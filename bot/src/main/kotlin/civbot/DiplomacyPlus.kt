package civbot

import com.unciv.Constants
import com.unciv.logic.automation.civilization.NextTurnAutomation
import com.unciv.logic.civilization.AlertType
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.PopupAlert
import com.unciv.logic.civilization.diplomacy.CityStateFunctions
import com.unciv.logic.civilization.diplomacy.Demand
import com.unciv.logic.civilization.diplomacy.DiplomacyFlags
import com.unciv.logic.civilization.diplomacy.DiplomaticModifiers
import com.unciv.logic.civilization.diplomacy.DiplomaticStatus
import com.unciv.logic.civilization.diplomacy.RelationshipLevel
import com.unciv.logic.map.tile.Tile
import com.unciv.logic.automation.civilization.TradeAutomation
import com.unciv.logic.trade.Trade
import com.unciv.logic.trade.TradeEvaluation
import com.unciv.logic.trade.TradeLogic
import com.unciv.logic.trade.TradeOffer
import com.unciv.logic.trade.TradeOfferType
import com.unciv.models.ruleset.tile.ResourceType
import com.unciv.models.ruleset.tile.TileImprovement
import com.unciv.models.ruleset.unique.TemporaryUnique
import com.unciv.models.ruleset.unique.UniqueType
import com.unciv.models.translations.fillPlaceholders
import com.unciv.view.GameView
import com.unciv.view.TradeView
import net.dv8tion.jda.api.interactions.components.ActionRow
import net.dv8tion.jda.api.interactions.components.buttons.Button
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Diplomacy gaps vs Unciv's DiplomacyScreen (ui/screens/diplomacyscreen):
 *  - ⚠️ confirm step for war (consequences like getDeclareWarButtonText) and for denouncing
 *  - the 10-turn peace lock after a declaration of war; automatic city-state peace
 *  - treaties on both sides of a deal + research-agreement gold top-up (OfferColumnsTable / TradeTable)
 *  - custom gold amounts; 🧭 Relations tab (opinion, promises, deals, world politics, 📢 demands)
 *  - city-state details (bonuses, quests, tribute willingness), improvement gifts, diplomatic marriage
 * Round 2 (audit vs Unciv): deals and peace offered to AIs wait in their tradeRequests and are decided on the AI's turn
 * (counteroffers land in the 📨 Inbox, "How about something else…" re-opens the deal builder), offers can be withdrawn,
 * human↔human proposals are saved in the game metadata, any resource amount, "✨ they lack this", 📜 all deals,
 * city-state actions re-checked on click (pledge/revoke confirmed), no war under DiplomaticRelationshipsCannotChange,
 * 📍 go to a civ's capital, ❗ city-states with quests for you.
 * The core Diplomacy / City-states tabs (Panel.kt) and trade code (Features.kt) call into this object.
 */
object DiplomacyPlus : Extension {
    private const val RELATIONS = "RELATIONS"
    private const val CONFIRM = "DP_CONFIRM"
    private const val PENDING = "dp_pending"
    private const val DEALS = "DP_DEALS"
    private const val IMPROVEMENT_GIFT_COST = 200
    private const val IMPROVEMENT_GIFT_INFLUENCE = 60

    /** A decision waiting for the ⚠️ confirm step. */
    private data class Pending(val kind: String, val civId: String, val back: String)

    override val tabs = listOf(
        ExtTab(RELATIONS, "Relations", "🧭", "What they think of you, promises, deals, demands, world politics") { civ ->
            civ.getKnownCivs().any { it.isMajorCiv && it.isAlive() }
        },
        // never in the tab list: opened by the War / Denounce buttons
        ExtTab(CONFIRM, "Confirm", "⚠️", "Confirm a serious decision") { false },
        // opened from 🧭 Relations (kept out of the tab list, which is nearly full)
        ExtTab(DEALS, "All deals", "📜", "Every deal with turns left, and offers waiting both ways") { false },
    )
    override val viewVerbs = setOf("dp_war", "dp_denounce", "dp_cancel", "dp_gold", "dp_gpt", "dp_res", "dp_csask", "dp_goto")
    override val prompts = mapOf(
        "dp_gold" to Prompt("Custom gold amount", "How much gold?", "a whole number, e.g. 120"),
        "dp_gpt" to Prompt("Custom gold per turn", "How much gold per turn?", "a whole number, e.g. 3"),
        "dp_res" to Prompt("How many?", "Amount (0 removes it)", "a whole number, e.g. 3"),
    )

    // ============================================================== war / peace / denounce

    /** Mod unique "Diplomatic relationships cannot change": Unciv hides every war button. */
    fun warBlockedByRules(civ: Civilization): String? =
        if (civ.gameInfo.ruleset.modOptions.hasUnique(UniqueType.DiplomaticRelationshipsCannotChange))
            "Diplomatic relationships can't change in this game, so war can't be declared." else null

    fun warLabel(civ: Civilization, other: Civilization): String {
        if (warBlockedByRules(civ) != null) return "War (not allowed)"
        val n = civ.getDiplomacyManager(other)?.turnsToPeaceTreaty() ?: 0
        return if (n > 0) "War ($n⏳)" else "War"
    }

    /** Turns until peace may be negotiated after a declaration of war (Unciv: DiplomacyFlags.DeclaredWar on their side). */
    fun peaceLock(civ: Civilization, other: Civilization): Int = other.getDiplomacyManager(civ)?.getFlag(DiplomacyFlags.DeclaredWar) ?: 0

    fun peaceLabel(civ: Civilization, other: Civilization): String {
        val n = peaceLock(civ, other)
        return if (n > 0) "Peace ($n⏳)" else "Peace"
    }

    /** Why peace can't be made right now, or null if it can. */
    fun peaceBlocker(civ: Civilization, other: Civilization): String? {
        if (civ.getDiplomacyManager(other) == null) return "You haven't met them."
        if (!civ.isAtWarWith(other)) return "You're not at war with ${other.civName}."
        if (civ.gameInfo.ruleset.modOptions.hasUnique(UniqueType.DiplomaticRelationshipsCannotChange))
            return "Diplomatic relationships can't change in this game."
        val n = peaceLock(civ, other)
        if (n > 0) return "War was declared too recently — peace talks with ${other.civName} open in $n turn(s)."
        if (other.isCityState) {
            val ally = other.allyCiv
            if (ally != null && ally != civ && civ.knows(ally) && ally.isAtWarWith(civ))
                return "${other.civName} is allied with ${ally.civName}, and you're at war with them — make peace with ${ally.civName} first."
        }
        return null
    }

    /** City-states accept peace right away (Unciv's getNegotiatePeaceCityStateButton: no AI evaluation). */
    fun cityStatePeace(civ: Civilization, cs: Civilization): String {
        peaceBlocker(civ, cs)?.let { throw PlayerError(it) }
        val tl = TradeLogic(civ, cs)
        tl.currentTrade.ourOffers.add(TradeOffer(Constants.peaceTreaty, TradeOfferType.Treaty, speed = civ.gameInfo.speed))
        tl.currentTrade.theirOffers.add(TradeOffer(Constants.peaceTreaty, TradeOfferType.Treaty, speed = civ.gameInfo.speed))
        tl.acceptTrade()
        return "🕊️ Peace signed between ${civ.civName} and ${cs.civName}."
    }

    /** Unciv's DiplomacyScreen.getDeclareWarButtonText, plus treaty turns and the minimum war length. */
    fun warConsequences(civ: Civilization, other: Civilization): List<String> {
        val out = ArrayList<String>()
        val dm = civ.getDiplomacyManager(other) ?: return listOf("You haven't met them.")
        val treaty = dm.turnsToPeaceTreaty()
        if (treaty > 0) out += "🕊️ A peace treaty forbids war for another **$treaty** turn(s)."
        if (other.getDiplomacyManager(civ)?.hasFlag(DiplomacyFlags.AgreedToNotAttackUs) == true)
            out += "💔 This will break your promise to not attack them. Other leaders will view this unfavorably."
        val theirPacts = other.diplomacy.values.filter {
            it.otherCiv != civ && it.diplomaticStatus == DiplomaticStatus.DefensivePact && !it.otherCiv.isAtWarWith(civ)
        }.map { it.otherCiv }
        for (c in theirPacts) out += "🛡️ " + (if (civ.knows(c)) "**${c.civName}**" else "An unknown civilization") + " will also join them in the war."
        for (d in civ.diplomacy.values) if (d.otherCiv != other && d.diplomaticStatus == DiplomaticStatus.DefensivePact && d.otherCiv !in theirPacts)
            out += "📜 This will cancel your defensive pact with **${d.otherCiv.civName}**."
        if (dm.hasFlag(DiplomacyFlags.DeclarationOfFriendship)) out += "💛 This ends your Declaration of Friendship."
        if (dm.hasFlag(DiplomacyFlags.ResearchAgreement)) out += "🔬 This cancels your research agreement (the gold is lost)."
        if (other.isCityState) {
            val protectors = other.cityStateFunctions.getProtectorCivs().filter { it != civ }
            if (protectors.isNotEmpty()) out += "🛡️ Its protectors (${protectors.joinToString { if (civ.knows(it)) it.civName else "unknown" }}) will be angry."
        }
        if (treaty == 0) out += "⏳ No peace talks for **${civ.gameInfo.ruleset.modOptions.constants.minimumWarDuration}** turns after declaring."
        return out
    }

    // ============================================================== trade

    /** "Custom amount…" option for gold / gold per turn (opens a popup, see [prompts]). */
    fun customAmountChoice(side: String, o: TradeOffer): Choice =
        Choice("ask:${if (o.type == TradeOfferType.Gold) "dp_gold" else "dp_gpt"}:$side", "Custom amount…",
            "type any amount up to ${o.amount}" + if (o.type == TradeOfferType.Gold_Per_Turn) " per turn" else "", "✏️")

    /** Offers Unciv's trade screen disables: peace while the post-war lock runs, research agreements nobody can pay for. */
    fun offerAllowed(civ: Civilization, other: Civilization, o: TradeOffer): Boolean = when {
        o.type == TradeOfferType.Treaty && o.name == Constants.peaceTreaty -> peaceLock(civ, other) == 0
        o.type == TradeOfferType.Treaty && o.name == Constants.researchAgreement ->
            civ.gold + other.gold > civ.diplomacyFunctions.getResearchAgreementCost(other) * 2
        else -> true
    }

    /** Unciv's OfferColumnsTable.addOffer puts treaties on both sides; TradeTable tops up gold for research agreements. */
    fun completeTreaties(tl: TradeLogic) {
        val ours = tl.currentTrade.ourOffers
        val theirs = tl.currentTrade.theirOffers
        for (o in ours.filter { it.type == TradeOfferType.Treaty }) if (theirs.none { it.type == o.type && it.name == o.name }) theirs.add(o.copy())
        for (o in theirs.filter { it.type == TradeOfferType.Treaty }) if (ours.none { it.type == o.type && it.name == o.name }) ours.add(o.copy())
        if (ours.none { it.name == Constants.researchAgreement }) return
        val civ = tl.ourCivilization; val other = tl.otherCivilization
        val cost = civ.diplomacyFunctions.getResearchAgreementCost(other)
        val ourGold = ours.firstOrNull { it.type == TradeOfferType.Gold }?.amount ?: 0
        val theirGold = theirs.firstOrNull { it.type == TradeOfferType.Gold }?.amount ?: 0
        val ourAfter = civ.gold + theirGold - cost
        val theirAfter = other.gold + ourGold - cost
        if (ourAfter < 0) theirs.add(TradeOffer(Constants.flatGold, TradeOfferType.Gold, -ourAfter, civ.gameInfo.speed))
        if (theirAfter < 0) ours.add(TradeOffer(Constants.flatGold, TradeOfferType.Gold, -theirAfter, civ.gameInfo.speed))
    }

    private fun setCustomAmount(ctx: ActionCtx, perTurn: Boolean) {
        ctx.requireTurn()
        val side = ctx.args.firstOrNull()?.takeIf { it == "g" || it == "a" } ?: throw PlayerError("Pick the side first.")
        val other = ctx.st.otherCivId?.let { ctx.game.getCivilization(it) } ?: throw PlayerError("Choose a civilization first.")
        val amount = ctx.value?.trim()?.toIntOrNull() ?: throw PlayerError("Type a whole number, like 120.")
        val type = if (perTurn) TradeOfferType.Gold_Per_Turn else TradeOfferType.Gold
        val tl = TradeLogic(ctx.civ, other)
        val offer = (if (side == "g") tl.ourAvailableOffers else tl.theirAvailableOffers).firstOrNull { it.type == type }
            ?: throw PlayerError("Gold can't be traded with them.")
        val unit = if (perTurn) "gold per turn" else "gold"
        val who = if (side == "g") "You have" else "${other.civName} has"
        if (amount < 0 || amount > offer.amount) throw PlayerError("Pick an amount between 0 and ${offer.amount} — $who ${offer.amount} $unit.")
        val prefix = "${side}t~${type.name}~"
        val keep = (if (side == "g") ctx.st.give else ctx.st.ask).filter { !it.startsWith(prefix) }
        val picks = if (amount == 0) keep else keep + "$prefix$amount~${offer.name}"
        if (side == "g") ctx.st.give = picks else ctx.st.ask = picks
        ctx.flash += if (amount == 0) "✏️ Removed the $unit from the deal." else "✏️ ${if (side == "g") "You give" else "You ask for"} **$amount $unit**."
    }

    // ============================================================== offers to AIs: decided on their turn

    /** Unciv's TradeTable "Offer trade (They'll decide on their turn)" = TradeView.tryProposeStagedTrade: the deal goes to the
     *  AI's tradeRequests, and on the AI's turn TradeAutomation.respondToTradeRequests accepts it, makes a counteroffer
     *  (TradeAutomation.getCounteroffer -> your 📨 Inbox) or declines it (a notification). One pending offer per civ, like Unciv. */
    fun offerToAI(tl: TradeLogic): String {
        if (!Features.tradeIsValid(tl)) throw PlayerError("That trade isn't possible any more — pick again.")
        val civ = tl.ourCivilization; val other = tl.otherCivilization
        val tv = TradeView(civ, other, GameView(civ.gameInfo, civ))
        val replaced = tv.hasPendingOfferFromUs()
        if (replaced) tv.tryRetractOffer() // Unciv: changing the staged deal retracts the pending one
        tv.setStagedTrade(tl.currentTrade)
        if (!tv.tryProposeStagedTrade()) throw PlayerError("Pick something to trade first.")
        val what = if (tl.currentTrade.isPeaceTreaty()) "🕊️ Peace offer" else "📨 Offer"
        return "$what sent to ${other.civName}: ${Features.describeTrade(tl)} — they'll decide on their turn " +
            "(their answer shows in your 📰 News, a counteroffer in the 📨 Inbox; withdraw it from 🧭 Relations → 📜 All deals)." +
            if (replaced) " It replaces your earlier offer." else ""
    }

    /** Our deal waiting in [other]'s trade requests (TradeView.hasPendingOfferFromUs), described from our side. */
    fun pendingOfferText(civ: Civilization, other: Civilization): String? {
        val r = other.tradeRequests.firstOrNull { it.requestingCiv == civ.civID } ?: return null
        val tl = TradeLogic(civ, other); tl.currentTrade.set(r.trade.reverse())
        return Features.describeTrade(tl)
    }

    /** TradeView.tryRetractOffer */
    fun retractOffer(civ: Civilization, other: Civilization): Boolean = TradeView(civ, other, GameView(civ.gameInfo, civ)).tryRetractOffer()

    /** TradePopup's "How about something else...": the deal builder (🤝 Diplomacy tab) pre-filled with their offer. */
    fun counterInBuilder(ctx: ActionCtx, other: Civilization, trade: Trade) {
        if (other.isCityState || ctx.civ.getDiplomacyManager(other) == null) throw PlayerError("You can't trade with ${other.civName}.")
        ctx.st.otherCivId = other.civID
        ctx.st.give = trade.ourOffers.map { pick("g", it.type, it.amount, it.name) }.distinct()
        ctx.st.ask = trade.theirOffers.map { pick("a", it.type, it.amount, it.name) }.distinct()
        ctx.st.tab = Tab.DIPLOMACY.name
        ctx.flash += "✏️ ${other.civName}'s offer is in the deal builder: change what you give or ask for, then press 🤝 **Propose deal** to send your counteroffer."
    }

    // ============================================================== resource amounts

    private fun pick(side: String, type: TradeOfferType, amount: Int, name: String) = side + "t~${type.name}~$amount~$name".take(100)

    /** Luxury / strategic / stockpiled resources in the deal lists: ×1 (and ×all for strategic), a ✏️ "choose an amount" popup
     *  for any amount 1..N, and Unciv's green highlight (OffersListScroll) for what the other side doesn't have. */
    fun resourceChoices(side: String, o: TradeOffer, otherSide: List<TradeOffer>): List<Choice> {
        val out = ArrayList<Choice>()
        val lux = o.type == TradeOfferType.Luxury_Resource
        val kind = when (o.type) {
            TradeOfferType.Luxury_Resource -> "luxury resource"; TradeOfferType.Strategic_Resource -> "strategic resource"; else -> "stockpiled resource"
        }
        val owner = if (side == "g") "you have" else "they have"
        val lacks = o.type != TradeOfferType.Stockpiled_Resource && otherSide.none { it.type == o.type && it.name == o.name && it.amount >= 0 }
        val mark = if (!lacks) "" else if (side == "g") " · ✨ they lack this" else " · ✨ you lack this"
        val amounts = if (lux) listOf(1) else listOf(1, o.amount).distinct()
        for (n in amounts) out += Choice(pick(side, o.type, n, o.name), "${o.name} ×$n", "$kind ($owner ${o.amount})$mark", if (lux) "💎" else "⛏️")
        if (o.amount > 1) out += Choice("ask:dp_res:$side~${o.type.name}~${o.name}".take(100), "${o.name}: choose an amount…",
            "any amount from 1 to ${o.amount}$mark", "✏️")
        return out
    }

    private fun setResourceAmount(ctx: ActionCtx) {
        ctx.requireTurn()
        val parts = ctx.args.firstOrNull()?.split("~", limit = 3)?.takeIf { it.size == 3 && (it[0] == "g" || it[0] == "a") }
            ?: throw PlayerError("Pick the resource first.")
        val (side, typeName, name) = parts
        val type = TradeOfferType.entries.firstOrNull { it.name == typeName } ?: throw PlayerError("Unknown resource.")
        val other = ctx.st.otherCivId?.let { id -> ctx.game.civilizations.firstOrNull { it.civID == id } } ?: throw PlayerError("Choose a civilization first.")
        val amount = ctx.value?.trim()?.toIntOrNull() ?: throw PlayerError("Type a whole number, like 3.")
        val tl = TradeLogic(ctx.civ, other)
        val offer = (if (side == "g") tl.ourAvailableOffers else tl.theirAvailableOffers)
            .firstOrNull { it.type == type && it.name == name && it.isTradable() } ?: throw PlayerError("$name can't be traded right now.")
        val who = if (side == "g") "You have" else "${other.civName} has"
        if (amount < 0 || amount > offer.amount) throw PlayerError("Pick an amount between 1 and ${offer.amount} (0 removes it) — $who ${offer.amount} $name.")
        val prefix = "${side}t~${type.name}~"
        val keep = (if (side == "g") ctx.st.give else ctx.st.ask).filter { !(it.startsWith(prefix) && it.removePrefix(prefix).substringAfter("~") == name) }
        val picks = if (amount == 0) keep else keep + pick(side, type, amount, name)
        if (side == "g") ctx.st.give = picks else ctx.st.ask = picks
        ctx.flash += if (amount == 0) "✏️ Removed $name from the deal." else "✏️ ${if (side == "g") "You give" else "You ask for"} **$name ×$amount**."
    }

    // ============================================================== human <-> human proposals, kept in the game metadata

    private const val K_PROPOSALS = "dp_proposals"
    private const val F_SEP = '\u001F'
    private const val R_SEP = '\u001E'
    private const val L_SEP = '\u001D'
    private fun safe(x: String) = x.replace(F_SEP, ' ').replace(R_SEP, ' ').replace(L_SEP, ' ')

    /** Pending human↔human proposals (saved in meta.settings.extra, so the Accept/Decline buttons work after a restart). */
    fun storedProposals(meta: GameMeta): List<Controller.Proposal> {
        val raw = meta.settings.extra[K_PROPOSALS]?.takeIf { it.isNotEmpty() } ?: return emptyList()
        return raw.split(R_SEP).mapNotNull { rec ->
            val f = rec.split(F_SEP)
            if (f.size < 8) return@mapNotNull null
            fun list(x: String) = x.split(L_SEP).filter { it.isNotEmpty() }
            Controller.Proposal(f[1], f[2], f[3], f[4], list(f[5]), list(f[6]), f[7], id = f[0])
        }
    }

    private fun saveProposals(meta: GameMeta, list: List<Controller.Proposal>) {
        if (list.isEmpty()) { meta.settings.extra.remove(K_PROPOSALS); return }
        meta.settings.extra[K_PROPOSALS] = list.joinToString(R_SEP.toString()) { p ->
            listOf(p.id, p.gid, p.kind, p.fromCiv, p.toCiv, p.give.joinToString(L_SEP.toString()) { safe(it) },
                p.ask.joinToString(L_SEP.toString()) { safe(it) }, p.text).joinToString(F_SEP.toString()) { safe(it) }
        }
    }

    fun rememberProposal(meta: GameMeta, p: Controller.Proposal) = saveProposals(meta, storedProposals(meta).filter { it.id != p.id } + p)
    fun forgetProposal(meta: GameMeta, id: String) = saveProposals(meta, storedProposals(meta).filter { it.id != id })

    // ============================================================== city-states

    fun allyLine(civ: Civilization, cs: Civilization): String {
        val ally = cs.allyCiv ?: return "Ally: none"
        val name = if (ally != civ && !civ.knows(ally)) "an unknown civilization" else ally.civName
        return "Ally: **$name** with ${cs.getDiplomacyManager(ally)?.getInfluence()?.toInt() ?: 0} influence"
    }

    private fun improvableTiles(viewer: Civilization, cs: Civilization): List<Tile> = cs.cities.flatMap { it.getTiles() }.filter {
        val r = it.tileResource ?: return@filter false
        viewer.canSeeResource(r) && r.resourceType != ResourceType.Bonus && (it.improvement == null || !r.isImprovedBy(it.improvement!!))
    }

    private fun giftableImprovements(viewer: Civilization, cs: Civilization): List<Pair<Tile, TileImprovement>> {
        val imps = cs.gameInfo.ruleset.tileImprovements.values.filter { it.turnsToBuild != -1 }
        return improvableTiles(viewer, cs).flatMap { t ->
            imps.filter { t.tileResource!!.isImprovedBy(it.name) && t.improvementFunctions.canBuildImprovement(it, cs.state) }.map { t to it }
        }
    }

    /** Extra entries for the City-states actions dropdown ("do:" values come back to [handle]). */
    fun cityStateGiftChoices(civ: Civilization, cs: Civilization): List<Choice> {
        val out = ArrayList<Choice>()
        if (cs.cities.isEmpty()) return out
        val infl = cs.getDiplomacyManager(civ)?.getInfluence()?.toInt() ?: 0
        for ((t, imp) in giftableImprovements(civ, cs)) {
            val desc = if (infl < IMPROVEMENT_GIFT_INFLUENCE) "needs $IMPROVEMENT_GIFT_INFLUENCE influence (you have $infl)" else "built at once, improves their ${t.resource}"
            out += Choice("do:dp_cs:imp~${t.position.x},${t.position.y}~${imp.name}", "Gift ${imp.name} on ${t.resource} — $IMPROVEMENT_GIFT_COST gold", desc, "🛠️")
        }
        if (civ.hasUnique(UniqueType.CityStateCanBeBoughtForGold)) {
            val f = cs.cityStateFunctions
            out += Choice("do:dp_cs:marry", "Diplomatic Marriage — ${f.getDiplomaticMarriageCost()} gold", marriageHint(civ, cs), "💍")
        }
        return out
    }

    private fun marriageHint(civ: Civilization, cs: Civilization): String {
        val f = cs.cityStateFunctions
        val cooldown = civ.getDiplomacyManager(cs)?.getFlag(DiplomacyFlags.MarriageCooldown) ?: 0
        return when {
            f.canBeMarriedBy(civ) -> "its cities join you as puppets"
            cs.allyCiv != civ -> "they must be your ally first"
            civ.getDiplomacyManager(cs)?.hasFlag(DiplomacyFlags.MarriageCooldown) == true -> "stay allied ${maxOf(cooldown, 1)} more turn(s) first"
            civ.gold < f.getDiplomaticMarriageCost() -> "you need ${f.getDiplomaticMarriageCost()} gold"
            else -> "not possible right now"
        }
    }

    private fun doCityStateGift(ctx: ActionCtx) {
        ctx.requireTurn()
        val civ = ctx.civ
        val cs = ctx.st.cityStateId?.let { ctx.game.getCivilization(it) }?.takeIf { it.isCityState && it.isAlive() } ?: throw PlayerError("Choose a city-state first.")
        val arg = ctx.args.firstOrNull() ?: throw PlayerError("Unknown action.")
        if (arg == "marry") {
            val f = cs.cityStateFunctions
            if (!civ.hasUnique(UniqueType.CityStateCanBeBoughtForGold)) throw PlayerError("Only civilizations with that ability can arrange a Diplomatic Marriage.")
            if (!f.canBeMarriedBy(civ)) throw PlayerError("Diplomatic Marriage with ${cs.civName} isn't possible yet: ${marriageHint(civ, cs)}.")
            val newCities = cs.cities.toList()
            f.diplomaticMarriage(civ)
            for (city in newCities) civ.popupAlerts.add(PopupAlert(AlertType.DiplomaticMarriage, city.id)) // annex or keep as puppet (📨 Inbox)
            ctx.st.cityStateId = null
            ctx.flash += "💍 You married into the ruling family of ${cs.civName}: ${newCities.joinToString { it.name }} now belong to you as puppets (annex them from the 📨 Inbox)."
            ctx.s.meta.addEvent("💍 ${civ.civName} married into the ruling family of ${cs.civName}.")
        } else {
            val parts = arg.split("~")
            if (parts.size != 3 || parts[0] != "imp") throw PlayerError("Unknown action.")
            val (x, y) = parts[1].split(",").map { it.toInt() }
            val tile = ctx.game.tileMap[x, y]
            val imp = ctx.game.ruleset.tileImprovements[parts[2]] ?: throw PlayerError("Unknown improvement.")
            if (giftableImprovements(civ, cs).none { it.first == tile && it.second == imp }) throw PlayerError("That improvement can't be built there any more.")
            val infl = cs.getDiplomacyManager(civ)!!.getInfluence()
            if (infl < IMPROVEMENT_GIFT_INFLUENCE) throw PlayerError("You need at least $IMPROVEMENT_GIFT_INFLUENCE influence with ${cs.civName} (you have ${infl.toInt()}).")
            if (civ.gold < IMPROVEMENT_GIFT_COST) throw PlayerError("You need $IMPROVEMENT_GIFT_COST gold.")
            civ.addGold(-IMPROVEMENT_GIFT_COST)
            tile.stopWorkingOnImprovement()
            tile.setImprovement(imp, cs)
            cs.cache.updateCivResources()
            ctx.flash += "🛠️ Built a **${imp.name}** on ${cs.civName}'s ${tile.resource} for $IMPROVEMENT_GIFT_COST gold."
        }
        ctx.changed = true
    }

    /** ❗ in the city-state list when it has a quest for you (Unciv's DiplomacyScreen quest icon). */
    fun questMark(civ: Civilization, cs: Civilization): String? =
        if (runCatching { cs.questManager.haveQuestsFor(civ) }.getOrDefault(false)) "❗ quest for you" else null

    fun pledgeBlocker(civ: Civilization, cs: Civilization): String? {
        if (cs.cityStateFunctions.otherCivCanPledgeProtection(civ)) return null
        val dm = cs.getDiplomacyManager(civ) ?: return "You haven't met ${cs.civName}."
        return when {
            civ.isAtWarWith(cs) -> "You're at war with ${cs.civName}."
            dm.diplomaticStatus == DiplomaticStatus.Protector -> "You already protect ${cs.civName}."
            dm.hasFlag(DiplomacyFlags.RecentlyWithdrewProtection) ->
                "You withdrew your protection recently — you can pledge again in ${dm.getFlag(DiplomacyFlags.RecentlyWithdrewProtection)} turn(s)."
            dm.getInfluence() < 0 -> "You need at least 0 influence with ${cs.civName} (you have ${dm.getInfluence().toInt()})."
            else -> "You can't pledge to protect ${cs.civName} right now."
        }
    }

    fun revokeBlocker(civ: Civilization, cs: Civilization): String? {
        if (cs.cityStateFunctions.otherCivCanWithdrawProtection(civ)) return null
        val dm = cs.getDiplomacyManager(civ) ?: return "You haven't met ${cs.civName}."
        return when {
            dm.diplomaticStatus != DiplomaticStatus.Protector -> "You don't protect ${cs.civName}."
            dm.hasFlag(DiplomacyFlags.RecentlyPledgedProtection) ->
                "You pledged recently — you can withdraw in ${dm.getFlag(DiplomacyFlags.RecentlyPledgedProtection)} turn(s)."
            else -> "You can't withdraw your protection of ${cs.civName} right now."
        }
    }

    fun tributeBlocker(civ: Civilization, cs: Civilization, worker: Boolean): String? {
        if (civ.isAtWarWith(cs)) return "You're at war with ${cs.civName}."
        val w = cs.cityStateFunctions.getTributeWillingness(civ, demandingWorker = worker)
        if (w < 0) return "${cs.civName} won't pay ${if (worker) "a worker" else "gold"} as tribute right now (willingness $w — it must be at least 0)."
        return null
    }

    /** The city-state actions, re-checked at click time like Unciv's disabled buttons (the panel may be stale), reporting real failures. */
    fun cityStateAction(civ: Civilization, cs: Civilization, id: String): String {
        if (!cs.isCityState || !cs.isAlive()) throw PlayerError("${cs.civName} isn't a city-state you can deal with any more.")
        val dm = cs.getDiplomacyManager(civ) ?: throw PlayerError("You haven't met ${cs.civName}.")
        val f = cs.cityStateFunctions
        return when {
            id.startsWith("gift:") -> {
                val g = id.removePrefix("gift:").toIntOrNull()?.takeIf { it > 0 } ?: throw PlayerError("Unknown gift.")
                if (civ.isAtWarWith(cs)) throw PlayerError("You're at war with ${cs.civName} — make peace before sending gifts.")
                if (civ.gold < g) throw PlayerError("Not enough gold: the gift is $g, you have ${civ.gold}.")
                val gain = f.influenceGainedByGift(civ, g)
                f.receiveGoldGift(civ, g)
                "🎁 Gave $g gold to ${cs.civName} (+$gain influence)."
            }
            id == "pledge" -> {
                pledgeBlocker(civ, cs)?.let { throw PlayerError(it) }
                f.addProtectorCiv(civ)
                if (dm.diplomaticStatus != DiplomaticStatus.Protector) throw PlayerError("Couldn't pledge to protect ${cs.civName}.")
                "🛡️ You now protect ${cs.civName}."
            }
            id == "revoke" -> {
                revokeBlocker(civ, cs)?.let { throw PlayerError(it) }
                f.removeProtectorCiv(civ)
                if (dm.diplomaticStatus == DiplomaticStatus.Protector) throw PlayerError("Couldn't withdraw your protection of ${cs.civName}.")
                "🚫 You no longer protect ${cs.civName} (-20 influence; you can't pledge again for 20 turns)."
            }
            id == "tribgold" -> {
                tributeBlocker(civ, cs, worker = false)?.let { throw PlayerError(it) }
                val before = civ.gold
                f.tributeGold(civ)
                "💰 ${cs.civName} paid ${civ.gold - before} gold in tribute (-15 influence)."
            }
            id == "tribworker" -> {
                tributeBlocker(civ, cs, worker = true)?.let { throw PlayerError(it) }
                if (cs.getCapital() == null || cs.gameInfo.ruleset.units.values.none { it.hasUnique(UniqueType.BuildImprovements) && it.isCivilian() && it.isBuildable(cs) })
                    throw PlayerError("${cs.civName} has no worker it could hand over.")
                val before = civ.units.getCivUnitsSize()
                f.tributeWorker(civ)
                if (civ.units.getCivUnitsSize() > before) "🔨 ${cs.civName} handed over a worker (-50 influence)."
                else "⚠️ ${cs.civName} gave in, but there was no free tile near its capital for the worker."
            }
            else -> throw PlayerError("Unknown action.")
        }
    }

    private fun askCityStateConfirm(ctx: ActionCtx) {
        ctx.requireTurn()
        val kind = ctx.args.firstOrNull()?.takeIf { it == "pledge" || it == "revoke" } ?: throw PlayerError("Unknown action.")
        val cs = ctx.st.cityStateId?.let { id -> ctx.game.civilizations.firstOrNull { it.civID == id } }?.takeIf { it.isCityState && it.isAlive() }
            ?: throw PlayerError("Choose a city-state first.")
        (if (kind == "pledge") pledgeBlocker(ctx.civ, cs) else revokeBlocker(ctx.civ, cs))?.let { throw PlayerError(it) }
        val back = if (ctx.st.tab == CONFIRM) Tab.CITYSTATES.name else ctx.st.tab
        ctx.st.ext[PENDING] = Pending(kind, cs.civID, back)
        ctx.st.tab = CONFIRM
    }

    /** Extra fields for the core City-states tab (Unciv's CityStateDiplomacyTable header, quests and tribute table). */
    fun cityStateFields(civ: Civilization, cs: Civilization): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        val dm = cs.getDiplomacyManager(civ) ?: return out
        val f = cs.cityStateFunctions
        // --- standing
        val s = ArrayList<String>()
        s += "Personality: **${cs.cityStatePersonality.name}**"
        val atWar = civ.isAtWarWith(cs)
        val influence = dm.getInfluence().toInt()
        when {
            atWar -> {}
            influence < 30 -> s += "Reach 30 influence for friendship."
            cs.allyCiv != civ -> s += "Reach the highest influence above 60 for alliance."
        }
        val level = dm.relationshipIgnoreAfraid()
        if (level >= RelationshipLevel.Friend) {
            val turns = dm.getTurnsToRelationshipChange()
            if (turns != 0) s += "Relationship changes in another **$turns** turn(s)."
        }
        val res = f.getCityStateResourcesForAlly().filter { it.resource.resourceType != ResourceType.Bonus }
        if (res.isNotEmpty()) s += "Resources for its ally: " + res.joinToString { "${it.resource.name} ${it.amount}" }
        cs.cityStateUniqueUnit?.let { u ->
            val techs = civ.gameInfo.ruleset.units[u]?.requiredTechs()?.toList().orEmpty()
            s += "Can give you a **$u**" + if (techs.isNotEmpty()) " once ${techs.joinToString(" and ")} ${if (techs.size == 1) "is" else "are"} researched." else "."
        }
        out += "🤝 Standing" to s.joinToString("\n").take(700)
        // --- friend / ally bonuses (the level you're at is ticked)
        val b = ArrayList<String>()
        for ((header, lvl) in listOf("When Friends:" to RelationshipLevel.Friend, "When Allies:" to RelationshipLevel.Ally)) {
            val bonuses = CityStateFunctions.getCityStateBonuses(cs.cityStateType, lvl).filterNot { it.isHiddenToUsers() }.toList()
            if (bonuses.isEmpty()) continue
            b += (if (level == lvl) "✅ **$header**" else "**$header**") + " " + bonuses.joinToString("; ") { GameOps.clean(it.getDisplayText()) }
        }
        if (b.isNotEmpty()) out += "🎁 Bonuses" to b.joinToString("\n").take(700)
        // --- quests (with rewards and turns) and "war against X" requests
        val q = ArrayList<String>()
        for (aq in cs.questManager.getAssignedQuestsFor(civ)) {
            val quest = aq.quest
            var line = "• **${GameOps.clean(quest.name)}**" + (if (quest.influence > 0) " (+${quest.influence.toInt()} influence)" else "") +
                " — " + GameOps.clean(aq.getDescription())
            if (quest.duration > 0) line += " · ${aq.getRemainingTurns()} turns left"
            if (quest.isGlobal()) cs.questManager.getScoreStringForGlobalQuest(aq).takeIf { it.isNotBlank() }?.let { line += " · " + GameOps.clean(it) }
            q += line
        }
        for (target in cs.getKnownCivs().filter { cs.questManager.isWarWithMajorActive(it) && it != civ }) {
            val progress = if (civ.knows(target)) "you have killed ${cs.questManager.unitsKilledSoFar(target, civ)}" else "you need to find them first"
            q += "• **War against ${target.civName}** — kill ${cs.questManager.unitsToKill(target)} of their military units ($progress)"
        }
        if (q.isNotEmpty()) out += "📜 Quests" to q.joinToString("\n").take(900)
        // --- tribute willingness (CityStateDiplomacyTable.getDemandTributeTable)
        if (!atWar) {
            val mods = f.getTributeModifiers(civ, requireWholeList = true)
            val text = mods.entries.joinToString(" · ") { "${GameOps.clean(it.key)} ${if (it.value > 0) "+" else ""}${it.value}" }
            out += "💰 Tribute willingness: ${mods.values.sum()}" to
                ("$text\n_At least 0 to take gold, at least 30 and a size-4 city for a worker._").take(700)
        }
        return out
    }

    // ============================================================== 🧭 relations tab

    private fun others(civ: Civilization) = GameOps.knownMajorCivs(civ).filter { !it.isCityState }

    private fun selectedOther(st: PanelState, civ: Civilization): Civilization? {
        val list = others(civ)
        val o = list.firstOrNull { it.civID == st.otherCivId } ?: list.firstOrNull()
        if (o?.civID != st.otherCivId) { st.give = emptyList(); st.ask = emptyList() }
        st.otherCivId = o?.civID
        return o
    }

    private fun signed(v: Int) = if (v > 0) "+$v" else "$v"

    /** Why they like or dislike you (MajorCivDiplomacyTable.getDiplomacyModifiersTable). */
    fun opinionText(civ: Civilization, other: Civilization): String {
        val theirs = other.getDiplomacyManager(civ) ?: return "You haven't met them."
        if (other.isHuman()) {
            val rel = when {
                theirs.diplomaticStatus == DiplomaticStatus.DefensivePact -> "Defensive Pact"
                theirs.hasFlag(DiplomacyFlags.DeclarationOfFriendship) -> "Friend"
                theirs.diplomaticStatus == DiplomaticStatus.War -> "Enemy"
                else -> "Neutral"
            }
            return "Our relationship: **$rel** — ${other.civName} is a human player and makes their own choices."
        }
        val lines = ArrayList<String>()
        lines += "Our relationship: **${theirs.relationshipLevel().name}** (opinion ${signed(theirs.opinionOfOtherCiv().roundToInt())})"
        for ((key, value) in theirs.diplomaticModifiers.entries.sortedByDescending { abs(it.value) }) {
            if (key == DiplomaticModifiers.AttackedProtectedMinor.name && theirs.hasModifier(DiplomaticModifiers.DestroyedProtectedMinor)) continue
            val m = DiplomaticModifiers.safeValueOf(key) ?: continue
            val v = value.roundToInt()
            lines += "${if (v > 5) "🟢" else if (v < -5) "🔴" else "⚪"} ${signed(v)} ${m.text}"
        }
        if (lines.size == 1) lines += "_They have no strong feelings about you yet._"
        return lines.joinToString("\n")
    }

    /** Promises (MajorCivDiplomacyTable.getPromisesTable) and demands still waiting for an answer. */
    fun promisesText(civ: Civilization, other: Civilization): String {
        val ours = civ.getDiplomacyManager(other) ?: return ""
        val theirs = other.getDiplomacyManager(civ) ?: return ""
        val lines = ArrayList<String>()
        for (d in Demand.entries) {
            if (theirs.hasFlag(d.agreedToDemand)) lines += "🤞 " + GameOps.clean(d.wePromisedText.fillPlaceholders(theirs.getFlag(d.agreedToDemand).toString()))
            if (ours.hasFlag(d.agreedToDemand)) lines += "🤝 " + GameOps.clean(d.theyPromisedText.fillPlaceholders(ours.getFlag(d.agreedToDemand).toString()))
            if (other.popupAlerts.any { it.type == d.demandAlert && it.value == civ.civID }) lines += "⏳ You demanded: _${d.demandText}_ — waiting for their answer."
        }
        return lines.joinToString("\n")
    }

    private fun offerText(o: TradeOffer): String {
        val base = when (o.type) {
            TradeOfferType.Gold_Per_Turn -> "${o.amount} gold/turn"
            TradeOfferType.Luxury_Resource, TradeOfferType.Strategic_Resource -> "${o.name} ×${o.amount}"
            else -> GameOps.clean(o.name)
        }
        return base + if (o.duration > 0) " (${o.duration}⏳)" else ""
    }

    /** Active deals with turns left (dm.trades), plus timed treaties and flags. */
    fun dealsText(civ: Civilization, other: Civilization): String {
        val dm = civ.getDiplomacyManager(other) ?: return ""
        val theirs = other.getDiplomacyManager(civ)
        val lines = ArrayList<String>()
        for (tr in dm.trades) {
            val ours = tr.ourOffers.filter { it.duration > 0 }
            val their = tr.theirOffers.filter { it.duration > 0 }
            if (ours.isEmpty() && their.isEmpty()) continue
            lines += "• You give ${ours.joinToString { offerText(it) }.ifEmpty { "—" }} · they give ${their.joinToString { offerText(it) }.ifEmpty { "—" }}"
        }
        if (dm.hasFlag(DiplomacyFlags.DeclarationOfFriendship)) lines += "💛 Declaration of Friendship: ${dm.getFlag(DiplomacyFlags.DeclarationOfFriendship)} turns left"
        if (dm.hasFlag(DiplomacyFlags.ResearchAgreement)) lines += "🔬 Research agreement: ${dm.getFlag(DiplomacyFlags.ResearchAgreement)} turns left"
        if (dm.hasFlag(DiplomacyFlags.DefensivePact)) lines += "🛡️ Defensive pact: ${dm.getFlag(DiplomacyFlags.DefensivePact)} turns left"
        if (dm.hasFlag(DiplomacyFlags.Denunciation)) lines += "😠 You denounced them: ${dm.getFlag(DiplomacyFlags.Denunciation)} turns left"
        if (theirs?.hasFlag(DiplomacyFlags.Denunciation) == true) lines += "😠 They denounced you: ${theirs.getFlag(DiplomacyFlags.Denunciation)} turns left"
        if (dm.hasOpenBorders) lines += "🚪 Open borders"
        if (civ.isAtWarWith(other) && peaceLock(civ, other) > 0) lines += "⚔️ No peace talks for ${peaceLock(civ, other)} more turn(s)"
        return lines.joinToString("\n")
    }

    /** Who is at war with whom, friendships, pacts and denunciations among the civs you know. */
    fun politicsText(civ: Civilization): String {
        val known = (listOf(civ) + others(civ)).filter { it.isMajorCiv && it.isAlive() }
        val wars = ArrayList<String>(); val friends = ArrayList<String>(); val pacts = ArrayList<String>(); val denounced = ArrayList<String>()
        for ((i, a) in known.withIndex()) for (b in known.drop(i + 1)) {
            val d = a.getDiplomacyManager(b) ?: continue
            val pair = "${a.civName} – ${b.civName}"
            if (d.diplomaticStatus == DiplomaticStatus.War) wars += pair
            if (d.diplomaticStatus == DiplomaticStatus.DefensivePact) pacts += pair
            if (d.hasFlag(DiplomacyFlags.DeclarationOfFriendship)) friends += pair
            if (d.hasFlag(DiplomacyFlags.Denunciation)) denounced += "${a.civName} → ${b.civName}"
            if (b.getDiplomacyManager(a)?.hasFlag(DiplomacyFlags.Denunciation) == true) denounced += "${b.civName} → ${a.civName}"
        }
        val lines = ArrayList<String>()
        if (wars.isNotEmpty()) lines += "⚔️ At war: " + wars.joinToString(" · ")
        if (pacts.isNotEmpty()) lines += "🛡️ Defensive pacts: " + pacts.joinToString(" · ")
        if (friends.isNotEmpty()) lines += "💛 Friends: " + friends.joinToString(" · ")
        if (denounced.isNotEmpty()) lines += "😠 Denounced: " + denounced.joinToString(" · ")
        val csAllies = civ.getKnownCivs().filter { it.isCityState && it.isAlive() }.mapNotNull { it.allyCiv }
            .filter { it == civ || civ.knows(it) }.groupingBy { it.civName }.eachCount()
        if (csAllies.isNotEmpty()) lines += "🏰 City-state allies: " + csAllies.entries.joinToString(" · ") { "${it.key} (${it.value})" }
        return lines.joinToString("\n").ifEmpty { "Everyone you know is at peace, with no pacts or friendships." }
    }

    /** Demands you can make (MajorCivDiplomacyTable.getDemandsTable): shown, not already asked, not already promised. */
    fun demandChoices(civ: Civilization, other: Civilization): List<Choice> {
        val dm = civ.getDiplomacyManager(other) ?: return emptyList()
        return Demand.entries.filter { d ->
            d.show(civ) && other.popupAlerts.none { it.type == d.demandAlert && it.value == civ.civID } && !dm.hasFlag(d.agreedToDemand)
        }.map { d ->
            Choice("dp:d:${d.name}", GameOps.clean(d.demandText).take(100),
                if (other.isHuman()) "they answer in their 📨 Inbox" else "they answer on their turn", "📢")
        }
    }

    private fun makeDemand(ctx: ActionCtx) {
        ctx.requireTurn()
        val civ = ctx.civ
        val other = ctx.st.otherCivId?.let { ctx.game.getCivilization(it) }?.takeIf { !it.isCityState } ?: throw PlayerError("Choose a civilization first.")
        val name = ctx.value?.removePrefix("dp:d:") ?: throw PlayerError("Pick a demand.")
        val d = Demand.entries.firstOrNull { it.name == name } ?: throw PlayerError("Unknown demand.")
        if (demandChoices(civ, other).none { it.id == "dp:d:${d.name}" }) throw PlayerError("You can't make that demand of ${other.civName} right now.")
        other.popupAlerts.add(PopupAlert(d.demandAlert, civ.civID))
        ctx.flash += "📢 You told ${other.civName}: _${d.demandText}_ — " +
            if (other.isHuman()) "it's waiting in their 📨 Inbox." else "they'll answer on their turn."
        ctx.changed = true
    }

    private fun renderRelations(t: TabCtx) {
        val civ = t.civ
        t.includeAdvisor = false
        val other = selectedOther(t.st, civ)
        if (other == null) {
            t.eb.addField("🧭 Relations", "You haven't met any other civilization yet. Explore!", false)
            t.worldMap(); return
        }
        val who = t.s.meta.seatOfCiv(other.civID)?.let { " (<@${it.userId}>)" } ?: " (AI)"
        t.eb.addField("🧭 How ${other.civName} sees you", "${other.civName}$who\n${opinionText(civ, other)}".take(1024), false)
        promisesText(civ, other).takeIf { it.isNotBlank() }?.let { t.eb.addField("🤞 Promises", it.take(1024), false) }
        t.eb.addField("📜 Deals and treaties", dealsText(civ, other).ifEmpty { "No active deals." }.take(1024), false)
        val pend = outgoing(t.s, civ, other).map { it.text } + incoming(t.s, civ, other)
        if (pend.isNotEmpty()) t.eb.addField("⏳ Waiting for an answer", (pend.joinToString("\n").take(560) + "\n_Withdraw your offers from 📜 All deals._").take(700), false)
        t.eb.addField("🌍 World politics", politicsText(civ).take(1024), false)
        t.rows += ActionRow.of(t.select("selciv", "Choose a civilization…",
            others(civ).map { Choice("civ:${it.civID}", it.civName, GameOps.relationText(civ, it).take(100), if (civ.isAtWarWith(it)) "⚔️" else "🤝") },
            "civ:${other.civID}"))
        val demands = demandChoices(civ, other)
        t.rows += ActionRow.of(t.select("dp_demand", if (demands.isEmpty()) "📢 No demands to make right now" else "📢 Make a demand of ${other.civName}…", demands, enabled = t.myTurn))
        t.endRow += t.button("tab", "Trade & war", "🤝", 2, true, Tab.DIPLOMACY.name)
        t.endRow += t.button("tab", "All deals", "📜", 2, true, DEALS)
        t.endRow += t.button("dp_goto", "Go to on map", "📍", 2, capitalTile(civ, other) != null, other.civID)
        t.worldMap()
    }

    // ============================================================== ⚠️ confirm step

    private fun renderConfirm(t: TabCtx) {
        t.includeAdvisor = false
        val p = t.st.ext[PENDING] as? Pending
        val other = p?.let { runCatching { t.game.getCivilization(it.civId) }.getOrNull() }
        if (p == null || other == null) {
            t.eb.addField("⚠️ Confirm", "Nothing is waiting for confirmation.", false)
            t.endRow += t.button("dp_cancel", "Back", "↩️")
            t.worldMap(); return
        }
        when (p.kind) {
            "war" -> {
                val dm = t.civ.getDiplomacyManager(other)
                val blocked = dm == null || !dm.canDeclareWar() || warBlockedByRules(t.civ) != null
                t.eb.addField("⚠️ Declare war on ${other.civName}?", warConsequences(t.civ, other).joinToString("\n").take(1024), false)
                t.endRow += t.button("dp_ok", if (blocked) "Can't declare war" else "Declare war", "⚔️", 4, t.myTurn && !blocked)
            }
            "denounce" -> {
                val friendsOfThem = t.civ.getKnownCivs().filter { it.isMajorCiv && it != other && it.getDiplomacyManager(other)?.hasFlag(DiplomacyFlags.DeclarationOfFriendship) == true }.toList()
                val lines = ArrayList<String>()
                lines += "Denouncing lasts **30** turns, and ${other.civName} will like you much less."
                if (friendsOfThem.isNotEmpty()) lines += "Their friends (${friendsOfThem.joinToString { it.civName }}) won't like it either; their enemies will."
                t.eb.addField("⚠️ Denounce ${other.civName}?", lines.joinToString("\n"), false)
                t.endRow += t.button("dp_ok", "Denounce (30 turns)", "😠", 4, t.myTurn && Features.canDenounce(t.civ, other))
            }
            // CityStateDiplomacyTable's ConfirmPopups
            "pledge" -> {
                val lines = listOf("🛡️ Other civilizations that attack or bully ${other.civName} will anger you, and you'll be asked how to respond.",
                    "⏳ You can't withdraw the pledge for **10** turns.", pledgeBlocker(t.civ, other)?.let { "❌ $it" }).filterNotNull()
                t.eb.addField("⚠️ Declare protection of ${other.civName}?", lines.joinToString("\n"), false)
                t.endRow += t.button("dp_ok", "Pledge to protect", "🛡️", 3, t.myTurn && pledgeBlocker(t.civ, other) == null)
            }
            "revoke" -> {
                val lines = listOf("📉 You lose **20** influence with ${other.civName}.", "⏳ You can't pledge again for **20** turns.",
                    revokeBlocker(t.civ, other)?.let { "❌ $it" }).filterNotNull()
                t.eb.addField("⚠️ Revoke protection for ${other.civName}?", lines.joinToString("\n"), false)
                t.endRow += t.button("dp_ok", "Revoke protection", "🚫", 4, t.myTurn && revokeBlocker(t.civ, other) == null)
            }
        }
        t.endRow += t.button("dp_cancel", "Cancel", "↩️")
        other.getCapital()?.getCenterTile()?.takeIf { t.civ.hasExplored(it) }?.let { t.mapAround(it) } ?: t.worldMap()
    }

    private fun askConfirm(ctx: ActionCtx, kind: String) {
        val cs = ctx.args.firstOrNull() == "cs"
        val id = (if (cs) ctx.st.cityStateId else ctx.st.otherCivId) ?: throw PlayerError("Choose a civilization first.")
        val other = ctx.game.getCivilization(id)
        if (kind == "war" && ctx.civ.isAtWarWith(other)) throw PlayerError("You're already at war with ${other.civName}.")
        if (kind == "war") warBlockedByRules(ctx.civ)?.let { throw PlayerError(it) }
        if (kind == "denounce" && !Features.canDenounce(ctx.civ, other)) throw PlayerError("You can't denounce ${other.civName} right now.")
        val back = if (ctx.st.tab == CONFIRM) (ctx.st.ext[PENDING] as? Pending)?.back ?: Tab.DIPLOMACY.name else ctx.st.tab
        ctx.st.ext[PENDING] = Pending(kind, other.civID, back)
        ctx.st.tab = CONFIRM
    }

    private fun cancelConfirm(ctx: ActionCtx) {
        val p = ctx.st.ext.remove(PENDING) as? Pending
        if (ctx.st.tab == CONFIRM) ctx.st.tab = p?.back ?: Tab.DIPLOMACY.name
    }

    private fun confirm(ctx: ActionCtx) {
        ctx.requireTurn()
        val p = ctx.st.ext[PENDING] as? Pending ?: throw PlayerError("Nothing to confirm.")
        val other = ctx.game.getCivilization(p.civId)
        val msg = when (p.kind) {
            "war" -> GameOps.declareWar(ctx.civ, other)
            "denounce" -> Features.denounce(ctx.civ, other)
            "pledge", "revoke" -> Features.doCityState(ctx.civ, other, p.kind) // re-checked at click time
            else -> throw PlayerError("Nothing to confirm.")
        }
        ctx.st.ext.remove(PENDING)
        ctx.st.tab = p.back
        ctx.flash += msg; ctx.s.meta.addEvent(msg)
        ctx.changed = true
    }

    // ============================================================== 📜 all deals + pending offers both ways

    private class Pend(val text: String, val retract: Choice? = null)

    private fun proposalText(p: Controller.Proposal) = when (p.kind) {
        "peace" -> "🕊️ peace"; "friend" -> "💛 a Declaration of Friendship"; else -> "🤝 " + p.text
    }

    private fun civById(civ: Civilization, id: String) = civ.gameInfo.civilizations.firstOrNull { it.civID == id }

    /** Offers we made that are still waiting for an answer (to [only], or to everyone). */
    private fun outgoing(s: Session, civ: Civilization, only: Civilization? = null): List<Pend> {
        val out = ArrayList<Pend>()
        for (o in civ.gameInfo.civilizations) {
            if (o == civ || !o.isAlive() || (only != null && o != only)) continue
            pendingOfferText(civ, o)?.let {
                out += Pend("📨 To **${o.civName}**: $it — ${if (o.isHuman()) "in their 📨 Inbox" else "they decide on their turn"}",
                    Choice("dp_r:ai:${o.civID}", "Withdraw the offer to ${o.civName}".take(100), it.replace("*", "").take(100), "↩️"))
            }
            if (o.popupAlerts.any { it.type == AlertType.DeclarationOfFriendship && it.value == civ.civID })
                out += Pend("💛 Friendship offered to **${o.civName}** — waiting for their answer")
            for (d in Demand.entries) if (o.popupAlerts.any { it.type == d.demandAlert && it.value == civ.civID })
                out += Pend("📢 You demanded of **${o.civName}**: _${d.demandText}_")
        }
        for (p in storedProposals(s.meta)) {
            if (p.fromCiv != civ.civID || (only != null && p.toCiv != only.civID)) continue
            val to = civById(civ, p.toCiv) ?: continue
            out += Pend("📨 To **${to.civName}**: ${proposalText(p)} — waiting for their answer in the game channel",
                Choice("dp_r:h:${p.id}", "Withdraw the offer to ${to.civName}".take(100), proposalText(p).replace("*", "").take(100), "↩️"))
        }
        return out
    }

    /** Offers waiting for OUR answer (from [only], or from everyone). */
    private fun incoming(s: Session, civ: Civilization, only: Civilization? = null): List<String> {
        val out = ArrayList<String>()
        for (r in civ.tradeRequests) {
            val o = civById(civ, r.requestingCiv)?.takeIf { only == null || it == only } ?: continue
            val tl = TradeLogic(civ, o); tl.currentTrade.set(r.trade)
            out += "📨 From **${o.civName}**: ${Features.describeTrade(tl)} — answer in the 📨 Inbox"
        }
        for (a in civ.popupAlerts) {
            val o = civById(civ, a.value)?.takeIf { only == null || it == only } ?: continue
            if (a.type == AlertType.DeclarationOfFriendship) out += "💛 **${o.civName}** offers a Declaration of Friendship — answer in the 📨 Inbox"
            Demand.entries.firstOrNull { it.demandAlert == a.type }?.let { d -> out += "📢 **${o.civName}** demands: _${d.demandText}_ — answer in the 📨 Inbox" }
        }
        for (p in storedProposals(s.meta)) {
            if (p.toCiv != civ.civID || (only != null && p.fromCiv != only.civID)) continue
            val from = civById(civ, p.fromCiv) ?: continue
            out += "📨 **${from.civName}** offers ${proposalText(p)} — answer with the buttons in the game channel"
        }
        return out
    }

    private fun renderDeals(t: TabCtx) {
        val civ = t.civ
        t.includeAdvisor = false
        var budget = 3600
        var hidden = 0
        val known = civ.getKnownCivs().filter { it.isAlive() && !it.isBarbarian && !it.isSpectator }.sortedBy { it.isCityState }.toList()
        for (o in known) {
            val d = dealsText(civ, o).takeIf { it.isNotBlank() } ?: continue
            val v = d.take(600)
            if (t.eb.fields.size >= 16 || budget - v.length < 0) { hidden++; continue }
            budget -= v.length
            t.eb.addField("📜 ${o.civName}" + if (o.isCityState) " (city-state)" else "", v, false)
        }
        if (t.eb.fields.isEmpty()) t.eb.addField("📜 Current deals", "No deals or treaties with anyone right now.", false)
        else if (hidden > 0) t.eb.addField("📜 …", "$hidden more civilization(s) with deals — open 🧭 Relations for each.", false)
        val out = outgoing(t.s, civ)
        t.eb.addField("📤 Your offers waiting for an answer", out.joinToString("\n") { it.text }.ifEmpty { "None." }.take(1024), false)
        t.eb.addField("📥 Offers waiting for your answer", incoming(t.s, civ).joinToString("\n").ifEmpty { "None." }.take(1024), false)
        val retract = out.mapNotNull { it.retract }
        t.rows += ActionRow.of(t.select("dp_retract", if (retract.isEmpty()) "↩️ No offers to withdraw" else "↩️ Withdraw an offer…", retract, enabled = t.myTurn))
        t.endRow += t.button("tab", "Relations", "🧭", 2, true, RELATIONS)
        t.endRow += t.button("tab", "Trade & war", "🤝", 2, true, Tab.DIPLOMACY.name)
        t.worldMap()
    }

    private fun retract(ctx: ActionCtx) {
        ctx.requireTurn()
        val v = ctx.value?.removePrefix("dp_r:") ?: throw PlayerError("Pick an offer.")
        when {
            v.startsWith("ai:") -> {
                val o = civById(ctx.civ, v.removePrefix("ai:")) ?: throw PlayerError("Unknown civilization.")
                if (!retractOffer(ctx.civ, o)) throw PlayerError("There's no offer to ${o.civName} to withdraw — they may have answered already.")
                ctx.flash += "↩️ You withdrew your offer to ${o.civName}."
            }
            v.startsWith("h:") -> {
                val id = v.removePrefix("h:")
                val p = storedProposals(ctx.s.meta).firstOrNull { it.id == id && it.fromCiv == ctx.civ.civID }
                    ?: throw PlayerError("That offer isn't open any more — it was answered already.")
                forgetProposal(ctx.s.meta, id)
                ctx.flash += "↩️ You withdrew your offer to ${civById(ctx.civ, p.toCiv)?.civName ?: "them"} — its buttons in the game channel no longer work."
            }
            else -> throw PlayerError("Pick an offer.")
        }
        ctx.changed = true
    }

    // ============================================================== 📍 go to on map

    /** Their capital, if we've seen it (Unciv shows "Go to on map" only then). */
    fun capitalTile(civ: Civilization, other: Civilization): Tile? = other.getCapital()?.getCenterTile()?.takeIf { civ.hasExplored(it) }

    private fun goToCapital(ctx: ActionCtx) {
        val id = ctx.args.firstOrNull() ?: ctx.st.otherCivId ?: throw PlayerError("Choose a civilization first.")
        val o = civById(ctx.civ, id) ?: throw PlayerError("Unknown civilization.")
        val tile = capitalTile(ctx.civ, o) ?: throw PlayerError("You don't know where ${o.civName}'s capital is yet.")
        ctx.st.mapCenter = tile.position; ctx.st.inspect = tile.position; ctx.st.wholeMap = false
        ctx.st.tab = Tab.MAP.name
        ctx.flash += "📍 The map is centred on ${o.civName}'s capital, **${o.getCapital()!!.name}**."
    }

    // ============================================================== extension hooks

    override fun renderTab(tabId: String, t: TabCtx) {
        when (tabId) {
            RELATIONS -> renderRelations(t)
            CONFIRM -> renderConfirm(t)
            DEALS -> renderDeals(t)
        }
    }

    override fun handle(verb: String, ctx: ActionCtx): Boolean {
        when (verb) {
            "dp_war" -> askConfirm(ctx, "war")
            "dp_denounce" -> askConfirm(ctx, "denounce")
            "dp_cancel" -> cancelConfirm(ctx)
            "dp_ok" -> confirm(ctx)
            "dp_demand" -> makeDemand(ctx)
            "dp_gold" -> setCustomAmount(ctx, perTurn = false)
            "dp_gpt" -> setCustomAmount(ctx, perTurn = true)
            "dp_cs" -> doCityStateGift(ctx)
            "dp_res" -> setResourceAmount(ctx)
            "dp_csask" -> askCityStateConfirm(ctx)
            "dp_retract" -> retract(ctx)
            "dp_goto" -> goToCapital(ctx)
            else -> return false
        }
        return true
    }

    // ============================================================== self-test

    override fun selfTest(t: SelfTestCtx) {
        val a = t.alice; val b = t.bob
        val gid = t.s.meta.id
        fun st() = t.ctl.state(gid, a.userId)
        fun check(ok: Boolean, what: String) { if (!ok) throw IllegalStateException("DiplomacyPlus: $what") }
        fun act(verb: String, value: String? = null, feature: String? = null, args: List<String> = emptyList()) = t.act(a, verb, args, value, feature)
        /** record a ✓ for something verified directly (a refusal, a computed text) */
        fun ok(feature: String) { t.act(a, "refresh", emptyList(), null, feature) }
        fun flash(tag: String) { println("  [dp $tag] " + st().flash.joinToString(" / ")); st().flash.clear() }
        fun buttons(): List<Button> = t.eng { Panel.render(t.s, a, st()) }.rows.flatMap { it.components }.filterIsInstance<Button>()
        fun render(tag: String) {
            t.render(a, tag)
            val r = t.eng { Panel.render(t.s, a, st()) }
            println("  [dp render $tag] embed ${r.embed.length} chars, rows ${r.rows.size}: " + r.rows.joinToString(" | ") { row -> row.components.joinToString { c ->
                (c as? Button)?.let { "[${it.label}${if (it.isDisabled) " (off)" else ""}]" } ?: "<menu>" } })
            r.embed.fields.forEach { println("    ## ${it.name}: " + it.value?.replace("\n", " / ")?.take(240)) }
            check(r.embed.length <= 6000 && r.embed.fields.all { (it.value?.length ?: 0) <= 1024 }, "embed too big on $tag")
        }
        fun atWar(x: String, y: String) = t.eng { g -> g.getCivilization(x).isAtWarWith(g.getCivilization(y)) }
        /** what runs on the AI's turn (NextTurnAutomation.automateCivMoves → TradeAutomation.respondToTradeRequests) */
        fun aiAnswers(id: String) = t.eng { g -> TradeAutomation.respondToTradeRequests(g.getCivilization(id), true) }
        fun pendingTo(id: String) = t.eng { g -> g.getCivilization(id).tradeRequests.count { it.requestingCiv == a.civId } }
        fun aliceNotes() = t.eng { g -> GameOps.civOf(g, a).notifications.map { it.text } }

        // ---- setup: everyone knows everyone, Alice at peace with all majors (no treaties), lots of gold
        val aiIds = t.eng { g ->
            val alice = GameOps.civOf(g, a); val bob = GameOps.civOf(g, b)
            val ais = g.civilizations.filter { it.isMajorCiv && it.isAlive() && !it.isHuman() }
            val all = ais + bob + alice
            for (x in all) for (y in all) if (x != y && !x.knows(y)) x.diplomacyFunctions.makeCivilizationsMeet(y)
            for (c in ais + bob) {
                if (alice.isAtWarWith(c)) GameOps.makePeace(alice, c)
                for ((x, y) in listOf(alice to c, c to alice)) x.getDiplomacyManager(y)!!.apply {
                    trades.clear(); removeFlag(DiplomacyFlags.DeclaredWar); diplomaticStatus = DiplomaticStatus.Peace
                }
            }
            alice.addGold(6000); bob.addGold(3000)
            ais.map { it.civID }
        }
        check(aiIds.size >= 3, "need 3 AI civs, have ${aiIds.size}")
        val (ai1, ai2, ai3) = aiIds
        val ai1Name = t.eng { g -> g.getCivilization(ai1).civName }
        val ai2Name = t.eng { g -> g.getCivilization(ai2).civName }

        // ---- 1. war: confirm step listing every consequence (Unciv's getDeclareWarButtonText)
        t.eng { g ->
            val alice = GameOps.civOf(g, a); val bob = GameOps.civOf(g, b)
            bob.getDiplomacyManager(alice)!!.setFlag(DiplomacyFlags.AgreedToNotAttackUs, 20)
            for ((x, y) in listOf(bob to g.getCivilization(ai1), alice to g.getCivilization(ai2))) {
                x.getDiplomacyManager(y)!!.diplomaticStatus = DiplomaticStatus.DefensivePact; y.getDiplomacyManager(x)!!.diplomaticStatus = DiplomaticStatus.DefensivePact
            }
        }
        act("tab", Tab.DIPLOMACY.name)
        act("selciv", "civ:${b.civId}")
        check(act("dp_war", feature = "⚠️ war: confirm step instead of instant war"), "dp_war")
        check(st().tab == CONFIRM, "confirm tab not shown")
        val cons = t.eng { g -> warConsequences(GameOps.civOf(g, a), GameOps.civOf(g, b)) }
        println("  [dp] war consequences: " + cons.joinToString(" | "))
        check(cons.any { it.contains("promise") }, "broken promise not listed")
        check(cons.any { it.contains(ai1Name) && it.contains("join") }, "defensive-pact ally joining not listed")
        check(cons.any { it.contains(ai2Name) && it.contains("cancel") }, "own pact cancellation not listed")
        ok("⚠️ war consequences: broken promise, pact allies joining, own pacts cancelled")
        render("dp-war-confirm")
        check(act("dp_cancel", feature = "⚠️ war: cancel keeps the peace"), "cancel")
        check(st().tab == Tab.DIPLOMACY.name && !atWar(a.civId, b.civId), "cancel declared war?")
        t.eng { g ->
            val alice = GameOps.civOf(g, a); val bob = GameOps.civOf(g, b)
            bob.getDiplomacyManager(alice)!!.removeFlag(DiplomacyFlags.AgreedToNotAttackUs)
            for ((x, y) in listOf(bob to g.getCivilization(ai1), alice to g.getCivilization(ai2))) {
                x.getDiplomacyManager(y)!!.diplomaticStatus = DiplomaticStatus.Peace; y.getDiplomacyManager(x)!!.diplomaticStatus = DiplomaticStatus.Peace
            }
        }
        act("dp_war")
        check(act("dp_ok", feature = "⚔️ declare war after confirming"), "dp_ok war")
        check(atWar(a.civId, b.civId), "war not declared")
        flash("war")

        // ---- peace lock: button, proposal and the human's answer all respect DiplomacyFlags.DeclaredWar
        val peaceBtn = buttons().firstOrNull { it.id?.endsWith("|peace") == true }
        println("  [dp] peace button: ${peaceBtn?.label} disabled=${peaceBtn?.isDisabled}")
        check(peaceBtn != null && peaceBtn.isDisabled && peaceBtn.label.contains("⏳"), "peace button should show the lock")
        check(!act("peace"), "peace proposal allowed during the lock")
        val lockMsg = t.eng { t.ctl.answer(t.s, Controller.Proposal(gid, "peace", a.civId, b.civId), true) }
        check(lockMsg.startsWith("❌") && atWar(a.civId, b.civId), "human answer ignored the lock: $lockMsg")
        ok("🕊️ peace locked for 10 turns after war (button, proposal, human answer)")
        render("dp-peace-locked")
        t.eng { g -> val alice = GameOps.civOf(g, a); val bob = GameOps.civOf(g, b)
            alice.getDiplomacyManager(bob)!!.removeFlag(DiplomacyFlags.DeclaredWar); bob.getDiplomacyManager(alice)!!.removeFlag(DiplomacyFlags.DeclaredWar) }
        check(act("peace", feature = "🕊️ peace proposal (human) once the lock is over"), "peace after lock")
        while (true) {
            val p = t.ctl.outbox.poll() ?: break
            println("  [dp] proposal ${p.kind}: " + t.eng { t.ctl.answer(t.s, p, accept = true) })
        }
        check(!atWar(a.civId, b.civId), "human peace not made")
        // the core scenario proposes peace right after declaring war, which the lock now refuses: cover its checklist lines here
        ok("peace proposal (human)"); ok("proposal answered (peace)")
        // now the peace treaty forbids war, and the confirm step says for how long
        act("dp_war")
        val treatyLines = t.eng { g -> warConsequences(GameOps.civOf(g, a), GameOps.civOf(g, b)) }
        check(treatyLines.first().contains("peace treaty") && !act("dp_ok"), "treaty should block war: $treatyLines")
        check(buttons().any { it.id?.contains("dp_ok") == true && it.isDisabled }, "confirm button should be disabled")
        ok("🕊️ peace treaty blocks war (turns left shown)")
        act("dp_cancel"); st().flash.clear()

        // ---- 6. Friendship AND Denounce on the Diplomacy tab; denouncing needs confirming
        act("tab", Tab.DIPLOMACY.name); act("selciv", "civ:$ai3")
        val labels = buttons().map { it.label }
        check(labels.any { it.startsWith("Friendship") } && labels.any { it.startsWith("Denounce") }, "both Friendship and Denounce must show: $labels")
        ok("🤝 Diplomacy tab shows Friendship and Denounce")
        check(act("dp_denounce", feature = "😠 denounce: confirm step"), "dp_denounce")
        render("dp-denounce-confirm")
        check(act("dp_ok", feature = "😠 denounce after confirming"), "denounce ok")
        check(t.eng { g -> GameOps.civOf(g, a).getDiplomacyManager(g.getCivilization(ai3))!!.hasFlag(DiplomacyFlags.Denunciation) }, "not denounced")
        ok("denounce")
        flash("denounce")

        // ---- 2. treaties on both sides + research agreement gold top-up, against an AI and a human
        fun enableTreaties(x: Civilization, y: Civilization) {
            for (c in listOf(x, y)) for (tech in listOf("Writing", "Philosophy", "Education", "Chivalry"))
                if (c.gameInfo.ruleset.technologies.containsKey(tech) && !c.tech.isResearched(tech)) c.tech.addTechnology(tech, false)
            x.getDiplomacyManager(y)!!.setModifier(DiplomaticModifiers.SharedEmbassies, 3f)
            y.getDiplomacyManager(x)!!.setModifier(DiplomaticModifiers.SharedEmbassies, 3f)
            x.getDiplomacyManager(y)!!.signDeclarationOfFriendship()
        }
        val raCost = t.eng { g ->
            val alice = GameOps.civOf(g, a); val ai = g.getCivilization(ai1)
            enableTreaties(alice, ai)
            ai.addGold(40 - ai.gold) // too poor: the deal must be topped up with Alice's gold
            alice.diplomacyFunctions.getResearchAgreementCost(ai)
        }
        act("tab", Tab.DIPLOMACY.name); act("selciv", "civ:$ai1")
        val ra = t.eng { g -> Features.tradeChoices(GameOps.civOf(g, a), g.getCivilization(ai1)).first }.firstOrNull { it.label.contains("Research Agreement") }
        check(ra != null, "research agreement not offered")
        act("give", ra!!.id)
        val draft = t.eng { g -> Features.buildTrade(GameOps.civOf(g, a), g.getCivilization(ai1), st().give, st().ask) }
        val topUp = draft.currentTrade.ourOffers.firstOrNull { it.type == TradeOfferType.Gold }?.amount ?: 0
        println("  [dp] RA draft: ${t.eng { Features.describeTrade(draft) }} (cost $raCost, top-up $topUp)")
        check(draft.currentTrade.theirOffers.any { it.name == Constants.researchAgreement }, "RA must be on both sides")
        ok("📜 treaties go on both sides of the deal")
        check(topUp == raCost - 40, "RA gold top-up should be ${raCost - 40}, is $topUp")
        ok("💰 research agreement gold top-up")
        val aiWouldRA = t.eng { g -> TradeEvaluation().isTradeAcceptable(draft.currentTrade.reverse(), g.getCivilization(ai1), GameOps.civOf(g, a)) }
        act("trade"); flash("RA")
        check(pendingTo(ai1) == 1 && t.eng { g -> !GameOps.civOf(g, a).getDiplomacyManager(g.getCivilization(ai1))!!.hasFlag(DiplomacyFlags.ResearchAgreement) },
            "a deal to an AI must wait for the AI's turn")
        ok("📨 deal to an AI waits for the AI's turn (Unciv's tradeRequests)")
        aiAnswers(ai1)
        val raSigned = t.eng { g -> val alice = GameOps.civOf(g, a); val ai = g.getCivilization(ai1)
            alice.getDiplomacyManager(ai)!!.hasFlag(DiplomacyFlags.ResearchAgreement) && ai.getDiplomacyManager(alice)!!.hasFlag(DiplomacyFlags.ResearchAgreement) }
        println("  [dp] AI research agreement: Unciv AI would accept=$aiWouldRA, signed=$raSigned")
        check(raSigned == aiWouldRA && raSigned, "AI research agreement: would=$aiWouldRA signed=$raSigned")
        ok("🔬 AI accepts a research agreement (as Unciv's AI would)")
        // defensive pact: Unciv's AI must see you as an Ally and want the pact
        t.eng { g -> g.getCivilization(ai1).getDiplomacyManager(GameOps.civOf(g, a))!!.setModifier(DiplomaticModifiers.GaveUsGifts, 150f) }
        act("selciv", "civ:$ai1")
        val dp = t.eng { g -> Features.tradeChoices(GameOps.civOf(g, a), g.getCivilization(ai1)).first }.firstOrNull { it.label.contains("Defensive Pact") }
        check(dp != null, "defensive pact not offered")
        act("give", dp!!.id)
        val aiWouldDP = t.eng { g ->
            val tl = Features.buildTrade(GameOps.civOf(g, a), g.getCivilization(ai1), st().give, st().ask)
            TradeEvaluation().isTradeAcceptable(tl.currentTrade.reverse(), g.getCivilization(ai1), GameOps.civOf(g, a))
        }
        act("trade"); flash("DP")
        aiAnswers(ai1)
        t.eng { g -> GameOps.civOf(g, a).tradeRequests.clear() } // a counteroffer, if the AI made one (tested below)
        val dpSigned = t.eng { g -> GameOps.civOf(g, a).getDiplomacyManager(g.getCivilization(ai1))!!.diplomaticStatus == DiplomaticStatus.DefensivePact }
        println("  [dp] AI defensive pact: Unciv AI would accept=$aiWouldDP, signed=$dpSigned")
        check(dpSigned == aiWouldDP, "defensive pact result differs from Unciv's evaluation")
        ok(if (dpSigned) "🛡️ AI accepts a defensive pact (as Unciv's AI would)" else "🛡️ AI declines a defensive pact (as Unciv's AI would)")
        // human <-> human: research agreement and defensive pact proposals, answered by Bob
        t.eng { g -> enableTreaties(GameOps.civOf(g, a), GameOps.civOf(g, b)) }
        for ((what, flag) in listOf("Research Agreement" to DiplomacyFlags.ResearchAgreement, "Defensive Pact" to DiplomacyFlags.DefensivePact)) {
            act("selciv", "civ:${b.civId}")
            val c = t.eng { g -> Features.tradeChoices(GameOps.civOf(g, a), GameOps.civOf(g, b)).first }.firstOrNull { it.label.contains(what) }
            check(c != null, "$what not offered to a human")
            act("give", c!!.id)
            check(act("trade"), "trade proposal to human")
            val p = t.ctl.outbox.poll()
            check(p != null, "no proposal posted")
            println("  [dp] human $what: " + t.eng { t.ctl.answer(t.s, p!!, accept = true) })
            check(t.eng { g -> val alice = GameOps.civOf(g, a); val bob = GameOps.civOf(g, b)
                alice.getDiplomacyManager(bob)!!.hasFlag(flag) && bob.getDiplomacyManager(alice)!!.hasFlag(flag) }, "human $what not signed")
            ok("🤝 human↔human ${what.lowercase()} proposal")
        }
        st().flash.clear()

        // ---- 5. deal amounts: custom gold / gold per turn, luxury ×1, tradable-only, readable names
        act("selciv", "civ:$ai2")
        val give2 = t.eng { g -> Features.tradeChoices(GameOps.civOf(g, a), g.getCivilization(ai2)).first }
        check(give2.any { it.id == "ask:dp_gold:g" }, "custom gold option missing")
        check(act("dp_gold", "137", "✏️ custom gold amount (popup)", listOf("g")), "dp_gold")
        check(!act("dp_gold", "abc", null, listOf("g")) && !act("dp_gold", "99999999", null, listOf("g")), "bad amounts must be refused")
        fun draftAmount(type: TradeOfferType) = t.eng { g -> Features.buildTrade(GameOps.civOf(g, a), g.getCivilization(ai2), st().give, st().ask) }
            .currentTrade.ourOffers.firstOrNull { it.type == type }?.amount
        check(draftAmount(TradeOfferType.Gold) == 137, "custom gold not in the deal")
        t.eng { g -> GameOps.civOf(g, a).stats.statsForNextTurn.gold = 25f }
        check(act("dp_gpt", "7", "✏️ custom gold-per-turn amount (popup)", listOf("g")), "dp_gpt")
        check(draftAmount(TradeOfferType.Gold_Per_Turn) == 7, "custom gold per turn not in the deal")
        st().give = emptyList(); st().ask = emptyList(); st().flash.clear()
        // two copies of a luxury: a pick trades just one
        val lux = t.eng { g ->
            val alice = GameOps.civOf(g, a); val rs = g.ruleset
            val luxRes = rs.tileResources.values.first { r -> r.resourceType == ResourceType.Luxury && alice.getResourceAmount(r.name) == 0 && rs.tileImprovements.values.any { r.isImprovedBy(it.name) } }
            val imp = rs.tileImprovements.values.first { luxRes.isImprovedBy(it.name) }
            for (tile in alice.cities.flatMap { it.getTiles() }.filter { it.isLand && !it.isCityCenter() }.take(2)) { tile.setTileResource(luxRes.name); tile.setImprovement(imp) }
            alice.cache.updateCivResources()
            luxRes.name
        }
        val luxChoices = t.eng { g -> Features.tradeChoices(GameOps.civOf(g, a), g.getCivilization(ai2)).first }.filter { it.emoji == "💎" }
        println("  [dp] luxury choices: ${luxChoices.map { "${it.label} (${it.description})" }}")
        val luxC = luxChoices.firstOrNull { it.label.startsWith(lux) }
        check(luxC != null && luxChoices.all { it.id.contains("~1~") }, "luxuries should be offered one at a time")
        act("give", luxC!!.id); act("trade"); flash("luxury")
        aiAnswers(ai2)
        check(t.eng { g -> GameOps.civOf(g, a).getResourceAmount(lux) == 1 && g.getCivilization(ai2).getResourceAmount(lux) >= 1 }, "luxury ×1 trade didn't move exactly one copy")
        ok("💎 luxury trades one copy at a time")
        val allChoices = t.eng { g -> val alice = GameOps.civOf(g, a)
            GameOps.knownMajorCivs(alice).filter { !it.isCityState }.flatMap { o -> Features.tradeChoices(alice, o).toList().flatten() } }
        check(allChoices.filter { !it.id.startsWith("ask:") }.all { (it.id.split("~").getOrNull(2)?.toIntOrNull() ?: 1) > 0 }, "non-tradable offers listed")
        check(allChoices.filter { it.emoji == "👋" || it.label.startsWith("Make peace") }.all { it.label.contains(" to ") || it.label.contains(" with ") }, "unreadable names")
        ok("🧹 only tradable offers, readable introduction/peace names")

        // ---- 3. 🧭 Relations: opinion, promises, deals, world politics
        t.eng { g ->
            val alice = GameOps.civOf(g, a); val ai = g.getCivilization(ai1)
            alice.getDiplomacyManager(ai)!!.setFlag(Demand.DoNotSettleNearUs.agreedToDemand, 30) // they promised us
            ai.getDiplomacyManager(alice)!!.setFlag(Demand.DoNotSpreadReligion.agreedToDemand, 25) // we promised them
        }
        check(act("tab", RELATIONS, "🧭 relations tab"), "relations tab")
        act("selciv", "civ:$ai1")
        val texts = t.eng { g -> val alice = GameOps.civOf(g, a); val ai = g.getCivilization(ai1)
            listOf(opinionText(alice, ai), promisesText(alice, ai), dealsText(alice, ai), politicsText(alice)) }
        listOf("opinion", "promises", "deals", "politics").zip(texts).forEach { (n, v) -> println("  [dp $n] ${v.replace("\n", " | ")}") }
        check(texts[0].contains(DiplomaticModifiers.GaveUsGifts.text) && texts[0].contains("opinion"), "opinion modifiers missing")
        ok("🧭 why they like or dislike you")
        check(texts[1].contains("They promised not to settle near us") && texts[1].contains("We promised not to spread religion"), "promises missing")
        ok("🤞 promises both sides made")
        check(texts[2].contains("Research agreement") && texts[2].contains("turns left") && texts[2].contains("⏳"), "deals missing")
        ok("📜 active deals with turns left")
        check(texts[3].contains("💛") && texts[3].contains("😠"), "politics view incomplete")
        ok("🌍 world politics view")
        render("dp-relations")

        // ---- 4. demands: to an AI (answered on its turn) and to a human (their Inbox)
        act("selciv", "civ:$ai2")
        val dch = t.eng { g -> demandChoices(GameOps.civOf(g, a), g.getCivilization(ai2)) }
        println("  [dp] demands: ${dch.map { it.id }}")
        check(dch.any { it.id == "dp:d:DoNotSettleNearUs" } && dch.none { it.id == "dp:d:DoNotAttackUs" }, "demand list wrong")
        check(act("dp_demand", "dp:d:DoNotSettleNearUs", "📢 demand to an AI"), "dp_demand ai")
        check(t.eng { g -> g.getCivilization(ai2).popupAlerts.any { it.type == AlertType.DemandToStopSettlingCitiesNear && it.value == a.civId } }, "AI didn't get the demand")
        check(!act("dp_demand", "dp:d:DoNotSettleNearUs"), "the same demand twice")
        render("dp-relations-demand")
        val answered = t.eng { g ->
            val ai = g.getCivilization(ai2)
            Engine.callHidden(NextTurnAutomation, "respondToPopupAlerts", GameView(g, ai).civView) // what runs on the AI's turn
            GameOps.civOf(g, a).popupAlerts.firstOrNull { (it.type == AlertType.AcceptingDemand || it.type == AlertType.RejectingDemand) && it.value == ai2 }?.type
        }
        println("  [dp] AI answer to the demand: $answered")
        check(answered != null, "AI didn't answer the demand")
        ok("📢 AI answers the demand on its turn")
        act("selciv", "civ:${b.civId}")
        check(act("dp_demand", "dp:d:DoNotSettleNearUs", "📢 demand to a human (their Inbox)"), "dp_demand human")
        check(t.eng { g -> GameOps.civOf(g, b).popupAlerts.removeIf { it.type == AlertType.DemandToStopSettlingCitiesNear && it.value == a.civId } }, "human didn't get the demand")
        t.eng { g -> GameOps.civOf(g, a).popupAlerts.removeIf { it.type == AlertType.AcceptingDemand || it.type == AlertType.RejectingDemand } }
        st().flash.clear()

        // ---- 7./8. city-states: details, tribute >= 0, automatic peace, ally blocking peace, gifts, marriage
        val csId = t.eng { g ->
            val alice = GameOps.civOf(g, a); val bob = GameOps.civOf(g, b)
            // a city-state where an improvement can be gifted: put a resource it can improve on one of its tiles
            val rs = g.ruleset
            fun giftSetUp(cs: Civilization, forceTerrain: Boolean): Boolean {
                if (cityStateGiftChoices(alice, cs).any { it.id.startsWith("do:dp_cs:imp~") }) return true
                for (tile in cs.cities.flatMap { it.getTiles() }.filter { it.isLand && !it.isCityCenter() && !it.isImpassible() && it.improvement == null }) {
                    if (forceTerrain) { tile.removeTerrainFeatures(); rs.terrains["Plains"]?.let { tile.setBaseTerrain(it) } }
                    for (r in rs.tileResources.values.filter { (it.resourceType == ResourceType.Luxury || it.resourceType == ResourceType.Strategic)
                            && tile.lastTerrain.name in it.terrainsCanBeFoundOn && alice.canSeeResource(it) }) {
                        tile.setTileResource(r.name)
                        for (imp in rs.tileImprovements.values.filter { r.isImprovedBy(it.name) })
                            imp.techRequired?.let { if (!cs.tech.isResearched(it)) cs.tech.addTechnology(it, false) }
                        if (cityStateGiftChoices(alice, cs).any { it.id.startsWith("do:dp_cs:imp~") }) return true
                        tile.setTileResource(null)
                    }
                    if (forceTerrain) return false
                }
                return false
            }
            val css = g.civilizations.filter { it.isCityState && it.isAlive() && it.cities.isNotEmpty() }
            for (c in css) for (m in listOf(alice, bob)) if (!m.knows(c)) m.diplomacyFunctions.makeCivilizationsMeet(c)
            val cs = css.firstOrNull { giftSetUp(it, false) } ?: css.firstOrNull { giftSetUp(it, true) } ?: css.first()
            if (alice.isAtWarWith(cs)) { cs.getDiplomacyManager(alice)!!.removeFlag(DiplomacyFlags.DeclaredWar); GameOps.makePeace(alice, cs) }
            cs.getDiplomacyManager(alice)!!.setInfluence(80f)
            // a "war against Bob" request from the city-state
            val field = cs.questManager.javaClass.getDeclaredField("unitsToKillForCiv").apply { isAccessible = true }
            @Suppress("UNCHECKED_CAST") (field.get(cs.questManager) as HashMap<String, Int>)[b.civId] = 3
            cs.civID
        }
        act("tab", Tab.CITYSTATES.name); act("selcs", "cs:$csId")
        val csf = t.eng { g -> cityStateFields(GameOps.civOf(g, a), g.getCivilization(csId)) }
        csf.forEach { (n, v) -> println("  [dp cs] $n: ${v.replace("\n", " | ").take(300)}") }
        check(csf.any { it.first.contains("Standing") && it.second.contains("Personality") }, "standing missing")
        check(csf.any { it.first.contains("Bonuses") && it.second.contains("When Allies") }, "bonuses missing")
        check(csf.any { it.second.contains("War against") }, "war quest missing")
        check(csf.any { it.first.startsWith("💰 Tribute willingness") }, "tribute breakdown missing")
        check(t.eng { g -> allyLine(GameOps.civOf(g, a), g.getCivilization(csId)) }.contains("influence"), "ally influence missing")
        ok("🏰 city-state details: ally influence, bonuses, personality, resources, quests, war request")
        val (will, hasTribute) = t.eng { g -> val alice = GameOps.civOf(g, a); val cs = g.getCivilization(csId)
            cs.cityStateFunctions.getTributeWillingness(alice) to Features.cityStateChoices(alice, cs).any { it.id == "tribgold" } }
        check(hasTribute == (will >= 0), "tribute shown=$hasTribute with willingness $will")
        ok("💰 tribute offered when willingness ≥ 0 (with breakdown)")
        render("dp-citystate")
        // improvement gift (set up above)
        val impChoice = t.eng { g -> cityStateGiftChoices(GameOps.civOf(g, a), g.getCivilization(csId)).firstOrNull { it.id.startsWith("do:dp_cs:imp~") } }
        check(impChoice != null, "no improvement gift could be set up")
        val goldBefore = t.eng { g -> GameOps.civOf(g, a).gold }
        check(act("csact", impChoice!!.id, "🛠️ gift an improvement to a city-state (200 gold)"), "improvement gift")
        val impParts = impChoice.id.removePrefix("do:dp_cs:imp~").split("~")
        val (ix, iy) = impParts[0].split(",").map { it.toInt() }
        check(t.eng { g -> g.tileMap[ix, iy].improvement == impParts[1] && GameOps.civOf(g, a).gold == goldBefore - IMPROVEMENT_GIFT_COST }, "improvement not built / gold not paid")
        flash("improvement")
        // war (confirmed) → peace blocked while at war with its ally → automatic peace
        act("tab", Tab.CITYSTATES.name); act("selcs", "cs:$csId")
        act("dp_war", null, null, listOf("cs"))
        check(act("dp_ok", feature = "🔥 war on a city-state (confirmed)") && atWar(a.civId, csId), "cs war")
        flash("cs war")
        t.eng { g -> val alice = GameOps.civOf(g, a); val cs = g.getCivilization(csId); val bob = GameOps.civOf(g, b)
            alice.getDiplomacyManager(cs)!!.removeFlag(DiplomacyFlags.DeclaredWar); cs.getDiplomacyManager(alice)!!.removeFlag(DiplomacyFlags.DeclaredWar)
            cs.getDiplomacyManager(bob)!!.setInfluence(500f) // Bob becomes its ally…
            alice.getDiplomacyManager(bob)!!.diplomaticStatus = DiplomaticStatus.War; bob.getDiplomacyManager(alice)!!.diplomaticStatus = DiplomaticStatus.War // …and is at war with Alice
            println("  [dp] city-state ally: ${cs.allyCiv?.civName}")
        }
        val blocker = t.eng { g -> peaceBlocker(GameOps.civOf(g, a), g.getCivilization(csId)) }
        check(blocker != null && !act("peace", null, null, listOf("cs")), "peace with a city-state allied to an enemy must be blocked")
        ok("🏰 city-state peace blocked while at war with its ally")
        t.eng { g -> val alice = GameOps.civOf(g, a); val bob = GameOps.civOf(g, b)
            alice.getDiplomacyManager(bob)!!.diplomaticStatus = DiplomaticStatus.Peace; bob.getDiplomacyManager(alice)!!.diplomaticStatus = DiplomaticStatus.Peace }
        check(act("peace", null, "🕊️ city-state peace is automatic", listOf("cs")) && !atWar(a.civId, csId), "cs peace")
        flash("cs peace")
        // diplomatic marriage (Austria's ability, granted for the test)
        t.eng { g -> val alice = GameOps.civOf(g, a); val cs = g.getCivilization(csId)
            alice.temporaryUniques.add(TemporaryUnique().apply { unique = "Can spend Gold to annex or puppet a City-State that has been your Ally for [0] turns"; turnsLeft = 2 })
            cs.getDiplomacyManager(GameOps.civOf(g, b))!!.setInfluence(0f)
            cs.getDiplomacyManager(alice)!!.setInfluence(200f)
            alice.getDiplomacyManager(cs)!!.removeFlag(DiplomacyFlags.MarriageCooldown) // as if allied for enough turns
            alice.addGold(5000) }
        val marry = t.eng { g -> cityStateGiftChoices(GameOps.civOf(g, a), g.getCivilization(csId)) }.firstOrNull { it.id == "do:dp_cs:marry" }
        println("  [dp] marriage option: ${marry?.label} — ${marry?.description}")
        check(marry != null, "marriage option missing")
        val citiesBefore = t.eng { g -> GameOps.civOf(g, a).cities.size }
        check(act("csact", marry!!.id, "💍 diplomatic marriage"), "marriage")
        check(t.eng { g -> GameOps.civOf(g, a).cities.size > citiesBefore && !g.getCivilization(csId).isAlive() }, "marriage didn't bring the city-state in")
        flash("marriage")
        t.eng { g -> GameOps.civOf(g, a).temporaryUniques.clear() }
        act("tab", Tab.CITYSTATES.name); render("dp-citystate-after")

        // ==================================================================== round 2
        fun goldGive(other: String) = t.eng { g -> Features.tradeChoices(GameOps.civOf(g, a), g.getCivilization(other)).first }
            .filter { it.id.startsWith("gt~Gold~") }.minByOrNull { it.id.split("~")[2].toInt() }!!
        fun embedText(): String = t.eng { Panel.render(t.s, a, st()) }.embed.fields.joinToString("\n") { "${it.name}: ${it.value}" }
        st().give = emptyList(); st().ask = emptyList(); st().flash.clear()
        t.eng { g -> val alice = GameOps.civOf(g, a); alice.tradeRequests.clear(); alice.addGold(4000)
            for (id in aiIds) g.getCivilization(id).tradeRequests.removeAll { it.requestingCiv == a.civId } }

        // ---- R1. an offer to an AI waits; a new one replaces it; it can be withdrawn (TradeView.tryRetractOffer)
        act("tab", Tab.DIPLOMACY.name); act("selciv", "civ:$ai2")
        act("give", goldGive(ai2).id)
        check(act("trade", feature = "📨 offer to an AI (decided on its turn)") && pendingTo(ai2) == 1, "offer to the AI not pending")
        flash("pending")
        act("give", goldGive(ai2).id); act("trade")
        check(pendingTo(ai2) == 1, "a second offer must replace the first (one pending offer per civ)")
        ok("📨 a new offer replaces the pending one (like Unciv)")
        act("tab", RELATIONS); act("selciv", "civ:$ai2")
        check(embedText().contains("Waiting for an answer"), "Relations doesn't show the pending offer")
        ok("⏳ Relations shows offers waiting both ways")
        check(act("tab", DEALS, "📜 all-deals view"), "deals tab")
        val dealsView = embedText()
        check(dealsView.contains("Your offers waiting") && dealsView.contains(ai2Name) && dealsView.contains("Research agreement"), "deals view incomplete: $dealsView")
        ok("📜 every deal with turns left + pending offers both ways")
        render("dp-deals")
        check(act("dp_retract", "dp_r:ai:$ai2", "↩️ withdraw a pending offer to an AI") && pendingTo(ai2) == 0, "retract failed")
        check(!act("dp_retract", "dp_r:ai:$ai2"), "withdrawing twice must fail")
        flash("retract")

        // Alice gets 5 copies of a strategic resource (for R2's counteroffer and R6's amounts)
        val stratName = t.eng { g ->
            val alice = GameOps.civOf(g, a); val rs = g.ruleset
            val r = rs.tileResources.values.first { r -> r.resourceType == ResourceType.Strategic && alice.canSeeResource(r) && rs.tileImprovements.values.any { r.isImprovedBy(it.name) } }
            val imp = rs.tileImprovements.values.first { r.isImprovedBy(it.name) }
            val tile = alice.cities.flatMap { it.getTiles() }.last { it.isLand && !it.isCityCenter() && it.resource == null }
            tile.setTileResource(r.name); tile.resourceAmount = 5; tile.setImprovement(imp)
            alice.cache.updateCivResources()
            r.name
        }
        // ---- R2. lopsided deal → Unciv's AI counteroffer (TradeAutomation.getCounteroffer) → 📨 Inbox → "How about something else…"
        // (an AI with no strong feelings about us: one that loves us would simply give it away)
        val coAi = ai2; val coName = ai2Name
        t.eng { g -> val alice = GameOps.civOf(g, a); val ai = g.getCivilization(coAi)
            for ((x, y) in listOf(alice to ai, ai to alice)) x.getDiplomacyManager(y)!!.apply {
                diplomaticModifiers.clear(); removeFlag(DiplomacyFlags.DeclarationOfFriendship); if (diplomaticStatus == DiplomaticStatus.DefensivePact) diplomaticStatus = DiplomaticStatus.Peace } }
        t.eng { g -> val ai = g.getCivilization(coAi); ai.addGold(300 - ai.gold) }
        act("tab", Tab.DIPLOMACY.name); act("selciv", "civ:$coAi")
        // ask for something of theirs that isn't gold, offer nothing: Unciv's AI can then ask for our gold in return.
        // Give the AI a strategic resource we lack, so there is always something to ask for.
        t.eng { g ->
            val alice = GameOps.civOf(g, a); val ai = g.getCivilization(coAi); val rs = g.ruleset
            val r = rs.tileResources.values.firstOrNull { r -> r.resourceType == ResourceType.Strategic && r.name != stratName && alice.getResourceAmount(r.name) == 0 &&
                alice.canSeeResource(r) && rs.tileImprovements.values.any { r.isImprovedBy(it.name) } }
            val tile = ai.cities.flatMap { it.getTiles() }.lastOrNull { it.isLand && !it.isCityCenter() && it.resource == null }
            if (r != null && tile != null) {
                val imp = rs.tileImprovements.values.first { r.isImprovedBy(it.name) }
                for (tech in listOfNotNull(r.revealedBy, imp.techRequired)) if (!ai.tech.isResearched(tech)) ai.tech.addTechnology(tech, false)
                tile.setTileResource(r.name); tile.resourceAmount = 3; tile.setImprovement(imp)
                ai.cache.updateCivResources()
            }
            println("  [dp] gave ${ai.civName} ${r?.name} on ${tile?.position}: now ${r?.let { ai.getResourceAmount(it.name) }}")
        }
        val askList = t.eng { g -> Features.tradeChoices(GameOps.civOf(g, a), g.getCivilization(coAi)).second }.filter { !it.id.startsWith("ask:") }
        val askItem = listOf("Strategic_Resource", "Technology", "Luxury_Resource", "Agreement", "Embassy", "Introduction")
            .firstNotNullOfOrNull { ty -> askList.firstOrNull { it.id.startsWith("at~$ty~") } }
            ?: askList.filter { it.id.startsWith("at~Gold~") }.minByOrNull { it.id.split("~")[2].toInt() }
        check(askItem != null, "nothing to ask the AI for")
        act("ask", askItem!!.id); act("trade"); flash("lopsided")
        check(pendingTo(coAi) == 1, "lopsided offer not pending")
        t.eng { g -> val ai = g.getCivilization(coAi); val r = ai.tradeRequests.first { it.requestingCiv == a.civId }
            val acc = TradeEvaluation().getTradeAcceptability(r.trade, ai, GameOps.civOf(g, a), true)
            val co = Engine.callHiddenResult(TradeAutomation, "getCounteroffer", ai, r) as? com.unciv.logic.trade.TradeRequest
            println("  [dp] Unciv's evaluation of the lopsided deal: acceptability=$acc, counteroffer asks=${co?.trade?.ourOffers?.map { "${it.name}×${it.amount}" }}") }
        aiAnswers(coAi)
        val counter = t.eng { g -> GameOps.civOf(g, a).tradeRequests.firstOrNull { it.requestingCiv == coAi }?.let { r ->
            Features.describeTrade(TradeLogic(GameOps.civOf(g, a), g.getCivilization(coAi)).apply { currentTrade.set(r.trade) }) } }
        val notes = aliceNotes().filter { it.contains(coName) && it.contains("trade request") }
        println("  [dp] AI answer to a lopsided deal: counteroffer=$counter, notes=$notes")
        check(pendingTo(coAi) == 0, "the AI didn't answer")
        if (counter == null) {
            check(notes.any { it.contains("denied") || it.contains("accepted") }, "no answer notification")
            ok("🔁 AI answered a lopsided deal on its turn (no counteroffer possible)")
            // still exercise "How about something else…" on an AI offer
            t.eng { g -> GameOps.civOf(g, a).tradeRequests.add(com.unciv.logic.trade.TradeRequest(coAi, Trade().apply {
                theirOffers.add(TradeOffer("Gold", TradeOfferType.Gold, 50, g.speed)); ourOffers.add(TradeOffer("Gold", TradeOfferType.Gold, 20, g.speed)) })) }
        } else {
            check(notes.any { it.contains("counteroffer") }, "no counteroffer notification")
            ok("🔁 AI counteroffer for a lopsided deal (Unciv's getCounteroffer)")
        }
        val offered = t.eng { g -> GameOps.civOf(g, a).tradeRequests.first { it.requestingCiv == coAi }.let { r ->
            Features.describeTrade(TradeLogic(GameOps.civOf(g, a), g.getCivilization(coAi)).apply { currentTrade.set(r.trade) }) } }
        run {
            check(act("tab", "INBOX") && embedText().contains("proposes a deal"), "counteroffer not in the Inbox")
            check(act("sp_ans", "sp:t:$coAi:0|else", "✏️ \"How about something else…\" opens the deal builder with their offer"), "else")
            check(st().tab == Tab.DIPLOMACY.name && st().otherCivId == coAi && st().ask.isNotEmpty() && st().give.isNotEmpty(),
                "deal builder not pre-filled: tab=${st().tab} give=${st().give} ask=${st().ask}")
            check(t.eng { g -> GameOps.civOf(g, a).tradeRequests.none { it.requestingCiv == coAi } }, "the offer should leave the Inbox")
            val draft = t.eng { g -> Features.describeTrade(Features.buildTrade(GameOps.civOf(g, a), g.getCivilization(coAi), st().give, st().ask)) }
            println("  [dp] their offer in the builder: $draft (AI's was: $offered)")
            check(draft == offered, "builder draft differs from their offer")
            render("dp-counter-builder")
            act("trade"); flash("counter back"); aiAnswers(coAi)
            println("  [dp] sending their offer back: " + aliceNotes().filter { it.contains(coName) && it.contains("trade request") }.lastOrNull())
        }
        st().give = emptyList(); st().ask = emptyList(); st().flash.clear()

        // ---- R3. peace with an AI goes to its trade requests too (MajorCivDiplomacyTable "Negotiate Peace" → TradeTable)
        t.eng { g -> val alice = GameOps.civOf(g, a); val ai = g.getCivilization(ai3)
            for ((x, y) in listOf(alice to ai, ai to alice)) x.getDiplomacyManager(y)!!.apply { diplomaticStatus = DiplomaticStatus.War; removeFlag(DiplomacyFlags.DeclaredWar) } }
        act("tab", Tab.DIPLOMACY.name); act("selciv", "civ:$ai3")
        check(act("peace", feature = "🕊️ peace offer to an AI (decided on its turn)") && atWar(a.civId, ai3) && pendingTo(ai3) == 1, "peace not pending")
        flash("peace pending")
        aiAnswers(ai3)
        println("  [dp] AI's answer to peace: at war=${atWar(a.civId, ai3)}, notes=${aliceNotes().filter { it.contains("trade request") }.takeLast(1)}")
        check(pendingTo(ai3) == 0, "the AI didn't answer the peace offer")
        t.eng { g -> val alice = GameOps.civOf(g, a); val ai = g.getCivilization(ai3)
            for ((x, y) in listOf(alice to ai, ai to alice)) x.getDiplomacyManager(y)!!.apply { diplomaticStatus = DiplomaticStatus.Peace; removeFlag(DiplomacyFlags.DeclaredWar) } }

        // ---- R4. friendship with an AI: a popup alert it answers on its turn (respondToDeclarationOfFriendship)
        act("selciv", "civ:$ai2")
        t.eng { g -> val alice = GameOps.civOf(g, a); val ai = g.getCivilization(ai2)
            for ((x, y) in listOf(alice to ai, ai to alice)) x.getDiplomacyManager(y)!!.apply { removeFlag(DiplomacyFlags.DeclarationOfFriendship); removeFlag(DiplomacyFlags.Denunciation) } }
        if (t.eng { g -> Features.friendshipAvailable(GameOps.civOf(g, a), g.getCivilization(ai2)) }) {
            check(act("friend", feature = "💛 friendship offer to an AI (answered on its turn)"), "friend")
            check(t.eng { g -> g.getCivilization(ai2).popupAlerts.any { it.type == AlertType.DeclarationOfFriendship && it.value == a.civId } &&
                !Features.friendshipAvailable(GameOps.civOf(g, a), g.getCivilization(ai2)) }, "friendship offer not pending / button still on")
            t.eng { g -> Engine.callHidden(NextTurnAutomation, "respondToPopupAlerts", GameView(g, g.getCivilization(ai2)).civView) }
            val fn = aliceNotes().filter { it.contains("Declaration of Friendship") }
            println("  [dp] AI's answer to friendship: $fn")
            check(fn.isNotEmpty(), "no friendship answer")
        } else println("  [dp] friendship with $ai2Name not available — skipped")
        flash("friend")

        // ---- R5. human↔human proposals survive a restart (meta.settings.extra), Accept still works; and can be withdrawn
        act("selciv", "civ:${b.civId}")
        act("give", goldGive(b.civId).id)
        check(act("trade"), "proposal to Bob")
        val prop = t.ctl.outbox.poll()
        check(prop != null, "no proposal")
        t.eng { t.ctl.store.saveGame(t.s) }
        t.ctl.proposals.clear() // what a restart loses
        val reloaded = Store(t.ctl.store.dir).apply { loadAll() }.sessions[gid]
        check(reloaded != null && Controller(t.ctl.store).findProposal(reloaded, prop!!.id)?.give == prop.give, "proposal not saved with the game")
        val restored = t.ctl.findProposal(t.s, prop!!.id)
        val bobGold = t.eng { g -> GameOps.civOf(g, b).gold }
        val answer = t.eng { t.ctl.answer(t.s, restored!!, true) }
        println("  [dp] answer after restart: $answer")
        check(answer.startsWith("🤝 Deal done") && t.eng { g -> GameOps.civOf(g, b).gold } > bobGold && t.ctl.findProposal(t.s, prop.id) == null, "answer after restart failed")
        ok("💾 human↔human proposal survives a restart (Accept still works)")
        act("give", goldGive(b.civId).id); act("trade")
        val prop2 = t.ctl.outbox.poll()!!
        act("tab", DEALS)
        check(embedText().contains("game channel"), "human proposal not in the deals view")
        check(act("dp_retract", "dp_r:h:${prop2.id}", "↩️ withdraw a proposal to a human") && t.ctl.findProposal(t.s, prop2.id) == null, "human retract failed")
        flash("human retract")

        // ---- R6. any resource amount 1..N, and ✨ what the other side lacks
        act("tab", Tab.DIPLOMACY.name); act("selciv", "civ:$ai2")
        val sc = t.eng { g -> Features.tradeChoices(GameOps.civOf(g, a), g.getCivilization(ai2)).first }.filter { it.label.startsWith(stratName) }
        println("  [dp] $stratName choices: ${sc.map { "${it.label} [${it.description}]" }}")
        val custom = sc.firstOrNull { it.id.startsWith("ask:dp_res:") }
        check(custom != null, "no custom amount for $stratName")
        val arg = custom!!.id.removePrefix("ask:dp_res:")
        val n = t.eng { g -> GameOps.civOf(g, a).getResourceAmount(stratName) }
        check(act("dp_res", "3", "✏️ any amount of a resource (popup)", listOf(arg)), "dp_res")
        check(!act("dp_res", "${n + 1}", null, listOf(arg)) && !act("dp_res", "x", null, listOf(arg)), "bad resource amounts must be refused")
        val amt = t.eng { g -> Features.buildTrade(GameOps.civOf(g, a), g.getCivilization(ai2), st().give, st().ask) }
            .currentTrade.ourOffers.firstOrNull { it.name == stratName }?.amount
        check(amt == 3, "resource ×3 not in the deal ($amt)")
        val theyLack = t.eng { g -> TradeLogic(GameOps.civOf(g, a), g.getCivilization(ai2)).theirAvailableOffers.none { it.name == stratName && it.amount >= 0 } }
        check(sc.all { it.description.contains("they lack this") == theyLack }, "lack mark wrong (they lack: $theyLack)")
        ok("✨ resources the other side lacks are marked (Unciv's green highlight)")
        st().give = emptyList(); st().ask = emptyList(); st().flash.clear()

        // ---- R7. city-state actions re-checked on click; pledge / revoke confirmed
        val cs2 = t.eng { g -> val alice = GameOps.civOf(g, a)
            val cs = g.civilizations.first { it.isCityState && it.isAlive() && it.cities.isNotEmpty() && it.civID != csId }
            if (!alice.knows(cs)) alice.diplomacyFunctions.makeCivilizationsMeet(cs)
            for ((x, y) in listOf(alice to cs, cs to alice)) x.getDiplomacyManager(y)!!.apply {
                diplomaticStatus = DiplomaticStatus.Peace; removeFlag(DiplomacyFlags.DeclaredWar)
                removeFlag(DiplomacyFlags.RecentlyWithdrewProtection); removeFlag(DiplomacyFlags.RecentlyPledgedProtection) }
            cs.getDiplomacyManager(alice)!!.setInfluence(40f)
            cs.civID }
        fun csStatus() = t.eng { g -> g.getCivilization(cs2).getDiplomacyManager(GameOps.civOf(g, a))!!.diplomaticStatus }
        act("tab", Tab.CITYSTATES.name); act("selcs", "cs:$cs2")
        check(act("csact", "do:dp_csask:pledge", "🛡️ pledge to protect: confirm step") && st().tab == CONFIRM && csStatus() != DiplomaticStatus.Protector, "pledge confirm")
        render("dp-pledge-confirm")
        check(act("dp_ok", feature = "🛡️ pledge after confirming") && csStatus() == DiplomaticStatus.Protector && st().tab == Tab.CITYSTATES.name, "pledge")
        check(!act("csact", "do:dp_csask:revoke"), "revoke right after pledging must be refused (10-turn lock)")
        check(!act("csact", "pledge"), "a stale second pledge must be refused")
        t.eng { g -> g.getCivilization(cs2).getDiplomacyManager(GameOps.civOf(g, a))!!.removeFlag(DiplomacyFlags.RecentlyPledgedProtection) }
        check(act("csact", "do:dp_csask:revoke", "🚫 revoke protection: confirm step") && act("dp_ok", feature = "🚫 revoke after confirming") &&
            csStatus() != DiplomaticStatus.Protector, "revoke")
        check(!act("csact", "pledge"), "pledging again during the 20-turn lock must be refused")
        ok("🛡️ pledge / revoke re-checked on click (otherCivCanPledgeProtection / otherCivCanWithdrawProtection)")
        // tribute: refused at war, else exactly when willingness >= 0
        t.eng { g -> val alice = GameOps.civOf(g, a); val cs = g.getCivilization(cs2)
            for ((x, y) in listOf(alice to cs, cs to alice)) x.getDiplomacyManager(y)!!.diplomaticStatus = DiplomaticStatus.War }
        check(!act("csact", "tribgold") && !act("csact", "gift:250"), "tribute / gift at war must be refused")
        t.eng { g -> val alice = GameOps.civOf(g, a); val cs = g.getCivilization(cs2)
            for ((x, y) in listOf(alice to cs, cs to alice)) x.getDiplomacyManager(y)!!.diplomaticStatus = DiplomaticStatus.Peace }
        val will2 = t.eng { g -> g.getCivilization(cs2).cityStateFunctions.getTributeWillingness(GameOps.civOf(g, a)) }
        val goldB = t.eng { g -> GameOps.civOf(g, a).gold }
        val tributeOk = act("csact", "tribgold")
        println("  [dp] tribute with willingness $will2: ${st().flash.lastOrNull()}")
        check(tributeOk == (will2 >= 0) && (t.eng { g -> GameOps.civOf(g, a).gold } > goldB) == tributeOk, "tribute result vs willingness $will2")
        ok("💰 tribute re-checked on click (willingness ≥ 0, not at war)")
        // gift with too little gold (a stale panel)
        val keepGold = t.eng { g -> val alice = GameOps.civOf(g, a); val x = alice.gold; alice.addGold(100 - x); x }
        check(!act("csact", "gift:250"), "a gift without the gold must be refused")
        t.eng { g -> GameOps.civOf(g, a).addGold(keepGold - 100) }
        ok("🎁 gold gift re-checked on click")
        st().flash.clear()

        // ---- R8. quest marker in the city-state list
        val (questCs, injected) = t.eng { g -> val alice = GameOps.civOf(g, a)
            val css = Features.cityStates(alice).filter { it.civID != cs2 }
            css.firstOrNull { it.questManager.haveQuestsFor(alice) }?.let { return@eng it.civID to null }
            val cs = css.first()
            val quest = g.ruleset.quests.values.first()
            val aq = com.unciv.logic.civilization.managers.quests.AssignedQuest(g, quest, cs, alice, g.turns)
            val f = cs.questManager.javaClass.getDeclaredField("assignedQuests").apply { isAccessible = true }
            @Suppress("UNCHECKED_CAST") (f.get(cs.questManager) as ArrayList<Any>).add(aq)
            cs.civID to aq }
        act("tab", Tab.CITYSTATES.name); act("selcs", "cs:$cs2")
        val csOptions = t.eng { Panel.render(t.s, a, st()) }.rows.flatMap { it.components }
            .filterIsInstance<net.dv8tion.jda.api.interactions.components.selections.StringSelectMenu>().first { it.id?.endsWith("|selcs") == true }.options
        val mark = csOptions.firstOrNull { it.value == "cs:$questCs" }
        println("  [dp] quest marker: ${mark?.emoji?.name} ${mark?.label} — ${mark?.description}")
        check(mark?.emoji?.name == "❗" && mark.description?.contains("quest") == true, "no quest marker")
        check(csOptions.filter { it.value != "cs:$questCs" }.all { o -> (o.emoji?.name == "❗") == t.eng { g ->
            g.getCivilization(o.value.removePrefix("cs:")).questManager.haveQuestsFor(GameOps.civOf(g, a)) } }, "quest markers wrong")
        ok("❗ city-states with quests for you are marked")
        if (injected != null) t.eng { g -> val cs = g.getCivilization(questCs)
            val f = cs.questManager.javaClass.getDeclaredField("assignedQuests").apply { isAccessible = true }
            (f.get(cs.questManager) as ArrayList<*>).remove(injected) }

        // ---- R9. mod unique "Diplomatic relationships cannot change": no war anywhere
        val origOptions = t.eng { g -> g.ruleset.modOptions }
        t.eng { g -> g.ruleset.modOptions = com.unciv.models.ruleset.ModOptions().apply { uniques.add(UniqueType.DiplomaticRelationshipsCannotChange.text) } }
        try {
            act("tab", Tab.DIPLOMACY.name); act("selciv", "civ:$ai2")
            val warBtn = buttons().firstOrNull { it.id?.contains("|dp_war") == true }
            act("tab", Tab.CITYSTATES.name); act("selcs", "cs:$cs2")
            val csWarBtn = buttons().firstOrNull { it.id?.contains("|dp_war") == true }
            println("  [dp] war buttons under the mod rule: ${warBtn?.label}/${warBtn?.isDisabled}, ${csWarBtn?.label}/${csWarBtn?.isDisabled}")
            check(warBtn?.isDisabled == true && csWarBtn?.isDisabled == true, "war buttons must be off")
            check(!act("dp_war", null, null, listOf("cs")) && !act("war", null, null, listOf("cs")), "war verbs must be refused")
            check(runCatching { t.eng { g -> GameOps.declareWar(GameOps.civOf(g, a), g.getCivilization(ai2)) } }.isFailure && !atWar(a.civId, ai2), "GameOps.declareWar must refuse")
            ok("🚫 no war under the mod rule DiplomaticRelationshipsCannotChange")
        } finally { t.eng { g -> g.ruleset.modOptions = origOptions } }

        // ---- R10. 📍 go to a civ's capital on the 🗺️ Map tab
        val cap = t.eng { g -> val c = g.getCivilization(ai1).getCapital()!!.getCenterTile(); c.setExplored(GameOps.civOf(g, a), true); c.position }
        act("tab", RELATIONS); act("selciv", "civ:$ai1")
        check(buttons().any { it.id?.contains("|dp_goto") == true && !it.isDisabled }, "go-to button missing")
        check(act("dp_goto", null, "📍 go to a civ's capital on the map", listOf(ai1)) && st().tab == Tab.MAP.name && st().mapCenter == cap, "go to")
        render("dp-goto-map")
        st().inspect = null; st().mapCenter = null; st().flash.clear()

        // ---- R11. the real end-turn flow: the AI answers on its turn (game.nextTurn → automateCivMoves)
        act("tab", Tab.DIPLOMACY.name); act("selciv", "civ:$ai1")
        act("give", goldGive(ai1).id); act("trade"); flash("before end turn")
        check(pendingTo(ai1) == 1, "offer not pending before ending the turn")
        var guard = 0
        do {
            val now = t.s.meta.seatOfCiv(t.eng { g -> g.currentPlayerCiv.civID })!!
            t.ctl.apply(t.s, now, "end", listOf("force"), null)
        } while (t.eng { g -> g.currentPlayerCiv.civID } != a.civId && guard++ < 4)
        val endNotes = aliceNotes().filter { it.contains(ai1Name) && it.contains("trade request") }
        println("  [dp] after ending the turn: pending=${pendingTo(ai1)}, notes=$endNotes")
        check(t.eng { g -> g.currentPlayerCiv.civID } == a.civId, "Alice should be playing again")
        check(pendingTo(ai1) == 0 && endNotes.isNotEmpty(), "the AI didn't answer during its turn")
        ok("⏭️ end turn: the AI answers pending offers on its own turn")
        st().flash.clear()

        // ---- leave a normal state behind
        act("tab", Tab.DIPLOMACY.name); render("dp-diplomacy")
        st().give = emptyList(); st().ask = emptyList(); st().ext.remove(PENDING); st().flash.clear()
    }
}

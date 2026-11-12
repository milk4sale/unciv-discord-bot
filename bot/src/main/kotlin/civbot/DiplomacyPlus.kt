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
 * The core Diplomacy / City-states tabs (Panel.kt) and trade code (Features.kt) call into this object.
 */
object DiplomacyPlus : Extension {
    private const val RELATIONS = "RELATIONS"
    private const val CONFIRM = "DP_CONFIRM"
    private const val PENDING = "dp_pending"
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
    )
    override val viewVerbs = setOf("dp_war", "dp_denounce", "dp_cancel", "dp_gold", "dp_gpt")
    override val prompts = mapOf(
        "dp_gold" to Prompt("Custom gold amount", "How much gold?", "a whole number, e.g. 120"),
        "dp_gpt" to Prompt("Custom gold per turn", "How much gold per turn?", "a whole number, e.g. 3"),
    )

    // ============================================================== war / peace / denounce

    fun warLabel(civ: Civilization, other: Civilization): String {
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
        t.eb.addField("🌍 World politics", politicsText(civ).take(1024), false)
        t.rows += ActionRow.of(t.select("selciv", "Choose a civilization…",
            others(civ).map { Choice("civ:${it.civID}", it.civName, GameOps.relationText(civ, it).take(100), if (civ.isAtWarWith(it)) "⚔️" else "🤝") },
            "civ:${other.civID}"))
        val demands = demandChoices(civ, other)
        t.rows += ActionRow.of(t.select("dp_demand", if (demands.isEmpty()) "📢 No demands to make right now" else "📢 Make a demand of ${other.civName}…", demands, enabled = t.myTurn))
        t.endRow += t.button("tab", "Trade & war", "🤝", 2, true, Tab.DIPLOMACY.name)
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
                val blocked = dm == null || !dm.canDeclareWar()
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
        }
        t.endRow += t.button("dp_cancel", "Cancel", "↩️")
        other.getCapital()?.getCenterTile()?.takeIf { t.civ.hasExplored(it) }?.let { t.mapAround(it) } ?: t.worldMap()
    }

    private fun askConfirm(ctx: ActionCtx, kind: String) {
        val cs = ctx.args.firstOrNull() == "cs"
        val id = (if (cs) ctx.st.cityStateId else ctx.st.otherCivId) ?: throw PlayerError("Choose a civilization first.")
        val other = ctx.game.getCivilization(id)
        if (kind == "war" && ctx.civ.isAtWarWith(other)) throw PlayerError("You're already at war with ${other.civName}.")
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
            else -> throw PlayerError("Nothing to confirm.")
        }
        ctx.st.ext.remove(PENDING)
        ctx.st.tab = p.back
        ctx.flash += msg; ctx.s.meta.addEvent(msg)
        ctx.changed = true
    }

    // ============================================================== extension hooks

    override fun renderTab(tabId: String, t: TabCtx) {
        when (tabId) {
            RELATIONS -> renderRelations(t)
            CONFIRM -> renderConfirm(t)
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

        // ---- leave a normal state behind
        act("tab", Tab.DIPLOMACY.name); render("dp-diplomacy")
        st().give = emptyList(); st().ask = emptyList(); st().ext.remove(PENDING); st().flash.clear()
    }
}

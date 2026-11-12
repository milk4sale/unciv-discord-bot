package civbot

import com.unciv.Constants
import com.unciv.logic.GameInfo
import com.unciv.logic.battle.BattleUnitCapture
import com.unciv.logic.city.City
import com.unciv.logic.civilization.AlertType
import com.unciv.logic.civilization.CivFlags
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.NotificationCategory
import com.unciv.logic.civilization.NotificationIcon
import com.unciv.logic.civilization.PopupAlert
import com.unciv.logic.civilization.diplomacy.DeclareWarReason
import com.unciv.logic.civilization.diplomacy.Demand
import com.unciv.logic.civilization.diplomacy.DiplomacyFlags
import com.unciv.logic.civilization.diplomacy.DiplomaticModifiers
import com.unciv.logic.civilization.diplomacy.DiplomaticStatus
import com.unciv.logic.civilization.diplomacy.WarType
import com.unciv.logic.map.HexCoord
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.logic.civilization.managers.ReligionState
import com.unciv.logic.trade.Trade
import com.unciv.logic.trade.TradeEvaluation
import com.unciv.logic.trade.TradeLogic
import com.unciv.logic.trade.TradeOffer
import com.unciv.logic.trade.TradeOfferType
import com.unciv.logic.trade.TradeRequest
import com.unciv.models.Spy
import com.unciv.models.SpyAction
import com.unciv.models.metadata.GameSetupInfo
import com.unciv.models.ruleset.BeliefType
import com.unciv.models.ruleset.Event
import com.unciv.models.ruleset.EventChoice
import com.unciv.models.ruleset.MilestoneType
import com.unciv.models.ruleset.Policy
import com.unciv.models.ruleset.unique.GameContext
import com.unciv.models.ruleset.unique.UniqueType
import com.unciv.utils.randomWeighted
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent
import net.dv8tion.jda.api.interactions.commands.OptionType
import net.dv8tion.jda.api.interactions.commands.build.SubcommandData
import net.dv8tion.jda.api.interactions.components.ActionRow

/**
 * Statecraft: 🕵️ Espionage (move spies, coups), 🗳️ World Congress votes, 📨 Inbox (AI demands,
 * friendship offers, trade requests and other popup alerts). Mirrors the logic of Unciv's
 * EspionageScreen, DiplomaticVotePickerScreen/ResultScreen, AlertPopup and TradePopup.
 * Also: 📜 random events with choices (AlertPopup.addEvent / RenderEvent) as Inbox decisions,
 * 🔬 a research queue (TechPickerScreen's right-click queueing), ⛪ religion founding with a custom
 * name and any number of beliefs (ReligiousBeliefsPickerScreen) and 🌳 policy branch progress.
 */
object StatecraftPlus : Extension {
    override val viewVerbs = setOf("sp_spy", "sp_relbase", "sp_relname", "sp_belief", "sp_relpage")

    private const val ESPIONAGE = "ESPIONAGE"
    private const val CONGRESS = "CONGRESS"
    private const val INBOX = "INBOX"
    private const val RQUEUE = "RQUEUE"
    private const val RELFOUND = "RELFOUND"
    /** Civ flags (saved with the game). FLAG_SPIES = -1 never counts down; FLAG_VOTE lasts one voting round. */
    private const val FLAG_SPIES = "CivBotManualSpies"
    private const val FLAG_VOTE = "CivBotManualVote"
    private const val SETTING = "espionage"

    override val tabs = listOf(
        ExtTab(ESPIONAGE, "Espionage", "🕵️", "Your spies: steal tech, rig elections, guard cities") { it.gameInfo.isEspionageEnabled() },
        ExtTab(CONGRESS, "World Congress", "🗳️", "Diplomatic victory votes") { congressVisible(it) },
        ExtTab(INBOX, "Inbox", "📨", "Demands, offers, events and news waiting for you") { inboxVisible(it) },
        ExtTab(RQUEUE, "Research queue", "🔬", "Queue several research goals, remove entries") { it.tech.techsToResearch.size > 1 },
        ExtTab(RELFOUND, "Found religion", "⛪", "Name your religion and pick all its beliefs") { foundingVisible(it) },
    )

    override val prompts = mapOf(
        "sp_relname" to Prompt("Name your religion", "Religion name", "e.g. Church of the Sun"),
    )

    // ============================================================== settings / lobby

    override fun commands(): List<SubcommandData> = listOf(
        SubcommandData("espionage", "Host: turn espionage (spies) on or off before the game starts")
            .addOption(OptionType.BOOLEAN, "enabled", "Spies steal tech, rig city-state elections and guard cities", true)
    )

    override fun onCommand(event: SlashCommandInteractionEvent, bot: BotApi): Boolean {
        if (event.subcommandName != "espionage") return false
        val s = bot.gameHere(event.channel.id, event.user.id)
        val reply = when {
            s == null -> "There's no game lobby you're part of here."
            s.meta.status != Status.LOBBY -> "Espionage can only be changed in the lobby, before the game starts."
            s.meta.hostId != event.user.id -> "Only the host (<@${s.meta.hostId}>) can change that."
            else -> {
                val on = event.getOption("enabled")?.asBoolean ?: false
                s.meta.settings.extra[SETTING] = on.toString()
                bot.store.saveMeta(s.meta)
                bot.refreshLobby(s.meta)
                if (on) "🕵️ Espionage is **on** — every civ earns spies as the eras advance." else "🕵️ Espionage is **off**."
            }
        }
        event.reply(reply).setEphemeral(true).queue()
        return true
    }

    override fun applyToSetup(setup: GameSetupInfo, meta: GameMeta) {
        setup.gameParameters.espionageEnabled = meta.settings.extra[SETTING] == "true"
    }

    override fun lobbyLines(meta: GameMeta): List<String> =
        listOf(if (meta.settings.extra[SETTING] == "true") "🕵️ espionage on" else "🕵️ espionage off (`/civ espionage`)")

    override fun advisorSkips(civ: Civilization): Set<String> {
        val out = HashSet<String>()
        if (civ.hasFlag(FLAG_SPIES)) out += "spies"
        if ((civ.flagsCountdown[FLAG_VOTE] ?: 0) > 0) out += "vote"
        return out
    }

    private fun markManualSpies(ctx: ActionCtx) {
        ctx.civ.addFlag(FLAG_SPIES, -1)
        val key = "sp_manualSpies"
        val set = ctx.s.meta.settings.extra[key].orEmpty().split(",").filter { it.isNotBlank() }.toMutableSet()
        if (set.add(ctx.civ.civID)) ctx.s.meta.settings.extra[key] = set.joinToString(",")
    }

    // ============================================================== rendering / verbs

    override fun renderTab(tabId: String, t: TabCtx) {
        when (tabId) {
            ESPIONAGE -> renderEspionage(t)
            CONGRESS -> renderCongress(t)
            INBOX -> renderInbox(t)
            RQUEUE -> renderQueue(t)
            RELFOUND -> renderFounding(t)
        }
    }

    override fun handle(verb: String, ctx: ActionCtx): Boolean {
        when (verb) {
            "sp_spy" -> ctx.st.ext["sp_spy"] = ctx.value?.removePrefix("sp:spy:")?.toIntOrNull() ?: 0
            "sp_move" -> moveSpy(ctx, ctx.value ?: throw PlayerError("Pick a destination."))
            "sp_hide" -> moveSpy(ctx, "sp:to:hideout")
            "sp_coup" -> coup(ctx)
            "sp_vote" -> vote(ctx)
            "sp_ans" -> answer(ctx)
            "sp_dismiss" -> dismissAll(ctx)
            "sp_qadd" -> queueAdd(ctx)
            "sp_qdel" -> queueRemove(ctx)
            "sp_qclear" -> queueClear(ctx)
            "sp_relbase" -> {
                val base = ctx.value?.removePrefix("r:") ?: throw PlayerError("Pick a religion.")
                if (ctx.st.ext["sp_relDisplay"] == ctx.st.religionName) ctx.st.ext.remove("sp_relDisplay") // the name followed the old pick
                ctx.st.religionName = base
            }
            "sp_relname" -> renameReligion(ctx)
            "sp_belief" -> ctx.st.beliefs[ctx.args.firstOrNull()?.toIntOrNull() ?: throw PlayerError("Unknown belief slot.")] =
                ctx.value?.removePrefix("b:") ?: throw PlayerError("Pick a belief.")
            "sp_relpage" -> ctx.st.ext["sp_relPage"] = (ctx.st.ext["sp_relPage"] as? Int ?: 0) + 1
            "sp_relok" -> confirmReligion(ctx)
            else -> return false
        }
        return true
    }

    // ============================================================== 🕵️ espionage

    private fun spyStatus(spy: Spy): String {
        val turns = spy.turnsRemainingForAction
        return when (spy.action) {
            SpyAction.None -> "idle in the hideout"
            SpyAction.Dead -> "dead — new recruit in $turns turn(s)"
            SpyAction.Moving -> "travelling ($turns turn)"
            SpyAction.EstablishNetwork -> "establishing a network ($turns turns)"
            SpyAction.Surveillance -> "observing the city"
            SpyAction.StealingTech -> if (turns > 0) "stealing tech (~$turns turns)" else "stealing tech"
            SpyAction.RiggingElections -> "rigging elections" + if (turns > 0) " (election in $turns turns)" else ""
            SpyAction.Coup -> "✊ staging a coup at end of turn"
            SpyAction.CounterIntelligence -> "counter-intelligence"
        }
    }

    private fun selectedSpy(st: PanelState, civ: Civilization): Spy? {
        val list = civ.espionageManager.spyList
        if (list.isEmpty()) return null
        val i = (st.ext["sp_spy"] as? Int ?: 0).coerceIn(0, list.size - 1)
        st.ext["sp_spy"] = i
        return list[i]
    }

    private fun promisedNoSpies(civ: Civilization, city: City) =
        city.civ != civ && city.civ.isMajorCiv && city.civ.knows(civ) &&
            city.civ.getDiplomacyManager(civ)?.hasFlag(DiplomacyFlags.AgreedToNotSendSpies) == true

    /** Destinations as Unciv's EspionageScreen offers them: explored cities without one of our spies (own first, then majors, then city-states). */
    private fun destinations(civ: Civilization, spy: Spy): List<City> =
        civ.gameInfo.getCities()
            .filter { civ.hasExplored(it.getCenterTile()) && it != spy.getCityOrNull() && !it.espionage.hasSpyOf(civ) }
            .sortedWith(compareBy<City>({ it.civ != civ }, { it.civ.isCityState }, { it.civ.civName }, { it.name })).toList()

    private fun destinationChoice(civ: Civilization, c: City): Choice {
        val (emoji, what) = when {
            c.civ == civ -> "🛡️" to "Counter-intelligence: guard against enemy spies"
            c.civ.isCityState -> "🗳️" to "Rig elections in ${c.civ.civName}"
            else -> "🔬" to "Steal technology from ${c.civ.civName}"
        }
        val warn = if (promisedNoSpies(civ, c)) " ⚠️ breaks your promise not to spy" else ""
        val owner = if (c.civ == civ || c.civ.isCityState) "" else " (${c.civ.civName})"
        return Choice("sp:to:${c.id}", "${c.name}$owner", (what + warn).take(100), emoji)
    }

    private fun renderEspionage(t: TabCtx) {
        val civ = t.civ
        if (!t.game.isEspionageEnabled()) {
            t.eb.addField("🕵️ Espionage", "Espionage is turned off in this game (the host can enable it in the lobby with `/civ espionage enabled:true`).", false)
            t.worldMap(); return
        }
        val spies = civ.espionageManager.spyList
        val spy = selectedSpy(t.st, civ)
        if (spies.isEmpty()) {
            t.eb.addField("🕵️ Spies", "You have no spies yet. Every major civ gains one as the world enters new eras (from the Renaissance in the standard rules), and some wonders grant more.", false)
            t.worldMap(); return
        }
        val lines = spies.map { s ->
            val here = s.getCityOrNull()?.let { c -> c.name + if (c.civ != civ && !c.civ.isCityState) " (${c.civ.civName})" else "" } ?: "Spy Hideout"
            "${if (s == spy) "▶" else "•"} **${GameOps.clean(s.name)}** ${"★".repeat(s.getEffectiveRank().coerceIn(1, 5))} — 📍 $here — ${spyStatus(s)}"
        }
        t.eb.addField("🕵️ Your spies (${spies.size})", lines.joinToString("\n").take(1024), false)
        if (spy != null) {
            val city = spy.getCityOrNull()
            val extra = StringBuilder()
            if (city != null && city.civ != civ && !city.civ.isCityState) {
                extra.append("Techs they know that you could steal: **${civ.espionageManager.getTechsToSteal(city.civ).size}**")
                if (city.cityStats.currentCityStats.science <= 0f) extra.append(" (but this city makes no science)")
                extra.append('\n')
            }
            if (city != null && city.civ.isCityState) {
                extra.append("Ally: ${city.civ.allyCiv?.civName ?: "none"}. ")
                if (spy.canDoCoup()) runCatching { (spy.getCoupChanceOfSuccess(false) * 100).toInt() }.getOrNull()
                    ?.let { extra.append("A ✊ coup would succeed about **$it%** of the time (failure kills the spy).") }
                extra.append('\n')
            }
            if (city != null && promisedNoSpies(civ, city)) extra.append("⚠️ You promised ${city.civ.civName} not to spy on them.\n")
            if ("spies" !in Extensions.advisorSkips(civ))
                extra.append("🤖 Until you move a spy yourself, the advisor moves them at the end of each turn.")
            if (extra.isNotEmpty()) t.eb.addField("${GameOps.clean(spy.name)} — ${city?.name ?: "Spy Hideout"}", extra.toString().take(1024), false)
        }

        t.rows += ActionRow.of(t.select("sp_spy", "Select a spy…",
            spies.mapIndexed { i, s -> Choice("sp:spy:$i", GameOps.clean(s.name), "${s.getLocationName()} — ${spyStatus(s)}".take(100), if (s.isAlive()) "🕵️" else "💀") },
            spy?.let { "sp:spy:${spies.indexOf(it)}" }))
        val canAct = t.myTurn && spy != null && spy.isAlive() && !civ.isDefeated()
        val dests = if (spy != null) destinations(civ, spy).map { destinationChoice(civ, it) } else emptyList()
        t.rows += ActionRow.of(t.select("sp_move", if (canAct) "Send ${GameOps.clean(spy!!.name)} to…" else "Move spies on your turn",
            dests.take(25), enabled = canAct))
        t.endRow += t.button("sp_hide", "Hideout", "🏠", 2, canAct && spy?.getCityOrNull() != null)
        if (spy != null && spy.getCityOrNull()?.civ?.isCityState == true && (spy.canDoCoup() || spy.action == SpyAction.Coup)) {
            val staging = spy.action == SpyAction.Coup
            t.endRow += t.button("sp_coup", if (staging) "Cancel coup" else "Stage coup", "✊", if (staging) 2 else 4, canAct)
        }
        val center = spy?.getCityOrNull()?.getCenterTile()
        if (center != null) t.mapAround(center) else t.worldMap()
    }

    private fun moveSpy(ctx: ActionCtx, value: String) {
        ctx.requireTurn()
        if (!ctx.game.isEspionageEnabled()) throw PlayerError("Espionage is off in this game.")
        val spy = selectedSpy(ctx.st, ctx.civ) ?: throw PlayerError("You have no spies.")
        val name = GameOps.clean(spy.name)
        if (!spy.isAlive()) throw PlayerError("$name is dead — a new spy will be recruited soon.")
        val target = value.removePrefix("sp:to:")
        if (target == "hideout") {
            if (spy.getCityOrNull() == null) throw PlayerError("$name is already in the hideout.")
            spy.moveTo(null)
            ctx.flash += "🏠 $name returned to the hideout."
        } else {
            val city = destinations(ctx.civ, spy).firstOrNull { it.id == target }
                ?: throw PlayerError("Your spy can't go there (unexplored, or one of your spies is already there).")
            val warn = promisedNoSpies(ctx.civ, city)
            spy.moveTo(city)
            ctx.flash += "🕵️ $name is travelling to **${city.name}** — ${destinationChoice(ctx.civ, city).description}" +
                (if (warn) "\n⚠️ This may break your promise to ${city.civ.civName} if your spy gets caught." else "")
        }
        ctx.civ.cache.updateViewableTiles()
        markManualSpies(ctx)
        ctx.changed = true
    }

    private fun coup(ctx: ActionCtx) {
        ctx.requireTurn()
        val spy = selectedSpy(ctx.st, ctx.civ) ?: throw PlayerError("You have no spies.")
        val cs = spy.getCityOrNull()?.civ
        if (spy.action == SpyAction.Coup) {
            spy.setAction(SpyAction.CounterIntelligence, 10) // what Unciv's EspionageScreen does when you cancel
            ctx.flash += "✊ Coup in ${cs?.civName} called off."
        } else {
            if (!spy.canDoCoup()) throw PlayerError("A coup needs a set-up spy in a city-state you're not allied with.")
            val chance = runCatching { (spy.getCoupChanceOfSuccess(false) * 100).toInt() }.getOrDefault(0)
            spy.setAction(SpyAction.Coup, 1)
            ctx.flash += "✊ ${GameOps.clean(spy.name)} will stage a coup in **${cs?.civName}** at the end of your turn (~$chance% chance; failure kills the spy)."
        }
        markManualSpies(ctx)
        ctx.changed = true
    }

    // ============================================================== 🗳️ world congress

    private fun diploVictoryInPlay(civ: Civilization) = runCatching {
        civ.victoryManager.getAvailableVictories().any { v -> v.milestoneObjects.any { it.type == MilestoneType.WinDiplomaticVote } }
    }.getOrDefault(false)

    private fun congressVisible(civ: Civilization) = civ.isMajorCiv &&
        (diploVictoryInPlay(civ) || civ.getTurnsTillNextDiplomaticVote() != null || civ.mayVoteForDiplomaticVictory())

    /** VictoryManager.votesNeededForDiplomaticVictory is private: call it, or use the same formula. */
    private fun votesNeeded(civ: Civilization): Int {
        (Engine.callHiddenResult(civ.victoryManager, "votesNeededForDiplomaticVictory") as? Int)?.let { return it }
        val un = civ.victoryManager.getUNBuildingAndOwnerNames().second
        val n = civ.gameInfo.civilizations.count { !it.isBarbarian && !it.isSpectator && !it.isDefeated() } + (if (un != null) 1 else 0)
        return if (n > 28) n * 35 / 100 else n * (67 - (1.1 * n).toInt()) / 100 + 1
    }

    /** Who [civ] may vote for, like DiplomaticVotePickerScreen: known, undefeated major civs (not yourself). */
    private fun voteCandidates(civ: Civilization) = civ.gameInfo.civilizations
        .filter { it != civ && it.isMajorCiv && !it.isDefeated() && !it.isSpectator && civ.knows(it) }
        .sortedBy { it.civName }

    private fun renderCongress(t: TabCtx) {
        val civ = t.civ; val g = t.game
        val (unBuilding, unOwner) = civ.victoryManager.getUNBuildingAndOwnerNames()
        val turns = civ.getTurnsTillNextDiplomaticVote()
        val info = StringBuilder()
        if (unBuilding != null && unOwner != null)
            info.append("🏛️ **${GameOps.clean(unBuilding)}** belongs to ${g.getCivilization(unOwner).civName} (their vote counts double).\n")
        val trigger = g.ruleset.buildings.values.firstOrNull { it.hasUnique(UniqueType.OneTimeTriggerVoting) }?.name
        info.append(when {
            civ.mayVoteForDiplomaticVictory() -> "🗳️ **The World Congress is voting now — cast your vote below!**"
            turns == null -> "The World Congress hasn't convened yet. Voting starts once someone builds ${trigger?.let { "the **${GameOps.clean(it)}**" } ?: "the right wonder"}."
            turns == 0 -> "🗳️ Votes are being counted this turn."
            else -> "⏳ Next vote in **$turns** turn(s)."
        })
        info.append("\n🏆 A civ needs **${votesNeeded(civ)}** votes (and no tie) to be elected world leader")
        info.append(if (diploVictoryInPlay(civ)) " — that wins a **Diplomatic Victory**." else " (Diplomatic Victory is off in this game).")
        val elected = g.civilizations.filter { it.victoryManager.hasEverWonDiplomaticVote }
        if (elected.isNotEmpty()) info.append("\nElected before: " + elected.joinToString { it.civName })
        t.eb.addField("🗳️ World Congress", info.toString().take(1024), false)

        val votes = g.diplomaticVictoryVotesCast
        if (votes.isNotEmpty()) {
            val breakdown = civ.victoryManager.getDiplomaticVictoryVoteBreakdown()
            val tally = breakdown.results.entries.sortedByDescending { it.value }
                .joinToString("\n") { (id, n) -> "**${g.getCivilization(id).civName}** — $n vote(s)" }
            val cast = votes.entries.sortedBy { it.key }.take(20).joinToString("\n") { (voter, target) ->
                "${g.getCivilization(voter).civName} → ${target?.let { g.getCivilization(it).civName } ?: "abstained"}"
            }
            val outcome = GameOps.clean(breakdown.winnerText).replace("\n", " · ")
            t.eb.addField("📊 Votes this session (${votes.size})", (tally.ifEmpty { "No valid votes yet." } + "\n_${outcome}_\n\n" + cast).take(1024), false)
        }
        if (civ.civID in votes)
            t.eb.addField("Your vote", votes[civ.civID]?.let { "You voted for **${g.getCivilization(it).civName}**." } ?: "You abstained.", false)

        if (civ.mayVoteForDiplomaticVictory()) {
            val opts = voteCandidates(civ).map {
                Choice("sp:v:${it.civID}", "Vote for ${it.civName}", (InfoPlus.scoreSuffix(civ, it).removePrefix(" · ").let { s -> if (s.isEmpty()) "" else "$s · " } + GameOps.relationText(civ, it)).take(100), "🗳️")
            } + Choice("sp:v:-", "Abstain", "Don't vote for anyone", "🚫")
            t.rows += ActionRow.of(t.select("sp_vote", "Who should lead the world?", opts.take(25), enabled = t.myTurn))
        }
        t.worldMap()
    }

    private fun vote(ctx: ActionCtx) {
        ctx.requireTurn()
        if (!ctx.civ.mayVoteForDiplomaticVictory()) throw PlayerError("There's no vote you can take part in right now.")
        val target = ctx.value?.removePrefix("sp:v:") ?: throw PlayerError("Pick someone.")
        val chosen = if (target == "-") null else voteCandidates(ctx.civ).firstOrNull { it.civID == target }
            ?: throw PlayerError("You can't vote for them.")
        ctx.civ.diplomaticVoteForCiv(chosen?.civID)
        ctx.civ.addFlag(FLAG_VOTE, ctx.civ.getTurnsBetweenDiplomaticVotes().coerceAtLeast(1))
        ctx.flash += if (chosen == null) "🗳️ You abstained in the World Congress vote." else "🗳️ You voted for **${chosen.civName}** to lead the world."
        ctx.s.meta.addEvent("🗳️ ${ctx.civ.civName} cast a World Congress vote.")
        ctx.changed = true
    }

    // ============================================================== 📨 inbox

    private val DECISIONS = setOf(
        AlertType.DemandToStopSettlingCitiesNear, AlertType.DemandToStopSpreadingReligion, AlertType.DemandToStopSpyingOnUs,
        AlertType.SpyingOnUsDespiteOurPromise, AlertType.DemandToNotAttackUs, AlertType.DeclarationOfFriendship,
        AlertType.BulliedProtectedMinor, AlertType.AttackedProtectedMinor, AlertType.AttackedAllyMinor,
        AlertType.Denounced, AlertType.RecapturedCivilian, AlertType.CityTraded, AlertType.DiplomaticMarriage,
    )

    private fun demandOf(type: AlertType): Demand? =
        if (type == AlertType.SpyingOnUsDespiteOurPromise) Demand.DontSpyOnUs else Demand.entries.firstOrNull { it.demandAlert == type }

    private class Item(val key: String, val text: String, val options: List<Pair<String, String>>, val emoji: String,
                       /** per-option dropdown description (else the item's text) */
                       val details: Map<String, String> = emptyMap())

    private fun civOrNull(g: GameInfo, id: String) = g.civilizations.firstOrNull { it.civID == id }

    /** The alert as a decision with AlertPopup's answers, or null if it's just news (or no longer valid — AlertPopup skips those). */
    private fun decision(civ: Civilization, a: PopupAlert): Item? {
        if (a.type == AlertType.Event) return eventDecision(civ, a)
        if (a.type !in DECISIONS) return null
        val g = civ.gameInfo
        val key = "a:${a.type.name}:${a.value}"
        return runCatching {
            val d = demandOf(a.type)
            if (d != null) {
                val o = civOrNull(g, a.value)?.takeIf { !it.isDefeated() && civ.getDiplomacyManager(it) != null } ?: return null
                val prefix = if (a.type == AlertType.SpyingOnUsDespiteOurPromise) "caught your spy despite your promise:" else "demands:"
                return Item(key, "**${o.civName}** $prefix _${d.demandText}_", listOf(
                    "yes" to "Agree — ${d.acceptDemandText}",
                    "no" to "Refuse — ${d.refuseDemandText}" + if (d == Demand.DoNotAttackUs) " (WAR)" else ""), "📢")
            }
            when (a.type) {
                AlertType.DeclarationOfFriendship -> {
                    val o = civOrNull(g, a.value) ?: return null
                    if (o.isDefeated() || civ.getDiplomacyManager(o) == null || o.getDiplomacyManager(civ)?.diplomaticStatus == DiplomaticStatus.War) return null
                    Item(key, "**${o.civName}** offers a **Declaration of Friendship** (30 turns).", listOf(
                        "yes" to "Declare Friendship with ${o.civName}", "no" to "We are not interested"), "💛")
                }
                AlertType.BulliedProtectedMinor, AlertType.AttackedProtectedMinor, AlertType.AttackedAllyMinor -> {
                    val (bullyId, csId) = a.value.split('@')
                    val bully = civOrNull(g, bullyId) ?: return null; val cs = civOrNull(g, csId) ?: return null
                    if (bully.isDefeated() || civ.getDiplomacyManager(bully) == null) return null
                    val what = if (a.type == AlertType.BulliedProtectedMinor) "took tribute from" else "attacked"
                    val opts = ArrayList<Pair<String, String>>()
                    if (!civ.isAtWarWith(bully)) opts += "war" to "THIS MEANS WAR! (declare war on ${bully.civName})"
                    opts += "pay" to "You'll pay for this! (side with ${cs.civName})"
                    opts += "ok" to "Very well. (breaks your pledge to protect ${cs.civName})"
                    Item(key, "**${bully.civName}** $what **${cs.civName}**, a city-state you protect or are allied with.", opts, "🏰")
                }
                AlertType.Denounced -> {
                    val o = civOrNull(g, a.value)?.takeIf { !it.isDefeated() } ?: return null
                    val dm = civ.getDiplomacyManager(o) ?: return null
                    val opts = ArrayList<Pair<String, String>>()
                    if (dm.canDeclareWar() && !civ.isAtWarWith(o)) opts += "war" to "THIS MEANS WAR! (declare war on ${o.civName})"
                    opts += "ok" to "Very well."
                    Item(key, "**${o.civName}** has **denounced** you!", opts, "😠")
                }
                AlertType.RecapturedCivilian -> {
                    val unit = g.tileMap[HexCoord.fromString(a.value)].civilianUnit ?: return null
                    val owner = unit.originalOwningCiv?.takeIf { !it.isDefeated() } ?: return null
                    Item(key, "You liberated a **${unit.name}** that originally belonged to **${owner.civName}**. Return it?", listOf(
                        "yes" to "Yes, return it to ${owner.civName} (they'll be grateful)", "no" to "No, keep it"), "🔓")
                }
                AlertType.CityTraded -> {
                    val city = g.getCities().firstOrNull { it.id == a.value } ?: return null
                    val founder = city.foundingCivObject
                    val opts = ArrayList<Pair<String, String>>()
                    if (founder != null && founder != civ && !civ.isAtWarWith(founder)) opts += "lib" to "Liberate (return to ${founder.civName})"
                    opts += "keep" to "Keep it"
                    Item(key, "You received **${city.name}** in a trade. What would you like to do with it?", opts, "🏙️")
                }
                AlertType.DiplomaticMarriage -> {
                    val city = g.getCities().firstOrNull { it.id == a.value } ?: return null
                    val opts = ArrayList<Pair<String, String>>()
                    if (!civ.hasUnique(UniqueType.MayNotAnnexCities)) opts += "annex" to "Annex ${city.name}"
                    opts += "puppet" to "Puppet ${city.name}"
                    Item(key, "Diplomatic marriage: **${city.name}** joins you. Annex or puppet it?", opts, "💍")
                }
                else -> null
            }
        }.getOrNull()
    }

    private fun tradeItems(civ: Civilization): List<Item> {
        val g = civ.gameInfo
        val seen = HashMap<String, Int>()
        return civ.tradeRequests.mapNotNull { r ->
            val n = seen.merge(r.requestingCiv, 1, Int::plus)!! - 1
            val o = civOrNull(g, r.requestingCiv) ?: return@mapNotNull null
            if (!runCatching { TradeEvaluation().isTradeValid(r.trade, civ, o) }.getOrDefault(false)) return@mapNotNull null
            val tl = TradeLogic(civ, o).apply { currentTrade.set(r.trade) }
            Item("t:${r.requestingCiv}:$n", "**${o.civName}** proposes a deal: ${Features.describeTrade(tl)}",
                listOf("yes" to "Sounds good! (accept ${o.civName}'s deal)", "no" to "Not this time. (decline ${o.civName}'s deal)"), "🤝")
        }
    }

    private fun demandOfViolation(type: AlertType) = Demand.entries.firstOrNull { it.violationDiscoveredAlert == type }

    private fun newsText(civ: Civilization, a: PopupAlert): String {
        val g = civ.gameInfo
        val who = civOrNull(g, a.value)?.civName ?: GameOps.clean(a.value)
        return when (a.type) {
            AlertType.WarDeclaration -> "⚔️ **$who** declared war on you!"
            AlertType.FirstContact -> if (civOrNull(g, a.value)?.isCityState == true) "👋 You met the city-state of **$who**." else "👋 You met **$who**."
            AlertType.WonderBuilt -> "🏛️ You completed **$who**!"
            AlertType.TechResearched -> "🔬 Researched **$who**."
            AlertType.Defeated -> "☠️ **$who** has been defeated."
            AlertType.GoldenAge -> "🌟 Your empire enters a **Golden Age**!"
            AlertType.StartIntro -> "📜 Your civilization's story begins."
            AlertType.AcceptingDemand -> "🤝 **$who** agreed to your demand."
            AlertType.RejectingDemand -> "😤 **$who** rejected your demand."
            AlertType.BorderConflict -> "🛡️ **$who**: \"Remove your troops in our border immediately!\""
            AlertType.TilesStolen -> "🗺️ **$who**: \"Those lands were not yours to take.\""
            AlertType.CitySettledNearOtherCivDespiteOurPromise, AlertType.ReligionSpreadDespiteOurPromise, AlertType.AttackedUsDespitePromise ->
                "💢 **$who** noticed you broke a promise: _${demandOfViolation(a.type)?.violationNoticedText ?: ""}_"
            AlertType.GameHasBeenWon -> g.victoryData?.let { "🏆 **${civOrNull(g, it.winningCiv)?.civName ?: it.winningCiv}** won a ${it.victoryType} victory!" } ?: "🏆 The game has been won."
            AlertType.Event -> "📜 Event: ${GameOps.clean(a.value.substringBefore(com.unciv.Constants.stringSplitCharacter))}"
            else -> "• ${a.type.name}: $who"
        }
    }

    private fun inboxVisible(civ: Civilization) =
        civ.tradeRequests.isNotEmpty() || civ.popupAlerts.any { it.type != AlertType.CityConquered }

    private fun renderInbox(t: TabCtx) {
        val civ = t.civ
        val alerts = civ.popupAlerts.filter { it.type != AlertType.CityConquered }
        val decisions = alerts.mapNotNull { decision(civ, it) } + tradeItems(civ)
        val news = alerts.filter { decision(civ, it) == null }
        if (decisions.isEmpty() && news.isEmpty())
            t.eb.addField("📨 Inbox", "Nothing waiting for you. AI demands, friendship offers and trade offers show up here.", false)
        if (decisions.isNotEmpty())
            t.eb.addField("📨 Waiting for your answer (${decisions.size})",
                (decisions.take(10).mapIndexed { i, d -> "${d.emoji} **${i + 1}.** ${d.text}" }.joinToString("\n").take(760) +
                    (if (decisions.size > 10) "\n…and ${decisions.size - 10} more (in the dropdown)" else "") +
                    "\n_Answer below. Anything left unanswered is decided by the 🤖 advisor when you end your turn (events: Unciv's AI weighting; cities, civilians, denouncements, city-states: Unciv's default)._").take(1024), false)
        // events get their own field: the story text and what each choice does (like RenderEvent)
        for ((n, a) in alerts.filter { it.type == AlertType.Event }.withIndex()) {
            if (n >= 3 || t.eb.fields.size >= 20) break
            val (ev, _, choices) = eventChoices(civ, a) ?: continue
            val body = StringBuilder(eventText(ev).take(500))
            choices.forEachIndexed { j, c -> body.append("\n**${j + 1}. ${GameOps.clean(c.text)}**").append(choiceEffects(c).let { if (it.isEmpty()) "" else " — $it" }) }
            if (choices.isEmpty()) body.append("\n_(nothing to choose — just acknowledge it)_")
            t.eb.addField("📜 ${GameOps.clean(ev.name)}".take(256), body.toString().take(1024), false)
        }
        if (news.isNotEmpty())
            t.eb.addField("📰 News (${news.size})", news.takeLast(12).joinToString("\n") { newsText(civ, it) }.take(1024), false)
        if (decisions.isNotEmpty()) {
            val opts = decisions.take(10).flatMapIndexed { i, d ->
                d.options.map { (opt, label) ->
                    Choice("sp:${d.key}|$opt", "${i + 1}. $label".take(100), (d.details[opt] ?: GameOps.clean(d.text.replace("*", "").replace("_", ""))).take(100),
                        when { opt in setOf("yes", "lib", "keep", "annex", "puppet") -> "✅"; opt == "war" -> "⚔️"; opt == "no" -> "❌"; d.key.startsWith("ev:") -> "📜"; else -> "▫️" })
                }
            }
            t.rows += ActionRow.of(t.select("sp_ans", if (t.myTurn) "Answer…" else "Answer on your turn", opts, enabled = t.myTurn))
        }
        t.endRow += t.button("sp_dismiss", "Dismiss news", "🧹", 2, t.myTurn && news.isNotEmpty())
        t.worldMap()
    }

    private fun answer(ctx: ActionCtx) {
        ctx.requireTurn()
        val v = ctx.value?.removePrefix("sp:") ?: throw PlayerError("Pick an answer.")
        val key = v.substringBeforeLast("|"); val opt = v.substringAfterLast("|")
        val civ = ctx.civ; val g = ctx.game
        if (key.startsWith("t:")) {
            val parts = key.split(":", limit = 3)
            if (parts.size < 3) throw PlayerError("Unknown answer.")
            val from = parts[1]
            val req = civ.tradeRequests.filter { it.requestingCiv == from }.getOrNull(parts[2].toIntOrNull() ?: -1)
                ?: throw PlayerError("That offer is gone.")
            val o = g.getCivilization(from)
            civ.tradeRequests.remove(req)
            ctx.changed = true
            // TradePopup / WorldScreen: offers that became invalid are dropped
            if (!TradeEvaluation().isTradeValid(req.trade, civ, o))
                throw PlayerError("That deal isn't possible any more (things changed), so it was removed.")
            if (opt == "yes") {
                val tl = TradeLogic(civ, o); tl.currentTrade.set(req.trade)
                val text = Features.describeTrade(tl)
                tl.acceptTrade()
                o.addNotification("[${civ.civName}] has accepted your trade request", NotificationCategory.Trade, civ.civName, NotificationIcon.Trade)
                ctx.flash += "🤝 Deal done: $text"
                ctx.s.meta.addEvent("🤝 ${civ.civName} and ${o.civName} made a deal.")
            } else {
                req.decline(civ)
                o.addNotification("[${civ.civName}] has denied your trade request", NotificationCategory.Trade, civ.civName, NotificationIcon.Trade)
                ctx.flash += "👎 You declined ${o.civName}'s offer."
            }
            return
        }
        if (key.startsWith("ev:")) { answerEvent(ctx, key, opt); return }
        val parts = key.split(":", limit = 3)
        if (parts.size < 3 || parts[0] != "a") throw PlayerError("Unknown answer.")
        val alert = civ.popupAlerts.firstOrNull { it.type.name == parts[1] && it.value == parts[2] }
            ?: throw PlayerError("That message was already answered.")
        val item = decision(civ, alert)
        if (item == null || item.options.none { it.first == opt }) {
            civ.popupAlerts.remove(alert); ctx.changed = true
            throw PlayerError("That doesn't apply any more, so it was removed.")
        }
        civ.popupAlerts.remove(alert)
        ctx.flash += applyAnswer(civ, alert, opt) { ctx.s.meta.addEvent(it) }
        ctx.changed = true
    }

    /** The button actions of Unciv's AlertPopup. */
    private fun applyAnswer(civ: Civilization, a: PopupAlert, opt: String, publicEvent: (String) -> Unit): String {
        val g = civ.gameInfo
        val d = demandOf(a.type)
        if (d != null) {
            val o = g.getCivilization(a.value)
            val dm = civ.getDiplomacyManager(o)!!
            if (opt == "yes") { dm.agreeToDemand(d); return "📢 You agreed to ${o.civName}'s demand." }
            dm.refuseDemand(d) // for "do not attack us" this declares war by itself
            if (d == Demand.DoNotAttackUs) publicEvent("⚔️ ${civ.civName} refused ${o.civName}'s demand — war!")
            return "📢 You refused ${o.civName}'s demand." + if (d == Demand.DoNotAttackUs) " **You are now at war.**" else ""
        }
        return when (a.type) {
            AlertType.DeclarationOfFriendship -> {
                val o = g.getCivilization(a.value); val dm = civ.getDiplomacyManager(o)!!
                if (opt == "yes") {
                    dm.signDeclarationOfFriendship()
                    publicEvent("💛 ${civ.civName} and ${o.civName} declared friendship!")
                    "💛 You and ${o.civName} declared friendship!"
                } else { dm.otherCivDiplomacy().setFlag(DiplomacyFlags.DeclinedDeclarationOfFriendship, 20); "💔 You declined ${o.civName}'s friendship." }
            }
            AlertType.BulliedProtectedMinor, AlertType.AttackedProtectedMinor, AlertType.AttackedAllyMinor -> {
                val (bullyId, csId) = a.value.split('@')
                val bully = g.getCivilization(bullyId); val cs = g.getCivilization(csId)
                val dm = civ.getDiplomacyManager(bully)!!
                when (opt) {
                    "war" -> {
                        dm.sideWithCityState()
                        val reason = if (a.type == AlertType.AttackedAllyMinor) WarType.AlliedCityStateWar else WarType.ProtectedCityStateWar
                        dm.declareWar(DeclareWarReason(reason, cs))
                        cs.getDiplomacyManager(civ)?.addInfluence(20f)
                        publicEvent("⚔️ ${civ.civName} declared war on ${bully.civName} to defend ${cs.civName}!")
                        "⚔️ You declared war on ${bully.civName} for ${cs.civName}!"
                    }
                    "pay" -> { dm.sideWithCityState(); "😠 You warned ${bully.civName}." }
                    "ignore" -> "No reply to ${bully.civName} about ${cs.civName}: your pledge still stands."
                    else -> {
                        civ.addNotification("You have broken your Pledge to Protect [${cs.civName}]!", cs.cityStateFunctions.getNotificationActions(), NotificationCategory.Diplomacy, cs.civName)
                        cs.cityStateFunctions.removeProtectorCiv(civ, forced = true)
                        "You let it go — your pledge to protect ${cs.civName} is broken."
                    }
                }
            }
            AlertType.Denounced -> {
                val o = g.getCivilization(a.value)
                if (opt == "war") {
                    civ.getDiplomacyManager(o)!!.declareWar()
                    publicEvent("⚔️ ${civ.civName} declared war on ${o.civName}!")
                    "⚔️ You declared war on ${o.civName}!"
                } else "Very well."
            }
            AlertType.RecapturedCivilian -> {
                val tile = g.tileMap[HexCoord.fromString(a.value)]
                val unit = tile.civilianUnit!!
                val owner = unit.originalOwningCiv!!
                val name = unit.baseUnit.name
                if (opt == "yes") {
                    unit.destroy()
                    owner.cities.minByOrNull { it.getCenterTile().aerialDistanceTo(tile) }
                        ?.let { owner.units.placeUnitNearTile(it.location, name) }
                    if (owner.isCityState) owner.getDiplomacyManagerOrMeet(civ).addInfluence(45f)
                    else if (owner.isMajorCiv) owner.getDiplomacyManagerOrMeet(civ).setModifier(DiplomaticModifiers.ReturnedCapturedUnits, 20f)
                    owner.addNotification("Your captured [$name] has been returned by [${civ.civName}]", NotificationCategory.Diplomacy, NotificationIcon.Trade, name, civ.civName)
                    "🔓 You returned the $name to ${owner.civName}."
                } else { BattleUnitCapture.captureOrConvertToWorker(unit, civ); "You kept the $name." }
            }
            AlertType.CityTraded -> {
                val city = g.getCities().first { it.id == a.value }
                if (opt == "lib") { val f = city.foundingCivObject!!.civName; city.liberateCity(civ); "🕊️ You liberated ${city.name} — it returns to $f." }
                else "You keep ${city.name}."
            }
            AlertType.DiplomaticMarriage -> {
                val city = g.getCities().first { it.id == a.value }
                if (opt == "annex") { city.annexCity(); "💍 ${city.name} is now part of your empire." }
                else { city.isPuppet = true; city.cityStats.update(); "💍 ${city.name} is now your puppet." }
            }
            else -> "OK."
        }
    }

    private fun dismissAll(ctx: ActionCtx) {
        ctx.requireTurn()
        val before = ctx.civ.popupAlerts.size
        ctx.civ.popupAlerts.removeAll { it.type != AlertType.CityConquered && decision(ctx.civ, it) == null }
        val n = before - ctx.civ.popupAlerts.size
        ctx.flash += if (n == 0) "Nothing to dismiss." else "🧹 Dismissed $n message(s)."
        ctx.changed = true
    }

    // ============================================================== 📜 events (AlertPopup.addEvent / RenderEvent)

    /** The alert value is the event name + (stringSplitCharacter + "unitId=N")*, as AlertPopup.addEvent parses it. */
    private fun parseEvent(civ: Civilization, a: PopupAlert): Pair<Event?, MapUnit?> {
        val parts = a.value.split(Constants.stringSplitCharacter)
        var unit: MapUnit? = null
        for (p in parts.drop(1))
            if (p.startsWith("unitId=")) unit = p.substringAfter("unitId=").toIntOrNull()?.let { civ.units.getUnitById(it) }
        return civ.gameInfo.ruleset.events[parts[0]] to unit
    }

    /** The event, its unit and the choices on offer right now — null when the event no longer applies (AlertPopup drops those). */
    private fun eventChoices(civ: Civilization, a: PopupAlert): Triple<Event, MapUnit?, List<EventChoice>>? {
        val (ev, unit) = parseEvent(civ, a)
        if (ev == null) return null
        val choices = runCatching { ev.getMatchingChoices(GameContext(civ, unit = unit)) }.getOrNull() ?: return null
        return Triple(ev, unit, choices.toList())
    }

    private fun eventText(ev: Event): String =
        GameOps.clean(ev.text.ifBlank { ev.civilopediaText.joinToString(" ") { it.text }.trim() }).ifBlank { GameOps.clean(ev.name) }

    /** What a choice does: its triggerable uniques (and comments), as RenderEvent lists them. */
    private fun choiceEffects(c: EventChoice): String = runCatching {
        c.uniqueObjects.filter { (it.isTriggerable || it.type == UniqueType.Comment) && !it.isHiddenToUsers() }
            .joinToString("; ") { GameOps.clean(it.getDisplayText()) }
    }.getOrDefault("")

    /** Stable key for an event alert: hash of its value + which duplicate it is. */
    private fun eventKey(civ: Civilization, a: PopupAlert): String {
        val same = civ.popupAlerts.filter { it.type == AlertType.Event && it.value == a.value }
        return "ev:${Integer.toHexString(a.value.hashCode())}:${same.indexOfFirst { it === a }.coerceAtLeast(0)}"
    }

    private fun eventDecision(civ: Civilization, a: PopupAlert): Item? {
        val (ev, _, choices) = eventChoices(civ, a) ?: return null
        val name = GameOps.clean(ev.name)
        val text = "**$name** — " + eventText(ev).let { if (it == name) "" else it }.take(300)
        if (choices.isEmpty()) return Item(eventKey(civ, a), text, listOf("ok" to "OK ($name)"), "📜")
        val opts = choices.mapIndexed { j, c -> "c$j" to GameOps.clean(c.text).ifBlank { "Choice ${j + 1}" } }
        val details = choices.withIndex().associate { (j, c) -> "c$j" to choiceEffects(c).ifEmpty { name } }
        return Item(eventKey(civ, a), text.removeSuffix(" — "), opts, "📜", details)
    }

    private fun answerEvent(ctx: ActionCtx, key: String, opt: String) {
        val civ = ctx.civ
        val alert = civ.popupAlerts.firstOrNull { it.type == AlertType.Event && eventKey(civ, it) == key }
            ?: throw PlayerError("That event was already answered.")
        val found = eventChoices(civ, alert)
        // RenderEvent closes the popup (removing the alert) first, then triggers the choice
        civ.popupAlerts.remove(alert)
        ctx.changed = true
        val (ev, unit, choices) = found ?: throw PlayerError("That event doesn't apply any more, so it was removed.")
        if (opt == "ok" && choices.isEmpty()) { ctx.flash += "📜 ${GameOps.clean(ev.name)} noted."; return }
        val choice = opt.removePrefix("c").toIntOrNull()?.let { choices.getOrNull(it) }
        if (choice == null) { civ.popupAlerts.add(alert); throw PlayerError("That choice isn't available any more — pick again.") }
        val ok = choice.triggerChoice(civ, unit)
        ctx.flash += "📜 **${GameOps.clean(ev.name)}**: you chose _${GameOps.clean(choice.text)}_" +
            (choiceEffects(choice).let { if (it.isEmpty()) "" else " — $it" }) + (if (ok) "" else " (nothing happened)")
    }

    /** Alerts the advisor answers with Unciv's default instead of letting them vanish (the default is what an AI / no answer gets). */
    private val DEFAULTS = mapOf(
        AlertType.DiplomaticMarriage to "puppet",   // CityStateFunctions.diplomaticMarriage already made it a puppet
        AlertType.CityTraded to "keep",
        AlertType.RecapturedCivilian to "no",       // non-human captors: captureOrConvertToWorker
        AlertType.Denounced to "ok",
        AlertType.BulliedProtectedMinor to "ignore", AlertType.AttackedProtectedMinor to "ignore", AlertType.AttackedAllyMinor to "ignore",
    )

    /**
     * Called by [GameOps.advisor] before it clears the remaining alerts: pick event choices with Unciv's
     * AI weighting (UniqueTriggerActivation: randomWeighted by getWeightForAiDecision) and apply Unciv's
     * default to the other decisions, instead of deleting them unanswered.
     */
    fun advisorAnswerInbox(civ: Civilization): List<String> {
        val notes = ArrayList<String>()
        var rounds = 0
        while (rounds++ < 5) { // a choice can trigger a follow-up event
            val events = civ.popupAlerts.filter { it.type == AlertType.Event }
            if (events.isEmpty()) break
            for (a in events) {
                val found = eventChoices(civ, a)
                civ.popupAlerts.remove(a)
                val (ev, unit, choices) = found ?: continue
                if (choices.isEmpty()) continue
                val gc = GameContext(civ, unit = unit)
                val choice = choices.randomWeighted(gc.stateBasedRandom("CivBot.advisorEvent", ev.name.hashCode())) { it.getWeightForAiDecision(gc) }
                runCatching { choice.triggerChoice(civ, unit) }
                notes += "📜 Advisor answered the event **${GameOps.clean(ev.name)}**: _${GameOps.clean(choice.text)}_"
            }
        }
        for (a in civ.popupAlerts.filter { it.type in DEFAULTS }) {
            val valid = decision(civ, a) != null
            civ.popupAlerts.remove(a)
            if (!valid) continue
            runCatching { applyAnswer(civ, a, DEFAULTS[a.type]!!) {} }
                .onSuccess { notes += "🤖 Unanswered, so Unciv's default applies: $it" }
        }
        return notes
    }

    /** End-of-turn checklist line for unanswered Inbox decisions (skipped if another line already mentions the Inbox). */
    fun inboxWarning(civ: Civilization, existing: List<String>): String? {
        if (existing.any { it.contains("Inbox", ignoreCase = true) }) return null
        val items = civ.popupAlerts.filter { it.type != AlertType.CityConquered }.mapNotNull { decision(civ, it) } + tradeItems(civ)
        if (items.isEmpty()) return null
        val events = items.count { it.key.startsWith("ev:") }
        return "Answer **${items.size}** decision(s) in the 📨 Inbox" + (if (events > 0) " — incl. **$events** event(s)" else "") +
            " (or the 🤖 advisor decides)"
    }

    // ============================================================== 🔬 research queue (TechPickerScreen right-click queueing)

    private fun techRs(civ: Civilization) = civ.gameInfo.ruleset.technologies

    /** Every tech that can be appended to the queue (its missing prerequisites come along). */
    fun queueAddChoices(civ: Civilization): List<Choice> {
        val tm = civ.tech
        val queued = tm.techsToResearch.toSet()
        return techRs(civ).values
            .filter { !tm.isResearched(it.name) && it.name !in queued && !tm.isUnresearchable(it) }
            .mapNotNull { t ->
                val path = runCatching { tm.getRequiredTechsToDestination(t) }.getOrNull() ?: return@mapNotNull null
                if (path.any { tm.isUnresearchable(it) }) return@mapNotNull null
                val added = path.count { it.name !in queued }
                Triple(t, added, path)
            }
            .sortedWith(compareBy({ it.first.column?.columnNumber ?: 99 }, { it.first.name }))
            .map { (t, added, _) -> Choice("sp:q:${t.name}", t.name, "+$added tech(s) to the queue · ${t.era()}".take(100), "➕") }
    }

    private fun queueTurns(civ: Civilization, techs: List<String>): String {
        val sci = civ.stats.statsForNextTurn.science
        if (sci <= 0f) return "∞"
        val left = techs.sumOf { civ.tech.remainingScienceToTech(it).coerceAtLeast(0) }
        return kotlin.math.ceil(left / sci).toInt().coerceAtLeast(if (left > 0) 1 else 0).toString()
    }

    private fun renderQueue(t: TabCtx) {
        val civ = t.civ; val tm = civ.tech
        val q = tm.techsToResearch.toList()
        if (q.isEmpty()) t.eb.addField("🔬 Research queue", "Nothing queued. Pick research on the 🔬 Research tab, or add goals below.", false)
        else {
            val lines = q.mapIndexed { i, n ->
                "${if (i == 0) "▶" else "${i + 1}."} **$n** — ${tm.turnsToTech(n)} turn(s) · total ~${queueTurns(civ, q.take(i + 1))}"
            }
            val shown = StringBuilder()
            for ((i, l) in lines.withIndex()) {
                if (shown.length + l.length > 950) { shown.append("…and ${lines.size - i} more"); break }
                shown.append(l).append('\n')
            }
            t.eb.addField("🔬 Research queue (${q.size} techs, ~${queueTurns(civ, q)} turns)", shown.toString().trim(), false)
        }
        t.eb.addField("How it works", "➕ adds a goal **after** what's queued (with any missing prerequisites), like right-clicking in Unciv's tech tree. " +
            "➖ removes a tech and anything queued that needs it. Picking research on the 🔬 Research tab replaces the queue.", false)
        t.rows += ActionRow.of(t.select("sp_qadd", "➕ Add goal to queue…", queueAddChoices(civ), enabled = t.myTurn))
        val removeOpts = q.map { n ->
            val dependents = dependentsInQueue(civ, n).filter { it != n }
            Choice("sp:q:$n", "Remove $n", (if (dependents.isEmpty()) "Only this tech" else "Also removes: " + dependents.joinToString()).take(100), "➖")
        }
        t.rows += ActionRow.of(t.select("sp_qdel", "➖ Remove from queue…", removeOpts, enabled = t.myTurn && q.isNotEmpty()))
        t.endRow += t.button("sp_qclear", "Keep only current", "🧹", 2, t.myTurn && q.size > 1)
        t.worldMap()
    }

    /** [name] plus every queued tech that (transitively) needs it. */
    private fun dependentsInQueue(civ: Civilization, name: String): List<String> {
        val tm = civ.tech
        return tm.techsToResearch.filter { n ->
            n == name || techRs(civ)[n]?.let { t -> runCatching { tm.getRequiredTechsToDestination(t) }.getOrDefault(emptyList()).any { it.name == name } } == true
        }
    }

    private fun queueAdd(ctx: ActionCtx) {
        ctx.requireTurn()
        val name = ctx.value?.removePrefix("sp:q:") ?: throw PlayerError("Pick a tech.")
        val tm = ctx.civ.tech
        val tech = techRs(ctx.civ)[name] ?: throw PlayerError("Unknown tech.")
        if (tm.isResearched(name)) throw PlayerError("You already know $name.")
        if (name in tm.techsToResearch) throw PlayerError("$name is already queued.")
        if (tm.isUnresearchable(tech)) throw PlayerError("$name can't be researched in this game.")
        val path = tm.getRequiredTechsToDestination(tech)
        if (path.any { tm.isUnresearchable(it) }) throw PlayerError("$name needs a tech you can't research.")
        val added = path.map { it.name }.filter { it !in tm.techsToResearch }
        tm.techsToResearch.addAll(added)
        tm.updateResearchProgress()
        ctx.flash += "➕ Queued **$name**" + (if (added.size > 1) " (+${added.size - 1} prerequisite(s))" else "") +
            ". Queue: ${tm.techsToResearch.size} techs, ~${queueTurns(ctx.civ, tm.techsToResearch)} turns."
        ctx.changed = true
    }

    private fun queueRemove(ctx: ActionCtx) {
        ctx.requireTurn()
        val name = ctx.value?.removePrefix("sp:q:") ?: throw PlayerError("Pick a tech.")
        val tm = ctx.civ.tech
        if (name !in tm.techsToResearch) throw PlayerError("$name isn't queued.")
        val gone = dependentsInQueue(ctx.civ, name).toSet()
        val keep = tm.techsToResearch.filter { it !in gone }
        tm.techsToResearch.clear(); tm.techsToResearch.addAll(keep)
        ctx.flash += "➖ Removed **$name**" + (gone.size.let { if (it > 1) " and ${it - 1} tech(s) that need it" else "" }) + "." +
            (tm.currentTechnologyName()?.let { " Now researching **$it**." } ?: " ⚠️ Nothing is being researched now — pick something.")
        ctx.changed = true
    }

    private fun queueClear(ctx: ActionCtx) {
        ctx.requireTurn()
        val tm = ctx.civ.tech
        val cur = tm.currentTechnologyName() ?: throw PlayerError("Nothing is queued.")
        val n = tm.techsToResearch.size - 1
        tm.techsToResearch.clear(); tm.techsToResearch.add(cur)
        ctx.flash += "🧹 Cleared $n queued tech(s); still researching **$cur**."
        ctx.changed = true
    }

    // ============================================================== ⛪ found / enhance a religion (ReligiousBeliefsPickerScreen)

    private fun foundingVisible(civ: Civilization) = civ.gameInfo.isReligionEnabled() && Features.pendingBeliefTypes(civ) != null

    private fun displayName(st: PanelState): String? = (st.ext["sp_relDisplay"] as? String) ?: st.religionName

    /** Rows of belief dropdowns per page: founding needs one row for the religion pick. */
    private fun slotsPerPage(founding: Boolean) = if (founding) 2 else 3

    private fun renderFounding(t: TabCtx) {
        val civ = t.civ; val rm = civ.religionManager; val st = t.st
        val types = Features.pendingBeliefTypes(civ)
        if (!civ.gameInfo.isReligionEnabled() || types == null) {
            t.eb.addField("⛪ Religion", "Nothing to found or enhance right now. A Great Prophet founds (and later enhances) a religion.", false)
            t.worldMap(); return
        }
        val founding = rm.religionState == ReligionState.FoundingReligion
        // drop picks that aren't valid any more (someone else took the belief)
        val valid = types.indices.associateWith { i -> Features.beliefChoices(civ, types[i]).map { it.id.removePrefix("b:") } }
        for (i in types.indices) st.beliefs[i]?.let { if (it !in valid[i]!!) st.beliefs.remove(i) }
        val info = StringBuilder()
        if (founding) {
            info.append("Religion: **${displayName(st) ?: "— pick one below —"}**")
            if (st.religionName != null && displayName(st) != st.religionName) info.append(" _(icon: ${st.religionName})_")
            info.append("\nPick a religion below, then ✏️ **Rename** it if you like.\n")
        } else info.append("Enhancing **${rm.religion?.getReligionDisplayName()}** (beliefs: ${rm.religion?.getAllBeliefsOrdered()?.joinToString { it.name }}).\n")
        types.forEachIndexed { i, type ->
            val b = st.beliefs[i]
            info.append("${i + 1}. ${type.name} belief: ").append(b?.let { "**$it**" } ?: "_not chosen_").append('\n')
        }
        val ready = (!founding || st.religionName != null) && types.indices.all { st.beliefs[it] != null }
        info.append(if (ready) "✅ Ready — press **${if (founding) "Found" else "Enhance"}**." else "Pick everything, then confirm. (If you don't, the 🤖 advisor picks at the end of your turn.)")
        t.eb.addField(if (founding) "⛪ Found your religion" else "⛪ Enhance your religion", info.toString().take(1024), false)
        st.beliefs.values.mapNotNull { civ.gameInfo.ruleset.beliefs[it] }.take(6).forEach { b ->
            t.eb.addField("✨ ${b.name}", GameOps.clean(b.uniques.joinToString("\n")).ifEmpty { "—" }.take(1024), true)
        }

        if (founding) t.rows += ActionRow.of(t.select("sp_relbase", "Pick a religion (icon & name)…", Features.religionNameChoices(civ),
            st.religionName?.let { "r:$it" }, enabled = t.myTurn))
        val per = slotsPerPage(founding)
        val pages = (types.size + per - 1) / per
        val page = Math.floorMod(st.ext["sp_relPage"] as? Int ?: 0, pages.coerceAtLeast(1))
        for (i in page * per until minOf(types.size, (page + 1) * per)) {
            val type = types[i]
            val takenElsewhere = st.beliefs.filterKeys { it != i }.values.toSet()
            val opts = Features.beliefChoices(civ, type).filter { it.id.removePrefix("b:") !in takenElsewhere }
            t.rows += ActionRow.of(t.select("sp_belief", "${i + 1}. ${type.name} belief…", opts, st.beliefs[i]?.let { "b:$it" }, t.myTurn, i.toString()))
        }
        if (founding) t.endRow += t.askButton("sp_relname", "Rename", "✏️", 2, t.myTurn && st.religionName != null)
        if (pages > 1) {
            val next = (page + 1) % pages
            t.endRow += t.button("sp_relpage", "Beliefs ${next * per + 1}–${minOf(types.size, (next + 1) * per)} of ${types.size}", "▶️", 1)
        }
        t.endRow += t.button("sp_relok", if (founding) "Found ${displayName(st) ?: "religion"}".take(80) else "Enhance religion", "⛪", 3, t.myTurn && ready)
        t.worldMap()
    }

    private fun renameReligion(ctx: ActionCtx) {
        val g = ctx.game
        val base = ctx.st.religionName ?: throw PlayerError("Pick a religion first, then rename it.")
        val name = ctx.value?.trim()?.replace(Regex("\\s+"), " ").orEmpty()
        // AskTextPopup's checks in ReligiousBeliefsPickerScreen, plus: no Unciv placeholder brackets, short enough for Discord
        when {
            name.isEmpty() -> throw PlayerError("Type a name.")
            name.length > 40 -> throw PlayerError("Keep the name to 40 characters or less.")
            name.any { it in "[]{}<>" || it == Constants.stringSplitCharacter } -> throw PlayerError("The name can't contain brackets.")
            name == Constants.noReligionName -> throw PlayerError("Nice try.")
            name != base && g.ruleset.religions.any { it.equals(name, true) } -> throw PlayerError("$name is another religion's name — pick it from the list instead.")
            g.religions.values.any { it.name.equals(name, true) || it.getReligionDisplayName().equals(name, true) } -> throw PlayerError("A religion called $name already exists.")
        }
        if (name == base) ctx.st.ext.remove("sp_relDisplay") else ctx.st.ext["sp_relDisplay"] = name
        ctx.flash += "✏️ Your religion will be called **$name**."
    }

    private fun confirmReligion(ctx: ActionCtx) {
        ctx.requireTurn()
        val civ = ctx.civ; val rm = civ.religionManager; val st = ctx.st
        val types = Features.pendingBeliefTypes(civ) ?: throw PlayerError("There's no religion to found or enhance right now.")
        val founding = rm.religionState == ReligionState.FoundingReligion
        val picks = types.indices.map { st.beliefs[it] ?: throw PlayerError("Pick belief ${it + 1} (${types[it].name}) first.") }
        if (picks.distinct().size != picks.size) throw PlayerError("Pick different beliefs.")
        val chosen = picks.mapIndexed { i, n ->
            if (Features.beliefChoices(civ, types[i]).none { it.id == "b:$n" }) throw PlayerError("$n isn't available as a ${types[i].name} belief any more — pick another.")
            civ.gameInfo.ruleset.beliefs[n]!!
        }
        if (founding) {
            val base = st.religionName ?: throw PlayerError("Pick a religion first.")
            if (base in civ.gameInfo.religions.keys) throw PlayerError("$base was just founded by someone else — pick another.")
            val display = displayName(st) ?: base
            if (!Engine.callHidden(rm, "foundReligion", display, base)) throw PlayerError("Couldn't found the religion — try 🤖 Auto-pick.")
        }
        rm.chooseBeliefs(chosen, rm.usingFreeBeliefs())
        val name = rm.religion?.getReligionDisplayName() ?: "Your religion"
        ctx.flash += (if (founding) "⛪ **$name** is founded! " else "⛪ **$name** is enhanced! ") + "Beliefs: ${chosen.joinToString { it.name }}."
        if (founding) ctx.s.meta.addEvent("⛪ ${civ.civName} founded $name.")
        st.beliefs.clear(); st.religionName = null; st.ext.remove("sp_relDisplay"); st.ext.remove("sp_relPage")
        ctx.changed = true
    }

    // ============================================================== 🌳 policy branches (PolicyPickerScreen)

    /** One line per branch: adopted/total, completed, locked by era or blocked. */
    fun branchProgress(civ: Civilization): String? {
        val pm = civ.policies
        val branches = civ.gameInfo.ruleset.policyBranches.values.sortedBy { civ.gameInfo.ruleset.eras[it.era]?.eraNumber ?: 0 }
        if (branches.isEmpty()) return null
        val completed = runCatching { pm.completedBranches }.getOrDefault(emptySet())
        val eraNow = civ.getEraNumber()
        val lines = branches.map { b ->
            val members = b.policies.filter { it.policyBranchType == Policy.PolicyBranchType.Member }
            val total = members.size + 1
            val adopted = (if (pm.isAdopted(b.name)) 1 else 0) + members.count { pm.isAdopted(it.name) }
            val eraNeeded = civ.gameInfo.ruleset.eras[b.era]?.eraNumber ?: 0
            val (icon, note) = when {
                b in completed || adopted == total -> "✅" to " complete"
                adopted > 0 -> "📖" to ""
                eraNeeded > eraNow -> "🔒" to " (from the ${b.era})"
                !pm.isAdoptable(b) -> "⛔" to " (blocked)"
                else -> "▫️" to ""
            }
            "$icon **${b.name}** $adopted/$total$note"
        }
        val done = branches.count { b -> b in completed }
        return ("Completed branches: **$done**/${branches.size}\n" + lines.joinToString("\n")).take(1024)
    }

    // ============================================================== self test

    override fun selfTest(t: SelfTestCtx) {
        val alice = t.alice
        fun st() = t.ctl.state(t.s.meta.id, alice.userId)
        fun flash(tag: String) { println("  [sp $tag] " + st().flash.joinToString(" / ")) }
        fun act(verb: String, value: String?, feature: String?) = t.act(alice, verb, emptyList(), value, feature)
        val verbose = System.getenv("SP_VERBOSE") != null
        fun render(tag: String) {
            t.render(alice, tag)
            if (!verbose) return
            val r = t.eng { Panel.render(t.s, alice, st()) }
            println("  [sp render $tag] rows=${r.rows.size} " + r.rows.joinToString(" | ") { row -> row.components.joinToString { it.toString().take(60) } })
            r.embed.fields.forEach { println("    ## ${it.name}\n    " + it.value?.replace("\n", "\n    ")) }
        }

        // --- lobby setting
        val m = GameMeta(); m.settings.extra[SETTING] = "true"
        val setup = GameSetupInfo(); applyToSetup(setup, m)
        println("  [sp] lobby option -> espionageEnabled=${setup.gameParameters.espionageEnabled}, line '${lobbyLines(m).first()}'")

        // --- espionage
        val (majorCity, csCity) = t.eng { g ->
            g.gameParameters.espionageEnabled = true
            val a = GameOps.civOf(g, alice)
            repeat(2) { a.espionageManager.addSpy() }
            val foreign = g.getCities().filter { it.civ != a }
            for (c in foreign) c.getCenterTile().setExplored(a, true)
            for (c in foreign.map { it.civ }.distinct()) if (!a.knows(c)) a.diplomacyFunctions.makeCivilizationsMeet(c)
            foreign.firstOrNull { it.civ.isMajorCiv }?.id to foreign.firstOrNull { it.civ.isCityState && it.civ.allyCiv != a }?.id
        }
        act("tab", ESPIONAGE, null)
        render("sp-espionage-before")
        act("sp_spy", "sp:spy:0", "spy select")
        val own = t.eng { g -> GameOps.civOf(g, alice).cities.first().id }
        act("sp_move", "sp:to:$own", "spy -> own city (counter-intel)")
        flash("move1")
        if (majorCity != null) { act("sp_spy", "sp:spy:1", null); act("sp_move", "sp:to:$majorCity", "spy -> foreign city (steal tech)"); flash("move2") }
        println("  [sp] advisor skips after manual move: " + t.eng { g -> Extensions.advisorSkips(GameOps.civOf(g, alice)) })
        render("sp-espionage")
        act("sp_hide", null, "spy -> hideout")
        if (csCity != null) {
            act("sp_move", "sp:to:$csCity", "spy -> city-state (rig elections)")
            // fast-forward: the spy arrives and sets up its network
            t.eng { g -> val sp = GameOps.civOf(g, alice).espionageManager.spyList[1]; repeat(4) { sp.endTurn() }; println("  [sp] cs spy: ${sp.action}") }
            act("sp_coup", null, "spy coup")
            flash("coup")
            render("sp-espionage-cs")
            act("sp_coup", null, null)
        }

        // --- world congress
        t.eng { g ->
            val a = GameOps.civOf(g, alice)
            for (c in g.civilizations) if (!c.isBarbarian && !c.isSpectator) c.addFlag(CivFlags.TurnsTillNextDiplomaticVote.name, 0)
            val other = g.civilizations.first { it.isMajorCiv && it != a && !it.isDefeated() }
            if (!a.knows(other)) a.diplomacyFunctions.makeCivilizationsMeet(other)
            other.diplomaticVoteForCiv(a.civID)
        }
        act("tab", CONGRESS, null)
        render("sp-congress-vote")
        val cand = t.eng { g -> voteCandidates(GameOps.civOf(g, alice)).first().civID }
        act("sp_vote", "sp:v:$cand", "congress vote")
        flash("vote")
        println("  [sp] advisor skips after vote: " + t.eng { g -> Extensions.advisorSkips(GameOps.civOf(g, alice)) })
        render("sp-congress")

        // --- inbox: AI demands, friendship, trade requests, news
        val (x, y) = t.eng { g ->
            val a = GameOps.civOf(g, alice)
            val ais = g.civilizations.filter { it.isMajorCiv && !it.isHuman() && !it.isDefeated() }
            for (o in ais) if (!a.knows(o)) a.diplomacyFunctions.makeCivilizationsMeet(o)
            for (o in ais) if (a.isAtWarWith(o)) GameOps.makePeace(a, o)
            val x = ais.first(); val y = ais.getOrElse(1) { x }
            a.popupAlerts.add(PopupAlert(AlertType.DemandToStopSettlingCitiesNear, x.civID))
            a.popupAlerts.add(PopupAlert(AlertType.DemandToStopSpyingOnUs, y.civID))
            a.popupAlerts.add(PopupAlert(AlertType.DeclarationOfFriendship, y.civID))
            a.popupAlerts.add(PopupAlert(AlertType.FirstContact, x.civID))
            a.popupAlerts.add(PopupAlert(AlertType.WarDeclaration, y.civID))
            a.popupAlerts.add(PopupAlert(AlertType.GoldenAge, ""))
            x.addGold(500); y.addGold(500)
            a.tradeRequests.add(TradeRequest(x.civID, Trade().apply { theirOffers.add(TradeOffer("Gold", TradeOfferType.Gold, 50, g.speed)) }))
            a.tradeRequests.add(TradeRequest(y.civID, Trade().apply { theirOffers.add(TradeOffer("Gold", TradeOfferType.Gold, 20, g.speed)) }))
            x.civID to y.civID
        }
        act("tab", INBOX, null)
        render("sp-inbox")
        act("sp_ans", "sp:a:DemandToStopSettlingCitiesNear:$x|yes", "inbox: agree to demand")
        act("sp_ans", "sp:a:DemandToStopSpyingOnUs:$y|no", "inbox: refuse demand")
        act("sp_ans", "sp:a:DeclarationOfFriendship:$y|yes", "inbox: accept friendship")
        val goldBefore = t.eng { g -> GameOps.civOf(g, alice).gold }
        act("sp_ans", "sp:t:$x:0|yes", "inbox: accept trade request")
        act("sp_ans", "sp:t:$y:0|no", "inbox: decline trade request")
        flash("inbox")
        println("  [sp] gold $goldBefore -> ${t.eng { g -> GameOps.civOf(g, alice).gold }}")
        render("sp-inbox-news")
        act("sp_dismiss", null, "inbox: dismiss news")
        flash("dismiss")
        println("  [sp] inbox left: " + t.eng { g -> GameOps.civOf(g, alice).let { c -> c.popupAlerts.map { it.type.name } + c.tradeRequests.map { "trade" } } })
        // ================= events, picker gaps, inbox completeness
        val failures = ArrayList<String>()
        fun check(ok: Boolean, label: String) {
            if (ok) t.act(alice, "refresh", emptyList(), null, label) else { failures += label; println("  [sp] CHECK FAILED: $label") }
        }
        fun aliceCiv() = GameOps.civOf(t.s.game!!, alice) // call inside t.eng only (t.eng doesn't nest)
        fun visible(key: String) = st().visible[key].orEmpty()
        fun fields() = t.eng { Panel.render(t.s, alice, st()) }.embed.fields

        // --- 📜 a random event with choices, triggered the real way (UniqueTriggerActivation, presentation Alert)
        val evName = "CivBot Test Caravan"
        val unitId = t.eng { g ->
            g.ruleset.events[evName] = Event().apply {
                name = evName; text = "A merchant caravan arrives at your borders."
                choices = arrayListOf(
                    EventChoice().apply { text = "Buy their gold"; uniques = arrayListOf("Gain [77] [Gold]") },
                    EventChoice().apply { text = "Study their customs"; uniques = arrayListOf("Gain [33] [Culture]", "[1000]% weight to this choice for AI decisions") },
                )
            }
            val a = GameOps.civOf(g, alice)
            val u = a.units.getCivUnits().first()
            com.unciv.models.ruleset.unique.UniqueTriggerActivation.triggerUnique(com.unciv.models.ruleset.unique.Unique("Triggers a [$evName] event"), a, unit = u)
            com.unciv.models.ruleset.unique.UniqueTriggerActivation.triggerUnique(com.unciv.models.ruleset.unique.Unique("Triggers a [$evName] event"), a)
            println("  [sp] event alerts: " + a.popupAlerts.filter { it.type == AlertType.Event }.map { it.value.replace(Constants.stringSplitCharacter, '|') })
            u.id
        }
        check(t.eng { aliceCiv().popupAlerts.count { it.type == AlertType.Event && it.value.endsWith("unitId=$unitId") } == 1 }, "event: triggered as an Inbox alert (with unit context)")
        check(t.eng { GameOps.turnWarnings(aliceCiv()).any { it.contains("Inbox") && it.contains("event") } }, "event: end-turn checklist lists Inbox events")
        act("tab", INBOX, null)
        render("sp-inbox-events")
        check(visible("sp_ans").count { it.startsWith("sp:ev:") } == 4, "event: choices offered as Inbox answers")
        check(fields().any { it.name?.contains(evName) == true && it.value?.contains("Gain") == true }, "event: text and choice effects shown")
        act("sp_dismiss", null, null)
        check(t.eng { aliceCiv().popupAlerts.count { it.type == AlertType.Event } == 2 }, "event: Dismiss news keeps unanswered events")
        val gold0 = t.eng { aliceCiv().gold }
        val evOpt = visible("sp_ans").first { it.startsWith("sp:ev:") && it.endsWith("|c0") }
        act("sp_ans", evOpt, "event: answer a choice (triggerChoice)")
        flash("event")
        check(t.eng { aliceCiv().gold } > gold0 && t.eng { aliceCiv().popupAlerts.count { it.type == AlertType.Event } == 1 }, "event: chosen effect applied (gold) and alert removed")

        // --- 📨 decisions that used to vanish: kept until answered, then the advisor applies Unciv's default
        val (cityId, other, csName) = t.eng { g ->
            val a = GameOps.civOf(g, alice)
            val o = g.civilizations.first { it.isMajorCiv && it != a && !it.isDefeated() && a.knows(it) }
            val cs = g.civilizations.firstOrNull { it.isCityState && it.isAlive() && a.knows(it) && o.knows(it) }
                ?: g.civilizations.first { it.isCityState && it.isAlive() }.also { c -> if (!a.knows(c)) a.diplomacyFunctions.makeCivilizationsMeet(c); if (!o.knows(c)) o.diplomacyFunctions.makeCivilizationsMeet(c) }
            a.popupAlerts.add(PopupAlert(AlertType.CityTraded, a.cities.first().id))
            a.popupAlerts.add(PopupAlert(AlertType.Denounced, o.civID))
            a.popupAlerts.add(PopupAlert(AlertType.BulliedProtectedMinor, o.civName + "@" + cs.civName))
            Triple(a.cities.first().id, o.civID, cs.civName)
        }
        render("sp-inbox-decisions")
        act("sp_dismiss", null, null)
        check(t.eng { aliceCiv().popupAlerts.count { it.type in DEFAULTS } == 3 }, "inbox: city/denounce/city-state decisions stay until answered")
        check(visible("sp_ans").any { it.contains("BulliedProtectedMinor") } && visible("sp_ans").any { it.contains("Denounced:$other") }, "inbox: those decisions are answerable")
        act("advisor", null, "advisor: answers events + applies Unciv defaults")
        val notes = st().flash.toList()
        println("  [sp advisor] " + notes.joinToString(" / "))
        check(notes.any { it.contains("Advisor answered the event") && it.contains("Study their customs") }, "advisor: event picked with Unciv's AI weighting")
        check(notes.count { it.contains("Unciv's default") } == 3, "advisor: Unciv's default applied explicitly (city, denounce, city-state)")
        check(t.eng { aliceCiv().popupAlerts.none { it.type == AlertType.Event || it.type in DEFAULTS } } &&
            t.eng { g -> g.getCities().first { it.id == cityId }.civ == aliceCiv() }, "advisor: decisions resolved without side effects")
        println("  [sp] csName=$csName")
        st().flash.clear()

        // --- dropdowns are no longer cut at 25
        val goalCount = t.eng { GameOps.researchGoalChoices(aliceCiv()).size }
        check(goalCount > 25, "research goals: all reachable goals listed ($goalCount)")
        act("tab", "TECH", null); render("sp-tech")
        check(visible("goal").size == 23 && visible("sp_qadd").isNotEmpty(), "research tab: goals page + ➕ add-to-queue row")
        val anyBeliefs = t.eng { g -> Features.beliefChoices(aliceCiv(), BeliefType.Any).size to g.ruleset.beliefs.values.count { it.type != BeliefType.Pantheon && it.type != BeliefType.None } }
        println("  [sp] beliefs (Any) offered ${anyBeliefs.first} of ${anyBeliefs.second}")
        check(anyBeliefs.first > 25, "beliefs: no 25 cut-off (${anyBeliefs.first})")

        // --- 🔬 research queue: append several goals, show, remove
        val (first, goal1, prereq1, goal2) = t.eng { g ->
            val a = GameOps.civOf(g, alice); val tm = a.tech
            val f = GameOps.researchChoices(a).first().id.removePrefix("tech:")
            GameOps.setResearch(a, f)
            val techs = g.ruleset.technologies.values.filter { !tm.isResearched(it.name) && !tm.isUnresearchable(it) && it.name != f }
            val g1 = techs.filter { tm.getRequiredTechsToDestination(it).size >= 3 }.minBy { tm.getRequiredTechsToDestination(it).size }
            val p1 = tm.getRequiredTechsToDestination(g1).first { it.name != f }.name
            val g2 = techs.first { t2 -> t2 != g1 && tm.getRequiredTechsToDestination(t2).none { it.name == p1 || it == g1 } && tm.getRequiredTechsToDestination(t2).size >= 2 }
            listOf(f, g1.name, p1, g2.name)
        }
        act("sp_qadd", "sp:q:$goal1", "research queue: add goal")
        act("sp_qadd", "sp:q:$goal2", "research queue: add a second goal")
        flash("queue")
        val q1 = t.eng { aliceCiv().tech.techsToResearch.toList() }
        println("  [sp] queue: $q1")
        check(q1.first() == first && goal1 in q1 && goal2 in q1 && q1.indexOf(goal1) < q1.indexOf(goal2), "research queue: goals appended in order")
        act("tab", RQUEUE, null); render("sp-research-queue")
        check(fields().any { it.name?.startsWith("🔬 Research queue (${q1.size} techs") == true } && visible("sp_qdel").size == q1.size, "research queue: full queue shown")
        act("sp_qdel", "sp:q:$prereq1", "research queue: remove an entry")
        flash("qdel")
        val q2 = t.eng { aliceCiv().tech.techsToResearch.toList() }
        check(prereq1 !in q2 && goal1 !in q2 && goal2 in q2 && q2.first() == first, "research queue: removing a tech drops what needs it")
        act("sp_qclear", null, "research queue: keep only current")
        check(t.eng { aliceCiv().tech.techsToResearch.toList() } == listOf(first), "research queue: cleared")

        // --- ⛪ found a religion: custom name + 4 beliefs (paged dropdowns). Runs on a snapshot that is
        // restored afterwards, so later extensions' tests still see the pantheon stage.
        val religionSnapshot = t.eng { g -> Engine.serialize(g) }
        val founded = t.eng { g ->
            val a = GameOps.civOf(g, alice); val rm = a.religionManager
            if (!g.isReligionEnabled() || a.cities.isEmpty()) return@eng false
            fun setPriv(n: String, v: Any) = rm.javaClass.getDeclaredField(n).apply { isAccessible = true }.set(rm, v)
            setPriv("religionState", ReligionState.FoundingReligion)
            setPriv("foundingCityId", a.cities.first().id)
            setPriv("shouldChoosePantheonBelief", true)
            rm.freeBeliefs.add(BeliefType.Follower.name, 1)
            println("  [sp] beliefs to pick: " + Features.pendingBeliefTypes(a))
            true
        }
        if (founded) {
            act("tab", RELFOUND, null); render("sp-found-religion")
            val types = t.eng { Features.pendingBeliefTypes(aliceCiv())!! }
            check(types.size >= 3, "religion: ${types.size} beliefs to pick manually")
            val base = t.eng { Features.religionNameChoices(aliceCiv()).first().id }
            act("sp_relbase", base, "religion: pick religion")
            check(!t.act(alice, "sp_relname", emptyList(), "[Bad] {name}", null), "religion: bad names rejected")
            act("sp_relname", "Church of the Test Suite", "religion: custom display name")
            val picked = HashSet<String>()
            for ((i, type) in types.withIndex()) {
                if (i > 0 && i % 2 == 0) { act("sp_relpage", null, "religion: belief page flip"); render("sp-found-religion-p${i / 2 + 1}") }
                check(visible("sp_belief|$i").isNotEmpty(), "religion: dropdown for belief ${i + 1} (${type.name})")
                val b = t.eng { Features.beliefChoices(aliceCiv(), type).first { it.id.removePrefix("b:") !in picked }.id }
                picked += b.removePrefix("b:")
                t.act(alice, "sp_belief", listOf(i.toString()), b, "religion: pick belief")
            }
            act("sp_relok", null, "religion: found with all beliefs")
            flash("religion")
            val r = t.eng { aliceCiv().religionManager.religion }
            check(r != null && r.getReligionDisplayName() == "Church of the Test Suite" && r.name == base.removePrefix("r:") &&
                picked.all { r.hasBelief(it) }, "religion: founded with custom name and every picked belief")
        }
        t.eng { _ -> val g = Engine.deserialize(religionSnapshot); t.s.game = g; com.unciv.UncivGame.Current.gameInfo = g }
        st().beliefs.clear(); st().religionName = null; st().ext.remove("sp_relDisplay"); st().ext.remove("sp_relPage")

        // --- 🌳 policy branch progress
        act("tab", "POLICIES", null); render("sp-policies")
        val branchField = fields().firstOrNull { it.name == "🌳 Branches" }?.value.orEmpty()
        println("  [sp] branches: " + branchField.replace("\n", " | ").take(300))
        check(branchField.contains("Completed branches") && Regex("\\d+/\\d+").containsMatchIn(branchField), "policies: branch progress shown")

        if (failures.isNotEmpty()) throw IllegalStateException("StatecraftPlus checks failed: " + failures.joinToString("; "))
        // leave the voting session closed so later turns aren't affected
        t.eng { g -> for (c in g.civilizations) c.removeFlag(CivFlags.TurnsTillNextDiplomaticVote.name) }
        act("tab", "UNITS", null)
    }
}

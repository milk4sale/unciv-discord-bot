package civbot

import com.unciv.logic.civilization.PlayerType
import java.util.concurrent.ConcurrentHashMap

/** Result of an end-of-turn, for the Discord layer to announce. */
class TurnResult(
    val events: List<String>,
    val nextSeat: Seat?,
    val nextCivName: String,
    val turnNumber: Int,
    val gameOver: Boolean,
    val winnerText: String?,
)

/**
 * Applies player actions to a game. Shared by the Discord bot and the Activity add-on, so both
 * frontends behave identically. Everything here runs on the engine thread via [Engine].
 */
class Controller(val store: Store) {
    private val log = org.slf4j.LoggerFactory.getLogger("Controller")
    private val states = ConcurrentHashMap<String, PanelState>()
    fun state(gid: String, userId: String) = states.getOrPut("$gid:$userId") { PanelState() }

    fun isMyTurn(s: Session, seat: Seat) = s.meta.status == Status.RUNNING &&
        s.game?.currentPlayerCiv?.civID == seat.civId

    /**
     * Apply a panel action. [verb]/[args] come straight from component ids (see Panel.id);
     * [value] is the picked select value (several values are joined with newlines) or modal text.
     * Returns a [TurnResult] if the turn ended, so the caller can announce it.
     */
    fun apply(s: Session, seat: Seat, verbIn: String, argsIn: List<String>, valueIn: String? = null): TurnResult? {
        // Dropdown paging: "pg:<key>:<page>" just flips the page (multi-select picks are merged below)
        val nav = valueIn?.split("\n")?.firstOrNull { it.startsWith("pg:") }
        if (nav != null) {
            val st = state(s.meta.id, seat.userId)
            val rest = nav.removePrefix("pg:")
            st.pages[rest.substringBeforeLast(":")] = rest.substringAfterLast(":").toIntOrNull() ?: 0
            val picks = valueIn.split("\n").filter { it.isNotBlank() && !it.startsWith("pg:") }
            if (verbIn == "give") st.give = mergePicks(st.give, picks, st.visible[verbIn])
            if (verbIn == "ask") st.ask = mergePicks(st.ask, picks, st.visible[verbIn])
            return null
        }
        // "do:<verb>[:<arg>]" select values call another verb directly (see Ext.kt)
        val direct = valueIn?.takeIf { it.startsWith("do:") }?.removePrefix("do:")?.split(":", limit = 2)
        val verb = direct?.get(0) ?: verbIn
        val args = direct?.drop(1) ?: argsIn
        val value = if (direct != null) null else valueIn
        val st = state(s.meta.id, seat.userId)
        val game = s.game ?: throw PlayerError("This game hasn't started yet.")
        if (UnitsPlus.onPlayerAction(s, seat, st, verb)) store.saveMeta(s.meta) // any click stops multi-turn AutoPlay
        return Engine.withGame(game) {
            val civ = GameOps.civOf(game, seat)
            val mine = isMyTurn(s, seat)
            fun requireTurn() { if (!mine) throw PlayerError("It's not your turn — you can look around, but not act.") }
            fun unit() = st.unitId?.let { GameOps.unitById(civ, it) } ?: throw PlayerError("Select a unit first.")
            fun city() = civ.cities.firstOrNull { it.id == st.cityId } ?: throw PlayerError("Select a city first.")
            fun other(cs: Boolean = false) = game.getCivilization(
                (if (cs) st.cityStateId else st.otherCivId) ?: throw PlayerError("Choose a civilization first."))
            fun values() = value.orEmpty().split("\n").filter { it.isNotBlank() && it != "none" }
            var changed = false
            var result: TurnResult? = null
            if (verb != "end") st.confirmEnd = false
            val citiesBefore = civ.cities.map { it.id }.toSet()
            if (mine && verb !in VIEW_VERBS && Extensions.all.none { verb in it.viewVerbs }) Extensions.all.forEach { it.beforeMutation(s, seat, verb) }

            when (verb) {
                "tab" -> st.tab = args.firstOrNull() ?: value!!
                "refresh" -> {}
                // ---- units
                "selunit" -> st.unitId = value?.removePrefix("u:")?.toIntOrNull()
                "nextunit" -> st.unitId = GameOps.nextIdleUnit(civ, st.unitId?.let { GameOps.unitById(civ, it) })?.id
                    ?: st.unitId.also { st.flash += "✅ All units have orders." }
                "uact" -> { requireTurn(); val u = unit(); st.flash += GameOps.doUnitChoice(u, value!!); changed = true; afterUnitAction(civ, st, u) }
                "step" -> { requireTurn(); val u = unit(); st.flash += GameOps.stepUnit(u, args[0].toInt()); changed = true; afterUnitAction(civ, st, u) }
                "roadto" -> {
                    requireTurn(); val u = unit()
                    val target = resolveTile(game, civ, st, value!!.trim(), u.getTile().position)
                    st.flash += GameOps.connectRoad(u, target); changed = true; afterUnitAction(civ, st, u)
                }
                "goto" -> {
                    requireTurn(); val u = unit()
                    val target = resolveTile(game, civ, st, value!!.trim(), u.getTile().position)
                    st.flash += GameOps.moveUnit(u, target); changed = true; afterUnitAction(civ, st, u)
                }
                // ---- cities
                "selcity" -> st.cityId = value?.removePrefix("city:")
                "cprev", "cnext" -> {
                    val cs = GameOps.citiesOf(civ)
                    if (cs.isNotEmpty()) {
                        val i = cs.indexOfFirst { it.id == st.cityId }.coerceAtLeast(0)
                        st.cityId = cs[Math.floorMod(i + if (verb == "cnext") 1 else -1, cs.size)].id
                    }
                }
                "qmode" -> st.queueMode = !st.queueMode
                "prod" -> {
                    requireTurn(); val c = city(); val name = value!!.removePrefix("prod:")
                    st.flash += if (st.queueMode) {
                        if (c.isPuppet) throw PlayerError("Puppet cities choose their own production.")
                        GameOps.queueConstruction(c, name)
                    } else GameOps.setProduction(c, name)
                    changed = true
                }
                "cact" -> {
                    requireTurn(); val c = city(); val v = value!!
                    st.flash += when {
                        v.startsWith("buy:") -> GameOps.buy(c, v.removePrefix("buy:"))
                        v.startsWith("faith:") -> Features.buyWithFaith(c, v.removePrefix("faith:"))
                        v.startsWith("bomb:") -> {
                            val (x, y) = v.removePrefix("bomb:").split(",").map { it.toInt() }
                            GameOps.bombard(c, game.tileMap[x, y])
                        }
                        else -> Extensions.all.firstNotNullOfOrNull { it.doCityChoice(c, v) } ?: Features.doCityExtra(c, v)
                    }
                    changed = true
                }
                "conq" -> {
                    requireTurn()
                    val city = game.getCities().firstOrNull { it.id == args[0] } ?: throw PlayerError("City not found.")
                    st.flash += GameOps.resolveConquest(civ, city, value!!); changed = true
                }
                // ---- research, policies, religion, great people
                "tech", "goal" -> { requireTurn(); st.flash += GameOps.setResearch(civ, value!!.substringAfter(":")); changed = true }
                "pol" -> { requireTurn(); st.flash += GameOps.adoptPolicy(civ, value!!.removePrefix("pol:")); changed = true }
                "pantheon" -> { requireTurn(); st.flash += Features.foundPantheon(civ, value!!.removePrefix("b:")); changed = true }
                "relname" -> st.religionName = value?.removePrefix("r:")
                "belief" -> st.beliefs[args[0].toInt()] = value!!.removePrefix("b:")
                "relconfirm" -> {
                    requireTurn()
                    val n = Features.pendingBeliefTypes(civ)?.size ?: 0
                    st.flash += Features.completeReligion(civ, st.religionName, (0 until n).map { st.beliefs[it] ?: "" })
                    st.beliefs.clear(); st.religionName = null; changed = true
                }
                "gp" -> { requireTurn(); st.flash += Features.chooseGreatPerson(civ, value!!.removePrefix("gp:")); changed = true }
                // ---- diplomacy
                "selciv" -> { st.otherCivId = value?.removePrefix("civ:"); st.give = emptyList(); st.ask = emptyList() }
                "give" -> st.give = mergePicks(st.give, values(), st.visible["give"])
                "ask" -> st.ask = mergePicks(st.ask, values(), st.visible["ask"])
                "trade" -> {
                    requireTurn(); val o = other()
                    val tl = Features.buildTrade(civ, o, st.give, st.ask)
                    if (!Features.tradeIsValid(tl)) throw PlayerError("That deal isn't possible — check what's selected.")
                    if (o.playerType == PlayerType.Human) {
                        propose(Proposal(s.meta.id, "trade", civ.civID, o.civID, st.give, st.ask, Features.describeTrade(tl)))
                        st.flash += "📨 Deal sent to ${o.civName}: ${Features.describeTrade(tl)}"
                    } else {
                        val msg = Features.proposeTradeToAI(tl)
                        st.flash += msg; if (msg.startsWith("🤝 Deal")) s.meta.addEvent("🤝 ${civ.civName} and ${o.civName} made a deal.")
                    }
                    st.give = emptyList(); st.ask = emptyList(); changed = true
                }
                "war" -> {
                    requireTurn(); val o = other(args.firstOrNull() == "cs")
                    val msg = GameOps.declareWar(civ, o)
                    st.flash += msg; s.meta.addEvent(msg); changed = true
                }
                "peace" -> {
                    requireTurn(); val o = other(args.firstOrNull() == "cs")
                    DiplomacyPlus.peaceBlocker(civ, o)?.let { throw PlayerError(it) } // 10-turn lock after a declaration, city-state's ally
                    if (o.isCityState) {
                        val msg = DiplomacyPlus.cityStatePeace(civ, o); st.flash += msg; s.meta.addEvent(msg)
                    } else if (o.playerType == PlayerType.Human) {
                        propose(Proposal(s.meta.id, "peace", civ.civID, o.civID))
                        st.flash += "🕊️ Peace proposal sent to ${o.civName}."
                    } else {
                        val msg = GameOps.proposePeaceToAI(civ, o)
                        st.flash += msg; if (msg.contains("signed")) s.meta.addEvent(msg)
                    }
                    changed = true
                }
                "friend" -> {
                    requireTurn(); val o = other()
                    if (!Features.friendshipAvailable(civ, o)) throw PlayerError("Friendship isn't possible with them right now.")
                    if (o.playerType == PlayerType.Human) {
                        propose(Proposal(s.meta.id, "friend", civ.civID, o.civID))
                        st.flash += "💛 Friendship offer sent to ${o.civName}."
                    } else {
                        val msg = Features.befriendAI(civ, o)
                        st.flash += msg; if (msg.contains("declared")) s.meta.addEvent(msg)
                    }
                    changed = true
                }
                "denounce" -> { requireTurn(); val msg = Features.denounce(civ, other()); st.flash += msg; s.meta.addEvent(msg); changed = true }
                // ---- city-states
                "selcs" -> st.cityStateId = value?.removePrefix("cs:")
                "csact" -> { requireTurn(); st.flash += Features.doCityState(civ, other(cs = true), value!!); changed = true }
                // ---- map
                "pan" -> { st.mapCenter = Features.pan(game, st.mapCenter ?: GameOps.focusTile(civ).position, args[0].toInt()); st.inspect = null }
                "home" -> { st.mapCenter = null; st.inspect = null; st.wholeMap = false }
                "whole" -> st.wholeMap = !st.wholeMap
                "inspect" -> {
                    val t = resolveTile(game, civ, st, value!!.trim(), st.mapCenter ?: GameOps.focusTile(civ).position)
                    st.inspect = t.position
                }
                // ---- turn
                "advisor" -> {
                    requireTurn()
                    val notes = GameOps.advisor(civ, fillPicks = true)
                    st.flash += notes.ifEmpty { listOf("🤖 Nothing needed picking.") }
                    changed = true
                }
                "end" -> {
                    requireTurn()
                    val force = args.firstOrNull() == "force"
                    val warnings = GameOps.turnWarnings(civ)
                    if (warnings.isNotEmpty() && !force && !st.confirmEnd) {
                        st.confirmEnd = true
                    } else {
                        st.confirmEnd = false
                        result = endTurn(s)
                    }
                }
                else -> {
                    val ctx = ActionCtx(s, seat, st, game, civ, mine, args, value)
                    if (!Extensions.all.any { it.handle(verb, ctx) }) throw PlayerError("Unknown action.")
                    changed = ctx.changed
                    if (ctx.endTurn && mine) result = endTurn(s)
                }
            }
            if (verb in setOf("uact", "step", "goto")) {
                Features.flagNewConquests(civ, citiesBefore)
                for (c in civ.cities) if (c.id !in citiesBefore) s.meta.addEvent("🏰 ${civ.civName} captured ${c.name}!")
            }
            if (changed && result == null) store.saveGame(s)
            result
        }
    }

    companion object {
        /** Panel verbs that only change what's shown (no game-state change, no undo snapshot) */
        val VIEW_VERBS = setOf("tab", "refresh", "selunit", "nextunit", "selcity", "cprev", "cnext", "qmode", "relname",
            "belief", "selciv", "give", "ask", "selcs", "pan", "home", "whole", "inspect", "end")
        fun resolveTileFor(game: com.unciv.logic.GameInfo, civ: com.unciv.logic.civilization.Civilization, st: PanelState,
                           input: String, fallbackCenter: com.unciv.logic.map.HexCoord): com.unciv.logic.map.tile.Tile {
            val window = st.window ?: MapWindow(fallbackCenter)
            return window.parse(input)?.let { (c, r) ->
                game.tileMap.values.firstOrNull { t -> window.cell(t.position.x, t.position.y) == Pair(c, r) }
            } ?: game.getCities().firstOrNull { it.name.equals(input.trim(), true) && it.getCenterTile().isExplored(civ) }?.getCenterTile()
            ?: throw PlayerError("Couldn't find tile **$input**. Use a code from the map, like D5, or a city name you've seen.")
        }
    }

    /** A tile code from the last map shown (e.g. "D5") or the name of a city the player has seen. */
    private fun resolveTile(game: com.unciv.logic.GameInfo, civ: com.unciv.logic.civilization.Civilization, st: PanelState,
                            input: String, fallbackCenter: com.unciv.logic.map.HexCoord): com.unciv.logic.map.tile.Tile {
        val window = st.window ?: MapWindow(fallbackCenter)
        return window.parse(input)?.let { (c, r) ->
            game.tileMap.values.firstOrNull { t -> window.cell(t.position.x, t.position.y) == Pair(c, r) }
        } ?: game.getCities().firstOrNull { it.name.equals(input, true) && it.getCenterTile().isExplored(civ) }?.getCenterTile()
        ?: throw PlayerError("Couldn't find tile **$input**. Use a code from the map, like D5, or a city name you've seen.")
    }

    /** Multi-select over pages: keep picks from pages not shown, replace those on the shown page. */
    private fun mergePicks(old: List<String>, now: List<String>, shown: List<String>?): List<String> =
        (old.filter { shown == null || it !in shown } + now).distinct()

    /** Ends whoever's turn it is (used for AFK auto-skip and when a player is removed). */
    fun forceEndTurn(s: Session): TurnResult = Engine.withGame(s.game!!) { endTurn(s) }

    private fun afterUnitAction(civ: com.unciv.logic.civilization.Civilization, st: PanelState, u: com.unciv.logic.map.mapunit.MapUnit) {
        // Unit died or used all moves -> jump to the next one that needs orders (like Unciv's auto-cycle)
        val alive = civ.units.getCivUnits().any { it == u }
        if (!alive || !u.hasMovement() || !u.isIdle() || !u.due) {
            GameOps.nextIdleUnit(civ, u)?.let { st.unitId = it.id }
        }
    }

    /** Must be called inside Engine.withGame. */
    fun endTurn(s: Session): TurnResult {
        // Snapshot first: if the engine throws mid-way through the AI turns, nothing is half-applied.
        val snapshot = Engine.serialize(s.game!!)
        val events = try {
            GameOps.endTurn(s.game!!, s.game!!.currentPlayerCiv).toMutableList()
        } catch (e: Exception) {
            log.warn("Engine error while ending turn in game {} ({}); restoring and retrying", s.meta.id, e.toString())
            restore(s, snapshot)
            log.info("Relocated {} stuck AI settler(s)", GameOps.unstickAISettlers(s.game!!))
            try {
                GameOps.endTurn(s.game!!, s.game!!.currentPlayerCiv).toMutableList()
            } catch (e2: Exception) {
                log.error("End turn failed twice in game {}", s.meta.id, e2)
                restore(s, snapshot)
                throw PlayerError("The game engine hit an error while running the AI turns (${e2.message}). " +
                    "Nothing was lost; try ending the turn again, or move your units differently first.")
            }
        }
        val game = s.game!!
        Extensions.all.forEach { runCatching { it.onTurnEnded(s) }.onFailure { e -> log.warn("onTurnEnded failed", e) } }
        // AI-controlled seats (players who left) get played automatically
        var guard = 0
        while (guard++ < 50) {
            val seat = s.meta.seatOfCiv(game.currentPlayerCiv.civID)
            // (multi-turn AutoPlay: UnitsPlus lets the AI play that human's turn, then it ends here too)
            if (seat == null || (!seat.aiControlled && !UnitsPlus.autoPlayTurn(s, seat, events))) break
            events += GameOps.endTurn(game, game.currentPlayerCiv)
        }
        val winner = GameOps.winner(game)
        val humansAlive = s.meta.seats.any { !it.aiControlled && GameOps.civOf(game, it).isAlive() }
        val over = winner != null || !humansAlive
        var winnerText: String? = null
        if (over) {
            s.meta.status = Status.ENDED
            winnerText = winner?.let { w ->
                val seat = s.meta.seatOfCiv(w.civID)
                "🏆 **${w.civName}**${seat?.let { " (<@${it.userId}>)" } ?: ""} wins!"
            } ?: "☠️ All human players have been defeated."
            s.meta.winner = winnerText
        }
        events.forEach { s.meta.addEvent(it) }
        s.meta.turnStartedAt = System.currentTimeMillis()
        s.meta.lastReminderAt = 0
        store.saveGame(s)
        val next = s.meta.seatOfCiv(game.currentPlayerCiv.civID)
        return TurnResult(events, next, game.currentPlayerCiv.civName, game.turns + 1, over, winnerText)
    }

    private fun restore(s: Session, snapshot: String) {
        val g = Engine.deserialize(snapshot)
        s.game = g
        com.unciv.UncivGame.Current.gameInfo = g
    }

    /** An offer from one human player to another, answered with buttons in the game thread. */
    class Proposal(
        val gid: String, val kind: String, val fromCiv: String, val toCiv: String,
        val give: List<String> = emptyList(), val ask: List<String> = emptyList(), val text: String = "",
    ) { val id = java.lang.Long.toString(System.nanoTime() % 2176782336L, 36) }

    val proposals = ConcurrentHashMap<String, Proposal>()
    /** Proposals the bot still has to post */
    val outbox = java.util.concurrent.ConcurrentLinkedQueue<Proposal>()
    private fun propose(p: Proposal) { proposals[p.id] = p; outbox += p }

    /** Accept or reject a proposal. Call on the engine thread. */
    fun answer(s: Session, p: Proposal, accept: Boolean): String {
        proposals.remove(p.id)
        val game = s.game!!
        val a = game.getCivilization(p.fromCiv); val b = game.getCivilization(p.toCiv)
        if (!accept) return when (p.kind) {
            "peace" -> "⚔️ ${b.civName} rejected peace with ${a.civName}."
            "friend" -> "💔 ${b.civName} declined friendship with ${a.civName}."
            else -> "❌ ${b.civName} rejected the deal from ${a.civName}."
        }
        val msg = when (p.kind) {
            "peace" -> DiplomacyPlus.peaceBlocker(a, b)?.let { "❌ $it" } ?: GameOps.makePeace(a, b)
            "friend" -> if (Features.friendshipAvailable(a, b)) Features.signFriendship(a, b) else "Friendship isn't possible any more."
            else -> {
                val tl = Features.buildTrade(a, b, p.give, p.ask)
                if (!Features.tradeIsValid(tl)) "❌ That deal is no longer possible (things changed since it was offered)."
                else { tl.acceptTrade(); "🤝 Deal done: ${Features.describeTrade(tl)}" }
            }
        }
        s.meta.addEvent(msg)
        store.saveGame(s)
        return msg
    }
}

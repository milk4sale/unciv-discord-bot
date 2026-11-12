package civbot

import com.unciv.logic.map.mapunit.MapUnit
import java.io.File

/**
 * Offline smoke test — no Discord needed: `./run.sh --selftest`.
 * Plays a 2-human + 3-AI game through the same Controller the bot uses, touching every panel
 * tab and feature, renders every tab to PNG and checks save/load. Prints a feature checklist.
 */
object SelfTest {
    private const val TURNS = 6 // human turns (two players -> ~15 game turns)
    private val hits = LinkedHashMap<String, Int>()
    private val errors = ArrayList<String>()
    private fun hit(name: String) { hits[name] = (hits[name] ?: 0) + 1 }

    fun run(out: File) {
        out.mkdirs()
        val store = Store(File(out, "data"))
        val ctl = Controller(store)
        val meta = GameMeta(id = "test01", name = "Self test", hostId = "1")
        meta.seats += Seat("1", "Alice", "Rome")
        meta.seats += Seat("2", "Bob", "Random")
        meta.settings.aiCivs = 3
        meta.settings.mapSize = "Small"
        meta.settings.cityStates = 6
        // Fixed map seed so the test (and CI) plays the same world every run. SELFTEST_SEED=random for a random map.
        val seed = System.getenv("SELFTEST_SEED") ?: "424242"
        if (seed != "random") meta.settings.extra[SetupPlus.K_SEED] = seed
        println("Self-test map seed: $seed")
        val game0 = Engine.run { GameOps.startGame(meta) }
        val s = Session(meta, game0)
        meta.status = Status.RUNNING
        store.sessions[meta.id] = s
        println("Started: " + Engine.withGame(game0) { game0.civilizations.joinToString { "${it.civName}(${it.playerType})" } })

        fun seatNow() = meta.seatOfCiv(Engine.withGame(s.game!!) { g -> g.currentPlayerCiv.civID })!!
        fun act(seat: Seat, verb: String, vararg args: String, value: String? = null, feature: String? = null): TurnResult? = try {
            val r = ctl.apply(s, seat, verb, args.toList(), value)
            val flash = ctl.state(meta.id, seat.userId).flash.lastOrNull().orEmpty()
            if (feature != null && !flash.startsWith("❌")) hit(feature)
            r
        } catch (e: PlayerError) { null } catch (e: Exception) {
            errors += "$verb ${args.toList()} $value -> ${e.javaClass.simpleName}: ${e.message}"; e.printStackTrace(); null
        }
        fun <T> eng(block: (com.unciv.logic.GameInfo) -> T): T = Engine.withGame(s.game!!) { g -> block(g) }
        fun render(seat: Seat, tag: String? = null) = try {
            val r = Engine.withGame(s.game!!) { Panel.render(s, seat, ctl.state(meta.id, seat.userId)) }
            if (tag != null) File(out, "$tag.png").writeBytes(r.file!!.data.readBytes())
            require(r.rows.size <= 5 && r.rows.all { it.components.size <= 5 }) { "too many rows/components" }
            r
        } catch (e: Exception) { errors += "render $tag -> ${e.javaClass.simpleName}: ${e.message}"; e.printStackTrace(); null }

        for (turn in 1..TURNS) {
            val seat = seatNow()
            val st = ctl.state(meta.id, seat.userId)
            val civName = eng { g -> GameOps.civOf(g, seat).civName }

            // --- units: settle, step around, explore with one, escort if possible
            val settler: MapUnit? = eng { g -> GameOps.civOf(g, seat).units.getCivUnits().firstOrNull { it.baseUnit.isCityFounder() } }
            if (settler != null) {
                act(seat, "selunit", value = "u:${settler.id}")
                eng { GameOps.unitChoices(settler) }.firstOrNull { it.id.startsWith("act:FoundCity") }?.let { act(seat, "uact", value = it.id, feature = "found city") }
            }
            repeat(3) {
                act(seat, "nextunit")
                val choices = eng { _ -> st.unitId?.let { id -> GameOps.unitById(GameOps.civOf(s.game!!, seat), id) }?.let { GameOps.unitChoices(it) } }.orEmpty()
                choices.firstOrNull { it.id.startsWith("atk:") }?.let { act(seat, "uact", value = it.id, feature = "attack") }
                choices.firstOrNull { it.id.startsWith("imp:") }?.let { act(seat, "uact", value = it.id, feature = "build improvement") }
                choices.firstOrNull { it.id == "escort" }?.let { act(seat, "uact", value = it.id, feature = "escort") }
                choices.firstOrNull { it.id.startsWith("promo:") }?.let { act(seat, "uact", value = it.id, feature = "promotion") }
                if (turn % 7 == 0) choices.firstOrNull { it.id == "explore" }?.let { act(seat, "uact", value = it.id, feature = "explore") }
                act(seat, "step", listOf(12, 2, 4, 6, 8, 10).random().toString(), feature = "step")
            }
            act(seat, "tab", value = "UNITS"); render(seat)
            act(seat, "goto", value = "F4", feature = "go to")

            // --- research & policies
            eng { g -> GameOps.researchChoices(GameOps.civOf(g, seat)).firstOrNull() }?.let { act(seat, "tech", value = it.id, feature = "research") }
            if (turn == 3) eng { g -> GameOps.researchGoalChoices(GameOps.civOf(g, seat)).getOrNull(3) }?.let { act(seat, "goal", value = it.id, feature = "research goal") }
            eng { g -> GameOps.policyChoices(GameOps.civOf(g, seat)).firstOrNull() }?.let { act(seat, "pol", value = it.id, feature = "policy") }

            // --- cities: production, queue, focus, buy tile, buy unit
            eng { g -> GameOps.civOf(g, seat).cities.firstOrNull() }?.let { city ->
                st.cityId = city.id
                act(seat, "tab", value = "CITIES"); render(seat)
                eng { GameOps.productionChoices(city).firstOrNull() }?.let { act(seat, "prod", value = it.id, feature = "production") }
                if (turn % 5 == 0) {
                    act(seat, "qmode")
                    eng { GameOps.productionChoices(city).getOrNull(2) }?.let { act(seat, "prod", value = it.id, feature = "queue") }
                    act(seat, "qmode")
                    act(seat, "cact", value = "focus:ProductionFocus", feature = "city focus")
                }
                eng { Features.cityExtraChoices(city, st.window).firstOrNull { it.id.startsWith("tile:") } }?.let { if (turn % 6 == 0) act(seat, "cact", value = it.id, feature = "buy tile") }
                eng { GameOps.buyChoices(city).firstOrNull() }?.let { if (turn % 9 == 0) act(seat, "cact", value = it.id, feature = "buy with gold") }
            }

            // --- diplomacy: trade with AIs, friendship, proposals to the other human
            act(seat, "tab", value = "DIPLOMACY"); render(seat)
            val others = eng { g -> GameOps.knownMajorCivs(GameOps.civOf(g, seat)).filter { !it.isCityState } }
            for (o in others.take(2)) {
                act(seat, "selciv", value = "civ:${o.civID}")
                val (give, ask) = eng { g -> Features.tradeChoices(GameOps.civOf(g, seat), o) }
                val g1 = give.firstOrNull { it.label.contains("gold") || it.description.contains("luxury") }
                val a1 = ask.firstOrNull { it.description.contains("luxury") || it.description.contains("embassy") }
                if (g1 != null) act(seat, "give", value = g1.id)
                if (a1 != null) act(seat, "ask", value = a1.id)
                if (g1 != null || a1 != null) act(seat, "trade", feature = if (o.playerType == com.unciv.logic.civilization.PlayerType.Human) "trade proposal (human)" else "trade (AI)")
                if (turn % 10 == 0) act(seat, "friend", feature = "friendship")
            }
            // other human answers whatever was proposed to them
            while (true) {
                val p = ctl.outbox.poll() ?: break
                eng { ctl.answer(s, p, accept = true) }.also { println("  proposal ${p.kind}: $it"); hit("proposal answered") }
            }

            // --- city-states
            act(seat, "tab", value = "CITYSTATES"); render(seat)
            eng { g -> Features.cityStates(GameOps.civOf(g, seat)).firstOrNull() }?.let { cs ->
                act(seat, "selcs", value = "cs:${cs.civID}")
                eng { g -> Features.cityStateChoices(GameOps.civOf(g, seat), cs).firstOrNull { it.id.startsWith("gift") || it.id == "pledge" } }
                    ?.let { act(seat, "csact", value = it.id, feature = "city-state ${it.id.substringBefore(":")}") }
            }

            // --- religion, overview, map
            act(seat, "tab", value = "RELIGION"); render(seat)
            if (eng { g -> Features.pantheonAvailable(GameOps.civOf(g, seat)) })
                eng { g -> Features.beliefChoices(GameOps.civOf(g, seat), com.unciv.models.ruleset.BeliefType.Pantheon).firstOrNull() }
                    ?.let { act(seat, "pantheon", value = it.id, feature = "pantheon") }
            act(seat, "tab", value = "OVERVIEW"); render(seat)
            act(seat, "tab", value = "MAP"); act(seat, "pan", "2", feature = "map pan"); render(seat)
            act(seat, "inspect", value = "E4", feature = "inspect tile"); act(seat, "whole", feature = "world map"); render(seat); act(seat, "home")

            if (turn == TURNS - 1 || turn == TURNS) {
                for (tab in Tab.entries) { act(seat, "tab", value = tab.name); render(seat, "final-${seat.userName}-${tab.name.lowercase()}") }
                act(seat, "tab", value = "MAP"); act(seat, "inspect", value = "F4"); render(seat, "final-${seat.userName}-inspect")
            }
            act(seat, "tab", value = "UNITS")
            st.flash.clear()
            val r = act(seat, "end") ?: act(seat, "end", "force")
            if (r != null) hit("end turn")
            if (turn % 10 == 0) println("Turn $turn ($civName) -> next ${r?.nextCivName}, game turn ${r?.turnNumber}")
        }

        // ---- Scripted scenario: set up the situations random play rarely reaches, then use the real controls
        run {
            val alice = seatNow()
            val bob = meta.seats.first { it != alice }
            val st = ctl.state(meta.id, alice.userId)
            eng { g ->
                val a = GameOps.civOf(g, alice); val b = GameOps.civOf(g, bob)
                if (a.getDiplomacyManager(b) == null) a.diplomacyFunctions.makeCivilizationsMeet(b)
                a.addGold(3000)
                a.religionManager.storedFaith += 2000
                a.greatPeople.freeGreatPeople += 1
                val city = a.cities.first()
                a.units.addUnit("Worker", city); a.units.addUnit("Warrior", city)
                for (cs in g.civilizations.filter { it.isCityState && it.isAlive() }.take(1))
                    if (a.getDiplomacyManager(cs) == null) a.diplomacyFunctions.makeCivilizationsMeet(cs)
            }
            // worker improvement + escort (worker and warrior share the city tile)
            val worker = eng { g -> GameOps.civOf(g, alice).units.getCivUnits().last { it.baseUnit.name == "Worker" } }
            act(alice, "selunit", value = "u:${worker.id}")
            eng { GameOps.unitChoices(worker) }.firstOrNull { it.id == "escort" }?.let { act(alice, "uact", value = it.id, feature = "escort") }
            act(alice, "step", "12"); act(alice, "selunit", value = "u:${worker.id}")
            eng { GameOps.unitChoices(worker) }.firstOrNull { it.id.startsWith("imp:") }?.let { act(alice, "uact", value = it.id, feature = "build improvement") }
            // city-state gift, buy unit with faith
            act(alice, "tab", value = "CITYSTATES")
            eng { g -> Features.cityStates(GameOps.civOf(g, alice)).firstOrNull() }?.let { cs ->
                act(alice, "selcs", value = "cs:${cs.civID}"); act(alice, "csact", value = "gift:500", feature = "city-state gift")
                render(alice, "scenario-citystates")
            }
            // pantheon + free great person
            act(alice, "tab", value = "RELIGION"); render(alice, "scenario-religion-before")
            eng { g -> Features.beliefChoices(GameOps.civOf(g, alice), com.unciv.models.ruleset.BeliefType.Pantheon).firstOrNull() }
                ?.let { act(alice, "pantheon", value = it.id, feature = "pantheon") }
            act(alice, "tab", value = "OVERVIEW"); render(alice, "scenario-overview")
            eng { g -> Features.greatPersonChoices(GameOps.civOf(g, alice)).firstOrNull() }?.let { act(alice, "gp", value = it.id, feature = "free great person") }
            // human <-> human: deal, friendship, war and peace, answered by the other player
            act(alice, "tab", value = "DIPLOMACY")
            act(alice, "selciv", value = "civ:${bob.civId}")
            val (give, _) = eng { g -> Features.tradeChoices(GameOps.civOf(g, alice), GameOps.civOf(g, bob)) }
            give.firstOrNull { it.label.contains("gold") }?.let { act(alice, "give", value = it.id) }
            act(alice, "trade", feature = "trade proposal (human)")
            act(alice, "war", feature = "declare war"); act(alice, "peace", feature = "peace proposal (human)")
            render(alice, "scenario-diplomacy")
            while (true) {
                val p = ctl.outbox.poll() ?: break
                eng { ctl.answer(s, p, accept = true) }.also { println("  proposal ${p.kind}: $it"); hit("proposal answered (${p.kind})") }
            }
            act(alice, "denounce", feature = "denounce")
            st.flash.forEach { println("  > $it") }; st.flash.clear()

            // ---- Paging: long dropdowns must page instead of cutting options off
            run {
                val pst = ctl.state(meta.id, alice.userId)
                fun check(ok: Boolean, what: String) { if (ok) hit(what) else errors += "paging: $what failed" }
                // until healed
                val hurt = eng { g -> val a = GameOps.civOf(g, alice)
                    val u = a.units.addUnit("Spearman", a.cities.first())!!
                    // put it on free land in our territory (embarked units can't heal)
                    a.cities.first().getTiles().firstOrNull { it.isLand && !it.isImpassible() && it.militaryUnit == null }?.let { t -> u.removeFromTile(); u.putInTile(t) }
                    u.health = 40; u.currentMovement = u.getMaxMovement().toFloat(); u }
                act(alice, "tab", value = "UNITS"); act(alice, "selunit", value = "u:${hurt.id}")
                val healChoice = eng { GameOps.unitChoices(hurt) }.firstOrNull { it.id == "fortifyheal" || it.id == "sleepheal" }
                if (healChoice != null) act(alice, "uact", value = healChoice.id, feature = "fortify/sleep until healed")
                else errors += "until healed: no option for a wounded unit " + eng { _ ->
                    "hp=${hurt.health} moves=${hurt.currentMovement} heal=${hurt.canHealInCurrentTile()} canFortify=${hurt.canFortify()} " +
                    "fortified=${hurt.isFortified()} action=${hurt.action} tile=${hurt.getTile().baseTerrain}/${hurt.getTile().isLand} " +
                    "alive=${hurt.civ.units.getCivUnits().contains(hurt)} choices=${GameOps.unitChoices(hurt).map { it.id }}" }
                // a big army: >25 units in the unit picker
                val army = eng { g -> val a = GameOps.civOf(g, alice); val c = a.cities.first(); (1..40).mapNotNull { a.units.addUnit("Warrior", c) } }
                act(alice, "tab", value = "UNITS"); render(alice)
                val unitCount = eng { g -> GameOps.civOf(g, alice).units.getCivUnitsSize() }
                check(unitCount > 25 && (pst.visible["selunit"]?.size ?: 99) in 1..23, "unit picker pages ($unitCount units)")
                act(alice, "selunit", value = "pg:selunit:1"); render(alice)
                val page2 = pst.visible["selunit"].orEmpty()
                check(page2.isNotEmpty() && pst.pages["selunit"] == 1, "unit picker next page")
                val farUnit = page2.last().removePrefix("u:").toInt()
                act(alice, "selunit", value = "u:$farUnit"); render(alice, "paging-units")
                check(pst.unitId == farUnit, "select a unit from page 2")
                // a late-game city: >25 things to build
                eng { g -> val a = GameOps.civOf(g, alice)
                    for (t in g.ruleset.technologies.values.filter { it.era() in listOf("Ancient era", "Classical era", "Medieval era", "Renaissance era") }) a.tech.addTechnology(t.name, false) }
                val city = eng { g -> GameOps.civOf(g, alice).cities.first() }
                pst.cityId = city.id
                val prodCount = eng { GameOps.productionChoices(city).size }
                act(alice, "tab", value = "CITIES"); render(alice)
                check(prodCount > 25 && pst.visible["prod"]?.size == 23, "production list pages ($prodCount options)")
                act(alice, "prod", value = "pg:prod:1"); render(alice)
                val p2 = pst.visible["prod"].orEmpty().firstOrNull()
                if (p2 != null) act(alice, "prod", value = p2, feature = "build something from production page 2")
                // multi-select picks survive page flips (trade lists)
                pst.visible["give"] = listOf("gA", "gB"); pst.give = listOf("gX", "gA")
                act(alice, "give", value = "gB")
                check(pst.give.toSet() == setOf("gX", "gB"), "trade picks kept across pages")
                pst.give = emptyList(); pst.visible.remove("give")
                // connect road (worker + The Wheel)
                val worker = eng { g -> val a = GameOps.civOf(g, alice); a.tech.addTechnology("The Wheel", false); a.units.addUnit("Worker", a.cities.first())!! }
                act(alice, "selunit", value = "u:${worker.id}"); render(alice)
                check(eng { GameOps.unitChoices(worker) }.any { it.id == "ask:roadto" }, "connect road offered")
                val dest = eng { g ->
                    val w = pst.window!!
                    worker.getTile().getTilesAtDistance(3).firstOrNull { it.isLand && !it.isImpassible() && it.isExplored(worker.civ) && w.code(it.position.x, it.position.y) != null }
                        ?.let { w.code(it.position.x, it.position.y) }
                }
                if (dest != null) {
                    act(alice, "roadto", value = dest, feature = "connect road to a tile")
                    // Only when the road was accepted (a target can be unreachable on some maps — then the bot refuses, correctly)
                    if (pst.flash.lastOrNull()?.startsWith("🛣️") == true)
                        check(eng { worker.isAutomatingRoadConnection() || worker.getTile().roadStatus != com.unciv.logic.map.tile.RoadStatus.None }, "worker is building the road")
                }
                // clean up so later tests see a normal-sized army
                eng { _ -> army.forEach { u -> if (u.civ.units.getCivUnits().contains(u)) u.destroy() } }
                pst.pages.clear(); pst.flash.clear()
            }

            // ---- Extension self-tests (each feature file tests itself)
            val tctx = SelfTestCtx(s, ctl, alice, bob,
                act = { seat, verb, args, value, feature ->
                    try {
                        ctl.apply(s, seat, verb, args, value)
                        val fl = ctl.state(meta.id, seat.userId).flash.lastOrNull().orEmpty()
                        if (fl.startsWith("❌")) false else { if (feature != null) hit(feature); true }
                    } catch (e: PlayerError) { false } catch (e: Exception) {
                        errors += "$verb $args $value -> ${e.javaClass.simpleName}: ${e.message}"; e.printStackTrace(); false
                    }
                },
                render = { seat, tag -> render(seat, tag) })
            for (ext in Extensions.all) {
                try { ext.selfTest(tctx) } catch (e: Exception) {
                    errors += "${ext.javaClass.simpleName}.selfTest -> ${e.javaClass.simpleName}: ${e.message}"; e.printStackTrace()
                }
                ctl.state(meta.id, alice.userId).flash.clear()
            }
            // every extension tab must render
            for ((_, t) in Extensions.all.flatMap { e -> e.tabs.map { e to it } }) {
                act(alice, "tab", value = t.id); render(alice, "ext-${t.id.lowercase()}")
            }
        }

        // ---- Standing orders: a long Go to keeps going on later turns
        run {
            val seat = seatNow()
            val (unitId, from, target) = eng { g ->
                val c = GameOps.civOf(g, seat)
                val u = c.units.getCivUnits().firstOrNull { it.isMilitary() && !it.baseUnit.isAirUnit() && !it.baseUnit.isWaterUnit }
                    ?: c.units.addUnit("Warrior", c.cities.first())!!
                u.action = null; u.automated = false; u.currentMovement = u.getMaxMovement().toFloat()
                // a reachable land tile 4-5 away, preferring our own or unclaimed land (fewer enemies on the way)
                val dest = (4..5).asSequence().flatMap { d -> u.getTile().getTilesAtDistance(d) }
                    .filter { it.isLand && !it.isImpassible() && it.militaryUnit == null && u.movement.canReach(it) && !u.movement.canReachInCurrentTurn(it) }
                    .sortedBy { if (it.getOwner() == c || it.getOwner() == null) 0 else 1 }.firstOrNull()
                Triple(u.id, u.getTile().position, dest)
            }
            if (target == null) println("standing orders: no reachable far tile on this map — skipped")
            else {
                eng { g -> GameOps.moveUnit(GameOps.unitById(GameOps.civOf(g, seat), unitId)!!, target) }
                val afterOrder = eng { g -> GameOps.unitById(GameOps.civOf(g, seat), unitId)?.getTile()?.position }
                // let both players end turns until it's this seat's turn again
                repeat(2) { val sNow = seatNow(); ctl.apply(s, sNow, "end", listOf("force"), null) }
                val later = eng { g -> GameOps.unitById(GameOps.civOf(g, seat), unitId)?.getTile()?.position }
                if (later == null) println("standing orders: the unit was lost on the way (combat) — skipped")
                else if (afterOrder != null && later != afterOrder) { hit("standing orders: multi-turn Go to keeps moving"); println("standing orders: $from -> $afterOrder -> $later (target ${target.position})") }
                else errors += "standing orders: unit didn't continue its Go to ($from -> $afterOrder -> $later, target ${target.position})"
            }
        }

        File(out, "world.png").writeBytes(eng { g -> MapRenderer.renderWhole(g.tileMap.values, null, 1) })
        val data = eng { g -> Engine.serialize(g) }
        val loaded = Engine.run { Engine.deserialize(data) }
        println("Save/load OK: turn ${loaded.turns}, ${loaded.civilizations.size} civs")
        // metadata round trip (settings incl. extension options must survive a restart)
        meta.settings.extra["selftest"] = "yes"; meta.settings.mods.add("NoSuchMod"); store.saveGame(s)
        val reloaded = Store(File(out, "data")).apply { loadAll() }.sessions[meta.id]
        if (reloaded?.meta?.settings?.extra?.get("selftest") != "yes" || reloaded.meta.settings.mods != listOf("NoSuchMod"))
            errors += "meta round trip lost settings"
        else println("Meta round trip OK")
        println("\nFeature checklist (times exercised):")
        hits.forEach { (k, v) -> println("  ✓ $k ×$v") }
        println("\nErrors: ${errors.size}")
        errors.distinct().take(20).forEach { println("  ✗ $it") }
        println("Images written to ${out.absolutePath}")
    }
}

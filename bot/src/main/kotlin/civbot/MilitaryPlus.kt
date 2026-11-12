package civbot

import com.unciv.logic.battle.AirInterception
import com.unciv.logic.battle.CityCombatant
import com.unciv.logic.battle.MapUnitCombatant
import com.unciv.logic.battle.Nuke
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.PlayerType
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.logic.map.tile.Tile
import com.unciv.models.UnitActionType
import com.unciv.models.ruleset.unique.UniqueType
import net.dv8tion.jda.api.interactions.components.ActionRow
import java.util.concurrent.ConcurrentHashMap

/**
 * Military extras: nukes (with a confirm step), air sweep, air rebasing, typed-in attack targets
 * and a ⚔️ Military tab with an army overview. Mirrors the logic of Unciv's WorldMapHolder /
 * BattleTable (Nuke.mayUseNuke + Nuke.NUKE, AirInterception.airSweep, air movement rules).
 *
 * Interception is passive in Unciv: any unit with an intercept chance that has NOT moved or attacked
 * this turn intercepts automatically (MapUnit.canIntercept). There's no "intercept" order, so the
 * tab just shows which interceptors are on alert.
 */
object MilitaryPlus : Extension {
    override val viewVerbs = setOf("mp_next")

    private const val TAB = "MILITARY"

    /** A nuke target waiting for confirmation, per game + unit. */
    private class PendingNuke(val x: Int, val y: Int, val code: String, val turn: Int)
    private val pending = ConcurrentHashMap<String, PendingNuke>()
    private fun key(u: MapUnit) = "${u.civ.gameInfo.gameId}:${u.civ.civID}:${u.id}"
    private fun pendingFor(u: MapUnit): PendingNuke? =
        pending[key(u)]?.takeIf { it.turn == u.civ.gameInfo.turns }

    override val tabs = listOf(ExtTab(TAB, "Military", "⚔️", "Army overview, nukes, air units, targets in range",
        visible = { civ -> civ.units.getCivUnits().any { it.isMilitary() } }))

    override val prompts = mapOf(
        "mp_nuke" to Prompt("☢️ Nuke which tile?", "Tile code or city name", "e.g. D5 — you'll confirm next"),
        "mp_sweep" to Prompt("✈️ Air sweep which tile?", "Tile code or city name", "e.g. D5"),
        "mp_attack" to Prompt("🎯 Attack which tile?", "Tile code or city name", "e.g. D5"),
    )

    // ================================================================== helpers

    private fun nukesEnabled(u: MapUnit) = u.civ.gameInfo.gameParameters.nuclearWeaponsEnabled
    private fun canSweep(u: MapUnit) = u.hasUnique(UniqueType.CanAirsweep)
    private fun canStrike(u: MapUnit) = u.isMilitary() && !u.isNuclearWeapon() && u.getRange() > 1 &&
        !u.hasUnique(UniqueType.CannotAttack) && (u.baseUnit.isAirUnit() || u.baseUnit.isRanged())

    /** The code a tile has on the map centred on [center] (what the Units and Military tabs show). */
    private fun codeOf(center: Tile, t: Tile, civ: Civilization): String =
        MapWindow(center.position).code(t.position.x, t.position.y)
            ?: civ.cities.minByOrNull { it.getCenterTile().aerialDistanceTo(t) }?.let { "near ${it.name}" } ?: "far away"

    /** Cities and carriers the air unit can fly to this turn (Unciv: getAirUnitMovementRange + canMoveTo). */
    fun rebaseTargets(u: MapUnit): List<Tile> {
        if (!u.baseUnit.isAirUnit() || !u.hasMovement()) return emptyList()
        val here = u.getTile()
        return here.getTilesInDistance(u.getAirUnitMovementRange())
            .filter { it != here && (it.isCityCenter() || it.militaryUnit?.canTransport(u) == true) }
            .filter { u.movement.canMoveTo(it) }
            .sortedBy { it.aerialDistanceTo(here) }.toList()
    }

    private fun targetName(t: Tile, viewer: Civilization): String {
        val city = t.getCity()?.takeIf { t.isCityCenter() }
        val unit = (t.militaryUnit ?: t.civilianUnit ?: t.airUnits.firstOrNull())?.takeIf { t.isVisible(viewer) }
        return when {
            city != null -> "city ${city.name} (${city.civ.civName})"
            unit != null -> "${unit.civ.civName} ${unit.label()}"
            else -> "empty tile"
        }
    }

    /** What a nuke on [target] would hit, as warning lines. */
    private fun blastReport(u: MapUnit, target: Tile): List<String> {
        val civ = u.civ
        val radius = u.getNukeBlastRadius()
        val tiles = target.getTilesInDistance(radius).toList()
        val lines = ArrayList<String>()
        val cities = tiles.filter { it.isCityCenter() && it.isExplored(civ) }.mapNotNull { it.getCity() }
        for (c in cities)
            lines += if (c.civ == civ) "🏙️ **YOUR city ${c.name}** (pop ${c.population.population})"
            else "🏙️ ${c.name} — ${c.civ.civName} (pop ${c.population.population})"
        val units = tiles.flatMap { t -> t.getUnits().filter { it.civ == civ || t.isVisible(civ) }.toList() }.filter { it != u }
        val mine = units.filter { it.civ == civ }
        val theirs = units.filter { it.civ != civ }
        if (mine.isNotEmpty()) lines += "🪖 **YOUR units:** " + mine.groupingBy { it.label() }.eachCount().entries.joinToString { "${it.key}×${it.value}" }
        if (theirs.isNotEmpty()) lines += "🎯 Enemy/foreign units: " + theirs.groupBy { it.civ.civName }.entries
            .joinToString("; ") { (c, l) -> "$c: " + l.groupingBy { it.label() }.eachCount().entries.joinToString { "${it.key}×${it.value}" } }
        val ownTiles = tiles.count { it.getOwner() == civ }
        if (ownTiles > 0) lines += "🌾 $ownTiles of YOUR tiles (improvements and population will suffer)"
        val owners = (tiles.mapNotNull { it.getOwner() } + theirs.map { it.civ }).distinct().filter { it != civ }
        val newWars = owners.filter { it.knows(civ) && !civ.isAtWarWith(it) }
        if (newWars.isNotEmpty()) lines += "🔥 This declares war on: " + newWars.joinToString { it.civName }
        if (lines.isEmpty()) lines += "Nothing visible in the blast zone."
        lines += "😠 Every civ you know will hate you for using nuclear weapons."
        return lines
    }

    /** Why [u] may not nuke [target] (mirrors Nuke.mayUseNuke), or null if it may. */
    private fun nukeProblem(u: MapUnit, target: Tile): String? {
        if (!nukesEnabled(u)) return "Nuclear weapons are disabled in this game."
        if (!u.isNuclearWeapon()) return "${u.label()} isn't a nuclear weapon."
        if (!u.canAttack()) return "${u.label()} can't attack any more this turn."
        val here = u.getTile()
        if (here == target) return "You can't nuke the tile your nuke is on."
        if (!target.isExplored(u.civ)) return "That tile is unexplored."
        if (here.aerialDistanceTo(target) > u.getRange()) return "That's ${here.aerialDistanceTo(target)} tiles away — range is ${u.getRange()}."
        if (!Nuke.mayUseNuke(MapUnitCombatant(u), target)) {
            val blocked = target.getTilesInDistance(u.getNukeBlastRadius())
                .flatMap { t -> sequenceOf(t.getOwner()) + t.getUnits().map { it.civ } }.filterNotNull().distinct()
                .filter { it != u.civ && !it.isBarbarian && !it.isDefeated() && u.civ.getDiplomacyManager(it)?.canAttack() != true }
                .joinToString { it.civName }
            return "The blast would hit ${blocked.ifEmpty { "someone" }}, and you can't attack them right now (unmet, or a peace treaty)."
        }
        return null
    }

    // ================================================================== unit menu

    fun militaryChoices(u: MapUnit): List<Choice> {
        val out = ArrayList<Choice>()
        if (!u.isMilitary()) return out
        if (u.isNuclearWeapon() && nukesEnabled(u) && u.canAttack()) {
            val p = pendingFor(u)
            if (p != null) {
                out += Choice("do:mp_nuke_confirm", "CONFIRM nuke ${p.code}", "Launch now — this can't be undone", "☢️")
                out += Choice("do:mp_nuke_cancel", "Cancel the nuke on ${p.code}", "Stand down", "✖️")
            }
            out += Choice("ask:mp_nuke", if (p != null) "Nuke a different tile…" else "Nuke a tile…",
                "Range ${u.getRange()} · blast radius ${u.getNukeBlastRadius()} · you confirm first", "☢️")
        }
        if (canSweep(u) && u.canAttack())
            out += Choice("ask:mp_sweep", "Air sweep…", "Draw out enemy interceptors before your bombers strike", "✈️")
        if (canStrike(u) && u.canAttack()) {
            val n = GameOps.attackableTargets(u).size
            out += Choice("ask:mp_attack", "Attack target…", "Type a tile code · ${if (n == 0) "no" else n} target(s) in range now", "🎯")
        }
        for (t in rebaseTargets(u).take(8)) {
            val name = if (t.isCityCenter()) t.getCity()!!.name else "${t.militaryUnit!!.label()} (carrier)"
            out += Choice("mp:rebase:${t.position.x},${t.position.y}", "Rebase to $name",
                "${codeOf(u.getTile(), t, u.civ)} · ${t.aerialDistanceTo(u.getTile())} tiles", "🛬")
        }
        return out
    }

    override fun unitChoices(u: MapUnit): List<Choice> = militaryChoices(u)

    override fun doUnitChoice(u: MapUnit, id: String): String? {
        if (!id.startsWith("mp:")) return null
        val parts = id.split(":")
        when (parts.getOrNull(1)) {
            "rebase" -> {
                val (x, y) = parts[2].split(",").map { it.toInt() }
                val t = u.civ.gameInfo.tileMap.getIfTileExistsOrNull(x, y) ?: throw PlayerError("That tile doesn't exist.")
                if (t !in rebaseTargets(u)) throw PlayerError("${u.label()} can't fly there now (out of range, full, or no moves left).")
                val name = if (t.isCityCenter()) t.getCity()!!.name else "the carrier"
                u.movement.moveToTile(t)
                return "🛬 ${u.label()} rebased to **$name**."
            }
        }
        return null
    }

    // ================================================================== verbs

    private fun tileArg(ctx: ActionCtx): Tile =
        ctx.tile(ctx.value?.trim().orEmpty().ifEmpty { throw PlayerError("Type a tile code, like D5.") })

    override fun handle(verb: String, ctx: ActionCtx): Boolean {
        when (verb) {
            "mp_nuke" -> {
                ctx.requireTurn()
                val u = ctx.selectedUnit()
                if (!nukesEnabled(u)) throw PlayerError("☢️ Nuclear weapons are disabled in this game.")
                val target = tileArg(ctx)
                nukeProblem(u, target)?.let { throw PlayerError("☢️ $it") }
                val code = codeOf(u.getTile(), target, ctx.civ)
                    .let { if (it.startsWith("near") || it == "far away") ctx.value!!.trim().uppercase().take(20) else it }
                pending[key(u)] = PendingNuke(target.position.x, target.position.y, code, ctx.game.turns)
                ctx.flash += "☢️ **Target locked: $code** (${targetName(target, ctx.civ)}). Blast radius ${u.getNukeBlastRadius()} will hit:\n" +
                    blastReport(u, target).joinToString("\n") { "• $it" } +
                    "\n⚠️ Pick **☢️ CONFIRM nuke $code** in the unit actions to launch."
                return true
            }
            "mp_nuke_cancel" -> {
                val u = ctx.selectedUnit()
                pending.remove(key(u))
                ctx.flash += "✖️ ${u.label()} stands down."
                return true
            }
            "mp_nuke_confirm" -> {
                ctx.requireTurn()
                val u = ctx.selectedUnit()
                val p = pendingFor(u) ?: throw PlayerError("Choose **☢️ Nuke a tile…** first.")
                val target = ctx.game.tileMap[p.x, p.y]
                nukeProblem(u, target)?.let { pending.remove(key(u)); throw PlayerError("☢️ $it") }
                pending.remove(key(u))
                val civ = ctx.civ
                val area = target.getTilesInDistance(u.getNukeBlastRadius()).toList()
                fun unitsHere() = area.flatMap { it.getUnits().toList() }.filter { it != u }.toSet()
                val unitsBefore = unitsHere()
                val hpBefore = unitsBefore.associateWith { it.health }
                val popBefore = area.mapNotNull { t -> t.getCity()?.takeIf { t.isCityCenter() } }.associateWith { it.population.population }
                val warsBefore = civ.getKnownCivs().filter { civ.isAtWarWith(it) }.toSet()
                val notesBefore = civ.notifications.size
                val nukeName = u.label()
                Nuke.NUKE(MapUnitCombatant(u), target)
                val lost = unitsBefore - unitsHere()
                val lostMine = lost.count { it.civ == civ }
                val hurt = (unitsBefore - lost).filter { it.health < hpBefore[it]!! }
                val dmg = hurt.sumOf { hpBefore[it]!! - it.health }
                val cityLines = popBefore.map { (c, before) ->
                    if (ctx.game.getCities().none { it == c }) "${c.name} was wiped out"
                    else "${c.name} pop $before→${c.population.population}"
                }
                val newWars = civ.getKnownCivs().filter { civ.isAtWarWith(it) && it !in warsBefore }.toList()
                val intercepted = civ.notifications.drop(notesBefore).any { it.text.contains("attempted attack") }
                val msg = if (intercepted) "☢️ ${civ.civName}'s $nukeName was **shot down** before it reached ${p.code}."
                else "☢️💥 ${civ.civName} **nuked ${p.code}**! ${lost.size} unit(s) destroyed" +
                    (if (lostMine > 0) " ($lostMine of them yours)" else "") +
                    (if (hurt.isNotEmpty()) ", ${hurt.size} damaged (−$dmg HP)" else "") +
                    (if (cityLines.isNotEmpty()) " · " + cityLines.joinToString() else "") + "."
                ctx.flash += msg + (if (newWars.isNotEmpty()) "\n🔥 Now at war with: ${newWars.joinToString { it.civName }}" else "")
                ctx.s.meta.addEvent(msg)
                if (!civAlive(civ, u)) ctx.st.unitId = GameOps.nextIdleUnit(civ, null)?.id
                ctx.changed = true
                return true
            }
            "mp_sweep" -> {
                ctx.requireTurn()
                val u = ctx.selectedUnit()
                if (!canSweep(u)) throw PlayerError("${u.label()} can't perform an air sweep.")
                if (!u.canAttack()) throw PlayerError("${u.label()} can't attack any more this turn.")
                val target = tileArg(ctx)
                if (target == u.getTile()) throw PlayerError("Pick a tile away from the plane.")
                val d = u.getTile().aerialDistanceTo(target)
                if (d > u.getRange()) throw PlayerError("That's $d tiles away — ${u.label()}'s range is ${u.getRange()}.")
                val code = codeOf(u.getTile(), target, ctx.civ)
                val hp = u.health
                val notesBefore = ctx.civ.notifications.size
                u.action = UnitActionType.AirSweep.value // BattleDamage adds the air-sweep bonuses while this is set
                AirInterception.airSweep(MapUnitCombatant(u), target)
                if (!u.isDestroyed) u.action = null
                val note = ctx.civ.notifications.drop(notesBefore).lastOrNull()?.let { GameOps.clean(it.text) }
                ctx.flash += "✈️ ${u.label()} swept $code: " + (note ?: "done") +
                    (if (u.isDestroyed || u.health <= 0) " — **our plane was lost.**" else if (u.health < hp) " (❤️ $hp→${u.health})" else "")
                ctx.changed = true
                return true
            }
            "mp_attack" -> {
                ctx.requireTurn()
                val u = ctx.selectedUnit()
                if (u.isNuclearWeapon()) throw PlayerError("Use **☢️ Nuke a tile…** for nuclear weapons.")
                if (!canStrike(u)) throw PlayerError("${u.label()} isn't a ranged or air unit — move it next to the enemy instead.")
                if (!u.canAttack()) throw PlayerError("${u.label()} can't attack any more this turn.")
                val target = tileArg(ctx)
                val at = GameOps.attackableTargets(u).firstOrNull { it.tileToAttack == target }
                    ?: throw PlayerError(when {
                        !target.isVisible(ctx.civ) -> "You can't see that tile right now."
                        u.getTile().aerialDistanceTo(target) > u.getRange() -> "Out of range (${u.getTile().aerialDistanceTo(target)} tiles, range ${u.getRange()})."
                        else -> "There's no enemy you can attack there (${targetName(target, ctx.civ)})."
                    })
                ctx.flash += GameOps.attack(u, at)
                if (!civAlive(ctx.civ, u) || !u.hasMovement()) GameOps.nextIdleUnit(ctx.civ, u)?.let { ctx.st.unitId = it.id }
                ctx.changed = true
                return true
            }
            "mp_next" -> {
                val mil = militaryUnits(ctx.civ)
                if (mil.isEmpty()) throw PlayerError("You have no military units.")
                val ready = mil.filter { it.due && it.hasMovement() && it.isIdle() }.ifEmpty { mil }
                val i = ready.indexOfFirst { it.id == ctx.st.unitId }
                ctx.st.unitId = ready[(i + 1) % ready.size].id
                return true
            }
        }
        return false
    }

    private fun civAlive(civ: Civilization, u: MapUnit) = civ.units.getCivUnits().any { it == u }

    private fun militaryUnits(civ: Civilization) =
        civ.units.getCivUnits().filter { it.isMilitary() }
            .sortedWith(compareBy({ !it.baseUnit.isAirUnit() }, { it.baseUnit.name })).toList()

    // ================================================================== tab

    override fun renderTab(tabId: String, t: TabCtx) {
        val civ = t.civ
        val mil = militaryUnits(civ)
        val unit = mil.firstOrNull { it.id == t.st.unitId } ?: mil.firstOrNull()
        if (unit != null) t.st.unitId = unit.id
        val center = unit?.getTile() ?: GameOps.focusTile(civ)
        fun code(tile: Tile) = codeOf(center, tile, civ)
        val myTurn = t.myTurn

        // Army overview grouped by type
        val army = mil.groupBy { it.baseUnit.name }.entries.joinToString("\n") { (name, us) ->
            "${GameOps.unitEmoji(us.first())} **$name** ×${us.size} — " + us.take(6).joinToString(", ") { u ->
                "${code(u.getTile())} ❤️${u.health}" + (if (u.isFortified()) " 🛡️" else if (u.isSleeping()) " 💤" else if (!u.hasMovement()) " ✔" else "")
            } + (if (us.size > 6) ", …" else "")
        }
        t.eb.addField("⚔️ Army (${mil.size} units)", army.ifEmpty { "No military units. Build some in 🏙️ Cities." }.take(1024), false)

        // Wars
        val enemies = civ.getKnownCivs().filter { civ.isAtWarWith(it) && it.isAlive() && !it.isBarbarian }.toList()
        val seen = civ.viewableTiles.flatMap { it.getUnits().toList() }.filter { it.civ != civ && civ.isAtWarWith(it.civ) }
        val warText = if (enemies.isEmpty() && seen.isEmpty()) "At peace with everyone." else
            (enemies.take(10).joinToString("\n") { e ->
                val who = if (e.playerType == PlayerType.Human) "human" else if (e.isCityState) "city-state" else "AI"
                "🔥 **${e.civName}** ($who) — ${seen.count { it.civ == e }} unit(s) in sight"
            } + seen.count { it.civ.isBarbarian }.let { if (it > 0) "\n🏴 Barbarians — $it unit(s) in sight" else "" }).trim()
        t.eb.addField("🔥 At war", warText.take(1024), false)

        // Targets in range for units that can still act
        val targets = if (!myTurn) emptyList() else mil.filter { !it.isNuclearWeapon() && it.canAttack() }.mapNotNull { u ->
            val ts = GameOps.attackableTargets(u)
            if (ts.isEmpty()) null else "${GameOps.unitEmoji(u)} ${u.label()} (${code(u.getTile())}) → " +
                ts.take(3).joinToString(", ") { a ->
                    val hp = when (val c = a.combatant) { is MapUnitCombatant -> " ${c.getHealth()}HP"; is CityCombatant -> " ${c.getHealth()}HP"; else -> "" }
                    "${targetName(a.tileToAttack, civ)}$hp ${code(a.tileToAttack)}"
                } + (if (ts.size > 3) " +${ts.size - 3}" else "")
        }
        if (targets.isNotEmpty()) t.eb.addField("🎯 Enemies in range", targets.take(8).joinToString("\n").take(1024), false)

        // Air force: interception status, nukes
        val air = ArrayList<String>()
        for (u in mil.filter { it.interceptChance() > 0 }.take(6))
            air += "${GameOps.unitEmoji(u)} ${u.label()} ${code(u.getTile())} — " +
                if (u.canIntercept()) "🛡️ on alert (${u.interceptChance()}% within ${u.getInterceptionRange()} tiles)"
                else "⏸️ used this turn, can't intercept until next turn"
        val nukes = mil.filter { it.isNuclearWeapon() }
        if (nukes.isNotEmpty()) air += if (!t.game.gameParameters.nuclearWeaponsEnabled) "☢️ Nuclear weapons are disabled in this game."
            else "☢️ ${nukes.size} nuclear weapon(s): " + nukes.take(6).joinToString { "${it.label()} ${code(it.getTile())}" +
                (pendingFor(it)?.let { p -> " → **${p.code}** (awaiting confirm)" } ?: "") }
        if (air.isNotEmpty()) t.eb.addField("✈️ Air force",
            (air.joinToString("\n") + "\n_Interception is automatic: fighters and AA that don't move or attack this turn intercept enemy air strikes._").take(1024), false)

        if (unit != null)
            t.eb.addField("Selected", "${GameOps.unitEmoji(unit)} **${unit.label()}** at ${code(unit.getTile())} · ❤️ ${unit.health} · " +
                "👣 ${GameOps.movesText(unit)} · ${GameOps.unitStatus(unit)}" +
                (if (unit.getRange() > 1) " · range ${unit.getRange()}" else ""), false)

        // Map: selected unit, with its targets (or the pending blast zone) in red, rebase spots light
        if (unit != null) {
            val p = pendingFor(unit)
            val red = when {
                p != null -> t.game.tileMap[p.x, p.y].getTilesInDistance(unit.getNukeBlastRadius()).toSet()
                myTurn && !unit.isNuclearWeapon() -> GameOps.attackableTargets(unit).map { it.tileToAttack }.toSet()
                else -> emptySet()
            }
            t.mapAround(center, Overlay(selectedUnit = unit, reachable = if (myTurn) rebaseTargets(unit).toSet() else emptySet(), attackable = red))
        } else t.mapAround(center)

        // Rows: pick a military unit, then its orders
        val unitChoices = mil.take(25).map { u ->
            Choice("u:${u.id}", "${u.label()} — ${GameOps.unitStatus(u)}", "${code(u.getTile())} · ❤️${u.health} · ${GameOps.movesText(u)} moves", GameOps.unitEmoji(u))
        }
        t.rows += ActionRow.of(t.select("selunit", "Military unit (${mil.size})", unitChoices, unit?.let { "u:${it.id}" }))
        val orders = ArrayList<Choice>()
        if (unit != null && myTurn) {
            orders += militaryChoices(unit)
            if (!unit.isNuclearWeapon()) for (a in GameOps.attackableTargets(unit).take(8))
                orders += Choice("atk:${a.tileToAttack.position.x},${a.tileToAttack.position.y}",
                    "Attack ${targetName(a.tileToAttack, civ)}", code(a.tileToAttack), "⚔️")
            if (unit.canFortify() && !unit.isFortified() && unit.hasMovement()) orders += Choice("fortify", "Fortify", "+defence, stays put", "🛡️")
            if (!unit.canFortify() && !unit.isSleeping() && unit.hasMovement()) orders += Choice("sleep", "Sleep", "Stays put (still intercepts if it can)", "💤")
            if (unit.isFortified() || unit.isSleeping()) orders += Choice("wake", "Wake up", GameOps.unitStatus(unit), "⏰")
        }
        t.rows += ActionRow.of(t.select("uact", if (orders.isEmpty()) "No orders right now" else "Orders for ${unit?.label()}…",
            orders.take(25), enabled = myTurn && orders.isNotEmpty()))
        t.endRow += t.button("mp_next", "Next", "⏭️", enabled = mil.size > 1)
        t.endRow += t.askButton("mp_attack", "Attack…", "🎯", 1, myTurn && unit != null && canStrike(unit) && unit.canAttack())
    }

    // ================================================================== self-test

    override fun selfTest(t: SelfTestCtx) {
        val a = t.alice; val b = t.bob
        val st = t.ctl.state(t.s.meta.id, a.userId)
        fun tick(label: String) = t.act(a, "refresh", emptyList(), null, label)
        fun say(msg: String) = println("  [MilitaryPlus] $msg")
        fun lastFlash() = st.flash.lastOrNull().orEmpty().also { if (it.isNotEmpty()) say("> " + it.replace("\n", " | ").take(400)) }

        class Setup(val capital: Tile, val bomb: MapUnit, val f1: MapUnit, val f2: MapUnit, val f3: MapUnit,
                    val nukeTarget: Tile?, val sweepTarget: Tile?, val extraCities: List<String>)

        val setup = t.eng { g ->
            val civ = GameOps.civOf(g, a)
            val barb = g.getBarbarianCivilization()
            val cap = civ.getCapital()!!
            val c = cap.getCenterTile()
            civ.tech.addTechnology("Nuclear Fission"); civ.tech.addTechnology("Radar")
            val bomb = civ.units.addUnit("Atomic Bomb", cap)!!
            val f1 = civ.units.addUnit("Fighter", cap)!!
            val f2 = civ.units.addUnit("Fighter", cap)!!
            val f3 = civ.units.addUnit("Fighter", cap)!!
            civ.cache.updateViewableTiles()
            fun free(tile: Tile) = tile.isLand && !tile.isImpassible() && tile.getUnits().none() && !tile.isCityCenter()
            // extra cities: rebase destination + two to trade away
            val extra = ArrayList<String>()
            for (tile in c.getTilesInDistanceRange(3..15).sortedBy { it.aerialDistanceTo(c) }.toList()) {
                if (extra.size >= 3) break
                if (!free(tile) || (tile.getOwner() != null && tile.getOwner() != civ)) continue
                if (g.getCities().any { it.getCenterTile().aerialDistanceTo(tile) < 3 }) continue
                extra += civ.addCity(tile.position).id
            }
            civ.cache.updateOurTiles(); civ.cache.updateViewableTiles()
            // sweep target with a barbarian AA gun on it, nuke target with a barbarian warrior on it
            val sweep = c.getTilesInDistanceRange(2..4).firstOrNull { free(it) && it.isVisible(civ) }
                ?.let { tile -> barb.units.placeUnitNearTile(tile.position, "Anti-Aircraft Gun")?.getTile() }
            civ.cache.updateViewableTiles()
            fun foreign(tile: Tile) = tile.getTilesInDistance(2).count { it.getOwner() != null && it.getOwner() != civ }
            val nuke = c.getTilesInDistanceRange(3..5).filter { free(it) && it.isVisible(civ) && (sweep == null || it.aerialDistanceTo(sweep) > 3) }
                .sortedBy { foreign(it) }
                .firstNotNullOfOrNull { tile ->
                    val w = barb.units.placeUnitNearTile(tile.position, "Warrior") ?: return@firstNotNullOfOrNull null
                    if (Nuke.mayUseNuke(MapUnitCombatant(bomb), w.getTile())) w.getTile() else { w.destroy(); null }
                }
            civ.cache.updateViewableTiles()
            Setup(c, bomb, f1, f2, f3, nuke, sweep, extra)
        }
        say("extra cities ${setup.extraCities.size}, sweep target ${setup.sweepTarget?.position}, nuke target ${setup.nukeTarget?.position}")
        val w = MapWindow(setup.capital.position)
        fun code(tile: Tile) = w.code(tile.position.x, tile.position.y)!!

        // ---- Military tab
        t.act(a, "tab", emptyList(), TAB, null)
        t.act(a, "selunit", emptyList(), "u:${setup.f1.id}", null)
        t.render(a, "mp-military-before")
        if (t.act(a, "mp_next", emptyList(), null, null) && st.unitId != setup.f1.id) tick("military tab: next unit")

        // ---- Air sweep (fighter 1), then a typed-in air strike (fighter 2) on the same AA gun
        val sweepTarget = setup.sweepTarget
        if (sweepTarget != null) {
            t.act(a, "selunit", emptyList(), "u:${setup.f1.id}", null)
            if (t.eng { militaryChoices(setup.f1) }.any { it.id == "ask:mp_sweep" } &&
                t.act(a, "mp_sweep", emptyList(), code(sweepTarget), null) && lastFlash().startsWith("✈️")) tick("air sweep")
            t.act(a, "selunit", emptyList(), "u:${setup.f2.id}", null)
            val hpBefore = t.eng { sweepTarget.militaryUnit?.health ?: 0 }
            if (t.act(a, "mp_attack", emptyList(), code(sweepTarget), null) && lastFlash().startsWith("⚔️")) {
                val hpAfter = t.eng { sweepTarget.militaryUnit?.health ?: 0 }
                if (hpAfter < hpBefore) tick("air strike on a typed tile")
            }
            t.act(a, "selunit", emptyList(), "u:${setup.f3.id}", null)
            if (!t.act(a, "mp_attack", emptyList(), code(setup.capital), null)) tick("attack: refuses tiles without enemies")
        }

        // ---- Nukes
        t.act(a, "selunit", emptyList(), "u:${setup.bomb.id}", null)
        t.act(a, "tab", emptyList(), "UNITS", null); t.render(a, null)
        val core = t.eng { GameOps.unitChoices(setup.bomb) }
        say("bomb's Unit actions: ${core.size} options, 'Nuke a tile…' at index ${core.indexOfFirst { it.id == "ask:mp_nuke" }} " +
            "(${core.count { it.id.startsWith("atk:") }} 'atk:' entries before it)")
        if (!t.act(a, "mp_nuke", emptyList(), code(setup.capital), null)) tick("nuke: refuses its own tile")
        t.eng { g -> g.gameParameters.nuclearWeaponsEnabled = false }
        val refusedWhenOff = !t.act(a, "mp_nuke", emptyList(), setup.nukeTarget?.let { code(it) } ?: "A1", null) &&
            t.eng { militaryChoices(setup.bomb) }.none { it.id == "ask:mp_nuke" }
        t.eng { g -> g.gameParameters.nuclearWeaponsEnabled = true }
        if (refusedWhenOff) tick("nuke: respects 'nuclear weapons disabled'")
        val nukeTarget = setup.nukeTarget
        if (nukeTarget != null) {
            val nc = code(nukeTarget)
            if (t.act(a, "mp_nuke", emptyList(), nc, null) && lastFlash().contains("Target locked") &&
                t.eng { militaryChoices(setup.bomb) }.any { it.id == "do:mp_nuke_confirm" && it.label.contains(nc) }) tick("nuke: target + blast warning")
            t.act(a, "tab", emptyList(), TAB, null)
            t.render(a, "mp-military-nuke-pending")
            val enemyBefore = t.eng { nukeTarget.getUnits().count() }
            if (t.act(a, "uact", emptyList(), "do:mp_nuke_confirm", null) && lastFlash().startsWith("☢️")) {
                val (bombGone, enemyAfter) = t.eng { g -> GameOps.civOf(g, a).units.getCivUnits().none { it == setup.bomb } to nukeTarget.getUnits().count() }
                say("nuke: bomb consumed=$bombGone, units on ground zero $enemyBefore→$enemyAfter")
                if (bombGone) tick("nuke: confirmed launch")
            }
        }

        // ---- Deals: city + war declaration, with an AI and between humans
        val ais = t.eng { g ->
            val civ = GameOps.civOf(g, a)
            val list = g.civilizations.filter { it.isMajorCiv && it.isAlive() && it.playerType == PlayerType.AI }
            for (x in list + GameOps.civOf(g, b)) for (y in list + civ) if (x != y && !x.knows(y)) x.diplomacyFunctions.makeCivilizationsMeet(y)
            list
        }
        if (ais.size >= 2 && setup.extraCities.size >= 2) {
            val ai1 = ais[0]; val ai2 = ais[1]; val target3 = ais.getOrElse(2) { ai2 }
            t.act(a, "tab", emptyList(), "DIPLOMACY", null)
            t.act(a, "selciv", emptyList(), "civ:${ai1.civID}", null)
            // City to an AI
            val cityId = setup.extraCities[1]
            val (give, ask) = t.eng { g -> Features.tradeChoices(GameOps.civOf(g, a), ai1) }
            val cityChoice = give.firstOrNull { it.id.contains("~City~") && it.id.endsWith(cityId) }
            if (cityChoice != null) {
                t.act(a, "give", emptyList(), cityChoice.id, null)
                t.act(a, "trade", emptyList(), null, null); lastFlash()
                if (t.eng { g -> g.getCities().first { it.id == cityId }.civ == ai1 }) tick("deal: city traded (AI)")
            } else say("no City offer for AI (give: ${give.map { it.label }})")
            // AI declares war on a third civ (paid with gold); else we declare war as our side of the deal
            val warAsk = ask.firstOrNull { it.id.contains("~WarDeclaration~") && it.id.endsWith(ai2.civID) }
            val gold = give.filter { it.id.contains("~Gold~") }.maxByOrNull { it.id.split("~")[2].toInt() }
            if (warAsk != null) {
                t.act(a, "ask", emptyList(), warAsk.id, null); if (gold != null) t.act(a, "give", emptyList(), gold.id, null)
                t.act(a, "trade", emptyList(), null, null); lastFlash()
            }
            if (warAsk != null && t.eng { ai1.isAtWarWith(ai2) }) tick("deal: AI declares war on a third civ")
            else {
                val (give2, _) = t.eng { g -> Features.tradeChoices(GameOps.civOf(g, a), ai1) }
                val wd = give2.firstOrNull { it.id.contains("~WarDeclaration~") && it.id.endsWith(ai2.civID) }
                if (wd != null) {
                    t.act(a, "give", emptyList(), wd.id, null); t.act(a, "trade", emptyList(), null, null); lastFlash()
                    if (t.eng { g -> GameOps.civOf(g, a).isAtWarWith(ai2) }) tick("deal: war declaration (AI, we join)")
                } else say("no WarDeclaration offer (ask: ${ask.map { it.label }})")
            }
            // Human <-> human: Alice gives a city, Bob declares war on a third civ
            val cityB = setup.extraCities.getOrNull(2)
            t.act(a, "selciv", emptyList(), "civ:${b.civId}", null)
            val (gb, ab) = t.eng { g -> Features.tradeChoices(GameOps.civOf(g, a), GameOps.civOf(g, b)) }
            val cityOffer = cityB?.let { id -> gb.firstOrNull { it.id.contains("~City~") && it.id.endsWith(id) } }
            val bobWar = ab.firstOrNull { it.id.contains("~WarDeclaration~") && it.id.endsWith(target3.civID) }
                ?: ab.firstOrNull { it.id.contains("~WarDeclaration~") }
            say("human deal: city=${cityOffer?.label} war=${bobWar?.label}")
            if (cityOffer != null) t.act(a, "give", emptyList(), cityOffer.id, null)
            if (bobWar != null) t.act(a, "ask", emptyList(), bobWar.id, null)
            if ((cityOffer != null || bobWar != null) && t.act(a, "trade", emptyList(), null, null)) {
                lastFlash()
                val p = t.ctl.outbox.firstOrNull { it.kind == "trade" && it.fromCiv == a.civId && it.toCiv == b.civId }
                if (p != null) {
                    t.ctl.outbox.remove(p)
                    val res = t.eng { t.ctl.answer(t.s, p, true) }; say("answer: $res")
                    val warCiv = bobWar?.id?.substringAfterLast("~")
                    val (cityOk, warOk) = t.eng { g ->
                        val bob = GameOps.civOf(g, b)
                        Pair(cityB != null && g.getCities().first { it.id == cityB }.civ == bob,
                            warCiv != null && bob.isAtWarWith(g.getCivilization(warCiv)))
                    }
                    if (cityOk) tick("deal: city traded (human ↔ human)")
                    if (warOk) tick("deal: war declaration (human ↔ human)")
                }
            }
        } else say("skipping deals: ${ais.size} AIs, ${setup.extraCities.size} extra cities")

        // ---- Rebase (fighter 3 flies to the untraded extra city)
        setup.extraCities.firstOrNull()?.let { cityId ->
            t.act(a, "selunit", emptyList(), "u:${setup.f3.id}", null)
            val opt = t.eng { g ->
                val tile = g.getCities().first { it.id == cityId }.getCenterTile()
                militaryChoices(setup.f3).firstOrNull { it.id == "mp:rebase:${tile.position.x},${tile.position.y}" }
            }
            if (opt != null && t.act(a, "uact", emptyList(), opt.id, null) &&
                t.eng { g -> setup.f3.getTile() == g.getCities().first { it.id == cityId }.getCenterTile() }) { lastFlash(); tick("air rebase") }
            else say("rebase options: ${t.eng { militaryChoices(setup.f3) }.map { it.label }}")
        }

        // ---- Gift a unit to a city-state (engine GiftUnit action in its territory)
        val gift = t.eng { g ->
            val civ = GameOps.civOf(g, a)
            val cs = Features.cityStates(civ).firstOrNull { !it.isAtWarWith(civ) && it.cities.isNotEmpty() }
            val tile = cs?.cities?.flatMap { it.getTiles().toList() }
                ?.firstOrNull { it.isLand && !it.isImpassible() && !it.isCityCenter() && it.getUnits().none() }
            val u = if (tile != null) civ.units.addUnit("Warrior", civ.getCapital()) else null
            if (u != null && tile != null && cs != null) {
                u.removeFromTile(); u.putInTile(tile); u.currentMovement = u.getMaxMovement().toFloat()
                Pair(u, cs)
            } else null
        }
        if (gift != null) {
            val (u, cs) = gift
            t.act(a, "selunit", emptyList(), "u:${u.id}", null)
            val inf = t.eng { g -> cs.getDiplomacyManager(GameOps.civOf(g, a))!!.getInfluence() }
            val opt = t.eng { GameOps.unitChoices(u) }.firstOrNull { it.id.startsWith("act:GiftUnit") }
            if (opt != null && t.act(a, "uact", emptyList(), opt.id, null)) {
                val (gone, inf2) = t.eng { g ->
                    GameOps.civOf(g, a).units.getCivUnits().none { it == u } to cs.getDiplomacyManager(GameOps.civOf(g, a))!!.getInfluence()
                }
                say("gift unit to ${cs.civName}: gone=$gone influence $inf→$inf2")
                if (gone) tick("gift unit to city-state")
            } else say("no GiftUnit action for the warrior in ${cs.civName} territory")
        } else say("no city-state tile for the gift test")

        t.act(a, "tab", emptyList(), TAB, null)
        t.eng { Panel.render(t.s, a, st) }.embed.fields.forEach { f -> say("tab field ${f.name}: ${f.value?.replace("\n", " | ")}") }
        t.render(a, "mp-military-after")
        st.flash.clear()
    }
}

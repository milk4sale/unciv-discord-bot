package civbot

import com.unciv.Constants
import com.unciv.logic.GameInfo
import com.unciv.logic.city.City
import com.unciv.logic.city.CityFocus
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.logic.map.tile.Tile
import com.unciv.models.ruleset.Building
import com.unciv.models.ruleset.tile.TileImprovement
import com.unciv.models.stats.Stat
import com.unciv.models.stats.Stats
import com.unciv.view.GameView
import net.dv8tion.jda.api.interactions.components.ActionRow

/**
 * City management extras, mirroring Unciv's CityScreen / ImprovementPicker logic (headless):
 *  - 👥 Citizens tab: work / lock / stop working tiles, specialists, reset, avoid growth
 *  - Sell a building (city action), rename city / unit (popups)
 *  - Worker improvement queue ("Remove Forest, then Farm", "Queue next: …")
 *  - Undo of the current player's actions within their turn
 */
object CityPlus : Extension {
    override val viewVerbs = setOf("cp_city")

    private const val TAB = "CITIZENS"
    private const val MAX_UNDO = 10
    private const val MAX_NAME = 40

    override val tabs = listOf(ExtTab(TAB, "Citizens", "👥", "Work tiles, lock citizens, specialists"))

    override val prompts = mapOf(
        "cp_rename_city" to Prompt("Rename city", "New city name", "e.g. Nova Roma"),
        "cp_rename_unit" to Prompt("Rename unit", "New unit name", "Type the unit type (e.g. Warrior) to reset"),
    )

    // ================================================================ helpers

    private val statEmoji = mapOf(
        Stat.Food to "🍞", Stat.Production to "⚙️", Stat.Gold to "💰", Stat.Science to "🔬",
        Stat.Culture to "🎭", Stat.Happiness to "😊", Stat.Faith to "🙏",
    )

    private fun num(v: Float) = if (v == v.toInt().toFloat()) v.toInt().toString() else "%.1f".format(v)
    private fun yields(s: Stats): String =
        s.asSequence().sortedBy { Stat.entries.indexOf(it.key).let { i -> if (it.key == Stat.Food) -1 else i } }
            .joinToString(" ") { "${statEmoji[it.key] ?: it.key.name}${num(it.value)}" }.ifEmpty { "no yield" }
    private fun signed(v: Float) = (if (v >= 0) "+" else "") + num(v)
    private fun focusName(f: CityFocus) = when (f) {
        CityFocus.NoFocus -> "Default (balanced)"
        CityFocus.Manual -> "Manual"
        else -> f.name.removeSuffix("Focus")
    }

    private fun tileStats(city: City, t: Tile): Stats = t.stats.getTileStats(city, city.civ)
    private fun code(st: PanelState?, t: Tile) = st?.window?.code(t.position.x, t.position.y) ?: "${t.position.x},${t.position.y}"
    private fun terrain(t: Tile, civ: Civilization) = GameOps.tileDescription(t, civ)

    /** Same rules as CityTileGroup's WORKABLE state: ours, in range, not another city's, not the center,
     *  not blockaded, has yields, and doesn't already yield without a citizen. */
    private fun workable(city: City, t: Tile): Boolean =
        t in city.tilesInRange && t.getOwner() == city.civ && !t.isCityCenter() &&
            (t.getWorkingCity() == null || t.getWorkingCity() == city) && !t.isBlockaded() &&
            !tileStats(city, t).isEmpty() && (city.isWorked(t) || !t.providesYield())

    private fun cityOf(ctx: ActionCtx): City {
        val city = ctx.selectedCity()
        if (city.isPuppet) throw PlayerError("${city.name} is a puppet — it manages its own citizens. Annex it first.")
        return city
    }

    private fun tileFrom(game: GameInfo, v: String): Tile {
        val (x, y) = v.substringAfter(":").split(",").map { it.trim().toInt() }
        return game.tileMap.getIfTileExistsOrNull(x, y) ?: throw PlayerError("That tile doesn't exist.")
    }

    /** Trimmed, markup-free, 1..40 chars. */
    private fun cleanName(raw: String?): String {
        val n = raw.orEmpty().filterNot { it in "[]{}<>@#*_~`|\\" }.replace(Regex("\\s+"), " ").trim()
        if (n.isEmpty()) throw PlayerError("The name can't be empty.")
        if (n.length > MAX_NAME) throw PlayerError("Names can be at most $MAX_NAME characters (that one has ${n.length}).")
        return n
    }

    // ================================================================ 👥 Citizens tab

    private fun tileChoices(city: City, st: PanelState?): List<Choice> {
        val civ = city.civ
        val free = city.population.getFreePopulation()
        val out = ArrayList<Choice>()
        for (t in city.getWorkedTiles().sortedBy { code(st, it) }) {
            val c = code(st, t); val y = yields(tileStats(city, t)); val d = terrain(t, civ)
            val xy = "${t.position.x},${t.position.y}"
            out += if (city.lockedTiles.contains(t.position)) Choice("u:$xy", "Unlock $c — $y", "🔒 locked · $d", "🔓")
            else Choice("l:$xy", "Lock $c — $y", "Worked by the governor · $d", "🔒")
            out += Choice("s:$xy", "Stop working $c", "The citizen becomes unemployed · $d", "✖️")
        }
        val unworked = city.tilesInRange.filter { !city.isWorked(it) && workable(city, it) }
            .map { it to tileStats(city, it) }
            .sortedByDescending { (_, s) -> s.food * 1.2f + s.production * 1.1f + s.gold + s.science + s.culture + s.faith }
        for ((t, s) in unworked) {
            out += Choice("w:${t.position.x},${t.position.y}", "Work ${code(st, t)} — ${yields(s)}",
                (if (free > 0) "Assign an unemployed citizen (locked) · " else "No unemployed citizen — free one first · ") + terrain(t, civ), "⬜")
        }
        return out.take(25)
    }

    private fun specialistSources(city: City, name: String) =
        city.cityConstructions.getBuiltBuildings().filter { it.newSpecialists()[name] > 0 }.map { it.name }

    private fun specialistStats(city: City, name: String): String {
        val gpp = city.getRuleset().specialists[name]?.greatPersonPoints?.asSequence()?.joinToString(" ") { "+${it.value} ${it.key} pts" }.orEmpty()
        return listOf(yields(city.cityStats.getStatsOfSpecialist(name)), gpp).filter { it.isNotBlank() }.joinToString(" · ")
    }

    private fun specialistChoices(city: City): List<Choice> {
        val out = ArrayList<Choice>()
        val free = city.population.getFreePopulation()
        val alloc = city.population.getNewSpecialists()
        for ((name, max) in city.population.getMaxSpecialists().asSequence().sortedBy { it.key }) {
            if (!city.getRuleset().specialists.containsKey(name)) continue
            val n = alloc[name]
            if (n < max) out += Choice("+:$name", "Add $name ($n/$max)",
                (if (free > 0) "" else "Needs an unemployed citizen · ") + specialistStats(city, name), "➕")
            if (n > 0) out += Choice("-:$name", "Remove $name ($n/$max)", "The citizen becomes unemployed", "➖")
        }
        return out.take(25)
    }

    override fun renderTab(tabId: String, t: TabCtx) {
        remember(t.s, t.seat, t.st)
        val civ = t.civ
        val cities = GameOps.citiesOf(civ)
        val city = cities.firstOrNull { it.id == t.st.cityId } ?: cities.firstOrNull()
        t.st.cityId = city?.id
        if (city == null) {
            t.eb.addField("👥 Citizens", "No cities yet — found one with a Settler in ⚔️ Units.", false)
            t.mapAround(GameOps.focusTile(civ))
            return
        }
        t.mapAround(city.getCenterTile(), Overlay(selectedTile = city.getCenterTile(), reachable = city.getWorkedTiles().toSet()))
        val st = t.st
        val pop = city.population
        val free = pop.getFreePopulation()
        val s = city.cityStats.currentCityStats
        val growth = pop.getNumTurnsToStarvation()?.let { "starving in $it turns" }
            ?: pop.getNumTurnsToNewPopulation()?.let { "grows in $it turns" } ?: "not growing"
        val canEdit = t.myTurn && !city.isPuppet
        t.eb.addField("👥 ${city.name} — ${pop.population} citizens" + (if (city.isPuppet) " (puppet)" else ""),
            "🌾 ${city.workedTiles.size} on tiles · 🎓 ${pop.getNumberOfSpecialists()} specialists · " +
                (if (free > 0) "🧍 **$free unemployed**" else "🧍 0 unemployed") +
                "\n🍞 ${signed(s.food)} ($growth) · ⚙️ ${signed(s.production)} · 💰 ${signed(s.gold)} · 🔬 ${signed(s.science)} · 🎭 ${signed(s.culture)}" +
                "\n🎯 Focus: ${focusName(city.getCityFocus())} · Specialists: ${if (city.manualSpecialists) "✋ manual" else "🤖 auto"}" +
                " · Avoid growth: ${if (city.avoidGrowth) "on" else "off"}" +
                "\n_Lighter tiles on the map are worked. Locked tiles stay put when the city re-plans._", false)

        val lines = ArrayList<String>()
        lines += "🏙️ ${code(st, city.getCenterTile())} city center — ${yields(tileStats(city, city.getCenterTile()))}"
        for (tile in city.getWorkedTiles().sortedBy { code(st, it) }) {
            val mark = if (city.lockedTiles.contains(tile.position)) "🔒" else "🟩"
            lines += "$mark ${code(st, tile)} ${terrain(tile, civ)} — ${yields(tileStats(city, tile))}"
        }
        val body = StringBuilder()
        for (l in lines) {
            if (body.length + l.length + 1 > 1000) { body.append("\n…"); break }
            if (body.isNotEmpty()) body.append('\n')
            body.append(l)
        }
        t.eb.addField("🌾 Worked tiles (🔒 locked · 🟩 governor's pick)", body.toString(), false)

        val specLines = pop.getMaxSpecialists().asSequence().sortedBy { it.key }
            .filter { city.getRuleset().specialists.containsKey(it.key) }
            .map { (name, max) ->
                "**$name** ${pop.getNewSpecialists()[name]}/$max — ${specialistStats(city, name)} (${specialistSources(city, name).joinToString()})"
            }.toList()
        t.eb.addField("🎓 Specialists", specLines.joinToString("\n").take(1000)
            .ifEmpty { "No specialist slots yet — buildings like a Market, Workshop, Amphitheater or University add them." }, false)

        t.rows += ActionRow.of(t.select("cp_city", "Select a city (${cities.size})",
            cities.map { c -> Choice("city:${c.id}", c.name, "pop ${c.population.population} · ${c.population.getFreePopulation()} unemployed", if (c.isCapital()) "⭐" else "🏙️") },
            "city:${city.id}"))
        t.rows += ActionRow.of(t.select("cp_tile", if (free > 0) "Work a tile ($free unemployed)…" else "Work, lock or free a tile…",
            tileChoices(city, st), enabled = canEdit))
        val specs = specialistChoices(city)
        t.rows += ActionRow.of(t.select("cp_spec", if (specs.isEmpty()) "No specialist slots" else "Add or remove specialists…", specs, enabled = canEdit))
        t.endRow += t.button("cp_reset", "Reset", "🔄", 2, canEdit)
        t.endRow += t.button("cp_avoid", if (city.avoidGrowth) "Avoid growth: on" else "Avoid growth: off", "🚫", if (city.avoidGrowth) 1 else 2, canEdit)
        t.endRow += t.button("cp_manspec", if (city.manualSpecialists) "Specialists: manual" else "Specialists: auto",
            if (city.manualSpecialists) "✋" else "🤖", if (city.manualSpecialists) 1 else 2, canEdit)
    }

    private fun doTile(ctx: ActionCtx): String {
        ctx.requireTurn()
        val city = cityOf(ctx)
        val v = ctx.value ?: throw PlayerError("Pick a tile.")
        val tile = tileFrom(ctx.game, v)
        val c = code(ctx.st, tile)
        val msg = when (v.substringBefore(":")) {
            "w" -> {
                if (!workable(city, tile)) throw PlayerError("${city.name} can't work $c.")
                if (!city.isWorked(tile)) {
                    if (city.population.getFreePopulation() <= 0)
                        throw PlayerError("${city.name} has no unemployed citizen. Stop working a tile or remove a specialist first.")
                    city.workTile(tile)
                }
                city.lockTile(tile)
                "👥 ${city.name} now works **$c** (locked) — ${yields(tileStats(city, tile))}."
            }
            "l" -> {
                if (!city.isWorked(tile)) throw PlayerError("$c isn't worked by ${city.name}.")
                city.lockTile(tile); "🔒 Locked **$c** — ${city.name} keeps working it."
            }
            "u" -> { city.unlockTile(tile); "🔓 Unlocked **$c** — the governor may reassign it." }
            "s" -> {
                if (!city.stopWorkingTile(tile)) throw PlayerError("$c isn't worked by ${city.name}.")
                "✖️ ${city.name} stopped working **$c** — ${city.population.getFreePopulation()} unemployed."
            }
            else -> throw PlayerError("Unknown tile action.")
        }
        city.cityStats.update()
        return msg
    }

    private fun doSpecialist(ctx: ActionCtx): String {
        ctx.requireTurn()
        val city = cityOf(ctx)
        val v = ctx.value ?: throw PlayerError("Pick a specialist.")
        val name = v.substringAfter(":")
        val max = city.population.getMaxSpecialists()[name]
        val n = city.population.getNewSpecialists()[name]
        val alloc = city.population.specialistAllocations
        val msg = when (v.substringBefore(":")) {
            "+" -> {
                if (n >= max) throw PlayerError("All $name slots in ${city.name} are full ($n/$max).")
                if (city.population.getFreePopulation() <= 0)
                    throw PlayerError("${city.name} has no unemployed citizen. Stop working a tile first.")
                alloc.add(name, 1); "➕ ${city.name}: new **$name** (${n + 1}/$max)."
            }
            "-" -> {
                if (n <= 0) throw PlayerError("${city.name} has no $name.")
                alloc.add(name, -1); "➖ ${city.name}: removed a **$name** — the citizen is unemployed."
            }
            else -> throw PlayerError("Unknown specialist action.")
        }
        city.manualSpecialists = true // Unciv: touching specialists switches the city to manual specialists
        city.cityStats.update()
        return msg
    }

    // ================================================================ sell building

    private fun sellable(city: City): List<Building> {
        if (city.isPuppet) return emptyList()
        if (city.hasSoldBuildingThisTurn && !city.civ.gameInfo.gameParameters.godMode) return emptyList()
        return city.cityConstructions.getBuiltBuildings()
            .filter { it.isSellable() && !city.civ.civConstructions.hasFreeBuilding(city, it) }
            .sortedWith(compareByDescending<Building> { it.maintenance }.thenByDescending { city.getGoldForSellingBuilding(it.name) })
            .toList()
    }

    private fun sell(city: City, name: String): String {
        val b = sellable(city).firstOrNull { it.name == name }
            ?: throw PlayerError(if (city.hasSoldBuildingThisTurn) "${city.name} already sold a building this turn." else "You can't sell $name in ${city.name}.")
        val gold = city.getGoldForSellingBuilding(b.name)
        city.sellBuilding(b)
        return "💸 Sold **${b.name}** in ${city.name} for $gold gold" + (if (b.maintenance > 0) " (saves ${b.maintenance} gold upkeep per turn)." else ".")
    }

    // ================================================================ worker queue (ImprovementPickerScreen logic)

    private fun queueField() = Tile::class.java.getDeclaredField("improvementQueue").apply { isAccessible = true }

    private fun queueNames(tile: Tile): List<String> = try {
        (queueField().get(tile) as List<*>).map { e ->
            runCatching { e!!.javaClass.getDeclaredField("improvement").apply { isAccessible = true }.get(e) as String }
                .getOrElse { e.toString().substringBefore(": ") }
        }
    } catch (_: Exception) { listOfNotNull(tile.improvementInProgress) }

    private fun dropQueued(tile: Tile) {
        @Suppress("UNCHECKED_CAST") val q = queueField().get(tile) as MutableList<Any?>
        while (q.size > 1) q.removeAt(q.size - 1)
    }

    /** Removal of the top terrain layer (e.g. "Remove Forest"), if the ruleset has one. */
    private fun removalFor(tile: Tile): TileImprovement? {
        if (tile.terrainFeatures.isEmpty()) return null
        return tile.ruleset.tileImprovements[Constants.remove + tile.lastTerrain.name]
    }

    private fun candidates(u: MapUnit, tile: Tile) = u.civ.gameInfo.ruleset.tileImprovements.values.filter {
        it.turnsToBuild != -1 && !it.name.startsWith(Constants.remove) && it.name != tile.improvement && u.canBuildImprovement(it)
    }

    /** Improvements Unciv's picker marks "queueable": only blocked by the top terrain, whose removal is buildable now. */
    private fun clearThenBuild(u: MapUnit): List<Pair<TileImprovement, TileImprovement>> {
        val tile = u.getTile()
        val removal = removalFor(tile) ?: return emptyList()
        if (!u.canBuildImprovement(removal)) return emptyList()
        val gv = GameView(u.civ.gameInfo, u.civ)
        val tv = gv.getTile(tile); val uv = gv.getMapUnitView(u)
        if (tv.getImprovementBuildingProblems(removal, uv).isNotEmpty()) return emptyList()
        return candidates(u, tile).filter { imp ->
            val now = tv.getImprovementBuildingProblems(imp, uv)
            now.isNotEmpty() && !now.all { it.reportable } &&
                tv.getImprovementBuildingProblems(imp, uv, pretendLastTerrainRemoved = true).isEmpty()
        }.map { removal to it }
    }

    /** What may follow the improvement in progress (one queued item, like Unciv's picker). */
    private fun followUps(u: MapUnit): List<TileImprovement> {
        val tile = u.getTile()
        val cur = tile.getTileImprovementInProgress() ?: return emptyList()
        if (queueNames(tile).size != 1) return emptyList()
        val gv = GameView(u.civ.gameInfo, u.civ)
        val tv = gv.getTile(tile); val uv = gv.getMapUnitView(u)
        val removing = cur.name == removalFor(tile)?.name
        return candidates(u, tile).filter { imp ->
            imp.name != cur.name && when {
                removing -> tv.getImprovementBuildingProblems(imp, uv, pretendLastTerrainRemoved = true).isEmpty()
                cur.isRoad() -> !imp.isRoad() && tv.getImprovementBuildingProblems(imp, uv).isEmpty()
                else -> imp.isRoad() && tv.getImprovementBuildingProblems(imp, uv).isEmpty()
            }
        }
    }

    // ================================================================ unit / city menus

    override fun unitChoices(u: MapUnit): List<Choice> {
        val out = ArrayList<Choice>()
        undoChoice(u.civ)?.let { out += it }
        val tile = u.getTile()
        if (u.isCivilian()) runCatching {
            if (u.hasMovement()) for ((rem, imp) in clearThenBuild(u).take(4)) {
                if (tile.improvementInProgress == rem.name) continue
                out += Choice("cp:rq:${imp.name}", "${GameOps.clean(rem.name)}, then build ${imp.name}",
                    "${rem.getTurnsToBuild(u.civ, u)} + ${imp.getTurnsToBuild(u.civ, u)} turns (queued)", "🪓")
            }
            for (imp in followUps(u).take(5))
                out += Choice("cp:qn:${imp.name}", "Queue next: ${imp.name}", "After ${tile.improvementInProgress} (${imp.getTurnsToBuild(u.civ, u)} turns)", "📋")
            val q = queueNames(tile)
            if (q.size > 1) out += Choice("cp:unq", "Unqueue ${q.drop(1).joinToString()}", "Keep building ${q.first()} only", "✖️")
        }
        out += Choice("ask:cp_rename_unit:${u.id}", "Rename unit", "Now: ${u.label()}", "✏️")
        return out
    }

    override fun doUnitChoice(u: MapUnit, id: String): String? {
        if (!id.startsWith("cp:")) return null
        val tile = u.getTile()
        return when {
            id.startsWith("cp:rq:") -> {
                val name = id.removePrefix("cp:rq:")
                val (rem, imp) = clearThenBuild(u).firstOrNull { it.second.name == name } ?: throw PlayerError("Can't queue $name here any more.")
                GameView(u.civ.gameInfo, u.civ).getMapUnitView(u).tryStartWorkingOnImprovement(rem, imp)
                u.action = null
                "🪓 ${u.label()}: ${GameOps.clean(rem.name)}, then **${imp.name}** (queued)."
            }
            id.startsWith("cp:qn:") -> {
                val name = id.removePrefix("cp:qn:")
                val imp = followUps(u).firstOrNull { it.name == name } ?: throw PlayerError("Can't queue $name after ${tile.improvementInProgress}.")
                tile.queueImprovement(imp, u.civ, u)
                "📋 ${u.label()} will build **${imp.name}** after ${tile.improvementInProgress}."
            }
            id == "cp:unq" -> { dropQueued(tile); "✖️ Queue cleared — ${u.label()} keeps building ${tile.improvementInProgress ?: "nothing"}." }
            else -> null
        }
    }

    override fun cityChoices(city: City, window: MapWindow?): List<Choice> {
        val out = ArrayList<Choice>()
        undoChoice(city.civ)?.let { out += it }
        out += Choice("ask:cp_rename_city:${city.id}", "Rename ${city.name}", "Give the city a new name", "✏️")
        for (b in sellable(city).take(5))
            out += Choice("cp:sell:${b.name}", "Sell ${b.name} for ${city.getGoldForSellingBuilding(b.name)} gold",
                (if (b.maintenance > 0) "Saves ${b.maintenance} upkeep · " else "") + "one sale per city per turn", "💸")
        return out
    }

    override fun doCityChoice(city: City, id: String): String? =
        if (id.startsWith("cp:sell:")) sell(city, id.removePrefix("cp:sell:")) else null

    // ================================================================ undo

    private class Snap(val data: String, val what: String)
    private class UndoStack(val civId: String, val turn: Int) { val snaps = ArrayDeque<Snap>() }
    /** Per game (GameInfo.gameId survives save/load and undo) */
    private val stacks = HashMap<String, UndoStack>()
    /** Panel states we've seen, to describe what's being snapshotted */
    private val seen = HashMap<String, PanelState>()
    private fun remember(s: Session, seat: Seat, st: PanelState) { seen["${s.meta.id}:${seat.userId}"] = st }

    private fun validStack(game: GameInfo, civ: Civilization): UndoStack? {
        val st = stacks[game.gameId] ?: return null
        return st.takeIf { it.civId == civ.civID && game.currentPlayerCiv.civID == civ.civID && it.turn == game.turns && it.snaps.isNotEmpty() }
    }

    private fun undoChoice(civ: Civilization): Choice? {
        val st = validStack(civ.gameInfo, civ) ?: return null
        return Choice("do:cp_undo", "Undo last action", "Reverts: ${st.snaps.last().what}".take(100), "↩️")
    }

    private val myViewVerbs = setOf("cp_undo", "cp_city")

    private fun describe(verb: String, civ: Civilization, st: PanelState?): String {
        val unit = st?.unitId?.let { GameOps.unitById(civ, it) }?.label()?.let { " ($it)" }.orEmpty()
        val city = st?.cityId?.let { id -> civ.cities.firstOrNull { it.id == id } }?.name?.let { " in $it" }.orEmpty()
        return when (verb) {
            "uact" -> "unit action$unit"
            "step", "goto" -> "move$unit"
            "prod" -> "production change$city"
            "cact" -> "city action$city"
            "conq" -> "decision on a captured city"
            "tech", "goal" -> "research choice"
            "pol" -> "policy adoption"
            "pantheon" -> "pantheon"
            "relconfirm" -> "religion"
            "gp" -> "great person choice"
            "trade" -> "trade offer"
            "war" -> "declaration of war"
            "peace" -> "peace offer"
            "friend" -> "friendship offer"
            "denounce" -> "denouncement"
            "csact" -> "city-state action"
            "advisor" -> "Auto-pick"
            "cp_tile" -> "tile assignment$city"
            "cp_spec" -> "specialist change$city"
            "cp_reset" -> "citizen reset$city"
            "cp_avoid" -> "avoid-growth toggle$city"
            "cp_manspec" -> "specialist mode$city"
            "cp_rename_city" -> "city rename"
            "cp_rename_unit" -> "unit rename"
            else -> verb
        }
    }

    override fun beforeMutation(s: Session, seat: Seat, verb: String) {
        if (verb in myViewVerbs) return
        val g = s.game ?: return
        runCatching {
            val cur = g.currentPlayerCiv
            if (cur.civID != seat.civId) return
            var stack = stacks[g.gameId]
            if (stack == null || stack.civId != cur.civID || stack.turn != g.turns) {
                stack = UndoStack(cur.civID, g.turns); stacks[g.gameId] = stack
            }
            val data = Engine.serialize(g)
            val what = describe(verb, cur, seen["${s.meta.id}:${seat.userId}"])
            // The previous action changed nothing (e.g. it failed or only changed the view): replace its snapshot
            if (stack.snaps.lastOrNull()?.data == data) stack.snaps.removeLast()
            stack.snaps.addLast(Snap(data, what))
            while (stack.snaps.size > MAX_UNDO) stack.snaps.removeFirst()
        }
    }

    override fun onTurnEnded(s: Session) { s.game?.let { stacks.remove(it.gameId) } }

    private fun undo(ctx: ActionCtx) {
        ctx.requireTurn()
        val stack = validStack(ctx.game, ctx.civ) ?: throw PlayerError("Nothing to undo this turn.")
        val now = Engine.serialize(ctx.game)
        while (stack.snaps.isNotEmpty() && stack.snaps.last().data == now) stack.snaps.removeLast() // no-op entries
        val snap = stack.snaps.removeLastOrNull() ?: throw PlayerError("Nothing to undo this turn.")
        val g = Engine.deserialize(snap.data)
        ctx.s.game = g
        com.unciv.UncivGame.Current.gameInfo = g
        ctx.changed = true
        ctx.flash += "↩️ Undid: ${snap.what}" + (if (stack.snaps.isNotEmpty()) " (${stack.snaps.size} more step(s) can be undone)" else "")
    }

    // ================================================================ verbs

    override fun handle(verb: String, ctx: ActionCtx): Boolean {
        if (!verb.startsWith("cp_")) return false
        remember(ctx.s, ctx.seat, ctx.st)
        when (verb) {
            "cp_city" -> ctx.st.cityId = ctx.value?.removePrefix("city:")
            "cp_tile" -> { ctx.flash += doTile(ctx); ctx.changed = true }
            "cp_spec" -> { ctx.flash += doSpecialist(ctx); ctx.changed = true }
            "cp_reset" -> {
                ctx.requireTurn(); val city = cityOf(ctx)
                if (city.getCityFocus() == CityFocus.Manual) city.setCityFocus(CityFocus.NoFocus)
                city.reassignAllPopulation() // automatic specialists + unlock everything (Unciv's "Reset Citizens")
                ctx.flash += "🔄 ${city.name}'s citizens are back on automatic — all locks cleared."; ctx.changed = true
            }
            "cp_avoid" -> {
                ctx.requireTurn(); val city = cityOf(ctx)
                city.avoidGrowth = !city.avoidGrowth; city.reassignPopulation()
                ctx.flash += if (city.avoidGrowth) "🚫 ${city.name} will avoid growing." else "🌱 ${city.name} may grow again."; ctx.changed = true
            }
            "cp_manspec" -> {
                ctx.requireTurn(); val city = cityOf(ctx)
                if (city.manualSpecialists) { city.manualSpecialists = false; city.reassignPopulation() }
                else city.manualSpecialists = true
                ctx.flash += if (city.manualSpecialists) "✋ ${city.name}: you choose the specialists." else "🤖 ${city.name}: the governor chooses specialists."
                ctx.changed = true
            }
            "cp_rename_city" -> {
                ctx.requireTurn()
                val city = ctx.civ.cities.firstOrNull { it.id == ctx.args.firstOrNull() } ?: ctx.selectedCity()
                val name = cleanName(ctx.value)
                if (ctx.game.getCities().any { it != city && it.name.equals(name, ignoreCase = true) })
                    throw PlayerError("There's already a city called $name.")
                val old = city.name
                city.name = name
                ctx.flash += "✏️ **$old** is now called **$name**."; ctx.changed = true
            }
            "cp_rename_unit" -> {
                ctx.requireTurn()
                val u = ctx.args.firstOrNull()?.toIntOrNull()?.let { GameOps.unitById(ctx.civ, it) } ?: ctx.selectedUnit()
                val name = cleanName(ctx.value)
                val base = u.baseUnit.name
                u.instanceName = if (name.equals(base, ignoreCase = true)) null else name
                ctx.flash += if (u.instanceName == null) "✏️ The unit is a plain **$base** again." else "✏️ Your $base is now called **$name**."
                ctx.changed = true
            }
            "cp_undo" -> undo(ctx)
            else -> return false
        }
        return true
    }

    // ================================================================ self-test

    override fun selfTest(t: SelfTestCtx) {
        val a = t.alice
        fun ok(cond: Boolean, what: String) { if (!cond) throw IllegalStateException("CityPlus: $what"); println("  CityPlus ok: $what") }
        val st = t.ctl.state(t.s.meta.id, a.userId)
        val cityId = t.eng { g -> GameOps.civOf(g, a).cities.first().id }
        fun city(g: GameInfo) = GameOps.civOf(g, a).cities.first { it.id == cityId }
        fun firstChoice(prefix: String) = t.eng { g -> tileChoices(city(g), st).firstOrNull { it.id.startsWith(prefix) }?.id }

        // --- setup: a 5-pop city with a building that has specialist slots
        val specBuilding = t.eng { g ->
            val c = city(g)
            c.population.setPopulation(5)
            val b = g.ruleset.buildings.values.first { b ->
                b.newSpecialists().isNotEmpty() && b.isSellable() && b.uniqueTo == null && b.cost > 0 &&
                    b.newSpecialists().keys.all { it in g.ruleset.specialists } && !c.cityConstructions.isBuilt(b.name)
            }
            c.cityConstructions.addBuilding(b.name)
            c.hasSoldBuildingThisTurn = false
            c.manualSpecialists = false
            c.reassignPopulation(resetLocked = true)
            b.name
        }
        val specName = t.eng { g -> city(g).population.getMaxSpecialists().keys.first { it in g.ruleset.specialists } }

        // --- 👥 Citizens tab
        t.act(a, "tab", emptyList(), TAB, null)
        t.act(a, "cp_city", emptyList(), "city:$cityId", "citizens: pick city")
        t.render(a, "cityplus-citizens-before")
        val worked = firstChoice("s:")!!
        t.act(a, "cp_tile", emptyList(), worked, "citizens: stop working tile")
        ok(t.eng { g -> city(g).population.getFreePopulation() } >= 1, "stopping a tile leaves an unemployed citizen")
        val toWork = t.eng { g -> tileChoices(city(g), st).filter { it.id.startsWith("w:") }.let { w -> (w.firstOrNull { it.id.drop(2) != worked.drop(2) } ?: w.first()).id } }
        t.act(a, "cp_tile", emptyList(), toWork, "citizens: work tile (locked)")
        ok(t.eng { g -> val c = city(g); val tl = tileFrom(g, toWork); c.isWorked(tl) && c.lockedTiles.contains(tl.position) }, "worked tile is locked")
        t.act(a, "cp_tile", emptyList(), "u:" + toWork.drop(2), "citizens: unlock tile")
        t.act(a, "cp_tile", emptyList(), "l:" + toWork.drop(2), "citizens: lock tile")
        // unemployed rule: nobody free -> working another tile / adding a specialist is refused
        t.eng { g -> val c = city(g); while (c.population.getFreePopulation() > 0) c.workTile(c.tilesInRange.firstOrNull { !c.isWorked(it) && workable(c, it) } ?: break) }
        firstChoice("w:")?.let { ok(!t.act(a, "cp_tile", emptyList(), it, null), "can't work a tile without an unemployed citizen") }
        ok(!t.act(a, "cp_spec", emptyList(), "+:$specName", null), "can't add a specialist without an unemployed citizen")
        // specialists
        t.act(a, "cp_tile", emptyList(), firstChoice("s:")!!, null)
        t.act(a, "cp_spec", emptyList(), "+:$specName", "citizens: add specialist")
        ok(t.eng { g -> city(g).population.getNewSpecialists()[specName] == 1 && city(g).manualSpecialists }, "specialist assigned, manual mode on")
        t.render(a, "cityplus-citizens-after")
        t.eng { _ ->
            val r = Panel.render(t.s, a, st)
            println("  CityPlus panel: " + r.embed.fields.joinToString("\n    ") { "[${it.name}] ${it.value}" })
            for (row in r.rows) println("    row: " + row.components.joinToString(" | ") { c ->
                when (c) {
                    is net.dv8tion.jda.api.interactions.components.selections.StringSelectMenu -> "<${c.placeholder}> " + c.options.joinToString("; ") { "${it.emoji?.name ?: ""}${it.label} (${it.description})" }
                    is net.dv8tion.jda.api.interactions.components.buttons.Button -> "[${c.label}]"
                    else -> c.toString()
                }
            })
        }
        t.act(a, "cp_spec", emptyList(), "-:$specName", "citizens: remove specialist")
        t.act(a, "cp_avoid", emptyList(), null, "citizens: avoid growth toggle")
        t.act(a, "cp_avoid", emptyList(), null, null)
        t.act(a, "cp_manspec", emptyList(), null, "citizens: specialist mode toggle")
        t.act(a, "cp_reset", emptyList(), null, "citizens: reset to automatic")
        ok(t.eng { g -> val c = city(g); c.lockedTiles.isEmpty() && !c.manualSpecialists && c.population.getFreePopulation() == 0 }, "reset unlocks all and re-employs everyone")

        // --- sell a building (one per city per turn)
        val sellId = "cp:sell:$specBuilding"
        ok(t.eng { g -> cityChoices(city(g), st.window).any { it.id == sellId } }, "sell offered")
        val gold0 = t.eng { g -> GameOps.civOf(g, a).gold }
        t.act(a, "cact", emptyList(), sellId, "sell building")
        ok(t.eng { g -> !city(g).cityConstructions.isBuilt(specBuilding) && GameOps.civOf(g, a).gold > gold0 }, "building sold for gold")
        ok(t.eng { g -> cityChoices(city(g), st.window).none { it.id.startsWith("cp:sell:") } }, "no second sale offered this turn")
        t.eng { g -> city(g).cityConstructions.addBuilding(specBuilding) }
        ok(!t.act(a, "cact", emptyList(), sellId, null), "second sale in a turn refused")

        // --- rename city and unit
        ok(t.eng { g -> cityChoices(city(g), st.window).any { it.id == "ask:cp_rename_city:$cityId" } }, "rename city offered")
        t.act(a, "cp_rename_city", listOf(cityId), "  Nova   Roma ", "rename city")
        ok(t.eng { g -> city(g).name } == "Nova Roma", "city renamed and trimmed")
        ok(!t.act(a, "cp_rename_city", listOf(cityId), "   ", null), "empty city name refused")
        ok(!t.act(a, "cp_rename_city", listOf(cityId), "x".repeat(41), null), "41-char name refused")
        val unitId = t.eng { g -> GameOps.civOf(g, a).units.getCivUnits().first().id }
        ok(t.eng { g -> unitChoices(GameOps.unitById(GameOps.civOf(g, a), unitId)!!).any { it.id == "ask:cp_rename_unit:$unitId" } }, "rename unit offered")
        t.act(a, "cp_rename_unit", listOf(unitId.toString()), "Brutus", "rename unit")
        ok(t.eng { g -> GameOps.unitById(GameOps.civOf(g, a), unitId)?.instanceName } == "Brutus", "unit renamed")

        // --- worker queue: a forest next to the city with a worker on it
        val workerId = t.eng { g ->
            val civ = GameOps.civOf(g, a); val c = city(g)
            for (tech in listOf("Mining", "Agriculture", "Bronze Working")) if (tech in g.ruleset.technologies) civ.tech.addTechnology(tech)
            val tile = c.getCenterTile().neighbors.first { it.isLand && !it.isCityCenter() && it.getOwner() == civ && it.civilianUnit == null && !it.isImpassible() }
            tile.setBaseTerrain(g.ruleset.terrains["Grassland"]!!)
            tile.setTileResource(null as String?); tile.removeImprovement(); tile.stopWorkingOnImprovement()
            for (f in tile.terrainFeatures.toList()) tile.removeTerrainFeature(f)
            tile.addTerrainFeature("Forest"); tile.setTerrainTransients() // ArrayList-backed, so the game still serializes
            val w = civ.units.placeUnitNearTile(tile.position, "Worker")!!
            ok(w.getTile() == tile, "worker placed on the forest")
            w.id
        }
        fun worker(g: GameInfo) = GameOps.unitById(GameOps.civOf(g, a), workerId)!!
        t.act(a, "selunit", emptyList(), "u:$workerId", null)
        val rq = t.eng { g -> unitChoices(worker(g)).firstOrNull { it.id == "cp:rq:Farm" } }
        ok(rq != null, "'Remove Forest, then build Farm' offered")
        t.act(a, "uact", emptyList(), rq!!.id, "worker: clear terrain, then build")
        ok(t.eng { g -> queueNames(worker(g).getTile()) } == listOf("Remove Forest", "Farm"), "queue = Remove Forest, Farm")
        t.act(a, "selunit", emptyList(), "u:$workerId", null); t.act(a, "uact", emptyList(), "cp:unq", "worker: unqueue")
        ok(t.eng { g -> queueNames(worker(g).getTile()) } == listOf("Remove Forest"), "queue back to Remove Forest only")
        val qn = t.eng { g -> unitChoices(worker(g)).firstOrNull { it.id == "cp:qn:Farm" } }
        ok(qn != null, "'Queue next: Farm' offered while removing the forest")
        t.act(a, "selunit", emptyList(), "u:$workerId", null); t.act(a, "uact", emptyList(), qn!!.id, "worker: queue next improvement")
        ok(t.eng { g -> queueNames(worker(g).getTile()) } == listOf("Remove Forest", "Farm"), "Farm queued after Remove Forest")

        // --- undo (city menu and unit menu), only within the turn
        t.act(a, "cp_rename_city", listOf(cityId), "Undo Test", null)
        ok(t.eng { g -> cityChoices(city(g), st.window).first().id } == "do:cp_undo", "undo is first among CityPlus city choices")
        ok(t.eng { g -> unitChoices(worker(g)).first().id } == "do:cp_undo", "undo is first among CityPlus unit choices")
        t.act(a, "cact", emptyList(), "do:cp_undo", "undo (city actions)")
        ok(t.eng { g -> city(g).name } == "Nova Roma", "undo restored the city name")
        t.act(a, "selunit", emptyList(), "u:$workerId", null); t.act(a, "uact", emptyList(), "do:cp_undo", "undo (unit actions)")
        ok(t.eng { g -> queueNames(worker(g).getTile()) } == listOf("Remove Forest"), "second undo removed the queued Farm")
        ok(!t.act(t.bob, "cp_undo", emptyList(), null, null), "the other player can't undo")
        t.eng { _ -> onTurnEnded(t.s) }
        ok(t.eng { g -> validStack(g, GameOps.civOf(g, a)) == null }, "no undo entries after a turn ends")
        ok(!t.act(a, "cact", emptyList(), "do:cp_undo", null), "undo refused after the turn ended")
        t.act(a, "tab", emptyList(), TAB, null)
        t.render(a, "cityplus-citizens-final")
        st.flash.clear()
    }
}

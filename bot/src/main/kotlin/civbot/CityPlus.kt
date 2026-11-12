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
import com.unciv.logic.city.CityFlags
import com.unciv.logic.city.CityResources
import com.unciv.logic.city.GreatPersonPointsBreakdown
import com.unciv.models.ruleset.IConstruction
import com.unciv.models.ruleset.INonPerpetualConstruction
import com.unciv.models.ruleset.PerpetualConstruction
import com.unciv.models.ruleset.RejectionReason
import com.unciv.models.ruleset.unique.UniqueType
import com.unciv.models.ruleset.unit.BaseUnit
import com.unciv.view.GameView
import net.dv8tion.jda.api.interactions.components.ActionRow
import kotlin.math.ceil

/**
 * City management extras, mirroring Unciv's CityScreen / ImprovementPicker logic (headless):
 *  - 👥 Citizens tab: work / lock / stop working tiles, specialists, reset, avoid growth
 *  - Sell a building (city action), rename city / unit (popups)
 *  - Worker improvement queue ("Remove Forest, then Farm", "Queue next: …")
 *  - Undo of the current player's actions within their turn
 *  - 🏗️ Production tab: edit the build queue like Unciv's CityScreen (move, remove, add to top, all cities),
 *    buy any entry with any stat, disable/enable constructions, default promotions, "creates one improvement"
 *    tile picking, and a 📈 details view (borders, WLTK, resistance, resources, great people, religion,
 *    stat breakdown, why things can't be built)
 *  - Buy a tile by code / a whole ring of tiles (city actions)
 *  - 🏙️ Cities overview and 🏛️ Wonders tabs
 */
object CityPlus : Extension {
    override val viewVerbs = setOf("cp_city", "cp_pcity", "cp_pview", "cp_psel", "cp_stat", "cp_cant", "cp_ovpage", "cp_ovsort", "cp_ovcity", "cp_won")

    private const val TAB = "CITIZENS"
    private const val MAX_UNDO = 10
    private const val MAX_NAME = 40

    private const val PROD = "PRODUCTION"
    private const val CITYOV = "CITYOVERVIEW"
    private const val WONDERS = "WONDERS"

    override val tabs = listOf(
        ExtTab(TAB, "Citizens", "👥", "Work tiles, lock citizens, specialists"),
        ExtTab(PROD, "Production", "🏗️", "Build queue, buying, disable items, city details"),
        ExtTab(CITYOV, "Cities overview", "🏙️", "Every city's population, yields, production, defence"),
        ExtTab(WONDERS, "Wonders", "🏛️", "World and natural wonders: built, known, not built yet"),
    )

    override val prompts = mapOf(
        "cp_imptile" to Prompt("Place the improvement", "Tile code (one of the city's own tiles)", "e.g. D5"),
        "cp_buytile" to Prompt("Buy a tile", "Tile code from the map", "e.g. D5"),
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
        when (tabId) {
            PROD -> return renderProduction(t)
            CITYOV -> return renderOverview(t)
            WONDERS -> return renderWonders(t)
        }
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
        for (b in sellable(city))
            out += Choice("cp:sell:${b.name}", "Sell ${b.name} for ${city.getGoldForSellingBuilding(b.name)} gold",
                (if (b.maintenance > 0) "Saves ${b.maintenance} upkeep · " else "") + "one sale per city per turn", "💸")
        if (city.tilesInRange.any { city.expansion.canBuyTile(it) })
            out += Choice("ask:cp_buytile:${city.id}", "Buy a tile by code…", "Any free tile touching ${city.name}'s land", "🗺️")
        out += ringChoices(city)
        return out
    }

    override fun doCityChoice(city: City, id: String): String? = when {
        id.startsWith("cp:sell:") -> sell(city, id.removePrefix("cp:sell:"))
        id.startsWith("cp:ring:") -> buyRing(city, id.removePrefix("cp:ring:").toIntOrNull() ?: throw PlayerError("Unknown ring."))
        else -> null
    }

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
            "cp_pact" -> "production queue change$city"
            "cp_imptile" -> "improvement tile choice$city"
            "cp_buytile" -> "tile purchase$city"
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

    // ================================================================ shared bits for the new tabs

    private val buyEmoji = mapOf(Stat.Gold to "💰", Stat.Faith to "🙏", Stat.Food to "🍞", Stat.Science to "🔬", Stat.Culture to "🎭")

    /** Join lines into one embed field value, never longer than [max] (embeds are capped at 6000 chars in total). */
    private fun joinCapped(ls: List<String>, max: Int = 900, empty: String = "—"): String {
        val b = StringBuilder()
        for ((i, l) in ls.withIndex()) {
            if (b.length + l.length + 1 > max - 16) { b.append("\n… +${ls.size - i} more"); break }
            if (b.isNotEmpty()) b.append('\n')
            b.append(l)
        }
        return b.toString().ifEmpty { empty }
    }

    private fun cityView(city: City) = GameView(city.civ.gameInfo, city.civ).getCityView(city)
    private fun civView(civ: Civilization) = GameView(civ.gameInfo, civ).civView
    private fun need(ok: Boolean) { if (!ok) throw PlayerError("It's not your turn — you can look around, but not act.") }

    private fun kindEmoji(c: IConstruction) = when (c) {
        is BaseUnit -> "🪖"
        is Building -> if (c.isWonder || c.isNationalWonder) "🏛️" else "🏠"
        is PerpetualConstruction -> "♾️"
        else -> "•"
    }

    private fun currentCity(st: PanelState, civ: Civilization): City? {
        val cities = GameOps.citiesOf(civ)
        val city = cities.firstOrNull { it.id == st.cityId } ?: cities.firstOrNull()
        st.cityId = city?.id
        return city
    }

    private fun createsOne(city: City, c: IConstruction) = (c as? Building)?.getImprovementToCreate(city.getRuleset(), city.civ)

    // ================================================================ 🏗️ Production tab: queue editing (CityConstructionsTable + CityScreenConstructionMenu)

    /** The queue entry or construction picked in the Production tab. [idx] = queue position, -1 when picked from the build list. */
    private class Sel(val name: String, val idx: Int)

    private fun selection(st: PanelState, city: City): Sel? {
        val q = city.cityConstructions.constructionQueue
        val raw = (st.ext["cp_sel"] as? String)?.takeIf { it.substringBefore("|") == city.id }?.substringAfter("|")
        if (raw != null && raw.startsWith("q:")) raw.removePrefix("q:").toIntOrNull()?.takeIf { it in q.indices }?.let { return Sel(q[it], it) }
        if (raw != null && raw.startsWith("c:")) raw.removePrefix("c:").takeIf { n -> runCatching { GameOps.construction(city, n) }.isSuccess }?.let { return Sel(it, -1) }
        return q.firstOrNull()?.takeIf { it.isNotEmpty() }?.let { Sel(it, 0) }
    }

    private fun select(st: PanelState, city: City, v: String?) { if (v == null) st.ext.remove("cp_sel") else st.ext["cp_sel"] = "${city.id}|$v" }

    private fun queueChoices(city: City): List<Choice> {
        val cc = city.cityConstructions
        return cc.constructionQueue.mapIndexed { i, n ->
            val c = runCatching { GameOps.construction(city, n) }.getOrNull()
            val info = if (c == null || c is PerpetualConstruction) "perpetual" else "${cc.turnsToConstruction(n)} turns · ${cc.getWorkDone(n)} ⚙️ done"
            Choice("q:$i", "#${i + 1} $n" + (if (i == 0) " (building now)" else ""), info, c?.let { kindEmoji(it) })
        }
    }

    /** Buildable units, buildings and wonders (Unciv's "available constructions"), then perpetual ones. */
    private fun buildChoices(city: City): List<Choice> {
        val cc = city.cityConstructions
        val rs = city.getRuleset()
        val items = (rs.units.values.asSequence() + rs.buildings.values.asSequence())
            .filter { it.isBuildable(cc) }
            .map { it to cc.turnsToConstruction(it.name) }
            .sortedWith(compareBy({ it.first is Building }, { (it.first as? Building)?.isAnyWonder() == true }, { it.second }))
            .map { (c, turns) ->
                val tags = listOfNotNull(
                    "$turns turns",
                    "🚫 disabled".takeIf { c.name in city.disabledConstructions },
                    createsOne(city, c)?.let { "creates a ${it.name}" },
                    "queued".takeIf { c.name in cc.constructionQueue },
                )
                Choice("c:${c.name}", c.name, tags.joinToString(" · "), kindEmoji(c))
            }.toList()
        return items + GameOps.perpetualChoices(city).map { it.copy(id = "c:" + it.id.removePrefix("prod:")) }
    }

    private fun candidateCities(civ: Civilization) = civ.cities.filter { !it.isPuppet && !it.isInResistance() && !it.isBeingRazed }
    /** CityScreenConstructionMenu.allCitiesEntryValid: more than one city, not a wonder, and some city qualifies */
    private fun allCitiesValid(civ: Civilization, c: IConstruction, pred: (City) -> Boolean) =
        civ.cities.size > 1 && (c as? Building)?.isAnyWonder() != true && candidateCities(civ).any(pred)

    private fun buyOptions(city: City, c: INonPerpetualConstruction, idx: Int): List<Choice> =
        Stat.statsUsableToBuy.filter { it != Stat.Faith || city.civ.gameInfo.isReligionEnabled() }.mapNotNull { stat ->
            if (!c.canBePurchasedWithStat(city, stat)) return@mapNotNull null
            val cost = c.getStatBuyCost(city, stat) ?: return@mapNotNull null
            if (!city.cityConstructions.isConstructionPurchaseAllowed(c, stat, cost)) return@mapNotNull null
            Choice("buy:${stat.name}~$idx~${c.name}", "Buy for $cost ${stat.name.lowercase()}" + (if (idx >= 0) " (queue #${idx + 1})" else ""),
                "You have ${city.getStatReserve(stat)} ${stat.name.lowercase()}", buyEmoji[stat])
        }

    private fun actionChoices(city: City, sel: Sel?): List<Choice> {
        if (sel == null) return emptyList()
        val civ = city.civ
        val cc = city.cityConstructions
        val q = cc.constructionQueue
        val c = runCatching { GameOps.construction(city, sel.name) }.getOrNull() ?: return emptyList()
        val perpetual = c is PerpetualConstruction
        val createOne = createsOne(city, c) != null
        val canEdit = !city.isPuppet
        val out = ArrayList<Choice>()
        if (sel.idx >= 0 && canEdit) {
            val withoutPerpetual = q.count { it !in PerpetualConstruction.perpetualConstructionsMap }
            if (q.size > 1 && sel.idx > 0) out += Choice("up", "Move up", "#${sel.idx + 1} → #${sel.idx}", "⬆️")
            if (q.size > 1 && sel.idx < q.lastIndex) out += Choice("down", "Move down", "#${sel.idx + 1} → #${sel.idx + 2}", "⬇️")
            if (!perpetual && sel.idx > 0) out += Choice("top", "Move to the top of the queue", "Builds it next", "⏫")
            if (!perpetual && sel.idx in 0 until withoutPerpetual - 1) out += Choice("end", "Move to the end of the queue", "", "⏬")
            out += Choice("rm", "Remove from the queue", if (q.size == 1) "The city will idle" else "", "✖️")
        }
        if (canEdit && cc.canAddToQueue(c) && !(perpetual && sel.name in q)) {
            out += Choice("add", if (createOne) "Add to the queue (best tile for its improvement)" else "Add to the queue", "At the end (before perpetual ones)", "➕")
            if (createOne) out += Choice("ask:cp_imptile:${sel.name}", "Add to the queue — choose the improvement's tile…", "Type a tile code from the map", "📍")
            else if (!perpetual) out += Choice("addtop", "Add to the top of the queue", "Builds it next", "🔝")
        }
        if (c is INonPerpetualConstruction) out += buyOptions(city, c, sel.idx)
        if (canEdit) {
            if (allCitiesValid(civ, c) { val qq = it.cityConstructions.constructionQueue
                    it.cityConstructions.canAddToQueue(c, qq) && !createOne && !(perpetual && sel.name in qq) })
                out += Choice("alladd", "Add to the queue in all cities", "Not puppets, razing or resisting cities", "🏙️")
            if (!perpetual && allCitiesValid(civ, c) { val qq = it.cityConstructions.constructionQueue
                    (it.cityConstructions.canAddToQueue(c, qq) && !createOne) || qq.indexOf(sel.name) > 0 })
                out += Choice("alltop", "Add or move to the top in all cities", "", "🔝")
            if (allCitiesValid(civ, c) { sel.name in it.cityConstructions.constructionQueue })
                out += Choice("allrm", "Remove from the queue in all cities", "", "🧹")
            if (sel.name !in city.disabledConstructions && c != PerpetualConstruction.Idle)
                out += Choice("dis", "Disable in this city", "The governor and auto-pick won't choose it here", "🚫")
            if (sel.name !in civ.disabledCityConstructions && c != PerpetualConstruction.Idle)
                out += Choice("disall", "Disable in all cities", "Also in cities you found later", "⛔")
            if (sel.name in city.disabledConstructions) out += Choice("en", "Enable in this city", "", "✅")
            if (sel.name in civ.disabledCityConstructions) out += Choice("enall", "Enable in all cities", "", "✅")
            if (c is BaseUnit) city.unitShouldUseSavedPromotion[c.name]?.let { on ->
                val promos = city.unitToPromotions[c.name]?.promotions?.joinToString().orEmpty()
                out += Choice("promo", "Use default promotions: ${if (on) "on → turn off" else "off → turn on"}", promos.ifEmpty { "Saved promotions for new ${c.name}s" }, "⭐")
            }
        }
        return out
    }

    /** Unciv's most important rejection reason, like the red text on the construction button. */
    private fun mainRejection(city: City, c: INonPerpetualConstruction): RejectionReason? {
        val rr = c.getRejectionReasons(city.cityConstructions, city.cityConstructions.constructionQueue).toList()
        return rr.filter { it.isImportantRejection() }.minByOrNull { it.getRejectionPrecedence() } ?: rr.firstOrNull { it.shouldShow } ?: rr.firstOrNull()
    }

    private fun selectedText(city: City, sel: Sel, st: PanelState?): String {
        val cc = city.cityConstructions
        val c = runCatching { GameOps.construction(city, sel.name) }.getOrNull() ?: return "Unknown construction."
        val lines = ArrayList<String>()
        lines += (if (sel.idx >= 0) "Queue #${sel.idx + 1}" else "Not in the queue") + " · " + when (c) {
            is BaseUnit -> "unit"; is Building -> if (c.isWonder) "world wonder" else if (c.isNationalWonder) "national wonder" else "building"; else -> "perpetual"
        }
        if (c is PerpetualConstruction) lines += GameOps.clean(c.description).replace("rate", (c as? PerpetualConstruction.StatConversion)?.getConversionRate(city)?.toString() ?: "rate")
        if (c is INonPerpetualConstruction) {
            lines += "⚙️ ${cc.getWorkDone(c.name)}/${c.getProductionCost(city.civ, city)} production · ${cc.turnsToConstruction(c.name)} turns"
            val prices = Stat.statsUsableToBuy.filter { c.canBePurchasedWithStat(city, it) }.mapNotNull { st ->
                c.getStatBuyCost(city, st)?.let { "${buyEmoji[st]} $it" + if (!cc.isConstructionPurchaseAllowed(c, st, it)) " (not now)" else "" }
            }
            lines += "Buy: " + prices.joinToString(" · ").ifEmpty { "can't be bought" } +
                (if (cc.isConstructionPurchaseBlockedByUnit(c)) " — move the unit out of the city first" else "")
            if (!c.isBuildable(cc) && sel.name !in cc.constructionQueue) mainRejection(city, c)?.let { lines += "🚫 " + GameOps.clean(it.errorMessage) }
        }
        createsOne(city, c)?.let { imp ->
            val t = cc.getTileForImprovement(imp.name)
            lines += "📍 Creates a ${imp.name}" + (t?.let { " on ${code(st, it)}" } ?: " on a tile you choose")
        }
        if (sel.name in city.disabledConstructions) lines += "🚫 Disabled in this city" + (if (sel.name in city.civ.disabledCityConstructions) " (and all cities)" else "")
        if (c is BaseUnit) city.unitShouldUseSavedPromotion[c.name]?.let { lines += "⭐ Default promotions: ${if (it) "on" else "off"}" }
        return joinCapped(lines, 600)
    }

    private fun renderProduction(t: TabCtx) {
        val civ = t.civ
        val st = t.st
        val city = currentCity(st, civ)
        if (city == null) {
            t.eb.addField("🏗️ Production", "No cities yet — found one with a Settler in ⚔️ Units.", false)
            t.mapAround(GameOps.focusTile(civ)); return
        }
        val details = st.ext["cp_details"] == true
        val cities = GameOps.citiesOf(civ)
        if (details) renderDetails(t, city)
        else {
            val cc = city.cityConstructions
            val sel = selection(st, city)
            val selC = sel?.let { runCatching { GameOps.construction(city, it.name) }.getOrNull() }
            val marks = selC?.let { c -> createsOne(city, c) }?.let { imp -> city.getTiles().filter { cc.canPlaceCreateOneImprovementOn(imp, it) }.toSet() }.orEmpty()
            t.mapAround(city.getCenterTile(), Overlay(selectedTile = city.getCenterTile(), reachable = marks))
            val q = cc.constructionQueue
            val qLines = q.mapIndexed { i, n ->
                val c = runCatching { GameOps.construction(city, n) }.getOrNull()
                val info = if (c == null || c is PerpetualConstruction) "♾️" else "${cc.turnsToConstruction(n)} turns"
                "${if (sel?.idx == i) "👉" else "`${i + 1}.`"} ${c?.let { kindEmoji(it) } ?: ""} **$n** — $info"
            }
            t.eb.addField("🏗️ ${city.name} — ⚙️ +${city.cityStats.currentCityStats.production.toInt()} production" + (if (city.isPuppet) " (puppet)" else ""),
                joinCapped(qLines + listOfNotNull(
                    city.disabledConstructions.takeIf { it.isNotEmpty() }?.let { "🚫 Disabled here: " + it.sorted().joinToString() },
                    "_Puppets choose their own production — annex the city to edit the queue._".takeIf { city.isPuppet },
                ), 700, "Nothing queued — pick something below."), false)
            if (sel != null) t.eb.addField("🔎 ${sel.name}", selectedText(city, sel, st), false)
            val canEdit = t.myTurn
            t.rows += ActionRow.of(t.select("cp_psel", "📋 Queue (${q.size}) — pick an entry…", queueChoices(city), sel?.takeIf { it.idx >= 0 }?.let { "q:${it.idx}" }, true, "q"))
            t.rows += ActionRow.of(t.select("cp_psel", "🏗️ Pick something to build…", buildChoices(city), sel?.takeIf { it.idx < 0 }?.let { "c:${it.name}" }, true, "b"))
            val acts = actionChoices(city, sel)
            t.rows += ActionRow.of(t.select("cp_pact", if (sel == null) "Pick a queue entry or construction first" else "Do with ${sel.name}…", acts, null, canEdit && acts.isNotEmpty()))
        }
        t.endRow += t.button("cp_pcity", "Prev city", "◀️", 2, cities.size > 1, "-1")
        t.endRow += t.button("cp_pcity", "Next city", "▶️", 2, cities.size > 1, "1")
        t.endRow += t.button("cp_pview", if (details) "Queue" else "Details", if (details) "🏗️" else "📈", 1)
    }

    private fun doProductionAction(ctx: ActionCtx): String {
        ctx.requireTurn()
        val city = ctx.selectedCity()
        val v = ctx.value ?: throw PlayerError("Pick an action.")
        val sel = selection(ctx.st, city) ?: throw PlayerError("Pick a queue entry or construction first.")
        if (v.startsWith("buy:")) { val m = GameOps.buy(city, v.removePrefix("buy:")); select(ctx.st, city, null); return m }
        if (city.isPuppet) throw PlayerError("${city.name} is a puppet — it chooses its own production. Annex it first.")
        val valid = actionChoices(city, sel).map { it.id }
        if (v !in valid) throw PlayerError("That isn't possible for ${sel.name} any more.")
        val civ = ctx.civ
        val cv = cityView(city)
        val cc = city.cityConstructions
        val c = GameOps.construction(city, sel.name)
        fun idxOf(name: String, top: Boolean) = cc.constructionQueue.let { if (top) it.indexOf(name) else it.lastIndexOf(name) }
        val msg = when (v) {
            "up" -> { val i = cv.tryRaisePriority(sel.idx) ?: -1; need(i >= 0); select(ctx.st, city, "q:$i"); "⬆️ ${sel.name} moved up to #${i + 1}." }
            "down" -> { val i = cv.tryLowerPriority(sel.idx) ?: -1; need(i >= 0); select(ctx.st, city, "q:$i"); "⬇️ ${sel.name} moved down to #${i + 1}." }
            "top" -> { need(cv.tryMoveEntryToTop(sel.idx)); select(ctx.st, city, "q:0"); "⏫ ${city.name} builds **${sel.name}** next." }
            "end" -> { need(cv.tryMoveEntryToEnd(sel.idx)); select(ctx.st, city, "q:${idxOf(sel.name, false)}"); "⏬ ${sel.name} moved to the end of the queue." }
            "rm" -> { need(cv.tryRemoveFromQueue(sel.idx, false)); select(ctx.st, city, null); "✖️ Removed **${sel.name}** from ${city.name}'s queue." }
            "add" -> { val m = GameOps.queueConstruction(city, sel.name); select(ctx.st, city, "q:${idxOf(sel.name, false)}"); m }
            "addtop" -> { val m = GameOps.queueConstruction(city, sel.name, addToTop = true); select(ctx.st, city, "q:${idxOf(sel.name, true)}"); m }
            "alladd" -> {
                var n = 0
                for (other in candidateCities(civ)) { val before = other.cityConstructions.constructionQueue.size; cityView(other).tryAddToQueueConstruction(c); if (other.cityConstructions.constructionQueue.size != before || sel.name in other.cityConstructions.constructionQueue) n++ }
                "🏙️ **${sel.name}** is queued in $n cities."
            }
            "alltop" -> {
                for (other in candidateCities(civ)) {
                    val ov = cityView(other); val i = other.cityConstructions.constructionQueue.indexOf(sel.name)
                    if (i > 0) ov.tryMoveEntryToTop(i) else ov.tryAddToQueueConstruction(c, addToTop = true)
                }
                "🔝 **${sel.name}** is now at the top of the queue in every city that can build it."
            }
            "allrm" -> { for (other in candidateCities(civ)) cityView(other).tryRemoveAllByName(sel.name); select(ctx.st, city, null); "🧹 Removed **${sel.name}** from every queue." }
            "dis" -> { need(cv.tryDisableConstruction(sel.name)); "🚫 ${city.name} won't pick **${sel.name}** automatically any more." }
            "en" -> { need(cv.tryEnableConstruction(sel.name)); "✅ **${sel.name}** is enabled again in ${city.name}." }
            "disall" -> { need(civView(civ).tryDisableCivConstruction(sel.name)); "⛔ **${sel.name}** is disabled in all your cities (and new ones)." }
            "enall" -> { need(civView(civ).tryEnableCivConstruction(sel.name)); "✅ **${sel.name}** is enabled again in all cities." }
            "promo" -> {
                val on = city.unitShouldUseSavedPromotion[sel.name] != true
                need(cv.trySetUnitShouldUseSavedPromotion(sel.name, on))
                "⭐ New ${sel.name}s from ${city.name} ${if (on) "get" else "no longer get"} the saved default promotions."
            }
            else -> throw PlayerError("Unknown action.")
        }
        // Unciv re-plans the citizens after construction menu actions (the current build affects the focus)
        if (v != "promo") cv.tryReassignPopulation()
        return msg
    }

    // ================================================================ 📈 City details (CityStatsTable, GreatPersonPointsBreakdownPopup, CityReligionInfoTable, DetailedStatsPopup)

    private fun growthAndBorders(city: City): List<String> {
        val pop = city.population
        val s = city.cityStats.currentCityStats
        val out = ArrayList<String>()
        val food = city.foodForNextTurn()
        out += "🍞 " + when {
            food < 0 -> "Starving — loses a citizen in ${pop.getNumTurnsToStarvation()} turns"
            food > 0 -> "Grows in ${pop.getNumTurnsToNewPopulation()} turns"
            else -> "Not growing"
        } + " (${pop.foodStored}/${pop.getFoodToNextPopulation()} food)"
        val ex = city.expansion
        val choosable = ex.getChoosableTiles().any()
        out += "🗺️ " + (if (s.culture > 0 && choosable) {
            val turns = ceil((ex.getCultureToNextTile() - ex.cultureStored) / s.culture).toInt().coerceAtLeast(1)
            "Borders grow in **$turns turns**"
        } else "Borders stopped growing") + (if (choosable) " (${ex.cultureStored}/${ex.getCultureToNextTile()} culture)" else "")
        out += when {
            city.isWeLoveTheKingDayActive() -> "🎉 **We Love the King Day** for ${city.getFlag(CityFlags.WeLoveTheKing)} more turns"
            city.demandedResource.isNotEmpty() -> "👑 Demands **${city.demandedResource}** — get it for We Love the King Day"
            else -> "👑 No luxury demanded right now"
        }
        if (city.isInResistance()) out += "✊ **In resistance** for ${city.getFlag(CityFlags.Resistance)} more turns (nothing can be bought)"
        out += "❤️ ${city.health}/${cityView(city).getMaxHealth()} HP · 🛡️ ${city.getStrength().toInt()} defence" +
            (if (city.isPuppet) " · puppet" else "") + (if (city.isBeingRazed) " · 🔥 being razed" else "")
        return out
    }

    private fun localResources(city: City): List<String> {
        val byName = LinkedHashMap<String, Pair<Int, MutableSet<String>>>()
        val cityWide = HashSet<String>()
        for (sup in CityResources.getCityResourcesAvailableToCity(city)) {
            if (sup.resource.getMatchingUniques(UniqueType.NotShownOnWorldScreen, city.state).any()) continue
            val (n, origins) = byName.getOrPut(sup.resource.name) { 0 to HashSet() }
            byName[sup.resource.name] = (n + sup.amount) to origins.apply { add(GameOps.clean(sup.origin)) }
            if (sup.resource.isCityWide) cityWide += sup.resource.name
        }
        val out = byName.filter { it.value.first != 0 }.map { (name, v) ->
            "${if (name in cityWide) "🏙️" else "💎"} $name ${v.first}" + " (${v.second.joinToString()})"
        }.toMutableList()
        for ((name, amount) in city.resourceStockpiles) if (amount != 0) out += "📦 $name stockpile: $amount"
        return out
    }

    private fun greatPeople(city: City): List<String> {
        val b = GreatPersonPointsBreakdown(city)
        val gp = city.civ.greatPeople
        val out = b.sum().entries.sortedByDescending { it.value }.map { (name, pts) ->
            "**$name** +$pts/turn (empire: ${gp.greatPersonPointsCounter[name]}/${gp.pointsForNextGreatPersonCounter[name]})"
        }.toMutableList()
        for (e in b.basePoints) if (e.counter.isNotEmpty()) out += "• ${GameOps.clean(e.source)}: " + e.counter.entries.joinToString { "${it.key} +${it.value}" }
        for (e in b.percentBonuses) if (e.counter.isNotEmpty())
            out += "• ${GameOps.clean(e.source)}: " + if (e.isAllGP) "+${e.counter.values.first()}% all" else e.counter.entries.joinToString { "${it.key} +${it.value}%" }
        return out
    }

    private fun religionLines(city: City): List<String> {
        val game = city.civ.gameInfo
        if (!game.isReligionEnabled()) return listOf("Religion is off in this game.")
        fun disp(n: String) = game.religions[n]?.getReligionDisplayName() ?: n
        val r = city.religion
        val out = ArrayList<String>()
        out += "Majority: **${r.getMajorityReligion()?.getReligionDisplayName() ?: "none"}**"
        r.religionThisIsTheHolyCityOf?.let { out += (if (r.isBlockedHolyCity) "🕍 Former holy city of " else "🕍 Holy city of ") + "**${disp(it)}**" }
        val followers = r.getNumberOfFollowers()
        val pressure = r.getPressures()
        val future = r.getPressuresFromSurroundingCities()
        for (name in (followers.keys + pressure.keys).distinct().sortedByDescending { followers[it] }) {
            if (name == com.unciv.Constants.noReligionName && followers[name] == 0) continue
            out += "• ${if (name == com.unciv.Constants.noReligionName) "No religion" else disp(name)}: ${followers[name]} followers · ${pressure[name]} pressure" +
                (future[name]?.takeIf { it > 0 }?.let { " (+$it/turn from nearby cities)" } ?: "")
        }
        return out
    }

    private val breakdownStats = listOf(Stat.Food, Stat.Production, Stat.Gold, Stat.Science, Stat.Culture, Stat.Faith, Stat.Happiness)

    private fun statBreakdown(city: City, stat: Stat): List<String> {
        val cs = city.cityStats
        val out = ArrayList<String>()
        if (stat == Stat.Happiness) {
            for ((src, v) in cs.happinessList) if (v != 0f) out += "• ${GameOps.clean(src)}: ${signed(v)}"
            out += "**Total: ${signed(cs.happinessList.values.sum())}**"
            return out
        }
        for ((src, node) in cs.baseStatTree.children) {
            val v = node.totalStats[stat]; if (v == 0f) continue
            val sub = node.children.entries.filter { it.value.totalStats[stat] != 0f }.sortedByDescending { it.value.totalStats[stat] }
                .take(4).joinToString { "${GameOps.clean(it.key)} ${signed(it.value.totalStats[stat])}" }
            out += "• ${GameOps.clean(src)}: ${signed(v)}" + (if (sub.isNotEmpty()) " ($sub)" else "")
        }
        for ((src, node) in cs.statPercentBonusTree.children) {
            val v = node.totalStats[stat]; if (v != 0f) out += "• ${GameOps.clean(src)}: ${signed(v)}%"
        }
        for ((src, st) in cs.finalStatList) if (src !in cs.baseStatTree.children && st[stat] < 0f) out += "• ${GameOps.clean(src)}: ${signed(st[stat])}"
        out += "**Total: ${signed(cs.currentCityStats[stat])} per turn**"
        return out
    }

    /** Constructions Unciv shows in red (displayed but not buildable), with the most important rejection reason. */
    private fun unbuildable(city: City): List<Pair<INonPerpetualConstruction, String>> {
        val cc = city.cityConstructions
        val q = cc.constructionQueue
        val rs = city.getRuleset()
        return (rs.units.values.asSequence() + rs.buildings.values.asSequence())
            .filter { it.shouldBeDisplayed(cc, q) && !it.isBuildable(cc) }
            .map { c -> c to (mainRejection(city, c)?.errorMessage?.let { GameOps.clean(it) } ?: "Can't be built") }
            .sortedBy { it.first.name }.toList()
    }

    private fun renderDetails(t: TabCtx, city: City) {
        t.mapAround(city.getCenterTile(), Overlay(selectedTile = city.getCenterTile()))
        val stat = (t.st.ext["cp_stat"] as? String)?.let { Stat.safeValueOf(it) } ?: Stat.Production
        t.eb.addField("📈 ${city.name} — growth, borders, mood", joinCapped(growthAndBorders(city), 600), false)
        t.eb.addField("💎 Resources in this city", joinCapped(localResources(city), 400, "None"), false)
        t.eb.addField("🌟 Great person points", joinCapped(greatPeople(city), 450, "None yet — specialists and wonders earn them."), false)
        t.eb.addField("⛪ Religion", joinCapped(religionLines(city), 450), false)
        t.eb.addField("📊 ${stat.name} breakdown", joinCapped(statBreakdown(city, stat), 600), false)
        val cities = GameOps.citiesOf(t.civ)
        t.rows += ActionRow.of(t.select("cp_city", "Select a city (${cities.size})",
            cities.map { c -> Choice("city:${c.id}", c.name, "pop ${c.population.population}", if (c.isCapital()) "⭐" else "🏙️") }, "city:${city.id}", true, "d"))
        t.rows += ActionRow.of(t.select("cp_stat", "Explain a stat…", breakdownStats.map { s ->
            Choice("s:${s.name}", "${s.name} breakdown", if (s == Stat.Happiness) "local happiness sources" else "${signed(city.cityStats.currentCityStats[s])} per turn", statEmoji[s])
        }, "s:${stat.name}"))
        val cant = unbuildable(city)
        t.rows += ActionRow.of(t.select("cp_cant", "🚫 Can't build yet (${cant.size}) — why?", cant.map { (c, why) ->
            Choice("x:${c.name}", c.name, why, kindEmoji(c))
        }))
    }

    // ================================================================ tiles: by code, whole rings

    /** Tiles of ring [d] around the city, in an order where each one touches the city's land when bought. */
    private fun ringPlan(city: City, d: Int): List<Tile> {
        val center = city.getCenterTile()
        val left = city.tilesInRange.filter { it.aerialDistanceTo(center) == d && it.getOwner() == null }.toMutableList()
        val owned = city.getTiles().toHashSet()
        val plan = ArrayList<Tile>()
        while (true) {
            val next = left.filter { t -> t.neighbors.any { it in owned } }
            if (next.isEmpty()) break
            for (t in next) { plan += t; owned += t; left -= t }
        }
        return plan
    }

    private fun ringCost(city: City, plan: List<Tile>) = plan.withIndex().sumOf { (i, t) -> city.expansion.getGoldCostOfTile(t, i) }

    private fun ringChoices(city: City): List<Choice> {
        if (city.isPuppet || city.isBeingRazed || city.isInResistance()) return emptyList()
        val max = city.tilesInRange.maxOfOrNull { it.aerialDistanceTo(city.getCenterTile()) } ?: return emptyList()
        return (1..max).mapNotNull { d ->
            val plan = ringPlan(city, d).takeIf { it.size >= 2 } ?: return@mapNotNull null
            val cost = ringCost(city, plan)
            Choice("cp:ring:$d", "Buy ring $d (${plan.size} tiles) — $cost gold", "Every free tile at distance $d · you have ${city.civ.gold} gold", "⭕")
        }
    }

    private fun buyRing(city: City, d: Int): String {
        val plan = ringPlan(city, d)
        if (plan.isEmpty()) throw PlayerError("No free tiles left in ring $d.")
        val cost = ringCost(city, plan)
        if (city.civ.gold < cost && !city.civ.gameInfo.gameParameters.godMode) throw PlayerError("Ring $d costs $cost gold — you have ${city.civ.gold}.")
        val cv = cityView(city)
        val gv = GameView(city.civ.gameInfo, city.civ)
        var n = 0
        for (t in plan) if (city.expansion.canBuyTile(t) && cv.tryBuyTile(gv.getTile(t))) n++
        return "⭕ ${city.name} bought $n tiles of ring $d for ${cost} gold."
    }

    private fun buyTileByCode(ctx: ActionCtx): String {
        ctx.requireTurn()
        val city = ctx.args.firstOrNull()?.let { id -> ctx.civ.cities.firstOrNull { it.id == id } } ?: ctx.selectedCity()
        val tile = ctx.tile(ctx.value?.trim().orEmpty().ifEmpty { throw PlayerError("Type a tile code, like D5.") })
        val c = code(ctx.st, tile)
        if (!city.expansion.canBuyTile(tile)) throw PlayerError(when {
            city.isPuppet -> "Puppet cities can't buy tiles."
            city.isInResistance() -> "${city.name} is in resistance."
            tile.getOwner() != null -> "$c already belongs to ${tile.getOwner()!!.civName}."
            tile !in city.tilesInRange -> "$c is too far from ${city.name}."
            else -> "$c must touch ${city.name}'s land."
        })
        val cost = city.expansion.getGoldCostOfTile(tile)
        if (city.civ.gold < cost && !city.civ.gameInfo.gameParameters.godMode) throw PlayerError("$c costs $cost gold — you have ${city.civ.gold}.")
        cityView(city).tryBuyTile(GameView(city.civ.gameInfo, city.civ).getTile(tile))
        return "🗺️ ${city.name} bought **$c** for $cost gold."
    }

    // ================================================================ 🏙️ Cities overview

    private const val OV_PAGE = 8
    private val sorts = linkedMapOf(
        "default" to "Capital first", "name" to "Name", "pop" to "Population", "prod" to "Production",
        "gold" to "Gold", "sci" to "Science", "cult" to "Culture", "def" to "Defence",
    )

    private fun sortedCities(civ: Civilization, sort: String): List<City> {
        val s = { c: City -> c.cityStats.currentCityStats }
        return when (sort) {
            "name" -> civ.cities.sortedBy { it.name }
            "pop" -> civ.cities.sortedByDescending { it.population.population }
            "prod" -> civ.cities.sortedByDescending { s(it).production }
            "gold" -> civ.cities.sortedByDescending { s(it).gold }
            "sci" -> civ.cities.sortedByDescending { s(it).science }
            "cult" -> civ.cities.sortedByDescending { s(it).culture }
            "def" -> civ.cities.sortedByDescending { it.getStrength() }
            else -> GameOps.citiesOf(civ)
        }
    }

    private fun cityLine(c: City): String {
        val s = c.cityStats.currentCityStats
        val cur = c.cityConstructions.currentConstructionName()
        val parts = ArrayList<String>()
        parts += "🍞${signed(s.food)} ⚙️${num(s.production)} 💰${signed(s.gold)} 🔬${num(s.science)} 🎭${num(s.culture)} 🙏${num(s.faith)}"
        parts += "🏗️ " + (if (cur.isEmpty()) "idle" else if (PerpetualConstruction.isNamePerpetual(cur)) cur else "$cur (${c.cityConstructions.turnsToConstruction(cur)}t)")
        parts += "🛡️ ${c.getStrength().toInt()} · ❤️ ${c.health}/${cityView(c).getMaxHealth()}"
        c.religion.getMajorityReligion()?.let { parts += "⛪ ${it.getReligionDisplayName()}" }
        when {
            c.isWeLoveTheKingDayActive() -> parts += "🎉 WLTK ${c.getFlag(CityFlags.WeLoveTheKing)}t"
            c.demandedResource.isNotEmpty() -> parts += "👑 wants ${c.demandedResource}"
        }
        if (c.isInResistance()) parts += "✊ resistance ${c.getFlag(CityFlags.Resistance)}t"
        return parts.joinToString(" · ")
    }

    private fun renderOverview(t: TabCtx) {
        val civ = t.civ
        val sort = t.st.ext["cp_ovsort"] as? String ?: "default"
        val cities = sortedCities(civ, sort)
        val pages = maxOf(1, (cities.size + OV_PAGE - 1) / OV_PAGE)
        val page = ((t.st.ext["cp_ovpage"] as? Int) ?: 0).coerceIn(0, pages - 1)
        t.st.ext["cp_ovpage"] = page
        val tot = cities.fold(Stats()) { acc, c -> acc.apply { add(c.cityStats.currentCityStats) } }
        t.eb.addField("🏙️ ${cities.size} cities · ${cities.sumOf { it.population.population }} citizens" + (if (pages > 1) " · page ${page + 1}/$pages" else ""),
            if (cities.isEmpty()) "No cities yet." else "Total: 🍞${signed(tot.food)} ⚙️${num(tot.production)} 💰${signed(tot.gold)} 🔬${num(tot.science)} 🎭${num(tot.culture)} 🙏${num(tot.faith)}" +
                " · sorted by ${sorts[sort]?.lowercase()}", false)
        for (c in cities.drop(page * OV_PAGE).take(OV_PAGE)) {
            val tags = listOfNotNull("puppet".takeIf { c.isPuppet }, "🔥 razing".takeIf { c.isBeingRazed })
            t.eb.addField("${if (c.isCapital()) "⭐" else "🏙️"} ${c.name} · pop ${c.population.population}" + (if (tags.isNotEmpty()) " (${tags.joinToString()})" else ""),
                cityLine(c).take(1000), false)
        }
        t.rows += ActionRow.of(t.select("cp_ovcity", "Open a city in 🏗️ Production…", cities.map { c ->
            Choice("city:${c.id}", c.name, "pop ${c.population.population} · ⚙️${num(c.cityStats.currentCityStats.production)} · ${c.cityConstructions.currentConstructionName().ifEmpty { "idle" }}", if (c.isCapital()) "⭐" else "🏙️")
        }))
        t.rows += ActionRow.of(t.select("cp_ovsort", "Sort by…", sorts.map { (k, v) -> Choice("sort:$k", v, "", "↕️") }, "sort:$sort"))
        t.endRow += t.button("cp_ovpage", "Previous", "◀️", 2, page > 0, "-1")
        t.endRow += t.button("cp_ovpage", "Next", "▶️", 2, page < pages - 1, "1")
        t.includeAdvisor = false
        t.worldMap()
    }

    // ================================================================ 🏛️ Wonders (WonderInfo.collectInfo, headless)

    private enum class WStatus(val label: String) { Hidden(""), NotFound("Not found"), Unbuilt("Not built"), Known("Known"), Owned("Owned") }
    private class WInfo(val name: String, val natural: Boolean, val era: String, val status: WStatus, val civ: Civilization?, val city: City?, val tile: Tile?)

    private fun wonderInfo(viewer: Civilization): List<WInfo> {
        val game = viewer.gameInfo
        val rs = game.ruleset
        val world = rs.buildings.values.filter { it.isWonder }
            .sortedWith(compareBy<Building>({ it.era(rs)?.eraNumber ?: 100 }, { it.name }))
        val built = HashMap<String, City>()
        for (city in game.getCities()) for (b in city.cityConstructions.getBuiltBuildings()) if (b.isWonder) built[b.name] = city
        val out = ArrayList<WInfo>()
        for (w in world) {
            val era = w.era(rs)
            val city = built[w.name]
            val status = when {
                city != null && city.civ == viewer -> WStatus.Owned
                city != null && viewer.hasExplored(city.getCenterTile()) -> WStatus.Known
                city != null -> WStatus.NotFound
                !w.isHiddenFromCivilopedia(game) && (era == null || era.eraNumber <= viewer.getEraNumber()) -> WStatus.Unbuilt
                else -> WStatus.Hidden
            }
            out += WInfo(w.name, false, era?.name ?: "Other", status, city?.civ, city, city?.getCenterTile())
        }
        val naturals = game.tileMap.values.filter { it.isNaturalWonder() }.associateBy { it.naturalWonder!! }
        for ((name, tile) in naturals.toSortedMap()) {
            val owner = tile.getOwner()
            val status = when {
                owner == viewer -> WStatus.Owned
                name in viewer.naturalWonders -> WStatus.Known
                else -> WStatus.NotFound
            }
            if (status == WStatus.NotFound && game.civilizations.none { c -> c.questManager.getAssignedQuestsFor(viewer.civID)
                    .any { it.questName == com.unciv.models.ruleset.QuestName.FindNaturalWonder.value && it.data1 == name } }) continue
            val city = if (status == WStatus.NotFound) null else game.getCities()
                .filter { it.getCenterTile().aerialDistanceTo(tile) <= 5 && viewer.knows(it.civ) && viewer.hasExplored(it.getCenterTile()) }
                .minByOrNull { it.getCenterTile().aerialDistanceTo(tile) }
            out += WInfo(name, true, "Natural Wonders", status, owner, city, tile)
        }
        return out
    }

    private fun wonderWhere(w: WInfo, viewer: Civilization): String = when {
        w.status <= WStatus.NotFound -> if (w.natural) "somewhere unexplored" else "in a city you haven't found"
        w.tile == null -> ""
        w.tile.isCityCenter() -> "${w.tile.getCity()!!.name} (${w.civ?.civName ?: "?"})"
        w.tile.getCity() != null -> "near ${w.tile.getCity()!!.name}"
        w.city != null -> "around ${w.city.name}"
        else -> "far away"
    }

    /** Your cities that could build [name] now, with turns. */
    private fun whoCanBuild(civ: Civilization, name: String): List<String> {
        val b = civ.gameInfo.ruleset.buildings[name] ?: return emptyList()
        return civ.cities.filter { !it.isPuppet && b.isBuildable(it.cityConstructions) }
            .map { "${it.name} (${it.cityConstructions.turnsToConstruction(name)}t)" }
    }

    private fun renderWonders(t: TabCtx) {
        val civ = t.civ
        val all = wonderInfo(civ)
        val world = all.filter { !it.natural }
        val mine = world.filter { it.status == WStatus.Owned }
        val others = world.filter { it.status == WStatus.Known || it.status == WStatus.NotFound }
        val unbuilt = world.filter { it.status == WStatus.Unbuilt }
        val hidden = world.count { it.status == WStatus.Hidden }
        t.eb.addField("🏛️ Your wonders (${mine.size})", joinCapped(mine.map { "**${it.name}** — ${it.city?.name}" }, 500, "None yet."), false)
        t.eb.addField("👀 Built by others (${others.size})", joinCapped(others.map {
            if (it.status == WStatus.Known) "**${it.name}** — ${it.civ?.civName} (${it.city?.name})" else "**${it.name}** — an unknown civilization"
        }, 500, "None that you know of."), false)
        t.eb.addField("🏗️ Not built yet (${unbuilt.size})" + (if (hidden > 0) " · $hidden more in later eras" else ""), joinCapped(unbuilt.groupBy { it.era }.map { (era, ws) ->
            "**$era**: " + ws.joinToString { w -> w.name + if (whoCanBuild(civ, w.name).isNotEmpty()) " ✅" else "" }
        } + "_✅ = one of your cities can build it now_", 600), false)
        val nat = all.filter { it.natural }
        t.eb.addField("🌋 Natural wonders (${nat.size})", joinCapped(nat.map { "**${it.name}** — ${it.status.label.lowercase()}" +
            wonderWhere(it, civ).takeIf { s -> s.isNotEmpty() && it.status > WStatus.NotFound }?.let { s -> ", $s" }.orEmpty() }, 400, "None discovered yet."), false)
        val selName = t.st.ext["cp_wonder"] as? String
        all.firstOrNull { it.name == selName && it.status != WStatus.Hidden }?.let { w ->
            val lines = ArrayList<String>()
            lines += "${w.status.label}${if (w.natural) " natural wonder" else " · ${w.era}"}" + wonderWhere(w, civ).takeIf { it.isNotEmpty() }?.let { " · $it" }.orEmpty()
            if (!w.natural) {
                val b = civ.gameInfo.ruleset.buildings[w.name]!!
                lines += GameOps.clean(b.uniques.joinToString("; ")).take(300).ifEmpty { "—" }
                if (w.status == WStatus.Unbuilt) {
                    val can = whoCanBuild(civ, w.name)
                    lines += if (can.isNotEmpty()) "✅ Can be built in: ${can.joinToString()}"
                    else currentCity(t.st, civ)?.let { c -> mainRejection(c, b)?.let { "🚫 ${c.name}: ${GameOps.clean(it.errorMessage)}" } } ?: "🚫 None of your cities can build it yet."
                }
            }
            t.eb.addField("🔎 ${w.name}", joinCapped(lines, 600), false)
        }
        t.rows += ActionRow.of(t.select("cp_won", "Look at a wonder (${all.count { it.status != WStatus.Hidden }})…",
            all.filter { it.status != WStatus.Hidden }.map { w ->
                Choice("w:${w.name}", w.name, (w.status.label + " · " + (if (w.status > WStatus.NotFound) wonderWhere(w, civ) else w.era)).take(100),
                    when (w.status) { WStatus.Owned -> "🏛️"; WStatus.Known -> "👀"; WStatus.Unbuilt -> "🏗️"; else -> "❔" })
            }, selName?.let { "w:$it" }))
        t.includeAdvisor = false
        t.worldMap()
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
            "cp_pcity" -> {
                val cs = GameOps.citiesOf(ctx.civ)
                if (cs.isNotEmpty()) {
                    val i = cs.indexOfFirst { it.id == ctx.st.cityId }.coerceAtLeast(0)
                    ctx.st.cityId = cs[Math.floorMod(i + (ctx.args.firstOrNull()?.toIntOrNull() ?: 1), cs.size)].id
                }
            }
            "cp_pview" -> ctx.st.ext["cp_details"] = ctx.st.ext["cp_details"] != true
            "cp_psel" -> { val city = ctx.selectedCity(); select(ctx.st, city, ctx.value?.takeIf { it.startsWith("q:") || it.startsWith("c:") }) }
            "cp_pact" -> { ctx.flash += doProductionAction(ctx); ctx.changed = true }
            "cp_imptile" -> {
                ctx.requireTurn()
                val city = ctx.selectedCity()
                val name = ctx.args.firstOrNull() ?: throw PlayerError("Pick the building first.")
                val tile = ctx.tile(ctx.value?.trim().orEmpty().ifEmpty { throw PlayerError("Type a tile code, like D5.") })
                ctx.flash += GameOps.queueConstruction(city, name, tile = tile).replace("(improvement tile marked)", "(improvement on ${code(ctx.st, tile)})")
                select(ctx.st, city, "q:${city.cityConstructions.constructionQueue.lastIndexOf(name)}")
                ctx.changed = true
            }
            "cp_buytile" -> { ctx.flash += buyTileByCode(ctx); ctx.changed = true }
            "cp_stat" -> ctx.st.ext["cp_stat"] = ctx.value?.removePrefix("s:") ?: "Production"
            "cp_cant" -> {
                val city = ctx.selectedCity()
                val name = ctx.value?.removePrefix("x:") ?: return true
                val c = GameOps.construction(city, name) as? INonPerpetualConstruction ?: return true
                val reasons = c.getRejectionReasons(city.cityConstructions).filter { it.shouldShow }.map { GameOps.clean(it.errorMessage) }.distinct().toList()
                ctx.flash += "🚫 **$name** in ${city.name}: " + reasons.ifEmpty { listOf("can't be built right now") }.joinToString("; ")
            }
            "cp_ovpage" -> ctx.st.ext["cp_ovpage"] = ((ctx.st.ext["cp_ovpage"] as? Int) ?: 0) + (ctx.args.firstOrNull()?.toIntOrNull() ?: 1)
            "cp_ovsort" -> { ctx.st.ext["cp_ovsort"] = ctx.value?.removePrefix("sort:") ?: "default"; ctx.st.ext["cp_ovpage"] = 0 }
            "cp_ovcity" -> { ctx.st.cityId = ctx.value?.removePrefix("city:"); ctx.st.tab = PROD; ctx.st.ext["cp_details"] = false }
            "cp_won" -> ctx.st.ext["cp_wonder"] = ctx.value?.removePrefix("w:").orEmpty()
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
        // the governor may already have filled the slots: start from no specialists and one unemployed citizen
        t.eng { g -> city(g).population.specialistAllocations.clear() }
        if (t.eng { g -> city(g).population.getFreePopulation() } == 0) t.act(a, "cp_tile", emptyList(), firstChoice("s:")!!, null)
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
        // Unciv's governor may leave a citizen unemployed when no job is worth anything; it must be the same as Unciv's own reset
        val freeAfterReset = t.eng { g -> city(g).population.getFreePopulation() }
        ok(t.eng { g -> val c = city(g); c.lockedTiles.isEmpty() && !c.manualSpecialists &&
            run { c.reassignAllPopulation(); freeAfterReset == c.population.getFreePopulation() } }, "reset unlocks all and re-employs everyone ($freeAfterReset unemployed)")

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
        selfTestProduction(t)
    }

    /** Production queue, buying, perpetual and "creates one improvement" constructions, disabling, focus, annex/raze
     *  rules, tile buying, city details and the two overview tabs. */
    private fun selfTestProduction(t: SelfTestCtx) {
        val a = t.alice
        fun dump(tag: String) = t.eng { _ ->
            val r = Panel.render(t.s, a, t.ctl.state(t.s.meta.id, a.userId))
            println("  CityPlus $tag (${r.embed.length} chars): " + r.embed.fields.joinToString("\n    ") { "[${it.name}] ${it.value}" })
            for (row in r.rows) println("    row: " + row.components.joinToString(" | ") { c ->
                when (c) {
                    is net.dv8tion.jda.api.interactions.components.selections.StringSelectMenu -> "<${c.placeholder}> " + c.options.take(8).joinToString("; ") { "${it.emoji?.name ?: ""}${it.label} (${it.description}) =${it.value}" } + if (c.options.size > 8) " …${c.options.size}" else ""
                    is net.dv8tion.jda.api.interactions.components.buttons.Button -> "[${c.label}]"
                    else -> c.toString()
                }
            })
        }
        fun ok(cond: Boolean, what: String) { if (!cond) throw IllegalStateException("CityPlus: $what"); println("  CityPlus ok: $what") }
        val st = t.ctl.state(t.s.meta.id, a.userId)
        fun civ(g: GameInfo) = GameOps.civOf(g, a)
        val cityId = t.eng { g -> civ(g).cities.first().id }
        fun city(g: GameInfo) = civ(g).cities.first { it.id == cityId }
        fun queue(id: String = cityId) = t.eng { g -> civ(g).cities.first { it.id == id }.cityConstructions.constructionQueue.toList() }
        fun acts() = t.eng { g -> actionChoices(city(g), selection(st, city(g))).map { it.id } }
        fun pick(v: String) = t.act(a, "cp_psel", listOf("b"), v, null)
        fun act(v: String, feature: String?) = t.act(a, "cp_pact", emptyList(), v, feature)
        fun resetQueue(c: City, vararg names: String) {
            val cc = c.cityConstructions
            while (cc.constructionQueue.size > 1) cc.removeFromQueue(cc.constructionQueue.lastIndex, false)
            cc.setCurrentConstruction(names[0]); for (n in names.drop(1)) cc.addToQueue(n)
        }
        fun tempUnique(g: GameInfo, text: String) = civ(g).temporaryUniques.add(com.unciv.models.ruleset.unique.TemporaryUnique().apply { unique = text; turnsLeft = 5 })
        fun dropUnique(g: GameInfo, text: String) = civ(g).temporaryUniques.removeAll { it.unique == text }

        // --- setup: classical techs, gold, faith, a bigger capital, a second city
        val city2Id = t.eng { g ->
            val c = civ(g)
            for (tech in g.ruleset.technologies.values.filter { it.era() in listOf("Ancient era", "Classical era") }) c.tech.addTechnology(tech.name, false)
            c.addGold(30000); c.religionManager.storedFaith += 5000
            val cap = city(g)
            cap.population.setPopulation(6); cap.reassignPopulation()
            val tile = cap.getCenterTile().getTilesInDistanceRange(4..14).sortedBy { it.aerialDistanceTo(cap.getCenterTile()) }
                .first { it.isLand && !it.isImpassible() && it.getUnits().none() && it.getOwner() == null && g.getCities().none { o -> o.getCenterTile().aerialDistanceTo(it) < 4 } }
            val id = c.addCity(tile.position).id
            c.cache.updateOurTiles(); c.cache.updateViewableTiles(); id
        }
        fun city2(g: GameInfo) = civ(g).cities.first { it.id == city2Id }
        // two ordinary buildings and a unit both cities can build
        val (b1, b2, unit) = t.eng { g ->
            val c = city(g); val c2 = city2(g)
            val bs = g.ruleset.buildings.values.filter { it.isBuildable(c.cityConstructions) && !it.isAnyWonder() && !it.hasCreateOneImprovementUnique() }.map { it.name }
            val u = g.ruleset.units.values.first { it.isBuildable(c.cityConstructions) && it.isBuildable(c2.cityConstructions) && it.isMilitary }.name
            Triple(bs[0], bs[1], u)
        }
        println("  CityPlus: testing with $b1, $b2, $unit")

        // --- 🏗️ Production tab: queue editing
        t.act(a, "selcity", emptyList(), "city:$cityId", null)
        t.act(a, "tab", emptyList(), PROD, "production tab")
        t.eng { g -> resetQueue(city(g), unit) }
        pick("c:$b1"); act("add", "production: add to the queue")
        ok(queue() == listOf(unit, b1), "added to the end")
        pick("c:$b2"); act("addtop", "production: add to the top")
        ok(queue() == listOf(b2, unit, b1), "added to the top")
        pick("c:$b1")
        ok("add" !in acts() && !act("add", null), "a queued building can't be queued twice (canAddToQueue)")
        t.act(a, "cp_psel", listOf("q"), "q:2", null); act("up", "production: move up")
        ok(queue() == listOf(b2, b1, unit), "moved up")
        act("down", "production: move down")
        ok(queue() == listOf(b2, unit, b1), "moved down")
        t.act(a, "cp_psel", listOf("q"), "q:2", null); act("top", "production: move to the top")
        ok(queue() == listOf(b1, b2, unit), "moved to the top")
        t.act(a, "cp_psel", listOf("q"), "q:0", null); act("end", "production: move to the end")
        ok(queue() == listOf(b2, unit, b1), "moved to the end")
        t.act(a, "cp_psel", listOf("q"), "q:1", null); act("rm", "production: remove one entry")
        ok(queue() == listOf(b2, b1), "entry removed")
        t.render(a, "cityplus-production"); dump("cityplus-production")
        // all cities
        pick("c:$unit"); act("alladd", "production: add in all cities")
        ok(unit in queue() && unit in queue(city2Id), "queued in both cities")
        pick("c:$unit"); act("alltop", "production: add/move to the top in all cities")
        ok(queue().first() == unit && queue(city2Id).first() == unit, "top of both queues")
        pick("c:$unit"); act("allrm", "production: remove from all cities")
        ok(unit !in queue() && unit !in queue(city2Id), "removed everywhere")
        // disable / enable, default promotions
        pick("c:$unit"); act("dis", "production: disable in this city")
        ok(t.eng { g -> unit in city(g).disabledConstructions }, "disabled here")
        act("en", "production: enable in this city")
        act("disall", "production: disable in all cities")
        ok(t.eng { g -> unit in civ(g).disabledCityConstructions && civ(g).cities.all { unit in it.disabledConstructions } }, "disabled everywhere")
        act("enall", "production: enable in all cities")
        ok(t.eng { g -> unit !in civ(g).disabledCityConstructions && civ(g).cities.none { unit in it.disabledConstructions } }, "enabled everywhere")
        t.eng { g -> city(g).unitShouldUseSavedPromotion[unit] = false }
        pick("c:$unit"); act("promo", "production: use default promotions toggle")
        ok(t.eng { g -> city(g).unitShouldUseSavedPromotion[unit] == true }, "default promotions on")

        // --- perpetual constructions (core list)
        val perp = t.eng { g -> GameOps.productionChoices(city(g)).filter { it.id.removePrefix("prod:") in PerpetualConstruction.perpetualConstructionsMap }.map { it.id }.toSet() }
        val expect = t.eng { g -> PerpetualConstruction.perpetualConstructionsMap.values.filter { it.isBuildable(city(g).cityConstructions) }.map { "prod:${it.name}" }.toSet() }
        ok(perp == expect && "prod:Nothing" in perp, "perpetual constructions offered exactly when buildable: $perp")
        t.act(a, "prod", emptyList(), "prod:Nothing", "perpetual: produce nothing")
        ok(queue().first() == "Nothing", "city idles")
        if ("prod:Gold" in perp) { t.act(a, "prod", emptyList(), "prod:Gold", "perpetual: convert production to gold"); ok(queue().first() == "Gold", "producing gold") }
        if ("prod:Science" in perp) t.act(a, "prod", emptyList(), "prod:Science", "perpetual: convert production to science")

        // --- buying: no caps, current + queued first, at the queue position, faith buildings, puppets
        t.eng { g -> resetQueue(city(g), b2, unit) }
        val buys = t.eng { g -> GameOps.buyChoices(city(g)) }
        // Current/queued items that can be bought come first (some may not be purchasable on a given map — Unciv's rules)
        fun inQueue(c: Choice) = c.label.contains("building now") || c.label.contains("queued #")
        val lastQueued = buys.indexOfLast { inQueue(it) }
        val firstOther = buys.indexOfFirst { !inQueue(it) }
        ok(buys.size > 9 && (lastQueued == -1 || firstOther == -1 || lastQueued < firstOther), "buy list uncapped (${buys.size}), current and queued items first")
        val units0 = t.eng { g -> civ(g).units.getCivUnitsSize() }
        // The queued unit / current building may not be purchasable on every random map (Unciv's rules) — test when they are
        val queuedBuy = buys.firstOrNull { it.label.contains("queued #2") }
        if (queuedBuy != null) {
            t.act(a, "cact", emptyList(), queuedBuy.id, "buy a queued item at its queue position")
            ok(queue() == listOf(b2) && t.eng { g -> civ(g).units.getCivUnitsSize() } == units0 + 1, "bought unit left the queue")
        } else println("CityPlus note: queued unit not purchasable on this map — skipped")
        t.act(a, "cp_psel", listOf("q"), "q:0", null)
        val buyCurrent = acts().firstOrNull { it.startsWith("buy:Gold~0~") }
        if (buyCurrent != null) {
            act(buyCurrent, "production tab: buy the current build")
            ok(t.eng { g -> city(g).cityConstructions.isBuilt(b2) }, "$b2 bought")
        } else println("CityPlus note: current build not purchasable on this map — skipped")
        // faith-bought building (a religion with the Pagodas belief) — on a copy of the game, restored afterwards
        val beforeReligion = t.eng { g -> Engine.serialize(g) }
        t.eng { g ->
            val c = civ(g); val rm = c.religionManager
            if (rm.religionState < com.unciv.logic.civilization.managers.ReligionState.Religion) {
                val name = g.ruleset.religions.first { it !in g.religions }
                val r = com.unciv.models.Religion(name, g, c, g.turns)
                rm.religion?.let { old -> r.addBeliefs(old.getAllBeliefsOrdered().asIterable()) }
                g.religions[name] = r; rm.religion = r
                rm.javaClass.getDeclaredField("religionState").apply { isAccessible = true }.set(rm, com.unciv.logic.civilization.managers.ReligionState.Religion)
                city(g).religion.religionThisIsTheHolyCityOf = name
            }
            rm.religion!!.addBelief("Pagodas")
            city(g).religion.addPressure(rm.religion!!.name, 5000)
            city(g).cityStats.update()
        }
        val pagoda = t.eng { g -> Features.faithBuyChoices(city(g)).firstOrNull { it.id.endsWith("~Pagoda") } }
        ok(pagoda != null, "Pagoda offered for faith")
        t.act(a, "cact", emptyList(), pagoda!!.id, "buy a building with faith")
        ok(t.eng { g -> city(g).cityConstructions.isBuilt("Pagoda") }, "Pagoda bought with faith")
        t.eng { _ -> val g2 = Engine.deserialize(beforeReligion); t.s.game = g2; com.unciv.UncivGame.Current.gameInfo = g2 }
        // puppets: only with "May buy items in puppet cities"
        t.eng { g -> city2(g).isPuppet = true }
        ok(t.eng { g -> GameOps.buyChoices(city2(g)).isEmpty() }, "nothing to buy in a puppet")
        t.eng { g -> tempUnique(g, "May buy items in puppet cities") }
        val pBuy = t.eng { g -> GameOps.buyChoices(city2(g)).firstOrNull() }
        ok(pBuy != null, "puppet buying allowed by the unique")
        t.act(a, "selcity", emptyList(), "city:$city2Id", null)
        t.act(a, "cact", emptyList(), pBuy!!.id, "buy in a puppet (May buy items in puppet cities)")
        t.eng { g -> dropUnique(g, "May buy items in puppet cities") }

        // --- annex / raze rules (May not annex cities), One-City Challenge destroy
        t.eng { g -> tempUnique(g, "May not annex cities") }
        ok(t.eng { g -> Features.cityExtraChoices(city2(g), null).none { it.id == "annex" || it.id == "raze" } }, "no annex/raze in the city menu when annexing isn't allowed")
        ok(!t.act(a, "cact", emptyList(), "annex", null), "annex refused")
        val conq = t.eng { g -> GameOps.conquestChoices(civ(g), city2(g)).map { it.id } }
        ok("annex" !in conq && "raze" in conq, "conquest: raze yes, annex no ($conq)")
        t.act(a, "conq", listOf(city2Id), "raze", "conquest: raze without annexing (May not annex cities)")
        ok(t.eng { g -> city2(g).isPuppet && city2(g).isBeingRazed }, "razed city stays a puppet")
        t.act(a, "conq", listOf(city2Id), "spare", null)
        t.eng { g -> dropUnique(g, "May not annex cities") }
        t.act(a, "cact", emptyList(), "annex", "annex a puppet")
        ok(t.eng { g -> !city2(g).isPuppet }, "annexed")
        val city3Id = t.eng { g ->
            val c = civ(g); val c2 = city2(g)
            val tile = c2.getCenterTile().getTilesInDistanceRange(4..14).sortedBy { it.aerialDistanceTo(c2.getCenterTile()) }
                .first { it.isLand && !it.isImpassible() && it.getUnits().none() && it.getOwner() == null && g.getCities().none { o -> o.getCenterTile().aerialDistanceTo(it) < 4 } }
            c.addCity(tile.position).id
        }
        t.eng { g -> g.gameParameters.oneCityChallenge = true }
        val occ = t.eng { g -> GameOps.conquestChoices(civ(g), civ(g).cities.first { it.id == city3Id }).map { it.id } }
        ok("destroy" in occ && "keep" !in occ && "raze" !in occ, "one-city challenge: destroy instead of keep/raze ($occ)")
        t.act(a, "conq", listOf(city3Id), "destroy", "one-city challenge: destroy a captured city")
        t.eng { g -> g.gameParameters.oneCityChallenge = false }
        ok(t.eng { g -> g.getCities().none { it.id == city3Id } }, "city destroyed")

        // --- focus: GoldGrowth / ProductionGrowth, Faith only with religion
        t.act(a, "selcity", emptyList(), "city:$cityId", null)
        val foci = t.eng { g -> Features.cityExtraChoices(city(g), null).map { it.id } }
        ok("focus:GoldGrowthFocus" in foci && "focus:ProductionGrowthFocus" in foci, "growth foci offered")
        ok(("focus:FaithFocus" in foci) == t.eng { g -> g.isReligionEnabled() } && "focus:HappinessFocus" !in foci, "faith focus only with religion, no hidden foci")
        t.act(a, "cact", emptyList(), "focus:GoldGrowthFocus", "focus: gold + growth")
        ok(t.eng { g -> city(g).getCityFocus() == CityFocus.GoldGrowthFocus }, "gold growth focus set")
        t.act(a, "cact", emptyList(), "focus:ProductionGrowthFocus", "focus: production + growth")
        t.eng { g -> city(g).setCityFocus(CityFocus.NoFocus) }

        // --- "creates one improvement" buildings (a test building; G&K has none)
        val testB = "CP Test Estate"
        val impName = t.eng { g -> val c = city(g)
            listOf("Farm", "Mine", "Pasture", "Plantation", "Camp", "Trading post", "Lumber mill").firstOrNull { n ->
                g.ruleset.tileImprovements[n]?.let { imp -> c.getTiles().count { c.cityConstructions.canPlaceCreateOneImprovementOn(imp, it) } >= 2 } == true }
        }
        ok(impName != null, "an improvement the test building can create")
        t.eng { g -> g.ruleset.buildings[testB] = Building().apply { name = testB; cost = 30; uniques = arrayListOf("Creates a [$impName] improvement on a specific tile"); ruleset = g.ruleset } }
        try {
            ok(t.eng { g -> GameOps.productionChoices(city(g)).any { it.id == "prod:$testB" } }, "test building offered")
            st.queueMode = true
            t.act(a, "prod", emptyList(), "prod:$testB", "creates-one: queue from the Cities tab (best tile)")
            st.queueMode = false
            ok(testB in queue() && t.eng { g -> city(g).cityConstructions.getTileForImprovement(impName!!) } != null, "queued with a marked tile")
            t.act(a, "cp_psel", listOf("q"), "q:${queue().indexOf(testB)}", null); act("rm", null)
            ok(t.eng { g -> city(g).cityConstructions.getTileForImprovement(impName!!) } == null, "marker removed with the entry")
            t.act(a, "tab", emptyList(), PROD, null); t.render(a, null)
            pick("c:$testB")
            ok("ask:cp_imptile:$testB" in acts(), "tile picker offered")
            val (tx, tcode) = t.eng { g -> val c = city(g); val imp = g.ruleset.tileImprovements[impName!!]!!
                val tl = c.getTiles().filter { c.cityConstructions.canPlaceCreateOneImprovementOn(imp, it) }.first { st.window?.code(it.position.x, it.position.y) != null }
                tl.position to st.window!!.code(tl.position.x, tl.position.y)!! }
            t.act(a, "cp_imptile", listOf(testB), tcode, "creates-one: queue on a chosen tile")
            ok(t.eng { g -> city(g).cityConstructions.getTileForImprovement(impName!!)?.position } == tx, "chosen tile $tcode marked")
            t.render(a, "cityplus-production-improvement"); dump("cityplus-production-improvement")
            act(acts().first { it.startsWith("buy:Gold~") && it.endsWith("~$testB") }, "creates-one: buying places the improvement")
            ok(t.eng { g -> g.tileMap[tx].improvement } == impName && testB !in queue(), "improvement built on $tcode")
        } finally {
            t.eng { g -> val c = city(g); st.queueMode = false
                if (c.cityConstructions.isBuilt(testB)) c.cityConstructions.removeBuilding(testB)
                c.cityConstructions.removeAllByName(testB)
                g.ruleset.tileImprovements[impName ?: ""]?.let { imp -> c.getTiles().forEach { if (it.isMarkedForCreatesOneImprovement(imp.name)) it.improvementFunctions.removeCreatesOneImprovementMarker(false) } }
                g.ruleset.buildings.remove(testB)
                if (c.cityConstructions.constructionQueue.isEmpty()) c.cityConstructions.setCurrentConstruction(unit)
            }
        }

        // --- tiles: by code and whole rings
        t.act(a, "tab", emptyList(), PROD, null); t.render(a, null)
        val buyCode = t.eng { g -> val c = city(g)
            c.tilesInRange.filter { c.expansion.canBuyTile(it) }.firstNotNullOfOrNull { tl -> st.window?.code(tl.position.x, tl.position.y)?.let { tl.position to it } } }
        ok(buyCode != null, "a tile to buy by code")
        ok(t.eng { g -> cityChoices(city(g), st.window).any { it.id == "ask:cp_buytile:$cityId" } }, "buy-by-code offered in city actions")
        t.act(a, "cp_buytile", listOf(cityId), buyCode!!.second, "buy a tile by code")
        ok(t.eng { g -> g.tileMap[buyCode.first].getCity()?.id } == cityId, "tile ${buyCode.second} bought")
        ok(!t.act(a, "cp_buytile", listOf(cityId), buyCode.second, null), "can't buy an owned tile")
        val ring = t.eng { g -> ringChoices(city(g)).firstOrNull() }
        ok(ring != null, "a ring to buy")
        val ringTiles = t.eng { g -> ringPlan(city(g), ring!!.id.removePrefix("cp:ring:").toInt()).map { it.position } }
        t.act(a, "cact", emptyList(), ring!!.id, "buy a whole ring of tiles")
        ok(t.eng { g -> ringTiles.all { g.tileMap[it].getCity()?.id == cityId } }, "${ringTiles.size} ring tiles bought")

        // --- 📈 city details
        t.eng { g -> val c = city(g); c.setFlag(CityFlags.WeLoveTheKing, 5); c.setFlag(CityFlags.Resistance, 2) }
        t.act(a, "tab", emptyList(), PROD, null)
        if (st.ext["cp_details"] == true) t.act(a, "cp_pview", emptyList(), null, null)
        t.act(a, "cp_pview", emptyList(), null, "city details view")
        val info = t.eng { g -> growthAndBorders(city(g)) }
        ok(info.any { it.contains("We Love the King") } && info.any { it.contains("resistance") } && info.any { it.contains("Borders") }, "borders, WLTK and resistance shown")
        t.act(a, "cp_stat", emptyList(), "s:Gold", "detailed stat breakdown")
        ok(t.eng { g -> statBreakdown(city(g), Stat.Gold).last().contains("Total") }, "gold breakdown has a total")
        ok(t.eng { g -> religionLines(city(g)).any { it.contains("followers") } }, "religion followers listed")
        val cant = t.eng { g -> unbuildable(city(g)).firstOrNull() }
        println("  CityPlus: can't build e.g. ${cant?.first?.name}: ${cant?.second}; GPP ${t.eng { g -> greatPeople(city(g)) }}; resources ${t.eng { g -> localResources(city(g)) }}")
        if (cant != null) { t.act(a, "cp_cant", emptyList(), "x:${cant.first.name}", "why can't it be built (rejection reason)"); ok(st.flash.lastOrNull()?.startsWith("🚫") == true, "reason shown") }
        t.render(a, "cityplus-details"); dump("cityplus-details")
        t.eng { g -> val c = city(g); c.removeFlag(CityFlags.WeLoveTheKing); c.removeFlag(CityFlags.Resistance) }
        t.act(a, "cp_pview", emptyList(), null, null)

        // --- 🏙️ cities overview, 🏛️ wonders
        t.act(a, "tab", emptyList(), CITYOV, "cities overview tab")
        t.act(a, "cp_ovsort", emptyList(), "sort:pop", "cities overview: sort")
        t.render(a, "cityplus-cities-overview"); dump("cityplus-cities-overview")
        t.act(a, "cp_ovcity", emptyList(), "city:$city2Id", "cities overview: open a city")
        ok(st.tab == PROD && st.cityId == city2Id, "jumped to the city's production")
        val wonder = t.eng { g -> val built = g.getCities().flatMap { c -> c.cityConstructions.getBuiltBuildings().map { it.name }.toList() }.toSet()
            g.ruleset.buildings.values.first { it.isWonder && it.name !in built && it.era(g.ruleset)?.eraNumber == 0 }.name.also { city(g).cityConstructions.addBuilding(it) } }
        ok(t.eng { g -> wonderInfo(civ(g)).any { it.name == wonder && it.status == WStatus.Owned && it.city?.id == cityId } }, "$wonder listed as ours")
        ok(t.eng { g -> wonderInfo(civ(g)).any { it.status == WStatus.Unbuilt } }, "unbuilt wonders listed")
        t.act(a, "tab", emptyList(), WONDERS, "wonders tab")
        t.act(a, "cp_won", emptyList(), "w:$wonder", "wonders: look at one")
        t.render(a, "cityplus-wonders"); dump("cityplus-wonders")
        t.act(a, "selcity", emptyList(), "city:$cityId", null)
        st.ext.remove("cp_sel"); st.flash.clear()
    }
}

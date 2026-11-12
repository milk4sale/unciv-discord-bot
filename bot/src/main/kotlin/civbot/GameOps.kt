package civbot

import com.unciv.Constants
import com.unciv.logic.GameInfo
import com.unciv.logic.GameStarter
import com.unciv.logic.automation.civilization.NextTurnAutomation
import com.unciv.logic.automation.civilization.ReligionAutomation
import com.unciv.logic.automation.civilization.TradeAutomation
import com.unciv.logic.automation.unit.EspionageAutomation
import com.unciv.logic.automation.unit.UnitAutomation
import com.unciv.logic.battle.AttackableTile
import com.unciv.logic.battle.Battle
import com.unciv.logic.battle.CityCombatant
import com.unciv.logic.battle.MapUnitCombatant
import com.unciv.logic.battle.TargetHelper
import com.unciv.logic.city.City
import com.unciv.logic.civilization.AlertType
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.PlayerType
import com.unciv.logic.civilization.diplomacy.DiplomaticStatus
import com.unciv.logic.automation.city.ConstructionAutomation
import com.unciv.logic.map.MapSize
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.logic.map.mapunit.actions.UnitActions
import com.unciv.logic.map.mapunit.actions.UnitActionsUpgrade
import com.unciv.logic.map.tile.Tile
import com.unciv.logic.trade.TradeEvaluation
import com.unciv.logic.trade.TradeLogic
import com.unciv.logic.trade.TradeOffer
import com.unciv.logic.trade.TradeOfferType
import com.unciv.models.UnitActionType
import com.unciv.models.metadata.GameSetupInfo
import com.unciv.models.metadata.Player
import com.unciv.models.ruleset.Building
import com.unciv.models.ruleset.INonPerpetualConstruction
import com.unciv.models.ruleset.PerpetualConstruction
import com.unciv.models.ruleset.unique.UniqueType
import com.unciv.logic.automation.Automation
import kotlin.math.roundToInt
import com.unciv.models.ruleset.unit.BaseUnit
import com.unciv.models.stats.Stat
import com.unciv.view.GameView

/** Unit name without Unciv's [placeholder] brackets. */
fun MapUnit.label() = GameOps.clean(displayName())

/** One choice in a Discord select menu. [id] round-trips back to [GameOps]. */
data class Choice(val id: String, val label: String, val description: String = "", val emoji: String? = null)

/** Thrown for "you can't do that" situations; the message is shown to the player. */
class PlayerError(msg: String) : Exception(msg)

/** All game rules interaction. Every function here must run on the engine thread. */
object GameOps {

    val MAP_SIZES = listOf("Tiny", "Small", "Medium", "Large", "Huge")
    val MAP_TYPES = listOf("Continent and Islands", "Pangaea", "Two Continents", "Archipelago", "Inner Sea", "Lakes", "Small Continents", "Fractal", "Perlin", "Three Continents", "Four Corners", "Spiral", "Boreal")
    val DIFFICULTIES = listOf("Settler", "Chieftain", "Warlord", "Prince", "King", "Emperor", "Immortal", "Deity")
    val SPEEDS = listOf("Quick", "Standard", "Epic", "Marathon")
    val ERAS = listOf("Ancient era", "Classical era", "Medieval era", "Renaissance era", "Industrial era", "Modern era", "Atomic era", "Information era")
    val VICTORIES = listOf("Scientific", "Cultural", "Domination", "Diplomatic", "Time")

    // ------------------------------------------------------------------ setup

    /** Major nations available for this lobby (respects selected mods / base ruleset). */
    fun majorNations(meta: GameMeta? = null): List<String> {
        val base = com.unciv.models.metadata.BaseRuleset.Civ_V_GnK.fullName
        val rs = try {
            if (meta != null && (meta.settings.mods.isNotEmpty() || meta.settings.extra["baseRuleset"] != null))
                com.unciv.models.ruleset.RulesetCache.getComplexRuleset(LinkedHashSet(meta.settings.mods), meta.settings.extra["baseRuleset"] ?: base)
            else com.unciv.models.ruleset.RulesetCache[base]!!
        } catch (_: Exception) { com.unciv.models.ruleset.RulesetCache[base]!! }
        return rs.nations.values.filter { it.isMajorCiv }.map { it.name }.sorted()
    }

    fun startGame(meta: GameMeta): GameInfo {
        Extensions.all.firstNotNullOfOrNull { it.createGame(meta) }?.let { game ->
            for (seat in meta.seats) seat.civId = game.civilizations.first { it.playerId == "discord:${seat.userId}" }.civID
            return game
        }
        val s = meta.settings
        val setup = GameSetupInfo()
        val ruleset = com.unciv.models.ruleset.RulesetCache[com.unciv.models.metadata.BaseRuleset.Civ_V_GnK.fullName]!!
        val players = ArrayList<Player>()
        for (seat in meta.seats) players += Player(seat.nation, PlayerType.Human, "discord:${seat.userId}")
        repeat(s.aiCivs) { players += Player(Constants.random, PlayerType.AI) }
        setup.gameParameters.apply {
            baseRuleset = ruleset.name
            difficulty = s.difficulty
            speed = s.speed
            noBarbarians = !s.barbarians
            numberOfCityStates = s.cityStates
            isOnlineMultiplayer = false // hotseat rotation: the bot is the "device" everyone shares
            this.players = players
            victoryTypes = ArrayList(s.victoryTypes.filter { it in ruleset.victories }.ifEmpty { ruleset.victories.keys.toList() })
            startingEra = s.startEra.takeIf { it in ruleset.eras } ?: startingEra
        }
        setup.mapParameters.apply {
            type = s.mapType
            worldWrap = s.worldWrap
            mapSize = when (s.mapSize) {
                "Tiny" -> MapSize.Tiny; "Medium" -> MapSize.Medium; "Large" -> MapSize.Large; "Huge" -> MapSize.Huge; else -> MapSize.Small
            }
        }
        Extensions.all.forEach { it.applyToSetup(setup, meta) }
        val game = GameStarter.startNewGame(setup)
        for (seat in meta.seats) {
            seat.civId = game.civilizations.first { it.playerId == "discord:${seat.userId}" }.civID
        }
        return game
    }

    fun civOf(game: GameInfo, seat: Seat): Civilization = game.getCivilization(seat.civId)

    // ------------------------------------------------------------------ text helpers

    /** Unciv strings use [placeholders]; strip the brackets for display. */
    fun clean(text: String) = text.replace("[", "").replace("]", "").replace("{", "").replace("}", "")

    fun yearText(game: GameInfo): String {
        val y = game.getYear()
        return if (y < 0) "${-y} BC" else "$y AD"
    }

    /** "Production" instead of Unciv's bare stat symbol */
    fun focusName(f: com.unciv.logic.city.CityFocus): String = when (f) {
        com.unciv.logic.city.CityFocus.NoFocus -> "Default"
        com.unciv.logic.city.CityFocus.Manual -> "Manual"
        else -> f.name.removeSuffix("Focus").replace(Regex("([a-z])([A-Z])"), "$1 + $2")
    }

    fun unitEmoji(u: MapUnit) = when {
        u.isCivilian() && u.baseUnit.name.contains("Settler") -> "🏕️"
        u.isCivilian() && (u.baseUnit.name.contains("Worker") || u.baseUnit.name.contains("Work Boat")) -> "🔨"
        u.isCivilian() -> "🧑"
        u.baseUnit.isAirUnit() -> "✈️"
        u.baseUnit.isWaterUnit -> "⛵"
        u.baseUnit.isRanged() -> "🏹"
        else -> "⚔️"
    }

    fun unitStatus(u: MapUnit): String = when {
        u.isAutomatingRoadConnection() -> "building a road"
        u.isAutomated() -> "automated"
        u.isExploring() -> "exploring"
        u.isMoving() -> "moving"
        u.isFortified() -> "fortified"
        u.isSleeping() -> "sleeping"
        u.getTile().improvementInProgress != null && u.canBuildImprovement(u.civ.gameInfo.ruleset.tileImprovements[u.getTile().improvementInProgress!!] ?: return "") ->
            "building ${u.getTile().improvementInProgress}"
        !u.hasMovement() -> "done"
        !u.due -> "skipped"
        else -> "ready"
    }

    fun movesText(u: MapUnit): String {
        val cur = u.currentMovement
        val txt = if (cur == cur.toInt().toFloat()) cur.toInt().toString() else "%.1f".format(cur)
        return "$txt/${u.getMaxMovement()}"
    }

    fun tileDescription(t: Tile, viewer: Civilization): String {
        if (!t.isExplored(viewer)) return "Unexplored"
        val parts = ArrayList<String>()
        parts += t.baseTerrain
        parts += t.terrainFeatures
        t.naturalWonder?.let { parts += it }
        t.resource?.let { r -> t.ruleset.tileResources[r]?.let { if (viewer.tech.isRevealed(it)) parts += r } }
        t.getShownImprovement(viewer)?.let { parts += it }
        t.getCity()?.let { if (t.isCityCenter()) parts += "City of ${it.name}" }
        return parts.joinToString(", ")
    }

    // ------------------------------------------------------------------ units

    fun unitsOf(civ: Civilization): List<MapUnit> =
        civ.units.getCivUnits().sortedWith(compareByDescending<MapUnit> { it.due && it.isIdle() }.thenBy { it.baseUnit.name }).toList()

    fun unitById(civ: Civilization, id: Int): MapUnit? = civ.units.getCivUnits().firstOrNull { it.id == id }

    fun nextIdleUnit(civ: Civilization, after: MapUnit?): MapUnit? {
        val due = civ.units.getCivUnits().filter { it.due && it.isIdle() && it.hasMovement() }.toList()
        if (due.isEmpty()) return null
        val i = due.indexOf(after)
        return due[(i + 1) % due.size]
    }

    fun reachableTiles(u: MapUnit): Set<Tile> =
        if (!u.hasMovement()) emptySet()
        else u.movement.getDistanceToTiles().keys.filter { it != u.getTile() && u.movement.canMoveTo(it) }.toSet()

    fun attackableTargets(u: MapUnit): List<AttackableTile> {
        // Nukes go through MilitaryPlus's targeted, confirmed "Nuke a tile…" flow, never the plain attack list
        if (!u.canAttack() || !u.hasMovement() || u.isNuclearWeapon()) return emptyList()
        return TargetHelper.getAttackableEnemies(u, u.movement.getDistanceToTiles())
            .filter { it.tileToAttack.isVisible(u.civ) }
            .distinctBy { it.tileToAttack }
    }

    /** Move toward [target]; multi-turn moves keep going automatically on later turns. */
    fun moveUnit(u: MapUnit, target: Tile, allowSwap: Boolean = true): String {
        if (target == u.getTile()) return "Already there."
        if (!u.hasMovement()) throw PlayerError("${u.label()} has no moves left this turn.")
        val enemy = target.getUnits().firstOrNull { it.civ != u.civ && u.civ.isAtWarWith(it.civ) }
            ?: target.getCity()?.takeIf { target.isCityCenter() && u.civ.isAtWarWith(it.civ) }
        if (enemy != null) {
            attackableTargets(u).firstOrNull { it.tileToAttack == target }?.let { return attack(u, it) }
            throw PlayerError("There's an enemy there. Use **Attack** from the actions menu when in range.")
        }
        // Own unit of the same kind there: swap places (Unciv: canUnitSwapTo / swapMoveToTile)
        if (allowSwap) UnitsPlus.trySwap(u, target)?.let { return it }
        val step = try { u.movement.getTileToMoveToThisTurn(target) } catch (e: Exception) {
            throw PlayerError("${u.label()} can't find a path there.")
        }
        val before = u.getTile()
        u.movement.moveToTile(step)
        if (u.getTile() == before) throw PlayerError("${u.label()} can't move there.")
        if (u.getTile() != target) {
            u.action = "moveTo ${target.position.x},${target.position.y}"
            return "${u.label()} is heading there (continues next turn)."
        }
        if (u.isExploring() || u.isMoving()) u.action = null
        return "${u.label()} moved."
    }

    /** Unciv's "Connect road": the worker builds a road to [target] automatically over the next turns. */
    fun connectRoad(u: MapUnit, target: Tile): String {
        if (target == u.getTile()) throw PlayerError("Pick a different tile than the one the worker is on.")
        if (!target.isExplored(u.civ)) throw PlayerError("You haven't explored that tile yet.")
        if (target.isWater) throw PlayerError("Roads can't go on water.")
        if (u.movement.getRoadPath(target) == null) throw PlayerError("No road route there from here.")
        u.automatedRoadConnectionDestination = target.position
        u.action = Constants.connectRoadAction
        u.automated = true
        if (u.hasMovement()) UnitAutomation.automateUnitMoves(GameView(u.civ.gameInfo, u.civ).getMapUnitView(u))
        return "🛣️ ${u.label()} is building a road there."
    }

    fun stepUnit(u: MapUnit, clock: Int): String {
        val d = com.unciv.logic.map.HexMath.getClockPositionToHexcoord(clock)
        val t = u.getTile().tileMap.getIfTileExistsOrNull(u.getTile().position.x + d.x, u.getTile().position.y + d.y)
            ?: throw PlayerError("That's the edge of the world.")
        return moveUnit(u, t)
    }

    fun attack(u: MapUnit, target: AttackableTile): String {
        val defender = target.combatant
        val defName = when (defender) {
            is MapUnitCombatant -> "${defender.unit.civ.civName} ${defender.unit.label()}"
            is CityCombatant -> defender.city.name
            else -> "the enemy"
        }
        val defHpBefore = defender?.getHealth() ?: 0
        val hpBefore = u.health
        Battle.moveAndAttack(MapUnitCombatant(u), target)
        val dealt = defHpBefore - (defender?.getHealth() ?: 0)
        val taken = hpBefore - u.health
        return buildString {
            append("⚔️ ${u.label()} attacked $defName: dealt **$dealt**, took **$taken**.")
            if (defender != null && defender.isDefeated()) append(" **$defName destroyed!**")
            if (u.health <= 0 || u.isDestroyed) append(" Our unit was lost.")
        }
    }

    /** Everything a unit can do right now, as menu choices. */
    fun unitChoices(u: MapUnit): List<Choice> {
        val out = ArrayList<Choice>()
        for (t in attackableTargets(u)) {
            val name = when (val c = t.combatant) {
                is MapUnitCombatant -> "${c.unit.civ.civName} ${c.unit.label()} (${c.getHealth()} HP)"
                is CityCombatant -> "city ${c.city.name} (${c.getHealth()} HP)"
                else -> "enemy"
            }
            out += Choice("atk:${t.tileToAttack.position.x},${t.tileToAttack.position.y}", "Attack $name", "Combat", "⚔️")
        }
        if (u.promotions.canBePromoted() && u.hasMovement() && u.attacksThisTurn == 0) // Unciv: PromotionPickerScreen.canPromoteNow
            for (p in u.promotions.getAvailablePromotions()) out += Choice("promo:${p.name}", "Promote: ${p.name}", clean(p.uniques.joinToString("; ")).take(100), "⭐")

        // Engine actions that need no extra UI (found city, pillage, great people, religion, upgrades...)
        val engineTypes = listOf(
            UnitActionType.FoundCity, UnitActionType.CreateImprovement, UnitActionType.Pillage, UnitActionType.Repair,
            UnitActionType.HurryResearch, UnitActionType.HurryPolicy, UnitActionType.HurryWonder, UnitActionType.HurryBuilding,
            UnitActionType.ConductTradeMission, UnitActionType.FoundReligion, UnitActionType.EnhanceReligion,
            UnitActionType.SpreadReligion, UnitActionType.RemoveHeresy, UnitActionType.AddInCapital,
            UnitActionType.Transform, UnitActionType.SetUp, UnitActionType.Guard, UnitActionType.TriggerUnique,
            UnitActionType.Paradrop, UnitActionType.GiftUnit,
        )
        for (type in engineTypes) {
            val actions = try { UnitActions.getUnitActions(u, type).toList() } catch (e: Exception) { emptyList() }
            actions.forEachIndexed { i, a ->
                if (a.action != null) out += Choice("act:${type.name}:$i", clean(a.title), type.value, if (type == UnitActionType.FoundCity) "🏙️" else "✨")
            }
        }
        try {
            UnitActionsUpgrade.getUpgradeActions(u).forEachIndexed { i, a ->
                if (a.action != null) out += Choice("upg:$i", clean(a.title), "Upgrade", "⬆️")
            }
        } catch (_: Exception) {}

        // Worker improvements on the current tile
        val tile = u.getTile()
        if (u.hasMovement()) {
            for (imp in u.civ.gameInfo.ruleset.tileImprovements.values) {
                if (imp.turnsToBuild == -1 || imp.name == tile.improvement || imp.name == tile.improvementInProgress) continue
                if (!u.canBuildImprovement(imp)) continue
                if (tile.improvementFunctions.getImprovementBuildingProblems(imp, u.cache.state).any()) continue
                val turns = imp.getTurnsToBuild(u.civ, u)
                out += Choice("imp:${imp.name}", "Build ${imp.name}", "$turns turns", "🔨")
            }
        }
        if (tile.improvementInProgress != null && u.isCivilian()) out += Choice("cancelimp", "Stop building ${tile.improvementInProgress}", "", "✖️")

        // Extension actions (see Ext.kt)
        for (e in Extensions.all) out += runCatching { e.unitChoices(u) }.getOrDefault(emptyList())

        // Escort: a civilian and a military unit on the same tile move together
        if (u.isEscorting()) out += Choice("unescort", "Stop escorting", "Units move separately again", "↔️")
        else if (u.getOtherEscortUnit() != null && !u.baseUnit.isAirUnit()) out += Choice("escort", "Escort with ${u.getOtherEscortUnit()!!.label()}", "Move together as a pair", "🤝")

        // Standing orders
        if (u.isAutomated() || u.isExploring() || u.isMoving() || u.isFortified() || u.isSleeping())
            out += Choice("wake", "Wake up / cancel orders", unitStatus(u), "⏰")
        if (!u.isAutomated() && u.hasMovement()) out += Choice("automate", if (u.isCivilian()) "Automate (AI handles it)" else "Automate", "Hands the unit to the AI", "🤖")
        if (!u.baseUnit.isAirUnit() && !u.isExploring() && u.hasMovement()) out += Choice("explore", "Explore", "Auto-explore the map", "🧭")
        if (u.canFortify() && !u.isFortified() && u.hasMovement()) out += Choice("fortify", "Fortify", "+defence, stays put", "🛡️")
        if (!u.canFortify() && !u.isSleeping() && u.hasMovement()) out += Choice("sleep", "Sleep", "Stays put until woken", "💤")
        // "…until healed": wakes up automatically at full health
        if (u.health < 100 && u.hasMovement() && u.canHealInCurrentTile()) {
            if (u.canFortify() && !u.isFortifyingUntilHealed()) out += Choice("fortifyheal", "Fortify until healed", "Wakes at full health", "🩹")
            if (!u.canFortify() && !u.isSleepingUntilHealed()) out += Choice("sleepheal", "Sleep until healed", "Wakes at full health", "🩹")
        }
        // Workers: build a road all the way to a tile or city (Unciv's "Connect road" automation)
        val road = u.civ.gameInfo.ruleset.tileImprovements[com.unciv.logic.map.tile.RoadStatus.Road.name]
        if (road != null && u.civ.tech.getBestRoadAvailable() != com.unciv.logic.map.tile.RoadStatus.None && u.canBuildImprovement(road))
            out += Choice("ask:roadto", "Connect road to…", "Builds a road to a tile or city, over several turns", "🛣️")
        if (u.hasMovement()) out += Choice("skip", "Skip this turn", "", "⏭️")
        if (u.hasMovement()) out += Choice("disband", "Disband unit…", UnitsPlus.disbandText(u), "🗑️")
        out.addAll(0, UnitsPlus.pendingDisbandChoices(u))
        return out.sortedBy { if (it.id == "do:cp_undo") 0 else 1 } // ↩️ Undo always first
    }

    fun doUnitChoice(u: MapUnit, id: String): String {
        val name = u.label()
        when {
            id.startsWith("atk:") -> {
                val (x, y) = id.removePrefix("atk:").split(",").map { it.toInt() }
                val t = attackableTargets(u).firstOrNull { it.tileToAttack.position.x == x && it.tileToAttack.position.y == y }
                    ?: throw PlayerError("That target is no longer in range.")
                return attack(u, t)
            }
            id.startsWith("promo:") -> {
                val p = id.removePrefix("promo:")
                if (!u.hasMovement() || u.attacksThisTurn > 0) throw PlayerError("$name can only be promoted before attacking and while it has moves left.")
                u.promotions.addPromotion(p)
                return "⭐ $name promoted: **$p**."
            }
            id.startsWith("act:") -> {
                val (_, typeName, idx) = id.split(":")
                val type = UnitActionType.valueOf(typeName)
                val a = UnitActions.getUnitActions(u, type).toList().getOrNull(idx.toInt())?.action
                    ?: throw PlayerError("That action isn't available any more.")
                a.invoke()
                return "✨ $name: ${type.value}."
            }
            id.startsWith("upg:") -> {
                val a = UnitActionsUpgrade.getUpgradeActions(u).toList().getOrNull(id.removePrefix("upg:").toInt())?.action
                    ?: throw PlayerError("Upgrade not available.")
                a.invoke()
                return "⬆️ $name upgraded."
            }
            id.startsWith("imp:") -> {
                val imp = u.civ.gameInfo.ruleset.tileImprovements[id.removePrefix("imp:")] ?: throw PlayerError("Unknown improvement.")
                u.getTile().startWorkingOnImprovement(imp, u.civ, u)
                u.action = null
                return "🔨 $name started building **${imp.name}**."
            }
            id == "escort" -> { u.startEscorting(); return "🤝 ${u.label()} and ${u.getOtherEscortUnit()?.label()} now move together." }
            id == "unescort" -> { u.stopEscorting(); return "↔️ Units move separately." }
            id == "cancelimp" -> { u.getTile().stopWorkingOnImprovement(); return "Stopped building." }
            id == "wake" -> { u.action = null; u.automated = false; u.automatedRoadConnectionDestination = null; u.due = true; return "$name is awaiting orders." }
            id == "automate" -> {
                u.automated = true
                if (u.hasMovement()) UnitAutomation.automateUnitMoves(GameView(u.civ.gameInfo, u.civ).getMapUnitView(u))
                return "🤖 $name is now automated."
            }
            id == "explore" -> {
                u.action = UnitActionType.Explore.value
                if (u.hasMovement()) UnitAutomation.automatedExplore(GameView(u.civ.gameInfo, u.civ).getMapUnitView(u))
                return "🧭 $name is exploring."
            }
            id == "fortify" -> { u.fortify(); return "🛡️ $name fortified." }
            id == "fortifyheal" -> { u.fortifyUntilHealed(); return "🩹 $name fortifies until healed." }
            id == "sleepheal" -> { u.action = UnitActionType.SleepUntilHealed.value; return "🩹 $name sleeps until healed." }
            id == "sleep" -> { u.action = UnitActionType.Sleep.value; return "💤 $name is sleeping." }
            id == "skip" -> { u.due = false; return "⏭️ $name skips this turn." }
            id == "disband" -> return UnitsPlus.armDisband(u)
        }
        Extensions.all.firstNotNullOfOrNull { it.doUnitChoice(u, id) }?.let { return it }
        throw PlayerError("Unknown action.")
    }

    // ------------------------------------------------------------------ cities

    fun citiesOf(civ: Civilization): List<City> = civ.cities.sortedByDescending { it.isCapital() }

    fun productionChoices(city: City): List<Choice> {
        val cc = city.cityConstructions
        val units = cc.getConstructableUnits().filter { it.isBuildable(cc) }.toList()
        val buildings = city.getRuleset().buildings.values.filter { it.isBuildable(cc) }
        val all = (units + buildings).map { c ->
            val turns = cc.turnsToConstruction(c.name)
            val kind = when (c) {
                is BaseUnit -> "🪖"
                is Building -> if (c.isWonder || c.isNationalWonder) "🏛️" else "🏠"
                else -> "•"
            }
            Triple(c, turns, kind)
        }.sortedWith(compareBy({ it.third == "🏛️" }, { it.second }))
        return all.map { (c, turns, kind) -> Choice("prod:${c.name}", c.name, "$turns turns", kind) } + perpetualChoices(city)
    }

    /** Every perpetual construction Unciv has (gold, science, culture, faith, food, nothing) that this city may use. */
    fun perpetualChoices(city: City): List<Choice> =
        PerpetualConstruction.perpetualConstructionsMap.values.filter { it.isBuildable(city.cityConstructions) }.map { p ->
            if (p is PerpetualConstruction.StatConversion) {
                val perTurn = (city.cityStats.currentCityStats.production / p.getConversionRate(city)).roundToInt()
                val e = mapOf(Stat.Gold to "💰", Stat.Science to "🔬", Stat.Culture to "🎭", Stat.Faith to "🙏", Stat.Food to "🍞")[p.stat] ?: "♾️"
                Choice("prod:${p.name}", "Convert production to ${p.name.lowercase()}", "Perpetual · about $perTurn ${p.name.lowercase()} per turn", e)
            } else Choice("prod:${p.name}", "Produce nothing", "Perpetual · the city idles", "⏸️")
        }

    /** Ruleset lookup by name (CityConstructions.getConstruction is internal to Unciv). */
    fun construction(city: City, name: String): com.unciv.models.ruleset.IConstruction =
        city.getRuleset().units[name] ?: city.getRuleset().buildings[name] ?: PerpetualConstruction.perpetualConstructionsMap[name]
            ?: throw PlayerError("Unknown construction $name.")

    /** [UniqueType.CreatesOneImprovement] buildings need a target tile marked before they are queued (Unciv throws otherwise).
     *  Uses [tile], an already marked tile, or the best free tile. Returns null for ordinary constructions. */
    fun markImprovementTile(city: City, name: String, tile: Tile? = null): Tile? {
        val b = city.getRuleset().buildings[name] ?: return null
        val imp = b.getImprovementToCreate(city.getRuleset(), city.civ) ?: return null
        val cc = city.cityConstructions
        val marked = cc.getTileForImprovement(imp.name)
        if (marked != null && (tile == null || tile == marked)) return marked
        if (tile != null && !cc.canPlaceCreateOneImprovementOn(imp, tile))
            throw PlayerError("A ${imp.name} can't be placed on that tile — pick one of ${city.name}'s own tiles where it can be built.")
        val valid = city.getTiles().filter { cc.canPlaceCreateOneImprovementOn(imp, it) }.toList()
        val t = tile ?: Automation.getTileForConstructionImprovement(city, imp)?.takeIf { it in valid } ?: valid.firstOrNull()
            ?: throw PlayerError("No tile of ${city.name} can take a ${imp.name}.")
        marked?.improvementFunctions?.removeCreatesOneImprovementMarker(removeConstruction = false)
        if (!cc.tryPlaceCreateOneImprovementMarker(imp, t)) throw PlayerError("Couldn't mark that tile for a ${imp.name}.")
        return t
    }

    /** Add to the queue with Unciv's checks (canAddToQueue), placing "creates one improvement" markers first. */
    fun queueConstruction(city: City, name: String, addToTop: Boolean = false, tile: Tile? = null): String {
        if (city.isPuppet) throw PlayerError("Puppet cities choose their own production.")
        val cc = city.cityConstructions
        val c = construction(city, name)
        if (!cc.canAddToQueue(c) || (c is PerpetualConstruction && name in cc.constructionQueue))
            throw PlayerError(if (cc.isQueueFull()) "${city.name}'s queue is full." else "$name can't be added to ${city.name}'s queue.")
        val t = markImprovementTile(city, name, tile)
        cc.addToQueue(c, addToTop = addToTop, tile = t)
        return "📋 Added **$name** to ${if (addToTop) "the top of " else ""}${city.name}'s queue" + (if (t != null) " (improvement tile marked)" else "") + "."
    }

    fun setProduction(city: City, name: String): String {
        if (city.isPuppet) throw PlayerError("Puppet cities choose their own production. Annex it first.")
        markImprovementTile(city, name)
        city.cityConstructions.setCurrentConstruction(name)
        return "🏗️ ${city.name} is now building **$name**."
    }

    fun buyChoices(city: City): List<Choice> = purchaseChoices(city, Stat.statsUsableToBuy - Stat.Faith, "buy:")

    /**
     * Everything [city] may buy with [stats] right now (Unciv's buy buttons): the current build and queued items first
     * (bought at their queue position), then everything else by price. Puppets only with MayBuyConstructionsInPuppets.
     * Ids: "<prefix><Stat>~<queueIndex or -1>~<name>".
     */
    fun purchaseChoices(city: City, stats: Collection<Stat>, prefix: String): List<Choice> {
        val cc = city.cityConstructions
        if (city.isPuppet && city.getMatchingUniques(UniqueType.MayBuyConstructionsInPuppets).none()) return emptyList()
        val rs = city.getRuleset()
        val queue = cc.constructionQueue
        val queued = queue.withIndex().mapNotNull { (i, n) -> ((rs.units[n] ?: rs.buildings[n]) as? INonPerpetualConstruction)?.let { i to it } }
        val others = (rs.units.values.asSequence() + rs.buildings.values.asSequence()).filter { it.name !in queue }.map { -1 to it as INonPerpetualConstruction }
        val emoji = mapOf(Stat.Gold to "💰", Stat.Faith to "🙏", Stat.Food to "🍞", Stat.Science to "🔬", Stat.Culture to "🎭")
        val first = ArrayList<Choice>(); val rest = ArrayList<Pair<Int, Choice>>()
        for (stat in stats) for ((idx, c) in queued.asSequence() + others) {
            if (!c.canBePurchasedWithStat(city, stat)) continue
            val cost = c.getStatBuyCost(city, stat) ?: continue
            if (!cc.isConstructionPurchaseAllowed(c, stat, cost)) continue
            val where = when (idx) { -1 -> ""; 0 -> " (building now)"; else -> " (queued #${idx + 1})" }
            val ch = Choice("$prefix${stat.name}~$idx~${c.name}", "Buy ${c.name}$where", "$cost ${stat.name.lowercase()}" +
                (if (c is Building && c.hasCreateOneImprovementUnique()) " · places its improvement" else ""), emoji[stat])
            if (idx >= 0) first += ch else rest += cost to ch
        }
        return first + rest.sortedBy { it.first }.map { it.second }
    }

    /** [spec] = "name" (legacy, gold) or "<Stat>~<queueIndex>~<name>" or "<queueIndex>~<name>" (with [defaultStat]). */
    fun buy(city: City, spec: String, defaultStat: Stat = Stat.Gold): String {
        val parts = spec.split("~", limit = 3)
        val (stat, idx, name) = when (parts.size) {
            3 -> Triple(Stat.safeValueOf(parts[0]) ?: defaultStat, parts[1].toIntOrNull() ?: -1, parts[2])
            2 -> Triple(defaultStat, parts[0].toIntOrNull() ?: -1, parts[1])
            else -> Triple(defaultStat, -1, spec)
        }
        val cc = city.cityConstructions
        val c = construction(city, name) as? INonPerpetualConstruction ?: throw PlayerError("$name can't be bought.")
        val pos = idx.takeIf { it >= 0 && cc.constructionQueue.getOrNull(it) == name } ?: -1
        val cost = c.getStatBuyCost(city, stat) ?: throw PlayerError("$name can't be bought with ${stat.name.lowercase()}.")
        if (!c.canBePurchasedWithStat(city, stat) || !cc.isConstructionPurchaseAllowed(c, stat, cost))
            throw PlayerError("You can't buy $name in ${city.name} with ${stat.name.lowercase()} right now ($cost needed, you have ${city.getStatReserve(stat)}).")
        // A queued "creates one improvement" building keeps the tile it already marked
        val tile = (c as? Building)?.getImprovementToCreate(city.getRuleset(), city.civ)?.let { cc.getTileForImprovement(it.name) }?.takeIf { pos >= 0 }
        if (!cc.purchaseConstruction(c, pos, false, stat, tile)) throw PlayerError("Couldn't buy $name — no room to place it near ${city.name}?")
        val e = mapOf(Stat.Gold to "💰", Stat.Faith to "🙏", Stat.Food to "🍞", Stat.Science to "🔬", Stat.Culture to "🎭")[stat] ?: "💰"
        return "$e Bought **$name** in ${city.name} for $cost ${stat.name.lowercase()}."
    }

    fun bombardTargets(city: City): List<Tile> =
        if (!city.canBombard()) emptyList()
        else TargetHelper.getBombardableTiles(city).filter { it.isVisible(city.civ) }.toList()

    fun bombard(city: City, tile: Tile): String {
        val def = Battle.getMapCombatantOfTile(tile) ?: throw PlayerError("No target there.")
        val before = def.getHealth()
        Battle.attack(CityCombatant(city), def)
        return "🎯 ${city.name} bombarded ${def.getName()}: dealt **${before - def.getHealth()}**."
    }

    fun annex(city: City): String { requireMayAnnex(city.civ); city.annexCity(); return "🏙️ ${city.name} annexed." }

    /** Unciv's [UniqueType.MayNotAnnexCities] (e.g. Venice) */
    fun mayAnnex(civ: Civilization) = !civ.hasUnique(UniqueType.MayNotAnnexCities)
    fun requireMayAnnex(civ: Civilization) { if (!mayAnnex(civ)) throw PlayerError("Your civilization may not annex cities.") }

    fun pendingConquest(civ: Civilization): City? {
        val alert = civ.popupAlerts.firstOrNull { it.type == AlertType.CityConquered } ?: return null
        return civ.gameInfo.getCities().firstOrNull { it.id == alert.value }
    }

    /** What Unciv already did with a captured city (it decides automatically without a game screen). */
    fun conquestStatus(city: City) = when {
        city.isBeingRazed -> "being razed"
        city.isPuppet -> "puppet"
        else -> "annexed"
    }

    fun conquestChoices(civ: Civilization, city: City): List<Choice> {
        val out = ArrayList<Choice>()
        val founder = city.foundingCivObject
        val occ = civ.isOneCityChallenger()
        if (!occ) out += Choice("keep", "Keep it as is (${conquestStatus(city)})", "", "👍")
        if (founder != null && founder != civ && founder.isAlive()) out += Choice("liberate", "Liberate (return to ${founder.civName})", "Big relationship boost", "🕊️")
        if (occ) { out += Choice("destroy", "Destroy", "One-City Challenge: the city is razed to the ground at once", "💥"); return out }
        val mayAnnex = mayAnnex(civ)
        if (city.isPuppet && mayAnnex) out += Choice("annex", "Annex", "Part of your empire (more unhappiness)", "🏙️")
        if (!city.isBeingRazed && city.canBeDestroyed(justCaptured = true))
            out += Choice("raze", "Raze", if (mayAnnex) "Annexes it and burns it down over time" else "Stays a puppet and burns down over time", "🔥")
        if (city.isBeingRazed) out += Choice("spare", "Spare it (stop razing)", "Keep the city", "🧯")
        return out
    }

    fun resolveConquest(civ: Civilization, city: City, choice: String): String {
        civ.popupAlerts.removeAll { it.type == AlertType.CityConquered && it.value == city.id }
        return when (choice) {
            "liberate" -> { city.liberateCity(civ); "🕊️ ${city.name} liberated." }
            "annex" -> { requireMayAnnex(civ); if (city.isPuppet) city.annexCity(); "🏙️ ${city.name} annexed." }
            "raze" -> {
                if (!city.canBeDestroyed(justCaptured = true)) throw PlayerError("Original capitals and holy cities can't be razed.")
                if (city.isPuppet && mayAnnex(civ)) city.annexCity(); city.isBeingRazed = true; "🔥 ${city.name} is being razed."
            }
            "spare" -> { city.isBeingRazed = false; "🧯 ${city.name} will be spared." }
            else -> if (civ.isOneCityChallenger()) {
                // Unciv: a one-city challenger can only liberate or destroy (Battle destroys with overrideSafeties)
                if (city.civ != civ) city.puppetCity(civ)
                val name = city.name; city.destroyCity(overrideSafeties = true); "💥 $name was destroyed."
            } else "👍 ${city.name} stays ${conquestStatus(city)}."
        }
    }

    // ------------------------------------------------------------------ tech & policies

    fun researchChoices(civ: Civilization): List<Choice> {
        val tm = civ.tech
        return civ.gameInfo.ruleset.technologies.values
            .filter { tm.canBeResearched(it.name) }
            .sortedBy { tm.costOfTech(it.name) }
            .map { Choice("tech:${it.name}", it.name, "${tm.turnsToTech(it.name)} turns · " + techUnlocks(civ, it).ifEmpty { it.era() }, "🔬") }
    }

    /** "Warrior, Barracks, Mine" — what a tech unlocks for this civ. */
    fun techUnlocks(civ: Civilization, tech: com.unciv.models.ruleset.tech.Technology): String {
        val rs = civ.gameInfo.ruleset
        val names = rs.units.values.filter { it.requiredTech == tech.name }.map { it.name } +
            rs.buildings.values.filter { it.requiredTech == tech.name }.map { it.name } +
            rs.tileImprovements.values.filter { it.techRequired == tech.name }.map { it.name } +
            rs.tileResources.values.filter { it.revealedBy == tech.name }.map { "reveals ${it.name}" }
        return names.distinct().joinToString(", ")
    }

    fun researchGoalChoices(civ: Civilization): List<Choice> {
        val tm = civ.tech
        return civ.gameInfo.ruleset.technologies.values
            .filter { !tm.isResearched(it.name) && !tm.canBeResearched(it.name) && !tm.isUnresearchable(it) }
            .sortedBy { it.column?.columnNumber ?: 99 }
            .map { t ->
                val steps = tm.getRequiredTechsToDestination(t).size
                Choice("goal:${t.name}", t.name, "$steps techs away · ${t.era()}", "🎯")
            }
    }

    fun setResearch(civ: Civilization, name: String): String {
        val tm = civ.tech
        val tech = civ.gameInfo.ruleset.technologies[name] ?: throw PlayerError("Unknown tech.")
        if (tm.freeTechs > 0 && tm.canBeResearched(name)) {
            tm.getFreeTechnology(name)
            return "🎁 Free technology: **$name**!"
        }
        tm.techsToResearch.clear()
        tm.techsToResearch.addAll(tm.getRequiredTechsToDestination(tech).map { it.name })
        return if (tm.techsToResearch.size > 1) "🎯 Research path set to **$name** (${tm.techsToResearch.size} techs)."
        else "🔬 Researching **$name**."
    }

    fun policyChoices(civ: Civilization): List<Choice> {
        val pm = civ.policies
        if (!pm.canAdoptPolicy()) return emptyList()
        return civ.gameInfo.ruleset.policies.values.filter { pm.isAdoptable(it) }.map { p ->
            val branch = if (p.branch == p) "Opens the ${p.name} tree" else p.branch.name
            Choice("pol:${p.name}", p.name, branch, if (p.branch == p) "📖" else "📜")
        }
    }

    fun adoptedPolicies(civ: Civilization): List<String> =
        civ.gameInfo.ruleset.policies.keys.filter { civ.policies.isAdopted(it) }

    fun adoptPolicy(civ: Civilization, name: String): String {
        val p = civ.gameInfo.ruleset.policies[name] ?: throw PlayerError("Unknown policy.")
        if (!civ.policies.canAdoptPolicy() || !civ.policies.isAdoptable(p)) throw PlayerError("You can't adopt $name right now.")
        civ.policies.adopt(p)
        return "📜 Adopted **$name**."
    }

    // ------------------------------------------------------------------ diplomacy

    fun knownMajorCivs(civ: Civilization) = civ.getKnownCivs().filter { it.isAlive() && !it.isBarbarian && !it.isSpectator }.toList()

    fun declareWar(civ: Civilization, other: Civilization): String {
        val dm = civ.getDiplomacyManager(other) ?: throw PlayerError("You haven't met them.")
        if (civ.isAtWarWith(other)) throw PlayerError("Already at war.")
        if (!dm.canDeclareWar()) throw PlayerError("You can't declare war on them right now (treaty in effect).")
        dm.declareWar()
        return "🔥 ${civ.civName} declared war on ${other.civName}!"
    }

    private fun peaceTrade(civ: Civilization, other: Civilization): TradeLogic {
        val tl = TradeLogic(civ, other)
        tl.currentTrade.ourOffers.add(TradeOffer(Constants.peaceTreaty, TradeOfferType.Treaty, speed = civ.gameInfo.speed))
        tl.currentTrade.theirOffers.add(TradeOffer(Constants.peaceTreaty, TradeOfferType.Treaty, speed = civ.gameInfo.speed))
        return tl
    }

    /** AI civs decide right away; for humans the caller asks the other player. */
    fun proposePeaceToAI(civ: Civilization, other: Civilization): String {
        if (!civ.isAtWarWith(other)) throw PlayerError("You're not at war with them.")
        val tl = peaceTrade(civ, other)
        val acceptable = TradeEvaluation().isTradeAcceptable(tl.currentTrade.reverse(), other, civ)
        if (!acceptable) return "🕊️ ${other.civName} refused peace. (Try again after a few turns, or win a battle first.)"
        tl.acceptTrade()
        return "🕊️ Peace signed between ${civ.civName} and ${other.civName}."
    }

    fun makePeace(a: Civilization, b: Civilization): String {
        if (!a.isAtWarWith(b)) return "Already at peace."
        peaceTrade(a, b).acceptTrade()
        return "🕊️ Peace signed between ${a.civName} and ${b.civName}."
    }

    fun relationText(civ: Civilization, other: Civilization): String {
        val dm = civ.getDiplomacyManager(other) ?: return "unknown"
        return when (dm.diplomaticStatus) {
            DiplomaticStatus.War -> "⚔️ at war"
            DiplomaticStatus.DefensivePact -> "🛡️ defensive pact"
            else -> "🤝 peace · ${dm.relationshipLevel()}"
        }
    }

    // ------------------------------------------------------------------ turn

    /** Things the player probably wants to deal with before ending the turn. */
    fun turnWarnings(civ: Civilization): List<String> {
        val w = ArrayList<String>()
        pendingConquest(civ)?.let { w += "Decide what to do with captured **${it.name}**" }
        if (civ.shouldOpenTechPicker()) w += "Pick a **research** (🔬 Tech tab)"
        if (civ.policies.canAdoptPolicy() && policyChoices(civ).isNotEmpty()) w += "A **policy** can be adopted (📜 Policies tab)"
        val noProd = civ.cities.filter { !it.isPuppet && it.cityConstructions.currentConstructionName().isEmpty() }
        if (noProd.isNotEmpty()) w += "Pick **production** in ${noProd.joinToString { it.name }}"
        val idle = civ.units.getCivUnits().count { it.due && it.isIdle() && it.hasMovement() }
        if (idle > 0) w += "**$idle** unit(s) still have orders to give"
        // The detailed Inbox line (counts events) first; UnitsPlus's simpler Inbox line only if that one isn't there
        StatecraftPlus.inboxWarning(civ, w)?.let { w += it }
        w += UnitsPlus.extraTurnWarnings(civ).filter { line -> !("Inbox" in line && w.any { "Inbox" in it }) }
        return w
    }

    /**
     * The advisor: let Unciv's AI make the decisions the bot has no screen for (religion beliefs,
     * free great people, spies, world congress votes, AI demands/trade offers), and optionally
     * fill any empty research/production/policy picks.
     */
    fun advisor(civ: Civilization, fillPicks: Boolean): List<String> {
        val notes = ArrayList<String>()
        val civView = GameView(civ.gameInfo, civ).civView
        if (fillPicks) {
            if (civ.shouldOpenTechPicker()) {
                Engine.callHidden(NextTurnAutomation, "chooseTechToResearch", civView)
                civ.tech.currentTechnologyName()?.let { notes += "🔬 Advisor chose research: **$it**" }
            }
            for (city in civ.cities) {
                if (!city.isPuppet && city.cityConstructions.currentConstructionName().isEmpty()) {
                    ConstructionAutomation(city.cityConstructions).chooseNextConstruction()
                    notes += "🏗️ Advisor: ${city.name} builds **${city.cityConstructions.currentConstructionName()}**"
                }
            }
            if (civ.policies.canAdoptPolicy()) {
                val before = adoptedPolicies(civ)
                Engine.callHidden(NextTurnAutomation, "adoptPolicy", civView)
                (adoptedPolicies(civ) - before.toSet()).firstOrNull()?.let { notes += "📜 Advisor adopted **$it**" }
            }
        }
        if (civ.greatPeople.freeGreatPeople > 0) {
            NextTurnAutomation.chooseGreatPerson(civView)
            notes += "🌟 Advisor picked your free Great Person"
        }
        val skip = Extensions.advisorSkips(civ)
        if (civ.gameInfo.isReligionEnabled() && "beliefs" !in skip) Engine.callHidden(ReligionAutomation, "chooseReligiousBeliefs", civ)
        if (civ.gameInfo.isEspionageEnabled() && "spies" !in skip) try { EspionageAutomation(civ).automateSpies() } catch (_: Exception) {}
        if ("vote" !in skip) Engine.callHidden(NextTurnAutomation, "tryVoteForDiplomaticVictory", civView)
        if ("alerts" in skip) { try { TradeAutomation.respondToTradeRequests(civ, true) } catch (_: Exception) {}; return notes }
        // AI demands / friendship / trade offers aimed at this player
        // Keep every alert the AI responder knows how to answer (all demand types + friendship offers);
        // drop purely informational ones. Never touch captured-city decisions.
        val answerable = com.unciv.logic.civilization.diplomacy.Demand.entries.map { it.demandAlert }.toSet() +
            AlertType.CityConquered + AlertType.DeclarationOfFriendship
        notes += StatecraftPlus.advisorAnswerInbox(civ) // events + decisions with a default: answered explicitly, not dropped
        civ.popupAlerts.removeAll { it.type !in answerable }
        // Unciv's responder clears ALL alerts afterwards; keep captured-city decisions for the player
        val conquests = civ.popupAlerts.filter { it.type == AlertType.CityConquered }
        Engine.callHidden(NextTurnAutomation, "respondToPopupAlerts", civView)
        civ.popupAlerts.addAll(conquests.filter { c -> civ.popupAlerts.none { it.type == c.type && it.value == c.value } })
        try { TradeAutomation.respondToTradeRequests(civ, true) } catch (_: Exception) {}
        return notes
    }

    fun autoResolveConquests(civ: Civilization) {
        while (true) {
            val city = pendingConquest(civ) ?: break
            resolveConquest(civ, city, "puppet")
        }
    }

    /** Ends the current human's turn and runs AIs until the next human. Returns public events. */
    fun endTurn(game: GameInfo, civ: Civilization): List<String> {
        val events = ArrayList<String>()
        autoResolveConquests(civ)
        events += advisor(civ, fillPicks = true).map { "${civ.civName}: $it" }
        val turnBefore = game.turns
        game.nextTurn()
        if (game.turns != turnBefore) events += "📅 Turn ${game.turns + 1} begins (${yearText(game)})"
        return events
    }

    /**
     * Workaround for an Unciv AI assertion ("City within distance"). If a player founds a city right
     * next to an AI's starting settler, Unciv's settler AI can insist on settling where it stands and
     * throw. After such a failure we let affected AI civs see nearby cities and move the stuck settler
     * to the nearest free tile it can settle on, then the turn is retried.
     */
    fun unstickAISettlers(game: GameInfo): Int {
        var moved = 0
        for (civ in game.civilizations) {
            if (civ.isHuman() || civ.isBarbarian || civ.isSpectator) continue
            for (u in civ.units.getCivUnits().toList()) {
                if (!u.baseUnit.isCityFounder()) continue
                val here = u.getTile()
                for (t in here.getTilesInDistance(8)) if (t.isCityCenter() && !t.isExplored(civ)) t.setExplored(civ, true)
                if (here.canBeSettled(civ)) continue
                val spot = (1..6).asSequence().flatMap { d -> here.getTilesAtDistance(d) }
                    .firstOrNull { it.canBeSettled(civ) && it.civilianUnit == null && it.militaryUnit == null && it.getContinent() == here.getContinent() }
                    ?: continue
                u.removeFromTile()
                u.putInTile(spot)
                moved++
            }
        }
        return moved
    }

    /** Notifications the civ received since its turn started, cleaned for Discord. */
    fun turnNotifications(civ: Civilization, max: Int = 10): List<String> =
        civ.notifications.takeLast(max).map { "• " + clean(it.text) }

    /** May everyone (public board, spectators) see every civ's score? Same settings as Unciv's Rankings page. */
    fun publicStatsVisible(game: GameInfo): Boolean = game.victoryData != null ||
        (game.gameParameters.showCivilizationStats == true && game.gameParameters.showRankings && !game.gameParameters.hideOtherCivilizationStats)

    fun scoreboard(game: GameInfo): List<Pair<Civilization, Int>> =
        game.civilizations.filter { it.isMajorCiv && !it.isSpectator }
            .map { it to it.calculateTotalScore().toInt() }
            .sortedByDescending { it.second }

    fun winner(game: GameInfo): Civilization? = game.civilizations.firstOrNull { it.victoryManager.hasWon() }

    // helpers used by MapRenderer callers
    fun focusTile(civ: Civilization): Tile = civ.getCapital()?.getCenterTile()
        ?: civ.units.getCivUnits().firstOrNull()?.getTile()
        ?: civ.gameInfo.tileMap.values.first()

    @Suppress("unused")
    private val keepImports = listOf(TradeEvaluation::class)
}

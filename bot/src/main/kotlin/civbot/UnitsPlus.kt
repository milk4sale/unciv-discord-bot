package civbot

import com.unciv.Constants
import com.unciv.UncivGame
import com.unciv.logic.automation.civilization.AutomationCache
import com.unciv.logic.automation.civilization.NextTurnAutomation
import com.unciv.logic.automation.unit.UnitAutomation
import com.unciv.logic.civilization.AlertType
import com.unciv.logic.civilization.CityAction
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.DiplomacyAction
import com.unciv.logic.civilization.EspionageAction
import com.unciv.logic.civilization.LocationAction
import com.unciv.logic.civilization.MapUnitAction
import com.unciv.logic.civilization.Notification
import com.unciv.logic.civilization.NotificationAction
import com.unciv.logic.civilization.OverviewAction
import com.unciv.logic.civilization.PolicyAction
import com.unciv.logic.civilization.PopupAlert
import com.unciv.logic.civilization.PromoteUnitAction
import com.unciv.logic.civilization.ReligionAction
import com.unciv.logic.civilization.TechAction
import com.unciv.logic.civilization.managers.ReligionState
import com.unciv.logic.map.HexCoord
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.logic.map.mapunit.PossibleUpgrade
import com.unciv.logic.map.tile.Tile
import com.unciv.models.ruleset.unique.UniqueType
import com.unciv.models.ruleset.unit.Promotion
import com.unciv.view.GameView
import net.dv8tion.jda.api.interactions.components.ActionRow
import java.util.concurrent.ConcurrentHashMap

/**
 * Units & world-screen extras, mirroring Unciv's WorldScreen / UnitTable logic:
 *  - 🔄 swap places with an own unit (Go to falls back to a swap; menu entry per swappable unit)
 *  - 👥 group move (multi-select nearby units, send them one after another)
 *  - destination of moving units (status text + path/marker on the map)
 *  - ⭐ promote along a path to a deeper promotion, 🎖️ default promotions per unit type (per city, like Unciv)
 *  - 🗑️ disband with the gold shown and a confirm step
 *  - ⬆️ upgrade all units of a type
 *  - 🤖 AutoPlay once (military / civilians / both) — never ends the turn
 *  - 📰 News tab: notifications that jump to their unit / city / tile, plus turn-by-turn history
 *  - extra end-turn checklist lines
 */
object UnitsPlus : Extension {
    private const val NEWS = "NEWS"
    private const val GROUP = "UP_GROUP"

    override val tabs = listOf(
        ExtTab(NEWS, "News", "📰", "Notifications: jump to the unit, city or tile; turn-by-turn history"),
        // Reached from Unit actions → "👥 Move a group…" (kept out of the tab list)
        ExtTab(GROUP, "Group move", "👥", "Send several units to one tile") { false },
    )
    override val viewVerbs = setOf("up_news", "up_nolder", "up_nnewer", "up_nlatest", "up_group", "up_gsel", "up_gall", "up_back", "up_disband_cancel")
    override val prompts = mapOf(
        "up_gmove" to Prompt("👥 Move the group to…", "Tile code or city name", "e.g. D5 — units move one after another"),
    )

    override fun onEngineInit() {
        // Unciv keeps only 5 turns of notification history by default; keep more for the 📰 News tab
        val s = UncivGame.Current.settings
        if (s.notificationsLogMaxTurns < 30) s.notificationsLogMaxTurns = 30
    }

    // ================================================================== helpers

    private fun view(u: MapUnit) = GameView(u.civ.gameInfo, u.civ).getMapUnitView(u)
    private fun code(center: Tile, t: Tile): String =
        MapWindow(center.position).code(t.position.x, t.position.y) ?: "${center.aerialDistanceTo(t)} tiles away"

    /** Where the unit is heading: its moveTo target, or the end of the road it's building. */
    fun destinationOf(u: MapUnit): Tile? = when {
        u.isMoving() -> runCatching { u.getMovementDestination() }.getOrNull()
        u.isAutomatingRoadConnection() -> u.automatedRoadConnectionDestination?.let { u.civ.gameInfo.tileMap.getIfTileExistsOrNull(it.x, it.y) }
        else -> null
    }

    /** "moving → D5" (code from [window], the map the player is looking at). */
    fun statusText(u: MapUnit, window: MapWindow?): String {
        val base = GameOps.unitStatus(u)
        val d = destinationOf(u) ?: return base
        val c = window?.code(d.position.x, d.position.y) ?: "${u.getTile().aerialDistanceTo(d)} tiles away"
        return "$base → $c"
    }

    // ================================================================== swap

    /** Swap with the own unit on [target] if Unciv allows it; null if there's nothing to swap with. */
    fun trySwap(u: MapUnit, target: Tile): String? {
        if (target.getUnits().none { it.civ == u.civ && it != u }) return null
        if (!u.movement.canUnitSwapTo(target)) return null
        val other = (if (u.isCivilian()) target.civilianUnit else target.militaryUnit)
            ?: u.getOtherEscortUnit()?.let { e -> if (e.isCivilian()) target.civilianUnit else target.militaryUnit }
        u.movement.swapMoveToTile(target)
        if (u.isExploring() || u.isMoving()) u.action = null
        return "🔄 ${u.label()} swapped places with ${other?.label() ?: "your unit"}."
    }

    private fun swapChoices(u: MapUnit): List<Choice> {
        if (!u.hasMovement() || u.baseUnit.isAirUnit()) return emptyList()
        val tiles = runCatching { u.movement.getUnitSwappableTiles().toList() }.getOrDefault(emptyList())
        return tiles.sortedBy { it.aerialDistanceTo(u.getTile()) }.take(8).map { t ->
            val other = (if (u.isCivilian()) t.civilianUnit else t.militaryUnit) ?: t.getUnits().first { it.civ == u.civ }
            val d = t.aerialDistanceTo(u.getTile())
            Choice("up:swap:${t.position.x},${t.position.y}", "Swap with ${other.label()} at ${code(u.getTile(), t)}",
                "Trade places ($d tile${if (d == 1) "" else "s"} away)", "🔄")
        }
    }

    // ================================================================== promotions

    private fun canPromoteNow(u: MapUnit) = u.promotions.canBePromoted() && u.hasMovement() && u.attacksThisTurn == 0

    /** Shortest chain of not-yet-adopted promotions ending in [target] (like PromotionTree.getPathTo), or empty if unreachable. */
    fun promotionPath(u: MapUnit, target: Promotion): List<Promotion> {
        val cands = u.promotions.getPromotionTreeCandidates().associateBy { it.promotion.name }
        val memo = HashMap<String, List<Promotion>?>()
        fun path(name: String, depth: Int): List<Promotion>? {
            if (depth > 30) return null
            if (name in memo) return memo[name]
            memo[name] = null // loop guard
            val c = cands[name] ?: return null
            val result: List<Promotion>? = when {
                c.isAdopted -> emptyList()
                c.isBlockedByUniques -> null
                c.promotion.prerequisites.isEmpty() -> listOf(c.promotion)
                else -> c.promotion.prerequisites.mapNotNull { path(it, depth + 1) }.minByOrNull { it.size }?.let { it + c.promotion }
            }
            memo[name] = result
            return result
        }
        return path(target.name, 0).orEmpty()
    }

    private fun promoPathChoices(u: MapUnit): List<Choice> {
        if (!canPromoteNow(u)) return emptyList()
        return u.promotions.getPromotionTreeCandidates().asSequence()
            .filter { !it.isAdopted && !it.isBlockedByUniques }
            .mapNotNull { c -> promotionPath(u, c.promotion).takeIf { it.size >= 2 }?.let { c.promotion to it } }
            .sortedWith(compareBy({ it.second.size }, { it.first.name }))
            .map { (p, path) ->
                val affordable = u.promotions.canAffordPromotions(path.count { !it.hasUnique(UniqueType.FreePromotion) })
                Choice("up:promo:${p.name}", "Promote toward ${p.name} (${path.size} steps)",
                    (path.joinToString(" → ") { it.name } + if (affordable) "" else " · as far as XP allows").take(100), "⭐")
            }.toList()
    }

    /** Applies the path to [targetName] one step at a time while the unit may still be promoted. */
    private fun promoteAlong(u: MapUnit, targetName: String): String {
        val target = u.civ.gameInfo.ruleset.unitPromotions[targetName] ?: throw PlayerError("Unknown promotion.")
        if (!canPromoteNow(u)) throw PlayerError("${u.label()} can only be promoted before attacking and while it has moves left.")
        val path = promotionPath(u, target)
        if (path.isEmpty()) throw PlayerError("${u.label()} can't reach $targetName.")
        val done = ArrayList<String>()
        for (p in path) {
            if (!u.promotions.canBePromoted() || u.promotions.getAvailablePromotions().none { it.name == p.name }) break
            if (!p.hasUnique(UniqueType.FreePromotion) && u.promotions.XP < u.promotions.xpForNextPromotion()) break
            view(u).tryAddPromotion(p.name)
            done += p.name
        }
        if (done.isEmpty()) throw PlayerError("${u.label()} doesn't have enough XP for the first step (${path.first().name}).")
        val rest = path.size - done.size
        return "⭐ ${u.label()} promoted: **${done.joinToString(" → ")}**" +
            (if (rest > 0) " ($rest more step(s) to $targetName when it has the XP)." else ".")
    }

    private fun defaultPromoChoices(u: MapUnit): List<Choice> {
        if (!view(u).canSaveDefaultPromotions()) return emptyList()
        val city = u.getTile().getCity() ?: return emptyList()
        val out = ArrayList<Choice>()
        val type = u.baseUnit.name
        if (u.promotions.promotions.isNotEmpty())
            out += Choice("up:defpromo", "Save promotions as default for $type",
                "New ${type}s from ${city.name} start with: ${u.promotions.promotions.joinToString()}".take(100), "🎖️")
        if (city.unitShouldUseSavedPromotion[type] == true)
            out += Choice("up:nodefpromo", "Stop default promotions for $type",
                "${city.name}: ${city.unitToPromotions[type]?.promotions?.joinToString().orEmpty()}".take(100), "🎖️")
        return out
    }

    // ================================================================== disband

    private val pendingDisband = ConcurrentHashMap<String, Int>()
    private fun key(u: MapUnit) = "${u.civ.gameInfo.gameId}:${u.civ.civID}:${u.id}"
    private fun isArmed(u: MapUnit) = pendingDisband[key(u)] == u.civ.gameInfo.turns
    private fun disbandGold(u: MapUnit) = if (u.getTile().getOwner() == u.civ) u.baseUnit.getDisbandGold(u.civ) else 0

    fun disbandText(u: MapUnit): String = disbandGold(u).let {
        if (it > 0) "You get $it gold back (in your territory) · asks to confirm" else "No gold outside your borders · asks to confirm"
    }

    fun armDisband(u: MapUnit): String {
        if (!u.hasMovement()) throw PlayerError("${u.label()} needs moves left to disband.")
        pendingDisband[key(u)] = u.civ.gameInfo.turns
        val gold = disbandGold(u)
        return "🗑️ Disband **${u.label()}**?" + (if (gold > 0) " You'd get **$gold gold**." else " You get no gold outside your borders.") +
            "\n⚠️ Pick **🗑️ CONFIRM disband** at the top of Unit actions."
    }

    fun pendingDisbandChoices(u: MapUnit): List<Choice> {
        if (!isArmed(u) || !u.hasMovement()) return emptyList()
        val gold = disbandGold(u)
        return listOf(
            Choice("do:up_disband", "CONFIRM disband ${u.label()}", if (gold > 0) "+$gold gold · can't be undone" else "No gold · can't be undone", "🗑️"),
            Choice("do:up_disband_cancel", "Keep ${u.label()}", "Cancel disbanding", "✖️"),
        )
    }

    // ================================================================== upgrade all

    /** Units of [u]'s type that could take [upgrade] right now (UnitUpgradeMenu.allUpgradableUnits). */
    private fun upgradableLike(u: MapUnit, upgrade: PossibleUpgrade): List<MapUnit> = u.civ.units.getCivUnits().filter {
        it.baseUnit.name == u.baseUnit.name && it.hasMovement() && it.getTile().getOwner() == it.civ && !it.isEmbarked() &&
            view(it).canUpgradeTo(upgrade.upgradedUnit)
    }.toList()

    private fun affordable(civ: Civilization, upgrade: PossibleUpgrade, n: Int): Boolean {
        if (civ.gold < upgrade.goldCostOfUpgrade * n) return false
        val have = civ.getCivResourcesByName()
        return (upgrade.newResourceRequirements * n).all { (res, amt) -> amt <= (have[res] ?: 0) }
    }

    private fun upgradeAllChoices(u: MapUnit): List<Choice> {
        val ups = runCatching { u.upgrade.getUpgradesOfKind(isSpecial = false) }.getOrDefault(emptyList())
        return ups.mapNotNull { up ->
            val all = upgradableLike(u, up)
            if (all.size < 2 || !affordable(u.civ, up, all.size)) return@mapNotNull null
            Choice("do:up_upgall:${up.upgradedUnit.name}", "Upgrade all ${all.size} ${u.baseUnit.name} → ${up.upgradedUnit.name}",
                "${up.goldCostOfUpgrade * all.size} gold (${up.goldCostOfUpgrade} each)", "⬆️")
        }
    }

    // ================================================================== autoplay once

    /** Unciv AutoPlayMenu "AutoPlay Military/Civilians Once": automate each unit's moves for this turn only. */
    fun autoPlayOnce(civ: Civilization, military: Boolean, civilians: Boolean): String {
        val gv = GameView(civ.gameInfo, civ)
        val isAtWar = civ.isAtWar()
        val cache = AutomationCache()
        val units = civ.units.getCivUnits().filter { (military && it.isMilitary()) || (civilians && it.isCivilian()) }
            .sortedBy { runCatching { NextTurnAutomation.getUnitPriority(gv.getMapUnitView(it), isAtWar, cache) }.getOrDefault(99) }.toList()
        val before = units.associateWith { Triple(it.getTile(), it.currentMovement, it.attacksThisTurn) }
        var errors = 0
        for (u in units) {
            if (u.isDestroyed) continue
            try { UnitAutomation.automateUnitMoves(gv.getMapUnitView(u), cache) } catch (_: Exception) { errors++ }
        }
        var bombards = 0
        if (military) for (city in civ.cities.toList())
            if (runCatching { UnitAutomation.tryBombardEnemy(gv.getCityView(city)) }.getOrDefault(false)) bombards++
        val acted = before.count { (u, b) -> u.isDestroyed || u.getTile() != b.first || u.currentMovement != b.second || u.attacksThisTurn != b.third }
        val what = when { military && civilians -> "all units"; military -> "military units"; else -> "civilian units" }
        return "🤖 AutoPlay once ($what): **$acted** of ${units.size} unit(s) acted" +
            (if (bombards > 0) ", $bombards city bombardment(s)" else "") + (if (errors > 0) " ($errors skipped)" else "") +
            ". Your turn continues — nothing stays automated."
    }

    // ================================================================== unit menu

    override fun unitChoices(u: MapUnit): List<Choice> {
        val out = ArrayList<Choice>()
        out += swapChoices(u)
        out += promoPathChoices(u)
        out += defaultPromoChoices(u)
        out += upgradeAllChoices(u)
        if (u.hasMovement() && !u.baseUnit.isAirUnit()) out += Choice("do:up_group", "Move a group…", "Pick nearby units and send them all to one tile", "👥")
        out += autoPlayChoices()
        return out
    }

    private fun autoPlayChoices() = listOf(
        Choice("do:up_auto:mil", "AutoPlay military once", "The AI moves all military units this turn", "🤖"),
        Choice("do:up_auto:civ", "AutoPlay civilians once", "The AI moves all civilian units this turn", "🤖"),
        Choice("do:up_auto:all", "AutoPlay all units once", "Military and civilians; you still end the turn", "🤖"),
    )

    override fun doUnitChoice(u: MapUnit, id: String): String? {
        if (!id.startsWith("up:")) return null
        val parts = id.split(":", limit = 3)
        return when (parts[1]) {
            "swap" -> {
                val (x, y) = parts[2].split(",").map { it.toInt() }
                val t = u.civ.gameInfo.tileMap.getIfTileExistsOrNull(x, y) ?: throw PlayerError("That tile doesn't exist.")
                trySwap(u, t) ?: throw PlayerError("${u.label()} can't swap there any more.")
            }
            "promo" -> promoteAlong(u, parts[2])
            "defpromo" -> {
                if (!view(u).trySaveDefaultPromotions()) throw PlayerError("Stand in one of your cities to save default promotions.")
                val city = u.getTile().getCity()!!
                city.unitToPromotions[u.baseUnit.name] = u.promotions.clone() // own copy: later promotions of this unit don't change it
                "🎖️ New **${u.baseUnit.name}s** built in ${city.name} start with ${u.promotions.promotions.joinToString()} (when they have the XP)."
            }
            "nodefpromo" -> {
                val city = u.getTile().getCity() ?: throw PlayerError("Stand in one of your cities.")
                city.unitShouldUseSavedPromotion.remove(u.baseUnit.name); city.unitToPromotions.remove(u.baseUnit.name)
                "🎖️ ${city.name} no longer applies default promotions to ${u.baseUnit.name}s."
            }
            else -> null
        }
    }

    // ================================================================== verbs

    private fun group(st: PanelState): MutableList<Int> {
        @Suppress("UNCHECKED_CAST")
        return st.ext.getOrPut("up_group") { ArrayList<Int>() } as MutableList<Int>
    }

    /** Units the group view offers: own non-air units near the anchor unit. */
    private fun groupCandidates(civ: Civilization, anchor: MapUnit?): List<MapUnit> {
        val center = anchor?.getTile() ?: return emptyList()
        return civ.units.getCivUnits().filter { !it.baseUnit.isAirUnit() && it.getTile().aerialDistanceTo(center) <= 4 }
            .sortedWith(compareBy({ it != anchor }, { it.getTile().aerialDistanceTo(center) }, { it.baseUnit.name })).toList()
    }

    private fun announceConquests(ctx: ActionCtx, citiesBefore: Set<String>) {
        Features.flagNewConquests(ctx.civ, citiesBefore)
        for (c in ctx.civ.cities) if (c.id !in citiesBefore) ctx.s.meta.addEvent("🏰 ${ctx.civ.civName} captured ${c.name}!")
    }

    override fun handle(verb: String, ctx: ActionCtx): Boolean {
        val civ = ctx.civ; val st = ctx.st
        when (verb) {
            "up_disband" -> {
                ctx.requireTurn()
                val u = ctx.selectedUnit()
                if (!isArmed(u)) throw PlayerError("Pick **🗑️ Disband unit…** first.")
                if (!u.hasMovement()) throw PlayerError("${u.label()} needs moves left to disband.")
                pendingDisband.remove(key(u))
                val name = u.label(); val goldBefore = civ.gold
                view(u).tryDisband()
                civ.updateStatsForNextTurn() // less upkeep
                st.flash += "🗑️ $name disbanded" + (if (civ.gold > goldBefore) " (+${civ.gold - goldBefore} gold)." else " (no gold outside your borders).")
                st.unitId = GameOps.nextIdleUnit(civ, null)?.id
                ctx.changed = true
            }
            "up_disband_cancel" -> { val u = ctx.selectedUnit(); pendingDisband.remove(key(u)); st.flash += "✖️ ${u.label()} stays." }
            "up_upgall" -> {
                ctx.requireTurn()
                val u = ctx.selectedUnit()
                val target = ctx.args.firstOrNull() ?: throw PlayerError("Pick an upgrade.")
                val up = u.upgrade.getUpgradesOfKind(isSpecial = false).firstOrNull { it.upgradedUnit.name == target }
                    ?: throw PlayerError("${u.label()} can't upgrade to $target.")
                val all = upgradableLike(u, up)
                if (all.isEmpty()) throw PlayerError("No ${u.baseUnit.name} can upgrade right now (they need moves left and to stand in your territory).")
                if (!affordable(civ, up, all.size)) throw PlayerError("Upgrading all ${all.size} costs ${up.goldCostOfUpgrade * all.size} gold (or more resources than you have).")
                val tile = u.getTile(); val oldName = u.baseUnit.name
                var n = 0
                for (x in all) if (view(x).tryUpgradeTo(up.upgradedUnit)) n++
                st.flash += "⬆️ Upgraded **$n** $oldName → **${up.upgradedUnit.name}**."
                tile.getUnits().firstOrNull { it.civ == civ && it.baseUnit == up.upgradedUnit }?.let { st.unitId = it.id }
                ctx.changed = true
            }
            "up_auto" -> {
                ctx.requireTurn()
                val kind = ctx.args.firstOrNull() ?: "all"
                val citiesBefore = civ.cities.map { it.id }.toSet()
                st.flash += autoPlayOnce(civ, kind != "civ", kind != "mil")
                announceConquests(ctx, citiesBefore)
                if (st.unitId?.let { GameOps.unitById(civ, it) } == null) st.unitId = GameOps.nextIdleUnit(civ, null)?.id
                ctx.changed = true
            }
            // ---- group move
            "up_group" -> {
                val u = ctx.selectedUnit()
                val g = group(st); g.clear(); g += u.id
                st.tab = GROUP
            }
            "up_gsel" -> {
                val picks = ctx.value.orEmpty().split("\n").filter { it.startsWith("u:") }.mapNotNull { it.removePrefix("u:").toIntOrNull() }
                val shown = st.visible["up_gsel"].orEmpty().mapNotNull { it.removePrefix("u:").toIntOrNull() }
                val g = group(st)
                val keep = g.filter { it !in shown } // picks on other dropdown pages stay
                g.clear(); g += (keep + picks).distinct()
            }
            "up_gall" -> {
                val anchor = st.unitId?.let { GameOps.unitById(civ, it) }
                val g = group(st); g.clear(); g += groupCandidates(civ, anchor).filter { it.hasMovement() }.map { it.id }
            }
            "up_back" -> st.tab = Tab.UNITS.name
            "up_gmove" -> {
                ctx.requireTurn()
                val target = ctx.tile(ctx.value?.trim().orEmpty().ifEmpty { throw PlayerError("Type a tile code, like D5.") })
                val units = group(st).mapNotNull { GameOps.unitById(civ, it) }
                if (units.isEmpty()) throw PlayerError("Pick the units of the group first.")
                val citiesBefore = civ.cities.map { it.id }.toSet()
                val lines = ArrayList<String>()
                var moved = 0
                for (u in units) { // one after another, like Unciv's WorldMapHolder.moveUnitToTargetTile
                    if (!u.hasMovement()) { lines += "${u.label()}: no moves left"; continue }
                    lines += try { GameOps.moveUnit(u, target, allowSwap = false).also { moved++ } } catch (e: PlayerError) { "${u.label()}: ${e.message.orEmpty().removePrefix(u.label() + " ")}" }
                }
                announceConquests(ctx, citiesBefore)
                val where = st.window?.code(target.position.x, target.position.y) ?: ctx.value!!.trim()
                st.flash += "👥 Group → **$where** ($moved of ${units.size} moved):\n" + lines.joinToString("\n") { "• $it" }
                ctx.changed = moved > 0
            }
            // ---- news
            "up_news" -> jump(ctx, ctx.value ?: throw PlayerError("Pick a notification."))
            "up_nolder" -> st.ext["up_np"] = ((st.ext["up_np"] as? Int ?: 0) + 1).coerceAtMost(civ.notificationsLog.size)
            "up_nnewer" -> st.ext["up_np"] = ((st.ext["up_np"] as? Int ?: 0) - 1).coerceAtLeast(0)
            "up_nlatest" -> st.ext["up_np"] = 0
            else -> return false
        }
        return true
    }

    // ================================================================== 📰 news

    private class NewsTurn(val key: String, val turn: Int, val notes: List<Notification>)

    /** This turn's notifications first, then the history (newest first). */
    private fun newsTurns(civ: Civilization): List<NewsTurn> =
        listOf(NewsTurn("c", civ.gameInfo.turns, civ.notifications.toList())) +
            civ.notificationsLog.withIndex().reversed().map { (i, l) -> NewsTurn("l$i", l.turn, l.notifications.toList()) }

    /** Notification actions keep their targets private; read them like the save file would. */
    private fun field(o: Any, name: String): Any? {
        var c: Class<*>? = o.javaClass
        while (c != null) {
            val f = c.declaredFields.firstOrNull { it.name == name }
            if (f != null) return try { f.isAccessible = true; f.get(o) } catch (_: Exception) { null }
            c = c.superclass
        }
        return null
    }

    /** Where an action leads, as a short label, or null if the bot can't follow it. */
    private fun actionLabel(civ: Civilization, a: NotificationAction): String? = when (a) {
        is MapUnitAction -> (field(a, "id") as? Int)?.let { civ.units.getUnitById(it) }?.let { "unit ${it.label()}" } ?: "map tile"
        is PromoteUnitAction -> civ.units.getUnitById(a.id)?.let { "promote ${it.label()}" }
        is CityAction -> (field(a, "city") as? HexCoord)?.let { civ.gameInfo.tileMap.getIfTileExistsOrNull(it.x, it.y)?.getCity() }?.let { "city ${it.name}" } ?: "map tile"
        is LocationAction -> "map tile"
        is TechAction -> "🔬 Research tab"
        is PolicyAction -> "📜 Policies tab"
        is ReligionAction -> "🙏 Religion tab"
        is DiplomacyAction -> "🤝 Diplomacy tab"
        is EspionageAction -> "🕵️ Espionage tab"
        is OverviewAction -> "📊 Overview tab"
        else -> null
    }

    private fun jumpable(civ: Civilization, n: Notification) = n.actions.filter { actionLabel(civ, it) != null }

    private fun emojiOf(a: NotificationAction) = when (a) {
        is MapUnitAction, is PromoteUnitAction -> "🪖"
        is CityAction -> "🏙️"
        is LocationAction -> "📍"
        else -> "➡️"
    }

    private fun newsChoices(civ: Civilization): List<Choice> = newsTurns(civ).flatMap { nt ->
        nt.notes.withIndex().reversed().mapNotNull { (i, n) ->
            val acts = jumpable(civ, n)
            if (acts.isEmpty()) return@mapNotNull null
            Choice("up:n:${nt.key}:$i", GameOps.clean(n.text).replace("\n", " ").take(100).ifEmpty { "Notification" },
                "Turn ${nt.turn + 1} · → ${actionLabel(civ, acts.first())}" + (if (acts.size > 1) " (+${acts.size - 1}, pick again to cycle)" else ""),
                emojiOf(acts.first()))
        }
    }

    private fun jump(ctx: ActionCtx, value: String) {
        val civ = ctx.civ; val st = ctx.st; val g = ctx.game
        val parts = value.removePrefix("up:n:").split(":")
        val nt = newsTurns(civ).firstOrNull { it.key == parts.getOrNull(0) } ?: throw PlayerError("That notification is gone.")
        val n = parts.getOrNull(1)?.toIntOrNull()?.let { nt.notes.getOrNull(it) } ?: throw PlayerError("That notification is gone.")
        val acts = jumpable(civ, n)
        if (acts.isEmpty()) throw PlayerError("Nothing to show for that one.")
        // Several targets: each pick shows the next one (Unciv's round-robin)
        val rrKey = "up_rr:$value"
        val idx = (st.ext[rrKey] as? Int ?: 0) % acts.size
        st.ext[rrKey] = idx + 1
        fun showTile(pos: HexCoord) { st.tab = Tab.MAP.name; st.wholeMap = false; st.mapCenter = pos; st.inspect = pos }
        fun showUnit(id: Int?, pos: HexCoord?) {
            val u = id?.let { civ.units.getUnitById(it) }
            when {
                u != null -> { st.unitId = u.id; st.tab = Tab.UNITS.name }
                pos != null -> showTile(pos)
                else -> throw PlayerError("That unit is gone.")
            }
        }
        when (val a = acts[idx]) {
            is MapUnitAction -> showUnit(field(a, "id") as? Int, field(a, "location") as? HexCoord)
            is PromoteUnitAction -> showUnit(a.id, field(a, "location") as? HexCoord)
            is CityAction -> {
                val pos = field(a, "city") as? HexCoord ?: throw PlayerError("That city is gone.")
                val city = g.tileMap.getIfTileExistsOrNull(pos.x, pos.y)?.getCity()
                if (city != null && city.civ == civ) { st.cityId = city.id; st.tab = Tab.CITIES.name } else showTile(pos)
            }
            is LocationAction -> showTile(field(a, "location") as? HexCoord ?: throw PlayerError("No location."))
            is TechAction -> st.tab = Tab.TECH.name
            is PolicyAction -> st.tab = Tab.POLICIES.name
            is ReligionAction -> st.tab = Tab.RELIGION.name
            is DiplomacyAction -> {
                val name = field(a, "otherCivName") as? String
                val o = g.civilizations.firstOrNull { it.civID == name || it.civName == name }
                when {
                    o != null && o.isCityState -> { st.cityStateId = o.civID; st.tab = Tab.CITYSTATES.name }
                    o != null -> { st.otherCivId = o.civID; st.tab = Tab.DIPLOMACY.name }
                    else -> st.tab = Tab.DIPLOMACY.name
                }
            }
            is EspionageAction -> st.tab = if (Extensions.tab("ESPIONAGE") != null && g.isEspionageEnabled()) "ESPIONAGE" else Tab.OVERVIEW.name
            is OverviewAction -> st.tab = Tab.OVERVIEW.name
        }
        st.flash += "📰 ${GameOps.clean(n.text).take(200)}"
    }

    private fun renderNews(t: TabCtx) {
        val civ = t.civ
        val turns = newsTurns(civ)
        val page = (t.st.ext["up_np"] as? Int ?: 0).coerceIn(0, turns.size - 1)
        t.st.ext["up_np"] = page
        val nt = turns[page]
        val lines = nt.notes.reversed().map { n -> (if (jumpable(civ, n).isNotEmpty()) "📍 " else "• ") + GameOps.clean(n.text).replace("\n", " ") }
        val body = StringBuilder()
        var shown = 0
        for (l in lines) { if (body.length + l.length + 40 > 1024) break; body.append(l).append('\n'); shown++ }
        if (shown < lines.size) body.append("_…and ${lines.size - shown} more_")
        val title = (if (page == 0) "📰 This turn (turn ${nt.turn + 1})" else "📰 Turn ${nt.turn + 1}") + " — page ${page + 1}/${turns.size}"
        t.eb.addField(title, body.toString().trim().ifEmpty { "No notifications." }.take(1024), false)
        val older = turns.drop(1).take(8).joinToString(" · ") { "T${it.turn + 1}: ${it.notes.size}" }
        if (older.isNotEmpty()) t.eb.addField("🗂️ History (${turns.size - 1} earlier turn(s))",
            "$older\n_📍 = pick it in the dropdown to jump there. ◀️ Older / ▶️ Newer flip through the turns._", false)
        val choices = newsChoices(civ)
        t.rows += ActionRow.of(t.select("up_news", if (choices.isEmpty()) "Nothing to jump to" else "📰 Jump to… (${choices.size})", choices))
        t.endRow += t.button("up_nolder", "Older", "◀️", enabled = page < turns.size - 1)
        t.endRow += t.button("up_nnewer", "Newer", "▶️", enabled = page > 0)
        t.endRow += t.button("up_nlatest", "Latest", "⏩", enabled = page > 0)
        t.includeAdvisor = false
        t.worldMap()
    }

    // ================================================================== 👥 group view

    private fun renderGroup(t: TabCtx) {
        val civ = t.civ
        val anchor = t.st.unitId?.let { GameOps.unitById(civ, it) } ?: GameOps.unitsOf(civ).firstOrNull()
        val g = group(t.st)
        g.retainAll { id -> GameOps.unitById(civ, id) != null }
        val center = anchor?.getTile() ?: GameOps.focusTile(civ)
        val window = MapWindow(center.position)
        fun codeIn(u: MapUnit) = window.code(u.getTile().position.x, u.getTile().position.y) ?: "off-map"
        val members = g.mapNotNull { GameOps.unitById(civ, it) }
        t.eb.addField("👥 Group move (${members.size} picked)",
            ((if (members.isEmpty()) "Pick units below (within 4 tiles of ${anchor?.label() ?: "your unit"})."
            else members.joinToString("\n") { u -> "${GameOps.unitEmoji(u)} ${u.label()} — ${codeIn(u)} · 👣 ${GameOps.movesText(u)}" }) +
                "\n_🎯 **Move group to…** sends them one after another. Lighter tiles = picked units._").take(1024), false)
        t.mapAround(center, Overlay(selectedUnit = anchor, reachable = members.map { it.getTile() }.toSet()))
        val choices = groupCandidates(civ, anchor).map { u ->
            Choice("u:${u.id}", "${u.label()} — ${statusText(u, window)}", "${codeIn(u)} · ${GameOps.movesText(u)} moves · ❤️${u.health}", GameOps.unitEmoji(u))
        }
        t.rows += ActionRow.of(t.multiSelect("up_gsel", "Pick the group's units…", choices, g.map { "u:$it" }))
        t.rows += ActionRow.of(t.select("up_autosel", "🤖 AutoPlay once…", autoPlayChoices(), enabled = t.myTurn))
        t.endRow += t.askButton("up_gmove", "Move group to…", "🎯", 1, t.myTurn && members.isNotEmpty())
        t.endRow += t.button("up_gall", "All nearby", "✅")
        t.endRow += t.button("up_back", "Units", "◀️")
        t.includeAdvisor = false
    }

    override fun renderTab(tabId: String, t: TabCtx) {
        when (tabId) {
            NEWS -> renderNews(t)
            GROUP -> renderGroup(t)
        }
    }

    // ================================================================== end-turn checklist

    /** Extra lines for GameOps.turnWarnings (Unciv NextTurnAction choices the bot didn't list yet). */
    fun extraTurnWarnings(civ: Civilization): List<String> {
        val w = ArrayList<String>()
        val g = civ.gameInfo
        if (civ.greatPeople.freeGreatPeople > 0) w += "Choose your free **Great Person** (📊 Overview tab)"
        if (g.isReligionEnabled()) {
            val rm = civ.religionManager
            if (Features.pantheonAvailable(civ)) w += "You can found a **pantheon** (🙏 Religion tab)"
            when (rm.religionState) {
                ReligionState.FoundingReligion -> w += "**Found your religion** — name it and pick beliefs (🙏 Religion tab)"
                ReligionState.EnhancingReligion -> w += "**Enhance your religion** — pick beliefs (🙏 Religion tab)"
                else -> if (rm.hasFreeBeliefs()) w += "**Reform your religion** — free belief(s) to pick (✨ Beliefs tab)"
            }
        }
        if (civ.isMajorCiv && runCatching { civ.mayVoteForDiplomaticVictory() }.getOrDefault(false)) w += "Cast your **World Congress** vote (🗳️ World Congress tab)"
        if (g.isEspionageEnabled()) {
            val idle = civ.espionageManager.getIdleSpies().size
            if (idle > 0) w += "**$idle** idle spy/spies to send out (🕵️ Espionage tab)"
        }
        val decisionTypes = com.unciv.logic.civilization.diplomacy.Demand.entries.map { it.demandAlert }.toSet() +
            AlertType.SpyingOnUsDespiteOurPromise + AlertType.DeclarationOfFriendship
        val inbox = civ.tradeRequests.size + civ.popupAlerts.count { it.type in decisionTypes }
        if (inbox > 0) w += "**$inbox** decision(s) waiting in the 📨 Inbox"
        return w
    }

    // ================================================================== self-test

    override fun selfTest(t: SelfTestCtx) {
        val a = t.alice
        val st = t.ctl.state(t.s.meta.id, a.userId)
        fun tick(label: String) = t.act(a, "refresh", emptyList(), null, label)
        fun say(msg: String) = println("  [UnitsPlus] $msg")
        fun last() = st.flash.lastOrNull().orEmpty().also { if (it.isNotEmpty()) say("> " + it.replace("\n", " | ").take(300)) }
        val created = ArrayList<MapUnit>()

        // Free land tiles in Alice's territory around her capital
        val (capTile, spots) = t.eng { g ->
            val civ = GameOps.civOf(g, a)
            val c = civ.getCapital()!!.getCenterTile()
            civ.addGold(5000)
            c to c.getTilesInDistance(5).filter { it.isLand && !it.isImpassible() && it.getOwner() == civ && !it.isCityCenter() }
                .sortedBy { it.aerialDistanceTo(c) }.toList()
        }
        fun freeSpot(except: Tile? = null): Tile? = t.eng { spots.firstOrNull { it.getUnits().none() && it != except } }
        fun spawn(name: String, tile: Tile): MapUnit = t.eng { g ->
            val civ = GameOps.civOf(g, a)
            val u = civ.units.addUnit(name, civ.getCapital())!!
            u.removeFromTile(); u.putInTile(tile); u.currentMovement = u.getMaxMovement().toFloat(); u.due = true
            civ.cache.updateViewableTiles(); created += u; u
        }
        fun drop(us: List<MapUnit>) = t.eng { _ -> us.forEach { u -> if (u.civ.units.getCivUnits().contains(u)) u.destroy() } }
        fun refill(vararg us: MapUnit) = t.eng { us.forEach { it.currentMovement = it.getMaxMovement().toFloat(); it.attacksThisTurn = 0 } }
        fun codeOf(center: Tile, tile: Tile) = MapWindow(center.position).code(tile.position.x, tile.position.y)!!
        say("${spots.size} land tiles in territory")

        // ---- 1. swap (menu entry + Go to fallback)
        val pair = t.eng { spots.filter { it.getUnits().none() }.let { free -> free.firstNotNullOfOrNull { s1 -> free.firstOrNull { it.aerialDistanceTo(s1) == 1 }?.let { s1 to it } } } }
        if (pair != null) {
            val w1 = spawn("Warrior", pair.first); val w2 = spawn("Warrior", pair.second)
            t.act(a, "tab", emptyList(), "UNITS", null); t.act(a, "selunit", emptyList(), "u:${w1.id}", null); t.render(a, null)
            val opt = t.eng { GameOps.unitChoices(w1) }.firstOrNull { it.id.startsWith("up:swap:") }
            say("swap option: ${opt?.label}")
            if (opt != null && t.act(a, "uact", emptyList(), opt.id, null) && t.eng { w1.getTile() == pair.second && w2.getTile() == pair.first }) { last(); tick("swap units (Unit actions)") }
            refill(w1, w2)
            t.act(a, "selunit", emptyList(), "u:${w1.id}", null); t.render(a, null)
            if (t.act(a, "goto", emptyList(), codeOf(w1.getTile(), w2.getTile()), null) && last().startsWith("🔄") &&
                t.eng { w1.getTile() == pair.first && w2.getTile() == pair.second }) tick("go to an own unit swaps places")
            drop(listOf(w1, w2))
        } else say("no adjacent free tiles for the swap test")

        // ---- 2. group move
        val gs = t.eng { spots.filter { it.getUnits().none() }.take(3) }
        if (gs.size == 3) {
            val us = gs.map { spawn("Spearman", it) }
            t.act(a, "selunit", emptyList(), "u:${us[0].id}", null)
            if (t.act(a, "uact", emptyList(), "do:up_group", null) && st.tab == GROUP) {
                t.act(a, "up_gsel", emptyList(), us.joinToString("\n") { "u:${it.id}" }, null)
                t.render(a, "up-group")
                val dest = t.eng { _ ->
                    val w = st.window!!
                    capTile.getTilesAtDistance(2).firstOrNull { tl -> tl.isLand && !tl.isImpassible() && tl.getUnits().none() && !tl.isCityCenter() &&
                        w.code(tl.position.x, tl.position.y) != null }?.let { w.code(it.position.x, it.position.y) }
                }
                val before = t.eng { us.map { it.getTile() } }
                if (dest != null && t.act(a, "up_gmove", emptyList(), dest, null)) {
                    last()
                    val movedN = t.eng { us.indices.count { us[it].getTile() != before[it] || us[it].isMoving() } }
                    say("group: $movedN/3 moved or heading to $dest")
                    if (movedN >= 2) tick("group move (👥)")
                }
            }
            else say("group: couldn't open the group view (tab=${st.tab}, flash=${st.flash})")
            t.act(a, "up_back", emptyList(), null, null)
            drop(us)
        } else say("group: only ${gs.size} free tiles")

        // ---- 3. destination shown (status + map marker)
        val scout = freeSpot()?.let { spawn("Scout", it) }
        if (scout != null) {
            t.act(a, "selunit", emptyList(), "u:${scout.id}", null); t.render(a, null)
            val far = t.eng { _ ->
                val w = st.window!!
                (6 downTo 4).asSequence().flatMap { d -> scout.getTile().getTilesAtDistance(d) }
                    .filter { it.isLand && !it.isImpassible() && it.isExplored(scout.civ) && w.code(it.position.x, it.position.y) != null && it.getUnits().none() }
                    .firstOrNull { runCatching { scout.movement.canReach(it) }.getOrDefault(false) }?.let { w.code(it.position.x, it.position.y) }
            }
            if (far != null && t.act(a, "goto", emptyList(), far, null)) {
                last()
                t.act(a, "selunit", emptyList(), "u:${scout.id}", null)
                val (moving, status) = t.eng { scout.isMoving() to statusText(scout, MapWindow(scout.getTile().position)) }
                say("scout heading to $far: '$status'")
                val fields = t.eng { Panel.render(t.s, a, st) }.embed.fields
                val field = fields.firstOrNull { it.name?.contains("Scout") == true }?.value.orEmpty()
                if (moving && status.contains("→") && field.contains("→")) tick("moving unit shows its destination")
                t.render(a, "up-destination")
            } else say("no far tile for the destination test ($far)")
        }

        // ---- 4 + 5. promotion rule, promote along a path, default promotions
        val vetSpot = freeSpot()
        if (vetSpot != null) {
            val vet = spawn("Swordsman", vetSpot)
            t.act(a, "selunit", emptyList(), "u:${vet.id}", null)
            t.eng { vet.promotions.XP = 400; vet.attacksThisTurn = 1 }
            val blocked = t.eng { GameOps.unitChoices(vet) }.none { it.id.startsWith("promo:") || it.id.startsWith("up:promo:") }
            t.eng { vet.attacksThisTurn = 0 }
            val allowed = t.eng { GameOps.unitChoices(vet) }.any { it.id.startsWith("promo:") }
            if (blocked && allowed) tick("promotions only before attacking (Unciv rule)")
            val pathOpt = t.eng { GameOps.unitChoices(vet) }.filter { it.id.startsWith("up:promo:") }
                .maxByOrNull { it.label.substringAfterLast("(").substringBefore(" ").toIntOrNull() ?: 0 }
            say("path option: ${pathOpt?.label} — ${pathOpt?.description}")
            if (pathOpt != null && t.act(a, "uact", emptyList(), pathOpt.id, null) && t.eng { vet.promotions.promotions.size } >= 2) { last(); tick("promote along a path") }
            refill(vet)
            val saved = t.act(a, "uact", emptyList(), "up:defpromo", null) && t.eng {
                vet.getTile().getCity()!!.let { c -> c.unitShouldUseSavedPromotion[vet.baseUnit.name] == true && c.unitToPromotions[vet.baseUnit.name]?.promotions == vet.promotions.promotions }
            }
            if (saved) { last(); tick("save default promotions for a unit type") }
            else say("defpromo failed: ${st.flash} choices=${t.eng { GameOps.unitChoices(vet).map { it.id } }}")
            t.act(a, "uact", emptyList(), "up:nodefpromo", null)
            drop(listOf(vet))
        }

        // ---- 6. disband with gold + confirm
        val junk = freeSpot()?.let { spawn("Warrior", it) }
        if (junk != null) {
            t.act(a, "selunit", emptyList(), "u:${junk.id}", null)
            val (desc, gold) = t.eng { GameOps.unitChoices(junk).firstOrNull { it.id == "disband" }?.description to junk.baseUnit.getDisbandGold(junk.civ) }
            say("disband entry: $desc")
            val goldBefore = t.eng { g -> GameOps.civOf(g, a).gold }
            if (t.act(a, "uact", emptyList(), "disband", null) && t.eng { junk.civ.units.getCivUnits().contains(junk) } &&
                t.eng { GameOps.unitChoices(junk) }.any { it.id == "do:up_disband" }) {
                last()
                if (t.act(a, "uact", emptyList(), "do:up_disband", null) &&
                    t.eng { g -> !junk.civ.units.getCivUnits().contains(junk) && GameOps.civOf(g, a).gold - goldBefore == gold }) {
                    last(); tick("disband: shows gold, asks to confirm")
                }
            }
            freeSpot()?.let { spawn("Warrior", it) }?.let { tired ->
                t.eng { tired.currentMovement = 0f }
                if (t.eng { GameOps.unitChoices(tired) }.none { it.id == "disband" }) tick("disband needs moves left")
                drop(listOf(tired))
            }
        }

        // ---- 7. wake clears the road destination
        freeSpot()?.let { spawn("Worker", it) }?.let { worker ->
            t.eng { worker.automatedRoadConnectionDestination = capTile.position; worker.action = Constants.connectRoadAction; worker.automated = true }
            t.act(a, "selunit", emptyList(), "u:${worker.id}", null)
            if (t.act(a, "uact", emptyList(), "wake", null) && t.eng { worker.automatedRoadConnectionDestination == null && !worker.isAutomated() })
                tick("wake clears the road destination")
            drop(listOf(worker))
        } ?: say("wake: no free tile")

        // ---- 8. upgrade all of a type (Spearman → Pikeman)
        t.eng { g -> GameOps.civOf(g, a).tech.addTechnology("Civil Service", false) }
        val s1 = freeSpot(); val s2 = freeSpot(s1)
        if (s1 != null && s2 != null) {
            val spears = listOf(spawn("Spearman", s1), spawn("Spearman", s2))
            t.act(a, "selunit", emptyList(), "u:${spears[0].id}", null)
            val opt = t.eng { GameOps.unitChoices(spears[0]) }.firstOrNull { it.id.startsWith("do:up_upgall:") }
            say("upgrade-all option: ${opt?.label} — ${opt?.description}")
            val pikesBefore = t.eng { g -> GameOps.civOf(g, a).units.getCivUnits().count { it.baseUnit.name == "Pikeman" } }
            if (opt != null && t.act(a, "uact", emptyList(), opt.id, null)) {
                last()
                val pikes = t.eng { g -> GameOps.civOf(g, a).units.getCivUnits().filter { it.baseUnit.name == "Pikeman" }.toList() }
                created += pikes
                if (pikes.size - pikesBefore >= 2) tick("upgrade all units of a type")
            }
        } else say("upgrade all: no free tiles")

        // ---- 9. AutoPlay once (never ends the turn)
        val turnCiv = t.eng { g -> g.currentPlayerCiv.civID }
        for ((k, label) in listOf("mil" to "AutoPlay military once", "civ" to "AutoPlay civilians once", "all" to "AutoPlay all units once")) {
            if (t.act(a, "uact", emptyList(), "do:up_auto:$k", null) && last().startsWith("🤖") && t.eng { g -> g.currentPlayerCiv.civID } == turnCiv) tick(label)
        }

        // ---- 10. 📰 News: jump to unit / city / tile, history paging
        val target = scout ?: created.first()
        val locTile = spots.last()
        val (unitNote, cityNote, locNote) = t.eng { g ->
            val civ = GameOps.civOf(g, a)
            val cap = civ.getCapital()!!
            civ.addNotification("Selftest: your unit reports in", MapUnitAction(target), Notification.NotificationCategory.Units)
            civ.addNotification("Selftest: ${cap.name} grew", CityAction(cap.location), Notification.NotificationCategory.Cities)
            civ.addNotification("Selftest: something on the map", LocationAction(locTile.position), Notification.NotificationCategory.General)
            // an earlier turn, to exercise history paging
            civ.notificationsLog.add(Civilization.NotificationsLog(g.turns - 1).apply {
                notifications.add(Notification("Selftest: last turn's news", arrayOf(), listOf(LocationAction(capTile.position)), Notification.NotificationCategory.General))
            })
            val n = civ.notifications.size
            Triple("up:n:c:${n - 3}", "up:n:c:${n - 2}", "up:n:c:${n - 1}")
        }
        t.act(a, "tab", emptyList(), NEWS, null); t.render(a, "up-news")
        val opts = t.eng { g -> newsChoices(GameOps.civOf(g, a)) }
        say("news options: ${opts.size} (${opts.take(4).joinToString { it.label }})")
        if (t.act(a, "up_news", emptyList(), unitNote, null) && st.tab == Tab.UNITS.name && st.unitId == target.id) tick("news: jump to a unit")
        t.act(a, "tab", emptyList(), NEWS, null)
        val capId = t.eng { g -> GameOps.civOf(g, a).getCapital()!!.id }
        if (t.act(a, "up_news", emptyList(), cityNote, null) && st.tab == Tab.CITIES.name && st.cityId == capId) tick("news: jump to a city")
        t.act(a, "tab", emptyList(), NEWS, null)
        if (t.act(a, "up_news", emptyList(), locNote, null) && st.tab == Tab.MAP.name && st.mapCenter == locTile.position) tick("news: jump to a map tile")
        t.act(a, "tab", emptyList(), NEWS, null)
        if (t.act(a, "up_nolder", emptyList(), null, null) && st.ext["up_np"] == 1) {
            t.render(a, "up-news-history")
            val f = t.eng { Panel.render(t.s, a, st) }.embed.fields.firstOrNull { it.name?.startsWith("📰 Turn") == true }
            if (f?.value?.contains("last turn's news") == true) tick("news: turn-by-turn history paging")
            t.act(a, "up_nlatest", emptyList(), null, null)
        }

        // ---- 11. end-turn checklist
        val warnings = t.eng { g ->
            val civ = GameOps.civOf(g, a)
            val gpBefore = civ.greatPeople.freeGreatPeople
            civ.greatPeople.freeGreatPeople = 1
            val other = g.civilizations.first { it != civ && it.isMajorCiv && it.isAlive() }
            civ.popupAlerts.add(PopupAlert(AlertType.DeclarationOfFriendship, other.civID))
            val voteFlag = civ.flagsCountdown["TurnsTillNextDiplomaticVote"]
            civ.flagsCountdown["TurnsTillNextDiplomaticVote"] = 0
            val w = GameOps.turnWarnings(civ)
            civ.greatPeople.freeGreatPeople = gpBefore
            civ.popupAlerts.removeAll { it.type == AlertType.DeclarationOfFriendship && it.value == other.civID }
            if (voteFlag == null) civ.flagsCountdown.remove("TurnsTillNextDiplomaticVote") else civ.flagsCountdown["TurnsTillNextDiplomaticVote"] = voteFlag
            w
        }
        say("warnings: ${warnings.joinToString(" | ")}")
        if (warnings.any { "Great Person" in it } && warnings.any { "Inbox" in it }) tick("end-turn checklist: great person + inbox")
        if (warnings.any { "World Congress" in it }) tick("end-turn checklist: World Congress vote")
        val wSpy = t.eng { g ->
            val civ = GameOps.civOf(g, a)
            val was = g.gameParameters.espionageEnabled
            g.gameParameters.espionageEnabled = true
            val hadSpies = civ.espionageManager.spyList.size
            runCatching { civ.espionageManager.addSpy() }
            val w = GameOps.turnWarnings(civ)
            while (civ.espionageManager.spyList.size > hadSpies) civ.espionageManager.spyList.removeAt(civ.espionageManager.spyList.size - 1)
            g.gameParameters.espionageEnabled = was
            w
        }
        if (wSpy.any { "idle spy" in it }) tick("end-turn checklist: idle spies")

        // ---- clean up: remove the units this test made, and its notifications
        t.eng { g ->
            created.forEach { u -> if (u.civ.units.getCivUnits().contains(u)) u.destroy() }
            val civ = GameOps.civOf(g, a)
            civ.notifications.removeAll { it.text.startsWith("Selftest:") }
            civ.notificationsLog.removeAll { l -> l.notifications.any { it.text.startsWith("Selftest:") } }
        }
        st.unitId = null; st.tab = Tab.UNITS.name; st.flash.clear()
    }
}

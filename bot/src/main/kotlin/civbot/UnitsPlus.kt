package civbot

import com.unciv.Constants
import com.unciv.UncivGame
import com.unciv.logic.automation.civilization.AutomationCache
import com.unciv.logic.automation.civilization.NextTurnAutomation
import com.unciv.logic.automation.city.ConstructionAutomation
import com.unciv.logic.automation.unit.UnitAutomation
import com.unciv.logic.battle.AttackableTile
import com.unciv.logic.battle.BattleDamage
import com.unciv.logic.battle.CityCombatant
import com.unciv.logic.battle.MapUnitCombatant
import com.unciv.logic.city.CityFocus
import com.unciv.logic.civilization.CivilopediaAction
import com.unciv.logic.civilization.MayaLongCountAction
import com.unciv.logic.civilization.managers.TurnManager
import com.unciv.logic.map.mapunit.UpgradeAvailability
import com.unciv.logic.map.mapunit.actions.UnitActionModifiers
import com.unciv.logic.map.mapunit.actions.UnitActions
import com.unciv.logic.map.mapunit.actions.UnitActionsUpgrade
import com.unciv.models.UnitAction
import com.unciv.models.UnitActionType
import com.unciv.models.UpgradeUnitAction
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
import com.unciv.logic.civilization.diplomacy.DiplomacyFlags
import com.unciv.models.ruleset.BeliefType
import com.unciv.logic.map.HexCoord
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.logic.map.mapunit.PossibleUpgrade
import com.unciv.logic.map.tile.Tile
import com.unciv.models.ruleset.unique.UniqueType
import com.unciv.models.ruleset.unit.Promotion
import com.unciv.view.GameView
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent
import net.dv8tion.jda.api.interactions.commands.OptionType
import net.dv8tion.jda.api.interactions.commands.build.SubcommandData
import net.dv8tion.jda.api.interactions.components.ActionRow
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Units & world-screen extras, mirroring Unciv's WorldScreen / UnitTable logic:
 *  - 🔄 swap places with an own unit (Go to falls back to a swap; menu entry per swappable unit)
 *  - 👥 group move (multi-select nearby units, send them one after another)
 *  - destination of moving units (status text + path/marker on the map)
 *  - ⭐ promote along a path to a deeper promotion, 🎖️ default promotions per unit type (per city, like Unciv)
 *  - 🗑️ disband with the gold shown and a confirm step
 *  - ⬆️ upgrade all units of a type
 *  - 🤖 AutoPlay (AutoPlayMenu): military / civilians / both / economy once, a full turn (ends it), or N turns in a row
 *    (persisted in meta.settings.extra, `/civ autoplay`, stops on any click)
 *  - ⚔️ combat preview on attacks (BattleTable.simulateBattle), ⚠️ confirm step for UnitActionConfirmation actions,
 *    🚫 engine actions that exist but can't run now, 🎯 "N turns to get there", XP / interception / religion / statuses
 *  - 🪖 Units overview tab (UnitOverviewTab): every unit, paged; pick one to select it
 *  - 📰 News tab: notifications that jump to their unit / city / tile / Civilopedia entry, Maya calendar, history
 *  - extra end-turn checklist lines
 */
object UnitsPlus : Extension {
    private const val NEWS = "NEWS"
    private const val GROUP = "UP_GROUP"
    private const val OVERVIEW = "UP_UNITS"

    override val tabs = listOf(
        ExtTab(NEWS, "News", "📰", "Notifications: jump to the unit, city or tile; turn-by-turn history"),
        // Reached from Unit actions → "👥 Move a group…" (kept out of the tab list)
        ExtTab(GROUP, "Group move", "👥", "Send several units to one tile") { false },
        ExtTab(OVERVIEW, "Units overview", "🪖", "Every unit: type, place, HP, moves, XP, promotions, status") { it.units.getCivUnitsSize() > 0 },
    )
    override val viewVerbs = setOf("up_news", "up_nolder", "up_nnewer", "up_nlatest", "up_group", "up_gsel", "up_gall", "up_back", "up_disband_cancel",
        "up_ovsel", "up_ovp")
    override val prompts = mapOf(
        "up_gmove" to Prompt("👥 Move the group to…", "Tile code or city name", "e.g. D5 — units move one after another"),
        "up_apstart" to Prompt("🤖 AutoPlay for how many turns?", "Number of turns (1–100)", "e.g. 10 — any click in your panel stops it"),
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

    /** "moving → D5" (code from [window], the map the player is looking at); with [withTurns] also "(3 turns)". */
    fun statusText(u: MapUnit, window: MapWindow?, withTurns: Boolean = false): String {
        val base = GameOps.unitStatus(u)
        val d = destinationOf(u) ?: return base
        val c = window?.code(d.position.x, d.position.y) ?: "${u.getTile().aerialDistanceTo(d)} tiles away"
        val turns = if (withTurns && u.isMoving()) turnsTo(u, d) else 0
        return "$base → $c" + (if (turns > 0) " (${turnsText(turns)})" else "")
    }

    // ================================================================== 🎯 turns to get there

    /** Unciv's "move here" number (WorldMapHolder.addTileOverlaysWithUnitMovement): turns along getShortestPath; 0 = can't get there. */
    fun turnsTo(u: MapUnit, target: Tile): Int = runCatching {
        when {
            target == u.getTile() -> 0
            u.baseUnit.isAirUnit() || u.isPreparingParadrop() -> if (u.movement.canReach(target)) 1 else 0
            u.hasMovement() -> u.movement.getShortestPath(target).size
            else -> { // out of moves: count from next turn (Unciv's path search starts with the moves left)
                val m = u.currentMovement
                try { u.currentMovement = u.getMaxMovement().toFloat(); u.movement.getShortestPath(target).size } finally { u.currentMovement = m }
            }
        }
    }.getOrDefault(0)

    fun turnsText(n: Int) = if (n == 1) "1 turn" else "$n turns"
    /** "🎯 3 turns to get there, " for the Go to result (empty when unknown). */
    fun turnsNote(n: Int) = if (n > 0) "🎯 ${turnsText(n)} to get there, " else ""

    /** Map tab inspect: how long the selected unit needs to reach [tile]. */
    fun inspectReach(civ: Civilization, st: PanelState, tile: Tile): String {
        val u = st.unitId?.let { GameOps.unitById(civ, it) } ?: return ""
        if (tile == u.getTile()) return "\n🎯 Your ${u.label()} is here."
        if (!tile.isExplored(civ)) return ""
        val n = turnsTo(u, tile)
        return "\n🎯 Your ${u.label()}: " + if (n > 0) "**${turnsText(n)}** to get there" else "can't get there"
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

    // ================================================================== ⚠️ action confirmations (UnitActionConfirmation)

    private class PendingConfirm(val type: UnitActionType, val title: String, val question: String, val confirmText: String, val turn: Int)
    private val pendingConfirm = ConcurrentHashMap<String, PendingConfirm>()
    private fun confirmFor(u: MapUnit) = pendingConfirm[key(u)]?.takeIf { it.turn == u.civ.gameInfo.turns }

    /** Unciv asks before running an action with a [UnitAction.confirmation] (pillage, founding a city that breaks a promise…).
     *  Arms the confirm step and returns the question, or null when the action needs no confirmation. */
    fun confirmFirst(u: MapUnit, a: UnitAction): String? {
        val c = a.confirmation ?: return null
        val p = PendingConfirm(a.type, GameOps.clean(a.title).replace("\n", " "), GameOps.clean(c.question), GameOps.clean(c.confirmText), u.civ.gameInfo.turns)
        pendingConfirm[key(u)] = p
        return "⚠️ ${p.question}\nPick **⚠️ CONFIRM ${p.confirmText}** at the top of Unit actions — or ✖️ cancel."
    }

    fun pendingConfirmChoices(u: MapUnit): List<Choice> {
        val p = confirmFor(u) ?: return emptyList()
        return listOf(
            Choice("up:confirm", "CONFIRM ${p.confirmText}" + (if (p.title.equals(p.confirmText, true)) "" else ": ${p.title}"), p.question, "⚠️"),
            Choice("up:confirmno", "Cancel: ${p.title}", "Don't do it", "✖️"),
        )
    }

    private fun runConfirmed(u: MapUnit): String {
        val p = confirmFor(u) ?: throw PlayerError("Nothing to confirm — pick the action again.")
        pendingConfirm.remove(key(u))
        val a = runCatching { UnitActions.getUnitActions(u, p.type).toList() }.getOrDefault(emptyList())
            .firstOrNull { GameOps.clean(it.title).replace("\n", " ") == p.title && it.action != null }
            ?: throw PlayerError("${p.title} isn't possible any more.")
        val name = u.label()
        a.action!!.invoke()
        return "✨ $name: ${p.title}."
    }

    // ================================================================== ⚔️ combat preview (BattleTable.simulateBattle)

    class CombatPreview(val atkStr: Int, val defStr: Int, val atkHp: Int, val defHp: Int,
                        val atkLeftMin: Int, val atkLeftMax: Int, val defLeftMin: Int, val defLeftMax: Int,
                        /** "captures it" / "occupies the city": no damage roll (melee vs civilian or a beaten city) */
                        val special: String?, val extraRanged: Boolean)

    fun combatPreview(u: MapUnit, t: AttackableTile): CombatPreview? = runCatching {
        val def = t.combatant ?: return null
        val atk = MapUnitCombatant(u)
        val from = t.tileToAttackFrom
        val atkStr = BattleDamage.getAttackingStrength(atk, def, from).roundToInt()
        val defStr = BattleDamage.getDefendingStrength(atk, def, from).roundToInt()
        val ah = atk.getHealth(); val dh = def.getHealth()
        val civilian = def is MapUnitCombatant && def.unit.isCivilian()
        if (!atk.isRanged() && (civilian || (def is CityCombatant && def.isDefeated()))) {
            val what = when {
                !civilian -> "occupies the city"
                (def as MapUnitCombatant).unit.hasUnique(UniqueType.Uncapturable) -> "destroys it"
                else -> "captures it"
            }
            return CombatPreview(atkStr, defStr, ah, dh, ah, ah, dh, dh, what, false)
        }
        var maxD = BattleDamage.calculateDamageToDefender(atk, def, from, 1f)
        var minD = BattleDamage.calculateDamageToDefender(atk, def, from, 0f)
        val maxT = BattleDamage.calculateDamageToAttacker(atk, def, from, 1f)
        val minT = BattleDamage.calculateDamageToAttacker(atk, def, from, 0f)
        val extra = def !is CityCombatant && u.hasUnique(UniqueType.ExtraRangedAttack)
        if (extra) BattleDamage.getExtraRangedAttackBonusDamage(atk, def, from).let { (mx, mn) -> maxD += mx; minD += mn }
        CombatPreview(atkStr, defStr, ah, dh, max(ah - maxT, 0), max(ah - minT, 0), max(dh - maxD, 0), max(dh - minD, 0), null, extra)
    }.getOrNull()

    private fun span(a: Int, b: Int) = if (a == b) "$a" else "${minOf(a, b)}–${maxOf(a, b)}"

    /** The "likely win / lose" note. */
    fun verdict(p: CombatPreview): String {
        p.special?.let { return "✅ $it" }
        val dealt = ((p.defHp - p.defLeftMin) + (p.defHp - p.defLeftMax)) / 2.0
        val taken = ((p.atkHp - p.atkLeftMin) + (p.atkHp - p.atkLeftMax)) / 2.0
        return when {
            p.defLeftMax == 0 && p.atkLeftMin > 0 -> "💀 destroys it"
            p.atkLeftMax == 0 -> "☠️ your unit dies"
            p.defLeftMin == 0 && p.atkLeftMin == 0 -> "🎲 risky: either may die"
            p.defLeftMin == 0 -> "🎯 may destroy it"
            p.atkLeftMin == 0 -> "⚠️ your unit may die"
            dealt >= taken * 1.5 -> "👍 likely win"
            dealt > taken -> "👍 favourable"
            dealt * 1.5 <= taken -> "👎 likely lose"
            else -> "⚖️ even"
        }
    }

    /** One line for the "Attack …" option (≤100 chars). */
    fun previewShort(u: MapUnit, t: AttackableTile): String? = combatPreview(u, t)?.let { p ->
        if (p.special != null) "⚔️${p.atkStr} · ${verdict(p)}"
        else "⚔️${p.atkStr} vs 🛡️${p.defStr} · deal ${span(p.defHp - p.defLeftMin, p.defHp - p.defLeftMax)} · take ${span(p.atkHp - p.atkLeftMin, p.atkHp - p.atkLeftMax)} · ${verdict(p)}"
    }?.take(100)

    private fun targetName(t: AttackableTile, viewer: Civilization): String = when (val c = t.combatant) {
        is MapUnitCombatant -> (if (c.unit.civ.isBarbarian) "Barbarian" else c.unit.civ.civName) + " " + c.unit.label()
        is CityCombatant -> c.city.name
        else -> "enemy"
    }

    /** "⚔️ Combat preview" field: expected HP after each possible attack, as Unciv's battle table shows it. */
    fun combatPreviewField(u: MapUnit, window: MapWindow): Pair<String, String>? {
        val targets = GameOps.attackableTargets(u)
        if (targets.isEmpty()) return null
        val lines = targets.take(6).mapNotNull { t ->
            val p = combatPreview(u, t) ?: return@mapNotNull null
            val code = window.code(t.tileToAttack.position.x, t.tileToAttack.position.y) ?: "${u.getTile().aerialDistanceTo(t.tileToAttack)} tiles away"
            "**${targetName(t, u.civ)}** ($code) ⚔️ ${p.atkStr} vs 🛡️ ${p.defStr} — " +
                (if (p.special != null) verdict(p)
                else "you ❤️${p.atkHp}→${span(p.atkLeftMin, p.atkLeftMax)} · it ❤️${p.defHp}→${span(p.defLeftMin, p.defLeftMax)} · ${verdict(p)}" +
                    (if (p.extraRanged) " (incl. an extra ranged attack)" else ""))
        }
        if (lines.isEmpty()) return null
        return "⚔️ Combat preview" to (lines.joinToString("\n") + (if (targets.size > 6) "\n_…and ${targets.size - 6} more_" else "") +
            "\n_Ranges = worst–best roll. Attack from Unit actions._").take(1024)
    }

    // ================================================================== 🚫 actions that exist but can't run now

    /** The engine action types GameOps.unitChoices lists (when runnable). */
    private val engineTypes = listOf(
        UnitActionType.FoundCity, UnitActionType.CreateImprovement, UnitActionType.Pillage, UnitActionType.Repair,
        UnitActionType.HurryResearch, UnitActionType.HurryPolicy, UnitActionType.HurryWonder, UnitActionType.HurryBuilding,
        UnitActionType.ConductTradeMission, UnitActionType.FoundReligion, UnitActionType.EnhanceReligion,
        UnitActionType.SpreadReligion, UnitActionType.RemoveHeresy, UnitActionType.AddInCapital,
        UnitActionType.Transform, UnitActionType.SetUp, UnitActionType.Guard, UnitActionType.TriggerUnique,
        UnitActionType.Paradrop, UnitActionType.GiftUnit,
    )

    /** Why Unciv greys out [a] (its action is null): Unciv's own conditions, in words. */
    fun unavailableReason(u: MapUnit, a: UnitAction): String {
        val tile = u.getTile()
        if (a is UpgradeUnitAction) return when {
            tile.getOwner() != u.civ -> "only inside your borders"
            !u.hasMovement() -> "no moves left"
            u.civ.gold < a.upgrade.goldCostOfUpgrade -> "needs ${a.upgrade.goldCostOfUpgrade} gold (you have ${u.civ.gold})"
            a.upgrade.availability == UpgradeAvailability.AvailableExceptResources ->
                "needs " + a.upgrade.newResourceRequirements.entries.joinToString { "${it.value} ${it.key}" }.ifEmpty { "more resources" }
            else -> "not possible right now"
        }
        if (!u.hasMovement()) return "no moves left"
        return when (a.type) {
            UnitActionType.FoundCity -> {
                val c = u.civ.gameInfo.ruleset.modOptions.constants
                when {
                    tile.getTilesInDistance(c.minimalCityDistance).any { it.isCityCenter() && it.getContinent() == tile.getContinent() } ||
                        tile.getTilesInDistance(c.minimalCityDistanceOnDifferentContinents).any { it.isCityCenter() && it.getContinent() != tile.getContinent() } ->
                        "too close to another city"
                    tile.getOwner().let { it != null && it != u.civ } -> "inside another civilization's borders"
                    else -> "can't pay its cost right now"
                }
            }
            UnitActionType.FoundReligion, UnitActionType.EnhanceReligion -> "must stand in one of your cities (where it's allowed)"
            UnitActionType.SpreadReligion -> "can't spread here now (already the majority, or no spreads left)"
            UnitActionType.HurryWonder -> "the city here isn't building a wonder"
            UnitActionType.HurryBuilding -> "the city here isn't building a building"
            UnitActionType.ConductTradeMission -> "must be inside a city-state's borders (not at war)"
            UnitActionType.CreateImprovement -> "can't be built on this tile"
            UnitActionType.AddInCapital -> "must be in your capital"
            UnitActionType.Repair -> "nothing to repair here"
            else -> if (u.isEmbarked()) "not while embarked" else "not possible here right now"
        }
    }

    /** "Found City — too close to another city" for every engine action that exists but is greyed out (Unciv shows them disabled). */
    fun unavailableActions(u: MapUnit): List<String> {
        val out = LinkedHashSet<String>()
        for (type in engineTypes) for (a in runCatching { UnitActions.getUnitActions(u, type).toList() }.getOrDefault(emptyList()))
            if (a.action == null && !a.isCurrentAction) out += "${GameOps.clean(a.title).replace("\n", " ")} — ${unavailableReason(u, a)}"
        for (a in runCatching { UnitActionsUpgrade.getUpgradeActions(u).toList() }.getOrDefault(emptyList()))
            if (a.action == null) out += "${GameOps.clean(a.title).replace("\n", " ")} — ${unavailableReason(u, a)}"
        return out.toList()
    }

    // ================================================================== unit info (UnitPresenter)

    /** Spread Religion uses left / max (UnitActionModifiers.usagesLeft / getMaxUsages, which Unciv keeps private). */
    fun spreadsLeft(u: MapUnit): Pair<Int, Int>? {
        val unique = u.getMatchingUniques(UniqueType.CanSpreadReligion).firstOrNull() ?: return null
        val left = Engine.callHiddenResult(UnitActionModifiers, "usagesLeft", u, unique) as? Int ?: return null
        val most = Engine.callHiddenResult(UnitActionModifiers, "getMaxUsages", u, unique) as? Int ?: return null
        return left to most
    }

    /** Extra lines for the selected unit's field: XP, interception, religious strength, timed statuses. */
    fun unitInfoText(u: MapUnit): String {
        val parts = ArrayList<String>()
        if (!u.isCivilian())
            parts += "✨ XP ${u.promotions.XP}/${u.promotions.xpForNextPromotion()}" + (if (u.promotions.canBePromoted()) " (promotion ready)" else "")
        val ir = u.getInterceptionRange(); val ic = u.interceptChance()
        if (ir > 0 && ic > 0) parts += "🛡️ intercepts $ic% within $ir tiles" + (if (u.canIntercept()) "" else " (not this turn)")
        val rs = u.baseUnit.religiousStrength
        if (rs > 0) {
            val rel = u.religion?.let { u.civ.gameInfo.religions[it]?.getReligionDisplayName() }
            parts += "🙏 religious strength ${rs - u.religiousStrengthLost}/$rs" + (rel?.let { " · $it" } ?: "")
        }
        spreadsLeft(u)?.let { (left, most) -> parts += "📿 $left/$most spreads left" }
        val statuses = u.statusMap.values.filter { s -> runCatching { s.uniques.none { it.type == UniqueType.NotShownOnWorldScreen } }.getOrDefault(true) }
        if (statuses.isNotEmpty()) parts += "⏳ " + statuses.joinToString { "${it.name} (${turnsText(it.turnsLeft)})" }
        return if (parts.isEmpty()) "" else "\n" + parts.joinToString(" · ")
    }

    /** Extra fields under the selected unit in the Units tab. */
    fun unitFields(u: MapUnit, window: MapWindow, myTurn: Boolean, meta: GameMeta): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        if (myTurn) combatPreviewField(u, window)?.let { out += it }
        val na = unavailableActions(u)
        if (na.isNotEmpty()) out += "🚫 Not available now" to na.joinToString("\n") { "• $it" }
        meta.seatOfCiv(u.civ.civID)?.let { autoPlayLeft(meta, it) }?.takeIf { it > 0 }?.let { out += "🤖 AutoPlay" to "**$it** turn(s) left — click anything in your panel to stop." }
        return out
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

    /** AutoPlayMenu "AutoPlay Economy Once": NextTurnAutomation.automateCities (production, annexing, defence builds when threatened). */
    fun economyOnce(civ: Civilization): String {
        val before = civ.cities.associate { it.id to it.cityConstructions.currentConstructionName() }
        val puppets = civ.cities.filter { it.isPuppet }.map { it.id }.toSet()
        NextTurnAutomation.automateCities(GameView(civ.gameInfo, civ).civView)
        // Unciv only lets the AI pick a human's production with its "auto-assign city production" setting; headless, idle cities get a pick like 🤖 Auto-pick
        for (city in civ.cities) if (!city.isPuppet && city.cityConstructions.currentConstructionName().isEmpty())
            runCatching { ConstructionAutomation(city.cityConstructions).chooseNextConstruction() }
        // Citizens and specialists: Unciv's own assignment for every city on an automatic focus (manual cities are left alone)
        var reassigned = 0
        for (city in civ.cities) if (city.getCityFocus() != CityFocus.Manual) { runCatching { city.reassignPopulation() }; reassigned++ }
        civ.updateStatsForNextTurn()
        val changed = civ.cities.filter { before[it.id] != it.cityConstructions.currentConstructionName() }
        val annexed = civ.cities.filter { it.id in puppets && !it.isPuppet }
        return "🤖 AutoPlay economy once: " + (if (changed.isEmpty()) "production unchanged"
            else changed.joinToString { "${it.name} → **${it.cityConstructions.currentConstructionName().ifEmpty { "nothing" }}**" }) +
            (if (annexed.isNotEmpty()) " · annexed ${annexed.joinToString { it.name }}" else "") +
            " · citizens re-assigned in $reassigned city(ies). Your turn continues."
    }

    /** AutoPlayMenu "AutoPlay End Turn": TurnManager.automateTurn (units, cities, research, policies, diplomacy, gold, faith).
     *  The caller ends the turn afterwards. */
    fun fullTurn(civ: Civilization): List<String> {
        val notes = ArrayList<String>()
        notes += GameOps.advisor(civ, fillPicks = true) // the bot's Inbox decisions / events get their proper defaults first
        val conquests = civ.popupAlerts.filter { it.type == AlertType.CityConquered }
        try { TurnManager(civ).automateTurn() } catch (e: Exception) { notes += "⚠️ The AI stopped early (${e.javaClass.simpleName}); the turn ends anyway." }
        civ.popupAlerts.addAll(conquests.filter { c -> civ.popupAlerts.none { it.type == c.type && it.value == c.value } })
        return notes
    }

    // ---- multi-turn AutoPlay (AutoPlay.turnsToAutoPlay), kept in meta.settings.extra so it survives restarts
    private fun apKey(seat: Seat) = "up_autoplay.${seat.userId}"
    fun autoPlayLeft(meta: GameMeta, seat: Seat): Int = meta.settings.extra[apKey(seat)]?.toIntOrNull() ?: 0
    fun setAutoPlay(meta: GameMeta, seat: Seat, turns: Int) {
        if (turns > 0) meta.settings.extra[apKey(seat)] = turns.toString() else meta.settings.extra.remove(apKey(seat))
    }

    /** Called by Controller.apply for every panel action: a click (other than the AutoPlay controls) hands control back. */
    fun onPlayerAction(s: Session, seat: Seat, st: PanelState, verb: String): Boolean {
        if (verb == "refresh" || verb.startsWith("up_ap") || autoPlayLeft(s.meta, seat) <= 0) return false
        setAutoPlay(s.meta, seat, 0)
        st.flash += "⏹️ AutoPlay stopped — you're back in control."
        return true
    }

    /** Called by Controller.endTurn when [seat]'s turn comes up: if AutoPlay is on, the AI plays it (the caller ends it). */
    fun autoPlayTurn(s: Session, seat: Seat, events: MutableList<String>): Boolean {
        if (autoPlayLeft(s.meta, seat) <= 0) return false
        val game = s.game ?: return false
        val civ = GameOps.civOf(game, seat)
        if (!civ.isAlive() || game.currentPlayerCiv != civ) return false
        playAutoTurn(s, seat, civ, events)
        return true
    }

    /** Plays one AutoPlay turn for [civ] (counting it down); the turn still has to be ended by the caller. */
    private fun playAutoTurn(s: Session, seat: Seat, civ: Civilization, events: MutableList<String>) {
        val left = autoPlayLeft(s.meta, seat) - 1
        setAutoPlay(s.meta, seat, left)
        val citiesBefore = civ.cities.map { it.id }.toSet()
        fullTurn(civ)
        Features.flagNewConquests(civ, citiesBefore)
        for (c in civ.cities) if (c.id !in citiesBefore) events += "🏰 ${civ.civName} captured ${c.name}!"
        events += "🤖 ${civ.civName}'s turn was AutoPlayed" + (if (left > 0) " ($left more to go)." else " — AutoPlay finished.")
    }

    override fun commands(): List<SubcommandData> = listOf(
        SubcommandData("autoplay", "Let the AI play your next turns (AutoPlay); any click in your panel stops it")
            .addOption(OptionType.INTEGER, "turns", "How many turns (0 = stop)", true),
    )

    override fun onCommand(event: SlashCommandInteractionEvent, bot: BotApi): Boolean {
        if (event.subcommandGroup != null || event.subcommandName != "autoplay") return false
        val n = (event.getOption("turns")?.asInt ?: 0).coerceIn(0, 100)
        val channelId = event.channel.id; val userId = event.user.id
        event.deferReply(true).queue { hook ->
            bot.async {
                val s = bot.gameHere(channelId, userId)
                val seat = s?.meta?.seatOf(userId)
                val game = s?.game
                if (s == null || seat == null || game == null || s.meta.status != Status.RUNNING) {
                    hook.editOriginal("There's no running game of yours here.").queue(); return@async
                }
                var result: TurnResult? = null
                val msg = Engine.withGame(game) { g ->
                    val civ = GameOps.civOf(g, seat)
                    setAutoPlay(s.meta, seat, n)
                    when {
                        n == 0 -> { bot.store.saveMeta(s.meta); "⏹️ AutoPlay is off." }
                        g.currentPlayerCiv == civ && civ.isAlive() -> {
                            val events = ArrayList<String>()
                            playAutoTurn(s, seat, civ, events)
                            events.forEach { s.meta.addEvent(it) }
                            result = bot.ctl.endTurn(s)
                            "🤖 AutoPlay on: the AI played this turn" + (if (n > 1) " and plays **${n - 1}** more." else ".") + " Click anything in your panel to stop."
                        }
                        else -> { bot.store.saveMeta(s.meta); "🤖 AutoPlay on for **$n** turn(s), starting with your next turn. Click anything in your panel to stop." }
                    }
                }
                hook.editOriginal(msg).queue()
                result?.let { r -> (bot as? Bot)?.announce(s, r) }
            }
        }
        return true
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
        Choice("do:up_auto:eco", "AutoPlay economy once", "The AI runs your cities: production, citizens; you still end the turn", "🤖"),
        Choice("do:up_apfull", "AutoPlay this turn and end it", "The AI plays everything (units, cities, diplomacy), then ends the turn", "⏩"),
        Choice("ask:up_apstart", "AutoPlay for several turns…", "The AI plays your turns until the count runs out; any click stops it", "🔁"),
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
            "confirm" -> runConfirmed(u)
            "confirmno" -> { val p = confirmFor(u); pendingConfirm.remove(key(u)); "✖️ ${p?.title ?: "Action"} cancelled." }
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
                if (kind == "eco") { st.flash += economyOnce(civ); ctx.changed = true; return true }
                val citiesBefore = civ.cities.map { it.id }.toSet()
                st.flash += autoPlayOnce(civ, kind != "civ", kind != "mil")
                announceConquests(ctx, citiesBefore)
                if (st.unitId?.let { GameOps.unitById(civ, it) } == null) st.unitId = GameOps.nextIdleUnit(civ, null)?.id
                ctx.changed = true
            }
            // ---- AutoPlay a whole turn / several turns (AutoPlayMenu "AutoPlay End Turn" / "Start AutoPlay")
            "up_apfull" -> {
                ctx.requireTurn()
                val citiesBefore = civ.cities.map { it.id }.toSet()
                val notes = fullTurn(civ)
                announceConquests(ctx, citiesBefore)
                st.flash += "⏩ The AI played your turn" + (if (notes.isNotEmpty()) ":\n" + notes.take(6).joinToString("\n") else ".") + "\nTurn ended."
                ctx.s.meta.addEvent("🤖 ${civ.civName}'s turn was AutoPlayed.")
                ctx.changed = true; ctx.endTurn = true
            }
            "up_apstart" -> {
                val n = (ctx.value ?: ctx.args.firstOrNull())?.trim()?.toIntOrNull()?.takeIf { it in 1..100 }
                    ?: throw PlayerError("Type a number of turns from 1 to 100.")
                setAutoPlay(ctx.s.meta, ctx.seat, n)
                ctx.changed = true
                if (ctx.myTurn) {
                    val events = ArrayList<String>()
                    playAutoTurn(ctx.s, ctx.seat, civ, events)
                    events.forEach { ctx.s.meta.addEvent(it) }
                    st.flash += "🔁 AutoPlay on: the AI played this turn" + (if (n > 1) " and plays **${n - 1}** more." else ".") +
                        " Click anything in your panel (or `/civ autoplay turns:0`) to stop."
                    ctx.endTurn = true
                } else st.flash += "🔁 AutoPlay on for **$n** turn(s), starting with your next turn. Click anything to stop."
            }
            "up_apstop" -> { setAutoPlay(ctx.s.meta, ctx.seat, 0); st.flash += "⏹️ AutoPlay is off."; ctx.changed = true }
            // ---- 🪖 units overview
            "up_ovsel" -> {
                val id = ctx.value?.removePrefix("u:")?.toIntOrNull()?.takeIf { GameOps.unitById(civ, it) != null } ?: throw PlayerError("That unit is gone.")
                st.unitId = id; st.tab = Tab.UNITS.name
            }
            "up_ovp" -> st.ext["up_ovp"] = ((st.ext["up_ovp"] as? Int ?: 0) + (ctx.args.firstOrNull()?.toIntOrNull() ?: 1)).coerceAtLeast(0)
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
        is CivilopediaAction -> "📖 Civilopedia" + ((field(a, "link") as? String)?.substringAfter("/")?.takeIf { it.isNotBlank() }?.let { ": ${GameOps.clean(it)}" } ?: "")
        is MayaLongCountAction -> "📅 Maya calendar"
        else -> null
    }

    private fun jumpable(civ: Civilization, n: Notification) = n.actions.filter { actionLabel(civ, it) != null }

    private fun emojiOf(a: NotificationAction) = when (a) {
        is MapUnitAction, is PromoteUnitAction -> "🪖"
        is CityAction -> "🏙️"
        is LocationAction -> "📍"
        is CivilopediaAction -> "📖"
        is MayaLongCountAction -> "📅"
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
            is CivilopediaAction -> openPedia(st, g, field(a, "link") as? String ?: "")
            is MayaLongCountAction -> { st.flash += mayaText(g.getYear()); return }
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

    // ---- 📖 Civilopedia links (CivilopediaAction) and 📅 the Maya calendar (MayaLongCountAction)

    /** Unciv's Civilopedia link categories ("Unit/Warrior") → the 📖 tab's categories (InfoPlus). */
    private val pediaCats = mapOf("Unit" to "units", "Building" to "buildings", "Wonder" to "wonders", "Technology" to "techs",
        "Policy" to "policies", "Belief" to "beliefs", "Resource" to "resources", "Improvement" to "improvements", "Terrain" to "terrains",
        "Promotion" to "promotions", "UnitType" to "unittypes", "Nation" to "nations", "Era" to "eras", "Victory" to "victories",
        "Tutorial" to "tutorials", "Difficulty" to "difficulties", "Speed" to "speeds")

    /** Opens the 📖 Civilopedia tab at [link]'s entry, like WorldScreen.openCivilopedia. */
    fun openPedia(st: PanelState, game: com.unciv.logic.GameInfo, link: String) {
        val cat = link.substringBefore("/", "")
        val name = link.substringAfter("/").trim()
        if (name.isEmpty()) { st.tab = "CIVILOPEDIA"; return }
        val rs = game.ruleset
        val hits = InfoPlus.search(rs, game, InfoPlus.tx(name))
        val want = pediaCats[cat]
        val hit = hits.firstOrNull { it.entry.name == name && (want == null || it.cat.key == want) }
            ?: hits.firstOrNull { it.entry.name == name } ?: hits.firstOrNull()
            ?: throw PlayerError("The Civilopedia has no entry for $name.")
        val idx = InfoPlus.entries(hit.cat, rs, game).indexOfFirst { it.name == hit.entry.name }.coerceAtLeast(0)
        st.ext["ip.pedia"] = InfoPlus.PediaState(hit.cat.key, hit.entry.name, idx / 25) // InfoPlus's selection (25 entries a page)
        st.tab = "CIVILOPEDIA"
    }

    /** (b'ak'tun, k'atun, tun) of a year — MayaCalendar.MayaYear. */
    fun mayaDate(year: Int): Triple<Int, Int, Int> {
        val mayaDays = (year + 3000) * 365 + (year + 3000) / 4 + (36000 + 5040 + 240 + 11)
        val totalTuns = if (mayaDays >= 0) mayaDays / 360 else 13 * 20 * 20 + mayaDays / 360
        val totalKatuns = totalTuns / 20
        val baktuns = totalKatuns / 20
        return Triple(baktuns, totalKatuns - baktuns * 20, totalTuns - totalKatuns * 20)
    }

    /** The text of Unciv's Maya calendar popup (MayaCalendar.openPopup), plus when the next b'ak'tun starts. */
    fun mayaText(year: Int): String {
        val (b, k, t) = mayaDate(year)
        val next = (year + 1..year + 500).firstOrNull { mayaDate(it).first != b }
        fun y(v: Int) = if (v < 0) "${-v} BC" else "$v AD"
        return "📅 **The Maya Long Count** — your scholars measure long spans of time in b'ak'tuns. Whenever a b'ak'tun ends, " +
            "a Great Person joins you.\nThe rest of the world calls this year **${y(year)}**; in the Maya calendar it is " +
            "**$b b'ak'tun, $k k'atun, $t tun**." + (next?.let { "\nThe next b'ak'tun begins in ${y(it)}." } ?: "")
    }

    // ================================================================== 🪖 units overview (UnitOverviewTab)

    private const val OV_PAGE = 12

    private fun renderOverview(t: TabCtx) {
        val civ = t.civ
        val units = civ.units.getCivUnits().sortedWith(compareBy<MapUnit>({ it.isCivilian() }, { it.baseUnit.name }, { it.id })).toList()
        val pages = maxOf(1, (units.size + OV_PAGE - 1) / OV_PAGE)
        val page = (t.st.ext["up_ovp"] as? Int ?: 0).coerceIn(0, pages - 1)
        t.st.ext["up_ovp"] = page
        val center = civ.getCapital()?.getCenterTile() ?: GameOps.focusTile(civ)
        val window = MapWindow(center.position)
        val slice = units.drop(page * OV_PAGE).take(OV_PAGE)
        fun where(u: MapUnit) = window.code(u.getTile().position.x, u.getTile().position.y) ?: "off-map"
        val lines = slice.map { u ->
            val promos = u.promotions.promotions.toList()
            "${GameOps.unitEmoji(u)} **${u.label()}** (${GameOps.clean(u.baseUnit.unitType)}) · ${where(u)} · ❤️${u.health} · 👣${GameOps.movesText(u)}" +
                (if (!u.isCivilian()) " · ✨${u.promotions.XP}/${u.promotions.xpForNextPromotion()}" else "") +
                (if (promos.isNotEmpty()) " · ⭐" + promos.take(3).joinToString() + (if (promos.size > 3) " +${promos.size - 3}" else "") else "") +
                (u.getClosestCity()?.let { " · 🏙️ ${it.name}" } ?: "") + " · ${statusText(u, window)}"
        }
        val mil = units.count { it.isMilitary() }
        val idle = units.count { it.due && it.isIdle() && it.hasMovement() }
        val ap = t.s.meta.seatOfCiv(civ.civID)?.let { autoPlayLeft(t.s.meta, it) } ?: 0
        t.eb.addField("🪖 ${units.size} units", "⚔️ $mil military · 🧑 ${units.size - mil} civilian · ⏳ $idle waiting for orders" +
            (if (ap > 0) "\n🔁 AutoPlay: **$ap** turn(s) left — any click stops it" else "") +
            "\n_Codes are on the map around ${civ.getCapital()?.name ?: "your units"}; lighter tiles = units on this page._", false)
        // pack the lines into fields of ≤1024 chars
        var body = StringBuilder(); var first = page * OV_PAGE + 1; var n = 0; var fields = 0
        fun flush() {
            if (n == 0) return
            t.eb.addField("Units $first–${first + n - 1} (page ${page + 1}/$pages)", body.toString().trim(), false)
            first += n; n = 0; body = StringBuilder(); fields++
        }
        for (l in lines) { val line = l.take(400); if (body.length + line.length + 1 > 1024) flush(); body.append(line).append('\n'); n++ }
        flush()
        t.mapAround(center, Overlay(reachable = slice.map { it.getTile() }.toSet()))
        val choices = units.map { u ->
            Choice("u:${u.id}", "${u.label()} — ${statusText(u, window)}".take(100),
                "${where(u)} · ❤️${u.health} · ${GameOps.movesText(u)} moves" + (u.getClosestCity()?.let { " · near ${it.name}" } ?: ""), GameOps.unitEmoji(u))
        }
        t.rows += ActionRow.of(t.select("up_ovsel", "🪖 Pick a unit to select it… (${units.size})", choices))
        t.rows += ActionRow.of(t.select("up_autosel", "🤖 AutoPlay…", autoPlayChoices() +
            (if (ap > 0) listOf(Choice("do:up_apstop", "Stop AutoPlay", "$ap turn(s) left", "⏹️")) else emptyList()), enabled = t.myTurn || ap > 0))
        t.endRow += t.button("up_ovp", "Prev", "◀️", 2, page > 0, "-1")
        t.endRow += t.button("up_ovp", "Next", "▶️", 2, page < pages - 1, "1")
        t.includeAdvisor = false
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
            OVERVIEW -> renderOverview(t)
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
            // Unciv's NextTurnAction "Expand pantheon" (CivView.canExpandPantheon) — not a reform
            if (InfoPlus.pantheonExpandable(civ)) w += "You can **add a belief to your pantheon** (✨ Beliefs tab)"
            when (rm.religionState) {
                ReligionState.FoundingReligion -> w += "**Found your religion** — name it and pick beliefs (🙏 Religion tab)"
                ReligionState.EnhancingReligion -> w += "**Enhance your religion** — pick beliefs (🙏 Religion tab)"
                else -> if (InfoPlus.reformable(civ)) w += "**Reform your religion** — free belief(s) to pick (✨ Beliefs tab)"
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

        // ---- 12. ⚔️ combat preview (Units tab option + field, Military tab), and it matches the real battle
        val barb = t.eng { g -> g.civilizations.firstOrNull { it.isBarbarian } }
        val fightPair = t.eng { spots.filter { it.getUnits().none() }.firstNotNullOfOrNull { s1 ->
            s1.neighbors.firstOrNull { it.isLand && !it.isImpassible() && it.getUnits().none() && !it.isCityCenter() }?.let { s1 to it } } }
        if (barb != null && fightPair != null) {
            val fighter = spawn("Swordsman", fightPair.first)
            val enemy = t.eng { g ->
                val e = g.tileMap.placeUnitNearTile(fightPair.second.position, "Warrior", barb)!!
                if (e.getTile() != fightPair.second) { e.removeFromTile(); e.putInTile(fightPair.second) }
                GameOps.civOf(g, a).cache.updateViewableTiles(); e
            }
            t.act(a, "tab", emptyList(), "UNITS", null); t.act(a, "selunit", emptyList(), "u:${fighter.id}", null)
            val atkOpt = t.eng { GameOps.unitChoices(fighter) }.firstOrNull { it.id == "atk:${fightPair.second.position.x},${fightPair.second.position.y}" }
            say("attack option: ${atkOpt?.label} — ${atkOpt?.description}")
            if (atkOpt != null && atkOpt.description.contains("deal") && atkOpt.description.contains("take")) tick("combat preview in the Attack option")
            val fields = t.eng { Panel.render(t.s, a, st) }.embed.fields
            val pv = fields.firstOrNull { it.name == "⚔️ Combat preview" }?.value.orEmpty()
            say("preview field: ${pv.replace("\n", " | ").take(250)}")
            if (pv.contains("→")) { tick("combat preview field (Units tab)"); t.render(a, "up-combat-preview") }
            t.act(a, "tab", emptyList(), "MILITARY", null)
            val mfields = t.eng { Panel.render(t.s, a, st) }.embed.fields
            if (mfields.any { it.name == "⚔️ Combat preview" }) tick("combat preview in the Military tab")
            t.act(a, "tab", emptyList(), "UNITS", null); t.act(a, "selunit", emptyList(), "u:${fighter.id}", null)
            val (lo, hi, before) = t.eng { _ ->
                val at = GameOps.attackableTargets(fighter).first { it.tileToAttack == fightPair.second }
                val p = combatPreview(fighter, at)!!
                Triple(p.defHp - p.defLeftMax, p.defHp - p.defLeftMin, enemy.health)
            }
            if (atkOpt != null && t.act(a, "uact", emptyList(), atkOpt.id, null)) {
                val dealt = t.eng { before - (if (enemy.isDestroyed) 0 else enemy.health) }
                say("${last()} (preview said $lo–$hi)")
                if (dealt in lo..hi || (enemy.isDestroyed && hi >= before)) tick("combat preview matches the battle")
            }
            t.eng { if (!enemy.isDestroyed) enemy.destroy() }
            drop(listOf(fighter))
        } else say("combat preview: no barbarians or no free pair of tiles")

        // ---- 13. ⚠️ confirmations (pillage asks first; cancel and confirm)
        val pillTile = t.eng { g ->
            val civ = GameOps.civOf(g, a)
            (3..8).asSequence().flatMap { capTile.getTilesAtDistance(it) }.firstOrNull {
                it.isLand && !it.isImpassible() && it.getOwner() == null && it.getUnits().none() && !it.isCityCenter() && it.isExplored(civ)
            }
        }
        if (pillTile != null) {
            val raider = spawn("Warrior", pillTile)
            t.eng { pillTile.improvementFunctions.setImprovement("Farm") }
            t.act(a, "selunit", emptyList(), "u:${raider.id}", null)
            val pOpt = t.eng { GameOps.unitChoices(raider) }.firstOrNull { it.id.startsWith("act:Pillage") }
            say("pillage option: ${pOpt?.label} — ${pOpt?.description}")
            if (pOpt != null && pOpt.description.contains("asks to confirm") && t.act(a, "uact", emptyList(), pOpt.id, null) &&
                last().startsWith("⚠️") && t.eng { !pillTile.improvementIsPillaged } &&
                t.eng { GameOps.unitChoices(raider) }.first { it.id != "do:cp_undo" }.id == "up:confirm") {
                tick("pillage asks to confirm first")
                if (t.act(a, "uact", emptyList(), "up:confirmno", null) && t.eng { GameOps.unitChoices(raider) }.none { it.id == "up:confirm" } &&
                    t.eng { !pillTile.improvementIsPillaged }) tick("cancel a confirmation")
                t.act(a, "uact", emptyList(), pOpt.id, null)
                if (t.act(a, "uact", emptyList(), "up:confirm", null) && t.eng { pillTile.improvementIsPillaged }) { last(); tick("confirm runs the action (pillage)") }
            }
            t.eng { pillTile.improvementFunctions.setImprovement(null as com.unciv.models.ruleset.tile.TileImprovement?) }
            drop(listOf(raider))
        } else say("pillage: no foreign tile nearby")

        // ---- 14. 🚫 actions that exist but can't run now (a settler next to the capital)
        val nearCap = t.eng { capTile.neighbors.firstOrNull { it.isLand && !it.isImpassible() && it.getUnits().none() } }
        if (nearCap != null) {
            val settler = spawn("Settler", nearCap)
            t.act(a, "selunit", emptyList(), "u:${settler.id}", null)
            val na = t.eng { unavailableActions(settler) }
            say("unavailable: $na")
            val field = t.eng { Panel.render(t.s, a, st) }.embed.fields.firstOrNull { it.name == "🚫 Not available now" }?.value.orEmpty()
            if (na.any { "too close to another city" in it } && field.contains("too close") && t.eng { GameOps.unitChoices(settler) }.none { it.id.startsWith("act:FoundCity") })
                tick("unavailable actions shown with Unciv's reason")
            drop(listOf(settler))
        }

        // ---- 15. 🎯 N turns to get there (Go to result, unit field, Map inspect)
        val walker = freeSpot()?.let { spawn("Warrior", it) }
        if (walker != null) {
            t.act(a, "tab", emptyList(), "UNITS", null); t.act(a, "selunit", emptyList(), "u:${walker.id}", null); t.render(a, null)
            val far = t.eng { _ ->
                val w = st.window!!
                (6 downTo 4).asSequence().flatMap { d -> walker.getTile().getTilesAtDistance(d) }
                    .filter { it.isLand && !it.isImpassible() && it.isExplored(walker.civ) && w.code(it.position.x, it.position.y) != null && it.getUnits().none() }
                    .firstOrNull { runCatching { walker.movement.canReach(it) && !walker.movement.canReachInCurrentTurn(it) }.getOrDefault(false) }
            }
            if (far != null) {
                val expect = t.eng { turnsTo(walker, far) }
                val code = t.eng { st.window!!.code(far.position.x, far.position.y)!! }
                // inspect from the Map tab while the walker is selected
                st.tab = Tab.MAP.name; st.wholeMap = false; st.mapCenter = walker.getTile().position; st.inspect = far.position
                val insp = t.eng { Panel.render(t.s, a, st) }.embed.fields.firstOrNull { it.name?.startsWith("🔍") == true || it.value?.contains("🎯") == true }?.value.orEmpty()
                say("inspect: ${insp.replace("\n", " | ").take(200)}")
                if (insp.contains("to get there")) tick("map inspect shows turns to get there")
                st.tab = Tab.UNITS.name; st.inspect = null
                t.act(a, "selunit", emptyList(), "u:${walker.id}", null); t.render(a, null)
                val code2 = t.eng { st.window!!.code(far.position.x, far.position.y)!! }
                if (t.act(a, "goto", emptyList(), code2, null) && last().contains("to get there")) tick("go to shows the turns to get there")
                say("expected $expect turns ($code)")
                t.act(a, "selunit", emptyList(), "u:${walker.id}", null)
                val f = t.eng { Panel.render(t.s, a, st) }.embed.fields.firstOrNull { it.name?.contains(walker.label()) == true }?.value.orEmpty()
                say("walker field: ${f.replace("\n", " | ").take(160)}")
                if (Regex("→ [A-Z]+\\d+ \\(\\d+ turns?\\)").containsMatchIn(f)) tick("moving unit shows turns left")
            } else say("turns: no far tile")
            drop(listOf(walker))
        }

        // ---- 16. unit info: XP, statuses, interception, religious strength
        freeSpot()?.let { spawn("Spearman", it) }?.let { sp ->
            t.eng { sp.promotions.XP = 7; sp.setStatus("Selftest status", 2) }
            val info = t.eng { unitInfoText(sp) }
            say("info: $info")
            if (info.contains("XP 7/") && info.contains("Selftest status (2 turns)")) tick("unit info: XP and timed statuses")
            t.eng { sp.removeStatus("Selftest status") }
            drop(listOf(sp))
        }
        for (name in listOf("Fighter", "Anti-Aircraft Gun", "Missionary", "Great Prophet")) {
            val u = t.eng { g -> val civ = GameOps.civOf(g, a); if (g.ruleset.units[name] != null) civ.units.addUnit(name, civ.getCapital()) else null } ?: continue
            created += u
            val info = t.eng { unitInfoText(u) }
            say("$name: $info")
            if (info.contains("intercepts")) tick("unit info: interception range and chance")
            if (info.contains("religious strength")) tick("unit info: religious strength")
            if (info.contains("spreads left")) tick("unit info: religion spreads left")
            drop(listOf(u))
        }

        // ---- 17. 🪖 units overview tab
        t.act(a, "tab", emptyList(), OVERVIEW, null); t.render(a, "up-units-overview")
        val ovFields = t.eng { Panel.render(t.s, a, st) }.embed.fields
        ovFields.take(3).forEach { say("overview field ${it.name}: ${it.value?.replace("\n", " | ")?.take(500)}") }
        if (ovFields.any { it.name?.startsWith("Units 1–") == true && it.value?.contains("❤️") == true }) tick("units overview: every unit, paged")
        val anyUnit = t.eng { g -> GameOps.civOf(g, a).units.getCivUnits().last() }
        if (t.act(a, "up_ovsel", emptyList(), "u:${anyUnit.id}", null) && st.tab == Tab.UNITS.name && st.unitId == anyUnit.id) tick("units overview: pick a unit to select it")
        t.act(a, "tab", emptyList(), OVERVIEW, null)
        if (t.eng { g -> GameOps.civOf(g, a).units.getCivUnitsSize() } > OV_PAGE && t.act(a, "up_ovp", listOf("1"), null, null) && st.ext["up_ovp"] == 1) {
            if (t.eng { Panel.render(t.s, a, st) }.embed.fields.any { it.name?.contains("page 2/") == true }) tick("units overview: next page")
            t.act(a, "up_ovp", listOf("-1"), null, null)
        }
        t.act(a, "tab", emptyList(), "UNITS", null)

        // ---- 18. AutoPlay economy once
        if (t.act(a, "uact", emptyList(), "do:up_auto:eco", null) && last().startsWith("🤖 AutoPlay economy")) tick("AutoPlay economy once")

        // ---- 19. 📰 News: Civilopedia link and the Maya calendar
        val (pediaNote, mayaNote) = t.eng { g ->
            val civ = GameOps.civOf(g, a)
            civ.addNotification("Selftest: read about warriors", CivilopediaAction("Unit/Warrior"), Notification.NotificationCategory.General)
            civ.addNotification("Selftest: the Maya calendar", MayaLongCountAction(), Notification.NotificationCategory.General)
            val n = civ.notifications.size
            "up:n:c:${n - 2}" to "up:n:c:${n - 1}"
        }
        t.act(a, "tab", emptyList(), NEWS, null)
        val newsOpts = t.eng { g -> newsChoices(GameOps.civOf(g, a)) }
        say("pedia/maya options: ${newsOpts.filter { it.id == pediaNote || it.id == mayaNote }.map { it.description }}")
        if (t.act(a, "up_news", emptyList(), pediaNote, null) && st.tab == "CIVILOPEDIA" && (st.ext["ip.pedia"] as? InfoPlus.PediaState)?.item == "Warrior") {
            t.render(a, "up-news-pedia"); tick("news: Civilopedia link opens the entry")
        }
        t.act(a, "tab", emptyList(), NEWS, null)
        if (t.act(a, "up_news", emptyList(), mayaNote, null) && st.tab == NEWS && last().contains("Maya Long Count") && last().contains("b'ak'tun")) tick("news: Maya calendar info")

        // ---- clean up: remove the units this test made, and its notifications
        t.eng { g ->
            created.forEach { u -> if (u.civ.units.getCivUnits().contains(u)) u.destroy() }
            val civ = GameOps.civOf(g, a)
            civ.notifications.removeAll { it.text.startsWith("Selftest:") }
            civ.notificationsLog.removeAll { l -> l.notifications.any { it.text.startsWith("Selftest:") } }
        }
        st.unitId = null; st.tab = Tab.UNITS.name; st.flash.clear()

        // ==== Turn-ending features run on a copy of the game, restored afterwards (later extensions still see Alice's turn)
        val snapshot = t.eng { g -> Engine.serialize(g) }
        val meta = t.s.meta
        val bobSt = t.ctl.state(meta.id, t.bob.userId)
        try {
            // ---- 20. found city near a "don't settle near us" promise asks first
            val promise = t.eng { g ->
                val civ = GameOps.civOf(g, a); val bob = GameOps.civOf(g, t.bob)
                if (civ.getDiplomacyManager(bob) == null) civ.diplomacyFunctions.makeCivilizationsMeet(bob)
                if (civ.isAtWarWith(bob)) GameOps.makePeace(civ, bob)
                bob.getDiplomacyManager(civ)!!.setFlag(DiplomacyFlags.AgreedToNotSettleNearUs, 100)
                val bc = bob.getCapital()?.getCenterTile()
                bc?.let { c -> (4..6).asSequence().flatMap { c.getTilesAtDistance(it) }.firstOrNull { it.isLand && it.getUnits().none() && it.canBeSettled(civ) } }
            }
            if (promise != null) {
                val settler = spawn("Settler", promise)
                t.act(a, "selunit", emptyList(), "u:${settler.id}", null)
                val fOpt = t.eng { GameOps.unitChoices(settler) }.firstOrNull { it.id.startsWith("act:FoundCity") }
                val citiesBefore = t.eng { g -> GameOps.civOf(g, a).cities.size }
                if (fOpt != null && fOpt.description.contains("asks to confirm") && t.act(a, "uact", emptyList(), fOpt.id, null) && last().contains("promise") &&
                    t.eng { g -> GameOps.civOf(g, a).cities.size } == citiesBefore &&
                    t.act(a, "uact", emptyList(), "up:confirm", null) && t.eng { g -> GameOps.civOf(g, a).cities.size } == citiesBefore + 1) {
                    last(); tick("found city breaking a promise asks to confirm")
                } else say("promise: option=${fOpt?.description} flash=${st.flash}")
            } else say("promise: no settleable tile near Bob")

            // ---- 21. expand-pantheon checklist line (not "Reform")
            val pw = t.eng { g ->
                // whoever of the two is still at most at the pantheon stage (a founded religion is forced back to one, on this copy)
                val civ = listOf(GameOps.civOf(g, a), GameOps.civOf(g, t.bob)).firstOrNull { it.religionManager.religionState <= ReligionState.Pantheon }
                    ?: GameOps.civOf(g, a).also { c -> c.religionManager.javaClass.getDeclaredField("religionState").apply { isAccessible = true }.set(c.religionManager, ReligionState.Pantheon) }
                val rm = civ.religionManager
                if (g.isReligionEnabled() && rm.religionState == ReligionState.None) {
                    rm.storedFaith += 1000
                    Features.beliefChoices(civ, BeliefType.Pantheon).firstOrNull()?.let { Features.foundPantheon(civ, it.id.removePrefix("b:")) }
                }
                if (rm.religionState == ReligionState.Pantheon) rm.freeBeliefs.add(BeliefType.Pantheon.name, 1)
                say("pantheon test civ ${civ.civName}: ${rm.religionState}, expandable=${InfoPlus.pantheonExpandable(civ)}")
                GameOps.turnWarnings(civ)
            }
            say("pantheon warnings: ${pw.filter { "antheon" in it || "Reform" in it }}")
            if (pw.any { "add a belief to your pantheon" in it } && pw.none { "Reform" in it }) tick("end-turn checklist: expand pantheon (not Reform)")

            // ---- 22. ⏩ AutoPlay this turn and end it
            val turn0 = t.eng { g -> g.turns }
            if (t.act(a, "uact", emptyList(), "do:up_apfull", null) && t.eng { g -> g.currentPlayerCiv.civID } == t.bob.civId) {
                say("full turn: " + st.flash.joinToString(" | ").take(200)); tick("AutoPlay full turn (ends the turn)")
            }
            st.flash.clear()
            if (t.eng { g -> g.currentPlayerCiv.civID } == t.bob.civId) t.act(t.bob, "end", listOf("force"), null, null)

            // ---- 23. 🔁 AutoPlay for N turns: plays this turn and the next one, then hands back
            if (t.eng { g -> g.currentPlayerCiv.civID } == a.civId) {
                if (t.act(a, "up_apstart", emptyList(), "2", null) && autoPlayLeft(meta, a) == 1 && meta.settings.extra.containsKey("up_autoplay.${a.userId}") &&
                    t.eng { g -> g.currentPlayerCiv.civID } == t.bob.civId) {
                    last()
                    t.act(t.bob, "end", listOf("force"), null, null)   // Alice's next turn is AutoPlayed inside the end-turn loop
                    val back = t.eng { g -> g.currentPlayerCiv.civID }
                    say("after Bob's end: current=$back left=${autoPlayLeft(meta, a)} events=${meta.events.takeLast(4)}")
                    if (back == t.bob.civId && autoPlayLeft(meta, a) == 0 && meta.events.any { "AutoPlay finished" in it }) tick("AutoPlay for N turns (keeps playing, then stops)")
                    if (t.eng { g -> g.turns } > turn0 + 1) say("played to turn ${t.eng { g -> g.turns }}")
                }
                bobSt.flash.clear()
                // any click stops it
                t.eng { setAutoPlay(meta, a, 5) }
                t.act(a, "tab", emptyList(), "UNITS", null)
                if (autoPlayLeft(meta, a) == 0 && st.flash.any { it.startsWith("⏹️") }) tick("AutoPlay stops when you click anything")
                if (commands().any { it.name == "autoplay" }) tick("/civ autoplay command")
            } else say("autoplay: Alice isn't current after the full turn")
        } finally {
            t.eng { _ -> val g2 = Engine.deserialize(snapshot); t.s.game = g2; UncivGame.Current.gameInfo = g2 }
            setAutoPlay(meta, a, 0); setAutoPlay(meta, t.bob, 0)
            bobSt.flash.clear()
        }
        st.unitId = null; st.tab = Tab.UNITS.name; st.flash.clear()
    }
}

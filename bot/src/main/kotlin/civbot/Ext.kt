package civbot

import com.unciv.logic.GameInfo
import com.unciv.logic.city.City
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.logic.map.tile.Tile
import com.unciv.models.metadata.GameSetupInfo
import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent
import net.dv8tion.jda.api.interactions.commands.build.SubcommandData
import net.dv8tion.jda.api.interactions.components.ActionRow
import net.dv8tion.jda.api.interactions.components.buttons.Button
import net.dv8tion.jda.api.interactions.components.selections.StringSelectMenu
import net.dv8tion.jda.api.utils.FileUpload

/*
 * ============================================================================================
 *  EXTENSION API
 *
 *  Feature areas live in their own files as `object XyzPlus : Extension`, registered in
 *  [Extensions.all]. An extension can add:
 *    - whole new panel tabs                       -> [Extension.tabs] + [Extension.renderTab]
 *    - options in the Unit actions / City actions -> [unitChoices]/[doUnitChoice], [cityChoices]/[doCityChoice]
 *    - new panel verbs (buttons / selects / modals)-> [handle]
 *    - text-input popups (modals)                 -> [prompts] + a component id/value "ask:<verb>[:<arg>]"
 *    - slash subcommands under /civ               -> [commands]/[onCommand]
 *    - game-setup changes (mods, maps…)           -> [applyToSetup], [onEngineInit], [lobbyLines]
 *    - turn hooks                                 -> [beforeMutation], [onTurnEnded], [advisorSkips]
 *    - self-test coverage                         -> [selfTest]
 *
 *  Everything except commands/onEngineInit runs on the engine thread (Unciv is single-threaded).
 *
 *  Discord limits to respect: max 5 rows per message, max 5 buttons per row, max 25 options per
 *  select, labels/descriptions/ids max 100 chars, embed field value max 1024 chars, 25 fields.
 *  Component ids are built with Panel.id(gid, verb, args...) => "c|<gid>|<verb>|<args...>".
 *
 *  DIRECT ACTIONS: a select option VALUE "do:<verb>[:<arg>]" (e.g. in Unit/City actions) is routed
 *  straight to [handle] as verb=<verb>, args=[<arg>], value=null — use it when you need the session.
 *
 *  SETTINGS: extensions keep lobby/game options in meta.settings.extra (String->String map, saved).
 *
 *  MODALS: put "ask:<verb>" (or "ask:<verb>:<arg>") as a select option VALUE, or use a button made
 *  with [TabCtx.askButton] (its component verb is "prompt" — don't use the verbs "ask"/"prompt" yourself). The bot shows the [Prompt] registered under <verb>; the text the
 *  player types arrives at [handle] as verb=<verb>, args=[<arg>] (if any), value=<text>.
 * ============================================================================================
 */

/** A text-input popup. */
data class Prompt(val title: String, val label: String, val placeholder: String = "", val long: Boolean = false)

/** A panel tab contributed by an extension. */
data class ExtTab(val id: String, val label: String, val emoji: String, val hint: String,
                  /** Hide the tab when it's irrelevant (e.g. espionage disabled) */
                  val visible: (Civilization) -> Boolean = { true })

/** Passed to [Extension.handle]. Mutate game state, add messages to [flash], set [changed] if saved state changed. */
class ActionCtx(
    val s: Session, val seat: Seat, val st: PanelState,
    val game: GameInfo, val civ: Civilization, val myTurn: Boolean,
    val args: List<String>, val value: String?,
) {
    var changed = false
    val flash get() = st.flash
    fun requireTurn() { if (!myTurn) throw PlayerError("It's not your turn — you can look around, but not act.") }
    fun selectedUnit(): MapUnit = st.unitId?.let { GameOps.unitById(civ, it) } ?: throw PlayerError("Select a unit first.")
    fun selectedCity(): City = civ.cities.firstOrNull { it.id == st.cityId } ?: throw PlayerError("Select a city first.")
    /** Resolve a tile code (like D5, from the last map shown) or a known city name. */
    fun tile(input: String): Tile = Controller.resolveTileFor(game, civ, st, input, GameOps.focusTile(civ).position)
    /** Per-extension scratch state for this player (survives between clicks, not restarts). */
    fun <T> ext(key: String, init: () -> T): T {
        @Suppress("UNCHECKED_CAST")
        return st.ext.getOrPut(key) { init() as Any } as T
    }
}

/** Passed to [Extension.renderTab]. Add fields to [eb], rows to [rows] (max 4 — the tab row is
 *  already there and the last row holds [endRow]), buttons to [endRow] (max 3 besides End turn). */
class TabCtx(
    val s: Session, val seat: Seat, val st: PanelState,
    val game: GameInfo, val civ: Civilization, val myTurn: Boolean,
    val eb: EmbedBuilder, val rows: MutableList<ActionRow>, val endRow: MutableList<Button>,
) {
    val gid get() = s.meta.id
    var file: FileUpload? = null
    /** Show the 🤖 Auto-pick button in the bottom row */
    var includeAdvisor = true
    fun id(verb: String, vararg args: String) = Panel.id(gid, verb, *args)
    fun select(verb: String, placeholder: String, choices: List<Choice>, selected: String? = null, enabled: Boolean = true, vararg args: String): StringSelectMenu =
        Panel.selectPublic(id(verb, *args), placeholder, choices, selected, enabled)
    fun multiSelect(verb: String, placeholder: String, choices: List<Choice>, selected: List<String>, enabled: Boolean = true): StringSelectMenu =
        Panel.multiSelectPublic(id(verb), placeholder, choices, selected, enabled)
    /** style: 1 blue, 2 grey, 3 green, 4 red */
    fun button(verb: String, label: String, emoji: String, style: Int = 2, enabled: Boolean = true, vararg args: String): Button =
        Panel.btnPublic(gid, verb, label, emoji, style, enabled, *args)
    /** A button that opens the [Prompt] registered for [verb]. */
    fun askButton(verb: String, label: String, emoji: String, style: Int = 2, enabled: Boolean = true, arg: String? = null): Button =
        Panel.btnPublic(gid, "prompt", label, emoji, style, enabled, *listOfNotNull(verb, arg).toTypedArray())
    fun mapAround(center: Tile, overlay: Overlay = Overlay(selectedTile = center)) { file = Panel.mapFilePublic(game, civ, st, center, overlay) }
    fun worldMap() { file = Panel.wholeMapFilePublic(game, civ) }
}

/** What the self-test gives extensions. */
class SelfTestCtx(
    val s: Session, val ctl: Controller,
    /** first human seat, guaranteed to be the current player when selfTest runs */
    val alice: Seat, val bob: Seat,
    /** apply a panel action as [seat]; returns false (and records nothing) on a PlayerError */
    val act: (seat: Seat, verb: String, args: List<String>, value: String?, feature: String?) -> Boolean,
    /** render the panel for [seat]; when tag != null also writes selftest-out/<tag>.png */
    val render: (seat: Seat, tag: String?) -> Unit,
) {
    fun <T> eng(block: (GameInfo) -> T): T = Engine.withGame(s.game!!) { g -> block(g) }
    fun civ(seat: Seat) = eng { g -> GameOps.civOf(g, seat) }
}

/** What extensions may use from the Discord side (commands). */
interface BotApi {
    val store: Store
    val ctl: Controller
    /** run off the gateway thread */
    fun async(block: () -> Unit)
    /** the game in this channel/thread that this user is in (or the first one here) */
    fun gameHere(channelId: String, userId: String): Session?
    /** re-draw the lobby message after changing settings */
    fun refreshLobby(meta: GameMeta)
}

interface Extension {
    val tabs: List<ExtTab> get() = emptyList()
    /** Your verbs that only change what's shown (no undo snapshot is taken for them) */
    val viewVerbs: Set<String> get() = emptySet()
    fun renderTab(tabId: String, t: TabCtx) {}
    /** Return true if this extension handled [verb]. Throw PlayerError for player-facing problems. */
    fun handle(verb: String, ctx: ActionCtx): Boolean = false
    val prompts: Map<String, Prompt> get() = emptyMap()

    fun unitChoices(u: MapUnit): List<Choice> = emptyList()
    /** Return a message if [id] was yours, else null. */
    fun doUnitChoice(u: MapUnit, id: String): String? = null
    fun cityChoices(city: City, window: MapWindow?): List<Choice> = emptyList()
    fun doCityChoice(city: City, id: String): String? = null

    fun commands(): List<SubcommandData> = emptyList()
    /** Return true if handled. Runs on the Discord gateway thread — reply/defer within 3 s. */
    fun onCommand(event: SlashCommandInteractionEvent, bot: BotApi): Boolean = false

    /** After Unciv is initialised (e.g. load mods). Engine thread. */
    fun onEngineInit() {}
    /** Build the whole game yourself (e.g. from a scenario save). Return null to use normal setup.
     *  Every seat's civ must end up with playerId "discord:<userId>" and PlayerType.Human. Engine thread. */
    fun createGame(meta: GameMeta): GameInfo? = null
    /** Change the new game's setup (mods, custom map, scenario). Engine thread. */
    fun applyToSetup(setup: GameSetupInfo, meta: GameMeta) {}
    /** Extra lines for the lobby's Settings field. */
    fun lobbyLines(meta: GameMeta): List<String> = emptyList()

    /** Called before any state-changing panel action (for undo snapshots). Engine thread. */
    fun beforeMutation(s: Session, seat: Seat, verb: String) {}
    /** Called after a turn ended. Engine thread. */
    fun onTurnEnded(s: Session) {}
    /** Things the advisor must NOT auto-handle for this civ: "spies", "vote", "alerts", "beliefs". */
    fun advisorSkips(civ: Civilization): Set<String> = emptySet()

    fun selfTest(t: SelfTestCtx) {}
}

object Extensions {
    /** Register feature files here. */
    val all: List<Extension> = listOf(CityPlus, MilitaryPlus, StatecraftPlus, InfoPlus, ModsPlus, UnitsPlus, DiplomacyPlus, SetupPlus)

    fun tab(id: String): Pair<Extension, ExtTab>? =
        all.asSequence().flatMap { e -> e.tabs.asSequence().map { e to it } }.firstOrNull { it.second.id == id }
    /** Popups used by core features */
    private val corePrompts = mapOf(
        "roadto" to Prompt("Connect road to…", "Tile code or city name", "e.g. D5 or a city you've seen"),
    )
    fun prompt(verb: String): Prompt? = corePrompts[verb] ?: all.firstNotNullOfOrNull { it.prompts[verb] }
    fun advisorSkips(civ: Civilization): Set<String> = all.flatMap { runCatching { it.advisorSkips(civ) }.getOrDefault(emptySet()) }.toSet()
}

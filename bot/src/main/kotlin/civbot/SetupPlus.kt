package civbot

import com.unciv.Constants
import com.unciv.UncivGame
import com.unciv.logic.CompatibilityVersion
import com.unciv.logic.GameInfo
import com.unciv.logic.files.IncompatibleGameInfoVersionException
import com.unciv.logic.MissingModsException
import com.unciv.logic.MissingNationException
import com.unciv.logic.civilization.AlertType
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.PlayerType
import com.unciv.logic.files.UncivFiles
import com.unciv.logic.map.MapParameters
import com.unciv.logic.map.MapShape
import com.unciv.logic.map.MapSize
import com.unciv.logic.map.MapType
import com.unciv.logic.map.MirroringType
import com.unciv.logic.map.mapgenerator.MapResourceSetting
import com.unciv.models.metadata.BaseRuleset
import com.unciv.models.metadata.GameParameters
import com.unciv.models.metadata.GameSetupInfo
import com.unciv.models.metadata.Player
import com.unciv.models.ruleset.Ruleset
import com.unciv.models.ruleset.RulesetCache
import com.unciv.models.ruleset.unique.UniqueType
import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.JDA
import net.dv8tion.jda.api.Permission
import net.dv8tion.jda.api.entities.MessageEmbed
import net.dv8tion.jda.api.entities.channel.middleman.MessageChannel
import net.dv8tion.jda.api.entities.emoji.Emoji
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent
import net.dv8tion.jda.api.hooks.ListenerAdapter
import net.dv8tion.jda.api.interactions.InteractionHook
import net.dv8tion.jda.api.interactions.components.ActionRow
import net.dv8tion.jda.api.interactions.components.buttons.Button
import net.dv8tion.jda.api.interactions.components.selections.SelectOption
import net.dv8tion.jda.api.interactions.components.selections.StringSelectMenu
import net.dv8tion.jda.api.interactions.commands.OptionType
import net.dv8tion.jda.api.interactions.commands.build.OptionData
import net.dv8tion.jda.api.interactions.commands.build.SubcommandData
import net.dv8tion.jda.api.interactions.commands.build.SubcommandGroupData
import net.dv8tion.jda.api.utils.FileUpload
import net.dv8tion.jda.api.utils.messages.MessageCreateBuilder
import net.dv8tion.jda.api.utils.messages.MessageEditBuilder
import org.slf4j.LoggerFactory
import java.io.File
import java.io.InputStream
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Every remaining Unciv new-game option, AI nation choice, and Unciv save export / import.
 *
 *  - `/civ setup <options>` (host, lobby only; no options = show the current values). Mirrors Unciv's
 *    NewGameScreen: GameOptionsTable (max turns, OCC, raging barbarians, nukes, razing, start bias, random
 *    civ / city-state counts, random nations pool, civilization stats), MapParametersTable (size incl. Huge,
 *    every map type, shape, resources, water level, mirroring, ruins, natural wonders, strategic balance,
 *    legendary start, seed, advanced sliders) and the player picker (specific AI nations, shuffled order).
 *  - `/civ save export`: the game as an Unciv save file (Engine.serialize = Unciv's own format). A save shows the whole
 *    unfogged map, so mid-game it's only for the host when the lobby turned `allow_export` on (`/civ lobby`), or for a
 *    lone human against the AI; anyone in the game may export once it has ended.
 *  - `/civ save import file:` (host or Manage Server, lobby only): start the lobby's game from an Unciv save.
 *  - Lobby lists (difficulties, speeds, eras, victories, nations with leader + ability, city-state maximum) come from the
 *    lobby's ruleset ([rules]); custom map sizes (radius or width × height) and the "Random" map type pool.
 *  - Turn history: the last [HISTORY_KEEP] start-of-turn snapshots per game in data/<id>.history/ (like Unciv's autosaves);
 *    `/civ rollback turn:` (host; every human player must accept) restores one.
 *
 * Settings live in meta.settings.extra under "su_*" keys (map size / type and the AI count use the core fields).
 */
object SetupPlus : Extension {
    private val log = LoggerFactory.getLogger("SetupPlus")

    const val K_MAX_TURNS = "su_maxTurns"
    const val K_RESOURCES = "su_resources"
    const val K_WATER = "su_water"
    const val K_SHAPE = "su_shape"
    const val K_MIRROR = "su_mirror"
    const val K_SEED = "su_seed"
    const val K_SLIDERS = "su_sliders"
    const val K_RAND_CIVS = "su_randCivs"
    const val K_RAND_CS = "su_randCs"
    const val K_POOL = "su_pool"
    const val K_AI_NATIONS = "su_aiNations"
    const val K_STATS = "su_stats"
    /** Absolute path of an uploaded save waiting in data/imports/ */
    const val K_IMPORT = "su_import"
    const val K_IMPORT_INFO = "su_importInfo"
    const val K_IMPORT_CIVS = "su_importCivs"
    /** "r=<radius>" or "w=<width>,h=<height>" when map size is [CUSTOM_SIZE] */
    const val K_CUSTOM_SIZE = "su_customSize"
    /** Map types "Random" may pick from (empty = all, like Unciv's checkbox list) */
    const val K_RANDOM_TYPES = "su_randTypes"
    /** "true": the host may export the save mid-game (players see this in the lobby before joining) */
    const val K_ALLOW_EXPORT = "su_allowExport"
    const val CUSTOM_SIZE = MapSize.custom
    /** Start-of-turn snapshots kept per game for /civ rollback */
    const val HISTORY_KEEP = 10
    /** Unciv's MapParametersTable warnings: LARGE_MAP_TILES / VERY_LARGE_MAP_TILES */
    private const val LARGE_TILES = 4000
    private const val VERY_LARGE_TILES = 16000

    const val MAX_SAVE_BYTES = 20L * 1024 * 1024
    /** Discord accepts 10 MB uploads from bots everywhere */
    const val MAX_EXPORT_BYTES = 10L * 1024 * 1024
    const val RANDOM_TYPE = "Random"
    private const val VERB_SELFTEST = "su_selftest_ok"
    private val DEFAULT_BASE = BaseRuleset.Civ_V_GnK.fullName

    /** Most major civilizations (players + AI) we let a generated map of each size hold. */
    val MAP_SIZE_CAP = linkedMapOf("Tiny" to 8, "Small" to 12, "Medium" to 16, "Large" to 22, "Huge" to 28)
    /** Custom maps / scenarios validate their own room (ModsPlus) */
    private const val OTHER_CAP = 28

    /** Unciv's "Water level" slider (-0.1 … 0.1) as choices */
    val WATER = linkedMapOf("-0.1" to "Much less water", "-0.05" to "Less water", "0" to "Normal", "0.05" to "More water", "0.1" to "Much more water")
    val MIRRORING = listOf(MirroringType.none, MirroringType.leftright, MirroringType.topbottom, MirroringType.fourway, MirroringType.aroundCenterTile)
    val RESOURCES = listOf(MapResourceSetting.sparse.label, MapResourceSetting.default.label, MapResourceSetting.abundant.label)

    // ================================================================== option tables

    /** An on/off option. [map] options are generation settings (ignored on custom maps). */
    private class Flag(val option: String, val key: String, val desc: String, val default: Boolean, val map: Boolean,
                       val onText: String, val offText: String, val set: (GameSetupInfo, Boolean) -> Unit)

    private val FLAGS = listOf(
        Flag("one_city", "su_occ", "One City Challenge: everyone keeps only their capital", false, false, "🏙️ One City Challenge", "") { s, v -> s.gameParameters.oneCityChallenge = v },
        Flag("raging_barbarians", "su_raging", "Raging Barbarians: more, earlier and angrier barbarians", false, false, "🔥 raging barbarians", "") { s, v -> s.gameParameters.ragingBarbarians = v },
        Flag("nukes", "su_nukes", "Nuclear weapons (default on)", true, false, "", "☢️ no nukes") { s, v -> s.gameParameters.nuclearWeaponsEnabled = v },
        Flag("no_razing", "su_noRazing", "No city razing", false, false, "🏚️ no razing", "") { s, v -> s.gameParameters.noCityRazing = v },
        Flag("no_start_bias", "su_noStartBias", "Disable starting bias (nations ignore their preferred terrain)", false, false, "🎯 no start bias", "") { s, v -> s.gameParameters.noStartBias = v },
        Flag("shuffle_order", "su_shuffle", "Shuffle the civ turn order at start", false, false, "🔀 shuffled turn order", "") { s, v -> s.gameParameters.shufflePlayerOrder = v },
        Flag("no_ruins", "su_noRuins", "No ancient ruins", false, true, "no ruins", "") { s, v -> s.mapParameters.noRuins = v },
        Flag("no_wonders", "su_noWonders", "No natural wonders", false, true, "no natural wonders", "") { s, v -> s.mapParameters.noNaturalWonders = v },
        Flag("strategic_balance", "su_strategic", "Strategic Balance: strategic resources near every start", false, true, "⚖️ Strategic Balance", "") { s, v -> s.mapParameters.strategicBalance = v },
        Flag("legendary_start", "su_legendary", "Legendary Start: extra-good starting spots", false, true, "🌟 Legendary Start", "") { s, v -> s.mapParameters.legendaryStart = v },
    )

    /** Unciv's advanced map sliders (MapParametersTable.addAdvancedControls) */
    private class Slider(val key: String, val label: String, val min: Float, val max: Float, val default: Float, val isInt: Boolean,
                         val set: (MapParameters, Float) -> Unit)

    private val SLIDERS = listOf(
        Slider("elevation", "Map Elevation", 0.6f, 0.8f, 0.7f, false) { p, v -> p.elevationExponent = v },
        Slider("temperature", "Temperature intensity", 0.4f, 0.8f, 0.6f, false) { p, v -> p.temperatureintensity = v },
        Slider("shift", "Temperature shift", -0.4f, 0.4f, 0f, false) { p, v -> p.temperatureShift = v },
        Slider("vegetation", "Vegetation richness", 0f, 1f, 0.4f, false) { p, v -> p.vegetationRichness = v },
        Slider("rare", "Rare features richness", 0f, 0.5f, 0.05f, false) { p, v -> p.rareFeaturesRichness = v },
        Slider("richness", "Resource richness", 0f, 0.5f, 0.1f, false) { p, v -> p.resourceRichness = v },
        Slider("coast", "Max Coast extension", 1f, 5f, 3f, true) { p, v -> p.maxCoastExtension = v.toInt() },
        Slider("biome", "Biome size", 1f, 15f, 6f, true) { p, v -> p.tilesPerBiomeArea = v.toInt() },
    )

    /** GameOptionsTable's "Show Civilization Stats" block */
    private class Stat(val key: String, val label: String, val default: Boolean, val set: (GameParameters, Boolean) -> Unit)

    private val STATS = listOf(
        Stat("civ_stats", "Show Civilization Stats", true) { g, v -> g.showCivilizationStats = v },
        Stat("rankings", "Show Rankings", true) { g, v -> g.showRankings = v },
        Stat("charts", "Show Charts", true) { g, v -> g.showCharts = v },
        Stat("demographics", "Show Demographics", false) { g, v -> g.showDemographics = v },
        Stat("own_only", "Restrict to own civilization", false) { g, v -> g.hideOtherCivilizationStats = v },
        Stat("extra_types", "Show additional stat types", false) { g, v -> g.showAdditionalRankingTypes = v },
    )

    // ================================================================== small helpers

    private fun isDefaultWord(s: String) = s.trim().lowercase() in setOf("", "default", "none", "off", "-", "clear", "random", "reset", "generated")
    private fun fmt(f: Float): String = "%.3f".format(java.util.Locale.ROOT, f).trimEnd('0').trimEnd('.').let { if (it == "-0") "0" else it }
    private fun clip(s: String, n: Int = 1950) = if (s.length <= n) s else s.take(n - 10) + "\n…"
    private fun list(v: String?): List<String> = v.orEmpty().split(",").map { it.trim() }.filter { it.isNotEmpty() }
    private fun range(v: String?): IntRange? {
        val p = v?.split("-") ?: return null
        if (p.size != 2) return null
        val a = p[0].trim().toIntOrNull() ?: return null
        val b = p[1].trim().toIntOrNull() ?: return null
        return a..b
    }
    private fun flagValue(ex: Map<String, String>, f: Flag) = ex[f.key]?.toBooleanStrictOrNull() ?: f.default
    private fun pairs(v: String?): Map<String, String> =
        list(v).mapNotNull { p -> p.split("=").takeIf { it.size == 2 }?.let { it[0].trim() to it[1].trim() } }.toMap()

    private fun customMap(meta: GameMeta) = meta.settings.extra[ModsPlus.K_MAP]?.takeIf { !isDefaultWord(it) }
    private fun scenario(meta: GameMeta) = meta.settings.extra[ModsPlus.K_SCENARIO]?.takeIf { !isDefaultWord(it) }
    private fun importPath(meta: GameMeta) = meta.settings.extra[K_IMPORT]?.takeIf { it.isNotBlank() }

    /** Engine thread. The lobby's ruleset (same lookup as GameOps.majorNations). */
    private fun rulesetE(meta: GameMeta): Ruleset {
        val base = meta.settings.extra[ModsPlus.K_BASE]?.takeIf { !isDefaultWord(it) } ?: DEFAULT_BASE
        return try {
            if (meta.settings.mods.isNotEmpty() || base != DEFAULT_BASE) RulesetCache.getComplexRuleset(LinkedHashSet(meta.settings.mods), base)
            else RulesetCache[DEFAULT_BASE]!!
        } catch (_: Exception) { RulesetCache[DEFAULT_BASE]!! }
    }
    private fun pickableMajors(rs: Ruleset) = rs.nations.values.filter { it.isMajorCiv && !it.hasUnique(UniqueType.WillNotBeChosenForNewGames) }.map { it.name }
    private fun pickableCityStates(rs: Ruleset) = rs.nations.values.count { it.isCityState && !it.hasUnique(UniqueType.WillNotBeChosenForNewGames) }

    // ================================================================== the lobby's ruleset lists (base + selected mods)

    /** A nation as the lobby's picker shows it (Unciv's NationTable: leader + unique ability). */
    class NationInfo(val name: String, val leader: String, val abilityName: String, val ability: String) {
        /** One line for a select option (max 100 chars) */
        val description: String get() = cut(listOf(leader.takeIf { it.isNotEmpty() }?.let { "👑 $it" },
            (if (abilityName.isNotEmpty()) "$abilityName: " else "") + ability).filterNotNull().filter { it.isNotBlank() }.joinToString(" · "), 100)
    }

    /** What the lobby may offer for this ruleset (Unciv's GameOptionsTable lists). */
    class LobbyRules(val difficulties: List<String>, val speeds: List<String>, val eras: List<String>, val victories: List<String>,
                     val maxCityStates: Int, val nations: List<NationInfo>, internal val ruleset: Ruleset) {
        fun nation(name: String) = nations.firstOrNull { it.name.equals(name, true) }
    }

    private val rulesCache = java.util.concurrent.ConcurrentHashMap<String, LobbyRules>()
    private fun rulesKey(meta: GameMeta) =
        (meta.settings.extra[ModsPlus.K_BASE]?.takeIf { !isDefaultWord(it) } ?: DEFAULT_BASE) + "|" + meta.settings.mods.joinToString("|")
    /** Forget cached lists (mods were installed / removed). */
    fun clearRulesCache() = rulesCache.clear()
    private fun onEngineThread() = Thread.currentThread().name == "unciv-engine"
    private fun cut(s: String, n: Int) = if (s.length <= n) s else s.take(n - 1).trimEnd() + "…"

    /** Any thread: the lobby's lists (cached; built on the engine thread on a miss). */
    fun rules(meta: GameMeta): LobbyRules = rulesCache[rulesKey(meta)] ?: if (onEngineThread()) rulesE(meta) else Engine.run { rulesE(meta) }

    /** Engine thread. */
    fun rulesE(meta: GameMeta): LobbyRules = rulesCache.getOrPut(rulesKey(meta)) {
        val rs = rulesetE(meta)
        val eras = rs.eras.keys.toList().let { if (rs.modOptions.hasUnique(UniqueType.CanOnlyStartFromStartingEra)) it.take(1) else it }
        val nations = rs.nations.values.filter { it.isMajorCiv && !it.hasUnique(UniqueType.WillNotBeChosenForNewGames) }.map { n ->
            val ability = n.uniqueText.takeIf { it.isNotBlank() }?.let { InfoPlus.tx(it) }
                ?: n.uniques.joinToString("; ") { InfoPlus.tx(it) }
            NationInfo(n.name, InfoPlus.tx(n.leaderName), InfoPlus.tx(n.uniqueName), ability)
        }.sortedBy { it.name }
        LobbyRules(rs.difficulties.keys.toList(), rs.speeds.keys.toList(), eras, rs.selectableVictories().map { it.name },
            pickableCityStates(rs), nations, rs)
    }

    /** Engine thread. Puts lobby values that the (new) ruleset doesn't have back to sane defaults; returns notes. */
    fun normalizeE(meta: GameMeta): List<String> {
        val r = rulesE(meta)
        val st = meta.settings
        val notes = ArrayList<String>()
        if (r.difficulties.isNotEmpty() && st.difficulty !in r.difficulties) {
            val d = "Prince".takeIf { it in r.difficulties } ?: r.difficulties[r.difficulties.size / 2]
            notes += "⚖️ These rules have no **${st.difficulty}** difficulty — set to **$d**."; st.difficulty = d
        }
        if (r.speeds.isNotEmpty() && st.speed !in r.speeds) {
            val sp = listOf("Quick", "Standard").firstOrNull { it in r.speeds } ?: r.speeds.first()
            notes += "⏱️ These rules have no **${st.speed}** speed — set to **$sp**."; st.speed = sp
        }
        if (r.eras.isNotEmpty() && st.startEra !in r.eras) {
            notes += "🏺 These rules can't start in the **${st.startEra}** — starting in the **${r.eras.first()}**."; st.startEra = r.eras.first()
        }
        if (st.victoryTypes.isNotEmpty()) {
            val keep = st.victoryTypes.filter { it in r.victories }
            if (keep.size != st.victoryTypes.size) {
                notes += "🏆 Victory types not in these rules were dropped (${st.victoryTypes.filter { it !in r.victories }.joinToString()})" + if (keep.isEmpty()) " — all victories are on." else "."
                st.victoryTypes = ArrayList(keep)
            }
        }
        if (st.cityStates > r.maxCityStates) { notes += "🏰 These rules have ${r.maxCityStates} city-states — lowered from ${st.cityStates}."; st.cityStates = r.maxCityStates }
        for (seat in meta.seats) if (seat.nation != Constants.random && r.nation(seat.nation) == null) {
            notes += "🏳️ <@${seat.userId}>'s nation **${seat.nation}** isn't in these rules — back to random."; seat.nation = Constants.random
        }
        return notes
    }

    /** A select option for the lobby (Bot builds the Discord component). */
    data class LobbyOption(val label: String, val value: String, val default: Boolean)

    private const val CS_PAGE = 23

    /** Pages of the lobby's city-state dropdown: 0 … the ruleset's maximum, 23 per page plus ◀/▶ ("pg:<n>") entries. */
    fun cityStateOptions(meta: GameMeta, page: Int = -1): List<LobbyOption> {
        val max = rules(meta).maxCityStates
        val cur = meta.settings.cityStates
        fun opt(n: Int) = LobbyOption(if (n == 0) "No city-states" else "$n city-states", n.toString(), n == cur)
        if (max + 1 <= 25) return (0..max).map { opt(it) }
        val pages = (max + CS_PAGE) / CS_PAGE   // values 0..max
        val p = (if (page >= 0) page else cur / CS_PAGE).coerceIn(0, pages - 1)
        val out = ArrayList<LobbyOption>()
        if (p > 0) out += LobbyOption("◀ ${(p - 1) * CS_PAGE}–${p * CS_PAGE - 1} city-states", "pg:${p - 1}", false)
        for (n in p * CS_PAGE..minOf(max, (p + 1) * CS_PAGE - 1)) out += opt(n)
        if (p < pages - 1) out += LobbyOption("▶ ${(p + 1) * CS_PAGE}–${minOf(max, (p + 2) * CS_PAGE - 1)} city-states", "pg:${p + 1}", false)
        return out
    }

    /** Most city-states this lobby can have (Unciv's slider maximum: the ruleset's pickable city-states). */
    fun maxCityStates(meta: GameMeta): Int = rules(meta).maxCityStates

    // ---- nation picker (lobby): Unciv's player picker hides "Will not be chosen for new games" nations

    /** Up to 4 nation selects ([verb] gets each picked value) + an optional "Nation details…" button row. */
    fun nationPickerRows(meta: GameMeta, uid: String, verb: String = "pick", withDetails: Boolean = true, withRandom: Boolean = true): List<ActionRow> {
        val taken = meta.seats.filter { it.userId != uid }.map { it.nation }.toSet()
        val nations = rules(meta).nations.filter { it.name !in taken }
        val rows = ArrayList<ActionRow>()
        for ((i, chunk) in nations.chunked(24).take(4).withIndex()) {
            val opts = ArrayList<SelectOption>()
            if (i == 0 && withRandom) opts += SelectOption.of(Constants.random, Constants.random).withDescription("🎲 Any nation — decided when the game starts")
            for (n in chunk) opts += SelectOption.of(n.name.take(100), n.name.take(100)).let { o -> n.description.takeIf { it.isNotEmpty() }?.let { o.withDescription(it) } ?: o }
            rows += ActionRow.of(StringSelectMenu.create(Panel.id(meta.id, verb, i.toString()))
                .setPlaceholder("Nations ${chunk.first().name.first()}–${chunk.last().name.first()}").addOptions(opts).build())
        }
        if (withDetails) rows += ActionRow.of(Button.secondary(Panel.id(meta.id, "ndet"), "Nation details…").withEmoji(Emoji.fromUnicode("📖")))
        return rows
    }

    /** Unciv's NationTable as an embed: leader, ability, start bias, unique units/buildings/improvements. */
    fun nationDetails(meta: GameMeta, name: String): MessageEmbed {
        val r = rules(meta)
        val info = r.nation(name) ?: throw PlayerError("**$name** isn't a nation you can pick in this game.")
        val compute = { val n = r.ruleset.nations[info.name]!!; InfoPlus.tx(n.getLeaderDisplayName()) to InfoPlus.pediaLines(n, r.ruleset).joinToString("\n") }
        val (title, body) = if (onEngineThread()) compute() else Engine.run(compute)
        return EmbedBuilder().setColor(java.awt.Color(46, 134, 193)).setTitle("🏳️ ${cut(title, 250)}")
            .setDescription(cut(body.ifBlank { info.description }, 4000))
            .setFooter("${meta.name} · ${r.nations.size} nations to pick from").build()
    }

    // ================================================================== caps (used by the core lobby, no engine needed)

    /** How many major civilizations (players + AI) this lobby's map can hold. */
    fun maxMajors(meta: GameMeta): Int {
        if (importPath(meta) != null) return meta.settings.extra[K_IMPORT_CIVS]?.toIntOrNull() ?: OTHER_CAP
        if (scenario(meta) != null || customMap(meta) != null) return OTHER_CAP
        return sizeCap(meta)
    }

    /** The host's custom map size (null unless map size is Custom with dimensions). Pure, any thread. */
    fun customSize(meta: GameMeta): MapSize? {
        if (meta.settings.mapSize != CUSTOM_SIZE) return null
        val p = pairs(meta.settings.extra[K_CUSTOM_SIZE])
        p["r"]?.toIntOrNull()?.let { return MapSize(it) }
        val w = p["w"]?.toIntOrNull() ?: return null
        val h = p["h"]?.toIntOrNull() ?: return null
        return MapSize(w, h)
    }
    private fun sizeCap(meta: GameMeta): Int =
        customSize(meta)?.let { MAP_SIZE_CAP[it.getPredefinedOrNextSmaller().name] } ?: MAP_SIZE_CAP[meta.settings.mapSize] ?: 12
    private fun byRadius(meta: GameMeta) = pairs(meta.settings.extra[K_CUSTOM_SIZE]).containsKey("r")
    private fun isRectShape(meta: GameMeta) = (meta.settings.extra[K_SHAPE] ?: MapShape.hexagonal) == MapShape.rectangular
    /** Tile count of the custom size for the lobby's shape */
    private fun tilesOf(meta: GameMeta, size: MapSize) =
        if (isRectShape(meta)) size.width * size.height else com.unciv.logic.map.HexMath.getNumberOfTilesInHexagon(size.radius)
    private fun sizeText(meta: GameMeta, size: MapSize) = if (byRadius(meta)) "radius ${size.radius}" else "${size.width}×${size.height}"

    /** Highest value for the lobby's AI dropdown (a select holds 25 options: 0..24). */
    fun maxAiChoice(meta: GameMeta): Int = (maxMajors(meta) - meta.seats.size).coerceIn(1, 24)

    /** Lobby can't take another human player. */
    fun isFull(meta: GameMeta): Boolean =
        if (importPath(meta) != null) meta.seats.size >= maxMajors(meta)
        else meta.seats.size + (if (meta.settings.extra[K_RAND_CIVS] != null) 0 else meta.settings.aiCivs) >= maxMajors(meta)

    // ================================================================== /civ setup: validate + apply

    /** Validates and applies [opts] (option name -> raw value). NOT on the engine thread. */
    fun updateSetup(meta: GameMeta, opts: Map<String, String>): String = synchronized(meta) { Engine.run { updateSetupE(meta, opts) } }

    private fun copyMeta(meta: GameMeta, ex: HashMap<String, String>, size: String, type: String, ai: Int): GameMeta {
        val st = meta.settings
        val s2 = Settings(mapSize = size, mapType = type, difficulty = st.difficulty, speed = st.speed, aiCivs = ai, cityStates = st.cityStates,
            barbarians = st.barbarians, fogOfWar = st.fogOfWar, startEra = st.startEra, victoryTypes = ArrayList(st.victoryTypes),
            worldWrap = st.worldWrap, mods = ArrayList(st.mods), extra = ex)
        return GameMeta(id = meta.id, name = meta.name, hostId = meta.hostId, status = meta.status, settings = s2, seats = meta.seats)
    }

    private fun updateSetupE(meta: GameMeta, opts: Map<String, String>): String {
        if (meta.status != Status.LOBBY) throw PlayerError("The game has already started — `/civ setup` only works in the lobby.")
        val st = meta.settings
        val ex = HashMap(st.extra)
        var size = st.mapSize
        var type = st.mapType
        var ai = st.aiCivs
        val notes = ArrayList<String>()
        var radius: Int? = null
        var width: Int? = null
        var height: Int? = null
        val rs = rulesetE(meta)
        val majors = GameOps.majorNations(meta)
        fun put(k: String, v: String?) { if (v == null) ex.remove(k) else ex[k] = v }
        fun nation(n: String): String = majors.firstOrNull { it.equals(n.trim(), true) }
            ?: throw PlayerError("There's no nation called **${n.trim()}** in this game's rules. Choices: ${majors.joinToString()}".take(1500))
        fun nations(v: String, what: String): List<String> {
            val out = list(v).map { nation(it) }
            val dup = out.groupBy { it }.filter { it.value.size > 1 }.keys
            if (dup.isNotEmpty()) throw PlayerError("$what lists ${dup.joinToString { "**$it**" }} twice.")
            return out
        }

        for ((name, raw) in opts) {
            val v = raw.trim()
            val flag = FLAGS.firstOrNull { it.option == name }
            if (flag != null) {
                val b = v.toBooleanStrictOrNull() ?: throw PlayerError("`$name` takes True or False.")
                put(flag.key, if (b == flag.default) null else b.toString())
                continue
            }
            when (name) {
                "map_size" -> size = (MAP_SIZE_CAP.keys + CUSTOM_SIZE).firstOrNull { it.equals(v, true) }
                    ?: throw PlayerError("Map sizes: ${(MAP_SIZE_CAP.keys + CUSTOM_SIZE).joinToString()}.")
                "radius" -> radius = v.toIntOrNull() ?: throw PlayerError("`radius` is a whole number of tiles from the centre.")
                "width" -> width = v.toIntOrNull() ?: throw PlayerError("`width` is a whole number of tiles.")
                "height" -> height = v.toIntOrNull() ?: throw PlayerError("`height` is a whole number of tiles.")
                "random_types" -> put(K_RANDOM_TYPES, if (isDefaultWord(v) || v.trim().equals("all", true)) null else list(v).map { n ->
                    MapType.allValues.firstOrNull { it.equals(n, true) } ?: throw PlayerError("**$n** isn't a map type. Types: ${MapType.allValues.joinToString()}.")
                }.distinct().joinToString(","))
                "map_options" -> parseMapOptions(v, ex)
                "map_type" -> type = (MapType.allValues + RANDOM_TYPE).firstOrNull { it.equals(v, true) }
                    ?: throw PlayerError("Map types: ${(MapType.allValues + RANDOM_TYPE).joinToString()}.")
                "shape" -> put(K_SHAPE, (MapShape.allValues.firstOrNull { it.equals(v, true) } ?: throw PlayerError("Map shapes: ${MapShape.allValues.joinToString()}."))
                    .takeIf { it != MapShape.hexagonal })
                "resources" -> put(K_RESOURCES, (RESOURCES.firstOrNull { it.equals(v, true) } ?: throw PlayerError("Resource settings: ${RESOURCES.joinToString()}."))
                    .takeIf { it != MapResourceSetting.default.label })
                "water" -> put(K_WATER, parseWater(v))
                "mirroring" -> put(K_MIRROR, (MIRRORING.firstOrNull { it.equals(v, true) } ?: throw PlayerError("Mirroring: ${MIRRORING.joinToString()}."))
                    .takeIf { it != MirroringType.none })
                "seed" -> put(K_SEED, if (isDefaultWord(v)) null else (v.toLongOrNull()?.toString() ?: throw PlayerError("The seed is a whole number (or `random`).")))
                "map_sliders" -> if (isDefaultWord(v)) { put(K_SLIDERS, null); put(K_WATER, null) } else {
                    // water=… is Unciv's "Water level" slider too
                    val (water, rest) = v.split(Regex("[,;\\s]+")).filter { it.isNotBlank() }.partition { it.substringBefore("=").trim().equals("water", true) }
                    water.lastOrNull()?.let { put(K_WATER, parseWater(it.substringAfter("=", ""))) }
                    if (rest.isNotEmpty()) put(K_SLIDERS, parseSliders(rest.joinToString(" "), pairs(ex[K_SLIDERS])))
                }
                "max_turns" -> {
                    val n = v.toIntOrNull()?.takeIf { it in 100..1500 } ?: throw PlayerError("Max turns goes from 100 to 1500 (Unciv's range).")
                    put(K_MAX_TURNS, if (n == 500) null else n.toString())
                }
                "ai" -> ai = v.toIntOrNull()?.takeIf { it >= 0 } ?: throw PlayerError("`ai` is the number of AI opponents.")
                "ai_nations" -> {
                    val names = if (isDefaultWord(v)) emptyList() else nations(v, "`ai_nations`")
                    if (names.size > ai) { notes += "🤖 Raised AI opponents from $ai to ${names.size} to fit the AI nations."; ai = names.size }
                    put(K_AI_NATIONS, names.joinToString(",").ifEmpty { null })
                }
                "nations_pool" -> {
                    val names = if (isDefaultWord(v)) emptyList() else nations(v, "`nations_pool`")
                    put(K_POOL, names.joinToString(",").ifEmpty { null })
                }
                "random_civs", "random_city_states" -> {
                    val key = if (name == "random_civs") K_RAND_CIVS else K_RAND_CS
                    if (isDefaultWord(v)) put(key, null)
                    else {
                        val r = range(v.replace(" ", "")) ?: v.toIntOrNull()?.let { it..it }
                            ?: throw PlayerError("`$name` takes a range like `3-8` (or `off`).")
                        if (r.first > r.last) throw PlayerError("`$name`: the minimum (${r.first}) is above the maximum (${r.last}).")
                        put(key, "${r.first}-${r.last}")
                    }
                }
                "stats" -> put(K_STATS, if (v.equals("default", true) || v.equals("on", true)) null else parseStats(v, pairs(ex[K_STATS])))
                else -> throw PlayerError("Unknown setup option `$name`.")
            }
        }
        val dims = radius != null || width != null || height != null
        if (dims && size != CUSTOM_SIZE) {
            if (opts.containsKey("map_size")) throw PlayerError("`radius` / `width` / `height` only apply with `map_size:Custom`.")
            size = CUSTOM_SIZE; notes += "📐 Map size set to **Custom**."
        }
        if (size == CUSTOM_SIZE) {
            if (radius != null && (width != null || height != null))
                throw PlayerError("Give either `radius:` (hexagonal maps) or `width:` + `height:` (rectangular maps), not both.")
            if (radius != null) put(K_CUSTOM_SIZE, "r=$radius")
            else if (width != null || height != null) {
                val old = pairs(ex[K_CUSTOM_SIZE])
                val w = width ?: old["w"]?.toIntOrNull() ?: throw PlayerError("Give both `width:` and `height:` for a rectangular custom size.")
                val h = height ?: old["h"]?.toIntOrNull() ?: throw PlayerError("Give both `width:` and `height:` for a rectangular custom size.")
                put(K_CUSTOM_SIZE, "w=$w,h=$h")
            }
        }
        if (ex[K_RANDOM_TYPES] != null && type != RANDOM_TYPE) notes += "🎲 `random_types` only matters when the map type is **Random**."
        notes += validateE(copyMeta(meta, ex, size, type, ai), rs)
        // commit only after the whole setup checked out
        st.extra.clear(); st.extra.putAll(ex)
        st.mapSize = size; st.mapType = type; st.aiCivs = ai
        val summary = summaryParts(meta)
        return (listOf("✅ Setup updated.", if (summary.isEmpty()) "All /civ setup options are at Unciv's defaults." else "⚙️ " + summary.joinToString(" · ")) + notes).joinToString("\n")
    }

    private fun parseWater(v: String): String? {
        if (isDefaultWord(v)) return null
        val f = v.trim().toFloatOrNull()?.takeIf { it in -0.1f..0.1f } ?: throw PlayerError("Water level goes from -0.1 (much less water) to 0.1 (much more).")
        return if (f == 0f) null else fmt(f)
    }

    /** `map_options: no_ruins=on legendary_start=on …` — the map-generation switches of MapParametersTable */
    private fun parseMapOptions(v: String, ex: HashMap<String, String>) {
        val mapFlags = FLAGS.filter { it.map }
        if (isDefaultWord(v)) { mapFlags.forEach { ex.remove(it.key) }; return }
        for (p in v.split(Regex("[,;\\s]+")).filter { it.isNotBlank() }) {
            val kv = p.split("=")
            val f = mapFlags.firstOrNull { it.option.equals(kv[0].trim(), true) }
                ?: throw PlayerError("Unknown map option `${kv[0].trim()}`. Map options: ${mapFlags.joinToString { "`${it.option}`" }} (each `=on` or `=off`).")
            val b = if (kv.size == 1) true else when (kv[1].trim().lowercase()) {
                "on", "true", "yes", "1" -> true; "off", "false", "no", "0" -> false; else -> throw PlayerError("`${f.option}` takes on or off.")
            }
            if (b == f.default) ex.remove(f.key) else ex[f.key] = b.toString()
        }
    }

    private fun parseSliders(v: String, old: Map<String, String>): String? {
        val out = LinkedHashMap(old)
        for (p in v.split(Regex("[,;\\s]+")).filter { it.isNotBlank() }) {
            val kv = p.split("=")
            if (kv.size != 2) throw PlayerError("Write sliders as `name=value`, e.g. `elevation=0.75 coast=4`. Names: ${SLIDERS.joinToString { it.key }}.")
            val s = SLIDERS.firstOrNull { it.key.equals(kv[0].trim(), true) }
                ?: throw PlayerError("Unknown slider `${kv[0]}`. Sliders: ${SLIDERS.joinToString { "${it.key} (${fmt(it.min)}…${fmt(it.max)})" }}.")
            if (isDefaultWord(kv[1])) { out.remove(s.key); continue }
            val f = kv[1].trim().toFloatOrNull()?.takeIf { it in s.min..s.max && (!s.isInt || it == it.toInt().toFloat()) }
                ?: throw PlayerError("${s.label} (`${s.key}`) goes from ${fmt(s.min)} to ${fmt(s.max)}${if (s.isInt) " in whole steps" else ""}.")
            if (f == s.default) out.remove(s.key) else out[s.key] = fmt(f)
        }
        return out.entries.joinToString(",") { "${it.key}=${it.value}" }.ifEmpty { null }
    }

    private fun parseStats(v: String, old: Map<String, String>): String? {
        val out = LinkedHashMap(old)
        if (v.equals("off", true) || v.equals("hidden", true)) out["civ_stats"] = "off"
        else for (p in v.split(Regex("[,;\\s]+")).filter { it.isNotBlank() }) {
            val kv = p.split("=")
            if (kv.size != 2) throw PlayerError("Write stats as `name=on/off`, e.g. `demographics=on rankings=off`. Names: ${STATS.joinToString { it.key }}.")
            val s = STATS.firstOrNull { it.key.equals(kv[0].trim(), true) } ?: throw PlayerError("Unknown stats option `${kv[0]}`. Options: ${STATS.joinToString { it.key }}.")
            val b = when (kv[1].trim().lowercase()) { "on", "true", "yes", "1" -> true; "off", "false", "no", "0" -> false; else -> throw PlayerError("`${s.key}` takes on or off.") }
            if (b == s.default) out.remove(s.key) else out[s.key] = if (b) "on" else "off"
        }
        return out.entries.joinToString(",") { "${it.key}=${it.value}" }.ifEmpty { null }
    }

    private fun statValue(meta: GameMeta, s: Stat): Boolean = pairs(meta.settings.extra[K_STATS])[s.key]?.let { it == "on" } ?: s.default

    /**
     * Engine thread. Checks the whole setup the way Unciv's NewGameScreen / GameStarter would see it.
     * Throws PlayerError for combinations that can't start; returns warnings.
     */
    fun validateE(meta: GameMeta, rs: Ruleset = rulesetE(meta)): List<String> {
        val st = meta.settings
        val ex = st.extra
        val notes = ArrayList<String>()
        if (importPath(meta) != null) { notes += "📥 An imported save is selected — it brings its own map, civs and options, so these settings are ignored."; return notes }
        if (scenario(meta) != null) { notes += "📜 A scenario is selected — it brings its own map and civs, so these settings are ignored."; return notes }
        val custom = customMap(meta)
        val humans = meta.seats.size
        val majorsAll = GameOps.majorNations(meta)
        val pickable = pickableMajors(rs)
        val aiNames = list(ex[K_AI_NATIONS])
        val pool = list(ex[K_POOL])
        val randCivs = range(ex[K_RAND_CIVS])
        val randCs = range(ex[K_RAND_CS])

        for (n in aiNames + pool) if (n !in majorsAll)
            throw PlayerError("**$n** isn't a nation in this game's rules any more (mods changed?) — fix `/civ setup ai_nations:` / `nations_pool:`.")
        val picked = meta.seats.map { it.nation }.filter { it != Constants.random }
        val clash = aiNames.filter { it in picked }
        if (clash.isNotEmpty()) throw PlayerError("${clash.joinToString { "**$it**" }} is picked by a player and also set as an AI nation — change `/civ setup ai_nations:`.")
        if (aiNames.size > st.aiCivs) throw PlayerError("You named ${aiNames.size} AI nations but there are only ${st.aiCivs} AI opponents — raise the AI count or shorten `ai_nations`.")

        // how many major civs the game can end up with
        val majors = randCivs?.last?.coerceAtLeast(humans) ?: (humans + st.aiCivs)
        if (randCivs != null) {
            if (randCivs.first < 2) throw PlayerError("A random number of civilizations needs a minimum of at least 2 (Unciv's slider starts at 2).")
            if (randCivs.last > pickable.size) throw PlayerError("These rules have ${pickable.size} nations, so `random_civs` can go up to ${pickable.size}.")
            if (randCivs.last < humans) notes += "🎲 The random civ count is below the number of players, so the game will have exactly the $humans players."
            notes += "🎲 With `random_civs` the AI count is ignored: the game picks ${randCivs.first}–${randCivs.last} civilizations (named AI nations may be dropped)."
        }
        if (custom == null) {
            checkCustomSize(meta, notes)
            val cap = sizeCap(meta)
            val sizeName = customSize(meta)?.let { "custom (${sizeText(meta, it)})" } ?: st.mapSize
            if (majors > cap) throw PlayerError("A **$sizeName** map fits at most $cap civilizations, but this setup can have $majors " +
                "($humans players + ${majors - humans} AI). Pick a bigger map size or fewer AI opponents.")
        }
        if (pool.isEmpty() && majors > pickable.size) throw PlayerError("These rules only have ${pickable.size} nations, but the game needs $majors (players + AI).")
        val csAvail = pickableCityStates(rs)
        if (randCs != null) {
            if (randCs.last > csAvail) throw PlayerError("These rules have $csAvail city-states, so `random_city_states` can go up to $csAvail.")
            notes += "🏰 With `random_city_states` the city-state count is ignored: ${randCs.first}–${randCs.last}."
        } else if (st.cityStates > csAvail) notes += "🏰 Only $csAvail city-states exist in these rules — the game will have $csAvail."
        if (pool.isNotEmpty()) {
            // GameStarter draws every "Random" civ from the pool and silently drops players it can't fill — never let that hit a human
            val explicit = (picked + aiNames).toSet()
            val left = pool.filter { it !in explicit }
            val randomHumans = meta.seats.count { it.nation == Constants.random }
            val randomAis = (if (randCivs != null) (majors - humans - aiNames.size) else (st.aiCivs - aiNames.size)).coerceAtLeast(0)
            if (left.size < randomHumans + randomAis) throw PlayerError("The random nations pool has ${left.size} nation(s) left after the fixed picks, " +
                "but ${randomHumans + randomAis} civilizations will be drawn from it ($randomHumans random player(s) + $randomAis random AI). Add nations to `nations_pool` or turn it off.")
        }
        if (ex[K_MAX_TURNS] != null) {
            val vic = st.victoryTypes.ifEmpty { rs.victories.keys.toList() }
            if (vic.none { v -> rs.victories[v]?.enablesMaxTurns() == true })
                notes += "⏳ Max turns only matters with **Time** victory, which is off in this lobby."
        }
        if (custom != null && (FLAGS.any { it.map && ex.containsKey(it.key) } || listOf(K_RESOURCES, K_WATER, K_SHAPE, K_MIRROR, K_SEED, K_SLIDERS).any { ex.containsKey(it) }))
            notes += "🗺️ Custom map **$custom** is selected, so the map-generation options (size, type, shape, resources, water, ruins, wonders…) are ignored."
        if (custom == null && st.worldWrap && st.mapSize in setOf("Large", "Huge")) notes += "🌐 World wrap on a ${st.mapSize} map is memory-hungry and slower to draw."
        if (ex[K_MIRROR]?.let { it != MirroringType.leftright } == true) notes += "🪞 Unciv's map editor only offers Left-right mirroring — the other kinds are experimental."
        if (!statValue(meta, STATS[0]) && STATS.drop(1).any { pairs(ex[K_STATS]).containsKey(it.key) })
            notes += "📊 Civilization stats are off, so the other stats switches have no effect."
        return notes
    }

    /** Unciv's MapSize.fixUndesiredSizes rules (as NewGameScreen checks them) + its large-map warnings. */
    private fun checkCustomSize(meta: GameMeta, notes: MutableList<String>) {
        if (meta.settings.mapSize != CUSTOM_SIZE) return
        val size = customSize(meta) ?: throw PlayerError("A **Custom** map size needs its dimensions: `/civ setup radius:` for hexagonal maps, " +
            "or `width:` + `height:` for rectangular ones (Unciv's World Size → Custom).")
        val wrap = meta.settings.worldWrap
        size.clone().fixUndesiredSizes(wrap)?.let { msg ->
            throw PlayerError("$msg (${size.width}×${size.height}, radius ${size.radius}). Unciv needs width and height of at least 3, " +
                "a radius of 2–500, sides no more than 16:1" + (if (wrap) ", and a width of at least 32 with world wrap" else "") + ".")
        }
        if (wrap && size.width % 2 != 0) notes += "🌐 World wrap needs an even width — Unciv will use ${size.width - 1}."
        val tiles = tilesOf(meta, size)
        if (tiles > VERY_LARGE_TILES) throw PlayerError("That map would have $tiles tiles. Unciv warns \"This is a very large map - may cause Out Of Memory crashes\" " +
            "from $VERY_LARGE_TILES tiles, so this bot stops there — pick a smaller size.")
        if (tiles >= LARGE_TILES) notes += "📐 This is a large map ($tiles tiles) — may cause latency issues (Unciv's warning); turns and map pictures get slower."
        if (isRectShape(meta) && byRadius(meta)) notes += "📐 Rectangular maps are sized by width × height — radius ${size.radius} becomes ${size.width}×${size.height}."
        if (!isRectShape(meta) && !byRadius(meta)) notes += "📐 ${meta.settings.extra[K_SHAPE] ?: MapShape.hexagonal} maps are sized by radius — ${size.width}×${size.height} becomes radius ${size.radius}."
    }

    /** Engine thread. */
    override fun applyToSetup(setup: GameSetupInfo, meta: GameMeta) {
        val st = meta.settings
        val ex = st.extra
        val gp = setup.gameParameters
        val mp = setup.mapParameters
        val rs = RulesetCache.getComplexRuleset(gp)   // ModsPlus already set mods / base ruleset
        validateE(meta, rs)
        val custom = mp.name.isNotEmpty() || customMap(meta) != null

        for (f in FLAGS) if (!f.map || !custom) f.set(setup, flagValue(ex, f))
        gp.maxTurns = ex[K_MAX_TURNS]?.toIntOrNull() ?: gp.maxTurns
        for (s in STATS) s.set(gp, statValue(meta, s))

        // AI nations: the first AI slots get the named nations (GameStarter puts explicit choices before random ones)
        val aiNames = list(ex[K_AI_NATIONS])
        val aiSlots = gp.players.filter { it.playerType == PlayerType.AI && it.chosenCiv == Constants.random }
        aiNames.forEachIndexed { i, n -> aiSlots.getOrNull(i)?.chosenCiv = n }
        range(ex[K_RAND_CIVS])?.let { r ->
            gp.randomNumberOfPlayers = true
            gp.minNumberOfPlayers = r.first; gp.maxNumberOfPlayers = r.last
            // as Unciv's checkbox does: random AIs leave the player list, GameStarter adds the right number back
            gp.players = ArrayList(gp.players.filterNot { it.playerType == PlayerType.AI && it.chosenCiv == Constants.random })
        }
        range(ex[K_RAND_CS])?.let { r ->
            gp.randomNumberOfCityStates = true
            gp.minNumberOfCityStates = r.first; gp.maxNumberOfCityStates = r.last
            gp.numberOfCityStates = gp.numberOfCityStates.coerceIn(r.first, r.last)
        }
        list(ex[K_POOL]).takeIf { it.isNotEmpty() }?.let { gp.enableRandomNationsPool = true; gp.randomNationsPool = ArrayList(it) }

        if (!custom) {
            if (st.mapSize in MAP_SIZE_CAP) mp.mapSize = MapSize(st.mapSize)
            else customSize(meta)?.let { mp.mapSize = it.clone(); mp.mapSize.fixUndesiredSizes(mp.worldWrap) }   // (rounds an odd world-wrap width)
            // "Random" picks from the host's map type list, like Unciv's "Enabled Map Generation Types" checkboxes
            val pool = list(ex[K_RANDOM_TYPES]).filter { it in MapType.allValues }.ifEmpty { MapType.allValues }
            mp.type = if (st.mapType == RANDOM_TYPE || st.mapType !in MapType.allValues) pool.random() else st.mapType
            mp.mapResources = ex[K_RESOURCES] ?: MapResourceSetting.default.label
            mp.waterThreshold = ex[K_WATER]?.toFloatOrNull() ?: 0f
            mp.shape = ex[K_SHAPE] ?: MapShape.hexagonal
            mp.mirroring = ex[K_MIRROR] ?: MirroringType.none
            ex[K_SEED]?.toLongOrNull()?.let { mp.seed = it }
            val sl = pairs(ex[K_SLIDERS])
            for (s in SLIDERS) s.set(mp, sl[s.key]?.toFloatOrNull() ?: s.default)
        }
    }

    // ================================================================== lobby text

    private fun summaryParts(meta: GameMeta): List<String> {
        val ex = meta.settings.extra
        val out = ArrayList<String>()
        for (f in FLAGS) {
            val v = flagValue(ex, f)
            val text = if (v) f.onText else f.offText
            if (v != f.default && text.isNotEmpty()) out += text
        }
        customSize(meta)?.let { out += "📐 custom size ${sizeText(meta, it)} (${tilesOf(meta, it)} tiles)" }
        if (meta.settings.mapType == RANDOM_TYPE) ex[K_RANDOM_TYPES]?.let { out += "🎲 random map type from ${list(it).joinToString()}" }
        ex[K_MAX_TURNS]?.let { out += "⏳ max $it turns" }
        ex[K_SHAPE]?.let { out += it.lowercase() }
        ex[K_RESOURCES]?.let { out += "${it.lowercase()} resources" }
        ex[K_WATER]?.let { out += (WATER[it] ?: "water $it").lowercase() }
        ex[K_MIRROR]?.let { out += "🪞 $it mirrored" }
        ex[K_SEED]?.let { out += "🌱 seed $it" }
        ex[K_SLIDERS]?.let { out += "🎚️ ${it.replace(",", " ")}" }
        ex[K_RAND_CIVS]?.let { out += "🎲 $it civs" }
        ex[K_RAND_CS]?.let { out += "🎲 $it city-states" }
        ex[K_AI_NATIONS]?.let { out += "🤖 AI: ${list(it).joinToString()}" }
        ex[K_POOL]?.let { out += "🎲 random pool: ${list(it).size} nations" }
        ex[K_STATS]?.let { out += "📊 stats ${it.replace(",", " ")}" }
        return out
    }

    override fun lobbyLines(meta: GameMeta): List<String> {
        val ex = meta.settings.extra
        if (importPath(meta) != null) {
            val l = "📥 Imported save: ${ex[K_IMPORT_INFO] ?: "uploaded game"} — lobby map/AI/rules settings are ignored"
            val warn = if (scenario(meta) != null) listOf("⚠️ A scenario is also selected and takes precedence — `/civ lobby scenario:none`") else emptyList()
            return listOf(l.take(400)) + warn
        }
        val export = if (ex[K_ALLOW_EXPORT] == "true") listOf("💾 The host may export the save mid-game (`allow_export` on — the file shows the whole map)") else emptyList()
        val parts = summaryParts(meta)
        if (parts.isEmpty()) return export
        val line = "⚙️ " + parts.joinToString(" · ")
        return listOf(if (line.length > 400) line.take(397) + "…" else line) + export
    }

    /** Every option with its current value (for `/civ setup` with no options). */
    fun describe(meta: GameMeta): String {
        val st = meta.settings
        val ex = st.extra
        val sl = pairs(ex[K_SLIDERS])
        fun on(b: Boolean) = if (b) "on" else "off"
        val lines = ArrayList<String>()
        val cs = customSize(meta)
        lines += "**Map** — map_size `${st.mapSize}`" + (cs?.let { " (${sizeText(meta, it)}: ${it.width}×${it.height}, radius ${it.radius})" } ?: "") +
            " · map_type `${st.mapType}` · random_types `${ex[K_RANDOM_TYPES]?.let { list(it).joinToString() } ?: "all"}` · shape `${ex[K_SHAPE] ?: MapShape.hexagonal}` · " +
            "resources `${ex[K_RESOURCES] ?: "Default"}` · mirroring `${ex[K_MIRROR] ?: MirroringType.none}` · seed `${ex[K_SEED] ?: "random"}`"
        lines += "**map_sliders** — water `${ex[K_WATER]?.let { "${WATER[it] ?: it} ($it)" } ?: "Normal (0)"}` · " + SLIDERS.joinToString(" · ") { "${it.key} `${sl[it.key] ?: fmt(it.default)}`" }
        lines += "**map_options** — " + FLAGS.filter { it.map }.joinToString(" · ") { "${it.option} `${on(flagValue(ex, it))}`" }
        lines += "**Rules** — " + FLAGS.filter { !it.map }.joinToString(" · ") { "${it.option} `${on(flagValue(ex, it))}`" }
        lines += "**Turns** — max_turns `${ex[K_MAX_TURNS] ?: "500"}` (Time victory)"
        lines += "**Civilizations** — ai `${st.aiCivs}` (the map allows up to ${maxMajors(meta)} civs) · ai_nations `${ex[K_AI_NATIONS]?.let { list(it).joinToString() } ?: "random"}` · " +
            "random_civs `${ex[K_RAND_CIVS] ?: "off"}` · random_city_states `${ex[K_RAND_CS] ?: "off"}` · nations_pool `${ex[K_POOL]?.let { list(it).joinToString() } ?: "off"}`"
        lines += "**stats** — " + STATS.joinToString(" · ") { "${it.key} `${on(statValue(meta, it))}`" }
        if (importPath(meta) != null) lines += "📥 **Imported save:** ${ex[K_IMPORT_INFO]} — the save's own options apply."
        return "⚙️ **${meta.name}** — game setup\n" + lines.joinToString("\n") +
            "\n\nThe host changes these with `/civ setup <option>:<value>` (several at once is fine; `off`/`default`/`random` resets one)." +
            " Mods, base rules, custom maps and scenarios are in `/civ lobby`."
    }

    // ================================================================== saves: export

    /** Engine thread (via Engine.withGame). A file name and Unciv's own save text. */
    fun exportSaveE(s: Session): Pair<String, String> {
        val game = s.game ?: throw PlayerError("This game hasn't started yet.")
        val text = Engine.serialize(game)
        val safe = s.meta.name.replace(Regex("[^A-Za-z0-9 _()-]"), "").trim().take(50).ifEmpty { "CivBot game" }
        return "$safe - turn ${game.turns + 1}.civ" to text
    }

    private fun exportNote(game: GameInfo): String {
        val rules = listOf(game.gameParameters.baseRuleset) + game.gameParameters.mods
        return "💾 Here's the game as an Unciv save (turn ${game.turns + 1}).\n" +
            "**Desktop Unciv:** *Load game* → *Load from custom location* → pick this file. It opens as a **hotseat** game " +
            "(every human civ is played on that one computer; AI civs stay AI).\n" +
            "**Rules:** ${rules.joinToString(" + ")} — " +
            (if (game.gameParameters.mods.isEmpty()) "no extra mods needed." else "install the same mods in Unciv first.") +
            "\nTo bring a game back here, a host opens a lobby and uses `/civ save import file:`."
    }

    // ================================================================== saves: who may export

    /**
     * Null if [userId] may export the game of [meta] now, else the reason. A save holds the whole map without fog of war,
     * so in a running game with other humans only the host may export, and only when the lobby turned `allow_export` on
     * (everyone saw that before joining). A lone human against the AI may always export; anyone may once the game ended.
     */
    fun exportBlocker(meta: GameMeta, userId: String, ended: Boolean): String? {
        if (meta.seatOf(userId) == null) return "You're not playing in **${meta.name}**."
        if (ended || meta.status == Status.ENDED) return null
        if (meta.seats.count { !it.aiControlled } <= 1) return null
        if (meta.settings.extra[K_ALLOW_EXPORT] == "true") {
            if (userId == meta.hostId) return null
            return "Only the host (<@${meta.hostId}>) may export **${meta.name}** while it's running (the save shows the whole map, fog of war included)."
        }
        return "A save file shows the whole map without fog of war, so **${meta.name}** can only be exported once the game has ended. " +
            "(A host who wants mid-game exports turns on `/civ lobby allow_export:True` before the game starts, so every player knows.)"
    }

    /** NOT on the engine thread. */
    fun setAllowExport(meta: GameMeta, on: Boolean) {
        if (on) meta.settings.extra[K_ALLOW_EXPORT] = "true" else meta.settings.extra.remove(K_ALLOW_EXPORT)
    }

    // ================================================================== turn history + rollback

    /** Engine thread. Keeps a start-of-turn snapshot (once per turn, like Unciv's autosave). */
    private fun snapshotE(s: Session) {
        val store = Store.current ?: return
        val g = s.game ?: return
        if (s.meta.status != Status.RUNNING || s.meta.id.isEmpty()) return
        val turn = g.turns + 1
        if (store.historyGameId(s.meta.id) == g.gameId && store.historyFile(s.meta.id, turn).isFile) return
        runCatching { store.saveHistory(s.meta.id, g.gameId, turn, Engine.serialize(g), HISTORY_KEEP) }
            .onFailure { log.warn("turn snapshot failed for {}: {}", s.meta.id, it.toString()) }
    }

    override fun onTurnEnded(s: Session) = snapshotE(s)
    override fun beforeMutation(s: Session, seat: Seat, verb: String) = snapshotE(s)

    /** Turns that can be rolled back to (oldest first). */
    fun rollbackTurns(s: Session, store: Store): List<Int> {
        val g = s.game ?: return emptyList()
        if (store.historyGameId(s.meta.id) != g.gameId) return emptyList()
        return store.historyTurns(s.meta.id).filter { it <= g.turns + 1 }
    }

    /** A proposed rollback, checked again right before it's applied. */
    class RollbackPlan(val gid: String, val toTurn: Int, val fromTurns: Int, val fromCiv: String, val summary: String)

    /** Engine thread. What going back to the start of [turn] would undo. */
    fun rollbackPlanE(s: Session, store: Store, turn: Int): RollbackPlan {
        val g = s.game ?: throw PlayerError("This game hasn't started yet.")
        if (s.meta.status != Status.RUNNING) throw PlayerError("**${s.meta.name}** isn't running.")
        val now = g.turns + 1
        val turns = rollbackTurns(s, store)
        if (turns.isEmpty()) throw PlayerError("There are no saved turns for **${s.meta.name}** yet (one is kept at the start of every turn).")
        if (turn > now) throw PlayerError("**${s.meta.name}** is on turn $now — you can only go back.")
        if (turn !in turns) throw PlayerError("There's no snapshot of turn $turn. Turns you can go back to: ${turns.joinToString()} (the last $HISTORY_KEEP are kept).")
        val old = Engine.deserialize(store.historyFile(s.meta.id, turn).readText())
        val lines = ArrayList<String>()
        lines += if (turn == now) "⏪ Back to the **start of turn $turn** — everything done since this turn began is undone."
            else "⏪ Back from turn **$now** to the **start of turn $turn** — ${now - turn} turn(s) of play by everyone (and the AI) are undone."
        for (seat in s.meta.seats) {
            val c = runCatching { g.getCivilization(seat.civId) }.getOrNull() ?: continue
            val o = runCatching { old.getCivilization(seat.civId) }.getOrNull() ?: continue
            lines += "• **${c.civName}** (<@${seat.userId}>): ${o.cities.size}→${c.cities.size} cities · ${o.units.getCivUnitsSize()}→${c.units.getCivUnitsSize()} units · " +
                "${o.tech.techsResearched.size}→${c.tech.techsResearched.size} techs · ${o.gold}→${c.gold} gold (then→now)"
        }
        lines += "Announcements already posted stay in the channel; players who left stay with the AI."
        return RollbackPlan(s.meta.id, turn, g.turns, g.currentPlayer, cut(lines.joinToString("\n"), 1500))
    }

    /** Engine thread. Restores the snapshot of [plan] (refused if the game moved on since). Returns the announcement. */
    fun rollbackE(s: Session, store: Store, plan: RollbackPlan, ctl: Controller? = null): String {
        val g = s.game ?: throw PlayerError("This game hasn't started yet.")
        if (s.meta.status != Status.RUNNING) throw PlayerError("**${s.meta.name}** isn't running any more.")
        if (g.turns != plan.fromTurns || g.currentPlayer != plan.fromCiv)
            throw PlayerError("The game moved on since the rollback was proposed — start it again with `/civ rollback`.")
        val file = store.historyFile(s.meta.id, plan.toTurn)
        if (!file.isFile || store.historyGameId(s.meta.id) != g.gameId) throw PlayerError("The snapshot of turn ${plan.toTurn} is gone.")
        val ng = Engine.deserialize(file.readText())
        // players who left since then stay with the AI
        for (seat in s.meta.seats) if (seat.aiControlled) runCatching { ng.getCivilization(seat.civId).playerType = PlayerType.AI }
        s.game = ng
        UncivGame.Current.gameInfo = ng
        try {
            // a snapshot taken at an AI-run seat's turn: let it play, as Controller.endTurn does
            var guard = 0
            while (guard++ < 50) {
                val seat = s.meta.seatOfCiv(ng.currentPlayerCiv.civID)
                if (seat == null || !seat.aiControlled) break
                GameOps.endTurn(ng, ng.currentPlayerCiv)
            }
        } catch (e: Exception) {
            s.game = g; UncivGame.Current.gameInfo = g
            throw e
        }
        CityPlus.onTurnEnded(s)   // its undo steps belong to the timeline we just left
        ctl?.proposals?.values?.removeIf { it.gid == s.meta.id }
        store.historyTurns(s.meta.id).filter { it > plan.toTurn }.forEach { store.historyFile(s.meta.id, it).delete() }
        val msg = "⏪ **${s.meta.name}** was rolled back to the start of turn ${plan.toTurn}."
        s.meta.addEvent(msg)
        s.meta.turnStartedAt = System.currentTimeMillis()
        s.meta.lastReminderAt = 0
        store.saveGame(s)
        return msg
    }

    /** Every human player (not AI-run seats) must accept. The host's confirmation counts as their accept. */
    class RollbackVote(val token: String, val plan: RollbackPlan, val hostId: String, val needed: Set<String>, val created: Long = System.currentTimeMillis()) {
        val accepted = LinkedHashSet<String>().apply { add(hostId) }
        @Volatile var done = false
        val complete get() = accepted.containsAll(needed)
        fun expired(now: Long = System.currentTimeMillis()) = now - created > VOTE_MS
        /** @return true when this accept completed the vote */
        fun accept(uid: String): Boolean {
            if (uid !in needed) throw PlayerError("Only players in this game vote on a rollback.")
            accepted += uid
            return complete
        }
        fun text(name: String) = "🗳️ <@$hostId> wants to roll **$name** back to the start of turn ${plan.toTurn}. **Every player must accept** " +
            "(${accepted.count { it in needed }}/${needed.size})" + needed.filter { it !in accepted }.let { w -> if (w.isEmpty()) "" else " — waiting for ${w.joinToString(" ") { "<@$it>" }}" } +
            "\n" + plan.summary
    }

    private const val VOTE_MS = 24L * 3600 * 1000
    private val pendingRollbacks = java.util.concurrent.ConcurrentHashMap<String, RollbackVote>()
    private fun token() = java.lang.Long.toString(System.nanoTime() % 2176782336L, 36)
    /** Our own buttons ("su|…"), handled by [ensureListener] — Bot.kt only routes "c|…" ids. */
    private fun suId(vararg parts: String) = (listOf("su") + parts).joinToString("|").take(100)
    fun voteRows(v: RollbackVote) = listOf(ActionRow.of(
        Button.success(suId("rbv", v.token, "y"), "Accept rollback").withEmoji(Emoji.fromUnicode("✅")),
        Button.danger(suId("rbv", v.token, "n"), "Reject"),
    ))

    private fun rollbackCommand(event: SlashCommandInteractionEvent, bot: BotApi) {
        val s = bot.gameHere(event.channel.id, event.user.id)?.takeIf { it.meta.status == Status.RUNNING && it.game != null }
            ?: return event.reply("There's no running game of yours here. Run this in the game's channel or thread.").setEphemeral(true).queue()
        val turn = event.getOption("turn")?.asInt
        if (turn == null) {
            val turns = rollbackTurns(s, bot.store)
            return event.reply(if (turns.isEmpty()) "No turns of **${s.meta.name}** are saved yet — one is kept at the start of every turn."
                else "⏪ **${s.meta.name}** can go back to the start of turn ${turns.joinToString()} (the last $HISTORY_KEEP turns are kept). " +
                    "The host runs `/civ rollback turn:<n>`; with 2+ players everyone must accept.").setEphemeral(true).queue()
        }
        if (s.meta.hostId != event.user.id) return event.reply("Only the host (<@${s.meta.hostId}>) can roll the game back.").setEphemeral(true).queue()
        event.deferReply(true).queue { hook ->
            bot.async {
                try {
                    val plan = Engine.withGame(s.game!!) { rollbackPlanE(s, bot.store, turn) }
                    val humans = s.meta.seats.filter { !it.aiControlled }.map { it.userId }.toSet()
                    val v = RollbackVote(token(), plan, s.meta.hostId, humans + s.meta.hostId)
                    pendingRollbacks[v.token] = v
                    val how = if (v.needed.size > 1) "Confirming posts a vote: **every player** (${v.needed.size}) must accept before anything changes."
                        else "You're the only human player, so it happens right away."
                    hook.editOriginal(MessageEditBuilder().setContent(cut("${plan.summary}\n\n$how", 1950)).setComponents(ActionRow.of(
                        Button.danger(suId("rbc", v.token, "y"), "Roll back to turn $turn").withEmoji(Emoji.fromUnicode("⏪")),
                        Button.secondary(suId("rbc", v.token, "n"), "Cancel"))).build()).queue()
                } catch (e: PlayerError) { hook.editOriginal("❌ ${e.message}").queue() } catch (e: Exception) {
                    log.error("rollback preview failed", e); hook.editOriginal("💥 Couldn't prepare the rollback: ${e.message}").queue()
                }
            }
        }
    }

    // ---- our buttons (registered once a SetupPlus command ran; votes live in memory and expire on restart)
    private val listenerAdded = java.util.concurrent.atomic.AtomicBoolean(false)
    @Volatile private var botApi: BotApi? = null

    private fun ensureListener(jda: JDA, bot: BotApi) {
        botApi = bot
        if (!listenerAdded.compareAndSet(false, true)) return
        jda.addEventListener(object : ListenerAdapter() {
            override fun onButtonInteraction(event: ButtonInteractionEvent) {
                if (!event.componentId.startsWith("su|")) return
                runCatching { onButton(event) }.onFailure { log.warn("SetupPlus button failed", it) }
            }
        })
    }

    private fun onButton(event: ButtonInteractionEvent) {
        val bot = botApi ?: return
        val parts = event.componentId.split("|")
        val v = pendingRollbacks[parts.getOrNull(2) ?: ""]
        if (v == null || v.expired() || v.done) {
            pendingRollbacks.remove(parts.getOrNull(2) ?: "")
            return event.editMessage("This rollback request has expired.").setComponents().queue()
        }
        val s = bot.store.sessions[v.plan.gid] ?: return event.editMessage("That game no longer exists.").setComponents().queue()
        val uid = event.user.id
        when (parts.getOrNull(1) to parts.getOrNull(3)) {
            "rbc" to "n" -> { pendingRollbacks.remove(v.token); event.editMessage("Rollback cancelled.").setComponents().queue() }
            "rbc" to "y" -> {
                if (uid != v.hostId) return event.reply("Only the host can confirm.").setEphemeral(true).queue()
                if (v.complete) { event.deferEdit().queue(); runRollback(event.hook, v, s, bot, edit = true); return }
                event.editMessage("🗳️ Vote posted in the game channel — the rollback happens once every player accepts.").setComponents().queue()
                event.jda.getChannelById(MessageChannel::class.java, s.meta.playChannelId)
                    ?.sendMessage(MessageCreateBuilder().setContent(cut(v.text(s.meta.name), 1950)).setComponents(voteRows(v)).build())?.queue()
            }
            "rbv" to "n" -> {
                if (uid !in v.needed) return event.reply("Only players in this game vote on a rollback.").setEphemeral(true).queue()
                pendingRollbacks.remove(v.token)
                event.editMessage("❌ <@$uid> rejected rolling **${s.meta.name}** back to turn ${v.plan.toTurn}. Nothing changed.").setComponents().queue()
            }
            "rbv" to "y" -> {
                val complete = try { synchronized(v) { v.accept(uid) } } catch (e: PlayerError) { return event.reply("❌ ${e.message}").setEphemeral(true).queue() }
                if (!complete) return event.editMessage(cut(v.text(s.meta.name), 1950)).setComponents(voteRows(v)).queue()
                event.deferEdit().queue()
                runRollback(event.hook, v, s, bot, edit = true)
            }
        }
    }

    private fun runRollback(hook: InteractionHook, v: RollbackVote, s: Session, bot: BotApi, edit: Boolean) = bot.async {
        synchronized(v) { if (v.done) return@async; v.done = true }
        pendingRollbacks.remove(v.token)
        try {
            val (msg, next) = Engine.withGame(s.game!!) {
                val m = rollbackE(s, bot.store, v.plan, bot.ctl)
                val g = s.game!!
                m to TurnResult(listOf(m), s.meta.seatOfCiv(g.currentPlayerCiv.civID), g.currentPlayerCiv.civName, g.turns + 1, false, null)
            }
            if (edit) hook.editOriginal("✅ $msg").setComponents().queue()
            (bot as? Bot)?.announce(s, next)
        } catch (e: PlayerError) { hook.editOriginal("❌ ${e.message}").setComponents().queue() } catch (e: Exception) {
            log.error("rollback failed", e); hook.editOriginal("💥 The rollback failed: ${e.message}. The game is unchanged.").setComponents().queue()
        }
    }

    // ================================================================== saves: import

    private fun playableCivs(game: GameInfo) = game.civilizations.filter { it.isMajorCiv && it.isAlive() && !it.isSpectator && !it.isBarbarian }

    /** Engine thread. Unciv's loader with player-facing errors for the ways a save can't be used. */
    private fun loadSaveE(text: String): GameInfo {
        val ours = CompatibilityVersion.CURRENT_COMPATIBILITY_VERSION
        val game = try {
            UncivFiles.gameInfoFromString(text)
        } catch (e: IncompatibleGameInfoVersionException) {
            val v = e.version
            throw PlayerError(if (v > ours)
                "That save was made with **Unciv ${v.createdWith.text}** (save format ${v.number}), which is newer than the bot's Unciv ${UncivGame.VERSION.text} " +
                    "(format ${ours.number}) — this bot can't read it. Save it from an Unciv version no newer than ${UncivGame.VERSION.text}."
            else "That save (made with Unciv ${v.createdWith.text.ifEmpty { "an unknown version" }}) couldn't be read — the file looks damaged.")
        } catch (e: MissingModsException) {
            throw PlayerError("That save needs mod(s) that aren't installed on this server: ${e.missingMods.joinToString { "**$it**" }}. " +
                "A server manager can add them with `/civ mod action:install url:`.")
        } catch (e: MissingNationException) {
            throw PlayerError("That save uses a nation the installed mods don't have (${e.message}). Check the mods: ${e.modNames.joinToString()}.")
        } catch (e: Throwable) {
            throw PlayerError("That isn't an Unciv saved game (${(e.message ?: e.javaClass.simpleName).take(120)}). " +
                "In Unciv use *Save game* (or *Copy to clipboard* and paste into a text file) and upload that file.")
        }
        if (game.civilizations.isEmpty() || game.tileMap.values.isEmpty()) throw PlayerError("That file has no civilizations or map — is it a map file instead of a saved game?")
        game.victoryData?.let { throw PlayerError("That game is already over (**${it.winningCiv}** won a ${it.victoryType} victory).") }
        return game
    }

    private fun describeSave(game: GameInfo, fileName: String): String {
        val civs = playableCivs(game)
        return "**${fileName.take(60)}** — turn ${game.turns + 1}, ${civs.size} civs (${civs.joinToString { it.civID + if (it.isHuman()) " 👤" else "" }}), " +
            "made with Unciv ${game.version.createdWith.text.ifEmpty { "?" }}"
    }

    /** Validates an uploaded save and keeps it in <data>/imports/ until the game starts. NOT on the engine thread. */
    fun importSave(meta: GameMeta, bytes: ByteArray, fileName: String, dataDir: File): String = synchronized(meta) {
        if (meta.status != Status.LOBBY) throw PlayerError("The game has already started — import a save into a new lobby (`/civ new`).")
        if (bytes.size > MAX_SAVE_BYTES) throw PlayerError("Save files can be at most ${MAX_SAVE_BYTES / 1048576} MB.")
        val text = String(bytes, Charsets.UTF_8)
        val (info, count, rules) = Engine.run {
            val g = loadSaveE(text)
            val civs = playableCivs(g)
            if (meta.seats.size > civs.size)
                throw PlayerError("That save has only ${civs.size} playable civilizations (${civs.joinToString { it.civID }}), but ${meta.seats.size} players joined.")
            Triple(describeSave(g, fileName), civs.size, listOf(g.gameParameters.baseRuleset) + g.gameParameters.mods)
        }
        val dir = File(dataDir, "imports").apply { mkdirs() }
        // forget uploads of lobbies that never started
        dir.listFiles()?.filter { System.currentTimeMillis() - it.lastModified() > 14L * 24 * 3600 * 1000 }?.forEach { it.delete() }
        val target = File(dir, "${meta.id.replace(Regex("[^A-Za-z0-9]"), "")}.civ")
        val tmp = File(dir, target.name + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(target)) { target.delete(); tmp.renameTo(target) }
        val notes = ArrayList<String>()
        if (meta.settings.extra.remove(ModsPlus.K_SCENARIO) != null) notes += "📜 The selected scenario was cleared — the save replaces it."
        meta.settings.extra[K_IMPORT] = target.absolutePath
        meta.settings.extra[K_IMPORT_INFO] = info
        meta.settings.extra[K_IMPORT_CIVS] = count.toString()
        (listOf("📥 Imported $info.", "Rules: ${rules.joinToString(" + ")}.",
            "When the host presses **Start**, each player takes back their own civ (if they played it here before), else a civ matching their 🏳️ nation pick, " +
                "else one of the save's human civs; every other civ is run by the AI. Lobby map/AI/option settings don't apply.") + notes).joinToString("\n")
    }

    fun clearImport(meta: GameMeta): String = synchronized(meta) {
        val p = importPath(meta) ?: throw PlayerError("No save is imported in this lobby.")
        File(p).takeIf { it.parentFile?.name == "imports" }?.delete()
        for (k in listOf(K_IMPORT, K_IMPORT_INFO, K_IMPORT_CIVS)) meta.settings.extra.remove(k)
        "🗑️ The imported save was removed — the lobby will generate a new world."
    }

    /** Engine thread. Builds the game from the imported save (seat mapping follows ModsPlus.createGame for scenarios). */
    override fun createGame(meta: GameMeta): GameInfo? {
        val path = importPath(meta) ?: return null
        val file = File(path)
        if (file.parentFile?.name != "imports" || !file.isFile) throw PlayerError("The imported save is gone — import it again with `/civ save import file:`.")
        val game = loadSaveE(file.readText())
        // Remove a Spectator and make everyone AI, as NewGameScreen does for scenarios
        game.civilizations.removeIf { it.civID == Constants.spectator }
        val previous = HashMap<String, String>()   // civID -> old playerId
        val wasHuman = HashSet<String>()
        for (civ in game.civilizations) {
            previous[civ.civID] = civ.playerId
            if (civ.isHuman()) wasHuman += civ.civID
            civ.playerType = PlayerType.AI
            civ.playerId = ""
            civ.diplomacy.remove(Constants.spectator)
            civ.popupAlerts.removeIf { it.type == AlertType.FirstContact && it.value == Constants.spectator }
        }
        val free = playableCivs(game).toMutableList()
        if (meta.seats.size > free.size) throw PlayerError("The imported save has only ${free.size} playable civilizations, but ${meta.seats.size} players joined.")
        fun take(seat: Seat, civ: Civilization) { free.remove(civ); civ.playerType = PlayerType.Human; civ.playerId = "discord:${seat.userId}" }
        val left = ArrayList(meta.seats)
        // 1. a player who played this civ here before (a re-imported CivBot export) gets it back
        for (seat in ArrayList(left)) free.firstOrNull { previous[it.civID] == "discord:${seat.userId}" }?.let { take(seat, it); left.remove(seat) }
        // 2. the nation they picked in the lobby
        for (seat in ArrayList(left))
            free.firstOrNull { seat.nation != Constants.random && (it.civID.equals(seat.nation, true) || it.civName.equals(seat.nation, true)) }
                ?.let { take(seat, it); left.remove(seat) }
        // 3. the save's human civs first (those were its players), then any other playable civ
        val order = free.filter { it.civID in wasHuman } + free.filter { it.civID !in wasHuman }.shuffled()
        for ((seat, civ) in left.zip(order)) take(seat, civ)

        game.gameId = UUID.randomUUID().toString()
        game.gameParameters.isOnlineMultiplayer = false
        game.gameParameters.players = ArrayList(game.civilizations.filter { it.isMajorCiv }.map { Player(it.civID, it.playerType, it.playerId) })
        UncivGame.Current.gameInfo = game
        // The turn must start with a human: let AIs ahead in the rotation play (as ModsPlus does for scenarios)
        val cur = game.civilizations.firstOrNull { it.civID == game.currentPlayer }
        if (cur == null || !cur.isHuman()) {
            val ok = cur != null && cur.isAlive() && runCatching { game.nextTurn() }.onFailure { log.warn("import AI pre-turn failed", it) }.isSuccess
            if (!ok || !game.currentPlayerCiv.isHuman()) {
                val first = game.civilizations.first { it.isHuman() }
                game.currentPlayer = first.civID
                game.currentPlayerCiv = first
            }
        }
        // the upload has done its job
        file.delete()
        for (k in listOf(K_IMPORT, K_IMPORT_CIVS)) meta.settings.extra.remove(k)
        return game
    }

    // ================================================================== Discord commands

    override fun commands(): List<SubcommandData> = listOf(
        SubcommandData("setup", "Host: every other Unciv game option (no options = show current values)").addOptions(
            OptionData(OptionType.STRING, "map_size", "Map size (Huge included; Custom = set radius: or width:+height:)").also { o ->
                MAP_SIZE_CAP.forEach { (k, n) -> o.addChoice("$k (up to $n civs)", k) }; o.addChoice("Custom (radius: or width: + height:)", CUSTOM_SIZE) },
            OptionData(OptionType.STRING, "map_type", "Map generator").also { o -> (MapType.allValues + RANDOM_TYPE).forEach { o.addChoice(it, it) } },
            OptionData(OptionType.STRING, "random_types", "Map types 'Random' may pick, e.g. Pangaea,Archipelago (or 'all')"),
            OptionData(OptionType.INTEGER, "radius", "Custom size, hexagonal maps: tiles from the centre (2-500)").setRequiredRange(1, 1000),
            OptionData(OptionType.INTEGER, "width", "Custom size, rectangular maps: width in tiles").setRequiredRange(1, 2000),
            OptionData(OptionType.INTEGER, "height", "Custom size, rectangular maps: height in tiles").setRequiredRange(1, 2000),
            OptionData(OptionType.STRING, "shape", "Map shape").also { o -> MapShape.allValues.forEach { o.addChoice(it, it) } },
            OptionData(OptionType.STRING, "resources", "Resource abundance").also { o -> RESOURCES.forEach { o.addChoice(it, it) } },
            OptionData(OptionType.STRING, "mirroring", "Mirror the map so every side is the same").also { o ->
                MIRRORING.forEach { o.addChoice(if (it == MirroringType.none || it == MirroringType.leftright) it else "$it (experimental)", it) } },
            OptionData(OptionType.STRING, "seed", "Map seed: a number to get the same map again, or 'random'"),
            OptionData(OptionType.STRING, "map_sliders", "e.g. water=0.05 elevation=0.75 temperature=0.5 vegetation=0.6 coast=4 biome=8 (or reset)"),
            OptionData(OptionType.STRING, "map_options", "e.g. no_ruins=on no_wonders=on strategic_balance=on legendary_start=on (or default)"),
        ).addOptions(FLAGS.filter { !it.map }.map { OptionData(OptionType.BOOLEAN, it.option, it.desc.take(100)) }).addOptions(
            OptionData(OptionType.INTEGER, "max_turns", "Turn limit for Time victory (100–1500, default 500)").setRequiredRange(100, 1500),
            OptionData(OptionType.INTEGER, "ai", "Number of AI opponents (more than the lobby dropdown offers)").setRequiredRange(0, OTHER_CAP.toLong()),
            OptionData(OptionType.STRING, "ai_nations", "Specific AI nations, e.g. Rome,Greece (or 'random')"),
            OptionData(OptionType.STRING, "random_civs", "Random number of civilizations, e.g. 3-8 (or 'off')"),
            OptionData(OptionType.STRING, "random_city_states", "Random number of city-states, e.g. 2-6 (or 'off')"),
            OptionData(OptionType.STRING, "nations_pool", "Random nations come only from these, e.g. Rome,Greece,Egypt (or 'off')"),
            OptionData(OptionType.STRING, "stats", "e.g. demographics=on rankings=off charts=off own_only=on extra_types=on (or off/default)"),
        ),
        SubcommandData("rollback", "Host: go back to the start of an earlier turn (every player must accept)").addOptions(
            OptionData(OptionType.INTEGER, "turn", "Turn to go back to (leave out to list the saved turns)").setMinValue(1),
        ),
    )

    override fun commandGroups(): List<SubcommandGroupData> = listOf(
        SubcommandGroupData("save", "Unciv save files: export this game, or start a lobby from one").addSubcommands(
            SubcommandData("export", "Download this game as an Unciv save (after it ended, or host with allow_export)"),
            SubcommandData("import", "Host / Manage Server: start this lobby's game from an Unciv save").addOptions(
                OptionData(OptionType.ATTACHMENT, "file", "The Unciv save file (max 20 MB)"),
                OptionData(OptionType.BOOLEAN, "remove", "Drop the imported save from this lobby"),
            ),
        ),
    )

    override fun onCommand(event: SlashCommandInteractionEvent, bot: BotApi): Boolean {
        when {
            event.subcommandGroup == null && event.subcommandName == "setup" -> setupCommand(event, bot)
            event.subcommandGroup == null && event.subcommandName == "rollback" -> rollbackCommand(event, bot)
            event.subcommandGroup == "save" && event.subcommandName == "export" -> exportCommand(event, bot)
            event.subcommandGroup == "save" && event.subcommandName == "import" -> importCommand(event, bot)
            else -> return false
        }
        ensureListener(event.jda, bot)
        return true
    }

    private fun work(hook: InteractionHook, bot: BotApi, block: () -> String) = bot.async {
        val msg = try { block() } catch (e: PlayerError) { "❌ ${e.message}" } catch (e: Exception) {
            log.error("SetupPlus command failed", e); "💥 Something went wrong: ${e.message}"
        }
        hook.editOriginal(clip(msg)).queue()
    }

    private fun lobbyHere(store: Store, channelId: String, userId: String): GameMeta? {
        val lobbies = store.sessions.values.map { it.meta }.filter { it.status == Status.LOBBY && (it.channelId == channelId || it.threadId == channelId) }
        return lobbies.firstOrNull { it.hostId == userId } ?: lobbies.firstOrNull { it.seatOf(userId) != null } ?: lobbies.firstOrNull()
    }

    private fun setupCommand(event: SlashCommandInteractionEvent, bot: BotApi) {
        val meta = lobbyHere(bot.store, event.channel.id, event.user.id)
            ?: return event.reply("There's no game lobby in this channel — `/civ setup` only works before a game starts (open one with `/civ new`).").setEphemeral(true).queue()
        val opts = event.options.associate { it.name to it.asString }
        if (opts.isEmpty()) return event.reply(clip(describe(meta))).setEphemeral(true).queue()
        if (meta.hostId != event.user.id) return event.reply("Only the host (<@${meta.hostId}>) can change the game setup.").setEphemeral(true).queue()
        event.deferReply(true).queue { hook -> work(hook, bot) { updateSetup(meta, opts).also { bot.refreshLobby(meta) } } }
    }

    private fun exportCommand(event: SlashCommandInteractionEvent, bot: BotApi) {
        val uid = event.user.id
        val ch = event.channel.id
        val s = bot.gameHere(ch, uid)?.takeIf { it.meta.seatOf(uid) != null }
        if (s == null) {
            // a finished game played here (moved to data/finished/)
            val fin = bot.store.finishedMetas().filter { (it.channelId == ch || it.threadId == ch) && it.seatOf(uid) != null }.maxByOrNull { it.turnStartedAt }
                ?: return event.reply("You're not playing in a game here. Run this in the game's channel or thread.").setEphemeral(true).queue()
            val file = bot.store.finishedSave(fin.id).takeIf { it.isFile }
                ?: return event.reply("The save of **${fin.name}** is gone.").setEphemeral(true).queue()
            if (file.length() > MAX_EXPORT_BYTES) return event.reply("❌ The save is ${file.length() / 1048576} MB — too big for a Discord upload.").setEphemeral(true).queue()
            event.deferReply(true).queue { hook ->
                bot.async {
                    try {
                        val text = file.readText()
                        val note = Engine.run { exportNote(Engine.deserialize(text)) }
                        val name = fin.name.replace(Regex("[^A-Za-z0-9 _()-]"), "").trim().take(50).ifEmpty { "CivBot game" } + " - final.civ"
                        hook.editOriginal(MessageEditBuilder().setContent("🏁 **${fin.name}** has ended.\n$note").setAttachments(FileUpload.fromData(text.toByteArray(), name)).build()).queue()
                    } catch (e: Exception) { log.error("export failed", e); hook.editOriginal("💥 Couldn't export the game: ${e.message}").queue() }
                }
            }
            return
        }
        val game = s.game ?: return event.reply("**${s.meta.name}** hasn't started yet — there's nothing to export.").setEphemeral(true).queue()
        exportBlocker(s.meta, uid, ended = false)?.let { return event.reply("🔒 $it").setEphemeral(true).queue() }
        event.deferReply(true).queue { hook ->
            bot.async {
                try {
                    val (name, text, note) = Engine.withGame(game) { val (n, t) = exportSaveE(s); Triple(n, t, exportNote(game)) }
                    val bytes = text.toByteArray(Charsets.UTF_8)
                    if (bytes.size > MAX_EXPORT_BYTES) hook.editOriginal("❌ The save is ${bytes.size / 1048576} MB — too big for a Discord upload.").queue()
                    else hook.editOriginal(MessageEditBuilder().setContent(note).setAttachments(FileUpload.fromData(bytes, name)).build()).queue()
                } catch (e: Exception) {
                    log.error("export failed", e); hook.editOriginal("💥 Couldn't export the game: ${e.message}").queue()
                }
            }
        }
    }

    private fun readCapped(input: InputStream, max: Long): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val r = input.read(buf)
            if (r < 0) break
            total += r
            if (total > max) throw PlayerError("Save files can be at most ${max / 1048576} MB.")
            out.write(buf, 0, r)
        }
        return out.toByteArray()
    }

    private fun importCommand(event: SlashCommandInteractionEvent, bot: BotApi) {
        val meta = lobbyHere(bot.store, event.channel.id, event.user.id)
            ?: return event.reply("There's no game lobby in this channel. Open one with `/civ new`, then import the save there.").setEphemeral(true).queue()
        val manager = event.member?.hasPermission(Permission.MANAGE_SERVER) == true
        if (meta.hostId != event.user.id && !manager) return event.reply("Only the host (<@${meta.hostId}>) or a server manager can import a save.").setEphemeral(true).queue()
        if (event.getOption("remove")?.asBoolean == true) {
            val msg = try { clearImport(meta).also { bot.refreshLobby(meta) } } catch (e: PlayerError) { "❌ ${e.message}" }
            return event.reply(msg).setEphemeral(true).queue()
        }
        val att = event.getOption("file")?.asAttachment
            ?: return event.reply("Attach the save with `file:` — Unciv's *Save game* writes it to its `SaveFiles` folder.").setEphemeral(true).queue()
        if (att.size > MAX_SAVE_BYTES) return event.reply("❌ Save files can be at most ${MAX_SAVE_BYTES / 1048576} MB.").setEphemeral(true).queue()
        event.deferReply().queue { hook ->
            work(hook, bot) {
                val bytes = att.proxy.download().get(60, TimeUnit.SECONDS).use { readCapped(it, MAX_SAVE_BYTES) }
                importSave(meta, bytes, att.fileName, bot.store.dir).also { bot.refreshLobby(meta) }
            }
        }
    }

    // ================================================================== self-test

    @Volatile private var selfTesting = false

    override fun handle(verb: String, ctx: ActionCtx): Boolean {
        if (verb != VERB_SELFTEST || !selfTesting) return false
        ctx.flash += "✅ ${ctx.value}"
        return true
    }

    override fun selfTest(t: SelfTestCtx) {
        selfTesting = true
        val failures = ArrayList<String>()
        fun ok(label: String) { t.act(t.alice, VERB_SELFTEST, emptyList(), label, label) }
        fun step(label: String, block: () -> Unit) {
            try { block(); ok(label) } catch (e: Throwable) { failures += "$label: ${e.javaClass.simpleName}: ${e.message}"; e.printStackTrace() }
        }
        fun expectError(label: String, mustContain: String = "", block: () -> Unit) = step(label) {
            val err = try { block(); null } catch (e: PlayerError) { e.message ?: "?" }
            check(err != null) { "expected a PlayerError" }
            check(err.contains(mustContain, true)) { "wrong error: $err" }
            println("  [SetupPlus] $label -> ${err.lines().first().take(170)}")
        }
        fun lobby(id: String, size: String = "Tiny", ai: Int = 2, cs: Int = 2) = GameMeta(id = id, name = "SetupPlus $id", hostId = "901").apply {
            seats += Seat("901", "Ann", "Greece"); seats += Seat("902", "Ben", Constants.random)
            settings.aiCivs = ai; settings.cityStates = cs; settings.mapSize = size
        }
        fun majors(g: GameInfo) = g.civilizations.filter { it.isMajorCiv }
        val dataDir = t.ctl.store.dir
        try {
            // ---- 1. every option, applied to a real game
            step("setup: /civ setup + /civ save commands build (Discord limits)") {
                val cmd = net.dv8tion.jda.api.interactions.commands.build.Commands.slash("civ", "test")
                    .addSubcommands(Extensions.all.flatMap { it.commands() }).addSubcommandGroups(Extensions.all.flatMap { it.commandGroups() })
                val setup = cmd.subcommands.first { it.name == "setup" }
                check(setup.options.size == 25 && cmd.subcommandGroups.any { g -> g.name == "save" && g.subcommands.map { it.name } == listOf("export", "import") })
                check(setup.options.map { it.name }.containsAll(listOf("radius", "width", "height", "random_types", "map_options")) && cmd.subcommands.any { it.name == "rollback" })
                check(cmd.subcommands.all { sc -> sc.options.size <= 25 && sc.options.all { it.description.length <= 100 && it.choices.size <= 25 } })
                println("  [SetupPlus] /civ has ${cmd.subcommands.size} extension subcommands + ${cmd.subcommandGroups.size} group(s); /civ setup has ${setup.options.size} options")
            }
            val a = lobby("su1")
            step("setup: show current values") { val d = describe(a); check(d.contains("max_turns") && d.contains("one_city") && d.contains("nations_pool")) }
            step("setup: options with choices applied to a started game") {
                println("  [SetupPlus] " + updateSetup(a, linkedMapOf(
                    "max_turns" to "300", "one_city" to "true", "raging_barbarians" to "true", "nukes" to "false", "no_razing" to "true",
                    "no_start_bias" to "true", "shuffle_order" to "true", "no_ruins" to "true", "no_wonders" to "true", "resources" to "Abundant",
                    "water" to "0.05", "shape" to "Rectangular", "mirroring" to "Left-right", "seed" to "12345", "map_type" to "Three Continents",
                    "map_sliders" to "elevation=0.75 coast=4 biome=8 temperature=0.5", "stats" to "demographics=on rankings=off own_only=on extra_types=on charts=off",
                )).replace("\n", " | "))
                val line = lobbyLines(a).single()
                check(line.contains("One City Challenge") && line.contains("no nukes") && line.contains("seed 12345")) { line }
                val g = t.eng { GameOps.startGame(a) }
                val gp = g.gameParameters; val mp = g.tileMap.mapParameters
                check(gp.maxTurns == 300 && gp.oneCityChallenge && gp.ragingBarbarians && !gp.nuclearWeaponsEnabled && gp.noCityRazing && gp.noStartBias && gp.shufflePlayerOrder) { gp.toString() }
                check(gp.showDemographics && !gp.showRankings && gp.hideOtherCivilizationStats && gp.showAdditionalRankingTypes && !gp.showCharts && gp.showCivilizationStats == true)
                check(mp.noRuins && mp.noNaturalWonders && mp.mapResources == "Abundant" && mp.waterThreshold == 0.05f && mp.shape == MapShape.rectangular) { mp.toString() }
                check(mp.mirroring == MirroringType.leftright && mp.seed == 12345L && mp.type == MapType.threeContinents && mp.elevationExponent == 0.75f &&
                    mp.maxCoastExtension == 4 && mp.tilesPerBiomeArea == 8 && mp.temperatureintensity == 0.5f) { mp.toString() }
                check(g.tileMap.naturalWonders.isEmpty()) { "natural wonders: ${g.tileMap.naturalWonders}" }
                check(g.tileMap.values.none { tile -> tile.improvement?.let { g.ruleset.tileImprovements[it]?.isAncientRuinsEquivalent() } == true }) { "ruins on the map" }
                check(a.seats.all { s -> g.getCivilization(s.civId).playerId == "discord:${s.userId}" })
                println("  [SetupPlus] game 1: ${mp.mapSize.name} ${mp.shape} ${mp.type}, ${g.tileMap.values.size} tiles, civs ${majors(g).map { it.civID }}")
            }
            // ---- 2. more than 7 AIs, specific AI nations, strategic balance + legendary start, random city-states
            val b = lobby("su2", size = "Small", ai = 2)
            step("setup: 9 AI opponents + chosen AI nations on a Small map") {
                check(maxAiChoice(b) > 7) { "AI dropdown cap ${maxAiChoice(b)}" }
                println("  [SetupPlus] " + updateSetup(b, linkedMapOf("ai" to "9", "ai_nations" to "rome, Egypt,Babylon", "strategic_balance" to "true",
                    "legendary_start" to "true", "resources" to "Sparse", "map_type" to "Pangaea", "random_city_states" to "2-5")).replace("\n", " | "))
                check(b.settings.aiCivs == 9 && b.settings.extra[K_AI_NATIONS] == "Rome,Egypt,Babylon")
                val g = t.eng { GameOps.startGame(b) }
                val m = majors(g)
                check(m.size == 11) { "majors ${m.map { it.civID }}" }
                check(listOf("Rome", "Egypt", "Babylon").all { n -> m.any { it.civID == n && it.isAI() } }) { "AI nations missing: ${m.map { it.civID }}" }
                check(g.getCivilization(b.seats[0].civId).civID == "Greece" && m.count { it.isHuman() } == 2)
                val cs = g.civilizations.count { it.isCityState }
                check(cs in 2..5 && g.gameParameters.randomNumberOfCityStates) { "city-states $cs" }
                val mp = g.tileMap.mapParameters
                check(mp.strategicBalance && mp.legendaryStart && mp.mapResources == "Sparse" && mp.type == MapType.pangaea) { mp.toString() }
                println("  [SetupPlus] game 2: ${m.size} majors (${m.filter { it.isAI() }.joinToString { it.civID }}), $cs city-states")
            }
            // ---- 3. random number of civs from a random nations pool
            val c = lobby("su3", size = "Tiny", ai = 1)
            val pool = listOf("Rome", "Greece", "Egypt", "Babylon", "China", "Persia")
            step("setup: random civ count + random nations pool") {
                updateSetup(c, mapOf("random_civs" to "3-5", "nations_pool" to pool.joinToString(",")))
                check(!isFull(c))
                val g = t.eng { GameOps.startGame(c) }
                val m = majors(g)
                check(m.size in 3..5 && m.all { it.civID in pool } && g.gameParameters.randomNumberOfPlayers && g.gameParameters.enableRandomNationsPool) { m.map { it.civID }.toString() }
                check(c.seats.all { s -> g.getCivilization(s.civId).isHuman() })
                println("  [SetupPlus] game 3: ${m.map { it.civID + "(" + it.playerType + ")" }}")
            }
            // ---- 4. Huge size + random map type, every map type in the lobby (setup only: generating a Huge world is slow)
            step("setup: Huge map size + every Unciv map type") {
                val h = lobby("su4", size = "Small", ai = 20)
                updateSetup(h, mapOf("map_size" to "huge", "map_type" to "random"))
                val setup = GameSetupInfo()
                setup.gameParameters.players = ArrayList(h.seats.map { Player(it.nation, PlayerType.Human, "discord:${it.userId}") } + List(20) { Player() })
                t.eng { applyToSetup(setup, h) }
                check(setup.mapParameters.mapSize.name == "Huge" && setup.mapParameters.type in MapType.allValues && h.settings.mapType == RANDOM_TYPE)
                check("Huge" in GameOps.MAP_SIZES && GameOps.MAP_TYPES.containsAll(MapType.allValues))
                check(maxAiChoice(h) == 24 && maxMajors(h) == 28)
            }
            // ---- 5. combinations Unciv (or the map) can't do
            expectError("setup: too many AIs for the map rejected", "fits at most") { updateSetup(lobby("su5"), mapOf("ai" to "20")) }
            expectError("setup: max turns out of range rejected", "100") { updateSetup(lobby("su5"), mapOf("max_turns" to "50")) }
            expectError("setup: unknown AI nation rejected", "no nation") { updateSetup(lobby("su5"), mapOf("ai_nations" to "Atlantis")) }
            expectError("setup: AI nation taken by a player rejected", "picked by a player") { updateSetup(lobby("su5"), mapOf("ai_nations" to "Greece")) }
            expectError("setup: AI nations follow the mods (Vanilla has no Austria)", "no nation") {
                updateSetup(lobby("su5").apply { settings.extra[ModsPlus.K_BASE] = "Civ V - Vanilla" }, mapOf("ai_nations" to "Austria"))
            }
            expectError("setup: reversed random civ range rejected", "above the maximum") { updateSetup(lobby("su5"), mapOf("random_civs" to "6-3")) }
            expectError("setup: too many random city-states rejected", "city-states") { updateSetup(lobby("su5"), mapOf("random_city_states" to "0-99")) }
            expectError("setup: random nations pool too small rejected", "pool") { updateSetup(lobby("su5"), mapOf("nations_pool" to "Rome")) }
            expectError("setup: slider out of range rejected", "Elevation") { updateSetup(lobby("su5"), mapOf("map_sliders" to "elevation=2")) }
            step("setup: rejected change leaves the lobby untouched") {
                val l = lobby("su6"); updateSetup(l, mapOf("one_city" to "true"))
                try { updateSetup(l, mapOf("nukes" to "false", "ai" to "40")) } catch (_: PlayerError) {}
                check(l.settings.extra["su_occ"] == "true" && l.settings.extra["su_nukes"] == null && l.settings.aiCivs == 2)
                updateSetup(l, mapOf("one_city" to "false")); check(l.settings.extra["su_occ"] == null && lobbyLines(l).isEmpty())
            }

            // ---- 6. save export (the running self-test game) and re-import
            val orig = t.s
            val (fileName, text) = t.eng { exportSaveE(orig) }
            step("save: export is an Unciv save that loads back") {
                check(fileName.endsWith(".civ"))
                val back = Engine.run { Engine.deserialize(text) }
                val (turns, ids) = t.eng { g -> g.turns to g.civilizations.map { it.civID } }
                check(back.turns == turns && back.civilizations.map { it.civID } == ids && !back.gameParameters.isOnlineMultiplayer)
                println("  [SetupPlus] export '$fileName': ${text.length} chars, turn ${back.turns + 1}, ${back.civilizations.size} civs, Unciv ${back.version.createdWith.text}")
            }
            step("save: re-import gives every player their own civ back") {
                val m = GameMeta(id = "su7", name = "Reimport", hostId = orig.meta.hostId).apply {
                    orig.meta.seats.forEach { seats += Seat(it.userId, it.userName, Constants.random) }
                }
                println("  [SetupPlus] " + importSave(m, text.toByteArray(), fileName, dataDir).lines().first())
                val stored = File(m.settings.extra[K_IMPORT]!!)
                check(stored.isFile && stored.parentFile.name == "imports" && stored.parentFile.parentFile.canonicalPath == dataDir.canonicalPath)
                check(lobbyLines(m).single().startsWith("📥") && maxMajors(m) == t.eng { g -> playableCivs(g).size })
                val g = t.eng { GameOps.startGame(m) }
                for ((i, s) in m.seats.withIndex()) check(s.civId == orig.meta.seats[i].civId) { "${s.userName} got ${s.civId}, had ${orig.meta.seats[i].civId}" }
                check(g.turns == t.eng { it.turns } && g.currentPlayerCiv.isHuman() && !stored.exists())
                check(g.civilizations.count { it.isHuman() } == m.seats.size)
                Engine.withGame(g) { GameOps.endTurn(g, g.currentPlayerCiv) }   // and it plays on
            }
            step("save: import maps new players to the save's civs") {
                val aiCiv = t.eng { g -> playableCivs(g).first { !it.isHuman() }.civID }
                val m = GameMeta(id = "su8", name = "Strangers", hostId = "701").apply { seats += Seat("701", "Cat", Constants.random); seats += Seat("702", "Dan", aiCiv) }
                importSave(m, text.toByteArray(), fileName, dataDir)
                val g = t.eng { GameOps.startGame(m) }
                val humansBefore = orig.meta.seats.map { it.civId }
                check(m.seats[1].civId == aiCiv && m.seats[0].civId in humansBefore) { "Cat=${m.seats[0].civId} Dan=${m.seats[1].civId}" }
                check(g.civilizations.filter { it.isHuman() }.map { it.playerId }.toSet() == setOf("discord:701", "discord:702") && g.currentPlayerCiv.isHuman())
                println("  [SetupPlus] import mapping: Cat -> ${m.seats[0].civId}, Dan -> ${m.seats[1].civId}, current ${g.currentPlayer}")
            }
            fun variant(edit: (GameInfo) -> Unit): String = Engine.run { val g = Engine.deserialize(text); edit(g); Engine.serialize(g) }
            val newer = Engine.run {
                val g = Engine.deserialize(text)
                g.version = CompatibilityVersion(CompatibilityVersion.CURRENT_COMPATIBILITY_NUMBER + 1, com.unciv.logic.Version("9.99.0", 99999))
                com.unciv.logic.files.FileConversions.zip(com.unciv.json.json().toJson(g))
            }
            expectError("save: newer Unciv version rejected", "newer") { importSave(lobby("su9"), newer.toByteArray(), "new.civ", dataDir) }
            expectError("save: missing mods rejected and listed", "CivBot Missing Mod") {
                importSave(lobby("su9"), variant { it.gameParameters.mods.add("CivBot Missing Mod") }.toByteArray(), "mods.civ", dataDir)
            }
            expectError("save: junk file rejected", "isn't an Unciv saved game") { importSave(lobby("su9"), "hello, not a save".toByteArray(), "junk.txt", dataDir) }
            expectError("save: too few civs for the seats rejected", "playable civilizations") {
                importSave(lobby("su9").apply { repeat(6) { seats += Seat("95$it", "P$it") } }, text.toByteArray(), "small.civ", dataDir)
            }
            expectError("save: finished game rejected", "already over") {
                importSave(lobby("su9"), variant { g -> g.victoryData = com.unciv.logic.VictoryData(g.civilizations.first { it.isMajorCiv }.civID, "Domination", g.turns) }
                    .toByteArray(), "over.civ", dataDir)
            }
            step("save: rejected imports store nothing; remove works") {
                val l = lobby("su9")
                check(l.settings.extra[K_IMPORT] == null && !File(dataDir, "imports/su9.civ").exists())
                importSave(l, text.toByteArray(), "x.civ", dataDir); check(File(dataDir, "imports/su9.civ").exists())
                clearImport(l); check(!File(dataDir, "imports/su9.civ").exists() && lobbyLines(l).isEmpty())
            }

            // ---- 7. lobby lists from the ruleset, nation picker, city-states beyond 12
            step("lobby: difficulties, speeds, eras, victories and city-state maximum from the ruleset") {
                val r = rules(lobby("su10"))
                check(r.difficulties.containsAll(GameOps.DIFFICULTIES) && r.speeds.containsAll(GameOps.SPEEDS) && r.eras.containsAll(GameOps.ERAS)) { "${r.difficulties} ${r.speeds} ${r.eras}" }
                check(r.victories.containsAll(GameOps.VICTORIES) && r.maxCityStates > 12) { "${r.victories} cs=${r.maxCityStates}" }
                println("  [SetupPlus] G&K lists: ${r.difficulties.size} difficulties, ${r.speeds.size} speeds, ${r.eras.size} eras, ${r.victories.size} victories, " +
                    "${r.maxCityStates} city-states, ${r.nations.size} nations")
            }
            step("lobby: nation picker shows leader + unique ability, hides taken nations") {
                val l = lobby("su10")
                val rows = nationPickerRows(l, "902")
                val opts = rows.flatMap { row -> row.components.filterIsInstance<StringSelectMenu>().flatMap { it.options } }
                val bab = opts.firstOrNull { it.value == "Babylon" }
                check(opts.none { it.value == "Greece" } && opts.any { it.value == Constants.random }) { "Greece offered to Ben" }
                check(bab != null && bab.description!!.contains("Nebuchadnezzar") && bab.description!!.contains("Ingenuity")) { bab?.description.toString() }
                check(opts.all { (it.description?.length ?: 0) <= 100 && it.label.length <= 100 } && rows.size <= 5 && rows.all { it.components.size <= 25 })
                check(rows.last().components.single() is Button)
                println("  [SetupPlus] picker: ${opts.size} options in ${rows.size - 1} selects; Babylon = ${bab.description}")
            }
            step("lobby: Nation details… view (Unciv's NationTable text)") {
                val e = nationDetails(lobby("su10"), "Babylon")
                check(e.title!!.contains("Nebuchadnezzar") && e.description!!.contains("Ingenuity") && e.description!!.length <= 4096) { e.description.toString() }
                println("  [SetupPlus] details: ${e.title} — ${e.description!!.replace("\n", " | ").take(200)}")
            }
            expectError("lobby: details of a nation that can't be picked refused", "you can pick") { nationDetails(lobby("su10"), "Atlantis") }
            step("lobby: more than 12 city-states (paged dropdown + started game)") {
                val l = lobby("su11", size = "Small", ai = 1, cs = 4)
                val max = maxCityStates(l)
                val pages = (0..10).map { cityStateOptions(l, it) }
                check(pages.all { it.size <= 25 } && pages[0].any { it.value == "pg:1" })
                val values = pages.flatten().map { it.value }.filter { !it.startsWith("pg:") }.distinct()
                check(values.size == max + 1 && values.contains(max.toString())) { "values $values (max $max)" }
                l.settings.cityStates = 16
                check(cityStateOptions(l).any { it.default && it.value == "16" })
                val g = t.eng { GameOps.startGame(l) }
                val cs = g.civilizations.count { it.isCityState }
                check(cs > 12) { "$cs city-states" }
                println("  [SetupPlus] city-states: dropdown 0–$max over ${values.size} values; Small map game has $cs")
            }
            // ---- 8. custom map size + Random map type pool + merged map options
            step("setup: custom map size by radius (hexagonal)") {
                val l = lobby("su12")
                println("  [SetupPlus] " + updateSetup(l, mapOf("map_size" to "Custom", "radius" to "12")).replace("\n", " | "))
                check(l.settings.mapSize == CUSTOM_SIZE && lobbyLines(l).single().contains("radius 12"))
                val g = t.eng { GameOps.startGame(l) }
                val ms = g.tileMap.mapParameters.mapSize
                check(ms.name == MapSize.custom && ms.radius == 12 && g.tileMap.values.size == com.unciv.logic.map.HexMath.getNumberOfTilesInHexagon(12)) { "$ms ${g.tileMap.values.size}" }
            }
            step("setup: custom map size by width × height (rectangular)") {
                val l = lobby("su13")
                println("  [SetupPlus] " + updateSetup(l, linkedMapOf("shape" to "Rectangular", "width" to "30", "height" to "18")).replace("\n", " | "))
                check(l.settings.mapSize == CUSTOM_SIZE)
                val g = t.eng { GameOps.startGame(l) }
                val ms = g.tileMap.mapParameters.mapSize
                check(ms.width == 30 && ms.height == 18 && g.tileMap.mapParameters.shape == MapShape.rectangular) { "$ms" }
                println("  [SetupPlus] rectangular custom map: $ms, ${g.tileMap.values.size} tiles")
            }
            expectError("setup: custom size too small rejected (Unciv's rule)", "too small") { updateSetup(lobby("su14"), mapOf("map_size" to "Custom", "radius" to "1")) }
            expectError("setup: custom size aspect ratio rejected", "aspect ratio") { updateSetup(lobby("su14"), linkedMapOf("shape" to "Rectangular", "width" to "100", "height" to "4")) }
            expectError("setup: world wrap needs 32 tiles width", "World wrap") {
                updateSetup(lobby("su14").apply { settings.worldWrap = true }, linkedMapOf("shape" to "Rectangular", "width" to "20", "height" to "16"))
            }
            expectError("setup: custom size too big rejected", "very large") { updateSetup(lobby("su14"), mapOf("map_size" to "Custom", "radius" to "120")) }
            expectError("setup: Custom without dimensions rejected", "dimensions") { updateSetup(lobby("su14"), mapOf("map_size" to "Custom")) }
            expectError("setup: radius and width together rejected", "not both") { updateSetup(lobby("su14"), mapOf("radius" to "10", "width" to "20")) }
            step("setup: large custom map warns like Unciv") {
                val txt = updateSetup(lobby("su14", ai = 1), mapOf("map_size" to "Custom", "radius" to "40"))
                check(txt.contains("large map")) { txt }
            }
            step("setup: Random map type picks only from random_types") {
                val l = lobby("su15")
                updateSetup(l, linkedMapOf("map_type" to "Random", "random_types" to "pangaea, archipelago"))
                check(l.settings.extra[K_RANDOM_TYPES] == "Pangaea,Archipelago" && lobbyLines(l).single().contains("Pangaea"))
                val seen = HashSet<String>()
                repeat(30) {
                    val setup = GameSetupInfo()
                    setup.gameParameters.players = ArrayList(l.seats.map { Player(it.nation, PlayerType.Human, "discord:${it.userId}") } + List(2) { Player() })
                    t.eng { applyToSetup(setup, l) }
                    seen += setup.mapParameters.type
                }
                check(seen == setOf(MapType.pangaea, MapType.archipelago)) { "picked $seen" }
                updateSetup(l, mapOf("random_types" to "all")); check(l.settings.extra[K_RANDOM_TYPES] == null)
            }
            expectError("setup: unknown random map type rejected", "isn't a map type") { updateSetup(lobby("su15"), mapOf("random_types" to "Atlantis")) }
            step("setup: map_options + water in map_sliders (merged options)") {
                val l = lobby("su16")
                updateSetup(l, mapOf("map_options" to "no_ruins=on legendary_start", "map_sliders" to "water=0.05 elevation=0.75"))
                val ex = l.settings.extra
                check(ex["su_noRuins"] == "true" && ex["su_legendary"] == "true" && ex[K_WATER] == "0.05" && ex[K_SLIDERS] == "elevation=0.75") { ex.toString() }
                updateSetup(l, mapOf("map_options" to "default", "map_sliders" to "reset"))
                check(ex["su_noRuins"] == null && ex[K_WATER] == null && ex[K_SLIDERS] == null)
            }
            expectError("setup: unknown map option rejected", "map option") { updateSetup(lobby("su16"), mapOf("map_options" to "no_rivers=on")) }

            // ---- 9. export fairness
            step("save: export only after the game, or the host with allow_export, or alone vs AI") {
                val m = lobby("su17").apply { status = Status.RUNNING }   // Ann (host 901) + Ben (902)
                check(exportBlocker(m, "901", false)!!.contains("ended") && exportBlocker(m, "902", false) != null)
                setAllowExport(m, true)
                check(exportBlocker(m, "901", false) == null && exportBlocker(m, "902", false)!!.contains("host"))
                check(lobbyLines(m).any { it.startsWith("💾") })
                check(exportBlocker(m, "902", true) == null && exportBlocker(m, "999", true) != null)
                setAllowExport(m, false); m.seats[1].aiControlled = true
                check(exportBlocker(m, "901", false) == null)   // the only human left
                val l = lobby("su18")
                ModsPlus.updateLobby(l, ModsPlus.LobbyChange(allowExport = true))
                check(l.settings.extra[K_ALLOW_EXPORT] == "true" && lobbyLines(l).any { it.contains("allow_export") })
                ModsPlus.updateLobby(l, ModsPlus.LobbyChange(allowExport = false)); check(l.settings.extra[K_ALLOW_EXPORT] == null)
            }

            // ---- 10. turn history + rollback
            val store = t.ctl.store
            step("rollback: start-of-turn snapshots of the running game (newest $HISTORY_KEEP kept)") {
                val turns = rollbackTurns(orig, store)
                val now = t.eng { it.turns + 1 }
                check(turns.size >= 2 && turns.last() <= now && turns.size <= HISTORY_KEEP) { "$turns (now $now)" }
                check(store.historyGameId(orig.meta.id) == t.eng { it.gameId })
                println("  [SetupPlus] history of ${orig.meta.id}: turns $turns, now on turn $now (${store.historyDir(orig.meta.id)})")
                repeat(13) { i -> store.saveHistory("suprune", "g1", i + 1, "x$i", HISTORY_KEEP) }
                check(store.historyTurns("suprune") == (4..13).toList()) { store.historyTurns("suprune").toString() }
                store.saveHistory("suprune", "g2", 1, "y", HISTORY_KEEP)
                check(store.historyTurns("suprune") == listOf(1) && store.historyGameId("suprune") == "g2")   // another game under the same id
                store.historyDir("suprune").deleteRecursively()
            }
            step("rollback: preview, vote of every player, restore, plays on") {
                val turns = rollbackTurns(orig, store)
                val target = turns.first()
                // on a copy, so the extension tests after us and the tab renders see the real game
                val cm = GameMeta(id = "surb", name = "Rollback copy", hostId = orig.meta.hostId, status = Status.RUNNING).apply {
                    orig.meta.seats.forEach { s0 -> seats += Seat(s0.userId, s0.userName, s0.nation, s0.civId) }
                }
                val copy = Session(cm, t.eng { g -> Engine.deserialize(Engine.serialize(g)) })
                store.historyDir("surb").deleteRecursively()
                store.historyDir(orig.meta.id).copyRecursively(store.historyDir("surb"))
                try {
                    val now = Engine.withGame(copy.game!!) { it.turns + 1 }
                    val plan = Engine.withGame(copy.game!!) { rollbackPlanE(copy, store, target) }
                    check(plan.summary.contains("start of turn $target") && plan.summary.contains("cities")) { plan.summary }
                    println("  [SetupPlus] rollback preview: ${plan.summary.replace("\n", " | ").take(300)}")
                    val tooFar = try { Engine.withGame(copy.game!!) { rollbackPlanE(copy, store, now + 3) }; null } catch (e: PlayerError) { e.message }
                    check(tooFar?.contains("only go back") == true) { "future turn: $tooFar" }
                    val v = RollbackVote("t1", plan, cm.hostId, cm.seats.filter { !it.aiControlled }.map { it.userId }.toSet())
                    check(!v.complete && v.text(cm.name).contains("1/2") && voteRows(v).single().components.size == 2)
                    check(runCatching { v.accept("999") }.exceptionOrNull() is PlayerError)   // not a player
                    check(v.accept(cm.seats[1].userId) && v.complete)
                    val msg = Engine.withGame(copy.game!!) { rollbackE(copy, store, plan, t.ctl) }
                    val (turnNow, curHuman) = Engine.withGame(copy.game!!) { g -> (g.turns + 1) to g.currentPlayerCiv.isHuman() }
                    check(turnNow == target && curHuman && store.historyTurns("surb").all { it <= target }) { "now $turnNow, history ${store.historyTurns("surb")}" }
                    check(cm.events.last().startsWith("⏪"))
                    val stale = try { Engine.withGame(copy.game!!) { rollbackE(copy, store, plan) }; null } catch (e: PlayerError) { e.message }
                    check(target == now || stale?.contains("moved on") == true) { "stale plan: $stale" }
                    Engine.withGame(copy.game!!) { g -> GameOps.endTurn(g, g.currentPlayerCiv) }   // and it plays on
                    println("  [SetupPlus] $msg (from turn $now)")
                } finally { store.delete("surb") }
            }
            expectError("rollback: unknown turn rejected", "no snapshot") {
                t.eng { rollbackPlanE(orig, store, 0) }
            }
        } catch (e: Throwable) {
            failures += "setup: ${e.javaClass.simpleName}: ${e.message}"; e.printStackTrace()
        } finally {
            selfTesting = false
            runCatching { t.eng { } }   // the self-test game is Unciv's current game again for the extensions after us
        }
        if (failures.isNotEmpty()) throw IllegalStateException("SetupPlus self-test failures:\n  " + failures.joinToString("\n  "))
    }
}

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
import net.dv8tion.jda.api.Permission
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent
import net.dv8tion.jda.api.interactions.InteractionHook
import net.dv8tion.jda.api.interactions.commands.OptionType
import net.dv8tion.jda.api.interactions.commands.build.OptionData
import net.dv8tion.jda.api.interactions.commands.build.SubcommandData
import net.dv8tion.jda.api.interactions.commands.build.SubcommandGroupData
import net.dv8tion.jda.api.utils.FileUpload
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
 *  - `/civ save export` (any player): the game as an Unciv save file (Engine.serialize = Unciv's own format).
 *  - `/civ save import file:` (host or Manage Server, lobby only): start the lobby's game from an Unciv save.
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

    // ================================================================== caps (used by the core lobby, no engine needed)

    /** How many major civilizations (players + AI) this lobby's map can hold. */
    fun maxMajors(meta: GameMeta): Int {
        if (importPath(meta) != null) return meta.settings.extra[K_IMPORT_CIVS]?.toIntOrNull() ?: OTHER_CAP
        if (scenario(meta) != null || customMap(meta) != null) return OTHER_CAP
        return MAP_SIZE_CAP[meta.settings.mapSize] ?: 12
    }

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
                "map_size" -> size = MAP_SIZE_CAP.keys.firstOrNull { it.equals(v, true) } ?: throw PlayerError("Map sizes: ${MAP_SIZE_CAP.keys.joinToString()}.")
                "map_type" -> type = (MapType.allValues + RANDOM_TYPE).firstOrNull { it.equals(v, true) }
                    ?: throw PlayerError("Map types: ${(MapType.allValues + RANDOM_TYPE).joinToString()}.")
                "shape" -> put(K_SHAPE, (MapShape.allValues.firstOrNull { it.equals(v, true) } ?: throw PlayerError("Map shapes: ${MapShape.allValues.joinToString()}."))
                    .takeIf { it != MapShape.hexagonal })
                "resources" -> put(K_RESOURCES, (RESOURCES.firstOrNull { it.equals(v, true) } ?: throw PlayerError("Resource settings: ${RESOURCES.joinToString()}."))
                    .takeIf { it != MapResourceSetting.default.label })
                "water" -> {
                    val f = v.toFloatOrNull()?.takeIf { it in -0.1f..0.1f } ?: throw PlayerError("Water level goes from -0.1 (much less water) to 0.1 (much more).")
                    put(K_WATER, if (f == 0f) null else fmt(f))
                }
                "mirroring" -> put(K_MIRROR, (MIRRORING.firstOrNull { it.equals(v, true) } ?: throw PlayerError("Mirroring: ${MIRRORING.joinToString()}."))
                    .takeIf { it != MirroringType.none })
                "seed" -> put(K_SEED, if (isDefaultWord(v)) null else (v.toLongOrNull()?.toString() ?: throw PlayerError("The seed is a whole number (or `random`).")))
                "map_sliders" -> put(K_SLIDERS, if (isDefaultWord(v)) null else parseSliders(v, pairs(ex[K_SLIDERS])))
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
        notes += validateE(copyMeta(meta, ex, size, type, ai), rs)
        // commit only after the whole setup checked out
        st.extra.clear(); st.extra.putAll(ex)
        st.mapSize = size; st.mapType = type; st.aiCivs = ai
        val summary = summaryParts(meta)
        return (listOf("✅ Setup updated.", if (summary.isEmpty()) "All /civ setup options are at Unciv's defaults." else "⚙️ " + summary.joinToString(" · ")) + notes).joinToString("\n")
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
            val cap = MAP_SIZE_CAP[st.mapSize] ?: 12
            if (majors > cap) throw PlayerError("A **${st.mapSize}** map fits at most $cap civilizations, but this setup can have $majors " +
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
            mp.type = if (st.mapType == RANDOM_TYPE || st.mapType !in MapType.allValues) MapType.allValues.random() else st.mapType
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
        val parts = summaryParts(meta)
        if (parts.isEmpty()) return emptyList()
        val line = "⚙️ " + parts.joinToString(" · ")
        return listOf(if (line.length > 400) line.take(397) + "…" else line)
    }

    /** Every option with its current value (for `/civ setup` with no options). */
    fun describe(meta: GameMeta): String {
        val st = meta.settings
        val ex = st.extra
        val sl = pairs(ex[K_SLIDERS])
        fun on(b: Boolean) = if (b) "on" else "off"
        val lines = ArrayList<String>()
        lines += "**Map** — map_size `${st.mapSize}` · map_type `${st.mapType}` · shape `${ex[K_SHAPE] ?: MapShape.hexagonal}` · resources `${ex[K_RESOURCES] ?: "Default"}` · " +
            "water `${ex[K_WATER]?.let { "${WATER[it] ?: it} ($it)" } ?: "Normal (0)"}` · mirroring `${ex[K_MIRROR] ?: MirroringType.none}` · seed `${ex[K_SEED] ?: "random"}`"
        lines += "**map_sliders** — " + SLIDERS.joinToString(" · ") { "${it.key} `${sl[it.key] ?: fmt(it.default)}`" }
        lines += "**Rules** — " + FLAGS.joinToString(" · ") { "${it.option} `${on(flagValue(ex, it))}`" }
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
            OptionData(OptionType.STRING, "map_size", "Map size (Huge included)").also { o -> MAP_SIZE_CAP.forEach { (k, n) -> o.addChoice("$k (up to $n civs)", k) } },
            OptionData(OptionType.STRING, "map_type", "Map generator").also { o -> (MapType.allValues + RANDOM_TYPE).forEach { o.addChoice(it, it) } },
            OptionData(OptionType.STRING, "shape", "Map shape").also { o -> MapShape.allValues.forEach { o.addChoice(it, it) } },
            OptionData(OptionType.STRING, "resources", "Resource abundance").also { o -> RESOURCES.forEach { o.addChoice(it, it) } },
            OptionData(OptionType.STRING, "water", "Sea level (Unciv's water level)").also { o -> WATER.forEach { (v, l) -> o.addChoice("$l ($v)", v) } },
            OptionData(OptionType.STRING, "mirroring", "Mirror the map so every side is the same").also { o ->
                MIRRORING.forEach { o.addChoice(if (it == MirroringType.none || it == MirroringType.leftright) it else "$it (experimental)", it) } },
            OptionData(OptionType.STRING, "seed", "Map seed: a number to get the same map again, or 'random'"),
            OptionData(OptionType.STRING, "map_sliders", "e.g. elevation=0.75 temperature=0.5 vegetation=0.6 coast=4 biome=8 (or reset)"),
        ).addOptions(FLAGS.map { OptionData(OptionType.BOOLEAN, it.option, it.desc.take(100)) }).addOptions(
            OptionData(OptionType.INTEGER, "max_turns", "Turn limit for Time victory (100–1500, default 500)").setRequiredRange(100, 1500),
            OptionData(OptionType.INTEGER, "ai", "Number of AI opponents (more than the lobby dropdown offers)").setRequiredRange(0, OTHER_CAP.toLong()),
            OptionData(OptionType.STRING, "ai_nations", "Specific AI nations, e.g. Rome,Greece (or 'random')"),
            OptionData(OptionType.STRING, "random_civs", "Random number of civilizations, e.g. 3-8 (or 'off')"),
            OptionData(OptionType.STRING, "random_city_states", "Random number of city-states, e.g. 2-6 (or 'off')"),
            OptionData(OptionType.STRING, "nations_pool", "Random nations come only from these, e.g. Rome,Greece,Egypt (or 'off')"),
            OptionData(OptionType.STRING, "stats", "e.g. demographics=on rankings=off charts=off own_only=on extra_types=on (or off/default)"),
        ),
    )

    override fun commandGroups(): List<SubcommandGroupData> = listOf(
        SubcommandGroupData("save", "Unciv save files: export this game, or start a lobby from one").addSubcommands(
            SubcommandData("export", "Download this game as an Unciv save (opens in desktop Unciv as hotseat)"),
            SubcommandData("import", "Host / Manage Server: start this lobby's game from an Unciv save").addOptions(
                OptionData(OptionType.ATTACHMENT, "file", "The Unciv save file (max 20 MB)"),
                OptionData(OptionType.BOOLEAN, "remove", "Drop the imported save from this lobby"),
            ),
        ),
    )

    override fun onCommand(event: SlashCommandInteractionEvent, bot: BotApi): Boolean {
        when {
            event.subcommandGroup == null && event.subcommandName == "setup" -> setupCommand(event, bot)
            event.subcommandGroup == "save" && event.subcommandName == "export" -> exportCommand(event, bot)
            event.subcommandGroup == "save" && event.subcommandName == "import" -> importCommand(event, bot)
            else -> return false
        }
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
        val s = bot.gameHere(event.channel.id, event.user.id)?.takeIf { it.meta.seatOf(event.user.id) != null }
            ?: return event.reply("You're not playing in a game here. Run this in the game's channel or thread.").setEphemeral(true).queue()
        val game = s.game ?: return event.reply("**${s.meta.name}** hasn't started yet — there's nothing to export.").setEphemeral(true).queue()
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
        } catch (e: Throwable) {
            failures += "setup: ${e.javaClass.simpleName}: ${e.message}"; e.printStackTrace()
        } finally {
            selfTesting = false
            runCatching { t.eng { } }   // the self-test game is Unciv's current game again for the extensions after us
        }
        if (failures.isNotEmpty()) throw IllegalStateException("SetupPlus self-test failures:\n  " + failures.joinToString("\n  "))
    }
}

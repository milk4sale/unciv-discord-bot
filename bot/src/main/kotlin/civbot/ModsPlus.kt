package civbot

import com.badlogic.gdx.files.FileHandle
import com.badlogic.gdx.utils.JsonReader
import com.unciv.Constants
import com.unciv.UncivGame
import com.unciv.logic.GameInfo
import com.unciv.logic.civilization.AlertType
import com.unciv.logic.civilization.PlayerType
import com.unciv.logic.files.MapSaver
import com.unciv.logic.github.Github.repoNameToFolderName
import com.unciv.logic.map.MapGeneratedMainType
import com.unciv.logic.map.TileMap
import com.unciv.models.metadata.BaseRuleset
import com.unciv.models.metadata.GameSetupInfo
import com.unciv.models.metadata.Player
import com.unciv.models.ruleset.Ruleset
import com.unciv.models.ruleset.RulesetCache
import com.unciv.models.ruleset.tile.TerrainType
import com.unciv.models.ruleset.unique.UniqueType
import com.unciv.models.ruleset.validation.ModCompatibility
import net.dv8tion.jda.api.JDA
import net.dv8tion.jda.api.Permission
import net.dv8tion.jda.api.events.interaction.command.CommandAutoCompleteInteractionEvent
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent
import net.dv8tion.jda.api.hooks.ListenerAdapter
import net.dv8tion.jda.api.interactions.InteractionHook
import net.dv8tion.jda.api.interactions.commands.OptionType
import net.dv8tion.jda.api.interactions.commands.build.OptionData
import net.dv8tion.jda.api.interactions.commands.build.SubcommandData
import org.slf4j.LoggerFactory
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipEntry
import java.util.zip.ZipException
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * Mods, custom maps and scenarios.
 *
 *  - `/civ mod action:install url:https://github.com/<owner>/<repo>` (Manage Server) downloads the repo's
 *    default-branch zip into Unciv's mods folder (`<unciv>/android/assets/mods/<Mod Name>`); `action:list`;
 *    `action:remove name:<mod>` (Manage Server).
 *  - `/civ map action:upload file:<map file> [name:]` (Manage Server) stores a map made in Unciv's map editor in
 *    `<unciv>/android/assets/maps/`; `action:list`; `action:remove name:<map>` (Manage Server).
 *  - `/civ mod action:browse query:<text>` searches GitHub for repositories with the `unciv-mod` topic (as Unciv's mod
 *    manager does) and lists them with an install hint (installing still needs Manage Server).
 *  - `/civ lobby add_mod: remove_mod: base: map: scenario: clear_mods:` (host, lobby only; no options = show).
 *    `accept_mod_errors:True` lets a combination Unciv reports errors for start anyway (Unciv's AcceptModErrorsPopup);
 *    `allow_export:True` lets the host export the save mid-game (SetupPlus).
 *
 * Settings: meta.settings.mods (extension mods), meta.settings.extra["baseRuleset" | "customMap" | "scenario"].
 * Scenarios are Unciv saved games shipped by mods in `<mod>/scenarios/` (same lookup as Unciv's NewGameScreen).
 */
object ModsPlus : Extension {
    private val log = LoggerFactory.getLogger("ModsPlus")

    const val MAX_ZIP_BYTES = 50L * 1024 * 1024
    const val MAX_UNZIPPED_BYTES = 200L * 1024 * 1024
    const val MAX_ZIP_ENTRIES = 20_000
    const val MAX_MAP_BYTES = 10L * 1024 * 1024
    const val K_BASE = "baseRuleset"
    const val K_MAP = "customMap"
    const val K_SCENARIO = "scenario"
    /** "true": the host accepted Unciv's mod-check errors for this lobby */
    const val K_ACCEPT_ERRORS = "mo_acceptErrors"
    /** How many error lines the accepted combination has (for the lobby warning) */
    const val K_MOD_ERRORS = "mo_modErrors"
    private val DEFAULT_BASE = BaseRuleset.Civ_V_GnK.fullName
    private const val VERB_SELFTEST = "modsplus_selftest_ok"

    // ================================================================== catalog (cached for autocomplete / lists)

    class ModInfo(val name: String, val isBase: Boolean, val summary: String, val scenarios: List<String>, val source: String?)
    class ScenarioInfo(val key: String, val mod: String, val fileName: String, val file: FileHandle)

    @Volatile var installedMods: List<ModInfo> = emptyList(); private set
    @Volatile var baseRulesets: List<String> = listOf(DEFAULT_BASE); private set
    @Volatile var scenarioList: List<ScenarioInfo> = emptyList(); private set

    /** Where Unciv loads mods from: UncivFiles.getModsFolder() = local "mods" = <working dir>/mods (working dir is android/assets). */
    fun modsDir(): File = UncivGame.Current.files.getModsFolder().file().absoluteFile.also { it.mkdirs() }
    /** Where Unciv keeps custom maps: local "maps" (MapSaver.mapsFolder). */
    fun mapsDir(): File = UncivGame.Current.files.getLocalFile(MapSaver.mapsFolder).file().absoluteFile.also { it.mkdirs() }

    fun mapNames(): List<String> = mapsDir().listFiles { f -> f.isFile && !f.name.startsWith(".") }.orEmpty().map { it.name }.sortedBy { it.lowercase() }

    /** Engine thread. Reloads every ruleset including installed mods. Returns Unciv's loading error lines. */
    fun reloadE(): List<String> {
        modsDir(); mapsDir()
        val errors = RulesetCache.loadRulesets(noMods = false)
        refreshCatalogE()
        SetupPlus.clearRulesCache()
        return errors
    }

    private fun refreshCatalogE() {
        installedMods = RulesetCache.values.filter { it.folderLocation != null }.distinctBy { it.name }.sortedBy { it.name.lowercase() }.map { infoOf(it) }
        baseRulesets = RulesetCache.getSortedBaseRulesets()
        scenarioList = UncivGame.Current.files.getScenarioFiles().filter { (f, _) -> !f.isDirectory && !f.name().startsWith(".") }
            .map { (f, mod) -> ScenarioInfo("${mod.name}/${f.name()}", mod.name, f.name(), f) }.toList().sortedBy { it.key.lowercase() }
    }

    private fun infoOf(rs: Ruleset): ModInfo {
        val parts = ArrayList<String>()
        fun n(count: Int, what: String) { if (count > 0) parts += "$count $what" }
        n(rs.nations.values.count { it.isMajorCiv }, "nations"); n(rs.units.size, "units"); n(rs.buildings.size, "buildings")
        n(rs.technologies.size, "techs"); n(rs.policies.size, "policies")
        val folder = rs.folderLocation
        val scen = folder?.child("scenarios")?.takeIf { it.exists() }?.list()?.filter { !it.isDirectory }?.map { it.name() }.orEmpty()
        val src = folder?.child(".civbot-source")?.takeIf { it.exists() }?.readString()?.trim()
        return ModInfo(rs.name, rs.modOptions.isBaseRuleset, parts.joinToString(", ").ifEmpty { "no game content" }, scen, src)
    }

    private fun isDefaultWord(s: String) = s.trim().lowercase() in setOf("", "default", "none", "generated", "off", "-")

    // ================================================================== mod install / remove

    class InstallResult(val name: String, val isBase: Boolean, val replaced: Boolean, val summary: String, val problems: String?, val scenarios: List<String>)

    private val installLock = Any()
    private val githubUrl = Regex("""^https://github\.com/([A-Za-z0-9](?:[A-Za-z0-9-]{0,38}))/([A-Za-z0-9._-]{1,100})/?$""")

    /** @return owner to repo, or throws PlayerError */
    fun parseGithubUrl(url: String): Pair<String, String> {
        val m = githubUrl.matchEntire(url.trim()) ?: throw PlayerError("Give a GitHub repository URL like `https://github.com/owner/repo` (nothing else is accepted).")
        val repo = m.groupValues[2].removeSuffix(".git")
        if (repo.isEmpty() || repo == "." || repo == "..") throw PlayerError("That isn't a valid repository name.")
        return m.groupValues[1] to repo
    }

    private val http: HttpClient by lazy {
        HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).connectTimeout(Duration.ofSeconds(15)).build()
    }
    private fun request(url: String) = HttpRequest.newBuilder(URI(url)).timeout(Duration.ofSeconds(120))
        .header("User-Agent", "CivBot-Unciv-mod-installer").GET()

    /** Best effort: canonical repo name (correct casing) from GitHub's API. Null if the API isn't reachable (rate limit…). */
    private fun githubRepoName(owner: String, repo: String): String? = try {
        val resp = http.send(request("https://api.github.com/repos/$owner/$repo").header("Accept", "application/vnd.github+json")
            .timeout(Duration.ofSeconds(20)).build(), HttpResponse.BodyHandlers.ofString())
        when (resp.statusCode()) {
            200 -> JsonReader().parse(resp.body()).getString("name", null)
            404 -> throw PlayerError("GitHub says **$owner/$repo** doesn't exist (or it's private).")
            else -> null
        }
    } catch (e: PlayerError) { throw e } catch (e: Exception) { null }

    /** Streams [url] into [dest], refusing anything bigger than [max] bytes. */
    private fun download(url: String, dest: File, max: Long) {
        val resp = http.send(request(url).build(), HttpResponse.BodyHandlers.ofInputStream())
        resp.body().use { body ->
            when (resp.statusCode()) {
                200 -> {}
                404 -> throw PlayerError("GitHub has no downloadable archive there (is the repository public?).")
                else -> throw PlayerError("GitHub answered HTTP ${resp.statusCode()} — try again later.")
            }
            val len = resp.headers().firstValueAsLong("Content-Length")
            if (len.isPresent && len.asLong > max) throw PlayerError("That mod is too big (${len.asLong / 1048576} MB, limit ${max / 1048576} MB).")
            dest.outputStream().use { out -> copyCapped(body, out, max, "That mod is too big (limit ${max / 1048576} MB).") }
        }
    }

    private fun copyCapped(input: InputStream, out: OutputStream, max: Long, tooBig: String): Long {
        val buf = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val r = input.read(buf)
            if (r < 0) break
            total += r
            if (total > max) throw PlayerError(tooBig)
            out.write(buf, 0, r)
        }
        return total
    }

    /** Download + install from GitHub. NOT on the engine thread (blocks on network, then reloads via Engine.run). */
    fun installFromGithub(url: String): InstallResult {
        val (owner, repo) = parseGithubUrl(url)
        val name = githubRepoName(owner, repo) ?: repo
        val tmp = File(modsDir(), ".civbot-dl-${System.nanoTime()}.zip")
        try {
            // codeload's HEAD archive = the default branch (Unciv downloads from the same host, see GithubAPI.toCodeloadUrl)
            download("https://codeload.github.com/$owner/$repo/zip/HEAD", tmp, MAX_ZIP_BYTES)
            return installFromZip(tmp, name.repoNameToFolderName(), "https://github.com/$owner/$repo")
        } finally { tmp.delete() }
    }

    fun sanitizeName(raw: String): String {
        val s = raw.trim().replace(Regex("""[^A-Za-z0-9 ._=()'&+,-]"""), "_").trim().take(80).trimEnd('.', ' ')
        if (s.isEmpty() || s.startsWith(".") || s.all { it == '_' || it == '.' }) throw PlayerError("That name can't be used.")
        return s
    }

    /** Extracts [zip] into [dest] refusing zip-slip / absolute paths, too many files or too much data. */
    fun extractSafely(zip: File, dest: File) {
        dest.mkdirs()
        val root = dest.canonicalFile
        val rootPrefix = root.path + File.separator
        try {
            ZipFile(zip).use { zf ->
                var count = 0
                var total = 0L
                for (e in zf.entries()) {
                    if (++count > MAX_ZIP_ENTRIES) throw PlayerError("The archive has too many files.")
                    val n = e.name.replace('\\', '/')
                    if (n.startsWith("/") || n.contains(':') || n.split('/').any { it == ".." } || n.contains('\u0000'))
                        throw PlayerError("The archive contains an unsafe path (`${n.take(60)}`) — refusing to install it.")
                    val out = File(root, n).canonicalFile
                    if (out != root && !out.path.startsWith(rootPrefix)) throw PlayerError("The archive contains an unsafe path — refusing to install it.")
                    if (e.isDirectory) { out.mkdirs(); continue }
                    out.parentFile?.mkdirs()
                    zf.getInputStream(e).use { inp ->
                        out.outputStream().use { o ->
                            total += copyCapped(inp, o, MAX_UNZIPPED_BYTES - total, "The mod unpacks to more than ${MAX_UNZIPPED_BYTES / 1048576} MB.")
                        }
                    }
                }
            }
        } catch (e: ZipException) {
            throw PlayerError("That isn't a valid zip archive.")
        }
    }

    /** The folder with `jsons/` in it: the archive root or its single top folder (GitHub zips have `<repo>-<branch>/`). */
    private fun findModRoot(dir: File): File {
        if (File(dir, "jsons").isDirectory) return dir
        val subs = dir.listFiles { f -> f.isDirectory && !f.name.startsWith("__MACOSX") }.orEmpty()
        if (subs.size == 1 && File(subs[0], "jsons").isDirectory) return subs[0]
        throw PlayerError("This doesn't look like an Unciv ruleset mod (no `jsons` folder). Audio/graphics-only mods aren't useful to the bot.")
    }

    /** Installs a mod archive under mods/<folderName>, replacing an older copy. NOT on the engine thread. */
    fun installFromZip(zip: File, folderName: String, source: String?): InstallResult = synchronized(installLock) {
        val name = sanitizeName(folderName)
        val mods = modsDir()
        val staging = File(mods, ".civbot-unpack-${System.nanoTime()}")
        val backup = File(mods, ".civbot-old-${System.nanoTime()}")
        try {
            extractSafely(zip, staging)
            val root = findModRoot(staging)
            val dest = File(mods, name)
            val replaced = dest.exists()
            if (replaced && !dest.renameTo(backup)) throw PlayerError("Couldn't replace the installed copy of **$name**.")
            if (!root.renameTo(dest)) {
                if (replaced) backup.renameTo(dest)
                throw PlayerError("Couldn't move the mod into the mods folder.")
            }
            source?.let { File(dest, ".civbot-source").writeText(it) }
            val (info, errors, problems) = Engine.run {
                val errs = reloadE()
                val rs = RulesetCache[name]
                val probs = rs?.let { r ->
                    val (_, list) = if (r.modOptions.isBaseRuleset) RulesetCache.checkCombinedModLinks(LinkedHashSet(), r.name)
                        else RulesetCache.checkCombinedModLinks(linkedSetOf(r.name), DEFAULT_BASE)
                    if (list.isError()) "Unciv reports errors in it (it may not be playable): " +
                        list.getErrorText().lines().filter { it.startsWith("Error") }.take(3).joinToString("; ").take(400)
                    else if (list.isWarnUser()) "Unciv reports some warnings — it should work, but don't expect everything to be perfect."
                    else null
                }
                Triple(rs?.let { infoOf(it) }, errs, probs)
            }
            if (info == null) {
                dest.deleteRecursively()
                if (replaced) backup.renameTo(dest)
                Engine.run { reloadE() }
                throw PlayerError("**$name** didn't load as an Unciv mod: ${errors.joinToString(" ").take(500).ifEmpty { "unknown error" }}")
            }
            backup.deleteRecursively()
            InstallResult(info.name, info.isBase, replaced, info.summary, problems, info.scenarios)
        } finally {
            staging.deleteRecursively()
            if (backup.exists() && File(mods, name).exists()) backup.deleteRecursively()
        }
    }

    /** Games (not ended) that use [mod]. */
    private fun gamesUsing(store: Store, mod: String) = store.sessions.values.filter { s ->
        val st = s.meta.settings
        s.meta.status != Status.ENDED && (st.mods.any { it.equals(mod, true) } || st.extra[K_BASE].equals(mod, true) ||
            st.extra[K_SCENARIO]?.startsWith("$mod/", true) == true ||
            s.game?.gameParameters?.let { p -> p.mods.any { it.equals(mod, true) } || p.baseRuleset.equals(mod, true) } == true)
    }

    /** NOT on the engine thread. */
    fun removeMod(name: String, store: Store): String = synchronized(installLock) {
        val info = installedMods.firstOrNull { it.name.equals(name.trim(), true) }
            ?: throw PlayerError("No installed mod called **${name.trim()}**. See `/civ mod action:list`.")
        val users = gamesUsing(store, info.name)
        if (users.isNotEmpty()) throw PlayerError("**${info.name}** is used by ${users.joinToString { "**${it.meta.name}** (${it.meta.id})" }} — finish or change those games first.")
        val mods = modsDir().canonicalFile
        val dir = File(mods, info.name).canonicalFile
        if (dir.parentFile != mods || !dir.isDirectory) throw PlayerError("Couldn't find that mod's folder.")
        dir.deleteRecursively()
        Engine.run { reloadE() }
        "🗑️ Removed mod **${info.name}**."
    }

    // ================================================================== maps

    private fun mapHandle(name: String): FileHandle {
        val n = mapNames().firstOrNull { it.equals(name.trim(), true) }
            ?: throw PlayerError("No custom map called **${name.trim()}**. See `/civ map action:list`.")
        return UncivGame.Current.files.getLocalFile(MapSaver.mapsFolder).child(n)
    }

    private fun usableLand(map: TileMap, rs: Ruleset) = map.tileList.count { t ->
        val ter = rs.terrains[t.baseTerrain]
        ter != null && ter.type == TerrainType.Land && !ter.impassable && t.terrainFeatures.none { rs.terrains[it]?.impassable == true }
    }

    private fun describeMapE(name: String, map: TileMap, rs: Ruleset): String {
        val p = map.mapParameters
        val starts = map.startingLocations.map { it.position }.distinct().size
        val rules = (listOf(p.baseRuleset) + p.mods).joinToString(" + ")
        return "**$name** — ${p.mapSize.name} ${p.shape}, ${map.tileList.size} tiles (${usableLand(map, rs)} usable land), " +
            "${if (starts == 0) "no preset start positions" else "$starts start position(s)"} · rules: $rules"
    }

    /** Engine thread: parse + validate an Unciv map file's text. */
    private fun parseMapE(text: String): Pair<TileMap, Ruleset> {
        val map = try { MapSaver.mapFromSavedString(text) } catch (e: Exception) { null }
        if (map == null || map.tileList.isEmpty())
            throw PlayerError("That isn't an Unciv map file. Make one in Unciv's **Map editor** (desktop or Android) and upload the file from its `maps` folder.")
        val p = map.mapParameters
        val missing = (p.mods + p.baseRuleset).filter { !RulesetCache.containsKey(it) }
        if (missing.isNotEmpty()) throw PlayerError("That map needs mod(s) ${missing.joinToString { "**$it**" }} — install them first with `/civ mod action:install`.")
        val rs = RulesetCache.getComplexRuleset(p)
        val inc = map.getRulesetIncompatibility(rs)
        if (inc.isNotEmpty()) throw PlayerError("That map doesn't match its own rules: ${inc.take(5).joinToString("; ")}")
        return map to rs
    }

    /** Validate and store an uploaded map. NOT on the engine thread. */
    fun saveMap(bytes: ByteArray, rawName: String): String {
        if (bytes.size > MAX_MAP_BYTES) throw PlayerError("Map files can be at most ${MAX_MAP_BYTES / 1048576} MB.")
        val name = sanitizeName(rawName.trim().removeSuffix(".map").removeSuffix(".json").removeSuffix(".txt"))
        val text = String(bytes, Charsets.UTF_8)
        val desc = Engine.run { val (map, rs) = parseMapE(text); describeMapE(name, map, rs) }
        val target = File(mapsDir(), name)
        val replaced = target.exists()
        val tmp = File(mapsDir(), ".$name.upload")
        tmp.writeBytes(bytes)
        if (!tmp.renameTo(target)) { target.delete(); tmp.renameTo(target) }
        return "🗺️ ${if (replaced) "Replaced" else "Saved"} map $desc\nHosts can now pick it with `/civ lobby map:$name`."
    }

    /** NOT on the engine thread. */
    fun listMaps(): String {
        val names = mapNames()
        if (names.isEmpty()) return "No custom maps yet. Make one in Unciv's **Map editor** and upload it with `/civ map action:upload file:`."
        return "🗺️ **Custom maps** (${names.size}):\n" + Engine.run {
            names.take(30).joinToString("\n") { n ->
                try {
                    val p = MapSaver.loadMapParameters(UncivGame.Current.files.getLocalFile(MapSaver.mapsFolder).child(n))
                    "• **$n** — ${p.mapSize.name} ${p.shape}" + (if (p.mods.isNotEmpty()) " · needs ${p.mods.joinToString()}" else "") +
                        (if (p.baseRuleset != DEFAULT_BASE) " · base ${p.baseRuleset}" else "")
                } catch (e: Exception) { "• **$n** — (unreadable)" }
            }
        }
    }

    fun removeMap(name: String, store: Store): String {
        val n = mapNames().firstOrNull { it.equals(name.trim(), true) } ?: throw PlayerError("No custom map called **${name.trim()}**.")
        val users = store.sessions.values.filter { it.meta.status == Status.LOBBY && it.meta.settings.extra[K_MAP].equals(n, true) }
        if (users.isNotEmpty()) throw PlayerError("**$n** is picked in lobby ${users.joinToString { "**${it.meta.name}**" }}.")
        File(mapsDir(), n).delete()
        return "🗑️ Removed map **$n**."
    }

    /** Engine thread. A message if [map] has no room for this many civs, else null. */
    private fun mapFitProblemE(name: String, map: TileMap, rs: Ruleset, majors: Int, cityStates: Int): String? {
        val land = usableLand(map, rs)
        val need = (majors + cityStates) * 3
        if (land >= need) return null
        return "Map **$name** has only $land usable land tiles — too small for $majors civilizations + $cityStates city-states " +
            "(needs about $need). Lower the AI opponents / city-states or pick a bigger map."
    }

    // ================================================================== scenarios

    private fun findScenario(name: String): ScenarioInfo? {
        val q = name.trim()
        return scenarioList.firstOrNull { it.key.equals(q, true) } ?: scenarioList.filter { it.fileName.equals(q, true) }.singleOrNull()
    }

    /** Engine thread. Loads a scenario like Unciv's NewGameScreen does (setTransients happens inside loadGameFromFile). */
    private fun loadScenarioE(sc: ScenarioInfo): GameInfo = try {
        UncivGame.Current.files.loadGameFromFile(sc.file)
    } catch (e: Exception) {
        throw PlayerError("Couldn't load scenario **${sc.key}**: ${e.message ?: e.javaClass.simpleName}")
    }

    private fun playableCivs(game: GameInfo) = game.civilizations.filter { it.isMajorCiv && it.isAlive() && !it.isSpectator && !it.isBarbarian }

    /** Engine thread. */
    override fun createGame(meta: GameMeta): GameInfo? {
        val key = meta.settings.extra[K_SCENARIO]?.takeIf { !isDefaultWord(it) } ?: return null
        val sc = findScenario(key) ?: throw PlayerError("Scenario **$key** isn't installed anymore — pick another with `/civ lobby scenario:`.")
        val game = loadScenarioE(sc)
        // Remove the Spectator (scenario builders play as one) and make everyone AI, as NewGameScreen does
        game.civilizations.removeIf { it.civID == Constants.spectator }
        for (civ in game.civilizations) {
            civ.playerType = PlayerType.AI
            civ.playerId = ""
            civ.diplomacy.remove(Constants.spectator)
            civ.popupAlerts.removeIf { it.type == AlertType.FirstContact && it.value == Constants.spectator }
        }
        val pool = playableCivs(game).shuffled().toMutableList()
        if (meta.seats.size > pool.size)
            throw PlayerError("Scenario **${sc.fileName}** has only ${pool.size} playable civilizations, but ${meta.seats.size} players joined.")
        // seats that picked a nation the scenario has get it, everyone else gets a free one
        val unassigned = ArrayList<Seat>()
        for (seat in meta.seats) {
            val wanted = pool.firstOrNull { seat.nation != Constants.random && (it.civID.equals(seat.nation, true) || it.civName.equals(seat.nation, true)) }
            if (wanted == null) { unassigned += seat; continue }
            pool.remove(wanted)
            wanted.playerType = PlayerType.Human; wanted.playerId = "discord:${seat.userId}"
        }
        for (seat in unassigned) {
            val civ = pool.removeAt(0)
            civ.playerType = PlayerType.Human; civ.playerId = "discord:${seat.userId}"
        }
        game.gameId = UUID.randomUUID().toString()
        game.gameParameters.isOnlineMultiplayer = false
        game.gameParameters.players = ArrayList(game.civilizations.filter { it.isMajorCiv }.map { Player(it.civID, it.playerType, it.playerId) })
        UncivGame.Current.gameInfo = game
        // The turn must start with a human: let AIs ahead in the rotation play, or skip a removed spectator
        val cur = game.civilizations.firstOrNull { it.civID == game.currentPlayer }
        if (cur == null || !cur.isHuman()) {
            val ok = cur != null && cur.isAlive() && runCatching { game.nextTurn() }.onFailure { log.warn("scenario AI pre-turn failed", it) }.isSuccess
            if (!ok || !game.currentPlayerCiv.isHuman()) {
                val first = game.civilizations.first { it.isHuman() }
                game.currentPlayer = first.civID
                game.currentPlayerCiv = first
            }
        }
        return game
    }

    // ================================================================== lobby choices

    /** Unciv's "Error:" lines of a mod check, without the severity prefix noise. */
    private fun errorLines(list: com.unciv.models.ruleset.validation.RulesetErrorList) =
        list.getErrorText().lines().filter { it.startsWith("Error") }

    /** Engine thread. Error lines Unciv reports for this combination (empty = none); throws if it can't even be loaded. */
    fun modErrorsE(base: String?, mods: Collection<String>): List<String> {
        val (rs, errors) = RulesetCache.checkCombinedModLinks(LinkedHashSet(mods), base ?: DEFAULT_BASE)
        if (rs == null) throw PlayerError("These mods can't be loaded together at all (accepting errors can't fix that):\n" + errors.getErrorText().take(600))
        return if (errors.isError()) errorLines(errors) else emptyList()
    }

    /** Engine thread. Throws PlayerError for impossible combos (unless [acceptErrors] and they're only Unciv's mod-check errors), returns warnings. */
    fun checkRulesE(base: String?, mods: Collection<String>, acceptErrors: Boolean = false): List<String> {
        val baseName = base ?: DEFAULT_BASE
        val baseRs = RulesetCache[baseName] ?: throw PlayerError("Base ruleset **$baseName** isn't installed.")
        if (!baseRs.modOptions.isBaseRuleset) throw PlayerError("**$baseName** isn't a base ruleset — add it with `add_mod:` instead.")
        val ext = mods.map { m ->
            val rs = RulesetCache[m] ?: throw PlayerError("Mod **$m** isn't installed (see `/civ mod action:list`).")
            if (rs.modOptions.isBaseRuleset) throw PlayerError("**$m** is a base ruleset — choose it with `base:` instead.")
            rs
        }
        for (rs in ext) {
            if (!ModCompatibility.meetsBaseRequirements(rs, baseRs)) throw PlayerError("Mod **${rs.name}** can't be used with the base ruleset **$baseName**.")
            if (!ModCompatibility.meetsAllRequirements(rs, baseRs, ext))
                throw PlayerError("Mod **${rs.name}** is incompatible with the other selected mods (or needs a mod that isn't selected).")
        }
        val (loaded, errors) = RulesetCache.checkCombinedModLinks(LinkedHashSet(mods), baseName)
        if (loaded == null) throw PlayerError("These mods can't be loaded together at all (accepting errors can't fix that):\n" + errors.getErrorText().take(600))
        if (errors.isError()) {
            val lines = errorLines(errors)
            val shown = lines.take(6).joinToString("\n").take(900) + if (lines.size > 6) "\n… +${lines.size - 6} more" else ""
            if (!acceptErrors) throw PlayerError("Unciv says this mod combination is broken (${lines.size} error(s)):\n$shown\n" +
                "To play anyway, as Unciv's *Accept* button allows, repeat the command with `accept_mod_errors:True` — the game may crash or misbehave.")
            return listOf("🚨 **You accepted ${lines.size} known mod error(s)** — Unciv says the game may crash or behave oddly:\n$shown")
        }
        return if (errors.isWarnUser()) listOf("⚠️ Unciv reports warnings for this combination — it should play, but don't expect everything to be perfect.") else emptyList()
    }

    class LobbyChange(val addMod: String? = null, val removeMod: String? = null, val base: String? = null,
                      val map: String? = null, val scenario: String? = null, val clearMods: Boolean = false,
                      val acceptErrors: Boolean? = null, val allowExport: Boolean? = null) {
        val empty get() = addMod == null && removeMod == null && base == null && map == null && scenario == null && !clearMods &&
            acceptErrors == null && allowExport == null
    }

    /** Validates and applies a lobby change. NOT on the engine thread. */
    fun updateLobby(meta: GameMeta, ch: LobbyChange): String = synchronized(meta) { Engine.run { updateLobbyE(meta, ch) } }

    private fun updateLobbyE(meta: GameMeta, ch: LobbyChange): String {
        if (meta.status != Status.LOBBY) throw PlayerError("The game has already started.")
        val st = meta.settings
        val mods = LinkedHashSet(st.mods)
        var base = st.extra[K_BASE]?.takeIf { !isDefaultWord(it) }
        var mapName = st.extra[K_MAP]?.takeIf { !isDefaultWord(it) }
        var scenario = st.extra[K_SCENARIO]?.takeIf { !isDefaultWord(it) }
        val accept = ch.acceptErrors ?: (st.extra[K_ACCEPT_ERRORS] == "true")
        val notes = ArrayList<String>()
        if (ch.clearMods) { mods.clear(); base = null }
        if (ch.addMod != null) {
            val rs = RulesetCache.values.firstOrNull { it.name.equals(ch.addMod.trim(), true) } ?: throw PlayerError("No installed mod called **${ch.addMod.trim()}**.")
            if (rs.modOptions.isBaseRuleset) { base = rs.name; notes += "**${rs.name}** is a base ruleset, so it replaces the base rules." }
            else mods += rs.name
        }
        if (ch.removeMod != null) mods.remove(mods.firstOrNull { it.equals(ch.removeMod.trim(), true) } ?: throw PlayerError("**${ch.removeMod.trim()}** isn't selected."))
        if (ch.base != null) {
            val bases = RulesetCache.getSortedBaseRulesets()
            base = if (isDefaultWord(ch.base)) null else (bases.firstOrNull { it.equals(ch.base.trim(), true) }
                ?: throw PlayerError("No base ruleset called **${ch.base.trim()}**. Choices: ${bases.joinToString()}"))
        }
        if (ch.map != null) mapName = if (isDefaultWord(ch.map)) null else mapHandle(ch.map).name()
        if (ch.scenario != null) scenario = if (isDefaultWord(ch.scenario)) null else (findScenario(ch.scenario) ?: throw PlayerError("No scenario called **${ch.scenario.trim()}**.")).key

        val majors = meta.seats.size + st.aiCivs
        if (mapName != null) {
            val map = MapSaver.loadMap(mapHandle(mapName))
            val p = map.mapParameters
            val missing = (p.mods + p.baseRuleset).filter { !RulesetCache.containsKey(it) }
            if (missing.isNotEmpty()) throw PlayerError("Map **$mapName** needs mod(s) ${missing.joinToString { "**$it**" }} that aren't installed.")
            val (mapBases, mapExt) = p.mods.partition { RulesetCache[it]?.modOptions?.isBaseRuleset == true }
            val mapBase = mapBases.firstOrNull() ?: p.baseRuleset
            if (base == null && mapBase != DEFAULT_BASE) { base = mapBase; notes += "Map **$mapName** uses base rules **$mapBase** — selected them." }
            val added = mapExt.filter { it !in mods }
            if (added.isNotEmpty()) { mods += added; notes += "Map **$mapName** needs ${added.joinToString { "**$it**" }} — added." }
            notes += checkRulesE(base, mods, accept)
            val rs = RulesetCache.getComplexRuleset(mods, base ?: DEFAULT_BASE)
            val inc = map.getRulesetIncompatibility(rs)
            if (inc.isNotEmpty()) throw PlayerError("Map **$mapName** doesn't work with these rules: ${inc.take(4).joinToString("; ")}")
            mapFitProblemE(mapName, map, rs, majors, st.cityStates)?.let { throw PlayerError(it) }
        } else notes += checkRulesE(base, mods, accept)
        if (scenario != null) {
            val sc = findScenario(scenario) ?: throw PlayerError("Scenario **$scenario** isn't installed anymore.")
            val game = loadScenarioE(sc)
            val playable = playableCivs(game)
            if (meta.seats.size > playable.size)
                throw PlayerError("Scenario **${sc.fileName}** has only ${playable.size} playable civilizations, but ${meta.seats.size} players joined.")
            notes += "📜 The scenario brings its own map, civilizations, AI and rules (${(listOf(game.gameParameters.baseRuleset) + game.gameParameters.mods).joinToString(" + ")}) — " +
                "map/mod/AI settings are ignored. Playable civs: ${playable.joinToString { it.civID }}."
        }

        val errorCount = if (accept) modErrorsE(base, mods).size else 0
        if (ch.acceptErrors == true && errorCount == 0) notes += "ℹ️ `accept_mod_errors` is on, but Unciv reports no errors for the current mods."
        st.mods = ArrayList(mods)
        fun put(k: String, v: String?) { if (v == null) st.extra.remove(k) else st.extra[k] = v }
        put(K_BASE, base); put(K_MAP, mapName); put(K_SCENARIO, scenario)
        put(K_ACCEPT_ERRORS, if (accept) "true" else null); put(K_MOD_ERRORS, errorCount.takeIf { it > 0 }?.toString())
        ch.allowExport?.let { SetupPlus.setAllowExport(meta, it) }
        // difficulty / speed / era / victories / nations the new rules don't have go back to defaults
        notes += SetupPlus.normalizeE(meta)
        return (listOf("✅ Lobby updated.") + lobbyLines(meta).ifEmpty { listOf("Standard rules on a generated map.") } +
            SetupPlus.lobbyLines(meta).filter { it.startsWith("💾") } + notes).joinToString("\n")
    }

    override fun lobbyLines(meta: GameMeta): List<String> {
        val st = meta.settings
        val out = ArrayList<String>()
        val scenario = st.extra[K_SCENARIO]?.takeIf { !isDefaultWord(it) }
        if (scenario != null) out += "📜 Scenario: **${scenario.substringAfter('/')}** (from ${scenario.substringBefore('/')}) — map, civs and rules come from it"
        val base = st.extra[K_BASE]?.takeIf { !isDefaultWord(it) }
        if (scenario == null && (base != null || st.mods.isNotEmpty()))
            out += "🧩 Rules: ${base ?: "Gods & Kings"}" + (if (st.mods.isNotEmpty()) " + ${st.mods.joinToString()}" else "")
        st.extra[K_MAP]?.takeIf { !isDefaultWord(it) && scenario == null }?.let { out += "🗺️ Custom map: **$it** (map size/type ignored)" }
        st.extra[K_MOD_ERRORS]?.takeIf { st.extra[K_ACCEPT_ERRORS] == "true" && scenario == null }?.let {
            out += "🚨 **Mod errors accepted by the host** ($it error(s) Unciv reports) — the game may crash or misbehave"
        }
        return out
    }

    /** Engine thread. */
    override fun applyToSetup(setup: GameSetupInfo, meta: GameMeta) {
        val st = meta.settings
        val gp = setup.gameParameters
        var base = st.extra[K_BASE]?.takeIf { !isDefaultWord(it) }
        val mods = LinkedHashSet(st.mods.filter { it.isNotBlank() })
        val mapName = st.extra[K_MAP]?.takeIf { !isDefaultWord(it) }
        var map: TileMap? = null
        if (mapName != null) {
            val file = mapHandle(mapName)
            val m = MapSaver.loadMap(file)
            map = m
            val (mapBases, mapExt) = m.mapParameters.mods.partition { RulesetCache[it]?.modOptions?.isBaseRuleset == true }
            if (base == null) base = (mapBases.firstOrNull() ?: m.mapParameters.baseRuleset).takeIf { it != DEFAULT_BASE }
            mods += mapExt
            // exactly like OjhBenchmark / MapFileSelectTable: GameStarter loads setup.mapFile when mapParameters.name is set
            setup.mapFile = file
            setup.mapParameters.type = MapGeneratedMainType.custom
            setup.mapParameters.name = file.name()
        }
        if (mods.isNotEmpty() || base != null) {
            val missing = (mods + listOfNotNull(base)).filter { !RulesetCache.containsKey(it) }
            if (missing.isNotEmpty()) throw PlayerError("Mod(s) ${missing.joinToString { "**$it**" }} aren't installed anymore — change them with `/civ lobby`.")
            gp.mods = mods
            if (base != null) gp.baseRuleset = base
            if (map == null) { setup.mapParameters.mods = LinkedHashSet(mods); setup.mapParameters.baseRuleset = gp.baseRuleset }
            val rs = RulesetCache.getComplexRuleset(gp)
            // GameOps filled these from the vanilla ruleset; make them valid for the modded one
            // like NewGameScreen: refuse Unciv's mod-check errors unless the host accepted them (they're recorded in the game)
            val (_, check) = RulesetCache.checkCombinedModLinks(LinkedHashSet(mods), gp.baseRuleset)
            if (check.isError()) {
                if (st.extra[K_ACCEPT_ERRORS] != "true") throw PlayerError("Unciv reports errors for these mods (were they updated?):\n" +
                    errorLines(check).take(4).joinToString("\n").take(700) + "\nThe host can start anyway with `/civ lobby accept_mod_errors:True`.")
                gp.acceptedModCheckErrors = check.getErrorText()
            }
            gp.victoryTypes = ArrayList(st.victoryTypes.filter { it in rs.victories }.ifEmpty { rs.selectableVictories().map { it.name } })
            if (gp.speed !in rs.speeds) gp.speed = listOf("Quick", "Standard").firstOrNull { it in rs.speeds } ?: rs.speeds.keys.firstOrNull() ?: gp.speed
            gp.startingEra = st.startEra.takeIf { it in rs.eras } ?: gp.startingEra.takeIf { it in rs.eras } ?: rs.eras.keys.firstOrNull() ?: gp.startingEra
            if (gp.difficulty !in rs.difficulties) gp.difficulty = rs.difficulties.keys.elementAtOrNull(rs.difficulties.size / 2) ?: gp.difficulty
            val pickable = rs.nations.values.filter { it.isMajorCiv && !it.hasUnique(UniqueType.WillNotBeChosenForNewGames) }
            for (p in gp.players)
                if (p.chosenCiv != Constants.random && p.chosenCiv != Constants.spectator && rs.nations[p.chosenCiv]?.isMajorCiv != true) p.chosenCiv = Constants.random
            if (gp.players.size > pickable.size) throw PlayerError("These rules only have ${pickable.size} nations, but the game needs ${gp.players.size} (players + AI).")
        }
        if (map != null && mapName != null)
            mapFitProblemE(mapName, map, RulesetCache.getComplexRuleset(gp), gp.players.size, gp.numberOfCityStates)?.let { throw PlayerError(it) }
    }

    override fun onEngineInit() {
        val errors = reloadE()
        log.info("ModsPlus: {} mod(s) installed in {}, {} map(s), {} scenario(s){}", installedMods.size, modsDir(), mapNames().size, scenarioList.size,
            if (errors.isEmpty()) "" else " — load errors: " + errors.take(5).joinToString(" | "))
    }

    // ================================================================== Discord commands

    override fun commands(): List<SubcommandData> = listOf(
        SubcommandData("mod", "Unciv mods: list, browse GitHub, install from GitHub, remove").addOptions(
            OptionData(OptionType.STRING, "action", "What to do", true)
                .addChoice("list installed mods", "list").addChoice("browse Unciv mods on GitHub", "browse")
                .addChoice("install from GitHub (Manage Server)", "install").addChoice("remove (Manage Server)", "remove"),
            OptionData(OptionType.STRING, "url", "install: https://github.com/<owner>/<repo>"),
            OptionData(OptionType.STRING, "name", "remove: the mod's name").setAutoComplete(true),
            OptionData(OptionType.STRING, "query", "browse: words to search for (empty = most-starred mods)"),
        ),
        SubcommandData("map", "Custom maps made in Unciv's map editor: list, upload, remove").addOptions(
            OptionData(OptionType.STRING, "action", "What to do", true)
                .addChoice("list maps", "list").addChoice("upload a map file (Manage Server)", "upload").addChoice("remove (Manage Server)", "remove"),
            OptionData(OptionType.ATTACHMENT, "file", "upload: the map file from Unciv's maps folder (max 10 MB)"),
            OptionData(OptionType.STRING, "name", "upload: name to save as · remove: map to delete").setAutoComplete(true),
        ),
        SubcommandData("lobby", "Host: pick mods, base rules, a custom map or a scenario (no options = show)").addOptions(
            OptionData(OptionType.STRING, "add_mod", "Add an installed mod").setAutoComplete(true),
            OptionData(OptionType.STRING, "remove_mod", "Remove a selected mod").setAutoComplete(true),
            OptionData(OptionType.STRING, "base", "Base ruleset ('default' = Gods & Kings)").setAutoComplete(true),
            OptionData(OptionType.STRING, "map", "Custom map name, or 'generated'").setAutoComplete(true),
            OptionData(OptionType.STRING, "scenario", "Scenario from an installed mod, or 'none'").setAutoComplete(true),
            OptionData(OptionType.BOOLEAN, "clear_mods", "Remove all selected mods and the base ruleset"),
            OptionData(OptionType.BOOLEAN, "accept_mod_errors", "Start even though Unciv reports errors in these mods (may crash)"),
            OptionData(OptionType.BOOLEAN, "allow_export", "Let the host export the save mid-game (it shows the whole map)"),
        ),
    )

    override fun onCommand(event: SlashCommandInteractionEvent, bot: BotApi): Boolean {
        when (event.subcommandName) {
            "mod" -> modCommand(event, bot)
            "map" -> mapCommand(event, bot)
            "lobby" -> lobbyCommand(event, bot)
            else -> return false
        }
        ensureAutocomplete(event.jda, bot)
        return true
    }

    private fun canManage(event: SlashCommandInteractionEvent) = event.member?.hasPermission(Permission.MANAGE_SERVER) == true

    private fun clip(s: String) = if (s.length <= 1950) s else s.take(1940) + "\n…"

    /** Runs [block] on a worker and puts its text (or the error) into the deferred reply. */
    private fun work(hook: InteractionHook, bot: BotApi, block: () -> String) = bot.async {
        val msg = try { block() } catch (e: PlayerError) { "❌ ${e.message}" } catch (e: Exception) {
            log.error("ModsPlus command failed", e); "💥 Something went wrong: ${e.message}"
        }
        hook.editOriginal(clip(msg)).queue()
    }

    private fun modListText(): String {
        val mods = installedMods
        if (mods.isEmpty()) return "No mods installed. A server manager can add one with `/civ mod action:install url:https://github.com/<owner>/<repo>`."
        return "🧩 **Installed mods** (${mods.size}):\n" + mods.joinToString("\n") { m ->
            "• **${m.name}** — ${if (m.isBase) "base ruleset" else "extension"} · ${m.summary}" +
                (if (m.scenarios.isNotEmpty()) " · 📜 ${m.scenarios.size} scenario(s)" else "") + (m.source?.let { " · <$it>" } ?: "")
        } + "\nHosts pick mods with `/civ lobby add_mod:` / `base:`, scenarios with `/civ lobby scenario:`."
    }

    private fun modCommand(event: SlashCommandInteractionEvent, bot: BotApi) {
        when (event.getOption("action")?.asString) {
            "install" -> {
                if (!canManage(event)) return event.reply("Only members with **Manage Server** can install mods.").setEphemeral(true).queue()
                val url = event.getOption("url")?.asString ?: return event.reply("Add `url:https://github.com/<owner>/<repo>`.").setEphemeral(true).queue()
                try { parseGithubUrl(url) } catch (e: PlayerError) { return event.reply("❌ ${e.message}").setEphemeral(true).queue() }
                event.deferReply().queue { hook ->
                    work(hook, bot) {
                        val r = installFromGithub(url)
                        "🧩 ${if (r.replaced) "Updated" else "Installed"} **${r.name}** — " +
                            (if (r.isBase) "**base ruleset** (replaces the standard rules: `/civ lobby base:${r.name}`)" else "**extension** (adds to the rules: `/civ lobby add_mod:${r.name}`)") +
                            "\nContents: ${r.summary}" + (if (r.scenarios.isNotEmpty()) "\n📜 Scenarios: ${r.scenarios.joinToString()} (`/civ lobby scenario:`)" else "") +
                            (r.problems?.let { "\n⚠️ $it" } ?: "")
                    }
                }
            }
            "remove" -> {
                if (!canManage(event)) return event.reply("Only members with **Manage Server** can remove mods.").setEphemeral(true).queue()
                val name = event.getOption("name")?.asString ?: return event.reply("Add `name:<mod>`.").setEphemeral(true).queue()
                event.deferReply().queue { hook -> work(hook, bot) { removeMod(name, bot.store) } }
            }
            "browse" -> {
                val q = event.getOption("query")?.asString.orEmpty()
                event.deferReply(true).queue { hook -> work(hook, bot) { browseText(q) } }
            }
            else -> event.reply(clip(modListText())).setEphemeral(true).queue()
        }
    }

    // ---- GitHub mod catalogue (Unciv's mod manager: repositories with the "unciv-mod" topic, sorted by stars)

    class ModSearchHit(val fullName: String, val url: String, val description: String, val stars: Int, val updated: String)

    private val searchHttp: HttpClient by lazy {
        HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).connectTimeout(Duration.ofSeconds(6)).build()
    }

    /** The GitHub search query Unciv uses (GithubAPI.fetchGithubReposWithTopic): "<search> topic:unciv-mod fork:true". */
    fun searchQuery(text: String): String {
        val q = text.replace(Regex("[\\r\\n\\t]"), " ").trim().take(100)
        return (if (q.isEmpty()) "" else "$q ") + "topic:unciv-mod fork:true"
    }

    fun parseModSearch(body: String): Pair<Int, List<ModSearchHit>> {
        val root = JsonReader().parse(body)
        val items = root.get("items") ?: return 0 to emptyList()
        val out = ArrayList<ModSearchHit>()
        var node = items.child
        while (node != null) {
            val name = node.getString("full_name", null)
            val url = node.getString("html_url", null)
            if (name != null && url != null && url.startsWith("https://github.com/"))
                out += ModSearchHit(name, url, node.get("description")?.takeIf { d -> d.isString }?.asString().orEmpty(), node.getInt("stargazers_count", 0),
                    node.getString("pushed_at", "").take(10))
            node = node.next
        }
        return root.getInt("total_count", out.size) to out
    }

    /** NOT on the engine thread (network, 10 s). Throws PlayerError when GitHub can't be asked. */
    fun searchGithubMods(text: String, perPage: Int = 10): Pair<Int, List<ModSearchHit>> {
        val url = "https://api.github.com/search/repositories?q=" + java.net.URLEncoder.encode(searchQuery(text), Charsets.UTF_8) +
            "&sort=stars&order=desc&per_page=$perPage"
        val resp = try {
            searchHttp.send(HttpRequest.newBuilder(URI(url)).timeout(Duration.ofSeconds(10)).header("User-Agent", "CivBot-Unciv-mod-browser")
                .header("Accept", "application/vnd.github+json").GET().build(), HttpResponse.BodyHandlers.ofString())
        } catch (e: Exception) {
            throw PlayerError("Couldn't reach GitHub (${e.javaClass.simpleName}${e.message?.let { ": ${it.take(80)}" } ?: ""}). Try again later, or browse <https://github.com/topics/unciv-mod>.")
        }
        return when (resp.statusCode()) {
            200 -> parseModSearch(resp.body())
            403, 429 -> throw PlayerError("GitHub's search limit was reached — try again in a minute, or browse <https://github.com/topics/unciv-mod>.")
            422 -> throw PlayerError("GitHub didn't accept that search — try simpler words.")
            else -> throw PlayerError("GitHub answered HTTP ${resp.statusCode()} — try again later.")
        }
    }

    fun browseText(query: String, search: (String) -> Pair<Int, List<ModSearchHit>> = { searchGithubMods(it) }): String {
        val (total, hits) = try { search(query) } catch (e: PlayerError) { return "❌ ${e.message}" }
        val what = if (query.isBlank()) "the most-starred Unciv mods" else "Unciv mods matching **${query.trim().take(100)}**"
        if (hits.isEmpty()) return "🔎 No GitHub repositories with the `unciv-mod` topic match **${query.trim().take(100)}**. Try other words, or browse <https://github.com/topics/unciv-mod>."
        val installed = installedMods.mapNotNull { it.source?.lowercase() }.toSet()
        val sb = StringBuilder("🔎 $what on GitHub ($total found, top ${hits.size} by ⭐):\n")
        for (h in hits) {
            val line = "• **${h.fullName}** ⭐${h.stars}" + (if (h.updated.isNotEmpty()) " · updated ${h.updated}" else "") +
                (if (h.url.lowercase() in installed) " · ✅ installed" else "") +
                (if (h.description.isNotBlank()) "\n  ${h.description.replace("\n", " ").take(140)}" else "") +
                "\n  `/civ mod action:install url:${h.url}`\n"
            if (sb.length + line.length > 1800) break
            sb.append(line)
        }
        sb.append("Installing needs **Manage Server**. Mods are code-free JSON rule sets, but only install ones you trust.")
        return sb.toString()
    }

    private fun mapCommand(event: SlashCommandInteractionEvent, bot: BotApi) {
        when (event.getOption("action")?.asString) {
            "upload" -> {
                if (!canManage(event)) return event.reply("Only members with **Manage Server** can upload maps.").setEphemeral(true).queue()
                val att = event.getOption("file")?.asAttachment
                    ?: return event.reply("Attach the map with `file:` — save it in Unciv's **Map editor**, then pick it from Unciv's `maps` folder.").setEphemeral(true).queue()
                if (att.size > MAX_MAP_BYTES) return event.reply("❌ Map files can be at most ${MAX_MAP_BYTES / 1048576} MB.").setEphemeral(true).queue()
                val name = event.getOption("name")?.asString ?: att.fileName
                event.deferReply().queue { hook ->
                    work(hook, bot) {
                        val bytes = att.proxy.download().get(60, TimeUnit.SECONDS).use { inp ->
                            val bos = java.io.ByteArrayOutputStream()
                            copyCapped(inp, bos, MAX_MAP_BYTES, "Map files can be at most ${MAX_MAP_BYTES / 1048576} MB.")
                            bos.toByteArray()
                        }
                        saveMap(bytes, name)
                    }
                }
            }
            "remove" -> {
                if (!canManage(event)) return event.reply("Only members with **Manage Server** can remove maps.").setEphemeral(true).queue()
                val name = event.getOption("name")?.asString ?: return event.reply("Add `name:<map>`.").setEphemeral(true).queue()
                try { event.reply(removeMap(name, bot.store)).queue() } catch (e: PlayerError) { event.reply("❌ ${e.message}").setEphemeral(true).queue() }
            }
            else -> event.deferReply(true).queue { hook ->
                work(hook, bot) {
                    listMaps() + "\n\nThere's no map editor in Discord: make maps in Unciv's **Map editor** (desktop/Android), " +
                        "then a server manager uploads them with `/civ map action:upload file:`."
                }
            }
        }
    }

    private fun lobbyHere(store: Store, channelId: String, userId: String): GameMeta? {
        val lobbies = store.sessions.values.map { it.meta }.filter { it.status == Status.LOBBY && (it.channelId == channelId || it.threadId == channelId) }
        return lobbies.firstOrNull { it.hostId == userId } ?: lobbies.firstOrNull { it.seatOf(userId) != null } ?: lobbies.firstOrNull()
    }

    private fun lobbyCommand(event: SlashCommandInteractionEvent, bot: BotApi) {
        val meta = lobbyHere(bot.store, event.channel.id, event.user.id)
            ?: return event.reply("There's no game lobby in this channel. Open one with `/civ new`.").setEphemeral(true).queue()
        val ch = LobbyChange(
            addMod = event.getOption("add_mod")?.asString, removeMod = event.getOption("remove_mod")?.asString,
            base = event.getOption("base")?.asString, map = event.getOption("map")?.asString,
            scenario = event.getOption("scenario")?.asString, clearMods = event.getOption("clear_mods")?.asBoolean == true,
            acceptErrors = event.getOption("accept_mod_errors")?.asBoolean, allowExport = event.getOption("allow_export")?.asBoolean)
        if (ch.empty) {
            val cur = lobbyLines(meta).ifEmpty { listOf("Standard Gods & Kings rules on a generated map.") }
            val avail = "**Mods:** ${installedMods.filter { !it.isBase }.joinToString { it.name }.ifEmpty { "none installed" }}\n" +
                "**Base rulesets:** ${baseRulesets.joinToString()}\n**Maps:** ${mapNames().joinToString().ifEmpty { "none uploaded" }}\n" +
                "**Scenarios:** ${scenarioList.joinToString { it.key }.ifEmpty { "none (they come inside mods)" }}"
            return event.reply(clip("⚙️ **${meta.name}**\n${cur.joinToString("\n")}\n\n$avail\n\nThe host changes these with `/civ lobby add_mod: remove_mod: base: map: scenario:` " +
                "(`accept_mod_errors:True` to play a combination Unciv reports errors for; `allow_export:True` lets the host export mid-game — " +
                "currently ${if (meta.settings.extra[SetupPlus.K_ALLOW_EXPORT] == "true") "on" else "off"})."))
                .setEphemeral(true).queue()
        }
        if (meta.hostId != event.user.id) return event.reply("Only the host (<@${meta.hostId}>) can change the game's mods, map or scenario.").setEphemeral(true).queue()
        event.deferReply(true).queue { hook ->
            work(hook, bot) { updateLobby(meta, ch).also { bot.refreshLobby(meta) } }
        }
    }

    // ---- autocomplete (Bot.kt doesn't route autocomplete to extensions, so we add our own listener once)
    private val autocompleteAdded = AtomicBoolean(false)
    @Volatile private var botApi: BotApi? = null

    private fun ensureAutocomplete(jda: JDA, bot: BotApi) {
        botApi = bot
        if (!autocompleteAdded.compareAndSet(false, true)) return
        jda.addEventListener(object : ListenerAdapter() {
            override fun onCommandAutoCompleteInteraction(event: CommandAutoCompleteInteractionEvent) {
                runCatching { autocomplete(event) }.onFailure { log.warn("autocomplete failed", it) }
            }
        })
    }

    private fun autocomplete(event: CommandAutoCompleteInteractionEvent) {
        if (event.name != "civ") return
        val f = event.focusedOption
        val options: List<String> = when (event.subcommandName to f.name) {
            "mod" to "name" -> installedMods.map { it.name }
            "map" to "name" -> mapNames()
            "lobby" to "add_mod" -> installedMods.map { it.name }
            "lobby" to "remove_mod" -> botApi?.let { lobbyHere(it.store, event.channelId ?: "", event.user.id)?.settings?.mods } ?: installedMods.map { it.name }
            "lobby" to "base" -> listOf("default") + baseRulesets
            "lobby" to "map" -> listOf("generated") + mapNames()
            "lobby" to "scenario" -> listOf("none") + scenarioList.map { it.key }
            else -> return
        }
        val q = f.value.trim()
        event.replyChoiceStrings(options.filter { q.isEmpty() || it.contains(q, true) }.distinct().take(25).map { it.take(100) }).queue()
    }

    override fun handle(verb: String, ctx: ActionCtx): Boolean {
        if (verb != VERB_SELFTEST || !selfTesting) return false
        ctx.flash += "✅ ${ctx.value}"
        return true
    }

    // ================================================================== self-test

    @Volatile private var selfTesting = false

    private fun zipOf(target: File, entries: Map<String, String>) {
        target.parentFile.mkdirs()
        ZipOutputStream(target.outputStream()).use { z -> for ((n, text) in entries) { z.putNextEntry(ZipEntry(n)); z.write(text.toByteArray()); z.closeEntry() } }
    }

    override fun selfTest(t: SelfTestCtx) {
        selfTesting = true
        val failures = ArrayList<String>()
        val home = File(System.getProperty("civbot.home") ?: ".")
        val work = File(home, "selftest-out/modsplus").apply { deleteRecursively(); mkdirs() }
        val testMod = "CivBot Test Mod"; val rivalMod = "CivBot Test Rival"; val testMap = "CivBot Test Map"
        val listsMod = "CivBot Lists Mod"; val brokenMod = "CivBot Broken Mod"
        var githubMod: String? = null
        fun ok(label: String) { t.act(t.alice, VERB_SELFTEST, emptyList(), label, label) }
        fun step(label: String, block: () -> Unit) {
            try { block(); ok(label) } catch (e: Throwable) { failures += "$label: ${e.javaClass.simpleName}: ${e.message}"; e.printStackTrace() }
        }
        fun expectError(label: String, mustContain: String = "", block: () -> Unit) = step(label) {
            val err = try { block(); null } catch (e: PlayerError) { e.message ?: "?" }
            check(err != null) { "expected a PlayerError" }
            check(err.contains(mustContain, true)) { "wrong error: $err" }
            println("  [ModsPlus] $label -> ${err.lines().first().take(160)}")
        }
        fun fakeMeta(id: String, ai: Int = 1, cs: Int = 1) = GameMeta(id = id, name = "ModsPlus $id", hostId = "901").apply {
            seats += Seat("901", "Ann", "Greece"); seats += Seat("902", "Ben", "Random")
            settings.aiCivs = ai; settings.cityStates = cs; settings.mapSize = "Tiny"
        }
        try {
            // --- test mods: one with a building + a scenario (a real Unciv save), one that declares itself incompatible
            val scenarioText = Engine.run {
                val setup = GameSetupInfo()
                setup.gameParameters.players = arrayListOf(Player("Rome", PlayerType.Human), Player("Greece", PlayerType.AI), Player("Egypt", PlayerType.AI))
                setup.gameParameters.numberOfCityStates = 0
                setup.mapParameters.mapSize = com.unciv.logic.map.MapSize.Tiny
                Engine.serialize(com.unciv.logic.GameStarter.startNewGame(setup))
            }
            val modZip = File(work, "test-mod.zip")
            zipOf(modZip, mapOf(
                "CivBot-Test-Mod-main/jsons/ModOptions.json" to """{"uniques":[]}""",
                "CivBot-Test-Mod-main/jsons/Buildings.json" to """[{"name":"CivBot Test Hall","cost":40,"culture":1,"requiredTech":"Pottery"}]""",
                "CivBot-Test-Mod-main/scenarios/Three Empires" to scenarioText,
                "CivBot-Test-Mod-main/README.md" to "test"))
            val rivalZip = File(work, "rival.zip")
            zipOf(rivalZip, mapOf("jsons/ModOptions.json" to """{"uniques":["Mod is incompatible with [$testMod]"]}"""))
            val evilZip = File(work, "evil.zip")
            zipOf(evilZip, mapOf("Evil-main/jsons/ModOptions.json" to "{}", "Evil-main/../../escaped.txt" to "pwned"))

            step("mods: install from zip (local archive)") {
                val r = installFromZip(modZip, "CivBot-Test-Mod".repoNameToFolderName(), "local test")
                check(r.name == testMod && !r.isBase) { "got ${r.name} base=${r.isBase}" }
                check(r.scenarios.contains("Three Empires")) { "scenario not found in mod" }
                println("  [ModsPlus] installed ${r.name}: ${r.summary}; problems=${r.problems}")
                installFromZip(rivalZip, rivalMod, null)
            }
            expectError("mods: zip-slip archive rejected", "unsafe path") { installFromZip(evilZip, "Evil", null) }
            step("mods: zip-slip left nothing behind") {
                check(!File(modsDir().parentFile, "escaped.txt").exists() && !File(modsDir(), "Evil").exists() && !File(modsDir(), "escaped.txt").exists())
            }
            expectError("mods: non-GitHub URL rejected") { parseGithubUrl("https://example.com/a/b") }
            try {
                val r = installFromGithub("https://github.com/yairm210/unciv-religion-mod-example")
                githubMod = r.name
                println("  [ModsPlus] GitHub download OK: ${r.name} (${if (r.isBase) "base" else "extension"}) ${r.summary}; problems=${r.problems}")
                ok("mods: install from GitHub (real download)")
            } catch (e: Exception) { println("  [ModsPlus] GitHub download skipped/failed (network?): ${e.message}") }
            step("mods: list") {
                val txt = modListText(); check(txt.contains(testMod) && txt.contains("extension")) { txt }
                check(Engine.run { RulesetCache.containsKey(testMod) })
            }
            // --- lobby selection & validation
            val lobby = fakeMeta("mp1")
            step("lobby: add mods") {
                println("  [ModsPlus] " + updateLobby(lobby, LobbyChange(addMod = testMod)).replace("\n", " | "))
                githubMod?.let { updateLobby(lobby, LobbyChange(addMod = it)) }
                check(testMod in lobby.settings.mods)
                check(lobbyLines(lobby).any { it.contains(testMod) })
            }
            expectError("lobby: incompatible mods rejected", "incompatible") { updateLobby(lobby, LobbyChange(addMod = rivalMod)) }
            expectError("lobby: base ruleset as extension rejected") { Engine.run { checkRulesE(null, listOf("Civ V - Vanilla")) } }
            expectError("lobby: unknown mod rejected") { updateLobby(lobby, LobbyChange(addMod = "No Such Mod")) }
            step("lobby: base ruleset choice") {
                updateLobby(lobby, LobbyChange(base = "Civ V - Vanilla")); check(lobby.settings.extra[K_BASE] == "Civ V - Vanilla")
                updateLobby(lobby, LobbyChange(base = "default")); check(lobby.settings.extra[K_BASE] == null)
            }
            step("mods: game started with mods") {
                val g = t.eng { GameOps.startGame(lobby) }
                check(testMod in g.gameParameters.mods) { "mods = ${g.gameParameters.mods}" }
                check(g.ruleset.buildings.containsKey("CivBot Test Hall")) { "mod building missing" }
                githubMod?.let { check(it in g.gameParameters.mods) }
                check(lobby.seats.all { s -> g.getCivilization(s.civId).playerId == "discord:${s.userId}" })
                val back = Engine.run { Engine.deserialize(Engine.serialize(g)) }   // save/load keeps the mods
                check(back.ruleset.buildings.containsKey("CivBot Test Hall"))
            }
            // --- custom maps (generated like Unciv's map editor does, then the upload code path)
            val mapText = Engine.run {
                val rs = RulesetCache[DEFAULT_BASE]!!
                val p = com.unciv.logic.map.MapParameters().apply { mapSize = com.unciv.logic.map.MapSize.Tiny; type = com.unciv.logic.map.MapType.pangaea }
                MapSaver.mapToSavedString(com.unciv.logic.map.mapgenerator.MapGenerator(rs).generateMap(p))
            }
            step("maps: upload validated + saved") {
                println("  [ModsPlus] " + saveMap(mapText.toByteArray(), "$testMap.map").lines().first())
                check(testMap in mapNames()); check(listMaps().contains(testMap))
            }
            expectError("maps: non-map file rejected") { saveMap("hello, not a map".toByteArray(), "junk") }
            expectError("maps: too many civs for the map rejected", "too small") { updateLobby(fakeMeta("mp3", ai = 60, cs = 40), LobbyChange(map = testMap)) }
            step("maps: game started on custom map") {
                val mapLobby = fakeMeta("mp2", ai = 2, cs = 2)
                updateLobby(mapLobby, LobbyChange(map = testMap))
                check(mapLobby.settings.extra[K_MAP] == testMap)
                val tiles = Engine.run { MapSaver.mapFromSavedString(mapText).tileList.size }
                val g = t.eng { GameOps.startGame(mapLobby) }
                check(g.tileMap.tileList.size == tiles) { "tiles ${g.tileMap.tileList.size} vs $tiles" }
                check(g.civilizations.count { it.isMajorCiv } == 4) { "majors = ${g.civilizations.count { it.isMajorCiv }}" }
            }
            // --- scenarios
            step("scenarios: listed from mod") { check(scenarioList.any { it.key == "$testMod/Three Empires" }) { scenarioList.map { it.key }.toString() } }
            val scenLobby = fakeMeta("mp4")
            step("scenarios: lobby selection") {
                println("  [ModsPlus] " + updateLobby(scenLobby, LobbyChange(scenario = "Three Empires")).replace("\n", " | ").take(300))
                check(scenLobby.settings.extra[K_SCENARIO] == "$testMod/Three Empires")
            }
            expectError("scenarios: too many players rejected", "playable") {
                val crowd = fakeMeta("mp5").apply { repeat(3) { seats += Seat("91$it", "P$it") } }
                updateLobby(crowd, LobbyChange(scenario = "Three Empires"))
            }
            var scenGame: GameInfo? = null
            step("scenarios: game created from scenario") {
                val g = t.eng { GameOps.startGame(scenLobby) }
                scenGame = g
                val ann = g.getCivilization(scenLobby.seats[0].civId)
                check(ann.civID == "Greece" && ann.isHuman() && ann.playerId == "discord:901") { "Ann got ${ann.civID}" }
                val ben = g.getCivilization(scenLobby.seats[1].civId)
                check(ben.isHuman() && ben.playerId == "discord:902" && ben != ann)
                check(g.civilizations.count { it.isHuman() } == 2) { "humans: ${g.civilizations.filter { it.isHuman() }.map { it.civID }}" }
                check(g.currentPlayerCiv.isHuman())
                println("  [ModsPlus] scenario: Ann=${ann.civID}, Ben=${ben.civID}, current=${g.currentPlayer}, turn ${g.turns}")
            }
            scenGame?.let { g ->
                step("scenarios: turns played") {
                    val before = Engine.withGame(g) { it.turns }
                    repeat(4) { Engine.withGame(g) { gg -> GameOps.endTurn(gg, gg.currentPlayerCiv) } }
                    check(Engine.withGame(g) { it.turns } > before && Engine.withGame(g) { it.currentPlayerCiv.isHuman() })
                }
            }
            // --- lobby lists follow the selected mods; hidden nations; accepted mod errors; GitHub catalogue
            val listsZip = File(work, "lists.zip")
            zipOf(listsZip, mapOf(
                "jsons/ModOptions.json" to "{}",
                "jsons/Speeds.json" to """[{"name":"CivBot Blitz","modifier":0.5,"turns":[{"yearsPerTurn":50,"untilTurn":1000}]}]""",
                "jsons/Difficulties.json" to """[{"name":"CivBot Easy","baseHappiness":15,"aiDifficultyLevel":"Chieftain"}]""",
                "jsons/VictoryTypes.json" to """[{"name":"CivBot Victory","milestones":["Capture all capitals"]}]""",
                "jsons/Nations.json" to """[{"name":"CivBot Visible","leaderName":"Ada Test","uniqueName":"Test Spirit","uniqueText":"Nothing special, just for the self-test",
                    "outerColor":[10,20,30],"innerColor":[200,210,220],"cities":["Visa","Vista","Vigo"]},
                    {"name":"CivBot Hidden","leaderName":"Nobody","uniques":["Will not be chosen for new games"],"outerColor":[1,2,3],"innerColor":[4,5,6],"cities":["Hidden City"]}]"""))
            val brokenZip = File(work, "broken.zip")
            zipOf(brokenZip, mapOf("jsons/ModOptions.json" to "{}",
                "jsons/Difficulties.json" to """[{"name":"CivBot Broken","researchCostModifier":-1,"aiDifficultyLevel":"Chieftain"}]"""))
            step("lobby lists: difficulties, speeds, victories and nations come from the mods") {
                installFromZip(listsZip, listsMod, null)
                val m = fakeMeta("mp6")
                check("CivBot Blitz" !in SetupPlus.rules(m).speeds)
                updateLobby(m, LobbyChange(addMod = listsMod))
                val r = SetupPlus.rules(m)
                check("CivBot Blitz" in r.speeds && "CivBot Easy" in r.difficulties && "CivBot Victory" in r.victories) { "${r.speeds} ${r.difficulties} ${r.victories}" }
                val vis = r.nation("CivBot Visible")
                check(vis != null && vis.description.contains("Ada Test") && vis.description.contains("Test Spirit") && vis.description.length <= 100) { vis?.description.toString() }
                println("  [ModsPlus] modded lists: speeds ${r.speeds}, difficulties ${r.difficulties.size}, victories ${r.victories}; CivBot Visible = ${vis.description}")
            }
            step("lobby lists: nations marked 'Will not be chosen for new games' are hidden") {
                val m = fakeMeta("mp6").apply { settings.mods.add(listsMod) }
                check(SetupPlus.rules(m).nation("CivBot Hidden") == null && Engine.run { GameOps.majorNations(m) }.none { it == "CivBot Hidden" })
                val opts = SetupPlus.nationPickerRows(m, "902").flatMap { row -> row.components.filterIsInstance<net.dv8tion.jda.api.interactions.components.selections.StringSelectMenu>().flatMap { it.options } }
                check(opts.any { it.value == "CivBot Visible" } && opts.none { it.value == "CivBot Hidden" })
                check(SetupPlus.nationDetails(m, "CivBot Visible").description!!.contains("Nothing special"))
            }
            step("lobby lists: modded speed + difficulty + victory used by the game; reset when the mod goes") {
                val m = fakeMeta("mp7").apply { settings.speed = "CivBot Blitz"; settings.difficulty = "CivBot Easy"; settings.victoryTypes = arrayListOf("CivBot Victory", "Domination") }
                updateLobby(m, LobbyChange(addMod = listsMod))
                check(m.settings.speed == "CivBot Blitz" && m.settings.difficulty == "CivBot Easy")
                val g = t.eng { GameOps.startGame(m) }
                val gp = g.gameParameters
                check(gp.speed == "CivBot Blitz" && gp.difficulty == "CivBot Easy" && "CivBot Victory" in gp.victoryTypes) { "${gp.speed} ${gp.difficulty} ${gp.victoryTypes}" }
                val txt = updateLobby(m, LobbyChange(removeMod = listsMod))
                check(m.settings.speed == "Quick" && m.settings.difficulty == "Prince" && m.settings.victoryTypes == listOf("Domination")) { "${m.settings.speed} ${m.settings.difficulty} ${m.settings.victoryTypes}" }
                println("  [ModsPlus] " + txt.lines().filter { it.startsWith("⚖️") || it.startsWith("⏱️") || it.startsWith("🏆") }.joinToString(" | "))
            }
            expectError("mods: errors refused unless accept_mod_errors", "accept_mod_errors") {
                installFromZip(brokenZip, brokenMod, null)
                updateLobby(fakeMeta("mp8"), LobbyChange(addMod = brokenMod))
            }
            step("mods: accept_mod_errors starts the game anyway (errors shown + recorded)") {
                val m = fakeMeta("mp8")
                val txt = updateLobby(m, LobbyChange(addMod = brokenMod, acceptErrors = true))
                check(txt.contains("accepted") && brokenMod in m.settings.mods) { txt }
                check(lobbyLines(m).any { it.startsWith("🚨") }) { lobbyLines(m).toString() }
                val g = t.eng { GameOps.startGame(m) }
                check(g.gameParameters.acceptedModCheckErrors.contains("CivBot Broken")) { g.gameParameters.acceptedModCheckErrors }
                val off = try { updateLobby(m, LobbyChange(acceptErrors = false)); null } catch (e: PlayerError) { e.message }
                check(off?.contains("broken") == true && m.settings.extra[K_ACCEPT_ERRORS] == "true") { "turning it off: $off" }
                updateLobby(m, LobbyChange(removeMod = brokenMod, acceptErrors = false))
                check(lobbyLines(m).none { it.startsWith("🚨") } && m.settings.extra[K_ACCEPT_ERRORS] == null)
                println("  [ModsPlus] accepted errors: ${g.gameParameters.acceptedModCheckErrors.lines().first().take(150)}")
            }
            step("mods: GitHub catalogue (canned search reply)") {
                val body = """{"total_count":2,"items":[{"full_name":"a/b","html_url":"https://github.com/a/b","description":"Test mod","stargazers_count":5,"pushed_at":"2026-01-02T00:00:00Z"},
                    {"full_name":"c/d","html_url":"https://github.com/c/d","description":null,"stargazers_count":1},{"full_name":"evil","html_url":"https://evil.example/x"}]}"""
                val (n, hits) = parseModSearch(body)
                check(n == 2 && hits.size == 2 && hits[0].description == "Test mod" && hits[1].description == "" && hits[0].updated == "2026-01-02")
                val txt = browseText("test") { parseModSearch(body) }
                check(txt.contains("/civ mod action:install url:https://github.com/a/b") && txt.contains("Manage Server") && !txt.contains("evil")) { txt }
                check(searchQuery("  wonders\n") == "wonders topic:unciv-mod fork:true" && searchQuery("") == "topic:unciv-mod fork:true")
                check(browseText("x") { throw PlayerError("Couldn't reach GitHub") }.startsWith("❌"))
                check(browseText("zz") { 0 to emptyList() }.contains("No GitHub repositories"))
            }
            step("mods: browse GitHub for Unciv mods (live search; network may fail)") {
                val txt = browseText("religion")
                check(txt.startsWith("🔎") || txt.startsWith("❌")) { txt }
                println("  [ModsPlus] live browse: " + (if (txt.startsWith("❌")) "GitHub unavailable, handled: " else "") + txt.lines().take(3).joinToString(" | ").take(300))
            }
            // --- removal
            expectError("mods: remove refused while a game uses it", "is used by") {
                val s = Session(lobby.apply { id = "mp1x" }, null)
                t.ctl.store.sessions[s.meta.id] = s
                try { removeMod(testMod, t.ctl.store) } finally { t.ctl.store.sessions.remove(s.meta.id) }
            }
            step("mods: remove") {
                println("  [ModsPlus] " + removeMod(testMod, t.ctl.store))
                check(!Engine.run { RulesetCache.containsKey(testMod) } && !File(modsDir(), testMod).exists())
                check(scenarioList.none { it.mod == testMod })
            }
            step("maps: remove") { removeMap(testMap, t.ctl.store); check(testMap !in mapNames()) }
        } catch (e: Throwable) {
            failures += "setup: ${e.javaClass.simpleName}: ${e.message}"; e.printStackTrace()
        } finally {
            // leave the install exactly as we found it
            for (n in listOfNotNull(testMod, rivalMod, githubMod, listsMod, brokenMod)) File(modsDir(), n).takeIf { it.exists() }?.deleteRecursively()
            File(mapsDir(), testMap).delete()
            runCatching { Engine.run { reloadE() } }
            selfTesting = false
        }
        if (failures.isNotEmpty()) throw IllegalStateException("ModsPlus self-test failures:\n  " + failures.joinToString("\n  "))
    }
}

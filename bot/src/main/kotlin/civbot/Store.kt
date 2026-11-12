package civbot

import com.badlogic.gdx.utils.Json
import com.badlogic.gdx.utils.JsonWriter
import com.unciv.logic.GameInfo
import org.slf4j.LoggerFactory
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** A human seat: one Discord user = one civilization. */
class Seat(
    var userId: String = "",
    var userName: String = "",
    /** Nation name, or "Random" until the game starts */
    var nation: String = "Random",
    /** Unciv civID once the game has started */
    var civId: String = "",
    /** Hand this seat to the AI (player left / went AFK) */
    var aiControlled: Boolean = false,
)

class Settings(
    var mapSize: String = "Small",
    var mapType: String = "Continent and Islands",
    var difficulty: String = "Prince",
    var speed: String = "Quick",
    var aiCivs: Int = 2,
    var cityStates: Int = 4,
    var barbarians: Boolean = true,
    /** If false, the public board shows the full map (casual/streamed games) */
    var fogOfWar: Boolean = true,
    /** Ping the current player again after this many hours (0 = off) */
    var reminderHours: Int = 12,
    /** Automatically end an AFK player's turn after this many hours (0 = off) */
    var autoSkipHours: Int = 0,
    var startEra: String = "Ancient era",
    /** Empty = all victory types */
    var victoryTypes: ArrayList<String> = ArrayList(),
    var worldWrap: Boolean = false,
    /** Selected mods (ModsPlus) */
    var mods: ArrayList<String> = ArrayList(),
    /** Free-form options owned by extensions */
    var extra: HashMap<String, String> = HashMap(),
)

enum class Status { LOBBY, RUNNING, ENDED }

class GameMeta(
    var id: String = "",
    var name: String = "",
    var guildId: String = "",
    var channelId: String = "",
    /** Thread the game is played in (empty = played directly in channelId) */
    var threadId: String = "",
    var lobbyMessageId: String = "",
    var boardMessageId: String = "",
    var hostId: String = "",
    var status: Status = Status.LOBBY,
    var settings: Settings = Settings(),
    var seats: ArrayList<Seat> = ArrayList(),
    var events: ArrayList<String> = ArrayList(),
    var turnStartedAt: Long = 0,
    var lastReminderAt: Long = 0,
    var winner: String = "",
) {
    val playChannelId get() = threadId.ifEmpty { channelId }
    fun seatOf(userId: String) = seats.firstOrNull { it.userId == userId }
    fun seatOfCiv(civId: String) = seats.firstOrNull { it.civId == civId }
    fun addEvent(e: String) {
        events.add(e)
        while (events.size > 12) events.removeAt(0)
    }
}

/** A running game: metadata + live engine state. */
class Session(val meta: GameMeta, var game: GameInfo?)

/** Saves games as plain files under the data dir: <id>.meta.json and <id>.civ (Unciv save format). */
class Store(val dir: File) {
    private val log = LoggerFactory.getLogger("Store")
    val sessions = ConcurrentHashMap<String, Session>()

    private val json = Json(JsonWriter.OutputType.json).apply {
        setIgnoreUnknownFields(true)
        setUsePrototypes(false)
        setElementType(GameMeta::class.java, "seats", Seat::class.java)
        setElementType(GameMeta::class.java, "events", String::class.java)
        setElementType(Settings::class.java, "victoryTypes", String::class.java)
        setElementType(Settings::class.java, "mods", String::class.java)
        setElementType(Settings::class.java, "extra", String::class.java)
    }

    init { dir.mkdirs() }

    fun loadAll() {
        for (f in dir.listFiles { f -> f.name.endsWith(".meta.json") }.orEmpty()) {
            try {
                val meta = json.fromJson(GameMeta::class.java, f.readText())
                val save = File(dir, "${meta.id}.civ")
                val game = if (save.isFile && meta.status != Status.LOBBY)
                    Engine.run { Engine.deserialize(save.readText()) } else null
                sessions[meta.id] = Session(meta, game)
            } catch (e: Exception) {
                log.error("Could not load {}: {}", f.name, e.toString())
            }
        }
        log.info("Loaded {} game(s)", sessions.size)
    }

    fun saveMeta(meta: GameMeta) = atomicWrite(File(dir, "${meta.id}.meta.json"), json.prettyPrint(meta))

    /** Call on the engine thread (serialization reads live game state). */
    fun saveGame(session: Session) {
        saveMeta(session.meta)
        val game = session.game ?: return
        atomicWrite(File(dir, "${session.meta.id}.civ"), Engine.serialize(game))
    }

    fun archive(id: String) {
        val arch = File(dir, "finished").apply { mkdirs() }
        for (ext in listOf(".meta.json", ".civ")) File(dir, "$id$ext").takeIf { it.exists() }?.renameTo(File(arch, "$id$ext"))
        sessions.remove(id)
    }

    fun delete(id: String) {
        for (ext in listOf(".meta.json", ".civ")) File(dir, "$id$ext").delete()
        sessions.remove(id)
    }

    private fun atomicWrite(target: File, text: String) {
        val tmp = File(target.parentFile, target.name + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(target)) { target.delete(); tmp.renameTo(target) }
    }

    fun byChannel(channelId: String) = sessions.values.filter {
        it.meta.status != Status.ENDED && (it.meta.threadId == channelId || ((it.meta.threadId.isEmpty() || it.meta.threadId == "-") && it.meta.channelId == channelId))
    }
    fun forUser(userId: String) = sessions.values.filter { s -> s.meta.status != Status.ENDED && s.meta.seats.any { it.userId == userId } }
}

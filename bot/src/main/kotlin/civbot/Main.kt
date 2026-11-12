package civbot

import net.dv8tion.jda.api.JDABuilder
import net.dv8tion.jda.api.requests.GatewayIntent
import org.slf4j.LoggerFactory
import java.io.File
import kotlin.system.exitProcess

private val log = LoggerFactory.getLogger("civbot")

/** Reads KEY=VALUE lines from a .env file; real environment variables win. */
private fun loadEnv(file: File): Map<String, String> {
    val out = HashMap<String, String>()
    if (file.isFile) file.readLines().forEach { line ->
        val l = line.trim()
        if (l.isEmpty() || l.startsWith("#") || !l.contains("=")) return@forEach
        out[l.substringBefore("=").trim()] = l.substringAfter("=").trim().removeSurrounding("\"")
    }
    out.putAll(System.getenv())
    return out
}

fun main(args: Array<String>) {
    val home = File(System.getProperty("civbot.home") ?: "../../..").canonicalFile // run.sh sets this
    val env = loadEnv(File(home, ".env"))
    val uncivDir = File(env["UNCIV_DIR"] ?: File(home, "unciv").path)
    val dataDir = File(env["CIVBOT_DATA"] ?: File(home, "data").path)
    Engine.init(uncivDir)
    Engine.run { Extensions.all.forEach { e -> runCatching { e.onEngineInit() }.onFailure { log.error("extension init failed", it) } } }

    if (args.firstOrNull() == "--selftest") {
        SelfTest.run(File(args.getOrNull(1) ?: File(home, "selftest-out").path))
        exitProcess(0)
    }

    val token = env["DISCORD_TOKEN"]
    if (token.isNullOrBlank()) {
        System.err.println("DISCORD_TOKEN is not set. Put it in ${File(home, ".env")} (see .env.example).")
        exitProcess(1)
    }
    val store = Store(dataDir).apply { loadAll() }
    val controller = Controller(store)
    val bot = Bot(store, controller)
    JDABuilder.createLight(token, GatewayIntent.GUILD_MESSAGE_REACTIONS)
        .addEventListeners(bot)
        .build()

    env["ACTIVITY_PORT"]?.toIntOrNull()?.let { port ->
        val clientId = env["DISCORD_CLIENT_ID"]
        val secret = env["DISCORD_CLIENT_SECRET"]
        if (clientId.isNullOrBlank() || secret.isNullOrBlank())
            log.warn("ACTIVITY_PORT is set but DISCORD_CLIENT_ID / DISCORD_CLIENT_SECRET are missing — Activity add-on disabled")
        else ActivityServer(store, controller, port, clientId, secret, File(home, "activity")) { s, r -> bot.announce(s, r) }.start()
    }
}

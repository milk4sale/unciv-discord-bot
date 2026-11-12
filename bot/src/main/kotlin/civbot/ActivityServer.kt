package civbot

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.parameters
import io.ktor.server.application.ApplicationCall
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondFile
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import net.dv8tion.jda.api.utils.data.DataArray
import net.dv8tion.jda.api.utils.data.DataObject
import org.slf4j.LoggerFactory
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * OPTIONAL add-on: a Discord Activity (embedded web app) that shows the same private panel
 * with a bigger, clickable view. The channel bot works fully without it.
 *
 * The page talks to these endpoints (all behind Discord's /.proxy URL mapping):
 *   POST /api/token   {code}             -> {access_token}   (OAuth code exchange)
 *   GET  /api/games                      -> games the user is seated in
 *   GET  /api/panel?gid=..               -> {embed, components} in Discord's own JSON shape
 *   GET  /api/map?gid=..                 -> PNG of the last rendered map
 *   POST /api/action  {gid, id, value}   -> applies a component action, returns new panel
 */
class ActivityServer(
    private val store: Store, private val ctl: Controller,
    private val port: Int, private val clientId: String, private val clientSecret: String,
    private val webDir: File,
    /** Lets the bot announce a turn that was ended from inside the Activity */
    private val onTurnEnded: (Session, TurnResult) -> Unit,
) {
    private val log = LoggerFactory.getLogger("Activity")
    private val http = HttpClient(CIO)
    private val users = ConcurrentHashMap<String, Pair<String, Long>>() // token -> (userId, expiry)
    private val lastMap = ConcurrentHashMap<String, ByteArray>()        // gid:user -> png

    fun start() {
        embeddedServer(Netty, port = port) {
            routing {
                get("/") {
                    val page = File(webDir, "index.html").readText().replace("%CLIENT_ID%", clientId)
                    call.respondText(page, ContentType.Text.Html)
                }
                post("/api/token") {
                    val code = DataObject.fromJson(call.receiveText()).getString("code")
                    val res = http.submitForm("https://discord.com/api/oauth2/token", parameters {
                        append("client_id", clientId); append("client_secret", clientSecret)
                        append("grant_type", "authorization_code"); append("code", code)
                    }).bodyAsText()
                    call.respondText(res, ContentType.Application.Json)
                }
                get("/api/games") {
                    val uid = user(call) ?: return@get
                    val arr = DataArray.empty()
                    store.forUser(uid).filter { it.game != null }.forEach { s ->
                        arr.add(DataObject.empty().put("id", s.meta.id).put("name", s.meta.name))
                    }
                    call.respondText(arr.toString(), ContentType.Application.Json)
                }
                get("/api/panel") {
                    val uid = user(call) ?: return@get
                    val gid = call.request.queryParameters["gid"] ?: ""
                    call.respondText(panelJson(gid, uid, null), ContentType.Application.Json)
                }
                get("/api/map") {
                    val uid = user(call) ?: return@get
                    val png = lastMap["${call.request.queryParameters["gid"]}:$uid"]
                    if (png == null) call.respondText("", status = HttpStatusCode.NotFound)
                    else call.respondBytes(png, ContentType.Image.PNG)
                }
                post("/api/action") {
                    val uid = user(call) ?: return@post
                    val body = DataObject.fromJson(call.receiveText())
                    val gid = body.getString("gid")
                    val parts = body.getString("id").split("|")
                    val value = body.getString("value", null)
                    call.respondText(panelJson(gid, uid, Triple(parts.getOrElse(2) { "refresh" }, parts.drop(3), value)), ContentType.Application.Json)
                }
            }
        }.start(wait = false)
        log.info("Activity add-on listening on :{}", port)
    }

    private suspend fun user(call: ApplicationCall): String? {
        val token = call.request.headers["Authorization"]?.removePrefix("Bearer ")?.trim()
        if (token.isNullOrEmpty()) { call.respondText("no token", status = HttpStatusCode.Unauthorized); return null }
        users[token]?.let { (id, exp) -> if (exp > System.currentTimeMillis()) return id }
        val me = http.get("https://discord.com/api/users/@me") { header("Authorization", "Bearer $token") }.bodyAsText()
        val id = runCatching { DataObject.fromJson(me).getString("id") }.getOrNull()
        if (id == null) { call.respondText("bad token", status = HttpStatusCode.Unauthorized); return null }
        users[token] = id to System.currentTimeMillis() + 10 * 60_000
        return id
    }

    private fun panelJson(gid: String, uid: String, action: Triple<String, List<String>, String?>?): String {
        val s = store.sessions[gid] ?: return """{"error":"No such game"}"""
        val seat = s.meta.seatOf(uid) ?: return """{"error":"You're not in this game"}"""
        if (s.game == null) return """{"error":"Game not started"}"""
        var error: String? = null
        if (action != null) try {
            ctl.apply(s, seat, action.first, action.second, action.third)?.let { onTurnEnded(s, it) }
        } catch (e: PlayerError) { error = e.message }
        val r = Engine.withGame(s.game!!) { Panel.render(s, seat, ctl.state(gid, uid)) }
        r.file?.let { lastMap["$gid:$uid"] = it.data.readBytes() }
        val rows = DataArray.empty().apply { r.rows.forEach { add(it.toData()) } }
        return DataObject.empty().put("embed", r.embed.toData()).put("components", rows)
            .put("error", error).toString()
    }
}

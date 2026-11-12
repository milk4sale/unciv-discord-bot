package civbot

import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.JDA
import net.dv8tion.jda.api.entities.channel.concrete.ThreadChannel
import net.dv8tion.jda.api.entities.channel.middleman.MessageChannel
import net.dv8tion.jda.api.entities.emoji.Emoji
import net.dv8tion.jda.api.events.interaction.ModalInteractionEvent
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent
import net.dv8tion.jda.api.events.interaction.component.GenericComponentInteractionCreateEvent
import net.dv8tion.jda.api.events.interaction.component.StringSelectInteractionEvent
import net.dv8tion.jda.api.events.session.ReadyEvent
import net.dv8tion.jda.api.hooks.ListenerAdapter
import net.dv8tion.jda.api.interactions.InteractionHook
import net.dv8tion.jda.api.interactions.commands.OptionType
import net.dv8tion.jda.api.interactions.commands.build.Commands
import net.dv8tion.jda.api.interactions.commands.build.OptionData
import net.dv8tion.jda.api.interactions.commands.build.SubcommandData
import net.dv8tion.jda.api.interactions.components.ActionRow
import net.dv8tion.jda.api.interactions.components.buttons.Button
import net.dv8tion.jda.api.interactions.components.selections.StringSelectMenu
import net.dv8tion.jda.api.interactions.components.text.TextInput
import net.dv8tion.jda.api.interactions.components.text.TextInputStyle
import net.dv8tion.jda.api.interactions.modals.Modal
import net.dv8tion.jda.api.utils.FileUpload
import net.dv8tion.jda.api.utils.messages.MessageCreateBuilder
import net.dv8tion.jda.api.utils.messages.MessageEditBuilder
import org.slf4j.LoggerFactory
import java.awt.Color
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Discord frontend: slash commands, lobby, public board, turn pings and the private panels. */
class Bot(override val store: Store, override val ctl: Controller) : ListenerAdapter(), BotApi {
    private val log = LoggerFactory.getLogger("Bot")
    private val workers = Executors.newFixedThreadPool(4) { r -> Thread(r, "civbot-worker").apply { isDaemon = true } }
    private val scheduler = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "civbot-timer").apply { isDaemon = true } }
    lateinit var jda: JDA

    // ================================================================== setup

    private lateinit var civCommand: net.dv8tion.jda.api.interactions.commands.build.SlashCommandData

    private fun registerCommands(guild: net.dv8tion.jda.api.entities.Guild) {
        guild.updateCommands().addCommands(civCommand).queue(
            { log.info("Slash commands ready in {}", guild.name) },
            { e -> log.warn("Couldn't register commands in {}: {}", guild.name, e.message) })
    }

    override fun onGuildJoin(event: net.dv8tion.jda.api.events.guild.GuildJoinEvent) = registerCommands(event.guild)

    override fun onReady(event: ReadyEvent) {
        jda = event.jda
        val speed = OptionData(OptionType.STRING, "speed", "Game speed (Quick is best for Discord)")
            .also { o -> GameOps.SPEEDS.forEach { o.addChoice(it, it) } }
        civCommand = (
            Commands.slash("civ", "Play Civilization-style games right here in Discord").addSubcommands(
                SubcommandData("new", "Open a lobby for a new game in this channel").addOptions(
                    OptionData(OptionType.STRING, "name", "Name for the game"),
                    speed,
                    OptionData(OptionType.INTEGER, "city_states", "How many city-states (default 4)")
                        .also { o -> (0..12).forEach { n -> o.addChoice(if (n == 0) "None" else "$n city-states", n.toLong()) } },
                    OptionData(OptionType.BOOLEAN, "barbarians", "Barbarians on/off (default on)"),
                    OptionData(OptionType.BOOLEAN, "fog", "Fog of war (default on). Off = everyone sees the whole map"),
                    OptionData(OptionType.BOOLEAN, "thread", "Play in a dedicated thread (default on)"),
                ),
                SubcommandData("play", "Open your private control panel"),
                SubcommandData("board", "Re-post the game board in this channel"),
                SubcommandData("games", "List your games"),
                SubcommandData("leave", "Hand your civilization over to the AI"),
                SubcommandData("kick", "Host: hand a player's civilization to the AI").addOption(OptionType.USER, "player", "Who to remove", true),
                SubcommandData("host", "Host: make someone else the host").addOption(OptionType.USER, "player", "New host", true),
                SubcommandData("spectate", "Watch a game you're not playing in"),
                SubcommandData("end", "End the game (host only)"),
                SubcommandData("help", "How to play"),
            ).addSubcommands(Extensions.all.flatMap { runCatching { it.commands() }.getOrDefault(emptyList()) })
                .addSubcommandGroups(Extensions.all.flatMap { runCatching { it.commandGroups() }.getOrDefault(emptyList()) })
        )
        // Register per server: updates apply instantly (global commands can take minutes and show
        // "This command is outdated"). Clear any old global copy so it doesn't appear twice.
        jda.updateCommands().queue()
        jda.guilds.forEach { registerCommands(it) }
        log.info("Logged in as {} — {} game(s) loaded", jda.selfUser.asTag, store.sessions.size)
        scheduler.scheduleAtFixedRate({ runCatching { remind() }.onFailure { log.warn("reminder failed", it) } }, 5, 30, TimeUnit.MINUTES)
    }

    // ================================================================== slash commands

    override fun onSlashCommandInteraction(event: SlashCommandInteractionEvent) {
        if (event.name != "civ") return
        when (event.subcommandName) {
            "new" -> newGame(event)
            "play" -> {
                val s = gameHere(event.channel.id, event.user.id) ?: return event.reply(noGameHere()).setEphemeral(true).queue()
                event.deferReply(true).queue { hook -> workers.submit { showPanel(hook, s, event.user.id) } }
            }
            "board" -> {
                val s = store.byChannel(event.channel.id).firstOrNull() ?: return event.reply(noGameHere()).setEphemeral(true).queue()
                event.deferReply().queue { hook -> workers.submit { safely(hook) { hook.editOriginal(boardEdit(s)).queue() } } }
            }
            "games" -> {
                val mine = store.forUser(event.user.id)
                val txt = if (mine.isEmpty()) "You're not in any games. Start one with `/civ new`."
                else mine.joinToString("\n") { s ->
                    val where = "<#${s.meta.playChannelId}>"
                    "• **${s.meta.name}** (${s.meta.id}) — ${s.meta.status.name.lowercase()} in $where"
                }
                event.reply(txt).setEphemeral(true).queue()
            }
            "leave" -> {
                val s = gameHere(event.channel.id, event.user.id) ?: return event.reply(noGameHere()).setEphemeral(true).queue()
                event.deferReply().queue { hook -> workers.submit { safely(hook) { leave(hook, s, event.user.id) } } }
            }
            "kick", "host" -> {
                val s = store.byChannel(event.channel.id).firstOrNull() ?: return event.reply(noGameHere()).setEphemeral(true).queue()
                if (s.meta.hostId != event.user.id) return event.reply("Only the host (<@${s.meta.hostId}>) can do that.").setEphemeral(true).queue()
                val target = event.getOption("player")!!.asUser
                if (s.meta.seatOf(target.id) == null) return event.reply("<@${target.id}> isn't in this game.").setEphemeral(true).queue()
                if (event.subcommandName == "host") {
                    s.meta.hostId = target.id; store.saveMeta(s.meta)
                    return event.reply("👑 <@${target.id}> is now the host of **${s.meta.name}**.").queue()
                }
                event.deferReply().queue { hook -> workers.submit { safely(hook) { leave(hook, s, target.id) } } }
            }
            "spectate" -> {
                val s = store.byChannel(event.channel.id).firstOrNull { it.game != null } ?: return event.reply(noGameHere()).setEphemeral(true).queue()
                event.deferReply(true).queue { hook -> workers.submit { safely(hook) { spectate(hook, s, event.user.id) } } }
            }
            "end" -> {
                val s = store.byChannel(event.channel.id).firstOrNull() ?: return event.reply(noGameHere()).setEphemeral(true).queue()
                if (s.meta.hostId != event.user.id) return event.reply("Only the host (<@${s.meta.hostId}>) can end the game.").setEphemeral(true).queue()
                event.reply("Really end **${s.meta.name}**? This can't be undone.").setEphemeral(true)
                    .addActionRow(Button.danger(Panel.id(s.meta.id, "endgame"), "Yes, end the game")).queue()
            }
            else -> {
                if (Extensions.all.any { runCatching { it.onCommand(event, this) }.getOrElse { e -> log.error("command failed", e); false } }) return
                event.replyEmbeds(helpEmbed()).setEphemeral(true).queue()
            }
        }
    }

    // ---- BotApi (for extensions)
    override fun async(block: () -> Unit) { workers.submit { runCatching(block).onFailure { log.error("extension task failed", it) } } }
    override fun refreshLobby(meta: GameMeta) = lobbyMessageEdit(meta)

    private fun noGameHere() = "There's no game you're part of here. Use `/civ games` to find yours, or `/civ new` to start one."

    override fun gameHere(channelId: String, userId: String): Session? {
        val here = store.byChannel(channelId)
        return here.firstOrNull { it.meta.seatOf(userId) != null && it.meta.status == Status.RUNNING }
            ?: here.firstOrNull { it.meta.seatOf(userId) != null }
    }

    private fun newGame(event: SlashCommandInteractionEvent) {
        // Check channel permissions up front (replies to commands work without them, posting doesn't)
        val self = event.guild?.selfMember
        val channel = event.guildChannel
        if (self != null) {
            val P = net.dv8tion.jda.api.Permission.entries
            val required = listOf("VIEW_CHANNEL", "MESSAGE_SEND", "MESSAGE_EMBED_LINKS", "MESSAGE_ATTACH_FILES", "MESSAGE_HISTORY")
                .map { n -> P.first { it.name == n } }
            val missing = required.filter { !self.hasPermission(channel, it) }
            if (missing.isNotEmpty()) {
                event.reply("⚠️ I can't run a game in this channel yet — I'm missing: **${missing.joinToString { it.getName() }}**.\n" +
                    "Fix: Edit Channel → Permissions → add **${self.effectiveName}** and allow those, or re-invite me with the invite link from the README.")
                    .setEphemeral(true).queue()
                return
            }
        }
        val canThread = self == null || (self.hasPermission(channel, net.dv8tion.jda.api.Permission.CREATE_PUBLIC_THREADS) &&
            self.hasPermission(channel, net.dv8tion.jda.api.Permission.MESSAGE_SEND_IN_THREADS))
        val id = java.lang.Long.toString(System.currentTimeMillis() % 2176782336L, 36).padStart(6, '0')
        val meta = GameMeta(
            id = id,
            name = event.getOption("name")?.asString?.take(60) ?: "${event.member?.effectiveName ?: event.user.name}'s game",
            guildId = event.guild?.id ?: "",
            channelId = event.channel.id,
            hostId = event.user.id,
        )
        meta.settings.speed = event.getOption("speed")?.asString ?: "Quick"
        event.getOption("city_states")?.let { meta.settings.cityStates = it.asInt }
        event.getOption("barbarians")?.let { meta.settings.barbarians = it.asBoolean }
        event.getOption("fog")?.let { meta.settings.fogOfWar = it.asBoolean }
        if (event.getOption("thread")?.asBoolean == false || !canThread) meta.threadId = "-"
        meta.seats += Seat(event.user.id, event.member?.effectiveName ?: event.user.name)
        store.sessions[id] = Session(meta, null)
        store.saveMeta(meta)
        event.reply(MessageCreateBuilder().setEmbeds(lobbyEmbed(meta)).setComponents(lobbyRows(meta)).build()).queue { hook ->
            hook.retrieveOriginal().queue { msg -> meta.lobbyMessageId = msg.id; store.saveMeta(meta) }
        }
    }

    // ================================================================== lobby

    private fun lobbyEmbed(meta: GameMeta) = EmbedBuilder().apply {
        setColor(Color(46, 134, 193))
        setTitle("🌍 ${meta.name}")
        setDescription(
            "A new game is forming! Click **Join** to take a seat, then the host clicks **Start**.\n" +
                "Turns are taken one at a time, so play at your own pace — the bot pings you when it's your turn."
        )
        addField("Players (${meta.seats.size})", meta.seats.joinToString("\n") { s ->
            "${if (s.userId == meta.hostId) "👑" else "•"} <@${s.userId}> — ${if (s.nation == "Random") "🎲 random nation" else s.nation}"
        }, false)
        val st = meta.settings
        addField("Settings", "🗺️ ${st.mapSize} · ${st.mapType}\n⚖️ ${st.difficulty} · ⏱️ ${st.speed}\n" +
            "🤖 ${st.aiCivs} AI civs · 🏰 ${st.cityStates} city-states · ${if (st.barbarians) "🪓 barbarians" else "no barbarians"} · " +
            (if (st.fogOfWar) "🌫️ fog of war" else "👁️ no fog") +
            "\n🏺 starts in ${st.startEra} · 🏆 ${st.victoryTypes.ifEmpty { GameOps.VICTORIES }.joinToString()}" +
            (if (st.worldWrap) " · 🌐 world wrap" else "") +
            (if (st.autoSkipHours > 0) "\n⏩ AFK turns auto-end after ${st.autoSkipHours}h" else "") +
            Extensions.all.flatMap { runCatching { it.lobbyLines(meta) }.getOrDefault(emptyList()) }.joinToString("") { "\n$it" }, false)
        setFooter("Game ${meta.id} · only the host can change settings (⚙️ for more)")
    }.build()

    private fun lobbyRows(meta: GameMeta): List<ActionRow> {
        val g = meta.id
        val st = meta.settings
        fun sel(key: String, ph: String, values: List<String>, cur: String) =
            StringSelectMenu.create(Panel.id(g, "set", key)).setPlaceholder(ph)
                .addOptions(values.map { net.dv8tion.jda.api.interactions.components.selections.SelectOption.of("$ph: $it", it).withDefault(it == cur) }).build()
        return listOf(
            ActionRow.of(
                Button.success(Panel.id(g, "join"), "Join").withEmoji(Emoji.fromUnicode("➕")),
                Button.secondary(Panel.id(g, "leave"), "Leave"),
                Button.secondary(Panel.id(g, "nation"), "Pick nation").withEmoji(Emoji.fromUnicode("🏳️")),
                Button.primary(Panel.id(g, "start"), "Start").withEmoji(Emoji.fromUnicode("▶️")),
                Button.secondary(Panel.id(g, "more"), "Settings").withEmoji(Emoji.fromUnicode("⚙️")),
            ),
            ActionRow.of(sel("size", "Map size", GameOps.MAP_SIZES, st.mapSize)),
            ActionRow.of(sel("type", "Map type", GameOps.MAP_TYPES, st.mapType)),
            ActionRow.of(sel("diff", "Difficulty", GameOps.DIFFICULTIES, st.difficulty)),
            ActionRow.of(sel("ai", "AI opponents", (0..SetupPlus.maxAiChoice(meta)).map { it.toString() }, st.aiCivs.toString())),
        )
    }

    private fun moreSettingsRows(meta: GameMeta): List<ActionRow> {
        val g = meta.id
        val st = meta.settings
        fun opt(label: String, value: String, def: Boolean) =
            net.dv8tion.jda.api.interactions.components.selections.SelectOption.of(label, value).withDefault(def)
        fun onOff(b: Boolean) = if (b) "on" else "off"
        return listOf(
            ActionRow.of(StringSelectMenu.create(Panel.id(g, "xset", "speed")).setPlaceholder("Game speed")
                .addOptions(GameOps.SPEEDS.map { opt("Speed: $it", it, it == st.speed) }).build()),
            ActionRow.of(StringSelectMenu.create(Panel.id(g, "xset", "cs")).setPlaceholder("City-states")
                .addOptions((0..12).map { opt(if (it == 0) "No city-states" else "$it city-states", it.toString(), it == st.cityStates) }).build()),
            ActionRow.of(StringSelectMenu.create(Panel.id(g, "xset", "era")).setPlaceholder("Starting era")
                .addOptions(GameOps.ERAS.map { opt("Start in the $it", it, it == st.startEra) }).build()),
            ActionRow.of(StringSelectMenu.create(Panel.id(g, "xset", "vic")).setPlaceholder("Victory types (pick any)")
                .addOptions(GameOps.VICTORIES.map { opt("$it victory", it, st.victoryTypes.isEmpty() || it in st.victoryTypes) })
                .setRequiredRange(1, GameOps.VICTORIES.size).build()),
            ActionRow.of(
                Button.secondary(Panel.id(g, "xtog", "barb"), "Barbarians: ${onOff(st.barbarians)}").withEmoji(Emoji.fromUnicode("🪓")),
                Button.secondary(Panel.id(g, "xtog", "fog"), "Fog: ${onOff(st.fogOfWar)}").withEmoji(Emoji.fromUnicode("🌫️")),
                Button.secondary(Panel.id(g, "xtog", "wrap"), "World wrap: ${onOff(st.worldWrap)}").withEmoji(Emoji.fromUnicode("🌐")),
                Button.secondary(Panel.id(g, "xtog", "skip"), "AFK skip: ${if (st.autoSkipHours == 0) "off" else "${st.autoSkipHours}h"}").withEmoji(Emoji.fromUnicode("⏩")),
                Button.danger(Panel.id(g, "xtog", "cancel"), "Cancel game"),
            ),
        )
    }

    private fun refreshLobby(hook: InteractionHook, meta: GameMeta) {
        store.saveMeta(meta)
        hook.editOriginalEmbeds(lobbyEmbed(meta)).setComponents(lobbyRows(meta)).queue()
    }

    private fun lobbyAction(event: GenericComponentInteractionCreateEvent, s: Session, verb: String, args: List<String>) {
        val meta = s.meta
        val uid = event.user.id
        val name = event.member?.effectiveName ?: event.user.name
        if (meta.status != Status.LOBBY) return event.reply("This game has already started.").setEphemeral(true).queue()
        val isHost = uid == meta.hostId
        when (verb) {
            "nation" -> {
                if (meta.seatOf(uid) == null) return event.reply("Join the game first.").setEphemeral(true).queue()
                val taken = meta.seats.filter { it.userId != uid }.map { it.nation }.toSet()
                val nations = Engine.run { GameOps.majorNations(meta) }.filter { it !in taken }
                val rows = nations.chunked(24).take(4).mapIndexed { i, chunk ->
                    ActionRow.of(StringSelectMenu.create(Panel.id(meta.id, "pick", i.toString()))
                        .setPlaceholder("Nations ${chunk.first().first()}–${chunk.last().first()}")
                        .addOptions((if (i == 0) listOf("Random") else emptyList<String>()).plus(chunk).map {
                            net.dv8tion.jda.api.interactions.components.selections.SelectOption.of(it, it)
                        }).build())
                }
                event.reply("Choose your nation:").setEphemeral(true).setComponents(rows).queue()
                return
            }
            "pick" -> {
                val value = (event as StringSelectInteractionEvent).values.first()
                synchronized(meta) {
                    if (value != "Random" && meta.seats.any { it.userId != uid && it.nation == value })
                        return event.reply("$value was just taken — pick another.").setEphemeral(true).queue()
                    meta.seatOf(uid)?.nation = value
                }
                event.editMessage("You'll play as **$value**.").setComponents().queue()
                lobbyMessageEdit(meta)
                return
            }
        }
        if (verb in setOf("start", "cancel", "set", "more", "xset", "xtog") && !isHost)
            return event.reply("Only the host (<@${meta.hostId}>) can do that.").setEphemeral(true).queue()
        if (verb == "more") return event.reply(MessageCreateBuilder().setContent("⚙️ **More settings** for ${meta.name}")
            .setComponents(moreSettingsRows(meta)).build()).setEphemeral(true).queue()
        if (verb == "xset" || verb == "xtog") {
            synchronized(meta) {
                val st = meta.settings
                if (verb == "xset") {
                    val values = (event as StringSelectInteractionEvent).values
                    when (args[0]) {
                        "speed" -> st.speed = values.first()
                        "cs" -> st.cityStates = values.first().toInt()
                        "era" -> st.startEra = values.first()
                        "vic" -> st.victoryTypes = ArrayList(values)
                    }
                } else when (args[0]) {
                    "barb" -> st.barbarians = !st.barbarians
                    "fog" -> st.fogOfWar = !st.fogOfWar
                    "wrap" -> st.worldWrap = !st.worldWrap
                    "skip" -> st.autoSkipHours = when (st.autoSkipHours) { 0 -> 12; 12 -> 24; 24 -> 48; else -> 0 }
                    "cancel" -> {
                        store.delete(meta.id)
                        event.editMessage("Game cancelled.").setComponents().queue()
                        jda.getChannelById(MessageChannel::class.java, meta.channelId)?.editMessageEmbedsById(meta.lobbyMessageId,
                            EmbedBuilder().setTitle("🌍 ${meta.name}").setDescription("Game cancelled by the host.").build())?.setComponents()?.queue()
                        return
                    }
                }
            }
            event.editMessage("⚙️ **More settings** for ${meta.name}").setComponents(moreSettingsRows(meta)).queue()
            lobbyMessageEdit(meta)
            return
        }
        event.deferEdit().queue()
        workers.submit {
            safely(event.hook) {
                synchronized(meta) {
                    when (verb) {
                        "join" -> if (meta.seatOf(uid) == null) {
                            if (SetupPlus.isFull(meta)) throw PlayerError("The game is full.")
                            meta.seats += Seat(uid, name)
                        }
                        "leave" -> {
                            if (isHost) throw PlayerError("The host can't leave — use **Cancel** instead.")
                            meta.seats.removeIf { it.userId == uid }
                        }
                        "set" -> {
                            val v = (event as StringSelectInteractionEvent).values.first()
                            when (args[0]) {
                                "size" -> meta.settings.mapSize = v
                                "type" -> meta.settings.mapType = v
                                "diff" -> meta.settings.difficulty = v
                                "ai" -> meta.settings.aiCivs = v.toInt()
                            }
                        }
                        "cancel" -> {
                            store.delete(meta.id)
                            event.hook.editOriginalEmbeds(EmbedBuilder().setTitle("🌍 ${meta.name}").setDescription("Game cancelled.").build())
                                .setComponents().queue()
                            return@safely
                        }
                        "start" -> {
                            startGame(event.hook, s)
                            return@safely
                        }
                    }
                }
                refreshLobby(event.hook, meta)
            }
        }
    }

    private fun lobbyMessageEdit(meta: GameMeta) {
        store.saveMeta(meta)
        val ch = jda.getChannelById(MessageChannel::class.java, meta.channelId) ?: return
        ch.editMessageEmbedsById(meta.lobbyMessageId, lobbyEmbed(meta)).setComponents(lobbyRows(meta)).queue()
    }

    private fun startGame(hook: InteractionHook, s: Session) {
        val meta = s.meta
        if (meta.seats.size + meta.settings.aiCivs < 2) throw PlayerError("You need at least 2 civilizations (add AI opponents or more players).")
        hook.editOriginalEmbeds(EmbedBuilder().setTitle("🌍 ${meta.name}").setDescription("⏳ Generating the world…").build()).setComponents().queue()
        val game = try { Engine.run { GameOps.startGame(meta) } } catch (e: Exception) {
            log.warn("Game start failed for {}", meta.id, e)
            hook.editOriginalEmbeds(lobbyEmbed(meta)).setComponents(lobbyRows(meta)).queue()
            throw if (e is PlayerError) e else PlayerError("Couldn't create the world: ${e.message}. Check the lobby settings (map, mods, scenario) and try again.")
        }
        s.game = game
        meta.status = Status.RUNNING
        meta.turnStartedAt = System.currentTimeMillis()
        meta.addEvent("🌍 The world was created. ${meta.seats.size} player(s), ${meta.settings.aiCivs} AI.")

        // Play in a thread off the lobby message, if allowed
        if (meta.threadId != "-") {
            try {
                val ch = jda.getTextChannelById(meta.channelId)
                val thread = ch?.createThreadChannel("🌍 ${meta.name}", meta.lobbyMessageId)
                    ?.setAutoArchiveDuration(ThreadChannel.AutoArchiveDuration.TIME_1_WEEK)?.complete()
                meta.threadId = thread?.id ?: ""
            } catch (e: Exception) {
                log.warn("Couldn't create thread ({}); playing in the channel", e.message)
                meta.threadId = ""
            }
        } else meta.threadId = ""
        Engine.withGame(game) { store.saveGame(s) }

        val rosterText = Engine.withGame(game) {
            meta.seats.joinToString("\n") { seat -> "<@${seat.userId}> leads **${GameOps.civOf(game, seat).civName}**" }
        }
        hook.editOriginalEmbeds(EmbedBuilder().setColor(Color(39, 174, 96)).setTitle("🌍 ${meta.name} has begun!")
            .setDescription(rosterText + if (meta.threadId.isNotEmpty()) "\n\nThe game is played in <#${meta.threadId}>." else "").build())
            .setComponents().queue()

        val play = playChannel(meta) ?: return
        play.sendMessage(boardCreate(s)).queue { msg ->
            meta.boardMessageId = msg.id
            store.saveMeta(meta)
            runCatching { msg.pin().queue({}, {}) }
        }
        announceTurn(s, null)
    }

    // ================================================================== board & turn announcements

    private fun playChannel(meta: GameMeta): MessageChannel? =
        jda.getChannelById(MessageChannel::class.java, meta.playChannelId)

    private class Board(val embed: net.dv8tion.jda.api.entities.MessageEmbed, val rows: List<ActionRow>, val file: FileUpload?)

    private fun buildBoard(s: Session): Board {
        val meta = s.meta
        val game = s.game!!
        return Engine.withGame(game) {
            val cur = game.currentPlayerCiv
            val curSeat = meta.seatOfCiv(cur.civID)
            val eb = EmbedBuilder().setColor(Color(241, 196, 15))
            eb.setTitle("🌍 ${meta.name} — Turn ${game.turns + 1} · ${GameOps.yearText(game)}")
            eb.setDescription(
                if (meta.status == Status.ENDED) "🏁 **Game over.** ${meta.winner}"
                else "▶️ Now playing: ${curSeat?.let { "<@${it.userId}>" } ?: cur.civName} (**${cur.civName}**)\n" +
                    "Press **Open my panel** to see your empire — only you can see your panel."
            )
            val public = GameOps.publicStatsVisible(game)
            val scores = GameOps.scoreboard(game).let { list -> if (public) list else list.sortedBy { it.first.civName } }.joinToString("\n") { (civ, score) ->
                val who = meta.seatOfCiv(civ.civID)?.let { "<@${it.userId}>" } ?: "AI"
                "${if (!civ.isAlive()) "☠️" else if (civ == cur) "▶️" else "•"} **${civ.civName}** ($who)" +
                    (if (public) " — $score pts · ${civ.cities.size} cities" else "")
            }
            eb.addField(if (public) "Scoreboard" else "Players (scores hidden by game settings)", scores.take(1024), false)
            if (meta.events.isNotEmpty()) eb.addField("Recent events", meta.events.takeLast(8).joinToString("\n").take(1024), false)
            var file: FileUpload? = null
            if (!meta.settings.fogOfWar || meta.status == Status.ENDED) {
                file = FileUpload.fromData(MapRenderer.renderWhole(game.tileMap.values, null, 1), "world.png")
                eb.setImage("attachment://world.png")
            }
            eb.setFooter("Game ${meta.id} · /civ help for how to play")
            val rows = if (meta.status == Status.ENDED) emptyList() else listOf(ActionRow.of(
                Button.primary(Panel.id(meta.id, "play"), "Open my panel").withEmoji(Emoji.fromUnicode("🎮")),
                Button.secondary(Panel.id(meta.id, "boardrefresh"), "Refresh").withEmoji(Emoji.fromUnicode("🔄")),
            ))
            Board(eb.build(), rows, file)
        }
    }

    private fun boardCreate(s: Session) = buildBoard(s).let { b ->
        MessageCreateBuilder().setEmbeds(b.embed).setComponents(b.rows).also { if (b.file != null) it.setFiles(b.file) }.build()
    }

    private fun boardEdit(s: Session) = buildBoard(s).let { b ->
        MessageEditBuilder().setEmbeds(b.embed).setComponents(b.rows).setAttachments(listOfNotNull(b.file)).build()
    }

    private fun updateBoard(s: Session) {
        val ch = playChannel(s.meta) ?: return
        if (s.meta.boardMessageId.isEmpty()) return
        ch.editMessageById(s.meta.boardMessageId, boardEdit(s)).queue({}, { e -> log.warn("board edit failed: {}", e.message) })
    }

    /** Used by the Activity add-on when a turn is ended there. */
    fun announce(s: Session, r: TurnResult) = workers.submit { announceTurn(s, r) }

    /** Post the turn change in the play channel and ping the next human. */
    private fun announceTurn(s: Session, result: TurnResult?) {
        val meta = s.meta
        val ch = playChannel(meta) ?: return
        updateBoard(s)
        if (result != null && result.gameOver) {
            val b = buildBoard(s)
            ch.sendMessage(MessageCreateBuilder().setContent("🏁 **Game over!** ${result.winnerText}\n" +
                meta.seats.joinToString(" ") { "<@${it.userId}>" })
                .setEmbeds(b.embed).also { if (b.file != null) it.setFiles(b.file) }.build()).queue()
            store.archive(meta.id)
            return
        }
        val game = s.game!!
        val (turn, civName, seat) = Engine.withGame(game) {
            Triple(game.turns + 1, game.currentPlayerCiv.civName, meta.seatOfCiv(game.currentPlayerCiv.civID))
        }
        val publicEvents = result?.events?.filter { !it.contains("Advisor") }?.takeLast(6).orEmpty()
        val content = buildString {
            if (publicEvents.isNotEmpty()) append(publicEvents.joinToString("\n")).append("\n")
            append("⏰ ${seat?.let { "<@${it.userId}>" } ?: civName}, it's your turn as **$civName** (turn $turn).")
        }
        ch.sendMessage(content.take(1900))
            .setActionRow(Button.primary(Panel.id(meta.id, "play"), "Play my turn").withEmoji(Emoji.fromUnicode("🎮")))
            .queue()
    }

    private fun remind() {
        val now = System.currentTimeMillis()
        for (s in store.sessions.values) {
            val m = s.meta
            if (m.status == Status.RUNNING && s.game != null && m.settings.autoSkipHours > 0 &&
                now - m.turnStartedAt > m.settings.autoSkipHours * 3_600_000L) {
                val seat = Engine.withGame(s.game!!) { m.seatOfCiv(s.game!!.currentPlayerCiv.civID) }
                m.addEvent("⏩ ${seat?.let { "<@${it.userId}>" } ?: "A player"}'s turn was skipped after ${m.settings.autoSkipHours}h.")
                runCatching { ctl.forceEndTurn(s) }.onSuccess { announceTurn(s, it) }.onFailure { log.warn("auto-skip failed", it) }
                continue
            }
            if (m.status != Status.RUNNING || m.settings.reminderHours <= 0 || s.game == null) continue
            val due = m.settings.reminderHours * 3_600_000L
            if (now - m.turnStartedAt < due || (m.lastReminderAt != 0L && now - m.lastReminderAt < due)) continue
            val seat = Engine.withGame(s.game!!) { m.seatOfCiv(s.game!!.currentPlayerCiv.civID) } ?: continue
            m.lastReminderAt = now
            store.saveMeta(m)
            playChannel(m)?.sendMessage("🔔 <@${seat.userId}>, friendly reminder: it's still your turn in **${m.name}**.")
                ?.setActionRow(Button.primary(Panel.id(m.id, "play"), "Play my turn").withEmoji(Emoji.fromUnicode("🎮")))?.queue()
        }
    }

    private fun leave(hook: InteractionHook, s: Session, uid: String) {
        val seat = s.meta.seatOf(uid) ?: throw PlayerError("You're not in this game.")
        if (s.meta.status == Status.LOBBY) {
            s.meta.seats.remove(seat); store.saveMeta(s.meta); lobbyMessageEdit(s.meta)
            hook.editOriginal("You left the lobby.").queue(); return
        }
        val game = s.game!!
        val result = Engine.withGame(game) {
            seat.aiControlled = true
            val civ = GameOps.civOf(game, seat)
            s.meta.addEvent("🤖 ${civ.civName} is now run by the AI (<@$uid> left).")
            // If it's their turn, end it first (as a human), then hand the civ to the AI for good
            val r = if (game.currentPlayerCiv == civ) ctl.endTurn(s) else null
            civ.playerType = com.unciv.logic.civilization.PlayerType.AI
            store.saveGame(s)
            r
        }
        hook.editOriginal("👋 <@$uid> left — the AI now runs their civilization.").queue()
        if (result != null) announceTurn(s, result) else updateBoard(s)
    }

    // ================================================================== components & modals

    override fun onButtonInteraction(event: ButtonInteractionEvent) = onComponent(event)
    override fun onStringSelectInteraction(event: StringSelectInteractionEvent) = onComponent(event)

    private val lobbyVerbs = setOf("join", "leave", "nation", "pick", "start", "cancel", "set", "more", "xset", "xtog")

    private fun onComponent(event: GenericComponentInteractionCreateEvent) {
        val parts = event.componentId.split("|")
        if (parts.size < 3 || parts[0] != Panel.P) return
        val gid = parts[1]
        val verb = parts[2]
        val args = parts.drop(3)
        val s = store.sessions[gid] ?: return event.reply("That game no longer exists.").setEphemeral(true).queue()
        val uid = event.user.id

        if (verb in lobbyVerbs && s.meta.status == Status.LOBBY) return lobbyAction(event, s, verb, args)

        when (verb) {
            "play" -> return event.deferReply(true).queue { hook -> workers.submit { showPanel(hook, s, uid) } }
            "boardrefresh" -> { event.deferEdit().queue { hook -> workers.submit { safely(hook) { hook.editOriginal(boardEdit(s)).queue() } } }; return }
            "endgame" -> {
                event.deferEdit().queue()
                workers.submit {
                    s.meta.status = Status.ENDED
                    s.meta.winner = "Ended by the host."
                    s.game?.let { g -> Engine.withGame(g) { store.saveGame(s) } }
                    updateBoard(s)
                    playChannel(s.meta)?.sendMessage("🏁 **${s.meta.name}** was ended by the host.")?.queue()
                    store.archive(gid)
                    event.hook.editOriginal("Game ended.").setComponents().queue()
                }
                return
            }
            "propyes", "propno" -> return proposalAnswer(event, s, verb == "propyes", args)
        }

        val seat = s.meta.seatOf(uid) ?: return event.reply("You're not playing in this game.").setEphemeral(true).queue()
        if (s.game == null) return event.reply("This game hasn't started yet.").setEphemeral(true).queue()

        if (verb == "goto" || verb == "inspect") {
            val input = TextInput.create("tile", "Tile code or city name", TextInputStyle.SHORT)
                .setPlaceholder("e.g. D5").setRequiredRange(1, 40).build()
            val title = if (verb == "goto") "Move unit to…" else "Inspect which tile?"
            event.replyModal(Modal.create(Panel.id(gid, verb), title).addComponents(ActionRow.of(input)).build()).queue()
            return
        }
        val value = (event as? StringSelectInteractionEvent)?.values?.joinToString("\n")
        if (value == "none") return event.deferEdit().queue()
        // Popups: a "prompt" button (args = verb[, arg]) or a select option valued "ask:<verb>[:<arg>]"
        val ask = when {
            verb == "prompt" -> args
            // (any picked value: a multi-select sends its other picks too)
            value != null && value.split("\n").any { it.startsWith("ask:") } ->
                value.split("\n").first { it.startsWith("ask:") }.removePrefix("ask:").split(":", limit = 2)
            else -> null
        }
        if (ask != null && ask.isNotEmpty()) {
            val p = Extensions.prompt(ask[0]) ?: Prompt("Enter a value", "Value")
            val input = TextInput.create("text", p.label.take(45), if (p.long) TextInputStyle.PARAGRAPH else TextInputStyle.SHORT)
                .setPlaceholder(p.placeholder.take(100).ifEmpty { null }).setRequiredRange(1, if (p.long) 1000 else 100).build()
            event.replyModal(Modal.create(Panel.id(gid, *ask.toTypedArray()), p.title.take(45)).addComponents(ActionRow.of(input)).build()).queue()
            return
        }
        event.deferEdit().queue()
        workers.submit { panelAction(event.hook, s, seat, verb, args, value) }
    }

    override fun onModalInteraction(event: ModalInteractionEvent) {
        val parts = event.modalId.split("|")
        if (parts.size < 3 || parts[0] != Panel.P) return
        val s = store.sessions[parts[1]] ?: return event.reply("That game no longer exists.").setEphemeral(true).queue()
        val seat = s.meta.seatOf(event.user.id) ?: return event.reply("You're not playing in this game.").setEphemeral(true).queue()
        val value = (event.getValue("tile") ?: event.getValue("text"))?.asString ?: ""
        event.deferEdit().queue()
        workers.submit { panelAction(event.hook, s, seat, parts[2], parts.drop(3), value) }
    }

    private fun panelAction(hook: InteractionHook, s: Session, seat: Seat, verb: String, args: List<String>, value: String?) {
        val st = ctl.state(s.meta.id, seat.userId)
        val result = try {
            ctl.apply(s, seat, verb, args, value)
        } catch (e: PlayerError) {
            st.flash += "❌ ${e.message}"; null
        } catch (e: Exception) {
            log.error("Action {} failed", verb, e)
            st.flash += "💥 Something went wrong: ${e.javaClass.simpleName}. The game is safe; try again."; null
        }
        sendPanel(hook, s, seat, edit = true)
        if (result != null) announceTurn(s, result)
        while (true) { val p = ctl.outbox.poll() ?: break; postProposal(s, p) }
        if (verb in setOf("war", "end", "trade", "peace", "friend", "denounce", "dp_ok")) updateBoard(s)
    }

    private fun showPanel(hook: InteractionHook, s: Session, uid: String) {
        val seat = s.meta.seatOf(uid)
        if (seat == null) { hook.editOriginal("You're not playing in **${s.meta.name}**. Ask the host to start a new game you can join.").queue(); return }
        if (s.game == null) { hook.editOriginal("The game hasn't started yet.").queue(); return }
        sendPanel(hook, s, seat, edit = true)
    }

    private fun sendPanel(hook: InteractionHook, s: Session, seat: Seat, edit: Boolean) {
        try {
            val r = Engine.withGame(s.game!!) { Panel.render(s, seat, ctl.state(s.meta.id, seat.userId)) }
            val msg = MessageEditBuilder().setContent("").setEmbeds(r.embed).setComponents(r.rows).setAttachments(listOfNotNull(r.file)).build()
            hook.editOriginal(msg).queue({}, { e -> log.warn("panel edit failed: {}", e.message) })
        } catch (e: Exception) {
            log.error("Panel render failed", e)
            hook.editOriginal("💥 Couldn't draw your panel: ${e.message}").queue()
        }
    }

    // ================================================================== human <-> human proposals

    private fun postProposal(s: Session, p: Controller.Proposal) {
        val game = s.game ?: return
        val (fromName, toName) = Engine.withGame(game) { game.getCivilization(p.fromCiv).civName to game.getCivilization(p.toCiv).civName }
        val toSeat = s.meta.seatOfCiv(p.toCiv) ?: return
        val fromSeat = s.meta.seatOfCiv(p.fromCiv)
        val who = "**$fromName**${fromSeat?.let { " (<@${it.userId}>)" } ?: ""}"
        val (text, yes, no) = when (p.kind) {
            "peace" -> Triple("🕊️ <@${toSeat.userId}> — $who proposes peace with **$toName**.", "Accept peace", "Keep fighting")
            "friend" -> Triple("💛 <@${toSeat.userId}> — $who offers a Declaration of Friendship.", "Accept", "Decline")
            else -> Triple("🤝 <@${toSeat.userId}> — $who offers a deal:\n${p.text}", "Accept deal", "Decline")
        }
        playChannel(s.meta)?.sendMessage(text.take(1900))?.setActionRow(
            Button.success(Panel.id(s.meta.id, "propyes", p.id), yes),
            Button.danger(Panel.id(s.meta.id, "propno", p.id), no),
        )?.queue()
    }

    private fun proposalAnswer(event: GenericComponentInteractionCreateEvent, s: Session, accept: Boolean, args: List<String>) {
        val p = ctl.proposals[args.firstOrNull() ?: ""]
            ?: return event.editMessage("This offer has expired (the bot restarted or it was already answered).").setComponents().queue()
        val toSeat = s.meta.seatOfCiv(p.toCiv)
        if (toSeat?.userId != event.user.id) return event.reply("Only <@${toSeat?.userId}> can answer this.").setEphemeral(true).queue()
        event.deferEdit().queue()
        workers.submit {
            safely(event.hook) {
                val text = Engine.withGame(s.game!!) { ctl.answer(s, p, accept) }
                event.hook.editOriginal(text).setComponents().queue()
                updateBoard(s)
            }
        }
    }

    // ================================================================== spectators

    private fun spectate(hook: InteractionHook, s: Session, uid: String) {
        val game = s.game!!
        if (s.meta.seatOf(uid) != null) { showPanel(hook, s, uid); return }
        val showMap = !s.meta.settings.fogOfWar || s.meta.status == Status.ENDED
        val (embed, png) = Engine.withGame(game) {
            val eb = EmbedBuilder().setColor(Color(149, 165, 166)).setTitle("👁️ Spectating ${s.meta.name} — Turn ${game.turns + 1}")
            val public = GameOps.publicStatsVisible(game)
            eb.setDescription(GameOps.scoreboard(game).joinToString("\n") { (c, sc) ->
                "**${c.civName}**" + (if (public) " — $sc pts · ${c.cities.size} cities · ${c.units.getCivUnitsSize()} units" else "") + (if (!c.isAlive()) " ☠️" else "")
            } + if (showMap) "" else "\n\n🌫️ Fog of war is on, so the map stays hidden until the game ends.")
            if (showMap) eb.setImage("attachment://world.png")
            eb.build() to (if (showMap) MapRenderer.renderWhole(game.tileMap.values, null, 1) else null)
        }
        val msg = MessageEditBuilder().setEmbeds(embed).setAttachments(listOfNotNull(png?.let { FileUpload.fromData(it, "world.png") })).build()
        hook.editOriginal(msg).queue()
    }

    // ================================================================== misc

    private fun safely(hook: InteractionHook, block: () -> Unit) {
        try { block() } catch (e: PlayerError) {
            hook.sendMessage("❌ ${e.message}").setEphemeral(true).queue()
        } catch (e: Exception) {
            log.error("Handler failed", e)
            hook.sendMessage("💥 Something went wrong: ${e.message}").setEphemeral(true).queue()
        }
    }

    private fun helpEmbed() = EmbedBuilder().setColor(Color(46, 134, 193)).setTitle("🌍 How to play").setDescription("""
        **Start:** `/civ new` opens a lobby. Friends click **Join** (and 🏳️ pick a nation). The host sets map, difficulty and AI, ⚙️ **Settings** has speed, city-states, starting era, victory types, fog, world wrap and AFK auto-skip. Then **Start**.

        **Your turn:** the bot pings you → **🎮 Play my turn** opens your private panel (only you see it — real fog of war). Use the dropdown at the top to switch tabs:
        ⚔️ **Units** — arrows to step, 🎯 **Go to** a tile code like `D5`; actions menu: found city, improvements, fortify, explore, automate, attack, promote, upgrade, escort…
        🏙️ **Cities** — production (📋 queue mode), buy with gold/faith, bombard, focus, buy tiles, raze/annex
        🔬 **Research** — next tech (shows what it unlocks) or a long-term goal
        📜 **Policies** · 🙏 **Religion** — pantheon, name your religion, pick beliefs
        🤝 **Diplomacy** — build deals (gold, luxuries, resources, techs, embassies, open borders…), war/peace, friendship, denounce
        🏰 **City-states** — gifts, protection, tribute, quests
        📊 **Overview** — empire stats, happiness, resources, rankings, Great People
        🗺️ **Map** — scroll around, 🔍 inspect any tile, 🌍 world map
        Also: 🏗️ **Production** (queue editing, buying, details) · 👥 **Citizens** · 🏙️ **Cities overview** · 🏛️ **Wonders** · 🎖️ **Military** (nukes, air units) · 🧭 **Relations** (opinions, deals, demands) · 🕵️ **Espionage** · 🗳️ **World Congress** · 📨 **Inbox** (AI demands, offers, events) · 📰 **News** · ✨ **Beliefs** · ⛪ **Found religion** · 🔬 **Research queue** · 📊 **Stats** & charts · 🎞️ **Replay** · 🏆 **Victory** · 📖 **Civilopedia**
        ↩️ **Undo** is the first entry in the Unit/City action menus.

        **🤖 Auto-pick** lets the AI fill in anything you skipped. **✅ End turn** passes play on; AIs move automatically.

        `/civ play` panel · `/civ board` re-post board · `/civ spectate` watch · `/civ leave` hand your civ to the AI
        Host: `/civ kick @player` · `/civ host @player` · `/civ end` · `/civ lobby` (mods, map, scenario) · `/civ setup` (every other option) · `/civ save import` · `/civ espionage`
        Admins: `/civ mod` (install Unciv mods from GitHub) · `/civ map` (upload custom maps) · Anyone: `/civ wiki` · Players: `/civ save export` (download the game for desktop Unciv)
    """.trimIndent()).build()
}

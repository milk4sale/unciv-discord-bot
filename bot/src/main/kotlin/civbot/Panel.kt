package civbot

import com.unciv.logic.GameInfo
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.map.HexCoord
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.logic.map.tile.Tile
import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.entities.MessageEmbed
import net.dv8tion.jda.api.entities.emoji.Emoji
import net.dv8tion.jda.api.interactions.components.ActionRow
import net.dv8tion.jda.api.interactions.components.buttons.Button
import net.dv8tion.jda.api.interactions.components.selections.SelectOption
import net.dv8tion.jda.api.interactions.components.selections.StringSelectMenu
import net.dv8tion.jda.api.utils.FileUpload
import java.awt.Color

enum class Tab(val label: String, val emoji: String, val hint: String) {
    UNITS("Units", "⚔️", "Move, fight, build, settle"),
    CITIES("Cities", "🏙️", "Production, buying, focus, tiles"),
    TECH("Research", "🔬", "Next tech and long-term goals"),
    POLICIES("Policies", "📜", "Social policies"),
    RELIGION("Religion", "🙏", "Pantheon, beliefs, faith"),
    DIPLOMACY("Diplomacy", "🤝", "Trade, war, peace, friendship"),
    CITYSTATES("City-states", "🏰", "Gifts, protection, tribute, quests"),
    OVERVIEW("Overview", "📊", "Empire stats, rankings, great people"),
    MAP("Map", "🗺️", "Look around, inspect tiles, world map"),
}

/** Per-player UI state for the private control panel (kept in memory only). */
class PanelState {
    /** Tab id: a [Tab] name or an extension tab id */
    var tab = Tab.UNITS.name
    /** Scratch state for extensions (see ActionCtx.ext) */
    val ext = HashMap<String, Any>()
    /** Dropdown paging: select key ("verb|args") -> page the player navigated to */
    val pages = HashMap<String, Int>()
    /** Option ids shown in each dropdown at the last render (multi-selects keep picks from other pages) */
    val visible = HashMap<String, List<String>>()
    var unitId: Int? = null
    var cityId: String? = null
    var otherCivId: String? = null
    var cityStateId: String? = null
    var confirmEnd = false
    var flash = ArrayList<String>()
    /** Window shown in the last render, so tile codes typed by the player resolve to the same tiles */
    var window: MapWindow? = null
    // Map tab
    var mapCenter: HexCoord? = null
    var inspect: HexCoord? = null
    var wholeMap = false
    // Cities tab: production picks add to the queue instead of replacing
    var queueMode = false
    // Diplomacy trade builder
    var give: List<String> = emptyList()
    var ask: List<String> = emptyList()
    // Religion founding
    var religionName: String? = null
    var beliefs = HashMap<Int, String>()
}

class Rendered(val embed: MessageEmbed, val rows: List<ActionRow>, val file: FileUpload?)

/** Builds the private (ephemeral) control panel. Call on the engine thread. */
object Panel {
    const val P = "c" // component id prefix
    fun id(gid: String, vararg parts: String) = (listOf(P, gid) + parts).joinToString("|").take(100)

    private fun options(choices: List<Choice>, selected: Collection<String> = emptyList()): List<SelectOption> =
        choices.take(25).map { c ->
            var o = SelectOption.of(c.label.take(100).ifEmpty { "—" }, c.id.take(100))
            if (c.description.isNotBlank()) o = o.withDescription(c.description.take(100))
            if (c.emoji != null) o = o.withEmoji(Emoji.fromUnicode(c.emoji))
            if (c.id in selected) o = o.withDefault(true)
            o
        }

    /** Panel state of the render in progress (renders run one at a time on the engine thread). */
    private var renderState: PanelState? = null
    private const val PAGE = 23

    /** Key that identifies a dropdown across renders: the "verb|args" part of its component id. */
    fun pageKey(customId: String) = customId.split("|").drop(2).joinToString("|")

    /**
     * Discord dropdowns hold 25 options. Longer lists are split into pages of [PAGE] with
     * "◀ Previous page" / "▶ More…" entries inside the dropdown (values "pg:<key>:<page>").
     */
    private fun paged(customId: String, placeholder: String, choices: List<Choice>, selected: Collection<String>): Pair<List<Choice>, String> {
        val key = pageKey(customId)
        val st = renderState
        if (choices.size <= 25) {
            st?.visible?.put(key, choices.map { it.id })
            return choices to placeholder
        }
        val pages = (choices.size + PAGE - 1) / PAGE
        val selectedPage = choices.indexOfFirst { it.id in selected }.takeIf { it >= 0 }?.div(PAGE)
        val page = (st?.pages?.get(key) ?: selectedPage ?: 0).coerceIn(0, pages - 1)
        val slice = choices.subList(page * PAGE, minOf(choices.size, (page + 1) * PAGE))
        st?.visible?.put(key, slice.map { it.id })
        val out = ArrayList<Choice>()
        if (page > 0) out += Choice("pg:$key:${page - 1}", "◀ Previous page", "page $page of $pages", "◀️")
        out += slice
        if (page < pages - 1) out += Choice("pg:$key:${page + 1}", "▶ More… (page ${page + 2} of $pages)", "${choices.size} options in total", "▶️")
        return out to "$placeholder (${page + 1}/$pages)"
    }

    private fun select(customId: String, placeholder: String, choices: List<Choice>, selected: String? = null, enabled: Boolean = true): StringSelectMenu {
        if (choices.isEmpty())
            return StringSelectMenu.create(customId).setPlaceholder(placeholder.take(150)).addOption("—", "none").setDisabled(true).build()
        val (shown, ph) = paged(customId, placeholder, choices, listOfNotNull(selected))
        return StringSelectMenu.create(customId).setPlaceholder(ph.take(150))
            .addOptions(options(shown, listOfNotNull(selected))).setDisabled(!enabled).build()
    }

    private fun multiSelect(customId: String, placeholder: String, choices: List<Choice>, selected: List<String>, enabled: Boolean): StringSelectMenu {
        if (choices.isEmpty())
            return StringSelectMenu.create(customId).setPlaceholder(placeholder.take(150)).addOption("—", "none").setDisabled(true).build()
        val (shown, ph) = paged(customId, placeholder, choices, selected)
        val picked = selected.count { s -> choices.any { it.id == s } && shown.none { it.id == s } }
        val opts = options(shown, selected)
        return StringSelectMenu.create(customId).setPlaceholder((if (picked > 0) "$ph · $picked picked on other pages" else ph).take(150))
            .addOptions(opts).setRequiredRange(0, opts.size).setDisabled(!enabled).build()
    }

    private fun btn(gid: String, verb: String, label: String, emoji: String, style: Int = 2, enabled: Boolean = true, vararg args: String): Button {
        val id = id(gid, verb, *args)
        val b = when (style) { 1 -> Button.primary(id, label); 3 -> Button.success(id, label); 4 -> Button.danger(id, label); else -> Button.secondary(id, label) }
        return b.withEmoji(Emoji.fromUnicode(emoji)).withDisabled(!enabled)
    }

    private fun color(civ: Civilization): Color {
        val c = civ.nation.getOuterColor()
        return Color((c.r * 255).toInt().coerceIn(0, 255), (c.g * 255).toInt().coerceIn(0, 255), (c.b * 255).toInt().coerceIn(0, 255))
    }

    fun statsLine(civ: Civilization): String {
        val s = civ.stats.statsForNextTurn
        fun signed(v: Float) = (if (v >= 0) "+" else "") + v.toInt()
        val culture = "${civ.policies.storedCulture}/${civ.policies.getCultureNeededForNextPolicy()}"
        return "💰 ${civ.gold} (${signed(s.gold)}) · 🔬 ${signed(s.science)} · 🎭 $culture (${signed(s.culture)}) · " +
            "😊 ${civ.getHappiness()}" + (if (civ.gameInfo.isReligionEnabled()) " · 🙏 ${civ.religionManager.storedFaith}" else "")
    }

    fun render(session: Session, seat: Seat, st: PanelState): Rendered {
        renderState = st
        val game = session.game!!
        val meta = session.meta
        val civ = GameOps.civOf(game, seat)
        val gid = meta.id
        val myTurn = game.currentPlayerCiv == civ && civ.isAlive() && meta.status == Status.RUNNING
        val eb = EmbedBuilder().setColor(color(civ))
        eb.setTitle("${GameOps.clean(civ.nation.getLeaderDisplayName()).ifEmpty { civ.civName }} — Turn ${game.turns + 1} · ${GameOps.yearText(game)}")

        val desc = StringBuilder()
        if (st.flash.isNotEmpty()) { desc.append(st.flash.joinToString("\n")).append("\n\n"); st.flash.clear() }
        desc.append(
            when {
                !civ.isAlive() -> "☠️ Your civilization has been defeated."
                meta.status == Status.ENDED -> "🏁 The game is over."
                myTurn -> "🟢 **Your turn.**"
                else -> {
                    val cur = meta.seatOfCiv(game.currentPlayerCiv.civID)
                    "⏳ Waiting for ${cur?.let { "<@${it.userId}>" } ?: game.currentPlayerCiv.civName} (${game.currentPlayerCiv.civName}). You can look around."
                }
            }
        ).append('\n').append(statsLine(civ))
        civ.tech.currentTechnologyName()?.let { desc.append("\n🔬 ").append(it).append(" (").append(civ.tech.turnsToTech(it)).append(" turns)") }
        eb.setDescription(desc.toString().take(4000))
        if (myTurn && st.tab != Tab.OVERVIEW.name) {
            val news = GameOps.turnNotifications(civ, 6)
            if (news.isNotEmpty()) eb.addField("📰 News", news.joinToString("\n").take(1024), false)
        }

        val rows = ArrayList<ActionRow>()
        rows += ActionRow.of(StringSelectMenu.create(id(gid, "tab")).setPlaceholder("Open a tab…")
            .addOptions((Tab.entries.map { t ->
                SelectOption.of(t.label, t.name).withEmoji(Emoji.fromUnicode(t.emoji)).withDescription(t.hint).withDefault(t.name == st.tab)
            } + Extensions.all.flatMap { it.tabs }.filter { runCatching { it.visible(civ) }.getOrDefault(false) }.map { t ->
                SelectOption.of(t.label, t.id).withEmoji(Emoji.fromUnicode(t.emoji)).withDescription(t.hint.take(100)).withDefault(t.id == st.tab)
            }).take(25)).build())

        // Decisions that come before anything else
        var file: FileUpload? = null
        val conquest = if (myTurn) GameOps.pendingConquest(civ) else null
        if (conquest != null) {
            eb.addField("🏰 You captured ${conquest.name}!", "Unciv's default: **${GameOps.conquestStatus(conquest)}**. Keep that or choose something else:", false)
            rows += ActionRow.of(select(id(gid, "conq", conquest.id), "Decide the city's fate…", GameOps.conquestChoices(civ, conquest)))
            file = mapFile(game, civ, st, conquest.getCenterTile(), Overlay(selectedTile = conquest.getCenterTile()))
            eb.setImage("attachment://map.png")
            return Rendered(eb.build(), rows, file)
        }

        val advisor = btn(gid, "advisor", "Auto-pick", "🤖", enabled = myTurn)
        val end = when {
            !myTurn -> Button.secondary(id(gid, "end"), "Not your turn").withEmoji(Emoji.fromUnicode("⏳")).asDisabled()
            st.confirmEnd -> Button.danger(id(gid, "end", "force"), "End anyway").withEmoji(Emoji.fromUnicode("⚠️"))
            else -> Button.success(id(gid, "end"), "End turn").withEmoji(Emoji.fromUnicode("✅"))
        }
        val endRow = ArrayList<Button>()
        var includeAdvisor = true

        when (Tab.entries.firstOrNull { it.name == st.tab }) {
            Tab.UNITS -> {
                val units = GameOps.unitsOf(civ)
                var unit: MapUnit? = st.unitId?.let { GameOps.unitById(civ, it) }
                if (unit == null && units.isNotEmpty()) unit = GameOps.nextIdleUnit(civ, null) ?: units.first()
                st.unitId = unit?.id
                val center = unit?.getTile() ?: GameOps.focusTile(civ)
                val window = MapWindow(center.position)
                val reach = if (unit != null && myTurn) GameOps.reachableTiles(unit) else emptySet()
                val atk = if (unit != null && myTurn) GameOps.attackableTargets(unit).map { it.tileToAttack }.toSet() else emptySet()
                file = mapFile(game, civ, st, center, Overlay(selectedUnit = unit, reachable = reach, attackable = atk), window)
                if (unit != null) {
                    val t = unit.getTile()
                    eb.addField("${GameOps.unitEmoji(unit)} ${unit.label()}",
                        "❤️ ${unit.health}/100 · 👣 ${GameOps.movesText(unit)} · ${GameOps.unitStatus(unit)}" +
                            "\n⚔️ strength ${unit.baseUnit.strength}" + (if (unit.baseUnit.rangedStrength > 0) " · 🏹 ranged ${unit.baseUnit.rangedStrength} (range ${unit.getRange()})" else "") +
                            (if (unit.isEscorting()) " · 🤝 escorting" else "") +
                            "\n📍 ${window.code(t.position.x, t.position.y)} — ${GameOps.tileDescription(t, civ)}" +
                            (if (unit.promotions.promotions.isNotEmpty()) "\n⭐ ${unit.promotions.promotions.joinToString()}" else "") +
                            (if (reach.isNotEmpty()) "\n_Lighter tiles = reachable this turn. Red = attackable._" else ""), false)
                } else eb.addField("No units", "Build some in the 🏙️ Cities tab.", false)
                val unitChoices = units.map { u ->
                    val code = window.code(u.getTile().position.x, u.getTile().position.y) ?: "off-map"
                    Choice("u:${u.id}", "${u.label()} — ${GameOps.unitStatus(u)}", "$code · ${GameOps.movesText(u)} moves · ❤️${u.health}", GameOps.unitEmoji(u))
                }
                rows += ActionRow.of(select(id(gid, "selunit"), "Select a unit (${units.size})", unitChoices, unit?.let { "u:${it.id}" }))
                val acts = if (unit != null) GameOps.unitChoices(unit) else emptyList()
                rows += ActionRow.of(select(id(gid, "uact"), "Unit actions…", acts, enabled = myTurn && unit != null))
                val canMove = myTurn && unit != null && unit.hasMovement()
                fun dir(clock: Int, arrow: String) = Button.secondary(id(gid, "step", clock.toString()), arrow).withDisabled(!canMove)
                rows += ActionRow.of(dir(10, "↖"), dir(12, "⬆"), dir(2, "↗"),
                    btn(gid, "goto", "Go to…", "🎯", 1, canMove), btn(gid, "nextunit", "Next", "⏭️"))
                endRow += listOf(dir(8, "↙"), dir(6, "⬇"), dir(4, "↘"))
            }

            Tab.CITIES -> {
                val cities = GameOps.citiesOf(civ)
                val city = cities.firstOrNull { it.id == st.cityId } ?: cities.firstOrNull()
                st.cityId = city?.id
                if (city != null) {
                    val cc = city.cityConstructions
                    val cur = cc.currentConstructionName()
                    val growth = city.population.getNumTurnsToStarvation()?.let { "starving in $it turns" }
                        ?: city.population.getNumTurnsToNewPopulation()?.let { "grows in $it turns" } ?: "not growing"
                    val s = city.cityStats.currentCityStats
                    eb.addField("${if (city.isCapital()) "⭐ " else ""}${city.name} (pop ${city.population.population})${if (city.isPuppet) " — puppet" else ""}",
                        "🍞 +${s.food.toInt()} ($growth) · ⚙️ +${s.production.toInt()} · 💰 +${s.gold.toInt()} · 🔬 +${s.science.toInt()} · 🎭 +${s.culture.toInt()} · 🙏 +${s.faith.toInt()}\n" +
                            "🏗️ Building: **${cur.ifEmpty { "nothing!" }}**" + (if (cur.isNotEmpty()) " (${cc.turnsToConstruction(cur)} turns)" else "") +
                            Features.queueText(city) +
                            "\n🎯 Focus: ${GameOps.focusName(city.getCityFocus())} · ❤️ ${city.health} HP · 🛡️ ${city.getStrength()}" +
                            (if (city.religion.getMajorityReligion() != null) " · 🙏 ${city.religion.getMajorityReligion()!!.getReligionDisplayName()}" else "") +
                            "\n🏠 ${cc.getBuiltBuildings().filter { !it.isAnyWonder() }.count()} buildings" +
                            cc.getBuiltBuildings().filter { it.isAnyWonder() }.joinToString { it.name }.let { if (it.isEmpty()) "" else " · 🏛️ $it" } +
                            (if (city.isBeingRazed) "\n🔥 Being razed" else ""), false)
                    file = mapFile(game, civ, st, city.getCenterTile(), Overlay(selectedTile = city.getCenterTile()))
                } else {
                    eb.addField("No cities yet", "Select a Settler in ⚔️ Units and choose **Found city**.", false)
                    file = mapFile(game, civ, st, GameOps.focusTile(civ), Overlay())
                }
                rows += ActionRow.of(select(id(gid, "selcity"), "Select a city (${cities.size})",
                    cities.map { c -> Choice("city:${c.id}", c.name, "pop ${c.population.population} · ${c.cityConstructions.currentConstructionName().ifEmpty { "idle" }}", if (c.isCapital()) "⭐" else "🏙️") },
                    city?.let { "city:${it.id}" }))
                val prod = if (city != null) GameOps.productionChoices(city) else emptyList()
                rows += ActionRow.of(select(id(gid, "prod"), if (st.queueMode) "➕ Add to queue…" else "Change production…", prod,
                    if (st.queueMode) null else city?.let { "prod:" + it.cityConstructions.currentConstructionName() }, enabled = myTurn && city != null && !city.isPuppet))
                val cityActs = ArrayList<Choice>()
                if (city != null && myTurn) {
                    for (t in GameOps.bombardTargets(city)) {
                        val target = t.militaryUnit ?: t.civilianUnit
                        cityActs += Choice("bomb:${t.position.x},${t.position.y}", "Bombard ${target?.civ?.civName ?: ""} ${target?.label() ?: "target"}", "City ranged attack", "🎯")
                    }
                    cityActs += GameOps.buyChoices(city).take(9)
                    cityActs += Features.faithBuyChoices(city).take(4)
                    cityActs += Extensions.all.flatMap { runCatching { it.cityChoices(city, st.window) }.getOrDefault(emptyList()) }
                    cityActs += Features.cityExtraChoices(city, st.window)
                }
                cityActs.sortBy { if (it.id == "do:cp_undo") 0 else 1 } // ↩️ Undo always first
                rows += ActionRow.of(select(id(gid, "cact"), if (cityActs.isEmpty()) "No city actions right now" else "Buy, bombard, focus, tiles, raze…", cityActs.take(25), enabled = myTurn))
                endRow += listOf(
                    btn(gid, "cprev", "Prev", "◀️", enabled = cities.size > 1),
                    btn(gid, "cnext", "Next", "▶️", enabled = cities.size > 1),
                    btn(gid, "qmode", if (st.queueMode) "Queue: on" else "Queue: off", "📋", if (st.queueMode) 1 else 2),
                )
            }

            Tab.TECH -> {
                val tm = civ.tech
                val cur = tm.currentTechnologyName()
                val curTech = cur?.let { game.ruleset.technologies[it] }
                eb.addField("Research",
                    (if (tm.freeTechs > 0) "🎁 **You have ${tm.freeTechs} free tech(s)!** Pick one below.\n" else "") +
                        (cur?.let { "Now: **$it** (${tm.turnsToTech(it)} turns)" } ?: "Nothing being researched!") +
                        (curTech?.let { "\nUnlocks: " + GameOps.techUnlocks(civ, it).ifEmpty { "—" } } ?: "") +
                        (if (tm.techsToResearch.size > 1) "\nGoal: **${tm.techsToResearch.last()}** (${tm.techsToResearch.size} techs)" else "") +
                        "\nEra: ${civ.getEra().name} · ${tm.techsResearched.size} techs known", false)
                rows += ActionRow.of(select(id(gid, "tech"), "Research next…", GameOps.researchChoices(civ), cur?.let { "tech:$it" }, enabled = myTurn))
                rows += ActionRow.of(select(id(gid, "goal"), "Set a long-term goal…", GameOps.researchGoalChoices(civ), enabled = myTurn))
                file = wholeMapFile(game, civ)
            }

            Tab.POLICIES -> {
                val pm = civ.policies
                val adopted = GameOps.adoptedPolicies(civ).filter { !it.endsWith("Complete") }
                eb.addField("Social policies",
                    "🎭 Culture: ${pm.storedCulture}/${pm.getCultureNeededForNextPolicy()}" +
                        (if (pm.freePolicies > 0) " · 🎁 ${pm.freePolicies} free" else "") +
                        (if (pm.canAdoptPolicy()) "\n**You can adopt a policy now!**" else "") +
                        "\nAdopted (${adopted.size}): " + adopted.takeLast(12).joinToString().ifEmpty { "none yet" }, false)
                rows += ActionRow.of(select(id(gid, "pol"), if (pm.canAdoptPolicy()) "Adopt a policy…" else "Not enough culture yet",
                    GameOps.policyChoices(civ), enabled = myTurn))
                file = wholeMapFile(game, civ)
            }

            Tab.RELIGION -> {
                if (!game.isReligionEnabled()) eb.addField("Religion", "Religion is turned off in this game.", false)
                else {
                    eb.addField("🙏 Religion", Features.religionInfo(civ).take(1024), false)
                    val types = Features.pendingBeliefTypes(civ)
                    when {
                        Features.pantheonAvailable(civ) ->
                            rows += ActionRow.of(select(id(gid, "pantheon"), "Choose your pantheon belief…", Features.beliefChoices(civ, com.unciv.models.ruleset.BeliefType.Pantheon), enabled = myTurn))
                        types != null && types.size <= 2 -> {
                            val founding = civ.religionManager.religionState == com.unciv.logic.civilization.managers.ReligionState.FoundingReligion
                            if (founding) rows += ActionRow.of(select(id(gid, "relname"), "Name your religion…", Features.religionNameChoices(civ), st.religionName?.let { "r:$it" }, enabled = myTurn))
                            types.forEachIndexed { i, type ->
                                rows += ActionRow.of(select(id(gid, "belief", i.toString()), "${type.name} belief…", Features.beliefChoices(civ, type),
                                    st.beliefs[i]?.let { "b:$it" }, enabled = myTurn))
                            }
                            endRow += btn(gid, "relconfirm", if (founding) "Found religion" else "Enhance religion", "⛪", 3, myTurn)
                        }
                        types != null -> eb.addField("Beliefs", "You have ${types.size} beliefs to pick — press 🤖 Auto-pick and the advisor will choose them.", false)
                    }
                }
                file = wholeMapFile(game, civ)
            }

            Tab.DIPLOMACY -> {
                val others = GameOps.knownMajorCivs(civ).filter { !it.isCityState }
                val other = others.firstOrNull { it.civID == st.otherCivId } ?: others.firstOrNull()
                if (other?.civID != st.otherCivId) { st.give = emptyList(); st.ask = emptyList() }
                st.otherCivId = other?.civID
                val lines = others.take(15).joinToString("\n") { o ->
                    val who = meta.seatOfCiv(o.civID)?.let { " (<@${it.userId}>)" } ?: " (AI)"
                    "**${o.civName}**$who — ${GameOps.relationText(civ, o)} · ${o.calculateTotalScore().toInt()} pts"
                }
                eb.addField("Known civilizations", lines.ifEmpty { "You haven't met anyone yet. Explore!" }, false)
                if (other != null && (st.give.isNotEmpty() || st.ask.isNotEmpty()))
                    eb.addField("📝 Draft deal", Features.describeTrade(Features.buildTrade(civ, other, st.give, st.ask)), false)
                rows += ActionRow.of(select(id(gid, "selciv"), "Choose a civilization…",
                    others.map { Choice("civ:${it.civID}", it.civName, GameOps.relationText(civ, it).take(100), if (civ.isAtWarWith(it)) "⚔️" else "🤝") },
                    other?.let { "civ:${it.civID}" }))
                val (giveC, askC) = if (other != null) Features.tradeChoices(civ, other) else emptyList<Choice>() to emptyList()
                rows += ActionRow.of(multiSelect(id(gid, "give"), "You give…", giveC, st.give, myTurn))
                rows += ActionRow.of(multiSelect(id(gid, "ask"), "You ask for…", askC, st.ask, myTurn))
                val atWar = other != null && civ.isAtWarWith(other)
                endRow += btn(gid, "trade", "Propose deal", "🤝", 1, myTurn && other != null && (st.give.isNotEmpty() || st.ask.isNotEmpty()))
                endRow += if (atWar) btn(gid, "peace", "Peace", "🕊️", 3, myTurn)
                else btn(gid, "war", "War", "🔥", 4, myTurn && other != null)
                endRow += if (other != null && Features.friendshipAvailable(civ, other)) btn(gid, "friend", "Friendship", "💛", 2, myTurn)
                else btn(gid, "denounce", "Denounce", "😠", 2, myTurn && other != null && Features.canDenounce(civ, other))
                includeAdvisor = false
                file = wholeMapFile(game, civ)
            }

            Tab.CITYSTATES -> {
                val css = Features.cityStates(civ)
                val cs = css.firstOrNull { it.civID == st.cityStateId } ?: css.firstOrNull()
                st.cityStateId = cs?.civID
                if (cs == null) eb.addField("🏰 City-states", "You haven't met any city-states yet. Explore!", false)
                else {
                    eb.addField("🏰 ${cs.civName}", Features.cityStateInfo(civ, cs).take(1024), false)
                    val others = css.filter { it != cs }.take(12).joinToString("\n") { o ->
                        "${o.civName} — ${o.getDiplomacyManager(civ)?.getInfluence()?.toInt() ?: 0} influence" + (o.allyCiv?.let { " · ally: ${it.civName}" } ?: "")
                    }
                    if (others.isNotEmpty()) eb.addField("Others", others, false)
                }
                rows += ActionRow.of(select(id(gid, "selcs"), "Choose a city-state…",
                    css.map { Choice("cs:${it.civID}", it.civName, "${it.cityStateType.name} · influence ${it.getDiplomacyManager(civ)?.getInfluence()?.toInt() ?: 0}", "🏰") },
                    cs?.let { "cs:${it.civID}" }))
                rows += ActionRow.of(select(id(gid, "csact"), "Gift, protect, demand tribute…", cs?.let { Features.cityStateChoices(civ, it) } ?: emptyList(), enabled = myTurn))
                if (cs != null) {
                    endRow += if (civ.isAtWarWith(cs)) btn(gid, "peace", "Peace", "🕊️", 3, myTurn, "cs")
                    else btn(gid, "war", "War", "🔥", 4, myTurn, "cs")
                }
                file = cs?.getCapital()?.let { mapFile(game, civ, st, it.getCenterTile(), Overlay(selectedTile = it.getCenterTile())) } ?: wholeMapFile(game, civ)
            }

            Tab.OVERVIEW -> {
                for ((name, value) in Features.overviewFields(civ)) eb.addField(name, value.take(1024), false)
                val gp = Features.greatPersonChoices(civ)
                if (gp.isNotEmpty()) rows += ActionRow.of(select(id(gid, "gp"), "🌟 Choose your free Great Person…", gp, enabled = myTurn))
                file = wholeMapFile(game, civ)
            }

            Tab.MAP -> {
                includeAdvisor = false
                val center = st.mapCenter ?: GameOps.focusTile(civ).position
                st.mapCenter = center
                val inspectTile = st.inspect?.let { game.tileMap.getIfTileExistsOrNull(it.x, it.y) }
                if (st.wholeMap) {
                    file = wholeMapFile(game, civ)
                    eb.addField("🌍 World map", "Everything you've explored. Switch back to 🔎 Zoomed to inspect tiles.", false)
                } else {
                    val window = MapWindow(center)
                    file = mapFile(game, civ, st, game.tileMap[center.x, center.y], Overlay(selectedTile = inspectTile), window)
                    if (inspectTile != null) {
                        val (title, body) = Features.inspect(inspectTile, civ, window.code(inspectTile.position.x, inspectTile.position.y) ?: "tile")
                        eb.addField(title, body.take(1024), false)
                    } else eb.addField("🗺️ Map", "Pan with the arrows, 🔍 **Inspect** a tile by its code, or 🏠 jump back home.", false)
                }
                fun pan(clock: Int, arrow: String) = Button.secondary(id(gid, "pan", clock.toString()), arrow).withDisabled(st.wholeMap)
                rows += ActionRow.of(pan(10, "↖"), pan(12, "⬆"), pan(2, "↗"),
                    btn(gid, "home", "Home", "🏠"), btn(gid, "inspect", "Inspect…", "🔍", 1, !st.wholeMap))
                endRow += listOf(pan(8, "↙"), pan(6, "⬇"), pan(4, "↘"),
                    btn(gid, "whole", if (st.wholeMap) "Zoomed" else "World", if (st.wholeMap) "🔎" else "🌍"))
            }
            null -> {
                val ext = Extensions.tab(st.tab)
                if (ext == null) { st.tab = Tab.UNITS.name; return render(session, seat, st) }
                val tc = TabCtx(session, seat, st, game, civ, myTurn, eb, rows, endRow)
                try { ext.first.renderTab(st.tab, tc) } catch (e: PlayerError) { eb.addField("❌", e.message ?: "", false) }
                file = tc.file
                includeAdvisor = tc.includeAdvisor
            }
        }

        val warnings = if (myTurn) GameOps.turnWarnings(civ) else emptyList()
        if (warnings.isNotEmpty() && st.tab != Tab.OVERVIEW.name)
            eb.addField(if (st.confirmEnd) "⚠️ Still to do (click End anyway to skip)" else "📝 This turn", warnings.joinToString("\n") { "• $it" }, false)
        eb.setFooter("Game ${meta.id} · tiles are labelled like D5 · only you can see this panel")
        if (file != null) eb.setImage("attachment://map.png")

        if (includeAdvisor) endRow += advisor
        endRow += end
        rows += ActionRow.of(endRow.takeLast(5))
        return Rendered(eb.build(), rows.take(5), file)
    }

    // Public helpers for extensions (TabCtx)
    fun selectPublic(customId: String, placeholder: String, choices: List<Choice>, selected: String?, enabled: Boolean) = select(customId, placeholder, choices, selected, enabled)
    fun multiSelectPublic(customId: String, placeholder: String, choices: List<Choice>, selected: List<String>, enabled: Boolean) = multiSelect(customId, placeholder, choices, selected, enabled)
    fun btnPublic(gid: String, verb: String, label: String, emoji: String, style: Int, enabled: Boolean, vararg args: String) = btn(gid, verb, label, emoji, style, enabled, *args)
    fun mapFilePublic(game: GameInfo, civ: Civilization, st: PanelState, center: Tile, overlay: Overlay) = mapFile(game, civ, st, center, overlay)
    fun wholeMapFilePublic(game: GameInfo, civ: Civilization) = wholeMapFile(game, civ)

    private fun mapFile(game: GameInfo, civ: Civilization, st: PanelState, center: Tile,
                        overlay: Overlay, window: MapWindow = MapWindow(center.position)): FileUpload {
        st.window = window
        val png = MapRenderer.renderWindow(game.tileMap.values, civ, window, overlay)
        return FileUpload.fromData(png, "map.png")
    }

    private fun wholeMapFile(game: GameInfo, civ: Civilization): FileUpload =
        FileUpload.fromData(MapRenderer.renderWhole(game.tileMap.values, civ, scale = 1), "map.png")
}

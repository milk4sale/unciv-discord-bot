package civbot

import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.Notification
import com.unciv.logic.civilization.diplomacy.DiplomacyFlags
import com.unciv.logic.civilization.diplomacy.RelationshipLevel
import com.unciv.logic.map.tile.Tile
import com.unciv.logic.trade.TradeOfferType
import com.unciv.models.Religion
import com.unciv.models.ruleset.Policy.PolicyBranchType
import com.unciv.models.ruleset.tile.ResourceType
import com.unciv.models.ruleset.tile.TileResource
import com.unciv.models.ruleset.tile.ResourceSupplyList
import com.unciv.models.ruleset.unique.UniqueType
import com.unciv.models.stats.Stat
import com.unciv.ui.screens.victoryscreen.RankingType
import net.dv8tion.jda.api.interactions.components.ActionRow
import net.dv8tion.jda.api.utils.FileUpload
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.geom.Path2D
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * InfoPlus, part 2 — the 📊 Stats tab (Unciv's EmpireOverviewScreen pages + VictoryScreen Demographics/Charts)
 * and the 🎞️ Replay tab (VictoryScreenReplay). Routed from [InfoPlus].
 */
object InfoStats {
    const val STATS = "STATS"
    const val REPLAY = "REPLAY"

    enum class View(val key: String, val label: String, val emoji: String, val hint: String) {
        BREAKDOWN("econ", "Stats breakdown", "💰", "Gold, science, culture, faith, happiness, score, unit supply, great people"),
        RESOURCES("res", "Resources by origin", "💎", "Where each luxury and strategic resource comes from"),
        RELIGION("rel", "Religions", "🙏", "Every religion: founder, holy city, followers, beliefs"),
        POLITICS("pol", "Global politics", "🌐", "Wars, friendships, policy branches, wonders of known civs"),
        LOG("log", "Notification log", "📜", "Everything that happened to you, turn by turn"),
        DEMOGRAPHICS("demo", "Demographics", "🎯", "Your rank, the best, the average and the worst per stat"),
        CHARTS("chart", "Charts", "📈", "A ranking stat of every civ over the turns"),
    }

    class StatsState(var view: String = View.BREAKDOWN.key, var page: Int = 0, var ranking: String = RankingType.Score.name)
    class ReplayState(var turn: Int? = null)

    fun stats(st: PanelState) = st.ext.getOrPut("ip.stats") { StatsState() } as StatsState
    fun replay(st: PanelState) = st.ext.getOrPut("ip.replay") { ReplayState() } as ReplayState
    private fun viewOf(s: StatsState) = View.entries.firstOrNull { it.key == s.view } ?: View.BREAKDOWN

    private fun tx(s: String) = InfoPlus.tx(s)
    private fun signed(v: Int) = if (v > 0) "+$v" else "$v"
    private fun signed(v: Float) = signed(v.roundToInt())

    // ================================================================== visibility rules (VictoryScreen tabs)

    fun demographicsShown(civ: Civilization): Boolean {
        val p = civ.gameInfo.gameParameters
        return civ.isSpectator || (p.showCivilizationStats == true && p.showDemographics) || civ.gameInfo.victoryData != null
    }

    fun chartsShown(civ: Civilization): Boolean {
        val g = civ.gameInfo
        val p = g.gameParameters
        return if (civ.isSpectator) g.civilizations.any { it.statsHistory.size >= 2 }
        else (civ.statsHistory.size >= 2 && p.showCivilizationStats == true && p.showCharts) || g.victoryData != null
    }

    /** VictoryTabs.Replay.isHidden, inverted: after 5 turns, or once the game is over / you're out. */
    fun replayAllowed(civ: Civilization): Boolean =
        civ.isSpectator || civ.gameInfo.victoryData != null || !civ.isAlive() || civ.gameInfo.turns >= 5

    /** ConvertGoldToScience mod option (StatsOverviewTab.addGoldSlider). */
    fun goldSliderAllowed(civ: Civilization) = civ.gameInfo.ruleset.modOptions.hasUnique(UniqueType.ConvertGoldToScience)

    /** "an unknown civilization" unless we know them (GlobalPoliticsOverviewTable.getCivName). */
    fun civName(viewer: Civilization, c: Civilization) =
        if (viewer == c || viewer.knows(c)) c.civName else "an unknown civilization"

    // ================================================================== 📊 stats tab

    fun render(t: TabCtx) {
        t.includeAdvisor = false
        val s = stats(t.st)
        val view = viewOf(s)
        val civ = t.civ
        t.rows += ActionRow.of(t.select("ip_sv", "Show…", View.entries.map { Choice("ip:${it.key}", it.label, it.hint, it.emoji) }, "ip:${view.key}"))
        val fields: List<Field> = when (view) {
            View.BREAKDOWN -> breakdownFields(civ)
            View.RESOURCES -> resourceFields(civ)
            View.RELIGION -> religionFields(civ)
            View.POLITICS -> politicsFields(civ)
            View.LOG -> logFields(civ)
            View.DEMOGRAPHICS -> demographicsFields(civ)
            View.CHARTS -> chartFields(civ, rankingOf(s, civ))
        }
        val pages = paginate(fields)
        s.page = s.page.coerceIn(0, pages.size - 1)
        for (f in pages[s.page]) t.eb.addField(f.name.take(256), f.value.take(1024).ifEmpty { "—" }, f.inline)

        when (view) {
            View.BREAKDOWN -> if (goldSliderAllowed(civ)) {
                val cur = (civ.tech.goldPercentConvertedToScience * 100).roundToInt()
                t.rows += ActionRow.of(t.select("ip_gsci", "🔁 Convert gold to science: $cur%", (0..10).map { i ->
                    Choice("ip:${i * 10}", "Convert ${i * 10}% of gold to science", if (i * 10 == cur) "current setting" else "", "🔁")
                }, "ip:${(cur / 10f).roundToInt() * 10}", t.myTurn))
            }
            View.CHARTS -> if (chartsShown(civ)) {
                val rt = rankingOf(s, civ)
                t.rows += ActionRow.of(t.select("ip_rank", "Chart of…", rankingTypes(civ).map {
                    Choice("ip:${it.name}", it.label, "${it.label} of every civ over time", "📈")
                }, "ip:${rt.name}"))
            }
            else -> {}
        }
        if (pages.size > 1) t.endRow += listOf(
            t.button("ip_spg", "Page", "◀️", 2, s.page > 0, "-1"),
            t.button("ip_spg", "${s.page + 1}/${pages.size}", "▶️", 2, s.page < pages.size - 1, "1"),
        )
        if (view == View.CHARTS && chartsShown(civ)) t.file = FileUpload.fromData(chartPng(civ, rankingOf(s, civ)), "map.png")
        else t.worldMap()
    }

    class Field(val name: String, val value: String, val inline: Boolean = false)

    /** Split fields into embed pages: ≤ 3400 chars (room for News + warnings) and ≤ 18 fields each. */
    fun paginate(fields: List<Field>, budget: Int = 3400): List<List<Field>> {
        val out = ArrayList<List<Field>>()
        var cur = ArrayList<Field>(); var used = 0
        for (f in fields) {
            val len = f.name.length + minOf(f.value.length, 1024)
            if (cur.isNotEmpty() && (used + len > budget || cur.size >= 18)) { out += cur; cur = ArrayList(); used = 0 }
            cur += f; used += len
        }
        if (cur.isNotEmpty() || out.isEmpty()) out += cur
        return out
    }

    /** Lines into one or more fields of ≤ 1024 chars; continuation fields get a blank-ish name. */
    private fun fieldsOf(name: String, lines: List<String>, inline: Boolean = false): List<Field> {
        val out = ArrayList<Field>()
        val sb = StringBuilder()
        fun flush() { if (sb.isNotBlank()) out += Field(if (out.isEmpty()) name else "$name (cont.)", sb.toString().trim(), inline); sb.clear() }
        for (raw in lines) {
            val l = if (raw.length > 1000) raw.take(999) + "…" else raw
            if (sb.length + l.length + 1 > 1020) flush()
            sb.append(l).append('\n')
        }
        flush()
        if (out.isEmpty()) out += Field(name, "—", inline)
        return out
    }

    // ---------------------------------------------------------------- breakdowns (StatsOverviewTab, UnitSupplyTable)

    private val statEmoji = mapOf(Stat.Gold to "💰", Stat.Science to "🔬", Stat.Culture to "🎭", Stat.Faith to "🙏")

    fun breakdownFields(civ: Civilization): List<Field> {
        val out = ArrayList<Field>()
        val statMap = civ.stats.getStatMapForNextTurn()
        val stats = listOf(Stat.Gold, Stat.Science, Stat.Culture) + if (civ.gameInfo.isReligionEnabled()) listOf(Stat.Faith) else emptyList()
        for (stat in stats) {
            var total = 0f
            val lines = ArrayList<String>()
            for ((source, st) in statMap) {
                total += st[stat]
                val v = st[stat].roundToInt()
                if (v != 0) lines += "${signed(v)} ${tx(source)}"
            }
            if (stat == Stat.Gold && goldSliderAllowed(civ)) lines += "🔁 ${(civ.tech.goldPercentConvertedToScience * 100).roundToInt()}% of gold → science"
            out += Field("${statEmoji[stat]} ${stat.name} ${signed(total)}", InfoPlus.fitLines(lines.ifEmpty { listOf("—") }, 1000), true)
        }
        val happy = civ.stats.getHappinessBreakdown()
        out += Field("😊 Happiness ${signed(happy.values.sum())}", InfoPlus.fitLines(happy.entries.mapNotNull { (k, v) ->
            v.roundToInt().takeIf { it != 0 }?.let { "${signed(it)} ${tx(k)}" } }.ifEmpty { listOf("—") }, 1000), true)

        // Score
        val score = civ.calculateScoreBreakdown()
        out += Field("🏅 Score ${score.values.sum().roundToInt()}", InfoPlus.fitLines(score.entries.mapNotNull { (k, v) ->
            v.roundToInt().takeIf { it != 0 }?.let { "$it ${tx(k)}" } }.ifEmpty { listOf("—") }, 1000), true)

        // Unit supply
        val s = civ.stats
        val deficit = s.getUnitSupplyDeficit()
        out += Field("🪖 Unit supply ${civ.units.getCivUnitsSize()}/${s.getUnitSupply()}", listOf(
            "Base supply: ${s.getBaseUnitSupply()}",
            "Cities: ${s.getUnitSupplyFromCities()}",
            "Population: ${s.getUnitSupplyFromPop()}",
            "**Total supply: ${s.getUnitSupply()}**",
            "In use: ${civ.units.getCivUnitsSize()}",
            "Supply deficit: ${if (deficit > 0) "⚠️ **$deficit**" else "0"}",
            "Production penalty: ${s.getUnitSupplyProductionPenalty().toInt()}%",
        ).joinToString("\n") + (if (deficit > 0) "\n_Increase your supply or disband units to remove the production penalty._" else ""), true)

        // Great people (current/required, per turn), great generals
        val gp = civ.greatPeople
        val perTurn = gp.getGreatPersonPointsForNextTurn()
        val gpLines = (gp.greatPersonPointsCounter.keys + perTurn.keys).distinct().sorted().map { name ->
            "$name: ${gp.greatPersonPointsCounter[name]}/${gp.getPointsRequiredForGreatPerson(name)} (${signed(perTurn[name])}/turn)"
        } + gp.greatGeneralPointsCounter.entries.map { (unit, pts) -> "$unit: $pts/${gp.pointsForNextGreatGeneralCounter[unit]}" }
        out += Field("🌟 Great person points", InfoPlus.fitLines(gpLines.ifEmpty { listOf("No great person points yet.") }, 1000), false)
        return out
    }

    // ---------------------------------------------------------------- resources (ResourcesOverviewTab)

    private const val UNIMPROVED = "Unimproved"
    private const val WLTK = "Celebrating WLTK"
    private const val DEMANDED = "Demanded by cities"
    private const val OFFERED = "Offered in trades"
    private const val NO_SOURCE = "No source"
    private const val STOCKPILE = "Stockpile"

    /** ResourcesOverviewTab.getExtraDrilldown */
    private fun extraDrilldown(civ: Civilization): ResourceSupplyList {
        val list = ResourceSupplyList(keepZeroAmounts = true)
        val rs = civ.gameInfo.ruleset
        fun Tile.countAsUnimproved(): Boolean {
            val r = tileResource
            return civ.canSeeResource(r) && r.resourceType != ResourceType.Bonus && !providesResources(civ)
        }
        fun addUnimproved(city: com.unciv.logic.city.City) {
            for (tile in city.getTiles()) if (tile.countAsUnimproved()) list.add(tile.tileResource!!, UNIMPROVED)
        }
        for (city in civ.cities) {
            if (city.demandedResource.isNotEmpty()) rs.tileResources[city.demandedResource]?.let {
                list.add(it, if (city.isWeLoveTheKingDayActive()) WLTK else DEMANDED)
            }
            addUnimproved(city)
        }
        for (other in civ.getKnownCivs()) {
            for (trade in other.tradeRequests.filter { it.requestingCiv == civ.civID })
                for (offer in trade.trade.theirOffers.filter { it.type == TradeOfferType.Strategic_Resource || it.type == TradeOfferType.Luxury_Resource })
                    rs.tileResources[offer.name]?.let { list.add(it, OFFERED, offer.amount) }
            if (!other.isCityState || other.allyCiv != civ) continue
            for (city in other.cities) addUnimproved(city)
        }
        for (r in rs.tileResources.values) if (r.resourceType == ResourceType.Strategic && civ.canSeeResource(r)) list.add(r, NO_SOURCE, 0)
        for (name in civ.resourceStockpiles.keys) {
            val r = rs.tileResources[name] ?: continue
            if (r.hasUnique(UniqueType.NotShownOnWorldScreen, civ.state)) continue
            list.add(r, STOCKPILE, civ.getResourceAmount(r))
        }
        return list
    }

    fun resourceFields(civ: Civilization): List<Field> {
        val detail = civ.detailedCivResources
        val extra = extraDrilldown(civ)
        val resources = (detail.asSequence() + extra.asSequence()).map { it.resource }
            .filter { it.resourceType != ResourceType.Bonus && !it.hasUnique(UniqueType.NotShownOnWorldScreen, civ.state) && !it.isCityWide }
            .distinct().sortedWith(compareBy<TileResource> { it.resourceType }.thenBy { tx(it.name) }).toList()
        if (resources.isEmpty()) return listOf(Field("💎 Resources", "You have no luxury or strategic resources yet — improve resource tiles in your territory."))
        val out = ArrayList<Field>()
        for ((type, list) in resources.groupBy { it.resourceType }) {
            val lines = list.map { r ->
                val total = detail.sumBy(r)
                val origins = detail.filter { it.resource == r && it.amount != 0 }
                    .joinToString(" · ") { "${it.origin.removeSuffix("+")} ${if (r.isStockpiled) signed(it.amount) else it.amount.toString()}" }
                val extras = extra.filter { it.resource == r && it.origin != NO_SOURCE && (it.amount != 0 || it.origin == STOCKPILE) }
                    .joinToString(" · ") { "${it.origin}: ${it.amount}" }
                val warn = if (total < 0 || extra.filter { it.resource == r && it.origin == DEMANDED }.sumOf { it.amount } > 0) "⚠️ " else ""
                "$warn**${tx(r.name)}** ${if (r.isStockpiled) signed(total) + "/turn" else total.toString()}" +
                    (if (origins.isNotEmpty()) " — $origins" else "") + (if (extras.isNotEmpty()) " _($extras)_" else "")
            }
            val emoji = when (type) { ResourceType.Luxury -> "💎"; ResourceType.Strategic -> "⚔️"; else -> "📦" }
            out += fieldsOf("$emoji ${type.name} resources", lines)
        }
        return out
    }

    // ---------------------------------------------------------------- religions (ReligionOverviewTab)

    fun religionFields(civ: Civilization): List<Field> {
        val game = civ.gameInfo
        if (!game.isReligionEnabled()) return listOf(Field("🙏 Religion", "Religion is turned off in this game."))
        val rm = civ.religionManager
        val head = ArrayList<String>()
        head += "Religions to be founded: " + if (civ.shouldHideCivCount()) "?" else "**${rm.remainingFoundableReligions()}**"
        runCatching { rm.remainingFoundableReligionsBreakdown().forEach { (text, n) -> head += " • ${tx(text)}: $n" } }
        if (rm.canGenerateProphet(ignoreFaithAmount = true))
            head += "Minimal faith for the next ${rm.getGreatProphetEquivalent()?.name ?: "Great Prophet"}: **${rm.faithForNextGreatProphet() + 1}** (you have ${rm.storedFaith})"
        head += "Your religious status: **${rm.religionState}**"
        val out = arrayListOf(Field("🙏 Religions of the world", head.joinToString("\n")))
        val religions: List<Religion> = game.civilizations.mapNotNull { it.religionManager.religion }.distinctBy { it.name }.sortedBy { it.foundingTurn }
        if (religions.isEmpty()) out += Field("No religions yet", "Nobody has founded a pantheon or religion yet.")
        for (rel in religions) {
            val lines = ArrayList<String>()
            val founder = rel.foundingCiv
            lines += "Founded by **${civName(civ, founder)}**" + (if (rel.foundingTurn > 0) " on turn ${rel.foundingTurn + 1}" else "")
            if (rel.isMajorReligion()) {
                val holy = game.getCities().firstOrNull { it.isHolyCityOf(rel.name) }
                if (holy != null) lines += "Holy city: " + if (civ.hasExplored(holy.getCenterTile())) "**${holy.name}**" else "an unknown city"
            }
            val m = founder.religionManager
            lines += "Cities following: **${m.numberOfCitiesFollowingThisReligion()}** · followers: **${m.numberOfFollowersFollowingThisReligion("in all cities")}**"
            for (b in rel.getAllBeliefsOrdered()) {
                val desc = b.uniques.joinToString("; ") { tx(it) }
                lines += "✨ **${b.name}** (${b.type.name}) — ${desc.take(180)}"
            }
            val kind = if (rel.isPantheon()) "Pantheon" else "Religion"
            out += fieldsOf("⛪ ${tx(rel.getReligionDisplayName())} ($kind)", lines)
        }
        return out
    }

    // ---------------------------------------------------------------- global politics (GlobalPoliticsOverviewTable)

    fun politicsFields(civ: Civilization): List<Field> {
        val game = civ.gameInfo
        val out = ArrayList<Field>()
        val count = if (civ.shouldHideCivCount()) "?" else game.civilizations.count { !it.isSpectator && !it.isBarbarian && !it.isCityState }.toString()
        val known = civ.getKnownCivs().filter { it.isMajorCiv && !it.isDefeated() }.sortedBy { it.civName }.toList()
        val defeated = civ.getKnownCivs().filter { it.isMajorCiv && it.isDefeated() }.sortedBy { it.civName }.toList()
        val head = arrayListOf("**$count** civilizations in the game · you know **${known.size}** alive" + (if (defeated.isNotEmpty()) " and ${defeated.size} defeated" else ""))
        civ.getTurnsTillNextDiplomaticVote()?.let { head += "Turns until the next diplomatic victory vote: **$it**" }
        out += Field("🌐 Global politics", head.joinToString("\n"))
        val civs = (if (civ.isSpectator) emptyList() else listOf(civ)) + known + defeated
        for (c in civs) {
            val lines = ArrayList<String>()
            lines += "${tx(c.nation.leaderName)} · ${c.getEra().name}" + InfoPlus.scoreSuffix(civ, c)
            val branches = c.policies.branches.filter { c.policies.isAdopted(it.name) }.map { b ->
                "${b.name} ${1 + b.policies.count { it.policyBranchType != PolicyBranchType.BranchComplete && c.policies.isAdopted(it.name) }}"
            }
            lines += "📜 Policies: " + branches.joinToString(" · ").ifEmpty { "none" }
            val wonders = c.cities.flatMap { city -> city.cityConstructions.getBuiltBuildings().filter { it.isWonder }.map { it.name } }
            lines += "🏛️ Wonders: " + wonders.joinToString(", ").ifEmpty { "none" }
            if (c.isDefeated()) lines += "☠️ Defeated"
            else {
                for (o in c.getKnownCivs()) if (c.isAtWarWith(o) && !o.isBarbarian) lines += "⚔️ At war with ${civName(civ, o)}"
                for (o in c.getKnownCivs()) {
                    val dm = c.getDiplomacyManager(o) ?: continue
                    when {
                        dm.hasFlag(DiplomacyFlags.DefensivePact) -> lines += "🛡️ Defensive pact with ${civName(civ, o)} (${dm.getFlag(DiplomacyFlags.DefensivePact)} turns)"
                        dm.hasFlag(DiplomacyFlags.DeclarationOfFriendship) -> lines += "💛 Friends with ${civName(civ, o)} (${dm.getFlag(DiplomacyFlags.DeclarationOfFriendship)} turns)"
                    }
                }
                for (o in c.getKnownCivs()) {
                    val dm = c.getDiplomacyManager(o) ?: continue
                    if (dm.hasFlag(DiplomacyFlags.Denunciation)) lines += "😠 Denounced ${civName(civ, o)} (${dm.getFlag(DiplomacyFlags.Denunciation)} turns)"
                }
                for (cs in game.getAliveCityStates())
                    if (cs.getDiplomacyManager(c)?.isRelationshipLevelEQ(RelationshipLevel.Ally) == true) lines += "🏰 Allied with ${civName(civ, cs)}"
            }
            out += fieldsOf("${if (c == civ) "⭐" else "🏳️"} ${c.civName}${if (c == civ) " (you)" else ""}", lines)
        }
        return out
    }

    // ---------------------------------------------------------------- notification log (NotificationsOverviewTable)

    private fun categoryEmoji(c: Notification.NotificationCategory) = when (c) {
        Notification.NotificationCategory.General -> "📌"
        Notification.NotificationCategory.Trade -> "💱"
        Notification.NotificationCategory.Diplomacy -> "🤝"
        Notification.NotificationCategory.Production -> "🏗️"
        Notification.NotificationCategory.Units -> "🪖"
        Notification.NotificationCategory.War -> "🔥"
        Notification.NotificationCategory.Religion -> "🙏"
        Notification.NotificationCategory.Espionage -> "🕵️"
        Notification.NotificationCategory.Cities -> "🏙️"
    }

    private fun turnLines(list: List<Notification>): List<String> =
        Notification.NotificationCategory.entries.flatMap { cat ->
            list.filter { it.category == cat }.map { "${categoryEmoji(cat)} ${tx(it.text).replace("\n", " ")}" }
        }

    fun logFields(civ: Civilization): List<Field> {
        val out = ArrayList<Field>()
        if (civ.notifications.isNotEmpty()) out += fieldsOf("📅 Turn ${civ.gameInfo.turns + 1} (now)", turnLines(civ.notifications))
        for (log in civ.notificationsLog.asReversed()) {
            if (log.notifications.isEmpty()) continue
            out += fieldsOf("📅 Turn ${log.turn + 1}", turnLines(log.notifications))
        }
        if (out.isEmpty()) out += Field("📜 Notification log", "Nothing has happened yet.")
        return out
    }

    // ---------------------------------------------------------------- demographics (VictoryScreenDemographics)

    fun rankingTypes(civ: Civilization) = RankingType.filteredEntries(civ.gameInfo.gameParameters)

    private fun rankingOf(s: StatsState, civ: Civilization) =
        rankingTypes(civ).firstOrNull { it.name == s.ranking } ?: RankingType.Score

    /** Value frozen at the end of the last completed turn (so mid-turn changes can't be probed). */
    private fun Civilization.demographicStat(rt: RankingType): Int {
        val h = statsHistory
        val snap = h[gameInfo.turns] ?: h.maxByOrNull { it.key }?.value
        return snap?.get(rt) ?: getStatForRanking(rt)
    }

    /** VictoryScreenCivGroup: name and value, or just "Unknown" for civs we haven't met. */
    private fun civWithValue(viewer: Civilization, c: Civilization, v: Int): String =
        if (viewer.knows(c) || viewer == c || c.isDefeated() || viewer.isDefeated())
            (if (c == viewer) "**you**" else c.civName) + (if (c.isDefeated()) "" else " $v")
        else "an unknown civ"

    fun demographicsFields(civ: Civilization): List<Field> {
        if (!demographicsShown(civ)) return listOf(Field("🎯 Demographics",
            "Demographics are turned off in this game (game option _Show demographics_). They become visible once the game is over."))
        val game = civ.gameInfo
        val majors = game.civilizations.filter { it.isMajorCiv && (it.isAlive() || it == civ) }
        val out = ArrayList<Field>()
        for (rt in rankingTypes(civ)) {
            val sorted = majors.map { it to it.demographicStat(rt) }.sortedByDescending { it.second }
            val lines = ArrayList<String>()
            val rank = sorted.indexOfFirst { it.first == civ } + 1
            if (rank > 0) lines += "Rank: **#$rank**"
            if (!civ.isDefeated() && civ.isMajorCiv) sorted.firstOrNull { it.first == civ }?.let { lines += "Value: **${it.second}**" }
            sorted.firstOrNull()?.let { lines += "Best: ${civWithValue(civ, it.first, it.second)}" }
            if (sorted.isNotEmpty()) lines += "Average: ${(sorted.sumOf { it.second }.toFloat() / sorted.size).roundToInt()}"
            sorted.lastOrNull()?.let { lines += "Worst: ${civWithValue(civ, it.first, it.second)}" }
            out += Field(rankEmoji(rt) + " " + rt.label, lines.joinToString("\n"), true)
        }
        return out
    }

    private fun rankEmoji(rt: RankingType) = when (rt) {
        RankingType.Score -> "🏅"; RankingType.Population -> "👥"; RankingType.Growth -> "🍞"; RankingType.Production -> "⚙️"
        RankingType.Gold -> "💰"; RankingType.Territory -> "⬡"; RankingType.Force -> "🛡️"; RankingType.Happiness -> "😊"
        RankingType.Technologies -> "🔬"; RankingType.Culture -> "🎭"; RankingType.TilesExplored -> "🧭"
    }

    // ---------------------------------------------------------------- charts (VictoryScreenCharts + LineChart)

    /** Turn -> value per visible civ; the live value is appended for the current turn (as VictoryScreenCharts). */
    fun chartData(viewer: Civilization, rt: RankingType): Map<Civilization, List<Pair<Int, Int>>> {
        val game = viewer.gameInfo
        val out = LinkedHashMap<Civilization, List<Pair<Int, Int>>>()
        for (c in game.civilizations.filter { it.isMajorCiv && InfoPlus.canViewStats(game, viewer, it) }) {
            val pts = java.util.TreeMap<Int, Int>()
            for ((turn, data) in c.statsHistory) data[rt]?.let { pts[turn] = it }
            if (pts.isEmpty() && c.isDefeated()) continue
            if (!c.isDefeated()) pts[game.turns] = c.getStatForRanking(rt)
            out[c] = pts.entries.map { it.key to it.value }
        }
        return out
    }

    private fun useActualColor(viewer: Civilization, c: Civilization) = viewer.isSpectator || viewer.isDefeated() ||
        viewer.victoryManager.hasWon() || viewer == c || viewer.knows(c) || c.isDefeated()

    private fun gdx(c: com.badlogic.gdx.graphics.Color) =
        Color((c.r * 255).toInt().coerceIn(0, 255), (c.g * 255).toInt().coerceIn(0, 255), (c.b * 255).toInt().coerceIn(0, 255))
    private fun lum(c: Color) = (0.299 * c.red + 0.587 * c.green + 0.114 * c.blue) / 255

    private fun lighten(c: Color): Color {
        var col = c
        while (lum(col) < 0.35) col = Color(minOf(255, col.red + 40), minOf(255, col.green + 40), minOf(255, col.blue + 40))
        return col
    }
    private fun dist(a: Color, b: Color) = abs(a.red - b.red) + abs(a.green - b.green) + abs(a.blue - b.blue)

    /** Civ colours that read on the dark chart: outer colour (lightened if too dark), or the inner one when
     *  the outer is too close to a colour already used; unknown civs are grey (LineChart.useActualColor). */
    fun chartColors(viewer: Civilization, civs: Collection<Civilization>): Map<Civilization, Color> {
        val out = LinkedHashMap<Civilization, Color>()
        for (c in civs.sortedBy { if (it == viewer) 0 else 1 }) {
            if (!useActualColor(viewer, c)) { out[c] = Color(105, 105, 112); continue }
            val outer = lighten(gdx(c.nation.getOuterColor())); val inner = lighten(gdx(c.nation.getInnerColor()))
            val used = out.values
            out[c] = listOf(outer, inner).firstOrNull { cand -> used.all { dist(it, cand) > 90 } }
                ?: listOf(outer, inner).maxBy { cand -> used.minOfOrNull { dist(it, cand) } ?: 999 }
        }
        return out
    }

    private fun niceStep(range: Double, ticks: Int = 5): Double {
        if (range <= 0) return 1.0
        val raw = range / ticks
        val mag = 10.0.pow(floor(log10(raw)))
        val n = raw / mag
        return mag * when { n <= 1 -> 1.0; n <= 2 -> 2.0; n <= 5 -> 5.0; else -> 10.0 }
    }

    fun chartPng(viewer: Civilization, rt: RankingType): ByteArray {
        val data = chartData(viewer, rt)
        val w = 1000; val h = 560
        val left = 78; val right = 250; val top = 56; val bottom = 62
        val pw = w - left - right; val ph = h - top - bottom
        val img = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
        val g = img.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        g.color = Color(30, 31, 36); g.fillRect(0, 0, w, h)
        g.color = Color(240, 240, 245); g.font = Font(Font.SANS_SERIF, Font.BOLD, 22)
        g.drawString("${rt.label} over time", left, 34)

        val all = data.values.flatten()
        val minT = all.minOfOrNull { it.first } ?: viewer.gameInfo.turns
        var maxT = all.maxOfOrNull { it.first } ?: minT
        if (maxT <= minT) maxT = minT + 1
        val minV0 = minOf(0, all.minOfOrNull { it.second } ?: 0)
        val maxV0 = maxOf(1, all.maxOfOrNull { it.second } ?: 1)
        val step = niceStep((maxV0 - minV0).toDouble())
        val minV = floor(minV0 / step) * step
        val maxV = ceil(maxV0 / step) * step
        fun px(t: Int) = left + (t - minT).toDouble() / (maxT - minT) * pw
        fun py(v: Double) = top + ph - (v - minV) / (maxV - minV) * ph

        // grid + y ticks
        g.font = Font(Font.SANS_SERIF, Font.PLAIN, 14)
        val fm = g.fontMetrics
        var v = minV
        while (v <= maxV + step / 2) {
            val y = py(v).roundToInt()
            g.color = if (abs(v) < 1e-9) Color(170, 170, 180) else Color(60, 62, 70)
            g.drawLine(left, y, left + pw, y)
            val label = if (abs(v) >= 10000) "${(v / 1000).roundToInt()}k" else v.roundToInt().toString()
            g.color = Color(200, 200, 210)
            g.drawString(label, left - 8 - fm.stringWidth(label), y + fm.ascent / 2 - 1)
            v += step
        }
        // x ticks
        val xStep = niceStep((maxT - minT).toDouble(), 8).coerceAtLeast(1.0).toInt()
        var t0 = (ceil(minT.toDouble() / xStep) * xStep).toInt()
        while (t0 <= maxT) {
            val x = px(t0).roundToInt()
            g.color = Color(60, 62, 70); g.drawLine(x, top, x, top + ph)
            g.color = Color(200, 200, 210)
            val label = (t0 + 1).toString()
            g.drawString(label, x - fm.stringWidth(label) / 2, top + ph + fm.ascent + 6)
            t0 += xStep
        }
        // axes + labels
        g.color = Color(170, 170, 180); g.stroke = BasicStroke(1.5f)
        g.drawLine(left, top, left, top + ph); g.drawLine(left, top + ph, left + pw, top + ph)
        g.font = Font(Font.SANS_SERIF, Font.BOLD, 15)
        val fmb = g.fontMetrics
        g.drawString("Turn", left + pw / 2 - fmb.stringWidth("Turn") / 2, h - 16)
        val rot = g.transform
        g.rotate(-Math.PI / 2); g.drawString(rt.label, -(top + ph / 2 + fmb.stringWidth(rt.label) / 2), 22); g.transform = rot

        // lines — the viewer's own last so it's on top
        val colors = chartColors(viewer, data.keys)
        val order = data.keys.sortedBy { if (it == viewer) 1 else 0 }
        for (c in order) {
            val pts = data[c] ?: continue
            g.color = colors[c]
            g.stroke = BasicStroke(if (c == viewer) 4.5f else 2.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
            if (pts.size == 1) { val (t, value) = pts[0]; g.fillOval(px(t).toInt() - 4, py(value.toDouble()).toInt() - 4, 8, 8); continue }
            val path = Path2D.Double()
            pts.forEachIndexed { i, (t, value) -> if (i == 0) path.moveTo(px(t), py(value.toDouble())) else path.lineTo(px(t), py(value.toDouble())) }
            g.draw(path)
            if (pts.size <= 40) for ((t, value) in pts) g.fillOval(px(t).toInt() - 3, py(value.toDouble()).toInt() - 3, 6, 6)
        }

        // legend, best first
        g.font = Font(Font.SANS_SERIF, Font.PLAIN, 15)
        val lfm = g.fontMetrics
        var ly = top + 6
        g.color = Color(240, 240, 245); g.font = Font(Font.SANS_SERIF, Font.BOLD, 15); g.drawString("Civilization", w - right + 22, ly + 10); ly += 28
        g.font = Font(Font.SANS_SERIF, Font.PLAIN, 15)
        val legend = data.entries.sortedWith(compareByDescending<Map.Entry<Civilization, List<Pair<Int, Int>>>> { if (it.key.isDefeated()) Int.MIN_VALUE else it.value.lastOrNull()?.second ?: 0 }
            .thenBy { it.key.civName })
        for ((c, pts) in legend) {
            if (ly > h - 20) break
            val known = viewer == c || viewer.knows(c) || c.isDefeated() || viewer.isDefeated() || viewer.isSpectator
            g.color = colors[c]
            g.fillRoundRect(w - right + 22, ly - 11, 22, 12, 4, 4)
            g.color = Color(225, 225, 232)
            val name = (if (known) c.civName else "Unknown civ") + (if (c == viewer) " (you)" else "")
            val value = if (c.isDefeated()) "☠" else pts.lastOrNull()?.second?.toString() ?: ""
            var label = name
            while (lfm.stringWidth("$label  $value") > right - 60 && label.length > 4) label = label.dropLast(2) + "…"
            g.drawString(label, w - right + 52, ly)
            g.drawString(value, w - 14 - lfm.stringWidth(value), ly)
            ly += 24
        }
        if (data.isEmpty()) { g.color = Color(200, 200, 210); g.drawString("No data yet.", left + 20, top + 30) }
        g.dispose()
        return ByteArrayOutputStream().also { ImageIO.write(img, "png", it) }.toByteArray()
    }

    fun chartFields(civ: Civilization, rt: RankingType): List<Field> {
        if (!chartsShown(civ)) return listOf(Field("📈 Charts",
            if (civ.statsHistory.size < 2) "Charts need at least two turns of history — come back next turn."
            else "Charts are turned off in this game (game option _Show charts_). They become visible once the game is over."))
        val data = chartData(civ, rt)
        val lines = data.entries.sortedByDescending { if (it.key.isDefeated()) Int.MIN_VALUE else it.value.lastOrNull()?.second ?: 0 }.map { (c, pts) ->
            val first = pts.firstOrNull()?.second ?: 0; val last = pts.lastOrNull()?.second ?: 0
            "${InfoPlus.nameFor(civ, c)} — " + (if (c.isDefeated()) "☠️ defeated" else "**$last** (${signed(last - first)} since turn ${(pts.firstOrNull()?.first ?: 0) + 1})")
        }
        val note = if (civ.gameInfo.gameParameters.hideOtherCivilizationStats && civ.gameInfo.victoryData == null) "\n_Other civilizations' stats are hidden in this game._" else ""
        return listOf(Field("📈 ${rt.label} over time", InfoPlus.fitLines(lines.ifEmpty { listOf("No data yet.") }, 1000 - note.length) + note))
    }

    // ================================================================== 🎞️ replay (VictoryScreenReplay + ReplayMap)

    fun firstTurn(civ: Civilization) = civ.gameInfo.historyStartTurn.coerceAtLeast(0).coerceAtMost(civ.gameInfo.turns)

    fun replayTurn(st: PanelState, civ: Civilization): Int {
        val r = replay(st)
        val t = (r.turn ?: firstTurn(civ)).coerceIn(firstTurn(civ), civ.gameInfo.turns)
        r.turn = t
        return t
    }

    /** ReplayMap: only tiles you've explored (all for spectators); owners and cities from the tile history. */
    private fun replayTiles(civ: Civilization): List<Tile> =
        civ.gameInfo.tileMap.values.filter { civ.isSpectator || it.isExplored(civ) }

    fun replayPng(civ: Civilization, turn: Int): ByteArray =
        MapRenderer.renderWhole(replayTiles(civ), if (civ.isSpectator) null else civ, 1, turn)

    private fun yearOf(civ: Civilization, turn: Int): String {
        val y = civ.gameInfo.getYear(turn - civ.gameInfo.turns)
        return if (y < 0) "${-y} BC" else "$y AD"
    }

    fun replaySummary(civ: Civilization, turn: Int): List<String> {
        val game = civ.gameInfo
        val tiles = HashMap<String, Int>(); val cities = HashMap<String, Int>()
        for (t in replayTiles(civ)) {
            val s = t.history.getState(turn)
            val owner = s.owningCivName ?: continue
            tiles[owner] = (tiles[owner] ?: 0) + 1
            if (s.cityCenterType != com.unciv.logic.map.tile.TileHistory.TileHistoryState.CityCenterType.None) cities[owner] = (cities[owner] ?: 0) + 1
        }
        return tiles.entries.sortedByDescending { it.value }.mapNotNull { (id, n) ->
            val c = game.getCivilizationOrNull(id) ?: return@mapNotNull null
            "${if (c.isCityState) "🏰" else "🏳️"} ${InfoPlus.nameFor(civ, c)} — $n tiles" + (cities[id]?.let { " · $it ${if (it == 1) "city" else "cities"}" } ?: "")
        }
    }

    fun renderReplay(t: TabCtx) {
        t.includeAdvisor = false
        val civ = t.civ
        val game = t.game
        if (!replayAllowed(civ)) {
            t.eb.addField("🎞️ Replay", "The replay opens after turn 5 (or once the game is over).", false)
            t.worldMap(); return
        }
        val first = firstTurn(civ); val last = game.turns
        val turn = replayTurn(t.st, civ)
        val summary = replaySummary(civ, turn)
        t.eb.addField("🎞️ Replay — turn ${turn + 1} of ${last + 1} · ${yearOf(civ, turn)}",
            (if (summary.isEmpty()) "Nobody owns land you know of yet." else InfoPlus.fitLines(summary, 900)) +
                "\n_Borders and cities as they were on that turn, on the land you've explored._", false)
        t.rows += ActionRow.of(
            t.button("ip_rp", "First", "⏮️", 2, turn > first, "first"),
            t.button("ip_rp", "−10", "⏪", 2, turn > first, "-10"),
            t.button("ip_rp", "Prev", "◀️", 1, turn > first, "-1"),
            t.button("ip_rp", "Next", "▶️", 1, turn < last, "1"),
            t.button("ip_rp", "+10", "⏩", 2, turn < last, "10"),
        )
        t.endRow += t.button("ip_rp", "Now", "⏭️", 2, turn < last, "last")
        t.endRow += t.askButton("ip_rturn", "Turn…", "🔢", 2)
        t.file = FileUpload.fromData(replayPng(civ, turn), "map.png")
    }

    // ================================================================== actions

    fun handle(verb: String, ctx: ActionCtx, v: String?): Boolean {
        val civ = ctx.civ
        when (verb) {
            "ip_sv" -> { val s = stats(ctx.st); s.view = v ?: s.view; s.page = 0 }
            "ip_spg" -> stats(ctx.st).page += ctx.args.firstOrNull()?.toIntOrNull() ?: 1
            "ip_rank" -> {
                val rt = rankingTypes(civ).firstOrNull { it.name == v } ?: throw PlayerError("Unknown ranking.")
                stats(ctx.st).ranking = rt.name
            }
            "ip_go" -> {
                when (val dest = ctx.args.firstOrNull()) {
                    REPLAY -> ctx.st.tab = REPLAY
                    else -> { ctx.st.tab = STATS; if (dest != null) { stats(ctx.st).view = dest; stats(ctx.st).page = 0 } }
                }
            }
            "ip_gsci" -> {
                ctx.requireTurn()
                if (!goldSliderAllowed(civ)) throw PlayerError("This ruleset doesn't allow converting gold to science.")
                val pct = v?.toIntOrNull()?.coerceIn(0, 100) ?: throw PlayerError("Pick a percentage.")
                civ.tech.goldPercentConvertedToScience = pct / 100f   // CivView.trySetGoldPercentConvertedToScience
                civ.cities.forEach { it.cityStats.update() }          // CivView.tryUpdateAllCityStats
                civ.updateStatsForNextTurn()
                ctx.flash += "🔁 Now converting **$pct%** of your gold to science."
                ctx.changed = true
            }
            "ip_rp" -> {
                if (!replayAllowed(civ)) throw PlayerError("The replay opens after turn 5.")
                val r = replay(ctx.st)
                val cur = replayTurn(ctx.st, civ)
                r.turn = when (val a = ctx.args.firstOrNull()) {
                    "first" -> firstTurn(civ)
                    "last" -> ctx.game.turns
                    else -> cur + (a?.toIntOrNull() ?: 1)
                }.coerceIn(firstTurn(civ), ctx.game.turns)
            }
            "ip_rturn" -> {
                if (!replayAllowed(civ)) throw PlayerError("The replay opens after turn 5.")
                val n = v?.trim()?.removePrefix("T")?.removePrefix("t")?.trim()?.toIntOrNull()
                    ?: throw PlayerError("Type a turn number between ${firstTurn(civ) + 1} and ${ctx.game.turns + 1}.")
                if (n - 1 !in firstTurn(civ)..ctx.game.turns) throw PlayerError("Turn $n isn't in the replay — pick ${firstTurn(civ) + 1}…${ctx.game.turns + 1}.")
                replay(ctx.st).turn = n - 1
                ctx.st.tab = REPLAY
            }
            else -> return false
        }
        return true
    }
}

// ================================================================== self-test (called from InfoPlus.selfTest)

fun InfoStats.selfTest(t: SelfTestCtx) {
    val a = t.alice
    fun check(ok: Boolean, what: String) { if (!ok) throw IllegalStateException("InfoPlus/stats: $what") }
    val st = t.ctl.state(t.s.meta.id, a.userId)
    fun panel() = t.eng { Panel.render(t.s, a, st) }
    fun fieldsOk(tag: String) {
        val r = panel()
        check(r.embed.length <= 6000, "$tag embed too long: ${r.embed.length}")
        check(r.embed.fields.size <= 25 && r.embed.fields.all { (it.value?.length ?: 0) <= 1024 }, "$tag fields too big")
        check(r.rows.size <= 5, "$tag rows")
    }

    // ---- ranking privacy: showRankings off / other civs' stats hidden / unknown civs
    val leak = t.eng { g ->
        val civ = GameOps.civOf(g, a)
        val p = g.gameParameters
        val other = g.civilizations.first { it.isMajorCiv && it != civ && it.isAlive() }
        val saved = Triple(p.showRankings, p.hideOtherCivilizationStats, p.showCivilizationStats)
        val out = ArrayList<String>()
        try {
            p.showCivilizationStats = true; p.showRankings = true; p.hideOtherCivilizationStats = false
            if (!InfoPlus.rankingLines(civ).any { it.contains("#") }) out += "rankings missing when allowed"
            if (InfoPlus.scoreSuffix(civ, other).isEmpty()) out += "score hidden when allowed"
            p.showRankings = false
            val ov = Features.overviewFields(civ).firstOrNull { it.first.contains("Rankings") }?.second.orEmpty()
            if (Regex("#\\d").containsMatchIn(ov)) out += "overview shows places with showRankings off: $ov"
            if (InfoPlus.scoreSuffix(civ, other).isNotEmpty()) out += "diplomacy score shown with showRankings off"
            if (InfoPlus.scoreSuffix(civ, civ).isEmpty()) out += "own score hidden"
            p.showRankings = true; p.hideOtherCivilizationStats = true
            if (InfoPlus.scoreSuffix(civ, other).isNotEmpty()) out += "score shown with hideOtherCivilizationStats"
            if (InfoPlus.rankingLines(civ).none { it.contains("#1 of 1") }) out += "hidden stats still ranked against others: ${InfoPlus.rankingLines(civ).first()}"
            val stranger = g.civilizations.firstOrNull { it != civ && !it.isBarbarian && !it.isSpectator && !civ.knows(it) }
            if (stranger != null && civName(civ, stranger) != "an unknown civilization") out += "unknown civ named"
            println("  [privacy] overview rankings (off): ${ov.replace("\n", " | ").take(160)} · stranger=${stranger?.civName}")
        } finally { p.showRankings = saved.first; p.hideOtherCivilizationStats = saved.second; p.showCivilizationStats = saved.third }
        out
    }
    check(leak.isEmpty(), leak.joinToString("; "))
    check(t.act(a, "tab", emptyList(), "DIPLOMACY", "ranking privacy (showRankings / hidden stats / unknown civs)"), "diplomacy tab")
    t.render(a, null)

    // ---- 📊 Stats tab, every view
    check(t.act(a, "tab", emptyList(), STATS, "📊 stats tab"), "stats tab")
    for (v in InfoStats.View.entries) {
        check(t.act(a, "ip_sv", emptyList(), "ip:${v.key}", null), "view ${v.key}")
        fieldsOk(v.key)
        t.render(a, "ip-stats-${v.key}")
        val fs = t.eng { g -> val c = GameOps.civOf(g, a); when (v) {
            InfoStats.View.BREAKDOWN -> breakdownFields(c); InfoStats.View.RESOURCES -> resourceFields(c); InfoStats.View.RELIGION -> religionFields(c)
            InfoStats.View.POLITICS -> politicsFields(c); InfoStats.View.LOG -> logFields(c); InfoStats.View.DEMOGRAPHICS -> demographicsFields(c)
            InfoStats.View.CHARTS -> chartFields(c, RankingType.Score) } }
        fs.take(6).forEach { println("  [stats/${v.key}] ${it.name}: ${it.value.replace("\n", " | ").take(260)}") }
    }
    val bd = t.eng { g -> breakdownFields(GameOps.civOf(g, a)) }.map { it.name }
    check(listOf("Gold", "Science", "Culture", "Happiness", "Score", "Unit supply", "Great person").all { k -> bd.any { it.contains(k) } }, "breakdown fields: $bd")
    t.act(a, "ip_sv", emptyList(), "ip:econ", "stats breakdown (gold/science/culture/faith/happiness, score, unit supply, GP)")
    t.act(a, "ip_sv", emptyList(), "ip:res", "resources by origin")
    t.act(a, "ip_sv", emptyList(), "ip:rel", "religion overview")
    t.act(a, "ip_sv", emptyList(), "ip:pol", "global politics")

    // gold -> science slider (only with the ConvertGoldToScience mod option — cheat it in)
    val unique = com.unciv.models.ruleset.unique.Unique("Can convert gold to science with sliders")
    val before = t.eng { g -> g.ruleset.modOptions.uniqueMap.addUnique(unique); GameOps.civOf(g, a).tech.goldPercentConvertedToScience }
    try {
        t.act(a, "ip_sv", emptyList(), "ip:econ", null)
        t.render(a, "ip-stats-slider")
        check(t.act(a, "ip_gsci", emptyList(), "ip:30", "gold→science slider"), "slider")
        check(t.eng { g -> GameOps.civOf(g, a).tech.goldPercentConvertedToScience } == 0.3f, "slider not applied")
        check(!t.act(t.bob, "ip_gsci", emptyList(), "ip:50", null), "slider allowed out of turn")
    } finally { t.eng { g -> g.ruleset.modOptions.uniqueMap.removeUnique(unique); GameOps.civOf(g, a).tech.goldPercentConvertedToScience = before } }
    check(!t.act(a, "ip_gsci", emptyList(), "ip:30", null), "slider allowed without the mod option")

    // notification log paging (pad the log so it needs several pages)
    t.eng { g -> val c = GameOps.civOf(g, a); repeat(80) { c.addNotification("Selftest notification number $it with some padding text to fill the page", Notification.NotificationCategory.General) } }
    t.act(a, "ip_sv", emptyList(), "ip:log", null)
    val pages = t.eng { g -> paginate(logFields(GameOps.civOf(g, a))).size }
    check(pages > 1, "log should page ($pages)")
    check(t.act(a, "ip_spg", listOf("1"), null, "notification log with paging"), "log page")
    check(stats(st).page == 1, "log page not moved")
    fieldsOk("log p2"); t.render(a, "ip-stats-log-p2")
    t.eng { g -> GameOps.civOf(g, a).notifications.removeAll { it.text.startsWith("Selftest notification") } }

    // demographics (off by default in Unciv — turn the option on)
    t.act(a, "ip_sv", emptyList(), "ip:demo", null)
    check(t.eng { g -> demographicsFields(GameOps.civOf(g, a)).size } == 1, "demographics should be hidden by default")
    val savedDemo = t.eng { g -> g.gameParameters.showDemographics.also { g.gameParameters.showDemographics = true; g.gameParameters.showCivilizationStats = true } }
    try {
        val df = t.eng { g -> demographicsFields(GameOps.civOf(g, a)) }
        check(df.size == t.eng { g -> rankingTypes(GameOps.civOf(g, a)).size } && df.all { it.value.contains("Best") && it.value.contains("Average") }, "demographics fields")
        check(t.act(a, "ip_sv", emptyList(), "ip:demo", "demographics (rank/best/average/worst)"), "demo view")
        fieldsOk("demo"); t.render(a, "ip-stats-demographics")
    } finally { t.eng { g -> g.gameParameters.showDemographics = savedDemo } }

    // charts (need ≥ 2 turns of statsHistory)
    val hist = t.eng { g -> GameOps.civOf(g, a).statsHistory.size }
    println("  [charts] history turns: $hist")
    check(hist >= 2, "statsHistory too short ($hist)")
    check(t.act(a, "ip_sv", emptyList(), "ip:chart", null), "chart view")
    check(t.act(a, "ip_rank", emptyList(), "ip:Population", "charts (line chart per civ over turns)"), "pick ranking")
    check(stats(st).ranking == "Population", "ranking not set")
    fieldsOk("chart"); t.render(a, "ip-chart-population")
    val home = System.getProperty("civbot.home") ?: "../../.."
    val png = t.eng { g -> chartPng(GameOps.civOf(g, a), RankingType.Score) }
    java.io.File(home, "selftest-out/ip-chart-score.png").writeBytes(png)
    check(!t.act(a, "ip_rank", emptyList(), "ip:Nonsense", null), "bad ranking accepted")

    // ---- 🎯📈🎞️ shortcuts from the Victory tab
    t.act(a, "tab", emptyList(), "VICTORY", null)
    check(t.act(a, "ip_go", listOf(InfoStats.View.DEMOGRAPHICS.key), null, "victory tab shortcuts (demographics/charts/replay)"), "go demo")
    check(st.tab == STATS && stats(st).view == InfoStats.View.DEMOGRAPHICS.key, "shortcut didn't open demographics")

    // ---- 🎞️ Replay (Unciv opens it after turn 5; the short self-test game is younger, so age it for the test)
    val realTurns = t.eng { g -> g.turns }
    if (realTurns < 5) {
        check(!t.act(a, "ip_rp", listOf("1"), null, null), "replay allowed before turn 5")
        t.eng { g -> g.turns = 5 }
    }
    try { replayTest(t, st, ::check, ::fieldsOk) } finally { t.eng { g -> g.turns = realTurns } }
}

private fun InfoStats.replayTest(t: SelfTestCtx, st: PanelState, check: (Boolean, String) -> Unit, fieldsOk: (String) -> Unit) {
    val a = t.alice
    val allowed = t.eng { g -> replayAllowed(GameOps.civOf(g, a)) }
    val (first, last) = t.eng { g -> val c = GameOps.civOf(g, a); firstTurn(c) to g.turns }
    println("  [replay] allowed=$allowed turns ${first + 1}..${last + 1} historyStartTurn=${t.eng { g -> g.historyStartTurn }}")
    check(allowed, "replay should be allowed on turn ${last + 1}")
    check(t.act(a, "ip_go", listOf(REPLAY), null, null) && st.tab == REPLAY, "go replay")
    check(t.act(a, "ip_rp", listOf("first"), null, "replay (turn slider ◀/▶, jump to turn)"), "replay first")
    fieldsOk("replay"); t.render(a, "ip-replay-first")
    check(replay(st).turn == first, "replay first -> ${replay(st).turn}")
    t.act(a, "ip_rp", listOf("1"), null, null); check(replay(st).turn == minOf(first + 1, last), "replay +1")
    t.act(a, "ip_rp", listOf("10"), null, null); check(replay(st).turn == minOf(first + 11, last), "replay +10")
    check(t.act(a, "ip_rturn", emptyList(), "${(first + last) / 2 + 1}", "replay jump-to-turn popup"), "jump")
    check(replay(st).turn == (first + last) / 2, "jump -> ${replay(st).turn}")
    t.render(a, "ip-replay-mid")
    check(!t.act(a, "ip_rturn", emptyList(), "9999", null), "jump beyond the game accepted")
    check(!t.act(a, "ip_rturn", emptyList(), "soon", null), "nonsense turn accepted")
    t.act(a, "ip_rp", listOf("last"), null, null); check(replay(st).turn == last, "replay last")
    t.render(a, "ip-replay-now")
    // the history really changes the picture: on the first turn fewer tiles are owned than now
    val (o0, o1) = t.eng { g -> val c = GameOps.civOf(g, a)
        replaySummary(c, first).size to replaySummary(c, last).size }
    println("  [replay] owners on first turn: $o0, now: $o1 · ${t.eng { g -> replaySummary(GameOps.civOf(g, a), last).take(4) }}")
    check(o1 >= 1, "nobody owns anything now?")
}

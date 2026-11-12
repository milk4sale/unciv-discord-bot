package civbot

import com.unciv.Constants
import com.unciv.logic.GameInfo
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.models.stats.Stat
import com.unciv.models.stats.Stats
import com.unciv.view.GameView
import kotlin.math.roundToInt
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.managers.ReligionState
import com.unciv.models.metadata.BaseRuleset
import com.unciv.models.ruleset.Belief
import com.unciv.models.ruleset.BeliefType
import com.unciv.models.ruleset.Building
import com.unciv.models.ruleset.IRulesetObject
import com.unciv.models.ruleset.Milestone
import com.unciv.models.ruleset.MilestoneType
import com.unciv.models.ruleset.Policy
import com.unciv.models.ruleset.Ruleset
import com.unciv.models.ruleset.RulesetCache
import com.unciv.models.ruleset.Victory
import com.unciv.models.ruleset.nation.Nation
import com.unciv.models.ruleset.tech.Technology
import com.unciv.models.ruleset.tile.Terrain
import com.unciv.models.ruleset.tile.TerrainType
import com.unciv.models.ruleset.tile.TileImprovement
import com.unciv.models.ruleset.tile.TileResource
import com.unciv.models.ruleset.unique.GameContext
import com.unciv.models.ruleset.unique.UniqueType
import com.unciv.models.ruleset.unit.BaseUnit
import com.unciv.models.ruleset.unit.Promotion
import com.unciv.models.translations.tr
import com.unciv.ui.screens.victoryscreen.RankingType
import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent
import net.dv8tion.jda.api.interactions.commands.OptionType
import net.dv8tion.jda.api.interactions.commands.build.SubcommandData
import net.dv8tion.jda.api.interactions.components.ActionRow
import net.dv8tion.jda.api.utils.FileUpload
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.WeakHashMap
import javax.imageio.ImageIO

/**
 * InfoPlus — information screens:
 *  - 🏆 Victory tab: progress towards every enabled victory (yours + what Unciv's VictoryScreen reveals about others), rankings by any stat
 *  - 📖 Civilopedia tab (+ `/civ wiki query:`): browse/search the ruleset's Civilopedia text, with pixel art and Related… cross-links
 *  - 📜/🔨 picker texts: policy effects + "Policy details…", improvement yield change / Replaces / blocked reasons
 *  - ✨ Beliefs tab: extra pantheon beliefs (pantheon expansion) and free beliefs for an existing religion
 *  - 📊 Stats tab and 🎞️ Replay tab: see [InfoStats] (InfoPlusStats.kt)
 */
object InfoPlus : Extension {
    override val viewVerbs = setOf("ip_cat", "ip_item", "ip_pg", "ip_nx", "ip_search", "ip_fb", "ip_sv", "ip_spg", "ip_rank", "ip_go", "ip_rp", "ip_rturn",
        "ip_rel", "ip_vrank", "ip_pold", "ip_impwhy", "ip_pcs")

    private const val VICTORY = "VICTORY"
    private const val PEDIA = "CIVILOPEDIA"
    private const val PANTHEON = "PANTHEON"

    override val tabs = listOf(
        ExtTab(VICTORY, "Victory", "🏆", "Progress towards each victory, score ranking"),
        ExtTab(PEDIA, "Civilopedia", "📖", "Look up units, buildings, techs, terrain…"),
        ExtTab(PANTHEON, "Beliefs", "✨", "Add a belief to your pantheon or religion") { civ -> pantheonExpandable(civ) || reformable(civ) },
        ExtTab(InfoStats.STATS, "Stats", "📊", "Breakdowns, resources, religions, politics, log, demographics, charts"),
        ExtTab(InfoStats.REPLAY, "Replay", "🎞️", "Watch the world's borders grow, turn by turn") { civ -> InfoStats.replayAllowed(civ) },
    )

    override val prompts = mapOf(
        "ip_search" to Prompt("Search the Civilopedia", "What are you looking for?", "e.g. Great Library, Iron, Archer"),
        "ip_rturn" to Prompt("Replay: jump to turn", "Turn number", "e.g. 12"),
    )

    override fun renderTab(tabId: String, t: TabCtx) {
        when (tabId) {
            VICTORY -> renderVictory(t)
            PEDIA -> renderPedia(t)
            PANTHEON -> renderPantheon(t)
            InfoStats.STATS -> InfoStats.render(t)
            InfoStats.REPLAY -> InfoStats.renderReplay(t)
        }
    }

    override fun handle(verb: String, ctx: ActionCtx): Boolean {
        if (!verb.startsWith("ip_")) return false
        val v = ctx.value?.removePrefix("ip:")
        val rs = ctx.game.ruleset
        if (InfoStats.handle(verb, ctx, v)) return true
        when (verb) {
            "ip_cat" -> { val ps = pedia(ctx.st); ps.cat = v ?: ps.cat; ps.page = 0; ps.item = null; ctx.st.pages.remove(ITEM_KEY) }
            "ip_item" -> pedia(ctx.st).item = v
            "ip_pg" -> {
                // the entry dropdown pages itself (Panel, 23 per page): flip its page
                val ps = pedia(ctx.st)
                val list = entries(catOf(ps), rs, ctx.game)
                val cur = ctx.st.pages[ITEM_KEY] ?: (list.indexOfFirst { it.name == ps.item }.coerceAtLeast(0) / 23)
                ctx.st.pages[ITEM_KEY] = (cur + (ctx.args.firstOrNull()?.toIntOrNull() ?: 1)).coerceIn(0, maxOf(0, (list.size - 1) / 23))
            }
            "ip_nx" -> {
                val ps = pedia(ctx.st)
                val list = entries(catOf(ps), rs, ctx.game)
                if (list.isNotEmpty()) {
                    val i = list.indexOfFirst { it.name == ps.item }.coerceAtLeast(0)
                    val n = Math.floorMod(i + (ctx.args.firstOrNull()?.toIntOrNull() ?: 1), list.size)
                    focus(ctx.st, catOf(ps), list[n].name)
                }
            }
            "ip_search" -> {
                val q = v?.trim().orEmpty()
                val hits = search(rs, ctx.game, q)
                val best = hits.firstOrNull() ?: throw PlayerError("Nothing in the Civilopedia matches **${q.take(50)}**.")
                focus(ctx.st, best.cat, best.entry.name)
                ctx.st.tab = PEDIA
                val also = hits.drop(1).take(4).joinToString { tx(it.entry.name) }
                ctx.flash += "🔍 **${tx(best.entry.name)}** (${best.cat.label})" + (if (also.isNotEmpty()) " — also: $also" else "")
            }
            "ip_rel" -> {
                // "Related…" cross-link: value "<cat key>|<entry name>"
                val (key, name) = (v ?: throw PlayerError("Pick an entry.")).split("|", limit = 2).let { it[0] to it.getOrElse(1) { "" } }
                val cat = cats.firstOrNull { it.key == key } ?: throw PlayerError("Unknown Civilopedia category.")
                if (entries(cat, rs, ctx.game).none { it.name == name }) throw PlayerError("**${tx(name)}** isn't in this game's Civilopedia.")
                focus(ctx.st, cat, name)
                ctx.st.tab = PEDIA
            }
            "ip_vrank" -> {
                val rt = InfoStats.rankingTypes(ctx.civ).firstOrNull { it.name == v } ?: throw PlayerError("Unknown ranking.")
                ctx.st.ext["ip.vrank"] = rt.name
            }
            "ip_pold" -> {
                val p = rs.policies[v] ?: throw PlayerError("Unknown policy.")
                ctx.st.ext["ip.pold"] = p.name
            }
            "ip_impwhy" -> {
                val u = ctx.selectedUnit()
                val name = ctx.args.firstOrNull() ?: v ?: throw PlayerError("Pick an improvement.")
                ctx.flash += improvementWhy(u, name)
            }
            "ip_pan" -> {
                ctx.requireTurn()
                ctx.flash += expandPantheon(ctx.civ, v ?: throw PlayerError("Pick a belief."))
                ctx.changed = true
            }
            "ip_fb" -> picks(ctx.st)[ctx.args.firstOrNull()?.toIntOrNull() ?: 0] = v ?: ""
            "ip_fbok" -> {
                ctx.requireTurn()
                val types = freeBeliefTypes(ctx.civ)
                val p = picks(ctx.st)
                ctx.flash += addFreeBeliefs(ctx.civ, types.indices.map { p[it] ?: "" })
                p.clear(); ctx.changed = true
            }
            else -> return false
        }
        return true
    }

    // ================================================================== 🏆 victory

    private fun victoryEmoji(name: String) = when (name) {
        "Scientific" -> "🚀"; "Cultural" -> "🎭"; "Domination" -> "⚔️"; "Diplomatic" -> "🕊️"; "Time" -> "⏳"; "Religious" -> "🙏"; else -> "🏆"
    }

    /** As Unciv's VictoryScreenCivGroup: real name only for civs we know (or that are dead). */
    fun nameFor(viewer: Civilization, c: Civilization): String = when {
        c == viewer -> "**${c.civName}** (you)"
        viewer.knows(c) || c.isDefeated() || viewer.isDefeated() || viewer.isSpectator -> c.civName
        else -> "an unknown civ"
    }

    /** VictoryScreen.canViewCivStats */
    fun canViewStats(game: GameInfo, viewer: Civilization, viewed: Civilization) =
        game.victoryData != null || viewer.isSpectator || !game.gameParameters.hideOtherCivilizationStats || viewed == viewer

    /** VictoryScreen's Rankings page visibility */
    fun rankingsShown(game: GameInfo, viewer: Civilization) =
        viewer.isSpectator || game.victoryData != null ||
            (game.gameParameters.showCivilizationStats == true && game.gameParameters.showRankings)

    /** May [viewer] see [other]'s score/ranking stats? (Rankings page shown + not hidden by hideOtherCivilizationStats) */
    fun mayShowStatsOf(viewer: Civilization, other: Civilization): Boolean =
        other == viewer || (rankingsShown(viewer.gameInfo, viewer) && canViewStats(viewer.gameInfo, viewer, other))

    /** " · 123 pts" when the game's ranking settings let [viewer] see [other]'s score, else "". */
    fun scoreSuffix(viewer: Civilization, other: Civilization): String =
        if (mayShowStatsOf(viewer, other)) " · ${other.calculateTotalScore().toInt()} pts" else ""

    /** The Overview tab's ranking lines, honouring showRankings / hideOtherCivilizationStats (as the Rankings page). */
    fun rankingLines(civ: Civilization): List<String> {
        val game = civ.gameInfo
        val shown = rankingsShown(game, civ)
        val majors = game.civilizations.filter { it.isMajorCiv && canViewStats(game, civ, it) }
        return com.unciv.ui.screens.victoryscreen.RankingType.filteredEntries(game.gameParameters).mapNotNull { rt ->
            runCatching {
                val mine = civ.getStatForRanking(rt)
                if (!shown || civ.isSpectator) return@runCatching "${rt.label}: $mine"
                val ranked = majors.sortedByDescending { if (it.isDefeated()) Int.MIN_VALUE else it.getStatForRanking(rt) }
                "${rt.label}: $mine (#${ranked.indexOf(civ) + 1} of ${ranked.size})"
            }.getOrNull()
        } + (if (!shown) listOf("_Rankings of other civilizations are hidden in this game._")
             else if (game.gameParameters.hideOtherCivilizationStats && game.victoryData == null) listOf("_Ranked among the civs whose stats you may see._") else emptyList())
    }

    /** Extra lines for the milestone being worked on — the text form of Milestone.getVictoryScreenButtons. */
    private fun milestoneDetails(m: Milestone, v: Victory, civ: Civilization): List<String> {
        val game = civ.gameInfo
        val hide = civ.shouldHideCivCount()
        fun mark(done: Boolean) = if (done) "✅" else "⬜"
        return when (m.type) {
            MilestoneType.AddedSSPartsInCapital -> {
                val have = civ.victoryManager.currentsSpaceshipParts
                listOf(v.requiredSpaceshipPartsAsCounter.entries.joinToString(" · ") { (part, n) ->
                    val h = minOf(have[part], n); "${mark(h >= n)} $part $h/$n"
                })
            }
            MilestoneType.CompletePolicyBranches -> listOf(game.ruleset.policyBranches.values.joinToString(" · ") { b ->
                val finisher = b.policies.lastOrNull()?.name
                "${mark(finisher != null && civ.policies.isAdopted(finisher))} ${b.name}"
            })
            MilestoneType.CaptureAllCapitals -> {
                val capitals = game.getCities().filter { it.isOriginalCapital }.associateBy { it.foundingCivObject }
                val parts = ArrayList<String>()
                for (other in game.civilizations.filter { it.isMajorCiv }) {
                    val city = capitals[other]
                    if (city != null) {
                        val known = civ.hasExplored(city.getCenterTile())
                        if (hide && !known) continue
                        parts += "${mark(city.civ == civ)} ${if (known) city.name else "unknown city"}"
                    } else parts += "${mark(other.isDefeated())} destroy ${nameFor(civ, other)}"
                }
                if (hide) parts += "⬜ ? more"
                listOf(parts.joinToString(" · "))
            }
            MilestoneType.DestroyAllPlayers -> {
                val parts = game.civilizations.filter { it.isMajorCiv && it != civ && (!hide || civ.knows(it)) }
                    .map { "${mark(it.isDefeated())} ${nameFor(civ, it)}" } + (if (hide) listOf("⬜ ? more") else emptyList())
                listOf(parts.joinToString(" · "))
            }
            MilestoneType.WorldReligion -> {
                val rel = civ.religionManager.religion
                val parts = game.civilizations.filter { it.isMajorCiv && it.isAlive() && (!hide || civ.knows(it) || it == civ) }.map {
                    val met = rel != null && (!rel.isPantheon() || civ == it) && it.religionManager.isMajorityReligionForCiv(rel)
                    "${mark(met)} ${nameFor(civ, it)}"
                }
                listOf("Majority religion of: " + parts.joinToString(" · "))
            }
            MilestoneType.MoreCountableThanEachPlayer -> {
                val parts = game.civilizations.filter { m.getMoreCountableThanOtherCivRelevant(civ, it) && (!hide || civ.knows(it)) }.map {
                    val pct = m.getMoreCountableThanOtherCivPercent(civ, it)
                    "${mark(pct > 100f)} ${nameFor(civ, it)}" + (if (pct >= 1f && pct < 200f) " ${pct.toInt()}%" else "")
                }
                listOf(parts.joinToString(" · "))
            }
            else -> emptyList()
        }
    }

    /** Same integer math as VictoryManager.votesNeededForDiplomaticVictory (private there). */
    private fun votesNeeded(game: GameInfo, civ: Civilization): Int {
        val voters = game.civilizations.count { !it.isBarbarian && !it.isSpectator && !it.isDefeated() }
        val (_, unOwner) = civ.victoryManager.getUNBuildingAndOwnerNames()
        val count = voters + (if (unOwner != null) 1 else 0)
        if (count > 28) return count * 35 / 100
        return count * (67 - (1.1 * count).toInt()) / 100 + 1
    }

    /** VictoryScreenCivRankings, one column: every major civ whose stats the game lets [viewer] see, by [rt]
     *  (unknown civs stay "an unknown civ"; hidden entirely when the Rankings page is). */
    fun rankingField(viewer: Civilization, rt: RankingType): Pair<String, String> {
        val game = viewer.gameInfo
        val emoji = InfoStats.rankEmoji(rt)
        fun value(c: Civilization) = runCatching { c.getStatForRanking(rt) }.getOrDefault(0)
        if (!rankingsShown(game, viewer))
            return "$emoji ${rt.label}" to "Yours: **${value(viewer)}**. Rankings of other civilizations are hidden in this game."
        val ranked = game.civilizations.filter { it.isMajorCiv && canViewStats(game, viewer, it) }
            .sortedBy { it.civName }.sortedByDescending { if (it.isDefeated()) Int.MIN_VALUE else value(it) }
        val unit = if (rt == RankingType.Score) " pts" else ""
        val lines = ranked.mapIndexed { i, c -> "${i + 1}. ${nameFor(viewer, c)} — " + (if (c.isDefeated()) "☠️ defeated" else "**${value(c)}**$unit") }
        val mine = ranked.indexOf(viewer)
        val shown = if (mine >= 12) lines.take(11) + "…" + lines[mine] else lines.take(12)
        val note = if (game.gameParameters.hideOtherCivilizationStats && game.victoryData == null) "\n_Other civilizations' stats are hidden in this game._" else ""
        return "$emoji ${rt.label} ranking" to fit(shown, 1000 - note.length) + note
    }

    fun victoryFields(civ: Civilization): List<Pair<String, String>> {
        val game = civ.gameInfo
        val out = ArrayList<Pair<String, String>>()
        game.victoryData?.let { vd ->
            val winner = game.civilizations.firstOrNull { it.civID == vd.winningCiv }
            out += "🏁 Game over" to "${winner?.let { nameFor(civ, it) } ?: vd.winningCiv} won a **${vd.victoryType}** victory on turn ${vd.victoryTurn + 1}."
        }
        // Time victory is "hidden in the victory screen" in Unciv, but its max-turns clock is shown in the corner — show it here too
        val victories = civ.victoryManager.getAvailableVictories().filter { !it.hiddenInVictoryScreen || it.enablesMaxTurns() }
        val majors = game.civilizations.filter { it.isMajorCiv }
        for (v in victories) {
            val lines = ArrayList<String>()
            if (v.victoryScreenHeader.isNotBlank() && v.victoryScreenHeader != "Do things to win!") lines += "_${tx(v.victoryScreenHeader)}_"
            var firstIncomplete = true
            for (m in v.milestoneObjects) {
                if (m.type == null) continue
                val done = runCatching { m.hasBeenCompletedBy(civ) }.getOrDefault(false)
                val partial = !done && firstIncomplete
                if (!done) firstIncomplete = false
                val head = runCatching { tx(m.getVictoryScreenButtonHeaderText(done, civ)) }.getOrElse { tx(m.uniqueDescription) }
                lines += "${if (done) "✅" else if (partial) "▶️" else "⬜"} $head"
                if (partial) runCatching { milestoneDetails(m, v, civ) }.getOrDefault(emptyList()).forEach { lines += " $it" }
            }
            if (v.milestoneObjects.any { it.type == MilestoneType.WinDiplomaticVote }) {
                val (un, owner) = civ.victoryManager.getUNBuildingAndOwnerNames()
                val unName = v.milestoneObjects.firstOrNull { it.type == MilestoneType.BuildingBuiltGlobally }?.params?.firstOrNull() ?: "United Nations"
                lines += if (un != null) "🏛️ $un owned by ${game.civilizations.firstOrNull { it.civID == owner }?.let { nameFor(civ, it) } ?: "?"} (2 votes)"
                else "🏛️ Nobody has built the $unName yet"
                val next = civ.getTurnsTillNextDiplomaticVote()
                lines += "🗳️ Votes needed to win: **${votesNeeded(game, civ)}**" + (if (next != null) " · next vote in $next turn(s)" else "")
                if (game.diplomaticVictoryVotesCast.isNotEmpty()) {
                    val br = civ.victoryManager.getDiplomaticVictoryVoteBreakdown()
                    val res = br.results.entries.sortedByDescending { it.value }.joinToString(" · ") { (id, n) ->
                        (game.civilizations.firstOrNull { it.civID == id }?.let { nameFor(civ, it) } ?: id) + " $n"
                    }.ifEmpty { "no valid votes" }
                    lines += "📊 Last vote: $res — ${tx(br.winnerText).replace("\n", " ")}"
                }
            }
            if (v.enablesMaxTurns()) {
                val max = game.gameParameters.maxTurns
                lines += "⏳ Turn ${game.turns + 1} of $max — **${(max - game.turns).coerceAtLeast(0)}** turns left; the highest score then wins (see ranking)."
            }
            // Global status (as Unciv's VictoryScreenGlobalVictory): every other civ's next milestone
            val others = majors.filter { it != civ && runCatching { v in it.victoryManager.getAvailableVictories() }.getOrDefault(false) }
                .sortedWith(compareBy<Civilization> { it.isDefeated() }.thenByDescending { it.victoryManager.amountMilestonesCompleted(v) })
            if (others.isNotEmpty() && !v.enablesMaxTurns()) {
                lines += "**Others:**"
                for (o in others.take(10)) {
                    val txt = runCatching { o.victoryManager.getNextMilestone(v)?.let { tx(it.getVictoryScreenButtonHeaderText(false, o)) } ?: "Done!" }.getOrDefault("?")
                    lines += "• ${nameFor(civ, o)}${if (o.isDefeated()) " ☠️" else ""} — $txt"
                }
                if (others.size > 10) lines += "• …and ${others.size - 10} more"
            }
            out += "${victoryEmoji(v.name)} ${tx(v.name)} victory" to fit(lines, 1000)
        }
        if (victories.isEmpty()) out += "🏆 Victory" to "No victory types are enabled in this game."

        out += rankingField(civ, RankingType.Score)
        return out
    }

    private fun renderVictory(t: TabCtx) {
        t.includeAdvisor = false
        t.rows += ActionRow.of(listOfNotNull(
            t.button("ip_go", "Demographics", "🎯", 2, true, InfoStats.View.DEMOGRAPHICS.key),
            t.button("ip_go", "Charts", "📈", 2, true, InfoStats.View.CHARTS.key),
            t.button("ip_go", "Replay", "🎞️", 2, InfoStats.replayAllowed(t.civ), InfoStats.REPLAY),
            t.button("ip_go", "Stats", "📊", 2, true, InfoStats.View.BREAKDOWN.key),
        ))
        // Rankings by any stat (Unciv's Rankings page has a column per RankingType)
        val rt = InfoStats.rankingTypes(t.civ).firstOrNull { it.name == t.st.ext["ip.vrank"] } ?: RankingType.Score
        if (rankingsShown(t.game, t.civ)) t.rows += ActionRow.of(t.select("ip_vrank", "🏅 Rank every civ by…", InfoStats.rankingTypes(t.civ).map {
            Choice("ip:${it.name}", "Ranking: ${it.label}", "Every civ's ${it.label.lowercase()}, best first", InfoStats.rankEmoji(it))
        }, "ip:${rt.name}"))
        val fields = victoryFields(t.civ).let { f -> if (rt == RankingType.Score) f else f.dropLast(1) + rankingField(t.civ, rt) }
        var total = 0
        // the ranking goes first when the player picked one, so it's never cut off
        for ((name, value) in if (rt == RankingType.Score) fields else listOf(fields.last()) + fields.dropLast(1)) {
            if (total + value.length > 3600) break
            total += value.length
            t.eb.addField(name.take(256), value.take(1024), false)
        }
        t.worldMap()
    }

    // ================================================================== 📖 civilopedia


    class Cat(val key: String, val label: String, val emoji: String, val list: (Ruleset, GameInfo?) -> Collection<IRulesetObject>)

    /** Civilopedia-only entries Unciv makes up (BaseBelief overviews, movement domains), built once per ruleset so they keep their identity. */
    private val virtualCache = WeakHashMap<Ruleset, Map<String, List<IRulesetObject>>>()
    private fun virtual(rs: Ruleset, kind: String): List<IRulesetObject> = virtualCache.getOrPut(rs) {
        val religions = runCatching { Belief.getCivilopediaReligionEntry(rs) }.getOrElse {
            Belief().apply { name = "Religions"; civilopediaText = rs.religions.sorted().map { com.unciv.ui.screens.civilopediascreen.FormattedLine(it, icon = "Belief/$it") } }
        }
        mapOf(
            "beliefs" to listOf(Belief.getCivilopediaBeliefsEntry(rs), religions),
            // CivilopediaCategories.UnitType: virtual "Domain: [Land]"… entries + the unit types some unit uses
            // (Ruleset.globalUniques is internal to the engine module)
            "tutorials" to listOfNotNull((Engine.callHiddenResult(rs, "getGlobalUniques") as? com.unciv.models.ruleset.GlobalUniques)?.takeIf { it.hasUniques() }),
            "unittypes" to runCatching { com.unciv.models.ruleset.unit.UnitType.getCivilopediaIterator(rs).toList() }.getOrDefault(rs.unitTypes.values.toList()),
        )
    }[kind].orEmpty()

    private val cats = listOf(
        Cat("units", "Units", "⚔️") { rs, _ -> rs.units.values },
        Cat("buildings", "Buildings", "🏠") { rs, _ -> rs.buildings.values.filter { !it.isAnyWonder() } },
        Cat("wonders", "Wonders", "🏛️") { rs, _ -> rs.buildings.values.filter { it.isAnyWonder() } },
        Cat("techs", "Technologies", "🔬") { rs, _ -> rs.technologies.values },
        Cat("policies", "Policies", "📜") { rs, _ -> rs.policies.values },
        // CivilopediaCategories.Belief: the beliefs + Unciv's "Beliefs" and "Religions" overview entries
        Cat("beliefs", "Religions and beliefs", "🙏") { rs, _ -> rs.beliefs.values + virtual(rs, "beliefs") },
        Cat("resources", "Resources", "💎") { rs, _ -> rs.tileResources.values },
        Cat("improvements", "Tile improvements", "🔨") { rs, _ -> rs.tileImprovements.values },
        Cat("terrains", "Terrains", "🌲") { rs, _ -> rs.terrains.values },
        Cat("promotions", "Promotions", "⭐") { rs, _ -> rs.unitPromotions.values },
        Cat("unittypes", "Unit types", "🛡️") { rs, _ -> virtual(rs, "unittypes") },
        Cat("nations", "Nations", "🏳️") { rs, _ -> rs.nations.values.filter { !it.isSpectator } },
        // The Personalities file contains unused chaff: only those some nation uses (CivilopediaCategories.Personality)
        Cat("personalities", "Personalities", "🎭") { rs, _ -> rs.personalities.values.filter { p -> rs.nations.values.any { it.personality == p.name } } },
        Cat("eras", "Eras", "📅") { rs, _ -> rs.eras.values },
        Cat("victories", "Victory types", "🏆") { rs, g -> rs.victories.values.filter { g == null || it.name in g.gameParameters.victoryTypes } },
        Cat("unitnames", "Unit names", "🏷️") { rs, _ -> rs.unitNameGroups.values.filter { it.unitNames.isNotEmpty() && it.getUnits(rs).isNotEmpty() } },
        // CivilopediaCategories.Tutorial: tutorials + the ruleset's Global Uniques when it has any to show
        Cat("tutorials", "Tutorials", "❓") { rs, _ -> rs.tutorials.values + virtual(rs, "tutorials") },
        Cat("difficulties", "Difficulty levels", "🎚️") { rs, _ -> rs.difficulties.values },
        Cat("speeds", "Game speeds", "⏱️") { rs, _ -> rs.speeds.values },
    )

    // ---- cross-links (FormattedLine.link "Category/Name")

    /** makeLink() -> (category, entry) for every Civilopedia entry of the ruleset. */
    private val linkIndex = WeakHashMap<Ruleset, Map<String, Pair<Cat, IRulesetObject>>>()
    private fun linkMap(rs: Ruleset): Map<String, Pair<Cat, IRulesetObject>> = linkIndex.getOrPut(rs) {
        val m = LinkedHashMap<String, Pair<Cat, IRulesetObject>>()
        for (c in cats) for (e in runCatching { c.list(rs, null) }.getOrDefault(emptyList())) {
            val link = runCatching { e.makeLink() }.getOrDefault("")
            if (link.isNotEmpty()) m.putIfAbsent(link, c to e)
            m.putIfAbsent("${c.key}|${e.name}", c to e)
        }
        m
    }

    /** Resolve an Unciv link ("Unit/Warrior", "Building/X" for a wonder…) to a Civilopedia entry shown in this game. */
    fun resolveLink(rs: Ruleset, game: GameInfo?, link: String): Pair<Cat, IRulesetObject>? {
        val m = linkMap(rs)
        val name = link.substringAfter('/', "")
        // Unciv's links say "Building/" for wonders too (and vice versa in older mods)
        val hit = m[link] ?: m["Wonder/$name"].takeIf { link.startsWith("Building/") } ?: m["Building/$name"].takeIf { link.startsWith("Wonder/") }
            ?: return null
        val (cat, e) = hit
        return if (entries(cat, rs, game).any { it === e || it.name == e.name }) hit else null
    }

    private val linksCache = WeakHashMap<IRulesetObject, List<String>>()

    /** The entries [e]'s Civilopedia text links to (Unciv's clickable lines), in order, without itself. */
    fun relatedEntries(e: IRulesetObject, rs: Ruleset, game: GameInfo?): List<Pair<Cat, IRulesetObject>> {
        val links = linksCache.getOrPut(e) {
            runCatching { e.assembleCivilopediaText(rs).civilopediaText }.recoverCatching { e.getCivilopediaTextLines(rs) }.getOrDefault(emptyList())
                .filter { it.linkType == com.unciv.ui.screens.civilopediascreen.FormattedLine.LinkType.Internal }.map { it.link }.distinct()
        }
        val self = runCatching { e.makeLink() }.getOrDefault("")
        return links.filter { it != self }.mapNotNull { resolveLink(rs, game, it) }.filter { it.second.name != e.name }.distinctBy { it.first.key + "|" + it.second.name }
    }

    class PediaState(var cat: String = "units", var item: String? = null, var page: Int = 0)
    private fun pedia(st: PanelState) = st.ext.getOrPut("ip.pedia") { PediaState() } as PediaState
    private fun catOf(ps: PediaState) = cats.firstOrNull { it.key == ps.cat } ?: cats.first()
    /** Panel's paging key of the entry dropdown */
    private const val ITEM_KEY = "ip_item"
    /** Show [name] of [cat], and let the entry dropdown jump to its page. */
    private fun focus(st: PanelState, cat: Cat, name: String) {
        val ps = pedia(st); ps.cat = cat.key; ps.item = name; st.pages.remove(ITEM_KEY)
    }

    fun entries(cat: Cat, rs: Ruleset, game: GameInfo?): List<IRulesetObject> =
        runCatching { cat.list(rs, game) }.getOrDefault(emptyList())
            .filter { e -> !runCatching { e.isHiddenFromCivilopedia(game, rs) }.getOrDefault(false) }
            .sortedBy { tx(it.name).lowercase() }

    private val iconChars = mapOf('†' to "⚔️", '‡' to "🏹", '➡' to "👣", '♡' to "❤️", '⚙' to "⚙️", '¤' to "💰", '⁂' to "🍞",
        '⍾' to "🔬", '♪' to "🎭", '⌣' to "😊", '☮' to "🙏", '✯' to "⭐", '♬' to "🎨", '⚒' to "🛠️", '⛤' to "🎖️", '⚖' to "⚖️", '⚛' to "⚛️")

    /** Translate Unciv markup ([placeholders], {sentences}) to plain text; font icons to emoji. */
    fun tx(s: String): String {
        if (s.isEmpty()) return s
        val t = runCatching { s.tr(hideIcons = true, hideStats = true) }.getOrElse { s }
        val sb = StringBuilder(t.length)
        for (ch in t) {
            val e = iconChars[ch]
            when {
                e != null -> sb.append(e)
                ch in ''..'' || ch == '[' || ch == ']' || ch == '{' || ch == '}' -> {}
                else -> sb.append(ch)
            }
        }
        return sb.toString().trim()
    }

    private val textCache = WeakHashMap<IRulesetObject, List<String>>()

    /** The entry's Civilopedia page as Discord-markdown lines (Unciv's assembleCivilopediaText, cleaned). */
    fun pediaLines(e: IRulesetObject, rs: Ruleset): List<String> = textCache.getOrPut(e) {
        val lines = runCatching { e.assembleCivilopediaText(rs).civilopediaText }
            .recoverCatching { e.getCivilopediaTextLines(rs) }.getOrDefault(emptyList())
        val out = ArrayList<String>()
        for ((i, l) in lines.withIndex()) {
            if (i == 0 && l.header > 0 && l.text == e.name) continue // the title, shown as the field name
            val txt = runCatching { tx(l.text) }.getOrDefault("")
            if (l.separator || txt.isEmpty()) { if (out.isNotEmpty() && out.last().isNotEmpty()) out += ""; continue }
            val pad = " ".repeat(l.indent.coerceIn(0, 3))
            out += when {
                l.header in 1..3 -> "**$txt**"
                l.header > 3 -> "__${txt}__"
                l.starred -> "$pad✦ $txt"
                l.indent > 0 -> "$pad• $txt"
                else -> txt
            }
        }
        while (out.isNotEmpty() && out.last().isEmpty()) out.removeAt(out.size - 1)
        if (out.isEmpty()) out += e.uniques.map { "• " + tx(it) }.ifEmpty { listOf("_No Civilopedia text for this entry._") }
        out
    }

    private fun shortDesc(e: IRulesetObject): String = runCatching {
        when (e) {
            is BaseUnit -> "${e.unitType} · ⚔️${e.strength}" + (if (e.rangedStrength > 0) " 🏹${e.rangedStrength}" else "") + (if (e.cost > 0) " · ${e.cost}⚙️" else "")
            is Building -> (if (e.cost > 0) "${e.cost}⚙️" else "") + (e.requiredTech?.let { " · needs $it" } ?: "")
            is Technology -> "${e.era()} · ${e.cost}🔬"
            is Policy -> runCatching { e.branch.name }.getOrDefault("")
            is Belief -> "${e.type.name} belief"
            is TileResource -> "${e.resourceType.name} resource"
            is Terrain -> e.type.name.replace("TerrainFeature", "Feature").replace("NaturalWonder", "Natural wonder")
            is Nation -> if (e.isCityState) "City-state" else tx(e.leaderName)
            else -> ""
        }
    }.getOrDefault("").take(100)

    /** Greedy-pack lines into chunks of at most [max] chars (long lines are cut). */
    private fun chunks(lines: List<String>, max: Int): List<String> {
        val out = ArrayList<String>()
        val sb = StringBuilder()
        for (raw in lines) {
            val line = if (raw.length > max) raw.take(max - 1) + "…" else raw
            if (sb.length + line.length + 1 > max) { out += sb.toString().trim(); sb.clear() }
            sb.append(line).append('\n')
        }
        if (sb.isNotBlank()) out += sb.toString().trim()
        return out.filter { it.isNotBlank() }
    }

    fun fitLines(lines: List<String>, max: Int) = fit(lines, max)

    private fun fit(lines: List<String>, max: Int): String {
        val c = chunks(lines, max - 2)
        return when {
            c.isEmpty() -> "—"
            c.size == 1 -> c[0]
            else -> c[0] + "\n…"
        }
    }

    data class Hit(val cat: Cat, val entry: IRulesetObject, val score: Int)

    private fun norm(s: String) = s.lowercase().filter { it.isLetterOrDigit() || it == ' ' }.trim()

    private fun lev(a: String, b: String): Int {
        val d = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            var prev = d[0]; d[0] = i
            for (j in 1..b.length) {
                val tmp = d[j]
                d[j] = minOf(d[j] + 1, d[j - 1] + 1, prev + if (a[i - 1] == b[j - 1]) 0 else 1)
                prev = tmp
            }
        }
        return d[b.length]
    }

    /** Best Civilopedia matches for [query], best first: name matches, then fuzzy names, then full text. */
    fun search(rs: Ruleset, game: GameInfo?, query: String): List<Hit> {
        val q = norm(query)
        if (q.isEmpty()) return emptyList()
        val all = cats.flatMap { c -> entries(c, rs, game).map { c to it } }
        val hits = ArrayList<Hit>()
        for ((c, e) in all) {
            val full = norm(tx(e.name))
            val n = full.removePrefix("the ")
            val s = when {
                n == q || full == q -> 1000
                n.startsWith(q) -> 800 - (n.length - q.length).coerceAtMost(100)
                n.split(' ').any { it.startsWith(q) } -> 650 - (n.length - q.length).coerceAtMost(100)
                n.contains(q) -> 550
                else -> {
                    // typo-tolerant: whole name, or any run of words as long as the query
                    val qw = q.split(' ').size
                    val words = n.split(' ')
                    val d = (listOf(n) + (0..(words.size - qw).coerceAtLeast(0)).map { words.drop(it).take(qw).joinToString(" ") })
                        .minOf { lev(it, q) }
                    if (d <= maxOf(1, q.length / 4)) 450 - 40 * d - (n.length - q.length).coerceIn(0, 40) else 0
                }
            }
            if (s > 0) hits += Hit(c, e, s * 100 - cats.indexOf(c))
        }
        if (hits.isEmpty()) for ((c, e) in all) {
            val text = norm(pediaLines(e, rs).joinToString(" "))
            if (text.contains(q)) hits += Hit(c, e, 200 * 100 - cats.indexOf(c))
        }
        return hits.sortedByDescending { it.score }
    }

    private fun renderPedia(t: TabCtx) {
        t.includeAdvisor = false
        val rs = t.game.ruleset
        val ps = pedia(t.st)
        val cat = catOf(ps)
        val list = entries(cat, rs, t.game)
        val item = list.firstOrNull { it.name == ps.item } ?: list.firstOrNull()
        ps.item = item?.name

        t.rows += ActionRow.of(t.select("ip_cat", "Category…", cats.map { c ->
            Choice("ip:${c.key}", c.label, "${entries(c, rs, t.game).size} entries", c.emoji)
        }, "ip:${cat.key}"))
        // the whole category: Panel pages it (◀/▶ entries inside the dropdown)
        t.rows += ActionRow.of(t.select("ip_item", "${cat.label} (${list.size})",
            list.map { Choice("ip:${it.name}", tx(it.name), shortDesc(it), cat.emoji) }, item?.let { "ip:${it.name}" }))
        // Unciv's clickable cross-links, as a "Related…" dropdown
        val related = item?.let { relatedEntries(it, rs, t.game) }.orEmpty()
        if (related.isNotEmpty()) t.rows += ActionRow.of(t.select("ip_rel", "🔗 Related… (${related.size})",
            related.map { (c, e) -> Choice("ip:${c.key}|${e.name}".take(100), tx(e.name), c.label, c.emoji) }))
        t.endRow += t.button("ip_nx", "Prev", "⏮️", 2, list.size > 1, "-1")
        t.endRow += t.button("ip_nx", "Next", "⏭️", 2, list.size > 1, "1")
        t.endRow += t.askButton("ip_search", "Search…", "🔍", 1)
        if (item == null) {
            t.eb.addField("📖 ${cat.label}", "Nothing in this category.", false)
            t.worldMap(); return
        }
        val parts = chunks(pediaLines(item, rs), 1024)
        var total = 0
        for ((i, p) in parts.withIndex()) {
            if (total + p.length > 3000) { t.eb.addField("…", "_(truncated — `/civ wiki` shows more)_", false); break }
            total += p.length
            t.eb.addField(if (i == 0) "${cat.emoji} ${tx(item.name)}".take(256) else "​", p, false)
        }
        val png = runCatching { pixelArt(cat.key, item, rs) }.getOrNull()
        if (png != null) t.file = FileUpload.fromData(png, "map.png") else t.worldMap()
    }

    // ---- pixel art (FantasyHex tiles/units, Unciv icons)

    private val androidDir by lazy { File(Engine.uncivDir, "android") }
    private val hexDir by lazy { File(androidDir, "Images.Tilesets/TileSets/FantasyHex") }
    private val imgCache = HashMap<String, BufferedImage?>()
    private fun img(f: File): BufferedImage? = imgCache.getOrPut(f.path) { if (f.isFile) runCatching { ImageIO.read(f) }.getOrNull() else null }
    private fun hexTile(name: String) = img(File(hexDir, "Tiles/$name.png"))
    private fun icon(path: String) = img(File(androidDir, "$path.png"))
    /** PortraitNation: the nation's icon, or the generic city-state one. */
    fun nationIcon(nation: Nation): BufferedImage? = icon("Images.NationIcons/NationIcons/${nation.name}")
        ?: if (nation.isCityState) icon("Images.NationIcons/NationIcons/CityState") else null
    /** Unciv's icons are black silhouettes the game tints: use the alpha as a mask in [tint]. */
    fun tinted(iconImg: BufferedImage, tint: Color): BufferedImage {
        val ic = BufferedImage(iconImg.width, iconImg.height, BufferedImage.TYPE_INT_ARGB)
        val rgb = tint.rgb and 0xFFFFFF
        for (y in 0 until iconImg.height) for (x in 0 until iconImg.width) ic.setRGB(x, y, (iconImg.getRGB(x, y) and 0xFF000000.toInt()) or rgb)
        return ic
    }

    private fun png(i: BufferedImage): ByteArray = ByteArrayOutputStream().also { ImageIO.write(i, "png", it) }.toByteArray()
    private val BG = Color(43, 45, 49)

    /** Stack 32px tile layers bottom-aligned (like the map renderer) and blow them up ×[scale]. */
    private fun stack(layers: List<BufferedImage>, scale: Int = 8): ByteArray? {
        if (layers.isEmpty()) return null
        val w = layers.maxOf { it.width } * scale; val h = layers.maxOf { it.height } * scale
        val pad = 4 * scale
        val out = BufferedImage(w + 2 * pad, h + 2 * pad, BufferedImage.TYPE_INT_ARGB)
        val g = out.createGraphics()
        g.color = BG; g.fillRect(0, 0, out.width, out.height)
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR)
        for (l in layers) g.drawImage(l, pad + (w - l.width * scale) / 2, pad + h - l.height * scale, l.width * scale, l.height * scale, null)
        g.dispose()
        return png(out)
    }

    /** An Unciv icon, white (or [tint]), on a coloured disc. */
    private fun disc(iconImg: BufferedImage?, color: Color, tint: Color? = null): ByteArray? {
        iconImg ?: return null
        val size = 256
        val out = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
        val g = out.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
        g.color = BG; g.fillRect(0, 0, size, size)
        g.color = color; g.fillOval(16, 16, size - 32, size - 32)
        g.color = color.darker(); g.stroke = BasicStroke(6f); g.drawOval(16, 16, size - 32, size - 32)
        // Unciv's icons are black silhouettes that the game tints: use them as an alpha mask
        val ic = BufferedImage(iconImg.width, iconImg.height, BufferedImage.TYPE_INT_ARGB)
        val rgb = (tint ?: Color.WHITE).rgb and 0xFFFFFF
        for (y in 0 until iconImg.height) for (x in 0 until iconImg.width) ic.setRGB(x, y, (iconImg.getRGB(x, y) and 0xFF000000.toInt()) or rgb)
        val s = 150
        g.drawImage(ic, (size - s) / 2, (size - s) / 2, s, s, null)
        g.dispose()
        return png(out)
    }

    /** A plain title card for entries without art. */
    private fun card(title: String, sub: String): ByteArray {
        val out = BufferedImage(512, 160, BufferedImage.TYPE_INT_ARGB)
        val g = out.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        g.color = BG; g.fillRect(0, 0, 512, 160)
        g.color = Color(201, 162, 39); g.fillRect(0, 0, 8, 160)
        g.color = Color.WHITE; g.font = Font(Font.SERIF, Font.BOLD, 40)
        g.drawString(title.take(22), 32, 80)
        g.color = Color(180, 180, 190); g.font = Font(Font.SANS_SERIF, Font.PLAIN, 22)
        g.drawString(sub.take(40), 32, 120)
        g.dispose()
        return png(out)
    }

    private fun awt(c: com.badlogic.gdx.graphics.Color) =
        Color((c.r * 255).toInt().coerceIn(0, 255), (c.g * 255).toInt().coerceIn(0, 255), (c.b * 255).toInt().coerceIn(0, 255))

    /** Base terrain (+ feature) to show something allowed on [names] (a resource's / improvement's terrains) on. */
    private fun groundFor(names: Collection<String>, rs: Ruleset): List<String> {
        val terrains = names.mapNotNull { rs.terrains[it] }
        val base = terrains.firstOrNull { it.type == TerrainType.Land } ?: terrains.firstOrNull { it.type.isBaseTerrain }
        val feature = if (base == null) terrains.firstOrNull { it.type == TerrainType.TerrainFeature } else null
        val ground = base?.name ?: feature?.occursOn?.firstOrNull { rs.terrains[it]?.type == TerrainType.Land }
            ?: feature?.occursOn?.firstOrNull { rs.terrains[it]?.type?.isBaseTerrain == true } ?: "Grassland"
        return listOfNotNull(ground, feature?.name)
    }

    private fun layered(ground: List<String>, top: String): List<BufferedImage> {
        hexTile((ground + top).joinToString("+"))?.let { return listOf(it) }
        val t = hexTile(top) ?: return emptyList()
        val base = hexTile(ground.joinToString("")) ?.let { listOf(it) } ?: ground.mapNotNull { hexTile(it) }
        return base + t
    }

    fun pixelArt(catKey: String, e: IRulesetObject, rs: Ruleset): ByteArray {
        val art: ByteArray? = when (e) {
            is BaseUnit -> {
                val sprite = img(File(hexDir, "Units/${e.name}.png"))
                if (sprite != null) stack(listOfNotNull(hexTile(if (e.isWaterUnit) "Ocean" else "Grassland"), sprite))
                else disc(icon("Images.ConstructionIcons/UnitIcons/${e.name}"), Color(70, 90, 120))
            }
            is Building -> disc(icon("Images.ConstructionIcons/BuildingIcons/${e.name}"), if (e.isAnyWonder()) Color(176, 136, 40) else Color(130, 90, 50))
            is Technology -> disc(icon("Images.Tech/TechIcons/${e.name}"), Color(40, 90, 160))
            is Policy -> disc(icon("Images.PolicyIcons/PolicyIcons/${e.name}") ?: icon("Images.PolicyIcons/PolicyBranchIcons/${e.name}"), Color(140, 60, 130))
            is Belief -> disc(icon("Images.ReligionIcons/ReligionIcons/${e.name}"), Color(60, 150, 160))
            is Promotion -> disc(icon("Images.UnitPromotionIcons/UnitPromotionIcons/${e.name}")
                ?: icon("Images.UnitPromotionIcons/UnitPromotionIcons/${e.name.replace(Regex(" [IVX]+$"), "")}"), Color(60, 120, 60))
            is Nation -> disc(nationIcon(e), awt(e.getOuterColor()), awt(e.getInnerColor()))
            is Victory -> disc(icon("Images.NationIcons/VictoryTypeIcons/${e.name}"), Color(150, 40, 40))
            is com.unciv.models.ruleset.unit.UnitNameGroup -> e.getUnits(rs).firstOrNull()?.let { u ->
                img(File(hexDir, "Units/${u.name}.png"))?.let { stack(listOfNotNull(hexTile(if (u.isWaterUnit) "Ocean" else "Grassland"), it)) }
                    ?: disc(icon("Images.ConstructionIcons/UnitIcons/${u.name}"), Color(70, 90, 120))
            }
            is Terrain -> when (e.type) {
                TerrainType.Land, TerrainType.Water -> stack(listOfNotNull(hexTile(e.name)))
                TerrainType.NaturalWonder -> stack(listOfNotNull(hexTile(e.name)).ifEmpty { layered(groundFor(e.occursOn, rs), e.name) })
                TerrainType.TerrainFeature -> {
                    val ground = "Grassland".takeIf { it in e.occursOn } ?: e.occursOn.firstOrNull { rs.terrains[it]?.type == TerrainType.Land }
                        ?: e.occursOn.firstOrNull { rs.terrains[it]?.type?.isBaseTerrain == true } ?: "Grassland"
                    // "GrasslandForest" style variants are overlays too: always lay the ground first
                    val top = hexTile("$ground${e.name}") ?: hexTile("$ground+${e.name}") ?: hexTile(e.name)
                    stack(listOfNotNull(hexTile(ground), top).takeIf { top != null }.orEmpty())
                }
            }
            is TileResource -> stack(layered(groundFor(e.terrainsCanBeFoundOn, rs), e.name))
                ?: disc(icon("Images.Icons/ResourceIcons/${e.name}"), Color(90, 110, 60))
            is TileImprovement -> stack(layered(groundFor(e.terrainsCanBeBuiltOn, rs), e.name))
                ?: disc(icon("Images.Icons/ImprovementIcons/${e.name}"), Color(110, 90, 60))
            else -> null
        }
        return art ?: card(tx(e.name), cats.firstOrNull { it.key == catKey }?.label ?: "Civilopedia")
    }

    // ---- /civ wiki

    private val baseRuleset by lazy { RulesetCache[BaseRuleset.Civ_V_GnK.fullName] ?: RulesetCache.getVanillaRuleset() }

    class WikiResult(val title: String, val body: String, val also: List<String>, val art: ByteArray?)

    /** Engine thread. */
    fun wiki(rs: Ruleset, game: GameInfo?, query: String): WikiResult? {
        val hits = search(rs, game, query)
        val best = hits.firstOrNull() ?: return null
        val body = chunks(pediaLines(best.entry, rs), 3800).firstOrNull().orEmpty()
        return WikiResult("${best.cat.emoji} ${tx(best.entry.name)} — ${best.cat.label}", body,
            hits.drop(1).take(6).map { tx(it.entry.name) }.distinct(), runCatching { pixelArt(best.cat.key, best.entry, rs) }.getOrNull())
    }

    override fun commands(): List<SubcommandData> = listOf(
        SubcommandData("wiki", "Look something up in the Civilopedia")
            .addOption(OptionType.STRING, "query", "Unit, building, tech, terrain, belief…", true)
    )

    override fun onCommand(event: SlashCommandInteractionEvent, bot: BotApi): Boolean {
        if (event.subcommandName != "wiki") return false
        val query = event.getOption("query")?.asString?.trim().orEmpty().take(100)
        val channelId = event.channel.id
        val userId = event.user.id
        event.deferReply(true).queue { hook ->
            bot.async {
                try {
                    val game = bot.gameHere(channelId, userId)?.game
                    val res = if (game != null) Engine.withGame(game) { g -> wiki(g.ruleset, g, query) }
                    else Engine.run { wiki(baseRuleset, null, query) }
                    if (res == null) { hook.editOriginal("🔍 Nothing in the Civilopedia matches **${query.take(60)}**.").queue(); return@async }
                    val eb = EmbedBuilder().setColor(Color(201, 162, 39)).setTitle(res.title.take(256)).setDescription(res.body.take(4000))
                    if (res.also.isNotEmpty()) eb.setFooter("Also matching: " + res.also.joinToString(", ").take(200))
                    if (res.art != null) {
                        eb.setThumbnail("attachment://pedia.png")
                        hook.editOriginalEmbeds(eb.build()).setFiles(FileUpload.fromData(res.art, "pedia.png")).queue()
                    } else hook.editOriginalEmbeds(eb.build()).queue()
                } catch (e: Exception) {
                    hook.editOriginal("💥 Civilopedia lookup failed: ${e.javaClass.simpleName}").queue()
                }
            }
        }
        return true
    }

    // ================================================================== ✨ pantheon expansion / free beliefs

    /** Unciv's CivView.canExpandPantheon: already a pantheon and a free pantheon belief is waiting. */
    fun pantheonExpandable(civ: Civilization): Boolean = civ.gameInfo.isReligionEnabled() &&
        civ.religionManager.religionState == ReligionState.Pantheon && civ.religionManager.canFoundOrExpandPantheon()

    /** Unciv's "Reform Religion" next-turn action: free beliefs outside founding/enhancing (which fold them in). */
    fun reformable(civ: Civilization): Boolean {
        val rm = civ.religionManager
        return civ.gameInfo.isReligionEnabled() && rm.hasFreeBeliefs() && !pantheonExpandable(civ) &&
            (rm.religionState == ReligionState.Religion || rm.religionState == ReligionState.EnhancedReligion)
    }

    /** PantheonPickerScreen.beliefIsAllowed */
    private fun beliefAllowed(b: Belief, civ: Civilization): Boolean {
        if (b.getMatchingUniques(UniqueType.OnlyAvailable, GameContext.IgnoreConditionals).any { !it.conditionalsApply(civ.state) }) return false
        if (b.getMatchingUniques(UniqueType.Unavailable, civ.state).any()) return false
        return true
    }

    private fun beliefDesc(b: Belief) = b.uniques.joinToString("; ") { tx(it) }

    /** Pantheon beliefs nobody holds yet that this civ may take (as PantheonPickerScreen). */
    fun pantheonChoices(civ: Civilization): List<Choice> = civ.gameInfo.ruleset.beliefs.values
        .filter { it.type == BeliefType.Pantheon && civ.religionManager.getReligionWithBelief(it) == null && beliefAllowed(it, civ) }
        .map { Choice("ip:${it.name}", it.name, beliefDesc(it).take(100), "✨") }.take(25)

    /** PantheonPickerScreen's OK action. */
    fun expandPantheon(civ: Civilization, name: String): String {
        val rm = civ.religionManager
        if (!pantheonExpandable(civ)) throw PlayerError("You can't add a pantheon belief right now.")
        val b = civ.gameInfo.ruleset.beliefs[name] ?: throw PlayerError("Unknown belief.")
        if (b.type != BeliefType.Pantheon || rm.getReligionWithBelief(b) != null || !beliefAllowed(b, civ))
            throw PlayerError("**${b.name}** isn't available any more — pick another.")
        rm.chooseBeliefs(listOf(b), useFreeBeliefs = rm.usingFreeBeliefs())
        return "✨ Your pantheon now also follows **${b.name}**."
    }

    fun freeBeliefTypes(civ: Civilization): List<BeliefType> =
        civ.religionManager.freeBeliefsAsEnums().flatMap { (t, n) -> List(n) { t } }.sortedBy { it.ordinal }

    @Suppress("UNCHECKED_CAST")
    private fun picks(st: PanelState) = st.ext.getOrPut("ip.fb") { HashMap<Int, String>() } as HashMap<Int, String>

    /** As ReligiousBeliefsPickerScreen.loadRightTable: right type, not ours already, not another religion's, not blocked. */
    fun freeBeliefChoices(civ: Civilization, type: BeliefType, exclude: Collection<String>): List<Choice> {
        val rm = civ.religionManager
        val current = rm.religion
        return civ.gameInfo.ruleset.beliefs.values
            .filter { it.type == type || type == BeliefType.Any }
            .filter { it.name !in exclude && current?.hasBelief(it.name) != true }
            .filter { val r = rm.getReligionWithBelief(it); r == null || r == current }
            .filter { beliefAllowed(it, civ) }
            .map { Choice("ip:${it.name}", it.name, "${it.type.name} · " + beliefDesc(it).take(80), "✨") }.take(25)
    }

    /** ReligiousBeliefsPickerScreen's OK action (no founding/naming in this case). */
    fun addFreeBeliefs(civ: Civilization, names: List<String>): String {
        if (!reformable(civ)) throw PlayerError("You have no free beliefs to add right now.")
        val types = freeBeliefTypes(civ)
        if (names.size < types.size || names.any { it.isEmpty() }) throw PlayerError("Pick all ${types.size} beliefs first.")
        val chosen = names.map { civ.gameInfo.ruleset.beliefs[it] ?: throw PlayerError("Unknown belief $it.") }
        if (chosen.distinct().size != chosen.size) throw PlayerError("Pick different beliefs.")
        chosen.forEachIndexed { i, b ->
            if (freeBeliefChoices(civ, types[i], emptyList()).none { it.id == "ip:${b.name}" }) throw PlayerError("**${b.name}** isn't available any more — pick another.")
        }
        val rm = civ.religionManager
        rm.chooseBeliefs(chosen, rm.usingFreeBeliefs())
        return "⛪ ${rm.religion?.getReligionDisplayName()} adopts ${chosen.joinToString { "**${it.name}**" }}."
    }

    private fun renderPantheon(t: TabCtx) {
        val civ = t.civ
        val rm = civ.religionManager
        val rs = civ.gameInfo.ruleset
        when {
            pantheonExpandable(civ) -> {
                val current = rm.religion?.getAllBeliefsOrdered()?.joinToString { it.name } ?: "—"
                val choices = pantheonChoices(civ)
                t.eb.addField("✨ Expand your pantheon",
                    "Your pantheon follows **$current**. You may add one more pantheon belief" +
                        (if (rm.freeBeliefs[BeliefType.Pantheon.name] > 0) " (free)" else "") + " — pick it below.", false)
                t.eb.addField("Available beliefs", fit(choices.mapNotNull { c -> rs.beliefs[c.label]?.let { "**${it.name}** — ${beliefDesc(it)}" } }, 1024), false)
                t.rows += ActionRow.of(t.select("ip_pan", "Add a pantheon belief…", choices, enabled = t.myTurn))
            }
            reformable(civ) -> {
                val types = freeBeliefTypes(civ)
                val p = picks(t.st)
                t.eb.addField("✨ Free beliefs for ${rm.religion?.getReligionDisplayName() ?: "your religion"}",
                    "You may add **${types.size}** belief(s): ${types.joinToString { it.name }}.\nCurrent: " +
                        (rm.religion?.getAllBeliefsOrdered()?.joinToString { it.name } ?: "—"), false)
                if (types.size > 3) t.eb.addField("Too many to pick here", "Press 🤖 Auto-pick and the advisor will choose them.", false)
                else {
                    types.forEachIndexed { i, type ->
                        val others = p.filterKeys { it != i }.values
                        t.rows += ActionRow.of(t.select("ip_fb", "${type.name} belief…", freeBeliefChoices(civ, type, others),
                            p[i]?.let { "ip:$it" }, t.myTurn, i.toString()))
                    }
                    t.endRow += t.button("ip_fbok", "Add beliefs", "⛪", 3, t.myTurn && types.indices.all { !p[it].isNullOrEmpty() })
                }
            }
            else -> t.eb.addField("✨ Beliefs", "Nothing to pick right now.", false)
        }
        t.worldMap()
    }

    // ================================================================== 📜 policy picker / 🔨 improvement picker texts

    /** What a policy does, as one cleaned line (its visible uniques, as PolicyPickerScreen's description). */
    fun policyEffect(p: Policy): String =
        p.uniqueObjects.filter { !it.isHiddenToUsers() }.joinToString("; ") { tx(it.getDisplayText()) }

    /** The "Adopt a policy…" option description: the effect, ≤ 100 chars. */
    fun policyDesc(p: Policy): String {
        val effect = policyEffect(p).ifEmpty { if (p.branch == p) "Opens the ${p.name} tree" else p.branch.name }
        val text = if (p.branch == p) "Opens tree: $effect" else effect
        return if (text.length > 100) text.take(99) + "…" else text
    }

    private fun policyStatus(civ: Civilization, p: Policy): String {
        val pm = civ.policies
        return when {
            pm.isAdopted(p.name) -> "✅ adopted"
            pm.isAdoptable(p) -> if (pm.canAdoptPolicy()) "🟢 can adopt now" else "🟡 next when you have the culture"
            else -> {
                val missing = p.requires.orEmpty().filter { !pm.isAdopted(it) }
                "🔒 " + (if (missing.isNotEmpty()) "needs ${missing.joinToString()}" else "locked")
            }
        }
    }

    /** Policies tab: a "📜 Policy details…" dropdown and the full text of the policy picked there. */
    fun policyDetails(civ: Civilization, st: PanelState, gid: String, eb: EmbedBuilder, rows: MutableList<ActionRow>) {
        val rs = civ.gameInfo.ruleset
        val pm = civ.policies
        val all = rs.policies.values.filter { it.policyBranchType != Policy.PolicyBranchType.BranchComplete }
        // available ones first, then by branch order (as the policy screen)
        val ordered = all.sortedWith(compareBy<Policy> { if (pm.isAdoptable(it)) 0 else if (pm.isAdopted(it.name)) 2 else 1 }
            .thenBy { rs.policyBranches.keys.indexOf(it.branch.name) }.thenBy { it.policyBranchType.ordinal })
        val sel = (st.ext["ip.pold"] as? String)?.let { rs.policies[it] }
        if (sel != null) {
            val head = "${policyStatus(civ, sel)} · ${if (sel.branch == sel) "branch" else sel.branch.name}"
            eb.addField("📜 ${tx(sel.name)}".take(256), fit(listOf(head) + pediaLines(sel, rs), 1024), false)
        }
        rows += ActionRow.of(Panel.selectPublic(Panel.id(gid, "ip_pold"), "📜 Policy details…", ordered.map { p ->
            val status = policyStatus(civ, p).substringBefore(' ')
            Choice("ip:${p.name}", "${p.name} (${if (p.branch == p) "branch" else p.branch.name})", policyDesc(p),
                status.takeIf { it.isNotBlank() } ?: "📜")
        }, sel?.let { "ip:${it.name}" }, true))
    }

    private val statEmojiShort = mapOf(Stat.Food to "🍞", Stat.Production to "⚙️", Stat.Gold to "💰", Stat.Science to "🔬",
        Stat.Culture to "🎭", Stat.Happiness to "😊", Stat.Faith to "🙏")

    fun statsText(stats: Stats): String = stats.mapNotNull { (stat, value) ->
        val v = (value * 10).roundToInt() / 10f
        if (v == 0f) null else (if (v > 0) "+" else "") + (if (v == v.toInt().toFloat()) v.toInt().toString() else v.toString()) + (statEmojiShort[stat] ?: stat.name)
    }.joinToString(" ")

    /** ImprovementPickerScreen.addImprovementRow, as text: turns, yield change on this tile (incl. maintenance),
     *  resource provided, "Replaces [X]", "Not in city work range". */
    fun improvementInfo(u: MapUnit, imp: TileImprovement, turns: Int? = null): List<String> {
        val civ = u.civ
        val tile = u.getTile()
        val gv = GameView(civ.gameInfo, civ)
        val tv = gv.getTile(tile)
        val parts = ArrayList<String>()
        val t = turns ?: if (tile.improvementInProgress == imp.name) tile.turnsToImprovement else imp.getTurnsToBuild(civ, u)
        if (t > 0) parts += "$t turns"
        val stats = runCatching { tv.getStatDiffForAlternative(setImprovement = imp).also { it.add(tv.getImprovementMaintenance(imp, gv.civView)) } }.getOrNull()
        val st = stats?.let { statsText(it) }.orEmpty()
        if (st.isNotEmpty()) parts += st
        val res = tile.tileResource?.takeIf { civ.canSeeResource(it) }
        if (res != null && res.isImprovedBy(imp.name)) parts += "provides ${res.name}"
        val replaces = !imp.isRoad() && !imp.name.startsWith(Constants.remove) && imp.name != Constants.cancelImprovementOrder
        if (tile.improvement != null && replaces) parts += "⚠️ Replaces ${tile.improvement}" +
            (if (res != null && res.isImprovedBy(tile.improvement!!)) " (loses ${res.name})" else "")
        if (tile.getOwner() != null && !imp.isRoad() && (stats?.max() ?: 0f) > 0f && !imp.name.startsWith(Constants.remove) &&
            !tv.getExploredTilesInDistance(civ.gameInfo.ruleset.modOptions.constants.cityWorkRange).any { it.isCityCenter() && it.getOwner()?.getCiv() == civ })
            parts += "not in city work range"
        return parts
    }

    /** The "Build …" option description (≤ 100 chars). */
    fun improvementDesc(u: MapUnit, imp: TileImprovement, turns: Int): String =
        runCatching { improvementInfo(u, imp, turns).joinToString(" · ") }.getOrDefault("$turns turns").let { if (it.length > 100) it.take(99) + "…" else it }

    /** ImprovementPickerScreen.getProblemReport: null = don't show; empty = buildable; else what to do first. */
    fun improvementProblems(u: MapUnit, imp: TileImprovement, mayRemoveLastTerrain: Boolean? = null): List<String>? {
        val civ = u.civ
        val rs = civ.gameInfo.ruleset
        val gv = GameView(civ.gameInfo, civ)
        val tv = gv.getTile(u.getTile())
        val uv = gv.getMapUnitView(u)
        val lastTerrain = u.getTile().allTerrains.last().name
        val canRemove = mayRemoveLastTerrain ?: (Constants.remove + lastTerrain in rs.tileImprovements)
        fun reportable(p: Collection<com.unciv.logic.map.tile.ImprovementBuildingProblem>) = p.all { it.reportable }
        var problems = tv.getImprovementBuildingProblems(imp, uv)
        var suggestRemoval = false
        if (!reportable(problems) && canRemove) {
            problems = tv.getImprovementBuildingProblems(imp, uv, pretendLastTerrainRemoved = true)
            if (!reportable(problems)) return null
            suggestRemoval = true
        }
        if (!reportable(problems)) return null
        val out = LinkedHashSet<String>()
        if (suggestRemoval) rs.tileImprovements[Constants.remove + lastTerrain]?.let { rem ->
            improvementProblems(u, rem, false)?.let { out.addAll(it) }
            out += "Remove $lastTerrain first"
        }
        val maxEras = rs.modOptions.constants.maxImprovementTechErasForward.takeUnless { it < 0 } ?: Int.MAX_VALUE
        if (com.unciv.logic.map.tile.ImprovementBuildingProblem.MissingTech in problems) {
            val maxEra = if (maxEras == Int.MAX_VALUE) Int.MAX_VALUE else civ.getEra().eraNumber
            for (tech in imp.requiredTechnologies(rs)) {
                val era = tech?.era(rs) ?: continue
                if (civ.tech.isResearched(tech.name)) continue
                if (era.eraNumber > maxEra) return null
                out += "Research ${tech.name} first"
            }
        }
        if (com.unciv.logic.map.tile.ImprovementBuildingProblem.NotJustOutsideBorders in problems) out += "Have this tile close to your borders"
        if (com.unciv.logic.map.tile.ImprovementBuildingProblem.OutsideBorders in problems) out += "Have this tile inside your empire"
        if (com.unciv.logic.map.tile.ImprovementBuildingProblem.MissingResources in problems) {
            fun need(name: String, amount: Int) { if (civ.getResourceAmount(name) < amount) out += "Acquire more $name" }
            for (un in imp.getMatchingUniques(UniqueType.ConsumesResources)) need(un.params[1], un.params[0].toInt())
            for ((name, amount) in imp.getStockpiledResourceRequirements(u.cache.state)) need(name, amount)
        }
        return out.toList()
    }

    /** Improvements this worker can't build here yet, with what to do first (shown after the "Build …" options). */
    fun blockedImprovementChoices(u: MapUnit): List<Choice> {
        val tile = u.getTile()
        val out = ArrayList<Choice>()
        for (imp in u.civ.gameInfo.ruleset.tileImprovements.values) {
            if (imp.turnsToBuild == -1 || imp.name == tile.improvement || imp.name == tile.improvementInProgress) continue
            if (!u.canBuildImprovement(imp)) continue
            val why = runCatching { improvementProblems(u, imp) }.getOrNull() ?: continue
            // empty = buildable (listed as "Build …"); only "remove the forest first" = CityPlus' "…, then build" option
            if (why.isEmpty() || why.size == 1 && why[0].startsWith("Remove ")) continue
            out += Choice("do:ip_impwhy:${imp.name}".take(100), "🚫 ${imp.name}", why.joinToString(" · ").take(100), "🔒")
        }
        return out
    }

    /** Picking a blocked improvement just explains it (Unciv shows it greyed out with the reasons). */
    fun improvementWhy(u: MapUnit, name: String): String {
        val imp = u.civ.gameInfo.ruleset.tileImprovements[name] ?: throw PlayerError("Unknown improvement.")
        val why = improvementProblems(u, imp)
        val info = runCatching { improvementInfo(u, imp) }.getOrDefault(emptyList())
        val head = when {
            why == null -> "🚫 **${imp.name}** can't be built here."
            why.isEmpty() -> "🔨 **${imp.name}** can be built here — pick **Build ${imp.name}**."
            else -> "🔒 **${imp.name}**: " + why.joinToString(" · ")
        }
        return head + (if (info.isNotEmpty()) "\n  ↳ " + info.joinToString(" · ") else "") +
            (runCatching { imp.getDescription(u.civ.gameInfo.ruleset) }.getOrDefault("").lines().map { tx(it) }.filter { it.isNotBlank() }
                .joinToString(" · ").take(400).let { if (it.isEmpty()) "" else "\n  ↳ $it" })
    }

    // ================================================================== self-test

    override fun selfTest(t: SelfTestCtx) {
        val a = t.alice
        fun check(ok: Boolean, what: String) { if (!ok) throw IllegalStateException("InfoPlus: $what") }

        // ---- 🏆 Victory: fake some progress so the milestone details have something to show
        t.eng { g ->
            val civ = GameOps.civOf(g, a)
            civ.victoryManager.currentsSpaceshipParts.add("SS Booster", 1)
            val other = g.civilizations.first { it.isMajorCiv && it != civ && it.isAlive() }
            g.diplomaticVictoryVotesCast[civ.civID] = other.civID
            g.diplomaticVictoryVotesCast[other.civID] = other.civID
        }
        check(t.act(a, "tab", emptyList(), VICTORY, "🏆 victory tab"), "victory tab")
        val vf = t.eng { g -> victoryFields(GameOps.civOf(g, a)) }
        vf.forEach { (n, v) -> println("  [victory] $n: ${v.replace("\n", " | ").take(400)}") }
        check(vf.size >= 3 && vf.any { it.first.contains("Scientific") }, "victory fields missing")
        check(vf.any { it.second.contains("Last vote") }, "diplomatic vote result missing")
        check(vf.all { it.second.length <= 1024 }, "victory field too long")
        t.render(a, "ip-victory")
        t.eng { g -> g.diplomaticVictoryVotesCast.clear(); GameOps.civOf(g, a).victoryManager.currentsSpaceshipParts.clear() }

        // ---- 📖 Civilopedia: every category, every entry's text and art must build
        check(t.act(a, "tab", emptyList(), PEDIA, "📖 civilopedia tab"), "pedia tab")
        val stats = t.eng { g ->
            cats.map { c ->
                val list = entries(c, g.ruleset, g)
                val empty = list.count { e -> pediaLines(e, g.ruleset).firstOrNull()?.startsWith("_No Civilopedia") == true }
                list.forEach { e -> pixelArt(c.key, e, g.ruleset) }
                "${c.key}=${list.size}" + (if (empty > 0) "(${empty} empty)" else "")
            }
        }
        println("  [pedia] " + stats.joinToString(" "))
        for ((ci, nm) in listOf(0 to "Warrior", 2 to "The Great Library", 3 to "Writing", 6 to "Iron", 11 to "Rome", 5 to "God of the Sea"))
            println("  [pedia] $nm: " + t.eng { g -> entries(cats[ci], g.ruleset, g).firstOrNull { it.name == nm }?.let { pediaLines(it, g.ruleset).joinToString(" | ") } }?.take(500))
        for (c in cats) {
            check(t.act(a, "ip_cat", emptyList(), "ip:${c.key}", "civilopedia category"), "category ${c.key}")
            t.render(a, null)
        }
        for ((key, name, tag) in listOf(Triple("units", "Warrior", "ip-pedia-unit"), Triple("wonders", "The Great Library", "ip-pedia-wonder"),
            Triple("resources", "Iron", "ip-pedia-resource"), Triple("nations", "Rome", "ip-pedia-nation"), Triple("techs", "Writing", "ip-pedia-tech"),
            Triple("terrains", "Forest", "ip-pedia-terrain"), Triple("improvements", "Farm", "ip-pedia-improvement"), Triple("eras", "Ancient era", "ip-pedia-era"))) {
            t.act(a, "ip_cat", emptyList(), "ip:$key", null)
            check(t.act(a, "ip_item", emptyList(), "ip:$name", "civilopedia entry"), "entry $name")
            t.render(a, tag)
            check(pedia(t.ctl.state(t.s.meta.id, a.userId)).item == name, "entry $name not in list")
        }
        t.act(a, "ip_cat", emptyList(), "ip:units", null)
        check(t.act(a, "ip_pg", listOf("1"), null, "civilopedia page ▶"), "page")
        t.render(a, "ip-pedia-page2")
        check(t.act(a, "ip_nx", listOf("1"), null, "civilopedia next entry"), "next")
        println("  [search] " + t.eng { g -> search(g.ruleset, g, "great libary").take(3).map { "${it.entry.name}:${it.score}" } })
        val st = t.ctl.state(t.s.meta.id, a.userId)
        val okSearch = t.act(a, "ip_search", emptyList(), "great libary", "civilopedia search (fuzzy)")
        if (!okSearch) println("  [search] flash: ${st.flash}")
        check(okSearch, "search")
        check(pedia(st).item == "The Great Library", "search found ${pedia(st).item}")
        t.act(a, "ip_search", emptyList(), "archer", null)
        check(pedia(st).item == "Archer", "search archer -> ${pedia(st).item}")
        t.render(a, "ip-pedia-search")
        check(!t.act(a, "ip_search", emptyList(), "zzqqxxjj", null), "nonsense search should fail")
        // /civ wiki — the same lookup the slash command runs (with and without a game)
        val w = t.eng { g -> wiki(g.ruleset, g, "iron working") } ?: throw IllegalStateException("wiki lookup failed")
        check(w.title.contains("Iron Working"), "wiki -> ${w.title}")
        val w2 = Engine.run { wiki(baseRuleset, null, "pyramids") } ?: throw IllegalStateException("wiki (no game) failed")
        check(w2.title.contains("Pyramids") && w2.body.length > 50, "wiki no game -> ${w2.title}")
        check(commands().single().options.single().name == "query", "wiki command")
        println("  [wiki] ${w2.title}: ${w2.body.replace("\n", " | ").take(300)}")
        runCatching { File(System.getProperty("civbot.home") ?: "../../..", "selftest-out/ip-wiki.png").writeBytes(w2.art!!) }
        check(t.act(a, "ip_search", emptyList(), "cavalry", "/civ wiki lookup"), "wiki via search")

        // ---- ✨ Pantheon expansion (free pantheon belief, as granted by e.g. a mod unique)
        t.eng { g ->
            val civ = GameOps.civOf(g, a)
            if (civ.religionManager.religionState == ReligionState.None) {
                civ.religionManager.storedFaith += 1000
                Features.foundPantheon(civ, pantheonChoices(civ).first().label)
            }
            civ.religionManager.freeBeliefs.add(BeliefType.Pantheon.name, 1)
        }
        check(t.eng { g -> pantheonExpandable(GameOps.civOf(g, a)) }, "pantheon should be expandable")
        check(t.act(a, "tab", emptyList(), PANTHEON, "✨ beliefs tab"), "beliefs tab")
        t.render(a, "ip-pantheon")
        val before = t.eng { g -> GameOps.civOf(g, a).religionManager.religion!!.getBeliefs(BeliefType.Pantheon).count() }
        val pick = t.eng { g -> pantheonChoices(GameOps.civOf(g, a)).first().id }
        check(t.act(a, "ip_pan", emptyList(), pick, "pantheon expansion"), "expand pantheon")
        val after = t.eng { g -> GameOps.civOf(g, a).religionManager.religion!!.getBeliefs(BeliefType.Pantheon).count() }
        check(after == before + 1, "pantheon beliefs $before -> $after")
        check(t.eng { g -> !pantheonExpandable(GameOps.civOf(g, a)) }, "pantheon still expandable")

        // ---- ✨ Free beliefs for a founded religion (cheat a religion into existence, as a Great Prophet would)
        val founded = t.eng { g ->
            val civ = GameOps.civOf(g, a)
            val rm = civ.religionManager
            // (what using a Great Prophet does; the setter is private, so cheat via reflection)
            fun setField(f: String, value: Any?) = rm.javaClass.getDeclaredField(f).apply { isAccessible = true }.set(rm, value)
            setField("religionState", ReligionState.FoundingReligion)
            setField("foundingCityId", civ.cities.first().id)
            val name = Features.religionNameChoices(civ).first().label
            if (!Engine.callHidden(rm, "foundReligion", name, name)) { setField("religionState", ReligionState.Pantheon); return@eng false }
            val chosen = ArrayList<Belief>()
            for (type in Features.pendingBeliefTypes(civ)!!) chosen += g.ruleset.beliefs.values.first {
                (it.type == type || (type == BeliefType.Any && it.type != BeliefType.Pantheon)) && it !in chosen && rm.getReligionWithBelief(it) == null
            }
            rm.chooseBeliefs(chosen)
            rm.freeBeliefs.add(BeliefType.Follower.name, 1)
            reformable(civ)
        }
        if (founded) {
            check(t.act(a, "tab", emptyList(), PANTHEON, null), "beliefs tab (reform)")
            t.render(a, "ip-freebelief")
            val choice = t.eng { g -> freeBeliefChoices(GameOps.civOf(g, a), BeliefType.Follower, emptyList()).first().id }
            check(t.act(a, "ip_fb", listOf("0"), choice, null), "pick free belief")
            check(t.act(a, "ip_fbok", emptyList(), null, "free belief (reform religion)"), "add free belief")
            check(t.eng { g -> !GameOps.civOf(g, a).religionManager.hasFreeBeliefs() }, "free beliefs not consumed")
        } else println("  [beliefs] couldn't cheat a religion — free-belief path not exercised")

        // ---- 📖 new Civilopedia categories (Unciv's Personalities / Unit Names)
        val newCats = t.eng { g -> listOf("personalities", "unitnames").associateWith { k -> entries(cats.first { it.key == k }, g.ruleset, g).size } }
        println("  [pedia] new categories: $newCats")
        check(newCats.values.all { it > 0 }, "personalities/unit names empty: $newCats")
        t.act(a, "tab", emptyList(), PEDIA, null)
        t.act(a, "ip_cat", emptyList(), "ip:unitnames", null)
        check(t.act(a, "ip_item", emptyList(), "ip:" + t.eng { g -> entries(cats.first { it.key == "unitnames" }, g.ruleset, g).first().name },
            "civilopedia personalities + unit names"), "unit names entry")
        t.render(a, "ip-pedia-unitnames")
        t.act(a, "ip_cat", emptyList(), "ip:personalities", null); t.render(a, "ip-pedia-personality")

        newFeatureTests(t)
        InfoStats.selfTest(t)
        t.act(a, "tab", emptyList(), "UNITS", null)
    }

    /** Rankings by every stat, Civilopedia cross-links + extra entries, policy / improvement picker texts. */
    private fun newFeatureTests(t: SelfTestCtx) {
        val a = t.alice
        fun check(ok: Boolean, what: String) { if (!ok) throw IllegalStateException("InfoPlus: $what") }
        val st = t.ctl.state(t.s.meta.id, a.userId)
        fun panel() = t.eng { Panel.render(t.s, a, st) }
        fun limitsOk(tag: String) {
            val r = panel()
            check(r.embed.length <= 6000 && r.embed.fields.size <= 25 && r.embed.fields.all { (it.value?.length ?: 0) <= 1024 }, "$tag embed limits")
            check(r.rows.size <= 5 && r.rows.all { row -> row.components.size <= 5 }, "$tag rows")
        }

        // ---- 🏅 rankings by every stat (VictoryScreenCivRankings), with the privacy rules
        val saved = t.eng { g -> val p = g.gameParameters; Triple(p.showRankings, p.hideOtherCivilizationStats, p.showCivilizationStats)
            .also { p.showCivilizationStats = true; p.showRankings = true; p.hideOtherCivilizationStats = false } }
        try {
            check(t.act(a, "tab", emptyList(), VICTORY, null), "victory tab")
            check(t.act(a, "ip_vrank", emptyList(), "ip:Population", "rankings by every stat (victory tab dropdown)"), "pick ranking")
            check(st.ext["ip.vrank"] == "Population", "ranking not stored")
            limitsOk("victory ranking"); t.render(a, "ip-victory-rank-population")
            val problems = t.eng { g ->
                val civ = GameOps.civOf(g, a)
                val out = ArrayList<String>()
                val majors = g.civilizations.count { it.isMajorCiv }
                for (rt in InfoStats.rankingTypes(civ)) {
                    val (name, body) = rankingField(civ, rt)
                    if (body.length > 1024) out += "${rt.name} too long"
                    if (body.lines().count { Regex("^\\d+\\.").containsMatchIn(it) } != minOf(12, majors)) out += "${rt.name}: not every civ listed: $body"
                    if (rt == RankingType.Gold) println("  [rank] $name: ${body.replace("\n", " | ").take(300)}")
                }
                val stranger = g.civilizations.firstOrNull { it.isMajorCiv && it != civ && !civ.knows(it) && !it.isDefeated() }
                if (stranger != null && !rankingField(civ, RankingType.Production).second.contains("an unknown civ")) out += "unknown civ named in rankings"
                if (stranger != null && rankingField(civ, RankingType.Production).second.contains(stranger.civName)) out += "stranger's name leaked"
                g.gameParameters.hideOtherCivilizationStats = true
                if (rankingField(civ, RankingType.Gold).second.lines().count { Regex("^\\d+\\.").containsMatchIn(it) } != 1) out += "hidden stats still ranked"
                g.gameParameters.hideOtherCivilizationStats = false; g.gameParameters.showRankings = false
                if (!rankingField(civ, RankingType.Gold).second.contains("hidden")) out += "rankings shown with showRankings off"
                if (Panel.render(t.s, a, st).rows.any { r -> r.components.any { (it as? net.dv8tion.jda.api.interactions.components.selections.StringSelectMenu)?.id?.contains("ip_vrank") == true } })
                    out += "ranking dropdown shown with showRankings off"
                g.gameParameters.showRankings = true
                out
            }
            check(problems.isEmpty(), problems.joinToString("; "))
            check(!t.act(a, "ip_vrank", emptyList(), "ip:Nonsense", null), "bad ranking accepted")
            t.act(a, "ip_vrank", emptyList(), "ip:Score", null)
        } finally { t.eng { g -> val p = g.gameParameters; p.showRankings = saved.first; p.hideOtherCivilizationStats = saved.second; p.showCivilizationStats = saved.third } }

        // ---- 📖 Civilopedia: Unciv's extra entries (BaseBelief overviews, Global Uniques, movement domains)
        val extra = t.eng { g ->
            fun names(k: String) = entries(cats.first { it.key == k }, g.ruleset, g).map { it.name }
            Triple(names("beliefs").filter { it == "Beliefs" || it == "Religions" }, names("tutorials").filter { it == "Global Uniques" }, names("unittypes").filter { it.startsWith("Domain:") })
        }
        println("  [pedia] extra entries: $extra")
        check(extra.first.size == 2 && extra.second.size == 1 && extra.third.isNotEmpty(), "missing Unciv pedia entries: $extra")
        t.act(a, "tab", emptyList(), PEDIA, null)
        for ((k, n, tag) in listOf(Triple("beliefs", "Religions", "ip-pedia-religions"), Triple("tutorials", "Global Uniques", "ip-pedia-globaluniques"), Triple("unittypes", extra.third.first(), null))) {
            t.act(a, "ip_cat", emptyList(), "ip:$k", null)
            check(t.act(a, "ip_item", emptyList(), "ip:$n", if (tag == null) null else "civilopedia Beliefs/Religions/Global Uniques/Domain entries"), "entry $n")
            val body = t.eng { g -> pediaLines(entries(cats.first { it.key == k }, g.ruleset, g).first { it.name == n }, g.ruleset) }
            println("  [pedia] $n: ${body.joinToString(" | ").take(240)}")
            check(body.isNotEmpty() && !body.first().startsWith("_No Civilopedia"), "$n has no text")
            if (tag != null) t.render(a, tag)
        }

        // ---- 🔗 cross-links: every entry's links resolve; Warrior offers Related… and it jumps
        val linkStats = t.eng { g -> cats.associate { c -> c.key to entries(c, g.ruleset, g).sumOf { relatedEntries(it, g.ruleset, g).size } } }
        println("  [pedia] links per category: $linkStats")
        check(linkStats.values.sum() > 100, "too few cross-links: $linkStats")
        t.act(a, "ip_cat", emptyList(), "ip:units", null)
        t.act(a, "ip_item", emptyList(), "ip:Warrior", null)
        val rel = t.eng { g -> relatedEntries(g.ruleset.units["Warrior"]!!, g.ruleset, g) }
        println("  [pedia] Warrior related: ${rel.map { "${it.first.key}/${it.second.name}" }}")
        check(rel.isNotEmpty(), "Warrior has no related entries")
        check(panel().rows.any { r -> r.components.any { (it as? net.dv8tion.jda.api.interactions.components.selections.StringSelectMenu)?.id?.contains("ip_rel") == true } }, "no Related… dropdown")
        limitsOk("pedia related"); t.render(a, "ip-pedia-related")
        val target = rel.first()
        check(t.act(a, "ip_rel", emptyList(), "ip:${target.first.key}|${target.second.name}", "civilopedia cross-links (Related… dropdown)"), "follow link")
        check(pedia(st).item == target.second.name && pedia(st).cat == target.first.key, "link went to ${pedia(st).cat}/${pedia(st).item}")
        check(!t.act(a, "ip_rel", emptyList(), "ip:units|Nonexistent Unit", null), "bad link accepted")
        // a wonder linked as "Building/…" still resolves
        check(t.eng { g -> resolveLink(g.ruleset, g, "Building/The Great Library")?.first?.key } == "wonders", "Building/ link to a wonder")
        // the entry dropdown pages itself: Next keeps the right page
        repeat(30) { t.act(a, "ip_nx", listOf("1"), null, null) }
        limitsOk("pedia page"); t.render(a, "ip-pedia-paged")

        // ---- 📜 policy picker: effects in the option descriptions, full text via "Policy details…"
        val pol = t.eng { g ->
            val civ = GameOps.civOf(g, a)
            val ps = g.ruleset.policies.values.filter { it.policyBranchType != com.unciv.models.ruleset.Policy.PolicyBranchType.BranchComplete }
            val bad = ps.filter { policyDesc(it).length > 100 || policyDesc(it).isBlank() }.map { it.name }
            val sample = ps.first { it.branch != it && policyEffect(it).isNotEmpty() }
            println("  [policy] ${sample.name}: ${policyDesc(sample)} · Tradition: ${g.ruleset.policies["Tradition"]?.let { policyDesc(it) }}")
            println("  [policy] picker now: ${GameOps.policyChoices(civ).take(3).map { "${it.label}: ${it.description}" }}")
            Triple(bad, sample.name, GameOps.policyChoices(civ).all { c -> c.description.length <= 100 && g.ruleset.policies[c.id.removePrefix("pol:")]?.let { p -> p.branch == p || c.description != p.branch.name } != false })
        }
        check(pol.first.isEmpty(), "bad policy descriptions: ${pol.first}")
        check(pol.third, "policy picker still shows only the branch")
        check(t.act(a, "tab", emptyList(), "POLICIES", null), "policies tab")
        check(t.act(a, "ip_pold", emptyList(), "ip:${pol.second}", "policy details (effects in picker + Policy details… dropdown)"), "policy details")
        val pf = panel().embed.fields.firstOrNull { it.name?.contains(pol.second) == true }
        println("  [policy] details field: ${pf?.value?.replace("\n", " | ")?.take(300)}")
        check(pf != null && (pf.value?.length ?: 0) > 20, "no policy details field")
        limitsOk("policies"); t.render(a, "ip-policy-details")
        check(!t.act(a, "ip_pold", emptyList(), "ip:Nonsense", null), "bad policy accepted")

        // ---- 🔨 improvement picker: yield change, Replaces [X], blocked improvements with their reason
        val wid = t.eng { g ->
            val civ = GameOps.civOf(g, a)
            if ("Agriculture" in g.ruleset.technologies) civ.tech.addTechnology("Agriculture")
            val city = civ.cities.first()
            val tile = city.getTiles().firstOrNull { it.isLand && !it.isCityCenter() && it.civilianUnit == null && it.militaryUnit == null && !it.isImpassible() && it.improvementInProgress == null && it.resource == null && it.terrainFeatures.isEmpty() }
                ?: city.getTiles().first { it.isLand && !it.isCityCenter() && it.civilianUnit == null && it.militaryUnit == null && !it.isImpassible() }
            tile.setBaseTerrain(g.ruleset.terrains["Grassland"]!!)
            tile.setTileResource(null as String?); tile.removeImprovement(); tile.stopWorkingOnImprovement()
            for (f in tile.terrainFeatures.toList()) tile.removeTerrainFeature(f)
            tile.setTerrainTransients()
            val w = civ.units.placeUnitNearTile(tile.position, "Worker")!!
            check(w.getTile() == tile, "worker not on the test tile")
            w.currentMovement = w.getMaxMovement().toFloat()
            w.id
        }
        t.act(a, "tab", emptyList(), "UNITS", null)
        t.act(a, "selunit", emptyList(), "u:$wid", null)
        fun worker(g: GameInfo) = GameOps.unitById(GameOps.civOf(g, a), wid)!!
        val ch = t.eng { g -> GameOps.unitChoices(worker(g)) }
        val farm = ch.firstOrNull { it.id == "imp:Farm" }
        val blocked = ch.filter { it.id.startsWith("do:ip_impwhy:") }
        println("  [improve] Farm: ${farm?.description} · blocked: ${blocked.map { "${it.label}: ${it.description}" }}")
        check(farm != null && farm.description.contains("🍞") && farm.description.contains("turns"), "Farm shows no yield change: ${farm?.description}")
        check(blocked.isNotEmpty() && blocked.all { it.description.isNotBlank() }, "no blocked improvements with reasons")
        check(blocked.any { it.description.contains("Research") }, "no 'Research X first' reason")
        limitsOk("units improvements"); t.render(a, "ip-improvement-picker")
        check(t.act(a, "uact", emptyList(), blocked.first().id, "improvement picker: blocked improvements with reasons"), "pick blocked")
        println("  [improve] why: ${st.flash.lastOrNull()?.replace("\n", " | ")}")
        check(st.flash.lastOrNull()?.contains("🔒") == true, "no reason shown")
        // an existing improvement: the others warn "Replaces [X]"
        val replaced = t.eng { g ->
            val tile = worker(g).getTile()
            val other = g.ruleset.tileImprovements.values.first { it.name != "Farm" && !it.isRoad() && it.turnsToBuild > 0 && !it.name.startsWith(Constants.remove) && it.uniqueTo == null }
            tile.setImprovement(other.name)
            val d = GameOps.unitChoices(worker(g)).firstOrNull { it.id == "imp:Farm" }?.description
            tile.removeImprovement()
            other.name to d
        }
        println("  [improve] with ${replaced.first}: Farm -> ${replaced.second}")
        check(replaced.second?.contains("Replaces ${replaced.first}") == true, "no 'Replaces' warning")
        check(t.act(a, "uact", emptyList(), "imp:Farm", "improvement picker: yield change + Replaces warning"), "build farm")
        t.eng { g -> worker(g).getTile().stopWorkingOnImprovement(); worker(g).destroy() }
    }
}

package civbot

import com.unciv.logic.automation.civilization.DiplomacyAutomation
import com.unciv.logic.city.City
import com.unciv.logic.city.CityFocus
import com.unciv.logic.civilization.AlertType
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.PopupAlert
import com.unciv.logic.civilization.diplomacy.DiplomacyFlags
import com.unciv.logic.civilization.managers.ReligionState
import com.unciv.logic.map.HexCoord
import com.unciv.logic.map.HexMath
import com.unciv.logic.map.tile.RoadStatus
import com.unciv.logic.map.tile.Tile
import com.unciv.logic.trade.TradeEvaluation
import com.unciv.logic.trade.TradeLogic
import com.unciv.logic.trade.TradeOffer
import com.unciv.logic.trade.TradeOfferType
import com.unciv.models.ruleset.Belief
import com.unciv.models.ruleset.BeliefType
import com.unciv.models.stats.Stat
import com.unciv.view.GameView

/**
 * Everything beyond the basics: map look-around, trading, city-states, city management,
 * religion, great people, the empire overview and captured-city decisions.
 * All functions run on the engine thread.
 */
object Features {

    // ================================================================== map look-around

    fun pan(game: com.unciv.logic.GameInfo, center: HexCoord, clock: Int, steps: Int = 3): HexCoord {
        val d = HexMath.getClockPositionToHexcoord(clock)
        for (s in steps downTo 1) {
            val p = HexCoord.of(center.x + d.x * s, center.y + d.y * s)
            if (game.tileMap.getIfTileExistsOrNull(p.x, p.y) != null) return p
        }
        return center
    }

    fun inspect(tile: Tile, civ: Civilization, code: String): Pair<String, String> {
        if (!tile.isExplored(civ)) return "🔍 $code" to "Unexplored."
        val lines = ArrayList<String>()
        lines += GameOps.tileDescription(tile, civ)
        val y = tile.stats.getTileStats(null, civ)
        val yields = listOf(Stat.Food to "🍞", Stat.Production to "⚙️", Stat.Gold to "💰", Stat.Science to "🔬", Stat.Culture to "🎭", Stat.Faith to "🙏")
            .filter { y[it.first] > 0 }.joinToString(" ") { "${it.second}${y[it.first].toInt()}" }
        if (yields.isNotEmpty()) lines += "Yields: $yields"
        tile.resource?.let { r ->
            val res = tile.ruleset.tileResources[r]
            if (res != null && civ.tech.isRevealed(res)) lines += "Resource: $r" + (if (tile.resourceAmount > 0) " ×${tile.resourceAmount}" else "") + " (${res.resourceType.name})"
        }
        tile.improvementInProgress?.let { lines += "Being built: $it (${tile.turnsToImprovement} turns)" }
        if (tile.roadStatus != RoadStatus.None) lines += "Road: ${tile.roadStatus.name}"
        if (tile.hasBottomRiver || tile.hasBottomLeftRiver || tile.hasBottomRightRiver) lines += "River"
        tile.getOwner()?.let { o -> lines += "Owner: ${o.civName}" + (tile.getCity()?.let { " (${it.name})" } ?: "") }
        if (tile.isVisible(civ)) {
            for (u in tile.getUnits()) lines += "${GameOps.unitEmoji(u)} ${u.civ.civName} ${u.label()} — ❤️${u.health}"
        } else lines += "_Not currently visible — units unknown._"
        lines += "Movement cost: ${tile.lastTerrain.movementCost}" + (if (tile.isRoughTerrain()) " · rough terrain (+defence)" else "")
        return "🔍 $code" to lines.joinToString("\n")
    }

    // ================================================================== trade

    private fun encode(o: TradeOffer, amount: Int) = "t~${o.type.name}~$amount~${o.name}".take(100)

    private fun offerChoices(game: com.unciv.logic.GameInfo, offers: List<TradeOffer>, side: String): List<Choice> {
        val out = ArrayList<Choice>()
        for (o in offers) {
            if (!o.isTradable()) continue // e.g. no gold, negative gold per turn, peace proposals not allowed yet
            when (o.type) {
                TradeOfferType.City -> {
                    val city = game.getCities().firstOrNull { it.id == o.name } ?: continue
                    out += Choice("$side${encode(o, o.amount)}", "City: ${city.name}", "pop ${city.population.population} · ${city.civ.civName}", "🏙️")
                }
                TradeOfferType.WarDeclaration -> {
                    val target = game.civilizations.firstOrNull { it.civID == o.name } ?: continue
                    out += Choice("$side${encode(o, o.amount)}", "Declare war on ${target.civName}", "joins the war", "⚔️")
                }
                TradeOfferType.Gold -> {
                    listOf(50, 100, 250, 500).filter { it < o.amount }.plus(o.amount).distinct().forEach {
                        out += Choice("$side${encode(o, it)}", "$it gold", "one-off payment", "💰")
                    }
                    out += DiplomacyPlus.customAmountChoice(side, o)
                }
                TradeOfferType.Gold_Per_Turn -> {
                    listOf(2, 5, 10).filter { it < o.amount }.plus(o.amount).distinct().forEach {
                        out += Choice("$side${encode(o, it)}", "$it gold per turn", "for ${o.duration} turns", "💰")
                    }
                    out += DiplomacyPlus.customAmountChoice(side, o)
                }
                TradeOfferType.Introduction, TradeOfferType.PeaceProposal -> {
                    val third = game.civilizations.firstOrNull { it.civID == o.name } ?: continue
                    out += if (o.type == TradeOfferType.Introduction) Choice("$side${encode(o, o.amount)}", "Introduction to ${third.civName}", "they meet each other", "👋")
                    else Choice("$side${encode(o, o.amount)}", "Make peace with ${third.civName}", "ends that war", "🕊️")
                }
                TradeOfferType.Luxury_Resource -> out += Choice("$side${encode(o, 1)}", "${o.name} ×1", "luxury resource (you have ${o.amount})", "💎")
                TradeOfferType.Strategic_Resource, TradeOfferType.Stockpiled_Resource -> listOf(1, o.amount).distinct().forEach {
                    out += Choice("$side${encode(o, it)}", "${o.name} ×$it", "strategic resource", "⛏️")
                }
                else -> out += Choice("$side${encode(o, o.amount)}", GameOps.clean(o.name), o.type.name.replace('_', ' ').lowercase(),
                    when (o.type) {
                        TradeOfferType.Luxury_Resource -> "💎"; TradeOfferType.Technology -> "🔬"; TradeOfferType.Treaty -> "📜"
                        TradeOfferType.Embassy -> "🏛️"; TradeOfferType.Agreement -> "🤝"; else -> "•"
                    })
            }
        }
        return out
    }

    /** (what we can give, what we can ask for) */
    fun tradeChoices(civ: Civilization, other: Civilization): Pair<List<Choice>, List<Choice>> {
        if (civ.getDiplomacyManager(other) == null || other.isCityState) return emptyList<Choice>() to emptyList()
        val tl = TradeLogic(civ, other)
        val ok = { o: TradeOffer -> DiplomacyPlus.offerAllowed(civ, other, o) }
        return offerChoices(civ.gameInfo, tl.ourAvailableOffers.filter(ok), "g") to offerChoices(civ.gameInfo, tl.theirAvailableOffers.filter(ok), "a")
    }

    fun buildTrade(civ: Civilization, other: Civilization, give: List<String>, ask: List<String>): TradeLogic {
        val tl = TradeLogic(civ, other)
        fun add(keys: List<String>, available: List<TradeOffer>, target: MutableList<TradeOffer>) {
            for (k in keys) {
                val parts = k.drop(1).split("~", limit = 4)
                if (parts.size < 4) continue
                val type = TradeOfferType.valueOf(parts[1]); val amount = parts[2].toInt(); val name = parts[3]
                val base = available.firstOrNull { it.type == type && it.name.take(name.length) == name } ?: continue
                if (target.any { it.type == type && it.name == base.name }) continue
                target += base.copy(amount = amount)
            }
        }
        add(give, tl.ourAvailableOffers.toList(), tl.currentTrade.ourOffers)
        add(ask, tl.theirAvailableOffers.toList(), tl.currentTrade.theirOffers)
        DiplomacyPlus.completeTreaties(tl) // treaties go on both sides + research-agreement gold top-up (like Unciv)
        return tl
    }

    fun describeTrade(tl: TradeLogic): String {
        fun side(l: List<TradeOffer>) = l.joinToString { o ->
            when (o.type) {
                TradeOfferType.Gold -> "${o.amount} gold"
                TradeOfferType.Gold_Per_Turn -> "${o.amount} gold/turn"
                TradeOfferType.Strategic_Resource, TradeOfferType.Stockpiled_Resource, TradeOfferType.Luxury_Resource -> "${o.name} ×${o.amount}"
                TradeOfferType.City -> "city of " + (tl.ourCivilization.gameInfo.getCities().firstOrNull { it.id == o.name }?.name ?: "?")
                TradeOfferType.WarDeclaration -> "war on " + (tl.ourCivilization.gameInfo.civilizations.firstOrNull { it.civID == o.name }?.civName ?: "?")
                TradeOfferType.Introduction -> "introduction to " + (tl.ourCivilization.gameInfo.civilizations.firstOrNull { it.civID == o.name }?.civName ?: "?")
                TradeOfferType.PeaceProposal -> "peace with " + (tl.ourCivilization.gameInfo.civilizations.firstOrNull { it.civID == o.name }?.civName ?: "?")
                TradeOfferType.Treaty -> GameOps.clean(o.name) + (if (o.duration > 0) " (${o.duration} turns)" else "")
                else -> GameOps.clean(o.name)
            }
        }.ifEmpty { "nothing" }
        return "${tl.ourCivilization.civName} gives **${side(tl.currentTrade.ourOffers)}** ↔ ${tl.otherCivilization.civName} gives **${side(tl.currentTrade.theirOffers)}**"
    }

    fun tradeIsValid(tl: TradeLogic) =
        (tl.currentTrade.ourOffers.isNotEmpty() || tl.currentTrade.theirOffers.isNotEmpty()) &&
            TradeEvaluation().isTradeValid(tl.currentTrade, tl.ourCivilization, tl.otherCivilization)

    fun proposeTradeToAI(tl: TradeLogic): String {
        if (!tradeIsValid(tl)) throw PlayerError("That trade isn't possible any more — pick again.")
        val other = tl.otherCivilization
        val ok = TradeEvaluation().isTradeAcceptable(tl.currentTrade.reverse(), other, tl.ourCivilization)
        if (!ok) return "🤝 ${other.civName} declined: ${describeTrade(tl)}. Try offering more."
        tl.acceptTrade()
        return "🤝 Deal! ${describeTrade(tl)}"
    }

    fun friendshipAvailable(civ: Civilization, other: Civilization) =
        !other.isCityState && civ.diplomacyFunctions.canSignDeclarationOfFriendshipWith(other)

    fun befriendAI(civ: Civilization, other: Civilization): String {
        val view = GameView(other.gameInfo, other).civView
        val wants = Engine.callHiddenResult(DiplomacyAutomation, "wantsToSignDeclarationOfFrienship", view, civ) as? Boolean ?: false
        if (!wants) return "💛 ${other.civName} isn't ready to declare friendship yet."
        civ.getDiplomacyManager(other)!!.signDeclarationOfFriendship()
        return "💛 ${civ.civName} and ${other.civName} declared friendship!"
    }

    fun signFriendship(a: Civilization, b: Civilization): String {
        a.getDiplomacyManager(b)!!.signDeclarationOfFriendship()
        return "💛 ${a.civName} and ${b.civName} declared friendship!"
    }

    fun canDenounce(civ: Civilization, other: Civilization): Boolean {
        val dm = civ.getDiplomacyManager(other) ?: return false
        return !other.isCityState && !civ.isAtWarWith(other) && !dm.hasFlag(DiplomacyFlags.Denunciation)
            && !dm.hasFlag(DiplomacyFlags.DeclarationOfFriendship) // Unciv hides Denounce while you're friends
    }

    fun denounce(civ: Civilization, other: Civilization): String {
        if (!canDenounce(civ, other)) throw PlayerError("You can't denounce them right now.")
        civ.getDiplomacyManager(other)!!.denounce()
        return "😠 ${civ.civName} denounced ${other.civName}!"
    }

    // ================================================================== city-states

    fun cityStates(civ: Civilization) = civ.getKnownCivs().filter { it.isCityState && it.isAlive() }.toList()

    fun cityStateInfo(civ: Civilization, cs: Civilization): String {
        val dm = cs.getDiplomacyManager(civ)
        val f = cs.cityStateFunctions
        val lines = ArrayList<String>()
        lines += "${cs.cityStateType.name} city-state · ${cs.cities.size} city"
        if (dm != null) lines += "Influence: **${dm.getInfluence().toInt()}** · ${dm.relationshipLevel()}" + (if (civ.isAtWarWith(cs)) " · ⚔️ at war" else "")
        lines += DiplomacyPlus.allyLine(civ, cs)
        val protectors = f.getProtectorCivs()
        if (protectors.isNotEmpty()) lines += "Protected by: ${protectors.joinToString { InfoStats.civName(civ, it) }}"
        return lines.joinToString("\n") // quests, bonuses and tribute: DiplomacyPlus.cityStateFields
    }

    fun cityStateChoices(civ: Civilization, cs: Civilization): List<Choice> {
        val out = ArrayList<Choice>()
        val f = cs.cityStateFunctions
        if (civ.isAtWarWith(cs)) return out
        for (g in listOf(250, 500, 1000)) if (civ.gold >= g)
            out += Choice("gift:$g", "Gift $g gold", "+${f.influenceGainedByGift(civ, g)} influence", "🎁")
        if (f.otherCivCanPledgeProtection(civ)) out += Choice("pledge", "Pledge to protect", "Others lose influence if they attack it", "🛡️")
        if (f.otherCivCanWithdrawProtection(civ)) out += Choice("revoke", "Withdraw protection", "", "🚫")
        if (f.getTributeWillingness(civ) >= 0) out += Choice("tribgold", "Demand ${f.goldGainedByTribute()} gold tribute", "-15 influence", "💰")
        if (f.getTributeWillingness(civ, demandingWorker = true) >= 0) out += Choice("tribworker", "Demand a worker as tribute", "-50 influence", "🔨")
        return out + DiplomacyPlus.cityStateGiftChoices(civ, cs)
    }

    fun doCityState(civ: Civilization, cs: Civilization, id: String): String {
        val f = cs.cityStateFunctions
        return when {
            id.startsWith("gift:") -> {
                val g = id.removePrefix("gift:").toInt()
                if (civ.gold < g) throw PlayerError("Not enough gold.")
                f.receiveGoldGift(civ, g); "🎁 Gave $g gold to ${cs.civName}."
            }
            id == "pledge" -> { f.addProtectorCiv(civ); "🛡️ You now protect ${cs.civName}." }
            id == "revoke" -> { f.removeProtectorCiv(civ); "🚫 You no longer protect ${cs.civName}." }
            id == "tribgold" -> { f.tributeGold(civ); "💰 ${cs.civName} paid tribute." }
            id == "tribworker" -> { f.tributeWorker(civ); "🔨 ${cs.civName} handed over a worker." }
            else -> throw PlayerError("Unknown action.")
        }
    }

    // ================================================================== city management

    private val focusEmoji = mapOf(
        CityFocus.NoFocus to "⚖️", CityFocus.Manual to "✋", CityFocus.FoodFocus to "🍞", CityFocus.ProductionFocus to "⚙️", CityFocus.GoldFocus to "💰",
        CityFocus.ScienceFocus to "🔬", CityFocus.CultureFocus to "🎭", CityFocus.FaithFocus to "🙏",
        CityFocus.GoldGrowthFocus to "📈", CityFocus.ProductionGrowthFocus to "🏭",
    )

    fun cityExtraChoices(city: City, window: MapWindow?): List<Choice> {
        val out = ArrayList<Choice>()
        // Unciv's CityScreen: no annexing and no razing for civs that "May not annex cities"
        val mayAnnex = GameOps.mayAnnex(city.civ)
        if (city.isPuppet && mayAnnex) out += Choice("annex", "Annex this city", "Take control of production", "🏙️")
        if (city.isBeingRazed) out += Choice("stopraze", "Stop razing", "", "🧯")
        else if (city.canBeDestroyed() && mayAnnex) out += Choice("raze", "Raze this city", "Burns down over several turns", "🔥")
        if (city.cityConstructions.constructionQueue.size > 1) out += Choice("clearq", "Clear the build queue", "Keeps the current item", "🧹")
        // Same focus list as Unciv's CitizenManagementTable (tableEnabled, Faith only with religion on)
        for ((focus, e) in focusEmoji) if (focus != city.getCityFocus() && focus.tableEnabled &&
            (focus != CityFocus.FaithFocus || city.civ.gameInfo.isReligionEnabled()))
            out += Choice("focus:${focus.name}", "Focus: ${GameOps.focusName(focus)}", if (focus == CityFocus.Manual) "Citizens stay on the tiles you pick" else "Citizens favour this", e)
        val buyable = city.tilesInRange.filter { city.expansion.canBuyTile(it) }
            .map { it to city.expansion.getGoldCostOfTile(it) }.sortedBy { it.second }
        for ((t, cost) in buyable) {
            val code = window?.code(t.position.x, t.position.y) ?: "${t.position.x},${t.position.y}"
            out += Choice("tile:${t.position.x},${t.position.y}", "Buy tile $code — $cost gold", GameOps.tileDescription(t, city.civ).take(100), "🗺️")
        }
        return out
    }

    fun doCityExtra(city: City, id: String): String = when {
        id == "annex" -> GameOps.annex(city)
        id == "raze" -> {
            GameOps.requireMayAnnex(city.civ)
            if (!city.canBeDestroyed()) throw PlayerError("${city.name} can't be razed (capitals and holy cities can't).")
            if (city.isPuppet) city.annexCity(); city.isBeingRazed = true; "🔥 ${city.name} is being razed."
        }
        id == "stopraze" -> { city.isBeingRazed = false; "🧯 ${city.name} is no longer being razed." }
        id == "clearq" -> {
            val cc = city.cityConstructions
            while (cc.constructionQueue.size > 1) cc.removeFromQueue(cc.constructionQueue.size - 1, false)
            "🧹 Queue cleared."
        }
        id.startsWith("focus:") -> {
            val f = CityFocus.valueOf(id.removePrefix("focus:"))
            city.setCityFocus(f); city.reassignPopulation()
            "${focusEmoji[f] ?: ""} ${city.name} now focuses on ${GameOps.focusName(f)}."
        }
        id.startsWith("tile:") -> {
            val (x, y) = id.removePrefix("tile:").split(",").map { it.toInt() }
            val t = city.civ.gameInfo.tileMap[x, y]
            if (!city.expansion.canBuyTile(t)) throw PlayerError("Can't buy that tile.")
            val cost = city.expansion.getGoldCostOfTile(t)
            if (city.civ.gold < cost) throw PlayerError("Not enough gold ($cost needed).")
            city.expansion.buyTile(t)
            "🗺️ Bought a tile for $cost gold."
        }
        else -> throw PlayerError("Unknown city action.")
    }

    /** Faith purchases: units and buildings (Cathedral, Pagoda…) whose uniques allow it. Ids "faith:Faith~<queueIndex>~<name>". */
    fun faithBuyChoices(city: City): List<Choice> {
        if (!city.civ.gameInfo.isReligionEnabled()) return emptyList()
        return GameOps.purchaseChoices(city, listOf(Stat.Faith), "faith:")
    }

    fun buyWithFaith(city: City, spec: String): String = GameOps.buy(city, spec, Stat.Faith)

    fun queueText(city: City): String {
        val q = city.cityConstructions.constructionQueue
        if (q.size <= 1) return ""
        return "\n📋 Queue: " + q.drop(1).joinToString(" → ")
    }

    // ================================================================== religion

    fun religionInfo(civ: Civilization): String {
        val rm = civ.religionManager
        val lines = ArrayList<String>()
        lines += "🙏 Faith: **${rm.storedFaith}** (+${civ.stats.statsForNextTurn.faith.toInt()}/turn)"
        val r = rm.religion
        lines += when (rm.religionState) {
            ReligionState.None -> "No pantheon yet." + (if (rm.canFoundOrExpandPantheon()) " **You can found a pantheon now!**" else " A pantheon costs ${rm.faithForPantheon()} faith.")
            ReligionState.Pantheon -> "Pantheon: **${r?.getAllBeliefsOrdered()?.joinToString { it.name }}**. A Great Prophet founds a full religion."
            ReligionState.FoundingReligion -> "**Your Great Prophet is ready — name your religion and pick its beliefs below.**"
            ReligionState.EnhancingReligion -> "**Pick the beliefs to enhance your religion below.**"
            else -> "Religion: **${r?.getReligionDisplayName()}**\nBeliefs: ${r?.getAllBeliefsOrdered()?.joinToString { it.name }}\nCities following it: ${rm.numberOfCitiesFollowingThisReligion()}"
        }
        if (rm.religionState < ReligionState.Religion && rm.canGenerateProphet(true))
            lines += "Next Great Prophet at ${rm.faithForNextGreatProphet()} faith."
        lines += "Religions founded: ${civ.gameInfo.religions.values.filter { !it.isPantheon() }.joinToString { it.getReligionDisplayName() }.ifEmpty { "none yet" }} · ${rm.remainingFoundableReligions()} slot(s) left"
        return lines.joinToString("\n")
    }

    private fun beliefTaken(civ: Civilization, b: Belief) = civ.gameInfo.religions.values.any { it.hasBelief(b.name) }

    fun beliefChoices(civ: Civilization, type: BeliefType): List<Choice> =
        civ.gameInfo.ruleset.beliefs.values
            .filter { (type == BeliefType.Any && it.type != BeliefType.Pantheon) || it.type == type }
            .filter { !beliefTaken(civ, it) && it.isAvailable(civ.state) }
            .map { Choice("b:${it.name}", it.name, GameOps.clean(it.uniques.joinToString("; ")).take(100), "✨") }

    fun pantheonAvailable(civ: Civilization) =
        civ.gameInfo.isReligionEnabled() && civ.religionManager.religionState == ReligionState.None && civ.religionManager.canFoundOrExpandPantheon()

    fun foundPantheon(civ: Civilization, belief: String): String {
        val b = civ.gameInfo.ruleset.beliefs[belief] ?: throw PlayerError("Unknown belief.")
        if (!pantheonAvailable(civ)) throw PlayerError("You can't found a pantheon right now.")
        civ.religionManager.chooseBeliefs(listOf(b), useFreeBeliefs = civ.religionManager.usingFreeBeliefs())
        return "🙏 Pantheon founded: **${b.name}**."
    }

    /** Belief types (in order) the player must pick right now, or null if not founding/enhancing. */
    fun pendingBeliefTypes(civ: Civilization): List<BeliefType>? {
        val rm = civ.religionManager
        val counter = when (rm.religionState) {
            ReligionState.FoundingReligion -> rm.getBeliefsToChooseAtFounding()
            ReligionState.EnhancingReligion -> rm.getBeliefsToChooseAtEnhancing()
            else -> return null
        }
        return counter.flatMap { (t, n) -> List(n) { t } }.sortedBy { it.ordinal }
    }

    fun religionNameChoices(civ: Civilization): List<Choice> {
        val used = civ.gameInfo.religions.keys
        val fav = civ.nation.favoredReligion
        return civ.gameInfo.ruleset.religions.filter { it !in used }
            .sortedByDescending { it == fav }
            .map { Choice("r:$it", it, if (it == fav) "Your people's favourite" else "", "⛪") }
    }

    fun completeReligion(civ: Civilization, name: String?, beliefs: List<String>): String {
        val rm = civ.religionManager
        val types = pendingBeliefTypes(civ) ?: throw PlayerError("Nothing to choose right now.")
        if (beliefs.size < types.size || beliefs.any { it.isEmpty() }) throw PlayerError("Pick all ${types.size} beliefs first.")
        val chosen = beliefs.map { civ.gameInfo.ruleset.beliefs[it] ?: throw PlayerError("Unknown belief $it.") }
        if (chosen.distinct().size != chosen.size) throw PlayerError("Pick different beliefs.")
        if (rm.religionState == ReligionState.FoundingReligion) {
            val n = name ?: throw PlayerError("Pick a name for your religion first.")
            if (!Engine.callHidden(rm, "foundReligion", n, n)) throw PlayerError("Couldn't found the religion — try 🤖 Auto-pick.")
        }
        rm.chooseBeliefs(chosen)
        return "⛪ ${rm.religion?.getReligionDisplayName()}: ${chosen.joinToString { it.name }}."
    }

    // ================================================================== great people

    fun greatPersonChoices(civ: Civilization): List<Choice> =
        if (civ.greatPeople.freeGreatPeople <= 0) emptyList()
        else civ.greatPeople.getFreeGreatPersonOptions().map { Choice("gp:${it.name}", it.name, "Free Great Person", "🌟") }

    fun chooseGreatPerson(civ: Civilization, name: String): String {
        civ.greatPeople.chooseFreeGreatPerson(name) ?: throw PlayerError("Couldn't place $name — do you have a city with room?")
        return "🌟 A **$name** joins your empire!"
    }

    // ================================================================== overview

    fun overviewFields(civ: Civilization): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        val s = civ.stats.statsForNextTurn
        out += "🏛️ Empire" to ("${civ.cities.size} cities · ${civ.cities.sumOf { it.population.population }} population · ${civ.units.getCivUnitsSize()} units\n" +
            "Era: ${civ.getEra().name}" + (if (civ.goldenAges.isGoldenAge()) " · ✨ Golden Age (${civ.goldenAges.turnsLeftForCurrentGoldenAge} turns)" else
                " · Golden Age at ${civ.goldenAges.storedHappiness}/${civ.goldenAges.happinessRequiredForNextGoldenAge()} 😊") +
            "\nPer turn: 💰 ${s.gold.toInt()} · 🔬 ${s.science.toInt()} · 🎭 ${s.culture.toInt()} · 🙏 ${s.faith.toInt()}")
        val happy = civ.stats.getHappinessBreakdown().entries.filter { it.value.toInt() != 0 }.sortedBy { it.value }
            .joinToString("\n") { "${if (it.value > 0) "+" else ""}${it.value.toInt()} ${GameOps.clean(it.key)}" }
        if (happy.isNotEmpty()) out += "😊 Happiness ${civ.getHappiness()}" to happy.take(1024)
        val res = civ.getCivResourcesByName().entries.filter { it.value != 0 }.sortedBy { it.key }
        if (res.isNotEmpty()) out += "💎 Resources" to res.joinToString(" · ") { "${it.key} ${it.value}" }.take(1024)
        val gp = civ.greatPeople
        val gpLines = gp.greatPersonPointsCounter.entries.filter { it.value > 0 }.joinToString("\n") { e ->
            "${e.key}: ${e.value}" + (gp.pointsForNextGreatPersonCounter[e.key].takeIf { it > 0 }?.let { "/$it" } ?: "")
        }
        if (gpLines.isNotEmpty()) out += "🌟 Great People points" to gpLines
        val ranks = InfoPlus.rankingLines(civ) // honours showRankings / hideOtherCivilizationStats
        if (ranks.isNotEmpty()) out += "📊 Rankings" to ranks.joinToString("\n").take(1024)
        val victories = civ.gameInfo.gameParameters.victoryTypes.joinToString(" · ")
        if (victories.isNotEmpty()) out += "🏆 Victory types in play" to victories
        val log = civ.notificationsLog.takeLast(2).flatMap { l -> l.notifications.map { "T${l.turn + 1}: " + GameOps.clean(it.text) } }.takeLast(8)
        if (log.isNotEmpty()) out += "📜 Recent history" to log.joinToString("\n").take(1024)
        return out
    }

    // ================================================================== captured cities

    /** After a fight: flag newly captured cities so the panel asks the player what to do with them. */
    fun flagNewConquests(civ: Civilization, citiesBefore: Set<String>) {
        // Unciv's Battle.conquerCity asks about every capture except retaking your own original capital (re-annexed at once)
        for (c in civ.cities) if (c.id !in citiesBefore && c.previousOwner.isNotEmpty() && c.previousOwner != civ.civID &&
            !(c.isOriginalCapital && c.foundingCivObject == civ) && c.turnAcquired == civ.gameInfo.turns)
            if (civ.popupAlerts.none { it.type == AlertType.CityConquered && it.value == c.id })
                civ.popupAlerts.add(PopupAlert(AlertType.CityConquered, c.id))
    }
}

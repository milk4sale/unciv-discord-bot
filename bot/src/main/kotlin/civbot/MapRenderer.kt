package civbot

import com.badlogic.gdx.Gdx
import com.unciv.json.json
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.map.HexCoord
import com.unciv.logic.map.HexMath
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.logic.map.tile.RoadStatus
import com.unciv.logic.map.tile.Tile
import com.unciv.models.tilesets.TileSetConfig
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.geom.Path2D
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

private val SQRT3 = sqrt(3.0)

/** Unciv hex coordinates -> flat-top world coordinates (hex radius 1, y pointing north). */
fun worldOf(x: Int, y: Int): Pair<Double, Double> = Pair(1.5 * (y - x), SQRT3 / 2 * (x + y))

/**
 * A rectangular window of the map centred on one tile, with chess-like codes
 * ("D5": column letter, row number) so players can name tiles in Discord.
 */
class MapWindow(val center: HexCoord, val halfCols: Int = 6, val halfRows: Int = 3) {
    val cols get() = halfCols * 2 + 1
    val rows get() = halfRows * 2 + 1

    /** (column, row) of a tile relative to the window, or null if outside. 0-based, row 0 = top. */
    fun cell(x: Int, y: Int): Pair<Int, Int>? {
        val (cx, cy) = worldOf(center.x, center.y)
        val (wx, wy) = worldOf(x, y)
        val col = ((wx - cx) / 1.5).roundToInt()
        val odd = Math.floorMod(col, 2) == 1
        val row = ((wy - cy) / SQRT3 - if (odd) 0.5 else 0.0).roundToInt()
        if (col < -halfCols || col > halfCols || row < -halfRows || row > halfRows) return null
        return Pair(col + halfCols, halfRows - row)
    }

    fun code(x: Int, y: Int): String? = cell(x, y)?.let { (c, r) -> "${'A' + c}${r + 1}" }

    fun parse(code: String): Pair<Int, Int>? {
        val m = Regex("^([A-Za-z])\\s*(\\d{1,2})$").find(code.trim()) ?: return null
        val c = m.groupValues[1].uppercase()[0] - 'A'
        val r = m.groupValues[2].toInt() - 1
        if (c !in 0 until cols || r !in 0 until rows) return null
        return Pair(c, r)
    }
}

/** What to highlight on top of the map. */
class Overlay(
    val selectedUnit: MapUnit? = null,
    val reachable: Set<Tile> = emptySet(),
    val attackable: Set<Tile> = emptySet(),
    val selectedTile: Tile? = null,
)

/**
 * Draws Unciv maps to PNG using Unciv's own FantasyHex pixel-art tiles,
 * honouring the viewer's fog of war.
 */
object MapRenderer {
    private const val TILESET = "FantasyHex"
    private val imageCache = HashMap<String, BufferedImage?>()
    private val tileSetConfig: TileSetConfig by lazy {
        json().fromJson(TileSetConfig::class.java, Gdx.files.internal("jsons/TileSets/$TILESET.json"))
    }
    private val tilesDir by lazy { File(Engine.uncivDir, "android/Images.Tilesets/TileSets/$TILESET") }

    private fun image(path: String): BufferedImage? = imageCache.getOrPut(path) {
        val f = File(tilesDir, "$path.png")
        if (f.isFile) ImageIO.read(f) else null
    }
    private fun tileImage(name: String) = image("Tiles/$name")

    /** Unit sprites have lots of transparent padding; crop to the visible pixels so they fill the flag. */
    private val trimCache = HashMap<String, BufferedImage?>()
    private fun trimmed(path: String): BufferedImage? = trimCache.getOrPut(path) {
        val img = image(path) ?: return@getOrPut null
        var minX = img.width; var minY = img.height; var maxX = -1; var maxY = -1
        for (y in 0 until img.height) for (x in 0 until img.width) {
            if ((img.getRGB(x, y) ushr 24) > 16) { minX = minOf(minX, x); maxX = maxOf(maxX, x); minY = minOf(minY, y); maxY = maxOf(maxY, y) }
        }
        if (maxX < 0) img else img.getSubimage(minX, minY, maxX - minX + 1, maxY - minY + 1)
    }
    private fun hasTile(name: String) = tileImage(name) != null

    /** Mirrors Unciv's TileLayerTerrain image selection (combined images, rule variants, fallbacks). */
    private fun layersFor(tile: Tile, viewer: Civilization?): List<BufferedImage> {
        val names = ArrayList<String>()
        val terrain = ArrayList<String>().apply {
            add(tile.baseTerrain)
            if (tile.naturalWonder != null) add(tile.naturalWonder!!) else addAll(tile.terrainFeatures)
        }
        val extras = ArrayList<String>()
        val res = tile.resource
        if (res != null) {
            val resObj = tile.ruleset.tileResources[res]
            if (viewer == null || (resObj != null && viewer.tech.isRevealed(resObj))) extras += res
        }
        if (tile.isCityCenter()) {
            // City centre art depends on the owner's era
            val era = tile.getOwner()?.getEra()?.name
            val cityImg = listOf("City center-$era", "City center").firstOrNull { hasTile(it) }
            names += terrain.filter { hasTile(it) }.ifEmpty { listOf(tile.baseTerrain) }
            if (cityImg != null) names += cityImg
            return names.mapNotNull { tileImage(it) }
        }
        tile.getShownImprovement(viewer)?.let { extras += it }

        val all = (terrain + extras).joinToString("+")
        val variant = tileSetConfig.ruleVariants[all]
        when {
            variant != null -> names += variant
            hasTile(all) -> names += all
            else -> {
                // terrain layers: try full combo, else each piece (with known forest/jungle variants)
                val terrainCombo = terrain.joinToString("+")
                val tv = tileSetConfig.ruleVariants[terrainCombo]
                when {
                    tv != null -> names += tv
                    hasTile(terrainCombo) -> names += terrainCombo
                    else -> names += terrain
                }
                if (extras.isNotEmpty()) {
                    val combo = extras.joinToString("+")
                    if (hasTile(combo)) names += combo else names += extras
                }
            }
        }
        return names.mapNotNull { tileImage(it) }
    }

    private fun awt(c: com.badlogic.gdx.graphics.Color, alpha: Int = 255) =
        Color((c.r * 255).toInt().coerceIn(0, 255), (c.g * 255).toInt().coerceIn(0, 255), (c.b * 255).toInt().coerceIn(0, 255), alpha)

    private fun hexPath(cx: Double, cy: Double, r: Double): Path2D.Double = Path2D.Double().apply {
        for (i in 0 until 6) {
            val a = PI / 3 * i
            val px = cx + r * cos(a)
            val py = cy + r * sin(a)
            if (i == 0) moveTo(px, py) else lineTo(px, py)
        }
        closePath()
    }

    /**
     * Render [window] of the map as [viewer] sees it. [viewer] null = no fog (spectator / game over).
     * [scale] multiplies the 32px pixel art.
     */
    fun renderWindow(
        tiles: Collection<Tile>, viewer: Civilization?, window: MapWindow,
        overlay: Overlay = Overlay(), scale: Int = 3, showCodes: Boolean = true,
    ): ByteArray {
        val r = 16.0 * scale
        val colStep = 1.5 * r
        val rowStep = SQRT3 * r
        val width = ((window.cols - 1) * colStep + 2 * r).toInt()
        val height = (window.rows * rowStep + rowStep / 2 + 6 * scale).toInt()
        val originX = r
        val originY = rowStep / 2 + 6 * scale // room for the art that overhangs the top of the hex

        val placed = ArrayList<Triple<Tile, Double, Double>>()
        for (t in tiles) {
            val (c, row) = window.cell(t.position.x, t.position.y) ?: continue
            val odd = Math.floorMod(c - window.halfCols, 2) == 1
            val px = originX + c * colStep
            val py = originY + row * rowStep + (if (odd) -rowStep / 2 else 0.0) + rowStep / 2
            placed += Triple(t, px, py)
        }
        val positions = placed.associate { it.first to Pair(it.second, it.third) }
        return draw(width, height, placed, positions, viewer, overlay, r, scale, if (showCodes) window else null)
    }

    /** The whole map, small, for the "full map" view and end-of-game overview. */
    fun renderWhole(tiles: Collection<Tile>, viewer: Civilization?, scale: Int = 1): ByteArray {
        val r = 16.0 * scale
        val pts = tiles.map { t -> val (wx, wy) = worldOf(t.position.x, t.position.y); Triple(t, wx * r, -wy * r) }
        val minX = pts.minOf { it.second } - r
        val maxX = pts.maxOf { it.second } + r
        val minY = pts.minOf { it.third } - r * 1.2
        val maxY = pts.maxOf { it.third } + r
        val placed = pts.map { Triple(it.first, it.second - minX, it.third - minY) }
        val positions = placed.associate { it.first to Pair(it.second, it.third) }
        return draw((maxX - minX).toInt(), (maxY - minY).toInt(), placed, positions, viewer, Overlay(), r, scale, null)
    }

    private fun draw(
        width: Int, height: Int,
        placed: List<Triple<Tile, Double, Double>>, positions: Map<Tile, Pair<Double, Double>>,
        viewer: Civilization?, overlay: Overlay, r: Double, scale: Int, window: MapWindow?,
    ): ByteArray {
        val img = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        val g = img.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR)
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        g.color = Color(18, 20, 28)
        g.fillRect(0, 0, width, height)

        fun explored(t: Tile) = viewer == null || t.isExplored(viewer)
        fun visible(t: Tile) = viewer == null || t.isVisible(viewer)

        // Draw top-to-bottom so lower tiles' tall art overlaps the ones behind them
        val ordered = placed.sortedBy { it.third }
        val hexH = SQRT3 / 2 * r
        for ((tile, cx, cy) in ordered) {
            if (!explored(tile)) {
                g.color = Color(32, 34, 44)
                g.fill(hexPath(cx, cy, r * 0.97))
                continue
            }
            for (layer in layersFor(tile, viewer)) {
                val w = (2 * r).toInt()
                val h = (layer.height * (2 * r / layer.width)).toInt()
                g.drawImage(layer, (cx - r).toInt(), (cy + hexH - h).toInt(), w, h, null)
            }
        }

        // Rivers, roads and borders
        g.stroke = BasicStroke((1.5 * scale).toFloat(), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
        for ((tile, cx, cy) in placed) {
            if (!explored(tile)) continue
            fun neighborCenter(clock: Int): Pair<Double, Double> {
                val d = HexMath.getClockPositionToHexcoord(clock)
                val (wx0, wy0) = worldOf(tile.position.x, tile.position.y)
                val (wx1, wy1) = worldOf(tile.position.x + d.x, tile.position.y + d.y)
                return Pair(cx + (wx1 - wx0) * r, cy - (wy1 - wy0) * r)
            }
            fun edge(clock: Int, color: Color, inset: Double) {
                val (nx, ny) = neighborCenter(clock)
                val mx = (cx + nx) / 2 + (cx - nx) / 2 * inset
                val my = (cy + ny) / 2 + (cy - ny) / 2 * inset
                val dx = (nx - cx); val dy = (ny - cy)
                val len = sqrt(dx * dx + dy * dy)
                val ux = -dy / len * r / 2; val uy = dx / len * r / 2
                g.color = color
                g.drawLine((mx - ux).toInt(), (my - uy).toInt(), (mx + ux).toInt(), (my + uy).toInt())
            }
            if (tile.roadStatus != RoadStatus.None || tile.isCityCenter()) {
                g.color = if (tile.roadStatus == RoadStatus.Railroad) Color(70, 70, 70) else Color(150, 110, 60)
                for (n in tile.neighbors) {
                    if (n.roadStatus == RoadStatus.None && !n.isCityCenter()) continue
                    if (tile.isCityCenter() && n.isCityCenter()) continue
                    val p = positions[n] ?: continue
                    g.drawLine(cx.toInt(), cy.toInt(), ((cx + p.first) / 2).toInt(), ((cy + p.second) / 2).toInt())
                }
            }
            val river = Color(70, 140, 230)
            g.stroke = BasicStroke((2.0 * scale).toFloat(), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
            if (tile.hasBottomRiver) edge(6, river, 0.0)
            if (tile.hasBottomLeftRiver) edge(8, river, 0.0)
            if (tile.hasBottomRightRiver) edge(4, river, 0.0)
            val owner = tile.getOwner()
            if (owner != null) {
                val col = awt(owner.nation.getOuterColor())
                g.stroke = BasicStroke((1.5 * scale).toFloat(), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
                for (clock in intArrayOf(12, 2, 4, 6, 8, 10)) {
                    val d = HexMath.getClockPositionToHexcoord(clock)
                    val n = tile.tileMap.getIfTileExistsOrNull(tile.position.x + d.x, tile.position.y + d.y)
                    if (n?.getOwner() == owner) continue
                    edge(clock, col, 0.12)
                }
            }
        }

        // Fog over explored-but-not-currently-visible tiles
        for ((tile, cx, cy) in placed) {
            if (explored(tile) && !visible(tile)) {
                g.color = Color(0, 0, 0, 105)
                g.fill(hexPath(cx, cy, r))
            }
        }

        // Highlights
        g.stroke = BasicStroke((1.2 * scale).toFloat())
        for (t in overlay.reachable) {
            val p = positions[t] ?: continue
            g.color = Color(255, 255, 255, 60)
            g.fill(hexPath(p.first, p.second, r * 0.85))
        }
        for (t in overlay.attackable) {
            val p = positions[t] ?: continue
            g.color = Color(235, 50, 50, 230)
            g.draw(hexPath(p.first, p.second, r * 0.85))
        }
        (overlay.selectedTile ?: overlay.selectedUnit?.getTile())?.let { t ->
            val p = positions[t] ?: return@let
            g.color = Color(255, 215, 0)
            g.stroke = BasicStroke((1.8 * scale).toFloat())
            g.draw(hexPath(p.first, p.second, r * 0.9))
        }
        overlay.selectedUnit?.let { drawDestination(g, it, positions, r, scale) }

        // Units
        for ((tile, cx, cy) in ordered) {
            if (!visible(tile)) continue
            val units = listOfNotNull(tile.militaryUnit, tile.civilianUnit, tile.airUnits.firstOrNull())
            if (units.isEmpty()) continue
            val offsets = if (units.size == 1) listOf(0.0) else listOf(-0.38, 0.38, 0.0)
            units.forEachIndexed { i, unit -> drawUnit(g, unit, cx + offsets[i] * r, cy, r, scale, unit == overlay.selectedUnit) }
        }

        // City banners
        for ((tile, cx, cy) in ordered) {
            if (!tile.isCityCenter() || !explored(tile)) continue
            val city = tile.getCity() ?: continue
            val civ = city.civ
            val label = "${city.population.population} ${city.name}${if (city.isCapital()) " ★" else ""}"
            g.font = Font(Font.SANS_SERIF, Font.BOLD, 5 * scale + 3)
            val fm = g.fontMetrics
            val w = fm.stringWidth(label) + 4 * scale
            val h = fm.height
            val bx = (cx - w / 2).toInt(); val by = (cy - hexH * 0.95 - h).toInt().coerceAtLeast(0)
            g.color = awt(civ.nation.getOuterColor(), 230)
            g.fillRoundRect(bx, by, w, h, h / 2, h / 2)
            g.color = awt(civ.nation.getInnerColor())
            g.drawString(label, bx + 2 * scale, by + fm.ascent)
            if (city.health < 200) { // health bar (cities start at 200)
                g.color = Color(40, 0, 0); g.fillRect(bx, by + h, w, scale + 1)
                g.color = Color(80, 220, 80); g.fillRect(bx, by + h, (w * (city.health / 200.0).coerceIn(0.0, 1.0)).toInt(), scale + 1)
            }
        }

        // Tile codes
        if (window != null) {
            g.font = Font(Font.SANS_SERIF, Font.BOLD, 4 * scale + 2)
            val fm = g.fontMetrics
            for ((tile, cx, cy) in placed) {
                val code = window.code(tile.position.x, tile.position.y) ?: continue
                val tx = (cx - fm.stringWidth(code) / 2).toInt()
                val ty = (cy + hexH - 2 * scale).toInt()
                g.color = Color(0, 0, 0, 170)
                g.drawString(code, tx + 1, ty + 1)
                g.color = if (explored(tile)) Color(255, 255, 255, 220) else Color(170, 170, 190, 160)
                g.drawString(code, tx, ty)
            }
        }
        g.dispose()
        return ByteArrayOutputStream().also { ImageIO.write(img, "png", it) }.toByteArray()
    }

    /** Path + marker to where the selected unit is heading (Unciv: getMovementDestination / road destination). */
    private fun drawDestination(g: Graphics2D, u: MapUnit, positions: Map<Tile, Pair<Double, Double>>, r: Double, scale: Int) {
        val dest = UnitsPlus.destinationOf(u) ?: return
        val start = positions[u.getTile()] ?: return
        val (wx0, wy0) = worldOf(u.getTile().position.x, u.getTile().position.y)
        fun at(t: Tile): Pair<Double, Double> = positions[t] ?: worldOf(t.position.x, t.position.y).let { (wx, wy) ->
            Pair(start.first + (wx - wx0) * r, start.second - (wy - wy0) * r) }
        val path = (listOf(u.getTile()) + (runCatching { u.movement.getShortestPath(dest) }.getOrNull().orEmpty().ifEmpty { listOf(dest) }))
        val line = Path2D.Double()
        path.forEachIndexed { i, t -> val (x, y) = at(t); if (i == 0) line.moveTo(x, y) else line.lineTo(x, y) }
        g.stroke = BasicStroke((1.4 * scale).toFloat(), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 1f, floatArrayOf((3f * scale), (2.5f * scale)), 0f)
        g.color = Color(0, 0, 0, 150); g.draw(line)
        g.stroke = BasicStroke((0.8 * scale).toFloat(), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 1f, floatArrayOf((3f * scale), (2.5f * scale)), 0f)
        g.color = Color(120, 230, 255, 235); g.draw(line)
        val (dx, dy) = at(dest)
        g.stroke = BasicStroke((1.8 * scale).toFloat())
        g.color = Color(120, 230, 255)
        g.draw(hexPath(dx, dy, r * 0.8))
        val m = r * 0.18
        g.fillOval((dx - m).toInt(), (dy - m).toInt(), (2 * m).toInt(), (2 * m).toInt())
    }

    private fun drawUnit(g: Graphics2D, unit: MapUnit, cx: Double, cy: Double, r: Double, scale: Int, selected: Boolean) {
        val civ = unit.civ
        val outer = awt(civ.nation.getOuterColor())
        val inner = awt(civ.nation.getInnerColor())
        val rad = r * 0.42
        // Coloured disc = owner, like Unciv's unit flags
        g.color = if (selected) Color(255, 215, 0) else Color(0, 0, 0, 160)
        g.fillOval((cx - rad - scale).toInt(), (cy - rad - scale).toInt(), (2 * rad + 2 * scale).toInt(), (2 * rad + 2 * scale).toInt())
        g.color = outer
        g.fillOval((cx - rad).toInt(), (cy - rad).toInt(), (2 * rad).toInt(), (2 * rad).toInt())
        val sprite = trimmed("Units/${unit.baseUnit.name}")
        if (sprite != null) {
            val s = (2 * rad * 0.78) / maxOf(sprite.width, sprite.height)
            val w = (sprite.width * s).toInt(); val h = (sprite.height * s).toInt()
            g.drawImage(sprite, (cx - w / 2).toInt(), (cy - h / 2).toInt(), w, h, null)
        } else {
            g.color = inner
            g.font = Font(Font.SANS_SERIF, Font.BOLD, (rad).toInt())
            val s = unit.baseUnit.name.take(2)
            val fm = g.fontMetrics
            g.drawString(s, (cx - fm.stringWidth(s) / 2).toInt(), (cy + fm.ascent / 2 - 1).toInt())
        }
        if (unit.health < 100) {
            val bw = (2 * rad).toInt()
            val bx = (cx - rad).toInt(); val by = (cy + rad + scale).toInt()
            g.color = Color(40, 0, 0); g.fillRect(bx, by, bw, scale + 1)
            g.color = if (unit.health > 50) Color(80, 220, 80) else Color(240, 180, 40)
            g.fillRect(bx, by, (bw * unit.health / 100.0).toInt(), scale + 1)
        }
    }

    @Suppress("unused")
    private fun floorDiv(a: Double) = floor(a)
}

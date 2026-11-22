package diagrams.render.raster

import diagrams.assets.TextMeasurer
import diagrams.assets.forEachCodePoint
import diagrams.resolver.Geometry
import diagrams.resolver.Matrix
import diagrams.resolver.ResolvedNode
import diagrams.resolver.ResolvedScene
import diagrams.resolver.Segment
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.geom.AffineTransform
import java.awt.geom.Point2D
import java.awt.geom.Ellipse2D
import java.awt.geom.Path2D
import java.awt.geom.Rectangle2D
import java.awt.geom.RoundRectangle2D
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.roundToInt

/** Headless renderer on the JVM: draws a resolved scene into pixels with Java2D. */
object RasterRenderer {
    /** A font asset: the file renderers draw with and the metrics the resolver measured with (spec section 16). */
    class FontFace(val file: File, val metrics: TextMeasurer)

    data class Options(
        val scale: Double = 1.0,
        val background: String? = "#ffffff",
        val fonts: Map<String, FontFace> = emptyMap(),
        /** Image files by asset name. */
        val images: Map<String, File> = emptyMap(),
        /** A video frame by asset and position in the file; without it a video draws a placeholder. */
        val videoFrame: ((String, Double) -> BufferedImage?)? = null,
    )

    private val decoded = java.util.concurrent.ConcurrentHashMap<File, BufferedImage>()

    private fun image(f: File): BufferedImage? = decoded[f] ?: runCatching { javax.imageio.ImageIO.read(f) }.getOrNull()?.also { decoded[f] = it }

    private val loaded = java.util.concurrent.ConcurrentHashMap<File, Font>()

    private fun awtFont(f: FontFace): Font = loaded.getOrPut(f.file.canonicalFile) { Font.createFont(Font.TRUETYPE_FONT, f.file) }

    /** Closest platform font to the fallback metrics; replaced by theme font files in milestone 1. */
    private val FONT_NAME: String = run {
        val names = runCatching { java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().availableFontFamilyNames.toSet() }.getOrDefault(emptySet())
        listOf("Helvetica", "Arial", "Liberation Sans", "DejaVu Sans").firstOrNull { it in names } ?: Font.SANS_SERIF
    }

    fun render(scene: ResolvedScene, options: Options = Options()): BufferedImage {
        val w = (scene.width * options.scale).roundToInt().coerceAtLeast(1)
        val h = (scene.height * options.scale).roundToInt().coerceAtLeast(1)
        val img = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
        val g = img.createGraphics()
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
            g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON)
            options.background?.let {
                g.color = color(it, 1.0)
                g.fillRect(0, 0, w, h)
            }
            node(g, scene.root, Matrix.scale(options.scale), 1.0, options)
        } finally {
            g.dispose()
        }
        return img
    }

    fun renderPng(scene: ResolvedScene, options: Options = Options()): ByteArray {
        ImageIO.setUseCache(false)
        val out = ByteArrayOutputStream()
        ImageIO.write(render(scene, options), "png", out)
        return out.toByteArray()
    }

    fun writePng(scene: ResolvedScene, file: File, options: Options = Options()) {
        file.writeBytes(renderPng(scene, options))
    }

    private fun color(c: String, opacity: Double): Color {
        val r = c.substring(1, 3).toInt(16)
        val gr = c.substring(3, 5).toInt(16)
        val b = c.substring(5, 7).toInt(16)
        val a = if (c.length == 9) c.substring(7, 9).toInt(16) else 255
        return Color(r, gr, b, (a * opacity).roundToInt().coerceIn(0, 255))
    }

    private fun Matrix.awt() = AffineTransform(a, b, c, d, e, f)

    /** An image or video frame fitted into the node's box (`contain`, `cover` or `fill`), else a placeholder. */
    private fun media(g: Graphics2D, m: Geometry.Media, opacity: Double, options: Options) {
        val img = if (m.kind == "video") m.time?.let { options.videoFrame?.invoke(m.asset, it) } else options.images[m.asset]?.let { image(it) }
        val comp = g.composite
        if (opacity < 1.0) g.composite = java.awt.AlphaComposite.getInstance(java.awt.AlphaComposite.SRC_OVER, opacity.toFloat().coerceIn(0f, 1f))
        try {
            if (img == null) {
                g.color = Color(0x2d, 0x34, 0x36)
                g.fill(Rectangle2D.Double(0.0, 0.0, m.width, m.height))
                if (m.kind == "video") {
                    val s = minOf(m.width, m.height) * 0.18
                    g.color = Color(0xdf, 0xe6, 0xe9)
                    g.fill(Path2D.Double().apply { moveTo(m.width / 2 - s * 0.6, m.height / 2 - s); lineTo(m.width / 2 - s * 0.6, m.height / 2 + s); lineTo(m.width / 2 + s, m.height / 2); closePath() })
                }
                return
            }
            val iw = img.width.toDouble()
            val ih = img.height.toDouble()
            val sx = m.width / iw
            val sy = m.height / ih
            val (w, h) = when (m.fit) {
                "fill" -> m.width to m.height
                "cover" -> maxOf(sx, sy).let { iw * it to ih * it }
                else -> minOf(sx, sy).let { iw * it to ih * it }
            }
            val clip = g.clip
            g.clip(Rectangle2D.Double(0.0, 0.0, m.width, m.height))
            g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BICUBIC)
            val at = AffineTransform.getTranslateInstance((m.width - w) / 2, (m.height - h) / 2)
            at.scale(w / iw, h / ih)
            g.drawImage(img, at, null)
            g.clip = clip
        } finally {
            g.composite = comp
        }
    }

    /** Length of a shape's outline, curves flattened. */
    private fun length(shape: java.awt.Shape): Double {
        val it = shape.getPathIterator(null, 0.25)
        val c = DoubleArray(6)
        var total = 0.0
        var sx = 0.0
        var sy = 0.0
        var x = 0.0
        var y = 0.0
        while (!it.isDone) {
            when (it.currentSegment(c)) {
                java.awt.geom.PathIterator.SEG_MOVETO -> { x = c[0]; y = c[1]; sx = x; sy = y }
                java.awt.geom.PathIterator.SEG_LINETO -> { total += kotlin.math.hypot(c[0] - x, c[1] - y); x = c[0]; y = c[1] }
                java.awt.geom.PathIterator.SEG_CLOSE -> { total += kotlin.math.hypot(sx - x, sy - y); x = sx; y = sy }
            }
            it.next()
        }
        return total
    }

    private fun node(g: Graphics2D, n: ResolvedNode, parent: Matrix, parentOpacity: Double, options: Options) {
        val world = parent * n.transform
        val opacity = parentOpacity * n.opacity
        val saved = g.transform
        g.transform = world.awt()
        try {
            val p = n.paint
            fun fillAndStroke(shape: java.awt.Shape) {
                p.fill?.let {
                    g.color = color(it, opacity)
                    g.fill(shape)
                }
                if (p.stroke != null && p.strokeWidth > 0 && n.draw > 0.0) {
                    g.color = color(p.stroke!!, opacity)
                    g.stroke = if (n.draw < 1.0) {
                        // a partly drawn stroke: one dash as long as that fraction of the outline
                        val len = length(shape)
                        BasicStroke(p.strokeWidth.toFloat(), BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f, floatArrayOf((len * n.draw).toFloat().coerceAtLeast(0.001f), (len + 1).toFloat()), 0f)
                    } else {
                        BasicStroke(p.strokeWidth.toFloat(), BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER)
                    }
                    g.draw(shape)
                }
            }
            when (val geo = n.geometry) {
                Geometry.Group -> {}
                is Geometry.Media -> media(g, geo, n.opacity * parentOpacity, options)
                is Geometry.Rect -> fillAndStroke(
                    if (geo.cornerRadius > 0) {
                        RoundRectangle2D.Double(0.0, 0.0, geo.width, geo.height, 2 * geo.cornerRadius, 2 * geo.cornerRadius)
                    } else {
                        Rectangle2D.Double(0.0, 0.0, geo.width, geo.height)
                    },
                )
                is Geometry.Ellipse -> fillAndStroke(Ellipse2D.Double(geo.cx - geo.rx, geo.cy - geo.ry, 2 * geo.rx, 2 * geo.ry))
                is Geometry.Poly -> {
                    fillAndStroke(
                        Path2D.Double().apply {
                            geo.points.forEachIndexed { i, pt -> if (i == 0) moveTo(pt.x, pt.y) else lineTo(pt.x, pt.y) }
                            if (geo.closed) closePath()
                        },
                    )
                    val stroke = p.stroke
                    if (stroke != null && n.draw >= 1.0) for (m in geo.markers) {
                        val shape = Path2D.Double().apply {
                            m.points.forEachIndexed { i, pt -> if (i == 0) moveTo(pt.x, pt.y) else lineTo(pt.x, pt.y) }
                            if (m.closed) closePath()
                        }
                        g.color = color(stroke, opacity)
                        if (m.filled) g.fill(shape)
                        g.stroke = BasicStroke(p.strokeWidth.toFloat(), BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER)
                        g.draw(shape)
                    }
                }
                is Geometry.PathGeom -> fillAndStroke(
                    Path2D.Double().apply {
                        for (s in geo.segments) {
                            when (s) {
                                is Segment.Move -> moveTo(s.p.x, s.p.y)
                                is Segment.Line -> lineTo(s.p.x, s.p.y)
                                is Segment.Cubic -> curveTo(s.c1.x, s.c1.y, s.c2.x, s.c2.y, s.p.x, s.p.y)
                                is Segment.Quad -> quadTo(s.c.x, s.c.y, s.p.x, s.p.y)
                                Segment.Close -> closePath()
                            }
                        }
                    },
                )
                is Geometry.Text -> {
                    val fill = p.fill ?: return
                    g.color = color(fill, opacity)
                    val face = geo.font?.let { options.fonts[it] }
                    if (face == null) {
                        g.font = Font(FONT_NAME, Font.PLAIN, 1).deriveFont(geo.fontSize.toFloat())
                        for (line in geo.lines) {
                            if (line.text.isNotEmpty()) g.drawString(line.text, line.x.toFloat(), line.baseline.toFloat())
                        }
                    } else {
                        // one glyph per character, placed at the advances the resolver measured: no platform layout
                        val font = awtFont(face).deriveFont(geo.fontSize.toFloat())
                        for (line in geo.lines) {
                            if (line.text.isEmpty()) continue
                            val gv = font.createGlyphVector(g.fontRenderContext, line.text)
                            var x = line.x
                            var i = 0
                            forEachCodePoint(line.text) { cp ->
                                if (i < gv.numGlyphs) gv.setGlyphPosition(i, Point2D.Double(x, 0.0))
                                x += face.metrics.charAdvance(cp, geo.fontSize)
                                i++
                            }
                            g.drawGlyphVector(gv, 0f, line.baseline.toFloat())
                        }
                    }
                }
            }
        } finally {
            g.transform = saved
        }
        for (c in n.children) node(g, c, world, opacity, options)
    }

    /**
     * Image comparison within a tolerance (spec section 19): the share of pixels whose largest
     * channel difference exceeds [channelTolerance]. Different sizes compare as 1.0.
     */
    fun diffRatio(a: BufferedImage, b: BufferedImage, channelTolerance: Int = 24): Double {
        if (a.width != b.width || a.height != b.height) return 1.0
        var bad = 0L
        for (y in 0 until a.height) for (x in 0 until a.width) {
            val p = a.getRGB(x, y)
            val q = b.getRGB(x, y)
            var maxD = 0
            for (shift in intArrayOf(0, 8, 16, 24)) {
                val d = kotlin.math.abs(((p ushr shift) and 0xff) - ((q ushr shift) and 0xff))
                if (d > maxD) maxD = d
            }
            if (maxD > channelTolerance) bad++
        }
        return bad.toDouble() / (a.width.toLong() * a.height)
    }
}

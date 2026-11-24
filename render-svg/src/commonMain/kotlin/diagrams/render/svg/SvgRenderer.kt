package diagrams.render.svg

import diagrams.assets.TextMeasurer
import diagrams.assets.forEachCodePoint
import diagrams.model.formatNumber
import diagrams.resolver.Geometry
import diagrams.resolver.Matrix
import diagrams.resolver.ResolvedNode
import diagrams.resolver.ResolvedScene
import diagrams.resolver.Segment

/** Draws a resolved scene as SVG. It reads geometry only: nothing is measured or laid out here. */
object SvgRenderer {
    /** Matches the built-in fallback metrics until theme fonts arrive (milestone 1). */
    const val FONT_FAMILY = "Helvetica, Arial, sans-serif"

    /** A font asset for SVG: where the browser loads it from, and the metrics the resolver measured with. */
    class Font(val url: String, val metrics: TextMeasurer)

    /**
     * [fontFaces] false leaves out the `@font-face` rules: a page that holds many SVGs (a deck)
     * declares each font once, with [fontFaceCss].
     */
    data class Options(
        val background: String? = null,
        val scale: Double = 1.0,
        val fonts: Map<String, Font> = emptyMap(),
        val fontFaces: Boolean = true,
        /** URLs of image assets, by asset name (data URIs make a self-contained SVG). */
        val media: Map<String, String> = emptyMap(),
        /** A video frame's URL, by asset and position in the file; without it a video draws a placeholder. */
        val videoFrame: ((String, Double) -> String?)? = null,
    )

    /** The CSS rule that makes a font asset available under the family name the SVG uses. */
    fun fontFaceCss(asset: String, url: String) = "@font-face { font-family: \"${fontFamily(asset)}\"; src: url(\"${esc(url)}\"); }"

    fun render(scene: ResolvedScene, options: Options = Options()): String {
        val sb = StringBuilder()
        val w = scene.width * options.scale
        val h = scene.height * options.scale
        sb.append("<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"${n(w)}\" height=\"${n(h)}\" viewBox=\"0 0 ${n(scene.width)} ${n(scene.height)}\">\n")
        val used = HashSet<String>()
        scene.root.walk { n -> (n.geometry as? Geometry.Text)?.font?.let { if (it in options.fonts) used += it } }
        if (used.isNotEmpty() && options.fontFaces) {
            sb.append("  <defs><style>\n")
            for (f in used.sorted()) sb.append("    ${fontFaceCss(f, options.fonts.getValue(f).url)}\n")
            sb.append("  </style></defs>\n")
        }
        options.background?.let { sb.append("  <rect width=\"100%\" height=\"100%\" fill=\"${color(it).first}\"/>\n") }
        node(scene.root, sb, 1, options)
        sb.append("</svg>\n")
        return sb.toString()
    }

    private fun n(d: Double) = formatNumber(d)

    /** `#rrggbbaa` becomes a 6-digit color plus an opacity, which every SVG consumer understands. */
    private fun color(c: String): Pair<String, Double?> =
        if (c.length == 9) c.substring(0, 7) to c.substring(7).toInt(16) / 255.0 else c to null

    private fun paintAttrs(fill: String?, stroke: String?, strokeWidth: Double): String {
        val sb = StringBuilder()
        if (fill == null) {
            sb.append(" fill=\"none\"")
        } else {
            val (c, a) = color(fill)
            sb.append(" fill=\"$c\"")
            if (a != null) sb.append(" fill-opacity=\"${n(a)}\"")
        }
        if (stroke != null && strokeWidth > 0) {
            val (c, a) = color(stroke)
            sb.append(" stroke=\"$c\" stroke-width=\"${n(strokeWidth)}\"")
            if (a != null) sb.append(" stroke-opacity=\"${n(a)}\"")
        }
        return sb.toString()
    }

    private fun transform(m: Matrix): String = when {
        m.isIdentity -> ""
        m.a == 1.0 && m.b == 0.0 && m.c == 0.0 && m.d == 1.0 -> " transform=\"translate(${n(m.e)} ${n(m.f)})\""
        else -> " transform=\"matrix(${m.toList().joinToString(" ") { n(it) }})\""
    }

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    private fun fontFamily(asset: String) = "diagrams-$asset"

    private fun node(node: ResolvedNode, sb: StringBuilder, depth: Int, options: Options) {
        val pad = "  ".repeat(depth)
        val id = node.ref?.let { " data-path=\"${esc(it)}\"" } ?: ""
        val op = if (node.opacity != 1.0) " opacity=\"${n(node.opacity)}\"" else ""
        val t = transform(node.transform)
        val p = node.paint
        // a partly drawn stroke: the dash pattern covers that fraction of the outline (spec: `draw`)
        // `stroke_dash` otherwise; markers stay solid
        val pattern = if (p.dash.isNotEmpty() && p.stroke != null) " stroke-dasharray=\"${p.dash.joinToString(" ") { n(it) }}\"" else ""
        val dash = if (node.draw < 1.0) " pathLength=\"1\" stroke-dasharray=\"${n(node.draw)} 1\"" else pattern
        when (val g = node.geometry) {
            Geometry.Group -> {
                if (node.children.isEmpty()) return
                sb.append("$pad<g$id$t$op>\n")
                node.children.forEach { node(it, sb, depth + 1, options) }
                sb.append("$pad</g>\n")
            }
            is Geometry.Rect -> {
                val r = if (g.cornerRadius > 0) " rx=\"${n(g.cornerRadius)}\"" else ""
                sb.append("$pad<rect$id$t$op width=\"${n(g.width)}\" height=\"${n(g.height)}\"$r${paintAttrs(p.fill, p.stroke, p.strokeWidth)}$dash/>\n")
            }
            is Geometry.Ellipse -> sb.append(
                "$pad<ellipse$id$t$op cx=\"${n(g.cx)}\" cy=\"${n(g.cy)}\" rx=\"${n(g.rx)}\" ry=\"${n(g.ry)}\"${paintAttrs(p.fill, p.stroke, p.strokeWidth)}$dash/>\n",
            )
            is Geometry.Poly -> {
                val tag = if (g.closed) "polygon" else "polyline"
                val pts = g.points.joinToString(" ") { "${n(it.x)},${n(it.y)}" }
                // markers show once the stroke is fully drawn
                if (g.markers.isEmpty() || node.draw < 1.0) {
                    sb.append("$pad<$tag$id$t$op points=\"$pts\"${paintAttrs(p.fill, p.stroke, p.strokeWidth)}$dash/>\n")
                } else {
                    sb.append("$pad<g$id$t$op>\n")
                    sb.append("$pad  <$tag points=\"$pts\"${paintAttrs(p.fill, p.stroke, p.strokeWidth)}$pattern/>\n")
                    for (m in g.markers) {
                        val mt = if (m.closed) "polygon" else "polyline"
                        val mp = m.points.joinToString(" ") { "${n(it.x)},${n(it.y)}" }
                        sb.append("$pad  <$mt points=\"$mp\"${paintAttrs(if (m.filled) p.stroke else null, p.stroke, p.strokeWidth)} stroke-linejoin=\"miter\"/>\n")
                    }
                    sb.append("$pad</g>\n")
                }
            }
            is Geometry.Media -> {
                val href = if (g.kind == "video") g.time?.let { t -> options.videoFrame?.invoke(g.asset, t) } else options.media[g.asset]
                if (href != null) {
                    val par = when (g.fit) {
                        "cover" -> "xMidYMid slice"
                        "fill" -> "none"
                        else -> "xMidYMid meet"
                    }
                    sb.append("$pad<image$id$t$op width=\"${n(g.width)}\" height=\"${n(g.height)}\" preserveAspectRatio=\"$par\" href=\"${esc(href)}\"/>\n")
                } else {
                    // no file to show: a placeholder the size of the media, named after its asset
                    val s = minOf(g.width, g.height) * 0.18
                    val cx = g.width / 2
                    val cy = g.height / 2
                    sb.append("$pad<g$id$t$op>\n")
                    sb.append("$pad  <rect width=\"${n(g.width)}\" height=\"${n(g.height)}\" fill=\"#2d3436\"/>\n")
                    if (g.kind == "video") sb.append("$pad  <polygon points=\"${n(cx - s * 0.6)},${n(cy - s)} ${n(cx - s * 0.6)},${n(cy + s)} ${n(cx + s)},${n(cy)}\" fill=\"#dfe6e9\"/>\n")
                    sb.append("$pad  <text x=\"8\" y=\"${n(g.height - 8)}\" font-family=\"sans-serif\" font-size=\"${n(maxOf(9.0, minOf(14.0, g.height / 10)))}\" fill=\"#dfe6e9\">${esc(g.asset)}</text>\n")
                    sb.append("$pad</g>\n")
                }
            }
            is Geometry.PathGeom -> {
                val d = g.segments.joinToString(" ") { s ->
                    when (s) {
                        is Segment.Move -> "M${n(s.p.x)} ${n(s.p.y)}"
                        is Segment.Line -> "L${n(s.p.x)} ${n(s.p.y)}"
                        is Segment.Cubic -> "C${n(s.c1.x)} ${n(s.c1.y)} ${n(s.c2.x)} ${n(s.c2.y)} ${n(s.p.x)} ${n(s.p.y)}"
                        is Segment.Quad -> "Q${n(s.c.x)} ${n(s.c.y)} ${n(s.p.x)} ${n(s.p.y)}"
                        Segment.Close -> "Z"
                    }
                }
                sb.append("$pad<path$id$t$op d=\"$d\"${paintAttrs(p.fill, p.stroke, p.strokeWidth)}$dash/>\n")
            }
            is Geometry.Text -> {
                val font = g.font?.let { options.fonts[it] }
                // a font asset gets one x per character: the browser draws where the resolver measured
                val family = if (font != null) fontFamily(g.font!!) else FONT_FAMILY
                val features = if (font != null) " style=\"font-kerning: none; font-variant-ligatures: none\"" else ""
                sb.append("$pad<g$id$t$op font-family=\"$family\" font-size=\"${n(g.fontSize)}\"$features${paintAttrs(p.fill, null, 0.0)}>\n")
                for (line in g.lines) {
                    if (line.text.isEmpty()) continue
                    val xs = if (font == null) {
                        n(line.x)
                    } else {
                        val out = mutableListOf<String>()
                        var x = line.x
                        forEachCodePoint(line.text) { cp ->
                            out += n(x)
                            x += font.metrics.charAdvance(cp, g.fontSize)
                        }
                        out.joinToString(" ")
                    }
                    sb.append("$pad  <text x=\"$xs\" y=\"${n(line.baseline)}\" xml:space=\"preserve\">${esc(line.text)}</text>\n")
                }
                sb.append("$pad</g>\n")
            }
        }
    }
}

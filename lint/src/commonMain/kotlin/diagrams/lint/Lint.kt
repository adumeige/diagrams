package diagrams.lint

import diagrams.model.Diagnostic
import diagrams.model.Severity
import diagrams.model.Value
import diagrams.model.formatNumber
import diagrams.resolver.Geometry
import diagrams.resolver.ResolvedNode
import diagrams.resolver.ResolvedScene

/**
 * Geometric lint on the resolved scene (spec section 18). Severity is configurable per rule;
 * a rule set to null is off.
 */
data class LintConfig(
    val severities: Map<String, Severity?> = DEFAULTS,
    /** Smallest readable on-screen text height, in units at zoom 1. */
    val minTextSize: Double = 9.0,
    /** WCAG contrast ratios: normal text, and large text (at least [largeTextSize] units). */
    val minContrast: Double = 4.5,
    val minContrastLarge: Double = 3.0,
    val largeTextSize: Double = 24.0,
) {
    companion object {
        val DEFAULTS: Map<String, Severity?> = mapOf(
            Lint.OUT_OF_VIEW to Severity.WARNING,
            Lint.TEXT_TOO_SMALL to Severity.WARNING,
            Lint.OVERLAP_SIBLINGS to null,
            Lint.LOW_CONTRAST to Severity.WARNING,
            Lint.TEXT_OVERFLOW to Severity.WARNING,
            Lint.CONNECTOR_CROSSES_SHAPE to Severity.WARNING,
        )
    }
}

object Lint {
    const val OUT_OF_VIEW = "out_of_view"
    const val TEXT_TOO_SMALL = "text_too_small"
    const val OVERLAP_SIBLINGS = "overlap_siblings"
    const val LOW_CONTRAST = "low_contrast"
    const val TEXT_OVERFLOW = "text_overflow"
    const val CONNECTOR_CROSSES_SHAPE = "connector_crosses_shape"

    fun run(full: ResolvedScene, config: LintConfig = LintConfig()): List<Diagnostic> {
        // what cannot be seen is not linted: subtrees at opacity 0 (a hidden slide, a step not shown yet)
        val scene = visible(full)
        val out = mutableListOf<Diagnostic>()
        fun report(rule: String, node: ResolvedNode, message: String, data: Value.Map? = null) {
            val sev = config.severities[rule] ?: return
            out += Diagnostic(rule, sev, node.label, message, null, data)
        }

        val view = scene.viewportBox
        fun visit(n: ResolvedNode, parentOutside: Boolean) {
            // report the outermost node that leaves the view, not each of its descendants
            var outside = parentOutside
            if (!parentOutside && n.kind != "container" && !view.contains(n.box)) {
                val b = n.box
                val overflow = Value.map(
                    "left" to Value.Num(maxOf(0.0, -b.x)),
                    "top" to Value.Num(maxOf(0.0, -b.y)),
                    "right" to Value.Num(maxOf(0.0, b.right - view.right)),
                    "bottom" to Value.Num(maxOf(0.0, b.bottom - view.bottom)),
                )
                val fully = !view.intersects(b)
                report(OUT_OF_VIEW, n, if (fully) "node is entirely outside the viewport" else "node extends past the viewport", overflow)
                outside = true
            }
            val g = n.geometry
            if (g is Geometry.Text) {
                val size = g.fontSize * n.world.scaleFactor
                if (size < config.minTextSize) {
                    report(TEXT_TOO_SMALL, n, "text renders at ${formatNumber(size)} units, below ${formatNumber(config.minTextSize)}", Value.map("size" to Value.Num(size)))
                }
            }
            if (config.severities[OVERLAP_SIBLINGS] != null) {
                val shapes = n.children.filter { it.kind != "container" && it.kind != "text" }
                for (i in shapes.indices) for (j in i + 1 until shapes.size) {
                    if (shapes[i].box.intersects(shapes[j].box)) report(OVERLAP_SIBLINGS, shapes[j], "overlaps sibling ${shapes[i].label}")
                }
            }
            n.children.forEach { visit(it, outside) }
        }
        visit(scene.root, false)
        if (config.severities[LOW_CONTRAST] != null) contrast(scene, config) { n, msg, data -> report(LOW_CONTRAST, n, msg, data) }
        if (config.severities[TEXT_OVERFLOW] != null) overflow(scene) { n, msg, data -> report(TEXT_OVERFLOW, n, msg, data) }
        if (config.severities[CONNECTOR_CROSSES_SHAPE] != null) crossings(scene) { n, msg, data -> report(CONNECTOR_CROSSES_SHAPE, n, msg, data) }
        return out
    }
}

private fun visible(s: ResolvedScene): ResolvedScene {
    fun prune(n: ResolvedNode): ResolvedNode = ResolvedNode(
        n.ref, n.loc, n.kind, n.layer, n.local, n.transform, n.world, n.box, n.opacity, n.paint, n.geometry,
        n.children.filter { it.opacity > 0.0 }.map(::prune), n.template,
    )
    return ResolvedScene(s.format, s.width, s.height, s.time, prune(s.root), s.background, s.states)
}

/** A painted, filled shape, in paint order: what text may sit on. */
private class Backdrop(val box: diagrams.resolver.Box, val rgb: DoubleArray, val alpha: Double)

private fun rgba(c: String): Pair<DoubleArray, Double> {
    val ch = DoubleArray(3) { c.substring(1 + 2 * it, 3 + 2 * it).toInt(16) / 255.0 }
    val a = if (c.length == 9) c.substring(7, 9).toInt(16) / 255.0 else 1.0
    return ch to a
}

private fun over(top: DoubleArray, alpha: Double, bottom: DoubleArray) = DoubleArray(3) { top[it] * alpha + bottom[it] * (1 - alpha) }

/** WCAG 2 relative luminance of an sRGB color. */
private fun luminance(c: DoubleArray): Double {
    fun lin(v: Double) = if (v <= 0.03928) v / 12.92 else kotlin.math.exp(2.4 * kotlin.math.ln((v + 0.055) / 1.055))
    return 0.2126 * lin(c[0]) + 0.7152 * lin(c[1]) + 0.0722 * lin(c[2])
}

private fun hex(c: DoubleArray) = "#" + c.joinToString("") { (kotlin.math.round(it * 255).toInt()).toString(16).padStart(2, '0') }

/**
 * `low_contrast`: text against its resolved fill. The backdrop is the scene background (white when
 * none) with every earlier-painted filled shape whose box covers the text's box composited over it.
 * Boxes are axis-aligned, so a shape whose corners are empty (an ellipse) counts as covering them.
 */
private fun contrast(scene: ResolvedScene, config: LintConfig, report: (ResolvedNode, String, Value.Map) -> Unit) {
    val painted = mutableListOf<Backdrop>()
    val base = rgba(scene.background ?: "#ffffff").first
    fun visit(n: ResolvedNode, parentOpacity: Double) {
        val opacity = parentOpacity * n.opacity
        val g = n.geometry
        val fill = n.paint.fill
        if (g is Geometry.Text && fill != null) {
            var bg = base
            for (b in painted) if (b.box.contains(n.box)) bg = over(b.rgb, b.alpha, bg)
            val (rgb, a) = rgba(fill)
            val fg = over(rgb, a * opacity, bg)
            val l1 = luminance(fg)
            val l2 = luminance(bg)
            val ratio = (maxOf(l1, l2) + 0.05) / (minOf(l1, l2) + 0.05)
            val size = g.fontSize * n.world.scaleFactor
            val min = if (size >= config.largeTextSize) config.minContrastLarge else config.minContrast
            if (ratio < min) {
                report(
                    n,
                    "text color ${hex(fg)} on ${hex(bg)} has contrast ${formatNumber(kotlin.math.round(ratio * 100) / 100)}:1, below ${formatNumber(min)}:1",
                    Value.map("ratio" to Value.Num(kotlin.math.round(ratio * 100) / 100), "foreground" to Value.Str(hex(fg)), "background" to Value.Str(hex(bg))),
                )
            }
        } else if (fill != null && g !is Geometry.Group && g !is Geometry.Text) {
            val (rgb, a) = rgba(fill)
            if (a * opacity > 0) painted += Backdrop(n.box, rgb, a * opacity)
        }
        n.children.forEach { visit(it, opacity) }
    }
    visit(scene.root, 1.0)
}

private val SHAPES = setOf("rect", "ellipse", "circle", "polygon", "path")

private fun r2(d: Double) = kotlin.math.round(d * 100) / 100

/**
 * `text_overflow`: a line wider than its text box (an explicit `width`), or text that leaves the
 * filled shape it sits on: the topmost earlier-painted shape under the center of the text's box.
 */
private fun overflow(scene: ResolvedScene, report: (ResolvedNode, String, Value.Map) -> Unit) {
    val painted = mutableListOf<ResolvedNode>()
    fun visit(n: ResolvedNode) {
        val g = n.geometry
        if (g is Geometry.Text) {
            val widest = g.lines.maxOfOrNull { it.width } ?: 0.0
            val over = widest - n.local.width
            if (over > 1e-6) {
                report(n, "text is ${formatNumber(r2(over))} units wider than its box (width ${formatNumber(r2(n.local.width))})", Value.map("overflow" to Value.Num(r2(over))))
            } else {
                val cx = n.box.centerX
                val cy = n.box.centerY
                val under = painted.lastOrNull { it.box.x <= cx && cx <= it.box.right && it.box.y <= cy && cy <= it.box.bottom }
                if (under != null && !under.box.contains(n.box, 1e-6)) {
                    val b = n.box
                    val u = under.box
                    val by = maxOf(u.x - b.x, b.right - u.right, u.y - b.y, b.bottom - u.bottom)
                    report(n, "text overflows ${under.label} by ${formatNumber(r2(by))} units", Value.map("overflow" to Value.Num(r2(by)), "shape" to Value.Str(under.label)))
                }
            }
        } else if (n.kind in SHAPES && n.paint.fill != null && rgba(n.paint.fill!!).second > 0) {
            // a fully transparent fill (an outline) holds nothing
            painted += n
        }
        n.children.forEach(::visit)
    }
    visit(scene.root)
}

/** Does the segment a-b pass through the interior of [box] (shrunk by [inset])? Liang-Barsky clipping. */
private fun crosses(a: diagrams.resolver.Point, b: diagrams.resolver.Point, box: diagrams.resolver.Box, inset: Double): Boolean {
    val x0 = box.x + inset
    val x1 = box.right - inset
    val y0 = box.y + inset
    val y1 = box.bottom - inset
    if (x0 >= x1 || y0 >= y1) return false
    var t0 = 0.0
    var t1 = 1.0
    val dx = b.x - a.x
    val dy = b.y - a.y
    for ((p, q) in listOf(-dx to a.x - x0, dx to x1 - a.x, -dy to a.y - y0, dy to y1 - a.y)) {
        if (p == 0.0) {
            if (q < 0) return false
        } else {
            val r = q / p
            if (p < 0) t0 = maxOf(t0, r) else t1 = minOf(t1, r)
            if (t0 > t1) return false
        }
    }
    return t1 - t0 > 1e-9
}

/**
 * `connector_crosses_shape`: a connector running through a shape's box. Shapes containing either
 * end of the connector are its ends (or hold them) and are skipped.
 */
private fun crossings(scene: ResolvedScene, report: (ResolvedNode, String, Value.Map) -> Unit) {
    val shapes = mutableListOf<ResolvedNode>()
    val connectors = mutableListOf<ResolvedNode>()
    scene.root.walk { n ->
        when {
            n.kind == "connector" -> connectors += n
            n.kind in SHAPES -> shapes += n
        }
    }
    for (c in connectors) {
        val pts = (c.geometry as? Geometry.Poly)?.points?.map { c.world.apply(it) } ?: continue
        if (pts.size < 2) continue
        val first = pts.first()
        val last = pts.last()
        fun holds(b: diagrams.resolver.Box, p: diagrams.resolver.Point) = p.x >= b.x - 0.5 && p.x <= b.right + 0.5 && p.y >= b.y - 0.5 && p.y <= b.bottom + 0.5
        for (s in shapes) {
            if (holds(s.box, first) || holds(s.box, last)) continue
            if (pts.zipWithNext().any { (a, b) -> crosses(a, b, s.box, 0.5) }) {
                report(c, "connector runs through ${s.label}", Value.map("shape" to Value.Str(s.label)))
            }
        }
    }
}

package diagrams.resolver

import diagrams.model.Value
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** Easing curves (spec section 12): `linear`, `in`, `out`, `in_out`, or `{bezier: [x1, y1, x2, y2]}`. */
class Easing private constructor(private val x1: Double, private val y1: Double, private val x2: Double, private val y2: Double, private val linear: Boolean) {
    /** Eased progress for linear progress [t] in 0..1. */
    operator fun invoke(t: Double): Double {
        val u = t.coerceIn(0.0, 1.0)
        if (linear || u == 0.0 || u == 1.0) return u
        // solve x(s) = u for the curve parameter s, then return y(s)
        var lo = 0.0
        var hi = 1.0
        var s = u
        repeat(40) {
            val x = bez(s, x1, x2)
            if (abs(x - u) < 1e-7) return bez(s, y1, y2)
            if (x < u) lo = s else hi = s
            s = (lo + hi) / 2
        }
        return bez(s, y1, y2)
    }

    private fun bez(s: Double, p1: Double, p2: Double): Double {
        val m = 1 - s
        return 3 * m * m * s * p1 + 3 * m * s * s * p2 + s * s * s
    }

    companion object {
        val LINEAR = Easing(0.0, 0.0, 1.0, 1.0, true)

        /** The CSS curves of the same names. */
        fun of(v: Value?): Easing = when (v) {
            is Value.Str -> when (v.value) {
                "in" -> Easing(0.42, 0.0, 1.0, 1.0, false)
                "out" -> Easing(0.0, 0.0, 0.58, 1.0, false)
                "in_out" -> Easing(0.42, 0.0, 0.58, 1.0, false)
                else -> LINEAR
            }
            is Value.Map -> {
                val l = (v["bezier"] as? Value.List)?.items?.mapNotNull { (it as? Value.Num)?.value }
                if (l == null || l.size != 4) LINEAR else Easing(l[0], l[1], l[2], l[3], false)
            }
            else -> LINEAR
        }
    }
}

/**
 * Automatic transitions (spec section 13): a frame between two resolved scenes. Nodes are matched by
 * path (unnamed nodes by locator); boxes, transforms, opacity, colors and compatible geometry
 * interpolate; text and other discrete values switch at the end. A node present in only one scene
 * pops: an exiting node is gone as soon as the transition starts, an entering node appears at its end.
 *
 * Connectors and attached nodes are interpolated like every other node rather than routed again on
 * every frame (a simplification of section 13's per-frame order, see the README).
 */
object Transitions {
    /** The frame at [progress] (already eased, 0..1). Progress 0 gives [a], 1 gives [b]. */
    fun frame(a: ResolvedScene, b: ResolvedScene, progress: Double): ResolvedScene {
        if (progress <= 0.0) return a
        if (progress >= 1.0) return b
        // a path names one node, except the parts of unnamed template instances (three unnamed links
        // each have a `line`): those are matched by locator, which is unique
        val shared = HashSet<String>()
        for (s in listOf(a, b)) {
            val seen = HashSet<String>()
            s.root.walk { n -> n.ref?.let { if (!seen.add(it)) shared += it } }
        }
        fun key(n: ResolvedNode) = n.ref?.takeIf { it !in shared } ?: n.loc
        val byKey = HashMap<String, ResolvedNode>()
        a.root.walk { byKey[key(it)] = it }
        val p = progress
        fun build(bn: ResolvedNode, parentWorld: Matrix): ResolvedNode? {
            val an = byKey[key(bn)] ?: return null
            val transform = matrix(an.transform, bn.transform, p)
            val world = parentWorld * transform
            val local = box(an.local, bn.local, p)
            return ResolvedNode(
                ref = bn.ref,
                loc = bn.loc,
                kind = bn.kind,
                layer = bn.layer,
                local = local,
                transform = transform,
                world = world,
                box = world.apply(local),
                opacity = lerp(an.opacity, bn.opacity, p),
                paint = Paint(color(an.paint.fill, bn.paint.fill, p), color(an.paint.stroke, bn.paint.stroke, p), lerp(an.paint.strokeWidth, bn.paint.strokeWidth, p)),
                geometry = geometry(an.geometry, bn.geometry, p),
                children = bn.children.mapNotNull { build(it, world) },
                template = bn.template,
                draw = lerp(an.draw, bn.draw, p),
                route = route(an.route, bn.route, p),
                routeLabel = if (an.routeLabel != null && bn.routeLabel != null) point(an.routeLabel, bn.routeLabel, p) else null,
            )
        }
        val root = build(b.root, Matrix.IDENTITY) ?: b.root
        return ResolvedScene(b.format, b.width, b.height, b.time, root, color(a.background, b.background, p), b.states, Cameras.interpolate(a.camera, b.camera, p, b.width, b.height), b.layers)
    }

    private fun lerp(x: Double, y: Double, t: Double) = x + (y - x) * t

    /**
     * Two supplied routes interpolate along their length (graph layouts addendum, section 9). With
     * the same number of points they interpolate point by point. Otherwise each gets the other's
     * points, placed at the same fraction of its own length, so that the frame at 0 is exactly the
     * first route and the frame at 1 exactly the second. A route on one side only is not interpolated:
     * the connector is routed again on every frame.
     */
    internal fun route(x: List<Point>?, y: List<Point>?, t: Double): List<Point>? {
        if (x == null || y == null || x.isEmpty() || y.isEmpty()) return null
        if (x.size == y.size) return x.zip(y) { p, q -> point(p, q, t) }
        val fx = fractions(x)
        val fy = fractions(y)
        // both lists are sorted: merge them, keeping one of two equal fractions
        val all = ArrayList<Double>()
        var i = 0
        var j = 0
        while (i < fx.size || j < fy.size) {
            val f = if (j >= fy.size || (i < fx.size && fx[i] <= fy[j])) fx[i++] else fy[j++]
            if (all.isEmpty() || f - all.last() > 1e-9) all += f
        }
        return all.map { f -> point(at(x, fx, f), at(y, fy, f), t) }
    }

    /** The fraction of the polyline's length at each of its points. */
    private fun fractions(pts: List<Point>): DoubleArray {
        val out = DoubleArray(pts.size)
        for (i in 1 until pts.size) out[i] = out[i - 1] + sqrt((pts[i].x - pts[i - 1].x) * (pts[i].x - pts[i - 1].x) + (pts[i].y - pts[i - 1].y) * (pts[i].y - pts[i - 1].y))
        val total = out[pts.size - 1]
        for (i in out.indices) out[i] = if (total == 0.0) i.toDouble() / maxOf(pts.size - 1, 1) else out[i] / total
        return out
    }

    private fun at(pts: List<Point>, fractions: DoubleArray, f: Double): Point {
        for (i in 1 until pts.size) {
            if (f <= fractions[i] || i == pts.size - 1) {
                val span = fractions[i] - fractions[i - 1]
                val u = if (span <= 0.0) 0.0 else ((f - fractions[i - 1]) / span).coerceIn(0.0, 1.0)
                return point(pts[i - 1], pts[i], u)
            }
        }
        return pts.first()
    }
    private fun point(x: Point, y: Point, t: Double) = Point(lerp(x.x, y.x, t), lerp(x.y, y.y, t))
    private fun box(x: Box, y: Box, t: Double) = Box(lerp(x.x, y.x, t), lerp(x.y, y.y, t), lerp(x.width, y.width, t), lerp(x.height, y.height, t))

    /** Translation, rotation and scale interpolate separately, rotation the short way round. */
    private fun matrix(x: Matrix, y: Matrix, t: Double): Matrix {
        if (x == y) return x
        fun parts(m: Matrix): DoubleArray {
            val sx = sqrt(m.a * m.a + m.b * m.b)
            val rot = atan2(m.b, m.a)
            val sy = if (sx == 0.0) 0.0 else (m.a * m.d - m.b * m.c) / sx
            return doubleArrayOf(sx, sy, rot, m.e, m.f)
        }
        val p = parts(x)
        val q = parts(y)
        var dr = q[2] - p[2]
        while (dr > PI) dr -= 2 * PI
        while (dr < -PI) dr += 2 * PI
        val sx = lerp(p[0], q[0], t)
        val sy = lerp(p[1], q[1], t)
        val r = p[2] + dr * t
        return Matrix(sx * cos(r), sx * sin(r), -sy * sin(r), sy * cos(r), lerp(p[3], q[3], t), lerp(p[4], q[4], t))
    }

    /** sRGB per channel, alpha included. A color on one side only switches at the end. */
    internal fun color(x: String?, y: String?, t: Double): String? {
        if (x == null || y == null || x == y) return x ?: if (t >= 1) y else null
        fun ch(c: String) = intArrayOf(c.substring(1, 3).toInt(16), c.substring(3, 5).toInt(16), c.substring(5, 7).toInt(16), if (c.length == 9) c.substring(7, 9).toInt(16) else 255)
        val a = ch(x)
        val b = ch(y)
        val out = IntArray(4) { i -> lerp(a[i].toDouble(), b[i].toDouble(), t).roundToInt().coerceIn(0, 255) }
        fun hex(i: Int) = out[i].toString(16).padStart(2, '0')
        return "#" + hex(0) + hex(1) + hex(2) + if (out[3] != 255) hex(3) else ""
    }

    private fun geometry(x: Geometry, y: Geometry, t: Double): Geometry = when {
        x is Geometry.Rect && y is Geometry.Rect -> Geometry.Rect(lerp(x.width, y.width, t), lerp(x.height, y.height, t), lerp(x.cornerRadius, y.cornerRadius, t))
        x is Geometry.Ellipse && y is Geometry.Ellipse -> Geometry.Ellipse(lerp(x.cx, y.cx, t), lerp(x.cy, y.cy, t), lerp(x.rx, y.rx, t), lerp(x.ry, y.ry, t))
        x is Geometry.Poly && y is Geometry.Poly && x.points.size == y.points.size -> Geometry.Poly(
            x.points.zip(y.points) { p, q -> point(p, q, t) },
            y.closed,
            if (x.markers.size == y.markers.size && x.markers.zip(y.markers).all { (m, n) -> m.kind == n.kind && m.points.size == n.points.size }) {
                x.markers.zip(y.markers) { m, n -> Marker(n.kind, m.points.zip(n.points) { p, q -> point(p, q, t) }, n.closed, n.filled) }
            } else {
                x.markers
            },
        )
        x is Geometry.PathGeom && y is Geometry.PathGeom && x.segments.size == y.segments.size && x.segments.zip(y.segments).all { (s, u) -> s::class == u::class } ->
            Geometry.PathGeom(x.segments.zip(y.segments) { s, u -> segment(s, u, t) })
        x is Geometry.Text && y is Geometry.Text && x.lines.map { it.text } == y.lines.map { it.text } && x.font == y.font -> Geometry.Text(
            x.lines.zip(y.lines) { l, m -> TextLine(m.text, lerp(l.x, m.x, t), lerp(l.baseline, m.baseline, t), lerp(l.width, m.width, t)) },
            lerp(x.fontSize, y.fontSize, t),
            y.font,
        )
        else -> x
    }

    private fun segment(s: Segment, u: Segment, t: Double): Segment = when {
        s is Segment.Move && u is Segment.Move -> Segment.Move(point(s.p, u.p, t))
        s is Segment.Line && u is Segment.Line -> Segment.Line(point(s.p, u.p, t))
        s is Segment.Cubic && u is Segment.Cubic -> Segment.Cubic(point(s.c1, u.c1, t), point(s.c2, u.c2, t), point(s.p, u.p, t))
        s is Segment.Quad && u is Segment.Quad -> Segment.Quad(point(s.c, u.c, t), point(s.p, u.p, t))
        else -> s
    }
}

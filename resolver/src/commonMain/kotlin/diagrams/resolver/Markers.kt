package diagrams.resolver

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A connector end marker, resolved to plain geometry in the connector's frame: a polygon (closed),
 * or an open polyline. Filled markers take the stroke color.
 */
data class Marker(val kind: String, val points: List<Point>, val closed: Boolean, val filled: Boolean)

/**
 * Arrowheads and other end markers (`marker_start`, `marker_end` on a connector). Their size follows
 * the stroke width. The route is shortened so the line stops where a closed marker begins and does
 * not show through a hollow one.
 */
object Markers {
    val KINDS = setOf("none", "arrow", "open_arrow", "triangle", "diamond", "open_diamond", "circle", "open_circle")

    class Result(val points: List<Point>, val markers: List<Marker>)

    fun apply(points: List<Point>, start: String?, end: String?, strokeWidth: Double): Result {
        if (points.size < 2) return Result(points, emptyList())
        val pts = points.toMutableList()
        val out = mutableListOf<Marker>()
        val len = 6 + 3 * strokeWidth
        fun shown(kind: String?) = kind != null && kind != "none"
        // two markers on one segment share it: each may take half, so that neither swallows the other
        val share = if (points.size == 2 && shown(start) && shown(end)) 0.5 else 1.0
        fun one(kind: String?, tip: Point, from: Point, setTip: (Point) -> Unit) {
            if (kind == null || kind == "none") return
            val dx = tip.x - from.x
            val dy = tip.y - from.y
            val l = sqrt(dx * dx + dy * dy)
            if (l < 1e-9) return
            val ux = dx / l
            val uy = dy / l
            // a point at distance a back along the line and b to its left
            fun at(a: Double, b: Double) = Point(tip.x - ux * a - uy * b, tip.y - uy * a + ux * b)
            val (m, back) = when (kind) {
                "arrow" -> Marker(kind, listOf(tip, at(len, len * 0.4), at(len, -len * 0.4)), closed = true, filled = true) to len
                "open_arrow" -> Marker(kind, listOf(at(len, len * 0.45), tip, at(len, -len * 0.45)), closed = false, filled = false) to 0.0
                "triangle" -> Marker(kind, listOf(tip, at(len * 1.2, len * 0.55), at(len * 1.2, -len * 0.55)), closed = true, filled = false) to len * 1.2
                "diamond", "open_diamond" -> Marker(
                    kind,
                    listOf(tip, at(len * 0.8, len * 0.4), at(len * 1.6, 0.0), at(len * 0.8, -len * 0.4)),
                    closed = true,
                    filled = kind == "diamond",
                ) to len * 1.6
                else -> { // circle, open_circle
                    val r = len * 0.4
                    val c = at(r, 0.0)
                    Marker(kind, (0 until 24).map { i -> Point(c.x + r * cos(2 * PI * i / 24), c.y + r * sin(2 * PI * i / 24)) }, closed = true, filled = kind == "circle") to 2 * r
                }
            }
            out += m
            if (back > 0) setTip(at(minOf(back, l * share), 0.0))
        }
        // both read the route as given: shortening one end must not change the other's direction or length
        one(end, points.last(), points[points.size - 2]) { pts[pts.size - 1] = it }
        one(start, points.first(), points[1]) { pts[0] = it }
        return Result(pts, out)
    }
}

package diagrams.resolver

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

data class Point(val x: Double, val y: Double)

data class Box(val x: Double, val y: Double, val width: Double, val height: Double) {
    val right get() = x + width
    val bottom get() = y + height
    val centerX get() = x + width / 2
    val centerY get() = y + height / 2

    fun union(o: Box): Box {
        val l = min(x, o.x)
        val t = min(y, o.y)
        return Box(l, t, max(right, o.right) - l, max(bottom, o.bottom) - t)
    }

    fun intersects(o: Box) = x < o.right && o.x < right && y < o.bottom && o.y < bottom

    fun contains(o: Box, eps: Double = 1e-9) =
        o.x >= x - eps && o.y >= y - eps && o.right <= right + eps && o.bottom <= bottom + eps

    fun corners() = listOf(Point(x, y), Point(right, y), Point(right, bottom), Point(x, bottom))

    companion object {
        val EMPTY = Box(0.0, 0.0, 0.0, 0.0)

        fun of(points: List<Point>): Box {
            if (points.isEmpty()) return EMPTY
            val l = points.minOf { it.x }
            val t = points.minOf { it.y }
            return Box(l, t, points.maxOf { it.x } - l, points.maxOf { it.y } - t)
        }
    }
}

/** Affine transform in SVG order: x' = a*x + c*y + e, y' = b*x + d*y + f. */
data class Matrix(val a: Double, val b: Double, val c: Double, val d: Double, val e: Double, val f: Double) {
    operator fun times(o: Matrix) = Matrix(
        a * o.a + c * o.b,
        b * o.a + d * o.b,
        a * o.c + c * o.d,
        b * o.c + d * o.d,
        a * o.e + c * o.f + e,
        b * o.e + d * o.f + f,
    )

    fun apply(p: Point) = Point(a * p.x + c * p.y + e, b * p.x + d * p.y + f)

    fun apply(box: Box): Box = Box.of(box.corners().map { apply(it) })

    /** Applies only the linear part: for directions and displacements. */
    fun applyVector(p: Point) = Point(a * p.x + c * p.y, b * p.x + d * p.y)

    fun inverse(): Matrix {
        val det = a * d - b * c
        if (det == 0.0) return IDENTITY
        val ia = d / det
        val ib = -b / det
        val ic = -c / det
        val id = a / det
        return Matrix(ia, ib, ic, id, -(ia * e + ic * f), -(ib * e + id * f))
    }

    val isIdentity get() = this == IDENTITY

    /** Uniform scale factor (geometric mean of the axes), used for sizes in lint. */
    val scaleFactor: Double get() = kotlin.math.sqrt(kotlin.math.abs(a * d - b * c))

    fun toList() = listOf(a, b, c, d, e, f)

    companion object {
        val IDENTITY = Matrix(1.0, 0.0, 0.0, 1.0, 0.0, 0.0)
        fun translate(x: Double, y: Double) = Matrix(1.0, 0.0, 0.0, 1.0, x, y)
        fun scale(s: Double) = Matrix(s, 0.0, 0.0, s, 0.0, 0.0)

        /** Degrees, positive clockwise with y pointing down (spec section 4). */
        fun rotate(deg: Double): Matrix {
            val r = deg * PI / 180.0
            val cs = cos(r)
            val sn = sin(r)
            return Matrix(cs, sn, -sn, cs, 0.0, 0.0)
        }
    }
}

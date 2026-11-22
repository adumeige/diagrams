package diagrams.resolver

/**
 * SVG path data reduced to absolute M, L, C, Q and Z segments, so renderers need no path parser.
 * Supported input commands: M L H V C S Q T Z, absolute and relative. Arcs (A) are not supported yet.
 */
sealed interface Segment {
    data class Move(val p: Point) : Segment
    data class Line(val p: Point) : Segment
    data class Cubic(val c1: Point, val c2: Point, val p: Point) : Segment
    data class Quad(val c: Point, val p: Point) : Segment
    data object Close : Segment
}

class PathDataException(message: String) : Exception(message)

object PathData {
    fun parse(d: String): List<Segment> {
        val toks = tokenize(d)
        var i = 0
        val out = mutableListOf<Segment>()
        var cur = Point(0.0, 0.0)
        var start = cur
        var lastCtrl: Point? = null
        var lastCmd = ' '
        var cmd = ' '

        fun num(): Double {
            val t = toks.getOrNull(i) ?: throw PathDataException("command '$cmd' is missing a number")
            if (t !is Double) throw PathDataException("command '$cmd' expects a number, found '$t'")
            i++
            return t
        }

        fun pt(rel: Boolean): Point {
            val x = num()
            val y = num()
            return if (rel) Point(cur.x + x, cur.y + y) else Point(x, y)
        }

        while (i < toks.size) {
            val t = toks[i]
            if (t is Char) {
                cmd = t
                i++
            } else if (cmd == ' ') {
                throw PathDataException("path data must start with a command")
            } else if (cmd == 'M') {
                cmd = 'L'
            } else if (cmd == 'm') {
                cmd = 'l'
            } else if (cmd == 'Z' || cmd == 'z') {
                throw PathDataException("unexpected number after 'Z'")
            }
            val rel = cmd.isLowerCase()
            when (cmd.uppercaseChar()) {
                'M' -> {
                    cur = pt(rel)
                    start = cur
                    out += Segment.Move(cur)
                    lastCtrl = null
                }
                'L' -> {
                    cur = pt(rel)
                    out += Segment.Line(cur)
                    lastCtrl = null
                }
                'H' -> {
                    val x = num()
                    cur = Point(if (rel) cur.x + x else x, cur.y)
                    out += Segment.Line(cur)
                    lastCtrl = null
                }
                'V' -> {
                    val y = num()
                    cur = Point(cur.x, if (rel) cur.y + y else y)
                    out += Segment.Line(cur)
                    lastCtrl = null
                }
                'C' -> {
                    val c1 = pt(rel)
                    val c2 = pt(rel)
                    val p = pt(rel)
                    out += Segment.Cubic(c1, c2, p)
                    lastCtrl = c2
                    cur = p
                }
                'S' -> {
                    val c1 = if (lastCmd.uppercaseChar() in "CS" && lastCtrl != null) Point(2 * cur.x - lastCtrl.x, 2 * cur.y - lastCtrl.y) else cur
                    val c2 = pt(rel)
                    val p = pt(rel)
                    out += Segment.Cubic(c1, c2, p)
                    lastCtrl = c2
                    cur = p
                }
                'Q' -> {
                    val c = pt(rel)
                    val p = pt(rel)
                    out += Segment.Quad(c, p)
                    lastCtrl = c
                    cur = p
                }
                'T' -> {
                    val c = if (lastCmd.uppercaseChar() in "QT" && lastCtrl != null) Point(2 * cur.x - lastCtrl.x, 2 * cur.y - lastCtrl.y) else cur
                    val p = pt(rel)
                    out += Segment.Quad(c, p)
                    lastCtrl = c
                    cur = p
                }
                'Z' -> {
                    out += Segment.Close
                    cur = start
                    lastCtrl = null
                }
                'A' -> throw PathDataException("arc commands (A) are not supported yet; use C or Q curves")
                else -> throw PathDataException("unknown path command '$cmd'")
            }
            lastCmd = cmd
        }
        if (out.isNotEmpty() && out.first() !is Segment.Move) throw PathDataException("path data must start with M")
        return out
    }

    /** Bounding box of all points, control points included (a conservative box). */
    fun bounds(segments: List<Segment>): Box = Box.of(
        segments.flatMap {
            when (it) {
                is Segment.Move -> listOf(it.p)
                is Segment.Line -> listOf(it.p)
                is Segment.Cubic -> listOf(it.c1, it.c2, it.p)
                is Segment.Quad -> listOf(it.c, it.p)
                Segment.Close -> emptyList()
            }
        },
    )

    private fun tokenize(d: String): List<Any> {
        val out = mutableListOf<Any>()
        var p = 0
        val numRe = Regex("[-+]?(\\d+\\.?\\d*|\\.\\d+)([eE][-+]?\\d+)?")
        while (p < d.length) {
            val c = d[p]
            when {
                c.isWhitespace() || c == ',' -> p++
                c.isLetter() && c != 'e' && c != 'E' -> {
                    out += c
                    p++
                }
                else -> {
                    val m = numRe.matchAt(d, p) ?: throw PathDataException("unexpected '$c' in path data at offset $p")
                    out += m.value.toDouble()
                    p += m.value.length
                }
            }
        }
        return out
    }
}

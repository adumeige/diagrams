package diagrams.resolver

import kotlin.math.max
import kotlin.math.min

/**
 * The `sequence` layout (docs/sequence-layout.md): participants side by side, one row per message in
 * declaration order, fragments and operands around the rows they hold, activations on the lines.
 * Pure geometry over sizes and indices; the engine maps nodes in and boxes out. Coordinates are in
 * the container's frame, inside its padding.
 */
internal object SequenceLayout {
    class Participant(val w: Double, val h: Double, val created: Boolean, val destroyed: Boolean)

    sealed interface Item

    /** A message between two participants (indices; -1 when an end is not a participant). */
    class Message(val from: Int, val to: Int, val labelW: Double, val labelH: Double, val parallel: Boolean) : Item

    /** A fragment or an operand opens: [node] indexes the result's boxes. */
    class Open(val node: Int, val operand: Boolean, val covers: List<Int>) : Item

    class Close(val node: Int) : Item

    /** An interaction use: a row of its own across [covers]. */
    class Reference(val node: Int, val w: Double, val h: Double, val covers: List<Int>) : Item

    /** A bar on participant [on] from message [from] to message [to] (message indices, in order). */
    class Activation(val node: Int, val on: Int, val from: Int, val to: Int)

    class Options(val gap: Double = 60.0, val rowGap: Double = 14.0, val fragmentPadding: Double = 16.0)

    class Result(
        val participants: List<Box>,
        /** Boxes of fragments, operands, references and activations, by node index. */
        val nodes: Map<Int, Box>,
        /** Per message: its route and where its label goes (the label's center). */
        val routes: List<List<Point>>,
        val labels: List<Point?>,
        val width: Double,
        val height: Double,
        val warnings: List<GWarning>,
    )

    private const val HEADER = 26.0
    private const val GUARD = 20.0
    private const val BAR = 12.0
    private const val LOOP = 30.0
    private const val LABEL_MARGIN = 16.0

    fun layout(ps: List<Participant>, items: List<Item>, activations: List<Activation>, o: Options): Result {
        val warnings = mutableListOf<GWarning>()
        val messages = items.filterIsInstance<Message>()
        val n = ps.size

        // ---- horizontal: centers, then room for the labels between two lines
        val cx = DoubleArray(n)
        for (i in 0 until n) cx[i] = if (i == 0) ps[0].w / 2 else cx[i - 1] + ps[i - 1].w / 2 + o.gap + ps[i].w / 2
        fun push(from: Int, by: Double) {
            if (by <= 0) return
            for (j in from until n) cx[j] += by
        }
        for (m in messages) {
            if (m.from < 0 || m.to < 0) continue
            if (m.from == m.to) {
                if (m.from + 1 < n) push(m.from + 1, BAR / 2 + LOOP + 8 + m.labelW + LABEL_MARGIN + ps[m.from + 1].w / 2 - (cx[m.from + 1] - cx[m.from]))
            } else {
                val lo = min(m.from, m.to)
                val hi = max(m.from, m.to)
                push(hi, m.labelW + 2 * LABEL_MARGIN - (cx[hi] - cx[lo]))
            }
        }

        // ---- vertical: rows in order
        val headH = ps.filterNot { it.created }.maxOfOrNull { it.h } ?: ps.maxOfOrNull { it.h } ?: 0.0
        val top = DoubleArray(n) { if (ps[it].created) -1.0 else headH - ps[it].h }
        val bottom = DoubleArray(n) { -1.0 }
        val lineY = DoubleArray(messages.size)
        val nodes = LinkedHashMap<Int, Box>()
        // per open group: its node, its top, whether it is an operand, the participants its messages touch
        class Group(val node: Int, val operand: Boolean, val top: Double, val covers: MutableSet<Int>, var depth: Int = 0, var reach: Double = 0.0)
        val open = ArrayDeque<Group>()
        val closed = mutableListOf<Pair<Group, Double>>()
        val fragmentOf = HashMap<Int, Int>() // operand -> its fragment
        var y = headH + 18
        var k = 0
        var last = -1.0
        for (it in items) {
            when (it) {
                is Message -> {
                    val created = it.to >= 0 && ps[it.to].created && top[it.to] < 0
                    val line = if (it.parallel && last >= 0) last else {
                        var l = y + it.labelH + 4
                        if (created) l = max(l, y + ps[it.to].h / 2 + 2)
                        l
                    }
                    lineY[k] = line
                    last = line
                    if (!it.parallel || k == 0) {
                        y = line + o.rowGap
                        if (it.from >= 0 && it.from == it.to) y += 18
                        if (created) y = max(y, line + ps[it.to].h / 2 + o.rowGap)
                    }
                    if (created) top[it.to] = line - ps[it.to].h / 2
                    if (it.to >= 0 && ps[it.to].destroyed) bottom[it.to] = line + 8
                    for (g in open) {
                        if (it.from >= 0) g.covers += it.from
                        if (it.to >= 0) g.covers += it.to
                        // a self message's loop and label stand out right of its line
                        if (it.from >= 0 && it.from == it.to) g.reach = max(g.reach, cx[it.from] + BAR / 2 + LOOP + 6 + it.labelW)
                    }
                    k++
                }
                is Open -> {
                    val g = Group(it.node, it.operand, y, it.covers.toMutableSet())
                    if (it.operand) open.lastOrNull { !it.operand }?.let { f -> fragmentOf[it.node] = f.node }
                    if (!it.operand) {
                        // an enclosing fragment pads once more for every fragment nested in it
                        val frags = open.filter { f -> !f.operand }
                        frags.forEachIndexed { i, f -> f.depth = max(f.depth, frags.size - i) }
                    }
                    open.addLast(g)
                    y += if (it.operand) GUARD else HEADER
                }
                is Close -> {
                    val g = open.removeLastOrNull() ?: continue
                    if (!g.operand) y += 6
                    closed += g to y
                    open.lastOrNull()?.let { outer ->
                        outer.covers.addAll(g.covers)
                        outer.reach = max(outer.reach, g.reach)
                    }
                    if (!g.operand) y += 10
                }
                is Reference -> {
                    nodes[it.node] = Box(0.0, y, it.w, it.h)
                    for (g in open) g.covers += it.covers
                    y += it.h + o.rowGap
                }
            }
        }
        val end = y + 10
        for (i in 0 until n) {
            if (top[i] < 0) top[i] = headH - ps[i].h
            if (bottom[i] < 0) bottom[i] = end
        }

        // ---- activations: bars on the lines, nested ones a half bar to the right
        val bars = mutableListOf<Triple<Int, Int, Box>>() // participant, node, box
        for (a in activations) {
            if (a.on !in 0 until n || a.from !in lineY.indices || a.to !in lineY.indices) continue
            val y0 = lineY[a.from] - 8
            val y1 = lineY[a.to] + 8
            val nest = bars.count { (p, _, b) -> p == a.on && b.y < y1 && b.bottom > y0 }
            val box = Box(cx[a.on] - BAR / 2 + nest * BAR / 2, y0, BAR, y1 - y0)
            bars += Triple(a.on, a.node, box)
            nodes[a.node] = box
        }
        /** Where a message meets participant [p] at height [yy], coming from direction [toward] (+1 right). */
        fun edge(p: Int, yy: Double, toward: Double): Double {
            val bar = bars.filter { (q, _, b) -> q == p && yy >= b.y - 1e-9 && yy <= b.bottom + 1e-9 }.maxByOrNull { it.third.x }
            return if (bar == null) cx[p] else if (toward > 0) bar.third.right else bar.third.x
        }

        // ---- routes and labels
        val routes = ArrayList<List<Point>>()
        val labels = ArrayList<Point?>()
        k = 0
        for (m in messages) {
            val ly = lineY[k++]
            if (m.from < 0 || m.to < 0) {
                routes += emptyList<Point>()
                labels += null
                continue
            }
            if (m.from == m.to) {
                val x0 = edge(m.from, ly, 1.0)
                val x1 = edge(m.from, ly + 18, 1.0)
                val far = max(x0, x1) + LOOP
                routes += listOf(Point(x0, ly), Point(far, ly), Point(far, ly + 18), Point(x1, ly + 18))
                labels += Point(far + 6 + m.labelW / 2, ly + 9)
                continue
            }
            val dir = if (cx[m.to] > cx[m.from]) 1.0 else -1.0
            val x0 = edge(m.from, ly, dir)
            val createdHere = ps[m.to].created && kotlin.math.abs(top[m.to] + ps[m.to].h / 2 - ly) < 1e-6
            val x1 = if (createdHere) cx[m.to] - dir * ps[m.to].w / 2 else edge(m.to, ly, -dir)
            routes += listOf(Point(x0, ly), Point(x1, ly))
            labels += Point((x0 + x1) / 2, ly - 4 - m.labelH / 2)
        }

        // ---- fragments and operands: around their rows and the lines their messages touch
        for ((g, bottomY) in closed) {
            if (g.operand) continue
            val covers = g.covers.filter { it in 0 until n }
            val pad = o.fragmentPadding * (1 + g.depth)
            val left = (covers.minOfOrNull { cx[it] } ?: 0.0) - pad - BAR / 2
            val right = max((covers.maxOfOrNull { cx[it] } ?: 0.0) + BAR / 2, g.reach) + pad
            if (covers.isEmpty()) warnings += GWarning("fragment_empty", g.node, null, "the fragment holds no message between participants")
            nodes[g.node] = Box(left, g.top, right - left, bottomY - g.top)
        }
        // an operand runs from its own top to the next operand's, or to its fragment's bottom
        val operands = closed.filter { it.first.operand }.sortedBy { it.first.top }
        for ((g, _) in operands) {
            val f = fragmentOf[g.node]?.let { nodes[it] } ?: continue
            val next = operands.map { it.first }.filter { fragmentOf[it.node] == fragmentOf[g.node] && it.top > g.top }.minOfOrNull { it.top }
            val b = next ?: f.bottom
            nodes[g.node] = Box(f.x, g.top, f.width, b - g.top)
        }
        // references span the lines they cover
        for (it in items.filterIsInstance<Reference>()) {
            val covers = it.covers.filter { c -> c in 0 until n }
            val b = nodes[it.node] ?: continue
            val left = (covers.minOfOrNull { c -> cx[c] } ?: cx.firstOrNull() ?: 0.0) - o.fragmentPadding
            val right = (covers.maxOfOrNull { c -> cx[c] } ?: cx.lastOrNull() ?: 0.0) + o.fragmentPadding
            nodes[it.node] = Box(left, b.y, max(right - left, b.width), b.height)
        }

        // ---- everything to the right of zero
        var participants = (0 until n).map { Box(cx[it] - ps[it].w / 2, top[it], ps[it].w, bottom[it] - top[it]) }
        val minX = min(participants.minOfOrNull { it.x } ?: 0.0, nodes.values.minOfOrNull { it.x } ?: 0.0)
        val shift = -minX
        participants = participants.map { Box(it.x + shift, it.y, it.width, it.height) }
        val shifted = nodes.mapValues { (_, b) -> Box(b.x + shift, b.y, b.width, b.height) }
        val shiftedRoutes = routes.map { r -> r.map { Point(it.x + shift, it.y) } }
        val shiftedLabels = labels.map { p -> p?.let { Point(it.x + shift, it.y) } }
        val width = max(participants.maxOfOrNull { it.right } ?: 0.0, shifted.values.maxOfOrNull { it.right } ?: 0.0)
        return Result(participants, shifted, shiftedRoutes, shiftedLabels, width, end, warnings)
    }
}

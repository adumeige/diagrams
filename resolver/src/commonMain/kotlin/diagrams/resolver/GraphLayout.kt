package diagrams.resolver

import diagrams.model.Codes
import kotlin.math.sqrt

/*
 * The graph layout kinds (graph layouts addendum): layered, tree, circular and force.
 *
 * Each is a pure function of its input: child sizes, the edges among the children, options and
 * hints in; child positions, routes and label positions out, in the container's content frame.
 *
 * Determinism rules (addendum, section 8). Everything here is common code and uses no platform
 * library. Ties are broken by declaration order: nodes and edges are indexed in declaration order
 * and no unordered collection is iterated. Arithmetic is add, subtract, multiply, divide and square
 * root, which are correctly rounded on every target; angles go through [Trig].
 */

/** A hint on the rank of a child of a layered container. */
internal sealed class RankHint {
    object First : RankHint()
    object Last : RankHint()
    class SameAs(val node: Int) : RankHint()
}

/** A child: its size, and the hints its container's kind declares. [pinned] is the top-left corner. */
internal class GNode(val w: Double, val h: Double, val rank: RankHint? = null, val pinned: Point? = null)

/**
 * An edge between two children, by index. A label size of zero reserves no room. [follows] is
 * whether the connector takes the route the layout supplies (`route: layout`).
 */
internal class GEdge(val from: Int, val to: Int, val constrain: Boolean = true, val labelW: Double = 0.0, val labelH: Double = 0.0, val follows: Boolean = true) {
    val hasLabel: Boolean get() = labelW > 0.0 || labelH > 0.0
}

/**
 * A supplied route, from the center of the source child to the center of the target child. The
 * engine moves both ends to the outlines. An [exact] route is used as it is (a self-loop).
 */
internal class GRoute(val points: List<Point>, val exact: Boolean = false)

internal class GWarning(val code: String, val node: Int?, val edge: Int?, val message: String)

internal class GResult(
    /** Top-left corner of each child's box. */
    val positions: List<Point>,
    /** Per edge: the route, or null when the kind supplies none for it. */
    val routes: List<GRoute?>,
    /** Per edge: the center of the room reserved for its label, or null. */
    val labels: List<Point?>,
    val width: Double,
    val height: Double,
    val warnings: List<GWarning>,
) {
    companion object {
        fun empty(edges: Int) = GResult(emptyList(), List(edges) { null }, List(edges) { null }, 0.0, 0.0, emptyList())
    }
}

/**
 * Edges between the same two nodes, in a kind that supplies no route of its own (circular, force):
 * each one bows apart from the line between the two centers, so that none hides another (graph
 * layouts addendum, section 2). The bow is the one `layered` uses, measured across the line, and
 * its side does not depend on which way each edge points. Only edges that follow the supplied
 * route count: one drawn `straight` or `orthogonal` keeps its own line and leaves no gap. An edge
 * that is alone between its two nodes, and a self-loop, get no route.
 */
internal object Twins {
    const val BOW = 18.0

    fun routes(centers: List<Point>, edges: List<GEdge>): List<GRoute?> {
        val n = centers.size
        val routes = arrayOfNulls<GRoute>(edges.size)
        val groups = LinkedHashMap<Long, MutableList<Int>>()
        for ((ei, e) in edges.withIndex()) {
            if (e.from == e.to || !e.follows) continue
            groups.getOrPut(minOf(e.from, e.to).toLong() * n + maxOf(e.from, e.to)) { mutableListOf() } += ei
        }
        for (group in groups.values) {
            if (group.size < 2) continue
            val first = edges[group[0]]
            val low = centers[minOf(first.from, first.to)]
            val high = centers[maxOf(first.from, first.to)]
            val dx = high.x - low.x
            val dy = high.y - low.y
            val len = sqrt(dx * dx + dy * dy)
            // two nodes on the same center have no line to bow across: leave them straight
            if (len < 1e-9) continue
            val nx = -dy / len
            val ny = dx / len
            val mid = Point((low.x + high.x) / 2, (low.y + high.y) / 2)
            for ((i, ei) in group.withIndex()) {
                val shift = (i - (group.size - 1) / 2.0) * BOW
                val e = edges[ei]
                routes[ei] = GRoute(listOf(centers[e.from], Point(mid.x + nx * shift, mid.y + ny * shift), centers[e.to]))
            }
        }
        return routes.toList()
    }
}

/**
 * Ranks that follow edge direction (Sugiyama): cycle removal, ranking, ordering within ranks,
 * coordinates. Long edges run through dummy vertices, which is what gives them their route; a
 * labeled edge has one dummy as wide as its label, which is what reserves the label's room.
 */
internal object LayeredLayout {
    class Options(val direction: String, val gap: Double, val rankGap: Double, val declared: Boolean)

    private const val SWEEPS = 24
    private const val TRANSPOSITIONS = 8
    private const val RELAXATIONS = 12
    private const val LABEL_PAD = 10.0
    private const val LOOP = 22.0
    private const val BOW = Twins.BOW

    fun layout(nodes: List<GNode>, edges: List<GEdge>, opt: Options): GResult {
        val n = nodes.size
        if (n == 0) return GResult.empty(edges.size)
        val warnings = mutableListOf<GWarning>()
        // the order axis `o` runs along a rank, the rank axis `r` across ranks
        val horizontal = opt.direction == "right" || opt.direction == "left"
        val oSize = DoubleArray(n) { if (horizontal) nodes[it].h else nodes[it].w }
        val rSize = DoubleArray(n) { if (horizontal) nodes[it].w else nodes[it].h }
        val selfLoop = BooleanArray(n)
        for (e in edges) if (e.from == e.to) selfLoop[e.from] = true

        // 1. cycle removal: a depth-first walk in declaration order reverses the back edges
        val cons = edges.indices.filter { edges[it].constrain && edges[it].from != edges[it].to }
        val reversed = BooleanArray(edges.size)
        run {
            val out = Array(n) { mutableListOf<Int>() }
            for (e in cons) out[edges[e].from] += e
            val state = IntArray(n)
            fun visit(v: Int) {
                state[v] = 1
                for (e in out[v]) {
                    val w = edges[e].to
                    if (state[w] == 1) reversed[e] = true else if (state[w] == 0) visit(w)
                }
                state[v] = 2
            }
            for (v in 0 until n) if (state[v] == 0) visit(v)
        }
        fun src(e: Int) = if (reversed[e]) edges[e].to else edges[e].from
        fun dst(e: Int) = if (reversed[e]) edges[e].from else edges[e].to

        // 2. `same_as` hints merge nodes into rank groups, unless the edges forbid it
        val group = IntArray(n) { it }
        fun find(v: Int): Int {
            var x = v
            while (group[x] != x) x = group[x]
            return x
        }
        fun acyclic(): Boolean {
            val g = IntArray(n) { find(it) }
            val indeg = IntArray(n)
            val out = Array(n) { mutableListOf<Int>() }
            for (e in cons) {
                val a = g[src(e)]
                val b = g[dst(e)]
                // an edge puts one node below the other: they cannot share a rank
                if (a == b) return false
                out[a] += b
                indeg[b]++
            }
            val reps = (0 until n).filter { g[it] == it }
            val queue = ArrayDeque(reps.filter { indeg[it] == 0 })
            var seen = 0
            while (queue.isNotEmpty()) {
                val v = queue.removeFirst()
                seen++
                for (w in out[v]) if (--indeg[w] == 0) queue += w
            }
            return seen == reps.size
        }
        for (v in 0 until n) {
            val h = nodes[v].rank as? RankHint.SameAs ?: continue
            if (h.node !in 0 until n || h.node == v) continue
            val a = find(v)
            val b = find(h.node)
            if (a == b) continue
            val hi = maxOf(a, b)
            group[hi] = minOf(a, b)
            if (!acyclic()) {
                group[hi] = hi
                warnings += GWarning(Codes.HINT_CONFLICT, v, null, "'rank: same_as' contradicts the edges: one of the two nodes is below the other; the hint is ignored")
            }
        }
        val g = IntArray(n) { find(it) }

        // 3. ranks: the longest path from the sources, then the hints. With a labeled edge every
        // edge spans two ranks: the nodes are on the even ranks, and an edge between two
        // neighbouring ranks of nodes has a rank in the middle for its label.
        val doubled = edges.any { it.hasLabel && it.from != it.to }
        val len = if (doubled) 2 else 1
        val ins = Array(n) { mutableListOf<Int>() }
        val outs = Array(n) { mutableListOf<Int>() }
        for (e in cons) {
            val a = g[src(e)]
            val b = g[dst(e)]
            if (a != b) {
                outs[a] += b
                ins[b] += a
            }
        }
        val reps = (0 until n).filter { g[it] == it }
        val groupRank = IntArray(n)
        run {
            val indeg = IntArray(n) { ins[it].size }
            val ready = reps.filter { indeg[it] == 0 }.toMutableList()
            while (ready.isNotEmpty()) {
                val v = ready.removeAt(0)
                for (w in outs[v]) {
                    groupRank[w] = maxOf(groupRank[w], groupRank[v] + len)
                    if (--indeg[w] == 0) {
                        ready += w
                        ready.sort()
                    }
                }
            }
        }
        val first = BooleanArray(n)
        val last = BooleanArray(n)
        for (v in 0 until n) {
            when (nodes[v].rank) {
                RankHint.First -> if (ins[g[v]].isNotEmpty()) {
                    warnings += GWarning(Codes.HINT_CONFLICT, v, null, "'rank: first' on a node that edges lead to; the hint is ignored")
                } else {
                    first[g[v]] = true
                }
                RankHint.Last -> if (outs[g[v]].isNotEmpty()) {
                    warnings += GWarning(Codes.HINT_CONFLICT, v, null, "'rank: last' on a node that edges leave from; the hint is ignored")
                } else {
                    last[g[v]] = true
                }
                else -> {}
            }
        }
        // a source sits next to what it leads to, unless it is held first
        for (v in reps) if (ins[v].isEmpty() && outs[v].isNotEmpty() && !first[v]) groupRank[v] = outs[v].minOf { groupRank[it] } - len
        val deepest = reps.maxOf { groupRank[it] }
        for (v in reps) if (last[v] && !first[v]) groupRank[v] = deepest
        val rank = IntArray(n) { groupRank[g[it]] }
        val lowest = rank.min()
        for (i in 0 until n) rank[i] -= lowest
        val maxRank = rank.max()

        // 4. vertices: the nodes, then one dummy per rank a long edge crosses. Each has a reference
        // point on the order axis, and extends `lo` before it and `hi` after it.
        val vRank = ArrayList<Int>()
        val vLo = ArrayList<Double>()
        val vHi = ArrayList<Double>()
        val vThick = ArrayList<Double>()
        val vKey = ArrayList<Double>()
        for (i in 0 until n) {
            vRank += rank[i]
            vLo += oSize[i] / 2
            vHi += oSize[i] / 2 + if (selfLoop[i]) LOOP else 0.0
            vThick += rSize[i]
            vKey += i.toDouble()
        }
        class Chain(val verts: IntArray, val flipped: Boolean, val label: Int)
        val chains = arrayOfNulls<Chain>(edges.size)
        val upN = ArrayList<MutableList<Int>>()
        val downN = ArrayList<MutableList<Int>>()
        repeat(n) {
            upN += mutableListOf<Int>()
            downN += mutableListOf<Int>()
        }
        val segments = Array(maxRank + 1) { mutableListOf<IntArray>() }
        val labelRank = BooleanArray(maxRank + 1)
        for (e in edges) {
            if (!e.hasLabel || rank[e.from] == rank[e.to]) continue
            val lo = minOf(rank[e.from], rank[e.to])
            labelRank[lo + (maxOf(rank[e.from], rank[e.to]) - lo) / 2] = true
        }
        for ((ei, e) in edges.withIndex()) {
            if (e.from == e.to) continue
            var a = e.from
            var b = e.to
            val flipped = rank[a] > rank[b]
            if (flipped) {
                a = e.to
                b = e.from
            }
            if (rank[a] == rank[b]) {
                chains[ei] = Chain(intArrayOf(e.from, e.to), false, -1)
                continue
            }
            val verts = ArrayList<Int>()
            verts += a
            var label = -1
            val middle = rank[a] + (rank[b] - rank[a]) / 2
            for (r in rank[a] + 1 until rank[b]) {
                // with doubled ranks, the ranks in between are for labels: an edge has a dummy there
                // only where it has to keep clear of one
                if (doubled && r % 2 == 1 && !labelRank[r]) continue
                val v = vRank.size
                vRank += r
                if (e.hasLabel && r == middle) {
                    val along = if (horizontal) e.labelH else e.labelW
                    vLo += LABEL_PAD / 2
                    vHi += along + LABEL_PAD * 3 / 2
                    vThick += if (horizontal) e.labelW else e.labelH
                    label = v
                } else {
                    vLo += 0.0
                    vHi += 0.0
                    vThick += 0.0
                }
                vKey += (a + b) / 2.0 + (ei + 1) * 1e-6
                upN += mutableListOf<Int>()
                downN += mutableListOf<Int>()
                verts += v
            }
            verts += b
            for (i in 0 until verts.size - 1) {
                downN[verts[i]] += verts[i + 1]
                upN[verts[i + 1]] += verts[i]
            }
            // crossings are counted between the ranks that hold nodes
            val stops = if (doubled) verts.filter { vRank[it] % 2 == 0 } else verts
            for (i in 0 until stops.size - 1) segments[vRank[stops[i]]] += intArrayOf(stops[i], stops[i + 1])
            chains[ei] = Chain(verts.toIntArray(), flipped, label)
        }
        val total = vRank.size

        // 5. order within each rank: declaration order, then barycenter sweeps and exchanges of
        // neighbours, keeping the order with the fewest crossings
        val ranks = Array(maxRank + 1) { mutableListOf<Int>() }
        for (v in 0 until total) ranks[vRank[v]] += v
        val pos = IntArray(total)
        fun index(r: Int) = ranks[r].forEachIndexed { i, v -> pos[v] = i }
        for (r in ranks.indices) {
            ranks[r].sortWith(compareBy<Int>({ vKey[it] }, { it }))
            index(r)
        }
        if (!opt.declared) {
            fun crossings(): Int {
                var c = 0
                for (segs in segments) {
                    for (i in segs.indices) for (j in i + 1 until segs.size) {
                        val p = segs[i]
                        val q = segs[j]
                        if ((pos[p[0]] - pos[q[0]]) * (pos[p[1]] - pos[q[1]]) < 0) c++
                    }
                }
                return c
            }
            fun sweep(down: Boolean) {
                val order = if (down) (1..maxRank).toList() else (maxRank - 1 downTo 0).toList()
                for (r in order) {
                    val keyed = ranks[r].map { v ->
                        val nb = if (down) upN[v] else downN[v]
                        val bary = if (nb.isEmpty()) pos[v].toDouble() else nb.sumOf { pos[it].toDouble() } / nb.size
                        Triple(v, bary, pos[v])
                    }
                    ranks[r] = keyed.sortedWith(compareBy<Triple<Int, Double, Int>>({ it.second }, { it.third })).map { it.first }.toMutableList()
                    index(r)
                }
            }
            // neighbours in a rank change places when that removes crossings: the sweeps alone stop
            // at orders that one exchange improves
            val above = Array(total) { mutableListOf<Int>() }
            val below = Array(total) { mutableListOf<Int>() }
            for (segs in segments) for (sg in segs) {
                below[sg[0]] += sg[1]
                above[sg[1]] += sg[0]
            }
            fun between(v: Int, w: Int): Int {
                var c = 0
                for (a in above[v]) for (b in above[w]) if (pos[a] > pos[b]) c++
                for (a in below[v]) for (b in below[w]) if (pos[a] > pos[b]) c++
                return c
            }
            fun transpose() {
                repeat(TRANSPOSITIONS) {
                    var improved = false
                    for (vs in ranks) {
                        for (i in 0 until vs.size - 1) {
                            val v = vs[i]
                            val w = vs[i + 1]
                            if (between(w, v) < between(v, w)) {
                                vs[i] = w
                                vs[i + 1] = v
                                pos[w] = i
                                pos[v] = i + 1
                                improved = true
                            }
                        }
                    }
                    if (!improved) return
                }
            }
            transpose()
            var best = crossings()
            var bestOrder = ranks.map { it.toList() }
            for (i in 0 until SWEEPS) {
                if (best == 0) break
                sweep(i % 2 == 0)
                transpose()
                val c = crossings()
                if (c < best) {
                    best = c
                    bestOrder = ranks.map { it.toList() }
                }
            }
            for (r in ranks.indices) {
                ranks[r] = bestOrder[r].toMutableList()
                index(r)
            }
        }
        // a rank of labels holds one dummy per edge that crosses it: they take the order of the
        // nodes those edges join, so that no two edges cross on their way through
        if (doubled) {
            for (r in 1 until maxRank step 2) {
                ranks[r].sortWith(compareBy<Int>({ pos[upN[it][0]] }, { pos[downN[it][0]] }, { pos[it] }))
                index(r)
            }
        }

        // 6. coordinates along the ranks: pack, then pull each vertex toward its neighbours in the
        // adjacent ranks. Keeping the order and the gaps is a weighted isotonic regression.
        val x = DoubleArray(total)
        fun sep(a: Int, b: Int) = vHi[a] + (if (a < n && b < n) opt.gap else opt.gap / 2) + vLo[b]
        // two dummies of one edge pull hardest, so that long edges run straight; then two nodes, so
        // that a chain of nodes lines up and the edges that go around it bend, not the chain
        // a dummy in a rank of labels stands for an edge between two neighbouring ranks of nodes
        fun between(v: Int) = doubled && vRank[v] % 2 == 1
        fun weight(a: Int, b: Int) = when {
            a >= n && b >= n -> 8.0
            a >= n -> if (between(a)) 3.0 else 1.0
            b >= n -> if (between(b)) 3.0 else 1.0
            else -> 3.0
        }
        for (r in ranks.indices) {
            val vs = ranks[r]
            for (i in vs.indices) x[vs[i]] = if (i == 0) vLo[vs[i]] else x[vs[i - 1]] + sep(vs[i - 1], vs[i])
        }
        fun relax(r: Int, up: Boolean, down: Boolean) {
            val vs = ranks[r]
            val m = vs.size
            if (m == 0) return
            val s = DoubleArray(m)
            for (i in 1 until m) s[i] = s[i - 1] + sep(vs[i - 1], vs[i])
            val bv = DoubleArray(m)
            val bw = DoubleArray(m)
            val bn = IntArray(m)
            var k = 0
            for (i in 0 until m) {
                val v = vs[i]
                var sum = 0.0
                var ws = 0.0
                if (up) for (u in upN[v]) {
                    val w = weight(u, v)
                    sum += w * x[u]
                    ws += w
                }
                if (down) for (u in downN[v]) {
                    val w = weight(u, v)
                    sum += w * x[u]
                    ws += w
                }
                bv[k] = (if (ws == 0.0) x[v] else sum / ws) - s[i]
                bw[k] = if (ws == 0.0) 0.05 else ws
                bn[k] = 1
                k++
                while (k > 1 && bv[k - 2] > bv[k - 1]) {
                    val tw = bw[k - 2] + bw[k - 1]
                    bv[k - 2] = (bv[k - 2] * bw[k - 2] + bv[k - 1] * bw[k - 1]) / tw
                    bw[k - 2] = tw
                    bn[k - 2] += bn[k - 1]
                    k--
                }
            }
            var i = 0
            for (b in 0 until k) repeat(bn[b]) {
                x[vs[i]] = bv[b] + s[i]
                i++
            }
        }
        repeat(RELAXATIONS) {
            for (r in 1..maxRank) relax(r, up = true, down = false)
            for (r in maxRank - 1 downTo 0) relax(r, up = false, down = true)
        }
        for (r in 0..maxRank) relax(r, up = true, down = true)

        // 7. positions across the ranks
        val thick = DoubleArray(maxRank + 1)
        for (v in 0 until total) thick[vRank[v]] = maxOf(thick[vRank[v]], vThick[v])
        val rGap = if (doubled) opt.rankGap / 2 else opt.rankGap
        val rPos = DoubleArray(maxRank + 1)
        for (r in 1..maxRank) rPos[r] = rPos[r - 1] + thick[r - 1] + rGap
        fun cr(v: Int) = rPos[vRank[v]] + thick[vRank[v]] / 2

        // 8. straighten the long edges. The dummies of an edge go onto the line between its two
        // nodes when nothing is in the way; failing that they share one coordinate, which gives
        // the edge one run along the rank axis and a bend at each end.
        fun fits(v: Int, at: Double): Boolean {
            val vs = ranks[vRank[v]]
            val i = pos[v]
            if (i > 0 && at - x[vs[i - 1]] < sep(vs[i - 1], v) - 1e-9) return false
            if (i < vs.size - 1 && x[vs[i + 1]] - at < sep(v, vs[i + 1]) - 1e-9) return false
            return true
        }
        for (c in chains) {
            if (c == null || c.verts.size < 3) continue
            val a = c.verts.first()
            val b = c.verts.last()
            val inner = c.verts.slice(1 until c.verts.size - 1)
            val line = inner.map { x[a] + (x[b] - x[a]) * (cr(it) - cr(a)) / (cr(b) - cr(a)) }
            if (inner.indices.all { fits(inner[it], line[it]) }) {
                inner.forEachIndexed { i, v -> x[v] = line[i] }
            } else if (inner.size > 1) {
                // the coordinates every dummy of the edge can take, between its neighbours in its rank
                var lo = Double.NEGATIVE_INFINITY
                var hi = Double.POSITIVE_INFINITY
                for (v in inner) {
                    val vs = ranks[vRank[v]]
                    val i = pos[v]
                    if (i > 0) lo = maxOf(lo, x[vs[i - 1]] + sep(vs[i - 1], v))
                    if (i < vs.size - 1) hi = minOf(hi, x[vs[i + 1]] - sep(v, vs[i + 1]))
                }
                if (lo <= hi) {
                    val run = minOf(maxOf(inner.sumOf { x[it] } / inner.size, lo), hi)
                    for (v in inner) x[v] = run
                }
            }
        }
        val minX = (0 until total).minOf { x[it] - vLo[it] }
        for (v in 0 until total) x[v] -= minX
        val totalO = (0 until total).maxOf { x[it] + vHi[it] }
        val totalR = rPos[maxRank] + thick[maxRank]
        fun pt(o: Double, r: Double): Point = when (opt.direction) {
            "up" -> Point(o, totalR - r)
            "right" -> Point(r, o)
            "left" -> Point(totalR - r, o)
            else -> Point(o, r)
        }
        val centers = List(n) { pt(x[it], cr(it)) }
        val positions = List(n) { Point(centers[it].x - nodes[it].w / 2, centers[it].y - nodes[it].h / 2) }

        // 9. routes and label positions
        val routes = arrayOfNulls<GRoute>(edges.size)
        val labels = arrayOfNulls<Point>(edges.size)
        val twins = HashMap<Long, MutableList<Int>>()
        for ((ei, e) in edges.withIndex()) {
            if (chains[ei] == null) continue
            val key = minOf(e.from, e.to).toLong() * n + maxOf(e.from, e.to)
            val list = twins[key]
            if (list == null) {
                twins[key] = mutableListOf(ei)
            } else {
                list += ei
            }
        }
        for ((ei, e) in edges.withIndex()) {
            if (e.from == e.to) {
                val c = centers[e.from]
                val nd = nodes[e.from]
                routes[ei] = if (horizontal) {
                    val y = c.y + nd.h / 2
                    GRoute(listOf(Point(c.x - 8, y), Point(c.x - 8, y + LOOP - 6), Point(c.x + 8, y + LOOP - 6), Point(c.x + 8, y)), exact = true)
                } else {
                    val xr = c.x + nd.w / 2
                    GRoute(listOf(Point(xr, c.y - 8), Point(xr + LOOP - 6, c.y - 8), Point(xr + LOOP - 6, c.y + 8), Point(xr, c.y + 8)), exact = true)
                }
                continue
            }
            val c = chains[ei] ?: continue
            if (c.verts.size == 2) {
                val a = centers[e.from]
                val b = centers[e.to]
                val group = twins.getValue(minOf(e.from, e.to).toLong() * n + maxOf(e.from, e.to))
                val direct = group.filter { chains[it]!!.verts.size == 2 }
                routes[ei] = if (group.size == 1) {
                    GRoute(listOf(a, b))
                } else {
                    // edges between the same two nodes bow apart, whichever way each one points. One
                    // that has a label keeps the straight line, with its label on the far side.
                    val shift = if (direct.size == group.size) (direct.indexOf(ei) - (direct.size - 1) / 2.0) * BOW else -(direct.indexOf(ei) + 1) * BOW
                    val flat = rank[e.from] == rank[e.to]
                    val low = if (e.from < e.to) a else b
                    val high = if (e.from < e.to) b else a
                    val mid = Point((low.x + high.x) / 2, (low.y + high.y) / 2)
                    val off = if (flat == horizontal) Point(shift, 0.0) else Point(0.0, shift)
                    GRoute(listOf(a, Point(mid.x + off.x, mid.y + off.y), b))
                }
                continue
            }
            // a dummy crosses its rank straight, so the edge only slants in the gaps between ranks
            val pts = c.verts.flatMap { v ->
                val t = thick[vRank[v]]
                if (v < n || t == 0.0) listOf(pt(x[v], cr(v))) else listOf(pt(x[v], cr(v) - t / 2), pt(x[v], cr(v) + t / 2))
            }
            routes[ei] = GRoute(if (c.flipped) pts.reversed() else pts)
            if (c.label >= 0) {
                val along = if (horizontal) e.labelH else e.labelW
                labels[ei] = pt(x[c.label] + LABEL_PAD + along / 2, cr(c.label))
            }
        }
        val size = pt(totalO, totalR).let { if (opt.direction == "down" || opt.direction == "up") Point(totalO, totalR) else Point(totalR, totalO) }
        return GResult(positions, routes.toList(), labels.toList(), size.x, size.y, warnings)
    }
}

/**
 * One parent per node. The first edge that reaches a node gives it its parent; any other is
 * reported and ignored for placement. A parent is centered over its children, which keep
 * declaration order. `radial` puts the root at the center and the levels on rings.
 */
internal object TreeLayout {
    class Options(val direction: String, val gap: Double, val levelGap: Double, val radial: Boolean)

    fun layout(nodes: List<GNode>, edges: List<GEdge>, opt: Options): GResult {
        val n = nodes.size
        if (n == 0) return GResult.empty(edges.size)
        val warnings = mutableListOf<GWarning>()
        val parent = IntArray(n) { -1 }
        val parentEdge = IntArray(n) { -1 }
        fun reaches(from: Int, target: Int): Boolean {
            var v = from
            while (v != -1) {
                if (v == target) return true
                v = parent[v]
            }
            return false
        }
        for ((ei, e) in edges.withIndex()) {
            if (e.from != e.to && parent[e.to] == -1 && !reaches(e.from, e.to)) {
                parent[e.to] = e.from
                parentEdge[e.to] = ei
            } else {
                warnings += GWarning(Codes.TREE_EXTRA_EDGE, null, ei, "this edge gives its target a second parent, or closes a loop; it does not place anything")
            }
        }
        val kids = Array(n) { mutableListOf<Int>() }
        for (v in 0 until n) if (parent[v] != -1) kids[parent[v]] += v
        val roots = (0 until n).filter { parent[it] == -1 }
        val depth = IntArray(n)
        fun descend(v: Int, d: Int) {
            depth[v] = d
            for (c in kids[v]) descend(c, d + 1)
        }
        for (r in roots) descend(r, 0)
        val routes = arrayOfNulls<GRoute>(edges.size)
        if (opt.radial) return radial(nodes, edges, opt, parent, parentEdge, kids, roots, depth, routes, warnings)

        val horizontal = opt.direction == "right" || opt.direction == "left"
        val oSize = DoubleArray(n) { if (horizontal) nodes[it].h else nodes[it].w }
        val rSize = DoubleArray(n) { if (horizontal) nodes[it].w else nodes[it].h }
        val span = DoubleArray(n)
        fun measure(v: Int): Double {
            var sum = 0.0
            kids[v].forEachIndexed { i, c -> sum += measure(c) + if (i > 0) opt.gap else 0.0 }
            span[v] = maxOf(oSize[v], sum)
            return span[v]
        }
        val xo = DoubleArray(n)
        fun place(v: Int, left: Double) {
            xo[v] = left + span[v] / 2
            var sum = 0.0
            kids[v].forEachIndexed { i, c -> sum += span[c] + if (i > 0) opt.gap else 0.0 }
            var cur = left + (span[v] - sum) / 2
            for (c in kids[v]) {
                place(c, cur)
                cur += span[c] + opt.gap
            }
        }
        var cur = 0.0
        for (r in roots) {
            measure(r)
            place(r, cur)
            cur += span[r] + opt.gap
        }
        val totalO = cur - opt.gap
        val levels = depth.max() + 1
        val thick = DoubleArray(levels)
        for (v in 0 until n) thick[depth[v]] = maxOf(thick[depth[v]], rSize[v])
        val rPos = DoubleArray(levels)
        for (d in 1 until levels) rPos[d] = rPos[d - 1] + thick[d - 1] + opt.levelGap
        val totalR = rPos[levels - 1] + thick[levels - 1]
        fun pt(o: Double, r: Double): Point = when (opt.direction) {
            "up" -> Point(o, totalR - r)
            "right" -> Point(r, o)
            "left" -> Point(totalR - r, o)
            else -> Point(o, r)
        }
        fun cr(v: Int) = rPos[depth[v]] + thick[depth[v]] / 2
        val centers = List(n) { pt(xo[it], cr(it)) }
        for (v in 0 until n) {
            val p = parent[v]
            if (p == -1) continue
            val bend = rPos[depth[v]] - opt.levelGap / 2
            routes[parentEdge[v]] = GRoute(listOf(centers[p], pt(xo[p], bend), pt(xo[v], bend), centers[v]))
        }
        val positions = List(n) { Point(centers[it].x - nodes[it].w / 2, centers[it].y - nodes[it].h / 2) }
        val vertical = !horizontal
        return GResult(positions, routes.toList(), List(edges.size) { null }, if (vertical) totalO else totalR, if (vertical) totalR else totalO, warnings)
    }

    private fun radial(
        nodes: List<GNode>,
        edges: List<GEdge>,
        opt: Options,
        parent: IntArray,
        parentEdge: IntArray,
        kids: Array<MutableList<Int>>,
        roots: List<Int>,
        depth: IntArray,
        routes: Array<GRoute?>,
        warnings: List<GWarning>,
    ): GResult {
        val n = nodes.size
        val extent = DoubleArray(n) { maxOf(nodes[it].w, nodes[it].h) }
        val widest = extent.max()
        val leaves = IntArray(n)
        fun count(v: Int): Int {
            leaves[v] = if (kids[v].isEmpty()) 1 else kids[v].sumOf { count(it) }
            return leaves[v]
        }
        val all = roots.sumOf { count(it) }
        val angle = DoubleArray(n)
        fun assign(v: Int, start: Double, sweep: Double) {
            angle[v] = start + sweep / 2
            var s = start
            for (c in kids[v]) {
                val part = sweep * leaves[c] / leaves[v]
                assign(c, s, part)
                s += part
            }
        }
        // one root sits at the center; several share the first ring
        val offset = if (roots.size == 1) 0 else 1
        var start = -Trig.PI / 2
        if (roots.size == 1) {
            assign(roots[0], start, Trig.TWO_PI)
        } else {
            for (r in roots) {
                val part = Trig.TWO_PI * leaves[r] / all
                assign(r, start, part)
                start += part
            }
        }
        val rings = depth.max() + offset + 1
        val need = DoubleArray(rings)
        for (v in 0 until n) need[depth[v] + offset] += extent[v] + opt.gap
        val step = opt.levelGap + widest
        val radius = DoubleArray(rings)
        for (k in 1 until rings) radius[k] = maxOf(radius[k - 1] + step, need[k] / Trig.TWO_PI)
        val outer = radius[rings - 1]
        val c = outer + widest / 2
        val centers = List(n) {
            val r = radius[depth[it] + offset]
            if (r == 0.0) Point(c, c) else Point(c + r * Trig.cos(angle[it]), c + r * Trig.sin(angle[it]))
        }
        for (v in 0 until n) if (parent[v] != -1) routes[parentEdge[v]] = GRoute(listOf(centers[parent[v]], centers[v]))
        val positions = List(n) { Point(centers[it].x - nodes[it].w / 2, centers[it].y - nodes[it].h / 2) }
        return GResult(positions, routes.toList(), List(edges.size) { null }, 2 * c, 2 * c, warnings)
    }
}

/** The children on a ring, in declaration order, clockwise from `start_angle`. */
internal object CircularLayout {
    class Options(val gap: Double, val radius: Double?, val startAngle: Double)

    fun layout(nodes: List<GNode>, edges: List<GEdge>, opt: Options): GResult {
        val n = nodes.size
        if (n == 0) return GResult.empty(edges.size)
        val extent = DoubleArray(n) { maxOf(nodes[it].w, nodes[it].h) }
        val step = Trig.TWO_PI / n
        // neighbours on the ring are a chord apart: it has to hold both of them and the gap
        val r = opt.radius ?: if (n == 1) {
            0.0
        } else {
            (0 until n).maxOf { (extent[it] + extent[(it + 1) % n]) / 2 + opt.gap } / (2 * Trig.sin(Trig.PI / n))
        }
        val maxW = nodes.maxOf { it.w }
        val maxH = nodes.maxOf { it.h }
        val cx = r + maxW / 2
        val cy = r + maxH / 2
        val start = opt.startAngle * Trig.PI / 180
        val centers = List(n) {
            val a = start + it * step
            Point(cx + r * Trig.cos(a), cy + r * Trig.sin(a))
        }
        val positions = List(n) { Point(centers[it].x - nodes[it].w / 2, centers[it].y - nodes[it].h / 2) }
        return GResult(positions, Twins.routes(centers, edges), List(edges.size) { null }, 2 * r + maxW, 2 * r + maxH, emptyList())
    }
}

/**
 * A simulation. Every edge is a spring that wants `link_distance` between the outlines of its two
 * nodes; nodes that no edge joins push each other away when they are close; everything is pulled
 * gently toward the middle, which keeps separate groups together. The movement allowed per step
 * cools down to nothing.
 *
 * It is reproducible to the last bit (addendum, section 8): the seed and the number of steps are
 * fixed, the start position comes from declaration order, and the arithmetic is add, subtract,
 * multiply, divide and square root only. Every resolution is a cold start.
 */
internal object ForceLayout {
    class Options(val seed: Long, val linkDistance: Double)

    private const val ITERATIONS = 300
    private const val REPULSION = 0.3
    private const val REACH = 3.0
    private const val GRAVITY = 0.03

    fun layout(nodes: List<GNode>, edges: List<GEdge>, opt: Options): GResult {
        val n = nodes.size
        if (n == 0) return GResult.empty(edges.size)
        val k = if (opt.linkDistance > 0.0) opt.linkDistance else 1.0
        var state = opt.seed
        fun next(): Double {
            state = state * 6364136223846793005L + 1442695040888963407L
            return (state ushr 11).toDouble() / 9007199254740992.0
        }
        val reach = DoubleArray(n) { maxOf(nodes[it].w, nodes[it].h) / 2 }
        // the start: a grid in declaration order, each node moved by up to one cell either way, so
        // that the seed decides which of the possible pictures comes out
        val pitch = k + 2 * reach.max()
        var cols = 1
        while (cols * cols < n) cols++
        val cx = DoubleArray(n)
        val cy = DoubleArray(n)
        val fixed = BooleanArray(n)
        for (i in 0 until n) {
            // drawn for every node, pinned or not, so that pinning one does not move the others' start
            val jx = (next() - 0.5) * 2 * pitch
            val jy = (next() - 0.5) * 2 * pitch
            val p = nodes[i].pinned
            if (p != null) {
                fixed[i] = true
                cx[i] = p.x + nodes[i].w / 2
                cy[i] = p.y + nodes[i].h / 2
            } else {
                cx[i] = (i % cols) * pitch + jx
                cy[i] = (i / cols) * pitch + jy
            }
        }
        val joined = BooleanArray(n * n)
        val degree = IntArray(n)
        for (e in edges) {
            if (e.from == e.to || joined[e.from * n + e.to]) continue
            joined[e.from * n + e.to] = true
            joined[e.to * n + e.from] = true
            degree[e.from]++
            degree[e.to]++
        }
        val dx = DoubleArray(n)
        val dy = DoubleArray(n)
        for (step in 0 until ITERATIONS) {
            val temp = k * (1.0 - step.toDouble() / ITERATIONS)
            var mx = 0.0
            var my = 0.0
            for (i in 0 until n) {
                mx += cx[i]
                my += cy[i]
            }
            mx /= n
            my /= n
            for (i in 0 until n) {
                dx[i] = (mx - cx[i]) * GRAVITY
                dy[i] = (my - cy[i]) * GRAVITY
            }
            for (i in 0 until n) for (j in i + 1 until n) {
                var vx = cx[i] - cx[j]
                var vy = cy[i] - cy[j]
                var d = sqrt(vx * vx + vy * vy)
                if (d < 0.01) {
                    // two nodes on one point part along a direction that declaration order gives
                    vx = 0.01
                    vy = 0.01 * (j - i)
                    d = sqrt(vx * vx + vy * vy)
                }
                val between = d - reach[i] - reach[j]
                val f = if (joined[i * n + j]) {
                    k - between
                } else if (between < REACH * k) {
                    REPULSION * k * k / maxOf(between, k * 0.05)
                } else {
                    0.0
                }
                dx[i] += vx / d * f
                dy[i] += vy / d * f
                dx[j] -= vx / d * f
                dy[j] -= vy / d * f
            }
            for (i in 0 until n) {
                if (fixed[i]) continue
                val gain = 0.5 / (1 + degree[i])
                val mxv = dx[i] * gain
                val myv = dy[i] * gain
                val l = sqrt(mxv * mxv + myv * myv)
                if (l == 0.0) continue
                val m = minOf(l, temp)
                cx[i] += mxv / l * m
                cy[i] += myv / l * m
            }
        }
        // pins are positions in the container's frame, so a pinned layout is left where it is
        if (fixed.none { it }) {
            val left = (0 until n).minOf { cx[it] - nodes[it].w / 2 }
            val top = (0 until n).minOf { cy[it] - nodes[it].h / 2 }
            for (i in 0 until n) {
                cx[i] -= left
                cy[i] -= top
            }
        }
        val positions = List(n) { Point(cx[it] - nodes[it].w / 2, cy[it] - nodes[it].h / 2) }
        val width = maxOf((0 until n).maxOf { cx[it] + nodes[it].w / 2 }, 0.0)
        val height = maxOf((0 until n).maxOf { cy[it] + nodes[it].h / 2 }, 0.0)
        return GResult(positions, Twins.routes(List(n) { Point(cx[it], cy[it]) }, edges), List(edges.size) { null }, width, height, emptyList())
    }
}

/**
 * Sine and cosine in common code, so that placement on a circle is identical on every target: the
 * platform functions may differ in their last bit. The argument is reduced to within a quarter turn
 * of zero and a fixed polynomial is evaluated with multiplications and divisions only.
 */
internal object Trig {
    const val PI = 3.141592653589793
    const val TWO_PI = 6.283185307179586
    private const val HALF_PI = 1.5707963267948966

    fun sin(a: Double): Double {
        val k = kotlin.math.floor(a / HALF_PI + 0.5)
        val r = a - k * HALF_PI
        return when (quadrant(k)) {
            0 -> sinP(r)
            1 -> cosP(r)
            2 -> -sinP(r)
            else -> -cosP(r)
        }
    }

    fun cos(a: Double): Double {
        val k = kotlin.math.floor(a / HALF_PI + 0.5)
        val r = a - k * HALF_PI
        return when (quadrant(k)) {
            0 -> cosP(r)
            1 -> -sinP(r)
            2 -> -cosP(r)
            else -> sinP(r)
        }
    }

    private fun quadrant(k: Double): Int = (((k % 4.0) + 4.0) % 4.0).toInt()

    private fun sinP(r: Double): Double {
        val q = r * r
        return r * (1 - q / 6 * (1 - q / 20 * (1 - q / 42 * (1 - q / 72 * (1 - q / 110 * (1 - q / 156 * (1 - q / 210)))))))
    }

    private fun cosP(r: Double): Double {
        val q = r * r
        return 1 - q / 2 * (1 - q / 12 * (1 - q / 30 * (1 - q / 56 * (1 - q / 90 * (1 - q / 132 * (1 - q / 182 * (1 - q / 240)))))))
    }
}

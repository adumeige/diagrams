package diagrams.resolver

import diagrams.assets.TextMeasurer
import diagrams.model.Codes
import diagrams.model.DataPath
import diagrams.model.DiagnosticSink
import diagrams.model.ExprEvalException
import diagrams.model.ExprSyntaxException
import diagrams.model.Exprs
import diagrams.model.Value
import diagrams.processor.CoreSchema
import diagrams.processor.GeometryRefs
import diagrams.processor.Layouts
import diagrams.processor.NodeIndex
import diagrams.processor.NodeInfo
import diagrams.processor.refsIn
import diagrams.processor.styleNames
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * One node during resolution. Its size, placement, world transform and (for a connector) route are
 * computed on first use and cached (spec section 6, "Static resolution order"): a node that needs
 * another's geometry asks for it, so the attachment and geometry-reference DAG is walked in
 * topological order without being built first. Asking for a value that is still being computed is
 * a cycle, reported as `attach_cycle`.
 */
internal class RNode(val info: NodeInfo, val parent: RNode?, val node: Value.Map, val tokens: Map<String, Value>) {
    val kind: String = info.shape ?: (node["media"] as? Value.Str)?.value ?: "container"
    val children = mutableListOf<RNode>()
    val dp: DataPath get() = info.dp
    val path: String get() = info.path
    val layout: String = (node["layout"] as? Value.Str)?.value ?: "free"
    val isConnector get() = kind == "connector"
    val isFlow get() = kind == "container" && layout != "free"

    var natural: Box? = null
    var stretchW: Double? = null
    var stretchH: Double? = null
    var flow: Flow? = null
    var placement: Matrix? = null
    var world: Matrix? = null
    var route: List<Point>? = null
    var text: TextMeasure? = null

    /** Whether the route came from the layout of a graph container (graph layouts addendum, section 3). */
    var supplied: Boolean = false
    var edgeOnly: Boolean? = null
}

/** Child placements of a flow container and its content size. [graph] is what a graph kind adds. */
internal class Flow(val placements: Map<RNode, Matrix>, val width: Double, val height: Double, val graph: GraphFlow? = null, val sequence: SeqFlow? = null)

/** What a sequence layout decided, in the container's frame: boxes of the nodes it places, message routes and labels. */
internal class SeqFlow(val boxes: Map<RNode, Box>, val routes: Map<RNode, List<Point>>, val labels: Map<RNode, Point>)

/** Routes and label positions supplied by a graph layout, by connector, in the container's frame. */
internal class GraphFlow(val routes: Map<RNode, GRoute>, val labels: Map<RNode, Point>)

/**
 * A connector that is an edge of the graph container [owner]: [from] and [to] are the children of
 * the container that hold its ends (the ends themselves when they are children).
 */
internal class Edge(val connector: RNode, val owner: RNode, val from: RNode, val to: RNode)

internal class TextMeasure(val font: String?, val m: TextMeasurer, val fontSize: Double, val lines: List<String>, val widths: List<Double>, val align: String, val width: Double)

private class Cycle(val key: Pair<RNode, String>) : Exception()
private class Unresolved : Exception()

internal class Engine(
    private val sink: DiagnosticSink,
    private val vw: Double,
    private val vh: Double,
    private val options: ResolveOptions,
    private val index: NodeIndex,
    /** Placements imposed by a transition frame, by locator: layout is not solved again per frame (spec section 13). */
    private val pins: Map<String, Pin> = emptyMap(),
    /** Supplied routes imposed by a transition frame, by locator: a graph layout is not solved again per frame either. */
    private val routePins: Map<String, RoutePin> = emptyMap(),
) {
    private val byLoc = HashMap<String, RNode>()
    private val connectors = mutableListOf<RNode>()
    private val attachers = mutableListOf<RNode>()

    /** The scene clock this resolution is for. */
    val time: Double get() = options.time
    private val env = options.environment
    private val byInfo = HashMap<NodeInfo, RNode>()
    private val notPresent = HashSet<NodeInfo>()
    private val inProgress = HashSet<Pair<RNode, String>>()

    // ---------------------------------------------------------------- tree

    /** Builds the tree of present nodes: styles applied, token scopes and presence evaluated. */
    fun build(info: NodeInfo, parent: RNode?): RNode? {
        val raw = info.node
        if (raw["use"] != null) return null
        if (info.shape in CoreSchema.UNSUPPORTED_SHAPES) return null
        val node = withStyles(raw)
        val parentTokens = parent?.tokens ?: env.rootTokens
        val tokens = when (val t = node["theme"]) {
            is Value.Str -> parentTokens + env.modules[t.value]?.tokens.orEmpty()
            is Value.Map -> parentTokens + t.entries.mapNotNull { (k, v) -> eval(v, null, parentTokens, info.dp.key("theme").key(k), "${info.path}.theme.$k")?.let { k to it } }
            else -> parentTokens
        }
        val present = eval(node["present"], null, tokens, info.dp.key("present"), "${info.path}.present") as? Value.Bool
        if (present?.value == false) {
            markAbsent(info)
            return null
        }
        val n = RNode(info, parent, node, tokens)
        byInfo[info] = n
        byLoc[info.loc] = n
        if (n.isConnector) connectors += n
        if (node["attach"] is Value.Map) attachers += n
        for (c in info.children) build(c, n)?.let { n.children += it }
        return n
    }

    private fun markAbsent(info: NodeInfo) {
        notPresent += info
        info.children.forEach(::markAbsent)
    }

    /** Node value, then named style (later styles win), then default (spec section 10). */
    private fun withStyles(node: Value.Map): Value.Map {
        val names = styleNames(node["style"])
        if (names.isEmpty()) return node
        val kind = CoreSchema.kindOf(node) ?: return node
        val out = LinkedHashMap<String, Value>()
        for (n in names) for ((k, v) in env.style(n)?.entries.orEmpty()) if (kind[k] != null) out[k] = v
        out.putAll(node.entries)
        return Value.Map(out)
    }

    fun background(): String? = (env.rootTokens["background"] as? Value.Str)?.value?.takeIf { CoreSchema.COLOR_PATTERN.matches(it) }?.lowercase()

    // ---------------------------------------------------------------- caching with cycle detection

    private inline fun <T : Any> cached(n: RNode, what: String, get: () -> T?, set: (T) -> Unit, fallback: () -> T, f: () -> T): T {
        get()?.let { return it }
        val key = n to what
        if (key in inProgress) throw Cycle(key)
        inProgress += key
        val v = try {
            f()
        } catch (c: Cycle) {
            if (c.key != key) throw c
            sink.error(Codes.ATTACH_CYCLE, n.path, "the $what of '${n.path}' depends on itself through attachments or geometry references", n.dp)
            fallback()
        } finally {
            inProgress -= key
        }
        set(v)
        return v
    }

    // ---------------------------------------------------------------- expressions

    /** Evaluates a scalar: literal, `==`-escaped literal or canonical expression, on node [n] (null while building). */
    private fun eval(v: Value?, n: RNode?, tokens: Map<String, Value>, dp: DataPath, path: String): Value? {
        if (v == null) return null
        if (v is Value.Str) return Value.Str(Exprs.unescape(v.value))
        if (v !is Value.Map) return v
        val expr = try {
            Exprs.fromCanonical(v)
        } catch (_: ExprSyntaxException) {
            null
        } ?: return v
        return try {
            Exprs.eval(expr) { ref -> lookup(ref, n, tokens, dp, path) }
        } catch (e: ExprEvalException) {
            sink.error(Codes.EXPR_EVAL, path, e.message ?: "evaluation failed", dp)
            null
        } catch (_: Unresolved) {
            null
        }
    }

    private fun lookup(ref: String, n: RNode?, tokens: Map<String, Value>, dp: DataPath, path: String): Value {
        val parts = ref.split('.')
        when (parts[0]) {
            "viewport" -> {
                val v = if (parts.size == 2) viewportValue(parts[1]) else null
                if (v != null) return Value.Num(v)
                sink.error(Codes.PATH_UNRESOLVED, path, "'$ref' matches no property of the viewport", dp)
                throw Unresolved()
            }
            "theme" -> {
                val t = if (parts.size == 2) tokens[parts[1]] else null
                if (t != null) return t
                sink.error(Codes.TOKEN_MISSING, path, "no theme in scope provides token '${parts.drop(1).joinToString(".")}'", dp)
                throw Unresolved()
            }
        }
        if (n == null) {
            sink.error(Codes.PATH_UNRESOLVED, path, "'$ref': token overrides and presence cannot reference geometry", dp)
            throw Unresolved()
        }
        return Value.Num(geometryValue(ref, n, dp, path) ?: throw Unresolved())
    }

    private fun viewportValue(p: String): Double? = when (p) {
        "width" -> vw
        "height" -> vh
        "left", "top", "x", "y" -> 0.0
        "right" -> vw
        "bottom" -> vh
        "center_x" -> vw / 2
        "center_y" -> vh / 2
        else -> null
    }

    /** The node a path written on [from] names, when present. Reports and returns null otherwise. */
    private fun target(ref: String, from: RNode, dp: DataPath, path: String): Pair<RNode, List<String>>? {
        if (ref == "host" || ref.startsWith("host.")) {
            val h = host(from) ?: run {
                sink.error(Codes.PATH_UNRESOLVED, path, "'host' is the container a decoration decorates; '${from.path}' is not inside an underlay or overlay", dp)
                return null
            }
            return h to ref.split('.').drop(1)
        }
        val m = index.resolve(ref, from.info)
        if (m == null) {
            sink.error(Codes.PATH_UNRESOLVED, path, "'$ref' matches no node in scope", dp)
            return null
        }
        val t = byInfo[m.node]
        if (t == null) {
            val why = if (m.node in notPresent) "is not present" else "is not drawn (a later-milestone construct)"
            sink.error(Codes.PATH_UNRESOLVED, path, "'$ref' $why, so it has no geometry", dp)
            return null
        }
        return t to m.rest
    }

    /**
     * A geometry reference (spec section 9): coordinates are in the frame of [n]'s parent, the frame
     * its own `at` is written in; `width` and `height` are the target's own size.
     */
    private fun geometryValue(ref: String, n: RNode, dp: DataPath, path: String): Double? {
        val (t, rest) = target(ref, n, dp, path) ?: return null
        // Only positions need the frame: a size read from inside a container that is being laid out
        // does not depend on where that container is placed. A position goes through the nearest
        // ancestor [n]'s parent and the target share, never through world coordinates, so that a
        // template can draw from its parts' positions while its container is still placing it.
        val local by lazy { if (t.isConnector) null else between(n.parent, t) }
        val frame by lazy { n.parent?.let { world(it).inverse() } ?: Matrix.IDENTITY }
        if (rest.size == 1) {
            when (rest[0]) {
                "width" -> return finalBox(t).width
                "height" -> return finalBox(t).height
            }
            val b = local?.apply(finalBox(t)) ?: frame.apply(worldBox(t))
            return when (rest[0]) {
                "x", "left" -> b.x
                "y", "top" -> b.y
                "right" -> b.right
                "bottom" -> b.bottom
                "center_x" -> b.centerX
                "center_y" -> b.centerY
                else -> null
            } ?: run {
                sink.error(Codes.PATH_UNRESOLVED, path, "'$ref': '${rest[0]}' is not a geometry property", dp)
                null
            }
        }
        if (rest.size == 2 && GeometryRefs.isAnchor(rest[0], t.info) && (rest[1] == "x" || rest[1] == "y")) {
            val p = local?.apply(compass(finalBox(t), rest[0]).first) ?: frame.apply(anchor(t, rest[0]).first)
            return if (rest[1] == "x") p.x else p.y
        }
        sink.error(Codes.PATH_UNRESOLVED, path, "'$ref' does not name a geometry value", dp)
        return null
    }

    /** An underlay or overlay node of a container (spec section 6, "Decorations"). */
    private fun decoration(n: RNode): Boolean = n.node["\$decoration"] != null

    /** The container a node decorates: the parent of its decoration (it or an ancestor). */
    private fun host(n: RNode): RNode? = generateSequence(n) { it.parent }.firstOrNull { decoration(it) }?.parent

    /** [t]'s placement in the frame of [from], through their nearest common ancestor; null when there is none. */
    private fun between(from: RNode?, t: RNode): Matrix? {
        if (from == null) return null
        val above = generateSequence(from) { it.parent }.toSet()
        val common = generateSequence(t) { it.parent }.firstOrNull { it in above } ?: return null
        val down = relative(common, t) ?: return null
        val up = relative(common, from) ?: return null
        return up.inverse() * down
    }

    /** [t]'s placement in the frame of [ancestor], when [t] is under it; null otherwise. */
    private fun relative(ancestor: RNode?, t: RNode): Matrix? {
        if (ancestor == null) return null
        if (t === ancestor) return Matrix.IDENTITY
        val chain = generateSequence(t) { it.parent }.takeWhile { it !== ancestor }.toList()
        if (chain.last().parent !== ancestor) return null
        return chain.asReversed().fold(Matrix.IDENTITY) { m, x -> m * placement(x) }
    }

    private fun prop(n: RNode, key: String): Value? = eval(n.node[key], n, n.tokens, n.dp.key(key), "${n.path}.$key")
    fun text(n: RNode, key: String): String? = str(n, key)

    /** `stroke_dash`: the dash and gap lengths, empty for a solid stroke. */
    fun dash(n: RNode): List<Double> =
        (prop(n, "stroke_dash") as? Value.List)?.items?.mapNotNull { (it as? Value.Num)?.value }?.takeIf { d -> d.any { it > 0 } }.orEmpty()
    fun num(n: RNode, key: String, default: Double): Double = (prop(n, key) as? Value.Num)?.value ?: default
    private fun numOrNull(n: RNode, key: String): Double? = (prop(n, key) as? Value.Num)?.value
    private fun str(n: RNode, key: String): String? = (prop(n, key) as? Value.Str)?.value

    fun color(n: RNode, key: String): String? {
        if (n.node[key] == null) return null
        val c = str(n, key) ?: return null
        if (!CoreSchema.COLOR_PATTERN.matches(c)) {
            sink.error(Codes.SCHEMA, "${n.path}.$key", "'$key' evaluated to '$c', which is not a color", n.dp.key(key))
            return null
        }
        return c.lowercase()
    }

    private fun point(n: RNode, v: Value, dp: DataPath, path: String): Point {
        val p = v as? Value.Map ?: return Point(0.0, 0.0)
        fun c(k: String) = (eval(p[k], n, n.tokens, dp.key(k), path) as? Value.Num)?.value ?: 0.0
        return Point(c("x"), c("y"))
    }

    fun points(n: RNode): List<Point> =
        (n.node["points"] as? Value.List)?.items.orEmpty().mapIndexed { i, p -> point(n, p, n.dp.key("points").index(i), "${n.path}.points") }

    // ---------------------------------------------------------------- sizes

    /** The node's local box before any `stretch` alignment. */
    private fun naturalBox(n: RNode): Box = cached(n, "size", { n.natural }, { n.natural = it }, { Box.EMPTY }) {
        pin(n)?.let { return@cached it.local }
        if (roleOf(n) == "fragment" || roleOf(n) == "operand") seqOwner[n]?.let { o -> flow(o).sequence?.boxes?.get(n)?.let { return@cached Box(0.0, 0.0, it.width, it.height) } }
        when (n.kind) {
            "container" -> containerBox(n)
            "rect", "ellipse" -> Box(0.0, 0.0, num(n, "width", 0.0), num(n, "height", 0.0))
            "circle" -> num(n, "radius", 0.0).let { Box(0.0, 0.0, 2 * it, 2 * it) }
            "line", "polyline", "polygon" -> Box.of(points(n))
            "path" -> PathData.bounds(pathSegments(n))
            "text" -> measure(n).let { Box(0.0, 0.0, it.width, it.lines.size * it.m.lineHeight(it.fontSize)) }
            "connector" -> Box.of(routeInParent(n))
            "image", "video" -> mediaBox(n)
            else -> Box.EMPTY
        }
    }

    /** The local box, after the parent's `stretch` alignment. */
    fun finalBox(n: RNode): Box {
        pin(n)?.let { return it.local }
        seqOwner[n]?.let { flow(it) }
        val p = n.parent
        if (p != null && p.isFlow && !n.isConnector && stretches(p)) flow(p)
        return stretchedBox(n)
    }

    private fun stretchedBox(n: RNode): Box {
        val b = naturalBox(n)
        if (n.stretchW == null && n.stretchH == null) return b
        return Box(b.x, b.y, n.stretchW ?: b.width, n.stretchH ?: b.height)
    }

    private fun stretches(c: RNode) = str(c, "align") == "stretch"

    /** Sets a child's stretched size. A stretched flow container lays its own children out again at that size. */
    private fun stretch(k: RNode, w: Double?, h: Double?) {
        val changed = (w != null && w != k.stretchW) || (h != null && h != k.stretchH)
        if (w != null) k.stretchW = w
        if (h != null) k.stretchH = h
        if (changed && k.isFlow) {
            k.flow = null
            fun clear(n: RNode) {
                for (c in n.children) {
                    c.placement = null
                    c.world = null
                    clear(c)
                }
            }
            clear(k)
        }
    }

    /** Only boxes with a free width or height can stretch: rectangles, ellipses, containers, text width. */
    private fun canStretch(n: RNode, horizontal: Boolean) = when (n.kind) {
        "rect", "ellipse", "container", "image", "video" -> true
        "text" -> horizontal
        else -> false
    }

    /**
     * An image or video's box (spec section 15): its intrinsic size from the manifest, or `width` and
     * `height`; with only one of them, the other keeps the aspect ratio.
     */
    private fun mediaBox(n: RNode): Box {
        val facts = intrinsic(n)
        val w = numOrNull(n, "width")
        val h = numOrNull(n, "height")
        val iw = facts?.first
        val ih = facts?.second
        return when {
            w != null && h != null -> Box(0.0, 0.0, w, h)
            w != null -> Box(0.0, 0.0, w, if (iw != null && ih != null && iw > 0) w * ih / iw else w)
            h != null -> Box(0.0, 0.0, if (iw != null && ih != null && ih > 0) h * iw / ih else h, h)
            else -> Box(0.0, 0.0, iw ?: 0.0, ih ?: 0.0)
        }
    }

    private val reportedMedia = HashSet<String>()

    /** The intrinsic size of a media node's asset, reported once when the manifest lacks it. */
    fun intrinsic(n: RNode): Pair<Double, Double>? {
        val asset = str(n, "asset") ?: return null
        val m = options.manifest.media(asset)
        val w = m?.width
        val h = m?.height
        if (w == null || h == null) {
            if (n.kind != "audio" && numOrNull(n, "width") == null && numOrNull(n, "height") == null && reportedMedia.add(asset)) {
                val why = if (options.manifest.assets.isEmpty()) "no asset manifest was given (run probe)" else "the asset manifest has no size for '$asset'"
                sink.error(Codes.ASSET_MISSING, n.path, "cannot size media '$asset': $why; or give 'width' and 'height'", n.dp, Value.map("asset" to Value.Str(asset)))
            }
            return null
        }
        return w to h
    }

    /** The media clips of the scene: audio and video on the scene clock (spec section 15). */
    val clips = mutableListOf<MediaClip>()

    /** Where a video is in its file at the scene's time, and records the clip. */
    fun mediaClip(n: RNode, ref: String?): MediaClip? {
        val asset = str(n, "asset") ?: return null
        val facts = options.manifest.media(asset)
        val from = num(n, "in", 0.0)
        val to = numOrNull(n, "out") ?: facts?.duration
        val c = MediaClip(ref, n.kind, asset, options.manifest.assets[asset]?.file, num(n, "start", 0.0), from, to, num(n, "volume", 1.0), num(n, "rate", 1.0))
        clips += c
        return c
    }

    private var pathCache = HashMap<RNode, List<Segment>>()

    fun pathSegments(n: RNode): List<Segment> = pathCache.getOrPut(n) {
        val d = str(n, "d") ?: ""
        try {
            PathData.parse(d)
        } catch (e: PathDataException) {
            sink.error(Codes.SCHEMA, "${n.path}.d", e.message ?: "invalid path data", n.dp.key("d"))
            emptyList()
        }
    }

    private val reportedFonts = HashSet<String>()

    /** Text measurement (spec section 16): the node's `font`, else the `font_body` token in scope. */
    fun measure(n: RNode): TextMeasure {
        n.text?.let { return it }
        val text = str(n, "text") ?: ""
        val fs = num(n, "font_size", 16.0)
        val align = str(n, "text_align") ?: "start"
        var font = str(n, "font") ?: (n.tokens["font_body"] as? Value.Str)?.value
        var m: TextMeasurer = options.fallback
        if (font != null) {
            val metrics = options.manifest.font(font)
            if (metrics == null) {
                if (reportedFonts.add(font)) {
                    val why = if (options.manifest.assets.isEmpty()) "no asset manifest was given (run probe)" else "the asset manifest has no font '$font'"
                    sink.error(Codes.ASSET_MISSING, n.path, "cannot measure text in font '$font': $why", n.dp, Value.map("asset" to Value.Str(font)))
                }
                font = null
            } else {
                m = metrics
            }
        }
        val explicit = numOrNull(n, "width")
        val wrap = (prop(n, "wrap") as? Value.Bool)?.value == true && explicit != null
        val lines = if (wrap) text.split('\n').flatMap { wrapLine(it, explicit!!, m, fs) } else text.split('\n')
        val widths = lines.map { m.advance(it, fs) }
        val width = explicit ?: (widths.maxOrNull() ?: 0.0)
        return TextMeasure(font, m, fs, lines, widths, align, width).also { n.text = it }
    }

    /** Greedy word wrap at spaces; a word longer than the width gets a line of its own. */
    private fun wrapLine(line: String, width: Double, m: TextMeasurer, fs: Double): List<String> {
        val words = line.split(' ')
        val out = mutableListOf<String>()
        var cur = ""
        for (w in words) {
            val next = if (cur.isEmpty()) w else "$cur $w"
            if (cur.isNotEmpty() && m.advance(next, fs) > width) {
                out += cur
                cur = w
            } else {
                cur = next
            }
        }
        out += cur
        return out
    }

    private fun containerBox(n: RNode): Box {
        val w = numOrNull(n, "width")
        val h = numOrNull(n, "height")
        val minW = numOrNull(n, "min_width") ?: 0.0
        val minH = numOrNull(n, "min_height") ?: 0.0
        if (n.isFlow) {
            val f = flow(n)
            return Box(0.0, 0.0, w ?: maxOf(f.width, minW), h ?: maxOf(f.height, minH))
        }
        val union = n.children.filter { !dependent(it) }.map { placement(it).apply(finalBox(it)) }.reduceOrNull { a, b -> a.union(b) } ?: Box.EMPTY
        if (w == null && h == null && union.right >= minW && union.bottom >= minH) return union
        return Box(0.0, 0.0, w ?: maxOf(union.right, minW), h ?: maxOf(union.bottom, minH))
    }

    /**
     * Nodes placed from other nodes' geometry do not count toward the size of their free container:
     * connectors, attached nodes, and nodes whose `at` references a node. This keeps a container's
     * size independent of anything attached inside it.
     */
    private fun dependent(n: RNode): Boolean {
        if (n.isConnector || decoration(n)) return true
        val p = n.parent ?: return false
        if (slotted(n)) return false
        if (p.isFlow) return n.node["attach"] is Value.Map || n.node["\$along"] != null
        if (n.node["\$along"] != null) return true
        if (n.node["attach"] is Value.Map) return true
        val at = n.node["at"] ?: return false
        return refsIn(at).any { it.substringBefore('.') !in setOf("theme", "viewport") }
    }

    // ---------------------------------------------------------------- flow layouts

    private class Padding(val top: Double, val right: Double, val bottom: Double, val left: Double)

    private fun padding(n: RNode): Padding {
        val v = n.node["padding"] ?: return Padding(0.0, 0.0, 0.0, 0.0)
        fun ev(x: Value?, k: String) = (eval(x, n, n.tokens, n.dp.key("padding").key(k), "${n.path}.padding") as? Value.Num)?.value
        if (v !is Value.Map) {
            val p = (eval(v, n, n.tokens, n.dp.key("padding"), "${n.path}.padding") as? Value.Num)?.value ?: 0.0
            return Padding(p, p, p, p)
        }
        val x = ev(v["x"], "x") ?: 0.0
        val y = ev(v["y"], "y") ?: 0.0
        return Padding(ev(v["top"], "top") ?: y, ev(v["right"], "right") ?: x, ev(v["bottom"], "bottom") ?: y, ev(v["left"], "left") ?: x)
    }

    /** Rotation and scale about the center of the local box (spec section 4). */
    private fun pivot(n: RNode): Matrix {
        val rot = num(n, "rotation", 0.0)
        val sc = num(n, "scale", 1.0)
        if (rot == 0.0 && sc == 1.0) return Matrix.IDENTITY
        val b = stretchedBox(n)
        return Matrix.translate(b.centerX, b.centerY) * Matrix.rotate(rot) * Matrix.scale(sc) * Matrix.translate(-b.centerX, -b.centerY)
    }

    /**
     * Whether the node takes a slot in its container's layout. Every child of a row, column, grid or
     * stack does, except connectors. In a graph container (graph layouts addendum, section 2) the
     * nodes are the children the edges connect: attached children follow their target as they do in a
     * free container, and a child that only holds connectors and what is attached to them (an instance
     * of a relation template) is not a node either.
     */
    private fun slotted(n: RNode): Boolean {
        val p = n.parent ?: return false
        if (!p.isFlow || n.isConnector || decoration(n) || p.layout == "sequence") return false
        if (!Layouts.isGraph(p.layout)) return true
        return n.node["attach"] !is Value.Map && n.node["\$along"] == null && !edgeOnly(n)
    }

    private fun edgeOnly(n: RNode): Boolean = n.edgeOnly ?: (
        n.kind == "container" && !n.isFlow && n.children.isNotEmpty() && n.children.all { dependent(it) || edgeOnly(it) }
        ).also { n.edgeOnly = it }

    private fun outer(k: RNode) = pivot(k).apply(stretchedBox(k))

    /** row, column, grid and stack (spec section 6), and the graph kinds: sizes bottom-up, then positions. */
    private fun flow(c: RNode): Flow = cached(c, "layout", { c.flow }, { c.flow = it }, { Flow(emptyMap(), 0.0, 0.0) }) {
        val kids = c.children.filter { slotted(it) }
        val pad = padding(c)
        val gap = num(c, "gap", 0.0)
        val align = str(c, "align") ?: "start"
        val explicitW = (numOrNull(c, "width") ?: c.stretchW)?.let { it - pad.left - pad.right }
        val explicitH = (numOrNull(c, "height") ?: c.stretchH)?.let { it - pad.top - pad.bottom }
        // `min_width` and `min_height`: the inner room a container keeps however small its content
        val minW = (numOrNull(c, "min_width") ?: 0.0) - pad.left - pad.right
        val minH = (numOrNull(c, "min_height") ?: 0.0) - pad.top - pad.bottom
        fun place(k: RNode, x: Double, y: Double): Matrix {
            val ob = outer(k)
            return Matrix.translate(x - ob.x, y - ob.y) * pivot(k)
        }
        fun offset(free: Double) = when (align) {
            "center" -> free / 2
            "end" -> free
            else -> 0.0
        }
        val out = LinkedHashMap<RNode, Matrix>()
        when (c.layout) {
            "row", "column" -> {
                val row = c.layout == "row"
                val natural = kids.map { outer(it) }
                val cross = (if (row) explicitH else explicitW) ?: maxOf(natural.maxOfOrNull { if (row) it.height else it.width } ?: 0.0, if (row) minH else minW)
                if (align == "stretch") {
                    for (k in kids) if (canStretch(k, !row)) {
                        if (row) stretch(k, null, cross) else stretch(k, cross, null)
                    }
                }
                var main = if (row) pad.left else pad.top
                for (k in kids) {
                    val ob = outer(k)
                    val crossSize = if (row) ob.height else ob.width
                    val crossPos = (if (row) pad.top else pad.left) + offset(cross - crossSize)
                    out[k] = if (row) place(k, main, crossPos) else place(k, crossPos, main)
                    main += (if (row) ob.width else ob.height) + gap
                }
                val mainSize = if (kids.isEmpty()) 0.0 else main - gap
                val w = if (row) mainSize + pad.right else pad.left + cross + pad.right
                val h = if (row) pad.top + cross + pad.bottom else mainSize + pad.bottom
                Flow(out, w, h)
            }
            "grid" -> {
                val cols = num(c, "columns", 1.0).toInt().coerceAtLeast(1)
                val rows = (kids.size + cols - 1) / cols
                val natural = kids.map { outer(it) }
                val colW = DoubleArray(cols)
                val rowH = DoubleArray(rows)
                natural.forEachIndexed { i, b ->
                    colW[i % cols] = maxOf(colW[i % cols], b.width)
                    rowH[i / cols] = maxOf(rowH[i / cols], b.height)
                }
                if (align == "stretch") {
                    kids.forEachIndexed { i, k ->
                        stretch(k, colW[i % cols].takeIf { canStretch(k, true) }, rowH[i / cols].takeIf { canStretch(k, false) })
                    }
                }
                val xs = DoubleArray(cols)
                val ys = DoubleArray(rows)
                for (j in 1 until cols) xs[j] = xs[j - 1] + colW[j - 1] + gap
                for (i in 1 until rows) ys[i] = ys[i - 1] + rowH[i - 1] + gap
                kids.forEachIndexed { i, k ->
                    val ob = outer(k)
                    val col = i % cols
                    val r = i / cols
                    out[k] = place(k, pad.left + xs[col] + offset(colW[col] - ob.width), pad.top + ys[r] + offset(rowH[r] - ob.height))
                }
                val w = if (cols == 0) 0.0 else xs[cols - 1] + colW[cols - 1]
                val h = if (rows == 0) 0.0 else ys[rows - 1] + rowH[rows - 1]
                Flow(out, pad.left + w + pad.right, pad.top + h + pad.bottom)
            }
            "sequence" -> sequenceFlow(c, pad)
            "layered", "tree", "circular", "force" -> {
                val g = graph(c, kids)
                val routes = LinkedHashMap<RNode, GRoute>()
                val labels = LinkedHashMap<RNode, Point>()
                fun shift(p: Point) = Point(p.x + pad.left, p.y + pad.top)
                kids.forEachIndexed { i, k -> out[k] = place(k, pad.left + g.result.positions[i].x, pad.top + g.result.positions[i].y) }
                g.edges.forEachIndexed { i, e ->
                    g.result.routes[i]?.let { r -> routes[e.connector] = GRoute(r.points.map(::shift), r.exact) }
                    g.result.labels[i]?.let { labels[e.connector] = shift(it) }
                }
                Flow(out, pad.left + g.result.width + pad.right, pad.top + g.result.height + pad.bottom, GraphFlow(routes, labels))
            }
            else -> { // stack
                val natural = kids.map { outer(it) }
                val iw = explicitW ?: maxOf(natural.maxOfOrNull { it.width } ?: 0.0, minW)
                val ih = explicitH ?: maxOf(natural.maxOfOrNull { it.height } ?: 0.0, minH)
                if (align == "stretch") {
                    for (k in kids) {
                        stretch(k, iw.takeIf { canStretch(k, true) }, ih.takeIf { canStretch(k, false) })
                    }
                }
                for (k in kids) {
                    val ob = outer(k)
                    out[k] = place(k, pad.left + offset(iw - ob.width), pad.top + offset(ih - ob.height))
                }
                Flow(out, pad.left + iw + pad.right, pad.top + ih + pad.bottom)
            }
        }
    }

    // ---------------------------------------------------------------- sequence layout

    /** A key of the node's `sequence` hint, evaluated. */
    private fun seqHint(n: RNode, key: String): Value? {
        val m = n.node["sequence"] as? Value.Map ?: return null
        return eval(m[key], n, n.tokens, n.dp.key("sequence").key(key), "${n.path}.sequence.$key")
    }

    private fun roleOf(n: RNode): String? = (seqHint(n, "role") as? Value.Str)?.value

    /**
     * What every node of a sequence container is to it (sequence layout addendum, section 2), found
     * by walking its children: participants, messages (their connectors), fragments and operands with
     * what they hold, activations, references. Decorations and attached nodes are not placed.
     */
    private val seqMembers: Map<RNode, List<Pair<RNode, String>>> by lazy {
        val out = LinkedHashMap<RNode, List<Pair<RNode, String>>>()
        for (c in byInfo.values) {
            if (c.kind != "container" || c.layout != "sequence") continue
            val members = mutableListOf<Pair<RNode, String>>()
            fun visit(n: RNode, top: Boolean) {
                if (decoration(n) || n.node["attach"] is Value.Map) return
                val role = roleOf(n) ?: when {
                    n.isConnector || edgeOnly(n) -> "message"
                    top -> "participant"
                    else -> null
                }
                when (role) {
                    null -> n.children.forEach { visit(it, false) }
                    "fragment", "operand" -> {
                        members += n to role
                        n.children.forEach { visit(it, false) }
                    }
                    else -> members += n to role
                }
            }
            c.children.forEach { visit(it, true) }
            out[c] = members
        }
        out
    }

    /** The sequence container that places a node, or routes a message's connector. */
    private val seqOwner: Map<RNode, RNode> by lazy {
        val out = HashMap<RNode, RNode>()
        for ((c, members) in seqMembers) for ((n, role) in members) {
            if (role == "message") seqConnector(n)?.let { out[it] = c } else out[n] = c
        }
        out
    }

    /** A message's connector: the node itself, or the first connector it holds. */
    private fun seqConnector(n: RNode): RNode? = if (n.isConnector) n else n.children.firstNotNullOfOrNull { seqConnector(it) }

    private fun sequenceFlow(c: RNode, pad: Padding): Flow {
        val members = seqMembers[c].orEmpty()
        val participants = members.filter { it.second == "participant" }.map { it.first }
        fun participantOf(x: RNode?): Int = if (x == null) -1 else participants.indexOfFirst { p -> p === x || generateSequence(x.parent) { it.parent }.any { it === p } }
        fun ref(n: RNode, v: Value?, key: String): RNode? = quiet((v as? Value.Str)?.value, n)?.first
        val ps = participants.map { p ->
            val b = outer(p)
            val life = (seqHint(p, "lifespan") as? Value.Str)?.value ?: "full"
            SequenceLayout.Participant(b.width, b.height, life == "created" || life == "both", life == "destroyed" || life == "both")
        }
        val items = mutableListOf<SequenceLayout.Item>()
        val nodes = mutableListOf<RNode>() // what node indices name
        val messages = mutableListOf<RNode>() // connectors, in order
        val messageNodes = mutableListOf<RNode>() // the message nodes (instances or connectors)
        val activations = mutableListOf<Pair<RNode, SequenceLayout.Activation?>>()
        fun covers(n: RNode) = ((n.node["sequence"] as? Value.Map)?.get("covers") as? Value.List)?.items.orEmpty().mapNotNull { v -> ref(n, v, "covers")?.let { participantOf(it) } }.filter { it >= 0 }
        val openGroups = ArrayDeque<RNode>()
        fun closeUntil(x: RNode) {
            // groups end where a member outside them comes: a node is inside a group when the group is its ancestor
            while (openGroups.isNotEmpty() && generateSequence(x.parent) { it.parent }.none { it === openGroups.last() }) {
                items += SequenceLayout.Close(nodes.indexOf(openGroups.removeLast()))
            }
        }
        for ((n, role) in members) {
            closeUntil(n)
            when (role) {
                "message" -> {
                    val k = seqConnector(n) ?: continue
                    val labels = labelsByConnector[k].orEmpty().map { outer(it) }
                    val a = end(k, "from")?.node
                    val b = end(k, "to")?.node
                    val from = participantOf(a)
                    val to = participantOf(b)
                    if (from < 0 || to < 0) sink.warning("message_unplaced", k.path, "an end of the message is not a participant of the sequence '${c.path}'; it is drawn straight", k.dp)
                    items += SequenceLayout.Message(from, to, labels.maxOfOrNull { it.width } ?: 0.0, labels.maxOfOrNull { it.height } ?: 0.0, bool(k, "parallel") == true)
                    messages += k
                    messageNodes += n
                }
                "fragment", "operand" -> {
                    nodes += n
                    items += SequenceLayout.Open(nodes.size - 1, role == "operand", covers(n))
                    openGroups.addLast(n)
                }
                "reference" -> {
                    nodes += n
                    val b = outer(n)
                    items += SequenceLayout.Reference(nodes.size - 1, b.width, b.height, covers(n))
                }
                "activation" -> {
                    nodes += n
                    activations += n to null
                }
            }
        }
        while (openGroups.isNotEmpty()) items += SequenceLayout.Close(nodes.indexOf(openGroups.removeLast()))
        fun messageIndex(n: RNode, v: Value?): Int {
            val t = quiet((v as? Value.Str)?.value, n)?.first ?: return -1
            return messageNodes.indexOfFirst { it === t || seqConnector(it) === t }
        }
        val acts = activations.mapNotNull { (n, _) ->
            val hint = n.node["sequence"] as? Value.Map
            val on = participantOf(ref(n, hint?.get("on"), "on"))
            val from = messageIndex(n, hint?.get("from"))
            val to = messageIndex(n, hint?.get("to"))
            if (on < 0 || from < 0 || to < 0 || to < from) {
                sink.error("activation_range", n.path, "an activation needs to be 'on' a participant, 'from' a message 'to' the same or a later one", n.dp)
                null
            } else {
                SequenceLayout.Activation(nodes.indexOf(n), on, from, to)
            }
        }
        val r = SequenceLayout.layout(ps, items, acts, SequenceLayout.Options(numOrNull(c, "gap") ?: 60.0, numOrNull(c, "row_gap") ?: 14.0, numOrNull(c, "fragment_padding") ?: 16.0))
        for (w in r.warnings) w.node?.let { nodes[it] }?.let { sink.warning(w.code, it.path, w.message, it.dp) }
        fun shift(b: Box) = Box(b.x + pad.left, b.y + pad.top, b.width, b.height)
        val boxes = LinkedHashMap<RNode, Box>()
        participants.forEachIndexed { i, p ->
            val b = shift(r.participants[i])
            boxes[p] = b
            stretch(p, null, b.height)
        }
        for ((i, b) in r.nodes) {
            val n = nodes[i]
            val s = shift(b)
            boxes[n] = s
            if (roleOf(n) != "fragment" && roleOf(n) != "operand") stretch(n, s.width, s.height)
        }
        val routes = LinkedHashMap<RNode, List<Point>>()
        val labels = LinkedHashMap<RNode, Point>()
        messages.forEachIndexed { i, k ->
            val route = r.routes[i]
            if (route.isNotEmpty()) routes[k] = route.map { Point(it.x + pad.left, it.y + pad.top) }
            r.labels[i]?.let { labels[k] = Point(it.x + pad.left, it.y + pad.top) }
        }
        val placements = LinkedHashMap<RNode, Matrix>()
        return Flow(placements, pad.left + r.width + pad.right, pad.top + r.height + pad.bottom, sequence = SeqFlow(boxes, routes, labels))
    }

    /** A planned node, in its parent's frame: the plan is in the container's, and a parent may be planned too. */
    private fun seqPlacement(n: RNode): Matrix {
        val owner = seqOwner[n]!!
        val plan = flow(owner).sequence ?: return Matrix.IDENTITY
        val b = plan.boxes[n] ?: return Matrix.IDENTITY
        // the parent's origin in the container's frame: the container, a planned node, or a node in between
        var x = 0.0
        var y = 0.0
        var p = n.parent
        while (p != null && p !== owner) {
            val pb = plan.boxes[p]
            if (pb != null) {
                x += pb.x
                y += pb.y
                break
            }
            val m = placement(p)
            x += m.apply(Point(0.0, 0.0)).x
            y += m.apply(Point(0.0, 0.0)).y
            p = p.parent
        }
        val local = stretchedBox(n)
        return Matrix.translate(b.x - x - local.x, b.y - y - local.y) * pivot(n)
    }

    /** A message's route in the scene, when a sequence container supplies it. */
    private fun seqRoute(n: RNode): List<Point>? {
        if (!n.isConnector) return null
        val o = seqOwner[n] ?: return null
        val pts = flow(o).sequence?.routes?.get(n) ?: return null
        n.supplied = true
        val w = world(o)
        return pts.map { w.apply(it) }
    }

    // ---------------------------------------------------------------- graph layouts

    private class Graph(val edges: List<Edge>, val result: GResult)

    /** A path written on [from], resolved without reporting: the validator and the router report. */
    private fun quiet(ref: String?, from: RNode): Pair<RNode, List<String>>? {
        val m = index.resolve(ref ?: return null, from.info) ?: return null
        val t = byInfo[m.node] ?: return null
        return t to m.rest
    }

    /**
     * The graph container a connector is an edge of, if any (graph layouts addendum, section 2): the
     * closest container that holds both ends in different children, when it is a graph container. An
     * end deeper than a child is lifted to that child. A connector with both ends on one child is a
     * self-loop of that child.
     */
    private fun edgeOf(k: RNode): Edge? {
        val a = quiet((k.node["from"] as? Value.Str)?.value, k)?.first ?: return null
        val b = quiet((k.node["to"] as? Value.Str)?.value, k)?.first ?: return null
        if (a.isConnector || b.isConnector) return null
        val c: RNode
        val ka: RNode
        val kb: RNode
        if (a === b) {
            c = a.parent ?: return null
            ka = a
            kb = a
        } else {
            val chain = generateSequence(a) { it.parent }.toList()
            var x: RNode? = b
            var below: RNode? = null
            while (x != null && chain.none { it === x }) {
                below = x
                x = x.parent
            }
            c = x ?: return null
            kb = below ?: return null
            val i = chain.indexOfFirst { it === c }
            if (i == 0) return null
            ka = chain[i - 1]
        }
        if (c.kind != "container" || !Layouts.isGraph(c.layout)) return null
        if (!slotted(ka) || !slotted(kb)) return null
        return Edge(k, c, ka, kb)
    }

    /** Every edge, by connector. Connectors are in declaration order, and so are the edges of a container. */
    private val edgeByConnector: Map<RNode, Edge> by lazy {
        val out = LinkedHashMap<RNode, Edge>()
        for (k in connectors) edgeOf(k)?.let { out[k] = it }
        out
    }

    private val edgesByOwner: Map<RNode, List<Edge>> by lazy {
        val out = LinkedHashMap<RNode, MutableList<Edge>>()
        for (e in edgeByConnector.values) out.getOrPut(e.owner) { mutableListOf() } += e
        out
    }

    /** The nodes attached to the `label` anchor of each connector. */
    private val labelsByConnector: Map<RNode, List<RNode>> by lazy {
        val out = LinkedHashMap<RNode, MutableList<RNode>>()
        for (n in attachers) {
            val (t, rest) = quiet(((n.node["attach"] as Value.Map)["to"] as? Value.Str)?.value, n) ?: continue
            if (t.isConnector && rest.size == 1 && rest[0] == GeometryRefs.LABEL_ANCHOR) out.getOrPut(t) { mutableListOf() } += n
        }
        out
    }

    private fun routeMode(k: RNode): String = str(k, "route") ?: "layout"

    private fun bool(n: RNode, key: String): Boolean? = (prop(n, key) as? Value.Bool)?.value

    /** Runs the layout of a graph container over its nodes and edges (graph layouts addendum, sections 4 to 7). */
    private fun graph(c: RNode, kids: List<RNode>): Graph {
        val edges = edgesByOwner[c].orEmpty()
        val layered = c.layout == "layered"
        val boxes = kids.map { outer(it) }
        fun indexOf(k: RNode) = kids.indexOfFirst { it === k }
        val nodes = kids.mapIndexed { i, k ->
            GNode(boxes[i].width, boxes[i].height, if (layered) rankHint(k, kids) else null, if (c.layout == "force") pinned(k) else null)
        }
        val gedges = edges.map { e ->
            // room is reserved for a label only when the connector follows the route that passes by it
            val label = if (layered && routeMode(e.connector) == "layout") labelsByConnector[e.connector]?.map { outer(it) } else null
            GEdge(
                indexOf(e.from),
                indexOf(e.to),
                constrain = !layered || bool(e.connector, "constrain") != false,
                labelW = label?.maxOfOrNull { it.width } ?: 0.0,
                labelH = label?.maxOfOrNull { it.height } ?: 0.0,
                follows = routeMode(e.connector) == "layout",
            )
        }
        val gap = numOrNull(c, "gap")
        val result = when (c.layout) {
            "layered" -> LayeredLayout.layout(nodes, gedges, LayeredLayout.Options(str(c, "direction") ?: "down", gap ?: 40.0, numOrNull(c, "rank_gap") ?: 60.0, str(c, "ordering") == "declared"))
            "tree" -> TreeLayout.layout(nodes, gedges, TreeLayout.Options(str(c, "direction") ?: "down", gap ?: 30.0, numOrNull(c, "level_gap") ?: 60.0, bool(c, "radial") == true))
            "circular" -> CircularLayout.layout(nodes, gedges, CircularLayout.Options(gap ?: 30.0, numOrNull(c, "radius"), numOrNull(c, "start_angle") ?: -90.0))
            else -> ForceLayout.layout(nodes, gedges, ForceLayout.Options((numOrNull(c, "seed") ?: 1.0).toLong(), numOrNull(c, "link_distance") ?: 60.0))
        }
        for (w in result.warnings) {
            val edge = w.edge
            val node = w.node
            when {
                edge != null -> edges[edge].connector.let { sink.warning(w.code, it.path, w.message, it.dp) }
                node != null -> kids[node].let { sink.warning(w.code, "${it.path}.rank", w.message, it.dp.key("rank")) }
            }
        }
        return Graph(edges, result)
    }

    /** The `rank` hint of a child of a layered container (graph layouts addendum, section 4). */
    private fun rankHint(k: RNode, kids: List<RNode>): RankHint? = when (val v = k.node["rank"]) {
        is Value.Str -> when (v.value) {
            "first" -> RankHint.First
            "last" -> RankHint.Last
            else -> null
        }
        is Value.Map -> {
            val t = quiet((v["same_as"] as? Value.Str)?.value, k)
            val i = if (t == null || t.second.isNotEmpty()) -1 else kids.indexOfFirst { it === t.first }
            if (i < 0 && t != null) {
                sink.warning(Codes.HINT_CONFLICT, "${k.path}.rank", "'rank: same_as' names a node the layout of '${k.parent?.path}' does not place; the hint is ignored", k.dp.key("rank"))
            }
            if (i < 0) null else RankHint.SameAs(i)
        }
        else -> null
    }

    /** The `pinned` hint of a child of a force container: its top-left corner, inside the container's padding. */
    private fun pinned(k: RNode): Point? = k.node["pinned"]?.let { point(k, it, k.dp.key("pinned"), "${k.path}.pinned") }

    /**
     * Hints and options that the layout in charge does not declare are reported, never silently
     * dropped (graph layouts addendum, section 3).
     */
    fun checkHints(root: RNode) {
        fun ignored(n: RNode, key: String, why: String) = sink.warning(Codes.HINT_IGNORED, "${n.path}.$key", "'$key' $why", n.dp.key(key))
        fun walk(n: RNode) {
            val p = n.parent
            // a member of a sequence container, at any depth, takes the hints of the sequence layout
            val seq = seqOwner[n] ?: p?.takeIf { it.layout == "sequence" }
            if (p != null) {
                val layout = seq?.layout ?: p.layout
                val kind = Layouts[layout]
                for (h in Layouts.NODE_HINTS) {
                    if (n.node[h] == null) continue
                    if (kind == null || h !in kind.nodeHints) {
                        ignored(n, h, "is not a hint of the '$layout' layout of '${(seq ?: p).path}'")
                    } else if (seq == null && !slotted(n)) {
                        ignored(n, h, "has no effect on a node the layout does not place")
                    }
                }
            }
            if (n.isConnector) {
                for (h in Layouts.EDGE_HINTS) {
                    // the value a connector has without the hint: a template may pass it through
                    if (n.node[h] == null || bool(n, h) == (h == "constrain")) continue
                    val e = edgeByConnector[n]
                    val o = seqOwner[n]
                    if (o != null) {
                        if (h !in Layouts["sequence"]!!.edgeHints) ignored(n, h, "is not a hint of the 'sequence' layout of '${o.path}'")
                    } else if (e == null) {
                        ignored(n, h, "has no effect: the connector is not an edge of a graph container")
                    } else if (h !in Layouts[e.owner.layout]!!.edgeHints) {
                        ignored(n, h, "is not a hint of the '${e.owner.layout}' layout of '${e.owner.path}'")
                    }
                }
            }
            if (n.kind == "container") {
                val kind = Layouts[n.layout]
                for (o in Layouts.GRAPH_OPTIONS) {
                    if (n.node[o] != null && (kind == null || o !in kind.options)) ignored(n, o, "is not an option of the '${n.layout}' layout")
                }
            }
            n.children.forEach(::walk)
        }
        walk(root)
    }

    /** Whether the connector's route is the one a graph layout supplied. */
    fun supplied(n: RNode): Boolean {
        route(n)
        return n.supplied
    }

    /** The room a layered layout reserved for the connector's label, in the connector's parent's frame. */
    fun labelInParent(n: RNode): Point? {
        val p = reservedLabel(n) ?: return null
        return (n.parent?.let { world(it).inverse() } ?: Matrix.IDENTITY).apply(p)
    }

    /** The reserved position of the label of a connector that follows a supplied route, in the scene. */
    private fun reservedLabel(t: RNode): Point? {
        seqOwner[t]?.let { o -> return flow(o).sequence?.labels?.get(t)?.let { world(o).apply(it) } }
        if (pins.isNotEmpty()) {
            val l = routePins[t.info.loc]?.label ?: return null
            return (t.parent?.let { world(it) } ?: Matrix.IDENTITY).apply(l)
        }
        if (routeMode(t) != "layout") return null
        val e = edgeByConnector[t] ?: return null
        val l = flow(e.owner).graph?.labels?.get(t) ?: return null
        return world(e.owner).apply(l)
    }

    // ---------------------------------------------------------------- placement

    /** Transform from the node's frame to its parent's: by the container, an attachment, or `at`. */
    fun placement(n: RNode): Matrix = cached(n, "position", { n.placement }, { n.placement = it }, { Matrix.IDENTITY }) {
        val p = n.parent
        when {
            pin(n) != null -> pin(n)!!.transform
            p == null || n.isConnector -> Matrix.IDENTITY
            seqOwner[n] != null -> seqPlacement(n)
            slotted(n) -> flow(p).placements[n] ?: Matrix.IDENTITY
            // a decoration is placed in its container's frame by its own `at`, whatever the layout
            decoration(n) -> atPlacement(n)
            n.node["\$along"] is Value.Map -> along(n) ?: atPlacement(n)
            n.node["attach"] is Value.Map -> attached(n) ?: atPlacement(n)
            // in a layout container `at` means nothing: a child without a slot stays in the container's frame
            p.isFlow -> Matrix.IDENTITY
            else -> atPlacement(n)
        }
    }

    /**
     * The pin of a node placed by its own geometry or its container. Nodes placed from other nodes
     * (connectors, attachments, geometry references, paths) are derived again instead, so they follow
     * their targets on every frame.
     */
    private fun pin(n: RNode): Pin? {
        if (pins.isEmpty()) return null
        val p = pins[n.info.loc] ?: return null
        return if (derived(n)) null else p
    }

    private fun derived(n: RNode): Boolean {
        if (n.isConnector) return true
        val p = n.parent ?: return false
        if (slotted(n)) return false
        if (p.isFlow) return n.node["attach"] is Value.Map || n.node["\$along"] != null
        if (n.node["attach"] is Value.Map || n.node["\$along"] != null) return true
        val at = n.node["at"] ?: return false
        return refsIn(at).any { it.substringBefore('.') !in setOf("theme", "viewport") }
    }

    /** A track `along` a connector: the node's center on the route, at a fraction of its length (spec section 12). */
    private fun along(n: RNode): Matrix? {
        val a = n.node["\$along"] as Value.Map
        val c = byLoc[(a["loc"] as? Value.Str)?.value] ?: return null
        val f = ((a["t"] as? Value.Num)?.value ?: 0.0).coerceIn(0.0, 1.0)
        val pts = route(c)
        if (pts.isEmpty()) return null
        val lens = pts.zipWithNext { p, q -> sqrt((q.x - p.x) * (q.x - p.x) + (q.y - p.y) * (q.y - p.y)) }
        var d = lens.sum() * f
        var at = pts.last()
        for ((i, l) in lens.withIndex()) {
            if (d <= l) {
                val u = if (l == 0.0) 0.0 else d / l
                at = Point(pts[i].x + (pts[i + 1].x - pts[i].x) * u, pts[i].y + (pts[i + 1].y - pts[i].y) * u)
                break
            }
            d -= l
        }
        val local = n.parent?.let { world(it).inverse() }?.apply(at) ?: at
        val m0 = pivot(n)
        val b = stretchedBox(n)
        val center = m0.apply(Point(b.centerX, b.centerY))
        return Matrix.translate(local.x - center.x, local.y - center.y) * m0
    }

    private fun atPlacement(n: RNode): Matrix {
        val at = n.node["at"]?.let { point(n, it, n.dp.key("at"), "${n.path}.at") } ?: Point(0.0, 0.0)
        return Matrix.translate(at.x, at.y) * pivot(n)
    }

    fun world(n: RNode): Matrix = cached(n, "world position", { n.world }, { n.world = it }, { Matrix.IDENTITY }) {
        (n.parent?.let { world(it) } ?: Matrix.IDENTITY) * placement(n)
    }

    fun worldBox(n: RNode): Box = if (n.isConnector) Box.of(route(n)) else world(n).apply(finalBox(n))

    /** A fixed anchor's position in the scene, and its outward direction (none for `center`). */
    private fun anchor(t: RNode, name: String): Pair<Point, Point?> {
        if (t.isConnector) {
            val pts = route(t)
            if (pts.isEmpty()) return Point(0.0, 0.0) to null
            return when (name) {
                "start" -> pts.first() to dir(pts.getOrNull(1) ?: pts.first(), pts.first())
                "end" -> pts.last() to dir(pts.getOrNull(pts.size - 2) ?: pts.last(), pts.last())
                "mid" -> midpoint(pts)
                // the room a layered layout reserved, or the middle of the route when there is none
                GeometryRefs.LABEL_ANCHOR -> reservedLabel(t)?.let { it to null } ?: midpoint(pts)
                else -> worldBox(t).let { compass(it, name) }.let { (p, d) -> p to d }
            }
        }
        val (p, d) = compass(finalBox(t), name)
        val w = world(t)
        return w.apply(p) to d?.let { normalize(w.applyVector(it)) }
    }

    private fun compass(b: Box, name: String): Pair<Point, Point?> {
        val s = 1 / sqrt(2.0)
        return when (name) {
            "north" -> Point(b.centerX, b.y) to Point(0.0, -1.0)
            "south" -> Point(b.centerX, b.bottom) to Point(0.0, 1.0)
            "east" -> Point(b.right, b.centerY) to Point(1.0, 0.0)
            "west" -> Point(b.x, b.centerY) to Point(-1.0, 0.0)
            "north_east" -> Point(b.right, b.y) to Point(s, -s)
            "north_west" -> Point(b.x, b.y) to Point(-s, -s)
            "south_east" -> Point(b.right, b.bottom) to Point(s, s)
            "south_west" -> Point(b.x, b.bottom) to Point(-s, s)
            else -> Point(b.centerX, b.centerY) to null
        }
    }

    private fun dir(from: Point, to: Point): Point? = normalize(Point(to.x - from.x, to.y - from.y))

    private fun normalize(p: Point): Point? {
        val l = sqrt(p.x * p.x + p.y * p.y)
        return if (l < 1e-12) null else Point(p.x / l, p.y / l)
    }

    /** The point halfway along a polyline, and the normal to its left (upward for a rightward segment). */
    private fun midpoint(pts: List<Point>): Pair<Point, Point?> {
        val lens = pts.zipWithNext { a, b -> sqrt((b.x - a.x) * (b.x - a.x) + (b.y - a.y) * (b.y - a.y)) }
        var half = lens.sum() / 2
        for ((i, l) in lens.withIndex()) {
            if (half <= l || i == lens.size - 1) {
                val a = pts[i]
                val b = pts[i + 1]
                val t = if (l == 0.0) 0.0 else (half / l).coerceIn(0.0, 1.0)
                val d = dir(a, b)
                return Point(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t) to d?.let { Point(it.y, -it.x) }
            }
            half -= l
        }
        return pts.first() to null
    }

    /**
     * An attachment (spec section 6): the attacher's anchor goes to the target anchor, moved `gap`
     * outward from it and then by `offset` (both in the parent's frame). A node path without an anchor
     * meets the attacher's opposite anchor: `{anchor: west, to: a}` sits right of `a`.
     */
    private fun attached(n: RNode): Matrix? {
        val a = n.node["attach"] as Value.Map
        val dp = n.dp.key("attach")
        val to = (a["to"] as? Value.Str)?.value ?: return null
        val own = (a["anchor"] as? Value.Str)?.value ?: "center"
        val targetAnchor: String
        val inv = n.parent?.let { world(it).inverse() } ?: Matrix.IDENTITY
        val (pw, dw) = if (to.startsWith("viewport.")) {
            // the viewport's anchors, in scene coordinates (spec section 9, reference roots)
            targetAnchor = to.removePrefix("viewport.")
            compass(Box(0.0, 0.0, vw, vh), targetAnchor)
        } else {
            val (t, rest) = target(to, n, dp.key("to"), "${n.path}.attach.to") ?: return null
            targetAnchor = rest.firstOrNull() ?: if (t.isConnector) "mid" else GeometryRefs.opposite(own)
            if (t.isConnector && targetAnchor == GeometryRefs.LABEL_ANCHOR) {
                // the layout reserved room for the label: it sits in the middle of it, whatever its own anchor and gap
                reservedLabel(t)?.let { room ->
                    val goal = inv.apply(room)
                    val off = a["offset"]?.let { point(n, it, dp.key("offset"), "${n.path}.attach.offset") } ?: Point(0.0, 0.0)
                    val m0 = pivot(n)
                    val local = m0.apply(compass(stretchedBox(n), "center").first)
                    return Matrix.translate(goal.x + off.x - local.x, goal.y + off.y - local.y) * m0
                }
            }
            if (t.isConnector && (a["side"] != null || a["along"] != null)) return beside(n, a, t, targetAnchor, inv)
            anchor(t, targetAnchor)
        }
        val p = inv.apply(pw)
        val d = dw?.let { normalize(inv.applyVector(it)) }
        val gap = (eval(a["gap"], n, n.tokens, dp.key("gap"), "${n.path}.attach.gap") as? Value.Num)?.value ?: 0.0
        val off = a["offset"]?.let { point(n, it, dp.key("offset"), "${n.path}.attach.offset") } ?: Point(0.0, 0.0)
        if (gap != 0.0 && d == null) {
            sink.warning(Codes.ATTACH_IGNORED, "${n.path}.attach.gap", "anchor '$targetAnchor' has no outward direction; 'gap' is ignored (use 'offset')", dp.key("gap"))
        }
        val goal = Point(p.x + (d?.x ?: 0.0) * gap + off.x, p.y + (d?.y ?: 0.0) * gap + off.y)
        val m0 = pivot(n)
        val local = m0.apply(compass(stretchedBox(n), own).first)
        return Matrix.translate(goal.x - local.x, goal.y - local.y) * m0
    }

    /**
     * An attachment beside a connector (spec section 6): the point `along` the route from the target
     * anchor, then `gap` away on `side`, where the attacher's anchor is the one facing the line.
     */
    private fun beside(n: RNode, a: Value.Map, t: RNode, anchorName: String, inv: Matrix): Matrix? {
        val dp = n.dp.key("attach")
        val pts = route(t)
        if (pts.size < 2) return null
        fun num(k: String) = (eval(a[k], n, n.tokens, dp.key(k), "${n.path}.attach.$k") as? Value.Num)?.value ?: 0.0
        val lens = pts.zipWithNext { p, q -> sqrt((q.x - p.x) * (q.x - p.x) + (q.y - p.y) * (q.y - p.y)) }
        val total = lens.sum()
        val along = num("along")
        val reserved = if (anchorName == GeometryRefs.LABEL_ANCHOR) reservedLabel(t) else null
        val s = when (anchorName) {
            "start" -> along
            "end" -> total - along
            else -> total / 2 + along
        }.coerceIn(0.0, total)
        // the point at distance s, and the direction of travel there
        var rest = s
        var at = pts.last()
        var dir = dir(pts[pts.size - 2], pts.last()) ?: Point(1.0, 0.0)
        for ((i, l) in lens.withIndex()) {
            if (rest <= l || i == lens.size - 1) {
                val u = if (l == 0.0) 0.0 else (rest / l).coerceIn(0.0, 1.0)
                at = Point(pts[i].x + (pts[i + 1].x - pts[i].x) * u, pts[i].y + (pts[i + 1].y - pts[i].y) * u)
                dir = dir(pts[i], pts[i + 1]) ?: dir
                break
            }
            rest -= l
        }
        if (reserved != null && a["along"] == null) at = reserved
        val local = inv.apply(at)
        val d = normalize(inv.applyVector(dir)) ?: Point(1.0, 0.0)
        val left = Point(d.y, -d.x)
        val normal = when ((eval(a["side"], n, n.tokens, dp.key("side"), "${n.path}.attach.side") as? Value.Str)?.value ?: "above") {
            "left" -> left
            "right" -> Point(-left.x, -left.y)
            "below" -> up(left).let { Point(-it.x, -it.y) }
            else -> up(left)
        }
        val gap = num("gap")
        val off = a["offset"]?.let { point(n, it, dp.key("offset"), "${n.path}.attach.offset") } ?: Point(0.0, 0.0)
        val goal = Point(local.x + normal.x * gap + off.x, local.y + normal.y * gap + off.y)
        // the attacher's anchor that faces the line: opposite the normal, to the nearest compass point
        val own = facing(Point(-normal.x, -normal.y))
        val m0 = pivot(n)
        val box = stretchedBox(n)
        val p = m0.apply(compass(box, own).first)
        return Matrix.translate(goal.x - p.x, goal.y - p.y) * m0
    }

    /** Of a normal and its opposite, the one that points up the screen (left, for a vertical line). */
    private fun up(v: Point): Point = when {
        v.y < -1e-6 -> v
        v.y > 1e-6 -> Point(-v.x, -v.y)
        v.x <= 0 -> v
        else -> Point(-v.x, -v.y)
    }

    /** The compass anchor in direction [v] from a box's center: east for (1, 0), south_west for (-1, 1). */
    private fun facing(v: Point): String {
        val angle = kotlin.math.atan2(v.y, v.x) * 180 / kotlin.math.PI
        val names = listOf("east", "south_east", "south", "south_west", "west", "north_west", "north", "north_east")
        val i = ((angle + 360.0 + 22.5).mod(360.0) / 45.0).toInt().mod(8)
        return names[i]
    }

    // ---------------------------------------------------------------- connectors

    private class End(val node: RNode, val anchor: String?)

    private fun end(n: RNode, key: String): End? {
        val ref = (n.node[key] as? Value.Str)?.value ?: return null
        val (t, rest) = target(ref, n, n.dp.key(key), "${n.path}.$key") ?: return null
        return End(t, rest.firstOrNull())
    }

    /** The route in scene coordinates (spec section 7): a second pass over resolved geometry. */
    fun route(n: RNode): List<Point> = cached(n, "route", { n.route }, { n.route = it }, { emptyList() }) {
        seqRoute(n)?.let { return@cached it }
        val a = end(n, "from")
        val b = end(n, "to")
        if (a == null || b == null) {
            emptyList()
        } else {
            // the router decides (graph layouts addendum, section 3): `layout` follows the route the
            // container's layout supplies, and is `straight` where there is none
            when (routeMode(n)) {
                "orthogonal" -> orthogonal(a, b)
                "straight" -> straight(a, b)
                else -> layoutRoute(n, a, b)?.also { n.supplied = true } ?: straight(a, b)
            }
        }
    }

    private fun straight(a: End, b: End): List<Point> {
        val ca = a.anchor?.let { anchor(a.node, it).first } ?: center(a.node)
        val cb = b.anchor?.let { anchor(b.node, it).first } ?: center(b.node)
        return listOf(a.anchor?.let { ca } ?: floating(a.node, cb), b.anchor?.let { cb } ?: floating(b.node, ca))
    }

    /**
     * The route supplied by the layout of the graph container the connector is an edge of, in the
     * scene. The layout gives the points between the two nodes; each end is then the outline of its
     * node toward the next point, or its fixed anchor. A fixed anchor does not change the layout, so
     * it may face away from the route: that is reported.
     */
    private fun layoutRoute(n: RNode, a: End, b: End): List<Point>? {
        fun rest(o: End) = o.anchor?.let { anchor(o.node, it).first } ?: center(o.node)
        if (pins.isNotEmpty()) {
            // a transition frame: the route was interpolated with the boxes, and its ends are taken
            // again on the interpolated outlines
            val pin = routePins[n.info.loc] ?: return null
            val w = n.parent?.let { world(it) } ?: Matrix.IDENTITY
            val pts = pin.points.map { w.apply(it) }
            if (pts.size < 2 || a.node === b.node) return pts
            fun inside(o: End, p: Point) = worldBox(o.node).let { p.x >= it.x && p.x <= it.right && p.y >= it.y && p.y <= it.bottom }
            val inner = pts.subList(1, pts.size - 1).dropWhile { inside(a, it) }.dropLastWhile { inside(b, it) }
            val first = a.anchor?.let { anchor(a.node, it).first } ?: floating(a.node, inner.firstOrNull() ?: rest(b))
            val last = b.anchor?.let { anchor(b.node, it).first } ?: floating(b.node, inner.lastOrNull() ?: rest(a))
            return simplify(listOf(first) + inner + last)
        }
        val e = edgeByConnector[n] ?: return null
        val r = flow(e.owner).graph?.routes?.get(n) ?: return null
        val w = world(e.owner)
        val pts = r.points.map { w.apply(it) }
        if (r.exact) return pts
        val inner = pts.subList(1, pts.size - 1)
        fun end(o: End, toward: Point, key: String): Point {
            val name = o.anchor ?: return floating(o.node, toward)
            val (p, d) = anchor(o.node, name)
            if (d != null && d.x * (toward.x - p.x) + d.y * (toward.y - p.y) < -1e-9) {
                sink.warning(Codes.ROUTE_BACKTRACK, "${n.path}.$key", "the anchor '$name' faces away from the route the layout of '${e.owner.path}' supplies, so the route turns back", n.dp.key(key))
            }
            return p
        }
        val first = end(a, inner.firstOrNull() ?: rest(b), "from")
        val last = end(b, inner.lastOrNull() ?: rest(a), "to")
        return simplify(listOf(first) + inner + last)
    }

    /** Connector geometry in its parent's frame: connectors paint where they are declared. */
    fun routeInParent(n: RNode): List<Point> {
        val inv = n.parent?.let { world(it).inverse() } ?: Matrix.IDENTITY
        return route(n).map { inv.apply(it) }
    }

    private fun center(t: RNode): Point = worldBox(t).let { Point(it.centerX, it.centerY) }

    /** The floating anchor: the point of [t]'s outline toward [toward] (spec section 6). */
    private fun floating(t: RNode, toward: Point): Point {
        if (t.isConnector) return anchor(t, "mid").first
        // an instance whose outline is one of its parts: the point of that part's shape
        val part = t.info.outline.takeIf { it != "box" && it != "ellipse" }?.let { t.info.namespace[it] }?.let { byInfo[it] }
        if (part != null && part !== t) return if (part.kind == "polygon") onPolygon(part, toward) else floating(part, toward)
        val w = world(t)
        val b = finalBox(t)
        val q = w.inverse().apply(toward)
        val cx = b.centerX
        val cy = b.centerY
        val dx = q.x - cx
        val dy = q.y - cy
        if (abs(dx) < 1e-12 && abs(dy) < 1e-12) return w.apply(Point(cx, cy))
        val hw = b.width / 2
        val hh = b.height / 2
        val s = if (t.info.outline == "ellipse") {
            if (hw == 0.0 || hh == 0.0) 0.0 else 1 / sqrt((dx / hw) * (dx / hw) + (dy / hh) * (dy / hh))
        } else {
            minOf(if (dx == 0.0) Double.MAX_VALUE else hw / abs(dx), if (dy == 0.0) Double.MAX_VALUE else hh / abs(dy))
        }
        return w.apply(Point(cx + dx * s, cy + dy * s))
    }

    /** Where the ray from the center of polygon [t]'s box toward [toward] leaves the polygon. */
    private fun onPolygon(t: RNode, toward: Point): Point {
        val w = world(t)
        val pts = points(t).map { w.apply(it) }
        val c = center(t)
        val dx = toward.x - c.x
        val dy = toward.y - c.y
        var best = Double.MAX_VALUE
        for (i in pts.indices) {
            val a = pts[i]
            val b = pts[(i + 1) % pts.size]
            val ex = b.x - a.x
            val ey = b.y - a.y
            val den = dx * ey - dy * ex
            if (abs(den) < 1e-12) continue
            val s = ((a.x - c.x) * ey - (a.y - c.y) * ex) / den
            val u = ((a.x - c.x) * dy - (a.y - c.y) * dx) / den
            if (s > 1e-9 && u >= -1e-9 && u <= 1 + 1e-9) best = minOf(best, s)
        }
        return if (best == Double.MAX_VALUE) floating(t, toward) else Point(c.x + dx * best, c.y + dy * best)
    }

    /**
     * The `orthogonal` router, without obstacle avoidance. Each end leaves from the middle of a side
     * (or from its fixed anchor): east or west when the ends are further apart horizontally, north or
     * south otherwise. Same-axis exits meet halfway with a two-bend route; overlapping spans give one
     * straight segment.
     */
    private fun orthogonal(a: End, b: End): List<Point> {
        val ba = worldBox(a.node)
        val bb = worldBox(b.node)
        val dx = (bb.centerX - ba.centerX) / maxOf(ba.width + bb.width, 1e-9)
        val dy = (bb.centerY - ba.centerY) / maxOf(ba.height + bb.height, 1e-9)
        val horizontal = abs(dx) >= abs(dy)
        // each end: exit point, whether the exit is horizontal, and its direction along that axis (+1 or -1)
        fun side(e: End, box: Box, sign: Double): Triple<Point, Boolean, Double> {
            e.anchor?.let { name ->
                val (p, d) = anchor(e.node, name)
                if (d == null) return Triple(p, horizontal, 0.0)
                val h = abs(d.x) >= abs(d.y)
                return Triple(p, h, if (h) kotlin.math.sign(d.x) else kotlin.math.sign(d.y))
            }
            val s = if (sign >= 0) 1.0 else -1.0
            return if (horizontal) {
                Triple(Point(if (s > 0) box.right else box.x, box.centerY), true, s)
            } else {
                Triple(Point(box.centerX, if (s > 0) box.bottom else box.y), false, s)
            }
        }
        val (p0, h0, s0) = side(a, ba, if (horizontal) dx else dy)
        val (p1, h1, s1) = side(b, bb, if (horizontal) -dx else -dy)
        var s = p0
        var t = p1
        // floating ends whose spans overlap meet on one straight line
        if (a.anchor == null && b.anchor == null) {
            if (horizontal) {
                val lo = maxOf(ba.y, bb.y)
                val hi = minOf(ba.bottom, bb.bottom)
                if (lo <= hi) {
                    val y = (lo + hi) / 2
                    s = Point(s.x, y)
                    t = Point(t.x, y)
                }
            } else {
                val lo = maxOf(ba.x, bb.x)
                val hi = minOf(ba.right, bb.right)
                if (lo <= hi) {
                    val x = (lo + hi) / 2
                    s = Point(x, s.y)
                    t = Point(x, t.y)
                }
            }
        }
        val pts = when {
            h0 && h1 -> {
                // both ends leave the same way: go out past the further one before crossing over
                val mx = when {
                    s0 > 0 && s1 > 0 -> maxOf(s.x, t.x) + STUB
                    s0 < 0 && s1 < 0 -> minOf(s.x, t.x) - STUB
                    else -> (s.x + t.x) / 2
                }
                listOf(s, Point(mx, s.y), Point(mx, t.y), t)
            }
            !h0 && !h1 -> {
                val my = when {
                    s0 > 0 && s1 > 0 -> maxOf(s.y, t.y) + STUB
                    s0 < 0 && s1 < 0 -> minOf(s.y, t.y) - STUB
                    else -> (s.y + t.y) / 2
                }
                listOf(s, Point(s.x, my), Point(t.x, my), t)
            }
            h0 -> listOf(s, Point(t.x, s.y), t)
            else -> listOf(s, Point(s.x, t.y), t)
        }
        return simplify(pts)
    }

    private companion object {
        /** How far a route leaves its ends before turning, when both ends exit the same way. */
        const val STUB = 20.0
    }

    /** Drops repeated and collinear points. */
    private fun simplify(pts: List<Point>): List<Point> {
        val out = mutableListOf<Point>()
        for (p in pts) {
            if (out.isNotEmpty() && abs(out.last().x - p.x) < 1e-9 && abs(out.last().y - p.y) < 1e-9) continue
            if (out.size >= 2) {
                val a = out[out.size - 2]
                val b = out.last()
                val cross = (b.x - a.x) * (p.y - a.y) - (b.y - a.y) * (p.x - a.x)
                if (abs(cross) < 1e-9) out.removeAt(out.size - 1)
            }
            out += p
        }
        return out
    }
}

/** A placement imposed on a node for one frame: its transform in the parent's frame and its local box. */
internal class Pin(val transform: Matrix, val local: Box)

/** A supplied route imposed on a connector for one frame, with its label position, in the parent's frame. */
internal class RoutePin(val points: List<Point>, val label: Point?)

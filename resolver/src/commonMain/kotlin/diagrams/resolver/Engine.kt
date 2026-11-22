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
}

/** Child placements of a flow container and its content size. */
internal class Flow(val placements: Map<RNode, Matrix>, val width: Double, val height: Double)

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
) {
    private val byLoc = HashMap<String, RNode>()

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
        val frame = n.parent?.let { world(it).inverse() } ?: Matrix.IDENTITY
        if (rest.size == 1) {
            when (rest[0]) {
                "width" -> return finalBox(t).width
                "height" -> return finalBox(t).height
            }
            val b = frame.apply(worldBox(t))
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
            val p = frame.apply(anchor(t, rest[0]).first)
            return if (rest[1] == "x") p.x else p.y
        }
        sink.error(Codes.PATH_UNRESOLVED, path, "'$ref' does not name a geometry value", dp)
        return null
    }

    private fun prop(n: RNode, key: String): Value? = eval(n.node[key], n, n.tokens, n.dp.key(key), "${n.path}.$key")
    fun text(n: RNode, key: String): String? = str(n, key)
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
        if (n.isFlow) {
            val f = flow(n)
            return Box(0.0, 0.0, w ?: f.width, h ?: f.height)
        }
        val union = n.children.filter { !dependent(it) }.map { placement(it).apply(finalBox(it)) }.reduceOrNull { a, b -> a.union(b) } ?: Box.EMPTY
        return if (w != null || h != null) Box(0.0, 0.0, w ?: union.right, h ?: union.bottom) else union
    }

    /**
     * Nodes placed from other nodes' geometry do not count toward the size of their free container:
     * connectors, attached nodes, and nodes whose `at` references a node. This keeps a container's
     * size independent of anything attached inside it.
     */
    private fun dependent(n: RNode): Boolean {
        if (n.isConnector) return true
        val p = n.parent ?: return false
        if (p.isFlow) return false
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

    /** row, column, grid and stack (spec section 6): sizes bottom-up, then positions. */
    private fun flow(c: RNode): Flow = cached(c, "layout", { c.flow }, { c.flow = it }, { Flow(emptyMap(), 0.0, 0.0) }) {
        val kids = c.children.filter { !it.isConnector }
        val pad = padding(c)
        val gap = num(c, "gap", 0.0)
        val align = str(c, "align") ?: "start"
        val explicitW = (numOrNull(c, "width") ?: c.stretchW)?.let { it - pad.left - pad.right }
        val explicitH = (numOrNull(c, "height") ?: c.stretchH)?.let { it - pad.top - pad.bottom }
        fun outer(k: RNode) = pivot(k).apply(stretchedBox(k))
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
                val cross = (if (row) explicitH else explicitW) ?: (natural.maxOfOrNull { if (row) it.height else it.width } ?: 0.0)
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
            else -> { // stack
                val natural = kids.map { outer(it) }
                val iw = explicitW ?: (natural.maxOfOrNull { it.width } ?: 0.0)
                val ih = explicitH ?: (natural.maxOfOrNull { it.height } ?: 0.0)
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

    // ---------------------------------------------------------------- placement

    /** Transform from the node's frame to its parent's: by the container, an attachment, or `at`. */
    fun placement(n: RNode): Matrix = cached(n, "position", { n.placement }, { n.placement = it }, { Matrix.IDENTITY }) {
        val p = n.parent
        when {
            pin(n) != null -> pin(n)!!.transform
            p == null || n.isConnector -> Matrix.IDENTITY
            p.isFlow -> flow(p).placements[n] ?: Matrix.IDENTITY
            n.node["\$along"] is Value.Map -> along(n) ?: atPlacement(n)
            n.node["attach"] is Value.Map -> attached(n) ?: atPlacement(n)
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
        if (p.isFlow) return false
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
        val (pw, dw) = if (to.startsWith("viewport.")) {
            // the viewport's anchors, in scene coordinates (spec section 9, reference roots)
            targetAnchor = to.removePrefix("viewport.")
            compass(Box(0.0, 0.0, vw, vh), targetAnchor)
        } else {
            val (t, rest) = target(to, n, dp.key("to"), "${n.path}.attach.to") ?: return null
            targetAnchor = rest.firstOrNull() ?: if (t.isConnector) "mid" else GeometryRefs.opposite(own)
            anchor(t, targetAnchor)
        }
        val inv = n.parent?.let { world(it).inverse() } ?: Matrix.IDENTITY
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

    // ---------------------------------------------------------------- connectors

    private class End(val node: RNode, val anchor: String?)

    private fun end(n: RNode, key: String): End? {
        val ref = (n.node[key] as? Value.Str)?.value ?: return null
        val (t, rest) = target(ref, n, n.dp.key(key), "${n.path}.$key") ?: return null
        return End(t, rest.firstOrNull())
    }

    /** The route in scene coordinates (spec section 7): a second pass over resolved geometry. */
    fun route(n: RNode): List<Point> = cached(n, "route", { n.route }, { n.route = it }, { emptyList() }) {
        val a = end(n, "from")
        val b = end(n, "to")
        if (a == null || b == null) {
            emptyList()
        } else if (str(n, "route") == "orthogonal") {
            orthogonal(a, b)
        } else {
            val ca = a.anchor?.let { anchor(a.node, it).first } ?: center(a.node)
            val cb = b.anchor?.let { anchor(b.node, it).first } ?: center(b.node)
            listOf(a.anchor?.let { ca } ?: floating(a.node, cb), b.anchor?.let { cb } ?: floating(b.node, ca))
        }
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

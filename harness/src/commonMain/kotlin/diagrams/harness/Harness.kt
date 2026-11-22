package diagrams.harness

import diagrams.assets.AssetManifest
import diagrams.lint.Lint
import diagrams.model.DataPath
import diagrams.model.Diagnostic
import diagrams.model.Severity
import diagrams.model.SourceMap
import diagrams.model.Value
import diagrams.processor.ENode
import diagrams.processor.ImportLoader
import diagrams.processor.LoadedFile
import diagrams.processor.NodeIndex
import diagrams.processor.ProcessResult
import diagrams.processor.Processor
import diagrams.processor.StateContainer
import diagrams.render.svg.SvgRenderer
import diagrams.resolver.Box
import diagrams.resolver.Geometry
import diagrams.resolver.Point
import diagrams.resolver.ResolveOptions
import diagrams.resolver.ResolvedNode
import diagrams.resolver.ResolvedScene
import diagrams.resolver.Resolver
import diagrams.resolver.Transition
import kotlin.math.sqrt

/**
 * A document and its files, as a harness receives them: the main file's path and the text of every
 * file it may import, keyed by path relative to the main file's directory.
 */
class Sources(val main: String, val files: Map<String, String>) {
    /** Imports resolve against the importing file's directory. */
    val loader = ImportLoader { path, from ->
        val dir = from?.substringBeforeLast('/', "")?.takeIf { from.contains('/') }
        val p = normalize(if (dir.isNullOrEmpty() || path.startsWith("/")) path else "$dir/$path")
        files[p]?.let { LoadedFile(it, p) }
    }

    companion object {
        fun normalize(path: String): String {
            val out = ArrayDeque<String>()
            for (s in path.split('/')) {
                when {
                    s == "" || s == "." -> {}
                    s == ".." && out.isNotEmpty() && out.last() != ".." -> out.removeLast()
                    else -> out.addLast(s)
                }
            }
            return out.joinToString("/")
        }
    }
}

/** One state container as the harness sees it: what it offers to navigate. */
class ContainerView(
    val owner: String,
    val kind: String,
    val states: List<String>,
    val current: String?,
    /** Tree: the parent of each state (null at the top). */
    val parents: Map<String, String?>,
    /** Graph: the named edges out of each state. */
    val edges: Map<String, Map<String, String>>,
)

/** A patch to the canonical document (spec section 17, "Patch"). */
sealed interface Patch {
    /** Sets a property: `card.fill`, `card.at.x`, `card.title` (a template parameter), `camera.zoom`. */
    class Set(val path: String, val value: Value) : Patch

    /** Adds a node to [parent]'s children, at [index] or at the end. */
    class Add(val parent: String, val node: Value, val index: Int? = null) : Patch

    /** Removes a node. */
    class Remove(val path: String) : Patch
}

class HarnessException(message: String) : Exception(message)

/**
 * The harness API (spec section 17): Read, Observe, Patch and Drive, over one document.
 *
 * The harness owns a scene clock ([time], advanced by [tick] while [playing]) and a current state
 * set. A state change starts a transition that plays on its own clock, also advanced by [tick], so
 * transitions animate while the timeline is paused. A state change during a transition interrupts
 * it: the frame on screen becomes the source of the next one.
 *
 * Resolution is not incremental yet: a patch or a state change resolves the whole document again.
 */
class Harness(sources: Sources, private var manifest: AssetManifest = AssetManifest.EMPTY, private val assets: Map<String, String> = emptyMap()) {
    private var sources = sources
    private var processed: ProcessResult = process()
    private var requested = LinkedHashMap<String, String>()
    private val entered = HashMap<String, Double>()
    private var transition: Transition? = null
    private var elapsed = 0.0
    private var cached: ResolvedScene? = null
    private var cachedKey: Any? = null
    private val observers = mutableListOf<Observer>()
    private val pointerListeners = mutableListOf<(String, String) -> Unit>()

    /** Seconds on the scene clock. */
    var time: Double = 0.0
        private set

    /** Whether [tick] advances the scene clock. */
    var playing: Boolean = false
        private set

    private class Observer(val path: String, val listener: (Value?) -> Unit, var last: Value?)

    private fun process(): ProcessResult {
        val text = sources.files[sources.main] ?: throw HarnessException("no file '${sources.main}' in the sources")
        return Processor.process(text, sources.main, sources.loader)
    }

    private fun options() = ResolveOptions(time = time, states = requested, entered = entered, manifest = manifest)

    /** Reads every file again (a live viewer calls this when a file changes); states and time are kept. */
    fun reload(sources: Sources, manifest: AssetManifest = this.manifest) {
        this.sources = sources
        this.manifest = manifest
        processed = process()
        transition = null
        invalidate()
    }

    // ---------------------------------------------------------------- the frame

    /** The scene on screen now: the transition in progress, else the current states at the current time. */
    fun frame(): ResolvedScene? {
        val t = transition
        if (t != null) {
            if (elapsed < t.duration) return t.frame(elapsed)
            transition = null
        }
        val key = listOf(time, requested.toMap(), entered.toMap())
        if (cachedKey != key) {
            cached = if (processed.hasErrors || processed.canonical == null) null else Resolver.resolve(processed, options()).scene
            cachedKey = key
        }
        return cached
    }

    private fun invalidate() {
        cachedKey = null
        notifyObservers()
    }

    /** Structural diagnostics, resolution diagnostics and lint of the current states (not of transition frames). */
    fun diagnostics(): List<Diagnostic> {
        val out = processed.diagnostics.toMutableList()
        if (processed.hasErrors || processed.canonical == null) return out
        val r = Resolver.resolve(processed, options())
        out += r.diagnostics
        r.scene?.let { out += Lint.run(it) }
        return out.distinct()
    }

    /** The current frame as SVG, fonts referenced by the URLs this harness was given. */
    fun svg(background: String? = null): String? {
        val scene = frame() ?: return null
        val fs = assets.mapNotNull { (name, url) -> manifest.font(name)?.let { name to SvgRenderer.Font(url, it) } }.toMap()
        return SvgRenderer.render(scene, SvgRenderer.Options(background = background ?: scene.background ?: "#ffffff", fonts = fs, media = assets))
    }

    /** The audio and video clips of the current frame, for a player that plays their sound (spec section 15). */
    fun media(): List<diagrams.resolver.MediaClip> = frame()?.media.orEmpty()

    /** The URL this harness was given for an asset. */
    fun assetUrl(name: String): String? = assets[name]



    // ---------------------------------------------------------------- Read

    /**
     * Any resolved value by path (spec section 17, "Read"), never with an encapsulation warning:
     * - `node.box` (`[x, y, width, height]`, viewport coordinates), `node.x`, `node.width`, `node.center_x`, ...
     * - `node.north` and other anchors (`[x, y]`), `node.opacity`, `node.fill`, `node.stroke`, `node.text`
     * - `node.state` or `camera.state`: the current state of the node's container
     * - `scene.states`: every container's current state; `scene.time`; `camera.zoom`, `camera.center`
     */
    fun read(path: String): Value? {
        val scene = frame() ?: return null
        when (path) {
            "scene.states" -> return Value.Map(scene.states.mapValuesTo(LinkedHashMap()) { Value.Str(it.value) })
            "scene.time" -> return Value.Num(time)
            "camera.zoom" -> return scene.camera?.let { Value.Num(it.zoom) } ?: Value.Num(1.0)
            "camera.center" -> return (scene.camera?.center ?: Point(scene.width / 2, scene.height / 2)).let { Value.list(Value.Num(it.x), Value.Num(it.y)) }
            "camera.rotation" -> return Value.Num(scene.camera?.rotation ?: 0.0)
            "camera.state" -> return scene.states["camera"]?.let { Value.Str(it) }
        }
        val dot = path.lastIndexOf('.')
        val (nodePath, prop) = if (dot < 0) path to "box" else path.substring(0, dot) to path.substring(dot + 1)
        val node = find(scene.root, nodePath) ?: find(scene.root, path)?.let { return box(it.box) } ?: return null
        val b = node.box
        return when (prop) {
            "box" -> box(b)
            "x", "left" -> Value.Num(b.x)
            "y", "top" -> Value.Num(b.y)
            "width" -> Value.Num(b.width)
            "height" -> Value.Num(b.height)
            "right" -> Value.Num(b.right)
            "bottom" -> Value.Num(b.bottom)
            "center_x" -> Value.Num(b.centerX)
            "center_y" -> Value.Num(b.centerY)
            "opacity" -> Value.Num(node.opacity)
            "fill" -> node.paint.fill?.let { Value.Str(it) }
            "stroke" -> node.paint.stroke?.let { Value.Str(it) }
            "text" -> (node.geometry as? Geometry.Text)?.let { g -> Value.Str(g.lines.joinToString("\n") { it.text }) }
            "state" -> scene.states[nodePath]?.let { Value.Str(it) }
            "kind" -> Value.Str(node.template ?: node.kind)
            in ANCHORS -> anchor(b, prop).let { Value.list(Value.Num(it.x), Value.Num(it.y)) }
            else -> null
        }
    }

    private fun box(b: Box) = Value.list(Value.Num(b.x), Value.Num(b.y), Value.Num(b.width), Value.Num(b.height))

    private fun find(root: ResolvedNode, path: String): ResolvedNode? {
        if (path == "scene") return root
        var out: ResolvedNode? = null
        root.walk { if (out == null && it.ref == path) out = it }
        return out
    }

    private fun anchor(b: Box, a: String) = when (a) {
        "north" -> Point(b.centerX, b.y)
        "south" -> Point(b.centerX, b.bottom)
        "east" -> Point(b.right, b.centerY)
        "west" -> Point(b.x, b.centerY)
        "north_east" -> Point(b.right, b.y)
        "north_west" -> Point(b.x, b.y)
        "south_east" -> Point(b.right, b.bottom)
        "south_west" -> Point(b.x, b.bottom)
        else -> Point(b.centerX, b.centerY)
    }

    // ---------------------------------------------------------------- Observe

    /** Calls [listener] with the value at [path] now, then every time it changes. Returns a function that stops it. */
    fun observe(path: String, listener: (Value?) -> Unit): () -> Unit {
        val o = Observer(path, listener, read(path))
        observers += o
        listener(o.last)
        return { observers.remove(o) }
    }

    /** Pointer events as paths: [listener] receives the event type (`click`) and the path hit. */
    fun onPointer(listener: (String, String) -> Unit): () -> Unit {
        pointerListeners += listener
        return { pointerListeners.remove(listener) }
    }

    /** A pointer event at viewport coordinates: hit-tested by the core, then sent to the pointer listeners. */
    fun pointer(type: String, x: Double, y: Double): String? {
        val path = hit(x, y) ?: return null
        pointerListeners.toList().forEach { it(type, path) }
        return path
    }

    private fun notifyObservers() {
        if (observers.isEmpty()) return
        for (o in observers.toList()) {
            val v = read(o.path)
            if (v != o.last) {
                o.last = v
                o.listener(v)
            }
        }
    }

    /**
     * The path of the topmost node under a viewport point: the nearest named node, with a keyed item
     * as `order.attributes[total]`, or `scene` for the background. Transparent nodes are not hit.
     */
    fun hit(x: Double, y: Double): String? {
        val scene = frame() ?: return null
        val p = Point(x, y)
        fun visit(n: ResolvedNode, named: String, opacity: Double): String? {
            val op = opacity * n.opacity
            if (op <= 0.01) return null
            val name = n.ref ?: named
            for (c in n.children.asReversed()) visit(c, name, op)?.let { return it }
            return if (inside(n, p)) name else null
        }
        return visit(scene.root, "scene", 1.0) ?: "scene"
    }

    private fun inside(n: ResolvedNode, p: Point): Boolean {
        val local = n.world.inverse().apply(p)
        val tol = 4 / n.world.scaleFactor.coerceAtLeast(1e-9)
        return when (val g = n.geometry) {
            Geometry.Group -> false
            is Geometry.Rect -> n.local.contains(Box(local.x, local.y, 0.0, 0.0))
            is Geometry.Ellipse -> g.rx > 0 && g.ry > 0 && ((local.x - g.cx) / g.rx).let { it * it } + ((local.y - g.cy) / g.ry).let { it * it } <= 1
            is Geometry.Text -> n.local.contains(Box(local.x, local.y, 0.0, 0.0))
            is Geometry.Poly -> {
                val near = g.points.zipWithNext().any { (a, b) -> distance(local, a, b) <= maxOf(tol, n.paint.strokeWidth / 2) }
                near || (g.closed && n.paint.fill != null && contains(g.points, local))
            }
            is Geometry.PathGeom, is Geometry.Media -> n.local.contains(Box(local.x, local.y, 0.0, 0.0))
        }
    }

    private fun distance(p: Point, a: Point, b: Point): Double {
        val dx = b.x - a.x
        val dy = b.y - a.y
        val l2 = dx * dx + dy * dy
        val t = if (l2 == 0.0) 0.0 else (((p.x - a.x) * dx + (p.y - a.y) * dy) / l2).coerceIn(0.0, 1.0)
        val qx = a.x + t * dx - p.x
        val qy = a.y + t * dy - p.y
        return sqrt(qx * qx + qy * qy)
    }

    private fun contains(poly: List<Point>, p: Point): Boolean {
        var inside = false
        var j = poly.size - 1
        for (i in poly.indices) {
            val a = poly[i]
            val b = poly[j]
            if ((a.y > p.y) != (b.y > p.y) && p.x < (b.x - a.x) * (p.y - a.y) / (b.y - a.y) + a.x) inside = !inside
            j = i
        }
        return inside
    }

    // ---------------------------------------------------------------- Patch

    /** The canonical document: what patches edit, and what a harness saves. */
    val document: Value? get() = processed.canonical

    /**
     * Applies patches to the canonical document (spec section 17, "Patch"), then processes and resolves
     * it again. Paths resolve from the root, like a reference written on the scene. Returns the new
     * structural diagnostics; nothing changes when a path is wrong.
     */
    fun patch(patches: List<Patch>): List<Diagnostic> {
        var doc = processed.canonical ?: throw HarnessException("the document has errors and cannot be patched")
        for (p in patches) doc = apply(doc, p)
        val next = Processor.processValue(doc, SourceMap(sources.main, emptyMap()), sources.loader)
        processed = next
        transition = null
        invalidate()
        return next.diagnostics
    }

    private fun apply(doc: Value, p: Patch): Value {
        val index = NodeIndex(ENode.plain(doc))
        val root = index.root ?: throw HarnessException("the document has no scene")
        fun node(path: String): Pair<List<Any>, List<String>> {
            if (path == "scene") return listOf<Any>("scene") to emptyList()
            if (path.startsWith("scene.") && index.resolve(path.removePrefix("scene."), root) == null) return listOf<Any>("scene") to path.removePrefix("scene.").split('.')
            val m = index.resolve(path, root) ?: throw HarnessException("'$path' matches no node")
            return m.node.dp.segments to m.rest
        }
        return when (p) {
            is Patch.Set -> {
                if (p.path.startsWith("camera.")) return setIn(doc, listOf("camera") + p.path.removePrefix("camera.").split('.'), p.value)
                val (dp, rest) = node(p.path)
                if (rest.isEmpty()) throw HarnessException("'${p.path}' names a node; a patch sets one of its properties, such as '${p.path}.fill'")
                setIn(doc, dp + rest, p.value)
            }
            is Patch.Add -> {
                val (dp, rest) = node(p.parent)
                if (rest.isNotEmpty()) throw HarnessException("'${p.parent}' does not name a node")
                val list = (get(doc, dp + "children") as? Value.List)?.items.orEmpty()
                val at = (p.index ?: list.size).coerceIn(0, list.size)
                setIn(doc, dp + "children", Value.List(list.take(at) + p.node + list.drop(at)))
            }
            is Patch.Remove -> {
                val (dp, rest) = node(p.path)
                if (rest.isNotEmpty() || dp.size < 2) throw HarnessException("'${p.path}' does not name a node that can be removed")
                val parent = dp.dropLast(1)
                val i = dp.last() as? Int ?: throw HarnessException("'${p.path}' fills a slot; remove it from the instance's slots")
                val list = (get(doc, parent) as Value.List).items
                setIn(doc, parent, Value.List(list.filterIndexed { k, _ -> k != i }))
            }
        }
    }

    private fun get(v: Value, path: List<Any>): Value? {
        var cur: Value = v
        for (s in path) cur = when {
            s is String && cur is Value.Map -> cur[s] ?: return null
            s is Int && cur is Value.List -> cur.items.getOrNull(s) ?: return null
            else -> return null
        }
        return cur
    }

    private fun setIn(v: Value, path: List<Any>, x: Value): Value {
        if (path.isEmpty()) return x
        val head = path[0]
        return when {
            head is String -> {
                val m = v as? Value.Map ?: Value.Map(emptyMap())
                Value.Map(m.entries + (head to setIn(m[head] ?: Value.Map(emptyMap()), path.drop(1), x)))
            }
            head is Int && v is Value.List -> Value.List(v.items.mapIndexed { i, c -> if (i == head) setIn(c, path.drop(1), x) else c })
            else -> throw HarnessException("cannot set a value at ${DataPath.ROOT}")
        }
    }

    // ---------------------------------------------------------------- Drive

    /** Every state container, with its current state and what it can navigate to. */
    fun containers(): List<ContainerView> {
        if (processed.hasErrors || processed.canonical == null) return emptyList()
        val r = Resolver.resolve(processed, options().copy(time = time))
        val current = transition?.target ?: r.scene?.states.orEmpty()
        return r.containers.map { c ->
            val parents = LinkedHashMap<String, String?>()
            for (s in c.container.states) parents[s.id] = s.parent?.id
            ContainerView(c.owner, c.container.kind, c.container.states.map { it.id }, current[c.owner], parents, c.container.states.associate { it.id to it.edges })
        }
    }

    private fun container(owner: String): Pair<StateContainer, String?> {
        if (processed.hasErrors || processed.canonical == null) throw HarnessException("the document has errors: fix them before driving its states")
        val r = Resolver.resolve(processed, options())
        val c = r.containers.firstOrNull { it.owner == owner } ?: throw HarnessException("no state container is owned by '$owner'")
        val cur = transition?.target?.get(owner) ?: r.scene?.states?.get(owner)
        return c.container to cur
    }

    /** Moves [owner]'s container to [state], playing the transition (spec section 11: `goto` works in every container). */
    fun goto(owner: String, state: String) {
        val (c, cur) = container(owner)
        if (c[state] == null) throw HarnessException("'$owner' has no state '$state'; states: ${c.states.joinToString { it.id }}")
        if (cur == state) return
        val from = transition?.target ?: (frame()?.states ?: emptyMap())
        val to = from + (owner to state)
        val running = transition
        transition = if (running != null && elapsed < running.duration) {
            running.interrupt(elapsed, to)
        } else {
            Resolver.transition(processed, from, to, options()).transition
        }
        elapsed = 0.0
        requested[owner] = state
        entered[owner] = time
        invalidate()
    }

    /** Sequence: the next state. */
    fun next(owner: String = "scene") = step(owner, +1)

    /** Sequence: the previous state. */
    fun prev(owner: String = "scene") = step(owner, -1)

    private fun step(owner: String, d: Int) {
        val (c, cur) = container(owner)
        if (c.kind != "sequence") throw HarnessException("'$owner' is a ${c.kind}; next and prev navigate a sequence")
        val i = c.states.indexOfFirst { it.id == cur }
        c.states.getOrNull(i + d)?.let { goto(owner, it.id) }
    }

    /** Tree: into the first child of the current state, or the child named [child]. */
    fun enter(owner: String = "scene", child: String? = null) {
        val (c, cur) = container(owner)
        if (c.kind != "tree") throw HarnessException("'$owner' is a ${c.kind}; enter navigates a tree")
        val kids = c.states.filter { it.parent?.id == cur }
        val target = if (child == null) kids.firstOrNull() else kids.firstOrNull { it.id == child } ?: throw HarnessException("'$child' is not a child of '$cur'")
        target?.let { goto(owner, it.id) }
    }

    /** Tree: to the parent of the current state. */
    fun up(owner: String = "scene") {
        val (c, cur) = container(owner)
        if (c.kind != "tree") throw HarnessException("'$owner' is a ${c.kind}; up navigates a tree")
        c[cur ?: return]?.parent?.let { goto(owner, it.id) }
    }

    /** Tree: to the next ([delta] = 1) or previous sibling of the current state. */
    fun sibling(owner: String = "scene", delta: Int = 1) {
        val (c, cur) = container(owner)
        if (c.kind != "tree") throw HarnessException("'$owner' is a ${c.kind}; sibling navigates a tree")
        val me = c[cur ?: return] ?: return
        val sibs = c.states.filter { it.parent === me.parent }
        sibs.getOrNull(sibs.indexOf(me) + delta)?.let { goto(owner, it.id) }
    }

    /** Graph: along the edge named [edge] from the current state. */
    fun follow(owner: String = "scene", edge: String) {
        val (c, cur) = container(owner)
        if (c.kind != "graph") throw HarnessException("'$owner' is a ${c.kind}; follow navigates a graph")
        val to = c[cur ?: return]?.edges?.get(edge) ?: throw HarnessException("state '$cur' has no edge '$edge'")
        goto(owner, to)
    }

    /** True while a transition is playing. */
    val transitioning: Boolean get() = transition?.let { elapsed < it.duration } ?: false

    /** How long the scene's timelines run. */
    fun duration(): Double = if (processed.hasErrors || processed.canonical == null) 0.0 else Resolver.timelineDuration(processed, options())

    fun play() {
        playing = true
    }

    fun pause() {
        playing = false
    }

    /** Moves the scene clock to [t] seconds. */
    fun seek(t: Double) {
        time = maxOf(0.0, t)
        invalidate()
    }

    /** Advances the transition in progress and, while playing, the scene clock. Returns true when the frame changed. */
    fun tick(dt: Double): Boolean {
        var changed = false
        if (transition != null) {
            elapsed += dt
            changed = true
        }
        if (playing) {
            time += dt
            changed = true
        }
        if (changed) notifyObservers()
        return changed
    }

    private companion object {
        val ANCHORS = setOf("center", "north", "south", "east", "west", "north_east", "north_west", "south_east", "south_west")
    }
}

/** Diagnostics are errors when any has severity error. */
fun List<Diagnostic>.hasErrors() = any { it.severity == Severity.ERROR }

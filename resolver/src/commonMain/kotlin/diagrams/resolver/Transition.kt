package diagrams.resolver

import diagrams.model.DataPath
import diagrams.model.DiagnosticSink
import diagrams.model.Value
import diagrams.processor.AutoSpan
import diagrams.processor.CompiledTimeline
import diagrams.processor.NodeInfo
import diagrams.processor.TimelineCompiler
import diagrams.processor.TrackSource

/**
 * A transition between two resolved states (spec section 13), played with [frame].
 *
 * - The automatic diff interpolates boxes, transforms, opacity, colors and compatible geometry.
 *   Layout is not solved again per frame, but connectors, attached nodes and nodes placed by
 *   geometry references are derived again on every frame from the interpolated boxes (decision 29).
 *   A route supplied by a graph layout is part of that layout: it is interpolated, and what is
 *   attached to the connector follows the interpolated route (graph layouts addendum, section 9).
 * - An explicit transition's body is a time item where `{auto: all}` places the automatic diff; its
 *   other items (clips, tracks) play on top.
 * - A node that exits pops at the start, unless it has an `exit` clip, which plays from the start.
 *   A node that enters pops at the end of the automatic diff, unless it has an `enter` clip, which
 *   plays from then on.
 *
 * Clips of a transition, `enter` and `exit` drive the resolved nodes directly: opacity, fill, stroke,
 * stroke width, `draw`, and `scale` and `rotation` composed with the node's own. Nothing is derived
 * again from what they move.
 */
class Transition internal constructor(private val a: Raw, private val b: Raw) {
    private class Clip(val timeline: CompiledTimeline, val offset: Double, val scope: NodeInfo, val node: ResolvedNode, val parentKey: String?)

    private val sink = DiagnosticSink()
    private val env = b.options.environment
    private val tokens = env.rootTokens
    private val auto: AutoSpan?
    private val body: CompiledTimeline
    private val bodyScope: NodeInfo?
    private val enters: List<Clip>
    private val exits: List<Clip>

    /** The length of the transition in seconds: automatic diff, body, `enter` and `exit` clips. */
    val duration: Double

    /** Problems found while compiling the body and clips. */
    val diagnostics get() = sink.all

    init {
        val from = a.applied.current
        val to = b.applied.current
        val changed = b.applied.containers.filter { from[it.owner] != to[it.owner] }.minByOrNull { it.depth }
        if (changed == null) {
            auto = AutoSpan(0.0, 0.0, null)
            body = CompiledTimeline.EMPTY
            bodyScope = null
        } else {
            val (d, e) = changed.container.timing(from[changed.owner], to.getValue(changed.owner))
            val def = changed.container.transition(from[changed.owner], to.getValue(changed.owner))
            bodyScope = b.index.all().firstOrNull { it.path == changed.owner && changed.owner != "camera" }
            if (def?.body != null) {
                body = TimelineCompiler(env, sink, auto = d to e, baseSource = TrackSource.RAW).timeline(def.body!!, DataPath.ROOT, "${changed.owner}.transitions")
                auto = body.autos.firstOrNull()
            } else {
                body = CompiledTimeline.EMPTY
                auto = AutoSpan(0.0, d, e)
            }
        }
        val autoEnd = auto?.let { it.start + it.duration } ?: body.duration
        val keyA = Keys(a.scene, b.scene)
        val inA = HashSet<String>()
        val inB = HashSet<String>()
        a.scene.root.walk { inA += keyA.key(it) }
        b.scene.root.walk { inB += keyA.key(it) }
        fun clips(raw: Raw, present: Set<String>, which: String, offset: Double): List<Clip> {
            val infos = raw.index.all().associateBy { it.loc }
            val out = mutableListOf<Clip>()
            fun walk(n: ResolvedNode, parent: ResolvedNode?) {
                if (keyA.key(n) !in present) {
                    val info = infos[n.loc]
                    val v = info?.node?.get(which)
                    if (info != null && v != null) {
                        val tl = TimelineCompiler(env, sink, implicitTarget = "").timeline(v, info.dp.key(which), "${info.path}.$which")
                        out += Clip(tl, offset, info, n, parent?.let { keyA.key(it) })
                    }
                    return // a node that enters or exits carries its subtree with it
                }
                n.children.forEach { walk(it, n) }
            }
            walk(raw.scene.root, null)
            return out
        }
        enters = clips(b, inA, "enter", autoEnd)
        exits = clips(a, inB, "exit", 0.0)
        duration = listOf(autoEnd, body.duration, enters.maxOfOrNull { it.offset + it.timeline.duration } ?: 0.0, exits.maxOfOrNull { it.timeline.duration } ?: 0.0).max()
    }

    /** The frame [t] seconds into the transition, seen through the camera. */
    fun frame(t: Double): ResolvedScene = Cameras.bake(unbaked(t))

    /** The current state set of the target. */
    val target: Map<String, String> get() = b.applied.current

    /**
     * A new transition to the states [to], starting from this one's frame at [t] (spec section 21,
     * "Interrupted transitions"): the in-flight values become the source of a new automatic diff.
     */
    fun interrupt(t: Double, to: Map<String, String>): Transition {
        val source = Raw(unbaked(t), b.applied, b.index, b.diagnostics, b.doc, b.sourceMap, b.options)
        return Transition(source, Resolver.raw(b.doc, b.sourceMap, b.options.copy(states = to)))
    }

    private fun unbaked(t: Double): ResolvedScene {
        if (t >= duration) return b.scene
        val p = auto?.let { if (it.duration <= 0) (if (t >= it.start) 1.0 else 0.0) else Easing.of(it.ease)((t - it.start) / it.duration) } ?: 0.0
        var scene = when {
            // the source scene still holds the exiting nodes: with exit clips, which graft them back
            // below, the first frame is interpolated like the others, or they would be drawn twice
            t <= 0.0 && exits.isEmpty() -> a.scene
            p >= 1.0 -> b.scene
            // from the first instant, exiting nodes are gone (spec section 13): interpolate even at 0
            else -> rederive(Transitions.frame(a.scene, b.scene, maxOf(p, 1e-9)))
        }
        // clips of the body and enter clips act on the target's nodes; exit clips on the source's
        val onB = HashMap<String, MutableMap<String, Value>>()
        if (body.tracks.isNotEmpty()) collect(Playing(body, bodyScope, t), b, onB)
        for (c in enters) if (t >= c.offset) collect(Playing(c.timeline, c.scope, t - c.offset), b, onB)
        var root = if (onB.isEmpty()) scene.root else overlay(scene.root, onB)
        for (c in exits) {
            if (t > c.offset + c.timeline.duration) continue
            val onA = HashMap<String, MutableMap<String, Value>>()
            collect(Playing(c.timeline, c.scope, t - c.offset), a, onA)
            root = graft(root, c, onA)
        }
        return scene.with(root = root, time = t)
    }

    /** Derives connectors and attached nodes again from the interpolated boxes: everything else is pinned. */
    private fun rederive(frame: ResolvedScene): ResolvedScene {
        val pins = HashMap<String, Pin>()
        // a route supplied by a graph layout is interpolated like a box: the graph is not laid out again
        val routes = HashMap<String, RoutePin>()
        frame.root.walk { n ->
            pins[n.loc] = Pin(n.transform, n.local)
            n.route?.let { routes[n.loc] = RoutePin(it, n.routeLabel) }
        }
        val r = Resolver.raw(b.doc, b.sourceMap, b.options, pins, b.applied, routes).scene
        val byLoc = HashMap<String, ResolvedNode>()
        r.root.walk { byLoc[it.loc] = it }
        fun merge(n: ResolvedNode): ResolvedNode {
            val kids = n.children.map(::merge)
            val x = byLoc[n.loc] ?: return n.copy(children = kids)
            return if (n.kind == "connector") {
                n.copy(local = x.local, transform = x.transform, world = x.world, box = x.box, geometry = x.geometry, children = kids)
            } else {
                n.copy(transform = x.transform, world = x.world, box = x.box, children = kids)
            }
        }
        return frame.with(root = merge(frame.root))
    }

    private fun collect(p: Playing, raw: Raw, into: HashMap<String, MutableMap<String, Value>>) {
        for (d in Animator.evaluate(listOf(p), raw.index, raw.applied.tree, null, tokens)) {
            val node = d.node ?: continue
            into.getOrPut(node.loc) { LinkedHashMap() }[d.property.joinToString(".")] = d.value
        }
    }

    /** Applies clip values by locator; descendants of a moved node move with it. */
    private fun overlay(n: ResolvedNode, props: Map<String, Map<String, Value>>, parentWorld: Matrix? = null): ResolvedNode {
        val p = props[n.loc]
        val x = when {
            p != null -> visual(n, p, parentWorld)
            parentWorld != null -> (parentWorld * n.transform).let { w -> n.copy(world = w, box = w.apply(n.local)) }
            else -> n
        }
        val moved = x.world != n.world
        return x.copy(children = n.children.map { overlay(it, props, if (moved) x.world else null) })
    }

    /** Applies clip values to a resolved node. */
    private fun visual(n: ResolvedNode, p: Map<String, Value>, parentWorld: Matrix?): ResolvedNode {
        fun num(k: String) = (p[k] as? Value.Num)?.value
        fun str(k: String) = (p[k] as? Value.Str)?.value?.lowercase()
        var paint = n.paint
        str("fill")?.let { paint = paint.copy(fill = it) }
        str("stroke")?.let { paint = paint.copy(stroke = it) }
        num("stroke_width")?.let { paint = paint.copy(strokeWidth = it) }
        val s = num("scale") ?: 1.0
        val r = num("rotation") ?: 0.0
        var transform = n.transform
        if (s != 1.0 || r != 0.0) {
            val c = Point(n.local.centerX, n.local.centerY)
            transform = transform * Matrix.translate(c.x, c.y) * Matrix.rotate(r) * Matrix.scale(s) * Matrix.translate(-c.x, -c.y)
        }
        val world = (parentWorld ?: (n.world * n.transform.inverse())) * transform
        return n.copy(transform = transform, world = world, box = world.apply(n.local), opacity = num("opacity") ?: n.opacity, paint = paint, draw = num("draw")?.coerceIn(0.0, 1.0) ?: n.draw)
    }

    private fun reworld(n: ResolvedNode, parentWorld: Matrix): ResolvedNode {
        val w = parentWorld * n.transform
        return n.copy(world = w, box = w.apply(n.local), children = n.children.map { reworld(it, w) })
    }

    /** An exiting node with an exit clip, drawn under its old parent while the clip plays. */
    private fun graft(root: ResolvedNode, c: Clip, props: Map<String, Map<String, Value>>): ResolvedNode {
        val keys = Keys(a.scene, b.scene)
        fun walk(n: ResolvedNode): ResolvedNode {
            if (keys.key(n) == c.parentKey) {
                val placed = overlay(reworld(c.node, n.world), props)
                return n.copy(children = n.children + placed)
            }
            return n.copy(children = n.children.map(::walk))
        }
        return walk(root)
    }
}

/** Node identity across two scenes: the path when it names one node in both, else the locator. */
internal class Keys(a: ResolvedScene, b: ResolvedScene) {
    private val shared = HashSet<String>()

    init {
        for (s in listOf(a, b)) {
            val seen = HashSet<String>()
            s.root.walk { n -> n.ref?.let { if (!seen.add(it)) shared += it } }
        }
    }

    fun key(n: ResolvedNode) = n.ref?.takeIf { it !in shared } ?: n.loc
}

package diagrams.resolver

import diagrams.assets.AssetManifest
import diagrams.assets.FallbackMetrics
import diagrams.assets.TextMeasurer
import diagrams.model.Codes
import diagrams.model.DataPath
import diagrams.model.Diagnostic
import diagrams.model.DiagnosticSink
import diagrams.model.ExprEvalException
import diagrams.model.ExprSyntaxException
import diagrams.model.Exprs
import diagrams.model.SourceMap
import diagrams.model.Value
import diagrams.processor.CoreSchema
import diagrams.processor.Environment
import diagrams.processor.NodeIndex
import diagrams.processor.ProcessResult
import diagrams.processor.styleNames

/** One line of a resolved text node, in the node's local frame. */
data class TextLine(val text: String, val x: Double, val baseline: Double, val width: Double)

/**
 * A node of the resolved scene (spec section 3). Read-only.
 *
 * [local] is the node's box in its own frame, [transform] maps that frame to the parent's,
 * [world] maps it to the scene, and [box] is the axis-aligned world box.
 */
class ResolvedNode(
    /** The node's path, or null when the node has no ID and cannot be referenced. */
    val ref: String?,
    /** Structural locator, always set: `scene/0/2` (child indices in declaration order). */
    val loc: String,
    val kind: String,
    val layer: String,
    val local: Box,
    val transform: Matrix,
    val world: Matrix,
    val box: Box,
    val opacity: Double,
    val paint: Paint,
    val geometry: Geometry,
    /** Children in paint order (layers, then declaration order). */
    val children: List<ResolvedNode>,
    /** The template this node is an instance of (`alias.name`). */
    val template: String? = null,
    /** The fraction of the stroke drawn, from its start (1: all of it). */
    val draw: Double = 1.0,
) {
    val label: String get() = ref ?: loc

    fun walk(visit: (ResolvedNode) -> Unit) {
        visit(this)
        children.forEach { it.walk(visit) }
    }

    fun copy(
        local: Box = this.local,
        transform: Matrix = this.transform,
        world: Matrix = this.world,
        box: Box = this.box,
        opacity: Double = this.opacity,
        paint: Paint = this.paint,
        geometry: Geometry = this.geometry,
        children: List<ResolvedNode> = this.children,
        draw: Double = this.draw,
    ) = ResolvedNode(ref, loc, kind, layer, local, transform, world, box, opacity, paint, geometry, children, template, draw)
}

data class Paint(val fill: String?, val stroke: String?, val strokeWidth: Double)

sealed interface Geometry {
    data object Group : Geometry
    data class Rect(val width: Double, val height: Double, val cornerRadius: Double) : Geometry
    data class Ellipse(val cx: Double, val cy: Double, val rx: Double, val ry: Double) : Geometry
    data class Poly(val points: List<Point>, val closed: Boolean, val markers: List<Marker> = emptyList()) : Geometry
    data class PathGeom(val segments: List<Segment>) : Geometry
    /** [font] is the font asset the text was measured with, or null for the built-in fallback metrics. */
    data class Text(val lines: List<TextLine>, val fontSize: Double, val font: String? = null) : Geometry

    /**
     * An image or a video frame drawn into the node's box (spec section 15). [intrinsic] is the
     * asset's own size, when known; [time] is the position in a video file, in seconds.
     */
    data class Media(val kind: String, val asset: String, val width: Double, val height: Double, val intrinsic: Box?, val fit: String, val time: Double? = null) : Geometry
}

/**
 * Audio or video on the scene clock (spec section 15): it plays from [start], from [from] to [to]
 * seconds of its file ([to] null: to the end), at [rate], at [volume]. [file] is the manifest's path.
 */
data class MediaClip(val ref: String?, val kind: String, val asset: String, val file: String?, val start: Double, val from: Double, val to: Double?, val volume: Double, val rate: Double) {
    /** Seconds the clip plays on the scene clock, when the end is known. */
    val duration: Double? get() = to?.let { (it - from) / rate }

    /** Position in the file at scene time [t], or null outside the clip. */
    fun at(t: Double): Double? {
        if (t < start) return null
        val p = from + (t - start) * rate
        return if (to != null && p > to) null else p
    }
}

class ResolvedScene(
    val format: String,
    val width: Double,
    val height: Double,
    val time: Double,
    val root: ResolvedNode,
    /** The `background` token at the root, when the base theme sets one. */
    val background: String? = null,
    /** The current state of each state container, by the path of its owner. */
    val states: Map<String, String> = emptyMap(),
    /** The camera this scene is seen through (spec section 14); null for none. */
    val camera: CameraView? = null,
    /** Options of the root layers (spec section 8). */
    val layers: Map<String, LayerOption> = emptyMap(),
    /** Audio and video on the scene clock (spec section 15), for players and exporters. */
    val media: List<MediaClip> = emptyList(),
) {
    val viewportBox get() = Box(0.0, 0.0, width, height)

    fun with(
        root: ResolvedNode = this.root,
        time: Double = this.time,
        background: String? = this.background,
        camera: CameraView? = this.camera,
    ) = ResolvedScene(format, width, height, time, root, background, states, camera, layers, media)
}

class ResolveResult(val scene: ResolvedScene?, val diagnostics: List<Diagnostic>, val containers: List<ContainerInfo> = emptyList())

/** Resolver inputs besides the canonical document (spec section 3). */
data class ResolveOptions(
    /** Seconds on the scene clock: the root timeline and node timelines play at this time. */
    val time: Double = 0.0,
    val states: Map<String, String> = emptyMap(),
    /** When each container entered its current state, on the scene clock: its state timeline starts then. */
    val entered: Map<String, Double> = emptyMap(),
    /** Imported themes and styles, from [ProcessResult.environment]. */
    val environment: Environment = Environment.EMPTY,
    /** Font metrics and other asset facts (spec section 15). */
    val manifest: AssetManifest = AssetManifest.EMPTY,
    /** Metrics for text that names no font. */
    val fallback: TextMeasurer = FallbackMetrics,
)

/** A resolution before the camera is applied, with what transitions need to derive frames from it. */
internal class Raw(
    val scene: ResolvedScene,
    val applied: StateApplication,
    val index: NodeIndex,
    val diagnostics: List<Diagnostic>,
    val doc: Value.Map,
    val sourceMap: SourceMap?,
    val options: ResolveOptions,
)

object Resolver {
    fun resolve(canonical: Value, sourceMap: SourceMap? = null, options: ResolveOptions = ResolveOptions()): ResolveResult {
        val doc = canonical as? Value.Map ?: return ResolveResult(null, listOf(Diagnostic(Codes.SCHEMA, diagrams.model.Severity.ERROR, "", "a document is a map")))
        val raw = raw(doc, sourceMap, options)
        return ResolveResult(Cameras.bake(raw.scene), raw.diagnostics, raw.applied.containers)
    }

    /** Resolves the output of the processor, with its imports. */
    fun resolve(processed: ProcessResult, options: ResolveOptions = ResolveOptions()): ResolveResult =
        resolve(processed.canonical!!, processed.sourceMap, options.copy(environment = processed.environment))

    /**
     * Static resolution plus the time model: states applied, every timeline playing at its time
     * evaluated and patched onto the tree, then layout, attachments and routing. [pins] impose
     * placements for a transition frame; [applied] reuses a state application already computed.
     */
    internal fun raw(doc: Value.Map, sourceMap: SourceMap?, options: ResolveOptions, pins: Map<String, Pin> = emptyMap(), applied: StateApplication? = null): Raw {
        val sink = DiagnosticSink(sourceMap)
        val vp = doc["viewport"] as Value.Map
        val w = (vp["width"] as Value.Num).value
        val h = (vp["height"] as Value.Num).value
        // expansion diagnostics are the validator's to report
        val app = applied ?: StateResolver.apply(doc, options.environment, options.states, sink)
        val (tree, camera) = TimePlayer.play(app, options)
        val index = NodeIndex(tree)
        val engine = Engine(sink, w, h, options, index, pins)
        val rootInfo = index.root
        val rootNode = rootInfo?.let { engine.build(it, null) }
        val root = rootNode?.let { Finisher(engine, sink).finish(it, isRoot = true) } ?: emptyRoot()
        val view = Cameras.resolve(camera, root, w, h, sink)
        val scene = ResolvedScene(CoreSchema.FORMAT, w, h, options.time, root, engine.background(), app.current, view, layerOptions(rootInfo?.node), engine.clips.toList())
        return Raw(scene, app, index, sink.all, doc, sourceMap, options)
    }

    private fun layerOptions(root: Value.Map?): Map<String, LayerOption> =
        (root?.get("layers") as? Value.List)?.items.orEmpty().mapNotNull { l ->
            val m = l as? Value.Map ?: return@mapNotNull null
            val name = (m["name"] as? Value.Str)?.value ?: return@mapNotNull null
            name to LayerOption((m["space"] as? Value.Str)?.value == "screen", (m["parallax"] as? Value.Num)?.value ?: 1.0)
        }.toMap()

    /**
     * The transition from the states [from] to the states [to] (spec section 13): play it with
     * [Transition.frame]. [start] is the scene on screen when it starts, if the previous transition was
     * interrupted: its in-flight values become the source.
     */
    fun transition(processed: ProcessResult, from: Map<String, String>, to: Map<String, String>, options: ResolveOptions = ResolveOptions()): TransitionResult {
        val o = options.copy(environment = processed.environment)
        val doc = processed.canonical as? Value.Map ?: return TransitionResult(null, emptyList(), emptyList())
        val a = raw(doc, processed.sourceMap, o.copy(states = from))
        val b = raw(doc, processed.sourceMap, o.copy(states = to))
        val diagnostics = (a.diagnostics + b.diagnostics).distinct()
        return TransitionResult(Transition(a, b), diagnostics, b.applied.containers)
    }

    /**
     * A frame of the transition from the states [from] to the states [to], [time] seconds after it
     * starts. [ResolveResult.containers] is the target's.
     */
    fun resolveTransition(processed: ProcessResult, from: Map<String, String>, to: Map<String, String>, time: Double, options: ResolveOptions = ResolveOptions()): ResolveResult {
        val r = transition(processed, from, to, options)
        val t = r.transition ?: return ResolveResult(null, r.diagnostics, r.containers)
        return ResolveResult(t.frame(time), (r.diagnostics + t.diagnostics).distinct(), r.containers)
    }

    /** How long the scene's timelines run in the given states (the longest; one loop of a looping one). */
    fun timelineDuration(processed: ProcessResult, options: ResolveOptions = ResolveOptions()): Double {
        val doc = processed.canonical as? Value.Map ?: return 0.0
        val app = StateResolver.apply(doc, processed.environment, options.states, DiagnosticSink())
        val index = NodeIndex(app.tree)
        val own = app.states.mapNotNull { s -> s.state.timeline?.let { diagrams.processor.TimelineCompiler(processed.environment, DiagnosticSink(), MediaClock(index, s.node, options.manifest)).timeline(it, DataPath.ROOT, "timeline").duration } }
        // media clips count too: a narrated piece lasts as long as its narration
        val media = index.all().mapNotNull { info -> MediaClock(index, info.parent, options.manifest).takeIf { info.id != null }?.span(info.id!!)?.second }.toList()
        return (own + media + TimePlayer.duration(app, processed.environment, options.manifest)).maxOrNull() ?: 0.0
    }

    /** Duration and easing of a change of states: those of the outermost container that changes. */
    fun timing(containers: List<ContainerInfo>, from: Map<String, String>, to: Map<String, String>): Pair<Double, Easing> {
        val changed = containers.filter { from[it.owner] != to[it.owner] }.minByOrNull { it.depth } ?: return 0.0 to Easing.LINEAR
        val (d, e) = changed.container.timing(from[changed.owner], to.getValue(changed.owner))
        return d to Easing.of(e)
    }

    private fun emptyRoot() = ResolvedNode("scene", "scene", "container", "default", Box.EMPTY, Matrix.IDENTITY, Matrix.IDENTITY, Box.EMPTY, 1.0, Paint(null, null, 0.0), Geometry.Group, emptyList())
}

class TransitionResult(val transition: Transition?, val diagnostics: List<Diagnostic>, val containers: List<ContainerInfo>)

/** Turns the engine's nodes into the read-only resolved scene, children in paint order. */
private class Finisher(private val e: Engine, private val sink: DiagnosticSink) {
    fun finish(n: RNode, isRoot: Boolean): ResolvedNode {
        val transform = e.placement(n)
        val world = e.world(n)
        val order = layerOrder(n.node)
        val children = n.children.withIndex()
            .sortedWith(compareBy({ layerIndex(order, it.value) }, { it.index }))
            .map { finish(it.value, false) }
        val paint = Paint(e.color(n, "fill"), e.color(n, "stroke"), e.num(n, "stroke_width", 0.0))
        val local: Box
        val geometry: Geometry
        var p = paint
        when (n.kind) {
            "container" -> {
                local = e.finalBox(n)
                geometry = Geometry.Group
                p = Paint(null, null, 0.0)
            }
            "rect" -> {
                local = e.finalBox(n)
                geometry = Geometry.Rect(local.width, local.height, e.num(n, "corner_radius", 0.0))
            }
            "ellipse", "circle" -> {
                local = e.finalBox(n)
                geometry = Geometry.Ellipse(local.centerX, local.centerY, local.width / 2, local.height / 2)
            }
            "line", "polyline", "polygon" -> {
                val pts = e.points(n)
                local = Box.of(pts)
                geometry = Geometry.Poly(pts, n.kind == "polygon")
                if (n.kind != "polygon") p = p.copy(fill = null)
            }
            "path" -> {
                val segs = e.pathSegments(n)
                local = PathData.bounds(segs)
                geometry = Geometry.PathGeom(segs)
            }
            "connector" -> {
                val sw = e.num(n, "stroke_width", 1.0)
                val mk = Markers.apply(e.routeInParent(n), e.text(n, "marker_start"), e.text(n, "marker_end"), sw)
                local = Box.of(mk.points + mk.markers.flatMap { it.points })
                geometry = Geometry.Poly(mk.points, false, mk.markers)
                p = p.copy(fill = null)
            }
            "image", "video" -> {
                local = e.finalBox(n)
                val i = e.intrinsic(n)
                val clip = if (n.kind == "video") e.mediaClip(n, n.info.id?.let { n.path }) else null
                val t = clip?.let { c -> (c.at(e.time) ?: if (e.time < c.start) c.from else c.to ?: c.from) }
                geometry = Geometry.Media(n.kind, e.text(n, "asset") ?: "", local.width, local.height, i?.let { Box(0.0, 0.0, it.first, it.second) }, e.text(n, "fit") ?: "contain", t)
                p = Paint(null, null, 0.0)
            }
            "audio" -> {
                local = Box.EMPTY
                geometry = Geometry.Group
                p = Paint(null, null, 0.0)
                e.mediaClip(n, n.info.id?.let { n.path })
            }
            else -> { // text
                val t = e.measure(n)
                local = e.finalBox(n)
                val lh = t.m.lineHeight(t.fontSize)
                val asc = t.m.ascent(t.fontSize)
                val desc = t.m.descent(t.fontSize)
                val lines = t.lines.mapIndexed { i, s ->
                    val x = when (t.align) {
                        "center" -> (local.width - t.widths[i]) / 2
                        "end" -> local.width - t.widths[i]
                        else -> 0.0
                    }
                    TextLine(s, x, i * lh + (lh - asc - desc) / 2 + asc, t.widths[i])
                }
                geometry = Geometry.Text(lines, t.fontSize, t.font)
                p = Paint(paint.fill, null, 0.0)
            }
        }
        return ResolvedNode(
            ref = if (isRoot) "scene" else if (n.info.id != null) n.path else null,
            loc = n.info.loc,
            kind = n.kind,
            layer = if (isRoot) "default" else layerOf(n.node),
            local = local,
            transform = transform,
            world = world,
            box = world.apply(local),
            opacity = e.num(n, "opacity", 1.0),
            paint = p,
            geometry = geometry,
            children = children,
            template = n.info.template?.ref,
            draw = e.num(n, "draw", 1.0).coerceIn(0.0, 1.0),
        )
    }

    private fun layerOf(node: Value.Map): String = (node["layer"] as? Value.Str)?.value ?: "default"

    private fun layerOrder(node: Value.Map): List<String> =
        (node["layers"] as? Value.List)?.items.orEmpty().mapNotNull {
            when (it) {
                is Value.Str -> it.value
                is Value.Map -> (it["name"] as? Value.Str)?.value
                else -> null
            }
        }

    /** `default` paints first unless the context lists it elsewhere (spec section 8). */
    private fun layerIndex(order: List<String>, c: RNode): Int {
        val layer = layerOf(c.node)
        val i = order.indexOf(layer)
        if (i >= 0) return i + 1
        if (layer == "default") return 0
        sink.warning(Codes.PATH_UNRESOLVED, c.path, "layer '$layer' is not declared on the parent; the node paints in 'default'", c.dp.key("layer"))
        return 0
    }
}

// ---- serialization ----

fun ResolvedScene.toValue(): Value {
    val out = linkedMapOf<String, Value>(
        "format" to Value.Str(format),
        "viewport" to Value.map("width" to Value.Num(width), "height" to Value.Num(height)),
        "time" to Value.Num(time),
    )
    background?.let { out["background"] = Value.Str(it) }
    if (states.isNotEmpty()) out["states"] = Value.Map(states.mapValuesTo(LinkedHashMap()) { Value.Str(it.value) })
    if (media.isNotEmpty()) out["media"] = Value.List(
        media.map { c ->
            Value.Map(
                linkedMapOf<String, Value>("kind" to Value.Str(c.kind), "asset" to Value.Str(c.asset), "start" to Value.Num(c.start), "in" to Value.Num(c.from)).apply {
                    c.to?.let { put("out", Value.Num(it)) }
                    put("volume", Value.Num(c.volume))
                    put("rate", Value.Num(c.rate))
                    c.ref?.let { put("path", Value.Str(it)) }
                },
            )
        },
    )
    camera?.let { c ->
        out["camera"] = Value.map("center" to c.center.toValue(), "zoom" to Value.Num(c.zoom), "rotation" to Value.Num(c.rotation), "into" to c.into.toValue())
    }
    out["root"] = root.toValue()
    return Value.Map(out)
}

private fun nums(vararg d: Double) = Value.List(d.map { Value.Num(it) })
private fun Box.toValue() = nums(x, y, width, height)
private fun Point.toValue() = nums(x, y)

fun ResolvedNode.toValue(): Value {
    val out = LinkedHashMap<String, Value>()
    out["path"] = ref?.let { Value.Str(it) } ?: Value.Null
    out["loc"] = Value.Str(loc)
    out["kind"] = Value.Str(kind)
    template?.let { out["template"] = Value.Str(it) }
    out["layer"] = Value.Str(layer)
    out["box"] = box.toValue()
    out["local"] = local.toValue()
    out["transform"] = Value.List(transform.toList().map { Value.Num(it) })
    out["opacity"] = Value.Num(opacity)
    if (draw != 1.0) out["draw"] = Value.Num(draw)
    paint.fill?.let { out["fill"] = Value.Str(it) }
    paint.stroke?.let {
        out["stroke"] = Value.Str(it)
        out["stroke_width"] = Value.Num(paint.strokeWidth)
    }
    when (val g = geometry) {
        Geometry.Group -> {}
        is Geometry.Rect -> if (g.cornerRadius != 0.0) out["corner_radius"] = Value.Num(g.cornerRadius)
        is Geometry.Ellipse -> {}
        is Geometry.Poly -> {
            out["points"] = Value.List(g.points.map { it.toValue() })
            if (g.markers.isNotEmpty()) {
                out["markers"] = Value.List(g.markers.map { m -> Value.map("kind" to Value.Str(m.kind), "points" to Value.List(m.points.map { it.toValue() })) })
            }
        }
        is Geometry.PathGeom -> out["segments"] = Value.List(
            g.segments.map { s ->
                when (s) {
                    is Segment.Move -> Value.list(Value.Str("M"), Value.Num(s.p.x), Value.Num(s.p.y))
                    is Segment.Line -> Value.list(Value.Str("L"), Value.Num(s.p.x), Value.Num(s.p.y))
                    is Segment.Cubic -> Value.List(listOf(Value.Str("C")) + listOf(s.c1, s.c2, s.p).flatMap { listOf(Value.Num(it.x), Value.Num(it.y)) })
                    is Segment.Quad -> Value.List(listOf(Value.Str("Q")) + listOf(s.c, s.p).flatMap { listOf(Value.Num(it.x), Value.Num(it.y)) })
                    Segment.Close -> Value.list(Value.Str("Z"))
                }
            },
        )
        is Geometry.Media -> {
            out["asset"] = Value.Str(g.asset)
            out["fit"] = Value.Str(g.fit)
            g.time?.let { out["media_time"] = Value.Num(it) }
        }
        is Geometry.Text -> {
            g.font?.let { out["font"] = Value.Str(it) }
            out["font_size"] = Value.Num(g.fontSize)
            out["lines"] = Value.List(
                g.lines.map { Value.map("text" to Value.Str(it.text), "x" to Value.Num(it.x), "baseline" to Value.Num(it.baseline), "width" to Value.Num(it.width)) },
            )
        }
    }
    if (children.isNotEmpty() || kind == "container") out["children"] = Value.List(children.map { it.toValue() })
    return Value.Map(out)
}

/** Compact text dump, one line per node (spec section 18, "Scene dump"). */
fun ResolvedScene.dump(): String {
    val sb = StringBuilder()
    val rows = mutableListOf<Pair<String, String>>()
    fun f(d: Double) = diagrams.model.formatNumber(d)
    root.walk { n ->
        val b = n.box
        val extra = when (val g = n.geometry) {
            is Geometry.Text -> "  text=" + diagrams.model.JsonWriter.string(g.lines.joinToString("\\n") { it.text })
            is Geometry.Poly -> if (n.kind == "connector") "  path=" + g.points.joinToString("->") { n.world.apply(it).let { p -> "(${f(p.x)},${f(p.y)})" } } else ""
            else -> ""
        }
        rows += n.label to "${(n.template ?: n.kind).padEnd(9)} box=(${f(b.x)},${f(b.y)},${f(b.width)},${f(b.height)})  state=${(if (n.ref != null) states[n.ref] else null) ?: "-"}$extra"
    }
    val w = rows.maxOf { it.first.length } + 2
    for ((label, rest) in rows) sb.append(label.padEnd(w)).append(rest).append('\n')
    return sb.toString()
}

package diagrams.resolver

import diagrams.model.DataPath
import diagrams.model.ExprEvalException
import diagrams.model.ExprSyntaxException
import diagrams.model.Exprs
import diagrams.model.Value
import diagrams.processor.CompiledTimeline
import diagrams.processor.CoreSchema
import diagrams.processor.ENode
import diagrams.processor.NodeIndex
import diagrams.processor.NodeInfo
import diagrams.processor.TrackDef
import diagrams.processor.resolveSetKey

/** A timeline playing at [time] seconds of its own clock, with paths written from [scope] (null: the root). */
internal class Playing(val timeline: CompiledTimeline, val scope: NodeInfo?, val time: Double)

/**
 * The track engine (spec section 12). At a given time, every track gives a value for its property;
 * when several drive one property at once, numbers add up as deltas from the base value (`scale`
 * multiplies), and other values go to the highest source: a raw track, then a clip, then a state.
 * Outside its span a track holds its first or last value; a property no track spans at that moment
 * keeps the value of the track that ended last, or else the first value of the next one to start.
 */
internal object Animator {
    class Driven(val node: NodeInfo?, val property: List<String>, val value: Value)

    private class Bound(val track: TrackDef, val time: Double, val node: NodeInfo?, val property: List<String>, val along: NodeInfo?)

    /** The value of every driven property, ready to patch onto the tree and the camera. */
    fun evaluate(playing: List<Playing>, index: NodeIndex, tree: ENode?, camera: Value.Map?, tokens: Map<String, Value>): List<Driven> {
        val bound = mutableListOf<Bound>()
        val nodes = HashMap<DataPath, ENode>()
        tree?.let { fun walk(e: ENode) { nodes[e.dp] = e; e.children.forEach(::walk) }; walk(it) }
        for (p in playing) {
            val tl = p.timeline
            val t = if (tl.loop && tl.duration > 0) ((p.time % tl.duration) + tl.duration) % tl.duration else p.time
            for (tr in tl.tracks) {
                if (tr.target.startsWith("camera.")) {
                    bound += Bound(tr, t, null, tr.target.removePrefix("camera.").split('.'), null)
                    continue
                }
                val from = p.scope ?: index.root ?: continue
                val target = resolveSetKey(tr.target, from, index) ?: continue
                if (target.property.isEmpty()) continue
                val along = tr.along?.let { a -> index.resolve(a, from)?.node?.takeIf { it.shape == "connector" } }
                if (tr.along != null && along == null) continue
                bound += Bound(tr, t, target.node, target.property, along)
            }
        }
        val out = mutableListOf<Driven>()
        for ((_, group) in bound.groupBy { (it.node?.dp?.toString() ?: "camera") + "#" + it.property.joinToString(".") }) {
            val first = group.first()
            val base = if (first.node == null) camera?.let { StateResolver.getProp(it, first.property) } else nodes[first.node.dp]?.node?.let { StateResolver.getProp(it, first.property) }
            val alongs = group.filter { it.along != null }
            if (alongs.isNotEmpty()) {
                // a path track places the node: the latest-starting one that has begun wins
                val b = pick(alongs)
                val f = (value(b.track, b.time, tokens) as? Value.Num)?.value ?: continue
                out += Driven(first.node, listOf("\$along"), Value.map("loc" to Value.Str(b.along!!.loc), "t" to Value.Num(f)))
                continue
            }
            val active = group.filter { it.time >= it.track.start && it.time <= it.track.end }
            val v = when {
                active.isEmpty() -> value(pick(group).track, pick(group).time, tokens)
                active.size == 1 -> value(active[0].track, active[0].time, tokens)
                else -> combine(active.map { it.track to value(it.track, it.time, tokens) }, base, first.property.last() == "scale")
            }
            out += Driven(first.node, first.property, v)
        }
        return out
    }

    /** Outside every span: the track that ended last, else the one that starts first. */
    private fun pick(group: List<Bound>): Bound {
        val t = group.first().time
        val ended = group.filter { it.track.end < t }
        return ended.maxWithOrNull(compareBy({ it.track.end }, { it.track.order })) ?: group.minWith(compareBy({ it.track.start }, { -it.track.order }))
    }

    private fun combine(values: List<Pair<TrackDef, Value>>, base: Value?, multiply: Boolean): Value {
        val b = (base as? Value.Num)?.value
        if (b != null && values.all { it.second is Value.Num }) {
            val xs = values.map { (it.second as Value.Num).value }
            return Value.Num(if (multiply) (if (b == 0.0) xs.last() else b * xs.fold(1.0) { acc, x -> acc * (x / b) }) else b + xs.sumOf { it - b })
        }
        val bm = base as? Value.Map
        if (bm != null && values.all { (it.second as? Value.Map)?.entries?.values?.all { c -> c is Value.Num } == true }) {
            return Value.Map(
                bm.entries.mapValuesTo(LinkedHashMap()) { (k, bv) ->
                    val bn = (bv as? Value.Num)?.value ?: return@mapValuesTo bv
                    Value.Num(bn + values.sumOf { (((it.second as Value.Map)[k] as? Value.Num)?.value ?: bn) - bn })
                },
            )
        }
        return values.maxWith(compareBy({ it.first.source.ordinal }, { it.first.order })).second
    }

    /** A track's value at [t]: held before its first key and after its last, interpolated between. */
    fun value(tr: TrackDef, t: Double, tokens: Map<String, Value>): Value {
        val keys = tr.keys
        if (t <= keys.first().at) return literal(keys.first().value, tokens)
        if (t >= keys.last().at) return literal(keys.last().value, tokens)
        val i = keys.indexOfLast { it.at <= t }
        val a = keys[i]
        val b = keys[i + 1]
        val u = if (b.at == a.at) 1.0 else (t - a.at) / (b.at - a.at)
        val e = Easing.of(b.ease ?: tr.ease)(u)
        return interpolate(literal(a.value, tokens), literal(b.value, tokens), e)
    }

    /** Numbers, colors and points interpolate; anything else steps at the next key. */
    fun interpolate(a: Value, b: Value, u: Double): Value = when {
        a is Value.Num && b is Value.Num -> Value.Num(a.value + (b.value - a.value) * u)
        a is Value.Str && b is Value.Str && CoreSchema.COLOR_PATTERN.matches(a.value) && CoreSchema.COLOR_PATTERN.matches(b.value) ->
            Value.Str(Transitions.color(a.value.lowercase(), b.value.lowercase(), u)!!)
        a is Value.Map && b is Value.Map && a.keys == b.keys && a.entries.values.all { it is Value.Num } && b.entries.values.all { it is Value.Num } ->
            Value.Map(a.entries.mapValuesTo(LinkedHashMap()) { (k, x) -> interpolate(x, b[k]!!, u) })
        else -> if (u >= 1.0) b else a
    }

    /** A key value written as an expression: its token references are read from the root theme. */
    private fun literal(v: Value, tokens: Map<String, Value>): Value {
        if (v !is Value.Map) return v
        val e = try {
            Exprs.fromCanonical(v)
        } catch (_: ExprSyntaxException) {
            null
        } ?: return v
        return try {
            Exprs.eval(e) { ref ->
                val parts = ref.split('.')
                if (parts.size == 2 && parts[0] == "theme") tokens[parts[1]] ?: throw ExprEvalException("no token '${parts[1]}'") else throw ExprEvalException("'$ref' cannot be read in a keyframe")
            }
        } catch (_: ExprEvalException) {
            v
        }
    }
}

/**
 * Plays the timelines of a resolution (spec section 12): every node's `timeline` on the scene clock,
 * and the timeline of each container's current state on a clock that starts when the state was
 * entered. Their values are patched onto the expanded tree and the camera before layout, so
 * attachments and connectors follow what the tracks move.
 */
internal object TimePlayer {
    fun play(app: StateApplication, options: ResolveOptions): Pair<ENode?, Value.Map?> {
        val tree = app.tree ?: return null to app.camera
        val index = NodeIndex(tree)
        val env = options.environment
        val sink = diagrams.model.DiagnosticSink()
        fun compile(v: Value, scope: NodeInfo?) = diagrams.processor.TimelineCompiler(env, sink, MediaClock(index, scope, options.manifest)).timeline(v, DataPath.ROOT, "timeline")
        val playing = mutableListOf<Playing>()
        for (info in index.all()) info.node["timeline"]?.let { playing += Playing(compile(it, info), info, options.time) }
        for (s in app.states) s.state.timeline?.let { playing += Playing(compile(it, s.node), s.node, options.time - (options.entered[s.owner] ?: 0.0)) }
        if (playing.isEmpty()) return tree to app.camera
        val driven = Animator.evaluate(playing, index, tree, app.camera, env.rootTokens)
        var camera = app.camera
        val patches = LinkedHashMap<DataPath, MutableList<Pair<List<String>, Value>>>()
        for (d in driven) {
            if (d.node == null) {
                camera = StateResolver.setProp(camera ?: Value.Map(emptyMap()), d.property, d.value)
            } else {
                patches.getOrPut(d.node.dp) { mutableListOf() } += d.property to d.value
            }
        }
        return StateResolver.patch(tree, patches) to camera
    }

    /** The duration of the scene's timelines on the scene clock (the longest; one loop of a looping one). */
    fun duration(app: StateApplication, env: diagrams.processor.Environment, manifest: diagrams.assets.AssetManifest): Double {
        val tree = app.tree ?: return 0.0
        val sink = diagrams.model.DiagnosticSink()
        val index = NodeIndex(tree)
        return index.all().mapNotNull { info -> info.node["timeline"]?.let { info to it } }
            .maxOfOrNull { (info, t) -> diagrams.processor.TimelineCompiler(env, sink, MediaClock(index, info, manifest)).timeline(t, DataPath.ROOT, "timeline").duration } ?: 0.0
    }
}

/**
 * Audio and video as timing references (spec section 15): `with: narration` spans the node's clip
 * on the scene clock, and `at: narration.cue[intro]` is a cue point of its asset, both from the
 * node's `start`, `in`, `out` and `rate`, and the manifest's duration and cues.
 */
internal class MediaClock(private val index: NodeIndex, private val scope: NodeInfo?, private val manifest: diagrams.assets.AssetManifest) : diagrams.processor.MediaTiming {
    private fun node(ref: String): Value.Map? {
        val from = scope ?: index.root ?: return null
        val m = index.resolve(ref, from) ?: return null
        if (m.rest.isNotEmpty()) return null
        return m.node.node.takeIf { (it["media"] as? Value.Str)?.value in setOf("audio", "video") }
    }

    private fun num(m: Value.Map, k: String, d: Double) = (m[k] as? Value.Num)?.value ?: d

    override fun span(ref: String): Pair<Double, Double>? {
        val m = node(ref) ?: return null
        val asset = (m["asset"] as? Value.Str)?.value ?: return null
        val start = num(m, "start", 0.0)
        val from = num(m, "in", 0.0)
        val to = (m["out"] as? Value.Num)?.value ?: manifest.media(asset)?.duration ?: from
        return start to start + (to - from) / num(m, "rate", 1.0)
    }

    override fun cue(ref: String, cue: String): Double? {
        val m = node(ref) ?: return null
        val asset = (m["asset"] as? Value.Str)?.value ?: return null
        val c = manifest.media(asset)?.cues?.get(cue) ?: return null
        return num(m, "start", 0.0) + (c - num(m, "in", 0.0)) / num(m, "rate", 1.0)
    }
}

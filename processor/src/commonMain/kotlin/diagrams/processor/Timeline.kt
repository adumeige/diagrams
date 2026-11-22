package diagrams.processor

import diagrams.model.Codes
import diagrams.model.DataPath
import diagrams.model.DiagnosticSink
import diagrams.model.Value
import diagrams.model.typeName

/** Where a track comes from. Non-summable overlaps go to the highest (spec section 12, "Blending"). */
enum class TrackSource { STATE, CLIP, RAW }

/** One keyframe: a time local to the track's start, a value, and the easing into it. */
class TrackKey(val at: Double, val value: Value, val ease: Value?)

/**
 * A compiled track: a property path written from the timeline's owner (`pulse.scale`, `camera.zoom`),
 * keys at absolute times of its timeline, and where it came from. [along] names a connector: the
 * track's values are then fractions of that connector's route, and the track places the node on it.
 */
class TrackDef(
    val target: String,
    val keys: List<TrackKey>,
    val ease: Value?,
    val source: TrackSource,
    val order: Int,
    val along: String?,
    val dp: DataPath,
    val path: String,
) {
    val start: Double get() = keys.first().at
    val end: Double get() = keys.last().at
}

/** The span an `{auto: all}` item gives the automatic diff in a transition body (spec section 13). */
class AutoSpan(val start: Double, val duration: Double, val ease: Value?)

class CompiledTimeline(val tracks: List<TrackDef>, val autos: List<AutoSpan>, val duration: Double, val loop: Boolean) {
    companion object {
        val EMPTY = CompiledTimeline(emptyList(), emptyList(), 0.0, false)
    }
}

/**
 * Time references to media (spec section 15): `with: narration` spans an audio node, and
 * `at: narration.cue[step_2]` starts at one of its cue points. Times are on the timeline being compiled.
 */
interface MediaTiming {
    /** Start and end of the media node [ref]; null when [ref] names no media node. */
    fun span(ref: String): Pair<Double, Double>?

    /** Time of cue [cue] of media node [ref]; null when unknown. */
    fun cue(ref: String, cue: String): Double?

    companion object {
        val NONE = object : MediaTiming {
            override fun span(ref: String): Pair<Double, Double>? = null
            override fun cue(ref: String, cue: String): Double? = null
        }
    }
}

/** Vocabulary of time items (spec section 12). */
object Timelines {
    val KINDS = listOf("track", "sequence", "parallel", "stagger", "wait", "use", "auto")
    val TIMING = setOf("id", "at", "after", "with")
    private val OWN_KEYS = mapOf(
        "track" to setOf("track", "target", "keys", "ease", "along"),
        "sequence" to setOf("sequence"),
        "parallel" to setOf("parallel"),
        "stagger" to setOf("stagger", "interval"),
        "wait" to setOf("wait"),
        "auto" to setOf("auto", "duration", "ease"),
    )
    val KEY_KEYS = setOf("at", "value", "ease")
    private val CUE = Regex("^([a-z][a-z0-9_.]*)\\.cue\\[([a-z0-9_]+)\\]$")

    /** True for a map that is a time item rather than a node: what makes a template a clip. */
    fun isTimeItem(v: Value): Boolean = v is Value.Map && v["shape"] == null && v["layout"] == null && v["children"] == null &&
        listOf("track", "sequence", "parallel", "stagger", "wait").any { v[it] != null }

    fun ownKeys(kind: String) = OWN_KEYS[kind].orEmpty()

    /** `narration.cue[step_2]` to (`narration`, `step_2`). */
    fun cueRef(s: String): Pair<String, String>? = CUE.find(s)?.let { it.groupValues[1] to it.groupValues[2] }
}

/**
 * Compiles a timeline (spec section 12) into tracks with absolute key times. Times are local to the
 * timeline: a clip's keys are offset by where the clip starts, and so on down the containers.
 *
 * Structural problems go to [sink]; the validator compiles every timeline once for them, and the
 * resolver compiles again with a sink it discards.
 *
 * [auto] is the default duration and easing of `{auto: all}`, which is only allowed when compiling a
 * transition body. [implicitTarget] fills a clip's missing `target` parameter: an `enter` or `exit`
 * clip acts on its own node.
 */
class TimelineCompiler(
    private val env: Environment,
    private val sink: DiagnosticSink,
    private val media: MediaTiming = MediaTiming.NONE,
    private val auto: Pair<Double, Value>? = null,
    private val implicitTarget: String? = null,
    private val baseSource: TrackSource = TrackSource.RAW,
) {
    private val tracks = mutableListOf<TrackDef>()
    private val autos = mutableListOf<AutoSpan>()
    private var order = 0
    private val expander = Expander(env, DiagnosticSink())

    private fun err(path: String, msg: String, dp: DataPath) = sink.error(Codes.SCHEMA, path, msg, dp)

    /** A whole timeline: one time item, optionally with `loop`. */
    fun timeline(v: Value, dp: DataPath, path: String): CompiledTimeline {
        val m = v as? Value.Map
        if (m == null) {
            err(path, "a timeline is a time item: track, sequence, parallel, stagger, wait or a clip", dp)
            return CompiledTimeline.EMPTY
        }
        val loop = when (val l = m["loop"]) {
            null -> false
            is Value.Bool -> l.value
            else -> {
                err("$path.loop", "'loop' is true or false", dp.key("loop"))
                false
            }
        }
        for (k in Timelines.TIMING) if (m[k] != null) err("$path.$k", "'$k' places an item among its siblings; a timeline itself starts at 0", dp.key(k))
        val item = Value.Map(m.entries.filterKeys { it != "loop" && it !in Timelines.TIMING })
        val d = item(item, 0.0, baseSource, dp, path, null, emptyList())
        return CompiledTimeline(tracks.toList(), autos.toList(), d, loop)
    }

    /** Compiles one item starting at [start]; returns its duration. */
    private fun item(v: Value, start: Double, source: TrackSource, dp: DataPath, path: String, span: Double?, clips: List<String>): Double {
        val m = v as? Value.Map
        if (m == null) {
            err(path, "a time item is a map, found ${v.typeName()}", dp)
            return 0.0
        }
        val kinds = Timelines.KINDS.filter { m[it] != null }
        if (kinds.size != 1) {
            err(path, "a time item has exactly one of ${Timelines.KINDS.joinToString()}; found ${kinds.joinToString().ifEmpty { "none" }}", dp)
            return 0.0
        }
        val kind = kinds[0]
        if (kind != "use") {
            val allowed = Timelines.TIMING + Timelines.ownKeys(kind)
            for (k in m.keys) if (k !in allowed) err("$path.$k", "unknown key '$k' in a $kind item; expected one of ${allowed.joinToString()}", dp.key(k))
        }
        return when (kind) {
            "track" -> track(m, start, source, dp, path)
            "wait" -> nonNegative(m["wait"], dp.key("wait"), "$path.wait", "'wait' is a number of seconds") ?: 0.0
            "sequence", "parallel", "stagger" -> {
                val interval = if (kind == "stagger") {
                    nonNegative(m["interval"], dp.key("interval"), "$path.interval", "a stagger needs 'interval': seconds between starts") ?: 0.0
                } else {
                    0.0
                }
                children(m[kind]!!, kind, start, interval, source, dp.key(kind), "$path.$kind", clips)
            }
            "auto" -> {
                if (auto == null) {
                    err("$path.auto", "'auto' stands for the automatic transition and is only allowed in a transition body", dp.key("auto"))
                    return 0.0
                }
                if ((m["auto"] as? Value.Str)?.value != "all") err("$path.auto", "'auto' takes 'all' in 0.1", dp.key("auto"))
                val d = m["duration"]?.let { nonNegative(it, dp.key("duration"), "$path.duration", "'duration' is a number of seconds") } ?: auto.first
                autos += AutoSpan(start, d, m["ease"] ?: auto.second)
                d
            }
            else -> clip(m, start, dp, path, span, clips)
        }
    }

    private fun nonNegative(x: Value?, dp: DataPath, path: String, msg: String): Double? {
        val n = (x as? Value.Num)?.value
        if (n == null || n < 0) {
            err(path, msg, dp)
            return null
        }
        return n
    }

    private fun track(m: Value.Map, start: Double, source: TrackSource, dp: DataPath, path: String): Double {
        val prop = (m["track"] as? Value.Str)?.value
        if (prop == null || prop.isEmpty()) {
            err("$path.track", "'track' is a property path, such as 'card.opacity'", dp.key("track"))
            return 0.0
        }
        val target = when (val t = m["target"]) {
            null -> null
            is Value.Str -> t.value
            else -> {
                err("$path.target", "'target' is a node path", dp.key("target"))
                null
            }
        }
        val along = when (val a = m["along"]) {
            null -> null
            is Value.Str -> a.value
            else -> {
                err("$path.along", "'along' is the path of a connector", dp.key("along"))
                null
            }
        }
        val keys = m["keys"] as? Value.List
        if (keys == null || keys.items.isEmpty()) {
            err(path, "a track needs 'keys': a list of {at, value}", dp)
            return 0.0
        }
        val out = mutableListOf<TrackKey>()
        keys.items.forEachIndexed { i, k ->
            val kdp = dp.key("keys").index(i)
            val km = k as? Value.Map
            if (km == null) return@forEachIndexed err("$path.keys", "a key is a map {at, value, ease}", kdp)
            for (x in km.keys) if (x !in Timelines.KEY_KEYS) err("$path.keys", "unknown key '$x' in a keyframe; expected at, value, ease", kdp.key(x))
            val at = nonNegative(km["at"], kdp.key("at"), "$path.keys", "a keyframe's 'at' is a number of seconds from the start of its track") ?: return@forEachIndexed
            val value = km["value"] ?: return@forEachIndexed err("$path.keys", "a keyframe needs a 'value'", kdp)
            if (out.isNotEmpty() && start + at < out.last().at) err("$path.keys", "keyframes are listed in time order", kdp.key("at"))
            out += TrackKey(start + at, value, km["ease"])
        }
        if (out.isEmpty()) return 0.0
        val full = if (!target.isNullOrEmpty()) "$target.$prop" else prop
        tracks += TrackDef(full, out, m["ease"], source, order++, along, dp, path)
        return out.last().at - start
    }

    /** Children of a container: each starts by its timing keys, else by the container's rule. */
    private fun children(v: Value, mode: String, start: Double, interval: Double, source: TrackSource, dp: DataPath, path: String, clips: List<String>): Double {
        val list = v as? Value.List
        if (list == null) {
            err(path, "'$mode' is a list of time items", dp)
            return 0.0
        }
        val items = list.items
        val ids = HashMap<String, Int>()
        items.forEachIndexed { i, x ->
            ((x as? Value.Map)?.get("id") as? Value.Str)?.value?.let { id ->
                if (!CoreSchema.ID_PATTERN.matches(id)) err("$path.$id", "an item id matches [a-z][a-z0-9_]*", dp.index(i).key("id"))
                if (ids.put(id, i) != null) err("$path.$id", "duplicate item id '$id'", dp.index(i).key("id"))
            }
        }
        val spans = arrayOfNulls<Pair<Double, Double>>(items.size)
        val visiting = BooleanArray(items.size)
        fun place(i: Int): Pair<Double, Double> {
            spans[i]?.let { return it }
            val idp = dp.index(i)
            if (visiting[i]) {
                sink.error(Codes.TIMING_CYCLE, path, "the timing references of this container form a loop", idp)
                return start to start
            }
            visiting[i] = true
            val m = items[i] as? Value.Map
            var hint: Double? = null
            fun ref(key: String, end: Boolean): Double? {
                val r = (m?.get(key) as? Value.Str)?.value ?: run {
                    err("$path.$key", "'$key' names a sibling item or a media node", idp.key(key))
                    return null
                }
                ids[r]?.let { j ->
                    val s = place(j)
                    if (key == "with") hint = s.second - s.first
                    return if (end) s.second else s.first
                }
                val s = media.span(r)
                if (s == null) {
                    sink.error(Codes.PATH_UNRESOLVED, "$path.$key", "'$r' is neither an item of this container nor a media node", idp.key(key))
                    return null
                }
                if (key == "with") hint = s.second - s.first
                return if (end) s.second else s.first
            }
            val at = m?.get("at")
            val s = when {
                at is Value.Num -> start + at.value
                at is Value.Str -> {
                    val cue = Timelines.cueRef(at.value)
                    val t = cue?.let { (r, c) -> media.cue(r, c) }
                    if (t == null) {
                        if (cue == null) err("$path.at", "'at' is seconds from the container's start, or a cue such as 'narration.cue[intro]'", idp.key("at"))
                        else sink.error(Codes.PATH_UNRESOLVED, "$path.at", "no cue '${cue.second}' on media node '${cue.first}'", idp.key("at"))
                    }
                    t ?: start
                }
                at != null -> {
                    err("$path.at", "'at' is seconds from the container's start, or a cue such as 'narration.cue[intro]'", idp.key("at"))
                    start
                }
                m?.get("after") != null -> ref("after", end = true) ?: start
                m?.get("with") != null -> ref("with", end = false) ?: start
                mode == "sequence" -> if (i == 0) start else place(i - 1).second
                mode == "stagger" -> start + i * interval
                else -> start
            }
            if (m?.get("with") != null && at != null) ref("with", end = false)
            val d = item(items[i], s, source, idp, path, hint, clips)
            visiting[i] = false
            return (s to s + d).also { spans[i] = it }
        }
        for (i in items.indices) place(i)
        val end = spans.filterNotNull().maxOfOrNull { it.second } ?: start
        return maxOf(0.0, end - start)
    }

    // an empty `target` (the default of `enter` and `exit` clips) is the owner of the timeline itself
    /** A clip instance: arguments checked against its schema, body substituted, then compiled in place. */
    private fun clip(m: Value.Map, start: Double, dp: DataPath, path: String, span: Double?, clips: List<String>): Double {
        val ref = (m["use"] as? Value.Str)?.value
        val t = ref?.let { env.template(it) }
        if (t == null) {
            sink.error(Codes.PATH_UNRESOLVED, "$path.use", "unknown clip '${ref ?: ""}'", dp.key("use"))
            return 0.0
        }
        if (!t.isClip) {
            err("$path.use", "'$ref' is a shape template; a timeline uses clips (templates whose body is a time item)", dp.key("use"))
            return 0.0
        }
        if (ref in clips) {
            sink.error(Codes.TIMING_CYCLE, path, "clip '$ref' uses itself through ${clips.joinToString(" -> ")} -> $ref", dp.key("use"))
            return 0.0
        }
        val args = LinkedHashMap<String, Value>()
        for ((k, x) in m.entries) {
            if (k == "use" || k in Timelines.TIMING) continue
            val p = t.params[k]
            if (p == null) {
                err("$path.$k", "'$k' is not a parameter of clip $ref; parameters: ${t.params.keys.joinToString().ifEmpty { "none" }}", dp.key(k))
                continue
            }
            ParamCheck(sink).check(p, x, dp.key(k), "$path.$k")
            args[k] = x
        }
        // `with:` spans what it names (spec section 15): it sets a clip's duration unless the clip is given one
        if (span != null && args["duration"] == null && t.params["duration"]?.type == ParamType.NUMBER) args["duration"] = Value.Num(span)
        for (p in t.params.values) {
            if (args[p.name] != null || !p.required) continue
            when {
                p.name == "duration" && span != null -> args["duration"] = Value.Num(span)
                p.name == "target" && implicitTarget != null -> args["target"] = Value.Str(implicitTarget)
                else -> err(path, "clip $ref requires '${p.name}'", dp)
            }
        }
        val body = expander.clip(t, Value.Map(args), path, dp)
        return item(body, start, TrackSource.CLIP, dp.key("\$body"), "$path(${t.ref})", span, clips + t.ref)
    }
}

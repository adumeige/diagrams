package diagrams.processor

import diagrams.model.Codes
import diagrams.model.DataPath
import diagrams.model.DiagnosticSink
import diagrams.model.Value
import diagrams.model.typeName

/** One state of a container (spec section 11): overrides on the base scene. */
class StateDef(
    val id: String,
    /** Property path (from the container's owner) to canonical value. */
    val set: Map<String, Value>,
    val extends: String?,
    /** The parent in a `tree`; a tree child extends its parent implicitly. */
    val parent: StateDef?,
    /** Named edges of a `graph`: edge name to target state. */
    val edges: Map<String, String>,
    val dp: DataPath,
    /** Plays while the scene sits in this state (spec section 11), on a clock that starts when it is entered. */
    val timeline: Value? = null,
)

/** An explicit transition (spec section 13): duration and easing for one edge. */
class TransitionDef(val from: String, val to: String, val duration: Double?, val ease: Value?, val body: Value.Map? = null)

/** A state container: its topology, its states, the defaults of automatic transitions. */
class StateContainer(
    val kind: String,
    val states: List<StateDef>,
    val duration: Double,
    val ease: Value,
    val cumulative: Boolean,
    val transitions: List<TransitionDef>,
) {
    private val byId = states.associateBy { it.id }
    operator fun get(id: String): StateDef? = byId[id]
    val first: StateDef? get() = states.firstOrNull()

    /**
     * The states whose overrides make up [id], oldest first (spec section 11, "Inheritance between
     * states"): `extends`, the tree parent, or the previous state of a cumulative sequence. Null on a cycle.
     */
    fun chain(id: String): List<StateDef>? {
        val out = ArrayList<StateDef>()
        val seen = HashSet<String>()
        var cur = byId[id]
        while (cur != null) {
            if (!seen.add(cur.id)) return null
            out += cur
            cur = when {
                cur.extends != null -> byId[cur.extends]
                cur.parent != null -> cur.parent
                kind == "sequence" && cumulative -> states.getOrNull(states.indexOf(cur) - 1)
                else -> null
            }
        }
        return out.asReversed()
    }

    /** Duration and easing of the move from [from] to [to]: an explicit transition, else the defaults. */
    /** The explicit transition for the move from [from] to [to], if any. */
    fun transition(from: String?, to: String): TransitionDef? = transitions.firstOrNull { it.to == to && (it.from == from || it.from == "*") }

    fun timing(from: String?, to: String): Pair<Double, Value> {
        val t = transition(from, to)
        return (t?.duration ?: duration) to (t?.ease ?: ease)
    }

    companion object {
        val KINDS = listOf("sequence", "tree", "graph", "bag")
        val KEYS = KINDS + listOf("defaults", "cumulative", "transitions")
        val STATE_KEYS = setOf("id", "set", "extends", "timeline", "children", "edges")
        val EASES = setOf("linear", "in", "out", "in_out")
        const val DEFAULT_DURATION = 0.5
        val DEFAULT_EASE: Value = Value.Str("in_out")

        /** Reads a canonical `states` value. Reports structural problems to [sink] when given. */
        fun read(v: Value, dp: DataPath, path: String, sink: DiagnosticSink? = null): StateContainer? {
            fun err(p: String, msg: String, at: DataPath) = sink?.error(Codes.SCHEMA, p, msg, at)
            if (v !is Value.Map) {
                err(path, "'states' is a map with one of ${KINDS.joinToString()}", dp)
                return null
            }
            for (k in v.keys) if (k !in KEYS) err("$path.$k", "unknown key '$k' in states; expected one of ${KEYS.joinToString()}", dp.key(k))
            val kinds = KINDS.filter { v[it] != null }
            if (kinds.size != 1) {
                err(path, "a state container has exactly one of ${KINDS.joinToString()}; found ${kinds.joinToString().ifEmpty { "none" }}", dp)
                return null
            }
            val kind = kinds[0]
            val states = ArrayList<StateDef>()
            val ids = HashSet<String>()
            fun state(x: Value, at: DataPath, parent: StateDef?) {
                if (x !is Value.Map) return run { err(path, "a state is a map {id, set, extends}", at) }
                for (k in x.keys) if (k !in STATE_KEYS) err("$path.$k", "unknown key '$k' in a state; expected one of ${STATE_KEYS.joinToString()}", at.key(k))
                val id = (x["id"] as? Value.Str)?.value
                if (id == null || !CoreSchema.ID_PATTERN.matches(id)) return run { err(path, "a state needs an 'id' matching [a-z][a-z0-9_]*", at) }
                if (!ids.add(id)) err("$path.$id", "duplicate state '$id'", at.key("id"))
                val set = LinkedHashMap<String, Value>()
                when (val s = x["set"]) {
                    null -> {}
                    is Value.Map -> set.putAll(s.entries)
                    else -> err("$path.$id.set", "'set' is a map of property paths to values", at.key("set"))
                }
                val ext = x["extends"]
                if (ext != null && ext !is Value.Str) err("$path.$id.extends", "'extends' names another state", at.key("extends"))
                val edges = LinkedHashMap<String, String>()
                x["edges"]?.let { e ->
                    if (kind != "graph") err("$path.$id.edges", "only a graph declares edges", at.key("edges"))
                    if (e !is Value.Map) err("$path.$id.edges", "'edges' maps edge names to target states", at.key("edges"))
                    else for ((name, t) in e.entries) (t as? Value.Str)?.let { edges[name] = it.value } ?: err("$path.$id.edges.$name", "an edge targets a state id", at.key("edges").key(name))
                }
                val def = StateDef(id, set, (ext as? Value.Str)?.value, parent, edges, at, x["timeline"])
                states += def
                x["children"]?.let { c ->
                    if (kind != "tree") err("$path.$id.children", "only a tree nests states", at.key("children"))
                    (c as? Value.List)?.items?.forEachIndexed { i, s -> state(s, at.key("children").index(i), def) }
                }
            }
            when (val list = v[kind]) {
                is Value.List -> list.items.forEachIndexed { i, x -> state(x, dp.key(kind).index(i), null) }
                else -> err("$path.$kind", "'$kind' is a list of states", dp.key(kind))
            }
            val defaults = v["defaults"] as? Value.Map
            if (v["defaults"] != null && defaults == null) err("$path.defaults", "'defaults' is a map {duration, ease}", dp.key("defaults"))
            defaults?.keys?.forEach { k -> if (k != "duration" && k != "ease") err("$path.defaults.$k", "'defaults' takes duration and ease", dp.key("defaults").key(k)) }
            val duration = (defaults?.get("duration") as? Value.Num)?.value ?: DEFAULT_DURATION
            if (duration < 0) err("$path.defaults.duration", "a duration is at least 0", dp.key("defaults").key("duration"))
            if (defaults?.get("duration") != null && defaults["duration"] !is Value.Num) err("$path.defaults.duration", "a duration is a number of seconds", dp.key("defaults").key("duration"))
            val ease = defaults?.get("ease") ?: DEFAULT_EASE
            checkEase(ease, dp.key("defaults").key("ease"), "$path.defaults.ease", sink)
            val cumulative = (v["cumulative"] as? Value.Bool)?.value ?: false
            if (v["cumulative"] != null && kind != "sequence") err("$path.cumulative", "'cumulative' applies to a sequence", dp.key("cumulative"))
            val transitions = ArrayList<TransitionDef>()
            when (val ts = v["transitions"]) {
                null -> {}
                !is Value.List -> err("$path.transitions", "'transitions' is a list of {from, to, duration, ease}", dp.key("transitions"))
                else -> ts.items.forEachIndexed { i, t ->
                    val at = dp.key("transitions").index(i)
                    val m = t as? Value.Map
                    val from = (m?.get("from") as? Value.Str)?.value
                    val to = (m?.get("to") as? Value.Str)?.value
                    if (m == null || from == null || to == null) return@forEachIndexed run { err("$path.transitions", "a transition names 'from' and 'to' states ('*' for any)", at) }
                    for (k in m.keys) {
                        when (k) {
                            "from", "to", "duration", "ease" -> {}
                            else -> if (Timelines.KINDS.none { m[it] != null }) err("$path.transitions.$k", "unknown key '$k' in a transition", at.key(k))
                        }
                    }
                    for ((key, s) in listOf("from" to from, "to" to to)) if (s != "*" && s !in ids) sink?.error(Codes.PATH_UNRESOLVED, "$path.transitions", "no state '$s' in this container", at.key(key))
                    m["ease"]?.let { checkEase(it, at.key("ease"), "$path.transitions.ease", sink) }
                    val body = Value.Map(m.entries.filterKeys { it !in setOf("from", "to", "duration", "ease") }).takeIf { b -> Timelines.KINDS.any { b[it] != null } }
                    transitions += TransitionDef(from, to, (m["duration"] as? Value.Num)?.value, m["ease"], body)
                }
            }
            val c = StateContainer(kind, states, duration, ease, cumulative, transitions)
            for (s in states) {
                if (s.extends != null && c[s.extends] == null) sink?.error(Codes.PATH_UNRESOLVED, "$path.${s.id}.extends", "no state '${s.extends}' in this container", s.dp.key("extends"))
                for ((name, t) in s.edges) if (c[t] == null) sink?.error(Codes.PATH_UNRESOLVED, "$path.${s.id}.edges.$name", "no state '$t' in this container", s.dp.key("edges").key(name))
                if (c.chain(s.id) == null) sink?.error(Codes.EXTENDS_CYCLE, "$path.${s.id}", "the extends chain of '${s.id}' loops", s.dp)
            }
            return c
        }

        private fun checkEase(e: Value, dp: DataPath, path: String, sink: DiagnosticSink?) {
            val ok = when (e) {
                is Value.Str -> e.value in EASES
                is Value.Map -> e.keys == setOf("bezier") && (e["bezier"] as? Value.List)?.items?.let { l -> l.size == 4 && l.all { it is Value.Num } } == true
                else -> false
            }
            if (!ok) sink?.error(Codes.SCHEMA, path, "an easing is one of ${EASES.joinToString()} or {bezier: [x1, y1, x2, y2]}, found ${e.typeName()}", dp)
        }

        /** Canonical form: defaults filled in, expressions in `set` values structured. */
        fun canonical(v: Value, dp: DataPath, path: String, sink: DiagnosticSink): Value {
            if (v !is Value.Map) return v
            val out = LinkedHashMap<String, Value>()
            val d = v["defaults"] as? Value.Map
            out["defaults"] = Value.Map(
                linkedMapOf(
                    "duration" to (d?.get("duration") ?: Value.Num(DEFAULT_DURATION)),
                    "ease" to (d?.get("ease") ?: DEFAULT_EASE),
                ) + d?.entries.orEmpty().filterKeys { it != "duration" && it != "ease" },
            )
            if (v["sequence"] != null) out["cumulative"] = v["cumulative"] ?: Value.Bool(false)
            for ((k, x) in v.entries) {
                if (k in out) continue
                out[k] = if (k in KINDS && x is Value.List) Value.List(x.items.mapIndexed { i, s -> state(s, dp.key(k).index(i), path, sink) }) else if (k == "transitions") deepCanonical(x, dp.key(k), "$path.transitions", sink) else x
            }
            return Value.Map(out)
        }

        private fun state(s: Value, dp: DataPath, path: String, sink: DiagnosticSink): Value {
            if (s !is Value.Map) return s
            return Value.Map(
                s.entries.mapValuesTo(LinkedHashMap()) { (k, x) ->
                    when {
                        k == "set" && x is Value.Map -> Value.Map(x.entries.mapValuesTo(LinkedHashMap()) { (p, sv) -> deepCanonical(sv, dp.key("set").key(p), "$path.$p", sink) })
                        k == "children" && x is Value.List -> Value.List(x.items.mapIndexed { i, c -> state(c, dp.key("children").index(i), path, sink) })
                        k == "timeline" -> deepCanonical(x, dp.key("timeline"), "$path.timeline", sink)
                        else -> x
                    }
                },
            )
        }

        /** Canonical to authored: `set` values printed back to `=` strings. */
        fun printed(v: Value, print: (Value) -> Value): Value {
            if (v !is Value.Map) return v
            fun state(s: Value): Value = if (s !is Value.Map) s else Value.Map(
                s.entries.mapValuesTo(LinkedHashMap()) { (k, x) ->
                    when {
                        k == "set" && x is Value.Map -> Value.Map(x.entries.mapValuesTo(LinkedHashMap()) { (_, sv) -> print(sv) })
                        k == "children" && x is Value.List -> Value.List(x.items.map { state(it) })
                        k == "timeline" -> print(x)
                        else -> x
                    }
                },
            )
            return Value.Map(v.entries.mapValuesTo(LinkedHashMap()) { (k, x) -> if (k in KINDS && x is Value.List) Value.List(x.items.map { state(it) }) else if (k == "transitions") print(x) else x })
        }
    }
}

/** Every authored expression inside a value, at any depth, in canonical form. */
internal fun deepCanonical(x: Value, dp: DataPath, path: String, sink: DiagnosticSink): Value = when (x) {
    is Value.Str -> canonicalValue(Prop("value", PType.STRING), x, dp, path, sink)
    is Value.Map -> if (isCanonicalExpr(x)) x else Value.Map(x.entries.mapValuesTo(LinkedHashMap()) { (k, c) -> deepCanonical(c, dp.key(k), path, sink) })
    is Value.List -> Value.List(x.items.mapIndexed { i, c -> deepCanonical(c, dp.index(i), path, sink) })
    else -> x
}

/** A state's `set` key resolved against the tree: the node and the property path on it. */
class SetTarget(val node: NodeInfo, val property: List<String>, val breach: NodeInfo?)

/**
 * Resolves a `set` key from the container's owner (spec section 5, "Scope"): a node path then a
 * property, or a bare property of the owner itself.
 */
fun resolveSetKey(key: String, owner: NodeInfo, index: NodeIndex): SetTarget? {
    val first = key.substringBefore('.')
    if (owner.namespace[first] == null && (first in SET_PROPERTIES || first == "state")) {
        return SetTarget(owner, key.split('.'), null)
    }
    val m = index.resolve(key, owner) ?: return null
    return SetTarget(m.node, m.rest, m.breach)
}

/** Every property name a state can set on some node kind. */
internal val SET_PROPERTIES: Set<String> by lazy {
    (CoreSchema.container.props + (CoreSchema.shapes.values + CoreSchema.media.values).flatMap { it.props }).map { it.name }.toSet() - setOf("id", "children", "states")
}

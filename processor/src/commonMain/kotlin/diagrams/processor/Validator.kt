package diagrams.processor

import diagrams.model.Codes
import diagrams.model.DataPath
import diagrams.model.DiagnosticSink
import diagrams.model.ExprSyntaxException
import diagrams.model.Exprs
import diagrams.model.Value
import diagrams.model.typeName

/** Structural validation of a canonical document against the core schema (spec section 18). */
class Validator(private val sink: DiagnosticSink, private val env: Environment = Environment.EMPTY) {
    private var index = NodeIndex(Value.Null)
    private fun error(path: String, msg: String, dp: DataPath) = sink.error(Codes.SCHEMA, path, msg, dp)

    fun document(doc: Value) {
        index = NodeIndex(Expander(env, sink).expand(doc))
        scopedIds()
        for (n in index.all()) for (c in n.slotClashes) {
            error(c.path, "'${c.id}' is already the name of a part of ${n.template?.ref} in ${n.path}; rename it", c.dp.key("id"))
        }
        val root = DataPath.ROOT
        if (doc !is Value.Map) {
            error("", "a document is a map, found ${doc.typeName()}", root)
            return
        }
        when (val f = doc["format"]) {
            null -> error("format", "missing required key 'format' (expected '${CoreSchema.FORMAT}')", root)
            !is Value.Str -> error("format", "'format' is a string", root.key("format"))
            else -> if (f.value != CoreSchema.FORMAT) error("format", "unsupported format '${f.value}'; this toolset reads '${CoreSchema.FORMAT}'", root.key("format"))
        }
        doc["title"]?.let { if (it !is Value.Str) error("title", "'title' is a string", root.key("title")) }
        when (val vp = doc["viewport"]) {
            null -> error("viewport", "missing required key 'viewport' ({width, height})", root)
            else -> viewport(vp, root.key("viewport"))
        }
        for (k in CoreSchema.UNSUPPORTED_DOCUMENT_KEYS) {
            if (doc[k] != null) sink.warning(Codes.UNSUPPORTED, k, "'$k' is not implemented yet and is ignored", root.key(k))
        }
        for (k in doc.keys) {
            if (k !in CoreSchema.documentKeys) error(k, "unknown document key '$k'; expected one of ${CoreSchema.documentKeys.joinToString()}", root.key(k))
        }
        when (val scene = doc["scene"]) {
            null -> error("scene", "missing required key 'scene' (the root node)", root)
            else -> node(scene, root.key("scene"), "scene", isRoot = true, tokens = env.rootTokens.keys)
        }
        doc["camera"]?.let { camera(it, root.key("camera")) }
        overlaps(rootTracks, "the scene timeline")
    }

    private fun viewport(vp: Value, dp: DataPath) {
        if (vp !is Value.Map) {
            error("viewport", "'viewport' is a map {width, height}", dp)
            return
        }
        for (k in listOf("width", "height")) {
            when (val x = vp[k]) {
                null -> error("viewport.$k", "missing 'viewport.$k'", dp)
                !is Value.Num -> error("viewport.$k", "'viewport.$k' is a number, found ${x.typeName()}", dp.key(k))
                else -> if (x.value <= 0) error("viewport.$k", "'viewport.$k' must be positive", dp.key(k))
            }
        }
        for (k in vp.keys) if (k != "width" && k != "height") error("viewport.$k", "unknown key '$k' in viewport", dp.key(k))
    }

    private fun node(v: Value, dp: DataPath, path: String, isRoot: Boolean, tokens: Set<String>) {
        if (v !is Value.Map) {
            error(path, "a node is a map, found ${v.typeName()}", dp)
            return
        }
        val shape = v["shape"]
        if (shape != null && (shape !is Value.Str || shape.value !in CoreSchema.SHAPES)) {
            error(path, "unknown shape ${shape.let { (it as? Value.Str)?.value ?: it.typeName() }}; expected one of ${CoreSchema.SHAPES.joinToString()}", dp.key("shape"))
            return
        }
        val forms = listOf("shape", "use", "media").filter { v[it] != null }
        if (forms.size > 1) {
            error(path, "a node has one form; found ${forms.joinToString(" and ")}", dp)
            return
        }
        if (shape != null && (v["children"] != null || v["layout"] != null)) {
            error(path, "a primitive has no 'children' or 'layout'; wrap it in a container", dp)
        }
        if (v["use"] != null) return instance(v, dp, path, isRoot, tokens)
        val mediaKind = v["media"]
        if (mediaKind != null && (mediaKind !is Value.Str || mediaKind.value !in CoreSchema.media)) {
            error(path, "unknown media kind ${(mediaKind as? Value.Str)?.value ?: mediaKind.typeName()}; expected one of ${CoreSchema.media.keys.joinToString()}", dp.key("media"))
            return
        }
        if (mediaKind != null && v["children"] != null) error(path, "a media node has no 'children'; wrap it in a container", dp)
        val shapeName = (shape as? Value.Str)?.value
        if (shapeName in CoreSchema.UNSUPPORTED_SHAPES) {
            sink.warning(Codes.UNSUPPORTED, path, "shape '$shapeName' is not implemented yet (milestone 2); the node is skipped", dp.key("shape"))
            return
        }
        val kind = CoreSchema.kindOf(v)!!
        val label = shapeName ?: (mediaKind as? Value.Str)?.value ?: "container"

        if (isRoot && v["id"] != null) error("scene", "the root node is addressed as 'scene' and takes no id", dp.key("id"))
        val scope = tokens + themeOverride(v["theme"], dp.key("theme"), "$path.theme", tokens)

        for ((k, x) in v.entries) {
            if (k == "shape" || k == "media") continue
            val prop = kind[k]
            if (prop == null) {
                error("$path.$k", "unknown property '$k' on ${article(label)} $label", dp.key(k))
                continue
            }
            property(k, x, prop, v, dp, path, label, scope)
        }
        for (prop in kind.props) {
            if (prop.required && v[prop.name] == null) error(path, "${article(label)} $label requires '${prop.name}'", dp)
        }
        if ((v["layout"] as? Value.Str)?.value == "grid" && v["columns"] == null) error(path, "a grid requires 'columns'", dp)
        for (name in styleNames(v["style"])) {
            for ((k, x) in env.style(name)?.entries.orEmpty()) if (kind[k] != null && v[k] == null) tokenRefs(x, scope, dp.key("style"), "$path.$k", dp)
        }
        val minPts = CoreSchema.MIN_POINTS[shapeName]
        val pts = v["points"] as? Value.List
        if (minPts != null && pts != null) {
            val maxPts = CoreSchema.MAX_POINTS[shapeName]
            if (pts.items.size < minPts || (maxPts != null && pts.items.size > maxPts)) {
                val expected = if (maxPts == minPts) "exactly $minPts" else "at least $minPts"
                error("$path.points", "a $label takes $expected points, found ${pts.items.size}", dp.key("points"))
            }
        }
        (v["sequence"] as? Value.Map)?.let { m ->
            for ((k, x) in m.entries) {
                when {
                    k !in Layouts.SEQUENCE_KEYS -> error("$path.sequence.$k", "unknown key '$k' in sequence; expected one of ${Layouts.SEQUENCE_KEYS.joinToString()}", dp.key("sequence").key(k))
                    k == "role" && x is Value.Str && x.value !in Layouts.ROLES -> error("$path.sequence.role", "'role' is one of ${Layouts.ROLES.joinToString()}", dp.key("sequence").key(k))
                    k == "lifespan" && x is Value.Str && x.value !in Layouts.LIFESPANS -> error("$path.sequence.lifespan", "'lifespan' is one of ${Layouts.LIFESPANS.joinToString()}", dp.key("sequence").key(k))
                }
            }
        }
        for (k in CoreSchema.DECORATIONS) (v[k] as? Value.List)?.let { l ->
            if (shape != null || mediaKind != null) error("$path.$k", "only a container has decorations", dp.key(k))
            l.items.forEachIndexed { i, c -> node(c, dp.key(k).index(i), childPathOf(path, c), isRoot = false, tokens = scope) }
        }
        val children = v["children"] as? Value.List ?: return
        if (isRoot || v["id"] != null) uniqueIds(children, dp.key("children"), path)
        children.items.forEachIndexed { i, c -> node(c, dp.key("children").index(i), childPathOf(path, c), isRoot = false, tokens = scope) }
    }

    /** One property of a node or an instance, known to the schema. */
    private fun property(k: String, x: Value, prop: Prop, v: Value.Map, dp: DataPath, path: String, label: String, scope: Set<String>) {
        val ppath = "$path.$k"
        if (!prop.supported) {
            sink.warning(Codes.UNSUPPORTED, ppath, "'$k' is not implemented yet and is ignored", dp.key(k))
            return
        }
        when (k) {
            "theme" -> return
            "attach" -> attach(x, dp, ppath, v)
            "padding" -> padding(x, dp.key(k), ppath)
            "from", "to" -> {
                check(prop, x, dp.key(k), ppath, label)
                if (x is Value.Str) anchorPath(x.value, dp, dp.key(k), ppath, allowFloating = true)
            }
            "columns" -> if (x !is Value.Num || x.value < 1 || x.value != kotlin.math.floor(x.value)) error(ppath, "'columns' is a whole number, at least 1", dp.key(k))
            "rank" -> rank(x, dp, ppath)
            "seed" -> {
                check(prop, x, dp.key(k), ppath, label)
                if (x is Value.Num && (x.value < 0 || x.value != kotlin.math.floor(x.value))) error(ppath, "'seed' is a whole number, 0 or more", dp.key(k))
            }
            "style" -> style(x, dp.key(k), ppath)
            "states" -> return states(x, dp, ppath)
            "timeline" -> return nodeTimeline(x, dp, ppath)
            "enter", "exit" -> return enterExit(x, dp, ppath, k)
            "draw" -> {
                check(prop, x, dp.key(k), ppath, label)
                if (x is Value.Num && (x.value < 0 || x.value > 1)) error(ppath, "'draw' is the fraction of the stroke drawn, from 0 to 1", dp.key(k))
            }
            "state" -> return stateValue(x, dp, ppath)
            "asset" -> {
                check(prop, x, dp.key(k), ppath, label)
                val declared = (x as? Value.Str)?.value?.let { env.assets()[it] }
                val kindOf = (declared?.second?.get("kind") as? Value.Str)?.value
                when {
                    x !is Value.Str -> {}
                    declared == null -> sink.error(Codes.PATH_UNRESOLVED, ppath, "no 'assets' map declares '${x.value}'; declare it in the document or an imported file", dp.key(k))
                    kindOf != label -> error(ppath, "asset '${x.value}' is ${article(kindOf ?: "")} $kindOf; this node shows ${article(label)} $label", dp.key(k))
                }
            }
            "volume", "rate", "start", "in", "out" -> {
                check(prop, x, dp.key(k), ppath, label)
                if (x is Value.Num && (x.value < 0 || (k == "rate" && x.value == 0.0))) error(ppath, "'$k' is ${if (k == "rate") "positive" else "at least 0"}", dp.key(k))
            }
            "font" -> {
                check(prop, x, dp.key(k), ppath, label)
                if (x is Value.Str && x.value !in env.fontAssets()) {
                    sink.error(Codes.PATH_UNRESOLVED, ppath, "no imported theme declares the font asset '${x.value}'", dp.key(k))
                }
            }
            else -> check(prop, x, dp.key(k), ppath, label)
        }
        if (prop.type.scalar || prop.type == PType.POINT || prop.type == PType.POINTS) tokenRefs(x, scope, dp.key(k), ppath, dp)
    }

    /** A template instance (spec section 9): the template exists, its parameters match the schema, its slots are filled. */
    private fun instance(v: Value.Map, dp: DataPath, path: String, isRoot: Boolean, tokens: Set<String>) {
        if (isRoot) error("scene", "the root node is a container; put the instance inside it", dp.key("use"))
        val ref = (v["use"] as? Value.Str)?.value
        if (ref == null) return error("$path.use", "'use' names a template: alias.name", dp.key("use"))
        val t = env.template(ref)
        if (t == null) {
            val alias = ref.substringBefore('.', "")
            val why = when {
                alias.isEmpty() -> "a template is used through its library's alias, such as 'uml.$ref'"
                alias !in env.modules -> "'$alias' is not an import alias"
                else -> "library '$alias' has no template '${ref.substringAfter('.')}'; templates: ${env.modules[alias]!!.templates.keys.joinToString().ifEmpty { "none" }}"
            }
            sink.error(Codes.PATH_UNRESOLVED, "$path.use", "unknown template '$ref': $why", dp.key("use"))
            return
        }
        if (t.isClip) {
            error("$path.use", "'$ref' is a clip: it plays in a timeline (timeline, enter, exit, a transition), not in the scene", dp.key("use"))
            return
        }
        val scope = tokens + themeOverride(v["theme"], dp.key("theme"), "$path.theme", tokens)
        val label = "instance of $ref"
        for ((k, x) in v.entries) {
            if (k == "use" || k == "children" || k == "slots") continue
            val param = t.params[k]
            if (param != null) {
                ParamCheck(sink).check(param, x, dp.key(k), "$path.$k")
                if (param.type == ParamType.PATH && x is Value.Str) anchorPath(x.value, dp, dp.key(k), "$path.$k", allowFloating = true)
                if (param.type.scalar) tokenRefs(x, scope, dp.key(k), "$path.$k", dp)
                continue
            }
            val prop = CoreSchema.instance[k]
            if (prop == null) {
                val params = t.params.keys.joinToString().ifEmpty { "none" }
                error("$path.$k", "'$k' is neither a parameter of $ref (parameters: $params) nor a node property", dp.key(k))
                continue
            }
            property(k, x, prop, v, dp, path, label, scope)
        }
        for (p in t.params.values) if (p.required && v[p.name] == null) error(path, "$ref requires '${p.name}'", dp)
        for (tok in t.exports.tokens) {
            if (tok !in scope) {
                val known = if (scope.isEmpty()) "no theme is in scope (set 'base_theme')" else "tokens in scope: ${scope.sorted().joinToString()}"
                sink.error(Codes.TOKEN_MISSING, path, "$ref expects token '$tok', which no theme in scope provides; $known", dp.key("use"), Value.map("token" to Value.Str(tok)))
            }
        }
        val children = v["children"]
        if (children != null) {
            if (children !is Value.List) {
                error("$path.children", "'children' is a list of nodes", dp.key("children"))
            } else {
                if ("default" !in t.slots && children.items.isNotEmpty()) error("$path.children", "$ref has no default slot, so it takes no children", dp.key("children"))
                if (v["id"] != null) uniqueIds(children, dp.key("children"), path)
                children.items.forEachIndexed { i, c -> node(c, dp.key("children").index(i), childPathOf(path, c), isRoot = false, tokens = scope) }
            }
        }
        when (val slots = v["slots"]) {
            null -> {}
            !is Value.Map -> error("$path.slots", "'slots' is a map of slot names to lists of nodes", dp.key("slots"))
            else -> for ((name, l) in slots.entries) {
                val sdp = dp.key("slots").key(name)
                when {
                    name !in t.slots -> error("$path.slots.$name", "$ref has no slot '$name'; slots: ${t.slots.joinToString().ifEmpty { "none" }}", sdp)
                    l !is Value.List -> error("$path.slots.$name", "a slot is filled with a list of nodes", sdp)
                    else -> l.items.forEachIndexed { i, c -> node(c, sdp.index(i), childPathOf(path, c), isRoot = false, tokens = scope) }
                }
            }
        }
    }

    /** A state container (spec section 11): its structure, then every `set` key resolved from the owner. */
    private fun states(x: Value, nodeDp: DataPath, path: String) {
        val c = StateContainer.read(x, nodeDp.key("states"), path, sink) ?: return
        val owner = info(nodeDp) ?: return
        stateTimelines(c, owner, path)
        for (s in c.states) for ((key, value) in s.set) {
            val at = s.dp.key("set").key(key)
            val p = "$path.${s.id}.$key"
            if (key.startsWith("camera.")) {
                cameraKey(key.removePrefix("camera."), value, at, p)
                continue
            }
            val t = resolveSetKey(key, owner, index)
            if (t == null) {
                sink.error(Codes.PATH_UNRESOLVED, p, "'$key' matches no node or property in the scope of ${owner.path}", at)
                continue
            }
            t.breach?.let { b ->
                val parts = b.template?.exports?.parts.orEmpty()
                sink.warning(Codes.ENCAPSULATION, p, "'$key' reaches inside ${b.path} past the interface of ${b.template?.ref}; exported parts: ${parts.joinToString().ifEmpty { "none" }}", at)
            }
            setTarget(t, key, value, at, p)
        }
    }

    // ---------------------------------------------------------------- time (spec sections 12 to 14)

    /** Tracks of the timelines that play on the scene clock, with the node each drives: checked for overlaps at the end. */
    private val rootTracks = mutableListOf<Pair<TrackDef, String>>()

    private val media = object : MediaTiming {
        var from: NodeInfo? = null
        override fun span(ref: String): Pair<Double, Double>? = mediaNode(ref)?.let { 0.0 to 0.0 }
        override fun cue(ref: String, cue: String): Double? = mediaNode(ref)?.let { 0.0 }
        private fun mediaNode(ref: String): NodeInfo? {
            val f = from ?: index.root ?: return null
            val m = index.resolve(ref, f) ?: return null
            return m.node.takeIf { m.rest.isEmpty() && (it.node["media"] as? Value.Str)?.value in setOf("audio", "video") }
        }
    }

    /** Compiles a timeline for its diagnostics, then resolves every track's target from [owner]. */
    private fun timeline(x: Value, owner: NodeInfo?, dp: DataPath, path: String, auto: Pair<Double, Value>? = null, implicit: String? = null): List<Pair<TrackDef, String>> {
        media.from = owner
        val c = TimelineCompiler(env, sink, media, auto, implicit).timeline(x, dp, path)
        return c.tracks.mapNotNull { t -> trackTarget(t, owner)?.let { t to it } }
    }

    private fun nodeTimeline(x: Value, nodeDp: DataPath, path: String) {
        rootTracks += timeline(x, info(nodeDp), nodeDp.key("timeline"), path)
    }

    /** `enter` and `exit` (spec section 13): one clip or time item, acting on the node by default. */
    private fun enterExit(x: Value, nodeDp: DataPath, path: String, which: String) {
        val owner = info(nodeDp)
        if (owner != null && owner.isRoot) return error(path, "the root is always present, so it has no '$which'", nodeDp.key(which))
        timeline(x, owner, nodeDp.key(which), path, implicit = "")
    }

    private fun stateTimelines(c: StateContainer, owner: NodeInfo, path: String) {
        for (s in c.states) {
            val t = s.timeline ?: continue
            overlaps(timeline(t, owner, s.dp.key("timeline"), "$path.${s.id}.timeline"), "the timeline of state ${s.id}")
        }
        c.transitions.forEachIndexed { i, tr ->
            val body = tr.body ?: return@forEachIndexed
            timeline(body, owner, DataPath.ROOT, "$path.transitions[$i]", auto = (tr.duration ?: c.duration) to (tr.ease ?: c.ease))
        }
    }

    /**
     * A track's target: `camera.<property>`, or a node path then an animatable property, resolved from
     * the timeline's owner like a state's `set` key. Returns the target node and property, for overlaps.
     */
    private fun trackTarget(t: TrackDef, owner: NodeInfo?): String? {
        val p = t.path
        val dp = t.dp.key("track")
        if (t.target.startsWith("camera.")) {
            val prop = t.target.removePrefix("camera.")
            if (prop.substringBefore('.') !in CAMERA_ANIMATABLE) error(p, "'${t.target}': a track can drive camera.${CAMERA_ANIMATABLE.joinToString(", camera.")}", dp)
            return t.target
        }
        val from = owner ?: index.root ?: return null
        val target = resolveSetKey(t.target, from, index)
        if (target == null) {
            sink.error(Codes.PATH_UNRESOLVED, p, "'${t.target}' matches no node or property in the scope of ${from.path}", dp)
            return null
        }
        target.breach?.let { b ->
            sink.warning(Codes.ENCAPSULATION, p, "'${t.target}' reaches inside ${b.path} past the interface of ${b.template?.ref}", dp)
        }
        val prop = target.property
        if (prop.isEmpty()) {
            error(p, "'${t.target}' names a node; a track drives one of its properties, such as '${t.target}.opacity'", dp)
            return null
        }
        val template = target.node.template
        if (template?.params?.containsKey(prop[0]) == true) {
            sink.error(Codes.SCHEMA, p, "'${t.target}': tracks cannot drive template parameters in 0.1; animate a part's property, or set the parameter from a state", dp)
            return null
        }
        val kind = if (template != null) CoreSchema.instance else CoreSchema.kindOf(target.node.node)
        val ok = kind?.get(prop[0]) != null && prop[0] !in NOT_ANIMATABLE && when {
            prop.size == 1 -> true
            prop.size == 2 && prop[0] == "at" -> prop[1] == "x" || prop[1] == "y"
            prop.size == 2 && prop[0] == "theme" -> CoreSchema.ID_PATTERN.matches(prop[1])
            else -> false
        }
        if (!ok) {
            error(p, "'${prop.joinToString(".")}' is not a property a track can drive on ${target.node.path}", dp)
            return null
        }
        if (t.along != null) {
            if (prop != listOf("at")) error(p, "a track 'along' a connector drives 'at': its values are fractions of the route", t.dp.key("along"))
            val c = index.resolve(t.along, from)
            if (c == null || c.rest.isNotEmpty() || c.node.shape != "connector") sink.error(Codes.PATH_UNRESOLVED, p, "'along: ${t.along}' does not name a connector", t.dp.key("along"))
        }
        return "${target.node.dp}#${prop.joinToString(".")}"
    }

    /** Two tracks on one property whose spans overlap: `blend_overlap` (spec section 12). */
    private fun overlaps(tracks: List<Pair<TrackDef, String>>, where: String) {
        val reported = HashSet<String>()
        for ((key, group) in tracks.groupBy { it.second }) {
            val ts = group.map { it.first }.sortedBy { it.start }
            for (i in ts.indices) for (j in i + 1 until ts.size) {
                val a = ts[i]
                val b = ts[j]
                if (b.start < a.end && a.start < b.end && reported.add(key)) {
                    sink.warning(Codes.BLEND_OVERLAP, b.path, "'${b.target}' is driven by two tracks at once in $where (${num(maxOf(a.start, b.start))}s to ${num(minOf(a.end, b.end))}s); numbers add up, other values take the higher source", b.dp.key("track"))
                }
            }
        }
    }

    private fun num(d: Double) = diagrams.model.formatNumber(d)

    /** The camera (spec section 14). */
    private fun camera(x: Value, dp: DataPath) {
        if (x !is Value.Map) return error("camera", "'camera' is a map {fit, padding, into, center, zoom, rotation, attach, states}", dp)
        for ((k, v) in x.entries) {
            when (k) {
                "states" -> {
                    val c = StateContainer.read(v, dp.key("states"), "camera.states", sink) ?: continue
                    for (s in c.states) for ((key, value) in s.set) cameraKey(key, value, s.dp.key("set").key(key), "camera.states.${s.id}.$key")
                }
                "state" -> {
                    val c = x["states"]?.let { StateContainer.read(it, dp, "camera") }
                    if (v !is Value.Str || c?.get(v.value) == null) sink.error(Codes.PATH_UNRESOLVED, "camera.state", "'state' names one of the camera's states", dp.key("state"))
                }
                else -> cameraKey(k, v, dp.key(k), "camera.$k")
            }
        }
    }

    /** One camera property, as written on the camera or set by a state. */
    private fun cameraKey(key: String, v: Value, dp: DataPath, path: String) {
        val root = index.root
        when (key) {
            "fit" -> {
                val s = (v as? Value.Str)?.value
                if (s == null) error(path, "'fit' is the path of the node to frame, or 'scene'", dp)
                else if (s != "scene" && root != null && index.resolve(s, root)?.rest?.isEmpty() != true) sink.error(Codes.PATH_UNRESOLVED, path, "'$s' names no node", dp)
            }
            "padding", "zoom", "rotation" -> {
                checkProp(Prop(key, PType.NUMBER), v, dp, path)
                if (key == "zoom" && v is Value.Num && v.value <= 0) error(path, "'zoom' is positive", dp)
            }
            "center", "center.x", "center.y" -> if (key == "center") checkProp(Prop(key, PType.POINT), v, dp, path) else checkProp(Prop(key, PType.NUMBER), v, dp, path)
            "into" -> {
                val m = v as? Value.Map
                if (m == null || m.keys != setOf("x", "y", "width", "height") || m.entries.values.any { it !is Value.Num }) error(path, "'into' is a viewport region {x, y, width, height}", dp)
            }
            "attach" -> {
                val to = ((v as? Value.Map)?.get("to") as? Value.Str)?.value
                if (to == null || (v as Value.Map).keys.any { it != "to" }) {
                    error(path, "the camera's 'attach' is {to: node or anchor path}: the camera centers on it", dp)
                } else if (root != null) {
                    val m = index.resolve(to, root)
                    if (m == null || m.rest.size > 1 || (m.rest.size == 1 && !GeometryRefs.isAnchor(m.rest[0], m.node))) sink.error(Codes.PATH_UNRESOLVED, path, "'$to' names no node or anchor", dp)
                }
            }
            "state" -> {}
            else -> error(path, "'$key' is not a camera property; the camera has ${CAMERA_KEYS.joinToString()}", dp)
        }
    }

    /** What a state can set: a property of the target's kind, `state`, a template parameter, `at.x`, `theme.<token>`. */
    private fun setTarget(t: SetTarget, key: String, value: Value, at: DataPath, p: String) {
        val prop = t.property
        if (prop.isEmpty()) return error(p, "'$key' names a node; a state sets one of its properties, such as '$key.present'", at)
        val node = t.node.node
        val name = prop[0]
        val template = t.node.template
        val kind = if (template != null) CoreSchema.instance else CoreSchema.kindOf(node)
        if (name == "state") {
            if (prop.size != 1) return error(p, "'state' has no members", at)
            val target = node["states"]?.let { StateContainer.read(it, at, p) }
            when {
                target == null -> error(p, "${t.node.path} owns no state container, so its 'state' cannot be set", at)
                value is Value.Str && target[value.value] == null -> sink.error(Codes.PATH_UNRESOLVED, p, "${t.node.path} has no state '${value.value}'; states: ${target.states.joinToString { it.id }}", at)
                value !is Value.Str -> error(p, "'state' is a state id", at)
                template != null && template.exports.states.isNotEmpty() && value.value !in template.exports.states ->
                    sink.warning(Codes.ENCAPSULATION, p, "state '${value.value}' is not exported by ${template.ref}; exported states: ${template.exports.states.joinToString()}", at)
            }
            return
        }
        val param = template?.params?.get(name)
        if (param != null) {
            if (t.node.dp.segments.contains("\$body")) sink.warning(Codes.UNSUPPORTED, p, "a state can set parameters of instances written in the document only; this one is inside a template", at)
            if (prop.size == 1) ParamCheck(sink).check(param, value, at, p)
            return
        }
        val pr = kind?.get(name)
        if (pr == null || name in setOf("id", "children", "underlay", "overlay", "states", "use", "slots", "timeline", "enter", "exit")) {
            return error(p, "'$name' is not a property a state can set on ${t.node.path}${template?.let { " (an instance of ${it.ref})" } ?: ""}", at)
        }
        val member = prop.getOrNull(1)
        val ok = when {
            prop.size == 1 -> true
            prop.size != 2 -> false
            name == "at" -> member == "x" || member == "y"
            name == "theme" -> CoreSchema.ID_PATTERN.matches(member!!)
            name == "attach" -> member in CoreSchema.ATTACH_KEYS
            name == "padding" -> member in setOf("top", "right", "bottom", "left", "x", "y")
            else -> false
        }
        if (!ok) return error(p, "'${prop.joinToString(".")}' is not a settable property path", at)
        if (prop.size == 1) {
            when (name) {
                "attach", "padding", "theme", "style" -> {}
                else -> check(pr, value, at, p, "state value")
            }
        } else if (name == "at") {
            check(Prop("$name.$member", PType.NUMBER), value, at, p, "state value")
        }
    }

    /** `state:` on a node picks the first state of its own container. */
    private fun stateValue(x: Value, nodeDp: DataPath, path: String) {
        val id = (x as? Value.Str)?.value ?: return error(path, "'state' is the id of one of this node's states", nodeDp.key("state"))
        val c = info(nodeDp)?.node?.get("states")?.let { StateContainer.read(it, nodeDp, path) }
        when {
            c == null -> error(path, "'state' needs a state container ('states') on the same node", nodeDp.key("state"))
            c[id] == null -> sink.error(Codes.PATH_UNRESOLVED, path, "no state '$id' in this node's container; states: ${c.states.joinToString { it.id }}", nodeDp.key("state"))
        }
    }

    /** IDs must be unique among the nodes reachable from a named node without crossing another named node (spec section 5). */
    private fun uniqueIds(children: Value.List, dp: DataPath, path: String) {
        val seen = HashSet<String>()
        fun visit(list: Value.List, ldp: DataPath) {
            list.items.forEachIndexed { i, c ->
                val m = c as? Value.Map ?: return@forEachIndexed
                val id = (m["id"] as? Value.Str)?.value
                if (id != null) {
                    if (!seen.add(id)) error(childPathOf(path, c), "duplicate id '$id' in the scope of '$path'", ldp.index(i).key("id"))
                } else {
                    (m["children"] as? Value.List)?.let { visit(it, ldp.index(i).key("children")) }
                }
            }
        }
        visit(children, dp)
    }

    /** Type check of one property value, for values outside a node such as a style's. */
    internal fun checkProp(prop: Prop, x: Value, dp: DataPath, path: String) = check(prop, x, dp, path, "style")

    private fun style(x: Value, dp: DataPath, path: String) {
        val names = when (x) {
            is Value.Str -> listOf(x.value to dp)
            is Value.List -> x.items.mapIndexedNotNull { i, s ->
                if (s !is Value.Str) error(path, "'style' is a style name or a list of style names", dp.index(i))
                (s as? Value.Str)?.let { it.value to dp.index(i) }
            }
            else -> return error(path, "'style' is a style name or a list of style names", dp)
        }
        for ((name, at) in names) {
            if (env.style(name) == null) {
                val hint = if ('.' in name) "" else " (a style of an imported file is written 'alias.$name')"
                sink.error(Codes.PATH_UNRESOLVED, path, "unknown style '$name'$hint", at)
            }
        }
    }

    /**
     * `theme: alias` takes every token of an imported theme; `theme: {token: value}` overrides single
     * tokens. Override values may reference tokens of the parent's scope. Returns the names it defines.
     */
    private fun themeOverride(x: Value?, dp: DataPath, path: String, parentScope: Set<String>): Set<String> = when (x) {
        null -> emptySet()
        is Value.Str -> {
            val m = env.modules[x.value]
            if (m == null) sink.error(Codes.PATH_UNRESOLVED, path, "'${x.value}' is not an import alias", dp)
            m?.tokens?.keys.orEmpty()
        }
        is Value.Map -> {
            for ((t, tv) in x.entries) {
                if (!CoreSchema.ID_PATTERN.matches(t)) error("$path.$t", "a token name matches [a-z][a-z0-9_]*", dp.key(t))
                if (!isExpr(tv, dp.key(t), "$path.$t") && tv !is Value.Str && tv !is Value.Num && tv !is Value.Bool) {
                    error("$path.$t", "a token is a string, number or boolean, found ${tv.typeName()}", dp.key(t))
                }
                tokenRefs(tv, parentScope, dp.key(t), "$path.$t")
            }
            x.keys
        }
        else -> {
            error(path, "'theme' is the alias of an imported theme or a map of token overrides", dp)
            emptySet()
        }
    }

    private fun info(nodeDp: DataPath): NodeInfo? = index[nodeDp]

    private fun encapsulation(m: PathMatch, ref: String, path: String, dp: DataPath) {
        val b = m.breach ?: return
        val parts = b.template?.exports?.parts.orEmpty()
        val hint = if (parts.isEmpty()) "it exports no parts" else "its exported parts: ${parts.joinToString()}"
        sink.warning(Codes.ENCAPSULATION, path, "'$ref' reaches inside ${b.path} past the interface of ${b.template?.ref}; $hint", dp)
    }

    /** A geometry reference in an expression: a node path, then a property or `anchor.x`/`anchor.y`. */
    private fun geometryRef(ref: String, nodeDp: DataPath, dp: DataPath, path: String) {
        val from = info(nodeDp) ?: return
        if (ref == "host" || ref.startsWith("host.")) {
            // the container a decoration decorates (spec section 6, "Decorations")
            if (generateSequence(from) { it.parent }.none { it.node["\$decoration"] != null }) {
                sink.error(Codes.PATH_UNRESOLVED, path, "'host' is the container a decoration decorates; this node is not in an underlay or overlay", dp)
            } else if (ref.split('.').drop(1).let { it.size != 1 || it[0] !in GeometryRefs.PROPERTIES }) {
                sink.error(Codes.PATH_UNRESOLVED, path, "'$ref': host gives ${GeometryRefs.PROPERTIES.joinToString()}", dp)
            }
            return
        }
        val m = index.resolve(ref, from)
        if (m == null) {
            sink.error(Codes.PATH_UNRESOLVED, path, "'$ref' matches no node in scope", dp)
            return
        }
        encapsulation(m, ref, path, dp)
        val r = m.rest
        val ok = (r.size == 1 && r[0] in GeometryRefs.PROPERTIES) || (r.size == 2 && GeometryRefs.isAnchor(r[0], m.node) && r[1] in setOf("x", "y"))
        if (!ok) {
            val what = if (r.isEmpty()) "names a node, not a value" else "'${r.joinToString(".")}' is not a geometry property of ${m.node.path}"
            sink.error(Codes.PATH_UNRESOLVED, path, "'$ref': $what; expected one of ${GeometryRefs.PROPERTIES.joinToString()} or <anchor>.x / .y", dp)
        }
    }

    /**
     * A path where an anchor is expected (`attach.to`, a connector's `from` and `to`): a node path,
     * whose floating anchor is used when [allowFloating], or a node path and one anchor name.
     */
    private fun anchorPath(ref: String, nodeDp: DataPath, dp: DataPath, path: String, allowFloating: Boolean): NodeInfo? {
        val from = info(nodeDp) ?: return null
        val m = index.resolve(ref, from)
        if (m == null) {
            sink.error(Codes.PATH_UNRESOLVED, path, "'$ref' matches no node in scope", dp)
            return null
        }
        encapsulation(m, ref, path, dp)
        when {
            m.rest.isEmpty() -> if (!allowFloating) sink.error(Codes.PATH_UNRESOLVED, path, "'$ref' names a node; an anchor is expected", dp)
            m.rest.size == 1 && GeometryRefs.isAnchor(m.rest[0], m.node) -> {}
            else -> {
                val anchors = GeometryRefs.ANCHORS + if (m.node.shape == "connector") GeometryRefs.CONNECTOR_ANCHORS + GeometryRefs.LABEL_ANCHOR else emptyList()
                sink.error(Codes.PATH_UNRESOLVED, path, "'${m.rest.joinToString(".")}' is not an anchor of ${m.node.path}; anchors: ${anchors.joinToString()}", dp)
                return null
            }
        }
        if (m.node === from || from.isAncestorOf(m.node)) {
            sink.error(Codes.ATTACH_CYCLE, path, "'$ref' is the node itself or inside it: its position would depend on itself", dp)
            return null
        }
        if ((m.node.node["present"] as? Value.Bool)?.value == false && (from.node["present"] as? Value.Bool)?.value != false) {
            sink.error(Codes.PATH_UNRESOLVED, path, "'$ref' is not present, so nothing can attach to it", dp)
        }
        return m.node
    }

    private fun attach(x: Value, nodeDp: DataPath, path: String, node: Value.Map) {
        val dp = nodeDp.key("attach")
        if (x !is Value.Map) return error(path, "'attach' is a map {to, anchor, gap, offset, policy}", dp)
        for (k in x.keys) if (k !in CoreSchema.ATTACH_KEYS) error("$path.$k", "unknown key '$k' in attach; expected one of ${CoreSchema.ATTACH_KEYS.joinToString()}", dp.key(k))
        when (val to = x["to"]) {
            null -> error(path, "'attach' requires 'to'", dp)
            !is Value.Str -> error("$path.to", "'to' is a node or anchor path", dp.key("to"))
            else -> if (to.value.startsWith("viewport.")) {
                if (to.value.removePrefix("viewport.") !in GeometryRefs.ANCHORS) sink.error(Codes.PATH_UNRESOLVED, "$path.to", "'${to.value}': the viewport's anchors are ${GeometryRefs.ANCHORS.joinToString()}", dp.key("to"))
            } else {
                anchorPath(to.value, nodeDp, dp.key("to"), "$path.to", allowFloating = true)
            }
        }
        x["anchor"]?.let { a ->
            if (a !is Value.Str || a.value !in GeometryRefs.ANCHORS) error("$path.anchor", "'anchor' is one of ${GeometryRefs.ANCHORS.joinToString()}", dp.key("anchor"))
        }
        x["gap"]?.let { checkProp(ATTACH_PROPS.getValue("gap"), it, dp.key("gap"), "$path.gap") }
        x["side"]?.let { checkProp(ATTACH_PROPS.getValue("side"), it, dp.key("side"), "$path.side") }
        x["along"]?.let { checkProp(ATTACH_PROPS.getValue("along"), it, dp.key("along"), "$path.along") }
        x["offset"]?.let { checkProp(ATTACH_PROPS.getValue("offset"), it, dp.key("offset"), "$path.offset") }
        when (val p = x["policy"]) {
            null -> {}
            !is Value.Str -> error("$path.policy", "'policy' is one of ${CoreSchema.POLICIES.joinToString()}", dp.key("policy"))
            else -> when (p.value) {
                !in CoreSchema.POLICIES -> error("$path.policy", "'policy' is one of ${CoreSchema.POLICIES.joinToString()}", dp.key("policy"))
                "stretch" -> error("$path.policy", "'stretch' needs two attached anchors; only connectors have them in 0.1", dp.key("policy"))
                else -> {}
            }
        }
        val parent = info(nodeDp)?.parent
        val layout = (parent?.node?.get("layout") as? Value.Str)?.value ?: "free"
        // in a graph container the nodes are what the edges connect: an attached child keeps following its target
        if (layout != "free" && !Layouts.isGraph(layout) && layout != "sequence") sink.warning(Codes.ATTACH_IGNORED, path, "the parent lays out its children ($layout); the container wins and 'attach' is ignored", dp)
        for (k in listOf("gap", "offset")) x[k]?.let { v -> refsIn(v).filter { it.substringBefore('.') !in setOf("theme", "viewport") }.forEach { geometryRef(it, nodeDp, dp.key(k), "$path.$k") } }
    }

    /** The rank hint of a layered container: `first`, `last` or `{same_as: <node>}`. */
    private fun rank(x: Value, nodeDp: DataPath, path: String) {
        val dp = nodeDp.key("rank")
        val expected = "'rank' is ${Layouts.RANKS.joinToString(", ")} or {same_as: <node>}"
        when (x) {
            is Value.Str -> if (x.value !in Layouts.RANKS) error(path, expected, dp)
            is Value.Map -> {
                val to = x["same_as"]
                if (x.keys.any { it != "same_as" } || to !is Value.Str) return error(path, expected, dp)
                val from = info(nodeDp) ?: return
                val m = index.resolve(to.value, from)
                when {
                    m == null -> sink.error(Codes.PATH_UNRESOLVED, "$path.same_as", "'${to.value}' matches no node in scope", dp.key("same_as"))
                    m.rest.isNotEmpty() -> sink.error(Codes.PATH_UNRESOLVED, "$path.same_as", "'${to.value}' does not name a node", dp.key("same_as"))
                    else -> encapsulation(m, to.value, "$path.same_as", dp.key("same_as"))
                }
            }
            else -> error(path, expected, dp)
        }
    }

    private fun padding(x: Value, dp: DataPath, path: String) {
        val sides = setOf("top", "right", "bottom", "left", "x", "y")
        when {
            x is Value.Map -> for ((k, s) in x.entries) {
                if (k !in sides) error("$path.$k", "padding sides are ${sides.joinToString()}", dp.key(k))
                checkProp(PADDING_SIDE, s, dp.key(k), "$path.$k")
            }
            else -> checkProp(PADDING_SIDE, x, dp, path)
        }
    }

    /** Every `theme.name` reference in a canonical value must name a token in scope. */
    private fun tokenRefs(x: Value, scope: Set<String>, dp: DataPath, path: String, nodeDp: DataPath? = null) {
        for (ref in refsIn(x)) {
            val parts = ref.split('.')
            if (parts[0] == "viewport") continue
            if (parts[0] != "theme") {
                nodeDp?.let { geometryRef(ref, it, dp, path) }
                continue
            }
            if (parts.size != 2) {
                sink.error(Codes.PATH_UNRESOLVED, path, "a token reference is 'theme.<name>', found '$ref'", dp)
            } else if (parts[1] !in scope) {
                val known = if (scope.isEmpty()) "no theme is in scope (set 'base_theme')" else "tokens in scope: ${scope.sorted().joinToString()}"
                sink.error(Codes.TOKEN_MISSING, path, "no theme in scope provides token '${parts[1]}'; $known", dp, Value.map("token" to Value.Str(parts[1])))
            }
        }
    }

    private fun isExpr(x: Value, dp: DataPath, path: String): Boolean {
        if (x is Value.Str && Exprs.isAuthored(x.value)) return true // already reported by the processor
        if (x !is Value.Map) return false
        return try {
            Exprs.fromCanonical(x) != null
        } catch (e: ExprSyntaxException) {
            sink.error(Codes.EXPR_SYNTAX, path, "malformed expression: ${e.message}", dp)
            true
        }
    }

    private fun check(prop: Prop, x: Value, dp: DataPath, path: String, label: String) {
        if (prop.type.scalar && isExpr(x, dp, path)) return
        when (prop.type) {
            PType.NUMBER -> {
                if (x !is Value.Num) return error(path, "'${prop.name}' is a number, found ${x.typeName()}", dp)
                val bad = when (prop.name) {
                    "width", "height", "min_width", "min_height", "radius", "stroke_width", "corner_radius" -> x.value < 0
                    "font_size" -> x.value <= 0
                    "opacity" -> x.value < 0 || x.value > 1
                    else -> false
                }
                if (bad) error(path, "'${prop.name}' is out of range: ${x.value}", dp)
            }
            PType.STRING -> if (x !is Value.Str) error(path, "'${prop.name}' is a string, found ${x.typeName()}", dp)
            PType.BOOL -> if (x !is Value.Bool) error(path, "'${prop.name}' is true or false, found ${x.typeName()}", dp)
            PType.COLOR -> if (x !is Value.Str || !CoreSchema.COLOR_PATTERN.matches(x.value)) {
                val hint = if (x is Value.Null) " (an unquoted '#' starts a comment: quote the color)" else ""
                error(path, "'${prop.name}' is a color '#rrggbb' or '#rrggbbaa'$hint", dp)
            }
            PType.ENUM -> {
                if (x !is Value.Str || x.value !in prop.values) {
                    error(path, "'${prop.name}' is one of ${prop.values.joinToString()}", dp)
                }
            }
            PType.ID -> if (x !is Value.Str || !CoreSchema.ID_PATTERN.matches(x.value)) {
                error(path, "an id matches [a-z][a-z0-9_]*", dp)
            } else if (x.value in CoreSchema.RESERVED_ROOTS || isPropertyName(x.value)) {
                error(path, "'${x.value}' is reserved and cannot be used as an id", dp)
            }
            PType.POINT -> point(x, dp, path)
            PType.POINTS -> if (x !is Value.List) error(path, "'${prop.name}' is a list of {x, y} points", dp) else x.items.forEachIndexed { i, p -> point(p, dp.index(i), path) }
            PType.NUMBERS -> if (x !is Value.List || x.items.any { it !is Value.Num || it.value < 0 }) {
                error(path, "'${prop.name}' is a list of numbers, none negative", dp)
            } else if (x.items.isNotEmpty() && x.items.all { (it as Value.Num).value == 0.0 }) {
                error(path, "'${prop.name}' needs a length above zero", dp)
            }
            PType.NODES -> if (x !is Value.List) error(path, "'children' is a list of nodes", dp)
            PType.LAYERS -> layers(x, dp, path)
            PType.ANY -> {}
        }
    }

    private fun point(x: Value, dp: DataPath, path: String) {
        if (x !is Value.Map || x.keys != setOf("x", "y")) return error(path, "a point is a map {x, y}", dp)
        for (k in listOf("x", "y")) {
            val c = x[k]!!
            if (!isExpr(c, dp.key(k), path) && c !is Value.Num) error(path, "'$k' is a number, found ${c.typeName()}", dp.key(k))
        }
    }

    private fun layers(x: Value, dp: DataPath, path: String) {
        if (x !is Value.List) return error(path, "'layers' is a list of layer names", dp)
        val names = HashSet<String>()
        x.items.forEachIndexed { i, l ->
            val name = when (l) {
                is Value.Str -> l.value
                is Value.Map -> {
                    for (k in l.keys) {
                        when (k) {
                            "name" -> {}
                            "space" -> if ((l[k] as? Value.Str)?.value != "screen") error(path, "'space' is 'screen': the layer ignores the camera", dp.index(i).key(k))
                            "parallax" -> if (l[k] !is Value.Num) error(path, "'parallax' is the fraction of the camera's movement the layer follows", dp.index(i).key(k))
                            else -> error(path, "unknown layer option '$k'", dp.index(i).key(k))
                        }
                    }
                    (l["name"] as? Value.Str)?.value
                }
                else -> null
            }
            if (name == null) {
                error(path, "a layer is a name or a map with 'name'", dp.index(i))
            } else if (!names.add(name)) {
                error(path, "duplicate layer '$name'", dp.index(i))
            }
        }
    }

    /**
     * Names a path may end with on any node: geometry, anchors and the properties every node has.
     * The properties of one kind of node are only reserved under a node of that kind (see [scopedIds]).
     */
    private fun isPropertyName(s: String): Boolean =
        s in GeometryRefs.PROPERTIES || s in GeometryRefs.ANCHORS || s in CoreSchema.commonNames || s == "shape" || s == "use" || s == "media"

    /**
     * An id is read right after its scope (`group.ordering`): it cannot be one of the properties of
     * that scope, a container's (`layout`, `gap`, `ordering`...) or an instance's (its parameters).
     */
    private fun scopedIds() {
        for (n in index.all()) {
            val id = n.id ?: continue
            if (isPropertyName(id) || id in CoreSchema.RESERVED_ROOTS) continue // reported with the id itself
            val scope = generateSequence(n.parent) { it.parent }.firstOrNull { it.id != null || it.template != null || it.isRoot } ?: continue
            val clash = when {
                // a part of a template body is the library's to name: the library reports it
                scope.template != null && n.owner === scope -> false
                scope.template != null -> id in scope.template.params || CoreSchema.instance[id] != null
                scope.shape == null -> CoreSchema.container[id] != null
                else -> false
            }
            if (clash) error(n.path, "'$id' is a property of ${if (scope.isRoot) "the scene" else scope.path}, so a path could not tell them apart; rename it", n.dp.key("id"))
        }
    }
}

/** Properties of the camera (spec section 14). */
internal val CAMERA_KEYS = listOf("fit", "padding", "into", "center", "zoom", "rotation", "attach", "states", "state")

/** Camera properties a track can drive. */
internal val CAMERA_ANIMATABLE = listOf("center", "zoom", "rotation", "padding")

/** Properties no track can drive: they define what a node is or where its data lives. */
internal val NOT_ANIMATABLE = setOf("id", "children", "states", "state", "use", "slots", "timeline", "enter", "exit", "style", "attach", "layers")

internal fun article(word: String) = if (word.firstOrNull()?.lowercaseChar() in setOf('a', 'e', 'i', 'o', 'u')) "an" else "a"

package diagrams.processor

import diagrams.model.Value

/**
 * A document's effective schema as JSON Schema (draft 2020-12): the core schema plus one definition
 * per template of the imported libraries (spec section 9, "Types and schemas"). It is written for
 * validation, editor completion and grammar-constrained decoding of authored documents, so every
 * scalar also accepts an `=` expression string.
 */
object JsonSchema {
    private fun s(v: String) = Value.Str(v)
    private fun obj(vararg p: Pair<String, Value>) = Value.map(*p)
    private fun arr(items: List<Value>) = Value.List(items)
    private fun ref(name: String) = obj("\$ref" to s("#/\$defs/$name"))
    private fun anyOf(vararg xs: Value) = obj("anyOf" to arr(xs.toList()))
    private val EXPR = ref("expr")

    fun document(env: Environment, title: String? = null): Value {
        val defs = LinkedHashMap<String, Value>()
        defs["expr"] = obj("type" to s("string"), "pattern" to s("^=(?!=)"), "description" to s("An expression: '=' then the expression grammar of spec section 9"))
        defs["number"] = anyOf(obj("type" to s("number")), EXPR)
        defs["boolean"] = anyOf(obj("type" to s("boolean")), EXPR)
        defs["color"] = anyOf(obj("type" to s("string"), "pattern" to s("^#([0-9a-fA-F]{8}|[0-9a-fA-F]{6})$")), EXPR)
        defs["point"] = closed(linkedMapOf("x" to ref("number"), "y" to ref("number")), listOf("x", "y"))
        defs["id"] = obj("type" to s("string"), "pattern" to s("^[a-z][a-z0-9_]*$"))
        defs["attach"] = closed(
            linkedMapOf(
                "to" to obj("type" to s("string"), "description" to s("A node path (floating anchor) or node path and anchor")),
                "anchor" to obj("enum" to arr(GeometryRefs.ANCHORS.map { s(it) })),
                "gap" to ref("number"),
                "offset" to ref("point"),
                "policy" to obj("enum" to arr(listOf(s("follow"), s("block")))),
            ),
            listOf("to"),
        )
        val side = ref("number")
        defs["padding"] = anyOf(ref("number"), closed(linkedMapOf("top" to side, "right" to side, "bottom" to side, "left" to side, "x" to side, "y" to side), emptyList()))
        val ease = anyOf(obj("enum" to arr(StateContainer.EASES.map { s(it) })), closed(linkedMapOf("bezier" to obj("type" to s("array"), "items" to obj("type" to s("number")), "minItems" to Value.num(4), "maxItems" to Value.num(4))), listOf("bezier")))
        defs["state"] = closed(
            linkedMapOf(
                "id" to ref("id"),
                "set" to obj("type" to s("object"), "description" to s("Property path (from the container's owner) to value: 'card.fill', 'step.present', 'order.state'")),
                "extends" to obj("type" to s("string")),
                "children" to obj("type" to s("array"), "items" to ref("state")),
                "edges" to obj("type" to s("object"), "additionalProperties" to obj("type" to s("string"))),
                "timeline" to ref("timeline"),
            ),
            listOf("id"),
        )
        val stateList = obj("type" to s("array"), "items" to ref("state"))
        defs["states"] = closed(
            linkedMapOf(
                "defaults" to closed(linkedMapOf("duration" to obj("type" to s("number")), "ease" to ease), emptyList()),
                "cumulative" to obj("type" to s("boolean")),
                "sequence" to stateList,
                "tree" to stateList,
                "graph" to stateList,
                "bag" to stateList,
                "transitions" to obj("type" to s("array"), "items" to obj("type" to s("object"), "description" to s("{from, to, duration, ease}, optionally with a time item as the body, where {auto: all} is the automatic transition"), "properties" to obj("from" to obj("type" to s("string")), "to" to obj("type" to s("string")), "duration" to obj("type" to s("number")), "ease" to ease), "required" to arr(listOf(s("from"), s("to"))))),
            ),
            emptyList(),
            "A state container (spec section 11): exactly one of sequence, tree, graph, bag",
        )
        defs["node"] = anyOf(*(listOf("container") + CoreSchema.SHAPES + CoreSchema.media.keys.map { "media_$it" } + env.modules.values.flatMap { m -> m.templates.values.filter { !it.isClip }.map { it.ref } }).map { ref(it) }.toTypedArray())
        timeDefs(defs, env, ease)

        defs["container"] = closed(props(CoreSchema.container), emptyList(), "A node with children and no shape: it places its children")
        for ((name, kind) in CoreSchema.shapes) {
            val p = linkedMapOf<String, Value>("shape" to obj("const" to s(name)))
            p.putAll(props(kind))
            defs[name] = closed(p, listOf("shape") + kind.props.filter { it.required }.map { it.name })
        }
        for ((name, kind) in CoreSchema.media) {
            val p = linkedMapOf<String, Value>("media" to obj("const" to s(name)))
            p.putAll(props(kind))
            defs["media_$name"] = closed(p, listOf("media") + kind.props.filter { it.required }.map { it.name })
        }
        defs["document_assets"] = obj("type" to s("object"), "additionalProperties" to closed(linkedMapOf("kind" to obj("enum" to arr(ASSET_KINDS.map { s(it) })), "file" to obj("type" to s("string"))), listOf("kind", "file")))
        for (m in env.modules.values) for (t in m.templates.values) defs[t.ref] = if (t.isClip) clip(t) else template(t)

        return obj(
            "\$schema" to s("https://json-schema.org/draft/2020-12/schema"),
            "title" to s(title ?: "diagrams/0.1 document"),
            "type" to s("object"),
            "required" to arr(listOf(s("format"), s("viewport"), s("scene"))),
            "properties" to Value.Map(
                linkedMapOf(
                    "format" to obj("const" to s(CoreSchema.FORMAT)),
                    "title" to obj("type" to s("string")),
                    "viewport" to closed(linkedMapOf("width" to obj("type" to s("number")), "height" to obj("type" to s("number"))), listOf("width", "height")),
                    "imports" to obj("type" to s("object"), "additionalProperties" to obj("type" to s("string"))),
                    "base_theme" to obj("type" to s("string")),
                    "styles" to obj("type" to s("object"), "additionalProperties" to obj("type" to s("object"))),
                    "assets" to ref("document_assets"),
                    "camera" to ref("camera"),
                    "scene" to ref("container"),
                ),
            ),
            "additionalProperties" to Value.Bool(false),
            "\$defs" to Value.Map(defs),
        )
    }

    /** Time items (spec section 12), the timeline, and the camera (spec section 14). */
    private fun timeDefs(defs: LinkedHashMap<String, Value>, env: Environment, ease: Value) {
        val num = obj("type" to s("number"), "minimum" to Value.num(0))
        val str = obj("type" to s("string"))
        fun timing(p: LinkedHashMap<String, Value>): LinkedHashMap<String, Value> {
            p["id"] = ref("id")
            p["at"] = anyOf(num, obj("type" to s("string"), "pattern" to s("\\.cue\\[[a-z0-9_]+]$"), "description" to s("A cue of a media node: narration.cue[intro]")))
            p["after"] = str
            p["with"] = str
            return p
        }
        val items = obj("type" to s("array"), "items" to ref("item"))
        val key = closed(linkedMapOf("at" to num, "value" to obj(), "ease" to ease), listOf("at", "value"))
        defs["track"] = closed(
            timing(linkedMapOf("track" to obj("type" to s("string"), "description" to s("Property path from the timeline's owner: card.opacity, camera.zoom")), "target" to str, "keys" to obj("type" to s("array"), "items" to key, "minItems" to Value.num(1)), "ease" to ease, "along" to str)),
            listOf("track", "keys"),
        )
        defs["sequence"] = closed(timing(linkedMapOf("sequence" to items)), listOf("sequence"), "Children one after the other")
        defs["parallel"] = closed(timing(linkedMapOf("parallel" to items)), listOf("parallel"), "Children together")
        defs["stagger"] = closed(timing(linkedMapOf("stagger" to items, "interval" to num)), listOf("stagger", "interval"), "Children started at a fixed interval")
        defs["wait"] = closed(timing(linkedMapOf("wait" to num)), listOf("wait"))
        defs["auto"] = closed(timing(linkedMapOf("auto" to obj("const" to s("all")), "duration" to num, "ease" to ease)), listOf("auto"), "The automatic transition, in a transition body")
        val clips = env.modules.values.flatMap { m -> m.templates.values.filter { it.isClip }.map { it.ref } }
        defs["item"] = anyOf(*(listOf("track", "sequence", "parallel", "stagger", "wait", "auto") + clips).map { ref(it) }.toTypedArray())
        defs["timeline"] = obj("allOf" to arr(listOf(ref("item"))), "properties" to obj("loop" to obj("type" to s("boolean"))), "description" to s("A time item; loop: true repeats it"))
        defs["camera"] = closed(
            linkedMapOf(
                "fit" to obj("type" to s("string"), "description" to s("Path of the node to frame, or 'scene'")),
                "padding" to ref("number"),
                "into" to closed(linkedMapOf("x" to obj("type" to s("number")), "y" to obj("type" to s("number")), "width" to obj("type" to s("number")), "height" to obj("type" to s("number"))), listOf("x", "y", "width", "height")),
                "center" to ref("point"),
                "zoom" to ref("number"),
                "rotation" to ref("number"),
                "attach" to closed(linkedMapOf("to" to str), listOf("to")),
                "states" to ref("states"),
                "state" to str,
            ),
            emptyList(),
            "The camera (spec section 14)",
        )
    }

    /** A clip instance in a timeline: its parameters and the timing keys. */
    private fun clip(t: Template): Value {
        val p = linkedMapOf<String, Value>("use" to obj("const" to s(t.ref)), "id" to ref("id"), "at" to obj(), "after" to obj("type" to s("string")), "with" to obj("type" to s("string")))
        for (param in t.params.values) p[param.name] = param(param)
        return closed(p, listOf("use") + t.params.values.filter { it.required && it.name != "target" && it.name != "duration" }.map { it.name }, t.description)
    }

    private fun closed(properties: Map<String, Value>, required: List<String>, description: String? = null): Value {
        val out = linkedMapOf<String, Value>("type" to s("object"))
        description?.let { out["description"] = s(it) }
        out["properties"] = Value.Map(properties)
        if (required.isNotEmpty()) out["required"] = arr(required.map { s(it) })
        out["additionalProperties"] = Value.Bool(false)
        return Value.Map(out)
    }

    private fun props(kind: NodeKind): LinkedHashMap<String, Value> {
        val out = LinkedHashMap<String, Value>()
        for (p in kind.props) out[p.name] = prop(p)
        return out
    }

    private fun prop(p: Prop): Value = when (p.name) {
        "style" -> anyOf(obj("type" to s("string")), obj("type" to s("array"), "items" to obj("type" to s("string"))))
        "theme" -> anyOf(obj("type" to s("string")), obj("type" to s("object"), "additionalProperties" to anyOf(obj("type" to s("string")), obj("type" to s("number")), obj("type" to s("boolean")))))
        "attach" -> ref("attach")
        "states" -> ref("states")
        "timeline" -> ref("timeline")
        "enter", "exit" -> ref("item")
        "padding" -> ref("padding")
        "slots" -> obj("type" to s("object"), "additionalProperties" to obj("type" to s("array"), "items" to ref("node")))
        else -> when (p.type) {
            PType.NUMBER -> ref("number")
            PType.STRING -> obj("type" to s("string"))
            PType.BOOL -> ref("boolean")
            PType.COLOR -> ref("color")
            PType.ENUM -> anyOf(obj("enum" to arr(p.values.sorted().map { s(it) })), EXPR)
            PType.ID -> ref("id")
            PType.POINT -> ref("point")
            PType.POINTS -> obj("type" to s("array"), "items" to ref("point"))
            PType.NODES -> obj("type" to s("array"), "items" to ref("node"))
            PType.LAYERS -> obj("type" to s("array"), "items" to anyOf(obj("type" to s("string")), obj("type" to s("object"), "properties" to obj("name" to obj("type" to s("string"))), "required" to arr(listOf(s("name"))))))
            PType.ANY -> obj()
        }
    }

    private fun template(t: Template): Value {
        val p = linkedMapOf<String, Value>("use" to obj("const" to s(t.ref)))
        for (prop in CoreSchema.instance.props) {
            if (prop.name == "children" && "default" !in t.slots) continue
            if (prop.name == "slots") {
                val named = t.slots - "default"
                if (named.isNotEmpty()) p["slots"] = closed(named.associateWith { obj("type" to s("array"), "items" to ref("node")) }, emptyList())
                continue
            }
            p[prop.name] = prop(prop)
        }
        for (param in t.params.values) p[param.name] = param(param)
        return closed(p, listOf("use") + t.params.values.filter { it.required }.map { it.name }, t.description)
    }

    private fun param(p: Param): Value {
        val base: Value = when (p.type) {
            ParamType.STRING, ParamType.PATH -> obj("type" to s("string"))
            ParamType.NUMBER -> ref("number")
            ParamType.BOOLEAN -> ref("boolean")
            ParamType.COLOR -> ref("color")
            ParamType.ENUM -> anyOf(obj("enum" to arr(p.values.map { s(it) })), EXPR)
            ParamType.ANY -> obj()
            ParamType.OBJECT -> closed(p.fields.mapValues { param(it.value) }, p.fields.values.filter { it.required }.map { it.name })
            ParamType.LIST -> {
                val item = p.item?.let { param(it) } ?: obj()
                val items = if (p.keyed) {
                    obj("type" to s("object"), "minProperties" to Value.num(1), "maxProperties" to Value.num(1), "propertyNames" to obj("pattern" to s("^[a-z][a-z0-9_]*$")), "additionalProperties" to item)
                } else {
                    item
                }
                obj("type" to s("array"), "items" to items)
            }
        }
        val extra = linkedMapOf<String, Value>()
        p.description?.let { extra["description"] = s(it) }
        p.default?.let { extra["default"] = it }
        if (extra.isEmpty()) return base
        return if (base is Value.Map && base["\$ref"] == null) Value.Map(base.entries + extra) else Value.Map(linkedMapOf<String, Value>("allOf" to arr(listOf(base))) + extra)
    }
}

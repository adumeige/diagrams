package diagrams.processor

import diagrams.model.Codes
import diagrams.model.DataPath
import diagrams.model.DiagnosticSink
import diagrams.model.ExprSyntaxException
import diagrams.model.Exprs
import diagrams.model.Value
import diagrams.model.typeName

/** Parameter types of a template schema (spec section 9). */
enum class ParamType(val label: String, val scalar: Boolean) {
    STRING("string", true),
    NUMBER("number", true),
    BOOLEAN("boolean", true),
    COLOR("color", true),
    ENUM("enum", true),

    /** A node or anchor path, resolved from the instance like a connector's `from`. */
    PATH("path", true),
    LIST("list", false),
    OBJECT("object", false),
    ANY("any", false),
    ;

    companion object {
        fun parse(s: String): ParamType? = entries.firstOrNull { it.label == s }
    }
}

/** One declared parameter. A parameter without a default is required. */
class Param(
    val name: String,
    val type: ParamType,
    val default: Value? = null,
    val values: List<String> = emptyList(),
    val item: Param? = null,
    val keyed: Boolean = false,
    val fields: Map<String, Param> = emptyMap(),
    val description: String? = null,
) {
    val required get() = default == null
}

/** What a template promises to the outside (spec section 9, `exports`). */
class Exports(
    val anchors: List<String> = emptyList(),
    val parts: List<String> = emptyList(),
    val states: List<String> = emptyList(),
    val tokens: List<String> = emptyList(),
)

/** A library template: a parameter schema, an interface and a body (a node, in authored form). */
class Template(
    val module: String,
    val name: String,
    val params: Map<String, Param>,
    val exports: Exports,
    /** `box`, `ellipse` or the id of a part: the outline that floating anchors and routing use. */
    val outline: String,
    val body: Value.Map,
    val description: String?,
) {
    val ref get() = "$module.$name"

    /** A clip (spec section 12): a template whose body is a time item, used in timelines, not in the scene. */
    val isClip: Boolean get() = Timelines.isTimeItem(body)

    /** Names of the slots in the body. */
    val slots: Set<String> by lazy {
        val out = LinkedHashSet<String>()
        fun walk(v: Value) {
            when (v) {
                is Value.Map -> {
                    (v["slot"] as? Value.Str)?.let { out += it.value }
                    v.entries.values.forEach(::walk)
                }
                is Value.List -> v.items.forEach(::walk)
                else -> {}
            }
        }
        walk(body)
        out
    }
}

internal val TEMPLATE_KEYS = listOf("description", "extends", "set", "params", "exports", "outline", "body")
internal val PARAM_KEYS = listOf("type", "default", "values", "item", "keyed", "fields", "description")

/** Keys of a body node that are template constructs, not properties. */
internal val BODY_CONSTRUCTS = setOf("for_each", "as", "do", "if", "slot")

/** Reads the `templates` map of a library, checking the schemas and the references in the bodies. */
internal class TemplateReader(private val alias: String, private val s: DiagnosticSink) {
    fun read(v: Value?, dp: DataPath): Map<String, Template> {
        if (v == null) return emptyMap()
        if (v !is Value.Map) {
            s.error(Codes.SCHEMA, "$alias.templates", "'templates' is a map of template names to definitions", dp)
            return emptyMap()
        }
        val out = LinkedHashMap<String, Template>()
        val derived = v.entries.filter { (_, t) -> t is Value.Map && t["extends"] != null }
        for ((name, t) in v.entries) if (t !is Value.Map || t["extends"] == null) template(name, t, dp.key(name))?.let { out[name] = it }
        // a template that extends another comes once its base is read, in as many rounds as the chain is long
        var pending = derived.map { it.key }
        while (pending.isNotEmpty()) {
            val ready = pending.filter { n -> (v[n] as Value.Map)["extends"].let { (it as? Value.Str)?.value in out || it !is Value.Str } }
            if (ready.isEmpty()) {
                for (n in pending) {
                    val base = ((v[n] as Value.Map)["extends"] as? Value.Str)?.value
                    s.error(Codes.SCHEMA, "$alias.templates.$n.extends", "'$base' is not a template of this library, or it extends $n in turn", dp.key(n).key("extends"))
                }
                break
            }
            for (n in ready) derive(n, v[n] as Value.Map, out, dp.key(n))?.let { out[n] = it }
            pending = pending - ready.toSet()
        }
        return LinkedHashMap(v.keys.mapNotNull { n -> out[n]?.let { n to it } }.toMap())
    }

    /**
     * A template that extends another (`extends: relation`): the base's parameters less those [set]
     * fixes, plus its own; its body is the base with every parameter passed through. Exports and
     * outline are the base's unless it declares its own.
     */
    private fun derive(name: String, t: Value.Map, known: Map<String, Template>, dp: DataPath): Template? {
        val p = "$alias.templates.$name"
        val baseName = (t["extends"] as? Value.Str)?.value
        val base = baseName?.let { known[it] } ?: run {
            s.error(Codes.SCHEMA, "$p.extends", "'extends' names a template of this library", dp.key("extends"))
            return null
        }
        if (t["body"] != null) s.error(Codes.SCHEMA, "$p.body", "a template that extends another has no body of its own", dp.key("body"))
        val set = when (val x = t["set"]) {
            null -> emptyMap()
            is Value.Map -> x.entries
            else -> {
                s.error(Codes.SCHEMA, "$p.set", "'set' is a map of the base's parameters to the values this template fixes", dp.key("set"))
                emptyMap()
            }
        }
        for (k in set.keys) if (k !in base.params) s.error(Codes.SCHEMA, "$p.set.$k", "'$k' is not a parameter of $baseName; parameters: ${base.params.keys.joinToString()}", dp.key("set").key(k))
        val params = LinkedHashMap(base.params.filterKeys { it !in set })
        own(t["params"], dp, p).forEach { (k, v) -> params[k] = v }
        val body = LinkedHashMap<String, Value>()
        body["use"] = Value.Str(baseName)
        for (k in base.params.keys) body[k] = set[k] ?: if (k in params) Value.Str("=params.$k") else continue
        val exports = if (t["exports"] != null) exports(t["exports"], dp.key("exports"), "$p.exports") else base.exports
        val outline = if (t["outline"] != null) outline(t["outline"], dp, p) else base.outline
        val description = (t["description"] as? Value.Str)?.value ?: base.description
        val b = Value.Map(body)
        checkRefs(b, params.keys, setOf("params"), dp.key("set"), "$p.set")
        return Template(alias, name, params, exports, outline, b, description)
    }

    private fun own(ps: Value?, dp: DataPath, p: String): Map<String, Param> {
        val params = LinkedHashMap<String, Param>()
        when (ps) {
            null -> {}
            !is Value.Map -> s.error(Codes.SCHEMA, "$p.params", "'params' is a map of parameter names to schemas", dp.key("params"))
            else -> for ((pn, pv) in ps.entries) {
                when {
                    !CoreSchema.ID_PATTERN.matches(pn) -> s.error(Codes.SCHEMA, "$p.params.$pn", "a parameter name matches [a-z][a-z0-9_]*", dp.key("params").key(pn))
                    pn in INSTANCE_RESERVED -> s.error(Codes.SCHEMA, "$p.params.$pn", "'$pn' is a property of every instance and cannot be a parameter", dp.key("params").key(pn))
                    else -> param(pn, pv, dp.key("params").key(pn), "$p.params.$pn")?.let { params[pn] = it }
                }
            }
        }
        return params
    }

    /** `box`, `ellipse`, or the id of a part whose shape is the outline (a polygon, an ellipse, a rect). */
    private fun outline(o: Value?, dp: DataPath, p: String, body: Value? = null): String = when {
        o == null -> "box"
        o is Value.Str && o.value in setOf("box", "ellipse") -> o.value
        o is Value.Str && CoreSchema.ID_PATTERN.matches(o.value) && (body == null || hasId(body, o.value)) -> o.value
        else -> {
            s.error(Codes.SCHEMA, "$p.outline", "'outline' is box, ellipse, or the id of a part of the body", dp.key("outline"))
            "box"
        }
    }

    private fun ids(v: Value): Set<String> = when (v) {
        is Value.Map -> setOfNotNull((v["id"] as? Value.Str)?.value) + v.entries.filterKeys { it != "params" }.values.flatMap { ids(it) }
        is Value.List -> v.items.flatMap { ids(it) }.toSet()
        else -> emptySet()
    }

    private fun hasId(v: Value, id: String): Boolean = when (v) {
        is Value.Map -> (v["id"] as? Value.Str)?.value == id || v.entries.values.any { hasId(it, id) }
        is Value.List -> v.items.any { hasId(it, id) }
        else -> false
    }

    private fun template(name: String, t: Value, dp: DataPath): Template? {
        val p = "$alias.templates.$name"
        if (!CoreSchema.ID_PATTERN.matches(name)) {
            s.error(Codes.SCHEMA, p, "a template name matches [a-z][a-z0-9_]*", dp)
            return null
        }
        if (t !is Value.Map) {
            s.error(Codes.SCHEMA, p, "a template is a map {params, exports, outline, body}", dp)
            return null
        }
        for (k in t.keys) if (k !in TEMPLATE_KEYS) s.error(Codes.SCHEMA, "$p.$k", "unknown key '$k' in a template; expected one of ${TEMPLATE_KEYS.joinToString()}", dp.key(k))
        if (t["set"] != null) s.error(Codes.SCHEMA, "$p.set", "'set' goes with 'extends'", dp.key("set"))
        val params = own(t["params"], dp, p)
        val exports = exports(t["exports"], dp.key("exports"), "$p.exports")
        val outline = outline(t["outline"], dp, p, t["body"])
        val body = t["body"]
        if (body !is Value.Map) {
            s.error(Codes.SCHEMA, "$p.body", "a template needs a 'body': one node", dp.key("body"))
            return null
        }
        if (Timelines.isTimeItem(body)) {
            checkRefs(body, params.keys, setOf("params"), dp.key("body"), "$p.body")
            return Template(alias, name, params, exports, outline, body, (t["description"] as? Value.Str)?.value)
        }
        for (c in listOf("for_each", "slot")) if (body[c] != null) s.error(Codes.SCHEMA, "$p.body", "the body root is one node; '$c' cannot stand at the root", dp.key("body"))
        if (body["id"] != null) s.error(Codes.SCHEMA, "$p.body.id", "the body root takes the instance's id; it cannot have its own", dp.key("body").key("id"))
        checkRefs(body, params.keys, setOf("params"), dp.key("body"), "$p.body")
        // a state or a patch sets a parameter by path (`order.attributes`): a part cannot take its name
        for (id in ids(body)) if (id in params) s.error(Codes.SCHEMA, "$p.body", "the part '$id' has the name of a parameter, so a path could not tell them apart; rename the part", dp.key("body"))
        val description = (t["description"] as? Value.Str)?.value
        return Template(alias, name, params, exports, outline, body, description)
    }

    fun param(name: String, v: Value, dp: DataPath, p: String): Param? {
        val m = when (v) {
            is Value.Str -> Value.map("type" to v) // shorthand: `name: string`
            is Value.Map -> v
            else -> {
                s.error(Codes.SCHEMA, p, "a parameter is a schema map {type, default, ...}", dp)
                return null
            }
        }
        for (k in m.keys) if (k !in PARAM_KEYS) s.error(Codes.SCHEMA, "$p.$k", "unknown key '$k' in a parameter schema; expected one of ${PARAM_KEYS.joinToString()}", dp.key(k))
        val typeName = (m["type"] as? Value.Str)?.value
        val type = typeName?.let { ParamType.parse(it) }
        if (type == null) {
            s.error(Codes.SCHEMA, "$p.type", "'type' is one of ${ParamType.entries.joinToString { it.label }}", dp.key("type"))
            return null
        }
        val values = (m["values"] as? Value.List)?.items.orEmpty().mapNotNull { (it as? Value.Str)?.value }
        if (type == ParamType.ENUM && values.isEmpty()) s.error(Codes.SCHEMA, "$p.values", "an enum parameter lists its 'values'", dp)
        val item = if (type == ParamType.LIST) {
            m["item"]?.let { param("item", it, dp.key("item"), "$p.item") } ?: Param("item", ParamType.ANY)
        } else {
            null
        }
        val keyed = (m["keyed"] as? Value.Bool)?.value ?: false
        if (keyed && type != ParamType.LIST) s.error(Codes.SCHEMA, "$p.keyed", "only a list can be keyed", dp.key("keyed"))
        val fields = LinkedHashMap<String, Param>()
        (m["fields"] as? Value.Map)?.entries?.forEach { (fn, fv) -> param(fn, fv, dp.key("fields").key(fn), "$p.fields.$fn")?.let { fields[fn] = it } }
        val out = Param(name, type, null, values, item, keyed, fields, (m["description"] as? Value.Str)?.value)
        val default = m["default"] ?: return out
        val ds = DiagnosticSink()
        ParamCheck(ds).check(out, default, dp.key("default"), "$p.default", allowExpr = false)
        ds.all.forEach { s.error(it.code, it.path, "default: ${it.message}", dp.key("default")) }
        return Param(name, type, default, values, item, keyed, fields, out.description)
    }

    private fun exports(v: Value?, dp: DataPath, p: String): Exports {
        if (v == null) return Exports()
        if (v !is Value.Map) {
            s.error(Codes.SCHEMA, p, "'exports' is a map {anchors, parts, states, tokens}", dp)
            return Exports()
        }
        fun names(k: String): List<String> {
            val x = v[k] ?: return emptyList()
            if (x !is Value.List || x.items.any { it !is Value.Str }) {
                s.error(Codes.SCHEMA, "$p.$k", "'$k' is a list of names", dp.key(k))
                return emptyList()
            }
            return x.items.map { (it as Value.Str).value }
        }
        for (k in v.keys) if (k !in setOf("anchors", "parts", "states", "tokens")) s.error(Codes.SCHEMA, "$p.$k", "unknown key '$k' in exports", dp.key(k))
        val anchors = names("anchors")
        for (a in anchors) if (a !in GeometryRefs.ANCHORS) s.error(Codes.SCHEMA, "$p.anchors", "'$a' is not an anchor; custom anchors are not supported in 0.1, use one of ${GeometryRefs.ANCHORS.joinToString()}", dp.key("anchors"))
        return Exports(anchors, names("parts"), names("states"), names("tokens"))
    }

    /** Every `params.x` reference in the body names a declared parameter; loop variables are in scope in their `do`. */
    private fun checkRefs(v: Value, params: Set<String>, roots: Set<String>, dp: DataPath, p: String) {
        when (v) {
            is Value.Str -> if (Exprs.isAuthored(v.value)) {
                val e = try {
                    Exprs.parse(v.value)
                } catch (e: ExprSyntaxException) {
                    s.error(Codes.EXPR_SYNTAX, p, "expression '${v.value}': ${e.message}", dp)
                    return
                }
                for (r in Exprs.refs(e)) refOk(r, params, roots, dp, p)
            }
            is Value.Map -> {
                val loop = v["for_each"]
                var inner = roots
                if (loop != null) {
                    val src = (loop as? Value.Str)?.value
                    if (src == null) {
                        s.error(Codes.SCHEMA, "$p.for_each", "'for_each' is a path to a list parameter, such as params.items", dp.key("for_each"))
                    } else {
                        refOk(src.removePrefix("="), params, roots, dp.key("for_each"), "$p.for_each")
                    }
                    val asName = (v["as"] as? Value.Str)?.value ?: "item"
                    if (!CoreSchema.ID_PATTERN.matches(asName) || asName in setOf("params", "theme", "viewport", "scene", "index")) {
                        s.error(Codes.SCHEMA, "$p.as", "'as' names the loop variable; '$asName' is not allowed", dp.key("as"))
                    }
                    if (v["do"] == null) s.error(Codes.SCHEMA, p, "'for_each' needs 'do': the node (or list of nodes) to repeat", dp)
                    for (k in v.keys) if (k !in setOf("for_each", "as", "do", "id")) s.error(Codes.SCHEMA, "$p.$k", "a for_each entry takes for_each, as, do and id; '$k' goes on the repeated node", dp.key(k))
                    inner = roots + asName
                }
                if (v["slot"] != null) {
                    if ((v["slot"] as? Value.Str)?.value?.let { CoreSchema.ID_PATTERN.matches(it) } != true) s.error(Codes.SCHEMA, "$p.slot", "'slot' names the slot: default or [a-z][a-z0-9_]*", dp.key("slot"))
                    for (k in v.keys) if (k != "slot") s.error(Codes.SCHEMA, "$p.$k", "a slot placeholder takes no other key", dp.key(k))
                }
                v["if"]?.let { c ->
                    val src = (c as? Value.Str)?.value
                    if (src == null && c !is Value.Bool) s.error(Codes.SCHEMA, "$p.if", "'if' is an expression over parameters", dp.key("if"))
                    else if (src != null) checkRefs(Value.Str(if (src.startsWith("=")) src else "=$src"), params, inner, dp.key("if"), "$p.if")
                }
                for ((k, x) in v.entries) {
                    if (k == "for_each" || k == "if") continue
                    checkRefs(x, params, if (k == "do") inner else roots.let { if (loop != null) inner else it }, dp.key(k), "$p.$k")
                }
            }
            is Value.List -> v.items.forEachIndexed { i, x -> checkRefs(x, params, roots, dp.index(i), p) }
            else -> {}
        }
    }

    private fun refOk(r: String, params: Set<String>, roots: Set<String>, dp: DataPath, p: String) {
        val steps = Exprs.steps(r)
        val root = steps.firstOrNull() ?: return
        if (root == "params") {
            if (roots.contains("params") && steps.getOrNull(1) !in params) {
                s.error(Codes.PATH_UNRESOLVED, p, "'$r': the template declares no parameter '${steps.getOrNull(1) ?: ""}'; parameters: ${params.joinToString()}", dp)
            }
        }
    }

    companion object {
        /** Instance keys that are not parameters. */
        val INSTANCE_RESERVED = setOf("id", "use", "children", "slots") + CoreSchema.instance.props.map { it.name }
    }
}

/** Checks a value against a parameter schema. Scalars may be expressions when [allowExpr]. */
internal class ParamCheck(private val s: DiagnosticSink) {
    fun check(p: Param, v: Value, dp: DataPath, path: String, allowExpr: Boolean = true) {
        if (p.type.scalar && allowExpr && isExpr(v)) return
        fun bad(expected: String) = s.error(Codes.SCHEMA, path, "'${p.name}' is $expected, found ${describe(v)}", dp)
        when (p.type) {
            ParamType.STRING, ParamType.PATH -> if (v !is Value.Str) bad("a string")
            ParamType.NUMBER -> if (v !is Value.Num) bad("a number")
            ParamType.BOOLEAN -> if (v !is Value.Bool) bad("true or false")
            ParamType.COLOR -> if (v !is Value.Str || !CoreSchema.COLOR_PATTERN.matches(v.value)) bad("a color '#rrggbb' or '#rrggbbaa'")
            ParamType.ENUM -> if (v !is Value.Str || v.value !in p.values) bad("one of ${p.values.joinToString()}")
            ParamType.ANY -> {}
            ParamType.OBJECT -> {
                if (v !is Value.Map) return bad("a map")
                for ((k, x) in v.entries) {
                    val f = p.fields[k]
                    if (f == null) {
                        if (p.fields.isNotEmpty()) s.error(Codes.SCHEMA, "$path.$k", "unknown field '$k'; fields: ${p.fields.keys.joinToString()}", dp.key(k))
                    } else {
                        check(f, x, dp.key(k), "$path.$k", allowExpr)
                    }
                }
                for (f in p.fields.values) if (f.required && v[f.name] == null) s.error(Codes.SCHEMA, path, "missing field '${f.name}'", dp)
            }
            ParamType.LIST -> {
                if (v !is Value.List) return bad("a list")
                val keys = HashSet<String>()
                v.items.forEachIndexed { i, x ->
                    val at = dp.index(i)
                    if (p.keyed) {
                        val m = x as? Value.Map
                        if (m == null || m.entries.size != 1) {
                            s.error(Codes.SCHEMA, "$path[$i]", "a keyed list item is a one-key map, such as '- total: Money'", at)
                            return@forEachIndexed
                        }
                        val (k, iv) = m.entries.entries.first()
                        if (!CoreSchema.ID_PATTERN.matches(k)) s.error(Codes.SCHEMA, "$path[$k]", "an item key matches [a-z][a-z0-9_]* (it addresses the item: path[key])", at)
                        if (!keys.add(k)) s.error(Codes.SCHEMA, "$path[$k]", "duplicate key '$k'", at)
                        p.item?.let { check(it, iv, at.key(k), "$path[$k]", allowExpr) }
                    } else {
                        p.item?.let { check(it, x, at, "$path[$i]", allowExpr) }
                    }
                }
            }
        }
    }

    private fun describe(v: Value) = when (v) {
        is Value.Str -> "'${v.value}'"
        is Value.Num -> diagrams.model.formatNumber(v.value)
        else -> v.typeName()
    }

    private fun isExpr(v: Value): Boolean = when (v) {
        is Value.Str -> Exprs.isAuthored(v.value)
        is Value.Map -> try {
            Exprs.fromCanonical(v) != null
        } catch (_: ExprSyntaxException) {
            false
        }
        else -> false
    }
}

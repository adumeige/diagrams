package diagrams.processor

import diagrams.model.Codes
import diagrams.model.DataPath
import diagrams.model.DiagnosticSink
import diagrams.model.Expr
import diagrams.model.ExprSyntaxException
import diagrams.model.Exprs
import diagrams.model.Value
import diagrams.model.typeName

/**
 * Template expansion (spec section 9): every instance is replaced by its template's body, with
 * parameters substituted, `for_each` repeated, `if` decided and slots filled. Expressions that only
 * read parameters and loop variables become literals; references to tokens, the viewport and geometry
 * stay for the resolver. The expanded nodes are canonical: defaults are filled in.
 *
 * The instance's own properties (`id`, `at`, `attach`, `theme`, ...) go onto the body root. A keyed
 * `for_each` with an `id` names its items `id[key]`, which is how paths address them.
 *
 * Unknown templates and parameter errors are the validator's to report; expansion reports what only
 * shows while expanding (a loop over a non-list, a template cycle).
 */
class Expander(private val env: Environment, private val sink: DiagnosticSink) {
    private val canon = Canonicalizer(DiagnosticSink(), env)

    fun expand(doc: Value): ENode? {
        val scene = (doc as? Value.Map)?.get("scene") as? Value.Map ?: return null
        return docNode(scene, DataPath.ROOT.key("scene"), "scene", isRoot = true)
    }

    /** A node of the document itself (canonical). */
    private fun docNode(v: Value.Map, dp: DataPath, path: String, isRoot: Boolean = false): ENode? {
        if (v["use"] != null) {
            val slots = docSlots(v, dp, path)
            return instance(v, dp, path, null, slots, emptyList())
        }
        val kids = (v["children"] as? Value.List)?.items.orEmpty()
        return ENode(
            without(v, "children"),
            dp,
            kids.mapIndexedNotNull { i, c -> (c as? Value.Map)?.let { docNode(it, dp.key("children").index(i), childPathOf(path, it)) } },
        )
    }

    private fun docSlots(v: Value.Map, dp: DataPath, path: String): Map<String, List<ENode>> {
        val out = LinkedHashMap<String, List<ENode>>()
        (v["children"] as? Value.List)?.let { l ->
            out["default"] = l.items.mapIndexedNotNull { i, c -> (c as? Value.Map)?.let { docNode(it, dp.key("children").index(i), childPathOf(path, it)) } }
        }
        (v["slots"] as? Value.Map)?.entries?.forEach { (name, l) ->
            out[name] = (l as? Value.List)?.items.orEmpty().mapIndexedNotNull { i, c ->
                (c as? Value.Map)?.let { docNode(it, dp.key("slots").key(name).index(i), childPathOf(path, it)) }
            }
        }
        return out
    }

    private class Ctx(
        val template: Template,
        val vars: Map<String, Value>,
        val tag: InstanceTag,
        val path: String,
        val dp: DataPath,
        val stack: List<String>,
        val slots: Map<String, List<ENode>>,
    ) {
        fun with(name: String, v: Value) = Ctx(template, vars + (name to v), tag, path, dp, stack, slots)
    }

    /**
     * Expands one instance. [v] holds the instance's properties and parameters (already substituted
     * when the instance sits in another template's body); [owner] is the instance that wrote it.
     */
    private fun instance(v: Value.Map, dp: DataPath, path: String, owner: InstanceTag?, slots: Map<String, List<ENode>>, stack: List<String>): ENode? {
        val ref = (v["use"] as? Value.Str)?.value ?: return null
        val t = env.template(ref) ?: return null
        if (t.isClip) return null // a clip plays in a timeline; the validator reports it
        if (ref in stack) {
            sink.error(Codes.TEMPLATE_CYCLE, path, "template '$ref' uses itself through ${stack.joinToString(" -> ")} -> $ref", dp.key("use"))
            return null
        }
        val params = LinkedHashMap<String, Value>()
        for (p in t.params.values) (v[p.name] ?: p.default)?.let { params[p.name] = it }
        val tag = InstanceTag(t)
        val ctx = Ctx(t, mapOf("params" to Value.Map(params)), tag, path, dp, stack + ref, slots)
        val before = sink.all.size
        val roots = body(t.body, ctx, dp.key("\$body"))
        val root = roots.singleOrNull() ?: run {
            if (sink.all.size == before) sink.error(Codes.SCHEMA, path, "the body of '$ref' expanded to ${roots.size} nodes; it must give exactly one", dp)
            return null
        }
        val props = LinkedHashMap(root.node.entries)
        props.remove("id")
        for ((k, x) in v.entries) {
            if (k == "use" || k == "children" || k == "slots" || k in t.params) continue
            if (CoreSchema.instance[k] == null && k != "id") continue
            val mine = props[k]
            props[k] = when {
                k == "theme" && mine is Value.Map && x is Value.Map -> Value.Map(mine.entries + x.entries)
                k == "style" && mine != null -> Value.List((styleNames(mine) + styleNames(x)).map { Value.Str(it) })
                else -> x
            }
        }
        // a body whose root is another instance: that instance's inside now belongs to this one
        val kids = root.instance?.let { inner -> root.children.map { reown(it, inner, tag) } } ?: root.children
        return ENode(Value.Map(props), dp, kids, instance = tag, owner = owner)
    }

    /**
     * A clip's body with its arguments substituted (spec section 12, "Clips"): a time item whose
     * expressions over parameters are folded to literals. Clips used inside it are qualified with the
     * clip's library alias.
     */
    fun clip(t: Template, args: Value.Map, path: String, dp: DataPath): Value {
        val params = LinkedHashMap<String, Value>()
        for (p in t.params.values) (args[p.name] ?: p.default)?.let { params[p.name] = it }
        val ctx = Ctx(t, mapOf("params" to Value.Map(params)), InstanceTag(t), path, dp, listOf(t.ref), emptyMap())
        val known = { n: String -> env.modules[t.module]?.templates?.containsKey(n) == true }
        fun walk(x: Value, at: DataPath): Value = when (x) {
            is Value.Map -> if (isCanonicalExpr(x)) {
                subst(x, ctx, at)
            } else {
                Value.Map(x.entries.mapValuesTo(LinkedHashMap()) { (k, c) -> if (k == "use" && c is Value.Str) qualify(c.value, t, known) else walk(c, at.key(k)) })
            }
            is Value.List -> Value.List(x.items.mapIndexed { i, c -> walk(c, at.index(i)) })
            else -> subst(x, ctx, at)
        }
        return walk(t.body, dp.key("\$body"))
    }

    private fun reown(e: ENode, from: InstanceTag, to: InstanceTag): ENode =
        ENode(e.node, e.dp, e.children.map { reown(it, from, to) }, e.instance, if (e.owner === from) to else e.owner)

    /** Expands one node of a template body: zero or more nodes. */
    private fun body(raw: Value, ctx: Ctx, dp: DataPath): List<ENode> {
        if (raw !is Value.Map) return emptyList()
        (raw["slot"] as? Value.Str)?.let { return ctx.slots[it.value].orEmpty() }
        if (raw["for_each"] != null) return forEach(raw, ctx, dp)
        var v: Value.Map = raw
        raw["if"]?.let { c ->
            val keep = condition(c, ctx, dp.key("if")) ?: return emptyList()
            if (!keep) return emptyList()
            v = without(v, "if")
        }
        val props = LinkedHashMap<String, Value>()
        for ((k, x) in v.entries) {
            if (k == "children" || k == "slots") continue
            props[k] = when (k) {
                "use" -> qualify((x as? Value.Str)?.value ?: "", ctx.template) { env.modules[ctx.template.module]?.templates?.containsKey(it) == true }
                "style" -> qualifyStyles(x, ctx.template)
                else -> subst(x, ctx, dp.key(k))
            }
        }
        val nodePath = childPathOf(ctx.path, Value.Map(props))
        if (props["use"] != null) {
            // a template used inside a body: its slot content is body content, expanded here
            val slots = LinkedHashMap<String, List<ENode>>()
            (v["children"] as? Value.List)?.let { l -> slots["default"] = l.items.flatMapIndexed { i, c -> body(c, ctx, dp.key("children").index(i)) } }
            (v["slots"] as? Value.Map)?.entries?.forEach { (name, l) ->
                slots[name] = (l as? Value.List)?.items.orEmpty().flatMapIndexed { i, c -> body(c, ctx, dp.key("slots").key(name).index(i)) }
            }
            return listOfNotNull(instance(Value.Map(props), dp, nodePath, ctx.tag, slots, ctx.stack))
        }
        val kids = (v["children"] as? Value.List)?.items.orEmpty().flatMapIndexed { i, c -> body(c, ctx, dp.key("children").index(i)) }
        return listOf(ENode(canonical(Value.Map(props), dp, nodePath), dp, kids, owner = ctx.tag))
    }

    private fun forEach(raw: Value.Map, ctx: Ctx, dp: DataPath): List<ENode> {
        val src = ((raw["for_each"] as? Value.Str)?.value ?: return emptyList()).removePrefix("=")
        val list = lookup(src, ctx.vars)
        if (list !is Value.List) {
            sink.error(Codes.SCHEMA, ctx.path, "for_each '$src' is not a list (found ${list?.typeName() ?: "nothing"})", ctx.dp)
            return emptyList()
        }
        val steps = Exprs.steps(src)
        val keyed = steps.size == 2 && steps[0] == "params" && ctx.template.params[steps[1]]?.keyed == true
        val asName = (raw["as"] as? Value.Str)?.value ?: "item"
        val idBase = (raw["id"] as? Value.Str)?.value
        val doNodes = when (val d = raw["do"]) {
            is Value.List -> d.items
            null -> emptyList()
            else -> listOf(d)
        }
        if (idBase != null && doNodes.size != 1) {
            sink.error(Codes.SCHEMA, ctx.path, "a for_each with an id repeats one node; 'do' has ${doNodes.size}", ctx.dp)
            return emptyList()
        }
        val out = mutableListOf<ENode>()
        list.items.forEachIndexed { i, item ->
            val pair = (item as? Value.Map)?.entries?.entries?.singleOrNull()
            val key: String
            val value: Value
            if (keyed && pair != null) {
                key = pair.key
                value = Value.map("key" to Value.Str(pair.key), "value" to pair.value)
            } else {
                key = i.toString()
                value = item
            }
            val inner = ctx.with(asName, value).with("index", Value.Num(i.toDouble()))
            for ((j, d) in doNodes.withIndex()) {
                val nodes = body(d, inner, dp.key("do").index(i * doNodes.size + j))
                out += if (idBase != null) nodes.map { renamed(it, "$idBase[$key]") } else nodes
            }
        }
        return out
    }

    private fun renamed(e: ENode, id: String) = ENode(Value.Map(e.node.entries + ("id" to Value.Str(id))), e.dp, e.children, e.instance, e.owner)

    private fun condition(c: Value, ctx: Ctx, dp: DataPath): Boolean? {
        val src = when (c) {
            is Value.Bool -> return c.value
            is Value.Str -> if (c.value.startsWith("=")) c.value else "=${c.value}"
            else -> return null
        }
        return when (val r = subst(Value.Str(src), ctx, dp)) {
            is Value.Bool -> r.value
            else -> {
                sink.error(Codes.SCHEMA, ctx.path, "'if: $src' must decide from parameters alone and give true or false", ctx.dp)
                null
            }
        }
    }

    /** Substitutes parameters and loop variables in a value, folding what becomes constant. */
    private fun subst(x: Value, ctx: Ctx, dp: DataPath): Value {
        val e: Expr = when (x) {
            is Value.Str -> {
                if (!Exprs.isAuthored(x.value)) return x
                try {
                    Exprs.parse(x.value)
                } catch (err: ExprSyntaxException) {
                    sink.error(Codes.EXPR_SYNTAX, ctx.path, "in template '${ctx.template.ref}': '${x.value}': ${err.message}", ctx.dp)
                    return x
                }
            }
            is Value.Map -> {
                val asExpr = try {
                    Exprs.fromCanonical(x)
                } catch (_: ExprSyntaxException) {
                    null
                }
                asExpr ?: return Value.Map(x.entries.mapValuesTo(LinkedHashMap()) { (k, c) -> subst(c, ctx, dp.key(k)) })
            }
            is Value.List -> return Value.List(x.items.mapIndexed { i, c -> subst(c, ctx, dp.index(i)) })
            else -> return x
        }
        // a bare reference to a list or map parameter passes the value through (to a nested template)
        if (e is Expr.Ref) {
            val v = varValue(e.path, ctx) ?: return Exprs.toCanonical(e)
            if (v is Value.List || v is Value.Map && !isCanonicalExpr(v)) return v
            val one = Exprs.fold(toExpr(v))
            return if (one is Expr.Lit) literal(one.value) else Exprs.toCanonical(one)
        }
        val out = Exprs.fold(
            Exprs.substitute(e) { path ->
                val v = varValue(path, ctx) ?: return@substitute null
                toExpr(v)
            },
        )
        return if (out is Expr.Lit) literal(out.value) else Exprs.toCanonical(out)
    }

    /** A literal string starting with '=' keeps its escape so it is not read as an expression again. */
    private fun literal(v: Value): Value = if (v is Value.Str && v.value.startsWith("=")) Value.Str("=" + v.value) else v

    private fun toExpr(v: Value): Expr = when (v) {
        is Value.Str -> if (Exprs.isAuthored(v.value)) {
            try {
                Exprs.parse(v.value)
            } catch (_: ExprSyntaxException) {
                Expr.Lit(v)
            }
        } else {
            Expr.Lit(Value.Str(Exprs.unescape(v.value)))
        }
        is Value.Map -> try {
            Exprs.fromCanonical(v)
        } catch (_: ExprSyntaxException) {
            null
        } ?: Expr.Lit(Value.Str(""))
        is Value.Num, is Value.Bool -> Expr.Lit(v)
        else -> Expr.Lit(Value.Str(""))
    }

    /** The value of a reference whose root is a parameter or loop variable; null for other roots. */
    private fun varValue(path: String, ctx: Ctx): Value? {
        val root = Exprs.steps(path).firstOrNull() ?: return null
        if (root !in ctx.vars) return null
        val v = lookup(path, ctx.vars)
        // undeclared and missing parameters are reported by the library reader and the validator
        if (v == null && root != "params") sink.error(Codes.PATH_UNRESOLVED, ctx.path, "in template '${ctx.template.ref}': '$path' has no value", ctx.dp)
        return v
    }

    private fun lookup(path: String, vars: Map<String, Value>): Value? {
        val steps = Exprs.steps(path)
        var cur: Value = vars[steps.firstOrNull() ?: return null] ?: return null
        for (s in steps.drop(1)) {
            cur = if (s.startsWith("[")) {
                val k = s.substring(1, s.length - 1)
                val l = cur as? Value.List ?: return null
                l.items.firstNotNullOfOrNull { (it as? Value.Map)?.takeIf { m -> m.entries.size == 1 }?.get(k) } ?: return null
            } else {
                (cur as? Value.Map)?.get(s) ?: return null
            }
        }
        return cur
    }

    private fun qualify(name: String, t: Template, known: (String) -> Boolean): Value =
        Value.Str(if ('.' !in name && known(name)) "${t.module}.$name" else name)

    private fun qualifyStyles(x: Value, t: Template): Value {
        val known = { n: String -> env.modules[t.module]?.styles?.containsKey(n) == true }
        return when (x) {
            is Value.Str -> qualify(x.value, t, known)
            is Value.List -> Value.List(x.items.map { s -> (s as? Value.Str)?.let { qualify(it.value, t, known) } ?: s })
            else -> x
        }
    }

    /** Defaults filled in, as for a document node. */
    private fun canonical(v: Value.Map, dp: DataPath, path: String): Value.Map {
        val c = canon.node(v, dp, path, isRoot = false) as? Value.Map ?: return v
        return without(c, "children")
    }

    private fun without(v: Value.Map, key: String) = if (v[key] == null) v else Value.Map(v.entries.filterKeys { it != key })
}

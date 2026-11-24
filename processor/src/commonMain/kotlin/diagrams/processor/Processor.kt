package diagrams.processor

import diagrams.model.Codes
import diagrams.model.DataPath
import diagrams.model.Diagnostic
import diagrams.model.DiagnosticSink
import diagrams.model.ExprSyntaxException
import diagrams.model.Exprs
import diagrams.model.SourceMap
import diagrams.model.SourcePos
import diagrams.model.Value
import diagrams.model.Yaml
import diagrams.model.YamlWriter

/**
 * Output of the processor (spec section 3): the canonical document, a source map from canonical
 * paths to authored positions, and the structural diagnostics of the processor and validator.
 */
class ProcessResult(
    val canonical: Value?,
    val sourceMap: SourceMap,
    val diagnostics: List<Diagnostic>,
    /** Imported themes and libraries, base theme and document styles: the resolver needs them too. */
    val environment: Environment = Environment.EMPTY,
) {
    val hasErrors: Boolean get() = diagnostics.any { it.severity == diagrams.model.Severity.ERROR }
}

object Processor {
    /** Authored text to canonical document, then validation. The input is never modified. */
    fun process(text: String, file: String? = null, loader: ImportLoader = ImportLoader.NONE): ProcessResult {
        val sink = DiagnosticSink()
        val parsed = Yaml.parseOrDiagnose(text, file, sink)
            ?: return ProcessResult(null, SourceMap(file, emptyMap()), sink.all)
        return processValue(parsed.value, parsed.sourceMap, loader)
    }

    fun processValue(authored: Value, authoredMap: SourceMap, loader: ImportLoader = ImportLoader.NONE): ProcessResult {
        val sink = DiagnosticSink(authoredMap)
        val env = environment(authored, authoredMap.file, loader, sink)
        val canonical = Canonicalizer(sink, env).document(authored)
        val canonicalMap = canonicalSourceMap(canonical, authoredMap)
        val vsink = DiagnosticSink(canonicalMap)
        Validator(vsink, env).document(canonical)
        return ProcessResult(canonical, canonicalMap, sink.all + vsink.all, env)
    }

    /**
     * Reads `imports`, checks `base_theme` and the document's `styles`. Imported files are read
     * through [loader]; their diagnostics carry their own file positions.
     */
    private fun environment(doc: Value, file: String?, loader: ImportLoader, sink: DiagnosticSink): Environment {
        if (doc !is Value.Map) return Environment.EMPTY
        val root = DataPath.ROOT
        val modules = LinkedHashMap<String, Module>()
        when (val imports = doc["imports"]) {
            null -> {}
            !is Value.Map -> sink.error(Codes.SCHEMA, "imports", "'imports' is a map of aliases to file paths", root.key("imports"))
            else -> for ((alias, p) in imports.entries) {
                val dp = root.key("imports").key(alias)
                when {
                    !CoreSchema.ID_PATTERN.matches(alias) -> sink.error(Codes.SCHEMA, "imports.$alias", "an import alias matches [a-z][a-z0-9_]*", dp)
                    alias in CoreSchema.RESERVED_ROOTS -> sink.error(Codes.SCHEMA, "imports.$alias", "'$alias' is reserved and cannot be an import alias", dp)
                    p !is Value.Str -> sink.error(Codes.SCHEMA, "imports.$alias", "an import is a local file path", dp)
                    else -> ModuleLoader(loader, sink).load(alias, p.value, file, dp)?.let { modules[alias] = it }
                }
            }
        }
        val base = when (val b = doc["base_theme"]) {
            null -> null
            !is Value.Str -> {
                sink.error(Codes.SCHEMA, "base_theme", "'base_theme' is the alias of an imported theme", root.key("base_theme"))
                null
            }
            else -> {
                val alias = (doc["imports"] as? Value.Map)?.get(b.value)
                if (alias == null) sink.error(Codes.PATH_UNRESOLVED, "base_theme", "'${b.value}' is not an import alias", root.key("base_theme"))
                b.value.takeIf { it in modules }
            }
        }
        // asset names are shared by every theme of the document: the manifest is keyed by them
        val seen = HashMap<String, Module>()
        for (m in modules.values) for ((name, a) in m.assets) {
            val other = seen[name] ?: run { seen[name] = m; null } ?: continue
            if (other.assets[name] != a) {
                sink.error(Codes.SCHEMA, "imports.${m.alias}", "asset '$name' is declared by both '${other.alias}' and '${m.alias}' with different files", root.key("imports").key(m.alias))
            }
        }
        val styles = styles(doc["styles"], root.key("styles"), "", sink)
        val assets = assetDecls(doc["assets"], root.key("assets"), "", sink)
        val document = Module("document", file ?: "", emptyMap(), emptyMap(), assets)
        for ((name, a) in assets) for (m in modules.values) {
            if (m.assets[name] != null && m.assets[name] != a) sink.error(Codes.SCHEMA, "assets.$name", "asset '$name' is also declared by '${m.alias}' with a different file", root.key("assets").key(name))
        }
        return Environment(modules, base, styles, document)
    }

    /** Canonical keeps the authored structure, so each canonical path maps to itself or its nearest authored ancestor. */
    fun canonicalSourceMap(canonical: Value, authoredMap: SourceMap): SourceMap {
        val out = LinkedHashMap<DataPath, SourcePos>()
        fun walk(v: Value, p: DataPath) {
            authoredMap.lookup(p)?.let { out[p] = it }
            when (v) {
                is Value.Map -> v.entries.forEach { (k, x) -> walk(x, p.key(k)) }
                is Value.List -> v.items.forEachIndexed { i, x -> walk(x, p.index(i)) }
                else -> {}
            }
        }
        walk(canonical, DataPath.ROOT)
        return SourceMap(authoredMap.file, out)
    }

    fun sourceMapToValue(map: SourceMap): Value = Value.Map(
        map.positions.entries.associate { (p, pos) -> p.toString() to Value.List(listOf(Value.num(pos.line), Value.num(pos.column))) },
    )
}

/** The node path used in diagnostics: IDs from the root, unnamed nodes being transparent. */
fun childPathOf(parent: String, child: Value): String {
    val id = (child as? Value.Map)?.get("id") as? Value.Str ?: return parent
    return if (parent == "scene") id.value else "${parent}.${id.value}"
}

/** A property value in canonical form: structured expressions, points with canonical coordinates. */
internal fun canonicalValue(prop: Prop, av: Value, dp: DataPath, path: String, sink: DiagnosticSink): Value {
    fun scalar(x: Value, at: DataPath): Value {
        if (x is Value.Str && Exprs.isAuthored(x.value)) {
            return try {
                val c = Exprs.toCanonical(Exprs.parse(x.value))
                // a constant string expression starting with '=' must stay a literal
                if (c is Value.Str && c.value.startsWith("=")) Value.Str("=" + c.value) else c
            } catch (e: ExprSyntaxException) {
                sink.error(Codes.EXPR_SYNTAX, path, "expression '${x.value}': ${e.message}", at)
                x
            }
        }
        return x
    }
    fun point(x: Value, at: DataPath): Value {
        if (x !is Value.Map) return x
        return Value.Map(x.entries.mapValuesTo(LinkedHashMap()) { (k, c) -> if (k == "x" || k == "y") scalar(c, at.key(k)) else c })
    }
    return when {
        prop.type.scalar -> scalar(av, dp)
        prop.type == PType.POINT -> point(av, dp)
        prop.type == PType.POINTS && av is Value.List -> Value.List(av.items.mapIndexed { i, p -> point(p, dp.index(i)) })
        else -> av
    }
}

/** Typed members of `attach` and `padding`, so their expressions are canonicalized and printed. */
internal val ATTACH_PROPS = mapOf(
    "gap" to Prop("gap", PType.NUMBER),
    "offset" to Prop("offset", PType.POINT),
    "to" to Prop("to", PType.STRING),
    "anchor" to Prop("anchor", PType.STRING),
    "side" to Prop("side", PType.ENUM, values = CoreSchema.SIDES),
    "along" to Prop("along", PType.NUMBER),
)
internal val PADDING_SIDE = Prop("padding", PType.NUMBER)

/** Token overrides take literals or expressions (evaluated in the parent's token scope). */
internal val TOKEN_VALUE = Prop("token", PType.STRING)

/** Names of the styles a node applies: `style: name` or `style: [a, b]` (later wins). */
fun styleNames(v: Value?): List<String> = when (v) {
    is Value.Str -> listOf(v.value)
    is Value.List -> v.items.mapNotNull { (it as? Value.Str)?.value }
    else -> emptyList()
}

/**
 * Authored to canonical: purely syntactic, needs only the schemas and the imported libraries
 * (spec section 3). Defaults are filled in, except for properties a named style sets: precedence is
 * node value, then style, then default (spec section 10), and the resolver applies the style.
 */
internal class Canonicalizer(private val sink: DiagnosticSink, private val env: Environment) {
    fun document(doc: Value): Value {
        if (doc !is Value.Map) return doc
        val out = LinkedHashMap<String, Value>()
        for (k in CoreSchema.documentKeys) {
            val v = doc[k] ?: continue
            out[k] = when (k) {
                "scene" -> node(v, DataPath.ROOT.key("scene"), "scene", isRoot = true)
                "camera" -> camera(v)
                "styles" -> if (v is Value.Map) Value.Map(v.entries.mapValuesTo(LinkedHashMap()) { (name, s) -> env.styles[name] ?: s }) else v
                else -> v
            }
        }
        for ((k, v) in doc.entries) if (k !in out) out[k] = v
        return Value.Map(out)
    }

    /** The camera (spec section 14): expressions structured, its state container canonical. */
    private fun camera(v: Value): Value {
        if (v !is Value.Map) return v
        val dp = DataPath.ROOT.key("camera")
        return Value.Map(
            v.entries.mapValuesTo(LinkedHashMap()) { (k, x) ->
                if (k == "states") StateContainer.canonical(x, dp.key("states"), "camera.states", sink) else deepCanonical(x, dp.key(k), "camera.$k", sink)
            },
        )
    }

    /**
     * An instance: common properties as on any node (no defaults), parameters in schema order with
     * their defaults, expressions canonical at any depth, then slot content.
     */
    private fun instance(v: Value.Map, dp: DataPath, path: String): Value {
        val t = (v["use"] as? Value.Str)?.value?.let { env.template(it) }
        val out = LinkedHashMap<String, Value>()
        v["id"]?.let { out["id"] = it }
        v["use"]?.let { out["use"] = it }
        for (prop in CoreSchema.instance.props) {
            if (prop.name == "id" || prop.name == "children" || prop.name == "slots") continue
            v[prop.name]?.let { out[prop.name] = propValue(prop, it, dp, path) }
        }
        t?.params?.values?.forEach { p ->
            val x = v[p.name] ?: p.default ?: return@forEach
            out[p.name] = deep(x, dp.key(p.name), "$path.${p.name}")
        }
        for ((k, x) in v.entries) if (k !in out && k != "children" && k != "slots") out[k] = deep(x, dp.key(k), "$path.$k")
        (v["children"] as? Value.List)?.let { l ->
            out["children"] = Value.List(l.items.mapIndexed { i, c -> node(c, dp.key("children").index(i), childPathOf(path, c), isRoot = false) })
        }
        v["slots"]?.let { s ->
            out["slots"] = if (s is Value.Map) {
                Value.Map(
                    s.entries.mapValuesTo(LinkedHashMap()) { (name, l) ->
                        if (l is Value.List) Value.List(l.items.mapIndexed { i, c -> node(c, dp.key("slots").key(name).index(i), childPathOf(path, c), isRoot = false) }) else l
                    },
                )
            } else {
                s
            }
        }
        return Value.Map(out)
    }

    /** Every authored expression inside a parameter value, at any depth, in canonical form. */
    private fun deep(x: Value, dp: DataPath, path: String): Value = when (x) {
        is Value.Str -> canonicalValue(Prop("param", PType.STRING), x, dp, path, sink)
        is Value.Map -> if (isCanonicalExpr(x)) x else Value.Map(x.entries.mapValuesTo(LinkedHashMap()) { (k, c) -> deep(c, dp.key(k), path) })
        is Value.List -> Value.List(x.items.mapIndexed { i, c -> deep(c, dp.index(i), path) })
        else -> x
    }

    private fun propValue(prop: Prop, av: Value, dp: DataPath, path: String): Value = when {
        prop.name == "states" -> StateContainer.canonical(av, dp.key("states"), "$path.states", sink)
        prop.name == "timeline" || prop.name == "enter" || prop.name == "exit" -> deepCanonical(av, dp.key(prop.name), "$path.${prop.name}", sink)
        prop.name == "theme" && av is Value.Map -> Value.Map(
            av.entries.mapValuesTo(LinkedHashMap()) { (t, x) -> value(TOKEN_VALUE, x, dp.key("theme").key(t), "$path.theme.$t") },
        )
        prop.name == "attach" && av is Value.Map -> Value.Map(
            av.entries.mapValuesTo(LinkedHashMap()) { (k, x) -> ATTACH_PROPS[k]?.let { value(it, x, dp.key("attach").key(k), "$path.attach.$k") } ?: x },
        )
        prop.name == "padding" -> when (av) {
            is Value.Map -> Value.Map(av.entries.mapValuesTo(LinkedHashMap()) { (k, x) -> value(PADDING_SIDE, x, dp.key("padding").key(k), "$path.padding.$k") })
            else -> value(PADDING_SIDE, av, dp.key("padding"), "$path.padding")
        }
        else -> value(prop, av, dp.key(prop.name), "$path.${prop.name}")
    }

    fun node(v: Value, dp: DataPath, path: String, isRoot: Boolean): Value {
        if (v !is Value.Map) return v
        if (v["use"] != null && v["shape"] == null) return instance(v, dp, path)
        val kind = CoreSchema.kindOf(v) ?: return v
        val out = LinkedHashMap<String, Value>()
        v["id"]?.let { out["id"] = it }
        for (k in CoreSchema.KIND_KEYS) {
            if (k == "layout") continue
            v[k]?.let { out[k] = it }
        }
        if (kind === CoreSchema.container) {
            out["layout"] = v["layout"]?.let { value(kind["layout"]!!, it, dp.key("layout"), "$path.layout") } ?: kind["layout"]!!.default!!
        }
        val styled = styleNames(v["style"]).flatMap { env.style(it)?.keys.orEmpty() }.toSet()
        for (prop in kind.props) {
            if (prop.name == "id" || prop.name == "layout" || prop.name == "children" || prop.name in CoreSchema.DECORATIONS) continue
            val av = v[prop.name]
            when {
                av != null -> out[prop.name] = propValue(prop, av, dp, path)
                prop.name in styled -> {}
                prop.default != null && prop.supported && !(isRoot && prop.name in ROOT_EXEMPT) -> out[prop.name] = prop.default
            }
        }
        for ((k, x) in v.entries) if (k !in out && k != "children" && k !in CoreSchema.DECORATIONS) out[k] = x
        if (kind === CoreSchema.container) {
            for (k in CoreSchema.DECORATIONS) (v[k] as? Value.List)?.let { l ->
                out[k] = Value.List(l.items.mapIndexed { i, c -> node(c, dp.key(k).index(i), childPathOf(path, c), isRoot = false) })
            }
            val children = v["children"]
            out["children"] = when (children) {
                null -> Value.List(emptyList())
                is Value.List -> Value.List(
                    children.items.mapIndexed { i, c -> node(c, dp.key("children").index(i), childPathOf(path, c), isRoot = false) },
                )
                else -> children
            }
        }
        return Value.Map(out)
    }

    private fun value(prop: Prop, av: Value, dp: DataPath, path: String): Value = canonicalValue(prop, av, dp, path, sink)

    companion object {
        /** The root is placed by the viewport and has no parent layering context. */
        val ROOT_EXEMPT = setOf("at", "layer")
    }
}

/** Canonical to authored: structured expressions become `=` strings again (spec section 3, "printer"). */
object Printer {
    fun toAuthored(canonical: Value): Value {
        if (canonical !is Value.Map) return canonical
        return Value.Map(
            canonical.entries.mapValuesTo(LinkedHashMap()) { (k, v) ->
                when {
                    k == "scene" -> node(v)
                    k == "camera" && v is Value.Map -> Value.Map(v.entries.mapValuesTo(LinkedHashMap()) { (ck, x) -> if (ck == "states") StateContainer.printed(x) { deep(it) } else deep(x) })
                    k == "styles" && v is Value.Map -> Value.Map(v.entries.mapValuesTo(LinkedHashMap()) { (_, s) -> style(s) })
                    else -> v
                }
            },
        )
    }

    fun print(canonical: Value): String = YamlWriter.write(toAuthored(canonical))

    private fun style(s: Value): Value =
        if (s is Value.Map) Value.Map(s.entries.mapValuesTo(LinkedHashMap()) { (k, x) -> anyProp(k)?.let { prop(it, x) } ?: x }) else s

    private fun prop(prop: Prop, x: Value): Value = when {
        prop.type.scalar -> scalar(x)
        prop.type == PType.POINT -> point(x)
        prop.type == PType.POINTS && x is Value.List -> Value.List(x.items.map { point(it) })
        else -> x
    }

    private fun node(v: Value): Value {
        if (v !is Value.Map) return v
        if (v["use"] != null && v["shape"] == null) return instance(v)
        val kind = CoreSchema.kindOf(v) ?: return v
        return Value.Map(
            v.entries.mapValuesTo(LinkedHashMap()) { (k, x) ->
                val prop = kind[k]
                when {
                    (k == "children" || k in CoreSchema.DECORATIONS) && x is Value.List -> Value.List(x.items.map { node(it) })
                    prop == null -> x
                    else -> nodeProp(k, prop, x)
                }
            },
        )
    }

    private fun nodeProp(k: String, prop: Prop, x: Value): Value = when {
        k == "states" -> StateContainer.printed(x) { deep(it) }
        k == "timeline" || k == "enter" || k == "exit" -> deep(x)
        k == "theme" && x is Value.Map -> Value.Map(x.entries.mapValuesTo(LinkedHashMap()) { (_, t) -> scalar(t) })
        k == "attach" && x is Value.Map -> Value.Map(x.entries.mapValuesTo(LinkedHashMap()) { (a, t) -> ATTACH_PROPS[a]?.let { prop(it, t) } ?: t })
        k == "padding" && x is Value.Map -> Value.Map(x.entries.mapValuesTo(LinkedHashMap()) { (_, t) -> scalar(t) })
        k == "padding" -> scalar(x)
        else -> prop(prop, x)
    }

    private fun instance(v: Value.Map): Value = Value.Map(
        v.entries.mapValuesTo(LinkedHashMap()) { (k, x) ->
            val prop = CoreSchema.instance[k]
            when {
                k == "children" && x is Value.List -> Value.List(x.items.map { node(it) })
                k == "slots" && x is Value.Map -> Value.Map(x.entries.mapValuesTo(LinkedHashMap()) { (_, l) -> if (l is Value.List) Value.List(l.items.map { node(it) }) else l })
                k == "id" || k == "use" -> x
                prop != null -> nodeProp(k, prop, x)
                else -> deep(x)
            }
        },
    )

    private fun deep(v: Value): Value = when (v) {
        is Value.Str -> v
        is Value.Map -> if (isCanonicalExpr(v)) scalar(v) else Value.Map(v.entries.mapValuesTo(LinkedHashMap()) { (_, x) -> deep(x) })
        is Value.List -> Value.List(v.items.map { deep(it) })
        else -> v
    }

    private fun point(v: Value): Value =
        if (v is Value.Map) Value.Map(v.entries.mapValuesTo(LinkedHashMap()) { (_, x) -> scalar(x) }) else v

    private fun scalar(v: Value): Value {
        val e = try {
            Exprs.fromCanonical(v)
        } catch (_: ExprSyntaxException) {
            null
        } ?: return v
        return Value.Str(Exprs.print(e))
    }
}

/** True for a map that is a canonical expression (`{add: [...]}`, `{ref: path}`). */
internal fun isCanonicalExpr(v: Value): Boolean = try {
    Exprs.fromCanonical(v) != null
} catch (_: ExprSyntaxException) {
    false
}

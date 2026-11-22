package diagrams.processor

import diagrams.model.Codes
import diagrams.model.DataPath
import diagrams.model.DiagnosticSink
import diagrams.model.Value
import diagrams.model.Yaml
import diagrams.model.typeName

/** A file the processor reads on behalf of an `imports` entry. */
class LoadedFile(val text: String, val file: String)

/**
 * Reads imported files. [load] receives the path as written in `imports` and the file that wrote it,
 * and returns null when there is no such file. The processor never touches the file system itself,
 * so it stays in common code.
 */
fun interface ImportLoader {
    fun load(path: String, from: String?): LoadedFile?

    companion object {
        val NONE = ImportLoader { _, _ -> null }
    }
}

/**
 * An imported theme or library (spec section 4): tokens and font assets for a theme, styles for both.
 * Styles hold canonical property values; tokens hold literals.
 */
class Module(
    val alias: String,
    val file: String,
    val tokens: Map<String, Value>,
    val styles: Map<String, Value.Map>,
    /** Asset name to its declaration `{kind, file}`, with `file` relative to [file]'s directory. */
    val assets: Map<String, Value.Map>,
    val templates: Map<String, Template> = emptyMap(),
)

/** Everything the document's imports and top-level declarations provide to its nodes. */
class Environment(
    val modules: Map<String, Module> = emptyMap(),
    val baseTheme: String? = null,
    /** Styles declared in the document itself, used by bare name. */
    val styles: Map<String, Value.Map> = emptyMap(),
    /** The document's own declarations (its `assets`), as a module named `document`. */
    val document: Module? = null,
) {
    /** Tokens at the root of the scene: those of the base theme. */
    val rootTokens: Map<String, Value> get() = baseTheme?.let { modules[it]?.tokens }.orEmpty()

    /** `name` is a document style, `alias.name` a style of an imported module. */
    /** `alias.name`: a template of an imported library. */
    fun template(ref: String): Template? {
        val dot = ref.indexOf('.')
        if (dot < 0) return null
        return modules[ref.substring(0, dot)]?.templates?.get(ref.substring(dot + 1))
    }

    fun style(ref: String): Value.Map? {
        val dot = ref.indexOf('.')
        if (dot < 0) return styles[ref]
        return modules[ref.substring(0, dot)]?.styles?.get(ref.substring(dot + 1))
    }

    /** All font assets of all modules, by asset name, with the module that declared each. */
    fun fontAssets(): Map<String, Pair<Module, Value.Map>> = assets("font")

    /** Every asset of the document and its imports, of the given kinds (all when empty), with its declaring module. */
    fun assets(vararg kinds: String): Map<String, Pair<Module, Value.Map>> {
        val out = LinkedHashMap<String, Pair<Module, Value.Map>>()
        for (m in listOfNotNull(document) + modules.values) for ((name, a) in m.assets) {
            if (kinds.isEmpty() || (a["kind"] as? Value.Str)?.value in kinds) if (name !in out) out[name] = m to a
        }
        return out
    }

    companion object {
        val EMPTY = Environment()
    }
}

/** Keys of a theme or library file. */
internal val MODULE_KEYS = listOf("format", "title", "imports", "tokens", "assets", "styles", "templates")

/** Properties a style cannot set: they define what a node is, not how it looks. */
internal val NOT_STYLABLE = setOf("id", "shape", "use", "media", "layout", "children", "layers", "style", "states", "state", "theme")

/** The first schema property of any node kind with this name, used to type a style's values. */
internal fun anyProp(name: String): Prop? =
    if (name in NOT_STYLABLE) null else CoreSchema.container[name] ?: (CoreSchema.shapes.values + CoreSchema.media.values).firstNotNullOfOrNull { it[name] }

/** Loads, parses and checks one imported file. Diagnostics carry the imported file's own positions. */
internal class ModuleLoader(private val loader: ImportLoader, private val sink: DiagnosticSink) {
    fun load(alias: String, path: String, from: String?, dp: DataPath): Module? {
        val loaded = loader.load(path, from)
        if (loaded == null) {
            sink.error(Codes.PATH_UNRESOLVED, "imports.$alias", "cannot read import '$path'", dp)
            return null
        }
        val msink = DiagnosticSink()
        val parsed = Yaml.parseOrDiagnose(loaded.text, loaded.file, msink)
        if (parsed == null) {
            msink.all.forEach(sink::add)
            return null
        }
        val fsink = DiagnosticSink(parsed.sourceMap)
        val root = DataPath.ROOT
        val doc = parsed.value as? Value.Map
        if (doc == null) {
            fsink.error(Codes.SCHEMA, alias, "an imported file is a map, found ${parsed.value.typeName()}", root)
            fsink.all.forEach(sink::add)
            return null
        }
        when (val f = doc["format"]) {
            null -> fsink.error(Codes.SCHEMA, "$alias.format", "missing required key 'format' (expected '${CoreSchema.FORMAT}')", root)
            else -> if ((f as? Value.Str)?.value != CoreSchema.FORMAT) fsink.error(Codes.SCHEMA, "$alias.format", "unsupported format; this toolset reads '${CoreSchema.FORMAT}'", root.key("format"))
        }
        for (k in doc.keys) {
            when (k) {
                !in MODULE_KEYS -> fsink.error(Codes.SCHEMA, "$alias.$k", "unknown key '$k' in an imported file; expected one of ${MODULE_KEYS.joinToString()}", root.key(k))
                "imports" -> fsink.warning(Codes.UNSUPPORTED, "$alias.imports", "imports inside an imported file are not implemented yet and are ignored", root.key(k))
            }
        }
        val tokens = tokens(doc["tokens"], root.key("tokens"), alias, fsink)
        val assets = assets(doc["assets"], root.key("assets"), alias, fsink)
        val styles = styles(doc["styles"], root.key("styles"), alias, fsink)
        val templates = TemplateReader(alias, fsink).read(doc["templates"], root.key("templates"))
        fsink.all.forEach(sink::add)
        return Module(alias, loaded.file, tokens, styles, assets, templates)
    }

    private fun tokens(v: Value?, dp: DataPath, alias: String, s: DiagnosticSink): Map<String, Value> {
        if (v == null) return emptyMap()
        if (v !is Value.Map) {
            s.error(Codes.SCHEMA, "$alias.tokens", "'tokens' is a map of token names to values", dp)
            return emptyMap()
        }
        val out = LinkedHashMap<String, Value>()
        for ((name, x) in v.entries) {
            val p = "$alias.tokens.$name"
            when {
                !CoreSchema.ID_PATTERN.matches(name) -> s.error(Codes.SCHEMA, p, "a token name matches [a-z][a-z0-9_]*", dp.key(name))
                x is Value.Str && diagrams.model.Exprs.isAuthored(x.value) ->
                    s.error(Codes.SCHEMA, p, "a theme token is a literal value; expressions in themes are not supported in 0.1", dp.key(name))
                x is Value.Str || x is Value.Num || x is Value.Bool -> out[name] = x
                else -> s.error(Codes.SCHEMA, p, "a token is a string, number or boolean, found ${x.typeName()}", dp.key(name))
            }
        }
        return out
    }

    private fun assets(v: Value?, dp: DataPath, alias: String, s: DiagnosticSink): Map<String, Value.Map> = assetDecls(v, dp, alias, s)
}

/** Asset kinds (spec section 15): fonts for themes, and the first media kinds. */
val ASSET_KINDS = setOf("font", "image", "audio", "video")

/** Reads an `assets` map {name: {kind, file}} of a theme, a library or the document. */
internal fun assetDecls(v: Value?, dp: DataPath, owner: String, s: DiagnosticSink): Map<String, Value.Map> {
    if (v == null) return emptyMap()
    val prefix = if (owner.isEmpty()) "assets" else "$owner.assets"
    if (v !is Value.Map) {
        s.error(Codes.SCHEMA, prefix, "'assets' is a map of asset names to {kind, file}", dp)
        return emptyMap()
    }
    val out = LinkedHashMap<String, Value.Map>()
    for ((name, x) in v.entries) {
        val p = "$prefix.$name"
        val m = x as? Value.Map
        val kind = (m?.get("kind") as? Value.Str)?.value
        val file = (m?.get("file") as? Value.Str)?.value
        when {
            !CoreSchema.ID_PATTERN.matches(name) -> s.error(Codes.SCHEMA, p, "an asset name matches [a-z][a-z0-9_]*", dp.key(name))
            m == null || kind == null || file == null -> s.error(Codes.SCHEMA, p, "an asset is a map {kind, file}", dp.key(name))
            m.keys.any { it != "kind" && it != "file" } -> s.error(Codes.SCHEMA, p, "an asset declares only 'kind' and 'file'; facts about it go in the manifest", dp.key(name))
            kind !in ASSET_KINDS -> s.error(Codes.SCHEMA, p, "asset kind '$kind' is not one of ${ASSET_KINDS.joinToString()}", dp.key(name).key("kind"))
            else -> out[name] = m
        }
    }
    return out
}

/** The paths of every `{ref: path}` inside a canonical value. */
fun refsIn(v: Value): List<String> {
    val out = mutableListOf<String>()
    fun walk(x: Value) {
        when (x) {
            is Value.Map -> {
                val r = x["ref"]
                if (x.entries.size == 1 && r is Value.Str) out += r.value else x.entries.values.forEach(::walk)
            }
            is Value.List -> x.items.forEach(::walk)
            else -> {}
        }
    }
    walk(v)
    return out
}

/** Checks and canonicalizes a `styles` map, in a theme, a library or the document. */
internal fun styles(
    v: Value?,
    dp: DataPath,
    owner: String,
    s: DiagnosticSink,
): Map<String, Value.Map> {
    if (v == null) return emptyMap()
    val prefix = if (owner.isEmpty()) "styles" else "$owner.styles"
    if (v !is Value.Map) {
        s.error(Codes.SCHEMA, prefix, "'styles' is a map of style names to property bundles", dp)
        return emptyMap()
    }
    val out = LinkedHashMap<String, Value.Map>()
    for ((name, x) in v.entries) {
        val p = "$prefix.$name"
        if (!CoreSchema.ID_PATTERN.matches(name)) {
            s.error(Codes.SCHEMA, p, "a style name matches [a-z][a-z0-9_]*", dp.key(name))
            continue
        }
        if (x !is Value.Map) {
            s.error(Codes.SCHEMA, p, "a style is a map of properties", dp.key(name))
            continue
        }
        val props = LinkedHashMap<String, Value>()
        for ((k, pv) in x.entries) {
            val prop = anyProp(k)
            if (prop == null) {
                val why = if (k in NOT_STYLABLE) "a style cannot set '$k'" else "unknown property '$k'"
                s.error(Codes.SCHEMA, "$p.$k", "$why in style '$name'", dp.key(name).key(k))
                continue
            }
            val cv = canonicalValue(prop, pv, dp.key(name).key(k), "$p.$k", s)
            Validator(s).checkProp(prop, cv, dp.key(name).key(k), "$p.$k")
            props[k] = cv
        }
        out[name] = Value.Map(props)
    }
    return out
}

package diagrams.model

enum class Severity(val label: String) {
    ERROR("error"), WARNING("warning"), INFO("info");

    companion object {
        fun parse(s: String): Severity? = entries.firstOrNull { it.label == s }
    }
}

/**
 * Every finding is structured data (spec section 18, "Common shape").
 *
 * [path] is the node path of the finding (`order.title`, `viewport.width`); [dataPath] locates
 * the offending value in the canonical document and is used to look up [source].
 */
data class Diagnostic(
    val code: String,
    val severity: Severity,
    val path: String,
    val message: String,
    val source: Source? = null,
    val data: Value.Map? = null,
) {
    data class Source(val file: String?, val line: Int, val column: Int)

    fun toValue(): Value.Map {
        val m = linkedMapOf<String, Value>(
            "code" to Value.Str(code),
            "severity" to Value.Str(severity.label),
            "path" to Value.Str(path),
        )
        if (source != null) {
            val s = linkedMapOf<String, Value>()
            if (source.file != null) s["file"] = Value.Str(source.file)
            s["line"] = Value.num(source.line)
            s["column"] = Value.num(source.column)
            m["source"] = Value.Map(s)
        }
        m["message"] = Value.Str(message)
        if (data != null) m["data"] = data
        return Value.Map(m)
    }

    override fun toString(): String {
        val where = source?.let { " ${it.file ?: ""}:${it.line}:${it.column}" } ?: ""
        return "${severity.label} [$code] $path$where: $message"
    }
}

/** Structural diagnostic codes (spec section 18), plus the parse-level codes this implementation adds. */
object Codes {
    const val SCHEMA = "schema"
    const val EXPR_SYNTAX = "expr_syntax"
    const val PATH_UNRESOLVED = "path_unresolved"
    const val ATTACH_CYCLE = "attach_cycle"
    const val EXTENDS_CYCLE = "extends_cycle"
    const val TOKEN_MISSING = "token_missing"
    const val ATTACH_IGNORED = "attach_ignored"
    const val ENCAPSULATION = "encapsulation"
    const val BLEND_OVERLAP = "blend_overlap"

    /** Not in the spec: the text is not valid YAML. */
    const val YAML_SYNTAX = "yaml_syntax"

    /** Not in the spec: a construct outside the strict YAML subset (anchor, tag, ambiguous plain scalar...). */
    const val YAML_SUBSET = "yaml_subset"

    /** Not in the spec: a known construct whose milestone is not implemented yet. It is ignored. */
    const val UNSUPPORTED = "unsupported"

    /** Not in the spec: an expression failed while evaluating (type mismatch, division by zero). */
    const val EXPR_EVAL = "expr_eval"

    /** Not in the spec: a document uses an asset (a font) that the asset manifest does not describe. */
    const val ASSET_MISSING = "asset_missing"

    /** Not in the spec: templates use each other in a cycle, so expansion would not terminate. */
    const val TEMPLATE_CYCLE = "template_cycle"

    /** Timing references (`after`, `with`) or clips that use one another form a loop. */
    const val TIMING_CYCLE = "timing_cycle"

    /** Graph layouts: a hint, a pin or an option the container's layout kind does not declare. It is ignored. */
    const val HINT_IGNORED = "hint_ignored"

    /** Graph layouts: a declared hint that cannot be honoured. The layout resolves without it. */
    const val HINT_CONFLICT = "hint_conflict"

    /** Graph layouts: an edge that gives a node of a `tree` a second parent. It is drawn and ignored for placement. */
    const val TREE_EXTRA_EDGE = "tree_extra_edge"

    /** Graph layouts: a supplied route doubles back to reach a fixed anchor. */
    const val ROUTE_BACKTRACK = "route_backtrack"
}

class DiagnosticSink(private val sourceMap: SourceMap? = null) {
    private val items = mutableListOf<Diagnostic>()
    val all: List<Diagnostic> get() = items
    val hasErrors: Boolean get() = items.any { it.severity == Severity.ERROR }

    fun add(d: Diagnostic) {
        items += d
    }

    fun report(
        code: String,
        severity: Severity,
        path: String,
        message: String,
        at: DataPath? = null,
        data: Value.Map? = null,
    ) {
        val pos = at?.let { sourceMap?.lookup(it) }
        items += Diagnostic(code, severity, path, message, pos?.let { Diagnostic.Source(sourceMap?.file, it.line, it.column) }, data)
    }

    fun error(code: String, path: String, message: String, at: DataPath? = null, data: Value.Map? = null) =
        report(code, Severity.ERROR, path, message, at, data)

    fun warning(code: String, path: String, message: String, at: DataPath? = null, data: Value.Map? = null) =
        report(code, Severity.WARNING, path, message, at, data)
}

fun List<Diagnostic>.toValue(): Value.List = Value.List(map { it.toValue() })

package diagrams.vaadin

import diagrams.harness.Json
import diagrams.harness.Patch
import diagrams.model.Diagnostic
import diagrams.model.JsonWriter
import diagrams.model.Severity
import diagrams.model.Value
import java.io.Serializable

/** One state container of the document and what it offers to navigate (spec section 17). */
data class DiagramContainer(
    /** The node that owns the states; `scene` for the document's own. */
    val owner: String,
    /** `sequence`, `tree` or `graph`. */
    val kind: String,
    val states: List<String>,
    val current: String?,
    /** For a tree: the parent of each state, null at the top. */
    val parents: Map<String, String?>,
    /** For a graph: the edges out of each state, by name, to the state they lead to. */
    val edges: Map<String, Map<String, String>>,
) : Serializable {
    val isSequence: Boolean get() = kind == "sequence"
    val isTree: Boolean get() = kind == "tree"
    val isGraph: Boolean get() = kind == "graph"

    /** The edges out of the current state of a graph. */
    val currentEdges: Map<String, String> get() = current?.let { edges[it] }.orEmpty()
}

/** A sound or film to play with the frames: from [start] on the scene clock, the media's [inPoint] to [outPoint]. */
data class DiagramMediaClip(
    val kind: String,
    val asset: String,
    val url: String?,
    val start: Double,
    val inPoint: Double,
    val outPoint: Double?,
    val volume: Double,
    val rate: Double,
) : Serializable

/** A harness operation that failed in the browser: an unknown state, an edge the state does not have. */
class DiagramException(val operation: String, message: String) : RuntimeException(message)

/** The JSON that crosses between the browser and the server. */
object DiagramJson {
    @JvmStatic
    fun diagnostics(json: String?): List<Diagnostic> = list(json).mapNotNull { diagnostic(it) }

    @JvmStatic
    fun diagnostic(v: Value): Diagnostic? {
        val m = v as? Value.Map ?: return null
        val source = (m["source"] as? Value.Map)?.let { s ->
            Diagnostic.Source(s.str("file"), s.num("line")?.toInt() ?: 0, s.num("column")?.toInt() ?: 0)
        }
        return Diagnostic(
            code = m.str("code") ?: "",
            severity = m.str("severity")?.let { Severity.parse(it) } ?: Severity.ERROR,
            path = m.str("path") ?: "",
            message = m.str("message") ?: "",
            source = source,
            data = m["data"] as? Value.Map,
        )
    }

    @JvmStatic
    fun containers(json: String?): List<DiagramContainer> = list(json).mapNotNull { v ->
        val m = v as? Value.Map ?: return@mapNotNull null
        DiagramContainer(
            owner = m.str("owner") ?: return@mapNotNull null,
            kind = m.str("kind") ?: "",
            states = (m["states"] as? Value.List)?.items.orEmpty().mapNotNull { (it as? Value.Str)?.value },
            current = m.str("current"),
            parents = (m["parents"] as? Value.Map)?.entries.orEmpty().mapValues { (it.value as? Value.Str)?.value },
            edges = (m["edges"] as? Value.Map)?.entries.orEmpty().mapValues { (_, e) ->
                (e as? Value.Map)?.entries.orEmpty().mapNotNull { (k, to) -> (to as? Value.Str)?.let { k to it.value } }.toMap()
            },
        )
    }

    @JvmStatic
    fun media(json: String?): List<DiagramMediaClip> = list(json).mapNotNull { v ->
        val m = v as? Value.Map ?: return@mapNotNull null
        DiagramMediaClip(
            kind = m.str("kind") ?: "",
            asset = m.str("asset") ?: "",
            url = m.str("url"),
            start = m.num("start") ?: 0.0,
            inPoint = m.num("in") ?: 0.0,
            outPoint = m.num("out"),
            volume = m.num("volume") ?: 1.0,
            rate = m.num("rate") ?: 1.0,
        )
    }

    @JvmStatic
    fun patches(patches: List<Patch>): String = JsonWriter.write(
        Value.List(
            patches.map { p ->
                when (p) {
                    is Patch.Set -> Value.map("op" to Value.Str("set"), "path" to Value.Str(p.path), "value" to p.value)
                    is Patch.Remove -> Value.map("op" to Value.Str("remove"), "path" to Value.Str(p.path))
                    is Patch.Add -> Value.Map(
                        linkedMapOf("op" to Value.Str("add"), "parent" to Value.Str(p.parent), "node" to p.node).apply {
                            p.index?.let { put("index", Value.num(it)) }
                        },
                    )
                }
            },
        ),
    ).trimEnd()

    @JvmStatic
    fun parse(json: String?): Value? = json?.takeIf { it.isNotBlank() }?.let { Json.parse(it) }

    private fun list(json: String?): List<Value> = (parse(json) as? Value.List)?.items.orEmpty()

    private fun Value.Map.str(k: String) = (this[k] as? Value.Str)?.value

    private fun Value.Map.num(k: String) = (this[k] as? Value.Num)?.value
}

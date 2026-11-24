package diagrams.processor

/**
 * What a layout kind declares (graph layouts addendum, section 3): the container options it reads,
 * the hints a child or an edge may carry, whether it accepts pins, whether it supplies connector
 * routes and reserves room for their labels, and what a drag maps to in an editor.
 *
 * The set is closed: kinds are built into the core, so every document resolves the same everywhere.
 */
class LayoutKind(
    val name: String,
    /** Container-level options beyond the ones every container has. */
    val options: Set<String> = emptySet(),
    /** Properties a child may carry. */
    val nodeHints: Set<String> = emptySet(),
    /** Properties a connector between two children may carry. */
    val edgeHints: Set<String> = emptySet(),
    /** Whether a child keeps a position of its own: `at` in a free container, `pinned` in a force one. */
    val pins: Boolean = false,
    /** Whether the kind uses the connectors among its children as edges. */
    val graph: Boolean = false,
    /** Whether it supplies connector paths, used by the `layout` router. */
    val routes: Boolean = false,
    /** Whether it reserves room for connector labels (the connector's `label` anchor). */
    val labels: Boolean = false,
    /** What a drag of a child writes: commands of the editor's catalogue, most specific first. */
    val gestures: List<String> = emptyList(),
)

object Layouts {
    /** Options of the graph kinds. On a container of another kind they are reported as `hint_ignored`. */
    val GRAPH_OPTIONS = setOf("direction", "rank_gap", "ordering", "radial", "level_gap", "radius", "start_angle", "seed", "link_distance")
    val NODE_HINTS = setOf("rank", "pinned")
    val EDGE_HINTS = setOf("constrain")

    val DIRECTIONS = setOf("down", "right", "up", "left")
    val ORDERINGS = setOf("auto", "declared")
    val RANKS = setOf("first", "last")

    val all: List<LayoutKind> = listOf(
        LayoutKind("free", pins = true, gestures = listOf("set at")),
        LayoutKind("row", options = setOf("gap", "align"), gestures = listOf("move")),
        LayoutKind("column", options = setOf("gap", "align"), gestures = listOf("move")),
        LayoutKind("grid", options = setOf("gap", "align", "columns"), gestures = listOf("move")),
        LayoutKind("stack", options = setOf("align")),
        LayoutKind(
            "layered",
            options = setOf("direction", "gap", "rank_gap", "ordering"),
            nodeHints = setOf("rank"),
            edgeHints = setOf("constrain"),
            graph = true,
            routes = true,
            labels = true,
            // within a rank: a move when ordering is declared; across ranks: the rank hint
            gestures = listOf("move", "set rank"),
        ),
        LayoutKind(
            "tree",
            options = setOf("direction", "gap", "level_gap", "radial"),
            graph = true,
            routes = true,
            // among siblings: a move; onto another node: the edge from the parent is retargeted
            gestures = listOf("move", "retarget"),
        ),
        LayoutKind("circular", options = setOf("gap", "radius", "start_angle"), graph = true, gestures = listOf("move")),
        LayoutKind("force", options = setOf("seed", "link_distance"), nodeHints = setOf("pinned"), pins = true, graph = true, gestures = listOf("set pinned")),
    )

    val kinds: Map<String, LayoutKind> = all.associateBy { it.name }

    operator fun get(name: String): LayoutKind? = kinds[name]

    fun isGraph(name: String): Boolean = kinds[name]?.graph == true
}

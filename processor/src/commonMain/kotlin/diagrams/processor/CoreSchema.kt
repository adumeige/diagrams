package diagrams.processor

import diagrams.model.Value

/** Property types of the core schema. Scalar types accept expressions. */
enum class PType(val scalar: Boolean) {
    NUMBER(true),
    STRING(true),
    BOOL(true),
    COLOR(true),
    ENUM(true),
    ID(false),
    POINT(false),
    POINTS(false),
    NUMBERS(false),
    NODES(false),
    LAYERS(false),
    ANY(false),
}

class Prop(
    val name: String,
    val type: PType,
    val default: Value? = null,
    val required: Boolean = false,
    val values: Set<String> = emptySet(),
    /** False for properties of a later milestone: accepted, reported as `unsupported`, ignored. */
    val supported: Boolean = true,
)

class NodeKind(val name: String, val props: List<Prop>) {
    private val byName = props.associateBy { it.name }
    operator fun get(name: String): Prop? = byName[name]
}

/**
 * The core schema of format `diagrams/0.1`, as far as milestones 0 and 1 implement it.
 * Later-milestone properties are listed with `supported = false` so they are recognized, not rejected.
 */
object CoreSchema {
    const val FORMAT = "diagrams/0.1"

    val SHAPES = listOf("rect", "ellipse", "circle", "line", "polyline", "polygon", "path", "text", "connector")
    val LAYOUTS: Set<String> = Layouts.kinds.keys
    val ROUTES = setOf("straight", "orthogonal", "layout")
    val ALIGNS = setOf("start", "center", "end", "stretch")
    val ATTACH_KEYS = setOf("to", "anchor", "gap", "offset", "policy", "side", "along")

    /** Where an attacher sits beside a connector (spec section 6, "Beside a connector"). */
    val SIDES = setOf("left", "right", "above", "below")
    val POLICIES = setOf("follow", "stretch", "block")
    val MARKERS = setOf("none", "arrow", "open_arrow", "triangle", "diamond", "open_diamond", "circle", "open_circle")
    val MEDIA = setOf("image", "audio", "video")

    /** Property names are reserved and cannot be used as IDs (spec section 5). */
    val RESERVED_ROOTS = setOf("scene", "camera", "viewport", "theme", "host")

    /** Lists of nodes a container holds: its children, and its decorations (spec section 6, "Decorations"). */
    val DECORATIONS = listOf("underlay", "overlay")

    val ID_PATTERN = Regex("[a-z][a-z0-9_]*")
    val COLOR_PATTERN = Regex("#([0-9a-fA-F]{8}|[0-9a-fA-F]{6})") // longest first: JS `matches` does not backtrack across alternatives

    private fun num(n: Number) = Value.Num(n.toDouble())
    private fun str(s: String) = Value.Str(s)
    private val ORIGIN = Value.map("x" to num(0), "y" to num(0))

    /** Properties every node has. */
    val commonNames: Set<String> get() = common.map { it.name }.toSet()

    private val common = listOf(
        Prop("id", PType.ID),
        Prop("layer", PType.STRING, str("default")),
        Prop("at", PType.POINT, ORIGIN),
        Prop("rotation", PType.NUMBER, num(0)),
        Prop("scale", PType.NUMBER, num(1)),
        Prop("opacity", PType.NUMBER, num(1)),
        Prop("present", PType.BOOL, Value.Bool(true)),
        Prop("style", PType.ANY),
        Prop("theme", PType.ANY),
        Prop("attach", PType.ANY),
        // hints for the graph layout of the parent: `rank` in a layered container, `pinned` in a force one
        Prop("rank", PType.ANY),
        Prop("pinned", PType.POINT),
        Prop("state", PType.STRING),
        Prop("states", PType.ANY),
        Prop("enter", PType.ANY),
        Prop("exit", PType.ANY),
        Prop("timeline", PType.ANY),
    )

    private val box = listOf(Prop("width", PType.NUMBER, required = true), Prop("height", PType.NUMBER, required = true))
    private fun paint(fill: String?) = listOfNotNull(
        fill?.let { Prop("fill", PType.COLOR, str(it)) },
        Prop("stroke", PType.COLOR, str("#000000")),
        Prop("stroke_width", PType.NUMBER, num(1)),
        // dash and gap lengths along the stroke, repeated from its start: [6, 4]; absent or [] is solid
        Prop("stroke_dash", PType.NUMBERS),
        // the fraction of the stroke drawn, from its start: animated by clips such as motion.draw_stroke
        Prop("draw", PType.NUMBER),
    )

    val container = NodeKind(
        "container",
        common + listOf(
            Prop("layout", PType.ENUM, str("free"), values = LAYOUTS),
            Prop("width", PType.NUMBER),
            Prop("height", PType.NUMBER),
            // the least size the container takes, however small its content (spec section 6)
            Prop("min_width", PType.NUMBER),
            Prop("min_height", PType.NUMBER),
            Prop("layers", PType.LAYERS),
            Prop("gap", PType.NUMBER),
            Prop("padding", PType.ANY),
            Prop("align", PType.ENUM, values = ALIGNS),
            Prop("columns", PType.NUMBER),
            // options of the graph kinds: no default here, each kind has its own (Layouts)
            Prop("direction", PType.ENUM, values = Layouts.DIRECTIONS),
            Prop("rank_gap", PType.NUMBER),
            Prop("ordering", PType.ENUM, values = Layouts.ORDERINGS),
            Prop("radial", PType.BOOL),
            Prop("level_gap", PType.NUMBER),
            Prop("radius", PType.NUMBER),
            Prop("start_angle", PType.NUMBER),
            Prop("seed", PType.NUMBER),
            Prop("link_distance", PType.NUMBER),
            Prop("children", PType.NODES, Value.List(emptyList())),
            Prop("underlay", PType.NODES),
            Prop("overlay", PType.NODES),
        ),
    )

    val shapes: Map<String, NodeKind> = mapOf(
        "rect" to NodeKind("rect", common + box + Prop("corner_radius", PType.NUMBER, num(0)) + paint("#ffffff")),
        "ellipse" to NodeKind("ellipse", common + box + paint("#ffffff")),
        "circle" to NodeKind("circle", common + Prop("radius", PType.NUMBER, required = true) + paint("#ffffff")),
        "line" to NodeKind("line", common + Prop("points", PType.POINTS, required = true) + paint(null)),
        "polyline" to NodeKind("polyline", common + Prop("points", PType.POINTS, required = true) + paint(null)),
        "polygon" to NodeKind("polygon", common + Prop("points", PType.POINTS, required = true) + paint("#ffffff")),
        "path" to NodeKind("path", common + Prop("d", PType.STRING, required = true) + paint("#00000000")),
        "text" to NodeKind(
            "text",
            common + listOf(
                Prop("text", PType.STRING, required = true),
                Prop("font", PType.STRING),
                Prop("font_size", PType.NUMBER, num(16)),
                Prop("fill", PType.COLOR, str("#000000")),
                Prop("text_align", PType.ENUM, str("start"), values = setOf("start", "center", "end")),
                Prop("width", PType.NUMBER),
                Prop("wrap", PType.BOOL, Value.Bool(false)),
            ),
        ),
        "connector" to NodeKind(
            "connector",
            common + listOf(
                Prop("from", PType.STRING, required = true),
                Prop("to", PType.STRING, required = true),
                // `layout` takes the path a graph container supplies, and is straight where none is supplied
                Prop("route", PType.ENUM, str("layout"), values = ROUTES),
                // false: drawn, without influencing the ranks of a layered container
                Prop("constrain", PType.BOOL),
                Prop("marker_start", PType.ENUM, str("none"), values = MARKERS),
                Prop("marker_end", PType.ENUM, str("none"), values = MARKERS),
            ) + paint(null),
        ),
    )

    private val timing = listOf(
        // when the media starts on the scene clock, and which part of the file plays
        Prop("start", PType.NUMBER, num(0)),
        Prop("in", PType.NUMBER, num(0)),
        Prop("out", PType.NUMBER),
        Prop("volume", PType.NUMBER, num(1)),
        Prop("rate", PType.NUMBER, num(1)),
    )
    private val frame = listOf(
        Prop("width", PType.NUMBER),
        Prop("height", PType.NUMBER),
        Prop("fit", PType.ENUM, str("contain"), values = setOf("contain", "cover", "fill")),
    )

    /**
     * Media nodes (spec section 15): `media: image | audio | video` and an `asset` declared in an
     * `assets` map. Images and videos have an intrinsic size from the manifest; audio and video play
     * on the scene clock from `start`, from their `in` point to their `out` point.
     */
    val media: Map<String, NodeKind> = mapOf(
        "image" to NodeKind("image", common + Prop("asset", PType.STRING, required = true) + frame),
        "video" to NodeKind("video", common + Prop("asset", PType.STRING, required = true) + frame + timing),
        "audio" to NodeKind("audio", common + Prop("asset", PType.STRING, required = true) + timing),
    )

    /**
     * A template instance (`use: alias.name`): the common properties, `children` for the default slot
     * and `slots` for named ones. Parameters come from the template's schema. No defaults are filled in
     * for common properties: the body's own values are the template's defaults (spec section 10).
     */
    val instance = NodeKind(
        "instance",
        common.map { Prop(it.name, it.type, null, values = it.values, supported = it.supported) } + listOf(
            Prop("children", PType.NODES),
            Prop("slots", PType.ANY),
        ),
    )

    /** Shapes and node forms that parse but are not implemented yet. */
    val UNSUPPORTED_SHAPES = emptySet<String>()

    val documentKeys = listOf("format", "title", "viewport", "imports", "base_theme", "styles", "assets", "camera", "scene")
    val UNSUPPORTED_DOCUMENT_KEYS = emptySet<String>()

    /** Minimum number of points per shape. */
    val MIN_POINTS = mapOf("line" to 2, "polyline" to 2, "polygon" to 3)
    val MAX_POINTS = mapOf("line" to 2)

    /** Which node form a map is, from its kind key. */
    fun kindOf(node: Value.Map): NodeKind? {
        val shape = node["shape"]
        return when {
            shape is Value.Str -> shapes[shape.value]
            node["media"] != null -> (node["media"] as? Value.Str)?.let { media[it.value] }
            node["use"] != null -> null
            else -> container
        }
    }

    /** Key that names the node's form, written first in canonical order after `id`. */
    val KIND_KEYS = listOf("shape", "use", "media", "layout")
}

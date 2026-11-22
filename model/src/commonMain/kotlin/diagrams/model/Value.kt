package diagrams.model

/**
 * The data model every stage reads and writes (spec section 3, "Serialization").
 * YAML and JSON are two spellings of this tree.
 */
sealed interface Value {
    data class Map(val entries: kotlin.collections.Map<String, Value>) : Value {
        operator fun get(key: String): Value? = entries[key]
        val keys: Set<String> get() = entries.keys
    }

    data class List(val items: kotlin.collections.List<Value>) : Value

    data class Str(val value: String) : Value

    /** All numbers are doubles; writers print whole values without a fraction. */
    data class Num(val value: Double) : Value

    data class Bool(val value: Boolean) : Value

    data object Null : Value

    companion object {
        fun map(vararg pairs: Pair<String, Value>): Map = Map(linkedMapOf(*pairs))
        fun list(vararg items: Value): List = List(items.toList())
        fun str(s: String): Str = Str(s)
        fun num(n: Number): Num = Num(n.toDouble())
        fun bool(b: Boolean): Bool = Bool(b)
    }
}

val Value.asMap: Value.Map? get() = this as? Value.Map
val Value.asList: Value.List? get() = this as? Value.List
val Value.asString: String? get() = (this as? Value.Str)?.value
val Value.asDouble: Double? get() = (this as? Value.Num)?.value
val Value.asBool: Boolean? get() = (this as? Value.Bool)?.value

fun Value.typeName(): String = when (this) {
    is Value.Map -> "map"
    is Value.List -> "list"
    is Value.Str -> "string"
    is Value.Num -> "number"
    is Value.Bool -> "boolean"
    Value.Null -> "null"
}

/**
 * Structural equality with a numeric tolerance, used to compare resolved scenes (spec section 19).
 * Map key order is ignored.
 */
fun Value.approxEquals(other: Value, tolerance: Double): Boolean = when (this) {
    is Value.Num -> other is Value.Num && kotlin.math.abs(value - other.value) <= tolerance
    is Value.Map -> other is Value.Map && entries.keys == other.entries.keys &&
        entries.all { (k, v) -> v.approxEquals(other.entries.getValue(k), tolerance) }
    is Value.List -> other is Value.List && items.size == other.items.size &&
        items.indices.all { items[it].approxEquals(other.items[it], tolerance) }
    else -> this == other
}

/** A location in a data tree: map keys and list indices from the document root. */
data class DataPath(val segments: kotlin.collections.List<Any>) {
    fun key(k: String) = DataPath(segments + k)
    fun index(i: Int) = DataPath(segments + i)
    val parent: DataPath? get() = if (segments.isEmpty()) null else DataPath(segments.dropLast(1))

    /** JSON-Pointer-like spelling: `/scene/children/0/at/x`. */
    override fun toString(): String =
        if (segments.isEmpty()) "/" else segments.joinToString("") { "/" + it.toString().replace("~", "~0").replace("/", "~1") }

    companion object {
        val ROOT = DataPath(emptyList())

        fun parse(s: String): DataPath {
            if (s == "/" || s.isEmpty()) return ROOT
            return DataPath(s.removePrefix("/").split("/").map { seg ->
                val unescaped = seg.replace("~1", "/").replace("~0", "~")
                unescaped.toIntOrNull() ?: unescaped
            })
        }
    }
}

/** A 1-based line and column in an authored file. */
data class SourcePos(val line: Int, val column: Int)

/** Maps data paths to the position of the value in the authored text. */
class SourceMap(val file: String?, val positions: kotlin.collections.Map<DataPath, SourcePos>) {
    /** The position of [path], or of its nearest ancestor that has one. */
    fun lookup(path: DataPath): SourcePos? {
        var p: DataPath? = path
        while (p != null) {
            positions[p]?.let { return it }
            p = p.parent
        }
        return null
    }
}

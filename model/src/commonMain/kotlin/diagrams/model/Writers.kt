package diagrams.model

import kotlin.math.abs
import kotlin.math.round

/**
 * Prints a number the same way on every target: whole values without a fraction,
 * others rounded to 6 decimals with trailing zeros removed.
 */
fun formatNumber(d: Double): String {
    require(!d.isNaN() && !d.isInfinite()) { "cannot print $d" }
    val scaled = round(abs(d) * 1_000_000.0)
    if (scaled == 0.0) return "0"
    val neg = d < 0
    val asLong = scaled.toLong()
    val intPart = asLong / 1_000_000
    val frac = asLong % 1_000_000
    val sb = StringBuilder()
    if (neg) sb.append('-')
    sb.append(intPart)
    if (frac != 0L) {
        sb.append('.')
        sb.append(frac.toString().padStart(6, '0').trimEnd('0'))
    }
    return sb.toString()
}

object YamlWriter {
    private const val FLOW_WIDTH = 80
    private val plainKey = Regex("[A-Za-z_][A-Za-z0-9_.\\-]*")

    fun write(v: Value): String {
        val sb = StringBuilder()
        when {
            v is Value.Map && v.entries.isNotEmpty() -> writeMapBody(v, 0, sb)
            v is Value.List && v.items.isNotEmpty() -> writeListBody(v, 0, sb)
            else -> sb.append(flow(v)).append('\n')
        }
        return sb.toString()
    }

    fun scalar(v: Value): String = when (v) {
        is Value.Str -> string(v.value)
        is Value.Num -> formatNumber(v.value)
        is Value.Bool -> v.value.toString()
        Value.Null -> "null"
        else -> flow(v)
    }

    fun string(s: String): String = if (isSafePlain(s)) s else quote(s)

    fun key(k: String): String = if (plainKey.matches(k) && (k.length == 1 || !Yaml.needsQuoting(k))) k else quote(k)

    fun quote(s: String): String {
        val sb = StringBuilder("\"")
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\t' -> sb.append("\\t")
                '\r' -> sb.append("\\r")
                else -> if (c < ' ') sb.append("\\u").append(c.code.toString(16).padStart(4, '0')) else sb.append(c)
            }
        }
        return sb.append('"').toString()
    }

    private fun isSafePlain(s: String): Boolean {
        if (s.isEmpty() || s != s.trim()) return false
        if (Yaml.needsQuoting(s)) return false
        if (s[0] in "-?:,[]{}#&*!|>'\"%@`=") return false
        return isSafePlainTail(s)
    }

    private fun isSafePlainTail(s: String): Boolean =
        s.none { it in ",[]{}\n\r\t" || it < ' ' } && !s.contains(": ") && !s.contains(" #") && !s.endsWith(":")

    private fun isScalar(v: Value) = v !is Value.Map && v !is Value.List

    /** Flow style for empty collections and short collections of scalars. */
    private fun flowable(v: Value): Boolean = when (v) {
        is Value.Map -> v.entries.isEmpty() || (v.entries.values.all { flowable(it) } && flow(v).length <= FLOW_WIDTH)
        is Value.List -> v.items.isEmpty() || (v.items.all { flowable(it) } && flow(v).length <= FLOW_WIDTH)
        else -> true
    }

    fun flow(v: Value): String = when (v) {
        is Value.Map -> v.entries.entries.joinToString(", ", "{", "}") { (k, x) -> key(k) + ": " + flow(x) }
        is Value.List -> v.items.joinToString(", ", "[", "]") { flow(it) }
        else -> scalar(v)
    }

    private fun indent(n: Int, sb: StringBuilder) {
        repeat(n) { sb.append(' ') }
    }

    private fun writeMapBody(m: Value.Map, ind: Int, sb: StringBuilder, firstInline: Boolean = false) {
        var first = true
        for ((k, v) in m.entries) {
            if (!(first && firstInline)) indent(ind, sb)
            first = false
            sb.append(key(k)).append(':')
            writeValueAfterKey(v, ind, sb)
        }
    }

    private fun writeValueAfterKey(v: Value, ind: Int, sb: StringBuilder) {
        if (flowable(v)) {
            sb.append(' ').append(flow(v)).append('\n')
        } else {
            sb.append('\n')
            when (v) {
                is Value.Map -> writeMapBody(v, ind + 2, sb)
                is Value.List -> writeListBody(v, ind + 2, sb)
                else -> error("unreachable")
            }
        }
    }

    private fun writeListBody(l: Value.List, ind: Int, sb: StringBuilder) {
        for (item in l.items) {
            indent(ind, sb)
            sb.append('-')
            when {
                flowable(item) -> sb.append(' ').append(flow(item)).append('\n')
                item is Value.Map -> {
                    sb.append(' ')
                    writeMapBody(item, ind + 2, sb, firstInline = true)
                }
                item is Value.List -> {
                    sb.append('\n')
                    writeListBody(item, ind + 2, sb)
                }
            }
        }
    }
}

object JsonWriter {
    private const val INLINE_WIDTH = 100

    fun write(v: Value): String {
        val sb = StringBuilder()
        write(v, 0, sb)
        sb.append('\n')
        return sb.toString()
    }

    fun compact(v: Value): String = when (v) {
        is Value.Map -> v.entries.entries.joinToString(", ", "{", "}") { (k, x) -> string(k) + ": " + compact(x) }
        is Value.List -> v.items.joinToString(", ", "[", "]") { compact(it) }
        is Value.Str -> string(v.value)
        is Value.Num -> formatNumber(v.value)
        is Value.Bool -> v.value.toString()
        Value.Null -> "null"
    }

    fun string(s: String): String {
        val sb = StringBuilder("\"")
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\t' -> sb.append("\\t")
                '\r' -> sb.append("\\r")
                else -> if (c < ' ') sb.append("\\u").append(c.code.toString(16).padStart(4, '0')) else sb.append(c)
            }
        }
        return sb.append('"').toString()
    }

    private fun write(v: Value, ind: Int, sb: StringBuilder) {
        val inline = compact(v)
        if ((v !is Value.Map && v !is Value.List) || inline.length + ind <= INLINE_WIDTH && !containsCollectionOfCollections(v)) {
            sb.append(inline)
            return
        }
        when (v) {
            is Value.Map -> {
                sb.append("{\n")
                v.entries.entries.forEachIndexed { i, (k, x) ->
                    repeat(ind + 2) { sb.append(' ') }
                    sb.append(string(k)).append(": ")
                    write(x, ind + 2, sb)
                    if (i < v.entries.size - 1) sb.append(',')
                    sb.append('\n')
                }
                repeat(ind) { sb.append(' ') }
                sb.append('}')
            }
            is Value.List -> {
                sb.append("[\n")
                v.items.forEachIndexed { i, x ->
                    repeat(ind + 2) { sb.append(' ') }
                    write(x, ind + 2, sb)
                    if (i < v.items.size - 1) sb.append(',')
                    sb.append('\n')
                }
                repeat(ind) { sb.append(' ') }
                sb.append(']')
            }
            else -> error("unreachable")
        }
    }

    private fun containsCollectionOfCollections(v: Value): Boolean = when (v) {
        is Value.Map -> v.entries.values.any { it is Value.List && it.items.any { x -> x is Value.Map } || it is Value.Map && it.entries.isNotEmpty() && it.entries.values.any { x -> x is Value.Map || x is Value.List } }
        is Value.List -> v.items.any { it is Value.Map && it.entries.values.any { x -> x is Value.Map || x is Value.List } }
        else -> false
    }
}

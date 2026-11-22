package diagrams.assets

/**
 * Text measurement lives in common code behind one interface; the resolver never asks the platform
 * (spec section 16). Stage 1 uses simple metrics: advance widths and line height, no kerning and no shaping.
 */
interface TextMeasurer {
    /** Advance width of one code point at [fontSize]. */
    fun charAdvance(codePoint: Int, fontSize: Double): Double

    /** Advance width of a single line at [fontSize]: the sum of its code points' advances. */
    fun advance(text: String, fontSize: Double): Double {
        var w = 0.0
        forEachCodePoint(text) { w += charAdvance(it, fontSize) }
        return w
    }

    fun ascent(fontSize: Double): Double
    fun descent(fontSize: Double): Double
    fun lineHeight(fontSize: Double): Double
}

/** Calls [f] with each Unicode code point of [s], joining surrogate pairs. */
inline fun forEachCodePoint(s: String, f: (Int) -> Unit) {
    var i = 0
    while (i < s.length) {
        val c = s[i]
        if (c.isHighSurrogate() && i + 1 < s.length && s[i + 1].isLowSurrogate()) {
            f(((c.code - 0xD800) shl 10) + (s[i + 1].code - 0xDC00) + 0x10000)
            i += 2
        } else {
            f(c.code)
            i++
        }
    }
}

/**
 * Built-in metrics for text that names no font asset: Helvetica advance widths in 1/1000 em,
 * ascender 718, descender 207, line height 1.2 em. Renderers draw such text with a generic
 * sans-serif font, so it is only approximately where the resolver measured it.
 */
object FallbackMetrics : TextMeasurer {
    private const val DEFAULT_ADVANCE = 556
    private val widths: IntArray = IntArray(128) { DEFAULT_ADVANCE }.also { w ->
        fun set(chars: String, v: Int) = chars.forEach { w[it.code] = v }
        set(" ", 278)
        set("!", 278); set("\"", 355); set("#$", 556); set("%", 889); set("&", 667); set("'", 191)
        set("()", 333); set("*", 389); set("+<=>~", 584); set(",.:;/", 278); set("-", 333)
        set("0123456789", 556); set("?", 556); set("@", 1015); set("[\\]", 278); set("^", 469)
        set("_", 556); set("`", 333); set("{}", 334); set("|", 260)
        set("ABEKPSVXY", 667); set("CDHNRU", 722); set("FTZ", 611); set("GOQ", 778)
        set("I", 278); set("J", 500); set("L", 556); set("M", 833); set("W", 944)
        set("abdeghnopqu", 556); set("ckszvxy", 500); set("fjt", 278); set("ilj", 222)
        set("m", 833); set("r", 333); set("w", 722)
        set("t", 278); set("f", 278)
    }

    override fun charAdvance(codePoint: Int, fontSize: Double): Double =
        (if (codePoint in 0..127) widths[codePoint] else DEFAULT_ADVANCE) * fontSize / 1000.0

    override fun ascent(fontSize: Double) = 0.718 * fontSize
    override fun descent(fontSize: Double) = 0.207 * fontSize
    override fun lineHeight(fontSize: Double) = 1.2 * fontSize
}

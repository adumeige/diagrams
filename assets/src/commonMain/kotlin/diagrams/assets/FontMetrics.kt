package diagrams.assets

/**
 * Simple font metrics, as the asset manifest records them (spec section 15): vertical metrics and
 * one advance width per mapped code point, all in font units. No kerning, no ligatures, no shaping.
 *
 * [descent] is negative, as in the font's `hhea` table. Unmapped code points take [defaultAdvance],
 * the advance of the `.notdef` glyph that renderers draw for them.
 */
class FontMetrics(
    val unitsPerEm: Int,
    val ascent: Int,
    val descent: Int,
    val lineGap: Int,
    val defaultAdvance: Int,
    val advances: Map<Int, Int>,
) : TextMeasurer {
    private fun scale(units: Int, fontSize: Double) = units * fontSize / unitsPerEm

    override fun charAdvance(codePoint: Int, fontSize: Double): Double = scale(advances[codePoint] ?: defaultAdvance, fontSize)
    override fun ascent(fontSize: Double): Double = scale(ascent, fontSize)
    override fun descent(fontSize: Double): Double = scale(-descent, fontSize)
    override fun lineHeight(fontSize: Double): Double = scale(ascent - descent + lineGap, fontSize)

    override fun equals(other: Any?): Boolean =
        other is FontMetrics && unitsPerEm == other.unitsPerEm && ascent == other.ascent && descent == other.descent &&
            lineGap == other.lineGap && defaultAdvance == other.defaultAdvance && advances == other.advances

    override fun hashCode(): Int = unitsPerEm * 31 + ascent * 17 + advances.hashCode()
}

class FontFormatException(message: String) : Exception(message)

/**
 * A pure-Kotlin reader for the few OpenType tables that simple metrics need:
 * `head`, `hhea`, `maxp`, `hmtx` and `cmap` (formats 4 and 12). It runs on every target,
 * so the probe gives the same numbers everywhere.
 */
object OpenType {
    /** Code points above this are not recorded: stage-1 metrics target Latin and nearby scripts. */
    const val MAX_CODE_POINT = 0xFFFF

    fun read(bytes: ByteArray): FontMetrics {
        val r = Reader(bytes)
        val version = r.u32(0)
        if (version != 0x00010000L && version != 0x4F54544FL && version != 0x74727565L) {
            throw FontFormatException(if (version == 0x74746366L) "font collections (.ttc) are not supported" else "not a TrueType or OpenType font")
        }
        val tables = HashMap<String, Int>()
        val numTables = r.u16(4)
        for (i in 0 until numTables) {
            val rec = 12 + 16 * i
            val tag = (0 until 4).map { r.u8(rec + it).toChar() }.joinToString("")
            tables[tag] = r.u32(rec + 8).toInt()
        }
        fun table(tag: String) = tables[tag] ?: throw FontFormatException("the font has no '$tag' table")

        val head = table("head")
        val unitsPerEm = r.u16(head + 18)
        val hhea = table("hhea")
        val ascent = r.i16(hhea + 4)
        val descent = r.i16(hhea + 6)
        val lineGap = r.i16(hhea + 8)
        val numHMetrics = r.u16(hhea + 34)
        val numGlyphs = r.u16(table("maxp") + 4)
        val hmtx = table("hmtx")
        if (numHMetrics == 0) throw FontFormatException("the font has no horizontal metrics")
        fun advanceOf(glyph: Int): Int = r.u16(hmtx + 4 * minOf(glyph, numHMetrics - 1))

        val glyphs = cmap(r, table("cmap"))
        val advances = LinkedHashMap<Int, Int>()
        for ((cp, g) in glyphs.entries.sortedBy { it.key }) {
            if (g in 1 until numGlyphs && cp <= MAX_CODE_POINT && !isControl(cp)) advances[cp] = advanceOf(g)
        }
        return FontMetrics(unitsPerEm, ascent, descent, lineGap, advanceOf(0), advances)
    }

    private fun isControl(cp: Int) = cp < 0x20 || cp in 0x7F..0x9F

    /** Code point to glyph id, from the best Unicode subtable. */
    private fun cmap(r: Reader, cmap: Int): Map<Int, Int> {
        val n = r.u16(cmap + 2)
        var best: Int? = null
        var bestRank = Int.MAX_VALUE
        for (i in 0 until n) {
            val rec = cmap + 4 + 8 * i
            val platform = r.u16(rec)
            val encoding = r.u16(rec + 2)
            val sub = cmap + r.u32(rec + 4).toInt()
            val format = r.u16(sub)
            val rank = when {
                format == 12 && (platform == 3 && encoding == 10 || platform == 0) -> 0
                format == 4 && (platform == 3 && encoding == 1 || platform == 0) -> 1
                else -> continue
            }
            if (rank < bestRank) {
                best = sub
                bestRank = rank
            }
        }
        val sub = best ?: throw FontFormatException("the font has no Unicode character map (cmap format 4 or 12)")
        val out = HashMap<Int, Int>()
        when (r.u16(sub)) {
            4 -> {
                val segX2 = r.u16(sub + 6)
                val ends = sub + 14
                val starts = ends + segX2 + 2
                val deltas = starts + segX2
                val rangeOffsets = deltas + segX2
                for (s in 0 until segX2 / 2) {
                    val start = r.u16(starts + 2 * s)
                    val end = r.u16(ends + 2 * s)
                    val delta = r.u16(deltas + 2 * s)
                    val ro = r.u16(rangeOffsets + 2 * s)
                    if (start == 0xFFFF) continue
                    for (c in start..end) {
                        val g = if (ro == 0) {
                            (c + delta) and 0xFFFF
                        } else {
                            val raw = r.u16(rangeOffsets + 2 * s + ro + 2 * (c - start))
                            if (raw == 0) 0 else (raw + delta) and 0xFFFF
                        }
                        if (g != 0) out[c] = g
                    }
                }
            }
            12 -> {
                val groups = r.u32(sub + 12).toInt()
                for (i in 0 until groups) {
                    val g = sub + 16 + 12 * i
                    val start = r.u32(g).toInt()
                    val end = minOf(r.u32(g + 4).toInt(), MAX_CODE_POINT)
                    val glyph = r.u32(g + 8).toInt()
                    for (c in start..end) out[c] = glyph + (c - start)
                }
            }
        }
        return out
    }

    private class Reader(val b: ByteArray) {
        private fun check(at: Int, n: Int) {
            if (at < 0 || at + n > b.size) throw FontFormatException("truncated font data at offset $at")
        }

        fun u8(at: Int): Int {
            check(at, 1)
            return b[at].toInt() and 0xFF
        }

        fun u16(at: Int): Int {
            check(at, 2)
            return ((b[at].toInt() and 0xFF) shl 8) or (b[at + 1].toInt() and 0xFF)
        }

        fun i16(at: Int): Int = u16(at).toShort().toInt()

        fun u32(at: Int): Long {
            check(at, 4)
            return (u16(at).toLong() shl 16) or u16(at + 2).toLong()
        }
    }
}

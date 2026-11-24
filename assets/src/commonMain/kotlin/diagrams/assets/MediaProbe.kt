package diagrams.assets

class MediaFormatException(message: String) : Exception(message)

/**
 * Reads the facts of media files from their headers (spec section 15, "Probe"): the size of PNG,
 * JPEG, GIF and SVG images; the duration and cue points of WAV audio (`cue ` chunk, named by `labl`
 * entries of a `LIST adtl` chunk); the size and duration of MP4 and MOV video. Nothing is decoded.
 * Other formats need their facts written by hand or by a harness (see [MediaFacts]).
 */
object MediaProbe {
    fun read(kind: String, name: String, b: ByteArray): MediaFacts = when (kind) {
        "image" -> image(name, b)
        "audio" -> if (isWav(b)) wav(b) else MediaFacts()
        "video" -> if (isMp4(b)) mp4(b) else MediaFacts()
        else -> MediaFacts()
    }

    /** Every read is checked: a truncated or malformed file is a [MediaFormatException], never a crash. */
    private fun u8(b: ByteArray, i: Int): Int {
        if (i < 0 || i >= b.size) throw MediaFormatException("the file is truncated or malformed (read at byte $i of ${b.size})")
        return b[i].toInt() and 0xff
    }
    private fun be16(b: ByteArray, i: Int) = (u8(b, i) shl 8) or u8(b, i + 1)
    private fun be32(b: ByteArray, i: Int) = (u8(b, i).toLong() shl 24) or (u8(b, i + 1).toLong() shl 16) or (u8(b, i + 2).toLong() shl 8) or u8(b, i + 3).toLong()
    private fun le16(b: ByteArray, i: Int) = u8(b, i) or (u8(b, i + 1) shl 8)
    private fun le32(b: ByteArray, i: Int) = u8(b, i).toLong() or (u8(b, i + 1).toLong() shl 8) or (u8(b, i + 2).toLong() shl 16) or (u8(b, i + 3).toLong() shl 24)
    private fun tag(b: ByteArray, i: Int) = if (i + 4 <= b.size) b.copyOfRange(i, i + 4).decodeToString() else ""

    private fun image(name: String, b: ByteArray): MediaFacts {
        if (b.size >= 24 && u8(b, 0) == 0x89 && tag(b, 1).startsWith("PNG")) return MediaFacts(be32(b, 16).toDouble(), be32(b, 20).toDouble())
        if (b.size >= 10 && tag(b, 0).startsWith("GIF")) return MediaFacts(le16(b, 6).toDouble(), le16(b, 8).toDouble())
        if (b.size >= 4 && u8(b, 0) == 0xff && u8(b, 1) == 0xd8) return jpeg(b)
        val text = b.decodeToString(0, minOf(b.size, 4096))
        if ("<svg" in text) return svg(text)
        throw MediaFormatException("'$name' is not a PNG, JPEG, GIF or SVG image")
    }

    private fun jpeg(b: ByteArray): MediaFacts {
        var i = 2
        while (i + 9 < b.size) {
            if (u8(b, i) != 0xff) {
                i++
                continue
            }
            val marker = u8(b, i + 1)
            val len = be16(b, i + 2)
            // start-of-frame markers carry the size; DHT, JPG and DAC share the range and do not
            if (marker in 0xc0..0xcf && marker != 0xc4 && marker != 0xc8 && marker != 0xcc) {
                return MediaFacts(be16(b, i + 7).toDouble(), be16(b, i + 5).toDouble())
            }
            i += 2 + len
        }
        throw MediaFormatException("no frame header in the JPEG data")
    }

    private fun svg(text: String): MediaFacts {
        val open = Regex("<svg[^>]*>").find(text)?.value ?: throw MediaFormatException("no <svg> element")
        fun attr(n: String) = Regex("\\s$n\\s*=\\s*[\"']([0-9.]+)").find(open)?.groupValues?.get(1)?.toDoubleOrNull()
        val vb = Regex("viewBox\\s*=\\s*[\"']([^\"']+)").find(open)?.groupValues?.get(1)?.trim()?.split(Regex("[\\s,]+"))?.mapNotNull { it.toDoubleOrNull() }
        return MediaFacts(attr("width") ?: vb?.getOrNull(2), attr("height") ?: vb?.getOrNull(3))
    }

    private fun isWav(b: ByteArray) = b.size >= 12 && tag(b, 0) == "RIFF" && tag(b, 8) == "WAVE"

    private fun wav(b: ByteArray): MediaFacts {
        var rate = 0L
        var bytesPerSecond = 0L
        var dataSize = 0L
        val cuePoints = LinkedHashMap<Long, Long>() // cue id to sample offset
        val labels = LinkedHashMap<Long, String>()
        var i = 12L
        while (i + 8 <= b.size) {
            val id = tag(b, i.toInt())
            // sizes are unsigned 32-bit: kept as Long so that a huge one ends the walk and never wraps
            val size = le32(b, i.toInt() + 4)
            val body = i.toInt() + 8
            when (id) {
                "fmt " -> {
                    rate = le32(b, body + 4)
                    bytesPerSecond = le32(b, body + 8)
                }
                // a streamed file may declare more data than it holds
                "data" -> dataSize = minOf(size, (b.size - body).toLong())
                "cue " -> {
                    val n = minOf(le32(b, body), (b.size / 24).toLong()).toInt()
                    for (k in 0 until n) {
                        val e = body + 4 + k * 24
                        if (e + 24 > b.size) break
                        cuePoints[le32(b, e)] = le32(b, e + 20)
                    }
                }
                "LIST" -> if (tag(b, body) == "adtl") {
                    var j = body + 4L
                    val end = minOf(b.size.toLong(), body + size)
                    while (j + 8 <= end) {
                        val at = j.toInt()
                        val sub = tag(b, at)
                        val sz = le32(b, at + 4)
                        if ((sub == "labl" || sub == "note") && sz >= 4) {
                            val cueId = le32(b, at + 8)
                            val textEnd = minOf(j + 8 + sz, end).toInt()
                            val text = if (textEnd > at + 12) b.copyOfRange(at + 12, textEnd).decodeToString().trimEnd('\u0000') else ""
                            if (sub == "labl" || cueId !in labels) labels[cueId] = text
                        }
                        j += 8 + sz + (sz and 1)
                    }
                }
            }
            i = body + size + (size and 1)
        }
        if (bytesPerSecond == 0L || rate == 0L) throw MediaFormatException("no 'fmt ' chunk in the WAV data")
        val cues = LinkedHashMap<String, Double>()
        for ((id, sample) in cuePoints) cues[labels[id] ?: "cue_$id"] = sample.toDouble() / rate
        return MediaFacts(duration = dataSize.toDouble() / bytesPerSecond, cues = cues)
    }

    private fun isMp4(b: ByteArray) = b.size >= 12 && tag(b, 4) in setOf("ftyp", "moov", "mdat", "wide", "free")

    private fun mp4(b: ByteArray): MediaFacts {
        var duration: Double? = null
        var width: Double? = null
        var height: Double? = null
        fun boxes(from: Int, to: Int) {
            var i = from
            while (i + 8 <= to) {
                var size = be32(b, i)
                val type = tag(b, i + 4)
                var header = 8
                if (size == 1L && i + 16 <= to) {
                    size = (be32(b, i + 8) shl 32) or be32(b, i + 12)
                    header = 16
                }
                if (size == 0L) size = (to - i).toLong()
                if (size < header) return
                val end = minOf(to.toLong(), i + size).toInt()
                when (type) {
                    "moov", "trak" -> boxes(i + header, end)
                    "mvhd" -> {
                        val v = u8(b, i + header)
                        duration = if (v == 1) {
                            val scale = be32(b, i + header + 20)
                            val d = (be32(b, i + header + 24) shl 32) or be32(b, i + header + 28)
                            if (scale > 0) d.toDouble() / scale else null
                        } else {
                            val scale = be32(b, i + header + 12)
                            if (scale > 0) be32(b, i + header + 16).toDouble() / scale else null
                        }
                    }
                    "tkhd" -> if (end - 8 >= i + header) {
                        // width and height are 16.16 fixed point at the end of the box; audio tracks have 0
                        val w = be32(b, end - 8) / 65536.0
                        val h = be32(b, end - 4) / 65536.0
                        if (w > 0 && h > 0 && width == null) {
                            width = w
                            height = h
                        }
                    }
                }
                i = end
            }
        }
        boxes(0, b.size)
        return MediaFacts(width, height, duration)
    }
}

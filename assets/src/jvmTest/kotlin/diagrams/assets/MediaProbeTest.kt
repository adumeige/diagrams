package diagrams.assets

import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** Malformed and truncated media end in a [MediaFormatException] or a result, never a hang or a crash. */
class MediaProbeTest {
    private fun le32(v: Long) = ByteArray(4) { (v shr (8 * it)).toByte() }
    private fun be32(v: Long) = ByteArray(4) { (v shr (8 * (3 - it))).toByte() }
    private fun bytes(vararg parts: ByteArray) = ByteArrayOutputStream().also { o -> parts.forEach(o::write) }.toByteArray()
    private fun chunk(id: String, body: ByteArray, size: Long = body.size.toLong()) = bytes(id.encodeToByteArray(), le32(size), body)

    private val fmt = chunk("fmt ", bytes(le32(0x00010001), le32(8000), le32(8000), le32(0x00080001)))
    private fun wav(vararg chunks: ByteArray) = bytes("RIFF".encodeToByteArray(), le32(0), "WAVE".encodeToByteArray(), *chunks)

    @Test
    fun readsDurationAndNamedCues() {
        val cue = chunk("cue ", bytes(le32(1), le32(7), le32(0), "data".encodeToByteArray(), le32(0), le32(0), le32(4000)))
        val list = chunk("LIST", bytes("adtl".encodeToByteArray(), chunk("labl", bytes(le32(7), "drop\u0000\u0000".encodeToByteArray()))))
        val facts = MediaProbe.read("audio", "a.wav", wav(fmt, chunk("data", ByteArray(8000)), cue, list))
        assertEquals(1.0, facts.duration)
        assertEquals(mapOf("drop" to 0.5), facts.cues)
    }

    @Test
    fun aSubChunkWithAHugeSizeEndsTheWalk() {
        val list = chunk("LIST", bytes("adtl".encodeToByteArray(), "labl".encodeToByteArray(), le32(0xFFFFFFF8), ByteArray(16)))
        assertEquals(1.0, MediaProbe.read("audio", "a.wav", wav(fmt, chunk("data", ByteArray(8000)), list)).duration)
    }

    @Test
    fun aLabelTooShortForItsCueIdIsSkipped() {
        val list = chunk("LIST", bytes("adtl".encodeToByteArray(), chunk("labl", ByteArray(2)), ByteArray(8)))
        assertEquals(emptyMap(), MediaProbe.read("audio", "a.wav", wav(fmt, list)).cues)
    }

    @Test
    fun aStreamedDataSizeIsCappedToTheFile() {
        val facts = MediaProbe.read("audio", "a.wav", wav(fmt, chunk("data", ByteArray(4000), size = 0xFFFFFFFF)))
        assertEquals(0.5, facts.duration)
    }

    @Test
    fun truncatedHeadersAreFormatErrors() {
        assertFailsWith<MediaFormatException> { MediaProbe.read("audio", "a.wav", wav(fmt).copyOf(22)) }
        val mvhd = bytes(be32(108), "mvhd".encodeToByteArray(), ByteArray(6))
        val mp4 = bytes(be32(16), "ftyp".encodeToByteArray(), ByteArray(8), be32(8L + mvhd.size), "moov".encodeToByteArray(), mvhd)
        assertFailsWith<MediaFormatException> { MediaProbe.read("video", "a.mp4", mp4) }
    }
}

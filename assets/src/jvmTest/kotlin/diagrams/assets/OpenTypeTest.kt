package diagrams.assets

import diagrams.model.Yaml
import diagrams.model.YamlWriter
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class OpenTypeTest {
    private val font = File("../fonts/AtkinsonHyperlegible-Regular.ttf").readBytes()

    @Test
    fun readsVerticalMetricsAndAdvances() {
        val m = OpenType.read(font)
        assertEquals(1000, m.unitsPerEm)
        assertEquals(950, m.ascent)
        assertEquals(-290, m.descent)
        assertEquals(280, m.advances[' '.code])
        assertTrue(m.advances.size > 200, "Latin coverage")
        assertTrue('\n'.code !in m.advances, "control characters are not recorded")
        // advances of a line are the sum of its characters'
        assertEquals((m.advances['A'.code]!! + m.advances['b'.code]!!) * 20.0 / 1000, m.advance("Ab", 20.0), 1e-9)
        assertEquals(m.defaultAdvance * 10.0 / 1000, m.charAdvance(0x4E2D, 10.0), 1e-9)
    }

    @Test
    fun manifestRoundTripsThroughYaml() {
        val manifest = AssetManifest(mapOf("body" to AssetEntry("font", "fonts/a.ttf", OpenType.read(font))))
        val text = YamlWriter.write(manifest.toValue())
        val again = AssetManifest.fromValue(Yaml.parse(text).value)
        assertEquals(manifest.font("body"), again.font("body"))
        assertEquals(text, YamlWriter.write(again.toValue()))
    }

    @Test
    fun rejectsWhatIsNotAFont() {
        assertFailsWith<FontFormatException> { OpenType.read("not a font at all".encodeToByteArray()) }
        assertFailsWith<FontFormatException> { OpenType.read(font.copyOf(200)) }
    }
}

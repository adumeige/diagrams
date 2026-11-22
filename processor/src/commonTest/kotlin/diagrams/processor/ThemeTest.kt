package diagrams.processor

import diagrams.model.Codes
import diagrams.model.Value
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ThemeTest {
    private val files = mapOf(
        "theme.yaml" to """
            format: diagrams/0.1
            tokens: {primary: "#112233", width: 3}
            styles:
              boxy: {fill: "=theme.primary", corner_radius: 4}
        """.trimIndent(),
    )
    private val loader = ImportLoader { path, _ -> files[path]?.let { LoadedFile(it, path) } }

    private fun process(scene: String) = Processor.process(
        "format: diagrams/0.1\nviewport: {width: 100, height: 100}\nimports: {t: theme.yaml}\nbase_theme: t\nscene:\n$scene",
        "doc.yaml",
        loader,
    )

    private fun child(r: ProcessResult, i: Int) =
        (((r.canonical as Value.Map)["scene"] as Value.Map)["children"] as Value.List).items[i] as Value.Map

    @Test
    fun overridesAreScopedToTheirSubtree() {
        val r = process(
            """
            |  children:
            |    - theme: {secondary: "=theme.primary"}
            |      children:
            |        - {shape: rect, width: 1, height: 1, fill: "=theme.secondary"}
            |    - {shape: rect, width: 1, height: 1, fill: "=theme.secondary"}
            """.trimMargin(),
        )
        assertEquals(listOf(Codes.TOKEN_MISSING to "scene.fill"), r.diagnostics.map { it.code to it.path })
    }

    @Test
    fun styledPropertiesGetNoDefault() {
        val r = process("  children:\n    - {shape: rect, width: 1, height: 1, style: t.boxy}\n")
        assertEquals(emptyList(), r.diagnostics)
        val rect = child(r, 0)
        assertNull(rect["fill"], "the style sets fill: the canonical document leaves it to the style")
        assertNull(rect["corner_radius"])
        assertEquals(Value.Str("#000000"), rect["stroke"], "properties the style does not set get their default")
        assertEquals(Value.Str("#112233"), r.environment.rootTokens["primary"])
    }

    @Test
    fun missingImportIsReported() {
        val r = Processor.process("format: diagrams/0.1\nviewport: {width: 1, height: 1}\nimports: {x: nope.yaml}\nscene: {}\n", "doc.yaml", loader)
        assertEquals(listOf(Codes.PATH_UNRESOLVED to "imports.x"), r.diagnostics.map { it.code to it.path })
    }
}

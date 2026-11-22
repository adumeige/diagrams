package diagrams.processor

import diagrams.model.Codes
import diagrams.model.Value
import diagrams.model.Yaml
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ProcessorTest {
    private val doc = """
        format: diagrams/0.1
        viewport: {width: 400, height: 300}
        scene:
          children:
            - shape: rect
              id: card
              width: "=viewport.width / 2"
              height: 100
              fill: "#336699"
            - shape: text
              text: "==not an expression"
    """.trimIndent()

    @Test
    fun canonicalizes() {
        val r = Processor.process(doc, "doc.yaml")
        assertEquals(emptyList(), r.diagnostics)
        val card = (r.canonical as Value.Map)["scene"]!!.let { (it as Value.Map)["children"] as Value.List }.items[0] as Value.Map
        assertEquals(listOf("id", "shape", "layer", "at", "rotation", "scale", "opacity", "present", "width", "height", "corner_radius", "fill", "stroke", "stroke_width"), card.keys.toList())
        assertEquals(Value.map("div" to Value.list(Value.map("ref" to Value.str("viewport.width")), Value.num(2))), card["width"])
    }

    @Test
    fun roundTrips() {
        val r = Processor.process(doc)
        val printed = Printer.print(r.canonical!!)
        assertTrue("width: \"=viewport.width / 2\"" in printed, printed)
        val again = Processor.process(printed)
        assertEquals(r.canonical, again.canonical)
        // canonical is itself a valid input
        val fromCanonical = Processor.processValue(r.canonical!!, Yaml.parse("{}").sourceMap)
        assertEquals(r.canonical, fromCanonical.canonical)
    }

    @Test
    fun reportsWithSourcePositions() {
        val r = Processor.process(
            """
            format: diagrams/0.1
            viewport: {width: 400, height: 300}
            scene:
              children:
                - shape: rect
                  id: card
                  width: "=1 +"
                  height: 10
                  fill: #fff
                  bogus: 1
            """.trimIndent(),
            "bad.yaml",
        )
        val byCode = r.diagnostics.associateBy { it.code to it.path }
        assertEquals(7, byCode[Codes.EXPR_SYNTAX to "card.width"]!!.source!!.line)
        assertEquals(9, byCode[Codes.SCHEMA to "card.fill"]!!.source!!.line)
        assertEquals(10, byCode[Codes.SCHEMA to "card.bogus"]!!.source!!.line)
    }

    @Test
    fun duplicateIdsAcrossUnnamedContainers() {
        val r = Processor.process(
            """
            format: diagrams/0.1
            viewport: {width: 10, height: 10}
            scene:
              children:
                - children: [{shape: circle, id: a, radius: 1}]
                - {shape: circle, id: a, radius: 2}
            """.trimIndent(),
        )
        assertEquals(listOf(Codes.SCHEMA to "a"), r.diagnostics.map { it.code to it.path })
    }
}

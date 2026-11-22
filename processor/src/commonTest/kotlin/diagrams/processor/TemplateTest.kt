package diagrams.processor

import diagrams.model.DiagnosticSink
import diagrams.model.Value
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class TemplateTest {
    private val files = mapOf(
        "lib.yaml" to """
            format: diagrams/0.1
            templates:
              list:
                params:
                  title: {type: string}
                  items: {type: list, keyed: true, item: {type: number}, default: []}
                  boxed: {type: boolean, default: false}
                exports: {parts: [heading]}
                body:
                  layout: column
                  children:
                    - {shape: text, id: heading, text: "=params.title + '!'"}
                    - {if: params.boxed, shape: rect, id: frame, width: 10, height: 10}
                    - for_each: params.items
                      as: it
                      id: row
                      do: {shape: rect, width: "=it.value * 10", height: "=index + 1"}
                    - {slot: default}
        """.trimIndent(),
    )
    private val loader = ImportLoader { path, _ -> files[path]?.let { LoadedFile(it, path) } }

    private fun process(scene: String) = Processor.process(
        "format: diagrams/0.1\nviewport: {width: 100, height: 100}\nimports: {lib: lib.yaml}\nscene:\n$scene",
        "doc.yaml",
        loader,
    )

    @Test
    fun expandsParametersLoopsConditionsAndSlots() {
        val r = process(
            """
              children:
                - use: lib.list
                  id: l
                  title: Hello
                  items: [a: 2, b: 3]
                  children: [{shape: circle, id: dot, radius: 1}]
            """.trimIndent().prependIndent("  "),
        )
        assertTrue(!r.hasErrors, r.diagnostics.joinToString("\n"))
        val index = NodeIndex(Expander(r.environment, DiagnosticSink()).expand(r.canonical!!))
        val from = index.root!!
        val heading = index.resolve("l.heading", from)!!.node
        assertEquals(Value.Str("Hello!"), heading.node["text"])
        assertEquals(null, index.resolve("l.frame", from)?.node?.takeIf { it.id == "frame" })
        val b = index.resolve("l.row[b]", from)!!.node
        assertEquals(Value.Num(30.0), b.node["width"])
        assertEquals(Value.Num(2.0), b.node["height"])
        assertNotNull(index.resolve("l.dot", from)?.node?.takeIf { it.id == "dot" })
        assertEquals(null, index.resolve("l.heading", from)!!.breach)
        assertEquals("l", index.resolve("l.row[a]", from)!!.breach?.path)
    }

    @Test
    fun schemaHasOneDefinitionPerTemplate() {
        val r = process("  children: []")
        val defs = (JsonSchema.document(r.environment) as Value.Map)["\$defs"] as Value.Map
        val list = defs["lib.list"] as Value.Map
        assertEquals(Value.List(listOf(Value.Str("use"), Value.Str("title"))), list["required"])
    }
}

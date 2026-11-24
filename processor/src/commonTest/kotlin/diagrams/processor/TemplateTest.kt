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
              panel:
                exports: {parts: [title]}
                body:
                  layout: column
                  children:
                    - {shape: text, id: title, text: Panel}
                    - id: inner
                      layout: column
                      children:
                        - {slot: default}
              arrow:
                params:
                  label: {type: string, default: ""}
                  head: {type: enum, values: [none, arrow], default: none}
                  width: {type: number, default: 1}
                exports: {parts: [line]}
                body:
                  children:
                    - {shape: text, id: line, text: "=params.label + ' ' + params.head + ' ' + params.width"}
              pointed:
                extends: arrow
                set: {head: arrow}
                params:
                  width: {type: number, default: 3}
              thick:
                extends: pointed
                description: Chains of extension resolve in order.
              table:
                params:
                  rows: {type: list, keyed: true, item: {type: string}, default: []}
                exports: {parts: [row]}
                body:
                  layout: column
                  children:
                    - for_each: params.rows
                      as: r
                      id: row
                      do: {shape: text, text: "=r.value"}
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
    fun exportingAForEachExportsItsItems() {
        val r = process(
            """
              children:
                - use: lib.table
                  id: t
                  rows: [a: first, b: second]
            """.trimIndent().prependIndent("  "),
        )
        assertTrue(!r.hasErrors, r.diagnostics.joinToString("\n"))
        val index = NodeIndex(Expander(r.environment, DiagnosticSink()).expand(r.canonical!!))
        val m = index.resolve("t.row[b]", index.root!!)!!
        assertEquals("t.row[b]", m.node.path)
        assertEquals(null, m.breach, "row is exported, so are its items")
    }

    @Test
    fun whatFillsASlotIsAddressedThroughTheInstance() {
        val r = process(
            """
              children:
                - use: lib.panel
                  id: p
                  children: [{shape: circle, id: dot, radius: 1}]
            """.trimIndent().prependIndent("  "),
        )
        assertTrue(!r.hasErrors, r.diagnostics.joinToString("\n"))
        val index = NodeIndex(Expander(r.environment, DiagnosticSink()).expand(r.canonical!!))
        val m = index.resolve("p.dot", index.root!!)!!
        assertEquals("p.dot", m.node.path, "the slot sits under the named part 'inner', the child is still p's")
        assertEquals(emptyList(), m.rest)
        assertEquals(null, m.breach)
    }

    @Test
    fun aChildCannotTakeTheNameOfAPart() {
        val r = process(
            """
              children:
                - use: lib.panel
                  id: p
                  children: [{shape: circle, id: title, radius: 1}]
            """.trimIndent().prependIndent("  "),
        )
        val d = r.diagnostics.single { it.severity == diagrams.model.Severity.ERROR }
        assertEquals("p.title", d.path)
        assertTrue("already the name of a part" in d.message, d.message)
    }

    @Test
    fun aTemplateExtendsAnother() {
        val r = process(
            """
              children:
                - {use: lib.pointed, id: a, label: go}
                - {use: lib.thick, id: b, label: up, width: 5}
            """.trimIndent().prependIndent("  "),
        )
        assertTrue(!r.hasErrors, r.diagnostics.joinToString("\n"))
        val pointed = r.environment.template("lib.pointed")!!
        assertEquals(listOf("label", "width"), pointed.params.keys.toList(), "head is fixed, so not a parameter")
        assertEquals(listOf("line"), pointed.exports.parts, "exports come from the base")
        val index = NodeIndex(Expander(r.environment, DiagnosticSink()).expand(r.canonical!!))
        assertEquals(Value.Str("go arrow 3"), index.resolve("a.line", index.root!!)!!.node.node["text"])
        assertEquals(Value.Str("up arrow 5"), index.resolve("b.line", index.root!!)!!.node.node["text"])
        val bad = Processor.process(
            "format: diagrams/0.1\nviewport: {width: 10, height: 10}\nimports: {lib: lib.yaml}\nscene: {children: []}",
            "doc.yaml",
        ) { path, _ -> if (path == "lib.yaml") LoadedFile("format: diagrams/0.1\ntemplates:\n  aa: {extends: bb, set: {z: 1}}\n  bb: {extends: aa}", path) else null }
        assertTrue(bad.diagnostics.any { "extends" in it.path }, bad.diagnostics.joinToString("\n"))
    }

    @Test
    fun schemaHasOneDefinitionPerTemplate() {
        val r = process("  children: []")
        val defs = (JsonSchema.document(r.environment) as Value.Map)["\$defs"] as Value.Map
        val list = defs["lib.list"] as Value.Map
        assertEquals(Value.List(listOf(Value.Str("use"), Value.Str("title"))), list["required"])
    }
}

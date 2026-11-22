package diagrams.harness

import diagrams.model.Value
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class HarnessTest {
    private val doc = """
        format: diagrams/0.1
        viewport: {width: 400, height: 200}
        imports: {lib: lib/shapes.yaml}
        scene:
          states:
            defaults: {duration: 1, ease: linear}
            sequence:
              - id: one
              - {id: two, set: {a.at.x: 200}}
              - {id: three, set: {a.at.x: 300}}
          children:
            - {shape: rect, id: a, at: {x: 20, y: 20}, width: 50, height: 40, fill: "#ff0000"}
            - {use: lib.tag, id: t, at: {x: 20, y: 120}, label: hello}
            - {shape: connector, id: c, from: a, to: t, stroke_width: 4}
    """.trimIndent()

    private val lib = """
        format: diagrams/0.1
        templates:
          tag:
            params: {label: string}
            body:
              layout: stack
              children:
                - {shape: rect, width: 80, height: 30, fill: "#eeeeee"}
                - {shape: text, id: text, text: "=params.label"}
    """.trimIndent()

    private fun harness() = Harness(Sources("doc.yaml", mapOf("doc.yaml" to doc, "lib/shapes.yaml" to lib)))

    @Test
    fun readsResolvedValues() {
        val h = harness()
        assertEquals(Value.list(Value.Num(20.0), Value.Num(20.0), Value.Num(50.0), Value.Num(40.0)), h.read("a.box"))
        assertEquals(Value.Num(70.0), h.read("a.right"))
        assertEquals(Value.list(Value.Num(45.0), Value.Num(20.0)), h.read("a.north"))
        assertEquals(Value.Str("one"), h.read("scene.state"))
        assertEquals(Value.Str("#ff0000"), h.read("a.fill"))
        // reads never warn, even past an interface
        assertEquals(Value.Str("hello"), h.read("t.text.text"))
    }

    @Test
    fun hitTestsToTheNearestNamedNode() {
        val h = harness()
        assertEquals("a", h.hit(30.0, 30.0))
        assertEquals("t.text", h.hit(22.0, 125.0))
        assertEquals("t", h.hit(95.0, 145.0))
        assertEquals("scene", h.hit(390.0, 190.0))
        var clicked: String? = null
        h.onPointer { _, p -> clicked = p }
        h.pointer("click", 30.0, 30.0)
        assertEquals("a", clicked)
    }

    @Test
    fun patchesTheCanonicalDocument() {
        val h = harness()
        h.patch(listOf(Patch.Set("a.fill", Value.Str("#00ff00")), Patch.Set("t.label", Value.Str("bye"))))
        assertEquals(Value.Str("#00ff00"), h.read("a.fill"))
        assertEquals(Value.Str("bye"), h.read("t.text.text"))
        h.patch(listOf(Patch.Add("scene", Value.map("shape" to Value.Str("circle"), "id" to Value.Str("dot"), "radius" to Value.Num(5.0), "at" to Value.map("x" to Value.Num(300.0), "y" to Value.Num(100.0))))))
        assertEquals(Value.Num(310.0), h.read("dot.right"))
        h.patch(listOf(Patch.Remove("dot")))
        assertEquals(null, h.read("dot.right"))
        assertFailsWith<HarnessException> { h.patch(listOf(Patch.Set("nothing.fill", Value.Str("#000000")))) }
        // an expression in authored form is accepted
        h.patch(listOf(Patch.Set("a.at.y", Value.Str("=10 + 15"))))
        assertEquals(Value.Num(25.0), h.read("a.y"))
    }

    @Test
    fun drivesStatesWithTransitionsAndInterruptions() {
        val h = harness()
        val seen = mutableListOf<Value?>()
        h.observe("a.x") { seen += it }
        h.next()
        assertTrue(h.transitioning)
        assertEquals(Value.Num(20.0), h.read("a.x"))
        h.tick(0.5)
        assertEquals(Value.Num(110.0), h.read("a.x"))
        // the connector is routed again on every frame
        val route = h.read("c.box")
        assertNotNull(route)
        // interrupted halfway: the in-flight position is the source of the next transition
        h.next()
        h.tick(0.5)
        assertEquals(Value.Num(205.0), h.read("a.x"))
        h.tick(1.0)
        assertEquals(Value.Num(300.0), h.read("a.x"))
        assertEquals(Value.Str("three"), h.read("scene.state"))
        assertTrue(seen.size >= 4)
        assertNotEquals(seen.first(), seen.last())
        h.goto("scene", "one")
        h.tick(2.0)
        assertEquals(Value.Num(20.0), h.read("a.x"))
        assertFailsWith<HarnessException> { h.enter() }
    }

    @Test
    fun navigatesTreesAndGraphs() {
        val d = """
            format: diagrams/0.1
            viewport: {width: 100, height: 100}
            scene:
              children:
                - id: menu
                  states:
                    tree:
                      - id: root
                        children:
                          - {id: files, children: [{id: recent}]}
                          - {id: edit}
                  children: [{shape: rect, id: r, width: 10, height: 10}]
                - id: door
                  states:
                    graph:
                      - {id: shut, edges: {open: ajar}}
                      - {id: ajar, edges: {push: wide, close: shut}}
                      - {id: wide}
                  children: [{shape: rect, id: leaf, width: 10, height: 10}]
        """.trimIndent()
        val h = Harness(Sources("d.yaml", mapOf("d.yaml" to d)))
        fun settle() = h.tick(10.0)
        h.enter("menu")
        settle()
        assertEquals(Value.Str("files"), h.read("menu.state"))
        h.sibling("menu", 1)
        settle()
        assertEquals(Value.Str("edit"), h.read("menu.state"))
        h.up("menu")
        settle()
        assertEquals(Value.Str("root"), h.read("menu.state"))
        h.enter("menu", "files")
        h.enter("menu")
        settle()
        assertEquals(Value.Str("recent"), h.read("menu.state"))
        h.follow("door", "open")
        h.follow("door", "push")
        settle()
        assertEquals(Value.Str("wide"), h.read("door.state"))
        assertFailsWith<HarnessException> { h.follow("door", "close") }
        assertFailsWith<HarnessException> { h.next("door") }
        val views = h.containers().associateBy { it.owner }
        assertEquals("recent", views["menu"]?.current)
        assertEquals("files", views["menu"]?.parents?.get("recent"))
    }

    @Test
    fun parsesPatchesAndBundlesAsJson() {
        val ps = patchesFromJson("""[{"op": "set", "path": "a.fill", "value": "#123456"}, {"op": "remove", "path": "t"}]""")
        assertEquals(2, ps.size)
        assertEquals(Value.map("a" to Value.List(listOf(Value.Num(1.0), Value.Str("x\ny")))), Json.parse("""{"a": [1, "x\ny"]}"""))
    }
}

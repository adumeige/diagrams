package diagrams.vaadin

import diagrams.harness.Json
import diagrams.harness.Patch
import diagrams.model.Severity
import diagrams.model.Value
import java.io.File
import java.io.FileNotFoundException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DiagramBundleTest {
    private val root = File(System.getProperty("diagrams.root"))

    @Test
    fun `gathers imports and assets of an example`() {
        val requested = mutableListOf<DiagramAsset>()
        val b = DiagramBundle.load("examples/agent_deck.yaml", DiagramFiles.directory(root)) { requested += it; "asset/${it.name}" }
        assertEquals("examples/agent_deck.yaml", b.main)
        assertTrue(b.files.keys.size > 1, "imports are gathered: ${b.files.keys}")
        assertTrue(b.files.keys.all { !it.startsWith("../") && !it.startsWith("/") }, "keys stay under the root: ${b.files.keys}")
        assertEquals(requested.map { it.name }.toSet(), b.assets.keys)
        assertTrue(b.problems.isEmpty(), "${b.problems}")
        if (requested.isNotEmpty()) {
            assertTrue(b.manifest.isNotBlank())
            assertTrue(requested.all { it.bytes.isNotEmpty() })
        }
        val json = Json.parse(b.toJson()) as Value.Map
        assertEquals(Value.Str("examples/agent_deck.yaml"), json["main"])
    }

    @Test
    fun `a document without imports`() {
        val b = DiagramBundle.of("title: hi\n", "doc.yaml")
        assertEquals(mapOf("doc.yaml" to "title: hi\n"), b.files)
        assertEquals("", b.manifest)
    }

    @Test
    fun `a missing document`() {
        assertFailsWith<FileNotFoundException> { DiagramBundle.load("nope.yaml", DiagramFiles.of(emptyMap())) }
    }

    @Test
    fun `sources never read out of their root`() {
        val dir = DiagramFiles.directory(File(root, "examples"))
        assertNotNull(dir.read("agent_deck.yaml"))
        assertNull(dir.read("../settings.gradle.kts"))
        assertNull(dir.read("../../etc/passwd"))
        val cp = DiagramFiles.classpath("diagrams/vaadin")
        assertNull(cp.read("../../META-INF/MANIFEST.MF"))
        val mem = DiagramFiles.of(mapOf("a/b.yaml" to "x"))
        assertContentEquals("x".encodeToByteArray(), mem.read("a/./c/../b.yaml"))
        assertNull(mem.read("../a/b.yaml"))
    }

    @Test
    fun `an import that climbs out of the root is not read`() {
        val files = DiagramFiles.of(mapOf("doc.yaml" to "imports:\n  x: ../secret.yaml\n", "secret.yaml" to "x: 1\n"))
        val b = DiagramBundle.load("doc.yaml", files)
        assertEquals(setOf("doc.yaml"), b.files.keys)
    }

    @Test
    fun `an overlay replaces the edited file only`() {
        val files = DiagramFiles.of(mapOf("doc.yaml" to "old", "lib.yaml" to "lib")).overlay(mapOf("doc.yaml" to "new"))
        assertEquals("new", files.read("doc.yaml")?.decodeToString())
        assertEquals("lib", files.read("lib.yaml")?.decodeToString())
    }

    @Test
    fun `reads what the browser sends`() {
        val d = DiagramJson.diagnostics("""[{"code":"overlap","severity":"warning","path":"a.b","source":{"file":"doc.yaml","line":3,"column":5},"message":"m"}]""").single()
        assertEquals(Severity.WARNING, d.severity)
        assertEquals("a.b", d.path)
        assertEquals(3, d.source?.line)
        val c = DiagramJson.containers("""[{"owner":"scene","kind":"graph","states":["a","b"],"current":"a","parents":{"a":null},"edges":{"a":{"go":"b"}}}]""").single()
        assertTrue(c.isGraph)
        assertEquals(mapOf("go" to "b"), c.currentEdges)
        assertEquals(mapOf("a" to null), c.parents)
        val m = DiagramJson.media("""[{"kind":"audio","asset":"s","url":null,"start":1,"in":0,"out":null,"volume":1,"rate":1}]""").single()
        assertNull(m.url)
        assertNull(m.outPoint)
        assertEquals(emptyList(), DiagramJson.diagnostics(null))
    }

    @Test
    fun `writes patches as the harness reads them`() {
        val json = DiagramJson.patches(listOf(Patch.Set("a.x", Value.num(3)), Patch.Add("scene", Value.map("id" to Value.Str("n")), 0), Patch.Remove("b")))
        val back = diagrams.harness.patchesFromJson(json)
        assertEquals(3, back.size)
        assertEquals(0, (back[1] as Patch.Add).index)
    }
}

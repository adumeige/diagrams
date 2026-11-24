package diagrams.harness

import diagrams.model.Value
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Graph layouts give the same geometry on every target, to the last bit (docs/graph-layouts.md,
 * section 8). This test runs on the JVM and on JavaScript against the same numbers, which were
 * produced on the JVM: `force` (seeded, pinned and not), `circular` and the radial `tree`, the
 * kinds whose arithmetic could differ between platforms.
 */
class GraphLayoutTest {
    private val doc = """
        format: diagrams/0.1
        viewport: {width: 800, height: 600}
        scene:
          children:
            - id: net
              layout: force
              children:
                - {shape: circle, id: hub, radius: 20}
                - {shape: circle, id: a, radius: 10}
                - {shape: circle, id: b, radius: 10}
                - {shape: rect, id: c, width: 40, height: 20}
                - {shape: circle, id: far, radius: 10}
                - {shape: connector, from: hub, to: a}
                - {shape: connector, from: hub, to: b}
                - {shape: connector, from: hub, to: c}
                - {shape: connector, from: a, to: b}
            - id: held
              at: {x: 400, y: 0}
              layout: force
              seed: 42
              link_distance: 35
              children:
                - {shape: circle, id: post, radius: 10, pinned: {x: 100, y: 100}}
                - {shape: circle, id: mid_node, radius: 10}
                - {shape: circle, id: tail, radius: 10}
                - {shape: connector, from: post, to: mid_node}
                - {shape: connector, from: mid_node, to: tail}
            - id: ring
              at: {x: 0, y: 300}
              layout: circular
              start_angle: 20
              children:
                - {shape: circle, id: r1, radius: 12}
                - {shape: circle, id: r2, radius: 12}
                - {shape: circle, id: r3, radius: 12}
                - {shape: circle, id: r4, radius: 12}
                - {shape: circle, id: r5, radius: 12}
            - id: star
              at: {x: 400, y: 300}
              layout: tree
              radial: true
              children:
                - {shape: circle, id: sun, radius: 12}
                - {shape: circle, id: p1, radius: 8}
                - {shape: circle, id: p2, radius: 8}
                - {shape: circle, id: p3, radius: 8}
                - {shape: circle, id: moon, radius: 5}
                - {shape: connector, from: sun, to: p1}
                - {shape: connector, from: sun, to: p2}
                - {shape: connector, from: sun, to: p3}
                - {shape: connector, from: p2, to: moon}
    """.trimIndent()

    private val expected = mapOf(
        "net.hub" to listOf(74.61666289646767, 73.24386481448774, 40.0, 40.0),
        "net.a" to listOf(128.26233150155974, 0.0, 20.0, 20.0),
        "net.b" to listOf(177.22290265322295, 60.42414439631082, 20.0, 20.0),
        "net.c" to listOf(0.0, 163.01384561300927, 40.0, 20.0),
        "net.far" to listOf(198.1971302052053, 259.6672697748959, 20.0, 20.0),
        "held.post" to listOf(500.0, 100.0, 20.0, 20.0),
        "held.mid_node" to listOf(464.1894162922383, 55.42334386056004, 20.0, 20.0),
        "held.tail" to listOf(428.36868348674756, 10.854842709269434, 20.0, 20.0),
        "ring.r1" to listOf(89.10005917460508, 361.64588806621384, 24.0, 24.0),
        "ring.r2" to listOf(44.33203025663282, 391.8423048536342, 24.0, 24.0),
        "ring.r3" to listOf(1.779449561869832, 358.5965851860486, 24.0, 24.0),
        "ring.r4" to listOf(20.248537301455933, 307.85318366360957, 24.0, 24.0),
        "ring.r5" to listOf(74.2156419604871, 309.7377564855446, 24.0, 24.0),
        "star.sun" to listOf(568.0, 468.0, 24.0, 24.0),
        "star.p1" to listOf(644.7461339178928, 430.0, 16.0, 16.0),
        "star.p2" to listOf(572.0, 556.0, 16.0, 16.0),
        "star.p3" to listOf(499.25386608210715, 430.00000000000006, 16.0, 16.0),
        "star.moon" to listOf(575.0, 643.0, 10.0, 10.0),
    )

    @Test
    fun graphLayoutsAreIdenticalOnEveryTarget() {
        val h = Harness(Sources("doc.yaml", mapOf("doc.yaml" to doc)))
        for ((path, box) in expected) {
            val actual = (h.read("$path.box") as Value.List).items.map { (it as Value.Num).value }
            assertEquals(box, actual, path)
        }
    }

    @Test
    fun twinEdgesBowApartInKindsWithoutRoutes() {
        // two nodes on a horizontal line (start_angle 0): twins bow up and down by half the bow
        val twins = """
            format: diagrams/0.1
            viewport: {width: 800, height: 600}
            scene:
              children:
                - id: ring
                  layout: circular
                  start_angle: 0
                  children:
                    - {shape: circle, id: a, radius: 10}
                    - {shape: circle, id: b, radius: 10}
                    - {shape: connector, id: t1, from: a, to: b}
                    - {shape: connector, id: t2, from: b, to: a}
                    - {shape: connector, id: flat, from: a, to: b, route: straight}
                - id: net
                  at: {x: 0, y: 300}
                  layout: force
                  children:
                    - {shape: circle, id: left_node, radius: 10}
                    - {shape: circle, id: right_node, radius: 10}
                    - {shape: connector, id: u1, from: left_node, to: right_node}
                    - {shape: connector, id: u2, from: left_node, to: right_node}
                    - {shape: connector, id: u3, from: left_node, to: right_node}
        """.trimIndent()
        val h = Harness(Sources("doc.yaml", mapOf("doc.yaml" to twins)))
        fun num(path: String) = (h.read(path) as Value.Num).value
        // the two twins bow to either side of the line between the centers, mirror images of
        // each other; the explicit straight one keeps the line and does not count as a twin
        val axis = num("ring.a.center_y")
        assertTrue(num("ring.t1.height") > 0.0 && num("ring.t2.height") > 0.0)
        assertEquals(num("ring.t1.height"), num("ring.t2.height"), 1e-9)
        assertEquals(2 * axis, num("ring.t1.top") + num("ring.t2.bottom"), 1e-9)
        assertEquals(0.0, num("ring.flat.height"), 1e-9)
        assertEquals(axis, num("ring.flat.top"), 1e-9)
        // three twins in a force container: three different paths, the middle one straight
        val boxes = listOf("u1", "u2", "u3").map { h.read("net.$it.box") }
        assertEquals(3, boxes.toSet().size)
    }

    @Test
    fun aForceLayoutStartsColdOnEveryResolution() {
        val h = Harness(Sources("doc.yaml", mapOf("doc.yaml" to doc)))
        val first = h.read("net.far.box")
        h.reload(Sources("doc.yaml", mapOf("doc.yaml" to doc)))
        assertEquals(first, h.read("net.far.box"))
    }
}

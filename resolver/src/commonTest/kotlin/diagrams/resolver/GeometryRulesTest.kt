package diagrams.resolver

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GeometryRulesTest {
    @Test
    fun twoMarkersShareAShortSegment() {
        // 10 units between the ends, markers 12 long: each takes half, both are drawn
        val r = Markers.apply(listOf(Point(0.0, 0.0), Point(10.0, 0.0)), "arrow", "arrow", 2.0)
        assertEquals(2, r.markers.size)
        assertEquals(listOf(Point(5.0, 0.0), Point(5.0, 0.0)), r.points)
        assertEquals(setOf(Point(0.0, 0.0), Point(10.0, 0.0)), r.markers.map { it.points.first() }.toSet(), "the tips stay on the ends")
    }

    @Test
    fun aLongSegmentIsShortenedByEachMarker() {
        val r = Markers.apply(listOf(Point(0.0, 0.0), Point(100.0, 0.0)), "arrow", "arrow", 2.0)
        assertEquals(listOf(Point(12.0, 0.0), Point(88.0, 0.0)), r.points)
        val one = Markers.apply(listOf(Point(0.0, 0.0), Point(10.0, 0.0)), null, "arrow", 2.0)
        assertEquals(1, one.markers.size)
    }

    @Test
    fun aSelfMessageOnTheLastLifelineStaysInsideTheSequence() {
        val ps = listOf(SequenceLayout.Participant(100.0, 30.0, false, false), SequenceLayout.Participant(100.0, 30.0, false, false))
        val self = SequenceLayout.Message(1, 1, labelW = 80.0, labelH = 14.0, parallel = false)
        val r = SequenceLayout.layout(ps, listOf(self), emptyList(), SequenceLayout.Options())
        val label = r.labels.single()!!
        assertTrue(r.width >= label.x + 40.0, "width ${r.width} holds the label, which ends at ${label.x + 40.0}")
        assertTrue(r.width >= r.routes.single().maxOf { it.x })
        assertTrue(r.participants.all { it.x >= 0.0 })
    }
}

package diagrams.vaadin.karibu

import com.github.mvysny.kaributesting.v10.MockVaadin
import com.github.mvysny.kaributesting.v10._get
import com.github.mvysny.karibudsl.v10.span
import com.github.mvysny.karibudsl.v10.verticalLayout
import com.vaadin.flow.component.ComponentUtil
import com.vaadin.flow.component.UI
import com.vaadin.flow.component.html.Span
import diagrams.vaadin.DiagramBundle
import diagrams.vaadin.DiagramClickEvent
import diagrams.vaadin.DiagramLoadEvent
import diagrams.vaadin.DiagramPlaybackEvent
import diagrams.vaadin.DiagramStateChangeEvent
import diagrams.vaadin.DiagramViewer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class DiagramViewerDslTest {
    @BeforeTest
    fun setUp() = MockVaadin.setup()

    @AfterTest
    fun tearDown() = MockVaadin.tearDown()

    private val containers = """[{"owner":"scene","kind":"sequence","states":["a","b"],"current":"b","parents":{},"edges":{}}]"""

    @Test
    fun `the builder adds a configured viewer`() {
        val bundle = DiagramBundle.of("title: x\n")
        UI.getCurrent().verticalLayout {
            diagramViewer(bundle) {
                isKeyboardNavigation = true
                timeUpdateInterval = 250
            }
        }
        val viewer = UI.getCurrent()._get<DiagramViewer>()
        assertSame(bundle, viewer.bundle)
        assertTrue(viewer.isKeyboardNavigation)
        assertEquals(250, viewer.timeUpdateInterval)
        assertTrue(viewer.element.getProperty("load").contains("\"keep\": false"), viewer.element.getProperty("load"))
    }

    @Test
    fun `events feed the state flow and the mirrored state`() {
        lateinit var viewer: DiagramViewer
        UI.getCurrent().verticalLayout { viewer = diagramViewer("title: x\n") }
        val state = viewer.state
        ComponentUtil.fireEvent(viewer, DiagramLoadEvent(viewer, true, true, null, "x", true, "[]", containers, 4.0))
        assertEquals("x", state.value.title)
        assertEquals("b", state.value.container()?.current)
        assertEquals(4.0, viewer.duration)
        ComponentUtil.fireEvent(viewer, DiagramPlaybackEvent(viewer, true, true, 1.5, 4.0))
        assertTrue(state.value.isPlaying)
        assertEquals(1.5, viewer.time)
        ComponentUtil.fireEvent(viewer, DiagramClickEvent(viewer, true, "scene.box1", 3.0, 4.0))
        assertEquals("scene.box1", state.value.selected)
        assertEquals("scene.box1", viewer.selected)
        ComponentUtil.fireEvent(viewer, DiagramStateChangeEvent(viewer, true, containers.replace("\"current\":\"b\"", "\"current\":\"a\"")))
        assertEquals("a", viewer.container()?.current)
    }

    @Test
    fun `bindings follow their flows while attached`() {
        val text = MutableStateFlow("one")
        val bundles = MutableStateFlow<DiagramBundle?>(DiagramBundle.of("a: 1\n", "doc.yaml"))
        lateinit var viewer: DiagramViewer
        UI.getCurrent().verticalLayout {
            span { bindText(text) }
            viewer = diagramViewer { bindBundle(bundles) }
        }
        val span = UI.getCurrent()._get<Span>()
        assertEquals("one", span.text)
        text.value = "two"
        assertEquals("two", span.text)
        assertEquals("doc.yaml", viewer.bundle?.main)
        bundles.value = DiagramBundle.of("a: 2\n", "doc.yaml")
        assertTrue(viewer.element.getProperty("load").contains("\"keep\": true"), "same main file: reloaded")
        bundles.value = null
        assertNull(viewer.bundle)
        UI.getCurrent().removeAll()
        text.value = "three"
        assertEquals("two", span.text, "detached: no longer bound")
    }
}

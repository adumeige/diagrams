package diagrams.vaadin.karibu

import com.github.mvysny.karibudsl.v10.VaadinDsl
import com.github.mvysny.karibudsl.v10.init
import com.vaadin.flow.component.HasComponents
import com.vaadin.flow.shared.Registration
import diagrams.vaadin.DiagramBundle
import diagrams.vaadin.DiagramClickEvent
import diagrams.vaadin.DiagramDiagnosticsEvent
import diagrams.vaadin.DiagramErrorEvent
import diagrams.vaadin.DiagramFiles
import diagrams.vaadin.DiagramLoadEvent
import diagrams.vaadin.DiagramPlaybackEvent
import diagrams.vaadin.DiagramStateChangeEvent
import diagrams.vaadin.DiagramTimeEvent
import diagrams.vaadin.DiagramViewer

/**
 * A diagrams viewer:
 *
 * ```kotlin
 * diagramViewer {
 *     setSizeFull()
 *     isKeyboardNavigation = true
 *     load("examples/agent_deck.yaml", DiagramFiles.classpath("diagrams"))
 *     onNodeClick { viewModel.onSelect(it.path) }
 * }
 * ```
 */
@VaadinDsl
fun (@VaadinDsl HasComponents).diagramViewer(
    bundle: DiagramBundle? = null,
    block: (@VaadinDsl DiagramViewer).() -> Unit = {},
): DiagramViewer = init(DiagramViewer().apply { if (bundle != null) this.bundle = bundle }, block)

/** A viewer showing the document [text], which imports nothing. */
@VaadinDsl
fun (@VaadinDsl HasComponents).diagramViewer(
    text: String,
    block: (@VaadinDsl DiagramViewer).() -> Unit = {},
): DiagramViewer = diagramViewer(DiagramBundle.of(text), block)

/** Shows the document [text], which imports nothing; [keepState] keeps the states and the clock. */
fun DiagramViewer.show(text: String, main: String = "document.yaml", keepState: Boolean = false) {
    val b = DiagramBundle.of(text, main)
    if (keepState) reload(b) else bundle = b
}

/** Files in memory, for [DiagramViewer.load]: `diagramFiles { file("doc.yaml", text) }`. */
fun diagramFiles(block: DiagramFilesBuilder.() -> Unit): DiagramFiles = DiagramFilesBuilder().apply(block).build()

@VaadinDsl
class DiagramFilesBuilder internal constructor() {
    private val files = LinkedHashMap<String, String>()

    fun file(path: String, text: String) {
        files[path] = text
    }

    internal fun build(): DiagramFiles = DiagramFiles.of(files)
}

// ---- listeners, karibu-style ----

fun DiagramViewer.onLoad(listener: (DiagramLoadEvent) -> Unit): Registration = addLoadListener { listener(it) }

fun DiagramViewer.onNodeClick(listener: (DiagramClickEvent) -> Unit): Registration = addNodeClickListener { listener(it) }

fun DiagramViewer.onStateChange(listener: (DiagramStateChangeEvent) -> Unit): Registration = addStateChangeListener { listener(it) }

fun DiagramViewer.onDiagnostics(listener: (DiagramDiagnosticsEvent) -> Unit): Registration = addDiagnosticsListener { listener(it) }

fun DiagramViewer.onPlayback(listener: (DiagramPlaybackEvent) -> Unit): Registration = addPlaybackListener { listener(it) }

fun DiagramViewer.onTime(listener: (DiagramTimeEvent) -> Unit): Registration = addTimeListener { listener(it) }

fun DiagramViewer.onError(listener: (DiagramErrorEvent) -> Unit): Registration = addErrorListener { listener(it) }

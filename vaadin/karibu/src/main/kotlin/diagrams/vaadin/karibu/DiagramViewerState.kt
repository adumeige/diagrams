package diagrams.vaadin.karibu

import com.vaadin.flow.component.ComponentUtil
import diagrams.model.Diagnostic
import diagrams.model.Severity
import diagrams.vaadin.DiagramBundle
import diagrams.vaadin.DiagramContainer
import diagrams.vaadin.DiagramFiles
import diagrams.vaadin.DiagramViewer
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** What a viewer shows, as its events report it. */
data class DiagramViewerState(
    val title: String? = null,
    val isLoaded: Boolean = false,
    val isRendered: Boolean = false,
    val diagnostics: List<Diagnostic> = emptyList(),
    val containers: List<DiagramContainer> = emptyList(),
    val time: Double = 0.0,
    val duration: Double = 0.0,
    val isPlaying: Boolean = false,
    val selected: String? = null,
    /** The last operation that failed, and why. */
    val error: String? = null,
) {
    val hasErrors: Boolean get() = diagnostics.any { it.severity == Severity.ERROR }
    val hasTimeline: Boolean get() = duration > 0.0

    fun container(owner: String = "scene"): DiagramContainer? = containers.firstOrNull { it.owner == owner }
}

/**
 * The viewer's state as a [StateFlow], fed by its events: bind views to it rather than listening
 * to each event. The first call starts following the viewer.
 */
val DiagramViewer.state: StateFlow<DiagramViewerState>
    get() = (ComponentUtil.getData(this, StateHolder::class.java) ?: StateHolder(this).also { ComponentUtil.setData(this, StateHolder::class.java, it) }).flow.asStateFlow()

private class StateHolder(viewer: DiagramViewer) {
    val flow = MutableStateFlow(DiagramViewerState())

    init {
        viewer.onLoad { e ->
            flow.value = DiagramViewerState(
                title = e.title,
                isLoaded = e.isOk,
                isRendered = e.isRendered,
                diagnostics = e.diagnostics,
                containers = e.containers,
                duration = e.duration,
                error = e.error,
            )
        }
        viewer.onStateChange { e -> flow.update { it.copy(containers = e.containers) } }
        viewer.onDiagnostics { e -> flow.update { it.copy(diagnostics = e.diagnostics) } }
        viewer.onPlayback { e -> flow.update { it.copy(isPlaying = e.isPlaying, time = e.time, duration = e.duration) } }
        viewer.onTime { e -> flow.update { it.copy(time = e.time) } }
        viewer.onNodeClick { e -> flow.update { it.copy(selected = e.path) } }
        viewer.onError { e -> flow.update { it.copy(error = "${e.operation}: ${e.message}") } }
    }
}

/**
 * Shows each bundle of [flow]. A bundle of the same main file as the one shown is [reloaded]
 * [DiagramViewer.reload] (states and clock kept) when [keepState] is true, as a live edit would.
 */
fun DiagramViewer.bindBundle(flow: Flow<DiagramBundle?>, keepState: Boolean = true): DiagramViewer = bindProperty(flow) { b ->
    when {
        b == null -> bundle = null
        b === bundle -> {}
        keepState && bundle?.main == b.main -> reload(b)
        else -> bundle = b
    }
}

/** The selected node, from [flow]. */
fun DiagramViewer.bindSelected(flow: Flow<String?>): DiagramViewer = bindProperty(flow) { if (selected != it) selected = it }

/** The highlighted nodes, path to CSS colour, from [flow]. */
fun DiagramViewer.bindHighlights(flow: Flow<Map<String, String>>): DiagramViewer = bindProperty(flow) { highlight(it) }

/** A document to load: its main file in [files], which also serve what it imports and its assets. */
data class DiagramDocument(val main: String, val files: DiagramFiles)

/**
 * Loads each document of [flow] with [DiagramViewer.load], so the viewer serves its assets. The
 * same main file as the one shown is reloaded with states and clock kept when [keepState] is true.
 * [onFailure] hears of a document that cannot be read (a missing main file).
 */
fun DiagramViewer.bindDocument(
    flow: Flow<DiagramDocument?>,
    keepState: Boolean = true,
    onFailure: (Exception) -> Unit = { throw it },
): DiagramViewer = bindProperty(flow) { d ->
    if (d == null) {
        bundle = null
    } else {
        try {
            load(d.main, d.files, keepState = keepState && bundle?.main == d.main)
        } catch (e: Exception) {
            onFailure(e)
        }
    }
}

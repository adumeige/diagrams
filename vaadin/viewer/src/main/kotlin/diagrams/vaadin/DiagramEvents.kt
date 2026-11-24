package diagrams.vaadin

import com.vaadin.flow.component.ComponentEvent
import com.vaadin.flow.component.DomEvent
import com.vaadin.flow.component.EventData
import diagrams.model.Diagnostic

/**
 * A bundle was loaded (or reloaded) in the browser. [isOk] is false when the bundle could not be
 * read at all; a document with errors loads, is not [rendered][isRendered], and reports its
 * [diagnostics].
 */
@DomEvent("diagrams-loaded")
class DiagramLoadEvent(
    source: DiagramViewer,
    fromClient: Boolean,
    @EventData("event.detail.ok") val isOk: Boolean,
    @EventData("event.detail.error") val error: String?,
    @EventData("event.detail.title") val title: String?,
    @EventData("event.detail.rendered") val isRendered: Boolean,
    @EventData("event.detail.diagnostics") val diagnosticsJson: String?,
    @EventData("event.detail.containers") val containersJson: String?,
    @EventData("event.detail.duration") val duration: Double,
) : ComponentEvent<DiagramViewer>(source, fromClient) {
    val diagnostics: List<Diagnostic> get() = DiagramJson.diagnostics(diagnosticsJson)
    val containers: List<DiagramContainer> get() = DiagramJson.containers(containersJson)
}

/** A click on the drawing: the [path] of the node under it (`scene` for the background), in viewport units. */
@DomEvent("diagrams-click")
class DiagramClickEvent(
    source: DiagramViewer,
    fromClient: Boolean,
    @EventData("event.detail.path") val path: String?,
    @EventData("event.detail.x") val x: Double,
    @EventData("event.detail.y") val y: Double,
) : ComponentEvent<DiagramViewer>(source, fromClient)

/** A state container changed state: driven, clicked, or at the end of a transition. */
@DomEvent("diagrams-states")
class DiagramStateChangeEvent(
    source: DiagramViewer,
    fromClient: Boolean,
    @EventData("event.detail.containers") val containersJson: String?,
) : ComponentEvent<DiagramViewer>(source, fromClient) {
    val containers: List<DiagramContainer> get() = DiagramJson.containers(containersJson)
}

/** The findings changed: after a load, a patch, a state change, or at a new moment of the timeline. */
@DomEvent("diagrams-diagnostics")
class DiagramDiagnosticsEvent(
    source: DiagramViewer,
    fromClient: Boolean,
    @EventData("event.detail.diagnostics") val diagnosticsJson: String?,
) : ComponentEvent<DiagramViewer>(source, fromClient) {
    val diagnostics: List<Diagnostic> get() = DiagramJson.diagnostics(diagnosticsJson)
}

/** The scene clock started, stopped, was sought, or its duration changed. */
@DomEvent("diagrams-playback")
class DiagramPlaybackEvent(
    source: DiagramViewer,
    fromClient: Boolean,
    @EventData("event.detail.playing") val isPlaying: Boolean,
    @EventData("event.detail.time") val time: Double,
    @EventData("event.detail.duration") val duration: Double,
) : ComponentEvent<DiagramViewer>(source, fromClient)

/** The scene clock while it plays, every [DiagramViewer.timeUpdateInterval] milliseconds. */
@DomEvent("diagrams-time")
class DiagramTimeEvent(
    source: DiagramViewer,
    fromClient: Boolean,
    @EventData("event.detail.time") val time: Double,
    @EventData("event.detail.duration") val duration: Double,
) : ComponentEvent<DiagramViewer>(source, fromClient)

/** A harness operation failed in the browser, whoever asked for it (the server, a key, a click). */
@DomEvent("diagrams-error")
class DiagramErrorEvent(
    source: DiagramViewer,
    fromClient: Boolean,
    @EventData("event.detail.operation") val operation: String?,
    @EventData("event.detail.message") val message: String?,
) : ComponentEvent<DiagramViewer>(source, fromClient)

package diagrams.vaadin

import com.vaadin.flow.component.Component
import com.vaadin.flow.component.ComponentEventListener
import com.vaadin.flow.component.HasSize
import com.vaadin.flow.component.HasStyle
import com.vaadin.flow.component.Tag
import com.vaadin.flow.component.dependency.JsModule
import com.vaadin.flow.server.StreamRegistration
import com.vaadin.flow.server.VaadinSession
import com.vaadin.flow.server.streams.DownloadEvent
import com.vaadin.flow.server.streams.DownloadHandler
import com.vaadin.flow.shared.Registration
import diagrams.harness.Patch
import diagrams.model.Diagnostic
import diagrams.model.JsonWriter
import diagrams.model.Value
import java.util.concurrent.CompletableFuture

/**
 * The diagrams viewer as a Vaadin component: the core runs in the browser (the harness compiled to
 * JavaScript, spec sections 16 and 17), and this component drives it from the server.
 *
 * Give it a [bundle] (or [load] one from a [DiagramFiles] source, which also serves the assets).
 * It draws the document scaled to fit, outlines lint findings in place, follows clicks, plays the
 * timeline with its sound, and reports what happens as events. The state it reports (findings,
 * state containers, clock) is mirrored here, as of the last event, so it can be read at once.
 *
 * Drive operations ([goTo], [next], [follow], ...) and reads ([read], [svg], [document], ...) run in
 * the browser and answer with a [CompletableFuture]; a drive that fails completes exceptionally with
 * a [DiagramException], and is also reported as a [DiagramErrorEvent].
 */
@Tag("diagrams-viewer")
@JsModule("./diagrams-viewer/diagrams-viewer.js")
class DiagramViewer() : Component(), HasSize, HasStyle {
    constructor(bundle: DiagramBundle) : this() {
        this.bundle = bundle
    }

    private var revision = 0
    private val assetRegistrations = mutableListOf<StreamRegistration>()

    /** The document shown. Setting it starts over: first states, clock at zero. See [reload] to keep them. */
    var bundle: DiagramBundle?
        get() = current
        set(value) {
            current = value
            send(value, keep = false)
        }

    private var current: DiagramBundle? = null

    /** Shows [bundle] and keeps the states and the clock where they are, as a live edit would. */
    fun reload(bundle: DiagramBundle) {
        current = bundle
        send(bundle, keep = true)
    }

    private fun send(bundle: DiagramBundle?, keep: Boolean) {
        revision++
        if (bundle == null) {
            element.setProperty("load", null as String?)
            return
        }
        // the revision makes the same bundle, sent again, a change
        val spec = Value.map("keep" to Value.Bool(keep), "revision" to Value.num(revision), "bundle" to bundle.toValue())
        element.setProperty("load", JsonWriter.write(spec).trimEnd())
    }

    /**
     * Reads [main] and what it imports from [files] and shows it ([reload] when [keepState]). The
     * assets are served by this component, for the current session: call it from the UI thread.
     */
    @JvmOverloads
    fun load(main: String, files: DiagramFiles, keepState: Boolean = false): DiagramBundle {
        val session = VaadinSession.getCurrent() ?: error("no Vaadin session: load a document from the UI thread")
        val previous = assetRegistrations.toList()
        assetRegistrations.clear()
        val b = DiagramBundle.load(main, files) { asset ->
            val r = session.resourceRegistry.registerResource(AssetDownload(asset.bytes, asset.contentType), element)
            assetRegistrations += r
            r.resourceUri.toString()
        }
        if (keepState) reload(b) else bundle = b
        previous.forEach { it.unregister() }
        return b
    }

    // ---- presentation ----

    /** Outlines lint findings in place on the drawing. On by default. */
    var isOutlines: Boolean
        get() = element.getProperty("outlines", true)
        set(value) {
            element.setProperty("outlines", value)
        }

    /** Space plays and pauses, arrows and Page Up/Down step the scene's sequence. Off by default. */
    var isKeyboardNavigation: Boolean
        get() = element.getProperty("keyboard", false)
        set(value) {
            element.setProperty("keyboard", value)
        }

    /** Plays the document's sound with the scene clock. On by default. */
    var isAudio: Boolean
        get() = element.getProperty("audio", true)
        set(value) {
            element.setProperty("audio", value)
        }

    /** Starts the clock when a document with a timeline is loaded. Off by default. */
    var isAutoplay: Boolean
        get() = element.getProperty("autoplay", false)
        set(value) {
            element.setProperty("autoplay", value)
        }

    /** A click selects (and outlines) the node under it. On by default. */
    var isSelectable: Boolean
        get() = element.getProperty("selectable", true)
        set(value) {
            element.setProperty("selectable", value)
        }

    /** How often, in milliseconds, a [DiagramTimeEvent] reports the clock while it plays; 0, the default, never. */
    var timeUpdateInterval: Int
        get() = element.getProperty("timeUpdates", 0)
        set(value) {
            element.setProperty("timeUpdates", value.toDouble())
        }

    /** What the viewer says when it has no document. */
    var emptyText: String
        get() = element.getProperty("emptyText", "No document.")
        set(value) {
            element.setProperty("emptyText", value)
        }

    /** The selected node's path, outlined; set by a click too. */
    var selected: String?
        get() = element.getProperty("selected")
        set(value) {
            element.setProperty("selected", value)
        }

    /** Outlines nodes, by path, in a CSS colour each; replaces the previous highlights. */
    fun highlight(paths: Map<String, String>) {
        val v = Value.List(paths.map { (p, c) -> Value.map("path" to Value.Str(p), "color" to Value.Str(c)) })
        element.setProperty("highlights", JsonWriter.write(v).trimEnd())
    }

    /** Outlines nodes, by path, in [color]. */
    @JvmOverloads
    fun highlight(paths: Collection<String>, color: String = "#2d6cdf") = highlight(paths.associateWith { color })

    fun clearHighlights() = highlight(emptyMap())

    // ---- state, as of the last event ----

    private var diagnosticsJson: String? = null
    private var containersJson: String? = null

    /** The title of the document loaded, or its file. */
    var title: String? = null
        private set

    /** Whether a document is loaded and drawn (false when it has errors). */
    var isRendered: Boolean = false
        private set

    val diagnostics: List<Diagnostic> get() = DiagramJson.diagnostics(diagnosticsJson)
    val containers: List<DiagramContainer> get() = DiagramJson.containers(containersJson)
    val hasErrors: Boolean get() = diagnostics.any { it.severity == diagrams.model.Severity.ERROR }

    /** The container of [owner], `scene` for the document's own. */
    @JvmOverloads
    fun container(owner: String = "scene"): DiagramContainer? = containers.firstOrNull { it.owner == owner }

    var time: Double = 0.0
        private set
    var duration: Double = 0.0
        private set
    var isPlaying: Boolean = false
        private set

    init {
        addListener(DiagramLoadEvent::class.java) {
            title = it.title
            isRendered = it.isRendered
            diagnosticsJson = it.diagnosticsJson
            containersJson = it.containersJson
            duration = it.duration
            time = 0.0
        }
        addListener(DiagramStateChangeEvent::class.java) { containersJson = it.containersJson }
        addListener(DiagramDiagnosticsEvent::class.java) { diagnosticsJson = it.diagnosticsJson }
        addListener(DiagramPlaybackEvent::class.java) {
            isPlaying = it.isPlaying
            time = it.time
            duration = it.duration
        }
        addListener(DiagramTimeEvent::class.java) { time = it.time }
        // the browser selected it: so the server knows, and setting it again is sent
        addListener(DiagramClickEvent::class.java) { element.setProperty("selected", it.path) }
    }

    // ---- driving ----

    fun goTo(owner: String, state: String): CompletableFuture<Void?> = drive("goTo", owner, state)

    @JvmOverloads
    fun next(owner: String = "scene"): CompletableFuture<Void?> = drive("next", owner)

    @JvmOverloads
    fun prev(owner: String = "scene"): CompletableFuture<Void?> = drive("prev", owner)

    /** Into [child] of the current state of a tree, or its first child. */
    @JvmOverloads
    fun enter(owner: String = "scene", child: String? = null): CompletableFuture<Void?> = drive("enter", owner, child)

    @JvmOverloads
    fun up(owner: String = "scene"): CompletableFuture<Void?> = drive("up", owner)

    @JvmOverloads
    fun sibling(owner: String = "scene", delta: Int = 1): CompletableFuture<Void?> = drive("sibling", owner, delta)

    /** Follows [edge] out of the current state of a graph. */
    fun follow(owner: String, edge: String): CompletableFuture<Void?> = drive("follow", owner, edge)

    fun play(): CompletableFuture<Void?> = drive("play")

    fun pause(): CompletableFuture<Void?> = drive("pause")

    fun togglePlay(): CompletableFuture<Void?> = drive("togglePlay")

    /** Moves the clock to [t] seconds. */
    fun seek(t: Double): CompletableFuture<Void?> = drive("seek", t)

    /** Starts over: the document as loaded, first states, clock at zero. */
    fun reset(): CompletableFuture<Void?> = drive("reset")

    /** A click at a point of the viewport: hit-tested, selected and sent to the document's pointer listeners. */
    fun click(x: Double, y: Double): CompletableFuture<String?> = call("click", x, y)

    private fun drive(operation: String, vararg args: Any?): CompletableFuture<Void?> =
        call(operation, *args).thenApply { err -> if (err != null) throw DiagramException(operation, err) else null }

    private fun call(function: String, vararg args: Any?): CompletableFuture<String?> =
        element.callJsFunction(function, *args).toCompletableFuture(String::class.java)

    // ---- reading ----

    /** Applies [patches] to the live document; completes with the findings after them. */
    fun patch(patches: List<Patch>): CompletableFuture<List<Diagnostic>> =
        call("patch", DiagramJson.patches(patches)).thenApply { json ->
            val v = DiagramJson.parse(json)
            val error = ((v as? Value.Map)?.get("error") as? Value.Str)?.value
            if (error != null) throw DiagramException("patch", error)
            DiagramJson.diagnostics(json)
        }

    /** Any resolved value by path (`order.box`, `scene.state`); null when the path names nothing. */
    fun read(path: String): CompletableFuture<Value?> = readJson(path).thenApply { DiagramJson.parse(it) }

    /** Any resolved value by path, as JSON. */
    fun readJson(path: String): CompletableFuture<String?> = call("read", path)

    /** The path of the node at a point of the viewport. */
    fun hit(x: Double, y: Double): CompletableFuture<String?> = call("hit", x, y)

    /** The document as patched, in authored form (YAML). */
    fun document(): CompletableFuture<String?> = call("document")

    /** The current frame as SVG; null when the document has errors. */
    fun svg(): CompletableFuture<String?> = call("svg")

    /** The sound and film to play with the frames. */
    fun media(): CompletableFuture<List<DiagramMediaClip>> = call("media").thenApply { DiagramJson.media(it) }

    // ---- listeners ----

    fun addLoadListener(listener: ComponentEventListener<DiagramLoadEvent>): Registration = addListener(DiagramLoadEvent::class.java, listener)

    fun addNodeClickListener(listener: ComponentEventListener<DiagramClickEvent>): Registration = addListener(DiagramClickEvent::class.java, listener)

    fun addStateChangeListener(listener: ComponentEventListener<DiagramStateChangeEvent>): Registration = addListener(DiagramStateChangeEvent::class.java, listener)

    fun addDiagnosticsListener(listener: ComponentEventListener<DiagramDiagnosticsEvent>): Registration = addListener(DiagramDiagnosticsEvent::class.java, listener)

    fun addPlaybackListener(listener: ComponentEventListener<DiagramPlaybackEvent>): Registration = addListener(DiagramPlaybackEvent::class.java, listener)

    fun addTimeListener(listener: ComponentEventListener<DiagramTimeEvent>): Registration = addListener(DiagramTimeEvent::class.java, listener)

    fun addErrorListener(listener: ComponentEventListener<DiagramErrorEvent>): Registration = addListener(DiagramErrorEvent::class.java, listener)
}

/** Serves one asset's bytes, inline. */
private class AssetDownload(private val bytes: ByteArray, private val type: String) : DownloadHandler {
    override fun handleDownloadRequest(event: DownloadEvent) {
        event.setContentType(type)
        event.setContentLength(bytes.size.toLong())
        event.inline()
        event.outputStream.use { it.write(bytes) }
    }
}

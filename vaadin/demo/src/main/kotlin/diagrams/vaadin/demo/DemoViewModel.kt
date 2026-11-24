package diagrams.vaadin.demo

import diagrams.model.Diagnostic
import diagrams.model.Severity
import diagrams.vaadin.DiagramFiles
import diagrams.vaadin.karibu.DiagramDocument
import diagrams.vaadin.overlay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine

/** A node the viewer selected, with what was read about it. */
data class NodeDetails(val path: String, val kind: String?, val box: List<Double>?)

/**
 * The demo's state: which example is open, its text as edited, the document the viewer shows, and
 * what the user asked to see. The viewer's own state (states, clock, findings) is its `state` flow.
 */
class DemoViewModel(private val files: DiagramFiles, val examples: List<String>) {
    private val _example = MutableStateFlow(examples.first())
    val example: StateFlow<String> = _example.asStateFlow()

    private val original = MutableStateFlow(read(examples.first()))
    private val _text = MutableStateFlow(original.value)
    val text: StateFlow<String> = _text.asStateFlow()

    val isEdited: Flow<Boolean> = combine(original, _text) { o, t -> o != t }

    private val _document = MutableStateFlow(DiagramDocument(path(examples.first()), files))
    val document: StateFlow<DiagramDocument> = _document.asStateFlow()

    private val _outlines = MutableStateFlow(true)
    val outlines: StateFlow<Boolean> = _outlines.asStateFlow()

    private val _highlighted = MutableStateFlow<Map<String, String>>(emptyMap())
    val highlighted: StateFlow<Map<String, String>> = _highlighted.asStateFlow()

    private val _selection = MutableStateFlow<NodeDetails?>(null)
    val selection: StateFlow<NodeDetails?> = _selection.asStateFlow()

    private val _failure = MutableStateFlow<String?>(null)
    val failure: StateFlow<String?> = _failure.asStateFlow()

    fun onSelectExample(name: String) {
        if (name !in examples) return
        _example.value = name
        original.value = read(name)
        _text.value = original.value
        _highlighted.value = emptyMap()
        _selection.value = null
        _failure.value = null
        _document.value = DiagramDocument(path(name), files)
    }

    /** The text as edited: the viewer reloads it, keeping its states and clock. */
    fun onEdit(text: String) {
        _text.value = text
        _failure.value = null
        _document.value = DiagramDocument(path(_example.value), files.overlay(mapOf(path(_example.value) to text)))
    }

    fun onRevert() = onEdit(original.value)

    fun onOutlines(on: Boolean) {
        _outlines.value = on
    }

    fun onSelected(details: NodeDetails?) {
        _selection.value = details
    }

    /** Outlines the node of a finding (or nothing), in the colour of its severity. */
    fun onFindingSelected(d: Diagnostic?) {
        _highlighted.value = d?.path?.takeIf { it.isNotEmpty() && it != "scene" }
            ?.let { mapOf(it to if (d.severity == Severity.ERROR) "#c0392b" else "#b7791f") }
            .orEmpty()
    }

    fun onLoadFailed(message: String?) {
        _failure.value = message ?: "the document cannot be read"
    }

    private fun path(name: String) = "examples/$name"

    private fun read(name: String) = files.read(path(name))?.decodeToString().orEmpty()

    companion object {
        /** The repository's examples, packed into the demo as class path resources. */
        fun fromClasspath(): DemoViewModel {
            val files = DiagramFiles.classpath("diagrams")
            val examples = files.read("examples/index.txt")?.decodeToString()?.lines()?.filter { it.isNotBlank() }.orEmpty()
            check(examples.isNotEmpty()) { "no examples on the class path" }
            val first = examples.firstOrNull { it == "agent_deck.yaml" }
            return DemoViewModel(files, listOfNotNull(first) + (examples - setOfNotNull(first)))
        }
    }
}

package diagrams.vaadin.demo

import com.github.mvysny.karibudsl.v10.KComposite
import com.github.mvysny.karibudsl.v10.VaadinDsl
import com.github.mvysny.karibudsl.v10.badge
import com.github.mvysny.karibudsl.v10.button
import com.github.mvysny.karibudsl.v10.checkBox
import com.github.mvysny.karibudsl.v10.columnFor
import com.github.mvysny.karibudsl.v10.content
import com.github.mvysny.karibudsl.v10.details
import com.github.mvysny.karibudsl.v10.flexBasis
import com.github.mvysny.karibudsl.v10.grid
import com.github.mvysny.karibudsl.v10.h4
import com.github.mvysny.karibudsl.v10.horizontalLayout
import com.github.mvysny.karibudsl.v10.init
import com.github.mvysny.karibudsl.v10.isExpand
import com.github.mvysny.karibudsl.v10.onClick
import com.github.mvysny.karibudsl.v10.primary
import com.github.mvysny.karibudsl.v10.secondary
import com.github.mvysny.karibudsl.v10.select
import com.github.mvysny.karibudsl.v10.span
import com.github.mvysny.karibudsl.v10.splitLayout
import com.github.mvysny.karibudsl.v10.textArea
import com.github.mvysny.karibudsl.v10.verticalLayout
import com.vaadin.flow.component.HasComponents
import com.vaadin.flow.component.button.ButtonVariant
import com.vaadin.flow.component.grid.GridVariant
import com.vaadin.flow.component.notification.Notification
import com.vaadin.flow.component.notification.NotificationVariant
import com.vaadin.flow.component.orderedlayout.VerticalLayout
import com.vaadin.flow.data.value.ValueChangeMode
import com.vaadin.flow.router.PageTitle
import com.vaadin.flow.router.Route
import diagrams.model.Diagnostic
import diagrams.model.Value
import diagrams.vaadin.DiagramContainer
import diagrams.vaadin.DiagramViewer
import diagrams.vaadin.karibu.bindDocument
import diagrams.vaadin.karibu.bindEnabled
import diagrams.vaadin.karibu.bindHighlights
import diagrams.vaadin.karibu.bindProperty
import diagrams.vaadin.karibu.bindText
import diagrams.vaadin.karibu.bindVisible
import diagrams.vaadin.karibu.diagramViewer
import diagrams.vaadin.karibu.onError
import diagrams.vaadin.karibu.onNodeClick
import diagrams.vaadin.karibu.state
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import java.util.Locale

/**
 * The demo: the drawing with its clock on the left; on the right the example's YAML, live-edited
 * (the viewer reloads it and keeps its states), its state containers, the node last clicked and
 * the lint findings.
 */
@Route("")
@PageTitle("diagrams · Vaadin")
class DemoView : KComposite() {
    private val vm = DemoViewModel.fromClasspath()
    private lateinit var viewer: DiagramViewer

    private val root = ui {
        splitLayout {
            setSizeFull()
            setSplitterPosition(62.0)
            primary {
                verticalLayout {
                    setSizeFull()
                    viewer = diagramViewer {
                        addClassName("demo-viewer")
                        setWidthFull()
                        isExpand = true
                        isKeyboardNavigation = true
                        timeUpdateInterval = 200
                        emptyText = "Loading…"
                        bindDocument(vm.document, onFailure = { vm.onLoadFailed(it.message) })
                        bindProperty(vm.outlines) { isOutlines = it }
                        bindHighlights(vm.highlighted)
                        onNodeClick { showDetails(it.path) }
                        onError { notifyError("${it.operation}: ${it.message}") }
                    }
                    playbackBar(viewer, vm)
                }
            }
            secondary {
                verticalLayout {
                    setSizeFull()
                    horizontalLayout {
                        setWidthFull()
                        content { align(left, bottom) }
                        select<String>("Example") {
                            isExpand = true
                            setItems(vm.examples)
                            bindProperty(vm.example) { if (value != it) value = it }
                            addValueChangeListener { if (it.isFromClient && it.value != null) vm.onSelectExample(it.value) }
                        }
                        button("Revert") {
                            bindEnabled(vm.isEdited)
                            onClick { vm.onRevert() }
                        }
                    }
                    span {
                        addClassName("demo-muted")
                        bindText(vm.failure) { it.orEmpty() }
                        bindVisible(vm.failure.map { it != null })
                    }
                    textArea("Document") {
                        addClassName("demo-editor")
                        setWidthFull()
                        // grows into the room the panels below leave, and scrolls inside it
                        isExpand = true
                        flexBasis = "0"
                        minHeight = "10em"
                        valueChangeMode = ValueChangeMode.LAZY
                        valueChangeTimeout = 400
                        bindProperty(vm.text) { if (value != it) value = it }
                        addValueChangeListener { if (it.isFromClient) vm.onEdit(it.value) }
                    }
                    details("States") {
                        setWidthFull()
                        isOpened = true
                        content { statesPanel(viewer) }
                    }
                    details("Selection") {
                        setWidthFull()
                        isOpened = true
                        content { selectionPanel(vm.selection) }
                    }
                    details("Diagnostics") {
                        setWidthFull()
                        isOpened = true
                        content {
                            diagnosticsGrid(viewer.state.map { it.diagnostics }.distinctUntilChanged()) { vm.onFindingSelected(it) }
                        }
                    }
                }
            }
        }
    }

    /** Reads what the browser knows of the clicked node. */
    private fun showDetails(path: String?) {
        if (path == null) return vm.onSelected(null)
        viewer.read("$path.kind").thenCombine(viewer.read("$path.box")) { kind, box ->
            NodeDetails(path, (kind as? Value.Str)?.value, (box as? Value.List)?.items?.mapNotNull { (it as? Value.Num)?.value })
        }.thenAccept { vm.onSelected(it) }
    }
}

/** Title, play and pause, restart, the clock and the outline switch, under the drawing. */
@VaadinDsl
private fun (@VaadinDsl HasComponents).playbackBar(viewer: DiagramViewer, vm: DemoViewModel) = horizontalLayout {
    setWidthFull()
    content { align(left, middle) }
    h4 {
        isExpand = true
        bindText(viewer.state) { it.title.orEmpty() }
    }
    button {
        addThemeVariants(ButtonVariant.LUMO_PRIMARY)
        bindText(viewer.state.map { if (it.isPlaying) "Pause" else "Play" })
        bindEnabled(viewer.state.map { it.hasTimeline })
        onClick { viewer.togglePlay() }
    }
    button("Restart") { onClick { viewer.reset() } }
    span {
        addClassName("demo-clock")
        bindText(viewer.state) { "%.2f / %.2f s".format(Locale.ROOT, it.time, it.duration) }
    }
    checkBox("Outlines") {
        bindProperty(vm.outlines) { if (value != it) value = it }
        addValueChangeListener { if (it.isFromClient) vm.onOutlines(it.value) }
    }
}

/** The path, kind and box of the node last clicked. */
@VaadinDsl
private fun (@VaadinDsl HasComponents).selectionPanel(selection: Flow<NodeDetails?>) = verticalLayout(padding = false, spacing = false) {
    span {
        addClassName("demo-mono")
        bindText(selection) { it?.path ?: "Click a node to see its path." }
    }
    span {
        addClassName("demo-muted")
        bindText(selection) { d ->
            listOfNotNull(
                d?.kind?.let { "kind $it" },
                d?.box?.let { b -> "box " + b.joinToString(", ") { "%.1f".format(Locale.ROOT, it) } },
            ).joinToString(" · ")
        }
    }
}

/** The lint and validation findings; choosing one outlines its node. */
@VaadinDsl
private fun (@VaadinDsl HasComponents).diagnosticsGrid(diagnostics: Flow<List<Diagnostic>>, onSelect: (Diagnostic?) -> Unit) = grid<Diagnostic> {
    width = "100%"
    height = "14em"
    addThemeVariants(GridVariant.LUMO_COMPACT, GridVariant.LUMO_ROW_STRIPES)
    columnFor(Diagnostic::severity, converter = { it?.label }) {
        setHeader("Severity")
        setAutoWidth(true)
        setFlexGrow(0)
    }
    columnFor(Diagnostic::code) {
        setHeader("Code")
        setAutoWidth(true)
    }
    columnFor(Diagnostic::path) { setHeader("Path") }
    columnFor(Diagnostic::message) {
        setHeader("Message")
        setFlexGrow(3)
    }
    bindProperty(diagnostics) { setItems(it) }
    asSingleSelect().addValueChangeListener { if (it.isFromClient) onSelect(it.value) }
}

/** Every state container of the document, with what it offers to navigate. */
class StatesPanel(private val viewer: DiagramViewer) : KComposite() {
    private val root = ui {
        verticalLayout(padding = false) {
            bindProperty(viewer.state.map { it.containers }.distinctUntilChanged()) { render(it) }
        }
    }

    private fun VerticalLayout.render(containers: List<DiagramContainer>) {
        removeAll()
        if (containers.isEmpty()) span("No state containers.") { addClassName("demo-muted") }
        containers.forEach { containerRow(it) }
    }

    private fun HasComponents.containerRow(c: DiagramContainer) = verticalLayout(padding = false, spacing = false) {
        horizontalLayout {
            content { align(left, baseline) }
            span(c.owner) { addClassName("demo-owner") }
            badge(c.kind)
        }
        horizontalLayout {
            content { align(left, middle) }
            select<String> {
                setItems(c.states)
                value = c.current
                addValueChangeListener { if (it.isFromClient && it.value != null) viewer.goTo(c.owner, it.value) }
            }
            when {
                c.isSequence -> {
                    small("◀", "Previous state") { viewer.prev(c.owner) }
                    small("▶", "Next state") { viewer.next(c.owner) }
                }
                c.isTree -> {
                    small("Up", "To the parent state") { viewer.up(c.owner) }
                    small("Enter", "Into the first child state") { viewer.enter(c.owner) }
                    small("◀", "Previous sibling") { viewer.sibling(c.owner, -1) }
                    small("▶", "Next sibling") { viewer.sibling(c.owner, 1) }
                }
                c.isGraph -> c.currentEdges.forEach { (edge, to) -> small(edge, "Follow $edge to $to") { viewer.follow(c.owner, edge) } }
            }
        }
    }

    private fun HasComponents.small(text: String, tooltip: String, action: () -> Unit) = button(text) {
        addThemeVariants(ButtonVariant.LUMO_SMALL)
        setTooltipText(tooltip)
        onClick { action() }
    }
}

@VaadinDsl
fun (@VaadinDsl HasComponents).statesPanel(viewer: DiagramViewer, block: (@VaadinDsl StatesPanel).() -> Unit = {}) =
    init(StatesPanel(viewer), block)

private fun notifyError(message: String) = Notification.show(message, 4000, Notification.Position.BOTTOM_END).apply {
    addThemeVariants(NotificationVariant.LUMO_ERROR)
}

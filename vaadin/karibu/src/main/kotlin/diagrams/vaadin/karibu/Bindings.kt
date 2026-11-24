package diagrams.vaadin.karibu

import com.vaadin.flow.component.Component
import com.vaadin.flow.component.HasEnabled
import com.vaadin.flow.component.HasText
import com.vaadin.flow.component.UI
import com.vaadin.flow.server.VaadinSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/**
 * Applies every value of [flow] to this component while it is attached: the current value at once
 * on attach, then each change. A value emitted on the UI thread is applied there and then; one
 * emitted on another thread goes through [UI.access] (with server push, it reaches the browser
 * right away; without it, on the next round trip).
 */
fun <C : Component, T> C.bindProperty(flow: Flow<T>, apply: C.(T) -> Unit): C {
    var job: Job? = null
    fun start(ui: UI) {
        job?.cancel()
        job = CoroutineScope(Dispatchers.Unconfined).launch(start = CoroutineStart.UNDISPATCHED) {
            flow.collect { value ->
                if (VaadinSession.getCurrent()?.hasLock() == true) apply(value) else ui.access { apply(value) }
            }
        }
    }
    addAttachListener { start(it.ui) }
    addDetachListener {
        job?.cancel()
        job = null
    }
    ui.ifPresent { start(it) }
    return this
}

/** The text of this component, from [flow]. */
fun <C, T> C.bindText(flow: Flow<T>, format: (T) -> String = { it?.toString().orEmpty() }): C where C : Component, C : HasText =
    bindProperty(flow) { text = format(it) }

fun <C> C.bindEnabled(flow: Flow<Boolean>): C where C : Component, C : HasEnabled = bindProperty(flow) { isEnabled = it }

fun <C : Component> C.bindVisible(flow: Flow<Boolean>): C = bindProperty(flow) { isVisible = it }

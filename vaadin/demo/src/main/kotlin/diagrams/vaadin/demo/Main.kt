package diagrams.vaadin.demo

import com.github.mvysny.vaadinboot.VaadinBoot
import com.vaadin.flow.component.dependency.StyleSheet
import com.vaadin.flow.component.page.AppShellConfigurator
import com.vaadin.flow.theme.lumo.Lumo

@StyleSheet(Lumo.STYLESHEET)
@StyleSheet("styles.css")
class AppShell : AppShellConfigurator

/** Starts the demo on http://localhost:8080 (the `SERVER_PORT` environment variable changes the port). */
fun main() {
    VaadinBoot().run()
}

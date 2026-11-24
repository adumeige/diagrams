@file:OptIn(ExperimentalJsExport::class)

package diagrams.embed

/**
 * The entry of the JS bundle. It has no code of its own: the bundle exports the harness's
 * `@JsExport` API (`Viewer`); a module without sources would not be compiled at all.
 */
@JsExport
fun embedVersion(): String = "1"

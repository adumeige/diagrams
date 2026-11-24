package diagrams.vaadin

import diagrams.model.Diagnostic
import diagrams.model.JsonWriter
import diagrams.model.Value
import diagrams.model.YamlWriter
import diagrams.processor.ImportLoader
import diagrams.processor.LoadedFile
import diagrams.processor.Processor
import diagrams.resolver.Probe
import java.io.FileNotFoundException
import java.io.Serializable

/**
 * What the viewer runs in the browser: a document, the text of every file it imports, the asset
 * manifest (font metrics, media facts) and a URL for each asset. Processing, resolution, lint and
 * rendering all happen in the browser; the server only gathers the files.
 *
 * [files] are keyed by path, [main] among them; imports resolve against the importing file's
 * directory. [manifest] is the asset manifest as YAML, empty when the document uses no assets.
 */
class DiagramBundle @JvmOverloads constructor(
    val main: String,
    val files: Map<String, String>,
    val manifest: String = "",
    val assets: Map<String, String> = emptyMap(),
    val title: String? = null,
    /** What gathering the files found: missing imports are reported by the viewer, missing or unreadable assets only here. */
    @Transient val problems: List<Diagnostic> = emptyList(),
) : Serializable {
    /** The bundle as the JavaScript `Viewer` reads it. */
    fun toValue(): Value.Map = Value.map(
        "main" to Value.Str(main),
        "title" to (title?.let { Value.Str(it) } ?: Value.Null),
        "files" to Value.Map(files.mapValuesTo(LinkedHashMap()) { Value.Str(it.value) }),
        "manifest" to Value.Str(manifest),
        "assets" to Value.Map(assets.mapValuesTo(LinkedHashMap()) { Value.Str(it.value) }),
    )

    fun toJson(): String = JsonWriter.write(toValue()).trimEnd()

    companion object {
        /** A document without imports nor assets. */
        @JvmStatic
        @JvmOverloads
        fun of(text: String, main: String = "document.yaml"): DiagramBundle = DiagramBundle(main, mapOf(main to text))

        /**
         * Reads [main] from [files], with every file it imports (transitively) and every asset its
         * imports declare. [assetUrl] gives the URL the browser fetches an asset from; an asset
         * without one is still measured, but the viewer cannot draw or play it.
         */
        @JvmStatic
        @JvmOverloads
        fun load(main: String, files: DiagramFiles, assetUrl: (DiagramAsset) -> String? = { null }): DiagramBundle {
            val mainPath = DiagramFiles.normalize(main) ?: throw IllegalArgumentException("not a relative path: $main")
            val mainText = files.read(mainPath)?.decodeToString() ?: throw FileNotFoundException(mainPath)
            val texts = linkedMapOf(mainPath to mainText)
            val gathered = ImportLoader { path, from ->
                resolve(path, from)?.let { p ->
                    val text = texts[p] ?: files.read(p)?.decodeToString()?.also { texts[p] = it }
                    text?.let { LoadedFile(it, p) }
                }
            }
            val processed = Processor.process(mainText, mainPath, gathered)
            val probed = Probe.probe(processed.environment) { module, file ->
                resolve(file, module.file)?.let { p -> files.read(p)?.let { Probe.Read(p, it) } }
            }
            val urls = LinkedHashMap<String, String>()
            for ((name, entry) in probed.manifest.assets) {
                val bytes = files.read(entry.file) ?: continue
                assetUrl(DiagramAsset(name, entry.kind, entry.file, bytes))?.let { urls[name] = it }
            }
            val title = ((processed.canonical as? Value.Map)?.get("title") as? Value.Str)?.value
            val manifest = if (probed.manifest.assets.isEmpty()) "" else YamlWriter.write(probed.manifest.toValue())
            return DiagramBundle(mainPath, texts, manifest, urls, title, probed.diagnostics)
        }

        /** [path] as written in [from], relative to the root; null when it leaves the root. */
        private fun resolve(path: String, from: String?): String? {
            val dir = from?.substringBeforeLast('/', "")?.takeIf { from.contains('/') }
            return DiagramFiles.normalize(if (dir.isNullOrEmpty() || path.startsWith("/")) path else "$dir/$path")
        }
    }
}

/** An asset a document declares: its name, kind (`font`, `image`, `audio`, `video`), path and bytes. */
class DiagramAsset(val name: String, val kind: String, val path: String, val bytes: ByteArray) {
    /** The MIME type, from the file's extension. */
    val contentType: String
        get() = when (path.substringAfterLast('.').lowercase()) {
            "ttf" -> "font/ttf"
            "otf" -> "font/otf"
            "woff" -> "font/woff"
            "woff2" -> "font/woff2"
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "gif" -> "image/gif"
            "svg" -> "image/svg+xml"
            "webp" -> "image/webp"
            "wav" -> "audio/wav"
            "mp3" -> "audio/mpeg"
            "ogg" -> "audio/ogg"
            "m4a" -> "audio/mp4"
            "mp4" -> "video/mp4"
            "mov" -> "video/quicktime"
            "webm" -> "video/webm"
            else -> "application/octet-stream"
        }
}

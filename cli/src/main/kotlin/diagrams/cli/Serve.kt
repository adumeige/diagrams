package diagrams.cli

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import diagrams.model.JsonWriter
import diagrams.model.Value
import diagrams.model.YamlWriter
import diagrams.processor.ImportLoader
import diagrams.processor.LoadedFile
import diagrams.processor.Processor
import diagrams.resolver.FileAssets
import java.io.File
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * The live viewer's server (spec section 17, "Viewer"). It only serves files: the viewer page, the
 * core compiled to ES modules, and a bundle of the document with every file it imports and its
 * asset manifest. Processing, resolution, lint and rendering happen in the browser. The page polls
 * `version` and reloads the bundle when a file changes.
 *
 * It listens on the loopback interface only, and serves only the document's files and assets.
 */
class ViewerServer(private val doc: File, private val port: Int) {
    private val dir: File = doc.absoluteFile.parentFile
    @Volatile private var allowed: Set<String> = emptySet()

    private class Bundle(val json: String, val files: List<File>)

    /** Reads the document and everything it imports, keyed by path relative to the document's directory. */
    private fun bundle(): Bundle {
        val files = LinkedHashMap<String, String>()
        val read = mutableListOf<File>()
        val main = doc.name
        val loader = ImportLoader { path, from ->
            val base = from?.substringBeforeLast('/', "")?.takeIf { from.contains('/') }
            val rel = normalize(if (base.isNullOrEmpty()) path else "$base/$path")
            val f = File(dir, rel)
            if (!f.isFile) {
                null
            } else {
                read += f
                files[rel] = f.readText()
                LoadedFile(files[rel]!!, rel)
            }
        }
        val text = doc.readText()
        files[main] = text
        read += doc
        val pr = Processor.process(text, main, loader)
        val probed = FileAssets(dir, dir).probe(pr.environment)
        val assets = LinkedHashMap<String, Value>()
        for ((name, a) in probed.manifest.assets) {
            assets[name] = Value.Str("asset?f=" + URLEncoder.encode(a.file, Charsets.UTF_8))
            read += File(dir, a.file)
        }
        allowed = probed.manifest.assets.values.map { it.file }.toSet()
        val title = ((pr.canonical as? Value.Map)?.get("title") as? Value.Str)?.value
        val json = JsonWriter.write(
            Value.map(
                "main" to Value.Str(main),
                "title" to (title?.let { Value.Str(it) } ?: Value.Null),
                "version" to Value.Str(version(read)),
                "files" to Value.Map(files.mapValuesTo(LinkedHashMap()) { Value.Str(it.value) }),
                "manifest" to Value.Str(YamlWriter.write(probed.manifest.toValue())),
                "assets" to Value.Map(assets),
            ),
        )
        return Bundle(json, read)
    }

    @Volatile private var watched: List<File> = listOf(doc)

    private fun version(files: List<File>) = files.joinToString(",") { "${it.lastModified()}:${it.length()}" }.hashCode().toString(16)

    private fun normalize(path: String): String {
        val out = ArrayDeque<String>()
        for (s in path.split('/')) {
            when {
                s == "" || s == "." -> {}
                s == ".." && out.isNotEmpty() && out.last() != ".." -> out.removeLast()
                else -> out.addLast(s)
            }
        }
        return out.joinToString("/")
    }

    fun start(): HttpServer {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", port), 0)
        server.createContext("/") { ex ->
            try {
                handle(ex)
            } catch (e: Exception) {
                send(ex, 500, "text/plain", (e.message ?: "error").toByteArray())
            }
        }
        server.executor = java.util.concurrent.Executors.newFixedThreadPool(4)
        server.start()
        return server
    }

    private fun handle(ex: HttpExchange) {
        val path = ex.requestURI.path
        when {
            path == "/" || path == "/index.html" -> resource(ex, "viewer/index.html", "text/html; charset=utf-8")
            path == "/viewer.js" -> resource(ex, "viewer/viewer.js", "text/javascript; charset=utf-8")
            path.startsWith("/lib/") && path.endsWith(".mjs") && !path.contains("..") -> resource(ex, "viewer" + path, "text/javascript; charset=utf-8")
            path == "/bundle" -> {
                val b = bundle()
                watched = b.files
                send(ex, 200, "application/json; charset=utf-8", b.json.toByteArray())
            }
            path == "/version" -> send(ex, 200, "text/plain", version(watched).toByteArray())
            path == "/asset" -> {
                val f = ex.requestURI.rawQuery?.split('&')?.firstOrNull { it.startsWith("f=") }?.removePrefix("f=")?.let { URLDecoder.decode(it, Charsets.UTF_8) }
                if (f == null || f !in allowed) return send(ex, 404, "text/plain", "not an asset of the document".toByteArray())
                val file = File(dir, f)
                if (!file.isFile) return send(ex, 404, "text/plain", "missing".toByteArray())
                send(ex, 200, contentType(file.name), file.readBytes())
            }
            else -> send(ex, 404, "text/plain", "not found".toByteArray())
        }
    }

    private fun contentType(name: String) = when (name.substringAfterLast('.').lowercase()) {
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
        "webm" -> "video/webm"
        else -> "application/octet-stream"
    }

    private fun resource(ex: HttpExchange, name: String, type: String) {
        val bytes = javaClass.classLoader.getResourceAsStream(name)?.use { it.readBytes() } ?: return send(ex, 404, "text/plain", "missing $name".toByteArray())
        send(ex, 200, type, bytes)
    }

    private fun send(ex: HttpExchange, code: Int, type: String, body: ByteArray) {
        ex.responseHeaders.add("Content-Type", type)
        ex.responseHeaders.add("Cache-Control", "no-store")
        ex.sendResponseHeaders(code, body.size.toLong())
        ex.responseBody.use { it.write(body) }
    }
}

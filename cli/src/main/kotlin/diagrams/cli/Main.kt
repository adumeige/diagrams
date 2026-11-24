package diagrams.cli

import diagrams.assets.AssetManifest
import diagrams.assets.ManifestException
import diagrams.lint.Lint
import diagrams.model.Diagnostic
import diagrams.model.JsonWriter
import diagrams.model.Severity
import diagrams.model.Yaml
import diagrams.model.YamlWriter
import diagrams.model.toValue
import diagrams.processor.Environment
import diagrams.processor.FileImportLoader
import diagrams.processor.JsonSchema
import diagrams.processor.ProcessResult
import diagrams.processor.Processor
import diagrams.render.raster.RasterRenderer
import diagrams.render.svg.SvgRenderer
import diagrams.resolver.FileAssets
import diagrams.resolver.ResolveOptions
import diagrams.resolver.ResolvedScene
import diagrams.resolver.Resolver
import diagrams.resolver.dump
import diagrams.resolver.toValue
import java.io.File
import kotlin.system.exitProcess

private const val USAGE = """usage: diagrams <command> <file> [options]

commands (spec section 16):
  canon     canonical document (YAML) on stdout; --source-map FILE writes the source map (JSON)
  validate  structural diagnostics
  resolve   resolved scene (JSON)
  lint      structural diagnostics and geometric lint
  dump      one line per node: path, kind, box, state
  render    SVG or PNG: --out FILE.svg|FILE.png [--scale N] [--background #rrggbb|none]
  states    the state containers, their states and the current one
  deck      one slide per state of the scene: --out FILE.html (animated) | FILE.pdf | DIR [--format svg|png] [--fps N] [--scale N]
  video     the scene clock as a video: --out FILE.mp4|.mov|.webm (ffmpeg) | FILE.gif | DIR (PNG frames)
            [--fps N] [--scale N] [--duration S] [--walk OWNER [--hold S]] [--ffmpeg PATH]
            --walk holds each state of OWNER's container for --hold seconds (default 2), then transitions
  serve     live viewer in the browser: reloads on file change, shows findings in place and the path of
            what is clicked, drives states and the clock [--port N] (default 8737, loopback only)
  probe     asset manifest (YAML) of the fonts the document's themes declare
  schema    JSON Schema of the core and of the document's imported libraries (file optional)

options:
  --out FILE           write the output to FILE instead of stdout
  --diagnostics FMT    text (default), yaml or json; on stdout for validate and lint, on stderr otherwise
  --manifest FILE      asset manifest written by probe; without it, fonts are probed on the fly
  --state S            current states: 'owner=state,...', or a state of the scene's container alone
  --time T             seconds on the scene clock: timelines play to that moment
  --from S --time T    a frame T seconds into the transition from the states S to --state

exit status: 0 ok, 1 errors found, 2 usage or I/O error"""

private class Args(argv: Array<String>) {
    val positional = mutableListOf<String>()
    val options = mutableMapOf<String, String>()

    init {
        var i = 0
        while (i < argv.size) {
            val a = argv[i]
            if (a.startsWith("--")) {
                val key = a.removePrefix("--")
                if (key.contains('=')) {
                    options[key.substringBefore('=')] = key.substringAfter('=')
                } else {
                    if (i + 1 >= argv.size) fail("option --$key needs a value")
                    options[key] = argv[++i]
                }
            } else {
                positional += a
            }
            i++
        }
    }
}

private fun fail(msg: String): Nothing {
    System.err.println("diagrams: $msg")
    exitProcess(2)
}

fun main(argv: Array<String>) {
    if (argv.isEmpty() || argv[0] in setOf("-h", "--help", "help")) {
        println(USAGE)
        return
    }
    val args = Args(argv)
    val command = args.positional.getOrNull(0) ?: fail("missing command")
    if (command == "schema" && args.positional.size == 1) {
        val text = JsonWriter.write(JsonSchema.document(Environment.EMPTY))
        args.options["out"]?.let { File(it).writeText(text) } ?: print(text)
        return
    }
    val path = args.positional.getOrNull(1) ?: fail("missing input file\n$USAGE")
    val file = File(path)
    if (!file.isFile) fail("cannot read '$path'")
    exitProcess(Cli(args, file).run(command))
}

private class Cli(val args: Args, val file: File) {
    val diagFormat = args.options["diagnostics"] ?: "text"
    val assets = FileAssets(file.absoluteFile.parentFile)

    fun output(text: String) {
        val out = args.options["out"]
        if (out == null) print(text) else File(out).writeText(text)
    }

    fun printDiagnostics(ds: List<Diagnostic>, toStdout: Boolean) {
        // stdout belongs to the command's own output: diagnostics go there only when they are the output
        val out = if (toStdout) System.out else System.err
        when (diagFormat) {
            "yaml" -> out.print(if (ds.isEmpty()) "[]\n" else YamlWriter.write(ds.toValue()))
            "json" -> out.print(JsonWriter.write(ds.toValue()))
            else -> ds.forEach { out.println(it) }
        }
    }

    fun process(): ProcessResult = Processor.process(file.readText(), file.path, FileImportLoader())

    /** The manifest given with --manifest, or one probed now from the document's themes. */
    fun manifest(pr: ProcessResult, collected: MutableList<Diagnostic>): AssetManifest {
        val given = args.options["manifest"]
        if (given != null) {
            val f = File(given)
            if (!f.isFile) fail("cannot read manifest '$given'")
            return try {
                AssetManifest.fromValue(Yaml.parse(f.readText(), f.path).value)
            } catch (e: ManifestException) {
                fail("manifest '$given': ${e.message}")
            } catch (e: diagrams.model.YamlException) {
                fail("manifest '$given': ${e.message}")
            }
        }
        val probed = assets.probe(pr.environment)
        collected += probed.diagnostics
        return probed.manifest
    }

    class Resolved(val scene: ResolvedScene, val manifest: AssetManifest)

    /** Processes and resolves; returns null and prints diagnostics when the document has errors. */
    fun resolveOrReport(collected: MutableList<Diagnostic>): Resolved? {
        val pr = process()
        collected += pr.diagnostics
        if (pr.hasErrors || pr.canonical == null) return null
        val manifest = manifest(pr, collected)
        val time = args.options["time"]?.toDoubleOrNull() ?: 0.0
        val opts = ResolveOptions(time = time, manifest = manifest)
        val to = states("state")
        val rr = if (args.options["from"] != null) {
            Resolver.resolveTransition(pr, states("from"), to, time, opts)
        } else {
            Resolver.resolve(pr, opts.copy(states = to))
        }
        collected += rr.diagnostics
        return if (collected.any { it.severity == Severity.ERROR }) null else Resolved(rr.scene!!, manifest)
    }

    /** `owner=state,owner=state`, or a bare state id for the scene's own container. */
    fun states(key: String): Map<String, String> {
        val v = args.options[key] ?: return emptyMap()
        return v.split(',').filter { it.isNotBlank() }.associate { s ->
            if ('=' in s) s.substringBefore('=').trim() to s.substringAfter('=').trim() else "scene" to s.trim()
        }
    }

    fun status(ds: List<Diagnostic>) = if (ds.any { it.severity == Severity.ERROR }) 1 else 0

    fun run(command: String): Int = when (command) {
        "canon" -> {
            val pr = process()
            pr.canonical?.let { output(YamlWriter.write(it)) }
            args.options["source-map"]?.let { File(it).writeText(JsonWriter.write(Processor.sourceMapToValue(pr.sourceMap))) }
            printDiagnostics(pr.diagnostics, toStdout = false)
            status(pr.diagnostics)
        }
        "schema" -> {
            val pr = process()
            printDiagnostics(pr.diagnostics, toStdout = false)
            val title = ((pr.canonical as? diagrams.model.Value.Map)?.get("title") as? diagrams.model.Value.Str)?.value
            output(JsonWriter.write(JsonSchema.document(pr.environment, title?.let { "Schema for '$it'" })))
            0
        }
        "validate" -> {
            val ds = process().diagnostics
            printDiagnostics(ds, toStdout = true)
            status(ds)
        }
        "probe" -> {
            val pr = process()
            val ds = pr.diagnostics.toMutableList()
            val probed = assets.probe(pr.environment)
            ds += probed.diagnostics
            printDiagnostics(ds, toStdout = false)
            if (status(ds) == 0) output(YamlWriter.write(probed.manifest.toValue()))
            status(ds)
        }
        "states" -> {
            val pr = process()
            val ds = pr.diagnostics.toMutableList()
            if (!pr.hasErrors && pr.canonical != null) {
                val rr = Resolver.resolve(pr, ResolveOptions(manifest = manifest(pr, ds), states = states("state")))
                ds += rr.diagnostics
                val sb = StringBuilder()
                for (c in rr.containers) {
                    val cur = rr.scene?.states?.get(c.owner)
                    sb.append("${c.owner}  ${c.container.kind}  ")
                    sb.append(c.container.states.joinToString(" ") { if (it.id == cur) "[${it.id}]" else it.id })
                    sb.append('\n')
                }
                if (rr.containers.isEmpty()) sb.append("no state containers\n")
                output(sb.toString())
            }
            printDiagnostics(ds, toStdout = false)
            status(ds)
        }
        "deck" -> {
            val out = args.options["out"] ?: fail("deck needs --out FILE.html, FILE.pdf or a directory")
            val pr = process()
            val ds = pr.diagnostics.toMutableList()
            if (pr.hasErrors || pr.canonical == null) {
                printDiagnostics(ds, toStdout = false)
                return 1
            }
            val manifest = manifest(pr, ds)
            val deck = Deck.build(pr, ResolveOptions(manifest = manifest, states = states("state"))) { ds += it }
            val errors = ds.distinct().filter { it.severity == Severity.ERROR }
            printDiagnostics(ds.distinct(), toStdout = false)
            if (deck == null || errors.isNotEmpty()) return 1
            val scale = args.options["scale"]?.toDoubleOrNull()
            val bgOpt = args.options["background"]
            val bg = if (bgOpt == "none") null else bgOpt
            val files = assets.fontFiles(manifest)
            val title = ((pr.canonical as? diagrams.model.Value.Map)?.get("title") as? diagrams.model.Value.Str)?.value ?: file.nameWithoutExtension
            val png = media.raster(manifest, RasterRenderer.Options(scale ?: 2.0, bg, files.mapValues { (name, f) -> RasterRenderer.FontFace(f, manifest.font(name)!!) }))
            when {
                out.endsWith(".html") -> File(out).writeText(DeckWriter.html(deck, title, manifest, files, bg, args.options["fps"]?.toDoubleOrNull() ?: 30.0) { media.svg(manifest, it) })
                out.endsWith(".pdf") -> DeckWriter.pdf(deck, File(out), png)
                else -> {
                    @OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)
                    val fonts = files.mapValues { (name, f) -> SvgRenderer.Font("data:font/ttf;base64," + kotlin.io.encoding.Base64.encode(f.readBytes()), manifest.font(name)!!) }
                    DeckWriter.folder(deck, File(out), args.options["format"] ?: "svg", media.svg(manifest, SvgRenderer.Options(background = bg, fonts = fonts)), png)
                }
            }
            System.err.println("diagrams: ${deck.scenes.size} slides written to $out")
            0
        }
        "video" -> video()
        "serve" -> {
            val port = args.options["port"]?.toIntOrNull() ?: 8737
            ViewerServer(file, port).start()
            System.err.println("diagrams: viewing ${file.path} at http://127.0.0.1:$port/ (Ctrl-C to stop)")
            Thread.currentThread().join()
            0
        }
        "lint" -> {
            val ds = mutableListOf<Diagnostic>()
            val r = resolveOrReport(ds)
            if (r != null) ds += Lint.run(r.scene)
            printDiagnostics(ds, toStdout = true)
            status(ds)
        }
        "resolve", "dump", "render" -> {
            val ds = mutableListOf<Diagnostic>()
            val r = resolveOrReport(ds)
            printDiagnostics(ds, toStdout = false)
            if (r == null) {
                1
            } else {
                when (command) {
                    "resolve" -> output(JsonWriter.write(r.scene.toValue()))
                    "dump" -> output(r.scene.dump())
                    else -> render(r)
                }
                0
            }
        }
        else -> fail("unknown command '$command'\n$USAGE")
    }

    fun video(): Int {
        val out = args.options["out"] ?: fail("video needs --out FILE.mp4, FILE.gif or a directory")
        val pr = process()
        val ds = pr.diagnostics.toMutableList()
        if (pr.hasErrors || pr.canonical == null) {
            printDiagnostics(ds, toStdout = false)
            return 1
        }
        val manifest = manifest(pr, ds)
        val opts = ResolveOptions(manifest = manifest, states = states("state"))
        val owner = args.options["walk"]
        val walk = owner?.let { o ->
            val c = Resolver.resolve(pr, opts).containers.firstOrNull { it.owner == o } ?: fail("no state container is owned by '$o'")
            c.container.states.map { it.id }
        }
        val playback = Playback(pr, opts, walk, owner ?: "scene", args.options["hold"]?.toDoubleOrNull() ?: 2.0, args.options["duration"]?.toDoubleOrNull())
        val files = assets.fontFiles(manifest)
        val png = media.raster(manifest, RasterRenderer.Options(args.options["scale"]?.toDoubleOrNull() ?: 1.0, args.options["background"]?.takeIf { it != "none" }, files.mapValues { (name, f) -> RasterRenderer.FontFace(f, manifest.font(name)!!) }))
        val fps = args.options["fps"]?.toDoubleOrNull() ?: 30.0
        try {
            VideoWriter.write(playback, File(out), fps, png, VideoWriter.findFfmpeg(args.options["ffmpeg"]), audioTracks(pr, opts))
        } catch (e: VideoException) {
            fail(e.message ?: "video failed")
        }
        ds += playback.diagnostics
        printDiagnostics(ds.distinct(), toStdout = false)
        System.err.println("diagrams: ${diagrams.model.formatNumber(playback.duration)} s at ${diagrams.model.formatNumber(fps)} fps written to $out")
        return status(ds)
    }

    /** Audio nodes of the scene, for the video's sound track (milestone 7). */
    /** Audio nodes of the scene, for the video's sound track (spec section 15). */
    fun audioTracks(pr: ProcessResult, opts: ResolveOptions): List<AudioTrack> {
        val scene = Resolver.resolve(pr, opts).scene ?: return emptyList()
        val files = assets.mediaFiles(opts.manifest, "audio")
        return scene.media.filter { it.kind == "audio" }.mapNotNull { c ->
            val f = files[c.asset] ?: return@mapNotNull null
            AudioTrack(f, c.start, c.from, c.to, c.volume, c.rate)
        }
    }

    val media by lazy { MediaFiles(assets, VideoWriter.findFfmpeg(args.options["ffmpeg"])) }

    @OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)
    fun render(r: Resolved) {
        val out = args.options["out"] ?: fail("render needs --out FILE.svg or FILE.png")
        val scale = args.options["scale"]?.toDoubleOrNull() ?: 1.0
        val bgOpt = args.options["background"]
        val bg = if (bgOpt == "none") null else bgOpt ?: r.scene.background ?: "#ffffff"
        val files = assets.fontFiles(r.manifest)
        when {
            out.endsWith(".svg") -> {
                // fonts are embedded, so the SVG renders the same anywhere
                val fonts = files.mapValues { (name, f) ->
                    SvgRenderer.Font("data:font/ttf;base64," + kotlin.io.encoding.Base64.encode(f.readBytes()), r.manifest.font(name)!!)
                }
                File(out).writeText(SvgRenderer.render(r.scene, media.svg(r.manifest, SvgRenderer.Options(background = bg, scale = scale, fonts = fonts))))
            }
            out.endsWith(".png") -> {
                val fonts = files.mapValues { (name, f) -> RasterRenderer.FontFace(f, r.manifest.font(name)!!) }
                RasterRenderer.writePng(r.scene, File(out), media.raster(r.manifest, RasterRenderer.Options(scale, bg, fonts)))
            }
            else -> fail("--out must end in .svg or .png")
        }
    }
}

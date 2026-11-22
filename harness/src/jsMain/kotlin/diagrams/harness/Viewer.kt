@file:OptIn(ExperimentalJsExport::class)

package diagrams.harness

import diagrams.assets.AssetManifest
import diagrams.model.JsonWriter
import diagrams.model.Value
import diagrams.model.Yaml
import diagrams.model.toValue
import diagrams.processor.Printer

/**
 * The harness API for the browser (spec sections 16 and 17). Values cross as JSON strings.
 *
 * The bundle is `{"main": "doc.yaml", "files": {"doc.yaml": "...", "../libs/deck.yaml": "..."},
 * "manifest": "<asset manifest YAML>", "assets": {"body": "/asset/fonts/Inter.ttf"}}`: every file
 * the document may import, keyed by path relative to the document's directory, and a URL for each
 * asset the renderer references.
 */
@JsExport
class Viewer(bundle: String) {
    private var h: Harness = create(bundle)

    private fun create(bundle: String): Harness {
        val (sources, manifest, assets) = parseBundle(bundle)
        return Harness(sources, manifest, assets)
    }

    private fun parseBundle(bundle: String): Triple<Sources, AssetManifest, Map<String, String>> {
        val b = Json.parse(bundle) as Value.Map
        val files = (b["files"] as Value.Map).entries.mapValues { (it.value as Value.Str).value }
        val manifest = (b["manifest"] as? Value.Str)?.value?.takeIf { it.isNotBlank() }?.let { AssetManifest.fromValue(Yaml.parse(it, "manifest.yaml").value) } ?: AssetManifest.EMPTY
        val assets = (b["assets"] as? Value.Map)?.entries.orEmpty().mapValues { (it.value as Value.Str).value }
        return Triple(Sources((b["main"] as Value.Str).value, files), manifest, assets)
    }

    /** Reads every file again; states and time are kept. */
    fun reload(bundle: String) {
        val (sources, manifest, _) = parseBundle(bundle)
        h.reload(sources, manifest)
    }

    fun svg(): String? = h.svg()

    /** `[{kind, asset, url, start, in, out, volume, rate}]`: the sound to play with the frames. */
    fun media(): String = JsonWriter.write(
        Value.List(
            h.media().map { c ->
                Value.Map(
                    linkedMapOf<String, Value>(
                        "kind" to Value.Str(c.kind),
                        "asset" to Value.Str(c.asset),
                        "url" to (h.assetUrl(c.asset)?.let { Value.Str(it) } ?: Value.Null),
                        "start" to Value.Num(c.start),
                        "in" to Value.Num(c.from),
                        "out" to (c.to?.let { Value.Num(it) } ?: Value.Null),
                        "volume" to Value.Num(c.volume),
                        "rate" to Value.Num(c.rate),
                    ),
                )
            },
        ),
    )

    fun diagnostics(): String = JsonWriter.write(h.diagnostics().toValue())

    /** `[{owner, kind, states, current, parents, edges}]` */
    fun containers(): String = JsonWriter.write(
        Value.List(
            h.containers().map { c ->
                Value.map(
                    "owner" to Value.Str(c.owner),
                    "kind" to Value.Str(c.kind),
                    "states" to Value.List(c.states.map { Value.Str(it) }),
                    "current" to (c.current?.let { Value.Str(it) } ?: Value.Null),
                    "parents" to Value.Map(c.parents.mapValuesTo(LinkedHashMap()) { (_, p) -> p?.let { Value.Str(it) } ?: Value.Null }),
                    "edges" to Value.Map(c.edges.mapValuesTo(LinkedHashMap()) { (_, e) -> Value.Map(e.mapValuesTo(LinkedHashMap()) { Value.Str(it.value) }) }),
                )
            },
        ),
    )

    /** Any resolved value by path, as JSON; null when the path names nothing. */
    fun read(path: String): String? = h.read(path)?.let { JsonWriter.write(it).trimEnd() }

    /** The path under a viewport point. */
    fun hit(x: Double, y: Double): String? = h.hit(x, y)

    /** A click: hit-tested, then sent to pointer listeners. */
    fun click(x: Double, y: Double): String? = h.pointer("click", x, y)

    /** Applies a JSON list of patches; returns the diagnostics as JSON, or `{"error": message}`. */
    fun patch(json: String): String = guard { JsonWriter.write(h.patch(patchesFromJson(json)).toValue()) }

    /** The canonical document in authored form (YAML), for saving what was patched. */
    fun document(): String? = h.document?.let { Printer.print(it) }

    fun goTo(owner: String, state: String): String? = error { h.goto(owner, state) }
    fun next(owner: String): String? = error { h.next(owner) }
    fun prev(owner: String): String? = error { h.prev(owner) }
    fun enter(owner: String, child: String?): String? = error { h.enter(owner, child) }
    fun up(owner: String): String? = error { h.up(owner) }
    fun sibling(owner: String, delta: Int): String? = error { h.sibling(owner, delta) }
    fun follow(owner: String, edge: String): String? = error { h.follow(owner, edge) }

    fun play() = h.play()
    fun pause() = h.pause()
    fun seek(t: Double) = h.seek(t)
    fun tick(dt: Double): Boolean = h.tick(dt)
    fun time(): Double = h.time
    fun duration(): Double = h.duration()
    fun playing(): Boolean = h.playing
    fun transitioning(): Boolean = h.transitioning

    private inline fun error(f: () -> Unit): String? = try {
        f()
        null
    } catch (e: Exception) {
        e.message ?: "failed"
    }

    private inline fun guard(f: () -> String): String = try {
        f()
    } catch (e: Exception) {
        JsonWriter.write(Value.map("error" to Value.Str(e.message ?: "failed")))
    }
}

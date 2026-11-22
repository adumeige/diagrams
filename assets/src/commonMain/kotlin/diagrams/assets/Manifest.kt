package diagrams.assets

import diagrams.model.Value

/** One asset of the manifest. [file] is relative to the directory of the document that was probed. */
class AssetEntry(val kind: String, val file: String, val font: FontMetrics? = null, val media: MediaFacts? = null)

/**
 * Facts about an image, audio or video asset (spec section 15): intrinsic size in pixels, duration in
 * seconds, and named cue points in seconds from the start of the file.
 */
data class MediaFacts(val width: Double? = null, val height: Double? = null, val duration: Double? = null, val cues: Map<String, Double> = emptyMap())

class ManifestException(message: String) : Exception(message)

/**
 * The asset manifest (spec section 15): declared facts about each asset, written by `probe`,
 * read by the resolver, which decodes nothing itself. Milestone 1 records fonts only.
 *
 * ```yaml
 * assets:
 *   body:
 *     kind: font
 *     file: fonts/Inter-Regular.ttf
 *     metrics:
 *       units_per_em: 2048
 *       ascent: 1984
 *       descent: -494
 *       line_gap: 0
 *       default_advance: 1229
 *       advances:
 *         - {from: 32, widths: "512 573 ..."}   # one width per consecutive code point
 * ```
 */
class AssetManifest(val assets: Map<String, AssetEntry>) {
    fun font(name: String): FontMetrics? = assets[name]?.font

    fun media(name: String): MediaFacts? = assets[name]?.media

    fun toValue(): Value = Value.map(
        "assets" to Value.Map(assets.mapValuesTo(LinkedHashMap()) { (_, a) -> entry(a) }),
    )

    private fun entry(a: AssetEntry): Value {
        val out = linkedMapOf<String, Value>("kind" to Value.Str(a.kind), "file" to Value.Str(a.file))
        a.font?.let { out["metrics"] = metrics(it) }
        a.media?.let { m ->
            m.width?.let { out["width"] = Value.Num(it) }
            m.height?.let { out["height"] = Value.Num(it) }
            m.duration?.let { out["duration"] = Value.Num(it) }
            if (m.cues.isNotEmpty()) out["cues"] = Value.Map(m.cues.mapValuesTo(LinkedHashMap()) { Value.Num(it.value) })
        }
        return Value.Map(out)
    }

    private fun metrics(m: FontMetrics): Value = Value.map(
        "units_per_em" to Value.num(m.unitsPerEm),
        "ascent" to Value.num(m.ascent),
        "descent" to Value.num(m.descent),
        "line_gap" to Value.num(m.lineGap),
        "default_advance" to Value.num(m.defaultAdvance),
        "advances" to Value.List(runs(m.advances).map { (from, ws) -> Value.map("from" to Value.num(from), "widths" to Value.Str(ws.joinToString(" "))) }),
    )

    companion object {
        val EMPTY = AssetManifest(emptyMap())

        /** Consecutive code points grouped into runs, so the manifest stays compact and readable. */
        private fun runs(advances: Map<Int, Int>): List<Pair<Int, List<Int>>> {
            val out = mutableListOf<Pair<Int, MutableList<Int>>>()
            var last = -2
            for ((cp, w) in advances.entries.sortedBy { it.key }) {
                if (cp == last + 1 && out.isNotEmpty()) out.last().second += w else out += cp to mutableListOf(w)
                last = cp
            }
            return out
        }

        fun fromValue(v: Value): AssetManifest {
            val root = v as? Value.Map ?: throw ManifestException("a manifest is a map with 'assets'")
            val assets = root["assets"] as? Value.Map ?: return EMPTY
            return AssetManifest(
                assets.entries.mapValuesTo(LinkedHashMap()) { (name, e) ->
                    val m = e as? Value.Map ?: throw ManifestException("asset '$name' is a map")
                    val kind = (m["kind"] as? Value.Str)?.value ?: throw ManifestException("asset '$name' has no 'kind'")
                    val file = (m["file"] as? Value.Str)?.value ?: throw ManifestException("asset '$name' has no 'file'")
                    val metrics = m["metrics"]?.let { fontMetrics(name, it) }
                    if (kind == "font" && metrics == null) throw ManifestException("font asset '$name' has no 'metrics'; run probe")
                    fun num(k: String) = (m[k] as? Value.Num)?.value
                    val cues = (m["cues"] as? Value.Map)?.entries.orEmpty().mapValues { (c, v) -> (v as? Value.Num)?.value ?: throw ManifestException("cue '$c' of '$name' is not a number of seconds") }
                    val media = if (kind == "font") null else MediaFacts(num("width"), num("height"), num("duration"), cues)
                    AssetEntry(kind, file, metrics, media)
                },
            )
        }

        private fun fontMetrics(name: String, v: Value): FontMetrics {
            val m = v as? Value.Map ?: throw ManifestException("'$name.metrics' is a map")
            fun int(k: String, default: Int? = null): Int =
                (m[k] as? Value.Num)?.value?.toInt() ?: default ?: throw ManifestException("'$name.metrics.$k' is missing")
            val advances = LinkedHashMap<Int, Int>()
            (m["advances"] as? Value.List)?.items.orEmpty().forEach { run ->
                val r = run as? Value.Map ?: throw ManifestException("'$name.metrics.advances' holds {from, widths} runs")
                val from = (r["from"] as? Value.Num)?.value?.toInt() ?: throw ManifestException("an advance run of '$name' has no 'from'")
                val widths = (r["widths"] as? Value.Str)?.value ?: throw ManifestException("an advance run of '$name' has no 'widths'")
                widths.split(' ').filter { it.isNotEmpty() }.forEachIndexed { i, w ->
                    advances[from + i] = w.toIntOrNull() ?: throw ManifestException("'$w' in the advances of '$name' is not an integer")
                }
            }
            val upem = int("units_per_em")
            if (upem <= 0) throw ManifestException("'$name.metrics.units_per_em' must be positive")
            return FontMetrics(upem, int("ascent"), int("descent"), int("line_gap", 0), int("default_advance", upem / 2), advances)
        }
    }
}

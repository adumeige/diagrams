package diagrams.resolver

import diagrams.assets.AssetEntry
import diagrams.assets.AssetManifest
import diagrams.assets.FontFormatException
import diagrams.assets.MediaFacts
import diagrams.assets.MediaFormatException
import diagrams.assets.MediaProbe
import diagrams.assets.OpenType
import diagrams.model.Codes
import diagrams.model.Diagnostic
import diagrams.model.Severity
import diagrams.model.Value
import diagrams.processor.Environment
import diagrams.processor.Module

class ProbeResult(val manifest: AssetManifest, val diagnostics: List<Diagnostic>)

/**
 * The probe step (spec section 15): reads every asset the document's imports declare and writes
 * their facts into the manifest: font metrics, and the size, duration and cue points of media.
 */
object Probe {
    /** A read asset: its bytes and the path the manifest records for it (relative to the document). */
    class Read(val path: String, val bytes: ByteArray)

    /** [read] gets the declaring module and the file as written there; null when it cannot be read. */
    fun probe(env: Environment, read: (Module, String) -> Read?): ProbeResult {
        val out = LinkedHashMap<String, AssetEntry>()
        val diags = mutableListOf<Diagnostic>()
        for ((name, decl) in env.fontAssets()) {
            val (module, asset) = decl
            val file = (asset["file"] as Value.Str).value
            val path = "${module.alias}.assets.$name"
            val r = read(module, file)
            if (r == null) {
                diags += Diagnostic(Codes.ASSET_MISSING, Severity.ERROR, path, "cannot read font file '$file' (declared in ${module.file})")
                continue
            }
            try {
                out[name] = AssetEntry("font", r.path, OpenType.read(r.bytes))
            } catch (e: FontFormatException) {
                diags += Diagnostic(Codes.SCHEMA, Severity.ERROR, path, "font file '$file': ${e.message}")
            }
        }
        for ((name, decl) in env.assets("image", "audio", "video")) {
            val (module, asset) = decl
            val kind = (asset["kind"] as Value.Str).value
            val file = (asset["file"] as Value.Str).value
            val path = if (module.alias == "document") "assets.$name" else "${module.alias}.assets.$name"
            val r = read(module, file)
            if (r == null) {
                diags += Diagnostic(Codes.ASSET_MISSING, Severity.ERROR, path, "cannot read $kind file '$file' (declared in ${module.file.ifEmpty { "the document" }})")
                continue
            }
            var facts = try {
                MediaProbe.read(kind, file, r.bytes)
            } catch (e: MediaFormatException) {
                diags += Diagnostic(Codes.SCHEMA, Severity.ERROR, path, "$kind file '$file': ${e.message}")
                continue
            }
            // facts a harness or a person wrote next to the file (`<file>.meta.yaml`) fill in or replace the probed ones
            read(module, "$file.meta.yaml")?.let { side ->
                try {
                    val m = diagrams.model.Yaml.parse(side.bytes.decodeToString(), side.path).value as? Value.Map
                    fun num(k: String) = (m?.get(k) as? Value.Num)?.value
                    val cues = (m?.get("cues") as? Value.Map)?.entries.orEmpty().mapNotNull { (k, v) -> (v as? Value.Num)?.let { k to it.value } }.toMap()
                    facts = MediaFacts(num("width") ?: facts.width, num("height") ?: facts.height, num("duration") ?: facts.duration, facts.cues + cues)
                } catch (e: diagrams.model.YamlException) {
                    diags += Diagnostic(Codes.SCHEMA, Severity.ERROR, path, "'$file.meta.yaml': ${e.message}")
                }
            }
            if ((kind == "audio" || kind == "video") && facts.duration == null) {
                diags += Diagnostic(Codes.ASSET_MISSING, Severity.WARNING, path, "the duration of '$file' is unknown (WAV, MP4 and MOV are read); write it in '$file.meta.yaml' as 'duration: <seconds>'")
            }
            out[name] = AssetEntry(kind, r.path, media = facts)
        }
        return ProbeResult(AssetManifest(out), diags)
    }
}

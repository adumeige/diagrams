package diagrams.resolver

import diagrams.assets.AssetManifest
import diagrams.processor.Environment
import java.io.File

/**
 * Asset files on the JVM. Manifest paths are relative to [documentDir]; relative module files
 * (as [diagrams.processor.FileImportLoader] names them) are relative to [baseDir].
 */
class FileAssets(val documentDir: File, val baseDir: File = File(".")) {
    /** Probes the fonts of [env]; manifest paths are relative to [documentDir]. */
    fun probe(env: Environment): ProbeResult = Probe.probe(env) { module, file ->
        val moduleDir = File(module.file).parentFile
        val asWritten = if (File(file).isAbsolute) File(file) else File(moduleDir, file)
        val f = if (asWritten.isAbsolute) asWritten else File(baseDir, asWritten.path)
        if (!f.isFile) {
            null
        } else {
            val rel = runCatching { f.canonicalFile.relativeTo(documentDir.canonicalFile).invariantSeparatorsPath }.getOrNull()
            Probe.Read(rel ?: f.absolutePath, f.readBytes())
        }
    }

    /** The media files of a manifest of the given kind, by asset name, for renderers and exporters. */
    fun mediaFiles(manifest: AssetManifest, kind: String): Map<String, File> =
        manifest.assets.filterValues { it.kind == kind }.mapValues { (_, a) -> File(a.file).let { if (it.isAbsolute) it else File(documentDir, a.file) } }

    /** The font files of a manifest, by asset name, for renderers. */
    fun fontFiles(manifest: AssetManifest): Map<String, File> =
        manifest.assets.filterValues { it.kind == "font" }.mapValues { (_, a) -> File(a.file).let { if (it.isAbsolute) it else File(documentDir, a.file) } }
}

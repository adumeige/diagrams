package diagrams.processor

import java.io.File

/**
 * Reads imports from the file system. A relative import is relative to the importing file's
 * directory; a relative importing file is itself relative to [baseDir].
 * The returned name, used in diagnostics, keeps the importing file's form.
 */
class FileImportLoader(private val baseDir: File = File(".")) : ImportLoader {
    override fun load(path: String, from: String?): LoadedFile? {
        val name = if (File(path).isAbsolute) File(path) else File(from?.let { File(it).parentFile }, path)
        val f = if (name.isAbsolute) name else File(baseDir, name.path)
        if (!f.isFile) return null
        return LoadedFile(f.readText(), name.normalize().path)
    }
}

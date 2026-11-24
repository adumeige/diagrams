package diagrams.vaadin

import diagrams.harness.Sources
import java.io.File
import java.io.Serializable
import java.nio.file.Path

/**
 * Where a document and the files it imports are read from. Paths are relative to a root, use `/`,
 * and have been normalized: they never start with `/` nor contain `.` or `..` segments. A source
 * returns null for a file it does not have, and never reads outside its root, so a document cannot
 * import files the application did not mean to serve, whatever its `imports` say.
 */
fun interface DiagramFiles : Serializable {
    fun read(path: String): ByteArray?

    companion object {
        /** The files under [root], on the file system. */
        @JvmStatic
        fun directory(root: Path): DiagramFiles = DirectoryFiles(root.toAbsolutePath().normalize().toString())

        /** The files under [root], on the file system. */
        @JvmStatic
        fun directory(root: File): DiagramFiles = directory(root.toPath())

        /** The class path resources under [root] (`diagrams`, `com/acme/docs`), read with [loader]. */
        @JvmStatic
        @JvmOverloads
        fun classpath(root: String, loader: ClassLoader = DiagramFiles::class.java.classLoader): DiagramFiles =
            ClasspathFiles(root.trim('/'), loader)

        /** Files held in memory, keyed by path. */
        @JvmStatic
        fun of(files: Map<String, String>): DiagramFiles {
            val bytes = HashMap(files.entries.associate { (k, v) -> Sources.normalize(k) to v.encodeToByteArray() })
            return MemoryFiles(bytes)
        }

        /** Normalizes a path relative to a root; null when it climbs out of the root. */
        @JvmStatic
        fun normalize(path: String): String? = Sources.normalize(path).takeUnless { it == ".." || it.startsWith("../") || it.isEmpty() }
    }
}

/** [overrides] first, then this: an edited document over the files it imports, for instance. */
fun DiagramFiles.overlay(overrides: Map<String, String>): DiagramFiles = OverlayFiles(DiagramFiles.of(overrides), this)

private class OverlayFiles(private val first: DiagramFiles, private val then: DiagramFiles) : DiagramFiles {
    override fun read(path: String): ByteArray? = first.read(path) ?: then.read(path)
}

private class DirectoryFiles(private val root: String) : DiagramFiles {
    override fun read(path: String): ByteArray? {
        val rel = DiagramFiles.normalize(path) ?: return null
        val base = File(root)
        val f = File(base, rel)
        // The canonical path guards against links that lead out of the root.
        val inside = runCatching { f.canonicalFile.toPath().startsWith(base.canonicalFile.toPath()) }.getOrDefault(false)
        return if (inside && f.isFile) f.readBytes() else null
    }
}

private class ClasspathFiles(private val root: String, @Transient private val loader: ClassLoader?) : DiagramFiles {
    override fun read(path: String): ByteArray? {
        val rel = DiagramFiles.normalize(path) ?: return null
        val name = if (root.isEmpty()) rel else "$root/$rel"
        val cl = loader ?: DiagramFiles::class.java.classLoader
        return cl.getResourceAsStream(name)?.use { it.readBytes() }
    }
}

private class MemoryFiles(private val files: HashMap<String, ByteArray>) : DiagramFiles {
    override fun read(path: String): ByteArray? = DiagramFiles.normalize(path)?.let { files[it] }
}

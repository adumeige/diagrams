package diagrams.cli

import diagrams.assets.AssetManifest
import diagrams.render.raster.RasterRenderer
import diagrams.render.svg.SvgRenderer
import diagrams.resolver.FileAssets
import java.awt.image.BufferedImage
import java.io.File
import java.util.Base64
import javax.imageio.ImageIO
import kotlin.math.roundToLong

/**
 * Media for the renderers on the JVM (spec section 15: only renderers decode): image files, and
 * video frames decoded by ffmpeg when it is installed. Without ffmpeg a video draws a placeholder.
 */
class MediaFiles(private val assets: FileAssets, private val ffmpeg: String?) {
    private val frames = HashMap<Pair<String, Long>, BufferedImage?>()

    fun raster(manifest: AssetManifest, o: RasterRenderer.Options): RasterRenderer.Options {
        val videos = assets.mediaFiles(manifest, "video")
        return o.copy(images = assets.mediaFiles(manifest, "image"), videoFrame = if (ffmpeg == null || videos.isEmpty()) null else { a, t -> frame(videos[a], t) })
    }

    /** Images (and video frames) embedded as data URIs, so the SVG stands alone. */
    fun svg(manifest: AssetManifest, o: SvgRenderer.Options): SvgRenderer.Options {
        val images = assets.mediaFiles(manifest, "image").filterValues { it.isFile }.mapValues { (_, f) -> dataUri(f.readBytes(), mime(f.name)) }
        val videos = assets.mediaFiles(manifest, "video")
        return o.copy(
            media = images,
            videoFrame = if (ffmpeg == null || videos.isEmpty()) null else { a, t -> frame(videos[a], t)?.let { img -> dataUri(png(img), "image/png") } },
        )
    }

    private fun png(img: BufferedImage): ByteArray = java.io.ByteArrayOutputStream().also { ImageIO.write(img, "png", it) }.toByteArray()

    private fun dataUri(bytes: ByteArray, mime: String) = "data:$mime;base64," + Base64.getEncoder().encodeToString(bytes)

    private fun mime(name: String) = when (name.substringAfterLast('.').lowercase()) {
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "gif" -> "image/gif"
        "svg" -> "image/svg+xml"
        else -> "application/octet-stream"
    }

    /** One frame of a video file at [t] seconds, decoded by ffmpeg; frames are cached by millisecond. */
    @Synchronized
    private fun frame(file: File?, t: Double): BufferedImage? {
        if (file == null || ffmpeg == null || !file.isFile) return null
        val key = file.path to (t * 1000).roundToLong()
        return frames.getOrPut(key) {
            val p = ProcessBuilder(ffmpeg, "-loglevel", "error", "-ss", "%.3f".format(t), "-i", file.path, "-frames:v", "1", "-f", "image2pipe", "-vcodec", "png", "-").start()
            val bytes = p.inputStream.readBytes()
            p.waitFor()
            if (bytes.isEmpty()) null else ImageIO.read(bytes.inputStream())
        }
    }
}

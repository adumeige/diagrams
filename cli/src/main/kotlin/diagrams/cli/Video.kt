package diagrams.cli

import diagrams.model.Diagnostic
import diagrams.processor.ProcessResult
import diagrams.render.raster.RasterRenderer
import diagrams.resolver.ResolveOptions
import diagrams.resolver.ResolvedScene
import diagrams.resolver.Resolver
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageTypeSpecifier
import javax.imageio.metadata.IIOMetadataNode
import javax.imageio.stream.FileImageOutputStream

/**
 * What a video shows over time (spec section 13, "Determinism": a frame is a pure function of the
 * document, the manifest, the state history and the time).
 *
 * Without a walk, the scene clock runs from 0 for [duration] seconds. With a walk, the video holds
 * each state of a container for [hold] seconds (its state timeline playing), then plays the
 * transition to the next one; the scene clock keeps running throughout.
 */
class Playback(private val pr: ProcessResult, private val options: ResolveOptions, private val walk: List<String>?, private val owner: String, private val hold: Double, duration: Double?) {
    private sealed interface Segment {
        val start: Double
        val length: Double
    }

    private class Hold(override val start: Double, override val length: Double, val states: Map<String, String>) : Segment
    private class Move(override val start: Double, override val length: Double, val transition: diagrams.resolver.Transition) : Segment

    private val segments = mutableListOf<Segment>()
    val diagnostics = mutableListOf<Diagnostic>()
    val duration: Double

    init {
        if (walk == null) {
            val d = duration ?: Resolver.timelineDuration(pr, options).takeIf { it > 0 } ?: 5.0
            segments += Hold(0.0, d, options.states)
            this.duration = d
        } else {
            var t = 0.0
            walk.forEachIndexed { i, id ->
                val states = options.states + (owner to id)
                segments += Hold(t, hold, states)
                t += hold
                val next = walk.getOrNull(i + 1) ?: return@forEachIndexed
                val r = Resolver.transition(pr, states, options.states + (owner to next), options.copy(time = t))
                diagnostics += r.diagnostics
                val tr = r.transition ?: return@forEachIndexed
                segments += Move(t, tr.duration, tr)
                t += tr.duration
            }
            this.duration = duration ?: t
        }
    }

    /** The frame at [t] seconds. */
    fun frame(t: Double): ResolvedScene {
        val s = segments.lastOrNull { t >= it.start } ?: segments.first()
        return when (s) {
            is Hold -> {
                val r = Resolver.resolve(pr, options.copy(time = t, states = s.states, entered = s.states.mapValues { s.start }))
                diagnostics += r.diagnostics
                r.scene!!
            }
            is Move -> s.transition.frame(t - s.start)
        }
    }
}

/** Writes frames as an animated GIF, a folder of PNG files, or a video through ffmpeg. */
object VideoWriter {
    fun write(p: Playback, out: File, fps: Double, png: RasterRenderer.Options, ffmpeg: String?, audio: List<AudioTrack> = emptyList()) {
        val n = maxOf(1, (p.duration * fps).toInt())
        fun image(k: Int): BufferedImage {
            val scene = p.frame(k / fps)
            return RasterRenderer.render(scene, png.copy(background = png.background ?: scene.background ?: "#ffffff"))
        }
        val name = out.name.lowercase()
        when {
            name.endsWith(".gif") -> gif(n, fps, out, ::image)
            name.endsWith(".mp4") || name.endsWith(".mov") || name.endsWith(".webm") || name.endsWith(".mkv") -> {
                val bin = ffmpeg ?: throw VideoException("writing ${out.name} needs ffmpeg on the PATH (or --ffmpeg PATH); .gif and a folder of PNG files need nothing")
                encode(bin, n, fps, out, audio, ::image)
            }
            else -> {
                out.mkdirs()
                for (k in 0 until n) ImageIO.write(image(k), "png", File(out, "frame-%05d.png".format(k)))
            }
        }
    }

    /** ffmpeg, from the PATH or the usual install places. */
    fun findFfmpeg(given: String?): String? {
        if (given != null) return given
        val dirs = System.getenv("PATH").orEmpty().split(File.pathSeparator) + listOf("/opt/homebrew/bin", "/usr/local/bin", "/usr/bin")
        return dirs.map { File(it, "ffmpeg") }.firstOrNull { it.canExecute() }?.path
    }

    private fun gif(n: Int, fps: Double, out: File, image: (Int) -> BufferedImage) {
        val writer = ImageIO.getImageWritersByFormatName("gif").next()
        out.delete()
        FileImageOutputStream(out).use { stream ->
            writer.output = stream
            writer.prepareWriteSequence(null)
            val delay = maxOf(2, Math.round(100 / fps).toInt()) // hundredths of a second
            for (k in 0 until n) {
                val img = toRgb(image(k))
                val meta = writer.getDefaultImageMetadata(ImageTypeSpecifier.createFromRenderedImage(img), null)
                val format = meta.nativeMetadataFormatName
                val root = meta.getAsTree(format) as IIOMetadataNode
                fun child(name: String) = (0 until root.length).map { root.item(it) }.firstOrNull { it.nodeName == name } as? IIOMetadataNode
                    ?: IIOMetadataNode(name).also { root.appendChild(it) }
                child("GraphicControlExtension").apply {
                    setAttribute("disposalMethod", "none")
                    setAttribute("userInputFlag", "FALSE")
                    setAttribute("transparentColorFlag", "FALSE")
                    setAttribute("delayTime", delay.toString())
                    setAttribute("transparentColorIndex", "0")
                }
                if (k == 0) {
                    val app = IIOMetadataNode("ApplicationExtension").apply {
                        setAttribute("applicationID", "NETSCAPE")
                        setAttribute("authenticationCode", "2.0")
                        userObject = byteArrayOf(1, 0, 0) // loop forever
                    }
                    child("ApplicationExtensions").appendChild(app)
                }
                meta.setFromTree(format, root)
                writer.writeToSequence(IIOImage(img, null, meta), null)
            }
            writer.endWriteSequence()
        }
        writer.dispose()
    }

    private fun toRgb(img: BufferedImage): BufferedImage {
        if (img.type == BufferedImage.TYPE_INT_RGB) return img
        val out = BufferedImage(img.width, img.height, BufferedImage.TYPE_INT_RGB)
        val g = out.createGraphics()
        g.color = java.awt.Color.WHITE
        g.fillRect(0, 0, img.width, img.height)
        g.drawImage(img, 0, 0, null)
        g.dispose()
        return out
    }

    /** Frames piped to ffmpeg as PNG; audio tracks mixed in at their offsets. */
    private fun encode(bin: String, n: Int, fps: Double, out: File, audio: List<AudioTrack>, image: (Int) -> BufferedImage) {
        val cmd = mutableListOf(bin, "-y", "-loglevel", "error", "-f", "image2pipe", "-framerate", fps.toString(), "-c:v", "png", "-i", "-")
        for (a in audio) cmd += listOf("-i", a.file.path)
        val webm = out.name.lowercase().endsWith(".webm")
        val filters = mutableListOf("[0:v]pad=ceil(iw/2)*2:ceil(ih/2)*2[v]")
        if (audio.isNotEmpty()) {
            audio.forEachIndexed { i, a ->
                val chain = mutableListOf<String>()
                if (a.from > 0 || a.to != null) chain += "atrim=start=${a.from}" + (a.to?.let { ":end=$it" } ?: "")
                chain += "asetpts=PTS-STARTPTS"
                if (a.rate != 1.0) chain += "atempo=${a.rate.coerceIn(0.5, 2.0)}"
                if (a.volume != 1.0) chain += "volume=${a.volume}"
                chain += "adelay=${(a.start * 1000).toLong()}:all=1"
                filters += "[${i + 1}:a]${chain.joinToString(",")}[a$i]"
            }
            filters += audio.indices.joinToString("") { "[a$it]" } + "amix=inputs=${audio.size}:normalize=0[a]"
        }
        cmd += listOf("-filter_complex", filters.joinToString(";"), "-map", "[v]")
        if (audio.isNotEmpty()) cmd += listOf("-map", "[a]", "-t", (n / fps).toString())
        cmd += if (webm) listOf("-c:v", "libvpx-vp9", "-b:v", "0", "-crf", "32") else listOf("-c:v", "libx264", "-pix_fmt", "yuv420p", "-crf", "18", "-movflags", "+faststart")
        if (audio.isNotEmpty()) cmd += if (webm) listOf("-c:a", "libopus") else listOf("-c:a", "aac", "-b:a", "192k")
        cmd += out.path
        val proc = ProcessBuilder(cmd).redirectErrorStream(true).start()
        val log = StringBuilder()
        val reader = Thread { proc.inputStream.bufferedReader().forEachLine { log.append(it).append('\n') } }.apply { start() }
        try {
            proc.outputStream.use { os ->
                for (k in 0 until n) {
                    val buf = ByteArrayOutputStream()
                    ImageIO.write(image(k), "png", buf)
                    os.write(buf.toByteArray())
                }
            }
        } catch (e: java.io.IOException) {
            // ffmpeg stopped reading: its log says why
        }
        val code = proc.waitFor()
        reader.join()
        if (code != 0) throw VideoException("ffmpeg failed ($code): ${log.toString().trim()}")
    }
}

/** An audio asset placed on the video's clock (spec section 15): from its `in` to `out`, at [start]. */
class AudioTrack(val file: File, val start: Double, val from: Double, val to: Double?, val volume: Double, val rate: Double)

class VideoException(message: String) : Exception(message)

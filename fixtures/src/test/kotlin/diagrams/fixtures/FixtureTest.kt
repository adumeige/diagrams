package diagrams.fixtures

import diagrams.assets.AssetManifest
import diagrams.lint.Lint
import diagrams.model.Diagnostic
import diagrams.model.JsonWriter
import diagrams.model.Severity
import diagrams.model.Value
import diagrams.model.Yaml
import diagrams.model.YamlWriter
import diagrams.model.approxEquals
import diagrams.model.toValue
import diagrams.processor.FileImportLoader
import diagrams.processor.Printer
import diagrams.processor.Processor
import diagrams.render.raster.RasterRenderer
import diagrams.render.svg.SvgRenderer
import diagrams.resolver.FileAssets
import diagrams.resolver.ResolveOptions
import diagrams.resolver.ResolvedScene
import diagrams.resolver.Resolver
import diagrams.resolver.toValue
import org.junit.jupiter.api.DynamicContainer
import org.junit.jupiter.api.DynamicNode
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Runs every fixture directory under `fixtures/cases` (spec section 19).
 *
 * Each stage is compared against its expected file when that file exists:
 * `expected.canonical.yaml` (exact structural equality), `expected.diagnostics.yaml` (codes,
 * severities, paths), `expected.resolved.json` (numeric tolerance), `expected.png` (pixel tolerance).
 * Every fixture with a canonical document also runs the round-trip check.
 *
 * `DIAGRAMS_UPDATE=1 ./gradlew :fixtures:test` regenerates the expected files; review the diff before committing.
 */
class FixtureTest {
    private val root = File(System.getProperty("diagrams.fixtures") ?: "cases")
    private val update = System.getenv("DIAGRAMS_UPDATE").let { it == "1" || it == "true" }

    init {
        ImageIO.setUseCache(false)
    }

    companion object {
        const val RESOLVED_TOLERANCE = 1e-3
        const val PNG_MAX_DIFF_RATIO = 0.02
    }

    @TestFactory
    fun fixtures(): List<DynamicNode> {
        val dirs = root.listFiles { f -> f.isDirectory && File(f, "input.authored.yaml").isFile }?.sortedBy { it.name }.orEmpty()
        assertTrue(dirs.isNotEmpty(), "no fixtures found under $root")
        return dirs.map { dir -> DynamicContainer.dynamicContainer(dir.name, stages(dir)) }
    }

    /** The documents under `examples/` process, resolve in every state of the scene and lint clean. */
    @TestFactory
    fun examples(): List<DynamicNode> {
        val dir = File(root, "../../examples")
        val files = dir.listFiles { f -> f.name.endsWith(".yaml") }?.sortedBy { it.name }.orEmpty()
        return files.map { f ->
            DynamicTest.dynamicTest(f.name) {
                val pr = Processor.process(f.readText(), f.name, FileImportLoader(dir))
                assertTrue(!pr.hasErrors, pr.diagnostics.joinToString("\n"))
                val manifest = FileAssets(dir, dir).probe(pr.environment).manifest
                val first = Resolver.resolve(pr, ResolveOptions(manifest = manifest))
                val owner = first.containers.firstOrNull { it.owner == "scene" }
                val states = owner?.container?.states?.map { mapOf(owner.owner to it.id) } ?: listOf(emptyMap())
                for (s in states) {
                    val rr = Resolver.resolve(pr, ResolveOptions(manifest = manifest, states = s))
                    val findings = rr.diagnostics + (rr.scene?.let { Lint.run(it) } ?: emptyList())
                    assertTrue(findings.isEmpty(), "${f.name} in $s:\n${findings.joinToString("\n")}")
                }
            }
        }
    }

    private class Run(
        val canonical: Value?,
        val diagnostics: List<Diagnostic>,
        val scene: ResolvedScene?,
        val manifest: AssetManifest,
        /** The manifest probed now, when the document declares fonts. */
        val probed: AssetManifest?,
    )

    private fun run(dir: File): Run {
        val input = File(dir, "input.authored.yaml")
        val pr = Processor.process(input.readText(), input.name, FileImportLoader(dir))
        val diags = pr.diagnostics.toMutableList()
        var scene: ResolvedScene? = null
        val probe = if (pr.environment.fontAssets().isEmpty()) null else FileAssets(dir, dir).probe(pr.environment)
        probe?.let { diags += it.diagnostics }
        val manifestFile = File(dir, "manifest.yaml")
        if (update) writeOrDelete(manifestFile, probe?.manifest?.let { YamlWriter.write(it.toValue()) })
        val manifest = if (manifestFile.isFile) AssetManifest.fromValue(Yaml.parse(manifestFile.readText(), manifestFile.name).value) else AssetManifest.EMPTY
        if (!pr.hasErrors && pr.canonical != null) {
            val rr = Resolver.resolve(pr, ResolveOptions(manifest = manifest))
            diags += rr.diagnostics
            if (rr.diagnostics.none { it.severity == Severity.ERROR }) {
                scene = rr.scene
                diags += Lint.run(scene!!)
            }
        }
        return Run(pr.canonical, diags, scene, manifest, probe?.manifest)
    }

    private fun stages(dir: File): List<DynamicNode> {
        val r = run(dir)
        val fontFiles = FileAssets(dir, dir).fontFiles(r.manifest)
        val pngOptions = RasterRenderer.Options(background = r.scene?.background ?: "#ffffff", fonts = fontFiles.mapValues { (n, f) -> RasterRenderer.FontFace(f, r.manifest.font(n)!!) })
        // the reviewer's SVG links the font file instead of embedding it
        val svgFonts = r.manifest.assets.filterValues { it.font != null }.mapValues { (_, a) -> SvgRenderer.Font(a.file, a.font!!) }
        val canonFile = File(dir, "expected.canonical.yaml")
        val diagFile = File(dir, "expected.diagnostics.yaml")
        val resolvedFile = File(dir, "expected.resolved.json")
        val pngFile = File(dir, "expected.png")
        val svgFile = File(dir, "expected.svg")

        if (update) {
            writeOrDelete(canonFile, r.canonical?.let { YamlWriter.write(it) })
            diagFile.writeText(if (r.diagnostics.isEmpty()) "[]\n" else YamlWriter.write(r.diagnostics.toValue()))
            writeOrDelete(resolvedFile, r.scene?.let { JsonWriter.write(it.toValue()) })
            // SVG is written for people reviewing the fixture; it is not compared.
            writeOrDelete(svgFile, r.scene?.let { SvgRenderer.render(it, SvgRenderer.Options(background = it.background ?: "#ffffff", fonts = svgFonts)) })
            if (r.scene != null) RasterRenderer.writePng(r.scene, pngFile, pngOptions) else pngFile.delete()
        }

        val tests = mutableListOf<DynamicTest>()
        if (canonFile.isFile) {
            tests += DynamicTest.dynamicTest("canonical") {
                val expected = Yaml.parse(canonFile.readText(), canonFile.name).value
                assertNotNull(r.canonical, "the processor produced no canonical document: ${r.diagnostics}")
                assertEquals(YamlWriter.write(expected), YamlWriter.write(r.canonical))
            }
        }
        if (r.canonical != null) {
            tests += DynamicTest.dynamicTest("round-trip") {
                val printed = Printer.print(r.canonical)
                val again = Processor.process(printed, "input.authored.yaml", FileImportLoader(dir))
                assertEquals(YamlWriter.write(r.canonical), again.canonical?.let { YamlWriter.write(it) }, "printed form:\n$printed")
            }
        }
        if (r.probed != null) {
            tests += DynamicTest.dynamicTest("manifest") {
                assertEquals(YamlWriter.write(r.probed.toValue()), YamlWriter.write(r.manifest.toValue()), "manifest.yaml differs from a fresh probe")
            }
        }
        if (diagFile.isFile) {
            tests += DynamicTest.dynamicTest("diagnostics") {
                val expected = (Yaml.parse(diagFile.readText()).value as? Value.List)?.items.orEmpty().map { d ->
                    val m = d as Value.Map
                    Triple((m["code"] as Value.Str).value, (m["severity"] as Value.Str).value, (m["path"] as? Value.Str)?.value ?: "")
                }
                val actual = r.diagnostics.map { Triple(it.code, it.severity.label, it.path) }
                assertEquals(expected.sortedWith(compareBy({ it.first }, { it.third })), actual.sortedWith(compareBy({ it.first }, { it.third })), "diagnostics:\n${r.diagnostics.joinToString("\n")}")
            }
        }
        if (resolvedFile.isFile) {
            tests += DynamicTest.dynamicTest("resolved") {
                val scene = r.scene ?: fail("no resolved scene: ${r.diagnostics}")
                val expected = Yaml.parse(resolvedFile.readText(), resolvedFile.name).value
                val actual = scene.toValue()
                assertTrue(
                    expected.approxEquals(actual, RESOLVED_TOLERANCE),
                    "resolved scene differs from ${resolvedFile.name}:\n${JsonWriter.write(actual)}",
                )
            }
        }
        if (pngFile.isFile) {
            tests += DynamicTest.dynamicTest("png") {
                val scene = r.scene ?: fail("no resolved scene: ${r.diagnostics}")
                val expected = ImageIO.read(pngFile)
                val actual = RasterRenderer.render(scene, pngOptions)
                val ratio = RasterRenderer.diffRatio(expected, actual)
                if (ratio > PNG_MAX_DIFF_RATIO) {
                    val out = File(dir, "actual.png")
                    ImageIO.write(actual, "png", out)
                    fail("rendered image differs on ${"%.2f".format(ratio * 100)}% of pixels; see ${out.path}")
                }
            }
        }
        tests += frameTests(dir, r, pngOptions)
        if (tests.isEmpty()) tests += DynamicTest.dynamicTest("has expectations") { fail("fixture ${dir.name} has no expected files; run with DIAGRAMS_UPDATE=1") }
        return tests
    }

    /**
     * Animation fixtures (spec section 19): `frames.yaml` lists entries `{name, states, from, time}`;
     * each has its own `expected.<name>.resolved.json` and `expected.<name>.png`. `states` and `from`
     * map container owners to states; with `from`, the frame is `time` seconds into the transition.
     */
    private fun frameTests(dir: File, r: Run, png: RasterRenderer.Options): List<DynamicTest> {
        val framesFile = File(dir, "frames.yaml")
        if (!framesFile.isFile || r.canonical == null) return emptyList()
        val input = File(dir, "input.authored.yaml")
        val pr = Processor.process(input.readText(), input.name, FileImportLoader(dir))
        val entries = (Yaml.parse(framesFile.readText(), framesFile.name).value as Value.List).items.map { it as Value.Map }
        fun states(v: Value?) = (v as? Value.Map)?.entries.orEmpty().mapValues { (it.value as Value.Str).value }
        return entries.flatMap { e ->
            val name = (e["name"] as Value.Str).value
            val opts = ResolveOptions(manifest = r.manifest)
            val time = (e["time"] as? Value.Num)?.value ?: 0.0
            val rr = if (e["from"] != null) {
                Resolver.resolveTransition(pr, states(e["from"]), states(e["states"]), time, opts)
            } else {
                Resolver.resolve(pr, opts.copy(states = states(e["states"]), time = time))
            }
            val scene = rr.scene
            val resolvedFile = File(dir, "expected.$name.resolved.json")
            val pngFile = File(dir, "expected.$name.png")
            if (update && scene != null) {
                resolvedFile.writeText(JsonWriter.write(scene.toValue()))
                RasterRenderer.writePng(scene, pngFile, png.copy(background = scene.background ?: "#ffffff"))
            }
            listOf(
                DynamicTest.dynamicTest("frame $name: resolved") {
                    val s = scene ?: fail("no resolved scene: ${rr.diagnostics}")
                    assertTrue(rr.diagnostics.none { it.severity == Severity.ERROR }, "errors: ${rr.diagnostics}")
                    val expected = Yaml.parse(resolvedFile.readText(), resolvedFile.name).value
                    assertTrue(expected.approxEquals(s.toValue(), RESOLVED_TOLERANCE), "frame $name differs from ${resolvedFile.name}:\n${JsonWriter.write(s.toValue())}")
                },
                DynamicTest.dynamicTest("frame $name: png") {
                    val s = scene ?: fail("no resolved scene: ${rr.diagnostics}")
                    val ratio = RasterRenderer.diffRatio(ImageIO.read(pngFile), RasterRenderer.render(s, png.copy(background = s.background ?: "#ffffff")))
                    assertTrue(ratio <= PNG_MAX_DIFF_RATIO, "frame $name differs on ${"%.2f".format(ratio * 100)}% of pixels")
                },
            )
        }
    }

    private fun writeOrDelete(f: File, text: String?) {
        if (text == null) f.delete() else f.writeText(text)
    }
}

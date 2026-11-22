package diagrams.resolver

import diagrams.model.Codes
import diagrams.model.DiagnosticSink
import diagrams.model.Value
import diagrams.processor.GeometryRefs

/**
 * The resolved camera (spec section 14): the scene point at the center of [into] (a region of the
 * viewport), the zoom, and the rotation in degrees.
 */
data class CameraView(val center: Point, val zoom: Double, val rotation: Double, val into: Box)

/** Options of a root layer (spec section 8): `space: screen` ignores the camera, `parallax` follows part of its movement. */
data class LayerOption(val screen: Boolean, val parallax: Double)

internal object Cameras {
    /**
     * The camera's view from its map, after states and tracks. `fit` frames a node with `padding`
     * inside `into`; `attach` centers on a node or anchor; `center`, `zoom` and `rotation` given
     * explicitly win over what `fit` derives.
     */
    fun resolve(cam: Value.Map?, root: ResolvedNode, vw: Double, vh: Double, sink: DiagnosticSink): CameraView? {
        if (cam == null) return null
        fun num(k: String): Double? = (cam[k] as? Value.Num)?.value
        val into = (cam["into"] as? Value.Map)?.let { m ->
            fun c(k: String) = (m[k] as? Value.Num)?.value ?: 0.0
            Box(c("x"), c("y"), c("width"), c("height"))
        } ?: Box(0.0, 0.0, vw, vh)
        var center = Point(vw / 2, vh / 2)
        var zoom = 1.0
        (cam["fit"] as? Value.Str)?.value?.let { fit ->
            val node = find(root, fit)
            if (node == null) {
                sink.error(Codes.PATH_UNRESOLVED, "camera.fit", "'$fit' names no drawn node")
            } else {
                val b = node.box
                val pad = num("padding") ?: 0.0
                val sx = if (b.width > 0) (into.width - 2 * pad) / b.width else Double.MAX_VALUE
                val sy = if (b.height > 0) (into.height - 2 * pad) / b.height else Double.MAX_VALUE
                zoom = minOf(sx, sy).takeIf { it.isFinite() && it > 0 && it < Double.MAX_VALUE } ?: 1.0
                center = Point(b.centerX, b.centerY)
            }
        }
        ((cam["attach"] as? Value.Map)?.get("to") as? Value.Str)?.value?.let { to ->
            val dot = to.lastIndexOf('.')
            val anchor = to.substring(dot + 1).takeIf { dot >= 0 && it in GeometryRefs.ANCHORS }
            val node = find(root, if (anchor != null) to.substring(0, dot) else to)
            if (node == null) sink.error(Codes.PATH_UNRESOLVED, "camera.attach", "'$to' names no drawn node") else center = compass(node.box, anchor ?: "center")
        }
        (cam["center"] as? Value.Map)?.let { c ->
            center = Point((c["x"] as? Value.Num)?.value ?: center.x, (c["y"] as? Value.Num)?.value ?: center.y)
        }
        num("zoom")?.let { zoom = it }
        return CameraView(center, zoom, num("rotation") ?: 0.0, into)
    }

    private fun find(root: ResolvedNode, path: String): ResolvedNode? {
        if (path == "scene") return root
        var found: ResolvedNode? = null
        root.walk { if (found == null && it.ref == path) found = it }
        return found
    }

    private fun compass(b: Box, a: String) = when (a) {
        "north" -> Point(b.centerX, b.y)
        "south" -> Point(b.centerX, b.bottom)
        "east" -> Point(b.right, b.centerY)
        "west" -> Point(b.x, b.centerY)
        "north_east" -> Point(b.right, b.y)
        "north_west" -> Point(b.x, b.y)
        "south_east" -> Point(b.right, b.bottom)
        "south_west" -> Point(b.x, b.bottom)
        else -> Point(b.centerX, b.centerY)
    }

    /** Scene to viewport. A layer with parallax `f` sees the camera's center moved by `f` of its offset from rest. */
    fun matrix(v: CameraView, vw: Double, vh: Double, parallax: Double = 1.0): Matrix {
        val rest = Point(vw / 2, vh / 2)
        val c = Point(rest.x + (v.center.x - rest.x) * parallax, rest.y + (v.center.y - rest.y) * parallax)
        return Matrix.translate(v.into.centerX, v.into.centerY) * Matrix.rotate(-v.rotation) * Matrix.scale(v.zoom) * Matrix.translate(-c.x, -c.y)
    }

    fun interpolate(a: CameraView?, b: CameraView?, t: Double, vw: Double, vh: Double): CameraView? {
        if (a == null && b == null) return null
        val rest = CameraView(Point(vw / 2, vh / 2), 1.0, 0.0, Box(0.0, 0.0, vw, vh))
        val x = a ?: rest
        val y = b ?: rest
        fun l(p: Double, q: Double) = p + (q - p) * t
        // zoom interpolates geometrically, so a zoom in and a zoom out of the same factor take the same time
        val z = if (x.zoom > 0 && y.zoom > 0) kotlin.math.exp(l(kotlin.math.ln(x.zoom), kotlin.math.ln(y.zoom))) else l(x.zoom, y.zoom)
        return CameraView(Point(l(x.center.x, y.center.x), l(x.center.y, y.center.y)), z, l(x.rotation, y.rotation), Box(l(x.into.x, y.into.x), l(x.into.y, y.into.y), l(x.into.width, y.into.width), l(x.into.height, y.into.height)))
    }

    /** The scene seen through its camera: each root layer's nodes moved into viewport coordinates. */
    fun bake(scene: ResolvedScene): ResolvedScene {
        val v = scene.camera ?: return scene
        val byParallax = HashMap<Double, Matrix>()
        fun m(p: Double) = byParallax.getOrPut(p) { matrix(v, scene.width, scene.height, p) }
        val kids = scene.root.children.map { c ->
            val opt = scene.layers[c.layer]
            if (opt?.screen == true) c else transform(c, m(opt?.parallax ?: 1.0), top = true)
        }
        return scene.with(root = scene.root.copy(children = kids))
    }

    private fun transform(n: ResolvedNode, v: Matrix, top: Boolean): ResolvedNode {
        val world = v * n.world
        return n.copy(
            transform = if (top) v * n.transform else n.transform,
            world = world,
            box = world.apply(n.local),
            children = n.children.map { transform(it, v, top = false) },
        )
    }
}

package diagrams.processor

import diagrams.model.DataPath
import diagrams.model.Value

/** Marks the nodes one template instance produced, so paths can tell its inside from its outside. */
class InstanceTag(val template: Template)

/**
 * A node of the expanded tree (spec section 6, "Static resolution order", first step): templates
 * replaced by their bodies. Document nodes keep their data path in the canonical document; nodes
 * from a template body get a synthetic one under their instance (`.../$body/...`), so diagnostics
 * point at the instance.
 */
class ENode(
    /** Properties, without `children`. */
    val node: Value.Map,
    val dp: DataPath,
    val children: List<ENode>,
    /** Set on the root of an expanded instance. */
    val instance: InstanceTag? = null,
    /** The instance whose body produced this node; null for document nodes. */
    val owner: InstanceTag? = null,
) {
    companion object {
        /** The document's own tree, templates left unexpanded: an instance keeps its slot content as children. */
        fun plain(doc: Value): ENode? {
            val scene = (doc as? Value.Map)?.get("scene") as? Value.Map ?: return null
            return plain(scene, DataPath.ROOT.key("scene"))
        }

        private fun plain(v: Value.Map, dp: DataPath): ENode {
            fun list(k: String) = (v[k] as? Value.List)?.items.orEmpty().mapIndexedNotNull { i, c -> (c as? Value.Map)?.let { plain(it, dp.key(k).index(i)) } }
            return ENode(
                Value.Map(v.entries.filterKeys { it != "children" && it !in CoreSchema.DECORATIONS }),
                dp,
                list("underlay") + list("children") + list("overlay"),
            )
        }
    }
}

/** One node of the (expanded) tree, with its place in it. */
class NodeInfo(
    val dp: DataPath,
    val node: Value.Map,
    val parent: NodeInfo?,
    /** Reference path used in diagnostics (`card.title`, root `scene`). */
    val path: String,
    /** Structural locator: `scene/0/2`. */
    val loc: String,
    /** The template this node is an instance of. */
    val template: Template? = null,
    /** The instance whose body this node comes from (null for document nodes). */
    val owner: NodeInfo? = null,
) {
    val id: String? = (node["id"] as? Value.Str)?.value
    val children = mutableListOf<NodeInfo>()
    val shape: String? = (node["shape"] as? Value.Str)?.value
    val isRoot get() = parent == null

    /** The outline of floating anchors: `ellipse` for ellipses, circles and instances that declare it, or the id of a part of an instance. */
    val outline: String get() = template?.outline ?: if (shape == "ellipse" || shape == "circle") "ellipse" else "box"

    /**
     * The IDs reachable from this node's children without crossing another named node (spec section
     * 5). An instance's scope also holds what fills its slots, wherever the body puts the slot: the
     * instance's children are its own, even under a named part of the body.
     */
    val namespace: Map<String, NodeInfo> by lazy { scope().first }

    /** Children of this instance that take an id one of its parts already has (spec section 9, "Slots"). */
    val slotClashes: List<NodeInfo> get() = scope().second

    private val scoped by lazy {
        val out = LinkedHashMap<String, NodeInfo>()
        val clashes = mutableListOf<NodeInfo>()
        val instance = this.takeIf { it.template != null }
        fun add(c: NodeInfo) {
            val id = c.id ?: return
            val seen = out[id]
            if (seen == null) {
                out[id] = c
            } else if (instance != null && (seen.owner === instance) != (c.owner === instance)) {
                clashes += if (c.owner === instance) seen else c
            }
        }
        fun visit(n: NodeInfo) {
            for (c in n.children) {
                if (c.id != null) {
                    add(c)
                    // a named part of the body can hold the slot: look through it for the instance's children
                    if (instance != null && c.owner === instance && c.template == null) slotContent(c, ::add, ::visit)
                } else if (c.template == null) {
                    // an unnamed instance is still a scope: its inside cannot be named from out here
                    visit(c)
                }
            }
        }
        visit(this)
        out to clashes
    }

    private fun scope() = scoped

    /** The instance's children under [part], a named node of its body. */
    private fun slotContent(part: NodeInfo, add: (NodeInfo) -> Unit, visit: (NodeInfo) -> Unit) {
        for (c in part.children) {
            when {
                c.owner === this -> if (c.template == null) slotContent(c, add, visit)
                c.id != null -> add(c)
                c.template == null -> visit(c)
            }
        }
    }

    fun isAncestorOf(o: NodeInfo): Boolean = generateSequence(o.parent) { it.parent }.any { it === this }

    /** True when this node is [instance] itself or comes from its body, at any depth. */
    fun isInside(instance: NodeInfo): Boolean = generateSequence(this) { it.owner }.any { it === instance }

    override fun toString() = path
}

/**
 * A path matched against the tree: the node it names and what follows (a property or an anchor).
 * [breach] is the instance whose interface the path reaches past, if any (spec section 5, "Encapsulation").
 */
class PathMatch(val node: NodeInfo, val rest: List<String>, val breach: NodeInfo? = null)

/** Vocabulary of geometry references and anchors (spec sections 6 and 9). */
object GeometryRefs {
    val PROPERTIES = setOf("x", "y", "width", "height", "left", "right", "top", "bottom", "center_x", "center_y")
    val ANCHORS = listOf("center", "north", "south", "east", "west", "north_east", "north_west", "south_east", "south_west")
    val CONNECTOR_ANCHORS = listOf("start", "end", "mid")

    /**
     * Where a connector's label goes: the room a graph layout reserved for it, or `mid` when none
     * did. It is not a reserved ID: a connector has no children a path could confuse it with.
     */
    const val LABEL_ANCHOR = "label"

    fun isAnchor(name: String, target: NodeInfo) = name in ANCHORS || (target.shape == "connector" && (name in CONNECTOR_ANCHORS || name == LABEL_ANCHOR))

    /** The opposite compass anchor: an attachment `{anchor: west, to: a}` meets `a.east`. */
    fun opposite(anchor: String): String = when (anchor) {
        "north" -> "south"
        "south" -> "north"
        "east" -> "west"
        "west" -> "east"
        "north_east" -> "south_west"
        "south_west" -> "north_east"
        "north_west" -> "south_east"
        "south_east" -> "north_west"
        else -> anchor
    }
}

/** Every node of a tree, and path resolution with the scoping rule of spec section 5. */
class NodeIndex(tree: ENode?) {
    /** The document as written, without template expansion. */
    constructor(doc: Value) : this(ENode.plain(doc))

    val root: NodeInfo?
    private val byDp = HashMap<DataPath, NodeInfo>()
    private val tags = HashMap<InstanceTag, NodeInfo>()

    init {
        root = tree?.let { build(it, null, "scene", "scene") }
    }

    private fun build(e: ENode, parent: NodeInfo?, path: String, loc: String): NodeInfo {
        val n = NodeInfo(e.dp, e.node, parent, path, loc, e.instance?.template, e.owner?.let { tags[it] })
        e.instance?.let { tags[it] = n }
        if (e.dp !in byDp) byDp[e.dp] = n
        e.children.forEachIndexed { i, c ->
            // what fills a slot is addressed through the instance, wherever its body put the slot
            val inst = n.owner
            val slotted = n.template == null && inst != null && c.owner?.let { tags[it] } !== inst
            n.children += build(c, n, childPathOf(if (slotted) inst!!.path else path, c.node), "$loc/$i")
        }
        return n
    }

    operator fun get(dp: DataPath): NodeInfo? = byDp[dp]

    /** All nodes, depth first. */
    fun all(): Sequence<NodeInfo> = sequence {
        val r = root ?: return@sequence
        val stack = ArrayDeque(listOf(r))
        while (stack.isNotEmpty()) {
            val n = stack.removeLast()
            yield(n)
            for (c in n.children.asReversed()) stack.addLast(c)
        }
    }

    /** Scopes from [from] outward: the node itself if named, its named ancestors and instances, then the root. */
    private fun scopes(from: NodeInfo) = generateSequence(from) { it.parent }.filter { it.id != null || it.isRoot || it.template != null }

    /**
     * Resolves a dotted path written on [from]: the first ID is looked up in the nearest named scope
     * that has it, the following IDs descend from there, and the rest is returned unmatched.
     * `scene.` starts at the root. Null when the first ID matches nothing.
     */
    fun resolve(ref: String, from: NodeInfo): PathMatch? {
        val root = root ?: return null
        val segs = ref.split('.')
        var cur: NodeInfo
        var breach: NodeInfo? = null
        var i = 1
        if (segs[0] == "scene") {
            cur = root
        } else {
            var scope: NodeInfo? = null
            cur = scopes(from).firstNotNullOfOrNull { s -> s.namespace[segs[0]]?.also { scope = s } } ?: return null
            breach = breachOf(from, scope!!, cur)
        }
        while (i < segs.size) {
            val next = cur.namespace[segs[i]] ?: break
            breach = breach ?: breachOf(from, cur, next)
            cur = next
            i++
        }
        return PathMatch(cur, segs.drop(i), breach)
    }

    /**
     * Stepping from [via] to [target] reaches past a template's interface unless the path is written
     * inside that instance, or [target] is a part the instance exports and the step starts at the instance.
     * Exporting the id of a `for_each` exports its items, `step[m2]` with `step`.
     */
    private fun breachOf(from: NodeInfo, via: NodeInfo, target: NodeInfo): NodeInfo? {
        val owner = target.owner ?: return null
        if (from.isInside(owner) && from !== owner) return null
        val part = target.id?.substringBefore('[')
        if (via === owner && part != null && part in owner.template?.exports?.parts.orEmpty()) return null
        return owner
    }
}

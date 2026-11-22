package diagrams.resolver

import diagrams.model.Codes
import diagrams.model.DataPath
import diagrams.model.DiagnosticSink
import diagrams.model.Value
import diagrams.processor.ENode
import diagrams.processor.Environment
import diagrams.processor.Expander
import diagrams.processor.NodeIndex
import diagrams.processor.NodeInfo
import diagrams.processor.SetTarget
import diagrams.processor.StateContainer
import diagrams.processor.StateDef
import diagrams.processor.resolveSetKey

/** A state container found in the expanded tree, by the path of the node that owns it (`camera` for the camera's). */
class ContainerInfo(val owner: String, val container: StateContainer, val depth: Int)

/** The current state of one container, with the node that owns it (null for the camera). */
internal class CurrentState(val owner: String, val node: NodeInfo?, val state: StateDef)

/** The expanded tree with the current states applied, and which state each container is in. */
internal class StateApplication(
    val tree: ENode?,
    val current: Map<String, String>,
    val containers: List<ContainerInfo>,
    /** The camera map (spec section 14) with state overrides applied; null without a camera. */
    val camera: Value.Map?,
    val states: List<CurrentState>,
)

/**
 * States (spec section 11). The current state of each container is, highest first: the harness's
 * choice ([requested], by owner path), a parent state's `set` of `<owner>.state`, the owner's
 * authored `state:`, then the first declared state. A resolved state is the fold of its `extends`
 * chain over the base scene. Overrides of template parameters are applied to the document and the
 * instance is expanded again; other overrides are applied to the expanded nodes.
 *
 * The camera can own a container too (owner `camera`), and scene states can set `camera.<property>`.
 *
 * When two containers set the same property, the one that comes later in the tree wins, and the
 * warning `blend_overlap` is emitted (spec section 21 expects precedence for states).
 */
internal object StateResolver {
    private class Override(val target: SetTarget, val value: Value, val from: NodeInfo)

    fun apply(doc: Value, env: Environment, requested: Map<String, String>, sink: DiagnosticSink): StateApplication {
        var current = doc
        var paramsApplied = false
        val cameraDoc = (doc as? Value.Map)?.get("camera") as? Value.Map
        val cameraContainer = cameraDoc?.get("states")?.let { StateContainer.read(it, DataPath.ROOT.key("camera").key("states"), "camera") }
        while (true) {
            val tree = Expander(env, DiagnosticSink()).expand(current)
            val index = NodeIndex(tree)
            val found = index.all().mapNotNull { info ->
                info.node["states"]?.let { s -> StateContainer.read(s, info.dp, info.path)?.let { info to it } }
            }.toList()
            for ((owner, id) in requested) {
                val c = found.firstOrNull { it.first.path == owner }?.second ?: if (owner == "camera") cameraContainer else null
                when {
                    c == null -> sink.warning(Codes.PATH_UNRESOLVED, owner, "no state container is owned by '$owner'; the requested state is ignored")
                    c[id] == null -> sink.warning(Codes.PATH_UNRESOLVED, owner, "'$owner' has no state '$id'; states: ${c.states.joinToString { it.id }}")
                }
            }
            var fromParents = emptyMap<NodeInfo, String>()
            var cameraFromParents: String? = null
            var selection = emptyMap<NodeInfo, String>()
            var overrides = emptyList<Override>()
            var cameraSets = emptyList<Pair<String, Value>>()
            for (round in 0 until 8) {
                selection = found.mapNotNull { (info, c) ->
                    val pick = listOf(requested[info.path], fromParents[info], (info.node["state"] as? Value.Str)?.value).firstOrNull { it != null && c[it] != null }
                        ?: c.first?.id
                    pick?.let { info to it }
                }.toMap()
                val sets = found.flatMap { (info, c) ->
                    val id = selection[info] ?: return@flatMap emptyList()
                    c.chain(id).orEmpty().flatMap { s -> s.set.entries.map { (k, v) -> Triple(info, k, v) } }
                }
                cameraSets = sets.filter { it.second.startsWith("camera.") }.map { it.second.removePrefix("camera.") to it.third }
                overrides = sets.filter { !it.second.startsWith("camera.") }.mapNotNull { (info, k, v) -> resolveSetKey(k, info, index)?.let { Override(it, v, info) } }
                val next = overrides.filter { it.target.property == listOf("state") }.mapNotNull { o -> (o.value as? Value.Str)?.let { o.target.node to it.value } }.toMap()
                val nextCamera = cameraSets.lastOrNull { it.first == "state" }?.second?.let { (it as? Value.Str)?.value }
                if (next == fromParents && nextCamera == cameraFromParents) break
                fromParents = next
                cameraFromParents = nextCamera
            }
            val params = overrides.filter { o ->
                val t = o.target
                t.property.size == 1 && t.node.template?.params?.containsKey(t.property[0]) == true && "\$body" !in t.node.dp.segments
            }
            if (params.isNotEmpty() && !paramsApplied) {
                // re-expand with the new parameters, then select again: the instance's inside may change
                current = params.fold(current) { d, o -> setAt(d, o.target.node.dp.segments, o.target.property, o.value) }
                paramsApplied = true
                continue
            }
            val patches = LinkedHashMap<DataPath, MutableList<Pair<List<String>, Value>>>()
            val setBy = HashMap<Pair<DataPath, List<String>>, Override>()
            for (o in overrides) {
                val t = o.target
                if (t.property == listOf("state") || t.property.isEmpty()) continue
                if (t.node.template?.params?.containsKey(t.property[0]) == true) continue
                val key = t.node.dp to t.property
                setBy[key]?.let { prev ->
                    if (prev.from !== o.from && prev.value != o.value) {
                        sink.warning(Codes.BLEND_OVERLAP, "${t.node.path}.${t.property.joinToString(".")}", "set by the states of both ${prev.from.path} and ${o.from.path}; the later one (${o.from.path}) wins")
                    }
                }
                setBy[key] = o
                patches.getOrPut(t.node.dp) { mutableListOf() } += t.property to o.value
            }
            val patched = tree?.let { patch(it, patches) }
            val containers = found.map { (info, c) -> ContainerInfo(info.path, c, generateSequence(info.parent) { it.parent }.count()) }.toMutableList()
            val states = found.mapNotNull { (info, c) -> selection[info]?.let { c[it] }?.let { CurrentState(info.path, info, it) } }.toMutableList()
            val currentMap = LinkedHashMap(selection.mapKeys { it.key.path })
            // the camera: the document's values, then the scene states' `camera.*`, then its own state
            var camera = cameraDoc?.let { Value.Map(it.entries.filterKeys { k -> k != "states" && k != "state" }) }
            if (camera != null || cameraSets.isNotEmpty()) {
                var cam = camera ?: Value.Map(emptyMap())
                for ((k, v) in cameraSets) if (k != "state") cam = setProp(cam, k.split('.'), v)
                if (cameraContainer != null) {
                    val pick = listOf(requested["camera"], cameraFromParents, (cameraDoc["state"] as? Value.Str)?.value).firstOrNull { it != null && cameraContainer[it] != null }
                        ?: cameraContainer.first?.id
                    if (pick != null) {
                        for (s in cameraContainer.chain(pick).orEmpty()) for ((k, v) in s.set) if (k != "state") cam = setProp(cam, k.split('.'), v)
                        currentMap["camera"] = pick
                        cameraContainer[pick]?.let { states += CurrentState("camera", null, it) }
                    }
                    containers += ContainerInfo("camera", cameraContainer, 0)
                }
                camera = cam
            }
            return StateApplication(patched, currentMap, containers, camera, states)
        }
    }

    fun patch(e: ENode, patches: Map<DataPath, List<Pair<List<String>, Value>>>): ENode {
        val ps = patches[e.dp]
        val node = if (ps == null) e.node else ps.fold(e.node) { m, (prop, v) -> setProp(m, prop, v) }
        return ENode(node, e.dp, e.children.map { patch(it, patches) }, e.instance, e.owner)
    }

    /** Sets a property path on a node map: `fill`, `at.x`, `theme.primary`. */
    fun setProp(m: Value.Map, prop: List<String>, v: Value): Value.Map {
        if (prop.size == 1) return Value.Map(m.entries + (prop[0] to v))
        val inner = m[prop[0]] as? Value.Map ?: Value.Map(emptyMap())
        return Value.Map(m.entries + (prop[0] to setProp(inner, prop.drop(1), v)))
    }

    /** Reads a property path on a node map. */
    fun getProp(m: Value.Map, prop: List<String>): Value? {
        var cur: Value = m
        for (p in prop) cur = (cur as? Value.Map)?.get(p) ?: return null
        return cur
    }

    /** Sets a property of the document node at [dp] (data path segments). */
    private fun setAt(doc: Value, dp: List<Any>, prop: List<String>, v: Value): Value {
        if (dp.isEmpty()) return (doc as? Value.Map)?.let { setProp(it, prop, v) } ?: doc
        val head = dp[0]
        return when {
            doc is Value.Map && head is String -> doc[head]?.let { Value.Map(doc.entries + (head to setAt(it, dp.drop(1), prop, v))) } ?: doc
            doc is Value.List && head is Int -> Value.List(doc.items.mapIndexed { i, x -> if (i == head) setAt(x, dp.drop(1), prop, v) else x })
            else -> doc
        }
    }
}

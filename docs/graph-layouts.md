# Graph layouts

Addendum to [spec.md](spec.md), draft 0.1. It adds four layout kinds that place their children from
the connectors between them, and the contract that every layout kind now sits under.

## 1. Purpose

`row`, `column`, `grid` and `stack` place children from their order. A graph layout places them
from their relations: the author declares nodes and connectors, and the container finds the
positions.

```yaml
- id: flow
  layout: layered
  children:
    - {use: flow.step, id: read, text: Read}
    - {use: flow.step, id: check, text: Check}
    - {use: flow.step, id: emit, text: Emit}
    - {shape: connector, id: ok, from: check, to: emit, marker_end: arrow}
    - {shape: connector, from: read, to: check, marker_end: arrow}
    - {shape: text, text: valid, attach: {to: ok.label}}
```

The set of kinds is closed and built into the core: `layered`, `tree`, `circular`, `force`. A kind
is not a plug-in, because a document must resolve to the same picture on every implementation.

## 2. Nodes, edges and nesting

- The **nodes** of a graph container are its children, each as one opaque box, whatever is inside:
  a shape, a template instance, a `stack`, another graph container with its own layout.
- Three kinds of children are not nodes:
  - connectors, as in every layout;
  - children with `attach`, which follow their target as they do in a `free` container (this is how
    a label sits on an edge);
  - children that hold nothing but connectors and attached nodes, which is what an instance of a
    relation template is (`uml.association`).
- The **edges** of a graph container are the connectors whose two ends are in two different nodes
  of it. Where the connector is declared does not matter. A connector between two nodes of the same
  child belongs to that child, or to nothing.
- An end deeper than a child is **lifted** to that child for placement. The connector is still drawn
  from the node it names.
- A connector with both ends on the same child is a self-loop of that child.
- A connector is never a node, and a connector that ends on a connector is not an edge.
- **Twins.** Edges between the same two nodes, whichever way each one points, bow apart from the
  line between the two centers, 18 units from one to the next, so that none hides another. Every
  graph kind does it, including those that supply no other route (`circular`, `force`): there the
  bow is the only route supplied. Only connectors routed by `layout` count: one that says
  `straight` or `orthogonal` keeps its own line. A reflexive association is the common case.
- Nesting is recursive: sizes are solved bottom-up, so an inner graph is laid out first and is a box
  of known size for the outer one.

## 3. The layout contract

Every layout kind, old and new, is described by one contract (`Layouts` in the processor). The
validator, the resolver, the JSON Schema and an editor read it.

| Field | Meaning |
| --- | --- |
| `options` | Properties of the container the kind reads |
| `nodeHints` | Properties of a child the kind reads |
| `edgeHints` | Properties of a connector the kind reads |
| `pins` | Whether the author can fix the position of a child |
| `routes` | Whether the kind supplies routes for its edges |
| `labels` | Whether the kind reserves room for edge labels |
| `gestures` | What a direct manipulation means: reorder, set a hint, pin |

| Kind | Options | Node hints | Edge hints | Pins | Routes | Labels |
| --- | --- | --- | --- | --- | --- | --- |
| `free` | | | | `at` | | |
| `row`, `column` | `gap`, `align` | | | | | |
| `grid` | `gap`, `align`, `columns` | | | | | |
| `stack` | `align` | | | | | |
| `layered` | `direction`, `gap`, `rank_gap`, `ordering` | `rank` | `constrain` | | yes | yes |
| `tree` | `direction`, `gap`, `level_gap`, `radial` | | | | yes | |
| `circular` | `gap`, `radius`, `start_angle` | | | | | |
| `force` | `seed`, `link_distance` | `pinned` | | `pinned` | | |

`padding`, `width` and `height` apply to every container.

**Author control is per kind.** Full automation is the default everywhere. A kind may add hints
(`layered`), pins (`force`), both or neither.

**Nothing is dropped silently.** A hint or an option that the layout in charge does not declare is
reported as `hint_ignored`. A hint that contradicts the edges is reported as `hint_conflict`, and is
ignored.

**Routes.** The layout supplies a route; the connector's router decides whether to use it.
- `route` is `layout`, `straight` or `orthogonal`. The default is `layout`.
- `layout` follows the route supplied by the container the connector is an edge of. Where there is
  none (the connector is not an edge, or the kind supplies no routes) it is `straight`.
- `straight` and `orthogonal` ignore the supplied route. The connector is still an edge: it still
  places its nodes.
- The layout gives the points between the two nodes. Each end is the outline of its node toward the
  next point, as for any floating end.
- A fixed anchor (`from: a.east`) ends the route where the author put it. It does not change the
  placement. If it faces away from the route, the route turns back: `route_backtrack`.

**Labels.** A connector has a fourth derived anchor, `label`.
- In a kind that reserves room for labels, the size of what is attached to `<connector>.label` is
  part of the layout, and the anchor is the middle of the room reserved. The attached node is
  centered there, whatever its own `anchor` and `gap`; `offset` still applies.
- Everywhere else `label` is `mid`. A library attaches its labels to `label` once and works in both.
- Room is reserved only for connectors routed by `layout`: the room is on the supplied route.
- `label` is not a reserved ID.

## 4. `layered`

Ranks that follow the direction of the edges (Sugiyama): flowcharts, dependency graphs, class
hierarchies, pipelines.

| Option | Values | Default |
| --- | --- | --- |
| `direction` | `down`, `right`, `up`, `left` | `down` |
| `gap` | distance between neighbours in a rank | 40 |
| `rank_gap` | distance between ranks | 60 |
| `ordering` | `auto` (fewest crossings found), `declared` (declaration order in each rank) | `auto` |

| Hint | On | Meaning |
| --- | --- | --- |
| `rank: first` | node | In the first rank. Conflicts with an edge that leads to the node |
| `rank: last` | node | In the last rank. Conflicts with an edge that leaves the node |
| `rank: {same_as: <node>}` | node | In the rank of a sibling. Conflicts with an edge between the two |
| `constrain: false` | connector | Drawn, without influencing the ranks |

- Cycles are allowed. The edges that close one are found by a depth-first walk in declaration order,
  and are drawn against the ranks.
- A node goes as far down as its incoming edges push it. A node with no incoming edge sits one rank
  above the closest node it leads to.
- An edge across several ranks is routed around the nodes in between.
- Two edges between the same two nodes are drawn apart. A self-loop is a small loop on the side of
  its node.
- An edge between two nodes of the same rank (after `same_as`, or with `constrain: false`) is a
  straight line; no room is reserved for its label.
- When an edge has a label, ranks are doubled internally so that the label has a rank of its own.
  The route passes beside the label.

## 5. `tree`

One parent per node: organisation charts, file trees, mind maps.

| Option | Values | Default |
| --- | --- | --- |
| `direction` | `down`, `right`, `up`, `left` | `down` |
| `gap` | distance between neighbouring subtrees | 30 |
| `level_gap` | distance between levels | 60 |
| `radial` | `true`: the root in the middle, one ring per level | `false` |

- The first edge that reaches a node, in declaration order, gives it its parent. Any other edge to
  it, and any edge that would close a loop, is reported as `tree_extra_edge`. It is still drawn
  (straight), and places nothing.
- A parent is centered over its children, which keep declaration order. Several roots stand side by
  side.
- Routes are elbows, or straight lines when `radial`.
- `radial` gives each subtree an angle in proportion to its number of leaves, starting at the top
  and turning clockwise. With several roots, the roots share the first ring.

## 6. `circular`

Declaration order on a ring: cycles, rings of peers, small networks.

| Option | Values | Default |
| --- | --- | --- |
| `gap` | least distance between neighbours | 30 |
| `radius` | radius of the ring | the smallest that respects `gap` |
| `start_angle` | angle of the first child in degrees, 0 pointing right, clockwise | -90 (top) |

No routes, except for twins (section 2): connectors are straight unless they say otherwise.

## 7. `force`

A simulation: edges are springs, unrelated nodes push each other away. For networks with no
direction and no hierarchy.

| Option | Values | Default |
| --- | --- | --- |
| `seed` | a whole number; another seed, another picture | 1 |
| `link_distance` | distance an edge wants between the outlines of its two nodes | 60 |

| Hint | On | Meaning |
| --- | --- | --- |
| `pinned: {x, y}` | node | The node's top-left corner, inside the container's padding. The simulation moves the others around it |

- The picture is stable for a given document, not under edits: adding a node can move every other
  one. Pin the nodes that must stay.
- With no pinned node the result is moved to the container's top-left corner. With pinned nodes it is
  left where the pins put it, and the container's `width` and `height` should be set.
- No routes, except for twins (section 2).

## 8. Determinism

A document resolves to the same geometry on every target (spec section 16). For graph layouts:

- All four kinds are common Kotlin code with no platform library.
- Every tie is broken by declaration order. No unordered collection is iterated.
- `force` is identical to the last bit by construction: a fixed number of steps, a seeded generator,
  a start position from declaration order, and arithmetic restricted to add, subtract, multiply,
  divide and square root, which IEEE 754 rounds correctly everywhere. Every resolution is a cold
  start: nothing is carried from one resolution to the next.
- `circular` and the radial `tree` need sines and cosines. They use one routine in common code
  (`Trig`) built from the same five operations, not the platform's.

## 9. Transitions

Layout is not solved again on every frame of a transition (spec section 13). That holds for graph
layouts:

- The two states are laid out once each. Boxes interpolate.
- A supplied route is part of the layout: it interpolates too, along its length. When the two routes
  have a different number of points, each receives the other's points at the same fraction of its
  own length, so the first frame is exactly the first route and the last frame exactly the second.
- On each frame the ends of the interpolated route are taken again on the interpolated outlines, and
  nodes attached to the connector follow the interpolated route and label position.
- A connector whose route is supplied in only one of the two states is routed again on every frame,
  like any other connector.

In the resolved scene a connector that follows a supplied route says `route: layout`, and `label`
holds the reserved label position when there is one.

## 10. Diagnostics

| Code | Severity | Raised when |
| --- | --- | --- |
| `hint_ignored` | warning | A hint or option is set where the layout in charge does not declare it |
| `hint_conflict` | warning | A hint contradicts the edges, or names a node the layout does not place |
| `tree_extra_edge` | warning | An edge of a `tree` gives a node a second parent or closes a loop |
| `route_backtrack` | warning | A fixed anchor faces away from the supplied route |

Malformed values (`rank: middle`, `seed: -1`, `direction: sideways`) are `schema` errors.
`attach_ignored` no longer applies inside a graph container, where attached children are honoured.

## 11. Compatibility

- `route` now defaults to `layout`. Outside a graph container that is `straight`, as before; the
  canonical form of a connector without `route` changes from `route: straight` to `route: layout`.
- Property names are reserved IDs (spec section 5). The new ones are `rank`, `pinned`, `constrain`,
  `direction`, `rank_gap`, `ordering`, `radial`, `level_gap`, `start_angle`, `seed` and
  `link_distance`: a document that used one of them as an ID has to rename it.

## 12. Decision log

Taken on 20 November 2026.

| # | Topic | Decision | Rationale |
| --- | --- | --- | --- |
| G1 | Routes | The layout supplies a route; the connector's router decides. `layout` is the default router | One model for connectors; an author can still override one edge |
| G2 | Nesting | Flat and recursive: children are opaque boxes, edges across them are lifted | Compound layout stays out of 0.1; nested diagrams still work |
| G3 | Author control | Per kind: automatic by default, a kind may add hints, pins or both | Each algorithm has its own natural controls; no global rule fits |
| G4 | Extensibility | A closed set of kinds in the core, under one contract | Same picture on every implementation; the contract keeps kinds uniform |
| G5 | Kinds | `layered`, `tree`, `circular`, `force` | The four families that cover diagrams in practice |
| G6 | Determinism of `force` | Identical to the last bit by construction, with pins for stability under edits | The resolver's guarantee holds without tolerance |

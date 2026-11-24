# Library authoring: friction log

Every library built on diagrams is a proving ground for the language. When writing one meets
friction (a workaround, a hack, a rule that surprises), it is logged here, classified, and fixed
where it belongs: in the library when only that library is concerned, in the core when any author
would meet it. A library ends with a short review that adds its entries. A core fix is proven by
rewriting the libraries that worked around it: the examples must render the same.

Status: `fixed` (with the commit or the spec section), `designed` (written, not implemented),
`open`, `accepted` (a deliberate trade-off).

## UML library (November 2026)

The first full library: class, object, package, component, deployment, use case, activity, state
machine and sequence diagrams ([libs/uml.yaml](../libs/uml.yaml)). Merise had met several of the
same points earlier, which is what made them core issues.

| # | Friction | Workaround it forced | Status |
| --- | --- | --- | --- |
| 1 | Children of a slot under a named part of the body were reachable only through that part (`frame.content.customer`) | No named node around any slot | Fixed: what fills a slot is in the instance's scope (spec 9, "Slots"; decision 33) |
| 2 | Shapes drawn from the size of what they surround (a box in perspective, a folded corner, a frame's tag) formed a layout cycle | A hidden stretched rect, a second free container and an extra layer | Fixed: `underlay` and `overlay` with the `host` root (spec 6, "Decorations"; decision 41) |
| 3 | Invisible rects reserved room, and lint saw them as shapes | Transparent spacer rects | Fixed: `min_width`, `min_height` (spec 6; decision 34); lint also ignores shapes that paint nothing |
| 4 | No conditional value: each variant of a template was a node duplicated under two `if`s | Duplicated connectors and texts | Fixed: `choose(cond, a, b)`, and styles may be chosen (spec 9; decision 35) |
| 5 | Members of a relation family repeated the base's whole parameter list; one new parameter meant editing eight templates | Copy and paste | Fixed: `extends` and `set` (spec 9, "Extending a template"; decision 36) |
| 6 | Which side a label lands on depended on the direction of the line | `label_anchor` and `label_gap` tuned by hand per line, in both libraries | Fixed: `side` on attachments to connector points (spec 6; decision 39) |
| 7 | Multiplicities were placed with negative gaps from the line ends | Magic numbers | Fixed: `along` (spec 6; decision 40) |
| 8 | Arrows stopped on the bounding box of a diamond | None | Fixed: an outline can be a part (spec 6, 9; decision 37) |
| 9 | Ordinary words were refused as IDs (`start`, `ordering`) because some node kind has that property | Renaming | Fixed: an ID avoids the properties of its scope only (spec 5; decision 38) |
| 10 | A part named like a parameter (`attributes`) silently hid the parameter from states and patches | None: it went unnoticed | Fixed: reported in the library; the UML compartments and Merise's `synchro` were renamed |
| 11 | Expressions could not count a list, so equal shares of a width needed a fixed number of parameters | Merise's `rule1` to `rule4` | Fixed: `count(list)`; Merise's rules are a keyed list |
| 12 | Paths that sit inside a template's own parts were computed through world coordinates, so a template placed by a row or a column cycled | None possible | Fixed: geometry goes through the nearest common ancestor, sizes need no frame |
| 13 | Keyed items of an exported `for_each` (`step[m3]`) raised encapsulation warnings | None | Fixed: exporting the id exports the items |
| 14 | No dashed strokes | Double outlines, muted colours | Fixed: `stroke_dash` (spec 10; decision 32) |
| 15 | Sequence diagrams: step lists shared by every lifeline, fragments placed by expressions | Step lists and geometry expressions | Fixed: the `sequence` layout ([sequence-layout.md](sequence-layout.md); decision 42) |
| 16 | No italic font for abstract names | `{abstract}` under the name | Open: a theme with font styles |
| 17 | `host` is a reserved root, so a node cannot be named `host`, a natural name in deployment diagrams | Rename the node | Accepted: shadowing would let a user's child replace the container in a library's decorations, silently |
| 18 | Paths with brackets must be quoted in YAML (`"web.step[m3]"`) | Quotes | Accepted: a YAML rule; the error says so |
| 19 | Messages nested in an unnamed fragment could not be named from outside it (an unnamed instance was opaque) | IDs on every fragment and longer paths | Fixed: an unnamed instance is transparent for what fills its slots (spec 9; decision 43) |
| 20 | Making the sequence hints properties of every node (`role`, `on`, `covers`) took those names from every library's parameters: Merise's `leg` has a `role` | None: the libraries broke | Fixed: a layout kind's hints live in one map property, `sequence` (decision 44). Rule for the core: every property added to all nodes is a parameter name taken from all libraries |
| 21 | `on` must be quoted as a key in YAML: a bare `on` reads as true | Quotes (`"on": web`) | Accepted: a YAML rule; the template's description says so |

## Checklist for a new library

- Write one example per diagram family and render it: most friction shows on screen, not in the
  schema.
- Lint every example clean, and add a fixture for anything the core does for the library.
- Name parts and parameters apart; export the parts users address.
- Prefer `extends` for families, `choose` for variants, decorations for shapes drawn from a size,
  `side` and `along` for labels on lines.
- End with a review: add your entries here.

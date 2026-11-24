

# diagrams: language and toolset specification (draft 0.1)

2026-11-17 · @Antoine

## 1. Purpose and scope

diagrams is a declarative language, written as YAML, for drawings, diagrams, slide decks and animations, with the toolset that validates, resolves and renders it. This draft records the design decisions taken so far and is written for the agent that will implement it.
- **Primary author:** an LLM working from schemas, examples and diagnostics. People write and edit the same files.
- **First targets:** diagrams built from typed libraries (UML, Archimate), step-by-step decks, animated explainers.
- **Outside the file:** interactive and generative logic. It lives in the harness (section 17).
- **Not a target:** character animation (rigs, deformation, path morphing). Nothing should preclude it, and no feature is designed for it.
- **No GUI editor in the first versions.** Editors come later as harnesses.

Syntax shown in this draft is normative for the concepts and illustrative for spelling. Section 21 lists the rules that were assumed and still need confirmation.
| Term | Meaning |
| --- | --- |
| Document | One authored file describing a scene |
| Library | A file of templates, imported under an alias |
| Theme | A file of named tokens and font assets |
| Core | Processor, validator and resolver, with no rendering dependency |
| Renderer | Draws a resolved scene to SVG, Canvas or pixels |
| Harness | Host program that drives a document through the API |

## 2. Design principles

Seven rules recur across the whole design. When a later question is undecided, the answer should follow them.
- **Containers on every axis.** Layout containers place nodes in space, time containers order clips in time, state containers define navigation.
- **Directed references.** Attachments, timing references and audio cues all point one way. Each kind forms a DAG, and a cycle is a validation error.
- **A convenient surface expands to a strict core.** Authored form expands to canonical form, state and clip forms expand to tracks, containers expand to coordinates.
- **Allow and warn.** Reaching past a template interface or overlapping two animations still renders, with a diagnostic.
- **Pure data.** The file holds closed constructs only. Every frame is a pure function of document, asset manifest, state set and time.
- **Document-only mutation.** The resolved scene is read-only. Anything on screen is explained by a document.
- **Schema first.** Every template's parameter schema compiles to JSON Schema, which drives validation, completion and constrained decoding.

## 3. Pipeline and artifacts

A document passes through three stages, and each stage's output is a document that tools can read.

The processor reads only library schemas, so it can run on every keystroke or model output. Assets, states and time enter at the resolver.
| Stage | Contains | Produced by | Consumed by |
| --- | --- | --- | --- |
| --- | --- | --- |
| Authored | String expressions, shortcuts (`cumulative`, implicit tree `extends`), omitted defaults, comments | People, LLMs | Processor |
| Canonical | The same semantics with every shortcut explicit: structured expressions, explicit `extends`, defaults filled in. Templates, containers and states are intact | Processor | Validator, schema tooling, diffing, harness patches |
| Resolved | Templates expanded, layout solved, attachments applied, connectors routed, tracks generated | Resolver | Renderers, exporter, lint, harness reads |

**Processor rules**
- Authored to canonical is purely syntactic. It needs the schemas of imported libraries and nothing else: no geometry, no assets.
- Canonical is a valid authored document. The processor accepts both, and a file may mix forms.
- The processor never modifies its input. It emits canonical as a separate document.
- The processor emits a source map from canonical paths to authored positions (line and column). Diagnostics use it now, and editors will use it later to write back.
- A printer converts canonical back to authored form, for example a structured expression back to a string.

**Resolver inputs:** the canonical document, the asset manifest (section 15), the current state set, and a time value.

**Serialization**
- The data model comes first. YAML and JSON are exact equivalents and convert losslessly.
- YAML is restricted to a strict subset: YAML 1.2 core types only, no anchors, aliases or merge keys, no custom tags, no duplicate keys.
- Only `true` and `false` are booleans. A string that could read as a number or boolean must be quoted, and the processor rejects ambiguous plain scalars.

## 4. Document structure and coordinate system

A document is one map with a small fixed set of top-level keys.
| Key | Required | Meaning |
| --- | --- | --- |
| --- | --- |
| `format` | yes | Language version, `diagrams/0.1` |
| `title` | no | Human-readable name |
| `viewport` | yes | `{width, height}` of the visible frame, in units |
| `imports` | no | Map of alias to local file path, for libraries and themes |
| `base_theme` | no | Alias of the imported theme that supplies root tokens |
| `camera` | no | Initial camera properties (section 14) |
| `scene` | yes | The root node |
```yaml
format: diagrams/0.1
title: "Rugby union match: domain model"
viewport: {width: 1800, height: 1300}
imports:
  uml: ./libs/uml.yaml
  paper: ./themes/paper.yaml
base_theme: paper
camera: {fit: scene, padding: 60}
scene:
  layers: [edges, classes, notes]
  children: []
```

Library and theme files share `format` and `imports`. A library declares `templates` and `styles`. A theme declares `tokens` and font assets.

**Imports.** In 0.1 an import is a local file reference. Every import has an alias, and its elements are always used through it (`uml.class`), so a registry can be added later without changing documents.

**Coordinates and units**
- Units are logical pixels: one unit is one pixel at zoom 1 and export scale 1. Export scale never changes the document.
- The origin is top-left. x grows to the right and y grows downward, as in SVG and Canvas.
- Rotation is in degrees, and positive rotation is clockwise.
- Each node has a local frame. A child's coordinates are relative to its parent's origin.
- Time values are in seconds.
- Colors are `#rrggbb` or `#rrggbbaa` strings.

## 5. Nodes and addressing

The scene is a tree of nodes, and every reference in the language is a path through that tree.

**Node kinds**
| Kind | Written as | Notes |
| --- | --- | --- |
| --- | --- |
| Primitive | `shape: rect`, `ellipse`, `circle`, `line`, `polyline`, `polygon`, `path`, `text`, `connector` | The drawing vocabulary |
| Container | `layout:` with `children:` and no `shape` | Places its children (section 6) |
| Template instance | `use: alias.name` | Parameters are sibling keys (section 9) |
| Media | `media: image`, `audio`, `video` | Extensible kinds (section 15) |

**Common properties:** `id`, `layer`, `at`, `width`, `height`, `rotation`, `scale`, `opacity`, `present`, `style`, `theme`, `attach`, `state`, `states`, `enter`, `exit`.

**Paths**
- An ID is local to its parent and matches `[a-z][a-z0-9_]*`. Global uniqueness is not required: two instances of one template repeat the same internal IDs.
- A path is the chain of IDs from an ancestor down: `order.header.title`.
- Only nodes with an ID are addressable. A node without one renders and cannot be referenced.
- A node without an ID is transparent in paths: `agent.engine.llm` skips an unnamed row between `agent` and `engine`. IDs must be unique among the nodes reachable from a named node without crossing another named node.
- Items of a repeated list are addressed by key, never by index: `order.attributes[total]`. The key is the item's identity for state diffs and tweening.
- A property is addressed by appending its name: `order.state`, `camera.zoom`, `agent.pulse.scale`. An ID cannot be a geometry property, an anchor or a property every node has (`opacity`, `state`...), nor a property of its scope, the named node its path starts from (`layout` or `ordering` under a container, a parameter under an instance): a path could not tell them apart.
- Reserved roots: `scene`, `camera`, `viewport`, `theme`.

**Scope.** A reference written on a node (`attach.to`, a connector's `from` and `to`, a geometry reference in an expression) resolves from the declaring node's enclosing named scope outward, nearest first. Paths inside a state container (`set`, `timeline`, `transitions`) resolve from the node that owns the container.

**Presence.** `present` is a boolean property, default `true`. A node that is not present takes no space in layout, is not painted, and is not hit-tested. States override a base scene, so a node that appears later is declared in the base with `present: false`. A present node attached to a non-present node is a resolution error.

**Encapsulation.** A template declares its interface: exported parts, anchors, properties, states and expected tokens (section 9). A path that reaches past the interface still resolves, and the validator emits an `encapsulation` warning. Harness reads never warn.

## 6. Placement and attachments

A node is placed by its container, by an attachment, or by its own coordinates, in that order of priority.

**Containers**
- `layout` is one of `row`, `column`, `grid`, `stack` or `free`. A node with children and no `layout` is `free`.
- `row` flows left to right and `column` flows top to bottom. `grid` takes `columns`. `stack` superposes its children.
- Shared options: `gap`, `padding`, `align` (`start`, `center`, `end`, `stretch`), and `min_width`, `min_height`: the least size the container takes however small its content, which replaces invisible spacer shapes. An empty container with a minimum size is the spacer: `{min_height: 40, children: []}`.
- A container sizes to its content unless `width` or `height` is set. Text is measured by the core (section 16).
- In a `free` container, a child is placed by `at: {x, y}` in the parent's frame. This is the mode for drawing a complex shape.
- Free relational placement ("right of A") is not part of 0.1.
- `layered`, `tree`, `circular` and `force` place their children from the connectors between them. They are specified in the addendum [graph-layouts.md](graph-layouts.md), with the contract every layout kind declares. `sequence`, which places participants and messages in rows from their order, is specified in [sequence-layout.md](sequence-layout.md).

**Anchors**
- A fixed anchor is a named point in a node's local frame. Every node has `center`, `north`, `south`, `east`, `west`, `north_east`, `north_west`, `south_east`, `south_west`. Shapes and templates can declare more, such as `port_2`.
- A computed anchor is a function of the node's outline and an external point: the point on the perimeter toward the other end. A reference to a node where an anchor is expected means this floating anchor.
- A computed anchor may read only the box or center of the other end, never its resolved anchors. This keeps the dependency graph acyclic.
- A shape's outline is declared explicitly or defaults to its bounding box. It is separate from the drawn primitives and serves floating anchors, routing and lint. A template declares `outline: box`, `outline: ellipse`, or the id of one of its parts (`outline: diamond`): a connector then stops on that part's shape, a polygon included.

**Attachments**
```yaml
- use: uml.note
  id: squad_note
  text: "Matchday squad: 15 starters, 8 replacements"
  attach: {anchor: west, to: lineup.mid, gap: 40}
```

- An attachment is directed. The target owns the position and the attacher adapts.
- Attachments form a DAG and are resolved in one topological pass. A cycle is the error `attach_cycle`.
- `gap` is the distance between the two anchors, measured outward from the target anchor. `offset: {x, y}` is available for any other displacement.
- An attached node must sit in a `free` container or a graph container. Inside `row`, `column`, `grid` and `stack` the container wins, with the warning `attach_ignored`.
- **Beside a connector.** On a point of a connector (`start`, `end`, `mid`, `label`), `side` places the attacher beside the line: `left` or `right` of its direction of travel, `above` or `below` on the screen (for a vertical line, `above` is its left). The engine chooses the attacher's anchor, the one facing the line, so that a label clears a slanted or reversed line; `gap` is the distance from the line. `along` moves the point along the route first: from `start` or `end` toward the other end, or from `mid` toward `end` (negative toward `start`). Multiplicities are `{to: line.end, along: 18, side: above, gap: 2}`.
- Attachment does not depend on tree position. A node at the root can follow a node deep in another subtree.

**Decorations.** A container can carry `underlay` and `overlay` lists of nodes: they are painted behind and in front of its children, are never laid out and never count toward its size, and are placed in its frame by their own `at` and `points`. Inside them, the reference root `host` is the container's box: `host.width`, `host.height`, `host.right`, `host.bottom`. A shape drawn from the size of what it surrounds (a box in perspective, a folded corner, the tag of a frame) is a decoration.
```yaml
- layout: stack
  padding: {left: 14, top: 24, right: 26, bottom: 12}
  underlay:
    - shape: polygon
      points: [{x: 0, y: 12}, {x: 12, y: 0}, {x: "=host.width", y: 0}, {x: "=host.width", y: "=host.height - 12"}, {x: "=host.width - 12", y: "=host.height"}, {x: 0, y: "=host.height"}]
  children: [...]
```

**Attachment policy,** set on the attacher:
| Policy | Behaviour | Valid with |
| --- | --- | --- |
| --- | --- |
| `follow` | Rigid translation with the target. Default | One attached anchor |
| `stretch` | Geometry is a function of all attached anchor positions | Two or more, as in connectors |
| `block` | Refuses the move in an interactive editor | Reserved, no effect at resolution in 0.1 |

**Static resolution order**
- Expand templates and evaluate parameter and token expressions.
- Measure text and intrinsic sizes.
- Solve container layout: sizes bottom-up, positions top-down.
- Apply attachments and geometry expressions in topological order.
- Route connectors (section 7).

## 7. Connectors and routing

A connector is a shape with the `stretch` policy and two attached anchors, so it needs no separate edge concept.
```yaml
- {use: uml.aggregation, id: lineup, from: team, to: player, to_mult: "23"}
```

- `from` and `to` each take a node path (floating anchor on its outline) or an anchor path (fixed point).
- A connector exports the derived anchors `start`, `end`, `mid` and `label`. Other nodes and other connectors can attach to them, which covers midpoint labels, association classes and relations on relations. `label` is `mid`, except in a graph container that reserves room for labels (addendum, section 3).
- Markers, labels and multiplicities are built by library templates from the `connector` primitive and nodes attached to its derived anchors.
- A connector paints in the layering context where it is declared (section 8). Declare it at the common ancestor of its two ends.

**Routing**
- Routing is a second pass: all non-connector geometry is resolved first, then connectors are routed. Connectors attached to other connectors are ordered by their own DAG.
- A router is a pluggable stretch strategy. It receives the two endpoints and the outlines of both ends, and returns a path.
- 0.1 ships `straight` and `orthogonal`, with no obstacle avoidance. `curved` and avoidance can be added without changing the model.
- The default router is `layout`: it follows the route supplied by the graph container the connector is an edge of, and is `straight` everywhere else (addendum, section 3).
- A floating endpoint is the point on the end's outline toward the center of the other end's box.

## 8. Layers

Paint order is set by named layers and declaration order. There is no numeric z-order.
- Every node with children is a layering context. A subtree always paints as one block, so a child of A can never sit between two children of B.
- A context can declare an ordered list of named layers. The first listed paints first, at the bottom.
- A child names its layer with `layer`. A child that names none lands in the layer `default`, which paints first unless the list places it elsewhere.
- Within a layer, declaration order decides: later nodes paint on top. Items of a repeated list follow list order.
- `layer` is a discrete property, so a state can change it.
- A node that must paint above an unrelated subtree is declared in a higher context, usually a root overlay layer, and attached to the node it follows.

**Root layer options.** A layer entry is a name, or a map with options. Options apply to root layers only in 0.1.
```yaml
scene:
  layers:
    - {name: backdrop, parallax: 0.4}
    - stage
    - {name: hud, space: screen}
```

| Option | Effect |
| --- | --- |
| `space: screen` | The layer ignores the camera: titles, subtitles, progress marks |
| `parallax: <factor>` | The layer follows the camera's translation scaled by the factor, for depth planes |

## 9. Templates, libraries and expressions

A template is a complex shape with a declared parameter schema, and that schema is the element's type. There is no separate model layer.

**Using a template.** The instance supplies data matching the schema, as sibling keys of `use`.
```yaml
- use: uml.class
  id: match
  name: Match
  attributes: [kickoff: DateTime, competition: String]
  operations:
    - winner: "(): Team?"
```

**Defining a template.** A library entry declares parameters, an interface and a body.
```yaml
templates:
  class:
    params:
      name: {type: string}
      abstract: {type: boolean, default: false}
      attributes: {type: list, keyed: true, item: {type: string}}
    exports:
      anchors: [north, south, east, west]
      parts: [header]
      states: [collapsed, expanded]
      tokens: [surface, stroke, font_body]
    outline: box
    body:
      layout: column
      children:
        - shape: text
          id: title
          text: "=params.name"
        - for_each: params.attributes
          as: attr
          do:
            shape: text
            text: "=attr.key + ': ' + attr.value"
        - slot: default
```

**Extending a template.** A template can be another one with some parameters fixed: `extends` names a template of the same library and `set` fixes some of its parameters, possibly from parameters of its own. The rest of the base's parameters pass through; exports and outline are the base's unless redeclared, and so is the description.
```yaml
templates:
  dependency:
    extends: relation
    set: {head: open_arrow, dashed: true}
    params:
      route: {type: enum, values: [orthogonal, straight, layout], default: layout}
  extend:
    extends: dependency
    set: {label: "=choose(params.condition == '', '«extend»', '«extend» [' + params.condition + ']')"}
    params:
      condition: {type: string, default: ""}
```

A part of a body cannot take the name of a parameter: a state or a patch sets a parameter by path (`order.attributes`), and the path would not tell them apart.

**Closed constructs.** These are the only computation a file can hold.
- Parameter substitution.
- `for_each` over a list parameter. Each item carries its key.
- `if` on an expression over parameters.
- Slots: a `slot` placeholder in the body is filled by the instance's `children`. Named slots are filled through `slots`. What fills a slot belongs to the instance's scope wherever the body puts the slot, under a named part or not: `frame.customer`, never `frame.content.customer`. A child that takes the id of a part of the body is an error. An instance without an ID is transparent for what fills its slots, as an unnamed container is: messages nested in an unnamed fragment are named from the fragment's scope. Its parts stay hidden.
- The closed expression set below.

There are no user-defined functions, no recursion and no general loops. A template may use other templates, and the instantiation graph must be acyclic. Everything terminates and can be checked without running it.

**Types and schemas**
- Restyling an element means a second template that accepts the same schema.
- Each template's parameter schema compiles to JSON Schema. A document's effective schema is the core schema plus the schemas of its imported libraries.
- That schema drives validation, editor completion and grammar-constrained decoding, for any library, with no custom tooling.
- Relation rules, such as which Archimate relation is legal between which elements, can be added later as optional library-level validation.

**Expressions**
- Authored form is a string with a leading `=`: `x: "=a.right + 2"`. A literal string that starts with `=` is written with `==`.
- Canonical form is structured: `x: {add: [{ref: a.right}, 2]}`.
- The processor parses authored strings and rewrites them as structured nodes, or rejects them with `expr_syntax`.
- The grammar is short enough to embed in a constrained-decoding grammar next to the schema.
```
expr    = or ;
or      = and { "or" and } ;
and     = cmp { "and" cmp } ;
cmp     = sum [ ( "==" | "!=" | "<" | "<=" | ">" | ">=" ) sum ] ;
sum     = term { ( "+" | "-" ) term } ;
term    = unary { ( "*" | "/" ) unary } ;
unary   = [ "-" | "not" ] primary ;
primary = number | string | "true" | "false" | ref | call | "(" expr ")" ;
call    = ( "min" | "max" | "abs" | "count" | "choose" ) "(" expr { "," expr } ")" ;
ref     = ident { "." ident | "[" ident "]" } ;
```

Canonical operator names: `add`, `sub`, `mul`, `div`, `neg`, `min`, `max`, `abs`, `count`, `choose`, `eq`, `ne`, `lt`, `le`, `gt`, `ge`, `and`, `or`, `not`, `ref`. `add` on two strings concatenates. `count` gives the length of a list parameter (`=params.width / count(params.rules)`). `choose(cond, a, b)` is `a` when the condition holds and `b` otherwise, and only the chosen value is evaluated: a template picks a value from its parameters with it (`marker_end: "=choose(params.open, 'open_arrow', 'arrow')"`) rather than duplicating a node under two `if`s.

**Reference roots**
| Root | Gives | Evaluated |
| --- | --- | --- |
| --- | --- |
| `params`, loop variables | Template parameters and the current item | At template expansion |
| `theme` | Token values in scope (section 10) | At template expansion, and again when a token changes |
| `viewport` | `width`, `height` and the viewport's anchors | At resolution |
| `host` | Inside a decoration: the box of the container it decorates (`width`, `height`, `left`, `right`, `top`, `bottom`, `center_x`, `center_y`), in its frame | At resolution |
| A node path | `x`, `y`, `width`, `height`, `left`, `right`, `top`, `bottom`, `center_x`, `center_y`, and anchors | At resolution | A geometry reference is a dependency. It joins the attachment DAG, and a cycle is an error.

## 10. Styling

Look comes from theme tokens and named styles, applied explicitly. There are no selectors.
- A **theme** is a file of named tokens: colors, stroke widths, fonts, and the rendering mode (clean or hand-drawn stroke). `base_theme` selects the root set.
- A token is referenced by expression: `fill: "=theme.primary"`.
- A **style** is a named bundle of properties, declared in a library, a theme or the document, and applied with `style: emphasis`.
- A library declares the tokens it expects as part of its interface. A missing token is the error `token_missing`.
- Fonts are declared assets of the theme, mapped to font files. System fonts are never used, so two renders always match.

**Subtree token overrides.** Any node can redefine tokens for itself and its whole subtree. The nearest ancestor wins.
```yaml
- use: uml.class
  id: order
  theme:
    primary: "#c0392b"
```

- An override crosses template boundaries. It is the sanctioned way to re-skin a library element locally, without a path into its internals.
- Only tokens cascade. Property values do not: setting `fill` on a group does not affect its children.
- A stroke is solid unless `stroke_dash` gives its dash and gap lengths, repeated from the start of the outline: `stroke_dash: [6, 4]`. It applies to every stroked primitive, connectors included; connector markers stay solid. A partly drawn stroke (`draw` below 1) ignores the pattern. Like any property, it can sit in a named style (`dashed: {stroke_dash: [6, 4]}`).
- Text properties are inherited through tokens such as `font_body`.
- A token override is a property. A state can set it and a transition can tween it, so switching a subtree from light to dark is an ordinary state change.
- A token reference stays live: when a token's value in scope changes, every property that references it is re-evaluated.

**Property precedence,** highest first: a value set on the node, then the node's named style, then the template's default.

## 11. States

A state is a named set of overrides on the base scene. States are declared only inside a state container, and any node can own one.

**State containers.** The container kind defines the topology and the navigation verbs the harness gets. `goto` works in all of them.
| Container | Topology | Harness verbs |
| --- | --- | --- |
| --- | --- |
| `sequence` | Implicit order | `next`, `prev` |
| `tree` | Parent and children | `enter`, `up`, `sibling` |
| `graph` | Declared edges | `follow(edge)` |
| `bag` | None | `goto` only |

**A state** has an `id`, a `set` map from property path to value, an optional `extends`, and an optional `timeline` that plays while the scene sits in it.
```yaml
states:
  defaults: {duration: 0.7, ease: in_out}
  sequence:
    - id: the_harness
      set:
        agent.harness.state: focus
        agent.tools.present: true
        progress.step: 4
    - id: the_agent
      extends: the_harness
      set:
        agent.state: focus
        progress.step: 5
```

**What ****`set`**** can target:** any property, including `present`, `layer`, token overrides, another node's `state`, and the parameters of a template instance. Setting a parameter re-expands that instance, and keyed items keep their identity across the change.

**Inheritance between states**
- By default a state's overrides are relative to the base scene. States are then independent and safe to reorder.
- `extends: <state>` makes a state build on another one, explicitly.
- A `tree` child extends its parent implicitly.
- A `sequence` with `cumulative: true` makes each state extend the previous one. It is off by default.
- A resolved state is the fold of its `extends` chain over the base scene, oldest first. A cycle is an error.

**State as a property**
- The current state of a node's container is the property `state` on that node.
- A parent state can set it: `order.state: expanded`. Entering the parent state triggers the child transition.
- Precedence for `state`, highest first: a direct harness call, a parent state's `set`, the node's authored `state:`, then the first declared state.
- The scene's state is the set of current states across all containers. With no harness intervention, one root state and a time value determine every frame.
- A template exports its state names as part of its interface.

**Transitions** are covered in section 13. `defaults` sets the duration and easing of automatic ones, and a `transitions` list overrides specific edges.

## 12. Time and animation

Tracks are the single underlying model. After resolution, every animation is a set of property tracks with keyframes, and the engine, validator and exporter see nothing else.

**Three authoring forms** produce tracks.
| Form | Written as | Expands to |
| --- | --- | --- |
| --- | --- |
| Keyframes | A `track` on a property path with `keys` | Itself |
| Time containers | `sequence`, `parallel`, `stagger` composing clips | The clips' tracks, offset in time |
| States | Two states of a container | An implicit clip tweening every property that differs |
```yaml
timeline:
  loop: true
  parallel:
    - sequence:
        - {use: motion.travel, target: agent.pulse, along: agent.api, duration: 0.7}
        - {wait: 0.4}
    - track: agent.pulse.scale
      keys:
        - {at: 0.5, value: 1}
        - {at: 0.7, value: 1.8}
        - {at: 0.9, value: 1}
```

**Clips.** A clip is a template whose body is tracks and time containers, with a declared parameter schema: `motion.fade_in`, `motion.draw_stroke`. Libraries ship motion the same way they ship shapes.

**Time containers**
- `sequence` plays its children one after the other, `parallel` plays them together, `stagger` starts them at a fixed interval. `wait` is an empty span.
- Relative timing: `after: <id>` and `with: <id>` refer to a sibling. `at: <seconds>` is the absolute escape hatch within the scope.
- Timing references form a DAG, like attachments.
- A container's duration is derived: the sum for `sequence`, the maximum for `parallel`.

**Scoping.** Keyframe times are local to their enclosing scope: a clip, a transition, or a state's timeline. Times are global only at the root. A hand-keyframed clip can therefore be reused inside a `sequence` without rewriting its times.

**Interpolation.** Numbers and points interpolate linearly through the easing curve. Colors interpolate per channel in sRGB. Discrete properties step at the keyframe time. Easing is `linear`, `in`, `out`, `in_out`, or `{bezier: [x1, y1, x2, y2]}`.

**Blending.** Several tracks can drive the same property at the same moment.
- Numeric properties are additive. The resolved value is the base value plus the sum of contributions, and each track contributes a delta from the base.
- Authors write absolute targets. The engine converts them to deltas: a clip written as "move to 200" contributes `200 - base`, and a state transition contributes `target - base`, interpolated.
- `scale` composes by multiplication.
- Properties that cannot be summed use precedence: a raw track beats a clip, which beats a state transition. This covers text, `present`, `layer`, `state`, enumerations and colors.
- Any overlap, summable or not, emits the warning `blend_overlap`. An overlap never blocks a render.

The additive rule also applies when two state containers override the same numeric property. That case is flagged for review in section 21.

## 13. Transitions and per-frame evaluation

A transition between any two states is generated automatically by diffing their resolved scenes. An explicit transition is an optional override.

**Automatic transition**
- Resolve the target state.
- Diff it against the current resolved scene. Nodes are matched by path, and repeated items by key.
- Generate a track for every property that differs, using the container's `defaults`.
- Play.

`goto` therefore works between any pair of states, in any container.

**Explicit transition.** A `transitions` entry names an edge and gives a time container as its body. The clip `{auto: all}` stands for the automatic diff, so an override can wrap or reorder it.
```yaml
transitions:
  - from: the_harness
    to: the_agent
    sequence:
      - {use: motion.draw_stroke, target: agent, duration: 0.6}
      - {auto: all, duration: 0.5}
```

**Enter and exit.** A node present in one state and absent in the other pops by default.
- An exiting node vanishes at the start of the transition.
- An entering node appears at the end.
- Nothing shows while the layout is still making or closing room.
- An `enter` or `exit` clip on the node (fade, draw-stroke) replaces the pop. An exit clip starts at the beginning of the transition, and an enter clip starts when the geometry tweens end.

**Per-frame evaluation order**
- Evaluate tracks on primary geometry and properties.
- Re-evaluate attachments, geometry expressions and floating anchors, in DAG order.
- Route connectors.
- Container layout is not re-solved per frame. Boxes interpolate between the two resolved states.
- Followers and connectors are derived every frame, so they stay correct when their targets move on a curve.
- An orthogonal route can snap when the relative positions of its ends cross a threshold mid-transition. This is the correct behaviour under this rule, and smoothing can come later.

**Determinism.** A frame is a pure function of the canonical document, the asset manifest, the state history and the time value. The harness can seek, scrub and export video with identical results on every machine.

## 14. Camera

The camera is an ordinary root node with three animatable properties: `center`, `zoom` and `rotation`. The document's `viewport` is the frame it looks through.
```yaml
camera:
  fit: agent
  padding: 80
  into: {x: 780, y: 140, width: 1060, height: 800}
```

- `fit: <path>` with `padding` frames a node. It is sugar, resolved into `center` and `zoom` from the node's resolved box, so an author never computes them.
- `into` names the viewport region the framed node must fit in. It defaults to the whole viewport, and keeps screen-space content such as a caption clear.
- The camera can attach to an anchor, which gives a follow-cam.
- Camera properties are set from states like any others (`camera.fit: agent.engine`), and the camera can own a state container.
- A transition between two `fit` targets tweens the resolved `center` and `zoom`.
- Root layers with `space: screen` ignore the camera, and layers with `parallax` follow it partially (section 8).
- 0.1 has one camera.

## 15. Media and the asset manifest

Media are nodes plus tracks, and the set of media kinds is open. The one new concept is an asset manifest that keeps the resolver free of decoding.

**Media kinds as plugins.** A kind supplies three things.
| Part | Role |
| --- | --- |
| Probe | Reads an asset and writes its facts into the manifest |
| Property schema | Declares the kind's animatable properties |
| Draw routine | One per renderer |
- The first kinds are `image`, `audio` and `video`. 3D models, gaussian splats and live sources fit the same contract later.
- An image is a shape with an intrinsic size.
- Audio and video are clips on a local timeline. `volume`, `rate`, `in` and `out` are ordinary animatable properties.

**Asset manifest**
- A separate probe step produces the manifest. Canonicalization stays purely syntactic.
- The manifest holds declared facts about each asset: image dimensions, clip durations, audio cue points, font metrics.
- The resolver takes the canonical document plus the manifest and decodes nothing. Only renderers decode.
```yaml
assets:
  narration:
    kind: audio
    file: ./audio/narration.wav
    duration: 42.6
    cues: {step_1: 0.0, step_2: 9.4, step_3: 21.8}
  body_font:
    kind: font
    file: ./fonts/Inter-Regular.ttf
    metrics: {units_per_em: 2048, ascent: 1984, descent: -494}
```

**Audio as a timing reference.** Time containers can reference cue points, so visuals follow the sound.
- `at: narration.cue[step_2]` starts a child at a cue.
- `with: narration` spans the audio's duration.
- These are directed references on the time axis, the counterpart of attachments.
- A harness can generate speech, write the returned word or sentence timestamps as cues, and the visuals line up with no timing arithmetic.

**Live sources.** A kind such as a webcam is not a function of file and time. It declares itself `live`, and the exporter records it or substitutes a placeholder.

## 16. Core architecture

The core is a Kotlin Multiplatform library with no rendering dependency. Renderers are thin and draw a resolved scene.

**Portable core**
- Parser, processor, validator, resolver, track engine and lint all live in common code.
- The same document gives the same geometry on every target. A harness in any language can inspect the resolved scene.
- The JVM target comes first and carries headless rendering, the CLI and export. The browser target (JS or Wasm) comes with the harness API.
- The browser target is compiled in CI from milestone 0, even while nothing uses it, so target-specific problems surface early.

**Text measurement**
- Measurement lives in common code behind one interface. The resolver never asks the platform.
- Stage 1 uses simple metrics from the manifest: advance widths and line height. It is portable and adequate for Latin text.
- Stage 2 replaces the module with real shaping: a pure-Kotlin OpenType reader, or one shared shaping library on every target. No interface changes.
- Platform-native shapers are excluded. They would bring back the divergence this architecture avoids.
- Renderers must draw with the same font files the resolver measured.

**The resolver is a runtime dependency**
- States on any node make the set of state combinations a product, so states cannot be pre-resolved at build time.
- The harness can patch documents live, which needs resolution on the spot.
- The core therefore runs wherever a renderer runs, and it must be incremental: re-resolve an affected subtree without recomputing the scene.

**Suggested modules:** `model`, `processor`, `schema`, `resolver`, `anim`, `lint`, `render-svg`, `render-raster`, `cli`, `fixtures`.

**CLI commands**
| Command | Output |
| --- | --- |
| `canon` | Canonical document and source map |
| `validate` | Structural diagnostics |
| `probe` | Asset manifest |
| `resolve` | Resolved scene for a state set and time |
| `lint` | Geometric diagnostics |
| `dump` | Compact text dump of the resolved scene |
| `render` | SVG or PNG for a state set and time, or a frame sequence |
| `schema` | JSON Schema for a document's imports |

## 17. Harness API

The harness has four verbs. It reads the resolved scene and writes only to the canonical document.
| Verb | Does | Notes |
| --- | --- | --- |
| --- | --- |
| Read | Returns any resolved value by path: geometry, anchors, current states | Never emits an encapsulation warning |
| Observe | Subscribes to changes on a path, and receives pointer events as paths | Hit-testing is done by the core: `clicked: order.attributes[total]` |
| Patch | Edits the canonical document by path: set a property, add or remove a node, change a parameter | The core re-resolves the affected subtree |
| Drive | Sets states and seeks or plays time | Uses the verbs of each state container (section 11) |
- The resolved scene is read-only. There is no direct write to resolved geometry.
- Every visual state is explained by a document, so anything a harness builds live can be saved, diffed and replayed.
- Transient effects, such as a hover highlight or a drag in progress, are expressed as a harness-owned state or a token override. They stay inside the model.
- Interactive and generative logic lives here, in the host language, with its own tooling.

**Editors.** A GUI editor is a harness that emits patches. None is planned for the first versions: the toolset is text plus a live viewer. When an editor comes, each patch is mapped through the source map and applied to the authored file, preserving comments and formatting.

**Viewer.** The first harness is a live viewer: reload on file change, validator and lint findings shown in place, and the path of whatever is clicked.

## 18. Diagnostics

An LLM writes these files without seeing them, so what the toolset returns matters as much as the format. Three kinds of feedback stack: structural diagnostics, geometric lint, and a rendered image.

**Common shape.** Every finding is structured data.
```yaml
- code: text_overflow
  severity: warning
  path: order.title
  source: {file: model.yaml, line: 42, column: 9}
  message: "Text overflows its box by 14 units"
  data: {overflow: 14}
```

**Structural diagnostics,** from the processor and validator.
| Code | Severity | Raised when |
| --- | --- | --- |
| --- | --- |
| `schema` | error | A value does not match the core or a library schema |
| `expr_syntax` | error | An expression string does not parse |
| `path_unresolved` | error | A path matches no node or property |
| `attach_cycle` | error | Attachments or geometry references form a cycle |
| `extends_cycle` | error | A state's `extends` chain loops |
| `token_missing` | error | A library expects a token no theme in scope provides |
| `attach_ignored` | warning | An attached node sits in a `row`, `column`, `grid` or `stack` |
| `hint_ignored` | warning | A layout hint or option is set where the layout in charge does not declare it |
| `hint_conflict` | warning | A layout hint contradicts the edges |
| `tree_extra_edge` | warning | An edge of a `tree` gives a node a second parent or closes a loop |
| `route_backtrack` | warning | A fixed anchor faces away from the route a layout supplies |
| `encapsulation` | warning | A path reaches past a template's interface |
| `blend_overlap` | warning | Two sources drive the same property at the same time |

**Geometric lint,** run on the resolved scene. Severity is configurable per rule.
| Rule | Finds |
| --- | --- |
| `overlap_siblings` | Sibling shapes that overlap |
| `text_overflow` | Text larger than its box, or clipped |
| `out_of_view` | Nodes outside the canvas or the camera view |
| `connector_crosses_shape` | A connector running through a shape |
| `connector_crosses_connector` | Two connectors whose paths cross away from their ends |
| `text_too_small` | Text below a readable size at the current zoom |
| `low_contrast` | Text against its resolved fill | Lint is in the core from the first version. It works with text-only models, costs little because the resolved scene already holds every box and outline, and gives findings a model can act on.

**Rendered image.** The headless renderer returns a PNG for vision-capable models. It judges overall composition, which lint cannot.

**Scene dump.** `dump` prints one line per node with its path, box and state, so a model can reason about positions without an image.
```
scene.team           box=(120,340,220,96)   state=-
scene.player         box=(480,520,240,112)  state=-
scene.lineup         path=(230,436)->(600,520)
```

## 19. Fixtures and conformance

Every feature is specified by fixtures, so the coding agent has a pass or fail target at each pipeline stage. A fixture is a directory holding one input and the expected output of every stage.
| File | Checks |
| --- | --- |
| `input.authored.yaml` | The input, as a person or model would write it |
| `manifest.yaml` | The asset manifest, when the fixture uses assets or fonts |
| `expected.canonical.yaml` | The processor: exact structural equality |
| `expected.diagnostics.yaml` | The validator and lint: codes, severities, paths |
| `expected.resolved.json` | The resolver: geometry within a numeric tolerance |
| `expected.png` | The headless renderer: image comparison within a tolerance |
- **Negative fixtures** hold an invalid input and the diagnostics it must produce. They have no resolved or image files.
- **Animation fixtures** add a list of state sets and times, with one expected resolved scene and image per entry.
- **Round-trip check:** printing a canonical document back to authored form and processing it again must give the same canonical document.
- **Determinism check:** every fixture is resolved on each compiled target, and the resolved scenes must be identical.
- **Update mode** regenerates expected files from the current implementation. A person reviews the diff before it is committed.
- A feature is done when its fixtures pass. A change in behaviour arrives with a changed fixture.

The two sample documents, `rugby_model.yaml` and `llm_stack_deck.yaml`, become the first end-to-end fixtures once the open syntax questions in section 21 are settled.

## 20. Milestones

The build order differs from the conceptual layering: static before motion, and the browser late. Each milestone ends in something the fixture runner can verify.
| # | Milestone | Proves |
| --- | --- | --- |
| --- | --- |
| 0 | Full pipeline on a trivial scene: authored, canonical, resolved, headless render, fixture runner, browser target compiling in CI | The architecture end to end |
| 1 | All primitives, IDs and paths, layers, theme tokens, asset manifest with simple font metrics | Static drawing |
| 2 | Layout containers, anchors, directed attachments, connectors and routing, geometric lint | Diagrams without coordinates |
| 3 | Templates with parameter schemas, closed expressions, libraries, JSON Schema generation | The LLM generation loop, and a first real library |
| 4 | State containers, `extends`, state as property, automatic transitions, tracks, blending | Decks and step-by-step explainers |
| 5 | Time containers, clips, keyframes, camera, video export | Animation |
| 6 | Browser target, harness API, live viewer | Interactivity |
| 7 | Media kinds and audio cues | Narrated pieces |
| 8 | Graph layouts: the layout contract, `layered`, `tree`, `circular`, `force`, the `layout` router ([addendum](graph-layouts.md)) | Graphs without coordinates |
- Milestone 3 is already a usable product: LLM-generated diagrams from typed libraries. It tests the riskiest thesis early.
- The track model is specified up front, so nothing in milestones 1 to 3 blocks it.
- Headless rendering on the JVM carries everything through milestone 5.
- Milestones 4 and 5 are where interactions compound: overlapping containers, blending, per-frame re-evaluation. They need the densest fixtures.

**Before milestone 0.** Two checks cost almost nothing and can change the syntax:
- Write a third sample by hand: a 20-second animated scene, to test verbosity for continuous motion.
- Give several models, including small local ones, a cheat sheet and the samples, and ask them to write new documents. Review what they get wrong.

## 21. Open questions

Nine rules in this draft were assumed while writing the samples and the spec, and four decisions are likely to be revisited. Each needs a yes or no before the syntax is frozen.

**Assumed, needs confirmation**
| # | Rule as written | Section | Why it is open |
| --- | --- | --- | --- |
| --- | --- | --- |
| 1 | Presence is a property, `present: false` in the base scene | 5 | Needed because states override a base scene; never discussed |
| 2 | Nodes without an ID are transparent in paths | 5 | Keeps paths short; makes ID uniqueness span unnamed containers |
| 3 | References on a node resolve from its enclosing scope outward; state paths resolve from the container's owner | 5 | Two scoping rules in one language |
| 4 | A keyed list item is a one-key map, `- total: Money` | 9 | Compact, but ambiguous with a real one-key object |
| 5 | Camera `into` region | 14 | Added to keep a caption area clear |
| 6 | Anchors use compass names | 6 | The deck sample uses `bottom_right`; one vocabulary must win |
| 7 | Expressions include comparison, boolean operators and string concatenation; `==` escapes a literal leading `=` | 9 | `if` and derived labels need them; the agreed set named only arithmetic, references, `min` and `max` |
| 8 | Exit clips start with the transition, enter clips start when geometry tweens end | 13 | Only the default pop was decided |
| 9 | Unlayered children go to a `default` layer that paints first | 8 | Only explicit layers and declaration order were decided |

**Likely to be revisited**
- **Additive blending for state overrides.** Summing deltas suits clips layered on motion. For two states that each set an absolute target on one property, the sum is rarely what the author meant. A probable end state is precedence for states and additive for clips.
- **Interrupted transitions.** A `goto` during a transition is undefined. A candidate rule: the in-flight values become the source of a new automatic diff.
- **Color interpolation space.** sRGB per channel is the simplest choice and gives muddy midpoints for some pairs.
- **Simple font metrics.** They make `text_overflow` unreliable for kerning-heavy fonts and non-Latin scripts until real shaping lands.

**Deferred by decision**
- A library registry, namespacing beyond aliases, and version pinning.
- Free relational placement, obstacle-avoiding routing, the `curved` router.
- GUI editors and write-back to authored source.
- Multiple cameras, and layer options below the root.
- Library-level relation rules.

## 22. Decision log

The 31 decisions taken on 17 November 2026, in the order they were made.
| # | Topic | Decision | Rationale |
| --- | --- | --- | --- |
| --- | --- | --- |
| 1 | Placement | Absolute coordinates and layout containers; no free relations | LLMs are weak at coordinates; containers need no general solver |
| 2 | Binding | Anchors bind positions through directed attachments | A DAG resolves in one pass; symmetric binding needs a constraint solver |
| 3 | Attachment policy | `follow`, `stretch`, `block`, set on the attacher | Per-shape control of how a node reacts when its target moves |
| 4 | Connectors | A connector is a stretch shape with two attached anchors | One mechanism; connector-to-connector and midpoint labels come free |
| 5 | Anchor kinds and routing | Fixed and floating anchors; two-pass routing; `straight` and `orthogonal` | UML and Archimate are unusable without floating anchors |
| 6 | Types | The template's parameter schema is the type | Semantic data stays addressable and validatable without a model layer |
| 7 | Computation | Closed constructs in the file; all logic in the harness | Files terminate, can be checked statically, and frames stay deterministic |
| 8 | Time forms | Keyframes, states and time containers, all expanding to tracks | Each suits a different kind of motion; one engine underneath |
| 9 | Time scope | Keyframe times are local to their enclosing scope | Edits stay local and clips are reusable |
| 10 | Blending | Additive with an overlap warning; precedence for non-summable properties | Layered motion needs sums; an overlap never blocks a render |
| 11 | State containers | `sequence`, `tree`, `graph`, `bag`; `goto` works in all | The container kind gives the harness its navigation verbs |
| 12 | State location | Any node can own a state container | Libraries ship shapes with states; camera and content steps separate |
| 13 | State coordination | A node's current state is a property a parent state can set | Self-playing files, with no new mechanism |
| 14 | State inheritance | Base by default; explicit `extends`; implicit for `tree`; `cumulative` flag for `sequence` | Reorder-safe by default; build-style decks cost one flag |
| 15 | Addressing | Local IDs, keyed items, paths; interface plus warning | Stable identity for attachments, diffs, tracks and the harness |
| 16 | Serialization | Data model first; strict YAML subset; JSON equivalent; JSON Schema per library | Validation, completion and constrained decoding with no custom tooling |
| 17 | Expressions | Strings when authored, structured when canonical | Readable to write, fully checkable once processed |
| 18 | Processor output | A separate canonical document; the source is never edited | In-place rewriting is too dangerous |
| 19 | Styling | Theme tokens and named styles; subtree token overrides | Explicit and local, with re-skinning and no selectors |
| 20 | Resolver | A portable core with fonts as explicit inputs | The same geometry on every machine |
| 21 | Stack | Kotlin Multiplatform; the resolver runs at runtime | State combinations and live patches cannot be pre-resolved |
| 22 | Harness writes | Patches to the canonical document only | Everything on screen is explained by a document |
| 23 | Editor | None now; later as harnesses that write back to authored source | The LLM and a live viewer are the first editing interface |
| 24 | Diagnostics | Structural, geometric lint and rendered image, with headless render at step 0 | An agent implements this and needs verifiable output at each stage |
| 25 | Media | Asset manifest, audio cue points, open set of media kinds | The resolver stays pure; narrated pieces need no timing arithmetic |
| 26 | Build order | Static first, motion next, browser late | A usable product at milestone 3; the riskiest thesis tested early |
| 27 | Paint order | Named layers and declaration order, local to a context; no z-order | Simple to write and to reason about |
| 28 | Enter and exit | Pop unless a clip is given | A predictable default |
| 29 | Geometry in transitions | Re-route connectors every frame | Interpolated paths look wrong when spatial relations change |
| 30 | Imports | Local file references behind aliases | Enough for now; a registry can follow without changing documents |
| 31 | Units, axes, camera | Logical pixels, top-left origin, y down; camera with `fit`, attachment, screen-space layers | Matches SVG, Canvas and what models have learned |
| 32 | Dashed strokes | `stroke_dash`, a list of dash and gap lengths, on every stroked primitive (24 November 2026) | UML and most notations tell relations apart by dashed lines; a double outline or a muted colour is no substitute |
| 33 | Slot scope | What fills a slot belongs to the instance's scope, under a named part of the body or not; a clash with a part is an error | Container templates need named parts around their slots; users address what they put in |
| 34 | Minimum sizes | `min_width`, `min_height` on containers | Invisible spacer shapes were the only way to reserve room, and lint saw them |
| 35 | Expressions | `choose(cond, a, b)` and `count(list)` | Variants of a template were duplicated nodes under two `if`s; shares of a width needed a fixed number of parameters |
| 36 | Template extension | `extends` and `set`, within a library | Relation families repeated the base's whole parameter list in every member |
| 37 | Outlines | A template's outline may be one of its parts, a polygon included | Arrows stopped on the bounding box of diamonds |
| 38 | Reserved IDs | An ID avoids geometry, anchors, common properties and the properties of its scope only | Reserving every property of every kind refused `start`, `fill` or `ordering` where no path was ambiguous |
| 39 | Labels on connectors | `side` on an attachment to a connector point; the engine picks the anchor facing the line | Two libraries tuned label anchors and gaps by hand for each direction of line |
| 40 | Points along connectors | `along` on an attachment to a connector point | Multiplicities and roles were placed with negative gaps |
| 41 | Decorations | `underlay` and `overlay` on containers, with the `host` reference root | Shapes drawn from the size of what they surround needed a hidden stretched shape, a separate container and a layer to avoid a cycle |
| 42 | Sequence layout | The `sequence` layout kind ([addendum](sequence-layout.md)): participants side by side, one row per message in order, fragments sized from what they hold | Step lists shared by every lifeline and fragments placed by expressions were the UML library's worst friction |
| 43 | Unnamed instances | Transparent for what fills their slots; their parts stay hidden | Messages nested in an unnamed fragment must be named from outside it |
| 44 | Layout hints of a kind | In one map property (`sequence: {...}`), not one property per hint | A property of every node is a parameter name no library can use: `role` broke Merise's legs |
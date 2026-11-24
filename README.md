# diagrams

A declarative language, written as YAML, for drawings, diagrams, slide decks and animations, and
the toolset that validates, resolves and renders it. The design is in [docs/spec.md](docs/spec.md)
(draft 0.1, snapshot of the shared spec) and its addendum [docs/graph-layouts.md](docs/graph-layouts.md).

## Status

Milestones 0 to 7 (spec section 20) are in place: the full pipeline, every static primitive, layers,
theme tokens, named styles, an asset manifest, layout containers, anchors, attachments, connectors
with `straight` and `orthogonal` routing, geometric lint, templates and libraries with JSON Schema
generation, state containers with transitions, slide decks, timelines with keyframes, time containers
and clips, the camera, video export, the harness API compiled for the browser with a live viewer,
and media (images, audio, video) with audio cue points as timing references. Milestone 8 adds the
graph layouts of the addendum: `layered`, `tree`, `circular` and `force` containers, the `layout`
router and the `label` anchor of connectors.

| Stage | Module | State |
| --- | --- | --- |
| Strict YAML subset parser with source positions, YAML/JSON writers | `model` | done |
| Expressions: authored `=` strings, canonical structured form, printer, evaluator, partial evaluation | `model` | done |
| Processor: authored to canonical, imports, templates, clips and timelines, states, camera, media; validator; printer; JSON Schema | `processor` | done |
| Asset manifest, pure-Kotlin OpenType reader, text metrics, media probes (PNG, JPEG, GIF, SVG, WAV with cues, MP4/MOV) | `assets` | done |
| Resolver: states, timelines and blending, layouts, graph layouts, attachments, connectors, markers, tokens, styles, fonts, camera, media, transitions; `probe` | `resolver` | done |
| Geometric lint: every rule of spec section 18 (`overlap_siblings` off by default) | `lint` | done |
| SVG renderer (common code), PNG renderer (Java2D) | `render-svg`, `render-raster` | done |
| Harness API (read, observe, patch, drive), exported to JavaScript as ES modules | `harness` | done |
| CLI: `canon`, `validate`, `probe`, `resolve`, `lint`, `dump`, `render`, `states`, `deck`, `video`, `serve`, `schema` | `cli` | done |
| Fixture runner with update mode, round-trip check and animation frames | `fixtures` | done |

The core modules are Kotlin Multiplatform (JVM and JS); the raster renderer, CLI and fixtures run on
the JVM, and the live viewer runs the core in the browser.

## Build

Needs a JDK 21 or newer.

```bash
./gradlew check
```

```bash
./gradlew :cli:installDist
```

The CLI is then `cli/build/install/diagrams/bin/diagrams`:

```bash
diagrams render fixtures/cases/m1_primitives/input.authored.yaml --out primitives.png --scale 2
```

```bash
diagrams validate my.yaml --diagnostics yaml
```

For embedding the core in another application, `./gradlew :embed:dist` writes to `embed/build/embed`:

- `jvm/diagrams-embed-<version>.jar`: one jar with the core and the raster renderer, the Kotlin
  standard library relocated under `diagrams.shaded.kotlin`, so it needs nothing else on the classpath;
- `js/diagrams-embed.mjs`: one self-contained ES module exporting `Viewer`, with its TypeScript
  definitions (`diagrams-embed.d.mts`) and a `package.json`.

## Vaadin

The `vaadin` modules put the viewer in Vaadin 25 applications. The core still runs in the browser;
the server only gathers the document's files and drives the viewer.

| Module | What it is |
| --- | --- |
| `vaadin:viewer` | `DiagramViewer`, a Vaadin component around the `<diagrams-viewer>` element: bundles loaded from a `DiagramFiles` source (a directory, the class path, memory) with their assets served by the component, every harness operation (drive, patch, read), and events for loads, clicks, states, findings and the clock |
| `vaadin:karibu` | karibu-dsl builders (`diagramViewer { }`), `onXxx { }` listeners, the viewer's state as a `StateFlow`, and `bindDocument`/`bindBundle`/`bindText`-style bindings |
| `vaadin:demo` | A Vaadin Boot webapp: the repository's examples beside a live YAML editor, with states, selection and findings |

```bash
SERVER_PORT=8091 ./gradlew :vaadin:demo:run
```

The first start builds Vaadin's development bundle, which takes a minute or two.

## Themes, styles and fonts

A theme is an imported file with `tokens`, font `assets` and `styles`; `base_theme` picks the one whose
tokens are in scope at the root. A library file may declare `styles` too.

```yaml
# themes/paper.yaml
format: diagrams/0.1
assets:
  body: {kind: font, file: ../fonts/AtkinsonHyperlegible-Regular.ttf}
tokens:
  background: "#faf8f3"
  primary: "#2d6cdf"
  font_body: body
styles:
  card: {fill: "=theme.surface", stroke: "=theme.stroke", corner_radius: 8}
```

```yaml
# a document
imports: {paper: ./themes/paper.yaml, dark: ./themes/dark.yaml}
base_theme: paper
styles:
  pill: {corner_radius: 18, fill: "=theme.primary"}
scene:
  children:
    - {shape: rect, width: 120, height: 60, style: [paper.card, pill]}
    - {theme: dark, children: [...]}                 # every token of another theme
    - {theme: {primary: "#c0392b"}, children: [...]}  # single overrides
```

Text is measured with the font named by its `font` property, else the `font_body` token in scope.
`diagrams probe doc.yaml --out manifest.yaml` reads the declared font files and writes their metrics;
`resolve`, `lint` and `render` take `--manifest`, or probe on the fly without it. Both renderers draw
each character at the advance the resolver measured, with the same font file (embedded in SVG output).

## Graph layouts

A container with `layout: layered`, `tree`, `circular` or `force` places its children from the
connectors between them ([docs/graph-layouts.md](docs/graph-layouts.md)):

```yaml
- id: flow
  layout: layered            # direction: down | right | up | left, gap, rank_gap, ordering
  children:
    - {shape: rect, id: draft, width: 100, height: 40}
    - {shape: rect, id: review, width: 100, height: 40}
    - {shape: rect, id: publish, width: 100, height: 40, rank: last}
    - {shape: connector, from: draft, to: review, marker_end: arrow}
    - {shape: connector, from: review, to: publish, marker_end: arrow}
    - {shape: connector, id: direct, from: draft, to: publish, marker_end: arrow}
    - {shape: text, text: fast track, attach: {to: direct.label}}
```

```bash
diagrams render examples/layouts.yaml --out layouts.png --scale 2
```

- The nodes are the children, each as one box; attached children and instances of relation
  templates are not nodes. The edges are the connectors between two different children, wherever
  they are declared; an end deeper than a child is lifted to it.
- A connector's `route` defaults to `layout`: the route the container supplies (`layered`, `tree`),
  `straight` where there is none. `straight` and `orthogonal` override it for one connector.
- `<connector>.label` is where a label goes: the room a `layered` container reserved for it, the
  middle of the connector anywhere else.
- Hints are per kind: `rank` (`first`, `last`, `{same_as: node}`) on a node and `constrain: false`
  on a connector for `layered`, `pinned: {x, y}` on a node for `force`. One set where it means
  nothing is reported (`hint_ignored`), as is one that contradicts the edges (`hint_conflict`).
- `force` gives the same picture for the same document on every target: fixed seed and step count,
  and arithmetic limited to the operations IEEE 754 rounds correctly.
- `libs/flow.yaml` has the two templates a graph needs: `step` (a box with a line of text) and `link`
  (a connector with an optional label that follows the supplied route).

Choices made where the addendum is silent:

- Layered: cycles are broken by a depth-first walk in declaration order; ranks are longest-path,
  with sources pulled next to what they lead to; crossings are reduced by 24 barycenter sweeps, each followed by
  exchanges of neighbours, keeping the best order; coordinates come from a weighted isotonic regression per rank, and long
  edges are then straightened where nothing is in the way. Routes are polylines.
- A relation template has to pass `route: layout` through to its connector to follow supplied
  routes; `libs/uml.yaml` accepts it and keeps `orthogonal` as its default.
- In a `force` container, `link_distance` is measured between outlines, nodes that an edge joins
  do not repel each other, repulsion stops at three link distances, and a weak pull toward the
  middle keeps separate groups together. 300 steps. The simulation does not minimise crossings: to
  pick a `seed`, lint a few and keep one without `connector_crosses_connector` warnings.
- Edges between the same two nodes bow apart in every graph kind (addendum, section 2), so a
  reflexive association needs no fixed anchors.
- A child of a graph container without a slot (an instance of a relation template) stays in the
  container's frame: its `at` is not read.
- In a transition frame the ends of an interpolated route are taken again on the interpolated
  outlines; points of the route that fall inside an end's box are dropped.

## Templates and libraries

A library is an imported file with `templates` (and optionally `styles`). A template declares a
parameter schema, an interface and a body; an instance passes parameters as sibling keys of `use`.
[libs/uml.yaml](libs/uml.yaml) (UML class, object, package, component, deployment, use case, activity,
state machine and sequence diagrams; one example per family in `examples/uml_*.yaml`), [libs/merise.yaml](libs/merise.yaml)
(the Merise data, processing and flow models; [examples/merise_festival.yaml](examples/merise_festival.yaml)
is an MCD placed by a `force` container) and [libs/deck.yaml](libs/deck.yaml) (headings, bullets,
cards, badges, progress dots) are complete examples.

```yaml
templates:
  card:
    params:
      title: {type: string}                       # no default: required
      items: {type: list, keyed: true, item: {type: string}, default: []}
      wide: {type: boolean, default: false}
    exports: {anchors: [north, south], parts: [heading], states: [], tokens: [ink]}
    outline: box                                  # or ellipse, for floating anchors
    body:
      layout: column
      children:
        - {shape: text, id: heading, text: "=params.title", fill: "=theme.ink"}
        - {if: params.wide, shape: rect, width: 300, height: 2}
        - for_each: params.items                  # each item: a key and a value
          as: it
          id: row                                 # items are addressed as card.row[key]
          do: {shape: text, text: "=it.key + ': ' + it.value"}
        - {slot: default}                         # the instance's children go here
```

```yaml
- {use: lib.card, id: order, title: Order, items: [total: Money, date: Date], at: {x: 40, y: 40}}
```

`diagrams schema doc.yaml` prints the document's JSON Schema: the core schema plus one definition per
template, every scalar also accepting an `=` expression. Paths that reach inside an instance past its
exported parts resolve, with an `encapsulation` warning.

## States, transitions and decks

Any node can own a state container. A state is a named set of overrides, by path from the owner:

```yaml
scene:
  states:
    defaults: {duration: 0.6, ease: in_out}       # linear, in, out, in_out, {bezier: [x1, y1, x2, y2]}
    sequence:                                     # or tree (nested children), graph (edges), bag
      - {id: intro, set: {title.present: true}}
      - {id: detail, extends: intro, set: {card.fill: "=theme.accent", steps.current: 2, panel.state: open}}
    transitions:
      - {from: intro, to: detail, duration: 1.2}
```

A state can set any property (`present`, `fill`, `at.x`, `theme.<token>`, `attach.gap`...), another
container's `state`, or a template parameter (the instance expands again). Keys with brackets must be
quoted inside `{...}`: `"list.item[1].present": false`.

```bash
diagrams states deck.yaml                                     # containers and their states
diagrams render deck.yaml --state detail --out detail.png     # one state ('owner=state,...' for several)
diagrams render deck.yaml --from intro --state detail --time 0.3 --out frame.png
diagrams deck deck.yaml --out deck.html                       # one slide per state, animated transitions
diagrams deck deck.yaml --out deck.pdf                        # one page per slide
diagrams deck deck.yaml --out slides/ --format png            # one file per slide
```

The HTML deck is self-contained (fonts embedded): arrow keys, space or a click move between slides,
`f` toggles full screen, and `#3` in the URL opens slide 3. [examples/agent_deck.yaml](examples/agent_deck.yaml)
is a short deck; [examples/llm_stack_deck.yaml](examples/llm_stack_deck.yaml) is a 25-step one
(model, engine and harness; context, tool calls and agents; knowledge graphs; embeddings).

## Timelines, clips and video

Any node can hold a `timeline`, which plays on the scene clock with paths written from that node;
a state's `timeline` plays while the scene sits in the state, from when it was entered. A time item
is one of `track`, `sequence`, `parallel`, `stagger` (with `interval`), `wait` or a clip (`use:`), and
may carry `id`, `at` (seconds, or a cue such as `"narration.cue[intro]"`), `after: <sibling>` or
`with: <sibling or media node>`. `loop: true` on a timeline repeats it.

```yaml
scene:
  timeline:
    sequence:
      - stagger: [{use: motion.fade_in, target: a}, {use: motion.fade_in, target: b}]
        interval: 0.25
      - {use: motion.draw_stroke, target: link}
      - {use: motion.travel, target: dot, along: link, id: go}
      - {use: motion.pulse, target: b, with: go}
      - track: a.fill
        keys: [{at: 0, value: "#b2bec3"}, {at: 0.5, value: "=theme.accent", ease: in_out}]
```

A clip is a template whose body is a time item. [libs/motion.yaml](libs/motion.yaml) has `fade_in`,
`fade_out`, `draw_stroke`, `pop_in`, `pulse`, `color_to`, `move` and `travel` (along a connector).
`enter` and `exit` take a clip that acts on its own node, and a transition can have a body where
`{auto: all}` places the automatic diff:

```yaml
transitions:
  - {from: one, to: two, sequence: [{use: motion.pulse, target: box}, {auto: all}]}
```

The camera (`camera: {fit: stage.a, padding: 16, into: {x, y, width, height}}`) can own states,
scene states can set `camera.fit`, and tracks can drive `camera.zoom` and `camera.center`. Root
layers take `space: screen` (ignores the camera) and `parallax: <factor>`.

```bash
diagrams render scene.yaml --time 2.5 --out frame.png         # the scene clock at 2.5 s
diagrams video scene.yaml --out scene.mp4                      # the timeline, through ffmpeg
diagrams video scene.yaml --out scene.gif --fps 15 --scale 0.5 # no ffmpeg needed
diagrams video deck.yaml --walk scene --hold 2 --out deck.mp4  # every state, then its transition
```

[examples/token_flow.yaml](examples/token_flow.yaml) is a 10-second animated explainer.

## Live viewer and harness

```bash
diagrams serve examples/llm_stack_deck.yaml    # then open http://127.0.0.1:8737/
```

The server only serves files (loopback only): the page, the core compiled to ES modules, and a
bundle of the document with its imports and asset manifest. Processing, resolution, lint and
rendering run in the browser. The page reloads when a file changes, lists validator and lint
findings with their source positions and outlines their nodes, shows the path of what is clicked,
drives every state container with the verbs of its kind (arrow keys for the scene's sequence), plays
and scrubs the scene clock, plays audio in sync, and saves the canonical document.

The same API is `diagrams.harness.Harness` in common code, and `Viewer` from
`lib/diagrams-harness.mjs` in the browser (`window.viewer` in the viewer page):

| Verb | Harness | Notes |
| --- | --- | --- |
| Read | `read("card.box")`, `read("card.north")`, `read("order.state")`, `read("camera.zoom")` | Viewport coordinates; never warns |
| Observe | `observe(path) { value -> }`, `onPointer { type, path -> }`, `hit(x, y)` | Hit-testing in the core |
| Patch | `patch([Set("card.fill", "#c0392b"), Add("scene", node), Remove("card")])` | Edits canonical, processes again |
| Drive | `goto`, `next`, `prev`, `enter`, `up`, `sibling`, `follow`, `seek`, `play`, `pause`, `tick(dt)` | Transitions play on `tick` |

## Media

Documents, themes and libraries declare assets: `assets: {photo: {kind: image, file: media/x.png}}`.
`probe` records image sizes, durations, and the cue points of WAV files (`cue ` and `labl` chunks);
a `<file>.meta.yaml` next to a file adds or replaces facts (`duration`, `cues`, `width`, `height`),
which is how a harness writes speech timestamps as cues.

```yaml
- {media: image, id: photo, asset: photo, width: 200}             # height from the aspect ratio
- {media: video, id: movie, asset: clip, width: 160, start: 1}
- {media: audio, id: narration, asset: voice, start: 0.5}
```

`media: image | video` draw into their box (`fit: contain | cover | fill`); audio and video play on
the scene clock from `start`, from their `in` to their `out` point, at `rate` and `volume`. In a
timeline, `at: "narration.cue[engine]"` starts at a cue and `with: narration` spans the audio.
`video` exports mix the audio nodes in; frames of video nodes are decoded with ffmpeg when it is
installed, and drawn as a placeholder otherwise (always in the browser viewer).

## Fixtures

Each directory under `fixtures/cases` holds `input.authored.yaml` and, when the document declares fonts,
`manifest.yaml` (checked against a fresh probe), plus the expected output of every
stage: `expected.canonical.yaml`, `expected.diagnostics.yaml`, `expected.resolved.json`,
`expected.png` (and `expected.svg`, for reviewers only). A feature is done when its fixtures pass.
Animation fixtures add `frames.yaml`, a list of `{name, states, from, time}` entries, each with its own
`expected.<name>.resolved.json` and `expected.<name>.png`. The documents under `examples/` must
resolve in every state of the scene with no diagnostic and no lint finding.

Regenerate expected files after an intended change, then review the diff before committing:

```bash
DIAGRAMS_UPDATE=1 ./gradlew :fixtures:test
```

## Choices made where the spec is silent

- Rotation and scale pivot on the center of the node's local box.
- Canonical strings keep the `==` escape, so canonical stays a valid authored document.
- Non-spec diagnostic codes: `yaml_syntax`, `yaml_subset` (outside the strict subset), `unsupported`
  (a construct this toolset cannot do yet), `expr_eval` (evaluation failure such as division by zero)
  and `timing_cycle` (timing references or clips in a loop).
- Diagnostic paths and the dump use reference form (`card.title`, root `scene`), not `scene.card.title`.
- Plain `y`, `n`, `~`, `Null` and similar are rejected as ambiguous; keys are always strings.
- Text that names no font (no `font`, no `font_body` token) is measured with built-in Helvetica
  advance widths and drawn with the closest platform font, so it can differ between machines.
- Theme files: keys `format`, `title`, `imports`, `tokens`, `assets`, `styles`, `templates`. Token
  values are literals (no expressions in themes); asset names are shared by all of a document's
  themes, and the manifest is keyed by them. Nested imports are not read yet.
- Document `styles` are used by bare name, imported ones as `alias.name`; `style` takes a name or a
  list (later wins). A style may not set `id`, `shape`, `layout`, `children`, `layers`, `theme`,
  `style` or states. Canonical form leaves out the defaults of properties a style sets; the resolver
  applies the style, so precedence is node value, then style, then default.
- `theme` on a node is an import alias (all of that theme's tokens) or a map of overrides; override
  values may be expressions over the parent's tokens. The root `background` token sets the canvas
  colour.
- Manifest font metrics: `units_per_em`, `ascent`, `descent` (from `hhea`), `line_gap`,
  `default_advance` (`.notdef`), and `advances` as runs of consecutive code points up to U+FFFF. Line
  height is ascent − descent + line gap. No kerning or ligatures, in measurement or drawing.
- Non-spec code `asset_missing`: a font the manifest lacks, or a font file probe cannot read.
- `low_contrast` uses WCAG ratios (4.5:1, or 3:1 from 24 units); the backdrop is every earlier filled
  shape whose axis-aligned box covers the text, composited over the background.
- The resolver is demand-driven: a node's size, placement and world transform, and a connector's
  route, are computed on first use and cached, so attachments and geometry references are resolved
  in dependency order without building the graph first. Re-entering a value still being computed is
  `attach_cycle`, reported on the node where the cycle was entered; that value falls back to zero.
- A free container's size ignores children placed from other nodes: connectors, attached nodes and
  nodes whose `at` references a node. A container's size never depends on what is attached inside it.
- Layouts: `align` is the cross axis for `row` and `column`, and both axes (within the cell) for
  `grid` and `stack`. `stretch` resizes rectangles, ellipses and containers on the stretched axes, and
  text widths; other shapes keep their size. There is no main-axis justification. `padding` is a
  number or a map of `top`, `right`, `bottom`, `left`, `x`, `y`. A rotated or scaled child takes the
  slot of its rotated bounding box. Connectors are never laid out.
- Geometry references (`=a.right + 20`) give coordinates in the frame of the referencing node's
  parent, the frame its own `at` is written in; `width` and `height` are the target's own size.
  `a.<anchor>.x` and `.y` read anchors. Paths resolve from the node itself, then its named ancestors,
  then the root; `scene.` is absolute.
- Attachments: `anchor` defaults to `center`. `to` with no anchor meets the opposite anchor of the
  attacher (`{anchor: west, to: a}` sits right of `a`), or a connector's `mid`. `gap` follows the
  target anchor's outward direction (none for `center`: use `offset`); `gap` and `offset` are in the
  parent's frame. `policy: stretch` is reserved for connectors; `block` has no effect.
- Connector anchors: `start`, `end` (outward along the path), `mid` (halfway along the path, with
  the left normal as outward direction: above a left-to-right segment) and `label` (`mid`, or the
  room a layered container reserved).
- Outlines: ellipses and circles use their ellipse, every other node its box. A floating end on a
  connector is its `mid`.
- `orthogonal` exits from side midpoints (east or west when the ends are further apart horizontally),
  meets in the middle with two bends, uses one straight segment when the ends' spans overlap, and
  steps 20 units out when both ends leave the same way. No obstacle avoidance.
- Geometry property and anchor names (`top`, `left`, `center`, `north`, `mid`...) are reserved and
  cannot be IDs, since `a.top` would be ambiguous.
- `text_overflow` flags lines wider than an explicit text `width`, and text leaving the topmost
  filled shape under its center. `connector_crosses_connector` reports each crossing
  pair once, on the later connector, and ignores crossings within 2 units of an end, where
  connectors that share a node meet. `connector_crosses_shape` checks boxes and skips shapes that hold
  either end of the connector.
- Templates: parameter types `string`, `number`, `boolean`, `color`, `enum` (`values`), `path` (a
  node or anchor path, checked from the instance), `list` (`item`, `keyed`), `object` (`fields`) and
  `any`; `name: string` is shorthand for `{type: string}`. A parameter without a default is required.
  Instance keys that are neither parameters nor node properties are errors.
- Expansion happens in the core (`Expander`), used by the validator for path checks and by the
  resolver. Canonical form keeps instances intact with parameter defaults filled in; an instance's
  common properties get no defaults, because the body's values are the template's defaults. The
  instance's `id`, `at`, `attach`, `layer`, `opacity`, ... go onto the body root; `theme` maps merge
  and `style` lists concatenate.
- Inside a body: `params.x`, loop variables and `index` (the 0-based loop position) are substituted
  and constant expressions folded; tokens, the viewport and geometry stay for the resolver. A keyed
  list's loop variable has `key` and `value`; a `for_each` with an `id` names its items `id[key]`
  (`id[0]`, `id[1]`... for an unkeyed list). `if` and `for_each` may be written without the leading
  `=`. Bare style and template names in a body mean the library's own.
- Encapsulation: a path written outside an instance that steps into its body warns unless the step
  is from the instance to one of its exported `parts`. Paths written inside the body never warn.
  Custom anchors (`port_2`) are not supported: `exports.anchors` lists compass anchors only.
- An instance is a scope even without an `id`: IDs inside its body (the `line` of every `deck.link`)
  never leak into the surrounding namespace, so several unnamed instances of one template coexist.
- Non-spec code `template_cycle`: templates that use each other in a loop.
- Not in the spec, added for usable diagrams and decks:
  - `marker_start` and `marker_end` on connectors (`arrow`, `open_arrow`, `triangle`, `diamond`,
    `open_diamond`, `circle`, `open_circle`), sized from the stroke width. The line stops at a closed
    marker. The spec builds markers from templates; that needs nodes that turn with the connector,
    which 0.1 cannot express.
  - `wrap: true` on text with a `width`: greedy word wrap at spaces.
  - `attach.to` may name a viewport anchor (`viewport.north`), so slide titles need no coordinates.
- States: a container is `states: {defaults, sequence|tree|graph|bag, cumulative, transitions}`; a
  graph state declares `edges: {name: target}`. Defaults: duration 0.5 s, ease `in_out`. `set` keys
  resolve from the owner; a bare property (`present`, `theme.primary`) is the owner's own. When two
  containers set the same property, the later one in the tree wins, with `blend_overlap`
  (precedence rather than the additive rule; spec section 21 expects this). A parent's `set` of a
  child's `state` is applied until the selection is stable.
- Transitions interpolate the two resolved scenes: nodes match by path (unnamed ones by locator, and
  by locator too when a path names several nodes, as the parts of unnamed instances do), and
  transforms (translation, rotation and scale separately), boxes, opacity, stroke width, `draw`,
  colors (sRGB per channel), points, path data with the same structure, and the camera (zoom
  geometrically) tween; text and other discrete values switch at the end. Layout is not solved
  again per frame: every node keeps its interpolated box, and connectors, attached nodes and nodes
  placed by geometry references are derived again from those boxes on every frame. Duration and
  easing come from the outermost container whose state changes. The scene clock does not advance
  during a transition: both ends are resolved at the time it starts.
- A transition's clips, and `enter` and `exit` clips, act on the resolved nodes: opacity, fill,
  stroke, stroke width, `draw`, and `scale` and `rotation` composed with the node's own. Nothing is
  derived again from what they move (an arrow does not follow a pulsing box). Without `{auto: all}`
  in a body, the diff happens at the end of the body.
- An interrupted transition (a state change while one plays) starts a new automatic diff from the
  frame on screen (spec section 21, candidate rule).
- Tracks: key times are seconds from the item's start; a key's `ease` shapes the segment that ends
  at it (else the track's `ease`, else linear). Outside its span a track holds its first or last
  value; when no track spans the moment, the one that ended last wins, else the next to start. So a
  `fade_in` that starts at 2 s keeps its node hidden until then.
- Blending: when tracks overlap on a property, numbers add up as deltas from the base value (`scale`
  multiplies, points per coordinate) and other values go to a raw track, then a clip; the validator
  warns `blend_overlap` once per property. Key values may read `theme.<token>` (root theme only).
- Tracks drive any property, `at.x` and `theme.<token>`, but not template parameters (an error:
  re-expanding an instance per frame is left for later), `state`, `style` or `attach`. A track with
  `along: <connector>` places the node's center at a fraction of the route, every frame.
- Timelines are evaluated before layout, so attachments and connectors follow what tracks move, and
  layout does respond to animated sizes (the spec only rules out re-solving layout in transitions).
- `with:` on a clip sets its `duration` parameter to the span it names unless it is given.
- `draw` (0 to 1, not in the spec) is the fraction of a stroke drawn from its start; arrowheads show
  only once it is complete. It is how `motion.draw_stroke` works.
- The camera: `fit` frames the node's box with `padding` inside `into` (default: the viewport);
  `attach: {to}` centers on a node or anchor; explicit `center`, `zoom` and `rotation` win over
  what `fit` derives. Parallax moves a layer's view center by that fraction of the camera's offset
  from rest, with the full zoom. Paths for `fit` are written from the root.
- Video export holds the scene clock (or walks a container: `--hold` seconds per state, then the
  transition). The video lasts as long as the longest timeline or media clip, else 5 s.
- Media node properties (`asset`, `fit`, `start`, `in`, `out`, `volume`, `rate`) are reserved names
  like other properties. A media node with neither a size in the manifest nor `width` and `height`
  is `asset_missing`. Audio is not drawn and takes no space.
- Decks: one slide per state of the scene's container (else the outermost container), in
  declaration order. The HTML deck holds one SVG per slide and pre-rendered transition frames (30
  per second by default); going back plays a transition in reverse. PDF pages are images rendered
  at scale 2.
- Lint skips subtrees at opacity 0 (hidden slides, steps not shown yet).
- The harness resolves the whole document again after a patch or a state change; the spec asks for
  incremental resolution, which is not done (a frame takes a few milliseconds for the examples).
- A cue that the audio does not have is not reported (the validator has no manifest and the
  resolver does not report timeline problems); the item then starts at its container's start.

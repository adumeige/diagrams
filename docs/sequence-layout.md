# Sequence layout

Addendum to [spec.md](spec.md) and [graph-layouts.md](graph-layouts.md), draft 0.1. Status: design,
not implemented. It adds the layout kind `sequence`, which places participants side by side and
messages in rows, in the order they are declared.

## 1. Purpose

A sequence diagram is laid out from order, not from coordinates. Without a layout kind for it, the
UML library makes every lifeline a column of steps, keyed alike on every lifeline, and places
fragments with geometry expressions over those steps ([libs/uml.yaml](../libs/uml.yaml), sequence
section). It works, and it is the worst friction the library met: each message needs a step on
every lifeline, each fragment four expressions, and inserting a message renumbers the diagram.

With `sequence`, the author declares the participants, then the messages in order, and groups
messages into fragments by nesting them:

```yaml
- id: checkout
  layout: sequence
  children:
    - {use: uml.lifeline, id: buyer, name: Customer, actor: true}
    - {use: uml.lifeline, id: web, name: ": Storefront"}
    - {use: uml.lifeline, id: orders, name: ": Orders"}
    - {use: uml.message, id: start, from: buyer, to: web, label: "checkout()"}
    - use: uml.fragment
      operator: loop
      guard: for each line
      children:
        - {use: uml.message, id: add, from: web, to: orders, label: "addLine(item)"}
        - {use: uml.message, from: orders, to: web, label: line, kind: reply}
    - {use: uml.activation, on: web, from: start, to: add}
```

Nothing in it is a coordinate, a step key or an expression.

## 2. Roles

A child of a `sequence` container has a **role**, the node hint `role`. A library sets it on the
body root of its templates, so authors never write it: an instance of `uml.lifeline` is a
participant, one of `uml.fragment` a fragment.

| Role | What it is | Placed by the layout |
| --- | --- | --- |
| `participant` | A lifeline: its head and the line under it | Side by side, heads on one baseline; stretched down to the last row |
| `message` | A child that holds nothing but a connector and what is attached to it (an instance of a relation template), or a connector itself | One row, in declaration order; the route is supplied |
| `fragment` | A combined fragment: messages and operands nested in it | Around the rows of what it holds and the participants those messages touch |
| `operand` | A part of a fragment: messages nested in it | Across its fragment, around its rows |
| `activation` | A bar on a participant, from a message to a message | On the participant's line, from the row of `from` to the row of `to` |
| `reference` | An interaction use (`ref`): it takes a row of its own | Across the participants listed in `covers` |

A child without a role is a participant, unless it holds only connectors and attached nodes, which
makes it a message (as for graph layouts, section 2). Attached children (notes) follow their target.

## 3. Placement

**Participants.** Left to right in declaration order, `gap` apart, center to center at least
`gap` plus the wider of two neighbours. Their heads share a bottom line, so that an actor's tall
figure and a box head start their lines at the same height. The layout stretches every participant
down to the end of the last row; a participant draws its line with a decoration
(`{shape: line, points: [..., {x: "=host.width / 2", y: "=host.height"}]}`).

**Rows.** Each message is a row, in the order the messages appear in a depth-first walk of the
children: a message nested in a fragment takes its row where the fragment stands. A row is as high
as the label it carries (the room reserved for it, as `layered` reserves room for labels) plus
`row_gap`. Options and hints:

- `parallel: true` on a message: it shares the previous message's row (an asynchronous burst).
- A self message (both ends on one participant) takes a row and a half: the layout supplies a loop
  route, out to the right and back one half-row down.
- A fragment or an operand adds a header row for its tag and guard; a reference takes a row of its
  own.

**Routes.** The layout supplies every message's route: a horizontal line at its row, from the
sending participant's line to the receiving one's, ending on the edge of the topmost activation bar
that covers that row on each side. A route is exact: the floating ends of graph layouts do not
apply. A message whose end is not a participant of the container is not placed (`message_unplaced`)
and is drawn `straight`.

**Creation and destruction.** Edge hints set by the message template from its kind:

- `creates: true`: the receiving participant starts at this row. Its head is centered on the
  message and its line starts under it; the message ends on the head.
- `destroys: true`: the receiving participant ends at this row; the participant template draws the
  end mark at the bottom of its box.

**Activations.** Explicit in this draft: an `activation` child names its participant (`on`) and two
messages (`from`, `to`, inclusive). Nested activations on one participant step right by half a bar.
Deriving activations from synchronous calls and their replies is left for later, behind an option
`activations: auto`.

**Fragments and operands.** A fragment spans the rows of everything nested in it, plus its header
row, and horizontally the participants its messages touch, `fragment_padding` beyond their lines
(nested fragments add the padding again). `covers: [a, b]` widens it to more participants. An
operand spans its fragment's width and its own rows; the layout sets both sizes, so the fragment and
operand templates draw their frame, tag and dashed separator as decorations from `host`.

## 4. Contract

| Kind | Options | Node hints | Edge hints | Pins | Routes | Labels |
| --- | --- | --- | --- | --- | --- | --- |
| `sequence` | `gap`, `row_gap`, `fragment_padding` | `role`, `covers`, `on`, `from`, `to` | `parallel`, `creates`, `destroys` | | yes | yes |

Gestures: reordering participants and moving a message to another row are reorders of the
document's children; there are no pins.

## 5. Determinism and transitions

The layout reads only the order of the children and the sizes of what they hold: the same document
gives the same picture everywhere, with no seed. A state that adds or removes a message (`present`)
moves the rows after it; a transition interpolates boxes and supplied routes as for graph layouts
(graph-layouts.md, section 9).

## 6. Diagnostics

- `message_unplaced` (warning): a message whose end is not a participant of the container.
- `activation_range` (error): an activation whose `to` comes before its `from`, or whose messages do
  not touch its participant.
- `hint_ignored`: a hint the `sequence` kind does not declare, or a `sequence` hint in another kind.

## 7. Library impact

The UML library keeps its current lifelines for free containers and gains the `sequence` ones:
`lifeline` (role participant, the line as a decoration), `message` (edge hints from its kind),
`fragment` and `operand` (decorated, sized by the layout), `activation`, `interaction_use` (role
reference). [examples/uml_sequence.yaml](../examples/uml_sequence.yaml) then loses its step lists and
its geometry expressions; the friction log ([library-authoring.md](library-authoring.md)) records why.

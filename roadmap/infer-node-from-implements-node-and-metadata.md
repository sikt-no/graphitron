---
id: R580
title: "Infer @node from `implements Node` + __NODE_* metadata"
status: Spec
bucket: architecture
priority: 8
theme: nodeid
depends-on: []
created: 2026-08-03
last-updated: 2026-08-04
---

# Infer @node from `implements Node` + __NODE_* metadata

A type declared `implements Node @table(name: "X")` whose backing jOOQ class publishes
`__NODE_TYPE_ID` / `__NODE_KEY_COLUMNS` should classify as a `NodeType` without the author
restating those values in `@node`. Decision taken 2026-08-04: this is the correct behaviour, and
the current opt-in-on-`@node` rule is the defect.

Today `TypeBuilder.buildTableType` reads the metadata, runs the malformed-metadata diagnostic, and
then returns a plain `TableType` when `@node` is absent. The type's `id: ID!` falls through to
ordinary column mapping and is rejected as `UnclassifiedField("column 'id' could not be resolved in
the jOOQ table; did you mean: ...")`. The rejection is accurate about the mechanism and useless
about the cause: the author is not misspelling a column, and the did-you-mean list actively pushes
them toward `@field(name: "...")`, which would satisfy the classifier while publishing a broken
`Node` contract. Reproduced against the `film_actor` fixture (metadata typeId `FilmActor`, keys
`ACTOR_ID` / `FILM_ID`); adding `@node` flips the type to `NodeType` and the field to
`ChildField.ColumnBackedField`.

## The rule

When a `@table` object type declares `implements Node`, carries no `@node`, and
`JooqCatalog.nodeIdMetadata(table)` is `Present`, promote it to `NodeType` with `typeId` and
`keyColumns` taken from the metadata. This is exactly the values-resolution the existing
`@node`-plus-metadata path computes when the author declares `@node` with no arguments, so the
implementation is a reachability change at the `hasNode` gate rather than a new resolution path.

Unchanged on every other axis:

- **`@table` + metadata without `implements Node` stays a `TableType`.** Publishing the Relay
  contract is the author's SDL-level opt-in and remains load-bearing. This is what keeps the
  `shared_node` fixture honest: `SharedNodeProjection @table(name: "shared_node")` is a nesting
  projection over a node-bearing table and must not become a second node.
- **Explicit `@node` still wins.** The SDL-versus-metadata merge rules (typeId overrides silently,
  key-column set disagreement is a hard error, order disagreement warns) are untouched. Inference
  fires only in the directive's absence, so an author who needs a different wire typeId writes it.
- **`@node` without `implements Node` is still rejected**, and the unconditional malformed-metadata
  diagnostic still runs ahead of everything.

## The collision hazard, and why `implements Node` is load-bearing

`TypeBuilder.buildTableType`'s comment justifies opt-in promotion on the grounds that inferring from
metadata "would silently collide typeIds across types whose backing tables share `__NODE_TYPE_ID`,
with no SDL-side opt-out". That is not a hypothetical: `R27`'s History section records the incident
behind it. A metadata-only auto-promotion shim lived at this exact site and was retired on consumer
feedback after roughly 200 sis event types, all backed by tables sharing
`__NODE_TYPE_ID = "195"`, promoted in lockstep and were then symmetrically demoted to
`UnclassifiedType` by `TypeBuilder.validateNodeTypeIdUniqueness`. A working build became hundreds of
simultaneous errors.

Two things separate that shim from this proposal, and the item stands or falls on the first:

1. **The retired shim promoted on metadata alone. This one requires `implements Node`.** A `@table`
   event type that never published the Relay contract does not promote here, so the lockstep
   promotion that caused the incident does not occur for those ~200 types unless they also declare
   `implements Node`. This is the central safety claim and it is **unverified against the real sis
   schema**; see Evidence required below. If a meaningful number of sis types do pair
   `implements Node` with tables sharing a typeId, this item reproduces the incident in miniature
   and the design needs a per-collision-group fallback rather than a mass rejection.
2. **The collision is diagnosed, not silent, and has a stated opt-out.**
   `validateNodeTypeIdUniqueness` registers a build-time diagnostic naming every member of a
   colliding group, and its message already names the escape hatch: "pick one via `@node(typeId:)`".
   Explicit `@node` continues to override inference. This is real mitigation but it does not scale
   to a 200-type lockstep group, which is precisely what the incident demonstrated: a diagnostic per
   colliding type is not a usable migration when the group is that large. Treat this as a reason the
   failure is *legible*, not a reason it is *acceptable*.

Spec should also confirm the collision message reads sensibly when one or both sides are inferred
(it currently says "is declared on multiple types", which is imprecise for an inferred typeId) and
add a pipeline case for the inferred-collision shape.

## Relationship to the nodeId grammar track

`R273` records this deliverable as "contradicted, not pending", citing `R473`, `R34` and `R27`. That
reading conflates two axes, and the distinction is what makes this change safe to take:

- `R473` is about a **field** acquiring node semantics from table facts: a bare `ID` field or
  argument that has declared nothing gets an implicit decode because its table happens to carry
  `__NODE_*`. Its grammar closes that off, correctly, and this item does not reopen it. Inputs,
  arguments, and cross-type references still require `@nodeId(typeName: T)`.
- This item is about a **type** that has already published nodehood in SDL getting its two identity
  parameters filled in from the catalog. The declaration of nodehood stays in SDL, where `R473`
  wants it; only `typeId` and `keyColumns` are sourced from the generator that owns them.

`R473` rule 1 is the direct precedent for the shape of the argument: "`Node.id` is the only implicit
nodeId. The `id` field satisfying the `Node` interface on a type declared `implements Node @node` is
obviously a nodeId and obviously of the enclosing type. The directive is redundant there." This item
extends the same "obviously" one step outward, from the `id` field to the `@node` directive itself:
where the jOOQ generator has published the node identity and the author has published the Node
contract, restating the two values is the same redundancy `R473` removes at the field level.

**But that behaviour is a deprecated shim today, and this item cannot ship on top of it unchanged.**
Bare `Node.id` does encode without `@nodeId` (confirmed against `bar`: `implements Node @table @node`
with a bare `id: ID!` classifies as `ColumnBackedField` with
`compaction=NodeIdEncodeKeys(encodeFoo)` over both key columns). It does so through `R27` Shim 1
site A, the branch at `FieldBuilder.java:7255-7265`, which fires a per-occurrence deprecation WARN
("declare `@nodeId` explicitly. The synthesis shim will be removed in a future release") and which
`R27` is chartered to delete and flip to a terminal classifier error. So on current trunk an
inferred node type would infer its own `@node`, then warn on its own `id` field that the author
should have written a directive, and would hard-fail once `R27` lands.

That is incoherent, so this item owns the reconciliation: adopt `R473` rule 1 at that site, making
the implicit `Node.id` carrier permanent for `NodeType` parents and dropping its deprecation WARN,
while leaving the other two shim sites (the input-scalar arm and the FK-qualifier arm) untouched for
`R27` to retire. The alternative, sequencing strictly behind `R473`, is discussed under Sequencing.
No test asserts the WARN's text (the two `*WarnFormatTest` classes cover the `@asConnection`
same-table and id-reference warnings, not this one), so dropping it is contained.

Reconciliation work this item owns at Spec time:

- Rewrite `R273`'s second bullet. The deliverable is live, not dropped, and `R273`'s surviving scope
  (the bare scalar-`ID` argument arm) is unaffected by it.
- Re-scope `R34` step 3. Its type-level hint offering `implements Node @node` is moot for the
  metadata-present case once inference lands; what survives is the narrower hint offering
  `implements Node` on a `@table` type whose backing class carries metadata but which has not
  published the interface. Steps 1 and 2 (the field-level shim quick fixes) are untouched.
- Note the reversal in `R27`, which records the earlier removal as deliberate.
- Rewrite the `TypeBuilder.buildTableType` comment; it states the retired policy and the stale
  rationale above.

## The shadowed `id` column

A table can carry `__NODE_*` metadata *and* a column literally named `id`. Decided 2026-08-04: the
node metadata wins, and the build emits a warning that the `id` column is being shadowed, naming the
two ways the author silences it by stating the choice explicitly. `@nodeId` on the field pins the
node interpretation; `@field` pins the column.

This is not only a migration concern for inferred nodes. It is a live defect on the **explicit**
`@node` path, which makes it a correctness fix this item carries rather than a compatibility rider.
Observed against the `baz` fixture (metadata `typeId = "Baz"`, `__NODE_KEY_COLUMNS = { BAZ.ID }`, and
the key column is itself named `id`):

| SDL | Type verdict | `id` compaction |
|---|---|---|
| `baz`, `implements Node @table @node`, bare `id: ID!` | `NodeType` | `Direct` (raw column) |
| `baz`, same plus `id: ID! @nodeId` | `NodeType` | `NodeIdEncodeKeys(encodeFoo)` |
| `baz`, same plus `id: ID! @field(name: "id")` | `NodeType` | `Direct` |
| `bar` (no literal `id` column), `implements Node @table @node`, bare `id: ID!` | `NodeType` | `NodeIdEncodeKeys(encodeFoo)` |

So a type that declares `@node` today, and whose table happens to have an `id` column, silently
publishes the raw column value as its Relay global id, while the identical declaration over a table
without that column collision publishes an encoded one. Nothing warns. The rule above corrects the
default and surfaces the case.

The precedence the rule needs already exists and needs no new machinery: both silencers are live
today (rows 2 and 3 above), so the work is flipping the default when metadata is present and adding
the warning. Spec should also cover the variant with no fixture yet, a table whose metadata keys on
columns *other* than a literal `id` column that also exists, where the same shadowing applies.

The single implementation site is the column-lookup-first ordering at `FieldBuilder.java:7247-7271`:
`svc.resolveColumn(columnName, tableType)` runs before the `NodeType` arm, so a successful lookup
short-circuits to `Direct` and the node interpretation never gets a turn. The fix is to give the
`NodeType` + Node-interface-`id` + no-field-directive case precedence over the column hit, and warn
when the column existed. `hasFieldDirective` (line 7208) is already the `@field` opt-out.

**Measured blast radius, which is why this may want to be its own item.** In-repo, inference itself
flips nothing: all 17 `implements Node` declarations across the SDL fixtures already carry `@node`,
so the new inference path needs new fixtures to be exercised at all. The shadowing rule is the
opposite. 22 test fixtures write `type Baz implements Node @table(name: "baz") @node { id: ID! }`,
which is exactly row 1, so every one of them flips its `id` from raw column to encoded and changes
generated output. Three more already write `id: ID! @nodeId` on the same shape, which is fixture
authors hand-applying the silencer, and is independent evidence that the current default is the
wrong one. Splitting decision goes to the reviewer; see Sequencing.

## Implementation plan

**Phase 1: inference.** In `TypeBuilder.buildTableType`, replace the `hasNode` early return
(`TypeBuilder.java:1308-1311`) with a gate that also admits `implementsNode(objType) &&
metadata.isPresent()`, routing that case to `buildNodeType` with the metadata's `typeId` and
`keyColumns`. The existing SDL-versus-metadata merge below stays reachable only when `@node` is
present, unchanged. Rewrite the comment at `:1294-1299`, which states the retired policy and the
rationale this item revises.

**Phase 2: adopt the `Node.id` rule.** At `FieldBuilder.java:7255-7265`, drop the deprecation WARN
and restate the branch as the permanent `R473` rule-1 carrier rather than a shim awaiting deletion.
Leave the other two `R27` shim sites alone. Coordinate the wording so `R27`'s inventory stays
accurate.

**Phase 3: the shadowing rule.** Reorder the column lookup and the `NodeType` arm as described
above, add the warning for the shadowed column, and migrate the 22 affected fixtures. The warning
names the column, the winning interpretation, and both silencers. Carry it as a
`BuildWarning.LintFinding` rather than `NoRule` so it reaches `ValidationReport.warnings()` and the
LSP replay with a fix attached, which is the same surface `R34` builds on; that also means it needs
a `LintRule` and a `LintFix` per silencer, and the two-fix shape should be checked against what
`LintQuickFixes.compute` can currently render.

**Phase 4: reconciliation.** Rewrite `R273`'s second bullet (the deliverable is live, not dropped),
re-scope `R34` step 3 to the narrower `implements Node`-missing hint, note the reversal in `R27`,
and update `METADATA_ONLY_NO_PROMOTION`'s description in `NodeIdPipelineTest`, which currently reads
"without `implements Node @node`" and cites the stale collision rationale. The fixture itself
survives unchanged, since `@table` without `implements Node` still stays a `TableType`.

## Sequencing

Phase 2 is the reason this item cannot simply queue behind `R473`: `R473` is a Backlog architecture
item spanning input-side shims, decode-resolution polarity, and a breaking SDL migration, and
blocking a small classifier correction on all of it would be disproportionate. Taking only rule 1's
adoption at one site is the minimum needed for Phase 1 to be coherent, and it moves in `R473`'s own
direction rather than against it. Reviewer should confirm that reading and that `R473`'s remaining
scope survives intact.

The open split question for the reviewer: Phase 3 changes behaviour on the **existing explicit
`@node` path** and carries the entire measured blast radius, while Phases 1 and 2 flip nothing
in-repo. A reviewer who wants the inference landed cleanly may prefer Phase 3 carved into its own
item, at the cost of leaving the raw-column-as-global-id defect live longer and of the two items
touching the same `FieldBuilder` block in sequence.

## Evidence required before this leaves Ready

1. **The sis verification.** Count the types in the sis schema that declare `implements Node` over a
   `@table` whose backing class carries `__NODE_TYPE_ID`, grouped by typeId, and confirm no group
   has more than one member. This is the direct test of the safety claim above and cannot be
   answered from this repo. If any group has more than one member, bring the count and the shape
   back to Spec before implementing.
2. **A shadowing sweep of the consumer schemas** for types over tables that carry metadata and also
   have a column named `id`, since those are the sites whose wire values change.

## Non-goals

- **`implements Node` + `@table` with no metadata: no change.** Decided 2026-08-04. Inference does
  not extend to deriving node identity from a primary key; metadata is a positive assertion by the
  jOOQ generator that this table has a published node identity and what its wire typeId is, whereas
  a primary key is not. Recorded observation for Spec, since it cuts against reading this shape as
  currently well-formed: over a table with no metadata and no literal `id` column (the `qux`
  fixture), `implements Node @table` without `@node` classifies the type as `TableType` and rejects
  `id: ID!` as `UnclassifiedField("column 'id' could not be resolved ...")`. Written `@node`
  explicitly, the same shape is well-formed and resolves against the `@node`-only defaults (type
  name for `typeId`, PK for `keyColumns`). Confirm at Spec time which of those two the non-goal is
  meant to preserve.
- Wire format, encode/decode emission, and the `Query.node` dispatch surface are untouched.

## Test surface

- `NodeIdPipelineTest`: inferred promotion on `implements Node @table` over `bar` (the headline
  case, `compaction=NodeIdEncodeKeys` with no directive written); `@table` + metadata without
  `implements Node` still `TableType`; inferred typeId collision produces the uniqueness diagnostic;
  explicit `@node(typeId:)` still overrides an inferred sibling. The existing `NO_METADATA_NO_NODE`
  case stays green unchanged. `METADATA_ONLY_NO_PROMOTION` survives on its `@table`-without-
  `implements Node` fixture but its description needs rewriting: it currently reads
  "without `implements Node @node`" and cites the stale collision rationale.
- The shadowing rule's four rows above, pinned on `baz` (three) and `bar` (one), plus the warning's
  presence and absence. The first row is the behaviour change, so it is the load-bearing assertion.
- `ClassifiedCorpus`: the `relay-node` example declares `type Film implements Node
  @table(name: "film")` over a table with no metadata, so it is unaffected; consider a sibling
  fixture pinning the inferred verdict.
- Execution tier: the `film_actor` NodeId round-trip already exists (`GraphQLQueryTest`'s
  `filmActorByNodeId`); a variant with `@node` dropped from the SDL proves inference end to end.

## Reproduction

```graphql
type Baz implements Node @table(name: "film_actor") { id: ID! }
type Query { baz: Baz }
```

`schema.type("Baz")` is a `TableType`; `schema.field("Baz", "id")` is an `UnclassifiedField` whose
reason names the column lookup. With `@node` added, `NodeType` + `ColumnBackedField`.

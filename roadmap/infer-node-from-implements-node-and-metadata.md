---
id: R580
title: "Infer @node from `implements Node` + __NODE_* metadata"
status: Backlog
bucket: architecture
priority: 8
theme: nodeid
depends-on: []
created: 2026-08-03
last-updated: 2026-08-03
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

## Why the recorded objection is stale

`TypeBuilder.buildTableType`'s comment justifies opt-in promotion on the grounds that inferring from
metadata "would silently collide typeIds across types whose backing tables share `__NODE_TYPE_ID`,
with no SDL-side opt-out". Both halves are false as the code stands:

- Not silent. `TypeBuilder.validateNodeTypeIdUniqueness` (run from `finishTypeClassification`)
  already groups every classified `NodeType` by `typeId` and registers a build-time diagnostic on
  each member of a colliding group, which the validator drains and which fails the build before
  generation. An inferred collision lands in exactly that check, because inference produces ordinary
  `NodeType`s.
- Not without opt-out. That check's own message already names the escape hatch: "pick one via
  `@node(typeId:)`". Explicit `@node` continues to override inference, so the author resolves a
  collision by declaring the directive on one of the two types.

So the hazard the opt-in rule was protecting against is already a handled, diagnosed, author-fixable
condition, while the protection itself costs every correctly-declared node an unexplained rejection.
Spec should confirm the collision message reads sensibly when one or both sides are inferred (it
currently says "is declared on multiple types", which is imprecise for an inferred typeId) and add a
pipeline case for the inferred-collision shape.

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

`R473` rule 1 is in fact the direct precedent, not an obstacle: "`Node.id` is the only implicit
nodeId. The `id` field satisfying the `Node` interface on a type declared `implements Node @node` is
obviously a nodeId and obviously of the enclosing type. The directive is redundant there." That is
already live behaviour, confirmed against the `bar` fixture: `implements Node @table @node` with a
bare `id: ID!` classifies as `ColumnBackedField` carrying
`compaction=NodeIdEncodeKeys(encodeFoo)` over both key columns, with no `@nodeId` written. This item
extends the same "obviously" one step outward, from the `id` field to the `@node` directive itself:
where the jOOQ generator has published the node identity and the author has published the Node
contract, restating the two values is the same redundancy `R473` already removed at the field level.

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

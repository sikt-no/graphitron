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
  wants it; only `typeId` and `keyColumns` are sourced from the generator that owns them. `R473`
  rule 1 ("`Node.id` is the only implicit nodeId") is if anything strengthened: the set of types on
  which that rule fires grows to include the ones the author plainly meant.

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

## Non-goals and open questions

- **`implements Node` + `@table` with no metadata is out of scope.** The natural coherent rule would
  be "`implements Node` on a `@table` type means the type is a node", with parameters resolved by
  the existing precedence ladder (metadata, then the `@node`-only defaults of type name for `typeId`
  and PK for `keyColumns`). Recommendation is to hold that back: metadata is a positive assertion by
  the jOOQ generator that this table has a published node identity and what its wire typeId is,
  whereas a primary key is not, and promoting on PK alone would silently convert every
  `implements Node @table` type's `id` from a column value to an encoded global id. The residue is
  that the misleading column did-you-mean rejection survives for that narrower shape; fix the
  message there rather than widening the inference.
- **The literal `id` column case is the one real migration hazard.** A table that carries `__NODE_*`
  metadata *and* a column named `id` classifies today as a plain column-mapped scalar and would
  become a NodeId-encoded field, changing the wire value. Spec should sweep the fixture corpus and
  the consumer schemas for that overlap; if any exists, decide between a targeted warning and
  requiring `@field(name: "id")` to keep the raw column.
- **Explicit field-level directives on `id`.** Decide whether `id: ID! @field(name: "...")` on an
  inferred node type suppresses the node interpretation (author override, consistent with `@node`
  winning at the type level) or is a rejection. Either is defensible; pick one and pin it.
- Wire format, encode/decode emission, and the `Query.node` dispatch surface are untouched.

## Test surface

- `NodeIdPipelineTest`: inferred promotion on `implements Node @table` over `film_actor` (the
  headline case); `@table` + metadata without `implements Node` still `TableType`; inferred typeId
  collision produces the uniqueness diagnostic; explicit `@node(typeId:)` still overrides an
  inferred sibling. The existing `NO_METADATA_NO_NODE` case stays green unchanged.
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

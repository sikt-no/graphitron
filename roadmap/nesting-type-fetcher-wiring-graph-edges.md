---
id: R459
title: "Model the schema-shape to fetcher wiring edge for fetcher-owning nesting types in CompileDependencyGraphBuilder"
status: In Review
bucket: bug
priority: 3
theme: dev-loop
depends-on: []
created: 2026-07-09
last-updated: 2026-07-10
---

# Model the schema-shape to fetcher wiring edge for fetcher-owning nesting types in CompileDependencyGraphBuilder

## Problem

A plain-object nesting type that owns a fetcher (`FetcherEmitter.nestedTypeOwnsFetchers` is true
for any nested type with at least one classified field, per R303) emits a `<Type>Fetchers` class,
and its `<Type>Type` schema-shape wiring class references that fetcher (`FilmMetaType → FilmMetaFetchers`).
`CompileDependencyGraphBuilder` does not model this: `addTypeNodes`' `NestingType` arm registers only
the schema-shape node, never the nested fetcher, so the wiring loop in `addBlanketAndWiringEdges`
(gated on `fetcherNodes.contains(ownFetcher)`) never adds the `schemaShape → fetcher` edge. The
`TypeSpecReferenceWalk` completeness oracle (working correctly) flags this as a real superset gap:
the walk sees the edge; the model misses it.

Surfaced during R455 while extending the incremental-compile harness corpus with a fetcher-owning
nesting type. It is orthogonal to R455 (that item closed the walk's own blind spots and the inline
projection/condition/NodeIdEncoder edges); this is a separate model-completeness gap in the nesting
wiring, deferred out of R455 to keep that item scoped.

## Design

### Registration walk: a dedicated mirror of the fetcher-emission seam

The nested fields live only on the `ChildField.NestingField` (they are not registered under the
nesting type's coordinates: `schema.fieldsOf("FilmMeta")` is empty), so the fetcher-node registration
must happen where the `NestingField` is reachable, not in `addTypeNodes`.

Add a dedicated walk, `addNestedFetcherNodes()`, called from `build()` between `addTypeNodes` and
`addBlanketAndWiringEdges` (any point before the wiring loop works; it snapshots nodes when it runs).
The walk mirrors `TypeFetcherGenerator.collectNestedFetcherClasses`' reachability verbatim: iterate
`schema.types()` filtered to `GraphitronType.TableBackedType`, walk each root's `fieldsOf` for
`ChildField.NestingField`, dedup nested types by name across the whole schema, recurse into inner
`NestingField`s unconditionally, and register `units.fetchers(nestedTypeName)` gated on a mirrored
`nestedTypeOwnsFetchers` predicate (any nested field not `UnclassifiedField`). Mirrored rather than
importing `FetcherEmitter`, preserving the builder's no-generator-coupling discipline; the mirror is
the same enforcement class as the builder's existing `filtersDecodeNodeId` / `hasSqlGeneratingField`
mirrors, with the oracle as the drift catch for corpus-covered shapes.

The Backlog sketch proposed piggybacking on the R455 projection recursion (`addProjectionChildEdges`)
instead. Rejected on seam grounds: that walk mirrors `TypeClassGenerator`'s emit seam and its host
filter (`TableType`/`NodeType` only), while fetcher emission is `TypeFetcherGenerator`'s seam with a
`TableBackedType` root filter. The two reachabilities are identical today, because the classifier
never lands a `NestingField` under a `TableInterfaceType` parent (the child-field entry gate at
`FieldBuilder.classifyChildField` is `parentType instanceof TableBackedType && !(parentType
instanceof TableInterfaceType)`, and the `NestingField` recursion re-roots on the original
non-interface parent), so the fork is decided by discipline, not by a live reachability delta:
mirroring the emit seam exactly cannot under-approximate if the classifier later admits
interface-hosted nesting, and it keeps the projection walk's single job (projection-composition
attribution) unconflated with node registration. The spec deliberately does not claim an
interface-hosted fixture is constructible; do not add one.

On the mirrored gate: post-classification the predicate is effectively always true for a reachable
`NestingField` (an unclassified nested field collapses the whole `NestingField` to
`UnclassifiedField` in `FieldBuilder`, and an empty plain object is invalid GraphQL), so the gate
exists for parity with the emit site, not because a fetcher-less nesting type is a live case. Keep
it mirrored anyway; do not pin the false branch with a test (a hand-built empty-`nestedFields` case
would assert an input the classifier cannot produce).

### Edges for free

Registering the node is the whole fix. `addBlanketAndWiringEdges` runs last and snapshots
`fetcherNodes` at that point, so the existing wiring loop then adds `schemaShape → ownFetcher`
(the `FilmMetaType → FilmMetaFetchers` edge the oracle sees), the blanket frozen-scaffold +
`GraphitronContext` edges, and `schemaClass → fetcher`, with no new edge code.

### Scope boundary: the nested fetcher's own outgoing precise edges are a separate item

The nested `<Type>Fetchers` class's own *outgoing* per-field edges (a nested `SplitTableField`'s
DataLoader methods referencing the target's projection class; a nested composite/`@nodeId` read
referencing `NodeIdEncoder`) are an adjacent model gap not touched here: `addFieldEdges` never sees
nested fields (absent from `schema.fields()`). Folding them in is not a routing one-liner: a nested
`SplitTableField` routed through `addFieldEdges`' `TableTargetField` arm would call
`addConditionsEdge(fetcher, "FilmMeta")`, whose `hasSqlGeneratingField` reads the empty
`schema.fieldsOf("FilmMeta")`, so conditions attribution over coordinate-less nested types needs its
own design, the same `fieldsOf` blindness that causes this bug. Filed as Backlog **R462**
(`nested-fetcher-outgoing-field-edges`) with the concrete failing pin: a harness fixture with a
nested `@splitQuery` (or composite-`@nodeId`) field that turns the completeness oracle red once the
R459 registration lands. This item's own fixture must NOT exercise that gap (see Tests).

### Collapse target (named, not blocking)

R459 adds the third independent re-walk of the `NestingField` tree (emitter, R455 projection walk,
this walk) and the third copy of the fetcher-ownership fact (two emit sites via `FetcherEmitter`,
plus this mirror). The collapse target, for a future item in the same spirit as the
`InlineProjectingField` note on `addTypeProjectionEdges`: expose nested fetcher-owning types (and
their fields) as a derived view on `GraphitronSchema`, so emitter, projection walk, and builder
project off one seam. Not blocking this item; name it in the walk's javadoc.

## Tests

- **Unit (`CompileDependencyGraphBuilderTest`)**: a hand-built model with a `TableType` host, a
  `NestingType` entry (so the schema-shape node exists), and a `NestingField` carrying at least one
  classified field, following `nestingHostedInlineFieldAttributesEdgeToOuterTypeClass`'s
  construction pattern. Assert the nested fetcher node exists, `schemaShape → fetcher`,
  `schemaClass → fetcher`, and a spot-checked blanket edge (`fetcher → GraphitronContext`). No
  negative empty-`nestedFields` case (vacuous, per above).
- **Pipeline (`IncrementalCompileHarnessTest`)**: extend both `SCHEMA` and `SCHEMA_EDITED`
  identically with a fetcher-owning nesting type carrying an inline table field, e.g.
  `Film.meta: FilmMeta` where `FilmMeta { language: Language @reference(...) }` (shares Film's
  table context; the nested field must classify as inline `TableField`, whose reified read
  references no generated unit, so the fixture exercises exactly the wiring edge and not the
  deferred per-field gap; verify the classification while implementing). Acceptance: the
  completeness oracle goes green with the nesting case present (it flags the gap before the fix),
  and clauses (a)/(b) still pass.

## Acceptance

1. `CompileDependencyGraph` contains the nested fetcher node and the `schemaShape → fetcher`,
   `schemaClass → fetcher`, and blanket edges for every fetcher-owning nesting type reachable from a
   `TableBackedType` root.
2. Oracle green on the extended harness corpus; unit case pinning the edges; full reactor green
   under `-Plocal-db`.
3. Follow-up Backlog stub filed for the nested per-field outgoing edges, carrying the
   oracle-reddening fixture sketch (R462, `nested-fetcher-outgoing-field-edges`).

## Implementation notes (landed)

- `CompileDependencyGraphBuilder.addNestedFetcherNodes()` (called from `build()` between
  `addTypeProjectionEdges` and `addBlanketAndWiringEdges`) walks `TableBackedType` roots' `NestingField`
  trees, dedups nested types by name, recurses into inner `NestingField`s unconditionally, and registers
  `units.fetchers(name)` gated on a mirrored `nestedTypeOwnsFetchers` predicate. The wiring loop then
  supplies `schemaShape → fetcher`, `schemaClass → fetcher`, and the blanket edges for free.
- Unit: `CompileDependencyGraphBuilderTest.fetcherOwningNestingTypeRegistersFetcherNodeAndWiringEdges`.
- Pipeline: `IncrementalCompileHarnessTest` `SCHEMA`/`SCHEMA_EDITED` gain `Film.meta: FilmMeta { language:
  Language @reference }`. Verified while implementing that disabling the walk left exactly the one
  `FilmMetaType → FilmMetaFetchers` oracle gap (single-valued inline `TableField`, no per-field gap).

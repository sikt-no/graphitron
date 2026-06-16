---
id: R316
title: "Pivot the field-dimensional model to (source, operation, target)"
status: Spec
bucket: structural
priority: 3
theme: structural-refactor
depends-on: [dimensional-model-pivot]
created: 2026-06-16
last-updated: 2026-06-16
---

# Pivot the field-dimensional model to (source, operation, target)

The field-dimensional model R290/R299/R305 shipped is `carrier x intent x mapping`. A
walk under the source-object / source-field vocabulary (the original `decompose-sourcekey`
thesis, see Lineage) shows the third axis is mislabeled and the first is under-named. The
correct model is `(source, operation, target)`: a field is an edge from a **source** (its
parent) to a **target** (what it projects), spanned by an **operation** (what the field
itself does). `mapping` is not a dimension; the catalog-vs-Java distinction it encodes is a
per-endpoint *polarity* that already lives on `source` and reappears on `target`.

The full argument, consequences, and the pros/cons of pivoting are in
[`audits/2026-06-16-source-operation-target-reframe.md`](audits/2026-06-16-source-operation-target-reframe.md).
This item is the decision to pivot and the plan to do it thoroughly. **The defining
constraint is thoroughness:** experience (R287, the staleness audits) shows that leaving
remnants of the retired model is a trap. The old vocabulary must be gone from the rewrite
scope when this item is Done, not merely shadowed by the new one.

## The target model

`source` is a sealed hierarchy with four arms, folding today's `Carrier` and `SourceShape`
into one type:

- `Source.Query` / `Source.Mutation` — the operation roots; no parent object. These arms
  *are* the operation-legality gate (write operations only on `Mutation`, `NodeResolve`
  only on `Query`), so the gate stops being a separate concern bolted onto `carrier`.
- `Source.Table` — a catalog table row parent. Bare beyond its arrival `cardinality`.
- `Source.Record` — a producer-handed domain record parent. Carries the reflected
  source-object facts the field cannot change: backing class, `env.getSource()` envelope,
  produced record type, plus arrival `cardinality`.

`SourceCardinality` rides as a slot on the two nested arms (`Table`, `Record`); the roots
are trivially single. `SourceShape` dissolves into the `Table`/`Record` arms.

`operation` is today's `Intent`, renamed. It is the field's own contribution: the operation
kind, its arguments, and the reflected service-method facts (parameter types, result type).
The rename to `operation` (not `field`, which reads circularly inside a field model) lands
here; growing it into a payload-carrying sealed hierarchy is staged with R314 (see
Relationships) so the emit side consumes it rather than duplicating it.

`target` is today's `Mapping`, renamed and reinterpreted: the destination endpoint and its
shape, `Table` / `Column` / `Connection` (catalog) and `Record` / `Field` (Java). The
catalog-vs-Java split here is the target's *polarity*, now a sibling of the source's
polarity rather than standing in for both. The symmetry is the vindication: `Source.Table`
sits next to `Target.Table`, and re-fetch is just a record-producing endpoint (`source` or
`operation`) crossing into a catalog `Target.Table`.

## Lineage: from decompose-sourcekey

R316 began as the mechanical `SourceKey` decomposition (evict `target`/`path` to the
field's existing slots, migrate source-object facts to the carrier, leave a source-field
key). That walk is what surfaced the model defect: `SourceKey`'s pieces had no honest homes
because the dimensions themselves were wrong. The mechanical simplification is now a
*downstream consumer* of this pivot (see Downstream), and R316's identity is the pivot. The
original framing is preserved in git history at the pre-rewrite revision of this file.

The backing audit recommended the inverse container split (a fresh item for the pivot, with
R316 kept as the downstream `SourceKey` consumer). Folding the pivot into R316 is a
deliberate override: it keeps this thread's id and history together. If a reviewer prefers
the audit's split, the move is a re-id, not a redesign.

## Slices

Slice 1 is the model decision; slices 2-4 lift the shipped work onto it; slice 5 is the
thoroughness gate. Land 1 first; 2-4 are sequenced by dependency, not parallel, because they
share the model types.

### Slice 1 — Rewrite the R222 model

Revise [`dimensional-model-pivot.md`](dimensional-model-pivot.md) (R222), whose umbrella
explicitly licenses slices to "redraw the diagram as implementation slices land and surface
new understanding." Replace the `carrier x intent x mapping` target model with
`(source, operation, target)`: the four-arm `source` seal, the `operation` axis, the `target`
endpoint with per-endpoint polarity, and the re-derived re-fetch predicate. This slice is
documentation only; it pins the vocabulary every later slice speaks.

### Slice 2 — The `source` seal in code

Fold `Carrier`, `SourceShape`, and `SourceCardinality` into one `Source` sealed hierarchy
(`Query` / `Mutation` / `Table` / `Record`), with `cardinality` on the nested arms and the
reflected facts on `Record`. Rename `OutputField.carrier()` to `source()`; repoint its
producers (`QueryField` / `MutationField` / `ChildField.sourceShape()` collapses into
`Source.Table` vs `Source.Record` construction) and its readers. Retire `Carrier.java`,
`SourceShape.java`, `SourceCardinality.java`.

### Slice 3 — `operation` and `target` in code

Rename `Intent` to `Operation` and `Mapping` to `Target`; rename `OutputField.intent()` to
`operation()` and `mapping()` to `target()`; reinterpret the catalog-vs-Java split on
`Target` as the target's polarity. Repoint all readers: `FieldBuilder`,
`GraphitronSchemaValidator`, `ServiceMethodCallEmitter`, `TypeFetcherGenerator`, the catalog
classification (`TypeClassification`, `FieldClassification`, `CatalogBuilder`).
**Carve-out:** `LookupMapping` and `MappingEntry` are the `@lookupKey` positional
correspondence, *not* the dimension. They are not renamed by this slice (they become
`Operation.Lookup` payload under the R314-coordinated operation enrichment, out of scope
here). The spec's retirement inventory must list them explicitly as do-not-touch.

### Slice 4 — Lift R281 (focus) and the derived layer

The R281 classification corpus is the primary lift target. Migrate `DimensionTuple`
(`(Carrier, Intent, Mapping)` to `(Source, Operation, Target)`), the `@classified` directive
arguments (`carrier:` / `intent:` / `mapping:` to `source:` / `operation:` / `target:`)
across every corpus example, and the `classifieddsl/*` harness (`ClassifiedCorpus`,
`ClassifiedDsl`, `ClassifiedHarness`, `QueryViewRenderer`) plus its tests
(`ClassifiedDslTest`, `QueryViewRendererTest`, `GraphitronSchemaBuilderTest`,
`SingleRecordPayloadPipelineTest`, `SourceShapeProjectionTest`, `TypeRegistryTest`,
`ConstructorFieldValidationTest`). Re-derive `OutputField.requiresReFetch()` over
`(source-polarity / operation, target-polarity)` (a `Source.Record` or record-producing
`operation` crossing into a `Target.Table`), replacing the `mapping() != Mapping.Table`
gate, and update its `GraphitronSchemaValidator.dispatchPerformsReFetch` mirror.
`SourceShapeProjectionTest` becomes a `Source`-arm projection test.

### Slice 5 — Thoroughness gate

The no-remnants mandate, made enforceable rather than aspirational:

- **Code.** No `Carrier` / `Intent` / `Mapping` / `SourceShape` / `SourceCardinality` type
  remains in `graphitron/src` (excluding the `LookupMapping` / `MappingEntry` carve-out). Two
  distinct guards, not one: the *coverage* gate is the disjoint-exhaustive partition over the
  `Source` seal (the shape `GeneratorCoverageTest.everyGraphitronFieldLeafHasAKnownDispatchStatus`
  already uses for leaf dispatch), proving every arm is handled; the *remnant* guard is a
  supplementary repository-grep for the retired type names, proving stale names and prose are
  gone, not merely shadowed. The grep is not a coverage check and must not be relied on as one.
- **Docs.** Sweep the five `.adoc` files that name the old axes
  (`code-generation-triggers.adoc`, `argument-resolution.adoc`, `typed-rejection.adoc`,
  `rewrite-design-principles.adoc`, `getting-started.adoc`) to the new vocabulary. The
  user-facing-doc check applies.
- **Changelog.** The R290/R299/R305 changelog entries describe a model that no longer
  exists; add a forward note so a reader is not misled, without rewriting history.

## Tests

- The `@classified` corpus is the behavioural backstop: migrated to the new axes, it must
  stay equivalent in *coverage* (every coordinate that classified before classifies after,
  to the renamed-but-corresponding verdict). `everyDimensionValueIsExercised` and the
  coverage/disjointness meta-tests carry over per axis.
- `requiresReFetch` re-derivation is pinned by `ReFetchDerivationTest` (behavioural, no
  `code().toString()` body matches) and the validator-mirror agreement check.
- The `Source` seal gets the sealed-leaf exhaustiveness guard `SourceShapeProjectionTest`
  becomes; the four arms are exercised by corpus coordinates with a documented
  `NOT_CORPUS_COVERED` for any arm no example reaches.
- Full reactor green (`mvn -f graphitron-rewrite/pom.xml install -Plocal-db`), including the
  execution tier, since the rename touches classification that the whole pipeline reads.

## Downstream: the `SourceKey` decomposition

The original `decompose-sourcekey` work becomes the first concrete consumer of the new
model, specced as its own follow-on once this pivot lands: `SourceKey.target` / `path` to
the `target` dimension, source-object facts to `Source.Record`, the service call and its
reflected result to `operation`, leaving a `Record`-source-only field key. It is no longer
in R316's scope; the pivot is the prerequisite that gives those pieces honest homes.

## Relationships

- **R222** (dimensional-model-pivot): the umbrella this revises. Slice 1 rewrites its model
  section; R222 explicitly permits slices to redraw it.
- **R290 / R299 / R305**: the shipped `carrier x intent x mapping` materialisation, corpus
  migration, and `Carrier.Source` build. Slices 2-4 lift their output onto the new model;
  this item is where their vocabulary is retired.
- **R314** (dissolve-reentry-leaves-dimensional-emit): owns the emit re-platforming and the
  payload-carrying `operation` enrichment. R316 lands the `operation` rename and the model;
  the operation payload and the emit switch coordinate with R314 so neither double-builds.

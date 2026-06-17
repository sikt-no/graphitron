---
id: R316
title: "Pivot the field-dimensional model to (source, operation, target)"
status: Spec
bucket: structural
priority: 3
theme: structural-refactor
depends-on: [dimensional-model-pivot]
created: 2026-06-16
last-updated: 2026-06-17
---

# Pivot the field-dimensional model to (source, operation, target)

The field-dimensional model R290/R299/R305 shipped is `carrier x intent x mapping`. A
walk under the source-object / source-field vocabulary (the original `decompose-sourcekey`
thesis, see Lineage) shows the third axis is mislabeled and the first two are under-named.
The correct model is `(source, operation, target)`: a field is an edge from a **source** it
arrives into to a **target** it projects, spanned by an **operation** it performs. `mapping`
is not a dimension; the catalog-vs-Java distinction it encodes is a per-endpoint *polarity*
that lives on `source` and reappears on `target`.

The full argument and the pros/cons of pivoting are in
[`audits/2026-06-16-source-operation-target-reframe.md`](audits/2026-06-16-source-operation-target-reframe.md).
This item is the decision to pivot and the plan to do it thoroughly. **The defining
constraint is thoroughness:** experience (R287, the staleness audits) shows that leaving
remnants of the retired model is a trap. The old vocabulary must be gone from the rewrite
scope when this item is Done, not merely shadowed by the new one.

## The target model

### source (the arrival endpoint)

A sealed hierarchy folding today's `Carrier` arms into one type whose arms are the field's
arrival position. `SourceShape` and `SourceCardinality` survive as the payload of the nested
arm rather than dissolving:

```
source      = Root(RootKind) | Child(SourceShape, SourceCardinality)
RootKind    = Query | Mutation
SourceShape = Table(<catalog metadata>) | Record(SourceObject, <extraction>)
```

- `Root(RootKind)`: an operation root; no parent arrives. `RootKind` (`Query` / `Mutation`)
  *is* the operation-legality gate (write operations only on `Mutation`, `NodeResolve` only
  on `Query`), so the gate stops being a separate concern.
- `Child(SourceShape, SourceCardinality)`: nested; a parent source object arrives. The arm
  names mirror the field seal (`OutputField permits RootField, ChildField`), so `RootField`
  builds `Root` and `ChildField` builds `Child`.

`SourceCardinality` (`One` / `Many`) stays a **slot** on `Child` rather than splitting it into
arm identity. It is a semiring (`One` the identity, `Many` the absorber) that R279/R308 will
compute by multiplying a field's ancestors', so it must stay a value to multiply; and `One` is
not yet produced (R305 hard-codes `Many`), so a cardinality-keyed arm would be born unreachable.
The `Child` arm carries `SourceShape`, the catalog-vs-Java polarity of the parent:

- `Table`: a catalog table row parent; carries its catalog metadata (table ref / row
  identity), slot detailed in the implementation pass.
- `Record`: a producer-handed domain record parent; carries (a) a `SourceObject` descriptor
  (a new type; today these facts live inside `SourceKey`) holding backing class,
  `env.getSource()` envelope, and produced record type, the facts the field cannot change, and
  (b) the field's own **extraction signature** (how *this* field reads its value off the
  record), reusing the existing `AccessorRef` / `LifterRef` via the (narrowed)
  `SourceKey.Reader` family. This arm is exactly where the decomposed `SourceKey`'s
  Record-source pieces land (see Downstream).

### operation (the verb)

A sealed hierarchy renaming `Intent`, **whose arms are the eventual payload carriers**, but
R316 ships the arms *empty*. The arm set, pinned here:

- `Fetch`: a catalog read; eventual payload the resolved filter surface `List<WhereFilter>`
  (`GeneratedConditionFilter | ConditionFilter`) plus ordering / pagination.
- `Lookup`: the positional `@lookupKey` correspondence; eventual payload `LookupMapping`.
- `ServiceCall`: a developer `@service` invocation; eventual payload the service `MethodRef`
  and params. This **collapses today's `QueryService` / `MutationService`**: they differ only
  by read-vs-write, which is purely the legality gate now carried by `Root(RootKind)`, and the
  two classify identically (same payload, same emit). `ServiceCall` carries no read/write bit;
  that holds while services stay root-only and graphitron does not branch on mutate-or-not (a
  future nested mutating `@service` would put the bit in a slot).
- the writes (`Insert` / `Update` / `Upsert` / `Delete`) and the remaining `Intent` values.

**Why empty arms.** The per-arm *payloads* (filters, lookup mapping, method ref) have no reader
in R316's scope: the emit still dispatches by leaf, and those facts already live on the leaves
and the condition model where every consumer reads them. The only future reader is R314's
re-platformed emit. So R316 seals the hierarchy and pins the arm set, which *is* consumed
(`requiresReFetch`, the corpus, diagnostics) and gives R314 a fixed target, and the per-arm
payload slots land in **R314, sliced per operation kind**, each slot arriving in the same slice
as the emit that reads it (see Relationships). Building boxes-with-data in R316 would stand up a
second home for facts that already have one, ahead of any reader.

### target (the projection endpoint)

Today's `Mapping`, renamed and reinterpreted: the destination endpoint and its shape,
`Table` / `Column` / `Connection` (catalog) and `Record` / `Field` (Java). The catalog-vs-Java
split here is the target's *polarity*, a sibling of `source`'s `SourceShape` polarity rather
than standing in for both. The two are not perfectly symmetric: on `source` the polarity *is*
the whole shape (`Table` / `Record`), while `target` carries five shapes with polarity only a
grouping, and re-fetch reads source-*polarity* together with target-*shape* (`Table`
specifically), not two parallel polarities. The vindication is the clean separation, not the
symmetry: each endpoint owns its polarity, so re-fetch is a record-producing endpoint (a
`source` `Record` or a record-producing `operation`) crossing into a catalog `Target.Table`,
read off the endpoints rather than decoded from a conflated `mapping`.

### the join path

The `@reference(path:)` join route is a slot bridging `source` to `target`, carrying per-hop
conditions that consume **context arguments only** (no field arguments are in scope at a
path-step `@condition`). So the path is not an argument-addressable surface; field arguments
bind `target`, and the path does not perturb the triple.

## Lineage: from decompose-sourcekey

R316 began as the mechanical `SourceKey` decomposition (evict `target`/`path` to the
field's existing slots, migrate source-object facts to the carrier, leave a source-field
key). That walk is what surfaced the model defect: `SourceKey`'s pieces had no honest homes
because the dimensions themselves were wrong. `SourceKey` is the second, independent witness
to that defect (the audit's first is `requiresReFetch`): its intended meaning is narrow, the
parent/source object's key a DataLoader uses to match a child field's rows back to their
source, yet it had accreted `target` (the read-to table), `path` (the FK route to it), and
`Reader` arms that are really `@service` calls and produced-record re-fetches. Same disease as
`mapping`: a grab-bag forms wherever the model lacks a clean endpoint to file facts under. The
mechanical simplification is now a *downstream consumer* of this pivot (see Downstream), and
R316's identity is the pivot. The original framing is preserved in git history at the
pre-rewrite revision of this file.

The backing audit recommended the inverse container split (a fresh item for the pivot, with
R316 kept as the downstream `SourceKey` consumer). Folding the pivot into R316 is a
deliberate override: it keeps this thread's id and history together. If a reviewer prefers
the audit's split, the move is a re-id, not a redesign.

## Slices

Slice 1 is the model decision; slices 2-4 lift the shipped work onto it; slice 5 is the
thoroughness gate. Land 1 first; 2-4 are sequenced by dependency, not parallel, because they
share the model types.

### Slice 1: Rewrite the R222 model

Revise [`dimensional-model-pivot.md`](dimensional-model-pivot.md) (R222), whose umbrella
explicitly licenses slices to "redraw the diagram as implementation slices land and surface
new understanding." Replace the `carrier x intent x mapping` target model with
`(source, operation, target)`: the `source` arrival seal, the `operation` arm set (arms empty,
payloads deferred to R314), the `target` endpoint with per-endpoint polarity, and the
re-derived re-fetch predicate. Documentation only; it pins the vocabulary every later slice speaks.

### Slice 2: The `source` seal in code

Fold `Carrier`'s arms into one `source` sealed hierarchy (`Root(RootKind)` /
`Child(SourceShape, SourceCardinality)`). `SourceShape` and `SourceCardinality` are no longer
top-level axes but survive as the payload of the `Child` arm (their internal reshaping,
`SourceShape.Record`'s reflected facts, is the downstream `SourceKey` work, not R316). Rename
`OutputField.carrier()` to `source()`; repoint its producers (`QueryField` / `MutationField`
build `Root`; `ChildField` builds `Child` over `Table` / `Record` and the carried cardinality)
and its readers. Retire `Carrier.java` as a standalone type; `SourceShape.java` /
`SourceCardinality.java` stay, now reachable only as `Child`'s slots.

### Slice 3: `operation` and `target` in code

Rename `Intent` to a sealed `Operation` and **pin the arm set** (`Fetch` / `Lookup` /
`ServiceCall` / the writes / the remaining values), `ServiceCall` collapsing today's
`QueryService` / `MutationService`. The arms ship **empty**: their payloads (`Fetch`'s
`List<WhereFilter>`, `Lookup`'s `LookupMapping`, `ServiceCall`'s `MethodRef`) have no reader
while the emit stays leaf-dispatched, so they are deferred to R314, landed per-kind alongside
the emit that reads them (see Relationships). Rename `Mapping` to `Target` and reinterpret its
catalog-vs-Java split as polarity. Rename `OutputField.intent()` to `operation()` and
`mapping()` to `target()`. Repoint all readers: `FieldBuilder`, `GraphitronSchemaValidator`,
`ServiceMethodCallEmitter`, `TypeFetcherGenerator`, the catalog classification
(`TypeClassification`, `FieldClassification`, `CatalogBuilder`).
**Carve-out:** `LookupMapping` and `MappingEntry` are the `@lookupKey` correspondence (the
eventual payload of `Operation.Lookup`), not the `Mapping` dimension. The `Mapping` to `Target`
rename must not sweep them. The retirement inventory lists them explicitly as do-not-touch.

### Slice 4: Lift R281 (focus) and the derived layer

The R281 classification corpus is the primary lift target. Migrate `DimensionTuple`
(`(Carrier, Intent, Mapping)` to `(Source, Operation, Target)`) and the `@classified` directive.
The directive has **five** arguments, not three: `intent:` / `mapping:` become `operation:` /
`target:`, and `carrier:` / `sourceShape:` / `sourceCardinality:` all fold into the `source:`
representation, the natural shape being a discriminator `source: {Query | Mutation | Child}`
plus `shape:` / `cardinality:` for the `Child` case, mirroring how `carrier:` + siblings
reconstitute `Carrier.Source` today. Migrate the `classifieddsl/*` harness (`ClassifiedCorpus`,
`ClassifiedDsl`, `ClassifiedHarness`, `QueryViewRenderer`) plus its tests
(`ClassifiedDslTest`, `QueryViewRendererTest`, `GraphitronSchemaBuilderTest`,
`SingleRecordPayloadPipelineTest`, `SourceShapeProjectionTest`, `TypeRegistryTest`,
`ConstructorFieldValidationTest`). Re-derive `OutputField.requiresReFetch()` over
`(source-polarity / operation, target-shape)` (a `Record` source or record-producing
`operation` crossing into a `Target.Table`), replacing the `mapping() != Mapping.Table` gate;
the existing `ReFetchDerivationTest` is migrated, not added.

The `dispatchPerformsReFetch` validator mirror is re-expressed over the new axes but **survives
R316 by design**: it guards the still-leaf-dispatched generator against `requiresReFetch()`
drift. Retiring it requires the emit to read `requiresReFetch()` directly, which is R314's
emit re-platforming, out of scope here. `SourceShapeProjectionTest` becomes a `source`-arm
projection test.

### Slice 5: Thoroughness gate

The no-remnants mandate, made enforceable rather than aspirational:

- **Code.** No `Carrier` / `Intent` / `Mapping` type remains in `graphitron/src` (excluding the
  `LookupMapping` / `MappingEntry` carve-out). `SourceShape` / `SourceCardinality` are *not*
  retired (they survive as `Source.Child`'s slots), so the remnant grep targets the three
  retired names only. Two distinct guards, not one: the *coverage* gate is the
  disjoint-exhaustive partition over the `source` and `Operation` seals (the shape
  `GeneratorCoverageTest.everyGraphitronFieldLeafHasAKnownDispatchStatus` already uses for leaf
  dispatch), proving every arm is handled; the *remnant* guard is a supplementary
  repository-grep for the retired type names, proving stale names and prose are gone, not merely
  shadowed. The grep is not a coverage check and must not be relied on as one.
- **Docs.** Sweep the four `.adoc` files that name the old axes
  (`code-generation-triggers.adoc`, `argument-resolution.adoc`, `typed-rejection.adoc`,
  `rewrite-design-principles.adoc`) to the new vocabulary. (`getting-started.adoc` does not name
  the axes, its only "intent" is colloquial, so it is out of scope.) The user-facing-doc check
  applies.
- **Changelog.** The R290/R299/R305 changelog entries describe a model that no longer
  exists; add a forward note so a reader is not misled, without rewriting history.

## Tests

- The `@classified` corpus is the behavioural backstop: migrated to the new axes, it must
  stay equivalent in *coverage* (every coordinate that classified before classifies after,
  to the renamed-but-corresponding verdict). `everyDimensionValueIsExercised` and the
  coverage/disjointness meta-tests carry over per axis (now folding `sourceShape:` /
  `sourceCardinality:` coverage under the `source:` arm).
- Slice 4 migrates the existing `ReFetchDerivationTest` onto the new axes (behavioural, no
  `code().toString()` body matches) and keeps its validator-mirror agreement check.
- The `source` seal gets a sealed-leaf exhaustiveness guard (what `SourceShapeProjectionTest`
  becomes); the arms are exercised by corpus coordinates with a documented `NOT_CORPUS_COVERED`
  for any arm no example reaches.
- Full reactor green (`mvn -f graphitron-rewrite/pom.xml install -Plocal-db`), including the
  execution tier, since the rename touches classification that the whole pipeline reads.

## Downstream: the `SourceKey` decomposition

The original `decompose-sourcekey` work becomes the first concrete consumer of the new model,
specced as its own follow-on once this pivot lands: `SourceKey.target` / `path` to the
`target` dimension, the source-object facts and the field's extraction signature to
`source`'s `SourceShape.Record` arm, the service call and its reflected result to
`Operation.ServiceCall`, leaving a `Record`-source-only field key. It is no longer in R316's
scope; the pivot is the prerequisite that gives those pieces honest homes.

## Relationships

- **R222** (dimensional-model-pivot): the umbrella this revises. Slice 1 rewrites its model
  section; R222 explicitly permits slices to redraw it.
- **R290 / R299 / R305**: the shipped `carrier x intent x mapping` materialisation, corpus
  migration, and `Carrier.Source` build. Slices 2-4 lift their output onto the new model;
  this item is where their vocabulary is retired.
- **R302** (rename-childfield-to-sourcefield): wanted to rename `ChildField` to `SourceField`
  to align the field name with `Carrier.Source`. R316 retires `Carrier.Source` and re-aligns
  `ChildField` with `Source.Child` instead, so this pivot likely moots or reverses R302.
- **R314** (dissolve-reentry-leaves-dimensional-emit): owns the emit re-platforming and, now,
  the `operation` **payloads**. R316 seals `Operation` and pins the arm set; R314 fills each
  arm's payload slot, **sliced per operation kind**, each slot landing in the same slice as the
  emit that reads it (the R222 slice pattern). It also dissolves the re-fetch leaves and retires
  the `dispatchPerformsReFetch` mirror R316 leaves standing.

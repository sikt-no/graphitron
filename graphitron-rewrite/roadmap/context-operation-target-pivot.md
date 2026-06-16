---
id: R316
title: "Pivot the field-dimensional model to (context, operation, target)"
status: Spec
bucket: structural
priority: 3
theme: structural-refactor
depends-on: [dimensional-model-pivot]
created: 2026-06-16
last-updated: 2026-06-16
---

# Pivot the field-dimensional model to (context, operation, target)

The field-dimensional model R290/R299/R305 shipped is `carrier x intent x mapping`. A
walk under the source-object / source-field vocabulary (the original `decompose-sourcekey`
thesis, see Lineage) shows the third axis is mislabeled and the first two are under-named.
The correct model is `(context, operation, target)`: a field is an edge from a source it
arrives into to a **target** it projects, spanned by an **operation** it performs. The
source endpoint is named **context** (its arms are arrival positions, not an `env.getSource()`
object). `mapping` is not a dimension; the catalog-vs-Java distinction it encodes is a
per-endpoint *polarity* that lives on `context` and reappears on `target`.

The full argument and the pros/cons of pivoting are in
[`audits/2026-06-16-source-operation-target-reframe.md`](audits/2026-06-16-source-operation-target-reframe.md)
(written before the source endpoint was named `context`; read `source` there as `context`).
This item is the decision to pivot and the plan to do it thoroughly. **The defining
constraint is thoroughness:** experience (R287, the staleness audits) shows that leaving
remnants of the retired model is a trap. The old vocabulary must be gone from the rewrite
scope when this item is Done, not merely shadowed by the new one.

## The target model

### context (the arrival endpoint)

A sealed hierarchy folding today's `Carrier`, `SourceShape`, and `SourceCardinality` into one
type whose arms are the field's arrival position, with arrival count folded into the arm
identity:

```
context     = Root(RootKind) | OnlyChild(SourceShape) | Child(SourceShape)
RootKind    = Query | Mutation
SourceShape = Table(<catalog metadata>) | Record(SourceObject, <extraction>)
```

- `Root(RootKind)` — an operation root; no parent arrives. `RootKind` (`Query` / `Mutation`)
  *is* the operation-legality gate (write operations only on `Mutation`, `NodeResolve` only
  on `Query`), so the gate stops being a separate concern.
- `OnlyChild` — nested, exactly one source object arrives (today's `SourceCardinality.One`).
- `Child` — nested, many arrive (`SourceCardinality.Many`).

So `context` is one coherent arrival axis (none / one / many), and `SourceCardinality`
dissolves into the `OnlyChild` / `Child` split. The nested arms carry `SourceShape`, the
catalog-vs-Java polarity of the parent:

- `Table` — a catalog table row parent; carries its catalog metadata (table ref / row
  identity), slot detailed in the implementation pass.
- `Record` — a producer-handed domain record parent; carries (a) a `SourceObject` descriptor
  (backing class, `env.getSource()` envelope, produced record type), the facts the field
  cannot change, and (b) the field's own **extraction signature** (how *this* field reads its
  value off the record), reusing the existing `AccessorRef` / `LifterRef` via the (narrowed)
  `SourceKey.Reader` family. This arm is exactly where the decomposed `SourceKey`'s
  Record-source pieces land (see Downstream).

### operation (the verb)

A sealed **payload-carrying** hierarchy, not a flat enum. Each arm carries the slots its kind
needs:

- `Fetch` — a catalog read. Carries the resolved filter surface `List<WhereFilter>`
  (`GeneratedConditionFilter | ConditionFilter`) plus ordering / pagination slots. Every
  field-argument filter binds against the **target**: generated conditions key off the
  return-type table, and user `@condition` (at field, argument, or input-field position) binds
  its single `REQUIRED` table slot to the target too. `@condition(override:true)` is resolved
  at classification time into which filters survive; override is *not* an operation fact, the
  arm carries only the resolved list.
- `Lookup` — the positional `@lookupKey` correspondence. Carries `LookupMapping` (key args to
  target key columns).
- `ServiceCall` — a developer `@service` invocation. Carries the service `MethodRef` and its
  params; arguments bind to method **parameters**, no table (the code is opaque). The lone
  arm whose arguments are not target-bound.
- the writes (`Insert` / `Update` / `Upsert` / `Delete`) and the remaining intent values, each
  with its DML / structural payload.

R316 builds this sealed hierarchy (renaming `Intent`); it is not deferred. The per-arm slot
design is slice 3's work; the spec pins the arm set and that each arm owns its own
argument-binding shape, which is the reason for sealed arms over a shared argument slot.

### target (the projection endpoint)

Today's `Mapping`, renamed and reinterpreted: the destination endpoint and its shape,
`Table` / `Column` / `Connection` (catalog) and `Record` / `Field` (Java). The catalog-vs-Java
split here is the target's *polarity*, a sibling of `context`'s `SourceShape` polarity rather
than standing in for both. The symmetry is the vindication: `SourceShape.Table` sits next to
`Target.Table`, and re-fetch is just a record-producing endpoint (`context` `Record` source or
a record-producing `operation`) crossing into a catalog `Target.Table`.

### the join path

The `@reference(path:)` join route is a slot bridging `context` to `target`, carrying per-hop
conditions that consume **context arguments only** (no field arguments are in scope at a
path-step `@condition`). So the path is not an argument-addressable surface; field arguments
bind `target`, and the path does not perturb the triple.

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
`(context, operation, target)`: the `context` arrival seal, the payload-carrying `operation`
hierarchy, the `target` endpoint with per-endpoint polarity, and the re-derived re-fetch
predicate. Documentation only; it pins the vocabulary every later slice speaks.

### Slice 2 — The `context` seal in code

Fold `Carrier`, `SourceShape`, and `SourceCardinality` into one `context` sealed hierarchy
(`Root(RootKind)` / `OnlyChild(SourceShape)` / `Child(SourceShape)`), with arrival folded into
the arms and the reflected facts on `SourceShape.Record`. Rename `OutputField.carrier()` to
`context()`; repoint its producers (`QueryField` / `MutationField` build `Root`;
`ChildField.sourceShape()` collapses into `Child` / `OnlyChild` construction over
`Table` / `Record`) and its readers. Retire `Carrier.java`, `SourceShape.java`,
`SourceCardinality.java` as standalone types.

### Slice 3 — `operation` and `target` in code

Rename `Intent` to a sealed payload-carrying `Operation` (`Fetch` / `Lookup` / `ServiceCall` /
writes), each arm carrying its slots (`Fetch`: `List<WhereFilter>` + ordering / pagination;
`Lookup`: `LookupMapping`; `ServiceCall`: `MethodRef`). Rename `Mapping` to `Target` and
reinterpret its catalog-vs-Java split as polarity. Rename `OutputField.intent()` to
`operation()` and `mapping()` to `target()`. Repoint all readers: `FieldBuilder`,
`GraphitronSchemaValidator`, `ServiceMethodCallEmitter`, `TypeFetcherGenerator`, the catalog
classification (`TypeClassification`, `FieldClassification`, `CatalogBuilder`).
**Carve-out:** `LookupMapping` and `MappingEntry` are the `@lookupKey` correspondence carried
*by* `Operation.Lookup`, not the `Mapping` dimension. The `Mapping` to `Target` rename must not
sweep them. The retirement inventory lists them explicitly as do-not-touch.

### Slice 4 — Lift R281 (focus) and the derived layer

The R281 classification corpus is the primary lift target. Migrate `DimensionTuple`
(`(Carrier, Intent, Mapping)` to `(context, Operation, Target)`), the `@classified` directive
arguments (`carrier:` / `intent:` / `mapping:` to `context:` / `operation:` / `target:`)
across every corpus example, and the `classifieddsl/*` harness (`ClassifiedCorpus`,
`ClassifiedDsl`, `ClassifiedHarness`, `QueryViewRenderer`) plus its tests
(`ClassifiedDslTest`, `QueryViewRendererTest`, `GraphitronSchemaBuilderTest`,
`SingleRecordPayloadPipelineTest`, `SourceShapeProjectionTest`, `TypeRegistryTest`,
`ConstructorFieldValidationTest`). Re-derive `OutputField.requiresReFetch()` over
`(context-polarity / operation, target-polarity)` (a `Record` source or record-producing
`operation` crossing into a `Target.Table`), replacing the `mapping() != Mapping.Table` gate.

The `dispatchPerformsReFetch` validator mirror is re-expressed over the new axes but **survives
R316 by design**: it guards the still-leaf-dispatched generator against `requiresReFetch()`
drift. Retiring it requires the emit to read `requiresReFetch()` directly, which is R314's
emit re-platforming, out of scope here. `SourceShapeProjectionTest` becomes a `context`-arm
projection test.

### Slice 5 — Thoroughness gate

The no-remnants mandate, made enforceable rather than aspirational:

- **Code.** No `Carrier` / `Intent` / `Mapping` / `SourceShape` / `SourceCardinality` type
  remains in `graphitron/src` (excluding the `LookupMapping` / `MappingEntry` carve-out). Two
  distinct guards, not one: the *coverage* gate is the disjoint-exhaustive partition over the
  `context` and `Operation` seals (the shape `GeneratorCoverageTest.everyGraphitronFieldLeafHasAKnownDispatchStatus`
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
- Slice 4 adds a `requiresReFetch` re-derivation test (behavioural, no `code().toString()`
  body matches) plus the validator-mirror agreement check.
- The `context` seal gets a sealed-leaf exhaustiveness guard (what `SourceShapeProjectionTest`
  becomes); the arms are exercised by corpus coordinates with a documented `NOT_CORPUS_COVERED`
  for any arm no example reaches.
- Full reactor green (`mvn -f graphitron-rewrite/pom.xml install -Plocal-db`), including the
  execution tier, since the rename touches classification that the whole pipeline reads.

## Downstream: the `SourceKey` decomposition

The original `decompose-sourcekey` work becomes the first concrete consumer of the new model,
specced as its own follow-on once this pivot lands: `SourceKey.target` / `path` to the
`target` dimension, the source-object facts and the field's extraction signature to
`context`'s `SourceShape.Record` arm, the service call and its reflected result to
`Operation.ServiceCall`, leaving a `Record`-source-only field key. It is no longer in R316's
scope; the pivot is the prerequisite that gives those pieces honest homes.

## Relationships

- **R222** (dimensional-model-pivot): the umbrella this revises. Slice 1 rewrites its model
  section; R222 explicitly permits slices to redraw it.
- **R290 / R299 / R305**: the shipped `carrier x intent x mapping` materialisation, corpus
  migration, and `Carrier.Source` build. Slices 2-4 lift their output onto the new model;
  this item is where their vocabulary is retired.
- **R314** (dissolve-reentry-leaves-dimensional-emit): owns the emit re-platforming. Because
  R316 now builds the payload-carrying `Operation` hierarchy (R314 had anticipated building
  it), **R314 shrinks to pure emit re-platforming**: switch on the model, dissolve the
  re-fetch leaves, and retire the `dispatchPerformsReFetch` mirror R316 leaves standing.

---
id: R290
title: "Field-side dimensional slots: materialise carrier x intent x mapping on the field"
status: In Progress
bucket: structural
priority: 4
theme: structural-refactor
depends-on: [dimensional-model-pivot]
created: 2026-06-10
last-updated: 2026-06-14
---

# Field-side dimensional slots: materialise carrier x intent x mapping on the field

This is R222's Stage 3 spin-out: the field-side half of the dimensional pivot. See R222's
**Field-side dimensional model (refined 2026-06-13)** for the target this slice implements. The summary:
a field's classification is `carrier x intent x mapping` plus a derived layer, and **the producer
dimension dissolves**. (An earlier draft of this item framed the field as carrying a "producer pipeline
over `{Query, Service, Dml}`, length <= 2, empty meaning inline-correlate"; that framing is retired,
there is no producer pipeline.)

Today a field's execution shape is encoded by its position in the fused
`QueryField` / `MutationField` / `ChildField` cross-product permit set, so `DataFetcherBuilder` reads the
leaf identity to decide how to fetch. This slice dissolves that cross-product: the field carries its
**carrier** (which *is* its type), **intent**, and **mapping** directly, and the fetcher/loader mechanism
is **derived** rather than switched on a leaf name:

- catalog vs domain (build-vs-consume) rides the `mapping` (`Table`/`Column` vs `Record`/`Field`); there
  is no separate provider to materialise.
- `FetchRelated` derives from the join-path slot.
- **re-fetch** derives from `(Service | DML intent) x Table mapping` — this is the old service/DML ->
  `@table` re-query (the earlier "`[Service, Query]`" pipeline), now a derived consequence of a
  domain/write producer yielding a catalog-table shape, not a chain the field stores.
- **new-query** (fold-vs-batch / split) derives from a `SourceField` slot forced by `@splitQuery` /
  polymorphic UNION / record-handoff.
- polarity (mutating?) derives from the intent family.

Concretely the service/DML re-query family (`QueryServiceTableField`, `MutationServiceTableField`,
`ChildField.ServiceTableField`, and the record-carrier siblings) stops deciding its re-fetch from leaf
identity and reads it off `intent x mapping` instead, and the slice **deletes R281's throwaway
`LeafTupleAdapter`** once the field exposes `(carrier, intent, mapping)` directly. Those service leaves
*survive* this slice (their leaf-identity consolidation is Stage 5); see **Spec: implementation shape**
below for the exact scope boundary and the two leaves R290 does retire.

## Leaf changes carried by this slice

The `ChildField` -> `SourceField` carrier rename is **split out to R302**
(`rename-childfield-to-sourcefield`): it is ~940 references of pure mechanical churn with no
behavioural change, and folding it into this slice's diff would bury the architectural change under
rename noise. R302 and this slice are independent (no ordering edge); whichever lands second rebases
trivially. The two leaf-set changes below stay here, because they change the corpus and are coupled to
the materialisation:

- **Dissolve `ConstructorField` (a misfeature; it falls)**: this leaf is wrong by design, and its only
  consumers are our own tests. "Not a live feature" is precise, not loose: no production schema depends on
  the shape; the leaf is reachable only when a child type sits under a `@table` parent *and* is
  independently record-backed by a `@service` return elsewhere (the `ResultType` arm of
  `FieldBuilder.classifyChildFieldOnTableType`, today constructing `new ConstructorField(...)`), yielding a
  `@table`-parent passthrough that materialises the child from the parent's own row. That the leaf carries a
  real fetcher arm (it sits in `TypeFetcherGenerator.IMPLEMENTED_LEAVES` with an `env -> env.getSource()`
  dispatch) and the execution coverage Record-fields Phase 1 shipped does **not** make it a feature we want
  to keep; it makes that coverage self-referential, exercising a shape that should never have classified
  cleanly. The classifier stops producing it: that `ResultType` arm becomes an `UnclassifiedField`
  rejection, and `GraphitronSchemaValidator` surfaces the table-and-service clash as a build-time error.
  Delete the leaf, its `LeafTupleAdapter` arm, its generator dispatch (`FetcherEmitter`,
  `TypeFetcherGenerator`, `CatalogBuilder`), and the test surface that exists only to serve it (enumerated
  under **Test surface that falls with `ConstructorField`** in Acceptance). Re-enabling a deliberate
  construct-from-table-row is deferred to a possible future `experimental_constructType` directive and is
  out of scope here.
- **Collapse `SingleRecordTableField` into `RecordTableField`**: its `(Service`/`DML) x Table` follow-up
  re-fetch is the same Source/Fetch/Table shape `RecordTableField` already carries. Fields that classify
  as `SingleRecordTableField` today reclassify to `RecordTableField` with their `(carrier, intent,
  mapping)` tuple unchanged; the single-source-object DataLoader-skip becomes a derived detail (computed
  from single-source cardinality off the slot), not a distinct leaf.

## Dependency status

The model-side prerequisites have cleared: the Stage 1 foundation (R238, `ServiceField` /
`ServiceMethodCall`) is in tree, and the executable acceptance corpus (R281 + R299) has shipped, so the
corpus already asserts `(carrier, intent, mapping)` and `LeafTupleAdapter` (under
`graphitron/src/test/.../classifieddsl/`) is a live artifact this slice deletes. The remaining
`depends-on: [dimensional-model-pivot]` edge is umbrella provenance, not an open block: R222-the-umbrella
stays in Spec while its slices land, and the refined field-side model this slice implements is settled.
Read the edge as "this is R222 Stage 3," not "blocked."

## Progress (in flight)

Landing in build-green slices on trunk:

- **Slice 1 (shipped)**: `carrier()` / `intent()` / `mapping()` materialised on the field model
  (the dimension enums moved to the `model` package; `carrier()` defaults per carrier root, and
  `intent()` / `mapping()` are per-carrier default-method switches reproducing the retired
  `LeafTupleAdapter` exactly). `LeafTupleAdapter` deleted; the corpus harness reads the three
  accessors off the field. Corpus byte-identical.
- **Slice 2 (shipped)**: `ConstructorField` dissolved. The classifier's `ResultType` arm rejects with
  a build-time error `GraphitronSchemaValidator` surfaces; the leaf, its dispatch, and its test
  cluster are removed/retargeted (the `constructor` corpus example became the
  `ConstructorFieldValidationTest` rejection fixture). Live leaves 49 -> 48.
- **Slice 4 (shipped)**: the re-fetch derivation made real. `OutputField.requiresReFetch()` is the
  single home of the service/DML -> `@table` re-query predicate (derived from `intent x mapping`);
  `GraphitronSchemaValidator` is the consumer/mirror, asserting the derivation agrees with the
  generator's actual re-fetch dispatch so the two cannot drift. (The spec named `ValidationBuilder` /
  `QueryBuilder` as the mirror/sibling consumers; neither class exists. `GraphitronSchemaValidator`
  is the validator and the mirror home; `TypeFetcherGenerator` is the generator-side consumer.)
- **Slice 3 (deferred to a follow-up item)**: collapsing `SingleRecordTableField` into
  `RecordTableField`. On implementation this proved to be more than a leaf merge: the two leaves use
  *different emit mechanisms* (an inline `env.getSource()`-reading follow-up SELECT with no
  DataLoader vs. a DataLoader/method-backed batched rows-method), so the "DataLoader-skip becomes a
  derived detail" framing requires teaching `RecordTableField`'s emit a second in-hand-source mode
  and threading it through the registration emitter, dispatch partition, and validator. That is a
  fetcher-emit refactor with execution-tier risk that overlaps Stage 5's permit consolidation; per
  R222's "Direction, not contract" clause it is split to its own item rather than forced here. With
  Slice 3 deferred the live leaf set is **48**, not the appendix's 47; the appendix's 47 is the
  post-collapse target the follow-up item reaches.

## Spec: implementation shape

The decisions below are load-bearing for this slice; R222's "Direction, not contract" clause still
licenses redrawing detail that surfaces in implementation, so long as the slot home, the derivation
proof, and the corpus-invariance gate hold.

### Slot home and shape

`carrier`, `intent`, and `mapping` land as **three narrow accessors** on the field model
(`carrier()`, `intent()`, `mapping()`), each returning its own typed value, **not** a single neutral
`DimensionTuple` component. A tuple carries no information its three parts don't already carry and
obscures which axis each consumer forks on (the legality gate reads `carrier`, polarity reads the
`intent` family, build-vs-consume reads `mapping`, re-fetch reads `intent x mapping` jointly); three
accessors keep each consumer reading exactly the axis it forks on, per "narrow component types" and
"sub-taxonomies carry distinct information." The test-side `DimensionTuple` record (the corpus's
assertion shape) stays as-is; the *field model* exposes the three axes directly, and the harness builds
its `DimensionTuple` by reading the three accessors off the field.

The home is the **field root that survives R222 Stage 6** — today `OutputField`, which Stage 6 renames
to `GraphitronField` after the input/output split dissolves. The triple is universal across every output
field (R299 proved total reconstruction from leaf identity), so the root is the narrowest interface that
names the property. These three are a **total classification, not an optional signal**: every field has
a carrier, an intent, and a mapping, with no "this field has no intent" state, so the `No<Family>`
absence arm that R222's absence-encoding principle attaches to *field-universal optional* slots does not
apply here, and neither does R238's interface-gated absence (that pattern is for *directive-gated* slots
like `ServiceMethodCall`, which not every field carries). Stating this explicitly so the
field-universal-slot-without-`No<>`-arm shape reads as deliberate, not as a missed absence arm.

The **carrier x intent legality** (write intents only on `Mutation`; `NodeResolve` / `EntityResolve`
only on `Query`; `Nesting` only on `Source`) is enforced where the field is constructed (the classifier
in `FieldBuilder` / `CatalogBuilder`), and `GraphitronSchemaValidator` mirrors it, per "validator mirrors
classifier invariants." (If implementation finds the per-construction-site checks want consolidating, a
small validated holder whose compact constructor pins the legality is acceptable — but it would be named
for that job, not as a neutral tuple.)

### Scope boundary (Stage 3, not Stage 5)

This slice **materialises the slots and makes one derived facet real**; it does **not** rip out the
~5300-line `TypeFetcherGenerator` per-leaf switch or delete the broad cross-product permit set. That
wholesale consumer migration and permit retirement is R222 **Stage 5** (the sync point on Stages 2+3).
What R290 does:

1. **Materialise** `carrier()` / `intent()` / `mapping()` on the field root, computed at classification
   time, reproducing exactly what `LeafTupleAdapter` reconstructs today.
2. **Delete `LeafTupleAdapter`**; the corpus harness reads the three accessors off the field.
3. **Make the re-fetch derivation real** as the proof that the slot earns its keep: the service/DML ->
   `@table` re-query, today decided by leaf identity in the consumer, derives instead from
   `(QueryService | MutationService | DML-write intent) x Table mapping`. The consumer
   (`TypeFetcherGenerator`, and `ValidationBuilder` as the mirror) computes the re-fetch step from the
   `intent()` x `mapping()` slots once, rather than each service/DML arm re-deciding it from its leaf
   type. Materialising the slots without landing a derivation would ship vocabulary no consumer pulls on
   (the rejected "horizontal phase 1" smell) and leave the re-fetch predicate duplicated across consumers
   (the "generation-thinking" smell); the derivation has to land here for the materialisation to mean
   anything.
4. **Retire exactly the two leaves the model removes outright** — **dissolve `ConstructorField`**
   (a wrong-by-design table-and-service construction whose only consumers are tests; the classifier rejects
   it via `UnclassifiedField` and `GraphitronSchemaValidator`) and **collapse `SingleRecordTableField` into
   `RecordTableField`** (its
   single-source DataLoader-skip becomes a derived detail). This brings the live leaf set from 49 to the
   appendix's
   **47**, which is the post-R290 inventory. The service re-query leaves (`QueryServiceTableField`,
   `MutationServiceTableField`, `ChildField.ServiceTableField`) **survive R290 as leaves** — the appendix
   lists all three, with re-fetch shown as derived (`RF`). R290 makes their *mechanism* slot-derived;
   their leaf-*identity* consolidation (merging the redundant permits onto a single service-backed field
   record) needs a consolidation target this slice should not invent, so it stays with Stage 5's broad
   permit merge.

**Reconciliation with Stage 5.** R290's permit-set delta is exactly two leaves (`ConstructorField`,
`SingleRecordTableField`); it does **not** pre-empt Stage 5's cross-product permit consolidation. What
R290 hands Stage 5 is the slot-derived re-fetch (so the service leaves' dispatch already reads
`intent x mapping`), leaving Stage 5 the narrower mechanical job of merging the now-behaviourally-identical
service permits. A future Stage 5 author treats its retirement list as "the cross-product permits, minus
the two R290 removed."

## Acceptance

R281 (`classification-test-dsl`, shipped) plus R299 (`intention-classification-dimension`) are this
item's **executable acceptance spec**: the corpus asserts `(carrier, intent, mapping)`, not leaf names,
not the retired `producer`. When this slice lands the adapter is deleted and the harness reads the three
accessors off the field. The classified-corpus delta is exactly **one example**: the `constructor`
fixture (`ClassifiedCorpus`, asserting `Film.details` as Source/Fetch/Record) leaves the classified
corpus, because that table-and-service shape is now a validator rejection rather than a clean
classification; it moves to the validator tier as a rejection fixture. **Every other corpus assertion
stays byte-identical**, including `SingleRecordTableField`'s coverage (`FilmPayload.film`, Source/Fetch/
Table), which reclassifies to `RecordTableField` with its tuple unchanged and so stays green without
edit. The continued green of every other row, including all three surviving service re-query leaves now
classifying via slots with re-fetch derived, proves the decomposition was behaviour-preserving.
`everyDimensionValueIsExercised` stays green: `Mapping.Record` is still exercised after the `constructor`
example leaves (e.g. `ErrorsField`, `ServiceRecordField`, the DML record carriers). The live leaf set
moves from 49 to **47** (the appendix inventory): the dispatch-partition coverage test
(`GeneratorCoverageTest.everyGraphitronFieldLeafHasAKnownDispatchStatus`, see the **Dispatch partition +
validator mirror** gate below) stays green with two fewer partition entries, and this appendix updates in
the same change. The generated `roadmap/inference-axis-coverage.adoc` enumerates the live leaves off the
model but is a CI-regenerated, data-free stub (R132 dropped the local `mvn verify` gate; trunk push
regenerates it), so it needs no manual edit here.

**Test surface that falls with `ConstructorField`.** The leaf is wrong by design and its only consumers
are tests, so retiring it deletes or retargets that whole cluster in the same change. This is the slice's
**one deliberate behavioural change**, distinct from the behaviour-preserving reclassifications around it:

- the `constructor` corpus fixture (`ClassifiedCorpus`, `Film.details`, today asserting Source/Fetch/
  Record) leaves the classified corpus and moves to the validator tier as a rejection fixture;
- `GraphitronSchemaBuilderTest.constructorField_tableParentRecordChild_classifiedAsConstructorField` and
  its `@ProjectionFor` sibling `constructorFieldProjectionIsZeroPayload` are deleted — the verdict and the
  `FieldClassification.Constructor` projection they assert no longer exist;
- `ConstructorFieldValidationTest` is retargeted from asserting a clean construction to asserting the new
  build-time rejection;
- the `SingleRecordPayloadPipelineTest` arm asserting "ConstructorField has its own dispatch arm" and the
  `DummyFetcherFixtures` constructor-child execution fixture (the self-referential coverage Record-fields
  Phase 1 shipped) are removed.

Every corpus assertion *other than* the `constructor` example stays byte-identical; the decomposition is
behaviour-preserving for every shape that survives, and the `ConstructorField` removal is the one shape
that does not.

The merge gate is both tiers green:

- **Corpus tier** (R281/R299): the decomposition is behaviour-preserving (byte-identical modulo the one
  removed `constructor` example, now a validator-rejection fixture; `SingleRecordTableField`'s coverage
  reclassifies to `RecordTableField` with its tuple unchanged).
- **Dispatch partition + validator mirror.** Retiring the two leaves means the `TypeFetcherGenerator`
  dispatch partition (`IMPLEMENTED_LEAVES` / `NOT_DISPATCHED_LEAVES` / `PROJECTED_LEAVES` /
  `STUBBED_VARIANTS`) drops those two entries in the same commit, and the coverage test asserting that
  partition is an exhaustive disjoint cover of every `GraphitronField` leaf stays green. The re-fetch
  derivation reads off `intent x mapping` at the consumer; `GraphitronSchemaValidator` mirrors that
  derivation so an unimplemented branch still fails at validate time, per "validator mirrors classifier
  invariants."
- **Pipeline / `TypeSpec` tier** for the slot-level emit: a derived-slot change keeps the corpus green,
  so emit-shape assertions for the collapsed re-query family stay the pipeline tier's job (structural
  `TypeSpec` assertions, no `code().toString()` body matches, per `rewrite-design-principles.adoc`).

The same corpus drives `TypeFetcherGenerator` (the generator-side consumer) and
`GraphitronSchemaValidator` (the validator/mirror); the Stage 1 foundation (`ServiceField` /
`ServiceMethodCall`) has landed. (An earlier draft named `QueryBuilder` / `ValidationBuilder` as
sibling Stage 3 consumers; neither class exists in the rewrite tree, the actual consumer/mirror are
the two named here.) See the R281 entry in `roadmap/changelog.md`.

## Appendix: leaf inventory (the verdicts R290 materialises)

Every current `OutputField` leaf under the `carrier × intent × mapping` model, with the derived columns
shown. This is the worked target R290 implements; **totality holds, no leaf has an unfilled cell.**
`ConstructorField` (dissolved; now a validator rejection) and `SingleRecordTableField` (collapsed into
`RecordTableField`) are absent by design, the leaf set is 47. Derived legend: `FR` = `FetchRelated` (from a
non-empty join-path), `RF` = re-fetch (from a `(Service`|`DML`) × `Table` mismatch), `NQ` = new-query
(`SourceField` slot, forced by `@splitQuery` / polymorphic / record-handoff). The orthogonal slot column
(method, batch-key, join-path, bulk, composite, participants) carries per-leaf detail; the triple is the
classification, not the whole emit-determinant.

### Query carrier (`QueryField`)

| Leaf | intent | mapping | derived | slot |
|---|---|---|---|---|
| QueryTableField | Fetch | Table | | |
| QueryLookupTableField | Lookup | Table | | |
| QueryTableMethodTableField | Fetch | Table | | method |
| QueryTableInterfaceField | Fetch | Table[poly] | | participants |
| QueryInterfaceField | Fetch | Table[poly] | | participants |
| QueryUnionField | Fetch | Table[poly] | | participants |
| QueryNodeField | NodeResolve | Table[poly] | | |
| QueryNodesField | NodeResolve | Table[poly] | | (list) |
| QueryServiceTableField | QueryService | Table | RF | |
| QueryServiceRecordField | QueryService | Record | | |

### Mutation carrier (`MutationField`)

| Leaf | intent | mapping | derived | slot |
|---|---|---|---|---|
| MutationInsertTableField | Insert | Column / Table | RF (when Table) | |
| MutationUpsertTableField | Upsert | Column / Table | RF (when Table) | |
| MutationUpdateTableField | Update | Column / Table | RF (when Table) | |
| MutationDeleteTableField | Delete | Column | | encoded-id only (R287) |
| MutationServiceTableField | MutationService | Table | RF | |
| MutationServiceRecordField | MutationService | Record | | |
| MutationDmlRecordField | Insert/Update/Upsert | Record | | |
| MutationBulkDmlRecordField | Insert/Update/Upsert | Record | | bulk |
| MutationUpdatePayloadField | Update | Record | | |
| MutationBulkUpdatePayloadField | Update | Record | | bulk |
| MutationDeletePayloadField | Delete | Record | | |
| MutationBulkDeletePayloadField | Delete | Record | | bulk |

### Source carrier (`SourceField`; rename from `ChildField` is R302)

| Leaf | intent | mapping | derived | slot |
|---|---|---|---|---|
| ColumnField | Fetch | Column | | |
| ColumnReferenceField | Fetch | Column | FR | join-path |
| ParticipantColumnReferenceField | Fetch | Column[poly] | FR | join-path, participant |
| CompositeColumnField | Fetch | Column | | composite |
| CompositeColumnReferenceField | Fetch | Column | FR | composite, deferred-stub |
| TableField | Fetch | Table | FR | (inline) |
| LookupTableField | Lookup | Table | | (inline) |
| TableInterfaceField | Fetch | Table[poly] | FR | participants (R288) |
| TableMethodField | Fetch | Table | FR | method |
| SplitTableField | Fetch | Table | FR, NQ | batch-key |
| SplitLookupTableField | Lookup | Table | NQ | batch-key |
| RecordTableField | Fetch | Table | FR, NQ | record-key |
| RecordLookupTableField | Lookup | Table | NQ | record-key |
| RecordTableMethodField | Fetch | Table | FR, NQ | method, record-key |
| ServiceTableField | QueryService | Table | RF | |
| ServiceRecordField | QueryService | Record | | |
| RecordField | Fetch | Field | | |
| PropertyField | Fetch | Field | | |
| ComputedField (`@externalField`) | Fetch | Field / Record | | reflection (reclassified from `Column`) |
| NestingField | Nesting | Table | | (reclassified from `Fetch`) |
| InterfaceField | Fetch | Table[poly] | FR, NQ | participants |
| UnionField | Fetch | Table[poly] | FR, NQ | participants |
| SingleRecordIdFieldFromReturning | Fetch | Column | | reads RETURNING |
| SingleRecordIdField | Fetch | Column | | encode-from-record |
| ErrorsField | Fetch | Record | | reads Outcome |

### Connection protocol roles (Source carrier; not current leaves)

Behind the ConnectionType quarantine; classifying them is a separate item. Their intents (`Count`,
`Facet`) are among the declared model-completeness gaps.

| Role | intent | mapping | derived |
|---|---|---|---|
| edges | Fetch | Table | NQ |
| totalCount | Count | Column | |
| facets | Facet | Record | |
| nodes | Fetch | Table | |
| pageInfo | Fetch | Record | |

Two reclassifications this materialisation forces, called out because they change live leaves:
`ComputedField` (`@externalField`) moves catalog `Column` → domain `Field`/`Record` (provider/mapping
classifies epistemic role, not SQL location), and `NestingField` moves `Fetch` → `Nesting`. Five intents
are modeled-but-unpopulated by the current leaf set (declared gaps): `EntityResolve`, `Count`, `Facet`,
`UpdateMatching`, `DeleteMatching`.

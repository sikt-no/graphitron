---
id: R178
title: Retire single-record carrier walk; collapse to SourceKey + R96 reflection
status: In Progress
bucket: architecture
priority: 7
theme: mutations-errors
depends-on: []
created: 2026-05-18
last-updated: 2026-05-19
---

# Retire single-record carrier walk; collapse to SourceKey + R96 reflection

> The single-record carrier walk
> (`BuildContext.tryResolveSingleRecordCarrier`, `classifyCarrierField`,
> the `SingleRecordCarrierShape` / `CarrierFieldRole` / `DataElement` /
> `PerFieldOutcome` / `PkResolution` model trees, the four
> `register*CarrierDataField` helpers, the four
> `ChildField.SingleRecord*` per-field permits, and the
> `Reader.ResultRowWalk` SourceKey arm) is a parallel classification
> regime that duplicates what `SourceKey` + `Reader.AccessorCall` + R96's
> `RecordBindingResolver` already do. Every piece of work it
> accomplishes - PK-keyed projected SELECT from a producer's source row,
> parent-record-keyed accessor reads, target-table binding, payload-
> shape classification - is expressible through the standard SourceKey
> + reflection machinery used everywhere else in the classifier. R178
> deletes the parallel hierarchy and routes every payload-returning
> mutation (`@service` and `@mutation`-DML, single and bulk) through the
> standard `@record`-parent + SourceKey path. The mutation-root permits
> that today identify payload-returning shapes
> (`MutationField.MutationDmlRecordField`,
> `MutationBulkDmlRecordField`, `MutationServiceRecordField`) **stay**;
> what changes is their classification path - the carrier-walk
> consultation moves out, the permits are constructed directly from the
> parsed directive + resolved input table + reflected error channel.

## The reported bug that exposed the duplication

```graphql
type SettKvotesporsmalAlgoritmePayload
    @record(record: { className: "...records.SettKvotesporsmalAlgoritmePayload" }) {
    kvotesporsmal: Kvotesporsmal! @field(name: "kvotesporsmal")  # works
}

type SettKvotesporsmalAlgoritmePayload
    @record(record: { className: "...records.SettKvotesporsmalAlgoritmePayload" }) {
    kvotesporsmal: Kvotesporsmal!                                # rejects
}
```

Removing the redundant `@field(name:)` flips classification because the
carrier walk's forbidden-directives loop
(`BuildContext.classifyCarrierField`, BuildContext.java:1050-1068) hard-
rejects `@field` on a carrier data field whose value is not the
`$source` sigil. The first schema falls through to the standard
`@record`-parent path and works; the second is admitted into the carrier
walk and `FieldBuilder.registerServiceCarrierDataField`
(FieldBuilder.java:2911) demands the `@service` method return
`KvotesporsmalRecord` instead of `SettKvotesporsmalAlgoritmePayload`.
The error message is misleading because it cites "the field's declared
return type" but reports the inner table's record class:

```
method 'settKvotesporsmalAlgoritme' must return
'no.sikt.fs.opptak.generated.jooq.regelverk.tables.records.KvotesporsmalRecord'
to match the field's declared return type
- got 'no.sikt.fs.opptak.regelverk.kvoteplassering.records.SettKvotesporsmalAlgoritmePayload'
```

Two semantically identical schemas should produce identical classification.
The fix is structural: eliminate the regime that produces the divergence.

## Where the duplication lives

`FetcherEmitter.buildSingleRecordTableFetcherValueRecordWrap`
(FetcherEmitter.java:330-446) does exactly what every child-on-`@record`-
parent fetcher does: pull a key off `env.getSource()`, run a projected
SELECT against the inner table filtered by that key, with column
selection driven by `env.getSelectionSet()`. The novelty is solely
"where does the key come from" - a DML's `RETURNING` row instead of a
catalog-FK column or a typed accessor on a `@record` parent. That
distinction belongs on the Reader axis, not in a parallel classification
hierarchy.

Each carrier-walk piece maps to standard machinery already in the
classifier:

- `CarrierFieldRole.DataChannel` / `ErrorChannelRole` → the per-field
  question `FieldBuilder.resolveRecordAccessor` (FieldBuilder.java:3816)
  already answers on `@record`-parent children.
- `DataElement.Table` / `Record` / `Id` → Reader-arm choices.
  `Table` → the `@splitQuery`-shaped `Reader.ColumnRead` + projected
  SELECT (see §"Bulk DML reference"); `Record` → identity passthrough
  via `$source` or name-matched `Reader.AccessorCall`; `Id` → encoded-
  NodeId via an accessor returning a PK column, through the existing
  `HelperRef.Encode` chain.
- `SingleRecordCarrierShape` invariants (one DataChannel, optional
  ErrorChannel) → dissolve under the multi-field direction (R128); each
  SDL field on the payload classifies independently.
- `classifyDeleteTableProjection` / `PerFieldOutcome` → `resolveRecordAccessor`
  with one new outcome arm: "accessor names a PK column."
- `carrierProducerRegistry` + the `single-producer-kind` reconciliation
  → vanishes; it exists only to reconcile the carrier walk's two
  encodings (`Wrap.Record` for DML, `Wrap.TableRecord` for `@service`)
  of the same coord.
- The four `register*CarrierDataField` helpers and the carrier-walk arm
  of the schema-builder loop (`GraphitronSchemaBuilder.buildSchema`
  GraphitronSchemaBuilder.java:217-256) → collapse to the single
  accessor-classification path.

## The target model

One classification path for every field on a payload-returning mutation:

1. **The producer determines `env.getSource()`**. `@service` methods
   return whatever the developer's signature says. DML mutation fetchers
   return `RecordN<...>` / `Result<RecordN<...>>` (same as today's
   carrier walk produces; the shape is unchanged). Root resolvers return
   what they return.
2. **R96 reflection determines binding**. `RecordBindingResolver`
   already reflects on producer return types to populate SDL → backing-
   class bindings. Under R178 it gains one new producer: a DML mutation
   fetcher's reflected output shape (`RecordN<...>` / `Result<RecordN<...>>`)
   as the producer-side binding for its payload SDL type. The binding
   names the producer-emitted row shape; no class is generated. Each SDL
   field on the payload type looks up its column on the bound row shape
   by name.
3. **Each SDL field has its own DataFetcher**, classified through the
   standard `FieldBuilder.classifyField` path against the resolved
   parent backing shape, with a `SourceKey` + `LoaderRegistration`
   describing the read. Resolution is name-based: an SDL field's name
   (or its `@field(name:)` override) looks up an accessor / column on
   the parent row's reflected shape. Two reserved sigils carry the
   cases where no name lookup applies: `@field(name: "$source")` reads
   `env.getSource()` directly (identity passthrough of the producer's
   reflected return value, used when the SDL field's value is the whole
   source - e.g. `svar: [KvoteSporsmalSvar!]!` against an `@service`
   returning `List<SakKvotesporsmalSvarRecord>`), and
   `@field(name: "$errors")` reads `env.getLocalContext()` (per the
   `ErrorChannel.LocalContext` contract under §"What stays" below).
   The errors-field defaulting rule fixes three sub-cases:
   `@field(name: "$errors")` always selects the localContext transport
   (no accessor lookup); `@field(name: "<literal>")` (explicit non-
   sigil, including the literal `"errors"`) always selects the payload-
   accessor transport with no localContext fallback (the author opted
   out of the default fallback by writing the directive); an SDL field
   named `errors` with no `@field` directive on a payload with an
   active error channel resolves the payload class's `errors` accessor
   first and falls back to localContext only when no accessor matches.
   Other field names resolve by accessor name only; the localContext
   fallback fires solely for the bare `errors` default. The
   `selectErrorsTransport` rule table at `FieldBuilder` pins this
   decision; the unit-tier `ErrorsTransportSelectionTest` is the
   contract. No DTOs are generated.
4. **The `@record` directive is irrelevant**. R96 already makes it
   informational; the deprecate-record-directive item retires it from
   the codegen surface entirely. R178 removes the last classifier
   reference to it (the carrier walk's directive-ignored warning path).

## Concrete deletions

Model types:
- `BuildContext.SingleRecordCarrierResolution` (sealed: `NotCandidate` /
  `Rejected` / `Ok.NoBacking` / `Ok.ClassBacked`)
- `SingleRecordCarrierShape`
- `CarrierFieldRole` (sealed: `DataChannel` / `ErrorChannelRole`)
- `DataElement` (sealed: `Table` / `Record` / `Id`)
- `BuildContext.DeleteTableProjection` (sealed: `Admitted` / `Rejected`)
- `BuildContext.PerFieldOutcome`
- `PkResolution`
- `ChildField.SingleRecordTableField`
- `ChildField.SingleRecordTableFieldFromReturning`
- `ChildField.SingleRecordIdentityField`
- `ChildField.SingleRecordIdFieldFromReturning`
- `SourceKey.Reader.ResultRowWalk` (the permit, not a free-standing type)

The three payload-returning mutation-root permits
(`MutationField.MutationDmlRecordField`,
`MutationField.MutationBulkDmlRecordField`,
`MutationField.MutationServiceRecordField`) **stay**. Their compact-
constructor shapes are already structurally aligned with
`QueryField.QueryServiceRecordField`; what changes is their
classification path - the carrier-walk consultation
(`tryResolveSingleRecordCarrier`, `register*CarrierDataField`,
`requireDataTableMatchesInputTable`, `classifyServiceCarrierProducer`,
`checkSourceSigilTypeMatch`) is removed from inside the classifier and
each permit is constructed directly from the parsed `@mutation` /
`@service` directive + resolved input table. The
`Optional<ResultAssembly>` slot is not added to the two DML permits;
"more like `QueryServiceRecordField`" refers to the classification
path, not field-by-field permit alignment, and the DML producer is
generator-emitted with no service method whose return type would bind
to a payload-class canonical constructor. The slot stays on
`MutationServiceRecordField` (where it already lives, mirroring
`QueryServiceRecordField`).

Classifier methods:
- `BuildContext.tryResolveSingleRecordCarrier(String)`
- `BuildContext.tryResolveSingleRecordCarrier(String, DmlKind)`
- `BuildContext.classifyCarrierField`
- `BuildContext.classifyDeleteTableProjection`
- `BuildContext.classifyElementFieldForDeleteProjection`
- `BuildContext.carrierProducerRegistry`
- `TypeBuilder.promoteSingleRecordCarriers`
- `FieldBuilder.classifyServiceCarrierProducer`
- `FieldBuilder.registerDmlCarrierDataField`
- `FieldBuilder.registerServiceCarrierDataField`
- `FieldBuilder.registerDeleteCarrierDataField`
- `FieldBuilder.registerCarrierDataFieldImpl`
- `FieldBuilder.requireDataTableMatchesInputTable`
- `FieldBuilder.checkSourceSigilTypeMatch`
- `GraphitronSchemaBuilder.registerCarrierDataField` and the carrier-walk
  arm of the schema-builder loop

Emitters:
- `FetcherEmitter.buildSingleRecordTableFetcherValue`
- `FetcherEmitter.buildSingleRecordTableFetcherValueRecordWrap`
- `FetcherEmitter.buildSingleRecordTableFetcherValueTableRecordWrap`
- `FetcherEmitter.buildSingleRecordIdFromReturningFetcherValue`
- `FetcherEmitter.buildSingleRecordTableFromReturningFetcherValue`

`TypeFetcherGenerator.buildMutationDmlRecordFetcher` and
`buildMutationBulkDmlRecordFetcher` stay alongside the two
`MutationField.Mutation*DmlRecordField` permits they consume; the DML
fetcher still emits `RecordN<PK>` / `Result<RecordN<PK>>` into
`env.getSource()` exactly as today. What changes is who builds the
payload-child fetchers off that source: the unified `classifyField`
path emits standard `PropertyField` / `RecordTableField` / `ErrorsField`
permits whose existing fetchers read PK columns via `Reader.ColumnRead`
on a plain `Record` cast, replacing the typed `RecordN<...>` cast in the
deleted `buildSingleRecordTableFetcherValueRecordWrap` arm.

`@LoadBearingClassifierCheck` keys:
- `single-record-carrier-shape.roles-exhaustively-classified`
- `mutation-dml-record-field.data-table-equals-input-table`
- `mutation-delete-carrier.pk-resolution-projection-clean`
- `carrier-data-field.single-producer-kind`
- `carrier-data-field.service-producer-strict-return`
- `source-key.result-row-walk-target-aligned-empty-path`

R159's `$source` sigil stays as the explicit identity-passthrough
mechanism (see §"Target model" #3 above). What retires from R159 is the
carrier walk's binding-confirmation role for the sigil
(`FieldBuilder.checkSourceSigilTypeMatch`,
`FieldSourceSigil.sourceSigilTypeMatches` against
`CarrierFieldRole.DataChannel.sourceSigil()`); under R178 the sigil
classifies through the standard field-classifier Reader arm with
type-match enforced uniformly with every other field.

## What stays / consolidates

- **The three payload-returning mutation-root permits** -
  `MutationField.MutationDmlRecordField`,
  `MutationField.MutationBulkDmlRecordField`,
  `MutationField.MutationServiceRecordField` - keep their compact-
  constructor shapes and stay the classification target for payload-
  returning mutations. The carrier-walk binding is removed from
  *around* them, not from inside: the classifier constructs each permit
  directly from the parsed `@mutation` / `@service` directive (DML kind,
  input `@table`, error channel) without consulting
  `tryResolveSingleRecordCarrier` or the `register*CarrierDataField`
  helpers. Their fetcher emitters
  (`TypeFetcherGenerator.buildMutationDmlRecordFetcher`,
  `buildMutationBulkDmlRecordFetcher`, the shared service-fetcher path
  used by `buildMutationServiceRecordFetcher`) stay unchanged. The
  error-channel resolution that today reads from the carrier shape
  (`ok.shape().errorChannel()`) moves into a standalone reflection
  walk over the payload SDL: scan for an errors-shaped field, derive
  `Optional<ErrorChannel>` from the polymorphic-of-`@error` member set
  and the payload class's structure, independent of the deleted
  `SingleRecordCarrierShape`. The result is shape-identical to today's
  carrier-walk output; the producer site changes.
- **The "PK columns off the source row, projected SELECT keyed by those
  PKs" fetcher logic** is the only emit work the carrier walk did that
  isn't already present elsewhere - and it already exists, under
  `@splitQuery`. The `@splitQuery`-shaped derivation
  (`FieldBuilder.deriveSplitQuerySource` at FieldBuilder.java:3956)
  produces `SourceKey(Wrap.Row, Reader.ColumnRead)` +
  `LoaderRegistration(POSITIONAL_LIST, LOAD_ONE)` against a parent
  exposing the PK columns; the DataLoader runs the projected SELECT.
  The DML payload case is a *new producer* for this existing consumer:
  the parent is a sparse `RecordN<PK>` from a DML-emitted source
  instead of a full `@table`-rooted record. `ColumnRead`'s body
  (`parent.get(<column>)`) is shape-agnostic - a `RecordN<PK>` exposes
  the PK columns the reader needs - so no `Reader` arm changes and no
  Reader axis widens. The deleted `source-key.result-row-walk-target-
  aligned-empty-path` invariant does not migrate to a new key: its
  three commitments dissolve at the unified emit site - (a) "wrap is
  `Record` / `TableRecord(target.recordClass())`" is irrelevant under
  `Wrap.Row` + `Reader.ColumnRead`; (b) empty path is given by
  `deriveSplitQuerySource` passing `List.of()` for `path` (FieldBuilder.java:3956);
  (c) target-aligned is given by the caller's choice of `parentTable`
  equal to the DML producer's `TableRef`, itself guaranteed by
  `record-binding.producer-agreement` (§"What stays" #3). No emitter
  consumer reads the deleted invariant directly; no new
  `@DependsOnClassifierCheck` is needed on `deriveSplitQuerySource`'s
  caller.
- **Error channel mechanism survives; only the carrier-walk wrapper
  retires.** The runtime contract is unchanged: the mutation wrapper
  emits `DataFetcherResult.newResult().data(<producerResult>).localContext(<mappedErrors>).build()`
  on every dispatch arm - empty list on the success arm, the
  per-handler-resolved error-type instances on the mapped-exception
  arm, the violation-derived error types on the validation arm. The
  payload's errors SDL field reads from `env.getLocalContext()`. The
  `ErrorChannel.LocalContext` model arm and the
  `ChildField.Transport.LocalContext` per-field arm both survive R178
  unchanged; what retires is `CarrierFieldRole.ErrorChannelRole`, the
  carrier-walk wrapper that binds them to the carrier shape. Each
  payload field is classified independently through
  `FieldBuilder.classifyField`: a field marked
  `@field(name: "$errors")` (or one named `errors` by default, when the
  payload has an active error channel) classifies into a Reader arm
  reading from `env.getLocalContext()`; sibling SDL fields read from
  `env.getSource()` via `$source` or name-matched lookup. The handler-
  resolution permits (`ErrorType.ExceptionHandler`,
  `SqlStateHandler`, `ValidationHandler`), the mappings-constant
  dedup (`MappingsConstantNameDedup`), and the wrapper-side dispatch
  through `ErrorRouter.dispatch` / `ErrorRouter.redact` all stay.
- **R96's `RecordBindingResolver`** gains one new producer arm. The
  walk today grounds at developer-authored sources reachable by
  reflection on a `java.lang.reflect.Method` (`@service` returns,
  `@table` resolutions, `@tableMethod` returns); the DML producer is
  generator-emitted - the row shape is determined by
  `TypeFetcherGenerator.buildMutationDml*Fetcher` from the input
  `@table` + `DmlKind` + cardinality. A new
  `ProducerBinding.DmlEmitted(TableRef, DmlKind, Cardinality)` permit
  carries this identity. To preserve the resolver's single-axis
  class-identity fold (RecordBindingResolver.java:368-385),
  `DmlEmitted.reflectedClass()` returns `TableRef.recordClass()` for
  its carried `TableRef` - the same class `RootTable` grounds with at
  RecordBindingResolver.java:156-159 - so the existing
  `record-binding.producer-agreement` check fires unchanged: a DML
  producer agrees with the input `@table`'s `RootTable` binding for
  the same record class. This is the structural replacement for the
  deleted `mutation-dml-record-field.data-table-equals-input-table`
  invariant; the guarantee re-emerges through the existing fold with
  no new load-bearing key and no carrier-walk-specific consumer. The
  classifier-side wiring that previously fired
  `requireDataTableMatchesInputTable` now lifts the DML mutation's
  input `@table` `TableRef` onto the emitted `ProducerBinding.DmlEmitted`,
  which the payload-side `deriveSplitQuerySource` caller reads as its
  `parentTable` argument (§"Bulk DML reference" #1). The lookup path:
  when `FieldBuilder.classifyField` constructs the SourceKey for a
  child `@table` field on a payload SDL type, it consults the parent's
  resolved `ProducerBinding` via `RecordBindingResolver`'s binding map,
  pattern-matches on `DmlEmitted`, and feeds `dmlEmitted.tableRef()`
  to `deriveSplitQuerySource(parentTable, List.of(), returnType)`.
- **R156's NodeId encoder chain** (`HelperRef.Encode`,
  `CallSiteCompaction.NodeIdEncodeKeys`) survives unchanged; under R178
  it is wired through `resolveRecordAccessor`'s outcome arms rather than
  through a dedicated `SingleRecordIdFieldFromReturning` carrier.

## Bulk DML reference (model alignment)

For `[Film!]` payload cardinality the producer (DML fetcher) leaves
`Result<RecordN<PK>>` in `env.getSource()` - same shape as today - and
the payload `films` field classifies through the existing
`@splitQuery`-shaped path:

1. `FieldBuilder.deriveSplitQuerySource` (FieldBuilder.java:3956)
   produces `SourceKey(target = Film table, columns = Film PK columns,
   wrap = Wrap.Row, reader = Reader.ColumnRead, cardinality = MANY)`
   paired with `LoaderRegistration(POSITIONAL_LIST, LOAD_ONE)`. The
   DataFetcher reads PK columns off each `RecordN<PK>` in
   `env.getSource()` and returns the DataLoader-batched future
   projected on the SelectionSet - the same shape a `@splitQuery` field
   on a `@table` parent uses.
2. graphql-java walks the returned `List<FilmRecord>` element-wise
   (spike V3; the unwrap is graphql-java's own contract, not
   graphitron's).
3. Per-Film child fields read directly off each projected `FilmRecord`
   via the standard `@table`-parent inline-subselect path, identical to
   a Query-rooted Film child.

The single-record case (`film: Film`) is the `Cardinality.ONE` arm of
the same derivation. The DML-emitted `RecordN<PK>` is admitted as a
new producer shape through the `ProducerBinding.DmlEmitted` permit
(§"What stays" #2); no SourceKey or Reader axis widens.

`graphitron-rewrite/graphitron/src/test/java/no/sikt/graphitron/rewrite/ListSourceBehaviorSpike.java`
pins the graphql-java behavior the encoding depends on (V3: a
DataFetcher returning a list-shaped source is walked element-wise; V5:
without an explicit DataFetcher on the list-typed field, the default
`PropertyDataFetcher` rejects the list source and the field renders
null). The spike stays in-tree as a behavioral contract pin under the
`UnitTier`.

## Test plan

- **Unit tier**: deletions only on the carrier-walk-specific tests
  (`CarrierFieldRoleCoverageTest`, `DataElementIdInvariantTest`,
  `PkResolutionEmitterReachabilityTest`, the carrier compact-ctor
  invariant cases in `SourceKeyTest`, the R161 `Ok.NoBacking` /
  `ClassBacked` fork cases) and on any remaining live reference to
  `SourceKey.Reader.ResultRowWalk` surfaced by `git grep` at deletion
  time (today: `UnifiedEmissionPinsTest`, `GeneratorUtils`, others).
  New unit cases pin `ProducerBinding.DmlEmitted`'s compact-constructor
  invariants (non-null `TableRef`, `reflectedClass()` returns
  `TableRef.recordClass()`).
- **Pipeline tier**:
  - Direct regression for the reported bug: assert that
    `SettKvotesporsmalAlgoritmePayload`-shaped schemas produce identical
    classification with and without the explicit
    `@field(name: "<sdlFieldName>")` directive. Two byte-identical
    `GraphitronSchema` snapshots, one fixture pair.
  - **Diagnostic-quality regression**: when the unified path rejects an
    `@service` mutation whose method's return type does not match the
    payload class, the diagnostic must cite the *SDL field name* and
    *the payload type's reflected class* - not the inner table's record
    class. The reported bug surfaced specifically because the carrier-
    walk-specific rejection ("must return `KvotesporsmalRecord` ... got
    `SettKvotesporsmalAlgoritmePayload`") cited the inner table's record
    instead of the payload's reflected class. Pin the new wording to
    prevent regression toward that failure mode.
  - Retarget `SingleRecordCarrierPipelineTest`,
    `SingleRecordTableFieldServiceProducerPipelineTest`,
    `FieldSourceSigilPipelineTest`, the
    `SINGLE_RECORD_CARRIER_DATA_FIELD_ORPHAN` /
    `SINGLE_RECORD_IDENTITY_FIELD` / `MUTATION_DML_RECORD_FIELD` /
    `MUTATION_BULK_DML_RECORD_FIELD` rows in
    `GraphitronSchemaBuilderTest`, the bulk-DML rows in
    `GraphitronSchemaBuilderTest`, and `MutationDeletePayloadCarrierCase`
    to assert the unified-path classification outcomes (most retarget to
    assertions on the standard `@record`-parent path; the
    producer-kind-conflict and `$source`-sigil cases delete outright;
    `SINGLE_RECORD_CARRIER_DATA_FIELD_ORPHAN`'s `variants()` returning
    `ChildField.SingleRecordTableField.class` retires with the permit
    deletion).
- **Compile tier**: keep all `graphitron-sakila-example` fixtures
  (`FilmsPayload`, `DeletedFilmsIdPayload`, `DeletedFilmsTablePayload`,
  `SingleFilmCardCarrier`, etc.) - the generated code must compile
  through the unified path. Compile-tier passing is the load-bearing
  guarantee that the producer/consumer wiring is correct.
- **Execution tier**: keep every existing carrier-walk execution test
  (`SingleRecordCarrierDmlTest`'s durability pins,
  `DmlBulkMutationsExecutionTest`'s order-preservation pins,
  `SingleRecordTableFieldServiceProducerExecutionTest`'s eight cases,
  R156's two DELETE-carrier end-to-end tests). Same SQL, same Java
  outputs; the runtime contract does not change. Test names migrate
  away from `Carrier*` naming as the model retires.
- **LSP tier**: the `$source` sigil itself survives R178 (see
  §"Concrete deletions" / §"Target model" #3). LSP cases that asserted
  carrier-walk-specific admission of `$source` (`DiagnosticsTest`'s
  R159 sigil cases at the `films: [Film!] @field(name: "$source")`
  shape, `FieldCompletionsTest`'s R159 sigil-completion cases) retarget
  to the unified-path admission predicate: the sigil is valid at any
  site where the per-field classifier would otherwise resolve a
  name-based accessor / column lookup against the parent's reflected
  shape. No new LSP cases.

## Migration / risk notes

- **Sequencing with `deprecate-record-directive`**: R178 reads R96's
  reflection-only binding as a hard prerequisite. The
  deprecate-record-directive item retires the `@record` directive from
  the SDL surface; R178 can land before or after that retirement
  (depending on Spec sequencing), but it must not re-introduce a
  directive read.
- **Sequencing with R128 (multi-field mutation carriers)**: R178
  subsumes R128's planned ErrorChannel-on-carrier work. Either R178
  lands first and R128 closes as a no-op (multi-field carriers become
  structurally trivial when the regime is gone), or R128 lands first
  inside the existing carrier-walk machinery and R178 retires both.
  Recommendation: R178 first, then close R128.
- **No external surface change**. Every SDL form the carrier walk
  admits today must classify under the unified path with structurally
  identical generated code. The execution-tier test surface is the
  load-bearing contract.
- **Diagnostic wording**: today's carrier-walk-specific rejections
  ("single-record carrier '<T>' declares ...", "carrier field '<f>'
  resolves to no CarrierFieldRole permit", etc.) are replaced by the
  standard `@record`-parent diagnostics. Authors get one consistent
  diagnostic family across the classifier.
- **Implementation slicing**. Phase 1 (additive `ProducerBinding.
  DmlEmitted` permit, observed by the R96 reflection walk, exposed
  through `TypeBuilder.dmlEmittedBinding`) and Phase 3A (additive
  `$errors` sigil + `selectErrorsTransport` rule-table helper, both
  unwired) shipped as separate commits with no behavior change. Phase
  3B is the atomic cutover required by the spec: every payload-
  returning mutation arm (DML payload, `@service` payload, DELETE-
  from-returning) switches from the carrier walk to the unified path
  in a single commit, with pipeline tests retargeted, the SettKvotes-
  porsmal regression pin added, and the diagnostic-wording pin added.
  Phase 4 deletions follow as their own commits and remove the now-
  unreachable carrier-walk types, methods, emitters, and load-bearing
  classifier checks listed under §"Concrete deletions".

## Out of scope

- The `RecordBindingResolver` walk itself - R178 adds one new producer
  caller but does not reshape the resolver's model or the post-fold
  agreement check.
- R156's NodeId encoder resolution (`resolveDeleteIdEncoder` and the
  `@nodeId(typeName:)` admission rules). The encoder chain stays; only
  the carrier-field wrapper around it dies.
- The two-step DML emit shape (`.returningResult(PK)` in transaction +
  projected SELECT outside) - the producer side stays the same; only
  the consumer-side classification path collapses.
- Wire-format / serialization for payload responses - unchanged.
- The `@table`-parent child-classification path (`Reader.ColumnRead`,
  `Reader.AccessorCall`, `Reader.SourceRowsCall`,
  `Reader.ServiceTableRecord`, `Reader.ServiceUntypedRecord`) - R178 is
  additive against this set and does not reshape existing arms.

## Principles alignment

- **Generation thinking**: the carrier walk is two producers of the
  same downstream value (a child field's SourceKey shape), reconciled
  by a registry. Collapsing to one producer is the canonical
  application of the principle.
- **Sealed hierarchies for resolution outcomes**: R178 deletes seven
  sealed hierarchies (`SingleRecordCarrierResolution`,
  `CarrierFieldRole`, `DataElement`, `DeleteTableProjection`,
  `PerFieldOutcome`, `PkResolution`, `Reader.ResultRowWalk` as a
  permit) without introducing new ones. The remaining classifier sealed
  trees describe orthogonal axes; the carrier-walk hierarchies were a
  parallel encoding of `Reader` permits.
- **No defensive casts in emitted code**: the `(Result<RecordN<PK>>) env.getSource()`
  / `(List<XRecord>) env.getSource()` casts in
  `buildSingleRecordTableFetcherValue` are replaced by the typed source
  reads the unified Reader arm produces; cast safety is pinned by
  SourceKey invariants the standard `@record`-parent path already
  enforces.
- **Reflection over directive read**: R96's directive-ignored stance
  generalizes; R178 removes the last classifier consumer of the
  `@record` directive's `className` argument (the directive-ignored
  warning continues to fire from `TypeBuilder.emitDirectiveIgnoredWarnings`
  unchanged until `deprecate-record-directive` lands).
- **One classification path per orthogonal axis**: today payload-
  returning mutations have two classification paths chosen by SDL-shape
  pattern-matching; R178 makes the path single-valued.

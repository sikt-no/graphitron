---
id: R178
title: Retire single-record carrier walk; collapse to SourceKey + R96 reflection
status: Spec
bucket: architecture
priority: 7
theme: mutations-errors
depends-on: []
created: 2026-05-18
last-updated: 2026-05-18
---

# Retire single-record carrier walk; collapse to SourceKey + R96 reflection

> The single-record carrier walk
> (`BuildContext.tryResolveSingleRecordCarrier`, `classifyCarrierField`,
> the `SingleRecordCarrierShape` / `CarrierFieldRole` / `DataElement` /
> `PerFieldOutcome` / `PkResolution` model trees, the four
> `register*CarrierDataField` helpers, the `MutationDmlRecordField` /
> `MutationServiceRecordField` / `SingleRecordTableField*` / `SingleRecordId*`
> permits, and the `Reader.ResultRowWalk` SourceKey arm) is a parallel
> classification regime that duplicates what `SourceKey` +
> `Reader.AccessorCall` + R96's `RecordBindingResolver` already do. Every
> piece of work it accomplishes - PK-keyed projected SELECT from a
> producer's source row, parent-record-keyed accessor reads, target-table
> binding, payload-shape classification - is expressible through the
> standard SourceKey + reflection machinery used everywhere else in the
> classifier. R178 deletes the parallel hierarchy and routes every
> payload-returning mutation (`@service` and `@mutation`-DML, single and
> bulk) through the standard `@record`-parent + SourceKey path.

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

## Why the carrier walk duplicates SourceKey + R96

`FetcherEmitter.buildSingleRecordTableFetcherValueRecordWrap`
(FetcherEmitter.java:330-446) does exactly what every child-on-`@record`-
parent fetcher does: pull a key off `env.getSource()`, run a projected
SELECT against the inner table filtered by that key, with column
selection driven by `env.getSelectionSet()`. The novelty is solely
"where does the key come from" - the DML's `RETURNING` row instead of a
catalog-FK column or a typed accessor on a `@record` parent's backing
class. That distinction is a Reader arm (`SourceKey.Reader`), not a
classification hierarchy.

Walking each piece of carrier machinery to confirm nothing survives
independently:

- **`CarrierFieldRole.DataChannel` / `ErrorChannelRole`** - the same
  question every `@record`-parent child field asks. `FieldBuilder.resolveRecordAccessor`
  (FieldBuilder.java:3816) already classifies "this child reads via
  accessor X, returning typed Y."
- **`DataElement.Table` / `Record` / `Id`** - all three reduce to
  Reader-arm choices in SourceKey. `Table` is "PK columns off the source
  row, projected SELECT" (today `Reader.ResultRowWalk` + `Wrap.Record`);
  `Record` is identity passthrough, expressible as `Reader.AccessorCall`
  against the payload's record component or simply returning
  `env.getSource()`; `Id` is encoded-NodeId via an accessor returning a
  PK column, with the existing `HelperRef.Encode` chain.
- **`SingleRecordCarrierShape` invariants** (exactly one DataChannel,
  at-most-one ErrorChannel) - structurally redundant under the
  multi-field-mutation-carrier direction (R128); each SDL field on the
  payload class becomes its own SourceKey-classified field.
- **`classifyDeleteTableProjection` / `PerFieldOutcome`** - "which payload
  fields map to which PK columns of a DELETE's RETURNING." The same
  question `resolveRecordAccessor` asks, with one extension: an
  accessor-resolution outcome that returns "this accessor names a PK
  column" rather than a record. Folds in.
- **`carrierProducerRegistry` + `single-producer-kind` compare-then-write**
  - exists only because the carrier walk has two encodings
  (`Wrap.Record` for DML, `Wrap.TableRecord` for `@service`) for the
  same coord. Without the parallel encoding the conflict can't arise.
- **Four `register*CarrierDataField` helpers + the carrier-walk arms in
  the schema-builder loop** (`GraphitronSchemaBuilder.buildSchema`
  GraphitronSchemaBuilder.java:217-256) - collapse to the single
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
   describing the read. Name-based lookup; `@field(name:)` overrides the
   lookup name. No DTOs are generated.
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
- `MutationField.MutationDmlRecordField`
- `MutationField.MutationBulkDmlRecordField`
- `MutationField.MutationServiceRecordField`
- `SourceKey.Reader.ResultRowWalk`

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
- `TypeFetcherGenerator.buildMutationDmlRecordFetcher`
- `TypeFetcherGenerator.buildMutationBulkDmlRecordFetcher`

`@LoadBearingClassifierCheck` keys:
- `single-record-carrier-shape.roles-exhaustively-classified`
- `mutation-dml-record-field.data-table-equals-input-table`
- `mutation-delete-carrier.pk-resolution-projection-clean`
- `carrier-data-field.single-producer-kind`
- `carrier-data-field.service-producer-strict-return`
- `source-key.result-row-walk-target-aligned-empty-path`

R159's `$source` sigil retires alongside (the sigil exists only to
confirm the carrier-walk's implicit binding; under R178 the binding is
named-by-default through `@field(name:)`).

## What stays / consolidates

- **The "PK columns off the source row, projected SELECT keyed by those
  PKs" fetcher logic** is the only emit work the carrier walk did that
  isn't already present elsewhere. Under R178 it lands as a single
  Reader arm consumed uniformly by both `@table`-parent children
  (existing producers) and payload-returning mutations (new producer).
  The Spec implementation picks one of two encodings; both are
  semantically equivalent and the choice is a model-cleanliness
  judgement, not a correctness one:
  - **Option A**: generalize `Reader.ColumnRead` so its scope widens
    from "catalog-FK column on a `@table`-backed parent" to "read these
    columns off the parent row, whatever the parent is." The DML payload
    case becomes a `ColumnRead` against the producer-emitted `RecordN<...>`.
  - **Option B**: add one new Reader arm `Reader.UpstreamSourceColumns(columns)`
    whose contract is "read named columns off `env.getSource()`,
    package as the loader-key shape." Compact-constructor invariant
    similar in size to today's `ResultRowWalk`.
  Spec phase picks. Recommendation: Option A. The narrower "FK column"
  invariant of `ColumnRead` is already a vestige (FKs are one concrete
  case of "named columns on the parent row"); widening makes both
  consumers structurally identical.
- **R96's `RecordBindingResolver`** gains one new producer: DML
  mutation fetcher reflection. The walk already accepts producer
  bindings from `@service` methods, `@table` resolutions, and
  `@tableMethod` returns; DML fetchers join that list. No model changes
  in `RecordBindingResolver`; one new caller.
- **R156's NodeId encoder chain** (`HelperRef.Encode`,
  `CallSiteCompaction.NodeIdEncodeKeys`) survives unchanged; under R178
  it is wired through `resolveRecordAccessor`'s outcome arms rather than
  through a dedicated `SingleRecordIdFieldFromReturning` carrier.

## Bulk DML reference (model alignment)

For `[Film!]` payload cardinality: today `env.getSource()` at the
payload-level fetcher is `Result<RecordN<PK>>` and the `films` data
field's fetcher walks it. Under R178 the producer (DML fetcher) leaves
exactly the same `Result<RecordN<PK>>` in source, the `films` SDL
field's SourceKey describes "read PK columns off the rows, projected
SELECT for Film," and the `Film` child fields then read off the
projected `FilmRecord` instances via the standard `@table`-parent path.
No generated payload class; no runtime behavior change; one
classification path instead of two.

## Test plan

- **Unit tier**: deletions only on the carrier-walk-specific tests
  (`CarrierFieldRoleCoverageTest`, `DataElementIdInvariantTest`,
  `PkResolutionEmitterReachabilityTest`, the carrier compact-ctor
  invariant cases in `SourceKeyTest`, the R161 `Ok.NoBacking`/`ClassBacked`
  fork cases). New unit cases pin the chosen Reader-arm option's
  compact-constructor invariants (mirrors today's `ResultRowWalk`
  invariant coverage in shape, smaller in surface).
- **Pipeline tier**:
  - Direct regression for the reported bug: assert that
    `SettKvotesporsmalAlgoritmePayload`-shaped schemas produce identical
    classification with and without the explicit
    `@field(name: "<sdlFieldName>")` directive. Two byte-identical
    `GraphitronSchema` snapshots, one fixture pair.
  - Retarget `SingleRecordCarrierPipelineTest`,
    `SingleRecordTableFieldServiceProducerPipelineTest`,
    `FieldSourceSigilPipelineTest`, the
    `SINGLE_RECORD_CARRIER_DATA_FIELD` / `SINGLE_RECORD_IDENTITY_FIELD` /
    `MUTATION_DML_RECORD_FIELD` rows in `GraphitronSchemaBuilderTest`,
    the bulk-DML rows in `GraphitronSchemaBuilderTest`, and
    `MutationDeletePayloadCarrierCase` to assert the unified-path
    classification outcomes (most retarget to assertions on the
    standard `@record`-parent path; the producer-kind-conflict and
    `$source`-sigil cases delete outright).
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
- **LSP tier**: `FieldSourceSigil`-related `Diagnostics` and
  `FieldCompletions` cases retire with the sigil; nothing new.

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
  identical generated code (modulo whatever Reader-arm encoding choice
  Spec picks). The execution-tier test surface is the load-bearing
  contract.
- **Diagnostic wording**: today's carrier-walk-specific rejections
  ("single-record carrier '<T>' declares ...", "carrier field '<f>'
  resolves to no CarrierFieldRole permit", etc.) are replaced by the
  standard `@record`-parent diagnostics. Authors get one consistent
  diagnostic family across the classifier.

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
  `Reader.ServiceTableRecord`, `Reader.ServiceUntypedRecord`) - any
  Reader-arm choice in #1 of "What stays" is additive against this set
  and does not reshape existing arms.

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

---
id: R158
title: "SourceKey.Reader sub-taxonomy: admit @service-backed producers for carrier data fields"
status: Backlog
bucket: structural
priority: 3
theme: mutations-errors
depends-on: []
created: 2026-05-13
last-updated: 2026-05-13
---

# SourceKey.Reader sub-taxonomy: admit @service-backed producers for carrier data fields

## Problem

`FetcherEmitter.buildSingleRecordTableFetcherValue` (`graphitron-rewrite/graphitron/src/main/java/no/sikt/graphitron/rewrite/generators/FetcherEmitter.java:240-389`) pins its upstream producer to `Result<RecordN<PK>>` (MANY) or `RecordN<PK>` (ONE) via the cast at line 269 / 272 and reads `source.getValues(<PK column>)` / `source.value1()` directly off the resulting jOOQ shape. That contract is satisfied exactly when the producer is a DML mutation whose two-step fetcher emits a `.returningResult(PK)` projection (`MutationDmlRecordField` / `MutationBulkDmlRecordField`); no other producer carries that row shape.

A `@service` mutation feeding a payload-shaped carrier whose data field is a `@table`-element (the carrier walk admitted via R159's `$source` sigil at `FieldBuilder.java:2752-2762`) returns the developer-declared `List<XRecord>` (or `XRecord` in the ONE case) verbatim. At runtime the FetcherEmitter cast then throws `ArrayList cannot be cast to org.jooq.Result`. Reproducer: `OpprettRegelverksamlingPayload` + `opprettRegelverksamling` `@service` returning `List<RegelverksamlingRecord>`.

The producer-kind axis is a classifier fact, not a runtime tolerance. The fix is to make that axis explicit in the model.

## Where the producer-kind decision lands

The spec-direction draft of this item placed the dispatch in `GraphitronSchemaBuilder.registerCarrierDataField`. That site (`GraphitronSchemaBuilder.java:279-336`) runs from the schema-walk loop, before `FieldBuilder` classifies any mutation field. At that point a carrier type may be returned by an unknown set of mutations of unknown kind; the producer-kind axis is not yet in scope.

The decision lands at the per-mutation classification site in `FieldBuilder.classifyMutationField` (`FieldBuilder.java:3158-3198`), which already has the producing mutation's `DmlKind` (or the `@service` reflection result) in scope and already uses the carrier resolution. R156 has established the pattern: for DELETE carriers, the verbless walk's `SingleRecordTableField` registration is overwritten by `FieldBuilder`'s `registerDeleteCarrierDataField` (`FieldBuilder.java:2823-2900`), which writes the verb-specific permit (`SingleRecordTableFieldFromReturning`) under the same coords. R158 extends that pattern to the reader-variant axis.

The verbless walk stops pre-binding `Reader.ResultRowWalk` for `DataElement.Table` carriers. `GraphitronSchemaBuilder.registerCarrierDataField`'s `DataChannel.element` switch becomes:

- `DataElement.Table` — verbless walk does NOT register a `ChildField` for this coord. The data field stays unregistered until `FieldBuilder` lands the producer-specific registration.
- `DataElement.Record` — verbless walk continues to register `SingleRecordIdentityField` (no reader-variant axis applies; the data field's value IS the parent's value).
- `DataElement.Id` — verbless walk continues to be a no-op (R156 already lands this from `FieldBuilder`).

This removes the verbless walk's pretence of knowing the producer for `DataElement.Table` and consolidates registration at the site where producer-kind evidence exists. `SingleRecordTableField`'s permit shape and compact-constructor invariant are unchanged.

## Mechanism

**`SourceKey.Reader.ResultRowWalk` splits into a sealed sub-taxonomy.** The current permit becomes a sealed interface with two variants:

- `Reader.ResultRowWalk.Dml` — upstream `Result<RecordN<PK>>` / `RecordN<PK>` from a DML mutation's `.returningResult(PK)` chain. Pairs with `Wrap.Record`. Compact-constructor invariant: `Wrap.Record` + empty path.
- `Reader.ResultRowWalk.Service(ClassName recordClass)` — upstream `List<XRecord>` / `XRecord` from the developer's reflected `@service` method return. `recordClass` is the data table's typed jOOQ `TableRecord` subclass (e.g. `RegelverksamlingRecord`). Pairs with `Wrap.TableRecord(recordClass)`. Compact-constructor invariant: `Wrap.TableRecord` whose `className` equals `recordClass` + empty path + `recordClass` equals `target.recordClass()` (the carrier's data table's record class — guaranteed equal by the existing data-table-equals-input-table check chain).

`ChildField.SingleRecordTableField`'s compact-constructor requirement (`ChildField.java:61-67`) widens from `Reader.ResultRowWalk` to the sealed parent; both sub-permits satisfy the existing rule.

**`FetcherEmitter`'s MANY and ONE arms switch on the sub-permit.** Each arm types its cast exactly to what its variant guarantees:

- *DML / MANY* (unchanged from today): cast `(Result<RecordN<...>>) env.getSource()`; `source.isEmpty()` short-circuit; PK-keyed map; iterate `source.getValues(<PK column>)` (typed value list) to reorder. R141's order-preservation is unchanged.
- *DML / ONE* (unchanged): cast `(RecordN<...>) env.getSource()`; `source == null` short-circuit; `source.value1()` for single-PK, `source.get(<PK column>)` for composite.
- *@service / MANY* (new): cast `(List<XRecord>) env.getSource()`; `source.isEmpty()` short-circuit; PK-keyed map; iterate `source.stream().map(r -> r.get(<PK column>)).toList()` to reorder. Same Java-side order-preservation shape as R141, just typed against `XRecord` instead of `RecordN`.
- *@service / ONE* (new): cast `(XRecord) env.getSource()`; `source == null` short-circuit; `source.get(<PK column>)` for every PK column.

The `@DependsOnClassifierCheck` annotation on `buildSingleRecordTableFetcherValue` (`FetcherEmitter.java:248-253`) splits per arm: the DML arm names the DML load-bearing key, the @service arm names the @service load-bearing key.

**Producer-side registration.** `FieldBuilder.classifyMutationField` learns one new branch and one new responsibility:

- New helper `registerDmlCarrierDataField(BuildContext ctx, SingleRecordCarrierShape shape, TableRef inputTable, String mutationName)` mirroring `registerDeleteCarrierDataField`. For non-DELETE DML kinds (INSERT, UPDATE, UPSERT) with a `DataElement.Table` data channel, registers `SingleRecordTableField` with `SourceKey(target, pkColumns, [], Wrap.Record, cardinality, ResultRowWalk.Dml)`. Called immediately after the existing `requireDataTableMatchesInputTable` check at `FieldBuilder.java:3179` for `kind != DELETE`.
- `@service` mutation classification for `MutationServiceTableField` (the only @service permit that can return a carrier — `MutationServiceRecordField` returns the domain record directly and has no carrier walk) gains a parallel: after the `@service` reflection result lands, if the return type resolves to a single-record carrier whose data field is `DataElement.Table` AND has the `$source` sigil set (R159 admission), register `SingleRecordTableField` with `SourceKey(target, pkColumns, [], Wrap.TableRecord(recordClass), cardinality, ResultRowWalk.Service(recordClass))`. The `recordClass` is the data table's record class (`target.recordClass()`); the reflected method return type is already constrained to match by the admission predicate below.

## Admission predicate for the `@service` producer

`ServiceCatalog.reflectServiceMethod` (`ServiceCatalog.java:158-224`) already enforces strict `TypeName.equals` against an `expectedReturnType` parameter (load-bearing key `service-catalog-strict-service-return`). The caller in `FieldBuilder` for a carrier-returning `@service` field computes:

- `Cardinality.ONE` (carrier's data field wrapper is non-list): `expectedReturnType = ClassName.get(recordClass)`.
- `Cardinality.MANY` (data field wrapper is list): `expectedReturnType = ParameterizedTypeName.get(ClassName.get(List.class), ClassName.get(recordClass))`.

The strict predicate then rejects `Set<XRecord>`, `Collection<XRecord>`, `Iterable<XRecord>`, `List<? extends XRecord>`, raw `List`, and any list element other than the exact `XRecord` class. The rejection message gets one targeted refinement: when the reflected return is a `Set` / `Collection` / `Iterable` whose element type equals `recordClass`, surface "use `List<XRecord>` to preserve iteration order against the carrier's data field" rather than the generic `TypeName.equals` mismatch text. The order-preservation requirement is structural (R141's contract that `output.data[i] = input[i]`), so the message is contractual, not stylistic.

No freestanding denial rule on the carrier walk. No widening of `Reader.ResultRowWalk.Service`'s admission predicate beyond what the strict return-type check already enforces.

## Load-bearing keys

The current key `source-key.result-row-walk-wrap-record-empty-path` (`SourceKey.java:95-102`) is the DML arm's invariant; it renames to `source-key.result-row-walk-dml-wrap-record-empty-path` and pins:

> `Reader.ResultRowWalk.Dml` paired with anything other than `Wrap.Record` or with a non-empty path. The upstream DML mutation fetcher emits `RecordN<...>` rows (cardinality ONE → single `RecordN`, cardinality MANY → `Result<RecordN>`); the data-field fetcher's typed source read via `SourceKey.wrap × columns` relies on `wrap = Record` to type the source value, and `path = empty` pins the source row shape to the DML's input table.

A new sibling key `source-key.result-row-walk-service-wrap-tablerecord-empty-path` pins:

> `Reader.ResultRowWalk.Service(recordClass)` paired with anything other than `Wrap.TableRecord(recordClass)` or with a non-empty path or with `recordClass != target.recordClass()`. The upstream `@service` method returns the typed `XRecord` (cardinality ONE) or `List<XRecord>` (cardinality MANY); the data-field fetcher's typed source read relies on `Wrap.TableRecord` carrying the same `XRecord` class the reader names, and `path = empty` pins the source to the data table.

`FetcherEmitter`'s split `@DependsOnClassifierCheck` annotations name these keys per arm.

## Test surface

Pipeline-tier (`PipelineTier`) cases on a new `SingleRecordTableFieldServiceProducerPipelineTest`:

- *ONE / single-PK admission* — `@service` returning `XRecord` for a carrier whose data field has non-list wrapper. Assert the registered `SourceKey` carries `Reader.ResultRowWalk.Service`, `Wrap.TableRecord(recordClass)`, `Cardinality.ONE`.
- *MANY / single-PK admission* — `@service` returning `List<XRecord>`. Assert MANY cardinality and emitted fetcher contains `((List<XRecord>) env.getSource())` and the `r.get(<PK>)` reorder pattern.
- *MANY / composite-PK admission* — same with two-PK table; assert `row(pk1, pk2)` shape and `r.get(<pk1>), r.get(<pk2>)` keying.
- *Set return reject* — `@service` returning `Set<XRecord>`; assert classification rejects with the order-preservation message and the message names `List<XRecord>` as the fix.
- *Iterable return reject* — `@service` returning `Iterable<XRecord>`; assert structural rejection via the strict predicate (covers the bare-`Iterable` case).
- *Wrong element type reject* — `@service` returning `List<OtherRecord>` for a carrier whose data table is `Regelverksamling`; assert rejection cites the expected `List<RegelverksamlingRecord>`.

Execution-tier (`ExecutionTier`) cases on a new `SingleRecordTableFieldServiceProducerExecutionTest` (mirrors `DmlBulkMutationsExecutionTest`):

- *MANY-arm end-to-end* — `@service` mutation returns `List<RegelverksamlingRecord>`; assert the data-field fetcher reads PKs off the record list, runs the follow-up SELECT, and projects rows in input order.
- *ONE-arm end-to-end* — `@service` mutation returns a single `RegelverksamlingRecord`; assert the follow-up SELECT runs and returns the expected row.
- *Empty source short-circuit* — `@service` returns empty `List<XRecord>`; assert no SELECT runs and the data field returns an empty list.

Unit-tier (`UnitTier`) cases on `SourceKeyCompactConstructorTest`:

- New load-bearing-key cases for both renamed and new keys; structural-rejection cases for `Wrap.TableRecord(other)` paired with `ResultRowWalk.Service(recordClass)` and for non-empty path on either sub-permit.

Coverage-tier — `CarrierFieldRoleCoverageTest` already enforces that every sealed permit has a consumer. The new test ensures every `ResultRowWalk` sub-permit is dispatched by `FetcherEmitter`'s MANY and ONE arms; a future sub-permit added without an arm fails the test.

## Out of scope

- **R141 PK-keyed-map → VALUES-idx-JOIN migration.** R141 is shipped working code with its own audit surface (`DmlBulkMutationsExecutionTest`); migrating its Java-side order preservation to a SQL-side `ORDER BY input.idx` against a derived VALUES table (the `@lookupKey` idiom in `InlineLookupTableFieldEmitter` / `LookupValuesJoinEmitter`) is a refactor of working code, not part of admitting the `@service` producer. A follow-up Backlog item names R158 as prereq and lifts both `MutationDmlRecordField` and `MutationBulkDmlRecordField` (and the new `@service` arm) in one move, against its own pipeline + execution test surface.
- **`Reader.ResultRowWalk.Service` consumed outside `SingleRecordTableField`.** The new sub-permit pairs only with `SingleRecordTableField` today; other consumers of `Reader.ResultRowWalk` (if any future ones land) will need their own dispatch arms. The sealed shape ensures the compiler surfaces missing arms at the next consumer-site change.
- **`@service` producer with `DataElement.Record` data field.** R96 alignment: producer-kind discrimination flows through `@service` method introspection captured by `ServiceCatalog.reflectServiceMethod`; no `@record` on the payload is needed or admitted. `SingleRecordIdentityField` remains the `DataElement.Record` permit; its fetcher is identity passthrough regardless of producer kind, so the sub-taxonomy does not apply.

## Migration notes

- Existing call sites of `Reader.ResultRowWalk` (today only `SourceKey`'s compact constructor, `ChildField.SingleRecordTableField`'s constructor check, and `GraphitronSchemaBuilder.registerCarrierDataField`) all become references to the sealed parent or one specific sub-permit. The sealed-parent references stay; the construction sites in `GraphitronSchemaBuilder` are removed (the verbless walk no longer constructs the reader for `DataElement.Table`).
- `FieldBuilder`'s new `registerDmlCarrierDataField` constructs `ResultRowWalk.Dml` for the four non-DELETE DML kinds (INSERT, UPDATE, UPSERT) of `MutationDmlRecordField` / `MutationBulkDmlRecordField`. The DELETE path keeps its existing `registerDeleteCarrierDataField` (no change; DELETE's permit is `SingleRecordTableFieldFromReturning`, not `SingleRecordTableField`).
- The renamed load-bearing key `source-key.result-row-walk-wrap-record-empty-path` → `source-key.result-row-walk-dml-wrap-record-empty-path` ripples to one `@LoadBearingClassifierCheck` annotation in `SourceKey.java` and one `@DependsOnClassifierCheck` annotation in `FetcherEmitter.java`. Both update in the same commit that introduces the sub-taxonomy.

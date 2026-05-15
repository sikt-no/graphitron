---
id: R158
title: Admit @service-backed producers for single-record DML carrier data fields
status: In Review
bucket: structural
priority: 3
theme: mutations-errors
depends-on: []
created: 2026-05-13
last-updated: 2026-05-15
---

# Admit `@service`-backed producers for single-record DML carrier data fields

## Problem

`FetcherEmitter.buildSingleRecordTableFetcherValue` reads the upstream value as `Result<RecordN<PK>>` (MANY) or `RecordN<PK>` (ONE) and pulls PK values through `source.getValues(<PK column>)` / `source.value1()`. That cast is satisfied exactly when the producer is a DML mutation whose two-step fetcher emits a `.returningResult(PK)` projection. A `@service` mutation feeding the same payload-shape carrier (admitted by R159's `$source` sigil) returns the developer's `List<XRecord>` (or `XRecord`) verbatim, and the cast throws `ArrayList cannot be cast to org.jooq.Result` at runtime. Reproducer: `OpprettRegelverksamlingPayload` + `opprettRegelverksamling` `@service` returning `List<RegelverksamlingRecord>`.

The producer-kind axis is a classifier fact, not a runtime tolerance. It lives in the model already, on the `Wrap` permit: `Wrap.Record` carries the DML's untyped `RecordN<...>` row shape; `Wrap.TableRecord(className)` carries the `@service` producer's typed `XRecord` row shape. R158 wires that existing discrimination through the carrier data field's classifier and emitter so the cast is typed correctly per producer.

Builds on R159, which admitted the `@service` producer's return as the carrier data field's source via the `$source` sigil at `@field(name:)`.

## Design — shipped at `f35644f`

`SourceKey.Reader.ResultRowWalk` widens to admit both `Wrap.Record` and `Wrap.TableRecord(target.recordClass())`; the load-bearing key renames to `source-key.result-row-walk-target-aligned-empty-path`. `FetcherEmitter.buildSingleRecordTableFetcherValue` factors into a wrap-permit switch (the existing `Wrap.Record` arms preserved unchanged; new `Wrap.TableRecord` arms emit typed `(List<XRecord>) env.getSource()` / `(XRecord) env.getSource()` casts and read PKs via the typed `record.get(<XTable.<PK_FIELD>>)` accessors, with `List.of(r.get(pk1), r.get(pk2), ...)` for composite-PK map keying). Registration of the carrier data field moves from the verbless walk in `GraphitronSchemaBuilder.registerCarrierDataField` to two per-producer helpers in `FieldBuilder` (`registerDmlCarrierDataField`, `registerServiceCarrierDataField`); the `DataElement.Table` arm of `registerCarrierDataField` becomes a no-op. The `@service`-side helper does its own strict `method.returnType().equals(expectedReturnType)` check against the carrier walk's `target.recordClass()` (pinned by the new load-bearing key `carrier-data-field.service-producer-strict-return`). Carrier types are producer-kind-monomorphic, enforced at write time via `ctx.carrierProducerRegistry` and a compare-then-write check; mismatched second registration lands as `UnclassifiedField` + `Rejection.structural` (pinned by `carrier-data-field.single-producer-kind`). `FieldRegistry.reclassify`'s `expectedExistingClass` parameter loosens to admit `null`; R156's `registerDeleteCarrierDataField` Table arm passes `null` in lockstep because the verbless walk no longer pre-registers.

## Test surface — shipped at `da25606` (pipeline + unit) and `129909c` + `34accf5` (execution)

Pipeline-tier cases on `SingleRecordTableFieldServiceProducerPipelineTest`: ONE/single-PK admission, MANY/single-PK admission, MANY/composite-PK admission, wrong-element-type reject, Set/Iterable return reject, mixed-producer carrier reject (both registration orders). Execution-tier cases on `SingleRecordTableFieldServiceProducerExecutionTest`: MANY-arm end-to-end (single-PK; `129909c`), MANY-arm composite-PK end-to-end (`34accf5`, the typed `List.of(r.get(pk1), r.get(pk2))` map-key shape and `row(pk1, pk2).in(...)` predicate emission), ONE-arm end-to-end, empty source short-circuit. Unit-tier cases on `SourceKeyTest`: loosened `ResultRowWalk` admission (positive `Wrap.TableRecord(target.recordClass())` + empty path; cross-table mismatch reject; non-empty path reject).

## Migration notes — shipped at `f35644f`

`Reader.ResultRowWalk`'s compact-constructor invariant loosened; the renamed load-bearing key updated one `@LoadBearingClassifierCheck` on `SourceKey` and one `@DependsOnClassifierCheck` on `FetcherEmitter`, plus two javadoc references (`ChildField.java`, `SourceKey.java`). `GraphitronSchemaBuilder.registerCarrierDataField`'s `DataElement.Table` arm hollowed out. `FieldBuilder.classifyMutationField` gained the call to `registerDmlCarrierDataField` for non-DELETE DML kinds and the call to `registerServiceCarrierDataField` in the `Resolved.Result` arm of `@service` resolution. `FieldRegistry.reclassify`'s `expectedExistingClass` admits `null`; R156's `registerDeleteCarrierDataField` Table arm passes `null` in the same commit.

## Out of scope

- **R141 PK-keyed-map → VALUES-idx-JOIN migration.** R141 is shipped working code with its own audit surface; migrating its Java-side order preservation to a SQL-side `ORDER BY input.idx` against a derived VALUES table is a refactor of working code, not part of admitting the `@service` producer.
- **`Reader.ResultRowWalk` consumed outside `SingleRecordTableField`.** The widened invariant pairs only with the carrier data field's permit today; any future consumer must adopt the same wrap-dispatch pattern or split its own permit.
- **`@service` producer with `DataElement.Record` data field.** Producer-kind discrimination is irrelevant for the identity-passthrough permit (`SingleRecordIdentityField`): the data field's value IS the parent's value regardless of producer. R96 alignment holds — no `@record` on the carrier is needed.

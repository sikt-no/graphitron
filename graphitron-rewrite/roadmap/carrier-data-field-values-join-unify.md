---
id: R158
title: Admit @service-backed producers for single-record DML carrier data fields
status: Spec
bucket: structural
priority: 3
theme: mutations-errors
depends-on: []
created: 2026-05-13
last-updated: 2026-05-15
---

# Admit `@service`-backed producers for single-record DML carrier data fields

## Problem

`FetcherEmitter.buildSingleRecordTableFetcherValue` (`graphitron-rewrite/graphitron/src/main/java/no/sikt/graphitron/rewrite/generators/FetcherEmitter.java:240-389`) reads the upstream value as `Result<RecordN<PK>>` (MANY) or `RecordN<PK>` (ONE) and pulls PK values through `source.getValues(<PK column>)` / `source.value1()`. That cast is satisfied exactly when the producer is a DML mutation whose two-step fetcher emits a `.returningResult(PK)` projection. A `@service` mutation feeding the same payload-shape carrier (admitted by R159's `$source` sigil) returns the developer's `List<XRecord>` (or `XRecord`) verbatim, and the cast throws `ArrayList cannot be cast to org.jooq.Result` at runtime. Reproducer: `OpprettRegelverksamlingPayload` + `opprettRegelverksamling` `@service` returning `List<RegelverksamlingRecord>`.

The producer-kind axis is a classifier fact, not a runtime tolerance. It lives in the model already, on the `Wrap` permit: `Wrap.Record` carries the DML's untyped `RecordN<...>` row shape; `Wrap.TableRecord(className)` carries the `@service` producer's typed `XRecord` row shape. R158 wires that existing discrimination through the carrier data field's classifier and emitter so the cast is typed correctly per producer.

Builds on R159, which admitted the `@service` producer's return as the carrier data field's source via the `$source` sigil at `@field(name:)`. R159 is shipped; this item is no longer blocked.

## Design

**`SourceKey.Reader.ResultRowWalk` widens to admit both wrap shapes.** The current compact-constructor invariant in `SourceKey.java:169-182` pins `ResultRowWalk` to `Wrap.Record` + empty path; it loosens to admit `Wrap.TableRecord(recordClass)` as well, on the condition that `recordClass.equals(target.recordClass())`. The load-bearing key renames from `source-key.result-row-walk-wrap-record-empty-path` to `source-key.result-row-walk-target-aligned-empty-path` and pins:

> `Reader.ResultRowWalk` paired with anything other than `Wrap.Record` or `Wrap.TableRecord(target.recordClass())`, or with a non-empty path. The upstream producer (DML mutation fetcher or carrier-shaped `@service` method) emits target-aligned rows; the data-field fetcher's typed source read relies on `wrap` to type the row shape and on `path = empty` to pin the source row to the data table. The `recordClass == target.recordClass()` arm of the `Wrap.TableRecord` case mirrors the existing `source-key.service-table-record-target-aligned-empty-path` invariant for the same reason: the typed `XRecord` class names the same table the carrier is target-aligned to.

The single `@DependsOnClassifierCheck` annotation on `FetcherEmitter`'s `buildSingleRecordTableFetcherValue` names the renamed key once. The annotation's `reliesOn` text spells out that the typed-`XRecord` cast in the `Wrap.TableRecord` arm is licensed by *this* invariant — `Reader.ResultRowWalk` + target-alignment — not by `Wrap.TableRecord` in general. Other `Wrap.TableRecord` consumers (e.g. `GeneratorUtils.buildKeyExtraction`'s `((Record) env.getSource()).into(Tables.X)` posture) operate under different Reader invariants and read the source through the erased `Record` shape; the typed cast posture is carrier-specific.

**`FetcherEmitter`'s MANY and ONE arms dispatch on `Wrap`.** Each arm types its cast and PK-extraction shape to the wrap permit:

- *`Wrap.Record` / MANY* (unchanged from today): cast `(Result<RecordN<...>>) env.getSource()`; `source.isEmpty()` short-circuit; PK-keyed map; `source.getValues(<PK column>)` to walk input order. R141's order-preservation is bit-identical.
- *`Wrap.Record` / ONE* (unchanged): cast `(RecordN<...>) env.getSource()`; `source == null` short-circuit; `source.value1()` for single-PK, `source.get(<PK column>)` for composite.
- *`Wrap.TableRecord(XRecord)` / MANY* (new): cast `(List<XRecord>) env.getSource()`; `source.isEmpty()` short-circuit; PK-keyed map; iterate `source` and read PK columns via the typed `record.get(<XTable.<PK_FIELD>>)` accessors. The `<XTable.<PK_FIELD>>` reference resolves through `SourceKey.target.constantsClass()` + `ColumnRef.javaName()`, the same path the `Wrap.Record` arm uses today (`FetcherEmitter.java:294-296`); the typed `XRecord.get(TableField<XRecord, T>)` overload returns the typed PK value directly. Composite-PK keying uses `List.of(r.get(pk1), r.get(pk2), …)` paralleling the `Wrap.Record` MANY arm's map-key shape. Same input-order projection shape, typed against `XRecord` instead of `RecordN`.
- *`Wrap.TableRecord(XRecord)` / ONE* (new): cast `(XRecord) env.getSource()`; `source == null` short-circuit; `record.get(<XTable.<PK_FIELD>>)` for every PK column (same reference path as the MANY arm).

The cardinality switch sits inside each wrap arm; the dispatch is two wrap-permit arms × cardinality, matching the existing `(Reader, cardinality)` 2×N pattern in the codebase.

**Registration of the carrier data field moves to the per-producer site.** Today the verbless walk in `GraphitronSchemaBuilder.registerCarrierDataField` (`GraphitronSchemaBuilder.java:279-336`) registers `ChildField.SingleRecordTableField` with `Wrap.Record` + `Reader.ResultRowWalk` for every `DataElement.Table` carrier, independent of the producing mutation's kind. That switch arm becomes a no-op for `DataElement.Table`; registration lands at the per-mutation classification site in `FieldBuilder`, where the producer (DML kind or `@service` reflection result) is in scope. The other two switch arms are unchanged: `DataElement.Record` continues to register `SingleRecordIdentityField` (the identity-passthrough permit has no `SourceKey` at all — its fetcher is `env -> env.getSource()` — so the `Wrap`-axis producer-kind discrimination doesn't apply and the walk-time registration is structurally complete), and `DataElement.Id` continues to be a no-op (R156 lands the per-field permit from `FieldBuilder`).

A fuller cleanup that hollows out `registerCarrierDataField` entirely — moving `DataElement.Record` registration into `FieldBuilder` as well — is a follow-up Backlog item, not part of R158. The asymmetry today is principled (registration runs where the producer-kind discrimination lives, and `Record` has none); the spec note above pins the reason so a next reader doesn't read it as drift.

`FieldBuilder` lands two registration helpers, both following R156's `registerDeleteCarrierDataField` pattern (`FieldBuilder.java:2823-2900`):

- `registerDmlCarrierDataField(BuildContext ctx, SingleRecordCarrierShape shape, TableRef inputTable, String mutationName)` runs for non-DELETE DML kinds (INSERT, UPDATE, UPSERT) of `MutationDmlRecordField` / `MutationBulkDmlRecordField`, after the existing `requireDataTableMatchesInputTable` check at `FieldBuilder.java:3179`. Registers `SingleRecordTableField` with `SourceKey(target, pkColumns, [], Wrap.Record, cardinality, ResultRowWalk)`.
- `registerServiceCarrierDataField(BuildContext ctx, SingleRecordCarrierShape shape, MethodRef.Service method, String mutationName)` runs in the `Resolved.Result` arm of `@service` resolution (the arm that produces `MutationField.MutationServiceRecordField`) when the SDL field's return type resolves to a single-record carrier with a `DataElement.Table` data channel admitted by R159's `$source` sigil. The carrier-payload case has `ReturnTypeRef.ResultReturnType`, not `TableBoundReturnType`; the `Resolved.TableBound` arm (which produces `MutationServiceTableField` for direct-`@table` returns like `createFilm: Film`) is not the host. The helper registers `SingleRecordTableField` with `SourceKey(target, pkColumns, [], Wrap.TableRecord(target.recordClass()), cardinality, ResultRowWalk)`.

Both helpers compare-then-write: if `ctx.fieldRegistry.get(coords)` already holds a `ChildField.SingleRecordTableField` whose `sourceKey().wrap()` differs from the incoming carrier's wrap, the helper returns a rejection string (the caller produces an `UnclassifiedField` with `Rejection.structural`) and skips the registry write. Otherwise the helper calls `ctx.fieldRegistry.reclassify(coords, carrier, /* expectedExistingClass */ null)`. The verbless walk no longer registers `DataElement.Table` data fields, so the `null` admits both "no pre-existing entry" (first producer) and "matching-wrap pre-existing entry" (second producer with consistent wrap, which is the no-op overwrite path; the second producer's helper has just confirmed the wraps match). A non-`null` `expectedExistingClass` is reserved for R156's DELETE-overwrite path, where the pre-existing class is known to be the verbless walk's structural placeholder.

The `FieldRegistry.reclassify` API loosens to admit `null` for `expectedExistingClass`: the `Objects.requireNonNull` guard drops, and the Javadoc gains the carrier-data-field registration path as a second admitted use alongside R156's DELETE-overwrite. With `null`, the method admits any pre-existing entry (or none); with a non-`null` class, the existing structural guard fires as today.

**Carrier types are producer-kind-monomorphic.** A single-record carrier type returned by both a DML and a `@service` mutation would land both registrations at the same `(carrierType, dataFieldName)` coords with different `Wrap` shapes. New load-bearing key `carrier-data-field.single-producer-kind` declares the invariant:

> For any `(carrierType, dataFieldName)` coords resolving to `ChildField.SingleRecordTableField`, at most one `SourceKey.wrap` shape is registered across all producer sites in the schema. The two producer-site helpers (`FieldBuilder.registerDmlCarrierDataField` and `FieldBuilder.registerServiceCarrierDataField`) enforce the rule at write time via the compare-then-write check described above; the validator mirrors via a new `GraphitronSchemaValidator.checkCarrierProducerKind` site that walks the assembled `MutationField` leaves (not the carrier-coord registry, which holds only the surviving registration) and surfaces the rejection at the carrier level (order-independent, names both producer sites in the diagnostic).

The validator-side walk groups `MutationField` leaves by the carrier coords they would produce a registration for: `MutationDmlRecordField` / `MutationBulkDmlRecordField` for DML producers (carrier coords are `(rrt.returnTypeName(), shape.data().fieldName())`), and `MutationServiceRecordField` for `$source`-sigil service producers (carrier coords are read from the same shape resolution). When two leaves derive the same `(carrierType, dataFieldName)` with different `SourceKey.wrap` shapes, the validator emits one `ValidationError` per offending mutation, each citing the other. The producer-side helper's rejection is order-dependent (whichever mutation classifies second triggers it, naming only the second mutation in its `UnclassifiedField`); the validator-side mirror is order-independent and names both. Both sites tag `@DependsOnClassifierCheck` against the same key. The schema author's recourse is to split the carrier into two payload types (one per producer kind) or to converge the producers.


## `@service` admission predicate

`ServiceCatalog.reflectServiceMethod` (`ServiceCatalog.java:158-224`, load-bearing key `service-catalog-strict-service-return`) already enforces strict `TypeName.equals` against the caller-supplied `expectedReturnType`. `registerServiceCarrierDataField` computes:

- `Cardinality.ONE` (carrier's data field wrapper is non-list): `expectedReturnType = ClassName.get(target.recordClass())`.
- `Cardinality.MANY` (data field wrapper is list): `expectedReturnType = ParameterizedTypeName.get(ClassName.get(List.class), ClassName.get(target.recordClass()))`.

The strict predicate rejects `Set<XRecord>`, `Collection<XRecord>`, `Iterable<XRecord>`, raw `List`, `List<? extends XRecord>`, and any list element other than the exact data table's record class. No new admission rule, no message refinement layered on top: the existing strict-return text names the expected and actual types, which is sufficient to communicate the contract.

## Test surface

Pipeline-tier (`PipelineTier`) cases on `SingleRecordTableFieldServiceProducerPipelineTest`:

- *ONE / single-PK admission* — `@service` returning `XRecord` for a carrier with a non-list data field. Assert the registered `SourceKey` carries `Wrap.TableRecord(recordClass)`, `Reader.ResultRowWalk`, `Cardinality.ONE`.
- *MANY / single-PK admission* — `@service` returning `List<XRecord>`. Assert the registered `SourceKey` carries `Wrap.TableRecord(recordClass)`, `Cardinality.MANY`. Runtime-shape assertions are the execution tier's job; the pipeline tier asserts the structural choice (which `Wrap` permit lands).
- *MANY / composite-PK admission* — same with a two-PK table; assert the registered `SourceKey.columns` carries both PK columns in declaration order. Composite-PK keying shape is closed by the execution tier.
- *Wrong element type reject* — `@service` returning `List<OtherRecord>` for a carrier whose data table is `Regelverksamling`; assert rejection cites the expected `List<RegelverksamlingRecord>`.
- *Set / Iterable return reject* — `@service` returning `Set<XRecord>` and `Iterable<XRecord>`; assert structural rejection via the strict predicate.
- *Mixed-producer carrier reject* — two mutations (one DML, one `@service`) returning the same carrier type; assert classification rejects the second registration with the typed `Rejection` naming both.

Execution-tier (`ExecutionTier`) cases on `SingleRecordTableFieldServiceProducerExecutionTest` (mirrors `DmlBulkMutationsExecutionTest`):

- *MANY-arm end-to-end* — `@service` mutation returns `List<RegelverksamlingRecord>`; assert the data-field fetcher reads PKs off the record list, runs the follow-up SELECT, and projects rows in input order.
- *ONE-arm end-to-end* — `@service` mutation returns a single `RegelverksamlingRecord`; assert the follow-up SELECT runs and returns the expected row.
- *Empty source short-circuit* — `@service` returns empty `List<XRecord>`; assert no SELECT runs and the data field returns an empty list.

Unit-tier (`UnitTier`) cases added to the existing `SourceKeyTest` (no new test class):

- Loosened `ResultRowWalk` admission: positive case for `Wrap.TableRecord(target.recordClass())` + empty path; rejection case for `Wrap.TableRecord(other)` (cross-table mismatch); rejection case for non-empty path under either wrap.

## Out of scope

- **R141 PK-keyed-map → VALUES-idx-JOIN migration.** R141 is shipped working code with its own audit surface (`DmlBulkMutationsExecutionTest`); migrating its Java-side order preservation to a SQL-side `ORDER BY input.idx` against a derived VALUES table (the `@lookupKey` idiom in `InlineLookupTableFieldEmitter` / `LookupValuesJoinEmitter`) is a refactor of working code, not part of admitting the `@service` producer. A follow-up Backlog item names R158 as prereq and lifts every `Wrap` arm to the VALUES-idx-JOIN shape in one move, against its own pipeline + execution test surface.
- **`Reader.ResultRowWalk` consumed outside `SingleRecordTableField`.** The widened invariant pairs only with the carrier data field's permit today; any future consumer must adopt the same wrap-dispatch pattern or split its own permit.
- **`@service` producer with `DataElement.Record` data field.** Producer-kind discrimination is irrelevant for the identity-passthrough permit (`SingleRecordIdentityField`): the data field's value IS the parent's value regardless of producer. R96 alignment holds — no `@record` on the carrier is needed.

## Migration notes

- `Reader.ResultRowWalk`'s compact-constructor invariant loosens; the renamed load-bearing key updates one `@LoadBearingClassifierCheck` annotation on `SourceKey` and one `@DependsOnClassifierCheck` annotation on `FetcherEmitter`.
- `GraphitronSchemaBuilder.registerCarrierDataField`'s `DataElement.Table` switch arm becomes a no-op; the arm comment names the deferral and points at the two `FieldBuilder` registration helpers.
- `FieldBuilder.classifyMutationField` gains the call to `registerDmlCarrierDataField` for non-DELETE DML kinds (next to the existing carrier-resolution path around `FieldBuilder.java:3175-3228`), and the call to `registerServiceCarrierDataField` in the `Resolved.Result` arm of `@service` resolution (around `FieldBuilder.java:3107-3121`, after the existing `checkSourceSigilTypeMatch` so the registration only runs on a successful type match). The Result arm produces `MutationServiceRecordField`; the carrier-payload case never reaches the `Resolved.TableBound` arm. Both helpers pass `expectedExistingClass = null` to `reclassify` after the compare-then-write check.
- `FieldRegistry.reclassify`'s `expectedExistingClass` parameter loosens to admit `null`: drop the `Objects.requireNonNull(expectedExistingClass)` at `FieldRegistry.java:71` and revise the method Javadoc to name the carrier-data-field registration path alongside R156's DELETE-overwrite use. The structural-guard arm (non-`null` class, existing entry that doesn't match) is unchanged.
- **R156's `registerDeleteCarrierDataField` Table arm needs an in-commit fix.** Today `FieldBuilder.java:2872` passes `ChildField.SingleRecordTableField.class` as `expectedExistingClass`, relying on the verbless walk's pre-registration. After R158 hollows out that walk arm, no prior registration exists for `DataElement.Table` carriers; R156's `reclassify` call drops to `expectedExistingClass = null` in the same commit. The migration step is one-line; the comment block at `FieldBuilder.java:2868-2871` revises in lockstep to reflect that the verbless walk no longer pre-registers.
- `FetcherEmitter.buildSingleRecordTableFetcherValue` factors into a wrap-permit switch; the existing `Wrap.Record` arms move into the `case Wrap.Record` branch and the new `Wrap.TableRecord` arms land in a parallel branch. The cardinality switch sits inside each wrap arm.

---
id: R61
title: "Emit Record1<T> instead of Row1<T> for single-column DataLoader keys"
status: In Review
bucket: architecture
priority: 7
theme: service
depends-on: []
---

# Emit Record1<T> instead of Row1<T> for single-column DataLoader keys

## Motivation

The framework currently emits `Row1<T>` keys for the row-keyed `BatchKey` arms. `Row1<T>` is jOOQ's SQL-expression type for tuple-IN comparisons against the database; it has no value accessor. A developer who signs `Set<Row1<Integer>> keys` cannot read each key's column value at the application side; the workaround today is to switch to `Set<Record1<Integer>>` (which classifies as `MappedRecordKeyed` rather than `MappedRowKeyed`). The test fixture `FilmService.titleUppercase` does exactly this.

`Record1<T>` extends `Row1<T>`, so SQL composition keeps working at the framework boundary, and adds `value1()` for application-side reading. The fix: emit `RecordN<...>` keys at the rows-method boundary so the developer's view is uniformly `RecordN`, regardless of which container axis (`Set` mapped vs. `List` positional) the field's classification picks.

## Scope

Four `BatchKey` arms whose key-extraction site has a jOOQ `Record` source available flip from `RowN<...>` to `RecordN<...>` in this iteration:

| Variant | Path | Source at extraction | New emit |
|---|---|---|---|
| `MappedRowKeyed` | `@service` (ParentKeyed) | `(Record) env.getSource()` | `record.into(table.col1, ...)` |
| `RowKeyed` (ParentKeyed face) | `@splitQuery` on `@table` parent | `(Record) env.getSource()` | `record.into(table.col1, ...)` |
| `RowKeyed` (RecordParentBatchKey face, jOOQ-record subarm) | `@record` parent w/ catalog FK; `JooqTableRecordType` / `JooqRecordType` resultType | `(Record) env.getSource()` | `record.into(table.col1, ...)` |
| `AccessorRowKeyedSingle` / `AccessorRowKeyedMany` | `@record` parent w/ typed `TableRecord` accessor | `TableRecord __elt` from accessor | `__elt.into(table.col1, ...)` |

After this iteration, six of seven variants surface `RecordN` keys at the rows-method boundary. The seventh (`LifterRowKeyed`) stays on `RowN`; that asymmetry is intentional and tracked under the deferred follow-up below.

Consumer-visible behavior change: a `@service` rows-method that signs `Set<Row1<Integer>> keys` against a `MappedRowKeyed` field will fail classification with the existing strict-return check after the flip; the validator's expected outer shape changes from `Map<Row1<Integer>, V>` to `Map<Record1<Integer>, V>`. The migration is mechanical (replace `Row1` with `Record1` in the developer's source-shape declarations); the existing `Set<Record1<Integer>>` workaround keeps classifying unchanged.

## Out of scope (deferred)

Two arms cannot flip in this iteration without out-of-band changes; they're tracked under [`recordn-key-parity-lifter-and-non-jooq-record-parents.md`](recordn-key-parity-lifter-and-non-jooq-record-parents.md):

- **`LifterRowKeyed` flip.** `BatchKeyLifterDirectiveResolver` pins the `@batchKeyLifter`-annotated method's return type to `org.jooq.Row1..Row22`. Flipping `LifterRowKeyed.keyElementType()` to `RecordN` requires the validator to require / accept `Record1..Record22` returns, which is a public API change for every existing consumer lifter today. `LifterRowKeyed` keeps `keyElementType() = RowN<...>` here.
- **`RowKeyed` in `RecordParentBatchKey` face with `JavaRecordType` / `PojoResultType` parent.** The `buildFkRowKey` arm reads scalar values from typed Java getters (no jOOQ `Record` in scope); constructing a `RecordN` from scalars without a `DSLContext` round-trip or a synthetic empty-record builder isn't worth the runtime cost without a fixture that exercises this combo. The classifier rejects this combination at classify time with a structured rejection until the deferred item resolves the construction strategy.

## Implementation

1. **`BatchKey.keyElementType()` switch.** `RowKeyed`, `MappedRowKeyed`, `AccessorRowKeyedSingle`, `AccessorRowKeyedMany` arms switch from `rowNType(...)` to `recordNType(...)`. `LifterRowKeyed` stays on `rowNType(...)`.
2. **`BatchKey.javaTypeName()` switch.** Same four arms switch their `containerType(...)` shape from `"Row"` to `"Record"`. `LifterRowKeyed.javaTypeName()` stays on `"Row"`.
3. **`GeneratorUtils.buildKeyExtraction` (`ParentKeyed`).** Collapse the four-arm switch to a single arm using `record.into(table.col1, ...)`; the row-vs-record axis at this site disappears.
4. **`GeneratorUtils.buildKeyExtractionWithNullCheck` (`RowKeyed` only).** Keep per-column scalar extraction for the null check; replace the `DSL.row(...)` tail with `RecordN<...> key = ((Record) env.getSource()).into(table.col1, ..., table.colN)` (re-reading from the record by `Field` reference for each PK column; the redundant read is O(1) and keeps the null-check shape uniform). `RowKeyed` carries 1..N columns (single FK on a single-cardinality `@splitQuery` parent, or a composite FK in the same shape); the `into(Field<T1>, Field<T2>, ...)` overload returns the corresponding `RecordN`.
5. **`GeneratorUtils.buildFkRowKey`.** `JooqTableRecordType` and `JooqRecordType` arms switch to `record.into(table.col1, ...)`. The `JavaRecordType` / `PojoResultType` arms become `throw new IllegalStateException(...)` defensive — the classifier rejection in step 6 makes them unreachable, and the throw is for the type system's exhaustiveness.
6. **Classifier rejection for the deferred combo.** In `FieldBuilder.deriveBatchKeyForResultType` (`FieldBuilder.java:2703-2712` — the sole site that constructs a `RowKeyed` for a `RecordParentBatchKey` parent today), reject the `JavaRecordType` / `PojoResultType` parent combination with a structured `Rejection` whose message names the deferred follow-up slug. The current null-return arm for untyped `PojoResultType` (`fqClassName() == null` at line 2708) stays; the new rejection covers the typed `JavaRecordType` and typed `PojoResultType` cases. Sibling unit-test coverage in the existing `*ValidationTest` set.
7. **`GeneratorUtils.buildAccessorRowKeySingle` / `Many`.** Replace `DSL.row(__elt.<getter>(), ...)` with `__elt.into(table.col1, ...)`. The `__elt` local stays a typed `TableRecord`; `Record.into(Field<T1>, Field<T2>, ...)` returns the corresponding `RecordN`.
8. **`@DependsOnClassifierCheck` annotation refresh.** The two checks under `buildAccessorRowKeySingle` / `Many` reference column accessors via `recordGetter(...)` ("javaName().") in their `reliesOn` text; update to reflect the `Field`-typed `into(...)` projection.

## Tests

- **L1 (`BatchKeyTest`).** Update `accessorRowKeyedSingleJavaTypeNameForSingleColumnPk` and `accessorRowKeyedManyJavaTypeNameForCompositePk` expectations to `Record1<...>` / `Record2<...>`.
- **L1 (new).** `BatchKey.keyElementType()` / `javaTypeName()` per variant: `LifterRowKeyed` stays `Row1<...>`; the other six arms return the `RecordN` shape. Captures the post-R61 asymmetry directly.
- **L3 (validator).** `ServiceFieldValidationTest`, `SplitTableFieldValidationTest`: existing strict-return cases that hard-code `Row1<...>` / `Row2<...>` expectations refresh to `Record1<...>` / `Record2<...>`. New rejection case for `RowKeyed` + `JavaRecordType` / `PojoResultType` parent.
- **L4 (pipeline).** `ServiceRootFetcherPipelineTest`, `FetcherPipelineTest`, `SplitTableFieldPipelineTest`: snapshot or structural assertions on emitted rows-method signatures and key-extraction code refresh to `Record1<...>` / `record.into(...)`. `SplitTableFieldPipelineTest:90` (the `java.util.List<org.jooq.Row1<java.lang.Integer>>` literal) is one anchor.
- **L5 (compile spec).** `TestServiceStub.java` source-shape stubs that take `List<Row1<Integer>>` flip to `List<Record1<Integer>>` (or stay on the `Record1` variants where they already do). The fixture `getFilmsWithSetOfRow1Sources` flips into a deliberately-mismatched validator case (now expected to reject) or refreshes its sources type.
- **L6 (execution).** No new coverage; the existing `FilmService.titleUppercase` execution test confirms `value1()` keeps working under the new emission.

## Open question (closed in this iteration)

The original R61 plan body asked: "does `Row1` afford a tuple-IN planner hint that `Record1` may not?" jOOQ's `Record1<T>` extends `Row1<T>`, so every typed `Row1`-API call site that today receives a `Row1<T>` continues to type-check when handed a `Record1<T>`. The framework's WHERE-clause emission paths read keys via `Row`-typed APIs; the existing emission stays unchanged. Resolution: no planner-hint change.

## Out of scope

- Element-shape conversion for `Set<TableRecord>` / `List<TableRecord>` developer signatures ([R70 / `service-rows-tablerecord-key-shape.md`](service-rows-tablerecord-key-shape.md), depends on this item but separate emitter work).
- `RowN` (n > 1) → `RecordN` (n > 1) parity for arms that already use composite keys. The four flipping arms here cover composite keys via `recordNType(...)` already; no separate work needed beyond the implementation steps above.

## Roadmap entries (siblings / dependencies)

- **Coordinates with** [`service-rows-tablerecord-key-shape.md`](service-rows-tablerecord-key-shape.md) (R70). R70 depends on this item: once keys are `RecordN`, R70's `Set<TableRecord>` / `List<TableRecord>` element-shape conversion becomes a thin `keys.stream().map(k -> Tables.FILM.newRecord(k.value1(), ...)).collect(...)` step at the rows-method call site.
- **Sibling slice:** [`recordn-key-parity-lifter-and-non-jooq-record-parents.md`](recordn-key-parity-lifter-and-non-jooq-record-parents.md). Lifts the asymmetry left by this iteration: brings `LifterRowKeyed` and the `JavaRecordType` / `PojoResultType` subarm of `RowKeyed` into `RecordN` parity.

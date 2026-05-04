---
id: R61
title: "Emit Record1<T> instead of Row1<T> for single-column DataLoader keys"
status: In Progress
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

R61 *adds* `RecordN` to the developer-facing surface; it does not replace `RowN`. A `@service` rows-method may still sign its source declaration with `Set<Row1<Integer>>` / `List<Row1<Integer>>` / the matching `Map<Row1<Integer>, V>` return; both shapes classify and dispatch cleanly. The framework emits `Record<N>` keys uniformly at the data-fetcher boundary and adapts at the developer-method call site:

- The strict-return check at the @service classifier accepts either `Row<N>` or `Record<N>` at the key position (in the source-list element, the mapped-set element, and the mapped-Map key).
- At the BatchLoader call site, when the developer's signature picked `Row<N>`, the framework converts the `Record<N>` keys via `Record::valuesRow` before invocation. `Record<N>.valuesRow()` returns a `Row<N>` whose `field<N>()` accessors carry the inline values (not column references), which is what the Row contract specifies.
- For mapped-shape returns (`Map<Row<N>, V>` from the developer): the framework looks up each input `Record<N>`'s response by `record.valuesRow()` against the developer's map. jOOQ's `Row.equals` / `hashCode` are value-based, so the lookup composes.
- For positional returns (`List<List<Record>>` / `List<Record>` etc.), there is no key in the return shape; no back-conversion needed.

Developers thus pick the surface they want: `Set<Record1<Integer>>` for `value1()` access, `Set<Row1<Integer>>` for the lighter Row API.

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
9. **Validator: relax the @service strict-shape check at the key position.** The check that validates the developer's source-method signature against `BatchKey.keyElementType()` accepts either `Row<N>` or `Record<N>` at every key-typed slot:
   - source-list element (`List<Row<N>>` / `List<Record<N>>`),
   - mapped-set element (`Set<Row<N>>` / `Set<Record<N>>`),
   - mapped-Map key position (`Map<Row<N>, V>` / `Map<Record<N>, V>` in the developer's return type).
   The shape check still pins `N`, the column-class tuple, and the container axis (`List` vs `Set` vs `Map`); only the element/key Row-vs-Record axis becomes free. Capture the developer's choice on the BatchKey or on a sibling carrier so the dispatch site (step 10) can fork.
10. **Dispatch-site `valuesRow` adapter.** At the BatchLoader call site emitted by `TypeFetcherGenerator`, when the developer's signature picked `Row<N>` (per step 9):
    - **Forward** (framework `Set<Record<N>>` / `List<Record<N>>` → developer's `Set<Row<N>>` / `List<Row<N>>`): emit `keys.stream().map($T::valuesRow).collect(...)` before the developer-method invocation.
    - **Mapped return** (developer's `Map<Row<N>, V>` → framework's `Map<Record<N>, V>` for the loader): for each input `Record<N>` in the original key set, look up its value via `developerMap.get(record.valuesRow())` and rebuild the loader-shaped Map. jOOQ's `Row.equals` / `hashCode` are value-based, so the lookup composes.
    - **Positional return** (`List<List<Record>>` / `List<Record>`): no key in the return shape; no back-conversion needed.

## Tests

- **L1 (`BatchKeyTest`).** Update `accessorRowKeyedSingleJavaTypeNameForSingleColumnPk` and `accessorRowKeyedManyJavaTypeNameForCompositePk` expectations to `Record1<...>` / `Record2<...>`.
- **L1 (new).** `BatchKey.keyElementType()` / `javaTypeName()` per variant: `LifterRowKeyed` stays `Row1<...>`; the other six arms return the `RecordN` shape. Captures the post-R61 asymmetry directly.
- **L3 (validator).** `ServiceFieldValidationTest`, `SplitTableFieldValidationTest`: existing strict-return cases that hard-code `Row1<...>` / `Row2<...>` expectations stay valid (the relaxed check accepts both). New parameterized cases pin: same field with `Set<Row1<Integer>>` and `Set<Record1<Integer>>` source declarations both classify cleanly; same for `Map<Row1<Integer>, V>` / `Map<Record1<Integer>, V>` returns. New rejection case for `RowKeyed` + `JavaRecordType` / `PojoResultType` parent.
- **L4 (pipeline).** `ServiceRootFetcherPipelineTest`, `FetcherPipelineTest`, `SplitTableFieldPipelineTest`: snapshot or structural assertions on emitted rows-method signatures and key-extraction code refresh to `Record1<...>` / `record.into(...)`. `SplitTableFieldPipelineTest:90` (the `java.util.List<org.jooq.Row1<java.lang.Integer>>` literal) is one anchor. New pipeline assertion: when the developer's source-shape is `Set<Row1<...>>`, the emitted BatchLoader applies the `Record::valuesRow` adapter before invoking the developer method (and rebuilds the mapped return).
- **L5 (compile spec).** `TestServiceStub.java` keeps both `Row1`-source and `Record1`-source fixtures; both classify cleanly post-R61. The `getFilmsWithSetOfRow1Sources` and `getFilmsWithSetOfRecord1Sources` siblings remain as the dual-shape coverage anchor.
- **L6 (execution).** Two passes: `FilmService.titleUppercase` (already a `Record1<Integer>`-source fixture) confirms `value1()` keeps working; a sibling fixture using `Row1<Integer>` source confirms the `valuesRow` adapter delivers value-bearing Row keys at runtime.

## Open question (closed in this iteration)

The original R61 plan body asked: "does `Row1` afford a tuple-IN planner hint that `Record1` may not?" jOOQ's `Record1<T>` extends `Row1<T>`, so every typed `Row1`-API call site that today receives a `Row1<T>` continues to type-check when handed a `Record1<T>`. The framework's WHERE-clause emission paths read keys via `Row`-typed APIs; the existing emission stays unchanged. Resolution: no planner-hint change.

## Out of scope

- Element-shape conversion for `Set<TableRecord>` / `List<TableRecord>` developer signatures ([R70 / `service-rows-tablerecord-key-shape.md`](service-rows-tablerecord-key-shape.md), depends on this item but separate emitter work).
- `RowN` (n > 1) → `RecordN` (n > 1) parity for arms that already use composite keys. The four flipping arms here cover composite keys via `recordNType(...)` already; no separate work needed beyond the implementation steps above.

## Roadmap entries (siblings / dependencies)

- **Coordinates with** [`service-rows-tablerecord-key-shape.md`](service-rows-tablerecord-key-shape.md) (R70). R70 depends on this item: once keys are `RecordN`, R70's `Set<TableRecord>` / `List<TableRecord>` element-shape conversion becomes a thin `keys.stream().map(k -> Tables.FILM.newRecord(k.value1(), ...)).collect(...)` step at the rows-method call site.
- **Sibling slice:** [`recordn-key-parity-lifter-and-non-jooq-record-parents.md`](recordn-key-parity-lifter-and-non-jooq-record-parents.md). Lifts the asymmetry left by this iteration: brings `LifterRowKeyed` and the `JavaRecordType` / `PojoResultType` subarm of `RowKeyed` into `RecordN` parity.

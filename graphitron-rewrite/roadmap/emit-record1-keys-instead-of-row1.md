---
id: R61
title: "Add Record1<T> source-shape support alongside Row1<T>"
status: In Review
bucket: architecture
priority: 7
theme: service
depends-on: []
---

# Add Record1<T> source-shape support alongside Row1<T>

## Motivation

The framework historically supported only one shape for the developer-facing batch-key key element: `Row1<T>` (and the wider `RowN<...>` for composite keys). `Row1<T>` is jOOQ's SQL-expression type for tuple-IN comparisons against the database; it has no value accessor. A developer who signs `Set<Row1<Integer>> keys` cannot read each key's column value at the application side — they have to widen to a separate workaround variant.

`Record1<T>` extends `Row1<T>`, so SQL composition keeps working at the framework boundary, and adds `value1()` for application-side reading. R61 *adds* `RecordN` source-shape support to the `@service` classifier path so developers can choose either shape at the source declaration: `Set<Row1<Integer>>` (Row surface, no `value1()`), `Set<Record1<Integer>>` (Record surface, with `value1()`). Both classify and dispatch cleanly.

## Scope

The choice surfaces through `BatchKey` variant identity: `ServiceCatalog.classifySources` already routes `List<Row<N>>` / `Set<Row<N>>` to `RowKeyed` / `MappedRowKeyed` and `List<Record<N>>` / `Set<Record<N>>` to `RecordKeyed` / `MappedRecordKeyed`. Each variant's `keyElementType()` reflects the developer's chosen shape, and the matching `GeneratorUtils.buildKeyExtraction` arm emits a key construction that matches the runtime type.

| Variant | Path | Developer-facing key shape | Key construction |
|---|---|---|---|
| `RowKeyed` (ParentKeyed face) | `@service` Row source on `@table` parent; `@splitQuery` on `@table` parent | `Row<N>` | `DSL.row((Record) env.getSource().get(table.col), ...)` |
| `MappedRowKeyed` | `@service` Row source on `@table` parent (Set container) | `Row<N>` | (same shape; `Set` instead of `List`) |
| `RecordKeyed` | `@service` Record source on `@table` parent | `Record<N>` | `((Record) env.getSource()).into(table.col, ...)` |
| `MappedRecordKeyed` | `@service` Record source on `@table` parent (Set container) | `Record<N>` | (same; `Set`) |
| `RowKeyed` (RecordParentBatchKey face) | `@record` parent w/ catalog FK | `Row<N>` (auto-derived; no developer-facing source on this face) | `DSL.row((scalar getters / record.get) per parent ResultType, ...)` |
| `AccessorKeyedSingle` / `AccessorKeyedMany` | `@record` parent w/ typed `TableRecord` accessor (the lift-back path after `@service` / `@externalField` returns a `TableRecord`-shaped value) | `Record<N>` (no developer-facing source — projection is internal; we emit `Record<N>` so the auto-emitted rows-method can read scalars via `value<N>()`) | `__elt.into(table.col, ...)` |
| `LifterRowKeyed` | `@record` parent w/ `@batchKeyLifter` | `Row<N>` (deferred to R71 — the consumer-supplied lifter's return type is the contract; symmetry there is a separate item) | `Lifters.method((BackingClass) env.getSource())` |

After this iteration, the `@service` classifier accepts both source shapes; the `@record-parent` accessor arms ship with `RecordN` keys (no consumer-facing source); the `@record-parent` `RowKeyed` arm and the lifter arm stay on `RowN`.

The `Accessor*` variants were renamed from `AccessorRowKeyed{Single,Many}` to `AccessorKeyed{Single,Many}` in this iteration. The `Row` discriminator in the previous name was leaking an emit-site detail (which jOOQ-typed local — `RowN` vs `RecordN` — the framework picks for the projected key) into the variant identity. There is no developer-supplied source on these arms — the role of the variant is *the lift-back into Graphitron scope after a `@service` or `@externalField` directive whose return is a `TableRecord` (or `List`/`Set` thereof)* — so the variant name doesn't need to encode the projection axis. Used by `ServiceTableField` and `RecordTableField` (and `ExternalField` whose return is `TableRecord`-shaped); the source-shape constraint (typed `TableRecord` accessor) lives in the variant's javadoc and is enforced by `FieldBuilder.deriveBatchKeyFromTypedAccessor`.

## Out of scope (deferred)

- **`@batchKeyLifter` lifter return-type symmetry.** `BatchKeyLifterDirectiveResolver` still pins the consumer-supplied static method to `org.jooq.Row1..Row22` returns. Bringing that surface to the same Row-or-Record symmetry the `@service` source-shape classifier has lives under [`recordn-key-parity-lifter-and-non-jooq-record-parents.md`](recordn-key-parity-lifter-and-non-jooq-record-parents.md) (R71). The migration shape (accept either with per-lifter `keyElementType()` branching, vs require `RecordN`, vs emit a wrapper) is a public-API decision worth making in its own Spec.

## Implementation

1. **`BatchKey.keyElementType()` per-variant shape.** `RowKeyed` / `MappedRowKeyed` / `LifterRowKeyed` produce `RowN<...>`; `RecordKeyed` / `MappedRecordKeyed` / `AccessorKeyedSingle` / `AccessorKeyedMany` produce `RecordN<...>`. The `keyElementType()` switch in `BatchKey` is the single source of truth.
2. **`BatchKey.javaTypeName()` matches.** Each variant's `containerType(...)` shape (`Row` vs `Record`) tracks `keyElementType()` and the variant's container axis (`List` for positional, `Set` for mapped).
3. **`GeneratorUtils.buildKeyExtraction` (`ParentKeyed`) forks by variant.** `RowKeyed` / `MappedRowKeyed` arms emit `DSL.row((Record) env.getSource().get(table.col), ...)`; `RecordKeyed` / `MappedRecordKeyed` arms emit `((Record) env.getSource()).into(table.col, ...)`. The classifier's source-shape detection picks the variant; this site translates that into the matching emit.
4. **`GeneratorUtils.buildKeyExtractionWithNullCheck` (`RowKeyed` only).** Per-column scalar extraction for the null check, then `RowN<...> key = DSL.row(fkVal0, fkVal1, ...)` after the null-check passes. Single-cardinality `@splitQuery` on a `@table` parent is the only caller today, and it always classifies as `RowKeyed`.
5. **`GeneratorUtils.buildFkRowKey` (`RecordParentBatchKey RowKeyed`).** Reads scalar values per parent `ResultType` (jOOQ `TableRecord`, jOOQ `Record`, Java record getter, typed POJO getter) and constructs the `RowN<...>` via `DSL.row(...)`. All four parent-type arms produce keys; no combo is rejected.
6. **`GeneratorUtils.buildAccessorKeySingle` / `Many`.** Auto-derived from a typed `TableRecord` accessor; no developer-facing source. Emits `__elt.into(table.col1, ...)` to produce `RecordN<...>` keys, giving the auto-emitted rows-method's `value<N>()` access for the parent VALUES table emission.
7. **`SplitRowsMethodEmitter` parent VALUES emission.** RowN-keyed arms (`RowKeyed`, `LifterRowKeyed`) use `k.field<N>()` (returns the inline-value Field a `DSL.row(value, ...)`-constructed Row carries). RecordN-keyed accessor arms use `DSL.val(k.value<N>())` (extract the scalar; wrap it as a bind parameter Field that typechecks against the inline-`i` first arg).
8. **`@DependsOnClassifierCheck` annotations.** The two checks under `buildAccessorKeySingle` / `Many` describe the `Field`-typed `into(...)` projection (R60-introduced; this scope keeps them on `RecordN`).
9. **Lift Invariant #10: single-cardinality `RecordTableField` / `RecordLookupTableField`.** R60 left this gated at `GraphitronSchemaValidator.validateRecordParentSingleCardinalityRejected` because the rows-method router in `SplitRowsMethodEmitter.buildForRecordTable` tied the single-record-per-key arm to `AccessorKeyedMany` only — a single-cardinality field would have routed to `buildListMethod` (returns `List<List<Record>>`) while `TypeFetcherGenerator.buildRecordBasedDataFetcher` already wants `Record` per key for single cardinality (`valueType = (dispatch == LOAD_MANY || !isList) ? Record : List<Record>`). Lift the gate by extending `RecordTableField.emitsSingleRecordPerKey()` (and the missing override on `RecordLookupTableField`) to also be true for single-cardinality fields, then drop `validateRecordParentSingleCardinalityRejected`. The data-fetcher and `buildSingleMethod` already handle the four `RecordParentBatchKey` permits correctly; this change just unblocks the router.

## Tests

- **L1 (`BatchKeyTest`).** New parameterised test pins `keyElementType()` and `javaTypeName()` per variant: `RowKeyed`, `MappedRowKeyed`, `LifterRowKeyed` → `RowN<...>`; `RecordKeyed`, `MappedRecordKeyed`, `AccessorKeyedSingle`, `AccessorKeyedMany` → `RecordN<...>`. Captures the seven-variant shape map directly.
- **L3 (validator).** `ServiceFieldValidationTest`: parameterised case asserts both `Set<Row1<Integer>>` and `Set<Record1<Integer>>` source declarations classify cleanly on the same field (each routes to its matching `*RowKeyed` / `*RecordKeyed` variant). Same for `Map<...>` returns. `SplitTableFieldValidationTest` strict-return cases that hard-code `Row1<...>` / `Row2<...>` expectations stay on those expectations (the `@splitQuery` path always classifies as `RowKeyed`).
- **L4 (pipeline).** `ServiceRootFetcherPipelineTest`, `FetcherPipelineTest`, `SplitTableFieldPipelineTest`: assertions track the variant shape — `Row1` for the `@splitQuery` and `@service` Row-source paths; `Record1` for the `@service` Record-source paths and the auto-derived accessor arms. The accessor-arm pipeline assertion includes the `DSL.val(k.value<N>())` parent VALUES emission shape.
- **L5 (compile spec).** `TestServiceStub.java` keeps both `Row1`-source and `Record1`-source fixtures; both compile and classify cleanly. The `getFilmsWithSetOfRow1Sources` / `getFilmsWithSetOfRecord1Sources` siblings remain as the dual-shape coverage anchor.
- **L6 (execution).** `FilmService.titleUppercase` (Record1 source) confirms `value1()` works in the developer-side iteration. A sibling fixture using `Row1` source confirms the `field1()`-based dispatch keeps working — values flow through the SQL VALUES table and reach the database correctly.

## Open question (closed in this iteration)

The original R61 plan asked: "does `Row1` afford a tuple-IN planner hint that `Record1` may not?" jOOQ's `Record1<T>` extends `Row1<T>`, so every typed `Row1`-API call site that receives a `Row1<T>` continues to type-check when handed a `Record1<T>`. The framework's WHERE-clause emission paths read keys via `Row`-typed APIs; the existing emission is shape-agnostic. Resolution: no planner-hint change.

## Out of scope

- Element-shape conversion for `Set<TableRecord>` / `List<TableRecord>` developer signatures ([R70 / `service-rows-tablerecord-key-shape.md`](service-rows-tablerecord-key-shape.md)). Once Record source shape is supported, R70's element-shape adapter becomes a thin call-site step.

## Roadmap entries (siblings / dependencies)

- **Coordinates with** [`service-rows-tablerecord-key-shape.md`](service-rows-tablerecord-key-shape.md) (R70). R70 builds on the dual-shape support landed here.
- **Sibling slice:** [`recordn-key-parity-lifter-and-non-jooq-record-parents.md`](recordn-key-parity-lifter-and-non-jooq-record-parents.md) (R71) brings the `@batchKeyLifter` consumer-supplied surface into the same shape-symmetry posture this item lands on the `@service` source-shape path.

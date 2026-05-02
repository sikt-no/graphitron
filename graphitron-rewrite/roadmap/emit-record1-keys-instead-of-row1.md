---
id: R61
title: "Emit Record1<T> instead of Row1<T> for single-column DataLoader keys"
status: Backlog
bucket: architecture
priority: 7
theme: service
depends-on: []
---

# Emit Record1<T> instead of Row1<T> for single-column DataLoader keys

Split out from R32's open follow-ups. The framework currently emits `Row1<T>` keys for the `RowKeyed` and `MappedRowKeyed` `BatchKey` variants (single-column key from a `Sources` parameter typed as `Set<TableRecord>` / `List<TableRecord>` / `Set<Row1<T>>` / `List<Row1<T>>`). `Row1<T>` is jOOQ's SQL-expression type for tuple-IN comparisons against the database; it has no value accessor. A developer who signs `Set<Row1<Integer>> keys` cannot read each key's column value at the application side.

`Record1<T>` extends `Row1<T>` (so SQL composition keeps working at the framework boundary) and adds `value1()` for application-side reading. Today the workaround is for the developer to switch their source-shape to `Set<Record1<Integer>>` (which classifies as `MappedRecordKeyed` rather than `MappedRowKeyed`); the test fixture `FilmService.titleUppercase` does exactly this. Devs whose actual data shape is `Set<TableRecord>` or `List<TableRecord>` hit the same wall as `Row1` devs since both classify as the `Row*Keyed` family.

The fix: emit `Record1<T>` (or, more generally, `RecordN<...>`) keys regardless of which source-shape the developer chose, while keeping the `RowKeyed` / `MappedRowKeyed` classification to drive other branches (e.g. element-shape conversion at the call site). This decouples "what the framework hands the developer at the rows-method boundary" from "what the developer wrote in their `Sources` parameter".

Concretely, this likely involves:

- `GeneratorUtils.keyElementType` collapsing the row-vs-record axis at emission time so it returns `RecordN<...>` for all four `ParentKeyed` permits with `>=1` columns.
- `buildKeyExtraction` switch (currently emits `DSL.row(...)` for the row-keyed arms and `record.into(...)` for the record-keyed arms) reusing the `into(...)` form for the row-keyed arms too, since that already produces a `RecordN`.
- Re-running the existing `serviceField_*` and `splitQuery_*` test cases under the new emission to check shape changes.
- Element-shape conversion (R32's deferred follow-up) gets simpler once keys are uniformly `RecordN`: developer's signature `Set<TableRecord>` becomes a thin `keys.stream().map(k -> Tables.FILM.newRecord(k.value1(), ...)).collect(...)` step.

## Open question

The `Row1` cardinality affords a tuple-IN hint to jOOQ's planner that `Record1` may not (Record1 IS a Row1, but the framework's downstream code probably consumes it via `Row1`-typed APIs). Verify that the existing fetcher emission's WHERE-clause construction continues to work unchanged when keys are `RecordN`. If a downstream API requires the narrower `Row1`, the conversion can happen at the framework's WHERE-clause site (where the keys are passed to jOOQ); the developer's view stays `RecordN`.

## Out of scope

- Element-shape conversion for `Set<TableRecord>` / `List<TableRecord>` developer signatures (R32's open follow-up §1; depends on this item but separate).
- `RowN` (n > 1) → `RecordN` (n > 1) parity. Likely the same shape fix; confirm during implementation.

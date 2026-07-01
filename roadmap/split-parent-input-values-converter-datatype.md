---
id: R413
title: "Parent-input VALUES table in split/reference batch fetchers drops converter-backed column DataType, breaking joins on numeric-domain keys"
status: Backlog
bucket: bug
priority: 3
depends-on: []
created: 2026-07-01
last-updated: 2026-07-01
---

# Parent-input VALUES table in split/reference batch fetchers drops converter-backed column DataType, breaking joins on numeric-domain keys

## Problem

The DataLoader rows-method emitted for a `@splitQuery` / `@reference` batch child correlates the target table to a `VALUES (idx, parent_key…)` derived table. `SplitRowsMethodEmitter.emitParentInputAndFkChain` builds each parent-key cell straight from the DataLoader key row (`SplitRowsMethodEmitter.java:265-272`):

```java
rowArgs.add(", k.field$L()", i + 1);   // Field<T> at the key's *Java* type
```

and types both the `Row<N>` type-args (`:189-194`) and the JOIN-predicate RHS field lookup (`emitFromBridgeAndParentJoin`, `:367-378`, `parentInput.field(sqlName, <ColumnClass>.class)`) from `ColumnRef.columnClass()` — the column's **Java** class, not its jOOQ `DataType`. For a column carrying a `Converter`, `columnClass()` is the converted user type (e.g. `java.lang.String`), so the `VALUES` literal renders at the wrong SQL type and the correlation JOIN compares mismatched types.

Concretely, against a real consumer schema (utdanningsregisteret): `Campus.ORGANISASJONSKODE` is the domain `kodeverk.kode_numerisk_domain` (over `BIGINT`) with a jOOQ `Converter` (`Long ⇄ String`). The key value is read as `String` and re-wrapped as a bare `Field<String>`, so the `VALUES` cell renders as `character varying`. The emitted correlation JOIN is then:

```
campus_c0.organisasjonskode  (kode_numerisk_domain / bigint)  =  parentInput.organisasjonskode  (character varying)
```

PostgreSQL has no `bigint-domain = varchar` operator, so the fetch throws `operator does not exist: kodeverk.kode_numerisk_domain = character varying`, and every parent row's child (`UtdanningsmulighetCampus.campus`, `.organisasjon`, …) nulls out with a `DataFetchingException`. Any `@splitQuery` / `@reference` whose join key uses a converter-backed or non-`varchar` domain column is affected; keys on plain `Integer`/`varchar` columns are unaffected, which is why the defect is invisible on the Sakila fixtures.

## Root cause

There are two `VALUES (idx, …)` builders in the generator and they disagree on cell typing:

- **Lookup-input path (correct):** `ValuesJoinRowBuilder.cellsCode` (`util/ValuesJoinRowBuilder.java:133-144`) emits each cell as `DSL.val(value, <tableLocal>.<COL>.getDataType())`, so jOOQ binds through the column's registered `Converter`/`DataType` and renders a correctly-typed JDBC bind (no SQL `CAST`). This is the same `DSL.val(v, col.getDataType())` idiom used pervasively elsewhere (`TypeFetcherGenerator`, `LookupValuesJoinEmitter`, `ConnectionHelperClassGenerator`, …).
- **Parent-input path (buggy):** `SplitRowsMethodEmitter` bypasses that idiom and builds cells from the raw key `Field`, typed by `columnClass()`.

## Fix sketch

Route the parent-input `VALUES` cells — and the `parentInput.field(...)` lookups in the JOIN predicate — through the join key column's `getDataType()` rather than `columnClass()` / `String.class`, mirroring `ValuesJoinRowBuilder`. The parent FK columns (`sourceKey.columns()`) carry the same domain+converter `DataType` and are in key order, so binding via `Tables.<PARENT>.<COL>.getDataType()` is order-safe.

Sites to change (the buggy shape is duplicated across both):

- `SplitRowsMethodEmitter.emitParentInputAndFkChain` (VALUES row cells + `Row<N>`/`Record<N>` type-args) feeding `buildSingleMethod` / `buildListMethod` / `buildConnectionMethod`, plus the field lookup in `emitFromBridgeAndParentJoin`.
- `SplitRowsMethodEmitter.emitRecordTableMethodBody` (`:604-666`) — the `@tableMethod` variant carries the same copy.

Threading note: `emitParentInputAndFkChain` currently receives only `SourceKey` (columns as `ColumnRef`, which lack a constants-class reference); it needs the parent `TableRef` (or the resolved target-column `DataType` handles) plumbed in to emit `<constants>.<TABLE>.<COL>.getDataType()`. Consider whether reusing `ValuesJoinRowBuilder` directly (it already encapsulates the typed-row array, arity cap, and alias-args) is cleaner than patching the bespoke loop.

## Regression coverage

The Sakila fixtures have no converter-backed / numeric-domain join key, so this slipped through. Add a fixture column whose jOOQ `DataType` carries a `Converter` (or a numeric domain) as a `@splitQuery`/`@reference` join key, and assert the generated rows-method binds the parent-input cell via `getDataType()` (and that the emitted SQL type-checks). See [composite-key-row2-source-row-coverage](composite-key-row2-source-row-coverage.md) for the adjacent Row2 source-key coverage.

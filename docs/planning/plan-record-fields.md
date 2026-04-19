# Record-field emission

> **Status:** Draft
>
> Enables `TypeFetcherGenerator` to produce fetcher classes for `@record`-annotated parent types and emit working code for `PropertyField`, `RecordField`, and `RecordTableField`. All six field variants on `@record` parents are currently unreachable — `TypeFetcherGenerator.generate()` filters out `ResultType` parents entirely, so no `*Fetchers` class is produced for them.

## Current state

- `TypeFetcherGenerator.generate()` (lines 65–68) filters to `TableType`, `NodeType`, `RootType`. `ResultType` parents are silently skipped.
- `PropertyField`, `RecordField`, `RecordTableField`, `RecordLookupTableField` are in `NOT_IMPLEMENTED_REASONS` (lines 205–231). `ServiceTableField` and `ServiceRecordField` on `@record` parents are classified correctly by `FieldBuilder.classifyChildFieldOnResultType()` but never reach the generator for the same reason.
- `TypeClassGenerator` produces no output for `ResultType` — no `$fields()` equivalent needed; result-mapped types carry data in the backing Java object, not in a SQL SELECT list.
- `RecordTableField` and `RecordLookupTableField` do not implement `BatchKeyField` — the batch key derivation from the parent backing object is the primary design problem this plan solves (for `RecordTableField`).
- No pipeline, compilation, or execution tests exist for any record-parent field variant.

## ResultType backing-object access patterns

The four `ResultType` variants require different accessor strategies. The generator reads the parent's resolved `ResultType` from `schema.type(f.parentTypeName())`.

| Variant | Backing type in generated code | Property access for `columnName` |
|---|---|---|
| `JooqTableRecordType(table)` | `TableRecord<?>` cast to fqClassName | `source.get(TABLE.COL)` — typed, uses `table`'s jOOQ table constant |
| `JooqRecordType` | `Record<?>` | `source.get("col")` — untyped string key |
| `JavaRecordType(fqClassName)` | Java `record` cast to fqClassName | `source.colName()` — component accessor, lower-camelCase of `columnName` |
| `PojoResultType(fqClassName non-null)` | POJO cast to fqClassName | `source.getColName()` — bean getter, lower-camelCase of `columnName` |
| `PojoResultType(fqClassName null)` | `Object` / `Map<?,?>` | `((Map<?,?>) source).get("columnName")` |

`columnName` → accessor name: lower-camelCase of the SQL column name, matching `ColumnField`'s existing convention.

---

## Phase 1 — `TypeFetcherGenerator` for `ResultType` + `PropertyField` + `RecordField`

**Goal.** Unblock the two simplest variants. No DataLoader, no SQL. The generator begins producing `*Fetchers` classes for `@record` types and wires property-access DataFetchers for scalar and nested-record fields.

### Changes

**`TypeFetcherGenerator.generate()`** — add `ResultType` to the parent-type filter alongside `TableType`, `NodeType`. The generated class uses the same structural shell (per-field methods + `wiring()`) but emits no `$fields` static method (no SQL projection; table-backed types only).

**`PropertyField` wiring.** A wiring entry whose DataFetcher casts `env.getSource()` to the backing type and reads `columnName` using the accessor pattern from the table above. The `ResultType` variant is read from the schema at generation time to select the right pattern. Move `PropertyField` to `IMPLEMENTED_LEAVES`.

**`RecordField` wiring.** Same backing-object access as `PropertyField`. When `returnType` is `ResultReturnType` (nested `@record`), the DataFetcher reads the nested object from the source and GraphQL-Java recurses into the nested type's own wiring. When `returnType` is `ScalarReturnType`, identical to `PropertyField`. Move `RecordField` to `IMPLEMENTED_LEAVES`.

**Pipeline tests.** `RecordFieldPipelineTest` (new): SDL with `@record` type produces a `*Fetchers` class; `PropertyField` and `RecordField` each produce a wiring entry. Structural only.

**Compile gate.** `mvn compile -pl :graphitron-rewrite-test-spec -Plocal-db` — passes trivially since the test-spec schema has no `@record` types until Phase 1 C4.

---

## Phase 2 — `RecordTableField`: `BatchKey` model + DataLoader emission

**Goal.** Emit a working DataLoader fetcher + rows method for `RecordTableField`. This is the primary use case for `@record` parents: a service or parent query returns records, each record has a `@table` child field that batches per record.

### BatchKey model change

Add `BatchKey batchKey()` to `ChildField.RecordTableField`, making it implement `BatchKeyField`. The builder (`FieldBuilder.classifyChildFieldOnResultType`) derives the batch key from the join path's first `FkJoin` step:

- `JooqTableRecordType` → `BatchKey.RowKeyed(fkJoin.sourceColumns)`. Key extraction uses `source.get(TABLE.FK_COL)` via the `table` component of the `ResultType`.
- `JooqRecordType`, `JavaRecordType`, `PojoResultType(non-null class)` → `BatchKey.RecordKeyed(fkJoin.sourceColumns)`. Key extraction uses the backing-object accessor pattern above.
- `PojoResultType(null class)` → `UnclassifiedField`; cannot derive batch key without a known backing class.

**`RowKeyed` vs `RecordKeyed` distinction.** Both carry `List<ColumnRef>`. `RowKeyed` = extract from a jOOQ table row via table-constant field accessor (`source.get(TABLE.COL)`). `RecordKeyed` = extract from a Java object via the backing-type accessor pattern.

### Key extraction helper

`GeneratorUtils` gains `buildRecordKeyExtraction(BatchKey.RecordKeyed bk, ResultType parentType)`, emitting the `<KeyType> key = ...` declaration before `loader.load(key, env)`. Parallels the existing `buildKeyExtraction(BatchKey.RowKeyed bk, TableRef parentTable)` for table-backed parents. Both helpers are called from the same fetcher-building path; the dispatch is on the `BatchKey` variant.

### Fetcher and rows method shape

Fetcher shape is identical to `SplitTableField` (`buildSplitQueryDataFetcher`): DataLoader-registering, `buildDataLoaderName()`, explicitly-typed batch lambda, `loader.load(key, env)`. The only difference is the key extraction line.

Rows method shape is identical to `SplitRowsMethodEmitter`: flat batched SELECT keyed on a VALUES-derived parent table, idx-scattered via `scatterByIdx`. `JoinPathEmitter`, `ArgCallEmitter`, and the `__idx__` column pattern are reused verbatim. `ConditionJoin` in the path → runtime stub (same as Phase 2b). Empty-keys short-circuit before touching DSL.

Move `RecordTableField` to `IMPLEMENTED_LEAVES`.

### Execution tests (C4)

Add a `FilmResult @record` type backed by `FilmRecord` (`JooqTableRecordType`), a `Inventory` child field, seed data, and assertions:
- Single parent returns its children.
- Multiple parents scatter correctly (N parents → 1 SQL round-trip — assert via query counter).
- Empty batch short-circuits.

---

## Phase 3 — `RecordLookupTableField`

**Prerequisite.** The `BatchKey.ObjectBased` generator path decision (roadmap Backlog) must be resolved. `RecordLookupTableField` already implements `LookupField` (carries `LookupMapping`); it needs the same `BatchKey` addition as Phase 2 and the same key-extraction infrastructure.

The emission adds the lookup-input VALUES join from `LookupValuesJoinEmitter.buildChildInputRowsMethod` (Phase 2a pattern) on top of the Phase 2 rows method shape. Phase 2b's typed `Row<N+1>` / `Table<Record<N+1>>` convention applies to the lookup side.

---

## Non-goals

- **`ServiceTableField` rows method** — stub on both table-backed and record-backed parents; separate plan.
- **`ServiceRecordField`** — service returning non-table type; separate plan.
- **`@record`-parent interface/union fields** — not classified in the current model.
- **`PojoResultType(null class)` for `RecordTableField`** — deliberately `UnclassifiedField`; cannot batch without a known type.

## Test strategy

| Tier | What is verified |
|---|---|
| Pipeline | `PropertyField` → wiring entry exists per `ResultType` variant (accessor style structural, not body). `RecordTableField` → fetcher + rows method signatures correct. |
| Compile | `mvn compile -pl :graphitron-rewrite-test-spec -Plocal-db` — catches wrong accessor types, missing imports. |
| Execution | `JooqTableRecordType` → `RecordTableField` child batches correctly; N parents → 1 SQL round-trip; scatter correct. |

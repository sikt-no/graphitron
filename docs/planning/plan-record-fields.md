# Record-field emission

> **Status:** Done — Phase 1
>
> Enables `TypeFetcherGenerator` to produce fetcher classes for `@record`-annotated parent types and emit working code for `PropertyField`, `RecordField`, and `RecordTableField`. All six field variants on `@record` parents are currently unreachable — `TypeFetcherGenerator.generate()` filters out `ResultType` parents entirely, so no `*Fetchers` class is produced for them.

## Current state

- `TypeFetcherGenerator.generate()` (lines 65–68) filters to `TableType`, `NodeType`, `RootType`. `ResultType` parents are silently skipped — no `*Fetchers` class is produced.
- `PropertyField`, `RecordField`, `RecordTableField`, `RecordLookupTableField` are in `NOT_IMPLEMENTED_REASONS` (lines 205–231). `ServiceTableField` and `ServiceRecordField` on `@record` parents are classified correctly by `FieldBuilder.classifyChildFieldOnResultType()` but never reach the generator for the same reason.
- `TypeClassGenerator` produces no output for `ResultType` — correct by design; result-mapped types carry data in the backing Java object, not in a SQL SELECT list.
- `RecordTableField` does not implement `BatchKeyField` — batch key derivation from the parent backing object is the one non-trivial piece this plan adds.
- No pipeline, compilation, or execution tests exist for any record-parent field variant.

## ResultType backing-object access patterns

The four `ResultType` variants require different accessor strategies. The generator reads the parent's resolved `ResultType` from `schema.type(f.parentTypeName())`.

| Variant | Backing type | Property access for `columnName` |
|---|---|---|
| `JooqTableRecordType(table)` | jOOQ `TableRecord<?>` | `source.get(TABLE.COL)` — typed, uses the `table` component's jOOQ table constant |
| `JooqRecordType` | jOOQ `Record<?>` | `source.get("col")` — untyped string key |
| `JavaRecordType(fqClassName)` | Java `record` | `source.colName()` — component accessor, lower-camelCase of `columnName` |
| `PojoResultType(fqClassName non-null)` | POJO | `source.getColName()` — bean getter, lower-camelCase of `columnName` |
| `PojoResultType(fqClassName null)` | untyped | `((Map<?,?>) source).get("columnName")` |

`columnName` → accessor name: lower-camelCase of the SQL column name, matching `ColumnField`'s convention.

## Phase 1 — all variants except `RecordLookupTableField`

Four sequential commits. No gates between them — each adds the next layer of complexity within the same generator bootstrap.

### C1 — `TypeFetcherGenerator` for `ResultType` + `PropertyField` + `RecordField`

**`TypeFetcherGenerator.generate()`** — add `ResultType` to the parent-type filter alongside `TableType`, `NodeType`. The generated class uses the same structural shell (`wiring()` + per-field methods) but emits no `$fields` static method (result-mapped types have no SQL projection).

**`PropertyField` wiring.** A wiring entry whose DataFetcher casts `env.getSource()` to the backing type and reads `columnName` using the accessor pattern above. Move to `IMPLEMENTED_LEAVES`.

**`RecordField` wiring.** Same backing-object access. When `returnType` is `ResultReturnType` (nested `@record`), reads the nested object from the source; GraphQL-Java recurses into the nested type's own wiring. Move to `IMPLEMENTED_LEAVES`.

Pipeline test: SDL with `@record` type → `PropertyField` and `RecordField` each produce a wiring entry. Structural only.

### C2 — `RecordTableField`: `BatchKey` model + DataLoader

**Model change.** Add `BatchKey batchKey()` to `ChildField.RecordTableField`, implementing `BatchKeyField`. Builder (`FieldBuilder.classifyChildFieldOnResultType`) derives the batch key from the join path's first `FkJoin` step:

- `JooqTableRecordType` → `BatchKey.RowKeyed(fkJoin.sourceColumns)` — extraction uses `source.get(TABLE.FK_COL)` via the `table` component.
- `JooqRecordType`, `JavaRecordType`, `PojoResultType(non-null class)` → `BatchKey.RecordKeyed(fkJoin.sourceColumns)` — extraction uses the backing-type accessor pattern above.
- `PojoResultType(null class)` → `UnclassifiedField`; cannot batch without a known backing class.

**`RowKeyed` vs `RecordKeyed` distinction.** Both carry `List<ColumnRef>`. `RowKeyed` = extract from a jOOQ table row via table-constant accessor. `RecordKeyed` = extract from a Java object via the backing-type accessor pattern.

**`GeneratorUtils.buildRecordKeyExtraction`** — new helper, ~30 lines, parallels `buildKeyExtraction(BatchKey.RowKeyed, TableRef)`. Emits the `<KeyType> key = ...` declaration before `loader.load(key, env)`, using the `ResultType` variant to choose the right accessor.

**Fetcher.** Identical shape to `buildSplitQueryDataFetcher` — DataLoader-registering, `buildDataLoaderName()`, explicitly-typed batch lambda. Only difference from `SplitTableField`: key extraction calls `buildRecordKeyExtraction` instead of `buildKeyExtraction`.

**Rows method.** Delegates to `SplitRowsMethodEmitter` unchanged — flat batched SELECT, VALUES-derived parent table, idx-scatter, `ConditionJoin` → runtime stub, empty-keys short-circuit. Move `RecordTableField` to `IMPLEMENTED_LEAVES`.

Pipeline test: `RecordTableField` → correct fetcher + rows method signatures.

### C3 — Compile gate

`mvn compile -pl :graphitron-rewrite-test-spec -Plocal-db` — no `@record` types in the schema yet, passes trivially. Confirms nothing broken in the existing suite.

### C4 — Schema fixture + execution tests

Add a `FilmResult @record` type backed by `FilmRecord` (`JooqTableRecordType`) with a `Language` child field. Execution assertions:

- Single parent returns its children.
- Multiple parents scatter correctly — N parents → 1 SQL round-trip (assert via query counter).
- Empty batch short-circuits.

## Phase 2 — `RecordLookupTableField`

**Prerequisite.** The `BatchKey.ObjectBased` generator path decision (roadmap Backlog) must be resolved first. `RecordLookupTableField` already implements `LookupField`; it needs the same `BatchKey` addition as C2 above and the same key-extraction infrastructure.

Emission adds the lookup-input VALUES join from `LookupValuesJoinEmitter.buildChildInputRowsMethod` on top of C2's rows method. Phase 2b's typed `Row<N+1>` / `Table<Record<N+1>>` convention applies to the lookup side.

## Non-goals

- **`ServiceTableField` rows method** — separate plan; not a record-field classification concern.
- **`ServiceRecordField`** — separate plan.
- **`PojoResultType(null class)` for `RecordTableField`** — deliberately `UnclassifiedField`; cannot batch without a known type.
- **`@record`-parent interface/union fields** — not classified in the current model.

## As built — divergences from the plan above

- **`BatchKey.RowKeyed` for all typed ResultType variants, not a `RowKeyed`/`RecordKeyed` split.** The plan's table mapped `JooqTableRecordType→RowKeyed` and the other typed variants→`RecordKeyed` (`record.into(TABLE.COL, ...)`). That would not compile on a Java record or POJO — `into(Field...)` is a jOOQ `Record` method. `FieldBuilder.deriveBatchKeyForResultType` picks `RowKeyed` for all typed variants; the accessor dispatch lives in `GeneratorUtils.buildRecordKeyExtraction`, which branches on `ResultType` and emits the right accessor idiom per variant (table-constant `get(TABLE.COL)`, string `get("sql_name")`, record component, or bean getter).
- **Untyped `DSL.field("name")` wiring for `JooqTableRecordType` PropertyField/RecordField.** Plan said typed `TABLE.COL` access via the `table` component. Implementation (`TypeFetcherGenerator.buildPropertyOrRecordFetcherEntry`) uses `new ColumnFetcher<>(DSL.field(columnName))` for both `JooqTableRecordType` and `JooqRecordType`. Works — the jOOQ `Record.get(Field)` fallback resolves by name — but drops the available type safety for the table-backed variant. Revisit in a follow-up if profiling or runtime typos motivate it.
- **`ConstructorField` emission was part of Phase 1.** Not called out in the plan — surfaced while wiring execution tests. `@table` parent → `@record` child navigation is a pass-through: `TypeFetcherGenerator` wires a `env -> env.getSource()` DataFetcher. The child's own `*Fetchers` class then handles PropertyField/RecordTableField resolution off the parent's Record. Classification lives in `FieldBuilder.classifyChildFieldOnTableType` under the `elementType instanceof ResultType` branch.
- **Empty-batch execution test not added.** Plan C4 listed three execution tests; the empty-batch short-circuit is exercised by the shared `SplitRowsMethodEmitter.buildListMethod` path (argres 2b has the Split* version of this test), not directly for `RecordTableField`. A PropertyField test replaced it, covering the Record-pass-through read path.
- **RecordTableField unsupported shapes validated at build time.** Single-cardinality, `ConditionJoin` paths, and empty `joinPath` are surfaced via `SplitRowsMethodEmitter.unsupportedReason(RecordTableField)` wired into `GraphitronSchemaValidator.validateVariantIsImplemented`, matching the argres 2b pattern for Split*. The runtime stubs still exist as a belt-and-suspenders fallback.

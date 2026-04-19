# argres Phase 2b — Split(Lookup)TableField DataLoader rows-method emission

> **Status:** Pending Review
>
> Fills in the DataLoader rows-method bodies for `ChildField.SplitTableField` and `ChildField.SplitLookupTableField`. Both were compile-only skeletons (`IMPLEMENTED_LEAVES` but runtime-throwing); Phase 2b makes `IMPLEMENTED_LEAVES` mean "emits working code".

## What shipped

- **`SplitRowsMethodEmitter`** — flat batched SELECT keyed on a VALUES-derived parent table; idx-scatter via `scatterByIdx`. Reuses `JoinPathEmitter` (FK chain), `ArgCallEmitter` (`@condition` filters), and `LookupValuesJoinEmitter.buildChildInputRowsMethod` (`@lookupKey` VALUES).
- **`buildSplitQueryDataFetcher`** rewritten — DataLoader-registering shape. Uses `buildDataLoaderName()` (tenant prefix via `getTenantId(env)` + path-key join), not the deprecated `getDataLoaderName`.
- **C3** — classifier rejects `FieldWrapper.Connection` on both `Split*` variants with a diagnostic explaining the window-function deferral.
- **C5 (folded in)** — lookup-input VALUES retypes from `RowN[]`/`Table<?>` to `Row<N+1>`/`Table<Record<N+1>>`, matching the parent-input typing from C1.

## Patterns established (Phase 2c inherits these)

- **Rows-method signature: 2-param.** `(List<RowN<…>> keys, DataFetchingEnvironment env)` — no `SelectedField` parameter. `DataFetchingFieldSelectionSet` is read via `env.getSelectionSet()` directly.
- **Key unpacking: codegen-time arity via `fieldJ()`.** `keys.get(i)` is cast to the concrete `RowN<…>` (known at codegen time from `BatchKey.RowKeyed.keyColumns().size()`); cells are read via `k.field1()`…`k.fieldN()` (not `valueJ()` — those live on `RecordN`, not `RowN`).
- **Idx-scatter.** `parentInput` carries a leading `__idx__` column (INT). The flat SELECT projects it as `parentInput.field(0, Integer.class).as("__idx__")`. `scatterByIdx` groups by that column into a positional `List<List<Record>>`. The `__idx__` value is never in the GraphQL selection set and is not fetched by any DataFetcher.
- **Empty-input short-circuit.** `keys.isEmpty()` before touching DSL. For SplitLookup, `lookupRows.length == 0` returns `emptyScatter(keys.size())`.
- **VALUES + explicit ON** (not USING). Junction tables re-expose FK column names that collide with target-table column names; USING would be ambiguous. Phase 2a's USING→ON lesson applies identically here.
- **DataLoader factory: `newDataLoader` with explicitly-typed lambda.** Target-typed inference picks `List<Object>`, breaking the rows-method call. Both lambda parameters need explicit types.

## Not implemented

- **Single-cardinality `Split*` fields** — runtime stub (`buildRuntimeStub`). `scatterFirstByIdx` (one `Record` per parent key, `null` for no-match) does not exist. This is not documented in the plan's Non-goals and should be.
- **`ConditionJoin` steps in FK path** — runtime stub, same as G5/Phase 2a.

## Review blockers (open before Done)

1. **N-parents → 1-SQL-round-trip assertion missing.** The execution tests verify scatter correctness but do not assert that multiple parents produce a single SQL batch. This is the primary proof that DataLoader batching works. Add a query-count assertion to `splitTableField_multipleParents_scatterPerParent`.
2. **Input-order preservation not tested for `SplitTableField`.** Only tested for the inline lookup path. Add a fixture with parents in non-identity order.
3. **`AS_CONNECTION_SPLIT_LOOKUP_REJECTED` tests fewer message substrings than `AS_CONNECTION_SPLIT_REJECTED`.** Messages are identical in `FieldBuilder`; the test should be symmetric.

## Non-goals

- **`RecordLookupTableField` (Phase 2c)** — blocked on the `BatchKey.ObjectBased` decision (roadmap Backlog).
- **`ServiceTableField` rows-method body** — service methods, not SQL; separate plan.
- **Pagination on `Split*`** — deferred; classifier rejects `@asConnection` in C3.
- **Union/interface split fields** — different classification path; separate plan.

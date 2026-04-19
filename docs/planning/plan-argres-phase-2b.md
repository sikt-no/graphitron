# argres Phase 2b — Split(Lookup)TableField DataLoader rows-method emission

> **Status:** Pending Review
>
> Fills in the DataLoader rows-method bodies for `ChildField.SplitTableField` and `ChildField.SplitLookupTableField`. Both were compile-only skeletons (`IMPLEMENTED_LEAVES` but runtime-throwing); Phase 2b makes `IMPLEMENTED_LEAVES` mean "emits working code".

## Patterns (Phase 2c inherits these)

- **Rows-method signature: 2-param.** `(List<RowN<…>> keys, DataFetchingEnvironment env)` — no `SelectedField` parameter. `DataFetchingFieldSelectionSet` is read via `env.getSelectionSet()` directly.
- **Key unpacking: codegen-time arity via `fieldJ()`.** `keys.get(i)` is cast to the concrete `RowN<…>` (known at codegen time from `BatchKey.RowKeyed.keyColumns().size()`); cells are read via `k.field1()`…`k.fieldN()` (not `valueJ()` — those live on `RecordN`, not `RowN`).
- **Idx-scatter.** `parentInput` carries a leading `__idx__` column (INT). The flat SELECT projects it as `parentInput.field(0, Integer.class).as("__idx__")`. `scatterByIdx` groups by that column into a positional `List<List<Record>>`. The `__idx__` value is never in the GraphQL selection set and is not fetched by any DataFetcher.
- **Empty-input short-circuit.** `keys.isEmpty()` before touching DSL. For SplitLookup, `lookupRows.length == 0` returns `emptyScatter(keys.size())`.
- **VALUES + explicit ON** (not USING). Junction tables re-expose FK column names that collide with target-table column names; USING would be ambiguous. Phase 2a's USING→ON lesson applies identically here.
- **DataLoader factory: `newDataLoader` with explicitly-typed lambda.** Target-typed inference picks `List<Object>`, breaking the rows-method call. Both lambda parameters need explicit types.

## Not implemented — rejected at validate time

The three shapes below were runtime-throwing stubs; review surfaced them through the validator
so build-time now fails instead of request-time. `SplitRowsMethodEmitter.unsupportedReason`
is the single source of truth — both the emitter (runtime stub) and
`GraphitronSchemaValidator.validateVariantIsImplemented` (build-time error) call it.

- **Single-cardinality `Split*` fields.** `scatterFirstByIdx` (one `Record` per parent key,
  `null` for no-match) does not exist. Validator rejects these at build time.
- **`ConditionJoin` steps in FK path.** Blocked on classification-vocabulary item 5 resolving
  condition-method target tables. Validator rejects.
- **Path-less `Split*` (empty `joinPath`).** A `@splitQuery` field without `@reference` today.
  Validator rejects.

## Non-goals

- **`RecordLookupTableField` (Phase 2c)** — blocked on the `BatchKey.ObjectBased` decision (roadmap Backlog).
- **`ServiceTableField` rows-method body** — service methods, not SQL; separate plan.
- **Pagination on `Split*`** — deferred; classifier rejects `@asConnection` in C3.
- **Union/interface split fields** — different classification path; separate plan.

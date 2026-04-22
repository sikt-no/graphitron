# Single-cardinality `@splitQuery` support

> **Status:** Spec

## Overview

Lift the runtime-stub error that fires on single-cardinality `@splitQuery` fields (e.g. `Vurderingsmelding.person: Person @splitQuery` with `vurderingsmelding.person_id → person.person_id`). Today the emitter rejects the shape at build time with "Single-cardinality @splitQuery … not yet supported; list cardinality is the Phase 2b C1 scope". After this plan, the same shape compiles and executes end-to-end.

**Approach:** key the DataLoader by the *parent's FK column value* rather than by parent PK. For parent-holds-FK fields, that value is already sitting on the parent record at projection time; the rows method becomes structurally symmetrical to the list-cardinality case. No extra joins, no parent-table round-trip through the rows SQL.

## Current state

`SplitRowsMethodEmitter.unsupportedReason(ChildField.SplitTableField)` at `SplitRowsMethodEmitter.java:148-158` rejects `!wrapper.isList()` outright; the sibling in `unsupportedReason(ChildField.SplitLookupTableField)` at `:187-203` does the same for `@splitQuery @lookupKey` single-cardinality. Both branches produce a runtime-throwing method whose reason string is surfaced by the validator as a build error.

The list-cardinality flow (shipped in `34359b4`, Argument-resolution Phase 2b) is:

- **BatchKey:** hardcoded to `new BatchKey.RowKeyed(parentTableType.table().primaryKeyColumns())` at `FieldBuilder.java:250` — always parent PK for the `@splitQuery` arm on a `@table` parent.
- **Parent key extraction:** `GeneratorUtils.buildKeyExtraction` at `GeneratorUtils.java:198-231` reads those columns from the parent record via `(($T) env.getSource()).get($T.$L.$L)`. Relies on the parent projection carrying the key columns.
- **DataLoader:** registered per `(type, field)` via `env.getDataLoaderRegistry().computeIfAbsent(name, …)`. Each invocation calls `loader.load(key, env)`; the batch lambda assembles `keys` and picks one DFE for `$fields` projection.
- **Rows method:** `FROM terminal .join(parentInput).on(firstHop.sourceCol = parentInput.pkCol)` — `parentInput` is a VALUES table of `(idx, parent_pk_cols…)`. Returns `List<List<Record>>` scattered by `__idx__` via `scatterByIdx`.

The parent-holds-FK case doesn't fit this plumbing because `firstHop.sourceColumns()` sit on the *parent* table, not the *terminal* table, so there's no column to join `parentInput` against on the terminal side.

## Desired end state

Parent-holds-FK `@splitQuery` fields classify and emit end-to-end.

- **Classifier** detects parent-holds-FK by comparing `firstHop.sourceTable().tableName()` to the parent-table name and derives a `BatchKey.RowKeyed(firstHop.sourceColumns())` — the FK column(s) on the parent — instead of parent PK. Child-holds-FK (list cardinality) keeps parent-PK keying unchanged.
- **Parent projection** must carry the BatchKey columns so `buildKeyExtraction` can read them off the parent record. `TypeClassGenerator.$fields` today is purely selection-driven — it projects a column only when the GraphQL query names the corresponding field (`emitSelectionSwitch` at `TypeClassGenerator.java:209-257`; the sole exception is `NodeIdField`, which projects its node-key columns at `:227-233`). The list-cardinality `@splitQuery` path works today **only because fixtures happen to select the parent PK** (see `GraphQLQueryTest.java:684` — `{ languageByKey(language_id: [1]) { languageId films { filmId } } }` explicitly requests `languageId`); drop that and `buildKeyExtraction` reads an absent column. This plan fixes that latent gap as a side effect: extend `$fields` with a required-projection set containing the BatchKey columns of every `@splitQuery` / DataLoader-backed child, unioned with the selection-derived columns. After this change, list-cardinality (child-holds-FK) becomes robust to PK-omitted selections, and single-cardinality (parent-holds-FK) starts working for the first time.
- **Rows method** becomes structurally identical to the list-cardinality case: `parentInput` is a VALUES table of `(idx, fk_col…)`, the JOIN is `terminal.pk = parentInput.fk_col`. No parent table in the FROM chain.
- **Single-cardinality return shape.** Rows method returns `List<Record>` indexed 1:1 with `keys` (one record per key, `null` when no match) instead of `List<List<Record>>`. New scatter helper `scatterSingleByIdx`; fetcher returns `CompletableFuture<Record>` (reusing the shape already in place for `ServiceTableField` single-return).
- **DataLoader deduplication** transparently coalesces sibling parents that share an FK value into one batch slot — already what DataLoader does for any repeated key. Because the coalescing happens *within* one DataLoader instance (scoped to one `(type, field)` pair) and GraphQL selection-merging guarantees all invocations of a same-name field in one operation share arguments and selection set, the FK-value keying does not cross-contaminate across paths with different args or selections. Different parent types with their own `person` fields get their own fetchers, their own DataLoaders, and never merge.
- **Validator** deletes the `!isList` branches in both `SplitRowsMethodEmitter.unsupportedReason` overloads.

Verification: `Vurderingsmelding.person: Person @splitQuery` (or any parent-holds-FK single-FK pair) compiles and executes against the test-spec database; two sibling parents pointing at the same child produce one rows-method invocation for that key.

## What we're NOT doing

- **Multi-hop paths.** Scope is one-hop FK: first step `FkJoin` whose source or target table equals the parent. Two-hop junction paths on single cardinality (rare — would usually indicate a modelling mistake; `@splitQuery @lookupKey` covers the junction case via `LookupMapping`) stay stubbed. The `path.size() == 1` guard is explicit; longer paths continue to fall through to the CARDINALITY stub.
- **Merging `@splitQuery` with `@lookupKey` on single cardinality.** Single-cardinality `@splitQuery @lookupKey` remains stubbed. Note: the existing scalar-return rejection at `FieldBuilder.java:266-269` only fires on the `!hasSplitQuery && hasLookupKey` arm — the `hasSplitQuery && hasLookupKey` arm at `:251-260` has no cardinality check today. This plan adds a parallel rejection at the `hasSplitQuery && hasLookupKey` arm (see §1 below) so the single-cardinality `@splitQuery @lookupKey` case is caught at classifier time rather than falling through to a runtime stub in the emitter.
- **Condition-join paths.** `JoinPathEmitter.hasConditionJoin` still short-circuits to a stub for both list and single cardinality; classification-vocabulary item 5 owns that.
- **Rekeying list cardinality by FK value.** The existing list-cardinality child-holds-FK path keeps parent-PK keying. Switching it would break DataLoader scatter semantics (list-cardinality needs one slot per parent, not one slot per shared FK value).
- **Record-parent variants.** `RecordTableField` / `RecordLookupTableField` single-cardinality stubs at `SplitRowsMethodEmitter.java:224-235` and `:266-280` already use `firstHop.sourceColumns()` for the BatchKey (see `FieldBuilder.deriveBatchKeyForResultType` at `:1657-1666` — it picks source columns unconditionally). Unblocking them is a strictly smaller variant of this plan and can land in a follow-up — the emitter change here is reusable, but the record-parent projection wiring is separate (record-parent rows don't go through `TypeClassGenerator.$fields`).

## Implementation approach

### 1. Classifier — parent-holds-FK BatchKey + single-cardinality `@splitQuery @lookupKey` rejection

**File:** `graphitron-rewrite/src/main/java/no/sikt/graphitron/rewrite/FieldBuilder.java`

**1a. BatchKey derivation.** At `:250` the parent-backed `@splitQuery` arm hardcodes `parentTableType.table().primaryKeyColumns()` into the BatchKey. Replace with a small helper that inspects the first FkJoin hop:

```java
// Sibling of deriveBatchKeyForResultType at :1657 — that helper is for record parents
// and always uses fk.sourceColumns() because record parents never batch by parent PK.
// This helper is for @table parents on a DataLoader-backed field: the parent-holds-FK
// case mirrors the record-parent choice, but the child-holds-FK case must fall back
// to parent PK (one key per parent slot, not per shared FK value).
private static BatchKey deriveSplitQueryBatchKey(TableRef parentTable, List<JoinStep> path) {
    if (!path.isEmpty() && path.get(0) instanceof JoinStep.FkJoin fk
            && fk.sourceTable().tableName().equalsIgnoreCase(parentTable.tableName())) {
        return new BatchKey.RowKeyed(fk.sourceColumns());  // parent-holds-FK: key by FK column
    }
    return new BatchKey.RowKeyed(parentTable.primaryKeyColumns());  // child-holds-FK: key by parent PK
}
```

Call from the two existing `parentBatchKey` construction sites (the `@splitQuery` and `@splitQuery @lookupKey` arms around `:250`). Pass the resolved `referencePath.elements()` — already in scope.

Case-insensitive `equalsIgnoreCase` matches how `JooqCatalog.findForeignKeysBetweenTables` compares names (see `BuildContext.parsePath`'s inference branch from the preceding plan). SDL-vs-catalog casing drift is benign.

**1b. Single-cardinality `@splitQuery @lookupKey` rejection.** Add a cardinality check on the `hasSplitQuery && hasLookupKey` arm at `:251`, mirroring the existing `!hasSplitQuery && hasLookupKey` check at `:266-269`:

```java
if (hasSplitQuery && hasLookupKey) {
    if (returnType.wrapper() instanceof FieldWrapper.Connection) { /* existing */ }
    if (returnType.wrapper() instanceof FieldWrapper.Single) {
        return new UnclassifiedField(parentTypeName, name, location, fieldDef,
            "Single-cardinality @splitQuery @lookupKey is not supported; pass a list-returning field or drop @lookupKey");
    }
    // ...
}
```

This keeps cardinality validation at classifier time instead of letting single-cardinality `@splitQuery @lookupKey` flow through to the now-deleted `!isList` branch in `SplitRowsMethodEmitter.unsupportedReason(ChildField.SplitLookupTableField)`. Without this rejection, §3's removal of both `!isList` branches would let the classifier produce a `SplitLookupTableField` with single cardinality that the emitter has no body for.

### 2. Parent projection — always project BatchKey columns

**File:** `graphitron-rewrite/src/main/java/no/sikt/graphitron/rewrite/generators/TypeClassGenerator.java`

`$fields` today is purely selection-driven (`emitSelectionSwitch` at `:209-257`): a column lands in the SELECT only when the GraphQL selection names the corresponding field. The sole existing exception is `NodeIdField`, which projects its node-key columns regardless of selection (`:227-233`). We extend that pattern to BatchKey columns.

Concrete change: before emitting the switch, compute a "required columns" set = union of `field.batchKey().keyColumns()` for every DataLoader-backed child (`SplitTableField`, `SplitLookupTableField`) of this type. After the selection-driven switch writes to `fields`, append any required column not already added (dedup by jOOQ `Field` identity — the jOOQ table field reference `$T.<table>.<col>` is canonical per column).

Scope clarification vs the list-cardinality status quo:

- **Parent-holds-FK single-cardinality** (this plan's primary case): BatchKey columns are the parent's FK columns. Without this extension, they'd never land in the SELECT because they're not selection-reachable from the shared nested projection — the single-cardinality rows method needs them. Required.
- **Child-holds-FK list-cardinality** (existing Phase 2b path): BatchKey columns are the parent PK. Works today only when the client selects a PK-mapped field; this extension closes that latent gap. Not strictly in scope, but fixing it is a one-line consequence of the implementation and removing the coincidence-dependency is strictly better.

Emit order: appended after the main switch so the generated SELECT reads "selected first, then required-but-unselected". Matters only for debuggability — jOOQ doesn't care.

### 3. Emitter — rows method for single cardinality

**File:** `graphitron-rewrite/src/main/java/no/sikt/graphitron/rewrite/generators/SplitRowsMethodEmitter.java`

Delete the `!isList` branches in both `unsupportedReason` overloads (`:148-158`, `:187-203`). Split `buildListMethod` into a shared core + two return-shape tails, or introduce a sibling `buildSingleMethod`. The SELECT / FROM / JOIN construction is identical; the only differences are:

- **Return type:** `List<Record>` (single) vs `List<List<Record>>` (list).
- **Scatter helper call:** `scatterSingleByIdx(flat, keys.size())` (new) vs `scatterByIdx(flat, keys.size())`.
- **Empty-lookup short-circuit** (SplitLookupTableField only): single-cardinality returns a `List<Record>` of `null`s, not `List<List<Record>>` of empty lists. Needs an `emptySingleScatter(int)` helper or a parameter on the existing one.

New scatter helper sketch:

```java
private static List<Record> scatterSingleByIdx(Result<Record> flat, int keyCount) {
    Record[] out = new Record[keyCount];
    for (Record r : flat) {
        int idx = r.get(IDX_COLUMN, Integer.class);
        out[idx] = r;  // at most one row per idx by construction (single-cardinality contract)
    }
    return java.util.Arrays.asList(out);  // nulls preserved where no match
}
```

The "at most one row per idx" invariant holds because the terminal table's PK equals the FK value keyed by idx — so `terminal.pk = parentInput.fk_col` yields ≤1 terminal row per idx. Add a defensive `IllegalStateException` if a second assignment to the same idx happens (cheap runtime check).

### 4. Fetcher — scatter-helper emission gate

**File:** `graphitron-rewrite/src/main/java/no/sikt/graphitron/rewrite/generators/TypeFetcherGenerator.java`

`buildSplitQueryDataFetcher` (`TypeFetcherGenerator.java:1083-1124`) already branches on `tb.wrapper().isList()` — single cardinality already produces `DataLoader<KeyType, Record>` and returns `CompletableFuture<Record>`. The runtime stub at `SplitRowsMethodEmitter.java:528-543` already returns `List<Record>` for single cardinality. No signature changes needed on the fetcher or stub.

The only fetcher-side work is the scatter-helper emission gate: `buildScatterByIdxHelper` (`SplitRowsMethodEmitter.java:576`) and `buildEmptyScatterHelper` (`:555`) currently emit when any Split* field is present. Add a parallel gate that emits `scatterSingleByIdx` / `emptySingleScatter` when any Split* field in the class has single cardinality. A class with both list and single Split* fields emits all four helpers.

### 5. Validator / tests

- `SplitTableFieldValidationTest`, `SplitLookupTableFieldValidationTest` — the existing `SINGLE_CARDINALITY_STUB` cases become emittable. Replace with a positive assertion (one-hop FkJoin, non-empty joinPath, single-cardinality wrapper).
- `GraphitronSchemaBuilderTest` — add `SPLIT_TABLE_SINGLE_CARDINALITY` classification case using a real single-FK pair (positive), plus `SPLIT_LOOKUP_TABLE_SINGLE_CARDINALITY_REJECTED` (negative: asserts the new classifier-level rejection from §1b). `Customer.address: Address` is a good fit for the positive case (`customer.address_id → address.address_id`, single FK). Both cases should cover explicit `@reference(path: [...])` and absent-`@reference` inference, since the BatchKey derivation runs on the resolved `referencePath.elements()` regardless of how the path was produced.
- `SplitTableFieldPipelineTest` — add structural assertions for the fetcher + rows method shapes in the single-cardinality case (mirror the list-cardinality assertions, swap `List<List<Record>>` → `List<Record>` and `scatterByIdx` → `scatterSingleByIdx`).
- Execution test in `graphitron-rewrite-test-spec/src/test/java/.../GraphQLQueryTest.java` — add a `Customer.address` query and assert DataLoader deduplication (two customers sharing an address_id → one rows invocation for that key; use the existing JDBC round-trip counter pattern from the Language.films tests).
- Test-spec schema — add the new field: `type Customer @table(name: "customer") { ... address: Address @splitQuery }`. Inference picks up the single FK; no `@reference` needed.

## Success criteria

### Automated

- `mvn test -pl :graphitron-rewrite` passes.
- `mvn test -pl :graphitron-rewrite-test-spec -Plocal-db` passes with the new `Customer.address @splitQuery` (or equivalent) execution test.
- Two sibling parents pointing at the same child invoke the rows method exactly once for that key — assert via the existing JDBC round-trip counter pattern.
- Grepping `SplitRowsMethodEmitter.java` for `"not yet supported; list cardinality is the Phase 2b"` returns zero hits (both `SplitTableField` and `SplitLookupTableField` single-cardinality stubs deleted).

### Manual

- Running the generator against `sis-graphql-spec` (the schema that originally surfaced the `Vurderingsmelding.person` error) no longer fails on single-cardinality `@splitQuery` fields whose inferred path is a single parent-holds-FK hop. Fields whose single cardinality is a genuine modelling mistake (e.g. the FK is on the child side, making single cardinality unsatisfiable without an extra filter) are unaffected — they continue to classify correctly via the child-holds-FK branch but would fail at SQL execution if the child table returns multiple rows for a parent. Flag for a follow-on validator check, not in scope here.

## Open questions

1. **BatchKey keying strategy — Option A (FK value) vs Option B (parent PK + bridge join).** This plan commits to A. Option B — keep the BatchKey as parent PK and add the parent table to the FROM chain of the rows method so the bridging join `parent.pk = parentInput.pk … parent.fk_col = terminal.pk` lives inside SQL — preserves the existing emitter structure at the cost of one extra join per batch and no cross-parent dedup. A wins on runtime (fewer round trips when siblings share FKs) and on emitter symmetry (rows method shape matches list cardinality). B wins if we later want to bundle single-cardinality fields with complex parent-side filter conditions that are easier to emit as WHERE clauses on the parent table than to propagate into the DataLoader key. Default: A unless review surfaces a concrete B use case.

2. **What does the DataLoader pass as `env` when two loads for the same key arrive with different DFEs?** Not introduced by this plan — the same question applies to list cardinality today. The batch lambda picks `getKeyContextsList().get(0)`'s DFE for `$fields` projection. GraphQL selection-merging guarantees all DFEs for a same-name field in one operation share args and selection set, so picking any one is safe. Flag for the reviewer; if this turns out to be false under an edge case (fragments with different `@skip`/`@include`?), the scope expands to "merge selection sets across DFEs" and this plan would need to pause on it. **Note:** this plan *increases* cross-parent coalescing within one DataLoader slot — parents with distinct PKs but shared FK values now share a key slot, where they would have been separate slots under the list-cardinality keying. The "shared selection set and args" invariant is now load-bearing in both directions (same-slot dedup relied on it already; now more parents end up same-slot).

3. **`Customer.address` vs `Store.manager` as the test-spec fixture.** Both are single-cardinality parent-holds-FK pairs in the fixtures DB. `Store.manager_staff_id → staff.staff_id` is currently nullable in init.sql (the fixture seeds `NULL` for one row), which exercises the null-FK edge case in the DataLoader. `Customer.address_id` is `NOT NULL`, so a simpler happy path. Preference: seed both — `Customer.address` as the primary fixture, `Store.manager` as a follow-up case to ensure null-FK parents produce `null` at the GraphQL layer (not an exception). Manager wins the edge-case coverage.

4. **`deriveBatchKeyForResultType` consolidation.** `FieldBuilder.java:1657` already does the right thing for record parents (uses `fkJoin.sourceColumns()` unconditionally). The new `deriveSplitQueryBatchKey` helper diverges from it when child-holds-FK, because a `@splitQuery` DataLoader on a table parent needs the parent-side key for scatter, not the source column. Keep them separate rather than forcing one signature — the divergence is semantic, not accidental. Call out in the implementation comment.

## References

- Existing list-cardinality `@splitQuery` emitter: `graphitron-rewrite/src/main/java/no/sikt/graphitron/rewrite/generators/SplitRowsMethodEmitter.buildListMethod` (`:283-508`).
- Fetcher single-cardinality precedent: `TypeFetcherGenerator.buildServiceDataFetcher` (`:991-1030`) already branches on `tb.wrapper().isList()` and returns `CompletableFuture<Record>` for single.
- Parent key extraction: `GeneratorUtils.buildKeyExtraction` (`:198-231`) — reads BatchKey columns off `env.getSource()`; the parent projection must carry them.
- Implicit `@reference` path inference plan: `plan-implicit-reference-inference.md` (In Review) — delivers the FK-column metadata in `firstHop.sourceColumns()` / `firstHop.sourceTable()` for absent-`@reference` fields. Both plans stand on their own but compose: `Vurderingsmelding.person` specifically requires both inference (to avoid hand-writing `@reference`) and this plan (to emit the single-cardinality rows method).
- Original trigger: `sis-graphql-spec` production build failure on `Vurderingsmelding.person` (generator-schema.graphql:19906).

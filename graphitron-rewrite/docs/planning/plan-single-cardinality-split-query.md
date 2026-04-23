# Single-cardinality `@splitQuery` support

> **Status:** Done
>
> **Shipped** — §1a `deriveSplitQueryBatchKey` helper (cardinality-driven); §1b
> single-cardinality `@splitQuery @lookupKey` classifier rejection; §1c multi-hop
> single-cardinality `@splitQuery` classifier rejection; `JoinStep.FkJoin` docstring
> corrected. §2 `$fields` appends BatchKey columns of every Split* child on the type,
> deduped against the selection-driven switch output. §3 `SplitRowsMethodEmitter.buildSingleMethod`
> + `scatterSingleByIdx`; both `!isList` branches deleted from `unsupportedReason` overloads.
> §4 null-FK short-circuit in `buildSplitQueryDataFetcher` via the new
> `GeneratorUtils.buildKeyExtractionWithNullCheck`; scatter-helper emission gates split into
> list-shape (`scatterByIdx`) and single-shape (`scatterSingleByIdx`).
>
> §5 test coverage: `Customer.addressSplit` and `Store.manager` fixtures added to
> `graphitron-rewrite-test` (schema + `init.sql` null-FK seed for store_id=2);
> `GraphitronSchemaBuilderTest` gained `SPLIT_TABLE_SINGLE_CARDINALITY`,
> `IMPLICIT_REFERENCE_SPLIT_TABLE_SINGLE_CARDINALITY`,
> `SPLIT_LOOKUP_TABLE_SINGLE_CARDINALITY_REJECTED`, and
> `SPLIT_TABLE_MULTI_HOP_SINGLE_CARDINALITY_REJECTED` cases plus the existing
> `SPLIT_LOOKUP_TABLE_FIELD`/`IMPLICIT_REFERENCE_SPLIT_LOOKUP_TABLE` cases updated to
> list cardinality; `SplitTableFieldValidationTest` rewrote the single-cardinality cases
> to a positive `SINGLE_CARDINALITY_EMITTABLE`; `SplitTableFieldPipelineTest` gained
> three structural assertions (`_singleCardinality_producesFetcherAndRowsMethod…`,
> `_nullFkShortCircuitAppearsInFetcherBody`, `_mixedCardinality_bothScatterHelpersEmitted`);
> `ScatterSingleByIdxTest` (new) covers empty/full/gap/duplicate-idx via reflection on
> `CustomerFetchers.scatterSingleByIdx`; execution tests cover happy path, shared-FK
> dedup (2 round-trips for 5 customers), null-FK short-circuit (1 round-trip for a
> NULL-manager store), non-null happy path, and mixed NULL/non-NULL scatter alignment.
> README `@splitQuery` section gained a "Cardinality" paragraph with a single-cardinality
> example.
>
> Drive-by: fixed the path in `GeneratedSourcesLintTest` to include the `graphitron/`
> subdirectory the maven-plugin writes under — the test was broken on trunk from commit
> `f8df839` but never flagged because the assertion fired inside a non-existent directory.
>
> Results: `mvn test -pl :graphitron-rewrite` — 544 green; `mvn test -pl
> :graphitron-rewrite-test -Plocal-db` — 87 green. Shipped in one commit on top of
> the In Progress marker.

## Overview

Lift the runtime-stub error that fires on single-cardinality `@splitQuery` fields (e.g. `Vurderingsmelding.person: Person @splitQuery` with `vurderingsmelding.person_id → person.person_id`). Today the emitter rejects the shape at build time with "Single-cardinality @splitQuery … not yet supported; list cardinality is the Phase 2b C1 scope". After this plan, the same shape compiles and executes end-to-end.

**Approach:** key the DataLoader by the *parent's FK column value* rather than by parent PK. For parent-holds-FK fields, that value is already sitting on the parent record at projection time; the rows method becomes structurally symmetrical to the list-cardinality case. No extra joins, no parent-table round-trip through the rows SQL.

**Decision — FK-value keying (A) vs parent-PK + bridge-join (B).** This plan commits to A. Option B would keep the BatchKey as parent PK and add the parent table to the FROM chain of the rows method (`parent.pk = parentInput.pk AND parent.fk_col = terminal.pk`), preserving emitter structure at the cost of one extra join per batch and zero cross-parent dedup. A wins on runtime (fewer round-trips when siblings share FK values) and on emitter symmetry (rows-method shape matches list cardinality). B's only advantage — easier emission of parent-side WHERE filters — is hypothetical; no current requirement needs it, and if it arises, filters on the *parent* of a split-query field are already an anti-pattern since the parent's selection context doesn't reach the rows method. Revisit only if a concrete B use case surfaces.

**Dependencies and sequencing.** `plan-implicit-reference-inference.md` has landed (Done), so the trigger field `Vurderingsmelding.person` and the `Customer.address` test fixture compile without an explicit `@reference` directive — inference supplies `firstHop.sourceColumns()` / `firstHop.sourceTable()` from the jOOQ catalog. This plan's classifier edits stand on the same metadata but touch neither the inference code path nor any other plan's classifier arm.

**Test-tier shift.** Extracting the new scatter helper (§3) makes the single-cardinality scatter semantics directly unit-testable without a database round-trip — a seam the list-cardinality path already benefits from via `scatterByIdx`. The execution-test evidence of DataLoader deduplication (two sibling parents share one rows invocation) is the end-to-end proof; the unit test is the fast feedback loop when scatter logic changes. This fits the "Rebalance test pyramid" backlog framing.

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
- **DataLoader deduplication** transparently coalesces sibling parents that share an FK value into one batch slot — already what DataLoader does for any repeated key, given that `DataLoader`'s default cache-enabled mode is in effect. Verified: `TypeFetcherGenerator.java:1025,1119,1188` registers DataLoaders via `newDataLoaderWithContext($L)` / `newDataLoader($L)` with no option overrides, and java-dataloader defaults `cachingEnabled=true`. Because the coalescing happens *within* one DataLoader instance (scoped to one `(type, field)` pair) and GraphQL selection-merging guarantees all invocations of a same-name field in one operation share arguments and selection set, the FK-value keying does not cross-contaminate across paths with different args or selections. Different parent types with their own `person` fields get their own fetchers, their own DataLoaders, and never merge.
- **Null-FK parents.** A parent row with NULL in the FK column takes a short-circuit path in the fetcher (see §4) — the fetcher returns `CompletableFuture.completedFuture(null)` without invoking the DataLoader, since `terminal.pk = parentInput.fk_col` can never match under ANSI NULL semantics. Correct for `Person @splitQuery` (nullable). A non-null `Person! @splitQuery` on a row with a NULL FK is a schema-author error that surfaces as a GraphQL non-null violation at runtime; flagged for a follow-on validator check (warn when `@splitQuery` targets a non-null field while the inferred FK column is nullable).
- **Validator** deletes the `!isList` branches in both `SplitRowsMethodEmitter.unsupportedReason` overloads. §1b (below) adds a classifier-level rejection for single-cardinality `@splitQuery @lookupKey` so the `SplitLookupTableField` emitter branch deletion is safe.

Verification: `Vurderingsmelding.person: Person @splitQuery` (or any parent-holds-FK single-FK pair) compiles and executes against the test-spec database; two sibling parents pointing at the same child produce one rows-method invocation for that key.

## What we're NOT doing

- **Multi-hop paths.** Scope is one-hop FK: the single `FkJoin` whose source table equals the parent. Two-hop junction paths on single cardinality (rare — would usually indicate a modelling mistake; `@splitQuery @lookupKey` covers the junction case via `LookupMapping`) stay stubbed. Today's `!isList` branches in `unsupportedReason` catch multi-hop single-cardinality by accident; §3 deletes them, so §1a's helper takes responsibility for the `path.size() == 1` invariant (see §1a below — the helper returns the parent-PK fallback whenever single-hop parent-holds-FK doesn't match, and §1 adds a parallel classifier-level rejection for single-cardinality multi-hop so the emitter never sees one).
- **Merging `@splitQuery` with `@lookupKey` on single cardinality.** Single-cardinality `@splitQuery @lookupKey` remains stubbed. Note: the existing scalar-return rejection at `FieldBuilder.java:266-269` only fires on the `!hasSplitQuery && hasLookupKey` arm — the `hasSplitQuery && hasLookupKey` arm at `:251-260` has no cardinality check today. This plan adds a parallel rejection at the `hasSplitQuery && hasLookupKey` arm (see §1 below) so the single-cardinality `@splitQuery @lookupKey` case is caught at classifier time rather than falling through to a runtime stub in the emitter.
- **Condition-join paths.** `JoinPathEmitter.hasConditionJoin` still short-circuits to a stub for both list and single cardinality; classification-vocabulary item 5 owns that.
- **Rekeying list cardinality by FK value.** The existing list-cardinality child-holds-FK path keeps parent-PK keying. Switching it would break DataLoader scatter semantics (list-cardinality needs one slot per parent, not one slot per shared FK value).
- **Record-parent variants.** `RecordTableField` / `RecordLookupTableField` single-cardinality stubs at `SplitRowsMethodEmitter.java:224-235` and `:266-280` already use `firstHop.sourceColumns()` for the BatchKey (see `FieldBuilder.deriveBatchKeyForResultType` at `:1657-1666` — it picks source columns unconditionally). Unblocking them is a strictly smaller variant of this plan and can land in a follow-up — the emitter change here is reusable, but the record-parent projection wiring is separate (record-parent rows don't go through `TypeClassGenerator.$fields`).

## Implementation approach

### 1. Classifier — parent-holds-FK BatchKey, single-hop guard, and single-cardinality `@splitQuery @lookupKey` rejection

**File:** `graphitron-rewrite/src/main/java/no/sikt/graphitron/rewrite/FieldBuilder.java`

**1a. BatchKey derivation.** At `:250` the parent-backed `@splitQuery` arm hardcodes `parentTableType.table().primaryKeyColumns()` into the BatchKey. That's correct for list cardinality but wrong for single cardinality, where the DataLoader should key by the FK column value on the parent row. Cardinality itself is the direction signal: the `@splitQuery` schema contract ties cardinality to FK direction (Single ⇒ parent-holds-FK; List ⇒ child-holds-FK), so the helper only needs `isList`:

```java
// Sibling of deriveBatchKeyForResultType at :1657 — that helper is for record parents
// and always uses fk.sourceColumns() because record parents never batch by parent PK.
// This helper is for @table parents on a DataLoader-backed field; the choice is driven
// entirely by the schema-side cardinality contract.
//
// The single-hop precondition is enforced by the caller (§1c) — this helper only
// decides keying, not feasibility.
private static BatchKey deriveSplitQueryBatchKey(TableRef parentTable, List<JoinStep> path, boolean isList) {
    if (!isList && !path.isEmpty() && path.get(0) instanceof JoinStep.FkJoin fk) {
        return new BatchKey.RowKeyed(fk.sourceColumns());  // single = parent holds FK
    }
    return new BatchKey.RowKeyed(parentTable.primaryKeyColumns());  // list = child holds FK
}
```

Call from the two existing `parentBatchKey` construction sites (the `@splitQuery` and `@splitQuery @lookupKey` arms around `:250`). Pass the resolved `referencePath.elements()` and `returnType.wrapper().isList()` — both already in scope.

**Why cardinality and not `FkJoin.sourceTable()` / table-name comparison.** `FkJoin.sourceTable` is currently written as the traversal-origin table (caller-provided `sourceSqlName`) in both `BuildContext.synthesizeFkJoin:473` and `parsePathElement:559-560`. For hop 0 that's always the parent, so a comparison like `fk.sourceTable().equalsIgnoreCase(parentTable.tableName())` would be trivially true for every FK — not a direction signal at all. The docstring at `JoinStep.java:70-72` claims `sourceTable` resolves to the FK-holder table, which would make the comparison work, but that claim doesn't match the construction code today. Grep confirms there are zero readers of `FkJoin.sourceTable()` — it's currently dead data — so we're the first consumer and the contradiction hasn't bitten anything yet. Don't load-bear on the ambiguous field; use cardinality, and file a Backlog item to resolve the docstring/code drift (see roadmap: "Clarify `FkJoin` direction semantics").

Side-effect hygiene: update the `JoinStep.FkJoin` docstring in the same commit to reflect actual behaviour — one sentence — so the next reader isn't misled. Anything more structural (rename, derive `fkOnSource()`, swap sourceTable semantics) is scope creep; leave for the Backlog item.

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

**1c. Multi-hop single-cardinality `@splitQuery` rejection.** Today both the `!isList` branches in `SplitRowsMethodEmitter.unsupportedReason` (`:148-158`, `:187-203`) catch all single-cardinality cases, including multi-hop paths, as a side effect of rejecting single cardinality outright. §3 deletes those branches, so multi-hop single cardinality loses its guard. Add a classifier-level check on the `hasSplitQuery && !hasLookupKey` arm around `:250`:

```java
if (hasSplitQuery && returnType.wrapper() instanceof FieldWrapper.Single
        && referencePath.elements().size() != 1) {
    return new UnclassifiedField(parentTypeName, name, location, fieldDef,
        "Single-cardinality @splitQuery requires a single-hop parent-holds-FK reference path; "
        + "multi-hop paths are not yet supported on single cardinality");
}
```

The guard lives in the classifier (not the emitter) so the rejection surfaces as a build error rather than a runtime stub — consistent with §1b. §1a's helper takes the second (parent-PK fallback) arm for child-holds-FK list cardinality, which is correct and unchanged; single-cardinality child-holds-FK is rare-to-impossible (FK uniqueness on the child side would make it list cardinality ≤1 by schema design, not a separate case). If such a field ever slips through classification, §1a's helper still picks parent-PK keying and §3's `buildSingleMethod` falls back to the list-cardinality JOIN shape — not useful in practice but not a crash.

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

Delete the `!isList` branches in both `unsupportedReason` overloads (`:148-158`, `:187-203`). The `SplitLookupTableField` branch is already unreachable — `FieldBuilder.java:266-269` rejects `!hasSplitQuery && hasLookupKey` single-cardinality, and §1b above adds the missing `hasSplitQuery && hasLookupKey` rejection — so its deletion is dead-code cleanup. The `SplitTableField` branch deletion is the one that enables new behaviour.

Split `buildListMethod` into a shared core + two return-shape tails, or introduce a sibling `buildSingleMethod`. The SELECT / FROM / JOIN construction is identical; the only differences are:

- **Return type:** `List<Record>` (single) vs `List<List<Record>>` (list).
- **Scatter helper call:** `scatterSingleByIdx(flat, keys.size())` (new) vs `scatterByIdx(flat, keys.size())`.
- **Empty-lookup short-circuit** (SplitLookupTableField only): single-cardinality returns a `List<Record>` of `null`s, not `List<List<Record>>` of empty lists. Needs an `emptySingleScatter(int)` helper or a parameter on the existing one.

New scatter helper sketch:

```java
private static List<Record> scatterSingleByIdx(Result<Record> flat, int keyCount) {
    Record[] out = new Record[keyCount];
    for (Record r : flat) {
        int idx = r.get(IDX_COLUMN, Integer.class);
        if (out[idx] != null) {
            throw new IllegalStateException(
                "scatterSingleByIdx: two rows at idx " + idx
                + " — single-cardinality @splitQuery contract requires ≤1 terminal row per key");
        }
        out[idx] = r;
    }
    return java.util.Arrays.asList(out);  // nulls preserved where no match; Arrays.asList (not List.of) to permit nulls
}
```

The "at most one row per idx" invariant holds because the terminal table's PK equals the FK value keyed by idx — so `terminal.pk = parentInput.fk_col` yields ≤1 terminal row per idx. The defensive check is cheap (one null-compare per row) and surfaces misconfigurations (e.g. a future caller forgetting to constrain the JOIN to PK) at test time rather than silently discarding rows. §5's unit test asserts the `IllegalStateException` on duplicate-idx input.

### 4. Fetcher — scatter-helper emission gate

**File:** `graphitron-rewrite/src/main/java/no/sikt/graphitron/rewrite/generators/TypeFetcherGenerator.java`

`buildSplitQueryDataFetcher` (`TypeFetcherGenerator.java:1083-1124`) already branches on `tb.wrapper().isList()` — single cardinality already produces `DataLoader<KeyType, Record>` and returns `CompletableFuture<Record>`. The runtime stub at `SplitRowsMethodEmitter.java:528-543` already returns `List<Record>` for single cardinality. No signature changes needed on the fetcher or stub.

**Null-FK short-circuit.** Single cardinality needs a pre-load check that list cardinality does not: if the extracted FK value is null (parent row's FK column was NULL), return `CompletableFuture.completedFuture(null)` without invoking `loader.load`. This avoids an unnecessary round-trip (the VALUES-vs-JOIN can never match a NULL key) and sidesteps any question about DataLoader null-key handling across versions. List-cardinality fetchers don't need this branch — parent PK columns are NOT NULL by database contract. Emit the check immediately after `buildKeyExtraction` produces the `RowKey`, gated on `!wrapper.isList()`.

**Scatter-helper emission gate.** `buildScatterByIdxHelper` (`SplitRowsMethodEmitter.java:576`) and `buildEmptyScatterHelper` (`:555`) currently emit when any Split* field is present. Add a parallel gate that emits `scatterSingleByIdx` / `emptySingleScatter` when any Split* field in the class has single cardinality. A class with both list and single Split* fields emits all four helpers.

### 5. Validator / tests

**Fixtures — primary (`Customer.address`) and null-FK (`Store.manager`).** `Customer.address` is the primary fixture: `customer.address_id → address.address_id` is a single FK, NOT NULL, seeded on every customer row. `Store.manager` carries the null-FK edge case: `store.manager_staff_id` is nullable in schema but currently seeded non-null on both rows by `init.sql:204-205` (`UPDATE store SET manager_staff_id = 1 WHERE store_id = 1;` / `= 2 WHERE store_id = 2;`). Drop the second UPDATE (or add a third store row) so at least one store carries NULL `manager_staff_id`, then assert in the execution test that `Store.manager` returns `null` (not throws) for that store. Without this fixture change, the null-FK short-circuit path in §4 has no end-to-end coverage.

**Unit test — scatter helper.** `scatterSingleByIdx` is a pure `Result<Record> × int → List<Record>` function; test directly:
- empty result + keyCount=3 → `[null, null, null]`
- full match (one record per idx, ordered) → records at matching slots
- gap in matches → null preserved at the gap index
- duplicate-idx input → defensive `IllegalStateException`

This locks down scatter semantics without the rewrite→compile→DB round-trip chain. The list-cardinality path's `scatterByIdx` has historically been caught out by edge cases here; the unit seam is a cheap ratchet.

**Pipeline and structural tests.**
- `SplitTableFieldValidationTest`, `SplitLookupTableFieldValidationTest` — the existing `SINGLE_CARDINALITY_STUB` cases become emittable. Replace with a positive assertion (one-hop FkJoin, non-empty joinPath, single-cardinality wrapper).
- `GraphitronSchemaBuilderTest` — add `SPLIT_TABLE_SINGLE_CARDINALITY` classification case using a real single-FK pair (positive), plus `SPLIT_LOOKUP_TABLE_SINGLE_CARDINALITY_REJECTED` (negative: asserts the new classifier-level rejection from §1b). `Customer.address: Address` is a good fit for the positive case. Both cases should cover explicit `@reference(path: [...])` and absent-`@reference` inference, since the BatchKey derivation runs on the resolved `referencePath.elements()` regardless of how the path was produced.
- `SplitTableFieldPipelineTest` — add structural assertions for the fetcher + rows method shapes in the single-cardinality case (mirror the list-cardinality assertions, swap `List<List<Record>>` → `List<Record>` and `scatterByIdx` → `scatterSingleByIdx`). Assert the null-FK short-circuit branch appears in the emitted fetcher body.

**Execution tests.** In `graphitron-rewrite/graphitron-rewrite-test/src/test/java/.../GraphQLQueryTest.java`:
- `Customer.address` happy path: query two customers sharing the same `address_id`, assert both resolve to the same Address, assert exactly one rows-method invocation for that key via the existing JDBC round-trip counter pattern from the Language.films tests.
- `Store.manager` null-FK path: after the `init.sql` fix above, query a store with NULL `manager_staff_id` and assert the resolver returns `null` rather than throwing. Cross-check that no DataLoader round-trip happens for that key (the short-circuit fires before `loader.load`).

**Test-spec schema additions.** Add to `graphitron-rewrite/graphitron-rewrite-test/src/main/resources/graphql/schema.graphqls`:
```graphql
type Customer @table(name: "customer") { ... address: Address @splitQuery }
type Store    @table(name: "store")    { ... manager: Staff   @splitQuery }
```
Inference picks up the single FK (already landed); no `@reference` needed.

**User-facing docs.** `graphitron-codegen-parent/graphitron-java-codegen/README.md` §"Split database queries with @splitQuery" (`:229-249`) is the canonical reference and today implicitly assumes list cardinality (all worked examples in the file — `:436`, `:462`, `:517` — are lists; the one single-cardinality example at `:481` is a doc fragment for path syntax, not a compile claim). Add a short paragraph to that section noting that parent-holds-FK single-cardinality `@splitQuery` is supported (one-hop, nullable or non-null target) with a one-line example (`address: Address @splitQuery` on `Customer`). Updating the existing single-cardinality fragment at `:481` to note cardinality support is optional — the fragment documents path syntax, not cardinality.

## Success criteria

### Automated

- `mvn test -pl :graphitron-rewrite` passes.
- `mvn test -pl :graphitron-rewrite-test -Plocal-db` passes with the new `Customer.address @splitQuery` (or equivalent) execution test.
- Two sibling parents pointing at the same child invoke the rows method exactly once for that key — assert via the existing JDBC round-trip counter pattern.
- Grepping `SplitRowsMethodEmitter.java` for `"not yet supported; list cardinality is the Phase 2b"` returns zero hits (both `SplitTableField` and `SplitLookupTableField` single-cardinality stubs deleted).

### Manual

- Running the generator against `sis-graphql-spec` (the schema that originally surfaced the `Vurderingsmelding.person` error) no longer fails on single-cardinality `@splitQuery` fields whose inferred path is a single parent-holds-FK hop. Fields whose single cardinality is a genuine modelling mistake (e.g. the FK is on the child side, making single cardinality unsatisfiable without an extra filter) are unaffected — they continue to classify correctly via the child-holds-FK branch but would fail at SQL execution if the child table returns multiple rows for a parent. Flag for a follow-on validator check, not in scope here.

## Open questions

1. **DataLoader DFE selection across merged loads — inherited, non-blocking.** When two loads for the same key arrive with different DFEs, the batch lambda picks `getKeyContextsList().get(0)`'s DFE for `$fields` projection. GraphQL selection-merging guarantees all DFEs for a same-name field in one operation share args and selection set, so picking any one is safe. This behaviour exists today for list cardinality and is unchanged here — **but** this plan *increases* cross-parent coalescing within one DataLoader slot: parents with distinct PKs but shared FK values now share a key slot, where they would have been separate slots under the list-cardinality keying. The "shared selection set and args" invariant is now load-bearing in both directions (same-slot dedup relied on it already; now more parents end up same-slot). If a fragment-with-different-`@skip`/`@include` edge case turns out to break the invariant, the fix applies equally to both cardinalities and is a separate roadmap item. Does **not** block this plan; flagged for reviewer awareness.

## References

- Existing list-cardinality `@splitQuery` emitter: `graphitron-rewrite/src/main/java/no/sikt/graphitron/rewrite/generators/SplitRowsMethodEmitter.buildListMethod` (`:283-508`).
- Fetcher single-cardinality precedent: `TypeFetcherGenerator.buildServiceDataFetcher` (`:991-1030`) already branches on `tb.wrapper().isList()` and returns `CompletableFuture<Record>` for single.
- Parent key extraction: `GeneratorUtils.buildKeyExtraction` (`:198-231`) — reads BatchKey columns off `env.getSource()`; the parent projection must carry them.
- Implicit `@reference` path inference plan: `plan-implicit-reference-inference.md` (Done) — delivers the FK-column metadata in `firstHop.sourceColumns()` / `firstHop.sourceTable()` for absent-`@reference` fields. `Vurderingsmelding.person` relies on both inference (already landed) and this plan (to emit the single-cardinality rows method).
- Original trigger: `sis-graphql-spec` production build failure on `Vurderingsmelding.person` (generator-schema.graphql:19906).

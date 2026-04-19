# argres Phase 2b — Split(Lookup)TableField DataLoader rows-method emission

> **Status:** In Progress
>
> Fills in the DataLoader rows-method bodies for `ChildField.SplitTableField` and `ChildField.SplitLookupTableField`, which today compile but throw `UnsupportedOperationException` at runtime. Completes the split-query story for inline DataLoader batching; `RecordLookupTableField` (Phase 2c) remains out of scope pending the `BatchKey.ObjectBased` model decision.

Phase 2a made `LookupTableField` render inline via `DSL.multiset` + VALUES/JOIN keyset. Phase 2b covers the DataLoader-batched complement: flat SELECT per batch instead of a per-parent multiset projection. Together, 2a and 2b close the `LookupField` generation story and make the two most common split-query shapes work end-to-end.

## Current state

- **Model.** `SplitTableField` (`ChildField.java:128-143`) and `SplitLookupTableField` (`ChildField.java:157-173`) both implement `TableTargetField` + `BatchKeyField`; `SplitLookupTableField` additionally implements `LookupField` (carries `LookupMapping`). `BatchKeyField` capability is the join point — three implementers (`Split*`, `ServiceTableField`); each overrides `rowsMethodName()` to `"rows" + Capitalized(name)`. Parent batch key is resolved by `FieldBuilder.java:245` as `BatchKey.RowKeyed(parentTable.primaryKeyColumns())` — same shape for both Split variants.
- **Generator — both the fetcher and the rows method are stubs today.** The skeleton plan understated this.
  - `TypeFetcherGenerator.buildSplitQueryDataFetcher` (`:1016-1024`) emits `public static CompletableFuture<List<Record>> xxx(DataFetchingEnvironment env) { throw new UnsupportedOperationException(); }`. No DataLoader is registered; no key is extracted from the parent source. Phase 2b replaces this with the DataLoader-registering shape established by `buildServiceDataFetcher` (`:942-981`) — `graphitronContext(env).getDataLoaderName(env)` + `env.getDataLoaderRegistry().computeIfAbsent(...)` + `GeneratorUtils.buildKeyExtraction(batchKey, parentTable)` + `loader.load(key, env)`.
  - `TypeFetcherGenerator.buildSplitRowsMethod` (`:1026-1035`) emits `public static Object rowsXxx(List<KeyElementType> sources) { throw new UnsupportedOperationException(); }`. Signature is placeholder: return type `Object` and a single `sources` parameter. The real signature must match `buildServiceRowsMethod`'s `(List<KeyElement> keys, DataFetchingEnvironment env, SelectedField sel) → List<List<Record>>` (list cardinality) / `List<Record>` (single) — this is what the DataLoader lambda calls at `buildServiceDataFetcher.java:964`.
  - Both variants sit in `IMPLEMENTED_LEAVES` (`:112-113`) today because skeletons compile. Phase 2b closes the honesty gap — after 2b, `IMPLEMENTED_LEAVES` means "emits working code", not "emits a method whose body throws".
- **Phase 2a reuse surface (unmodified).** `LookupValuesJoinEmitter.buildChildInputRowsMethod` reads `@lookupKey` args from a `SelectedField` and builds a typed `RowN[]`. `JoinPathEmitter` handles the FK chain and correlation predicate. `ArgCallEmitter` handles `@condition` filter args. `InlineLookupTableFieldEmitter`'s USING→ON lesson applies identically here (junction paths collide).
- **Key extraction infra (already present).** `GeneratorUtils.keyElementType(batchKey)` (`:130-136`) maps `RowKeyed` → `RowN<A,B,…>`, `RecordKeyed` → `RecordN<A,B,…>`, `ObjectBased` → FQN. `GeneratorUtils.buildKeyExtraction(batchKey, parentTable)` (`:171-204`) emits the `<KeyType> key = …` declaration the fetcher places before `loader.load(key, env)`. Phase 2b consumes both unchanged.
- **Validator.** Stubbed-variant validator (Pending Review on trunk) does not flag Split* today because they sit in `IMPLEMENTED_LEAVES`. Phase 2b makes the "implemented" claim honest.

## Design

### Emission locus

Two generated methods per field, both on the `*Fetchers` class — Phase 2b rewrites both:

1. **Public fetcher** (`rowsMethodName()`'s sibling, name = field name). DataLoader-registering. Signature matches `buildServiceDataFetcher`'s shape: `public static CompletableFuture<List<Record>> xxx(DataFetchingEnvironment env)` for list cardinality, `CompletableFuture<Record>` for single. Body: extract per-parent key via `GeneratorUtils.buildKeyExtraction`, register DataLoader (keyed on batch-key type), call `loader.load(key, env)`. The DataLoader's batch lambda invokes the rows method with the collected keys list.
2. **Package-visible rows method** (`rowsXxx`). Signature: `public static List<List<Record>> rowsXxx(List<KeyN<…>> keys, DataFetchingEnvironment env, SelectedField sel)` (list) / `List<Record>` (single). Body: flat correlated-batch SELECT, idx-scattered return list aligned with `keys`.

Both method signatures match the existing ServiceTableField pair so the DataLoader lambda in (1) can call (2) directly — the service-field infrastructure already speaks this protocol. The one new piece is the rows method's SQL body and its Java-side scatter.

### Shape

The rows method emits a **flat batched SELECT that ANDs parent correlation with any lookup-key narrowing**, keyed on a VALUES derived table whose leading `idx` column drives the scatter.

```java
public static List<List<Record>> rowsXxx(
        List<Row2<Integer, Integer>> keys,  // example: 2-column parent PK
        DataFetchingEnvironment env,
        SelectedField sel) {
    if (keys.isEmpty()) return List.of();
    DSLContext dsl = graphitronContext(env).getDslContext(env);

    // --- Parent-input VALUES table ---
    // Unpack each RowN into (idx, pkCol1, pkCol2, ...) because RowN has no generic
    // dynamic-arity accessor; we know N at codegen time from BatchKey.RowKeyed.keyColumns().size().
    RowN[] parentRows = new RowN[keys.size()];
    for (int i = 0; i < keys.size(); i++) {
        Row2<Integer, Integer> k = keys.get(i);
        parentRows[i] = DSL.row(DSL.inline(i), k.value1(), k.value2());
    }
    Table<?> parentInput = DSL.values(parentRows).as("parentInput", "idx", "pk_col1", "pk_col2");

    // --- Alias + FK chain (same as G5 / Phase 2a) ---
    Target f0 = Tables.TARGET.as("f0");
    // (intermediate FK-hop aliases here)

    // --- Lookup-input VALUES table (SplitLookupTableField only) ---
    RowN[] lookupRows = xxxInputRows(sel, f0);  // from LookupValuesJoinEmitter.buildChildInputRowsMethod
    Table<?> lookupInput = DSL.values(lookupRows).as("xxxInput", "idx", "<lookup_col>");

    // --- Flat SELECT ---
    Result<Record> flat = dsl
        .select(fieldsPlusIdx(Target.$fields(sel.getSelectionSet(), f0, env), parentInput))
        .from(f0)
        .join(<bridging hops via JoinPathEmitter>).onKey(...)
        .join(parentInput).on(<first-hop FK cols = parentInput cols>)
        .join(lookupInput).on(<target cols = lookupInput cols>)       // SplitLookupTableField only
        .where(<whereFilter methods + tf.filters() via ArgCallEmitter>)
        .orderBy(<tf.orderBy fixed columns, if any>)
        .fetch();

    return scatterByIdx(flat, keys.size());  // see "Scatter contract" below
}
```

**Why parent-input VALUES+ON rather than `WHERE (fk_col...) IN (keys)`.** Three reasons: (a) the idx column is the scatter signal (see Scatter contract) — IN predicates don't expose positional indices; (b) it reuses Phase 2a's emitter shape nearly verbatim, so the mental model and aliasing conventions are shared; (c) USING vs ON ambiguity: on multi-hop paths the junction table brings in columns that collide with the FK columns, exactly as in Phase 2a C2 (`film_actor.actor_id` vs `actor.actor_id`). Using explicit ON forecloses that class of bug.

**Why the lookup-input is a second VALUES join rather than folded into the parent-input.** Parent keys vary per batch entry; `@lookupKey` input is extracted once from `env` and broadcast across all parents. Mixing them into one VALUES table would require a cross-product emission in Java; keeping them as two separate JOINs lets Postgres compute the intersection as a natural two-join query.

**Empty-keys short-circuit.** `if (keys.isEmpty()) return List.of();` before touching the DSL context. Cheap, and matches the Phase 2a "empty-input short-circuits in Java, not SQL" invariant (jOOQ rejects empty `RowN[]` to `DSL.values`).

### Scatter contract

DataLoader's contract: given `List<K> keys`, the batch function returns `List<V>` aligned by index — `V[i]` is the value for `keys[i]`. For Phase 2b, `V = List<Record>` (list cardinality) or `V = Record` (single cardinality).

**We scatter by the VALUES `idx` column, not by re-reading the parent PK from each returned row.**

Reasons:

1. **idx is guaranteed present.** We already emit it as the leading VALUES column; it ships back on every joined row via the SELECT projection. Re-reading parent PK from the result would require it to survive the projection, which it does not by default (`$fields` projects only what the GraphQL selection set asks for).
2. **idx is dense and positional — matches List indexing directly.** `result.get(idx)` is O(1); no Map rebuild, no equality-by-RowN semantics (which jOOQ's RowN equality does support, but is more expensive than integer hash).
3. **idx is stable under ambiguous parent PKs.** If a schema accidentally ships the same RowN twice in `keys`, idx scatter still puts results in the correct positions; key-based scatter would coalesce them.

**Concrete implementation.** The SELECT adds `parentInput.field("idx").as("__idx__")` to the projection. The scatter is:

```java
private static List<List<Record>> scatterByIdx(Result<Record> flat, int keyCount) {
    List<List<Record>> out = new ArrayList<>(keyCount);
    for (int i = 0; i < keyCount; i++) out.add(new ArrayList<>());
    for (Record r : flat) {
        int idx = r.get("__idx__", Integer.class);
        out.get(idx).add(r);
    }
    return out;
}
```

For single cardinality, `scatterFirstByIdx` returns `List<Record>` aligned with `keys` where each slot is the first row for that idx, or `null` when the SELECT returned no row for that key. The null-not-empty-list convention matches DataLoader's contract for single-cardinality fields (graphql-java reads the `CompletableFuture<Record>` and treats `null` as "no result for this parent", which propagates to GraphQL as an explicit null). If two rows share an idx (ambiguous schema), the second silently wins; the emitter asserts single-cardinality at codegen time by inspecting `returnType().wrapper()` and the SQL should never return multiple matches in practice, but the helper does not defensively assert at runtime — treat as an execution-tier test coverage item.

**The `__idx__` projection column is harmless downstream.** graphql-java field resolvers read by GraphQL field name; `__idx__` isn't in the selection set, so it's never fetched by a DataFetcher. The projection cost is one additional integer column per row — negligible.

**Scatter helper placement.** Emitted once per `*Fetchers` class, private static, conditional on the class containing any Split* field. Prefer a single shared helper over per-field inlining: the method is <10 LOC and identical across fields.

## Commit structure

Four commits. C1 lands everything needed to make one Split field work end-to-end; C2 extends to `SplitLookupTableField`; C3 adds pagination rejection; C4 ships schema fixtures + execution tests (Phase 2a's C1/C2 split pattern).

### C1 — Fetcher rewrite, rows-method rewrite (`SplitTableField` only), pipeline test

Atomic: `buildSplitQueryDataFetcher` and `buildSplitRowsMethod` must change together, or the DataLoader lambda calls a method whose signature doesn't match.

- **`SplitRowsMethodEmitter`** — new class in `no.sikt.graphitron.rewrite.generators`. `public static MethodSpec buildRowsMethod(BatchKeyField bkf, TableRef parentTable)`. Builds the rows-method MethodSpec complete with signature + body. Internally branches on `bkf instanceof ChildField.SplitLookupTableField` vs `ChildField.SplitTableField` — C1 implements the latter only; the lookup branch throws `UnsupportedOperationException` at codegen time so C2 is the next landing signal.
- **Rewrite `TypeFetcherGenerator.buildSplitQueryDataFetcher`** (`:1016-1024`). Model on `buildServiceDataFetcher` (`:942-981`) — same skeleton, same `buildKeyExtraction` + DataLoader registry pattern. Consider factoring a shared helper `buildDataLoaderRegisteringFetcher(fieldName, bkf, returnType, rowsMethodName)` that both Service and Split paths call, but only if the factoring is a net-smaller diff; otherwise duplicate to avoid scope creep.
- **Rewrite `TypeFetcherGenerator.buildSplitRowsMethod`** (`:1026-1035`). Delegate to `SplitRowsMethodEmitter.buildRowsMethod(...)`. Fix signature to `(List<KeyElement>, DataFetchingEnvironment, SelectedField) → List<List<Record>>` / `List<Record>`.
- **Scatter helper emission.** `TypeFetcherGenerator.generateTypeSpec` emits a single `scatterByIdx` (list variant) + `scatterFirstByIdx` (single variant) on any `*Fetchers` class that contains a `Split*` field. Private static.
- **ConditionJoin stub parity with G5.** `SplitRowsMethodEmitter` rejects any `ConditionJoin` step in the path with the same runtime-throwing stub G5 and Phase 2a emit. Inherits classification-vocabulary item 5 resolution.
- **Pipeline test.** `SplitTableFieldPipelineTest` (new) — SDL with a `@splitQuery` child field produces a fetcher `TypeSpec` whose fetcher method signature is `CompletableFuture<List<Record>> xxx(DataFetchingEnvironment)` and whose rows method signature is `List<List<Record>> rowsXxx(List<RowN<…>>, DataFetchingEnvironment, SelectedField)`. Structural only; no body-substring asserts.
- **Compile gate.** `mvn compile -pl :graphitron-rewrite-test-spec -Plocal-db` must stay green. Will pass trivially because the test-spec schema has zero `@splitQuery` fields until C4.

### C2 — `SplitLookupTableField` rows-method body

Extend `SplitRowsMethodEmitter` with the lookup branch: add the `xxxInputRows` helper call, the lookup-input VALUES, and the second JOIN ... ON. Reuses `LookupValuesJoinEmitter.buildChildInputRowsMethod` verbatim — Phase 2a already emits it onto the type class; for Phase 2b we emit it onto the fetcher class instead (the rows method is there). Confirm the helper's modifiers stay package-visible; no other change required.

- Pipeline test additions: same SDL shape as C1 but with `@lookupKey`, assert the rows method + the inputRows helper both land on the fetcher class.

### C3 — Classifier rejection for `@asConnection` on `Split*`

Per Open Decision 3 (defer pagination). `FieldBuilder` rejects `FieldWrapper.Connection` on `SplitTableField` / `SplitLookupTableField` classification with a concrete diagnostic: `"@asConnection on @splitQuery fields is not supported; per-parent pagination inside a DataLoader batch requires window-function partitioning and is deferred to a follow-up plan."` No "follow-up X" placeholder — the diagnostic is a self-contained explanation plus "deferred to a follow-up plan" that does not name a specific doc the reader must look up. When the pagination-on-split-fields plan lands, update the diagnostic to reference it. Pipeline test covers the rejection. Parallels G5 C1's TableField rejection + Phase 2a C1's LookupTableField rejection — the invariant that `Split*.returnType().wrapper() != Connection` is established in one commit so C1/C2 emitters rely on it cleanly.

Ordering note. C3 logically precedes C1 (it's a classifier invariant the emitter assumes) but it can land after as long as no fixture in `test-spec` schema pairs `@splitQuery` with `@asConnection`. A one-line grep audit before landing confirms this.

### C4 — Schema fixtures + execution tests

- **`SplitTableField` fixture.** A single-hop `@splitQuery` field — e.g. `Film.inventory: [Inventory!]! @splitQuery @reference(path: [{key: "inventory_film_id_fkey"}])` plus an `Inventory` type. Needs `inventory` seed rows in `init.sql`.
- **`SplitLookupTableField` fixture.** Same field with `@lookupKey`: `Film.inventoryByStore(store_id: [Int!]! @lookupKey): [Inventory!]! @splitQuery @reference(...)`. Exercises the AND of parent correlation and lookup narrowing.
- **Execution tests (`GraphQLQueryTest`).**
  - Basic batching: multiple parents → one SQL round-trip (assert via `jooq.tools.LoggerListener` capture or `dsl.configuration().executeListenerProviders()` counter).
  - Per-parent scatter correctness: parent A gets A's children, B gets B's, never cross-contaminated.
  - Input-order preservation: keys returned in order.
  - Empty-keys short-circuit.
  - `@lookupKey` narrowing: same parents with different lookup-key inputs.
  - Multi-hop `@reference` path: junction table in the middle (ON-vs-USING lesson still applies).

### Resolved decisions

Second iteration — each decision resolved with concrete reasoning.

1. **Rows-method return type — `List<List<Record>>` (list) / `List<Record>` (single).** The existing `buildServiceRowsMethod` (`:992-1010`) already emits these return types for the same `BatchKeyField` capability, and `buildServiceDataFetcher`'s DataLoader lambda at `:964` consumes them. Phase 2b aligns with that shape so a single DataLoader-registering helper can serve both the Service and Split paths. `Result<Record>` was rejected (caller has to re-implement scatter). `Map<K, List<Record>>` was rejected (jOOQ's per-key Map coalesces duplicate keys, which would silently eat duplicates in an ambiguous-key schema). Gated C1.
2. **`SplitTableField` input-row shape — VALUES + ON (option a).** Pick option (a) from the skeleton. Three concrete reasons in the Shape section above: idx-based scatter requires VALUES; emitter shape matches Phase 2a 1:1; the junction-table ON-vs-USING lesson from Phase 2a C2 applies identically here. Option (b) (`WHERE parent_fk IN (values)`) was rejected: no idx signal for scatter, and IN-with-RowN requires jOOQ to emit a different SQL shape on composite keys.
3. **Pagination on split fields (`@asConnection`) — defer + classifier rejection (option i).** Per-parent pagination inside a DataLoader batch is a genuine design problem (window-function partitioning vs per-parent subqueries vs cursor-per-key) that deserves its own plan. For 2b, the classifier rejects `FieldWrapper.Connection` on `Split*` with a diagnostic pointing at the follow-up. Landed as C3. This also closes the current "fetcher returns `CompletableFuture<List<Record>>` — what's a connection supposed to wrap?" ambiguity.
4. **Empty-input short-circuit — Java-level `keys.isEmpty()` before touching DSL.** Matches Phase 2a's `rows.length == 0` invariant (jOOQ rejects empty `DSL.values` arrays). DataLoader typically won't dispatch an empty batch, but the rows method is a `public static` utility and the guard is two lines; include. Landed in C1 as part of `SplitRowsMethodEmitter`.
5. **`ServiceTableField` scope — out.** Verified: `buildServiceRowsMethod` (`:992-1010`) is also a stub (`throw new UnsupportedOperationException()`), but its body path is different — it calls a service method, not SQL. The SQL-emission design in this plan does not apply. Service rows bodies land in a separate plan alongside the other service-path items. `buildServiceDataFetcher` stays unchanged and Phase 2b's fetcher borrows its shape without touching it.

### Additional decisions surfaced during this iteration

6. **Scatter column naming — `__idx__`.** Chosen because it's GraphQL-impossible (field names match `/[_A-Za-z][_0-9A-Za-z]*/` but the leading-underscore + trailing-underscore pair is reserved-feeling and collision-unlikely with any `@field(name:)` mapping). Alternative `idx` was rejected — too common; could collide with a schema field literally named `idx` (our test-spec schema has one column using this kind of name in pagination args).
7. **Key-column unpacking — by codegen-time arity, not runtime introspection.** Parent key is `BatchKey.RowKeyed` with a known column count at codegen time. Emitter casts `keys.get(i)` to the concrete `RowN<…>` (e.g. `Row2<Integer, Integer>`) and calls `value1()`…`valueN()`. Generic `Row.intoArray()` was rejected — boxing + Object[] allocations per row. The codegen path is simpler and type-safe.

### Non-goals

- **`RecordLookupTableField` (Phase 2c).** Blocked on the `BatchKey.ObjectBased` decision (roadmap Backlog).
- **`ServiceTableField` rows-method body.** Service methods, not SQL. Separate plan alongside the other service-path items.
- **Pagination on `Split*`.** Deferred by Resolved Decision 3; classifier rejects `@asConnection` in C3. A follow-up plan covers pagination-via-window-functions or per-parent subqueries.
- **Union / interface split fields.** Different classification path; separate plan.
- **Mutation bodies** (roadmap stub priority #4). Separate track.

### Cross-plan dependencies

- **No partition migration.** Both variants already sit in `IMPLEMENTED_LEAVES`; Phase 2b fills in bodies, meta-test contract is unchanged.
- **Stubbed-variant validator (Pending Review on trunk).** 2b closes a known "IMPLEMENTED but throws at runtime" gap — after 2b lands, `IMPLEMENTED_LEAVES` semantics tightens to "emits working code". Note in the validator plan's `IMPLEMENTED_LEAVES` invariant once 2b is Done; no code change in the validator itself.
- **G5 / Phase 2a reuse.** `JoinPathEmitter` (G5) for alias + FK-chain emission and `emitCorrelationWhere`; `ArgCallEmitter` (G5) for `@condition` filter args; `LookupValuesJoinEmitter.buildChildInputRowsMethod` (Phase 2a) for `SplitLookupTableField`'s lookup-input helper. All consumed, none modified.
- **Phase 2a's USING→ON lesson.** The ON-based VALUES join in `InlineLookupTableFieldEmitter` (`:130-144`) is the reference implementation for Phase 2b's two VALUES joins. Same junction-collision risk; same fix.
- **Service DataLoader pattern.** `buildServiceDataFetcher` (`:942-981`) and `buildServiceRowsMethod` signature shape (`:992-1010`) are the reference for Phase 2b's fetcher shape. Consumed pattern-wise, not as code calls.
- **argres Phase 2c (`RecordLookupTableField`).** Inherits the emission pattern from 2b once the `BatchKey.ObjectBased` decision lands (roadmap Backlog).
- **argres Phase 3.** Independent — mutation / `InputColumnBinding` path.

## Test strategy

Per CLAUDE.md — structural at unit tier, behaviour at execution tier:

| Surface | What is verified |
|---|---|
| Unit | `buildSplitRowsMethod` returns a `MethodSpec` with the expected return type and parameter; no body-substring assertions. |
| Pipeline | SDL with `@splitQuery` and `@splitQuery + @lookupKey` produces a fetcher `TypeSpec` with `rowsXxx` methods; structural shape only. |
| Compilation | `mvn compile -pl :graphitron-rewrite-test-spec -Plocal-db` — real jOOQ catches type errors in flat SELECT emission. |
| Execution | `mvn test -pl :graphitron-rewrite-test-spec -Plocal-db` — batched queries against seeded PG data. Assert shape, content, and DataLoader batching semantics (N parents → 1 SQL round-trip). |

## History

- **2026-04-19 (Draft → Approved)** — independent reviewer pass by a session that didn't author either Draft iteration. Verified line-number citations against trunk: `TypeFetcherGenerator:942-981` (buildServiceDataFetcher), `:992-1010` (buildServiceRowsMethod), `:1016-1024` / `:1026-1035` (both Split stubs), `ChildField.java:128-143` / `:157-173` (Split* / SplitLookup* records), `FieldBuilder:245` (parentBatchKey construction), `GeneratorUtils:130-136` / `:171-204` (keyElementType + buildKeyExtraction), `InlineLookupTableFieldEmitter:130-144` (USING→ON lesson). All accurate. Two small reviewer fixes landed in the same pass: (a) C3's classifier-rejection diagnostic no longer contains the literal "follow-up X" placeholder — replaced with a self-contained reason plus a soft "deferred to a follow-up plan" pointer that will be tightened when the pagination-on-split-fields plan lands; (b) `scatterFirstByIdx`'s null-vs-empty-list semantics pinned — null for no-match, first() for match, ambiguous-key "second silently wins" acknowledged as an execution-tier coverage concern. Plan is implementation-ready; C1 can start immediately.
- **2026-04-19 (second Draft iteration)** — full design pass after code audit on trunk:
  - **Current state corrected.** The skeleton said only the rows-method body was a stub; in fact both `buildSplitQueryDataFetcher` (`:1016-1024`) and `buildSplitRowsMethod` (`:1026-1035`) are stubs. Phase 2b rewrites both.
  - **Fetcher shape pinned.** Match `buildServiceDataFetcher` (`:942-981`): DataLoader-registering via `graphitronContext(env).getDataLoaderName` + `DataLoaderFactory.newDataLoaderWithContext` + `GeneratorUtils.buildKeyExtraction`. A shared `buildDataLoaderRegisteringFetcher` helper is an optional micro-refactor, not a prerequisite.
  - **Rows-method signature pinned.** `(List<KeyN<…>>, DataFetchingEnvironment, SelectedField) → List<List<Record>>` (list) / `List<Record>` (single), matching `buildServiceRowsMethod`'s existing shape.
  - **Scatter contract added.** idx-based scatter over a `__idx__` projection column. Reasoning: idx is guaranteed present, dense and positional, and stable under duplicate keys. Key-based scatter was rejected for all three reasons.
  - **Shape expanded.** Two VALUES joins (parent-input + lookup-input), explicit ON (inheriting Phase 2a C2's USING→ON lesson for junction-collision paths).
  - **Five open decisions resolved.** Return type (list-of-list), VALUES+ON vs IN (VALUES+ON), `@asConnection` (defer + reject at classifier time in C3), empty-keys (Java-level short-circuit), ServiceTableField (out of scope — separate plan).
  - **Components restructured into four ordered commits.** C1 = fetcher rewrite + SplitTableField rows body + pipeline test; C2 = SplitLookupTableField rows branch; C3 = classifier `@asConnection` rejection; C4 = schema fixtures + execution tests.
  - **Decisions 6 + 7 surfaced** during design: `__idx__` naming, and codegen-time key-arity unpacking via `Row2<…>.value1()` calls rather than runtime `Row.intoArray()` introspection.
- **2026-04-19 (skeleton Draft)** — skeleton draft after Phase 2a landed (Pending Review). Five open decisions pinned; bodies intentionally coarse. Next iteration: resolve return-type shape (#1) and pagination stance (#3), then tighten deliverable 1's body spec.

# Argument Resolution

> **Status:** Approved
>
> Foundation (classification + projection) landed on `claude/graphitron-rewrite`. Phase 2 (generator-side migration: inline `TableField`, lookup VALUES+JOIN, composite-key input bindings) is the remaining work and gates six downstream features.

Plan for unified argument classification in the builder, `@condition` directive support, and lookup-field generation. The foundation (builder-side) is in; the generator-side refactor is the substantial work ahead and is the focus of this plan going forward.

## Current State

What's live on `claude/graphitron-rewrite` today:

- **Single-pass classification** (`FieldBuilder.classifyArguments`) produces `List<ArgumentRef>` — a builder-internal sealed hierarchy capturing every GraphQL argument's role (`ColumnArg`, `UnboundArg`, `TableInputArg`, `PlainInputArg`, `OrderByArg`, `PaginationArgRef`, `UnclassifiedArg`).
- **Projection helpers** (`projectFilters`, `projectOrderBySpec`, `projectPaginationSpec`, `projectForLookup`) turn the refs into generation-ready model values. The three legacy passes (`buildFilters` / `buildOrderBySpec` / `buildPaginationSpec`) are gone.
- **`@condition` on both `FIELD_DEFINITION` and `ARGUMENT_DEFINITION`** is supported. The four-state projection table (field-override × per-arg-override) is codified in `projectFilters`. `contextArguments` flow through `ServiceCatalog.reflectTableMethod` into trailing `ParamSource.Context` parameters on the emitted `ConditionFilter`.
- **`LookupField` capability interface** over the four lookup variants (`QueryLookupTableField`, `LookupTableField`, `SplitLookupTableField`, `RecordLookupTableField`), each carrying a non-`Optional` `LookupMapping lookupMapping()`. Populated at classify-time by `projectForLookup`.
- **`@lookupKey` classified once.** `ArgumentRef.ScalarArg.ColumnArg.isLookupKey` captures the directive at classify time; `projectForLookup` reads only refs and never re-touches the SDL. The classifier is the single source of truth for "is this a lookup key column".
- **Non-empty-`LookupMapping` invariant enforced at classify time.** `projectForFilter` checks that a field tripping the lookup gate (`hasLookupKeyAnywhere`) produces at least one `LookupColumn`; otherwise it returns a classify-time error rather than deferring the failure to the generator. This closes the gap where `@lookupKey` on an input-type field would silently produce an empty mapping.
- **`InputColumnBinding`** (cross-plan type shared with legacy-platform-id) is defined in `model/`; `TableInputArg.fieldBindings` is its consumer.
- **`UnboundArg` / `UnclassifiedArg` surface as field-level errors** via `projectFilters`, preserving the legacy `"argument 'X': <reason>"` format plus a Levenshtein candidate hint for column misses.

What the generator does *not* yet do:

- Lookup fetchers still read the `GeneratedConditionFilter` entries from `filters()` and delegate to a generated `<Type>Conditions` method (which does the `.in()`/`.eq()` emission).
- `LookupMapping.columns()` is populated but not read by any generator.
- `TableInputArg.fieldBindings` is always empty — composite-key input types have no generator path yet.
- No VALUES + JOIN emission; legacy IN/EQ semantics still govern lookup SQL.

---

## Design Principles (unchanged — retained for reference)

### Classification + projection, separated

`classifyArguments` is a one-pass structural classifier. It produces `List<ArgumentRef>` and never decides what the output shape will be. Projection helpers read the refs and decide per concern (filter, orderBy, pagination, lookup). Each projection is exhaustive over the refs; adding a new `ArgumentRef` variant forces every projection to account for it at compile time.

This survives the rewrite because the classify/project split is inherently decoupled — classification can evolve (new variants, new directive readings) without cascading through the projection call sites.

### Capability interfaces over dispatch chains

`LookupField` joins `SqlGeneratingField`, `MethodBackedField`, `BatchKeyField` as a cross-cutting capability. Generators dispatch on capability, not on per-variant `instanceof` — one arm, one emission path, one stub-vs-real decision site.

### Narrow component types

`LookupField.lookupMapping()` returns a plain `LookupMapping` (not `Optional<>`) because the four permitting variants always have one. The type signature encodes the invariant instead of relying on runtime null-guards.

### Builder-internal hierarchies are ephemeral

`ArgumentRef` never appears on field records or reaches generators. It's a builder decomposition tool, not a model type. Generators see the projected outputs — `WhereFilter` list, `OrderBySpec`, `PaginationSpec`, `LookupMapping` — not the raw classification.

---

## Remaining Work: The Generator-Side Migration

The builder now says everything the generator needs in order to emit lookup SQL correctly. The generator, however, still takes the legacy path — reading `filters()` and delegating to a separately-generated `<Type>Conditions` class. Completing the migration is foundational: it decouples lookup-key emission from the generic filter path, unlocks VALUES + JOIN semantics, and makes composite-key lookup tractable.

Below is the problem shape, then a four-phase plan. Earlier drafts split the migration into six phases with a behaviour-preserving interstitial emitter (`LookupConditionEmitter`) that a later phase superseded. That added two commits of generator churn with no end-state value — the plan now lands the VALUES + JOIN emitter directly and skips the disposable intermediate.

### The coupling between lookup and `TypeConditionsGenerator`

Today, a `QueryLookupTableField.filters()` contains a `GeneratedConditionFilter` whose `bodyParams()` are the lookup-key arguments. Two generators consume it:

1. **`TypeFetcherGenerator.buildQueryLookupRowsMethod`** — emits `condition = condition.and(FilmConditions.filmByIdCondition(table, filmIdKeys…))`.
2. **`TypeConditionsGenerator.generateConditionsClass`** — emits the method body: `return noCondition().and(table.FILM_ID.in(filmIdKeys))`.

Both iterate the same `filters()` list. Removing lookup keys from `filters()` (a requirement for `LookupMapping` to become authoritative) therefore also removes them from `TypeConditionsGenerator`'s input — the fetcher must either inline the condition or call a new emitter. The two generators must move together.

### Phase 1 — VALUES + JOIN emission, driven by `LookupMapping`

**Goal.** Land the end-state directly: `TypeFetcherGenerator.buildQueryLookupRowsMethod` emits a `VALUES(idx, col1, col2, …) AS input JOIN target ON …` derived-table select, ordered by `input.idx` to preserve input ordering. The emitter reads only `LookupField.lookupMapping()`; `filters()` no longer carries lookup-key entries; the `<Type>Conditions` method for the lookup field disappears.

**Why one phase, not three.** The previous plan sequenced an intermediate emitter that produced the same IN/EQ condition as today, then replaced it with VALUES+JOIN. That added two commits of churn with no end-state value. The value of the intermediate — bisectability of a regression — is already provided by execution tests that run per-commit; an IN/EQ-preserving interstitial commit would pass those tests identically and teach us nothing.

**Component deliverables (single logical change, can split across commits if helpful).**

1. **Builder-side.** `projectFilters` stops adding `bodyParams` entries for arguments with `ColumnArg.isLookupKey() == true`. `LookupField.filters()` then contains only non-key filters (field-level `@condition`, per-arg `@condition`). `projectForLookup` already reads these via `ca.isLookupKey()` — no change needed there.

2. **`LookupValuesJoinEmitter`** — new class in `generators/lookup/`. Generates, per lookup field, two artifacts into the enclosing `*Fetchers` class:

   - A pure helper `<fieldName>InputRows(DataFetchingEnvironment env, <TargetTable> table) -> Row[]`. Extracts each key from env, builds one `Row` per input index with `DSL.val(value, targetColumn.getDataType())` cells (typed binds — jOOQ applies the column's Converter internally and renders a plain JDBC bind, no SQL-level CAST). Returns an empty array when the primary list arg is null/empty. Pure function of `env` + `table`; unit-testable in isolation.
   - A thin fetcher body: `Row[] rows = <fieldName>InputRows(env, table); if (rows.length == 0) return <empty>; Table<?> input = DSL.values(rows).as("<fieldName>Input"); return dsl.select(...).from(table).join(input).using(table.COL1, table.COL2, …).orderBy(input.field("idx")).fetch();`.

   Raw `Row[]` is retained (typed `RowN<…>` has no clean JavaPoet mapping), but each cell is a typed `Param<T>` via `DSL.val`, so VALUES columns carry types without requiring typed row generics in emitted code. USING-join works by construction: VALUES column labels are emitted to match target column names, `idx` stays excluded by virtue of not existing on the target table.

3. **`TypeFetcherGenerator.buildQueryLookupRowsMethod`** — replace the current "iterate `filters()` for the lookup `GeneratedConditionFilter`, call `<Type>Conditions.<field>Condition`" block with calls to the emitter. Non-key filters (if any) are AND-ed as a `.where(...)` before `.orderBy(...)` as today.

4. **`TypeConditionsGenerator`** — gain an explicit `skip LookupField` arm. Self-documents the decoupling rather than leaving a silent "no `GeneratedConditionFilter` → no method" side effect.

5. **Variant-coverage meta-test (roadmap P1 #1).** Extend the sealed-root meta-test to assert every `LookupField` permit has an emission arm in `TypeFetcherGenerator`. Pin it here — this is the phase where lookup emission becomes a live capability rather than a stub.

**Verification.**

- Execution tests in `graphitron-rewrite-test-spec` must pass. Upgrade `containsExactlyInAnyOrder` → `containsExactly` and add an ordered-input test (`[3, 1, 2]` → films in that order) that proves VALUES+JOIN ordering.
- Pipeline test: `LookupField.filters()` contains no `GeneratedConditionFilter` for lookup-key args; `<Type>Conditions` class no longer has a method for lookup fields. `GeneratedSourcesSmokeTest` confirms.
- **No `CodeBlock`-substring assertions** (per CLAUDE.md). The emitter is verified via the above: generated code compiles (real jOOQ catalog) and produces ordered results against a real database. Emitter-level tests, if any, render the produced SQL via `DSLContext.renderInlined(...)` or equivalent and assert on the rendered SQL string — behaviour-level, not shape-level.

**Multi-list-key tuple correlation.** When multiple `@lookupKey` args are lists, treat them as zipped by index (one row of the VALUES table per input index, broadcasting scalars). Row count N is determined by the first list-typed column's length; scalars broadcast across all rows. If multiple list args have mismatched lengths the generated code fails at VALUES construction — the concrete error shape pins on the first schema that demands finer handling. If all `@lookupKey` args are scalar, N=1 — a single-row VALUES+JOIN, semantically identical to `WHERE k = v` but uniform with the list-keyed path (no special case in the generator).

**Blast radius.** Spike (2026-04-18): zero external consumers of the generated `<Type>Conditions.<field>Condition` methods. Only caller is `TypeFetcherGenerator.buildQueryLookupRowsMethod` itself — exactly the method this phase rewrites. Example server, example service, and approval tests reference *user-written* condition classes via schema-level `@condition(className:, method:)`, which is an orthogonal concept. `GeneratedSourcesSmokeTest` does not enumerate `*Conditions` classes; no update required there. Phase 1 is therefore a single-site change with no parallel deprecation path.

### Phase 2 — Child-field lookup generators (G5/G6)

**Goal.** Extend the emission path to `LookupTableField` (inline correlated subquery, table-mapped parent), `SplitLookupTableField` (DataLoader-backed, table-mapped parent), and `RecordLookupTableField` (result-mapped parent). All three are stubs today; each throws `UnsupportedOperationException`.

**Prerequisites surfaced after Phase 1 landed (2026-04-18).** The first draft of this section framed Phase 2 as "primarily wiring `LookupValuesJoinEmitter` into three new dispatch arms of `TypeFetcherGenerator`." Reconnaissance after Phase 1 shows that's incomplete:

1. **G5 is a gating prerequisite for 2a.** Plain `ChildField.TableField` (inline subquery without `@lookupKey`) has no emission today — it's also a stub (roadmap "Stubs to complete" #1, G5). Phase 2a's "inline correlated subquery with a VALUES+JOIN keyset" layers lookup-key correlation onto the inline-subquery shape that G5 is supposed to establish. Absent G5, Phase 2a has to invent both the inline-subquery pattern and the lookup-key layer simultaneously. Order as G5 → Phase 2a.

2. **Inline emission lives in `TypeClassGenerator.$fields`, not `TypeFetcherGenerator`.** Today `$fields(sel, table, env)` only projects `ColumnField` and `PlatformIdField`. Inline child `TableField` / `LookupTableField` must be embedded as sub-SELECT expressions *inside* `$fields`, not as separate fetcher methods. The emitter's role in Phase 2a is therefore narrower than its fetcher-side role in Phase 1 — it produces a `CodeBlock` that slots into `$fields`' projected-expression list, with the parent-row correlation already built by whatever G5 establishes.

3. **`RecordLookupTableField` has no `BatchKey` field.** This section originally grouped it with `SplitLookupTableField` as "DataLoader-backed rows methods, dispatches per `BatchKey` variant." The sealed hierarchy today carries `BatchKey` on `SplitLookupTableField` but not on `RecordLookupTableField`. Three possibilities to resolve before 2b/2c begins:
   - (a) the model needs a `BatchKey` field added to `RecordLookupTableField`,
   - (b) result-mapped batching uses a different mechanism (e.g. derived from the parent's result shape at dispatch time),
   - (c) `RecordLookupTableField` is actually synchronous in practice and doesn't belong to the DataLoader category.
   Pick one and codify it in the sealed hierarchy / emission arm before landing `RecordLookupTableField` emission.

4. **Test-spec schema has zero child-lookup fields.** `graphitron-rewrite-test-spec`'s `schema.graphqls` exercises only `@lookupKey` on root queries (`filmById`, `languageByKey`, `customerById`). Phase 2 execution tests require new child-lookup fields (e.g. `Film.actor(actor_id: ID! @lookupKey): Actor`) plus likely init.sql additions to populate the Sakila subset with FK-resolvable rows.

**Revised sub-items.**

- **2a.** `LookupTableField` — inline correlated subquery. Requires G5 first. The emitter slots a VALUES+JOIN derived-table select into `$fields` as one projected expression, correlated to the parent row via `joinPath`.
- **2b.** `SplitLookupTableField` — DataLoader rows method, table-mapped parent. Keys come from `BatchKey.RowKeyed` / `RecordKeyed` extracted from the parent batch, combined with the `@lookupKey` VALUES+JOIN as the derived-target side. Cross-product in SQL; rows method returns grouped results by parent-key position.
- **2c.** `RecordLookupTableField` — only after the `BatchKey` question above is resolved.

**Cross-reference.** Roadmap G5/G6 track these stubs. G5 is now an explicit prerequisite rather than a parallel track; `rewrite-roadmap.md` notes the dependency.

### Phase 3 — Composite keys via `TableInputArg` + `InputColumnBinding`

**Goal.** Populate `TableInputArg.fieldBindings` with real `InputColumnBinding` entries so the lookup emitter can treat an input-typed argument as a source of multiple key columns. Lifts the classify-time error that the foundation step surfaces ("@lookupKey declared but no scalar argument resolved to a lookup column") once composite-key inputs produce columns.

**Today.** `TableInputArg.fieldBindings` is always `List.of()`. The builder knows the input type's `TableRef` but doesn't walk the type's fields to resolve bindings. A field whose lookup keys come only from an input-type's fields is rejected at classify time with the error above.

**Atomic change (single logical commit — the three steps cannot ship separately without leaving `LookupColumn.sourcePath` half-wired).**

- **Binding population.** In `FieldBuilder.classifyArgument`, for a `TableInputArg`, walk the input type's fields and build `InputColumnBinding(fieldName, columnRef, extraction)` for each field that resolves to a column on `inputTable`. Unmatched fields error at classify time (matches `UnboundArg` strictness for scalar args).
- **Schema-model change.** Add `LookupColumn.sourcePath` — a record capturing the hierarchy (top-level arg name, then zero or more input-field names). `argName` drops as an ambiguous label. Generators read the path to build unique VALUES column labels and to derive extraction code at the call site.
- **Projection.** Extend `projectForLookup` to walk `TableInputArg.fieldBindings` and add `LookupColumn` entries with populated `sourcePath`. Each binding becomes one key column.
- **Emitter.** `LookupValuesJoinEmitter` handles the composite-key case: for an input-typed arg, extract its value once at the fetcher, then read each bound field for row construction.

**Architectural decision already committed.** `TableInputArg` stays builder-internal. Bindings flow through `LookupMapping.columns()` unchanged — one `LookupColumn` per input field. Generators never see `TableInputArg` or `InputColumnBinding` directly.

### Phase 4 — `@condition` on `INPUT_FIELD_DEFINITION`

**Goal.** Support `@condition` on fields *inside* input types (the third legal position per `directives.graphqls`; currently deferred).

**Scope.** This is an input-type-classification concern, not an argument-classification one. The GraphQL spec permits `@condition` on `INPUT_FIELD_DEFINITION`, and legacy README §645–674 documents the semantics: each input-type field with `@condition` contributes its own predicate, scoped to that field. Nested input types can each carry their own conditions.

**Sub-items.**

- **4a.** Extend `InputField` to carry an optional `ArgConditionRef` — the per-input-field `@condition` directive, reflected at type-build time.
- **4b.** When an input-type arg is used at a call site, its per-field conditions become additional `ConditionFilter` entries in the lookup emitter's output.
- **4c.** Override propagation: an outer-level `@condition(override: true)` on the arg suppresses inner fields' auto-predicates but not their explicit `@condition` methods (per legacy semantics).

**Test matrix (required before 4b begins landing).** The override-propagation interaction has four legal states per field and compounds across nested input types. Write the full matrix out before emitting any code:

| Outer arg `@condition` | Inner field `@condition` | Inner field auto-predicate | Inner explicit condition method |
|---|---|---|---|
| Absent | Absent | Emitted | — |
| Absent | Present (no `override`) | Emitted | AND-ed |
| Absent | Present (`override: true`) | Suppressed | Replaces |
| `override: true` | Absent | Suppressed | — |
| `override: true` | Present (no `override`) | Suppressed | AND-ed |
| `override: true` | Present (`override: true`) | Suppressed | Replaces |
| `override: false` | Absent | Emitted | — |
| `override: false` | Present (no `override`) | Emitted | AND-ed |
| `override: false` | Present (`override: true`) | Suppressed | Replaces |

One classification test + one execution test per row. Without this matrix written up front, the four-state-per-field compounds to N × M states across nested inputs and we'll debug cases instead of specifying them.

**Defer until.** Phases 1–3 are complete. Input-field conditions only bite once composite-key inputs are actually wired through the generator, which is Phase 3. Until then, the rewrite pipeline never reaches the code path that would consume them.

## Architectural Decisions Pending

These should be answered before the corresponding phase begins.

| Phase | Decision | Resolution |
|---|---|---|
| 1 | `LookupValuesJoinEmitter` raw-`Row` arrays vs. typed `RowN`? | Raw arrays with typed bind cells — `DSL.val(value, targetColumn.getDataType())` preserves column types at bind time without requiring typed `RowN<…>` in generated code. Raw-types `@SuppressWarnings` scoped to emitted code only. |
| 1 | Value binding: manual `.convert()` at call site, `DSL.cast`, or `DSL.val(value, dataType)`? | `DSL.val(value, dataType)` — typed `Param<T>` invokes the column's own Converter internally and renders a plain JDBC bind (no SQL-level CAST). Collapses the `CallSiteExtraction` branches: `Direct` and `JooqConvert` become the same emission, `EnumValueOf` and `TextMapLookup` add a tiny per-extraction pre-step that produces the Java value before `DSL.val`. |
| 1 | Join kind: USING vs explicit ON condition? | USING — VALUES column labels are emitted to match target column names by construction. `idx` (ordering column) is naturally excluded because it's not on the target. |
| 1 | Emitter shape: inline in `buildQueryLookupRowsMethod`, or extracted helper? | Extracted `<fieldName>InputRows(env, table) -> Row[]` helper generated into the same `*Fetchers` class. The helper is a pure function — unit-testable against a mocked env + real jOOQ table class — and the fetcher body becomes thin composition. |
| 1 | VALUES alias scheme? | `<fieldName>Input` (lowerCamelCase of the lookup field name, suffix `Input`). Unique per fetcher; no collision concerns within a single generated SQL query. |
| 1 | Empty-input semantics? | Short-circuit to empty `Result` before constructing VALUES (jOOQ rejects empty `Row[]`; this also matches legacy `in ([])` → no rows behaviour). |
| 1 | Extraction restriction on `@lookupKey` args? | None — all `CallSiteExtraction` variants flow through `DSL.val`. `EnumValueOf` and `TextMapLookup` produce already-converted Java values (enum instance / mapped string) that jOOQ binds through the target column's DataType. `ContextArg` is unreachable by directive position (`@lookupKey` only applies to `ARGUMENT_DEFINITION`); the emitter asserts as a defensive check. |
| 1 | `TypeConditionsGenerator` explicit `skip LookupField` arm, or "no `GeneratedConditionFilter` → no method" as an implicit consequence? | Explicit arm — self-documents the decoupling. |
| 1 | Multi-list-key correlation: zipped, cartesian, or error? | Zipped (tuple correlation) with length-mismatch as a runtime error. Matches the N × M contract. First schema that needs it pins the length-mismatch policy. |
| 3 | Partial-binding tolerance on a `TableInputArg`: error on unmatched input field, or record as `UnboundBinding`? | Error at classify time. Matches scalar-arg strictness. |
| 3 | Composite-key `LookupColumn` disambiguation: concatenated name, or a `sourcePath` record? | `sourcePath` record — explicit hierarchy survives future nesting (input type embedding another input type). Lands atomically with the binding population. |

## Phase-Aware Test Strategy

One rule covers every phase: **do not assert on emitted method bodies.** Per CLAUDE.md, body-substring and snapshot-of-CodeBlock assertions test the implementation, not the behaviour, and break on every refactor.

Each phase has a natural test surface:

- **Phase 1** —
  - *Pipeline:* `LookupField.filters()` contains no `GeneratedConditionFilter` for lookup-key args; `LookupField.lookupMapping().columns()` matches the declared keys. Structural.
  - *Compilation:* generated lookup fetcher compiles against `graphitron-rewrite-test-spec`'s real jOOQ catalog. Catches type errors, wrong packages, ambiguous overloads.
  - *Execution:* against a real database — `containsExactly(...)` on films retrieved by `[3, 1, 2]` must return films in that order. Preserves N × M semantics.
  - *Smoke:* `GeneratedSourcesSmokeTest` confirms `<Type>Conditions` no longer declares a method for lookup fields.
  - *Emitter-level (optional):* if an isolated emitter test is wanted, assert on jOOQ's rendered SQL (`DSLContext.renderInlined(...)`) — behaviour-level, reflects what the DB will receive.
- **Phase 2** — extend the Phase 1 suite to each child-field lookup variant: pipeline + compilation + execution. Variant-coverage meta-test gains assertions for `LookupTableField` / `SplitLookupTableField` / `RecordLookupTableField` emission arms.
- **Phase 3** — execution test for a composite-key input (`@table` input with two scalar fields, both serving as keys). Pipeline test: `LookupMapping.columns()` carries populated `sourcePath` entries.
- **Phase 4** — the full override-propagation matrix above: one classification test + one execution test per row, reusing nested-input examples from legacy README §645–674.

Structural properties (method names, param types, presence/absence) are the right signal for builder and classifier tests. End-to-end execution is the right signal for emission correctness.

## Out of Scope

- **Mutations.** Input-type arguments for DML use a different mapping (create/update records, not lookup keys). The argument-resolution pipeline here is read-only. Mutations get their own plan.
- **Non-`@table` input types with columns.** The "implicit-table" heuristic in legacy that tries to infer a target table from the input's field names is not reproduced. Inputs must carry `@table` to bind to one.
- **Renaming `LookupTableField` / `SplitLookupTableField` etc.** The sealed-type names already describe the lookup variants accurately. No rename is implied by any decision in this plan.
- **Cursor-format stability** for paginated lookups. `paginated-fields.md` owns that concern; this plan doesn't touch it.

## Cross-plan Ownership

- **`InputColumnBinding`** — canonical definition in this plan, consumed by both this plan (Phase 3) and `legacy-platform-id.md` (item 3). Implementation lands with this plan.
- **Variant-coverage meta-test** — the meta-test (every sealed-root permit has a classification case and a generator branch — see `plan-variant-coverage-meta-test.md`) is extended *in Phase 1* to assert every `LookupField` permit has an emission arm in `TypeFetcherGenerator`. Phase 2 adds assertions for the child-field permits as they come online.

## History

- 2026-04-17 — original plan drafted (classify/project design, four-state `@condition` semantics).
- 2026-04-17 — 2026-04-18: steps 0, 1, 2, 3, 4, 5, 6, 10 landed (foundation complete).
- 2026-04-18 — plan rewritten to reflect foundation status and reframe remaining work as a six-phase generator migration. Steps 7–9 of the old plan absorbed into phases with explicit sub-items and decision points.
- 2026-04-18 — post-review tightening. Two foundation fixes landed: `ColumnArg.isLookupKey` lifts `@lookupKey` into the classifier (projection no longer re-reads SDL); `projectForFilter` rejects fields that trip the lookup gate but produce an empty `LookupMapping`, closing the input-field-`@lookupKey` gap until Phase 3. Plan collapsed from six phases to four: the disposable `LookupConditionEmitter` interstitial is gone; Phase 1 lands VALUES + JOIN directly. Phase 3 composite-key work is a single atomic change (binding population + `sourcePath` + projection + emitter). Phase 4 gains a required override-propagation test matrix. Test strategy rewritten to execution-level assertions only — no `CodeBlock`-substring or snapshot-of-emitted-SQL tests.
- 2026-04-18 (late) — Phase 1 design tightening. Blast-radius spike: zero external consumers — single-site change, no parallel deprecation path. Emitter shape: extracted `<fieldName>InputRows(env, table) -> Row[]` helper + thin fetcher body (pure helper is unit-testable; fetcher is composition). Join: USING (VALUES labels emitted to match target column names). Bind: `DSL.val(value, dataType)` — typed `Param<T>` invokes the column's Converter internally, plain JDBC bind, no SQL-level CAST. No extraction restriction on `@lookupKey`. Alias scheme `<fieldName>Input`. Uniform emission for n=1. Decision table extended with seven Phase-1 resolutions.
- 2026-04-18 (later) — Phase 1 landed. `FieldBuilder.projectFilters` now excludes `isLookupKey` from bodyParams; `LookupValuesJoinEmitter` emits `<fieldName>InputRows(env, table) -> RowN[]` + thin fetcher with USING-join; `TypeConditionsGenerator` skips `LookupField`; validator reads `lookupMapping().columns()` for cardinality. 446 unit tests + 35 execution tests green; `filmById_preservesInputOrder` test proves ordered VALUES+JOIN.
- 2026-04-18 (later still) — Phase 2 scope clarified post-reconnaissance. Four prerequisites added: G5 (plain inline `TableField` subquery) gates Phase 2a; inline emission lives in `TypeClassGenerator.$fields`, not `TypeFetcherGenerator`; `RecordLookupTableField` has no `BatchKey` in the sealed hierarchy and the model question must be resolved before 2c; test-spec schema needs child-lookup fields + Sakila seed additions. Sub-items re-split: 2a (LookupTableField, requires G5), 2b (SplitLookupTableField), 2c (RecordLookupTableField, blocked on model decision).

# Argument Resolution

**Status:** Foundation landed — classification + projection complete; generator-side migration remains.
**Last updated:** 2026-04-18 after commits `441b8f9 … 01d1c91`.

Plan for unified argument classification in the builder, `@condition` directive support, and lookup-field generation. The foundation (builder-side) is in; the generator-side refactor is the substantial work ahead and is the focus of this plan going forward.

## Current State

What's live on `claude/graphitron-rewrite` today:

- **Single-pass classification** (`FieldBuilder.classifyArguments`) produces `List<ArgumentRef>` — a builder-internal sealed hierarchy capturing every GraphQL argument's role (`ColumnArg`, `UnboundArg`, `TableInputArg`, `PlainInputArg`, `OrderByArg`, `PaginationArgRef`, `UnclassifiedArg`).
- **Projection helpers** (`projectFilters`, `projectOrderBySpec`, `projectPaginationSpec`, `projectForLookup`) turn the refs into generation-ready model values. The three legacy passes (`buildFilters` / `buildOrderBySpec` / `buildPaginationSpec`) are gone.
- **`@condition` on both `FIELD_DEFINITION` and `ARGUMENT_DEFINITION`** is supported. The four-state projection table (field-override × per-arg-override) is codified in `projectFilters`. `contextArguments` flow through `ServiceCatalog.reflectTableMethod` into trailing `ParamSource.Context` parameters on the emitted `ConditionFilter`.
- **`LookupField` capability interface** over the four lookup variants (`QueryLookupTableField`, `LookupTableField`, `SplitLookupTableField`, `RecordLookupTableField`), each carrying a non-`Optional` `LookupMapping lookupMapping()`. Populated at classify-time by `projectForLookup`.
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

Below is the problem shape, then a phased plan. The phases are sized deliberately small so each commit is tractable and every intermediate state is shippable.

### The coupling between lookup and `TypeConditionsGenerator`

Today, a `QueryLookupTableField.filters()` contains a `GeneratedConditionFilter` whose `bodyParams()` are the lookup-key arguments. Two generators consume it:

1. **`TypeFetcherGenerator.buildQueryLookupRowsMethod`** — emits `condition = condition.and(FilmConditions.filmByIdCondition(table, filmIdKeys…))`.
2. **`TypeConditionsGenerator.generateConditionsClass`** — emits the method body: `return noCondition().and(table.FILM_ID.in(filmIdKeys))`.

Both iterate the same `filters()` list. Removing lookup keys from `filters()` (a requirement for step 6's `LookupMapping` to become authoritative) therefore also removes them from `TypeConditionsGenerator`'s input — the fetcher must either inline the condition or call a new emitter. The two generators must move together.

### Phase 1 — Extract a lookup-emission helper, keep semantics identical

**Goal.** Introduce `LookupConditionEmitter` (new class in `generators/`) that takes a `LookupMapping` and produces a `CodeBlock` emitting the same IN/EQ condition the fetcher produces today. No generator change yet; no behaviour change.

**Shape.**

```java
final class LookupConditionEmitter {
    /** Emits a WhereFilter-equivalent condition directly from a LookupMapping.
     *  Assumes `env` and `table` are in scope. Produces: variable declarations for
     *  list keys (so jOOQ `convert` overloads disambiguate), then AND-chained
     *  `.in()` / `.eq()` calls. */
    static CodeBlock emitCondition(LookupMapping mapping, String conditionVar);
}
```

The emitter duplicates the logic currently inside `FilmConditions.filmByIdCondition` — but rendered inline, driven by `LookupMapping`, not by `GeneratedConditionFilter`.

**Why first.** A pure-function emitter is easy to unit test (given a `LookupMapping`, assert the emitted `CodeBlock` contains expected fragments). Once it exists, Phase 2 swaps the caller over without any semantic surprise.

**Deliverable.** New file + unit tests. No change to `TypeFetcherGenerator` or `TypeConditionsGenerator` yet. Fetcher output identical.

### Phase 2 — Switch the fetcher to read `LookupField.lookupMapping()`

**Goal.** `TypeFetcherGenerator.buildQueryLookupRowsMethod` (and its child-field siblings once they come online) stops iterating `filters()` for lookup keys and instead calls `LookupConditionEmitter.emitCondition(field.lookupMapping(), "condition")`.

**Builder-side coordinating change.** `projectFilters` stops emitting `GeneratedConditionFilter` entries for arguments that have `@lookupKey` (they're now represented only by `LookupMapping`). `LookupField.filters()` now contains *only* non-key filters (field-level `@condition`, per-arg `@condition`). Non-lookup fields are unchanged.

**`TypeConditionsGenerator` coordinating change.** It no longer emits a method for a lookup field — `extractGeneratedConditionFilter` returns `Optional.empty()` for them because `filters()` no longer contains a `GeneratedConditionFilter` for the key args. The existing filter-by-field-type logic in `TypeConditionsGenerator` may need an explicit "skip `LookupField`" arm to make the invariant visible.

**Verification.** Execution tests in `graphitron-rewrite-test-spec` must pass unchanged. The `<Type>Conditions` class no longer has a method for lookup fields — confirm via `GeneratedSourcesSmokeTest`.

**Risk.** This is the one commit where generated output actually changes. Existing tests catch regressions, but the Conditions-class removal might surprise downstream consumers that `import static`-ed the now-gone methods. Grep the example server and approval tests before merging.

### Phase 3 — VALUES + JOIN emission

**Goal.** Replace the IN/EQ condition with a `VALUES(idx, col1, col2, …) AS input JOIN target ON …` derived-table query. Preserves input ordering and enables true tuple correlation for multi-list-key lookups.

**Scope.** This is a semantic change, not a refactor. Each phase sub-item is independent and shippable:

- **3a.** `LookupValuesJoinEmitter` — new emitter that takes a `LookupMapping` and emits a `CodeBlock` producing the VALUES+JOIN select. Unit-testable in isolation (no generator change).
- **3b.** Switch the fetcher to call `LookupValuesJoinEmitter` instead of `LookupConditionEmitter` for single-list-key or list-with-broadcast-scalars cases.
- **3c.** Upgrade the execution test assertion from `containsExactlyInAnyOrder` to `containsExactly` and add a test that proves input ordering is preserved (e.g. request `[3, 1, 2]`, expect films returned in that order).
- **3d.** Multi-list-key tuple correlation — when multiple `@lookupKey` args are lists, treat them as correlated (zipped by index) rather than cartesian. Requires deciding whether lengths must match or whether padding/truncation applies. Defer until a real schema needs it; single-list is the common case.

**Architectural decision (answer before 3a).** Does `LookupConditionEmitter` (Phase 1) survive, or does `LookupValuesJoinEmitter` supersede it entirely? Likely the former stays for fields where the target database can't do VALUES efficiently — but that's a theoretical concern; postgres and mysql both handle it fine. Recommend superseding, keeping `LookupConditionEmitter` only if a concrete DB compatibility need surfaces.

**Risk.** JavaPoet does not have first-class support for jOOQ's typed `RowN<…>` generics. The emitter will likely use raw `Row` arrays with a `@SuppressWarnings("rawtypes")` comment on the generated code. Acceptable — jOOQ docs recommend the raw-type approach for dynamic VALUES construction.

### Phase 4 — Child-field lookup generators (G5/G6)

**Goal.** Extend the emission path to `LookupTableField` (inline correlated subquery, table-mapped parent), `SplitLookupTableField` (DataLoader-backed, table-mapped parent), and `RecordLookupTableField` (DataLoader-backed, result-mapped parent). All three are stubs today; each throws `UnsupportedOperationException`.

**Why this phase depends on 1–3.** Child-field lookup emission reuses the same VALUES + JOIN shape. If Phase 3 is complete, Phase 4 is primarily about wiring the emitter into the three new dispatch arms of `TypeFetcherGenerator`, not about rewriting SQL.

**Sub-items.**

- **4a.** `LookupTableField` — inline correlated subquery form. Join path (`joinPath`) already resolved by the builder; the emitter writes `select(...) from values join target on … where target joined to parent via joinPath`.
- **4b.** `SplitLookupTableField` and `RecordLookupTableField` — DataLoader rows methods. Generator dispatches per `BatchKey` variant (`RowKeyed` / `RecordKeyed`) when building the key extractor.

**Cross-reference.** Roadmap G5/G6 track this phase as stub completion. Once Phases 1-3 land, G5/G6 reopens from the emission side, not the classification side.

### Phase 5 — Composite keys via `TableInputArg` + `InputColumnBinding`

**Goal.** Populate `TableInputArg.fieldBindings` with real `InputColumnBinding` entries so the lookup emitter can treat an input-typed argument as a source of multiple key columns.

**Today.** `TableInputArg.fieldBindings` is always `List.of()`. The builder knows the input type's `TableRef` but doesn't walk the type's fields to resolve bindings.

**Change.**

- **5a.** In `FieldBuilder.classifyArgument`, for a `TableInputArg`, walk the input type's fields and build `InputColumnBinding(fieldName, columnRef, extraction)` for each field that resolves to a column on `inputTable`. Fields without a matching column produce a classify-time error (or a per-field `UnboundBinding` variant if we want partial tolerance — defer that decision).
- **5b.** Extend `projectForLookup` to walk `TableInputArg.fieldBindings` and add `LookupColumn` entries to the `LookupMapping`. Each binding becomes one key column; the argument name is the input-type arg name plus the input field name (or we introduce a `LookupColumn.sourcePath` to disambiguate).
- **5c.** Extend `LookupValuesJoinEmitter` (Phase 3a) to read the composite-key case: for an input-typed arg, extract its value once at the fetcher, then read each bound field for row construction.

**Architectural decision (answer before 5a).** Does `TableInputArg.fieldBindings` stay builder-internal (populated and read only within `FieldBuilder`) or become a model component on some field variant? The cross-plan ownership note in this document commits `InputColumnBinding` as a model type, so the bindings *can* surface on model records. But `TableInputArg` is builder-internal. Recommend: the bindings flow through `LookupMapping.columns()` unchanged — one `LookupColumn` per input field. Generators never see `TableInputArg` or `InputColumnBinding` directly.

### Phase 6 — `@condition` on `INPUT_FIELD_DEFINITION`

**Goal.** Support `@condition` on fields *inside* input types (the third legal position per `directives.graphqls`; currently deferred).

**Scope.** This is an input-type-classification concern, not an argument-classification one. The GraphQL spec permits `@condition` on `INPUT_FIELD_DEFINITION`, and legacy README §645–674 documents the semantics: each input-type field with `@condition` contributes its own predicate, scoped to that field. Nested input types can each carry their own conditions.

**Sub-items.**

- **6a.** Extend `InputField` to carry an optional `ArgConditionRef` — the per-input-field `@condition` directive, reflected at type-build time.
- **6b.** When an input-type arg is used at a call site, its per-field conditions become additional `ConditionFilter` entries in the lookup emitter's output.
- **6c.** Override propagation: an outer-level `@condition(override: true)` on the arg suppresses inner fields' auto-predicates but not their explicit `@condition` methods (per legacy semantics).

**Defer until.** Phases 1-5 are complete. Input-field conditions only bite once composite-key inputs are actually wired through the generator, which is Phase 5. Until then, the rewrite pipeline never reaches the code path that would consume them.

## Architectural Decisions Pending

These should be answered before the corresponding phase begins:

| Phase | Decision | Lean |
|---|---|---|
| 1 | Does `LookupConditionEmitter` live in `generators/` or a new `generators/lookup/` subpackage? | Subpackage once two emitters coexist — pre-empt a one-file rename in Phase 3. |
| 2 | Should `TypeConditionsGenerator` get an explicit `skip LookupField` arm, or is "no `GeneratedConditionFilter` → no method" sufficient? | Explicit arm — self-documents the decoupling. |
| 3a | `LookupValuesJoinEmitter` raw-`Row` arrays vs. typed `RowN`? | Raw arrays — jOOQ's idiomatic form for dynamic VALUES. Suppression scoped to emitted code, not generator code. |
| 3d | Multi-list-key correlation: zipped, cartesian, or error? | Zipped (tuple correlation) with length-mismatch as a classify-time or runtime error. Matches the N × M contract. Defer concrete decision until a schema demands it. |
| 5a | Partial-binding tolerance: error on an unmatched input field, or record as `UnboundBinding`? | Error at classify time. Matches the strictness of the scalar-arg column-resolution path (which already produces `UnboundArg` → field error). |
| 5b | Composite-key `LookupColumn.argName` disambiguation: `argName + "." + fieldName`, or introduce a `sourcePath` field? | `sourcePath` record — explicit hierarchy survives future nesting (e.g. an input type embedding another input type). |

## Phase-Aware Test Strategy

Each phase has a natural test surface. Rough taxonomy:

- **Phase 1** — unit tests on `LookupConditionEmitter` (given a `LookupMapping`, assert emitted `CodeBlock` substrings). Fast, targeted, no full-build dependency.
- **Phase 2** — pipeline tests verifying `LookupField.filters()` no longer contains lookup args + execution tests verifying behaviour is preserved. `GeneratedSourcesSmokeTest` confirms the `<Type>Conditions` class shrinks by the expected methods.
- **Phase 3a** — unit tests on `LookupValuesJoinEmitter`. Snapshot tests of emitted SQL shape are acceptable here — the emitter is one function, and a snapshot test locks the shape without asserting brittle line-by-line content.
- **Phase 3b/3c** — execution tests with ordered assertions (`containsExactly`), exercising the scalar-broadcast and single-list-key shapes end-to-end.
- **Phase 4** — extend pipeline + execution coverage to child-field lookups; reuse the emitter unit tests.
- **Phase 5** — execution test for a composite-key input (e.g. a lookup whose single arg is an `@table` input with two scalar fields, both serving as keys).
- **Phase 6** — pipeline + execution coverage for `@condition` on input-type fields; reuse the nested-input examples from legacy README §645–674.

The generation-thinking principle applies throughout: **do not assert on emitted method bodies**. Structural properties (method names, param types, presence/absence) are the right signal for builder and classifier tests. End-to-end behaviour is the right signal for emitter tests.

## Out of Scope

- **Mutations.** Input-type arguments for DML use a different mapping (create/update records, not lookup keys). The argument-resolution pipeline here is read-only. Mutations get their own plan.
- **Non-`@table` input types with columns.** The "implicit-table" heuristic in legacy that tries to infer a target table from the input's field names is not reproduced. Inputs must carry `@table` to bind to one.
- **Renaming `LookupTableField` / `SplitLookupTableField` etc.** The sealed-type names already describe the lookup variants accurately. No rename is implied by any decision in this plan.
- **Cursor-format stability** for paginated lookups. `paginated-fields.md` owns that concern; this plan doesn't touch it.

## Cross-plan Ownership

- **`InputColumnBinding`** — canonical definition in this plan, consumed by both this plan (Phase 5) and `legacy-platform-id.md` (step 6). Roadmap P2 #5 tracks the shared-type agreement.
- **Variant coverage meta-test** — the roadmap's P1 #1 meta-test (every sealed-root permit has a classification case and a generator branch) should be extended to include `LookupField` permits as they gain emission arms across Phases 2 and 4.

## History

- 2026-04-17 — original plan drafted (classify/project design, four-state `@condition` semantics).
- 2026-04-17 — 2026-04-18: steps 0, 1, 2, 3, 4, 5, 6, 10 landed (foundation complete).
- 2026-04-18 — plan rewritten to reflect foundation status and reframe remaining work as a six-phase generator migration. Steps 7-9 of the old plan are absorbed into Phases 1-5 with explicit sub-items and decision points.

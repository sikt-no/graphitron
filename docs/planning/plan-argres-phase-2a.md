# argres Phase 2a — Inline `LookupTableField` emission

> **Status:** Pending Review
>
> Phase 2a of the [argument-resolution plan](argument-resolution.md). G5 has landed, clearing the inline-subquery prerequisite. Scope: the `ChildField.LookupTableField` variant only (inline, table-mapped parent). `SplitLookupTableField` is Phase 2b; `RecordLookupTableField` is Phase 2c (blocked on the `BatchKey` model question).

`LookupTableField` is a table-mapped child field carrying both a `@reference(path:)` join path AND `@lookupKey` arguments. The classifier builds it today; the fetcher emits a stub (`NOT_IMPLEMENTED_REASONS`). G5 established the inline-subquery emission pattern in `TypeClassGenerator.$fields`. Phase 2a layers a VALUES + JOIN keyset onto that pattern so the inline subquery narrows by the `@lookupKey` args in addition to the FK-path correlation.

## Current State

- **Builder.** `FieldBuilder.java:251-254` constructs `ChildField.LookupTableField` with resolved `joinPath`, `filters`, `orderBy`, `pagination`, and a non-empty `LookupMapping`. The `LookupMapping`'s `columns()` lists the `@lookupKey` columns on the *target* table. Classifier coverage exists; the field currently has no execution coverage because no fixture exercises it.
- **Generator.** `TypeFetcherGenerator.NOT_IMPLEMENTED_REASONS` at line 199 routes `LookupTableField` to `stub(f)`. `TypeClassGenerator.$fields` has no arm for it — the `default` no-op skips projection. No method is emitted.
- **Test-spec schema.** `graphitron-rewrite-test-spec/src/main/resources/graphql/schema.graphqls` has zero child-level `@lookupKey` fields. Root-level lookups (`filmById`, etc.) exercise Phase 1; child-level does not.
- **Available infrastructure.** `InlineTableFieldEmitter` (G5) emits the correlated-subquery FK join chain. `LookupValuesJoinEmitter` (Phase 1) emits the VALUES table + USING-join for the root-lookup shape, with a companion input-rows helper. Neither class handles the composite case yet.

## Design

### Emission locus

Same as G5: inline in `TypeClassGenerator.$fields`. A child `LookupTableField` becomes one aliased `DSL.multiset(...)` entry in the parent's `$fields` projection list, identical in outer shape to a `TableField` but with an added `.join(<values>).using(<cols>)` inside the correlated subquery.

`LookupTableField` moves from `NOT_IMPLEMENTED_REASONS` to `PROJECTED_LEAVES`. The four-way partition test is already in place and will enforce disjoint-cover.

### Shape

One uniform shape across list and single cardinality — following the G5 implementation finding that `DSL.multiset` handles both uniformly.

```java
case "filmsByIds" -> {
    Film f0 = Tables.FILM.as(table.getName() + "_f0");
    RowN[] rows = filmsByIdsInputRows(sf, f0);
    if (rows.length == 0) {
        fields.add(DSL.multiset(DSL.select(Film.$fields(sf.getSelectionSet(), f0, env))
            .from(f0).where(DSL.falseCondition())).as("filmsByIds"));
    } else {
        // VALUES column labels — "idx" + each target column's SQL name (lowercase, not the
        // jOOQ Java field name). USING compares rendered identifiers against the derived
        // table's labels; mismatched casing breaks USING on Postgres. See
        // LookupValuesJoinEmitter.java:195-202 for the authoritative rule.
        Table<?> input = DSL.values(rows).as("filmsByIdsInput", "idx", "film_id");
        fields.add(DSL.multiset(
            DSL.select(Film.$fields(sf.getSelectionSet(), f0, env))
                .from(f0)
                .join(input).using(f0.FILM_ID)
                .where(<parent correlation against `table`>)
                .orderBy(input.field("idx"))
        ).as("filmsByIds"));
    }
}
```

The inner subquery combines two predicates:
1. **Parent-row correlation** — as in G5, derived from `joinPath.get(0)` against the parent alias (`table` for top-level, or a deeper alias in nested contexts). Uses G5's cardinality-driven FK-direction branching (`Single` → parent holds FK, `List` → parent is PK side) via `JoinPathEmitter.emitCorrelationWhere`.
2. **Keyset narrowing via VALUES** — `.join(DSL.values(rows).as("<name>Input", "idx", "<col_sql_name>"…)).using(<target-cols>)`. Preserves input ordering via `orderBy(input.field("idx"))`.

The two predicates **AND** — the result is the intersection of FK-correlated rows and the lookup keyset. This is the semantic a child-level `@lookupKey` on a table-mapped parent should carry: a user asking `store { filmsByIds(film_id: [1,2]) }` expects "films 1 and 2 that are actually stocked at this store", not "films 1 and 2 regardless of store". If the keyset contains IDs unrelated to the parent, those entries drop out (and the input-order slot is empty).

**Empty-input shape (first branch above)** is not the same construct as Phase 1's empty-input short-circuit. Phase 1 returns `dsl.newResult()` at the Java level (a complete early return from a fetcher method). Phase 2a's emitter sits inside a SELECT-list slot and must emit an SQL expression that produces an empty multiset; `DSL.multiset(select ... where(falseCondition()))` is the SQL-level equivalent. Both deliver the same observable result (empty collection) through different mechanisms.

**Connection wrapper note.** G5 C1's classifier rejection of `@asConnection` applies only to the `TableField` branch (`FieldBuilder.java:260-263`). The `LookupTableField` branch (lines 251-254) does *not* currently reject `Connection`. Phase 2a must either extend the rejection or emit an unclassified error — see Open Decision 6.

Single-cardinality child LookupTableFields are unusual but legal; they follow the G5 convention (`.limit(1)` inside, single-value `DataFetcher` unwrap in wiring). With `@lookupKey` this resolves to "the first matched row by input order" — a canonical ordering tiebreaker that may or may not be what the user intended. See Open Decision 7.

### Argument extraction: `env.getArgument` vs `sf.getArguments`

**Decision needed.** Phase 1's `LookupValuesJoinEmitter.buildInputRowsMethod` extracts args via `env.getArgument(name)` (line 114-117). That works for root queries where the env's root args *are* the field's args. For child fields, the `@lookupKey` args are on the child's `SelectedField`, not on the outer env's root. The helper must read `sf.getArguments().get(name)` instead.

Options:
- **(A)** Extend `LookupValuesJoinEmitter` with a sibling `buildChildInputRowsMethod(LookupField, ClassName)` that reads from a `SelectedField` parameter instead of `env`. The row-construction core (typed `DSL.val(v, col.getDataType())` per cell, `RowN[]` assembly, empty-input short-circuit) is shared via a common private helper.
- **(B)** Pass extracted values as parameters to the helper; inline extraction at the call site. The helper becomes pure row-construction without env/sf awareness.
- **(C)** Split the helper: one root variant, one child variant. Two similar-but-not-identical helpers on `*Fetchers` / type class respectively.

Recommend (A) — smallest surface delta, preserves the pure-function testability of Phase 1's helper, single source of truth for row-construction.

**Signature hazard for (A).** `SelectedField.getArguments()` returns `Map<String, Object>`, not a typed accessor. The child variant therefore replaces the typed `env.getArgument(name)` with `sf.getArguments().get(name)` plus a cast — `List<?>` for list args, `Object` for scalars — same as Phase 1 after the type-inference is satisfied. No cast is needed at the `DSL.val(value, dataType)` site because the row-construction core already treats cells as `Object`. The extension therefore differs only in the extraction statements emitted, not the row-building loop.

### Helper method placement

Root `QueryLookupTableField` emits its input-rows helper into the `*Fetchers` class (Phase 1). For a child `LookupTableField` projected by `$fields`, the helper naturally lives on the *type* class (e.g. `Customer.filmsByIdsInputRows`), next to `$fields`. `TypeClassGenerator` gains method emission for each child-lookup field in scope.

Alternative: inline the row-construction directly in the switch-arm body. Given the row-building loop is non-trivial (iterate args, typed `DSL.val` per cell), a named helper is clearer.

### FK direction, self-refs

Inherit G5's cardinality-driven FK-direction discriminator (`Single` → parent holds FK, `List` → parent is PK side). Self-referential `LookupTableField` is legal — same rules.

The `@lookupKey` direction is independent of FK direction: `@lookupKey` args match columns on the *target* table. The builder's `LookupMapping.columns()` already resolves to target-table columns; the emitter uses those directly in the VALUES column labels + USING list.

### `ConditionJoin` in `joinPath`

Same limitation as G5: `InlineTableFieldEmitter.buildSwitchArmBody` throws at codegen for any `ConditionJoin` step (classification-vocabulary item 5). Phase 2a inherits this — a `LookupTableField` with a `ConditionJoin` step emits a runtime-throwing stub until item 5 resolves target-table metadata for condition joins.

### Empty-input short-circuit

A child lookup field with an empty list of keys should return an empty multiset — not the full target-table correlated result. The emitter emits an `if (rows.length == 0)` check that short-circuits to an empty multiset wrapper (`DSL.multiset(select…where(falseCondition()))` or equivalent), matching Phase 1's root-level behaviour.

### Partition migration

- Remove `ChildField.LookupTableField.class` from `NOT_IMPLEMENTED_REASONS`.
- Add it to `PROJECTED_LEAVES`.
- `TypeFetcherGenerator.generateTypeSpec`'s switch arm changes from `stub(f)` to a no-op `{ }` (same as `TableField` today).
- The four-way partition meta-test catches any misalignment.

### Builder-invariant assumptions

Carried over from Phase 1 and G5:
- `LookupTableField.lookupMapping().columns()` is non-empty (classifier rejects empty mappings at classify time).
- `LookupTableField.joinPath()` is non-empty (must navigate from parent).
- All `@lookupKey` args are scalar. Composite-key input-type lookups are Phase 3.

**Not yet invariant — must be established in C1:**
- `LookupTableField.returnType().wrapper()` is `Single` or `List`, not `Connection`. G5 C1's classifier rejection lives in the `TableField` construction branch (`FieldBuilder.java:260-263`) and does *not* cover `LookupTableField`. C1 must either extend the rejection (preferred — keeps the builder invariant uniform across all inline variants) or teach the emitter to handle `Connection` (rejected — duplicates G5's precedent).

## Commit Structure

Two commits, ordered.

### C1 — Classifier rejection + emitter + switch arm + partition migration + tests

One atomic change because the partition disjoint-cover property fails unless all parts land together.

- **Extend G5 C1's classifier rejection to `LookupTableField`.** `FieldBuilder.java:251-254` currently constructs `LookupTableField` regardless of wrapper. Insert a `FieldWrapper.Connection` check ahead of the construction, reusing G5's error message modulo the variant name: "`@asConnection` on inline (non-`@splitQuery`) LookupTableField is not supported; add `@splitQuery` for batched connection semantics". Establishes the builder invariant Phase 2a's emitter relies on. Blast radius: whatever test fixtures or production schemas pair `@asConnection` with `@lookupKey` (audit via grep before landing — likely zero).
- **Extend `LookupValuesJoinEmitter`** with a `buildChildInputRowsMethod(LookupField, ClassName)` sibling that reads from a `SelectedField` parameter instead of `DataFetchingEnvironment`. Share the row-construction core (typed `DSL.val(v, col.getDataType())` per cell, `RowN[]` assembly, empty-input short-circuit) with the root variant through a common private helper. Package-private is fine — `TypeClassGenerator` lives in the same `generators` package as `LookupValuesJoinEmitter`.
- **New emitter class or extension.** Either:
  - Add a `LookupTableField` branch to `InlineTableFieldEmitter.buildSwitchArmBody`, or
  - Introduce `InlineLookupTableFieldEmitter` as a dedicated class that composes `InlineTableFieldEmitter`'s join/correlation logic with `LookupValuesJoinEmitter`'s VALUES + USING pattern.
  **Decision needed at implementation time** — prefer extension if the shared logic with G5 dominates; prefer a new class if the divergence is substantial.
- **`TypeClassGenerator.generateForType` gathering.** The current implementation (`TypeClassGenerator.java:69-74`) filters `ChildField.TableField` into a dedicated list that flows into `buildTypeSpec`. C1 adds a parallel `lookupTableFields` gathering (or refactors the pair into a single `TableTargetField`-gathering step that splits in the switch body). `buildTypeSpec`'s signature grows to accept the new list.
- **`TypeClassGenerator.$fields`** — add `case ChildField.LookupTableField` arm alongside the existing `TableField` arm. Also emit the per-field input-rows helper method on the type class (alongside `$fields`).
- **`TypeFetcherGenerator`** — move `LookupTableField` from `NOT_IMPLEMENTED_REASONS` (line 199-200) to `PROJECTED_LEAVES` (line 140); the generator switch arm at line 315 changes from `stub(f)` to `{ }` (same as the `TableField` arm at line 314).
- **`TypeFetcherGenerator.buildWiringEntry` dispatch gap.** The wiring entry at line 1068 matches `ChildField.TableField` explicitly — single-cardinality emits an unwrapping lambda DataFetcher, list-cardinality emits a `ColumnFetcher` with `DSL.field(fieldName)`. After C1's partition move, a `LookupTableField` is projected by `$fields` as a multiset in the parent's record (identical outer shape to `TableField`) but would fall through the wiring switch to the default method-reference line at 1085 — pointing at a non-existent fetcher method. C1 must extend the `instanceof ChildField.TableField` branch to cover `LookupTableField` (prefer: match on the common `TableTargetField` capability, guarded by "projected in `$fields`"). Same two-path internal: single-cardinality unwrap vs list-cardinality `ColumnFetcher`.
- **Pipeline test.** Extend `TableFieldPipelineTest` (or add a sibling `LookupTableFieldPipelineTest`) — SDL with a child `@lookupKey` field produces a `TypeSpec` whose `$fields` has an arm, no fetcher method emitted, helper method present on the type class. Also assert the classifier-rejection case: `@asConnection` + `@lookupKey` on an inline path returns `UnclassifiedField`.
- **Compilation gate — note the limitation.** `mvn compile -pl :graphitron-rewrite-test-spec -Plocal-db` passes trivially after C1 because the existing test-spec schema contains zero child-lookup fields; no new generated code is exercised. The real compile-tier coverage lands with C2's schema fixture. C1 still runs the build to guarantee no regression in G5-era generated code.

### C2 — Schema fixtures + execution tests

Must land with C1's emission working, but in a separate commit for clean bisectability.

- **Schema fixture.** Add at least one child `LookupTableField` to `schema.graphqls`. Candidate (pending init.sql audit):
  - `Store.filmsByIds(film_id: [Int] @lookupKey): [Film!]!` via `inventory` join, OR
  - `Film.actors(actor_id: [Int] @lookupKey): [Actor!]!` via `film_actor` join, OR
  - simpler: `Customer.paymentsByIds(payment_id: [Int] @lookupKey): [Payment!]!` if payments are in the fixture DB.
- **`init.sql` audit.** Check `graphitron-rewrite-test-fixtures/src/main/resources/init.sql` for the required tables. If the chosen target table is not in the Sakila subset today, add schema + seed rows with the commit.
- **Execution tests (`GraphQLQueryTest`).**
  - Input-order preservation: `[3, 1, 2]` → three rows in that order (matches Phase 1's test pattern).
  - Empty input: `[]` → empty list.
  - Single-key / multi-key: depending on chosen fixture.
  - Nested within a parent query (e.g. `stores { filmsByIds(film_id: [1, 2]) { title } }`).

## Test Strategy

Per CLAUDE.md:

| Surface | What is verified |
|---|---|
| Pipeline test | SDL → classified schema → generated TypeSpec: switch-arm presence, helper method signature, no fetcher method. |
| Compilation | `mvn compile -pl :graphitron-rewrite-test-spec -Plocal-db` catches type errors in the nested multiset + values-join subquery. |
| Execution | `mvn test -pl :graphitron-rewrite-test-spec -Plocal-db` — queries against real PG return correct nested + ordered shape. |

No `CodeBlock`-substring assertions on emitted method bodies.

## Open Decisions

Answer at Draft-review or implementation time. Items 1–3 have concrete recommendations and can be resolved by reviewer assent; items 4–7 need implementation-time investigation.

1. **Argument extraction locus.** `env.getArgument` vs `sf.getArguments().get`? Recommend (A) above — extend `LookupValuesJoinEmitter` with a `SelectedField`-reading variant sharing the row-construction core.
2. **Emitter class layout.** Extend `InlineTableFieldEmitter` with a `LookupTableField` branch, or introduce `InlineLookupTableFieldEmitter`? Decide when the C1 emitter diff is visible. Heuristic: if the branch body is <30 lines and the FK/correlation/orderBy shared logic dominates, extend; if the VALUES + USING + child-helper plumbing dwarfs the shared portion, split.
3. **Helper method placement.** Input-rows helper on the type class (e.g. `Customer`) vs. inlined into the switch-arm body? Recommend: type-class helper, matching Phase 1's separation-of-concerns rationale.
4. **Schema fixture choice.** Which Sakila table best exercises a child LookupTableField without forcing large init.sql additions? Resolve alongside C2.
5. **Empty-input inner shape.** `DSL.multiset(select … .where(falseCondition()))` vs a pre-built empty-multiset constant? Any SQL dialect concerns? (Plan recommends falseCondition because it's clearly an SQL-level expression and requires no additional helper; revisit only if it produces unoptimisable plans on Postgres.)
6. **`@asConnection` + `@lookupKey` classifier behaviour.** Phase 2a extends G5 C1's rejection to `LookupTableField` (see C1 bullet 1). **Audit confirmed 2026-04-19** — zero fixtures or production schemas pair the two directives (`grep -r @asConnection graphitron-rewrite-test/ graphitron-example-spec/` returns no matches; `@lookupKey` appears only with list-wrapped returns in test-spec). Extend without a rollout plan.
7. **Single-cardinality + `@lookupKey` semantics.** A child lookup field returning a single object (e.g. `Customer.latestAddress(address_id: [Int!]! @lookupKey): Address`) resolves to "first matched row by input order" via `.limit(1)`. Option X: accept as-is and document the semantics in a classification comment. Option Y: reject at classifier time — Single + `@lookupKey` is almost always a schema bug (why pass a list of keys if only one row is returned?). Option Y matches Graphitron's "reject ambiguous combinations" style elsewhere (see G5 C1 for `@asConnection` on inline). Recommendation: Option Y unless existing fixtures demonstrate a use case.

## Cross-plan Dependencies

- **Prerequisite: G5** — landed (`0ac6048d`).
- **Sibling: classification-vocabulary item 5** (`ConditionJoin` target-table metadata). Phase 2a inherits G5's runtime-throwing stub for `ConditionJoin` steps; resolution is independent.
- **Feeds: Phase 2b** (`SplitLookupTableField` — DataLoader rows method, table-mapped parent). 2b reuses the VALUES + USING derived-target pattern Phase 2a establishes but wraps it in a DataLoader rows method.
- **Feeds: Phase 2c** (`RecordLookupTableField`). Blocked on the `BatchKey` model question (see argres Phase 2 prerequisites).

## Out of Scope

- **`SplitLookupTableField`** — Phase 2b.
- **`RecordLookupTableField`** — Phase 2c.
- **Composite-key lookup via input types** — Phase 3.
- **`@condition` on input fields** — Phase 4.
- **`ConditionJoin` runtime behaviour** — classification-vocabulary item 5.

## History

- **2026-04-19 (Pending Review, reviewer pass)** — two class-level Javadoc drifts corrected while reading the implementation. (a) `InlineLookupTableFieldEmitter` class doc still said "VALUES + USING keyset" after the C2 USING→ON switch; updated to "VALUES + JOIN keyset" with a paragraph explaining why `ON` rather than `USING` (junction-table column-name collision on Postgres). (b) `LookupValuesJoinEmitter` class doc only described the root-lookup path; after Phase 2a, this class serves both root (USING, `*Fetchers`, env-based args) and child (ON layered by `InlineLookupTableFieldEmitter`, type class, `SelectedField`-based args). Rewrote the doc to enumerate both paths explicitly. No code changes; `mvn -pl :graphitron-rewrite test` green (465/465). Implementation review turned up no functional issues — classifier rejections (Connection, Single), partition migration, wiring dispatch, and execution tests all line up with the plan. The pre-existing `.code().toString().contains(...)` body-substring pattern used in `LookupTableFieldPipelineTest` matches the pattern in `TableFieldPipelineTest` and other sibling pipeline tests; banning it repo-wide is out of scope for this plan.
- **2026-04-19 (In Progress → Pending Review)** — C1 + C2 landed on `claude/review-platformid-implementation-WWUwK`. Open Decisions resolved:
  - **OD 1 (arg extraction).** Adopted (A): `LookupValuesJoinEmitter.buildChildInputRowsMethod` sibling reads from `SelectedField`, shared row-construction core via private helper.
  - **OD 2 (emitter layout).** Split: `InlineLookupTableFieldEmitter` is a new class; `JoinPathEmitter`-level shared helpers already cover alias/correlation/filter emission, so the VALUES+ON+child-helper body lives independently from `InlineTableFieldEmitter`.
  - **OD 3 (helper placement).** Adopted: input-rows helper emitted as a private method on the type class (e.g. `Film.actorsInputRows`), not inlined.
  - **OD 4 (fixture).** Chose `Film.actors` via `film_actor` junction (two-hop `@reference(path:)`). Seeds added to `init.sql`; no DDL change (schema version stays at 1.1).
  - **OD 5 (empty-input shape).** Adopted: `DSL.multiset(select … .where(falseCondition()))` inside the Java `rows.length == 0` branch. No SQL-dialect concerns observed on Postgres.
  - **OD 7 (Single + @lookupKey).** Adopted Option Y: classifier rejects `FieldWrapper.Single` on inline `@lookupKey` LookupTableField with a clear diagnostic. Pipeline-test case covers it.
- **2026-04-19 (C2 emitter bug: USING → ON)** — execution tests surfaced a Postgres "common column name appears more than once in left table" error when the FK chain brings in a junction table (e.g. `film_actor`) that shares a column name with the lookup key (`actor_id`). USING requires the named column to appear exactly once on each side; the junction violates that. Switched `.using(col…)` to an explicit `.on(terminal.COL.eq(input.field(terminal.COL)))`. `Table.field(Field)` dereferences by name, so the typing stays correct without casts. Phase 1's root-lookup shape still uses USING (no FK chain → no ambiguity).
- 2026-04-19 — drafted after G5 landed (`0ac6048d`) and cleared the inline-subquery prerequisite. Status: Draft. Five open decisions pinned for reviewer / implementation.
- 2026-04-19 — reviewer pass: (a) fixed the Shape example's VALUES column label — `"FILM_ID"` → `"film_id"` (SQL name, not Java name; `LookupValuesJoinEmitter.java:195-202` is authoritative). (b) Made the **parent-correlation AND lookup-keyset** semantics explicit; prior draft's shape AND'd them without justifying the intent. (c) Clarified that the empty-input shape (`DSL.multiset(...).where(falseCondition())`) is the SQL-expression equivalent of Phase 1's Java-level `dsl.newResult()` — same observable, different construct. (d) Flagged the **Connection classifier gap**: G5 C1's rejection lives in the `TableField` branch only (`FieldBuilder.java:260-263`); `LookupTableField` accepts `Connection` today. C1 must extend the rejection. (e) Added Open Decision 6 (classifier-rejection grep-before-landing) and Open Decision 7 (Single-cardinality + `@lookupKey` semantics — recommend classifier rejection). (f) Renamed C1 header to reflect the added classifier-rejection step; tightened the compile-gate note (trivial until C2's fixture lands). (g) Pinned cardinality-driven FK-direction branching as inherited from G5, rather than left implicit. (h) Nailed the helper-signature hazard — `SelectedField.getArguments()` is a raw `Map<String, Object>`, casts required for list args.
- 2026-04-19 (second iteration) — three integration-point additions to C1 that the reviewer pass missed. (1) `TypeClassGenerator.generateForType`'s gathering step (`TypeClassGenerator.java:69-74`) filters only `TableField` today; C1 must add a parallel `LookupTableField` gathering (or refactor to a shared `TableTargetField` step that splits in the switch body). `buildTypeSpec`'s signature grows. (2) `TypeFetcherGenerator.buildWiringEntry` (line 1068) matches `ChildField.TableField` explicitly and emits the single-unwrap-lambda / list-`ColumnFetcher` wiring. After partition migration, an unextended `LookupTableField` falls through to the default method-reference line (1085) pointing at a non-existent fetcher method — a concrete regression vector. C1 extends the branch (prefer: match on the `TableTargetField` capability guarded by "projected in `$fields`"). (3) Open Decision 6's audit resolved — `grep -r @asConnection` across `graphitron-rewrite-test/` + `graphitron-example-spec/` returns zero matches; `@lookupKey` appears only with list-wrapped returns in test-spec. Extend the classifier rejection without a rollout plan.
- 2026-04-19 (Approved) — Draft → Approved on user direction. User acts as the independent reviewer authority for the Draft → Approved transition; the author (initial Draft commit `ddb153bc`, second iteration `c871db22`) must not be the implementer. Open Decisions 1–3 adopt their recommended resolutions; 4, 5, 7 remain for implementation-time investigation; 6 is already resolved by the audit above.

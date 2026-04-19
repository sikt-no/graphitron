# argres Phase 2a — Inline `LookupTableField` emission

> **Status:** Draft
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
        Table<?> input = DSL.values(rows).as("filmsByIdsInput", "idx", "FILM_ID");
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
1. **Parent-row correlation** — as in G5, derived from `joinPath.get(0)` against the parent alias (`table` for top-level, or a deeper alias in nested contexts).
2. **Keyset narrowing via VALUES** — `.join(DSL.values(rows).as("<name>Input", "idx", "COL"…)).using(<target-cols>)`. Preserves input ordering via `orderBy(input.field("idx"))`.

Single-cardinality child LookupTableFields are unusual but legal; they follow the G5 convention (`.limit(1)` inside, single-value `DataFetcher` unwrap in wiring).

### Argument extraction: `env.getArgument` vs `sf.getArguments`

**Decision needed.** Phase 1's `LookupValuesJoinEmitter.buildInputRowsMethod` extracts args via `env.getArgument(name)`. That works for root queries where the env's root args *are* the field's args. For child fields, the `@lookupKey` args are on the child's `SelectedField`, not on the outer env's root. The helper must read `sf.getArguments().get(name)` instead.

Options:
- **(A)** Extend `LookupValuesJoinEmitter.buildInputRowsMethod` with a second overload that takes a `SelectedField` parameter and reads from it.
- **(B)** Pass extracted values as parameters to the helper; inline extraction at the call site. The helper becomes pure row-construction without env/sf awareness.
- **(C)** Split the helper: one root variant, one child variant. Two similar-but-not-identical helpers on `*Fetchers` / type class respectively.

Recommend (A) — smallest surface delta, preserves the pure-function testability of Phase 1's helper, single source of truth for row-construction.

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
- `LookupTableField.returnType().wrapper()` is `Single` or `List` (not `Connection` — classifier rejects `@asConnection` on inline paths per G5 C1).

## Commit Structure

Two commits, ordered.

### C1 — Emitter + switch arm + partition migration + tests

One atomic change because the partition disjoint-cover property fails unless all parts land together.

- **Extend `LookupValuesJoinEmitter`** with a `buildChildInputRowsMethod(LookupField, ClassName)` overload (or a second method variant) that reads from a `SelectedField` parameter instead of `DataFetchingEnvironment`. The row-construction core (typed `DSL.val(v, col.getDataType())` per cell, `RowN[]` assembly) is shared with the root variant.
- **New emitter class or extension.** Either:
  - Add a `LookupTableField` branch to `InlineTableFieldEmitter.buildSwitchArmBody`, or
  - Introduce `InlineLookupTableFieldEmitter` as a dedicated class that composes `InlineTableFieldEmitter`'s join/correlation logic with `LookupValuesJoinEmitter`'s VALUES + USING pattern.
  **Decision needed at implementation time** — prefer extension if the shared logic with G5 dominates; prefer a new class if the divergence is substantial.
- **`TypeClassGenerator.$fields`** — add `case ChildField.LookupTableField` arm. Also emit the per-field input-rows helper method on the type class (alongside `$fields`).
- **`TypeFetcherGenerator`** — move `LookupTableField` from `NOT_IMPLEMENTED_REASONS` to `PROJECTED_LEAVES`; switch arm becomes `{ }`.
- **Pipeline test.** Extend `TableFieldPipelineTest` (or add a sibling `LookupTableFieldPipelineTest`) — SDL with a child `@lookupKey` field produces a `TypeSpec` whose `$fields` has an arm, no fetcher method emitted, helper method present on the type class.
- **Compilation gate.** `mvn compile -pl :graphitron-rewrite-test-spec -Plocal-db` must pass.

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

Answer at Draft-review or implementation time.

1. **Argument extraction locus.** `env.getArgument` vs `sf.getArguments().get`? Recommend (A) above — extend `LookupValuesJoinEmitter` with a `SelectedField`-reading variant.
2. **Emitter class layout.** Extend `InlineTableFieldEmitter` with a `LookupTableField` branch, or introduce `InlineLookupTableFieldEmitter`? Decide when the C1 emitter diff is visible.
3. **Helper method placement.** Input-rows helper on the type class (e.g. `Customer`) vs. inlined into the switch-arm body? Recommend: type-class helper, matching Phase 1's separation-of-concerns rationale.
4. **Schema fixture choice.** Which Sakila table best exercises a child LookupTableField without forcing large init.sql additions? Resolve alongside C2.
5. **Empty-input inner shape.** `DSL.multiset(select … .where(falseCondition()))` vs a pre-built empty-multiset constant? Any SQL dialect concerns?

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

- 2026-04-19 — drafted after G5 landed (`0ac6048d`) and cleared the inline-subquery prerequisite. Status: Draft. Five open decisions pinned for reviewer / implementation.

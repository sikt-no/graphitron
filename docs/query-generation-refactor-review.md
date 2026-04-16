# Review: Query Generation Refactor Plan

Review of [query-generation-refactor.md](query-generation-refactor.md).

## Overall

The core idea is right: separating projection (`$fields`) from execution unlocks G5 inline nesting
by making the table alias a first-class parameter. The G5 section clearly explains why this matters,
and the recursive multiset pattern it sets up is sound. Several issues need addressing before
implementation.

---

## Issues

### 1. Step ordering will break compilation

Steps 1 and 2 modify `TypeClassGenerator` (rename `fields` → `$fields`, remove execution methods).
Step 3 is what updates `TypeFetcherGenerator` to stop calling those removed methods. As written,
executing steps 1+2 before step 3 leaves the fetcher generator calling `Type.selectMany(...)` on a
type class that no longer has that method — the compilation test fails in the gap.

**Fix:** reverse the dependency order: step 1 becomes "inline execution in TypeFetcherGenerator"
(stop calling type class execution methods), *then* strip the type class, *then* clean up
`BatchKey`. The final state is identical; the path through it compiles at every step.

---

### 2. `env` on `$fields` is premature

The current `fields(sel)` only builds a SELECT list — it maps column names to `table.COLUMN`
references. No `env` is needed for that. The plan adds `env` because "the Type class uses `env` for
context argument extraction," but that is a G5 concern: per-field methods like
`language(sf, table, env)` will need it when they evaluate `FieldCondition` at the call site.
Right now, adding `env` to every `$fields` call creates parameter bloat in generated output before
there is any use for it.

**Fix:** omit `env` from `$fields` in this refactor. Add it as part of G5 when the first per-field
method that actually needs context is introduced.

---

### 3. `subselectMany`/`subselectOne` are already dead code — say so

The removal of these methods is correct, but the plan does not explain why it is safe. Nothing in
`TypeFetcherGenerator` calls them today — they are stubs generated on every type class that are
never invoked. The `TablePipelineTest` tests (`subselectMany_usesMultiset`,
`subselectOne_usesMultisetWithLimit`, `subselectMany_tableRefIsCorrectForSchema`) are testing dead
code. Stating this explicitly would make the removal feel less risky to a reviewer.

---

### 4. Service rows method pseudocode is misleading

The plan shows `loadRecommendations` with a real jOOQ body calling `Film.$fields(...)`. But the
current implementation throws `UnsupportedOperationException`, and after this refactor it *still*
should — service field execution is not being implemented here. The pseudocode shows the eventual
goal, which blurs what actually changes in this step. The real delta is narrow: service rows methods
no longer delegate to `batchKey.selectManyMethodName()` on the type class; they just throw directly.
That should be stated clearly rather than illustrated with a future-state body.

---

### 5. Test count and enumeration is incomplete

The plan says "Remove signature tests for all removed methods (9 tests)" then separately calls out
"Remove `fieldsWithExtra_signature`". That is 10 removals, not 9.

More importantly, the `FetcherPipelineTest` section only names 2 tests to update
(`queryTableField_list_delegatesToSelectMany`, `queryTableField_single_delegatesToSelectOne`). Both
assert `contains("selectMany")` / `contains("selectOne")`. But there are further tests with body
assertions that depend on delegation to the type class — the lookup rows method tests and service
field rows tests also need updating and should be listed explicitly.

---

### 6. `needsGraphitronContextHelper` expansion needs a sharper condition

"True whenever any query-executing field exists" is vague after this refactor, since every non-stub
fetcher now executes a query directly. The condition probably simplifies to: emit the helper
whenever the fetchers class has at least one field that is not a stub (i.e., not a split-query
placeholder or an unimplemented service rows method). Spell out the exact predicate rather than
leaving it open to interpretation.

---

### 7. Minor: "10 generated methods per type class" in the removal table is confusing

The table says "9 builder methods, 10 generated methods per type class" without clarifying that
`buildFieldsMethod` is being *replaced* (not removed) and `buildFieldsWithExtraMethod` is being
removed. A footnote clarifying that `fields(sel)` becomes `$fields(sel, table)` — and is therefore
not counted in the removed 10 — would remove the ambiguity.

---

## What's solid

- The three concrete problems (fixed overloads, hidden alias, `BatchKey` method-name explosion) are
  accurately diagnosed and are genuine friction points in the current design.
- The G5 walkthrough with the recursive multiset pattern gives strong confidence that the
  architectural decision is correct.
- `BatchKey.selectManyMethodName()` / `selectOneMethodName()` removal is a genuine simplification —
  those methods exist solely to route to the type class, which is going away.
- `ConnectionHelper`, `ConnectionResult`, `OrderByResultClassGenerator`, and
  `TypeConditionsGenerator` staying unchanged is correct.
- The `$` prefix on `$fields` cleanly avoids collision with a GraphQL field named `fields` in the
  generated switch statement.

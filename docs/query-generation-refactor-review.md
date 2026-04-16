# Review: Query Generation Refactor Plan

Review of [query-generation-refactor.md](query-generation-refactor.md).

## Overall

The core idea is right: separating projection (`$fields`) from execution unlocks G5 inline nesting
by making the table alias a first-class parameter. The G5 section clearly explains why this matters,
and the recursive multiset pattern it sets up is sound. Several issues need addressing before
implementation.

---

## Issues

### 1. Step ordering breaks compilation and the test suite

Steps 1 and 2 modify `TypeClassGenerator` (rename `fields` → `$fields`, remove execution methods).
Step 3 is what updates `TypeFetcherGenerator` to stop calling those removed methods. As written,
executing steps 1+2 before step 3 leaves the fetcher generator calling `Type.selectMany(...)` on a
type class that no longer has that method — the compilation test fails in the gap.

The same gap applies to the unit test suite: tests for removed methods
(`subselectMany_usesMultiset`, `fields_signature`, etc.) will fail as soon as the methods are
removed, before step 5 cleans them up.

**Fix:** reverse the dependency order. Step 1 becomes "inline execution in `TypeFetcherGenerator`"
(stop calling type class execution methods). *Then* strip the type class. *Then* clean up
`BatchKey`. *Then* remove the now-dead tests. The final state is identical; the path through it
compiles and passes tests at every step.

---

### 2. `$fields` must be declared `public static` — the plan doesn't say so

The plan shows `Film.$fields(...)` called from `QueryFetchers` (a different class). That requires
`public static`. Current `TypeClassGenerator` methods are likely package-private. The access
modifier is a generated-code output decision and must be stated explicitly in the plan — it affects
readability, API surface, and what the generated code looks like to a developer inspecting it.

---

### 3. `env` on `$fields` — needs an explicit decision, not just a pattern

The current `fields(sel)` only builds a SELECT list — it maps column names to `table.COLUMN`
references. No `env` is needed for that. The plan adds `env` because per-field methods like
`language(sf, table, env)` will need it in G5 when evaluating `FieldCondition` at the call site.
Adding `env` now creates parameter bloat in generated output before there is any use for it.

However, G5 is listed as the immediate next item in the roadmap. If `$fields(sel, table)` ships
without `env`, G5 immediately requires another signature migration of the same method across every
generated type class.

The plan should state the tradeoff explicitly and commit to one side:
- **Omit `env` now:** cleaner minimal step; G5 adds it later with a known second migration.
- **Include `env` now:** avoids the second migration; the parameter is dead weight until G5 but
  only for one iteration.

Either is defensible. The current plan adds `env` without acknowledging the cost, which leaves the
implementer uncertain whether it was deliberate.

---

### 4. `$` prefix should be justified or alternatives considered

`$` is valid Java but unusual in idiomatic generated code. Developers reading the generated output
will find it surprising. The plan's one-line justification ("avoids collision with a GraphQL field
named `fields`") does not consider alternatives:

- `selectFields(sel, table, env)` — descriptive; no collision risk since it is a static method on
  the type class, not a switch-case label
- `buildSelectList(sel, table, env)` — equally clear
- `_fields(sel, table, env)` — underscore prefix, less unusual than `$`

The `$` is defensible — it visually signals "generated/special" — but the plan should name the
alternatives and explain why `$` was preferred. As written, a reviewer has no way to know whether
the naming was deliberate or a default.

---

### 5. `subselectMany`/`subselectOne` are already dead code — say so

The removal is correct, but the plan does not explain why it is safe. Nothing in
`TypeFetcherGenerator` calls them today — they are stubs generated on every type class that are
never invoked. The `TablePipelineTest` tests (`subselectMany_usesMultiset`,
`subselectOne_usesMultisetWithLimit`, `subselectMany_tableRefIsCorrectForSchema`) are testing dead
code. Stating this explicitly would make the removal feel less risky to a reviewer.

---

### 6. Service rows method pseudocode shows the future state, not the actual delta

The plan shows `loadRecommendations` with a real jOOQ body calling `Film.$fields(...)`. But the
current implementation throws `UnsupportedOperationException`, and after this refactor it *still*
should — service field execution is not being implemented here. The pseudocode shows the eventual
goal, which blurs what actually changes in this step. The real delta is narrow: service rows methods
no longer delegate to `batchKey.selectManyMethodName()` on the type class; they just throw
directly. That should be stated clearly rather than illustrated with a future-state body.

---

### 7. Test plan conflates "delete" and "update" — CLAUDE.md says delete

The `FetcherPipelineTest` section names tests to "update" with new body assertions
(`contains("$fields")` instead of `contains("selectMany")`). But CLAUDE.md explicitly prohibits
body-content assertions — they test implementation, not behavior, and break on every refactor. Tests
asserting `contains("selectMany")` or `contains(".fetch()")` should be **deleted**, not updated
with different strings.

The correct replacement coverage is already specified in the plan's step 6: compilation tests
(does the generated code compile against real jOOQ?) and execution tests (does it return correct
results?). The plan should flag this explicitly rather than implying the body assertions are being
preserved with updated strings.

Additionally, the `FetcherPipelineTest` section only names 2 tests
(`queryTableField_list_delegatesToSelectMany`, `queryTableField_single_delegatesToSelectOne`), but
there are further tests with body assertions that depend on delegation to the type class — the
lookup rows method tests and service field rows tests also need to be addressed and should be listed.

---

### 8. `needsGraphitronContextHelper` expansion needs a sharper condition

"True whenever any query-executing field exists" is vague after this refactor, since every non-stub
fetcher now executes a query directly. The condition probably simplifies to: emit the helper
whenever the fetchers class has at least one `SqlGeneratingField` in its field list (i.e., at least
one non-stub, non-UnsupportedOperationException-throwing method). Spell out the exact predicate in
terms of the field classification model rather than leaving it open to interpretation.

---

### 9. `fields.contains(extra)` in the connection example relies on jOOQ `Field.equals()` semantics

The plan shows:

```java
var fields = new ArrayList<>(Film.$fields(env.getSelectionSet(), table, env));
for (var extra : extraFields) {
    if (!fields.contains(extra)) fields.add(extra);
}
```

This deduplication relies on jOOQ `Field.equals()` to detect when a cursor column is already
present in the select list. If `$fields` returns `table.TITLE` and `extra` is also `table.TITLE`
(the same object reference), this works. But if they are different `Field<?>` instances
representing the same column (e.g., one aliased, one not), `contains()` may silently fail to
deduplicate, producing a duplicate column in the query.

**Fix:** verify jOOQ `Field.equals()` is structural for the cases that arise here, or switch to a
`Set<String>` keyed on `field.getName()` for deduplication — more explicit and not dependent on
jOOQ equality semantics.

---

### 10. `BatchKey.ObjectBased` interaction should be clarified

The plan removes `selectManyMethodName()` and `selectOneMethodName()` from the `BatchKey` sealed
interface and all implementations. `ObjectBased` currently throws `UnsupportedOperationException`
on both. After the refactor, `ObjectBased` still exists in the sealed hierarchy but these methods
are gone.

The roadmap's Known Gaps section lists `ObjectBased` batch loading as an open decision: either
collapse into `RecordKeyed` (Option A) or implement `selectManyByObjectKeys` (Option B). The plan
should confirm that removing these methods from the interface is compatible with both options — or
flag that any field currently classified as `ObjectBased` will need a decision before this refactor
can complete.

---

### 11. Removal table undercounts builder methods and is ambiguous about `$fields`

The "What gets removed" table says "9 builder methods, 10 generated methods per type class".

Two problems:

First, `sortFieldList()` is listed in step 2 as also being removed but is not counted in the 9
builder methods. That makes 10 removed builder methods (or 11 if `buildFieldsWithExtraMethod` is
also counted separately from `buildFieldsMethod`).

Second, the table does not clarify that `buildFieldsMethod` is being *replaced* (it generates
`$fields` instead of `fields`) while `buildFieldsWithExtraMethod` is being *removed* outright. A
reader cannot tell from the table whether `fields → $fields` is a rename or a net removal. A
footnote stating that `fields(sel)` becomes `$fields(sel, table[, env])` — and is therefore not
counted among the 10 removed generated methods — would remove the ambiguity.

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
- `subselectMany`/`subselectOne` removal is safe because neither is called from anywhere — the plan
  just needs to say so.
- The step 6 verification sequence (`mvn test` → `mvn compile` on test-spec → `mvn test` on
  test-spec) is the right layered check: unit/pipeline correctness, then type-level compilation
  safety, then behavioral correctness against a real database.

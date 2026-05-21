---
id: R211
title: "@condition(override:true) build failure surfaces misleading no-column-found message"
status: Backlog
bucket: bugs
depends-on: []
created: 2026-05-21
last-updated: 2026-05-21
---

# @condition(override:true) build failure surfaces misleading no-column-found message

R210 added an eager `@condition(override: true)` gate at the "no column found" fall-through in `BuildContext.classifyInputFieldInternal`: when the directive carries `override: true`, the gate calls `buildInputFieldCondition`, and on success returns `ConditionOnlyField`. When the condition itself fails to build (parameter binding mismatch, reflection failure, etc.), `condErrors` is populated by the `buildInputFieldCondition` call and the gate falls through to the original `Unresolved("no column 'X' found in table 'Y'")` arm. Both messages then surface side-by-side on the composite `Rejection`, e.g.:

```
input field 'opptaksNavn': no column 'opptaksNavn' found in table 'opptak';
input field 'opptaksNavn' @condition: parameter 'navn' in method 'opptakNavnSok' is not a GraphQL argument and not a context key
```

The first half is noise: `override: true` makes the column unused by construction, so "no column found" is not the actionable diagnostic. The schema author sees two errors per field and has to know which one to act on.

## Background

R210's first commit (`94bc3bf`) suppressed the column-miss with a clearer placeholder when the gate's `buildInputFieldCondition` populated errors:

```java
if (errors.size() > errorsBefore) {
    return new InputFieldResolution.Unresolved(name, null,
        "@condition(override: true) failed to build; see condition error above");
}
```

The architect-review follow-up (`47f5f39`, Finding 2) removed this suppression because eagerly *building* the condition at the gate also populates `condErrors` on `override: false` reflection failures — collapsing R205 acceptance test #4's typed `AuthorError.UnknownName` into a `Structural` composite. The fix at the time was to make the gate read the override flag first (cheap, no side effects) and only build the condition when `override: true` actually fires; on failure it still falls through to the column-miss Unresolved arm.

That solved the R205 test-#4 regression but left the symmetric usability gap: on the `override: true` branch, when the gate's condition build fails, the column-miss message is misleading by construction.

## Direction

In `BuildContext.classifyInputFieldInternal`, when the `override: true` gate fires and `buildInputFieldCondition` populates `errors` but returns empty, suppress the redundant column-miss arm — return a `ConditionOnly`-flavoured `Unresolved` whose message points the author at the condition error, not the column. The R205 test-#4 path is unaffected: `override: false` never enters this branch, so the gate's eager build never populates `condErrors` on the override-false leg.

Sketch (refining R210's `BuildContext.java:1788-1799`):

```java
if (conditionDirective != null && conditionDirective.override()) {
    int errorsBefore = errors.size();
    Optional<ArgConditionRef> overrideCond = buildInputFieldCondition(field, name, errors);
    if (overrideCond.isPresent()) {
        return new InputFieldResolution.Resolved(new InputField.ConditionOnlyField(...));
    }
    if (errors.size() > errorsBefore) {
        // R211: column is unused by construction under override:true; the condition error
        // already surfaced via buildInputFieldCondition is the actionable diagnostic.
        return new InputFieldResolution.Unresolved(name, null,
            "@condition(override: true) failed to build; see condition error above");
    }
}
```

The `errorsBefore` size-delta check distinguishes "gate fired, condition error already in `errors`" from "gate fired, condition silently returned empty" — the latter should not happen given `readConditionDirective` returned non-null with `override:true`, but the guard keeps the column-miss fallback safe.

## Acceptance tests

1. **Pipeline: override:true + parameter binding failure produces a single, actionable error.** Schema with `input PlainFilter { foo: String @condition(method: "barCondition", override: true) }` where `barCondition(Table, String wrong)` has a non-matching parameter name. Assert the rejected `Rejection` does NOT contain `"no column 'foo' found in table"`, and DOES contain the condition parameter-binding error.
2. **Pipeline: R205 acceptance test #4 boundary preserved.** Re-run / re-assert `plainInput_overrideFalseWithoutMatchingColumn_stillRejectsAsUnclassifiedField` — the override:false leg still surfaces `AuthorError.UnknownName` with the column attempt; the R211 suppression must not bleed into the override:false branch.
3. **Pipeline: existing R210 broken-condition test still passes.** `plainInput_overrideTrueWithBrokenCondition_rejectsAsUnclassifiedField` (R210) asserted the *presence* of `'PlainFilter'` and `'sakskode'` substrings in the reason; tighten or replace it so the column-miss substring is asserted absent.

## References

- `graphitron/src/main/java/no/sikt/graphitron/rewrite/BuildContext.java:1780-1801` — the R210 override-true gate to tighten.
- R210 (`condition-override-true-column-not-required.md`, shipped at `94bc3bf` + `47f5f39` + `ac2588c`) — the gate's history; commit `47f5f39` removed the prior suppression for the R205 test-#4 reason described above.
- R205 acceptance test #4 (`GraphitronSchemaBuilderTest.plainInput_conditionReflectionFailure_rejectsAsUnclassifiedField` and the override:false boundary test added in `47f5f39`) — the typed-rejection contract that constrains how this suppression is reintroduced.
- Surfaced by alf's production opptak-subgraph schema (`OpptakFilterInput.opptaksNavn` / `utdanningstilbud`); reporter's Java method parameter names did not match the GraphQL input field names, exposing the misleading composite.

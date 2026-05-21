---
id: R211
title: "@condition(override:true) build failure surfaces misleading no-column-found message"
status: In Review
bucket: bugs
depends-on: []
created: 2026-05-21
last-updated: 2026-05-21
---

# `@condition(override: true)` build failure surfaces misleading no-column-found message

R210 added an eager `@condition(override: true)` gate at the "no column found" fall-through in `BuildContext.classifyInputFieldInternal`: when the directive carries `override: true`, the gate calls `buildInputFieldCondition` and on success returns `ConditionOnlyField`. When the condition build itself fails (parameter-binding mismatch, reflection failure), `condErrors` is populated by `buildInputFieldCondition` and the gate falls through to the original `Unresolved("no column 'X' found in table 'Y'")` arm. Both messages then surface side-by-side on the composite `Rejection`.

## Symptom

Production schema (`opptak-subgraph`):

```graphql
input OpptakFilterInput {
    opptaksNavn: String @condition(
        condition: {className: "no.sikt.fs.opptak.opptak.OpptakService", method: "opptakNavnSok"},
        override: true
    )
    utdanningstilbud: [ID!] @condition(
        condition: {className: "no.sikt.fs.opptak.opptak.OpptakService", method: "harUtdanningstilbud"},
        override: true
    )
}
```

Where the Java methods have parameter names that don't match the GraphQL input field names (`opptakNavnSok(Table, String navn)` and `harUtdanningstilbud(Table, List<String> utdanningstilbudIder)`). R210's gate fires, attempts the condition build, fails on the binding-by-name mismatch, populates `condErrors`, and falls through to the column-miss Unresolved:

```
input field 'opptaksNavn': no column 'opptaksNavn' found in table 'opptak';
input field 'utdanningstilbud': no column 'utdanningstilbud' found in table 'opptak';
input field 'opptaksNavn' @condition: parameter 'navn' in method 'opptakNavnSok' is not a GraphQL argument and not a context key;
input field 'utdanningstilbud' @condition: parameter 'utdanningstilbudIder' in method 'harUtdanningstilbud' is not a GraphQL argument and not a context key
```

The first two lines are noise: `override: true` makes the column unused by construction, so "no column found" is not the actionable diagnostic. The schema author has to know which half of each pair to act on.

## Background

R210's first commit (`94bc3bf`) suppressed the column-miss with a redirecting placeholder when the gate's `buildInputFieldCondition` populated errors:

```java
if (errors.size() > errorsBefore) {
    return new InputFieldResolution.Unresolved(name, null,
        "@condition(override: true) failed to build; see condition error above");
}
```

The principles-architect review pass (`47f5f39`, Finding 2) removed that suppression to address a different concern: eagerly *building* the condition at the gate also populates `condErrors` on `override: false` reflection failures, which collapses R205 acceptance test #6's typed `AuthorError.UnknownName` into a `Structural` composite. The architect's fix was to read the override flag first (cheap, no errors-list side effects) and only build the condition when `override: true` actually fires; on failure it still falls through to the column-miss arm.

That preserved R205's typed-rejection contract but reintroduced the original usability bug for the `override: true` path. R211 reinstates the suppression scoped strictly to the override:true branch (the override:false leg never enters this branch), getting both invariants at once.

## Design alternatives considered

Three shapes were on the table:

1. **Placeholder Unresolved on the override:true gate (chosen).** When `buildInputFieldCondition` populates `errors` and returns empty, return `Unresolved(fieldName, lookupColumn=null, reason="@condition(override: true) failed to build; see condition error")`. The `InputFieldResolver.resolve` lift (`InputFieldResolver.java:81-89`) checks `condErrors.isEmpty() && failures.size() == 1 && failures.get(0).lookupColumn() != null` for the typed `AuthorError.UnknownName` lift; the override:true case satisfies none of those (condErrors populated, lookupColumn null) so it folds to `Rejection.structural` prose, which is semantically right — this is a condition-method binding issue, not an unknown-column issue. Pros: minimal change, scoped to the gate R210 introduced; the override:false branch is structurally untouched so R205 test #6 stays typed; no shape changes to `InputFieldResolution.Unresolved`. Cons: renders as two lines in the structural composite — the placeholder forwards to the actual condition error, which sits on the next line. A future cleanup could dedup.

2. **Restructure the gate to attempt condition build first.** Move the override:true block before the column lookup at `BuildContext.java:1734`; classify entirely via the condition method without touching the column-resolution path. Pros: structurally honest — override:true becomes a primary classification path, not a fall-through. Cons: today, a field with `override:true` AND a matching column classifies as `ColumnField` with the override condition (suppressing the implicit predicate at `walkInputFieldConditions`). Reordering would need a uniform "if override:true, build condition once; if column matches, attach as ColumnField + override; else ConditionOnlyField" pass — that's an R210-scale restructure for a usability fix R211 can solve at the gate. Deferred.

3. **Dedup at the `InputFieldResolver.resolve` rendering loop.** Detect overlap between `failures` and `condErrors` by field name and skip the `failures` line when the field also has a condErrors entry. Pros: handles any future column-miss-vs-condition-error pair uniformly. Cons: brittle (substring matching across opaque rejection prose); concentrates classifier-level knowledge at the consumer site. Deferred until a second occurrence justifies the dedup machinery.

The chosen shape (1) optimises for minimal blast radius and preserves R205's typed-rejection contract by construction. (2) would be the cleaner landing if a future item revisits override-flag handling holistically; (3) is the right move if more column-miss-vs-condition-error pairs emerge.

## Direction

In `BuildContext.classifyInputFieldInternal`, reinstate the `errorsBefore` size-delta check inside the override:true block at `BuildContext.java:1791-1798`:

```java
var conditionDirective = readConditionDirective(field);
if (conditionDirective != null && conditionDirective.override()) {
    int errorsBefore = errors.size();
    Optional<ArgConditionRef> overrideCond = buildInputFieldCondition(field, name, errors);
    if (overrideCond.isPresent()) {
        return new InputFieldResolution.Resolved(new InputField.ConditionOnlyField(
            parentTypeName, name, locationOf(field), typeName, nonNull, list,
            overrideCond.get()));
    }
    if (errors.size() > errorsBefore) {
        // R211: column is unused by construction under override:true; the condition error
        // already appended by buildInputFieldCondition is the actionable diagnostic. Returning
        // a redirecting Unresolved with lookupColumn=null suppresses the misleading
        // "no column 'X' found in table 'Y'" arm. The structural-vs-typed lift in
        // InputFieldResolver.resolve folds this leg to Rejection.structural (condErrors
        // non-empty + lookupColumn null), which is the right bucket — the failure shape is
        // condition-method binding, not unknown-column.
        return new InputFieldResolution.Unresolved(name, null,
            "@condition(override: true) failed to build; see condition error");
    }
}
return new InputFieldResolution.Unresolved(name, columnName,
    "no column '" + columnName + "' found in table '" + tableName + "'");
```

The `errorsBefore` size-delta check distinguishes "gate fired, condition error in `errors`" from "gate fired, condition silently returned empty". The second case shouldn't happen given `buildInputFieldCondition`'s contract (every empty return populates `errors`), but the guard keeps the column-miss fallback safe if that contract ever loosens.

Also update R210's existing comment block at `BuildContext.java:1780-1789` to reflect the new behavior (the comment currently says "if it fails to build, the condErrors entry surfaces via the outer composite — same as today" which becomes stale).

## Acceptance tests

1. **Pipeline: override:true + parameter-binding failure renders without the column-miss noise.** New test `plainInput_overrideTrueWithBrokenCondition_doesNotMentionColumnMiss`. Schema: `input PlainFilter { sakskode: String @condition(method: "sakskodeCondition", override: true) }` plus a `TestConditionStub.sakskodeCondition` variant whose parameter name doesn't match (or reuse `NoSuchClass`/`nope` for a reflection failure — both populate `condErrors`). Assert the rejected `UnclassifiedField.reason()` does NOT contain `"no column 'sakskode' found"`, and DOES contain the condition-error substring (`"@condition"` plus the parameter or class name). The composite prefix `"plain input type 'PlainFilter'"` should remain.

2. **Pipeline: R205 acceptance test #6 (typed AuthorError.UnknownName on override:false) stays typed.** Re-assert `plainInput_conditionReflectionFailure_rejectsAsUnclassifiedField` at `GraphitronSchemaBuilderTest.java:4184` — the override:false path never enters R211's branch, so the typed lift survives. No new test needed; the existing test serves as a regression guard.

3. **Pipeline: tighten R210's existing override:true broken-condition test.** `plainInput_overrideTrueWithBrokenCondition_rejectsAsUnclassifiedField` at `GraphitronSchemaBuilderTest.java:4166` currently asserts the *presence* of `"plain input type 'PlainFilter'"` and `"sakskode"` substrings. Add `assertThat(uf.reason()).doesNotContain("no column 'sakskode' found")` so a regression that re-introduces the column-miss arm under override:true gets caught at the existing test site too.

4. **Pipeline: R210's override:false-without-matching-column boundary test stays green.** `plainInput_overrideFalseWithoutMatchingColumn_stillRejectsAsUnclassifiedField` at `GraphitronSchemaBuilderTest.java:4143` — override:false never enters R211's branch; the typed `AuthorError.UnknownName` lift survives. Pre-existing test serves as a regression guard.

No new fixtures needed: `TestConditionStub` already carries `sakskodeCondition(Table, String sakskode)` from R210; R211 test (1) can use a class+method that reflects but has a parameter-name mismatch, or reuse the existing `NoSuchClass.nope` reflection-failure path. The simpler shape is the reflection failure — R210 test 3 (`plainInput_overrideTrueWithBrokenCondition_rejectsAsUnclassifiedField`) already builds that exact schema; R211 test (1) extends test 3 with the `doesNotContain` assertion. Treat test (1) and test (3) as the same test with one additional assertion (covered under "tighten R210's existing test"); only test (1) needs new code, and only if we want a distinct parameter-binding-failure variant beyond reflection failure.

A simpler total scope: **one assertion added to the existing R210 broken-condition test, plus the BuildContext edit and comment update**. No new tests required.

## References

- `graphitron/src/main/java/no/sikt/graphitron/rewrite/BuildContext.java:1780-1801` — the R210 override-true gate to tighten; comment block at `:1780-1789` needs the post-R211 update too.
- `graphitron/src/main/java/no/sikt/graphitron/rewrite/InputFieldResolver.java:81-89` — the `canLiftToUnknownName` lift; constrains how the R211 Unresolved folds (condErrors non-empty + lookupColumn null → structural prose, which is correct for this case).
- `graphitron/src/test/java/no/sikt/graphitron/rewrite/GraphitronSchemaBuilderTest.java:4166-4178` — R210's existing `plainInput_overrideTrueWithBrokenCondition_rejectsAsUnclassifiedField` test to extend with the `doesNotContain("no column …")` assertion.
- `graphitron/src/test/java/no/sikt/graphitron/rewrite/GraphitronSchemaBuilderTest.java:4143-4159` — R210's override:false-without-matching-column boundary test; structurally untouched by R211.
- `graphitron/src/test/java/no/sikt/graphitron/rewrite/GraphitronSchemaBuilderTest.java:4184-4196` — R205 acceptance test #6 (`plainInput_conditionReflectionFailure_rejectsAsUnclassifiedField`); structurally untouched by R211.
- R210 (`condition-override-true-column-not-required.md`, shipped at `94bc3bf` + `47f5f39` + `ac2588c`) — the gate's history. Commit `47f5f39` (architect-review Finding 2) removed the prior suppression for the R205 test-#6 reason; R211 reinstates it scoped to the override:true branch where the test-#6 path doesn't reach.
- R213 (`input-field-rejection-attribution.md`) — the broader companion. Same `opptak-subgraph` source schema also surfaced that plain-input field rejections are attributed to the *consuming* field's source location, not the input type's offending field. R211 shrinks the message at the consuming-field carrier; R213 moves (or splits) the carrier to point at the input field's `@condition` directive directly. The two fixes are independent and stack: R211 ships first.
- R214 (`column-binding-at-classification-not-usage.md`) — the structural restructure that subsumes R211's placeholder. Under R214 the override:true gate moves above the column lookup, so the column-miss arm is unreachable on this branch by construction. R211's `doesNotContain("no column 'sakskode' found")` assertion stays as a regression guard; the placeholder code can be removed once R214 lands.
- Surfaced by alf's production `opptak-subgraph` schema (`OpptakFilterInput.opptaksNavn` / `utdanningstilbud`); the Java parameter-name mismatch was the schema author's bug, but the composite rejection's column-miss noise made the actionable error hard to find.

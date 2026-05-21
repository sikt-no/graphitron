---
id: R214
title: "Infer argMapping when the @condition / @service Java signature is unambiguous"
status: Backlog
bucket: dx
depends-on: []
created: 2026-05-21
last-updated: 2026-05-21
---

# Infer argMapping when the @condition / @service Java signature is unambiguous

`ServiceCatalog.reflectTableMethod` / `reflectServiceMethod` bound each Java parameter to a GraphQL argument by *name*: a parameter matched a same-named field argument, a same-named declared context key, classified as a SOURCES shape, or was rejected with the long "either rename the Java parameter to match … or bind explicitly via the @service directive's argMapping field" diagnostic. Authors hit this even when the pairing was structurally unique — a field with one argument and a method with one non-Table / non-Context / non-DSLContext parameter has only one possible binding, but the long-form error still required either renaming the Java parameter or writing a redundant `argMapping: "javaName: gqlName"`. The user flagged this as a refusal point in real schemas:

```
opptaksNavn: String @condition(
    condition: {className: "no.sikt.fs.opptak.opptak.OpptakService", method: "opptakNavnSok"},
    override: true
)
# signature: public static Condition opptakNavnSok(Opptak opptak, String whateverWeDontCareBecauseItsObvious)
```

**Design decision (user, DX > rigor):** if there is one and only one possible mapping between parameters and arguments based on type, use it; otherwise fall back to name-based mapping. Two-parameter methods that take a Table and a String can never be ambiguous.

**Implementation:** `ServiceCatalog.inferBindingsByType` augments the `argByJavaName` map after the override-typo check. For each Java type `T` that appears exactly once among unbound Java parameters AND exactly once among unclaimed GraphQL slots, a positional binding is added. `Table<?>` and `DSLContext` parameters are skipped (they're typed elsewhere); parameters whose name matches a declared context key are skipped (the existing name-based binding wins); slots whose GraphQL type doesn't map to a canonical Java type via `mapToJavaTypeName` (enums, named input objects, unclassified scalars) are silently dropped from the inference candidate set. Asymmetric counts (two String params + one String slot; one String param + two String slots) leave the parameter unbound and the existing diagnostic fires. The inference applies to argument-level `@condition`, field-level `@condition`, `@service`, `@tableMethod`, and input-field `@condition` — every caller of the reflection methods that has slot types in scope (the path-step `@condition` in `BuildContext.resolveConditionRef` has none and is unaffected).

**Open forks left for follow-up:** Surfacing inferred pairs in the resolved-coordinate report or as an LSP hint (today the inference is silent); reconsidering the `-parameters` requirement for the single-slot case where positional inference doesn't need parameter names. LSP `Behavior.java` quickfixes that synthesize `argMapping:` suggestions are untouched — they fire from the existing diagnostic that still surfaces when the inference can't disambiguate.

**Files changed:** `ServiceCatalog.java` (new `inferBindingsByType` helper; new slot-types-aware `reflectTableMethod` overload), `ConditionResolver.java` (passes slot types through), `TableMethodDirectiveResolver.java` (passes slot types through), `BuildContext.java` (input-field `@condition` passes slot types). Tests: `TestConditionStub.argConditionTypeUnique` / `argConditionTwoStrings` fixtures plus matching `ServiceCatalogTest` cases pinning the happy path and the type-ambiguous fall-back.

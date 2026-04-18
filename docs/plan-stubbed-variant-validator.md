# Stubbed-Variant Validator Plan (P2 #3)

> **Status:** Draft. One design decision with real consequences (retrofit
> shape of 33 per-variant test classes), three smaller decisions with
> recommended directions. Depends on variant-coverage Phase 1 (shipped
> at `15f9f61`) — this plan consumes the `NOT_IMPLEMENTED_REASONS` map
> now guaranteed to be an exhaustive, leaf-keyed record of stubbed
> variants.

## Overview

Extend `GraphitronSchemaValidator` with a single cross-cutting check
that rejects any schema whose classification lands on a stubbed field
variant (i.e., one present in
`TypeFetcherGenerator.NOT_IMPLEMENTED_REASONS`). Today such a schema
passes validation and — when the rewrite pipeline becomes canonical —
would throw `UnsupportedOperationException` at the first request that
hits the variant. The validator check surfaces the gap at build time,
which is the cheapest place to catch it per
[`docs/graphitron-principles.md`](graphitron-principles.md).

Per `docs/rewrite-roadmap.md` §307-308 and `docs/plan-variant-coverage-meta-test.md`
sequencing, this item is the single-arm consumer of the partition
established in Phase 1 of the variant-coverage plan.

## Current State

- **`NOT_IMPLEMENTED_REASONS`** (`graphitron-rewrite/src/main/java/no/sikt/graphitron/rewrite/generators/TypeFetcherGenerator.java`).
  `Map<Class<? extends GraphitronField>, String>` with 33 leaves —
  `QueryField` (8), `MutationField` (6), `ChildField` (19). Each entry
  carries a user-targeted reason string
  (e.g. `"MutationInsertTableField not yet implemented — see rewrite-roadmap.md 'Stubs to complete' #4"`).
  Stability: variant-coverage Phase 1 enforces that every key is a
  real sealed leaf and that implemented/stubbed/not-dispatched are
  pairwise disjoint and exhaustive.
- **`GraphitronSchemaValidator.validateField`** (same module, root
  package). Exhaustive sealed switch over `GraphitronField`; every leaf
  routes to a per-variant `validate*(field, errors)` method (most
  stubs are no-ops today). Final line calls the cross-cutting
  `validatePaginationRequiresOrdering` — the extension point the new
  check will mirror.
- **`ValidateMojo`** (`graphitron-maven-plugin/src/main/java/no/sikt/graphitron/mojo/ValidateMojo.java:47-53`).
  Runs `new GraphitronSchemaValidator().validate(graphitronSchema)`
  and surfaces each `ValidationError` as a **warning** (`getLog().warn`).
  Deliberately non-blocking during the legacy→rewrite migration — the
  legacy pipeline is still the build-failing path.
- **Per-variant validation tests** (`graphitron-rewrite/src/test/java/no/sikt/graphitron/rewrite/validation/*ValidationTest.java`).
  51 test classes; each parameterised enum-driven via the
  `ValidatorCase` interface. Every stubbed variant has at least one
  case asserting `List.of()` expected errors (`"…always valid"`,
  `"…implicit column"`, etc.). Those cases become inaccurate the
  moment the new check lands — they now produce the
  "not yet implemented" error. Retrofit count: 33 test classes.

## What We're NOT Doing

- **Not flipping `ValidateMojo`'s warn→error severity.** Today all
  rewrite-path errors surface as warnings (the legacy pipeline
  block-fails first). Promoting the stubbed-variant error to
  block-fail would require a per-error severity channel on
  `ValidationError`, or a full migration flip — both out of scope.
  When the rewrite becomes the canonical pipeline, *all* rewrite
  errors should block the build (this one included); no special
  casing.
- **Not changing the stub body in `TypeFetcherGenerator.stub(f)`.**
  The runtime `UnsupportedOperationException` remains as a
  defense-in-depth backstop. A build that skipped validation (direct
  library use bypassing the mojo, say) still gets a loud failure at
  request time.
- **Not moving `NOT_IMPLEMENTED_REASONS` out of
  `TypeFetcherGenerator`.** Some reviewers will ask whether a
  "not-yet-implemented registry" belongs on a model class or in the
  validator. Keep it where it is: the map is still maintained
  alongside the switch arms that consume it, which is the only place
  where "is this stubbed?" is tractable to answer. The validator
  reads it; doesn't own it.
- **Not introducing a separate test suite for the new check.**
  The existing per-variant tests are the natural home — each one
  already builds a fixture for its variant. Adding a centralised
  `StubbedVariantCheckTest` would duplicate fixtures and obscure
  which variants are stubbed. See Decision B.

---

## Four Design Decisions

### Decision A: Where the check lives

**Option 1 — New private method `validateVariantIsImplemented(field, errors)`, called once from `validateField` next to `validatePaginationRequiresOrdering`.** *(recommended)*

```java
private void validateField(GraphitronField field, …, List<ValidationError> errors) {
    switch (field) { … }                              // existing per-variant dispatch
    validatePaginationRequiresOrdering(field, errors); // existing cross-cutting
    validateVariantIsImplemented(field, errors);       // NEW
}

private void validateVariantIsImplemented(GraphitronField field, List<ValidationError> errors) {
    String reason = TypeFetcherGenerator.NOT_IMPLEMENTED_REASONS.get(field.getClass());
    if (reason != null) {
        errors.add(new ValidationError(
            "Field '" + field.qualifiedName() + "': " + reason,
            field.location()));
    }
}
```

- **Pros:** single insertion point; mirrors the existing
  `validatePaginationRequiresOrdering` pattern exactly; the
  `NOT_IMPLEMENTED_REASONS` lookup naturally ignores leaves in
  `IMPLEMENTED_LEAVES` (returns `null`) and `NOT_DISPATCHED_LEAVES`
  (also returns `null`), so no per-variant branching.
- **Cons:** introduces a dependency from `GraphitronSchemaValidator`
  (root package) on `TypeFetcherGenerator` (`generators` package).
  Today the direction is reversed only for constants
  (the validator is consumed by the generator setup, not the other
  way round). Acceptable: the map already exists, carries the
  intended reason strings, and has no other natural home.

**Option 2 — Inline the check inside each stubbed variant's `validate*` method.**

- **Pros:** keeps dependency direction unchanged (no validator→generator
  import).
- **Cons:** 33 near-identical mutations; drifts; invariant (every
  stubbed variant produces the error) is enforced by convention
  rather than structure; new stubs would silently miss the check.
- **Rejected** — the whole point of the partition is that the check
  is cross-cutting.

**Option 3 — New `StubbedVariantValidator` class, composed into `GraphitronSchemaValidator`.**

- **Pros:** isolates the dependency.
- **Cons:** adds a class for a three-line check. Premature extraction.
- **Rejected** until a second cross-cutting check wants the same home.

**Recommendation: Option 1.**

### Decision B: Test shape — retrofit existing 33 classes vs. centralised suite

**Option 1 — Retrofit each stubbed variant's existing test class; have the "stubbed" case expect the variant-specific error.** *(recommended)*

```java
// before
VALID("insert mutation field — always valid",
    new MutationInsertTableField(…),
    List.of()),

// after
STUBBED("insert mutation field — not yet implemented, produces stubbed-variant error",
    new MutationInsertTableField(…),
    List.of(stubbedError("Mutation.createFilm", MutationInsertTableField.class))),
```

Where `stubbedError(String qualifiedName, Class<?> variant)` is a helper
in `FieldValidationTestHelper` that reads
`NOT_IMPLEMENTED_REASONS.get(variant)` and formats the expected
message, so the test references the same source of truth as
production rather than duplicating strings.

- **Pros:** keeps each variant's test class self-documenting about
  its dispatch status; leverages the existing `ValidatorCase`
  pattern; failure to retrofit a class is caught by that class's
  existing parameterised test.
- **Cons:** 33 file edits. Mechanical.

**Option 2 — New `StubbedVariantValidationTest` that parameterises over every key of `NOT_IMPLEMENTED_REASONS`; leave existing tests untouched.**

- **Pros:** one file. Guaranteed exhaustive.
- **Cons:** requires fixture builders for every stubbed variant
  re-implemented in one place (the existing classes already hold
  these); existing tests still need updates regardless, because
  their `List.of()` assertions become false (the cross-cutting check
  runs on every field). So Option 2 is actually strictly more work
  than Option 1.
- **Rejected.**

**Option 3 — Bypass the new check in tests via a `GraphitronSchemaValidator(skipStubbedCheck: true)` test constructor.**

- **Pros:** zero test edits.
- **Cons:** breaks test/prod parity — the validator shape under test
  no longer matches production. Exactly the anti-pattern
  `CLAUDE.md` warns against.
- **Rejected.**

**Recommendation: Option 1** with the `stubbedError(...)` helper in
`FieldValidationTestHelper` to keep the reason string single-sourced.

### Decision C: Error-message shape

**Option 1 — `"Field '<qualifiedName>': " + NOT_IMPLEMENTED_REASONS.get(cls)`** *(recommended)*

Produces e.g.
`Field 'Mutation.createFilm': MutationInsertTableField not yet implemented — see rewrite-roadmap.md 'Stubs to complete' #4`.

- **Pros:** reason string already includes the variant name and the
  roadmap pointer; no duplication.
- **Cons:** tightly couples the test assertion to the reason string.
  Mitigated by the `stubbedError(...)` helper referencing the map
  at test time.

**Option 2 — Generic message without the reason:** `"Field 'X.y': variant <Class> is not yet implemented in the rewrite generator"`.

- **Pros:** stable across reason-string edits.
- **Cons:** loses the roadmap pointer; user now has to look up the
  variant elsewhere.

**Option 3 — Both:** `"Field 'X.y': <reason> [variant: <Class>]"`.

- **Pros:** belt and suspenders.
- **Cons:** noisy; redundant with the reason string (which already
  names the variant).

**Recommendation: Option 1.**

### Decision D: Ordering with existing per-variant validator errors

Some stubbed variants already produce other validation errors (e.g.
`MultitableReferenceField` emits "not supported in record-based
output"). After the new check, such a field produces *two* errors:
its existing one and the new "not yet implemented" one.

**Option 1 — Leave both.** *(recommended)*

The two errors are orthogonal: the existing one explains *why* the
variant was classified the way it was; the new one explains *what's
stubbed in the generator*. Contributors see both; no loss.

**Option 2 — Short-circuit: skip per-variant validation if stubbed.**

- **Pros:** less noise per field.
- **Cons:** hides design-level errors that are still real; a stub
  that got miscategorised also has a real bug that should surface.
- **Rejected.**

**Option 3 — Skip the stubbed-variant check if the field already has any error.**

- **Pros:** less noise.
- **Cons:** the stubbed message is the actionable one from the user's
  perspective — the one that tells them *the rewrite hasn't finished
  supporting this yet*. Hiding it is worse than the noise.
- **Rejected.**

**Recommendation: Option 1** (both errors emitted; test assertions
gain an extra expected entry where applicable).

---

## Chosen Approach Summary

| # | Decision | Choice |
|---|---|---|
| A | Where the check lives | `validateVariantIsImplemented(field, errors)` next to `validatePaginationRequiresOrdering` |
| B | Test shape | Retrofit each stubbed variant's test class; share `stubbedError(...)` helper |
| C | Message shape | `"Field '<qn>': " + NOT_IMPLEMENTED_REASONS.get(cls)` |
| D | Interaction with other errors | Both errors emitted; no short-circuit |

## Implementation Approach

One PR. Three commits feasible (production, test helper, test
retrofit) if the retrofit diff becomes review-unfriendly as a single
commit; start as one commit and split only if needed.

### 1. Production change

**File:** `graphitron-rewrite/src/main/java/no/sikt/graphitron/rewrite/GraphitronSchemaValidator.java`

Add one method plus one call site in `validateField` — no other
changes. Import of `TypeFetcherGenerator` accepted as the dependency
cost.

### 2. Test helper

**File:** `graphitron-rewrite/src/test/java/no/sikt/graphitron/rewrite/validation/FieldValidationTestHelper.java`

Add one static helper:

```java
public static String stubbedError(String qualifiedName, Class<? extends GraphitronField> variant) {
    return "Field '" + qualifiedName + "': "
        + TypeFetcherGenerator.NOT_IMPLEMENTED_REASONS.get(variant);
}
```

Returning a string (not a `ValidationError`) matches the existing
`containsExactlyInAnyOrderElementsOf(tc.errors())` assertion shape
where `errors()` is a `List<String>`.

### 3. Per-variant test retrofit

33 classes. Each one has one or more cases built with
`List.of()` (or `List.of(existingError)`). Update each such
case's expected list to include `stubbedError(qn, Variant.class)`.
Rename `VALID` to `STUBBED` on the cases that previously asserted
zero errors (keeps test names honest post-retrofit); leave
multi-case test names unchanged where they already describe specific
scenarios.

Variants to touch (from `NOT_IMPLEMENTED_REASONS`, same ordering):

- QueryField (8): `QueryTableMethodTableFieldValidationTest`,
  `QueryNodeFieldValidationTest`, `QueryEntityFieldValidationTest`,
  `QueryTableInterfaceFieldValidationTest`,
  `QueryInterfaceFieldValidationTest`,
  `QueryUnionFieldValidationTest`,
  `QueryServiceTableFieldValidationTest`,
  `QueryServiceRecordFieldValidationTest`.
- MutationField (6): `MutationInsertTableFieldValidationTest`,
  `MutationUpdateTableFieldValidationTest`,
  `MutationDeleteTableFieldValidationTest`,
  `MutationUpsertTableFieldValidationTest`,
  `MutationServiceTableFieldValidationTest`,
  `MutationServiceRecordFieldValidationTest`.
- ChildField (19): `TableFieldValidationTest` (TableField variant),
  `LookupTableFieldValidationTest`,
  `TableInterfaceFieldValidationTest`,
  `RecordTableFieldValidationTest`,
  `RecordLookupTableFieldValidationTest`,
  `PlatformIdFieldValidationTest`,
  `ColumnReferenceFieldValidationTest`,
  `NodeIdFieldValidationTest`,
  `NodeIdReferenceFieldValidationTest`,
  `TableMethodFieldValidationTest`,
  `InterfaceFieldValidationTest`,
  `UnionFieldValidationTest`,
  `NestingFieldValidationTest`,
  `ConstructorFieldValidationTest`,
  `ServiceRecordFieldValidationTest` (if it targets the stubbed variant),
  `RecordFieldValidationTest`,
  `ComputedFieldValidationTest`,
  `PropertyFieldValidationTest`,
  `MultitableReferenceFieldValidationTest`.

Audit note: a few of these class names don't map one-to-one with
the stubbed leaf (e.g., `ColumnReferenceFieldValidationTest` might
cover both `ChildField.ColumnReferenceField` and
`InputField.ColumnReferenceField`). Confirm the mapping during
retrofit; expect 33 code-path touches, not necessarily 33 classes.

## Testing Strategy

### Existing tests (retrofit — done as part of §3)

Each retrofitted per-variant test class keeps its existing assertion
shape; the expected-errors list grows. Running `mvn test -pl
graphitron-rewrite` verifies the retrofit.

### Regression check

Add one scenario to `GraphitronSchemaBuilderTest` (the schema-level
pipeline test): a minimal SDL targeting one stubbed variant
(`MutationInsertTableField` is a natural choice) and assert the
pipeline surfaces the stubbed-variant error for it. Locks the
end-to-end behaviour in addition to the per-variant coverage.

### Negative verification (manual, recorded in PR description)

1. **Temporarily delete** one entry from `NOT_IMPLEMENTED_REASONS` in
   `TypeFetcherGenerator`. The retrofitted test for that variant
   fails because `stubbedError(...)` returns `"Field 'X': null"`.
   Expected; confirms the test binds to the map.
2. **Flip the validator check off** (comment out the call site).
   Every retrofitted test fails with a clear "missing expected error"
   message. Expected; confirms the test binds to the production
   check.
3. **Add a new sealed leaf to `ChildField` without a
   `NOT_IMPLEMENTED_REASONS` entry.** The variant-coverage Phase 1
   partition test fails (not this plan's concern, but worth noting
   the two checks compose correctly: Phase 1 catches the missing
   declaration, this plan catches the runtime consequence if
   Phase 1's check is bypassed).

## Consequences

### What this plan makes cheap
- Adding a new stubbed leaf: contributor adds it to `NOT_IMPLEMENTED_REASONS`
  (required by variant-coverage Phase 1) and the validator
  automatically rejects schemas using it. The retrofitted test for
  that variant documents the expected rejection.
- Promoting a stub to real implementation: contributor removes the
  entry from `NOT_IMPLEMENTED_REASONS`, moves the leaf to
  `IMPLEMENTED_LEAVES`, and updates the variant's test from
  `STUBBED` back to `VALID`. The variant-coverage partition test
  enforces the first two steps; the per-variant test fails if the
  third is skipped.

### What this plan makes expensive
- Retrofitting the 33 stubbed-variant tests is mechanical but
  touches many files. Keep the PR reviewable by splitting the
  production commit from the test-retrofit commit.

### What this plan does not address
- **Mojo severity flip.** Errors remain warnings in `ValidateMojo`
  until the legacy pipeline is retired. This plan's value lands at
  that flip; until then it's latent-but-ready. Track under
  `rewrite-roadmap.md` as a separate follow-up when the rewrite
  canonicalisation plan is scoped.
- **Stale reason strings.** A reason string in
  `NOT_IMPLEMENTED_REASONS` that has drifted from its stub's true
  limitation is invisible to both the variant-coverage and this
  plan's tests. Reason-string audit is a separate documentation
  concern.

## Sequencing

- **This plan is a single PR.** Production change: ~15 LOC on the
  validator plus ~5 LOC on the test helper. Test retrofit: ~33 file
  edits, each small.
- **Blocks on:** variant-coverage Phase 1 (shipped).
- **Blocks:** nothing hard. The mojo severity flip is a natural
  downstream consumer but is not gated by this plan landing first;
  can coexist with a warning-only rewrite pipeline.
- **Concurrent with:** argument-resolution work (no file overlap —
  that work churns the builder and `GraphitronSchemaBuilderTest`
  enums; this plan touches the validator and the `validation/` test
  classes).

## References

- `docs/rewrite-roadmap.md` §99 and §307-308 — the "validator asks
  'can this generate?'" priority this plan implements.
- `docs/plan-variant-coverage-meta-test.md` — Phase 1 shipped; its
  sequencing section explicitly calls out this plan as the next
  item.
- `docs/graphitron-principles.md` — "problems caught at build time
  are far cheaper" (the motivation).
- `graphitron-rewrite/src/main/java/no/sikt/graphitron/rewrite/GraphitronSchemaValidator.java`
  — `validatePaginationRequiresOrdering` is the template the new
  check mirrors.
- `graphitron-rewrite/src/main/java/no/sikt/graphitron/rewrite/generators/TypeFetcherGenerator.java`
  — `NOT_IMPLEMENTED_REASONS` is the map this plan consumes.
- `graphitron-rewrite/src/test/java/no/sikt/graphitron/rewrite/validation/ValidatorCase.java`
  — the interface the test retrofit stays within.
- `graphitron-maven-plugin/src/main/java/no/sikt/graphitron/mojo/ValidateMojo.java`
  — where the validator runs; severity-flip future work lives here.

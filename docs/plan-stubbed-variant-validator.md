# Stubbed-Variant Validator Plan (P2 #3)

> **Status:** Draft. Two design decisions with real consequences
> (retrofit shape of 33 per-variant test classes; mojo severity
> flip), three smaller decisions with recommended directions.
> Depends on variant-coverage Phase 1 (shipped at `15f9f61`) — this
> plan consumes the `NOT_IMPLEMENTED_REASONS` map now guaranteed to
> be an exhaustive, leaf-keyed record of stubbed variants. The mojo
> severity flip (Decision E, default `true`) is a **breaking change
> for consumers** currently ignoring rewrite warnings; mitigated by
> a single-flag escape hatch.

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
  53 test classes; each parameterised enum-driven via the
  `ValidatorCase` interface. Every stubbed variant has at least one
  case asserting `List.of()` expected errors (`"…always valid"`,
  `"…implicit column"`, etc.). Those cases become inaccurate the
  moment the new check lands — they now produce the
  "not yet implemented" error. Retrofit count: 33 test classes.

## What We're NOT Doing

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
- **Not changing `ValidateMojo`'s debug-swallow of pipeline
  exceptions** (the `catch` around `GraphitronSchemaBuilder.build(...)`
  that surfaces schema-build crashes as `getLog().debug(...)`). Those
  are pipeline failures rather than validation errors; routing them
  through the same severity switch as `ValidationError` is a separate
  concern tracked as a follow-up. Decision E only applies to
  `ValidationError` surfacing.

---

## Five Design Decisions

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

### Decision E: Mojo severity — warn vs. fail on rewrite `ValidationError`

Currently `ValidateMojo` surfaces every `ValidationError` from the
rewrite validator as `getLog().warn(...)` (lines 48-56 and 58). The
plan's check would otherwise ship as another warning — actionable
information routed to a severity tier users reasonably ignore.

The rewrite validator catches schema shapes the legacy pipeline
misses (missing-table requirements, non-table-backed `ColumnField`,
and — with this plan — stubbed variants). Treating its findings as
advisory is a policy holdover from the pre-rewrite era. The rewrite
is now strict enough to be authoritative.

**Option 1 — `failOnRewriteValidationError` mojo parameter, default `true`.** *(recommended)*

```java
@Parameter(property = "graphitron.failOnRewriteValidationError", defaultValue = "true")
private boolean failOnRewriteValidationError;

// in execute(), after the warn-logging loop:
if (!errors.isEmpty() && failOnRewriteValidationError) {
    throw new MojoExecutionException(
        "Rewrite validation found " + errors.size() + " error(s). "
        + "Set -Dgraphitron.failOnRewriteValidationError=false to downgrade to warnings "
        + "(temporary escape hatch for in-progress migrations).");
}
```

Every error still logs via `getLog().warn(...)` first so the user
sees all of them, not just the first — then the build fails once
with an aggregate message.

- **Pros:** default behaviour matches how the rewrite is intended to
  be used from day one. "We are better than legacy" — consumers who
  have been silently accumulating rewrite warnings now get a clear
  fail-closed signal with a single-flag escape hatch. One `@Parameter`;
  no per-error channel needed. When the legacy pipeline retires the
  flag becomes redundant and can be removed (or kept as a no-op
  deprecation).
- **Cons:** **behavioural change for existing consumers.** Projects
  currently relying on the warning-only rewrite path will fail their
  next Graphitron upgrade unless they either fix the validation
  error or set the flag to `false`. Mitigation: aggregate error
  message explicitly documents the flag.
- **Scope:** applies to `ValidationError` surfacing only — the
  `catch (Exception e) { getLog().debug(...) }` swallowing pipeline
  crashes stays out of scope (see "What We're NOT Doing").

**Option 2 — Scope the flip to stubbed-variant errors only.**

- **Pros:** narrower blast radius; only this plan's new error is
  authoritative.
- **Cons:** requires a per-error severity channel on
  `ValidationError` (the shape change this plan's original exclusion
  list correctly called out). Creates two tiers of rewrite error
  without principle — "stubbed" is not more authoritative than
  "missing-table requires annotation". The rewrite pipeline either is
  authoritative or it isn't; splitting the line creates its own
  confusion.
- **Rejected.**

**Option 3 — Keep `defaultValue = "false"`, let consumers opt in.**

- **Pros:** zero behaviour change for existing consumers.
- **Cons:** opt-in strictness rarely gets adopted; the error stays
  latent for the builds that most need it. Also inverts the
  "principles say fail at build time" framing from
  `docs/graphitron-principles.md`.
- **Rejected.**

**Option 4 — Per-project `<failOnRewriteValidationError>false</>` in `<configuration>`, but no system property.**

- **Pros:** forces deliberate override via pom, not a command-line
  flag.
- **Cons:** a CI-level override (system property) is the standard
  Maven escape hatch; excluding it forces pom edits for what should
  be a one-off bypass.
- **Rejected** — Option 1's `@Parameter(property=...)` supports both
  pom config and `-Dgraphitron.failOnRewriteValidationError=false`
  at once; no tradeoff.

**Recommendation: Option 1.** Default `true`; system property escape
hatch; flag retires when legacy retires.

---

## Chosen Approach Summary

| # | Decision | Choice |
|---|---|---|
| A | Where the check lives | `validateVariantIsImplemented(field, errors)` next to `validatePaginationRequiresOrdering` |
| B | Test shape | Retrofit each stubbed variant's test class; share `stubbedError(...)` helper |
| C | Message shape | `"Field '<qn>': " + NOT_IMPLEMENTED_REASONS.get(cls)` |
| D | Interaction with other errors | Both errors emitted; no short-circuit |
| E | Mojo severity | `failOnRewriteValidationError` mojo parameter, `defaultValue = "true"` |

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

### 4. `ValidateMojo` severity flip

**File:** `graphitron-maven-plugin/src/main/java/no/sikt/graphitron/mojo/ValidateMojo.java`

Add the `@Parameter`-annotated field and the aggregate-fail branch
per Decision E. Keep the existing `getLog().warn(...)` loop — users
should still see each individual error before the build fails. The
change to the summary line switches from "treated as warnings during
migration" wording to a neutral count message; when the flag is
`false` the message should note the override for easier debugging
of why a CI pipeline that was failing now isn't.

~10 LOC: one field declaration, one branch around the existing
summary line. The `@Parameter(property = "...")` form supports both
pom `<configuration>` and `-D` system-property override.

**Error-message contract.** The `MojoExecutionException` message
must mention `graphitron.failOnRewriteValidationError=false` so a
consumer whose build fails unexpectedly has the escape hatch in the
error itself, not buried in changelog.

**Integration tests.** The `it/` directory (Maven invoker tests) if
present should gain one project that exercises each branch:
validation-error + `failOn...=true` asserts the build fails;
validation-error + `failOn...=false` asserts the build passes with
warnings. If `it/` tests don't exist for the mojo, skip — the
production change is mechanical and the rewrite validator tests
already exercise the `ValidationError` path.

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
- **Breaking change for consumers** currently tolerating rewrite
  warnings: their next Graphitron upgrade starts failing builds on
  schemas where the rewrite validator finds any error (not just the
  stubbed-variant ones — Decision E flips severity for every
  `ValidationError` from the rewrite pipeline). Escape hatch is one
  system property (`-Dgraphitron.failOnRewriteValidationError=false`).
  Call this out in the release notes for the version shipping this
  plan.

### What this plan does not address
- **Stale reason strings.** A reason string in
  `NOT_IMPLEMENTED_REASONS` that has drifted from its stub's true
  limitation is invisible to both the variant-coverage and this
  plan's tests. Reason-string audit is a separate documentation
  concern.
- **Pipeline-exception severity.** `ValidateMojo` still swallows
  `GraphitronSchemaBuilder.build(...)` crashes as `getLog().debug(...)`.
  A crash during schema build (not a classified `ValidationError`)
  still doesn't fail the build. Natural follow-up once the
  stubbed-variant flip has settled. Tracked alongside the mojo in
  the roadmap.

## Sequencing

- **This plan is a single PR.** Production change: ~15 LOC on the
  validator + ~5 LOC on the test helper + ~10 LOC on the mojo flip
  (Decision E). Test retrofit: ~33 file edits, each small.
- **Blocks on:** variant-coverage Phase 1 (shipped).
- **Blocks:** nothing hard. The `ValidateMojo` severity flip now
  lands *with* this plan rather than waiting on legacy retirement
  (Decision E); downstream work that depended on "rewrite errors
  fail the build" unblocks immediately.
- **Concurrent with:** argument-resolution work (no file overlap —
  that work churns the builder and `GraphitronSchemaBuilderTest`
  enums; this plan touches the validator, the `validation/` test
  classes, and the mojo).
- **Release coordination:** the breaking-change call-out in
  Consequences should be reflected in the plugin's release notes
  for the version shipping this PR, including the one-flag escape
  hatch.

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

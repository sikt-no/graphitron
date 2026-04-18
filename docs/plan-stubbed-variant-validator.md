# Stubbed-Variant Validator Plan (P2 #3)

> **Status:** Pending Review
>
> Implemented at `9ba498bc` (core validator + test retrofit) and `7cf568f4` (ValidateMojo severity flip + pipeline regression) on `claude/graphitron-rewrite`.

## What shipped

**Core check** (`9ba498bc`):
- `GraphitronSchemaValidator.validateVariantIsImplemented` — called
  from `validateField` next to `validatePaginationRequiresOrdering`.
  Reads `TypeFetcherGenerator.NOT_IMPLEMENTED_REASONS` and appends a
  `ValidationError` when the field's class is in the map. Returns
  silently for `IMPLEMENTED_LEAVES` and `NOT_DISPATCHED_LEAVES`
  entries (invariant enforced by variant-coverage Phase 1).
- `FieldValidationTestHelper.stubbedError(qualifiedName, variant)` —
  single-sourced helper so tests and production read the same reason
  string.
- 33 per-variant test cases retrofitted across 27 classes.
  Dual-purpose test classes (e.g., `ColumnReferenceFieldValidationTest`
  houses both stubbed `ChildField.ColumnReferenceField` cases and
  not-dispatched `InputField.ColumnReferenceField` cases) had only
  the stubbed cases updated, confirming Decision A's "null for
  non-stubbed leaves" path end-to-end.

**Mojo severity flip — Decision E** (`7cf568f4`):
- `ValidateMojo.failOnRewriteValidationError` `@Parameter` added,
  default `true`. Threads through a `-Dgraphitron.failOnRewriteValidationError=false`
  escape hatch.
- `rewriteErrors` hoisted outside the existing
  `try { … } catch (Exception e) { debug(…) }` so a
  `MojoExecutionException` can escape.
- Legacy-failure check stays first (preserves priority).
- Aggregate error list in the `MojoExecutionException` body with
  `source:line:column: msg` prefix matching the warn-loop format;
  escape-hatch cost named in the failure message.

**Regression test** (`7cf568f4`):
- `StubbedVariantPipelineTest` — three SDL → classifier → validator
  cases: stubbed mutation, stubbed query, implemented variant (negative
  case — no stubbed error).

All 450 graphitron-rewrite tests + graphitron-maven-plugin tests green.

## For the reviewer

Things worth checking:

1. The placement of `validateVariantIsImplemented` — immediately
   after `validatePaginationRequiresOrdering` in `validateField`.
   Decision A's claim was that both cross-cutting checks belong at
   the end, not in the per-variant switch.
2. `ValidateMojo.java` — confirm the throw actually escapes. The
   pre-plan structure wrapped the rewrite validation in a
   `catch (Exception)` block; if the hoist was incomplete, the
   exception would be swallowed and demoted to debug, and the whole
   severity flip would be a no-op. Key lines to read:
   - `rewriteErrors` declared **outside** the try (line ~63).
   - `throw new MojoExecutionException(...)` placed **after** the
     `if (legacyFailure != null)` check (line ~80+).
3. The stubbed-error test helper reads the map at call time, not at
   test class load time. Tests are decoupled from reason-string
   text; changing a reason string in `NOT_IMPLEMENTED_REASONS` does
   not require test updates.
4. Dual-purpose test classes: `ColumnReferenceFieldValidationTest`
   retrofits the stubbed `ChildField.ColumnReferenceField` cases
   and leaves the `InputField.ColumnReferenceField` cases untouched.
   This is the pattern the plan called out; confirm no stubbed case
   slipped through.

## Items surfaced during implementation

Logged here rather than acted on — they belong in the roadmap as
follow-ups if worth pursuing:

- **Pipeline-exception severity.** The `catch (Exception e) { getLog().debug(...) }`
  swallow around `GraphitronSchemaBuilder.build(...)` is unchanged.
  A schema-build crash (not a classified `ValidationError`) still
  doesn't fail the build. Natural follow-up once this plan settles.
- **Maven invoker integration tests.** No `it/` directory exists
  under `graphitron-maven-plugin`. Adding one project exercising
  each `failOn...` branch would be ergonomic but isn't blocking —
  the validator tests + pipeline regression cover the behaviour.
- **Stale reason strings.** Reasons in `NOT_IMPLEMENTED_REASONS` that
  drift from their stub's true limitation are invisible to these
  tests. A periodic audit is a doc-hygiene concern, not code.

## References

- `graphitron-rewrite/src/main/java/no/sikt/graphitron/rewrite/GraphitronSchemaValidator.java`
  — `validateVariantIsImplemented`.
- `graphitron-rewrite/src/main/java/no/sikt/graphitron/rewrite/generators/TypeFetcherGenerator.java`
  — `NOT_IMPLEMENTED_REASONS`.
- `graphitron-maven-plugin/src/main/java/no/sikt/graphitron/mojo/ValidateMojo.java`
  — severity-flip plumbing.
- `graphitron-rewrite/src/test/java/no/sikt/graphitron/rewrite/StubbedVariantPipelineTest.java`
  — end-to-end regression.
- `graphitron-rewrite/src/test/java/no/sikt/graphitron/rewrite/validation/FieldValidationTestHelper.java`
  — `stubbedError(...)` helper.
- `docs/plan-variant-coverage-meta-test.md` — Phase 1 shipped is the
  invariant this plan's validator check relies on.

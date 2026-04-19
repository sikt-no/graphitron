# Stubbed-Variant Validator Plan (P2 #3)

> **Status:** Done
>
> Implemented at `9ba498bc` (core validator + test retrofit) and `7cf568f4` (ValidateMojo severity flip + pipeline regression) on `claude/graphitron-rewrite`. Reviewed 2026-04-19 (independent session, separate from implementer and from the prior review at `91bb3c9`).

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
  The "null for non-stubbed leaves" path (Decision A) is
  end-to-end-confirmed by two patterns already in the tree:
  - **Implemented-leaf null**: `ServiceFieldValidationTest` houses
    both stubbed `ChildField.ServiceRecordField` cases (got
    `stubbedError`) and implemented `ChildField.ServiceTableField`
    cases (no `stubbedError`, expected-error lists unchanged). The
    latter would have failed if the validator fired on an
    `IMPLEMENTED_LEAVES` entry.
  - **Not-dispatched-leaf null**: `PlatformIdFieldValidationTest`
    (covers `InputField.PlatformIdField`) and
    `InputNestingFieldValidationTest` (covers
    `InputField.NestingField`) each assert `List.of()`. Both targets
    are in `NOT_DISPATCHED_LEAVES`; the tests would break if the
    validator mistakenly fired on them.

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
4. Cross-leaf null-path confirmation: `ServiceFieldValidationTest`
   is the sharpest dual-leaf example — `ServiceRecordField` (stubbed)
   got `stubbedError`, `ServiceTableField` (implemented) did not.
   The not-dispatched-leaf null is separately witnessed by
   `PlatformIdFieldValidationTest` and `InputNestingFieldValidationTest`
   (both `List.of()`). Confirm no stubbed case slipped through and
   no non-stubbed leaf accidentally gained a `stubbedError`.

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

## History

- **2026-04-19 (Pending Review → Done)** — Independent-session review. Verified `GraphitronSchemaValidator.validateVariantIsImplemented` (:154-162) reads `NOT_IMPLEMENTED_REASONS` via `.get(field.getClass())` and skips silently on non-stubbed leaves (the partition invariant). `ValidateMojo`: `rewriteErrors` correctly hoisted outside the rewrite try/catch (:64), `throw new MojoExecutionException` placed after the `legacyFailure` check (:83), escape hatch `-Dgraphitron.failOnRewriteValidationError=false` documented with the re-opened-UOE-window cost named inline. `FieldValidationTestHelper.stubbedError` reads the map at call time (:75-78) — test decoupling from reason-string text confirmed. `StubbedVariantPipelineTest`: three cases (stubbed mutation, stubbed query-node, implemented-variant negative) covering the dispatcher contract end-to-end. `NOT_IMPLEMENTED_REASONS` still accurate after argres Phase 2a — `LookupTableField` correctly migrated to `PROJECTED_LEAVES`, comment on :199 reflects the move. `mvn -pl :graphitron-rewrite test` — 465/465 green. `mvn -pl :graphitron-maven-plugin test` — 7/7 (1 unrelated Watch skip). Three items in "Items surfaced during implementation" remain as follow-ups; none block Done.
- **2026-04-19 (Pending Review, prior reviewer pass)** — `91bb3c9` corrected dual-purpose claim about `ColumnReferenceFieldValidationTest`, cleaned C3 drift (dead `stubbedError` imports, stale "(stubbed)" suffixes, javadoc claim about `TableField` being stubbed). Status left at Pending Review — that reviewer addressed plan/test drift, not the Pending Review → Done gate.

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
- `plan-variant-coverage-meta-test.md` — Phase 1 shipped is the
  invariant this plan's validator check relies on.

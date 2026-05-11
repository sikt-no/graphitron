---
id: R134
title: "Fix mutation empty-input short-circuit to use newRecord for single-record payloads"
status: Ready
bucket: bugfix
priority: 3
depends-on: []
---

# Fix mutation empty-input short-circuit to use newRecord for single-record payloads

## Problem

The mutation codegen for list-input mutations emits an empty-list short-circuit that always calls `DSL.using(dsl.configuration()).newResult(<pkProjection>)` regardless of the local variable's static type. When the mutation's direct return type is a payload object (not a list), e.g. `opprettX(input: [XInput]): XPayload!`, `dataIsList` is `false` and the local is typed as a single `Record1<...>`, but `newResult(Field...)` returns `Result<Record>`. The result is a non-assignable type and a compile error in generated output that ships to consumers.

## Fix shipped at 36122dc

`TypeFetcherGenerator.buildMutationDmlRecordFetcher` (around line 2853 post-fix) now branches on `dataIsList`: `newResult(...)` for the projected-list arm, `newRecord(...)` for the single-record arm. The non-empty branch was already gated on `dataIsList` (`.fetch()` vs `.fetchOne()`); this aligns the empty arm with it.

Regression test: `SingleRecordCarrierPipelineTest#bulkInput_singlePayloadCarrier_insertEmptyShortCircuitBuildsEmptyRecordNotResult`. Uses the user-reported shape (`createFilm(in: [FilmInput!]!): FilmPayload @mutation(typeName: INSERT)` with a single-record-carrier payload). Pins the empty-branch constructor call (`.newRecord(`, not `.newResult(`) and the `DataFetcherResult<org.jooq.Record1<...>>` return-type parameter. Scoped to INSERT only because bulk UPDATE/UPSERT on `MutationDmlRecordField` still throw `UnsupportedOperationException` in their respective chain builders; should be parameterised over INSERT/UPDATE/UPSERT when those land.

## In Review → Ready → In Review (rework cycle, addressed)

First In Review pass (at 36122dc) flipped back to Ready on two housekeeping misses:

1. **Build red on `verify-leaf-coverage-report`.** The new R134 test added a trace and bumped counts on ~13 leaves in `graphitron-rewrite/roadmap/inference-axis-coverage.adoc`; the report was not regenerated. Addressed in the rework commit by running `mvn -f graphitron-rewrite/pom.xml -pl roadmap-tool exec:java -Dexec.args='leaf-coverage graphitron-rewrite'` and including the updated report. `mvn install -Plocal-db -P'!docs'` now passes end-to-end.
2. **Spec body lacked a shipped-at note and a named follow-up.** Restructured into Problem / Fix shipped at <sha> / Follow-up sections per `workflow.adoc` "Plan housekeeping". The latent `fetchOne()`-against-multi-row-VALUES question (previously a parenthetical) is now lifted out as a named follow-up below.

## Follow-up: list-input / single-record-output coherence

Deliberately out of scope for this item, but worth filing as a separate roadmap entry: the non-empty branch of `buildMutationDmlRecordFetcher` on the bulk-input + single-record-payload arm calls `.fetchOne()` against multi-row `valuesOfRows(...)` VALUES. The compile bug is now fixed, but inserting N rows still discards N-1 returned keys at runtime, same shape as the Invariant #15 footgun the validator already rejects for `[ID!]!` and `[T!]!` returns. Extending the validator to reject bulk-input + single-payload-carrier (or lifting the emit to `.fetch()` and projecting onto the payload's list data field) is the right fix and deserves its own spec.

## In Review → Ready (second rework, independent reviewer findings)

Independent review of the 5ab5c55 rework flipped this back to Ready on three findings. Address before the next In Review.

### 1. (Material) `inference-axis-coverage.adoc` regeneration conflicts with R132

`graphitron-rewrite/roadmap/inference-axis-coverage.adoc` (commit 5ab5c55, +615 −16) brings the file back to a full-data leaf-coverage report, but R132 (`leaf-coverage-verify-off-local.md`, status `In Review` at 94ebb9c, committed 16:49 UTC — seven minutes before 5ab5c55 at 16:56 UTC) deliberately replaced the same file with a static placeholder and removed the `verify-leaf-coverage-report` Maven execution from `graphitron-rewrite/roadmap-tool/pom.xml`. After R132, the file is documentation that CI regenerates on trunk pushes and uploads as an artifact; the in-repo copy is openly labeled non-data. The R134 rework's stated diagnosis ("Build red on `verify-leaf-coverage-report`") was true of 36122dc on pre-R132 trunk, but stale at the time of 5ab5c55: the gate had already been dropped, so no regeneration was needed.

Net trunk state today: the placeholder R132 wrote (`ea3b512`) was overwritten back to data by R134 rework. When R132's In Review → Done review runs, the file is no longer in R132's designed shape; either R132 has to re-replace it or R134 has to restore the placeholder.

Fix: drop the `graphitron-rewrite/roadmap/inference-axis-coverage.adoc` hunk from the R134 rework (`git restore --source 94ebb9c -- graphitron-rewrite/roadmap/inference-axis-coverage.adoc` on a fresh feature branch, then commit). The R134 commit set should be a one-line change to `TypeFetcherGenerator.java` plus the new pipeline test plus this spec body — nothing in `roadmap/` other than the spec file itself and the regenerated `README.md`. Update the "Build red" bullet in the spec body above to reflect that the gate is gone and no regeneration is required.

### 2. (Moderate) `SingleRecordCarrierPipelineTest.java:374` uses a brittle code-string assertion

The new regression test asserts:

```java
assertThat(body)
    .as("bulk arm casts in to List<Map<?,?>>")
    .contains("java.util.List<java.util.Map<?, ?>> in = (java.util.List<java.util.Map<?, ?>>) env.getArgument")
    ...
```

This is exactly the body-content match `rewrite-design-principles.adoc:128` bans: "Code-string assertions on generated method bodies are banned at every tier; they test implementation, not behaviour, and break on every refactor." The literal `"java.util.List<java.util.Map<?, ?>> in = (java.util.List<java.util.Map<?, ?>>) env.getArgument"` pins variable name `in`, the cast form, and whitespace — a rename or formatting change is a refactor with no semantic effect that breaks the test. The neighbouring `.contains(".newRecord(")` / `.doesNotContain(".newResult(")` checks are precedented (jOOQ DSL method-name presence/absence, same shape as `directReturn_dmlFetcher_emitsTwoStepShape` in this file), but the bulk-arm-cast assertion is not.

Fix: drop the first `.contains(...)` line. The remaining three assertions (`.newRecord(`, `!.newResult(`, return-type structural check on line 382) are sufficient to pin the bug.

### 3. (Suggestion) The principled regression test is a sakila fixture, not a pipeline-tier string assertion

`rewrite-design-principles.adoc:138-140`: "Compilation against real jOOQ is a test tier... compile + execute replace the body-content assertions that the 'generation-thinking' principle bans." The sakila example today has `createFilmPayload(in: FilmCreateInput!): FilmPayload @mutation(typeName: INSERT)` (single→single, line 1171), but no bulk-input + single-payload mutation — the exact combination that triggered this bug. Adding e.g. `createFilmsPayload(in: [FilmCreateInput!]!): FilmPayload @mutation(typeName: INSERT)` to `schema.graphqls` would let the compilation tier catch a regression to the empty-arm constructor without any source-text assertion: the generated `MutationFetchers.createFilmsPayload` would fail `mvn compile -pl :graphitron-sakila-example` if `newResult(...)` were re-emitted, because `Result<Record>` is not assignable to `Record1<...>`. An execution-tier test passing an empty `in` list would then catch the runtime contract.

This is a suggestion, not a blocker — the structural assertions in (2) are precedented. But if the rework lifts the test to compilation tier via a sakila fixture, the pipeline-tier `SingleRecordCarrierPipelineTest` addition can be deleted entirely.

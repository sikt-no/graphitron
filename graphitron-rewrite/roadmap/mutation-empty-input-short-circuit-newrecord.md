---
id: R134
title: "Fix mutation empty-input short-circuit to use newRecord for single-record payloads"
status: In Review
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

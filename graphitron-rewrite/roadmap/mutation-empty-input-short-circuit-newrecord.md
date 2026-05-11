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

`TypeFetcherGenerator.buildMutationDmlRecordFetcher` now branches on `dataIsList`: `newResult(...)` for the projected-list arm, `newRecord(...)` for the single-record arm. The non-empty branch was already gated on `dataIsList` (`.fetch()` vs `.fetchOne()`); this aligns the empty arm with it.

## Regression coverage shipped at <next>

Lifted to compilation tier per `rewrite-design-principles.adoc:138-140` ("compile + execute replace the body-content assertions that the 'generation-thinking' principle bans"). Sakila `schema.graphqls` now declares `createFilmsPayload(in: [FilmCreateInput!]!): FilmPayload @mutation(typeName: INSERT)` — the exact bulk-input + single-payload shape that triggered the bug. The generated `MutationFetchers.createFilmsPayload` emits `Record1<Integer> payload = DSL.using(dsl.configuration()).newRecord(Tables.FILM.FILM_ID)` and is compiled against real jOOQ classes by `mvn compile -pl :graphitron-sakila-example`. A regression to `newResult(...)` would re-emit `Result<Record>` into the `Record1<Integer>` local and fail compilation. The compilation tier owns the regression; the pipeline-tier `SingleRecordCarrierPipelineTest` addition from 36122dc was deleted in the rework.

Scoped to INSERT only because bulk UPDATE/UPSERT on `MutationDmlRecordField` still throw `UnsupportedOperationException` in their respective chain builders; the fixture should be widened to cover UPDATE/UPSERT (and the matching DELETE-rejection arm if/when that surface lands) when those land.

## Follow-up: list-input / single-record-output coherence

Deliberately out of scope for this item, but worth filing as a separate roadmap entry: the non-empty branch of `buildMutationDmlRecordFetcher` on the bulk-input + single-record-payload arm calls `.fetchOne()` against multi-row `valuesOfRows(...)` VALUES. The compile bug is now fixed, but inserting N rows still discards N-1 returned keys at runtime, same shape as the Invariant #15 footgun the validator already rejects for `[ID!]!` and `[T!]!` returns. Extending the validator to reject bulk-input + single-payload-carrier (or lifting the emit to `.fetch()` and projecting onto the payload's list data field) is the right fix and deserves its own spec.

## Rework history

Two In Review → Ready cycles before reaching the current state:

1. **First In Review (36122dc).** First reviewer flipped back to Ready: build was red on `verify-leaf-coverage-report` (the new pipeline-tier test bumped trace counts in `inference-axis-coverage.adoc`; the report needed regenerating). Plan body lacked shipped-at structuring per `workflow.adoc`.
2. **Second In Review (5ab5c55).** Rework regenerated the leaf-coverage report (615 lines of data) and restructured the spec body, but the regeneration conflicted with R132 (which had landed in between and intentionally replaced the file with a placeholder + dropped the verify gate). Independent reviewer flipped back to Ready on three findings: (i) the leaf-coverage regen, (ii) a brittle code-string assertion in the pipeline test, (iii) the principled approach is to lift the regression to compilation tier via a sakila fixture rather than pipeline-tier string matching.
3. **Third rework (current).** All three findings addressed: leaf-coverage placeholder restored from 94ebb9c; pipeline-tier `SingleRecordCarrierPipelineTest#bulkInput_singlePayloadCarrier_insertEmptyShortCircuitBuildsEmptyRecordNotResult` deleted entirely; sakila `createFilmsPayload(in: [FilmCreateInput!]!): FilmPayload` added at `schema.graphqls:1182` so the compilation tier catches a regression to `newResult(...)`. `mvn install -Plocal-db -P'!docs'` green end-to-end.

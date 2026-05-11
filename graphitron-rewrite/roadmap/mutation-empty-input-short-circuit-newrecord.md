---
id: R134
title: "Fix mutation empty-input short-circuit to use newRecord for single-record payloads"
status: Ready
bucket: bugfix
priority: 3
depends-on: []
---

# Fix mutation empty-input short-circuit to use newRecord for single-record payloads

The mutation codegen for list-input mutations emits an empty-list short-circuit that always calls `DSL.using(dsl.configuration()).newResult(<pkProjection>)` regardless of the local variable's static type. When the mutation's direct return type is a payload object (not a list) — e.g. `opprettX(input: [XInput]): XPayload!` — `dataIsList` is `false` and the local is typed as a single `Record1<...>`, but `newResult(Field...)` returns `Result<Record>`, producing a non-assignable type and a compile error in generated output. The fix is in `TypeFetcherGenerator.java` around line 2757: branch on `dataIsList` and emit `newRecord(...)` for the single-record path while keeping `newResult(...)` for the list path. (There is a separate, latent question about whether list-input + single-record-output is even coherent — the non-empty branch calls `fetchOne()` against multi-row VALUES — but that is out of scope for this item; this fixes the compile bug only.)

## In Review → Ready (rework)

Code fix and pipeline test landed at 36122dc and are sound: `TypeFetcherGenerator` branches on `dataIsList` and emits `newRecord(...)` for the single-record arm, `newResult(...)` for the list arm; the new INSERT-only regression in `SingleRecordCarrierPipelineTest` pins the `.newRecord(` shape and the `DataFetcherResult<org.jooq.Record1<...>` parameter, and asserts the list-arm `.newResult(` is absent. INSERT-only scoping is appropriate; the test notes the parameterisation to add when bulk UPDATE/UPSERT lift.

Rework for two housekeeping items before the next In Review pass:

1. **Local build is red on `mvn -f graphitron-rewrite/pom.xml install -Plocal-db`.** The new R134 test bumps trace counts on several leaves in `graphitron-rewrite/roadmap/inference-axis-coverage.adoc` (`UnclassifiedField` 594→595, `MutationDmlRecordField` 27→28, `SingleRecordTableField` 25→26, `ColumnField` (output) 615→616, `ColumnField` (input) 234→235, `EnumType` 1922→1925, `InterfaceType` 637→638, `PlainObjectType` 199→200, and a handful more — 12 lines drift in total) but the report was not regenerated. The `verify-leaf-coverage-report` exec goal fails the build. Regenerate with:
   ```
   mvn -f graphitron-rewrite/pom.xml -pl roadmap-tool exec:java -Dexec.args='leaf-coverage graphitron-rewrite'
   ```
   and include the regenerated `inference-axis-coverage.adoc` in the next push.

2. **Mark the plan body to reflect what shipped.** Per `workflow.adoc` "Plan housekeeping": collapse the problem statement above to a one-line `shipped at 36122dc` note and lift the parenthetical "latent question about whether list-input + single-record-output is even coherent (non-empty branch calls `fetchOne()` against multi-row VALUES)" into a named follow-up (Backlog item or explicit out-of-scope note) so the next reviewer sees it without re-reading the original paragraph.

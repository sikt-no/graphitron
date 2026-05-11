---
id: R134
title: "Fix mutation empty-input short-circuit to use newRecord for single-record payloads"
status: In Review
bucket: bugfix
priority: 3
depends-on: []
---

# Fix mutation empty-input short-circuit to use newRecord for single-record payloads

The mutation codegen for list-input mutations emits an empty-list short-circuit that always calls `DSL.using(dsl.configuration()).newResult(<pkProjection>)` regardless of the local variable's static type. When the mutation's direct return type is a payload object (not a list) — e.g. `opprettX(input: [XInput]): XPayload!` — `dataIsList` is `false` and the local is typed as a single `Record1<...>`, but `newResult(Field...)` returns `Result<Record>`, producing a non-assignable type and a compile error in generated output. The fix is in `TypeFetcherGenerator.java` around line 2757: branch on `dataIsList` and emit `newRecord(...)` for the single-record path while keeping `newResult(...)` for the list path. (There is a separate, latent question about whether list-input + single-record-output is even coherent — the non-empty branch calls `fetchOne()` against multi-row VALUES — but that is out of scope for this item; this fixes the compile bug only.)

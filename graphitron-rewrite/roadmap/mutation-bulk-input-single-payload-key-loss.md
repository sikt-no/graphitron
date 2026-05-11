---
id: R138
title: "Reject or lift bulk-input + single-record-payload mutations that drop N-1 returned keys"
status: Backlog
bucket: validation
priority: 3
theme: mutations-errors
depends-on: []
---

# Reject or lift bulk-input + single-record-payload mutations that drop N-1 returned keys

Surfaced in R134 (`mutation-empty-input-short-circuit-newrecord`, shipped at `36122dc` + `7fadbda`). The R134 fix patches the empty-input short-circuit so it emits `newRecord(...)` instead of `newResult(...)` when the mutation's direct return is a single-cardinality payload (e.g. `opprettX(input: [XInput]): XPayload!`), restoring type-correct compilation. The non-empty arm of `TypeFetcherGenerator.buildMutationDmlRecordFetcher` on that same shape remains incoherent at runtime: it constructs `valuesOfRows(...)` over the N input rows and then calls `.fetchOne()` on the returning clause, silently dropping N-1 returned PK records. Same footgun shape as the Invariant #15 case the validator already rejects for `[ID!]!` / `[T!]!` returns. Two plausible fixes: (a) extend the classifier-time invariant to reject bulk-input + single-record-payload at validation time (consistent with the `[ID!]!` / `[T!]!` precedent), or (b) lift the emission to `.fetch()` and project the resulting list onto the payload's list data field. Choice depends on whether any real schema legitimately wants "bulk insert returning a single carrier"; if not, (a) is the cheaper guardrail.

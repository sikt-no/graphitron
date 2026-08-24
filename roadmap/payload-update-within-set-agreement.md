---
id: R821
title: "The payload-returning UPDATE arms emit no within-SET value-agreement check"
status: Backlog
bucket: bug
priority: 3
theme: mutation-write
depends-on: []
created: 2026-08-24
last-updated: 2026-08-24
---

# The payload-returning UPDATE arms emit no within-SET value-agreement check

Two input fields can write the same SET column when at least one of them carries a `@nodeId` decode.
The walker admits that overlap deliberately (an all-plain overlap is the `PlainColumnCollision`
reject; a decode-involving one is meant to be reconciled at runtime), and the direct-return single-row
UPDATE emits `emitSetAgreementPreamble` to compare the two decoded values before the DML. The two
payload-returning UPDATE arms, `TypeFetcherGenerator.buildCarrierUpdateChainSingle` and
`buildCarrierBulkPerRowUpdateBody`, do not. They build the SET map with `emitSetMapPuts` and no
preamble, so the second `Map.put` silently clobbers the first and the caller's other value is
discarded with no error.

Found while implementing the straddling-reference partition, which closed the sibling gap on the same
two arms: neither of them emitted the *cross-partition* (WHERE ∩ SET) check either. That half is
fixed, because the walker now states those overlaps as `AgreementObligation` rows on the `UpdateRows`
carrier and all four emit consumers fold over them. This item is the remaining half, and it is
genuinely a different fact: a within-SET overlap is not a WHERE/SET overlap, so it is not an
obligation row, and the direct-return arms derive it at emit time from
`ColumnOverlap.groupByColumn(setGroupWriters(setGroups))`.

## What a fix has to settle

Whether to call `emitSetAgreementPreamble` from the two payload arms (cheap, and the direct-return
arms prove the shape), or to follow the cross-partition fix and have the walker state within-SET
overlaps on the carrier too so that no consumer can omit the check by doing nothing. The second is
the more consistent answer and the reason the first exists is that this emitter derivation predates
the carrier component; the argument for stating it is the same argument that applied to obligations,
namely that a derived fact is one a consumer can silently skip.

Either way it needs its own execution case. The corpus has no payload-returning UPDATE whose input
puts two decode writers on one SET column, so the fixture is part of the work.

---
id: R502
title: "Reads-by-name-off-Record capability for jOOQ-record-backed types"
status: Backlog
bucket: architecture
priority: 4
theme: codegen-correctness
depends-on: []
created: 2026-07-18
last-updated: 2026-07-18
---

# Reads-by-name-off-Record capability for jOOQ-record-backed types

The fact "children read by name off a generic jOOQ `Record`" is already stated twice, as the same
`JooqTableRecordType || JooqRecordType` disjunction emitting the same
`((Record) source).get(DSL.field(name))` read, in `FetcherEmitter.propertyOrRecordBinding` and its
Outcome sibling `FetcherEmitter.inlineSuccessRead`. Per "Capabilities reify an orthogonal axis",
that recurring disjunction should be a capability interface the record-backed `GraphitronType`
variants implement, consulted by the emitters, instead of an `instanceof` OR-list restated per
site. Membership is structural (the variant's runtime carrier is a generic jOOQ `Record`), not a
hand-declared marker, so the capability is drift-safe. When R501 lands its `PivotProjection`
variant (whose slot read is the same by-alias shape via a dedicated leaf), it becomes a natural
third member; this item deliberately stays separate so the pivot slice does not carry a refactor
of two unrelated emitter sites.

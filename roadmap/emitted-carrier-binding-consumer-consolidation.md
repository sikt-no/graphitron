---
id: R619
title: "Consolidate the emitted-carrier producer-binding consumers onto one seam"
status: Backlog
bucket: cleanup
priority: 18
theme: mutation-write
depends-on: []
created: 2026-08-10
last-updated: 2026-08-10
---

# Consolidate the emitted-carrier producer-binding consumers onto one seam

`ProducerBinding` has three emitted-carrier arms (`DmlEmitted`, `ServiceEmitted`, and the routine
arm that `roadmap/routine-mutation-payload-carrier-return.md` adds) sharing a consumer-facing shape:
`reflectedClass`, `tableRef`, `arrival`, the correlation columns, and an identical
`reflectedClass.getName().equals(tableRef.recordClass().reflectionName())` compact-constructor
invariant. Their genuine difference is provenance, consumed only by `describe()` and the
multi-producer rejection.

The carrier-return item lands the `EmittedCarrierBinding` capability over the three arms, so the
shared accessors are read once and totally. What it deliberately leaves alone is the consumer side,
which by then carries the same structure three times over: three memo maps on
`RecordBindingResolver`, three `xEmittedBinding` accessors, and three near-duplicate blocks in
`FieldBuilder.classifyChildFieldOnResultType` (resolve binding, resolve return type, lift a
polymorphic errors field, check the table agreement, call
`buildPayloadCarrierBatchedTableField`). No consumer forks on the arm's identity; every one reads
the same two or three accessors.

Two sibling sites are *not* in this item's scope, because the carrier-return item has to touch them
to work at all: `TypeBuilder.carrierBinding`'s per-family probe (without a routine probe the payload
never registers as a carrier) and `FieldBuilder.transportForParent`'s `activeChannel` disjunction
(without a routine disjunct the carrier's errors field binds `Transport.PayloadAccessor`, which a
directiveless structural carrier cannot satisfy). They arrive already three-armed; if the capability
gained the presence probe that gate needs, the disjunction is already gone by the time this item
starts.

Scope: fold the memo maps and accessors onto the capability, collapse the classify-time blocks to
one, and unify the three table-agreement diagnostics without losing the per-family wording that
existing fixtures pin (the DML block's "payload-returning DML mutations require child @table-bound
fields to bind to the input table" and its siblings). The diagnostics are the real work; the
structure is mechanical.

Deliberately not folded into the carrier-return item: that item touches neither the DML nor the
`@service` emit path, and pulling this in would put both families' emit into its acceptance surface
for a cleanup neither needs.

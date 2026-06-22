---
id: R356
title: "Unify the per-column shared-column overlap analysis across mutation write paths"
status: Backlog
bucket: architecture
priority: 6
theme: nodeid
depends-on: []
created: 2026-06-22
last-updated: 2026-06-22
---

# Unify the per-column shared-column overlap analysis across mutation write paths

The "group writers by backing column, keep groups of two-or-more, an all-plain overlap is a
build-time reject and a decode-involving one needs a runtime value-agreement check" analysis is now
hand-rolled in six places, accreted one per write surface as R322/R354/R342 closed the agreement gap:
`JooqRecordInstantiationEmitter.analyzeOverlap` (`@service`, over `Writer`/`SlotRef`),
`TypeFetcherGenerator.insertColumnPlan` (INSERT, over `InputField` leaves),
`MutationInputResolver.collectSetColumns` (validate-time plain-collision detection),
`TypeFetcherGenerator.emitSetAgreementPreamble` (single-row within-SET, over `SetGroup`),
`TypeFetcherGenerator.emitKeySetAgreementPreamble` (R354's cross-partition WHERE ∩ SET), and
`setColumnPlan` (the bulk-SET dedup R342 adds). R322's D1 and R354 both flagged this as a
"same predicate, multiple consumers, drift risk" smell and named R342 as the place to consider the
lift; R342's spec deliberately declined to bundle the refactor into a feature slice and filed it here.

The trap, surfaced by the `principles-architect` read on R342, is that the six are a *family
resemblance*, not literally one predicate, so a naive "one carrier-agnostic writer utility behind all
six" would be the wrong consolidation. Two sites run at validate time over `InputField` permits; three
run at emit time over three different carrier models (`InputField`,
`CallSiteExtraction.{ColumnBinding,RecordKeyDecode}`, `SetGroup`); and R354's site is a cross-partition
intersection with two contributor lists and a which-partition dimension the within-clause sites never
read. A single type spanning all of them would straddle the validate/emit boundary and carry dead
fields per consumer. The genuine Generation-thinking move is to lift the *column-overlap fact* into the
model carrier (so each consumer reads the grouping off the model rather than re-deriving it), not to
unify the six walks behind an emitter helper. The shared agreement *predicate* already has one home
(`NodeIdEncoder.requireColumnAgreement`); this item is about the structural grouping, and its scope is
the whole six-site surface, which is exactly why it is not a rider on R342. Follows R342 so the lift
captures all six sites including the bulk-SET one R342 introduces.

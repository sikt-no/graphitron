---
id: R171
title: "Fold InputType and TableInputType under sealed parent InputLikeType"
status: Backlog
bucket: architecture
priority: 4
theme: classification-model
depends-on: []
created: 2026-05-17
last-updated: 2026-07-15
---

# Fold InputType and TableInputType under sealed parent InputLikeType

> **Superseded pending the input-entity dissolution (flagged 2026-07-15).** This item (written
> 2026-05-17) proposes *tightening* the input-type hierarchy by adding a sealed `InputLikeType`
> parent. The dimensional pivot reverses that direction: R97 (`consumer-derived-input-tables`),
> R222 (`dimensional-model-pivot`), and R333 (`coordinate-lowers-to-datafetcher-queryparts`)
> dissolve the input-type hierarchy entirely, and R222's absorption ledger already lists this item
> as "Dissolves; no per-input model record survives." Building the fold ahead of that pivot is
> negative work: the sealed root it adds is removed by R97. Do not implement as written. Discard
> candidate once R97 reaches Ready (the point at which the dissolution stops being provisional); it
> is kept for now only because R97/R222/R333 are themselves still Spec/Backlog. Confirm against R97
> before touching this.

`GraphitronType` today permits `InputType` (with four leaves:
`PojoInputType`, `JavaRecordInputType`, `JooqRecordInputType`,
`JooqTableRecordInputType`) and `TableInputType` as siblings. Any
capability uniformly true of "things that come in as SDL input"
(R94's `HasInputRecordShape`, R98's `ConstraintSet`-attachment slot,
any future input-side carrier) must be declared on five places
instead of one, and a sixth input-like variant added to
`GraphitronType.permits` will not get a compile-time miss for the
capability. Fold the two siblings under a `sealed interface
InputLikeType extends GraphitronType permits InputType,
TableInputType`, and relocate input-side capabilities onto that
root. Cleanup item surfaced by R94's capability-declaration site;
deferred from R94 to keep that item narrow. Not blocking: capability
interfaces declared on five sites work today, the fold tightens the
sealed root so the compiler enforces the invariant.

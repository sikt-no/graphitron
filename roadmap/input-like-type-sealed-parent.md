---
id: R171
title: "Fold InputType and TableInputType under sealed parent InputLikeType"
status: Backlog
bucket: architecture
priority: 4
theme: structural-refactor
depends-on: []
created: 2026-05-17
last-updated: 2026-05-17
---

# Fold InputType and TableInputType under sealed parent InputLikeType

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

---
id: R752
title: "JoinedTableReprojection dedupes same-named participant terms first-wins, silently dropping a divergent projection"
status: Backlog
bucket: bug
priority: 6
theme: codegen-correctness
depends-on: []
created: 2026-08-20
last-updated: 2026-08-20
---

# JoinedTableReprojection dedupes same-named participant terms first-wins, silently dropping a divergent projection

`JoinedTableReprojection.of` folds one discriminated interface's joined-table participants into
the base slice the discriminated query projects, and deduplicates the terms first-wins across all
participants: `seenAliases` keys `BaseSliceTerm.InheritedRef` by bare field name and
`BaseSliceTerm.SharedKey` by column SQL name. When two participants' same-named terms genuinely
denote the same projection (the in-tree `Subject`/`Party` fixtures, where every participant's
inherited reference resolves to the same base column), first-wins is correct. When they diverge,
for example two joined-table participants declaring a same-named `ColumnBackedReferenceField`
over different base columns, the second participant's projection is dropped silently: its rows
read the surviving participant's column through the shared `__rk_` result-key alias, with no
build-time diagnostic. This is the joined-route sibling of the single-table participant alias
collision R749 fixes; it was surfaced by R749's spec-time principles consultation and split out
because it is a different producer's defect with no in-tree oracle (no fixture has disagreeing
same-named joined participants).

Direction sketch, to be decided at Spec: the cheap honest floor is a census inside the fold,
comparing the projection identity behind a `seenAliases`-blocked term against the surviving
term's and emitting a `Deferral` (the fold's existing validator-drained channel) on disagreement,
so the shape fails the build instead of returning wrong data; true duplicates keep deduping. The
fuller alternative is extending R749's owner-keyed alias scheme to the joined route. Until either
ships, the joined route is not covered by the correct-or-build-error contract R749 establishes
for the single-table route.

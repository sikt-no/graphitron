---
id: R567
title: "Lookup coordinates: unrealized co-member payloads"
status: Backlog
bucket: generator
theme: classification-model
depends-on: []
created: 2026-08-03
last-updated: 2026-08-03
---

# Lookup coordinates: unrealized co-member payloads

A lookup-keyed coordinate can mint orderBy and paginate members at child grain that no seam
realizes: the launcher lookup row's ordering slot is deliberately empty (a lookup entails input
ordering), the LookupMultiset projection arm passes no orderBy, and the `@orderBy` rejection
exists at root only, so an authored `@defaultOrder` or carried window on a child lookup is
silently dropped at emit. Separately, the batched single-record-per-key lookup combination (a
single-cardinality record-arm lookup, or a loadMany dispatch) fails loud at production
(`LauncherCommands.batchedLookupRow`'s guard) instead of rejecting located at validate time.

Decide per combination: realize the payload at a seam, or reject it located at validation.
Promoting the production throw to a validator rejection is a rejection-grain improvement (a
build crash becomes a located build error), not an emit capability change; realizing the
ordering or window on a lookup SELECT is an emit item in its own right. Surfaced by the
lookup-triplet dissolution's per-kind realization audit, which made the drops legible as member
rows with no realizing seam.

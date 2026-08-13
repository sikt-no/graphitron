---
id: R651
title: "Support @asConnection on a child field returning a single-table discriminated interface"
status: Backlog
bucket: feature
priority: 5
theme: interface-union
depends-on: [root-connection-over-discriminated-interface]
created: 2026-08-13
last-updated: 2026-08-13
---

# Support @asConnection on a child field returning a single-table discriminated interface

## Problem

A child field on a table-backed parent returning a single-table discriminated interface (`@table` +
`@discriminate`, implementers pinned by `@discriminator(value:)`, all sharing one jOOQ table) cannot
be paginated. `FieldBuilder`'s `TableInterfaceType` arm in `classifyObjectReturnChildField` rejects
the pair with a typed deferral, "`@asConnection` on a field returning a single-table discriminated
interface ('X') is not yet supported; return the list shape instead". Nothing pins the rejection
today (no test asserts the message).

The sibling root item (`roadmap/root-connection-over-discriminated-interface.md`) is a contained
emission fix. This one is not, because the child leaf's delivery story is unsettled.

## Why this is the harder half

`ChildField.TableInterfaceField` is emitted by `TypeFetcherGenerator.buildTableInterfaceFieldFetcher`,
an unbatched per-parent SELECT correlated off `env.getSource()`. It is not on the launcher seam and
registers no `DataLoader`, so it is N+1 by construction today. Two routes, and picking between them
is the substance of the item:

* **Per-parent paginated fetch.** Cheap, mirrors what the leaf already does, keeps the existing
  N+1. But the plain table child rejects inline `@asConnection` outright and directs the author to
  `@splitQuery` for batched connection semantics, so shipping this would make the discriminated
  child strictly more permissive than the plain child, on the same authoring surface, for no
  principled reason.
* **Batched.** Consistent with the plain child and with
  `MultiTablePolymorphicEmitter.buildBatchedConnectionRowsMethod` (the windowed-CTE shape the
  multi-table polymorphic child already uses). This needs `@splitQuery` to work on a discriminated
  child first, and it does not: the interface arm of `classifyObjectReturnChildField` never reads
  `forcesSplitDelivery`, so `@splitQuery` on such a field is silently ignored rather than honoured
  or rejected.

## Open questions for Spec

* Which route, and if batched, does `@splitQuery` on a discriminated child become its own
  prerequisite item or land inside this one?
* The silently-ignored `@splitQuery` on this arm is arguably a bug independent of pagination and may
  deserve splitting out: today an author writes the directive and gets no batching and no
  diagnostic.
* Whether the child arm can share the split-out select-list seam the root item introduces in
  `DiscriminatedTableFragments`, or whether the batched shape needs its own assembly.

## Dependency

The root item should land first. It splits `DiscriminatedTableFragments.assembly` so the select list
is a parameter, which is the seam a per-parent paginated child would consume directly, and it
settles the row-fan-out-under-`limit` question that applies identically here.

## Provenance

Surfaced alongside the root item while tracing a consumer report of the root rejection. Both `R405`
and `R406` recorded `@asConnection` as out of scope for this interface family.

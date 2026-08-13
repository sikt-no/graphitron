---
id: R661
title: "Honour or reject @splitQuery on a child field returning a discriminated table interface"
status: Backlog
bucket: bug
priority: 3
theme: interface-union
depends-on: []
created: 2026-08-13
last-updated: 2026-08-13
---

# Honour or reject @splitQuery on a child field returning a discriminated table interface

## Problem

`@splitQuery` on a child field returning a discriminated table interface (`@table` + `@discriminate`)
is gathered and then dropped. `DeliveryFactVisitor` mints the `DeliveryFacts.Row` unconditionally, so
the marker reaches the fact base; the `TableInterfaceType` arm of `FieldBuilder`'s
`classifyObjectReturnChildField` never reads it. The sibling plain table-backed arm, a few lines
earlier in the same method, opens with `forcesSplitDelivery(fieldDef)` and forks its whole delivery
story on it. The interface arm neither honours the directive nor rejects it, so an author writes
`@splitQuery`, gets the unbatched per-parent SELECT that `TypeFetcherGenerator`'s
`buildTableInterfaceFieldFetcher` emits, and gets no diagnostic saying so.

This is a bug independent of pagination. Batched delivery is the directive's entire purpose, and this
arm is N+1 by construction today, so the silent swallow costs exactly what the author was trying to
avoid.

## Why it is also load-bearing

The plain table child rejects inline `@asConnection` outright and names the remedy: "@asConnection on
inline (non-`@splitQuery`) TableField is not supported; add `@splitQuery` for batched connection
semantics". That is the codebase's standing answer to pagination over an inline join: pagination
rides a split query, it does not ride a join welded into the parent statement.

A discriminated interface is an opt-in to subtyping where one base row is one entity, so the base
table is a stable ordering and pagination surface by construction. What can break that is a
non-pattern join sharing the paginating statement. The joined-detail arm is part of the pattern and
is already proven single-valued by `TypeBuilder.resolveJoinedTableParticipant`'s PK=FK check. The
participant cross-table `@reference` is not part of the pattern; it is ordinary navigation that this
arm happens to emit as a gated LEFT JOIN into the base statement rather than splitting it out.

So the interface family currently sits outside the split-query doctrine on both halves: it cannot ask
for a split, and it paginates over joins the doctrine would have split. Wiring this arm is upstream
of the connection work, not a side quest beside it.

## Open questions for Spec

* Honour or reject first. Rejecting is small and stops the silent swallow immediately; honouring
  needs the batched route the connection work wants anyway. Shipping the rejection first is
  defensible only if it is not thrown away by the honouring change.
* If honoured, whether the batched shape reuses
  `MultiTablePolymorphicEmitter.buildBatchedConnectionRowsMethod` (the windowed-CTE shape the
  multi-table polymorphic child already uses) or needs its own assembly over
  `DiscriminatedTableFragments`.
* Whether the same reasoning reaches the *root* connection: if participant cross-table fields belong
  in a split rather than in the paginating statement, the root item's fan-out question dissolves
  rather than needing a build-time cardinality invariant.

## Provenance

Surfaced during the Spec review of `roadmap/root-connection-over-discriminated-interface.md`, whose
sign-off was reopened over exactly this question. Noted as splittable in the open questions of
`roadmap/child-connection-over-discriminated-interface.md`; filed separately because both connection
items now rest on it.

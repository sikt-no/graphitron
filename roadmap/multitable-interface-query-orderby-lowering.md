---
id: R382
title: "Lower orderBy onto multitable-interface/union queries"
status: Backlog
bucket: bug
priority: 3
theme: interface-union
depends-on: []
created: 2026-06-25
last-updated: 2026-06-25
---

# Lower orderBy onto multitable-interface/union queries

## Problem

A root query field returning a multitable interface or union (`QueryField.QueryInterfaceField` /
`QueryField.QueryUnionField`, and the `@asConnection` variant) cannot carry a user-specified
ordering. `operation()` hardcodes `new OrderBySpec.None()` for both arms, and the emitter orders
results solely by the synthetic `__sort__` key (the participant PK). A consumer asking for a
specific order gets PK order regardless.

Split off from R363, which deliberately scoped its day-one work to `@field` filter lowering (the
reported data-correctness bug) and left ordering to this item. The two are siblings: both lower a
per-field surface onto a polymorphic UNION, both must hold the "column present on every participant"
rule (lowered per participant against each participant's own table, so an absent or
type-incompatible column on one participant becomes that participant's classifier rejection), and
both thread their result into each UNION branch in `MultiTablePolymorphicEmitter`.

## Why this is harder than filter lowering

Filters AND into each branch's `.where(...)` and bind to that branch's alias with no effect on the
union's shape. Ordering is structural:

- `MultiTablePolymorphicEmitter.branchProjection` hardcodes `__sort__` as the participant PK
  (single-column PK projects directly; composite uses `DSL.jsonbArray(...)`).
- `buildStage1Block` / `buildMainFetcher` (non-connection root) order by `DSL.field(name("__sort__"))`.
- On the connection path (`buildRootConnectionFetcher` / `buildStage1ConnectionBlock`), `__sort__`
  **is the Relay cursor seek key**: it is projected as `sortField`, fed to `page.seekFields()`, and
  round-tripped through `ConnectionHelper.encodeCursor` / `decodeCursor`, with `__typename ASC` as a
  deterministic tiebreaker so identical PKs across participants page consistently.

So a user orderBy column has to be projected into every UNION branch, *replace* (or compose with)
`__sort__` as the sort and cursor seek key, keep a deterministic tiebreaker so cross-participant ties
still page consistently, and round-trip through the cursor codec (which today assumes the PK column
class, or JSONB for composite). Mixed sort directions and multi-key orderings compound this. This is
the design work the item owns.

## Cross-links

Sibling of R363 (per-participant `@field` filter lowering on the same fields); shares
`MultiTablePolymorphicEmitter`. The single-table discriminator interface
(`QueryTableInterfaceField`) already carries `OrderBySpec` and is unaffected; only the two multitable
polymorphic variants lack it.

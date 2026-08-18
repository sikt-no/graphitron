---
id: R662
title: "Ordering over a routine chain may name columns from any node"
status: Backlog
bucket: feature
theme: routine
depends-on: []
created: 2026-08-13
last-updated: 2026-08-13
---

# Ordering over a routine chain may name columns from any node

When a `@routine` chain joins on to a catalog table, every node of the chain is a live alias in
the emitted query, so the database can order by columns from the routine result and the joined
table alike. The generator cannot express that: ordering resolves against exactly one table, the
chain's terminus, so a column that exists only on the routine's result is unreachable the moment
a `@reference` hop follows it.

The shape this blocks is ordinary. A function that computes a rank, a score, or a
business-defined sequence, hopped out to a catalog table for the projected columns, wants to
project `film.*` and order by `source.rank`. Today the author must either drop the hop (losing
the catalog columns) or drop the ordering intent (and, after R704, the field will not build at
all without some ordering). Neither is a real option, so the ordering the schema can declare is
narrower than the ordering the query could execute.

## Where the constraint actually sits

Three layers each assume a single table, and all three have to move together. None of them is a
database limitation.

* **SDL.** `input FieldSort` carries `name`, `collate` and `direction`; there is no way to say
  *which* node a name belongs to.
* **Model.** `OrderBySpec.ColumnOrderEntry(ColumnRef column, String collation, SortDirection
  direction)` carries a resolved column with no notion of the node it was read from, and
  `OrderByResolver.resolve` takes one `tableSqlName`.
* **Render.** `OrderByFragments.fixedSortParts(fixed, srcAlias)` renders `<srcAlias>.<COL>.<dir>()`
  with one alias applied to every entry, so a spec spanning two nodes is unrepresentable at the
  point of emission even if the model could hold it.

The emit side is already ready. Every `JoinStep` exposes `alias()`, and `TableExpr.RoutineCall`
is "aliased like any table", so both the routine local and the terminus alias are in scope in
`RootLauncherRenderer.routineBody` and in the child inline emit. Nothing new needs to reach the
SQL; the aliases are sitting there unused.

## The design fork: how a column names its node

This is the decision the Spec has to make, and it drives everything else.

* **Implicit resolution** (no SDL change): resolve each name across all chain nodes, unique match
  wins, ambiguity is a build error. Cheapest surface, but ambiguity is the common case rather
  than the exception, since the name-matched hop out of a routine result exists precisely because
  the routine exposes the target's key columns under the same names. `film_id` would be ambiguous
  on every chain that currently works.
* **Explicit qualifier**: `fields: [{table: "films_for_actor", name: "rank"}]`. Unambiguous and
  reads well, but adds a field to `FieldSort`, which is shared with the `@order` enum-value path
  used by `@orderBy`.
* **Terminus by default, qualifier to opt out**: an unqualified name keeps today's meaning
  (resolve against the terminus), a qualified one reaches any node. Additive, preserves every
  existing schema's meaning, and makes the ambiguity problem disappear without a rejection ladder.
  This looks like the right starting point, but it is the reviewer's call.

Whichever wins, the qualifier's vocabulary should be the chain's own node identity rather than a
raw SQL name, so a chain with the same table at two positions stays expressible.

## Notes for whoever picks this up

* **Ordering target is not a performance lever.** Measured for R704 on PostgreSQL 16 over a
  500k-row pair: naming the routine column and naming the joined catalog column produce
  byte-identical plans, for inlinable and opaque functions alike, with and without `LIMIT`,
  because the hop is an equi-join and the columns share an equivalence class. That result is about
  columns *in* the join key. This item is about columns outside it, where there is no equivalence
  and no choice, so the two do not overlap: the case for this item is expressiveness, and no part
  of it should be argued on planner behaviour.
* **`@orderBy` interaction.** `FieldSort` is shared with the enum-value `@order` path. `@orderBy`
  is deferred on routine-backed fields today, so nothing breaks immediately, but an SDL change
  here lands on that path too and should be designed with it in view rather than retrofitted.
* **Pagination.** `@asConnection` over a routine chain is rejected or deferred today. If keyset
  pagination ever reaches these chains, cursor columns spanning two aliases is a harder problem
  than sort columns spanning two aliases, since the cursor has to round-trip values from both.
  `OrderByFragments.fixedColumnParts` has the same single-alias shape as `fixedSortParts` and
  would need the same treatment.

Depends on R704 (`roadmap/routine-composition-surface-from-facts.md`), which makes root chains
ordered at all; ordering that spans nodes is only meaningful once ordering resolves for these
fields in the first place.

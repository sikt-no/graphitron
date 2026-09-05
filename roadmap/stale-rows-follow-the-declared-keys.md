---
id: R924
title: "What must re-run is a walk of the keys the schema already declares"
status: Backlog
bucket: architecture
priority: 2
theme: dev-loop
depends-on: [dev-round-is-told-what-changed]
created: 2026-09-05
last-updated: 2026-09-05
---

# What must re-run is a walk of the keys the schema already declares

## Goal

When one of a consumer's inputs moves, the fact store works out which of its rows are no longer
trustworthy by following the foreign keys it already declares, rather than by each stage holding a
private idea of what its output depended on. A *gatherer* is one of the seven passes that fill the
store; R922 gives them a mark saying which inputs moved. This item is the other half: turning "this
input moved" into "therefore these rows must be recomputed, and nothing else". When it lands, a dev
round refreshes the part of the store its edit reached, and the answer to what that part is comes
from the schema rather than from a list somebody maintains.

## Why the declared keys are the right source

The schema declares 205 foreign keys across roughly 250 relations, and they are already the store's
statement of what depends on what: a child row cannot exist without its parent, so a parent row that
stops being trustworthy takes its children with it.

What makes them better than any list we could write is not that they exist but that they carry the
*join predicate*. A hand-maintained dependency list gives relation names, so the coarsest thing it
can say is "recompute all of `graphitron_tabletype`". A foreign key names the column tuple on both
ends, `(table_source_name, table_schema, table_name)` from `graphitron_tabletype` into `sql_table`,
so the same edge says exactly which rows of the child are reached from a given set of parent rows.
That turns propagation from a relation-level invalidation into a row-level one, which is the
difference between rewriting a partition and rewriting what changed.

And they cannot fall behind. `meta_relation_reference`'s comment already makes this argument for the
documentation reader: the edges are "resolved out of the engine's own catalog, a referential
constraint through the key columns on both ends, so the edges are exactly what the DDL declares and no
second list can fall behind them". A relation added with a foreign key joins the graph by existing.

## Both routes into the graph already exist

The store reads this graph twice today, from opposite sides, and neither reader is this item's.

`meta_relation_reference` is a view over `INFORMATION_SCHEMA.REFERENTIAL_CONSTRAINTS` joined through
`KEY_COLUMN_USAGE`, one row per declared foreign key, naming both relations and the family the census
places each in. It is at constraint grain and deliberately unfiltered, and its comment says the
generated reference "is only the first reader". It drops the columns, which is precisely the part this
item needs, so the SQL route is that view plus a sibling at column grain.

`FactSink.parentsFirst` walks the same graph in Java, through jOOQ's `Table.getReferences()`, as a
depth-first topological sort that tolerates a cycle by short-circuiting on the visiting set. It is what
orders the store's writes parents-first and, reversed in `StoreRefresh.childrenFirst`, its deletes
children-first. A jOOQ `ForeignKey` exposes both ends' fields, so the join predicate is available on
this route too.

So the choice is not whether the graph is reachable but which reader to build on, and the item should
pick one rather than adding a third.

## What it replaces

`StoreRefresh`'s graph-scoped clear deletes and rewrites the whole of a round's graph partition
regardless of what changed, and every registered materialization downstream of it is then re-derived.
That is the largest unconditional cost in a round by orders of magnitude: a real consumer capture was
measured from inside the refresh at 15477.1 seconds over twenty registrations. R857 is specifying the
currency claim that decides which registrations may be skipped; what it does not have, and what this
item supplies, is the derivation of which claims one moved input actually falsifies.

## The limit to design around

A foreign key is a referential dependency, not a derivational one. A view that joins two relations
produces rows keyed by neither, so the FK graph under-reports what a derivation reads. The store
already knows this: `meta_materialize_dependency` exists because "transitive reach through
unregistered intermediate views is not a plain SQL view's to express", and it is derived at boot from
the stored view definitions rather than declared.

So the closure has two edge sources, the declared keys and the registered views' derived reach, and the
item's correctness argument is about their union rather than about the keys alone. The keys are the
backbone and the view edges are what closes the derivations over them.

## What Spec owes

* Which reader to build on, the information-schema view or jOOQ's references, and the column-grain
  sibling the chosen one needs.
* Whether the transitive closure is computed on demand with a recursive query or derived once at boot
  into a table, which is the shape `meta_materialize_dependency` already chose for the harder half and
  the precedent to argue against or follow.
* The grain at which staleness is recorded once propagated: per row, per key range, or per partition.
  This decides whether the saving is real, because a closure that is exact and then rounded up to the
  partition has bought nothing.
* Measurement against the unconditional cost above, on the same consumer, so the item can be judged
  on what it removes rather than on the elegance of where the edges come from.

## Provenance

Reached at the end of the thread that produced R922 and R923. R922 records what moved; the question
it deliberately leaves open is what that makes stale. The answer began as a proposal to declare the
dependency edges by hand and to lean on `meta_gatherer_dependency`, which is gatherer grain and far too
coarse to invalidate rows with, and settled on the foreign keys once it was clear they carry the join
predicate and are resolved from the engine catalog rather than maintained beside it.

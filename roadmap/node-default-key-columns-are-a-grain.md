---
id: R902
title: "@node key columns defaulted from the primary key are a grain table, not a read-time tier"
status: Backlog
bucket: architecture
priority: 3
theme: nodeid
depends-on: []
created: 2026-08-31
last-updated: 2026-08-31
---

# @node key columns defaulted from the primary key are a grain table, not a read-time tier

An `@node` that omits its key columns takes the bound table's primary key in declaration order, which
the user manual documents as the default. Nothing captures that. It is the third arm of
`intent_resolved_node_key_column`, reconstructed on every read by joining the type's resolved binding
to `sql_primary_key` and `sql_constraint_column`, under a `DENSE_RANK()` window that picks the
winning tier.

**Nothing blocks capturing it, which took a wrong answer and a correction to establish.** The
gatherer that decodes `@node` runs after the one that writes both the database catalog and the
bytecode classpath census, and every base table the resolution reaches belongs to a gatherer at or
before it. An earlier reading held that the classpath census belonged to a later gatherer; it does
not, and the family that does, `java_`, is read by no view in this schema.

**The measurement is what makes this interesting, and it points the other way from the title.** On
the one consumer schema measured, the tier distribution of `intent_resolved_node_key_column` is 929
`JOOQ_METADATA`, 2 `SDL_PINNED` and **zero** `CATALOG_PRIMARY_KEY`. The defaulted arm returns no
rows. So capturing it would store an empty table, while the arm itself goes on costing three joins
and a rank tier on every read of the resolved key, and this relation is read by a registered target.

**Which makes the real question whether the arm should exist at read time at all**, and the title of
this item is a hypothesis rather than the answer. Three shapes are worth pricing against each other
rather than one being assumed:

- Capture the default as a grain table keyed on graph, type and key position, and have the arm read
  it. Right if the population is non-empty on consumers other than the one measured.
- Capture it and find it stays empty, in which case the honest outcome is that the manual documents
  a default that the generator's own metadata always beats, and the arm is dead code with a
  documentation bug behind it.
- Leave the default where the manual puts it and remove the arm from the resolution, making the
  fallback something a reader asks for rather than something every read pays for.

**What this item owes before it chooses.** The tier distribution on more than one consumer schema,
since a single capture cannot tell an empty population from an unrepresentative one. And a reading of
whether the jOOQ metadata arm and the primary-key arm can ever disagree, which the resolution view's
own comment says nothing records today.

**One thing not to fold in.** Whether the whole relation becomes a keyed grain table rather than a
window-function view is a register question and belongs with the item that holds the register.

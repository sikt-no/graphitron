---
id: R872
title: "A capture of one graph deletes another graph's node-identity and routine facts"
status: Spec
bucket: bug
priority: 2
theme: tooling
depends-on: []
created: 2026-08-28
last-updated: 2026-09-06
---

# A capture of one graph deletes another graph's node-identity and routine facts

## Goal

Capturing one graph stops deleting another graph's catalog facts. A *graph* is one consumer schema
and the configuration it is generated under, the unit a capture runs for; a *source* is one thing a
run crawled, which for the catalog families is one generated jOOQ package; a *partition* is the rows
one source owns in a relation that several sources share. Today a *warm* capture, one into a store
that already holds a previous run's rows, empties `sql_node_metadata`, `sql_node_key_column`,
`sql_routine` and `sql_routine_parameter` for every source in the store and refills them only for the
sources its own census names. So in the ordinary workspace of two modules on two jOOQ packages, which
`GraphitronModelStore` describes the file-backed store as being shared by, building module A blanks
module B's node-identity and routine facts. B's language server then answers "no node metadata" for a
table that publishes it, and answers nothing for a routine that exists, until B is captured again.

When this lands, the relations the catalog walk deletes per source are exactly the relations the warm
refresh retains per source, and a build-time gate makes the two sets unable to disagree again.

The urgency is that the self-repair is about to be withdrawn. A jOOQ package partition carries no
content stamp, so today's catalog walk rewrites it unconditionally and B recovers on its next
capture: the damage is a window, not a permanent loss. R857 is the item that stops a round rewriting
what did not change, and on the day it lands the window becomes forever, because B's capture will
correctly decide it has nothing to rewrite while the rows are gone. That is why R857 names this item
as its only remaining dependency, and why this is worth fixing ahead of it rather than inside it.

## The defect, in the order it happens

`StoreRefresh.PARTITIONED` names the relations whose rows survive a warm capture by source rather
than being emptied. It lists ten `sql_` relations, seven `jvm_` ones and four `java_` ones. Four
source-keyed `sql_` relations are missing from it. A relation absent from that set and carrying no
`graph_name` column falls through every exclusion in `StoreRefresh.wholesale()` and is emptied by
`clear`'s wholesale arm, which is a predicate-free `deleteFrom(table).execute()`, on every warm
capture of any graph. `CatalogFactCapture` then re-inserts rows for the sources this run's census
names and for no others.

The omission is visible from three directions, which is what makes it an oversight rather than a
decision. `CatalogFactCapture.clearSchemaSources` already deletes all four per source, in the same
loop body as the ten listed relations and immediately before the `ClasspathSources.upsert` that takes
the source over, which is exactly the per-source delete `PARTITIONED`'s own javadoc says a member must
have. `sql_node_metadata`'s table comment states the intent outright, placing the relation under the
`sql_` family because the constants "ride on the same generated package `sql_table` partitions on,
refreshed in the same clearing round by the same walk", and adding that "a family boundary here would
cut one refresh unit in half", which is what the omission does. And all four lead their primary key
with `source_name`, so `FactSchemaGateTest.everyRelationLeadsWithItsPartitionDimension` already
classifies them as source-partitioned.

### The wholesale arm has no other members

Re-derived from the DDL against `wholesale()`'s own predicate, the reactor's 176 base relations
divide as 140 graph-scoped by their `graph_name` column, 21 in `PARTITIONED`, 8 in the `meta_` family,
3 `store_` relations `wholesale()` names by hand, and 4 left over. The 4 left over are exactly the
four this item is about. The arithmetic closes with nothing unaccounted for, so the wholesale arm's
entire population today *is* the bug: there is no relation in the store that is legitimately emptied
outright by a run.

That reframes what the fix has to be. Adding the four constants is not a patch that leaves a working
mechanism slightly larger; it empties a code path that has never had a correct member, and it leaves
behind a default that silently selects that path for anything nobody classified.

## Adding the four constants

`SQL_NODE_METADATA`, `SQL_NODE_KEY_COLUMN`, `SQL_ROUTINE` and `SQL_ROUTINE_PARAMETER` join
`StoreRefresh.PARTITIONED`. Their per-source delete already exists in `clearSchemaSources`, in the
FK-safe order the family needs (`sql_routine_parameter` before `sql_routine`, `sql_node_key_column`
before `sql_node_metadata`), so nothing else in the write path moves.

## The gate: an unclassified relation stops the build

The Backlog filing asked a spec to settle the gate that keeps the set honest in the reverse
direction, because today's anchor only holds one way. A relation added to `PARTITIONED` with no
matching per-source delete keeps rows whose partition went away, and
`WarmStartRefreshTest.warmAndColdAgreeRelationByRelation` catches that as a warm-versus-cold row-count
difference. Nothing catches the reverse, a relation whose walk deletes it per source while the
wholesale clear empties it anyway, which is this defect.

The plan is to flip the wholesale arm's polarity and gate the residue.

`wholesale()`'s derivation by subtraction is replaced by an explicitly declared `RUN_OWNED` set,
initially empty, and `clear`'s third loop iterates that. The subtraction moves into a build-time gate
stated as a completeness claim: every base relation is accounted for by exactly one lifetime rule,
being graph-scoped by its `graph_name` column, source- or file-partitioned by `PARTITIONED`,
schema-authored under `meta_`, lifetime-managed as one of the three named `store_` relations, or
run-owned by `RUN_OWNED`. A relation matching none of them fails the build, naming itself and asking
for the decision.

This is a deliberate reversal of the polarity `wholesale()`'s javadoc currently defends, that "a
relation nobody thought about is emptied and rebuilt, never silently retained". The reversal is
warranted because that reasoning holds only for a relation the run owns entirely, and "nobody listed
it" is not evidence of that. In a store shared by a workspace's modules, emptying a relation nobody
thought about is not the safe default; it is this bug. The gate keeps what the old polarity was
protecting, since a relation nobody classified is still not silently retained, and stops the build
instead of silently destroying rows.

The empty `clear` loop stays rather than being deleted with its set. It is the shape a genuinely
run-owned relation would use, and its FK-safe `childrenFirst` ordering is the part that is easy to get
wrong; deleting it means whoever first answers the gate with `RUN_OWNED` writes that ordering from
scratch.

## Implementation

- `StoreRefresh.PARTITIONED` gains the four constants, with the javadoc's family sentence updated to
  the new count and the `sql_` clause naming the catalog walk as their delete site, which it already
  does for the ten.
- `StoreRefresh.wholesale()` becomes `RUN_OWNED`, a declared and initially empty `Set<Table<?>>`,
  carrying the javadoc for what membership asserts: that no partition of this relation belongs to any
  run but the current one. `clear` iterates it unchanged.
- `StoreRefresh` gains a package-private `unclassified()` returning the base relations no lifetime
  rule claims, which is the old subtraction with `RUN_OWNED` in `PARTITIONED`'s place. Production code
  does not call it; the gate does, and having it beside the rules is what keeps it from re-deriving
  them differently.
- No change to `CatalogFactCapture`, to the DDL, or to what any walk writes. This item moves a
  retention decision only.

## Tests

- **The regression, in `WarmStartRefreshTest`.** Capture graph B over one jOOQ package fixture and
  graph A over a different one, so the two share no source at all, then capture A warm and assert B's
  rows are unchanged. Asserted as a sweep rather than over four hand-named relations: the test derives
  the source-partitioned relations from the schema metadata (base relations whose primary key leads
  with `source_name`, less `store_source`) and compares B's rendered row sets before and after, so a
  relation added to the family later is covered without the test being edited. `CaptureCorpusIsolationTest.contentsOf`
  is the rendering shape to copy.
- **The control that keeps it honest.** The sweep passes vacuously for any relation the fixtures leave
  empty, so the same case asserts separately that B holds rows in each of the four relations this item
  adds, before A's capture runs. The default fixture catalog publishes node metadata, which
  `CaptureCorpusIsolationTest.theCatalogArmIsNotVacuous` already relies on, and declares the
  `films_for_actor` table-valued function that fills the two routine relations; the fixture pairing has
  to put that catalog under B.
- **The gate, in `graphitron-model`'s unit tier.** A new test in `no.sikt.graphitron.model.capture`,
  where `StoreRefresh` is reachable, asserting `unclassified()` is empty and naming what to do when it
  is not. It carries a floor on the number of relations it classified, so a rename or a metadata
  change that empties its input fails rather than passing vacuously; that failure mode was recorded
  against `FactSchemaGateTest.currencyAccompaniesEveryStamp` at R922's Done gate and is cheap to avoid
  here.
- Both directions of the loop are then held: this gate for a per-source relation left out of the set,
  and the existing warm-versus-cold anchor for a set member whose per-source delete nobody wrote.

## Other solutions we've considered

**Deriving `PARTITIONED` from the key instead of listing it.** Every member leads its primary key with
its partition dimension, and the partition-dimension gate already enforces that, so membership could
be computed as "leads with `source_name` or `file`, less `store_source`" and this bug would be
structurally impossible rather than gated. Rejected because it trades one silent failure for the
other one. Membership in `PARTITIONED` asserts that a per-source delete exists somewhere, in `clear`
for `jvm_`, in the catalog walk for `sql_`, in `JavaSourceFacts` for `java_`, and a key column is no
evidence of that. A derived set would silently enrol a relation whose delete nobody wrote, which keeps
rows whose partition went away. The list stays a list, checked from both sides.

**Adding the four and leaving the polarity alone.** The narrower fix, with a gate asserting only that
no relation in the wholesale set leads its key with `source_name`. It closes this defect and nothing
else: a future relation at a new grain, or at the `file` grain the `java_` family already uses, walks
into the same hole with the gate silent. Given that the residue is empty either way, the broader gate
costs one extra clause and covers grains nobody has invented yet.

**Accepting the loss and repairing it on read.** Not viable past R857, which is the item this blocks:
today's unconditional reader-side refresh recomputes the affected targets from the emptied relations
rather than repairing them, and R857 exists to stop that pass running unconditionally.

## Provenance

Found while reviewing R857, whose currency rule needs these relations rewritten only inside a
transaction that upserts the owning source's `store_source` row. Registrations in the node-id family
read `sql_node_metadata` and `sql_node_key_column` through `intent_node_metadata_defect` and
`intent_inferred_node_type`, so that item depends on this one. `sql_routine` and
`sql_routine_parameter` have no registered materialization reader yet, so the next registration naming
a routine would have walked into the same hole silently.

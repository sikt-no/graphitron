---
id: R872
title: "A warm capture empties four source-keyed catalog relations for every source, having partitioned only the ten it lists"
status: Backlog
bucket: bug
priority: 2
theme: tooling
depends-on: []
created: 2026-08-28
last-updated: 2026-08-28
---

# A warm capture empties four source-keyed catalog relations for every source, having partitioned only the ten it lists

`StoreRefresh.PARTITIONED` names the relations whose rows survive a warm capture by source rather
than being emptied. It lists ten `sql_` relations, nine `jvm_` ones and four `java_` ones. Four
source-keyed `sql_` relations are missing from it: `sql_node_metadata`, `sql_node_key_column`,
`sql_routine` and `sql_routine_parameter`. A relation absent from that set and carrying no
`graph_name` column falls through every exclusion in `StoreRefresh.wholesale()` and is emptied by
`clear`'s wholesale arm, which is a predicate-free `deleteFrom(table).execute()`, on every warm
capture of any graph. `CatalogFactCapture` then re-inserts rows for the sources this run's census
names and for no others.

So a capture of graph A destroys graph B's rows in all four relations, even where A and B share no
source at all. That is the ordinary two-modules-two-jOOQ-packages workspace, since
`GraphitronModelStore` describes the file-backed store as a per-user cache shared by one workspace's
modules. Nothing repairs B until B is captured again.

It reads as an oversight rather than a decision, and the omission is visible from three directions.
`CatalogFactCapture.clearSchemaSources` already deletes all four per source, in the same loop body
as the ten listed relations and immediately before the `ClasspathSources.upsert` that takes the
source over, which is exactly the per-source delete `PARTITIONED`'s own javadoc says a member must
have. `sql_node_metadata`'s table comment states the intent outright, placing the relation under the
`sql_` family because the constants "ride on the same generated package `sql_table` partitions on,
refreshed in the same clearing round by the same walk", and adds that "a family boundary here would
cut one refresh unit in half", which is what the omission does. And the four relations are keyed on
`source_name` leading, so `FactSchemaGateTest`'s partition-dimension gate already classifies them as
source-partitioned.

The likely fix is adding the four constants to `PARTITIONED`. What a spec has to settle is the gate
that keeps the set honest in the other direction. Today's anchor catches a relation added to
`PARTITIONED` with no matching delete; nothing catches the reverse, a relation whose walk deletes it
per source while the wholesale clear empties it anyway, which is this defect. That predicate is
derivable: a base relation leading its key with `source_name` and outside `PARTITIONED` is either
this bug or a relation no walk writes per source, and the second class is enumerable. `sql_routine`
and `sql_routine_parameter` have no registered materialization reader yet, so the next registration
naming a routine walks into the same hole silently.

Found while reviewing R857, whose currency rule needs these relations to be rewritten only inside a
transaction that upserts the owning source's `store_source` row. Four of the twenty registered
materializations read `sql_node_metadata` and `sql_node_key_column` through
`intent_node_metadata_defect`, so that item depends on this one. The data loss is this item's, not
R857's: today's unconditional reader-side refresh merely recomputes those four targets from the
emptied relations rather than repairing them.

---
id: R872
title: "The keys say what a source owns, and the gatherer decides how to refresh it"
status: Spec
bucket: architecture
priority: 2
theme: tooling
depends-on: []
created: 2026-08-28
last-updated: 2026-09-08
---

# The keys say what a source owns, and the gatherer decides how to refresh it

## Goal

The store says what a source owns in the only place that cannot go stale, its own foreign keys, and
then stops deciding for each gatherer how that source gets refreshed. A *source* is one input the
store has read, a `.graphqls` file, a jar, a generated jOOQ package or a `.java` file, each carrying a
row in `store_source`; a *partition* is the rows one source owns in a relation that several sources
share; a *gatherer* is one of the passes that fill the store, each answering for one corpus.

The foreign keys carry `ON DELETE CASCADE`, and that is a statement about ownership rather than a
schedule for deleting anything. It says a fact cannot outlive the source that produced it, so any
delete anybody does issue is complete without being told what else to remove. What it does not say is
when a delete happens or whether one happens at all. A gatherer that can work out what actually
changed should reconcile against the store and touch only that; a gatherer for which that is not worth
the effort can delete its root row and walk fresh, and gets a correct result in one statement. Both
are legitimate, the choice belongs to the gatherer that knows its corpus, and the keys hold either
way.

Today no gatherer has that choice, and what this removes is a mechanism rather than a bug.
`StoreRefresh` empties every relation outright unless it appears in `PARTITIONED`, a hand-maintained
list of 21 exemptions, and the exempted ones are deleted per source by twenty-one hand-written
statements, seven in `StoreRefresh.clear` and fourteen in `CatalogFactCapture.clearSchemaSources`,
hand-ordered children before parents. Membership on the list is an
unverified promise that one of those statements exists somewhere else. That is the store dictating one
refresh strategy, the crudest one, to every corpus at once, and getting it wrong for four relations
left off the list. When this lands there is no list, no exemption polarity, no wholesale arm and no
hand-ordered delete: retention becomes the decision not to issue a delete, and completing a delete
becomes the database's job.

For a consumer the immediate effect is that building one module stops destroying another module's
facts. Four relations are missing from the list today, so a warm capture of graph A empties
`sql_node_metadata`, `sql_node_key_column`, `sql_routine` and `sql_routine_parameter` for every source
in the store and refills them only for the sources A's own census names. In the ordinary workspace of
two modules on two jOOQ packages sharing one store, A's build blanks B's node-identity and routine
facts until B is captured again, and the reach is wider than the rows: `Nodes.derive` resolves a node
from a declared arm or a published arm joining `sql_node_metadata`, so an emptied partition can change
which of B's types are nodes at all. Under this design that defect is not fixed, it is
unrepresentable. There is no list to be left out of.

The reason to do this now rather than patch the list is R857, which stops a round rewriting what did
not change. Today the loss self-repairs, a jOOQ package carrying no stamp so the catalog walk rewrites
it unconditionally, and R857 withdraws exactly that: on the day it lands, B's capture will correctly
decide it has nothing to rewrite while the rows are gone. R857 exposes a second loss the same way,
on the files two graphs share: the record of reading is store-global while an SDL row is the graph's
own reading, so a graph can skip a re-walk on a stamp its sibling moved. Both are one shortfall, a
store that partitions its facts more finely than it partitions its record of having read them.
R857 also needs what this item builds. A
refresh that can delete one source's facts precisely is the prerequisite for a refresh that re-reads
only one source.

## The design

One registry, one web of ownership, and two operations over it.

`store_source` is the registry: one row per input the store has read, whatever read it. It gains a
`JAVA_SOURCE` kind alongside `SCHEMA_FILE`, `JAR`, `DIRECTORY` and `JOOQ_SCHEMA`, so a consumer's
`.java` file is a source like any other. That is a correction rather than an extension: the family
was given its own bookkeeping on the grounds that `store_source` is a capture round's read set, but
being read on a different cadence by a different gatherer is what `meta_gatherer` and `meta_corpus`
are for, and the SDL and classpath gatherers already share the registry while running on cadences of
their own. `java_file` keeps `source_root`, which is a fact about the file, and its `stamp` and
`read_at` move up to the registry row so currency is stated in one place for every corpus.

**The foreign keys declare ownership. They do not decide when anything is deleted.** `ON DELETE
CASCADE` says what belongs to what, which is a fact about the model and is true whether or not a
delete ever runs. What it buys is that any delete anybody does issue is complete by construction.
Two different operations then use it, and they are not the same operation.

### Refreshing a source that changed

The file still exists and its registry row still identifies it, so nothing deletes that row. What a
content change invalidates is the facts read out of the file, and a gatherer has two honest strategies
for reaching them.

A gatherer with a changed source has two honest strategies, and the item's job is to make both
available rather than to pick one.

**Reconcile.** Walk the source, compare against what the store holds, write what differs, delete what
is gone, and leave what matches alone. This is the better answer wherever a gatherer can afford it,
and not only because it writes less. A reconciling gatherer *knows what changed*, and that knowledge
is exactly what the rest of the refresh work needs: R857 wants to re-derive only what an edit reached,
and R924 wants to propagate staleness along the declared keys. Delete-and-rewalk destroys that
information by construction, since afterwards nothing can tell a row that never changed from one
deleted and rewritten identically. So the crude strategy is not merely cruder, it discards the input
the next item wants.

**Delete the root and walk fresh.** One statement, no comparison, and correct because the cascade is
complete. The right trade where the walk is cheap and the corpus small, which is a real case: a jOOQ
package carries no stamp and its walk costs milliseconds, so the catalog gatherer has little to gain
from comparing.

The rest of this section serves the second strategy, because it is the one that needs the schema to
name a root. A *root* is precise: a relation whose rows hang directly off the registry row, so that
deleting them reaches the family's whole subtree. Enumerated from the keys rather than assumed,
because the obvious answer is wrong in one place.

[cols="2,3,4"]
|===
| Family | Its root rows | One delete each replaces

| `sql_`
| `sql_schema` **and** `sql_enum_binding`
| the fourteen per-source statements in `CatalogFactCapture.clearSchemaSources`, three in its first
  round and eleven in its second. Two roots, not one:
  `sql_enum_binding` keys `(source_name, class_fqn)` and its `table_schema` is nullable, so it hangs
  off the registry rather than off a schema and no edge can be added to fold it in

| `jvm_`
| `jvm_class`
| the seven hand-unrolled deletes in `StoreRefresh.clear`, hand-ordered children before parents

| `java_`
| `java_file`
| the family's own declaration sweep

| `graphql_`, `graphitron_`
| `store_graph_source`, the `(graph, source)` row
| a scoped delete across 52 relations, which is why this family needs its root named rather than
  assumed
|===

So a gatherer taking that route deletes one row per root per changed source, the cascade clears
everything beneath, and the walk rewrites.

That last clause is precise for three of the four family groups and not yet for the fourth, which the
roots table above cannot show because it names a joint root for two families one gatherer does not
write. `GraphitronFactCapture.capture` takes a graph name and nothing else, and `SOURCE_NAME` appears
once in the class, so the decode runs over the graph's whole transcription rather than over one source:
a per-source delete of the `(graph, source)` root cascades the 40 owned `graphitron_` relations away
and no per-source walk rewrites them. So the joint root buys per-source precision for the 12
`graphql_` relations, and the `graphitron_` half is either re-decoded whole or left empty until one
gatherer writes both halves; R876's first slice moves those 40 writes into the walk and closes it.
Nothing ships broken from this, the item changing no gatherer's route and the SDL families still being
re-walked per graph, and this item takes no dependency on that slice. What it does take is the
project's family ordering, the transcription families first and the decode family after, which is the
same ordering the gap already has.

The registry row persists either way and its `stamp` and
`read_at` update in place, which is where R922's currency comparison expects to find them. Nothing
here obliges a gatherer to take that route, and the item ships no change to which route any existing
gatherer takes; what changes is that the choice becomes theirs to make.

**Deleting the `(graph, source)` row is not the mechanic this plan rejects, and the difference is worth
being explicit about since the two look alike.** `store_source` holds the file's identity, which a
refresh does not change and must not churn. `store_graph_source` holds *this graph's reading of that
file*, and a re-read genuinely replaces the reading, so the row going and coming back is the fact
being restated rather than an identity being churned to trigger a side effect. A reviewer should test
that claim rather than take it: if the reading row ever acquires state worth preserving across a
re-read, this stops being true and the family needs a root of its own. Phase four gives the row a
`stamp`, which is the state that clause anticipates and does not trip it: the value is what the
graph's last read established, so a re-read replaces it with the read just performed. What would trip
it is state a re-read cannot restore, and the row carries none.

**A refresh in one corpus can validly delete rows in another family**, and the family-by-family table
hides it. `graphitron_tabletype` and `graphitron_field_table` hang off `sql_table`, and
`graphitron_node_keycolumn` off `sql_column`, so refreshing a jOOQ package deletes those decode rows.
That is correct, since a decode resolved against the catalog cannot outlive the catalog row it
resolved against, and it is exactly the kind of edge a hand-written per-family delete has no way to
find.

`store_graph_source` earns its place here without being a second registry. It already exists, already
keys `(graph_name, source_name)`, already carries foreign keys to both sides, and is already
maintained by `GraphSourceMembership`. Making the 52 source-owned SDL relations hang off it
gives that family the same shape `sql_schema` and `jvm_class` give theirs: one row to delete, one
cascade, no roster.

### Removing a source that went away

A schema file deleted, a jar dropped from the classpath, a `.java` file removed. Here the registry row
is exactly what should go, and one rule covers every family: **a removal flags, and the owner reaps.**
Deleting a registry row deletes exactly that row and nothing else.

Every root carries the indirection that makes this possible. `store_graph_source`, `sql_schema`,
`sql_enum_binding`, `jvm_class` and `java_file` each gain a nullable `source_ref` holding a second copy
of the source name their primary key already carries, under
`CHECK (source_ref IS NULL OR source_ref = <the key column>)` and
`FOREIGN KEY (source_ref) REFERENCES store_source (source_name) ON DELETE SET NULL`. Deleting the
registry row nulls that column on every root that named the source and deletes none of them, so every
subtree hanging off those roots survives. A null `source_ref` is not a missing attribution, the
attribution being in the primary key where it always was; it is the registry withdrawing its vouch for
a reading, and it is discoverable in one predicate.

Each owner then reaps its own with one statement whose cascade clears the subtree beneath, and the
predicate is the same everywhere: `WHERE source_ref IS NULL`. Two scopes, and the difference between
them is exactly the difference between an owned fact and a shared one. A graph's readings are owned, so
the membership sweep is scoped to the running graph and another graph's readings wait for that graph's
own next boot. The `sql_`, `jvm_` and `java_` roots are shared and graph-independent, so a flagged one
is dead for everybody and whichever capture runs next may reap it without overriding anyone.

Letting the removal cascade instead would be the one place this item contradicts its own thesis.
Refresh is already each gatherer's decision about its own rows, and a cascading removal would have one
process reach into every other partition and delete rows there, on a cadence their owners know nothing
about. It would also destroy what the deletion knows: an owner that finds its rows gone can only
re-walk, where an owner that finds them flagged knows which source went and can reconcile, which is the
argument this plan makes for preferring reconciliation everywhere else. And it opens a window, between
the removal and the other graph's next boot, in which that graph's coordinate anchors stand with their
declarations deleted and any reader of its facts sees half a graph.

**What the schema gives up, and what gives it back.** A child cannot carry both a cascading and a
set-null edge to one parent: H2 accepts the declaration and the cascade wins, so the row is deleted and
the null never happens. Each of those five roots therefore loses the foreign key on its primary-key
source column, and with it the guarantee that a root names a registered source. The gatherers enforce
that key instead, on every refresh, in `StoreRefresh.prepare`, which already runs before the walk on
the warm path and already takes the graph's name. One predicate covers both failures the missing key
would have caught, because both are the same fact: a row whose `source_ref` is null is a reading the
registry does not vouch for, whether the source was removed under it or a write left the column unset.
Which of the two it is, a `store_source` row for the name existing or not, is worth reporting and
changes nothing about the remedy: delete the root row, let the cascade take the subtree, and let the
source be re-read if this round's inputs still name it.

The sweep is derived rather than listed, which is the same argument the rest of the item makes: the
relations to sweep are the ones the schema declares a set-null twin on, read from the store's own
metadata, so a family root added later is swept without anybody remembering to add it. A roster here
would be `PARTITIONED` again in a new costume.

**The three relations that key into the catalog need nothing, and it is worth saying why rather than
leaving a reader to wonder.** `graphitron_tabletype`, `graphitron_field_table` and
`graphitron_node_keycolumn` are the only relations of their family that reference the catalog, and each
is an *anchor*: it holds what a spelling resolved to and nothing a reader could not recompute.
`graphitron_tabletype` carries the resolved `(source, schema, table)` and no authored text at all,
while the `@table` an author wrote lives beside it in `graphitron_table_entry` with its `table_ref` and its
declaration position, keyed into `graphql_type_declaration`. The other two pair off the same way, with
`graphitron_node_entry` and `graphitron_node_keycolumn_entry` carrying the authored `type_id` and
`column_ref`, and `graphitron_field_navigation` carrying the navigation `graphitron_field_table`
resolves.

So a removed jOOQ package deletes resolutions that name a table which no longer exists, which is the
only correct outcome for them, and deletes nothing anybody wrote. Every entry hangs off the graph's SDL
transcription and therefore off the membership row, where this graph's twin protects it whoever removed
the source. The graph re-resolves on its next capture from entries that never left, with no source
re-read anywhere.

That is the rule the twins implement, stated positively: **an entry is owned and the twin protects it;
an anchor is a function of both sides and dies with either.** The taxonomy above already puts these
three in the descendant bucket with nothing to do, which is that rule applied. It is also R876's own
vocabulary for this family, so the two items cut it the same way from opposite directions.

It is also an operation the store does not currently have at all, which is worth stating as a gain
rather than leaving implied. Nothing in the tree deletes a `store_source` row: there is no
`deleteFrom(STORE_SOURCE)` anywhere, and `StoreReaper` reaps whole store *files* from disk rather than
partitions inside one. `StoreRefresh` says so from the other side, retaining a source absent from this
run's input set on the correct ground that another graph may still need it, and nothing ever asks
whether any graph still does. So a jar that leaves a consumer's classpath keeps its classes for the
life of the workspace cache, and the store grows monotonically across a project's dependency churn.
This item does not add the reaping policy, which is a question about when a source is known to be
unwanted rather than about how to remove it, but it is what makes the removal expressible at all
rather than as twenty-one hand-written deletes and a graph-by-graph sweep nobody has written. That bears on R917 and on the cache byte-budget item, and both should
be told the mechanism exists rather than each inventing one.

### A source file can belong to several graphs

An earlier version of this section gave `store_source` a `graph_name` column and refused a second
graph claiming a schema file another owns. The rule is dropped. It is unsatisfiable, on the file it
was least likely to be tested against: `SdlFactCapture.captureSources` walks every source name the
registry hands back and `ClasspathSources.upsert` writes the bundled `directives.graphqls` as one
`SCHEMA_FILE` row, with a `store_graph_source` membership per graph, so the rule demands a single
owner for a file every graph reads by construction and the refusal fires on the second module of every
workspace. Two shipped tests pin it from both ends, `TaggedCaptureStampTest` on the single registry row
and `FactCaptureAgreementTest.graphSourceMembershipEqualsTheRunsReadSet` on the two memberships. We
also have consumer files shared between graphs, so the rule was not merely unsatisfiable in the
generator's own corner.

Of the three ways out, this takes the third: drop the column and hold the rule where the fact lives, or
do not hold it at all. Sharing is supported, and the design already carries it because the two kinds of
fact key differently. `jvm_class` keys `(source_name, class_name)` with no graph dimension, a class
declaration being a function of the bytes, so two graphs reading one jar want the same rows.
`graphql_type_declaration` keys `(graph_name, type_name, source_name, source_line, source_column)` and
carries `merge_ordinal`, "capture-assigned position in merge order", which is a property of the graph's
document set rather than of the file: two graphs listing one file beside different siblings assign it
different values. A row of that family is the graph's *reading* of the file. So the source-keyed
families root at `store_source`, the graph-keyed ones root at the `(graph, source)` row, each graph
holds a root of its own over a shared file, and a refresh reaches exactly its own rows. That is the
shape the roots table above already has, and sharing needs no structure the store does not have.

What is missing is one column. `store_source.stamp` says what the bytes are; nothing says what content
a given graph's rows were built from, and with the reading per graph that is the fact a graph needs.
`store_graph_source` gains `stamp`, the content identity this graph last read the source under, and the
currency test becomes an equality between the two columns: a database comparison with no file I/O, so a
shared file is hashed once a round and each graph decides for itself from its own record. Without it
the hazard arrives with R857 rather than being live today, and it is silent: A edits a shared file and
re-walks, the registry stamp becomes the new hash, and B's next round finds that stamp matching the
bytes on disk, concludes it has nothing to re-read, and keeps SDL rows built from content that is gone.

The membership row also stops being cleared and rewritten wholesale per graph, which is a change this
item makes anyway rather than one the column adds: the row is the SDL family's root, so a refresh
deletes it per changed source and retains the rest. Its table comment records the wholesale clear as
today's behaviour and is rewritten with the rest of the mechanism's prose. Retention keeping the stamp
is what makes the column worth having.

Nothing else moves onto the row. Per-graph *age*, when a graph last named a source in its inputs, is
the eviction question `last_seen` answers store-global, and the reaping policy is another item's; a
second timestamp here would be built for a reader that does not exist.

**One thing goes rather than moving: the null-stamp protocol.** `ClasspathSources.upsert` blanks an
existing row's `stamp` and `read_at`, on the ground that "this run is about to (re)write the source's
partition, and the null is what keeps a killed run re-walked", and `store_source.stamp`'s own
description states the same rule. Both are hand-rolled crash consistency standing in for a transaction
the capture already has. `FactCapture.capture` is one transaction end to end with `commitStamps` inside
it, and the blanking branch fires only against a row that already exists, which means a warm store,
which means the store holds a graph row written in that same transaction, which is the path where the
stamps commit with the rows. A run that dies rolls back the stamp and the rows together. So the branch
protects nothing the rollback does not, and under sharing it is actively wrong: one graph's capture
blanking a currency record another graph reads. What survives is a write-ordering rule and not a null,
write the stamp after the rows it vouches for, which the first-graph path needs because its
materialization refresh runs outside the transaction and its stamps commit in a second one, and which
needs no existing row to blank.

**What the dropped rule was protecting is left unprotected, deliberately.** Nothing now stops two
consumer graphs binding one schema file, and `SourceGraph.Shared` stays a general arm rather than
narrowing to the bundled file. If that turns out to want a rule, it belongs on
`store_graph_source.source_name` where the fact lives, as a restriction over consumer sources, and it
is a policy question about what we support rather than anything the cascade needs. This item does not
ask it.

### What cascades, and what has to be re-aggregated

Every relation of these five families is one of three things, and the test is *whether the row's
existence is a function of one source or of several*, not whether it happens to carry a source
column. Getting that test wrong is what put two cross-file relations in the cascading set in an
earlier draft. The counts are computed from the DDL over all 124 relations of the five families and
they sum, which the previous version's did not.

[cols="3,1,5"]
|===
| Kind | Count | Treatment

| Owned by one source
| 77
| A cascading foreign key into its family root; a root reaches the registry by the set-null twin
  instead, which is what makes a removal flag rather than delete.
  14 `sql_`, 7 `jvm_`, 4 `java_`, 12 `graphql_`, 40 `graphitron_`.

| Descendant of an owned row
| 30
| Nothing to do; it already cascades transitively. `graphql_directive_location` under
  `graphql_directive`, the five `*_directive_arg` relations under their applications, and the 24
  `graphitron_` decodes hanging off owned rows or off catalog rows.

| A function of more than one source, of none, or of the recipe
| 17
| Re-aggregate after the walk and delete what no longer matches.
|===

The last row is the case that cannot cascade, and it has five kinds in it. Two arrive from phase
three and neither is a function of several documents, which is why the row's heading is wider than it
was. `graphql_root_operation`'s convention arm exists because a `schema` block is *absent*, so the row
is a function of no document at all. `graphql_schema_directive` holds the tag-link `@link`, whose
existence is a function of the *recipe*: `TagLinkSynthesiser.apply` fires when any `SchemaInput` in
the set carries a configured `tag`, and adds one extension for the registry however many do. Both are
recomputed rather than deleted, which is what makes re-aggregation the right treatment for a shape
neither the owned set nor a several-documents reading covers; the predicate each recomputes from is
named in phase three so an implementer does not go looking for a declaration to survive. Six are the SDL coordinate
anchors, `graphql_element`, the four `*_element` relations and `graphql_type`: a coordinate exists if
*any* declaration site names it, and a type declared in one file may be extended in three others, so
deleting one file's rows must not remove a coordinate another file still declares. Seven are the
`graphitron_` anchors that key at `store_graph` rather than at a source, `graphitron_element` and its
type, field and argument relations among them, which no source refresh reaches at all and which would
otherwise keep coordinates whose declarations are gone.

Two are verdicts, and they are the ones an earlier draft had cascading. `graphql_schema_error` records
what the registry and assembly stages refuse, judged over the document set as a whole rather than one
file at a time, and `graphql_duplicate_declaration` records a losing occurrence whose existence
depends on the winner's file as much as its own: refreshing the winner can make the duplicate go away,
and a row hanging off the loser's file would survive that. `graphql_syntax_error` is the counter-case
and stays in the cascading set, its own comment saying it is judged one file at a time.

Reconciling them is one step wherever a gatherer puts it: after the changed sources have been
re-read, whichever strategy read them, the coordinate set is recomputed from the surviving
`graphql_type_declaration` and its kin, and coordinates with no remaining declaration are deleted.

Those deletions cascade in turn, which is what closes the chain rather than needing a fourth
mechanism: the `graphitron_` decodes hang off the coordinate anchors by foreign keys the DDL already
declares `ON DELETE CASCADE`, its own comment naming that as the family's pattern, and `graphql_type`
hangs off `graphql_type_element`. A coordinate that stops being declared takes its decode with it
without anything being told to do so.

## What the schema already has

This is largely a design the store already models and does not yet use, which is the argument for
doing it rather than for patching the list.

- **21 of 22 source-keyed relations already reach `store_source` by declared foreign keys**, most of
  them transitively: `sql_node_metadata` through `sql_table`, `sql_routine_parameter` through
  `sql_routine`, `jvm_method_parameter` through `jvm_method` through `jvm_class`. The `java_` family
  is already a tree rooted at `java_file`, and `store_graph_source` already keys `(graph_name,
  source_name)` with foreign keys to both sides, so every root this design deletes already exists as
  a relation.
- **The foreign-key graph carries one cycle and it is harmless**, checked over all 177 base
  relations. `graphitron_argmapping_candidate` references itself on `(graph_name, coordinate,
  parent_path)`, already `ON DELETE CASCADE`, which terminates because it recurses on rows rather
  than on relations and which sits outside the cascading set anyway. Everywhere else the graph is
  acyclic, so a cascade needs no ordering decision from any caller. A figure of this kind rots: 176
  was correct when this plan was written and the DDL has gained a relation since, so the structural
  gate below is what should be trusted rather than the number.
- **The idiom is already project doctrine.** The DDL carries 16 `ON DELETE CASCADE` clauses, and the
  element family's own comment declares it the pattern every relation there follows. The source-keyed
  families are the ones hand-rolling it instead.
- **The cross-source case is already modelled.** `sql_referential_constraint` carries two foreign keys
  into `sql_constraint`, its own and the referenced one, which is the schema-crossing key that
  `CatalogFactCapture.clearSchemaSources` runs two separate loops to handle. Two cascading edges do it
  with no loop at all.

**The new SDL edges need indexes, and this is the item's main cost.** `source_name` sits outside the
primary key of 51 of the 52 source-owned `graphql_` and `graphitron_` relations, only
`graphql_type_declaration` carrying it in its key. So a
`(graph_name, source_name)` foreign key has no supporting index on the child side, and without one
H2 scans the child for every parent row deleted and on every referential check. Each of those
relations therefore needs an index on `(graph_name, source_name)`, which is storage and per-insert
maintenance on the families capture writes most heavily. That cost is real, it is the price of the
mechanism rather than an oversight, and Implementation measures it rather than assuming it: capture
wall-clock and store size on the sakila example, before and after, reported in the item.

Three sets of edges are missing and are this item's schema work. `jvm_declared_type_ref` carries no
foreign key at all: it is a defect rather than an exemption, its key leading `(source_name,
class_name)` which is exactly `jvm_class`'s whole primary key and exactly the edge its four siblings
already carry, so it gains
`FOREIGN KEY (source_name, class_name) REFERENCES jvm_class (source_name, class_name) ON DELETE
CASCADE`. Its `referenced_class` column deliberately gets none: that names a class which may sit in
another source or in no captured source at all, and the schema already refuses to model cross-source
resolution as a reference. The owner-precise edge is not expressible either, the owner being a
method, a record component or a method parameter by `owner_kind`, so the common ancestor is the right
parent. The 12 source-owned `graphql_` relations carry `source_name` and reference no source
registry at all, and the 40 source-owned `graphitron_` relations are in the same position; all 52
gain a cascading `(graph_name, source_name)` foreign key into `store_graph_source`, which is what
gives that family the single root row the other three already have.

## What the code loses

- `StoreRefresh.PARTITIONED`, `StoreRefresh.wholesale()`, and `clear`'s wholesale loop.
- `clear`'s seven hand-unrolled `jvm_` deletes, and the children-before-parents order they are
  hand-written in.
- The fourteen per-source deletes and the two-loop structure in
  `CatalogFactCapture.clearSchemaSources`, three in its first round and eleven in its second.
- `StoreRefresh.childrenFirst` for these families, the database owning the order instead.
- The blanking branch in `ClasspathSources.upsert`, which resets `stamp` and `read_at` on a row
  another graph may have established, for a crash the enclosing transaction already covers.

Two things stay, named so they are not read as collateral. `freshSources` still decides which sources
this round re-read; that set becomes the argument a gatherer refreshes against, rather than the scope
of twenty-one statements the store issues on its behalf. And the claim seeding in `prepare` is
untouched, being an insert-side optimisation that stops a walk rewriting a partition it is retaining,
which is orthogonal to how deletion happens.

What does not appear anywhere is a delete of a registry row followed by an insert of the same registry
row. A refresh never churns a source's identity to clear its facts; the row stays, and its `stamp` and
`read_at` update in place, which is where R922's currency comparison reads them.

## The descriptions this settles, retires and falsifies

A mechanism's prose goes stale the moment the mechanism does, and this item deletes enough of one to
leave a trail. The sweep is scoped here rather than left to the Done gate to discover, and it divides
into three kinds.

**One description stops being aspirational and becomes enforced**, and it is the best argument the
item has. `store_source`'s own table comment already states this design:

> Every base relation is partitionable by the source that produced it: a refresh deletes exactly the
> rows one source wrote and re-walks it, so a relation unreachable from a source row is one the store
> can only ever discard wholesale.

The four relations this item was filed for are exactly the ones unreachable from a source row, and
the wholesale discard is exactly what happened to them. So the sentence describes a property nothing
checks, and the structural gate this item adds is its enforcer.

It is also **too wide**, and the revision narrows it rather than only citing the gate. "A refresh
deletes exactly the rows one source wrote and re-walks it" states a refresh strategy as though the
schema mandated it, and the schema mandates no such thing: delete-and-rewalk is one option, it is the
cheap one, and a gatherer that reconciles instead is not violating the store's design. A description
on `store_source` should say what the keys guarantee and stop. The replacement says that ownership is
declared by the keys into this row, that no fact can outlive its source, that any delete a gatherer
issues is therefore complete without naming what else to remove, and that how a gatherer refreshes its
corpus is its own decision.

The same comment's opening clause, "store-global rather than graph-keyed: it can say what a file
hashed to, never which graph read it", is kept exactly as it stands and becomes load-bearing. An
earlier version of this plan would have falsified it with a `graph_name` column on the row; the
sharing case is what showed that column to be the wrong answer, and the clause it would have
contradicted turns out to state the design.

**Two claims become false and have to be rewritten rather than trimmed.** The first has two homes.
`store_source.stamp` says it is "Also NULL while the source's rows are being written, and set only
once they are all in, so a run that dies mid-load leaves a partition that is re-walked rather than one
that claims to be complete", and `ClasspathSources.upsert`'s javadoc states the same rule from the
code side. The capture is one transaction and its rollback gives that guarantee already, so both
describe a protocol this item deletes; what replaces them is the ordering rule, write the stamp after
the rows it vouches for, stated on the one path that needs it. `store_graph_source`'s comment carries
the second, that a warm capture "clears and rewrites exactly its own graph's rows", which stops being
true when the row becomes a root deleted per changed source and carries a stamp worth retaining.
And `meta_relation`'s
`java_file` row argues its own placement: "Its own relation rather than a `store_source` row because
`store_source` is a capture round's read set and a `.java` file is read by neither the SDL walk nor
the classpath scan, so this family carries its own freshness bookkeeping and leaves that taxonomy
closed." Every clause of that is reversed here. The registry becomes every input the store read
whoever read it, the `.java` file joins the taxonomy rather than being kept out of it, and the
family's separate freshness bookkeeping moves up to the registry row. `store_source.source_kind`'s
comment names the closed taxonomy member by member and gains `JAVA_SOURCE` with it.

**The rest describe a mechanism that is being deleted, so their homes go with them.**
`StoreRefresh`'s class javadoc is an account of retention by exemption, `PARTITIONED`'s javadoc
explains why the set is listed rather than derived and cites an anchor that does not exist,
`wholesale()`'s explains the exemption polarity, and `clear`'s explains hand-ordering children before
parents. `CatalogFactCapture.clearSchemaSources` carries the two-loop argument for the
schema-crossing foreign key that two cascading edges now handle, and the class's remaining
partition-and-retain sentences describe a decision the walk no longer makes. None of these is edited
in place: they document code this item removes, and what replaces them is the shorter description of
a delete whose completeness the keys guarantee.

R877 is rewriting relation descriptions concurrently and is not a dependency, its subject being
descriptions written as argument transcripts rather than descriptions falsified by a mechanism
change. The two touch the same file and should be sequenced by whoever picks this up second, which is
a merge concern and not a design one.

## What an existing store does at the upgrade

It is discarded once, deliberately. Adding foreign keys and cascade clauses changes the DDL, so
`store_stamp.ddl_hash` moves and every persisted store is rebuilt cold on first open under the new
version. That is the existing mechanism for a schema change working as intended rather than a
migration this item skips, and it is worth paying once here rather than twice if the schema work
were split across two items.

## Implementation

Descriptions travel with the code that carries them rather than being swept at the end: the javadoc
on a deleted method goes in the commit that deletes it, `meta_relation`'s `java_file` row is rewritten
in the phase that moves the family into the registry, and `store_source`'s table comment gains its
enforcer's name in the phase that adds the gate.

Four phases, and the seams are real rather than bookkeeping: each lands a family group or a
prerequisite that can be observed working while the rest is untouched. The first phase pays the
`ddl_hash` store discard, so the later three are free, and it also fixes the data loss the item was
filed for, so nothing behind it blocks that.

**Phase one, the source-keyed families.** Add `jvm_declared_type_ref`'s missing edge; add
`ON DELETE CASCADE` throughout the `sql_`, `jvm_` and `java_` webs so each family cascades from its
roots; replace the twenty-one statements between `clear`'s `jvm_` block and `clearSchemaSources` with
one delete per root per changed source, which is two for `sql_` and one each for the other two and is
the strategy those three gatherers take today; remove `PARTITIONED` and `wholesale()`. The four
relations this item was filed for are carried by the cascade with nothing naming them.

The three roots of these families take the set-null twin in this phase rather than in phase four, so
the removal rule arrives with the families it governs: `sql_schema`, `sql_enum_binding` and
`jvm_class` each swap the foreign key on their primary-key source column for a `source_ref` twin under
its `CHECK`, and `java_file` takes the same treatment in phase two when it joins the registry. The
metadata-derived sweep in `StoreRefresh.prepare` lands here too, since these three are its first
members and the membership row joins them in phase four without the sweep changing.

**Phase two, `java_` joins the registry.** Add the `JAVA_SOURCE` kind, give `java_file` the
`source_ref` twin and its `CHECK` against `store_source` on phase one's pattern, and move its `stamp` and `read_at` up to the registry row so currency
is stated once. `JavaSourceFacts` is the only reader or writer of either column, so the move is
contained to one class; R922's comparison takes the instant as an argument and does not care which
relation it came from.

**Phase three, the four populations no document produced.** The prerequisite for phase four. Every
definition the parser reads is already attributed: `SchemaLoader.parseSource` builds a
`MultiSourceReader` with `.reader(reader, sourceName)`, so a `SourceLocation` carries a source name
for every document including the bundled directives, which parse under
`SchemaLoader.DIRECTIVES_SOURCE_NAME`. So the work is not to recover a document. It is to decide what
four populations are, none of which came from one, and which between them are why `source_name` is
nullable in 37 of the 54 relations.

They divide into two shapes, and both shapes already exist in the plan.

*Injected by an artifact, so attributed to that artifact.* The federation definitions
`FederationLinkApplier.apply` injects before capture sees the registry come from
`federation-graphql-java-support`, a jar on the compile classpath that already carries a
`store_source` row of kind `JAR` with a real stamp. They are attributed there, which makes them
`NOT NULL`, reachable by phase four's edge, and genuinely refreshed when the library version moves.
The bundled `directives.graphqls` is the same shape one step in: it ships inside graphitron's own
artifact, it already has a `store_source` row from `captureSources`, and what it lacks is a stamp, so
it gains one tied to the generator version that `store_stamp` already records. Neither is a sentinel,
because both name a thing that changes and that something re-reads when it does.

*Derived from the document set, so re-aggregated.* `SdlFactCapture.captureConventionRoots` writes a
`graphql_root_operation` row when a schema declares a `Query`, `Mutation` or `Subscription` type and
no `schema` block, and its own javadoc says why the row has no position: "no SDL line spells the
binding". A row that exists because a declaration is *absent* is a function of the document set rather
than of any document, which is the re-aggregated set's own definition. `graphql_root_operation` moves
there whole rather than being split by arm, because the relation also holds explicit bindings and a
taxonomy that classifies half a relation is not one; recomputing it after the walk covers both arms
from the surviving declarations plus the convention rule.

`TagLinkSynthesiser` is the fourth and it is the one the plan cannot leave alone, because it is the
disposition the alternatives section refuses, already shipped: it stamps what it injects with
`SourceLocation(1, 1, "<graphitron-synthesised:tag-link>")`, so those rows are `NOT NULL`, would
satisfy phase four's edge and the strengthened gate, and belong to a source no refresh ever names.

It cannot be attributed to a document, because no document triggers it. `TagLinkSynthesiser.apply`
fires on `bySource.values().stream().anyMatch(i -> i.tag().isPresent())`, and `SchemaInput.tag` is a
configuration field carried from `SchemaRecipe.Binding.tag()` and persisted by `StoredRecipe` as a
`tag` column; the `@link` is what the synthesiser writes, not what it reads. `synthesise(registry)`
then adds one `SchemaExtensionDefinition` for the whole registry however many inputs are tagged, and
`captureSchema` turns that into one `graphql_schema_directive` row. Picking one tagged input to own it
would be unsound in this item's own terms: with two tagged inputs, refreshing the chosen one deletes a
row the other still requires, which is a partition losing rows it still owns.

So `graphql_schema_directive` re-aggregates, recomputed after the walk from the surviving inputs and
the same predicate, and the synthetic source name is retired. **This and the convention roots together
move the counts to 77 / 30 / 17.** It also settles the schema-level ownership question an earlier
version of this phase raised, `graphql_schema_directive` having been the other relation named there.

`SdlFactCapture.stampTarget`'s javadoc names the bundled directives and the synthesised name as "the
whole miss set", which is true of sources with no file and is why those two are the ones with rows and
no stamps. This phase's population list is wider because it asks a different question, which rows have
no document rather than which sources have no file.

What Implementation still owes, rather than the plan: whether the `SchemaSource.Named` label arm
belongs in the same account. It carries its label as `source_name` so it produces no NULL and is not
part of this defect, but it is a source with no file and no stamp, which is the property that makes
the bundled directives need one. It is a live sealed arm at nine main-source sites and persisted as
`KIND_NAMED`, so retiring it is a scope statement this item does not make.

**Phase four, the SDL families.** Add `store_graph_source.stamp` and delete the blanking branch in
`ClasspathSources.upsert`; replace `store_graph_source`'s existing foreign key into `store_source`
with the `source_ref` twin, its `CHECK` and its `ON DELETE SET NULL` edge, which the sweep phase one
built picks up from the metadata without being told; add the cascading
`(graph_name, source_name)` foreign keys from the
12 `graphql_` and 40 `graphitron_` source-owned relations into `store_graph_source`, with the index
each needs; write the re-aggregation over all seventeen relations that are a function of more than one
source, of none, or of the recipe, the coordinate anchors of both families and the two cross-file
verdicts among them; reduce the graph-scoped clear to what does not now cascade, the membership row
being a root this phase deletes per changed source rather than empties per graph. No ownership column
and no refusal: the phase's own pre-flight step asked whether a fixture captures two graphs over one
schema file, and the answer, established by two shipped tests, is that every store does.

## Tests

- **The regression this item was filed for**, in `WarmStartRefreshTest`: capture graph B over one jOOQ
  package fixture and graph A over a different one so the two share no source, capture A warm, and
  assert B's rows are unchanged. Written as a sweep over the source-partitioned relations derived from
  schema metadata rather than over named relations, so a relation added later is covered unedited, with
  a control asserting B holds rows in the four before A's capture runs. The default fixture catalog
  publishes node metadata and declares `films_for_actor` with reflected parameters, both already
  asserted non-empty by `FactCaptureAgreementTest`, so the control is real rather than nominal.
- **A shared source file keeps two graphs apart**, which is the test that fails under the wrong
  design rather than under a typo: two graphs over one schema file in one store, the file edited and
  graph A re-read, asserting A's rows follow the edit, B's rows are untouched, and B's
  `store_graph_source` stamp still names the content B read, so B's next round re-walks instead of
  trusting the registry stamp A moved. `FactCaptureAgreementTest.graphSourceMembershipEqualsTheRunsReadSet`
  already captures two graphs into one store and is the shape to extend; `EntryFamilyFixture` holds
  nine relations to carrying rows from two sources at once, which is the overlap a per-source delete
  can be observed on at all.
- **The aggregate case**: a type declared in one file and extended in another, the extending file
  re-read and then removed, asserting the coordinate survives the first and goes on the second, and
  that the `graphitron_` decode hanging off it goes with it.
- **A structural gate over the reference web**, which is what replaces the list with something
  checkable: every base relation of the five families either reaches its root by declared foreign keys
  whose every edge cascades, or is named in a small declared aggregate set. A relation added with no
  such path fails the build. The gate checks two things and not one, because the round-1 finding
  turned on the difference: that the edge is declared, and that its child column is `NOT NULL` so the
  edge reaches every row of the relation rather than only the rows that happen to carry a value. A
  nullable child column fails the gate rather than passing as covered. `store_graph_source.source_ref`
  is one of five nullable source columns in the store that are not defects, the set-null twins, and
  every one of them sits on a root rather than inside a family, so they are outside this gate's
  population by construction rather than by exemption. The gate does check that the set is coherent,
  each twin carrying its `CHECK`, its set-null edge and no competing cascade on the same parent, and
  that the sweep reads its targets from that set rather than from a list; what pins the behaviour is
  the removal tests above. It carries a floor on how many relations it classified, so a metadata
  change cannot let it pass vacuously, that failure mode having been recorded against
  `FactSchemaGateTest.currencyAccompaniesEveryStamp` at R922's Done gate.
- **Removing a source leaves every graph able to clean up, and does not clean up for them**: two
  graphs over one shared file, the registry row deleted, asserting the delete itself removes neither
  graph's rows, that both memberships come back with a null `source_ref`, that the running graph's
  next refresh reaps its own subtree and re-aggregates its anchors, and that the other graph's rows
  stand untouched until it captures and reaps them itself. Its companion is the enforcement's other
  half, a membership row written with a null `source_ref` while its source still exists, which the
  same pass reconciles: the predicate is the foreign key the primary-key column cannot carry, so the
  test that pins it is the one that would otherwise be a `FOREIGN KEY` clause. The same shape once for
  a shared corpus rather than a graph's, a jOOQ package removed and `sql_schema` flagged, which is
  where the two scopes differ and so is worth a case of its own rather than a parameter.
- **The two operations are distinguishable**, which is the pin against the mechanic the design
  rejects: a refresh of a changed source leaves its `store_source` row in place with a new `stamp` and
  `read_at` and the same identity, while removing the source deletes the registry row and everything
  under it. A test asserting the first would fail against any implementation that cleared facts by
  churning the registry row.
- **A cross-file verdict is not deleted by refreshing one of its files**, which is the case the
  corrected taxonomy exists for: two files declaring the same type, the duplicate recorded, then the
  *winner's* file re-read, asserting the duplicate row is reconciled rather than left hanging off the
  loser's file. The same shape for `graphql_schema_error`, whose stages judge the document set whole.
- **Cascade cost is measured, not assumed**, and for both operations, since the plan now distinguishes
  them: refreshing a changed jar, which deletes its `jvm_class` root, and removing a jar outright,
  which deletes its registry row. Each reported as a row count and a duration beside the hand-written
  path it replaces, so a regression in refresh cost is visible here rather than in a dev loop. Insert
  cost is measured too and is the one more likely to regress: 52 new foreign keys and 52 new indexes sit on
  the families capture writes most heavily, so capture wall-clock and store size on the sakila example
  are reported before and after.

## Retired vocabulary

Declared for the retirement sweep at the Done gate. Each term names something this item removes
rather than renames, so a surviving use is a description of a mechanism that is gone.

- `PARTITIONED`, and the phrase *source-partitioned families* where it names membership of that set
  rather than the property of being partitioned by source, which survives and is the whole point.
- `wholesale()`, the *wholesale arm*, the *wholesale clear*, and *discard wholesale* as a description
  of what happens to an unlisted relation.
- *Exemption polarity*, and the argument that a relation nobody thought about is emptied rather than
  silently retained.
- *An empty refresh empties every relation*, the anchor `PARTITIONED`'s javadoc cites, which no test
  ever implemented and which the structural gate replaces.
- `childrenFirst` and the children-before-parents ordering as a caller's obligation, the ordering
  becoming the database's.
- *A capture round's read set* as the definition of `store_source`, which becomes every input the
  store read whoever read it.
- *The null stamp as a crash marker*, and the claim that blanking a `store_source` row's `stamp` is
  what keeps a killed run re-walked, the enclosing transaction's rollback being what does that.
- `TagLinkSynthesiser.SYNTHESISED_SOURCE_NAME` and its value `<graphitron-synthesised:tag-link>`,
  which phase three retires along with the stamp-lookup branch that tolerates it.

## Other solutions we've considered

**Adding the four missing relations to `PARTITIONED`.** The one-line fix, and what this item was
specified as until the mechanism was examined. Rejected because the list is the defect: nothing derives
it, nothing checks it, and its 21 members are three different situations under one name, being deleted
here, deleted elsewhere, and not capture's business at all. The four went missing because a
hand-maintained exemption list has no way to notice an omission, and patching it leaves the next
omission free to happen.

**Letting the removal cascade through the roots.** The obvious reading of "the keys declare
ownership", and what this plan said until the mechanics of a shared file were worked through: give
every root a cascading foreign key into `store_source` and let deleting a registry row take every
family's rows with it. Rejected because it makes removal the one operation an owner does not own. Every
other delete in this design is issued by the gatherer whose rows it clears, and this one would be
issued by whichever process happened to notice the file was gone, against partitions whose owners are
not running. It also destroys what the deletion knows, leaving the owner to re-walk where it could have
reconciled, and it publishes a half-graph to any reader until that graph next boots.

**Twinning only the membership row.** The half-measure this plan held for one revision, on a suspicion
that the twin would be expensive on `jvm_class`, which is the one root with many rows per source.
Measured instead of argued, across five real consumer stores in the local cache: `jvm_class` holds
3.4k to 11k rows over 26 to 173 sources, and the duplicated source name totals 262 KB to 964 KB against
stores of 408 MB to 824 MB, which is under an eighth of a percent. The asymmetry cost more to explain
than the column costs to carry, so every root takes the twin and the rule has no exception. Those
stores each hold one graph, so they say nothing about how common sharing is; the byte figure is what
they were read for.

**One graph per schema file.** Carried by this plan for four rounds: a `graph_name` column on
`store_source` with a `CHECK` tying it to the `SCHEMA_FILE` kind, and a typed rejection when a second
graph claimed a file another owned, on the grounds that a shared schema file was not a thing we wanted
to support and that two families are cheaper to reason about when a file has one owner. Rejected as
unsatisfiable rather than as undesirable: the bundled `directives.graphqls` is one `SCHEMA_FILE` row
with a membership per graph, so no assignment of the column satisfies the `CHECK` and the refusal fires
on the second module of every workspace, which is the case the item was filed to fix. Two other ways
out were available, a `source_kind` of its own for the bundled file or a NULL `graph_name` for it, and
both keep a column that answers "which graph read this source" a second time and kind-filtered, which
is the shape `store_graph_source`'s own comment argues against and which round 1 flagged before the
counterexample existed. Dropping it costs nothing the cascade needed: the SDL family roots at the
`(graph, source)` row either way, and the column would have falsified the clause of `store_source`'s
own comment that turns out to state the design.

**Deriving `PARTITIONED` from the key instead of listing it.** Makes the omission impossible without
removing the mechanism, membership being computable as leading the key with `source_name` or `file`.
Rejected because membership asserts that a per-source delete exists somewhere and a key column is no
evidence of that, so a derived set would silently enrol a relation whose delete nobody wrote and keep
rows whose partition went away. Cascade answers both halves at once: the edge granting membership is
the edge performing the delete, so the two cannot disagree.

**Keeping the wholesale arm behind an explicitly empty roster and a completeness gate.** The previous
version of this plan. Rejected as careful work on a mechanism this design deletes, a gate whose only
purpose is catching omissions from a list that would no longer exist.

**Clearing a source's facts by deleting its registry row and inserting it again.** An earlier
revision's mechanic, and the reason the plan now distinguishes two operations. It churns the identity
of a row that did not change to clear rows that did, it writes the registry twice per refresh, and it
makes every future foreign key into `store_source` a hazard, since anything pointing at a source would
be destroyed by a routine refresh of it. Deleting the family root instead leaves the registry row
untouched and reaches exactly the facts the file produced.

**Giving the `java_` family its own registry, as it has today.** Kept in an earlier revision on the
grounds that its cadence differs. Rejected: cadence is what `meta_gatherer` and `meta_corpus` express,
the SDL and classpath gatherers already share `store_source` while running on cadences of their own,
and a second registry duplicates the freshness model that R922 had just finished stating once. A
`.java` file is an input the store read, which is what the registry is for.

**Making any gatherer reconcile instead of delete-and-rewalk.** Out of scope, deliberately, and worth
stating because the plan argues reconciliation is the better strategy. This item makes the choice
available and changes nobody's answer: each of the three source-keyed gatherers keeps the
delete-and-rewalk it performs today, expressed as one delete rather than twenty-one. Moving a gatherer
to reconciliation is a decision about that corpus, wants the measurement of its own walk beside it,
and belongs with R857, which is the item that needs the knowledge reconciliation produces. Folding it
in here would mix a data-loss fix with a per-corpus performance judgment.

**Two of the three dispositions offered for the nullable `source_name`.** Filling the 37 columns with
a sentinel, matching `store_graph_source`'s empty-string convention, makes the edge match but yields a
synthetic source no refresh ever names, so those rows would never be deleted at all: the column would
be `NOT NULL` and the mechanism still broken, which is worse than the honest NULL because the gate
would then pass. Moving the schema-level relations into the re-aggregated set fixes
`graphql_schema_directive` and `graphql_root_operation` and leaves 35 relations with the nullable
column, so it treats the two instances the reviewer could name rather than the defect. Both bend the
plan around the nullability; phase three removes it.

**Extending the same treatment to the graph dimension.** Out of scope rather than rejected.
`graphql_element` already keys into `store_graph`, so cascading from the graph row would retire
`clear`'s graph-scoped loop too. That loop is derived from the schema and is not broken, so it is not
this item's to fix, and folding it in would put a working mechanism inside the blast radius of a
data-loss fix.

## Provenance

Filed on 2026-08-28 out of a review of R857, as four relations missing from a list. Respecified on
2026-09-06 after a self-review traced the mechanism rather than the omission and found two things: the
wholesale arm's entire population is those four relations, so the arm has never had a correct member,
and the machinery that makes the list unnecessary already exists in the same method, used for the
graph dimension and not for the source one. The cascade design, and the split between what cascades
and what is re-aggregated, are the user's.

## Reviewer findings

### Round 1 (2026-09-07, Spec -> Ready, reviewer session 01BnH5mPddDZACkr4BfodYag)

Verdict: withhold. One blocking finding on question two, scoped to phase three; question one passes,
and passes well. Phases one and two extend a shape the tree already carries and I would hand them to
an implementer as written. Phase three roots 54 SDL relations at `store_graph_source` by a foreign
key whose child column is nullable in 37 of them, which the plan does not mention and the tree
argues against in two places, and the structural gate the plan offers as the list's replacement
cannot see the gap.

The goal reads on its own. Building module A blanks module B's node-identity and routine facts in a
shared store because four relations sit outside `PARTITIONED`, and afterwards the omission is
unrepresentable because there is no list. Nearly every claim the plan makes about the tree checks
out, and two of the sharper ones check out exactly: `StoreRefresh.wholesale()`'s population computes
to precisely `sql_node_metadata`, `sql_node_key_column`, `sql_routine` and `sql_routine_parameter`
and nothing else, so the arm has indeed never had a correct member; and the 79 / 30 / 15 taxonomy
over 124 relations reproduces from the DDL down to each per-family figure, the seven `graphitron_`
anchors keyed at `store_graph` and the five `*_directive_arg` descendants included. `sql_`'s second
root is real (`sql_enum_binding` hangs off the registry with a nullable `table_schema`, no edge can
fold it into a schema), `jvm_declared_type_ref` is the one relation of its family with no foreign key
at all, and `meta_relation`'s `java_file` row and `store_source`'s table comment are quoted verbatim.

**Finding 1 (question two: architecture fit). The `(graph_name, source_name)` edge into
`store_graph_source` does not reach the rows it has to reach: `source_name` is nullable in 37 of the
54 relations, a NULL matches no foreign key, and the structural gate would certify those relations
as covered.**

Of the 54 source-owned SDL relations, 17 declare `source_name NOT NULL` and 37 declare it nullable
(8 of the 14 `graphql_`, 29 of the 40 `graphitron_`). A row whose `source_name` is NULL has no parent
in `store_graph_source` and is not deleted when the root row goes, so for those relations "any delete
anybody does issue is complete without being told what else to remove", which is the whole
correctness argument of the design, does not hold. The rows survive every refresh of the file that
produced them.

The nullability is deliberate and the tree states it twice, in both cases as an argument against
exactly this edge. `store_graph`'s table comment: "why the SDL roots carry an FK here while the
SDL-to-`store_source` FK was declined: the graph is ambient before the walk begins and NOT NULL on
every row, while the source rows are a summary collected last and nullable at schema-level sites, so
the FK doctrine admits one and not the other." And
`FactCaptureAgreementTest.schemaFilesAreRecordedAsSources`'s javadoc: "It declares no foreign key
into `store_source`: a schema-level row can carry a null source name, and the FK doctrine puts one
only where the walk writes the child while standing on the parent." The plan engages with neither.

The population is concrete, not hypothetical. `graphql_schema_directive` and `graphql_root_operation`
are the schema-level relations those two statements are about, both are in the plan's 54, and
`SdlFactCapture.setPosition` returns without setting `source_name` whenever the graphql-java
`SourceLocation` is absent or carries no source name, which is the programmatic-caller and bundled-
resource case `store_source.stamp`'s own comment already records ("the bundled `directives.graphqls`
is a resource name, a programmatic caller may hand a bare name"). The two ends of the proposed edge
also disagree on how "no source" is spelled: `GraphSourceMembership.note` normalises a null source to
the *empty string*, so even a `''` parent row would not match a NULL child.

What makes this blocking rather than an implementation detail is the interaction with the plan's own
replacement for the list. The gate under `## Tests` passes a relation that "reaches its root by
declared foreign keys whose every edge cascades". All 54 would declare such an edge, so all 54 pass,
while 37 of them retain rows the cascade never reaches. That is the exemption-list failure mode
returning in a form the new gate is structurally unable to catch, which is the one outcome this item
exists to make impossible. It also makes the taxonomy's first row wrong for those relations: the
plan's own test is "whether the row's existence is a function of one source", and a row with no
source recorded is not.

Three dispositions are open and they are not variations on each other. Making the 37 columns NOT NULL
against `''`, matching `store_graph_source`'s existing stand-in convention, touches every writer that
leaves them unset and yields a synthetic source no refresh ever names, so those rows then never get
deleted at all. Moving the schema-level relations into the re-aggregated set changes the 79 / 30 / 15
counts and the gate's declared aggregate set, and makes the re-aggregation pass wider than fifteen
relations. Attributing a source at capture time from the enclosing document rather than from the
`SourceLocation` is a capture change carrying its own question about which file owns a schema
extension's directive. Which one is chosen decides whether the gate is sound, so it belongs in the
plan rather than in the implementer's hands mid-phase.

> **Author, 2026-09-07.** Accepted; the finding is reproduced exactly, 37 of 54, and both quoted tree
> statements check out verbatim. The third disposition is chosen and the plan absorbs it as a new
> **phase three**, which pushes the SDL families to phase four; your "phase three" above is that
> phase under its old number. The other two dispositions are refused in the alternatives section, on
> the ground that both leave the nullability in place: a sentinel yields a synthetic source no
> refresh ever names, so the column would be `NOT NULL` and the mechanism still broken with the gate
> now passing, and reclassifying the schema-level relations treats the two instances while 35 keep
> the column. The premise the new phase works from is that there is no legitimate NULL: everything in
> the merged registry came from a document, programmatically built registries are retired rather than
> attributed, and the bundled `directives.graphqls` is a source that gets a row. Both questions you
> flagged as the disposition's own are settled in that phase rather than left to the implementer, the
> schema-level ownership question with its consequence named (those two relations move to the
> re-aggregated set and the counts move with them) and the two spellings of absence. The gate now
> checks that a child column is `NOT NULL` as well as that the edge is declared, which is the check
> that would have caught this, and it is the round's most valuable outcome.
>
> This was briefly split into a separate item and folded back within the hour. The split let phases
> one and two clear the gate with the hard half removed, which is the same bend in a different shape,
> and it would have left the store carrying both refresh mechanisms indefinitely behind an
> unscheduled item. R930's id stays a gap.

**Finding 2 (question one: claims about code). Two structural figures in "What the schema already
has" are wrong as stated, and three statement counts undercount.**

The conclusions survive in every case, which is why this is one finding rather than four, but a
reviewer checking them finds them false:

- "The foreign-key graph is acyclic, checked over all 176 base relations." The DDL declares 177 base
  relations, and the graph carries one cycle: `graphitron_argmapping_candidate` has a self-
  referencing `(graph_name, coordinate, parent_path)` foreign key, already `ON DELETE CASCADE`. The
  conclusion holds anyway (a self-loop terminates on rows, and the relation carries no `source_name`
  so it is outside the cascading set), but the claim as written does not, and the sentence is
  load-bearing for "a cascade terminates and needs no ordering decision from any caller".
- "the eleven per-source statements in `CatalogFactCapture.clearSchemaSources`", repeated as "The
  eleven per-source deletes and the two-loop structure" under "What the code loses".
  `clearSchemaSources` issues 14 deletes, 3 in the first round and 11 in the second. Eleven is the
  second round alone, which is odd beside the same sentence naming the two-loop structure as the
  thing being replaced.
- "eighteen hand-written statements across three files", repeated in phase one as "the eighteen
  statements between `clear`'s `jvm_` block and `clearSchemaSources`". Those two sites hold 7 + 14 =
  21. Counting `JavaSourceFacts.clear`'s three declaration deletes as the third file gives 24. Either
  figure is defensible; eighteen is neither.

> **Author, 2026-09-07.** All four corrected, each verified first. The count is 177 and the graph is
> not acyclic: `graphitron_argmapping_candidate` references itself on `(graph_name, coordinate,
> parent_path)`, already cascading, terminating on rows and outside the cascading set, so the
> conclusion survives and the sentence now argues that rather than asserting acyclicity. 176 was
> correct when written and the DDL has gained a relation since, so the corrected sentence also says a
> figure of this kind rots and points at the gate instead. `clearSchemaSources` is fourteen, three in
> its first round and eleven in its second, and both sites now say so. Twenty-one replaces eighteen at
> four sites, spelled as seven plus fourteen so the arithmetic is visible rather than asserted.

### Non-blocking note

`store_source.graph_name` falsifies the first clause of `store_source`'s own table comment, "store-
global rather than graph-keyed: it can say what a file hashed to, never which graph read it". The
description sweep is otherwise thorough and rewrites the comment's second sentence, so the omission
reads as an oversight rather than a decision. Relatedly, the column makes `store_source` a second and
kind-filtered place that answers which graph read a source, which is the shape
`store_graph_source`'s comment argues against ("a kind-filtered membership would make completeness a
function of which consumers had shipped"). The plan says the rule is severable policy and invites the
reviewer to judge it as such, so this is a note and not a finding; if the rule stays, the falsified
clause belongs in the sweep.

> **Author, 2026-09-07.** The rule stays, so the clause is now in the sweep: the description section
> names `store_source`'s "store-global rather than graph-keyed: it can say what a file hashed to,
> never which graph read it" as a third thing phase four falsifies, alongside `meta_relation`'s
> `java_file` row. The second half of the note, that the column makes `store_source` a second
> kind-filtered answer to which graph read a source and that `store_graph_source`'s comment argues
> against exactly that, is not resolved by a sweep and is recorded here as the strongest argument
> against the rule. The plan still offers the rule as severable policy, and this note is the reason a
> reviewer might sever it.

### Round 2 (2026-09-07, Spec -> Ready, reviewer session 01BnH5mPddDZACkr4BfodYag)

Verdict: withhold. One blocking finding on question two, on the new phase three alone. Everything
round 1 asked for is done and done well: finding 2's four figures are all corrected and each
correction checks out (177 relations, the `graphitron_argmapping_candidate` self-cycle described
accurately as harmless and outside the cascading set, fourteen deletes as three plus eleven, and
twenty-one spelled as seven plus fourteen. One "eighteen" survived the sweep, in the
alternatives section's reconciliation entry, and is corrected in this commit as a stale count). The non-blocking note is absorbed in both halves. The disposition chosen for finding 1 is the
right one of the three, the refusals of the other two are correct on their merits, and making the
structural gate check that a child column is `NOT NULL` as well as declared is the strongest thing to
come out of round 1. Phases one, two and four are implementable as written.

What is not established is phase three's premise. It reads "there is no legitimate NULL: everything
in the merged registry came from a document, so everything in it can name one", and names two
NULL-producing populations to remove. Neither is a NULL producer, and the two production populations
that are do not come from a document at all, so the phase as written would send an implementer at
code that is not the problem and leave the problem standing under phase four's new gate.

**Finding 3 (question two: architecture fit). Phase three's account of what produces a NULL is wrong
in both directions, and two of the real populations are unreachable by the mechanism it proposes.**

*The parser path produces no NULL, so neither named population is one.* `SchemaLoader.parseSource`
hands every document to a `MultiSourceReader` built with `.reader(reader, sourceName)`, so every
definition parsed through `parsePerSource` carries a `SourceLocation` with a source name, the bundled
directives included: `parseDirectives` parses under `SchemaLoader.DIRECTIVES_SOURCE_NAME`. The
bundled file therefore needs a `store_source` row and a stamp decision, which the phase is right
about, but it produces no NULL and removing it removes none. And no capture path in the tree builds a
registry programmatically. `SdlFactCapture.capture` is reached only from `FactCapture.capture`, whose
registry comes from `parsePerSource` in production (`GraphQLRewriteGenerator.loadAttributedRegistry`)
and from `CapturedStore.registryOf` in tests, which writes the SDL to a real file and goes through
`SchemaLoader.load`. The nameless `new SchemaParser().parse(sdl)` registries in the tree feed
`LintEngine` and `DeclaredDirectives`, never capture. If the intended target is the `SchemaSource.Named`
label arm, that is a different claim and a much larger one: `Named` is a live sealed arm handled at
nine main-source sites across the plugin, `SchemaRecipe`, `StoredRecipe` (persisted as `KIND_NAMED`)
and the generator, and it carries its label as `source_name`, so it too produces no NULL. Retiring it
is a scope statement the phase does not make.

*Two production populations do produce a NULL, and neither came from a document.* First,
`SdlFactCapture.captureConventionRoots` writes `graphql_root_operation` rows with no `setPosition`
call at all, for any schema that declares a `Query`, `Mutation` or `Subscription` type and no `schema`
block, which is the ordinary shape. Such a row exists because of the *absence* of a declaration, so
there is no document to attribute it to and the premise is false for it. The plan does name
`graphql_root_operation`, but its primary disposition is "attribution from the walk's position", and
here there is no position; its fallback, the re-aggregated set, is defined as "a function of more than
one source", and a convention root is a function of none, so the taxonomy has no row for it as
written.

Second, and this one the phase cannot reach at all: `FederationLinkApplier.apply` runs on the registry
before capture sees it, deliberately, and injects definitions from
`federation-graphql-java-support`. That code's own comment states the consequence, "No source file
means the existing entry was not parsed from any `.graphqls`; it was added by
federation-graphql-java-support itself", and distinguishes a hand-written declaration that "carries a
`SourceLocation` with a file path" from a "source-name-less existing definition". Those definitions
are transcribed into `graphql_directive`, `graphql_directive_argument` and the directive-application
relations, all of which carry a nullable `source_name`. Driving capture from the per-source parse
does not help, because the injection happens after the parse inside a library graphitron does not
control, and federation is a shipped feature rather than a shape that can be retired.

*A synthetic source that no refresh names already exists.* `TagLinkSynthesiser` stamps what it injects
with `SourceLocation(1, 1, SYNTHESISED_SOURCE_NAME)`, where that constant is
`"<graphitron-synthesised:tag-link>"`. `SdlFactCapture.stampTarget`'s javadoc names it and the bundled
directives as "the whole miss set" for sources with no file. Rows attributed to it are `NOT NULL` and
would satisfy phase four's edge and the strengthened gate, while belonging to a source no refresh ever
names, so they would never be deleted. That is exactly the property the plan rejects the sentinel
disposition for, already in the tree at production, and the plan does not mention it. Whatever answer
phase three reaches for the sentinel has to cover this name too, or phase four ships the failure mode
the alternatives section just refused.

What would satisfy the finding is a phase three written from the real population list rather than the
inferred one: the parser path attributes everything already, so the work is not "recover the document"
but "decide what these four non-document populations are". For each of convention roots, federation
library injections, `<graphitron-synthesised:tag-link>` and the bundled directives, say whether it gets
a `store_source` row of its own kind, or whether the relations carrying it move to the re-aggregated
set, and where the counts land. The two questions the phase already settles are the right shape for
this; there are four populations rather than two, and the schema-level ownership question is the least
of them.

> **Author, 2026-09-07.** Accepted in full; every claim reproduced before rewriting. The parser does
> attribute everything, `parseSource` building a `MultiSourceReader` with `.reader(reader, sourceName)`
> and the bundled directives parsing under `DIRECTIVES_SOURCE_NAME`, so the premise was wrong and the
> phase was aimed at code that is not the problem. `captureConventionRoots`'s own javadoc says the
> positions are null "because no SDL line spells the binding", `FederationLinkApplier` states the
> source-name-less case in its own comment, and `TagLinkSynthesiser` does stamp
> `<graphitron-synthesised:tag-link>`, which `stampTarget` names alongside the bundled directives as
> the whole miss set.
>
> Phase three is rewritten from the four populations, and each gets a disposition carrying a refresh
> path, because a disposition without one is the sentinel this plan already refuses. Two are injected
> by an artifact and are attributed to it: the federation definitions to the
> `federation-graphql-java-support` jar, which is already a `store_source` row of kind `JAR` with a
> real stamp, and the bundled directives to graphitron's own artifact, which already has a row from
> `captureSources` and gains the stamp it lacks, tied to the generator version `store_stamp` records.
> Two are derivations: `graphql_root_operation` moves to the re-aggregated set whole rather than being
> split by arm, since a row existing because a `schema` block is absent is a function of no document,
> which takes the counts to **78 / 30 / 16**; and the tag-link synthesis is attributed to the document
> carrying the `@link` that triggered it, which retires the synthetic name rather than letting phase
> four ship the failure mode the alternatives section refuses.
>
> One thing is left to Implementation rather than settled, and named as such: whether the
> `SchemaSource.Named` label arm belongs in the same account. It produces no NULL so it is not this
> defect, but it is a source with no file and no stamp, which is the property that made the bundled
> directives need one. Retiring a live sealed arm at nine main-source sites, persisted as
> `KIND_NAMED`, is a scope statement this item does not make.

### Round 3 (2026-09-07, Spec -> Ready, reviewer session 01BnH5mPddDZACkr4BfodYag)

Verdict: withhold, on one of phase three's four dispositions. The other three are right and I checked
each against the tree: `captureConventionRoots`'s javadoc says verbatim that "the row's positions are
null because no SDL line spells the binding", and moving `graphql_root_operation` whole rather than by
arm is correct because `captureSchema` writes the explicit bindings and the convention arm into the
same relation. The federation disposition is sound and better than it needs to be:
`graphitron-sakila-example` declares `federation-graphql-java-support` at compile scope with the
comment "the federation-enabled generated `GraphitronSchema.build()` references `Federation` and
`SchemaTransformer` at compile time", so a consumer whose registry `FederationLinkApplier` injects into
necessarily has that jar on the compile classpath the census scans, where it gets a `JAR` row with a
real stamp and a `store_graph_source` membership row, so phase four's edge resolves. The bundled
directives already get a row from `captureSources` and `store_stamp` already records
`generator_version`. The new counts are arithmetically sound: 78 / 30 / 16 sums to 124 and the
`graphql_` family still tallies to 28 with `graphql_root_operation` moved. `SchemaSource.Named` is
correctly parked, and correctly described.

**Finding 4 (question two: architecture fit). The tag-link disposition attributes the synthesised row
to "the document carrying the `@link` that triggered it", and no such document exists: the trigger is
a configured field on any schema input, and one row is synthesised for the whole input set.**

`TagLinkSynthesiser.apply` opens with
`boolean anyTagged = bySource.values().stream().anyMatch(i -> i.tag().isPresent())` and returns early
when that is false. `SchemaInput.tag` is a configuration field on the record, populated from
`SchemaRecipe.Binding.tag()` and persisted by `StoredRecipe` as a `tag` column, not an `@link` tag in
a document. Nothing in a document triggers the synthesis; the `@link` is what the synthesiser *writes*.
What it reads from the registry is an already-present federation `@link`, and finding one that imports
`@tag` makes it do nothing at all.

So the trigger is a predicate over the input set, and `synthesise(registry)` adds exactly one
`SchemaExtensionDefinition` for the registry however many inputs are tagged. `captureSchema` walks
`registry.getSchemaExtensionDefinitions()`, so that one extension's `@link` becomes one
`graphql_schema_directive` row stamped `<graphitron-synthesised:tag-link>`.

Attributing that row to a document is therefore not available, and picking one of the tagged inputs is
unsound in the item's own terms: with two tagged inputs, refreshing the chosen one would delete a row
that must survive, because the other input still makes the predicate true. That is a partition losing
rows it still owns, which is the failure this item exists to remove, arriving through the disposition
meant to prevent it.

By the plan's own taxonomy the row is a function of the input set, so it takes the same disposition
`graphql_root_operation` just took: `graphql_schema_directive` re-aggregates, recomputed after the walk
from the surviving inputs and the same predicate. That moves it out of the 54 owned relations and the
counts again, to 77 / 30 / 17. It also settles the schema-level ownership question the phase's earlier
version raised and the rewrite dropped, since `graphql_schema_directive` was the other relation named
there.

One thing worth saying because it bears on how the disposition is written rather than on which one is
chosen: this row's existence is a function of the *recipe*, not of the document set, which is a shape
neither the owned set nor the re-aggregated set is defined for. Re-aggregation reaches the right answer
because it recomputes rather than deletes, so the plan does not need a fifth taxonomy row; the
treatment column for that relation should say the predicate it recomputes from, so an implementer does
not go looking for a declaration to survive.

Nothing else in the round is outstanding. If the disposition lands as re-aggregation with the counts
moved, phases one through four read as implementable to me and I would sign off on the next pass.

> **Author, 2026-09-07.** Accepted, and verified before changing anything:
> `TagLinkSynthesiser.apply` opens on `anyMatch(i -> i.tag().isPresent())` over the input map and
> `SchemaInput.tag` is a record component fed from the recipe, so the trigger is configuration and
> `synthesise(registry)` writes one extension for the whole registry. The disposition was unsound in
> this item's own terms, and the way you put it is the part worth keeping: with two tagged inputs,
> refreshing the chosen one deletes a row the other still requires, which is a partition losing rows
> it still owns, arriving through the disposition meant to prevent exactly that.
>
> `graphql_schema_directive` re-aggregates, recomputed after the walk from the surviving inputs and
> the same predicate, and the synthetic source name is retired. Counts move to **77 / 30 / 17**, which
> sums to 124, with the `graphql_` owned figure at 12 and the family still tallying to 28. Phase four's
> re-aggregation now covers seventeen relations.
>
> Your note on how to write it rather than which to pick is taken. The re-aggregated row's heading is
> now "a function of more than one source, of none, or of the recipe", and the taxonomy section names
> the predicate each of the two new members recomputes from, so an implementer is not sent looking for
> a declaration to survive. That row now holds five kinds rather than three, and the widening is the
> honest consequence of two populations that are functions of neither one document nor several.
>
> The schema-level ownership question is settled by this rather than parked: `graphql_root_operation`
> and `graphql_schema_directive` were the two relations it was about, and both are now re-aggregated.

### Round 4 (2026-09-07, Spec -> Ready, reviewer session 01BnH5mPddDZACkr4BfodYag)

Verdict: sign off. Finding 4 is answered as asked. `graphql_schema_directive` re-aggregates from the
surviving inputs and the same predicate, the synthetic source name is retired, and the re-aggregated
row's heading now carries the recipe case with the predicate named beside it, so the taxonomy states a
shape rather than stretching an existing one to cover it. The counts are right: 77 + 30 + 17 sums to
124, the `graphql_` family still tallies to 28 with both relations moved, and phase four's
re-aggregation covers seventeen.

Both gate questions are answered. A reader learns from the Goal that building one module stops
destroying another module's facts in a shared store, and that the omission becomes unrepresentable
rather than fixed, which is a stronger claim and the one the item delivers. The outcome is reachable:
across four passes every structural claim this plan makes about the tree has been checked against the
DDL and the capture code, and the ones that were wrong are now right. The solution extends shapes
already in the tree rather than standing a new one beside them: the `jvm_` family's sibling edges into
`jvm_class`, the sixteen `ON DELETE CASCADE` clauses the element family already calls its pattern, and
`store_graph_source` as a root it already has the key for. I would hand this to an implementer.

Two corrections landed in this commit rather than as findings, both determinate and neither changing
what gets built, which is the test the workflow sets for what a reviewer may fix in passing.

The counts moved twice across rounds 2 and 3 and seven sites kept the pre-move figure: the SDL root's
"scoped delete across 54 relations", "making the 54 source-owned SDL relations hang off it", "53 of
the 54" for the index argument, "the 14 source-owned `graphql_` relations" and "all 54" in the missing-
edges paragraph, phase four's "14 `graphql_` and 40 `graphitron_`", and the Tests section's "54 new
foreign keys and 54 new indexes". All now read 52, with 51 of 52 for the index figure and 12 for the
`graphql_` half, which is what the taxonomy table's 12 plus 40 already fixed them at. Phase three's
"nullable in 37 of the 54 relations" is left alone deliberately: it diagnoses the tree as it stands
before the phase acts, where both figures are correct. It is the one place "54" now means the pre-move
set, and a disambiguating clause there would not hurt.

`## Retired vocabulary` gained the entry phase three's own text requires, `SYNTHESISED_SOURCE_NAME`
and its value, since that section is the Done gate's only grep query for the sweep and a retirement
stated in the plan body but missing from the index is a hole in a later gate rather than a wording
choice.

### Non-blocking note (round 4)

`SdlFactCapture.stampTarget`'s javadoc is the one description phase three falsifies that the
descriptions sweep does not name. It calls the bundled directives and the synthesised name "the whole
miss set" and explains that "capture's stamp lookup has to name it to tolerate the miss instead of
absorbing it in a filesystem probe". Phase three retires one member and gives the other a stamp, so
the pair is no longer a miss set and the branch the javadoc explains is deleted;
`SYNTHESISED_SOURCE_NAME`'s own javadoc, which argues why the constant is public, goes with the
constant. Phase three mentions the javadoc but frames it as still true of sources with no file, which
is why the sweep did not pick it up. Left to the author rather than corrected here, because what
replaces a description is authoring rather than arithmetic. It is not a gate question: the item's
description discipline is already strong enough that this reads as one propagation miss from the
round-3 change, and the retirement sweep at Done will reach the constant either way.

### Notes from R876's session (2026-09-07)

Not a review round, and it arrives after round 4 signed the item off. One of these is blocking
anyway, and whether it reopens the item is for whoever owns it rather than for this session. R876's
entry-and-anchor work crosses phase four, and what it measured on the way belongs here rather than
in that item. The two designs do not conflict, and the first note is the evidence: both cut the
`graphitron_` family along the same line, and the rework's counts leave that line where it was. The
blocking one is the second, where phase four's shared-file refusal fires on graphitron's own bundled
schema file and therefore on the second graph of every store. Round 3 checked that file's
`store_source` row and found it present, which is right; what nothing has checked is that it also
carries a `store_graph_source` membership per graph. The rest are ordering, wording, and one thing
this item's phase three does for R876 in return.

**The two decompositions coincide exactly, which is confirmation and worth recording.** R876 splits
the family by writer, 56 relations written by the directive decode against 15 written by a gatherer
stage plus the match view. This item splits the same 71 tables by whether a row's existence is a
function of one source: 40 owned, 24 descendants, 7 keyed at `store_graph`. Measured against the
DDL, exactly 40 `graphitron_` tables carry a `source_name` column and every one of them is on the
decode side; not one of the fifteen resolved relations carries one. So the owned set is the
as-written set, 40 owned plus 16 descendant entries is 56, and 8 descendant anchors plus the 7
graph-keyed is 15. That is not a coincidence: a relation is a function of one source exactly when
its rows are a function of one document, which is what the decode side is. This item reaches the
same line from the refresh side, and its own three cross-family cases are the mirror image,
`graphitron_tabletype` and `graphitron_field_table` hanging off `sql_table` and
`graphitron_node_keycolumn` off `sql_column` being resolved relations, and the only three in the
family that key into the catalog at all.

**Phase four's rule refuses the second graph in every store, and the file it trips on is
graphitron's own.** The plan opens that phase by confirming "that no fixture captures two graphs
over one schema file, the new rule being a refusal an existing test could trip". Three fixtures do,
but they are the small half. `SdlFactCapture.captureSources` walks every source name the registry
handed back, calls `GraphSourceMembership.note` for each and writes a `store_source` row of kind
`SCHEMA_FILE`, and the bundled `directives.graphqls` is always in that set because every graph
parses it. Measured on a two-graph capture: three `SCHEMA_FILE` sources, the two consumer files with
one membership each and `directives.graphqls` with two. So a second graph always claims a schema
file the first one owns, `CHECK ((graph_name IS NOT NULL) = (source_kind = 'SCHEMA_FILE'))` demands
a single graph for a file every graph reads, and the typed rejection fires on the ordinary
two-module workspace this item was filed for.

**The distinction the rule wants is consumer file against bundled file, not one graph against two.**
A consumer's schema file belonging to exactly one graph is a rule worth having and nothing here
argues against it. What cannot hold is the same rule over a file graphitron ships inside its own
artifact and every graph reads by construction. That file is not a shared resource in the sense the
rule is guarding against, because nobody outside graphitron can edit it and graphitron owns when it
changes: phase three already says so, stamping it with the generator version `store_stamp` records.
Sharing is safe exactly where the refresh cadence is ours.

**Which says the ownership column is in the wrong relation, and the item already half-knew it.**
Whether a graph read a file is a fact about the membership, and `store_graph_source` holds it and
puts no uniqueness on `source_name` on purpose. `store_source.graph_name` is a second answer to the
same question, kind-filtered, and round 1's non-blocking note already flagged it as the shape
`store_graph_source`'s own comment argues against. The bundled file turns that from a design
preference into a counterexample: there is no single graph to put in the column. Three ways out, and
the first looks cleanest. Give the bundled file a `source_kind` of its own, since it is a different
kind of thing on every axis the registry cares about, shipped inside the generator, stamped by
generator version, read by every graph, and phase three already handles it specially for stamping;
the `CHECK` then keeps its shape and the refusal keeps its scope. Or admit a NULL `graph_name` for
it, which weakens the `CHECK` to a disjunction and leaves the rule guarding less than it looks like
it does. Or drop the column and state the rule where the fact lives, as uniqueness over
`store_graph_source.source_name` restricted to consumer sources.

**One consequence for `SourceGraph.Shared`, which the refusal was going to make unreachable.**
Under the corrected rule the arm stops being a general facility and becomes the bundled file's case:
after consumer files are held to one graph, `directives.graphqls` is the only source that can carry
two memberships. So the arm survives, narrowly, for a reason nobody has to defend as policy. Whether
the LSP ever needs to resolve that particular file is a much smaller question than whether a sealed
arm in main sources should exist at all, and the plan can settle the refusal without answering it.

**The joint root is only precise once one gatherer writes both halves of the family.** Phase four
roots `graphql_` and `graphitron_` together at `store_graph_source` and describes the cheap strategy
as deleting one row per root per changed source, after which "the cascade clears everything beneath,
and the walk rewrites". That last clause is false today for 40 of the 52 relations. The cascade
clears them and the SDL walk does not rewrite them, because the graphitron gatherer writes them and
it runs over the whole graph's transcription rather than over one source. So per-source precision
stops at the family boundary: refreshing one schema file either re-runs the entire decode for the
graph or leaves 40 relations empty. R876's first slice moves those 40 writes into the walk, after
which one walk of one source rewrites exactly what the cascade deleted. Today's wholesale clear
hides this, which is why the family table can name a joint root without the question arising. The
two items carry no dependency on each other, but this is the reason to take R876's slice one first,
and it is correctness rather than convenience.

**One wording, because the word is load-bearing in the other item.** The re-aggregated set is
described here as "the seven `graphitron_` anchors that key at `store_graph` rather than at a
source", naming the element family and leaving three unnamed. At least one of the unnamed three is
an entry: `graphitron_spelled_reference_entry` keys `(graph_name, spelling)`, foreign-keys only to
`store_graph`, carries no `source_name`, and deduplicates a spelling across the seven sites that can
write one, so its rows really are a function of the document set and it really does belong in that
bucket. It is also as-written in every other respect and asks nothing of the catalog. Naming the set
by what it is, graph-keyed, rather than by "anchor" costs nothing and stops the two items using one
word for two things.

**One thing this item does for R876 in return.** Phase four was blocked by round 1's finding that
`source_name` goes unset in 37 of the 54 relations, counted before the rework moved two of them out,
and phase three is what answers it. R876's slice one writes its 40 source-attributed entries from
the walk, which is where the real `SourceLocation` is rather than one rebuilt from stored columns.
If phase three lands first, those entries are written against positions already attributed and the
question does not arise for them.

**A fixture both items need now exists.** `EntryFamilyFixture` in `graphitron-model`'s test sources
applies every graphitron directive the decode writes a relation for, across two documents, and
`EntryFamilyCoverageTest` holds it to populating all 56 entry relations, to nine of them holding
rows from two sources at once, and to its two lists partitioning the family so a new relation has to
be classified. The two-source overlap is there for this item rather than incidental to it: a
per-source delete can only be observed on a relation holding rows from two sources, so the gate
holds the overlap and the next edit to the SDL cannot quietly remove it.

### Round 5 (2026-09-07, Ready -> Spec, reviewer session 01BnH5mPddDZACkr4BfodYag)

Verdict: reopen. R876's session is right that its second note is blocking, and it defeats my round-4
sign-off rather than qualifying it. I verified it against the tree instead of taking it, and it holds.
Phase four's shared-file rule is not severable policy that an implementer could ship and revisit; it
is unsatisfiable as written, and the refusal fires on the second graph of every store. Status goes
back to `Spec`, which re-engages the guard, so a fresh independent sign-off is owed after the
revision. Phases one, two and three are unaffected and still read as implementable.

Of R876's eight notes, two are gate-blocking and are restated as findings below so the author knows
what must be answered. The rest are advisory, and I checked the two that make claims about the tree:
exactly 40 `graphitron_` relations carry a `source_name` column, which is the same 40 this item calls
owned, so the two decompositions do coincide; and `graphitron_spelled_reference_entry` does key
`(graph_name, spelling)` with no `source_name` and a foreign key only to `store_graph`, so it does
belong in the re-aggregated set and "graph-keyed" is a better name for that set than "anchors". Both
are fair and neither is a gate question.

**Finding 5 (question two: the rule cannot hold). `directives.graphqls` is a `SCHEMA_FILE` source that
every graph reads, so "a schema file belongs to exactly one graph" has no satisfying assignment and
the refusal fires on the second graph in any store.**

The mechanism is three steps and all three are in the tree today. `SdlFactCapture.captureSources`
collects a name from every definition the registry holds, and `addSource` admits any non-null source
name, so `SchemaLoader.DIRECTIVES_SOURCE_NAME` is in the set for every graph, the bundled definitions
being registry rows by design (the directive-definition walk's own javadoc: "Graphitron's own bundled
definitions are rows too"). For each name it calls `GraphSourceMembership.note`, before and
independent of the `sink.claim(STORE_SOURCE, name)` guard, so membership is recorded per graph
whether or not this run claimed the source row. Then `ClasspathSources.upsert(dsl, name, SCHEMA_FILE)`
inserts-or-updates on `source_name` alone, so the bundled file is one `store_source` row of kind
`SCHEMA_FILE` shared by every graph, carrying one `store_graph_source` row per graph.

This is already pinned by a shipped test rather than only inferable.
`TaggedCaptureStampTest` fetches the `SCHEMA_FILE` rows and asserts that every one that is not the
fixture file is either `TagLinkSynthesiser.SYNTHESISED_SOURCE_NAME` or
`SchemaLoader.DIRECTIVES_SOURCE_NAME`, both unstamped. So the row exists, its kind is `SCHEMA_FILE`,
and the test would have to change for it not to.

Phase four then adds `CHECK ((graph_name IS NOT NULL) = (source_kind = 'SCHEMA_FILE'))` and a typed
rejection when a second graph claims a schema file another graph owns. The bundled file must
therefore name exactly one graph while every graph reads it, and the second module captured into a
shared store is refused. That is the ordinary two-module workspace this item was filed to fix, so the
rule as written breaks the item's own goal case.

Two things make this worse than a fixture problem. Phase three deliberately *keeps* the bundled file
as a source, giving it the stamp it lacks tied to the generator version, so the counterexample
survives phase three by design rather than being cleared by it. And it is not one file:
`SYNTHESISED_SOURCE_NAME` is a second `SCHEMA_FILE` row shared across every graph with a tagged
input, which the same test pins. Phase three retires that one, but its existence shows the rule's
premise is false for generator-injected source names as a class rather than for one path.

R876's note offers three ways out and I am not choosing between them; that is the author's fork. I
will say the diagnosis underneath it looks right to me, and it is the one round 1 raised and I let
stand: whether a graph read a file is a fact about the membership, `store_graph_source` already holds
it and deliberately puts no uniqueness on `source_name`, and `store_source.graph_name` is a second
kind-filtered answer to the same question. Round 1 recorded that as the strongest argument against the
rule and accepted the author's framing of it as severable policy. It was not severable, and I should
have tested whether the rule was satisfiable before signing off rather than judging only whether it
was desirable. That is my miss, not a moved goalpost.

**Finding 6 (question one: a claim about code). Phase four's "the cascade clears everything beneath,
and the walk rewrites" is false for 40 of the 52 relations it roots.**

`GraphitronFactCapture.capture(sink, dsl, graphName)` takes a graph name and nothing else, and
`SOURCE_NAME` appears once in the whole class, so the decode runs over the graph's entire
transcription rather than over one source. A per-source delete of the `store_graph_source` root
therefore cascades the 40 owned `graphitron_` relations away, and no per-source walk rewrites them:
refreshing one schema file either re-runs the whole graph's decode or leaves those 40 empty. Today's
wholesale clear hides this, which is why the roots table can name a joint root for both families
without the question surfacing.

Nothing ships broken from this, because the item changes no gatherer's route and the SDL families are
still re-walked per graph. What is wrong is the sentence, which states a property of the mechanism
that does not hold for the larger half of the family it is about. Whether the answer is to qualify it,
to say the joint root buys per-source precision only for the `graphql_` half until one gatherer writes
both, or to take a sequencing dependency on R876's first slice, is the author's call. R876 argues the
last and calls it correctness rather than convenience; on the evidence above that reading is
defensible, but the item can also ship honest prose and no dependency, since the imprecision is latent
until something asks for a per-source SDL refresh.

**A fixture worth knowing about.** `EntryFamilyFixture` and `EntryFamilyCoverageTest` in
`graphitron-model`'s test sources now hold nine relations to carrying rows from two sources at once.
A per-source delete can only be observed on a relation holding rows from two sources, so that overlap
is the shape this item's own regression test needs, and the Tests section may be able to lean on it
rather than build its own.

### Round 5 addendum (2026-09-07, reviewer session 01BnH5mPddDZACkr4BfodYag)

Appended rather than folded into round 5 above, which stands as written. R876's note said the
`store_source` row had been checked but that nothing had checked the per-graph membership. That gap
is now closed, and by an existing test rather than by reasoning from `GraphSourceMembership.note`.

`FactCaptureAgreementTest.graphSourceMembershipEqualsTheRunsReadSet` captures two graphs into one
store and asserts each graph's `store_graph_source` set equals its own read-set, then re-asserts the
first graph's set after the second is captured. Its oracle, `sdlSourceNames`, mirrors
`captureSources` and walks `registry.getDirectiveDefinitions()`, which is where the bundled
definitions live, so `directives.graphqls` is in *both* graphs' expected sets and the test pins that
it is in both actual ones. With `TaggedCaptureStampTest` pinning the single `SCHEMA_FILE` row for that
same file, finding 5 is pinned from both ends: one registry row, two memberships, both asserted today.

Which resolves phase four's own pre-flight step against it. That phase opens by saying
"Implementation confirms first that no fixture captures two graphs over one schema file, the new rule
being a refusal an existing test could trip". This is that test, it does trip, and the reason is not a
fixture that happens to share a file: both graphs read the bundled file by construction, so no fixture
edit can make the check pass. The step is worth keeping in the revised phase, but its answer is
already known and it is the finding rather than a way around it.

> **Author, 2026-09-08.** Both findings accepted, and one note covers both since the revision is one
> change. Findings 5 and 6 are left exactly as written above; the round-1 non-blocking note is where
> this should have been settled and I am not going back to amend it.
>
> **Finding 5.** The rule is dropped rather than repaired, which is the third of the three ways out.
> The user reported independently that we have consumer schema files shared between graphs, so the
> bundled file is the sharpest counterexample rather than the only one, and a rule that has to be
> narrowed to "consumer files, but not that one" is answering a question the keys already answer. The
> section is rewritten as "A source file can belong to several graphs" and states why sharing is safe
> from the keys rather than from a rule: `jvm_class` carries no graph dimension because a class
> declaration is a function of the bytes, `graphql_type_declaration` is graph-keyed and carries
> `merge_ordinal`, a property of the graph's document set, so the two kinds of fact root in different
> places and each graph holds a root of its own over a shared file. Round 1 had the diagnosis and I
> accepted my own framing of it as severable policy; it was not severable, and the column was the
> wrong relation for the fact from the start.
>
> One column is genuinely missing and phase four now adds it. `store_source.stamp` says what the bytes
> are and nothing says what content a given graph's rows were built from, which under sharing is a
> silent stale read the day R857 lands: A re-walks an edited shared file, the registry stamp becomes
> the new hash, and B finds that stamp matching the bytes and keeps rows built from content that is
> gone. `store_graph_source` gains `stamp` and the currency test becomes an equality between the two
> columns. `read_at` is deliberately not added beside it: the graph's question is which content its
> rows came from, and the time questions are already answered store-global.
>
> A first draft of this revision kept `ClasspathSources.upsert`'s null-stamp protocol and relocated it
> to the membership row. That was wrong and the user caught it: `FactCapture.capture` is one
> transaction with `commitStamps` inside it, and the blanking branch fires only against an existing
> row, which means a warm store, which is the path where the stamps commit with the rows. It is
> hand-rolled crash consistency standing in for a transaction we already have, so it goes rather than
> moves, and the surviving rule has no null in it. Two descriptions go with it and the descriptions
> sweep names them. The one description phase four was going to falsify, `store_source`'s "store-global
> rather than graph-keyed", is kept exactly as it stands and is now load-bearing.
>
> **Finding 6.** The sentence is qualified rather than defended, in the design section where it
> appears. `GraphitronFactCapture.capture` takes a graph name and nothing else, so the joint root buys
> per-source precision for the 12 `graphql_` relations and the 40 owned `graphitron_` ones are
> re-decoded whole or left empty until one gatherer writes both halves. No dependency on R876's slice
> is declared, per your framing that the item can ship honest prose instead; what the plan does record
> is that the ordering is the project's own, the transcription families first and the decode family
> after, which is where that gap already sits.
>
> R876's advisory notes are taken where they bear on this text. The 40/24/7 split and the decode-side
> coincidence are recorded as confirmation rather than restated, and I have not renamed the
> re-aggregated set from "anchors" to "graph-keyed" in this pass: the set now holds five kinds and two
> of them are neither, so a rename would trade one imprecision for another, and the row's heading
> already says what the members have in common. That is a wording call and I would not defend it hard
> if you disagree.
>
> Verified on the tree before writing: `captureSources` and `ClasspathSources.upsert` for the bundled
> file's single row, `FactCapture.capture` for the transaction boundary and `commitStamps` inside it,
> `GraphitronFactCapture.capture`'s signature for finding 6, and `graphql_type_declaration`'s key and
> `merge_ordinal` comment against `jvm_class`'s key for the sharing argument.

### Author revision, 2026-09-08: the removal stops at the membership row

No reviewer round between this and the one above; it is a design change the user directed after the
shared-file rework, and it changes what phase four builds, so it is recorded here rather than left to
be inferred from the body.

The removal path cascaded through `store_graph_source` and took every graph's readings with it. It now
stops there. The row gains a nullable `source_ref` holding a second copy of `source_name`, under a
`CHECK` tying the two together and a foreign key with `ON DELETE SET NULL`, so deleting a registry row
nulls the column on every graph's membership row and deletes none of them. Each graph reaps its own
with one statement whose cascade clears its subtree, the running graph in that round and the others on
their next boot.

An earlier pass in this conversation dismissed `ON DELETE SET NULL` for this item, on the grounds that
the coordinate anchors are foreign-key parents rather than children so the cascade already stops at
them, and that a nulled attribution would be a lie about which file declared a row. The first half was
true and answered a different question, the anchors being about refresh where this is about removal.
The second was answered by the user's construction: with the attribution kept in the primary key and
the twin carrying only the reference, the null is not a lost attribution but the registry withdrawing
its vouch, which is a fact worth recording rather than one being destroyed.

Four things were checked against H2 2.4.240 rather than assumed, and one of them changed the design.
Set-null on a twin outside the primary key works and the `CHECK` survives it; the subtree hanging off
the surviving row survives too, and one `WHERE source_ref IS NULL` delete reaps it by cascade; a child
carrying both a cascading and a set-null edge to one parent is accepted by H2 and the cascade wins, so
the row is deleted and the null never happens. That last one is why the primary-key column gives up its
foreign key rather than keeping it, and the fourth check confirmed the cost: with no key there, a
membership row naming an unregistered source inserts without complaint.

The user's answer to that cost is the enforcement, and it is better than the key it replaces. Every
refresh, in `StoreRefresh.prepare`, reconciles the readings the round inherited, and one predicate
covers both failures: an orphan whose source was removed under it and a wrong row whose column was
never set are both readings the registry does not vouch for. The remedy is the same for both, and it
is scoped to the running graph's own rows, which is what stopping the cascade was for.

### Author revision, 2026-09-08: every root takes the twin

Directed by the user, on the ground that the asymmetry was not worth its explanation. The previous
revision twinned only `store_graph_source` and let the source-keyed families cascade, which left two
removal behaviours in one design and a boundary to remember. Now `sql_schema`, `sql_enum_binding`,
`jvm_class` and `java_file` carry the same twin, deleting a registry row deletes exactly that row, and
the rule is one sentence: a removal flags, and the owner reaps. Phase one carries the three catalog and
classpath roots and the sweep; phase two carries `java_file` when it joins the registry; phase four
adds the membership row to a mechanism that is already there.

The reservation I held was cost, `jvm_class` being the one root with many rows per source, and it was
measured rather than argued. Five real consumer stores in the local cache: `jvm_class` holds 3.4k to
11k rows over 26 to 173 sources, and duplicating the source name totals 262 KB to 964 KB against stores
of 408 MB to 824 MB. Under an eighth of a percent, so the reservation was wrong and the symmetric
design is also the cheap one. Those stores each hold a single graph, so they measure bytes and say
nothing about how common sharing is; the sharing premise still rests on the user's report and on the
bundled file.

Two things the symmetry buys beyond predictability. The sweep now has more than one member, which is
what makes deriving it from the schema's own twin declarations worth doing instead of naming the
relation; a family root added later is swept without anybody remembering it, and a roster here would
have been `PARTITIONED` again in a new costume. And the enforcement generalises with it: the same
predicate stands in for the foreign key every one of those five primary-key columns gives up.

One thing it does not buy, now stated in the body rather than left for a reviewer. The graph-keyed
`graphitron_` decodes hanging off `sql_table` and `sql_column` are one copy under a shared parent, so
reaping a flagged `sql_schema` still takes a not-running graph's decode rows. Symmetry moves when that
happens and does not prevent it. The distinction that makes it acceptable is worth having explicitly: a
twin protects what is recoverable only by re-reading a source, and a decode is recoverable from the
graph's own surviving `graphql_` rows without re-reading anything.

### Author correction, 2026-09-08: the catalog-keyed decodes were never a limitation

The revision above closed with a paragraph naming a residual: that reaping a flagged `sql_schema`
takes a not-running graph's `graphitron_` decode rows, that symmetry only moves when it happens, and
that closing it properly would mean re-parenting three relations. The user pushed on it, on the ground
that a design should not ship a strange limitation, and they were right to. There is no limitation, and
the paragraph was a misdiagnosis rather than a scope judgement.

All three relations are anchors with entries already beside them, which the DDL says plainly and which
nothing but my own inference contradicted. `graphitron_tabletype` holds a resolved
`(table_source_name, table_schema, table_name)` and no authored text; `graphitron_table_entry` holds the
`@table` spelling in `table_ref` with its declaration position and keys into
`graphql_type_declaration`. `graphitron_node` hangs off the anchor while `graphitron_node_entry` holds
the authored `type_id` and position; `graphitron_node_keycolumn_entry` holds the authored `column_ref`;
`graphitron_field_navigation` holds what `graphitron_field_table` resolves, keyed at
`graphitron_field` with no catalog reference.

So removing a jOOQ package deletes resolutions naming a table that is gone and deletes nothing an
author wrote, every entry sitting under the graph's own membership row where its twin protects it. The
body now says that, and states the rule positively: an entry is owned and the twin protects it, an
anchor is a function of both sides and dies with either. That is the same line R876 cuts, which is
confirmation rather than coincidence.

The error is worth naming because it is the one this item's review has caught four times: a claim I
reasoned to rather than executed. I inferred from a foreign key into `sql_table` that the row was a
graph's owned fact, and one look at the relation beside it would have said otherwise.

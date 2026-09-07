---
id: R872
title: "The keys say what a source owns, and the gatherer decides how to refresh it"
status: Spec
bucket: architecture
priority: 2
theme: tooling
depends-on: []
created: 2026-08-28
last-updated: 2026-09-06
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
list of 21 exemptions, and the exempted ones are deleted per source by eighteen hand-written
statements across three files, hand-ordered children before parents. Membership on the list is an
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
decide it has nothing to rewrite while the rows are gone. R857 also needs what this item builds. A
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
| the eleven per-source statements in `CatalogFactCapture.clearSchemaSources`. Two roots, not one:
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
| a scoped delete across 54 relations, which is why this family needs its root named rather than
  assumed
|===

So a gatherer taking that route deletes one row per root per changed source, the cascade clears
everything beneath, and the walk rewrites. The registry row persists either way and its `stamp` and
`read_at` update in place, which is where R922's currency comparison expects to find them. Nothing
here obliges a gatherer to take that route, and the item ships no change to which route any existing
gatherer takes; what changes is that the choice becomes theirs to make.

**Deleting the `(graph, source)` row is not the mechanic this plan rejects, and the difference is worth
being explicit about since the two look alike.** `store_source` holds the file's identity, which a
refresh does not change and must not churn. `store_graph_source` holds *this graph's reading of that
file*, and a re-read genuinely replaces the reading, so the row going and coming back is the fact
being restated rather than an identity being churned to trigger a side effect. A reviewer should test
that claim rather than take it: if the reading row ever acquires state worth preserving across a
re-read, this stops being true and the family needs a root of its own.

**A refresh in one corpus can validly delete rows in another family**, and the family-by-family table
hides it. `graphitron_tabletype` and `graphitron_field_table` hang off `sql_table`, and
`graphitron_node_keycolumn` off `sql_column`, so refreshing a jOOQ package deletes those decode rows.
That is correct, since a decode resolved against the catalog cannot outlive the catalog row it
resolved against, and it is exactly the kind of edge a hand-written per-family delete has no way to
find.

`store_graph_source` earns its place here without being a second registry. It already exists, already
keys `(graph_name, source_name)`, already carries foreign keys to both sides, and is already
maintained by `GraphSourceMembership`. Making the 54 source-owned SDL relations hang off it
gives that family the same shape `sql_schema` and `jvm_class` give theirs: one row to delete, one
cascade, no roster.

### Removing a source that went away

A schema file deleted, a jar dropped from the classpath, a `.java` file removed. Here the registry
row is exactly what should go, and deleting it takes the family roots, their subtrees and the
membership rows with it in one statement. This is the operation the cascade to `store_source` is for.

It is also an operation the store does not currently have at all, which is worth stating as a gain
rather than leaving implied. Nothing in the tree deletes a `store_source` row: there is no
`deleteFrom(STORE_SOURCE)` anywhere, and `StoreReaper` reaps whole store *files* from disk rather than
partitions inside one. `StoreRefresh` says so from the other side, retaining a source absent from this
run's input set on the correct ground that another graph may still need it, and nothing ever asks
whether any graph still does. So a jar that leaves a consumer's classpath keeps its classes for the
life of the workspace cache, and the store grows monotonically across a project's dependency churn.
This item does not add the reaping policy, which is a question about when a source is known to be
unwanted rather than about how to remove it, but it is what makes the removal expressible in one
statement instead of eighteen. That bears on R917 and on the cache byte-budget item, and both should
be told the mechanism exists rather than each inventing one.

### A schema file belongs to exactly one graph

`store_source` gains a nullable `graph_name` with a foreign key into `store_graph` and a
`CHECK ((graph_name IS NOT NULL) = (source_kind = 'SCHEMA_FILE'))`, and a second graph claiming a
schema file another graph owns is refused with a typed rejection naming both graphs and the file.

Two honest notes on it. The refusal itself stays in capture, because a cross-row rule of this shape
is no `CHECK` and H2 carries no filtered unique index to express it; the column makes the owner
single-valued and stated on the row, which is what gives the refusal something to compare against.
And under the mechanics above the rule is a simplification rather than a correctness requirement: the
SDL family roots at the `(graph, source)` row, so two graphs sharing a file would get a root each and
each graph's refresh would already delete only its own. The rule is kept because a shared schema file
is not a thing we want to support, and because every relation of two families is cheaper to reason
about when a file has one owner. It is not load-bearing for the cascade, and a reviewer should judge
it as policy.

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
| 79
| A cascading foreign key into its family root, or into the registry where it is a root itself.
  14 `sql_`, 7 `jvm_`, 4 `java_`, 14 `graphql_`, 40 `graphitron_`.

| Descendant of an owned row
| 30
| Nothing to do; it already cascades transitively. `graphql_directive_location` under
  `graphql_directive`, the five `*_directive_arg` relations under their applications, and the 24
  `graphitron_` decodes hanging off owned rows or off catalog rows.

| A function of more than one source
| 15
| Re-aggregate after the walk and delete what no longer matches.
|===

The last row is the case that cannot cascade, and it has three kinds in it. Six are the SDL coordinate
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
- **The foreign-key graph is acyclic**, checked over all 176 base relations, so a cascade terminates
  and needs no ordering decision from any caller.
- **The idiom is already project doctrine.** The DDL carries 16 `ON DELETE CASCADE` clauses, and the
  element family's own comment declares it the pattern every relation there follows. The source-keyed
  families are the ones hand-rolling it instead.
- **The cross-source case is already modelled.** `sql_referential_constraint` carries two foreign keys
  into `sql_constraint`, its own and the referenced one, which is the schema-crossing key that
  `CatalogFactCapture.clearSchemaSources` runs two separate loops to handle. Two cascading edges do it
  with no loop at all.

**The new SDL edges need indexes, and this is the item's main cost.** `source_name` sits outside the
primary key of 53 of the 54 source-owned `graphql_` and `graphitron_` relations, only
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
parent. The 14 source-owned `graphql_` relations carry `source_name` and reference no source
registry at all, and the 40 source-owned `graphitron_` relations are in the same position; all 54
gain a cascading `(graph_name, source_name)` foreign key into `store_graph_source`, which is what
gives that family the single root row the other three already have.

## What the code loses

- `StoreRefresh.PARTITIONED`, `StoreRefresh.wholesale()`, and `clear`'s wholesale loop.
- `clear`'s seven hand-unrolled `jvm_` deletes, and the children-before-parents order they are
  hand-written in.
- The eleven per-source deletes and the two-loop structure in
  `CatalogFactCapture.clearSchemaSources`.
- `StoreRefresh.childrenFirst` for these families, the database owning the order instead.

Two things stay, named so they are not read as collateral. `freshSources` still decides which sources
this round re-read; that set becomes the argument a gatherer refreshes against, rather than the scope
of eighteen statements the store issues on its behalf. And the claim seeding in `prepare` is
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

**One description becomes false and has to be rewritten rather than trimmed.** `meta_relation`'s
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

Three phases, and the seams are real rather than bookkeeping: each lands a family group that can be
observed working while the others are untouched, and only the last needs the re-aggregation. The first
phase pays the `ddl_hash` store discard, so the later two are free.

**Phase one, the source-keyed families.** Add `jvm_declared_type_ref`'s missing edge; add
`ON DELETE CASCADE` throughout the `sql_`, `jvm_` and `java_` webs so each family cascades from its
roots; replace the eighteen statements between `clear`'s `jvm_` block and `clearSchemaSources` with
one delete per root per changed source, which is two for `sql_` and one each for the other two and is
the strategy those three gatherers take today; remove `PARTITIONED` and `wholesale()`. The four
relations this item was filed for are carried by the cascade with nothing naming them.

**Phase two, `java_` joins the registry.** Add the `JAVA_SOURCE` kind, give `java_file` a cascading
foreign key into `store_source`, and move its `stamp` and `read_at` up to the registry row so currency
is stated once. `JavaSourceFacts` is the only reader or writer of either column, so the move is
contained to one class; R922's comparison takes the instant as an argument and does not care which
relation it came from.

**Phase three, the SDL families.** Add `store_source.graph_name` with its foreign key and `CHECK`, and
the refusal a second graph meets; add the cascading `(graph_name, source_name)` foreign keys from the
14 `graphql_` and 40 `graphitron_` source-owned relations into `store_graph_source`, with the index
each needs; write the re-aggregation over all fifteen relations that are a function of more than one
source, the coordinate anchors of both families and the two cross-file verdicts among them; reduce the
graph-scoped clear to what does not now cascade. Implementation confirms first that no fixture
captures two graphs over one schema file, the new rule being a refusal an existing test could trip.

## Tests

- **The regression this item was filed for**, in `WarmStartRefreshTest`: capture graph B over one jOOQ
  package fixture and graph A over a different one so the two share no source, capture A warm, and
  assert B's rows are unchanged. Written as a sweep over the source-partitioned relations derived from
  schema metadata rather than over named relations, so a relation added later is covered unedited, with
  a control asserting B holds rows in the four before A's capture runs. The default fixture catalog
  publishes node metadata and declares `films_for_actor` with reflected parameters, both already
  asserted non-empty by `FactCaptureAgreementTest`, so the control is real rather than nominal.
- **The refusal that makes one root sound**, which is the test that fails under the wrong design
  rather than under a typo: a second graph claiming a schema file another graph already owns is
  rejected, with the message naming both graphs and the file. Its companion asserts the case the rule
  permits, two graphs over two schema files in one store, each refreshed without touching the other.
- **The aggregate case**: a type declared in one file and extended in another, the extending file
  re-read and then removed, asserting the coordinate survives the first and goes on the second, and
  that the `graphitron_` decode hanging off it goes with it.
- **A structural gate over the reference web**, which is what replaces the list with something
  checkable: every base relation of the five families either reaches its root by declared foreign keys
  whose every edge cascades, or is named in a small declared aggregate set. A relation added with no
  such path fails the build. It carries a floor on how many relations it classified, so a metadata
  change cannot let it pass vacuously, that failure mode having been recorded against
  `FactSchemaGateTest.currencyAccompaniesEveryStamp` at R922's Done gate.
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
  cost is measured too and is the one more likely to regress: 54 new foreign keys and 54 new indexes sit on
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

## Other solutions we've considered

**Adding the four missing relations to `PARTITIONED`.** The one-line fix, and what this item was
specified as until the mechanism was examined. Rejected because the list is the defect: nothing derives
it, nothing checks it, and its 21 members are three different situations under one name, being deleted
here, deleted elsewhere, and not capture's business at all. The four went missing because a
hand-maintained exemption list has no way to notice an omission, and patching it leaves the next
omission free to happen.

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
delete-and-rewalk it performs today, expressed as one delete rather than eighteen. Moving a gatherer
to reconciliation is a decision about that corpus, wants the measurement of its own walk beside it,
and belongs with R857, which is the item that needs the knowledge reconciliation produces. Folding it
in here would mix a data-loss fix with a per-corpus performance judgment.

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

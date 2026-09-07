---
id: R872
title: "The keys say what a source owns, so refreshing one is a single delete"
status: Spec
bucket: bug
priority: 2
theme: tooling
depends-on: []
created: 2026-08-28
last-updated: 2026-09-06
---

# The keys say what a source owns, so refreshing one is a single delete

## Goal

The store says what a source owns in the only place that cannot go stale, its own foreign keys, and
refreshing one source becomes a single delete per family instead of eighteen hand-written statements
and a list of exemptions. A *source* is one input the store has read, a `.graphqls` file, a jar, a
generated jOOQ package or a `.java` file, each carrying a row in `store_source`; a *partition* is the
rows one source owns in a relation that several sources share.

The foreign keys carry `ON DELETE CASCADE`, which is a statement about ownership rather than a
schedule for deleting anything. It says a column's row cannot outlive the table row it describes, so
whatever delete anybody does issue is complete without being told what else to remove. A refresh then
deletes the one row each family hangs its facts from, and the source's own registry row is left alone,
because the file still exists and its identity has not changed. Deleting the registry row is the other
operation, the one for a source that has genuinely gone away.

What this removes is a mechanism, not a bug. Today a refresh empties every relation outright unless
that relation appears in `StoreRefresh.PARTITIONED`, a hand-maintained list of 21 exemptions; the
relations on the list are then deleted per source by eighteen hand-written statements spread across
three files, hand-ordered children before parents. Membership on the list is an unverified promise
that one of those statements exists somewhere else. When this lands there is no list, no exemption
polarity, no wholesale arm and no hand-ordered delete. Retention becomes the decision not to issue a
delete, and completing a delete becomes the database's job.

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
content change invalidates is the facts read out of the file, and each family already has a single
relation those facts hang from:

[cols="2,2,4"]
|===
| Family | Its root row | One delete replaces

| `sql_`
| `sql_schema` for the source
| the eleven per-source statements in `CatalogFactCapture.clearSchemaSources`, tables, columns,
  constraints, indexes, node metadata and routines all cascading from it

| `jvm_`
| `jvm_class` for the source
| the seven hand-unrolled deletes in `StoreRefresh.clear`, hand-ordered children before parents

| `java_`
| `java_file` for the file
| the family's own declaration sweep

| `graphql_`, `graphitron_`
| `store_graph_source`, the `(graph, source)` row
| a scoped delete per relation across 56 relations, which is why this family needs its root named
  rather than assumed
|===

So a refresh deletes one row per family per changed source, the cascade clears everything beneath it,
and the walk rewrites. The registry row persists throughout and its `stamp` and `read_at` update in
place, which is also what R922's currency comparison expects to find there.

`store_graph_source` earns its place here without being a second registry. It already exists, already
keys `(graph_name, source_name)`, already carries foreign keys to both sides, and is already
maintained by `GraphSourceMembership`. Making the 56 source-attributed SDL relations hang off it
gives that family the same shape `sql_schema` and `jvm_class` give theirs: one row to delete, one
cascade, no roster.

### Removing a source that went away

A schema file deleted, a jar dropped from the classpath, a `.java` file removed. Here the registry
row is exactly what should go, and deleting it takes the family roots, their subtrees and the
membership rows with it in one statement. This is the operation the cascade to `store_source` is for,
and it is the one the current code has no clean expression of at all.

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

Every relation of these five families is one of three things. The counts are derived from the DDL
rather than estimated.

[cols="3,1,5"]
|===
| Kind | Count | Treatment

| Source-attributed: carries `source_name` or `file`
| 81
| A cascading foreign key into its family root, or into `store_source` where it is the root itself.
  14 `sql_`, 7 `jvm_`, 4 `java_`, 16 `graphql_`, 40 `graphitron_`.

| Descendant of a source-attributed row
| 6
| Nothing to do. It already cascades transitively through the parent carrying the attribution:
  `graphql_directive_location` under `graphql_directive`, and the five `*_directive_arg` relations
  under their directive-application parents.

| Aggregate over declaration sites
| 6
| Re-aggregate and delete what no longer matches. `graphql_element`, the four `*_element` coordinate
  anchors, and `graphql_type`.
|===

The aggregate row is the case that cannot cascade, and the reason is worth stating plainly because it
is what makes this design more than a schema edit. A coordinate exists if *any* declaration site names
it, and a type may be declared in one file and extended in three others. So no single source owns
`graphql_element`, and deleting one file's rows must not remove a coordinate another file still
declares. After the changed sources' roots are deleted and re-walked, the coordinate set is recomputed
from the surviving `graphql_type_declaration` and its kin, and coordinates with no remaining
declaration are deleted.

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

Three sets of edges are missing and are this item's schema work. `jvm_declared_type_ref` carries no
foreign key at all: it is a defect rather than an exemption, its key leading `(source_name,
class_name)` which is exactly `jvm_class`'s whole primary key and exactly the edge its four siblings
already carry, so it gains
`FOREIGN KEY (source_name, class_name) REFERENCES jvm_class (source_name, class_name) ON DELETE
CASCADE`. Its `referenced_class` column deliberately gets none: that names a class which may sit in
another source or in no captured source at all, and the schema already refuses to model cross-source
resolution as a reference. The owner-precise edge is not expressible either, the owner being a
method, a record component or a method parameter by `owner_kind`, so the common ancestor is the right
parent. The 16 source-attributed `graphql_` relations carry `source_name` and reference no source
registry at all, and the 40 source-attributed `graphitron_` relations are in the same position; all 56
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
this round re-read; that set becomes the argument to one delete per family rather than the scope of
eighteen. And the claim seeding in `prepare` is untouched, being an insert-side optimisation that
stops a walk rewriting a partition it is retaining, which is orthogonal to how deletion happens.

What does not appear anywhere is a delete of a registry row followed by an insert of the same registry
row. A refresh never churns a source's identity to clear its facts; the row stays, and its `stamp` and
`read_at` update in place, which is where R922's currency comparison reads them.

## What an existing store does at the upgrade

It is discarded once, deliberately. Adding foreign keys and cascade clauses changes the DDL, so
`store_stamp.ddl_hash` moves and every persisted store is rebuilt cold on first open under the new
version. That is the existing mechanism for a schema change working as intended rather than a
migration this item skips, and it is worth paying once here rather than twice if the schema work
were split across two items.

## Implementation

Three phases, and the seams are real rather than bookkeeping: each lands a family group that can be
observed working while the others are untouched, and only the last needs the re-aggregation. The first
phase pays the `ddl_hash` store discard, so the later two are free.

**Phase one, the source-keyed families.** Add `jvm_declared_type_ref`'s missing edge; add
`ON DELETE CASCADE` throughout the `sql_`, `jvm_` and `java_` webs so each family cascades from its
root; replace `clear`'s `jvm_` block and `clearSchemaSources`'s eighteen statements with one delete
per family per changed source; remove `PARTITIONED` and `wholesale()`. The four relations this item
was filed for are carried by the cascade with nothing naming them.

**Phase two, `java_` joins the registry.** Add the `JAVA_SOURCE` kind, give `java_file` a cascading
foreign key into `store_source`, and move its `stamp` and `read_at` up to the registry row so currency
is stated once. `JavaSourceFacts` is the only reader or writer of either column, so the move is
contained to one class; R922's comparison takes the instant as an argument and does not care which
relation it came from.

**Phase three, the SDL families.** Add `store_source.graph_name` with its foreign key and `CHECK`, and
the refusal a second graph meets; add the cascading `(graph_name, source_name)` foreign keys from the
16 `graphql_` and 40 `graphitron_` source-attributed relations into `store_graph_source`; write the
coordinate re-aggregation over the surviving declaration sites; reduce the graph-scoped clear to what
does not now cascade. Implementation confirms first that no fixture captures two graphs over one schema
file, the new rule being a refusal that an existing test could trip.

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
- **Cascade cost is measured, not assumed**: one delete of a jar's source row against the sakila
  fixture, reported as a row count and a duration beside the hand-written path it replaces, so a
  regression in refresh cost is visible here rather than discovered in a dev loop.

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

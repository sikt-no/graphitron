---
id: R872
title: "A refresh deletes the sources it re-read, and the database deletes the rest"
status: Spec
bucket: bug
priority: 2
theme: tooling
depends-on: []
created: 2026-08-28
last-updated: 2026-09-06
---

# A refresh deletes the sources it re-read, and the database deletes the rest

## Goal

Refreshing the fact store becomes one statement: delete the rows of the source registry naming the
inputs this round re-read. Everything those inputs produced goes with them, because the foreign keys
that already model the ownership say `ON DELETE CASCADE`. A *source* is one input the store read, a
`.graphqls` file, a jar, a generated jOOQ package or a `.java` file, each carrying a row in
`store_source`; a *partition* is the rows one source owns in a relation that several sources share.

What this removes is a mechanism, not a bug. Today a refresh empties every relation outright unless
that relation appears in `StoreRefresh.PARTITIONED`, a hand-maintained list of 21 exemptions; the
relations on the list are then deleted per source by eighteen hand-written statements spread across
three files, hand-ordered children before parents. Membership on the list is an unverified promise
that one of those statements exists somewhere else. When this lands there is no list, no exemption
polarity, no wholesale arm and no hand-ordered delete. Retention becomes the decision not to delete a
source row, and deleting everything downstream becomes the database's job.

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

Two roots, one for the sources a run reads and one for the family that keeps its own registry.

[cols="2,2,4"]
|===
| Root | Families | Why this root

| `store_source`
| `sql_`, `jvm_`, `graphql_`, `graphitron_`
| Every row is owned outright by the source it was read from. A jar's classes are the same classes
  whoever reads them, and a schema file belongs to one graph by the rule below, so in both cases
  deleting the source row can only remove facts that source produced.

| `java_file`
| `java_`
| The family carries its own registry keyed on the file, refreshed on the source-save cadence, and
  capture never writes it at all.
|===

### A schema file belongs to exactly one graph

This is a new rule, and it is what lets one root serve both the shared and the per-graph families.
Two graphs reading one `.graphqls` file would each hold their own transcription of it, so deleting
the file's source row would take both graphs' rows and reproduce this item's own bug in a new
costume. Rather than model the ownership as a `(graph, source)` pair to survive that case, the case
is refused: a schema file read by a second graph in one store is rejected, so the pair cannot arise.

The distinction is by source kind and not by policy, which is what makes it hold rather than merely
hold today. `store_source.source_kind` already separates `SCHEMA_FILE` from `JAR`, `DIRECTORY` and
`JOOQ_SCHEMA`. The latter three are dependencies, genuinely shared between the modules of a
workspace and stored once. A schema file is a project's own input, and two graphs sharing one is
unusual enough that forbidding it costs less than carrying a second ownership shape through every
relation of two families.

The rule wants to be visible in the schema rather than kept as a convention, so `store_source` gains
a nullable `graph_name` with a foreign key into `store_graph` and a
`CHECK ((graph_name IS NOT NULL) = (source_kind = 'SCHEMA_FILE'))`. What the column buys is precise
and worth stating without inflating it. `store_source` is keyed on `source_name` alone, so there was
never a second row for a second graph to create; sharing was implicit in a junction that admitted
both pairs, and nothing anywhere said which graph a schema file belonged to. With the column the
answer is single-valued and stated on the row, so a second graph claiming the file is a conflict
between a value and a value rather than a fact nobody recorded.

The refusal itself stays in capture, because a cross-row rule of this shape is not expressible as a
`CHECK` and H2 carries no filtered unique index to express it either. So the column does not make
the state unrepresentable, and the plan should not claim it does; it makes the state legible enough
that the refusal has something to compare against, and it puts the cascade's correctness on a column
a reader can see rather than on an invariant held elsewhere. The refusal names both graphs and the
file, and is a typed rejection like every other author-facing refusal.

`store_graph_source` stays as it is. It is the read spine every graph-partitioned relation joins,
used by `SourceGraph`, `StoreHandle`, `TableTypes` and `FieldEndpoints`, and it keeps covering the
kinds that really are shared. What changes is that it stops being asked to carry an ownership claim
it was never the right shape for.

### What cascades, and what has to be re-aggregated

Every relation of these five families is one of three things. The counts are derived from the DDL
rather than estimated.

[cols="3,1,5"]
|===
| Kind | Count | Treatment

| Source-attributed: carries `source_name` or `file`
| 81
| A foreign key into its root on `source_name`, or on `file` for the `java_` family, each
  `ON DELETE CASCADE`. 14 `sql_`, 7 `jvm_`, 4 `java_`, 16 `graphql_`, 40 `graphitron_`.

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
declares. After the moved sources are deleted and re-walked, the coordinate set is recomputed from the
surviving `graphql_type_declaration` and its kin, and coordinates with no remaining declaration are
deleted.

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
  is already a tree rooted at `java_file`.
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
registry at all, and the 40 source-attributed `graphitron_` relations are in the same position; all
56 gain a cascading foreign key on `source_name` into `store_source`.

## What the code loses

- `StoreRefresh.PARTITIONED`, `StoreRefresh.wholesale()`, and `clear`'s wholesale loop.
- `clear`'s seven hand-unrolled `jvm_` deletes, and the children-before-parents order they are
  hand-written in.
- The eleven per-source deletes and the two-loop structure in
  `CatalogFactCapture.clearSchemaSources`.
- `StoreRefresh.childrenFirst` for these families, the database owning the order instead.

Two things stay, named so they are not read as collateral. `freshSources` still decides which sources
this round re-read; that set becomes the argument to one delete rather than the scope of eighteen. And
the claim seeding in `prepare` is untouched, being an insert-side optimisation that stops a walk
rewriting a partition it is retaining, which is orthogonal to how deletion happens.

## What an existing store does at the upgrade

It is discarded once, deliberately. Adding foreign keys and cascade clauses changes the DDL, so
`store_stamp.ddl_hash` moves and every persisted store is rebuilt cold on first open under the new
version. That is the existing mechanism for a schema change working as intended rather than a
migration this item skips, and it is worth paying once here rather than twice if the schema work
were split across two items.

## Implementation

Two phases, and the seam is real rather than bookkeeping: the source-keyed families land and can be
observed working with the SDL families untouched, and only the second phase needs the re-aggregation.
The first phase pays the store discard, so the second is free.

**Phase one, the source-keyed families.** Add `jvm_declared_type_ref`'s missing edge; add
`ON DELETE CASCADE` to the existing roots, being `sql_schema`, `sql_table`, `sql_enum_binding`,
`sql_routine` and `jvm_class` into `store_source`, and the `java_` tree into `java_file`; replace
`clear`'s `jvm_` block and `clearSchemaSources`'s deletes with a delete of the source rows the round
re-read; remove `PARTITIONED` and `wholesale()`. The four relations this item was filed for are carried
by the cascade with nothing naming them.

**Phase two, the SDL families.** Add `store_source.graph_name` with its foreign key and `CHECK`, and
the refusal a second graph meets; add the cascading `source_name` foreign keys from the 16 `graphql_`
and 40 `graphitron_` source-attributed relations into `store_source`; write the coordinate
re-aggregation over the surviving declaration sites; reduce the graph-scoped clear to what does not now
cascade. Implementation confirms first that no fixture captures two graphs over one schema file, the
new rule being a refusal that an existing test could trip.

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

**Rooting the SDL families at `store_graph_source` instead.** The previous revision's design, which
carried the `(graph, source)` pair as the owner so that two graphs could share a schema file safely.
Rejected because it pays for a case we do not want: every relation of two families would carry a
composite ownership key to model sharing that is unusual in practice and that nothing needs. Refusing
the case costs one column and one `CHECK`, and leaves one root serving five families.

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

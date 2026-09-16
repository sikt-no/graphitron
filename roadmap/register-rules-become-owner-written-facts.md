---
id: R955
title: "The register empties bottom-up: every remaining registered rule becomes a fact the graphitron gatherer writes in stage order, and meta_materialize has no rows left"
status: Backlog
bucket: architecture
priority: 2
theme: model-cleanup
depends-on: [reference-step-walks-are-stored-rows]
created: 2026-09-16
last-updated: 2026-09-16
---

# The register empties bottom-up: every remaining registered rule becomes a fact the graphitron gatherer writes in stage order, and meta_materialize has no rows left

## Goal

A consumer's build computes every derived verdict the fact store answers with exactly once per
capture, written down by the gatherer that owns it, in the order that gatherer already runs its
stages; no view is refreshed into a table by a register, and no reader ever meets a rule that a
recursive view re-walks once per driving row. Today twenty-three such verdicts are *registrations*:
rows of `meta_materialize`, the register that keeps a rule in a view under a `_live` name and moves the
canonical name onto a table a refresh pass empties and refills after every gatherer has finished. R954
takes the eight of them under the `@reference` walks bottom-up into stage-written `graphitron_` tables
and shows the method works. This item takes the remaining fifteen the same way, so that when it lands
`meta_materialize` and `meta_materialize_dependency` hold no rows and are dropped, the `_live`
convention is gone, `Materializations.refresh` is called from nowhere, and a consumer's `graphitron:dev`
round and `generate` both pay each rule once at capture, per graph, on rows the same gatherer wrote a
statement earlier.

Four terms, glossed once. The *fact store* is the H2 database each generator pass captures the schema,
the jOOQ catalog and the classpath into, and answers its verdicts out of by SQL. A *gatherer* is one
pass that fills the store from one input; the `graphitron` gatherer is the one that runs last, after
the transcribing gatherers have flushed, as a sequence of *stages*, each an `INSERT ... SELECT` over
rows earlier stages and earlier gatherers wrote. The `intent_` *family* is the prefix under which
derived rules live today as views, 115 of them, and as the tables the register fills. The principle
this item applies, stated on the fact-model page and worked out at length in R876, is that a rule the
last gatherer can compute in a stage needs to be neither a view, nor a registration, nor a reader's
join: it is a fact that gatherer writes.

## Why the register exists, and why it stops needing to

The register exists to schedule refreshes for rules that have no owner to schedule them. Every one of
the twenty-three registered rules reads two or more captured families, so under the old reading none
of them belonged to any single gatherer, and a register standing outside every gatherer was the only
thing that could refresh them. R876 computed the owner every relation in the store has, taking a
view's owner to be the latest, in gatherer order, of the owners of what it reads, and every registered
target computed to `graphitron`. The register is therefore that gatherer's refresh plan, held in a
mechanism of its own because the gatherer did not yet run its stages in an order that could hold it.
It does now: `GraphitronFactCapture.capture` runs eight stages in an order its own comments justify,
the last being `FieldEndpoints.derive`, which reads `graphitron_`, `graphql_` and `sql_` relations,
joins and ranks across three families, and writes `graphitron_field_table`. That is the shape every
registration becomes, and nothing about it is new.

**Why the derivations were not captured earlier.** They could have been. Computed from the shipped
DDL: every one of the twenty-three registered rules, expanded through every `intent_` view it names
until only stored relations remain, bottoms out in captured facts of the `graphitron_`, `graphql_`,
`sql_`, `jvm_` and `store_` families plus at most four of the six hand-written `intent_` base tables,
which are themselves written by producers in Java before the refresh runs. Not one reads anything a
gatherer does not hold by the time the `graphitron` gatherer starts. The rules were not put in stages
because the model was built view-first: a verdict was stated as a view because a view was the cheapest
thing to write, the view was found slow, and the register was the one lever that did not require
deciding who owned it. The result is the shape R876 names, a pipeline whose intermediate results were
never written down, and it is why the `@nodeId` decode rule has to re-derive a `@reference` walk with a
recursive common table expression and two window functions once per driving row instead of joining a
table that holds the resolved path.

**The seam that blocked this dissolves bottom-up, and R954 shows how.** A stage may read a plain view
and may not read a registered target, because `FactCapture.capture` runs every hand-written producer
before `Materializations.refresh`, so a producer beside them would read the previous capture's rows.
Taken one relation at a time that blocked every conversion, and the register's own `reason` for
`intent_node_id_decode_column_live` records being blocked for exactly this. Taken bottom-up it blocks
nothing: convert the registration with no registration under it first, and every registration one rung
up then reads only captured facts, plain views and stage-written tables, so it converts with no seam.
No ordering mechanism and no successor to `meta_materialize_dependency` is needed. The order the stages
run in *is* the dependency order, chosen once by the person writing the stage, exactly as the eight
existing stages are ordered today.

## What is in scope

The fifteen registered rules R954 does not reach, arranged by the registrations they read. The rung of
a relation is one more than the highest rung it reads; R954's eight occupy rungs 0 to 5 and are treated
here as already stage-written tables. The read sets are computed from the shipped `_live` view bodies
and are for the implementer to recompute at pickup rather than trust.

[cols="1,4,5"]
|===
| rung | relation | reads, among the registrations

| 6 | `intent_field_column_scope` | `intent_resolved_type_binding`, and the field walk as a plain view
| 6 | `intent_argument_column_scope` | `intent_argument_reference_step_target`, `intent_argument_scope_table`
| 6 | `intent_mutation_write_payload` | `intent_field_scope_table`
| 6 | `intent_input_field_column_match` | none; reads `intent_input_field_column_scope`, a plain view over rung-5 relations
| 6 | `intent_node_id_instruction` | `intent_argument_reference_step_target`, `intent_argument_scope_table`, and the field walk as a plain view
| 6 | `intent_node_id_decode_hop` | `intent_argument_reference_step_target`, and the input-field walk as a plain view
| 7 | `intent_argument_column_match` | `intent_argument_column_scope`
| 7 | `intent_node_id_decode_hop_column` | `intent_node_id_decode_hop`
| 7 | `intent_input_field_filter_role` | `intent_input_field_column_match`, `intent_node_id_instruction`, and three rung-5 relations
| 8 | `intent_node_id_decode_column` | `intent_node_id_decode_hop_column`
| 9 | `intent_input_field_carrier_role` | `intent_input_field_filter_role`, `intent_node_id_decode_column`
| 10 | `intent_mutation_payload_refusal` | `intent_input_field_carrier_role`, `intent_input_field_filter_role`, `intent_mutation_write_payload`, `intent_input_field_resolving_table`
| 11 | `intent_mutation_payload_column` | `intent_mutation_payload_refusal` and five below it
| 12 | `intent_mutation_payload_key_membership` | `intent_mutation_payload_column`
| 13 | `intent_mutation_write_destination` | `intent_mutation_payload_column`, `intent_mutation_payload_key_membership`
|===

Three families of verdict, and they land in that order because the ladder says so: the column-scope
pair and the mutation write payload at rung 6, then the `@nodeId` decode chain from instruction through
hop, hop column and decode column, then the input-field roles and the mutation payload chain that reads
them. The mutation chain is the deepest thing in the store, twenty registrations below its top rung,
and it is the last to convert because everything it reads has to be a table first.

Also in scope, because they exist only to serve the register:

- `Materializations` (535 lines), `MaterializeDependencies` (261) and `RefreshProgress` (185) in
  `graphitron-model`, the boot-time derivation of `meta_materialize_dependency` from stored view
  definitions, and the `refresh`, `refreshAnalysing` and `refreshAll` call sites in `FactCapture`,
  `GraphitronModelStore`, `StoreRefresh` and `DevMojo`. `Materializations.analyse` stays if the
  stage-written tables want statistics after the gatherer writes them; that is a question for the
  first conversion to answer with a plan, not a prediction.
- `UnlowerableOrderingRejectionRows`, the one producer that runs *after* the refresh because it reads
  `intent_field_scope_table`. Once that relation is a stage-written table the producer is a stage like
  any other and moves into the gatherer's order.
- The `_live` naming convention and the 23 `COMMENT ON VIEW ... _live` blocks that explain it, which
  go with the views.

## Implementation

Bottom-up, one rung per commit or a few, each commit leaving the tree green and the register strictly
smaller. The shape of every conversion is the one R954's phase 1 sets and is restated here so this body
stands alone.

**One conversion.** The `_live` view's rule moves unchanged into a stage's `INSERT ... SELECT`, scoped to
the graph being captured. The canonical table takes a `graphitron_` name, keeps its indexes and gains a
`COMMENT ON INDEX` naming the reader each index serves where it lacks one. The `_live` view and the
`meta_materialize` row are deleted. The stage is placed in `GraphitronFactCapture.capture` after the
last stage it reads and before the first stage that reads it, and the placement comment says which two
those are, the way the existing eight do. Every Java reader that spelled `Tables.INTENT_X` spells
`Tables.GRAPHITRON_X`; the fifteen relations here have one such reader, `intent_node_id_instruction`'s,
so the rename cost is almost entirely inside the DDL and the tests.

**Convert or demote, per relation.** Where a registered rule's only reader is the stage directly above
it, demoting it to a plain view that stage evaluates inline is the smaller change: the registration was
buying rows-on-disk for many readers and buys nothing for one. Where it has several readers, it converts
to a stage. `DerivedReadCostTest`'s reader counts decide, and the commit records which way each went.
The decode chain is the case to watch: `intent_node_id_decode_hop_column` has one reader today and its
stated case was a recursive walk over rows the register filled; with the hop a table, whether the column
projection needs its own table or is a join the decode column's stage makes inline is an answer the
reader count gives, not a doctrine.

**A recursive rule becomes a fold.** Where a `_live` rule is a recursive common table expression, the
stage writes it as R954's phase 2 does: the seed is one insert, position k is an insert joining the rows
written at position k-1 to the step relation on the index that serves it, the loop stops when a pass
inserts no rows, and the bound is asserted from the walk's own entry relation so an edit that stops
terminating fails loudly. Aggregates that were window functions over the recursive term, such as
`last_position` on the decode hop, become one grouped `UPDATE` per graph after the fold. Of the fifteen,
none is recursive in its own body today; the recursion they pay for is in the walks under them, which
R954 folds. The rule is stated here so that the next recursive derivation anyone writes has a shape to
copy that is not a view.

**The graph partition is written, not filtered.** `Materializations.refreshPartition` issues
`INSERT INTO target SELECT * FROM source WHERE graph_name = ?`, and R954 measured that the predicate
cannot reach inside a recursive term, so a workspace store holding several subgraph modules paid every
module's rule at every module's refresh. A stage writes one graph's rows because it is running for one
graph. This is the one cost term a registration cannot reach and a conversion removes by construction,
and it is the reason the item is about `generate` and CI as much as about the dev round.

**The register's prose is retired with it, not edited.** Each `meta_materialize.reason` carries the
measurements that justified its row, several of them stale by R876's own audit. A conversion deletes
the row; the figures that still say something true about the rule's cost move to the table's
`COMMENT ON TABLE`, stated as facts about the rule and never as a comparison against a registration
that no longer exists. Neighbouring reasons stop being re-priced at each retirement because there stop
being neighbours; until the last row goes, each commit edits the reasons of surviving rows whose rules
read what it converted, which `meta_materialize.reason`'s own comment requires.

**The last commit drops the mechanism.** With no rows left: drop `meta_materialize` and
`meta_materialize_dependency` from the DDL, delete `Materializations`, `MaterializeDependencies` and
`RefreshProgress`, remove the refresh call and the empty-store exception path from `FactCapture` and
the `ModelCapture` order it is being replaced by, delete `MaterializeRegistryGateTest`,
`MaterializationOrderTest`, `MaterializationProgressTest`, `RefreshPlanStatisticsTest`,
`RefreshPrerequisiteStatisticsTest`, `WarmStartRefreshTest` where its subject was the refresh, and the
`derivation` row of `meta_gatherer` with its six `meta_gatherer_dependency` edges, per R876's "What each
mechanism becomes" table. `UnlowerableOrderingRejectionRows` moves into the stage order in the same
commit. `HAND_WRITTEN`'s impossibility criterion, which exists to tell a deliberate hand-written
`intent_` table from a bespoke materializer written beside the register, has nothing left to
discriminate and goes with the gate; the six tables it lists are the `graphitron` gatherer's producers
and get declared as such.

**What each stage owes on landing.** A `meta_relation` row naming `graphitron` as owner and stating the
grain, since the frozen undeclared roster only shrinks and a renamed relation is a new one to it. A
primary key where the grain admits one; R876's burn-down item 9 records that fifteen of the register's
targets carry no key at all and that nothing refuses a duplicate row in them, and a stage-written table
is the moment to fix that, because the writer is now code someone can read. An index for each reader
that seeks into it, with the comment the gate already requires.

## Tests

- **Answer preservation, per conversion.** `EXCEPT` in both directions between the stage-written table
  and the `_live` view text it replaced, over a populated store, per graph, before the view is deleted.
  This oracle is the whole reason the conversions are cheap to review, and it is available precisely
  because every one of these rules is expressible as a view.
- **The relation tests already pinning each verdict** at the coordinate grain pass with no edit beyond
  the rename. A test whose expectations move is a signal that a conversion moved an answer.
- **`FactCaptureAgreementTest`**, whose `REGISTRATIONS` map is the agreement surface between the two
  capture entry points, shrinks with the register and is deleted with it.
- **`DerivedReadCostTest`** prices every pair of a registration and a relation reaching its target. Every
  retirement moves its pinned set, which is the confrontation it exists to force, and when the register
  is empty the test's subject is gone; what replaces it is the rule bench R876 names under "No instrument
  for any of this lives in the repository", pricing a stage's statement against a captured store, and
  that is R899's instrument rather than this item's to build.
- **`MetaDeclarationGateTest`** binds on every converted relation's declaration, including the
  view-ownership gate, which is the mechanical check that a moved relation reads only what its owner may.
- **`CaptureCorpusIsolationTest`** covers a producer's reads, which no catalog parse can see, and is the
  gate that a stage reading a relation its gatherer is not declared downstream of fails.
- **Acceptance evidence for the goal**, which a green build does not supply: `SELECT COUNT(*) FROM
  meta_materialize` is not a query the store can answer, `grep -r "_live" graphitron-model/src/main`
  finds nothing, and the `sis` consumer's `graphitron:capture` pass is timed before and after on a copy of
  its store and written into the changelog entry.

## What this item does not do

- **It does not move the family-local misplacements.** Nine `intent_` relations compute to an owner that
  runs before `graphitron` and are R876's enumerated list; this item converts rules whose owner is
  `graphitron` and leaves the prefix on everything else, per R876's decision that the prefix stops
  naming an owner rather than being renamed away.
- **It does not settle per-gatherer transaction control.** The stages run inside the capture transaction
  as the eight existing ones do. Whether a gatherer should commit its family before deriving over it,
  which the fact-model page names as the prerequisite for statistics-aware refresh, is R876's and stays
  so; this item makes the question smaller by leaving one gatherer with one boundary.
- **It does not touch R857's or R872's refresh scoping.** Both would let a dev round skip stages the edit
  did not touch. A stage is a better unit for that than a registration, because its reads are in one
  method rather than derived from a view definition at boot, but making stages skippable is their work.
- **It does not build the rule bench.** R899 owns making a rule's cost countable from the tree.

## Relation to other items

**R954** is the first rungs of this ladder and this item depends on it in the front-matter: its eight
conversions are what make the fifteen here reachable without a seam, and its phase 2 is where the fold
shape is first written. If R954's phase-3 measurement splits its phases 4 and 5 into a successor, that
successor's four registrations sit between R954 and this item and are absorbed here rather than filed
twice.

**R876** is the doctrine and this is its burn-down item 8, the register, taken as an item of its own so
that R876's own body, already past four thousand lines, does not carry another sequenced arc. R876's
"What each mechanism becomes" table is this item's acceptance criterion row by row, and its finding that
"all twenty registered targets compute to `graphitron`" is what lets this item convert without a single
ownership judgement. What this item hands back: the ordering question R876 left open, "what orders two
relations under one owner", turns out to have the answer the gatherer already had, statement order in
one method, and `meta_materialize_dependency` needs no successor. R876's burn-down item 9, keying the
targets, is folded into each conversion here rather than left to the end.

**R899** prices one register row at a time and was reopened to Spec because R876 takes the register
away as the unit of account. This item is the removal; R899's instrument survives it as a bench over
stages rather than registrations, and R899 should be re-cut against that once this item is Ready.

**R942** fails the build on a rule that names an unregistered view once per driving row. With the
register gone, "unregistered" stops meaning anything and the gate's subject becomes a stage that names a
plain view on the inner side of a join; the detector's positions are unchanged, and R942 should say so in
its own body when it is next touched.

**R953** is a statistics cliff on one evaluation of the walk and is orthogonal: a `SELECTIVITY` on a
partition column is as true of a stage-written table as of a registered one.

**R877** declares grains and owners family by family. Each conversion here writes the `meta_relation`
row its relation owes, which is R877's kind of work done at the moment the relation is being rewritten
anyway, and R877's reconciliation of the fourteen `sql_` rows declared `catalog` while `JooqFactCapture`
writes them is unaffected.

## Other solutions we've considered

- **Leave the register and register more.** It is three DDL lines per rule and it lands the same rows.
  It is not the plan because it grows a mechanism that exists to compensate for a gatherer not having an
  order, when the gatherer has one; because it cannot write one graph's partition; and because its
  reasons are unchecked prose that R876's audit found stale by up to three orders of magnitude with
  nothing failing. R954's "Other solutions" makes the same case for its subtree and it holds for every
  rung above.
- **A producer inside the refresh order, declaring what it reads in a `meta_` relation.** The mechanism
  R954's first draft proposed and withdrew: a hand-kept ordering with no derivable source, which the
  fact-model page rules out by name. Bottom-up conversion makes it unnecessary.
- **Convert top-down, starting with the dearest rule.** The dearest rules are the deepest, so a top-down
  order meets the seam at every step and needs the mechanism above. The bottom-up order costs the
  cheapest conversions first and never meets the seam, which is why the ladder is ordered as it is.
- **One commit for all fifteen.** Fifteen relations across three verdict families and a hundred-relation
  closure at the top, with the mutation chain's tests as the regression surface. The per-rung order
  exists so a reviewer checks one `EXCEPT` at a time and so trunk stays green between rungs.
- **Rewrite the rules in Java rather than SQL.** A stage is an `INSERT ... SELECT`, so the rule stays
  stated once in SQL and what changes is who runs it and when. Hand-rolled Java resolution would restate
  each rule in a second language with no oracle to prove it against.

## Provenance

Filed 2026-09-16 as the follow-up R954's reviewer round and re-cut left implied: R954 found that its
27-relation closure bottoms out entirely in captured facts and converts bottom-up without an ordering
mechanism, and asked, in effect, why the same was not true of the rest of the register. It is. The
principle is R876's, stated on the fact-model page: a materialization is usually the price of a fact
nobody captured, and the `intent_` family is the shape a pipeline takes when its intermediate results
are not written down. This item writes them down.

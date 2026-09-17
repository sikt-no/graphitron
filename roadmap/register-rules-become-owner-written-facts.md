---
id: R955
title: "The register empties bottom-up: every remaining registered rule becomes a fact the graphitron gatherer writes in stage order, and meta_materialize has no rows left"
status: Ready
bucket: architecture
priority: 2
theme: model-cleanup
depends-on: [reference-step-walks-are-stored-rows]
created: 2026-09-16
last-updated: 2026-09-17
---

# The register empties bottom-up: every remaining registered rule becomes a fact the graphitron gatherer writes in stage order, and meta_materialize has no rows left

## Goal

A consumer's build computes each of the fact store's twenty-three registered verdicts exactly once per
capture, written down by the gatherer that owns it, in the order that gatherer already runs its
stages, and no view is refreshed into a table by a register. The `@nodeId` decode rule is the one among
them a recursive view re-walks once per driving row today, and it lands as a table its readers seek
into. Today those twenty-three verdicts are *registrations*: rows of
`meta_materialize`, the register that keeps a rule in a view under a `_live` name and moves the
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
mechanism of its own because nothing in the pass ran that gatherer's derivations as an ordered list.
The pass has such a list now: `FactCapture.capture` runs `GraphitronFactCapture.capture`'s eight
stages, flushes, and then runs five more producers in a fixed order before the refresh,
`ClassificationDomainCapture`, `InputOccurrencePaths`, `ArgMappingCandidates`, `TypeBackingRows` and
`AuthoredClaimRejectionRows`, each writing a table the next may read. Appending to that list is what
every conversion here does.

**What a stage is here, and what it is not.** The fact-model page states the shape under "Derived
reads are views, not stored facts": where a derivation must be paid once and stored, "the reduction is
an ordinary table populated `INSERT INTO derived SELECT ... FROM <view>`, which keeps the view as the
single statement of the rule while making reads a plain indexed scan". That is the stage this item
writes, and it is deliberately not `FieldEndpoints.derive`'s shape, whose rules are jOOQ expressions
over base tables. The rule stays a stored view, so its read set stays in the catalog where
`ViewReferences` and `MetaDeclarationGateTest.aDeclaredViewReadsOnlyWhatItsOwnerMay` can see it, the
`EXCEPT` oracle between rule and table stays runnable for as long as both exist rather than for one
commit, and nothing is restated in a second language. What goes is the register: the row that
scheduled the insert, the boot-time dependency derivation that ordered it, and the `_live` suffix whose
own comment says it names "the registration in `meta_materialize`".

**Why the derivations were not captured earlier.** They could have been. Computed from the shipped
DDL: every one of the twenty-three registered rules, expanded through every `intent_` view it names
until only stored relations remain, bottoms out in captured facts of the `graphitron_`, `graphql_`,
`sql_`, `jvm_` and `store_` families plus at most three of the six hand-written `intent_` base tables,
`intent_type_backing_class`, `intent_input_occurrence_path` and `intent_input_occurrence_path_step`,
which are themselves written by the producers above. Not one reads anything the pass does not hold
before the refresh runs. The rules were not put in stages because the model was built view-first: a
verdict was stated as a view because a view was the cheapest thing to write, the view was found slow,
and the register was the one lever that did not require deciding who owned it. The result is the shape
R876 names, a pipeline whose intermediate results were never written down, and it is why the `@nodeId`
decode rule has to re-derive a `@reference` walk with a recursive common table expression and two window
functions once per driving row instead of joining a table that holds the resolved path.

**The seam that blocked this dissolves bottom-up, and R954 shows how.** A stage may read a plain view
and may not read a table a *later* step of the pass writes, registered or hand-written, because it
would read the previous capture's rows; the register's own `reason` for
`intent_node_id_decode_column_live` records being blocked for exactly this. Taken one relation at a
time that blocked every conversion. Taken bottom-up it blocks nothing: convert the registration with
no registration under it first, and every registration one rung up then reads only captured facts,
plain views, tables the producers above wrote and tables earlier stages wrote, so it converts with no
seam. No successor to `meta_materialize_dependency` is needed to *find* the order, because the ladder
is it. What is still owed is an enforcer for the order once it is statement order in Java, and
"Implementation" names one built from the parse the register already has.

## What is in scope

The fifteen registered rules R954 does not reach. The *rung* of a relation is one more than the
highest rung of any registration its rule reads, directly or through plain views; a rule reading no
registration is rung 0. Computed from the shipped `_live` view bodies with SQL comments stripped, and
for the implementer to recompute at pickup rather than trust. This counts only registrations, where
R954's ladder counts the plain views between them as rungs of their own, so the same relation carries
a different number in the two bodies and neither is wrong; every rung number below is this item's.
R954's eight occupy rungs 0 to 6 under this counting and are listed in its own body; two of this
item's fifteen sit *inside* that range, which is stated below rather than smoothed over.

[cols="1,4,5,1,2"]
|===
| rung | relation | registered rules it reads (direct; then through plain views) | view readers | key today

| 3 | `intent_field_column_scope` | `intent_resolved_type_binding`; `intent_field_reference_step_hop` | 3 | primary key
| 5 | `intent_mutation_write_payload` | `intent_field_scope_table` | 4 | none
| 7 | `intent_argument_column_scope` | `intent_argument_reference_step_target`, `intent_argument_scope_table` | 1 | primary key
| 7 | `intent_input_field_column_match` | none direct; `intent_input_field_resolving_table`, `intent_field_reference_step_hop` through `intent_input_field_column_scope` | 2 | one index
| 7 | `intent_node_id_instruction` | `intent_argument_reference_step_target`, `intent_argument_scope_table`; `intent_resolved_type_binding`, `intent_field_reference_step_hop` | 8, and one Java reader | one index
| 8 | `intent_argument_column_match` | `intent_argument_column_scope` | 1 | none
| 8 | `intent_input_field_filter_role` | `intent_input_field_column_match`, `intent_node_id_instruction`, and three of R954's | 4 | one index
| 8 | `intent_node_id_decode_hop` | `intent_argument_reference_step_target`; `intent_node_id_instruction` and three of R954's | 2 | primary key
| 9 | `intent_node_id_decode_hop_column` | `intent_node_id_decode_hop` | 1 | one index
| 10 | `intent_node_id_decode_column` | `intent_node_id_decode_hop_column`; `intent_node_id_instruction` | 4 | none
| 11 | `intent_input_field_carrier_role` | `intent_input_field_filter_role`, `intent_node_id_decode_column` | 2 | none
| 12 | `intent_mutation_payload_refusal` | `intent_input_field_carrier_role`, `intent_input_field_filter_role`, `intent_mutation_write_payload`, `intent_input_field_resolving_table` | 2 | one index
| 13 | `intent_mutation_payload_column` | `intent_mutation_payload_refusal` and five below it | 3 | none
| 14 | `intent_mutation_payload_key_membership` | `intent_mutation_payload_column` | 2 | none
| 15 | `intent_mutation_write_destination` | `intent_mutation_payload_column`, `intent_mutation_payload_key_membership` | 1 | one index
|===

**The two rungs inside R954's range.** `intent_field_column_scope` reads no registration above
`intent_resolved_type_binding`, and `intent_mutation_write_payload` none above `intent_field_scope_table`,
so both are convertible before R954 finishes, and the `depends-on:` edge is on R954's phases rather
than on its Done: this item's first commit can land once `intent_resolved_type_binding` and the hop are
tables, its second once `intent_field_scope_table` is. The other thirteen wait for the top of R954's
ladder, and the mutation write chain at the top is the deepest thing in the store, twenty registrations
below it in the transitive closure, so it converts last because everything it reads has to be a table
first.

**Three verdict families, landing in ladder order.** The column-scope pair and the mutation write
payload; then the `@nodeId` decode chain, instruction through hop, hop column and decode column; then
the input-field roles and the mutation payload chain that reads them. The decode chain is where the
`@nodeId` decode rule pays for a `@reference` walk today, and `intent_node_id_decode_column_live` is
itself recursive: its `lifted` term walks `intent_node_id_decode_hop_column` position by position to
carry the local column name along the chain. It is the one rule among the fifteen whose stage inserts
from a recursive view, which "Implementation" prices.

**Also in scope, because they exist only to serve the register:**

- `Materializations` (535 lines) and `RefreshProgress` (185) in `graphitron-model`, and the four call
  sites: `FactCapture.capture`'s refresh and its empty-store cadence, `GraphitronModelStore`'s
  boot-time `MaterializeDependencies.populate`, `StoreRefresh`, and `DevMojo`'s `refreshAll` at
  session start. `StoreRefresh` is a read of the register rather than a call into it, and it is
  executable rather than prose: its warm-pass clear queries `meta_materialize` to decide what not to
  empty. "The last commit" says what replaces that query.
  `MaterializeDependencies` (261 lines) is not deleted: its parse of stored view
  definitions into read sets is the derivable source the stage order's gate needs, and it is repointed
  rather than retired. `ModelCapture.capture`, the entry point R876 is moving the run onto, calls no
  refresh today; the stages land in `FactCapture`'s derivation stratum, which is the part of the pass
  R876's own plan carries across.
- `UnlowerableOrderingRejectionRows`, the one producer that runs *after* the refresh because it reads
  `intent_field_scope_table`. Once that relation is a stage-written table the producer is a stage like
  any other and moves into the order.
- The `_live` naming convention and the 23 `COMMENT ON VIEW ... _live` blocks that explain it, which
  are rewritten with the rename.
- `Materializations.analyse` and the two statistics cadences `FactCapture.capture` keeps, which survive
  the register with a new roster; "Implementation" says how.

**Out of this item's fifteen but in the last commit's way: the ten relations `meta_relation` declares
under the `derivation` gatherer.** `lint_violation` and nine `intent_` views: `intent_scalar_java_type`,
`intent_condition_slot`, `intent_condition_context_parameter`, `intent_reference_for_application`,
`intent_field_unlowerable_ordering`, `intent_field_unlowerable_ordering_rejection`,
`intent_field_reference_step_fanout`, `intent_external_field_contract_defect` and
`intent_node_id_decode_landing_defect`. None is registered. `meta_relation.owner_name` is a foreign key
to `meta_gatherer`, so the `derivation` row cannot go while any of them names it, and R876's finding that
every *registered target* computes to `graphitron` says nothing about these ten. They are enumerated
here so the last commit's claim is honest about its precondition; "The last commit" says what happens to
each.

## Implementation

Bottom-up, one rung per commit or a few, each commit leaving the tree green and the register strictly
smaller. Everything below is stated so this body stands alone; where it copies R954, it says so.

**One conversion.** Three DDL edits and one Java line. The `_live` view keeps its text and is renamed
`graphitron_<x>_rule`, its comment rewritten to say it is the rule the stage inserts from. The table
takes `graphitron_<x>`, keeps its indexes, gains a `COMMENT ON INDEX` naming the reader each serves
where it lacks one, and gains a primary key where the grain admits one. The `meta_materialize` row is
deleted. The stage is one statement, `INSERT INTO graphitron_<x> SELECT * FROM graphitron_<x>_rule
WHERE graph_name = ?`, preceded by the graph-scoped `DELETE` the refresh issues today, placed in
`FactCapture.capture`'s derivation stratum after the last step it reads and before the first that reads
it, with a placement comment naming both. Every reader spelling the old name spells the new one: in
the DDL that is each view body counted in the table above, in Java it is one site,
`Tables.INTENT_NODE_ID_INSTRUCTION`, the only one of the fifteen any main source names.

**Where the stages run, and why that position is current for every input.** All fifteen bottom out in
at least one hand-written table except `intent_field_column_scope`, and `FactCapture.capture` writes
those tables *after* `GraphitronFactCapture.capture` returns. So the stages go after the five producers
in the derivation stratum, in ladder order, not inside the gatherer's eight stages: the position
`ArgMappingCandidates.derive` already occupies, and the one R954's round-2 review names for its own
rungs 4 and 5. One class per verdict family under `derive/`, each exposing `derive(dsl, graphName)` on
that precedent, called from the stratum in the order the ladder gives. The invariant every placement
satisfies: **no stage reads a table a later step of the pass writes, registered or hand-written.**
Whether the gatherer's eight stages, the five producers and these fifteen become one list under one
class is R876's "The capture layer dissolves into one linear read" and is not reorganised here; this
item appends to the stratum.

**The order gets an enforcer, built from the parse the register already has.** Statement order in one
method is a hand-kept ordering, and the fact-model page's objection to hand-kept orderings is that
they have no derivable source. This one has one: each stage inserts from a stored rule view, and
`MaterializeDependencies` already parses stored view definitions into read sets with `ViewReferences`.
A gate, `StageOrderGateTest` in `graphitron-model`, reads the stratum's stage list in order, resolves
each stage's rule view to the `graphitron_` tables it reads transitively through plain views, and fails
the build when a stage reads a table a later stage or producer writes. The hand-written producers are
jOOQ code the parse cannot see, so their write sets are declared to the gate by the same equality-pinned
roster `HAND_WRITTEN` uses today, which is what that roster becomes. This is the "admissible version"
R954's own "Other solutions" names, an ordering derived from the producer's source and gated on drift,
and it is what answers R876's open question rather than statement order alone.

**Convert or demote, per relation, on three terms.** Where a registered rule has one reader and that
reader is a stage this item writes, the table can go and the stage read the rule view inline: the
registration was buying rows on disk for many readers and buys nothing for one statement. Three terms
decide, recorded per relation in the commit: view-body readers, counted from the DDL as in the table
above; Java and stage readers, enumerated by hand once readers are stages the parse cannot count; and
the statement size of the reader before and after the demote, both figures read off
`report-inline-multiplicity`'s ranking and written into the commit beside the other two terms. The
third term is a judgement rather than a threshold, and deliberately so: that tool reports and does not
gate, its own javadoc declining to name a ceiling until "a few reductions give that number a basis",
and this item is one of those reductions rather than the pass that sets the number. What the figure is
read against is the fact-model page's warning that past some size a stored table "is not buying speed,
it is buying a plan existing", priced there at 963 for the schema as it ships against 2739455 with
every registration demoted, two relations at the top of that range exhausting a four-gigabyte heap
while still parsing. A demote whose reader lands anywhere near that upper range is refused whatever
the reader count says, and the commit says which way it went and on what number. The term bites
hardest at the top of this ladder, where the mutation chain's closure is a hundred relations.
Worth knowing before reading the figure: the shipped ranking's own heaviest relation prints as 141
rather than 963, so the page and the tool do not currently agree on the scale, and the implementer
compares a demote's before and after against each other rather than against either published figure.
By the first term alone three
are candidates: `intent_argument_column_scope`, read only by `intent_argument_column_match`'s rule;
`intent_node_id_decode_hop_column`, read only by `intent_node_id_decode_column`'s; and
`intent_mutation_write_destination`, read only by the plain view `intent_mutation_write_agreement`,
which is a consumer read and therefore converts unless that view is itself made a stage.
`intent_argument_column_match`'s one reader, `intent_argument_filter_role`, is a plain view with readers
of its own, so it converts.

**The recursive rule keeps its recursion in the view.** `intent_node_id_decode_column`'s rule is the one
of the fifteen with a `WITH RECURSIVE`, its `lifted` term walking `intent_node_id_decode_hop_column`
position by position. It converts like the others, the recursion staying inside the rule view and the
stage inserting from it, and a Java fixpoint loop is not written: a `UNION` recursion reaches a fixpoint
on its own, the tree's one Java loop, `ClassificationDomainCapture`, is admitted on an impossibility
argument this rule cannot make, and R954's round-2 review makes the same point about its phase 2. One
cost follows and is stated rather than hidden. R954 measured that an outer `graph_name = ?` cannot prune
inside a recursive term, so this stage's insert evaluates the recursion over every graph in the store
and keeps one graph's rows, which is the register's own cost for this rule today and no worse. Whether
the seed should carry the graph predicate inside the statement, which a stored view cannot be handed
and a SQL string in the stage can, is the fork R954's phase 2 is settling for its walks now; this item
copies whatever shape R954 lands for the one rule here, and says so rather than picking twice.

**The graph partition is written, not filtered, for the fourteen non-recursive rules.**
`Materializations.refreshPartition` issues `INSERT INTO target SELECT * FROM source WHERE graph_name = ?`,
and for a non-recursive rule view H2 pushes that predicate into the view, so the fourteen already pay
one graph's evaluation and keep doing so as stages. The per-graph term R954 measured on its walks, 170.9
ms against 22.6 ms on a three-graph store, belongs to recursive rules and is the paragraph above's.

**Statistics keep both cadences; only the roster changes.** `FactCapture.capture` has two today, and
the fact-model page prices why: a warm store refreshes inside the capture transaction and runs
`Materializations.analyse` after it commits, H2's `ANALYZE` committing; a store holding no graph runs
`refreshAnalysing` outside the transaction, one committed step per registration, analysing each target
before the next plans against it, and one cold refresh prefix measured 6293 s against 90.8 s with that
cadence. Stages inside one transaction on a cold store would reproduce the 6293 s case rung by rung. So
both cadences carry across, keyed to stages: warm, the stratum runs inside the transaction and one
`ANALYZE TABLE` pass follows over every stage-written table; cold, each stage commits and analyses its
table before the next runs, exactly as `refreshAnalysing` does per registration. The roster the analyse
walks is no longer `meta_materialize` but `meta_relation` where `owner_name = 'graphitron'` and the
relation is a table, which each conversion's declaration adds its relation to. This is the one piece of
`Materializations` that survives, renamed for what it does, and `RefreshPrerequisiteStatisticsTest`'s
claim, that every derivation meets the tables its rule reads analysed, keeps a successor with the same
assertion over stages. R953's lever 2, a static `SELECTIVITY 1` on each stage-written table's
`graph_name`, is the cheap floor under both cadences and lands per table as R953 specifies.

**Progress lines move with the statements.** `RefreshProgress` prints one line per registration before
its statements and one after, at debug, and `dev-loop-internals.adoc` teaches a consumer to find a
stuck relation by the last line with no `done in` under it. A stage order owes the same instrument or
that recipe dies with the register: each stage reports its name before its statements and its row
count and duration after, on the same logger tier, and the how-to is rewritten to grep for stage lines
instead of `n/20` lines.

**The register's prose is retired with it, not edited.** Each `meta_materialize.reason` carries the
measurements that justified its row, several of them stale by R876's own audit. A conversion deletes
the row; a figure that still says something true about the rule's cost moves to the table's
`COMMENT ON TABLE`, stated as a fact about the rule and never as a comparison against a registration
that no longer exists. Until the last row goes, each commit edits the reasons of surviving rows whose
rules read what it converted, which `meta_materialize.reason`'s own comment requires; after it, there
are no neighbours to re-price.

**What each stage owes on landing.** A `meta_relation` row naming `graphitron` as owner and stating the
grain, since the frozen undeclared roster only shrinks and a renamed relation is a new one to it. A
primary key where the grain admits one: twelve of the fifteen carry none today, only
`intent_field_column_scope`, `intent_argument_column_scope` and `intent_node_id_decode_hop` having one,
and R876's burn-down item 9
records that nothing refuses a duplicate row in them, and a stage-written table is the moment to fix
that; where the grain includes a meaningfully nullable column the table is indexed instead and the
comment says why, on `MaterializeRegistryGateTest.everyTargetIsIndexedOrStatesWhyNot`'s existing
argument. An index for each reader that seeks into it, with the comment the gate already requires. And
a line in the stage order's gate roster where the writer is jOOQ code rather than a rule view.

**The last commit drops the mechanism.** With no rows left:

- Drop `meta_materialize` and `meta_materialize_dependency` from the DDL. Delete `Materializations`
  except the analyse loop above, and `RefreshProgress`. Remove the refresh call and the empty-store
  cadence from `FactCapture.capture`, replacing both with the stage cadences above; remove the
  dependency population from `GraphitronModelStore`'s boot; remove `DevMojo`'s `refreshAll` at session
  start, whose own comment says it exists because "a warm partition whose capture was skipped because
  nothing changed refreshes nothing of its own", and a stage-written table holds the previous capture's
  rows in exactly that case, the clear below running only when a capture does.
- **Repoint `StoreRefresh`'s exemption, which is code and not a comment.** Its warm-pass graph-scoped
  clear subtracts `refilled(dsl)`, a method whose whole body selects `meta_materialize.target_table_name`,
  so that a relation whose owner empties it itself is not emptied twice per pass with nothing writing it
  in between. Dropping the register takes that method's only source away, and every converted relation
  declares `graphitron` as its owner in `meta_relation`, so without a replacement the clear starts
  covering exactly the tables the stages are about to delete and refill, which that comment measures at
  24 ms over 2780 rows on the sakila example and expects to be a consumer store's largest relations.
  The replacement is the stage roster the order gate and the analyse loop already need: the exemption
  keeps its meaning and its measured argument, and only where it reads the list from changes. The
  method is renamed for what it now names and its comment rewritten with it. Its foreign-key safety
  argument carries across unchanged, the converted relations being the same tables under new names,
  each declaring one foreign key and it to `store_graph`, which this clear never deletes. Making
  `graphitron` a `SELF_SWEEPING` gatherer instead is the wrong shape here and is not done, and the
  reason is worth stating because the name reads as though it fits. That set is read from the declared
  owner, and the sixteen relations declaring `graphitron` today are the base tables the gatherer's own
  eight stages write, from `graphitron_element` through `graphitron_argmapping_candidate`. None of them
  empties its partition first; this clear is what empties them, which is what it means by still
  covering 205 relations. Declaring the gatherer self-sweeping would stop the clear reaching those
  sixteen while nothing else empties them, and the set only grows as R877 declares more. The exemption
  belongs to the relations a stage refills, which is the roster, not to the gatherer.
- Move `UnlowerableOrderingRejectionRows` into the stage order.
- Delete `MaterializeRegistryGateTest`, `MaterializationOrderTest`, `MaterializationProgressTest`,
  `RefreshPlanStatisticsTest`, `UnregisteredRelationTest`, `CandidateCutSetTest` and the
  `CandidateCutSet`, `RefreshStages` and `UnregisteredRelation` test helpers, all of which take a
  registration as their subject. `RefreshPrerequisiteStatisticsTest` is rewritten over stages, above.
  `DetectionReadReachGateTest` and `WarmStartRefreshTest` keep their subjects and lose the arms that
  enumerate registrations.
- `HAND_WRITTEN` becomes the stage order gate's write-set roster for jOOQ-written tables, above. Its
  impossibility criterion, which told a deliberate hand-written derivation from a bespoke materializer
  written beside the register, is replaced by the criterion the gate enforces: every base table under
  a derived family's prefix is written by a step the order names, and a base table no step writes fails
  the build. The six tables it lists are declared by their actual owner per relation, not blanket:
  `intent_type_domain` is the SDL gatherer's, as the fact-model page and `ClassificationDomainCapture`'s
  own javadoc say; the other five are written by producers in the stratum and are declared `graphitron`.
- The ten `derivation`-declared relations are re-owned one by one. Each of the nine `intent_` views is
  declared with the owner R876's rule computes for it, checked by
  `MetaDeclarationGateTest.aDeclaredViewReadsOnlyWhatItsOwnerMay` against that owner's declared
  dependency set; one whose reads that set does not admit stays `derivation` and is named in the
  changelog entry. `lint_violation` is written by the lint engine, not by any stage here, and is R876's
  to move to a `lint` gatherer per its 2026-09-16 chapter; it stays `derivation` until then. So the
  `derivation` row of `meta_gatherer` and its eight `meta_gatherer_dependency` edges go in this item
  only if all ten have left it, and otherwise go with whichever item moves the last one. The
  mechanism's deletion does not wait on that.
- Rewrite the contributor-facing surfaces that describe the register as a mechanism:
  `fact-model.adoc`'s "Ownership" section, whose paragraph on what `meta_materialize` is becomes a
  paragraph on the stage order and its gate; the same page's lever order under "Derived reads are
  views, not stored facts", whose fourth rung, "a registration", becomes "its owner stores it in a
  stage", with one added sentence saying what a rule that is right as a view and too slow now does,
  which also retires `meta_materialize.reason`'s distinction between "too slow" and "no view could
  state it"; that page's paragraphs on `DerivedReadCostTest`, the two statistics tests and the refresh
  observer, rewritten for their successors; `pipeline-overview.adoc`'s line naming the capture-cadence
  materializations; and `dev-loop-internals.adoc`'s stuck-refresh recipe, rewritten around stage lines.
  The `store-performance` skill names the register in seven places and is corrected in the same
  commit; it sits outside the citation guard but not outside being true.
- The retirement sweep runs at the Done gate over the vocabulary declared below, and only what survives
  it graduates into `RetiredVocabularyGuardTest`'s registry. Blanket graduation is not the move, for two
  reasons that both come from the guard itself. Its entry bar is demonstrated recurrence, "a term enters
  the registry when an audit finds it surviving a cleanup, not at every rename", which is the escalation
  step `roadmap/workflow.adoc` describes rather than a deliverable a plan can schedule. And the
  mechanism cannot hold the generic half of the list whatever the bar: a token entry matches a whole
  identifier over the Java identifier character class, and outside the three classes this item deletes,
  the main sources spell `registration` 155 times, `registered` 186 and `register` 106, almost all of it
  the unrelated and entirely live data-fetcher sense in the generators, so registering any of those
  fails `noRegisteredTokenIsALiveMainSourceName` on arrival. `_live` cannot be a token entry at all,
  being a suffix rather than a token: `intent_node_id_instruction_live` is one identifier. So the
  candidates a sweep could plausibly graduate are the unambiguous names, `meta_materialize`,
  `meta_materialize_dependency`, `Materializations`, `RefreshProgress`, `refreshPartition`,
  `refreshWhole`, `refreshAll`, `refreshAnalysing`, `REGISTRATIONS` and `REFRESH_STAGES`, and the
  generic terms are left to the sweep's own grep. What holds the relation names against regrowth needs
  no registry entry either way: `FactSchemaGateTest`'s frozen roster does it, and the `_live`
  convention leaves the tree with the last view that carries the suffix.

## Tests

- **Answer preservation, per conversion.** `EXCEPT` in both directions between the stage-written table
  and its rule view, per graph, over a populated store. Because the rule view survives the conversion,
  this is a standing assertion rather than a one-commit check: a test in `graphitron`'s pipeline tier
  runs it over every stage-written table on the captured fixture store, and it is the successor to the
  agreement `FactCaptureAgreementTest.REGISTRATIONS` pins today.
- **The relation tests already pinning each verdict** at the coordinate grain pass with no edit beyond
  the rename. A test whose expectations move is a signal that a conversion moved an answer.
- **`StageOrderGateTest`**, new, above: the stratum's order against each stage's parsed read set and the
  producers' declared write sets, failing on a stage that reads a table a later step writes. It binds
  from the first conversion, not the last.
- **`DerivedReadCostTest`** prices every pair of a registration and a relation reaching its target. Every
  retirement moves its pinned set, which is the confrontation it exists to force; when the register is
  empty its subject is gone and it is deleted with the mechanism. What replaces it is the rule bench
  R876 names under "No instrument for any of this lives in the repository", pricing a stage's statement
  against a captured store, and that is R899's instrument rather than this item's to build.
- **`RefreshPrerequisiteStatisticsTest`'s successor**: on a store holding no graph, every stage's insert
  plans against analysed tables for every table its rule reads, asserted the way the test asserts it
  today over registrations.
- **`MetaDeclarationGateTest`** binds on every converted relation's declaration, including the
  view-ownership gate, which is the mechanical check that a moved rule view reads only what its owner
  may, and on each of the ten re-owned relations.
- **Acceptance evidence for the goal**, which a green build does not supply: the `sis` consumer's
  `graphitron:capture` pass timed before and after on a copy of its store, warm and cold, written into
  the changelog entry beside the per-stage lines that replaced the per-registration ones.

## Retired vocabulary

Mechanism names, gone with the last commit: `meta_materialize`, `meta_materialize_dependency`, the
`_live` suffix and "the `_live` view", "registration", "registered target", "the register",
"materializer", "materialization refresh", "refresh pass", "refresh order", "refresh stage",
`Materializations` (as a class; the analyse loop is renamed), `RefreshProgress`, `refreshPartition`,
`refreshWhole`, `refreshAll`, `refreshAnalysing`, `REGISTRATIONS`, `REFRESH_STAGES`, `HAND_WRITTEN`
(as a name; the roster is repurposed under the gate's name), and "hand-written derivation" as a
category distinct from a stage.

Relation names, renamed by the move: the fifteen `intent_` names in the table above, each to its
`graphitron_` successor and its `_rule` view, and R954's eight where R954 has not already swept them.

## What this item does not do

- **It does not restate any rule in Java.** Every stage inserts from a stored rule view. The one open
  shape question, a graph predicate inside a recursive seed, is R954's to settle and this item copies
  its answer.
- **It does not move the family-local misplacements.** Nine `intent_` relations compute to an owner that
  runs before `graphitron` and are R876's enumerated list; this item converts rules whose owner is
  `graphitron` and leaves the prefix on everything else, per R876's decision that the prefix stops
  naming an owner rather than being renamed away.
- **It does not merge the gatherer's stages, the producers and these stages into one class.** That is
  R876's linear-read target; this item appends to the stratum in the order the ladder gives.
- **It does not settle per-gatherer transaction control.** Both statistics cadences carry across as they
  are; whether a gatherer should commit its family before deriving over it stays R876's.
- **It does not touch R857's or R872's refresh scoping.** Both would let a dev round skip stages the edit
  did not touch. A stage is a better unit for that than a registration, because its read set is one
  rule view rather than a boot-time derivation, but making stages skippable is their work.
- **It does not build the rule bench.** R899 owns making a rule's cost countable from the tree.
- **It does not mint a `lint` gatherer.** R876 has designed one; `lint_violation` waits for it.

## Relation to other items

**R954** is the bottom of this ladder and this item depends on it in the front-matter. Its eight
conversions are what make thirteen of the fifteen here reachable without a seam, and two of this
item's rungs become convertible part-way through it, as "What is in scope" states. Its round-2 review
found two things this body takes as settled: that rungs reaching a hand-written table run after the
producers, which is where every stage here runs, and that a recursive rule stays a single SQL
statement rather than a Java loop. If its phase-3 measurement splits its phases 4 and 5 into a
successor, that successor's four registrations sit between R954 and this item and are absorbed here
rather than filed twice.

**R876** is the doctrine and this is its burn-down item 8, the register, taken as an item of its own so
that R876's own body does not carry another sequenced arc. R876's "What each mechanism becomes" table
is this item's acceptance criterion row by row, with two amendments this body argues:
`meta_materialize_dependency`'s *parse* survives as the stage order's gate, and the `derivation` row
goes when its last declared relation leaves rather than with the register. Its finding that "all
twenty registered targets compute to `graphitron`" is what lets this item convert without a single
ownership judgement, and its silence about the ten declared `derivation` relations is why the last
commit has to make ten. What this item hands back on R876's open question, "what orders two relations
under one owner": the ordering constraint is satisfiable by construction bottom-up, statement order in
one method realises it, and the derivable source the page demands is the rule view's stored definition
read by a gate. R876's burn-down item 9, keying the targets, is folded into each conversion here.

**R899** prices one register row at a time and was reopened to Spec because R876 takes the register
away as the unit of account. This item is the removal; R899's instrument survives it as a bench over
stages rather than registrations, and R899 should be re-cut against that once this item is Ready.

**R942** fails the build on a rule that names an unregistered view once per driving row. With the
register gone, "unregistered" stops meaning anything and the gate's subject becomes a rule view named
on the inner side of a join by another rule view; the detector's positions are unchanged, and R942
should say so in its own body when it is next touched.

**R953** is a statistics cliff on one evaluation of the walk and is orthogonal: its lever 2, a
`SELECTIVITY` on a partition column, is the floor under both statistics cadences here and lands per
stage-written table.

**R877** declares grains and owners family by family. Each conversion here writes the `meta_relation`
row its relation owes, which is R877's kind of work done at the moment the relation is being rewritten
anyway, and the ten `derivation` re-declarations are the same work on relations this item does not
otherwise touch.

## Other solutions we've considered

- **Leave the register and register more.** It is three DDL lines per rule and it lands the same rows.
  It is not the plan because it grows a mechanism that exists to compensate for a gatherer not having an
  order, when the pass has one; because its ordering is derived at boot from a register rather than
  checked at build from the rule; and because its reasons are unchecked prose that R876's audit found
  stale by up to three orders of magnitude with nothing failing. R954's "Other solutions" makes the same
  case for its subtree and it holds for every rung above.
- **Move each rule's text into the stage as a SQL string and delete the view.** This is the Backlog
  draft of this item and R954's phase 1 as written. It takes the rule's read set out of the catalog,
  where `ViewReferences`, the ownership gate and the order gate all read it, and makes the `EXCEPT`
  oracle a one-commit artifact. The fact-model page's stored form keeps the view for exactly these
  reasons. The one place the string form buys something a view cannot, a graph predicate inside a
  recursive seed, is stated under "The recursive rule keeps its recursion in the view".
- **A `meta_` relation declaring what each producer reads.** A hand-kept ordering with no derivable
  source, which the fact-model page rules out by name. The gate above derives the read set from the rule
  view instead, and only the jOOQ-written producers need a declared write set.
- **Convert top-down, starting with the dearest rule.** The dearest rules are the deepest, so a top-down
  order meets the seam at every step. The bottom-up order costs the cheapest conversions first and never
  meets the seam.
- **One commit for all fifteen.** Fifteen relations across three verdict families and a hundred-relation
  closure at the top, with the mutation chain's tests as the regression surface. The per-rung order
  exists so a reviewer checks one `EXCEPT` at a time and so trunk stays green between rungs.
- **Rewrite the rules in Java rather than SQL.** A stage inserts from a view, so the rule stays stated
  once in SQL and what changes is who runs it and when. Hand-rolled Java resolution would restate each
  rule in a second language with no oracle to prove it against.
- **Drop the `derivation` gatherer with the register.** Ten declared relations name it and the foreign
  key refuses. Re-owning them is ten judgements, not one, and one of them is a gatherer R876 has
  designed and not yet landed.

## Provenance

Filed 2026-09-16 as the follow-up R954's reviewer round and re-cut left implied: R954 found that its
27-relation closure bottoms out entirely in captured facts and converts bottom-up without an ordering
mechanism, and asked, in effect, why the same was not true of the rest of the register. It is. The
principle is R876's, stated on the fact-model page: a materialization is usually the price of a fact
nobody captured, and the `intent_` family is the shape a pipeline takes when its intermediate results
are not written down. This item writes them down.

## Reviewer findings

### Round 1 (2026-09-17, Spec -> Ready, reviewer session 01Kc43YJCDD7SrJp3kXLiRxb)

Verdict: withhold, on four findings. None of them touches the ladder or the method, which are right
and were checked rather than taken on trust; three are the plan naming an instrument that will not
do what the plan asks of it, and one is the goal paragraph claiming outcomes the item does not
deliver. All four are a sentence or a bullet each.

**What was recomputed from the tree and holds.** This is stated first because the body's factual
density is unusual and a later reviewer should not spend the passes again. `meta_materialize` holds
exactly 23 rows. The rung of every one of the fifteen was recomputed from the shipped `_live` bodies
with comments stripped, expanding through plain views, and every rung in the scope table is right,
3 and 5 through 15 as printed, with R954's eight occupying 0 through 6 under the same definition and
the fifteen plus the eight partitioning the register exactly. Every `view readers` count is right,
all fifteen of them. The three demote candidates are the three relations with one view reader and no
other, and `intent_mutation_write_destination`'s one reader is the plain view
`intent_mutation_write_agreement`. The `key today` column is right in all fifteen rows. The leaf
closure of the fifteen bottoms out in `graphitron_`, `graphql_` and `sql_` facts plus
`intent_input_occurrence_path` and `intent_input_occurrence_path_step` and nothing else, so no stage
reads `intent_type_domain`, `intent_authored_claim_rejection` or
`intent_field_unlowerable_ordering_rejection` and moving `UnlowerableOrderingRejectionRows` into the
order closes no cycle. `FactCapture.capture` runs the eight stages, flushes, then
`ClassificationDomainCapture`, `InputOccurrencePaths`, `ArgMappingCandidates`, `TypeBackingRows` and
`AuthoredClaimRejectionRows`, then the refresh, then `analyse`, then the rejection rows, in that
order. `Materializations` is 535 lines, `RefreshProgress` 185, `MaterializeDependencies` 261, and
the four call sites are where the body says. `refreshPartition` issues the delete and the
`INSERT ... SELECT * ... WHERE graph_name` the body quotes. `intent_node_id_instruction` is the only
one of the fifteen any main source names, in `NodeIdDecodeCoverageFacts`, and that read sits in
`detect` rather than in the derivation stratum, so it raises no ordering constraint on the stages.
`meta_relation` declares exactly the ten `derivation` relations the body enumerates and
`meta_gatherer_dependency` carries exactly eight `derivation` edges. `HAND_WRITTEN` lists the six
tables on the impossibility argument the body describes, and `ClassificationDomainCapture`'s javadoc
does call itself the SDL gatherer's last stage. Every quotation from `fact-model.adoc` is verbatim,
including the stored form, the plan-existing sentence and the 6293 s against 90.8 s. Every class,
test and gate method the body names exists under that name, `StageOrderGateTest` excepted, which is
the one it proposes.

**Finding 1, blocking (question one). The goal paragraph claims two outcomes this item does not
deliver, and the middle one it does.** "No view is refreshed into a table by a register" is exactly
what lands and is judgeable on its own. The clause around it is not. "Computes every derived verdict
the fact store answers with exactly once per capture" is false the day this lands: about a hundred
plain `intent_` views remain views, inlined at every naming, which "What this item does not do" says
in as many words. "No reader ever meets a rule that a recursive view re-walks once per driving row"
is also not delivered: the schema ships seven recursive views, this item converts one of them
(`intent_node_id_decode_column`) and R954 converts three, leaving `intent_authored_field_claim`,
`intent_field_chain_node` and `intent_jvm_ancestor` as recursive plain views readers still name, and
`intent_field_chain_node` is one R954 explicitly leaves for a stage to read inline. This is blocking
rather than a wording note because of what the goal paragraph is for here: it stands alone, both
gates are decided by reading it, and the revive rule decides an item undelivered when its acceptance
evidence does not demonstrate its goal. As written, the acceptance evidence this item names, a
consumer's capture timed warm and cold, cannot demonstrate two thirds of its own goal sentence.
What satisfies it: trim the first and last clauses to the register and its fifteen rules, and let the
recursion claim say what is true, that the one recursive rule among the fifteen is evaluated once per
capture instead of once per driving row.

**Finding 2, blocking (question two). The demote rule's third term measures against a maximum that
does not exist, and the tool that would supply it refuses to have one.** "Convert or demote, per
relation, on three terms" makes the third term the statement size after the demote, "from
`report-inline-multiplicity`, which must stay inside the shipped maximum". There is no shipped
maximum. `InlineMultiplicityCheck`'s own javadoc is explicit that there is not and argues why:
"Reports rather than gates. A ceiling would have to be a number somebody could defend, and the metric
is a deliberate over-approximation ... Until a few reductions give that number a basis, printing the
ranking is what the metric is for." Run against the tree as it stands it prints a top-15 ranking, no
threshold anywhere, heaviest relation 141, which does not agree with the 963 `fact-model.adoc` states
for the same quantity, so an implementer reaching for a ceiling finds neither a gate nor two figures
that agree. The term bites exactly where the body says it bites hardest: `intent_mutation_write_destination`
is rung 15 with a hundred-relation closure and is one of the three demote candidates, and the same
page prices the fully-demoted schema at 2739455 with two relations exhausting a four-gigabyte heap
while still parsing. What satisfies it: either give the ceiling a basis and say what it is, this item
being precisely the reduction the tool's javadoc says it is waiting for, or drop the third term to a
recorded judgement, the relation's before and after number from the ranking written into the commit
beside the other two terms, with no threshold claimed.

**Finding 3, blocking (question two). `StoreRefresh` reads the register in executable code, not in a
comment.** The last commit lists it as "`StoreRefresh`'s comment naming `refreshPartition` is
repaired". What is actually there is `refilled(dsl)`, a method whose whole body selects
`META_MATERIALIZE.TARGET_TABLE_NAME`, and whose result the warm pass's graph-scoped clear subtracts
so that a registered target is not emptied by a clear that is not its owner's. Dropping
`meta_materialize` breaks the method, and repairing the comment above it does not answer what
replaces the exemption. It is also an unmade decision rather than a mechanical repoint, because every
converted relation gains a `meta_relation` row owned by `graphitron` on this item's own terms, so the
clear either starts covering the converted tables, emptying each one in the same transaction that its
stage is about to delete and refill, on relations that comment calls a consumer store's largest, or it
excludes them from a source that has to exist. The stage roster the order gate and the analyse loop
both already need is the obvious one, which is why this is a sentence and not a redesign. Worth saying
that the adjacent argument holds: `StoreRefresh.prepare` is called from inside the capture, so a
`graphitron:dev` round that skips the capture runs neither the clear nor the stages, and the case
made for deleting `DevMojo`'s `refreshAll` is sound.

**Finding 4, blocking (question two). Blanket graduation into `RetiredVocabularyGuardTest` is against
that guard's entry bar, and the generic half of the list fails the build on arrival.** The last commit
says "the retired names below graduate into `RetiredVocabularyGuardTest`'s registry". Two things
refuse that. The guard's own javadoc sets the bar at demonstrated recurrence, "a term enters the
registry when an audit finds it surviving a cleanup, not at every rename", and excludes the generic
case by name, "tokens too generic to be unambiguous are omitted even when retired";
`roadmap/workflow.adoc` says the same, that recurrently-surviving terms graduate, as the escalation
step of the Done-gate sweep rather than as a plan deliverable. And mechanically the list cannot be
entered as it stands. Token entries match a whole identifier token over the Java identifier character
class, and in main sources outside the three classes being deleted, `registration` occurs 155 times,
`registered` 186 and `register` 106, almost all of it the live and unrelated data-fetcher sense in the
generators, so `noRegisteredTokenIsALiveMainSourceName` fails the build the moment any of them is
registered. `_live` cannot be a token entry at all, being a suffix rather than a token:
`intent_node_id_instruction_live` is one identifier. What satisfies it: keep the `## Retired
vocabulary` section as it is, since it is the right declaration for the Done-gate sweep, and change
the last-commit bullet to say that the sweep runs and only what survives it graduates, naming the
handful that could be token entries (`meta_materialize`, `meta_materialize_dependency`,
`Materializations`, `RefreshProgress`, `refreshPartition`, `refreshWhole`, `refreshAll`,
`refreshAnalysing`, `REGISTRATIONS`, `REFRESH_STAGES`) and leaving the generic terms to the sweep and
to `FactSchemaGateTest`'s frozen roster, which already holds the relation names against regrowth.

**Non-blocking, two.** "Ten of the fifteen carry none today", of a primary key, disagrees with this
item's own `key today` column and with the DDL: three of the fifteen have a primary key
(`intent_field_column_scope`, `intent_argument_column_scope`, `intent_node_id_decode_hop`) and twelve
have none, six of those carrying one index. The plan is unaffected, the instruction being to key what
the grain admits, but the number is quoted as a finding and should be twelve. Separately, "the other
thirteen wait for R954's rung 6" attributes to R954 a number from this item's numbering: R954's own
ladder stops at rung 5 because it counts plain views as rungs and this item counts only registrations,
so the same relations carry different numbers in the two bodies. Under this item's definition the
figure is right. Say "the top of R954's ladder", or state the numbering difference once where the rung
is defined.

**On the dependency.** R954 is `Spec` with three withheld rounds, and its round 3 records that round
2's two blocking findings still stand unanswered in its body. This item takes both of those findings
as settled ("Its round-2 review found two things this body takes as settled"), which is a fair reading
of the findings but not of R954's plan, which still describes the Java fold round 2 objected to. It
also defers a live shape question, the graph predicate inside a recursive seed, to "whatever shape
R954 lands". Not raised as a finding, because deferring one open question to the item that owns it is
the right call and the front-matter edge is honest about the ordering. Worth knowing when this item is
picked up: two of its fifteen are convertible against R954's phases rather than its Done, and the
other thirteen are not startable until R954 both lands and settles the seed question.

### Response to round 1 (2026-09-17, session 01Kc43YJCDD7SrJp3kXLiRxb)

All six findings are answered in the body by the session that raised them, which disqualifies that
session from signing off the result: the next `Spec → Ready` gate needs a third session. What changed,
so the next reviewer reads the diff rather than reconstructing it:

- **Finding 1.** The goal's first sentence is scoped to the twenty-three registered verdicts, which is
  what this item and R954 between them deliver, and the recursion clause now claims the one rule it is
  true of rather than every reader in the store.
- **Finding 2.** The demote rule's third term is a recorded judgement, the reader's statement size
  before and after written into the commit, with no threshold claimed. The body says why there is no
  ceiling to claim, that this item is one of the reductions the tool is waiting on, and what the figure
  is read against instead. The disagreement between the shipped ranking's 141 and the page's 963 is
  stated where an implementer will meet it.
- **Finding 3.** `StoreRefresh` moves out of the comment-repair bullet into one of its own. The
  exemption keeps its meaning and its measured argument and is repointed to the stage roster the order
  gate and the analyse loop already need. The `SELF_SWEEPING` alternative is named and refused, with
  the reason: the sixteen relations declaring `graphitron` today are base tables the gatherer's stages
  write without emptying a partition, and this clear is what empties them.
- **Finding 4.** Graduation into `RetiredVocabularyGuardTest` is conditioned on the Done-gate sweep
  rather than scheduled. The body carries the guard's entry bar, the ten names a sweep could plausibly
  graduate, and the mechanical reason the generic terms and the `_live` suffix cannot be token entries.
- **The two non-blocking corrections** are made: twelve of the fifteen carry no primary key, the three
  that do are named, and the rung numbering says once that it counts only registrations where R954
  counts plain views too, with the one number that read as R954's replaced by the relation it means.

Nothing in the ladder, the placement, the stage shape or the test plan changed, so the verification
those parts had in round 1 carries.

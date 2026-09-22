---
id: R958
title: "The column-scope departures and the input-field and argument walks are gatherer-written rows, not registered views"
status: Spec
bucket: architecture
priority: 2
theme: model-cleanup
depends-on: []
created: 2026-09-18
last-updated: 2026-09-22
---

# The column-scope departures and the input-field and argument walks are gatherer-written rows, not registered views

## Goal

Six derived rules that a consumer's build re-resolves on a cadence nobody owns become rows one
gatherer writes down once per capture. The *fact store* is the H2 database each generator pass
captures the schema, the jOOQ catalog and the classpath into and answers its verdicts out of by SQL;
a *gatherer* is one pass that fills it from one input, and the `graphitron` gatherer runs last, as a
sequence of *stages*, each a statement over rows earlier stages wrote. Five of the six are
*registrations* today, rows of `meta_materialize`, the register that keeps a rule in a view under a
`_live` name and refills a table from it after every gatherer has finished, which exists to schedule
refreshes for rules no owner schedules. The sixth, `intent_input_field_reference_step_target`, is not
even that: it is a plain view, and the `@nodeId` decode-hop rule re-walks it once per *driving row*,
once for each row of the relation that read reaches it from. When this lands the register holds
fourteen rows where it holds nineteen, the decode-hop rule joins a stored walk instead of re-walking
one, and the column-scope family's departures are captured facts the way the `@reference` family's
already are.

The six, in the order their rules read each other: `intent_carrier_data_field`,
`intent_field_scope_table`, `intent_argument_scope_table`, `intent_input_field_resolving_table`,
`intent_argument_reference_step_target` and `intent_input_field_reference_step_target`. The first
four are *departures*: which table a field's, an argument's or an input field's column-shaped content
binds against, which is where a predicate the generator emits is rooted. The last two are the two
`@reference` path walks that are not the field-site walk R954 shipped, and they read the departures,
which is why one item holds both halves.

Two of the six cannot be stated as one view and land as jOOQ statements instead, which is where this
item spends most of its effort: a rule that leaves the catalog leaves every instrument that reads a
rule body, the stage-order gate among them, so the same item that adds those two stages is the one
that gives the gate a read set it can derive from a jOOQ statement. A stage whose placement nothing
checks is not the shape this family of items is moving the store towards.

What it unblocks is as much of the point as what it costs. R955, which empties the rest of the
register bottom-up, has fourteen rungs it cannot start: every one of them reads one of these five
registrations directly or through a plain view, and a stage may not read a table the refresh refills
after it. Each conversion here releases the rungs above it, so the two items interleave rather than
queue.

The cost this closes is measured and bounded, seconds rather than the failure its predecessor
answered; what raises this item's priority is the fourteen rungs behind it rather than its own
figures. `meta_materialize`'s own reason for `intent_node_id_decode_hop`
carries the measurement: the two reference-target walks cost 12 and 25 milliseconds standalone and
account for seconds of the rule that names them, which is per-driving-row re-evaluation stated as
arithmetic. One of those two is registered and one is the plain view this item stores, so what is
left to collect is the second half of a lever whose first half is already in the tree. Every figure
in that row and in this body was taken before R953's statistics levers shipped and before R954 landed
its stages, so this item re-takes its own reading rather than quoting one; "Tests" says with what.

## Implementation

The method is R954's and R955's rung 0, not a new one. Read `FieldColumnScopes` for the
insert-over-a-rule-view shape, `FieldReferenceStepTargets` for the recursive two-arm shape, and
`StageOrderGateTest` for the invariant the placement has to satisfy: no step of the pass reads a
table a *later* step writes, because it would read the previous capture's rows.

### The ladder, and why the order is forced

Computed from the shipped DDL by expanding each rule through every view it names until only stored
relations remain. Each rung reads exactly one registered target, and every one of those is this
item's own, which is what makes the six a single ladder with nothing outside it in the way.

[cols="1,4,3,3,2"]
|===
| rung | relation | registered target its rule reads | producer table its rule reaches | shape

| 1 | `intent_carrier_data_field` | none | `intent_type_backing_class` (`TypeBackingRows`) | rule view
| 2 | `intent_field_scope_table` | `intent_carrier_data_field` | none | rule view
| 3 | `intent_argument_scope_table` | `intent_field_scope_table` | none | rule view
| 4 | `intent_input_field_resolving_table` | `intent_argument_scope_table` | `intent_input_occurrence_path`, `intent_input_occurrence_path_step` (`InputOccurrencePaths`) | rule view
| 4 | `intent_argument_reference_step_target` | `intent_argument_scope_table` | none | two arms
| 5 | `intent_input_field_reference_step_target` | `intent_input_field_resolving_table` | none | two arms
|===

The two rung-4 conversions are independent of each other and both wait on rung 3; everything else is
a chain. A rung converted out of order reads a table the refresh still fills, which is the seam
`StageOrderGateTest` exists to catch, so the order is enforced rather than merely recommended.

### Placement

All six stages go in `FactCapture.derive`'s derivation stratum *after* the hand-written producers,
between `AuthoredClaimRejectionRows` and `Materializations.refresh`. After the producers because two
of the rungs reach tables those producers write, `intent_type_backing_class` through
`intent_type_backing` and the two occurrence-path tables directly, so a stage ahead of them would
read the previous capture's rows. Before the refresh because the registrations that survive read
these rows and have to see this capture's. This is the opposite end of the stratum from rung 0, whose
rule bottoms out in captured facts alone and therefore runs first, and the difference is a property
of each rule's read set rather than a convention.

### What each conversion owes

Per relation, the same list the shipped conversions satisfied:

- A graph-scoped `DELETE` before the `INSERT`. This is mandatory rather than tidy: the store persists
  across `graphitron:dev` rounds, so a second capture of one graph is the ordinary case, and an
  appending stage fails loudly on a keyed relation and doubles a keyless one in silence.
- The canonical name moves into the `graphitron_` family, the prefix naming the gatherer that writes
  the rows, and every reader is repointed: the view bodies in `graphitron-model.sql`, the generated
  jOOQ constants in `graphitron`, `graphitron-lsp` (`CarrierDataField`, `DiagnosticFacts`) and
  `graphitron/src/main/java/no/sikt/graphitron/plan/RoutineWriteFacts.java`, and the relation's own
  tests. Prose counts as a reader: `ServiceCatalog` and `MaterializedRegistryFixture` both name one
  of the six in javadoc, and the retirement sweep at the Done gate is what catches the ones a
  compiler cannot.
- A `meta_relation` declaration with a grain sentence and an example, for the target and, where one
  is kept, for the rule view: with no registration there is no exemption for a rule view, and the
  frozen `undeclared-relations.txt` roster only shrinks, so each conversion deletes its line from it.
- A primary key that matches the declared grain, so `MetaDeclarationGateTest`'s key case is satisfied
  rather than widened. Two of the four have no key at all today, `intent_carrier_data_field` and
  `intent_input_field_resolving_table`, and declaring a relation is what forces one. The second's
  grain is legible from its rule, a `SELECT DISTINCT` over the input coordinate and the resolving
  table; the first's is not, four of its six columns being nullable, so the rung that converts it
  derives the grain from the rule and states it rather than taking the column list as given.
- The rule text stays in the catalog, under a `_rule` name beside the target, so the `EXCEPT` oracle
  in both directions between table and rule is a standing assertion rather than a one-commit check.
  Both directions, because they fail differently: rows in the table and not in the rule are a
  previous capture's leftovers, rows in the rule and not in the table are rows the stage did not
  write.
- The index a register reason justified moves onto the relation's own declaration, where rung 0 put
  it. `ix_field_scope_table_coordinate` and `ix_argument_scope_table_coordinate` both carry
  measurements in their comments and both stay; what moves is the prose that explains them.

### The two walks

Rungs 4 and 5's walks cannot be one insert over one rule view, because each has to land as two
relations and a view states one. They take the shape `FieldReferenceStepTargets` landed in: two arm
tables under a union view carrying the canonical name, each arm one statement over one recursive
chain, written as jOOQ.

**The arm split is argued per relation, not inherited.** Two relations and not one because the rows
carry two key shapes: on a `KEY` or `TABLE` element the constraint and its orientation are identity,
and on a `NAME_MATCH` or `CONDITION` element there is no key to enumerate, so the coordinate with
both table triples is already total. One relation over all four arms would therefore declare no key
at all, and a keyless stage doubles its rows on a second capture of one graph without failing. Both
new relations enter `MetaDeclarationGateTest`'s declared-table population, so each arm owes its own
grain sentence and a primary key that spells it.

**The keys are not the field walk's.** The argument walk adds `argument_name` to the coordinate,
because an argument's path departs from what the argument's own content binds against rather than
from the enclosing type's binding. The input-field walk puts the whole departure triple in the key,
which neither sibling does and which its shipped column comments already state: an input field's
departure is its consuming site's and not its own, so one input field reached under two arguments
whose fields select from different tables walks two chains from one authored path. Transcribing the
field walk's key would lose that row.

**The arities sit a level below the arm filter**, because a `WHERE` is evaluated before the window
functions of its own `SELECT`, so a filter beside them counts the arm where the relation means to
count the element. Getting this wrong is what sent R954 back at its Done gate;
`FieldReferenceStepTargets.counted` states the rule where an implementer meets it.

**One walk, three callers.** After this item three relations carry the same chain, the same
`DENSE_RANK`, the same two arities, the same arm filter and the same level-below-the-filter hazard,
differing only in the seed join and in which coordinate columns go into the key and the window
partitions. The schema tolerates that duplication between the two hop views with an argument its own
comment makes, that SQL cannot parameterize a coordinate. Java can, so the same trade is not
available here: the walk becomes one builder parameterized by the coordinate columns, the seed
`Select` and the arity partition, with `FieldReferenceStepTargets` refactored onto it as the first
caller. Three spellings of one rule with no enforcer between them is the shape this whole family of
items exists to remove.

**What stops seeing a rule when it becomes Java, and what this item does about it.** The four rule
view conversions keep their read set in the catalog, where `ViewReferences` parses it, where
`MetaDeclarationGateTest.aDeclaredViewReadsOnlyWhatItsOwnerMay` checks ownership against it, and
where the `EXCEPT` oracle stays runnable. A jOOQ stage has none of that, and `StageOrderGateTest`
skips a step whose `ruleView` is null, which means the placement argument for exactly these two rungs
is checked by nothing. Today that blind set is the five producers and the hop stages; two more tips
the derivation stratum into being mostly unparsed, which is the hand-kept ordering with no derivable
source the fact model rules out by name.

The source is derivable and nearly in place. `ViewReferences.readBy` does its whole walk over a jOOQ
`Query` through a render `VisitListener`; only its `definitionOf` plus `parse` prologue binds it to a
stored view name. So this item splits that prologue off, giving `readBy` an overload that takes the
`Query` directly, has each jOOQ deriver expose the statements it runs beside the method that runs
them, and teaches `StageOrderGateTest` to derive a jOOQ stage's read set from the same object the
capture executes. The seam lands with rung 4, the first jOOQ stage this item adds, and the two hop
stages and the producers that can expose their statements come with it. It is the largest single
piece of work here and it is what stops this item from spending an enforcer to buy a stage.

**`intent_argument_reference_step_hop` stays a view unless a measurement says otherwise, and the
measurement is not a statement count.** The hop is named in the walk's recursive term, so it is
re-expanded once per accumulated row rather than once per statement, which is the multiplier the
fact-model page measured at 146 seconds for twenty rows on a comparable shape. The field-site walk
recurses over two keyed tables and the argument-site one would recurse over a four-arm `UNION ALL`
across `sql_constraint`, `sql_referential_constraint`, `graphitron_spelled_table` and the name-match
and condition routes, so the asymmetry is real and not an oversight. The answer may well still be
that the argument population is small enough to leave the hop a view, and that is the expected
outcome; what this item may not do is assert it from the shape. Rung 4 takes the reading on the
largest store it can reach and states the number, and if the number is bad it says so and files the
conversion rather than absorbing it.

While rung 4 is in that neighbourhood: the hop view's comment says the two hop bodies are textually
identical arm for arm, which stopped being true when the field-site hop became jOOQ. The rung that
reads that comment is the one that should correct it.

### The producer that stops being special

`UnlowerableOrderingRejectionRows` runs after the refresh, alone, for one reason: the view it renders
reads `intent_field_scope_table`, which the refresh is what fills. Rung 2 removes that reason. The
move into the stratum is one call site, one `StageOrderGateTest` row and a javadoc paragraph in
`FactCapture` that stops being true, and rung 2 has to touch that class anyway for the rename, so it
lands with rung 2 rather than being left for R955 to collect. R955's body claims the move and its
round-1 reviewer already verified that it closes no cycle, nothing in the stratum reading what that
producer writes; the `Relation to other items` section below records that it moves here instead.

The fallback is not "leave it alone". If the move is declined for a reason this body does not
anticipate, rung 2 still rewrites the comment that explains the position, because a step sitting
after the refresh for a dependency that no longer exists is a stale rationale nothing can catch: the
producer is jOOQ with no parsed read set, so the order gate cannot see that its position has become
gratuitous.

### Landing

Rung by rung, in ladder order. The seam is real rather than bookkeeping: each rung leaves the tree
green with the register one row smaller and releases the R955 rungs that were waiting on exactly that
relation, so an intermediate state is observable and worth stopping at. How many commits a rung takes
is the implementer's call.

## Tests

R954's and rung 0's test obligations apply unchanged. What follows is what this item adds or moves.

- **`StageAnswerAgreementTest`** gains a `Stage` row per rule-view conversion, four of them, over a
  captured store whose fixture reaches every arm of each rule. A comparison between two empty
  relations passes while asserting nothing, so each addition owes the non-vacuity case beside it.
- **`StageOrderGateTest`** gains a `Step` per stage in `STRATUM`, and `UnlowerableOrderingRejectionRows`
  moves up the list with rung 2. The two walks are where the gate grows rather than merely gains
  rows: with the read set of a jOOQ statement derivable (see "The two walks"), a `Step` carries
  either a rule view or a statement source, and the cases that today skip a null `ruleView` check
  both. Until that seam lands, a jOOQ step is ordered by nothing, which is what makes the seam part
  of this item rather than a follow-up. Its `aStageAheadOfItsPrerequisiteIsCaught` case is written
  against `intent_node_id_decode_column_live`, which is R955's and still registered when this item
  finishes, so the case survives as written.
- **`MaterializeRegistryGateTest`** is equality-pinned on both figures: `REGISTRATIONS` falls from 19
  to 14, and `REFRESH_STAGES` falls from 13 by however much the dependency depth loses, which the
  implementer re-derives per rung rather than predicting here.
- **`DerivedReadCostTest`**'s `READERS_WITH_CELLS` and `CELLS` both fall, since five registrations
  leaving takes their cells with them. Both are equality-pinned, so each rung fails the build until
  its figures are updated, which is the ratchet working rather than friction.
- **The two walks are pinned by named fixtures, not by agreement with the view they replace.** A
  total `EXCEPT` against the retired rule is worth running once as evidence, and for rung 5 it is
  nearly free since the view is the only statement today, but it is evidence and not the
  specification: asserting that the replacement equals its predecessor installs the predecessor as
  the standard and pins whatever it gets wrong. What each walk owes instead is the property the
  arm-split shape can get wrong and the single-view form could not, which is
  `ReferenceStepTargetTest.bothArmsOfOneElementCountTowardsItsArities` on its own cross-arm seed,
  one per walk; and for rung 5 the two-departure case, one authored path walked from two resolving
  tables, which is the property that relation's own comment calls out as distinguishing it from both
  siblings.
- **`WarmStartRefreshTest`** covers a second capture of one graph doubling nothing. Each new table
  owes a fixture that populates it, or the case counts zero against zero on exactly the relation it
  exists to hold.
- **`MetaDeclarationGateTest`** already holds the declared-view ownership rule and the declared-table
  key rule; both bite here rather than needing extension, and the key rule is what forces
  `intent_input_field_resolving_table`'s grain to become a key.
- **`SeededStore`** runs each new stage in the stratum's position. A harness that seeds the catalog by
  hand gets nothing from a stage nobody ran, and the refresh it calls no longer fills these tables;
  rung 0 found this the hard way, in eighteen unrelated-looking test failures.
- **`UnregisteredRelationTest`** takes `intent_argument_scope_table` as its `TARGET`, chosen for
  being named from seven view bodies, and its own javadoc says the subject moves each time a
  conversion takes the previous one out of the register. Rung 3 is that conversion, so it moves the
  subject again, to a relation still registered and still named from several view bodies.
  `RelationRegistrationGateTest`, `DetectionReadReachGateTest`, `RefreshPlanStatisticsTest`,
  `FactCaptureAgreementTest` and `FactSchemaGateTest` each name one of the six in a roster or in
  prose and move with the rung that converts it.
- **Renames** reach `ArgumentScopeTableTest`, `FieldScopeTableTest`, `CarrierDataFieldPopulationTest`,
  `InputFieldResolvingTableTest`, `ArgumentReferenceStepTargetTest`, `InputFieldColumnScopeTest`,
  `ArgumentColumnScopeTest`, `FieldParticipantScopeTableTest`, `FieldEndpointsTest` and the
  `graphitron-lsp` surface tests. A rename is not a rewrite: a test whose assertion changes in one of
  these commits is a finding for the rung's reviewer.

**The measurement this item owes.** The modelling claim, that each of the six resolves once per
capture under an owner, is demonstrated in-tree by the gates above. The cost claim is not, and the
figures this body inherited are stale twice over. The in-tree instrument is `DerivedReadCostTest`'s
scan counts, which are the same number on a loaded machine as on an idle one; the reading to take is
the decode-hop rule's cells before and after rung 5, since that rule is where the input-field walk's
per-driving-row expansion is paid. No consumer store is reachable from this repository, which is the
limit R954's Done gate recorded and which applies here unchanged: a session holding one takes the
wall-clock reading with the method `meta_materialize`'s decode-hop row states, three sweeps per
configuration with `OPTIMIZE_REUSE_RESULTS` off, and this item claims a ranking rather than a price
until somebody does.

## Retired vocabulary

The `_live` spelling and the `intent_` canonical name of each converted relation:
`intent_carrier_data_field`, `intent_field_scope_table`, `intent_argument_scope_table`,
`intent_input_field_resolving_table`, `intent_argument_reference_step_target`,
`intent_input_field_reference_step_target`, and the five `_live` views beside them. Also the phrase
"materialized into ... on the capture cadence" wherever it describes one of these six, and the
register-reason prose each conversion deletes from `meta_materialize`.

## Other solutions we've considered

**One relation per walk instead of two arms.** Rejected for the reason R954 rejected it: a single
relation over all four `via` arms has no total key, so it declares none, and a keyless stage doubles
its rows on a second capture of one graph without failing. The union view is what gives readers one
name back.

**Restating the four departure rules as jOOQ expressions, the way `FieldEndpoints.derive` states
its.** Rejected. The rule would then be stated twice with no oracle proving the two statements agree,
where keeping it a stored view leaves `ViewReferences` a read set to parse, leaves the declared-view
ownership gate something to check, and leaves the `EXCEPT` runnable for as long as both relations
exist.

**Letting the two jOOQ stages stay unparsed, and filing the read-set seam as a successor.** This is
the cheaper item and the one that should be named, since it is what R954 did. Rejected because the
enforcer and the thing it enforces belong in the same item: a stage's placement argument is the
whole safety case for converting a rule, and an item that adds two stages nothing can check has
spent an invariant to buy a cadence. The seam is also cheapest here, `ViewReferences.readBy` already
walking a jOOQ `Query` and needing only its stored-definition prologue split off, and it pays for
the hop stages already in the tree on the way past.

**Leaving `intent_input_field_reference_step_target` a view and registering it instead.** That is the
move the tree already made for its argument-site sibling, and it is the one this item is undoing:
a registration buys the same per-capture evaluation at the cost of a refresh pass nobody owns and a
rung the fourteen above it cannot cross.

## Relation to other items

**R954** shipped the three rungs below these and is where the method, the placement argument and the
two-arm key gate all come from. Its re-measurement caveat carries over verbatim.

**R955** empties the rest of the register bottom-up and is blocked on this item for fourteen of its
fifteen rungs, which its own body records after recomputing the ladder at pickup. Two coordination
facts: this item takes `UnlowerableOrderingRejectionRows`'s move into the stratum, which R955's body
lists as its own, and R955's rung 0 is the worked example every conversion here follows. The items
interleave, so both bodies have to stay accurate about which relation has already left the register;
the register itself is the arbiter, not either body.

**R961** repoints prose that still calls a converted relation a view. Each conversion here creates
the same hazard, so each rung checks its own prose rather than filing a successor.

---
id: R942
title: "A rule that evaluates an unregistered view once per driving row fails the build, whoever writes the next one"
status: Spec
bucket: testing
priority: 3
theme: model-cleanup
depends-on: []
created: 2026-09-09
last-updated: 2026-09-09
---

# A rule that evaluates an unregistered view once per driving row fails the build, whoever writes the next one

## Goal

A rule that makes the database ask an expensive question once per driving row, rather than once,
stops the build that introduces it instead of reaching a consumer. Three terms, glossed once. The
*fact store* is the H2 database each generator pass captures the schema, the jOOQ catalog and the
classpath into, and then answers its verdicts out of by SQL. An `intent_` *rule* is one of those
verdicts stated as a SQL view over captured facts. A *registration* is a row of `meta_materialize`
that keeps such a rule in a view under a `_live` name and moves the canonical name every reader
spells onto a table the capture refills once per pass, so readers meet stored rows instead of
re-evaluating the rule; a view with no registration is *unregistered*, and every naming of it
expands its body afresh.

H2 inlines a view wherever it is named and eliminates no common subexpression, so a rule named once
inside a correlated subquery is evaluated once per row of the query it is correlated to, and one
named inside a recursive term is expanded once per iteration of the walk. That is the whole
mechanism behind a dev round on a real consumer schema costing minutes instead of seconds, and today
nothing refuses it: the shape is invisible in the SQL text, where the naming appears exactly once,
and it is invisible to the cost gates, whose fixtures are a dozen units when the cost is a function
of the consumer's population.

When this lands, a stored view definition that names an unregistered view inside a correlated
subquery or a recursive term is a build error in the `graphitron-model` test tier, naming the reader,
the view it names, the position and the relations that drive the re-evaluation. The pairs that exist
on landing day are carried on a roster in two parts: a dated, shrink-only *frozen* part holding the
pairs that were already in the DDL when the gate arrived, tolerated rather than judged, and an
*accepted* part holding the pairs a measurement has argued for, each with its figure. A new pair
enters only the accepted part, and only with a measurement; a pair that disappears fails the build
until its row is deleted. So the gate says nothing about the past and everything about the future,
which is what "whoever writes the next one" in the title asks for: the next author meets the shape
while the fix is still free, rather than a consumer meeting it as a dev loop that does not start.

One position the detector reads is deliberately outside the predicate. A naming on the non-driving
side of a join is the shape as written and not the shape as executed: H2 normalizes an inner join's
predicate into `WHERE` and drives from whichever side it likes, so the walk cannot know whether that
naming is re-evaluated. The gate refuses only what the walk knows, and discloses the gap in its own
javadoc; the section on scope below says why, and what would close it.

## Why this is cheap: the detection already runs

`ViewReferences.readBy` parses every stored view definition it is asked about and already answers
this exact question. `ViewReferences.Position` names the three re-evaluating positions
(`INNER_SIDE`, `CORRELATED`, `RECURSIVE`), `ViewReferences.Enclosure` carries what each one is
re-evaluated against, and `ViewReferences.Reference#reEvaluated` answers whether anything
re-evaluates a reference beyond the once its naming already costs. Nothing in the tree reads that
answer and fails on it: `MaterializeDependencies` and three existing gates go through
`relationsReadBy`, which discards position and multiplicity on purpose. So the work is a gate over
data the build already computes, plus one fact moved from javadoc onto the type that owns it.

Two constraints, both from what the tree already records. The gate is a structural predicate with a
roster, never a cost score: `ViewReferences`' own javadoc records that weighting these positions
into a ranking was built, run against a real capture, and refused, because it did not reproduce the
register's recorded savings and was worse than a plain count exactly where it mattered. And the
predicate ranges over namings of *unregistered views*: a re-evaluated naming of a registered target
is a seek against a table and fine, which is what a registration is for.

## What the predicate finds today

Measured on the booted fact schema with no rows, which is all the predicate needs, by running the
walk below over every view in the catalog. 124 views make 849 references, 584 of them enclosed by
something that re-evaluates them; 463 of those name a table, which the predicate ignores, and 121
name a view. Of the 121, 68 sit only on the inner side of a join and are outside the predicate.
The 53 that remain reduce to 39 distinct (reader, named view, position) rows from 24 readers into
27 views, 37 correlated and 2 recursive. This table is what the gate computes and only starts the
implementer from; the roster ships from the gate's own output on the day it lands, not from this
list, because the register moves between now and then (R939, the `@nodeId` landing-defect item, registers
the decode hop, which changes the inner-side population and leaves this one alone).

| reader | names, and at which position |
|---|---|
| `intent_argmapping_projection_defect` | `intent_argmapping_key_column_candidate` (CORRELATED) |
| `intent_argument_reference_step_hop` | `intent_name_matched_key_pair` (CORRELATED) |
| `intent_argument_reference_step_target` | `intent_argument_reference_step_hop` (RECURSIVE) |
| `intent_carrier_data_field_live` | `intent_bound_table` (CORRELATED), `intent_errors_field` (CORRELATED), `intent_type_backing` (CORRELATED) |
| `intent_condition_context_parameter` | `intent_condition_slot` (CORRELATED), `intent_condition_table_parameter` (CORRELATED) |
| `intent_condition_method_route_defect` | `intent_condition_method_route` (CORRELATED) |
| `intent_condition_param_extraction` | `intent_java_enum_class` (CORRELATED) |
| `intent_condition_table_parameter` | `intent_jvm_ancestor` (CORRELATED) |
| `intent_errors_field` | `intent_poly_member` (CORRELATED) |
| `intent_field_chain_node` | `intent_field_chain_start` (RECURSIVE) |
| `intent_field_error_channel` | `intent_errors_field` (CORRELATED), `intent_type_backing` (CORRELATED) |
| `intent_field_reference_step_hop_live` | `intent_name_matched_key_pair` (CORRELATED) |
| `intent_field_separate_fetch` | `intent_bound_table` (CORRELATED) |
| `intent_inferred_node_type` | `intent_node_metadata_defect` (CORRELATED) |
| `intent_mutation_routine_seat` | `intent_bound_table` (CORRELATED), `intent_carrier_routine_hop` (CORRELATED), `intent_connection_element_type` (CORRELATED), `intent_field_chain_node` (CORRELATED), `intent_field_chain_start` (CORRELATED), `intent_field_chain_terminus` (CORRELATED), `intent_name_matched_key_pair` (CORRELATED) |
| `intent_node_container_member` | `intent_node_type` (CORRELATED) |
| `intent_node_id_candidate_node_type` | `intent_node_type` (CORRELATED) |
| `intent_node_id_decode` | `intent_node_id_candidate_node_type` (CORRELATED), `intent_node_id_decode_slot` (CORRELATED), `intent_record_slot_assignable` (CORRELATED) |
| `intent_node_id_decode_landing_defect` | `intent_node_id_decode_endpoint` (CORRELATED), `intent_reference_for_application` (CORRELATED) |
| `intent_node_id_decode_slot` | `graphql_element_field` (CORRELATED) |
| `intent_node_id_instruction_live` | `intent_node_type` (CORRELATED) |
| `intent_node_id_polymorphic_decode_defect` | `intent_field_producer_reference` (CORRELATED), `intent_node_id_decode_slot` (CORRELATED), `intent_record_slot_assignable` (CORRELATED) |
| `intent_resolved_field_claim` | `intent_authored_field_claim` (CORRELATED) |
| `intent_resolved_node_type_id` | `intent_node_metadata_defect` (CORRELATED) |

Three readings of that table matter for the design. First, the one row a measurement already
stands behind: `intent_node_id_decode_landing_defect` names `intent_node_id_decode_endpoint` inside
a correlated `NOT EXISTS`, and the landing-defect item priced that read at 115 ms on the population
that exposed the defect and recorded it as acceptable on its own pin; that row starts on the
accepted side, citing the pin, and every other row starts frozen. Second, three readers are
themselves registered source views (`_live`), so their per-row expansion is paid once per capture
rather than once per read; that is a different cadence, not a different shape, and they stay in
scope with the cadence derived rather than written down (see Implementation). Third, one row is a
reader naming a *captured* view rather than a rule, `intent_node_id_decode_slot` naming
`graphql_element_field`, which is a reminder that the predicate is over what the catalog calls a
view and not over the `intent_` prefix.

## Implementation

**A position says whether it survives planning, on the type.** `ViewReferences.Position` gains a
method, `survivesPlanning()`, true for `CORRELATED` and `RECURSIVE` and false for `INNER_SIDE`, and
the class javadoc's paragraph "what the three positions are worth is not equal" collapses onto that
method's doc. The fact exists in prose today and the gate would be its second copy; putting it on the
enum means a fourth position cannot join the type without declaring which side it is on, and the
gate's exclusion of the inner side becomes a model fact it reads rather than a test's taste. The
ordering by strength stays as declaration order; the two axes are orthogonal and the enum carries
both.

**The gate.** A new test class in `graphitron-model` beside `MaterializeRegistryGateTest`,
`PerRowViewNamingGateTest`, structural over `FactStores.inMemory()`: an empty booted store, no
captured rows, no timing and no scan count. It enumerates every relation in `INFORMATION_SCHEMA`
under `PUBLIC` with its kind, calls `ViewReferences.readBy` on each view, and keeps each reference
where `reEvaluated()` holds, the strongest position enclosing it survives planning, and the named
relation's kind is `VIEW`. A registered target is a table in the catalog, so "unregistered view"
falls out of the kind with no register lookup; a reader that named a `_live` source view directly
would count, and none does today. The offender computation is a static helper over a `DSLContext`,
the shape `MetaDeclarationGateTest`'s `viewOffenders` takes, so the seeded case below can run it
against a private store.

The predicate is over *direct* references and is closed under composition: if A names B inside a
correlated subquery and B plainly names an expensive C, the gate fires on A's body for the A-to-B
edge, and B's expense is what the roster argument for that row has to answer. If B itself names C
per row, that is B's own violation and fires on B's body. Every enclosed edge fails at the body that
wrote it, so no transitive walk is needed and none is done.

**The roster.** Two resource files beside the test, in the directory `transcription-readers.txt`
already lives in, one row per line as `<reader> <named view> <position>`, with `#` lines carrying
the reason and a header saying what the file's population is:

- `per-row-view-namings-frozen.txt`: the rows present when the gate landed, dated, shrink-only. The
  header says an entry is not a claim that the naming is acceptable, only that somebody knows it is
  there, and that the file only loses lines. A line leaves when its reader is rewritten, its named
  view is registered, or it is measured and moved to the accepted file.
- `per-row-view-namings-accepted.txt`: rows a measurement has argued for, each with the figure, the
  population it was taken on, and the argument, in `#` lines above the row. This is the only file a
  new row may enter. Where the measurement is already pinned elsewhere, the reason cites the pin's
  class rather than copying the number, so one fact has one home: the landing-defect row cites the
  reach pin that item leaves in this module, not a millisecond figure.

Rows are keyed on the position as well as the pair because the pair alone hides the thing the
register calls unbounded: a pair rostered for a correlated naming would silently absorb a later
recursive naming between the same two relations. A pair enclosed at two surviving positions is two
rows, which is what the failure message names anyway.

The gate asserts, in this order: no row appears in both files; the union of the two files equals the
observed set, by two-way equality, with the message `ExpandedPopulationReaderGateTest` uses stated
for this domain, that a missing row is a rule that now re-evaluates an unregistered view per driving
row and must be rewritten, have its target registered, or be measured into the accepted file, and an
extra row is a naming that no longer exists and whose line must go. A third assertion says the frozen
file only shrinks, which the equality already implies for a single run; it is stated separately so
the failure names the file and not the set.

**The failure message.** For each offender: the reader, the named view, the strongest position, the
enclosure chain outermost first with each enclosure's drivers (all already on
`ViewReferences.Reference`), whether the reader is itself a registered source view, read off
`Materializations.registrations` at failure time rather than from any roster line, so the cadence
the cost is paid at is derived and cannot go stale, and the exits in the order the fact model ranks
its levers under "Derived reads are views, not stored facts": a captured fact where the rule
reconstructs what capture could have written, an index where the named relation is already a table,
a rewrite that moves the naming off the driving row, a registration last because it is the one lever
that adds work, and, where the cost is measured acceptable, a row in the accepted file with the
figure. The message points at the `store-performance` skill for how to take the measurement.

**Scope, and the disclosed gap.** `INNER_SIDE` is outside the predicate for the reason the enum will
now state: it is read off the written join order, H2 is free to drive from either side, and a build
error asserting a fact the walk cannot know is the wrong direction. The gate's javadoc states the gap
on the precedent `CollectionValuedColumnGateTest` sets for a gate that names what it does not see,
and says what would close it: a check that reads the plan rather than the definition, which is a
measurement and not a structural gate, or a fixed-join-order reading restricted to outer joins, which
is the one case the walk's inner-side answer is also the executed shape. Neither is this item. Breadth
is likewise out of scope: a view named many times plainly is many expansions, `report-inline-multiplicity`
in the roadmap-tool run already reports that, and it reports rather than gates for the reason the
fact model gives.

**The census column, named now so two gates do not derive one predicate two ways.** The owner-read
gate item in Spec beside this one, R941, gives `meta_relation_family` a `rule_relation_name` column, the
relation whose stored definition states this relation's rule, which is the relation itself for a
view and the source view for a registered target. "Named relation is an unregistered view" is that
column equal to the relation's own name. Whichever of the two items lands second repoints this
predicate: if that column exists when this gate is written, the gate reads it instead of the catalog
kind; if this gate lands first, the census item's implementation moves the predicate onto the column
as one of its readers, and this item's javadoc says so, so the repointing is owed and not
rediscovered.

## Tests

The gate is the deliverable, so the tests are the gate and the proof that it fires.

- `PerRowViewNamingGateTest.everyPerRowNamingOfAnUnregisteredViewIsRostered`: the equality
  assertion above over the real fact schema. Passes on landing day by construction, because the
  frozen file is the gate's own output; what it demonstrates is stated by the next case.
- `PerRowViewNamingGateTest.theGateDetectsWhatItClaimsTo`: a seeded case on a `ScratchSchema`, the
  private store `ViewReferencesTest` uses, defining a base table, an unregistered view over it, and
  three readers: one naming the view inside a correlated subquery, one inside a recursive common
  table expression, and one on the inner side of a `LEFT JOIN`. The offender helper reports the
  first two with their positions and drivers and not the third, and a fourth reader naming the base
  table inside a correlated subquery is not reported either. This is the case that shows the roster
  files are exemptions from a live predicate rather than a list nobody checks, the precedent
  `MetaDeclarationGateTest.theGatesDetectWhatTheyClaimTo` sets.
- `PerRowViewNamingGateTest.noRowIsBothFrozenAndAccepted`, and a case that the frozen file's header
  carries its freeze date, so a reader of the file knows what the count means.
- `ViewReferencesTest` gains one case pinning `survivesPlanning()` per position, so the enum's
  second axis is asserted where its first already is.

Completion is demonstrated by the seeded case failing on the correlated and recursive readers with
a message naming both relations, the position and the drivers, and by the frozen file on landing day
holding exactly the rows the gate computed, with the landing-defect row on the accepted side citing
its pin.

## Relation to the reach pin

R939, the `@nodeId` landing read-cost item, leaves behind a per-reader pin of the view bodies each detection
component evaluates live. That pin is keyed on a Java detection component and answers "what does this
reader evaluate on every pass"; this gate is keyed on a (view, view, position) triple in the DDL and
answers "what shape may a rule be written in". Different keys over different populations, so they
stay separate rather than folding into one roster; the pin makes a new reach visible and requires an
author to write it down, and this refuses one shape a reach may not take. The one seam is the
landing-defect row, whose measurement lives on the pin and is cited from the accepted file rather
than copied.

## Other solutions we've considered

- **A throw in `MaterializeDependencies.populate`, like the self-reading-registration check.** That
  runs on every consumer's store boot, so it would fire days after the author's build passed and
  would ship the roster into runtime code. The existing throw there is a defect that makes the run's
  own answer wrong, not a cost policy with exemptions. A test-tier gate is where every other DDL
  invariant in this module is enforced.
- **A cost score over the positions.** Built, run against a real capture, and refused, as
  `ViewReferences`' javadoc records; the gate inherits that verdict rather than re-running the
  experiment.
- **All three positions.** Fifty-six more rows on the roster that nobody measured, every future
  inner join against a view a roster edit, and a build error asserting a re-evaluation the walk
  cannot know happened. Refused in favour of stating the gap.
- **Measuring the 38 unmeasured rows before landing.** A day or two of instrument work for
  information the gate does not use; the gate's value is refusing the fortieth row, and measurement
  is the price of a new row rather than the price of the guard. The frozen file makes the backlog
  countable instead of hiding it.
- **Folding into the reach pin.** Two axes spliced into one roster; see the section above.

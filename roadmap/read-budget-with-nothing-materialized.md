---
id: R899
title: "A registration's alternative is counted from the schema, so the last lever stops being the first one reached for"
status: Spec
bucket: architecture
priority: 1
theme: model-cleanup
depends-on: []
created: 2026-08-31
last-updated: 2026-09-01
---

# A registration's alternative is counted from the schema, so the last lever stops being the first one reached for

The fact model has four levers for an expensive derived read, ordered on the fact-model page from
the one that removes work to the one that adds it: capture writes the fact, an index on a stored
column, a rewrite, and last a registration in `meta_materialize`. The order is doctrine and the
register is the record of the doctrine losing. Twenty registrations stand in the schema, and the
reason is mechanical rather than anybody's preference: adding a row to `meta_materialize` costs
three lines and shows up in review, while finding out what the rule costs without one has meant
building an instrument in a scratch directory that dies with the session. A lever nobody can price
is a lever nobody reaches for.

This item makes the alternative countable from the repository. Not the wall clock, which needs a
captured consumer store and correctly stays outside the tree, but the one dimension in which a
registration's necessity has a decisive answer that the schema alone contains: how large a statement
the planner has to build before it reads a row, with the registration and without it.

## What this item's file used to say, and why none of it survives unchanged

The file was filed as a target: no relation in the consumer read set refuses a five-second budget
with nothing materialized. Four things have happened to that target and each is checkable now.

**The target was measured and is unreachable.** The predecessor item took a fresh capture of a
26 818-line consumer schema and priced both arms. With all twenty registrations the store reads all
114 relations in 88.2 s with none over budget. With nothing registered the arm does not finish:
sixteen relations pass a 120-second budget and the planner then exhausts its heap. That is not a
figure to improve on, it is a different failure. Emptying the register is not a reachable state.

**The reason it is unreachable is countable from the DDL and needs no capture at all.** The
fact-model page already states the mechanism. H2 inlines a view at every naming and eliminates no
common subexpression, so the statement the planner builds grows multiplicatively with the depth and
fan-out of the view graph. A registered target is a table, and a table's subtree is itself, so a
registration truncates that tree at its own name for every rule above it. Past some size a
registration is not buying speed, it is buying a plan existing. That is a static property of the
schema text.

**The prediction the file staked itself on has had its cause removed rather than confirmed.** The
file predicted that three relations moving the wrong way sit above
`intent_argmapping_bound_parameter_type`, described there as a six-arm reconstruction of the one
confirmed missing supertype nobody has captured. That relation has two arms today. Its
`UNION ALL` resolves an authored `(class, method)` pair on one side and reaches
`sql_routine_parameter` through `intent_field_routine_method` on the other, and the seven arms that
used to differ only in which owner relation carried the class and the method collapsed into one join
when `graphitron_method_reference` was captured as a table. The supertype the prediction was waiting
for exists. What the three relations cost now is unmeasured, which is a different statement from the
one the file makes.

**The five-second number is not a budget this tree installs, and `ReadBudget` refuses to mean what
the target asked of it.** `DevMojo` installs three: `INTERACTIVE_READ_BUDGET` at 3 s for every
keystroke-grain read, `SESSION_READ_BUDGET` at 30 s for the diagnostics drain, and
`MCP_READ_BUDGET` at 60 s for an agent's turn. The interactive budget's javadoc states what the
number is for, that the target is a query which would otherwise never return and that a threshold
tight enough to police slowness would start refusing correct answers on a loaded machine; the other
two state their own rationales, and the argument here needs only the one. A target
phrased as a `ReadBudget` therefore asks the shipped mechanism to be a latency policy, which its own
documentation declines to be. A threshold for research belongs to the instrument that measures, not
to the guard that ships.

So the title changed with the premise. The file was
"No relation a consumer reads refuses a five-second budget with nothing materialized"; citations to
that phrasing in the predecessor item and in the changelog are the same item under its measured
subject.

## What changes when this lands

**An author reaching for a registration is told what the schema costs without it, by the build, in
the dimension where the answer is not a matter of taste.** `report-inline-multiplicity` already
counts relation instantiations per read from the DDL alone, needing no database and no profiler, and
prints a ranking on every roadmap-tool run. It counts one arm: the schema as it ships, where every
registered target is a table. It gains demotion arms, the same count with a chosen set of
registrations demoted to the `_live` view that states each rule, and off those arms two figures per
registration, defined under "The report prices each registration twice" below. The figures are what
a registration is worth in plan size, with no store and no timing.

**Two rules that were only ever tested in their storage form get their rules examined**, which is
the mistake the predecessor item exists to name and did not finish clearing. Details under
"The two rules nobody has examined".

Nothing a reader asks the store changes: the same relations, the same rows, under the same names.
A consumer's observable gain is on the two rules, not on the metric, and the metric is what stops
the next twenty registrations arriving unpriced.

## Why the counterfactual arm and not the bench

The predecessor item's closing section says a rule bench that prices a relation as a view against a
captured store belongs in the tree. The audit it points at says the opposite about the apparatus that
took its figures, that none of it is reactor code and none of it belongs there, because it measures
one consumer's store against schemas from several days of the tree. Both are right about different
halves, and the split is what this item is built on.

What cannot live in the tree is anything whose input is a captured consumer store. A capture needs
the consumer's sources, its catalog and its build, none of which are in this repository and none of
which should be. A wall-clock figure taken against one is research evidence, and the predecessor
item's own test section states the rule it lives under: a duration is not a build assertion.

What can live in the tree is every question the schema text answers on its own. Plan size is one,
and it is the question that decides the headline claim: the unregistered arm's failure is a plan that
cannot be built, not a read that is slow, so it is fully diagnosable from the DDL. Adding the
counterfactual arm to a metric that already ships beats building a bench that cannot run in CI, and
it is what makes the pricing requirement the predecessor item hands to the ownership item mechanical
rather than a convention nobody can check.

**Two figures already in the tree say the metric needs the arm and needs to be a tool rather than a
measurement.** The fact-model page records the shipping schema's largest statement as 963 and the
fully demoted schema's as 2739455. Running `report-inline-multiplicity` on the tree today reports
`intent_argmapping_projection_defect` at 368 as the heaviest, and the demoted arm as specified here
puts the fully demoted maximum at 2792329 on `intent_mutation_write_agreement`, so both figures have
rotted. They rotted for a good reason, the supertype captures having collapsed unions the count was
compounding, which is precisely why the numbers want to be a reported metric that moves with the
schema instead of prose in a document.

## The two rules nobody has examined

Both were found on the fresh capture, both are named as plan defects rather than as timings, and
neither has had its rule looked at. The predecessor item states them as unfinished and this item
takes them.

**The demand and exemption family names the expansion union many times over and asks its questions
as correlated existence tests.** Counted over the six view bodies from `intent_field_demand_rule`
through `intent_resolved_type_demand`: 13 references to `intent_expanded_type`, 12 to
`intent_expanded_field`, and 10 `EXISTS` terms. `intent_resolved_type_demand` at 111 and
`intent_type_demand` at 106 sit in the metric's top eight today. The work is to state what each
`EXISTS` is asking and whether it is asking it in a form a planner can answer, which is the rung-3
question, and to reach for a registration only if the answer is that it cannot.

**The probes resolve on the partition dimension alone.** Where a probe's only equality is the graph
partition, a single-graph consumer selects the whole relation, so the predicate reads as a scope and
prunes nothing. What is owed is the enumeration of which probes those are and, for each, either a
key the probe could match on or a statement that no narrower key exists.

Both are held by row identity: the relations answer with the same rows before and after, on the
fixture and on the sakila example. Neither is held by a duration.

## Implementation

**`InlineMultiplicityCheck` in `roadmap-tool` gains demotion arms.** It parses the DDL today,
distinguishes `CREATE VIEW` from `CREATE TABLE`, and treats a registered target as a table by
construction because that is what the schema says it is. A demotion arm takes a set of registrations
to demote, read as target-to-source-view pairs off the `INSERT INTO meta_materialize` seed rows,
rewrites every reference to a demoted target into a reference to its `_live` view, and runs the same
multiplication. The figures below need the fully demoted arm and the twenty single-registration
arms at each end, forty-two multiplications counting the shipped arm, and each is one memoized walk
over the view graph, so cost is not a consideration. No arm outgrows an `int`: the largest count any
arm reaches on today's schema is the fully demoted maximum of 2792329, three orders of magnitude
inside the boundary. The accumulator stays an `int` and the arithmetic goes through
`Math.multiplyExact` and `Math.addExact`, so a schema that someday does cross the boundary fails the
build loudly instead of wrapping.

**The report prices each registration twice, and neither figure is a difference of global maxima.**
Round 1 implemented the statistic this file used to specify, the rise in the largest statement any
relation reaches when one registration is demoted, and found it reads zero for fifteen of the twenty
registrations, because demoting one registration moves the global maximum only when it lifts the
single reigning relation or lifts something past it. The zeros were not findings about the
registrations, so the statistic is replaced by a pair, each a maximum over relations rather than
the change in the schema's one maximum:

- *The marginal figure.* Demote this registration alone, everything else as it ships, and report
  the largest rise any single relation's count takes, naming the relation that takes it. A demotion
  turns a leaf into a subtree, so no count falls, and the maximum is zero exactly when no relation's
  plan changes at all, which is the definedness the round-1 finding asked for. On today's schema it
  is non-zero for all twenty, from 8 (`intent_argument_scope_table`, taken on
  `intent_node_id_instruction_live`) to 657 (`intent_node_id_instruction`, taken on
  `intent_condition_param_decode`). This is the figure a reviewer of a retirement needs: what gets
  heavier, and by how much, if this row goes today with the rest of the register standing.
- *The sole figure.* Register this registration alone into the otherwise fully demoted schema, and
  report the largest drop any single relation's count takes against the fully demoted arm, naming
  the relation. Also non-zero for all twenty, from 736 (`intent_field_column_scope`, taken on
  `intent_field_column_table`) to 2792316 (`intent_mutation_write_destination`, taken on
  `intent_mutation_write_agreement`). This is the figure an author adding the first registration
  over a subtree is claiming: what the truncation buys when no neighbour shields it.

The gap between the two is the compositional term the retired statistic was blind to, and it is the
register's normal case rather than an edge one: registrations truncate each other's trees, so worth
is held jointly. `intent_mutation_write_destination` marginally buys 52, its neighbours already
truncating nearly everything above it, and alone buys 2792316. The two ends bracket the
registration's worth in every intermediate state of the register, a demotion only ever growing the
trees a registration truncates, so per relation the drop from registering it is smallest with
everything else registered and largest with nothing else registered. What the pair means is stated
on the report itself: the marginal figure prices one retirement against the register as it ships and
does not sum, so retiring a second row means re-running the report after the first, which the tool
makes a command rather than a project; the sole figure is the ceiling on what the row can be worth,
and a row small on both ends is the honest signal a registration never paid its way. A row small
marginally and large alone is worth what its neighbours leave it, which is a fact about the register
rather than a defect in the metric.

**The two rules above are restated where restating them is what the shape asks for**, and left alone
where the examination concludes the rule is already in the form the planner wants. An examination
that changes nothing is a result and gets recorded as one; this item does not owe a rewrite per
defect.

**The fact-model page's two stale statement-size figures are replaced by a pointer to the metric.**
A figure in prose that the schema moves under is what rotted; the page states the mechanism and the
lever order, and the numbers come off the tool. The same edit takes the page's refresh-statistics
passage, which says "twenty-two registrations" three times where the register holds twenty. Those
counts date the measurements they describe, taken when the register held twenty-two, so the fix is
to mark them as counts at measurement time or rephrase them off the live register size, not to swap
numerals under measured claims.

## Tests

- `InlineMultiplicityCheck`'s arms get a test in `roadmap-tool` over a stated miniature schema
  rather than over the shipping DDL: a base table, a rule naming it twice, a registration over that
  rule, a second rule naming the first registration's target twice, a registration over that, and a
  reader above both. Six relations are the smallest schema where the round-1 failure is visible:
  demoting the lower registration alone moves nothing above the upper one, so its marginal figure is
  small while its sole figure carries the product, and both ends of the bracket are checkable by
  hand in the lines that produced them. A case over the shipping schema would pin a figure that
  moves whenever a view is added.
- The demotion arms' register parse is held against the booted store rather than against its own
  regex: the pairs it reads out of the DDL text must equal `Materializations.registrations` as the
  store reports them. That is the assertion that catches the seed-row format changing, and it is the
  reason this half of the work is worth a test at all.
- In the same habitat, the reach each figure claims is held against the AST walk the refresh order
  is already built on: every relation whose count moves in a single-registration arm must be one
  `MaterializeDependencies.registrationsReachedByView` reports as reaching that registration. That
  catches the textual rewrite counting a name the parser does not read.
- Row identity on any relation the two examinations restate, on the fixture and on the sakila
  example, at the counts already recorded for them.
- `DerivedReadCostTest`'s directional claim covers any restatement automatically, and its
  `KNOWN_NON_MONOTONIC` set is where a restatement that clears a pinned pair shows up. A cleared
  pair is a row that goes, and the set is asserted by equality, so the build fails until it does.

No wall-clock assertion anywhere, for the reason the predecessor item's test section states: a
duration is not a build assertion, and every timing this item quotes is research evidence taken
against a captured consumer store which is not a fixture and is not in this repository.

## What this item does not do

**It does not build the bench.** A wall-clock instrument whose input is a captured consumer store
cannot run in CI, cannot be pointed at anything this repository contains, and has no fixture. The
research apparatus that took the predecessor item's figures is correctly outside the tree and stays
there; what comes into the tree is the half whose input is the schema.

**It does not take a position on any registration's fate.** The figures are numbers, and reading
them as a retirement is the ownership item's decision to make with an owner attached. Thirteen of the twenty
targets hold no rows at all on the consumer capture measured, which is a stronger argument about
several of them than plan size is, and it is not this item's argument to make.

**It does not add the priced-reason gate over `meta_materialize.reason`.** That requirement is
handed to the ownership item, and eighteen of the twenty reasons already carry an unregistered price
in prose. The two that do not are `intent_input_field_carrier_role_live` and
`intent_node_id_decode_column_live`. Naming them here is provenance for whoever writes the gate, not
a claim on the work.

**It does not restate the register's own recorded prices.** Several are stale and the predecessor
item says so; a wall-clock figure in a `reason` is a figure only the consumer store can refresh.

## Inherited questions this item does not close

Two questions arrived with the file and neither is answerable from the repository, so both are
recorded rather than planned.

Whether `intent_spelled_table`'s registration is still priced correctly now that the relation it
reads has a grain. Its reason records the broadest fan-out in the register, thirty-two readers about
half again as dear without it, and no re-measurement since the grain landed.

The planner degradation observed on `intent_resolved_type_binding`. Its reason records removing it
alone taking the refresh from about a second to forty-seven minutes and a reader past a sixty-second
budget. The marginal and sole figures will say what that registration is worth in plan size, which
is a different quantity from the one the reason records and does not replace it.

Both get an answer the next time somebody takes a capture, and the metric this item lands is what
tells them which relations to time.

## Reviewer findings

### Round 1 (2026-09-01, Spec -> Ready, reviewer session 01Cvmoe2Dzwtbfbc8YcPgYJb)

Verdict: withhold. One blocking finding on question two, which also undercuts the outcome
question one asks about. The rest of the plan checks out against the tree, including every
figure it quotes off the DDL.

**Blocking: the per-registration delta, as this file defines it, reads zero for fifteen of the
twenty registrations, and the five it does not read zero for are not the five that matter most.**

The definition under "The report gains a per-registration delta" is a difference of global
maxima: "the largest statement any relation reaches with it registered against the largest with
it demoted and every other registration left as it ships." Demoting one registration moves that
number only when it lifts the single reigning relation, which is
`intent_argmapping_projection_defect` at 368, or lifts something past it. For most registrations
it does not, so the statistic is 0 even though the demotion makes real relations much heavier.
Implementing the arm exactly as specified and running it on the current schema gives a non-zero
delta for `intent_resolved_type_binding` (+375), `intent_node_id_instruction` (+328),
`intent_carrier_data_field` (+246), `intent_spelled_table` (+30) and
`intent_input_field_filter_role` (+20), and exactly 0 for the other fifteen. Those zeros are not
findings about the registrations. Demoting `intent_argument_column_scope` alone takes
`intent_argument_column_match_live` from 6 to 90, and its reported delta is still 0.

The cause is structural rather than a matter of picking a better threshold, and it is the same
compounding this item is built to expose. Registrations truncate each other's trees, so their
effects compose multiplicatively rather than adding: with every registration demoted the largest
statement is 2792329, while the one-at-a-time effects on the global maximum sum to under a
thousand. A registration sitting beneath another one buys nothing on its own, because the one
above it already truncates the tree at its own name; its worth only appears once its neighbour
goes too. A marginal one-at-a-time delta measured against a global maximum is blind to exactly
that effect.

Both use cases this file names for the figure invert under it. "The figure an author adding a
registration is claiming" would show fifteen zeros and read as a register that buys nothing.
"The figure a reviewer of a retirement needs" would show 0 against
`intent_mutation_write_destination`, whose own `_live` stands at 1396159 in the fully demoted
arm. A metric that under-reports in the retirement direction is worse for this item's purpose
than no metric, because the ownership item this file hands the number to is the one deciding
fates.

What would satisfy the finding is a defined statistic that is non-zero whenever a registration
measurably changes plan size for some relation. Which one is the author's call, and the arms are
not equivalent: a per-relation delta (the largest rise any single relation takes, rather than the
rise in the largest relation), an aggregate restricted to the readers that actually reach the
target, which `MaterializeDependencies.registrationsReachedByView` already computes and
`DerivedReadCostTest` already uses as an axis, or a marginal figure reported alongside something
that shows the compositional term. Whichever it is, the plan should say what the number means when
two registrations are only worth anything jointly, since that is the schema's normal case rather
than an edge one.

**Non-blocking, but it rests on a false premise and is an instruction to the implementer, so it is
yours rather than mine to correct.** Under "`InlineMultiplicityCheck` in `roadmap-tool` gains the
demoted arm": "Counts in the demoted arm exceed the range of an `int`, so the accumulator is a
`long` on both arms." The fully demoted maximum is 2792329, three orders of magnitude below
`Integer.MAX_VALUE`, and single-registration demotions are bounded above by it. This file quotes
2739455 for the same quantity two sections earlier, which also fits in an `int`, so the claim
contradicts its own figure. A `long` is harmless in itself; the stated reason for it is what is
wrong, and an implementer would encode a false belief about the schema's size.

Two things noticed in passing, neither a gate matter. `fact-model.adoc` says "twenty-two
registrations" in the refresh-statistics paragraph while the register holds twenty; that is a
different staleness from the two statement-size figures this item already plans to replace on
that page, and it is adjacent to the edit. And the claim that each of the three budget javadocs
says the target is a query that would otherwise never return holds for
`INTERACTIVE_READ_BUDGET` only; the other two state their own rationale. The argument the section
builds survives on the one javadoc, so this changes nothing but the count.

### Author response to round 1 (2026-09-01)

All four findings taken; the blocking one by replacing the statistic rather than defending it.

**The difference of global maxima is gone.** The section now titled "The report prices each
registration twice" defines a pair of per-relation maxima: the marginal figure (demote one, largest
rise any single relation takes) and the sole figure (register one into the fully demoted schema,
largest drop against that arm). Reproduced independently this session from the DDL: both figures are
non-zero for all twenty registrations, the marginal ranging 8 to 657 and the sole 736 to 2792316,
and every figure the review quotes reproduces exactly, the shipped maximum of 368, the fully demoted
maximum of 2792329, the five non-zero global-max deltas and the fifteen zeros,
`intent_argument_column_match_live` going 6 to 90 under `intent_argument_column_scope`'s demotion,
and `intent_mutation_write_destination_live` at 1396159 fully demoted. The finding's closing ask,
what the number means when two registrations are only worth anything jointly, is answered in the
section: demotion only grows the trees a registration truncates, so the two figures are the ends of
a bracket over every intermediate register state, the gap between them is the jointly held worth,
and the marginal figure does not sum, so a second retirement re-runs the report. The miniature test
schema grew to two stacked registrations so exactly that case is pinned by hand-checkable
arithmetic, and a new assertion ties the relations a figure moves on to
`MaterializeDependencies.registrationsReachedByView`, the reach axis the review pointed at.

**The `long` justification was false and is replaced by the truth.** The largest count any arm
reaches today is 2792329, well inside `int`; the accumulator stays `int` with `Math.multiplyExact`
and `Math.addExact` so an overflow on a future schema fails loudly instead of wrapping.

**Both passing notes are folded in.** The budget paragraph now attributes the never-returns
rationale to the interactive budget's javadoc alone, and the "twenty-two registrations" staleness
joins the fact-model page edit this item already owes, with the caution that those counts sit under
measured claims and want dating or rephrasing rather than a numeral swap.

### Round 2 (2026-09-01, Spec -> Ready, reviewer session 011zrnHVVbsRGNmsuEmJckms)

Verdict: sign off. Both gate questions are answered. The replaced statistic was re-derived
independently from the DDL this session rather than read off the file, and every figure the file
quotes reproduces exactly, including the pair being non-zero for all twenty; the monotonicity and
bracket properties the file argues from hold under test rather than merely reading as plausible.
Three non-blocking notes, none of which changes what gets built.

**Non-blocking, and it is a false cost claim attached to an instruction to the implementer, so it is
the author's to correct rather than mine.** Under "`InlineMultiplicityCheck` in `roadmap-tool` gains
demotion arms": "each is one memoized walk over the view graph, so cost is not a consideration". One
walk over today's schema costs about a second, measured on the shipping goal itself
(`report-inline-multiplicity` on this tree, 1244 ms including JVM start), so forty-two walks is forty
to fifty seconds added to `roadmap-tool`'s `verify` phase. Every build reaching verify pays it, and
that module is half of the cheap scoped build CLAUDE.md prescribes for a roadmap-only diff. Pattern
caching buys nothing here (1062 ms against 1061): the cost is `references()` scanning all 287
relations against each of 117 view bodies, repeated per arm.

What makes the claim true is a mechanism the file does not specify. The arms need no textual rewrite
at all, a demotion being edge retargeting on an already-parsed graph: parse references once, then per
arm redirect every edge pointing at a demoted target to that target's `_live` view and re-run the
multiplication. Measured this session, that reproduces all six figures the file quotes (368,
2792329, marginal 8 to 657, sole 736 to 2792316, `intent_mutation_write_destination` marginal 52,
zero zeros on either figure) and costs 9 ms for all forty-two arms on top of the single parse the
report already pays. The two mechanisms are equivalent by construction, the rewrite substituting
names one-for-one where retargeting substitutes edges, so this is not a choice between arms with a
trade-off to settle. It does reach one Tests bullet: the reach assertion's stated rationale, that it
"catches the textual rewrite counting a name the parser does not read", guards a hazard edge
retargeting removes by construction. The assertion stays worth making on its own terms; its reason
changes.

**The changelog carries no citation to the old phrasing.** Under "So the title changed with the
premise": "citations to that phrasing in the predecessor item and in the changelog are the same item
under its measured subject". The predecessor-item half is right, `derived-read-cost-is-a-shape-problem.md`
naming the target as R899's as filed and recording that the arms retired it. `roadmap/changelog.md`
carries neither the phrasing nor any mention of this item, R876 not having landed. Orientation prose
rather than an instruction, so nothing downstream hangs on it.

**"The ownership item" has no roadmap item behind it.** It is named three times as the recipient of
work this item declines: the priced-reason gate over `meta_materialize.reason`, the retirement
decisions, and reading the figures as fates. The predecessor item states "The register is ownerless"
as a finding but files no item for it, and its "Filed out of this item" list names R899, R900, R901,
R895 and R896 only. Nothing in this item's own scope depends on that item existing, which is why
this is a note and not a finding: the metric lands and prints either way. What does rest on it is the
file's "the metric is what stops the next twenty registrations arriving unpriced", which that item
delivers rather than this one, so the sentence currently describes a hand-off with no destination.
Filing the item is Backlog work any session can do.

### Round 3 (2026-09-01, Ready -> Spec reopen, session 01D6nTadyDFtGpxbjF2SBzdR)

Verdict: reopen. This is not a finding against the plan as reviewed. Round 2 signed off correctly on
what it could see; the foundation the plan prices moved afterwards, and the change is large enough
that the existing approval no longer covers the shape of the work.

Disclosure: this session is R876's author, and R876 is the item whose finding triggers the reopen.
`Ready -> Spec` is unguarded by design, but the next `Spec -> Ready` sign-off should come from a
session with no stake in R876.

**The unit of account is under active revision.** This item prices one `meta_materialize` row at a
time: a registration against the `_live` view that states its rule, twenty of them, with the
population and the naming taken from the register as it ships. R876 has since walked all 114
`intent_` rules down to the base relations they bottom out at, and found that the derivation gatherer
has no input the graphitron gatherer lacks. No rule in the family reads the configuration corpus,
Java sources or the compiler, which are three of the six dependencies that gatherer declares. Its
conclusion is that `intent_` collapses into `graphitron`, and that `meta_materialize` then loses its
subject rather than its justification: the register exists to schedule refreshes for relations whose
owner runs too late to schedule them itself, and under one derivation gatherer there is no such
relation.

**What that does and does not do to this item.** The instrument survives. "How large a statement does
the planner build for this rule as a view" is a question about a rule, not about a register, and it
stays worth answering from the DDL whether the answer feeds a registration decision or an owner's
choice to store, index or leave alone. What does not survive unexamined is the framing. The unit is a
`meta_materialize` row, the population is "the twenty", the counterfactual arm is named after `_live`
views, and the stated outcome is that "the metric is what stops the next twenty registrations
arriving unpriced". With no register there is no twenty-first registration to stop, and that sentence
needs a different subject.

**This sharpens Round 2's open note rather than leaving it.** That round recorded that "the ownership
item" is named three times as a recipient with no roadmap item behind it, and judged it a note
because the metric lands either way. The recipient is R876, which is In Progress and still revising
its own conclusions. That is the more precise version of the same problem: the hand-off has a
destination and the destination is not settled.

**What the revision owes.** Not a rewrite. Three decisions. Whether the arm is specified per
registration or per rule, which is the difference between a metric that dies with the register and
one that outlives it. What the population is, once "the twenty" is not a stable set. And whether this
item waits on R876 reaching In Review, or ships the rule-level metric first on the grounds that it is
the instrument either way. The third is a real fork and the answer is not obvious: the metric is
arguably worth more before the collapse than after, because it is what would let an owner price the
choice the collapse hands them.

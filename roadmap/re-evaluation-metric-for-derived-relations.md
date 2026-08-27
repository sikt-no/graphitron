---
id: R849
title: "Measure re-evaluation rather than naming, so a materialization cut set can be chosen on evidence"
status: In Review
bucket: architecture
priority: 2
theme: model-cleanup
depends-on: []
created: 2026-08-27
last-updated: 2026-08-27
---

# Measure re-evaluation rather than naming, so a materialization cut set can be chosen on evidence

Every materialization registration in the fact store is argued from a measurement of one relation
against the tree as it stood. There is no instrument that says what the register does as a set, so
there is no way to propose a different set and no way to tell whether a registration still earns its
place. R848 states that problem. This item builds the instrument it needs.

Two instruments already count **namings**: how many times H2 instantiates a rule when a reader is
evaluated, H2 inlining a view wherever it is named and eliminating no common subexpression. That is
one of the three mechanisms the register's own reasons cite, and both instruments are blind to the
other two. Naming them apart is the first thing this plan owes an implementer, because they are not
the same quantity and only one of them is in the tree.

## The two naming metrics, and which one this item is about

**The shipped one** is `no.sikt.graphitron.roadmap.InlineMultiplicityCheck` in `roadmap-tool` main
scope, tested by `InlineMultiplicityCheckTest` and bound by `roadmap-tool/pom.xml` to `verify` as the
`report-inline-multiplicity` execution, so it runs in every full build. It scans the authored DDL
textually, counts each relation's references per `CREATE VIEW` body, multiplies down the tree, and
prints the fifteen heaviest relations. A materialized relation drops out by construction, its
canonical name being a table. It reports and does not gate, deliberately, because it
over-approximates: it counts textual references without knowing which arms a predicate prunes.

**The uncommitted one** is a probe written while filing R848 and never committed. It reads H2's
*normalized stored definitions* out of a booted store rather than the authored text, sums over the 48
root readers rather than reporting per view, and can redirect each registered target to its `_live`
view to produce a no-materialization counterfactual, which is where 470 and 913,978 below come from.

They disagree, and the mechanism matters because it lands inside slice 1.
`InlineMultiplicityCheck` counts what an author typed;
`MaterializeDependencies.relationsReadBy` collects off the normalized definition and returns a
`Set`, discarding multiplicity along with position. So the multiplicities available to the new
instrument are H2's after normalization, not the authored ones, and **slice 1 has to recover
multiplicity as well as position**. Neither basis is wrong. They answer different questions, and no
arithmetic reconciles their totals.

This item is about **neither of them**. It is about the weighted instrument that replaces the
uncommitted probe. `InlineMultiplicityCheck` is not touched by this item at any outcome, which the
acceptance section below states explicitly.

## Vocabulary

A **derived relation** is a view in the fact store: a rule stated once in SQL, evaluated whenever a
reader names it. A **registration** moves the canonical name onto a table refilled from the rule once
per capture, so readers stop evaluating it. A **root reader** is a view no other view names, which is
where a real read enters the derivation. **Re-evaluation** is the thing being counted throughout: how
many times the engine actually executes a rule's body during one read, which is not the same as how
many times an author wrote its name.

## What the naming metric says

Computed by the uncommitted probe over 107 views, 168 base tables, 20 registrations and 48 root
readers. Both figures are total rule instantiations summed across every root reader, counting H2's
normalized references with multiplicity. They are not comparable with `InlineMultiplicityCheck`'s
per-view ranking, whose heaviest single view already exceeds the first figure here.

**These figures are pinned to the tree they were taken on and are not restated per run.** That tree
declared 107 views; trunk declared 109 within a day, two having landed while this item sat in review.
Swapping the census while leaving the totals would misattribute them, and re-taking the totals on
every census move is work with no reader. Nothing downstream depends on their being current: the
acceptance gate compares against magnitudes recorded in the DDL rather than against anything measured
per run. The drift is also the item's own thesis happening again while the item waits for it.

| | Rule instantiations |
|---|---|
| As evaluated today, 20 targets standing as tables | 470 |
| With no materialization at all | 913,978 |

The distribution is the finding rather than the ratio. A single family dominates: the five worst root
readers are all in the write-payload chain and account for about 98% of the total. A greedy search,
adding whichever registration cuts total expansion most, reaches 595 with twelve registrations
against the 470 that twenty buy.

## The finding that justifies building the real thing

The same probe was run two days apart, across a tree that gained exactly one registration in between,
and the pair says something no per-relation measurement could.

`intent_field_scope_table` was registered by an increment whose subject was not materialization. On
the tree before it, `intent_argument_scope_table` was the single largest lever in the whole register:
greedy step one, taking total instantiations from 910,497 to 78,457. On the tree after, that role
belongs to `intent_field_scope_table` (913,978 to 77,209), and `intent_argument_scope_table` has
fallen to a marginal value of **+17** and last place in the greedy order.

Registering the lower relation absorbed almost all of the upper one's value. The two are near
substitutes and the register holds both, each with its own reason arguing its own case, neither
mentioning the other. Nobody did anything wrong and nobody could have noticed: the value of a
registration is a function of which other registrations exist, and no per-relation measurement can
see that.

This also shows why the marginal column is the wrong summary to act on. `intent_field_scope_table`
scores +9 marginally and is the largest lever in the register. Registrations are substitutes, so
dropping-one-at-a-time systematically understates every relation that has a near-twin.

## Where a naming metric is blind, and why that disqualifies both of them

Three mechanisms put registrations in the register. A naming metric, on either parsing basis, models
one. Nothing below turns on the difference between the two, which is why they are separated once
above and treated together here.

| Mechanism | What re-evaluates the rule | Naming metric |
|---|---|---|
| Breadth | a rule named N times is expanded N times | counted correctly |
| Per-row | a derived relation on the inner side of a join, or a correlated probe, is evaluated once per driving row | **counted as 1** |
| Recursive | a view named in a recursive term or its anchor is re-expanded per iteration | **counted as 1** |

Check the blindness against the register's own timings. `intent_mutation_write_destination` scores
+10 and its reason records 12983 milliseconds falling to 5.4. `intent_field_reference_step_hop`
scores +36 and its reason records `intent_node_id_decode` falling from about fifty seconds to about
thirteen. Those are the two registrations bought for per-row and recursive re-evaluation, and the
metric ranks them tenth and fifteenth of twenty by marginal, mid-table and below, against reasons
recording two of the largest measured wins in the register.

So a naming metric is a map of one mechanism, useful for locating where breadth concentrates and
unusable for choosing a cut set. Committing the probe as-is, or reading a cut-set decision off the
shipped report, would repeat the error the store-performance skill already records twice: a count
that is real, and a reading of it as cost that is wrong. `InlineMultiplicityCheck` does not make that
error itself, its javadoc saying outright that it reports rather than gates because the metric
over-approximates; the error would be a reader's.

## What to build

A metric that weights each naming by its **position**, because position is what decides how many
times the engine runs the body.

Parse each stored view definition with jOOQ's parser and classify every relation reference as one of:
plain (evaluated once per naming), inner-side-of-join (once per driving row), correlated (once per
outer row), or recursive-term (once per iteration). `MaterializeDependencies` already parses stored
definitions this way and walks them, collecting table references off the query object model rather
than off text, so the walk and its normalization rules are established; what is new is retaining each
reference's position instead of discarding it.

Weight the three re-evaluating positions by the driving side's cardinality, which the store can count
because it holds the rows. That makes the metric population-dependent, and that is correct rather
than regrettable: the register's own reasons record that a synthetic fixture twelve clusters wide
understates a per-row probe by as much as it takes to turn four seconds into no termination at all. A
metric that ignores population would inherit exactly that error.

Home: `graphitron-model`, test scope, beside the instruments that already live in
`no.sikt.graphitron.model.test` (`UnregisteredRelation`, `RunawayRelation`, `FactStores`). It is a
research instrument and nothing at runtime needs it. Promoting it to main scope, or to an MCP
surface, is a separate question and should not be settled here.

**Why not extend `InlineMultiplicityCheck` instead**, which is the module that already holds a static
metric over this DDL.

Not for want of dependencies, which is what an earlier draft of this plan claimed and got wrong.
`roadmap-tool` depends on `graphitron-model` at compile scope, so jOOQ is on its classpath; it
carries `org.duckdb:duckdb_jdbc` (named as evidence about that module's dependencies and nothing
more: this instrument runs on H2 through jOOQ like everything else in `graphitron-model`, and nothing
in this item uses DuckDB or proposes a change of engine, the fact store's own being out of scope
however much the register's reasons blame its inlining); and it already opens a populated store on
every full build,
`SchemaIdentifierDriftCheck` and `SchemaReferencePages` both calling `GraphitronModelStore.open()`
with `check-schema-identifiers` bound to `verify` beside `report-inline-multiplicity`. Both inputs
this instrument needs are present there today. The "needing no database and no profiler" premise
belongs to `InlineMultiplicityCheck` and describes that check, not the module around it.

The grounds are the instrument's collaborators and its cadence.

**The walk being extended is `MaterializeDependencies.relationsReadBy`**, `graphitron-model` main
scope. Extending it from another module means duplicating the walk or widening its API for a single
caller, and the H2 normalization rules it encodes are exactly the part that must not be re-derived
independently, the two existing metrics already disagreeing because their bases differ.

**The counterfactual's collaborators are test scope in that module.** Scoring a candidate cut set
means evaluating the registered and unregistered shapes of a relation, which is what
`UnregisteredRelation` does, over a store from `FactStores`; both are `no.sikt.graphitron.model.test`,
republished as a test-jar so downstream modules build on that floor rather than opening a store of
their own. `report-inline-multiplicity` runs from `roadmap-tool` **main** scope through `exec:java`,
and main cannot reach test-scope instruments. Hosting the weighted metric beside it therefore means
reimplementing the swap in main scope, or putting it in `roadmap-tool` test scope where it is neither
a build step nor near anything it collaborates with.

**Cadence decides what is left.** Every `roadmap-tool` execution is bound to `verify`, so the module's
shape is build steps. This instrument answers a question somebody is asking at the time they ask it,
and its store boot and cardinality counts are not costs to add to every build. A class can sit
unbound in that module, which is why this is the third ground rather than the first.

So the two coexist on purpose, with different jobs and different cadences: a DDL-only report that
runs every build and catches an authored-multiplicity regression, and a store-dependent instrument
run on demand when a cut set is in question. What would make the shipped one redundant is a weighted
metric that runs without a store, which this plan does not propose and does not believe in, the
population being the reason the register's own reasons record a fixture understating a per-row probe
by orders of magnitude.

## Acceptance: the metric must reproduce a known ranking

This is the gate, and the item fails honestly rather than shipping a plausible number that nobody can
check.

Most rows of `meta_materialize` carry a measured before-and-after in wall clock. Those timings were
taken on different trees and different schemas, so they are not comparable as figures. What survives
that incomparability is a **coarse class**, because the classes below sit orders of magnitude apart
and no cross-tree noise flips one into another.

**Classify all twenty first, then score.** Both the class boundaries and the twenty assignments are
fixed and written into this item below, before the metric is run, so the gate cannot be tuned to the
answer it produces. Class on the **absolute
saving** a reason records rather than on the ratio: a cut set is chosen to reduce total time, and a
large ratio on a small base is not evidence of value.

| Class | What the reason records |
|---|---|
| **A** | a read or capture that did not terminate, or timed out |
| **B** | a saving of a second or more |
| **C** | a saving under a second |
| **U** | no saving stated: the reason argues breadth with no figure at all, or its figures price the refresh rather than the win |

Here are the twenty, classified from the reason text in `meta_materialize` as it stands today. The
assignments are in this item rather than promised to slice 3, so the gate is a written-down
prediction and not a thing scored after the fact. The evidence column quotes what the reason records,
so a Done reviewer can audit every row against the DDL without re-taking a measurement.

| Registration | Class | What the reason records |
|---|---|---|
| `intent_resolved_type_binding` | A | the census-driven join over it did not finish inside a five-minute timeout |
| `intent_node_id_decode_hop_column` | A | the walk does not finish inside a two-minute timeout |
| `intent_node_id_instruction` | A | the decode slot went from not answering in 400 s to 278 ms |
| `intent_mutation_payload_refusal` | A | the capture did not finish, twenty-three minutes of CPU with no output |
| `intent_mutation_write_destination` | B | 12983 ms to 5.4 |
| `intent_field_scope_table` | B | 6167 ms to 342 |
| `intent_input_field_filter_role` | B | 4.9 s to 56 ms |
| `intent_errors_field` | B | the carrier read about 49 s to under 7 |
| `intent_field_reference_step_hop` | B | `intent_node_id_decode` about 50 s to about 13 |
| `intent_mutation_payload_key_membership` | B | 1527 ms to 100 |
| `intent_argument_scope_table` | B | the reader's five seconds, one evaluation being 70 ms against 69 driving rows |
| `intent_field_column_scope` | B | `intent_field_column_table`'s ten seconds, one evaluation being about 170 ms |
| `intent_mutation_payload_column` | B | one read about 4 s, the matched key over it inheriting that and adding half a second |
| `intent_mutation_write_payload` | C | 266 ms to 37 |
| `intent_input_field_resolving_table` | C | the bare walk 39 ms to 1 |
| `intent_argument_column_scope` | C | 27 ms to 5 |
| `intent_argument_column_match` | C | 15 ms to 6 |
| `intent_argmapping_pair` | U | sixteen readers, fifty-five instantiations in one read, no figure |
| `intent_spelled_table` | U | six readers, thirty-nine instantiations, no figure |
| `intent_carrier_data_field` | U | timings price the refresh and a restructure, not the registration's own win |

**Three rows are worth stating precisely, because classifying all twenty is what exposed them.** The
last five B rows do not record a before-and-after the way the first eight do. `intent_argument_scope_table`,
`intent_field_column_scope` and `intent_mutation_payload_column` record the cost the registration
removes without an after figure; the after is a read of a filled table, which the register elsewhere
measures at under a millisecond, so the class is determined and not guessed. `intent_carrier_data_field`
is the one row the classes as first written did not cover, and it is why class U is defined above on
a missing *saving* rather than a missing timing. Its reason is dense with figures, and every one of
them prices either the refresh (about 170 ms for 15 rows, about 12 ms on a carrier-free schema) or a
restructure that landed in the same change (the carrier read falling from about 49 s to that 170 ms
by restructure alone). What the registration itself buys, the seat's five namings and the error
channel's per-producing-field probe reading rows instead of re-evaluating the rule, has no figure.
It is derivable at roughly five evaluations of a 170-millisecond rule, which would put it in B, and
deriving a class is exactly what this gate exists to stop. So it goes in U, and it is the first
relation the metric should be pointed at once it works.

**The ship condition is over all twenty, not over a triple: no registration may outrank one in a
higher class.** Class U is excluded from the comparison, having no evidence to compare against, and
must be listed rather than quietly dropped. Three of the twenty are in U, so the comparison runs over
seventeen: four A, nine B, four C.

**This discriminates, and the earlier version did not.** The probe fails it, on the same marginals
this plan already reports:

- `intent_input_field_resolving_table` (+18, class C, its reason recording a walk from 39
  milliseconds to 1) outranks `intent_mutation_write_destination` (+10, class B, 12983 milliseconds
  to 5.4). A 38-millisecond saving placed above a thirteen-second one.
- `intent_mutation_payload_refusal` (+39, class A, its reason recording a capture that did not finish
  at all, twenty-three minutes of CPU with no output) ranks ninth of twenty. Eight registrations
  outrank the one row in the register whose absence stops a build. Which classes those eight fall in
  is a count off a probe run this item does not carry the output of, so slice 3 restates it from its
  own run rather than this plan asserting it. The violation does not turn on that count: no class-A
  registration can sit ninth here without something in a lower class above it. Eight rows outrank it,
  at most three of them can be the register's other A rows and at most three the excluded U rows, so
  at least two are B or C.

**What the triple was, and what it now is.** An earlier draft made the ship condition an ordering
over `intent_mutation_write_destination`, `intent_field_reference_step_hop` and
`intent_argument_column_match`, and claimed the probe got it backwards at +10 and +36 against +4.
That claim was simply wrong: those three marginals are in the demanded relative order, and the probe
passes that test. So does `InlineMultiplicityCheck` on the obvious per-registration derivation, its
source-view subtree counts putting them at 28, 20 and 6. A ship condition passed by both metrics this
item exists to replace is not a gate. The triple survives below as an illustration of what the
blindness looks like, which is the job it can actually do: `intent_field_reference_step_hop` and
`intent_mutation_write_destination`, the two bought for recursive and per-row re-evaluation, rank
tenth and fifteenth of twenty by marginal (+36 and +10) despite their reasons recording among the
largest measured wins in the register. That is a statement about where they sit in the whole ranking,
which is the claim the gate now tests, rather than about their order among themselves, which was
never wrong.

**What the negative branch deletes**, stated exactly because the first draft of this plan left it to
implication. If the gate fails, the new weighted instrument and its tests are deleted, and the
uncommitted probe stays uncommitted. `InlineMultiplicityCheck`, its test, and the
`report-inline-multiplicity` build step are untouched: they are a build-bound reporting surface whose
retirement is a separate decision with a separate blast radius, and nothing this item measures bears
on whether that report is worth printing.

**Where a negative result is recorded.** Not in this file, which is deleted at Done. It goes in
`roadmap/changelog.md`, one of the three permanent roadmap artifacts, naming the item and what the
gate refused and why. Three things have to reach it rather than only the verdict: the corrected
inversion counts, including that the naming count ranks the top of the register better than the
weighted one; and the condition under which the retained positions should themselves be removed,
which names two open items by id and so cannot live in the javadoc that otherwise carries this
result, `RoadmapReferenceGuardTest` failing the build on an `R<n>` in a comment. A finding that a static count cannot rank these registrations is worth as much
to the next author as a working metric would have been, and it is the kind of result this tree loses
by default.

## Slices

1. **Position-and-multiplicity parse.** Extend the definition walk to retain each reference's
   position *and* its multiplicity, `MaterializeDependencies.relationsReadBy` returning a `Set` today
   and so discarding both. Pin it with cases over hand-written view bodies of each shape, so the
   classifier is tested against known answers before it is pointed at the schema.
2. **Weighting and the whole-register score.** Cardinality from the store, a total per root reader,
   and a cut-set score for an arbitrary candidate set.

   The regression test is **self-contained, not cross-tool**. On the same hand-written fixtures slice
   1 pins, with every weight forced to one, the instrument must produce the instantiation counts
   derivable by hand from those bodies. This deliberately replaces the first draft's "reproduces the
   naming metric's numbers", which named nothing runnable: the shipped tool computes per-view subtree
   counts off authored text, the uncommitted probe computes root-reader totals off normalized
   definitions, and no arithmetic takes either to the other. Agreement with a second implementation
   on a different basis is not a property worth asserting; agreement with a hand-derived answer is.
3. **The validation gate.** Rank the twenty registrations, compare against the reasons' recorded
   magnitudes, and record the outcome either way, in `roadmap/changelog.md` when it is negative.

Slices 1 and 2 are worth nothing without 3 and should not land separately from it.

## Risks

The classifier is the risk. H2's stored definition is already normalized and may not preserve the
distinction between a join whose inner side is derived and one whose inner side is a base relation in
a way the parser exposes. If it does not, slice 1 stops and the item needs a different reading of
position, possibly from `EXPLAIN` plan shape rather than from the definition. Establish this in slice
1 before building anything on top of it.

Cardinality weighting can also mislead in the other direction, inflating a relation whose driving
side is large but whose body is trivial. The gate is what catches that, which is another reason it
cannot be deferred.

## Relationship to R848

R848 asks which cut set the store should have. This item builds the only instrument that could
answer it on evidence, so R848 should not reach Spec before this one has produced a result. Stated
here rather than as a `depends-on` edge on R848, whose body is being actively worked by another
session.

## Reviewer findings

### Round 1 (2026-08-27, Spec -> Ready, reviewer session session_014R3TSfjFfZQzoms4otDrVn)

Verdict: withhold, on one finding. The design holds up and the goal is well communicated: nothing
changes at the consumer surface when this lands, and the plan says so, but what it unblocks does,
every capture and every language-server or MCP store open paying the register's evaluations, so an
instrument that can score a *set* rather than a relation is the thing standing between R848 and an
answer. The three-mechanism diagnosis is the right diagnosis, and the decision to weight a static
count rather than to reach for `EXPLAIN ANALYZE` scan counts is better founded than the plan claims:
the store-performance skill records that a scan count stops tracking cost exactly when a change
moves rows between a view and a table, "which is what every registration in the register does", so
the shipped scan-count instruments are the wrong primary here for a reason the tree already states.

### Round 2 (2026-08-27, Spec -> Ready, reviewer session session_014R3TSfjFfZQzoms4otDrVn)

Verdict: withhold, on one finding, in the one section Round 1's asks produced.

Three of the four asks landed and landed well. The referent section separates the two metrics
correctly on every detail I checked: `InlineMultiplicityCheck` does print the fifteen heaviest, does
count authored text, does report rather than gate on an over-approximation premise its own javadoc
states, and `MaterializeDependencies.relationsReadBy` does return a `Set` that discards multiplicity
along with position, which slice 1 now requires be recovered. Slice 2's regression test is now
runnable by the implementer who writes it, and the trade it names is the right one: agreement with a
hand-derived count over agreement with a second implementation on an incommensurable basis. The
negative branch says exactly what is deleted and what is not. Sending a negative result to
`roadmap/changelog.md` rather than to a file that Done deletes is right, and the changelog is one of
the three permanent roadmap artifacts, so it is a durable home rather than another transient one.

**1. Every stated ground of the home argument is false about `roadmap-tool`.** "Why not extend
`InlineMultiplicityCheck` instead" rests on two inputs being unavailable in that module: jOOQ's
parser for position, and a populated store for cardinality. Both are there, and both are already
used at `verify`.

- `roadmap-tool/pom.xml` depends on `graphitron-model` at compile scope, so jOOQ is on the
  classpath. The dependency's own comment says what it is for: "The fact store:
  render-schema-reference boots it from the DDL and reads the relation census, comments and meta rows
  back through the shared catalog reader". The module also depends on `org.duckdb:duckdb_jdbc`.
- `SchemaIdentifierDriftCheck` and `SchemaReferencePages` both call `GraphitronModelStore.open()`.
- `check-schema-identifiers` is bound to `verify` beside `report-inline-multiplicity`. Running
  `mvnd -pl roadmap-tool verify -Plocal-db` prints "check-schema-identifiers: 51 pages and 4402
  store prose values resolve against 275 relations in 13 families", so a populated store is opened
  in that module on every full build.

So nothing would be "added" to host a store-dependent instrument there, and the property the plan
says adding it would destroy is one the module does not have. The quoted premise "needing no database
and no profiler" belongs to `InlineMultiplicityCheck`, describing that check, and the plan reads it
as a property of the module.

This is a finding about the argument and not a request to move the instrument. I expect
`graphitron-model` is still right, on grounds the plan has available and does not use: the walk being
extended and the sibling test instruments both live there, this is on-demand research code that
should not sit on any `verify` path, and `roadmap-tool`'s store boot serves build-time documentation
rendering and gating, which is a different job at a different cadence from scoring a candidate cut
set. But that is the author's argument to write, and it lands somewhere the current one does not:
it argues from what the instrument is and where its collaborators live, rather than from a
dependency that is missing.

What would satisfy this: restate the home decision on grounds that survive the pom. If the true
grounds change the answer, say that instead.

#### Non-blocking

"which both naming metrics get backwards", in the acceptance section, claims more than the tree
supports. The first draft said "the naming metric", meaning the probe, and the +10 and +36 marginals
support it. The shipped tool produces no per-registration ranking at all, and on the obvious way to
derive one, each registration's source view subtree count, it gets the demanded order right rather
than backwards: `intent_mutation_write_destination_live` 28,
`intent_field_reference_step_hop_live` 20, `intent_argument_column_match_live` 6. Nothing in the gate
turns on the sentence.

Everything else checkable checked out. The census is exact: the DDL declares 107 `CREATE VIEW` and
168 `CREATE TABLE` statements, `meta_materialize` holds 20 rows with `intent_field_scope_table` the
twentieth, and 48 views are named by no other view. `intent_field_scope_table` was indeed registered
by an increment whose subject was not materialization (`02ec43c`, the condition membership fold).
Both cited timings are verbatim in the register: `intent_mutation_write_destination` records 12983
milliseconds falling to 5.4 and names its per-row re-evaluation, `intent_field_reference_step_hop`
records `intent_node_id_decode` falling from about fifty seconds to about thirteen and names its
recursive term, and `intent_argument_column_match` records the fifteen-to-six the plan contrasts
them against. Every symbol exists as named: `MaterializeDependencies` in `graphitron-model` main
scope parsing stored definitions with jOOQ's parser and collecting off the query object model,
`UnregisteredRelation`, `RunawayRelation` and `FactStores` all in `no.sikt.graphitron.model.test`,
and every relation the plan names as a view or a table in the fact schema.

**1. "The naming metric" has no runnable referent, and the one in the tree is not throwaway.** The
plan opens by reporting the naming metric as "throwaway code". A metric answering to that
description is committed and build-bound:
`no.sikt.graphitron.roadmap.InlineMultiplicityCheck` in `roadmap-tool` main scope, tested by
`InlineMultiplicityCheckTest`, bound by `roadmap-tool/pom.xml` to the `verify` phase as the
`report-inline-multiplicity` execution, so it runs in every full build. Its javadoc states the same
mechanism in nearly the same words, "H2 inlines a view wherever it is named and eliminates no common
subexpression", it computes relation instantiations per read from the DDL alone with a materialized
relation exempt by construction, and its report line prints the census this plan quotes: "107 views
over 168 tables".

Three places in the plan turn on which metric is meant, and each resolves differently.

Slice 2's regression test, "reproduces the naming metric's numbers when every weight is forced to
one", names nothing an implementer can run. The figures behind 470 and 913,978 came from code that
is not in the tree, and the shipped tool computes a different quantity: per-view subtree
instantiations, printed as a top-15 ranking, with no root-reader total and no
no-materialization counterfactual. Summing its per-view counts over the 48 root readers does not
land on either figure under any reading I could construct. Per-naming counts give 3219 as evaluated
today and 5,359,571 with each registered target redirected to its `_live` view; restricting the sum
to the 28 root readers that are not themselves registered source views gives 2397 and 3,043,899;
deduplicating references within a body gives 1208 and 350,295, or 766 and 142,629 restricted the
same way. The shipped tool's own heaviest single view is 899, already above the plan's 470 total, so
the two are not the same quantity whatever the counterfactual. This is not a claim that the plan's
figures are wrong. It is that slice 2 has to say which naming metric the extension must agree with,
and under what definition of the two totals, or the implementer picks one and the regression test
means whatever they picked.

The mechanism for why the two bases can legitimately disagree is worth stating, because it lands
inside slice 1. `InlineMultiplicityCheck` counts textual references in the authored DDL;
`MaterializeDependencies.relationsReadBy` collects off H2's normalized stored definition and returns
a `Set`, so it discards multiplicity as well as position. Slice 1 must recover both, and the
multiplicities it recovers are H2's after normalization rather than the ones an author typed.

The acceptance gate's negative branch, "the naming metric is deleted rather than kept as a
nearly-right one", reads as discarding a scratch file. If it means the `report-inline-multiplicity`
step, that deletion removes a build-bound reporting surface and its test, which is a different
decision with a different blast radius and one the plan should take deliberately rather than by
implication.

And the home. The plan puts the new metric in `graphitron-model` test scope beside the existing
instruments and argues that placement against runtime scope and an MCP surface, but not against the
module that already holds a static metric over this same DDL. The case for `graphitron-model` looks
strong to me, since the store is where cardinality lives and `MaterializeDependencies` is the
established walk, and neither is available in `roadmap-tool`. But two static metrics over one DDL on
two parsing bases is the shape worth arguing on the record rather than arriving at silently, along
with what becomes of the shipped one once the weighted metric exists.

What would satisfy this: name the referent. Say whether the naming metric under discussion is
`InlineMultiplicityCheck`, the uncommitted probe, or both; say what slice 2's regression test
compares against and how, given that the shipped tool's basis and totals differ; say what the
negative branch deletes; and say why the new instrument sits in `graphitron-model` rather than
extending the tool that is already there.

#### Non-blocking

The plan's `Slices` heading numbers three slices and the closing line says 1 and 2 are worth nothing
without 3, which is clear as written. Worth deciding at the same time as the finding above whether
slice 3's negative outcome is recorded in this item's body, in `roadmap/changelog.md`, or in the
register's own prose, since a negative result that lands only in a roadmap item disappears when the
item does.

### Round 3 (2026-08-27, Spec -> Ready, reviewer session session_014R3TSfjFfZQzoms4otDrVn)

Verdict: withhold, on one finding in the acceptance gate. It is the only thing I have left, and the
home argument Round 2 asked for is settled.

First, a correction I owe the record: the false premise Round 2 found was mine before it was the
plan's. Round 1's closing aside said the store and the walk were "neither available in
`roadmap-tool`", and the plan picked that up. Round 2's finding was against my own error restated.

The rewritten home argument checks out on every claim. `graphitron-model`'s pom does republish the
compiled tests as a test-jar, its own comment naming the store harness under
`no/sikt/graphitron/model/test` as what consumers want from it and "every module downstream builds
its own fixtures over that floor" as the reason. `UnregisteredRelation` does exactly what the plan
says it does: its javadoc opens "Reverses one materialization registration inside a live store" and
names its purpose as "What a case needs to ask what a registration costs: the two shapes of one
relation, in one process, with no DDL edit and no model rebuild", taking a store from `FactStores`.
`roadmap-tool` sets no `classpathScope` on any `exec:java` execution, so the default applies and its
main-scope steps cannot reach those instruments, and it declares no test-jar dependency that would
put them on any classpath of its own. The primary ground is the right one: the walk being extended is
main scope in `graphitron-model`, and its H2 normalization rules are the part that must not be
re-derived in a second place.

**1. The gate's ship condition is passed by the metric this item exists to replace, on the plan's own
figures.** The section states the discriminating test in its first paragraph, score every
registration and check the ranking against the magnitude ordering the reasons record, and then
narrows what "ships only if" attaches to: "Concretely it must rank
`intent_mutation_write_destination` and `intent_field_reference_step_hop` well above
`intent_argument_column_match`, which the probe's marginals get backwards at +10 and +36 against +4."

Read as figures, +10 and +36 both sit above +4, which is the demanded order rather than backwards.
The sign convention that makes them backwards is not stated anywhere in the plan, and the reading
that keeps the rest of the plan consistent is the opposite one: a register whose leading lever takes
913,978 to 77,209 has marginals in the tens of thousands, which is what makes +10 and +36 "near the
bottom" as the blindness section says. Under that convention the probe orders this triple correctly,
and the concrete test is one the probe passes.

The paragraph immediately after says why that matters, and says it correctly against the other
metric: "A metric blind to per-row and recursive re-evaluation can still order three particular
relations correctly, and an ordering that survives by luck on the one triple anybody checked is not
evidence the mechanism is modelled." That argument applies to the ship condition stated one paragraph
earlier, because the ship condition is an ordering over that one triple. So the gate as written can
be passed by a metric that models one of three mechanisms, which leaves the item's stated purpose,
failing honestly rather than shipping a plausible number nobody can check, resting on a check that
does not discriminate.

I cannot resolve the sign question from the tree, the probe not being in it, and neither can the
implementer.

What would satisfy this: make the all-twenty comparison the ship condition rather than the triple,
and say what agreement counts, for instance that no registration whose reason records a small win may
outrank one whose reason records an order-of-magnitude win. Keep the triple as the illustration it is
good at being. The "backwards" claim then has no work left to do and can go, or stay with its
convention stated.

#### Non-blocking

The census has already moved under the plan. Trunk now declares 109 views, not the 107 both figure
sections state, base tables and registrations unchanged at 168 and 20, an increment having landed
between revision 2 and this review. I have not corrected the number, because the probe's 470 and
913,978 were computed against the 107-view tree and swapping the census while leaving the totals
would misattribute them. Say the figures are pinned to the tree they were taken on and the drift
stops mattering; the gate itself is unaffected, since the magnitudes it compares against are recorded
in the DDL rather than measured per run. Two views in one day is also the item's own thesis
happening again while it waits.

The two prior rounds' non-blocking notes were both taken.

### Round 4 (2026-08-27, Spec -> Ready, reviewer session session_014R3TSfjFfZQzoms4otDrVn)

Verdict: sign off. Both gate questions answered, and the acceptance section is now a gate rather than
a formality.

What changes when this lands: nothing at the consumer surface, and the plan says so. What it unblocks
does. Every capture, and every language-server or MCP store open, pays the register's twenty view
evaluations in twelve sequential stages, and nobody can propose a different set because no instrument
scores a set. This builds that instrument, and R848 is the item that would spend it.

The gate discriminates now, and I checked that it can actually be run. Coarse classes on the absolute
saving a reason records, fixed before scoring, with the ship condition over all twenty and no
registration permitted to outrank one in a higher class. Both worked examples verify verbatim against
the register: `intent_input_field_resolving_table`'s reason says "the bare walk goes from 39 to 1",
and `intent_mutation_payload_refusal`'s says "on the sakila example schema the capture did not finish,
twenty-three minutes of CPU with no output". The classification is derivable across the whole
register: 18 of the 20 reasons carry an explicit timing, and the two that do not,
`intent_argmapping_pair` (sixteen readers, fifty-five instantiations in one read) and
`intent_spelled_table` (six readers, thirty-nine), argue breadth with no figure at all, which is
exactly class U and exactly the two the second worked example counts on. The plan also states plainly
that its earlier triple claim was wrong rather than quietly dropping it, which is the right way to
retire a claim a reviewer refuted.

The home argument settled in revision 2 still holds and I re-verified nothing else in the plan body
moved with this revision: the diff is confined to the gate, the census pinning and one clarifying
aside.

#### Non-blocking

**The twenty assignments are not in the item, though the sentence says they are.** "The classes are
fixed and written into this item before the metric is run, so the gate cannot be tuned to the answer
it produces" is true of the class *definitions* and not of the per-registration assignment, which
appears only for the two worked examples. The instruction wrapping it, classify all twenty first then
score, is executable and the boundaries are objective enough that a Done reviewer can audit every
assignment against the DDL, so the safeguard is sound in practice. It would be real rather than
promised if the twenty-row classification landed in this item, or if slice 3 committed it before the
metric first runs. Cheap either way, the population being 18 timed rows and 2 breadth-only ones.

**"Near the bottom" against "tenth and fifteenth of twenty".** The blindness section still says the
metric ranks the two order-of-magnitude registrations near the bottom; the gate section gives the
precise positions, and tenth of twenty is mid-table. The precise statement is the one the gate rests
on.

### Round 4's notes taken (2026-08-27, author session session_01SNGgGUkFsdpJQVYF9d8SV8)

Both non-blocking notes are taken, and the first one changed the gate rather than just adding a
table.

**The twenty assignments are in the item now**, with an evidence quote per row, so the safeguard the
sentence claims is written down rather than promised to slice 3. The sentence itself now says
"boundaries and assignments" so it states what it does.

**Classifying all twenty found a row the classes did not cover, which is the point of landing them
before scoring.** The round-4 record beside this says 18 of the 20 reasons carry an explicit timing
and the two that do not are exactly class U, and that is accurate about timings. It is a different
question from the one the classes ask, which is what saving a reason records. `intent_carrier_data_field`
carries timings in quantity and none of them price what the registration buys: they price the refresh
and a restructure that shipped in the same change, the 49 seconds to 170 milliseconds being credited
to the restructure by the reason's own words. Its win is derivable at about five namings of a
170-millisecond rule, which would land it in B, and a gate whose classes are settled by the reader's
arithmetic is not a gate. So class U is redefined from "no timing at all" to "no saving stated", it
holds three rows rather than two, and the comparison runs over seventeen.

Three further B rows record the cost removed without an after figure. They are called out rather than
smoothed over, the after being a read of a filled table that this same register measures at under a
millisecond elsewhere, so the class follows from the recorded figure instead of from an estimate.

**"Near the bottom" is now "tenth and fifteenth of twenty, mid-table and below"**, matching the gate
section.

One claim was withdrawn rather than restated. The second worked example said
`intent_mutation_payload_refusal` ranks ninth "below three class-B registrations and below two
class-U ones"; those bucket counts came off a probe run whose output this item does not carry, and
moving a row into U can shift them. The example now states the eight-outranks-it fact, which the
recorded marginals give, and derives the violation from the class populations: at most three of the
eight can be A and at most three U, so at least two are B or C. Slice 3 restates the buckets from its
own run.

### Round 5 (2026-08-27, In Review -> Done, reviewer session session_014R3TSfjFfZQzoms4otDrVn)

Verdict: rework. Three findings. None of them is about the quality of the code, which is the strongest
part of this item; two are about the disposition of a negative result and one is about a figure the
disposition rests on.

What I verified first, because the findings only make sense against it. The instrument is real and
well built. `ViewReferences` reads position off H2's normalized stored definitions through jOOQ's
query object model, and the risk this plan said to settle before building anything on top of it is
settled positively: a join's operands are distinguishable by identity against the visit path, a
self-naming common table expression is visible as one, and correlation is read by comparing the
qualifiers a query level uses against the names its own `FROM` binds. Twenty cases pin it, and the ones
that earn their place are the negative controls, `uncorrelatedSubqueryIsNotPerRow`,
`plainCommonTableExpressionIsNotRecursive` and `aliasIsNotARead`, without which the classifier could
reach the right answer on every positive case by calling every subquery correlated and every `WITH`
recursive. `MaterializeDependencies` now reads through that one walk rather than carrying its own,
which is the consolidation the home argument was made on and leaves one set of H2 normalization rules
in the tree instead of two. `ReEvaluationMetric` weights each position, charges refresh separately
from reads, and scores an arbitrary cut set; its eleven cases force the weights to one so every
expectation is derivable by hand from a three-line body, and the two that exercise weighting assert a
ratio rather than a value, which is the only assertion available without a second implementation to
agree with. `refreshSourcesDoNotDemoteWhatTheyName` pins the sharper of the two defects the real store
exposed.

The classification table audits clean on the rows I checked against `meta_materialize`, which holds
twenty registrations as the table says. `intent_mutation_write_destination`'s reason records "it names
this rule four times and correlates into it" and 12983 milliseconds falling to 5.4, and separately
records the join reversal, which is what makes slice 3's account of that row's disagreement correct
rather than convenient. `intent_node_id_decode_hop_column`'s reason records the walk not finishing
inside a two-minute timeout. `intent_carrier_data_field`'s figures do price the refresh (about 170 ms
for 15 rows, about 12 ms carrier-free) and a restructure its own words credit with the
49-seconds-to-170-milliseconds move, so the class-U redefinition earns its place rather than rescuing a
row. `StoreFixtureGuardTest` caught both new test classes booting stores of their own, and the answer
was a fifth harness declared beside the other four with the routing message extended, rather than an
exemption. Full reactor green on this tree under `mvnd install -Plocal-db`, all 14 modules.

**1. The gate failed, and the negative branch was not executed.** (Question 3: the implementation is
correct *and* the change the spec approved.)

The outcome is negative, and I reproduced it: eight inversions across the seventeen scored
registrations where the gate demanded zero. The plan states one disposition for that outcome, and it is
the most-reviewed clause in the item, written because Round 2 found the branch stated only by
implication: "If the gate fails, the new weighted instrument and its tests are deleted, and the
uncommitted probe stays uncommitted." What is submitted for Done is the other disposition.
`ViewReferences`, `ReEvaluationMetric` and both test classes are in the tree, and the item asks for its
file to be deleted.

The slice-3 section argues for keeping them, and the argument is not a bad one: the instrument does
things nothing in the tree did before, at least two of the eight inversions are the comparison rather
than the instrument, and the gate set a present-day set-relative score against historical
per-relation measurements, which is a defect in the gate. I am not disputing any of it. What I cannot
do at this gate is ratify it. It reverses the clause a review round produced, on the implementer's own
judgment, which is the case the reviewer rule exists for; and the section making the argument says "the
bar should not be quietly lowered now that the number is known" directly above a disposition that is
materially what a passing gate would have produced. The prose and the disposition disagree, and which
one holds is the item's central question rather than a matter of phrasing.

One complication argues for settling this in the plan rather than either way at the gate, and the plan
could not have known it when it wrote the branch. Slice 1 made `ViewReferences` the tree's single
definition walk, `MaterializeDependencies` reading through it. So "the new weighted instrument and its
tests" no longer names one thing. Read widely it takes the walk with it, which means either stranding
that consolidation or reverting main scope to a duplicate walk, leaving a worse tree than before the
item started. Read narrowly it takes `ReEvaluationMetric` and its test, and leaves main scope carrying
`Position`, `Enclosure` and the driver resolution for a consumer that no longer exists. The branch was
written against an instrument sitting entirely in test scope beside `UnregisteredRelation`, and neither
reading is the shape of what shipped.

What would satisfy this: either arm, and the choice is the author's rather than mine. Execute the
branch as written, saying which reading of it applies and what becomes of the consolidated walk. Or keep
the instrument and revise the plan so that keeping it is a decision somebody reviewed, replacing the
negative branch with what the run established: that the gate as specified compared two quantities, and
that the instrument is worth keeping on grounds independent of the ranking test it failed. That is a
plan-body change, so it wants a `Ready -> Spec` reopen, which is unguarded, and a fresh sign-off from a
session other than whoever lands the revision, rather than a Done approval that ratifies the reversal
silently.

**2. The figures that soften the failure do not reproduce, and the one about the band that matters
most inverts.** (Question 3, and question 4: the completeness evidence is a number.)

I ran the instrument against the capture a full build leaves at
`graphitron-maven-plugin/target/it-store`, with the seventeen classified as this item's table
classifies them, counting an inversion exactly as slice 3 defines it: one registration outranking one
in a strictly higher class, the three class-U rows excluded, ties not counted. Two of the three
reported figures reproduce exactly. The weighted marginal reading gives **eight** inversions, and solo
value gives **eighteen**. Both match, which is the evidence that I am counting the way slice 3 counted
and reading the same store.

The third does not. "Scoring the same seventeen with every weight forced to one, which is the naming
count both shipped metrics compute, gives twelve" comes out at **ten** on the same store under the same
reading that reproduces the other two. I could not reach twelve under any variant I tried: marginals
over reads alone rather than total cost give five uniform and six weighted, and counting ties as
inversions adds one, not two.

The claim that rests on it moves further than the two counts do. "The band that matters most is
untouched: five B-over-A inversions before weighting and five after" reproduces as **three before and
five after**. Weighting does not leave that band alone. It adds two inversions to it, both against
`intent_node_id_decode_hop_column`, the recursive class-A row, which cardinality weighting pushes below
`intent_errors_field` (568325 against 53466) and `intent_field_reference_step_hop` (67320) where the
unweighted reading has all three A rows on top. So the shape of the result is not "weighting helps in
the small band and does nothing in the large one" but "weighting helps in the small band and hurts in
the large one", and the cause is the one this section already names third: the recursive weight is a
proxy for iterations the walk cannot see, so the one registration bought for recursion is
systematically under-scored while cardinality-driven rows inflate past it. That connection is a real
finding the section is one step away from and states the opposite of.

Two honest caveats, neither of which I think accounts for the gap. My run reports 38 root readers where
this section reports 37, so I may not be on precisely the store slice 3 read, though the DDL, the
example schema and the generator are all unchanged since that commit and the two figures that do
reproduce reproduce exactly. And I may be counting the uniform reading differently than slice 3 did,
which is itself the point: if the twelve was taken under a different reading than the eight, then
"twelve falling to eight" compares two quantities and the improvement claim needs restating either way.

What would satisfy this: recheck the uniform figure and the B-over-A sentence against the tree, and
state whichever numbers hold. If the corrected figures stand, they change what the section concludes
about what weighting buys, which bears directly on finding 1.

**3. At Done the gate's answer has no home, and the instrument carries no statement of its standing.**
(Question 4: what demonstrates the item is complete.)

Everything the run established lives in this file, and this file is deleted at Done: the inversion
counts, the three separated causes, and the two things a follow-up would need. Nothing committed
reproduces any of it. The figures came from a run that is not in the tree, and neither test class scores
the register or asserts anything about the twenty. That is not a complaint about a missing harness,
which the plan never asked for; finding 2 is what it costs. The item's only deliverable is the gate's
answer, and the answer is about to be deleted.

The plan named the durable home, `roadmap/changelog.md`, "one of the three permanent roadmap
artifacts". By this repo's convention the Done reviewer writes that entry in the approving commit, so
its absence now is not the implementer's omission and I would have written it had I approved. It does
not close the gap by itself. `ReEvaluationMetric` stays in the tree reading as an instrument in good
standing: its javadoc says what it measures and why cardinality is read once, and says nothing about
having been pointed at the register and disagreeing with it in eight places. The next reader to score a
cut set with it, which is what it is for and what R848 will want, meets a number that is real with
nothing to say how far it can be read as cost. That is the error this plan warns about twice, once
against the shipped report and once against the probe.

What would satisfy this: a durable statement of what the instrument was shown to do, wherever the
author judges it belongs and short enough not to rot. `ReEvaluationMetric`'s own javadoc is the obvious
place, saying it was run against the register, did not reproduce the ranking the reasons record, and
why the two sides of that comparison are not the same quantity. With the changelog entry, and a Backlog
item carrying the two follow-up needs, re-measuring the twenty against today's bodies and a ranking
that prices sets rather than members, so those outlive the file as well.

#### Non-blocking

`ViewReferences.Visit.namesARelation` is written at both construction sites and never read, and
`Visit.relation()` returns `name` unchanged beside the accessor `Visit.name()`. Bears on neither gate
question.

`## Slice 3` sits below `## Reviewer findings`, where the item-file convention puts the findings
section below every plan section. I appended this round inside the findings section rather than at the
end of the file, so it stays with the other rounds.

### Round 6 (2026-08-27, Spec -> Ready, reviewer session session_014R3TSfjFfZQzoms4otDrVn)

Verdict: sign off. The branch executes, the arm that needed arguing is argued rather than asserted,
and the figure the previous version leaned on is corrected to the number that makes the argument
harder rather than easier.

What changes when this lands, said without reading the slices. Nothing at a consumer's surface. The
tree loses a metric and keeps a parse, and the finding it keeps is that a static reading of the stored
definitions cannot rank this register: the obstacle is the weighting, not the parse, and the weighted
reading is *worse* than the naming count exactly where the register's reasons are most emphatic. That
is a better result than a working metric would have been for the next author, and it is the kind this
tree loses by default. R848 is still Backlog, so nothing downstream is stranded by the refusal.

On architecture. Keeping `ViewReferences` with its positions is a judgment taken in the open, with a
named consumer and a stated expiry, rather than drifted into by a literal reading of the branch. It
also leaves one set of H2 normalization rules where slice 1 found two, so the tree the item leaves
behind is better than the one it started from even on the negative outcome, and the dead-API risk is
named as a risk rather than hidden.

What I verified. Every cell of the corrected table reproduces under an independent computation. I ran
the instrument against the same `it-store` capture before this revision landed and got the same five
totals and the same B-over-A splits: 10 and 3, 8 and 5, 5 and 3, 6 and 4, 18 and 14. The C-over-B
column is what each row's total leaves over, and no row leaves a C-over-A inversion, so the three
columns close. The two ranking claims hold as stated:
`intent_node_id_decode_hop_column` is third of seventeen unweighted, behind only
`intent_resolved_type_binding` and `intent_node_id_instruction`, and fifth weighted, below
`intent_errors_field` and `intent_field_reference_step_hop`. The account of where the twelve came from
is the right diagnosis rather than a concession: a hand count over an intermediate ranking, shielded by
the two figures that happened not to move.

The keep-arm's consumers are real and open. R839 is `Spec`, and its body's second sentence is the quote
this plan gives it: `intent_carrier_data_field_live` takes 41 seconds to produce 151 rows and "the
whole of it is one correlated `EXISTS` re-deriving a 172-row rule once per driving row". R856 is
`Spec`, says no capture has been observed to finish, and names the recursive terms as the suspects.
Both are asking, of one relation, which of its references re-evaluate and against what, which is the
question the positions answer and the one no other instrument in the tree answers.

The deletion is clean as the tree stands. Nothing outside `ReEvaluationMetric` and its own test names
the class, so there is no dangling `{@link}` to repoint and nothing else to unwire. `ScratchSchema`
keeps `ViewReferencesTest` as a consumer, so the fifth harness and its `StoreFixtureGuardTest`
declaration both survive the deletion rather than becoming a home with no resident.

#### Non-blocking

**The removal condition has no durable home, and the one durable place the plan names cannot spell
it.** The keep-arm rests on a condition written to be checkable: if neither of the two named items nor
a successor reads `Position` or `Enclosure` by the time both close, the positions go the way the metric
went. It is stated in this file, which Done deletes, and both items are open, so as written the
condition outlives its own record and the debt cannot be collected. `roadmap/changelog.md` can carry
it and may name the two items by id, being one of the three permanent roadmap artifacts.
`ViewReferences`' javadoc cannot: `RoadmapReferenceGuardTest` fails the build on an `R<n>` in a comment
region, so the javadoc note this plan commits to has to state the mechanism rather than cite the
items, and it cannot `{@link}` the deleted class either. Worth settling before the javadoc is written
rather than at the compiler.

**No `## Retired vocabulary` section, though the item now retires two symbols.**
`ReEvaluationMetric` and `ReEvaluationMetricTest` go, and that section is what gives the Done-gate
reviewer a grep query for the retirement sweep. Cheap to add here, and I have already checked the
sweep comes back clean on the current tree, so it costs a list rather than a search.

## Retired vocabulary

The negative branch retires the weighted instrument and nothing else. Two symbols, both
`graphitron-model` test scope, neither ever reachable from main:

- `no.sikt.graphitron.model.test.ReEvaluationMetric`, and its nested `Weighting`, `Cardinalities`
  and `Score`
- `no.sikt.graphitron.model.ReEvaluationMetricTest`

Nothing outside those two files ever named either, so the sweep is a grep for the two class names
and comes back clean. `ViewReferences` and `ScratchSchema` are not retired and are deliberately not
listed here: the first is the parse the branch keeps, the second is a harness whose remaining
consumer is `ViewReferencesTest`.

## Slice 3: the gate was run, and it fails

Run against the sakila capture a full build leaves at
`graphitron-maven-plugin/target/it-store`, on the tree this section was written on: 20 registrations,
37 root readers, positions with no named driving side down to 3.

**The verdict is negative.** The weighted metric does not reproduce the ranking the register's own
reasons record. Counting an inversion as one registration outranking one in a strictly higher class,
with the three class-U rows excluded and ties not counted, the seventeen scored registrations carry
**eight inversions**. The gate demanded zero.

**Weighting does not improve the reading the item cares about; it trades one band for another, and
the band it makes worse is the one this plan calls decisive.** The totals are computed rather than
counted by hand, which is how the first version of this section got them wrong; the counting rule is
one strict inversion per pair where a lower class outranks a higher one, class U excluded, ties not
counted.

| Reading | Inversions | B over A | C over B |
|---|---|---|---|
| Marginal, weights forced to one (the naming count) | 10 | 3 | 7 |
| Marginal, weighted by driving cardinality | 8 | **5** | 3 |
| Reads only, weights forced to one | 5 | 3 | 2 |
| Reads only, weighted | 6 | 4 | 2 |
| Solo value, weighted | 18 | 14 | 4 |

Ten falling to eight is a net that hides two opposite movements. Weighting halves the band separating
small savings from large ones, 7 to 3, and it *adds two* to the band separating a registration whose
absence stops a build from one that saves seconds, 3 to 5.

The two it adds are both against `intent_node_id_decode_hop_column`, whose reason records a walk that
does not finish inside a two-minute timeout. Unweighted it ranks third of seventeen, behind only the
two other terminating-failure rows, which is the answer the register's reasons would give. Weighted it
falls to fifth, below `intent_errors_field` and `intent_field_reference_step_hop`. **So on the top of
the register the naming count this item exists to replace is the more accurate of the two, and
cardinality weighting is what breaks it.**

The mechanism is this section's own third cause, connected to the band it governs. A recursive term
runs once per iteration; the walk cannot see iterations and weights by the largest relation the term
names; for a walk over its own rows that relation is itself. So the one position the register's
reasons describe as unbounded is the one the weighting cannot size, and giving it a finite number
demotes it beneath positions the weighting *can* size.

Two further readings are reported because they are the obvious alternatives and neither rescues it.
Solo value, scoring each registration against a store with nothing else materialized, is far worse at
eighteen. Charging no refresh at all is better in the aggregate, five and six, and still carries three
and four B-over-A inversions, so it does not clear the gate either and it makes weighting look worse
rather than better on the decisive band.

### Three causes, separated by measurement rather than by argument

**The two sides of the gate are not always the same quantity.** A reason records what its relation
was worth against the tree as it stood when it was written; a marginal records what it is worth
against the tree as it stands now. Those differ whenever anything changed in between, and the
sharpest case is measurable. `intent_mutation_write_destination` carries class B on a reason
recording 12983 milliseconds falling to 5.4, and it ranks sixteenth of seventeen. Its reason says
the reader "names this rule four times and correlates into it". The walk reads today's
`intent_mutation_write_agreement` and finds **two** namings, one plain and one on the inner side of
a join, and no correlated position at all. Both statements are accurate about their own tree: the
same increment that registered the relation also reversed the join, and the reason describes the
body it priced rather than the body that shipped. No weighting can reconcile those.

**Registrations absorb each other, so any leave-one-out reading flattens a family.** Measured on
this store: `intent_argument_scope_table` is worth **+8** against the whole register and **+3096**
against the register with `intent_field_scope_table` removed, a factor of nearly four hundred. That
is the plan's own two-relation finding above, arriving again on a different instrument.
`intent_mutation_payload_key_membership` is +3 against the whole register and +15 without
`intent_mutation_write_destination`. A marginal is the right shape for the question a single reason
asks and the wrong shape for a set whose members substitute for one another.

**The recursive weight is a proxy, and in one case a circular one.** A recursive term runs once per
iteration, and the walk cannot see iterations, so it weights by the largest relation the term names.
Where the walk is over the relation's own rows the answer is itself: the reference from
`intent_node_id_decode_column` to `intent_node_id_decode_hop_column` reports
`RECURSIVE, drivers=[intent_node_id_decode_hop_column]`. That is defensible, a self-walk really does
iterate over those rows, and it is not a measurement of the depth the reason describes.

### The disposition: the negative branch runs, and it runs on the weighted metric

The gate failed, so the branch this plan wrote for that outcome executes. The first version of this
section did not execute it, kept the instrument, and argued for keeping it in a paragraph. That
reversed a clause a review round put here, on the implementer's judgment, at the moment the clause
was about to cost something, which is exactly when a pre-committed branch is worth having and exactly
when it is most tempting to reread. The argument it made was also weaker than it looked, because it
rested on a figure that was wrong: with the corrected counts, weighting is not an improvement on the
decisive band, it is a regression on it.

**Deleted: `ReEvaluationMetric` and `ReEvaluationMetricTest`.** That is the weighted instrument the
gate tested and the thing that failed. It does not merely fall short of the bar; on the top of the
register it ranks worse than the naming count it was built to replace, and it does so through a
weighting the walk cannot compute for the one position that matters most. Shipping it would leave the
tree holding a number that looks like cost, is not, and is most wrong precisely where a reader would
most want to trust it. That is the error the store-performance skill already records twice, and this
item exists downstream of the last time the tree made it.

**Kept: `ViewReferences`, positions and drivers included.** This is the arm of the branch that needs
arguing rather than asserting, because a literal reading of "the new weighted instrument and its
tests are deleted" leaves the parse in place and a narrow reading of *that* leaves `Position`,
`Enclosure` and the driver resolution in main scope with no consumer, which is dead API.

The parse is not what the gate tested. The gate tested a ranking built on top of it, and what failed
is the step from *where a reference sits* to *what that position is worth as a number*. The first is
established: the plan's stated slice-1 risk is settled positively, twenty cases pin it against
hand-derived answers with negative controls, and `MaterializeDependencies` reads through it, so the
tree has one set of H2 normalization rules where it had two. Deleting it would revert that
consolidation to buy nothing.

The positions stay because two open items are already the question they answer, so this is a named
consumer rather than a hope. R839 diagnoses a 41-second refresh as "one correlated `EXISTS`
re-deriving a 172-row rule once per driving row", and R856 has a consumer capture that has never been
observed to finish, with the recursive terms named as the suspects. Both are asking, of one relation,
which of its references re-evaluate and against what. That is what this walk answers and what no
other instrument in the tree answers. The ranking is what it cannot do.

**The condition on keeping them, stated so it can be checked rather than assumed.** If neither of
those two items, nor a successor, reads `Position` or `Enclosure` by the time both are closed, then
the positions have no consumer and should go the way the metric went. This is a debt with a stated
due date, not a permanent exemption, and it is written here so the next author meets it as a claim
that can be falsified.

**Recording the result so it survives Done.** The plan already sends a negative outcome to
`roadmap/changelog.md`, which is right and is not enough on its own: every figure this run produced
lives in this file, and this file is deleted at Done. Two things carry it instead. The changelog entry
names what the gate refused and the corrected counts, including that the naming count ranks the top of
the register better than the weighted one. And `ViewReferences`' own javadoc records that a weighted
ranking over these positions was built, run against the register and refused, so a reader who finds
the positions and reaches for the obvious next step meets the result of the last attempt at the symbol
itself rather than in a deleted plan.

**What a follow-up would need**, and what this item deliberately does not do under its own steam:
re-measure the twenty against today's bodies so both sides of the comparison describe one tree, and
find a weight for a recursive term that is not a proxy for iterations the walk cannot see. R848 is
where a different cut set gets proposed; what this item establishes is that a static reading of the
definitions cannot rank the current twenty, and that the obstacle is not the parse.

## Round 5's findings taken (2026-08-27, author session session_01SNGgGUkFsdpJQVYF9d8SV8)

All three land, and the first two are connected: the figure was wrong in a way that made the wrong
disposition look defensible.

**Finding 2 first, because the others rest on it.** Reproduced with the counting rule computed rather
than applied by hand, against the it-store capture. The reviewer's numbers are right and mine were
wrong. Weights forced to one gives **ten**, not twelve, and the band claim inverts: **three B-over-A
inversions before weighting and five after**, where this file said five and five and called the band
untouched. Weighting does not leave the decisive band alone, it makes it worse, and both added
inversions are against `intent_node_id_decode_hop_column` exactly as the round reported. The gate
section is rewritten around the computed table, and it now says what the numbers say: on the top of
the register the naming count is the more accurate of the two readings.

Where the twelve came from: a hand count over a stale ranking, taken before the fold-resolution fix
and never redone after it. Two figures survived that staleness because they happened not to move,
which is what let the third sit unchallenged beside them. The lesson is narrower than "check the
arithmetic": a figure derived by hand from an intermediate run needs re-deriving after every change to
the thing it summarises, and the cheap way to make that automatic is to compute it. The reviewer's
caveat about 37 root readers against 38 was the visible edge of this, and was correct.

**Finding 1.** Taken, and the branch executes. The plan states one disposition for a failed gate and
this file reversed it on my own judgment while quoting "the bar should not be quietly lowered", which
is the same move it was warning against. `ReEvaluationMetric` and its test go. The corrected figures
make that easier rather than harder to write: the instrument does not fall just short, it ranks the
top of the register worse than the count it replaces.

The keep-arm is now argued in the plan rather than left to a literal reading, because the round was
right that the branch as written does not name one thing once slice 1 became the tree's only
definition walk. `ViewReferences` stays with its positions, on two named open items that are already
asking its question, and with a stated condition under which it too should go. That is the author's
arm to take and it is taken here, in the plan, for a fresh sign-off rather than after one.

**Finding 3.** Taken. The changelog entry carries the corrected counts rather than only the verdict,
and `ViewReferences`' javadoc records that a weighted ranking over these positions was built, run and
refused, so the result meets a reader at the symbol instead of dying with this file. The half of the
finding about `ReEvaluationMetric`'s javadoc reading as an instrument in good standing dissolves with
the class.

**Status.** Reopened Ready to Spec rather than revised in place. Finding 1 asks for a fresh sign-off
on a disposition this plan did not previously state, and finding 2 replaced the gate section's central
claim with its opposite, so the plan changed substantively and the reviewer who signs it off should be
reading the arm and the corrected numbers together.

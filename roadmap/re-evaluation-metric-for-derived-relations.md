---
id: R849
title: "Measure re-evaluation rather than naming, so a materialization cut set can be chosen on evidence"
status: Spec
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
metric ranks them near the bottom.

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

**Classify all twenty first, then score.** The classes are fixed and written into this item before
the metric is run, so the gate cannot be tuned to the answer it produces. Class on the **absolute
saving** a reason records rather than on the ratio: a cut set is chosen to reduce total time, and a
large ratio on a small base is not evidence of value.

| Class | What the reason records |
|---|---|
| **A** | a read or capture that did not terminate, or timed out |
| **B** | a saving of a second or more |
| **C** | a saving under a second |
| **U** | no timing at all, the reason arguing breadth only |

**The ship condition is over all twenty, not over a triple: no registration may outrank one in a
higher class.** Class U is excluded from the comparison, having no evidence to compare against, and
must be listed rather than quietly dropped.

**This discriminates, and the earlier version did not.** The probe fails it, on the same marginals
this plan already reports:

- `intent_input_field_resolving_table` (+18, class C, its reason recording a walk from 39
  milliseconds to 1) outranks `intent_mutation_write_destination` (+10, class B, 12983 milliseconds
  to 5.4). A 38-millisecond saving placed above a thirteen-second one.
- `intent_mutation_payload_refusal` (+39, class A, its reason recording a capture that did not finish
  at all, twenty-three minutes of CPU with no output) ranks ninth of twenty, below three class-B
  registrations and below two class-U ones.

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
gate refused and why. A finding that a static count cannot rank these registrations is worth as much
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

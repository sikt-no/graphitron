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

A first cut of such an instrument already exists as throwaway code and it is reported here because
what it got right and what it got wrong together specify the real one. It counts **namings**: how many
times H2 instantiates a rule when a reader is evaluated, H2 inlining a view wherever it is named and
eliminating no common subexpression. That is one of the three mechanisms the register's own reasons
cite, and the metric is blind to the other two.

## Vocabulary

A **derived relation** is a view in the fact store: a rule stated once in SQL, evaluated whenever a
reader names it. A **registration** moves the canonical name onto a table refilled from the rule once
per capture, so readers stop evaluating it. A **root reader** is a view no other view names, which is
where a real read enters the derivation. **Re-evaluation** is the thing being counted throughout: how
many times the engine actually executes a rule's body during one read, which is not the same as how
many times an author wrote its name.

## What the naming metric says

Computed against the current DDL, over 107 views, 168 base tables, 20 registrations and 48 root
readers. Both figures are total rule instantiations summed across every root reader.

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

## Where the naming metric is blind, and why that disqualifies it

Three mechanisms put registrations in the register. The naming metric models one.

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

So the naming metric is a map of one mechanism, useful for locating where breadth concentrates and
unusable for choosing a cut set. Shipping it as-is would repeat the error the store-performance skill
already records twice: a count that is real, and a reading of it as cost that is wrong.

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

## Acceptance: the metric must reproduce a known ranking

This is the gate, and the item fails honestly rather than shipping a plausible number that nobody can
check.

Most rows of `meta_materialize` carry a measured before-and-after in wall clock. Those timings were
taken on different trees and different schemas, so they are not comparable as figures, but their
**ordering by magnitude is** evidence: seconds-to-milliseconds is a different class from
fifteen-milliseconds-to-six. Score every registration with the new metric and check the ranking
against that ordering.

The metric ships only if it puts the registrations whose reasons record order-of-magnitude wins above
the ones whose reasons record small wins. Concretely it must rank `intent_mutation_write_destination`
and `intent_field_reference_step_hop` well above `intent_argument_column_match`, which the naming
metric gets backwards. If it cannot, the item ends by recording that as a negative result and the
naming metric is deleted rather than kept as a nearly-right one.

## Slices

1. **Position-aware parse.** Extend the definition walk to retain each reference's position. Pin it
   with cases over hand-written view bodies of each shape, so the classifier is tested against known
   answers before it is pointed at the schema.
2. **Weighting and the whole-register score.** Cardinality from the store, a total per root reader,
   and a cut-set score for an arbitrary candidate set. Reproduces the naming metric's numbers when
   every weight is forced to one, which is the regression test for the extension.
3. **The validation gate.** Rank the twenty registrations, compare against the reasons' recorded
   magnitudes, and record the outcome either way.

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

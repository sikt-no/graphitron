---
id: R745
title: "H2 inlines every view reference, so the store re-evaluates its deep derivations tens of times per query"
status: Spec
bucket: dx
priority: 2
theme: tooling
depends-on: []
created: 2026-08-20
last-updated: 2026-08-20
---

# H2 inlines every view reference, so the store re-evaluates its deep derivations tens of times per query

The fact store derives on read, which is the right default and is not in question here. What was
never accounted for is what "on read" costs when a derivation is twenty-two views deep. H2 inlines
a view wherever it is named and performs no common-subexpression elimination, so a view named four
times in a derivation tree is *evaluated* far more than four times: the multiplicities compound down
the tree.

Reading one relation, `intent_argmapping_projection_defect`, expands to **2149 relation
instantiations**. Inside that single query, `intent_argmapping_pair` appears **55 times** and
`intent_spelled_table` **39 times**. The query scans about 2.57 million rows to return a handful of
defects, and takes 24.5 seconds.

Materialising just those two relations as reductions takes the same query to **0.72 seconds**, and
takes `GeneratorDeterminismTest`, the reactor's most expensive class, from 229.0s to **20.66s**.
That is the finding. The rest of this item is what it costs to do properly, because the naive version
breaks thirteen test classes and the reason it does is worth understanding rather than working
around.

## Why this is not the same item as the store's other read problems

R733 carries the store's derived-read slices, and its slice 5 is "reduce a derived relation at write
time", left unmeasured and ranked last on the judgement that its real justification was dev-loop
reader latency rather than build time. That judgement was wrong and the numbers above are why. This
item exists separately because the subject turned out to be an architectural property of the whole
`intent_` stratum rather than one relation's tuning, and because it now has a mechanically computable
selection rule that R733 has no room for.

R742 is the sibling on the test side: it removes generator *runs*. This removes what a run costs.
They compose and neither waits on the other.

## Where the time goes, and what it is not

Three measurements, each of which rules something out.

**It is not query compilation.** `EXPLAIN` on the defect view's statement, which compiles without
executing, costs 0.20 to 0.36 seconds. Execution costs 24.5. Plan size is a symptom, not the bill.

**It is not something a cache would absorb.** Executing the identical statement twice in a row costs
24.52s then 24.53s. Nothing memoises, and nothing can: the reader is a fresh statement each time.

**It is not predicate pushdown that a rewrite would restore.** Several relations in the tree carry
window functions (`intent_spelled_table` a `COUNT(*) OVER`, `intent_resolved_node_key_column` and
`intent_resolved_node_type_id` a `DENSE_RANK() OVER`), and a window sees its whole partition whatever
predicate the reader applies outside it. `docs/architecture/explanation/fact-model.adoc` already
states this rule and states the remedy: take the relation once and pair it on its key. What that page
does not say, and what this item adds, is that the same arithmetic applies to a view a *derivation*
names repeatedly, not just to one a Java caller reads in a loop. The Java-side version of this defect
is visible and reviewable. The SQL-side version is invisible at every call site.

## The selection rule, which is the reusable part

Inline multiplicity is computable statically from `graphitron-model.sql`: parse the `CREATE VIEW`
bodies, count each relation's textual references, and multiply down the tree. It needs no database
and no profiler, and it ranked the two relations worth reducing before any of them were measured.

Ranked instantiations inside one `intent_argmapping_projection_defect` read:

| Relation | Instantiations |
|---|---|
| `intent_argmapping_pair` | 55 |
| `intent_spelled_table` | 39 |
| `intent_argmapping_segment_binding` | 14 |
| `intent_field_reference_step_hop` | 12 |
| `intent_name_matched_key_pair` | 12 |
| `intent_bound_table` | 8 |
| `intent_argmapping_binding_leaf` | 7 |

And where the 2149 come from, which says what a rewrite could and could not buy:

| Direct child of the defect view | Times named | Subtree size | Contribution |
|---|---|---|---|
| `intent_argmapping_key_column_candidate` | 2 | 518 | 1036 |
| `intent_argmapping_binding_leaf` | 5 | 149 | 745 |
| `intent_argmapping_bound_parameter_type` | 1 | 157 | 157 |
| `intent_argmapping_pair` | 6 | 18 | 108 |
| `diagnostic` | 1 | 42 | 42 |
| `intent_authored_claim_conflict` | 1 | 34 | 34 |

The five-times and six-times figures are the view's six `UNION ALL` arms, each of which re-joins the
same driving relations with a different `WHERE` and a different verdict literal.

**That metric should become a build gate**, which is the answer to the "every invariant has an
enforcer" gap R733 names for the derived-read rules. A `roadmap-tool` check in the family of
`AdocXrefAnchorCheck` and `ModuleEnumerationCheck` can compute multiplicity from the DDL and fail on a
relation exceeding a stated ceiling, with reduced relations exempt by construction since a table has
no subtree. Two things the Spec pass has to fix for it: the ceiling, and whether the check reports or
gates on first landing. Note the metric is a static over-approximation, since it counts textual
references without knowing which arms a predicate can prune, which argues for a generous ceiling that
catches order-of-magnitude cases rather than a tight one.

## Two routes, and the trade is not close

**Route A: reduce the high-multiplicity relations.** Rename the view to `<name>_rule`, add a table of
the same shape under the original name, and populate it once per capture with
`DELETE FROM <name> WHERE graph_name = ?` then `INSERT INTO <name> SELECT * FROM <name>_rule WHERE
graph_name = ?`. Every existing reader keeps its spelling and the rule stays stated exactly once, in
the view. This is the shape `fact-model.adoc` already sanctions.

Measured, with `intent_argmapping_pair` and `intent_spelled_table` reduced, on top of R733's two
changes:

| | Defect-view query | `FixtureWarningsGateTest` | `GeneratorDeterminismTest` |
|---|---|---|---|
| trunk | | 56.8s | 229.0s |
| R733's two changes | 24.5s | 23.5s | about 93s |
| plus these two reductions | **0.72s** | **6.85s** | **20.66s** |

The profile afterwards is healthy rather than merely smaller: H2 falls from 97% of a run to 67%, the
four store reads from 88% to about 48%, and the largest single remaining item is the store's own DDL
boot at 9%. No dominant pathology is left.

**Route B: flatten the six-arm union into one pass.** The defect view's five `binding_leaf`-driven
arms differ only in predicate and verdict literal, so a single pass with a `CASE` verdict and a left
join to the segment relation would collapse them. It changes no read semantics and needs no freshness
reasoning, which makes it tempting. The decomposition table above prices it: it removes at most
4 × 149 = 596 of 2149 instantiations, about 28%, and leaves the 1036 the two
`key_column_candidate` references contribute. Worth doing on its own merits as a simplification, and
it is not an alternative to Route A.

Take Route A. Route B is a follow-on, not a substitute.

## What Route A costs, which is the part to design rather than discover

The prototype broke **thirteen `graphitron-model` test classes**, and the reason is structural rather
than a prototype defect. Those classes seed rows into base tables and read a derived relation
directly, with no capture pass anywhere in the fixture, so a reduction nothing populated returns zero
rows. `ArgmappingPairTest`, `ArgmappingProjectionDefectTest`, `ArgmappingBindingLeafTest`,
`ArgmappingSegmentBindingTest`, `ColumnMatchClaimTest`, `FieldColumnTableTest`,
`FieldRoutineMethodTest`, `NodeTypeTest`, `ReferenceStepTargetTest`, `ResolvedNodeKeyColumnTest`,
`ResolvedNodeKeyProjectionTest`, `SeparateFetchRuleTest` and `TypeBackingTest`.

This is the freshness invariant made visible, and it is a feature of the finding rather than an
obstacle to it. Three things follow.

**The refresh needs a home on the capture cadence, not the detection cadence.** The prototype
populated inside `FactCapture`'s detection pass, which is skipped when a run has no walk reach. A
capture that writes rows and leaves the reduction stale is the failure mode the whole design has to
exclude, so the population belongs immediately after capture, unconditionally, and the ordering has
to be written down rather than inferred.

**The seeded tests have to call the refresh, and they should.** Each of the thirteen classes reads
through a small per-class helper (`rows(dsl)`, `only(dsl)`, `rowFor(dsl, ...)`), so the edit is on the
order of thirty insertions rather than the ninety-six `withSeededStore` call sites. Making it explicit
is the right outcome: these tests pin what a rule returns given rows, and under a reduction "given
rows" acquires a second half, which is "and after the reduction was refreshed".

**Every `_rule` view needs its own comment text.** `SchemaReferencePagesTest` fails the build on any
relation or column with a blank comment, so renaming a view moves its comments to the reduction and
leaves the rule view undocumented, which the gate catches immediately. That is the gate working: the
rule and the reduction are two relations a reader can land on and they owe different sentences, the
view saying what the rule is and the table saying when it was last populated and by whom. Budget the
prose rather than being surprised by it.

**Do not split the readers.** The tempting shortcut is to let the seeded tests keep reading the
`_rule` view while the runtime reads the reduction. That makes the tests exercise different SQL from
production, which is the "two readings of one population" drift this schema's own comments warn
about repeatedly. One new test pinning that the reduction equals its rule after a refresh is the
honest version of that instinct.

## Open for the Spec pass

* **Which relations to reduce.** Two were enough to collapse the measured case; the multiplicity
  table names five more above eight. Reduce on measured need rather than on the ranking alone, and
  record what was left unreduced so the next reader knows it was a decision.
* **The ceiling and the gate.** See the selection-rule section.
* **Readers outside a capture.** The language server and the MCP server open the store without
  running a capture. Establish that they always arrive after one, or give them a stated behaviour
  when they do not, per the "absence needs a stated meaning" rule.
* **Store size and boot.** A reduction adds tables to a schema whose DDL boot already costs 9% of a
  run at these speeds. Worth a glance, not a blocker.

## How to re-measure

```bash
# Split compile from execution for one view, inside the real populated store: temporarily print
# EXPLAIN and two consecutive executions of the same statement beside the read under test.
# dsl.resultQuery("EXPLAIN " + dsl.renderInlined(query)) compiles without executing.

# One generator run end to end.
time mvn test -pl :graphitron-sakila-example -Plocal-db -Dleaf-coverage.skip \
  -Dtest=FixtureWarningsGateTest -Dsurefire.failIfNoSpecifiedTests=false
```

Inline multiplicity needs no build at all: parse `CREATE VIEW` bodies out of
`graphitron-model/src/main/resources/no/sikt/graphitron/model/graphitron-model.sql`, count each
relation's word-boundary references per body, and multiply down the tree from the relation under
study.

Standing caveats: pass `-Plocal-db` or the jOOQ catalog jar is silently emptied and the failures will
be unrelated cascades, and measure against a warm local repository. Figures here were taken on one
4 vCPU sandbox; ratios transfer between machines and absolute seconds do not.

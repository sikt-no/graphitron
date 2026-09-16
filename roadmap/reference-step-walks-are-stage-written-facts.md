---
id: R954
title: "A resolved @reference path is a stored fact a capture stage writes, not a recursive view every reader re-walks"
status: Backlog
bucket: architecture
priority: 1
theme: model-cleanup
depends-on: []
created: 2026-09-16
last-updated: 2026-09-16
---

# A resolved @reference path is a stored fact a capture stage writes, not a recursive view every reader re-walks

## Goal

A consumer's build resolves each authored `@reference` path once, when the schema is captured, and
every reader afterwards selects the resolved hops from a table. Today the path is resolved by three
sibling recursive SQL views, one per departure site, each carrying window functions no outer
predicate prunes, and one of the three is named on the inner side of a `LEFT JOIN` where it is
re-evaluated once per driving row. That makes the rule over it quadratic in the size of the store,
which on the `sis` consumer is a `graphitron:dev` registration that runs past twenty minutes. When
this lands the three walks are stored facts a capture stage writes, with primary keys and indexes,
reading a resolved path costs a seek, and `meta_materialize` loses the rows that stood over them.

Three terms, glossed once. The *fact store* is the H2 database each generator pass captures the
schema, the jOOQ catalog and the classpath into, and answers its verdicts out of by SQL. A *capture
stage* is a Java step of a gatherer that computes a relation and writes its rows, of which
`graphitron-model`'s `derive` package holds about thirty; the graphitron gatherer runs its own stages
in a declared order in `GraphitronFactCapture.capture`, each reading what the one before it wrote. A
*registration* is a row of `meta_materialize`, which keeps a rule in a view under a `_live` name and
moves the canonical name onto a table the capture refills, so readers meet stored rows instead of
re-evaluating a view.

## Why a stage rather than a registration, and why this is not a new mechanism

The lever order on `docs/architecture/explanation/fact-model.adoc` puts a registration last, and
R876's own worked case says what reaching for it means: proposing to register `intent_jvm_ancestor`
"was reaching for the last lever on a rule whose real problem was that nobody chose where it lived".
This item applies that to the reference walks, and the shape it asks for is already the tree's.

**The precedent is `InputOccurrencePaths`.** It walks the input surface by descending through input
object types, stratified by depth, clears the run's graph partition and re-derives it inside
capture's own transaction, and writes `intent_input_occurrence_path` with a primary key of
`(graph_name, path)`. It carries no registration. Its class javadoc states the criterion that put it
in a stage: cyclic input nesting is legal GraphQL and H2 has no safe recursive view form over a
cyclic graph.

**The reference walk is strictly easier than that precedent.** An authored path is a finite ordered
list of elements. Resolving it is a fold: resolve element 0 from the departure, resolve element 1
from wherever element 0 landed, stop where nothing resolves. There is no cycle to guard, no fixpoint,
no depth stratification and no pass bound, and the `targets` and `candidates` columns are counts per
group that fall out of the same loop. What makes the current form a `WITH RECURSIVE` carrying a
`DENSE_RANK` and two window aggregates is that it was stated in SQL, not anything about the rule.

So the criterion that has been applied is whether a rule *can* be a view rather than whether it
*should* be. The occurrence walk could not, so it got a stage. The reference walks could, so they
stayed views, and the cost surfaced later and somewhere else: first in readers, then in the
registration refresh, and on the `sis` consumer as a round that does not finish.

**The slot is adjacent.** `GraphitronFactCapture.capture`'s last stage is `FieldEndpoints.derive`,
which writes `graphitron_field_table`: where a field departs from and where it arrives. That is the
endpoint pair a reference walk chains between.

## What is in scope

Three sibling walks, which differ only in where the chain departs from, and which all read
`intent_field_reference_step_hop`'s candidate joins:

- `intent_field_reference_step_target`, departing from the enclosing type's own binding. Registered.
- `intent_argument_reference_step_target`, departing from the table the argument's content binds
  against. Registered, by R943's fourth lever.
- `intent_input_field_reference_step_target`, departing from the table the consuming field handed
  the expansion. A view, and the one this investigation measured.

One relation that is not a walk and belongs to a different family. `intent_node_id_decode_hop_live`
inlines an anonymous derived table over `sql_referential_constraint` that counts the foreign keys
connecting an ordered table pair. It reads that one catalog relation and nothing else, so by the
ownership rule R876 states, the latest owner of anything it reads, it belongs to the catalog
gatherer, the one that runs first. It has one reader today and no name, no key and no index. It is in
scope because it is the same defect in the same rule, and because the measurements below say it is
the larger residual once the walks are stored.

Consequential rather than primary: `intent_node_id_decode_hop` is a registered target whose rule is a
join of an endpoint view to two of these walks, and `intent_node_id_decode_hop_column` is a
registration whose stated case is a recursive walk over those very rows. Whether either has anything
left to stand over once the walks are stored is a question this item's Spec should ask rather than
assume.

## What was measured

Fixture only, and that is the first thing this item's Spec owes a correction to. Everything below was
taken on the scaled registry fixture (`MaterializedRegistryFixture.scaledSdl`, with a `@nodeId` input
field added so the `INPUT_FIELD` decode arm has a population), not on a consumer store. Three sweeps
per configuration, best of three, `OPTIMIZE_REUSE_RESULTS` off.

`intent_node_id_decode_hop_live`, which is the statement the materialization refresh issues per
graph:

| decode endpoints | as shipped | input-field walk as a table | walk and foreign-key term as tables |
|---|---|---|---|
| 96 | 73.4 ms | 10.5 ms | 1.0 ms |
| 192 | 264.0 ms | 20.0 ms | 1.4 ms |
| 384 | 1069.4 ms | 41.8 ms | 2.8 ms |

As shipped the rule grows by 3.6 and then 4.05 per doubling, an exponent of about 1.93. Both stored
forms grow by 1.9 to 2.1, so linear. The walk answers in 0.8 to 2.7 ms standalone at these sizes and
the foreign-key term in 0.3 ms over 56 rows at every size, so neither is expensive; what costs is
that each is evaluated once per driving endpoint row. `intent_argument_reference_step_target` costs
nothing measurable, being an indexed table since R943, which is the same claim from the other
direction. Snapshotting the walk was proved to change cost and nothing else: `EXCEPT` in both
directions returned zero rows at every size.

Two further readings on the same fixture:

**The refresh's graph predicate does not prune.** With three graphs of 72 endpoints in one store, the
refresh of a single graph came out at 170.9 ms against 175.3 ms for the same statement with no
predicate at all, and 22.6 ms for that graph alone in a store of its own. So a workspace store holding
several subgraph modules pays the rule over all of them at every module's refresh. A stage writes one
graph's partition, so this disappears rather than needing its own lever.

**Rung 3 was tried and does not reach it.** Restating `last_position` as a join to a `GROUP BY`
derived table instead of a window function measured 41.2 s against 170.9 ms, a regression of about
240 times, because it names the body twice. Moving the site discriminator out of the `ON` clause, and
hoisting the walk into a non-recursive `WITH`, each changed nothing. Splitting the rule into one arm
per site buys 3.6 times and stays quadratic, so it is a constant and not a fix; it is recorded here
because it also refutes a claim on `intent_node_id_decode_hop`'s own comment, that a second naming of
the endpoint subtree "would dominate the read", which is not what the measurement says and which
should be corrected wherever this item touches that comment.

## What this item owes before Spec

The measurements above are a shape claim taken on a synthetic population, and the same investigation
already had one conclusion overturned by contact with a real store: it reported that statistics were
not the lever, from a regime that measured identically at every fixture size, which R953's readings on
a copy of the `sis` store contradict. Treat the table above the same way. The first thing this item's
Spec owes is the same four configurations timed on a `sis` store copy, per the `store-performance`
skill's method, including whether R953's lever 2 alone is sufficient.

## Relation to other items

**R876** is the doctrine this item applies, and this is an instance of its thesis rather than a new
argument: every rule gets an owner, and a registration is what a rule with no owner gets given. Two
of the relations in scope are the misplacement R876 names, one in the `sql_` family and one that
belongs to a stage rather than to the register.

**R953** is a different defect on an overlapping path, and the two compose rather than competing. It
diagnoses a statistics cliff: `intent_field_reference_step_hop.graph_name` at H2's default selectivity
makes the planner choose a one-column index over the step index, and the first refresh on a store
plans with no statistics at all. That is a cliff in the cost of *one* evaluation. This item is about
the *number* of evaluations. They multiply, because these walks join that very table in both the
anchor and the step, so each of N driving rows pays a bad plan. R953's levers 1 and 2 divide the
constant and leave the exponent; this item changes the exponent and leaves R953's trap as a constant.
R953's lever 3 proposes registering the field-site walk; if this item lands, that lever becomes a
stage instead, and the two items should agree on which before either implements.

**R942** is a gate rather than a fix and does not overlap, but one reading here is evidence for its
Spec. Its detector drops the `INNER_SIDE` position, because `ViewReferences`' javadoc records that an
inner-side reading is the executed shape "only where the join order is fixed, which is the outer
joins", and asks for such a naming to be priced rather than asserted. `intent_node_id_decode_hop_live`
naming the input-field walk is one of those, now priced. A scan of the DDL puts the outer-join subset
at 9 distinct (reader, view) pairs across 12 namings, against 45 distinct on inner joins, so if R942
wants to widen it is a nine-row question rather than the fifty-six R942 costed for the whole
inner-side set. Note also that no scope of R942 reaches the foreign-key term, which names a table
rather than a view.

**R899** is evidence, not a dependency. It exists to make a registration's alternative countable so
the last lever stops being the first reached for, and stands demoted on the argument that R876
dissolves the register and leaves it without a subject. The recent record runs the other way: R939
registered the decode hop, R943 registered two more, R953's lever 3 proposes another, and this
investigation's own first recommendation was a fifth before the stage precedent was found.

**R857** and **R872** would stop a dev round refreshing this rule when the edit did not touch it,
which mitigates the symptom on the dev loop and does nothing for a `generate` or for CI.

## Other solutions we've considered

- **Register `intent_input_field_reference_step_target`.** This was the first recommendation and it
  is rung 4 reached for first. It buys the same rows on disk by a mechanism that schedules a refresh
  for a rule with no owner, and it grows a register R876 is trying to leave with no work. The
  measurements above were taken against a snapshot table, so they price a stage just as well.
- **R953's lever 2 alone**, a static `SELECTIVITY 1` on the partition column in the DDL. Cheap,
  already specced, and orthogonal: it fixes which index one evaluation picks and does not reduce the
  number of evaluations. It should land whatever this item does.
- **A rewrite of the rule.** Three were measured, above. One regressed by about 240 times, two
  changed nothing, and the one that helped left the exponent alone.
- **Waiting for computed ownership.** An earlier reading of this held that a stage needs R877's
  declarations and per-gatherer transaction control first, so a register row was the only move
  available. `InputOccurrencePaths` refutes that: it writes its rows in a stage inside capture's own
  transaction, carries no registration and no ownership declaration, and landed without either. The
  prerequisite is real for the statistics question, which is R953's, and not for writing the rows.

## Provenance

Filed 2026-09-16 from an investigation into a `graphitron:dev` registration on the `sis` consumer
taking more than twenty minutes, which began as a question about `intent_node_id_decode_hop_live` and
found the shape defect underneath it. The investigation's instrument was a throwaway probe in a
scratch directory, which is the failure mode R899 names, so its figures are recorded above rather
than left to be re-derived. Two of its conclusions were overturned before filing and are recorded in
this body rather than dropped: that statistics were not the lever, and that the window function was
what blocked the graph predicate from pruning.

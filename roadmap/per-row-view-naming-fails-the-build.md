---
id: R942
title: "A rule that evaluates an unregistered view once per driving row fails the build, whoever writes the next one"
status: Backlog
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
stops the build that introduces it instead of reaching a consumer. An `intent_` *rule* is one of the
generator's verdicts stated as a SQL view over captured facts; a *registration* is what keeps such a
rule in a table the capture refills once per pass, so readers meet stored rows instead of
re-evaluating the rule. H2 inlines a view wherever it is named and eliminates no common
subexpression, so a rule named once inside a correlated subquery, on the inner side of a join, or
inside a recursive term is evaluated once per row of whatever drives it. That is the whole mechanism
behind a dev round on a real consumer schema costing minutes instead of seconds, and today nothing
refuses it: the shape is invisible in the SQL text, where the naming appears exactly once, and it is
invisible to the cost gates, whose fixtures are a dozen units when the cost is a function of the
consumer's population. When this lands, that shape is a build error naming the two relations and the
position, with a pinned roster for the ones a measurement has argued for, so the next author meets
it while the fix is still free rather than a consumer meeting it as a dev loop that does not start.

## Why this is cheap: the detection already runs

`ViewReferences` parses every stored view definition on store boot and already answers this exact
question. `ViewReferences.Position` names the three re-evaluating positions (`INNER_SIDE`,
`CORRELATED`, `RECURSIVE`), `ViewReferences.Enclosure` carries what each one is re-evaluated
against, and `ViewReferences.Reference#reEvaluated` answers whether anything re-evaluates a
reference beyond the once its naming already costs. Nothing in the tree reads that answer and fails
on it. So the work is a gate over data the build already computes, not new machinery.

Two constraints for whoever specs this, both from what the tree already records. The gate has to be
a structural predicate with a pinned exception roster, not a cost score: `ViewReferences`' own
javadoc records that weighting these positions into a ranking was built, run against a real capture,
and refused, because it did not reproduce the register's recorded savings and was worse than a plain
count exactly where it mattered. And the scope question is which references the predicate ranges
over, since a re-evaluated naming of a registered target is a seek against a table and fine; what is
not fine is a re-evaluated naming of an unregistered view.

## Relation to the reach pin

R939, the `@nodeId` landing read-cost item, leaves behind a per-reader pin of the view bodies each
detection component evaluates live. That makes a new reach visible and requires an author to write
it down; it does not refuse the shape. This item states the defect as a property of the DDL instead,
which is what makes it hold for every future rule rather than for the readers that item happened to
touch. The two compose: the pin says what a reader reaches, this says what a reader may not reach
that way.

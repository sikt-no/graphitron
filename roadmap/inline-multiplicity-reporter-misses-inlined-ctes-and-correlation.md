---
id: R871
title: "report-inline-multiplicity counts textual references, so it cannot see an inlined CTE or a correlation"
status: Backlog
bucket: cleanup
priority: 5
theme: model-cleanup
depends-on: []
created: 2026-08-28
last-updated: 2026-08-28
---

# report-inline-multiplicity counts textual references, so it cannot see an inlined CTE or a correlation

`report-inline-multiplicity`, a reporting step in the roadmap-tool run wired into `roadmap-tool`'s
pom and documented on `docs/architecture/explanation/fact-model.adoc`, ranks derived views by how many
relation instantiations one read of each expands to. It computes that from the DDL text alone: each
relation's references counted in each `CREATE VIEW` body and multiplied down the tree. Two things it
cannot see are the two things that made the most expensive statement in the fact store expensive, and
there is now a worked case for both.

## The worked case

R856 investigated a consumer capture that spends over an hour inside the materialization refresh and
localised the cost to two relations. Both blind spots are in that localisation:

- **A local CTE name is invisible to a textual reference count**, and H2 inlines a non-recursive
  `WITH` exactly like a view. `intent_mutation_payload_refusal_live` names its `refused` CTE twice and
  `intent_mutation_payload_column_live` names its `admitted` CTE twice, once per union arm, and each
  naming reaches `intent_input_field_carrier_role` through a further CTE. So the reporter
  under-reported the carrier view's expansion by half in each of the two views.
- **Correlation is invisible outright**, and it is the factor that turns a constant into a driving-row
  count. One of the refusal view's two namings sits inside a correlated `NOT EXISTS`, and the measured
  mechanism is one whole evaluation of the carrier view per driving row.

Neither relation appears in the reporter's ranking as a suspect, and both were found by timing
statements instead.

## What is not the finding

The metric's own documentation is careful that it ranks breadth and not cost, and says so as the
reason it reports rather than gates: 2528 namings of a relation answering in 0.4 s is not a problem
and 83 namings of one answering in 20 s was. So this is not a tool making a claim it cannot support.
The finding is narrower and it is about the tool's continued place rather than its correctness: the
two relations it missed are the two whose refresh statements stopped captures from finishing, which is
the sharpest available case that a breadth ranking does not name suspects on this schema any more.

## Why this is filed rather than folded

R849 built a weighted re-evaluation metric over the same DDL, ran it against a real capture, failed
its own pre-committed acceptance gate, and executed the negative branch: `ReEvaluationMetric` and its
test are deleted, `ViewReferences` and its positions are kept. `roadmap/changelog.md` carries the
figures. That item's negative branch asked whether a shipped step should be deleted rather than kept
as a nearly-right one, and it asked it of the metric R849 itself built. `report-inline-multiplicity`
survived that pass without the question being put to it, and R849 is Done, so there is no open item
where it can be. R848 is the item arguing the register's shape as a whole and is In Progress; it
declines instrument work explicitly, R849 having established that a static reading of the definitions
cannot rank the twenty registrations.

## What a Spec here would decide

Three dispositions, and the point of the item is to pick one on evidence rather than to assume the
third:

1. **Keep it as it is**, with the two blind spots stated on the fact model page beside the
   breadth-not-cost sentence that is already there, so a reader knows a low rank is not a clean bill.
2. **Correct it.** `ViewReferences` is in the tree reading multiplicity, correlated positions and
   recursive CTEs off H2's normalized stored view definitions through jOOQ's query object model, which
   is precisely what a textual count cannot do. Re-basing the report on that parse is the version of
   this that keeps a ranking and makes it see what it currently misses. It needs a booted store where
   the current step needs no database, which is the cost to weigh, and R849's result bounds what the
   output may claim: positions can be read, a cost ranking cannot be derived from them.
3. **Delete it.** R849's negative branch is the precedent for the shape of that argument, and it
   would be the honest outcome if nobody can name a decision the ranking has informed.

Whichever is picked, the two blind spots want recording where a reader of the metric meets them,
which is the fact model page rather than this file.

## Related

R856 is where the two blind spots were found, and its Roadmap entries section is where this item's
disposal was decided. R849 is the metric item that reached Done as a negative result; its figures are
in `roadmap/changelog.md`. R848 argues the register's shape as a whole.

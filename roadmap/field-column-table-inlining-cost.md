---
id: R781
title: "intent_field_column_table costs 151 seconds for 116 rows, and it is the inlining"
status: Backlog
bucket: model
priority: 2
theme: model-cleanup
depends-on: []
created: 2026-08-21
last-updated: 2026-08-21
---

# intent_field_column_table costs 151 seconds for 116 rows, and it is the inlining

`intent_field_column_table` takes 151 seconds to return 116 rows against a realistically populated
store, and nothing under it is the reason. Measured by the acceptance dry run of the store-performance
skill: a real capture of the sakila example's 4111-line schema against the sakila jOOQ catalog, the
relation timed in isolation, then each of nine children timed the same way. The children are all
cheap. `intent_argmapping_key_column_candidate` is 0.75 s, `intent_argmapping_binding_leaf` and
`intent_field_accessor_hop` are about 27 ms, and the other six are between 0 and 7 ms with four of
them empty. So there is no expensive child to reach for and nothing underneath it worth registering,
which is the push-down lever ruled out by measurement rather than by taste.

What the plan says instead is that the cost is the expansion. `EXPLAIN ANALYZE` over the relation
produces 807 scan nodes and 1.37 MB of plan text, and its scan counts are not one large number but
the same middling number repeated: 5700 twice, then 4148 a dozen times over. That is view inlining
with no common-subexpression elimination, the rule stated on
`docs/architecture/explanation/fact-model.adoc` under "Derived reads are views, not stored facts",
arriving as a plan. It is also what the relation's static multiplicity predicts: the
`report-inline-multiplicity` ranking puts it fifth in the schema at 533 instantiations per read.

Nothing reads this relation at build time in a way that pays the 151 seconds today, which is why the
cost is not currently visible. That makes it the same shape as the hop-column registration's own
history: a cost that arrives with its first real reader, and a lever best pulled in the increment
that adds one rather than left as a surprise for it. The question this item has to answer is which
lever, in the order the page states. Whether the subject itself earns a `meta_materialize`
registration turns on its reader count and the price of its refresh, and the refresh here is one
evaluation of a 151-second view, so that trade is not obviously winnable and wants measuring rather
than assuming. Whether some part of the derivation should instead become a captured fact, the top
rung, has not been looked at at all.

One thing to know before theorising: the 533 is already net of the registry. Two of this relation's
children are registered and therefore tables at read time, `intent_argmapping_pair` named on fifty
lines of the view body and `intent_spelled_table` on three, and the multiplicity report exempts a
materialized relation by construction, a table's subtree being itself. So the breadth the ranking
reports is live breadth and the 151 seconds is not waiting on a registration that already exists.
Which means the first move is attribution rather than a lever: read the plan's scan nodes back to the
relations they belong to, so the 151 seconds is apportioned across the tree instead of guessed at.
The repeated 4148 is the number to explain first, since fifty scans of a thirty-nine-row table is not
it.

Found by, not caused by, the store-performance skill's dry run. Filed separately rather than widened
into that item, whose subject is the methodology.

## A live reader has since turned up, which moves the premise above

Added by the session that filed `roadmap/diagnostics-drain-overruns-its-session-budget.md`. The claim
that nothing reads this relation in a way that pays the cost today is no longer safe. The language
server's diagnostics drain reads it, in the one statement `DiagnosticFacts` issues per graph, and on a
real dev session that statement overran its 30 s budget and was aborted, so a developer's diagnostics
stopped tracking their schema. That is the "first real reader" this item's own reasoning says the lever
should be pulled for, and it is not a build-time reader, which is why the build's timings did not see
it coming.

What it does not establish is that this relation is the expensive term in that statement. The drain
reads it filtered by graph and by explicit type-field pairs, unlike the 151-second unfiltered
measurement, and the statement also carries the census-side join shape that
`roadmap/lsp-surface-latency-budgets.md` measured at about 1.1 s per evaluation on this store.
Attribution across that statement's roughly twenty subqueries belongs to the drain's own item; what
belongs here is that the reader now exists and the cost has somewhere to land.

## The drain item has since done the attribution, and most of this item's premise moved

Added by the session that implemented `roadmap/diagnostics-drain-overruns-its-session-budget.md`,
whose write-up carries the measurements. Confirmed here: the drain's read of this relation paid the
full unfiltered evaluation, 131 s on a comparable box, because the filters never prune past the
ROW_NUMBER. The 4148-style repeated scans this item said to explain first were attributed:
`sql_constraint_column` under the reference-step machinery, re-entered once per naming and once per
row of the unresolved-path arm's correlated NOT EXISTS against `intent_field_column_scope`, on top
of `intent_resolved_type_binding` being re-evaluated per naming through its own COUNT(*) OVER.

Two registrations landed there rather than here, because the drain was the live reader: 
`intent_resolved_type_binding` and `intent_field_column_scope` are now `meta_materialize` rows whose
`reason` columns carry the arithmetic. One evaluation of this relation is now about 144 ms on the
same capture, from 131 s. What remains for this item is the smaller question of whether that
residual, and this view's remaining static breadth, still earn work of their own, and the answer
should start from fresh numbers rather than from the 151 s in the title.

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

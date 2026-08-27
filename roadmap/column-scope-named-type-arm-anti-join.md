---
id: R850
title: "The column scope named-type arm cannot be repointed while its authored-claim anti-join stands"
status: Backlog
bucket: bug
priority: 2
theme: classification-model
depends-on: []
created: 2026-08-27
last-updated: 2026-08-27
---

# The column scope named-type arm cannot be repointed while its authored-claim anti-join stands

`intent_field_column_scope`'s `NAMED_TYPE_TABLE` arm answers where a column name written at an
object-typed field resolves: the table the field's named type binds. It navigates connections by
reading `graphitron_field_synthesis`, so it answers for a connection the generator synthesised and
is silent about one the author declared in the SDL. That is the same silence R846 diagnosed on
`intent_field_scope_table`, at a second site, and it means an ordering column named on a field
returning an author-declared connection has no scope.

The fix exists and this arm cannot take it. `intent_field_navigated_type` states the whole
navigation once and the other four sites that spelled the same `COALESCE` now read it. Repointing
this one makes the arm fifty times slower, and every other form that names a derived relation in
the navigated-type position is worse still.

## What was measured

On a store captured from the sakila example schema, 928 fields, this arm alone, 39 rows out:

[cols="3,1"]
|===
| shape | one evaluation

| the shipped inline expression over base tables
| 89 ms

| joining `intent_field_navigated_type`
| 4308 ms

| reaching `intent_field_navigated_type` by correlated scalar subquery
| did not finish in 200 s

| reaching `intent_connection_element_type` by correlated scalar subquery
| did not finish in 200 s

| spelling the `edges`/`node` shape inline as a base-table subquery
| did not finish in 200 s
|===

Materializing `intent_field_navigated_type` into an indexed table and joining that measured 3952 ms,
so the cost is not the navigation relation being a view.

## Where the cost actually is

`EXPLAIN ANALYZE` names it. The arm carries three anti-joins, and one of them correlates
`intent_authored_field_claim`, which is a recursive view costing 29 milliseconds to evaluate whole.
With the navigated type spelled as a literal expression over base tables, H2 drives from
`graphql_field` and the anti-join runs against a narrow surviving set. The moment the navigated type
comes from anywhere else, H2 drives from `intent_resolved_type_binding` instead and re-evaluates the
recursive view once per driving row.

So the arm is fast by luck of a plan rather than by construction, and the repointing did not create
the defect, it exposed it. Any future change to this arm's driving side will hit the same wall.

## What to check when picking this up

Whether the anti-join can be evaluated once. `NOT IN` over a row value and a `WITH` common table
expression were both tried and neither terminated, H2 materializing neither. That leaves two
candidates: registering `intent_authored_field_claim` so the anti-join probes a table rather than
re-running a recursive walk, or restating the claim relation so that the recursion is not on the
correlated side. The first is a materialization decision and belongs in the whole-register question
R848 asks rather than being taken to rescue one arm; the second is the rewrite this family's own
rule says to try first.

Only once one of those lands can this arm read `intent_field_navigated_type`, which is what closes
the silence. Until then the arm's own comment carries these figures so nobody repeats the
experiment.

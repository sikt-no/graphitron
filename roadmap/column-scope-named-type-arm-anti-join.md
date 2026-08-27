---
id: R850
title: "The column scope named-type arm cannot be repointed while its authored-claim anti-join stands"
status: Spec
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

## Notes carried from Backlog

The Backlog body asked whether the anti-join can be evaluated once. `NOT IN` over a row value and a
`WITH` common table expression were both tried and neither terminated, H2 materializing neither.
That left two candidates: registering `intent_authored_field_claim` so the anti-join probes a table
rather than re-running a recursive walk, or restating the claim relation so that the recursion is
not on the correlated side. The first is a materialization decision and belongs in the
whole-register question R848 asks rather than being taken to rescue one arm; the second is the
rewrite this family's own rule says to try first. The spec below ranks a third form ahead of both,
because the fact model's own reader rule for window- and recursion-carrying views points at the
reader, not the relation.

## Decision: de-correlate the reader first, name the closure second, split the arms last

The Backlog framed the question as how to make the recursive view cheap to probe. The fact model
already states how a reader takes a view carrying a window function or a recursive term: once per
answer, paired on its key, never correlated per row. The arm's anti-join violates that rule today
and survives on plan luck, which is the defect as filed. So the first candidate is the prescribed
reader shape rather than any new relation, and a schema change has to be earned by measurement
instead of being committed ahead of one. Three candidates, ranked; all are measured in one sweep
before any DDL lands.

**A. De-correlate the anti-join.** Take the claim keys once, as a derived table in the arm's own
`FROM`: a `LEFT JOIN` on `SELECT DISTINCT graph_name, type_name, field_name FROM
intent_authored_field_claim` with an `IS NULL` filter, replacing the correlated `NOT EXISTS`. No
new relation, no equivalence argument, no naming decision. The Backlog measured `NOT IN` and a
`WITH` common table expression; the joined `DISTINCT` key projection was not among the failures,
and it is the shape the fact model prescribes for exactly this reader.

**B. Name the recursive closure.** The claim view's recursion is the `lookup_bearing` CTE, and it
has a genuine grain: one input object type from which a `@lookupKey`-marked argument is reachable.
Promoted to a named relation (the name is settled at implementation; that grain sentence is what
it must state), the `@lookupKey` arm takes one join instead of an inline closure. This is the
restatement the Backlog's second candidate names, it follows the precedent of splitting a
derivation where only part of it needs recursion, and it hands R848 a registrable candidate whose
name is already earned should the whole-register question later want one. Whether it flips this
arm's plan on its own is a measurement; it composes with A.

**C. Restrict the probe to the arms whose masks admit this arm's population.** The structural
observation is real: the arm drives only non-root parents (`f.type_name NOT IN ('Query',
'Mutation', 'Subscription')`), the `LOOKUP_KEY` arm claims only at `Query` and feeds the only
recursion, the `MUTATION` arms only at `Mutation`, and the remaining four arm-pairs (`SERVICE`,
`EXTERNAL_FIELD`, `NODE_ID`, `ROUTINE`) read base tables only. So the recursion is unreachable
from this reader's population, and probing only the reachable arms would defuse it. Taking that as
the fix has real costs, which is why it ranks last: the restricted set has no honest row-grain
name, because what carves it is a property of the arms rather than of the rows; the equivalence
rests on literal-name masks on both sides that nothing binds, in a store that elsewhere prefers
the `graphql_root_operation` binding over literal root names; and every future claim arm (`@pivot`
is the named next one) inherits a which-layer decision whose wrong answer silently un-guards this
arm. If A and B, alone or composed, miss the gate, C is prototyped inline in a measurement probe,
never as DDL first; a relation earns its name from the result. If C ships, its enforcer is a
both-directions `EXCEPT` between the full and the restricted claim sets over a populated store,
restricted to the arm's own population, because a mask widening would never be exercised by rows
nobody thought to seed into a fixture.

### The measurement protocol and the gate

Per the store-performance methodology, against the store the sakila example build leaves on disk:
time the shipped arm, then A, B, A composed with B, and C inline, each with the navigated-type
repoint in place, in one sweep. The gate is framed by what the relation is: intent_field_column_scope
is registered, so this arm's cost is refresh cost, paid once per graph per capture, and not read
cost. The bar is that the rewritten arm must not become the dominant term of the
intent_field_column_scope refresh, with the shipped 89 ms as the reference point; the 4308 ms
plan-flip is the failure the gate exists to catch. The winning form and the losing figures are
recorded in the implementation commit message, and no relation comment carries the arithmetic
forward.

### The repoint

With the anti-join defused, the arm repoints: the inline `LEFT JOIN graphitron_field_synthesis`
plus `COALESCE` is replaced by a join to `intent_field_navigated_type`, the same read its four
sibling sites take. That closes the silence: a non-root field returning an author-declared
connection type gets a `NAMED_TYPE_TABLE` row at the element type's binding, because the
navigation relation's `CONNECTION_ELEMENT` rung answers where the synthesis record is silent. The
`basis` vocabulary does not change; the rule is still "the named type's own binding", now with the
named type read from the relation that states it once. The `DISTINCT`, the root guard, the
reference-step guard and the `@pivot` guard are all unchanged, and the `@pivot` guard's
fold-into-the-anti-join note stays; that day is still not this item's.

## Deliverables

### The winning anti-join rewrite in `graphitron-model.sql`

Whichever of A, B, A-plus-B or C the sweep selects. B's relation, if it ships, carries a comment
on the store's own terms: the grain sentence above, the `@lookupKey` arm as its first reader, and
nothing about this arm's plan. If C ships, it ships with the `EXCEPT` enforcer and a comment that
names the mask alignment it depends on.

### The arm rewrite in `intent_field_column_scope_live`

The de-correlated or restated anti-join, plus the repoint to `intent_field_navigated_type`, as the
Decision section states them.

### Comment deletions at the two relations that document the defect

`intent_field_column_scope`'s comment deletes the measurements and the "the one site that cannot"
narrative outright; once the arm is repointed they describe a shape that no longer exists in the
tree, and carrying figures forward onto a rewritten body is the drift the store-performance
discipline warns about. The named-type rule is stated as reading the navigation relation, like its
siblings. `intent_field_navigated_type`'s comment drops the fifth-site paragraph and replaces the
"count to hold this relation to is four" sentence with the rule itself: every site that needs a
field's navigated type reads this relation. The count has been wrong twice by construction, which
is what an unguarded inventory does. The Backlog figures live on in this file's history and in the
measurement section above.

### Tests at the layers that observe the change

- A seeded `graphitron-model` intent-tier anchor (beside `FieldColumnTableTest`'s existing cases):
  a non-root field returning a type with the structural `edges`/`node` shape and **no synthesis
  row** resolves `NAMED_TYPE_TABLE` scope at the element type's binding. This is the closed
  silence, pinned at the grain it lives at. The existing synthesis-driven case
  (`aConnectionFieldResolvesItsElementsTable`) stays green beside it.
- The relation's live consumers are the walk differential (`ColumnMatchShadowTest`) and the LSP's
  `FieldColumnTable` surface, not the emit path. The implementation names which of them moves on
  the new rows and pins the movement there; a pipeline-tier fixture is added only if an emit-path
  observer of column scope at such a coordinate is identified, because a fixture at a tier that
  cannot observe the change pins nothing.
- `AuthoredClaimTest` extended with a row-set pin over a store seeded with all six claim kinds:
  the full claim view's rows are identical across whatever restatement ships.
- If C ships: the both-directions `EXCEPT` enforcer over a populated store.

### One sentence owed to R848

The implementation states, in this file before Done, what the chosen rewrite did to the register's
as-composed depth (B adds one relation to it) and whether the result is a rung R848's
whole-register pass would want to fold, so the next reader of the register is not left to discover
it.

## Out of scope

- Registering anything in `meta_materialize`; that is R848's whole-register question, and this
  design deliberately avoids pre-empting it. Naming the closure (B) is not registering it.
- The `@pivot` claim arm and folding the explicit `@pivot` guard into the anti-join.
- Column scope at root coordinates: the arm's root guard is a rule boundary, not part of this
  defect, and it does not move.
- `intent_field_column_table`, the column-match classifier, and every other consumer downstream of
  the scope relation; they read the same relation with more rows in it.

## Acceptance

- The named seeded anchor showing a non-root author-declared connection field with a
  `NAMED_TYPE_TABLE` scope row at its element's table: the silence this item exists to close,
  demonstrated at the tier that observes the relation directly.
- All five sites named by `intent_field_navigated_type`'s comment read the navigation relation;
  the synthesis `COALESCE` spelling appears nowhere in `intent_field_column_scope_live`.
- The measurement sweep's figures are recorded in the implementation commit message and the
  rewritten arm is inside the gate as the protocol section frames it.
- The two relation comments carry no measurement arithmetic and no fifth-site narrative; the
  claim-view row-set pin is green; if C shipped, its enforcer is green.
- The verification build is green.

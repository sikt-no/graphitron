---
id: R850
title: "The column scope named-type arm cannot be repointed while its type binding hangs off graphql_type"
status: In Progress
bucket: bug
priority: 2
theme: classification-model
depends-on: []
created: 2026-08-27
last-updated: 2026-08-27
---

# The column scope named-type arm cannot be repointed while its type binding hangs off graphql_type

> **The title changed at implementation.** It named the authored-claim anti-join as what blocks the
> repoint, because that is what the Backlog's reading of the plan said. The sweep's floor control
> refuted it: with the anti-join deleted from the statement outright the repoint still costs seventy
> times the shipped arm. The blocker is one join in the arm's own graph, and the
> "What the sweep measured" section below carries the correction, the figures and what shipped.
> Everything above that section is the plan as it was signed off, left as written.

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

**B. Name the recursive closure.** The claim view's recursion is the `lookup_bearing` CTE. Its seed
is `graphitron_field_lookup_key`, which is `@lookupKey` on an *input field*, the retired site, and
its recursive term walks `input_object_field_edge` upward from there. So a row means: this input
object type transitively contains an input field carrying the retired `@lookupKey`. That is the
grain sentence the promoted relation must state (the name itself is settled at implementation), and
the relation reads that table and not `graphitron_argument_lookup_key`: a directly marked argument
is the arm's other trigger and stays in the `direct` join, untouched. Promoted, the recursion leaves
the claim view's body and becomes a relation of its own, so the view stops carrying a recursive term
and the arm's inner `EXISTS` probes something with a name.

Two things follow from that seed being the retired site, and both narrow B's case from the way the
Backlog framed it. On a schema the build accepts the relation is empty, because classification
rejects `@lookupKey` on an input field outright; capture writes the row whenever the directive is
present, so the population is exactly the schemas mid-migration, which is what a rejected build's
own store holds and what the language server reads while an author is half way through the move. So
B does not hand R848 a registrable candidate after all, and that argument for it is withdrawn:
registering a relation that is empty wherever the build succeeds buys no refresh cost back. And
because H2 inlines a view the way it inlines a non-recursive `WITH`, promoting the closure may leave
the reader's plan exactly where it is. What survives is the argument this family's own rule makes:
the recursion becomes a relation with a grain, a comment and somewhere to be pinned, instead of a
term buried in one arm of the claim view's union. That is worth having on its own, it composes with
A, and whether it moves this arm's plan is a measurement like everything else here.

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

This sweep is not the only thing that measures this arm. `DerivedReadCostTest` is the build's own
cost gate over the same relation, and it holds a claim the sweep does not: what the rewrite costs
every *other* reader of the registrations this arm reaches. The sweep picks the form; that gate says
whether the form is affordable elsewhere, and what it owes is stated with the other tests below.

### The repoint

With the anti-join defused, the arm repoints: the inline `LEFT JOIN graphitron_field_synthesis`
plus `COALESCE` is replaced by a join to `intent_field_navigated_type`, the same read its four
sibling sites take. That closes the silence: a non-root field returning an author-declared
connection type gets a `NAMED_TYPE_TABLE` row at the element type's binding, because the
navigation relation's `CONNECTION_ELEMENT` rung answers where the synthesis record is silent. The
`basis` vocabulary does not change; the rule is still "the named type's own binding", now with the
named type read from the relation that states it once. The arm's three guards, the root guard, the
reference-step guard and the `@pivot` guard, are all unchanged, and the `@pivot` guard's
fold-into-the-anti-join note stays; that day is still not this item's.

## What the sweep measured

The protocol above says no DDL lands before the sweep, and this is what the sweep was for. All four
ranked candidates were measured with the repoint in place, on a store captured from the sakila
example schema, 928 fields, this arm alone, 39 rows out, interleaved sweeps with result reuse off.
None of them is the fix, and three of them move the arm by nothing at all.

Every figure below is re-taken rather than carried from the Backlog's table, which is why the
shipped arm reads 51 to 55 ms here where that table records 89: a wall clock is a property of the
machine it was taken on, so nothing in this section is compared against a figure from another run.
The shipped arm is re-measured alongside each candidate for exactly that reason.

[cols="3,1"]
|===
| shape | one evaluation

| the shipped arm
| 51 to 55 ms

| the repoint alone
| 3399 to 3430 ms

| A, the de-correlated anti-join
| 3277 to 3578 ms

| B, the promoted closure
| 3392 to 3490 ms

| A composed with B
| 3247 to 3403 ms

| C, the probe restricted to the reachable arms
| 507 to 516 ms
|===

### The diagnosis was wrong, and the control that says so is the cheapest one

The floor control the store-performance procedure asks for, removing the suspect from the statement
entirely, refutes the Decision section's whole premise. With the authored-claim anti-join deleted
outright the arm costs 3 ms over the synthesis expression and 203 to 213 ms with the navigated type
read from `intent_field_navigated_type`. So the plan flip happens with no anti-join in the statement
at all: the anti-join is not what flips the plan, it is what the flipped plan makes expensive. That
also explains why A changes nothing, and the explanation is a rule already recorded in
`DerivedReadCostTest`: H2 re-evaluates a derived relation on the inner side of a join once per
driving row whatever the join is spelled as, so pairing the claim keys on their key buys nothing
while the driving side is 62 thousand rows rather than 928.

`EXPLAIN ANALYZE` names the real cause, and it is one join. The arm joined
`intent_resolved_type_binding` on `graphql_type`'s echo of the navigated type rather than on the
navigated type itself. With the navigation spelled as an expression over the field's own columns
that costs nothing, because the binding can only be reached through a chain that starts at
`graphql_field`. With the navigation projected into a column of a relation, the same spelling makes
the binding a legal driver and the cheapest relation in the join graph, 68 rows after its
`candidates = 1` filter, so H2 drives from it; and a view is seekable on the coordinate it is keyed
by and not on a name it projects, so the navigation lands on the probed side and is evaluated once
per driving row.

### What shipped

Three changes, each one measured, and each one a consequence of the one before it.

[cols="3,1"]
|===
| shape | one evaluation

| the repoint, with the binding joined on `nv.navigated_type_name`
| 50 to 56 ms

| the same, with the OBJECT test spelled as an existence test
| 14 ms

| the same, with A on top: what ships
| 6 to 7 ms
|===

Binding the type binding to the navigation relation's own projection is what removes the flip: with
nothing hanging the binding off `graphql_type`, the navigation drives and every other term is a
seek. That alone puts the repoint at the shipped arm's cost with the anti-join untouched, which is
the reading that settles the item: the arm was always repointable. Once the binding reads
`nv.navigated_type_name`, `graphql_type` contributes no column to the arm, so its OBJECT test is
spelled as the existence test it had become, and that is worth 50 ms to 14. A then goes on top, not
because the sweep needed it but because it is the reader rule at the head of `graphitron-model.sql`
applied to the reader that violated it, and it measures better rather than worse.

Same-run before and after, all three statements in one capture: the arm 47 to 60 ms before and 6 to
10 ms after, and `intent_field_column_scope_live` whole 10 to 12 ms after. The whole relation now
costs a fifth of what its named-type arm alone cost, so the gate's question, whether the rewritten
arm becomes the dominant term of the refresh, does not arise in either direction.

### B and C did not ship, and the sweep is why

B, promoting the recursive closure to a relation of its own, was measured at 3392 to 3490 ms against
the repoint's 3399 to 3430: no effect, which the plan predicted it might have, H2 inlining a view the
way it inlines a non-recursive `WITH`. That leaves B standing on the restatement argument alone, and
against that argument stands what the plan already established about the relation it would add: on
every schema the build accepts it is empty. Adding an empty relation to the composition to restate a
term that costs nothing where it is, in an item whose measured fix is elsewhere, is not a trade this
increment should make. It is a defensible piece of work on its own and it is not this one; nothing
in the shipped arm forecloses it.

C did not ship for the reason the plan ranked it last. It was the only ranked candidate that moved
anything, 507 to 516 ms against 3399, and that is exactly the shape of a partial answer: it defuses
the amplification without touching the flip, so it would have bought a tenfold improvement on a
statement that is now sixty times cheaper without it. None of its costs, the row grain with no honest
name, the unbound mask alignment, the which-layer decision every future claim arm inherits, needed
to be paid.

### The sentence owed to R848

The shipped rewrite adds no relation to the register's composition and removes none. What it changes
for that question is the arm's reach: `intent_field_column_scope_live` no longer names
`graphitron_field_synthesis` or `graphql_field` at this arm, and names `intent_field_navigated_type`
instead, which reaches both. `DerivedReadCostTest`'s cell count is unmoved at 178, so the register's
as-composed depth is what it was. Nothing here is a rung a whole-register pass would want to fold,
and one thing is worth carrying into that pass as a general fact rather than as this arm's: a
projected name is not a probe key, so a registration that turns a view into a table changes which
side of a join the planner can drive from, and a reader that joins a *second* relation on the
projection rather than on the projecting relation is the shape that turns that into a per-row
re-evaluation. That is the same mirror `ix_resolved_type_binding_type`'s comment records from the
other direction.

### Deliverables the selection made vacuous

Three of the plan's deliverables are conditional on B or C shipping and are therefore not present:
B's relation and its comment, C's both-directions `EXCEPT` enforcer, and the row-set pin over the
claim view. The pin is worth a sentence rather than a silent omission: it was asked for so that a
restatement of `intent_authored_field_claim` could be shown to change no row, and no restatement
ships, the view being untouched by this change. What did change shape is what the arm *reads* from
that view, and a pin over the view's own rows cannot observe that; the arm's answer is pinned where
it belongs, in `FieldColumnTableTest`.

`DerivedReadCostTest`'s pinned set did not move: the cell count, both pinned sets and all four
assertions are green unchanged. The surviving pair
`intent_field_reference_step_hop|intent_field_column_scope_live` had its justification rewritten to
the figures it now records, per the plan: 3272 scans registered against 3084, and 13 milliseconds
against 34, where it recorded 2854 against 2666 and 15 against 29. The difference of 188 scans is
unchanged, which is the reading worth keeping: that gap is the walk's namings against the registered
target, which this arm never touched, so the pair survives on the mechanism it was always charged to.

The relation's live consumers were named in the plan as the walk differential and the language
server's `FieldColumnTable` surface. Neither moves on this fixture set: the rows the repoint adds
exist only at a non-root field returning an author-declared connection type and carrying no
`@reference`, and both such fields in the sakila example schema, `Film.actorsConnection` and
`Film.actorsOrderedConnection`, carry one and so take the path-terminal rule instead. The new
rows are therefore pinned at the tier that can state them as rows, which is the seeded anchor, and no
pipeline-tier fixture was added, per the plan's own instruction that a fixture at a tier that cannot
observe the change pins nothing.

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
- `DerivedReadCostTest`'s pinned set is expected to move, and the implementation states which way
  and why. That gate holds, for every registration and every relation whose derivation reaches its
  target, that reading the registered shape visits no more rows than reading the unregistered one,
  with the known exceptions pinned by equality rather than as an allowlist, so a pair appearing and
  a pair leaving both fail the build until the set is corrected. It already holds
  `intent_field_reference_step_hop|intent_field_column_scope_live`, whose justification records this
  relation at 2854 scans registered against 2666, and 15 milliseconds against 29: figures of the
  plan this item rewrites. A set change is therefore an expected consequence of the rewrite rather
  than a licence to widen an exemption. A pair that goes monotonic is deleted; a pair that survives
  has its justification prose rewritten to the figures it now records, because a stale reason there
  is worse than no row; a pair that appears is answered as an index-or-cost question the way that
  gate's existing rows are, before it is pinned. Scan counts and the sweep's milliseconds are
  different claims and neither figure transfers into the other, which that test states about itself.
- If C ships: the both-directions `EXCEPT` enforcer over a populated store.

### One sentence owed to R848

The implementation states, in this file before Done, what the chosen rewrite did to the register's
as-composed depth (B adds one relation to the composition, and not a registrable one) and whether
the result is a rung R848's whole-register pass would want to fold, so the next reader of the
register is not left to discover it.

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
- All five sites that spelled the synthesis `COALESCE` read the navigation relation:
  `intent_field_scope_table`, `intent_field_participant_scope_table`,
  `intent_routine_return_binding`, `intent_mutation_routine_seat` and this arm. The spelling appears
  nowhere in `intent_field_column_scope_live`.
- The measurement sweep's figures are recorded in the implementation commit message and the
  rewritten arm is inside the gate as the protocol section frames it.
- The two relation comments carry no measurement arithmetic and no fifth-site narrative; the
  claim-view row-set pin is green; if C shipped, its enforcer is green.
- `DerivedReadCostTest`'s pinned set is correct for the shipped form, every surviving row's reason
  matches the figures it records, and this file says what moved there.
- The verification build is green.

## Reviewer findings

### Round 1 (2026-08-27, Spec -> Ready, reviewer session 01ArucGYVJs2dFCVP1JdaQAc)

Verdict: withhold. Two findings, both on question 2. Question 1 passes, and the diagnosis behind
the plan is the strongest part of it: the item is not really about a connection type at all, it is
about an arm that is fast by accident, and saying so is what makes the ranking of candidates
legible.

The goal reads off the plan without reconstructing it. Today an author who writes a connection type
by hand in the SDL and puts it on a non-root field gets no column scope at that site: names written
there resolve against nothing, so the structural column-match reading is silent and the language
server offers no column completions or definitions at that coordinate, while the same field over a
generator-synthesised connection resolves fine. After this lands the field's names resolve against
the element type's table, which is what every sibling site already does. The second outcome is the
one the title is about and matters more to a contributor than to a consumer: the arm stops
depending on the plan H2 happens to pick, so the next person to touch its driving side does not
rediscover a fifty-fold slowdown.

Everything checkable against the tree checked out. The arm is as described, an inline
`LEFT JOIN graphitron_field_synthesis` plus `COALESCE` under three anti-joins, with the root guard
written as literal names and the claim anti-join the one correlating the recursive view.
`intent_field_navigated_type` carries the three rungs and the `AUTHORED_EXPRESSION` /
`CONNECTION_ELEMENT` / `NAMED_TYPE` basis vocabulary, and its comment carries both the fifth-site
paragraph and the "count to hold this relation to is four" sentence the deliverables name. The four
sites that read it are `intent_routine_return_binding`, `intent_field_participant_scope_table`,
`intent_field_scope_table_live` and `intent_mutation_routine_seat`. The reader rule candidate A
appeals to is at the head of `graphitron-model.sql`, "a derivation this store cannot afford to
re-evaluate gets read whole and paired on its key, never correlated per row", and A's exact shape,
a `LEFT JOIN` on a `SELECT DISTINCT` key projection read for its `IS NULL`, is already written in
`intent_node_id_encode`; the keys are `NOT NULL` on both sides and the projection is distinct over
the whole join key, so the plan's "no equivalence argument" holds and neither fan-out nor the
`NOT IN` null trap applies. C's structural observation holds arm by arm: the `LOOKUP_KEY` arm masks
to `Query` and is the only reader of `lookup_bearing`, the `MUTATION` arms mask to `Mutation`, and
the other four arm-pairs read `graphitron_service`, `graphitron_external_field`,
`graphitron_field_node_id`, `graphitron_routine` and `graphql_field_directive` only. The
registration exists, so framing the gate as refresh cost is right. `FieldColumnTableTest` holds
`aConnectionFieldResolvesItsElementsTable`, and its row helper asserts at most one row per
coordinate, which is the disjointness guard the repoint leans on without naming.
`AuthoredClaimTest` already covers all six claim kinds. `ColumnMatchShadowTest` and the LSP's
`FieldColumnTable` are the live consumers as claimed: `intent_field_column_table` and
`intent_field_reference_discovery` have no SQL reader at all, which is what makes the plan's "not
the emit path" true rather than assumed.

**1. Candidate B's grain sentence names the wrong site, and the plan makes that sentence the
contract.** B says the name is settled at implementation but "that grain sentence is what it must
state", so the sentence is what the promoted relation's comment will say. It says "one input object
type from which a `@lookupKey`-marked argument is reachable". The closure's seed is
`graphitron_field_lookup_key`, whose own comment reads "@lookupKey on an input field: the retired
site", and the recursive term walks `input_object_field_edge` from that seed up to the types that
contain it. A row therefore means: this input object type transitively contains an input field
carrying the retired `@lookupKey`. A marked *argument* is the arm's other trigger entirely, read
through the `direct` join over `graphitron_argument_lookup_key` and never through the closure.
`intent_authored_field_claim`'s comment states the two as a disjunction; B's sentence collapses them
into the one the closure does not answer.

What satisfies this: restate B's grain from the seed relation it actually walks, and name which of
the two lookup-key tables the promoted relation reads. Either reading of the sentence as written
costs something. A comment stating the argument site over rows about the input-field site is
relation prose the fact model treats as load-bearing, and an implementer who takes the sentence at
its word reseeds the closure from `graphitron_argument_lookup_key`, which changes what
`intent_authored_field_claim` answers rather than restating it. The row-set pin the plan already
asks for would catch that, but as a red build rather than as a decision the plan made.

The revision is also where a fact B's case has to reckon with belongs. `intent_authored_field_claim`
says the closure is "seeded from the retired input-field site, so on accepted schemas the recursion
never expands". A relation whose population is empty on every schema the build accepts is a weaker
candidate for a name than B presents, and that bears directly on two of B's three arguments: why it
outranks C, and what R848 is being handed as an earned registrable candidate. Whether it still
earns the promotion is the author's call; it should be made on the page rather than left for the
sweep to imply.

*Author response (2026-08-27).* Correct on both counts, and the second one changes B's case rather
than only its wording. B's grain now reads off `graphitron_field_lookup_key` and says so, and states
that the promoted relation reads that table while the marked-argument trigger stays in the `direct`
join. On the population: capture writes the row whenever the directive is present and classification
rejects it, so the rows exist exactly on schemas mid-migration, which a rejected build's store and
the language server's store of a half-edited schema both hold. That is enough for the relation to
mean something and not enough to register, so B keeps its rung on the family's restatement rule
alone and the R848-registrable-candidate argument is withdrawn from it. The sentence owed to R848
now says B adds a relation to the composition and not a registrable one. Also noted on the page:
because H2 inlines a view like a non-recursive `WITH`, B may move no plan at all, which was implicit
in "whether it flips this arm's plan is a measurement" and is now said outright.

**2. The plan does not name `DerivedReadCostTest`, which is the one automated gate over this
relation's cost and is pinned by equality.** The test tiers named in the deliverables are the
seeded intent anchor, the shadow differential, the LSP surface and the claim-view row-set pin. The
gate that will actually decide whether the verification build goes green is
`graphitron/src/test/java/no/sikt/graphitron/rewrite/derive/DerivedReadCostTest.java`, a pipeline-
tier test whose `KNOWN_NON_MONOTONIC` set is asserted with
`containsExactlyInAnyOrderElementsOf`. Its own javadoc states what that buys: "adding a pair fails
the build, and so does removing one, so the day a lever lands the assertion fails until the row goes
rather than the row surviving as a stale exemption nobody is forced to revisit."

That set already holds `intent_field_reference_step_hop|intent_field_column_scope_live`, and its
justification comment records this relation at 2854 scans registered against 2666 unregistered, 15
milliseconds against 29. Those figures are a property of the plan H2 picks for the body this item
rewrites, and the item's whole premise is that the plan for that body changes. So the pinned set can
move in either direction: the pair can go monotonic and have to be deleted, or the rewrite can push
a neighbouring cell over. Whichever happens, an implementer working from this plan meets it as a
failing assertion with no brief for what it means, and has to decide unbriefed whether a set change
is the expected consequence of the item or the regression the gate exists to catch. That is the
redesign-as-you-go this gate asks about.

What satisfies this: name the gate in the deliverables, say that a change to its pinned set is an
expected consequence rather than a licence to widen an allowlist, and require that a row which stays
has its justification prose rewritten to the new figures rather than left carrying the old ones. The
plan is already firm that the measurement figures live in the commit message and not in relation
comments; this gate is the one place in the tree where such figures are checked in on purpose, and
it needs the same instruction the two relation comments got.

*Author response (2026-08-27).* Accepted as stated. The gate is now a bullet of its own in the tests
section, carrying the equality property, the pinned pair and its recorded figures, and the three
dispositions a moved cell gets: deleted where it goes monotonic, its reason rewritten where it
survives, answered as an index-or-cost question before being pinned where it is new. The gate
section says why the sweep does not subsume it, and acceptance now demands that no surviving row's
reason contradicts the figures it records.

#### Non-blocking

- The repoint section lists "The `DISTINCT`, the root guard, the reference-step guard and the
  `@pivot` guard" as unchanged. The named-type arm carries no `DISTINCT`; it is on the
  `PATH_TERMINAL` arm above. Nothing follows from it, since A's distinct key projection cannot fan
  the arm out either way, but it reads as one of this arm's own guards.
- The acceptance criterion "All five sites named by `intent_field_navigated_type`'s comment" is
  checkable against today's comment and stops being so once the deliverable rewrites that comment to
  drop the count. Harmless, the five sites being settled, but a criterion that outlives its own
  source reads oddly.
- `graphitron_field_lookup_key`'s comment says "the sole consumer is the located migration
  rejection", and three relations read it today, one of them as its own precedence arm. Not this
  item's, except that B would make it four.

*Author response (2026-08-27).* First two taken: the repoint section names the arm's three guards
without the `DISTINCT`, and the acceptance criterion now lists the five sites itself instead of
citing a comment the same item rewrites. The third is left where it is. Correcting that comment
inside this item would mean editing a relation the plan does not otherwise touch, and B's own
deliverable already re-reads that table, so the honest place for it is B's implementation if B
ships and a Backlog item if it does not.

### Round 2 (2026-08-27, Spec -> Ready, reviewer session 01FjkK8JvUYCQt6qUk35j4Y3)

Verdict: sign off. Both round-1 findings are answered at the level they were raised, and the second
answer is the better one: naming `DerivedReadCostTest` did not just add a bullet, it gave the item
its hard gate, where the sweep only ever had a soft one.

Question 1 passes on its own reading. A graphitron author who writes a Relay connection type out by
hand in the SDL, rather than letting `@asConnection` synthesise one, and puts it on a non-root
object field gets nothing back today when they name a column at that field: the scope relation has
no row there, so the language server offers no column completion, definition or hover at that
coordinate and the structural column-match reading is silent, while the identical field over a
synthesised connection resolves fine. After this lands those names resolve against the element
type's table, the same answer every sibling site already gives. The second outcome is the title's
and is contributor-facing: the arm stops resting on the plan H2 happens to pick, so the next change
to its driving side does not rediscover the fifty-fold cliff. The plan is honest about how far the
first outcome reaches, which is the part a weaker spec would have overclaimed: `FieldColumnTable`
in `graphitron-lsp` is the only main-source Java reader of this relation, the emit path reads it
through nothing, and the tests section says so rather than reaching for a pipeline fixture that
would pin nothing.

Question 2 passes. A is the file header's own rule applied to the reader that violates it, with the
shape already written in `intent_node_id_encode`; B moves a term out of a view body into a named
relation, which is what the rest of this schema looks like; and C, the one candidate that would
stand a parallel mechanism, is ranked last with its costs stated rather than argued away. The
repoint is a fifth caller collapsing onto one relation. Nothing here is a new mechanism.

Everything checkable checked out on this tree, including what round 1 had to take on trust.
`lookup_bearing` is seeded from `graphitron_field_lookup_key` and its recursive term joins
`input_object_field_edge` on the named type projecting the declaring type, so B's revised grain
sentence is the closure's actual grain, and the marked-argument trigger really does sit in the
`direct` join over `graphitron_argument_lookup_key`, untouched by B. The population argument holds
end to end and not only at the comment: `GraphitronFactCapture` writes the marker on directive
presence with no classification consulted, and `BuildContext.classifyInputFieldInternal` rejects
`DIR_LOOKUP_KEY` outright, so the rows exist exactly on the schemas the plan says they do.
`DerivedReadCostTest` is as described: `KNOWN_NON_MONOTONIC` asserted with
`containsExactlyInAnyOrderElementsOf`, the pair `intent_field_reference_step_hop|intent_field_column_scope_live`
present, its justification recording 2854 scans against 2666 and 15 milliseconds against 29, and
line 52 of that file stating the scans-and-clocks separation the plan attributes to it. The arm is
the shape the plan describes, under three anti-joins with the claim one correlated; the registration
row for `intent_field_column_scope` exists, so framing the gate as refresh cost is right; the four
sites reading `intent_field_navigated_type` are `intent_routine_return_binding`,
`intent_field_participant_scope_table`, `intent_field_scope_table_live` and
`intent_mutation_routine_seat`; that relation's comment carries both sentences the deliverables
delete; `AuthoredClaimTest` covers all six claim kinds and exercises the closure through nesting and
a cycle, so the row-set pin has something to pin; and `FieldColumnTableTest.scopeRow` asserts at
most one row per coordinate, which is the disjointness the repoint leans on. C's structural claim
holds arm by arm.

#### Non-blocking

- B's shipping condition points two ways. The deliverable is "whichever of A, B, A-plus-B or C the
  sweep selects", which selects on cost, while B's own paragraph argues it is "worth having on its
  own" for a reason cost does not measure. If A alone clears the gate the implementer has to pick a
  reading. Either answer is small and defensible, so this is not worth a round; noting it so the
  choice is made rather than defaulted.
- No stop rule if all three candidates miss the gate. The fourth option the Backlog raised, registering
  the claim view, is deliberately out of scope here. The risk looks low, A removing the correlation
  the diagnosis blames outright, and an implementer who ran out of candidates would come back through
  the ordinary In Progress fork; but the plan does not say that is what should happen.
- Acceptance names `intent_field_scope_table` among the five sites where the reader is
  `intent_field_scope_table_live`. That is the store's own convention of spelling the canonical
  registered name, and the relation comment does the same, so it is right as written; flagged only
  because the four-site list a grep produces will not match it literally.

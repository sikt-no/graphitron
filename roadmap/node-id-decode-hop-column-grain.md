---
id: R878
title: "The node id decode hop column drops the foreign key it walked, so nine distinct hops become nine identical rows and arity states a false number"
status: In Review
bucket: bug
priority: 1
theme: codegen-correctness
depends-on: []
created: 2026-08-30
last-updated: 2026-08-30
---

# The node id decode hop column drops the foreign key it walked, so nine distinct hops become nine identical rows and arity states a false number

`intent_node_id_decode_hop_column` resolves a hop between two tables into the pair of columns that
join them. It projects the column names and drops the constraint and the tables the hop went between.
Where several foreign keys join different tables using the same column names, the rows those hops
produce become indistinguishable, and the relation ends up holding the same row many times over.
Everything above it then counts those repeats as if they were separate facts.

## The worked case

A filter input over person roles, `Query.personroller(filter)/fsRoller` on a real consumer schema.
`intent_node_id_decode_hop` holds nine rows for that coordinate, correctly: nine foreign keys, from
`EMNEROLLE`, `KLASSEROLLE`, `KULLROLLE`, `KURSROLLE`, `ORGANISASJONSENHETSROLLE`,
`STUDIEPROGRAMROLLE`, `TIMEPLANROLLE`, `UNDERVISNINGSAKTIVITETSROLLE` and
`UNDERVISNINGSENHETSROLLE`, each pointing at `ROLLE`. All nine join on the same two column names,
`INSTITUSJONSNR_EIER` and `ROLLEKODE`.

The hop column relation keeps the column names and drops which foreign key produced them, so those
nine rows arrive as nine identical output rows. On the same capture:

[cols="4,2,2"]
|===
| relation | rows | distinct rows

| `intent_node_id_decode_hop_column` | 1078 | 1009
| `intent_node_id_decode_column` | 1559 | 1114
|===

Per coordinate the multiplication is much larger than those totals suggest. At the coordinate above,
`intent_node_id_decode_column` holds 162 rows of which 2 are distinct. Five other coordinates are the
same shape: 64 against 4, 40 against 5, 28 against 7, 8 against 2, 6 against 3.

## What it currently breaks

**`intent_node_id_decode.arity` states a false number.** It is `COUNT(*)` over
`intent_node_id_decode_column` partitioned by graph and use site, so the node identity at that
coordinate is described as having 162 key columns where it has 2.

**Nothing emitted carries that number today.** `intent_node_id_decode` is named in javadoc and in
tests; no main source reads its `arity`. The generator consumes `destination`.

**`destination` survives, but by accident.** It is `lifted = positions`, and at all seven affected
coordinates every row carries a non-null lifted column, so the duplication inflates both sides
equally and the comparison lands where it would have. Nothing in the rule makes that proportional.
One duplicated row with a null lifted column at any of those coordinates flips `destination` from
own-table columns to target-table columns, and that value is consumed. This is the reason the item is
filed as a bug rather than as cleanup.

## Why `DISTINCT` is the wrong fix

It looks like a missing `DISTINCT` and it is not. The nine rows entering the projection are nine
different facts, one per foreign key. Collapsing them at the top would delete eight real hops and
hand any reader that needs to know which table it hopped from one arbitrary answer. The duplication
is information already lost by the time it is visible, so a fix that removes the visible copies
removes the evidence rather than the defect.

## What the missing discriminator actually is

The item was filed naming the constraint as the dropped fact. Reading the family from the endpoint
down, the constraint is a symptom and the dropped fact is one rung more fundamental: what the
children drop is **the endpoint the decode departs from**, which at a polymorphic slot is the branch.

`intent_node_id_decode_endpoint` resolves the departure through `intent_argument_scope_table`, whose
answer is one table per branch where the consuming field returns a multi-table polymorphic container.
So the endpoint relation holds nine rows at the worked coordinate, one per branch, differing in
`from_source_name` / `from_schema` / `from_table` and agreeing in everything else. The arrival is the
node type's binding, demanded at `candidates = 1`, so it does not vary. The branch is therefore
exactly the departure triple, and the endpoint relation's real key is
`(graph_name, use_site, from_source_name, from_schema, from_table)`.

Every child below it keys on `(graph_name, use_site)` and, where it is ordinal-keyed, `position`.
That is the endpoint's key with the branch removed. `intent_node_id_decode_hop` survives only because
it happens to project the *step's* own endpoints, which coincide with the decode's departure at
position 0; at position 1 and beyond even that coincidence is gone. `intent_node_id_decode_hop_column`
projects neither, and that is where the rows become indistinguishable.

The constraint discriminates the nine rows here because each branch declares exactly one foreign key
reaching `ROLLE`, which the `DISCOVERED_KEY` arm demands. It is a discriminator by arithmetic rather
than by meaning, and carrying it instead of the branch would leave the multi-hop authored path,
where one branch contributes several constraints, still unkeyed.

## The decision this needs: answered

The item asked whether a multi-table node id decode is one decode shared by every branch or one per
branch, and left it for whoever owns node id decoding. The tree already answers, in four places, and
all four say one per branch.

**The relation the departure is resolved from declares the branch as part of its grain.**
`intent_argument_scope_table` now carries a primary key on the argument coordinate *and the table*,
and its comment says why in terms this item could have been written from: "a field whose named type
is a multi-table polymorphic container is rooted in one table per branch, so each of its arguments
binds against each of those tables and each pair is a predicate the generator emits. One row per
argument is therefore the ordinary case and not the rule, and a reader that assumed it would silently
take one branch of a coordinate that has several." That is the defect this item reports, stated one
rung up and about the same branches, and "each pair is a predicate the generator emits" is the answer
to the question the item deferred to whoever owns node id decoding. The decode family is precisely a
reader that assumed one row per coordinate.

**The endpoint relation states it as its own grain.** Its comment: "such a slot has one endpoint pair
per branch and the decode is stated once per branch, which is what the resolver does with it." The
grain was decided when that relation was authored; the children simply failed to carry it.

**A shipped consumer is already keyed on the branch.** `intent_input_field_carrier_role_live`'s
`decoded` term takes `e.from_source_name, e.from_schema, e.from_table` and makes them the relation's
own `resolving_*` grain, so its rows are per branch and its declared key is per branch. It then joins
its `landing` aggregate over `intent_node_id_decode_column` on `(graph_name, use_site)` alone. A
per-branch relation is reading a not-per-branch aggregate, so every branch at a polymorphic slot
receives one verdict computed over all branches' rows mixed together. That is a live consumer whose
answer the missing key already makes wrong wherever branches disagree, and it is materialized and on
the build path.

**Sharing produces answers no branch performs.** The recursive `lifted` term in
`intent_node_id_decode_column_live` chains a hop to its successor on
`(graph_name, use_site, position + 1)` plus a folded column-name match, with nothing restricting the
step to the branch the previous step was on. On the `INPUT_FIELD` authored-path arm, where
`intent_input_field_reference_step_target` is joined on `resolving_* = e.from_*` and branches
therefore contribute genuinely different hops, a two-hop path can chain branch A's position 0 to
branch B's position 1 and lift a column through a route no branch declares. That is a wrong row and
not a copy of a right one, so no collapse above can recover from it, which retires the "the grain is
the column mapping" arm on correctness rather than on taste. (Hazard by construction; the fixture
that pins it is named under Tests below, and authoring it is where the implementation starts.)

The `ARGUMENT` authored-path arm does not reach that hazard today, its `tg` join carrying no
departure predicate, so all branches get identical hops. That is a second, separate silence: an
argument-site authored path at a polymorphic scope resolves the same hop set for every branch
regardless of which table the branch departs. It is out of scope here and filed separately; see Not
in this item.

So: **the grain includes the branch.** `intent_node_id_decode_hop_column` and
`intent_node_id_decode_column` carry it, the two aggregates over them partition by it, and
`intent_node_id_decode` becomes one row per instruction, use site and branch.

## Implementation

Shipped. All of it in `graphitron-model/src/main/resources/no/sikt/graphitron/model/graphitron-model.sql`
except the roster row, which is in `MaterializeRegistryGateTest`.

**The discriminator.** Three columns, `origin_source_name`, `origin_schema`, `origin_table`, carried
from `intent_node_id_decode_endpoint`'s `from_*` triple, through `intent_node_id_decode_hop`,
`intent_node_id_decode_hop_column`, `intent_node_id_decode_column` and `intent_node_id_decode`. Named
apart from `from_*` because on the hop both exist and mean different things: `from_*` is the step's
own departure, `origin_*` is the decode's, and they agree exactly at position 0.

Carried into the predicates as well as the projections, which is where the correctness is: the hop's
`last_position` window partitions by the branch, the recursive `lifted` term chains only within one
branch, the `LEFT JOIN lifted` matches the endpoint's own departure, and both windows in
`intent_node_id_decode` partition by it. On the slot arm the three are NULL, a slot departing no
table.

**Two downstream readers.** `intent_input_field_carrier_role_live` groups its `landing` aggregate by
the branch and joins on it, so its `local` flag is now a statement about the branch whose row it is.
`intent_mutation_payload_column_live`'s `NODE_ID` arm joins the decode on the write table as well as
the path.

That second one landed as an invariant guard rather than as the fix the spec described, and the
relation's own comment is why. It already argued that the join needed no departure guard, because a
write payload cannot have a branch: the participant arm's precondition is that the field's named type
binds no table and carries no resolving `@mutation(table:)` spelling, and each write rung contradicts
one. That argument holds and the ninefold multiplication the spec predicted here does not occur. The
guard is taken anyway because the two failure modes are not symmetric: unguarded, a second branch
would be one payload column per branch and silent; guarded, a decode departing somewhere the payload
does not write is no payload column at all, which is loud. The comment now says that instead of
saying no guard is needed.

**Indexes: one re-argued, one deleted.** `ix_node_id_decode_hop_column_step` gained the three branch
columns and keeps its place. Measured on the read-cost gate's fixture in rows visited by
`intent_node_id_decode_column_live`: 3306 with against 3450 without at twelve units, 39054 against
41358 at forty-eight. The gain grows with the schema. The three new columns tie exactly with the
narrow shape at both sizes, no coordinate in that fixture departing more than one table, so they are
claimed on being conjuncts of the join rather than on a figure, and the comment says so.

`ix_node_id_decode_column_use_site` was deleted. Both jobs it was bought for dissolved in this
change: the payload probe's predicate gained the three branch columns and the carrier's grouping went
from two columns to five, so `(graph_name, site, use_site)` is a prefix of both rather than a cover of
either. With the index against without, in rows visited: the carrier role 472 against 424 at twelve
units and 1804 against 1612 at forty-eight, the payload column relation 1284 against 1283 and 4848
against 4847, the payload refusal and the key membership identical at both sizes. No reader improves
at either size and the one that loses loses proportionally more as the schema grows. The obvious
repair, the same key with the branch appended so it covers both readers again, was measured beside
the other two and ties the narrow shape on every reader at both sizes, so it is not a fix being
deferred. The registration row in `meta_materialize` and the `NO_INDEX` roster in
`MaterializeRegistryGateTest` both carry the figures and the reason.

**The registration measurements stand.** The spec expected the millisecond figures on the
`meta_materialize` rows to move, the target populations shrinking. They do not, and the reason is
checkable: those figures were taken against the sakila example schema, whose only unions are error
unions with no table-bound members and whose one interface, `Signal`, carries `@table` on the
container, which is exactly the participant arm's exclusion. That schema therefore has no branch to
collapse and its populations are unchanged, so re-taking the figures would reproduce them. Only the
index paragraph on the `intent_node_id_decode_column` row needed rewriting, and it was re-measured.

## Tests

Shipped as `NodeIdDecodeBranchTest` in `graphitron-model`, six cases on two fixtures.

**The polymorphic branch fixture.** A union of three role tables, each declaring its own foreign key
to the node type's table, all three pairing the same two column names: the worked case reduced to the
smallest catalog that carries it. Before the fix it reproduced the defect exactly in miniature,
`intent_node_id_decode_column` holding eighteen rows of which two were distinct and
`intent_node_id_decode_hop_column` six of which two were. Three cases stand on it: each branch
contributes its own hop-column pairing, each lands its own key positions once, and neither relation
holds a duplicate row, and each branch is its own decode row carrying the node key's real arity of
two, which is the column the item is named for and which read eighteen before.

**The cross-branch chaining fixture.** The `INPUT_FIELD` authored-path shape, two branches whose one
authored path resolves against whichever table the branch departs, arriving at intermediate tables
that share the column name their second hop departs. This was the spec's one claim taken from the
shape of the SQL rather than from a capture, and it is confirmed: with the branch predicate removed
from the recursive term and the lift join, `klasserolle` lifts `emne_code`, a column of a table it
never departed. A route no branch declares, so no collapse above could have recovered from it.

The carrier role's per-branch answer is asserted on this fixture too, that being the one reading
downstream of the family a consumer can observe: both branches lift to a column of their own table
and each is told `CROSS_TABLE_FK` on its own row, where the shared landing aggregate used to hand
every branch one verdict computed over all of them.

**Not a demonstration.** A green build was compatible with the branches still being collapsed, every
other fixture in the tree being single-branch. Both fixtures were confirmed to fail on the
unfixed rule before being asserted against the fixed one.

**What moved elsewhere: nothing.** The spec expected movement in `NodeIdDecodeColumnTest`,
`NodeIdDecodeDestinationTest`, `NodeIdDecodeReachTest`, `FactCaptureAgreementTest`,
`DerivedReadCostTest` and `RefreshPlanStatisticsTest`. None moved, for the same reason the sakila
figures did not: every fixture those tests run is single-branch, so the widened key partitions rows
that were already one per partition. `MaterializeRegistryGateTest` moved as predicted, and by
failing rather than by drifting: its index roster caught the deleted index and demanded a row.

## Risks

**The carrier role's verdict changes for real inputs.** It is the one observable behaviour change and
it is a per-branch answer replacing a mixed one. Where branches agree, which is every fixture in the
tree today, nothing moves. Where they disagree the new answer is the correct one and the old one was
a coin flip over row counts. Named here so the Done gate looks for it rather than discovering it.

**The index re-argument did not pay, and the index went.** Recorded above with its figures. What is
left open is the one thing the measurement cannot settle: every figure here comes from the read-cost
gate's scaled fixture at two sizes, and the deleted index's original argument rested partly on a
sakila capture and partly on an expectation about a consumer schema, where both sides of the payload
probe grow. The mechanism that removed its value is structural rather than population-dependent, a
three-column key being a prefix of a six-column predicate whatever the row count, which is why the
deletion is claimed on the mechanism with the figures as corroboration rather than the other way
round. A consumer schema that made the probe expensive again would want the six-column shape, which
is measured, named and ties today.

**Cost was lowest now, and this is what it cost.** No main source reads `intent_node_id_decode` or
either child; the family's only main-source reader is `NodeIdDecodeDefects`, over
`intent_node_id_decode_defect`, which sits on `intent_node_id_decode_slot` and is untouched. So the
widened key reached no emitted output and no generated resolver, and the whole change is inside the
fact model.

## Reproducing

Any store with a captured consumer schema that has a polymorphic filter over several tables sharing
a foreign key column naming. Compare `count(*)` against `count(DISTINCT (all columns))` on the two
relations named above, then group by `use_site` to see the per-coordinate multiplication.

## Not in this item

The other eighteen materialization targets. A separate item measured all twenty and found these two
are the only ones holding duplicate rows; the rest have a key available and need no remodelling.

The argument-site authored path's missing departure predicate, described under the decision above:
`intent_node_id_decode_hop`'s `tg` join resolves the same hops for every branch of a polymorphic
argument scope, where the input-field arm beside it restricts on the resolving table. Fixing it means
deciding what an argument-site `@reference` means when the scope resolves several tables, which is a
question about the reference walk rather than about this family's grain, and it is reachable only
through a shape no fixture in the tree has. File it as its own Backlog item at pickup rather than
folding it in here.

## Reviewer findings

### Round 1 (2026-08-30, In Review -> Done, reviewer session 014r2o88dHiYCSdbDmtftxY4)

Verdict: withhold, on one narrow finding against question three. Everything else this gate asks is
answered, and answered well: the rule change is correct, it is the change the spec approved, the two
deviations from the spec are both improvements and both argued in the open, and question four has a
better answer than the spec asked for.

What I verified. The full `mvn install -Plocal-db` is green on the pushed tree, independently of the
implementer's run. Every predicate the correctness rests on is in place and reads correctly: the
hop's `last_position` window partitions by the branch, the recursive `lifted` term carries the
branch forward and joins only within it, the `LEFT JOIN lifted` matches the endpoint's own
departure, and both windows in `intent_node_id_decode` partition by it. The three SQL readers of
`intent_node_id_decode_column` and the two of `intent_node_id_decode_hop_column` are the only ones
in the file and all five were updated; no Java main source reads either, so the widened key reaches
no emitted output, as the item claims. The deleted index's name survives nowhere but in this item's
own prose.

Question four I checked by breaking it rather than by reading it. With the branch predicate removed
from the recursive term and the lift join, `NodeIdDecodeBranchTest` fails four of its six cases:
`arity` reads 6 where the node key is two columns wide, the decode column relation holds twelve rows
beyond its own distinct content, and the chained fixture has `klasserolle` lifting `emne_code` and
`emnerolle` lifting `klasse_code`, which is the cross-branch route the spec predicted from the shape
of the SQL and could not then demonstrate. That hazard is now a fact rather than an argument, and it
is the strongest thing in this change: a wrong row no collapse above could have recovered from,
which is what retires `DISTINCT` on correctness rather than on taste.

The two deviations are both sound. The mutation payload join landing as an invariant guard rather
than as the multiplication fix the spec predicted is the right call and the honest one: the
relation's own comment already carried the argument that a write payload has no branch, the spec's
paragraph was written without it, and the implementer found that, said so, and took the guard anyway
on an asymmetry between a loud failure and a silent one. Deleting `ix_node_id_decode_column_use_site`
rather than widening it is exactly what the spec's own instruction asked for, measured at two sizes,
with the covering shape measured beside it so nothing is left implied.

**Finding 1 (question three: the implementation is the change the spec approved).
`intent_node_id_decode_hop_column` now states its own key three ways in four adjacent lines, and two
of the three omit the branch.**

The whole of this item is that the branch is part of this family's grain. On the relation the item
is named for, the new `origin_source_name` comment says so: "with the graph, the use site, the
position and the pair position it completes this relation's key". The two comments either side of it
still state the superseded key:

* `intent_node_id_decode_hop_column.use_site`: "with the graph, the position and the pair position,
  the key".
* `intent_node_id_decode_hop_column.position`: "with the pair position beside it, the use site and
  the graph, this is the key".

Both were correct before this change and are contradicted by it. The equivalent comments were
updated everywhere else in the family, which is what makes this an omission rather than a position:
`intent_node_id_decode_hop.use_site` now reads "with the graph, the branch above and the position
below, the whole of this relation's key", `intent_node_id_decode_column.use_site` reads "with the
graph, the branch and the position, the key", `intent_node_id_decode.use_site` reads "with the graph
and the branch below, this relation's key", and this relation's own table comment carries the branch
as a key column at length. One relation, and only this one, was missed.

Why this blocks rather than being noted in passing. In this model a relation's stated key is the
model: it is what a reader joins on and what the sibling architecture item means by a relation
having said what one of its rows is about. A reader who takes `use_site` at its word joins on
`(graph_name, use_site, position, pair_position)` and reproduces the defect this item exists to fix,
one rung up. These comments also render into the published schema reference, so the contradiction
ships. And the item file is deleted at Done, so a non-blocking note here has nowhere to live: the
choice is this round or a fresh Backlog item for two lines the change itself introduced.

What would satisfy it: rewrite those two comments to state the key including the branch, on the model
of the three siblings that already do. Nothing else in the implementation needs to move, and I would
not expect a rebuild beyond the model module's own tests to establish that.

**Author's response (2026-08-30).** Fixed, and the finding was right to block: a stated key is what a
reader joins on, so those two lines described the defect rather than the fix.
`intent_node_id_decode_hop_column.use_site` and `.position` now both state the key with the branch in
it, on the model of the three siblings.

Taking the finding as a class rather than as two lines turned up a third, on
`intent_node_id_decode_endpoint` itself, which the round did not name.
`intent_node_id_decode_endpoint.path` read "With the four columns above this is the key, and it is
the key the two children carry". Both halves were wrong, and the first half was wrong before this
item started: the endpoint holds one row per branch, so the coordinate alone was never its key, and
that unstated multiplicity is precisely the premise the whole defect rested on. It now says the
coordinate is the key only where one departing table answers, that a polymorphic slot has one row per
branch differing in the departure triple, and that the children carry the departure as their origin
columns. `from_source_name` and `from_table` beside it now name the branch as a key column too,
having previously described the departure without saying it discriminates rows.

That is the same class the reviewer found, on the relation the family hangs from, and it is the
comment the spec quoted as evidence that the tree already knew the grain: the relation's prose
carried the branch while its key statement denied it, which is how the children came to be written
without it.

Verified with `mvn install -Plocal-db` on the whole reactor rather than the model module alone: the
comments render into the published schema reference, which `graphitron-docs` builds.

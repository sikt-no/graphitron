---
id: R878
title: "The node id decode hop column drops the foreign key it walked, so nine distinct hops become nine identical rows and arity states a false number"
status: Spec
bucket: bug
priority: 1
theme: codegen-correctness
depends-on: []
created: 2026-08-30
last-updated: 2026-08-29
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
`intent_node_id_decode_column` holds 162 rows of which 2 are distinct. Six other coordinates are the
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
branch, and left it for whoever owns node id decoding. The tree already answers, in three places, and
all three say one per branch.

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

All of it is `graphitron-model/src/main/resources/no/sikt/graphitron/model/graphitron-model.sql`
unless stated.

**The discriminator's spelling.** Three columns, `origin_source_name`, `origin_schema`,
`origin_table`, carried from `intent_node_id_decode_endpoint`'s `from_*` triple. Named apart from
`from_*` because on `intent_node_id_decode_hop` both exist and mean different things: `from_*` is the
step's own departure, `origin_*` is the decode's, and they agree exactly at position 0. Not
serialized into one string the way `use_site` is: `use_site` earns that by collapsing five columns
two of which are nullable by site, and this triple is three non-null columns the neighbouring
relations already spell in parts.

**`intent_node_id_decode_hop`.** Add the three columns, taken from `e.from_*` in the inner `walked`
subquery and projected out. Add them to the `last_position` window's `PARTITION BY`, which today
takes the greatest position across all branches and would otherwise report one branch's terminal for
another's chain.

**`intent_node_id_decode_hop_column_live`** and the table it fills. Add the three columns, carried
from `h`. This is the projection the item is named for and the smallest of the changes.

**`intent_node_id_decode_column_live`** and the table it fills. Carry the triple through the `lifted`
CTE, add it to the recursive term's join so a step can only chain within its own branch, add it to
the `LEFT JOIN lifted` predicate matched against `e.from_*`, and project it. This is the change that
retires the cross-branch chaining above.

**`intent_node_id_decode`.** Partition both windows by the triple beside `(graph_name, use_site)`, so
`positions` and `lifted` are counted per branch and `arity` states the node key's real width. Project
the triple. The slot arm has no departing table, a slot being reached at the root of the use site, so
its three values are NULL, determined by destination in the sense the nullable-by-kind discipline
allows; the column comments say so. The existing `SELECT DISTINCT` stays and now collapses the key
positions of one branch rather than the branches of one coordinate.

**`intent_input_field_carrier_role_live`.** Its `landing` term groups over `(graph_name, use_site)`
and its `decoded` term is already per branch. Add the triple to the group and to the join. The
reading this fixes rather than changes: the relation's `local` flag becomes a statement about the
branch whose row it is, which is what a per-branch grain claims it already was. A slot whose branches
disagree, one lifting to local columns and another not, now gets `CROSS_TABLE_FK` on the one and
`REMOTE` on the other instead of one verdict for both. That is the intended answer and it needs
saying out loud in the relation's comment, because it is the only behaviour change in this item that
a downstream reader can observe.

**`intent_mutation_payload_column_live`.** Its `NODE_ID` arm joins `intent_node_id_decode_column` on
`(graph_name, site, use_site)`, so today it emits one payload column row per duplicate, which at the
worked coordinate is a ninefold multiplication of the payload columns. It has no branch of its own to
join on: an admitted occurrence is a coordinate, not a branch. Restrict the join to the branch the
occurrence's carrier role resolved, which the `admitted` term already carries as
`write_source_name` / `write_schema` / `write_table`. State in its comment that the payload column
population is per occurrence and that the branch is what selects which decode answers for it.

**Indexes.** `ix_node_id_decode_hop_column_step` is `(graph_name, use_site, position)` and serves the
recursive join, whose predicate gains three columns; extend it to
`(graph_name, use_site, origin_source_name, origin_schema, origin_table, position)` and re-argue the
comment against the read-cost gate's figures rather than carrying the old ones forward.
`ix_node_id_decode_column_use_site` is `(graph_name, site, use_site)` and serves the payload probe
and the carrier's grouping; both gain the branch, so measure the extended shape against the current
one on the gate's fixture and keep whichever wins, recording the figures either way. Neither index
comment may keep a measurement it no longer stands on.

**The materialize roster.** Three of the five relations touched are registered targets
(`intent_node_id_decode_hop_column`, `intent_node_id_decode_column`,
`intent_input_field_carrier_role`) and a fourth reads them at refresh
(`intent_mutation_payload_column`). The registration rows in `meta_materialize` carry measurements
that this change invalidates by shrinking the target populations; re-take them on the same fixture
and update the prose. The refresh order does not change, no new dependency edge being introduced.

## Tests

**The guard that makes this stay fixed.** A `graphitron-model` intent test asserting that
`intent_node_id_decode_hop_column` and `intent_node_id_decode_column` hold no duplicate rows on a
seeded store: `COUNT(*)` equals `COUNT(*)` over the distinct projection, per relation. This is the
check that would have failed on the day the defect landed, and it is cheap enough to keep. State it
as a property of these two relations rather than as a roster over all twenty; the sibling item that
measured the other eighteen found they need no such guard, and a roster would claim otherwise.

**The polymorphic branch fixture.** A seeded fixture with one slot whose scope resolves several
tables, each declaring its own foreign key to the node type's table, all joining on the same column
names. That is the worked case reduced to the smallest catalog that reproduces it. Assertions: the
hop column relation holds one row per branch per key position, `intent_node_id_decode` holds one row
per branch with `arity` equal to the node key's real width, and `intent_input_field_carrier_role`
holds one row per branch.

**The cross-branch chaining fixture.** The `INPUT_FIELD` authored-path shape named above: a
polymorphic slot with a two-hop `@reference` where the branches' first hops arrive at different
tables whose second-hop departing columns share a name. Assert that each branch's lift follows its
own route. Author this fixture first: it is the one claim in this spec taken from the shape of the
SQL rather than from a capture, and if it turns out unreachable the spec's correctness argument
needs re-stating on the carrier-role evidence alone, which stands on its own but is a weaker case
for touching `intent_node_id_decode_column`'s recursive term.

**Existing tests to expect movement in.** `NodeIdDecodeColumnTest`, `NodeIdDecodeDestinationTest`,
`NodeIdDecodeReachTest`, `MaterializeRegistryGateTest` (roster columns and measurements),
`FactCaptureAgreementTest`, `DerivedReadCostTest` and `RefreshPlanStatisticsTest` (figures).
`FamilyRosterGateTest` and `CommentRenderabilityGateTest` will hold the new columns to the comment
conventions.

**Not a demonstration.** A green build is compatible with the branches still being collapsed, every
current fixture being single-branch. The two fixtures above are what shows the item delivered.

## Risks

**The carrier role's verdict changes for real inputs.** It is the one observable behaviour change and
it is a per-branch answer replacing a mixed one. Where branches agree, which is every fixture in the
tree today, nothing moves. Where they disagree the new answer is the correct one and the old one was
a coin flip over row counts. Named here so the Done gate looks for it rather than discovering it.

**The index re-argument may not pay.** Both index comments are long, measured arguments, and a wider
key can lose on a fixture this size. The instruction above is to measure and record, not to widen on
principle; an index that no longer earns its place is deleted with its reason, not kept with a stale
comment.

**Cost is lowest now.** No main source reads `intent_node_id_decode` or either child; the family's
only main-source reader is `NodeIdDecodeDefects`, over `intent_node_id_decode_defect`, which sits on
`intent_node_id_decode_slot` and is untouched here. Every consumer added before this lands is one
more site that has to be re-read against a widened key.

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

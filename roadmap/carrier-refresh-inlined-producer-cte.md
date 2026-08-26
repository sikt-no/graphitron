---
id: R839
title: "The carrier refresh costs 41 seconds per capture, and it is the producer CTE inlined per driving row"
status: Spec
bucket: model
priority: 2
theme: model-cleanup
depends-on: []
created: 2026-08-26
last-updated: 2026-08-26
---

# The carrier refresh costs 41 seconds per capture, and it is the producer CTE inlined per driving row

`intent_carrier_data_field_live` takes 41 seconds to produce 151 rows against a real consumer store,
and the whole of it is one correlated `EXISTS` re-deriving a 172-row rule once per driving row.
Registering the relation that rule reads, `intent_field_payload_producer`, measures 0.3 seconds.

Unlike R781 and R830, which price relations nothing exercises at build time yet, this cost is being
paid now, on every capture, by every consumer whose schema reaches the carrier family. That is what
the priority reflects.

## What changes when this lands

A capture of a consumer schema stops spending 41 seconds on the carrier relation and spends about
0.3 seconds instead, so a consumer whose schema reaches the carrier family gets most of a minute
back on every build. Nothing about what the store answers changes: the same rows, in the same
columns, under the same names every reader already spells.

The change is one materialization registration. A registration is a row in `meta_materialize`
saying "refill this table from that view, once per capture, per graph": the view keeps the rule's
text under a `_live` name, the table takes the canonical name every reader spells, and so no reader
is edited and the rule is still written exactly once. Which relation to register is the question the
measurements below answer, and the answer is `intent_field_payload_producer`.

## What a capture pays today

Measured against a real captured store for a consumer schema of 8408 fields, 2345 types, 5619
catalog columns and 39 classpath sources, one graph, using the same `DELETE` plus
`INSERT INTO target SELECT * FROM source_view WHERE graph_name = ?` statements `Materializations`
itself runs:

| Registration target | One refresh |
|---|---|
| `intent_carrier_data_field` | 41.1 s |
| `intent_field_column_scope` | 6.4 s |
| `intent_errors_field` | 4.2 s |
| `intent_node_id_instruction` | 3.4 s |
| `intent_node_id_decode_hop_column` | 2.2 s |
| `intent_argument_scope_table` | 1.1 s |
| the remaining six | about 1.4 s combined |
| **total, one capture** | **59.9 s** |

`Materializations.analyse` is 23 ms of that and is not worth attention. The store measured carried
twelve registrations; the register has since grown, so the total is a floor rather than a current
figure.

## The cost is the expansion, and the term is identified

The children are all cheap, so there is nothing expensive underneath to reach for:
`intent_field_payload_producer` is 4 to 31 ms, `intent_bound_table` 2 to 21 ms,
`intent_type_backing` 3 to 15 ms, and `intent_errors_field` is a table and answers in under a
millisecond.

Bisecting the body puts the cost in one place. The whole view is 45.7 s, the bare
`producer` join `data_channel` with all three disqualification arms removed is 44.6 s, and the
`data_channel` CTE evaluated on its own is 44.4 s. So the three `NOT EXISTS` arms together cost
about 1.1 s and the CTE is the subject.

Inside that CTE the term is the population filter:

```sql
WHERE EXISTS (SELECT 1 FROM producer p
               WHERE p.graph_name = f.graph_name
                 AND p.payload_type_name = f.type_name)
```

`producer` is a non-recursive `WITH` naming `intent_field_payload_producer`, and H2 inlines such a
CTE exactly like a view with no common-subexpression elimination, so a correlated reference
re-derives the whole rule once per driving row of `graphql_field` joined to `graphql_type`. The
arithmetic closes: about 5 ms per evaluation against roughly eight thousand candidate rows is about
forty seconds, against a measured 41.

## Controls, including the one that refuted the obvious reading

Same store, same run, two sweeps each, `OPTIMIZE_REUSE_RESULTS` off:

| `data_channel` variant | Sweep 0 | Sweep 1 |
|---|---|---|
| as written | 44.9 s | 39.7 s |
| the `producer` CTE snapshotted into a table | 224 ms | 194 ms |
| all three probed relations snapshotted | 233 ms | 197 ms |
| only `intent_bound_table` and `intent_type_backing` snapshotted | 40.6 s | 40.0 s |

The last row is the one worth keeping. The CTE also probes those two views inside a `CASE WHEN
EXISTS`, which is where a reader following the plan's shape would look first, and substituting tables
for both changes nothing. Reading the body without pricing it would have named the wrong term.

## The lever, and which registration to land

Both candidate depths were measured on the same store:

* registering `intent_field_payload_producer`, which is already a named relation, leaving the CTE
  spelled as it is: 327 ms and 261 ms;
* promoting the `producer` CTE itself to a first-class relation and registering that: 253 ms and
  206 ms.

The deeper option buys about 60 ms more and costs a new relation with a name and comments, so the
shallower one is the registration to land and the difference belongs in the `reason` as measured
follow-up rather than in this change.

The trade the middle rung has to win is a refresh against the re-evaluations it avoids, and here it
is not close. One evaluation of `intent_field_payload_producer` is 4 to 31 ms. It has two readers in
SQL: this correlated probe, and `intent_field_error_channel`, which drives from it in a plain `FROM`
and therefore already pays exactly one evaluation and is indifferent to the registration.

## Implementation

All of it lands in
`graphitron-model/src/main/resources/no/sikt/graphitron/model/graphitron-model.sql`, except one line
of a test list and the re-pinned figures under "Tests and gates" below. It is the established cheap
registration shape, so nothing here is a new mechanism.

**Rename the rule's view.** `CREATE VIEW intent_field_payload_producer` becomes
`CREATE VIEW intent_field_payload_producer_live`, column list and body unchanged, declared exactly
where it stands today. Its inputs are `graphitron_service`, `graphitron_mutation`,
`graphitron_routine`, `graphql_field` and `graphql_root_operation`, all captured base tables, so no
declaration order in the file moves.

**Declare the target under the canonical name**, immediately after the view, which is the pair order
the `intent_errors_field_live` / `intent_errors_field` pair a few relations above already uses:

```sql
CREATE TABLE intent_field_payload_producer (
  graph_name        VARCHAR NOT NULL,
  type_name         VARCHAR,
  field_name        VARCHAR,
  family            VARCHAR,
  payload_type_name VARCHAR,
  root_operation    VARCHAR,
  FOREIGN KEY (graph_name) REFERENCES store_graph (graph_name)
);
```

Same column names in the same order as the view, which is what
`MaterializeRegistryGateTest.targetsAreShapedLikeTheViewsThatFillThem` closes: the refresh is
`INSERT INTO target SELECT * FROM source`, so a shape mismatch writes the wrong columns rather than
failing. No primary key, because `root_operation` is meaningfully nullable and H2 refuses a key over
a nullable column; that is why the index question below is a question at all rather than answered by
the key.

**Move the comments rather than write new ones.** The relation's existing view comment and its six
column comments belong on the table, verbatim, with the standard materialization sentence appended
to the relation comment:

> Materialized: this relation is a table refilled from `intent_field_payload_producer_live` on the
> capture cadence, per graph, under the registration in `meta_materialize`, which carries why. The
> rule above is stated once, in that view; these rows are what it computed for each captured graph.

The view then takes the stub form every other `_live` view carries, one relation comment naming
where the documentation went and one per column:

> This states the rule and is evaluated on demand. The canonical name
> `intent_field_payload_producer` beside it is the table this view is materialized into on the
> capture cadence, which is what every reader spells and what the registration in
> `meta_materialize` records; a reader naming this relation instead is asking for on-demand
> evaluation and will get it. The rule itself, and what each column means, is documented on
> `intent_field_payload_producer`.

**Why the canonical name has to move to the table** rather than the target taking a new one: readers
and prose both. The two view bodies that read this relation are not edited, they simply stop hitting
a view and start hitting a table, which is what makes a registration invisible. And
`SchemaIdentifierDriftCheck` sweeps the DDL's own comments for relation names and fails the build on
one the store no longer declares, so the three citations in `intent_field_error_channel`'s comments
(its view comment and its `transport` and `family` column comments) stay true only because the name
they spell is still declared. This change edits no prose outside the two relations it touches.

**Register it.** One row in `INSERT INTO meta_materialize`, keyed
`('intent_field_payload_producer_live', 'intent_field_payload_producer', <reason>)`. The `reason`
column is required and is where the argument lives, so this one has to state:

* The readers. Two in SQL. `intent_carrier_data_field_live`'s `data_channel` CTE names it in a
  correlated `EXISTS` keyed on the graph and the payload type, which is the reader the registration
  is bought for; `intent_field_error_channel` drives from it in a plain `FROM`, therefore already
  pays exactly one evaluation, and is indifferent to the registration.
* The trade. One evaluation of the rule is 4 to 31 ms, which is what a refresh costs, against the
  roughly eight thousand re-evaluations one carrier refresh was paying.
* The measured move, with the schema named: the carrier refresh from 41 s to about 0.3 s on the
  consumer schema this item describes (8408 fields, 2345 types, 5619 catalog columns, 39 classpath
  sources), because a recorded measurement is evidence about the schema it was taken on and a reason
  that omits the schema invites the mistake the carrier's own row already made.
* The control that refuted the obvious reading: substituting tables for `intent_bound_table` and
  `intent_type_backing`, the two relations the same CTE probes inside a `CASE WHEN EXISTS`, leaves
  the cost unchanged at about 40 s. Worth a sentence because that is where a reader following the
  plan's shape looks first.
* The deeper lever, declined on measurement: promoting the `producer` CTE itself to a first-class
  relation and registering that measured 253 ms and 206 ms against this registration's 327 ms and
  261 ms, so about 60 ms for a new relation with a name and comments of its own. Recorded as
  measured follow-up, not taken.

State facts and figures in that reason and do not cite a roadmap item id in it. The DDL's prose is
read by consumers through the generated schema reference, where an item id is stale the day the item
ships.

**Nothing orders the refresh by hand.** `meta_materialize_dependency` is machine-written at boot by
`MaterializeDependencies.populate`, which parses the stored view definitions, so the edge from
`intent_carrier_data_field_live` to this registration appears on its own and the materializer
refreshes the producer first. There is no authored row to add, and `MaterializeRegistryGateTest`'s
acyclicity and order-respecting tests are what confirm the edge arrived.

## The carrier's own reason row is wrong, and this change should say so

`intent_carrier_data_field_live`'s registration prices its refresh at about 170 ms for 15 rows on the
sakila example and about 12 ms on a carrier-free schema. The relation this item is about is the
reason that figure does not transfer: on a consumer schema the same refresh is 41 s. The fact model
page is explicit that a recorded measurement is evidence about the schema it was measured on and that
a stored reason contradicted by a later measurement needs correcting where it lives, so the row
should be corrected in the same change that moves the number, with both figures and the schema each
was taken on.

Two sibling rows are wrong the same way and are deliberately out of scope here, because re-pricing
the register's recorded claims is R831's subject rather than this item's: `intent_errors_field_live`
records about ten milliseconds against a measured 4.2 s, and `intent_field_column_scope_live` records
about 170 ms "on a real schema" against a measured 6.4 s.

## The index question is this registration's own, and it is answered by measurement

Every registered target either carries a declared index whose `COMMENT ON INDEX` names the reader it
serves, or has a row in `MaterializeRegistryGateTest.NO_INDEX` arguing why not, asserted by equality
in both directions. So this registration cannot land without answering the question, and the answer
has to be a figure.

Do not assume the roster row. This is the first registration here whose motivating reader genuinely
probes in from a population larger than the target: the carrier CTE's `EXISTS` seeks
`(graph_name, payload_type_name)` once per driving row of `graphql_field` joined to `graphql_type`,
which is thousands of driving rows against a producer table of a few hundred. That is exactly the
shape `intent_argument_column_match`'s roster row names as the one that would change its own answer.

So declare and time `ix_field_payload_producer_payload ON intent_field_payload_producer
(graph_name, payload_type_name)`, on both the consumer store and the twelve-unit fixture
`DerivedReadCostTest` runs, against no index at all. If it moves a reader, it ships with a comment
naming that reader, on `ix_spelled_table_spelling`'s model. If it moves nothing, the relation joins
`NO_INDEX` with the figures that say so. The 327 ms and 261 ms refresh figures in this item were
taken with no index declared, so they are the floor a roster row would stand on, and an index would
have to earn its cost on every refresh on top of them.

## Tests and gates

No new behavioural test, and that is the mechanism working rather than a gap. A registration changes
no answer, and the claim that the target holds its view's rows is what the capture agreement
machinery already asserts once the `_live` view is registered with it.

* `FactCaptureAgreementTest`: one line,
  `registrations.put("intent_field_payload_producer_live", Arm.DERIVED);`, beside the existing row
  for the canonical name. A registered materialization is two relations under one rule and both are
  derived, which is what that list encodes. This test fails a full build and not a scoped one, so
  this item needs a verification build rather than a `-pl` run.
* `MaterializeRegistryGateTest`: no edit unless the index question lands on the roster, in which case
  one `NO_INDEX` entry plus its argument in the set's javadoc. Its five structural tests (kinds,
  column shape, acyclicity, order, and nothing materializing outside the mechanism) are what check
  the pair, and they need nothing from the author.
* `DerivedReadCostTest`: three pinned counts move and one pinned set may.
  `READERS_IN_SCHEMA` rises by one, the new `_live` view being a view in the schema; it was 99 when
  this item was drafted and is 101 on trunk today, so re-pin it from the tree rather than from a
  figure this file states.
  `READERS_WITH_CELLS` and `CELLS` move in both directions at once, which the constant's own javadoc
  explains: the reachability walk stops at a registered target, so every reader that reached a
  registration only through this relation loses those cells, while this registration and its `_live`
  view add cells of their own. Re-pin all three from the failure message rather than predicting them.
  `KNOWN_NON_MONOTONIC` may gain a row, a reader that got dearer for joining a keyless table; a new
  row there is a finding to argue in that set's javadoc after measuring the index shape, never a
  tolerance to add quietly.
* The inline-multiplicity report (`roadmap-tool report-inline-multiplicity`) reports rather than
  gates, and a registered relation leaves its ranking by construction, so nothing there needs
  re-pinning.

## Verification

`mvn install -Plocal-db` from the repo root, on the exact tree that gets pushed. The agreement test
and the read-cost gate both need the full pipeline, so a scoped `-pl` run is not verification for
this item.

Separately from the build, re-take the two figures the `reason` will state, by the procedure in the
`store-performance` skill: a store a real build already wrote rather than a fixture, single-file JDBC
programs over the pinned H2 version, `OPTIMIZE_REUSE_RESULTS` off, the real refresh statements. Do
not transcribe this item's numbers into the DDL. They were measured on a tree whose register has
grown since, and the register growing is exactly what moves them.

## What would falsify this plan

* The refresh costing materially more than the 4 to 31 ms measured. The whole trade is one refresh
  against the re-evaluations it avoids, and a dearer refresh is a different trade. Re-price before
  landing rather than after.
* The carrier refresh not landing near 0.3 s on the consumer store after the registration. That
  would mean the identified term was not the whole cost. Stop and re-bisect the body; do not stack a
  second registration on top of a wrong diagnosis.
* `KNOWN_NON_MONOTONIC` gaining more than a row or two. At that point the answer is the index on the
  target, not a set of roster rows, and the index has to be measured rather than reasoned about.

## Not in scope

Why a registration can land with an unpriced refresh at all. `DerivedReadCostTest` holds the read
side only, in scan counts, over a twelve-unit synthetic fixture, and states outright that it asserts
no duration anywhere; `MaterializeRegistryGateTest` closes the register against the schema and asks
nothing about cost. So nothing prices the side every consumer generate pays. That is an architectural
question about what a registration must prove before it lands, it wants its own Spec cycle, and it is
filed separately.

## How to re-take these figures

Every number above comes from the procedure in the `store-performance` skill, against a store a real
build had already written rather than a fixture, driven from single-file JDBC programs over the H2
version the root pom pins. Nothing here was read off a reactor wall clock, a thread dump or a
profiler frame. The relation timings are `INFORMATION_SCHEMA.QUERY_STATISTICS` rows or direct
statement timings under `OPTIMIZE_REUSE_RESULTS FALSE`; the totals are the real refresh statements.
The one figure taken at a single execution is the per-registration table in the first section, which
ranks and is provisional in its tail; the 41 s subject was measured six times across four programs
and ranged 39.7 s to 49.1 s, the high end taken while a second probe shared the machine.

## Reviewer findings

### Round 1 (2026-08-26, Spec -> Ready, reviewer session 310ba981-0e52-442f-afd2-1a3cee64c049)

Verdict: withhold. Two findings, both about the evidence the plan rests on rather than about the
design, which holds up. The diagnosis is the kind this repo asks for: a real captured store, a
bisect that names the term rather than the section, and a control that refuted the reading a reader
following the plan's shape would reach first. Everything checkable against the tree checked out. The
view's declared column list is `(graph_name, type_name, field_name, family, payload_type_name,
root_operation)`, exactly the proposed table's columns in exactly that order; its inputs are the five
captured base tables the plan names, so nothing in the file's declaration order moves; `store_graph`
is declared at the top of the file and both reading bodies sit well below the insertion point; the
pair order and the comment split match the `intent_errors_field_live` pair the plan cites;
`intent_field_error_channel`'s view comment and its `transport` and `family` column comments do spell
the canonical name three times, so the name moving to the table is what keeps
`SchemaIdentifierDriftCheck` satisfied; grep finds no Java reader of the relation, so "two readers in
SQL" is complete rather than approximate; and `meta_relation_family` is a census view over the
catalog, so the new pair needs no authored roster row.

**1. The gates named as confirming the refresh edge cannot confirm it.** "Nothing orders the refresh
by hand" states that `MaterializeRegistryGateTest`'s acyclicity and order-respecting tests "are what
confirm the edge arrived", and the "no new behavioural test, and that is the mechanism working
rather than a gap" conclusion under Tests and gates rests on that claim. Neither test can carry it.
`theDerivedDependenciesAdmitARefreshOrder` asserts only that every registration appears exactly once
in the returned order, which is true whatever edges exist. `theRefreshOrderRespectsEveryDependencyRow`
iterates the rows of `meta_materialize_dependency` and collects offenders, so an edge that was never
derived contributes no row and therefore no offender: the test passes vacuously on the store where
the edge is missing, which is the one store it needs to fail on.

This is a finding about cited evidence and not a predicted defect. The mechanism looks sound:
`MaterializeDependencies` walks the parsed query object model rather than the view text and filters
on H2's schema-qualified spelling, its own javadoc calling out that CTE names stay unqualified while
real relation references do not, so the qualified reference inside `intent_carrier_data_field_live`'s
`producer` CTE is exactly the shape the walk collects. What is missing is a statement of what fails
if it does not. That answer exists and is loud: a carrier refresh ordered before the producer refresh
reads an empty producer table, so `intent_carrier_data_field` lands with zero rows, and
`CarrierDataFieldTest` asserts each case's whole row set over a captured store rather than a
projection of it, so every non-empty case fails. Name that, or an assertion that the edge row is
present, in place of the two structural tests. The distinction matters here more than it usually
would, because this is the item that decides no new test is needed.

**2. The index question is elevated to "answered by measurement" and then answered against one
shape.** The section is right that this is the first registration whose motivating reader probes in
from a population larger than the target, and right that
`intent_argument_column_match`'s roster row names that as the shape which would change its answer. But
the probe carries a constraint the proposed index shape omits. The `producer` CTE is `SELECT DISTINCT
graph_name, payload_type_name, family FROM intent_field_payload_producer WHERE root_operation =
'MUTATION'`, so every one of the thousands of probes is an equality on `(graph_name,
payload_type_name)` underneath a constant `root_operation = 'MUTATION'`, against a table whose rows
are mostly not mutation-rooted: `root_operation` is null for every producing field on a
non-root type and `'QUERY'` for most of the rest. Timing only `(graph_name, payload_type_name)`
therefore measures a seek that still filters the bulk of its matches afterwards, and a `NO_INDEX`
roster row resting on that one figure would be read as settled by the next reader, the roster being
asserted by equality in both directions. Time a `root_operation`-carrying shape beside the named one
on both fixtures, and let the roster row or the shipped index state which shapes were timed.

**3. Minor, and it lands in a durable surface.** The plan twice calls `intent_field_error_channel`
"indifferent to the registration", once in The lever and once in the list of what the `reason` must
state. It is not indifferent, it is unmeasured and cheaper: driving from the relation in a plain
`FROM` means it paid exactly one evaluation of the rule before and reads stored rows after, which is
a small gain in the same direction as the carrier's large one. The `reason` column is read by
consumers through the generated schema reference, so the word that goes there should be the accurate
one. "Already paid exactly one evaluation, so the registration does not change how many times it
reads the rule" says what is meant without claiming a cost that was not measured.

Corrected in passing, in the same commit as these findings: the `READERS_IN_SCHEMA` line under Tests
and gates pinned 99 rising to 100, and trunk moved that constant to 101 after this item was drafted.
Rewritten to say it rises by one and to point at the tree for the figure, which is what the section's
own "re-pin from the failure message rather than predicting them" already asks for.

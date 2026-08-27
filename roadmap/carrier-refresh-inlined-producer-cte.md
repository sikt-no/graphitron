---
id: R839
title: "The carrier refresh costs 41 seconds per capture, and it is the producer CTE inlined per driving row"
status: Spec
bucket: model
priority: 2
theme: model-cleanup
depends-on: []
created: 2026-08-26
last-updated: 2026-08-27
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

The change is one materialization registration, plus corrections to three stored `reason` figures
the measurements below falsified. A registration is a row in `meta_materialize`
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
and therefore already pays exactly one evaluation; the registration does not change how many times it
reads the rule, only what one read costs, an unmeasured small gain in the same direction as the
carrier's large one.

## Implementation

All of it lands in
`graphitron-model/src/main/resources/no/sikt/graphitron/model/graphitron-model.sql`, except one line
of a test list, one new gate-test method, and the re-pinned figures under "Tests and gates" below.
It is the established cheap registration shape, so nothing here is a new mechanism.

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
  is bought for; `intent_field_error_channel` drives from it in a plain `FROM`, so it already paid
  exactly one evaluation before the registration and reads stored rows after it: the registration
  does not change how many times it reads the rule, and the gain in what one read costs is small
  and unmeasured, in the same direction as the carrier's large one.
* The trade. One evaluation of the rule is 4 to 31 ms, which is what a refresh costs, against the
  roughly eight thousand re-evaluations one carrier refresh was paying.
* The measured move, with the schema named: the carrier refresh from 41 s to about 0.3 s on the
  consumer schema this item describes (8408 fields, 2345 types, 5619 catalog columns, 39 classpath
  sources), because a recorded measurement is evidence about the schema it was taken on, per the
  `store-performance` skill, and a reason that omits the schema invites the mistake the carrier's own
  row already made.
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

**Nothing orders the refresh by hand, and one assertion confirms the edge.**
`meta_materialize_dependency` is machine-written at boot by `MaterializeDependencies.populate`,
which parses the stored view definitions: `relationsReadBy` renders each parsed query under a
`VisitListener` and keeps every `Table<?>` whose qualified name is `PUBLIC` plus one segment, a
shape the reference inside `intent_carrier_data_field_live`'s `producer` CTE satisfies. So the edge
from the carrier to this registration appears on its own, and there is no authored row to add.

What confirms the edge arrived needs care, because everything downstream passes on the exact store
where it is missing. `MaterializeRegistryGateTest`'s two order tests cannot fail on it:
`theDerivedDependenciesAdmitARefreshOrder` asserts set equality between the refresh order and the
census, true whatever edges exist, and `theRefreshOrderRespectsEveryDependencyRow` collects
offenders per dependency row, so a missing edge contributes no row and no offender. Nor would the
capture agreement or the carrier's behavioural tests fail, and the reason is an alphabetical
accident worth spelling out. `Materializations.refreshOrder` is Kahn's algorithm with an
alphabetical tie-break on the source view name. The producer view reads only captured base tables,
so it is eligible from the first step; the carrier already carries three edges on today's booted
store (to `intent_errors_field_live`, `intent_resolved_type_binding_live` and
`intent_spelled_table_live`, read straight off `meta_materialize_dependency` after boot), and the
last two sort after `intent_field_payload_producer_live`. So with the new edge missing, the
tie-break still places the producer before the carrier, the carrier fills from current rows, and
`FactCaptureAgreementTest` and `CarrierDataFieldTest` pass. A correct order by name coincidence is
not a confirmed edge, and the property is one rename or one retired registration away from silently
inverting.

So this item adds one structural assertion instead of trusting the coincidence: a new
`MaterializeRegistryGateTest` test asserting `META_MATERIALIZE_DEPENDENCY` holds the row
`('intent_carrier_data_field_live', 'intent_field_payload_producer_live')` on the booted store.
That class's charter already covers it, asserting over the DDL's own registry and the dependency
edges the bootstrap derived from it, and the pin is argued in the test's javadoc on these terms:
the register's safety argument is that a registration changes no answer, the one way this
registration could change one is the carrier refreshing before its producer, and the ordering that
prevents that has to rest on a derived edge rather than on how the relation names happen to sort.

## Three reason rows are wrong, and this change corrects them where they live

`intent_carrier_data_field_live`'s registration prices its refresh at about 170 ms for 15 rows on the
sakila example and about 12 ms on a carrier-free schema. The relation this item is about is the
reason that figure does not transfer: on a consumer schema the same refresh is 41 s. The
`store-performance` skill is explicit that a recorded measurement is evidence about the schema it was
measured on and that a stored reason a later measurement disagrees with needs correcting where it
lives rather than explaining away, so the row is corrected in the same change that moves the number,
with both figures and the schema each was taken on.

Two sibling rows are wrong the same way, the same rule reaches them, and this item's own first table
already holds the measurements, so they are corrected here too: `intent_errors_field_live` records
about ten milliseconds against a measured 4.2 s, and `intent_field_column_scope_live` records about
170 ms "on a real schema" against a measured 6.4 s, both taken on the consumer schema named above in
the same run as the carrier's figure. An earlier draft deferred them to R831, and the deferral landed
nowhere: R831's subject is measured claims in ordinary relation comments, and it scopes the register
out by name, so nothing there accepts these rows. Nor does any gate re-price a refresh duration, the
read-cost gate holding scan counts and asserting no duration, per "Not in scope" below, so a
correction by hand, made where the figures are already in hand, is the only mechanism available.
Each correction is a prose edit inside the row's `reason` string, stating the new figure and its
schema beside the old figure and its schema; no rows, no readers and no tests move with them.

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

Two things about the probe constrain what an honest measurement is. First, it carries a constant the
two-column coordinate omits: the `producer` CTE is `SELECT DISTINCT graph_name, payload_type_name,
family FROM intent_field_payload_producer WHERE root_operation = 'MUTATION'`, and most of the
target's rows are not mutation-rooted (`root_operation` is null for every producing field on a
non-root type and 'QUERY' for most of the rest), so a seek on `(graph_name, payload_type_name)`
alone still filters the bulk of its matches afterwards, and a roster row timed on that shape only
would be read as settled while the shape the probe actually wants went unmeasured. Second, the
correlated equality sits outside that `SELECT DISTINCT`, so whether any index on the target is
reachable at all depends on H2 pushing the predicate through the `DISTINCT` into the inlined CTE
body; if it does not, every shape times identically and a roster row reading "measured, nothing
moved" would record the wrong cause.

So the measurement is three timings beside the no-index floor, on both the consumer store and the
twelve-unit fixture `DerivedReadCostTest` runs: `ix_field_payload_producer_payload ON
intent_field_payload_producer (graph_name, payload_type_name)`; a `root_operation`-carrying shape,
`(root_operation, graph_name, payload_type_name)`, which lets the seek bind the constant too; and,
as the baseline that separates an unhelpful coordinate from an unreached index, the same probe timed
with the `DISTINCT` removed from the CTE text. That last one is a measurement variant only, never a
shipped edit; whether the view needs the `DISTINCT` for its rows is not this item's question. If a
shape moves a reader, it ships with a comment naming that reader, on `ix_spelled_table_spelling`'s
model, and saying which shapes were timed. If nothing moves, the relation joins `NO_INDEX` with the
figures, the shapes timed, and what the `DISTINCT` baseline showed, so the next reader can tell an
unhelpful coordinate from an index the plan never reached. The 327 ms and 261 ms refresh figures in
this item were taken with no index declared, so they are the floor a roster row would stand on, and
an index would have to earn its cost on every refresh on top of them.

## Tests and gates

No new behavioural test: a registration changes no answer, and the claim that the target holds its
view's rows is what the capture agreement machinery already asserts once the `_live` view is
registered with it. One new structural test does land, the edge-presence assertion argued in the
"Nothing orders the refresh by hand" paragraph above, because the ordering that keeps the agreement
true has to rest on a derived edge rather than on the alphabetical accident that currently orders
these two relations correctly.

* `FactCaptureAgreementTest`: one line,
  `registrations.put("intent_field_payload_producer_live", Arm.DERIVED);`, beside the existing row
  for the canonical name. A registered materialization is two relations under one rule and both are
  derived, which is what that list encodes. This test fails a full build and not a scoped one, so
  this item needs a verification build rather than a `-pl` run.
* `MaterializeRegistryGateTest`: one new test asserting the dependency row
  `('intent_carrier_data_field_live', 'intent_field_payload_producer_live')` is present on the
  booted store, per the section above. If the index question lands on the roster, also one
  `NO_INDEX` entry plus its argument in the set's javadoc, naming the shapes timed and what the
  `DISTINCT` baseline showed. Its five structural tests (kinds, column shape, acyclicity, order,
  and nothing materializing outside the mechanism) check the pair and need nothing from the author.
* `DerivedReadCostTest`: three pinned counts move and one pinned set may.
  `READERS_IN_SCHEMA` rises by one, the new `_live` view being a view in the schema; the constant has
  moved twice in the days since this item was drafted, so re-pin it from the tree rather than from any
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

Separately from the build, re-take the figures the edited `reason` rows will state (the producer's
refresh cost and the carrier's move for the new row, and the two sibling refresh durations for the
corrected ones), by the procedure in the
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
* The edge-presence assertion failing on the built store. That means the dependency walk does not
  see the reference shape this plan says it sees, and the fix goes into the walk; the registration
  must not land on a hand-authored ordering workaround.

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

### Round 2 (2026-08-27, Spec -> Ready, reviewer session 51a08584-2b89-44ad-bff7-75ac900ba795)

Verdict: withhold. The plan body is unchanged since round 1, so findings 1 to 3 stand unaddressed and
this round does not restate them. What it does instead is check them against the tree independently
and add what they did not reach.

Both round 1 findings re-verified and confirmed, in the code rather than by reading its prose.
`theDerivedDependenciesAdmitARefreshOrder` asserts set equality between `refreshOrder().registrations()`
and `registrations()`, which holds whatever edges exist; `theRefreshOrderRespectsEveryDependencyRow`
iterates the rows of `META_MATERIALIZE_DEPENDENCY` and appends an offender only per row, so a missing
edge produces no row and no offender. Neither can fail on the store the finding is about. The index
finding is confirmed at the source: the `producer` CTE is `SELECT DISTINCT graph_name,
payload_type_name, family FROM intent_field_payload_producer WHERE root_operation = 'MUTATION'`, and
`intent_argument_column_match`'s roster row does close with "worth revisiting where a reader probes it
from a population larger than the table itself, which is the shape that would change the answer".

Two things the plan asserts and round 1 did not check, both of which hold and neither of which is a
finding. The dependency edge really does arrive on its own: `relationsReadBy` renders the parsed query
under a `VisitListener` and keeps every `Table<?>` whose qualified name is `PUBLIC` plus one segment,
which the reference inside a CTE body satisfies, and today the walk recurses through the producer view
to five base tables reaching no registration, so registering it adds exactly one edge and removes
none. And the capture-order hazard every registration carries is closed here rather than argued away:
`FactCapture` writes the directive tables through `SdlFactCapture.capture`, flushes, and only then
calls `Materializations.refresh` inside the same transaction, so the three `graphitron_*` inputs this
rule reads are complete before the refresh reads them. The plan's sentence about declaration order is
about the DDL file and answers a different question; the answer to this one happens to be yes.

**4. The two sibling `reason` rows are deferred to an item whose subject excludes them.** "Not in
scope" hands `intent_errors_field_live`'s ten milliseconds against a measured 4.2 s, and
`intent_field_column_scope_live`'s "about 170 ms on a real schema" against a measured 6.4 s, to R831
on the grounds that "re-pricing the register's recorded claims is R831's subject rather than this
item's". R831's subject is the opposite of that. Its body is about measured claims in ordinary
relation comments, and it scopes the register out by name: "`meta_materialize`'s registrations are
priced against their readers on every build. What has no gate is the far larger population of measured
claims written into ordinary relation comments." So the deferral lands nowhere. Nothing is filed
against those two rows, R831 is Backlog with `depends-on: []` in both directions, and the next reader
of either row gets a figure this item has already measured to be two to three orders out.

The bind is sharper than a missing cross-reference, because this item's own evidence falsifies the
sentence R831 uses to scope itself out. The plan's "Not in scope" section establishes that the register
is *not* priced on every build: `DerivedReadCostTest` holds scan counts over a twelve-unit synthetic
fixture and asserts no duration anywhere, and `MaterializeRegistryGateTest` asks nothing about cost.
Both cannot be true. Either the register's recorded durations belong to R831 after all, in which case
R831's premise needs correcting and this item should say so and link it, or they belong here, where the
measurements already are, sitting in this item's own first table. The plan already argues the second
for the carrier's row, on the fact model page's rule that a stored reason contradicted by a later
measurement is corrected where it lives; that rule does not distinguish the carrier's row from these
two. Pick one and state it. What the plan may not do is keep a deferral whose destination does not
accept the work.

**5. Sharpening finding 2 rather than adding to it, because the roster row is asserted by equality.**
Round 1 is right that the omitted `root_operation` constant has to be timed. The reason it matters more
than a shape choice is that the correlated equality sits outside a `SELECT DISTINCT`: whether an index
on the target is reachable at all depends on H2 pushing `p.graph_name = f.graph_name AND
p.payload_type_name = f.type_name` through that `DISTINCT` into the inlined CTE body. If it does not,
every shape times identically and a `NO_INDEX` row reading "measured, nothing moved" would record the
wrong cause: not that the coordinate is unhelpful, but that no index was ever reached. Include a
baseline that separates the two, the cheapest being the same timing with the `DISTINCT` removed, and
let whichever artifact ships say which shapes were timed and what the baseline showed. A roster
asserted by equality in both directions is read as settled by the next author, which is the whole
reason this section elevated the question in the first place.

Corrected in passing, in the same commit as these findings: the `READERS_IN_SCHEMA` line under Tests
and gates named 101 as the value on trunk, and trunk is at 109. The line now says the constant has
moved twice since drafting and points at the tree, which is what the sentence was already trying to
say; naming a third figure would be stale on the same schedule as the first two.

### Round 3 (2026-08-27, Spec -> Ready, reviewer session 01UWc2xPX433phnCi5RGgoyW)

Verdict: withhold. Every finding from rounds 1 and 2 is addressed in the plan body, checked against
the tree rather than against the revision commit's account of itself: the edge confirmation is now a
structural assertion with a falsifier row of its own, the index measurement is three timings beside
the no-index floor including the `DISTINCT` baseline, "indifferent to the registration" is gone from
both places it stood, and the two sibling `reason` rows are in scope with the deferral removed. The
revision recorded what it did in its commit message rather than in a note beneath each finding, which
is what the workflow's item-file conventions ask for and what lets a returning reviewer audit a delta
instead of re-reading the spec; worth restoring on the next pass.

The finding below that matters is one neither earlier round could reach, because both checked the
plan's evidence and this one is about the SQL the evidence is taken on.

**6. The predicate the whole item is about is redundant with the outer join, and nothing prices
deleting it.** `intent_carrier_data_field_live`'s `data_channel` CTE filters its population with
`EXISTS (SELECT 1 FROM producer p WHERE p.graph_name = f.graph_name AND p.payload_type_name =
f.type_name)`, and the view's own outer query is `FROM producer p JOIN data_channel d ON d.graph_name
= p.graph_name AND d.type_name = p.payload_type_name`. A `data_channel` row survives that join on
exactly the condition the `EXISTS` tests, so the filter removes no row the join would have kept.

It cannot change `data_fields` either, which is the one place a population filter under a window
usually does change an answer. The window is `COUNT(*) OVER (PARTITION BY f.graph_name, f.type_name)`
and the `EXISTS` is a function of `(f.graph_name, f.type_name)` alone, so it is uniform across every
partition: it drops whole partitions and never thins one. The per-field `NOT EXISTS` against
`intent_errors_field` beside it is the one that does thin a partition, and it is not in question. So
the two spellings, with the filter and without it, return the same rows with the same counts, and
`data_channel` is named exactly once, by that join, so there is no second reader for which the
difference could matter.

This is a finding about the diagnosis and not a predicted defect in the change. The bisect is what
hid it: measuring the CTE in isolation prices a predicate that is load-bearing there, because nothing
outside is left to subsume it, and the redundancy exists only in the assembled view. That is worth
saying because the same bisect is this repo's recommended instrument and this is a failure mode it
has.

Two things follow, and only the second is likely to change what lands. First, the item's own control
discipline asks for the timing: it prices two registration depths against each other but never prices
the third option, deleting the filter and letting the join do the work. My reading is that the
registration still wins, because without the filter the two `CASE WHEN EXISTS` probes into
`intent_bound_table` and `intent_type_backing` face every object-type field in the graph rather than
the carrier candidates, and the control table already shows those two are only cheap today at the
population the filter leaves them. But that is a prediction from reading a plan's shape, which is the
thing this item is elsewhere careful not to accept, and it costs one timing to settle.

Second, and durably: whatever the timing says, the `reason` row must not describe that probe as the
reader the registration is bought for without recording that it is redundant. As the plan stands, an
author who later notices the redundancy and deletes three lines removes the registration's motivating
reader entirely, leaving `intent_field_error_channel`, which the plan itself says gains something
small and unmeasured. The row should say the probe is redundant with the outer join, that deleting it
was priced and what it measured, and that it was kept. A reason that omits this reads as settled to
exactly the author most likely to falsify it.

**7. The edge assertion pins one production pair in the class of universal properties, and the shape
it is worried about is missing from the class whose charter is shapes.**
`MaterializationOrderTest`'s class javadoc says it drives `MaterializeDependencies.populate` and
`Materializations.refreshOrder` "through every shape the design claims", and names them: the direct
read, the walk through an unregistered intermediate view, the cycle, the row-free relation, the
refresh that respects the order. Its fixtures are `SELECT v FROM scratch_p` and `SELECT v FROM
scratch_mid`. A relation reference inside a non-recursive `WITH` body, which is the one shape this
item's edge depends on being collected, is not among them. So the general hole is a missing shape in
the shape roster, and the plan patches one instance of it with a pinned production pair in
`MaterializeRegistryGateTest`, whose other seven tests are all quantified over the whole registry.

A synthetic case in `MaterializationOrderTest` covering a read from inside a CTE body would cover
this item and every registration after it, needs no production relation names in a test, and is
"extending a shape already in the tree" in the sense the Spec gate asks about. The production pin may
still be worth keeping beside it, and the plan's argument for it, that the alphabetical tie-break is
one rename away from inverting, is a good one that the shape test does not answer. What the plan
should not do is land the pin without saying why the class built for this question is not where the
mechanism gets covered.

Related, and it weakens the pin's stated urgency rather than the pin: round 2 established that today's
walk already recurses through `intent_field_payload_producer` to its five base tables. It only reaches
that view through the same CTE-body reference, so the collection of that shape is already
demonstrated on the shipped store, and the edge arriving is close to certain rather than the open
question "what confirms the edge arrived needs care" implies. The pin's real value is as a regression
guard, which is a smaller claim and the one it should make.

**8. "Three reason rows are wrong" overstates two of the three, and the rule invoked is cited to a
page that does not carry it.** `intent_carrier_data_field_live`'s row reads "about 170 milliseconds
for 15 rows on a carrier-bearing schema (the sakila example ...) and about 12 milliseconds for no rows
on a carrier-free one". It names both schemas and both row counts and claims no transfer, so a 41 s
measurement on a consumer schema does not contradict it; it extends it. `intent_errors_field_live`'s
"about ten milliseconds for 15 rows" discloses its scale the same way. Only
`intent_field_column_scope_live`'s "about 170 ms on a real schema" reads as a general claim, and that
one this item's 6.4 s does falsify.

The edits the plan specifies are right either way, both figures with both schemas in each row. It is
the framing that should match: two rows are being completed with a second schema's figure and one is
being corrected, and the difference is worth keeping because the rows differ in what a next reader can
trust them for. A row that names its schema is doing what the register asks; saying it was wrong
teaches the opposite lesson to whoever writes the next one.

The governing rule is real and the plan applies it correctly, but it lives in the `store-performance`
skill ("if your measurement disagrees with a stored reason, the stored reason needs correcting rather
than explaining away. Say so where it lives"), not on the fact model page, which carries no sentence
about correcting stored reasons. Repointed in passing, below.

**9. `CELLS` and `READERS_WITH_CELLS` cannot move in both directions here.** The Tests and gates
section says both "move in both directions at once", on the reachability walk stopping at a registered
target so that readers reaching a registration only through this relation lose those cells. Round 2
established the fact that rules this out: `intent_field_payload_producer`'s five inputs are all
captured base tables, so its subtree contains no registration and no reader reaches one through it.
Registering it adds cells and removes none, which is the same thing round 2 said about the edge count.

The instruction under it, re-pin from the failure message rather than predicting the values, is safe
whatever happens, so this changes no step. It matters because predicting a possible decrease licenses
accepting one, and a decrease here would be the walk doing something the plan does not expect, which
is a signal worth keeping.

Noticed, not a finding, and outside this item's scope: R831's body still carries the sentence that
scopes the register out of it, "`meta_materialize`'s registrations are priced against their readers on
every build". This item's "Not in scope" section is the evidence that it is false, the read-cost gate
holding scan counts over a twelve-unit fixture and asserting no duration. Round 2 raised this as one
of two branches and the plan took the other, correctly, so nothing here is owed. But R831 is Backlog
and the next session to pick it up reads that sentence first. One line in R831 recording what this
item measured would close it, and any session may land it.

Corrected in passing, in the same commit as these findings: the two places attributing the
stored-reason correction rule to the fact model page now name the `store-performance` skill, which is
where the sentence is. Same rule, same edits it licenses; only the citation moves.

### Round 4 (2026-08-27, Spec -> Ready, reviewer session 01CNbmyNbsjJG6UmVKz9CJZo)

Verdict: withhold, on question 2. The plan body is unchanged since `0a7980b`, which was the revision
answering rounds 1 and 2, so round 3's findings 6 to 9 have had no revision and all four stand. This
round does not restate them. It re-derives each one from the tree, because a finding nobody has acted
on is worth exactly as much as its evidence, and adds one the three earlier rounds walked past.

What the goal is, in my own words and not from the phase list: a consumer whose GraphQL schema
reaches the carrier family stops paying most of a minute per build for one derived relation, and gets
back the same rows under the same names. That part is well communicated and the outcome is reachable.
Every symbol the plan names exists as named, checked by FQN-aware grep:
`MaterializeRegistryGateTest.targetsAreShapedLikeTheViewsThatFillThem`,
`theDerivedDependenciesAdmitARefreshOrder`, `theRefreshOrderRespectsEveryDependencyRow`, `NO_INDEX`,
`MaterializeDependencies.populate`, `relationsReadBy`, `registrationsReachedByView`,
`Materializations.refreshOrder`, `Materializations.analyse`, `SchemaIdentifierDriftCheck`,
`FactCaptureAgreementTest` with `Arm.DERIVED`, `CarrierDataFieldTest`, `MaterializationOrderTest`,
`DerivedReadCostTest`'s four constants, `ix_spelled_table_spelling`, and the three `reason` strings
quoted in "Three reason rows are wrong", each of which reads on the tree exactly as the plan quotes
it. `intent_field_payload_producer` really is declared as a view with the six columns in the order
the proposed table gives them; `FactCaptureAgreementTest` already carries the canonical name at line
505 beside the `intent_errors_field` / `intent_errors_field_live` pair, so the one-line addition is
the right shape. The register now carries twenty registrations against the twelve the measured store
had, which is what the plan's "the total is a floor" sentence already says.

**Finding 6 confirmed at the source, and its consequence is larger than round 3 stated.** The
`data_channel` population filter and the outer join test the same condition, so the filter removes no
row the join keeps; the window partitions by `(f.graph_name, f.type_name)` and the filter is a
function of those two columns alone, so it drops whole partitions and never thins one; `data_channel`
is named once. All three hold on the tree.

What round 3 did not say is that `producer` is named exactly twice, and only one of the two namings
is expensive. The outer `FROM producer p` is one inlined evaluation. The correlated
`EXISTS (SELECT 1 FROM producer p ...)` inside `data_channel` is the per-driving-row one, and it is
the redundant one. So deleting three lines does not merely offer a cheaper lever than the
registration: it removes the entire diagnosed cost term by construction, leaving `producer` named
once in a plain `FROM`, which is the same one-evaluation shape the registration buys. The registration
is then bought for a reader that no longer exists, and what remains to price is not the carrier's 41 s
but what the two `CASE WHEN EXISTS` probes cost at the unfiltered population. This item's own control
table bounds those two at about 9 ms across the filtered population (224 ms against 233 ms, rows two
and three), and the driving-row ratio is a count the same store can answer, so the timing round 3 asks
for is cheap and is the one that decides whether this item ships a registration or a deletion.

Worth one more sentence because it is the same class of miss: the four-line comment directly above
this predicate already documents the hazard it pays. It explains that the three disqualification arms
deliberately stand on `graphql_field` rather than on the CTE, because "H2 inlines a CTE afresh at
every naming". The author of that comment reasoned about every correlated naming into the CTE except
the one four lines below it.

**Findings 7, 8 and 9 confirmed independently.** `MaterializationOrderTest`'s fixtures are
`SELECT v FROM scratch_p`, `SELECT v FROM scratch_mid`, `SELECT v FROM scratch_src` and
`SELECT v FROM scratch_y`; no fixture reads a relation from inside a `WITH` body, so the shape this
item's edge depends on is absent from the class whose javadoc claims every shape the design claims.
The carrier's `reason` row does name both schemas and both row counts and
`intent_errors_field_live`'s names its row count, so finding 8's framing point holds, and only
`intent_field_column_scope_live`'s "170 ms on a real schema" reads as a general claim. And
`registrationsReachedByView` walks from every view, stops at a registered target, and the producer's
five inputs are all captured base tables, so no reader loses a cell.

**10. `READERS_IN_SCHEMA` does not move at all, and the reason generalizes to the sentence above it.**
"Tests and gates" says "`READERS_IN_SCHEMA` rises by one, the new `_live` view being a view in the
schema". There is no new view. The plan renames one view and adds one table, and
`registrationsReachedByView` keys its answer on relations whose `INFORMATION_SCHEMA` kind is `VIEW`,
so the census loses `intent_field_payload_producer` and gains
`intent_field_payload_producer_live` and its cardinality is unchanged.

The tree confirms it twice. `CELLS`' own javadoc says of the write-destination registration that "the
relation was already a view in this domain with cells of its own; registering it renamed those cells
onto the `_live` view rather than removing them", which is the same rename read on the cell axis. And
`02ec43c`, the commit that registered `intent_field_scope_table`, moved `READERS_IN_SCHEMA` from 106
to 107 while its DDL diff shows the registration was a rename plus a table and one genuinely new view,
`intent_condition_membership`. The new view is the whole of the +1; the registration contributed
nothing.

The same reasoning takes `READERS_WITH_CELLS`. Both readers of this relation,
`intent_carrier_data_field_live` and `intent_field_error_channel`, already reach `intent_errors_field`
and so already have cells; the new `_live` view reaches no registration, its five inputs being base
tables. So no view gains its first cell and none loses its last, and only `CELLS` moves, upward, by
the number of views that reach the producer. The section predicts three moving counts where the
mechanism supports one.

The instruction under it, re-pin from the failure message rather than predicting, is safe whatever
happens, so like finding 9 this changes no step. It is worth a round for the reason finding 9 gives
and one more. Finding 9's reason: predicting a move licenses accepting one, and a `READERS_IN_SCHEMA`
that did rise would mean this change added a view it did not intend to, which is a signal to keep. The
extra reason: rounds 1 and 2 each edited this very sentence, for the figure it named, and neither
looked at the direction claim underneath. A count corrected twice reads as checked.

**What would satisfy this round.** Question 2 is what fails, and finding 6 is the whole of it. As the
plan stands an implementer lands a table, a view rename, seven relocated comments, a registration
`reason`, a structural test and three re-pinned constants, in service of a probe that a three-line
deletion removes. Price the deletion on the same store the other two depths were priced on, state the
figure, and say which of the two ships. If the registration still wins, the `reason` row has to record
that its motivating reader is redundant with the outer join and was kept deliberately, per round 3.
If the deletion wins, this item is a different and much smaller change, and that is a better outcome
than a registration nobody can later remove. Findings 7 to 10 are cheap once that fork is settled:
7 wants a sentence saying why the CTE-body shape is not covered where shapes are covered, 8 wants
"completed" rather than "wrong" for two of the three rows, and 9 and 10 want the three predicted
constant moves reduced to the one the mechanism supports.

Non-blocking, noticed while checking. `MaterializeRegistryGateTest` carries nine tests, not the five
the plan enumerates; the four it does not name are the two index tests the plan handles in its own
section, the hand-written-boundary test and the analyse test, and none of the four needs anything from
the author, so the count is the only thing stale. And round 1's note that grep finds no Java reader of
the relation is no longer true: `ErrorChannelRelationTest` reads `Tables.INTENT_FIELD_PAYLOAD_PRODUCER`
and asserts the whole graph's rows over it. That is not a defect, the test already reads the
registered `INTENT_ERRORS_FIELD` the same way and so its fixture refreshes before it asserts, and
"two readers in SQL" is still literally right. It is worth knowing that a behavioural test does read
this relation directly, because it is the test that would fail first if the capture-order argument
round 2 closed ever stopped holding.

### Round 5 (2026-08-27, Spec -> Ready, reviewer session 01PgVUtgWCaJD7QmwLHtHTmy)

Verdict: withhold, on question 2, and for the same reason round 4 gave. The plan body is unchanged
since `0a7980b`, whose only edit after round 3 was round 3's own citation repoint, so round 3's
findings 6 to 9 and round 4's finding 10 have had no revision and all five stand. This round does
not restate them. It re-derives the one that decides the item, and then adds two things neither
earlier round reached: the doctrine this register already carries for exactly this fork, and a third
arm of the fork that round 4's framing treats as binary.

Question 1 passes, and it is worth saying plainly rather than as a formality, because four rounds of
withholding can read as a plan in trouble and this one is not. In my own words: a consumer whose
GraphQL schema declares mutation payloads gets most of a minute back on every build, once per
capture, and gets back byte-identical rows under the names every reader already spells. The cost is
real, being paid now, and diagnosed rather than guessed: a real captured store, a bisect that names
one predicate rather than a section, and a control that killed the reading a reader of the view's
shape would arrive at first. Nothing about that is in doubt, and nothing in this round touches it.
What is in doubt is only which lever collects the minute, and the plan still prices two of at least
four.

**Finding 6 re-derived at the source, and every part of it holds.** Checked against the view body
rather than against round 3's or round 4's account of it. The `data_channel` filter is
`EXISTS (SELECT 1 FROM producer p WHERE p.graph_name = f.graph_name AND p.payload_type_name =
f.type_name)` and the outer query is `FROM producer p JOIN data_channel d ON d.graph_name =
p.graph_name AND d.type_name = p.payload_type_name`, where `d.graph_name` and `d.type_name` are
`f.graph_name` and `f.type_name` projected; so the join's surviving condition is the filter's
condition verbatim and the filter keeps no row from being dropped and drops no row the join keeps.
The window is `COUNT(*) OVER (PARTITION BY f.graph_name, f.type_name)` and the filter is a function
of those two columns alone, so it is constant across each partition and removes whole partitions
without thinning one; the per-field `NOT EXISTS` against `intent_errors_field` beside it is the one
that thins, and it is not in question. `data_channel` is named once, by that join. And the view names
`intent_field_payload_producer` once, at line 5399, so the two namings round 4 counts are namings of
the CTE and its reading is right: the outer `FROM producer p` is one inlining and the correlated
`EXISTS` is the per-driving-row one. Deleting three lines therefore leaves the relation named once in
a plain `FROM`, which is the one-evaluation shape the registration is being bought to produce.

Also verified while there, because the plan's `reason` will state it: `intent_field_payload_producer`
is named exactly three times in the whole DDL, its own `CREATE VIEW`, the carrier's CTE body and
`intent_field_error_channel`'s `FROM intent_field_payload_producer p`. That last one is a single
uncorrelated naming inside a derived table, so "two readers in SQL, one of which already pays exactly
one evaluation" is exact rather than approximate.

**11. The register states a doctrine for this exact fork, and its last three rows are that doctrine
and both of its discharges. This plan discharges neither.** Round 3 asked for the deletion's timing
on the grounds of "the item's own control discipline". The grounds are stronger than that and they
are written down in the surface this change edits. `intent_mutation_payload_key_membership_live`'s
`reason` closes with the general form: "a registration prices the rule as it stands, so a rule with a
re-evaluation inside it should be rewritten before it is priced". The two rows immediately after it
are the two ways that comes out. `intent_mutation_write_destination_live` reads "The rewrite came
first, as the row above this one says it must", and records the rewrite it tried, reversing one join
in `intent_mutation_write_agreement` so the small derived side drives, with the figure it bought
(75741 ms to about 13 s) beside what the registration then bought (13 s to 5.4 ms).
`intent_field_scope_table_live` reads "So the rewrite was tried first, as the doctrine here says it
must be, and it is the case where the rewrite is not the answer", and records the rewrite it tried
and the 68349 ms that refuted it.

Two things follow that finding 6 does not already say. First, the doctrine's literal subject is a
re-evaluation *inside the rule being registered*, and this one is inside the reader; but both rows
that cite it discharged it with a reader-side rewrite, a join order in the reading view, so the
register's own practice reads the doctrine the way that covers this item. The re-evaluation here is
one predicate in one reader, which is the smallest reader-side rewrite either of those rows
contemplated. Second, and this is what makes it a question-2 finding rather than a restatement: the
row this change writes is the twenty-first in that register and it lands directly under those three.
As the plan stands it can state the trade and the measured move honestly and cannot state the one
thing its two immediate predecessors both state, because the rewrite was never priced. A reason that
is silent where the two rows above it are explicit is not a stylistic gap in a comment; the register
is where this project keeps the argument that a registration was the right rung, and this one would
be the first row that skipped the rung below it without saying so.

**12. The fork has a third arm, and it is the arm that survives whichever way the deletion's
population question goes.** Round 4 states the fork as a registration or a deletion, and prices the
deletion's risk correctly: without the filter, the two `CASE WHEN EXISTS` probes and the window face
every field of every OBJECT type rather than the carrier candidates, and how much that costs is a
driving-row ratio the same store can answer. But those are not the only two spellings that remove
the per-driving-row naming. The filter can also be spelled as a join into `data_channel` rather than
as a correlated `EXISTS`, which is one inlining of the producer instead of one per driving row, and
which keeps the narrow population that the probes and the window run over. It is the arm that needs
no registration, no table, no relocated comments, no `reason` row, no structural test and no re-pinned
constants, and it does not depend on the ratio coming back small.

Two things constrain it, and both are why it belongs in the priced set rather than in a
recommendation. The `producer` CTE is `SELECT DISTINCT graph_name, payload_type_name, family`, so it
is not unique on the two columns the filter tests: a payload type produced under two families is two
rows there, which is load-bearing in the outer join, where the plan's own prose says a payload two
families both return is a row per family. Joined into `data_channel` on the two columns it would
duplicate each field row per family and double `data_fields`, which is the one column of this
relation a reader refuses on. So the arm needs its own two-column projection and is not a
three-character edit. And whether H2 plans it as one evaluation is a claim about the engine, which on
this item's own standard is measured rather than reasoned about. Naming it is not choosing it: it is
the third timing the same store and the same program can take in the same sitting as the deletion's,
and leaving it unpriced would repeat the shape of finding 6, a lever the plan did not look at because
it was not the lever the plan started from.

**What would satisfy this round.** Round 4's requirement, widened by one arm. Price, on the store the
two registration depths were priced on: the deletion; the filter respelled as a join on a two-column
projection; and the registration figures already in hand. State the three figures and say which
ships. Then discharge the rung, in whichever surface the change lands in: if a registration ships,
its `reason` says the rewrite was tried, what each spelling measured, that the motivating probe is
redundant with the outer join, and that it was kept deliberately, which is what the three rows above
it in the register do and what round 3's second half already asked for; if a rewrite ships, this item
is a much smaller change and the register gains no row at all. Findings 7 to 10 are cheap once the
fork is settled and round 4 has already said what each wants.

Non-blocking, and confirming rather than adding. `MaterializeRegistryGateTest` carries nine tests,
per round 4, not the five the plan enumerates. `MaterializationOrderTest`'s fixtures are
`SELECT v FROM scratch_p`, `SELECT v FROM scratch_mid`, `SELECT v FROM scratch_src` and
`SELECT v FROM scratch_x` / `SELECT v FROM scratch_y`, none of which reads a relation from inside a
`WITH` body, so finding 7's missing shape is missing. The register now carries twenty rows.

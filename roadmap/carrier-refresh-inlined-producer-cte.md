---
id: R839
title: "The carrier states one condition twice, and the duplicate re-derives the producer once per driving row"
status: Spec
bucket: model
priority: 2
theme: model-cleanup
depends-on: []
created: 2026-08-26
last-updated: 2026-08-27
---

# The carrier states one condition twice, and the duplicate re-derives the producer once per driving row

`intent_carrier_data_field_live` takes 41 seconds to produce 151 rows against a real consumer store,
and the whole of it is one correlated `EXISTS` re-deriving a 172-row rule once per driving row.
Registering the relation that rule reads, `intent_field_payload_producer`, measures 0.3 seconds.

Unlike R781 and R830, which price relations nothing exercises at build time yet, this cost is being
paid now, on every capture, by every consumer whose schema reaches the carrier family. That is what
the priority reflects.

## What changes when this lands

The carrier relation evaluates its producer rule a fixed number of times per refresh instead of once
per driving row, and returns the same rows under the same names. Nothing about what the store answers
changes: the same rows, in the same columns, under the same names every reader already spells, which is
proved for both spellings rather than argued.

That sentence is deliberately about how many times the rule is evaluated rather than about how many
times its condition is written down, because the two arms differ on the second and agree on the first.
The arm this item ships, B below, respells the correlated `EXISTS` as a join and leaves the condition
stated in two places; what it removes is the per-driving-row evaluation. An earlier draft of this
section said the re-derivation goes "because the condition is stated once instead of twice", which is
true of arm A alone and read as though the fork were settled towards the arm the plan leans away from.

**What a consumer gets, stated at the strength the evidence supports.** The re-derivation is gone by
construction, and that is checkable in the tree today. The size of the win is not: the 41 seconds this
item measured was measured on one consumer schema, and no figure for the shipped spelling can be taken
in this reactor at all, for the reasons the audit records. So a consumer whose schema reaches the
carrier family should expect the dominant term of that 41 seconds to go, and this item does not promise
a number. An earlier draft of this section promised 0.3 seconds, which was one candidate lever's
measurement read as the item's outcome.

The change is an edit to one view body, plus the three stored `reason` rows the measurements below
complete or correct, plus one seeded anchor that makes the row-identity claim fail when it breaks. All
three are deliverable in this repository: which arm ships is decided below rather than by a timing, and
the `reason` rows state this item's own measurements as provenance rather than waiting on figures to be
re-taken. The register gains no row: why registering the relation is a later question rather than an arm
of this one is argued in "The condition is stated twice" below.

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

## The condition is stated twice, and the duplicate evaluation goes before anything is priced

The predicate this item is about **removes no row, and that is not the same as doing nothing.** It has
two jobs, and only one of them is redundant. Getting that distinction wrong is what made an earlier
draft of this section state a three-way race that principle does not admit.

The predicate this item is about removes no row. The view's outer query is `FROM producer p JOIN
data_channel d ON d.graph_name = p.graph_name AND d.type_name = p.payload_type_name`, and
`d.graph_name` and `d.type_name` are `f.graph_name` and `f.type_name` projected, so the join's
surviving condition is the filter's condition verbatim. It cannot change `data_fields` either: the
window is `COUNT(*) OVER (PARTITION BY f.graph_name, f.type_name)` and the filter is a function of
those two columns alone, so it is constant across each partition and drops whole partitions without
thinning one. The per-field `NOT EXISTS` against `intent_errors_field` beside it is the one that
thins a partition, and it is not in question. `data_channel` is named once, by that join, so there is
no second reader for which the difference could matter. Confirmed by execution as well as by reading;
see `roadmap/audits/2026-08-27-carrier-filter-redundancy-probe.md`.

**The filter's second job is not redundant, and the page predicts it.** Beyond testing a condition the
join re-tests, the filter narrows the population that the two `CASE WHEN EXISTS` probes and the window
run over. That narrowing cannot be recovered from outside, and the reason is stated rather than
guessed: a derived view carrying a window function cannot be pruned by a predicate applied outside it,
so a reader takes it once per answer and pairs it on its key rather than correlating it per row.
`data_channel` carries the window. So deleting the filter outright does not merely risk a wider
population, it is *predicted* to widen one, and this is not a correctness-preserving simplification
with no cost side.

So `producer` is named exactly twice, the outer `FROM producer p` being one inlining and this
correlated `EXISTS` the per-driving-row one, and the per-driving-row naming has two spellings that
remove it:

* **A. Delete the filter**, letting the outer join carry the condition. Three lines out, and
  `producer` is left named once in a plain `FROM`. It gives up the narrowing above, which is why it is
  not obviously the smaller change it looks like.
* **B. Respell the filter as a join** into `data_channel`. One inlining rather than one per driving
  row, and the narrowing stays inside the derivation where the window can use it, which is the shape
  the page describes. It needs its own narrower projection: the `producer` CTE is `SELECT DISTINCT
  graph_name, payload_type_name, family` and so is not unique on the two columns the filter tests, and
  joining it as it stands duplicates each field row per family and doubles `data_fields`, reproduced in
  the audit. It also leaves `producer` named twice rather than once, the projection being the second
  naming, and leaves the condition itself written in two places, the new join inside `data_channel` and
  the outer join that already tested it. Both of those are stated rather than glossed under "which arm
  ships" below.

**Registering the relation is not a third arm, and principle rather than measurement is why.**
`meta_materialize.reason`'s own column comment defines what a row of that register claims: a
hand-written derivation argues in its table comment that no view could express its rule, a
registration argues that a view expresses the rule correctly and only too slowly, and a row that
cannot say which is not a registration. A registration's honest row here cannot say either. The rule
is right as a view, and it is not too slow to evaluate per naming; it is slow because a naming exists
that need not. The doctrine two rows below says the same as an obligation rather than an option: "a
registration prices the rule as it stands, so a rule with a re-evaluation inside it should be
rewritten before it is priced", argued from a case where pricing first was wrong by three orders. Both
rows that discharge it use obligation language, and in the register there is no row where a rewrite
and a registration were priced side by side and the registration taken without the rewrite having
first landed or been refuted. The fact model page says it from the other side: register the relation
every expensive reader has in common, "and not the relation that looked slow from where the reader
happened to stand".

So a registration is not forbidden here. It is inadmissible as a *first* move, and the question it
answers is unaskable until the surplus evaluation is gone, because a registration prices the rule as it
stands and the shape being priced still re-derives it once per driving row. That splits into two
questions with an order:

1. **Is the condition evaluated once per driving row when one evaluation would do?** Yes, proved, and
   no cost figure is needed to act on it. This item is that question, and it ships arm B, for the
   reasons under "Which arm ships" below. By how much a consumer gains is a figure only a consumer
   store can supply, and this item does not wait on it.
2. **Does anything still want a registration?** Not answerable here, and filed as R861
   (`roadmap/producer-registration-after-duplication-removal.md`), to be taken up after this item
   lands and the carrier is re-measured on a consumer store. The two candidate depths already priced
   (327 ms and 261 ms against the promoted CTE's 253 ms and 206 ms) carry forward to it as evidence,
   along with the audit.

**Why the duplicate evaluation goes even if the cost turns out small, and what the duplicated statement
owes once it stays.** Two spellings of one condition agree exactly until one of them changes, which is
the state that precedes drift rather than evidence against it, and the audit has just proved they agree
today. The fact model page also names what this predicate is in its taxonomy: a filter one caller
applies, which is one of the things to watch for as never having been a fact at all. Neither of those is
a cost argument, so neither waits on a figure. What they do change is what the arm that keeps the
statement owes: a duplicated condition left in place on a drift argument this strong may not rest on a
proof by reading, which is the load the seeded anchor picks up under "Which arm ships" below.

**Which arm ships: B, and it is decided by a bound rather than by a ranking.** An implementer takes
arm B. The reasoning is available here, on this tree, and does not need the consumer store:

* **Arm B's worst case is bounded by reading.** Every population inside the view body is exactly the
  population it faces today, the join on a duplicate-free projection of the filter's own two columns
  admitting the same rows the `EXISTS` admits. So nothing in the body faces more rows than it does
  now, the work removed is the roughly eight thousand re-derivations, and the work added is one further
  inlining of a rule this item measured at 4 to 31 ms. Arm B cannot be dearer than today by more than
  that one evaluation, whatever the engine does with the join.
* **Arm A's worst case is not bounded here.** Deleting the filter widens the population the two `CASE
  WHEN EXISTS` probes and the window run over from the carrier candidates to every field of every
  OBJECT type, and how much that costs is a driving-row ratio only a consumer store answers. The page
  rule predicts the widening rather than merely permitting it, and this item's own control table prices
  those two probes only at the population the filter leaves them. Choosing A here would be choosing an
  unmeasured population change over a bounded one.
* **The one engine claim arm B rests on is the claim the whole diagnosis already rests on.** H2 inlines
  a non-recursive `WITH` afresh at every naming and eliminates no common subexpression, which is what
  makes the correlated `EXISTS` per-driving-row work and the outer `FROM producer p` one evaluation.
  The DDL states it in the four-line comment above `data_channel`. An uncorrelated naming in a join is
  the second of those two cases, so arm B is asking the engine for the shape the file already documents
  it as giving. It is not a new claim about the planner.

What the timing is for, then, is confirmation and not selection. Where a consumer-scale captured store
is reachable, take the carrier's refresh before and after the edit by the `store-performance` procedure
and record both figures with the schema they were taken on, in the `reason` row this item already edits.
Where one is not reachable, which is the case in this repository and is why the fork could not be
ranked, record the absence in the same row: the rewrite landed, the per-driving-row evaluation is gone
by construction, and no post-rewrite consumer-scale figure was taken. A recorded absence is what the
register already does for figures no reader of the file can re-take, in
`intent_node_id_instruction_live`'s reason: "they are stated as provenance because no reader of this
file can re-take them". An implementer with no consumer store therefore ships the whole item, and the
missing figure is R861's trigger rather than this item's blocker.

**What arm B does not do, said plainly.** It does not reduce the condition to one statement. After it,
`data_channel` joins the producer's payload types and the outer query joins them again, so the
condition is still written in two places, and the drift argument in the paragraph above still applies to
them. Two things make that the right trade rather than a residue swept aside. The drift hazard is what
the seeded anchor under
"Tests and gates" converts into an enforced invariant: the anchor pins the view's rows over a store
seeded to make the two conditions disagree if they ever do, so a divergence fails a build instead of
changing an answer quietly. And the alternative that does reduce the statement to one, arm A, buys that
tidiness with the unbounded population change above. A per-driving-row re-derivation is the defect
being paid for now; two agreeing statements of a condition, with an enforcer under them, is not.

**The edit arm B makes, so the implementer is not choosing a spelling either.** In
`intent_carrier_data_field_live`, one more non-recursive `WITH` term beside `producer`, projecting it
down to the two columns the filter tests:

```sql
producer_type (graph_name, payload_type_name) AS (
  SELECT DISTINCT graph_name, payload_type_name FROM producer
),
```

`data_channel` then joins it, `JOIN producer_type pt ON pt.graph_name = f.graph_name AND
pt.payload_type_name = f.type_name`, and the correlated `EXISTS` against `producer` comes out of its
`WHERE` clause. The per-field `NOT EXISTS` against `intent_errors_field` beside it stays, that being the
arm that thins a partition. The projection is taken over `producer` rather than over
`intent_field_payload_producer` directly so that `root_operation = 'MUTATION'` is written once and the
two terms cannot drift on it; both spellings cost two inlinings of the rule per refresh, so the choice
is about the constant rather than about the cost. Nothing else in the body moves, and the column list,
the window and the three disqualification arms are untouched.

**Where the argument lands.** `intent_carrier_data_field` is itself a registration and this change
edits its row already, so there is a `reason` to write either way; that row also already prices a
rewrite beside a registration, recording the carrier falling "from about 49 seconds to that 170
milliseconds by restructure alone". The asymmetry worth stating once is that a rewrite's argument lands
in the store's ungated prose while a registration's earns a gated row and cells in the read-cost gate.

## The trade a registration would have to win

Also R861's inheritance rather than a condition on this item. The trade the middle rung has to win is a refresh against the re-evaluations it avoids, and here it
is not close. One evaluation of `intent_field_payload_producer` is 4 to 31 ms. It has two readers in
SQL: this correlated probe, and `intent_field_error_channel`, which drives from it in a plain `FROM`
and therefore already pays exactly one evaluation; the registration does not change how many times it
reads the rule, only what one read costs, an unmeasured small gain in the same direction as the
carrier's large one.

## Implementation of the registration this item does not make

**This section covers the registration this item does not make**, and is kept because the follow-up
item inherits it. Nothing here is conditional: this item ships arm B, which is an edit to
`intent_carrier_data_field_live`'s body and none of what follows, and R861
(`roadmap/producer-registration-after-duplication-removal.md`) is where a table, a view rename, the
relocated comments, the registration row and the re-pinned constants would land if that item finds
anything still wants them. It is written out here rather than in R861 because it was specified here
first, and a reader of R861 will want it.

All of the registration would land in
`graphitron-model/src/main/resources/no/sikt/graphitron/model/graphitron-model.sql`, except one line
of a test list, two new test methods, and the re-pinned figure under "Tests and gates" below.
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
  PRIMARY KEY (graph_name, type_name, field_name, family),
  FOREIGN KEY (graph_name) REFERENCES store_graph (graph_name)
);
```

Same column names in the same order as the view, which is what
`MaterializeRegistryGateTest.targetsAreShapedLikeTheViewsThatFillThem` closes: the refresh is
`INSERT INTO target SELECT * FROM source`, so a shape mismatch writes the wrong columns rather than
failing.

The key is the relation's own grain and not a convenience. The view comment says it: "The grain is
the coordinate", and the family column's comment says "A field carrying two is a row per family
rather than a pick". Uniqueness holds on the inputs rather than by assumption: `graphitron_service`
and `graphitron_mutation` are each keyed on `(graph_name, type_name, field_name)` so their arms
produce one row per coordinate, the ROUTINE arm carries `SELECT DISTINCT` because
`graphitron_routine` is keyed per hop, and `root_operation` is a function of `(graph_name,
type_name)` through a grouped subquery so it adds no multiplicity. `payload_type_name` is `f.named_type`,
carried straight off the coordinate's own row, so both non-key columns are functions of the coordinate
and the ROUTINE arm's `DISTINCT` collapses to one row per coordinate rather than merely deduplicating
hops. `root_operation` being nullable is therefore not an obstacle: it is not in the key.

The key is declared on that grain argument and on nothing else. It does **not** narrow the index
question: the heap the fact model page warns about is the cost a *probing* reader pays, the one known
probe here seeks `(graph_name, payload_type_name)`, and only `graph_name` is a prefix of this key, so
the probe gains a graph-partition seek and no more. The index question below is exactly as open as it
was. What declaring the key does buy is that a modeling error becomes a capture-time constraint
violation on a consumer's build rather than silent duplicate rows.

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

So this arm adds two assertions instead of trusting the coincidence, and the general one comes first.
`MaterializationOrderTest`'s class javadoc says its fixtures drive the population routine "through
every shape the design claims" and names them; a relation reference inside a non-recursive `WITH`
body, which is the shape this edge depends on being collected, is not among them, its fixtures being
plain `SELECT v FROM scratch_p` reads. That is a hole in the shape roster rather than a fact about
this relation, so it is closed where shapes are closed: one more synthetic case in that class, a
registered source view reading its prerequisite from inside a `WITH` body, asserting the same one
dependency row its sibling direct-read case asserts. It covers this arm and every registration after
it and names no production relation.

The production pin lands beside it, with the smaller claim that is the honest one. A new
`MaterializeRegistryGateTest` test asserts `META_MATERIALIZE_DEPENDENCY` holds the row
`('intent_carrier_data_field_live', 'intent_field_payload_producer_live')` on the booted store, as a
regression guard rather than as the thing that establishes the mechanism works: today's walk already
recurses through `intent_field_payload_producer` to its five base tables and only reaches that view
through the same CTE-body reference, so collection of the shape is already demonstrated on the
shipped store and the edge arriving is close to certain. What the pin is worth keeping for is that
the alphabetical tie-break which currently orders these two relations correctly is one rename or one
retired registration away from inverting, and that is a regression the shape test cannot see.

## Two reason rows are completed and one is corrected, where they live

These three edits depend on no part of the lever question. Their figures are in hand and the rows they
correct are wrong now, so nothing about which spelling ships changes a word of them. They have been
carried across five review rounds by coupling to a decision they do not touch, which is bookkeeping
rather than a seam; if this item is split again for any reason, they go with whichever half lands
first.

**Where the figures come from: this item's own measurements, stated as provenance.** In hand means the
figures in the first table above, taken on 2026-08-26 and 2026-08-27 on the consumer schema named
there, and each edited row states the figure with that schema and that date rather than as a current
reading. Nothing is re-taken to land these three edits. That is what the register already asks of a
row whose figures its readers cannot reproduce: `intent_node_id_instruction_live`'s reason states a
consumer schema's numbers and closes "Those are that schema's figures and that tree's; they are stated
as provenance because no reader of this file can re-take them", and the `store-performance` skill calls
a recorded measurement evidence about the schema it was measured on. A dated figure with its schema
named is the strongest thing any of these rows can carry, because the store the figures came from is
not in this repository and no build here can write one.

The carrier's row is the one where that matters most, because its own new figure is the pre-rewrite
one. The 41 s is what the carrier cost with the per-driving-row re-derivation in it, so the row states
it as measured before this rewrite, on that schema, on that date, and states what the rewrite removed
by construction. Whether a post-rewrite figure joins it depends on reach and not on this item: where a
consumer-scale store is reachable the implementer takes it and the row carries both, and where one is
not the row records that no post-rewrite consumer-scale figure was taken, which is the honest reading
and is R861's trigger. An implementer without such a store ships all three edits and every other
deliverable this item names.

The three rows differ in what a next reader can trust them for, and the difference is worth keeping
because it teaches opposite lessons to whoever writes the next one.

`intent_carrier_data_field_live`'s registration prices its refresh at about 170 ms for 15 rows on the
sakila example and about 12 ms for no rows on a carrier-free one. It names both schemas and both row
counts and claims no transfer, so this item's 41 s on a consumer schema does not contradict it; it
completes it with a third schema's figure. The relation this item is about is the reason the original
figure does not transfer. The `store-performance` skill is explicit that a recorded measurement is
evidence about the schema it was measured on and that a stored reason a later measurement disagrees
with needs correcting where it lives rather than explaining away, so the row gains the new figure in
the same change that moves the number, with the schema each was taken on. A row that names its schema
is doing what the register asks, and saying it was wrong would teach the opposite.

Two sibling rows are reached by the same rule and this item's own first table already holds the
measurements, so they are edited here too, but only one of the two is wrong.
`intent_errors_field_live` records about ten milliseconds for 15 rows against a measured 4.2 s: it
discloses its scale through the row count, so like the carrier's row it is completed rather than
corrected. `intent_field_column_scope_live` records about 170 ms "on a real schema" against a
measured 6.4 s, and that one reads as a general claim and is falsified. Both new figures were taken on
the consumer schema named above, in the same run as the carrier's.

An earlier draft deferred these two to R831, and the deferral landed nowhere: R831's subject is
measured claims in ordinary relation comments, and it scopes the register out by name, so nothing
there accepts these rows. Nor does any gate re-price a refresh duration, the read-cost gate holding
scan counts and asserting no duration, per "Not in scope" below, so a hand edit where the figures are
already in hand is the only mechanism available. Each edit is a prose change inside the row's `reason`
string, stating the new figure and its schema beside the old figure and its schema; no rows, no
readers and no tests move with them.

## The index question the registration would inherit, and it is answered by measurement

**This section, too, covers the registration this item does not make**, and is kept because the
follow-up item inherits it. Nothing here is conditional on this item: arm B declares no target, so it
raises no index question at all, and everything below belongs to R861. Every registered target either carries a declared index whose `COMMENT ON INDEX` names the
reader it serves, or has a row in `MaterializeRegistryGateTest.NO_INDEX` arguing why not, asserted by
equality in both directions. So the registration cannot land without answering the question, and the
answer has to be a figure. What the declared primary key above changes is the scope of it: the target
is no longer a heap, so every join that reads it whole or drives from it is served by the key, and
what is left to measure is the probe coordinate alone.

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

**One new behavioural test, and it is the item's own invariant.** The claim this change rests on is
that the filter's condition and the outer join's are one condition, so that respelling the first as a
join over a duplicate-free projection admits exactly the rows it admitted before; right now that claim
is a proof by reading plus a synthetic probe recorded in an audit. Nothing fails if it is wrong, which means it is not yet an invariant. It gets an
enforcer: a new seeded anchor for the carrier relation in
`graphitron-model/src/test/java/no/sikt/graphitron/model/intent/`, alongside `ProducerCardinalityTest`,
`MutationPayloadColumnTest` and their siblings.

The habitat is not a preference. What a view returns given rows is pinned in `graphitron-model`, the
module whose DDL declares it, against a store seeded row by row, and the rule's own edges are that
seeded half's business; agreement with the transitional walk stays beside the walk and retires with it.
`CarrierDataFieldTest` is in `graphitron/src/test/java/no/sikt/graphitron/rewrite/derive/`, the second
habitat, so an anchor placed there is scheduled to drain while this view is not, and its population is
whatever the fixture schema happens to declare. That last part is the same vacuity the earlier findings
caught twice: a captured fixture cannot be relied on to contain the discriminating type, so a test over
it can pass without ever exercising the claim.

What makes the anchor an enforcer rather than a re-run of the audit is which rows it seeds. Three
seedings, and each of them is a way the change could be wrong:

* An OBJECT type in `data_channel`'s pre-filter population that no mutation-rooted producer names, so
  the filter and the join have something to disagree about.
* A producing type whose partition `data_fields` is asserted over, so a false redundancy claim changes
  an asserted count instead of passing unobserved.
* A payload type produced under two families, with its `data_fields` asserted. This is arm B's own
  hazard rather than the redundancy claim's: joining the producer's three-column projection into
  `data_channel` would duplicate each field row per family and double the count, which the audit
  reproduced on a fixture, so a projection that is not narrowed to the two columns the condition tests
  fails here rather than reaching a consumer. The relation deliberately reports a row per family in its
  outer join, so this seeding also pins that the narrowing did not cost that multiplicity where it is
  wanted.

Pinning where no row appears is pinning the boundary rather than reporting a gap.

The anchor carries one more load under arm B, which is worth saying where the anchor is specified
rather than only where the arm is chosen. Arm B leaves the condition stated in two places, and the
argument for removing a duplicate is that two spellings agree until one of them changes. The anchor is
what makes that drift fail a build: it pins the view's rows over a store seeded to make the two
conditions disagree if they ever do, so the residual duplication has an enforcer under it where today
it has a proof by reading.

Its scope needs stating beside it: this closes the row-identity half only. The read-cost gate asserts
no duration anywhere, so the cost half stays unenforced whichever spelling ships, and the anchor must
not be read as covering it.

**The rest of this section covers the registration this item does not make**, and is kept because the
follow-up item inherits it.

No new behavioural test would have been needed for a registration: a registration changes no answer,
and the claim that the target holds its view's rows is what the capture agreement machinery already
asserts once the `_live` view is registered with it. One new structural test does land with it, the
edge-presence assertion argued in the "Nothing orders the refresh by hand" paragraph above, because the
ordering that keeps the agreement true has to rest on a derived edge rather than on the alphabetical
accident that currently orders these two relations correctly.

* `FactCaptureAgreementTest`: one line,
  `registrations.put("intent_field_payload_producer_live", Arm.DERIVED);`, beside the existing row
  for the canonical name. A registered materialization is two relations under one rule and both are
  derived, which is what that list encodes. This test fails a full build and not a scoped one, so
  this item needs a verification build rather than a `-pl` run.
* `MaterializeRegistryGateTest`: one new test asserting the dependency row
  `('intent_carrier_data_field_live', 'intent_field_payload_producer_live')` is present on the
  booted store, per the section above. If the index question lands on the roster, also one
  `NO_INDEX` entry plus its argument in the set's javadoc, naming the shapes timed and what the
  `DISTINCT` baseline showed. Its nine existing tests check the pair and need nothing from the author.
* `MaterializationOrderTest`: one new synthetic case covering a source view that reads its
  prerequisite from inside a `WITH` body, per the section above. It needs no production relation
  names and covers every registration after this one.
* `DerivedReadCostTest`: one pinned count moves, in one direction, and one pinned set may.
  `CELLS` rises, by the number of views that reach the producer through unregistered paths, and
  nothing falls: `registrationsReachedByView` stops the walk at a registered target, but this
  relation's five inputs are all captured base tables, so its subtree contains no registration and no
  reader loses a cell for the walk stopping earlier.
  `READERS_IN_SCHEMA` does not move at all. There is no new view: the change renames one view and
  adds one table, and the census keys on relations whose `INFORMATION_SCHEMA` kind is `VIEW`, so it
  loses `intent_field_payload_producer` and gains `intent_field_payload_producer_live` at unchanged
  cardinality. `READERS_WITH_CELLS` does not move either: both readers of this relation already reach
  `intent_errors_field` and so already have cells, and the new `_live` view reaches no registration,
  so no view gains its first cell or loses its last.
  Re-pin `CELLS` from the failure message rather than from any figure this file states. The
  instruction is safe whichever way the counts move, and the point of predicting only the one is that
  a `READERS_IN_SCHEMA` that did rise would mean this change added a view it did not intend to, which
  is a signal to keep rather than a movement to absorb.
  `KNOWN_NON_MONOTONIC` may gain a row. The declared key does not rule that out, for the reason given
  where the key is declared: it does not serve the one known probe. A new row there is a finding to
  argue in that set's javadoc after measuring the index shape, never a tolerance to add quietly.
* The inline-multiplicity report (`roadmap-tool report-inline-multiplicity`) reports rather than
  gates, and a registered relation leaves its ranking by construction, so nothing there needs
  re-pinning.

## Verification

`mvn install -Plocal-db` from the repo root, on the exact tree that gets pushed. The new seeded anchor
is a `graphitron-model` test and the carrier's existing behavioural anchors need a captured store, so a
scoped `-pl` run is not verification for this item. The agreement test and the read-cost gate matter to
the registration follow-up rather than to this change, which registers nothing.

Nothing else is a precondition, and in particular no measurement is. The three `reason` edits state
this item's own figures with the schema and the date they were taken on, per the section above, so
transcribing them is the deliverable rather than a shortcut past one. What must not happen is stating
any of them as a current or a post-rewrite reading: they were taken on 2026-08-26 and 2026-08-27, on a
consumer store this repository does not contain, on a tree whose register has grown since, and the
register growing is exactly what would move them.

Where a consumer-scale captured store is reachable, and only there, one further measurement is worth
taking and is confirmation rather than a gate: the carrier's refresh before and after the edit, by the
procedure in the `store-performance` skill, a store a real build already wrote rather than a fixture,
single-file JDBC programs over the pinned H2 version, `OPTIMIZE_REUSE_RESULTS` off, the real refresh
statements, two sweeps. Read `roadmap/audits/2026-08-27-carrier-filter-redundancy-probe.md` first: a
synthetic fixture does not reproduce the per-driving-row re-derivation, so a figure taken on one would
be worse than no figure.

## What would falsify this plan

The three that are about the change this item ships:

* **The seeded anchor showing the predicate removes a row, or moves a `data_fields` count.** That
  falsifies the item rather than an arm: the redundancy proof is what says the filter and the outer join
  test one condition, and if the anchor disagrees with it then arm A and arm B both change answers and
  neither may land. Stop at the anchor, not at the build. This is the falsifier the anchor exists to
  make available, and it is checkable here, on a seeded store, with no consumer schema in sight.
* **Arm B's respelling not planning as one inlining per naming.** The bound this item chose the arm on
  rests on H2 inlining an uncorrelated `WITH` naming once, which is the claim the DDL's own comment
  above `data_channel` states and the claim the diagnosis already rests on; if a consumer-store
  confirmation shows the carrier unchanged, the engine is nesting the inlined term inside the join
  instead. That does not make arm B dearer than today, which is what the bound guarantees, but it does
  mean the item bought nothing, and the next move is the one the register's doctrine points at rather
  than a second guess at a spelling.
* **The carrier staying expensive on a consumer store after the rewrite.** That means the re-derivation
  was not the whole of the 41 s, and this item's control table is what would be wrong. It is not a
  failure of the edit, which removes what it says it removes; it is R861's trigger, and R861 already
  says the term has to be re-bisected before a registration is proposed rather than assuming the
  producer is still the subject.

The four below are inherited by R861 along with the registration material, and none of them is a
condition on this item, which registers nothing:

* A rewrite arm measuring as cheap as the registration. That does not falsify the diagnosis, it
  falsifies the registration: the middle rung's trade is a refresh against the re-evaluations it
  avoids, and a lever that removes the same re-evaluations for one edit to one view body wins that
  trade at zero refresh. This item is that lever, so for R861 the bullet reads as the question it opens
  with.
* The refresh costing materially more than the 4 to 31 ms measured. The whole trade is one refresh
  against the re-evaluations it avoids, and a dearer refresh is a different trade. Re-price before
  landing rather than after.
* `KNOWN_NON_MONOTONIC` gaining more than a row or two. At that point the answer is the index on the
  target, not a set of roster rows, and the index has to be measured rather than reasoned about.
* The edge-presence assertion failing on the built store. That means the dependency walk does not
  see the reference shape this plan says it sees, and the fix goes into the walk; the registration
  must not land on a hand-authored ordering workaround.

The bullet that stood here about the carrier refresh "not landing near 0.3 s after the registration" is
gone rather than inherited. It reinstated a figure "What changes when this lands" retracts by name, that
figure being one candidate lever's measurement read as the item's outcome, and the honest form of what
it was reaching for is the third bullet above.

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

*Author's note (round 5 revision).* Correctness half taken and closed: the redundancy is stated in the
plan body's new "The filter is redundant" section, and confirmed by execution as well as by reading in
`roadmap/audits/2026-08-27-carrier-filter-redundancy-probe.md`. Cost half not taken and now explicitly
outstanding: the body names the three arms, the doctrine that orders them, and the one round of timings
that decides, and states that those timings cannot be taken in the reactor. The audit records why,
including a synthetic instrument that failed to reproduce the re-derivation and must not be retried.
The `reason` requirement stands and is written into the lever section for whichever arm ships.

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

*Author's note (round 5 revision).* Taken as stated. The synthetic CTE-body case lands in
`MaterializationOrderTest` where shapes are covered, and the production pin stays beside it with the
regression-guard claim rather than the mechanism-establishing one. Both are written into "Nothing
orders the refresh by hand" and the tests list.

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

*Author's note (round 5 revision).* Taken. The section is now "Two reason rows are completed and one
is corrected", and it says which is which and why the distinction is worth keeping. The edits
themselves are unchanged, both figures with both schemas in each row.

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

*Author's note (round 5 revision).* Taken, together with finding 10. The tests list now predicts one
moving count, `CELLS`, upward only, and says why nothing falls.

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

*Author's note (round 5 revision).* Taken. `READERS_IN_SCHEMA` and `READERS_WITH_CELLS` are now
stated as not moving, with the mechanism for each, and the reason for predicting only `CELLS` is
kept as you and finding 9 give it.

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

*Author's note (round 5 revision).* Taken. The lever section now carries the doctrine and both of its
discharges, and states the order as the register's rather than as this item's preference. The finding
understated itself, and a later architecture read found the stronger form now in the body: `meta_materialize.reason`'s column comment says a row that
cannot say which of the two claims it makes is not a registration, and a registration's row here
cannot say either, so this is not a completeness gap in a `reason` but a registration that is
inadmissible as a first move. The register is consequently not edited by this item at all, and the
registration question is filed separately.

A correction to an earlier version of this note, which claimed the ladder-versus-doctrine
reconciliation was in the body when it was only in the note: it is in the body now, in "Registering the
relation is not a third arm". The claim was wrong when written, and a note asserting a body edit that
does not exist is exactly the hazard the workflow names about reviewer-authored prose reaching the next
reviewer labelled settled.

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

*Author's note (round 5 revision).* Taken. Arm B is named as an arm with its projection hazard stated,
and the hazard is reproduced rather than reasoned about: joining the three-column producer CTE
directly returned exactly twice the correlated spelling's rows on the fixture in
`roadmap/audits/2026-08-27-carrier-filter-redundancy-probe.md`. It is in the priced set, not
recommended. The audit also records that arm B's payoff, unlike its correctness, is not measurable on
a synthetic fixture.

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

### Round 6 (2026-08-27, Spec -> Ready, reviewer session 01V5QbYrN6uc9qHQeuwRZmma)

Verdict: withhold, on question 1 and question 2. Rounds 3 to 5 are addressed and the plan is a
different and better item than the one they reviewed. What withholds it is not a new objection to the
design; it is that the revision that narrowed the item left three sections speaking for the wider one,
and that the arm the implementer must ship is chosen by a measurement the plan itself says cannot be
taken here.

What is settled, checked against the tree rather than against the revision commits. The split is the
right call and the argument it rests on reads verbatim: `meta_materialize.reason`'s column comment does
say that a row which cannot claim either "no view could express this" or "a view expresses it correctly
and only too slowly" is not a registration, so a registration whose subject is a naming that need not
exist really is inadmissible as a first move rather than merely unattractive. The doctrine sentence is
in `intent_mutation_payload_key_membership_live`'s reason as quoted, and the two rows after it do
discharge it in obligation language ("The rewrite came first, as the row above this one says it must",
"So the rewrite was tried first, as the doctrine here says it must be"). The redundancy holds at the
source: the filter, the outer join, the `COUNT(*) OVER (PARTITION BY f.graph_name, f.type_name)` window
and the single naming of `data_channel` are all as the body describes, and the four-line comment above
`data_channel` states the inlining hazard from the tree's own side. The narrowing claim that makes arm A
a real cost risk is the page's, at `docs/architecture/explanation/fact-model.adoc:125`, quoted
accurately. Both page quotes about which relation to register check out. The three `reason` rows read on
the tree exactly as the plan quotes them, and only `intent_field_column_scope_live`'s reads as a general
claim, which is what the "completed and corrected" framing now says. R861 exists at the path given, and
the audit exists and is honest about its failed instrument. Every symbol named exists as named, by
FQN-aware grep: the nine `MaterializeRegistryGateTest` tests including
`targetsAreShapedLikeTheViewsThatFillThem` and `NO_INDEX`, `MaterializationOrderTest` with no
`WITH`-body fixture, `MaterializeDependencies.populate` / `relationsReadBy` /
`registrationsReachedByView`, `Materializations.refreshOrder` / `analyse`, `SchemaIdentifierDriftCheck`,
`FactCaptureAgreementTest`, `CarrierDataFieldTest`, `DerivedReadCostTest`'s four constants,
`ix_spelled_table_spelling`, `report-inline-multiplicity`, and the register at twenty rows. The seeded
anchor's habitat is real and the pattern supports what the plan asks of it:
`graphitron-model/src/test/java/no/sikt/graphitron/model/intent/` holds `ProducerCardinalityTest` and
its siblings over `SeededStore.withSeededStore`, and no carrier anchor exists there yet, so "a new
seeded anchor" is the right shape. That anchor is the best new material in this revision, and nothing
below touches it.

Question 1, in my own words and not from the phase list: after this lands the carrier view names its
producer once instead of once per driving row, so a consumer whose schema declares mutation payloads
stops paying the dominant term of a per-capture cost it pays today, gets the same rows back under the
same names, and gets an invariant that fails if the predicate being removed ever did remove a row. That
sentence is available from the body's first two sections and it is the right one. It stops being the
only available answer three sections later, which is finding 13.

**13. The item's scope is stated two ways, and the second one is a live conditional.** "What changes
when this lands" says "The register gains no row", "Registering the relation is not a third arm" argues
that a registration is inadmissible as a first move, and question 2 of the sequenced pair files it as
R861. Then "Implementation of the registration arm" opens with "Everything from here to 'Tests and
gates' specifies arm C and applies only if the timings pick it", and "The index question is the
registration arm's own" opens with "This section applies only if arm C ships". Those are not
inheritance notes for the follow-up item; they are conditions this item's own timings could satisfy, on
an arm the body two sections earlier removed from the set the timings choose from. So an implementer
reading top-down gets two answers to the first question they have, and the two deliverables are not
close: one edit to one view body against a table, a view rename, seven relocated comments, a register
row, two structural tests and a re-pinned constant.

The Tests and gates section already shows the framing that works, in "**The rest of this section covers
the registration this item does not make**, and is kept because the follow-up item inherits it". What
would satisfy this finding is the same sentence at the head of the other two sections, with the
conditional gone. Retaining the material is right and I am not asking for it to be deleted; R861 says it
inherits it, and a reader of R861 will want it. It just has to be inherited rather than optional.

*Author's note (round 7 revision).* Taken as stated, with round 7's sharpening. The label "arm C" is
gone from the body, no section gates on a condition any more, and both retained sections now open with
the Tests and gates framing: "Implementation of the registration this item does not make" and "The index
question the registration would inherit" each say in their first sentence that the material is R861's
inheritance and that nothing in them is conditional, and each names R861 by id and path. Nothing was
deleted. The short section that stated the trade was retitled from "The registration this item does not
make" to "The trade a registration would have to win", because the new head sentence on the section
after it would otherwise have given two adjacent sections near-identical titles, and it carries the same
inheritance sentence in one line.

**14. The arm the implementer ships is chosen by a measurement the plan says cannot be taken here, and
no fallback replaced the one the revision dropped.** "What decides between A and B, and it is
outstanding" requires both arms timed on the consumer store, and then states that this cannot be taken
in the reactor. The previous revision closed that paragraph with "So the figures come from the consumer
store or they do not come"; `c4ec2f5` dropped the clause and nothing took its place. The falsifier
section hardens it into a precondition: "This is the fork, and it is the one thing that has to be
measured before anything here is built." Read together, the item cannot be taken In Progress in this
repository at all, and Ready means an implementer can pick it up.

This is the question 2 failure, and it is the one that decides the round: handed this plan as-is, an
implementer either stops at the fork or picks an arm on their own reading, and which arm ships is
exactly what this gate exists to see settled before the work starts.

The plan already holds what settles it, which is why this is cheap. The page rule at
`fact-model.adoc:125` predicts that arm A widens the population the two `CASE WHEN EXISTS` probes and
the window run over, the DDL comment above `data_channel` says the same from the tree's side, and round
5's finding 12 observed that arm B is the arm that survives whichever way A's population question goes.
The body itself says the rule "already leans towards B". What would satisfy this finding is a decision
procedure that terminates in this reactor: name the arm that ships and give the timing its actual role,
confirmation where a consumer store is reachable and a recorded absence where it is not, or state
plainly what an implementer does when the figures cannot be taken. Which of those, and which arm, is the
author's call; what may not stand is a fork whose only stated resolution is unavailable.

*Author's note (round 7 revision).* Decided, and the paragraph is now "Which arm ships: B, and it is
decided by a bound rather than by a ranking". Arm B ships. What replaced the missing timing is not a
cheaper timing but a different kind of argument, and the body states it as three points: arm B's worst
case is bounded by reading, since every population inside the view body is unchanged and the only added
work is one further inlining of a 4-to-31 ms rule, so arm B cannot be dearer than today by more than
that; arm A's worst case is not bounded here, the population change being exactly what no store in this
repository can price; and the one engine claim arm B needs, that an uncorrelated `WITH` naming is
inlined once per naming, is the same claim the diagnosis and the DDL's own comment above `data_channel`
already rest on rather than a new one. The timing is demoted to confirmation with both branches stated:
taken and recorded where a consumer store is reachable, recorded as absent where it is not, the recorded
absence following `intent_node_id_instruction_live`'s reason, which already states figures no reader of
the file can re-take as provenance. The body also now specifies the edit itself, one further `WITH` term
projecting `producer` to the two columns the condition tests plus the join that replaces the correlated
`EXISTS`, so neither the arm nor its spelling is left to the implementer. The first falsifier bullet
that made the timing a precondition on building anything is gone, per finding 15.

**15. "What would falsify this plan" describes only the change this item does not make, and reinstates
the number the opening retracts.** All five bullets are registration-conditional. The third makes "The
carrier refresh not landing near 0.3 s on the consumer store after the registration" a stop-and-rebisect
condition, while "What changes when this lands" retracts that figure by name as "one candidate lever's
measurement read as the item's outcome". The second prices a refresh this item does not install, the
fourth is about a roster set no rewrite touches, and the fifth is about an edge assertion the tests
section files under work this item does not do. The first, as finding 14 says, states a precondition the
plan cannot meet.

So the section that says when to stop says nothing about what ships. That is a question 1 finding
because a falsifier list is how a reader learns what the author thinks could still be wrong, and on the
rewrite the honest ones are available and interesting: the seeded anchor showing that the filter does
remove a row or does move a `data_fields` count, which falsifies the item rather than an arm; arm B's
respelling not planning as one evaluation, which is a claim about the engine the plan elsewhere insists
on measuring; and the carrier staying expensive after the rewrite, which is R861's trigger rather than
this item's failure and is worth saying so rather than leaving to inference. Keep the registration
bullets if they are inherited, marked as inherited.

*Author's note (round 7 revision).* Taken, and the three candidates you name are the three the section
now opens with: the seeded anchor showing the predicate does remove a row or does move a `data_fields`
count, which falsifies the item rather than an arm; arm B not planning as one inlining per naming, with
the note that the bound still holds so the failure is "bought nothing" rather than "made it worse"; and
the carrier staying expensive on a consumer store, stated as R861's trigger rather than as this item's
failure. The four registration bullets are kept below them under a sentence marking them as R861's
inheritance. The 0.3 s bullet is not among them: it is deleted, with one sentence saying why, since
inheriting a bullet that reinstates a retracted figure would carry the same defect into R861.

**16. The CTE-body shape case is assigned by each of the two items to the other.** This item's tests
list places `MaterializationOrderTest`'s new synthetic case inside "the registration this item does not
make ... kept because the follow-up item inherits it". R861 says the opposite: "`MaterializationOrderTest`
covers no shape where a source view reads a relation from inside a `WITH` body, which is how the carrier
reaches this relation; the sibling item adds that synthetic case, so this one inherits it rather than
needing it." Confirmed that the hole is real, no fixture in that class reading a relation from inside a
`WITH` body. This is round 2's finding 4 in mirror form: a hand-off whose destination hands it back, so
the work lands nowhere.

My reading is that R861's sentence is the wrong one, the shape mattering only where a registration's
refresh edge has to be derived, and one line in R861 closes it; any session may land that, so this is
the smallest of the four. It is a finding rather than a note because this item's own text is one half of
the contradiction and a returning reader of either file cannot tell which half is stale.

*Author's note (round 7 revision).* Taken, and I agree with your reading: R861's sentence is the stale
half. The synthetic `WITH`-body case matters only where a registration's refresh edge has to be derived
through that shape, which is R861's change and not this one, so this item's tests list keeps the case
inside the material R861 inherits, and R861's sentence now says it adds the case rather than inheriting
it. One line, landed in
`roadmap/producer-registration-after-duplication-removal.md` in this revision's commit.

**What would satisfy this round.** Findings 13 and 15 are editorial in effort and about scope in
substance: say once, at the head of each retained section, that the registration material is R861's
inheritance rather than a conditional arm, and give the falsifier list at least one entry about the
change that ships. Finding 14 is the one that needs a decision: state how the A / B choice is made when
the deciding figures cannot be taken here, or name the arm and demote the timing to confirmation.
Finding 16 is one line, in whichever of the two files the author judges stale. Nothing here asks for new
scope, and nothing here disputes the split, the diagnosis, the seeded anchor or the three `reason`
edits, all of which I checked and none of which I would change.

Non-blocking. `DerivedReadCostTest` is now at `READERS_IN_SCHEMA` 111 and `CELLS` 178, having moved
again since round 5; the plan names neither figure any more, which is why it did not go stale this time,
and that is the pattern to keep. Corrected in passing, in the same commit as these findings: round 5's
author note under finding 11 carried a stray "One" left mid-sentence by an edit, which is now joined up.

### Round 7 (2026-08-27, Spec -> Ready, reviewer session 01LZ7eesfmnkBdKZpmvJsAvd)

Verdict: withhold, on question 1 and question 2. The plan body is unchanged since `c4ec2f5`; the only
commit on this file since is round 6's own findings, so findings 13 to 16 have had no revision and all
four stand. This round does not restate them. It re-derives each from the tree, sharpens 13, and adds
two that no earlier round reached, one of which changes what would satisfy the item.

What I checked and would not change. The redundancy is exactly as the body describes, read at the
source: the `data_channel` filter is `EXISTS (SELECT 1 FROM producer p WHERE p.graph_name =
f.graph_name AND p.payload_type_name = f.type_name)`, the outer query is `FROM producer p JOIN
data_channel d ON d.graph_name = p.graph_name AND d.type_name = p.payload_type_name` with `d.graph_name`
and `d.type_name` being `f.graph_name` and `f.type_name` projected, the window is `COUNT(*) OVER
(PARTITION BY f.graph_name, f.type_name)` so the filter is constant per partition, and `data_channel` is
named once. The four-line comment above `data_channel` does state the inlining hazard from the tree's
own side. The producer view's five inputs really are captured base tables and its declared column list
is the six columns in the order the proposed table gives them. The three `reason` strings read verbatim
as quoted, and only `intent_field_column_scope_live`'s "about 170 ms on a real schema" reads as a
general claim. `fact-model.adoc` carries the windowed-derivation pruning rule, the "not the relation
that looked slow" sentence and the "a filter one caller applies" taxonomy line as cited. The register is
at twenty rows; `MaterializeRegistryGateTest` has nine tests including
`targetsAreShapedLikeTheViewsThatFillThem` and `NO_INDEX`; `MaterializationOrderTest` fixtures are
`SELECT v FROM scratch_p` / `scratch_mid` / `scratch_src` / `scratch_x` / `scratch_y` and no fixture
reads a relation from inside a `WITH` body; `DerivedReadCostTest` is at `READERS_IN_SCHEMA` 111,
`READERS_WITH_CELLS` 67, `CELLS` 178, none of which the plan names. Every other symbol exists as named,
by FQN-aware grep: `SchemaIdentifierDriftCheck`, `FactCaptureAgreementTest` with `Arm.DERIVED`,
`CarrierDataFieldTest`, `MaterializeDependencies.populate`, `relationsReadBy`,
`registrationsReachedByView`, `Materializations.refreshOrder` / `analyse`, `ix_spelled_table_spelling`,
`report-inline-multiplicity`, and `SeededStore.withSeededStore` in the anchor habitat, which holds
`ProducerCardinalityTest` and `MutationPayloadColumnTest` and carries no carrier anchor yet. R861 and
the audit exist at the paths given, and the audit is honest about what it could not establish. The
seeded anchor is the right piece of new work and nothing below touches it.

**13 confirmed, and it is sharper than round 6 stated: the label the two conditional sections gate on
is not defined in this file at all.** "arm C" appears three times in the body, at the head of
"Implementation of the registration arm", in its second paragraph, and at the head of "The index
question is the registration arm's own". The arm list in "The condition is stated twice" names two
spellings, A and B, and the section beneath it argues that a registration is not a third arm. So C is
defined only in `roadmap/audits/2026-08-27-carrier-filter-redundancy-probe.md`, which is not the plan.
An implementer reading this file top-down meets two sections whose applicability condition names
something the file never introduced, and whose condition ("if the timings pick it") is on an arm the
body two sections earlier removed from the set the timings choose from. Round 6's remedy is the right
one and this only makes it cheaper to see why it is needed.

**14 confirmed at the source, and the audit is the strongest evidence for it.** "What decides between A
and B" requires both arms timed on the consumer store and then says the timing cannot be taken in the
reactor; the first falsifier bullet makes that timing a precondition on building anything. The audit
agrees in its own words, "the cost half is open and needs a store this repository does not contain",
and records the only store a build here writes as three graphs, 63 `graphql_field` rows, one
`intent_field_payload_producer` row and zero `intent_carrier_data_field` rows, plus a synthetic
instrument that failed and must not be retried. Nothing in the repository supplies what the fork needs.

**15 and 16 confirmed.** All five falsifier bullets are registration-conditional and the third
reinstates the 0.3 s figure that "What changes when this lands" retracts by name. And the
`MaterializationOrderTest` `WITH`-body case is assigned by this item's tests list to "the registration
this item does not make ... kept because the follow-up item inherits it", while R861 reads "the sibling
item adds that synthetic case, so this one inherits it rather than needing it". Both sentences read
verbatim on the tree, and the hole they are about is real.

**17. The half of the item that does not depend on the fork is also not startable here, so settling the
fork alone would not make this Ready.** "Two reason rows are completed and one is corrected" opens with
"These three edits depend on no part of the lever question. Their figures are in hand and the rows they
correct are wrong now", which reads as the unconditional half of the item. The Verification section
then withdraws exactly that: "re-take the figures the edited `reason` rows will state (the carrier's
move and the two sibling refresh durations)", by the `store-performance` procedure "against a store a
real build had already written rather than a fixture", closing with "Do not transcribe this item's
numbers into the DDL." All three figures are consumer-schema figures (41 s, 6.4 s, 4.2 s at 8408
fields), and the carrier's is a post-rewrite figure besides. On the store this repository can write they
would all be near zero and none of them would be the figure the row needs, which is the whole reason the
rows are being corrected.

So of the three deliverables "What changes when this lands" names, the view-body edit is blocked on
finding 14 and the three `reason` edits are blocked on the same unavailable store, and the seeded anchor
is the only one an implementer here can produce. That is a question 2 failure and not a restatement of
14, because it changes the remedy: naming the arm, which is what round 6 asks for, leaves two thirds of
the item still unstartable. What would satisfy it is the same decision applied to the figures. Either
say that the `reason` edits state this item's figures with their provenance and the date they were
taken, which is what the carrier's existing row already does for two schemas and what
`store-performance` calls evidence about the schema it was measured on, or say plainly that this item
completes outside the reactor and what an implementer without a consumer store does with it. What may
not stand is an item whose every DDL-touching deliverable waits on a measurement the item itself
records as unavailable here.

*Author's note (round 7 revision).* Taken, and decided the first of the two ways you name. The three
`reason` rows state this item's own measurements with the schema and the date they were taken on, and
nothing is re-taken to land them: that is now a paragraph of its own at the head of "Two reason rows are
completed and one is corrected", citing the precedent in the register itself, where
`intent_node_id_instruction_live`'s reason states a consumer schema's figures and says outright that
they are provenance because no reader of the file can re-take them. The carrier's row is the one that
needed the extra sentence, its new figure being the pre-rewrite 41 s, so the row states it as measured
before this rewrite, on that schema, on that date, beside what the rewrite removes by construction;
whether a post-rewrite figure joins it is a matter of reach, both branches are stated, and the absent
branch is a recorded absence rather than a hole. The Verification section is the half that was
withdrawing the claim, and it is rewritten: no measurement is a precondition, transcribing the dated
figures is the deliverable rather than a shortcut past one, what may not happen is stating any of them
as a current or post-rewrite reading, and the consumer-store timing is named as confirmation available
only where such a store is reachable. So all three deliverables the opening names are producible here.

**18. The one-sentence answer to "what changes for a consumer" is true of the arm the plan leans away
from.** "What changes when this lands" says the carrier "stops re-deriving its producer once per driving
row, because the condition that made it do so is stated once instead of twice", and the title says the
same. Under arm A that is exact. Under arm B it is not: respelling the filter as a join into
`data_channel` leaves the condition stated in two places, the new join inside the CTE and the outer join
that already tested it, and what goes is the correlated evaluation rather than the duplication. Arm B
also names the producer twice in the body rather than once, the narrow two-column projection being a
second naming of either the relation or the `producer` CTE, which is still constant work rather than
per-driving-row work but is not "named once in a plain `FROM`" either. The body's own reading is that
the page rule "already leans towards B", so the sentence a reader gets for the item's outcome describes
the spelling the plan expects not to ship.

The consumer-facing outcome is arm-independent and is available in one sentence: the carrier evaluates
its producer rule a fixed number of times per refresh instead of once per driving row, and returns the
same rows under the same names. This is a question 1 finding rather than phrasing because that section
is the answer to question 1, and because the arm-specific causal clause is what makes the section read
as though the fork were already settled towards A when the plan's own argument leans the other way.

*Author's note (round 7 revision).* Taken, and your sentence is the one the section now leads with: the
carrier evaluates its producer rule a fixed number of times per refresh instead of once per driving row,
and returns the same rows under the same names. A short paragraph beneath it says why the sentence is
about evaluations rather than about how many times the condition is written down, names the retracted
earlier wording, and says that arm B leaves the condition stated in two places. Arm B's second naming of
the producer is stated where the arm is listed, and the residual duplication has a paragraph of its own
under "Which arm ships", including what carries the drift argument once the duplicate stays: the seeded
anchor, which is where a divergence between the two statements now fails a build. The title is left
alone. It names the defect being paid for today, which is true whichever arm removes it, and retitling
would make the item id in seven rounds of reviewer prose harder to follow rather than easier.

**What would satisfy this round.** Round 6's four, unchanged, plus these two. Finding 17 is the one that
needs a decision beyond round 6's: say how the three `reason` figures are obtained, or state what an
implementer without a consumer store delivers. Finding 18 is one sentence, once the arm question in 14
is answered, or one arm-independent sentence if it is not. Nothing here asks for new scope, and nothing
here disputes the split, the diagnosis, the redundancy proof, the seeded anchor or the three `reason`
edits themselves, all of which I checked and would keep.

Non-blocking. The "`MaterializeRegistryGateTest` carries nine tests, not the five the plan enumerates"
note carried by rounds 4, 5 and 6 is itself stale: the tests list already reads "Its nine existing tests
check the pair", so there is nothing left to correct there. Nothing else in the body went stale this
round, and no in-passing corrections were needed.

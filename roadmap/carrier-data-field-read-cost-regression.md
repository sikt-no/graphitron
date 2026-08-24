---
id: R819
title: "The carrier data field read tripled and the seat put it on every generation"
status: In Review
bucket: store
priority: 2
theme: mutation-write
depends-on: [step-hop-registration-costs-two-readers]
created: 2026-08-24
last-updated: 2026-08-24
---

# The carrier data field read tripled and the seat put it on every generation

Reading `intent_carrier_data_field` against a real capture costs about half a minute, and the
R682 slice-one commits multiplied that cost onto the build's hot paths: the new
`intent_mutation_routine_seat` names it five times (once per carrier-facing verdict arm) and
costs about 43 seconds per read,
`intent_carrier_routine_hop` drives from it (about 10 seconds), and `RoutineWriteFacts`
reads the seat family once per generation (its three statements name the seat four times and
join the carrier relation twice more directly), so the sakila example's generation, and every
routine-carrying pipeline test now pay these reads. The relation answers 15 rows in every
measurement below; this is a cost regression, not an answer change.

## What was measured

The probe methodology is the one the node-id decode regression item established: the sakila
example's own schema captured against the sakila catalog (`CapturedStore.ofCatalog`), timing
one read per relation inside one capture. Same-fixture controls ran the view's old and new
body texts as raw queries against the same captured rows, with suspect children snapshotted
into plain tables where named.

Per-relation, current trunk (`4b9ddcea9`):

| relation | rows | ms |
|---|---|---|
| `intent_carrier_data_field` | 15 | 48569 |
| `intent_mutation_routine_seat` | 5 | 43311 |
| `intent_carrier_routine_hop` | 2 | 10291 |
| `intent_field_error_channel` | 19 | 5299 |
| `intent_field_chain_start` / `_node` / `_terminus` | 18 / 24 / 18 | 1 / 14 / 11 |
| `intent_poly_member`, `intent_field_payload_producer`, `intent_errors_field`, `intent_errors_field_member` (each in isolation) | 50 / 129 / 15 / 27 | 10 / 16 / 16 / 45 |

Same-fixture controls on `intent_carrier_data_field`'s body (one store, one run, warm):

| body variant | ms |
|---|---|
| new body as shipped | 31798 |
| old body, from before `a5f5ad1a3` | 14922 |
| new body with `intent_field_payload_producer` snapshotted to a table | 33767 |
| new body with `intent_errors_field` snapshotted to a table | 9587 |
| new body with both snapshotted | 8508 |

## What the controls say

- Every child of the expensive relations is cheap in isolation (10 to 45 ms), so the cost is
  re-evaluation, not an expensive term: the shape the fact-model page names as view inlining
  with no common-subexpression elimination.
- `a5f5ad1a3` ("the error channel becomes facts") roughly doubled to tripled the read
  (15 s to 32-48 s). The convicted term is the promoted `intent_errors_field`, which now
  probes `intent_poly_member` per driving field row; that view carries a `ROW_NUMBER() OVER`
  on its interface arm, so no outer predicate prunes it and each correlated probe evaluates it
  whole. The producer promotion is exonerated by its control (33.8 s, within noise of as-is).
- The pre-existing 15 s belongs to the body's own tail: the windowed `data_channel` CTE is
  named four times (one join, three correlated `NOT EXISTS`), and each naming re-evaluates it
  over every object-type field. That shape predates R682 slice one; what slice one added is
  the readers that made it hot (`1f260e67f` seat, `4b9ddcea9` generation-path read).
- Snapshotting `intent_errors_field` alone brings the new body under the old one
  (9.6 s vs 14.9 s), so the promotion done as a materialized relation is an improvement
  rather than a rollback candidate.

## What was not established

`intent_node_id_decode` measured 24.4 s here where the decode regression item recorded about
13 s on `37c5814`. Different session, so per the store-performance discipline the pair is
suggestive, not evidence; whether something after `37c5814` regressed the decode read is open.

## The plan

Vocabulary first, because the whole item moves along one axis. A *registration* is a row in
`meta_materialize`: the rule keeps its text in a view renamed to `<name>_live`, a table of the
same shape takes the canonical name every reader already spells, and the materializer refills
that table from the view once per graph inside each capture transaction. No reader is edited
and no answer changes; what changes is that a relation named N times per read is evaluated
once per capture instead of N times per read. The doctrine that admits a registration, the
depth rule, and the shared-investment gate are in
`docs/architecture/explanation/fact-model.adoc`; the mechanics are `Materializations` and the
registry gates in `graphitron-model`.

This item depends on the target-index item (`depends-on` above), and the ordering is
load-bearing rather than administrative: that item indexes the existing registered targets,
adds `ANALYZE` after every refill, and gates that each target carries an index or a measured
roster row arguing why not. Registering new targets before it lands would create exactly the
unkeyed heaps it identified as the source of every non-monotonic pair in the read-cost gate,
manufacturing pinned findings it would immediately un-pin.

Three slices, each ending in a re-measurement with the probe methodology above, so the next
slice decides on fresh numbers rather than on this item's opening ones. Each conditional
branch states its control and the disposition each outcome produces.

### Slice 1: register `intent_errors_field`

The convicted term, and the control has already priced the win: the carrier body falls from
32 s to 9.6 s, before the effect propagates to the seat, hop and error-channel reads that
inline the carrier body. Refresh is one 16 ms evaluation per graph. The case is view-body
multiplicity alone, and it is sufficient: four namings across three view bodies
(`intent_carrier_data_field`, `intent_errors_field_member`, `intent_field_error_channel`
twice) multiply it today. No Java reader names this relation, so the registration makes no
consumer claim; the consumer-facing wins are inherited by the relations above it.

### Slice 2 (conditional): `intent_poly_member`

The window-carrying term the errors view multiplies through: its interface arm carries a
`ROW_NUMBER() OVER`, so no outer predicate prunes it and every correlated probe evaluates it
whole. But slice 1 moves those probes onto the refresh cadence, where they run once per
capture, so the conviction may not survive slice 1.

The control is the same-fixture shape this item already used: on the post-slice-1 tree,
snapshot `intent_poly_member` into a plain table and re-read every relation that names it
(`intent_errors_field_member` is its remaining per-read namer, plus slice 1's own refresh).
Dispositions:

- The snapshot moves nothing meaningful: decline, and record the numbers here. Declining is
  a real outcome; every registration costs a refresh per capture and a row of priced cells in
  `DerivedReadCostTest` forever, so one admitted on suspicion is doctrine debt.
- The snapshot convicts the term: before registering, weigh the reshape one rung up. The
  derived interface-arm ordinal is the entire reason the window exists, and the view's own
  comment says the ordinal matters to exactly one reader, the mapping-constant fingerprint.
  Moving the ordinal into a sibling relation leaves a prunable membership relation with no
  refresh to buy at all, which changes what the relation is rather than how it is named, the
  class of change the fact-model page records as the one that pays. Registration is the
  fallback if the reshape loses on measurement or on shape.

### Slice 3: register `intent_carrier_data_field`, and restructure its body in the same slice

After slices 1-2 the carrier body's own tail remains: the windowed `data_channel` CTE named
four times inside `intent_carrier_data_field` (8.5 s in the both-snapshotted control). The
recommendation is to register the relation, and the depth rule is satisfied rather than
excepted: after slice 1, this is the deepest relation whose materialization removes
re-evaluation for every expensive reader left, namely the seat's five namings, the hop's one,
`intent_field_error_channel`'s per-producer-row probe, and the two runtime readers
(`RoutineWriteFacts` joins the carrier relation twice per generation beside its four seat
namings; the LSP's `CarrierDataField` reads it per `$source` completion).

The registration also carries a consumer claim the multiplication argument does not need but
the LSP makes literal: the body is a windowed view, so `CarrierDataField.admitsSigil`'s
coordinate filter prunes nothing and one completion at a `$source` site pays the whole
derivation. Measure it the way the target-index item did: the coordinate-filtered read
against the whole-relation read, in scans, before and after registration. If they match
before and diverge after, the reason row states a person-waits-on claim.

What the registration installs is a refresh: one full evaluation of the body per graph per
capture, and the body drives from every OBJECT-type field before the producer join narrows
it, so a capture with no mutation-root routine and no carrier pays it in full and reads
nothing back. At 8.5 s that refresh is five to five hundred times every shipped
registration's, and no reason row could honestly absorb it as a disclosure. So the
restructure is not a deferred optimization; it is the only lever on the number the
registration creates, and it lands in the same slice:

- Drive the three `NOT EXISTS` disqualification arms off `graphql_field` (plus the errors
  exclusion) directly rather than off the windowed CTE, so the window is evaluated once for
  the join and the CTE's four-fold expansion goes.
- Price the refresh explicitly on two captures, the sakila example (carrier-bearing) and a
  carrier-free schema, and state both numbers in the reason row. The carrier-free number is
  the one every consumer with no routines pays.
- If the restructured refresh still lands far above the shipped registrations' range, the
  reason row discloses it on the hop-column registration's model (the registration made in
  the increment that adds the reader, with the cost stated), and the Spec review weighs it.

Decide on slice 3 only after re-measuring the seat, hop and generation-path reads on the
post-slice-1 tree: if slice 1 alone already takes the seat read to the floor the other
relations sit at, the registration case weakens and the re-measurement will say so.

## What one registration touches

The checklist, assembled from the two precedent commits (the chain-walk registration and the
target-index item):

- `graphitron-model.sql`: rename the view to `<name>_live` and give it the short
  points-at-the-table comment (the `intent_spelled_table_live` pattern); `CREATE TABLE` under
  the canonical name with a column list matching the view name-for-name in order (gated);
  move the full relation and column documentation onto the table; add the registry row whose
  `reason` states the measurements. Each new target arrives with the index-or-roster decision
  the target-index item's gate demands, measured the way that item measures: an index chosen
  over the readers' join coordinates, or a row in the pinned no-index roster that item's gate
  installs in `MaterializeRegistryGateTest`, whose measurements argue the absence. (The roster's
  constant name is that item's to mint; its spec commits to the roster, not to a name.)
- `FactCaptureAgreementTest`: one `<name>_live` row in the `DERIVED` arm per registration.
- `DerivedReadCostTest`: re-pin `READERS_WITH_CELLS` and `CELLS` (the reachability walk adds
  cells for every relation reaching a new target), and revisit `KNOWN_NON_MONOTONIC`; a new
  pair there is a finding to answer, not a tolerance to record.
- The registration census is counted in prose in two places
  (`docs/architecture/explanation/fact-model.adoc`, `DerivedReadCostTest`'s javadoc, both
  saying "seven"). Rather than bumping an unguarded inventory again, rewrite those sentences
  to cite the register without a number; `meta_materialize` is the live roster and its gate
  already closes it.
- The LSP ceiling that can move is `DiagnosticsStatementCountTest.DRAIN_SCAN_CEILING`: the
  diagnostics drain reads `intent_carrier_data_field` through `DiagnosticFacts.sigilSiteArm`.
  `SurfaceScanCountTest`'s ceilings sit over the inlay-hint surface and should not move; the
  completion path through `CarrierDataField.admitsSigil` has no scan ceiling at all, which is
  a fact about coverage this item states and does not fix.
- `meta_materialize_dependency` needs nothing: the edges are derived from the stored view
  definitions at boot, so slice 3's read of slice 1's target orders itself.

## The fixture gap

`DerivedReadCostTest`'s scaled fixture populates none of `intent_errors_field`,
`intent_carrier_data_field` or `intent_mutation_routine_seat`: it has no mutation-root
`@routine` and no `@error` union payload, so every new cell would price the instrument's
floor and the gate would pass while seeing nothing. The fixture may grow and may not shrink,
so this item grows it: a routine-carrier cluster (a mutation-root `@routine` field returning
a payload type wrapping one data field beside an errors channel, the channel a union whose
members all carry `@error`), scaled with the existing units so the new targets hold rows
proportional to schema size.

Two consequences of growing it in shape rather than only in scale:

- `UNITS = 12` is justified in the test's own javadoc by a scale-dependence boundary
  established over the node cluster, and that argument does not transfer to a fixture with a
  new cluster in it. Re-run the check at two sizes on the grown fixture and re-state the
  justification in the same terms, rather than only re-pinning the cell counts.
- The target-index item records that `intent_node_id_decode_hop_column` holds no rows on this
  fixture either, the same defect one relation over. The two fixture edits want to be one;
  coordinate with that item rather than growing the fixture twice.

## Non-goals

- The `intent_node_id_decode` observation above stays unestablished here. Re-run that probe
  once, same session as the slice-1 re-measurement, purely to get a same-fixture pair; if the
  regression is real it becomes its own item with its own controls, not a fourth slice. It
  cannot ride this one in any case: nothing reads that relation yet, so it carries no
  consumer claim for a registration to rest on.
- No tuning of generated SQL, and no JVM or build-clock work.

## Open questions for the Spec review

1. Slice 3 prices the refresh it installs and restructures the body to shrink it, but the
   spec names no acceptance threshold. Is "within the shipped registrations' range after the
   restructure" the bar, or does the reviewer accept a disclosed outlier on the hop-column
   registration's model if the restructure falls short?
2. If slice 2's control convicts `intent_poly_member`, the reshape (moving the derived
   interface-arm ordinal into a sibling relation, leaving a prunable membership relation)
   changes a relation's shape and touches its readers, where a registration touches none. Is
   that reshape in this item's scope, or does conviction spawn its own item and this one
   registers as the interim?
3. The fixture growth overlaps the target-index item's own fixture loose end. One combined
   edit in whichever item lands second, or does the reviewer want the fixture change isolated
   in one of them?

## Reviewer findings

### Round 1, Spec -> Ready

Sign-off. Both gate questions pass; this round records what was checked, one correction made under
the reviewer's stale-symbol license, and the answers to the three open questions the spec poses to
this review.

**Question 1, the goal.** In this reviewer's words: no answer and no generated output changes; the
item registers up to two expensive derived views (and restructures one body) so that relations H2
currently re-evaluates once per naming are evaluated once per capture, taking the carrier-family
reads from tens of seconds back toward the store's floor. The consumers who feel it are every
routine-carrying generation (`RoutineWriteFacts` reads the seat family once per generation), the
pipeline tests that pay those generations, the diagnostics drain, and the `$source` completion.
That is communicable without the phase list, and it is reachable: the registration mechanism
exists with seven shipped precedents, and slice 1's win is already priced by a same-fixture
control. The structural claims that generate the cost were all verified against trunk
(`e40993854`): the seat body names `intent_carrier_data_field` exactly five times, all on
carrier-facing verdict arms; `intent_carrier_routine_hop` drives from it; `intent_errors_field` is
named four times across the three view bodies the spec lists, probes `intent_poly_member` per
driving row, and has no main-source Java reader; `intent_poly_member`'s interface arm carries the
`ROW_NUMBER() OVER` and its comment does say the ordinal matters to exactly one reader; the
carrier body's `data_channel` CTE is named four times (one join, three `NOT EXISTS`) and drives
from every OBJECT-type field before the producer join narrows it; `RoutineWriteFacts` is three
statements naming the seat four times and joining the carrier relation twice. The fixture-gap
claim also holds: the `DerivedReadCostTest` fixture's one `@routine` sits on the Query root, so
the seat relation is empty, and `Film0`'s `@reference` fields disqualify it from the carrier view,
so all three named relations hold no rows there. The timing figures themselves are the authoring
session's measurements and were not re-run; the shapes that explain them are all in the tree.

**Question 2, the fit.** The plan extends the registration shape exactly as shipped: rename to
`_live`, table under the canonical name, registry row whose `reason` states measurements,
`FactCaptureAgreementTest` `_live` row (the canonical name keeps its own row, per the seven
precedents), `DerivedReadCostTest` re-pin, census prose rewritten to cite the register. The
depends-on ordering is argued rather than asserted, and the deference to that item's index gate
avoids duplicating a mechanism mid-flight. Slice dispositions include declining, which is the
doctrine's honest outcome. No parallel mechanism anywhere. Hand-to-implementer: yes.

**One correction, made in this commit.** The checklist named `MaterializeRegistryGateTest.NO_INDEX`,
a constant that exists nowhere: the depends-on item's spec commits to "a row in a pinned roster"
and mints no name for it. The sentence now points at the roster without pinning the constant, so
this spec cannot rot against a name its dependency never promised.

**Answers to the open questions.**

1. No fixed numeric bar. The doctrine's own gate is directional and carries no number, and a range
   test would be false precision. The bar is the slice's stated procedure honestly executed: the
   restructure lands first, both captures are priced, and the reason row must be able to state the
   carrier-free number as something a consumer with no routines pays for nothing. A disclosed
   outlier on the hop-column model is acceptable if the restructured carrier-free refresh is a
   number the reason row can carry with a straight face and `DerivedReadCostTest` holds; if it
   remains a magnitude above every shipped registration, declining and recording the numbers is
   the disposition, symmetric with slice 2's.

2. In scope, on conviction. The slice already carries the control, both dispositions, and the
   registration fallback; the reshape's stated blast radius is one derived ordinal with one reader
   plus the carried position column, and a separate item would duplicate this item's measurement
   context to buy no isolation. If implementation finds the radius larger than the comment claims,
   that is a real fork to bring back, not one to pre-arm a second item for.

3. One combined edit in whichever item lands second, the spec's own lean. The dependency is In
   Progress and will land first in the expected order, leaving this item to grow the fixture once
   with both clusters' needs; isolating the fixture edit in a third item would put a shared test
   asset behind a gate nobody asked for.

## Implementation notes

### Slice 1 landed as specced, and the index the checklist demanded was measured and declined

The registration is exactly the checklist's: the rule keeps its text in
`intent_errors_field_live`, the table takes the canonical name with the full documentation and a
"Materialized:" sentence, the registry row states the multiplicity and the measurements, and
`FactCaptureAgreementTest` carries the `_live` row. The census prose was rewritten to cite the
register without a number in five places rather than the two the spec counted: the two it named
(`docs/architecture/explanation/fact-model.adoc`, `DerivedReadCostTest`'s javadoc), plus
`Materializations.analyse`'s javadoc, `MaterializeRegistryGateTest`'s grain sentence and the
model DDL's header, all of which said "seven" too.

The index-or-roster decision went to the roster. On the grown read-cost fixture with statistics
current, an index on the coordinate the two probing readers join (graph, type, field) improves no
reader: the three cheap readers move within the instrument's noise, and the two dear ones get
worse, `intent_carrier_routine_hop` from 3876 scans to 8136 and `intent_mutation_routine_seat`
from 28857 to 33117, the planner preferring a seek into a fifteen-row relation over the plan it
picks unaided. The row is in the gate's no-index roster with those figures.

### The grown fixture surfaced two pairs on a shipped registration, and both were answered

The routine-carrier cluster (one mutation-root `@routine` field per unit returning a payload
wrapping one nullable data field beside a union-of-`@error` errors channel) populates all five
carrier-family relations proportionally to schema size. The domain re-pins: 83 views unchanged,
47 with cells (was 46, the member view's first cells), 107 cells (was 102, the five readers of
the new target).

Growing the fixture also made two pre-existing shapes visible on `intent_argmapping_pair`, whose
site-literal reader arms pruned the pair view's union to one arm apiece and now visit the whole
table per arm: `intent_argmapping_bound_parameter_type` reads 815 scans registered against 357
unregistered, `intent_argmapping_segment_binding` 433 against 391. Both were answered as the
gate's doctrine demands, index first: a site-leading shape and a coordinate shape were both
measured and the planner ignored both, every reader unmoved to the scan. The same holds for the
new registration's own pair (`intent_errors_field_member`, 916 against 713, the fused inlined
plan skipping rows a plain table join visits). All three are pinned in `KNOWN_NON_MONOTONIC` with
the mechanism stated; the wall clock either way is sub-millisecond against the seconds the
registrations buy.

### The fixture size was re-justified at three sizes, and the boundary moved

The old justification (same pinned set at four units and twelve) does not survive the grown
fixture: the set is empty at one unit, the floor trio plus the parameter-type pair at four units
and at eight, and the six pairs above at twelve, where two borderline cells' plans flip against
the statistics that size implies. Twelve is kept because it is the size that ships and the
fixture may not shrink; the test's javadoc records all three sizes so the next re-pin knows the
boundary has moved before.

### Slice 1 re-measured on the sakila example, per the probe methodology

Same capture shape as the opening table (`CapturedStore.ofCatalog`, the example's schema over the
sakila catalog), on the post-slice-1 tree, which also carries the target-index item's indexes:

| relation | rows | before, ms | after, ms |
|---|---|---|---|
| `intent_carrier_data_field` | 15 | 48569 | 6570 |
| `intent_mutation_routine_seat` | 5 | 43311 | 7840 |
| `intent_carrier_routine_hop` | 2 | 10291 | 1923 |
| `intent_field_error_channel` | 19 | 5299 | 615 |
| `intent_errors_field` (read) | 15 | 16 | 9 |
| `intent_errors_field_live` (the refresh) | 15 | - | 10 |

### Slice 2: declined, and the numbers are these

The conviction did not survive slice 1, as the spec anticipated it might not. On the post-slice-1
tree every remaining per-read namer of `intent_poly_member` reads at the store's floor, and the
spec's own control confirms there is nothing to move: with the relation snapshotted into a plain
table on the same capture, `intent_errors_field_member`'s body goes from 5 ms to 2 and the
errors-field rule (the refresh's one evaluation) from 18 ms to 8. Single-digit milliseconds on
both sides; a registration here would buy a refresh and a priced gate row forever to save less
than the instrument's jitter. Declined.

### The decode observation dissolves

The non-goal probe ran three times in this session's stores: `intent_node_id_decode` reads 43
rows in 8.5, 12.0 and 13.0 seconds across runs of the same capture shape. The spread says the
read's wall clock is noisy at this scale, and every reading sits at or under the roughly thirteen
seconds the decode regression item recorded on its own tree; the 24.4-second reading in this
item's opening measurements does not reproduce on a tree carrying the target indexes. No
same-fixture pair convicts anything and no item is filed.

### Slice 3: restructured and registered, and the restructure carried most of the win

The body restructure landed first, alone, with an answer-equality control on both fixtures: the
old body and the new one return identical rows on the sakila capture (15) and on the grown
read-cost fixture (12). Two changes, both inside the rule: the three `NOT EXISTS`
disqualification arms stand on `graphql_field` and `graphql_field_directive` directly instead of
on the windowed CTE (so the CTE is named once, by the join, and the four-fold expansion goes),
and the CTE itself narrows to producer payload types before classifying (the window partitions by
type, and the filter drops whole types, so the counts within kept types are untouched). The
restructure alone took the carrier read on the sakila capture from 6.6 seconds to 173 ms, and it
is the whole answer to the carrier-free question the spec priced the slice on: a capture with no
mutation-root routine and no carrier evaluates the rule in 12 ms and reads nothing back, so the
refresh the registration installs sits inside the shipped registrations' range on both captures
(about 170 ms carrier-bearing, 12 ms carrier-free) and no disclosed-outlier path was needed.

The registration then followed the checklist. Post-registration, the whole family sits at the
store's floor on the sakila capture: the carrier read is a table read (under a millisecond for
15 rows), the seat 17 ms (from 43.3 s at this item's opening), the hop 53 ms (from 10.3 s), the
error channel 12 ms (from 5.3 s). The index-or-roster decision went to the roster again: on both
coordinates its readers spell (the error channel's family coordinate, the sigil surface's field
coordinate) no reader moves at all, to the scan. The sigil consumer claim measured as the spec
asked: a coordinate-filtered read of the rule prunes nothing (2510 scans against 2551 whole), and
the same filter against the table is 13 scans, so one `$source` completion falls from paying the
whole derivation to reading a table; the completion path still has no scan ceiling, which this
item states and does not fix, per its own spec.

### The gate flagged four pairs on the registration, and the lever is filed rather than shipped

Registering the carrier changed the planner's join order in the hop and the seat: both now reach
`graphql_field` through its `named_type` column, which no key serves, and
`DerivedReadCostTest` flagged four non-monotonic pairs (the hop and the seat, against this
registration and against the spelled-table one; 1.3k to 18k scans, wall clocks 1 and 10 ms on
the fixture). Every lever inside the registration's own scope was measured and moved nothing:
index shapes on the carrier target, a FROM-order restructure of the hop (H2 reorders base-table
join graphs freely, 19606 against 19619), and driving the hop from
`intent_field_payload_producer` instead of the reverse named-type probe (equal answers, 17003
scans). The lever that works is `CREATE INDEX ON graphql_field (graph_name, named_type)`,
measured at 2137 and 10049 scans for the hop and seat and clearing three of the four pairs, but
that is the first authored index on a captured base table, and a principles consult placed what
this item's evidence does not cover: the reader axis is the whole derived stratum (the gate is
blind to a base-table index by construction), the cost lands on every capture's write path, the
index-comment gate does not reach outside the register, and two doc sentences would need
amending. So the index is R820 (`graphql-field-named-type-index`), filed with the measurements
and those tasks, and the four pairs are pinned with the mechanism and the lever named; the
equality assertion deletes the rows the day it lands.

---
id: R835
title: "The node-id decode read costs three quarters of a second and no gate holds a figure over it"
status: Backlog
bucket: architecture
priority: 3
theme: nodeid
depends-on: []
created: 2026-08-25
last-updated: 2026-08-25
---

# The node-id decode read costs three quarters of a second and no gate holds a figure over it

Reading `intent_node_id_decode` once, over the schema `graphitron-sakila-example` ships, takes
**742 ms and visits 10603 rows to answer 47**. That is the single dearest relation read measured
in the fact store to date, and nothing in the tree fails when it moves: `DerivedReadCostTest`
holds a direction rather than a number, the `scanCount` ceilings in `graphitron-lsp` are held over
reader surfaces rather than over relations, and no reader of the decode has a budget of its own.
This item is to find the lever and, whatever the lever turns out to be, to leave a figure behind
that the next change has to answer to.

> **Amended after a merit review.** Two of the premises below did not survive checking and a third
> turned out to be larger than this item's scope. Read "The instrument cannot see the cost this item
> is about" and "No reader pays the 742 ms today" before anything else; both were written after the
> body under them and both correct it. Where they and an earlier section disagree, they are later.

## The measurement

Instrument: `EXPLAIN ANALYZE`, `scanCount` summed over the plan nodes, min-of-three wall clock,
one reader minted per read. Fixture: the sakila example schema as shipped, captured through
`CapturedStore.ofCatalog`, not the scaled synthetic fixture `DerivedReadCostTest` builds. The two
disagree by an order of magnitude on questions of this kind and the shipped schema is the
measurement; the input-field role relation's own comment carries the case where that mattered.

[cols="3,1,1,1"]
|===
| relation | rows | scans | ms

| `intent_node_id_decode`
| 47
| 10603
| 742

| `intent_node_id_decode_hop`
| 21
| 26045
| 248

| `intent_node_id_decode_column`
| 65
| 7035
| 14

| `intent_node_id_decode_slot`
| 0
| 1379
| 61

| `intent_node_id_decode_endpoint`
| 47
| 3402
| 2

| `intent_node_id_decode_defect`
| 0
| 1380
| 60
|===

Two things in that table are worth reading before picking a lever.

**The decode's own body costs more than everything under it.** Its two children together read in
about 75 ms; the decode reads in 742. The body is a windowed reduction over the column child
unioned with the slot arm, and the first arm carries a correlated `NOT EXISTS` against
`intent_node_id_decode_slot` per driving row. The slot relation is 61 ms to read once and holds no
rows on this schema, so the suspicion to test first is that the emptiness is being re-established
per row rather than once.

**The hop visits 26045 rows to yield 21**, more than the decode above it, on a schema whose whole
node-id surface is 81 instructions. That is a second, separable question: the hop resolves an
authored path through the reference-target views and a discovered key through the catalog, and the
`CASE`-per-column shape its comment defends was chosen to name the endpoint subtree once. Whether
it still does is worth re-measuring rather than assuming.

## The instrument cannot see the cost this item is about

Read the table above again, in two columns rather than six rows. The decode's scan count is the sum
of its children's, 7035 plus 1379 is 8414 against 10603 with the driving rows and the reduction on
top. Its wall clock is ten times the sum of its children's, 14 plus 61 is 75 against 742. A count of
rows visited and a duration disagreeing by a factor of ten over the same statement is not noise, and
it is not the two instruments measuring different things in the loose sense either. It has a
mechanism, and the mechanism is the reason the first suspicion above is both right and unprovable
from these numbers.

**H2 annotates a correlated subquery's plan node with one evaluation's scan count, not the total
across its evaluations.** So the summed scan count, which is the instrument `DerivedReadCostTest`
holds its whole claim on and the one the `store-performance` skill teaches, is blind to how many
times a correlated subquery ran. It grows with the driving row count and with nothing else.

Established standalone against H2 2.4.240, the version the reactor pins, with a counting function
inside a view so the evaluations are observed rather than inferred:

```java
public static final AtomicLong CALLS = new AtomicLong();
public static int tick(int x) { CALLS.incrementAndGet(); return x; }

s.execute("CREATE ALIAS tick FOR 'Probe.tick'");
// base holds 500 rows; the view scans all of them and returns none
s.execute("CREATE VIEW sub AS SELECT id, g FROM base WHERE tick(id) < 0");
// then, for a driver table of n rows:
"SELECT d.id FROM driver d WHERE NOT EXISTS (SELECT 1 FROM sub v WHERE v.id = d.id)"
```

[cols="1,1,1"]
|===
| driver rows | `tick()` calls, counted | summed `scanCount`

| 10
| 5000
| 513

| 20
| 10000
| 523

| 40
| 20000
| 543
|===

Four times the evaluations moves the summed scan count by six per cent. The subquery really did run
once per driving row, 40 times over a 500-row scan, and the plan reported 500 for it. A companion
probe on the same shape timed at 35 ms against 1 ms for one read of the view and 0 ms for the driver
alone, so the wall clock sees what the scan count does not.

That is exactly the signature in the measurement table, and the decode's first arm is exactly the
shape: a correlated `NOT EXISTS` against `intent_node_id_decode_slot`, keyed on the graph and the
use site. The arithmetic closes to within a factor: 742 ms over the decode's 47 to 65 driving rows
is 11 to 16 ms per evaluation of a slot subtree that reads in 61 ms uncorrelated, which is what
correlation pushdown would be expected to leave. So the suspicion this item opened with, that the
slot's emptiness is re-established per row, is very likely correct and no figure already in this
item can confirm it.

### What this costs beyond this item

Two shipped artifacts state something the probe above contradicts, and neither is this item's to fix
alone.

- `DerivedReadCostTest`'s javadoc says "No duration is asserted anywhere here" and argues that a
  count of rows visited is the right instrument because it is machine-independent. That argument
  holds for everything except multiplicity. A registration that flips a plan between evaluating a
  correlated subquery once per query and once per driving row moves the gate's number by a few per
  cent, so the gate would pass a regression of exactly the kind it exists to catch. The blindness is
  structural, not a threshold that could be tightened.
- The `store-performance` skill states the heuristic in one direction: "One enormous count on a
  single node says a relation is being re-evaluated per driving row." The converse false negative, a
  perfectly ordinary count that is a per-row re-evaluation, is undocumented and is the case that bit
  here.

Whether that becomes a sibling item or a slice of this one is the fourth Spec question below. The
argument for splitting it is that it has a live consumer today and the decode's cost does not, which
is the next section.

## No reader pays the 742 ms today

The decode's own view comment already says this and the tree agrees with it: "It carries no
registration of its own because nothing on the build path reads it yet."

- The build path reads `intent_node_id_decode_defect`, through `NodeIdDecodeDefects.detect` at
  `FactCapture.java:406`, and that view is derived from `intent_node_id_decode_slot` and
  `intent_resolved_node_key_shape`. It does not name `intent_node_id_decode`.
- The `@nodeId` walk on the build path is Java, in `ServiceCatalog` and the
  `CallSiteExtraction.NodeIdDecodeKeys` family. It reads no relation of this family at all.
- `intent_node_id_decode` itself is named by three tests and nothing else:
  `NodeIdDecodeDestinationTest` and `NodeIdDecodeDefectTest`, both seeded model-tier fixtures, and
  `NodeIdDecodeSlotCaptureTest`, whose fixture is a four-field SDL.

So the 742 ms is a figure from an ad-hoc probe against a sakila capture that no routine path
performs, and the build-path cost of this family is the defect view's 60 ms. That does not make the
relation's cost uninteresting, because R682 will eventually put a planner on it, but it does mean
this item is prophylactic rather than remedial, and its priority should be argued on that footing
rather than on an urgency it does not have.

It also bears on the third Spec question directly. A budget over a relation nothing reads pins a
number against no consequence, and R682's own slice-one reflection already states the rule for this
family: state the relation's refusals, "and put the relation through the read-cost gate in the same
increment, rather than reading a scan count out of a test that measured it for something else". The
increment that adds the reader is where the figure belongs.

## On the regression this item was filed for

The item was filed for a read-cost regression on this relation observed between `200fd26` and
`424a0e4`. **That regression does not reproduce from the store side, and the store side is ruled
out rather than merely unconfirmed.**

- The decode family's DDL is **byte-identical** across that window. Diffing the comment-stripped
  schema between the two commits produces 180 changed lines and not one of them is in
  `intent_node_id_decode`, its four children, `intent_argument_scope_table` or
  `intent_resolved_node_key_column`.
- The only upstream store change in the window is `272ef1361`, which materialized
  `intent_resolved_type_binding` and `intent_field_column_scope`. Both are reached by the decode's
  derivation, so both are candidates. Reversing each inside a live store with
  `UnregisteredRelation.install` and re-reading says they made the decode **cheaper**: without the
  type-binding registration the decode reads 18235 scans and 1014 ms against the shipped 10603 and
  742, and the column-scope registration is neutral to it (identical scans, wall clock inside
  noise). A registration that halves a reader is not the cause of that reader getting dearer.

What did change in the window, and is not ruled out, is the **reader side**: `828440035`,
`c79f4fd19` and `8df021744` reworked how the `@nodeId` walk reads the decode, and `ed424f628`
reshaped the diagnostic relations around it. A regression measured across that window is therefore
a question about how many times and in what shape the decode is read, not about what one read of it
costs. Whoever picks this item up should get the original measurement's method from its author
before spending time reconstructing it: a build wall clock and a relation read are different
claims, and no figure measured by one transfers to the other.

## What Spec has to decide

1. **Where the 742 ms goes, measured with an instrument that can see it.** Bisect the decode's body
   with cheap children, the method the `store-performance` skill sets out: time the two union arms
   apart, and time the first arm with the `NOT EXISTS` removed. The amendment above narrows this
   from an open question to a hypothesis with a predicted number, that the slot subtree is evaluated
   once per driving row at 11 to 16 ms each, so the bisect is a confirmation rather than a search.
   Time it. Do not read a scan count and conclude anything about multiplicity from it, which is the
   error the earlier draft of this item made in its own table.
2. **Whether the hop is a separate item**, and it now has a candidate mechanism to accept or refute.
   26045 scans for 21 rows is the shape of a relation walked whole per driving row, and the hop has
   one: the `d` derived table in its body is a windowed `COUNT(*) OVER (PARTITION BY ...)` over the
   whole of `sql_referential_constraint`, `LEFT JOIN`ed with `e.navigation = 'DISCOVERED_KEY'` sitting
   in the join condition. A window function blocks predicate pushdown into its input, so the
   constraint table cannot be probed by key and gets scanned. Unlike the decode's own cost this one
   is visible to the scan instrument, because the scan is in the plan rather than inside a correlated
   subquery. If it holds, this is a lever independent of everything else here and should be its own
   item.
3. **What gets pinned, where, and whether anything should be pinned yet.** A figure over a relation
   read is a shape this tree does not yet have: `DerivedReadCostTest` deliberately holds no ceilings,
   and its javadoc argues why a ceiling is the wrong instrument *there*. That argument is about
   registration cells and does not obviously extend to a plain per-relation budget, but the case has
   to be made rather than assumed, and whichever tier holds it has to hold it on a fixture that will
   not silently stop being representative. Two constraints the amendments above add. The precondition
   is a reader: per "No reader pays the 742 ms today", the figure belongs in the increment that gives
   the decode a build-path consumer, which is R682's, and pinning it earlier pins it against nothing.
   And whatever holds it cannot hold it in summed scan counts alone, for the reason question 4 is
   about.
4. **Whether the instrument finding is this item's or its own.** The blindness established above is
   not about the decode: it is a property of the gate and of the skill, both of which have live
   consumers today where the decode's cost has none. The recommendation from the review that produced
   this amendment is to split it out and do it first, on the grounds that a wrong claim in a shipped
   test's javadoc and a wrong heuristic in a shipped skill are both cheap to correct and both
   currently misleading the next author. Spec decides; if it stays here, it should lead rather than
   trail.
5. **Whether the reader side is in scope.** If the reported regression is real and reader-side, this
   item either grows to cover it or hands it to a sibling. Deciding that needs the original method,
   which is item 0 above. Note that this question has an unavailable input sitting inside an item
   with four other tasks, and that dropping it costs little: the store side is ruled out, so nothing
   below the reader is waiting on the answer.

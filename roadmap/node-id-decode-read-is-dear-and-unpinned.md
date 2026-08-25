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

1. **Where the 742 ms goes.** Bisect the decode's body with cheap children, the method the
   `store-performance` skill sets out: time the two union arms apart, time the first arm with the
   `NOT EXISTS` removed, and establish whether the slot subtree is evaluated once or per row before
   proposing anything.
2. **Whether the hop is a separate item.** 26045 scans for 21 rows may share a cause with the
   decode above it or may be its own; if it is its own, it should be filed as its own rather than
   carried along.
3. **What gets pinned, and where.** A figure over a relation read is a shape this tree does not yet
   have: `DerivedReadCostTest` deliberately holds no ceilings, and its javadoc argues why a ceiling
   is the wrong instrument *there*. That argument is about registration cells and does not obviously
   extend to a plain per-relation budget, but the case has to be made rather than assumed, and
   whichever tier holds it has to hold it on a fixture that will not silently stop being
   representative.
4. **Whether the reader side is in scope.** If the reported regression is real and reader-side, this
   item either grows to cover it or hands it to a sibling. Deciding that needs the original method,
   which is item 0 above.

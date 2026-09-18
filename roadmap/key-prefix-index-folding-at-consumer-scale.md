---
id: R957
title: "A prefix of a wide key is measured free on a twelve-unit fixture, so the folding verdicts in the NO_INDEX roster are unpriced at consumer scale"
status: Backlog
bucket: architecture
priority: 3
theme: model-cleanup
depends-on: []
created: 2026-09-18
last-updated: 2026-09-18
---

# A prefix of a wide key is measured free on a twelve-unit fixture, so the folding verdicts in the NO_INDEX roster are unpriced at consumer scale

## Goal

The `MaterializeRegistryGateTest.NO_INDEX` roster gains a priced answer for the question its rows
currently answer structurally: whether a declared index on the columns a reader seeks is worth
keeping when a registered target's primary key already leads with those same columns. Today the
roster carries two verdicts that disagree, `intent_spelled_table` keeping its index because folding
it moved three refresh statements and a read-cost pair, and the two reference-step hop arms dropping
theirs because folding them moved nothing on either instrument. Both measurements were taken on this
repository's own fixtures, a twelve-unit schema and a sakila capture, and a consumer schema is
larger by orders of magnitude in exactly the dimension that decides: a per-driving-row seek is linear
in driving rows, and a recursive walk pairs that cost with itself.

The reason this is not merely "re-run it bigger" is that the structural argument and the risk live in
different places. A prefix seek is served by a wider key at any size, so *seekability* does not decay
with rows. What can decay is *planner choice*: H2 charges a wide index for its unused columns in
`Index.getCostRangeIndex`, so a thirteen-column unique key with eight columns constrained is not the
same offer as an eight-column index that serves the identical seek. The tree already holds one worked
failure of exactly that shape on exactly this relation, where H2 priced the one-column `graph_name`
index below the eight-column step index and paid millions of row visits per evaluation until the
partition selectivity was declared. So the question to answer is not "is the key fast enough" but
"at what population, if any, does the planner stop choosing it", and the falsifier is a plan that
reaches for `graph_name` alone where the fixture-sized plan reaches for the key.

What lands is a figure per roster row rather than a change of verdict: the roster rows keep their
structural argument and gain the population it was checked against, or one of them changes and the
item says which. Filed out of R956's Done gate, where the folding verdict was checked and found
correctly decided and thinly evidenced for the scale its consumers run at.

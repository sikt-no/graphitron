---
id: R938
title: "The view count is paid at every store open, before any read happens"
status: Backlog
bucket: architecture
priority: 2
theme: model-cleanup
depends-on: []
created: 2026-09-08
last-updated: 2026-09-08
---

# The view count is paid at every store open, before any read happens

## Goal

Opening a fact store costs what its *data* costs, not what its *view count* costs. Today the fact
model's 120 views are paid three separate times on paths that have not read a single row yet, and
the largest of the three is invisible because it happens inside H2 rather than in our code.

## The three payments, measured

All figures 2026-09-08 at `7a3fae6e`, one 4 vCPU 15 GB sandbox.

**One: H2 rebuilds the view catalog at every open.** Two identical prebuilt template stores, one
with the 120 views and one with them dropped, opened from a copy with the arms alternated over two
rounds:

| | open with 120 views | open without views |
|---|---|---|
| round 0 | 129.6 ms best, 155.1 median | **31.3 ms** best, 40.2 median |
| round 1 | 91.7 ms best, 92.8 median | **25.7 ms** best, 26.7 median |

So **65 to 115 ms of every store open is view recompilation**, roughly 70% of it. This is paid by
every open of a file-backed store, and it is the reason a prebuilt template file cannot get near
its 3 ms copy cost: a template skips executing the DDL, but not building the catalog.

**Two: `deriveDependencies` parses the same view bodies again, in our code.**
`MaterializeDependencies.populate` walks out from each `meta_materialize` registration and parses
each stored view definition it reaches with `dsl.parser().parseQuery(...)`. It now reaches all 120.
Parsing all 120 definitions (324,789 characters) costs 78 ms warm, and the step itself measures
95.5 ms best and 139.7 ms mean, so the step is very nearly the parse. It has gone from 8.41 ms to
139.7 ms since 2026-08-20 and is now 32% of an in-memory boot.

**Three: reads inline them multiplicatively.** H2 inlines a view at every naming and eliminates no
common subexpression. That is the read-side cost R876 owns and this item does not.

## Why this is its own item

The first payment belongs to nobody. R876 attacks the read-side consequence of the view graph and
R768 attacks how many times the build opens a store, but neither makes an open cheaper, and an open
is what a consumer pays on every `graphitron:generate`, every `graphitron:dev` start, every language
server session and every MCP server start. The second payment does go when R876 dissolves
`meta_materialize`, and this item should not claim it.

The trend is the argument for filing now rather than waiting. Between 2026-08-21 and today the fact
schema went from 71 views to 120 and from 148 tables to 190, and the cost of an in-memory boot went
from 138.0 ms to 436.1 ms. No gate notices a view being added, and the cost of adding one is paid
by every consumer at every start.

## What a Spec pass has to settle

* **Whether the count can come down at all**, which is largely R876's question. Its remedy converts
  rules into capture-written relations and stored keys, which should retire views; the count has
  gone up rather than down while it has been in progress, so this wants measuring after that item
  lands rather than predicting now. **Re-run the two-arm open measurement above at that point before
  spending a Spec cycle here.**
* **Whether a view that no reader names has to exist in the catalog at all.** A view is a
  convenience for whoever writes SQL against the store; one nothing reads still costs every opener.
  A census of view read sites would say how many are in that position.
* **Whether the catalog cost can be avoided rather than reduced**, for instance by a store shape
  that carries its views only where something will read them. This is speculative and is listed so
  a Spec pass rules it in or out deliberately.

## What this item does not do

It does not touch read cost, which is R876's, and it does not touch boot *count*, which is R768's.
It is specifically about what one open costs before any row is read.

## How to re-measure

```
Build two file-backed templates from the same DDL. In the second, DROP VIEW every row of
INFORMATION_SCHEMA.VIEWS before closing. Then, for each, copy the stamped directory to a fresh
temp dir and time a raw DriverManager.getConnection to the copied file. Alternate the arms and
discard the first few iterations: the spread between cold and warm is larger than the effect on
a single pair, and the first arm pays JIT and reads high.
```

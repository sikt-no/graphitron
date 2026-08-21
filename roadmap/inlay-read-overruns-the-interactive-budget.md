---
id: R798
title: "Inlay hints do not finish inside the interactive budget on a real schema"
status: Backlog
bucket: bug
priority: 2
theme: lsp
depends-on: []
created: 2026-08-21
last-updated: 2026-08-21
---

# Inlay hints do not finish inside the interactive budget on a real schema

With every inlay axis enabled, one inlay-hint request over a fifty-line window of the sakila
example's 4222-line `schema.graphqls` took 11918 ms to produce ten hints, and 2013 ms for a
different fifty-line window of the same file. The interactive budget is three seconds, so in a real
session the request does not return hints at all: it spends the budget and is aborted, and the
developer sees no annotations with no indication why beyond a line in the build log.

This was found while measuring every language-server surface for
`roadmap/lsp-surface-latency-budgets.md`. That item recorded inlay at 10 ms and doubted the figure,
because inlay hints are configuration-gated through `applyPulledInlayHintConfig` and
`InlayHintConfig.defaults()` has every axis off, so the probe had measured an early return. With the
configuration on, inlay is the most expensive request surface by a wide margin rather than the
cheapest.

What that item did about it is stop the cost blocking anything else: the inlay request moved to a
reader of its own behind `StoreAccess.annotating`, so a hover or a jump no longer queues behind it.
That is a different fix from making the request answer, and it deliberately left this one alone. The
read itself is untouched, and a surface that always runs out of budget is a surface that does not
work.

One measurement to start from, which already rules out the obvious lever. Driving the same request
at three region sizes over the sakila schema, with the statement's `scanCount` read off
`EXPLAIN ANALYZE`:

[cols="1,1,1,1",options="header"]
|===
| Region | Time | Hints | Rows scanned

| 10 lines
| 4797 ms
| 0
| 561734

| 50 lines
| 13789 ms
| 10
| 561746

| 200 lines
| 36695 ms
| 24
| 561868
|===

One statement each, which is what `InlayHintStatementCountTest` pins, so the cost is inside that one
statement and not a round trip per site. What the scan counts say is that **the region is not the
lever**: the statement scans about 561700 rows whatever the region is, varying by 134 rows across a
twentyfold change in region size, so it is reading the schema rather than the window. The wall clock
does track the region, which means the per-region growth is work done per scanned row (a wider
predicate over the same rows) rather than more rows visited. A ten-line region that produced no
hints at all still spent 4797 ms and half a million row scans.

So the question is what that half-million-row scan is, and `InlayFacts` is where to look. Whether it
is a join-shape defect of the kind the declaration read turned out to have is the first thing to
check, and `SurfaceScanCountTest` is the instrument. The `store-performance` skill is the method.

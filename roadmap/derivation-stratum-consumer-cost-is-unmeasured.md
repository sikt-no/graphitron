---
id: R972
title: "The derivation stratum has never been timed on a consumer store: one sis capture, warm and cold, before and after the register left"
status: Backlog
bucket: architecture
priority: 3
theme: model-cleanup
depends-on: []
created: 2026-09-24
last-updated: 2026-09-24
---

# The derivation stratum has never been timed on a consumer store: one sis capture, warm and cold, before and after the register left

## Goal

A consumer's `graphitron:capture` has been timed on the tree that shipped the derivation stratum, so what that stratum costs at consumer scale is a number someone watched rather than an inference from the mechanism. The *derivation stratum* is the ordered list of steps (`DerivationStratum.steps`) the capture runs after its gatherers have flushed: each stage empties one graph's partition of a `graphitron_` table and refills it with one `INSERT` from the stored view that states its rule. R955 replaced the materialization register with that stratum, and R953, R954 and R958 changed the same pass on the way there. All four closed their gates without a consumer reading, because no consumer store is reachable from this repository. The fixtures show the rules are answered once per capture and correctly; they cannot show what a capture of the `sis` schema pays, and on this arc that is where the fixtures and the consumer have parted before: the fact-model page records one cold refresh prefix at 6293 s against 90.8 s with statistics.

## What is owed

One reading on a copy of a `sis` store, which the consumer has to supply:

- `graphitron:capture` warm (a store whose stratum tables already hold that graph's rows) and cold (a store none of whose stratum tables holds a row), on the tree before the register left (`fc327fb`) and on the tree after it.
- The per-stage lines from both captures after the change (`mvn -X`, the `n/N stage` and `done in` pairs), so a stage that dominates is named rather than inferred.
- The figures written into this item and into the changelog entry, including any stage that regressed against its old registration's refresh.

Until a store copy is available this item stays in Backlog. Close it as Discarded only if the consumer's own builds are measured some other way, and say which way.

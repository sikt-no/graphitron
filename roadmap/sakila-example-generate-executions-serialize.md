---
id: R766
title: "Five generate executions run one after another on the last node of the critical path"
status: Backlog
bucket: dx
priority: 3
theme: tooling
depends-on: []
created: 2026-08-20
last-updated: 2026-08-20
---

# Five generate executions run one after another on the last node of the critical path

`graphitron-sakila-example` declares five `graphitron:generate` executions. They write to disjoint
output packages, share nothing but the module, and Maven runs them one after another because Maven has
no notion of parallel executions within a phase. Together they are **33.1 seconds** on the module that
is both the last node of the build's dependency graph and the one place three of four cores sit idle.

## The measurement

One 4 vCPU sandbox, warm repository, per-goal timings from
`mvn install -pl :graphitron-sakila-example -Plocal-db -Dorg.slf4j.simpleLogger.showDateTime=true`.
The module totals 93.6 s built on its own.

| Execution | Time |
|---|---|
| `rewrite-generate` | 15.4 s |
| `rewrite-generate-federated` | 4.8 s |
| `rewrite-generate-multitenant` | 4.3 s |
| `rewrite-generate-multischema` | 4.4 s |
| `rewrite-generate-multischema-mutation` | 4.2 s |

Read the shape before sizing the win. The first execution costs 15.4 s and the other four cost about
4.4 s each, because all five run in one Maven JVM behind one plugin classloader: the JVM warm-up and
the store's first boot are paid once and amortised across the five. **So a fan-out recovers the 16.7
seconds the four warm executions spend, not the full 33.1.**

Why those seconds are worth more than their face value: `graphitron-sakila-example` depends on
everything and nothing depends on it, so it is the terminal node of the reactor and can never overlap
another module. Whatever it spends is wall clock, and while it spends it, three of four cores are idle.
A separate measurement puts the whole build's critical path at 340 s against a 340 s wall clock, so
there is no slack anywhere for this to hide in.

Within a warm execution, about 1.6 s is the classpath census's own deletes and merges into the fact
store, measured per statement. That is a third of each warm execution, and R762 owns cutting it; the
two compose but neither waits on the other. R763 takes the other half of this module, its 805 tests
running one at a time, and carries the reactor-wide measurement this section's framing rests on.

## What a Spec pass has to settle

* **What the fan-out is a fan-out of.** Maven will not run executions in parallel, so the concurrency
  has to live inside one invocation: a goal that takes a list of generation units and runs them across
  a small pool. That is a real API change to the plugin, not a pom edit.
* **What the five separate executions currently document, and how not to lose it.** Each carries a
  long pom comment naming the compile-tier property it proves: the federation fixture's isolation from
  the shared schema, the multi-schema catalog's segmented FQNs, the tenant-column routing, the
  cross-schema helper-name collision. A single execution taking a list must keep each unit's reason
  attached to that unit, or the next contributor deletes a fixture without knowing what it was for.
* **Whether the store tolerates five concurrent captures.** They currently share one store
  sequentially. Five writers into one fact store at once is a different exercise, and whether that is
  a feature or a source of flakes is the question. The store's per-graph segmentation is the thing to
  check first.
* **Whether the first execution's 11-second premium can be cut instead.** 15.4 s against 4.4 s is
  11 seconds of one-time cost inside the module, and cutting that needs no concurrency at all. Worth
  attributing before building a pool: if most of it is the store's first boot, a different item already
  owns it.

## How to re-measure

```bash
mvn install -pl :graphitron-sakila-example -Plocal-db \
    -Dorg.slf4j.simpleLogger.showDateTime=true \
    -Dorg.slf4j.simpleLogger.dateTimeFormat="HH:mm:ss.SSS"
# Each goal's start is stamped; the difference between consecutive stamps is the goal's cost.
# Use mvn rather than mvnd: mvnd reformats the output and the timestamps are lost.
```

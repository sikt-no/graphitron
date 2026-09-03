---
id: R916
title: "The dev session index and refresh loop re-reads only what changed"
status: Backlog
bucket: dx
priority: 2
theme: tooling
depends-on: []
created: 2026-09-03
last-updated: 2026-09-03
---

# The dev session index and refresh loop re-reads only what changed

## Goal

A dev session's refresh costs work proportional to what the developer actually changed. Today a
`graphitron:dev` round and a `graphitron:generate` under `quarkus:dev` both re-run an index and
refresh loop whose cost tracks the size of the workspace rather than the size of the edit, so a
one-character schema change pays for a full re-read. When this lands, an incremental round is
measurably cheaper than a cold one, and the loop's cost is attributable per phase rather than
observable only as total wall-clock.

## Where this comes from

Split off from the fact store cache item filed against
[issue 544](https://github.com/sikt-no/graphitron/issues/544). That item bounds the cache: it stops
the H2 file growing without limit, stops stale stamped directories accumulating, and stops a warm
clear from stalling a run. It deliberately does not ask whether the loop that fills the store is
doing the right amount of work in the first place, which is a separate question with its own answer
and should not block a fix that is ready to deploy.

The issue's measurement is the motivation. On a consumer schema, `graphitron:generate` spent roughly
one minute on fact collection *after* the store work was accounted for. That minute is this item's
subject: the cache fix removes the two-minute clear, and leaves the capture cost standing.

## What to investigate

* **What the loop re-reads per round.** Which sources (jOOQ catalog classes, JVM classpath scan,
  schema files, Java sources) are re-read on a refresh that follows a single edit, and which of those
  could be known unchanged from a cheap stamp rather than re-derived.
* **Whether the grain of invalidation matches the grain of the edit.** A schema edit and a Java source
  edit invalidate different facts. If the loop invalidates everything on any change, that is the
  finding.
* **Where the time actually goes.** Per-phase timing for one round, so the answer is measured rather
  than reasoned. This is the same instrumentation the cache item needs for its own verification, so
  the two can share it.
* **Whether two processes in one checkout can each keep a warm session.** The issue reports
  `graphitron:dev` and `quarkus:dev` losing warm start to each other on every round. The cache item
  covers the file-lock and store-ownership half; what belongs here is whether the loop's work can be
  shared or cheaply repeated rather than duplicated wholesale.

## Out of scope

Store file size, compaction, stale stamp reclamation, and the warm-clear stall. Those belong to the
cache item, and this one should assume they are fixed rather than work around them.

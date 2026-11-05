---
id: R1154
title: "Refuse acquisition after release in the single-tenant connection carrier"
status: Backlog
bucket: bug
priority: 2
theme: runtime-connection
depends-on: []
created: 2026-11-05
last-updated: 2026-11-05
---

# Refuse acquisition after release in the single-tenant connection carrier

## Goal

An acquisition that arrives after an operation released its connections is refused in single-tenant builds too, so no mounted connection is ever orphaned outside the pool. The multi-tenant `TenantConnections` sets a volatile `closed` flag before draining, removes entries one at a time, and has a late pin abort itself. The single-tenant variant has none of this (`ConnectionRuntimeClassGenerator` emits the `closed` field for multi-tenant builds only): its `releaseAll()` iterates the entries and then calls `entries.clear()`. A `dslDefault()` that runs after or during release, from a cancelled execution or consumer code still running asynchronously, pins and mounts a fresh connection that release never sees. That connection is never unmounted and never returned. It carries the same caller's identity, so the direct cost is a leaked connection and eventually an exhausted pool; with a pool that reclaims abandoned connections back into circulation, the mounted identity could reach another borrower. When this lands both variants share the multi-tenant close-then-drain shape.

## Context

Found in an adversarial review of the owned-connection session lifecycle. Two windows: (1) acquisition strictly after `releaseAll()`, which recreates the entry; (2) a concurrent `computeIfAbsent` that lands between the iteration and `entries.clear()`, whose entry is cleared without release. The multi-tenant fix covers both. Also worth folding in: `PinnedConnection.released` is a plain boolean, so a cancellation and a completion calling `release()` on different threads can both unmount and close; an `AtomicBoolean.compareAndSet` closes that.

The execution case belongs in the suite R1153 builds.


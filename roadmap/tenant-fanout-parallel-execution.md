---
id: R510
title: "Bounded parallel execution substrate for tenant fan-out"
status: Backlog
bucket: architecture
priority: 6
theme: runtime-connection
depends-on: []
created: 2026-07-22
last-updated: 2026-07-22
---

# Bounded parallel execution substrate for tenant fan-out

## Motivation

R46 ([`service-multi-tenant-fanout.md`](service-multi-tenant-fanout.md)) runs one field's query against every tenant in the fan-out domain and unions the results. Sequentially, dozens of databases per field is dozens of round-trips end to end; the hand-written production resolver R46 replaces already fans out in parallel. But the shipped runtime is deliberately serial per operation, in three load-bearing ways:

- Every generated fetcher returns `CompletableFuture.completedFuture(...)`; the synchronous-body invariant is test-pinned and documented as the one-connection-per-operation safety story.
- The generated `TenantConnections` carrier is documented not thread-safe: a plain `LinkedHashMap` of pinned connections, a plain `defaultPinned` field, and a `releaseAll` that assumes fetchers run serially on the dispatch thread.
- There is no executor anywhere in the runtime; the only `Executor` is the same-thread `abortExecutor`, and no concurrency cap or timeout configuration exists.

Parallel fan-out is therefore not an increment on R46's emitters; it is the first concurrency in the runtime, and it deserves its own item so the thread-safety rework and its proofs land and get reviewed on their own, before R46's classification arm and emitters ride on them. This item is the substrate; R46 is the consumer.

## Design

### Parallelism confined to a scatter/join helper; fetchers stay synchronous

The central move: parallelism lives *inside* one generated runtime helper, and the fetcher-facing surface stays blocking. `TenantConnections` gains a scatter method, shape roughly:

```java
<R> List<R> scatter(Collection<TenantKey> keys, Function<DSLContext, R> perTenant)
```

Each tenant's unit of work runs on a bounded executor; each worker acquires and pins that tenant's connection through the existing R429 acquisition seam (so session hooks mount per acquisition and per-tenant RLS composes unchanged); the calling dispatch thread blocks joining all results under a timeout. Results return in the iteration order of `keys`, so union order is deterministic and owned by the caller.

What this deliberately preserves:

- **The synchronous-body invariant stands.** Generated fetchers keep returning `completedFuture`; graphql-java async semantics, DataLoader batching, and the loader-name partition mechanism are untouched. A fanned-out fetcher (R46's emitters) calls `scatter` and blocks; concurrency never leaks into fetcher bodies.
- **R429 stays the only acquisition path.** Workers acquire through the same pin-and-mount seam; one read-only transaction context per tenant, N tenants is N independent transaction contexts, exactly the demarcation rule already shipped.

### Executor ownership and configuration

The concurrency cap and the scatter timeout are deployment-time values, not build-time facts, so they do not touch the Mojo. They live on the generated `GraphitronRuntime` constructor surface: a new optional configuration (cap, timeout, or a consumer-supplied `Executor`) with working defaults, beside the `DataSource` map and dialect the consumer already hands over. Generated output targets Java 17, so the default executor is a bounded pool of platform threads, sized conservatively; a consumer that wants virtual threads on a newer JVM supplies its own executor.

Open sub-question for the spec review: exact shape of the configuration (two scalars plus optional executor, or a small builder/record), and the default cap and timeout values.

### Thread-safety rework of `TenantConnections`

- `pinnedByTenant` becomes a concurrent structure with per-key single acquisition (one pin per tenant even under concurrent scatter workers; `computeIfAbsent` or per-key future memoization, chosen at implementation against the acquire-inside-lock trade-off).
- `defaultPinned` gets safe publication.
- `releaseAll` stays correct against the new callers: `scatter` joins (or times out and cancels) before returning, so no pinning is in flight at release time; this is asserted, not assumed.
- `GraphitronTransactionProvider` instances stay per-connection and are used by at most one worker at a time; the sequential-children case (fields below the fanned field running on the dispatch thread against pinned connections) is unchanged.

The "not thread-safe, fetchers run serially" javadoc on the carrier is rewritten to state the new contract precisely: concurrent `dslFor`/`scatter` is safe; everything else keeps the serial assumption.

### Failure semantics

One tenant's failure fails the whole scatter: outstanding work is cancelled, everything pinned is released through the existing first-failure-wins release path, and the failure propagates as a request-level error. Timeout is the same event. No silent partial results: a fan-out that drops a failed tenant would return incomplete results presented as complete, the exact posture R45 already rejects for a claimed tenant missing from the `DataSource` map. (Whether R46 later wants softer per-tenant error surfacing composes on top; the substrate's primitive is strict.)

### Scope boundary

No schema surface, no classification change, no fetcher-emission change. The scatter helper is generated but has no generated caller until R46 lands its arm and emitters; that is acceptable for a runtime seam (the carrier already exposes fetcher-facing statics individually consumed) but the pair of items should land close together, and R46's spec must not re-open this item's decisions.

## Tests

- **Generator tier:** pin the emitted runtime code: the scatter method, the concurrent pinning structure, the constructor configuration surface, single-tenant builds byte-identical when the surface is absent (mirroring R45's single-tenant baseline discipline).
- **Execution tier (sakila, database-per-tenant PostgreSQL):** scatter across real tenant databases proves parallel execution (bounded by the cap), per-tenant session state intact under concurrency, deterministic result order, one-tenant-failure cancels and releases all, timeout propagates as request error, `releaseAll` after a concurrent scatter leaks nothing (assert via connection counts).
- **Concurrency-focused unit proof** of per-key single acquisition under contention (many workers, same key, exactly one pin).

## Siblings

- **R46** ([`service-multi-tenant-fanout.md`](service-multi-tenant-fanout.md)): the consumer; its fan-out arm and emitters call `scatter`. R46's open question 4 (parallelism bounds) resolves here.
- **R505** ([`tenant-index-parent-row-routing.md`](tenant-index-parent-row-routing.md)): once index routing narrows the fan-out domain, scatter degrees shrink; the substrate is unchanged.
- **R429** (Done, recorded in [`changelog.md`](changelog.md)): the acquisition, pinning, session-hook, and release seams this item makes safe under concurrency without changing their ownership.

---
id: R510
title: "Bounded parallel execution substrate for tenant fan-out"
status: Spec
bucket: architecture
priority: 6
theme: runtime-connection
depends-on: []
created: 2026-07-22
last-updated: 2026-07-22
---

# Bounded parallel execution substrate for tenant fan-out

## Motivation

R46 ([`service-multi-tenant-fanout.md`](service-multi-tenant-fanout.md)) runs one field's query against every tenant in the fan-out domain and unions the results. Sequentially, dozens of databases per field is dozens of round-trips end to end. This is not speculative: the hand-written production resolver R46 replaces (`megVedLarested`) fans out in parallel because serial execution measured too slow, so the parallelism requirement is evidence-backed at the fan-out point specifically. The fan-out point is also where the concurrency can be *controlled*: one helper, bounded, with the rest of the runtime untouched. But the shipped runtime is deliberately serial per operation, in three load-bearing ways:

- Every generated fetcher returns `CompletableFuture.completedFuture(...)`; the synchronous-body invariant is test-pinned and documented as the one-connection-per-operation safety story.
- The generated `TenantConnections` carrier is documented not thread-safe: a plain `LinkedHashMap` of pinned connections, a plain `defaultPinned` field, and a `releaseAll` that assumes fetchers run serially on the dispatch thread.
- There is no executor anywhere in the runtime; the only `Executor` is the same-thread `abortExecutor`, and no concurrency cap or timeout configuration exists.

Parallel fan-out is therefore not an increment on R46's emitters; it is the first concurrency in the runtime, and it deserves its own item so the thread-safety rework and its proofs land and get reviewed on their own, before R46's classification arm and emitters ride on them. This item is the substrate; R46 is the consumer.

## Design

### Parallelism confined to a scatter/join helper; fetchers stay synchronous

The central move: parallelism lives *inside* one generated runtime helper, and the fetcher-facing surface stays blocking. `TenantConnections` gains a scatter method that reports per-tenant *outcomes*, shape roughly:

```java
<R> List<Outcome<R>> scatter(Collection<TenantKey> keys, Function<DSLContext, R> perTenant)

sealed interface Outcome<R> {
    record Success<R>(TenantKey key, R value) implements Outcome<R> {}
    record Failed<R>(TenantKey key, Throwable cause) implements Outcome<R> {}
    record TimedOut<R>(TenantKey key) implements Outcome<R> {}
}
```

Each tenant's unit of work runs on a bounded executor; each worker resolves its `DSLContext` by calling the existing `dslFor(key)`, so the pin-and-mount recipe (connection binding, transaction provider, session hooks, settle callback) stays single-sourced in the one place that owns it and per-tenant RLS composes unchanged. The helper owns only the concurrency and the join: the calling dispatch thread blocks until every worker completes or the deadline passes, then returns outcomes in the iteration order of `keys`, so union order is deterministic and owned by the caller.

The outcome taxonomy is deliberate: the substrate is **policy-neutral about partial failure**. R46's open question 3 (null-drop vs error surfacing per tenant) stays R46's to answer; its emitted caller collapses the outcomes under whichever policy it chooses, and a policy change later never reworks this item's return contract. For the same reason `scatter` runs every worker to completion or deadline rather than cancelling on first failure; fail-fast is a caller policy, not a substrate primitive.

Workers never touch the default connection: `perTenant` receives only the keyed `DSLContext`, structurally, so `defaultPinned` and `dslDefault` stay owned by the dispatch thread alone (which is blocked inside the join for the scatter's whole duration). The scatter surface is emitted only under the existing `multiTenant` gate, beside the routing statics that are gated for the same reason: no uncallable public method lands in single-tenant consumers' generated sources.

`scatter` is not re-entrant: a `perTenant` body must never call `scatter` (a bounded pool waiting on itself deadlocks). Nothing generated produces that shape, but the prohibition is an invariant, so it gets a cheap runtime guard (a worker-thread marker; violation throws `IllegalStateException` immediately rather than deadlocking silently) and a test that pins the guard.

What this deliberately preserves:

- **The synchronous-body invariant stands.** Generated fetchers keep returning `completedFuture`; graphql-java async semantics, DataLoader batching, and the loader-name partition mechanism are untouched. A fanned-out fetcher (R46's emitters) calls `scatter` and blocks; concurrency never leaks into fetcher bodies.
- **R429 stays the only acquisition path.** Workers acquire through the same pin-and-mount seam; each tenant's pinned connection carries its own transaction context, so N tenants is N independent transaction contexts, exactly the demarcation rule already shipped (read-only enforcement itself is R460's, [`query-read-only-enforcement.md`](query-read-only-enforcement.md), and orthogonal here).

### Executor ownership and configuration

The concurrency cap and the scatter timeout are deployment-time values, not build-time facts, so they do not touch the Mojo. They live on the generated `GraphitronRuntime` constructor surface: a new optional configuration (cap, timeout, or a consumer-supplied `Executor`) with working defaults, beside the `DataSource` map and dialect the consumer already hands over. Generated output targets Java 17, so the default executor is a bounded pool of platform threads, sized conservatively; a consumer that wants virtual threads on a newer JVM supplies its own executor.

Open sub-question for the spec review: exact shape of the configuration (two scalars plus optional executor, or a small builder/record), and the default cap and timeout values. The new bounded executor is a second, independent field beside the existing same-thread `abortExecutor`; the two are orthogonal and never conflated.

### Thread-safety rework of `TenantConnections`

- `pinnedByTenant` becomes a concurrent structure with per-key single acquisition (one pin per tenant even under concurrent scatter workers; `computeIfAbsent` or per-key future memoization, chosen at implementation against the acquire-inside-lock trade-off). Scatter partitions distinct keys one worker each, but nothing structural prevents two scatters or a worker and the dispatch thread racing the same key, so the per-key guarantee is the contract, not an accident of today's callers.
- `defaultPinned` needs no concurrency work: workers cannot reach it (structural, above), and the dispatch thread that owns it is blocked in the join while workers run. Its check-then-pin stays a serial code path; the spec states this as the reason rather than adding speculative synchronization.
- `releaseAll` stays correct against the new callers, and the timed-out straggler is the corner it must survive: a `TimedOut` outcome means the join stopped *waiting*, not that the worker stopped *working*. A JDBC call cannot be safely killed, so a straggler may still hold its pinned connection mid-statement when the operation completes. The invariant, with its own enforcer: a connection is never closed or returned to the pool while its worker may still be executing, and a timed-out tenant's pinned entry is never reused later in the operation. Mechanically, `releaseAll` releases settled tenants normally and routes stragglers' connections through the existing abort seam (`Connection.abort` via the runtime's abort executor), which is designed for exactly this; the straggler's eventual completion lands harmlessly.
- `GraphitronTransactionProvider` instances stay per-connection and are used by at most one worker at a time; the sequential-children case (fields below the fanned field running on the dispatch thread against pinned connections) is unchanged.

Two doc regions revise in the same change, so neither states a live-but-wrong rationale: the carrier's "not thread-safe (a single operation's fetchers run serially on the dispatch thread)" javadoc, and the class-level one-connection-per-operation invariant section that ties the `completedFuture` synchronous-body invariant to the absence of concurrent access. Both restate the new contract precisely: concurrency is confined to `scatter`'s bounded workers, each owning one keyed connection single-threaded through `dslFor`, with the dispatch thread blocked on the join; the synchronous-body invariant and per-connection single-threading both still hold, for the revised reason.

### Failure semantics

The substrate reports; the caller decides. Every tenant's work runs to completion or the deadline, and each ends as exactly one `Outcome`: `Success`, `Failed` (the worker threw; the cause is carried, never swallowed), or `TimedOut` (the deadline passed first). Nothing is dropped silently at this layer: a failed or timed-out tenant is present in the returned list, so an R46 caller that chooses to error on any non-success can, and a caller that chooses softer surfacing (composing with the typed-errors plan) also can, without this item changing. What the substrate does guarantee unconditionally: every pinned connection is released through the existing first-failure-wins `releaseAll` path at operation end regardless of outcome mix, and an empty result from a tenant (RLS row-scoping legitimately yields nothing) is a `Success` carrying an empty value, distinct from `Failed`; conflating the two is exactly the incomplete-presented-as-complete confusion R45's error posture exists to prevent.

### Considered and deferred: full async fetchers

The alternative design, worked through from first principles before this spec settled (2026-07-22), is to make emitted fetcher and batch-loader bodies genuinely async at the per-tenant statement-execution seam, so cross-tenant parallelism emerges from R45's loader-name partition for *every* tenant-heterogeneous shape rather than only at marked fan-out fields. The session constraint draws the same scope line for both designs: statement concurrency requires connection concurrency, every fresh connection must start a session before executing queries, and the minimum session cost of an operation is one mount per distinct source touched. Cross-tenant work already pays one mount per tenant whether serial or parallel, so overlapping the per-tenant lanes adds zero session cost; intra-source parallelism would pay extra mounts and pool slots for gains DataLoader batching has mostly eaten, and is out of scope under either design. Pin-per-operation survives untouched under either design.

Full async is deferred, not rejected, because the evidence localizes: the measured-too-slow shape is the controlled fan-out point, which this item's confined helper covers, while full async would retire the synchronous-body invariant across the runtime, introduce per-lane ordering (at most one statement in flight per pinned connection) as a new load-bearing invariant with its own enforcer, and permanently raise the concurrency bar for all future runtime work. Confinement keeps the serial reasoning model everywhere except inside `scatter`.

The accepted cost of confinement, named so it is a decision and not an oversight: R45's per-row multi-tenant dispatch shapes (node and `_entities` batches spanning tenants) stay serial under this item. Their typical tenant degree is small, so the serial penalty is minor today. The trigger for revisiting full async is evidence that those shapes, or a proliferation of fan-out sites, are measurably slow in production (the instrumentation is already tenant-keyed); the design recorded here is the starting point for that item, and the scatter contract is compatible with it (a caller composing futures would supersede the blocking join without changing the pinning, outcome, or release semantics).

### Scope boundary

No schema surface, no classification change, no fetcher-emission change. The scatter helper is generated but has no generated caller until R46 lands its arm and emitters. This follows the precedent set when the `TenantConnections` routing surface itself shipped ahead of the fetcher emission that calls it, proved directly against test-supplied keys and a fake tenant map; and because this item adds no classifier arm, there is no validate-time guarantee at stake (that hazard is a classification variant without a generator branch, which R46 owns). The pair of items should still land close together, and R46's spec must not re-open this item's decisions.

## Tests

Every concurrency invariant gets a named enforcer in this item; none waits for R46's execute-tier schema.

- **Generator tier:** pin the emitted runtime code: the scatter method and `Outcome` taxonomy, the concurrent pinning structure, the constructor configuration surface, the `multiTenant` gate (single-tenant builds byte-identical, mirroring R45's single-tenant baseline discipline).
- **Direct-carrier concurrency proofs** (compile-and-run over the emitted `TenantConnections`, the same tier that proved the routing surface directly with test-supplied keys and a fake tenant map): a deterministic bounded executor plus latch-based `perTenant` bodies pin the cap (never more than N workers in flight), the deadline (`TimedOut` outcomes for workers past it), failure isolation (one throwing worker yields `Failed` with its cause while siblings still succeed), outcome order matching key iteration order, per-key single acquisition under contention (many workers, same key, exactly one pin), the re-entrancy guard (a `perTenant` body calling `scatter` throws immediately), and the straggler contract (a latch-held worker past the deadline: scatter returns `TimedOut`, `releaseAll` releases settled tenants and aborts the straggler's connection without closing it under the live statement, and the worker's eventual completion neither throws into the void nor leaks the connection).
- **Execution tier (sakila, database-per-tenant PostgreSQL):** direct calls against the generated carrier over real tenant databases: genuinely parallel execution under the cap, per-tenant session state intact under concurrency, empty-result tenants come back `Success`-empty and distinct from `Failed`, and `releaseAll` after a concurrent scatter leaks nothing (assert via connection counts).

## Siblings

- **R46** ([`service-multi-tenant-fanout.md`](service-multi-tenant-fanout.md)): the consumer; its fan-out arm and emitters call `scatter`. R46's open question 4 (parallelism bounds) resolves here.
- **R505** ([`tenant-index-parent-row-routing.md`](tenant-index-parent-row-routing.md)): once index routing narrows the fan-out domain, scatter degrees shrink; the substrate is unchanged.
- **R429** (Done, recorded in [`changelog.md`](changelog.md)): the acquisition, pinning, session-hook, and release seams this item makes safe under concurrency without changing their ownership.

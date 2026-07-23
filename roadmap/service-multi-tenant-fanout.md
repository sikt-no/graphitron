---
id: R46
title: "Multi-tenant fan-out: run one field across many tenants and union the results"
status: Backlog
bucket: architecture
priority: 6
theme: runtime-connection
depends-on: []
last-updated: 2026-07-22
---

# Multi-tenant fan-out: run one field across many tenants and union the results

## Motivation

Two production patterns need the same shape:

- **No index narrows the tenant.** A student's results live in per-university databases. Until a tenant-index table routes children per row (R505, [`tenant-index-parent-row-routing.md`](tenant-index-parent-row-routing.md)), the only way to answer "all results for this student" is to query *every* organisation and union what comes back. This is what production does by hand today, and this item is the first-iteration answer for tenant-spanning queries.
- **Membership-driven fan-out.** A downstream resolver (`megVedLarested`) bypasses `@service` and hand-writes: for each tenant the logged-in user belongs to, open that tenant's connection, call the service, drop nulls, union. The service method is GraphQL-free Java; what does not fit codegen today is the per-tenant connection plumbing and the parallel orchestration. The parallelism is evidence-backed, not speculative: this resolver went parallel because serial execution measured too slow, so the generated replacement must fan out in parallel too. The fan-out point is also where concurrency can be *controlled*: one bounded helper, with the rest of the runtime untouched (see the execution substrate section below).

## Direction (to be spec'd)

This item is the deliberate no-binding arm of R45's `TenantBinding` axis. An explicit schema marker (form open: a directive such as `@fanOut`, or a list-typed contextArgument naming the tenant subset) classifies the field into a fan-out arm; the arm and its emitters land together here, so R45's `noTenantBinding` rejection keeps guarding every unmarked unroutable field.

**Fan-out domain (resolved):** the intersection of the `Map<TenantId, DataSource>` keys and the tenantIds the user holds roles for in the request's claims, with the two directions of the difference treated differently. A mapped tenant the user holds no role for is never queried: the authorization pre-filter, silent by design. A claimed tenant missing from the map is a **request-level error before any SQL runs**, not a silent skip: the derived tenant set is the model's statement that data could exist there, so skipping would return incomplete results presented as complete, and R45 already gives the same event (a divined tenant with no `DataSource`) the same error semantics. Deployments where claims legitimately span more tenants than this subgraph hosts narrow the set in the claims-extraction seam (question 1), where the narrowing is the consumer's explicit statement rather than a silent runtime drop. Neither the whole map nor the raw claims set alone is ever the domain.

Mechanics ride the R429 substrate through the bounded scatter helper spec'd below: acquisition per tenant in the domain through the map, each tenant's pinned connection carrying its own transaction context (R429's demarcation rule already covers N transactions per operation; read-only enforcement itself is R460's, [`query-read-only-enforcement.md`](query-read-only-enforcement.md)), session state set per acquisition so per-tenant RLS composes (a tenant where the user has no row access contributes nothing). Results union; nulls and empties drop. Connection threading is R429's acquisition seam plus the scatter helper, never hand-rolled executor code in generated fetchers.

The previous design here (a `ContextValueRegistration<FanOut>` permit, `DslContextPerElement`, and `GraphitronContext` widening with `getContextFanOut` / `openContextDslContext` / `getExecutor`) predates R190 sealing `GraphitronContext` and R429 owning connections; it is superseded and recorded in this file's git history.

## Execution substrate: bounded scatter (spec'd 2026-07-22)

Absorbed from discarded R510 (`tenant-fanout-parallel-execution`), whose Spec pass, including a principles-architect consult, produced this section; this item is scatter's first and likely only caller, so the substrate ships here as the leading slices (the same shape as R45 shipping the `TenantConnections` routing surface one slice ahead of the fetcher emission that calls it, proved directly). The shipped runtime is deliberately serial per operation in three load-bearing ways: every generated fetcher returns `CompletableFuture.completedFuture(...)` (the synchronous-body invariant, test-pinned and documented as the one-connection-per-operation safety story); the generated `TenantConnections` carrier is documented not thread-safe (plain `LinkedHashMap` of pinned connections, plain `defaultPinned` field, `releaseAll` assuming serial fetchers); and there is no executor anywhere in the runtime beyond the same-thread `abortExecutor`, and no concurrency cap or timeout configuration. This substrate is the first concurrency in the runtime, so its slices land and are proved on their own before the arm and emitter slices ride on them.

### Parallelism confined to a scatter/join helper; fetchers stay synchronous

Parallelism lives *inside* one generated runtime helper, and the fetcher-facing surface stays blocking. `TenantConnections` gains a scatter method that reports per-tenant *outcomes*, shape roughly:

```java
<R> List<Outcome<R>> scatter(Collection<TenantKey> keys, Function<DSLContext, R> perTenant)

sealed interface Outcome<R> {
    record Success<R>(TenantKey key, R value) implements Outcome<R> {}
    record Failed<R>(TenantKey key, Throwable cause) implements Outcome<R> {}
    record TimedOut<R>(TenantKey key) implements Outcome<R> {}
}
```

Each tenant's unit of work runs on a bounded executor; each worker resolves its `DSLContext` by calling the existing `dslFor(key)`, so the pin-and-mount recipe (connection binding, transaction provider, session hooks, settle callback) stays single-sourced in the one place that owns it and per-tenant RLS composes unchanged. The helper owns only the concurrency and the join: the calling dispatch thread blocks until every worker completes or the deadline passes, then returns outcomes in the iteration order of `keys`, so union order is deterministic and owned by the caller.

The outcome taxonomy is deliberate: the substrate is **policy-neutral about partial failure**. Open question 3 below (null-drop vs error surfacing per tenant) stays open; the emitted caller collapses the outcomes under whichever policy the Spec pass chooses, and a policy change later never reworks the scatter contract. For the same reason `scatter` runs every worker to completion or deadline rather than cancelling on first failure; fail-fast is a caller policy, not a substrate primitive.

Workers never touch the default connection: `perTenant` receives only the keyed `DSLContext`, structurally, so `defaultPinned` and `dslDefault` stay owned by the dispatch thread alone (which is blocked inside the join for the scatter's whole duration). The scatter surface is emitted only under the existing `multiTenant` gate, beside the routing statics that are gated for the same reason: no uncallable public method lands in single-tenant consumers' generated sources.

`scatter` is not re-entrant: a `perTenant` body must never call `scatter` (a bounded pool waiting on itself deadlocks). Nothing generated produces that shape, but the prohibition is an invariant, so it gets a cheap runtime guard (a worker-thread marker; violation throws `IllegalStateException` immediately rather than deadlocking silently) and a test that pins the guard.

What this deliberately preserves: the synchronous-body invariant stands (generated fetchers keep returning `completedFuture`; graphql-java async semantics, DataLoader batching, and the loader-name partition mechanism are untouched; the fanned-out fetcher calls `scatter` and blocks, and concurrency never leaks into fetcher bodies), and R429 stays the only acquisition path.

### Executor ownership and configuration

The concurrency cap and the scatter timeout are deployment-time values, not build-time facts, so they do not touch the Mojo. They live on the generated `GraphitronRuntime` constructor surface: a new optional configuration (cap, timeout, or a consumer-supplied `Executor`) with working defaults, beside the `DataSource` map and dialect the consumer already hands over. Generated output targets Java 17, so the default executor is a bounded pool of platform threads, sized conservatively; a consumer that wants virtual threads on a newer JVM supplies its own executor. The new bounded executor is a second, independent field beside the existing same-thread `abortExecutor`; the two are orthogonal and never conflated. Open sub-question for the spec review: exact configuration shape (two scalars plus optional executor, or a small builder/record) and the default cap and timeout values.

### Thread-safety rework of `TenantConnections`

- `pinnedByTenant` becomes a concurrent structure with per-key single acquisition (one pin per tenant even under concurrent scatter workers; `computeIfAbsent` or per-key future memoization, chosen at implementation against the acquire-inside-lock trade-off). Scatter partitions distinct keys one worker each, but nothing structural prevents two scatters or a worker and the dispatch thread racing the same key, so the per-key guarantee is the contract, not an accident of the callers.
- `defaultPinned` needs no concurrency work: workers cannot reach it (structural, above), and the dispatch thread that owns it is blocked in the join while workers run. Its check-then-pin stays a serial code path; this is stated as the reason rather than adding speculative synchronization.
- `releaseAll` stays correct against the new callers, and the timed-out straggler is the corner it must survive: a `TimedOut` outcome means the join stopped *waiting*, not that the worker stopped *working*. A JDBC call cannot be safely killed, so a straggler may still hold its pinned connection mid-statement when the operation completes. The invariant, with its own enforcer: a connection is never closed or returned to the pool while its worker may still be executing, and a timed-out tenant's pinned entry is never reused later in the operation. Mechanically, `releaseAll` releases settled tenants normally and routes stragglers' connections through the existing abort seam (`Connection.abort` via the runtime's abort executor), which is designed for exactly this; the straggler's eventual completion lands harmlessly.
- `GraphitronTransactionProvider` instances stay per-connection and are used by at most one worker at a time; the sequential-children case (fields below the fanned field running on the dispatch thread against pinned connections) is unchanged.

Two doc regions revise in the same change, so neither states a live-but-wrong rationale: the carrier's "not thread-safe (a single operation's fetchers run serially on the dispatch thread)" javadoc, and the class-level one-connection-per-operation invariant section that ties the `completedFuture` synchronous-body invariant to the absence of concurrent access. Both restate the new contract precisely: concurrency is confined to `scatter`'s bounded workers, each owning one keyed connection single-threaded through `dslFor`, with the dispatch thread blocked on the join; the synchronous-body invariant and per-connection single-threading both still hold, for the revised reason.

### Failure semantics of the substrate

The substrate reports; the caller decides. Every tenant's work runs to completion or the deadline, and each ends as exactly one `Outcome`: `Success`, `Failed` (the worker threw; the cause is carried, never swallowed), or `TimedOut` (the deadline passed first). Nothing is dropped silently at this layer: a failed or timed-out tenant is present in the returned list, so a caller that chooses to error on any non-success can, and a caller that chooses softer surfacing (composing with the typed-errors plan) also can. What the substrate guarantees unconditionally: every pinned connection is released through the existing first-failure-wins `releaseAll` path at operation end regardless of outcome mix, and an empty result from a tenant (RLS row-scoping legitimately yields nothing) is a `Success` carrying an empty value, distinct from `Failed`; conflating the two is exactly the incomplete-presented-as-complete confusion R45's error posture exists to prevent.

### Considered and deferred: full async fetchers

The alternative design, worked through from first principles before this substrate settled (2026-07-22), is to make emitted fetcher and batch-loader bodies genuinely async at the per-tenant statement-execution seam, so cross-tenant parallelism emerges from R45's loader-name partition for *every* tenant-heterogeneous shape rather than only at marked fan-out fields. The session constraint draws the same scope line for both designs: statement concurrency requires connection concurrency, every fresh connection must start a session before executing queries, and the minimum session cost of an operation is one mount per distinct source touched. Cross-tenant work already pays one mount per tenant whether serial or parallel, so overlapping the per-tenant lanes adds zero session cost; intra-source parallelism would pay extra mounts and pool slots for gains DataLoader batching has mostly eaten, and is out of scope under either design. Pin-per-operation survives untouched under either design.

Full async is deferred, not rejected, because the evidence localizes: the measured-too-slow shape is the controlled fan-out point, which the confined helper covers, while full async would retire the synchronous-body invariant across the runtime, introduce per-lane ordering (at most one statement in flight per pinned connection) as a new load-bearing invariant with its own enforcer, and permanently raise the concurrency bar for all future runtime work. Confinement keeps the serial reasoning model everywhere except inside `scatter`.

The accepted cost of confinement, named so it is a decision and not an oversight: R45's per-row multi-tenant dispatch shapes (node and `_entities` batches spanning tenants) stay serial under this item. Their typical tenant degree is small, so the serial penalty is minor today. The trigger for revisiting full async is evidence that those shapes, or a proliferation of fan-out sites, are measurably slow in production (the instrumentation is already tenant-keyed); the design recorded here is the starting point for that item, and the scatter contract is compatible with it (a caller composing futures would supersede the blocking join without changing the pinning, outcome, or release semantics).

### Substrate tests

Every concurrency invariant gets a named enforcer in the substrate slices; none waits for the arm and emitter slices' schema.

- **Generator tier:** pin the emitted runtime code: the scatter method and `Outcome` taxonomy, the concurrent pinning structure, the constructor configuration surface, the `multiTenant` gate (single-tenant builds byte-identical, mirroring R45's single-tenant baseline discipline).
- **Direct-carrier concurrency proofs** (compile-and-run over the emitted `TenantConnections`, the same tier that proved the routing surface directly with test-supplied keys and a fake tenant map): a deterministic bounded executor plus latch-based `perTenant` bodies pin the cap (never more than N workers in flight), the deadline (`TimedOut` outcomes for workers past it), failure isolation (one throwing worker yields `Failed` with its cause while siblings still succeed), outcome order matching key iteration order, per-key single acquisition under contention (many workers, same key, exactly one pin), the re-entrancy guard (a `perTenant` body calling `scatter` throws immediately), and the straggler contract (a latch-held worker past the deadline: scatter returns `TimedOut`, `releaseAll` releases settled tenants and aborts the straggler's connection without closing it under the live statement, and the worker's eventual completion neither throws into the void nor leaks the connection).
- **Execution tier (sakila, database-per-tenant PostgreSQL):** direct calls against the generated carrier over real tenant databases: genuinely parallel execution under the cap, per-tenant session state intact under concurrency, empty-result tenants come back `Success`-empty and distinct from `Failed`, and `releaseAll` after a concurrent scatter leaks nothing (assert via connection counts). The through-the-schema execution proofs (a fanned field end to end) belong to the arm and emitter slices.

## Open questions for the Spec pass

1. **Claims extraction seam.** The domain is resolved (map keys intersected with the user's role-bearing tenantIds, above); what remains is how graphitron reads the tenant set out of the claims. Candidates: the consumer derives a collection-typed contextArgument (e.g. `Set<Long> tenantRoles`) from the JWT before calling the factory, keeping graphitron claims-format-agnostic; or graphitron takes a claims-map contextArgument plus a configured extraction. The pre-derived contextArgument is the lighter seam and matches how the hand-written resolver already reads `institusjonsroller`. This seam also owns relevance-scoping: when claims legitimately span more tenants than this subgraph hosts, the consumer narrows the derived set here, which is what keeps the missing-tenant error above meaningful.
2. **Marker syntax.** Directive vs contextArgument-driven; reconcile with R45's inference posture (fan-out cannot be inferred, it must be asked for).
3. **Result semantics.** Ordering across the union; pagination and `@asConnection` over a fanned-out field; per-tenant partial failure (null-drop vs error surfacing, composing with the typed-errors plan).
4. **Parallelism bounds.** Resolved in the execution substrate section above (absorbed from discarded R510): the bounded executor, thread-safe `TenantConnections` pinning, scatter/join helper, concurrency cap, and timeout are spec'd there and land as this item's leading slices. The remaining Spec work must not re-open the substrate's decisions; its open sub-question (configuration shape and defaults) rides along.

## Siblings

- **R45** (Done, recorded in [`changelog.md`](changelog.md)): the `TenantBinding` axis this item adds its arm to.
- **R505** ([`tenant-index-parent-row-routing.md`](tenant-index-parent-row-routing.md)): the tenant-index routing that later narrows this item's fan-out to the tenants that actually hold data, making fan-out the fallback rather than the default.
- **R429** (Done, recorded in [`changelog.md`](changelog.md)): acquisition, transaction demarcation, session state, and the threading rules the scatter substrate builds on without changing their ownership.
- **R510** (Discarded, recorded in [`changelog.md`](changelog.md)): the separate substrate item, absorbed wholesale into the execution substrate section above once it was clear this item is scatter's first and likely only caller.

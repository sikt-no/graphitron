---
id: R1154
title: "Refuse acquisition after release in the single-tenant connection carrier"
status: In Progress
bucket: bug
priority: 2
theme: runtime-connection
depends-on: []
created: 2026-11-05
last-updated: 2026-11-06
---

# Refuse acquisition after release in the single-tenant connection carrier

## Goal

In a single-tenant build, code that asks for a database connection after its operation has already given its connections back is refused, instead of being handed a fresh connection that nothing will ever return. Today that late request borrows a connection from the pool, mounts the caller's identity on it, and keeps it forever: the connection is never unmounted and never returned. The direct cost is one lost connection per occurrence and, eventually, an exhausted pool. With a pool that reclaims abandoned connections and puts them back into use, the mounted identity could also reach another borrower. Multi-tenant builds already refuse such requests. When this lands, single-tenant builds behave the same way: every connection the runtime pins is unmounted and returned, or discarded, however late the request for it arrives.

## Why it happens

Terms. On the owned-connection path (the engine `GraphitronRuntime.newGraphQL(schema)` builds), each operation gets a *carrier*, the generated `TenantConnections` class, which holds the operation's connections. A fetcher that needs the database calls `dslDefault()` (or `dslFor(key)` in multi-tenant builds). On first demand the carrier *pins* a connection: it borrows one from the pool and keeps it for the rest of the operation. If a session identity is configured, the carrier also *mounts* that identity on the connection by running the consumer's mount hook. At the end of the operation `releaseAll()` *releases* each pinned connection: it unmounts the identity and returns the connection to the pool, or evicts the connection if the unmount failed.

`ConnectionRuntimeClassGenerator.tenantConnections` emits two shapes of `releaseAll()`, chosen by `multiTenant`:

- **Multi-tenant** sets a volatile `closed` flag first, then drains the map key by key with `entries.remove(key)`, so each entry is processed by exactly one thread. `entryFor`, the one method every acquisition goes through, checks `closed` after its `computeIfAbsent`. If the carrier closed while the pin was in flight, it removes the entry, calls `PinnedConnection.abort()` on it and throws.
- **Single-tenant** has no flag. It iterates `entries.values()`, releases each entry, then calls `entries.clear()`. `entryFor` has no late check, and `PinnedConnection.abort()` is not emitted at all (`pinnedConnection` adds it only when `multiTenant`).

That leaves two windows in single-tenant builds:

1. **Acquisition strictly after release.** The map is empty, so `computeIfAbsent` pins and mounts a new connection, and nothing will release it.
2. **Acquisition during release.** A `computeIfAbsent` that lands between the iteration and `entries.clear()` mints an entry. `clear()` then drops it without release, while the caller goes on to use the connection.

Either window needs code that is still running after its operation ended. Two sources produce it: a cancelled or aborted execution (R1158 moves release so that it runs on those paths too), and consumer code that keeps running asynchronously, such as a service fetcher whose future outlives the operation. Fetchers that Graphitron emits for single-tenant builds run synchronously, so they never produce it.

One smaller defect sits beside these. `PinnedConnection.released` is a plain `boolean`, checked and then set. Two threads that call `release()` on the same instance at the same time can both pass the check, so the identity is unmounted twice and the connection closed twice. `abort()` is `synchronized`, but `release()` is not, and the two do not share a lock.

## Implementation

All in `ConnectionRuntimeClassGenerator`. The change removes the single-tenant branches rather than adding guards beside them, so both topologies emit one close-then-drain shape.

- **`closed` in both topologies.** `tenantConnections` adds `closedField` unconditionally (today only inside `if (multiTenant)`, next to `timedOutTenants`). Its javadoc is rewritten from "a straggler worker" to any acquisition that completes after release, because single-tenant builds have no scatter workers.
- **One `releaseAll()`.** Every build sets `closed = true`, then drains with `entries.remove(key)` per key, releasing each entry it removes and keeping the existing rule that collects the first failure and rethrows it after the loop. The `timedOutTenants` abort branch stays multi-tenant only. The single-tenant `for (entry : entries.values()) ... entries.clear()` branch is deleted. The emitted javadoc says the drain removes each entry exactly once, which is what makes a second call a no-op, replacing "the map is cleared". The multi-tenant comment about `remove()` arbitrating each entry against a straggler is reworded to apply to both topologies.
- **`entryFor` refuses before pinning.** A new check, placed just before the `computeIfAbsent`, so after the multi-tenant authorization and timeout refusals and after the remembered-mount-failure rethrow, throws `IllegalStateException` when `closed` is already set. The request in window 1 then borrows nothing and mounts nothing. This check is an optimisation, not the guard: it cannot see a pin that started before `closed` was set.
- **`entryFor` aborts a pin that raced release.** The existing post-`computeIfAbsent` block is emitted in both topologies. On a set `closed` it removes the entry, aborts it if this thread won the removal, and throws. Only multi-tenant builds keep the `timedOutTenants` half of the condition. The thrown message is per topology. Multi-tenant keeps its message ("pinned after its scatter deadline or after the operation completed"). Single-tenant says a connection was requested after the operation had released its connections, with no `Optional.empty` key in the text. This check is the guard for window 2. Ordering is what makes it sound: the minting thread publishes its entry before reading `closed`, and `releaseAll()` writes `closed` before it starts iterating. So either the drain sees the entry and releases it (the late thread's `remove` then returns `null`, and it throws without having used the connection), or the late thread sees `closed` and processes the entry itself.
- **Abort, not release, for a raced pin.** A second thread may have received the same entry from `computeIfAbsent` and could be running a statement on it. Winning `remove` decides which thread processes the entry, but it does not prove the connection is idle. `abort()` evicts through `Connection.abort` without running the unmount, which is the primitive that is safe under a live statement. The cost is one discarded physical connection, and it is paid only on that race. Window 1 never borrows thanks to the pre-pin check.
- **`abort()` in both topologies.** `pinnedConnection` adds `abortMethod()` unconditionally. Its javadoc and the generator comment above `abortMethod` are generalised from "a scatter straggler past the join deadline" to a connection another thread may still be using: a straggler past its deadline, or a pin that completed after release.
- **`released` becomes an `AtomicBoolean`.** `release()` and `abort()` both start with `if (!released.compareAndSet(false, true)) return;`. `abort()` drops `synchronized`, because the compare-and-set is now what decides which call runs. Inside the carrier the per-key `remove` already stops one entry from reaching two processors. The atomic flag makes `PinnedConnection`'s own documented promise ("idempotent, so a redundant cancel-then-complete release unmounts exactly once") hold for any caller, not only callers that go through the carrier.
- **Class javadoc.** The `tenantConnections` generator javadoc and the emitted single-tenant carrier javadoc gain one sentence: release closes the carrier, and any acquisition after that point is refused. Neither cites a roadmap item.

What this does not change: a connection already handed to a fetcher before release can still be in use while release unmounts it. Closing the carrier refuses new acquisitions. It does not wait for, or fence off, acquisitions that were granted earlier. That holds in both topologies today, and it belongs to the in-flight half of R1158's story, not to this item.

## Tests

- **Unit tier, single-tenant: `TenantConnectionsGeneratorTest`.** It already compiles the single-tenant carrier with recording hooks and records each lifecycle event (`getConnection`, `connect` for the mount, `disconnect` for the unmount, `close`, `abort`); the expectations below use those names.
  - *Acquisition after release.* Pin, `releaseAll()`, then `dslDefault()`: the second call throws `IllegalStateException`, and the events show no second `getConnection` and no second `connect`. This fails today: both happen, and no `disconnect` or `close` ever follows.
  - *Pin in flight across release.* A `DataSource` whose `getConnection` blocks on a latch. Thread A calls `dslDefault()` and parks inside the pin. `releaseAll()` runs on a second thread with a timeout, because the old emission's `entries.clear()` waits on the bin that `computeIfAbsent` holds and would otherwise hang the test. Then the latch opens. Expected: A's call throws `IllegalStateException`, and the events are exactly `getConnection`, `connect`, `abort`, with no `disconnect` and no `close`.
  - *Idempotence.* `releaseAll()` twice yields one `disconnect` and one `close`. If an existing case already pins this, keep it as the regression check for the drain rewrite rather than adding another.
- **Unit tier, multi-tenant: `TenantScatterSubstrateTest` (or whichever multi-tenant harness fits best).** The same two cases through `dslFor(key)`. The `closed` guard is currently exercised only through the scatter deadline path, and after this change one emitted code path serves both topologies, so both should pin it.
- **Unit tier, `PinnedConnection`.** `release()` then `abort()` on one instance closes once and never evicts. `abort()` then `release()` evicts once and never unmounts. Both run single-threaded: the compare-and-set race itself is not deterministically testable, and this case only pins that the two methods share one flag.
- **Execution tier, through a real pool.** R1153's suite names this case as a gap. End to end, the window needs a consumer fetcher that is still running when its operation releases, and on trunk only a cancellation produces that once R1158 releases on abort. The case: on a pool of one, a consumer service fetcher waits on a latch and the query is cancelled. After release the fetcher calls `dslDefault()` and is refused. The next request's borrow returns rather than blocking, and it reads the session identity as empty. Whichever of R1153, R1154 and R1158 lands last adds this case.

## Documentation

`docs/architecture/reference/runtime-extension-points.adoc`, owned-connection section, after "then runs your unmount callback and releases the connection": add that a request for a connection after the operation has released, for example from a consumer fetcher still running after the operation was cancelled, is refused with an `IllegalStateException` rather than given a new connection. A consumer with asynchronous fetchers then knows what that late call does.

## Other solutions we've considered

- **Add the flag but keep `entries.clear()`.** This closes window 1 but not window 2: `clear()` still drops an entry that was minted mid-drain, without releasing it. Removing key by key is what lets exactly one thread own each entry.
- **Only check `closed` before pinning.** A pin that started before the flag was set completes afterwards and is never seen. The check after `computeIfAbsent` is the one that makes this correct, and the one before it only saves a pointless borrow.
- **Release the raced pin instead of aborting it.** That would return a possibly busy connection to the pool and run an unmount concurrently with someone else's statement. The multi-tenant carrier aborts for exactly this reason.
- **Synchronise `entryFor` and `releaseAll` on one lock.** That is simpler to reason about, but every acquisition would serialise on it, including multi-tenant scatter workers that today pin their keys concurrently. The flag and removal shape is lock-free and already in production in the multi-tenant carrier.

## Context

Found in an adversarial review of the owned-connection session lifecycle. Siblings: R1153 (the execution-tier suite this item owes a case), R1158 (release on synchronous abort, the main source of post-release fetchers; no dependency in either direction, since the two edit different generators), R1151 and R1152 (other leaks found in the same review).

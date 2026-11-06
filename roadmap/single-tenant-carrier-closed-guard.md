---
id: R1154
title: "Refuse acquisition after release in the single-tenant connection carrier"
status: In Review
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

Shipped at `d76fac61`, all in `ConnectionRuntimeClassGenerator`, as planned: both topologies emit one close-then-drain carrier. `closed` and `PinnedConnection.abort()` are emitted unconditionally; `releaseAll()` sets `closed` and drains by per-key `remove`, with the `timedOutTenants` abort branch still multi-tenant only; `entryFor` refuses before pinning once closed, and after `computeIfAbsent` removes, aborts and throws for a pin that completed after release; `released` is an `AtomicBoolean` that `release()` and `abort()` share, and `abort()` is no longer `synchronized`.

Two details the plan left open, settled in flight:

- **Refusal messages.** The refusal before pinning reads "A connection was requested after the operation had released its connections." in both topologies, with " for tenant '<key>'" after "A connection" when the key is present, so neither an `Optional.empty` nor an `Optional[...]` reaches the text. The refusal after a raced pin keeps the multi-tenant message ("Key '…' was pinned after its scatter deadline or after the operation completed.") and uses the same no-key message in single-tenant builds.
- **`TenantRuntimeKeyTypeTest`** pinned `synchronized void abort()` as present in multi-tenant and absent in single-tenant builds. Both now pin `public void abort()` as present.

What this does not change: a connection already handed to a fetcher before release can still be in use while release unmounts it. Closing the carrier refuses new acquisitions. It does not wait for, or fence off, acquisitions that were granted earlier. That holds in both topologies, and it belongs to the in-flight half of R1158's story, not to this item.

## Tests

Acceptance evidence, all landed in `d76fac61`:

- **`TenantConnectionsGeneratorTest`** (single-tenant carrier): `acquisitionAfterRelease_isRefused_withoutBorrowingOrMounting` (no event at all after the refusal, for `dslDefault()` and for a never-pinned key), `pinInFlightAcrossRelease_isAbortedAndRefused` (events exactly `getConnection`, `connect`, `abort`; `releaseAll()` runs bounded on a second thread while the pin is parked, and only then is the pin let through), `releaseAll_twice_unmountsAndReturnsEachConnectionOnce`, `pinnedConnection_releaseThenAbort_returnsOnceAndNeverEvicts` and `pinnedConnection_abortThenRelease_evictsOnceAndNeverUnmounts`.
- **`TenantScatterSubstrateTest`** (multi-tenant carrier): the same two cases through `dslFor(key)`.
- **`SessionIdentityPoolIsolationExecutionTest`** (real Agroal pool of one): `fetcherThatAsksForAConnectionAfterACancelReleased_isRefused_andTheNextBorrowIsClean`. A test instrumentation replaces the fetcher of root field `late` with one that returns a pending future and asks for `TenantConnections.dslDefault(env)` on another thread only after the cancel at `b`'s dispatch has released. The call is refused with the single-tenant message, the pool's acquire count stays at one, and the next borrow returns and reads no identity. The suite's class javadoc no longer lists this gap; the rejected-mount gap stays.

Verification: full `mvnd install -Plocal-db` on the rebased tree, upstream modules green, then `-rf :graphitron` after test fixes, with every module green except `GraphQLQueryTest.rentFilmPayloadNested_dotPathArgMappingReachesTheRoutine`, a shared-`rental` flake of the kind R1121 tracks, which passed on a rerun of the class (538/538).

## Documentation

Shipped: `docs/architecture/reference/runtime-extension-points.adoc`, owned-connection section, says that once the operation has released it takes no new connection, and that a late request, for example from a consumer fetcher still running after a cancel, is refused with an `IllegalStateException`.

## Other solutions we've considered

- **Add the flag but keep `entries.clear()`.** This closes window 1 but not window 2: `clear()` still drops an entry that was minted mid-drain, without releasing it. Removing key by key is what lets exactly one thread own each entry.
- **Only check `closed` before pinning.** A pin that started before the flag was set completes afterwards and is never seen. The check after `computeIfAbsent` is the one that makes this correct, and the one before it only saves a pointless borrow.
- **Release the raced pin instead of aborting it.** That would return a possibly busy connection to the pool and run an unmount concurrently with someone else's statement. The multi-tenant carrier aborts for exactly this reason.
- **Synchronise `entryFor` and `releaseAll` on one lock.** That is simpler to reason about, but every acquisition would serialise on it, including multi-tenant scatter workers that today pin their keys concurrently. The flag and removal shape is lock-free and already in production in the multi-tenant carrier.

## Context

Found in an adversarial review of the owned-connection session lifecycle. Siblings: R1153 (the execution-tier suite this item owes a case), R1158 (release on synchronous abort, the main source of post-release fetchers; no dependency in either direction, since the two edit different generators), R1151 and R1152 (other leaks found in the same review).

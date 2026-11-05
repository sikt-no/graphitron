---
id: R1159
title: "A rejected mount poisons the pool: abort() eviction hands the dead connection to the next borrower"
status: Backlog
bucket: bug
priority: 2
theme: runtime-connection
depends-on: []
created: 2026-11-05
last-updated: 2026-11-05
---

# A rejected mount poisons the pool: abort() eviction hands the dead connection to the next borrower

## Goal

When Graphitron evicts a connection from the consumer's pool, the pool discards it, so the next borrower gets a live connection. Today it does not, at least on Agroal (the pool Quarkus deploys with): a rejected mount, or an unmount that throws, leaves a dead connection in the pool and the next request fails on it.

## What happens today

`PinnedConnection.evict` calls `Connection.abort(executor)` on the pool's wrapper, on the premise that pool wrappers discard the physical connection rather than reclaim it. Agroal 3.2.1's `ConnectionWrapper.abort` aborts the physical connection and then calls `ConnectionHandler.onConnectionWrapperClose`, the ordinary return path; it marks the handler flush-only only when the physical abort itself throws. So the pool keeps a handler whose physical connection is closed, its `destroyCount` does not move, and with no validation on borrow configured the next `getConnection` hands that handler out.

Observed while implementing the real-pool isolation suite, on an Agroal pool of one as a non-superuser role: a request whose mount was rejected (`sub = "reject-me"`) threw `SessionMountException` as specified; the pool then read `acquireCount` +1, `destroyCount` +0, `creationCount` unchanged; and the next request (a different user, a plain read) answered `data: null` with one redacted error, its logged cause `RuntimeException` over `PSQLException: This connection has been closed.` Whether that next failure evicts again in turn, keeping a small pool poisoned until the pool's own background validation (if configured) removes the handler, is not yet established.

Identity is not at risk: the physical database session is closed, which the suite pins (the backend exits). The cost is availability, a rejected caller making the next caller's request fail, and the eviction premise stated in the emitted code's comment does not hold for this pool.

## Notes for the Spec

- Both `evict` call sites are affected: the rejected mount in `acquire` and the throwing unmount in `release`.
- What other pools do with `abort()` on a wrapper (HikariCP in particular) is unverified; the fix should not assume a pool-specific API is available, and JDBC has no standard "evict this connection" call.
- When this lands, `SessionIdentityPoolIsolationExecutionTest`'s rejected-mount case owes the assertions it currently leaves out: the pool's destroy count rises by one, and the next request on the same pool succeeds with only its own rows. Its class javadoc names this gap.

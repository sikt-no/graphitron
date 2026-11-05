---
id: R1155
title: "Fix the escape-hatch identity example, which mounts nothing in autocommit"
status: Backlog
bucket: bug
priority: 2
theme: runtime-connection
depends-on: []
created: 2026-11-05
last-updated: 2026-11-05
---

# Fix the escape-hatch identity example, which mounts nothing in autocommit

## Goal

The escape-hatch identity example in the docs works, and following it cannot leak identity across pooled connections. The example in `docs/architecture/reference/runtime-extension-points.adoc` ("On the escape-hatch path") runs `SELECT set_config('app.user_id', ?, true)` on a freshly borrowed connection in autocommit. With `is_local = true` the setting lasts for that statement's implicit transaction only, so every later query runs with no identity: fail-closed policies show the caller nothing. The obvious repair is to flip the flag to `false`, and then the identity stays on the connection after it goes back to the pool, because the escape-hatch path has no unmount. When this lands the example either wraps the request in one transaction and keeps `is_local = true`, or sets session scope and clears it in a `finally` before closing, and says why.

## Context

Found in an adversarial review of the owned-connection session lifecycle. While in the area, reconcile the hook state contract's wording with the code: the doc says acquisition "asserts the connection arrives in autocommit and fails closed if it does not", while the emitted `PinnedConnection.acquire` calls `setAutoCommit(true)`, which under JDBC commits any transaction the pool handed over open. Either the code checks and evicts, or the doc says it forces the mode. A related user-manual gap: the mount runs on whichever thread first needs a connection, so a `SessionState` that reads identity from ambient state (a thread-local security context) when the mount runs can see another request's caller; the `SessionState` javadoc warns, the manual's session identity section should too.


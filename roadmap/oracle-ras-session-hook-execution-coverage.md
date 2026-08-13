---
id: R468
title: "Oracle/RAS execution-tier coverage for session identity hooks"
status: Backlog
bucket: architecture
priority: 3
theme: runtime-connection
depends-on: []
created: 2026-07-10
last-updated: 2026-07-10
---

# Oracle/RAS execution-tier coverage for session identity hooks

Session identity mounts through the `<sessionState>` method hooks (a static `<mount>` method whose return value is the handle later passed to `<unmount>`), with execution-tier proof on Postgres only; Oracle stays unit-tier because the build has no Oracle container. The Oracle worked example is the load-bearing one for Sikt's kernel API (definer-rights package, VPD institution context, RAS `CREATE_SESSION`/`ATTACH_SESSION` with the session id as the handle, detach/destroy by handle on unmount), so the mount/unmount contract, handle capture and rebinding, and the outside-any-transaction invariant (autocommit asserted at acquisition, mount running as committed session state) should each be proven against a real Oracle database once a container (or an external test target, like the `test.db.url` seam the Postgres tiers already use) is available. Originally a follow-on to the shipped connect/disconnect callable form; the method-hook rework keeps the Oracle gap open and this item remains its tracking home.

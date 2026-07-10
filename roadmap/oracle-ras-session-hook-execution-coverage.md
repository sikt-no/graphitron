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

R429 shipped the session identity hooks (connect/disconnect from `<sessionState>`, handle threading, the per-settle re-fire fallback for hooks without `<stateSurvivesTransactions>`) with execution-tier proof on Postgres only; Oracle stays unit-tier because the build has no Oracle container. The Oracle worked example is the load-bearing one for Sikt's kernel API (definer-rights package, VPD institution context, RAS `CREATE_SESSION`/`ATTACH_SESSION` with the session id as the OUT handle, detach/destroy by handle on disconnect), so the two-hook contract, handle capture and rebinding, the outside-any-transaction invariant, and the re-fire fallback should each be proven against a real Oracle database once a container (or an external test target, like the `test.db.url` seam the Postgres tiers already use) is available. Named as a follow-on in R429's slice 3; this item is its tracking home now that the R429 spec is deleted.

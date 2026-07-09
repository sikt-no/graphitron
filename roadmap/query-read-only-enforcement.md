---
id: R460
title: "Targeted read-only enforcement for query paths graphitron does not control (@routine, @service)"
status: Backlog
bucket: architecture
priority: 5
theme: service
depends-on: []
created: 2026-07-09
last-updated: 2026-07-09
---

# Targeted read-only enforcement for query paths graphitron does not control (@routine, @service)

## In one paragraph

R429 originally ran every query operation inside a read-only transaction (`SET TRANSACTION READ ONLY`, DB-enforced) so a query "literally cannot write". That guarantee costs a per-request round trip (the demarcation, plus the trailing commit), which on a high-latency database measured ~15ms per round trip on one Sikt subgraph. The cost/benefit does not hold up for the common path: graphitron's generated query fetchers only ever emit `SELECT`, so read-only enforcement is guarding against a write those fetchers cannot produce. The only query surfaces where graphitron genuinely does *not* know whether a write can happen are the two escape hatches to code it did not generate: `@routine` (an arbitrary database routine, which may be `VOLATILE` / may write) and `@service` (consumer Java that receives the pinned-connection `DSLContext` and may issue any SQL). R429 therefore drops blanket read-only enforcement; this item re-introduces it *narrowly*, only where the SQL is uncontrolled, and/or offers zero-per-request-cost realizations for consumers who still want a broad read-only guarantee.

## Why this is split out of R429

R429 owns the connection lifecycle: one connection pinned per operation, identity mounted through the connect hook, mutation fields committing per field through the custom `TransactionProvider`'s commit-policy axis. None of that needs a read-only transaction. Blanket read-only enforcement is a separable concern with its own cost/benefit and its own design space (below), so it does not belong on R429's critical path. R429 keeps queries in autocommit on the pinned connection (no begin/commit round trips); this item decides if and how read-only is layered back on.

## The insight that scopes it

Read-only enforcement is only load-bearing where graphitron cannot prove the SQL is a read:

- **Generated query fetchers** emit `SELECT` only. Known read-only; no enforcement needed.
- **`@routine` on a query field** calls a database routine graphitron does not author. It may write.
- **`@service` on a query field** calls consumer Java that gets the pinned `DSLContext`. It may write.

So the precise question is not "make queries read-only" but "guard the uncontrolled read surfaces (`@routine`, `@service`) without taxing the controlled ones."

## Design options explored (during R429 slice-2 discussion; none chosen yet)

These are candidates for the Spec, not decisions:

- **Per-request demarcation, made cheaper.** Keep the read-only transaction but fold `SET TRANSACTION READ ONLY` into a single `START TRANSACTION READ ONLY` (Postgres) so it is demarcation + commit rather than demarcation + set + commit. Removes one round trip; the commit round trip remains. On Oracle the read-only transaction additionally buys true transaction-level read consistency, so the axis should stay dialect-aware rather than being ripped out unconditionally.
- **Strategy knob (runtime-configured, no regeneration to flip).** `TRANSACTION` (enforce per request, safe default), `NONE` (autocommit, fastest, relies on RLS + SELECT-only codegen), `INHERIT` (assume the connection is already read-only). It is a deployment choice, not a schema choice, so it belongs at `Graphitron.runtime(...)` construction, not in the Mojo config.
- **Read/write pool split.** An optional `readDataSource` alongside the required `writeDataSource`: query operations route to the read pool, mutations to the write pool; the instrumentation already knows the operation type at operation start. The read pool is read-only *by construction* (a read replica, or a second pool at the same primary opened under a read-only role / `connectionInitSql = SET SESSION CHARACTERISTICS AS TRANSACTION READ ONLY`), so the guarantee costs zero per-request round trips. A single already-read-only pool is not enough for a mixed subgraph, because mutations cannot run on it; the split is what makes zero-cost read-only work when the subgraph both reads and writes. Caveats: larger total connection budget; a replica read pool introduces replication lag (read-your-writes within a single mutation operation is preserved because the mutation and its read-back both stay on the write pool, but a query in a *later* operation may not see a just-committed write); if the read pool is a replica, the connect hook's session-state must be replica-safe (`set_config` is; Oracle RAS needs Active Data Guard).
- **Targeted-only enforcement.** Detect whether a query operation reaches any `@routine`/`@service` field and demarcate read-only only then, leaving pure-`SELECT` query operations in autocommit. Narrowest cost, but the read-only demarcation is operation-scoped while the trigger is field-scoped, so this needs care (an operation mixing generated reads and an `@routine` read would demarcate the whole operation).

## Relationship to other items

- **R429 (connection-transaction-lifecycle).** Parent. R429 drops blanket read-only enforcement and keeps queries in autocommit on the pinned connection; this item is the follow-on that re-introduces read-only where it earns its cost. The custom `TransactionProvider`'s commit-policy axis (`COMMIT`/`ROLLBACK_ONLY`) and the per-operation instrumentation seam R429 lands are the substrate this item builds on.
- **R45 (operation-divined tenant routing).** The read/write pool-split option turns the runtime into a router over `DataSource`s keyed on operation type, which is the same shape R45 introduces keyed on tenant. The two axes ultimately compose (a tenant with its own read + write pools). If the pool-split option is taken, its seam must be defined so R45's tenant routing composes rather than forking a second router.

## Non-goals (carried from the discussion)

- Changing generated query fetchers (they are `SELECT`-only and need no enforcement).
- Any read-only concept for mutation operations (they need writable per-field transactions regardless).

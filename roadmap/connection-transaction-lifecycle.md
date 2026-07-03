---
id: R429
title: "Graphitron owns connection and transaction lifecycle: operation-typed read-only/commit semantics and RLS-first session state"
status: Spec
bucket: architecture
priority: 3
theme: service
depends-on: []
created: 2026-07-03
last-updated: 2026-07-03
---

# Graphitron owns connection and transaction lifecycle: operation-typed read-only/commit semantics and RLS-first session state

## In one paragraph

Today the generated runtime has **no opinion** on connections or transactions: `Graphitron.newExecutionInput(DSLContext defaultDsl, …)` (R190) takes a fully-built `DSLContext` from the consumer, stashes it in the `graphQLContext`, and every fetcher runs on it with whatever transaction and session state the consumer happened to set. That is both a missed safety guarantee and, given how graphitron works, a latent hazard: graphitron **maps the database directly** into a GraphQL read surface, so absent row-level enforcement the generated API is an unguarded `SELECT *` over the schema. This item gives graphitron a strong, opinionated stance on the connection lifecycle: graphitron takes a **`DataSource`** (owning connection acquisition), derives the **transaction mode from the operation type** (queries read-only, mutations commit then project read-only), and sets **RLS/RAS session state transaction-local** from the request's contextArguments, with built-in strategies for Postgres and Oracle plus a generic escape hatch. The linchpin is that owning the transaction boundary is what makes transaction-local session state, and therefore *safe RLS over a pooled connection*, possible at all; a consumer-provided `DSLContext` cannot guarantee it because it does not own the boundary.

## Why graphitron should own this (the load-bearing argument)

The read-only/commit semantics, the session state, and the pooling safety are not three features; they are one, hinged on **transaction-local session state**. Postgres `set_config(key, value, true)` and Oracle's context/RAS equivalents scope the RLS variables to the *current transaction*, cleared automatically on commit or rollback. That is only correct if a single authority owns the transaction boundary:

- graphitron owns the transaction → it sets RLS state transaction-local → **no identity leaks across a pooled connection**, with no manual reset dance.
- A consumer-supplied `DSLContext` fundamentally cannot promise this, because transaction demarcation is not its to control.

So owning connection acquisition and transaction demarcation is the *enabling* decision, not a convenience. Everything else in this item hangs off it.

## The connection model (resolved)

- **Single tenant: graphitron takes a `DataSource`.** It acquires a connection per operation, builds the `DSLContext` with the configured dialect, demarcates the transaction, applies session state, and releases the connection. The consumer (or their framework) still owns *pool creation and configuration*; graphitron owns *acquisition, transaction, and state* on top of it.
- **Multi tenant, shared database (RLS partition): a single `DataSource`.** The tenant is just another session-state value (an RLS variable), routed through the same session-state mechanism. No per-tenant `DataSource`.
- **Multi tenant, database-per-tenant: a `Map<TenantId, DataSource>`.** graphitron resolves the tenant from the request context (the tenant selector arrives as a contextArgument, the same source as the RLS identity values), looks up the `DataSource`, then runs the identical transaction/session-state machinery against it. The two multi-tenant flavors compose: a per-tenant database *and* RLS within it.

The `TenantId` and the RLS identity values share one origin, the per-request contextArguments, so tenant selection and session state read from the same map the fetchers already see.

## Operation-typed transaction mode

- **Query → real read-only transaction.** `SET TRANSACTION READ ONLY`, which Postgres and Oracle *enforce* at the database (not merely the JDBC `Connection.setReadOnly` hint). RLS + enforced read-only means the generated read surface is safe by construction: a query literally cannot write, and sees only permitted rows.
- **Mutation → writable for the mutation root, commit, then read-only projection.** graphql-java runs top-level mutation fields *serially*; the leaning resolution is **per-top-level-mutation-field commit** (field 1 commits before field 2 begins, matching GraphQL's partial-success-with-errors semantics), after which the payload's selection set is resolved in a read-only transaction over the now-committed state (read-your-writes, safely). The exact boundary and its interaction with DataLoader dispatch is the spike below.

## Session-state strategies (the "do more")

The user decides *what* state to set; graphitron owns the plumbing and ships the common cases:

- **Postgres**: `set_config(key, value, true)` (transaction-local) per configured RLS variable.
- **Oracle**: the context/RAS session-attach wiring (`DBMS_SESSION.SET_CONTEXT` / RAS), transaction- or request-scoped as the dialect allows.
- **Generic**: the consumer supplies an ordered list of parameterized statements (or a hook), bound from contextArgs; the read-only enforcement here degrades to the JDBC hint where the dialect has no enforced form.

A session-state policy maps `contextArgs → statements`, run transaction-local immediately after connection acquisition and before the operation. **Read-only enforcement is a gradient**: enforced on Postgres/Oracle, hint-only on the generic path; documented as such so nobody assumes generic means enforced.

## RLS-assumed becomes an explicit principle

graphitron assumes the database enforces row access and makes that first-class; it **cannot enforce** that the consumer enabled RLS (that is a DB feature). This posture is written into `docs/architecture` as a stated principle, and the runtime emits a **startup warning when no session-state policy is configured**, so the assumption is loud rather than silently dangerous. Non-RLS consumers remain responsible for exposing only safe surfaces (views, restricted schemas); the warning names that responsibility.

## The contract change, and migration (the main cost)

This reopens the seam R190 deliberately settled and R45 built on. `newExecutionInput` moves from *"consumer hands us a `DSLContext`"* to *"consumer hands us a `DataSource` (or `Map<TenantId, DataSource>`) plus a session-state policy; graphitron builds the `DSLContext`, owns the transaction, sets the state."* Because R190 is freshly landed and has consumers (the sakila example), this needs an **additive path**, a `DataSource`-based factory alongside the existing `DSLContext` one, with the `DSLContext` form retained as a lower-opinion escape hatch (no graphitron-owned transaction, no session state, caller's responsibility) rather than removed outright. The migration story and whether the `DSLContext` form is deprecated or kept as a documented low-level seam is a Spec decision.

## Relationship to other items

- **R45 (multi-tenant routing, Ready).** R45's chosen seam is a `byTenant Function<T, DSLContext>` overload on the existing factory. This item **supersedes that mechanism**: tenant routing becomes `Map<TenantId, DataSource>` (DB-per-tenant) or an RLS session value (shared DB), and graphitron owns `DSLContext` creation rather than receiving one from a `byTenant` function. R45 is currently `Ready`; it should be **reopened to Spec** and reconciled against this item (or absorbed), since implementing R45 as written would bake in a seam this item replaces. Flag for the reviewer; do not implement R45 as-is in the meantime.
- **R190 (schema-driven ExecutionInput factory, landed).** The contract this item revises. See migration above.
- **R428 (MCP in-process query execution).** Becomes a **consumer** of this item: the dev tool feeds graphitron a `DataSource` built from its `GRAPHITRON_DEV_DB_*` config and exercises the same connection/transaction/session-state machinery an app does. R428's "rollback everything" is this item's transaction model with commit suppressed; R428's session-state fork (a)/(b) dissolves into this item's strategies. R428's `depends-on` is updated to include this item.
- **R410 (in-process incremental compile).** Independent; R428 sits on both.

## Slices and test tiers

1. **Connection/transaction runtime seam.** graphitron acquires from a `DataSource`, demarcates a transaction, releases; `@UnitTier` over a fake `DataSource` asserting acquisition/commit/rollback/close ordering.
2. **Operation-typed mode.** Query → read-only transaction; mutation → writable-then-read-only-projection. `@PipelineTier` on the emitted wiring; **spike** the mutation boundary vs graphql-java serial execution + DataLoader dispatch before pinning per-field vs per-operation commit.
3. **Session-state strategies.** Postgres `set_config(…, true)`, Oracle context/RAS, generic list; policy maps contextArgs → statements, transaction-local. `@ExecuteTier` (sakila, Postgres): an RLS-scoped read sees only permitted rows; transaction-local state does not leak to the next acquisition on the same pooled connection.
4. **Multi-tenant DataSource map.** Tenant resolved from contextArgs selects the `DataSource`; `@UnitTier` on routing + `@ExecuteTier` per-tenant isolation (reshapes R45's execute-tier coverage).
5. **Factory contract + migration.** Additive `DataSource` factory alongside the `DSLContext` form; `@PipelineTier` on the emitted facade; sakila example migrated to the `DataSource` form as the first client.
6. **RLS-assumed principle + warning.** Architecture-doc principle; startup warning when no session-state policy is configured. Docs + `@UnitTier` on the warning condition.

## Non-goals

- Creating or configuring the connection pool (the consumer's/framework's `DataSource` owns that).
- Enforcing that RLS is actually enabled on the database (impossible; graphitron assumes and warns).
- Per-tenant *dialect* variation in V0 (dialect is global config; note as a possible extension if a `Map<TenantId, DataSource>` ever spans dialects).
- Application hot-reload or distributed transactions.

## First-client user-doc draft

> **Graphitron manages the database connection for you.** You give graphitron a `DataSource` (single database) or a `Map<TenantId, DataSource>` (a database per tenant) and tell it the dialect. For every request graphitron takes a connection, runs your query in a **read-only transaction**, and hands it back, so a query can never write. A mutation runs in a writable transaction, commits, and then reads back its result read-only.
>
> **Row-level security is assumed.** Because graphitron maps your tables directly, you should be running row-level security (RLS on Postgres, RAS on Oracle). Tell graphitron which session variables carry the current user/tenant, taken from your query's context arguments, and it sets them transaction-locally on every connection, so each request sees exactly what its identity is allowed and nothing leaks between pooled connections. Graphitron ships this wiring for Postgres and Oracle; a generic form lets you supply the statements yourself. If you configure no session state, graphitron warns you at startup: an unsecured direct-to-database API is a data-exposure risk.

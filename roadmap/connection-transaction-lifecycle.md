---
id: R429
title: "Graphitron owns connection and transaction lifecycle: operation-typed read-only/commit semantics and RLS-first session state"
status: Spec
bucket: architecture
priority: 3
theme: service
depends-on: []
created: 2026-07-03
last-updated: 2026-07-05
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
- **Multi tenant, database-per-tenant: a `Map<TenantId, DataSource>`.** graphitron resolves the tenant key either from the request context (a contextArgument, the same source as the RLS identity values) or, per R45, divined from the operation's own arguments via tenant-column bindings (`emner(filter: { eierOrganisasjon: 1234 })` routes to tenant 1234's database). It looks up the `DataSource`, then runs the identical transaction/session-state machinery against it. **Demarcation rule (owned here, referenced by R45):** within one operation, all SQL for the same divined tenant shares one transaction; a query touching N tenants therefore runs N read-only transactions, each internally consistent per tenant. The two multi-tenant flavors compose: a per-tenant database *and* RLS within it.

The RLS identity values arrive as per-request contextArguments. The `TenantId` arrives the same way when the caller knows it up front, or is divined per field from the operation (R45's tenant-column bindings); either way session state for an acquisition binds the tenant that acquisition serves.

## Placement: generated into the output package (resolved)

The machinery is **emitted into the consumer's output package**, following the `GraphitronContext` precedent: emitted code depends only on the app's own build output plus jOOQ, graphql-java, and the JDK (`javax.sql.DataSource` is JDK). No new graphitron runtime artifact. The spec states the split explicitly so the choice is reviewable:

- **Schema-varying, genuinely generated:** the `DataSource` factory signature (contextArgument parameters, tenant-map overload per R45), and the session-state policy binding (which session variables map from which contextArgs).
- **Schema-invariant, emitted anyway:** the transaction-demarcation loop, the Postgres `set_config` strategy, the Oracle context/RAS wiring. These do not vary by schema; the criterion for emitting them rather than shipping a Java-17 runtime jar (the `graphitron-jakarta-rest` category) is the no-runtime-dependency invariant `GraphitronContextInterfaceGenerator` already documents: consumers of the generated code add no graphitron artifact to their runtime classpath, and version skew between generator and runtime cannot exist. If this invariant machinery grows past a few classes, revisit; at the V0 size the duplication cost is below the dependency cost.

Because the emitted machinery is substantial generated source, it must be **valid Java 17** (no Java 21+ syntax in emitted bodies), verified as always by the `graphitron-sakila-example` `<release>17</release>` compile.

## The enabling invariant: SQL within an operation is sequential

A single pinned connection per operation is safe **because generated batch loaders execute SQL synchronously on the dispatch thread**: `RowsMethodCall.batchLoaderLambda` emits `CompletableFuture.completedFuture(rows(keys, dfe))`, so no two fetches of one operation ever run SQL concurrently. This is load-bearing for the whole item; a future "make loaders async" change would break connection pinning. Prose alone does not protect an invariant, so slice 1 pins it mechanically: a test asserting the emitted loader body is the synchronous `completedFuture(...)` shape, plus a javadoc `{@link}` from the connection-lifecycle generator to `RowsMethodCall`, so going async fails a test instead of silently invalidating connection-per-operation.

## Operation-typed transaction mode

- **Query → real read-only transaction.** `SET TRANSACTION READ ONLY`, which Postgres and Oracle *enforce* at the database (not merely the JDBC `Connection.setReadOnly` hint). RLS + enforced read-only means the generated read surface is safe by construction: a query literally cannot write, and sees only permitted rows.
- **Mutation → commit (or roll back) each mutation field serially (resolved).** graphql-java runs top-level
  mutation fields serially, and graphitron matches that granularity: **each mutation field runs in its own
  writable transaction that commits on success and rolls back on failure, before the next field begins.**
  This is the decided boundary (not per-operation): it mirrors GraphQL's serial-mutation, partial-success-
  with-errors semantics, so a failing field 2 rolls back its own write while field 1's committed write
  stands. After a field commits, its payload selection set is resolved read-only over the now-committed state
  (read-your-writes, safely). The implementation care point (not a design fork) is aligning this per-field
  commit/rollback with graphql-java's DataLoader dispatch cycles; slice 2 verifies that alignment.

  **This rides the shipped DML shape.** `TypeFetcherGenerator`'s two-step DML fetchers already emit
  `dsl.transactionResult(tx -> ...)` per mutation field with a PK-only `RETURNING`; that call IS the
  per-field boundary, so for mutation operations graphitron pins a writable connection but opens no
  outer transaction, and the emitter keeps emitting plain `transactionResult`.

  **Slice-2 requirement (not a care point): the read-back projection gets its own read-only
  transaction.** Today the post-commit payload projection runs on a bare auto-commit `dsl` outside the
  write transaction. Under this item that is a hole, not a nicety: session state is transaction-local,
  so the write commit *clears* the RLS variables, and a bare read-back would execute with no identity,
  the exact leak this item exists to prevent, on the mutation path specifically. The read-back SELECT
  must run inside a demarcated read-only transaction that re-applies session state through the same
  begin-hook as every other transaction.

## One transaction boundary: the provider seam

Two needs land on the same mechanism, and the design keeps them as **one** seam rather than two: a custom jOOQ `TransactionProvider` (or `TransactionListener`) wrapped around the pinned connection owns every transaction boundary in an operation.

- **`begin` applies session state.** Session state is transaction-local, so it must re-apply at *every* transaction begin: once for a query's single read-only transaction, N times for a mutation's per-field transactions plus read-back projections, once per tenant-transaction under R45. Hooking begin in the provider means the emitters never carry session-state awareness.
- **`commit` honours the transaction mode**, a plain enum (no per-variant payload, so an enum, not a sealed hierarchy): `READ_ONLY`, `WRITABLE_COMMIT`, `ROLLBACK_ONLY`. `ROLLBACK_ONLY` is the named seam R428's rollback-everything dev tool consumes: `dsl.transactionResult(...)` inherently commits on normal return, so commit suppression *must* live in the provider that every emitted `transactionResult` already routes through; R428 sets the mode and touches no emission.

The alternative (a begin-hook for session state plus a separate mode switch threaded into emission) would put mode awareness into every emitted commit site; rejected.

## Session-state strategies (the "do more")

The user decides *what* state to set; graphitron owns the plumbing and ships the common cases:

- **Postgres**: `set_config(key, value, true)` (transaction-local) per configured RLS variable.
- **Oracle**: the context/RAS session-attach wiring (`DBMS_SESSION.SET_CONTEXT` / RAS), transaction- or request-scoped as the dialect allows.
- **Generic**: the consumer supplies an ordered list of parameterized statements (or a hook), bound from contextArgs; the read-only enforcement here degrades to the JDBC hint where the dialect has no enforced form.

A session-state policy maps `contextArgs → statements`, run transaction-local at every transaction begin (via the provider seam above), not once at acquisition. **Read-only enforcement is a gradient**: enforced on Postgres/Oracle, hint-only on the generic path; documented as such so nobody assumes generic means enforced.

## RLS-assumed becomes an explicit principle

graphitron assumes the database enforces row access and makes that first-class; it **cannot enforce** that the consumer enabled RLS (that is a DB feature). This posture is written into `docs/architecture` as a stated principle, and the runtime emits a **startup warning when no session-state policy is configured**, so the assumption is loud rather than silently dangerous. Non-RLS consumers remain responsible for exposing only safe surfaces (views, restricted schemas); the warning names that responsibility.

**Warning scope (resolved):** the no-session-state warning applies to the `DataSource` path, where graphitron owns the boundary and could have set state but was not told what to set. The `DSLContext` escape hatch instead emits a **one-time caller-owns-transactions-and-RLS notice at wiring time**; warning it about missing session-state policy would be noise, since on that path the policy is not graphitron's to apply.

## The contract change, and migration (the main cost)

This reopens the seam R190 deliberately settled and R45 built on. `newExecutionInput` moves from *"consumer hands us a `DSLContext`"* to *"consumer hands us a `DataSource` (or `Map<TenantId, DataSource>`) plus a session-state policy; graphitron builds the `DSLContext`, owns the transaction, sets the state."* Because R190 is freshly landed and has consumers (the sakila example), this needs an **additive path**, a `DataSource`-based factory alongside the existing `DSLContext` one.

**Resolved:** the `DSLContext` overload is **kept as a documented low-opinion escape hatch**, not `@Deprecated` in V0. Its javadoc states plainly that the caller owns transaction demarcation and session state and that graphitron's safety guarantees do not apply; the `DataSource` form is the documented front door, and the sakila example migrates to it as the first client. Deprecation is a future call once the `DataSource` form has consumer mileage.

**The escape hatch is conditional, enforced at build time.** The caller-owns-everything `DSLContext` overload is structurally incapable of R45's per-tenant acquisition; if `<tenantColumn>` is configured and a consumer wired through it, tenant routing would silently not happen, a data-exposure failure at runtime. So when tenant routing is configured, **the `DSLContext` overload is simply not emitted** (the generator knows the config), converting the hole into a compile error at the consumer's call site.

## @defer and connection release (V0 stance)

graphql-java can run deferred fetchers *after* the initial result is delivered; connection-per-operation release would close the pinned connection out from under them. **V0 stance: incremental delivery stays off on the owned-connection path.** graphql-java's incremental support is opt-in per request, and the `DataSource` factories own the `ExecutionInput` wiring, so they simply do not opt in; no fetcher can run after execution completes and release-at-completion is safe. Enabling `@defer` under owned connections is a follow-on item that must own the connection-lifetime story. `DeferBehaviorTest` today is exploratory (it pins what graphql-java exposes to fetchers, not this stance); slice 2 adds the stance's own pinning test asserting the owned-connection factories leave incremental support disabled.

## Relationship to other items

- **R45 (operation-divined tenant routing, Spec).** Reconciled 2026-07-03: R45's earlier `byTenant Function<T, DSLContext>` seam is gone. R45 now classifies per-field `TenantBinding`s from a `<tenantColumn>` declaration and routes through this item's `Map<TenantId, DataSource>` acquisition. Ownership split: this item owns acquisition, the transaction demarcation rule (see the connection model above), and session state; R45 owns the schema-shaped half (tenant-scope table classification, per-field binding inference, factory shape, per-tenant partitioning of the batching machinery, validation). Keep the two Specs in sync through sign-off.
- **R190 (schema-driven ExecutionInput factory, landed).** The contract this item revises. See migration above.
- **R428 (MCP in-process query execution).** Becomes a **consumer** of this item: the dev tool feeds graphitron a `DataSource` built from its `GRAPHITRON_DEV_DB_*` config and exercises the same connection/transaction/session-state machinery an app does. R428's "rollback everything" is this item's `ROLLBACK_ONLY` transaction mode (the provider seam above), consumed by name, no emission changes; R428's session-state fork (a)/(b) dissolves into this item's strategies. R428's `depends-on` is updated to include this item.
- **R410 (in-process incremental compile).** Independent; R428 sits on both.

## Slices and test tiers

1. **Connection/transaction runtime seam.** graphitron acquires from a `DataSource`, demarcates a transaction via the provider seam (begin applies session state, commit honours the mode enum), releases; `@UnitTier` over a fake `DataSource` asserting acquisition/begin/commit/rollback/close ordering and that `ROLLBACK_ONLY` suppresses commit. Also pins the enabling invariant: a test asserting `RowsMethodCall.batchLoaderLambda`'s emitted body is the synchronous `completedFuture(...)` shape.
2. **Operation-typed mode.** Query → read-only transaction; mutation → per-field commit/rollback riding the existing `transactionResult` emission, then **read-back projection in its own read-only transaction with session state re-applied** (the slice-2 requirement above). `@PipelineTier` on the emitted wiring; `@ExecutionTier` asserting each mutation field commits/rolls back independently and serially (a failing field 2 leaves field 1's committed write, its own rolled back), that the per-field commit aligns with graphql-java's serial mutation execution + DataLoader dispatch, and that the owned-connection factories leave incremental (`@defer`) support disabled.
3. **Session-state strategies.** Postgres `set_config(…, true)`, Oracle context/RAS, generic list; policy maps contextArgs → statements, applied at every transaction begin. `@ExecutionTier` (sakila, Postgres): an RLS-scoped read sees only permitted rows; a mutation's post-commit read-back still sees only permitted rows (the transaction-local state was re-applied); transaction-local state does not leak to the next acquisition on the same pooled connection.
4. **Multi-tenant DataSource map.** Tenant resolved from contextArgs selects the `DataSource`; `@UnitTier` on routing + `@ExecutionTier` per-tenant isolation (reshapes R45's execution-tier coverage).
5. **Factory contract + migration.** Additive `DataSource` factory alongside the `DSLContext` form; `DSLContext` overload suppressed when tenant routing is configured; `@PipelineTier` on the emitted facade covering both the two-overload shape and the suppression; sakila example migrated to the `DataSource` form as the first client.
6. **RLS-assumed principle + warning.** Architecture-doc principle; startup warning when no session-state policy is configured on the `DataSource` path; one-time caller-owns-everything notice on the `DSLContext` path. Docs + `@UnitTier` on both conditions.

## Non-goals

- Creating or configuring the connection pool (the consumer's/framework's `DataSource` owns that).
- Enforcing that RLS is actually enabled on the database (impossible; graphitron assumes and warns).
- Per-tenant *dialect* variation in V0 (dialect is global config; note as a possible extension if a `Map<TenantId, DataSource>` ever spans dialects).
- Application hot-reload or distributed transactions.
- Enabling `@defer` / incremental delivery on the owned-connection path (see the V0 stance above; follow-on item).

## Spec review findings (Spec → Spec revise, 2026-07-05)

Independent Spec → Ready review outcome: **revisions requested**; the design core (owned boundary, single provider seam, enum mode, conditional escape-hatch suppression) is sound, but the following must be resolved before sign-off. Stale-reference spot-checks all passed (`newExecutionInput(DSLContext, …)`, `RowsMethodCall.batchLoaderLambda`, `TypeFetcherGenerator`'s `transactionResult` DML shape, `DeferBehaviorTest`, R45/R428 cross-references).

1. **Name the mode carrier; reconcile it with the "rejected" paragraph.** Within one mutation operation the provider sees at least two modes: `WRITABLE_COMMIT` for a field's `transactionResult`, then `READ_ONLY` for its read-back projection. So the mode is not derivable from the operation type at the provider, yet § "One transaction boundary" rejects "mode awareness threaded into emission" while the slice-2 read-back requirement forces a distinct emitted read-only wrapper, which *is* per-site mode awareness. Recommended shape: the emitted site that opens each transaction declares its mode (query fetcher `READ_ONLY`, per-field `transactionResult` `WRITABLE_COMMIT`, read-back wrapper `READ_ONLY`); the provider only honours what it is handed. State that and rewrite the rejection paragraph to reject only the original alternative (a mode switch at every commit site), not the per-open declaration the read-back already requires.

2. **Commit to the session-state policy declaration surface.** "Which session variables map from which contextArgs" is listed as schema-varying generated code, but the spec never says where the author declares that mapping: Mojo config, SDL directive, or runtime argument. § Migration says the consumer hands the factory "a session-state policy" at runtime, while R45's pinned factory signature carries no policy parameter; the two readings conflict. This is the feature's primary configuration API and cannot be invented in slice 3. Recommendation: Mojo config (deployment/infra binding, same family as `<tenantColumn>`), with dialect → strategy selection stated alongside; then fix the § Migration sentence to match.

3. **Resolve the generic strategy's "(or a hook)" fork.** An ordered statement list is generation-time data bound inside the owned boundary; a consumer-implemented hook reintroduces the hand-implemented runtime seam R190 removed, on the one path whose read-only enforcement is hint-only. Pick the statement list; a hook needs its own justification if a real consumer demands it.

4. **State the Oracle scoping concretely and name its test tier.** "Transaction- or request-scoped as the dialect allows" hedges the load-bearing linchpin (transaction-local state, cleared on commit, no reset dance) on one of the two named dialects, and slice 3's execution coverage is Postgres-only, so the uncertain scoping is exactly the untested part. Either pin Oracle's guarantee as transaction-local, or scope the leak-safety claim to Postgres for V0 and mark Oracle leak-safety a follow-on. Name what tier covers the Oracle strategy's emission (there is no Oracle container in the test stack).

5. **Drop "(or `TransactionListener`)".** A jOOQ `TransactionListener` observes lifecycle events and cannot suppress a commit; `ROLLBACK_ONLY` therefore requires the `TransactionProvider`. The seam should name the provider only.

6. **The slice-1 invariant pin is a code-string assertion on an emitted body**, which the principles doc bans at every tier. The behavioural property (no two SQL fetches of one operation run concurrently on the pinned connection) is what needs pinning; a `completedFuture(` string match breaks on harmless refactors and can survive a genuinely async change. Recommended: pin the property at the execution tier (one connection serves the whole operation), keep the `{@link}` as navigation, and if a structural check is still wanted, mark it explicitly as extending `RowsMethodCallTest`'s existing tolerated unit-tier assertions rather than presenting it as the invariant's guardian.

7. **Minor, one sentence each.** (a) Size the V0 emitted surface (provider, mode enum, three strategies, policy binding) against the spec's own "a few classes" revisit trigger, and acknowledge the fix-propagation trade for security-critical machinery: emitted code patches by regenerate-per-consumer, a runtime jar patches by dependency bump. (b) Note that the escape-hatch suppression's build-time failure lives at the consumer's compile (missing overload), not at `ValidateMojo`; readers of the validator-mirrors-classifier principle will look for a validate-time rejection.

## First-client user-doc draft

> **Graphitron manages the database connection for you.** You give graphitron a `DataSource` (single database) or a `Map<TenantId, DataSource>` (a database per tenant) and tell it the dialect. For every request graphitron takes a connection, runs your query in a **read-only transaction**, and hands it back, so a query can never write. A mutation runs in a writable transaction, commits, and then reads back its result read-only.
>
> **Row-level security is assumed.** Because graphitron maps your tables directly, you should be running row-level security (RLS on Postgres, RAS on Oracle). Tell graphitron which session variables carry the current user/tenant, taken from your query's context arguments, and it sets them transaction-locally on every connection, so each request sees exactly what its identity is allowed and nothing leaks between pooled connections. Graphitron ships this wiring for Postgres and Oracle; a generic form lets you supply the statements yourself. If you configure no session state, graphitron warns you at startup: an unsecured direct-to-database API is a data-exposure risk.

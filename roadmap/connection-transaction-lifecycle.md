---
id: R429
title: "Graphitron owns connection and transaction lifecycle: operation-typed read-only/commit semantics and RLS-first session state"
status: Ready
bucket: architecture
priority: 3
theme: service
depends-on: []
created: 2026-07-03
last-updated: 2026-07-05
---

# Graphitron owns connection and transaction lifecycle: operation-typed read-only/commit semantics and RLS-first session state

## In one paragraph

Today the generated runtime has **no opinion** on connections or transactions: `Graphitron.newExecutionInput(DSLContext defaultDsl, …)` (R190) takes a fully-built `DSLContext` from the consumer, stashes it in the `graphQLContext`, and every fetcher runs on it with whatever transaction and session state the consumer happened to set. That is both a missed safety guarantee and, given how graphitron works, a latent hazard: graphitron **maps the database directly** into a GraphQL read surface, so absent row-level enforcement the generated API is an unguarded `SELECT *` over the schema. This item gives graphitron a strong, opinionated stance on the connection lifecycle: graphitron takes a **`DataSource`** (owning connection acquisition), derives the **transaction mode from the operation type** (queries read-only, mutations commit then project read-only), and sets **RLS/RAS session state transaction-scoped** from the request's contextArguments, with built-in strategies for Postgres and Oracle plus a generic escape hatch. The linchpin is that owning the transaction boundary is what makes transaction-scoped session state, and therefore *safe RLS over a pooled connection*, possible at all; a consumer-provided `DSLContext` cannot guarantee it because it does not own the boundary.

## Why graphitron should own this (the load-bearing argument)

The read-only/commit semantics, the session state, and the pooling safety are not three features; they are one, hinged on **transaction-scoped session state**: RLS variables set at transaction begin and gone by the time the connection returns to the pool. The clearing mechanism differs by dialect, and the spec is honest about that: Postgres `set_config(key, value, true)` is genuinely transaction-local, cleared by the database itself on commit or rollback; Oracle's `DBMS_SESSION.SET_CONTEXT` is **session-scoped**, so on Oracle the provider itself clears the context at transaction end. Either way the guarantee has a single source: the authority that owns the transaction boundary sets the state at begin and ensures it is gone at end. Postgres's database-level scoping is defense-in-depth on top of that ownership, not a substitute for it.

- graphitron owns the transaction → it sets RLS state at begin and guarantees clearing at end → **no identity leaks across a pooled connection**, with no reset dance left to the consumer.
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
- **Schema-invariant, emitted anyway:** the transaction-demarcation loop, the Postgres `set_config` strategy, the Oracle context/RAS wiring. These do not vary by schema; the criterion for emitting them rather than shipping a Java-17 runtime jar (the `graphitron-jakarta-rest` category) is the no-runtime-dependency invariant `GraphitronContextInterfaceGenerator` already documents: consumers of the generated code add no graphitron artifact to their runtime classpath, and version skew between generator and runtime cannot exist.

Sizing that criterion against its own trigger: the expected V0 emitted surface is roughly four classes (the `TransactionProvider`, one session-state strategy selected by the configured dialect, the read-only/writable acquisition handles, and the `DataSource` factory additions to the existing facade). That sits at, not comfortably under, the "a few classes" threshold where a runtime jar becomes the better trade; if implementation grows the count, the placement decision reopens rather than stretches. One trade is acknowledged explicitly because this machinery is security-critical: **a fix in emitted machinery reaches consumers only through regeneration** with an upgraded graphitron, never through a runtime-jar version bump. Every generated line carries that property; it is named here because a session-state bug is a security bug, and the mitigation is the same channel as any generator fix (release, consumers rebuild).

Because the emitted machinery is substantial generated source, it must be **valid Java 17** (no Java 21+ syntax in emitted bodies), verified as always by the `graphitron-sakila-example` `<release>17</release>` compile.

## The enabling invariant: SQL within an operation is sequential

A single pinned connection per operation is safe **because generated batch loaders execute SQL synchronously on the dispatch thread**: `RowsMethodCall.batchLoaderLambda` emits `CompletableFuture.completedFuture(rows(keys, dfe))`, so no two fetches of one operation ever run SQL concurrently. This is load-bearing for the whole item; a future "make loaders async" change would break connection pinning. Prose alone does not protect an invariant, so it is pinned twice, behaviourally and at the emission site:

- **Behaviourally (primary, slice 2):** an `@ExecutionTier` assertion over an instrumented `DataSource` that a whole multi-fetch operation acquires exactly one connection, serving nested and batched fetches alike.
- **At the emission site (tripwire):** `RowsMethodCallTest` already asserts the synchronous `completedFuture(...)` body at `@UnitTier`; slice 1 extends that test's javadoc to name this invariant and adds a `{@link}` from the connection-lifecycle generator to `RowsMethodCall`. The body assertion is a code-string check on an emitted snippet, tolerated at unit tier by the existing `RowsMethodCallTest` precedent (a targeted snippet-shape pin, not a generated-method-body assertion); it exists so that the async change fails *at the file being edited*, with the javadoc explaining why, rather than as a distant execution-tier flake.

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
  write transaction. Under this item that is a hole, not a nicety: session state is transaction-scoped,
  so it is gone once the write transaction ends (database-cleared on Postgres, provider-cleared on
  Oracle), and a bare read-back would execute with no identity,
  the exact leak this item exists to prevent, on the mutation path specifically. The read-back SELECT
  must run inside a demarcated read-only transaction that re-applies session state through the same
  begin-hook as every other transaction.

## One transaction boundary: the provider seam

Two needs land on the same mechanism, and the design keeps them as **one** seam rather than two: a custom jOOQ `TransactionProvider` wrapped around the pinned connection owns every transaction boundary in an operation. (A `TransactionListener` cannot fill this role: listeners observe boundaries, they cannot suppress a commit.)

Two axes meet at the provider, and they are deliberately separate concepts:

- **Transaction intent (`READ_ONLY` / `WRITABLE`) is declared by the emitted site that opens the transaction**, carried by which acquisition entry point it uses: the machinery exposes a read-only handle and a writable handle, and each emitted transaction-opening site goes through the one matching its intent. A query operation's single transaction and a mutation's read-back projections use the read-only handle; a mutation field's `transactionResult` runs on the writable handle. The provider cannot derive intent from operation type alone, because one mutation operation legitimately interleaves writable per-field transactions with read-only read-back transactions.
- **Commit policy (`COMMIT` / `ROLLBACK_ONLY`) is global provider configuration**, never site-declared. `ROLLBACK_ONLY` is the named seam R428's rollback-everything dev tool consumes: `dsl.transactionResult(...)` inherently commits on normal return, so commit suppression *must* live in the provider that every emitted `transactionResult` already routes through; R428 sets the policy and touches no emission.

On both axes, **`begin` applies session state**. Session state is transaction-scoped, so it must re-apply at *every* transaction begin: once for a query's single read-only transaction, N times for a mutation's per-field transactions plus read-back projections, once per tenant-transaction under R45. Hooking begin in the provider means the emitters never carry session-state awareness.

What is rejected is **per-commit-site mode switching**: an emitted commit site choosing its own commit-vs-rollback behaviour would thread policy awareness through every emitter. Site-declared *intent* is fine (a site knows whether it reads or writes); site-declared *policy* is not.

## Session-state strategies (the "do more")

The user decides *what* state to set; graphitron owns the plumbing and ships the common cases:

- **Postgres**: `set_config(key, value, true)` (transaction-local, database-cleared) per configured RLS variable.
- **Oracle**: `DBMS_SESSION.SET_CONTEXT` at transaction begin, **explicitly cleared by the provider at transaction end** (`CLEAR_CONTEXT`), because Oracle's context is session-scoped with no transaction-local form. The set/clear pairing is the provider's responsibility, exercised by slice 3's ordering coverage.
- **Generic**: the consumer supplies an ordered list of parameterized statements, bound from contextArgs. A statement list, deliberately not a hook: a consumer-implemented callback would reintroduce the consumer-implemented runtime seam R190 removed, on the least-safe path of the three. The read-only enforcement here degrades to the JDBC hint where the dialect has no enforced form.

**Declaration surface (resolved): the session-state policy is Mojo configuration**, a `<sessionState>` block in the plugin `<configuration>`, the same declaration family as R45's `<tenantColumn>`. It maps session variables (or generic statements) to contextArgument names; codegen validates the referenced contextArguments exist and bakes the bindings into the emitted strategy. The policy is build-time data, so the runtime factory signature carries **no policy parameter**, which keeps R45's pinned factory shape true as written. The policy runs at every transaction begin (via the provider seam above), not once at acquisition. **Read-only enforcement is a gradient**: enforced on Postgres/Oracle, hint-only on the generic path; documented as such so nobody assumes generic means enforced.

## RLS-assumed becomes an explicit principle

graphitron assumes the database enforces row access and makes that first-class; it **cannot enforce** that the consumer enabled RLS (that is a DB feature). This posture is written into `docs/architecture` as a stated principle, and graphitron emits a **warning when no session-state policy is configured**, so the assumption is loud rather than silently dangerous. Non-RLS consumers remain responsible for exposing only safe surfaces (views, restricted schemas); the warning names that responsibility.

**Warning scope and timing (resolved):** the session-state policy is Mojo configuration, so its absence is a build-time fact; the warning fires at **generation time** in the build log and applies to the `DataSource` path, where graphitron owns the boundary and could have set state but was not told what to set. The `DSLContext` escape hatch instead emits a **one-time caller-owns-transactions-and-RLS notice at wiring time**; warning it about a missing session-state policy would be noise, since on that path the policy is not graphitron's to apply.

## The contract change, and migration (the main cost)

This reopens the seam R190 deliberately settled and R45 built on. `newExecutionInput` moves from *"consumer hands us a `DSLContext`"* to *"consumer hands us a `DataSource` (or `Map<TenantId, DataSource>`); graphitron builds the `DSLContext`, owns the transaction, sets the state."* The session-state policy is not a runtime parameter; it is declared in the plugin configuration (see the declaration surface above), so the factory signature stays exactly the shape R45 pins. Because R190 is freshly landed and has consumers (the sakila example), this needs an **additive path**, a `DataSource`-based factory alongside the existing `DSLContext` one.

**Resolved:** the `DSLContext` overload is **kept as a documented low-opinion escape hatch**, not `@Deprecated` in V0. Its javadoc states plainly that the caller owns transaction demarcation and session state and that graphitron's safety guarantees do not apply; the `DataSource` form is the documented front door, and the sakila example migrates to it as the first client. Deprecation is a future call once the `DataSource` form has consumer mileage.

**The escape hatch is conditional, enforced by omission.** The caller-owns-everything `DSLContext` overload is structurally incapable of R45's per-tenant acquisition; if `<tenantColumn>` is configured and a consumer wired through it, tenant routing would silently not happen, a data-exposure failure at runtime. So when tenant routing is configured, **the `DSLContext` overload is simply not emitted** (the generator knows the config). To be precise about where the enforcement lives: this is not a `ValidateMojo` rejection, the failure surfaces as a missing-method compile error in the *consumer's* build. That is acceptable here because omission leaves no misuse window at all (there is nothing to call), which is strictly stronger than a diagnostic.

## @defer and connection release (V0 stance)

graphql-java can run deferred fetchers *after* the initial result is delivered; connection-per-operation release would close the pinned connection out from under them. **V0 stance: incremental delivery stays off on the owned-connection path.** graphql-java's incremental support is opt-in per request, and the `DataSource` factories own the `ExecutionInput` wiring, so they simply do not opt in; no fetcher can run after execution completes and release-at-completion is safe. Enabling `@defer` under owned connections is a follow-on item that must own the connection-lifetime story. `DeferBehaviorTest` today is exploratory (it pins what graphql-java exposes to fetchers, not this stance); slice 2 adds the stance's own pinning test asserting the owned-connection factories leave incremental support disabled.

## Relationship to other items

- **R45 (operation-divined tenant routing, Spec).** Reconciled 2026-07-03: R45's earlier `byTenant Function<T, DSLContext>` seam is gone. R45 now classifies per-field `TenantBinding`s from a `<tenantColumn>` declaration and routes through this item's `Map<TenantId, DataSource>` acquisition. Ownership split: this item owns acquisition, the transaction demarcation rule (see the connection model above), and session state; R45 owns the schema-shaped half (tenant-scope table classification, per-field binding inference, factory shape, per-tenant partitioning of the batching machinery, validation). Keep the two Specs in sync through sign-off.
- **R190 (schema-driven ExecutionInput factory, landed).** The contract this item revises. See migration above.
- **R428 (MCP in-process query execution).** Becomes a **consumer** of this item: the dev tool feeds graphitron a `DataSource` built from its `GRAPHITRON_DEV_DB_*` config and exercises the same connection/transaction/session-state machinery an app does. R428's "rollback everything" is this item's `ROLLBACK_ONLY` commit policy (the provider seam above), consumed by name, no emission changes; R428's session-state fork (a)/(b) dissolves into this item's strategies. R428's `depends-on` is updated to include this item.
- **R410 (in-process incremental compile).** Independent; R428 sits on both.

## Slices and test tiers

1. **Connection/transaction runtime seam.** graphitron acquires from a `DataSource`, demarcates transactions via the provider seam (site-declared intent through the read-only/writable handles, begin applies session state, commit honours the global commit policy), releases; `@UnitTier` over a fake `DataSource` asserting acquisition/begin/commit/rollback/close ordering, that the read-only handle yields an enforced read-only transaction, and that `ROLLBACK_ONLY` suppresses commit. Also lands the emission-site tripwire for the enabling invariant (the `RowsMethodCallTest` javadoc extension + generator `{@link}` described above).
2. **Operation-typed mode.** Query → read-only transaction; mutation → per-field commit/rollback riding the existing `transactionResult` emission on the writable handle, then **read-back projection in its own read-only transaction with session state re-applied** (the slice-2 requirement above). `@PipelineTier` on the emitted wiring; `@ExecutionTier` asserting each mutation field commits/rolls back independently and serially (a failing field 2 leaves field 1's committed write, its own rolled back), that the per-field commit aligns with graphql-java's serial mutation execution + DataLoader dispatch, that a whole multi-fetch operation acquires exactly one connection (the behavioural invariant pin), and that the owned-connection factories leave incremental (`@defer`) support disabled.
3. **Session-state strategies.** Postgres `set_config(…, true)`, Oracle set/clear context pairing, generic statement list; policy declared in the `<sessionState>` Mojo block, bindings baked at codegen, applied at every transaction begin. `@ExecutionTier` (sakila, Postgres): an RLS-scoped read sees only permitted rows; a mutation's post-commit read-back still sees only permitted rows (the transaction-scoped state was re-applied); state does not leak to the next acquisition on the same pooled connection. **Oracle is unit-tier only in V0:** `@UnitTier` over a fake connection asserting the `SET_CONTEXT`-at-begin / `CLEAR_CONTEXT`-at-end ordering on commit *and* rollback paths; the build has no Oracle container, so Oracle `@ExecutionTier` coverage is a named follow-on, not silently absent.
4. **Multi-tenant DataSource map.** Tenant resolved from contextArgs selects the `DataSource`; `@UnitTier` on routing + `@ExecutionTier` per-tenant isolation (reshapes R45's execution-tier coverage).
5. **Factory contract + migration.** Additive `DataSource` factory alongside the `DSLContext` form; `DSLContext` overload suppressed when tenant routing is configured; `@PipelineTier` on the emitted facade covering both the two-overload shape and the suppression; sakila example migrated to the `DataSource` form as the first client.
6. **RLS-assumed principle + warning.** Architecture-doc principle; the no-session-state warning fires at **generation time** (the `<sessionState>` block is Mojo configuration, so its absence is a build-time fact; a build-log warning names the exposure and the consumer's responsibility); one-time caller-owns-everything notice at wiring time on the `DSLContext` path. Docs + `@UnitTier` on both conditions.

## Non-goals

- Creating or configuring the connection pool (the consumer's/framework's `DataSource` owns that).
- Enforcing that RLS is actually enabled on the database (impossible; graphitron assumes and warns).
- Per-tenant *dialect* variation in V0 (dialect is global config; note as a possible extension if a `Map<TenantId, DataSource>` ever spans dialects).
- Application hot-reload or distributed transactions.
- Enabling `@defer` / incremental delivery on the owned-connection path (see the V0 stance above; follow-on item).

## Spec review findings (Spec → Spec revise, 2026-07-05): applied

The independent Spec → Ready review requested revisions on seven findings; all are folded into the body above in this revision. Trace, for the next reviewer:

1. Mode carrier: resolved by splitting site-declared *intent* (the read-only/writable acquisition handles) from global *commit policy*; the rejection paragraph now rejects only per-commit-site policy switching (§ "One transaction boundary").
2. Session-state declaration surface: resolved as the `<sessionState>` Mojo block; the factory carries no policy parameter and the § Migration sentence is fixed (§ "Session-state strategies").
3. Generic strategy: statement list only; the "(or a hook)" fork is closed (§ "Session-state strategies").
4. Oracle scoping: stated concretely as session-scoped with provider-owned `SET_CONTEXT`/`CLEAR_CONTEXT` pairing; the linchpin claim is rewritten as boundary-owner-sets-and-clears; Oracle is `@UnitTier`-only in V0 with execution-tier coverage a named follow-on (§ "Why graphitron should own this", § "Session-state strategies", slice 3).
5. `TransactionListener` removed; the seam names the `TransactionProvider` only (§ "One transaction boundary").
6. Invariant pin: behavioural `@ExecutionTier` one-connection-per-operation assertion is primary; the emission-site body check is explicitly marked as riding `RowsMethodCallTest`'s tolerated unit-tier precedent (§ "The enabling invariant", slices 1-2).
7. Emitted-surface sizing, the regenerate-to-patch trade, and the consumer-compile (not `ValidateMojo`) location of the escape-hatch enforcement are all stated (§ "Placement", § "The contract change").

## First-client user-doc draft

> **Graphitron manages the database connection for you.** You give graphitron a `DataSource` (single database) or a `Map<TenantId, DataSource>` (a database per tenant) and tell it the dialect. For every request graphitron takes a connection, runs your query in a **read-only transaction**, and hands it back, so a query can never write. A mutation runs in a writable transaction, commits, and then reads back its result read-only.
>
> **Row-level security is assumed.** Because graphitron maps your tables directly, you should be running row-level security (RLS on Postgres, RAS on Oracle). Tell graphitron which session variables carry the current user/tenant, taken from your query's context arguments, and it sets them at the start of every transaction and guarantees they are gone when it ends, so each request sees exactly what its identity is allowed and nothing leaks between pooled connections. Graphitron ships this wiring for Postgres and Oracle; a generic form lets you supply the statements yourself. If you configure no session state, graphitron warns you at build time: an unsecured direct-to-database API is a data-exposure risk.

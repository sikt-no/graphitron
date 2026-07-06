---
id: R429
title: "Graphitron owns the connection lifecycle: application runtime, operation-typed transactions, and database-mounted session identity"
status: Spec
bucket: architecture
priority: 3
theme: service
depends-on: []
created: 2026-07-03
last-updated: 2026-07-06
---

# Graphitron owns the connection lifecycle: application runtime, operation-typed transactions, and database-mounted session identity

## In one paragraph

Graphitron maps the database directly into a GraphQL surface, so access control belongs to the database: row-level enforcement (RLS on Postgres, VPD/RAS on Oracle) decides what each request may see. For that to hold over pooled connections, one authority must own connection acquisition, transaction demarcation, and the mounting and unmounting of per-request identity onto the connection; split that ownership and identity either leaks across requests or never reaches the database at all. This item gives graphitron that ownership. An application-scoped runtime owns the `DataSource`; every operation pins exactly one connection; queries run in enforced read-only transactions and mutation fields commit serially; and per-request identity travels as an opaque payload (typically the caller's JWT) to a consumer-owned database *connect hook* at acquisition, with a paired *disconnect hook* at release. Graphitron owns the plumbing and the lifecycle guarantees; the database owns identity types, validation, and enforcement.

## The stance: the database enforces, graphitron delivers identity

Absent row-level enforcement, a directly mapped schema is an unguarded `SELECT *` surface. Graphitron therefore assumes database-enforced row access as a first-class principle (see Warnings below for how the assumption is kept loud). The generator's contribution is the part only it can provide: because it owns when a connection is acquired and when it is released, it can guarantee that the request's identity is mounted on the connection before any SQL runs and is gone before the connection returns to the pool. That single ownership fact is what makes RLS over a pooled connection safe; every other section hangs off it.

Equally load-bearing is what graphitron does *not* do: it never parses, verifies, or interprets the identity payload. The payload is an opaque `String` end to end. The caller's platform verified the token at the edge (for Sikt subgraphs, MicroProfile JWT/SmallRye); the consumer's database hook transforms it into session state. JWT claims are JSON and both target databases speak JSON natively (`jsonb` operators on Postgres, `JSON_VALUE` on Oracle), so the easiest and safest place to transform identity into what the database needs is inside the database, where the types, the entitlement data, and the enforcement already live. The database is the type authority; graphitron config maps no claim to any type, ever.

## Two lifecycles, two emitted surfaces

Connection setup is application-scoped; identity is request-scoped. The emitted surface separates them:

- **Application-scoped runtime.** Built once at wiring time: `var runtime = Graphitron.runtime(dataSource)` (emitted class name at the implementer's discretion). It holds the `DataSource`, the configured dialect, the acquisition and transaction machinery, and the session hooks baked from configuration. The consumer (or their framework) still owns pool creation and tuning; graphitron owns acquisition, transaction, and identity state on top of it. R45's database-per-tenant `Map<TenantId, DataSource>` is a runtime-construction overload, not a per-request parameter.
- **Per-request factory.** `runtime.newExecutionInput(identity, <contextArgs...>)` carries only per-request data: the opaque identity payload plus the schema's typed contextArguments exactly as today. The factory stashes values into the `graphQLContext` and never touches a connection; the connect hook fires later, inside the runtime's acquisition machinery, when the operation actually pins a connection.

contextArguments are untouched by this item. They remain the Java-typed channel anchored by `@service`/`@condition` signatures, and they are never sent to the database. The identity channel and the contextArgument channel are disjoint by design: one is string-natured and consumed by the database, the other is Java-typed and consumed by consumer code.

**Placement.** The machinery is emitted into the consumer's output package, following the `GraphitronContext` precedent: emitted code depends only on the app's own build output plus jOOQ, graphql-java, and the JDK (`javax.sql.DataSource` is JDK). Because the identity payload is a plain `String`, no auth-framework type appears anywhere in generated code; the consumer's adapter line is simply `runtime.newExecutionInput(jwt.getRawToken())`. Expected V0 emitted surface is roughly five classes (runtime, transaction provider, read-only/writable acquisition handles, factory additions); if implementation grows materially past that, the emit-vs-runtime-jar placement decision reopens rather than stretches. A fix in emitted machinery reaches consumers only through regeneration; that trade is named because session-state bugs are security bugs. Emitted bodies must be valid Java 17, verified by the `graphitron-sakila-example` `<release>17</release>` compile.

## The connection model

One operation pins exactly one connection: acquired from the runtime's `DataSource` when the operation starts executing, released when execution completes. This is safe because generated batch loaders execute SQL synchronously on the dispatch thread: `RowsMethodCall.batchLoaderLambda` emits `CompletableFuture.completedFuture(rows(keys, dfe))`, so no two fetches of one operation ever run SQL concurrently. That invariant is load-bearing and pinned twice: behaviourally, an `@ExecutionTier` assertion over an instrumented `DataSource` that a whole multi-fetch operation acquires exactly one connection; and at the emission site, where `RowsMethodCallTest`'s existing synchronous-body assertion gains javadoc naming this invariant plus a `{@link}` from the connection-lifecycle generator, so a future "make loaders async" change fails at the file being edited rather than as a distant execution-tier flake.

Multi-tenancy composes without new mechanism. Shared-database tenancy (RLS/VPD partition) needs nothing at all here: the tenant travels inside the identity payload and the connect hook applies it like any other identity fact. Database-per-tenant routing (R45) selects a `DataSource` from the runtime's map per divined tenant key; within one operation, all SQL for the same divined tenant shares one pinned connection and transaction, so a query touching N tenants runs N independently consistent read-only transactions. The two flavors compose: a per-tenant database and row enforcement within it.

## Session identity: mount and unmount through consumer-owned hooks

The `<sessionState>` block in the plugin `<configuration>` names two database callables:

```xml
<sessionState>
  <connect call="Pk_Ras.Connect"/>       <!-- (p_identity IN, p_handle OUT) -->
  <disconnect call="Pk_Ras.Disconnect"/> <!-- (p_handle IN) -->
</sessionState>
```

The contract is deliberately mechanical:

- **Connect at acquisition.** The runtime calls the connect hook with the identity payload immediately after pinning the connection, before any operation SQL. An optional `OUT` handle (an opaque string) is captured by the provider.
- **Disconnect at release.** The runtime calls the disconnect hook, binding the captured handle if one was declared, on *every* release path: normal completion, error, and abandoned/cancelled execution. Identity state is therefore acquisition-scoped: mounted for exactly the pinned lifetime of the connection, across every transaction the operation runs.
- **Fail closed.** A connect hook that raises (missing claim, unknown person, unentitled role) rejects the request before any SQL executes, surfaced as a request-level GraphQL error.
- **Evict on unmount failure.** If the disconnect hook fails or cannot run, the physical connection is closed and evicted from the pool, never returned. A connection whose identity cannot be proven unmounted does not get a next borrower.

Everything domain-shaped lives inside the hook, in the database's own language, behind whatever privilege fence the dialect offers: JSON parsing of the payload, claim validation, entitlement filtering, VPD context calls, RAS session management, `set_config` writes. Graphitron validates nothing about the payload at build time because there is nothing to validate; codegen checks only that the configured callables are named.

**Oracle worked example (RAS).** Sikt's kernel API uses Oracle Real Application Security: a definer-rights package (privileges the connection user does not hold; the connection user has `EXECUTE` on the package and nothing more) whose connect procedure sets the VPD institution context, resolves the person from the national identity number in the claims, creates a RAS session via `DBMS_XS_SESSIONS.CREATE_SESSION`/`ATTACH_SESSION`, filters requested roles against database-side entitlement checks before `ENABLE_ROLE`, and returns the RAS session id as the handle; the disconnect procedure detaches and destroys by handle. RAS sessions can also be detached without destruction and reattached later, so a package can amortize session creation across requests entirely on its own: the connect hook receives the full identity every time and can decide create-versus-reattach internally, keeping its own identity-to-session mapping. The two-hook contract deliberately supports that without graphitron knowing it happens.

**Postgres.** The same contract: a `SECURITY DEFINER` connect function parses the payload (`jsonb`) and applies session-scoped state (`set_config(key, value, false)`); the disconnect function clears exactly what connect set. For the common low-ceremony case, a built-in sugar form generates both halves so a consumer writes no SQL at all:

```xml
<sessionState>
  <variables>
    <variable name="app.user_id" claim="sub"/>
  </variables>
</sessionState>
```

compiles to a graphitron-generated connect statement, `SELECT set_config('app.user_id', ($1::jsonb)->>'sub', false)`, and a matching disconnect that clears each variable. Even the sugar transforms in the database, in SQL; no JSON parser enters emitted Java. The sugar presumes the identity payload is claims JSON rather than a raw compact token; its documentation says so.

**Integrity gradient (documented, not solved).** How tamper-resistant the mounted identity is varies by dialect and pattern, and the docs must say so plainly. Oracle's package-bound contexts and RAS are an *enforced fence*: code on the connection cannot set state except through the trusted package. Plain Postgres GUCs are a *convention fence*: any SQL on the connection can overwrite them, and the real guarantee is that graphitron generates every statement and consumer `@service` code behaves. Between them sits the *cryptographic fence*, available today on both dialects through the function hook: pass the raw signed token as the payload, verify the signature inside the database, and have policies read identity only through verified state, so even arbitrary SQL on the connection cannot forge another user's identity. The RLS-assumed documentation carries this gradient, and the combination "Postgres + `<variables>` sugar + `@service` methods in the schema" earns a loud generation-time note, since that is the convention fence with consumer code on the connection.

## Operation-typed transactions

- **Query operations** run in a single transaction opened `SET TRANSACTION READ ONLY`, which Postgres and Oracle enforce at the database. Row enforcement plus enforced read-only makes the generated read surface safe by construction: a query literally cannot write.
- **Mutation operations** match graphql-java's serial execution of top-level mutation fields: each mutation field runs in its own writable transaction that commits on success and rolls back on failure before the next field begins, mirroring GraphQL's partial-success semantics (a failing field 2 rolls back its own write while field 1's committed write stands). This rides the shipped DML shape: `TypeFetcherGenerator`'s two-step DML fetchers already emit `dsl.transactionResult(tx -> ...)` per mutation field, and that call is the per-field boundary; graphitron pins a writable connection and opens no outer transaction. After a field commits, its payload selection set resolves in a read-only transaction over the now-committed state. Identity needs no re-application anywhere in this sequence: it is acquisition-scoped and the connection is still pinned, so per-field commits and read-back projections all see the mounted state.

All transaction boundaries route through one seam: a custom jOOQ `TransactionProvider` wrapped around the pinned connection (a `TransactionListener` cannot fill this role; listeners observe boundaries but cannot suppress a commit). Two axes meet there and stay separate. *Transaction intent* (`READ_ONLY`/`WRITABLE`) is declared by the emitted site that opens the transaction, carried by which acquisition handle it uses; a query's single transaction and a mutation's read-back projections use the read-only handle, a mutation field's `transactionResult` the writable one. *Commit policy* (`COMMIT`/`ROLLBACK_ONLY`) is global provider configuration, never site-declared; `ROLLBACK_ONLY` is the named seam R428's rollback-everything dev tool consumes without touching emission. Per-commit-site policy switching is rejected: a site knows whether it reads or writes, it does not get to choose commit-versus-rollback behaviour. Session identity is orthogonal to this whole section: hooks fire at acquisition and release, not at transaction boundaries.

## @defer and connection release

graphql-java can run deferred fetchers after the initial result is delivered; connection-per-operation release would close the pinned connection out from under them. V0 stance: incremental delivery stays off on the owned-connection path. Incremental support is opt-in per request, the factory owns the `ExecutionInput` wiring, and it simply does not opt in, so no fetcher can run after execution completes and release-at-completion is safe. Enabling `@defer` under owned connections is a follow-on item that must own the connection-lifetime story; a pinning test asserts the factory leaves incremental support disabled.

## Warnings, principles, and the escape hatch

- **RLS-assumed is a stated principle** in `docs/architecture`: graphitron cannot enforce that the database enforces row access; it assumes it, and says so. Non-RLS consumers remain responsible for exposing only safe surfaces (views, restricted schemas).
- **Generation-time warning** when no `<sessionState>` is configured on the runtime path: graphitron owns the boundary and could have mounted identity but was not told how. The warning names the exposure and the consumer's responsibility. Absence of config is a build-time fact, so the warning is a build-log fact.
- **Doc reconciliation:** the architecture reference currently states that generated code performs no auth checks. Identity plumbing and fail-closed connect rejection are connection machinery, not resolver-level authorization, but the sentence must be reconciled explicitly so the docs and the shipped behaviour cannot be read as contradicting each other.
- **Escape hatch:** the `DSLContext`-accepting factory form is kept as a documented low-opinion path whose javadoc states that the caller owns transaction demarcation and identity state and that graphitron's guarantees do not apply; it emits a one-time caller-owns-everything notice at wiring time. When database-per-tenant routing is configured, the escape hatch is structurally incapable of per-tenant acquisition, so it is simply not emitted; the enforcement is a missing-method compile error in the consumer's build, which leaves no misuse window at all.

## The contract change, and migration

This revises R190's factory contract: from a per-request, consumer-built `DSLContext` to an application-scoped runtime plus a per-request identity-carrying factory. The path is additive; the `DSLContext` form survives as the escape hatch above. The sakila example migrates to the runtime form as the first client, resolving its identity payload from the authenticated request.

## Relationship to other items

- **R45 (operation-divined tenant routing, Spec).** Routes through this item's runtime-held `Map<TenantId, DataSource>`; the map moves from R45's sketched factory parameter to runtime construction, so R45's factory shape needs a sync edit during its own review. Ownership split unchanged: this item owns acquisition, demarcation, and identity; R45 owns tenant classification, binding inference, and per-tenant partitioning of the batching machinery.
- **R190 (schema-driven ExecutionInput factory, landed).** The contract this item revises; see migration above.
- **R428 (MCP in-process query execution).** Consumer of this item: feeds the runtime a `DataSource` from dev config; its rollback-everything mode is the provider's `ROLLBACK_ONLY` commit policy, consumed by name.
- **R410 (in-process incremental compile).** Independent; R428 sits on both.

## Slices and test tiers

1. **Runtime and acquisition/release seam.** Emitted runtime over a `DataSource`; pinning; connect/disconnect hook invocation with handle threading; `@UnitTier` over a fake `DataSource` asserting acquisition/connect/.../disconnect/release ordering on success, error, and cancellation paths, fail-closed connect rejection, and eviction on disconnect failure. Also lands the emission-site tripwire for the sequential-SQL invariant.
2. **Operation-typed transactions.** Read-only queries, per-field mutation commit/rollback riding the existing `transactionResult` emission, read-back projection read-only; `@PipelineTier` on the emitted wiring; `@ExecutionTier` asserting per-field commit/rollback independence and serial alignment with graphql-java's dispatch, exactly one connection per multi-fetch operation, and incremental support left disabled.
3. **Session hooks.** Function hooks with handle threading; the Postgres `<variables>` sugar generating both halves. `@UnitTier` over a fake connection asserting the emitted call shapes, handle capture and rebinding, and disconnect-then-evict on both commit and rollback outcomes. `@ExecutionTier` (sakila, Postgres): an RLS-scoped read sees only permitted rows; a mutation's post-commit read-back still sees only permitted rows; state is demonstrably absent on the next acquisition of the same pooled connection. Oracle/RAS execution-tier coverage is a named follow-on (no Oracle container in the build); Oracle stays unit-tier in V0.
4. **Multi-tenant runtime map.** Tenant-keyed acquisition against the runtime's map; `@UnitTier` routing plus `@ExecutionTier` per-tenant isolation (reshapes R45's planned coverage).
5. **Runtime/factory contract and migration.** Runtime construction, per-request factory, escape-hatch emission and its suppression under tenant routing; `@PipelineTier` on the emitted facade shapes; sakila example migrated as first client.
6. **Principle docs and warnings.** RLS-assumed principle, integrity gradient, doc reconciliation, generation-time warning, wiring-time notice; docs plus `@UnitTier` on both warning conditions.

## Non-goals

- Creating or configuring the connection pool (the consumer's `DataSource` owns that).
- Verifying, parsing, or interpreting the identity payload (the edge verified it; the hook may re-verify in-database; graphitron never looks inside).
- Enforcing that row-level enforcement is actually enabled (impossible; assumed and warned).
- Enabling `@defer`/incremental delivery on the owned-connection path (named follow-on).
- Cross-acquisition identity/handle caching in the runtime (a future optimization the two-hook contract already permits DB-side).
- Per-tenant dialect variation, distributed transactions, application hot-reload.

## First-client user-doc draft

> **Graphitron manages the database connection for you.** At startup you give graphitron a `DataSource` and the dialect. For every request graphitron takes a connection, mounts your caller's identity on it, runs queries in a read-only transaction (a query cannot write), commits each mutation field separately, and unmounts the identity before the connection goes back to the pool.
>
> **Identity goes to the database, not to Java code.** You pass the authenticated request's token (or any string you choose) to `newExecutionInput`; graphitron hands it, untouched, to a connect function you write in your database. That function parses the claims, validates what it must, and sets your row-level security state; a disconnect function clears it. Graphitron guarantees the pair runs at connection mount and unmount and that a connection whose unmount failed is never reused. If you configure no session state, graphitron warns you at build time: an unsecured direct-to-database API is a data-exposure risk.

## Notes on discarded earlier designs

Kept as one-liners for archaeology; none of these survive in the design above.

- *Session state sourced from contextArguments:* an identity used only for row enforcement has no `@service`/`@condition` site to anchor its type, and the Java-typed channel was the wrong carrier for string-natured database state.
- *A claim-binding vocabulary in the Mojo block* (per-parameter claim names, collection types, constants, optional flags): dissolved once transformation moved into the database hook; JSON is the carrier and the database is the type authority.
- *A built-in direct `DBMS_SESSION.SET_CONTEXT` Oracle strategy:* Oracle application contexts are package-bound (`CREATE CONTEXT ... USING`), so raw calls from the connection fail; real deployments need RAS attach/detach, not variable writes.
- *Transaction-scoped session state re-applied at every begin:* redundant once acquisition and release are owned; it forced a scope axis, per-begin re-fire machinery, and a read-back re-application rule, all deleted by acquisition scoping.
- *MicroProfile-JWT-typed factory surfaces, a `Function<String,String>` claims source, and a `graphitron-mp-jwt` adapter artifact:* all dissolved by the opaque `String` payload.
- *`DataSource` as a per-request factory parameter:* connection setup is application-scoped; split into the runtime object.

---
id: R429
title: "Graphitron owns the connection lifecycle: application runtime, operation-typed transactions, and database-mounted session identity"
status: In Progress
bucket: architecture
priority: 3
theme: service
depends-on: []
created: 2026-07-03
last-updated: 2026-07-09
---

# Graphitron owns the connection lifecycle: application runtime, operation-typed transactions, and database-mounted session identity

## In one paragraph

Graphitron maps the database directly into a GraphQL surface, so access control belongs to the database: row-level enforcement (RLS on Postgres, VPD/RAS on Oracle) decides what each request may see. For that to hold over pooled connections, one authority must own connection acquisition, transaction demarcation, and the mounting and unmounting of per-request identity onto the connection; split that ownership and identity either leaks across requests or never reaches the database at all. This item gives graphitron that ownership. An application-scoped runtime owns the `DataSource`; every operation pins exactly one connection; mutation fields commit serially per field; and the caller's claims travel as an opaque payload (typically the JWT itself) to a consumer-owned database *connect hook* at acquisition, with a paired *disconnect hook* at release. Graphitron owns the plumbing and the lifecycle guarantees; the database owns claim types, validation, and enforcement.

## The stance: the database enforces, graphitron delivers the claims

Absent row-level enforcement, a directly mapped schema is an unguarded `SELECT *` surface. Graphitron therefore assumes database-enforced row access as a first-class principle (see Warnings below for how the assumption is kept loud). The generator's contribution is the part only it can provide: because it owns when a connection is acquired and when it is released, it can guarantee that the request's identity is mounted on the connection before any SQL runs and is gone before the connection returns to the pool. That single ownership fact is what makes RLS over a pooled connection safe; every other section hangs off it.

Equally load-bearing is what graphitron does *not* do: it never parses, verifies, or interprets the claims payload. The payload is an opaque `String` end to end. The caller's platform verified the token at the edge (for Sikt subgraphs, MicroProfile JWT/SmallRye); the consumer's database hook transforms it into session state. JWT claims are JSON and both target databases speak JSON natively (`jsonb` operators on Postgres, `JSON_VALUE` on Oracle), so the easiest and safest place to transform the claims into what the database needs is inside the database, where the types, the entitlement data, and the enforcement already live. The database is the type authority; graphitron config maps no claim to any type, ever. Vocabulary, used consistently below: *claims* are the asserted payload that arrives, unvalidated; *identity* is the session state the connect hook mounts after validating them.

## Two lifecycles, two emitted surfaces

Connection setup is application-scoped; claims are request-scoped. The emitted surface separates them:

- **Application-scoped runtime.** Built once at wiring time: `var runtime = Graphitron.runtime(dataSource)` (emitted class name at the implementer's discretion). It holds the `DataSource`, the configured dialect, the acquisition and transaction machinery, and the session hooks baked from configuration. The consumer (or their framework) still owns pool creation and tuning; graphitron owns acquisition, transaction, and identity state on top of it. R45's database-per-tenant `Map<TenantId, DataSource>` is a runtime-construction overload, not a per-request parameter.
- **Per-request factory.** `runtime.newExecutionInput(claims, <contextArgs...>)` carries only per-request data: the opaque claims payload plus the schema's typed contextArguments exactly as today. The factory stashes values into the `graphQLContext` and never touches a connection; the connect hook fires later, inside the runtime's acquisition machinery, when the operation actually pins a connection.

contextArguments are untouched by this item. They remain the Java-typed channel anchored by `@service`/`@condition` signatures, and they are never sent to the database. The claims channel and the contextArgument channel are disjoint by design: one is string-natured and consumed by the database, the other is Java-typed and consumed by consumer code.

**Placement.** The machinery is emitted into the consumer's output package, following the `GraphitronContext` precedent: emitted code depends only on the app's own build output plus jOOQ, graphql-java, and the JDK (`javax.sql.DataSource` is JDK). Because the claims payload is a plain `String`, no auth-framework type appears anywhere in generated code; the consumer's adapter line is simply `runtime.newExecutionInput(jwt.getRawToken())`. Much of this machinery is schema-invariant, so the alternative placement (a Java 17 runtime jar, the `graphitron-jakarta-rest` category) was weighed deliberately: emission is chosen because it preserves the invariant that consumers add no graphitron artifact to their runtime classpath and generator/runtime version skew cannot exist, and because the patch channel is operationally equivalent, a fix reaches consumers as a generator version bump plus the regeneration their build already performs, the same act as bumping a jar version. The trade is still named out loud because session-state bugs are security bugs. Expected V0 emitted surface is roughly a handful of classes (runtime, pinned connection, session hook, a writable-transaction provider, execution instrumentation, factory additions); if implementation grows materially past that, the emit-vs-jar decision reopens rather than stretches. Emitted bodies must be valid Java 17, verified by the `graphitron-sakila-example` `<release>17</release>` compile.

**Additive by construction.** Nothing that exists changes shape: the R190 static factory, its eager `DSLContext` stash into the `GraphQLContext`, `getDslContext(env)`, and every fetcher emission stay exactly as they are. The runtime, its factory, and the acquisition machinery are new surfaces beside them, and the R190 form becomes the documented low-opinion escape hatch. The single subtraction is conditional and owned by R45: under database-per-tenant routing the caller-owns-everything form is not emitted (see the escape hatch below).

**How the pinned connection reaches fetchers.** Generated fetchers keep calling `getDslContext(env)` unchanged. On the runtime path the factory stashes no `DSLContext`; the runtime's execution instrumentation (wired by the emitted engine assembly, so consumers register nothing) acquires the connection, runs the connect hook, and publishes a `DSLContext` bound to the pinned connection and configured with the transaction provider under the same `GraphQLContext` key the escape-hatch path populates eagerly. Release and the disconnect hook ride execution completion through the same instrumentation. This is the precise R190 revision, stated as such: the key's producer moves from the factory to the acquisition machinery; every consumer of the key is untouched. Whether acquisition is eager at operation start or lazy on first use is an implementation decision inside this seam (introspection-only operations argue for lazy); the contract is that the connect hook has run before any operation SQL and that release runs exactly once at completion.

## The connection model

One operation pins exactly one connection: acquired from the runtime's `DataSource` when the operation starts executing, released when execution completes. This is safe because generated batch loaders execute SQL synchronously on the dispatch thread: `RowsMethodCall.batchLoaderLambda` emits `CompletableFuture.completedFuture(rows(keys, dfe))`, so no two fetches of one operation ever run SQL concurrently. That invariant is load-bearing and pinned twice: behaviourally, an `@ExecutionTier` assertion over an instrumented `DataSource` that a whole multi-fetch operation acquires exactly one connection; and at the emission site, where `RowsMethodCallTest`'s existing synchronous-body assertion gains javadoc naming this invariant plus a `{@link}` from the connection-lifecycle generator, so a future "make loaders async" change fails at the file being edited rather than as a distant execution-tier flake.

Multi-tenancy composes without new mechanism. Shared-database tenancy (RLS/VPD partition) needs nothing at all here: the tenant travels inside the claims payload and the connect hook applies it like any other claim. Database-per-tenant routing (R45) selects a `DataSource` from the runtime's map per divined tenant key; within one operation, all SQL for the same divined tenant shares one pinned connection, so a query touching N tenants pins N connections, one per distinct divined key. The two flavors compose: a per-tenant database and row enforcement within it.

## Session identity: mount and unmount through consumer-owned hooks

The `<sessionState>` block in the plugin `<configuration>` names two database callables:

```xml
<sessionState>
  <connect call="Pk_Ras.Connect"/>       <!-- (p_claims IN, p_handle OUT) -->
  <disconnect call="Pk_Ras.Disconnect"/> <!-- (p_handle IN) -->
</sessionState>
```

The contract is deliberately mechanical:

- **Connect at acquisition.** The runtime calls the connect hook with the claims payload immediately after pinning the connection, before any operation SQL. An optional `OUT` handle (an opaque string) is captured by the acquisition machinery and held as per-acquisition state. (The handle deliberately does not belong to the transaction provider; that seam is transaction-scoped and orthogonal to identity, see Operation-typed transactions.)
- **Disconnect at release.** The runtime calls the disconnect hook, binding the captured handle if one was declared, on *every* release path: normal completion, error, and abandoned/cancelled execution. Identity state is therefore acquisition-scoped: mounted for exactly the pinned lifetime of the connection, across every transaction the operation runs.
- **Fail closed.** A connect hook that raises (missing claim, unknown person, unentitled role) rejects the request before any SQL executes, surfaced as a request-level GraphQL error.
- **Evict on unmount failure.** If the disconnect hook fails or cannot run, the physical connection is closed and evicted from the pool, never returned. A connection whose identity cannot be proven unmounted does not get a next borrower.

Everything domain-shaped lives inside the hook, in the database's own language, behind whatever privilege fence the dialect offers: JSON parsing of the payload, claim validation, entitlement filtering, VPD context calls, RAS session management, `set_config` writes. Graphitron validates nothing about the payload at build time because there is nothing to validate. What codegen does validate is the pairing: a `<connect>` without a `<disconnect>`, or a handle declared on one call and not produced or bound by the other, is rejected at build time, not warned about, because a configuration whose identity mounts and provably never unmounts is a security hole. (A genuinely unmount-free hook design must opt out explicitly with an empty-disconnect marker, which the generation-time warning machinery names.)

**Oracle worked example (RAS).** Sikt's kernel API uses Oracle Real Application Security: a definer-rights package (privileges the connection user does not hold; the connection user has `EXECUTE` on the package and nothing more) whose connect procedure sets the VPD institution context, resolves the person from the national identity number in the claims, creates a RAS session via `DBMS_XS_SESSIONS.CREATE_SESSION`/`ATTACH_SESSION`, filters requested roles against database-side entitlement checks before `ENABLE_ROLE`, and returns the RAS session id as the handle; the disconnect procedure detaches and destroys by handle. RAS sessions can also be detached without destruction and reattached later, so a package can amortize session creation across requests entirely on its own: the connect hook receives the full claims every time and can decide create-versus-reattach internally, keeping its own identity-to-session mapping. The two-hook contract deliberately supports that without graphitron knowing it happens.

**Postgres.** The same contract: a `SECURITY DEFINER` connect function parses the payload (`jsonb`) and applies session-scoped state (`set_config(key, value, false)`); the disconnect function clears exactly what connect set. For the common low-ceremony case, a built-in sugar form generates both halves so a consumer writes no SQL at all:

```xml
<sessionState>
  <variables>
    <variable name="app.user_id" claim="sub"/>
  </variables>
</sessionState>
```

compiles to a graphitron-generated connect statement, `SELECT set_config('app.user_id', ($1::jsonb)->>'sub', false)`, and a matching disconnect that clears each variable. Both hook halves are emitted from one resolved variable-set carrier, so "disconnect clears exactly what connect set" is structural, not a prose agreement between two emitters. Even the sugar transforms in the database, in SQL; no JSON parser enters emitted Java. The sugar presumes the claims payload is bare claims JSON rather than a raw compact token; its documentation says so, and because the sugar is simultaneously the lowest-ceremony path and the weakest rung of the integrity gradient below, that documentation also points security-serious consumers at the function hook rather than letting the default steer them onto the convention fence unawares.

**Integrity gradient (documented, not solved).** How tamper-resistant the mounted identity is varies by dialect and pattern, and the docs must say so plainly. Oracle's package-bound contexts and RAS are an *enforced fence*: code on the connection cannot set state except through the trusted package. Plain Postgres GUCs are a *convention fence*: any SQL on the connection can overwrite them, and the real guarantee is that graphitron generates every statement and consumer `@service` code behaves. Between them sits the *cryptographic fence*, available today on both dialects through the function hook: pass the raw signed token as the payload, verify the signature inside the database, and have policies read identity only through verified state, so even arbitrary SQL on the connection cannot forge another user's identity. The RLS-assumed documentation carries this gradient, and the combination "Postgres + `<variables>` sugar + `@service` methods in the schema" earns a loud generation-time note, since that is the convention fence with consumer code on the connection.

## Operation-typed transactions

- **Query operations** run in autocommit on the pinned connection: no begin, no commit, no read-only demarcation, so a query costs no transaction round trips beyond its `SELECT`s. This is safe because the generated read surface is `SELECT`-only, and row-level enforcement (RLS/VPD) is the actual access boundary; a read-only *transaction* would only guard against a write the generated fetchers cannot emit. The two query surfaces graphitron does not control, `@routine` (an arbitrary database routine) and `@service` (consumer Java holding the pinned `DSLContext`), *can* write, and targeted read-only enforcement for exactly those is split into **R460** (it was originally blanket-enforced here, at a measured per-request round-trip cost that did not earn its keep on the controlled path).
- **Mutation operations** match graphql-java's serial execution of top-level mutation fields: each mutation field runs in its own writable transaction that commits on success and rolls back on failure before the next field begins, mirroring GraphQL's partial-success semantics (a failing field 2 rolls back its own write while field 1's committed write stands). This rides the shipped DML shape: `TypeFetcherGenerator`'s two-step DML fetchers already emit `dsl.transactionResult(tx -> ...)` per mutation field, and that call is the per-field boundary; graphitron pins the connection and opens no outer transaction. After a field commits, its payload selection set resolves in autocommit over the now-committed state. Identity needs no re-application anywhere in this sequence: it is acquisition-scoped and the connection is still pinned, so per-field commits and read-back projections all see the mounted state.

Mutation transaction boundaries route through one seam: a custom jOOQ `TransactionProvider` wrapped around the pinned connection (a `TransactionListener` cannot fill this role; listeners observe boundaries but cannot suppress a commit). *Commit policy* (`COMMIT`/`ROLLBACK_ONLY`) is global provider configuration, never site-declared; `ROLLBACK_ONLY` is the named seam R428's rollback-everything dev tool consumes without touching emission. Per-commit-site policy switching is rejected: a site opens a transaction to write, it does not get to choose commit-versus-rollback behaviour. Session identity is orthogonal to this whole section: hooks fire at acquisition and release, not at transaction boundaries.

## @defer and connection release

graphql-java can run deferred fetchers after the initial result is delivered; connection-per-operation release would close the pinned connection out from under them. V0 stance: incremental delivery stays off on the owned-connection path. Incremental support is opt-in per request, the factory owns the `ExecutionInput` wiring, and it simply does not opt in, so no fetcher can run after execution completes and release-at-completion is safe. Enabling `@defer` under owned connections is a follow-on item that must own the connection-lifetime story; a pinning test asserts the factory leaves incremental support disabled.

## Warnings, principles, and the escape hatch

- **RLS-assumed is a stated principle** in `docs/architecture`: graphitron cannot enforce that the database enforces row access; it assumes it, and says so. Non-RLS consumers remain responsible for exposing only safe surfaces (views, restricted schemas).
- **Generation-time warning** when no `<sessionState>` is configured on the runtime path: graphitron owns the boundary and could have mounted identity but was not told how. The warning names the exposure and the consumer's responsibility. Absence of config is a build-time fact, so the warning is a build-log fact.
- **Doc reconciliation:** `runtime-extension-points.adoc` opens by promising that generated code contains no auth checks, no transaction management, and no tenant-aware connection routing, all injected at runtime through `GraphitronContext`, and its RLS example is built on the `getDslContext` seam. This item supersedes that entire framing on the runtime path, not just the auth sentence: transaction management and identity mounting become emitted machinery, and the RLS example becomes the escape-hatch story. The page must be rewritten to describe both paths honestly rather than patched one sentence at a time.
- **Escape hatch:** the `DSLContext`-accepting factory form is kept as a documented low-opinion path whose javadoc states that the caller owns transaction demarcation and identity state and that graphitron's guarantees do not apply; it emits a one-time caller-owns-everything notice at wiring time. When database-per-tenant routing is configured, the escape hatch is structurally incapable of per-tenant acquisition, so it is simply not emitted; the enforcement is a missing-method compile error in the consumer's build, which leaves no misuse window at all.

## The contract change, and migration

This revises R190's factory contract: from a per-request, consumer-built `DSLContext` to an application-scoped runtime plus a per-request claims-carrying factory. The path is additive; the `DSLContext` form survives as the escape hatch above. The sakila example migrates to the runtime form as the first client, resolving its claims payload from the authenticated request.

## Relationship to other items

- **R45 (operation-divined tenant routing, Spec).** Routes through this item's runtime-held `Map<TenantId, DataSource>`. R45's sketched factory shape is stale in two independent ways and needs a sync edit during its own review: the `DataSource`/map parameters move to runtime construction, and the per-request factory gains the leading opaque claims parameter. Ownership split unchanged: this item owns acquisition, demarcation, and identity; R45 owns tenant classification, binding inference, and per-tenant partitioning of the batching machinery.
- **R190 (schema-driven ExecutionInput factory, landed).** The contract this item revises additively; see the additivity paragraph and migration above.
- **R428 (MCP in-process query execution).** Consumer of this item: feeds the runtime a `DataSource` from dev config; its rollback-everything mode is the provider's `ROLLBACK_ONLY` commit policy, consumed by name. The sync edit this item once called for on R428 has landed: R428's executor entry point now carries the opaque `claims` payload (`execute(conn, dialect, query, variables, claims, contextArgs)`) and constructs this item's runtime with it, and its prose treats claims and contextArguments as disjoint channels. The two Specs stay consistent on that split; any change to either side's claims-vs-contextArgs handling re-checks the other.
- **R410 (in-process incremental compile).** Independent; R428 sits on both.
- **R460 (targeted read-only enforcement for uncontrolled query paths).** Split out of this item. This item drops blanket read-only enforcement (queries run in autocommit on the pinned connection); R460 re-introduces read-only only where graphitron cannot prove the SQL is a read (`@routine`, `@service`), and owns the design space for how (cheaper per-request demarcation, a runtime strategy knob, or a read/write pool split). It builds on this item's per-operation instrumentation seam and the provider's commit-policy axis.

## Slices and test tiers

1. **Runtime and acquisition/release seam.** Emitted runtime over a `DataSource`; pinning; connect/disconnect hook invocation with handle threading; `@UnitTier` over a fake `DataSource` asserting acquisition/connect/.../disconnect/release ordering on success, error, and cancellation paths, fail-closed connect rejection, and eviction on disconnect failure. Also lands the emission-site tripwire for the sequential-SQL invariant.
2. **Operation-typed transactions.** Queries in autocommit on the pinned connection (no read-only transaction; blanket read-only enforcement is split into R460); per-field mutation commit/rollback riding the existing `transactionResult` emission through the custom `TransactionProvider`'s commit-policy axis; read-back projection in autocommit over the committed state; `@UnitTier` over the emitted `TransactionProvider` bytes (EmittedCodeHarness) asserting commit-policy settlement and savepoint nesting; emission and wiring completeness covered by the R410 compile-dependency oracle rather than a `@PipelineTier` test (the runtime, provider, and instrumentation are schema-invariant, so there is no schema-shaped assertion to make: the oracle's nodes and edges are the completeness check); `@ExecutionTier` asserting per-field commit/rollback independence and serial alignment with graphql-java's dispatch, exactly one connection per multi-fetch operation, and incremental support left disabled.
3. **Session hooks.** Function hooks with handle threading; the Postgres `<variables>` sugar generating both halves from one resolved carrier; build-time rejection of unpaired or handle-inconsistent `<sessionState>` config. `@UnitTier` over a fake connection asserting the emitted call shapes, handle capture and rebinding, that disconnect fires on both commit and rollback outcomes, and that eviction happens exactly when disconnect fails (and not otherwise); validator-tier assertions on the pairing rejections. `@ExecutionTier` (sakila, Postgres): an RLS-scoped read sees only permitted rows; a mutation's post-commit read-back still sees only permitted rows; state is demonstrably absent on the next acquisition of the same pooled connection. Oracle/RAS execution-tier coverage is a named follow-on (no Oracle container in the build); Oracle stays unit-tier in V0.
4. **Multi-tenant runtime map.** Exercises the runtime built over a `Map<TenantId, DataSource>` (R45's construction overload) and the *tenant-keyed acquisition seam* R45's emitted fetchers consume: given a tenant key, the seam selects that key's `DataSource`, pins one connection for it, and runs the connect hook on that connection. This slice is provable without R45, and must be: R429's per-request factory carries no tenant parameter (the tenant is operation-divined, never request-scope, so there is no request-scope value to thread), which means the tests cannot reach the seam through the factory and instead drive it directly with test-supplied keys against a fake tenant map, with no `TenantBinding` classification in scope. Slice 2's one-connection-per-operation invariant generalizes here to one pinned connection per *distinct divined key within an operation*: an operation touching N keys pins N connections, each with its own mounted identity. `@UnitTier`: the right `DataSource` selected per key, one connect/.../disconnect cycle per distinct key, an unknown key raising a request-level error before any SQL, and disconnect-failure eviction holding per connection. `@ExecutionTier`: per-tenant isolation (two keys in one request see disjoint rows) and the N-keys/N-connections count. The split with R45 is then exact: R429 proves that *given* a key, acquisition, per-key pinning, and isolation are correct; R45 proves the key is divined correctly and reshapes these execute-tier proofs onto inferred bindings, so the isolation proof lands once.
5. **Runtime/factory contract and migration.** Runtime construction, per-request factory, escape-hatch emission and its suppression under tenant routing; `@PipelineTier` on the emitted facade shapes; sakila example migrated as first client.
6. **Principle docs and warnings.** RLS-assumed principle, integrity gradient, the `runtime-extension-points.adoc` rewrite; docs plus `@UnitTier` on all three notice conditions: the no-`<sessionState>` generation-time warning, the convention-fence note (Postgres `<variables>` sugar combined with `@service` methods in the schema), and the escape hatch's wiring-time notice. The claims-payload docs include the adapter-side recipe for producing each payload form from MicroProfile JWT or a similar verified-token API (see the user-doc draft below): raw compact token for the cryptographic fence, JDK-only base64url decode of the JWS payload segment for bare claims JSON, and the JWE caveat. No MP JWT dependency enters any graphitron artifact; the recipe is consumer adapter code.

## Non-goals

- Creating or configuring the connection pool (the consumer's `DataSource` owns that).
- Verifying, parsing, or interpreting the claims payload (the edge verified it; the hook may re-verify in-database; graphitron never looks inside).
- Enforcing that row-level enforcement is actually enabled (impossible; assumed and warned).
- Enabling `@defer`/incremental delivery on the owned-connection path (named follow-on).
- Read-only enforcement of query operations (moved to R460; queries run in autocommit here).
- Cross-acquisition claims-keyed handle caching in the runtime (a future optimization the two-hook contract already permits DB-side).
- Per-tenant dialect variation, distributed transactions, application hot-reload.

## First-client user-doc draft

> **Graphitron manages the database connection for you.** At startup you give graphitron a `DataSource` and the dialect. For every request graphitron takes a connection, mounts your caller's identity on it, commits each mutation field separately, and unmounts the identity before the connection goes back to the pool.
>
> **Identity goes to the database, not to Java code.** You pass the authenticated request's token (or any string you choose) to `newExecutionInput`; graphitron hands it, untouched, to a connect function you write in your database. That function parses the claims, validates what it must, and sets your row-level security state; a disconnect function clears it. Graphitron guarantees the pair runs at connection mount and unmount and that a connection whose unmount failed is never reused. If you configure no session state, graphitron warns you at build time: an unsecured direct-to-database API is a data-exposure risk.
>
> **Producing the claims payload from MicroProfile JWT (or any JWS bearer).** The two payload forms come straight off the injected `JsonWebToken`. For the signed compact token (the cryptographic-fence form; your connect hook decodes or verifies it in-database), pass `jwt.getRawToken()`. For bare claims JSON (the form the `<variables>` sugar expects), the payload segment of a JWS already is the claims JSON, so decode it with the JDK alone, no JSON parser needed:
>
> ```java
> String claims = new String(
>     Base64.getUrlDecoder().decode(jwt.getRawToken().split("\\.")[1]),
>     StandardCharsets.UTF_8);
> return runtime.newExecutionInput(claims);
> ```
>
> Note that `getRawToken()` returns the three-segment compact serialization, not JSON; passing it where claims JSON is expected fails at the hook's `jsonb` cast. If your platform hands you an encrypted token (JWE), the payload segment is ciphertext; rebuild the claims JSON from the platform's claim accessors instead. Which form to pick is the integrity-gradient decision: the signed token keeps working even if you must distrust SQL running on the connection; bare claims JSON trusts the edge verification you already rely on everywhere else.

## Notes on discarded earlier designs

Kept as one-liners for archaeology; none of these survive in the design above.

- *Session state sourced from contextArguments:* an identity used only for row enforcement has no `@service`/`@condition` site to anchor its type, and the Java-typed channel was the wrong carrier for string-natured database state.
- *A claim-binding vocabulary in the Mojo block* (per-parameter claim names, collection types, constants, optional flags): dissolved once transformation moved into the database hook; JSON is the carrier and the database is the type authority.
- *A built-in direct `DBMS_SESSION.SET_CONTEXT` Oracle strategy:* Oracle application contexts are package-bound (`CREATE CONTEXT ... USING`), so raw calls from the connection fail; real deployments need RAS attach/detach, not variable writes.
- *Transaction-scoped session state re-applied at every begin:* redundant once acquisition and release are owned; it forced a scope axis, per-begin re-fire machinery, and a read-back re-application rule, all deleted by acquisition scoping.
- *MicroProfile-JWT-typed factory surfaces, a `Function<String,String>` claims source, and a `graphitron-mp-jwt` adapter artifact:* all dissolved by the opaque `String` payload.
- *`DataSource` as a per-request factory parameter:* connection setup is application-scoped; split into the runtime object.

---
id: R428
title: "MCP tool executes GraphQL against generated resolvers in-process (graphitron:dev)"
status: Ready
bucket: feature
priority: 3
theme: dev-loop
depends-on: []
created: 2026-07-03
last-updated: 2026-07-11
---

# MCP tool executes GraphQL against generated resolvers in-process (graphitron:dev)

## In one paragraph

The `graphitron:dev` MCP server today lets an agent *discover* (catalog, schema, code, diagnostics per
R118) and *compile* (R410, shipped) the generated resolvers in-process. What it still cannot do is
answer the question that actually closes the authoring loop: **what does this query return?** Today that
requires standing up Quarkus (or any app server) with a live datasource and running the query by hand. This
item adds an MCP tool that executes a GraphQL query/mutation **against the generated resolvers, in-process,
in the dev JVM**, and returns the result. It is the query-execution capability R410 explicitly scopes out
("a separate item; this one delivers the loadable classes it stands on"): R410 makes the generated
`Graphitron` facade a loaded, runnable `.class`; this item drives it. For an agent this turns graphitron
into a closed loop, validate-error → compile-error → *real result*, without leaving the dev process.

## Relationship to R410 and R118

- **Builds on R410 (shipped, so no longer in `depends-on`).** The generated `Graphitron` facade lives in
  the consumer's `<outputPackage>` and only exists as a `.class` because R410 compiles it into
  `target/graphitron-classes` and loads it on the dev JVM. No R410, nothing to execute against.
- **Sibling of R118, not a slice.** R118's spine is "read the live `Workspace` model; structured tools need
  no store; only two tools are semantic." Execution reads neither the model nor RAG; it stands on R410's
  compiled classes plus a live DB connection. Different foundation, so it is a peer MCP tool hosted on the
  R341 transport/lifecycle seam, not a member of R118's tool surface. It reuses R118's *posture* (loopback
  only, degrade-gracefully when unconfigured) but none of its embedder/vector-store machinery.

## The load-bearing insight: generate the executor, do not reflect the facade

graphitron's superpower is that it generates and compiles Java in-process (R410). So the wiring this tool
needs is not obtained through a *runtime seam* (an SPI, a `ServiceLoader`, a CDI container); it is
**generated and compiled like everything else**. graphitron emits one small class into the output package,
`GraphitronDevExecutor`, compiled in the same R410 pass over the same classpath, exposing a single stable
entry point whose signature is **JDK types only**:

```java
// generated; compiled in-process alongside the rest of the closure
public static String execute(
    java.sql.Connection conn, String dialect, String query,
    java.util.Map<String,Object> variables, String claims, java.util.Map<String,Object> contextArgs);
```

Inside, it references the generated facade and R429's runtime **symbolically** (compile-time, since it is
compiled against them): it wraps the connection in a single-connection `DataSource`, constructs the R429
runtime with the requested dialect and the `ROLLBACK_ONLY` commit policy, calls the per-request factory with
the opaque `claims` payload, binds `contextArgs` to the schema's typed context parameters using the *same
classified model that produced the facade*, executes, and returns `ExecutionResult.toSpecification()`
serialized to a JSON string. This buys three simplifications at once:

- **No provider seam.** No `GraphitronDevExecution` SPI, no `ServiceLoader`, no CDI/Weld container, no
  consumer-written provider class. The connection is plain config data (below).
- **No reflection over the schema-varying `newExecutionInput` signature**, and no runtime coercion of
  context-arg values to their Java types: codegen absorbs both, type-checked at compile time.
- **A JDK-only reflection boundary.** The host reflects exactly one method taking `Connection`/`String`/
  `Map` and returning `String`; no jOOQ or graphql-java type crosses the host↔generated boundary, so there
  is zero shared-type coupling to manage. The `GraphitronDevExecutor` generator is a sibling consumer of the
  classified model alongside `GraphitronFacadeGenerator`, one model, many consumers.

The executor regenerates and recompiles exactly when the schema's context arguments change, which is
already what R410's incremental engine does for every generated source, so it adds nothing to the loop.

## Execution primitive and host responsibilities

The sufficient execution substrate is a plain JDBC `Connection` wrapped in a `DSLContext` with the correct
dialect. The **host** (dev-loop code, not generated) owns the connection lifecycle per invocation:

```
open Connection            (url/user/password/dialect from config)
resolve claims payload   (GRAPHITRON_DEV_CLAIMS; opaque string, passed through untouched)
reflect GraphitronDevExecutor.execute(conn, dialect, query, variables, claims, contextArgs)
close                      (transactions, session hooks, and rollback ran inside, via R429's machinery)
```

Rollback-by-default is R429's `ROLLBACK_ONLY` commit policy on the executor-constructed runtime (the named
commit-suppression seam on its transaction provider), so read *and* write operations are observable without
persisting anything. An opt-in commit is a later nicety, not V0.

## Database configuration

Plain connection coordinates, settable **both** in the plugin `<configuration>` block **and** via
environment variables (`GRAPHITRON_DEV_DB_URL` / `_USER` / `_PASSWORD` / `_DIALECT`; env overrides pom so a
developer keeps credentials out of the checked-in pom):

- `url`, `user`, `password`
- `claims`: the opaque per-request claims payload handed to R429's connect hook
  (`GRAPHITRON_DEV_CLAIMS`; inline, or `@/path/to/file` to keep tokens out of the environment listing).
  Required when the schema configures `<sessionState>`; see the section below.
- `dialect`: **explicit, enumerated, never defaulted to Postgres.** graphitron is already multi-dialect
  (see the `DialectRequirement` classification: bulk DML requires the Postgres family, most operations are
  portable), and Sikt runs Oracle. `POSTGRES` and `ORACLE` are the V0 targets; the consumer's JDBC driver is
  already on the R410-resolved classpath (jOOQ codegen and their app both need it).

When no connection is configured the execution tool is **absent / disabled with a clear message**, exactly
the degrade-gracefully posture the RAG tools use. Every other MCP tool keeps working with no DB.

## Session identity, transactions, and RLS/RAS: delegated to R429

R429 (`connection-transaction-lifecycle`) gives the generated runtime ownership of connection acquisition,
operation-typed transactions, and session identity: an opaque per-request claims payload (typically the
caller's JWT) is handed to a consumer-owned database *connect hook* at acquisition, with a paired
*disconnect hook* at release. This item is a **consumer** of that machinery rather than reinventing it:

- The executor wraps its single dev connection in a `DataSource` and executes through the R429 runtime, so
  the dev loop exercises the *same* acquisition/hook/transaction path a real app does, including the
  consumer's own connect hook. Higher execution fidelity for free.
- **Claims are developer-supplied and opaque.** Graphitron does not know what the consumer's connect hook
  expects; that opacity is R429's point, the hook is the only party that understands the payload. The
  developer, who owns the hook, supplies the payload via `GRAPHITRON_DEV_CLAIMS`, and the tool passes it
  through untouched, exactly as the production factory does. This channel is disjoint from `contextArgs`,
  which remain the Java-typed service/condition channel and carry no session state.
- **Fail loud, never skip.** If the schema configures `<sessionState>` and no dev claims are supplied, the
  execute tool fails with a pointer at the config knob. Silently skipping the connect hook would run dev
  queries under a different security posture than production: seeing nothing (RLS denies), or on a
  convention-fence Postgres setup, seeing everything.
- **The hook is the validator, and its errors are the feedback loop.** The tool surfaces connect-hook
  failures verbatim as the tool result. An agent that supplies a payload missing a claim gets the hook's
  own error message and corrects the payload; no graphitron-side payload validation exists, by design.
- **Rollback-by-default is R429's `ROLLBACK_ONLY` commit policy**, consumed by name; exploration cannot
  persist writes. (The "no emission changes" expectation did not survive contact with the shipped DML
  two-step; see the deferred-rollback implementation decision below.)

**Security posture.** The dev tool grants no capability the dev database credentials do not already grant:
whoever holds them can call the connect hook with any payload, because the hook validates *entitlement*
(is this person a saksbehandler in this database), not *authentication*, which was the edge's job. The
fences are config separation (the tool reads `GRAPHITRON_DEV_DB_*`, never the app's runtime datasource),
the loopback-only posture, `ROLLBACK_ONLY` for writes, and the claims payload for reads. That last one
earns its own sentence: rollback protects against mutation, not disclosure, so against a prod-copy database
the configured claims decide which identity mounts and therefore what an agent can *read*; pin
low-privilege claims there. A per-call claims override on the execute tool (for multi-identity RLS probing:
does the student role really not see other students' rows?) is opt-in via config, default off, so shared or
sensitive dev databases keep one pinned identity. One divergence is by design: a consumer whose hook
verifies token signatures in-database (R429's cryptographic fence) will reject hand-written claims JSON,
correctly; the dev claims payload there must be a genuinely issued dev token. Graphitron does not soften this, because a hook with a dev-mode bypass
would be a hole in the production fence.

R429 shipped before implementation began, so the R429-not-landed fallback this section used to carry is
moot and was removed; the executor consumes the real runtime machinery.

**Implementation decision: the deferred-rollback realization of `ROLLBACK_ONLY` (reviewer, note the R429
contract change).** The shipped R449/R451 DML fetchers are a two-step (write inside
`dsl.transactionResult(...)`, then a post-settle SELECT projecting the payload) precisely so the response
observes committed state. Under `ROLLBACK_ONLY`'s original realization (settle each field by rolling back),
the post-settle read-back found nothing: `createFilm` returned `null` data with no errors, falsifying this
spec's "write operations are observable" promise (caught at the execution tier against real Postgres, and
unfixable from the executor side: per-field commits cannot be un-committed). Fixed by changing only the
`ROLLBACK_ONLY` arms of the generated transaction provider to a dev observe-then-discard topology: the
operation transaction opens once and defers across field settles, each field boundary becomes a savepoint
(field independence preserved; a failed field discards exactly its own writes), read-backs run inside the
open transaction and observe the writes, and the already-shipped `PinnedConnection.release` discards the
whole transaction before the disconnect hook. `COMMIT` behavior is byte-identical and no production path
constructs a `ROLLBACK_ONLY` provider, but this *is* a behavioral-contract change to an R429-shipped
artifact (top-level commit no longer restores autocommit or fires the settle seam under this policy), so it
is flagged here for the In Review gate rather than pre-answered by the enum javadoc's R428 assignment.

**Stated fidelity limitation (from the principles-architect consult).** Holding the operation transaction
open structurally conflicts with the per-settle session-identity re-fire contract (hooks assume autocommit,
no open transaction), so under `ROLLBACK_ONLY` the inter-field re-fire never fires: dev mode does not
exercise a consumer's unconfirmed connect/disconnect re-fire pair between mutation fields. Mounted identity
itself is unaffected (session-scoped state established at acquire; the release rollback cannot revert it).
This is documented in the provider javadoc and pinned by a provider unit test rather than left silent.
Relatedly, the "always rolled back" promise is scoped to mutation-field transactions: a *query*-path
`@service`/`@routine` that writes runs in autocommit and escapes the policy entirely (pre-existing,
R460-adjacent; the user doc scopes the claim).

## Considered and rejected

- **A `ServiceLoader` / FQCN-configured `DSLContext` provider SPI.** The instinct was to have the consumer
  hand graphitron a `DSLContext`. Rejected because graphitron can *generate* the glue instead (see the
  load-bearing insight), so no runtime provider seam is needed at all; the connection is plain config.
- **Bootstrapping a CDI container (Quarkus ARC / Weld SE) over the project sources.** `graphitron:dev` runs
  in the Maven plugin JVM, not the app JVM, so there is no live container; ARC is build-time-wired (not
  designed for runtime discovery over an arbitrary classpath), and Weld SE would be a different container
  than the consumer's ARC, so `@ConfigProperty` injection and extension-produced beans (notably the
  synthetic, config-driven `AgroalDataSource`) would be absent, the datasource is precisely the bean *not*
  in the project sources. It would add a heavyweight dependency and a fidelity trap without solving
  connection sourcing.
- **An out-of-process bridge into the running `quarkus:dev`.** Where a real container and connected
  datasource already exist, this is where CDI would be natural, but it directly contradicts R410/R118's
  premise (execute in-process, *no app server needed*). Named here because it is the tempting Quarkus-shop
  reflex.
- **Reflecting the variadic `newExecutionInput` from the host.** Doable but fiddly (find the method, order
  params per R190, coerce context-arg values). The generated fixed-signature executor removes it.

## Federation (out of V0)

`Graphitron.newGraphQL()` covers only the non-federation default. A federation subgraph needs the two-arg
`buildSchema(schemaCustomizer, federationCustomizer)` and an entity fetcher, so executing `_entities`
against a federated schema is a follow-on: generate a federation-aware executor variant. V0 targets the
non-federation path; flag `_entities` execution as a known gap.

## Review feedback (In Review -> Ready, 2026-07-11)

Independent In Review gate; sent back for rework. Two material findings, both must be resolved before
the next In Review handoff.

1. **Build is red (automatic rework).** `mvn install -Plocal-db` fails at
   `graphitron-maven-plugin`: `MojoDocCoverageTest.everyMojoParameterHasADocRowAndViceVersa`
   (MojoDocCoverageTest.java:111) reports the new editable Mojo parameter `devDatabase`
   (`DevMojo.java:121`, slice 4) has no matching row in
   `docs/manual/reference/mojo-configuration.adoc`. Slice 4 added user docs to
   `mcp-agent-context.adoc` / `dev-loop.adoc` but missed the audited reference table.
   Fix: add the `devDatabase` row (and any nested sub-parameters the audit expects) to
   `mojo-configuration.adoc`, then confirm the full `-Plocal-db` reactor is green.

2. **Code-string assertions on generated method bodies (review-enforced ban).**
   `GraphitronDevExecutorGeneratorTest` asserts on `executeMethod(...).code().toString()` in five
   tests: `execute_wiresTheRuntimeEngineWithRollbackOnlyCommitPolicy`,
   `execute_buildsOwnedInputAndReturnsToSpecificationJson`,
   `noSessionState_normalizesNullClaimsInsteadOfFailing`,
   `sessionStateConfigured_failsLoudOnMissingClaims`, and
   `contextArguments_bindAlphabeticallyIntoTheOwnedFactoryCall`. These pin generated *implementation*
   text (`new ...GraphitronRuntime(...)`, `.query(query)`, `engine.execute(input)`,
   `if (claims == null || claims.isBlank())`, the binding strings), which
   `development-principles.adoc` bans at every tier ("Code-string assertions on generated method
   bodies are banned at every tier ... review-enforced at test-review time"). The sibling pipeline
   test already documents the rule and omits them; the unit test must do the same. The behaviours
   these strings stand in for are already pinned behaviourally: ROLLBACK_ONLY / observable-write /
   fail-loud claims by `DevExecuteExecutionTest` (execution tier), the deferred topology by
   `GraphitronTransactionProviderGeneratorTest`, and alphabetical binding by the L5 sakila-example
   compile against the facade's typed factory. Fix: drop or convert the five body-string tests;
   keep the structural `MethodSpec`/`TypeSpec` assertions (signature, federation gate, helper
   emission, nested DataSource), which are not body-string assertions and are fine.

Not blocking, but note for the next pass: the deferred-rollback `ROLLBACK_ONLY` topology and its
stated fidelity limit (no mid-operation `afterSettle` re-fire; `release()` at
`ConnectionRuntimeClassGenerator` rollback+autocommit-restore discards the deferred transaction) were
reviewed and are architecturally sound; the R429 contract-change flag is satisfied. The
`GraphitronDevExecutor` generator, `DevQueryExecutor` host boundary, and `ExecuteTool` wiring are
otherwise aligned with the spec.

## Slices and test tiers (implementation status)

1. **`GraphitronDevExecutor` generator.** Shipped at `5488bc4`. `GraphitronDevExecutorGeneratorTest`
   (unit: JDK-only signature pin, ROLLBACK_ONLY wiring, fail-loud vs normalize arms, alphabetical binding,
   federation gate) + `GraphitronDevExecutorGeneratorPipelineTest` (classifier-driven schema keeps the
   signature fixed). Emission decisions: `ExecutionResult.toSpecification()` serialized via
   `org.jooq.tools.json.JSONValue` (jOOQ is always on the generated classpath, so the spec's String-return
   signature holds with no new dependency); federation schemas get no executor (the follow-on variant named
   below); the compile graph models the unit unconditionally (superset-safe, GraphitronSessionHook
   precedent) with precise edges, covered by the R410 completeness oracle.
2. **Host execution path.** Shipped at `ae91f86` (`DevQueryExecutor` in graphitron-mcp). Fresh
   platform-parented `URLClassLoader` per call over `target/graphitron-classes` + the consumer compile
   classpath (always the current compile round's bytecode, no staleness bookkeeping); JDBC driver discovered
   via `ServiceLoader` on the project loader (DriverManager resolves against the plugin realm and is
   bypassed); TCCL pointed at the generated world during the call; executor-side failures surfaced with
   their own message verbatim. The spec's `setAutoCommit(false)`/rollback-in-`finally` (R429-fallback
   residue) became a defense-in-depth leftover-transaction rollback before close; R429's machinery owns
   transactions inside. `DevQueryExecutorTest` drives a synthetic executor + fake driver compiled at test
   time.
3. **Claims and session hooks.** Shipped across `5488bc4` (generated fail-loud arm, opaque pass-through),
   `ae91f86` (verbatim error surfacing), and the deferred-rollback provider change (below). Execution-tier
   Postgres round-trip in `DevExecuteExecutionTest`: valid claims mount through the real `<variables>` hook
   (fail-closed runtime, so success proves the mount), malformed claims surface Postgres's own jsonb error
   (the payload demonstrably reached the real hook), missing claims fail loudly naming
   `GRAPHITRON_DEV_CLAIMS`, and rollback leaves no trace. The RLS-scoping half of the hook contract stays
   pinned by `SessionHookExecutionTest` on the same emitted hook.
4. **DB configuration + degrade-gracefully.** Shipped at `29420c8`. `<devDatabase>` block + env-wins
   overrides (`GRAPHITRON_DEV_DB_URL/_USER/_PASSWORD/_DIALECT`, `GRAPHITRON_DEV_CLAIMS`,
   `GRAPHITRON_DEV_DB_ALLOW_CLAIMS_OVERRIDE`); explicit enumerated dialect (missing/unsupported fails the
   goal loudly; absent url disables quietly); claims `@file` resolved per call; per-call claims override
   rejected unless opted in; the execute tool registered on `GraphitronMcpServer` exactly when configured
   (absent, not degrading). `DevMojoTest` + `ExecuteToolTest` + `GraphitronMcpServerTest` extensions; user
   docs moved from this plan's first-client draft into `mcp-agent-context.adoc` / `dev-loop.adoc`.
5. **Execution-tier integration.** `DevExecuteExecutionTest` (sakila example, real Postgres): the fidelity
   check (executor JSON byte-equal to a direct in-app owned-path execution), variables binding, the
   observable-write + no-trace mutation proof, and field independence under the deferred topology (good
   field's payload visible, bad field errors, nothing persists).

## Non-goals

- Persisting mutations (rollback-by-default; opt-in commit is a later nicety).
- Federation `_entities` execution (follow-on).
- Any runtime DI / provider / container mechanism (dissolved by codegen).
- Managing credentials or a connection pool beyond a single per-invocation connection.

## First-client user-doc draft

> **Run a query without an app server.** In a dev session, point graphitron at your database once (in the
> plugin config or via `GRAPHITRON_DEV_DB_*` environment variables: JDBC url, user, password, and dialect,
> `POSTGRES` or `ORACLE`). The MCP `execute` tool then runs a GraphQL query or mutation against your
> generated resolvers *in-process*, no Quarkus, no deploy, and hands back exactly what the query returns.
> Mutations run in a transaction that is always rolled back, so you can probe freely without changing data.
>
> **Row-level security still applies.** If your schema mounts identity (graphitron's `<sessionState>`
> connect hook), give the dev tool a claims payload via `GRAPHITRON_DEV_CLAIMS`: the same string your
> connect function expects in production (claims JSON, or a genuinely issued dev token if your function
> verifies signatures). The tool runs your real connect function and sees only what the mounted identity is
> permitted, just like your running service. If claims are required and missing, the tool says so loudly
> instead of running unsecured; if your connect function rejects the payload, you get its error message
> verbatim, so you can fix the payload and retry.
>
> **No database configured?** The `execute` tool simply does not appear; every other dev tool (schema,
> catalog, diagnostics, docs) works with no database at all.


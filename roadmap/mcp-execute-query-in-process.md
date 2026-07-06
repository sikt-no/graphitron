---
id: R428
title: "MCP tool executes GraphQL against generated resolvers in-process (graphitron:dev)"
status: Spec
bucket: feature
priority: 3
theme: lsp
depends-on: [connection-transaction-lifecycle]
created: 2026-07-03
last-updated: 2026-07-06
---

# MCP tool executes GraphQL against generated resolvers in-process (graphitron:dev)

## In one paragraph

The `graphitron:dev` MCP server today lets an agent *discover* (catalog, schema, code, diagnostics per
R118) and, once R410 lands, will *compile* the generated resolvers in-process. What it still cannot do is
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
    java.util.Map<String,Object> variables, String identity, java.util.Map<String,Object> contextArgs);
```

Inside, it references the generated facade and R429's runtime **symbolically** (compile-time, since it is
compiled against them): it wraps the connection in a single-connection `DataSource`, constructs the R429
runtime with the requested dialect and the `ROLLBACK_ONLY` commit policy, calls the per-request factory with
the opaque `identity` payload, binds `contextArgs` to the schema's typed context parameters using the *same
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
resolve identity payload   (GRAPHITRON_DEV_IDENTITY; opaque string, passed through untouched)
reflect GraphitronDevExecutor.execute(conn, dialect, query, variables, identity, contextArgs)
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
- `identity`: the opaque per-request identity payload handed to R429's connect hook
  (`GRAPHITRON_DEV_IDENTITY`; inline, or `@/path/to/file` to keep tokens out of the environment listing).
  Required when the schema configures `<sessionState>`; see the section below.
- `dialect`: **explicit, enumerated, never defaulted to Postgres.** graphitron is already multi-dialect
  (see the `DialectRequirement` classification: bulk DML requires the Postgres family, most operations are
  portable), and Sikt runs Oracle. `POSTGRES` and `ORACLE` are the V0 targets; the consumer's JDBC driver is
  already on the R410-resolved classpath (jOOQ codegen and their app both need it).

When no connection is configured the execution tool is **absent / disabled with a clear message**, exactly
the degrade-gracefully posture the RAG tools use. Every other MCP tool keeps working with no DB.

## Session identity, transactions, and RLS/RAS: delegated to R429

R429 (`connection-transaction-lifecycle`) gives the generated runtime ownership of connection acquisition,
operation-typed transactions, and session identity: an opaque per-request identity payload (typically the
caller's JWT) is handed to a consumer-owned database *connect hook* at acquisition, with a paired
*disconnect hook* at release. This item is a **consumer** of that machinery rather than reinventing it:

- The executor wraps its single dev connection in a `DataSource` and executes through the R429 runtime, so
  the dev loop exercises the *same* acquisition/hook/transaction path a real app does, including the
  consumer's own connect hook. Higher execution fidelity for free.
- **Identity is developer-supplied and opaque.** Graphitron does not know what the consumer's connect hook
  expects; that opacity is R429's point, the hook is the only party that understands the payload. The
  developer, who owns the hook, supplies the payload via `GRAPHITRON_DEV_IDENTITY`, and the tool passes it
  through untouched, exactly as the production factory does. This channel is disjoint from `contextArgs`,
  which remain the Java-typed service/condition channel and carry no session state.
- **Fail loud, never skip.** If the schema configures `<sessionState>` and no dev identity is supplied, the
  execute tool fails with a pointer at the config knob. Silently skipping the connect hook would run dev
  queries under a different security posture than production: seeing nothing (RLS denies), or on a
  convention-fence Postgres setup, seeing everything.
- **The hook is the validator, and its errors are the feedback loop.** The tool surfaces connect-hook
  failures verbatim as the tool result. An agent that supplies a payload missing a claim gets the hook's
  own error message and corrects the payload; no graphitron-side payload validation exists, by design.
- **Rollback-by-default is R429's `ROLLBACK_ONLY` commit policy**, consumed by name; no emission changes,
  and exploration cannot persist writes.

**Security posture.** The dev tool grants no capability the dev database credentials do not already grant:
whoever holds them can call the connect hook with any payload, because the hook validates *entitlement*
(is this person a saksbehandler in this database), not *authentication*, which was the edge's job. The
fences are config separation (the tool reads `GRAPHITRON_DEV_DB_*`, never the app's runtime datasource),
the loopback-only posture, `ROLLBACK_ONLY` for writes, and the identity payload for reads. That last one
earns its own sentence: rollback protects against mutation, not disclosure, so against a prod-copy database
the configured identity decides what an agent can *read*; pin a low-privilege identity there. A per-call
identity override on the execute tool (for multi-identity RLS probing: does the student role really not see
other students' rows?) is opt-in via config, default off, so shared or sensitive dev databases keep one
pinned identity. One divergence is by design: a consumer whose hook verifies token signatures in-database
(R429's cryptographic fence) will reject hand-written claims JSON, correctly; the dev identity there must
be a genuinely issued dev token. Graphitron does not soften this, because a hook with a dev-mode bypass
would be a hole in the production fence.

If R429's runtime has not landed when R428 is implemented, the fallback is R428's minimal host-side
connection handling (open connection, `setAutoCommit(false)`, rollback in `finally`) with the execute tool
refusing to run when `<sessionState>` is configured (the fail-loud rule above; without R429 there is no
hook machinery to honor it). R429 then subsumes the fallback. This keeps R428 shippable without blocking on
the larger runtime item.

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

## Slices and test tiers

1. **`GraphitronDevExecutor` generator.** Emit the fixed-signature executor as a sibling consumer of the
   classified model (context-arg binding reads the same classification `GraphitronFacadeGenerator` reads).
   `@UnitTier` on the generator; `@PipelineTier` asserting the emitted call binds a realistic schema's
   contextArguments in R190 order. Folds into R410's generated closure so the incremental engine compiles it.
2. **Host execution path.** Connection open from config, `setAutoCommit(false)`, rollback-in-`finally`,
   reflect the one JDK-typed entry point, marshal the JSON result. `@UnitTier` over a synthetic executor;
   the reflection boundary is JDK-types-only so the test needs no generated classpath.
3. **Identity and session hooks.** Executor constructs the R429 runtime with `ROLLBACK_ONLY`; identity
   payload passed through opaquely; fail-loud when `<sessionState>` is configured and no identity is
   supplied; connect-hook failures surfaced verbatim as the tool result. `@UnitTier` on the fail-loud and
   error-surfacing rules; a Postgres round-trip asserting an RLS-scoped read sees only the configured
   identity's permitted rows and that rollback leaves no trace.
4. **DB configuration + degrade-gracefully.** pom `<configuration>` and env resolution (env wins), explicit
   dialect, identity payload (inline and `@file` forms) and the opt-in per-call identity-override flag
   (default off), tool absent/disabled with a clear message when unconfigured. `DevMojoTest` extension.
5. **Execution-tier integration.** A real query and a real mutation (rolled back) against the sakila example
   DB through the tool, asserting the returned JSON matches a direct in-app execution, the fidelity check
   that the tool sees what the app sees.

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
> connect hook), give the dev tool an identity payload via `GRAPHITRON_DEV_IDENTITY`: the same string your
> connect function expects in production (claims JSON, or a genuinely issued dev token if your function
> verifies signatures). The tool runs your real connect function and sees only what that identity is
> permitted, just like your running service. If identity is required and missing, the tool says so loudly
> instead of running unsecured; if your connect function rejects the payload, you get its error message
> verbatim, so you can fix the payload and retry.
>
> **No database configured?** The `execute` tool simply does not appear; every other dev tool (schema,
> catalog, diagnostics, docs) works with no database at all.


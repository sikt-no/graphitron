---
id: R428
title: "MCP tool executes GraphQL against generated resolvers in-process (graphitron:dev)"
status: Spec
bucket: feature
priority: 3
theme: lsp
depends-on: [connection-transaction-lifecycle]
created: 2026-07-03
last-updated: 2026-07-05
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
    java.sql.Connection conn, String dialect,
    String query, java.util.Map<String,Object> variables, java.util.Map<String,Object> contextArgs);
```

Inside, it references `Graphitron.newGraphQL()` / `Graphitron.newExecutionInput(dsl, …)` **symbolically**
(compile-time, since it is compiled against the facade), builds the `DSLContext` with the requested dialect,
binds `contextArgs` to the schema's typed context parameters using the *same classified model that produced
the facade*, executes, and returns `ExecutionResult.toSpecification()` serialized to a JSON string. This
buys three simplifications at once:

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
setAutoCommit(false)
set session state          (RLS/RAS seam, below; keyed off the request's contextArgs)
reflect GraphitronDevExecutor.execute(conn, dialect, query, variables, contextArgs)
rollback; close
```

Rollback-by-default falls out of host ownership: the host wraps the reflective call in a transaction it
always rolls back, so read *and* write operations are observable without persisting. An opt-in commit is a
later nicety, not V0.

## Database configuration

Plain connection coordinates, settable **both** in the plugin `<configuration>` block **and** via
environment variables (`GRAPHITRON_DEV_DB_URL` / `_USER` / `_PASSWORD` / `_DIALECT`; env overrides pom so a
developer keeps credentials out of the checked-in pom):

- `url`, `user`, `password`
- `dialect` — **explicit, enumerated, never defaulted to Postgres.** graphitron is already multi-dialect
  (see the `DialectRequirement` classification: bulk DML requires the Postgres family, most operations are
  portable), and Sikt runs Oracle. `POSTGRES` and `ORACLE` are the V0 targets; the consumer's JDBC driver is
  already on the R410-resolved classpath (jOOQ codegen and their app both need it).

When no connection is configured the execution tool is **absent / disabled with a clear message**, exactly
the degrade-gracefully posture the RAG tools use. Every other MCP tool keeps working with no DB.

## Session state, transactions, and RLS/RAS — delegated to R429

The earlier draft carried an open fork here (config-driven session statements vs a Java hook) for setting
RLS/RAS session state on the dev connection. That concern has been **promoted into its own item, R429**
(`connection-transaction-lifecycle`), which gives the *generated runtime* a strong opinion on connection
acquisition, operation-typed transaction mode (query read-only; mutation commit-then-project), and
RLS-first transaction-local session state with built-in Postgres/Oracle/generic strategies. This item is a
**consumer** of that machinery rather than reinventing it:

- The dev tool builds a `DataSource` from its `GRAPHITRON_DEV_DB_*` config (below) and feeds graphitron's
  R429 runtime, so it exercises the *same* connection/transaction/session-state path a real app does, higher
  execution fidelity for free.
- **Rollback-by-default is R429's transaction model with commit suppressed:** the dev tool runs every
  operation (query or mutation) as if read-only and never commits, so exploration cannot persist. An opt-in
  commit is a later nicety.
- Session state (the current user/tenant RLS values) comes from R429's session-state strategy, keyed off the
  same contextArgs the executor binds. No R428-local session-state seam.

If R429 has not landed when R428 is implemented, the fallback is R428's minimal host-side connection handling
(open connection, `setAutoCommit(false)`, rollback in `finally`) with a generic session-statement list; R429
then subsumes it. This keeps R428 shippable without blocking on the larger runtime item.

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
3. **Session-state seam.** R429's session-state strategy keyed off contextArgs, with the commit policy
   set to R429's `ROLLBACK_ONLY` (the named commit-suppression seam on its provider). Test with a Postgres
   `set_config` round-trip asserting an RLS-scoped read sees only permitted rows and that rollback leaves
   no trace.
4. **DB configuration + degrade-gracefully.** pom `<configuration>` and env resolution (env wins), explicit
   dialect, tool absent/disabled with a clear message when unconfigured. `DevMojoTest` extension.
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
> **Row-level security still applies.** If your database uses RLS/RAS, configure the session state to set
> per-request (the current user, tenant, and so on come from the query's context arguments), and the tool
> sees only what that identity is permitted, just like your running service.
>
> **No database configured?** The `execute` tool simply does not appear; every other dev tool (schema,
> catalog, diagnostics, docs) works with no database at all.


# Runtime Extension Points

Generated code is intentionally simple — no auth checks, no transaction
management, no tenant-aware connection routing. These concerns belong at
runtime, injected through `GraphitronContext` without touching generated
code.

This document is the reference for the rewrite-emitted `GraphitronContext`
interface. For the minimal wiring that stands up a working endpoint, see
[Getting Started](getting-started.md); this file picks up where that leaves
off.

---

## GraphitronContext

`GraphitronContext` is the runtime extension point that Graphitron defines.
Every generated DataFetcher retrieves an implementation per request and calls
its methods. Generated helper utilities that issue SQL on behalf of those
fetchers (for example, `ConnectionHelper.totalCount(env)` for `@asConnection`
connections) follow the same pattern: they emit a private
`graphitronContext(env)` shim and look up the `DSLContext` per invocation
rather than capturing one. You control what happens at runtime by providing
your own implementation; transaction boundaries, connection scope, session
variables, and listeners are all decided by the `DSLContext` you return, not
by anything Graphitron emits.

### Where the interface comes from

The interface is **emitted per app**, not imported from a shared module. Every
code-generation run produces one `GraphitronContext.java` file under
`<outputPackage>.rewrite.schema`, written by
`GraphitronContextInterfaceGenerator`. The generated interface depends only on
graphql-java's `DataFetchingEnvironment` and jOOQ's `DSLContext`; it does not
pull in any Graphitron runtime jar.

```java
// GENERATED — from GraphitronContextInterfaceGenerator
public interface GraphitronContext {
    DSLContext getDslContext(DataFetchingEnvironment env);
    <T> T getContextArgument(DataFetchingEnvironment env, String name);
    default String getTenantId(DataFetchingEnvironment env) { return ""; }
}
```

### Registration

Apps register an implementation per request via the typed key
`GraphitronContext.class`:

```java
ExecutionInput input = ExecutionInput.newExecutionInput()
    .query(query)
    .graphQLContext(b -> b.put(GraphitronContext.class, ctx))
    .build();
```

Generated fetchers retrieve it through a private helper emitted once per
`*Fetchers` class:

```java
// GENERATED — from TypeFetcherGenerator.buildGraphitronContextHelper
private static GraphitronContext graphitronContext(DataFetchingEnvironment env) {
    return env.getGraphQlContext().get(GraphitronContext.class);
}
```

The full minimum viable wiring (instantiating an implementation, building
the schema, wiring `GraphQL`) lives in
[Getting Started → Hello world](getting-started.md#hello-world). The rest of
this section documents what each method is for.

### `getDslContext` — database access

Every generated query method calls `getDslContext(env)` to obtain the
`DSLContext` for executing SQL. Your implementation controls what
`DSLContext` is returned — connection pooling, session variables, transaction
boundaries, and jOOQ listeners are all configured here. Graphitron makes no
assumptions about how the `DSLContext` is configured; it receives one, uses
it, and does not inspect it.

A tenant-scoped example reading the tenant id off the request-scoped context
and returning a per-tenant `DSLContext` (with `SET LOCAL` for transaction-
scoped session variables) is in
[Getting Started → Tenant-scoped DSLContext](getting-started.md#tenant-scoped-dslcontext).
The same shape composes with PostgreSQL row-level security; see
[Row-Level Security](#row-level-security) below.

### `getContextArgument` — passing runtime values into generated methods

`getContextArgument` passes values from the GraphQL context into generated
condition and method calls. It is invoked when a method parameter is
classified as `ParamSource.Context` — driven by the `contextArguments` field
on the `@condition` and `@tableMethod` directives.

For example, a field with
`@condition(condition: "AccessControl.visibleToUser",
contextArguments: ["userId"])` produces:

```java
// GENERATED
condition = condition.and(AccessControl.visibleToUser(table,
    graphitronContext(env).getContextArgument(env, "userId")));
```

A typical implementation pulls the value off `env.getGraphQlContext()` keyed
by the contextArgument name, or off a JWT-claims map stashed there at request
entry. See
[Getting Started → Context arguments from a JWT claim](getting-started.md#context-arguments-from-a-jwt-claim)
for a worked example.

### `getTenantId` — DataLoader cache isolation

`getTenantId` returns the tenant identifier for the current request, or an
empty string when tenant scoping does not apply (the default returns `""`).
Graphitron concatenates this value with the GraphQL field path to build the
DataLoader registry key for batched fetches:

```java
// GENERATED — see TypeFetcherGenerator.buildDataLoaderName
String name = graphitronContext(env).getTenantId(env)
    + "/" + String.join("/", env.getExecutionStepInfo().getPath().getKeysOnly());
```

The path component is Graphitron-controlled and cannot be overridden, so two
DataLoader caches cannot accidentally collide; only the tenant prefix is
pluggable. Single-tenant apps leave this method on its default; multi-tenant
apps return a stable id per tenant so two tenants issuing the same query do
not share a DataLoader cache:

```java
@Override
public String getTenantId(DataFetchingEnvironment env) {
    return env.getGraphQlContext().get("tenantId");
}
```

Note: the consumer-side tenant id used by `getDslContext` to choose a
`DSLContext` and the value returned here are typically the same id read from
the same place; they are separate methods because their consumers are
separate (your code vs. Graphitron's DataLoader registry).

---

## Complementary Technologies

The sections below describe standard capabilities that compose naturally with
`GraphitronContext`. They are not Graphitron-specific extension points — they
work because `getDslContext` gives you full control over the `DSLContext` and
its configuration.

**Where each concern belongs.** Three layers, in order of preference:

- **jOOQ `Configuration`** for cross-cutting jOOQ behaviour: type converters,
  forced types, `RecordMapperProvider`, naming strategies. Configured once
  per app and shared by every `DSLContext` you return from `getDslContext`.
- **`getDslContext` (i.e., `GraphitronContext`)** for per-request decisions
  that need the `DataFetchingEnvironment`: which tenant's `DSLContext` to
  return, which session variables to `SET LOCAL`, which connection to
  acquire. Anything that varies request-by-request lives here.
- **Schema directives** (`@condition`, `@tableMethod`, `@reference`) for
  predicates and joins that are part of the schema's business semantics.
  Anything a schema author should be able to read in the SDL belongs here,
  not in a runtime hook.

`ExecuteListener` is an advanced jOOQ-level hook (logging, metrics, query
rewriting); use it sparingly and prefer `Configuration` or a directive when
either fits.

### jOOQ Configuration

For most applications, jOOQ's `Configuration` is the most important
extension point. `DefaultConfiguration` controls type converters, forced
types, synthetic primary keys, embedded records, naming strategies, and
more. These settings flow through every query Graphitron generates.

```java
var config = new DefaultConfiguration();
config.set(SQLDialect.POSTGRES);
config.set(dataSource);
// Type converters, forced types, RecordMapperProvider, etc. go here
config.set(new DefaultRecordMapperProvider());
DSLContext ctx = DSL.using(config);
```

See the [jOOQ Configuration documentation](https://www.jooq.org/doc/latest/manual/sql-building/dsl-context/custom-settings/)
for the full set of options.

### jOOQ ExecuteListener

`ExecuteListener` is an advanced hook that intercepts query execution at
lifecycle points (before rendering, before execution, after execution, etc.).
Most applications do not need this — it is mainly useful for SQL logging,
metrics collection, or query rewriting. Register a listener on
`DefaultConfiguration` before creating the `DSLContext`; see the
[jOOQ ExecuteListener documentation](https://www.jooq.org/doc/latest/manual/sql-execution/execute-listeners/)
for the full lifecycle and available hooks.

### Row-Level Security

Graphitron's security model designates the database as the enforcement
point. Row-level security (RLS) is the recommended mechanism — policies
filter rows transparently based on session context, with no changes to
generated queries.

Most relational databases support a form of row-level security. The example
below uses PostgreSQL.

The connection point is `getDslContext`: set the session context before
returning the `DSLContext`, and RLS policies read those values for every
query.

```sql
-- ILLUSTRATIVE — PostgreSQL RLS policy
ALTER TABLE documents ENABLE ROW LEVEL SECURITY;

CREATE POLICY tenant_isolation ON documents
    USING (tenant_id = current_setting('app.tenant_id', true)::uuid);
```

```java
// ILLUSTRATIVE — setting session context in getDslContext
@Override
public DSLContext getDslContext(DataFetchingEnvironment env) {
    String tenantId = env.getGraphQlContext().get("tenantId");

    // Hold a single connection for the request so the SET and all queries share it.
    // Use set_config with isLocal=true (transaction-scoped) for connection pool safety —
    // this resets automatically when the transaction ends rather than persisting on the
    // connection after it returns to the pool.
    Connection conn = dataSource.getConnection();
    DSLContext ctx = DSL.using(conn, SQLDialect.POSTGRES);
    ctx.execute("SELECT set_config('app.tenant_id', ?, true)", tenantId);
    return ctx;
}
```

Generated code is unaware of RLS — it issues plain `SELECT` statements, and
the database enforces the policies automatically.

---

**See also:**

- [Getting Started](getting-started.md) — Hello world, custom scalar,
  federation, tenant-scoped `DSLContext`, JWT-claim context arguments
- [Code Generation Triggers](code-generation-triggers.md) — Schema patterns
  to sealed variants, including the `@condition` / `@tableMethod` directives
  that drive `getContextArgument` calls
- [Security Model](../../docs/security.md) — Database-level security
  philosophy

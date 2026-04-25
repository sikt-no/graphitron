# Runtime Extension Points

Generated code is intentionally simple — no tenant filtering, no auth checks, no transaction
management. These concerns belong at runtime, injected through `GraphitronContext` without
touching generated code.

---

## GraphitronContext

`GraphitronContext` is the runtime extension point that Graphitron defines. Every generated
DataFetcher retrieves it and calls its methods. Generated helper utilities that issue SQL on
behalf of those fetchers (for example, `ConnectionHelper.totalCount(env)` for `@asConnection`
connections) follow the same pattern: they emit a private `graphitronContext(env)` shim and look
up `DSLContext` per invocation rather than capturing one. You control what happens at runtime by
providing your own implementation; transaction boundaries, connection scope, session variables,
and listeners are all decided by the `DSLContext` you return, not by anything Graphitron emits.

**Location:** `graphitron-common/src/main/java/no/sikt/graphql/GraphitronContext.java`

```java
public interface GraphitronContext {
    DSLContext getDslContext(DataFetchingEnvironment env);
    <T> T getContextArgument(DataFetchingEnvironment env, String name);
    String getDataLoaderName(DataFetchingEnvironment env);
}
```

The default implementation is `DefaultGraphitronContext`. The example server registers it in
`GraphqlServlet`:

```java
// From graphitron-example-server GraphqlServlet.java — real code
var config = new DefaultConfiguration();
config.set(SQLDialect.POSTGRES);
config.set(dataSource);
QueryCapturingExecuteListener.getInstanceIfEnabled().ifPresent(config::set);
DSLContext ctx = DSL.using(config);
input.graphQLContext(Map.of("graphitronContext", new DefaultGraphitronContext(ctx)));
```

Generated fetchers retrieve the context through a private helper emitted per Fetchers class:

```java
// GENERATED — from TypeFetcherGenerator.buildGraphitronContextHelper()
private static GraphitronContext graphitronContext(DataFetchingEnvironment env) {
    return env.getGraphQlContext().get("graphitronContext");
}
```

### getDslContext — database access

Every generated query method calls `getDslContext(env)` to obtain the `DSLContext` for executing
SQL. Your implementation controls what `DSLContext` is returned — this is where you configure
connection pooling, session variables, transaction boundaries, and jOOQ listeners.

The generated code makes no assumptions about how the `DSLContext` is configured. It receives it,
uses it, and doesn't inspect it.

*Illustrative example (not in the codebase):*

```java
// ILLUSTRATIVE — multi-tenancy via session variables
public class TenantGraphitronContext implements GraphitronContext {
    private final DataSource dataSource;

    @Override
    public DSLContext getDslContext(DataFetchingEnvironment env) {
        String tenantId = env.getGraphQlContext().get("tenantId");

        // Acquire a connection explicitly so jOOQ holds it across statements.
        // Using DSL.using(dataSource) would return the connection to the pool
        // between statements, losing any SET variables.
        Connection conn = dataSource.getConnection();
        DSLContext ctx = DSL.using(conn, SQLDialect.POSTGRES);

        // SET LOCAL is transaction-scoped — safer than SET with connection pools
        ctx.execute("SET LOCAL app.tenant_id = ?", tenantId);

        return ctx;
    }
}
```

### getContextArgument — passing runtime values into generated conditions

`getContextArgument` passes values from the GraphQL context into generated condition and method
calls. The default implementation delegates to `env.getGraphQlContext().get(name)`.

This is used by the `contextArguments` parameter on `@tableMethod` and `@condition`. When a
method parameter is classified as `ParamSource.Context`, the generator emits:

```java
// GENERATED — from TypeFetcherGenerator.buildArgExtraction(), ContextArg branch
graphitronContext(env).getContextArgument(env, "paramName")
```

For example, a field with `@condition(condition: "AccessControl.visibleToUser",
contextArguments: ["userId"])` produces:

```java
// GENERATED
condition = condition.and(AccessControl.visibleToUser(table,
    graphitronContext(env).getContextArgument(env, "userId")));
```

### getDataLoaderName — DataLoader registry key

`getDataLoaderName` returns the key used to look up or create the `DataLoader` for a batch field.
The default implementation derives the name from the field and its parent type. For most
applications the default is correct and this method does not need to be overridden.

---

## Complementary Technologies

The sections below describe standard capabilities that compose naturally with `GraphitronContext`.
They are not Graphitron-specific extension points — they work because `getDslContext()` gives you
full control over the `DSLContext` and its configuration.

### jOOQ Configuration

For most applications, jOOQ's `Configuration` is the most important extension point.
`DefaultConfiguration` controls type converters, forced types, synthetic primary keys, embedded
records, naming strategies, and more. These settings flow through every query Graphitron generates.

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

`ExecuteListener` is an advanced hook that intercepts query execution at lifecycle points (before
rendering, before execution, after execution, etc.). Most applications do not need this — it is
mainly useful for SQL logging, metrics collection, or query rewriting.

The example server uses it for test infrastructure — `QueryCapturingExecuteListener` captures
executed SQL for integration tests:

```java
// From QueryCapturingExecuteListener.java — real code
@Override
public void executeStart(ExecuteContext ctx) {
    queries.add(ctx.sql());
}
```

Register a listener on the `DefaultConfiguration` before creating the `DSLContext`:

```java
// From GraphqlServlet.java — real code
var config = new DefaultConfiguration();
config.set(SQLDialect.POSTGRES);
config.set(dataSource);
QueryCapturingExecuteListener.getInstanceIfEnabled().ifPresent(config::set);
DSLContext ctx = DSL.using(config);
```

See the [jOOQ ExecuteListener documentation](https://www.jooq.org/doc/latest/manual/sql-execution/execute-listeners/)
for the full lifecycle and available hooks.

### Row-Level Security

Graphitron's security model (see [Security](../../docs/security.md)) designates the database as the
enforcement point. Row-level security (RLS) is the recommended mechanism — policies filter rows
transparently based on session context, with no changes to generated queries.

Most relational databases support a form of row-level security. The examples below use PostgreSQL.

The connection point is `getDslContext()`: set the session context before returning the
`DSLContext`, and RLS policies read those values for every query.

*Illustrative example (not in the codebase):*

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

Generated code is unaware of RLS — it issues plain `SELECT` statements, and the database enforces
the policies automatically.

---

**See also:**
- [Security Model](../../docs/security.md) — Database-level security philosophy
- [Common Module README](../../graphitron-common/README.md) — GraphitronContext API reference
- [Example Server](../../graphitron-example/graphitron-example-server) — Working implementation

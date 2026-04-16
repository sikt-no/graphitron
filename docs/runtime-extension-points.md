# Runtime Extension Points

Generated code is intentionally simple — no tenant filtering, no auth checks, no transaction management. These concerns belong at runtime, injected through `GraphitronContext` without touching generated code.

---

## GraphitronContext

`GraphitronContext` is the sole runtime extension point that Graphitron defines. Every generated DataFetcher retrieves it and calls its methods. You control what happens at runtime by providing your own implementation.

**Location:** `graphitron-common/src/main/java/no/sikt/graphql/GraphitronContext.java`

```java
public interface GraphitronContext {
    DSLContext getDslContext(DataFetchingEnvironment env);
    <T> T getContextArgument(DataFetchingEnvironment env, String name);
    String getDataLoaderName(DataFetchingEnvironment env);
}
```

The default implementation is `DefaultGraphitronContext`. The example server uses it in `GraphqlServlet`:

```java
// From graphitron-example-server GraphqlServlet.java — real code
var config = new DefaultConfiguration();
config.set(SQLDialect.POSTGRES);
config.set(dataSource);
QueryCapturingExecuteListener.getInstanceIfEnabled().ifPresent(config::set);
DSLContext ctx = DSL.using(config);
input.graphQLContext(Map.of("graphitronContext", new DefaultGraphitronContext(ctx)));
```

Generated code retrieves the context by key. The rewrite generator emits a private helper per Fetchers class:

```java
// GENERATED — from TypeFetcherGenerator.buildGraphitronContextHelper()
private static GraphitronContext graphitronContext(DataFetchingEnvironment env) {
    return env.getGraphQlContext().get("graphitronContext");
}
```

FIXME: These should be using a common utility instead.

Query methods (in generated `TypeClass` files) inline the retrieval and immediately call `getDslContext`:

```java
// GENERATED — from TypeClassGenerator.selectMany()
DSLContext dsl = ((GraphitronContext) env.getGraphQlContext().get("graphitronContext")).getDslContext(env);
```

### getDslContext — database access

Every generated query method calls `getDslContext(env)` to obtain the `DSLContext` for executing SQL. Your implementation controls what `DSLContext` is returned — this is where you configure connection pooling, session variables, transaction boundaries, and jOOQ listeners.

The generated code makes no assumptions about how the `DSLContext` is configured. It receives it, uses it, and doesn't inspect it. This means you can freely add jOOQ `ExecuteListener`s, set PostgreSQL session variables for RLS, wrap it in a transaction, or do anything else jOOQ supports.

*Illustrative example (no implementation of this exists in the codebase):*

```java
// ILLUSTRATIVE — shows how you could use getDslContext for multi-tenancy
public class TenantGraphitronContext implements GraphitronContext {
    private final DataSource dataSource;

    @Override
    public DSLContext getDslContext(DataFetchingEnvironment env) {
        String tenantId = env.getGraphQlContext().get("tenantId");

        DSLContext ctx = DSL.using(dataSource, SQLDialect.POSTGRES);

        // Set session variables for PostgreSQL RLS policies
        ctx.execute("SET app.tenant_id = ?", tenantId);

        return ctx;
    }

    // ... getContextArgument and getDataLoaderName omitted for brevity
}
```

FIXME: The above won’t work unless the DSLContext has been created directly on a JDBC Connection. If it’s wrapping a DataSource then jOOQ will return the connection to the pool immediately after executing the statement.
### getContextArgument — passing runtime values to generated code

`getContextArgument` passes values from the GraphQL context into generated condition and service method calls. The default implementation delegates to `env.getGraphQlContext().get(name)`.

This is used by the `contextArguments` directive parameter on `@service` and `@tableMethod`. When a method parameter is classified as `ParamSource.Context`, the generator emits:

```java
// GENERATED — from TypeFetcherGenerator.buildArgExtraction(), ContextArg branch
graphitronContext(env).getContextArgument(env, "paramName")
```

FIXME: @tableMethod is currently called @externalField in the directives. The same holds for @condition so include it. It’s almost ready so let’s just document as if it’s live. 

This is inlined directly as an argument to the method call — not a separate variable declaration. For example, a `@service` method with `contextArguments: ["tenantId"]` produces something like:

```java
// GENERATED
condition = condition.and(MyService.filterByTenant(table, graphitronContext(env).getContextArgument(env, "tenantId")));
```

FIXME: This is wrong. A service is responsible for the entire query and will never be inlined in a condition like this.

### getDataLoaderName — DataLoader isolation

Generated DataLoader fetchers call `getDataLoaderName(env)` to determine the registry key for `DataLoaderRegistry.computeIfAbsent`. This controls which requests share a DataLoader instance.

The default implementation in `DefaultGraphitronContext`:

```java
// From DefaultGraphitronContext.java — real code
@Override
public String getDataLoaderName(DataFetchingEnvironment env) {
    return String.format("%sFor%s",
        capitalize(env.getField().getName()),
        env.getExecutionStepInfo().getObjectType().getName());
    // Example: field "films" on type "Actor" → "FilmsForActor"
}
```

Note: `capitalize` (from `graphql.util.StringKit`) uppercases the first letter, so the output is `"FilmsForActor"`, not `"filmsForActor"`.

FIXME: The detail level her is wrong. Users shouldn’t know about or be able to decide the name of the dataloaders we create.

A fresh `DataLoaderRegistry` is created per HTTP request (in `GraphitronServlet`), so DataLoaders are never shared across requests. The naming only affects sharing *within* a single request — two fields in the same query that resolve to the same name will batch together.

FIXME: See previous FIXME. Is this relevant?

**Design note:** The `GraphitronContext` Javadoc recommends encoding the full execution path (stripped of list indices) rather than just `fieldName + "For" + typeName`. The default implementation uses the simpler formula, which works when the same field+type always has the same arguments. For cases where different parts of a query reach the same type via different paths with different arguments, a path-based implementation prevents unintended batching. The rewrite test suite uses the path-based approach:

```java
// From GraphQLQueryTest.java — real test code
@Override
public String getDataLoaderName(DataFetchingEnvironment env) {
    return env.getExecutionStepInfo().getPath().toString().replaceAll("/\\d+", "");
}
```

FIXME: See previous FIXME. Is this relevant?
---

## Complementary Technologies

The sections below describe standard jOOQ and PostgreSQL capabilities that compose naturally with `GraphitronContext`. They are not Graphitron-specific extension points — they work because `getDslContext()` gives you full control over the `DSLContext` and its configuration.

FIXME: Oracle and other databases also have similar support, so let’s be generic here. Use Postgres as an example but not as the standard. 

### FIXME: jOOQ Configuration

Most users will be using the jOOQ configuration to do datatype conversion, synthetic keys, embedded records etc.

This is one of the MAIN extension points.

Make sure we link to upstream since there is a lot of things here.

### jOOQ ExecuteListener

FIXME: This is for very advanced use cases. A lot can be done here but most people won’t need it.

jOOQ's `ExecuteListener` intercepts query execution at lifecycle points (before rendering, before execution, after execution, etc.). You can log SQL, collect metrics, or modify queries.

The example server already uses this pattern — `QueryCapturingExecuteListener` captures executed SQL for integration testing:

```java
// From QueryCapturingExecuteListener.java — real code
@Override
public void executeStart(ExecuteContext ctx) {
    queries.add(ctx.sql());
}
```

It's registered on the `DefaultConfiguration` before creating the `DSLContext`:

```java
// From GraphqlServlet.java — real code
var config = new DefaultConfiguration();
config.set(SQLDialect.POSTGRES);
config.set(dataSource);
QueryCapturingExecuteListener.getInstanceIfEnabled().ifPresent(config::set);
DSLContext ctx = DSL.using(config);
```

You would follow the same pattern to add logging, metrics, or audit listeners — implement `ExecuteListener`, register it on the configuration in your `getDslContext()` implementation, and every generated query flows through it.

*Illustrative example (not in the codebase):*

```java
// ILLUSTRATIVE — SQL logging listener
public class SqlLoggingExecuteListener implements ExecuteListener {
    @Override
    public void executeStart(ExecuteContext ctx) {
        logger.debug("SQL: {}", ctx.sql());
    }
}
```

FIXME: We should link to upstream documentation.


### PostgreSQL Row-Level Security

FIXME: Database security model, make it generic with examples from Postgres. Make sure our examples are in line with best practice performance guides from supabase. 

Graphitron's security model (see [Security](security.md)) designates the database as the enforcement point. PostgreSQL RLS is the recommended mechanism — policies filter rows transparently based on session variables.

The connection point is `getDslContext()`: your implementation executes `SET` statements before returning the `DSLContext`, and RLS policies read those session variables.

*Illustrative example (not in the codebase):*

```sql
-- ILLUSTRATIVE — PostgreSQL RLS setup
ALTER TABLE students ENABLE ROW LEVEL SECURITY;

CREATE POLICY tenant_isolation ON students
    USING (tenant_id = current_setting('app.tenant_id')::uuid);
```

```java
// ILLUSTRATIVE — setting session variables in getDslContext
@Override
public DSLContext getDslContext(DataFetchingEnvironment env) {
    String tenantId = env.getGraphQlContext().get("tenantId");
    DSLContext ctx = DSL.using(dataSource, SQLDialect.POSTGRES);
    ctx.execute("SET app.tenant_id = ?", tenantId);
    return ctx;
}
```

Generated code is unaware of RLS — it issues plain `SELECT` statements, and the database enforces the policies automatically.

---

## Choosing Your Approach

| Goal | Mechanism | How |
|------|-----------|-----|
| Multi-tenancy | `getDslContext()` + PostgreSQL RLS | Set session variables, create RLS policies |
| Context values in queries | `getContextArgument()` + `@service`/`@tableMethod` `contextArguments` | Pass runtime values (user ID, tenant ID) to generated method calls |
| SQL logging / metrics | jOOQ `ExecuteListener` | Register on `DefaultConfiguration` in `getDslContext()` |
| DataLoader isolation | `getDataLoaderName()` | Override naming to scope per path or per tenant |
| Transaction management | `getDslContext()` | Return a transaction-aware `DSLContext` for mutations |

**Key principle:** Generated code calls `GraphitronContext` and uses whatever it returns. Runtime concerns live in your implementation, not in generated code.

---

**See also:**
- [Security Model](security.md) — Database-level security philosophy
- [Common Module README](../graphitron-common/README.md) — GraphitronContext API reference
- [Example Server](../graphitron-example/graphitron-example-server) — Working implementation

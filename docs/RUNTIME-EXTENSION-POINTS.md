# Runtime Extension Points

This document explains how to extend Graphitron's behavior at runtime without modifying generated code. These extension points enable multi-tenancy, custom security, transaction management, and other cross-cutting concerns.

---

## Philosophy: Extension Without Modification

Graphitron generates code based on your schema. That generated code is intentionally simple and doesn't include runtime concerns like:
- Multi-tenancy (which tenant is this request for?)
- Security context (which user is making this request?)
- Transaction management (should this be in a transaction?)
- Custom logging or metrics

Instead, Graphitron provides **extension points** where you can inject your own logic at runtime without touching generated code.

---

## The Three Extension Mechanisms

### 1. GraphitronContext - Per-Request Configuration
**Purpose:** Customize how each GraphQL request obtains and configures its database context.

**Use cases:**
- Multi-tenancy with database-level isolation
- Per-request transaction management
- Custom connection pooling strategies
- Passing security context to queries

### 2. jOOQ ExecuteListener - Query Interception
**Purpose:** Intercept and modify queries before execution.

**Use cases:**
- Add tenant filters to all queries automatically
- Log SQL for debugging or auditing
- Modify queries based on user permissions
- Collect metrics on query execution

### 3. Database Row-Level Security (RLS)
**Purpose:** Enforce security at the database level, not application level.

**Use cases:**
- Tenant isolation (users can't see other tenants' data)
- User permissions (users can only see their own records)
- Data classification (hide sensitive data from certain users)

---

## Extension Point 1: GraphitronContext

### What It Is

`GraphitronContext` is an interface that bridges GraphQL execution and database access. It's called by every generated DataFetcher to obtain the `DSLContext` for executing queries.

**Location:** `graphitron-common/src/main/java/no/sikt/graphql/GraphitronContext.java`

```java
public interface GraphitronContext {
    DSLContext getDslContext(DataFetchingEnvironment env);
    <T> T getContextArgument(DataFetchingEnvironment env, String name);
    String getDataLoaderName(DataFetchingEnvironment env);
}
```

### How It Works

**1. Application provides GraphitronContext at startup:**
```java
@Override
protected ExecutionInput buildExecutionInput(ExecutionInput.Builder builder) {
    GraphitronContext context = new MyCustomGraphitronContext(dataSource);
    return builder.graphQLContext(Map.of("graphitronContext", context)).build();
}
```

**2. Generated code retrieves it per-request:**
```java
// GENERATED in every DataFetcher
GraphitronContext graphitronContext = env.getGraphQlContext().get("graphitronContext");
DSLContext ctx = graphitronContext.getDslContext(env);

// Use ctx to execute queries
return ctx.select(...).fetch();
```

**3. Your implementation controls what DSLContext is returned:**
```java
public class TenantGraphitronContext implements GraphitronContext {
    @Override
    public DSLContext getDslContext(DataFetchingEnvironment env) {
        String tenantId = env.getGraphQlContext().get("tenantId");

        // Create DSLContext with tenant-specific configuration
        DSLContext ctx = DSL.using(dataSource, SQLDialect.POSTGRES);

        // Execute SET SESSION for PostgreSQL RLS
        ctx.execute("SET app.tenant_id = ?", tenantId);
        ctx.execute("SET app.user_id = ?", getCurrentUserId());

        return ctx;
    }
}
```

### Use Case: Multi-Tenancy with PostgreSQL RLS

**Application setup:**
```java
public class TenantGraphitronContext implements GraphitronContext {
    private final DataSource dataSource;

    @Override
    public DSLContext getDslContext(DataFetchingEnvironment env) {
        // Extract tenant from GraphQL context
        String tenantId = env.getGraphQlContext().get("tenantId");
        if (tenantId == null) {
            throw new UnauthorizedException("No tenant context");
        }

        // Create connection and set session variables
        DSLContext ctx = DSL.using(dataSource, SQLDialect.POSTGRES);
        ctx.execute("SET app.tenant_id = ?", tenantId);

        return ctx;
    }
}
```

**Database setup (PostgreSQL RLS):**
```sql
-- Enable RLS on tables
ALTER TABLE users ENABLE ROW LEVEL SECURITY;
ALTER TABLE orders ENABLE ROW LEVEL SECURITY;

-- Create policy using session variable
CREATE POLICY tenant_isolation ON users
    USING (tenant_id = current_setting('app.tenant_id')::uuid);

CREATE POLICY tenant_isolation ON orders
    USING (tenant_id = current_setting('app.tenant_id')::uuid);
```

**Result:** Every query automatically filtered by tenant. Generated code stays simple, database enforces security.

### Use Case: Transaction Management

```java
public class TransactionalGraphitronContext implements GraphitronContext {
    private final DataSource dataSource;

    @Override
    public DSLContext getDslContext(DataFetchingEnvironment env) {
        // Mutations run in transactions
        if (env.getFieldDefinition().getType().getName().endsWith("Payload")) {
            DSLContext ctx = DSL.using(dataSource, SQLDialect.POSTGRES);
            return ctx.configuration()
                      .derive()
                      .dsl(); // Transaction-aware context
        }

        // Queries don't need transactions
        return DSL.using(dataSource, SQLDialect.POSTGRES);
    }
}
```

### Context Arguments

GraphitronContext provides `getContextArgument()` for passing values from GraphQL context to service methods and conditions:

**Schema:**
```graphql
type Query {
  myData: [Data!]!
    @condition(
      condition: {className: "Filters", method: "forCurrentUser"},
      contextArguments: ["userId"]
    )
}
```

**Generated code passes context argument:**
```java
String userId = graphitronContext.getContextArgument(env, "userId");
Condition filter = Filters.forCurrentUser(userId);
```

**GraphitronContext implementation:**
```java
@Override
public <T> T getContextArgument(DataFetchingEnvironment env, String name) {
    return env.getGraphQlContext().get(name);
}
```

This allows passing security context (user ID, tenant ID) to queries without exposing them as GraphQL arguments.

### DataLoader Names and Multi-Tenancy

GraphitronContext provides `getDataLoaderName()` to control how DataLoaders are named. **This is critical for multi-tenancy security.**

**The Problem:** DataLoaders cache results to batch database queries. If different tenants share the same DataLoader instance, tenant A could see cached data from tenant B.

**The Solution:** Scope DataLoader names per-tenant.

**Default implementation (single-tenant):**
```java
@Override
public String getDataLoaderName(DataFetchingEnvironment env) {
    return String.format("%sFor%s",
        capitalize(env.getField().getName()),
        env.getExecutionStepInfo().getObjectType().getName());
    // Returns: "filmsForActor"
}
```

**Multi-tenant implementation:**
```java
@Override
public String getDataLoaderName(DataFetchingEnvironment env) {
    String tenantId = env.getGraphQlContext().get("tenantId");
    String baseName = String.format("%sFor%s",
        capitalize(env.getField().getName()),
        env.getExecutionStepInfo().getObjectType().getName());

    // Scope DataLoader per tenant
    return baseName + "-" + tenantId;
    // Returns: "filmsForActor-tenant123"
}
```

**Result:** Each tenant gets isolated DataLoader instances. No cache leakage between tenants.

**Important:** If you use multi-tenancy with GraphitronContext, you **must** also override `getDataLoaderName()` to prevent cache leakage.

---

## Extension Point 2: jOOQ ExecuteListener

### What It Is

jOOQ's `ExecuteListener` intercepts query execution at various lifecycle points. You can modify queries, log SQL, collect metrics, or add filters before execution.

**Use cases:**
- Automatically add tenant filters to queries
- Log all SQL for debugging
- Measure query performance
- Enforce security policies

### How It Works

**1. Implement ExecuteListener:**
```java
public class TenantFilterExecuteListener implements ExecuteListener {

    @Override
    public void renderStart(ExecuteContext ctx) {
        // Called before query is converted to SQL
        String tenantId = TenantContext.getCurrentTenant();

        if (ctx.query() instanceof Select) {
            Select<?> select = (Select<?>) ctx.query();

            // Add WHERE clause to all SELECT queries
            ctx.query(select.where(TENANT_ID.eq(tenantId)));
        }
    }

    @Override
    public void executeStart(ExecuteContext ctx) {
        // Called just before execution - log SQL
        logger.debug("Executing: {}", ctx.sql());
    }
}
```

**2. Register with DSLContext:**
```java
var config = new DefaultConfiguration();
config.set(dataSource);
config.set(new TenantFilterExecuteListener());  // Register listener

DSLContext ctx = DSL.using(config);
```

**3. All queries through this DSLContext get intercepted:**
```java
// This query will automatically have tenant filter added
ctx.select(USERS.fields()).from(USERS).fetch();

// Becomes:
// SELECT ... FROM users WHERE tenant_id = 'current-tenant-id'
```

### Use Case: Automatic Tenant Filtering

```java
public class TenantFilterExecuteListener implements ExecuteListener {

    @Override
    public void renderStart(ExecuteContext ctx) {
        String tenantId = getCurrentTenantId(); // From thread local or context

        if (ctx.query() instanceof Select) {
            Select<?> select = (Select<?>) ctx.query();

            // Check if query uses a table with tenant_id column
            if (queryHasTenantColumn(ctx)) {
                ctx.query(select.where(TENANT_ID.eq(tenantId)));
            }
        }

        if (ctx.query() instanceof Insert) {
            // Automatically add tenant_id to inserts
            Insert<?> insert = (Insert<?>) ctx.query();
            ctx.query(insert.set(TENANT_ID, tenantId));
        }
    }
}
```

### Use Case: SQL Logging

```java
public class SqlLoggingExecuteListener implements ExecuteListener {

    @Override
    public void executeStart(ExecuteContext ctx) {
        logger.info("SQL: {}", ctx.sql());
        logger.info("Bindings: {}", ctx.bindValues());
    }

    @Override
    public void executeEnd(ExecuteContext ctx) {
        long duration = ctx.executionTime();
        if (duration > 1000) {
            logger.warn("Slow query ({}ms): {}", duration, ctx.sql());
        }
    }
}
```

### Combining GraphitronContext and ExecuteListener

You can use both together:

```java
public class TenantGraphitronContext implements GraphitronContext {
    private final DataSource dataSource;

    @Override
    public DSLContext getDslContext(DataFetchingEnvironment env) {
        String tenantId = env.getGraphQlContext().get("tenantId");

        // Configure DSLContext with ExecuteListener
        var config = new DefaultConfiguration();
        config.set(dataSource);
        config.set(new TenantFilterExecuteListener(tenantId));  // Pass tenant to listener
        config.set(new SqlLoggingExecuteListener());

        return DSL.using(config);
    }
}
```

---

## Extension Point 3: Database Row-Level Security

### What It Is

PostgreSQL Row-Level Security (RLS) enforces access control at the database level. Even if application code is compromised, the database protects data.

**From SECURITY.md:**
> "The database is the authoritative security layer. That's why Graphitron's generated code is intentionally 'naive' about security—the database enforces it."

### How It Works with GraphitronContext

**1. GraphitronContext sets session variables:**
```java
@Override
public DSLContext getDslContext(DataFetchingEnvironment env) {
    String tenantId = env.getGraphQlContext().get("tenantId");
    String userId = env.getGraphQlContext().get("userId");

    DSLContext ctx = DSL.using(dataSource, SQLDialect.POSTGRES);

    // Set session variables for RLS policies to read
    ctx.execute("SET app.tenant_id = ?", tenantId);
    ctx.execute("SET app.user_id = ?", userId);

    return ctx;
}
```

**2. Database policies read these variables:**
```sql
-- Tenant isolation
CREATE POLICY tenant_isolation ON users
    USING (tenant_id = current_setting('app.tenant_id')::uuid);

-- User can only see own data
CREATE POLICY own_data_only ON orders
    USING (user_id = current_setting('app.user_id')::uuid);

-- Admin can see everything
CREATE POLICY admin_all_access ON orders
    USING (current_setting('app.user_role') = 'admin');
```

**3. All queries automatically filtered:**
```java
// Generated code just does:
ctx.select(USERS.fields()).from(USERS).fetch();

// Database enforces tenant filter automatically
// Returns only users where tenant_id matches session variable
```

### RLS Benefits

**Security:**
- Enforced at database level (can't be bypassed by app bugs)
- Applies to all queries (generated and custom)
- Database is authoritative layer

**Simplicity:**
- Generated code stays simple (no tenant filters)
- One place to define policies (database)
- Easy to audit (check database policies)

**Performance:**
- Database can optimize filtered queries
- Indexes work with RLS policies
- No application-level filtering overhead

---

## Choosing Your Extension Strategy

### For Multi-Tenancy

**Option A: GraphitronContext + RLS (Recommended)**
- Set session variables in `getDslContext()`
- Create RLS policies in database
- Pro: Database enforces, app stays simple
- Con: Requires PostgreSQL 9.5+ or equivalent

**Option B: ExecuteListener**
- Modify queries to add tenant filters
- Pro: Works with any database
- Con: More complex, application-level filtering

**Option C: Context Arguments + @condition**
- Pass tenant ID explicitly to every query
- Pro: Explicit, visible in schema
- Con: Tedious, easy to forget

### For Logging/Metrics

**ExecuteListener** - Perfect for observability without changing generated code

### For Transaction Management

**GraphitronContext** - Return transaction-aware DSLContext for mutations

### For Custom Security Logic

**Combination:**
1. GraphitronContext sets session variables
2. RLS policies enforce at database
3. ExecuteListener adds audit logging

---

## Summary

| Extension Point | Purpose | Use When |
|----------------|---------|----------|
| **GraphitronContext** | Per-request DSLContext customization | Multi-tenancy, transactions, custom config |
| **ExecuteListener** | Query interception and modification | Automatic filters, logging, metrics |
| **Database RLS** | Row-level security at database | Tenant isolation, user permissions |

**Key principle:** Keep generated code simple. Use extension points for runtime concerns.

---

**See also:**
- [Security Model](SECURITY.md) - Database-level security philosophy
- [Common Module README](../graphitron-common/README.md) - GraphitronContext API reference
- [Example Server](../graphitron-example/graphitron-example-server) - Working implementation

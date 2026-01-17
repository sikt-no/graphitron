# What Graphitron Generates

This document introduces the vocabulary and taxonomy of generated code. For when and why to use different patterns, see [Code Generation Patterns](CODE-GENERATION-PATTERNS.md).

## The Big Picture

```
RuntimeWiring (GraphQL-Java)
    ↓ wires GraphQL fields to
<Type>Queries Classes (Generated)
    ↓ contain methods that either
    ├─ Execute SQL Queries
    └─ Generate QueryParts (jOOQ)
           ↓ compose into SQL
```

Graphitron generates two main artifacts:

1. **RuntimeWiring configuration** - Wires GraphQL fields to Java methods (thin layer)
2. **`<Type>Queries` classes** - Contains the actual query logic (where the work happens)

Inside `Queries` classes, methods either **execute complete SQL queries** or **generate QueryParts** that compose into larger queries.

---

## RuntimeWiring

**What it is:** GraphQL-Java's mechanism for connecting GraphQL schema fields to Java code.

**What Graphitron generates:** Lambda expressions that delegate to `Queries` methods.

```java
RuntimeWiring.newRuntimeWiring()
    .type("Query", builder -> builder
        .dataFetcher("user", env -> userQueries.user(env))
        .dataFetcher("users", env -> queryQueries.users(env))
    )
    .type("User", builder -> builder
        .dataFetcher("orders", env -> userQueries.ordersLoader(env))
        .dataFetcher("address", env -> ((UsersRecord) env.getSource()).get("address"))
        .dataFetcher("posts", env -> ((UsersRecord) env.getSource()).get("posts"))
    )
    .build();
```

**Key insight:** DataFetchers are just wiring. The valuable code is in `Queries` classes.

---

## `<Type>Queries` Classes

**Naming:** One class per GraphQL type - `QueryQueries`, `MutationQueries`, `UserQueries`, `OrderQueries`, etc.

**Purpose:** Contains all generated query logic for a GraphQL type.

**Three method categories:**

### 1. Query Execution Methods
**Signature:** `TypeRecord methodName(DataFetchingEnvironment env)`
**Purpose:** Execute complete SQL queries
**When:** Root Query/Mutation fields
**Called:** Once per GraphQL request

```java
public class QueryQueries {
    public UsersRecord user(DataFetchingEnvironment env) {
        var ctx = getCtx(env);
        var id = env.getArgument("id");

        return ctx.select(buildColumns(env.getSelectionSet()))
            .from(USERS)
            .where(USERS.ID.eq(id))
            .fetchOne();
    }
}
```

### 2. Loader Methods
**Signature:** `Map<ID, Result<TypeRecord>> methodNameLoader(DataFetchingEnvironment env)`
**Purpose:** Execute batched SQL queries via DataLoader
**When:** Fields marked with `@splitQuery` or fields with GraphQL arguments
**Called:** Once per batch (across all parents)

```java
public class UserQueries {
    public Map<Integer, Result<OrdersRecord>> ordersLoader(DataFetchingEnvironment env) {
        var ctx = getCtx(env);
        var userIds = (List<Integer>) env.getSource();  // Batched IDs

        return ctx.select(buildOrderColumns(env.getSelectionSet()))
            .from(ORDERS)
            .where(ORDERS.USER_ID.in(userIds))
            .fetch()
            .intoGroups(ORDERS.USER_ID);  // Map: userId -> orders
    }
}
```

### 3. QueryPart Helpers
**Signature:** `Field<?> buildFieldNameMultiset(SelectedField field)` or `buildFieldNameRow(SelectedField field)`
**Purpose:** Generate jOOQ QueryParts that compose into parent queries
**When:** Inline nested relationships (default)
**Called:** During parent query construction

```java
public class UserQueries {
    // Many-to-one relationship
    private Field<?> buildAddressRow(SelectedField field) {
        return row(
            select(buildAddressColumns(field.getSelectionSet()))
            .from(ADDRESS)
            .where(ADDRESS.ID.eq(USERS.ADDRESS_ID))
        );
    }

    // One-to-many relationship
    private Field<?> buildOrdersMultiset(SelectedField field) {
        return multiset(
            select(buildOrderColumns(field.getSelectionSet()))
            .from(ORDERS)
            .where(ORDERS.USER_ID.eq(USERS.ID))
        );
    }
}
```

**Common pattern:** DSLContext extraction
```java
private DSLContext getCtx(DataFetchingEnvironment env) {
    GraphitronContext ctx = env.getGraphQlContext().get("graphitronContext");
    return ctx.getDslContext(env);
}
```

---

## QueryParts

**What they are:** jOOQ's composable SQL fragments. QueryParts don't execute SQL; they generate SQL strings that compose into larger queries.

**Three categories:**

### Field QueryParts
**SQL clause:** SELECT
**Purpose:** Define what columns/expressions to select

| Type | jOOQ API | Purpose | Example |
|------|----------|---------|---------|
| Column reference | `TABLE.COLUMN` | Direct column mapping | `USERS.NAME` |
| Calculated field | `DSL.concat(...)` | SQL expression | `concat(FIRST_NAME, ' ', LAST_NAME)` |
| Nested row | `row(select(...))` | Correlated subquery (many-to-one) | Address in User query |
| Nested multiset | `multiset(select(...))` | Correlated subquery (one-to-many) | Orders in User query |
| Aggregate | `count()`, `sum(...)` | Aggregate function | `COUNT(*)` |

### Condition QueryParts
**SQL clause:** WHERE
**Purpose:** Filter rows

| Type | Purpose | Example |
|------|---------|---------|
| User condition | Custom filter logic | `@condition` returns `post.STATUS.eq("ACTIVE")` |
| Argument filter | Filter by GraphQL argument | `USERS.STATUS.eq(status)` |
| Lookup | Primary/unique key lookup | `USERS.ID.in(ids)` |

### Table QueryParts
**SQL clause:** FROM
**Purpose:** Define source table

| Type | Purpose | Example |
|------|---------|---------|
| Base table | Default table reference | `USERS` |
| Aliased table | Avoid naming collisions | `USERS.as("managers")` |
| Transformed table | View/lateral join | `@tableMethod` returns transformed table |

---

## Key Concepts

### Selection-Set-Driven Projection

**What:** Only SELECT the columns that the GraphQL query actually requested.

**How:** Generated code inspects `SelectionSet` and conditionally adds Fields:

```java
private List<Field<?>> buildColumns(SelectionSet selection) {
    var columns = new ArrayList<Field<?>>();

    if (selection.contains("id")) columns.add(USERS.ID);
    if (selection.contains("name")) columns.add(USERS.NAME);
    if (selection.contains("email")) columns.add(USERS.EMAIL);
    if (selection.contains("address")) {
        columns.add(buildAddressRow(selection.getField("address")));
    }
    if (selection.contains("orders")) {
        columns.add(buildOrdersMultiset(selection.getField("orders")));
    }

    return columns;
}
```

**GraphQL query:**
```graphql
query {
  user(id: 123) {
    name
    email
  }
}
```

**Generated SQL:**
```sql
SELECT users.name, users.email
FROM users
WHERE users.id = 123
```

No over-fetching. No N+1 queries. Just the data requested.

### Correlated Subqueries (row and multiset)

**What:** Nested SELECT statements that reference the parent query's columns.

**Why:** Fetch related data in a single query without JOINs flattening the structure.

#### row() - Many-to-One

Returns a single record (or null):

```java
Field<?> addressField = row(
    select(ADDRESS.ID, ADDRESS.STREET, ADDRESS.CITY)
    .from(ADDRESS)
    .where(ADDRESS.ID.eq(USERS.ADDRESS_ID))  // ← References parent table
);
```

**Generated SQL:**
```sql
SELECT
    users.id,
    users.name,
    (SELECT row(address.id, address.street, address.city)
     FROM address
     WHERE address.id = users.address_id) as address
FROM users
```

#### multiset() - One-to-Many

Returns an array of records:

```java
Field<?> ordersField = multiset(
    select(ORDERS.ID, ORDERS.STATUS, ORDERS.TOTAL)
    .from(ORDERS)
    .where(ORDERS.USER_ID.eq(USERS.ID))  // ← References parent table
);
```

**Generated SQL:**
```sql
SELECT
    users.id,
    users.name,
    (SELECT json_agg(row(orders.id, orders.status, orders.total))
     FROM orders
     WHERE orders.user_id = users.id) as orders
FROM users
```

**Key advantage:** Single query fetches parent and all related children. No N+1 problem.

### Composition vs Execution

**Composition** (QueryParts):
- Build SQL fragments
- Don't execute anything
- Compose into larger queries
- Lightweight, just object construction

**Execution** (Query methods):
- Call `.fetch()`, `.fetchOne()`, etc.
- Execute SQL against database
- Return jOOQ Records
- I/O operation

```java
// Composition - just building SQL fragments
Field<?> addressPart = buildAddressRow(field);
Field<?> ordersPart = buildOrdersMultiset(field);
List<Field<?>> columns = List.of(USERS.ID, USERS.NAME, addressPart, ordersPart);

// Execution - actually runs SQL
Result<UsersRecord> users = ctx.select(columns)
    .from(USERS)
    .fetch();  // ← This executes SQL
```

---

## Example: Complete Flow

**GraphQL query:**
```graphql
query {
  user(id: 123) {
    name
    address {
      city
    }
    orders {
      total
    }
  }
}
```

**Generated code flow:**

1. **RuntimeWiring** receives request, calls `QueryQueries.user(env)`

2. **user() method** inspects selection set:
   ```java
   public UsersRecord user(DataFetchingEnvironment env) {
       var selection = env.getSelectionSet();

       // Build column list based on what was selected
       var columns = new ArrayList<Field<?>>();
       columns.add(USERS.NAME);  // name was selected
       columns.add(buildAddressRow(selection.getField("address")));
       columns.add(buildOrdersMultiset(selection.getField("orders")));

       return ctx.select(columns).from(USERS).where(USERS.ID.eq(123)).fetchOne();
   }
   ```

3. **buildAddressRow()** generates correlated subquery:
   ```java
   private Field<?> buildAddressRow(SelectedField field) {
       var subSelection = field.getSelectionSet();
       return row(
           select(ADDRESS.CITY)  // Only city was selected
           .from(ADDRESS)
           .where(ADDRESS.ID.eq(USERS.ADDRESS_ID))
       );
   }
   ```

4. **buildOrdersMultiset()** generates correlated subquery:
   ```java
   private Field<?> buildOrdersMultiset(SelectedField field) {
       var subSelection = field.getSelectionSet();
       return multiset(
           select(ORDERS.TOTAL)  // Only total was selected
           .from(ORDERS)
           .where(ORDERS.USER_ID.eq(USERS.ID))
       );
   }
   ```

5. **Final SQL executed:**
   ```sql
   SELECT
       users.name,
       (SELECT row(address.city)
        FROM address
        WHERE address.id = users.address_id) as address,
       (SELECT json_agg(row(orders.total))
        FROM orders
        WHERE orders.user_id = users.id) as orders
   FROM users
   WHERE users.id = 123
   ```

6. **Result:** Single query, no over-fetching, no N+1 problem.

---

## Vocabulary Summary

| Term | What It Is | Purpose |
|------|------------|---------|
| **RuntimeWiring** | GraphQL-Java configuration | Wires fields to Java methods |
| **`<Type>Queries`** | Generated Java class | Contains query logic for a GraphQL type |
| **Query execution method** | `fieldName(env)` method | Executes complete SQL query |
| **Loader method** | `fieldNameLoader(env)` method | Executes batched SQL query via DataLoader |
| **QueryPart helper** | `buildFieldName*()` method | Generates SQL fragment for composition |
| **QueryPart** | jOOQ interface | Composable SQL fragment |
| **Field QueryPart** | `Field<?>` | SELECT clause component |
| **Condition QueryPart** | `Condition` | WHERE clause component |
| **Table QueryPart** | `Table<R>` | FROM clause component |
| **row()** | jOOQ function | Correlated subquery (many-to-one) |
| **multiset()** | jOOQ function | Correlated subquery (one-to-many) |
| **SelectionSet** | GraphQL-Java API | What fields were requested |
| **Correlated subquery** | SQL pattern | Nested SELECT referencing parent |
| **Selection-set-driven** | Pattern | Only fetch what was requested |

---

**See also:**
- [Code Generation Patterns](CODE-GENERATION-PATTERNS.md) - When and why to use different patterns
- [Graphitron Mental Model](../geps/GEP-001.1%20Graphitron%20Mental%20Model.md) - Conceptual foundation
- [graphitron-java-codegen README](../graphitron-codegen-parent/graphitron-java-codegen/README.md) - Complete directive reference

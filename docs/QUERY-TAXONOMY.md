# Graphitron Query Taxonomy

This document defines the vocabulary for discussing Graphitron's generated query code. The focus is on **Queries** (methods that execute SQL) and **QueryParts** (methods that build SQL fragments).

---

## Overview

Graphitron generates two categories of methods in `{Type}DBQueries` classes:

```
┌─────────────────────────────────────────────────────────────┐
│  QUERIES (execute SQL, return data)                         │
│  ├── Root Queries      - Entry points on Query/Mutation     │
│  ├── Loader Queries    - Batched queries for nested fields  │
│  └── Count Queries     - Aggregation for pagination         │
├─────────────────────────────────────────────────────────────┤
│  QUERYPARTS (build SQL fragments, compose into queries)     │
│  ├── Field Builders    - SELECT clause fragments            │
│  ├── Condition Methods - WHERE clause fragments             │
│  └── Table Methods     - FROM clause / table references     │
└─────────────────────────────────────────────────────────────┘
```

---

## Queries

Queries are methods that **execute SQL** against the database and return results.

### Root Queries

Entry points called once per GraphQL request. Generated for fields on `Query` and `Mutation` types.

**Characteristics:**
- Execute complete SQL statements
- Called once per request (not N times)
- May include inline QueryParts for nested data

**Variations:**

| Return Pattern | When Used |
|----------------|-----------|
| `Type` | Single object field |
| `List<Type>` | List field |
| `List<Pair<String, Type>>` | Paginated field (cursor + data) |
| `String` | Mutation returning ID |
| `Integer` | Mutation returning affected count |

**Examples:**
- `customerForQuery(ctx, select)` - fetches a single customer
- `customersForQuery(ctx, pageSize, after, select)` - fetches paginated customers
- `createCustomerForMutation(ctx, input, select)` - inserts and returns

### Loader Queries

Batched queries for nested fields. Called once per batch of parent records via DataLoader.

**Characteristics:**
- Receive a set of parent keys
- Return a map from parent key to child data
- Enable N+1 prevention through batching

**When generated:**
- Fields with `@splitQuery` directive
- Fields with GraphQL arguments (implicit split)
- Reference fields that can't be inlined

**Signature pattern:**
```
Map<RowN<KeyTypes>, ResultType> fieldForParent(ctx, parentKeys, select)
```

**Examples:**
- `ordersForCustomer(ctx, customerIds, select)` - batched order lookup
- `addressForUser(ctx, userIds, select)` - batched address lookup

### Count Queries

Aggregation queries for pagination `totalCount` fields.

**Characteristics:**
- Execute `SELECT COUNT(*)` queries
- Generated alongside paginated queries
- May be root or loader style

**Examples:**
- `countCustomersForQuery(ctx, select)` - total count for root query
- `countOrdersForCustomer(ctx, customerIds, select)` - count per parent

---

## QueryParts

QueryParts are methods that **build SQL fragments** without executing them. They compose into larger queries.

### Field Builders

Private helper methods that return `SelectField<Type>` for use in SELECT clauses.

**Characteristics:**
- Return jOOQ `SelectField` or `Field` types
- Called by query methods to build column lists
- Handle record-to-DTO mapping via `.mapping()`

**Patterns:**

**Row mapping** (for single nested objects):
```java
DSL.row(FIELD1, FIELD2, ...).mapping(Type::new)
```

**Multiset** (for nested lists):
```java
DSL.multiset(select(...).from(...)).mapping(...)
```

**Correlated subquery** (for related data):
```java
DSL.field(select(...).from(RELATED).where(FK.eq(PARENT.ID)))
```

### Condition Methods

User-provided methods that return jOOQ `Condition` for WHERE clauses.

**Characteristics:**
- Referenced via `@condition` directive
- Receive table alias and optional parameters
- Return `Condition` that gets added to WHERE clause

**Example usage:**
```graphql
type Query {
  activeCustomers: [Customer!]!
    @condition(condition: {className: "Conditions", method: "isActive"})
}
```

```java
public class Conditions {
  public static Condition isActive(Customer table) {
    return table.STATUS.eq("ACTIVE");
  }
}
```

### Table Methods

User-provided methods that return modified table references.

**Characteristics:**
- Referenced via `@tableMethod` directive
- Can return filtered/transformed table views
- Affect the FROM clause

---

## Query Composition

### Inline vs Split

Graphitron uses two strategies for fetching related data:

**Inline** (default):
- Nested data fetched in same query via correlated subqueries
- Uses `multiset()` for lists, `row()` for single objects
- One database round-trip
- Field Builders compose into parent query

**Split** (`@splitQuery`):
- Nested data fetched in separate batched query
- Uses DataLoader for batching across parents
- Two database round-trips
- Loader Query executes independently

### Composition Flow

```
Root Query
├── Field Builders (inline nested data)
│   ├── multiset(...) for one-to-many
│   └── row(...) for many-to-one
├── Condition Methods (WHERE clauses)
└── Table Methods (FROM clause modifications)

Loader Query (separate execution)
├── Batches parent keys
├── Returns Map<ParentKey, ChildData>
└── GraphQL-Java distributes results
```

---

## Naming Conventions

### Query Methods

| Type | Pattern | Example |
|------|---------|---------|
| Root query | `{field}For{Parent}` | `customerForQuery` |
| Loader query | `{field}For{Parent}` | `ordersForCustomer` |
| Count query | `count{Field}For{Parent}` | `countCustomersForQuery` |

### Field Builders

| Type | Pattern | Example |
|------|---------|---------|
| Direct helper | `{parentMethod}_{field}` | `customerForQuery_address` |
| Nested helper | `_{depth}_{parentMethod}_{field}` | `_1_customerForQuery_orders` |

---

## Summary

| Term | What It Is | Executes SQL? |
|------|------------|---------------|
| **Root Query** | Entry point for Query/Mutation field | Yes |
| **Loader Query** | Batched query for nested field | Yes |
| **Count Query** | Aggregation for totalCount | Yes |
| **Field Builder** | SelectField fragment for composition | No |
| **Condition Method** | User-provided WHERE clause | No |
| **Table Method** | User-provided FROM clause modification | No |
| **Inline** | Nested data via correlated subquery | Part of parent |
| **Split** | Nested data via separate batched query | Separate execution |

---

**See also:**
- [What Graphitron Generates](WHAT-GRAPHITRON-GENERATES.md) - Generated class structure
- [Code Generation Triggers](CODE-GENERATION-TRIGGERS.md) - What schema patterns trigger what
- [graphitron-java-codegen README](../graphitron-codegen-parent/graphitron-java-codegen/README.md) - Directive reference

# What Graphitron Generates

This document describes the vocabulary and taxonomy of generated code. For what triggers code generation, see [Code Generation Triggers](CODE-GENERATION-TRIGGERS.md).

---

## The Big Picture

```
GraphQL Schema + @directives
         ↓ Graphitron generates
┌─────────────────────────────────────────────────────────────┐
│  Graphitron                 (entry point)                   │
│       ↓                                                     │
│  Wiring                     (connects fetchers to schema)   │
│       ↓                                                     │
│  {Type}GeneratedDataFetcher (one per GraphQL type)          │
│       ↓                                                     │
│  {Type}DBQueries            (SQL query builders)            │
│       ↓                                                     │
│  RecordTransformer          (jOOQ ↔ DTO conversion)         │
│  {Type}TypeMapper           (field-level mapping)           │
└─────────────────────────────────────────────────────────────┘
```

Graphitron generates a layered architecture where each layer has a single responsibility:

1. **Entry Point** - Application integration
2. **Wiring** - GraphQL field → Java method mapping
3. **DataFetchers** - Request handling and orchestration
4. **Query Builders** - SQL generation via jOOQ
5. **Transformers/Mappers** - Data conversion between layers

---

## Generated Components

### Entry Point (Graphitron)

The main integration point for your application. Provides access to:
- The GraphQL type registry (parsed schema)
- The runtime wiring (field-to-fetcher mapping)
- A fully configured GraphQL schema ready to execute

Your application calls this to get the schema, then uses it with GraphQL-Java's execution engine.

### Wiring

Connects GraphQL schema fields to their implementations. For each field that needs data fetching, wiring registers which DataFetcher handles it.

This is a thin configuration layer - it doesn't contain business logic, just the mapping from "GraphQL field X" to "Java method Y".

### DataFetchers

One class per GraphQL type that has fields requiring data fetching. DataFetchers are responsible for:

- **Receiving requests** from the GraphQL execution engine
- **Extracting arguments** from the GraphQL query
- **Delegating to query builders** for actual data retrieval
- **Coordinating transformations** between input DTOs and database records

DataFetchers handle the "orchestration" - they know what needs to happen but delegate the actual work to specialized components.

### Query Builders ({Type}DBQueries)

One class per GraphQL type containing jOOQ query logic. These are where SQL gets built. Query builders are responsible for:

- **Constructing jOOQ queries** based on what fields were requested
- **Handling relationships** through joins or correlated subqueries
- **Applying filters** from GraphQL arguments
- **Batching** for fields that use the DataLoader pattern

Query builders receive the GraphQL selection set and build only the SQL needed to satisfy the request - no over-fetching.

### Transformers and Mappers

Convert data between representations:

- **RecordTransformer** - Coordinates conversion between jOOQ Records and GraphQL DTOs
- **{Type}TypeMapper** - Handles field-level mapping for a specific type, respecting the selection set

These components ensure that only requested fields are mapped, maintaining the selection-set-driven approach throughout the stack.

### DTOs

Plain Java classes matching GraphQL types. Used as the return type from resolvers. Generated with fields matching the GraphQL schema.

---

## Key Concepts

### Selection-Set-Driven Processing

The core principle: only fetch and process what the GraphQL query actually requested.

This flows through every layer:
1. **Query builders** select only requested columns
2. **Mappers** populate only requested fields
3. **No over-fetching** at any layer

### Inline vs Split Queries

Graphitron uses two strategies for fetching related data:

**Inline (default)** - Related data is fetched in the same query using correlated subqueries. One database round-trip fetches parent and children together. Best for relationships that are almost always needed together.

**Split (`@splitQuery`)** - Related data is fetched in a separate batched query using the DataLoader pattern. Parent query runs first, then child query batches all parent IDs together. Best for optional relationships or when the child query is complex.

### Correlated Subqueries

For inline fetching, Graphitron generates correlated subqueries that reference the parent query:

- **Single related record** (many-to-one) - Returns one nested object or null
- **Multiple related records** (one-to-many) - Returns a list of nested objects

This keeps related data nested in the result without flattening via JOINs, and avoids the N+1 problem by fetching everything in one query.

### DataLoader Batching

For split queries, Graphitron uses GraphQL-Java's DataLoader mechanism:

1. Multiple parent records request their children
2. All child IDs are collected into a batch
3. One query fetches all children
4. Results are distributed back to parents

This also avoids N+1 queries but with two round-trips instead of one.

---

## Vocabulary Summary

| Term | What It Is |
|------|------------|
| **Graphitron** | Entry point class providing schema and wiring |
| **Wiring** | Configuration connecting GraphQL fields to fetchers |
| **DataFetcher** | Handles a GraphQL field request, orchestrates data retrieval |
| **DBQueries** | Builds jOOQ queries for a GraphQL type |
| **RecordTransformer** | Coordinates record-to-DTO conversion |
| **TypeMapper** | Maps fields for a specific type |
| **Selection set** | The fields requested in a GraphQL query |
| **Inline query** | Fetches related data via correlated subquery |
| **Split query** | Fetches related data via separate batched query |
| **Correlated subquery** | Nested SELECT that references parent query |

---

**See also:**
- [Code Generation Triggers](CODE-GENERATION-TRIGGERS.md) - What schema patterns trigger what code
- [graphitron-java-codegen README](../graphitron-codegen-parent/graphitron-java-codegen/README.md) - Complete directive reference
- [Graphitron Principles](GRAPHITRON-PRINCIPLES.md) - Design philosophy

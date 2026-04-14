# Code Generation Triggers

This document is a reference for what schema patterns trigger what code generation in Graphitron.
For the rewrite pipeline architecture, see [Rewrite Roadmap](REWRITE-ROADMAP.md).

---

## How Classification Works

`GraphitronSchemaBuilder` reads the schema once and classifies every type and field into a sealed
hierarchy. The generators then operate on these classified models — they never re-read directives.

```
GraphQL Schema
      ↓
GraphitronSchemaBuilder  (the only place directives are read)
      ↓
GraphitronSchema
  ├── Map<String, GraphitronType>   (one per GraphQL type)
  └── Map<String, GraphitronField>  (one per field)
      ↓
Generators
  ├── TypeFieldsGenerator  →  rewrite.types.*Fields
  └── TypeClassGenerator   →  rewrite.types.*
```

Each sealed variant maps to specific generator output. The sections below show the full
directive-pattern → variant → generator output chain.

---

## Type Classification

| Directive Pattern on Type | `GraphitronType` Variant | Generator Output |
|---|---|---|
| `@table` (no `@node`, no `@discriminate`) | `TableType` | `*Fields` class + `*Tables` class |
| `@table` + `@node` | `NodeType` | `*Fields` class + `*Tables` class (with Relay ID handling) |
| `@record` | `ResultType` | `*Fields` class only (no SQL scope of its own) |
| `Query` or `Mutation` root type | `RootType` | `*Fields` class only |
| Interface with `@table` + `@discriminate` | `TableInterfaceType` | `*Fields` class |
| Interface without `@table` (multi-table) | `InterfaceType` | `*Fields` class |
| Union type | `UnionType` | `*Fields` class |
| `@error` | `ErrorType` | No generation (error mapping config) |
| Input type with `@table` | `TableInputType` | Used in mutation generation |
| Input type without `@table` | `InputType` | No generation (developer-provided class) |
| Conflicting or unresolvable directives | `UnclassifiedType` | Validation error — build fails |

---

## Field Classification

Fields are classified separately for root types (Query/Mutation) and nested types.

### Query Fields

| Schema Pattern | `QueryField` Variant | `*Fields` Generates |
|---|---|---|
| Any argument has `@lookupKey` | `QueryLookupTableField` | Async DataLoader fetcher + sync `lookup*()` rows method |
| `@tableMethod` | `QueryTableMethodTableField` | Field method stub |
| Field named `node` | `QueryNodeField` | Field method stub |
| Field named `_entities` (Federation) | `QueryEntityField` | Field method stub |
| Return: `@table`+`@discriminate` interface | `QueryTableInterfaceField` | Field method stub |
| Return: multi-table interface | `QueryInterfaceField` | Field method stub |
| Return: union | `QueryUnionField` | Field method stub |
| `@service`, return `@table` type | `QueryServiceTableField` | Async DataLoader fetcher + `rows*()` method |
| `@service`, return non-table type | `QueryServiceRecordField` | Field method stub |
| Return: `@table` type (default) | `QueryTableField` | Full fetcher — condition call + orderBy build + delegates to `Tables.selectMany/selectOne` |
| Anything else | `UnclassifiedField` | Validation error — build fails |

### Mutation Fields

| Schema Pattern | `MutationField` Variant | `*Fields` Generates |
|---|---|---|
| `@mutation(typeName: INSERT)` | `MutationInsertTableField` | Field method stub |
| `@mutation(typeName: UPDATE)` | `MutationUpdateTableField` | Field method stub |
| `@mutation(typeName: DELETE)` | `MutationDeleteTableField` | Field method stub |
| `@mutation(typeName: UPSERT)` | `MutationUpsertTableField` | Field method stub |
| `@service`, return `@table` type | `MutationServiceTableField` | Async DataLoader fetcher + `rows*()` method |
| `@service`, return non-table type | `MutationServiceRecordField` | Field method stub |
| Neither `@service` nor `@mutation` | `UnclassifiedField` | Validation error — build fails |
| Both `@service` and `@mutation` | `UnclassifiedField` | Validation error — build fails |

### Child Fields (on `@table` parent)

#### Scalar / Enum return type

| Schema Pattern | `ChildField` Variant | `*Fields` Generates |
|---|---|---|
| `@nodeId(typeName:)` | `NodeIdReferenceField` | Column method in `wiring()` |
| `@nodeId` (no typeName) | `NodeIdField` | Column method in `wiring()` |
| `@reference` on scalar | `ColumnReferenceField` | Column method in `wiring()` |
| `@field(name:)` or matching column name | `ColumnField` | Column method in `wiring()` |

#### Object return type

| Schema Pattern | `ChildField` Variant | `*Fields` Generates |
|---|---|---|
| `@externalField` | `ComputedField` | Column method in `wiring()` (developer supplies `Field<?>`) |
| `@tableMethod` | `TableMethodField` | Field method stub |
| `@service`, return `@table` | `ServiceTableField` | Async DataLoader fetcher + `rows*()` method |
| `@service`, return non-table | `ServiceRecordField` | Field method stub |
| Return `@table`, `@splitQuery` + `@lookupKey` | `SplitLookupTableField` | Async DataLoader fetcher + `rows*()` method |
| Return `@table`, `@lookupKey` (no split) | `LookupTableField` | Field method stub (`Tables.subselectMany/subselectOne`) |
| Return `@table`, `@splitQuery` | `SplitTableField` | Async DataLoader fetcher + `rows*()` method |
| Return `@table` (default) | `TableField` | Field method stub (`Tables.subselectMany/subselectOne`) |
| Return `@table`+`@discriminate` interface | `TableInterfaceField` | Field method stub |
| Return multi-table interface | `InterfaceField` | Field method stub |
| Return union | `UnionField` | Field method stub |
| Return plain object (no `@table`) | `NestingField` | Field method stub (inherits parent table context) |
| `@notGenerated` | `NotGeneratedField` | Nothing — field is omitted from `wiring()` |
| Conflicting directives | `UnclassifiedField` | Validation error — build fails |

### Child Fields (on `@record` parent)

| Schema Pattern | `ChildField` Variant | `*Fields` Generates |
|---|---|---|
| Scalar/enum with `@field` | `PropertyField` | Column method in `wiring()` |
| Return `@table`, `@lookupKey` | `RecordLookupTableField` | Async DataLoader fetcher + `rows*()` method |
| Return `@table` (default) | `RecordTableField` | Async DataLoader fetcher + `rows*()` method |
| Return non-table type | `RecordField` | Column method in `wiring()` |
| `@service`, return `@table` | `ServiceTableField` | Async DataLoader fetcher + `rows*()` method |
| `@service`, return non-table | `ServiceRecordField` | Field method stub |

---

## What the Generators Produce

### `*Fields` class (`rewrite.types.<TypeName>Fields`)

One class per `TableType`, `NodeType`, `ResultType`, `RootType`, interface, or union.

**`wiring()` method** — registers all field DataFetchers:
```java
public static TypeRuntimeWiring.Builder wiring() {
    return TypeRuntimeWiring.newTypeWiring("Film")
        .dataFetcher("title",    FilmFields::title)
        .dataFetcher("actors",   FilmFields::actors)
        .dataFetcher("language", FilmFields::language);
}
```

**Column methods** (for `ColumnField`, `NodeIdField`, `ComputedField`, `PropertyField`, etc.):
```java
// Generated — registered in wiring()
public static Object title(DataFetchingEnvironment env) { ... }
```

**Async DataLoader methods** (for `SplitTableField`, `ServiceTableField`, `QueryLookupTableField`, etc.):
```java
// Async fetcher — schedules batched load
public static CompletableFuture<List<Record>> actors(DataFetchingEnvironment env) { ... }

// Rows method — executes batched SQL when DataLoader fires
public static List<List<Record>> loadActors(List<Record> sourceRows, SelectedField sel, ...) { ... }
```

### `*Conditions` class (`rewrite.types.<TypeName>Conditions`)

One class per type that has fields with Graphitron-generated argument predicates.

Each method is a pure function — takes the jOOQ table alias and typed argument values,
returns an `org.jooq.Condition`. No dependency on GraphQL runtime types.

```java
public class FilmConditions {
    // Generated for a text-enum argument (e.g. `rating: String @lookupArg`)
    static final Map<String, String> RATING_MAP = Map.of("G", "G", "PG", "PG", ...);

    public static Condition films(FilmTable table, String title, String rating) {
        var condition = DSL.noCondition();
        if (title != null) condition = condition.and(table.TITLE.eq(DSL.val(title, table.TITLE)));
        condition = condition.and(table.RATING.eq(DSL.val(RATING_MAP.get(rating), table.RATING)));
        return condition;
    }
}
```

The fetcher calls this method to build the WHERE clause, then delegates to the `*` class.

---

### `*` class (`rewrite.types.<TypeName>`)

One class per GraphQL type (e.g. `Film` for `type Film @table`). Named after the GraphQL type,
not the SQL table — two GraphQL types mapped to the same table each get their own class.

```java
// SELECT list builder — iterates the selection set, adds table columns for requested fields
List<Field<?>>       fields(DataFetchingFieldSelectionSet sel)

// Top-level queries (root Query/Mutation fields)
Result<Record>       selectMany(DataFetchingEnvironment env, Condition condition, List<SortField<?>> orderBy)
Record               selectOne (DataFetchingEnvironment env, Condition condition)

// Inline nested data (ChildField.TableField / LookupTableField) — returns a multiset expression
Field<Result<Record>> subselectMany(DataFetchingEnvironment env, SelectedField sel, Condition condition, List<SortField<?>> orderBy)
Field<Record>         subselectOne (DataFetchingEnvironment env, SelectedField sel, Condition condition)

// DataLoader batch queries (SplitTableField, Row-keyed service fields)
List<List<Record>>  selectManyByRowKeys(List<? extends Row> keys, DataFetchingEnvironment env, SelectedField sel, List<?> serviceRecords)
List<Record>        selectOneByRowKeys (List<? extends Row> keys, DataFetchingEnvironment env, SelectedField sel, Object serviceRecord)

// DataLoader batch queries (Record-keyed service fields — TableRecord or RecordN parents)
List<List<Record>>  selectManyByRecordKeys(List<? extends Record> keys, DataFetchingEnvironment env, SelectedField sel, List<?> serviceRecords)
List<Record>        selectOneByRecordKeys (List<? extends Record> keys, DataFetchingEnvironment env, SelectedField sel, Object serviceRecord)
```

`env` is threaded through all methods for context arguments (e.g. tenant ID). `SelectedField` and
`DataFetchingFieldSelectionSet` allow implementations to build selection-aware queries (only fetch
requested columns). The batch overloads are currently stubs throwing `UnsupportedOperationException`.

---

## Directive Reference

### `@table`

Classifies a type as table-backed. Required for SQL generation on that type.

```graphql
type Film @table(name: "FILM") {
  title: String!   # Maps to FILM.TITLE
}
```

Optional `name` argument — defaults to the type name uppercased if omitted.

---

### `@node`

Adds Relay Global Object Identification. Pair with `@table`.

```graphql
type Film implements Node @table(name: "FILM") @node {
  id: ID! @nodeId
}
```

Optional parameters: `typeId` (custom string embedded in the global ID),
`keyColumns` (ordered list of PK columns for composite keys).

---

### `@nodeId`

Marks a field as a Relay global ID. Required on the `id` field of any `@node` type.
Also used on input types to decode incoming global IDs.

```graphql
input FilmInput @table(name: "FILM") {
  id: ID! @nodeId(typeName: "Film")  # decoded to PK before use
}
```

---

### `@field`

Maps a GraphQL field to a differently-named database column.

```graphql
type Film @table(name: "FILM") {
  releaseYear: Int @field(name: "RELEASE_YEAR")
}
```

Also supports `javaName` for Java record field mapping.

---

### `@reference`

Defines the FK path from the current type to the field's return type.

```graphql
type Film @table(name: "FILM") {
  language: Language @reference(path: [{key: "FILM__FILM_LANGUAGE_ID_FKEY"}])
}
```

Path elements can contain:
- `key` — explicit jOOQ foreign key name (e.g. `{key: "FILM__FILM_LANGUAGE_ID_FKEY"}`)
- `table` — implicit FK resolution: finds the unique FK from the current table to the named target table automatically (e.g. `{table: "LANGUAGE"}`); build fails if multiple FKs exist between the two tables
- `condition` — extra SQL condition on this step (`{className, method}`)

Without `@splitQuery` or arguments → inline subquery via `Tables.subselectMany/subselectOne`.
With `@splitQuery` or arguments → DataLoader.

---

### `@splitQuery`

Forces a DataLoader (batched separate query) for a child field, even when inline would work.

```graphql
type Film @table(name: "FILM") {
  activityLog: [Activity!]! @splitQuery @reference(path: [{key: "FK_ACTIVITY_FILM"}])
}
```

---

### `@lookupKey`

Marks an argument or input field as a primary/unique key for `WHERE pk IN (?)` lookup.
Preserves result order matching input order.

```graphql
type Query {
  films(ids: [ID!]! @lookupKey): [Film]!
}
```

Composite key: use an input type where each field maps to a key column.

---

### `@orderBy`

Enables ORDER BY with index validation. Uses `@index` on enum values to reference DB indexes.

```graphql
type Query {
  films(orderBy: FilmOrderBy @orderBy): [Film]!
}

enum FilmOrderByField {
  TITLE       @index(name: "IDX_FILM_TITLE")
  RELEASE_YEAR @index(name: "IDX_FILM_RELEASE_YEAR")
}
```

---

### `@mutation`

Classifies a Mutation field as INSERT, UPDATE, DELETE, or UPSERT.

```graphql
type Mutation {
  createFilm(input: FilmInput!): Film! @mutation(typeName: INSERT)
  updateFilm(id: ID!, input: FilmInput!): Film! @mutation(typeName: UPDATE)
  deleteFilm(id: ID!): Boolean! @mutation(typeName: DELETE)
}
```

Mutations on PostgreSQL use `RETURNING *` to fetch the result in a single round-trip.

---

### `@service`

Escapes SQL generation entirely. The field is backed by a developer-provided Java method
rather than a generated query.

```graphql
type Film @table(name: "FILM") {
  recommendations: [Film!]!
    @service(service: {className: "RecommendationService", method: "forFilm"})
}
```

If the return type is `@table`-backed, Graphitron generates a DataLoader that calls the service
method and then runs the result through the normal table SQL scope.

---

### `@condition`

Injects a developer-provided `Condition` into the WHERE clause of the generated query.

```graphql
type Query {
  activeFilms: [Film!]!
    @condition(condition: {className: "FilmConditions", method: "isActive"}, override: true)
}
```

Can also appear inside a `@reference` path element to filter a specific join step.

---

### `@externalField`

Injects a developer-provided `Field<T>` expression into the SELECT clause.

```graphql
type Film @table(name: "FILM") {
  fullTitle: String! @externalField
}
```

Developer provides:
```java
public static Field<String> fullTitle(FilmTable film) {
    return DSL.concat(film.TITLE, DSL.val(" ("), film.RELEASE_YEAR.cast(String.class), DSL.val(")"));
}
```

---

### `@tableMethod`

Replaces the FROM clause table with a developer-provided expression (e.g. a function-valued
table, a filtered view, or a renamed alias).

```graphql
type Query {
  topFilms: [Film!]! @tableMethod(tableMethodReference: {className: "FilmMethods", method: "top100"})
}
```

---

### `@record`

Maps an object or input type to a developer-provided Java record class rather than a
generated jOOQ table record. Used for service-backed types or custom input shapes.

```graphql
input FilmInput @record(record: {className: "FilmJavaRecord"}) {
  title: String!
}
```

---

### `@discriminate` and `@discriminator`

Single-table inheritance pattern. The interface specifies which column holds the
discriminator; each implementing type specifies its value.

```graphql
interface Vehicle @table(name: "VEHICLES") @discriminate(on: "TYPE") { id: ID! }
type Car  implements Vehicle @discriminator(value: "CAR")  { doors: Int! }
type Bike implements Vehicle @discriminator(value: "BIKE") { gears: Int! }
```

---

### `@notGenerated`

Suppresses generation for a specific field. The field is declared in the schema but
Graphitron will not register a DataFetcher for it — the developer provides the
implementation at runtime.

```graphql
type Film @table(name: "FILM") {
  computedScore: Float! @notGenerated
}
```

---

### Schema Transformation Directives

These are processed by `graphitron-schema-transform` **before** code generation.

#### `@asConnection`

Transforms a list field into a Relay Connection type (adds `edges`, `pageInfo`, `nodes`,
and optionally `totalCount`).

```graphql
type Query {
  films: [Film] @asConnection  # becomes FilmConnection
}
```

Optional: `defaultFirstValue` (default page size), `connectionName` (custom type name).

---

## Implicit Triggers

These generate code without any directive.

| Schema Pattern | Classification Effect |
|---|---|
| Field on `Query` root | Classified as a `QueryField` variant |
| Field on `Mutation` root | Classified as a `MutationField` variant |
| Field name matches column name (on `@table` type) | `ColumnField` — direct column mapping |
| Field returns `*Connection` type | `FieldWrapper.Connection` — pagination logic |
| Field named `totalCount` on `*Connection` | Separate `COUNT(*)` query |
| Fields named `edges`, `pageInfo`, `nodes` on `*Connection` | Trivial extraction from pagination result |
| GraphQL enum on `@table` field | Enum↔DB string/int mapping |
| Interface with different `@table` per impl | `InterfaceType` — multi-table type resolver |
| Union type | `UnionType` — type resolver by record class |

---

## Quick Lookup

| Schema Pattern | `GraphitronField` Variant | `*Fields` Generates |
|---|---|---|
| Root Query field (default) | `QueryTableField` | Full fetcher — condition + orderBy + `Tables.selectMany/selectOne` |
| Root Query field + `@lookupKey` | `QueryLookupTableField` | Async DataLoader fetcher |
| Root Query field + `@service` | `QueryServiceTableField` | Async DataLoader fetcher |
| Root Mutation field + `@mutation` | `MutationInsertTableField` / `Update` / `Delete` / `Upsert` | Method stub |
| Child: scalar/enum on `@table` | `ColumnField` | Column method in `wiring()` |
| Child: `@field(name:)` | `ColumnField` | Column method in `wiring()` |
| Child: `@nodeId` | `NodeIdField` | Column method in `wiring()` |
| Child: `@externalField` | `ComputedField` | Column method in `wiring()` |
| Child: `@reference` (default) | `TableField` | Method stub; SQL from `Tables.subselectMany/subselectOne` |
| Child: `@splitQuery` | `SplitTableField` | Async DataLoader fetcher |
| Child: `@lookupKey` (non-root) | `LookupTableField` | Method stub; SQL from `Tables.subselectMany/subselectOne` |
| Child: `@service` | `ServiceTableField` | Async DataLoader fetcher |
| `@notGenerated` | `NotGeneratedField` | Nothing |
| Invalid combination | `UnclassifiedField` | Validation error |

---

**See also:**
- [Rewrite Roadmap](REWRITE-ROADMAP.md) — migration plan and generator architecture
- [graphitron-java-codegen README](../graphitron-codegen-parent/graphitron-java-codegen/README.md) — complete directive reference with legacy examples

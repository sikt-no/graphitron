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

## Classification Vocabulary

### Source context

Every field has a source context — the type on which it is defined.

| Source context | Directive | What Graphitron generates |
|---|---|---|
| **Unmapped** | *(none — Query, Mutation)* | Entry point. No SQL yet. |
| **Table-mapped** | `@table` | Full SQL generation — queries, joins, projections. |
| **Result-mapped** | `@record` | Runtime wiring only. Graphitron validates types and wires data fetchers, but generates no SQL until a new scope starts. |

### Scope

A Graphitron scope corresponds to one SQL statement. Fields within a scope contribute to the same query.

| Boundary | Trigger |
|---|---|
| **Enter** | An unmapped root field reaches a table-mapped type — the first scope starts |
| **Split** | `@splitQuery` on a `SplitTableField` — new scope via DataLoader |
| **Lookup** | `@lookupKey` (no `@splitQuery`) on a `LookupTableField` — result-mapped parent: new scope via DataLoader; table-mapped parent: correlated subquery inlined in the current scope |
| **Split lookup** | `@splitQuery` + `@lookupKey` on a `SplitLookupTableField` — always a new scope via DataLoader with both derived tables |
| **Record handoff** | A `TableField` or `LookupTableField` on a result-mapped type, or a user-provided return (`@service`, `@tableMethod`) reaching a table-mapped type — new scope via DataLoader, keyed by the parent's PK |

`@service` fields use a **private scope** — they create their own SQL statement independently and do not participate in any Graphitron-managed scope.

### Derived tables

Two kinds of `VALUES(…)` derived tables built by Graphitron when batching:

- **Derived source table** — built from parent source records. Contains the FK-relevant columns from the parent: the parent's PK/unique-key columns when the FK is on the child side, or the FK columns themselves when the FK is on the parent side. Used for `@splitQuery` table fields, user-provided returns (`@service`, `@tableMethod`), and mutation read-backs.
- **Derived target table** — built from `@lookupKey` argument values (from `SelectedField.getArguments()`). Each argument value (or list element) is one row. **Identical for every source in a batch** — because all N parents in a batch share the same request arguments, M (the number of lookup rows) is constant for the entire batch. Result count is always exactly N × M. This is why `@condition` is blocked on lookup fields: any filter would break the positional invariant.

### Conditions

| Kind | Purpose | Source |
|---|---|---|
| **Reference condition** | How two tables are joined within a scope | `@reference` directive, FK metadata |
| **Filter condition** | Narrows the result set | `@condition` directive, arguments, cursor |

### Structural properties

| Property | Effect |
|---|---|
| **`@splitQuery`** | Forces a new scope via DataLoader. On a `TableField` (no `@lookupKey`) → `SplitTableField`; on a field with `@lookupKey` → `SplitLookupTableField`. Error on result-mapped fields (they always start a new scope implicitly). |
| **`@lookupKey`** | Argument values become the derived target table. Blocks `@condition` and pagination (preserves N × M result invariant). Without `@splitQuery` → `LookupTableField`; with `@splitQuery` → `SplitLookupTableField`. |

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

## Model Reference

`GraphitronSchemaBuilder` produces a `GraphitronSchema` — two flat maps a generator can query:

```java
schema.type("Film")          // → GraphitronType (or null)
schema.field("Film", "title") // → GraphitronField (or null)
schema.fieldsOf("Film")       // → List<GraphitronField> in declaration order
```

The values in those maps are sealed hierarchies. Pattern-match to get what you need.

### JooqCatalog

Lazy wrapper around the jOOQ `Catalog`. Used only by `GraphitronSchemaBuilder` and its permitted collaborators — generators never call it directly.

Key methods:
- `findTable(sqlName)` → `Optional<TableEntry>` (`javaFieldName`, `Table<?>`)
- `findColumn(table, sqlColumnName)` → `Optional<ColumnEntry>` (`javaName`, `columnClass`, `nullable`)
- `findForeignKey(name)` → searches by SQL constraint name or jOOQ Java constant name, case-insensitive
- `findForeignKeysBetweenTables(tableA, tableB)` → all FKs where one endpoint is `tableA` and the other is `tableB` (either direction)
- `findIndexColumns(tableSqlName, indexName)` → ordered list of `ColumnEntry` for a named index
- `findPkColumns(tableSqlName)` → PK columns in key-field order; empty when no PK
- `allColumnsOf(tableSqlName)` → all columns in declaration order
- `columnSqlNamesOf(tableSqlName)` → SQL column names only, for candidate hints in error messages
- `allTableSqlNames()` → all table SQL names, for candidate hints
- `allForeignKeySqlNames()` → all FK constraint names, for candidate hints

### GraphitronType variants

**Table-backed** (carry `TableRef table()`):
- `TableType` — `@table` without `@node`
- `NodeType` — `@table` + `@node`; also carries `typeId`, `nodeKeyColumns`
- `TableInterfaceType` — single-table discriminated interface; carries `discriminatorColumn`, `participants`

**Result-mapped** (`@record` types, carry `fqClassName`):
- `JavaRecordType`, `PojoResultType`, `JooqRecordType`, `JooqTableRecordType`

**Other**:
- `RootType` — Query or Mutation
- `InterfaceType`, `UnionType` — multi-table polymorphic; carry `participants`
- `ErrorType` — `@error` type; carries `handlers`
- `InputType` variants — `JavaRecordInputType`, `PojoInputType`, `JooqRecordInputType`, `JooqTableRecordInputType`
- `TableInputType` — `@table` input; owns DML; carries `table` and resolved `inputFields`
- `UnclassifiedType` — build-time classification failure; carries `reason`

### GraphitronField variants

Root fields (`RootField`):
- `QueryField` variants: `QueryTableField`, `QueryLookupTableField` (exposes `lookupMethodName()`), `QueryTableMethodTableField`, `QueryNodeField`, `QueryEntityField`, `QueryTableInterfaceField`, `QueryInterfaceField`, `QueryUnionField`, `QueryServiceTableField`, `QueryServiceRecordField`
  - `QueryTableField`, `QueryLookupTableField`, `QueryTableInterfaceField` implement `SqlGeneratingField`
- `MutationField` variants: `MutationInsertTableField`, `MutationUpdateTableField`, `MutationDeleteTableField`, `MutationUpsertTableField`, `MutationServiceTableField`, `MutationServiceRecordField`

Child fields (`ChildField`):
- Column access: `ColumnField`, `ColumnReferenceField`
- Node id: `NodeIdField`, `NodeIdReferenceField`
- Table-navigating (`TableTargetField` — implements `SqlGeneratingField`; carry `returnType`, `joinPath`, `filters`, `orderBy`, `pagination`): `TableField`, `SplitTableField`, `LookupTableField`, `SplitLookupTableField`, `TableInterfaceField`, `ServiceTableField`, `RecordTableField`, `RecordLookupTableField`
  - `SplitTableField`, `SplitLookupTableField` also carry `batchKey: BatchKey`
  - `ServiceTableField` also carries `method: MethodRef` and `batchKey` (via `method.params()`); exposes `rowsMethodName()` — the generated `load*()` helper name
- Other: `TableMethodField` (carries `method: MethodRef`), `InterfaceField`, `UnionField`, `NestingField`, `ConstructorField`, `ServiceRecordField`, `RecordField`, `ComputedField`, `PropertyField`, `MultitableReferenceField`

Special: `NotGeneratedField` (explicit `@notGenerated`), `UnclassifiedField` (carries `reason`)

### Support types

**`TableRef`** — a resolved jOOQ table: `tableName()` (SQL), `javaFieldName()` (e.g. `FILM`), `javaClassName()` (e.g. `Film`), `primaryKeyColumns()`, `hasPrimaryKey()`.

**`ColumnRef`** — a resolved column: `sqlName()`, `javaName()`, `columnClass()` (fully-qualified Java type).

**`ReturnTypeRef`** — what a field returns, combined with its `FieldWrapper`:
- `TableBoundReturnType` — Graphitron generates SQL; carries `table`
- `ResultReturnType` — `@record` type; no SQL
- `ScalarReturnType` — scalar or enum; no SQL
- `PolymorphicReturnType` — multi-table interface/union; generation not yet implemented

**`FieldWrapper`** — cardinality: `Single(nullable)`, `List(listNullable, itemNullable)`, `Connection(connectionNullable, itemNullable)`. Use `wrapper.isList()` instead of `!(wrapper instanceof Single)` — both `List` and `Connection` return `true`.

**`JoinStep`** — one hop in a `@reference` path:
- `FkJoin(fkName, targetTableSqlName, whereFilter)` — navigates via a jOOQ FK; `whereFilter` is an optional WHERE clause (not the JOIN ON)
- `ConditionJoin(condition)` — navigates via a user condition method, which becomes the ON clause

**`WhereFilter`** — one WHERE predicate. Sealed interface; `className()`, `methodName()`, `callParams()` define the call-site contract used uniformly by fetcher generators:
- `GeneratedConditionFilter` — Graphitron-generated predicate driven by field arguments. The builder produces one per SQL-generating field that has filterable arguments. Carries `className`, `methodName`, `tableRef`, `callParams` (call-site: argument extraction expressions), `bodyParams` (body-generation: column refs, nullability, enum mappings). A corresponding method is generated on the `*Conditions` class.
- `ConditionFilter` — developer-supplied `@condition` method. Carries `MethodRef method`; `callParams()` is derived from `method.params()` by skipping the implicit `Table` parameter.

**`SqlGeneratingField`** — orthogonal capability interface (does not extend `GraphitronField`). Implemented by `QueryTableField`, `QueryLookupTableField`, `QueryTableInterfaceField`, and all `TableTargetField` variants. Exposes `returnType()`, `filters()`, `orderBy()`, `pagination()`. Use `field instanceof SqlGeneratingField sgf` in generators that process all SQL-generating fields uniformly — no need to switch between `QueryField` and `ChildField` branches.

**`MethodRef`** — a resolved Java method: `className`, `methodName`, `returnTypeName`, `params`. Each param is either `Typed(name, typeName, source)` or `Sourced(name, batchKey)`. `ParamSource` variants: `Arg`, `Context`, `DslContext`, `Table`, `SourceTable`, `Sources(batchKey)`.

**`BatchKey`** — the DataLoader key strategy for a `Sourced` parameter: `RowKeyed(keyColumns)` (element type `RowN<T…>`), `RecordKeyed(keyColumns)` (element type `RecordN<T…>`), or `ObjectBased(fqClassName)` (whole parent record/DTO). `keyColumns` comes from the parent type's `primaryKeyColumns()`; it is empty for root operation fields with no backing table. All three carry `javaTypeName()` (the `List<?>` parameter type as a string). `RowKeyed` and `RecordKeyed` also carry `selectManyMethodName()` / `selectOneMethodName()` — the names of the generated table-class batch methods. `ObjectBased` batch loading is not yet implemented.

**`OrderBySpec`** — the ordering strategy for a SQL-generating field. Three variants:
- `Fixed(columns, direction)` — statically resolved ORDER BY. `direction` is `"ASC"` or `"DESC"` (from the directive); use `jooqMethodName()` when building jOOQ sort calls (returns `"asc"` or `"desc"`).
- `Argument(name, typeName, nonNull, list, sortFieldName, directionFieldName, namedOrders, base)` — dynamic ordering from an `@orderBy` argument; `base` is a `Fixed` fallback (may be `null`).
- `None` — no ordering applicable (single-value field, or no PK and no `@defaultOrder`).

---

**See also:**
- [Rewrite Roadmap](REWRITE-ROADMAP.md) — remaining generator work, design principles, and known gaps
- [graphitron-java-codegen README](../graphitron-codegen-parent/graphitron-java-codegen/README.md) — complete directive reference with examples

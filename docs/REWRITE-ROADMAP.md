# Rewrite Roadmap

This document tracks the migration from `ProcessedSchema` (the current God Object) to the new parse→validate→generate pipeline. It also documents the error quality pattern to follow as validation expands.

---

## Current State: Phase 1 Complete

The rewrite pipeline is live and running in parallel with the legacy pipeline:

```
GraphitronSchemaBuilder  →  GraphitronSchemaValidator  →  Generators (partial — see table below)
         ↓                            ↓
   GraphitronSchema           List<ValidationError>
   (sealed type/field             (warnings only,
    hierarchies)                  non-fatal)
```

**What's working:**
- `GraphitronSchemaBuilder` reads all schema directives once and classifies every type and field into a sealed hierarchy (`GraphitronType`, `GraphitronField`)
- `GraphitronSchemaValidator` collects all errors before reporting — no early exits
- `ValidationError` carries `SourceLocation` (file + line + column from the SDL)
- `ValidateMojo` runs both pipelines: legacy errors are fatal, rewrite errors are warnings

**Generator progress:**

| Generator / method | State |
|---|---|
| `FieldsCodeGenerator` — `wiring()` | **Done** — registers all field DataFetchers via method references |
| `FieldsCodeGenerator` — `ColumnField` data fetcher | **Done** — reads from `env.getSource()` via `TABLE.COLUMN` |
| `FieldsCodeGenerator` — `@service` field DataLoader + `load*()` body | **Done** — `computeIfAbsent`, `newDataLoaderWithContext`, delegates to service |
| `FieldsCodeGenerator` — `@splitQuery` field wiring | **Done** — async fetcher stub + typed `rows*()` stub |
| `TableCodeGenerator` — `selectMany` / `selectOne` | **Done** — `getDslContext().select(fields(env.getSelectionSet())).from(table).where(condition)...` |
| `TableCodeGenerator` — `subselectMany` / `subselectOne` | **Done** — `DSL.multiset(DSL.select(fields(sel.getSelectionSet())).from(table).where(condition)...)` |
| All other field types | Stub — signature generated, body throws `UnsupportedOperationException` |

The rewrite pipeline produces Java code for the cases above. Full SQL generation across all field types is Phase 2.

---

## Phase 2: Migrate Generators from ProcessedSchema

**Goal**: Replace `ProcessedSchema` queries with rewrite config, one generator at a time.

**Scope of ProcessedSchema today**:
- `ProcessedSchema.java`: 1,355 lines, 70+ query methods
- Usage: 247 `processedSchema.` calls across 42 generator files

**Migration pattern** (apply per generator):

```java
// Before
if (processedSchema.hasTable(typeName)) {
    var table = processedSchema.getTable(typeName);
    // ...
}

// After
var typeConfig = schema.types().get(typeName);
if (typeConfig instanceof GraphitronType.TableType t) {
    var table = t.table();
    // ...
}
```

**Approach**:
1. Pass `GraphitronSchema` alongside `ProcessedSchema` to the generator under migration
2. Replace each `processedSchema.` call with the equivalent rewrite lookup
3. Run the full test suite after each generator — confirm identical output
4. Remove the `ProcessedSchema` parameter once a generator is fully migrated

**Risk**: MEDIUM — touching code generation. Keep the legacy path running until Phase 3.

---

## Phase 3: Delete ProcessedSchema

Once all 247 call sites are migrated:

```java
// Clean entry point — no ProcessedSchema anywhere
CodeGenerationConfig config = new GraphitronSchemaBuilder(jooqCatalog).build(schema);
new GraphitronSchemaValidator().validate(config);
new CodeGenerator(config).generate();
```

**Delete**:
- `ProcessedSchema.java` (1,355 lines)
- `ProcessedDefinitionsValidator.java` (superseded by `GraphitronSchemaValidator`)

**Risk**: LOW — by this point every code path runs through rewrite config in production.

This is also the point where the `disableLegacy` flag in `GenerateMojo` becomes meaningful for external users.

---

## Error Quality: Candidate Hints

`GraphitronSchemaBuilder` already implements `candidateHint(attempt, candidates)` using Levenshtein distance to sort candidates by similarity. It is used in 12 places: table name lookups, column name lookups, FK name lookups, service method name lookups, and type name lookups. `ValidationError` carries `SourceLocation`.

When Phase 2 adds new jOOQ existence checks in the validator or builder, follow the same pattern — pass the relevant candidate list from `JooqCatalog` (`allTableSqlNames()`, `columnSqlNamesOf(table)`, `allForeignKeySqlNames()`) to `candidateHint`.

---

## Model Reference

`GraphitronSchemaBuilder` produces a `GraphitronSchema` — two flat maps a generator can query:

```java
schema.type("Film")          // → GraphitronType (or null)
schema.field("Film", "title") // → GraphitronField (or null)
schema.fieldsOf("Film")       // → List<GraphitronField> in declaration order
```

The values in those maps are sealed hierarchies. Pattern-match to get what you need.

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
- `QueryField` variants: `QueryTableField`, `QueryLookupTableField`, `QueryTableMethodTableField`, `QueryNodeField`, `QueryEntityField`, `QueryTableInterfaceField`, `QueryInterfaceField`, `QueryUnionField`, `QueryServiceTableField`, `QueryServiceRecordField`
- `MutationField` variants: `MutationInsertTableField`, `MutationUpdateTableField`, `MutationDeleteTableField`, `MutationUpsertTableField`, `MutationServiceTableField`, `MutationServiceRecordField`

Child fields (`ChildField`):
- Column access: `ColumnField`, `ColumnReferenceField`
- Node id: `NodeIdField`, `NodeIdReferenceField`
- Table-navigating (`TableTargetField` — carry `returnType`, `joinPath`, `filters`, `orderBy`, `pagination`): `TableField`, `SplitTableField`, `LookupTableField`, `SplitLookupTableField`, `TableInterfaceField`, `ServiceTableField`, `RecordTableField`, `RecordLookupTableField`
  - `SplitTableField` and `SplitLookupTableField` also carry `batchKey: BatchKey` — the DataLoader key strategy derived from the parent type's primary-key columns
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

**`FieldWrapper`** — cardinality: `Single(nullable)`, `List(listNullable, itemNullable)`, `Connection(connectionNullable, itemNullable)`.

**`JoinStep`** — one hop in a `@reference` path:
- `FkJoin(fkName, targetTableSqlName, whereFilter)` — navigates via a jOOQ FK; `whereFilter` is an optional WHERE clause (not the JOIN ON)
- `ConditionJoin(condition)` — navigates via a user condition method, which becomes the ON clause

**`WhereFilter`** — one WHERE predicate:
- `ColumnFilter` — scalar argument → `col = ?`
- `InputFilter` — `@table` input argument → composite equality
- `ConditionFilter` — `@condition` method

**`MethodRef`** — a resolved Java method: `className`, `methodName`, `returnTypeName`, `params`. Each param is either `Typed(name, typeName, source)` or `Sourced(name, batchKey)`. `ParamSource` variants: `Arg`, `Context`, `DslContext`, `Table`, `SourceTable`, `Sources(batchKey)`.

**`BatchKey`** — the DataLoader key strategy for a `Sourced` parameter: `RowKeyed(keyColumns)` (element type `RowN<T…>`), `RecordKeyed(keyColumns)` (element type `RecordN<T…>`), or `ObjectBased(fqClassName)` (whole parent record/DTO). `keyColumns` comes from the parent type's `primaryKeyColumns()`; it is empty for root operation fields with no backing table.

---

## Generated Code Architecture

The rewrite generators produce:

- **`rewrite.types.*Fields`** — one class per GraphQL type with a static method per field and a `wiring()` method that registers them all as DataFetchers via method references (e.g. `FilmFields::title`). GraphQL-Java only calls the methods for fields present in the selection set.
- **`rewrite.tables.*`** — one class per jOOQ table with `selectMany`/`selectOne` (top-level queries) and `subselectMany`/`subselectOne` (returns `Field<Result<Record>>` — a jOOQ multiset expression for inline nested data).

No DTOs, no TypeMappers. DataFetchers return `Result<Record>`; GraphQL-Java traverses the records using the registered field DataFetchers.

### Selection-aware queries and multiset

`DataFetchingFieldSelectionSet` and `SelectedField` are already threaded through all table method signatures, structurally committing to selection-aware queries. When the table method bodies are implemented:

- **Top-level**: build the column list from `selection.contains("fieldName")` checks, then `ctx.select(columns).from(TABLE)...`
- **Inline nesting**: use jOOQ `multiset(select(columns).from(CHILD).where(...)).as("alias")`, returned as `Field<?>` (type-erased). Use type erasure at every helper method boundary — jOOQ's generic types compound badly with nesting depth, causing slow compile times.
- **`@splitQuery`**: separate DataLoader; parent fetches the FK/PK columns, child batches by those keys.

### Query plan caching trade-off

Selection-driven queries produce different SQL per request (different column lists). The database cannot reuse cached query plans across requests. This is an acceptable cost for wide tables with large optional columns, but for narrow tables (≤10 columns) where most fields are always requested, selecting `TABLE.*` is simpler and the overhead of dynamic column selection exceeds the benefit.

### `@selectiveQuery` directive

Not yet in `directives.graphqls`. The intended design is an opt-in per-type directive (or global Maven config flag) to enable selection-aware column building. Add the directive when implementing the table method bodies.

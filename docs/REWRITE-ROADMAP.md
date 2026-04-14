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
| `TypeFieldsGenerator` — `wiring()` | **Done** — registers all field DataFetchers via method references |
| `TypeFieldsGenerator` — `ColumnField` data fetcher | **Done** — reads from `env.getSource()` via `TABLE.COLUMN` |
| `TypeFieldsGenerator` — `@service` field DataLoader + `load*()` body | **Done** — `computeIfAbsent`, `newDataLoaderWithContext`, delegates to service |
| `TypeFieldsGenerator` — `@splitQuery` field wiring | **Done** — async fetcher stub + typed `rows*()` stub |
| `TypeFieldsGenerator` — `QueryTableField` fetcher | **Done** — condition call + orderBy build + delegates to `Tables.selectMany/selectOne` |
| `TypeClassGenerator` — `selectMany` / `selectOne` | **Done** — `getDslContext().select(fields(env.getSelectionSet())).from(table).where(condition)...` |
| `TypeClassGenerator` — `subselectMany` / `subselectOne` | **Done** — `DSL.multiset(DSL.select(fields(sel.getSelectionSet())).from(table).where(condition)...)` |
| `TypeConditionsGenerator` | **Done** — one `*Conditions` class per type with argument-driven predicate methods |
| All other field types | Stub — signature generated, body throws `UnsupportedOperationException` |

The rewrite pipeline produces Java code for the cases above. Full SQL generation across all field types is Phase 2.

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

## Design Principles

### Generation-thinking

**Before implementing a generator body, ensure the model carries what the generator needs — pre-resolved, generation-ready.**

The model's job is to be a clean decision boundary. `GraphitronSchemaBuilder` reads directives once and resolves everything: table names, column references, method names, call-site argument extraction strategies, body-generation strategies. Generators receive a model that is already in terms of "what to emit", not "what to interpret".

Signs a model type needs more pre-resolution:
- A generator switches on a raw string (e.g. `"ASC".equalsIgnoreCase(fixed.direction())`)
- A generator contains a multi-arm type switch that recurs across multiple generators (the same switch in 3 places → move the result to the model)
- A generator recomputes a derived name from a field name (e.g. `"load" + capitalize(sf.name())`)
- Generation and calling are conflated in the same model type (e.g. the old `WhereFilter` carrying both column references for body-generation and call expressions for call-site — split them)

**The corollary for tests**: do not assert on generated method bodies. Assert on structural properties (method names, parameter types, return types, which methods exist). Body-content tests are implementation tests that break on every refactor. The correct signal that a body is right is compilation (`graphitron-rewrite-test-spec mvn compile`) and execution against a real database.

### Sealed hierarchies over enums for typed information

When different variants of a concept carry different data, use a sealed interface — not an enum with a shared field set. An enum forces every variant to have the same shape; a sealed record hierarchy gives each variant exactly the fields it needs.

`BatchKey` illustrates the pattern: `RowKeyed` and `RecordKeyed` carry `keyColumns: List<ColumnRef>`, while `ObjectBased` carries `fqClassName: String`. None carry fields they don't use. The compiler enforces exhaustive switches — when a new variant is added, every switch that doesn't handle it becomes a compile error.

### Classification belongs at the parse boundary

`ServiceCatalog.reflectServiceMethod()` and `ServiceCatalog.reflectTableMethod()` are the only places that read the reflection `java.lang.reflect.Type` tree to classify parameters. They convert raw reflection output into `MethodRef.Param` values (each carrying a `ParamSource`). Everything downstream — validator, generator — switches on the pre-classified values and never touches reflection types.

`JooqCatalog`, `TypeBuilder`, `FieldBuilder`, and `ServiceCatalog` are the only classes permitted to hold raw jOOQ types (`Table<?>`, `ForeignKey<?,?>`) or raw graphql-java schema types. If a generator needs information not yet in a taxonomy record, the fix is to add a component and extract the value in the builder — not to reach past the taxonomy boundary.

### Narrow component types over broad interfaces

Field record components are declared with the narrowest type the classifier can guarantee rather than the broad sealed-interface root. A field whose return type is always table-bound (e.g. `TableField`, `ServiceTableField`, `QueryTableField`) declares `ReturnTypeRef.TableBoundReturnType` directly; a field whose return type is always polymorphic declares `ReturnTypeRef.PolymorphicReturnType` directly.

This pushes classification certainty into the type system: code that receives a `ServiceTableField` knows its `returnType` is `TableBoundReturnType` without a runtime check. Where the return type genuinely varies, the broad `ReturnTypeRef` is retained. The same discipline applies to splitting semantically distinct variants into separate records rather than using a discriminating boolean or enum.

### Sub-taxonomies for resolution outcomes

Complex resolution outcomes get their own sealed type rather than being stored as raw strings. `BatchKey` is a sub-taxonomy of `ParamSource.Sources`, just as `TableRef` is a sub-taxonomy of `GraphitronType.TableBackedType` and `ColumnRef` is a sub-taxonomy of `InputField.ColumnField`. This pattern keeps each concept's complexity local and makes the taxonomy self-documenting: the type of a field tells you exactly what states it can be in.

---

## Phase 2: Two Parallel Tracks

Phase 2 has two independent workstreams that can be done in any order.

### Track A — Finish stub implementations

Complete the generator bodies that currently throw `UnsupportedOperationException`. These do not touch ProcessedSchema; they extend the rewrite pipeline directly.

**Prerequisite**: before implementing any stub body, check that the model is generation-ready (see Design Principle above). If the model for that field type still carries raw strings or requires resolution logic in the generator, fix the model first.

Stubs to complete (approximate priority order):
1. `TypeFieldsGenerator` — `QueryLookupTableField` fetcher body + `lookup*()` rows method body
2. `TypeFieldsGenerator` — `TableField` / `LookupTableField` inline-subquery field methods (call `Tables.subselectMany/subselectOne` with condition + orderBy)
3. `TypeFieldsGenerator` — `SplitTableField` / `SplitLookupTableField` rows method bodies (DataLoader batch SQL)
4. `TypeFieldsGenerator` — `QueryTableInterfaceField`, `QueryInterfaceField`, `QueryUnionField` fetchers
5. `TypeFieldsGenerator` — Mutation field bodies (all four DML variants: INSERT/UPDATE/DELETE/UPSERT)
6. `TypeClassGenerator` — `selectManyByRowKeys` / `selectOneByRowKeys` and `selectManyByRecordKeys` / `selectOneByRecordKeys` bodies

#### G5 — Inline `TableField`

`TableField` in table-mapped source context (no `@splitQuery`). Extends the SQL scope with an inline subselect — does not start a new scope or use a DataLoader. Introduces the static field method pattern (called from the parent type class during SELECT assembly).

#### G6 — Split/Lookup field categories

G6 covers four categories of DataLoader-backed field. Before implementing any category, verify the model is generation-ready.

| Category | DataLoader | Derived tables | `@condition` / non-`@lookupKey` args | Pagination |
|---|---|---|---|---|
| **`LookupQueryField`** (root lookup) | No — synchronous | Derived target only | Blocked (lookup invariant) | Never — result count = M exactly |
| **Table-mapped `LookupTableField`** (`@splitQuery` + `@lookupKey`, table-mapped parent) | No — correlated subquery | Derived target + correlated parent join | Blocked | Never |
| **Result-mapped `TableField`** (`@splitQuery`, no `@lookupKey`) | Yes | Derived source only | Allowed | Allowed |
| **Result-mapped `LookupTableField`** (`@splitQuery` + `@lookupKey`, result-mapped parent) | Yes | Both | Blocked | Never — result count = N × M |

Bespoke method signatures (all take `SelectedField sel` for argument unpacking; args are never passed as separate parameters):

| Category | Bespoke method | Return type |
|---|---|---|
| `LookupQueryField` | `static List<Record> lookup<FieldName>(DataFetchingEnvironment env, SelectedField sel)` | M results total |
| Table-mapped `LookupTableField` | `static Field<Result<Record>> subselect<FieldName>(<ParentAlias> alias, SelectedField sel)` | jOOQ multiset — embedded in parent SELECT |
| Result-mapped `TableField` — returns `[T]` | `static List<List<Record>> load<FieldName>(List<Row> sourceRows, DataFetchingEnvironment env, SelectedField sel)` | One inner list per source |
| Result-mapped `TableField` — returns `T` | `static List<Record> load<FieldName>(List<Row> sourceRows, DataFetchingEnvironment env, SelectedField sel)` | One Record per source |
| Result-mapped `TableField` — paginated | `static List<List<Record>> load<FieldName>Page(List<Row> sourceRows, DataFetchingEnvironment env, SelectedField sel)` | One page per source |
| Result-mapped `LookupTableField` | `static List<List<Record>> lookup<FieldName>(List<Row> sourceRows, DataFetchingEnvironment env, SelectedField sel)` | N inner lists, each up to M records |

SQL structure: each bespoke method builds an indexed `VALUES(…)` derived table (prepending a 1-based `idx`), JOINs it against the target, and partitions results back by `idx`. Result-mapped lookup fields use two `idx` columns (`src_idx`, `tgt_idx`). The table-mapped `LookupTableField` is built as a `DSL.multiset(…)` correlated subquery with the FK join condition baked into the generated method.

### Track B — Migrate legacy generators from ProcessedSchema

Replace `ProcessedSchema` queries with rewrite config, one generator at a time.

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

**Track B also includes:** rewrite `IntrospectMojo` to use `JooqCatalog` instead of `TableReflection`. Prerequisites are already in place: `JooqCatalog.ColumnEntry` carries `nullable()`, and `allColumnsOf(tableSqlName)` returns all columns. No module changes needed (`graphitron-maven-plugin` already depends on `graphitron-java-codegen`). No test changes needed (`IntrospectMojoTest` tests only JSON serialisation).

---

## Known Gaps

These are concrete, bounded problems identified during implementation. They block specific functionality but do not block the rest of Phase 2.

### `ConditionFilter` has no builder path

`FieldBuilder` currently produces `GeneratedConditionFilter` entries for filterable arguments, but never produces `ConditionFilter` entries for `@condition` directives. Field-level `@condition` annotations are not yet classified into the rewrite pipeline's filter list.

The gap is invisible to existing tests because `ConditionFilter` is constructed directly in test helpers (bypassing the builder). To expose it: write a `FieldBuilder` test that reads a schema with a `@condition` directive and asserts the resulting field carries a `ConditionFilter`.

**Fix**: add `@condition` directive reading to `FieldBuilder.resolveFilters()` (or equivalent). When the builder constructs `ConditionFilter`, it should also pre-resolve `callParams` (currently derived lazily from `method.params()`) and promote them to a record component, consistent with how `GeneratedConditionFilter` carries pre-resolved `callParams` and `bodyParams`.

### `ObjectBased` batch loading is unimplemented

`BatchKey.ObjectBased.selectManyMethodName()` and `selectOneMethodName()` throw `UnsupportedOperationException`. The `TypeClassGenerator` generates no `selectManyByObjectKeys` / `selectOneByObjectKeys` method.

Two options to resolve:
- **Option A** — determine that `ObjectBased` always implies a jOOQ `TableRecord` parent in practice, and collapse it into `RecordKeyed`. Remove `ObjectBased` from the sealed hierarchy.
- **Option B** — implement `selectManyByObjectKeys(List<SomeClass> keys, env, sel, serviceResult)` in `TypeClassGenerator`, handling the case where the key is an arbitrary Java object (not a jOOQ Record/Row).

Decision needed before implementing any `ObjectBased`-keyed service field.

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

## Generated Code Architecture

The rewrite generators produce:

- **`rewrite.types.*Fields`** — one class per GraphQL type with a static method per field and a `wiring()` method that registers them all as DataFetchers via method references (e.g. `FilmFields::title`). GraphQL-Java only calls the methods for fields present in the selection set.
- **`rewrite.types.*`** — one class per GraphQL type (e.g. `Film`) with `selectMany`/`selectOne` (top-level queries) and `subselectMany`/`subselectOne` (returns `Field<Result<Record>>` — a jOOQ multiset expression for inline nested data).
- **`rewrite.types.*Conditions`** — one class per type that has fields with Graphitron-generated argument predicates. Contains one `public static Condition` method per field (taking the jOOQ table alias and typed argument values), plus static `Map<String,String>` lookup fields for enum-text arguments. The fetcher calls these methods to build the WHERE condition. No dependency on GraphQL runtime types — testable as plain Java.

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

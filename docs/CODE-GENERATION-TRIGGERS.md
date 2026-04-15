# Code Generation Triggers

A guide to how GraphQL schema patterns drive Graphitron's code generation. This document introduces the classification pipeline and the vocabulary needed to read the source code. For variant details and record components, read the Javadoc on each source file listed in the [Source Map](#source-map) below.

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
  ├── TypeFieldsGenerator      →  rewrite.types.*Fields
  ├── TypeClassGenerator       →  rewrite.types.*
  └── TypeConditionsGenerator  →  rewrite.types.*Conditions
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
| `@table` (no `@node`, no `@discriminate`) | `TableType` | `*Fields` class + `*` class |
| `@table` + `@node` | `NodeType` | `*Fields` class + `*` class (with Relay ID handling) |
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

## Implicit Classification Rules

Not all classification requires directives. The builder also classifies based on:

| Schema Pattern | Classification Effect |
|---|---|
| Field name matches column name (on `@table` type) | `ColumnField` — direct column mapping |
| Field returns `*Connection` type (from `@asConnection` transform) | `FieldWrapper.Connection` — pagination logic |
| GraphQL enum on `@table` field | Enum-to-DB string/int mapping |

---

## Source Map

All source lives under `graphitron-rewrite/src/main/java/no/sikt/graphitron/rewrite/`. Each file has Javadoc documenting its variants and record components.

### Model (`model/`)

| Concept | File | What to look for |
|---|---|---|
| Type hierarchy | `GraphitronType.java` | Sealed interface — all type variants and their record components |
| Field hierarchy | `GraphitronField.java` | Sealed interface — `RootField`/`ChildField`/`InputField` sub-hierarchies |
| SQL-generating marker | `SqlGeneratingField.java` | Orthogonal interface — use `instanceof SqlGeneratingField` for uniform SQL field access |
| Table reference | `TableRef.java` | Resolved jOOQ table with PK columns |
| Column reference | `ColumnRef.java` | Resolved column with Java type |
| Return type | `ReturnTypeRef.java` | `TableBound` / `Result` / `Scalar` / `Polymorphic` |
| Cardinality | `FieldWrapper.java` | `Single` / `List` / `Connection` |
| Join path | `JoinStep.java` | `FkJoin` / `ConditionJoin` |
| WHERE filters | `WhereFilter.java` | `GeneratedConditionFilter` / `ConditionFilter` — call-site contract |
| Service methods | `MethodRef.java` | Resolved Java method with `ParamSource` variants |
| DataLoader keys | `BatchKey.java` | `RowKeyed` / `RecordKeyed` / `ObjectBased` |
| Ordering | `OrderBySpec.java` | `Fixed` / `Argument` / `None` |
| Pagination | `PaginationSpec.java` | Relay cursor arguments |
| Condition params | `CallParam.java`, `BodyParam.java` | Call-site vs body-generation views |

### Builders (root package)

| Component | File | Responsibility |
|---|---|---|
| Entry point | `GraphitronSchemaBuilder.java` | Sole directive-reading boundary — assembles `GraphitronSchema` |
| Type classification | `TypeBuilder.java` | Two-pass: classify types, then enrich interfaces/unions with participants |
| Field classification | `FieldBuilder.java` | Classifies fields based on parent type, directives, and return type |
| jOOQ lookups | `JooqCatalog.java` | Lazy wrapper around jOOQ `Catalog` — tables, columns, FKs, indexes, PKs |
| Service reflection | `ServiceCatalog.java` | Reflects `@service`/`@tableMethod` Java methods into `MethodRef` |

### Generators (`generators/`)

| Generator | Output | File |
|---|---|---|
| `TypeFieldsGenerator` | `rewrite.types.*Fields` — wiring + field methods | `TypeFieldsGenerator.java` |
| `TypeClassGenerator` | `rewrite.types.*` — select/subselect/batch methods | `TypeClassGenerator.java` |
| `TypeConditionsGenerator` | `rewrite.types.*Conditions` — pure-function WHERE predicates | `TypeConditionsGenerator.java` |

### Directives

- **SDL definitions**: `graphitron-common/src/main/resources/directives.graphqls`
- **Directive reference with examples**: [graphitron-java-codegen README](../graphitron-codegen-parent/graphitron-java-codegen/README.md)

---

**See also:**
- [Rewrite Roadmap](REWRITE-ROADMAP.md) — remaining generator work, design principles, and known gaps

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
  ├── TypeFetcherGenerator     →  fetchers.*Fetchers
  ├── TypeClassGenerator       →  types.*
  └── TypeConditionsGenerator  →  conditions.*Conditions
```

Each sealed variant maps to specific generator output. The sections below show the full
directive-pattern → variant → generator output chain.

---

## Classification Vocabulary

Two independent classifications describe every field: the **source context** it is defined on (parent type), and the **target type** it returns. Both matter because scope transitions are determined by the pair, not by either alone.

### Source context

The type on which a field is defined.

| Source context | Directive | What Graphitron generates |
|---|---|---|
| **Unmapped** | *(none — Query, Mutation)* | Entry point. No SQL yet. |
| **Table-mapped** | `@table` | Full SQL generation — queries, joins, projections. |
| **Result-mapped** | `@record` | Runtime wiring only. Graphitron validates types and wires data fetchers, but generates no SQL until a new scope starts. |

### Target type

The classification of the field's return type (the element type — looked through `List` and `Connection` wrappers). Encoded as `ReturnTypeRef`.

| Target type | `ReturnTypeRef` variant | When it appears |
|---|---|---|
| **Target table** | `TableBoundReturnType` | Return type has `@table` (or is a `@table` + `@discriminate` interface), or a `NestingField` inherits the parent's table context. Carries a fully resolved `TableRef`. |
| **Target record** | `ResultReturnType` | Return type has `@record`. |
| **Target scalar** | `ScalarReturnType` | Scalar, enum, or an unclassified type name (e.g. `@nodeId(typeName:)` argument types). |
| **Target polymorphic** | `PolymorphicReturnType` | Interfaces/unions spanning multiple tables, and the Relay/Federation built-ins `node` / `_entities`. |

"Target table" is the pivot concept for scope transitions: every new scope is a query rooted in some target table, driven either by the root entering a table-mapped type or by a **record handoff** from a result-mapped source into a target-table return.

### Scope

A Graphitron scope corresponds to one SQL statement. Fields within a scope contribute to the same query. Scope is determined by the **(source context, target type)** pair — *independently* of `@lookupKey`, which is orthogonal.

| Boundary | Trigger |
|---|---|
| **Enter** | An unmapped root field reaches a target-table type — the first scope starts |
| **Split** | `@splitQuery` on a table-mapped source — a new scope via DataLoader, keyed by the parent's PK |
| **Record handoff** | A target-table field on a result-mapped source, or any user-provided return (`@service`, `@tableMethod`) reaching a target-table type — new scope via DataLoader, keyed by the parent's PK or a custom batch key |
| **Exit** | `@service` fields create a **private scope** — their SQL statement is independent of any Graphitron-managed scope |

`@lookupKey` does not appear in this table on purpose. It shapes the batch (adds the derived target table and the N × M invariant) but does not by itself open or close a scope — that is always decided by the source/target pair above.

### Derived tables

Two kinds of `VALUES(…)` derived tables built by Graphitron when batching:

- **Derived source table** — built from parent source records. Contains the FK-relevant columns from the parent: the parent's PK/unique-key columns when the FK is on the child side, or the FK columns themselves when the FK is on the parent side. Used for `@splitQuery` table fields, user-provided returns (`@service`, `@tableMethod`), and mutation read-backs.
- **Derived target table** — built from `@lookupKey` argument values (from `SelectedField.getArguments()`). Each argument value (or list element) is one row. **Identical for every source in a batch** — all N parents in a batch share the same request arguments, so M (the number of lookup rows) is constant for the entire batch. Base result count is exactly N × M.

**`@condition` on lookup fields is allowed.** The condition method, however, must preserve the N × M positional contract: each (source, target) pair produced by the derived-table cross join is either kept in full or dropped in full, and no additional rows may be introduced. In practice this means the condition should be a predicate over the pair of rows, not a filter that can change the per-parent result cardinality non-uniformly. Violating the contract desynchronises batch dispatch — the client receives rows that cannot be reattached to their source. The contract is a developer responsibility, not a build-time check.

### Conditions

| Kind | Purpose | Source |
|---|---|---|
| **Reference condition** | How two tables are joined within a scope | `@reference` directive: FK key → `FkJoin`; condition method → `ConditionJoin` |
| **Filter condition** | Narrows the result set of the current scope | `@condition` directive, arguments, cursor |
| **Lookup condition** | Filters the (source × target) row pairs produced by a lookup's derived target table. Must preserve the N × M positional contract — see [Derived tables](#derived-tables) above. | `@condition` directive on a field with `@lookupKey` |

### Structural properties

| Property | Effect |
|---|---|
| **`@splitQuery`** | On a table-mapped source, forces a new scope via DataLoader: `TableField` (no `@lookupKey`) → `SplitTableField`; field with `@lookupKey` → `SplitLookupTableField`. On a result-mapped source it is redundant — the record handoff already opens a new scope — and should produce a build **warning**, not an error. |
| **`@lookupKey`** | Argument values become the derived target table (see [Derived tables](#derived-tables)). Blocks pagination (preserves the N × M result invariant). Without `@splitQuery` → `LookupTableField`; with `@splitQuery` → `SplitLookupTableField`. Orthogonal to scope — see [Scope](#scope). |

---

## Type Classification

| Directive Pattern on Type | `GraphitronType` Variant | Generator Output |
|---|---|---|
| `@table` (no `@node`, no `@discriminate`) | `TableType` | `*Fetchers` class + `*` class |
| `@table` + `@node` | `NodeType` | `*Fetchers` class + `*` class (with Relay ID handling) |
| `@record` | `ResultType`* | `*Fetchers` class only (no SQL scope of its own) |
| `Query` or `Mutation` root type | `RootType` | `*Fetchers` class only |
| Interface with `@table` + `@discriminate` | `TableInterfaceType` | `*Fetchers` class |
| Interface without `@table` (multi-table) | `InterfaceType` | `*Fetchers` class |
| Union type | `UnionType` | `*Fetchers` class |
| `@error` | `ErrorType` | No generation (error mapping config) |
| Input type with `@table` | `TableInputType` | Used in mutation generation |
| Input type with `@table` + `@record` | `InputType`* (via `@record`) | On input types, `@record` dominates `@table`. When both are present, `@table` is ignored and the input classifies as if only `@record` were declared; a build warning names the shadowed directive. Clean up by removing `@table` from the input declaration — the warning disappears naturally. |
| Input type without `@table` | `InputType`* | No generation (developer-provided class) |
| Input type with `@table` used on fields with conflicting return tables | `PojoInputType` (unbound) | No generation — column binding resolved per field-usage |
| Conflicting or unresolvable directives | `UnclassifiedType` | Validation error — build fails |

**Intermediate sealed interfaces** (not shown in the table — grouping nodes in the hierarchy):
- `TableBackedType` — groups `TableType`, `NodeType`, `TableInterfaceType`. Builders switch on this to detect table-mapped types.
- `ResultType` is itself a sealed sub-interface with four concrete variants: `JavaRecordType`, `PojoResultType`, `JooqRecordType`, `JooqTableRecordType` — reflecting how the result class is represented in Java.
- `InputType` is itself a sealed sub-interface with four concrete variants: `JavaRecordInputType`, `PojoInputType`, `JooqRecordInputType`, `JooqTableRecordInputType` — same split by Java representation. `PojoInputType` is also used when an input type appears as an argument on fields with different return tables — it is classified as unbound rather than failing.

---

## Field Classification

Fields are classified separately for root types (Query/Mutation) and nested types.

### Query Fields

| Schema Pattern | `QueryField` Variant | `*Fetchers` Generates |
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
| Return: `@table` type (default) | `QueryTableField` | Full fetcher — condition call + orderBy build + inline DSL chain (`dsl.select(Type.$fields(...)).from(table)...`) |
| Anything else | `UnclassifiedField`** | Validation error — build fails |

### Mutation Fields

| Schema Pattern | `MutationField` Variant | `*Fetchers` Generates |
|---|---|---|
| `@mutation(typeName: INSERT)` | `MutationInsertTableField` | Field method stub |
| `@mutation(typeName: UPDATE)` | `MutationUpdateTableField` | Field method stub |
| `@mutation(typeName: DELETE)` | `MutationDeleteTableField` | Field method stub |
| `@mutation(typeName: UPSERT)` | `MutationUpsertTableField` | Field method stub |
| `@service`, return `@table` type | `MutationServiceTableField` | Async DataLoader fetcher + `rows*()` method |
| `@service`, return non-table type | `MutationServiceRecordField` | Field method stub |
| Neither `@service` nor `@mutation` | `UnclassifiedField`** | Validation error — build fails |
| Both `@service` and `@mutation` | `UnclassifiedField`** | Validation error — build fails |

### Child Fields (on `@table` parent)

#### Scalar / Enum return type

| Schema Pattern | `ChildField` Variant | `*Fetchers` Generates |
|---|---|---|
| `@nodeId(typeName:)` | `NodeIdReferenceField` | Column method in `wiring()` |
| `@nodeId` (no typeName) | `NodeIdField` | Column method in `wiring()` |
| `@reference` on scalar | `ColumnReferenceField` | Column method in `wiring()` |
| `@field(name:)` or matching column name | `ColumnField` | Column method in `wiring()` |

#### Object return type

| Schema Pattern | `ChildField` Variant | `*Fetchers` Generates |
|---|---|---|
| `@externalField` | `ComputedField` | Column method in `wiring()` (developer supplies `Field<?>`) |
| `@tableMethod` | `TableMethodField` | Field method stub |
| `@service`, return `@table` | `ServiceTableField` | Async DataLoader fetcher + `rows*()` method |
| `@service`, return non-table | `ServiceRecordField` | Field method stub |
| Return `@table`, `@splitQuery` + `@lookupKey` | `SplitLookupTableField` | Async DataLoader fetcher + `rows*()` method |
| Return `@table`, `@lookupKey` (no split) | `LookupTableField` | Field method stub (correlated subquery — G5 pending) |
| Return `@table`, `@splitQuery` | `SplitTableField` | Async DataLoader fetcher + `rows*()` method |
| Return `@table` (default) | `TableField` | Field method stub (correlated subquery — G5 pending) |
| Return `@table`+`@discriminate` interface | `TableInterfaceField` | Field method stub |
| Return multi-table interface | `InterfaceField` | Field method stub |
| Return union | `UnionField` | Field method stub |
| Return plain object (no `@table`) | `NestingField` | Field method stub (inherits parent table context) |
| Constructor-mapped field | `ConstructorField` | Field method stub |
| `@reference` to multi-table interface | `MultitableReferenceField` | Field method stub |
| Conflicting directives or `@notGenerated` | `UnclassifiedField`** | Validation error; build fails |

### Child Fields (on `@record` parent)

| Schema Pattern | `ChildField` Variant | `*Fetchers` Generates |
|---|---|---|
| Scalar/enum with `@field` | `PropertyField` | Column method in `wiring()` |
| Return `@table`, `@lookupKey` | `RecordLookupTableField` | Async DataLoader fetcher + `rows*()` method |
| Return `@table` (default) | `RecordTableField` | Async DataLoader fetcher + `rows*()` method |
| Return non-table type | `RecordField` | Column method in `wiring()` |
| `@service`, return `@table` | `ServiceTableField` | Async DataLoader fetcher + `rows*()` method |
| `@service`, return non-table | `ServiceRecordField` | Field method stub |

### Input Fields (on `@table` input parent)

| Schema Pattern | `InputField` Variant | Used for |
|---|---|---|
| `@field(name:)` or matching column name | `InputField.ColumnField` | Maps input argument to a table column |
| `@reference` on input scalar | `InputField.ColumnReferenceField` | Maps input argument to a FK column |
| Return plain object (no `@table`) | `InputField.NestingField` | Expands nested input fields inline against parent table |

`InputField` is a separate top-level sub-hierarchy of `GraphitronField`, alongside `RootField` and `ChildField`. It classifies fields on `TableInputType` for mutation input processing.

**\*\* `UnclassifiedField`** is a direct permit of `GraphitronField`; it is not nested under `QueryField`, `MutationField`, or `ChildField`. It is listed in the tables above for completeness, but structurally it sits at the top level of the sealed hierarchy. `TableTargetField` is an intermediate sealed sub-interface of `ChildField` grouping all 8 SQL-generating child field variants (`TableField`, `SplitTableField`, `LookupTableField`, `SplitLookupTableField`, `TableInterfaceField`, `ServiceTableField`, `RecordTableField`, `RecordLookupTableField`).

### DataLoader-backed field categories

Four categories of DataLoader-backed child field, distinguished by `@splitQuery` and `@lookupKey`:

| Category | DataLoader | Derived tables | `@condition` / non-`@lookupKey` args | Pagination |
|---|---|---|---|---|
| **`QueryLookupTableField`** (root lookup, no `@splitQuery`) | No — synchronous | Derived target only | Allowed — must preserve N × M contract† | Never — result count = M exactly |
| **`LookupTableField`** (`@lookupKey`, no `@splitQuery`, table-mapped parent) | No — correlated subquery | Derived target + correlated parent join | Allowed — must preserve N × M contract† | Never |
| **`SplitTableField`** (`@splitQuery`, no `@lookupKey`) | Yes | Derived source only | Allowed | Allowed |
| **`SplitLookupTableField`** (`@splitQuery` + `@lookupKey`, result-mapped parent) | Yes | Both | Allowed — must preserve N × M contract† | Never — result count = N × M |

† See [Derived tables](#derived-tables) for the contract definition.

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
| SQL-generating marker | `SqlGeneratingField.java` | Orthogonal interface — `returnType()`, `filters()`, `orderBy()`, `pagination()` |
| Method-backed marker | `MethodBackedField.java` | Orthogonal interface — `method()` returning `MethodRef` |
| DataLoader marker | `BatchKeyField.java` | Orthogonal interface — `batchKey()`, `rowsMethodName()` |
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
| Argument extraction | `CallSiteExtraction.java` | `Direct` / `EnumValueOf` / `TextMapLookup` / `ContextArg` / `JooqConvert` |
| Input field hierarchy | `InputField.java` | Sealed interface — `ColumnField` / `ColumnReferenceField` / `NestingField` for mutation inputs |
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

| Component | Output | File |
|---|---|---|
| `TypeFetcherGenerator` | `fetchers.*Fetchers` — wiring + field fetcher/rows methods | `TypeFetcherGenerator.java` |
| `TypeClassGenerator` | `types.*` — `$fields(sel, table, env)` projection method | `TypeClassGenerator.java` |
| `TypeConditionsGenerator` | `conditions.*Conditions` — pure-function WHERE predicates | `TypeConditionsGenerator.java` |
| `GeneratorUtils` | Shared building blocks — `ResolvedTableNames`, key type construction, constants | `GeneratorUtils.java` |
| `ColumnFetcherClassGenerator` | `rewrite.ColumnFetcher<T>` — `LightDataFetcher` for column fields | `util/ColumnFetcherClassGenerator.java` |
| `ConnectionResultClassGenerator` | `rewrite.ConnectionResult` — pagination carrier (Result + pageSize + cursor + columns) | `util/ConnectionResultClassGenerator.java` |
| `ConnectionHelperClassGenerator` | `rewrite.ConnectionHelper` — edges/nodes/pageInfo + cursor encode/decode | `util/ConnectionHelperClassGenerator.java` |
| `GraphitronValuesClassGenerator` | `rewrite.GraphitronValues` — shared constants (`GRAPHITRON_INPUT_IDX`) | `util/GraphitronValuesClassGenerator.java` |

### Directives

- **SDL definitions**: `graphitron-common/src/main/resources/directives.graphqls`
- **Directive reference with examples**: [graphitron-java-codegen README](../../graphitron-codegen-parent/graphitron-java-codegen/README.md)

---

## Known Gaps

### `ConditionFilter` has no builder path

`FieldBuilder` currently produces `GeneratedConditionFilter` entries for filterable arguments, but never produces `ConditionFilter` entries for `@condition` directives on fields. Field-level `@condition` annotations — WHERE predicates applied to the field's own target table — are not yet classified into the rewrite pipeline's filter list.

**Note:** This gap is about the `@condition` directive on *fields* (WHERE predicate). It is distinct from condition joins in `@reference` paths — `{condition: {className, method}}` in a `path:` element — which are fully resolved by the builder into `ConditionJoin` steps.

**Fix**: add `@condition` directive reading to `FieldBuilder.resolveFilters()`. `ConditionFilter` now implements `MethodRef` directly, so the builder constructs it with `(className, methodName, params)` and `callParams()` is derived automatically.

---

**See also:**
- [Rewrite Design Principles](rewrite-design-principles.md) — architectural and technical principles for the rewrite pipeline
- [Rewrite Roadmap](planning/rewrite-roadmap.md) — remaining generator work

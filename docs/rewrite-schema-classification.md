# Rewrite Pipeline: Schema Classification

> **Status: in progress.** The parsing and validation layer described here is complete. The generating stream (code emission) is not yet built. The rewrite pipeline is behind the `rewriteBasedOutput` flag (default `false`) and is not ready for production use.

This document describes the parsing and validation layer of the rewrite pipeline. The companion document [`plan-record-generation.md`](plan-record-generation.md) covers the generating stream, remaining deliverables, and outstanding testing gaps for this layer.

---

## Purpose

The record-based output pipeline eliminates the DTO/TypeMapper layer from generated code. graphql-java's `RuntimeWiring` can resolve fields directly from jOOQ `Record` objects — no intermediate DTOs required. This reduces generated code volume, removes selection-set-per-field mapping boilerplate, and unblocks `@record` output support.

New generators live in `<outputPackage>.rewrite.*` alongside the existing pipeline, controlled by the `rewriteBasedOutput` flag (default `false`).

---

## Architecture

```
TypeDefinitionRegistry
  │
  ▼  GraphitronSchemaBuilder   (schema traversal + jOOQ resolution; zero JavaPoet)
  │
  ▼  GraphitronSchema          (Map<String, GraphitronType> + Map<FieldCoordinates, GraphitronField>)
  │
  ▼  GraphitronSchemaValidator (accumulates errors; never throws; fails build after full scan)
  │
  ▼  Generators                (consume GraphitronSchema; emit TypeSpec → .java files)
```

`GraphitronSchemaBuilder` operates on a `GraphQLSchema` assembled from the `TypeDefinitionRegistry` (same pattern as `SchemaTransformer.assembleSchema()`). It iterates `schema.getAllTypesAsList()` for type classification, then each `GraphQLObjectType`'s field definitions for field classification. Interface and union participant lists are populated in a second enrichment pass.

`GraphitronRewriteGenerator` (in `no.sikt.graphitron.rewrite`) is the entry point: it runs the builder, runs the validator, and dispatches generators in parallel.

---

## Core Types

### `GraphitronSchema`

Top-level container for the parsed schema. A record with two maps:

- `Map<String, GraphitronType> types` — all classified types keyed by name
- `Map<FieldCoordinates, GraphitronField> fields` — all classified fields keyed by `(typeName, fieldName)`

Convenience accessors: `type(typeName)`, `field(typeName, fieldName)`.

### `JooqCatalog`

Lazy wrapper around the jOOQ `Catalog`. Resolves table and column references via reflection so that projects with custom `GeneratorStrategy` implementations work correctly — Java field names are read from the generated `Tables` class, not uppercased from SQL names.

Key methods:
- `findTable(sqlName)` → `Optional<TableEntry>` (`javaFieldName`, `Table<?>`)
- `findColumn(table, sqlColumnName)` → `Optional<ColumnEntry>` (`javaName`, `Field<?>`)
- `findForeignKey(name)` → searches by SQL or Java FK name, case-insensitive

---

## Type Classification (`GraphitronType`)

Every GraphQL named type is classified into one `GraphitronType` variant. The builder reports violations as `UnclassifiedType(reason)`.

| Variant | Trigger | Key fields |
|---|---|---|
| `TableType` | `@table` directive | `table: TableRef`, `node: NodeRef` |
| `ResultType` | `@record` directive | runtime wiring only; no SQL scope |
| `RootType` | `Query` / `Mutation` type | entry point; no directives |
| `TableInterfaceType` | `@table` + `@discriminate` | `discriminatorColumn`, `table: TableRef`, `participants: List<ParticipantRef>` |
| `InterfaceType` | interface, no directives | `participants: List<ParticipantRef>` (each member carries `@table`) |
| `UnionType` | GraphQL union | `participants: List<ParticipantRef>` (all members carry `@table`) |
| `ErrorType` | `@error` directive | `handlers: List<ErrorHandlerSpec>` |
| `InputType` | input object, no directives | — |
| `TableInputType` | input object + `@table` | `table: TableRef`, `fields: List<InputFieldRef>` |
| `UnclassifiedType` | conflicting or unrecognised directives | `reason: String` |

**Type-level directive exclusivity:** `@table`, `@record`, and `@error` are mutually exclusive peers on any type.

### `TableRef`

Two-variant sealed hierarchy for the outcome of matching `@table(name:)` against the jOOQ catalog:

- `ResolvedTable` — `tableName`, `javaFieldName`, `table: Table<?>` (columns, PK, FK metadata)
- `UnresolvedTable` — `tableName` only; validator reports an error

`tableName()` is present on both so callers never need to pattern-match just to retrieve the SQL name.

### `NodeRef`

Whether a `TableType` carries `@node`:

- `NoNode` — no `@node` directive
- `NodeDirective` — `typeId: String` (nullable), `keyColumns: List<KeyColumnRef>`

`KeyColumnRef` is resolved/unresolved: `ResolvedKeyColumn(name, javaName)` or `UnresolvedKeyColumn(name)`.

### `ParticipantRef`

Each implementing or member type of an interface or union:

- `BoundParticipant` — type has `@table`; `typeName`, `table: TableRef`, `discriminatorValue` (from `@discriminator(value:)`, null when absent)
- `UnboundParticipant` — type lacks `@table`; validator reports an error

`BoundParticipant.discriminatorValue` drives the type resolver generator mapping discriminator column values to concrete Java types.

### `InputFieldRef`

Resolution outcome for a single field in a `TableInputType`:

- `TableInputField` — `name`, `typeName`, `nonNull`, `list`, `table: ResolvedTable`, `javaColumnName`, `column: Field<?>`
- `UnresolvedInputField` — `name`, `typeName`, `nonNull`, `list`, `columnName`

---

## Field Classification (`GraphitronField`)

Every field is classified into one of three sealed branches:

```
GraphitronField
├── ChildField       (fields on non-root output types)
├── RootField
│   ├── QueryField   (fields on Query)
│   └── MutationField (fields on Mutation)
├── NotGeneratedField (@notGenerated)
└── UnclassifiedField (reason: String)
```

**Field-level directive exclusivity:**

| Scope | Mutually exclusive directives | Notes |
|---|---|---|
| Child fields | `@service`, `@externalField`, `@tableMethod`, `@nodeId`, `@notGenerated`, `@multitableReference` | `@reference` is a path-annotation and may be combined with any of the above |
| Query fields | `@service`, `@lookupKey`, `@tableMethod` | |
| Mutation fields | `@service`, `@mutation` | |

Violations produce `UnclassifiedField(reason)` naming the conflicting directives.

### Child fields (14 variants)

| Variant | Classification trigger | Key fields |
|---|---|---|
| `ColumnField` | scalar/enum, no path directive | `columnName`, `column: ColumnRef` |
| `ColumnReferenceField` | scalar/enum + `@reference` | `columnName`, `column: ColumnRef`, `referencePath` |
| `NodeIdField` | `@nodeId` (no typeName) | `node: NodeRef` (from parent's `@node`) |
| `NodeIdReferenceField` | `@nodeId(typeName: ...)` | `typeName`, `nodeType: NodeTypeRef`, `returnType`, `referencePath`, `parentTable` |
| `TableField` | return type is `TableType`, no `@tableMethod` | `returnType`, `referencePath`, `condition: FieldConditionRef`, `arguments` |
| `TableMethodField` | `@tableMethod` on child field | developer supplies pre-filtered `Table<?>` |
| `TableInterfaceField` | return type is `TableInterfaceType` | — |
| `InterfaceField` | return type is `InterfaceType` | — |
| `UnionField` | return type is `UnionType` | — |
| `NestingField` | inline nesting, no table join | — |
| `ConstructorField` | return type is `ResultType` child | — |
| `ServiceField` | `@service` on child field | `referencePath`, `arguments` |
| `ComputedField` | `@computed` | `referencePath` |
| `PropertyField` | direct property access | — |
| `MultitableReferenceField` | `@multitableReference` | validation error — not supported in rewrite pipeline |

### Query field classification (9 variants, priority order)

1. `@service` directive → `ServiceQueryField`
2. Field name `_entities` → `EntityQueryField`
3. Field name `node` → `NodeQueryField`
4. Any argument (direct or nested in input types) has `@lookupKey` → `LookupQueryField`
5. `@tableMethod` directive → `TableMethodQueryField`
6. Return type is `TableType` → `TableQueryField`
7. Return type is `TableInterfaceType` → `TableInterfaceQueryField`
8. Return type is `InterfaceType` → `InterfaceQueryField`
9. Return type is `UnionType` → `UnionQueryField`

`hasLookupKeyAnywhere()` checks direct arguments for `@lookupKey`, then recursively checks input type fields. Depth is capped at 10 levels to prevent infinite recursion on circular input type references.

**`LookupQueryField` cardinality rule:** if any argument is a list, the return type must also be a list; if all arguments are scalar, the return must be single; connection return is never valid on a lookup field.

### Mutation field classification (5 variants)

`@service` → `ServiceMutationField`. Otherwise `@mutation(typeName:)` determines: `InsertMutationField`, `UpdateMutationField`, `DeleteMutationField`, or `UpsertMutationField`.

---

## Return Types and Wrappers

### `ReturnTypeRef`

Outcome of resolving a field's return type name against classified types:

- `TableBoundReturnType` — type is a `TableType`; carries resolved `TableRef`
- `OtherReturnType` — type exists but is not table-bound (result type, interface, union)

Both variants carry `returnTypeName` and `wrapper: FieldWrapper`.

### `FieldWrapper`

Describes the cardinality wrapping of a field's element type:

- `Single(nullable)` — one instance or null
- `List(listNullable, itemNullable, defaultOrder, orderByValues)` — SQL-style list
- `Connection(connectionNullable, itemNullable, defaultOrder, orderByValues)` — Relay cursor-paginated list

`Connection` is detected by structural inspection (edges → node chain), not a directive.

`DefaultOrderSpec` holds an `OrderSpec` and direction. `OrderSpec` is itself a sealed hierarchy normalising three sources of sort specification:

| Variant | State |
|---|---|
| `IndexOrder` | unresolved; resolves to `FieldsOrder` via index lookup |
| `FieldsOrder` | explicit column list; always resolved |
| `PrimaryKeyOrder` | resolves to `FieldsOrder` via PK lookup |
| `UnresolvedIndexOrder` | index lookup failed; validation error |
| `UnresolvedPrimaryKeyOrder` | no primary key; validation error |

---

## Argument Resolution (`ArgumentRef`)

Arguments on query and mutation fields are resolved to one of three sealed branches:

```
ArgumentRef
├── InputTypeArg (sealed)
│   ├── TableInputTypeArg    — resolved to TableInputType
│   ├── OrderByArg           — carries @orderBy; sortFieldName, directionFieldName
│   └── PlainInputTypeArg    — unresolved input type arg
├── ScalarArg (sealed)
│   ├── ColumnArg            — resolved to column on return type's table
│   ├── UnboundScalarArg     — column could not be matched
│   └── ParamArg             — passed as direct Java parameter
└── UnclassifiedArg          — unsupported directive; reason: String
```

All variants carry `name`, `typeName`, `nonNull`, `list`.

---

## Reference Path Resolution

`@reference` paths are resolved to `List<ReferencePathElementRef>`. Each step is one of:

| Variant | State |
|---|---|
| `FkRef` | jOOQ FK resolved; no condition |
| `FkWithConditionRef` | FK + condition method both resolved |
| `ConditionOnlyRef` | condition method only (condition lift) |
| `UnresolvedKeyRef` | FK lookup failed |
| `UnresolvedConditionRef` | condition method not found |
| `UnresolvedKeyAndConditionRef` | both failed |

Field-level `@condition` is resolved to `FieldConditionRef`:

- `NoFieldCondition` — no `@condition` on field
- `ResolvedFieldCondition` — `method: MethodRef`, `override`, `contextArgs`
- `UnresolvedFieldCondition` — `qualifiedName`, `override`, `contextArgs`

`MethodRef` carries `qualifiedName`, `returnTypeName`, `params: List<ParamInfo>`. `ParamInfo` records type and parameter name (requires `-parameters` compiler flag on user code).

`NodeTypeRef` resolves `@nodeId(typeName:)` targets:

- `ResolvedNodeType` — type exists as `TableType` with `@node`
- `NoNodeDirectiveType` — type exists but has no `@node`
- `NotFoundNodeType` — type name not in schema

---

## Validator

`GraphitronSchemaValidator` receives a `GraphitronSchema` and accumulates `ValidationError` records — it never throws. Every sealed leaf variant has a dedicated validation branch. `UnclassifiedField` and `UnclassifiedType` report their `reason` as a build error. `ErrorType` is a deliberate no-op (no structural constraints at this layer).

After the full scan, `GraphitronRewriteGenerator` logs all errors with source locations and throws a `RuntimeException` to fail the build.

Additional rules beyond basic variant validation:

- **`LookupQueryField` cardinality** — list/scalar argument cardinality must match return type cardinality
- **Reference path integrity** — each unresolved step in a path is reported
- **Deterministic ordering** — tables without a primary key that appear in paginated queries must have a `@defaultOrder` or `@orderBy` configured

---

## Testing Strategy

### Level 1 — Validator unit tests

`GraphitronField` and `GraphitronType` instances constructed directly. No schema parsing, no DB. One test class per sealed leaf type.

```java
enum Case implements ValidatorCase {
    RESOLVED_IMPLICIT("no @field — column name defaults to field name",
        new ColumnField("title", null, "title", new ResolvedColumn("TITLE", null)),
        List.of()),

    UNRESOLVED_COLUMN("column name could not be matched",
        new ColumnField("title", null, "title", new UnresolvedColumn()),
        List.of("Field 'title': column 'title' could not be resolved"));
}
```

Rule: use `@EnumSource` when constants have behaviour or are reused; use `@CsvSource` when data is purely tabular.

### Level 2 — Classification tests

Each test defines its own minimal inline schema as a text block. One canonical representative per leaf type confirms the classifier produces the correct concrete type.

```java
@Test
void columnField() {
    var result = build("""
        type Customer @table { email: String }
        type Query { customer: Customer }
        """);
    assertThat(result.field("Customer", "email")).isInstanceOf(ColumnField.class);
}
```

### Level 3 — Error message and source location tests

Verifies that error messages are human-readable, contain the right field/type name, and that `SourceLocation` carries correct line and column.

Outstanding testing gaps for this layer are tracked in [`plan-record-generation.md`](plan-record-generation.md).

---

## Key Files

| File | Role |
|---|---|
| `rewrite/GraphitronSchema.java` | Container: `Map<String, GraphitronType>` + `Map<FieldCoordinates, GraphitronField>` |
| `rewrite/GraphitronSchemaBuilder.java` | Parser: `TypeDefinitionRegistry` → `GraphitronSchema` |
| `rewrite/GraphitronSchemaValidator.java` | Validator: accumulates `ValidationError` per sealed variant |
| `rewrite/JooqCatalog.java` | jOOQ reflection wrapper: SQL name → `Table<?>` / `Field<?>` |
| `rewrite/GraphQLRewriteGenerator.java` | Entry point: build → validate → dispatch generators |
| `rewrite/ValidationError.java` | `message`, `location: SourceLocation` |
| `rewrite/field/GraphitronField.java` | Root of 28+ leaf field type hierarchy |
| `rewrite/field/ChildField.java` | Sealed branch: 14 child field variants |
| `rewrite/field/QueryField.java` | Sealed branch: 9 query field variants |
| `rewrite/field/MutationField.java` | Sealed branch: 5 mutation field variants |
| `rewrite/field/ReturnTypeRef.java`, `FieldWrapper.java` | Return type + cardinality |
| `rewrite/field/ArgumentRef.java` | Argument resolution: `InputTypeArg`, `ScalarArg`, `UnclassifiedArg` |
| `rewrite/field/ReferencePathElementRef.java` | 6-variant FK + condition path step |
| `rewrite/field/FieldConditionRef.java` | Field-level `@condition` resolution |
| `rewrite/field/ColumnRef.java`, `NodeTypeRef.java` | Column and node type resolution |
| `rewrite/field/OrderSpec.java`, `FieldWrapper.java` | Sort specification |
| `rewrite/type/GraphitronType.java` | Root of 10-variant type hierarchy |
| `rewrite/type/TableRef.java` | `@table` → jOOQ `Table<?>` resolution |
| `rewrite/type/ParticipantRef.java` | Interface/union member resolution |
| `rewrite/type/NodeRef.java`, `KeyColumnRef.java` | `@node` directive and key columns |
| `rewrite/type/InputFieldRef.java` | `TableInputType` field resolution |
| `graphitron-common/.../GraphitronContext.java` | SPI: `getDslContext`, `getContextArgument`, `getDataLoaderName` |
| `graphitron-common/.../GraphitronFetcherFactory.java` | `field(Field<T>)`, `nestedRecord(alias)`, `nestedResult(alias)` |

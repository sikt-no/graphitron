# Plan: Phase Out DTO Layer — Record-Based Output

## Context

The current code generation pipeline produces output DTOs and TypeMapper classes that convert jOOQ Records into those DTOs. This is unnecessary: graphql-java's `RuntimeWiring` can resolve fields directly from any Java object, including jOOQ `Record`. Eliminating the DTO/TypeMapper layer reduces generated code volume, removes the selection-set-per-field mapping boilerplate, and unblocks future features (e.g. `@record` output support).

The change is behind a `recordBasedOutput` feature flag (default `false`) and generates new artefacts into a separate package (`<outputPackage>.record.*`) so old and new code can coexist. The existing generators continue to run unchanged — the flag adds new generators alongside.

### Existing codebase facts

The main orchestrator is `GraphQLGenerator.getGenerators()` which runs, in order: 5 DTO generators, `DBClassGenerator` (→ `{TypeName}DBQueries` in package `queries`), transformer + mapper generators, exception generators, `OperationClassGenerator`, `TypeResolverClassGenerator`, `WiringClassGenerator`. All live in `graphitron-codegen-parent/graphitron-java-codegen`.

Generator base class: `AbstractSchemaClassGenerator<T>` — takes a `ProcessedSchema`, produces one `TypeSpec` per `generate(T target)` call.

Existing wiring pattern: data fetcher generators register wiring info → `WiringClassGenerator` assembles in a final pass. The new generators follow the same two-phase pattern: `FieldsClassGenerator` generates `<TypeName>Fields` classes with embedded `wiring()` methods → `GraphitronWiringClassGenerator` aggregates them.

**`GraphitronContext`** is at `no.sikt.graphql.GraphitronContext`:
- `getDslContext(DataFetchingEnvironment env)` — provides jOOQ `DSLContext`
- `getContextArgument(DataFetchingEnvironment env, String name)` — reads named values from `GraphQlContext`

**`DefaultGraphitronContext`** is at `no.sikt.graphql.DefaultGraphitronContext`.

### Schema traversal

`GraphitronSchemaBuilder` operates on a `GraphQLSchema` (not `TypeDefinitionRegistry` AST). The schema is assembled using the same pattern as `SchemaTransformer.assembleSchema()` in `graphitron-schema-transform`:

```java
RuntimeWiring runtimeWiring = EchoingWiringFactory.newEchoingWiring(wiring -> {
    typeDefinitionRegistry.scalars().forEach((name, v) -> {
        if (!ScalarInfo.isGraphqlSpecifiedScalar(name)) wiring.scalar(fakeScalar(name));
    });
});
GraphQLSchema schema = new SchemaGenerator().makeExecutableSchema(typeDefinitionRegistry, runtimeWiring);
```

`GraphitronSchemaBuilder` then iterates `schema.getAllTypesAsList()` to classify types, and a second pass over each `GraphQLObjectType`'s `getFieldDefinitions()` to classify fields. Interface and union participant lists are populated in a separate enrichment pass after the initial type classification.

---

## Deliverables

Each deliverable is a self-contained, reviewable change behind the `recordBasedOutput` flag. No deliverable breaks existing behaviour.

| # | Deliverable | Key output |
|---|---|---|
| 1 | `GraphitronField` skeleton | Sealed interface hierarchy compiling; all field types modelled |
| 2 | Infrastructure | `GraphitronFetcherFactory`, `getTenantId()`, feature flag |
| **Parsing stream** | `GraphitronSchemaBuilder` — schema → `GraphitronField` | Independent of generating stream |
| P1 ✓ | Scalar parsing | `ColumnField`, `ColumnReferenceField`, `NodeIdField`, `NodeIdReferenceField`, `NotGeneratedField`, `ErrorType` — done |
| P2 ✓ | Table child parsing | `TableField`, `TableMethodField`, `NestingField` |
| P3 ✓ | Remaining child parsing | `ComputedField`, `PropertyField`, `TableInterfaceField`, `InterfaceField`, `UnionField`, `ServiceField` |
| P4 ✓ | Field arguments + input types | `InputType` in `GraphitronType`; argument list on field records |
| P5 ✓ | Root field parsing | `LookupQueryField`, `TableQueryField`, `TableMethodQueryField`, `NodeQueryField`, `EntityQueryField`, `TableInterfaceQueryField`, `InterfaceQueryField`, `UnionQueryField`, `ServiceQueryField`, all `MutationField` variants |
| P4 is a prerequisite for P5. Field arguments must be classified before root fields can be fully specified, because root-field records carry their argument lists. Input types must be in `GraphitronType` before the validator can check argument type references. P4 does not depend on P2 or P3. |
| **Generating stream** | `FieldsCodeGenerator` — `GraphitronField` → Java | Independent of parsing stream |
| G1 | Scalar generating | `ColumnField`, `ColumnReferenceField` → `wiring()` + `fields()` |
| G2 | Table child generating | `TableField` (inline + `@splitQuery`) → field methods + DataLoader |
| G3 | Remaining child generating | `NodeIdField`, `NodeIdReferenceField`, `ComputedField`, `PropertyField`, `TableInterfaceField`, `InterfaceField`, `UnionField`, `NestingField`, `TableMethodField`, `ServiceField` → wiring entries |
| G4 | Root field generating | `TableQueryField`, `LookupQueryField`, `TableMethodQueryField`, `NodeQueryField`, `EntityQueryField`, `TableInterfaceQueryField`, `InterfaceQueryField`, `UnionQueryField`, `ServiceQueryField` → DataFetcher methods |
| **Integration** | Wire streams together | End-to-end per field category |
| I1 | `FieldsClassGenerator` + `GraphitronWiringClassGenerator` | First working end-to-end output |
| I2 | `@condition` support | `ConditionWrapperClassGenerator` |
| I3 | `@service` root fields | `ServiceWrapperClassGenerator` |
| I4 | Ordering | `@defaultOrder` and `@orderBy` |

---

## Deliverable 1: `GraphitronField` skeleton

The `GraphitronField` sealed interface hierarchy is the Java materialisation of the field taxonomy. It is the foundation everything else targets — the spec builder populates it, the code generators consume it.

**Packages:** `no.sikt.graphitron.record.field` and `no.sikt.graphitron.record.type` (in `graphitron-java-codegen`)

### `GraphitronType`

Every GraphQL named type is classified into a `GraphitronType`. This is where `@table` directive mappings are validated — jOOQ table class exists, discriminator columns are present, etc. — and it is the authoritative source of source context for all fields on that type.

```java
sealed interface GraphitronType
    permits TableType, ResultType, RootType,
            TableInterfaceType, InterfaceType, UnionType, ErrorType {
    String name();
    SourceLocation location();
}

// @table — full SQL generation; @table directive mapping validated here.
// The SQL table name is accessible via table.tableName() on both ResolvedTable and UnresolvedTable.
// node captures whether @node is present: NoNode when absent, NodeDirective when present.
record TableType(
    String name,
    SourceLocation location,
    TableRef table,    // resolved jOOQ table, or unresolved sentinel; carries SQL name
    NodeRef node       // @node directive tracking; NoNode when absent
) implements GraphitronType {}

// @record — runtime wiring only, no SQL until a new scope starts
record ResultType(String name, SourceLocation location)
    implements GraphitronType {}

// Query / Mutation — unmapped entry points
record RootType(String name, SourceLocation location)
    implements GraphitronType {}

// interface with @table + @discriminate; implementing types have @table + @discriminator
// SQL table name is accessible via table.tableName().
record TableInterfaceType(
    String name,
    SourceLocation location,
    String discriminatorColumn,
    TableRef table,                    // resolved jOOQ table, or unresolved sentinel; carries SQL name
    List<ParticipantRef> participants  // one per implementing type
) implements GraphitronType {}

// interface with no directives; implementing types have @table
record InterfaceType(
    String name,
    SourceLocation location,
    List<ParticipantRef> participants  // one per implementing type
) implements GraphitronType {}

// union; all member types have @table
record UnionType(
    String name,
    SourceLocation location,
    List<ParticipantRef> participants  // one per member type
) implements GraphitronType {}

// @error — maps Java exceptions to GraphQL error responses
record ErrorType(
    String name,
    SourceLocation location,
    List<ErrorHandlerSpec> handlers
) implements GraphitronType {}
```

### `TableRef`

`TableType` and `TableInterfaceType` use a sealed hierarchy to represent the outcome of resolving the `@table` directive value against the jOOQ catalog:

```java
sealed interface TableRef permits ResolvedTable, UnresolvedTable {
    /** The raw SQL table name from the @table directive (e.g. "film"). Always available. */
    String tableName();
}

// jOOQ table class found in catalog
record ResolvedTable(
    String tableName,      // raw SQL name from @table directive — e.g. "film"
    String javaFieldName,  // field name in generated Tables class — e.g. "FILM"
    Table<?> table         // jOOQ instance; provides column and FK metadata
) implements TableRef {}

// SQL name could not be matched to any class in the jOOQ catalog
record UnresolvedTable(String tableName) implements TableRef {}
```

The validator reports an error for `UnresolvedTable`; the code generator only consumes `ResolvedTable`.

### `GraphitronField`

```java
sealed interface GraphitronField
    permits RootField, ChildField, NotGeneratedField, UnclassifiedField {
    String name();
    SourceLocation location();
}

sealed interface RootField extends GraphitronField
    permits QueryField, MutationField {}

sealed interface QueryField extends RootField
    permits LookupQueryField, TableQueryField, TableMethodQueryField,
            NodeQueryField, EntityQueryField,
            TableInterfaceQueryField, InterfaceQueryField, UnionQueryField,
            ServiceQueryField {}

sealed interface MutationField extends RootField
    permits InsertMutationField, UpdateMutationField, DeleteMutationField,
            UpsertMutationField, ServiceMutationField {}

sealed interface ChildField extends GraphitronField
    permits ColumnField, ColumnReferenceField,
            NodeIdField, NodeIdReferenceField,
            TableField, TableMethodField,
            TableInterfaceField, InterfaceField, UnionField,
            NestingField, ConstructorField,
            ServiceField, ComputedField, PropertyField {}
```

Each leaf type is a Java `record` carrying the properties relevant to code generation (table class, FK key constant, condition wrapper class, etc.). Source context for a `ChildField` is derived from `schema.type(parentTypeName)` — a `TableType` means table-mapped, a `ResultType` means result-mapped.

**`ConstructorField` is planned but not yet implemented.** Until its directive and generation logic are defined, `GraphitronSchemaBuilder` must classify any field that would otherwise match `ConstructorField` as `UnclassifiedField` — which the validator rejects with a clear error. Recognising a type in the hierarchy without a generation path is a hidden gap; `UnclassifiedField` makes the gap visible and enforced. This note will be removed when the deliverable for `ConstructorField` is added to the sequence.

### `ColumnRef`

`ColumnField` and `ColumnReferenceField` use a sealed hierarchy to represent the outcome of resolving the field's column name against the jOOQ table:

```java
sealed interface ColumnRef permits ResolvedColumn, UnresolvedColumn {}

// column found in jOOQ table
record ResolvedColumn(
    String javaName,   // Java identifier in the generated table class — e.g. "FILM_ID", "TITLE".
                       // Obtained via reflection on the table instance fields (same pattern as
                       // TableEntry.javaFieldName), NOT by uppercasing the SQL name. Uppercasing
                       // is only correct for the default jOOQ naming strategy; a custom
                       // GeneratorStrategy can produce any identifier, so reflection is required.
    Field<?> column    // jOOQ instance; used for type inspection at code-gen time
) implements ColumnRef {}

// column name could not be matched; columnName is on the parent record
record UnresolvedColumn() implements ColumnRef {}
```

### `ReferencePathElementRef`

`TableField`, `ColumnReferenceField`, `NodeIdReferenceField`, `TableMethodField`, `ServiceField`, and `ComputedField` each carry a `List<ReferencePathElementRef>` representing the `@reference(path:)` join steps. The sealed hierarchy distinguishes six states:

```java
sealed interface ReferencePathElementRef
    permits FkRef, FkWithConditionRef, ConditionOnlyRef,
            UnresolvedKeyRef, UnresolvedConditionRef, UnresolvedKeyAndConditionRef {}

record FkRef(ForeignKey<?, ?> key) implements ReferencePathElementRef {}
record FkWithConditionRef(ForeignKey<?, ?> key, MethodRef condition) implements ReferencePathElementRef {}
record ConditionOnlyRef(MethodRef condition) implements ReferencePathElementRef {}
record UnresolvedKeyRef(String keyName) implements ReferencePathElementRef {}
record UnresolvedConditionRef(String qualifiedName) implements ReferencePathElementRef {}
record UnresolvedKeyAndConditionRef(String keyName, String conditionName) implements ReferencePathElementRef {}
```

The validator reports errors for the three `Unresolved*` variants; the code generator only consumes the three resolved variants.

### `ReturnTypeRef`

Every non-scalar field has a return type name that must be resolved against the classified schema. The outcome is captured in `ReturnTypeRef`, which also embeds the `FieldWrapper` describing how the element type is wrapped. Together they form a complete description of a field's declared GraphQL return type — e.g. `[Film!]!` is `TableBoundReturnType("Film", filmTable, List(false, false, ...))` and `Film` is `TableBoundReturnType("Film", filmTable, Single(true))`.

```java
sealed interface ReturnTypeRef permits TableBoundReturnType, OtherReturnType, UnresolvedReturnType {
    String returnTypeName();

    /** The wrapper around the element type. */
    FieldWrapper wrapper();

    /** Type exists and is a TableType. table carries the @table resolution outcome. */
    record TableBoundReturnType(String returnTypeName, TableRef table, FieldWrapper wrapper) implements ReturnTypeRef {}

    /** Type exists but is not a table-backed type (result type, interface, union). */
    record OtherReturnType(String returnTypeName, FieldWrapper wrapper) implements ReturnTypeRef {}

    /** Type name does not exist in the schema. Validator reports an error. */
    record UnresolvedReturnType(String returnTypeName, FieldWrapper wrapper) implements ReturnTypeRef {}
}
```

### `FieldWrapper`

`FieldWrapper` models how a field's element type is wrapped in the GraphQL type system. It is always embedded inside `ReturnTypeRef` — callers access it via `returnType.wrapper()`.

All fields in GraphQL can be list-wrapped and non-null-wrapped. Connection is a wrapper abstraction — semantically equivalent to a list wrapper but using the Relay `edges.node` structure rather than a bare `[T]`. Both list and connection carry optional ordering configuration.

Connection detection is **structural**, not name-based: the return type is checked for an `edges` field whose element type has a `node` field. `connectionElementTypeName()` navigates `edges.node` to find the actual element type — the `returnTypeName` on `TableBoundReturnType` is always the element type, never the connection wrapper type.

```java
sealed interface FieldWrapper permits Single, List, Connection {

    /** Returns one instance (or null). */
    record Single(boolean nullable) implements FieldWrapper {}

    /** Returns zero-or-more instances. May carry a default sort order; query fields also
     *  carry @orderBy enum value specs (empty list for child fields). */
    record List(
        boolean listNullable,
        boolean itemNullable,
        DefaultOrderSpec defaultOrder,                       // null when @defaultOrder is absent
        java.util.List<OrderByEnumValueSpec> orderByValues  // empty for child fields today
    ) implements FieldWrapper {}

    /** Relay cursor-based paginated list. Cursor/pagination config TBD — this variant will
     *  gain additional components when connection support is implemented. Ordering rules
     *  follow List. */
    record Connection(
        boolean connectionNullable,
        boolean itemNullable,
        DefaultOrderSpec defaultOrder,
        java.util.List<OrderByEnumValueSpec> orderByValues
        // + cursor/pagination config (TBD)
    ) implements FieldWrapper {}
}
```

The three variants generate structurally different code:

| Variant | Child: SQL expression | Root: fetch call |
|---|---|---|
| `Single` | `DSL.field(DSL.select(...))` → `Field<Record>` | `fetchOne()` |
| `List` | `DSL.multiset(DSL.select(...))` → `Field<Result<Record>>` | `fetch()` |
| `Connection` | Cursor-filtered SELECT + optional totalCount window | Relay connection wrapper |

Fields that carry `returnType` but are always single by specification — `LookupQueryField`, `NodeQueryField`, `EntityQueryField`, `NestingField`, `ConstructorField`, `ServiceField`, `ComputedField`, all `MutationField` variants — still embed a `FieldWrapper` inside their `ReturnTypeRef` (always `Single`). Fields that can be single, list, or connection — `TableField`, `TableMethodField`, `TableInterfaceField`, `InterfaceField`, `UnionField`, and their query equivalents — derive the wrapper from the GraphQL field definition at parse time.

`FieldConditionRef` (field-level `@condition`) is orthogonal to the wrapper. `@splitQuery` on `TableField` is also orthogonal — it changes whether a DataLoader is used but not the SQL shape. Both remain separate properties on their respective records.

### `GraphitronSchema` container

`GraphitronSchema` holds both maps. Types are keyed by name (the natural identifier in GraphQL); fields by `FieldCoordinates` — the GraphQL-spec-standardised `(typeName, fieldName)` pair provided by GraphQL Java (`graphql.schema.FieldCoordinates`), the same type used as the key in `GraphQLCodeRegistry`. The two field maps are therefore parallel by construction.

```java
record GraphitronSchema(
    Map<String, GraphitronType> types,
    Map<FieldCoordinates, GraphitronField> fields
) {
    GraphitronType type(String name) { return types.get(name); }

    GraphitronField field(String typeName, String fieldName) {
        return fields.get(FieldCoordinates.coordinates(typeName, fieldName));
    }
}
```

`GraphitronSchemaBuilder` populates both maps during schema traversal, using a `JooqCatalog` wrapper to resolve table names.

### `JooqCatalog`

A thin wrapper around jOOQ's `Catalog`. Loads the catalog once via reflection (`DefaultCatalog.DEFAULT_CATALOG`) and provides lazy lookups — no pre-built maps. Each method queries the catalog on demand.

```java
class JooqCatalog {
    private final Catalog catalog;

    JooqCatalog(String generatedJooqPackage) {
        this.catalog = loadDefaultCatalog(generatedJooqPackage);
    }

    /** Find a table by its SQL name. Returns both the Table<?> instance and its Java field name in Tables. */
    Optional<TableEntry> findTable(String sqlName) {
        return catalog.schemaStream()
            .flatMap(schema -> getTablesClass(schema).stream())
            .flatMap(cls -> Arrays.stream(cls.getFields()))
            .filter(f -> Table.class.isAssignableFrom(f.getType()))
            .map(f -> new TableEntry(f.getName(), (Table<?>) getFieldValue(f)))
            .filter(e -> e.table().getName().equalsIgnoreCase(sqlName))
            .findFirst();
    }

    /** Find a foreign key by its SQL name. */
    Optional<ForeignKey<?, ?>> findForeignKey(String sqlName) { ... }

    /**
     * Find a column in a table by its SQL name. Returns both the jOOQ Field instance and its
     * Java identifier name in the generated table class (e.g. "FILM_ID", "TITLE").
     *
     * Uses reflection on the table class's public instance fields — NOT toUpperCase() on the SQL
     * name. The default jOOQ naming strategy happens to uppercase column names, so toUpperCase()
     * would work there, but a custom GeneratorStrategy can produce any identifier. Reflecting the
     * real field name (the same pattern used by findTable for Tables class fields) is required to
     * honour custom strategies.
     */
    Optional<ColumnEntry> findColumn(Table<?> table, String sqlColumnName) {
        return Arrays.stream(table.getClass().getFields())
            .filter(f -> Field.class.isAssignableFrom(f.getType()))
            .map(f -> new ColumnEntry(f.getName(), (Field<?>) instanceFieldValue(f, table)))
            .filter(e -> sqlColumnName.equalsIgnoreCase(e.column().getName()))
            .findFirst();
    }
}

record TableEntry(String javaFieldName, Table<?> table) {}
record ColumnEntry(String javaName, Field<?> column) {}
```

The `@table` directive carries the SQL name — what the schema author writes. The Java field name (e.g. `"FILM"`) is what appears in generated code. Both differ and both are needed, so `TableEntry` returns them together. `GraphitronSchemaBuilder` then stores both on `TableType` using the `TableRef` sealed hierarchy:

```java
String sqlName = getDirectiveArg(objectType, "table", "name");
Optional<TableEntry> entry = jooqCatalog.findTable(sqlName);
TableRef tableRef = entry
    .<TableRef>map(e -> new ResolvedTable(sqlName, e.javaFieldName(), e.table()))
    .orElseGet(() -> new UnresolvedTable(sqlName));

types.put(typeName, new TableType(name, location, tableRef, node));
```

`tableName` is carried on both `ResolvedTable` and `UnresolvedTable` so callers can always retrieve it via `TableRef.tableName()` without having to reach into the parent type record. When `ResolvedTable` it provides columns, primary key, and FK metadata — used directly by FK auto-inference. When `UnresolvedTable`, a downstream validation pass reports the unresolved name.

The generator drives iteration from `TypeDefinitionRegistry` (types with `@table`) and looks up by coordinates:

```java
typeDef.getFieldDefinitions().forEach(fieldDef -> {
    GraphitronField gField = schema.field(typeDef.getName(), fieldDef.getName());
    // switch on gField
});
```

This deliverable is complete when the hierarchies and `GraphitronSchema` compile, and a simple pattern-match over all permits exhaustively covers every leaf of both `GraphitronType` and `GraphitronField`.

### Explicitly unsupported: `@multitableReference` / `MultitableReferenceField`

`MultitableReferenceField` exists in the sealed hierarchy as a permanent non-generated leaf. It is classified when a field carries the `@multitableReference` directive, and the validator always reports an error for it — record-based output does not and will not support this directive. This is a deliberate design boundary, not an omission: `@multitableReference` relies on runtime DTO mapping that the record-based pipeline eliminates. Users must convert affected fields to `@service` or an equivalent pattern before enabling `recordBasedOutput`.

Once D1 is merged, two streams open up that are fully independent of each other:

- **Parsing stream** (`GraphitronSchemaBuilder`): reads a `GraphQLSchema` and produces `GraphitronField` instances. Zero JavaPoet. Tests assert which concrete type is produced and that its properties are populated correctly — one test per leaf type minimum.
- **Generating stream** (`FieldsCodeGenerator`): consumes hand-crafted `GraphitronField` instances and produces `TypeSpec` via JavaPoet. Zero schema logic. Tests use approval files — one approved file per leaf type minimum.

Neither stream depends on the other. Integration (`FieldsClassGenerator`) connects them.

---

## Parsing stream — P4: Field arguments and input types

**Prerequisite:** P2 and P3 do not need to be done first. P4 must be done before P5.

Field arguments on query and mutation fields are what drive filter conditions, pagination, lookup keys, and service context bindings. Until the builder captures them, root-field records carry no useful data for code generation. Input types are the GraphQL types of those arguments — they must appear in `GraphitronType` so the validator can check references and generators can inspect their fields.

### `InputType` in `GraphitronType`

`InputObjectTypeDefinition` is currently discarded in `classifyType()`. Add a new variant:

```java
record InputType(
    String name,
    SourceLocation location,
    List<InputFieldSpec> fields
) implements GraphitronType {}
```

Each `InputFieldSpec` captures the field name, its GraphQL type string, and directive markers that generators need:

```java
record InputFieldSpec(
    String name,
    String typeName,           // base type name (unwrapped)
    boolean nonNull,
    boolean list,
    boolean orderBy            // @orderBy present on this field
) {}
```

> **`@lookupKey` is not stored per field or per argument.** Its presence anywhere in the argument tree (direct args or nested input fields) classifies the *root field* as a `LookupQueryField`. Once that classification is made, `@lookupKey` carries no further per-argument semantic — all arguments on a lookup field participate equally in lookup semantics. Storing it in `InputFieldSpec` or `ArgumentSpec` would mislead generator authors into treating `@lookupKey` args differently from non-`@lookupKey` args, which is wrong.

`@field(name:, javaName:)` on input fields is also captured here (column name override, Java name override) when present. Directives not needed for generation are ignored.

### Argument list on field records

Every field type that can carry GraphQL arguments gains a `List<ArgumentSpec> arguments` property. This applies to all root fields (P5) and to child fields that accept arguments (primarily `TableField` for pagination/ordering, `ServiceField` for context args).

```java
record ArgumentSpec(
    String name,
    String typeName,       // base type name (unwrapped)
    boolean nonNull,
    boolean list,
    boolean orderBy,       // @orderBy present
    boolean conditionArg   // @condition present on this argument
) {}
```

> `lookupKey` is absent — see note under `InputFieldSpec` above.

`contextArguments` from `@service` and `@tableMethod` directives (a `[String!]` list of `GraphQLContext` key names) are captured separately as `List<String> contextArguments` on `ServiceQueryField`, `ServiceMutationField`, `ServiceField`, `TableMethodQueryField`, and `TableMethodField`.

### Validation

The validator gains checks for:
- Every `ArgumentSpec.typeName` that is not a built-in scalar must resolve to a type in `GraphitronSchema.types()` (either an `InputType`, scalar, or enum).
- Every `InputFieldSpec.typeName` with the same constraint.
- `@orderBy` must not appear on non-scalar argument/field types.

### What P4 does not include

- Condition resolution via reflection (`@condition` on `ARGUMENT_DEFINITION`) — still deferred to P3 of the original deliverable sequence (the "P3" in the `resolveConditionRef` comment in `GraphitronSchemaBuilder`).
- Default value capture — not needed for current generators.
- Relay pagination argument names (`first`, `after`, `last`, `before`) are captured as ordinary `ArgumentSpec` entries; the `FieldWrapper.Connection` wrapper identifies the field as paginated, not the presence of specific argument names.

---

## Parsing stream — P5: Root field parsing

**Prerequisite:** P4 must be done first.

Root fields are classified into the `QueryField`/`MutationField` sealed hierarchies by `classifyQueryField()` and `classifyMutationField()` in `GraphitronSchemaBuilder`.

### Classification priority (Query fields)

1. `@service` directive → `ServiceQueryField`
2. Field name `_entities` → `EntityQueryField`
3. Field name `node` → `NodeQueryField`
4. Any arg (direct or nested in input types) has `@lookupKey` → `LookupQueryField`
5. `@tableMethod` directive → `TableMethodQueryField`
6. Return type is `TableType` → `TableQueryField`
7. Return type is `TableInterfaceType` → `TableInterfaceQueryField`
8. Return type is `InterfaceType` → `InterfaceQueryField`
9. Return type is `UnionType` → `UnionQueryField`

`hasLookupKeyAnywhere()` checks direct args for `@lookupKey`, then recursively checks input type fields (depth-guarded at 10 levels). This allows `@lookupKey` to appear on nested input object fields to classify the root Query field.

### `@lookupKey` semantics

`@lookupKey` is a **field-level classifier only**. Its presence on any argument anywhere in the arg tree marks the whole Query field as a `LookupQueryField`. Beyond classification, `@lookupKey` carries no per-argument semantic — all arguments on a lookup field participate equally in lookup semantics (list args positionally correlated, scalar args broadcast). This is intentional: having a non-`@lookupKey` arg with the correct dimension would be semantically identical to a `@lookupKey` arg, so the distinction is meaningless and storing it per-arg would mislead generator authors.

### `LookupQueryField` validation

Three constraints are enforced:
- **Single return type**: lookup fields must return a single object (`FieldWrapper.Single`), not a list or connection. The result list length equals the input key list length — the DataLoader correlation depends on this.
- **No `@orderBy` args**: ordering is meaningless when returning a single object per key.
- **No `@condition` args**: filter conditions are incompatible with lookup semantics.

### Mutation classification

`@service` directive → `ServiceMutationField`. Otherwise, `@mutation(typeName:)` determines the type: `INSERT`, `UPDATE`, `DELETE`, or `UPSERT`.

---

## Deliverable 2: Infrastructure

### `GraphitronFetcherFactory`

**File:** `graphitron-common/src/main/java/no/sikt/graphql/GraphitronFetcherFactory.java`

```java
public class GraphitronFetcherFactory {

    /** Resolves a scalar column directly from the jOOQ Record in source position. */
    public static <T> LightDataFetcher<T> field(Field<T> jooqField) {
        return env -> ((Record) env.getSource()).get(jooqField);
    }

    /** Resolves an inline nested single object (many-to-one) from the source Record. */
    public static LightDataFetcher<Record> nestedRecord(String alias) {
        return env -> ((Record) env.getSource()).get(alias, Record.class);
    }

    /** Resolves an inline nested list (one-to-many) from the source Record. */
    public static LightDataFetcher<Result<Record>> nestedResult(String alias) {
        return env -> ((Record) env.getSource()).get(alias, Result.class);
    }
}
```

### `GraphitronContext` — add `getTenantId()`

```java
@NotNull Optional<String> getTenantId(DataFetchingEnvironment env);
```

`DefaultGraphitronContext` returns `Optional.empty()`. Used in `loaderName()` for multi-tenant DataLoader key isolation.

### Feature flag

**`GeneratorConfig.java`**: `private static boolean recordBasedOutput = false;` + getter/setter.
**Maven plugin mojo**: `@Parameter(property = "graphitron.recordBasedOutput", defaultValue = "false") protected boolean recordBasedOutput;`

---

## Deliverable 3: Scalar fields end-to-end

Introduces `GraphitronSchemaBuilder`, `FieldsCodeGenerator`, `FieldsClassGenerator`, and `GraphitronWiringClassGenerator`. At this stage only `ColumnField` and simple root `TableQueryField` are handled — enough to produce a working end-to-end pipeline for types with no nested fields.

### Generated `CustomerFields` (scalar-only, non-root)

```java
public class CustomerFields {

    public static TypeRuntimeWiring.Builder wiring() {
        return TypeRuntimeWiring.newTypeWiring("Customer")
            .dataFetcher("id",    GraphitronFetcherFactory.field(CUSTOMER.CUSTOMER_ID))
            .dataFetcher("email", GraphitronFetcherFactory.field(CUSTOMER.EMAIL_ADDRESS));
    }

    public static List<Field<?>> fields(Customer customer, DataFetchingFieldSelectionSet sel) {
        var fields = new ArrayList<Field<?>>();
        fields.add(customer.CUSTOMER_ID);
        fields.add(customer.EMAIL_ADDRESS);
        return fields;
    }
}
```

### Generated `QueryFields` (root, scalar return)

```java
public class QueryFields {

    public static TypeRuntimeWiring.Builder wiring() {
        return TypeRuntimeWiring.newTypeWiring("Query")
            .dataFetcher("customer", QueryFields::customer);
    }

    public static CompletableFuture<Record> customer(DataFetchingEnvironment env) {
        GraphitronContext ctx = env.getGraphQlContext().get("graphitronContext");
        String id = env.getArgument("id");
        return CompletableFuture.supplyAsync(() -> {
            var _a = CUSTOMER.as("customer_hash");
            return ctx.getDslContext(env)
                .select(CustomerFields.fields(_a, env.getSelectionSet()))
                .from(_a)
                .where(_a.CUSTOMER_ID.eq(UInteger.valueOf(id)))
                .fetchOne();
        });
    }
}
```

### Generated `GraphitronWiring`

```java
public class GraphitronWiring {
    public static RuntimeWiring.Builder getRuntimeWiringBuilder() {
        return RuntimeWiring.newRuntimeWiring()
            .type(QueryFields.wiring())
            .type(CustomerFields.wiring());
    }
}
```

---

## Deliverable 4: Inline `TableField`

Extends `FieldsCodeGenerator` with `TableField` in table-mapped source context (no `@splitQuery`). Introduces the static field method pattern and the `@defer` check-then-fetch resolver.

### Static field methods

Each inline `TableField` generates a `public static` method on `CustomerFields`, analogous to jOOQ's static column fields. The method takes the parent table alias (needed for correlated joins) and a `SelectedField` (carries both the nested selection set and any arguments).

The `FieldWrapper` variant on the `TableField` spec (accessed via `returnType.wrapper()`) drives the return type and SQL expression:
- `FieldWrapper.List` → `Field<Result<Record>>` via `DSL.multiset(...)`
- `FieldWrapper.Single` → `Field<Record>` via `DSL.field(DSL.select(...))`
- `FieldWrapper.Connection` → deferred to connection deliverable

```java
public static Field<Result<Record>> payments(Customer customer, SelectedField field) {
    return DSL.multiset(
        DSL.select(PaymentFields.fields(PAYMENT, field.getSelectionSet()))
            .from(PAYMENT)
            .join(customer).onKey(Keys.PAYMENT__PAYMENT_CUSTOMER_ID_FKEY)
    ).as("payments");
}

public static Field<Record> address(Customer customer, SelectedField field) {
    return DSL.field(
        DSL.select(AddressFields.fields(ADDRESS, field.getSelectionSet()))
            .from(ADDRESS)
            .join(customer).onKey(Keys.CUSTOMER__CUSTOMER_ADDRESS_ID_FKEY)
    ).as("address");
}
```

`fields()` adds them conditionally:

```java
public static List<Field<?>> fields(Customer customer, DataFetchingFieldSelectionSet sel) {
    var fields = new ArrayList<Field<?>>();
    fields.add(customer.CUSTOMER_ID);
    fields.add(customer.EMAIL_ADDRESS);
    sel.getFields("payments").forEach(f -> fields.add(payments(customer, f)));
    sel.getFields("address").forEach(f -> fields.add(address(customer, f)));
    return fields;
}
```

### `@defer` check-then-fetch wiring

Every inline `TableField` generates a `private static final` typed field constant used as the key both when embedding the subquery in `fields()` and when reading back the result in `wiring()`. This constant is the alias: it is type-safe, collision-free, and the same value serves both roles.

```java
// Generated constant — type-safe key for both embed and read-back
private static final Field<Result<Record>> CUSTOMER_PAYMENTS_FIELD =
    DSL.field("payments", Result.class);

private static final Field<Record> CUSTOMER_ADDRESS_FIELD =
    DSL.field("address", Record.class);
```

`fields()` embeds the subquery under the constant's alias:

```java
public static List<Field<?>> fields(Customer customer, DataFetchingFieldSelectionSet sel) {
    var fields = new ArrayList<Field<?>>();
    fields.add(customer.CUSTOMER_ID);
    fields.add(customer.EMAIL_ADDRESS);
    sel.getFields("payments").forEach(f -> fields.add(payments(customer, f).as(CUSTOMER_PAYMENTS_FIELD)));
    sel.getFields("address").forEach(f ->  fields.add(address(customer, f).as(CUSTOMER_ADDRESS_FIELD)));
    return fields;
}
```

`wiring()` reads back using the same constant — never a raw string:

```java
public static TypeRuntimeWiring.Builder wiring() {
    return TypeRuntimeWiring.newTypeWiring("Customer")
        .dataFetcher("id",       GraphitronFetcherFactory.field(CUSTOMER.CUSTOMER_ID))
        .dataFetcher("email",    GraphitronFetcherFactory.field(CUSTOMER.EMAIL_ADDRESS))
        .dataFetcher("payments", env -> {
            Record source = env.getSource();
            Result<Record> result = source.get(CUSTOMER_PAYMENTS_FIELD);
            if (result != null) return result;
            GraphitronContext ctx = env.getGraphQlContext().get("graphitronContext");
            return ctx.getDslContext(env)
                .select(PaymentFields.fields(PAYMENT, env.getSelectionSet()))
                .from(PAYMENT)
                .where(PAYMENT.CUSTOMER_ID.eq(source.get(CUSTOMER.CUSTOMER_ID)))
                .fetch();
        })
        .dataFetcher("address", env -> {
            Record source = env.getSource();
            Record result = source.get(CUSTOMER_ADDRESS_FIELD);
            if (result != null) return result;
            GraphitronContext ctx = env.getGraphQlContext().get("graphitronContext");
            return ctx.getDslContext(env)
                .select(AddressFields.fields(ADDRESS, env.getSelectionSet()))
                .from(ADDRESS)
                .where(ADDRESS.ADDRESS_ID.eq(source.get(CUSTOMER.ADDRESS_ID)))
                .fetchOne();
        });
}
```

`fields()` is an optimisation — pre-fetching via parent SELECT. The check-then-fetch resolver is the correctness guarantee. Using typed `Field<T>` constants rather than raw string aliases prevents alias collisions and makes the null-check type-safe at compile time.

### FK auto-inference

- Exactly one FK between two tables → `onKey(Keys.FK_CONSTANT)`
- Zero or multiple → codegen error with clear message
- User override: `@reference(path: {key: "FK_NAME"})` → `Keys.FK_NAME`

---

## Deliverable 5: `@splitQuery` `TableField`

Extends `FieldsCodeGenerator` with `TableField` where `@splitQuery` is set. Adds DataLoader + BatchLoader generation and the `loaderName()` helper.

```java
public static CompletableFuture<Result<Record>> orders(DataFetchingEnvironment env) {
    GraphitronContext ctx = env.getGraphQlContext().get("graphitronContext");
    String name = loaderName(env.getExecutionStepInfo().getPath(), ctx.getTenantId(env));
    DataLoader<CustomerRecord, Result<Record>> loader = env.getDataLoaderRegistry()
        .computeIfAbsent(name, k -> DataLoaderFactory.newMappedDataLoaderWithContext(
            CustomerFields::ordersLoader));
    return loader.load(((Record) env.getSource()).into(CUSTOMER), env);
}

private static CompletableFuture<Map<CustomerRecord, Result<Record>>> ordersLoader(
        List<CustomerRecord> keys, BatchLoaderEnvironment ctx) {
    DataFetchingEnvironment env = (DataFetchingEnvironment) ctx.getKeyContextsList().get(0);
    GraphitronContext gCtx = env.getGraphQlContext().get("graphitronContext");
    return CompletableFuture.supplyAsync(() -> {
        Order _a = ORDER.as("order_hash");
        return gCtx.getDslContext(env)
            .select(OrderFields.fields(_a, env.getSelectionSet()))
            .from(_a)
            .where(_a.CUSTOMER_ID.in(keys.stream().map(CustomerRecord::getCustomerId).toList()))
            .fetch().stream()
            .collect(Collectors.groupingBy(r -> r.into(CUSTOMER)));
    });
}

private static String loaderName(ResultPath path, Optional<String> tenantId) {
    String normalized = path.toList().stream()
        .filter(seg -> !(seg instanceof Integer))
        .map(Object::toString).collect(Collectors.joining("/"));
    return tenantId.map(id -> id + "/" + normalized).orElse(normalized);
}
```

---

## Deliverable 5b: Remaining `ChildField` types (P3 / G3)

The testing contract requires at least one Level 2 classification test and one Level 3 approved output file per sealed leaf type. The types in P3/G3 are individually named here so gaps are tracked explicitly rather than hidden in a catch-all bucket.

### Parsing (P3) — one classification test each

| Field type | Trigger | Notes |
|---|---|---|
| `NodeIdField` | `@nodeId` on table-mapped type | Source type must carry `@node` |
| `NodeIdReferenceField` | `@nodeId(typeName: ...)` on table-mapped type | Joins to target type's table |
| `ComputedField` | `@computed` | Developer-supplied jOOQ expression; no projection |
| `PropertyField` | Field on `@record` result type, no other directive | Scalar or nested record property |
| `TableInterfaceField` | Return type is a `@table`+`@discriminate` interface | Cardinality applies |
| `InterfaceField` | Return type is a no-directive interface | Cardinality applies |
| `UnionField` | Return type is a union | Cardinality applies |
| `NestingField` | Return type inherits source table context | Table-mapped source only |
| `TableMethodField` | `@tableMethod` on child field | Cardinality applies |
| `ServiceField` | `@service` on child field | Table-mapped or result-mapped source |

### Generating (G3) — one approved output file each

Each field type above maps to one wiring entry style. The approved file demonstrates the generated `wiring()` call (and `fields()` addition where applicable) for a minimal schema containing that field type.

| Field type | Wiring output shape |
|---|---|
| `NodeIdField` | `GraphitronFetcherFactory.field(...)` with encoded key columns |
| `NodeIdReferenceField` | Same as `ColumnReferenceField` but encodes as Relay ID |
| `ComputedField` | `GraphitronFetcherFactory.field(alias)` — reads pre-computed column by alias |
| `PropertyField` | `GraphitronFetcherFactory.field(...)` on the record property |
| `TableInterfaceField` | Inline field method returning union-wrapped subquery |
| `InterfaceField` | Inline field method returning per-implementor union wrapper |
| `UnionField` | Inline field method returning per-member union wrapper |
| `NestingField` | Adds a nested `fields()` block; no new SQL scope |
| `TableMethodField` | Inline field method using developer-supplied filtered table |
| `ServiceField` | DataLoader for table-mapped source; direct call for result-mapped |

---

## Deliverable 6: `@condition` support

One `<ConditionClassName>Wrapper` per distinct external condition class referenced anywhere in the schema (via `@condition` or `ReferenceElement.condition`). Arguments matched by name (requires `-parameters`); `DSLContext` matched by type.

```java
public class CustomerConditionsWrapper {
    public static Condition activeCustomers(DSLContext ctx) {
        return CustomerConditions.activeCustomers(ctx);
    }
}
```

`override` is a property of the condition spec consumed by `FieldsCodeGenerator`, not the wrapper.

**New files:** `record/ConditionWrapperSpec.java`, `ConditionWrapperSpecBuilder.java`, `ConditionWrapperCodeGenerator.java`, `ConditionWrapperClassGenerator.java`

---

## Deliverable 7: `@service` root fields

One `<ServiceClassName>Wrapper` per distinct external service class. Arguments matched by name; `DSLContext` matched by type; instance service classes via `(DSLContext)` or no-arg constructor.

```java
public class HelloWorldServiceWrapper {
    public static CompletableFuture<HelloWorldRecord> helloWorldAgain(DataFetchingEnvironment env) {
        String name = env.getArgument("name");
        GraphitronContext ctx = env.getGraphQlContext().get("graphitronContext");
        HelloWorldService service = new HelloWorldService(ctx.getDslContext(env));
        return CompletableFuture.supplyAsync(() -> service.helloWorldAgain(name));
    }
}
```

**New files:** `record/ServiceWrapperSpec.java`, `ServiceWrapperSpecBuilder.java`, `ServiceWrapperCodeGenerator.java`, `ServiceWrapperClassGenerator.java`

---

## Deliverable 8: Ordering

`@defaultOrder` and `@orderBy` logic added to root field methods and `@splitQuery` BatchLoaders.
Both live inside `FieldWrapper.List` (and `FieldWrapper.Connection` when implemented) — only those variants generate `ORDER BY` clauses. `FieldWrapper.Single` fields never get an `ORDER BY`.

```java
// @defaultOrder only (FieldWrapper.List with non-null defaultOrder, empty orderByValues)
var orderFields = QueryHelper.getSortFields(_a, "IDX_TITLE", "ASC");

// @orderBy with @defaultOrder fallback (FieldWrapper.List with both populated)
var orderFields = orderBy == null
    ? QueryHelper.getSortFields(_a, "IDX_TITLE", "ASC")
    : switch (orderBy.getOrderByField().toString()) {
        case "TITLE" -> QueryHelper.getSortFields(_a, "IDX_TITLE", orderBy.getDirection().toString());
        case "ID"    -> _a.getPrimaryKey().getFieldsArray();
        default      -> throw new IllegalArgumentException("Unknown orderBy: " + orderBy.getOrderByField());
    };
```

Three `@defaultOrder` modes: `index` → `QueryHelper.getSortFields(table, indexName, dir)`, `fields` → explicit `SortField[]`, `primaryKey` → `table.getPrimaryKey().getFieldsArray()`.

---

## Scope and Future Work

### Mutations

The sealed hierarchy models all five mutation field types (`InsertMutationField`, `UpdateMutationField`, `DeleteMutationField`, `UpsertMutationField`, `ServiceMutationField`). None of D3–D8 cover generating them. This is an explicit scope decision for the current phase: the deliverables above establish the read path (queries, inline fields, DataLoaders, conditions, ordering) end-to-end. Mutation generation is deferred to a follow-on phase and will be stated there as its own deliverable sequence. Until then, the validator will report an error for any mutation field encountered when `recordBasedOutput` is enabled.

### Removing the DTO layer

The current plan adds new generators alongside the existing DTO/TypeMapper generators, controlled by the `recordBasedOutput` flag. Once the record-based pipeline achieves full feature parity and the example server passes all approval tests under the flag, a final cleanup deliverable will:

1. Delete DTO generator classes (`DtoClassGenerator` and related)
2. Delete TypeMapper generator classes
3. Remove the `recordBasedOutput` flag — record-based output becomes the only path
4. Update `GraphQLGenerator.getGenerators()` to drop the removed generators

This deliverable is explicitly out of scope for the current phase. The flag and the side-by-side coexistence are intentional transition scaffolding, not permanent architecture.

---

## Package Structure

| Subpackage | Contents |
|---|---|
| `<outputPackage>.record.fields` | `<TypeName>Fields` — SQL logic + `wiring()` per output type |
| `<outputPackage>.record.resolvers` | `GraphitronWiring`, `<ConditionClassName>Wrapper`, `<ServiceClassName>Wrapper` |

---

## Generator Overview

| Step | Generator | Output | Depends on |
|---|---|---|---|
| 0 | Infrastructure | `GraphitronFetcherFactory`, feature flag, `getTenantId()` | — |
| 1 | `ConditionWrapperClassGenerator` | `<ConditionClassName>Wrapper.java` | — |
| 2 | `ServiceWrapperClassGenerator` | `<ServiceClassName>Wrapper.java` | — |
| 3 | `FieldsClassGenerator` | `<TypeName>Fields.java` | Steps 1 + 2 |
| 4 | `GraphitronWiringClassGenerator` | `GraphitronWiring.java` | Step 3 |
| 5 | Orchestration | Modify `GraphQLGenerator` | Steps 0–4 |

`FieldsClassGenerator` is the core generator. It owns both the SQL logic (field methods, scope-establishing methods, DataLoaders) and `wiring()` for each type. This mirrors how `DBClassGenerator` produces one self-contained class with all DB methods, and lets `wiring()` reference methods in the same class directly (`CustomerFields::orders`).

---

## Generator Architecture: Spec Layer

```
GraphQLSchema
  │
  ▼  GraphitronSchemaBuilder  (schema traversal + FK inference + @table validation; zero JavaPoet)
  │
  ▼  GraphitronSchema  (Map<String, GraphitronType> + Map<FieldCoordinates, GraphitronField>)
  │
  ▼  FieldsCodeGenerator  (iterates TypeDefinitionRegistry; looks up by name / FieldCoordinates; JavaPoet)
  │
  ▼  TypeSpec → .java file
```

`FieldsCodeGenerator` iterates `GraphitronField` instances and emits, per class:
1. `wiring()` — one `.dataFetcher(...)` per field
2. `fields(table, sel)` — scalars unconditional; inline fields guarded by `sel.getFields(...)` (non-root only)
3. Public static field methods — one per inline `TableField`
4. `public <name>(DataFetchingEnvironment)` — one per `@splitQuery` field and root fields
5. `private <name>Loader(List, BatchLoaderEnvironment)` — one per `@splitQuery` field
6. `private loaderName(ResultPath, Optional<String>)` — if any `@splitQuery` fields exist

Wrapper generators (`ConditionWrapperClassGenerator`, `ServiceWrapperClassGenerator`) follow the same spec → codegen split.

---

## Testing Strategy

Three levels, each with a distinct purpose. Every `GraphitronField` and `GraphitronType` leaf type must be covered at Level 1. Gaps at Level 1 are gaps in the specification.

### Level 1 — Validator unit tests (no schema parsing)

`GraphitronField` and `GraphitronType` instances are constructed directly from GraphQL-Java builder APIs. No `GraphitronSchemaBuilder`, no schema files. Each test class covers one sealed leaf type; each parameterised case is one rule or one combination of rules.

The shared contract is a `ValidatorCase` interface implemented by every test enum:

```java
interface ValidatorCase {
    GraphitronField field();
    List<String> errors();
    default boolean isValid() { return errors().isEmpty(); }
}
```

Enum constants carry `(description, field, errors)` constructor arguments. Each constant is a self-contained fixture: the human-readable description drives the `@ParameterizedTest(name = "{0}")` display name via `toString()`, and the field + errors are the test data:

```java
enum Case implements ValidatorCase {

    RESOLVED_IMPLICIT("no @field — column name defaults to the GraphQL field name",
        new ColumnField("title", null, "title", new ResolvedColumn("TITLE", null)),
        List.of()),

    UNRESOLVED_COLUMN("column name could not be matched to a jOOQ field in the table",
        new ColumnField("title", null, "title", new UnresolvedColumn()),
        List.of("Field 'title': column 'title' could not be resolved in the jOOQ table"));

    private final String description;
    private final GraphitronField field;
    private final List<String> errors;

    Case(String description, GraphitronField field, List<String> errors) {
        this.description = description;
        this.field = field;
        this.errors = errors;
    }

    @Override public GraphitronField field() { return field; }
    @Override public List<String> errors() { return errors; }
    @Override public String toString() { return description; }
}
```

**Important**: do not share path-element values (e.g. `List.of(new FkStep(...))`) via outer-class static fields — enum constructors run during class initialisation before outer-class statics are guaranteed to be ready. Inline all values directly in each constant's constructor call.

The test method is a one-liner. `@EnumSource` filtering splits valid and invalid cases into separate methods when it aids clarity:

```java
@ParameterizedTest(name = "{0}")
@EnumSource(Case.class)
void lookupQueryField(Case tc) {
    assertThat(validate(tc.field()))
        .extracting(ValidationError::message)
        .containsExactlyInAnyOrderElementsOf(tc.errors());
}
```

The validation test enum for `LookupQueryField` is `LookupQueryFieldValidationTest.Case` (in `LookupQueryFieldValidationTest.java`) with cases: `VALID`, `VALID_WITH_ARGS`, `LIST_RETURN`, `CONNECTION_RETURN`, `ORDERBY_ARG`, `CONDITION_ARG`, `ORDERBY_AND_CONDITION_ARGS`.

For multi-dimensional combinations (e.g., type directive × field directive → classification), `@CsvSource` with `useHeadersInDisplayName = true` reads as a truth table:

```java
@CsvSource(useHeadersInDisplayName = true, textBlock = """
    typeDirective,  fieldDirective,  expectedType
    @table,         @nodeId,         NodeIdField
    @table,         @field,          ColumnField
    @table,         ,                UnclassifiedField
    ,               @nodeId,         UnclassifiedField
""")
```

**Rule**: use `@EnumSource` when constants have behaviour or are reused across test classes; use `@CsvSource` when the data is purely tabular and self-contained to one test method.

### Level 2 — Classification tests (inline schema → `GraphitronSchemaBuilder`)

Each test defines its own minimal inline schema as a text block — no shared schema. The schema is the documentation; keep it minimal. One canonical representative per leaf type confirms the classifier produces the right concrete type.

```java
@Test
void columnField() {
    var result = GraphitronSchemaBuilder.build(parse("""
        type Customer @table { email: String }
        type Query { customer: Customer }
        """));
    assertThat(result.get("Customer", "email")).isInstanceOf(ColumnField.class);
}
```

Schema mutation (adding directives, wrapping field types) uses the same `remove` + `add(transform(...))` pattern as the schema transformer, via small helpers in `RegistryTestHelper`:

```java
// Add directive to type or field
RegistryTestHelper.addDirective(registry, "Film", "@table");
RegistryTestHelper.addDirective(registry, "Film", "title", "@notGenerated");

// Wrap a field's return type
RegistryTestHelper.wrapReturnType(registry, "Query", "film", ListType::new);
RegistryTestHelper.wrapReturnType(registry, "Query", "film", t -> new NonNullType(new ListType(t)));
```

`RegistryTestHelper.parseDirective(String)` builds the AST directive node by parsing `"type _D @directive {}"` — handles argument forms automatically.

### Level 3 — Error message and source location tests

A small number of end-to-end tests verify that error messages are human-readable, contain the right field/type name, and that `SourceLocation` carries correct line, column, and file path. `SourceLocation.getSourceName()` is populated by `MultiSourceReader` during schema loading (already established in `SchemaReadingHelper`).

```java
@Test
void errorMessageReferencesSourceLocation() {
    var errors = validateSchema("""
        type Query { film(id: ID! @lookupKey): [Film] }
        type Film @table { id: ID! @nodeId }
        """);
    assertThat(errors).singleElement().satisfies(e -> {
        assertThat(e.message()).contains("film", "single item");
        assertThat(e.location().getLine()).isPositive();
    });
}
```

### Generating stream — `FieldsCodeGeneratorTest`

Tests use hand-crafted `GraphitronField` instances — no schema, no `GraphitronSchemaBuilder`. Output is compared against approved `.java` files. Every leaf type needs at least one approved file.

### Integration — `RecordOutputIntegrationTest`

End-to-end: schema in, generated `.java` source out (or running Quarkus test). Validates that parsing + generating agree. Added per integration deliverable (I1–I4).

### Test file layout

```
src/test/java/.../record/
  validation/
    ValidatorCase.java              ← shared interface implemented by all test enums
    LookupQueryFieldValidationTest.java
    TableQueryFieldValidationTest.java
    ColumnFieldValidationTest.java
    // one class per sealed leaf type
  GraphitronSchemaBuilderTest.java        ← Level 2: classification, one test per leaf type
  FieldsCodeGeneratorTest.java      ← generating stream, one approval per leaf type
  integration/
    RecordOutputIntegrationTest.java

src/test/resources/record/
  CustomerFields_scalar.java
  CustomerFields_inline.java
  CustomerFields_splitQuery.java
  QueryFields.java
  // one file per leaf type tested in generating stream
```

---

## Critical Files

| File | Change |
|---|---|
| `graphitron-common/.../GraphitronContext.java` | Add `getTenantId()` |
| `graphitron-common/.../DefaultGraphitronContext.java` | Implement `getTenantId()` → `Optional.empty()` |
| `graphitron-common/.../GraphitronFetcherFactory.java` | **New** |
| `graphitron-java-codegen/.../configuration/GeneratorConfig.java` | Add `recordBasedOutput` |
| `graphitron-java-codegen/.../generate/Generator.java` | Add `boolean recordBasedOutput()` |
| `graphitron-maven-plugin/.../mojo/GenerateMojo.java` | Add `@Parameter recordBasedOutput` |
| `graphitron-java-codegen/.../generate/GraphQLGenerator.java` | Add new generators when flag is set |
| `graphitron-java-codegen/.../mappings/JavaPoetClassName.java` | Add `JOOQ_RECORD`, `JOOQ_RESULT`, `LIGHT_DATA_FETCHER`, `GRAPHITRON_FETCHER_FACTORY` |
| `record/field/GraphitronField.java` + 28 leaf types | **Done** — Deliverable 1 |
| `record/field/ColumnRef.java` + `ResolvedColumn`, `UnresolvedColumn` | **Done** — Deliverable 1 |
| `record/field/ReferencePathElementRef.java` + 6 step types | **Done** — Deliverable 1 |
| `record/type/GraphitronType.java` + 6 leaf types | **Done** — Deliverable 1 |
| `record/type/TableRef.java` + `ResolvedTable`, `UnresolvedTable` | **Done** — Deliverable 1 |
| `record/GraphitronSchema.java` | **Done** — Deliverable 1 |
| `record/GraphitronSchemaBuilder.java` | **New** |
| `record/FieldsCodeGenerator.java` | **New** |
| `record/FieldsClassGenerator.java` | **New** |
| `record/GraphitronWiringClassGenerator.java` | **New** |
| `record/ConditionWrapper*.java` (4 files) | **New** |
| `record/ServiceWrapper*.java` (4 files) | **New** |

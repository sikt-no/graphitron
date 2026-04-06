# Plan: Record-Based Output — Parsing and Validation

**Status: Complete.** This document covers the work that has been done: the sealed field/type hierarchy (D1), the full parsing stream (P1–P5), the validator, and the testing strategy for those layers.

The companion document [`plan-record-generation.md`](plan-record-generation.md) covers the generating stream, the test infrastructure for generated code, and the remaining deliverables.

---

## Context

The current code generation pipeline produces output DTOs and TypeMapper classes that convert jOOQ Records into those DTOs. This is unnecessary: graphql-java's `RuntimeWiring` can resolve fields directly from any Java object, including jOOQ `Record`. Eliminating the DTO/TypeMapper layer reduces generated code volume, removes the selection-set-per-field mapping boilerplate, and unblocks future features (e.g. `@record` output support).

The change is behind a `rewriteBasedOutput` feature flag (default `false`) and generates new artefacts into a separate package (`<outputPackage>.rewrite.*`) so old and new code can coexist. The existing generators continue to run unchanged — the flag adds new generators alongside.

### Existing codebase facts

The main orchestrator is `GraphQLGenerator.getGenerators()` which runs, in order: 5 DTO generators, `DBClassGenerator` (→ `{TypeName}DBQueries` in package `queries`), transformer + mapper generators, exception generators, `OperationClassGenerator`, `TypeResolverClassGenerator`, `WiringClassGenerator`. All live in `graphitron-codegen-parent/graphitron-java-codegen`.

Generator base class: `AbstractSchemaClassGenerator<T>` — takes a `ProcessedSchema`, produces one `TypeSpec` per `generate(T target)` call.

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

## Deliverable 1: `GraphitronField` skeleton ✓

The `GraphitronField` sealed interface hierarchy is the Java materialisation of the field taxonomy. It is the foundation everything else targets — the spec builder populates it, the code generators consume it.

**Packages:** `no.sikt.graphitron.rewrite.field` and `no.sikt.graphitron.rewrite.type` (in `graphitron-java-codegen`)

### `GraphitronType`

Every GraphQL named type is classified into a `GraphitronType`. This is where `@table` directive mappings are validated — jOOQ table class exists, discriminator columns are present, etc. — and it is the authoritative source of source context for all fields on that type.

See [`GraphitronType.java`](../graphitron-codegen-parent/graphitron-java-codegen/src/main/java/no/sikt/graphitron/record/type/GraphitronType.java). The variants are:

| Variant | Trigger | Key fields |
|---|---|---|
| `TableType` | `@table` directive | `TableRef table`, `NodeRef node` |
| `ResultType` | `@record` directive | Runtime wiring only; no SQL until a new scope starts |
| `RootType` | `Query` / `Mutation` type | Entry points; no directive required |
| `TableInterfaceType` | `@table` + `@discriminate` | `discriminatorColumn`, `TableRef table`, `List<ParticipantRef>` |
| `InterfaceType` | Interface without `@table` | `List<ParticipantRef>` (each member carries `@table`) |
| `UnionType` | GraphQL union | `List<ParticipantRef>` (all members carry `@table`) |
| `ErrorType` | `@error` directive | `List<ErrorHandlerSpec>` — maps Java exceptions to error responses |
| `InputType` | GraphQL input object | `List<InputFieldSpec>` for argument inspection |
| `UnclassifiedType` | Conflicting or unrecognised directives | `reason` string naming the conflict |

### `TableRef`

See [`TableRef.java`](../graphitron-codegen-parent/graphitron-java-codegen/src/main/java/no/sikt/graphitron/record/type/TableRef.java). `TableType` and `TableInterfaceType` use a two-variant sealed hierarchy (`ResolvedTable`, `UnresolvedTable`) for the outcome of matching the `@table` directive's SQL name against the jOOQ catalog. `tableName()` is present on both so callers never need to pattern-match just to retrieve the SQL name. `ResolvedTable` additionally carries the jOOQ `Table<?>` instance (columns, primary key, FK metadata) and the Java field name in the generated `Tables` class. The validator reports an error for `UnresolvedTable`; the code generator only consumes `ResolvedTable`.

### `ParticipantRef`

See [`ParticipantRef.java`](../graphitron-codegen-parent/graphitron-java-codegen/src/main/java/no/sikt/graphitron/record/type/ParticipantRef.java). Each implementing or member type of an interface or union is represented as `BoundParticipant` (carries `@table` + the `TableRef`) or `UnboundParticipant` (does not — validator reports an error). `BoundParticipant.discriminatorValue` (from `@discriminator(value:)`, `null` when absent) is used by the type resolver generator to map a discriminator column value to a concrete Java type.

### `GraphitronField`

The sealed interface hierarchy covering all 28+ leaf field types. See [`GraphitronField.java`](../graphitron-codegen-parent/graphitron-java-codegen/src/main/java/no/sikt/graphitron/record/field/GraphitronField.java). Key subtypes:

- **`ChildField`** — fields on non-root types. Includes `ColumnField`, `ColumnReferenceField`, `TableField`, `TableMethodField`, `NodeIdField`, `NodeIdReferenceField`, `ComputedField`, `PropertyField`, `ServiceField`, `NestingField`, `TableInterfaceField`, `InterfaceField`, `UnionField`, `MultitableReferenceField`, `NotGeneratedField`
- **`QueryField`** — root Query fields. Includes `TableQueryField`, `LookupQueryField`, `TableMethodQueryField`, `NodeQueryField`, `EntityQueryField`, `TableInterfaceQueryField`, `InterfaceQueryField`, `UnionQueryField`, `ServiceQueryField`
- **`MutationField`** — root Mutation fields (classified but not yet generated): `InsertMutationField`, `UpdateMutationField`, `DeleteMutationField`, `UpsertMutationField`, `ServiceMutationField`
- **`UnclassifiedField`** — fields that matched conflicting or unrecognised directives; carries a `reason` string naming the conflict

### `FieldWrapper`

Sealed hierarchy on return type cardinality: `Single`, `List`, `Connection`. `Connection` is detected by structural inspection (edges → node chain), not a directive.

### `ColumnRef`, `ReferencePathElementRef`, `FieldConditionRef`, `ArgumentSpec`, etc.

Supporting value types carried in field records. See their respective source files under `no.sikt.graphitron.rewrite.field`.

---

## Parsing stream ✓

All parsing stream deliverables are complete. The builder is in `GraphitronSchemaBuilder.java`.

| Deliverable | Content |
|---|---|
| P1 ✓ | `ColumnField`, `ColumnReferenceField`, `NodeIdField`, `NodeIdReferenceField`, `NotGeneratedField`, `ErrorType` |
| P2 ✓ | `TableField`, `TableMethodField`, `NestingField` |
| P3 ✓ | `ComputedField`, `PropertyField`, `TableInterfaceField`, `InterfaceField`, `UnionField`, `ServiceField` |
| P4 ✓ | `InputType` in `GraphitronType`; argument list on field records |
| P5 ✓ | All `QueryField` and `MutationField` variants |

### P4: Field arguments and input types

Field arguments on query and mutation fields drive filter conditions, pagination, lookup keys, and service context bindings. `InputObjectTypeDefinition` is classified into `InputType`. Each `InputFieldSpec` captures the field name, its GraphQL type string, and directive markers generators need.

> **`@lookupKey` is not stored in `InputFieldSpec` or `ArgumentSpec`.** `hasLookupKeyAnywhere()` checks the live GraphQL schema to decide whether a root Query field is a `LookupQueryField`. Once classified, `@lookupKey` carries no further semantic.

`@field(javaName:)` is deprecated. Its presence is recorded as `javaNamePresent: boolean` so the validator can emit a deprecation error. The value is not stored.

`contextArguments` from `@service` and `@tableMethod` directives are captured as `List<String> contextArguments` on `ServiceQueryField`, `ServiceMutationField`, `ServiceField`, `TableMethodQueryField`, and `TableMethodField`. The `ExternalCodeReference` input object is stored as an `ExternalRef` with `className` and nullable `methodName`.

### P5: Root field parsing

Root fields are classified by `classifyQueryField()` and `classifyMutationField()` in `GraphitronSchemaBuilder`.

**Query field classification priority:**
1. `@service` directive → `ServiceQueryField`
2. Field name `_entities` → `EntityQueryField`
3. Field name `node` → `NodeQueryField`
4. Any arg (direct or nested in input types) has `@lookupKey` → `LookupQueryField`
5. `@tableMethod` directive → `TableMethodQueryField`
6. Return type is `TableType` → `TableQueryField`
7. Return type is `TableInterfaceType` → `TableInterfaceQueryField`
8. Return type is `InterfaceType` → `InterfaceQueryField`
9. Return type is `UnionType` → `UnionQueryField`

`hasLookupKeyAnywhere()` checks direct args for `@lookupKey`, then recursively checks input type fields (depth-guarded at 10 levels).

**Mutation classification:** `@service` → `ServiceMutationField`. Otherwise `@mutation(typeName:)` determines: `INSERT`, `UPDATE`, `DELETE`, or `UPSERT`.

### Directive exclusivity rules

The builder classifies violations as `UnclassifiedField(reason)` or `UnclassifiedType(reason)`.

| Scope | Mutually exclusive directives | Notes |
|---|---|---|
| **Type-level** | `@table`, `@record`, `@error` | All three are peers |
| **Child fields** | `@service`, `@externalField`, `@tableMethod`, `@nodeId`, `@notGenerated`, `@multitableReference` | `@reference` intentionally excluded — it is a path-annotation directive and may be combined with any of the above |
| **Query fields** | `@service`, `@lookupKey`, `@tableMethod` | |
| **Mutation fields** | `@service`, `@mutation` | |

---

## Validator

`GraphitronSchemaValidator` receives a `GraphitronSchema` and accumulates `ValidationError` records — it never throws. Each `GraphitronField` and `GraphitronType` variant has a dedicated `validate*()` method. `UnclassifiedField` and `UnclassifiedType` report their `reason` as a build error. `ErrorType` is a deliberate no-op (no structural constraints to check at this layer).

---

## Testing Strategy (parsing and validation layers)

### Level 1 — Validator unit tests (no schema parsing)

`GraphitronField` and `GraphitronType` instances are constructed directly. No `GraphitronSchemaBuilder`, no schema files. One test class per sealed leaf type; each parameterised case is one rule or one combination of rules.

```java
interface ValidatorCase {
    GraphitronField field();
    List<String> errors();
    default boolean isValid() { return errors().isEmpty(); }
}
```

```java
enum Case implements ValidatorCase {

    RESOLVED_IMPLICIT("no @field — column name defaults to field name",
        new ColumnField("title", null, "title", new ResolvedColumn("TITLE", null)),
        List.of()),

    UNRESOLVED_COLUMN("column name could not be matched",
        new ColumnField("title", null, "title", new UnresolvedColumn()),
        List.of("Field 'title': column 'title' could not be resolved"));
```

**Rule**: use `@EnumSource` when constants have behaviour or are reused; use `@CsvSource` when data is purely tabular.

### Level 2 — Classification tests (inline schema → `GraphitronSchemaBuilder`)

Each test defines its own minimal inline schema as a text block. One canonical representative per leaf type confirms the classifier produces the right concrete type.

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

---

## Critical Files (Done)

| File | Status |
|---|---|
| `record/field/GraphitronField.java` + 28 leaf types | Done |
| `record/field/ColumnRef.java` + variants | Done |
| `record/field/ReferencePathElementRef.java` + 6 step types | Done |
| `record/field/ArgumentSpec.java`, `ExternalRef.java`, `InputFieldSpec.java` | Done |
| `record/type/GraphitronType.java` + all variants | Done |
| `record/type/TableRef.java`, `ParticipantRef.java`, `NodeRef.java` | Done |
| `record/GraphitronSchema.java` | Done |
| `record/GraphitronSchemaBuilder.java` | Done |
| `record/GraphitronSchemaValidator.java` | Done |

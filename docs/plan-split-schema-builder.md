# Plan: Split GraphitronSchemaBuilder into Four Components

## Status: COMPLETE (5 of 5 files done)

**Branch:** `claude/validation-test-coverage-plan-EcOP7`

---

## Motivation

`GraphitronSchemaBuilder.java` has grown to 2154 lines. It mixes four
distinct concerns in one class. Splitting it makes each piece readable in
isolation, testable independently, and easier to evolve.

---

## Target Structure (5 files in `no.sikt.graphitron.rewrite`)

| File | Role | Status |
|------|------|--------|
| `BuildContext.java` | Shared state + stateless utilities | ✅ Done |
| `ServiceCatalog.java` | Reflection + jOOQ catalog lookups | ✅ Done |
| `TypeBuilder.java` | Two-pass type classification | ✅ Done |
| `FieldBuilder.java` | Field classification (all classifyXxx methods) | ✅ Done |
| `GraphitronSchemaBuilder.java` | Thin orchestrator (~100 lines) | ✅ Done |

All five files are package-private (`no.sikt.graphitron.rewrite`).
The only public API is `GraphitronSchemaBuilder.build(TypeDefinitionRegistry)`,
which does not change.

---

## What Has Been Done

### `BuildContext.java` (✅ committed)
Holds:
- All 44 directive/argument name constants (previously scattered in
  `GraphitronSchemaBuilder`)
- Three shared mutable fields: `final GraphQLSchema schema`,
  `final JooqCatalog catalog`, `Map<String, GraphitronType> types`
  (`types` is null until `TypeBuilder.buildTypes()` sets it)
- Stateless utility methods (all static where possible):
  `argString`, `argStringList`, `asMap`, six `locationOf` overloads,
  `isConnectionType`, `connectionItemNullable`, `connectionElementTypeName`,
  `baseTypeName`, `resolveReturnType`, `candidateHint`, `levenshteinDistance`

### `ServiceCatalog.java` (✅ committed)
Handles all reflection and catalog lookups:
- `resolveTable`, `resolveTableByRecordClass`, `buildTableRef`
- `resolveKeyColumn`, `resolveColumn`, `resolveColumnForReference`,
  `terminalTableSqlNameForReference`, `resolveColumnInTable`
- `getTableSqlNameForType`
- `reflectServiceMethod` (loads service class via reflection, classifies params)
- `classifySourcesType` (static; recognises `List<RowN<...>>`,
  `List<RecordN<...>>`, `List<SomeTableRecord>`)
- Inner record: `ServiceReflectionResult(MethodRef ref, String failureReason)`

### `TypeBuilder.java` (✅ committed)
Runs the two-pass type classification:
- `buildTypes()` — first pass calls `classifyType` for every named type,
  sets `ctx.types`; second pass calls `enrichXxx` to add participant lists
- `classifyType`, `buildTableType`, `buildResultType`,
  `buildTableInterfaceType`, `buildErrorType`, `buildInputType`,
  `buildNonTableInputType`, `buildTableInputType`
- `enrichTableInterfaceType`, `enrichInterfaceType`, `enrichUnionType`
- `buildParticipantList`, `implementorNames`
- `buildInputColumnField`, `parseErrorHandler`, `findReturnTablesForInput`
- `fieldCoordinatesOf` (static helper)
- `detectTypeDirectiveConflict` (static; moved here from FieldBuilder
  because it's called from `classifyType`)
- Inner record: `ParticipantListResult(List<ParticipantRef> list, String error)`

---

## What Remains

### Step 1 — Write `FieldBuilder.java`

Constructor: `FieldBuilder(BuildContext ctx, ServiceCatalog svc)`

Move these methods verbatim from `GraphitronSchemaBuilder.java`, updating
all internal cross-references as described in the **Reference translation**
section below:

**Field classification (main dispatch)**
- `classifyField`
- `classifyRootField`
- `classifyQueryField`
- `classifyMutationField`
- `classifyChildFieldOnTableType`
- `classifyChildFieldOnResultType`
- `classifyObjectReturnChildField`

**Wrapper / filter / order-by / pagination builders**
- `buildWrapper`
- `buildFilters`
- `buildOrderBySpec`
- `resolveDefaultOrderSpec`
- `resolveColumnOrderSpec`
- `resolveOrderByArgSpec`
- `buildPaginationSpec`
- `isPaginationArg` (static)

**Reference path parsing**
- `parsePath()` (no-source overload)
- `parsePath(fieldDef, startSqlTableName)`
- `parsePathElement`
- `resolveConditionRef` (stub returning null)
- `extractConditionQualifiedName`

**Helper methods**
- `parseExternalRef`
- `parseContextArguments`
- `getMutationTypeName`
- `hasLookupKeyAnywhere`
- `inputTypeHasLookupKey`
- `isScalarOrEnum`

**Conflict detection**
- `detectChildFieldConflict`
- `detectQueryFieldConflict`

**Inner records**
- `ExternalRef(String className, String methodName)`
- `ParsedPath(List<JoinStep> elements, String errorMessage)` with
  `boolean hasError() { return errorMessage != null; }`

#### Reference translation table for FieldBuilder

| Original reference | New reference |
|--------------------|---------------|
| `this.schema` | `ctx.schema` |
| `this.catalog` | `ctx.catalog` |
| `this.types` | `ctx.types` |
| `argString(...)` | `BuildContext.argString(...)` (or static import) |
| `argStringList(...)` | `BuildContext.argStringList(...)` |
| `asMap(...)` | `BuildContext.asMap(...)` |
| `locationOf(...)` | `BuildContext.locationOf(...)` |
| `baseTypeName(...)` | `BuildContext.baseTypeName(...)` |
| `candidateHint(...)` | `BuildContext.candidateHint(...)` |
| `isConnectionType(...)` | `ctx.isConnectionType(...)` |
| `connectionElementTypeName(...)` | `ctx.connectionElementTypeName(...)` |
| `connectionItemNullable(...)` | `ctx.connectionItemNullable(...)` |
| `resolveReturnType(...)` | `ctx.resolveReturnType(...)` |
| `reflectServiceMethod(...)` | `svc.reflectServiceMethod(...)` |
| `resolveColumn(...)` | `svc.resolveColumn(...)` |
| `resolveColumnForReference(...)` | `svc.resolveColumnForReference(...)` |
| `terminalTableSqlNameForReference(...)` | `svc.terminalTableSqlNameForReference(...)` |

Note: `catalog.findColumn(...)`, `catalog.columnSqlNamesOf(...)`,
`catalog.findPkColumns(...)`, `catalog.findIndexColumns(...)`,
`catalog.findForeignKey(...)`, `catalog.allForeignKeySqlNames()` are accessed
as `ctx.catalog.findColumn(...)` etc. (direct catalog access is fine in
FieldBuilder for the order-by and filter methods).

---

### Step 2 — Rewrite `GraphitronSchemaBuilder.java`

Replace the entire 2154-line file with ~80 lines:

```java
public class GraphitronSchemaBuilder {

    public static GraphitronSchema build(TypeDefinitionRegistry registry) {
        var runtimeWiring = EchoingWiringFactory.newEchoingWiring(wiring ->
            registry.scalars().forEach((name, v) -> {
                if (!ScalarInfo.isGraphqlSpecifiedScalar(name)) {
                    wiring.scalar(EchoingWiringFactory.fakeScalar(name));
                }
            })
        );
        var assembled = new SchemaGenerator().makeExecutableSchema(registry, runtimeWiring);
        var ctx = new BuildContext(assembled, new JooqCatalog(GeneratorConfig.getGeneratedJooqPackage()));
        var svc = new ServiceCatalog(ctx);
        var typeBuilder = new TypeBuilder(ctx, svc);
        var fieldBuilder = new FieldBuilder(ctx, svc);
        return buildSchema(ctx, typeBuilder, fieldBuilder);
    }

    private static GraphitronSchema buildSchema(BuildContext ctx, TypeBuilder typeBuilder, FieldBuilder fieldBuilder) {
        validateDirectiveSchema(ctx);
        ctx.types = typeBuilder.buildTypes();
        var fields = new LinkedHashMap<FieldCoordinates, GraphitronField>();
        ctx.schema.getAllTypesAsList().stream()
            .filter(t -> t instanceof GraphQLObjectType && !t.getName().startsWith("__"))
            .map(t -> (GraphQLObjectType) t)
            .forEach(objType -> {
                var parentType = ctx.types.get(objType.getName());
                if (parentType == null) return;
                objType.getFieldDefinitions().forEach(fieldDef ->
                    fields.put(
                        FieldCoordinates.coordinates(objType.getName(), fieldDef.getName()),
                        fieldBuilder.classifyField(fieldDef, objType.getName(), parentType)));
            });
        return new GraphitronSchema(ctx.types, Collections.unmodifiableMap(fields));
    }

    private static void validateDirectiveSchema(BuildContext ctx) {
        assertDirective(ctx, BuildContext.DIR_TABLE, BuildContext.ARG_NAME);
        // ... all assertDirective calls (copy from original lines 2039-2058)
    }

    private static void assertDirective(BuildContext ctx, String name, String... args) {
        // copy from original lines 2061-2075, replacing this.schema with ctx.schema
    }
}
```

---

### Step 3 — Verify and commit

```bash
# Compile only (fast check)
mvn test-compile -pl :graphitron-java-codegen -Dmaven.resolver.transport=wagon

# Full test run
mvn test -pl :graphitron-java-codegen \
    -Dtest="no/sikt/graphitron/rewrite/**" \
    -Dmaven.resolver.transport=wagon \
    -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: 348 tests pass, zero compilation errors.

Then delete the dead code from `GraphitronSchemaBuilder.java` (old version),
and commit with message:

```
Split GraphitronSchemaBuilder into BuildContext/ServiceCatalog/TypeBuilder/FieldBuilder

No logic changes — pure structural split of the 2154-line builder into
four focused components. The only public API (build(TypeDefinitionRegistry))
is unchanged. All 348 rewrite tests pass.
```

---

## Key Design Decisions (already settled)

- **Option A for FieldBuilder constructor**: `FieldBuilder(BuildContext ctx, ServiceCatalog svc)`
  — explicit dependency, no hidden coupling through BuildContext.
- `types` is a mutable field on `BuildContext` (not a record) because it is
  written by `TypeBuilder.buildTypes()` and then read by FieldBuilder.
- Directive/argument name constants live in `BuildContext` as package-private
  `static final String` fields.
- Shared stateless utilities (`argString`, `locationOf`, `candidateHint`, etc.)
  are `static` methods on `BuildContext`; connection-type helpers
  (`isConnectionType`, etc.) are instance methods because they read `schema`.
- `detectTypeDirectiveConflict` lives in `TypeBuilder` (called from
  `classifyType`); `detectChildFieldConflict` and `detectQueryFieldConflict`
  live in `FieldBuilder`.

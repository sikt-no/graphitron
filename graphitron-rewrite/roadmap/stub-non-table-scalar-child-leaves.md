---
title: "Stub #8: Non-table / scalar / reference child leaves"
status: Spec
bucket: stubs
priority: 2
---

# Stub #8: Non-table / scalar / reference child leaves

Lift these leaves out of `TypeFetcherGenerator.NOT_IMPLEMENTED_REASONS`: `ChildField.ColumnReferenceField`, `ComputedField`, `TableMethodField`, `ServiceRecordField`, `MultitableReferenceField`.

Priority number `#8` is referenced by emitted reason strings and must stay stable.

`NodeIdReferenceField` shipped under *`@nodeId` + `@node` directive support* (Done) for the FK-mirror case; the non-FK-mirror form is tracked under [Cleanup -> `NodeIdReferenceField` JOIN-projection form](nodeidreferencefield-join-projection-form.md).

Each variant is independent. The five tracks below ship in any order. Plan bodies for `ComputedField` and `ServiceRecordField` are fully spec'd; the remaining three (`ColumnReferenceField`, `TableMethodField`, `MultitableReferenceField`) remain Backlog inside this umbrella, each waiting for its own author to draft a plan.

---

## Track 1: `ComputedField` (`@externalField`)

### Decision

`@externalField` follows the same "wired via `ColumnFetcher`, projected through `$fields()`" pattern as `ColumnField`. The developer supplies a static method returning `Field<?>`; the generator invokes it at codegen time, inlines the returned expression aliased to the field name in `$fields()`, and registers a `ColumnFetcher` keyed on `DSL.field("<name>")` for the wiring side. This mirrors the legacy generator (`FetchDBMethodGenerator.getExternalBlock`) but resolves the developer reference through an `ExternalCodeReference` directive argument, bringing `@externalField` in line with `@service`, `@tableMethod`, and `@condition`.

The first iteration covers the no-`@reference` case (the legacy's primary use case). The lift case (developer method resolved through a `joinPath` of one or more `ConditionJoin` hops) is deferred; the validator rejects non-empty `joinPath` with a `DEFERRED` rejection until a real schema needs it.

### Schema-side change: `@externalField` gains a `reference` argument

Update `graphitron/src/main/resources/no/sikt/graphitron/rewrite/schema/directives.graphqls`:

```graphql
"""
The @externalField directive indicates that the annotated field is retrieved
using a static extension method implemented in Java. The referenced method takes
the parent table as its single parameter and returns Field<X> matching the
field's scalar type.
"""
directive @externalField(reference: ExternalCodeReference) on FIELD_DEFINITION
```

`ExternalCodeReference` already ships for `@service` etc., with `className` (or deprecated `name` resolved through `RewriteContext.namedReferences()`) and `method`.

A new constant `ARG_EXTERNAL_FIELD_REF = "reference"` lands in `BuildContext`.

**Backward compatibility**: `reference` is optional. When `@externalField` is applied without a `reference` argument, the builder logs a deprecation warning at codegen time and falls back to the current stub behavior: a `ComputedField` with `method = null` is classified, no real fetcher is generated, and the emitted stub throws `UnsupportedOperationException` at request time. Existing schemas compile without errors; they receive a deprecation warning in the build log pointing toward adding `reference: {className: ..., method: ...}`. A forced migration shim is out of scope for this track.

**Existing test fixtures**: `GraphitronSchemaBuilderTest` uses `@externalField` without `reference` at line 1201 and in conflict-directive cases at lines 3610-3653. After this track ships those fixtures continue to pass (no-arg form logs a warning and generates a stub); no schema changes are required. The warning assertion is a new test case: see the Tests section.

### Model: `ComputedField` carries a `MethodRef`

`graphitron/src/main/java/no/sikt/graphitron/rewrite/model/ChildField.java`:

```java
record ComputedField(
    String parentTypeName,
    String name,
    SourceLocation location,
    ReturnTypeRef returnType,
    List<JoinStep> joinPath,
    @Nullable MethodRef method  // NEW; null when @externalField has no reference arg (legacy no-arg form)
) implements ChildField {}
```

The `MethodRef.Basic` carries the captured return type (always `Field<X>` post-reflection) and one `MethodRef.Param.Typed` whose `ParamSource` is `ParamSource.Table`.

### Builder: reflect, classify, attach

`FieldBuilder.classifyChildField` `if (fieldDef.hasAppliedDirective(DIR_EXTERNAL_FIELD))` arm (line 2019) currently resolves the join path but performs no method reflection. Replace with:

**No-reference path (legacy)**: if the applied directive carries no `reference` argument (the directive argument is null or absent), log a deprecation warning via the builder's existing logger:
```
log.warn("Field '{}': @externalField without a reference argument is deprecated; "
    + "add reference: {{className: ..., method: ...}} to enable full code generation.",
    qualifiedName);
```
Return `new ComputedField(parentTypeName, name, location, returnType, externalPath.elements(), null)`. The null method preserves backward-compatible stub behavior; see `TypeFetcherGenerator` cleanup below.

**Reference path (new)**:

1. `parseExternalRef(parentTypeName, fieldDef, DIR_EXTERNAL_FIELD, ARG_EXTERNAL_FIELD_REF)`. Failure -> `UnclassifiedField(AUTHOR_ERROR, "external field reference could not be resolved - " + lookupError)`.
2. Reflect via a new `ServiceCatalog.reflectExternalField(className, methodName, parentTableRecordClass, expectedScalarType)`:
   - Locate the public static method by name.
   - Assert the single parameter is assignable from the parent's jOOQ generated `Table<?>` class.
   - Assert the return type is parameterised `org.jooq.Field`. Capture the type argument; reject if it is not assignable from the GraphQL scalar's runtime Java type. (`ScalarTypeRef.javaType()` already exposes this.)
   - On success, return a `MethodRef.Basic` whose params list is `[new MethodRef.Param.Typed("table", "<TableType>", new ParamSource.Table())]` and whose return type is the declared `Field<X>`.
3. Build `ComputedField` with `joinPath = externalPath.elements()` (existing path parsing) and `method = result.ref()`.

The reflection helper mirrors `reflectTableMethod` (line 255 in `ServiceCatalog`). The strict return-type check is necessary so the inlined call expression compiles in the generated `$fields()` body (the projection list is `List<Field<?>>`).

### `$fields()` projection: inlined aliased call

`TypeClassGenerator.emitSelectionSwitch` (line 225) gains:

```java
case ChildField.ComputedField cf when cf.method() != null -> {
    var ref = cf.method();
    var refClass = ClassName.bestGuess(ref.className());
    builder.addCode("        case $S -> fields.add($T.$L($L).as($S));\n",
        cf.name(), refClass, ref.methodName(), tableArg, cf.name());
}
// null-method (legacy no-arg @externalField): no projection entry; stub fetcher handles it
```

Generated code for `Film.isEnglish`:

```java
case "isEnglish" -> fields.add(FilmExtensions.isEnglish(table).as("isEnglish"));
```

The `.as("<name>")` alias is required so the wiring side can look the field up by name in the result Record (see below).

### Wiring: `ColumnFetcher` + `DSL.field(<name>)`

`FetcherEmitter.dataFetcherValue` (line 51) gains an arm matching `ChildField.ComputedField`:

```java
if (field instanceof ChildField.ComputedField cf && cf.method() != null) {
    var columnFetcherClass = ClassName.get(outputPackage + ".util",
        ColumnFetcherClassGenerator.CLASS_NAME);
    return CodeBlock.of("new $T<>($T.field($S))", columnFetcherClass, DSL, cf.name());
}
// null-method (legacy no-arg @externalField) falls through to the method-reference default,
// which emits a reference to the stub generated in TypeFetcherGenerator
```

Generated code:

```java
.dataFetcher("isEnglish", new ColumnFetcher<>(DSL.field("isEnglish")))
```

The lookup-by-name pattern is the same one already used for `ChildField.LookupTableField` and the list-cardinality `ChildField.TableField` arms in `FetcherEmitter` (lines 87 and 92). The alias applied in `$fields()` makes the lookup unambiguous.

### `TypeFetcherGenerator` cleanup

`graphitron/src/main/java/no/sikt/graphitron/rewrite/generators/TypeFetcherGenerator.java`:

1. Remove the `Map.entry(ChildField.ComputedField.class, ...)` from `NOT_IMPLEMENTED_REASONS` (actual lines 255-256).
2. Add `ChildField.ComputedField.class` to `IMPLEMENTED_LEAVES` (around line 146).
3. Replace the `case ChildField.ComputedField f -> builder.addMethod(stub(f));` arm (actual line 396) with two guarded arms:
   ```java
   case ChildField.ComputedField cf when cf.method() != null -> { }  // wired via FetcherEmitter; projected via $fields
   case ChildField.ComputedField cf -> builder.addMethod(stub(cf));  // legacy: no reference arg, preserve stub behavior
   ```
4. Update the `IMPLEMENTED_LEAVES` Javadoc reference list (line 51) to mention `ChildField.ComputedField` alongside `ChildField.ColumnField`.

### Validator

`GraphitronSchemaValidator.validateComputedField` (line 680) gets two additions:

1. A short-circuit for the legacy no-arg case (warning was already logged at builder time; no further validation is warranted).
2. A guard rejecting non-empty `joinPath` when a reference is present, until the lift case ships.

```java
private void validateComputedField(ComputedField field, List<ValidationError> errors) {
    if (field.method() == null) {
        return;  // legacy no-arg @externalField: deprecation warning logged at builder time
    }
    if (!field.joinPath().isEmpty()) {
        errors.add(new ValidationError(RejectionKind.DEFERRED,
            field.qualifiedName(),
            "Field '" + field.qualifiedName() + "': @externalField with a @reference path "
                + "(condition-join lift form) is not yet supported - see "
                + "graphitron-rewrite/roadmap/stub-non-table-scalar-child-leaves.md",
            field.location()));
        return;
    }
    validateReferencePath(field.qualifiedName(), field.location(), field.joinPath(), errors);
}
```

### Tests

Unit tier (`graphitron/src/test/`):
- `FetcherEmitterTest`: assert `dataFetcherValue` for a `ComputedField` emits `new ColumnFetcher<>(DSL.field("<name>"))`.
- `TypeClassGeneratorTest`: assert `$fields()` arm emits `<Class>.<method>(table).as("<name>")`.
- `GraphitronSchemaBuilderTest.computedFieldClassification`: extend the existing `ComputedFieldCase` enum with:
  - a `WITH_REFERENCE` case asserting `field.method()` reflects against fixture `FilmExtensions.isEnglish` correctly.
  - a `WITHOUT_REFERENCE` case asserting `field.method()` is null and a deprecation warning was logged.
  - a failure case (method not found during reflection) returning `UnclassifiedField(AUTHOR_ERROR)`.
- `ComputedFieldValidationTest`: keep the two existing stubbed cases by toggling them to expect "no errors" once the variant moves to `IMPLEMENTED_LEAVES`. Add a `WITH_LIFT_CONDITION` case asserting the new `DEFERRED` rejection. Add a `LEGACY_NO_ARG` case asserting no validation errors (the validator short-circuits on null method).

Pipeline tier (`FetcherPipelineTest`): run the classifier+generator pipeline on a small schema with one `@externalField` and assert the generated `Film.$fields()` body contains the inlined call and the wiring registers a `ColumnFetcher`.

Compile tier: a pipeline test outputs the full generator artifacts; the existing compile harness verifies the generated sources compile against the test fixture.

Execute tier: add a `GraphQLQueryTest` query selecting the computed field and asserting the value matches the expression evaluated against PostgreSQL. Concretely, project `isEnglish` and confirm the resolver returns `true` for English-language films and `false` otherwise.

### Fixture

Add a fixture extension class to `graphitron-fixtures`:

```java
package no.sikt.graphitron.rewrite.test.extensions;

public final class FilmExtensions {
    private FilmExtensions() {}

    public static Field<Boolean> isEnglish(Film table) {
        return DSL.iif(table.LANGUAGE_ID.eq(1), DSL.inline(true), DSL.inline(false));
    }
}
```

Add the schema field to `graphitron-test/src/main/resources/graphql/schema.graphqls`:

```graphql
type Film @table(name: "film") {
    ...
    isEnglish: Boolean @externalField(reference: {
        className: "no.sikt.graphitron.rewrite.test.extensions.FilmExtensions",
        method: "isEnglish"
    })
}
```

### Open questions

1. **`namedReferences` shorthand**: should `@externalField` accept the deprecated `name:` form via `RewriteContext.namedReferences()` for parity with `@service`? Default: yes, since `parseExternalRef` already supports it; no extra work.
2. **Return-type strictness for enums**: resolved. `ScalarTypeRef.javaType()` already returns the enum's Java class when the `@enum` directive is bound to a class, and falls back to `String.class` when unbound. The `reflectExternalField` check calls `scalarTypeRef.javaType()` to obtain the expected type argument for `Field<X>`; no extra enum-resolution logic is needed.
3. **Lift case scoping**: the legacy supports an `@externalField` on a non-`@table` parent type by lifting through a join path. None of the current rewrite fixtures or production schemas under inspection use this shape; the deferred rejection above keeps the surface honest. Extract a follow-up roadmap item if a real schema needs it.

### Roadmap entry update

After ComputedField ships:

- `code-generation-triggers.md` line 171: change "Column method in `wiring()` (developer supplies `Field<?>`)" to mention the new directive argument shape and link the runtime contract.
- `directive reference` (currently legacy README): note that `@externalField` now takes an optional `reference: ExternalCodeReference` argument and that the no-argument form is deprecated.
- The matching changelog entry lands in `roadmap/changelog.md` on Done.

---

## Track 2: `ColumnReferenceField` (Backlog)

`@reference` on a scalar field, mapping the field to an FK column on the parent table. Plan body pending.

## Track 3: `TableMethodField` (Backlog)

`@tableMethod` returning a non-table type (scalar / enum). The `@tableMethod` directive's reflection path is already in place; this track repurposes the call site against a `Field<?>`-typed result. Plan body pending.

## Track 4: `ServiceRecordField`

### Decision

`ServiceRecordField` is structurally identical to `ServiceTableField` going in:
same `BatchKey` machinery (List/Set container axis cross Row/Record key-shape axis),
same `Sources` parameter shape on the developer's service method, same DataLoader
plumbing (`newDataLoader` for positional, `newMappedDataLoader` for mapped). It
diverges going out: `ServiceTableField` lifts the resulting `Record` into a
Graphitron-projected query via `$fields()`, while `ServiceRecordField` returns the
developer's value directly to graphql-java, which dispatches subfield resolution
through normal wiring (default `PropertyDataFetcher` on backing-class accessors,
or registered fetchers for further `@service` / `@table` chains).

The track ships in two phases mirroring the `ServiceTableField` cadence:

- **Phase A (this spec)**: classification, BatchKey on the variant, DataLoader
  registration, lambda + key-extraction emission, rows-method stub. The fetcher
  compiles and the schema validates; the rows method throws
  `UnsupportedOperationException` at request time.
- **Phase B (deferred to [`service-rows-method-body.md`](service-rows-method-body.md))**:
  fill the rows-method body for both `ServiceTableField` and `ServiceRecordField`.
  The "call the developer's service and return the value" form for
  `ServiceRecordField` is the simpler of the two (no projection step), so it slots
  cleanly into that follow-up.

Scope guards: `@record`-typed parents (Site 1 in `FieldBuilder.classifyChildFieldOnResultType`)
and non-empty `joinPath` are rejected with `DEFERRED` for now, mirroring Track 1's
approach to the `@externalField` lift case.

In passing, Phase A also corrects the element-shape carry-through note in
[`set-parent-keys-on-service.md`](set-parent-keys-on-service.md): rather than
re-reflecting the service method later to recover whether the user wrote
`Set<TableRecord>` or `Set<RowN<...>>`, the classifier captures the element type
at the same site as the `BatchKey` and surfaces it on `MethodRef.Param.Sourced`.
See "Sources element-type capture" inside the Model section.

#### Phase A trade-off

Phase A removes the `[deferred]` validator gate on `ServiceRecordField`, so
schemas that today fail validation start compiling cleanly and codegen
succeeds. The rows-method stub still throws `UnsupportedOperationException` at
request time, so any query that *selects* a `ServiceRecordField` errors at
runtime instead of being rejected at validation. Callers who route around the
field (don't select it) succeed; callers who select it get a runtime error
pointing at this roadmap item.

This is the same trade-off `ServiceTableField`'s Phase A made. The alternative,
keeping the validator gate but switching it to a non-fatal warning while
codegen skips the variant, is uniformly attractive but is its own design
problem and would land across both variants together. Deferred for now.

### Model: `BatchKey` on `ServiceRecordField`

`graphitron/src/main/java/no/sikt/graphitron/rewrite/model/ChildField.java`:

```java
record ServiceRecordField(
    String parentTypeName,
    String name,
    SourceLocation location,
    ReturnTypeRef returnType,
    List<JoinStep> joinPath,
    MethodRef method,
    BatchKey batchKey  // NEW
) implements ChildField, MethodBackedField, BatchKeyField {

    @Override
    public String rowsMethodName() {
        return "load" + Character.toUpperCase(name().charAt(0)) + name().substring(1);
    }
}
```

Same `load<X>` naming convention as `ServiceTableField` for consistency with the
existing emitter dispatch. Adding `BatchKeyField` to the `implements` list lets
generators pattern-match on the shared interface (already used by
`TypeFetcherGenerator`'s service / split-query arms via
`GeneratorUtils.keyElementType(BatchKey)` and friends).

#### Element-type derivation (resolves the `set-parent-keys-on-service.md` footnote)

The `set-parent-keys-on-service.md` spec defers element-type recovery to the Phase B
body emitter, which would re-reflect the service method. That deferral is closed here
without modifying `MethodRef.Param.Sourced`: `serviceRecordElementType(field)` derives
the Java element type directly from the field's `ReturnTypeRef`, mirroring
`computeServiceRecordReturnType(QueryServiceRecordField)` at `TypeFetcherGenerator.java:920`:

- `ResultReturnType` with non-null `fqClassName`: element is `ClassName.bestGuess(fqClassName)`.
- All other cases: element is `field.method().returnType()`, the reflected return type
  already captured on `MethodRef`.

No change to `MethodRef.Param.Sourced` is needed for Phase A. Phase B's body emitter
will determine the correct container-unwrapping logic (the `Map<KeyType, V>` call shape)
from `batchKey` and the schema-side return type, without needing a separate V field on
`Sourced`.

### Builder: derive and attach `BatchKey`; reject deferred shapes

`FieldBuilder.classifyChildFieldOnTableType` (Site 2, around line 2009): the existing
`resolveServiceField(...)` call already produces a `MethodRef` whose `Sources` parameter
carries a `BatchKey`. Lift that key using `extractBatchKey(MethodRef)`, the private
helper at `FieldBuilder.java:164` already used by the `ServiceTableField` arms at the
same site:

```java
var batchKey = extractBatchKey(svcResult.method());
if (batchKey == null) {
    return new UnclassifiedField(parentTypeName, name, location, fieldDef,
        RejectionKind.AUTHOR_ERROR,
        "@service on a child field requires a Sources parameter (List/Set of "
            + "Row/Record keys); see roadmap/stub-non-table-scalar-child-leaves.md");
}
return switch (returnType) {
    case ReturnTypeRef.ResultReturnType r ->
        new ServiceRecordField(parentTypeName, name, location, r, servicePath.elements(), svcResult.method(), batchKey);
    case ReturnTypeRef.ScalarReturnType s ->
        new ServiceRecordField(parentTypeName, name, location, s, servicePath.elements(), svcResult.method(), batchKey);
    // existing TableBoundReturnType arm continues to produce ServiceTableField
};
```

#### Strict return-type validation

`ServiceTableField` enforces that the developer's reflected return type matches
the schema's declared `ReturnTypeRef` element (via
`FieldBuilder.computeExpectedServiceReturnType`). The same enforcement must run
on `ServiceRecordField`, using `serviceRecordElementType` (see the Generator
section) to derive the expected Java type from the schema:

- `String` field: expected element is `String`. Method shape is `Map<KeyType, String>` (mapped) /
  `List<String>` (positional).
- `Boolean` field: expected element is `Boolean`. Same shape rules.
- `@record`-backed `FilmDetails` field: expected element is `no.example.FilmDetails`
  (from `ResultReturnType.fqClassName()`); `@record` backing class matched by FQN.

Mismatches surface as classification-time `AUTHOR_ERROR`, not compile-tier.

`FieldBuilder.classifyChildFieldOnResultType` (Site 1, lines 1857-1859): the parent
is a `@record`. Deriving a batch key here would require lifting through the
parent chain to the rooted `@table` whose PK provides the key columns, which is
its own design problem (parallel to Track 5's interface-union dispatch). Reject
for now until a real schema needs it:

```java
return new UnclassifiedField(parentTypeName, name, location, fieldDef,
    RejectionKind.DEFERRED,
    "@service on a @record-typed parent is not yet supported; the batch key "
        + "must be lifted through the parent chain to the rooted @table. See "
        + "roadmap/stub-non-table-scalar-child-leaves.md.");
```

Site 1's `ScalarReturnType` and `ResultReturnType` arms become a single `DEFERRED`
rejection; the `TableBoundReturnType` arm at the same site already routes to a
different code path and is unaffected.



### Generator: `buildServiceRecordFetcher` + stub rows method

`graphitron/src/main/java/no/sikt/graphitron/rewrite/generators/TypeFetcherGenerator.java`:

The data fetcher's body is the same shape as `buildServiceDataFetcher`
(`ServiceTableField`): build the DataLoader name, register / look up the loader,
extract the parent key, return `loader.load(key, env)`. The only difference is the
DataLoader value type, which is the field's element Java type rather than `Record`.

```java
private static MethodSpec buildServiceRecordFetcher(
        String fieldName,
        ChildField.ServiceRecordField field,
        TableRef parentTable,
        String jooqPackage) {

    boolean isList = field.returnType().wrapper().isList();
    TypeName elementType = serviceRecordElementType(field);
    TypeName valueType   = isList ? ParameterizedTypeName.get(LIST, elementType) : elementType;
    TypeName returnType  = ParameterizedTypeName.get(COMPLETABLE_FUTURE, valueType);

    var batchKey = field.batchKey();
    boolean isMapped = batchKey instanceof BatchKey.MappedRowKeyed
                    || batchKey instanceof BatchKey.MappedRecordKeyed;
    TypeName keyType    = GeneratorUtils.keyElementType(batchKey);
    TypeName loaderType = ParameterizedTypeName.get(DATA_LOADER, keyType, valueType);
    String factoryMethod = isMapped ? "newMappedDataLoader" : "newDataLoader";
    TypeName lambdaKeysType = ParameterizedTypeName.get(isMapped ? SET : LIST, keyType);

    var lambdaBlock = CodeBlock.builder()
        .add("($T keys, $T batchEnv) -> {\n", lambdaKeysType, BATCH_LOADER_ENV)
        .indent()
        .addStatement("$T dfe = ($T) batchEnv.getKeyContextsList().get(0)", ENV, ENV)
        .addStatement("$T sel = dfe.getSelectionSet().getField($S)", SELECTED_FIELD, fieldName)
        .addStatement("return $T.completedFuture($L(keys, dfe, sel))", COMPLETABLE_FUTURE, field.rowsMethodName())
        .unindent()
        .add("}")
        .build();

    var methodBuilder = MethodSpec.methodBuilder(fieldName)
        .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
        .returns(returnType)
        .addParameter(ENV, "env")
        .addCode(buildDataLoaderName())
        .addCode(
            "$T loader = env.getDataLoaderRegistry()\n" +
            "    .computeIfAbsent(name, k -> $T.$L($L));\n",
            loaderType, DATA_LOADER_FACTORY, factoryMethod, lambdaBlock);
    methodBuilder.addCode(GeneratorUtils.buildKeyExtraction(batchKey, parentTable, jooqPackage));
    return methodBuilder.addStatement("return loader.load(key, env)").build();
}
```

The `serviceRecordElementType(field)` helper mirrors `computeServiceRecordReturnType(QueryServiceRecordField)` at `TypeFetcherGenerator.java:920`:

- `ResultReturnType` with non-null `fqClassName`: element is `ClassName.bestGuess(fqClassName)`.
- All other cases: element is `field.method().returnType()`, the reflected return type already on `MethodRef`.

Data fetchers return Java types; graphql-java's registered scalar coercing handles
the GraphQL-side coercion at output. No GraphQL-scalar-to-Java mapping table is
needed in the generator.

The rows-method stub mirrors `buildServiceRowsMethod` (`ServiceTableField`),
parameterised by the element type:

```java
private static MethodSpec buildServiceRecordRowsMethod(ChildField.ServiceRecordField field) {
    boolean isList = field.returnType().wrapper().isList();
    boolean isMapped = field.batchKey() instanceof BatchKey.MappedRowKeyed
                    || field.batchKey() instanceof BatchKey.MappedRecordKeyed;

    TypeName keyElement = GeneratorUtils.keyElementType(field.batchKey());
    TypeName keysContainer = ParameterizedTypeName.get(isMapped ? SET : LIST, keyElement);

    TypeName element = serviceRecordElementType(field);
    TypeName perKey  = isList ? ParameterizedTypeName.get(LIST, element) : element;
    TypeName returnType = isMapped
        ? ParameterizedTypeName.get(MAP, keyElement, perKey)
        : (isList ? ParameterizedTypeName.get(LIST, ParameterizedTypeName.get(LIST, element))
                  : ParameterizedTypeName.get(LIST, element));

    return MethodSpec.methodBuilder(field.rowsMethodName())
        .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
        .returns(returnType)
        .addParameter(keysContainer, "keys")
        .addParameter(ENV, "env")
        .addParameter(SELECTED_FIELD, "sel")
        .addStatement("throw new $T()", UnsupportedOperationException.class)
        .build();
}
```

### `TypeFetcherGenerator` cleanup

1. Remove the `Map.entry(ChildField.ServiceRecordField.class, ...)` row from
   `NOT_IMPLEMENTED_REASONS`.
2. Add `ChildField.ServiceRecordField.class` to `IMPLEMENTED_LEAVES`.
3. Replace the stub arm in the dispatch switch:
   ```java
   case ChildField.ServiceRecordField f -> {
       builder.addMethod(buildServiceRecordFetcher(f.name(), f, parentTable, jooqPackage));
       builder.addMethod(buildServiceRecordRowsMethod(f));
   }
   ```

Both methods land on the same `<TypeName>Fetchers` class as the `ServiceTableField`
fetcher / rows method; no new top-level emitter is introduced.

### Validator

`GraphitronSchemaValidator.validateServiceRecordField` (line 660) currently runs
only `validateReferencePath`. Add a guard rejecting non-empty `joinPath`:

```java
private void validateServiceRecordField(ChildField.ServiceRecordField field,
        Map<String, GraphitronType> types, List<ValidationError> errors) {
    if (!field.joinPath().isEmpty()) {
        errors.add(new ValidationError(RejectionKind.DEFERRED,
            field.qualifiedName(),
            "Field '" + field.qualifiedName() + "': @service with a @reference path "
                + "(condition-join lift form) is not yet supported; see "
                + "graphitron-rewrite/roadmap/stub-non-table-scalar-child-leaves.md",
            field.location()));
        return;
    }
    validateReferencePath(field.qualifiedName(), field.location(), field.joinPath(), errors);
}
```

The variant-implementation gate (`validateVariantIsImplemented`,
`GraphitronSchemaValidator.java:147`) reads `NOT_IMPLEMENTED_REASONS.keySet()`;
removing the entry per the generator-cleanup step above is what flips the
`[deferred]` error off for this field class.

### Tests

Unit tier (`graphitron/src/test/`):

- `ServiceCatalogTest`: extend the existing `reflectServiceMethod_*` cases with one
  per `BatchKey` variant cross scalar/record return: e.g.
  `reflectServiceMethod_setOfRow1Sources_scalarReturn_classifiedAsServiceRecord`.
  The classifier itself is shared with `ServiceTableField`; what we're verifying is
  that the builder routes scalar/record returns to `ServiceRecordField` while
  preserving the `BatchKey` derivation. This needs builder-level coverage, not
  catalog-level (the catalog already classifies sources).
- `GraphitronSchemaBuilderTest`: extend the existing `SERVICE_FIELD_ON_RESULT_TYPE`
  case to assert (a) the field is `ServiceRecordField`, (b) it carries the expected
  `BatchKey` for the parent's PK, (c) it implements `BatchKeyField`. Add a
  `SERVICE_FIELD_ON_RECORD_PARENT_DEFERRED` case asserting the new `DEFERRED`
  rejection for the @record-typed parent shape.
- `TypeFetcherGeneratorTest`: add `serviceRecordField_*` fixtures mirroring the
  `serviceField_*` fixtures shipped under
  [`set-parent-keys-on-service.md`](set-parent-keys-on-service.md):
  - `serviceRecordField_scalar_list_dataFetcherReturnsCompletableFutureListString`
    (or whichever scalar shape; assert `CompletableFuture<List<...>>`).
  - `serviceRecordField_scalar_single_dataFetcherReturnsCompletableFutureString`.
  - `serviceRecordField_recordBacked_list_dataFetcherReturnsCompletableFutureListPojo`.
  - `serviceRecordField_mappedRow_list_dataFetcherCallsNewMappedDataLoaderWithSetKeys`.
  - `serviceRecordField_mappedRow_list_rowsMethodReturnsMapToListOfElement`.

The `newDataLoader` vs `newMappedDataLoader` factory selection is shared
infrastructure (factory-method-string + `BuildKeyExtraction`) covered by
`ServiceTableField`'s test surface; no parallel regression on
`ServiceRecordField` is needed.

Add one classifier-level case in `ServiceCatalogTest` asserting V capture on
`MethodRef.Param.Sourced`: e.g. for a method `Map<Row1<Integer>, String>
forkortelser(Set<Row1<Integer>> ids, DSLContext dsl)`, the resulting Sourced
param's `elementType` is `String`. Cover both batched containers and the
backed-`@record` case.

Pipeline tier: a `SchemaToFetchersPipelineTest` case running classifier + generator
on a small schema with one scalar-returning `@service` child field on a `@table`
parent, asserting (a) the generated fetcher class compiles, (b) it contains the
expected `newDataLoader` / `newMappedDataLoader` call.

Compilation tier (`graphitron-test`, `<release>17</release>`): the existing
compile harness picks up the new fetcher + rows method automatically once the
fixture below is in place.

Execution tier: deferred to Phase B. The rows method throws at request time, so
no end-to-end query test can assert a value yet. Phase B
(`service-rows-method-body.md`) adds a `GraphQLQueryTest` case selecting a scalar
`@service` field and asserting the value.

### Fixture

The fixture below exists for classification + reflection-time discovery only.
Phase A does not execute the rows method; the stub's `UnsupportedOperationException`
mirrors the generated rows-method body. Phase B replaces the body with the real
`Service.method(keys, ...)` call.

Add to `graphitron-test/src/main/resources/graphql/schema.graphqls`:

```graphql
extend type Film @table(name: "film") {
    titleUppercase: String @service(service: {
        className: "no.sikt.graphitron.rewrite.test.services.FilmService",
        method: "titleUppercase"
    })
}
```

And the corresponding stub in `graphitron-test`:

```java
public final class FilmService {
    private FilmService() {}

    /** Phase A: signature only; the body throws to mirror the rows-method stub. */
    public static java.util.Map<org.jooq.Row1<Integer>, String> titleUppercase(
            java.util.Set<org.jooq.Row1<Integer>> filmIds, org.jooq.DSLContext dsl) {
        throw new UnsupportedOperationException();
    }
}
```

The fixture compiles and is reachable from the schema; Phase B fills the body.

### Out of scope

- **Rows-method body** (Phase B): folded into
  [`service-rows-method-body.md`](service-rows-method-body.md). For
  `ServiceRecordField` the body shape is "call the developer's method, return the
  values directly" with no projection step, simpler than the `ServiceTableField`
  body. Both shapes share infrastructure (DSLContext local, arg-call emission via
  `ArgCallEmitter.buildMethodBackedCallArgs`, scatter-result-into-Map for
  positional variants), so they ship together.
- **`@record`-typed parents.** Site 1 in `FieldBuilder` returns `DEFERRED` for the
  scalar/record-return arms. The blocker is "what's the batch key when the parent
  is a `@record`?". The `@record` parent might itself be reachable via a
  `@table`-rooted chain whose terminal node has a derivable PK, but that's a
  separate design problem (see also Track 5's interface-union dispatch).
- **`MutationServiceRecordField`.** Tracked under
  [`mutations.md`](mutations.md), unaffected by this track.
- **Non-empty `joinPath`.** `DEFERRED` rejection on the validator surface;
  re-promote when a real schema needs the lift form.

### Open questions

None remaining. The element-type derivation question (how to surface V without
modifying `MethodRef.Param.Sourced` or changing `classifySourcesType`'s return type)
is resolved: `serviceRecordElementType` reads the schema-side `ReturnTypeRef` directly,
mirroring the existing `computeServiceRecordReturnType` at line 920.

### Roadmap entry update

After Phase A ships:

- This file: collapse Track 4's body into a one-line "shipped at `<sha>`" pointer
  and a brief note about what landed (DataLoader plumbing + stub body); the
  remaining work cross-references `service-rows-method-body.md`.
- `changelog.md`: standard "Done" entry.
- The umbrella status stays Spec until all five tracks ship; do not flip the file
  to Done on individual track completion.

## Track 5: `MultitableReferenceField` (Backlog)

`@reference` on a scalar field whose target is a multi-table interface or union. Plan body pending; depends on Track B of `stub-interface-union-fetchers.md` for the dispatch design.

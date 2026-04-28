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

Each variant is independent. The five tracks below ship in any order. The plan body for `ComputedField` is fully spec'd; the other four remain Backlog inside this umbrella, each waiting for its own author to draft a plan.

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
directive @externalField(reference: ExternalCodeReference!) on FIELD_DEFINITION
```

`ExternalCodeReference` already ships for `@service` etc., with `className` (or deprecated `name` resolved through `RewriteContext.namedReferences()`) and `method`.

A new constant `ARG_EXTERNAL_FIELD_REF = "reference"` lands in `BuildContext`.

**Migration**: schemas using legacy `@externalField` (no argument) fail loudly with an `AUTHOR_ERROR` "missing required argument 'reference'" produced by graphql-java's directive validation. Hand-migration is mechanical: every existing call site has a known method in a known class (in `externalReferenceImports`). A separate migration shim is out of scope for this track.

### Model: `ComputedField` carries a `MethodRef`

`graphitron/src/main/java/no/sikt/graphitron/rewrite/model/ChildField.java`:

```java
record ComputedField(
    String parentTypeName,
    String name,
    SourceLocation location,
    ReturnTypeRef returnType,
    List<JoinStep> joinPath,
    MethodRef method  // NEW
) implements ChildField {}
```

The `MethodRef.Basic` carries the captured return type (always `Field<X>` post-reflection) and one `MethodRef.Param.Typed` whose `ParamSource` is `ParamSource.Table`.

### Builder: reflect, classify, attach

`FieldBuilder.classifyChildField` `if (fieldDef.hasAppliedDirective(DIR_EXTERNAL_FIELD))` arm (line 2019) currently wraps with empty path placeholder. Replace with:

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
case ChildField.ComputedField cf -> {
    var ref = cf.method();
    var refClass = ClassName.bestGuess(ref.className());
    builder.addCode("        case $S -> fields.add($T.$L($L).as($S));\n",
        cf.name(), refClass, ref.methodName(), tableArg, cf.name());
}
```

Generated code for `Film.isEnglish`:

```java
case "isEnglish" -> fields.add(FilmExtensions.isEnglish(table).as("isEnglish"));
```

The `.as("<name>")` alias is required so the wiring side can look the field up by name in the result Record (see below).

### Wiring: `ColumnFetcher` + `DSL.field(<name>)`

`FetcherEmitter.dataFetcherValue` (line 51) gains an arm matching `ChildField.ComputedField`:

```java
if (field instanceof ChildField.ComputedField cf) {
    var columnFetcherClass = ClassName.get(outputPackage + ".util",
        ColumnFetcherClassGenerator.CLASS_NAME);
    return CodeBlock.of("new $T<>($T.field($S))", columnFetcherClass, DSL, cf.name());
}
```

Generated code:

```java
.dataFetcher("isEnglish", new ColumnFetcher<>(DSL.field("isEnglish")))
```

The lookup-by-name pattern is the same one already used for `ChildField.LookupTableField` and the list-cardinality `ChildField.TableField` arms in `FetcherEmitter` (lines 87 and 92). The alias applied in `$fields()` makes the lookup unambiguous.

### `TypeFetcherGenerator` cleanup

`graphitron/src/main/java/no/sikt/graphitron/rewrite/generators/TypeFetcherGenerator.java`:

1. Remove the `Map.entry(ChildField.ComputedField.class, ...)` from `NOT_IMPLEMENTED_REASONS` (lines 254-255).
2. Add `ChildField.ComputedField.class` to `IMPLEMENTED_LEAVES` (around line 145).
3. Replace the `case ChildField.ComputedField f -> builder.addMethod(stub(f));` arm (line 395) with a no-op:
   ```java
   case ChildField.ComputedField ignored -> { }  // wired via FetcherEmitter; projected via $fields
   ```
4. Update the `IMPLEMENTED_LEAVES` Javadoc reference list (line 51) to mention `ChildField.ComputedField` alongside `ChildField.ColumnField`.

### Validator

`GraphitronSchemaValidator.validateComputedField` (line 680) already validates the join path; no change is needed beyond removing the variant from the stub gate (which reads `NOT_IMPLEMENTED_REASONS.keySet()`).

Add a guard rejecting non-empty `joinPath` until the lift case ships:

```java
private void validateComputedField(ComputedField field, List<ValidationError> errors) {
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
- `GraphitronSchemaBuilderTest.computedFieldClassification`: extend the existing `ComputedFieldCase` enum with a case asserting `field.method()` reflects against a fixture `FilmExtensions.isEnglish` correctly. Add a failure case (method missing) returning `UnclassifiedField(AUTHOR_ERROR)`.
- `ComputedFieldValidationTest`: keep the two existing stubbed cases by toggling them to expect "no errors" once the variant moves to `IMPLEMENTED_LEAVES`. Add a `WITH_LIFT_CONDITION` case asserting the new `DEFERRED` rejection.

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
2. **Return-type strictness for enums**: the rewrite emits `Field<EnumClass>` for enum scalars after `@enum` resolution. The reflection check needs to consume the enum's Java class via the existing `@enum` resolution path. If the schema's enum is not bound to a Java class, fall back to accepting `Field<String>`.
3. **Lift case scoping**: the legacy supports an `@externalField` on a non-`@table` parent type by lifting through a join path. None of the current rewrite fixtures or production schemas under inspection use this shape; the deferred rejection above keeps the surface honest. Extract a follow-up roadmap item if a real schema needs it.

### Roadmap entry update

After ComputedField ships:

- `code-generation-triggers.md` line 171: change "Column method in `wiring()` (developer supplies `Field<?>`)" to mention the new directive argument shape and link the runtime contract.
- `directive reference` (currently legacy README): note that `@externalField` now takes a `reference: ExternalCodeReference!` argument.
- The matching changelog entry lands in `roadmap/changelog.md` on Done.

---

## Track 2: `ColumnReferenceField` (Backlog)

`@reference` on a scalar field, mapping the field to an FK column on the parent table. Plan body pending.

## Track 3: `TableMethodField` (Backlog)

`@tableMethod` returning a non-table type (scalar / enum). The `@tableMethod` directive's reflection path is already in place; this track repurposes the call site against a `Field<?>`-typed result. Plan body pending.

## Track 4: `ServiceRecordField` (Backlog)

`@service` returning a non-table type (scalar / enum / `@record`). Plan body pending.

## Track 5: `MultitableReferenceField` (Backlog)

`@reference` on a scalar field whose target is a multi-table interface or union. Plan body pending; depends on Track B of `stub-interface-union-fetchers.md` for the dispatch design.

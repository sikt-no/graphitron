---
id: R48
title: "Stub: `@externalField` resolved-reference path (`ComputedField`)"
status: Spec
bucket: stubs
priority: 2
theme: service
depends-on: []
---

# Stub: `@externalField` resolved-reference path (`ComputedField`)

Lift `ChildField.ComputedField` out of `TypeFetcherGenerator.NOT_IMPLEMENTED_REASONS`. `@externalField` is wired via `ColumnFetcher` and projected through `$fields()` exactly like `ColumnField`: the developer supplies a static method returning `Field<?>`, the generator invokes it at codegen time, inlines the returned expression aliased to the field name in `$fields()`, and registers a `ColumnFetcher` keyed on `DSL.field("<name>")` for the wiring side. The developer reference is resolved through an `ExternalCodeReference` directive argument, bringing `@externalField` in line with `@service`, `@tableMethod`, and `@condition`.

The first iteration covers the no-`@reference` case (the legacy's primary use case). The lift case (developer method resolved through a `joinPath` of one or more `ConditionJoin` hops) is deferred; the validator rejects non-empty `joinPath` with a `DEFERRED` rejection until a real schema needs it.

**Legacy no-arg form is not supported.** The legacy generator scanned `externalReferenceImports` to discover the method by name; the rewrite drops this. `@externalField` without a `reference` argument is rejected at classification time with `AUTHOR_ERROR`. Existing schemas (49 known usages in downstream Sikt projects) migrate by adding the `reference` argument; Phase 5 LSP completion (see "Roadmap entry update" below) suggests matching methods.

## Schema-side change: `@externalField` gains a `reference` argument

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

`ExternalCodeReference` already ships for `@service` etc., with `className` (or deprecated `name` resolved through `RewriteContext.namedReferences()`) and `method`. The `reference` argument is **non-null** on the schema side: omitting it is a graphql-java-side parse error.

A new constant `ARG_EXTERNAL_FIELD_REF = "reference"` lands in `BuildContext`.

**Existing test fixtures**: `GraphitronSchemaBuilderTest:1225` uses `@externalField` no-arg in the `@externalField on a @table parent → ComputedField` case; update the SDL to add `reference: { className: ..., method: ... }` and switch the assertion to assert classification as `ComputedField` with a populated `MethodRef`. The conflict-test fixtures at lines 3637 and 3672 still use `@externalField` no-arg; those test that mutually-exclusive directives are detected at the directive-presence level, which fires before reference-argument validation, so they continue to pass unchanged. Verify post-implementation.

## Model: `ComputedField` carries a non-null `MethodRef`

`graphitron/src/main/java/no/sikt/graphitron/rewrite/model/ChildField.java`:

```java
/** Resolved @externalField with a static method reference. */
record ComputedField(
    String parentTypeName,
    String name,
    SourceLocation location,
    ReturnTypeRef returnType,
    List<JoinStep> joinPath,
    MethodRef method  // non-null
) implements ChildField, MethodBackedField {}
```

The `MethodRef.Basic` carries the captured return type (always `Field<X>` post-reflection) and one `MethodRef.Param.Typed` whose `ParamSource` is `ParamSource.Table`.

## Builder: parse, reflect, attach

`FieldBuilder.classifyChildField` `if (fieldDef.hasAppliedDirective(DIR_EXTERNAL_FIELD))` arm (~line 2019):

1. `parseExternalRef(parentTypeName, fieldDef, DIR_EXTERNAL_FIELD, ARG_EXTERNAL_FIELD_REF)`. Argument absent or unresolvable -> `UnclassifiedField(AUTHOR_ERROR, "@externalField requires a reference argument: reference: {className: \"<FQN>\", method: \"<methodName>\"} where the method is public static Field<X> methodName(<ParentTable> table)")`. Lookup-resolution failures (FQN not on classpath, method not found) surface the existing `parseExternalRef` failure messages.
2. Reflect via a new `ServiceCatalog.reflectExternalField(className, methodName, parentTableRecordClass, expectedScalarType)`:
   - Locate the public static method by name.
   - Assert the single parameter is assignable from the parent's jOOQ generated `Table<?>` class.
   - Assert the return type is parameterised `org.jooq.Field`. Capture the type argument; reject if it is not assignable from the GraphQL scalar's runtime Java type. (`ScalarTypeRef.javaType()` already exposes this.)
   - On success, return a `MethodRef.Basic` whose params list is `[new MethodRef.Param.Typed("table", "<TableType>", new ParamSource.Table())]` and whose return type is the declared `Field<X>`.
3. Build `ComputedField` with `joinPath = externalPath.elements()` (existing path parsing) and `method = result.ref()`.
4. **Alias-collision check.** The wiring side looks the field up by name via `DSL.field("<name>")` against the result Record (see Wiring section below). If the GraphQL field name collides with a real SQL column on the parent `@table`, the alias shadows it and `ColumnFetcher` resolves to the wrong value. Reject with `UnclassifiedField(AUTHOR_ERROR, "@externalField name 'X' collides with column 'X' on table '<TableName>'; rename the GraphQL field or use @field(name: ...) to disambiguate")`. Lookup is `JooqCatalog.findColumn(parentTable, fieldName)`.

The reflection helper mirrors `reflectTableMethod` (line 255 in `ServiceCatalog`). The strict return-type check is necessary so the inlined call expression compiles in the generated `$fields()` body (the projection list is `List<Field<?>>`).

## `$fields()` projection: inlined aliased call

`TypeClassGenerator.emitSelectionSwitch` (line 225) gains a single arm:

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

## Wiring: `ColumnFetcher` + `DSL.field(<name>)`

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

## `TypeFetcherGenerator` cleanup

`graphitron/src/main/java/no/sikt/graphitron/rewrite/generators/TypeFetcherGenerator.java`:

1. Remove the `Map.entry(ChildField.ComputedField.class, ...)` from `NOT_IMPLEMENTED_REASONS`.
2. Add `ChildField.ComputedField.class` to `IMPLEMENTED_LEAVES`.
3. Replace the `case ChildField.ComputedField f -> builder.addMethod(stub(f));` arm with `case ChildField.ComputedField cf -> { }` (wired via FetcherEmitter; projected via `$fields()`).
4. Update the `IMPLEMENTED_LEAVES` Javadoc reference list (line 51) to mention `ChildField.ComputedField` alongside `ChildField.ColumnField`.

## Validator

`GraphitronSchemaValidator.validateComputedField` (~line 680) gains a guard rejecting non-empty `joinPath` until the lift case ships:

```java
private void validateComputedField(ComputedField field, List<ValidationError> errors) {
    if (!field.joinPath().isEmpty()) {
        errors.add(new ValidationError(RejectionKind.DEFERRED,
            field.qualifiedName(),
            "Field '" + field.qualifiedName() + "': @externalField with a @reference path "
                + "(condition-join lift form) is not yet supported - see "
                + "graphitron-rewrite/roadmap/computed-field-with-reference.md",
            field.location()));
        return;
    }
    validateReferencePath(field.qualifiedName(), field.location(), field.joinPath(), errors);
}
```

## Tests

Unit tier (`graphitron/src/test/`):
- `FetcherEmitterTest`: assert `dataFetcherValue` for a `ComputedField` emits `new ColumnFetcher<>(DSL.field("<name>"))`.
- `TypeClassGeneratorTest`: assert `$fields()` arm emits `<Class>.<method>(table).as("<name>")`.
- `GraphitronSchemaBuilderTest.computedFieldClassification`: extend `ComputedFieldCase`:
  - a `WITH_REFERENCE` case asserting `field.method()` reflects against fixture `FilmExtensions.isEnglish` correctly.
  - a `WITH_NAMED_REFERENCE` case asserting the deprecated `name:` form (resolved via `RewriteContext.namedReferences()`) works for `@externalField` parity with `@service`.
  - a `MISSING_REFERENCE` case (no-arg) asserting `UnclassifiedField(AUTHOR_ERROR)` with the "requires a reference argument" message.
  - a `NAME_COLLIDES_WITH_COLUMN` case asserting `UnclassifiedField(AUTHOR_ERROR)` when the GraphQL field name matches a SQL column on the parent `@table` (regression guard for the alias-collision check).
  - a failure case (method not found during reflection) returning `UnclassifiedField(AUTHOR_ERROR)`.
- `ComputedFieldValidationTest`: keep the two existing stubbed cases by toggling them to expect "no errors" once the variant moves to `IMPLEMENTED_LEAVES`. Add a `WITH_LIFT_CONDITION` case asserting the new `DEFERRED` rejection.

Pipeline tier (`FetcherPipelineTest`): run the classifier+generator pipeline on a small schema with one `@externalField` and assert the generated `Film.$fields()` body contains the inlined call and the wiring registers a `ColumnFetcher`.

Compile tier: a pipeline test outputs the full generator artifacts; the existing compile harness verifies the generated sources compile against the test fixture.

Execute tier: add a `GraphQLQueryTest` query selecting the computed field and asserting the value matches the expression evaluated against PostgreSQL. Concretely, project `isEnglish` and confirm the resolver returns `true` for English-language films and `false` otherwise.

## Fixture

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

## Open questions

1. **`namedReferences` shorthand**: should `@externalField` accept the deprecated `name:` form via `RewriteContext.namedReferences()` for parity with `@service`? Default: yes, since `parseExternalRef` already supports it; no extra work.
2. **Return-type strictness for enums**: resolved. `ScalarTypeRef.javaType()` already returns the enum's Java class when the `@enum` directive is bound to a class, and falls back to `String.class` when unbound. The `reflectExternalField` check calls `scalarTypeRef.javaType()` to obtain the expected type argument for `Field<X>`; no extra enum-resolution logic is needed.
3. **Lift case scoping**: the legacy supports an `@externalField` on a non-`@table` parent type by lifting through a join path. None of the current rewrite fixtures or production schemas under inspection use this shape; the deferred rejection above keeps the surface honest. Extract a follow-up roadmap item if a real schema needs it.

## Roadmap entry update

After this ships:

- `code-generation-triggers.md` line 171: change "Column method in `wiring()` (developer supplies `Field<?>`)" to mention the new directive argument shape and link the runtime contract.
- `directive reference` (currently legacy README): note that `@externalField` now requires a `reference: ExternalCodeReference!` argument; the no-argument form is no longer supported.
- `graphitron-lsp.md` Phase 5 exit criteria: add `@externalField` reference-argument completion (LSP indexes `public static Field<X> ?(Table<ParentTable> t)` methods from source roots and offers them as `reference:` completions). The `CompletionData` shape and `ParamSource.Table` taxonomy already accommodate this; only the per-directive dispatch and the source-walk filter need extending.
- The matching changelog entry lands in `roadmap/changelog.md` on Done.

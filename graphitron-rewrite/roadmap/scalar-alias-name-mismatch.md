---
id: R313
title: "Scalar alias name mismatch: @scalarType registers under constant intrinsic name not the SDL name"
status: Spec
bucket: bug
priority: 1
depends-on: []
created: 2026-06-15
last-updated: 2026-06-15
---

# Scalar alias name mismatch: @scalarType registers under constant intrinsic name not the SDL name

> A `@scalarType` (or convention-resolved) scalar whose SDL name differs from the
> intrinsic `getName()` of the `GraphQLScalarType` constant it points at is registered
> in the generated `GraphitronSchema.build()` under the *constant's* name, not the SDL
> name. The schema's field type references use the SDL name, so they bind to nothing and
> `schemaBuilder.build()` fails at runtime with `type <SdlName> not found in schema`. The
> fix carries the constant's intrinsic name on `ScalarResolution.Resolved` and, when it
> differs from the SDL name, registers a renamed alias instead of the raw constant.

## Root cause

The bug bites whenever the SDL scalar name is an *alias* for the resolved constant. The
canonical repro:

----
scalar LocalDate @scalarType(scalar: "graphql.scalars.ExtendedScalars.Date")
----

1. *Assembly* (`GraphitronSchemaBuilder`): graphql-java's `SchemaGenerator` assembles
   `scalar LocalDate` into a `GraphQLScalarType` named `LocalDate` (the SDL name), and every
   field referencing it emits `GraphQLTypeReference.typeRef("LocalDate")`.
2. *Resolution* (`ScalarTypeResolver.resolveFromConstantFqn`): the directive value resolves to
   `ScalarResolution.Resolved(javaType, owner = graphql.scalars.ExtendedScalars, field = "Date")`.
   The record carries the owner/field pair but *not* the constant's intrinsic GraphQL name.
3. *Emit* (`GraphitronSchemaClassGenerator`, the `ScalarResolution.Resolved` arm of the
   `scalarRegistrations` loop): emits `schemaBuilder.additionalType(ExtendedScalars.Date)`.
   Because `ExtendedScalars.Date.getName()` is `"Date"`, graphql-java registers the scalar
   under `Date`. The `typeRef("LocalDate")` references then resolve to nothing, and
   `schemaBuilder.build()` throws `AssertException: type LocalDate not found in schema`.

Downstream consumers (Sikt's own schemas) surface this as `buildSchema` failures, e.g.
`SakMerknaderShapeTest` / `SakSaksdokumenterShapeTest` ("type LocalDate not found in schema").

The existing happy-path coverage (`scalar Money @scalarType(... ScalarConstants.MONEY)`) never
caught this because `MONEY.getName()` coincidentally equals its SDL name `Money`. The defect is
specifically the name *mismatch*, which the extended-scalars `Date` -> `LocalDate` rename triggers.

## Design

Carry the constant's intrinsic GraphQL name on the resolution result, and let the emitter
register a renamed alias only when it diverges from the SDL name.

### `ScalarResolution.Resolved` gains the constant's intrinsic name

----
record Resolved(
    TypeName javaType,
    ClassName scalarConstantOwner,
    String scalarConstantField,
    String scalarName            // new: the constant's intrinsic GraphQLScalarType.getName()
) implements Successful {}
----

`ScalarTypeResolver` already holds the live `GraphQLScalarType` during reflection, so the name
is free to capture at both construction sites:

* `resolveBuiltIn` -> the spec built-in's name (always equal to the SDL name; `GraphQLInt.getName()`
  is `"Int"`, etc.), so built-ins never trip the alias path.
* `resolveFromConstantFqn` -> `check.scalar().getName()` from the validated constant.

Classifying the name at the resolution boundary (where the loader and reflection already ran) and
carrying it as a typed component, rather than re-reflecting at emit time, follows the project's
"classify at the parse boundary, carry the typed result downstream" stance.

### Emitter aliases only on mismatch

In the `scalarRegistrations` loop, the `Resolved` arm compares the SDL name (`reg.sdlName()`,
already in hand) against `r.scalarName()`:

* *Names equal* (built-ins, and convention/`@scalarType` scalars declared under the constant's
  own name): emit the existing `schemaBuilder.additionalType(<owner>.<field>)` verbatim. No
  output change, so existing golden assertions are untouched.
* *Names differ* (genuine alias): emit a renamed registration:

----
schemaBuilder.additionalType(
    GraphQLScalarType.newScalar(ExtendedScalars.Date).name("LocalDate").build());
----

`GraphQLScalarType.newScalar(existing)` copies the coercing, description, and directives, and
`.name(sdlName)` overrides the name, producing a scalar that registers under the SDL name with
the constant's behaviour intact.

### Alternative considered: always alias

Wrapping *every* resolved scalar in `newScalar(constant).name(sdlName).build()` would remove the
need for the new `scalarName` component, but it was rejected: it would re-register copies of the
spec built-ins (`GraphQLString`, ...) under their own names rather than the canonical graphql-java
constants, risking graphql-java's built-in-scalar identity handling, and it would churn every
existing `.additionalType(...)` golden assertion for no behavioural gain. Aliasing only on
mismatch keeps the common path byte-identical and confines the new shape to the case that needs it.

## Implementation sites

* `model/ScalarResolution.java`: add the `scalarName` component to `Resolved`; extend the javadoc
  note on `Resolved` to describe it.
* `ScalarTypeResolver.java`: populate `scalarName` at both `new ScalarResolution.Resolved(...)`
  sites (built-in table name; `check.scalar().getName()` for the constant path).
* `generators/schema/GraphitronSchemaClassGenerator.java`: in the `Resolved` arm of the
  `scalarRegistrations` switch, branch on `reg.sdlName().equals(r.scalarName())` to emit either the
  plain constant reference or the `GraphQLScalarType.newScalar(...).name(...).build()` alias.
* `ServiceCatalogTest.java` and any other test constructing `ScalarResolution.Resolved` directly:
  thread the new component through (compile-only churn).

## Tests

* *Pipeline-tier* (`GraphitronSchemaClassGeneratorTest`, the scalar-registration block around the
  existing Phase-2 cases): add a fixture `scalar LocalDate @scalarType(scalar: "...ExtendedScalars.Date")`
  used by a field, and assert the generated body contains the renamed-alias registration
  (`GraphQLScalarType.newScalar(...).name("LocalDate").build()`) rather than the bare
  `.additionalType(...ExtendedScalars.Date)`. Keep an assertion that a name-matching scalar
  (e.g. the existing `Money` case) still emits the plain constant form, pinning the "alias only on
  mismatch" branch.
* *Execution / build-through*: the real proof is that a schema using the aliased scalar assembles.
  The `mvn install -Plocal-db` pipeline already builds schemas end-to-end; add the aliased scalar
  to a fixture schema whose generated `GraphitronSchema.build()` is exercised so a regression
  reproduces the `type LocalDate not found in schema` failure on the old code and passes on the new.
* *Resolver-tier* (`ScalarTypeResolverTest`): assert `resolveFromConstantFqn` on a constant whose
  name differs from the SDL alias carries the constant's intrinsic `scalarName`; assert built-ins
  carry their spec name.

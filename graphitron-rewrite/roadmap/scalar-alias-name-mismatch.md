---
id: R313
title: "Scalar alias name mismatch: @scalarType registers under constant intrinsic name not the SDL name"
status: Ready
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
> fix routes the mismatch through the existing `ScalarResolution.Synthesised` arm, which
> already registers a scalar under its SDL name by borrowing the constant's coercing; no
> new emitter branch and no new model component are introduced.

## Review feedback (In Review → Ready, 2026-06-15)

Rework requested. The resolver-tier and pipeline-tier coverage and the `mvn install -Plocal-db` build are
green, and the code matches the design (the `Synthesised` reuse, the `getName()` fork in
`resolveFromConstantFqn`, the two `Resolved`→`Successful` widenings, no emitter/model-component additions).
Two items before Done:

1. **Execution / build-through test missing (Tests §3, the named "real proof").** No fixture schema uses an
   aliasing scalar, and no test assembles the generated `GraphitronSchema.build()` for the alias case. The
   pipeline test (`GraphitronSchemaClassGeneratorTest.scalarRegistration_directiveAliasesConstant_...`) only
   string-matches the emitted registration (`.additionalType(scalar_LocalDate())`, `b.name("LocalDate")`).
   For a runtime schema-assembly bug, a string match can pass while `build()` still throws, so the very
   regression this item fixes ("type LocalDate not found in schema") is not pinned by any test that actually
   builds the schema. Add the aliased scalar to a build-through fixture (the `-Plocal-db` pipeline, or a
   focused generate-compile-build test) asserting `GraphitronSchema.build()` succeeds and would fail on the
   pre-fix code. Bears on the spec's own Tests §3 and "compilation/execution against real jOOQ is a test tier".

2. **Stale landing SHA.** The §Implementation note and the `In Review` move-commit (`c1f9f46`) both say
   "shipped at `d82392e`", which is not a commit in the repo; the actual code commit is `adfaeff`. Correct it
   when re-shipping (per "Documentation names only live tests/code").

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

The same mismatch hides in the convention layer: `scalar GraphQLBigDecimal` resolves through
`CONVENTION_TABLE` to `ExtendedScalars.GraphQLBigDecimal`, whose intrinsic `getName()` is
`"BigDecimal"` (the library exposes the `GraphQL`-prefixed Java identifier as the field name and
the unprefixed string as the scalar name). So any consumer writing a `GraphQL`-prefixed convention
name hits the identical "registered under `BigDecimal`, referenced as `GraphQLBigDecimal`" failure.
The fix below covers both the `@scalarType` and convention entry points uniformly because the
comparison lives in the resolver, below both.

## Implementation (shipped at d82392e)

Landed as designed, single commit:

* `ScalarTypeResolver` forks on the recovered constant's `getName()`: match -> `Resolved`;
  mismatch -> `Synthesised(javaType, sdlName, owner, field)`. The SDL name is threaded through
  `resolveFromConstantFqn` (new 4-arg overload; the 3-arg overload stays alias-agnostic) via
  `resolveFromDirectiveValue` and `resolveByConvention`. `resolveBuiltIn` is unchanged.
* `TypeBuilder` `@scalarType` arm and convention arm widened from `instanceof Resolved` to
  `instanceof Successful`, mirroring the federation arm.
* `model/ScalarResolution.java` `Synthesised` javadoc generalised to cover the aliasing case.
* No change to `GraphitronSchemaClassGenerator`, `HelperMethodSink`, or any `.javaType()` reader.
* Tests: `ScalarTypeResolverTest` (alias -> `Synthesised`, match -> `Resolved`, `GraphQLBigDecimal`
  sibling); `GraphitronSchemaClassGeneratorTest` (alias emits `scalar_LocalDate()` helper, not the
  bare constant); `GraphitronSchemaBuilderTest.DIRECTIVE_BEATS_CONVENTION` updated to the corrected
  `Synthesised` outcome (it was latently broken before). Full `mvn install -Plocal-db` green.

## Design

The alias mechanism already exists in the model. `ScalarResolution.Synthesised(javaType, sdlName,
coercingSourceOwner, coercingSourceField)` (`model/ScalarResolution.java:50`) is the variant for
"there is no constant referenceable under the SDL name; register a scalar named `sdlName` that
borrows another constant's coercing". `HelperMethodSink.addSynthesisedScalar`
(`HelperMethodSink.java:64`, body at `buildSynthesisedScalarMethod`) emits exactly the renamed
registration this bug needs:

----
GraphQLScalarType.Builder b = GraphQLScalarType.newScalar();
b.name(sdlName);
b.coercing(<coercingSourceOwner>.<coercingSourceField>.getCoercing());
return b.build();
----

routed through `GraphitronSchemaClassGenerator`'s existing `Synthesised` switch arm
(`GraphitronSchemaClassGenerator.java:224`). Federation-namespace scalars
(`federation__FieldSet`, ...) are the current users of this arm.

The fix is therefore to make the resolver *classify the alias case as `Synthesised`*, not to teach
the emitter a new branch. The name comparison is a resolution-outcome decision and belongs in the
resolver, where the loader and reflection already ran and the live `GraphQLScalarType` is in hand.

### Resolver returns `Synthesised` on a name mismatch

`resolveFromConstantFqn` (`ScalarTypeResolver.java:148`) already holds the validated constant
(`check.scalar()`), its recovered input `javaType`, and its owner/field. It needs one more input:
the SDL name the scalar must register under, so it can compare. Thread the SDL name down the two
constant-resolution entry points that currently omit it:

* `resolveFromDirectiveValue(scalarFqn, loader)` -> add the SDL name; pass through to
  `resolveFromConstantFqn`.
* `resolveByConvention(scalarName, loader)` -> already has the SDL name (`scalarName`); pass it on.

(`resolveFederationNamespaceScalar` already takes the SDL name and already returns `Synthesised`;
no change. `resolveBuiltIn` needs no SDL name: a spec built-in's constant name equals its SDL name
by the spec, so it can never alias.)

With the SDL name available, `resolveFromConstantFqn` forks on the recovered constant's name:

* `check.scalar().getName().equals(sdlName)` -> return `Resolved(javaType, owner, field)` exactly
  as today. The common path (every scalar declared under its constant's own name, including
  `scalar Money @scalarType(... MONEY)` and `scalar Date`) is byte-identical.
* otherwise -> return `Synthesised(javaType, sdlName, owner, field)`. The recovered `javaType`
  (not a hardcoded `String`) flows into the variant so input-record components, service params,
  and `Field<X>` projections still bind to the real Java type; `owner`/`field` point at the
  constant whose coercing the emitted helper borrows.

The `CoercingErased` guard is unchanged and still runs first: a mismatch only reaches the
`Synthesised` branch after the coercing's input type recovers successfully, the same precondition
`Resolved` has today.

### Consumers: widen two classifier arms from `Resolved` to `Successful`

`GraphitronType.ScalarType` already holds a `ScalarResolution.Successful` (the federation arm at
`TypeBuilder.java:810` passes a `Synthesised` into it today). Every downstream reader of a
classified scalar's resolution goes through `Successful.javaType()` (`ServiceCatalog.java:1230`,
`CatalogBuilder.java:589`, `TypeBuilder.java:1332`, `FieldBuilder.java:4531`) or switches on the
arm (`GraphitronSchemaClassGenerator.java:221`); none cast to `Resolved`. So the only sites that
must change are the two classifier arms that currently accept only `Resolved`:

* `TypeBuilder.java:823` (the `@scalarType` directive arm): `instanceof ScalarResolution.Resolved r`
  -> `instanceof ScalarResolution.Successful s`, mirroring the federation arm at `:809`.
* `TypeBuilder.java:834` (the convention arm): same widening.

The built-in arm (`TypeBuilder.java:794`) stays `Resolved`-only (built-ins never alias). The
emitter, helper sink, and all javaType readers are untouched.

### Copy vs borrow: borrow the coercing only

The `Synthesised` path sets `name` + `coercing` and nothing else. The alias scalar therefore
carries the source constant's *coercing* (the load-bearing behaviour: parse/serialise) but not its
description or applied directives. This is the deliberate choice, consistent with the existing
federation synthesis: the SDL-declared alias is a distinct named scalar whose identity is its SDL
name, and graphitron does not today thread an SDL `"""description"""` onto synthesised scalars.
Carrying the constant's own description (e.g. extended-scalars' "An RFC-3339 compliant Full Date
Scalar") onto a differently-named alias would misattribute it. Threading the SDL-declared
description through is a possible later refinement, noted out of scope below.

### Why `Synthesised`, not a 4th component on `Resolved`

An earlier draft added a `scalarName` component to `Resolved` and branched inside the emitter's
`Resolved` arm. That is the "generator branches on a predicate over pre-resolved data" smell
(rewrite-design-principles § Generation-thinking): the fork is a resolution outcome and belongs in
the sealed taxonomy, which already has the exact arm for it. `Synthesised` carries what `Resolved`
cannot (an SDL name distinct from any referenceable constant name, plus a coercing-source pointer),
which is precisely the § "Sub-taxonomies for resolution outcomes" criterion for a separate variant.
Reusing it adds zero emitter branches and keeps the registration form (named `scalar_<name>()`
helper, per § "Generated code is read and debugged") consistent with the federation path rather than
introducing a second, inline form.

## Implementation sites

* `ScalarTypeResolver.java`: thread the SDL name into `resolveFromDirectiveValue` and on into
  `resolveFromConstantFqn`; pass it from `resolveByConvention`. In `resolveFromConstantFqn`, fork on
  `check.scalar().getName().equals(sdlName)` to return `Resolved` (match) or `Synthesised` (mismatch,
  carrying the recovered `javaType`). No `resolveBuiltIn` change.
* `TypeBuilder.java`: widen the `@scalarType` arm (`:823`) and convention arm (`:834`) from
  `instanceof ScalarResolution.Resolved` to `instanceof ScalarResolution.Successful`, matching the
  federation arm (`:809`).
* `model/ScalarResolution.java`: generalise the `Synthesised` javadoc — it now also serves consumer
  constants that exist but whose intrinsic name differs from the SDL name, not only
  federation-namespace names with no constant at all.
* No change to `GraphitronSchemaClassGenerator`, `HelperMethodSink`, or any `.javaType()` reader.
* Callers of `resolveFromDirectiveValue` / `resolveFromConstantFqn` in tests
  (`ScalarTypeResolverTest`, and any direct construction in `ServiceCatalogTest`) adjust to the new
  signature (compile-only churn).

## Tests

* *Resolver-tier* (`ScalarTypeResolverTest`): a constant whose `getName()` differs from the SDL
  name (e.g. resolve `...ExtendedScalars.Date` under SDL name `LocalDate`) returns
  `Synthesised(sdlName = "LocalDate", coercingSource = ExtendedScalars.Date)` with the recovered
  `javaType`; a constant whose name matches (`...ScalarConstants.MONEY` under `Money`) still returns
  `Resolved`. Assert the convention `GraphQLBigDecimal` case (name `BigDecimal`) returns `Synthesised`
  too, pinning the sibling fix.
* *Pipeline-tier* (`GraphitronSchemaClassGeneratorTest`, the scalar-registration block): a fixture
  `scalar LocalDate @scalarType(scalar: "...ExtendedScalars.Date")` used by a field emits a
  `scalar_LocalDate()` helper (whose body sets `name("LocalDate")` and borrows
  `ExtendedScalars.Date.getCoercing()`) and registers it via `schemaBuilder.additionalType(scalar_LocalDate())`.
  Keep an assertion that the name-matching `Money` case still emits the plain
  `.additionalType(...MONEY)` constant form, pinning the no-regression path.
* *Execution / build-through*: the real proof is that a schema using the aliased scalar assembles.
  The `mvn install -Plocal-db` pipeline builds schemas end-to-end; add the aliased scalar to a
  fixture schema whose generated `GraphitronSchema.build()` is exercised, so the regression
  reproduces `type LocalDate not found in schema` on the old code and passes on the new.

## Out of scope

* Threading an SDL-declared `"""description"""` (or applied directives) onto the synthesised alias
  scalar. The alias borrows coercing only, matching existing `Synthesised` behaviour; description
  propagation is a separate refinement.
* Routing consumer-resolved (non-built-in, non-federation) scalars through
  `AppliedDirectiveEmitter.emitInputType` directive-argument type slots. That path keeps its
  existing `GraphQLString` placeholder for custom scalars (`AppliedDirectiveEmitter.java:201`) and
  is explicitly out of Phase 3 scope already; this bug does not touch it.
* Convention-table or federation-synthesis resolution semantics beyond the name-comparison fork.

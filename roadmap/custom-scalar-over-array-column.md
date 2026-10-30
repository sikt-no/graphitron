---
id: R1135
title: "A custom scalar coercing an array or a parameterized list has no valid shape"
status: Spec
bucket: bug
priority: 4
theme: codegen-correctness
depends-on: []
created: 2026-10-30
last-updated: 2026-10-30
---

# A custom scalar coercing an array or a parameterized list has no valid shape

## Goal

A custom scalar whose `Coercing` produces a Java array or a parameterized list classifies and generates, so a scalar can sit over a `text[]` column. Today the array form crashes the generator with an `IllegalArgumentException` and the list form is rejected with a message that blames erasure the author never wrote. A *custom scalar* here is a `scalar` declaration bound to a `GraphQLScalarType` constant by `@scalarType`; its *coercing* is the `Coercing<I, O>` whose `I` is the Java type Graphitron binds input-record components, service parameters and projections to.

```graphql
scalar Tags @scalarType(scalar: "com.example.Scalars.TAGS")   # Coercing<String[], String[]>

type ArrayHolder @table(name: "array_holder") {
  id: Int
  tags: Tags          # array_holder.tags is text[], jOOQ Field<String[]>
}

input ArrayHolderFilter { tags: Tags }   # emitted record component: String[] tags
```

`Coercing<List<String>, ...>` works the same way and yields `List<String>`. Shapes that cannot be named as a concrete Java type (a wildcard, a type variable, an `Object` inside the generic) are rejected at the scalar with a message that names the shape and the fix, never a stack trace. The schema the kreg port needed (`tags: Tags` over a text array) becomes writable; its workaround was to declare the field `[String]`.

## What the code does today

* `ScalarTypeResolver.recoverInputType` returns `ClassName.get(box(input))`. For `Coercing<String[], ...>` the recovered `Class` is an array class and JavaPoet throws `array types cannot be represented as a ClassName`. Nothing on the way up (`resolveFromConstantFqn`, `TypeBuilder.classifyScalarType`) catches it, so the generator dies instead of reporting. Confirmed by reading; there is no existing test over an array coercing.
* `ScalarTypeResolver.inputFromInterface` only accepts a `Class<?>` as the first type argument. A `ParameterizedType` such as `List<String>` falls through to `null`, `recoverInputType` returns `null`, and `resolveFromConstantFqn` returns `ScalarResolution.Rejected.CoercingErased` with `describeCoercingDeclaration` reporting `ERASED_NAMED_CLASS`. So the list form is not "read erased": it is rejected, with `TypeBuilder.asRejection` telling the author the parameters are erased and to "declare concrete parameters" when they already did. The same misreport applies to a `GenericArrayType`.
* The item's premise that the coerced type is a `ClassName` is only half true. `ScalarResolution.Successful.javaType()` (so `Resolved` and `Synthesised`) is already a `TypeName`; only the recovery step in `ScalarTypeResolver` narrows to `Class`. No model type changes.
* There is no "match against the column's type" step to extend. Nothing in the classifier compares a custom scalar's Java type to a jOOQ column type: a scalar-typed field on a `@table` type binds to its column by name and jOOQ converts at bind time, which is why the kreg `[String]` workaround compiled. The earlier item text implying a column-type match is dropped from the plan.

## Consumers of the recovered type

Each reads `ScalarType.resolution().javaType()` and must tolerate `ArrayTypeName` and `ParameterizedTypeName`. Verified by reading each site:

[cols="2,3,2"]
|===
| Consumer | What it does with the type | Array / list safe?

| `TypeBuilder.resolveInputElementJavaType`, `InputRecordGenerator.readComponent`
| Becomes the `InputComponent` type of the emitted input record; the component is read with a direct cast `($T) in.get(...)`, and the `readComponent` list branch triggers on `pt.rawType().equals(LIST)`.
| Yes. `String[]` casts directly. A non-list SDL field whose scalar is `List<String>` takes the list branch and rebuilds the list element-wise with a no-op cast; the value is equal, so it is correct, but a test must pin it so a later change to that branch does not silently break the scalar case. Both emit under the method's existing `@SuppressWarnings("unchecked")`.

| `ServiceCatalog.mapToJavaTypeName`, `WireCoercionResolver.checkScalar`
| Renders the type with `toString()` and compares it textually to a service parameter's declared type; `peelJava` strips a `java.util.List<` / `Set<` wrapper off the declared name.
| Array: yes (`java.lang.String[]` on both sides). List-valued scalar: a non-list SDL leaf against a `List<String>` parameter takes the list-ness-differs path and passes through unchecked, which is safe but means no wire check; recorded as accepted, not widened here.

| `FieldBuilder.mapGraphQLTypeToReflectType`
| Needs a `java.lang.reflect.Type` for resolver assignability; takes it only when the type `instanceof ClassName`, else falls back to `Object.class`.
| Degrades safely (assignability then accepts anything), but loses the check. Extend it: `ParameterizedTypeName` maps to its raw type, `ArrayTypeName` to the array class via the element's `Class.forName`, so an array or list scalar keeps its assignability check.

| `TypeFetcherEmissionContext.validationPathsConstant`
| Casts `ValidationSubject.javaType()` to `ClassName`.
| Not reached from scalars (the subject is a bound class or jOOQ record). No change.

| `ScalarTypeResolver.coercionOutputType`, `isClassifiedScalarJavaType`
| `isClassifiedScalarJavaType` compares `javaType().toString()` to a parameter's FQN string.
| Yes. An array renders `java.lang.String[]`, matching how a service parameter's array type is named.
|===

## Scope decision

Supported: a coercing input type that is, recursively, a concrete class, an array of a supported type (reference or primitive element, including nested arrays), or a parameterized type whose arguments are all supported types (`List<String>`, `Set<Long>`, `Map<String, Integer>`, `List<String[]>`). This is exactly what `com.palantir.javapoet.TypeName.get(java.lang.reflect.Type)` decodes, so `recoverInputType` delegates to it after a pre-walk that rejects what must not reach it. Primitive array elements (`int[]`) stay as written; the existing `box` step applies only to a bare top-level class and is kept.

Rejected at the scalar's coordinate (the `UnclassifiedType` the classifier already produces, so the validator's unclassified-type pass reports it as a build error):

* Top-level `Object`, a top-level type variable, a raw or anonymous `Coercing`: unchanged, `CoercingErased`.
* A wildcard anywhere in the input type (`List<?>`, `List<? extends Number>`), a type variable nested in a generic or array (`List<T>`, `T[]`), or `Object` as a generic argument or array element (`List<Object>`, `Object[]`): a new arm `ScalarResolution.Rejected.CoercingInputNotConcrete(String coercingClass, String declaredInput)`, rendered by `TypeBuilder.asRejection` as `scalar 'Tags': the Coercing on '<class>' declares input type '<declared>', which names a wildcard, type variable or Object where a concrete Java type is needed. Declare a concrete type such as List<String>.` A wildcard has no single Java type a record component or parameter can be bound to, and `Object` inside a generic reintroduces the `Object` the resolver's invariant forbids at the top level. A new arm rather than reusing `CoercingErased` because that arm's message and `CoercingDeclarationKind` are about the class declaration, not the shape of a well-declared parameter.

Recommendation: ship supported and rejected as above in one item. The alternative of supporting only arrays leaves the list form with the same misleading message, and the work is the same walk.

Not in scope, found while verifying: `findCoercingInputType` only recognises a `Coercing<I, ...>` parameterisation whose type arguments are classes, so `class X extends AbstractTypedCoercing<Money, Money>` (a generic base that itself implements `Coercing<I, O>`) is rejected as erased, contradicting the walk's own javadoc. That is a type-variable-resolution gap separate from this shape question; file separately if wanted.

## Implementation

* `ScalarTypeResolver.recoverInputType` / `findCoercingInputType` / `inputFromInterface`: carry a `java.lang.reflect.Type` instead of a `Class<?>`. Take `args[0]` of the `Coercing` parameterisation whatever its kind, return it, and convert once. Conversion: reject (see above) when the type tree contains a wildcard, a type variable or `Object`; otherwise `TypeName.get(type)`, applying `box` to a bare top-level class. Return a small internal result distinguishing erased-at-top-level (existing `CoercingErased`) from not-concrete-nested (new arm), so `resolveFromConstantFqn` emits the right `Rejected`.
* `ScalarResolution.Rejected`: add `CoercingInputNotConcrete`; add it to the `permits` list. Update the class javadoc of `ScalarTypeResolver` ("Never `Object`" invariant now reads "never `Object` or a wildcard at any depth").
* `TypeBuilder.asRejection`: add the switch arm with the message above (the switch is exhaustive, so the compiler enforces it).
* `FieldBuilder.mapGraphQLTypeToReflectType`: extend the `instanceof ClassName` read to raw-type and array handling as in the consumer table.
* Docs: `docs/manual/reference/directives/scalarType.adoc` (the `CoercingErased` bullet) and `docs/manual/how-to/custom-scalars.adoc` (the two "concrete type parameters" sentences and the rejection-reason list) state that arrays and parameterized types of concrete types are accepted and list the new rejection. No roadmap markers in the prose.

## Tests

Fixtures first. `graphitron/src/test/java/no/sikt/graphitron/rewrite/scalarfixture/` already holds `Money`, `MoneyCoercing`, `RawCoercing`, `ErasedNamedCoercing` and the `ScalarConstants` owner class. Add coercings `StringArrayCoercing` (`Coercing<String[], String[]>`), `StringListCoercing` (`Coercing<List<String>, List<String>>`), `WildcardListCoercing` (`Coercing<List<?>, List<?>>`), `ObjectArrayCoercing` (`Coercing<Object[], Object[]>`) and matching `ScalarConstants` constants. The sakila database already has the column fixture: `array_holder` (`tags text[]`, `flags boolean[]`) in `graphitron-sakila-db/src/main/resources/init.sql`, in use by `ArrayColumnCodegenPipelineTest`; no schema change.

* Unit tier, `ScalarTypeResolverTest`: array resolves to `ArrayTypeName` of `String` (this is the case that crashes today, so it fails before the fix); list resolves to `ParameterizedTypeName` `List<String>`; wildcard list and `Object[]` return `CoercingInputNotConcrete` with the declared type text; `Money` and the three existing `CoercingErased` cases unchanged.
* Pipeline tier, `GraphitronSchemaBuilderTest` rows beside `DIRECTIVE_RESOLVES_CONSUMER_SCALAR`: an array scalar and a list scalar each classify to `ScalarType` with the expected `javaType`; the wildcard scalar classifies to `UnclassifiedType` whose reason contains the scalar name and `not concrete`/the declared type; a `@table(name: "array_holder")` type with `tags: Tags` classifies without a field rejection.
* Pipeline tier, a new `*PipelineTest` using `InputRecordRenderTestSupport.renderInputRecords`: an input with `tags: Tags` emits a `String[]` component read by a direct cast; the same with the list scalar emits `List<String>` and the element-wise read compiles-by-inspection of the emitted `TypeSpec`; both pinned on the model/`TypeSpec` shape, not code strings.
* Compilation and execution tiers: no new fixture. The generated records are plain Java and the column bind is jOOQ's own; the pipeline `TypeSpec` assertions carry the contract.

## Open question

Whether the `ServiceCatalog` wire-coercion check should also judge a list-valued scalar against a `List<String>` service parameter (today it passes through unchecked). Recommendation: leave as is; it is a hardening, not part of this defect.

Provenance: reported by the kreg Graphitron 10 port session (fs-plattform's kreg module on 10.0.0-RC46, branch `worktree-kreg-g10` at `7858137af4`, not pushed), 2026-10-30; the reporter found it present on graphitron `origin/main` the same day.

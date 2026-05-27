---
id: R248
title: Survivor directive definitions emit incorrect arg types and miss defaults
status: In Review
bucket: bug
depends-on: []
created: 2026-05-27
last-updated: 2026-05-27
---

# Survivor directive definitions emit incorrect arg types and miss defaults

The programmatic schema emitted by `GraphitronSchemaClassGenerator` registers
every survivor directive (including the federation directives) via
`.additionalDirective(...)` calls built by `DirectiveDefinitionEmitter`. The
result for `@key`, as observed in the wild, is:

```java
.additionalDirective(GraphQLDirective.newDirective()
    .name("key").repeatable(true)
    .validLocation(Introspection.DirectiveLocation.OBJECT)
    .validLocation(Introspection.DirectiveLocation.INTERFACE)
    .argument(GraphQLArgument.newArgument()
        .name("fields")
        .type(GraphQLNonNull.nonNull(Scalars.GraphQLString))
        .build())
    .argument(GraphQLArgument.newArgument()
        .name("resolvable")
        .type(Scalars.GraphQLBoolean)
        .build())
    .build())
```

The SDL the directive was loaded from is

```graphql
directive @key(fields: FieldSet!, resolvable: Boolean = true) repeatable on OBJECT | INTERFACE
```

so the emitted shape diverges in two ways:

1. *Default value dropped.* `resolvable: Boolean = true` becomes
   `resolvable: Boolean` with no default. `DirectiveDefinitionEmitter.buildDefinition`
   (`graphitron/src/main/java/no/sikt/graphitron/rewrite/generators/schema/DirectiveDefinitionEmitter.java:63-74`)
   emits `.name(...).type(...).description(...).build()` and never calls
   `.defaultValueProgrammatic(...)`. The sibling path for field arguments at
   `ObjectTypeGenerator.java:258-261` already round-trips defaults via
   `arg.hasSetDefaultValue()` + `GraphQLArgument.getArgumentDefaultValue(arg)` +
   `GraphQLValueEmitter.emit(...)`; the directive-definition emitter never adopted
   it. This bug hits user-declared directives with defaults too, not just the
   federation surface.

2. *Federation-namespace scalars collapse to `Scalars.GraphQLString`.* The
   `fields` argument is `federation__FieldSet!` in the assembled schema (the
   federation-jvm `LinkImportsRenamingVisitor` renames non-imported scalars to
   the `federation__` prefix at registry-injection time). The applied directive
   emitter's `emitInputType` routes federation-namespace scalars through
   `ScalarTypeResolver.resolveFederationNamespaceScalar`, which returns
   `Resolved(java.lang.String, graphql.Scalars, "GraphQLString")`. The directive's
   `fields` arg, the registered scalar type, and every applied-directive `fields`
   argument all reference `Scalars.GraphQLString` rather than a scalar named
   `federation__FieldSet`.

The in-tree comments at `ScalarTypeResolver.java:83-95`, `TypeBuilder.java:601-605`,
and `AppliedDirectiveEmitter.java:125-131` all justify the placeholder by
claiming `Federation.transform()` replaces the `GraphQLString` reference with
its own scalar definition after Graphitron's base schema is built. That claim is
wrong. The entry point graphitron uses
(`com.apollographql.federation.graphqljava.Federation.transform(GraphQLSchema)`
in federation-graphql-java-support 6.0.0, called from the emitted
`GraphitronSchema.build` body at
`GraphitronSchemaClassGenerator.java:232-238` and `:246-255`) skips the
directive-injection scaffolding entirely; `SchemaTransformer.build()` only adds
`_Any` / `_Entity` / `_Service` and wires entity resolution. It never rewrites
the `@key` directive definition, never injects a `federation__FieldSet` scalar,
and never touches the type slot of any applied directive. Independently,
`ServiceSDLPrinter.generateServiceSDLV2` delegates to graphql-java's standard
`SchemaPrinter`, which prints whatever's in the schema as-is. The "transform
replaces it" story has no enforcement step behind it; the divergence reaches the
printed SDL untouched.

Symptom that motivates the lift:

```java
ServiceSDLPrinter.generateServiceSDLV2(
    Graphitron.buildSchema(b -> {}, ft -> ft.setFederation2(true)))
```

prints `directive @key(fields: String!, resolvable: Boolean) repeatable on OBJECT | INTERFACE`
rather than the spec-canonical
`directive @key(fields: federation__FieldSet!, resolvable: Boolean = true) repeatable on OBJECT | INTERFACE`
(the `federation__FieldSet` name is the post-rename form; consumers who
`@link(import: ["@key", "FieldSet"])` get `FieldSet` un-prefixed instead).
Downstream subgraph-composition tooling rejects the former.

## Why a codegen-time fix, not a runtime / architecture shift

Federation-jvm exposes
`Federation.transform(TypeDefinitionRegistry, RuntimeWiring, SchemaGenerator.Options)`,
which internally calls `ensureFederationV2DirectiveDefinitionsExist(registry, wiring, defs)`
to inject any missing federation directive definitions and synthesise any
missing federation scalars via `_Any.type.getCoercing()`. Routing graphitron's
federation users through that registry+wiring entry point would let
federation-jvm own the directive surface entirely.

We reject that direction here. The rewrite's prebuilt-programmatic-schema model
(`R10`'s predecessor landing) is load-bearing for fast boot: no SDL parse, no
`SchemaGenerator.makeExecutableSchema` at startup, every type instance
constructed once via JavaPoet-emitted builder calls. Federation handling at
generation time fits that model; federation-jvm's runtime injection scaffolding
does not. The codegen flow already produces an assembled `GraphQLSchema` that
carries the correct directive shapes (the `FederationLinkApplier` pipeline
calls `LinkDirectiveProcessor.loadFederationImportedDefinitions` at codegen,
realised by `SchemaGenerator.makeExecutableSchema` into the assembled schema
that downstream emitters walk). The bug is purely in the JavaPoet
reconstruction step that re-emits those directives into the generated builder
calls. Fix the reconstruction.

## Fix

### 1. Default values

Inside `DirectiveDefinitionEmitter.buildDefinition`'s per-argument loop
(`DirectiveDefinitionEmitter.java:63-74`), between the description block and the
closing `.build())`, mirror `ObjectTypeGenerator.buildArgument`:

```java
if (arg.hasSetDefaultValue()) {
    Object defaultValue = graphql.schema.GraphQLArgument.getArgumentDefaultValue(arg);
    block.add(".defaultValueProgrammatic(")
         .add(GraphQLValueEmitter.emit(defaultValue))
         .add(")");
}
```

`GraphQLValueEmitter` is already on the classpath of this package; no new
imports beyond the static method reference. Argument-level `@deprecated` is not
added here (no survivor directive Graphitron currently emits carries a
deprecated argument), keeping the diff narrow.

### 2. Register federation-namespace scalars under their actual names

`ScalarTypeResolver.resolveFederationNamespaceScalar` currently returns
`ScalarResolution.Resolved(String.class, graphql.Scalars, "GraphQLString")`, so
`plan.scalarRegistrations` ends up emitting
`.additionalType(Scalars.GraphQLString)` for every federation-namespace scalar
the schema mentions. The `federation__FieldSet` scalar (and its siblings
`federation__Scope`, `federation__Policy`, `federation__ContextFieldValue`,
`link__Import`) never lands in the programmatic schema under its own name, so a
`GraphQLTypeReference.typeRef("federation__FieldSet")` on the `@key` directive's
`fields` argument would dangle at schema build.

Extend the `ScalarResolution` sealed type with an intermediate `Successful`
sub-interface that admits both the existing constant-pointing `Resolved` and a
new `Synthesised` for scalars without a referenceable public-static constant:

```java
public sealed interface ScalarResolution permits Successful, Rejected {

    sealed interface Successful extends ScalarResolution permits Resolved, Synthesised {
        TypeName javaType();
    }

    record Resolved(TypeName javaType, ClassName scalarConstantOwner, String scalarConstantField)
        implements Successful {}

    record Synthesised(TypeName javaType, String sdlName, ClassName coercingSourceOwner,
                       String coercingSourceField)
        implements Successful {}

    // ... existing Rejected hierarchy unchanged ...
}
```

The intermediate `Successful` interface keeps `javaType()` as a common accessor
so the four call sites that only read `.javaType()` (`ServiceCatalog.java:1230`,
`ScalarTypeResolver.java:524`, `CatalogBuilder.java:545-546`,
`FieldBuilder.java:3892`) keep working unchanged. `GraphitronType.ScalarType.resolution`
widens from `ScalarResolution.Resolved` to `ScalarResolution.Successful`.

Three call sites need exhaustive dispatch added:

- `TypeBuilder.java:646-657` (the federation-namespace branch of
  `tryResolveScalar`, which today reads `resolveFederationNamespaceScalar(...)`
  into `instanceof ScalarResolution.Resolved r` at `:653` and constructs the
  `GraphitronType.ScalarType` from `r`): rebind to `Synthesised` (or pattern
  match on `Successful`) so the federation classifier propagates the new
  variant into the model. Without this, the federation resolver's
  `Synthesised` return falls through to `asRejection(...)`, which throws
  `IllegalStateException("asRejection invoked on Resolved")` since the input
  is neither `Resolved` nor `Rejected`.
- `TypeBuilder.java:1217` (`instanceof ScalarResolution.Resolved sr`): pattern
  match on `Successful` so the `Synthesised` arm participates in the same
  Java-type lookup the existing arm performs.
- `GraphitronSchemaClassGenerator.planFor:509-510` (reads
  `Resolved.scalarConstantOwner` and `scalarConstantField`): pass `Successful`
  through to the emit loop in `build()` so it can dispatch on the variant.

`AppliedDirectiveEmitter.emitInputType:153-160` also needs dispatch (step 3
below): today both the spec-built-in branch and the federation-namespace branch
read the `Resolved` shape directly; after the lift, the federation branch reads
`Synthesised` and emits `GraphQLTypeReference.typeRef(...)`.

Change `ScalarTypeResolver.resolveFederationNamespaceScalar` to return
`Synthesised` with `sdlName` = the federation-namespaced name
(`federation__FieldSet`, etc.), `coercingSourceOwner` =
`com.apollographql.federation.graphqljava._Any`, `coercingSourceField` =
`"type"`. The Java-side carrier stays `String` (these scalars deserialize to
strings on the wire).

`_Any.type.getCoercing()` is the coercing source federation-jvm itself borrows
in `ensureFederationV2DirectiveDefinitionsExist` when synthesising missing
federation scalars at the registry+wiring entry point (verified against the
6.0.0 jar: the worker lambda at `Federation$lambda$ensureFederationV2DirectiveDefinitionsExist$4`
builds the scalar with `_Any.type.getCoercing()`). Reusing it here mirrors
federation-jvm's own choice; we are not inventing a coercing.

`GraphitronSchemaClassGenerator.build()` currently emits each scalar via
`(owner, fieldName)`:

```java
body.add("\n.additionalType($T.$L)", reg.owner(), reg.fieldName());
```

Extend it to dispatch on the resolution variant and emit one of:

```java
// Resolved (existing path, unchanged):
.additionalType(graphql.Scalars.GraphQLInt)

// Synthesised (new path):
.additionalType(GraphQLScalarType.newScalar()
    .name("federation__FieldSet")
    .coercing(com.apollographql.federation.graphqljava._Any.type.getCoercing())
    .build())
```

`name(String)` + `coercing(Coercing<?,?>)` + `build()` is the minimum the builder
requires (verified against graphql-java 25.0 `GraphQLScalarType$Builder`).
`_Any.type.getCoercing()` returns `Coercing<?,?>` which matches the
`Builder.coercing(...)` signature exactly. No reflection, no string-keyed
lookups; the codegen-emitted reference is a typed static field access.

### 3. Emit `GraphQLTypeReference` for federation scalars in directive arg types

`AppliedDirectiveEmitter.emitInputType` is the shared emitter for both directive
*definition* argument types (called from `DirectiveDefinitionEmitter.java:68`)
and applied directive argument types. Change the federation-namespace branch to
return a forward type reference:

```java
if (ScalarTypeResolver.isFederationNamespaceScalar(name)) {
    return CodeBlock.of("$T.typeRef($S)", CN_TYPE_REF, name);
}
```

Once step 2 lands, the scalar is registered under that name, so the type
reference resolves at `schemaBuilder.build()` time. Both directive-definition
arg types and applied-directive arg types route through the same edit, so
definition and application agree on the type slot by construction.

### 4. Update the stale "transform replaces the placeholder" comments

The misleading paragraphs at `ScalarTypeResolver.java:83-95`,
`ScalarTypeResolver.java:310-314` (the javadoc on
`resolveFederationNamespaceScalar` itself; this one is implicitly rewritten in
step 2 along with the method's return-type and contract, but it's listed here
so the step-4 enumeration is exhaustive), `TypeBuilder.java:601-605` (the
`tryResolveScalar` javadoc bullet), `TypeBuilder.java:647-651` (the inline
comment in the same method's federation-namespace branch — "emitting
`Scalars.GraphQLString` as a placeholder satisfies graphql-java's type-resolver
until the replacement runs"), and `AppliedDirectiveEmitter.java:99-101` /
`:125-131` all rest on a false claim about federation-jvm's
`transform(GraphQLSchema)` entry. Replace the "federation-jvm replaces this
binding" framing with a factual description of the new synthesised-scalar path:
graphitron emits the scalar under its federation-namespaced SDL name with
`_Any.type.getCoercing()` borrowed (the same lever federation-jvm pulls in
`ensureFederationV2DirectiveDefinitionsExist` for the registry+wiring entry
point), and the directive arg types reference it via `typeRef`. Drop the "safe
placeholder" wording entirely.

### Order of work

Steps 2 and 3 must land in the same commit (the type-ref dangles without the
registered scalar, and the registered scalar without a `typeRef` reference is a
no-op). Step 1 is independent and can land first or alongside. Step 4 is the
docs sweep, landing in the same commit as step 3. Single Spec → Ready → In
Progress → In Review → Done flow for the whole landing.

---

## Tests

### Unit-tier

`DirectiveDefinitionEmitterTest` extensions:

- Extend `buildDefinition_emitsNameLocationsAndArguments` to assert that the
  emitted `CodeBlock` for the existing `mode: String = "strict"` argument
  contains `.defaultValueProgrammatic(` plus the string literal `"strict"`.
- Add a second arm with a `Boolean` default mirroring the canonical
  `@key(resolvable: Boolean = true)` shape, since `GraphQLValueEmitter.emit(...)`
  dispatches differently on string and boolean values.
- Add a test that the emitted directive references federation scalars via
  `GraphQLTypeReference.typeRef("federation__FieldSet")` rather than
  `Scalars.GraphQLString`. SDL fixture: a federation `@link` import that pulls
  in `@key` without importing `FieldSet`, so the federation library's renaming
  visitor produces the prefixed scalar name.

`ScalarTypeResolverTest` extensions:

- New test that `resolveFederationNamespaceScalar("federation__FieldSet")`
  returns a `Synthesised` carrying `sdlName == "federation__FieldSet"`,
  `coercingSourceOwner ==
  com.apollographql.federation.graphqljava._Any`,
  `coercingSourceField == "type"`. Repeat for one other federation-namespace
  name to confirm the dispatch (not just FieldSet).
- Existing tests that assert `resolveFederationNamespaceScalar(...) instanceof Resolved`
  flip to `instanceof Synthesised`.

`GraphitronSchemaClassGeneratorTest` extensions: assert the emitted
`GraphitronSchema.build()` body contains an inline
`GraphQLScalarType.newScalar().name("federation__FieldSet").coercing(_Any.type.getCoercing())...`
when the schema imports federation directives, and that the directive
definition uses `GraphQLTypeReference.typeRef("federation__FieldSet")`.

### Pipeline-tier

Extend `FederationBuildSmokeTest` (already in `graphitron-sakila-example`,
`PipelineTier`) with a test that runs

```java
GraphQLSchema schema = Graphitron.buildSchema(b -> {}, fed -> {});
String sdl = ServiceSDLPrinter.generateServiceSDLV2(schema);
```

and asserts the resulting SDL contains:

- `directive @key(fields: federation__FieldSet!, resolvable: Boolean = true) repeatable on OBJECT | INTERFACE`
  (the name matches the renaming convention dictated by the fixture's `@link`
  import set; the test's fixture imports `@key` only, so `FieldSet` lands
  prefixed)
- `scalar federation__FieldSet`

Same fixture also asserts that `Federation.transform(...).setFederation2(true).build()`
does not throw under the new directive shape (the existing
`twoArgBuildDoesNotThrow` test already covers this; the assertion extends to
the synthesised-scalar path).

### Compilation-tier

The existing `graphitron-sakila-example` compile against real federation-jvm
classes covers the new emitted scalar synthesis and type-ref shape. No new test
class needed; the existing federation tests in that module already exercise
the emitted code path.

---

## Out of scope

- Argument-level `@deprecated` on directive definitions. No survivor directive
  Graphitron currently emits carries a deprecated argument; emitting
  `.deprecate(...)` on the argument is a separate capability lift.
- Re-emitting the `@link` directive itself. The federation library injects
  `@link` into the registry via `LinkDirectiveProcessor`; the survivor walker
  picks it up like any other directive. Its argument types (`url: String!`,
  `as: String`, `import: [link__Import]`, `for: link__Purpose`) flow through
  steps 2-3 along with the rest.
- Switching graphitron's federation entry point from
  `Federation.transform(GraphQLSchema)` to
  `Federation.transform(TypeDefinitionRegistry, RuntimeWiring)`. The latter
  would let federation-jvm own the directive injection, but at the cost of
  giving up the prebuilt-programmatic-schema fast-boot model the rewrite chose
  in R10's predecessor landing. Federation handling stays at generation time;
  this item fixes the codegen reconstruction, not the runtime model.
- The federation v1 surface (`FederationDirectives.key` and friends,
  `_FieldSet.type`, `ensureFederationDirectiveDefinitionsExist`). Graphitron
  targets federation v2 only (`FederationSpec.URL` pins the v2 spec); v1's
  prebuilt `GraphQLDirective` constants do not match the v2 directive shape
  (`resolvable: Boolean = true` is a v2 addition).

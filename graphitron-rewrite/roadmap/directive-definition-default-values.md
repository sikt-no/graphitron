---
id: R248
title: Survivor directive definitions emit incorrect arg types and miss defaults
status: Spec
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
   it.
2. *Federation-namespace scalars collapse to `Scalars.GraphQLString`.* The
   `fields` argument is `federation__FieldSet!` in the assembled schema (the
   federation-jvm `LinkImportsRenamingVisitor` renames non-imported scalars to
   the `federation__` prefix), and the source SDL declares it as `FieldSet!`.
   `AppliedDirectiveEmitter.emitInputType` (which the directive-definition
   emitter delegates to) routes federation-namespace scalars through
   `ScalarTypeResolver.resolveFederationNamespaceScalar`, which returns
   `Resolved(java.lang.String, graphql.Scalars, "GraphQLString")`. The result is
   that the directive's `fields` arg, the registered scalar type, and every
   applied-directive `fields` argument all reference `Scalars.GraphQLString`
   rather than a scalar named `federation__FieldSet`.

The in-tree comments at `ScalarTypeResolver.java:83-95`, `TypeBuilder.java:601-605`,
and `AppliedDirectiveEmitter.java:125-131` all justify #2 by claiming
"`Federation.transform()` replaces the `GraphQLString` reference with its own
scalar definition after Graphitron's base schema is built". That claim is wrong.
`SchemaTransformer.build()`
(`com.apollographql.federation.graphqljava.SchemaTransformer` in
federation-graphql-java-support 6.0.0) only adds `_Any` / `_Entity` / `_Service`
and wires entity resolution; it never rewrites the `@key` directive definition,
never injects a `federation__FieldSet` scalar, and never touches the type slot
of any applied directive. Independently, `ServiceSDLPrinter.generateServiceSDLV2`
delegates to graphql-java's standard `SchemaPrinter`, which prints whatever's in
the schema as-is. The "transform replaces it" story has no enforcement step
behind it; the divergence reaches the printed SDL untouched.

Symptom that motivates the lift:

```java
ServiceSDLPrinter.generateServiceSDLV2(
    Graphitron.buildSchema(b -> {}, ft -> ft.setFederation2(true)))
```

prints `directive @key(fields: String!, resolvable: Boolean) repeatable on OBJECT | INTERFACE`
rather than the spec-canonical
`directive @key(fields: FieldSet!, resolvable: Boolean = true) repeatable on OBJECT | INTERFACE`.
Downstream subgraph-composition tooling rejects the former.

---

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
`_FieldSet`, `link__Import`, `link__Purpose`) never lands in the programmatic
schema under its own name, so a `GraphQLTypeReference.typeRef("federation__FieldSet")`
on the `@key` directive's `fields` argument would dangle at schema build.

Extend the `ScalarResolution` sealed type with a new `Resolved` sibling that
carries the SDL scalar name plus a hint for which coercing to borrow at emit
time:

```java
public sealed interface ScalarResolution permits Resolved, Synthesised, Rejected {
    record Resolved(TypeName javaType, ClassName scalarConstantOwner, String scalarConstantField)
        implements ScalarResolution {}

    record Synthesised(TypeName javaType, String sdlName, ClassName coercingSourceOwner,
                       String coercingSourceField)
        implements ScalarResolution {}

    // ... existing Rejected hierarchy unchanged ...
}
```

`Synthesised` is for scalars the resolver has classified but for which no
referenceable public-static constant exists. Federation-namespace scalars are
the v1 set; future "graphitron-synthesised" scalars would extend this without
disturbing the `Resolved` path.

Change `resolveFederationNamespaceScalar` to return `Synthesised` with
`sdlName` = the federation-namespaced name (`federation__FieldSet`, etc.),
`coercingSourceOwner` = `graphql.Scalars`, `coercingSourceField` = `"GraphQLString"`.
The Java-side carrier stays `String` (these scalars deserialize to strings).

`GraphitronSchemaClassGenerator.planFor` currently emits each scalar via
`(owner, fieldName)`:

```java
body.add("\n.additionalType($T.$L)", reg.owner(), reg.fieldName());
```

Extend `ScalarRegistration` to carry the resolution variant (or split into two
record types) so the loop in `GraphitronSchemaClassGenerator.build` can dispatch
on the variant and emit one of:

```java
// Resolved (existing path, unchanged):
.additionalType(graphql.Scalars.GraphQLInt)

// Synthesised (new path):
.additionalType(GraphQLScalarType.newScalar()
    .name("federation__FieldSet")
    .coercing(graphql.Scalars.GraphQLString.getCoercing())
    .build())
```

`TypeBuilder.classifyScalarType` already wraps the resolver's output into a
`GraphitronType.ScalarType` and stores it on the schema model; that path
threads through unchanged once the resolution variant accommodates `Synthesised`.

### 3. Emit `GraphQLTypeReference` for federation scalars in directive arg types

`AppliedDirectiveEmitter.emitInputType` is the shared emitter for both directive
*definition* argument types (via `DirectiveDefinitionEmitter`) and applied
directive argument types (via `AppliedDirectiveEmitter` itself). Today the
federation-namespace branch returns `Scalars.GraphQLString`. Change it to
return `GraphQLTypeReference.typeRef("<federation-namespaced name>")`:

```java
if (ScalarTypeResolver.isFederationNamespaceScalar(name)) {
    return CodeBlock.of("$T.typeRef($S)", CN_TYPE_REF, name);
}
```

Once step 2 lands, the scalar is registered under that name, so the type
reference resolves at `schemaBuilder.build()` time. The applied-directive
argument types must match the directive-definition argument types or graphql-java
rejects the schema build, so this change is mandatory in lockstep with step 2,
not optional.

### 4. Update or remove the stale "transform replaces the placeholder" comments

The misleading paragraphs at `ScalarTypeResolver.java:83-95`,
`TypeBuilder.java:601-605`, and `AppliedDirectiveEmitter.java:99-101` plus
`:125-131` all rest on a false claim about federation-jvm. Replace the
"federation-jvm replaces this binding" framing with a factual description of
the new synthesised-scalar path: graphitron emits the scalar under its
federation-namespaced name and the directive arg types reference it via
`typeRef`. Drop the "safe placeholder" wording entirely.

### Order of work

Steps 2 and 3 must land together (the type-ref dangles without the registered
scalar, and the registered scalar without a `typeRef` reference is a no-op).
Step 1 is independent and can land first or alongside. Step 4 is the docs
sweep, landing in the same commit as step 3. Single Spec → Ready → In Progress
→ In Review → Done flow for the whole landing.

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
  `coercingSourceOwner == graphql.Scalars`,
  `coercingSourceField == "GraphQLString"`. Repeat for one other
  federation-namespace name to confirm the dispatch (not just FieldSet).
- Existing tests that assert `resolveFederationNamespaceScalar(...) instanceof Resolved`
  flip to `instanceof Synthesised`.

`GraphitronSchemaClassGeneratorTest` extensions: assert the emitted
`GraphitronSchema.build()` body contains an inline `GraphQLScalarType.newScalar().name("federation__FieldSet")...`
when the schema imports federation directives, and that the directive
definition uses `GraphQLTypeReference.typeRef("federation__FieldSet")`.

### Pipeline-tier

Extend `FederationBuildSmokeTest` (already in
`graphitron-sakila-example`, `PipelineTier`) with a test that runs

```java
GraphQLSchema schema = Graphitron.buildSchema(b -> {}, fed -> {});
String sdl = ServiceSDLPrinter.generateServiceSDLV2(schema);
```

and asserts the resulting SDL contains, exactly:

- `directive @key(fields: federation__FieldSet!, resolvable: Boolean = true) repeatable on OBJECT | INTERFACE`
  (the names match the renaming convention dictated by the fixture's `@link`
  import set; the test's fixture imports `@key` only, so `FieldSet` lands
  prefixed)
- `scalar federation__FieldSet`

Same fixture also asserts that `Federation.transform(...).setFederation2(true).build()`
does not throw under the new directive shape (the existing
`twoArgBuildDoesNotThrow` test already covers this; the assertion extends to the
synthesised-scalar path).

### Compilation-tier

The existing `graphitron-sakila-example` compile against real federation-jvm
classes covers the new emitted scalar synthesis and type-ref shape. No new
test class needed; the existing federation tests in that module already exercise
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
  steps 2-3 already. No additional handling.
- Federation v1's `_FieldSet` scalar redirect to federation-jvm's
  `com.apollographql.federation.graphqljava._FieldSet.type` constant. v1's
  `_FieldSet` would naturally route through `Synthesised` with `sdlName == "_FieldSet"`;
  switching it to point at federation-jvm's constant is a separate optimization
  with no observable schema-shape difference.

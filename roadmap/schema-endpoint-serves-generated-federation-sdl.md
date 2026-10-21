---
id: R1081
title: "graphitron-jakarta-rest /graphql/schema serves SDL federation composition rejects"
status: In Progress
bucket: bug
priority: 3
depends-on: []
created: 2026-10-21
last-updated: 2026-10-21
---

# graphitron-jakarta-rest /graphql/schema serves SDL federation composition rejects

## Goal

A federated subgraph served through `graphitron-jakarta-rest` answers `GET /graphql/schema` with SDL that Federation 2 composition accepts: the same SDL its `{ _service { sdl } }` query already answers, carrying the `schema @link(url: ".../federation/v2.x", ...)` declaration and no definitions of the GraphQL built-in directives. A supergraph composed from schema URLs then works for every library consumer, and no consumer needs its own schema resource to get there. The cost to a consumer is one line in its adapter (the small hand-written class that connects the library to its generated code): the constructor passes the generated SDL printer alongside the schema.

```java
// before: /graphql/schema prints the runtime schema with graphql-java's default printer
super(() -> Graphitron.buildSchema(b -> {}));
// after: /graphql/schema prints it the way the subgraph's _service.sdl does
super(() -> Graphitron.buildSchema(b -> {}), Graphitron::schemaSdl);
```

## The defect

`GraphqlHttpHandler.schema()` prints the runtime schema with `new SchemaPrinter()` at default options. In graphql-java 25 those defaults print the `schema { ... }` block only when a root type has a non-standard name, so the block that carries the federation `@link` is dropped; and they print every directive definition, spec built-ins included. A composer reading that SDL (observed with `@theguild/federation-composition` 0.27.0 through the fs-plattform `supergraf-compose` tool) therefore:

- sees no `@link`, treats the subgraph as Federation 1, and rejects `@key`, `@requires` and `@provides` because their `fields` argument is `federation__FieldSet!` where Federation 1 expects `_FieldSet!` (`DIRECTIVE_DEFINITION_INVALID`);
- adds its own `@skip`, `@include` and, in Federation 1 mode, `@key`, then rejects the printed duplicates ("There can be only one directive named ...", `INVALID_GRAPHQL`).

Composition by introspection works. For a Federation 2 schema, federation-jvm's `SchemaTransformer.build` bakes the `_service.sdl` value by printing the transformed schema through `ServiceSDLPrinter.generateServiceSDLV2`, which keeps the schema definition and drops the spec built-in directive definitions; when the schema uses `@oneOf`, the generated `GraphitronSchema.build` then routes the result through the generated `OneOfDirectiveSdl.withOneOfDefinition` helper, which reinstates the `@oneOf` definition that printer strips and composition needs. Composition from the schema URL is the path that fails, and it is the only path for a subgraph that does not answer introspection.

So the composition-ready print already exists at runtime, inside the generated code, and the library endpoint does not use it. The generate-time file `schema.graphqls` (`SchemaSdlEmitter.printFederationServiceSdl`) mirrors the same printer options, and `FederationBuildSmokeTest.emittedSdlMatchesRuntimeSchema` holds the file and `generateServiceSDLV2` over the runtime schema structurally equal.

Observed in fs-plattform, 2026-10-21. `utdanningsregisteret` and `kompetanse` extend `AbstractGraphitronApplication` and rely on the library route; both fail composition from their schema URL with the errors above. `opptak` (a hand-written resource, not the library) and `tilgangsstyring` (the library with `builtInEndpointEnabled()` returning `false` and its own `SchemaResource`) serve the generated `schema.graphqls` from the classpath and compose.

## Design

The endpoint stays a view of the schema the engine executes; what changes is who prints it. The print policy lives where it already lives, in generated code that may name federation-jvm, and the library receives it as a function.

**The facade prints.** The generated `Graphitron` class (the facade: the one generated entry point a consumer calls, which the library never names because it lives in a per-subgraph package) gains `public static String schemaSdl(GraphQLSchema schema)`. Which body it gets is a generate-time decision, the same `federationLink` flag that already picks the facade's `buildSchema` overloads:

- Federated arm: `ServiceSDLPrinter.generateServiceSDLV2(schema)`, then, when the schema uses `@oneOf` (the same `OneOfDirectiveSdl.usesOneOf(assembled)` gate `GraphitronSchemaClassGenerator` applies to `build`), the generated `OneOfDirectiveSdl` helper's string augment. The helper's string computation (print, idempotence check, append `DEFINITION`) is factored out of `withOneOfDefinition` into a `public static String` method both callers use, so the `_service.sdl` value and the facade's output come from one piece of code. The facade already compiles against federation-jvm in this arm (its two-argument `buildSchema` names `SchemaTransformer`), so nothing new reaches the consumer's classpath.
- Plain arm: graphql-java's `SchemaPrinter` with the options `SchemaSdlEmitter.printPlain` uses that are meaningful at runtime: `includeSchemaDefinition(true)`, `includeScalarTypes(true)`, `includeIntrospectionTypes(false)`. The emitter's two filters (`SchemaDirectiveRegistry::isSurvivor` for directives, the dropped-support-type element filter) are generator-side and vacuous here, because the runtime schema `GraphitronSchema.build` assembles registers neither generator-only directives nor unretained support types. A plain schema was never broken for composition; the arm exists so the facade method is total and a non-federated adapter takes the same one-line form.

The body is Java 17 in both arms.

**The SPI gains an SDL method.** `GraphitronApplication` (the SPI: the interface a consumer's adapter implements, and the only thing the library depends on) gains `default String schemaSdl()`. The default keeps today's behaviour, printing `schema()` with a default `SchemaPrinter`, so a consumer implementing the interface directly still compiles. Its javadoc says plainly that the fallback is not composition-ready for a federated subgraph and that an adapter should return the facade's `schemaSdl(schema())`. A default method rather than an abstract one follows `engineBuilder()`, `graphiqlEnabled()` and `builtInEndpointEnabled()`: the SPI is open and consumer-owned, and an abstract addition breaks every direct implementer at compile time. The default is deliberately not a better printer: a third copy of the federation print policy inside a runtime jar would have no enforcer, and the library cannot name federation-jvm.

**`AbstractGraphitronApplication` takes the printer as a second argument.** A new constructor `AbstractGraphitronApplication(Supplier<GraphQLSchema> schemaSupplier, Function<GraphQLSchema, String> sdlPrinter)` caches `sdlPrinter.apply(schema())` the way `schema()` caches the schema (computed at most once, lazily, thread-safe), and a `final` `schemaSdl()` override returns it. A `Function` over the schema rather than a `Supplier<String>`: arguments to `super(...)` cannot reference `this`, so a supplier could not reach `schema()`, and a consumer who wants different SDL passes its own function instead of overriding. The one-argument constructor stays and is `@Deprecated`, pointing at the two-argument form; with it, `schemaSdl()` falls through to the SPI default. Deprecation rather than removal keeps existing adapters compiling while making the one-line change discoverable at the consumer's next compile, which is how `utdanningsregisteret` and `kompetanse` learn about it.

**The handler serves it.** `GraphqlHttpHandler.schema()` returns `application.schemaSdl()`. `GraphqlResource.schema()` is unchanged; it already delegates to the handler, and so does any consumer-mounted resource (`PolicyMountedGraphqlResource` in the example's tests is one).

**The single-source claims stay true and stay put.** `GraphitronApplication.schema()`, `engineBuilder()`, `AbstractGraphitronApplication` (class and `schema()`) and `GraphqlHttpHandler.schema()` all say the engine and the SDL endpoint share one built schema. They still do: the endpoint prints `schema()`, so a `buildSchema` customizer's additions are served, and `/graphql/schema` and `_service.sdl` print the same schema with the same printer. Their javadoc needs only the printer's provenance added (the handler's "printed from" sentence names `GraphitronApplication.schemaSdl()`).

## Implementation

- `graphitron/.../generators/util/OneOfDirectiveSdlGenerator.java`: factor the string augment out of the emitted `withOneOfDefinition` into an emitted `public static String` method; `withOneOfDefinition` calls it.
- `graphitron/.../generators/schema/GraphitronFacadeGenerator.java`: emit `schemaSdl(GraphQLSchema)` with the arm-specific body; the federated arm calls the helper's string method under the `usesOneOf` gate. Add it to the class javadoc's list of what the facade exposes.
- `graphitron-jakarta-rest/.../GraphitronApplication.java`: add `default String schemaSdl()`; the `schema()` and `engineBuilder()` javadoc mention it as the SDL endpoint's source.
- `graphitron-jakarta-rest/.../AbstractGraphitronApplication.java`: two-argument constructor, cached `final schemaSdl()`, deprecate the one-argument constructor, update the class-javadoc example to the two-argument form.
- `graphitron-jakarta-rest/.../GraphqlHttpHandler.java`: `schema()` delegates to `application.schemaSdl()`; the `SchemaPrinter` import moves to the SPI default.
- `graphitron-sakila-example/.../app/SakilaGraphitronApplication.java`: switch to the two-argument constructor and add the SDL printer to its javadoc's list of seams.
- `graphitron-sakila-example/README.md`: the app section's "three seams" sentence names the schema supplier; extend it to the pair.
- `docs/manual/how-to/apollo-federation.adoc`: the user-docs section below.

## User documentation (first-client check)

A new section in `docs/manual/how-to/apollo-federation.adoc`, after "Build the federation-wrapped schema":

> == Publish the subgraph SDL
>
> `Graphitron.schemaSdl(schema)` prints a federated schema the way its `_service { sdl }` query answers: the `schema @link(...)` declaration included, the GraphQL built-in directive definitions left out, the form Federation 2 composition expects.
>
> If you serve the subgraph with `graphitron-jakarta-rest`, pass it to your adapter's constructor and `GET /graphql/schema` serves that SDL, so a supergraph can be composed from the schema URL as well as by introspection:
>
> ```java
> super(() -> Graphitron.buildSchema(b -> {}), Graphitron::schemaSdl);
> ```
>
> The SDL is printed from the schema your application executes, so anything your `buildSchema` customizer adds is included.

It reads simply, which is the check. The tutorial needs no change; a non-federated adapter takes the same constructor form.

## Tests

- `GraphitronFacadeGeneratorTest`: `generatedClass_exposesBuildSchemaBothFactoriesNewGraphQLAndRuntime` grows to include `schemaSdl`, and a case pins its signature (`public static String schemaSdl(GraphQLSchema)`) in both arms. No assertion on the method body's text; the behaviour is pinned below against the real generated facade.
- Compilation tier: `graphitron-sakila-example` compiles both emitted facades at `<release>17</release>`, which covers the method bodies' syntax.
- Pipeline tier, `graphitron-sakila-example` (the example's Quarkus app boots only the non-federated schema, so the federated half is pinned in-process): an adapter built with the two-argument constructor over the federated facade (`no.sikt.graphitron.generated.federated.Graphitron`; `newExecutionInput()` unused) returns from `schemaSdl()` exactly the string `{ _service { sdl } }` answers when executed against the same `schema()`. That one equality pins both the composition-ready property (the introspection path composes today) and the single-source claim. Beside it, two direct assertions on the same string name the properties the composer rejected: the schema-applied `@link` declaration referencing `FederationSpec.URL` (the pattern `SchemaSdlEmissionTest.federatedExecutionEmitsServiceSdl` uses), and no `directive @skip` or `directive @include` definition. The federated fixture applies `@oneOf` (`federated-schema.graphqls`, the fixture `OneOfDirectiveGateTest` names), so the equality also covers the factored `@oneOf` augment.
- Pipeline tier, plain arm: `Graphitron.schemaSdl(Graphitron.buildSchema(b -> {}))` for the non-federated facade parses to a schema structurally equal (`SchemaDiffing`, as `emittedSdlMatchesRuntimeSchema` does) to the emitted `no/sikt/graphitron/generated/schema.graphqls`. This is the enforcer for the claim that the plain arm's runtime options reproduce the emitter's, which nothing checks today.
- Pipeline tier, compatibility: an adapter built with the deprecated one-argument constructor returns non-empty SDL from `schemaSdl()`, so the fallback stays exercised.
- Execution tier, `graphitron-sakila-example`: a test beside `GraphqlResourceSmokeTest` asserts `GET /graphql/schema` returns `200`, `text/plain`, and a body equal to `SakilaGraphitronApplication`'s `schemaSdl()`, and that the body contains the `schema {` block today's default printer omits for this schema. This pins the HTTP route to the SPI method; with the in-process equality above it carries the regression to the HTTP surface.

## Other solutions we've considered

- **Serve the generated `schema.graphqls` from the classpath** (the backlog direction, and what `opptak` and `tilgangsstyring` do). It composes, but it turns the endpoint from a view of the executed schema into a copy of a generate-time artifact: a customizer's additions would execute without being served, `/graphql/schema` and `_service.sdl` could disagree, and a missing resource (native image, repackaged jar) would fail on a live request rather than at build time. Printing at runtime costs one print per application lifetime and keeps the invariant true by construction.
- **Make the library's default print composition-ready** (schema definition on, spec built-ins off, `@oneOf` reinstated). A third copy of the print policy, in a runtime jar that cannot name federation-jvm and has no test tying it to the other two.
- **Have the facade execute `{ _service { sdl } }`** and return the answer. Same string, but it runs an engine to read a constant and has nothing to say for the plain arm.

## Out of scope

- A composition check in the build. The composers are JavaScript; the properties the composer rejected are asserted directly, and the equality with `_service.sdl` ties the endpoint to the path that composes today.
- The runtime `_service.sdl` path, which already composes and only gains a shared helper method.
- The broader "serve your schema over HTTP" how-to (R530). That item should describe `/graphql/schema` as composition-ready once this lands; a line in its body records that.

# Plan: Graphitron emits a prebuilt programmatic GraphQLSchema

> **Status:** Spec

## Goal

Replace the emitted `Graphitron.java` facade's SDL + `RuntimeWiring`
assembly path with a prebuilt `GraphQLSchema`. Apps stop calling
`SchemaGenerator.makeExecutableSchema`, stop assembling a
`RuntimeWiring.Builder`, stop carrying the SDL resource on the runtime
classpath. `Graphitron.buildSchema(customizer)` returns a fully wired
`GraphQLSchema` with every fetcher attached at construction.

Secondary goal: emitted code depends on jOOQ and graphql-java only.
The `no.sikt.graphql.GraphitronContext` interface (currently retrieved
by every generated fetcher from `graphitron-common`) goes away.
Fetchers read `DSLContext`, tenant identifier, and context-argument
values directly from `env.getGraphQlContext()` using documented keys.

The SDL stays a build-time artifact for federation composition and
client documentation. It is generated from the programmatic schema via
`SchemaPrinter`, not consumed at runtime.
## Context

Outcome of an explicit A / B / C tradeoff exploration:

- **A.** Keep SDL + `RuntimeWiring.Builder`. Status quo; ecosystem-standard.
- **B.** Prebuilt programmatic `GraphQLSchema`. No runtime SDL parse. Apps
  get a ready-to-use schema.
- **C.** Middle ground: SDL input + emitted `GraphQLCodeRegistry`.

B was selected on three signals: (1) a clear UX win, apps call one
method and start serving, (2) zero `SchemaDirectiveWiring` in any known
consumer app, (3) `@notGenerated` drops to a validator rejection in
the same commit, so there is no escape-hatch regression to worry about.
Federation
SDL sort-order differences are acceptable as long as the emitted SDL is
semantically equal to what federation-jvm would serialize from the
built schema.
## Current state

Rewrite emits the following application-facing surface, matching the
legacy generator's shape (see `graphitron-codegen-parent/.../codeinterface/default/expected/Graphitron.java`):

```java
public class Graphitron {
    public static TypeDefinitionRegistry getTypeRegistry() { ... }
    public static RuntimeWiring.Builder getRuntimeWiringBuilder() { ... }
    public static RuntimeWiring getRuntimeWiring() { ... }
    public static GraphQLSchema getSchema() {
        var wiring = getRuntimeWiringBuilder();
        var registry = getTypeRegistry();
        return new SchemaGenerator().makeExecutableSchema(registry, wiring.build());
    }
}
```

Supporting emitted artifacts:

- `<TypeName>Fetchers` classes: hold the `DataFetcher` implementations.
- `<TypeName>Wiring` classes: bind field names to fetcher method
  references via `TypeRuntimeWiring.newTypeWiring(...)`. Per-type wiring
  landed at `cadab36` + `2c366bb`.
- `GraphitronWiring`: aggregator, calls `.type(<TypeName>Wiring.wiring())`
  for every emitted type.
- `TypeRegistry`: re-parses the SDL at runtime via
  `SchemaReadingHelper.getTypeDefinitionRegistry(...)`.
- Client SDL resource: shipped in the generated JAR, loaded at runtime
  by `TypeRegistry`.

Cost paid per application startup: SDL file read + parse + build
registry, plus `RuntimeWiring` assembly, plus
`SchemaGenerator.makeExecutableSchema` type-check and wiring pass.
## Target state

```java
public class Graphitron {
    /**
     * Builds the schema with all generator-emitted fetchers attached.
     * The customizer receives the underlying {@code GraphQLSchema.Builder}
     * for adding scalars, additional types, or custom directives before
     * {@code .build()} is called. Use additive methods only; do not call
     * {@code .query()}, {@code .mutation()}, {@code .subscription()}, or
     * {@code .clearDirectives()} on the builder.
     */
    public static GraphQLSchema buildSchema(Consumer<GraphQLSchema.Builder> customizer) { ... }

    /**
     * Returns the schema SDL, for federation composition and client docs.
     * Built lazily on first call from the no-customization schema and cached.
     */
    public static String getSdl() { ... }
}
```

Two methods, no hidden state beyond the lazy SDL cache (§D4). Apps
construct the graphql-java `GraphQL` engine and per-request
`ExecutionInput` themselves using stock graphql-java APIs; runtime
values they need to pass into fetchers go through the documented
`GraphQLContext` keys in §Runtime context plumbing.

New emitted surface:

- `<TypeName>Fetchers` classes: unchanged; hold DataFetcher
  implementations. Body changes only where `GraphitronContext` calls
  are replaced by direct `env.getGraphQlContext()` reads (§Runtime
  context plumbing).
- `<TypeName>Type` classes (rename from `<TypeName>Wiring`): expose a
  single `public static GraphQLObjectType type()` that builds a typed
  `GraphQLObjectType` with fetchers attached via a shared
  `GraphQLCodeRegistry.Builder`.
- `GraphitronSchema` (rename from `GraphitronWiring`): internal
  assembler that wires each `<TypeName>Type` into a top-level
  `GraphQLSchema.Builder`, registers the code registry, and builds the
  schema once. Scalars, extra types, and extra directives are applied
  by the user's `Consumer<GraphQLSchema.Builder>` before `.build()`.
- `GraphitronSdl`: emits the SDL string via
  `SchemaPrinter(SchemaPrinter.Options)` against the built schema,
  stripping generator-only directives. Built once on first call to
  `getSdl()`; cached.

Removed artifacts:

- `TypeRegistry` class: no runtime SDL parse, no need.
- `Wiring` aggregator in its current form.
- `SchemaReadingHelper` runtime dependency path: gone.
- `no.sikt.graphql.GraphitronContext` interface reference from emitted
  fetchers: gone. The `graphitron-common` runtime dep for emitted code
  drops away. Emitted fetchers depend only on `graphql-java` and
  `org.jooq`.
- Client SDL resource on the runtime classpath: optional. Emitted to
  `target/generated-sources/...` for federation composition and docs,
  but `Graphitron.getSdl()` can produce it from the programmatic schema
  on demand.
## Approach

Single breaking change; no parallel path, no deprecation cycle. The
rewrite pipeline is still pre-release, consumers track it on a known
cadence, and the generator stays easier to reason about without two
emission strategies living side by side.

**What lands in one commit:**

- New generator emits `<TypeName>Type` classes producing
  `GraphQLObjectType`. Types reference each other via
  `GraphQLTypeReference.typeRef("OtherType")` to sidestep topological
  ordering.
- New aggregator `GraphitronSchema` builds the `GraphQLSchema`, walks
  all emitted `<TypeName>Type` classes, wires `GraphQLCodeRegistry`
  with fetcher coordinates, attaches type resolvers for
  generator-produced interfaces / unions. Exposes the
  `GraphQLSchema.Builder` for user customization before calling
  `.build()`.
- New generator `GraphitronSdl` emits the client SDL via
  `SchemaPrinter(SchemaPrinter.Options)` against the built schema,
  stripping generator-only directives.
- `Graphitron.java` facade surface becomes `buildSchema(Consumer)` and
  `getSdl()`.
- Emitted fetchers replace every `graphitronContext(env).getXxx(...)`
  call with a direct `env.getGraphQlContext().get(...)` read; see
  §Runtime context plumbing for the key conventions.
- New `graphitron-rewrite/docs/getting-started.md`. See §Getting
  started document as API quality gate for the intended shape and
  why it's a constraint on the API, not just a deliverable.

**What is removed in the same commit:**

- `<TypeName>Wiring` emission.
- `GraphitronWiring` aggregator.
- `TypeRegistry` emission.
- `Graphitron.getTypeRegistry()`, `getRuntimeWiringBuilder()`,
  `getRuntimeWiring()`, `getSchema()` facade methods.
- Runtime SDL resource loading from the generated JAR.
- `GraphitronContext` interface references from emitted fetcher code
  (the per-class `graphitronContext(env)` helper and every call
  through it). The `graphitron-common` dep on emitted code drops away.

**Consumer impact.** Apps on the rewrite pipeline change in two
places: (1) startup wiring calls `Graphitron.buildSchema(b -> {...})`
in place of the `makeExecutableSchema` assembly (scalars move from
`RuntimeWiring.Builder.scalar(...)` to `b.additionalType(...)`); (2)
the request pipeline populates `ExecutionInput.graphQLContext` with
the `DSLContext` and any tenant / context-argument entries the
generated fetchers read (§Runtime context plumbing). Apps that
previously implemented `GraphitronContext` replace that with an
inline `graphQLContext` population step. Documented in a CHANGELOG
entry alongside the release with a before/after example.

Exit criteria: every execution test in `graphitron-rewrite-test-spec`
passes against the new path; the `Graphitron.java` emitted surface is
exactly the two methods in §Target state; no `SchemaReadingHelper`,
`RuntimeWiring`, or `no.sikt.graphql.GraphitronContext` references
remain in emitted code.
## Federation integration

`com.apollographql.federation:federation-graphql-java-support` accepts
a `GraphQLSchema` directly: `Federation.transform(builtSchema)`. Apps
compose:

```java
GraphQLSchema base = Graphitron.buildSchema(b -> {});
GraphQLSchema federated = Federation.transform(base)
    .resolveEntityType(resolver)
    .fetchEntities(entityFetcher)
    .build();
```

Two concrete decisions fall out:

**`_Service.sdl` content.** Federation's entity-exposing `_Service.sdl`
field needs an SDL string. Two sources:
- (a) Federation-jvm serializes the programmatic schema back to SDL.
  Sort order and formatting may differ from our emitted SDL, which is
  fine per the scope statement.
- (b) Apps pass our emitted SDL explicitly via
  `Federation.transform(schema, sdl).build()` to pin the exact output.

Recommend (a): one fewer moving part, semantic equality is guaranteed
because the SDL is derived from the same schema object. If a
consumer ever needs byte-stable SDL output for supergraph compose
testing, (b) is available as an escape hatch.

**Federation-only directives in the emitted schema.** `@key`,
`@external`, `@provides`, `@requires`, `@shareable`, `@override`,
`@tag` must survive to the SDL output for supergraph composition.
Under B, these directives are declared on `GraphQLObjectType` /
`GraphQLFieldDefinition` builders at construction time using
`GraphQLDirective`s that the generator emits. The client SDL output
then contains them by default. The directive-stripping concern from
the schema-transform umbrella simplifies: we keep what we want in the
programmatic schema and strip the rest at SDL-emission time.
## `@notGenerated` handling

Dropped as part of this plan. The directive declaration stays in
`directives.graphqls` so existing schema documents keep parsing, but
any use of `@notGenerated` on a field, argument, or input field is
rejected at validator time with a clear error: "`@notGenerated` is
not supported by the rewrite pipeline; the field must be fully
described by the schema."

Signals informing this call: alf's production schema
(post-legacy-`ElementRemovalFilter`) shows zero usages, and we may
never bring the escape hatch back. Adding a config-level extension
point for a directive we don't intend to support would be work that
gets deleted.

Consumer migration path: authors with remaining `@notGenerated`
fields either (a) describe the field fully via generator-supported
directives, or (b) switch to `@externalField` for the
"manually-implemented field" shape. `@externalField` (20 production
usages) is unaffected by this plan and keeps working: those fields
get fully generated fetchers that dispatch to the user's static
method, no extension point needed.
## Custom scalars, type resolvers, code registry

**Custom scalars.** The user's SDL today declares scalars like `UUID`,
`LocalDate`, `JSON` and the app registers `GraphQLScalarType` instances
against them via `RuntimeWiring.Builder.scalar(...)`. Under this plan,
apps register via the customizer:

```java
GraphQLSchema schema = Graphitron.buildSchema(b -> b
    .additionalType(ExtendedScalars.UUID)
    .additionalType(ExtendedScalars.Date));
```

Every scalar reference in the generated `<TypeName>Type` classes uses
`GraphQLTypeReference.typeRef("UUID")`; graphql-java's schema builder
resolves the reference against the registered `GraphQLScalarType` at
`.build()` time. Unregistered scalars fail at schema-build time with
graphql-java's native error naming the scalar and the referencing
types.

**Type resolvers for interfaces / unions.** The generator emits
`TypeResolver`s directly on `GraphQLInterfaceType.Builder` /
`GraphQLUnionType.Builder` for interfaces and unions that have
generated discrimination logic (via `@discriminate` / `@discriminator`).
For user-defined interfaces the app mutates the code registry
additively through the customizer:

```java
GraphQLSchema schema = Graphitron.buildSchema(b -> b
    .codeRegistry(cr -> cr.typeResolver("SomeInterface", myResolver)));
```

Ties into the backlog item "TypeResolver wiring for interface/union
types" (Cleanup §).

**Code registry.** Internal to `GraphitronSchema`. Fetchers attach by
`FieldCoordinates.coordinates(typeName, fieldName)`; the generator
produces the coordinates alongside each `GraphQLFieldDefinition` it
emits. End result is one `GraphQLCodeRegistry` attached to the final
`GraphQLSchema`. Apps never see the code registry directly.

## Runtime context plumbing

Generated fetchers need three things at request time: a `DSLContext`,
an optional tenant identifier, and values for any `@condition` /
`@tableMethod` `contextArguments`. Today these are brokered through
the `GraphitronContext` interface and retrieved from
`env.getGraphQlContext().get("graphitronContext")`. Under this plan
the interface goes away and fetchers read directly from
`env.getGraphQlContext()`.

**Conventions:**

| Value                    | Key                        | Access in fetcher |
|--------------------------|----------------------------|-------------------|
| `DSLContext`             | `DSLContext.class`         | `env.getGraphQlContext().get(DSLContext.class)` |
| Tenant identifier        | `"graphitron.tenant"`      | `env.getGraphQlContext().getOrDefault("graphitron.tenant", "")` |
| Context-argument value   | `"graphitron.arg.<name>"`  | `env.getGraphQlContext().get("graphitron.arg." + name)` |

Class-keyed for `DSLContext` (type-safe, zero collision risk with
user code, jOOQ is an allowed dep). String-keyed with a
`graphitron.` namespace for the two other values so consumer keys
never collide.

**App wire-up (per request):**

```java
DSLContext dsl = buildRequestDsl(req); // may set tenant session vars
ExecutionInput input = ExecutionInput.newExecutionInput(query)
    .graphQLContext(ctx -> ctx
        .of(DSLContext.class, dsl)
        .of("graphitron.tenant", req.tenantId())
        .of("graphitron.arg.userId", req.userId()))
    .build();
engine.executeAsync(input);
```

Apps that previously overrode `GraphitronContext.getDslContext(env)`
to inspect the DFE (e.g. to pick a tenant-scoped pool) do that
inspection before building the `ExecutionInput` instead. Every piece
of information those overrides used is available from the HTTP
request or equivalent entry point; the DFE itself carries nothing a
request filter does not already have.

**Codegen impact:** `TypeFetcherGenerator.buildGraphitronContextHelper`
and all `graphitronContext(env).getXxx(...)` call-site emissions
switch to the direct `env.getGraphQlContext().get(...)` form. The
`GRAPHITRON_CONTEXT` `ClassName` constant in `GeneratorUtils` is
removed.
## SDL as build artifact

SDL is produced from the built `GraphQLSchema` via graphql-java's
`SchemaPrinter`, with options tuned to:

- Include federation-relevant directives (`@key`, `@external`, etc.)
  and user-declared ones (`@deprecated`, app custom directives).
- Strip generator-only directives (`@table`, `@field`, `@condition`,
  `@lookupKey`, `@reference`, `@splitQuery`, `@asConnection`,
  `@service`, `@tableMethod`, etc.). These are implementation detail
  and must not leak to clients or federation.

Two consumption paths:

- **Build-time emission:** generator writes `target/generated-sources/.../schema.graphql`
  alongside generated Java. This is what the faceted-search plan and
  the "Rewrite emits the client SDL as generated output" umbrella item
  describe; B makes it strictly simpler because the source of truth is
  the programmatic schema.
- **Runtime via `Graphitron.getSdl()`:** for apps that want to expose
  the SDL at an operational endpoint (e.g. `/schema.graphql`) without
  loading a resource. The method builds the SDL once and caches.

The "directive stripping" umbrella item collapses into a single
`SchemaPrinter.Options` configuration inside `GraphitronSdl`. No
separate SDL-transformation pass, no cross-module enum to keep in
sync.
## Getting started document as API quality gate

Ships alongside the code: `graphitron-rewrite/docs/getting-started.md`.
Starting point: the reader has already run the legacy Maven plugin
and has generated sources on their classpath. The doc picks up from
"you have generated code, now what" and covers only the runtime
wiring side: build the schema, construct the engine, wire the
per-request `ExecutionInput`. Maven plugin configuration is out of
scope until the rewrite owns its own Mojo; legacy-plugin docs stay
where they are.

The document is also a design constraint. If any of the following
cases does not fit in a few lines each, that is a signal the API is
wrong and we iterate on `Graphitron` and on the §Runtime context
plumbing keys, not on the doc:

- **Hello world.** `Graphitron.buildSchema(b -> {})`, one
  `GraphQL.newGraphQL(schema).build()`, one `ExecutionInput` that
  puts a `DSLContext` in `graphQLContext`, done.
- **Custom scalar.** `Graphitron.buildSchema(b -> b.additionalType(ExtendedScalars.UUID))`.
- **Federation.** `Federation.transform(Graphitron.buildSchema(b -> {})).build()`.
- **Tenant-scoped DSLContext.** Request filter builds a
  tenant-configured `DSLContext` (e.g. via `SET LOCAL app.tenant_id`)
  and stuffs it under `DSLContext.class`.
- **Context arguments.** App reads `userId` from the JWT and puts
  it under `"graphitron.arg.userId"` before executing.

The last two cases are what point (5) above rides on: if the wire-up
demo for tenant DSLContext or context arguments runs long, that is
direct evidence the three-key convention is wrong and we revise it,
not the prose. Reviewer who finds a section growing past a few lines
should push back on the keys, not on the doc.

## Prerequisites

Ordered dependencies from the schema-transform umbrella:

1. **Rewrite owns schema loading + directive auto-injection**
   ([plan](plan-rewrite-owns-schema-loading.md), currently Spec). This
   plan kills the runtime SDL-parse path; the build-time path must
   live inside rewrite first.
2. **Rewrite owns type-extension merging** (backlog). Extension
   merging is a registry-level pre-pass; the programmatic schema
   consumes the merged classification output, not raw SDL. Needed
   before the `<TypeName>Type` emitter can trust that every field is
   visible on its declaring type.
3. **Rewrite owns `@asConnection` → Connection synthesis** (backlog).
   Connection / Edge / PageInfo types must exist in the classifier
   output so `<TypeName>Type` can emit them.

Items the umbrella reshapes or absorbs under B:

- **Rewrite owns `@notGenerated` element removal.** Obsolete under
  this plan: the directive becomes a validator error in the same
  commit, so there is never an SDL with surviving `@notGenerated`
  fields for a removal pass to operate on. The umbrella item closes
  without migration work.
- **Rewrite owns directive stripping in the emitted client SDL.**
  Collapses into `SchemaPrinter.Options`; see §SDL as build artifact.
- **Rewrite emits the client SDL as generated output.** Still wanted;
  this plan supplies the mechanism (`GraphitronSdl`).
- **Rewrite owns feature-flag SDL splits.** Unchanged in concept, but
  now operates on the classification model rather than SDL. Likely
  needs its own plan revision after B lands.
- **Rewrite owns federation SDL integration.** Simplified: federation
  wraps the prebuilt schema; no separate SDL integration pass needed
  unless a consumer insists on byte-stable `_Service.sdl` output.
## Tests

**Execution suite.** Every test in `graphitron-rewrite-test-spec` runs
against the new path, unchanged in intent. These are the functional
regression net: they already assert query response shapes and round-trip
counts; they pass iff the programmatic schema is semantically equal to
what the old path produced. They also double as the proof-of-life for
the `GraphQLContext` key conventions; the test harness populates
`DSLContext.class`, `"graphitron.tenant"`, and
`"graphitron.arg.<name>"` the same way real apps will.

**`@notGenerated` rejection test.** An SDL fixture with one
`@notGenerated` field; validator produces an error with the field's
coordinates and a clear message. Mirror case: an SDL with no
`@notGenerated` usage passes validation.

**Federation integration test.** A small SDL with `@key`, `@external`,
`@shareable`; wrap `Graphitron.buildSchema(b -> {})` via
`Federation.transform`; execute an `_entities` query; assert the
response. Pins the contract that federation-jvm consumes a
programmatically-built schema.

**Lint ratchet.** `GeneratedSourcesLintTest` asserts no
`RuntimeWiring`, `TypeRuntimeWiring`, `SchemaGenerator`,
`SchemaReadingHelper`, or `no.sikt.graphql.GraphitronContext` imports
appear in emitted code. Prevents regression into the old shape and
enforces the zero-runtime-dep invariant.
## Open decisions

**D1.** API shape for app customization. **Resolved.** Two-method
facade on `Graphitron`: `buildSchema(Consumer<GraphQLSchema.Builder>)`
and `getSdl()`. No engine factory, no execution helper, no
`GraphitronConfig` abstraction; graphql-java's native
`GraphQLSchema.Builder`, `GraphQL.newGraphQL(...)`, and
`ExecutionInput.newExecutionInput(...)` are the composition surface.
Runtime values the fetchers need (DSLContext, tenant id, context
arguments) travel through `ExecutionInput.graphQLContext` under the
keys documented in §Runtime context plumbing. Rationale: keeps the
emitted code dep-free (jOOQ + graphql-java only), avoids inventing
parallel APIs for problems graphql-java already solves, and lets the
"getting started" examples stay at a few lines each. Safe-surface
guidance for the customizer goes in javadoc on the `Consumer`
parameter ("use additive methods only; do not call `.query()`,
`.mutation()`, `.subscription()`, `.clearDirectives()`").

**D2.** Package for generated `<TypeName>Type` classes. **Resolved.**
New `<outputPackage>.rewrite.schema` package. Keeps the graphql-java
builder classes away from the existing `<outputPackage>.rewrite.types`
carriers and groups schema-building artifacts
(`<TypeName>Type`, `GraphitronSchema`, `GraphitronSdl`) under one
roof.

**D3.** `<TypeName>Type` class naming. **Resolved.** See §Target
state: `<TypeName>Type` mirrors graphql-java's own type-hierarchy
naming; method-level entry point is `FilmType.type()` returning a
`GraphQLObjectType`.

**D4.** `getSdl()` caching. **Resolved.** Lazy + cached in a
`static volatile` field. Eventually consistent, safe because the SDL
is deterministic from the immutable schema: a rare startup-time race
results in two concurrent `SchemaPrinter` invocations producing the
same string. Apps that never call `getSdl()` pay nothing.


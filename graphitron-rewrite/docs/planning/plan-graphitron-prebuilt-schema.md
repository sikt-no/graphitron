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
The `no.sikt.graphql.GraphitronContext` interface currently retrieved
by every generated fetcher from `graphitron-common` moves into the
generated output package, so emitted fetchers reference only types
the app's own build produces. The interface shape stays
DFE-aware so advanced consumers can continue to pick a
`DSLContext` or derive a context-argument value from the
`DataFetchingEnvironment` at call time. See §Runtime context
plumbing.

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
     * {@code .query()}, {@code .mutation()}, {@code .subscription()},
     * {@code .clearDirectives()}, or the replace overload
     * {@code .codeRegistry(GraphQLCodeRegistry)} (the
     * {@code .codeRegistry(UnaryOperator)} overload is fine, and is the
     * supported extension point for additional type resolvers).
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
  implementations. Body changes only where the `GraphitronContext`
  lookup retargets from `no.sikt.graphql.GraphitronContext` (legacy
  dep) to the locally-emitted
  `<outputPackage>.rewrite.schema.GraphitronContext`; see §Runtime
  context plumbing.
- `GraphitronContext` interface: emitted to
  `<outputPackage>.rewrite.schema.GraphitronContext`. Three
  DFE-aware methods (`getDslContext`, `getContextArgument`,
  `getTenantId`). Apps implement this generated type.
- `<TypeName>Type` classes (rename from `<TypeName>Wiring`): expose a
  single `public static GraphQLObjectType type(GraphQLCodeRegistry.Builder codeRegistry)`
  that builds a typed `GraphQLObjectType` and registers its fetchers
  on the passed-in code registry. The aggregator (`GraphitronSchema`)
  owns the single shared `GraphQLCodeRegistry.Builder` instance and
  hands it to each type in turn; `type(...)` cannot be called in
  isolation because the code registry is required for correctness.
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
- `no.sikt.graphql.GraphitronContext` reference from emitted
  fetchers: gone; replaced by the generated
  `<outputPackage>.rewrite.schema.GraphitronContext`. The
  `graphitron-common` runtime dep for emitted code drops away.
  Emitted fetchers depend only on `graphql-java`, `org.jooq`, and
  types the app's own build produces.
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
- New survivor-directive sub-pass in the `<TypeName>Type` emitter.
  For every directive application the classification model carries on
  a type / field / argument / input-field, the emitter checks the
  directive name against the survivors registry (federation set +
  user-declared custom directives; see §Directive emission strategy)
  and, on a hit, translates the application to a
  `GraphQLDirective.newDirective()` call on the matching graphql-java
  builder. Directive-argument values (scalar, list, object-literal,
  default) route through graphql-java's standard value translation.
  Generator-only directives are never emitted.
- New aggregator `GraphitronSchema` builds the `GraphQLSchema`, owns
  the shared `GraphQLCodeRegistry.Builder`, passes it to each
  `<TypeName>Type.type(codeRegistry)` in turn, attaches type resolvers
  for generator-produced interfaces / unions, and exposes the
  `GraphQLSchema.Builder` for user customization before calling
  `.build()`.
- New generator `GraphitronSdl` emits the client SDL via
  `SchemaPrinter(SchemaPrinter.Options)` against the built schema,
  stripping generator-only directives.
- `Graphitron.java` facade surface becomes `buildSchema(Consumer)` and
  `getSdl()`.
- New generator emits `<outputPackage>.rewrite.schema.GraphitronContext`
  as the per-app DFE-aware extension point (§Runtime context
  plumbing). The emitted `graphitronContext(env)` helper in each
  Fetchers class retargets to this generated type, keyed by
  `GraphitronContext.class` in `env.getGraphQlContext()`.
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
- Imports of `no.sikt.graphql.GraphitronContext` from emitted fetcher
  code (retargeted to the locally-emitted
  `<outputPackage>.rewrite.schema.GraphitronContext`). The
  `graphitron-common` dep on emitted code drops away.

**Consumer impact.** Apps on the rewrite pipeline change in two
places: (1) startup wiring calls `Graphitron.buildSchema(b -> {...})`
in place of the `makeExecutableSchema` assembly (scalars move from
`RuntimeWiring.Builder.scalar(...)` to `b.additionalType(...)`); (2)
their `GraphitronContext` implementation re-imports from
`<outputPackage>.rewrite.schema.GraphitronContext` instead of
`no.sikt.graphql.GraphitronContext`, and the `graphQLContext` key
moves from the string `"graphitronContext"` to the typed
`GraphitronContext.class`. Method signatures match (minus
`getDataLoaderName`, which rewrite no longer calls), so
implementation bodies carry over verbatim. Documented in a
CHANGELOG entry alongside the release with a before/after example.

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
`@tag` must survive to the SDL output for supergraph composition. See
§Directive emission strategy for how the `<TypeName>Type` emitter
places these on `GraphQLObjectType` / `GraphQLFieldDefinition` /
argument / input-field builders via `GraphQLDirective.newDirective()`.
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
`@tableMethod` `contextArguments`. Every value may depend on the
`DataFetchingEnvironment` at call time: the app may route to a
read-replica `DSLContext` for a specific operation, derive `userId`
from `env.getExecutionStepInfo()`, pick the tenant based on the
field being resolved. Pre-computing these values at request entry
and stashing them in `graphQLContext` works for the simple case but
loses that degree of freedom. The brokering interface must carry
the DFE through.

Today that interface is `no.sikt.graphql.GraphitronContext` in
`graphitron-common`. Emitted fetchers resolve it by
`env.getGraphQlContext().get("graphitronContext")`. The dep on
`graphitron-common` is the only thing standing between emitted code
and the jOOQ + graphql-java baseline.

**Under this plan the interface moves into the generated output.**
The generator emits
`<outputPackage>.rewrite.schema.GraphitronContext` as part of every
build. Apps implement that generated interface instead of the
`graphitron-common` one. Classpath consequence: emitted code imports
only the app's own generated types plus jOOQ + graphql-java; no
shared graphitron artifact on the runtime classpath.

**Emitted interface shape:**

```java
package <outputPackage>.rewrite.schema;

import graphql.schema.DataFetchingEnvironment;
import org.jooq.DSLContext;

public interface GraphitronContext {
    DSLContext getDslContext(DataFetchingEnvironment env);
    <T> T getContextArgument(DataFetchingEnvironment env, String name);
    default String getTenantId(DataFetchingEnvironment env) { return ""; }
}
```

Three methods. `getDataLoaderName` from the legacy interface is
dropped; the rewrite emitter computes DataLoader names itself from
`getTenantId(env)` plus `env.getExecutionStepInfo().getPath()` (see
`runtime-extension-points.md` §getDataLoaderName legacy note).

**App wire-up (per request):**

```java
ExecutionInput input = ExecutionInput.newExecutionInput(query)
    .graphQLContext(Map.of(GraphitronContext.class, myContext))
    .build();
engine.executeAsync(input);
```

Key is `GraphitronContext.class` (type-keyed against the generated
interface; zero collision risk). Apps build one `GraphitronContext`
instance per app or per request depending on what they need
mutable, then hand it in. Advanced implementations inspect the DFE
inside each method call, unchanged from today's pattern.

**Emitted fetcher access:**

```java
// Per Fetchers class, same shape as today, retargeted to the generated interface:
private static GraphitronContext graphitronContext(DataFetchingEnvironment env) {
    return env.getGraphQlContext().get(GraphitronContext.class);
}
// Call sites unchanged in shape:
DSLContext dsl = graphitronContext(env).getDslContext(env);
Object userId = graphitronContext(env).getContextArgument(env, "userId");
String tenant = graphitronContext(env).getTenantId(env);
```

**Codegen impact:** `GRAPHITRON_CONTEXT` in
`GeneratorUtils` (currently `ClassName.get("no.sikt.graphql", "GraphitronContext")`)
re-targets to `ClassName.get(outputPackage + ".rewrite.schema", "GraphitronContext")`.
`TypeFetcherGenerator.buildGraphitronContextHelper` changes only the
key expression (`.get("graphitronContext")` becomes
`.get(GraphitronContext.class)`). A new small generator emits the
`GraphitronContext.java` interface file into the schema package
once per build.

**Migration for consumers.** Apps that already implement
`no.sikt.graphql.GraphitronContext` change one import
(`no.sikt.graphql.GraphitronContext` to
`<outputPackage>.rewrite.schema.GraphitronContext`) and the
`graphQLContext` key (`"graphitronContext"` string to
`GraphitronContext.class`). Body of the implementation is
unchanged because the three methods match by signature
(minus `getDataLoaderName`, which rewrite-targeted apps already
don't call into).

## Directive emission strategy

Two classes of directives in the schema today:

1. **Survivors** — must reach the programmatic schema (and, via
   `SchemaPrinter`, the SDL output). Federation directives (`@key`,
   `@external`, `@provides`, `@requires`, `@shareable`, `@override`,
   `@tag`) and user-declared custom directives (`@deprecated`, app
   directives).
2. **Generator-only** — consumed at build time, never emitted.
   `@table`, `@field`, `@condition`, `@lookupKey`, `@reference`,
   `@splitQuery`, `@asConnection`, `@service`, `@tableMethod`,
   `@discriminate`, `@discriminator`, `@notGenerated`,
   `@externalField`.

Implementation: the `<TypeName>Type` emitter reads each schema
element's directive applications off the classification model; for
every application whose directive name is in the survivors set, the
emitter translates it to `GraphQLDirective.newDirective()` on the
`GraphQLObjectType` / `GraphQLFieldDefinition` / argument /
input-field builder. Generator-only applications are skipped.
Directive-argument values (scalars, lists, object literals, defaults)
go through graphql-java's standard value-translation path.

Consequence: generator-only directives never enter the programmatic
schema, so `SchemaPrinter.Options` needs no stripping pass — it just
preserves what's there. The "directive stripping" umbrella item
collapses to a survivors registry plus a single "known directive
names" check in the emitter. No cross-module enum to keep in sync.

## SDL as build artifact

SDL is produced from the built `GraphQLSchema` via graphql-java's
`SchemaPrinter` with `SchemaPrinter.Options.includeDirectives(true)`.
Because the programmatic schema already carries only survivor
directives (see §Directive emission strategy), the printer output
contains exactly what clients and federation need, nothing more.

Two consumption paths:

- **Build-time emission:** generator writes `target/generated-sources/.../schema.graphql`
  alongside generated Java. This is what the faceted-search plan and
  the "Rewrite emits the client SDL as generated output" umbrella item
  describe; B makes it strictly simpler because the source of truth is
  the programmatic schema.
- **Runtime via `Graphitron.getSdl()`:** for apps that want to expose
  the SDL at an operational endpoint (e.g. `/schema.graphql`) without
  loading a resource. The method builds the SDL once and caches.
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
wrong and we iterate on `Graphitron` and on the emitted
`GraphitronContext` shape, not on the doc:

- **Hello world.** Implement the generated `GraphitronContext` with
  a one-line `getDslContext` returning a shared `DSLContext`; build
  the schema via `Graphitron.buildSchema(b -> {})`; construct the
  engine via `GraphQL.newGraphQL(schema).build()`; per request,
  `ExecutionInput` stashes the context impl under
  `GraphitronContext.class`.
- **Custom scalar.** `Graphitron.buildSchema(b -> b.additionalType(ExtendedScalars.UUID))`.
- **Federation.** `Federation.transform(Graphitron.buildSchema(b -> {})).build()`.
- **Tenant-scoped DSLContext.** `GraphitronContext.getDslContext(env)`
  inspects the DFE (e.g. pulls tenant id from
  `env.getGraphQlContext()`), returns a per-tenant
  `DSLContext` configured with `SET LOCAL app.tenant_id`. No
  request-time pre-computation needed.
- **Context arguments from a JWT claim.**
  `GraphitronContext.getContextArgument(env, "userId")` reads the
  JWT (passed through `graphQLContext`) and returns the claim. The
  generator already emits the `getContextArgument(env, name)` call
  site for every `@condition contextArguments: ["userId"]`.

The last two cases are what point (5) above rides on: if the wire-up
demo for tenant DSLContext or context arguments runs long, that is
direct evidence the emitted `GraphitronContext` surface is wrong and
we revise it, not the prose. Reviewer who finds a section growing
past a few lines should push back on the interface, not on the doc.

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
the generated `GraphitronContext` contract: the test harness
implements the emitted interface and stashes it under
`GraphitronContext.class` in `graphQLContext` exactly as real apps
will.

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
`.mutation()`, `.subscription()`, `.clearDirectives()`, or the
replace overload `.codeRegistry(GraphQLCodeRegistry)` — the
`UnaryOperator` overload is the supported extension point for
additional type resolvers").

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


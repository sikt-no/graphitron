# Plan: Graphitron emits a prebuilt programmatic GraphQLSchema

> **Status:** Spec

## Goal

Replace the emitted `Graphitron.java` facade's SDL + `RuntimeWiring`
assembly path with a prebuilt `GraphQLSchema` that the application gets
ready to use. Apps stop calling `SchemaGenerator.makeExecutableSchema`,
stop assembling a `RuntimeWiring.Builder`, stop carrying the SDL
resource on the runtime classpath. `Graphitron.getSchema()` returns a
fully wired `GraphQLSchema` with every fetcher attached at construction.

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
    /** Returns the prebuilt schema with all fetchers attached. */
    public static GraphQLSchema getSchema() { ... }

    /** Returns the schema SDL, for federation composition and client docs. */
    public static String getSdl() { ... }

    /**
     * Builds a schema with application customization (custom scalars,
     * federation entity resolvers).
     */
    public static GraphQLSchema buildSchema(GraphitronConfig config) { ... }
}
```

New emitted surface:

- `<TypeName>Fetchers` classes: unchanged; hold DataFetcher
  implementations.
- `<TypeName>Type` classes (rename from `<TypeName>Wiring`): expose a
  single `public static GraphQLObjectType type()` that builds a typed
  `GraphQLObjectType` with fetchers attached via a shared
  `GraphQLCodeRegistry.Builder`.
- `GraphitronSchema` (rename from `GraphitronWiring`): internal
  assembler that wires each `<TypeName>Type` into a top-level
  `GraphQLSchema` builder, registers the code registry, attaches
  custom scalars from `GraphitronConfig`, and builds the schema once.
- `GraphitronSdl`: emits the SDL string via
  `SchemaPrinter(SchemaPrinter.Options)` against the built schema,
  stripping generator-only directives. Built once on first call to
  `getSdl()`; cached.

Removed artifacts:

- `TypeRegistry` class: no runtime SDL parse, no need.
- `Wiring` aggregator in its current form.
- `SchemaReadingHelper` runtime dependency path: gone. The
  `graphitron-common` runtime dep for emitted code drops away with it.
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
- New aggregator `GraphitronSchemaAssembler` builds the
  `GraphQLSchema`, walks all emitted `<TypeName>Type` classes, wires
  `GraphQLCodeRegistry` with fetcher coordinates, registers custom
  scalars from `GraphitronConfig`, attaches type resolvers for
  interfaces / unions.
- New generator `GraphitronSdl` emits the client SDL via
  `SchemaPrinter(SchemaPrinter.Options)` against the built schema,
  stripping generator-only directives.
- `Graphitron.java` facade surface becomes `getSchema()`,
  `getSdl()`, `buildSchema(GraphitronConfig)`.
- New `graphitron-rewrite/docs/getting-started.md`. See §Getting
  started document as API quality gate for the intended shape and
  why it's a constraint on the API, not just a deliverable.

**What is removed in the same commit:**

- `<TypeName>Wiring` emission.
- `GraphitronWiring` aggregator.
- `TypeRegistry` emission.
- `Graphitron.getTypeRegistry()`, `getRuntimeWiringBuilder()`,
  `getRuntimeWiring()` facade methods.
- Runtime SDL resource loading from the generated JAR.
- The `graphitron-common` runtime dependency on emitted code.

**Consumer impact.** Apps on the rewrite pipeline adapt their startup
wiring to call `Graphitron.getSchema()` in place of the
`makeExecutableSchema` assembly. The release notes call out the
breaking change and include a three-line before/after example.
Documented in a CHANGELOG entry alongside the release.

Exit criteria: every execution test in `graphitron-rewrite-test-spec`
passes against the new path; the `Graphitron.java` emitted surface is
exactly the three methods above; no `SchemaReadingHelper` references
remain in generated code; no `RuntimeWiring` references remain in
generated code.
## Federation integration

`com.apollographql.federation:federation-graphql-java-support` accepts
a `GraphQLSchema` directly: `Federation.transform(builtSchema)`. Apps
compose:

```java
GraphQLSchema base = Graphitron.getSchema();
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
against them via `RuntimeWiring.Builder.scalar(...)`. In the
programmatic world this moves to `GraphitronConfig`:

```java
GraphQLSchema schema = Graphitron.buildSchema(GraphitronConfig.builder()
    .scalar("UUID", ExtendedScalars.UUID)
    .scalar("LocalDate", ExtendedScalars.Date)
    .build());
```

Every schema scalar reference in the generated `<TypeName>Type`
classes uses `GraphQLTypeReference.typeRef("UUID")` at construction;
the assembler resolves the reference against the config-supplied
scalar instance at build time. Unregistered scalars: build fails with
a clear error that names the scalar and the field(s) using it.

**Type resolvers for interfaces / unions.** Today these wire through
`RuntimeWiring.Builder.type("Foo").typeResolver(...)`. In B, they
attach to `GraphQLInterfaceType.Builder.typeResolver(...)` at
construction. The generator emits the `TypeResolver` directly for
interfaces / unions that have generated discrimination logic (via
`@discriminate` / `@discriminator`); apps supply custom ones via
`GraphitronConfig.typeResolver("Foo", resolver)` for user-defined
interfaces. Ties into the backlog item "TypeResolver wiring for
interface/union types" (Cleanup §).

**Code registry.** Internal to `GraphitronSchemaAssembler`. Fetchers
attach by `FieldCoordinates.coordinates(typeName, fieldName)`; the
generator produces the coordinates alongside each `GraphQLFieldDefinition`
it emits. End result is one `GraphQLCodeRegistry` attached to the
final `GraphQLSchema`. Apps never see the code registry directly.
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
wiring side: call `Graphitron.getSchema()`, hand it to the HTTP
layer, done. Maven plugin configuration is out of scope until the
rewrite owns its own Mojo; legacy-plugin docs stay where they are.

The document is also a design constraint. If any of the following
cases doesn't fit in a few lines each, that's a signal the API is
wrong and we iterate on `Graphitron` / `GraphitronConfig`, not on the
doc:

- "Hello world": instantiate, serve one query.
- Custom scalar: register one `GraphQLScalarType`.
- Federation: wrap the schema.

This flips the usual relationship. The doc is not a crutch to explain
an awkward API; it is a test that the API didn't become awkward. A
reviewer who finds a section growing past a few lines should push
back on the API, not on the prose.

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
what the old path produced.

**SDL semantic-equality test.** `GraphitronSdl.emit(schema)` is parsed
back via `SchemaParser` + `SchemaGenerator.makeExecutableSchema`; the
resulting schema is compared to the original for structural equality
(types, fields, directives, argument definitions). Byte equality is
not asserted. Covers the federation concern: whatever `SchemaPrinter`
produces, federation-jvm can consume equivalently.

**`@notGenerated` rejection test.** An SDL fixture with one
`@notGenerated` field; validator produces an error with the field's
coordinates and a clear message. Mirror case: an SDL with no
`@notGenerated` usage passes validation.

**Federation integration test.** A small SDL with `@key`, `@external`,
`@shareable`; wrap `Graphitron.getSchema()` via `Federation.transform`;
execute an `_entities` query; assert the response. Pins the contract
that federation-jvm consumes a programmatically-built schema.

**Lint ratchet.** `GeneratedSourcesLintTest` asserts no
`RuntimeWiring`, `TypeRuntimeWiring`, `SchemaGenerator`, or
`SchemaReadingHelper` imports appear in emitted code. Prevents
regression into the old shape.
## Open decisions

**D1.** `GraphitronConfig` vs. lambda customizer vs. subclass hook.
Three shapes for app customization: builder (as drafted), `buildSchema(Consumer<SchemaBuilder>)`,
or `abstract class Graphitron` that apps extend. Recommend builder:
discoverable, IDE-autocomplete-friendly, testable. Lambda is terser
but less discoverable; subclass forces inheritance where composition
suffices.

**D2.** Package for generated `<TypeName>Type` classes. Options:
`<outputPackage>.rewrite.types` (reuses existing types package), or
a new `<outputPackage>.rewrite.schema` package. Reusing `.types`
collides with the existing `TypeClassGenerator` output (the internal
`GraphitronValues`-style carriers). Recommend a new `.schema` package
for clarity.

**D3.** `<TypeName>Type` class naming. Alternatives considered:
`<TypeName>Type`, `<TypeName>Schema`, `<TypeName>Builder`. Recommend
`<TypeName>Type` mirroring graphql-java's own type-hierarchy naming
(`GraphQLObjectType`, `GraphQLInterfaceType`).

**D4.** `getSdl()` caching. Options: build on every call, build once
and cache in a `static volatile`, build at class-init time. Recommend
lazy + cached: no work for apps that don't call it, one-time cost
when they do.

**D5.** `GraphitronConfig` lives where. The rewrite module (closest
to generation) or a new `graphitron-rewrite-runtime` module (separate
from the generator, ships only runtime-facing types). Recommend
keeping it in rewrite; revisit module extraction later if the runtime
surface grows.


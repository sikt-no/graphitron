# Plan: Graphitron emits a prebuilt programmatic GraphQLSchema

> **Status:** Ready
>
> **In flight.** Roadmap is `[In Progress]`. Commit A landed at
> `81fa607`: `GraphitronContext` emitted into `<outputPackage>.rewrite.schema`,
> helper retargeted, class-keyed lookup.
>
> Commit B landed across nine sub-commits (`5b4ecce` -> `4088cb1`). Structural
> pass (`5b4ecce` -> `7437fd0`): directive survivor registry, enum / input /
> object / interface / union `<TypeName>Type` emitters, `GraphitronSchema`
> assembler, `Graphitron` facade, wired into `GraphQLRewriteGenerator.generate()`
> alongside the legacy emitters. Functional pass (`ab64db5` -> `4088cb1`):
> fetcher registration via a legacy-wiring bridge (`ObjectTypeGenerator`
> emits `registerFetchers(codeRegistry)` that copies fetchers from the
> matching `<TypeName>Wiring.wiring().build()` into the code registry keyed
> by `FieldCoordinates`; the assembler invokes it for every bridged type);
> survivor directive definitions + applications (`DirectiveDefinitionEmitter`
> drives `schemaBuilder.additionalDirective(...)` for survivors; every
> per-type emitter calls `AppliedDirectiveEmitter.applicationsFor(...)` on
> type / field / argument / input-field / enum-value builders; argument
> values translate through `GraphQLValueEmitter`); default argument and
> input-field values round-trip via `.defaultValueProgrammatic(...)`.
>
> 651 rewrite unit tests green (89 new). Legacy `<TypeName>Wiring`,
> `GraphitronWiring`, `TypeRegistry`, and the SDL runtime resource remain in
> place until Commit C deletes them.
>
> Execution-tier verification landed at `dabfba3` (`mvn test -pl
> :graphitron-rewrite-test -Plocal-db` against the web-sandbox native
> PostgreSQL): 114 pass, 0 fail. Two pre-existing gaps fixed on the way
> in: (1) `GraphQLQueryTest` still wired `GraphitronContext` at the pre-A
> string key against the upstream interface; switched to the generated
> interface + class-keyed lookup; (2) the generated-sources `var` lint
> caught three Commit B helper locals (`typeWiring`, `codeRegistry`,
> `schemaBuilder`); replaced with explicit JavaPoet `$T` references.
>
> **Commit C remains unchanged**: remove legacy emitters, replace the
> `registerFetchers` bridge with direct `FetcherEmitter` calls, add
> `@notGenerated` validator rejection, add lint ratchet, write
> `graphitron-rewrite/docs/getting-started.md`.

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

SDL emission (both the build-time `schema.graphql` artifact and a
runtime `getSdl()` accessor) is **out of scope for this plan** and
deferred to a follow-up. The programmatic schema is the only
generator output under this plan; the §Directive emission strategy
invariant (generator-only directives never enter the programmatic
schema) leaves it in a clean state for a follow-up SDL emitter that
calls `SchemaPrinter` against it. Apps that need an SDL today can
call `SchemaPrinter` themselves against the built schema in a few
lines.
## Context

Outcome of an explicit A / B / C tradeoff exploration:

- **A.** Keep SDL + `RuntimeWiring.Builder`. Status quo; ecosystem-standard.
- **B.** Prebuilt programmatic `GraphQLSchema`. No runtime SDL parse. Apps
  get a ready-to-use schema.
- **C.** Middle ground: SDL input + emitted `GraphQLCodeRegistry`.

B was selected on three signals: (1) concrete quality wins, no SDL
parse at startup, no stringly-typed wiring builder, emitted code
depends only on jOOQ and graphql-java, (2) zero
`SchemaDirectiveWiring` in any known consumer app, (3) `@notGenerated`
drops to a validator rejection in the same commit, so there is no
escape-hatch regression to worry about. Apps still build the
`GraphQL` engine and `ExecutionInput` themselves using stock
graphql-java APIs; the win is in what those composition steps no
longer have to carry, not in their count.
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
}
```

One method, no hidden state. Apps construct the graphql-java `GraphQL`
engine and per-request `ExecutionInput` themselves using stock
graphql-java APIs; runtime values they need to pass into fetchers go
through the documented `GraphQLContext` keys in §Runtime context
plumbing. SDL emission is deferred (see §Goal); apps that need an SDL
today call `SchemaPrinter` against the built schema.

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
- `<TypeName>Type` classes (rename from `<TypeName>Wiring`): expose
  two static methods. `public static GraphQLObjectType type()` is
  pure, returns the `GraphQLObjectType`. `public static void
  registerFetchers(GraphQLCodeRegistry.Builder codeRegistry)`
  attaches the type's fetchers by coordinate on the passed-in
  registry. The aggregator (`GraphitronSchema`) calls both in
  sequence; pure return + explicit side-effect keeps each method
  single-purpose and leaves room for test callers that want just
  the type structure.
- `GraphitronSchema` (rename from `GraphitronWiring`): internal
  assembler that wires each `<TypeName>Type` into a top-level
  `GraphQLSchema.Builder`, registers the code registry, and builds the
  schema once. Scalars, extra types, and extra directives are applied
  by the user's `Consumer<GraphQLSchema.Builder>` before `.build()`.

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
- Client SDL resource on the runtime classpath: gone, with no
  replacement in this plan. Build-time SDL emission and a runtime
  `getSdl()` accessor are both deferred to a follow-up (§Goal);
  consumers that need an SDL today call `SchemaPrinter` against the
  built schema themselves.
## Approach

Breaking change landing across a small number of commits (three in
flight). Each commit compiles and keeps the rewrite unit-test suite
green; the old path stays fully functional until Commit C deletes it.
Rewrite is pre-release so we don't owe a parallel path.

### Commit A: `GraphitronContext` retargeting (landed `81fa607`)

New `GraphitronContextInterfaceGenerator` emits the interface into
`<outputPackage>.rewrite.schema.GraphitronContext`. `GeneratorUtils`
exposes `graphitronContext()` (a method, not a constant, because the
`ClassName` depends on `RewriteConfig.outputPackage()` which isn't
available at class-init; matches the existing `ResolvedTableNames.of(...)`
pattern). `TypeFetcherGenerator.buildGraphitronContextHelper` returns
the retargeted type and keys on `GraphitronContext.class`.
`getDataLoaderName` from the legacy interface dropped; rewrite
computes the name itself. 562 rewrite tests green.

### Commit B: new emission path

**What lands.**

- New `<TypeName>Type` generator emitting one class per GraphQL type
  in `<outputPackage>.rewrite.schema`. Class name convention
  `<TypeName>Type`; two static methods per D-resolution:
  `public static <GraphQLObjectType|Interface|Union|InputObject|Enum> type()`
  (pure, returns the graphql-java type) and
  `public static void registerFetchers(GraphQLCodeRegistry.Builder codeRegistry)`
  (side effect, attaches fetcher references by
  `FieldCoordinates.coordinates(typeName, fieldName)`). Fetcher
  references target the existing `<TypeName>Fetchers` class, unchanged.
- Cross-type references use `GraphQLTypeReference.typeRef("OtherType")`
  to sidestep topological ordering.
- Survivor-directive registry as a small registry class (constants
  for federation set; accepts user-declared custom directives detected
  from the classifier output). `<TypeName>Type` emitters read directive
  applications off the classification model and translate each survivor
  to a `GraphQLDirective.newDirective()` call on the matching builder.
  Generator-only applications are skipped (§Directive emission strategy).
  Definitions get added to the schema via
  `schemaBuilder.additionalDirective(...)` in the assembler.
- New `GraphitronSchema` aggregator: owns the shared
  `GraphQLCodeRegistry.Builder`, walks `<TypeName>Type` classes in a
  stable order, identifies root types (`Query`, `Mutation`,
  `Subscription`) by name and routes them through
  `schemaBuilder.query(...)/mutation(...)/subscription(...)`
  instead of `additionalType(...)`, invokes the user's
  `Consumer<GraphQLSchema.Builder>`, and calls `.build()`.
- New `Graphitron.java` facade: single public static
  `buildSchema(Consumer<GraphQLSchema.Builder>)` method that delegates
  to `GraphitronSchema`.
- `GraphQLRewriteGenerator.generate()` writes the new artifacts.

**What stays temporarily.** Legacy `<TypeName>Wiring`,
`GraphitronWiring`, `TypeRegistry`, and the legacy `Graphitron` facade
stay emitted. This keeps the generated-sources tree compilable during
Commit B and lets `graphitron-rewrite-test` (execution-tier) continue
to work against the old shape until Commit C.

**Build-order notes.**
1. Schema-directive registry (small standalone class). Exercise with
   a unit test asserting survivor set.
2. Input/enum `<TypeName>Type` generators first (leaf types, no
   cross-references, smallest surface). Output: two small generators
   + unit tests pinning emitted type shape.
3. Object/interface/union `<TypeName>Type` generators. Output:
   structurally similar to the existing `<TypeName>Wiring` emitters;
   the difference is building a `GraphQLObjectType` vs. a
   `TypeRuntimeWiring.Builder`. Reuse field-definition emission
   helpers where they already exist.
4. `GraphitronSchema` aggregator. Reuse patterns from
   `GraphitronWiringClassGenerator` for root discovery. Unit test
   asserts emitted Java compiles and calls the expected methods.
5. `Graphitron.java` facade. Small.
6. Wire all of this into `GraphQLRewriteGenerator.generate()` with
   `write(..., "rewrite.schema")` alongside the interface.

### Commit C: removal + validator + lint + docs

- Delete `WiringClassGenerator`, `GraphitronWiringClassGenerator`,
  `TypeRegistryMethodGenerator`, and their call sites in
  `GraphQLRewriteGenerator.generate()`.
- Delete the legacy `Graphitron.java` facade generator.
- `GraphitronSchemaValidator`: reject `@notGenerated` applications on
  `FIELD_DEFINITION | ARGUMENT_DEFINITION | INPUT_FIELD_DEFINITION` with
  the error message in §`@notGenerated` handling.
- Add lint ratchet per §Tests.
- Write `graphitron-rewrite/docs/getting-started.md` per §Getting
  started document as API quality gate.

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
exactly the single method in §Target state; no `SchemaReadingHelper`,
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
field needs an SDL string. Federation-jvm serializes the programmatic
schema back to SDL on demand; that's the only path under this plan,
because Graphitron itself no longer emits SDL (§Goal). If a consumer
ever needs byte-stable SDL output for supergraph compose testing,
they produce it by calling `SchemaPrinter` against the built schema
themselves and passing the result via
`Federation.transform(schema, sdl).build()`.

**Federation-only directives in the emitted schema.** `@key`,
`@external`, `@provides`, `@requires`, `@shareable`, `@override`,
`@tag` must survive to any downstream SDL for supergraph composition,
which means they must reach the programmatic schema. See §Directive
emission strategy for how the `<TypeName>Type` emitter places these on
`GraphQLObjectType` / `GraphQLFieldDefinition` / argument /
input-field builders via `GraphQLDirective.newDirective()`.
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

**Codegen impact (landed in Commit A, `81fa607`):**
`GeneratorUtils.GRAPHITRON_CONTEXT` the constant became
`GeneratorUtils.graphitronContext()` the method, so the `ClassName`
can read `RewriteConfig.outputPackage()` at call time; class-init
constants see the config as null. Matches the existing
`ResolvedTableNames.of(...)` pattern.
`TypeFetcherGenerator.buildGraphitronContextHelper` returns the
method-computed type and keys on `$T.class` (where `$T` is the
retargeted class). A new `GraphitronContextInterfaceGenerator` in
`generators/util/` emits the interface file once per build.

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

1. **Survivors.** Must reach the programmatic schema (and, via
   `SchemaPrinter`, any downstream SDL output a future SDL-emitter
   plan produces). Federation directives (`@key`, `@external`,
   `@provides`, `@requires`, `@shareable`, `@override`, `@tag`) and
   user-declared custom directives (`@deprecated`, app directives).
2. **Generator-only.** Consumed at build time, never emitted.
   `@table`, `@field`, `@condition`, `@lookupKey`, `@reference`,
   `@splitQuery`, `@asConnection`, `@service`, `@tableMethod`,
   `@discriminate`, `@discriminator`, `@notGenerated`,
   `@externalField`.

Implementation, two layers:

1. **Definitions.** `GraphitronSchema` calls
   `schemaBuilder.additionalDirective(...)` once per survivor
   directive declared in the SDL. Without a definition on the schema,
   graphql-java rejects applications at build time and
   `SchemaPrinter.includeDirectives(true)` omits the directive from
   downstream SDL output.
2. **Applications.** The `<TypeName>Type` emitter reads each schema
   element's directive applications off the classification model; for
   every application whose directive name is in the survivors set, it
   translates the application to `GraphQLDirective.newDirective()` on
   the `GraphQLObjectType` / `GraphQLFieldDefinition` / argument /
   input-field builder. Generator-only applications are skipped.
   Directive-argument values (scalars, lists, object literals,
   defaults) go through graphql-java's standard value-translation
   path.

Consequence: generator-only directives never enter the programmatic
schema. A future SDL-emitter plan can call `SchemaPrinter` against the
built schema with `SchemaPrinter.Options.includeDirectives(true)` and
get exactly the survivors in the output: no stripping pass, no
cross-module enum to keep in sync. The "directive stripping" umbrella
item collapses to a survivors registry plus a single "known directive
names" check in this emitter.

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

The last two cases are the point of the whole gate: if the wire-up
demo for tenant `DSLContext` or context arguments runs long, that is
direct evidence the emitted `GraphitronContext` surface is wrong and
we revise it, not the prose. Reviewer who finds a section growing
past a few lines should push back on the interface, not on the doc.

## Prerequisites

None hard. This plan changes only the emitted *output* shape
(generated fetcher-wiring classes, the `Graphitron.java` facade, the
runtime SDL resource). Build-time input handling is untouched:
rewrite continues to read the input schema via whatever mechanism
it uses when this plan lands.

Umbrella items that sound like prerequisites but aren't:

- **Rewrite owns schema loading** concerns the build-time parser
  (`SchemaReadingHelper` in `graphitron-common`). This plan removes
  the runtime SDL-parse path, which is a distinct concern; the
  build-time path can stay on the legacy helper until the
  schema-loading plan lands separately.
- **Rewrite owns type-extension merging** and **Rewrite owns
  `@asConnection` → Connection synthesis** are umbrella items that
  migrate existing schema-transform passes into rewrite. The rewrite
  classifier today already sees merged extensions (graphql-java's
  parser handles them) and synthesized Connection types (from
  `MakeConnections`); this plan consumes whatever the classifier
  produces, so neither migration is required first.

**Landing order.** No rewrite consumers today, so this plan can land
ahead of the schema-transform umbrella items and the
umbrella reshapes around it once it's in.

Items the umbrella reshapes or absorbs under B:

- **Rewrite owns `@notGenerated` element removal.** Obsolete under
  this plan: the directive becomes a validator error in the same
  commit, so there is never an SDL with surviving `@notGenerated`
  fields for a removal pass to operate on. The umbrella item closes
  without migration work.
- **Rewrite owns directive stripping in the emitted client SDL.**
  Reshaped: this plan makes the stripping pass unnecessary (§Directive
  emission strategy); a follow-up SDL emitter consumes the already-clean
  programmatic schema.
- **Rewrite emits the client SDL as generated output.** Still wanted,
  deferred to a follow-up plan. This plan leaves the programmatic
  schema in a clean state (survivor directives only) so the follow-up
  is a thin `SchemaPrinter` wrapper plus Mojo plumbing for the output
  path.
- **Rewrite owns feature-flag SDL splits.** Unchanged in concept, but
  now operates on the classification model rather than SDL. Likely
  needs its own plan revision after B lands.
- **Rewrite owns federation SDL integration.** Simplified: federation
  wraps the prebuilt schema; federation-jvm serializes `_Service.sdl`
  on demand from it.
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

**Context-key contract test.** Two cases, both exercise the emitted
`graphitronContext(env)` helper directly. Happy path: app provides a
`GraphitronContext` implementation under
`GraphitronContext.class` in `graphQLContext`; the emitted helper
returns it; a query resolves successfully. Error-quality path: no
context provided; the helper surfaces a clear error at the first
fetcher that dereferences it, naming the missing key (not a bare
NullPointerException). Pins both the key name and the quality of the
"you forgot to wire the context" signal.

**Lint ratchet.** `GeneratedSourcesLintTest` asserts no
`graphql.schema.idl.RuntimeWiring`,
`graphql.schema.idl.TypeRuntimeWiring`,
`graphql.schema.idl.SchemaGenerator`,
`no.sikt.graphql.schema.SchemaReadingHelper`, or
`no.sikt.graphql.GraphitronContext` imports appear in emitted code.
Match is on the FQN, not the simple name, so the generated
`<outputPackage>.rewrite.schema.GraphitronContext` is not affected.
Prevents regression into the old shape and enforces the
zero-legacy-dep invariant on emitted code.

## Environment notes

Unit-tier tests (under `graphitron-rewrite/src/test/`) run without
Docker; use these during Commit B construction. 562 tests green
after Commit A is the baseline to beat.

Execution-tier tests (`graphitron-rewrite-test-spec` and
`graphitron-rewrite-test`) require Docker for legacy jOOQ codegen
and thus cannot run in the Claude Code web sandbox. Plan is to
verify these in a Docker-enabled environment before flipping the
roadmap In Progress -> In Review.

Web-sandbox setup for fixtures-jar (if it gets clobbered):

```
pg_ctlcluster 16 main start
sudo -u postgres psql -c "ALTER USER postgres PASSWORD 'postgres';"
sudo -u postgres psql -c "CREATE DATABASE rewrite_test;"
PGPASSWORD=postgres psql -h localhost -U postgres -d rewrite_test \
    -f graphitron-rewrite/graphitron-rewrite-fixtures/src/main/resources/init.sql
mvn install -pl :graphitron-rewrite-fixtures -am -Plocal-db -DskipTests -q
```

See `graphitron-rewrite/docs/claude-code-web-environment.md` for
the detailed web-sandbox playbook.

## Open decisions

**D1.** API shape for app customization. **Resolved.** Single-method
facade on `Graphitron`: `buildSchema(Consumer<GraphQLSchema.Builder>)`.
No engine factory, no execution helper, no SDL accessor, no
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
replace overload `.codeRegistry(GraphQLCodeRegistry)`, since the
`UnaryOperator` overload is the supported extension point for
additional type resolvers").

**D2.** Package for generated `<TypeName>Type` classes. **Resolved.**
New `<outputPackage>.rewrite.schema` package. Keeps the graphql-java
builder classes away from the existing `<outputPackage>.rewrite.types`
carriers and groups schema-building artifacts
(`<TypeName>Type`, `GraphitronSchema`) under one roof.

**D3.** `<TypeName>Type` class naming. **Resolved.** See §Target
state: `<TypeName>Type` mirrors graphql-java's own type-hierarchy
naming; method-level entry point is `FilmType.type()` returning a
`GraphQLObjectType`.

**D4.** *(Withdrawn.)* Previously covered `getSdl()` caching; SDL
emission is now out of scope for this plan (§Goal).


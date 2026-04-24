# Getting started with the Graphitron rewrite runtime

You have generated sources on your classpath. This doc covers what to write in
your app to stand up a working GraphQL endpoint against them. Build-time Maven
plugin *configuration* is out of scope here; see the legacy-plugin docs for
that until the rewrite owns its own Mojo. The dev-loop behaviour the generator
guarantees is documented below in `## Dev loop`, because it is part of what a
consumer agrees to when they adopt the rewrite generator.

Every example assumes the emitted output package is `com.example.app`. Your
generator config determines the real package; substitute it when importing the
generated `Graphitron`, `GraphitronContext`, and related classes.

## Hello world

Implement the generated `GraphitronContext`:

```java
import com.example.app.schema.GraphitronContext;
import graphql.schema.DataFetchingEnvironment;
import org.jooq.DSLContext;

public class AppContext implements GraphitronContext {
    private final DSLContext dsl;

    public AppContext(DSLContext dsl) { this.dsl = dsl; }

    @Override public DSLContext getDslContext(DataFetchingEnvironment env) { return dsl; }

    @Override public <T> T getContextArgument(DataFetchingEnvironment env, String name) {
        return null;
    }
}
```

Build the schema and engine once per app, handle each request:

```java
import com.example.app.Graphitron;
import com.example.app.schema.GraphitronContext;
import graphql.ExecutionInput;
import graphql.GraphQL;
import graphql.schema.GraphQLSchema;

GraphQLSchema schema = Graphitron.buildSchema(b -> {});
GraphQL engine = GraphQL.newGraphQL(schema).build();

GraphitronContext ctx = new AppContext(dsl);

ExecutionInput input = ExecutionInput.newExecutionInput()
    .query(query)
    .graphQLContext(b -> b.put(GraphitronContext.class, ctx))
    .build();

var result = engine.execute(input);
```

The context key is the generated `GraphitronContext.class`; every emitted
fetcher looks it up with that typed key.

## Custom scalar

Register scalars through the customizer:

```java
import graphql.scalars.ExtendedScalars;

GraphQLSchema schema = Graphitron.buildSchema(b -> b
    .additionalType(ExtendedScalars.UUID)
    .additionalType(ExtendedScalars.Date));
```

Every scalar reference in the generated schema uses
`GraphQLTypeReference.typeRef(name)`; graphql-java resolves the reference
against your registered `GraphQLScalarType` at build time. An unregistered
scalar fails at schema-build with graphql-java's native "unresolved type"
error naming the scalar and the referencing types.

## Federation

`federation-graphql-java-support` consumes the prebuilt schema directly:

```java
import com.apollographql.federation.graphqljava.Federation;

GraphQLSchema base = Graphitron.buildSchema(b -> {});
GraphQLSchema federated = Federation.transform(base)
    .resolveEntityType(entityTypeResolver)
    .fetchEntities(entityFetcher)
    .build();
```

All the federation directives declared in your SDL (`@key`, `@external`,
`@provides`, `@requires`, `@shareable`, `@override`, `@tag`) land on the
programmatic schema as applied directives, so
`federation-graphql-java-support` picks them up on its own. `_Service.sdl`
is serialized back out by the federation library from the programmatic
schema; no extra wiring is needed.

If you need byte-stable SDL for supergraph compose testing, call
`SchemaPrinter` against the built schema yourself and pass the result via
`Federation.transform(schema, sdl).build()`.

## Tenant-scoped DSLContext

`getDslContext(env)` is called per resolver invocation, so it can inspect
the `DataFetchingEnvironment` and return a tenant-specific `DSLContext`:

```java
public class TenantContext implements GraphitronContext {
    private final DSLContext shared;
    private final Map<String, DSLContext> perTenant;

    @Override public DSLContext getDslContext(DataFetchingEnvironment env) {
        String tenantId = env.getGraphQlContext().get("tenantId");
        return perTenant.computeIfAbsent(tenantId, id ->
            shared.configuration().derive(settingsFor(id)).dsl());
    }
    // ...
}
```

Request entry puts the tenant id into `graphQLContext`; resolvers read
their `DSLContext` per call. No request-time pre-computation in the
caller; the logic lives in one place.

## Context arguments from a JWT claim

Directives like `@condition(contextArguments: ["userId"])` emit a
`getContextArgument(env, "userId")` call. Your implementation pulls the
claim off the request-scoped context:

```java
public class JwtContext implements GraphitronContext {
    @Override @SuppressWarnings("unchecked")
    public <T> T getContextArgument(DataFetchingEnvironment env, String name) {
        Map<String, Object> claims = env.getGraphQlContext().get("jwtClaims");
        return (T) claims.get(name);
    }
    // ...
}
```

Per-request wiring stashes the JWT claims under a stable key:

```java
ExecutionInput input = ExecutionInput.newExecutionInput()
    .query(query)
    .graphQLContext(b -> b
        .put(GraphitronContext.class, ctx)
        .put("jwtClaims", verifiedClaims))
    .build();
```

Every `@condition contextArguments: ["userId"]` declaration in the SDL
reaches this method with `name = "userId"`; the generator already emitted
the call site.

## Customizer safe surface

`Graphitron.buildSchema(Consumer<GraphQLSchema.Builder>)` hands you the
underlying builder. Use additive methods only:

- `.additionalType(...)` for custom scalars, additional object types, extra
  input types.
- `.additionalDirective(...)` for directives beyond the schema's declared
  survivors.
- `.codeRegistry(UnaryOperator<GraphQLCodeRegistry.Builder>)` for registering
  type resolvers on user-defined interfaces / unions.

Avoid:

- `.query(...)`, `.mutation(...)`, `.subscription(...)` — Graphitron has
  already set them.
- `.clearDirectives()` — drops the survivor definitions Graphitron emitted.
- The replace overload `.codeRegistry(GraphQLCodeRegistry)` — use the
  `UnaryOperator` overload to add resolvers to the in-flight registry.

## Dev loop

### What you do

Edit your `.graphqls` source files, then run `mvn generate-sources` (or let
your build tool re-trigger it). Graphitron ships no watch goal; if you want
file-watching, wire `mvn generate-sources` into your IDE's file-watcher or
use whichever live-reload tool your framework provides.

### What the generator does

Every run walks the full schema, renders each output file, and then:

1. Writes only the files whose rendered content differs from what is already
   on disk (SHA-256 comparison inside javapoet).
2. Deletes any `*.java` file in the six rewrite-owned sub-packages
   (`<outputPackage>`, `.util`, `.schema`, `.types`, `.conditions`,
   `.fetchers`) that this run did not emit.

Both steps happen unconditionally on every run; no flag is required.

### What you observe

- **`git diff` is proportional.** A schema edit that touches one type
  rewrites that type's files; every other generated file is byte-identical
  to the previous run and unchanged on disk, so `git diff` shows only the
  types the edit actually touched.
- **IDE recompile time is proportional.** IntelliJ's incremental compiler
  and Quarkus `quarkus:dev` and Spring Boot DevTools all watch
  `target/generated-sources/` for mtime changes. Because unchanged files
  keep their mtimes, only the files that actually changed trigger a
  recompile.
- **Removing a type removes its file.** Deleting a type or field from the
  schema causes the corresponding generated file to disappear on the next
  generator run. No orphan code, no stale compile errors, no manual
  `target/` cleanup.

These three properties are guaranteed by the generator and pinned by
`IdempotentWriterTest` and `GeneratorDeterminismTest`; a future refactor
that breaks any of them fails the test suite.

### Tool interop

No Graphitron-specific IDE plugin or tool integration is required. The
behaviour composes with standard incremental-compile paths:

- **IntelliJ IDEA** — incremental compiler re-examines files whose mtime
  changed; unchanged generated files are invisible to it.
- **Quarkus `quarkus:dev`** — detects source-root changes by mtime; only
  the changed files trigger a reload.
- **Spring Boot DevTools** — same mtime-based detection; unchanged files
  are ignored.

## Notes

- The app no longer loads an SDL resource at runtime; everything the engine
  needs is in the programmatic schema. If you need an SDL string (e.g., for
  logging or debugging), call `new SchemaPrinter().print(schema)`.
- `DataLoaderRegistry` is per-request. graphql-java requires one on
  `ExecutionInput` even when no DataLoader is used; emit
  `.dataLoaderRegistry(new DataLoaderRegistry())` alongside the context put.

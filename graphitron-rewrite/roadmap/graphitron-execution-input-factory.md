---
id: R184
title: Graphitron.newExecutionInput factory for single-tenant ergonomics
status: In Progress
bucket: feature
priority: 6
depends-on: []
created: 2026-05-19
last-updated: 2026-05-19
---

# Graphitron.newExecutionInput factory for single-tenant ergonomics

Every app that runs a graphitron-generated schema repeats the same two lines of boilerplate on every request: `.graphQLContext(b -> b.put(GraphitronContext.class, ctx))` to thread the per-request context where generated fetchers look it up, and `.dataLoaderRegistry(new DataLoaderRegistry())` to satisfy graphql-java's requirement that one always be present (the sakila example's `GraphqlResource.execute` at `graphitron-rewrite/graphitron-sakila-example/src/main/java/no/sikt/graphitron/sakila/example/app/GraphqlResource.java:62-67` is the canonical demonstration, with the javadoc apologising for the registry requirement). The context wiring is a silent foot-gun: forget it and generated fetchers look up `GraphitronContext.class` in the request's `GraphQLContext`, get `null`, and crash deep in a resolver. The registry boilerplate is pure ceremony because generated fetchers populate the registry lazily via `env.getDataLoaderRegistry().computeIfAbsent(name, k -> DataLoaderFactory.newDataLoader(...))` (e.g. `QueryNodeFetcherClassGenerator.java:163`, `DataLoaderFetcherEmitter.java:116`), so an empty fresh registry is exactly what every app needs. The legacy codebase recognised half this gap in `graphitron-common`'s `DefaultGraphitronContext` (`graphitron-common/src/main/java/no/sikt/graphql/DefaultGraphitronContext.java`), which lets single-tenant users construct a context from a single `DSLContext`; the rewrite has no analog.

Collapse both lines into a single generated factory entry point so the single-tenant path is `Graphitron.newExecutionInput(dsl).query(q).build()` and the multi-tenant path is `Graphitron.newExecutionInput(myContextImpl).query(q).build()`, with the registry wiring and context-key plumbing hidden inside the factory and overridable for power users who need them.

## Implementation

Two generator-side edits and one example-side edit:

1. **`GraphitronFacadeGenerator`** (`graphitron-rewrite/graphitron/src/main/java/no/sikt/graphitron/rewrite/generators/schema/GraphitronFacadeGenerator.java`): add two static methods to the emitted `Graphitron` class, alongside the existing `buildSchema` overloads. Both return `graphql.ExecutionInput.Builder`.
   - `newExecutionInput(GraphitronContext context)`: returns `ExecutionInput.newExecutionInput().graphQLContext(b -> b.put(GraphitronContext.class, context)).dataLoaderRegistry(new DataLoaderRegistry())`.
   - `newExecutionInput(DSLContext dsl)`: returns `newExecutionInput((GraphitronContext) env -> dsl)`. The cast is so the lambda binds to the single-abstract-method interface form rather than getting inferred as `Function<DataFetchingEnvironment, DSLContext>`.

2. **`GraphitronContextInterfaceGenerator`** (`graphitron-rewrite/graphitron/src/main/java/no/sikt/graphitron/rewrite/generators/util/GraphitronContextInterfaceGenerator.java`): flip `getContextArgument` from `ABSTRACT` to `DEFAULT` with body `return env.getGraphQlContext().get(name);` (matches the legacy `DefaultGraphitronContext` implementation). The interface now has exactly one abstract method (`getDslContext`); `getContextArgument`, `getTenantId`, `getValidator` all carry defaults. Do not annotate the interface `@FunctionalInterface`. The annotation is a permanent contract that would block ever adding another abstract method; the lambda form `env -> dsl` works whether or not the annotation is present.

3. **Sakila example** (`graphitron-rewrite/graphitron-sakila-example/src/main/java/no/sikt/graphitron/sakila/example/app/GraphqlResource.java`): rewrite `execute(...)` to use the factory. The current six-line builder collapses to roughly:
   ```java
   ExecutionInput.Builder input = Graphitron.newExecutionInput(new AppContext(dataSource, Map.of()))
       .query(query);
   if (variables != null) input.variables(variables);
   if (operationName != null) input.operationName(operationName);
   ```
   This is the canonical "how do I use this" reference; updating it is half the value of the item, because the docs section below quotes it.

## Tests

Pipeline tier carries the generator changes; compilation tier carries the lambda-form contract; execution tier requires no new test because the rewritten `GraphqlResource` already runs under every existing execution test.

- **Pipeline** (`GraphitronFacadeGeneratorTest`): assert the returned `TypeSpec` for `Graphitron` contains exactly two methods named `newExecutionInput`, with parameter lists `(GraphitronContext)` and `(DSLContext)` respectively, both returning `ExecutionInput.Builder`. Assert method signatures and existence, not body strings (per "no code-string body assertions" principle); the compilation tier covers body correctness.
- **Pipeline** (`GraphitronContextInterfaceGeneratorTest`): assert `getContextArgument` carries the `DEFAULT` modifier and not `ABSTRACT`. Assert the emitted `TypeSpec` has *exactly one* abstract method (the count, not the name), so any future generator change that adds another abstract method fails this test and forces a deliberate decision about whether to keep the lambda form working. This is the load-bearing-style check the architect self-review identified: it turns the "de-facto functional interface" property into a build-time invariant without paying the `@FunctionalInterface` cost.
- **Compilation tier** (`mvn compile -pl :graphitron-sakila-example -Plocal-db`): the rewritten `GraphqlResource.execute` is the load-bearing test that `Graphitron.newExecutionInput(dsl)` actually compiles, i.e. that the lambda `(GraphitronContext) env -> dsl` resolves to a valid implementation. If `GraphitronContext` ever grows a second abstract method, this compile fails and the pipeline test above fires in tandem.
- **Compilation tier** (one new line in `GraphqlResource` or a small test class): exercise the `dataLoaderRegistry` replacement contract explicitly. Build `Graphitron.newExecutionInput(dsl).dataLoaderRegistry(customRegistry).build()` and assert the resulting `ExecutionInput.getDataLoaderRegistry()` returns `customRegistry`, not the factory's fresh one. This pins the graphql-java behaviour the design rides on (replace, not merge); if a future graphql-java version changes the semantics, this test fires before users discover it via a leaked default registry.
- **Execution tier**: no new test. Every existing execution test in `graphitron-sakila-example` runs through `GraphqlResource.execute`, so rewriting that method *is* the execution coverage for the factory.

## User documentation (first-client check)

`graphitron-rewrite/docs/getting-started.adoc` is the canonical reference doc for the user-facing surface this item adds. The relevant snippet at `getting-started.adoc:52-55` reads:

```java
ExecutionInput input = ExecutionInput.newExecutionInput()
    .query(query)
    .graphQLContext(b -> b.put(GraphitronContext.class, ctx))
    .build();
```

After this item lands it becomes:

```java
ExecutionInput input = Graphitron.newExecutionInput(ctx)
    .query(query)
    .build();
```

The "Multi-tenant" example at `getting-started.adoc:261-266` simplifies the same way (the `.put("jwtClaims", verifiedClaims)` chains onto the factory's `graphQLContext` via the additive merging form: `Graphitron.newExecutionInput(ctx).graphQLContext(b -> b.put("jwtClaims", verifiedClaims))`). The "DataLoader registry is per-request" callout at `getting-started.adoc:430-432` flips from "emit `.dataLoaderRegistry(new DataLoaderRegistry())` alongside the context put" to "the factory pre-wires an empty registry; chain `.dataLoaderRegistry(custom)` to override." A one-line single-tenant convenience example is added showing `Graphitron.newExecutionInput(dsl)` with a plain `DSLContext`.

If those three doc edits do not read more simply than the originals, the design is wrong and the spec needs to change before implementation. The current draft reads simpler: same import surface, one less line per call site, the foot-gun (forgetting the context put) is structurally unreachable.

## Considered and rejected

- **Generate `DefaultGraphitronContext` as a class alongside the interface.** The legacy codebase ships this in `graphitron-common`; the symmetric move is to emit it from the generator. Rejected on the "stability through simplicity" principle: fewer generated names = smaller surface to keep in sync with `GraphitronContext`'s method set. If the interface gains a new default method tomorrow, the lambda form `env -> dsl` picks up the new default automatically with no regeneration; a generated companion class would need its own regeneration logic to mirror the interface. The lambda form's brevity (one expression vs. one constructor + class import) is a secondary win, not the principled reason.

- **Hand-write the factory in a new runtime module instead of generating it.** The factory references the generated `GraphitronContext` type, which is emitted per-app at `<outputPackage>.schema.GraphitronContext`. A hand-written helper in a shared module would either need to live below that type (impossible: it must reference the user's package) or use reflection (introduces a dependency direction graphitron does not have anywhere else). Generating it keeps the user-facing surface in one place (the same `Graphitron` facade that already houses `buildSchema`), matches the existing emission shape, and stays consistent with the "Separate business logic from API code" principle: the API code lives next to the rest of the API code.

- **Wrap `ExecutionInput.Builder` in a graphitron-specific builder.** The friction is the two specific calls (`graphQLContext` + `dataLoaderRegistry`), not the input-builder plumbing as a whole. Wrapping the full builder surrenders flexibility (instrumentation, extensions, custom locale/operation-name handling) for one map entry. Apps that want extra `graphQLContext` entries chain `.graphQLContext(b -> b.put(...))` after the factory; graphql-java's builder *merges* into the existing context, so the factory's put-call and the user's put-calls coexist cleanly.

- **Three-arg overload `Graphitron.newExecutionInput(DSLContext, DataLoaderRegistry)`.** Covered without expanding API surface: `Graphitron.newExecutionInput(dsl).dataLoaderRegistry(customRegistry)` replaces the factory's default because graphql-java's `dataLoaderRegistry(...)` semantics are replace-not-merge. The Tests section pins this so the contract doesn't drift silently.

## Out of scope

- **No federation overload of `newExecutionInput`.** `GraphitronFacadeGenerator` emits a federation-aware second `buildSchema` overload when `federationLink` is true, but `ExecutionInput` carries no federation-specific wiring; the same factory serves both schema flavours. Apps running a federation schema call `Graphitron.newExecutionInput(ctx)` and pass the result to a `GraphQL.newGraphQL(federationSchema)` engine identically.
- **No change to `DataLoader` registration mechanics.** Generated fetchers continue to populate the registry lazily via `computeIfAbsent`; the factory just supplies the empty registry they populate.
- **No change to `GraphQLContext` merge semantics or graphql-java version.** The design rides on the existing graphql-java 25.0 contract that `ExecutionInput.Builder.graphQLContext(Consumer)` merges and `.dataLoaderRegistry(Registry)` replaces; both are pinned by tests above.
- **No new module, no change to dependency graph.** Everything lives in already-emitted classes (`Graphitron`, `GraphitronContext`) or already-existing example files (`GraphqlResource`).
- **`getTenantId` and `getValidator` defaults stay as they are.** This item touches only `getContextArgument`'s abstract → default flip; the other two were already default-method shaped before this item.

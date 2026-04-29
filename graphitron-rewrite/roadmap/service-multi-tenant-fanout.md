---
id: R46
title: "Multi-tenant fan-out for `@service`"
status: Backlog
bucket: architecture
priority: 6
theme: service
depends-on: [typed-context-value-registry, mutations]
---

# Multi-tenant fan-out for `@service`

## Motivation

A custom resolver in a downstream Sikt project (`megVedLarested`) bypasses `@service` and writes the resolver by hand because the directive can't express what it needs: for each tenant the logged-in user belongs to, open a tenant-scoped `DSLContext`, fan out in parallel on the executor, drop nulls, return the union. The service method itself is GraphQL-free Java; what doesn't fit `@service` is the `ConnectionManager` lookup, the per-tenant `DSLContext` plumbing, and the `executor.allOf().join()` shape.

Carved out of the original R31 spec; depends on [`typed-context-value-registry.md`](typed-context-value-registry.md) (R45) which ships the sealed `ContextValueRegistration` interface this slice extends with a `FanOut` permit.

## Design constraints

- **Schema carries minimal logic.** No expression language inside directive arguments, no per-field flags for orchestration mode.
- **Services are GraphQL-free.** They receive plain values (`String tenantId`, `DSLContext ctx`), never `DataFetchingEnvironment`.
- **Multi-tenant is native.** Per-tenant fan-out is a first-class graphitron concept that the schema author opts into by naming `tenantId` in `contextArguments`, not a per-directive switch.

## Design

### Extend `ContextValueRegistration` with a `FanOut` permit

```java
public sealed interface ContextValueRegistration<T> {
    String name();
    Class<T> type();

    record Scalar<T>(...) implements ContextValueRegistration<T> {}  // from R45

    record FanOut<T>(String name, Class<T> type,
                     Function<DataFetchingEnvironment, Collection<T>> resolver,
                     Optional<DslContextPerElement> dslContext)
        implements ContextValueRegistration<T> {}
}

public interface DslContextPerElement {
    DSLContext open(DataFetchingEnvironment env, Object element);
}
```

Consumer registers fan-out values like:

```java
registry.register(new FanOut<>("tenantId", String.class,
    env -> {
        UserInfo ui = graphQlContext(env).get("userInfo");
        return ui.getInstitusjonsroller().keySet();
    },
    Optional.of((env, element) -> graphQlContext(env)
        .get("connectionManager")
        .createDSLContextWithSettings((String) element))));
```

### Code-generation: discovery and validation

Building on R45's `ContextValueCatalog`:

- **Fan-out classification:** if any context arg on a field is a `FanOut` registration, the field becomes a fan-out service field. Constraint: at most one fan-out context arg per field (multi-axis fan-out is out of scope; flag it as a classification error so we don't paint ourselves into a corner). The field's GraphQL return type must be a list whose element type matches the service method's return type.
- **Type matching for fan-out:** the registered fan-out element type must be assignable to the matching Java parameter type (`String tenantId`, not `Set<String>`).
- New field model carrier on the existing service field types (`QueryServiceTableField`, `QueryServiceRecordField`, mutation / child equivalents): an optional `FanOutContextArg` ref pointing at the driving param.

### Code-generation: emission

`ArgCallEmitter.java:100-101` becomes type-aware via the registry's `Kind` discriminator (per R45):

```java
case ParamSource.Context ctx -> registry.lookup(param.name()) match {
    case Scalar(_, type, _) ->
        // R45 emission, unchanged
        CodeBlock.of("graphitronContext(env).getContextArgument(env, $S)", param.name());
    case FanOut(_, _, _, _) ->
        CodeBlock.of("$L", fanOutLoopVarName(param.name()));
};
```

`TypeFetcherGenerator.buildQueryServiceRecordFetcher` (`TypeFetcherGenerator.java:687-707`) gains a fan-out branch when the field has a fan-out context arg:

```java
// Generated for: megVedLarested: [PersonProfil]
//   @service(service: ..., contextArguments: ["fnr", "tenantId"])
public CompletableFuture<List<PersonProfil>> megVedLarested(DataFetchingEnvironment env) {
    var ctx = graphitronContext(env);
    var executor = ctx.getExecutor(env);
    var fnr = (String) ctx.getContextArgument(env, "fnr");
    var elements = ctx.<String>getContextFanOut(env, "tenantId");

    var futures = elements.stream()
        .map(tenantId -> CompletableFuture.supplyAsync(() -> {
            DSLContext dsl = ctx.openContextDslContext(env, "tenantId", tenantId);
            try {
                return PersonService.getPersonVedLarested(dsl, fnr, tenantId);
            } catch (RuntimeException e) {
                return null;
            }
        }, executor))
        .toList();

    return CompletableFuture
        .allOf(futures.toArray(CompletableFuture[]::new))
        .thenApply(v -> futures.stream()
            .map(CompletableFuture::join)
            .filter(Objects::nonNull)
            .toList());
}
```

`getContextFanOut`, `openContextDslContext`, and `getExecutor` are new methods on the generated `GraphitronContext` interface (`GraphitronContextInterfaceGenerator.java:41-49,51-60`). The consumer's registry implementation backs all three.

The "open the DSLContext on the worker thread, join on the orchestrator thread so connections close on the right thread" rule is the easy thing to get wrong by hand and the main reason fan-out should live in codegen.

The non-fan-out path (no `FanOut` context arg present) keeps emitting the synchronous shape from R45.

## Implementation

### Runtime contract (`graphitron-rewrite-runtime/`)

- Add `FanOut<T>` permit to `ContextValueRegistration` (defined in R45). Add `DslContextPerElement` interface.
- Extend the generated `GraphitronContext` shape to answer `getExecutor(env)`, `getContextFanOut(env, name)`, `openContextDslContext(env, name, element)`. The default impl backs them with the registry; consumers customise by replacing the registry, not the context.

### Catalog + classification

- Extend the `ContextValueCatalog` (from R45) to recognise `FanOut` registrations and surface a `Kind` discriminator.
- New field model carrier on `QueryServiceTableField`, `QueryServiceRecordField`, `MutationServiceRecordField`, `ChildField.ServiceTableField`, `ChildField.ServiceRecordField`: optional `FanOutContextArg` ref pointing at the driving param.
- Reject multi-fan-out fields and non-list-return fan-out fields at classification time.

### Emission (`generators/.../`)

- `ArgCallEmitter.java:100-101`: emit fan-out loop variable for `FanOut` params (the `Kind`-based switch).
- `TypeFetcherGenerator.java:687-707` (and mutation / child equivalents): branch on the field's fan-out flag; emit the fan-out scaffold described above in the fan-out arm; leave the synchronous emission untouched in the scalar-only arm.
- Generated method return type for fan-out fields: keep the schema-driven list type but wrap in `CompletableFuture<List<...>>` so graphql-java's data-loader dispatch can join the orchestrator thread.

## Tests

- **Classification (L2)** — fan-out flag set when one fan-out arg present; multi-fan-out rejected; non-list return on fan-out field rejected.
- **Validation (L3)** — extend the four `*ServiceFieldValidationTest` classes for the new field model carrier.
- **Pipeline (L4)** — extend `ServiceRootFetcherPipelineTest`, `FetcherPipelineTest`: end-to-end SDL → emitted fetcher with a fan-out context arg compiles and matches a structural snapshot of the emitted method.
- **Compile (L5)** — extend the rewrite-fixtures package with a schema/service pair using the fan-out shape (with per-element DSLContext) so the generated code passes javac under the existing compile-spec gate.
- **Execute (L6)** — one or two execution fixtures hitting a fan-out service with `-Plocal-db`, verifying parallel calls happen on the executor and per-tenant `DSLContext` flows through. Null-filtering case too.

## Open questions for the reviewer

1. **Multi-tenant as a flag vs. as a fan-out registration?** Recommend: **registration-only** (no `<multiTenant>` Mojo flag). Cleaner; the consumer expresses fan-out intent in their registry, no Mojo flag flipping behavior. Built-in `tenantId` registration is shipped only if the consumer opts in by registering it themselves.
2. **Null filtering on fan-out always-on vs. opt-out?** The motivating resolver drops nulls; could plausibly want a strict mode that fails the whole field if any element threw. Default-on, no opt-out, until a real case appears.
3. **Is checked-exception handling in the fan-out arm in scope here, or does it wait for [`checked-exceptions-typed-errors.md`](checked-exceptions-typed-errors.md)?** The motivating resolver swallows exceptions to null; that's the cheap default. Anything richer should compose with the typed-errors plan.
4. **Per-element DSLContext: registered on the fan-out value, or a separate registration?** Drafted as a field on `FanOut`; could instead be a sibling registration keyed by the same name. Field-on-`FanOut` is simpler; sibling allows one fan-out to drive multiple context shapes.
5. **Multi-axis fan-out (e.g., over `tenantId × roleId`):** rejected at classification. Real demand is hypothetical; keep rejected unless a use case shows up.

## Roadmap entries (siblings / dependencies)

- **Hard requires** [`typed-context-value-registry.md`](typed-context-value-registry.md) (R45). The `Scalar` permit and `ContextValueCatalog` shape this slice extends.
- **Updates** [`graphitroncontext-extension-point-docs.md`](graphitroncontext-extension-point-docs.md): the `FanOut` registration + `DslContextPerElement` interface are documentable extension points.
- **Independent of** [`set-parent-keys-on-service.md`](set-parent-keys-on-service.md), [`checked-exceptions-typed-errors.md`](checked-exceptions-typed-errors.md): no shared file edits, but error-channel design overlaps with the null-filtering question above.
- **New sibling Backlog item:** `auto-nodes-relay-resolver.md` (auto-emit Relay `nodes` when `node` exists; not part of this plan because it isn't a `@service` extension).

---
id: R45
title: "Typed context-value registry for `@service`"
status: Spec
bucket: architecture
priority: 5
theme: service
depends-on: []
---

# Typed context-value registry for `@service`

## Motivation

Today, `@service(contextArguments: ["tenantId", "userId"])` resolves at runtime via `graphitronContext(env).getContextArgument(env, "tenantId")` (`ArgCallEmitter.java:100-101`). The codegen already knows the expected Java type from reflection of the service method's parameter (it captures it on `MethodRef.Param.Typed.typeName`), and `<T> T getContextArgument(...)` lets Java's type inference handle the cast at the call site, so the emitted call compiles with the right cast for free. What's missing is the *declaration* surface. The default implementation reads from `env.getGraphQlContext()`, a `Map<Object, Object>` the consumer populates somewhere with no declared shape; lifecycle is implicit (no distinction between values that are constant for the JVM, values that are constant for a request, and values derived per-fetch from the `DataFetchingEnvironment`); there is no registry of known names; no derived-value extension point (the `fnr = sudoFodselsnr ?? fodselsnr` rule has to be repeated by every caller); and no generate-time check that a referenced name is declared or that its registered shape is assignable to the matched service parameter.

This item covers the typed-registry slice of the original R31 spec. The multi-tenant fan-out slice (the `FanOut` registration variant, `DslContextPerElement`, async fan-out emission, executor plumbing) is tracked separately under [`service-multi-tenant-fanout.md`](service-multi-tenant-fanout.md). The short class-name resolution legacy parity is tracked under [`service-short-classname-resolution.md`](service-short-classname-resolution.md).

## Design

### Context-value registry

The plan is to make the consumer-supplied registry first-class:

```java
public interface ContextValueRegistry {
    void register(ContextValueRegistration<?> registration);
}

public sealed interface ContextValueRegistration<T> {
    String name();
    Class<T> type();

    record Scalar<T>(String name, Class<T> type,
                     Function<DataFetchingEnvironment, T> resolver)
        implements ContextValueRegistration<T> {}
}
```

The `sealed` interface anticipates [`service-multi-tenant-fanout.md`](service-multi-tenant-fanout.md) adding a `FanOut` permit. This slice ships only `Scalar`. Sealing now, rather than leaving the interface open and sealing on the second permit, is deliberate: when the fan-out permit lands, every `switch` over `ContextValueRegistration` becomes a compile error until each arm is handled, so the new behaviour cannot land as a runtime-only addition.

The consumer implements `ContextValueRegistry` once (likely on or beside `GraphitronContext`) and registers names like:

```java
registry.register(new Scalar<>("userInfo", UserInfo.class,
    env -> graphQlContext(env).get("userInfo")));

registry.register(new Scalar<>("fnr", String.class, env -> {
    UserInfo ui = graphQlContext(env).get("userInfo");
    return ui.getSudoFodselsnr() != null ? ui.getSudoFodselsnr() : ui.getFodselsnr();
}));
```

`fnr`'s sudo fallback is now defined once, in Java, where it belongs.

### Model carrier: classified `ParamSource.Context`

`ParamSource.Context` is currently an empty record (`ParamSource.java:64`); the param's GraphQL name is read off the enclosing `MethodRef.Param` at emission, and the registration kind is rediscovered nowhere today (the runtime call is type-agnostic). A registry-lookup-in-the-emitter is the obvious port, but it would leave the model carrying "what to interpret" rather than "what to emit", and the fan-out slice would then have to unwind that on every switch over `ParamSource.Context`.

R45 widens `ParamSource.Context` to a sealed sub-taxonomy and lifts the registration kind into the carrier at classification time:

```java
sealed interface ParamSource.Context extends ParamSource
    permits ParamSource.Context.Scalar {

    record Scalar(String name, ClassName javaType)
        implements ParamSource.Context {}
}
```

`Scalar.javaType` is the registered `Class<T>` projected to the project's `no.sikt.graphitron.javapoet.ClassName`, classifier-side; emission needs no further reflection. R46 adds a `FanOut(...)` permit; every emitter switch over `ParamSource.Context` becomes a compile error until the new arm is handled. The catalog is consulted exactly once, in `FieldBuilder`, to populate the carrier; it is never consulted at emission.

### Code-generation: discovery and validation

A new `ContextValueCatalog` is loaded once per codegen run from a Mojo-configured class on the consumer side. It shares `ServiceCatalog`'s build-time-reflection *lifecycle* (loaded once via Mojo class config, holds typed descriptors, fails fast on misconfiguration), not its per-method shape; the catalog reduces to `Map<String, ContextValueRegistration<?>>` plus the loader, with no `MethodRef`-analog.

**Discovery contract.** The loader instantiates the consumer's registry-impl class on the codegen classloader and invokes `register(...)` against a capture registry that records each `(name, type)` pair without retaining the resolver lambda. `register(...)` is contracted to be pure: no I/O, no resolver invocation, no side-effects outside the capture registry; resolver lambdas may close over services that are unavailable at codegen time, but their bodies must not be invoked during registration. Any throw from the constructor or from `register(...)` is wrapped as a single typed `Rejection.contextValueRegistryLoadFailure(className, cause)`; the consumer's stack trace is preserved in the cause but the diagnostic frame is the rejection. (The alternative shape, splitting registration into a static `List<ContextValueDescriptor>` separated from a runtime resolver registry, was considered and rejected: it doubles the consumer-side surface for no generate-time clarity gain once the purity contract is stated, and undercuts the "defined once, in Java" framing of `fnr` above.)

**Validation.** Per the validator-mirrors-classifier invariant, every classifier decision driven by the catalog must also fail at validate time. The new typed rejections are:

- `Rejection.unknownContextValueName(attempted, candidates)`: emitted from `FieldBuilder.resolveServiceField()` (`FieldBuilder.java:180-203`) when a name in `contextArguments` is not in the catalog. Candidates are Levenshtein-ranked via `BuildContext.candidateHint(attempted, candidates)`, matching the existing pattern for unresolved service refs.
- `Rejection.contextValueTypeMismatch(name, javaParamType, registeredType)`: emitted from `ServiceCatalog.reflectServiceMethod()` (`ServiceCatalog.java:148-216`) when the registered `Class<T>` is not assignable to the Java parameter type on the matched service method.
- `Rejection.duplicateContextValueRegistration(name)`: emitted from the catalog loader when two `register(...)` calls share a name.
- `Rejection.contextValueRegistryLoadFailure(className, cause)`: the load-failure rejection described above.

### Code-generation: emission

The emitted runtime call is unchanged: `graphitronContext(env).getContextArgument(env, $S)` at `ArgCallEmitter.java:185` and `:279`, with Java's `<T>` inference handling the cast at the call site. What changes is the *dispatch shape*. Both emission sites switch over `ParamSource.Context`'s sealed permits and pull `name` from the carrier (`Scalar.name()`), not from the enclosing `param.name()`. With one permit shipped, the sole arm reduces to the existing emission; the switch exists so R46's `FanOut` permit triggers compile-time errors at every switch site rather than silently no-oping into the scalar path.

### Configuration (Mojo)

- `<contextValueRegistry>` Mojo parameter (the consumer's registry-impl class; mirrors how services are discovered).

## Implementation

### Runtime contract (`graphitron-rewrite-runtime/`)

- New `ContextValueRegistry`, `ContextValueRegistration` (sealed; `Scalar` permit only in this slice) interfaces.
- The generated `GraphitronContext` shape is already typed via `<T>` on `getContextArgument`. No change needed for the Scalar emission path.

### Model carrier

- Widen `ParamSource.Context` from an empty record (`ParamSource.java:64`) to a sealed sub-interface and add the `Scalar(String name, ClassName javaType)` permit. Update the enclosing `ParamSource` sealed `permits` clause to list `ParamSource.Context` (the sub-interface), since the sub-permits are sealed inside `Context`.
- `FieldBuilder` (the classification site that constructs `ParamSource.Context` today) populates the carrier with `(name, javaType)` from the matched catalog entry, projecting the registered `Class<T>` to `no.sikt.graphitron.javapoet.ClassName` at the parse boundary.

### Catalog + classification

- New `ContextValueCatalog`: build-time-reflection lifecycle parallel to `ServiceCatalog` (Mojo class config, fail-fast loading), but a shape of its own (`Map<String, ContextValueRegistration<?>>` after the capture-registry walk; no per-call-site `MethodRef`-analog).
- `FieldBuilder.resolveServiceField()` (`FieldBuilder.java:180-203`): validate each name in `contextArguments` against the catalog; emit `Rejection.unknownContextValueName` on miss with Levenshtein candidates from the catalog's name set.
- `ServiceCatalog.reflectServiceMethod()` (`ServiceCatalog.java:148-216`): type-assignability check between catalog entry's `Class<T>` and the Java parameter; emit `Rejection.contextValueTypeMismatch` on miss.
- Catalog loader: emit `Rejection.duplicateContextValueRegistration` and `Rejection.contextValueRegistryLoadFailure` per Design.

### Docs

- Extend `graphitron-rewrite/docs/runtime-extension-points.adoc` to document the registry-impl extension point alongside `GraphitronContext`, including the `register(...)` purity contract.

## Tests

- **Classification (L2):** extend `GraphitronSchemaBuilderTest` and `ServiceCatalogTest`. One assertion per new rejection variant (`unknownContextValueName`, `contextValueTypeMismatch`, `duplicateContextValueRegistration`, `contextValueRegistryLoadFailure`), plus a carrier-shape assertion that `FieldBuilder` populates `ParamSource.Context.Scalar(name, javaType)` from the catalog (not from `param.name()`).
- **Validation (L3):** extend the five service-field validation tests (`ServiceFieldValidationTest`, `QueryServiceTableFieldValidationTest`, `QueryServiceRecordFieldValidationTest`, `MutationServiceTableFieldValidationTest`, `MutationServiceRecordFieldValidationTest`) so each catalog-driven rejection surfaces a clean message; exercise the Levenshtein candidate hint on the unknown-name case.
- **Pipeline (L4):** extend `ServiceRootFetcherPipelineTest` and `FetcherPipelineTest`. End-to-end SDL → emitted fetcher with one `Scalar` context arg compiles and matches a structural snapshot; the snapshot pins that emission reads `name` off the carrier permit.
- **Compile (L5):** extend the rewrite-fixtures package with a schema/service pair using one `Scalar` context arg so the generated code passes javac under the existing compile-spec gate.

## Open questions

1. **Where does the registry-impl class live in consumer projects?** Co-located with their `GraphitronContext` impl, or a separate top-level class wired via Mojo? Settled by analogy: services are discovered via Mojo config; mirror that.
2. **Built-in registrations (e.g. `userInfo`):** ship none in this slice. The motivating examples (`userInfo`, `fnr`) are illustrative; consumers register their own. Built-in `tenantId` (single-tenant scalar vs multi-tenant fan-out) belongs to [`service-multi-tenant-fanout.md`](service-multi-tenant-fanout.md) since the registration shape depends on the fan-out semantics.

## Roadmap entries (siblings / dependencies)

- **Coordinates with** [`dslcontext-on-condition-tablemethod.md`](dslcontext-on-condition-tablemethod.md): both touch `ArgCallEmitter`'s param-walking. Sequencing: this slice should land after that one, or share its `params()` walk fix.
- **Sibling slice:** [`service-multi-tenant-fanout.md`](service-multi-tenant-fanout.md). Adds the `FanOut` permit on `ContextValueRegistration` and the fan-out emission branch.
- **Sibling slice:** [`service-short-classname-resolution.md`](service-short-classname-resolution.md). Independent refactor that benefits both `@service` and `@externalField`.

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

Today, `@service(contextArguments: ["tenantId", "userId"])` produces an untyped per-name lookup: `graphitronContext(env).getContextArgument(env, "tenantId")` (`ArgCallEmitter.java:100-101`). The name is a literal string into a `Map<String, Object>` the consumer populates somewhere. There is no registry of known names, no derived-value support (the `fnr` = `sudoFodselsnr ?? fodselsnr` rule has to be repeated by every caller), and no generate-time validation that a referenced name exists or has the right type.

Runtime emission already works — `getContextArgument` is generic (`<T> T getContextArgument(...)`) and Java's type inference binds `T` to the call-site parameter type, so the existing `ArgCallEmitter` emission compiles with the right cast for free. What's missing is the *generate-time* surface: the name validation, the type assignability check, the documented extension point.

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

The `sealed` interface anticipates [`service-multi-tenant-fanout.md`](service-multi-tenant-fanout.md) adding a `FanOut` permit. This slice ships only `Scalar`.

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

### Code-generation: discovery and validation

`ServiceCatalog` already reflects consumer service classes at generate time; add the same shape for the registry. A new `ContextValueCatalog` discovers the registrations (a single Java entry-point class the consumer points the generator at, parallel to how services are discovered), captures `(name, type, kind)` for each, and feeds:

- **Validation:** `FieldBuilder.resolveServiceField()` (`FieldBuilder.java:180-203`) checks each name in `contextArguments` against the catalog. Unknown name = classification error with a clear message, matching the existing pattern for unresolved service refs.
- **Type matching:** `ServiceCatalog.reflectServiceMethod()` (`ServiceCatalog.java:148-216`) currently captures the Java parameter type as a string but does no validation against the schema's `contextArguments`. Add a check: the registered context-value type must be assignable to the matching Java parameter type.

### Code-generation: emission

Runtime emission stays as it is today — `ArgCallEmitter.java:100-101` already emits `graphitronContext(env).getContextArgument(env, $S)` and Java's `<T>` inference handles the typing at the call site. No emitter change is needed for this slice. (The fan-out slice will branch the emission based on a future `Kind` discriminator.)

### Configuration (Mojo)

- `<contextValueRegistry>` Mojo parameter (the consumer's registry-impl class; mirrors how services are discovered).

## Implementation

### Runtime contract (`graphitron-rewrite-runtime/`)

- New `ContextValueRegistry`, `ContextValueRegistration` (sealed; `Scalar` permit only in this slice) interfaces.
- The generated `GraphitronContext` shape is already typed via `<T>` on `getContextArgument`. No change needed for the Scalar emission path.

### Catalog + classification

- New `ContextValueCatalog` mirroring `ServiceCatalog`'s reflection shape; loaded from the consumer's registry-impl class, configured via Mojo.
- `FieldBuilder.resolveServiceField()` (`FieldBuilder.java:180-203`): validate each name against the catalog; reject unknown names with a clear classification error.
- `ServiceCatalog.reflectServiceMethod()` (`ServiceCatalog.java:148-216`): add type-assignability check between catalog entry and Java parameter.

## Tests

- **Classification (L2)** — extend `GraphitronSchemaBuilderTest`, `ServiceCatalogTest`: unknown context-value name rejected; type mismatch rejected.
- **Validation (L3)** — extend the four `*ServiceFieldValidationTest` classes: validate the new catalog-driven errors surface clean messages.
- **Pipeline (L4)** — extend `ServiceRootFetcherPipelineTest`, `FetcherPipelineTest`: end-to-end SDL → emitted fetcher with one context arg compiles and matches a structural snapshot.
- **Compile (L5)** — extend the rewrite-fixtures package with a schema/service pair using one Scalar context arg so the generated code passes javac under the existing compile-spec gate.

## Open questions

1. **Where does the registry-impl class live in consumer projects?** Co-located with their `GraphitronContext` impl, or a separate top-level class wired via Mojo? Settled by analogy: services are discovered via Mojo config; mirror that.
2. **Built-in registrations (e.g. `userInfo`):** ship none in this slice. The motivating examples (`userInfo`, `fnr`) are illustrative; consumers register their own. Built-in `tenantId` (single-tenant scalar vs multi-tenant fan-out) belongs to [`service-multi-tenant-fanout.md`](service-multi-tenant-fanout.md) since the registration shape depends on the fan-out semantics.

## Roadmap entries (siblings / dependencies)

- **Coordinates with** [`dslcontext-on-condition-tablemethod.md`](dslcontext-on-condition-tablemethod.md): both touch `ArgCallEmitter`'s param-walking. Sequencing: this slice should land after that one, or share its `params()` walk fix.
- **Sibling slice:** [`service-multi-tenant-fanout.md`](service-multi-tenant-fanout.md). Adds the `FanOut` permit on `ContextValueRegistration` and the fan-out emission branch.
- **Sibling slice:** [`service-short-classname-resolution.md`](service-short-classname-resolution.md). Independent refactor that benefits both `@service` and `@externalField`.

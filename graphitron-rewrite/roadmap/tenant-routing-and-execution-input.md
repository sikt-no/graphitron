---
id: R45
title: "Tenant routing and schema-driven ExecutionInput factory"
status: Spec
bucket: architecture
priority: 5
theme: service
depends-on: []
---

# Tenant routing and schema-driven ExecutionInput factory

## Motivation

Today, `@service(contextArguments: ["tenantId", "userId"])` resolves at runtime via `graphitronContext(env).getContextArgument(env, "tenantId")` (`ArgCallEmitter.java:100-101`). The codegen already knows the expected Java type from reflection of the service method's parameter (it captures it on `MethodRef.Param.Typed.typeName`), and `<T> T getContextArgument(...)` lets Java's type inference handle the cast at the call site, so the emitted call compiles correctly. Three things are still wrong with the surface this exposes to consumers:

1. **No declared surface for contextArguments.** The default implementation reads from `env.getGraphQlContext()`, a `Map<Object, Object>` consumers populate with `b.put(...)` calls in their HTTP filter, despite `GraphQLContext` being an implementation detail they should never see. There is no codegen-time check that a referenced name will be supplied at runtime, no documentation seam for which names are expected, and no static evidence at the consumer's call site that they have wired up everything the schema requires.

2. **Tenant routing is privileged but underspecified.** `GraphitronContext.getTenantId(env)` is already first-class — used at five emission sites (`DataLoaderFetcherEmitter:135`, `TypeFetcherGenerator:4337`, `MultiTablePolymorphicEmitter:810,876`, `QueryNodeFetcherClassGenerator:161`) to partition DataLoader cache names by tenant. But its return type is hard-coded `String`, there is no abstraction for "given a tenant id, hand me the matching DSLContext", and the partitioning is applied unconditionally — every loader is per-tenant, including loaders for reference data that doesn't carry a tenant column and is identical across tenants.

3. **`GraphitronContext` is exposed as a public extension point that doesn't pay for the openness.** Consumers implement it per request, then graphitron calls back into their impl. The interface advertises freedom (override `getDslContext` / `getContextArgument` / `getTenantId` / `getValidator` per request) the consumer almost never needs. Narrowing the surface so consumers supply the narrow inputs and graphitron constructs the carrier removes a class of footgun (forgetting to call a default, leaking `GraphQLContext` into application code) at no real cost.

## Design

### Tenant column declaration

A single Mojo element declares the app's tenant column, by name and Java type:

```xml
<tenantColumn>
  <name>tenant_id</name>
  <javaType>java.lang.Long</javaType>
</tenantColumn>
```

One column per app. `<javaType>` drives the Java type `T` everywhere graphitron talks about tenant ids — the `byTenant` factory parameter, the configured-into-`GraphitronContext` return type, the `@tenantId` arg-type check.

When `<tenantColumn>` is absent, graphitron runs in single-tenant mode: no multi-tenant factory overload, no DataLoader name partitioning by tenant, no `byTenant` shape anywhere in the emitted surface.

### Tenant-scope classification

At codegen, after the jOOQ catalog is loaded, every table is classified:

- **Tenant-scoped** — contains a column whose name matches `<tenantColumn><name>`.
- **Global** — does not.

The classification is computed once during catalog loading and consulted at three places: the multi-tenant factory generation (does any reachable graph type sit on a tenant-scoped table?), the DataLoader emission sites (which name prefix do we emit?), and the `@tenantId` arg-type check (the marked arg must be assignable to the configured `T`).

### Generated `Graphitron` factory methods

`GraphitronFacadeGenerator` already emits a `Graphitron` class with two `newExecutionInput` overloads (`GraphitronFacadeGenerator.java:54-72`). R45 grows these into schema-driven factories whose parameter list reflects the schema's `contextArguments`.

Codegen walks the SDL, collects every `contextArgument` name referenced by `@service` / `@tableMethod` / `@condition` directives, and reflects each name's expected Java type from the matched user-method parameter (`MethodRef.Param.Typed.typeName`, already captured). Names are sorted alphabetically for stable parameter order. The reflected parameterised type carries through unchanged — `List<Long>` is a perfectly valid factory parameter type, not just `Long`.

For a schema using `@service(contextArguments: ["userInfo", "fnr"])` with no tenant column configured:

```java
public static ExecutionInput.Builder newExecutionInput(
    DSLContext defaultDsl,
    String fnr,
    UserInfo userInfo);
```

For the same schema with `<tenantColumn>` configured:

```java
public static ExecutionInput.Builder newExecutionInput(
    DSLContext defaultDsl,
    String fnr,
    UserInfo userInfo);                                  // single-tenant fallback

public static ExecutionInput.Builder newExecutionInput(
    DSLContext defaultDsl,
    Function<Long, DSLContext> byTenant,
    String fnr,
    UserInfo userInfo);                                  // multi-tenant
```

The `byTenant` factory and the per-tenant DSLContext routing are *independent of* whether `tenantId` appears in any `contextArguments` list. They are driven by the Mojo config alone; the schema's per-field tenant id source is the *value* fed into `byTenant` at the call site.

Both overloads return graphql-java's `ExecutionInput.Builder` unchanged; the consumer's standard `.query(...)` / `.variables(...)` / `.operationName(...)` chain works as today. Internally each factory constructs the `GraphitronContext` impl, stashes the supplied parameters into `GraphQLContext` under typed keys (the same machinery already used at `GraphitronFacadeGenerator.java:60-62`), and returns the standard builder.

### Sources for the per-field tenant id

A given field may source its tenant id from either:

- **Request scope, via contextArguments.** If `@service(contextArguments: ["tenantId", ...])` lists `tenantId`, the factory grows a `Long tenantId` parameter alongside the others. The consumer extracts it in their HTTP filter (typically from JWT / session) and passes it in.
- **GraphQL argument, via `@tenantId`.** A new ARGUMENT_DEFINITION directive marks the argument that drives DSLContext routing for the field:

  ```graphql
  findCustomerOrders(
    customerCompanyId: ID! @tenantId,
    status: OrderStatus
  ): [Order!] @table(name: "order")
  ```

  At runtime, the field extracts the marked argument's value and calls `byTenant.apply(value)`. The marked argument's GraphQL type must produce a value assignable to the configured tenant `T`; the codegen rejects mismatches at validation time.

A field carrying both a `@tenantId` argument and `tenantId` in its `contextArguments` is rejected at codegen — two competing sources of the same value. At most one `@tenantId` argument per field; the directive applies to ARGUMENT_DEFINITION only.

### `GraphitronContext` stays generated, not exposed

`GraphitronContextInterfaceGenerator` continues to emit the `GraphitronContext` interface and all its existing methods, with two changes:

- The interface is `sealed` and `permits` the single generated impl class (in the same generated package). Consumers cannot implement it from outside.
- `getTenantId(env)` returns the configured `T` (was hard-coded `String`). A `getDslContext(T tenantId)` method is added so the multi-tenant fan-out paths (this slice and R46) can route by id without re-running tenant lookup on every call.

All other methods unchanged. Every existing emission site — `TypeFetcherEmissionContext.graphitronContextCall()`, the helper-emission gate, R85's planned host-class helper emission — keeps working: the internal carrier is the same; only its visibility narrows.

### Validator override

A new Mojo element names the consumer's custom validator factory:

```xml
<validatorFactory>com.example.MyValidatorFactory</validatorFactory>
```

The named class must implement a single-method functional interface `(DataFetchingEnvironment) -> Validator`. The generated `GraphitronContext` impl's `getValidator(env)` delegates to an instance of it; absent the element, the existing default (`Validation.buildDefaultValidatorFactory().getValidator()`) stays in place. Consumers using the default have no runtime API change to make.

### DataLoader name partitioning

Today every DataLoader name is `getTenantId(env) + "/" + path` at five emission sites regardless of the loaded type's table scope. R45 makes the prefix conditional on classification:

- **Tenant-scoped target type:** name is `getTenantId(env) + "/" + path` (the runtime call's return type now `T` rather than hard-coded `String`).
- **Global target type:** name is `path` alone — the loader is shared across tenants, which is the point of marking those tables global.

When `<tenantColumn>` is unconfigured, the prefix drops entirely at every site; every type is treated as global.

### Cross-site type agreement (codegen-time)

When two directive sites reference the same `contextArgument` name with mutually incompatible reflected Java parameter types (neither assignable to the other), the codegen rejects:

```
Rejection.contextArgumentTypeConflict(name, [(site, paramType), …])
```

The check operates on parameterised types: `List<Long>` vs `List<String>` is a conflict, `List<Long>` vs `Long` is a conflict, `Long` vs `Number` is accepted (the more specific type is captured for the factory parameter).

### Runtime diagnostics (first-fetch)

The generated `getContextArgument(env, name, ExpectedType.class)` boundary carries the expected type and the call-site coordinates for typed diagnostics:

- *Missing value:* `"context value 'fnr' was not supplied at ExecutionInput build; required by com.example.FooService.foo(String)"`.
- *Type mismatch:* `"context value 'fnr' was supplied as java.lang.Integer; expected java.lang.String at com.example.FooService.foo"`.

Both messages name the consumer-side fix (factory call) and the schema-side site, so the loop closes in one round-trip.

## Implementation

### Runtime contract (`graphitron-rewrite-runtime/`)

- New functional interface for the validator factory: `(DataFetchingEnvironment) -> Validator`.
- The generated `GraphitronContext` shape is unchanged in method set; the runtime artefact is the new typed `T` parameter on `getTenantId` and `getDslContext` and the `sealed` / `permits` modifiers.

### Mojo configuration (`graphitron-maven/`)

- New `<tenantColumn>` element with `<name>` (column name) and `<javaType>` (FQN). Both required when the element is present.
- New `<validatorFactory>` element (FQN of a class implementing the validator-factory functional interface).

### Catalog / classification (`graphitron/`)

- Tenant-scope classification during catalog loading: produces a `Set<TableName>` of tenant-scoped tables, plus the configured `T` `TypeName`. Stored on the build result for downstream consumers.
- Cross-site contextArgument type-agreement check, accumulating `Map<String, List<(site, TypeName)>>` and rejecting on incompatible-type sets.
- New rejections: `Rejection.contextArgumentTypeConflict`, `Rejection.tenantIdArgTypeMismatch`, `Rejection.duplicateTenantIdSource`.

### Facade generation (`generators/schema/GraphitronFacadeGenerator.java`)

- SDL walk: collect every contextArgument name + reflected Java type, alphabetical-sort, append to the factory parameter list after the structural parameters.
- Always emit the single-tenant `newExecutionInput(DSLContext, ...)` overload.
- When `<tenantColumn>` is configured, also emit the multi-tenant overload with the `Function<T, DSLContext> byTenant` parameter slotted second.
- Internal factory body constructs the `GraphitronContext` impl, stashes the supplied values into `GraphQLContext` under typed keys, and returns the standard `ExecutionInput.Builder`.

### `GraphitronContext` (`generators/util/GraphitronContextInterfaceGenerator.java`)

- Add `sealed` and `permits` for the generated impl class.
- Type `getTenantId(env)` from `String` to the configured `T`.
- Add `getDslContext(T tenantId)`.
- Other methods unchanged.

### Emission sites

- Five DataLoader name sites: branch on the target type's tenant-scope classification (prefix vs no prefix).
- `ArgCallEmitter.java:185, :279`: emit the `getContextArgument(env, name, ExpectedType.class)` form with the call-site expected type literal.
- New `@tenantId` arg path: at the marked argument's slot, emit `byTenant.apply(env.getArgument("customerCompanyId"))` (or the equivalent direct field-access form already used for other arg extractions).

### Schema (`schema/directives.graphqls`)

- New `directive @tenantId on ARGUMENT_DEFINITION`.

## Tests

- **Catalog (L1/L2).** Tenant-column classification: tables with the configured column tagged tenant-scoped, others global. Mojo without `<tenantColumn>` ⇒ all tables global; multi-tenant overload not generated.
- **Classification (L2).** `contextArgumentTypeConflict` fires across `@service` / `@tableMethod` / `@condition` sites. `@tenantId` arg type-mismatch rejection. Duplicate-source rejection (`@tenantId` arg + `tenantId` in `contextArguments` on the same field).
- **Validation (L3).** Extend the five service-field validation tests (`ServiceFieldValidationTest`, `QueryServiceTableFieldValidationTest`, `QueryServiceRecordFieldValidationTest`, `MutationServiceTableFieldValidationTest`, `MutationServiceRecordFieldValidationTest`) for the new rejections. New `TenantIdArgValidationTest` for the `@tenantId` arg surface.
- **Pipeline (L4).** End-to-end SDL → emitted factory: structural snapshot pins the alphabetical parameter order, the typed parameters, and the conditional multi-tenant overload. Loader-name partitioning snapshot for both scopes. `ServiceRootFetcherPipelineTest`, `FetcherPipelineTest` extensions.
- **Compile (L5).** `rewrite-fixtures` gains a multi-tenant schema using both factory overloads, the `@tenantId` arg directive, and a global reference-data fetch (un-prefixed loader name); generated code passes javac under the compile-spec gate.
- **Execute (L6).** Single-tenant and multi-tenant runtime fixtures under `-Plocal-db`: the multi-tenant path routes through `byTenant`; loader names match the documented shape; first-fetch diagnostics for missing / wrong-typed contextArguments render the documented messages.

## Open questions

1. **`@tenantId` syntactic form.** Currently drafted as a per-argument directive. Alternatives considered: sigil in argMapping value (`argMapping: "customerCompanyId: $tenantId"`); top-level directive arg (`@service(..., tenantIdArg: "customerCompanyId")`). Per-arg directive picked because the marker is a property of the argument, local to its definition, and the existing `argMapping` slot's "GraphQL arg → Java param name" semantics stays single-purpose. Revisit if R46's fan-out cousin (analogous "this arg drives fan-out") finds the sigil shape natural and the per-arg-directive vs sigil divergence becomes friction.
2. **Multi-tenant overload when the reachable graph has no tenant-scoped types.** Currently emitted whenever `<tenantColumn>` is configured. Alternative: skip emission if classification finds no tenant-scoped reachable types. The conservative emission is harmless; revisit only if it causes IDE noise.
3. **Validator-factory functional interface location.** Drafted as a new single-method interface in `graphitron-rewrite-runtime`. Could equally well sit on the generated `GraphitronContext`-adjacent package. Either has the same surface; pick during implementation.

## Roadmap entries (siblings / dependencies)

- **Reshapes** [`service-multi-tenant-fanout.md`](service-multi-tenant-fanout.md) (R46). R46's prior design built on a publicly-implemented `ContextValueRegistration<FanOut>` permit and on `GraphitronContext` as an extension point — both of which this slice dissolves. R46 will be redesigned: the fan-out source becomes a list-typed `contextArgument` (e.g. `["fnr", "tenantIds"]` with `List<Long> tenantIds` on the factory) plus a new directive arg (`fanOutOver:`) or per-arg directive (`@fanOut`). R45 leaves the hooks: list-typed factory parameters carry the reflected parameterised type unchanged; cross-site type-agreement applies to parameterised types; emission makes no scalar-only assumption about contextArguments.
- **Affects** [`helper-emission-non-fetcher-hosts.md`](helper-emission-non-fetcher-hosts.md) (R85). The host-class `graphitronContext(env)` helper-emission work is still needed; `GraphitronContext` continues to be emitted (just sealed-internal), so the bug is structurally unchanged.
- **Coordinates with** [`dslcontext-on-condition-tablemethod.md`](dslcontext-on-condition-tablemethod.md): both touch `ArgCallEmitter`'s param walk. No shared file edits but adjacent emission paths.
- **Independent of** [`service-short-classname-resolution.md`](service-short-classname-resolution.md).

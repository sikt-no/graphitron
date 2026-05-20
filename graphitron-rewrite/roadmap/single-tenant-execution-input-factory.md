---
id: R190
title: Single-tenant schema-driven ExecutionInput factory and sealed GraphitronContext
status: Spec
bucket: architecture
priority: 5
theme: service
depends-on: []
created: 2026-05-20
last-updated: 2026-05-20
---

# Single-tenant schema-driven ExecutionInput factory and sealed GraphitronContext

R45 (`tenant-routing-and-execution-input.md`) bundles the design every consumer needs — a schema-driven `newExecutionInput` factory whose parameter list reflects the schema's `contextArguments` with reflected Java types, a sealed generated `GraphitronContext` consumers cannot implement, and typed `getContextArgument(env, name, ExpectedType.class)` diagnostics that name the consumer-side fix and the schema-side site — together with the multi-tenant additions (tenant-column classification, `byTenant` factory overload, per-loader name partitioning, `@tenantId` ARGUMENT_DEFINITION directive) that only consumers with multiple tenants ever exercise. The single-tenant slice is independently shippable, unblocks the majority of consumers (who today populate `GraphQLContext` by hand and discover misconfiguration at first fetch), and gives R45 a landed baseline to layer multi-tenant fan-out on top of instead of a single monolithic spec where the simpler half is held up by the harder half.

This item covers the single-tenant design end-to-end; R45 is rescoped to "given the single-tenant factory and sealed context, add tenant column classification + `byTenant` routing + `@tenantId` arg" once this lands.

## Design

### Schema-driven factory shape

`GraphitronFacadeGenerator` today emits two `newExecutionInput` overloads at `GraphitronFacadeGenerator.java:54-72`: `newExecutionInput(GraphitronContext)` taking the full context, and `newExecutionInput(DSLContext)` lambda-adapting `(GraphitronContext) env -> dsl`. R190 collapses these into a single typed overload whose parameter list reflects the schema's declared `contextArguments`.

The codegen walks the SDL, collects every `contextArgument` name referenced by `@service` / `@tableMethod` / `@condition` directives (current call sites: `ServiceDirectiveResolver.java:133`, `TableMethodDirectiveResolver.java:144`, `BuildContext.java:1472`), looks up each name's reflected Java type via `MethodRef.Param.Typed.typeName` (`MethodRef.java:238` — captured as the raw `Type.getTypeName()` string at `ServiceCatalog.java:250`, faithful to parameterised types e.g. `"java.util.List<java.lang.Long>"`), and alphabetical-sorts by name for stable parameter order. The reflected type string is parsed into a JavaPoet `TypeName` for emission.

For a schema using `@service(contextArguments: ["userInfo", "fnr"])` where `fnr: String` and `userInfo: UserInfo`:

```java
public static ExecutionInput.Builder newExecutionInput(
    DSLContext defaultDsl,
    String fnr,
    UserInfo userInfo);
```

`DSLContext defaultDsl` is always first; contextArguments follow alphabetically. The body constructs the sealed-impl carrier and stashes it under `GraphitronContext.class` via the existing `b.put(GraphitronContext.class, context)` convention (`GraphitronFacadeGenerator.java:60-62`). Schemas with zero contextArguments collapse to `newExecutionInput(DSLContext defaultDsl)`.

### Sealed `GraphitronContext`

`GraphitronContextInterfaceGenerator.java:35-117` today emits `public interface GraphitronContext` with four default-or-abstract methods. R190 makes the generated interface `sealed permits GraphitronContextImpl` and adds the impl as a generated record in the same package. Consumers can no longer construct ad-hoc `GraphitronContext` lambdas or anonymous subclasses; the factory is the only path that produces one.

The method set in single-tenant mode:

- `DSLContext getDslContext(DataFetchingEnvironment env)` — abstract; impl returns the stashed `defaultDsl`.
- `<T> T getContextArgument(DataFetchingEnvironment env, String name, Class<T> expectedType)` — default; impl reads from the impl's name→value map and runtime-checks the cast (see "Typed `getContextArgument` boundary" below).
- `Validator getValidator(DataFetchingEnvironment env)` — default; impl returns `Validation.buildDefaultValidatorFactory().getValidator()` (unchanged; R192 layers an override on top).

`getTenantId(env)` is **not declared** in single-tenant mode — its current default returning `""` is removed. R45 reintroduces it on top of the sealed surface, which is method-set widening (additive) rather than a signature change, so the absence-to-presence transition is non-breaking for code that compiled against R190's shape.

### Typed `getContextArgument` boundary

`ArgCallEmitter.java:191` and `:285` today emit an untyped two-arg call `<ctx>.getContextArgument(env, "<name>")`, leaving the cast to Java's `<T>` type inference at the consumer-side method-parameter slot. R190 grows the signature with a third `Class<T> expectedType` parameter so the runtime-side diagnostic carries the expected type:

```java
<ctx>.getContextArgument(env, "fnr", String.class)
```

The default-method body in the sealed `GraphitronContext` reads the value from the impl's name→value map. Two diagnostic shapes:

- **Missing value** (name absent from the map): `"context value 'fnr' was not supplied; call Graphitron.newExecutionInput(...) — required by com.example.FooService.foo(String)"`. The factory class+method is hard-coded (today's facade is always emitted as `Graphitron.newExecutionInput`); the call-site coordinates come from the `MethodRef` that surfaced the contextArgument in classification, passed through to the diagnostic as part of the `expectedType` literal's emission context (the emitter writes the FQN literal into the message string at codegen, not at runtime).

- **Type mismatch** (`!expectedType.isInstance(value)`): `"context value 'fnr' was supplied as java.lang.Integer; expected java.lang.String at com.example.FooService.foo"`. Belt-and-braces: statically impossible if the consumer routes through the factory (factory parameter type IS the reflected expected type); only reachable if consumer hand-rolls `ExecutionInput.Builder` and bypasses the factory. The factory is the supported entry point; this path documents the unsupported case at runtime.

### Cross-site contextArgument type-agreement (load-bearing classifier)

The factory emitter pastes one `TypeName` per contextArgument name into the generated parameter list. If two directive sites reference the same name with mutually-incompatible Java types (`@service(contextArguments: ["fnr"])` on a `(String fnr)` method and `@condition(contextArguments: ["fnr"])` on a `(Long fnr)` method), the emitter cannot produce a single factory parameter type. A new classifier check runs after `ServiceCatalog.reflectTableMethod` and `reflectServiceMethod` have populated `MethodRef.Param.Typed.typeName` across the catalog:

- Collect `Map<String, List<(MethodRef, String typeName)>>` keyed by contextArgument name across all directive sites.
- For each name with more than one distinct `typeName`, verify assignability via reflection on the parsed `Type` — the more-specific type wins if one is assignable to all others (`Long` wins over `Number`); otherwise reject.
- On reject, produce `Rejection.contextArgumentTypeConflict(name, sites)`, a new `AuthorError` factory on the `Rejection` sealed interface (`Rejection.java:18`). Reuses the existing `AuthorError.Structural` leaf shape; no new variant arm needed.

The classifier method is annotated `@LoadBearingClassifierCheck(key = "context-argument.type-agreement", description = "Cross-site agreement on contextArgument Java types; consumed by the factory emitter")`. The factory-emitting method in `GraphitronFacadeGenerator` is annotated `@DependsOnClassifierCheck(key = "context-argument.type-agreement", reliesOn = "single typeName per name, pasted into the factory signature")`. `LoadBearingGuaranteeAuditTest` (`LoadBearingGuaranteeAuditTest.java:57-67`) enforces the pairing.

### DataLoader name emission

Five sites today emit `String name = <ctx>.getTenantId(env) + "/" + String.join("/", env.getExecutionStepInfo().getPath().getKeysOnly())`:

- `DataLoaderFetcherEmitter.java:135`
- `TypeFetcherGenerator.java:4337`
- `MultiTablePolymorphicEmitter.java:810`
- `MultiTablePolymorphicEmitter.java:876`
- `QueryNodeFetcherClassGenerator.java:161` (a slight variant inside a `.map(id -> {...})` lambda, using a pre-computed `path` local instead of `getKeysOnly()`)

Today's default `getTenantId(env)` returns `""`, so the de-facto runtime name is `"/" + path` — a leading-slash artifact. R190 drops the `<ctx>.getTenantId(env) + "/"` segment at all five sites; the emitted name becomes the path expression alone. DataLoader names lose the leading slash. R45 reintroduces the prefix at all five sites when `<tenantColumn>` is configured, restoring the tenant-partitioned shape.

### Out of scope

- Tenant column Mojo config (`<tenantColumn>`), tenant-scope classification, `byTenant` factory overload, DataLoader name partitioning by tenant, `@tenantId` ARGUMENT_DEFINITION directive — all R45 after rescope on top of R190.
- Custom validator factory (`<validatorFactory>` Mojo element) — R192.

## Implementation

### Catalog / classification (`graphitron/`)

- New classifier step (location: `BuildContext`-adjacent, after the existing `ServiceCatalog.reflectTableMethod` / `reflectServiceMethod` populate the `MethodRef.Param.Typed` set; before generator emission). Produces `Map<String, ResolvedContextArg>` where `ResolvedContextArg(String name, TypeName javaType, List<MethodRef> sites)`. Annotated `@LoadBearingClassifierCheck(key = "context-argument.type-agreement", description = "...")`.
- New factory `Rejection.contextArgumentTypeConflict(String name, List<Map.Entry<MethodRef, String>> sites)` on the `Rejection` sealed interface (`Rejection.java`); reuses `AuthorError.Structural`.
- The classified map is stored on the build result and read by `GraphitronFacadeGenerator` and `GraphitronContextInterfaceGenerator`.

### Schema-driven factory (`GraphitronFacadeGenerator.java:54-72`)

- Replace the two-overload emission with a single overload: parameter list is `DSLContext defaultDsl` followed by the alphabetical-sorted `(JavaPoet TypeName, name)` pairs from the classified `ResolvedContextArg` map.
- Factory body: construct `new GraphitronContextImpl(defaultDsl, Map.of(name1, value1, ...))` (or a `LinkedHashMap` literal if `Map.of` arity becomes awkward at >10 args), stash under `GraphitronContext.class`.
- Annotated `@DependsOnClassifierCheck(key = "context-argument.type-agreement", reliesOn = "...")`.

### Sealed `GraphitronContext` (`GraphitronContextInterfaceGenerator.java:35-117`)

- Add `sealed permits GraphitronContextImpl` to the interface declaration.
- Drop the `getTenantId(env)` default method.
- Grow `getContextArgument` to `<T> T getContextArgument(DataFetchingEnvironment env, String name, Class<T> expectedType)`. Default body reads from the impl's name→value map, throwing the documented diagnostic strings on miss / mismatch.
- Emit a generated record `GraphitronContextImpl(DSLContext defaultDsl, Map<String, Object> contextValues) implements GraphitronContext` in the same package. Record-final closes off subclassing; sealed-interface closes off alternate implementations.

### `getContextArgument` typed call sites (`ArgCallEmitter.java:191, :285`)

- Emit the third arg: the expected-type literal. Use the JavaPoet `TypeName` parsed from `MethodRef.Param.Typed.typeName`; emit `$T.class` with the raw type (parameterised types collapse to their erasure for the runtime `isInstance` check, which is sufficient — the static-side type is already pinned by the consumer-side method parameter).
- Change `CodeBlock.of("$L.getContextArgument(env, $S)", ctx.graphitronContextCall(), param.name())` to `CodeBlock.of("$L.getContextArgument(env, $S, $T.class)", ctx.graphitronContextCall(), param.name(), rawType)`.

### DataLoader name emission (five sites)

Each of the five sites listed under "DataLoader name emission" above drops the `<ctx>.getTenantId(env) + "/"` segment. Helper extraction (a shared `DataLoaderNames.untenanted(env)` utility) is optional and can be deferred; the five sites share the same shape and R45 will need to revisit them anyway when it reintroduces the prefix.

### Runtime contract (`graphitron-rewrite-runtime/`)

No new runtime surface for R190. The `getContextArgument` signature change is in the generated interface, not the runtime artifact. R192 introduces the runtime functional interface for the validator factory.

## Tests

- **Pipeline (L4).** `GraphitronFacadeGeneratorPipelineTest` (or the closest existing test for that generator) gains a case: SDL with multiple `@service(contextArguments: [...])` sites producing the alphabetical-sorted factory parameter list. Snapshot the emitted factory `TypeSpec` and the sealed `GraphitronContext` interface + impl record. Loader-name snapshot for one of the five DataLoader sites pins the un-prefixed shape.
- **Classification (L2).** `ContextArgumentTypeAgreementTest` (new): two SDL fixtures, one accepted (single typeName per name across `@service` + `@tableMethod` + `@condition`), one rejected (`contextArgumentTypeConflict` with `List<Long>` vs `List<String>`). Assert the rejection's `name` and `sites` fields.
- **Audit (L2).** `LoadBearingGuaranteeAuditTest` is already wired; the new key `context-argument.type-agreement` lands with one producer and one consumer and the test stays green by construction. If the audit fails after the slice, the producer/consumer annotations are mis-keyed.
- **Compile (L5).** `graphitron-sakila-example` gains an SDL fragment with at least one `@service(contextArguments: ["userId"])` site so the new factory shape appears in the compile fixture; generated code passes `mvn compile -pl :graphitron-sakila-example -Plocal-db`.
- **Execute (L6).** `graphitron-sakila-example`'s test suite calls `Graphitron.newExecutionInput(dsl, userId)` and verifies the contextArgument round-trips through `getContextArgument` to the user method. A second test exercises the missing-value diagnostic by hand-rolling an `ExecutionInput.Builder` that omits the stash, and asserts the message text matches the documented form (substring match on `"call Graphitron.newExecutionInput(...)"`).

## Open questions

1. **Impl carrier shape: `Map<String, Object>` vs typed record fields.** Drafted as `Map<String, Object>` keyed by contextArgument name. Alternative: emit the impl record with one typed field per contextArgument (`GraphitronContextImpl(DSLContext defaultDsl, String fnr, UserInfo userInfo)`), with `getContextArgument` switching on name to read the typed field. Map keeps the impl uniform (one record component beyond `defaultDsl`); typed fields push contextArgument names into the impl's type signature too, closer to the spirit of "generation-thinking". Pick during implementation; cross-cuts no other decision.

2. **Diagnostic message: factory name as literal vs generator-emitted constant.** Drafted as the literal `"Graphitron.newExecutionInput(...)"`. Today the facade class+method are fixed by `GraphitronFacadeGenerator`, so the literal is accurate; if facade naming ever gains configurability (different consumer, different class name), the literal goes stale. Cheap to fix when it matters — change the emitter to inject the configured FQN at codegen.

## Roadmap entries (siblings / dependencies)

- **Splits from** [`tenant-routing-and-execution-input.md`](tenant-routing-and-execution-input.md) (R45). R45 awaits this landing before its Spec rescopes to the multi-tenant additions on top.
- **Reshapes** [`service-multi-tenant-fanout.md`](service-multi-tenant-fanout.md) (R46) transitively via R45: the public `ContextValueRegistration` permit and `GraphitronContext` extension-point assumptions R46 was built on dissolve here.
- **Affects** [`helper-emission-non-fetcher-hosts.md`](helper-emission-non-fetcher-hosts.md) (R85). The host-class `graphitronContext(env)` helper-emission gate stays structurally unchanged because the sealed-internal carrier keeps the same method set in single-tenant mode (no `getTenantId` to call). If R45 widens that set, R85 sees the addition cleanly.
- **Coordinates with** [`dslcontext-on-condition-tablemethod.md`](dslcontext-on-condition-tablemethod.md): both touch `ArgCallEmitter`'s param walk; no shared file edits but adjacent emission paths.
- **Spawns** [`custom-validator-factory.md`](custom-validator-factory.md) (R192) as the carved-out validator-override item.

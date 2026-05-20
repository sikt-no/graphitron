---
id: R190
title: "Single-tenant schema-driven ExecutionInput factory and sealed GraphitronContext"
status: Backlog
bucket: architecture
priority: 5
theme: service
depends-on: []
created: 2026-05-20
last-updated: 2026-05-20
---

# Single-tenant schema-driven ExecutionInput factory and sealed GraphitronContext

R45 (`tenant-routing-and-execution-input.md`) bundles the design every consumer needs — a schema-driven `newExecutionInput` factory whose parameter list reflects the schema's `contextArguments` with reflected Java types, a sealed generated `GraphitronContext` consumers cannot implement, and typed `getContextArgument(env, name, ExpectedType.class)` diagnostics that name the consumer-side fix and the schema-side site — together with the multi-tenant additions (tenant-column classification, `byTenant` factory overload, per-loader name partitioning, `@tenantId` ARGUMENT_DEFINITION directive) that only consumers with multiple tenants ever exercise. The single-tenant slice is independently shippable, unblocks the majority of consumers (who today populate `GraphQLContext` by hand and discover misconfiguration at first fetch), and gives R45 a landed baseline to layer multi-tenant fan-out on top of instead of a single monolithic spec where the simpler half is held up by the harder half.

Carve-line commitments for the Spec author:

- **No `getTenantId` method in single-tenant mode.** When `<tenantColumn>` is absent the sealed `GraphitronContext` does not declare `getTenantId` at all; the five DataLoader-name emission sites (`DataLoaderFetcherEmitter:135`, `TypeFetcherGenerator:4337`, `MultiTablePolymorphicEmitter:810,876`, `QueryNodeFetcherClassGenerator:161`) emit the un-prefixed `path` form with no stub call. R45 adds `getTenantId(): T` and the prefixed form together when it introduces the tenant column; the method set widens cleanly with no breaking change to the single-tenant shape.

- **Cross-site contextArgument type-agreement is a load-bearing classifier check.** The factory emitter pastes the reflected `TypeName` per name straight into the generated parameter list; emitter correctness depends on the classifier producing a *single* `TypeName` per name. The Spec body should declare the check with `@LoadBearingClassifierCheck` and the factory emitter with the matching `@DependsOnClassifierCheck` so the audit test enforces the pairing.

- **Consumers must go through the factory; direct `ExecutionInput.Builder` construction is unsupported.** The missing-value runtime diagnostic should name the factory method as the fix (`"context value 'fnr' was not supplied; call Graphitron.newExecutionInput(...) — required by com.example.FooService.foo(String)"`), not treat hand-rolled `ExecutionInput` construction as a parallel supported path. The type-mismatch case is statically impossible if consumers route through the factory (the parameter type is the reflected type); it remains as a belt-and-braces check for the unsupported direct-construction path.

- **Validator override is out of scope; tracked separately in R192.** R190 emits the existing `Validation.buildDefaultValidatorFactory().getValidator()` default unchanged.

This item covers the single-tenant design end-to-end; R45 is rescoped to "given the single-tenant factory and sealed context, add tenant column classification + `byTenant` routing + `@tenantId` arg" once this lands.

## Roadmap entries (siblings / dependencies)

- **Splits from** [`tenant-routing-and-execution-input.md`](tenant-routing-and-execution-input.md) (R45). R45 awaits this landing before its Spec rescopes to the multi-tenant additions on top.
- **Reshapes** [`service-multi-tenant-fanout.md`](service-multi-tenant-fanout.md) (R46) transitively via R45: the public `ContextValueRegistration` permit and `GraphitronContext` extension-point assumptions R46 was built on dissolve here.
- **Affects** [`helper-emission-non-fetcher-hosts.md`](helper-emission-non-fetcher-hosts.md) (R85). The host-class `graphitronContext(env)` helper-emission gate stays structurally unchanged because the sealed-internal carrier keeps the same method set in single-tenant mode (no `getTenantId` to call). If R45 widens that set, R85 sees the addition cleanly.
- **Coordinates with** [`dslcontext-on-condition-tablemethod.md`](dslcontext-on-condition-tablemethod.md): both touch `ArgCallEmitter`'s param walk; no shared file edits but adjacent emission paths.
- **Spawns** [`custom-validator-factory.md`](custom-validator-factory.md) (R192) as the carved-out validator-override item.

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

R45 (`tenant-routing-and-execution-input.md`) bundles the design every consumer needs — a schema-driven `newExecutionInput` factory whose parameter list reflects the schema's `contextArguments` with reflected Java types, a sealed generated `GraphitronContext` consumers cannot implement, typed `getContextArgument(env, name, ExpectedType.class)` diagnostics that name the consumer-side fix and the schema-side site, and an opt-in `<validatorFactory>` Mojo element — together with the multi-tenant additions (tenant-column classification, `byTenant` factory overload, per-loader name partitioning, `@tenantId` ARGUMENT_DEFINITION directive) that only consumers with multiple tenants ever exercise. The single-tenant slice is independently shippable, unblocks the majority of consumers (who today populate `GraphQLContext` by hand and discover misconfiguration at first fetch), and gives R45 a landed baseline to layer multi-tenant fan-out on top of instead of a single monolithic spec where the simpler half is held up by the harder half. This item covers the single-tenant design end-to-end; R45 is rescoped to "given the single-tenant factory and sealed context, add tenant column classification + `byTenant` routing + `@tenantId` arg" once this lands.

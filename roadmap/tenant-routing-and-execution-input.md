---
id: R45
title: "Database-per-tenant routing on the graphitron-owned connection lifecycle"
status: Spec
bucket: architecture
priority: 5
theme: service
depends-on: [connection-transaction-lifecycle]
last-updated: 2026-07-03
---

# Database-per-tenant routing on the graphitron-owned connection lifecycle

## Summary

R429 (`connection-transaction-lifecycle`) is the substrate: graphitron takes a `DataSource`, owns connection acquisition and transaction demarcation, and sets RLS session state transaction-locally from the request's contextArguments. Multi-tenancy resolves into two deployment shapes there:

- **Shared database, RLS partition.** The tenant is one more session-state value, set transaction-locally by R429's session-state policy from a contextArgument. This flavor needs nothing from R45: no config element, no factory change.
- **Database per tenant.** graphitron takes a `Map<TenantId, DataSource>`, resolves the tenant from request context once per operation at connection acquisition, and runs the identical transaction and session-state machinery against the routed source.

The tenant is therefore uniform across any one request. R45 owns the schema-integration layer for the second shape: how the tenant selector is declared and typed, the generated multi-tenant factory shape, the build-time validation tying the selector to the schema's contextArguments, and the retirement of the legacy per-field tenant machinery. R429 owns the runtime: the map lookup, acquisition, transactions, session state, and the execute-tier per-tenant isolation proof (its slice 4, which names this item's old coverage as its reshape target). One behaviour, one owner: R45 lands no runtime routing and no isolation execute test of its own.

## Design

### Tenant selector declaration

A Mojo element declares the selector's name and Java type `T`:

```xml
<tenantSelector>
  <name>tenantId</name>
  <javaType>java.lang.Long</javaType>
</tenantSelector>
```

Configured, it gates the multi-tenant factory below; absent, R429's single-`DataSource` factory is the whole surface. It is config rather than SDL because which database a request routes to is deployment topology, not data model, and R429 already treats the RLS identity values as config-mapped contextArguments. The element is specifically the database-per-tenant routing key: shared-database RLS deployments do not configure it.

### Resolved selector model

The selector name may or may not coincide with a declared contextArgument. Resolve that once, at build time, into a sealed outcome; the facade emitter, the GraphQLContext put site, and the validator all read the resolved arm rather than re-deriving a string comparison:

```java
sealed interface TenantSelector {
    /** Name matches a declared contextArgument; one factory parameter serves both roles. */
    record BoundToContextArg(ResolvedContextArg arg) implements TenantSelector {}
    /** Routing-only selector; the factory adds a standalone parameter. */
    record Standalone(String name, TypeName javaType) implements TenantSelector {}
}
```

`BoundToContextArg` construction checks that the configured `<javaType>` equals the contextArgument's reflected type; disagreement is a new rejection `tenantSelectorTypeConflict` (typed `AuthorError` record with a `message()` override and a factory on `Rejection`, mirroring `contextArgumentTypeConflict`). The classification is computed once and cached on the build result alongside `GraphitronSchema.contextArguments`, and `GraphitronSchemaValidator.validate` drains the rejection the same way `validateContextArgumentTypeAgreement` does. The four rejections of the previous design fall away with the machinery they mirrored.

### Multi-tenant factory

Rides R429's `DataSource` factory (its slice 5). With `<tenantSelector>` configured, the facade emits a second overload; for a schema with `@service(contextArguments: ["userInfo", "fnr"])` and a `Long` selector named `tenantId`:

```java
public static ExecutionInput.Builder newExecutionInput(
    Map<Long, DataSource> dataSourcesByTenant,
    Long tenantId,
    String fnr,
    UserInfo userInfo);
```

The map sits first, the selector second, then the alphabetical contextArguments. Exactly one selector parameter is emitted in both arms: under `BoundToContextArg` it doubles as that contextArgument (no duplicate parameter), under `Standalone` it exists for routing alone. The body null-checks every slot and puts the selector value on `GraphQLContext` under its configured name, so it reaches R429's session-state policy and any `getContextArgument` read like every other contextArgument; the map and the value are handed to R429's acquisition seam, which performs the lookup at connection acquisition. An unknown tenant is a request-level error raised there, before any SQL runs.

Composition holds: a per-tenant database with RLS inside it works because the same selector value is available to the session-state policy as an ordinary contextArgument.

Why the map is a factory parameter rather than the consumer resolving a `DataSource` themselves: graphitron holding the map is what lets it own the unknown-tenant error and route the tenant into transaction-local session state, the single-authority argument R429 is built on, and it is the hook R46's fan-out needs (fanning out requires the whole map).

### What the request-uniform tenant dissolves

The previous design carried a per-field routing model. All of it collapses once the tenant is resolved once per operation:

- **Per-field `TenantIdSource` axis and the `@tenantId` argument directive.** No per-field variation is left to classify. Per-field tenant selection would require per-field connection acquisition, contradicting R429's operation-scoped transaction; genuine many-tenants-in-one-request is R46's fan-out, a different execution model.
- **`<tenantColumn>` table classification.** Row scoping is the database's job under R429's RLS-assumed principle; codegen has no remaining consumer for "which tables carry the tenant column".
- **DataLoader-name tenant prefixes (five emission sites).** The generated `DataLoaderRegistry` is per-request and the tenant is request-uniform, so a name prefix partitions nothing.
- **Federation `_entities` per-tenant grouping and node-dispatch tenant grouping.** A batch cannot span tenants.

### Legacy retirement (implementation tasks)

- Delete the two commented-out execution-test stubs whose premise (per-`DataFetchingEnvironment` consumer-supplied tenant keys) no longer exists: `FederationEntitiesDispatchTest.entities_multiTenancyPartition_oneSelectPerTenant` and `GraphQLQueryTest.nodes_perTenantPartition_separateBatchPerTenant` in `graphitron-sakila-example`. R429 slice 4's isolation test is the successor coverage.
- Sweep the stale pre-R190 tenant prose: `MultiTablePolymorphicEmitter` ("build the tenant-scoped DataLoader", ~`:1341`), `QueryNodeFetcherClassGenerator` ("(type, alternative, tenantId) grouping", ~`:180`), `LoaderRegistration` ("tenant-qualified DataLoader name", ~`:43`). Anchor on the quoted phrases; lines drift.

## Tests

- **Classification (L2).** `TenantSelector` arms: selector name matching a declared contextArgument yields `BoundToContextArg`; no match yields `Standalone`; a type disagreement yields `tenantSelectorTypeConflict`, asserted as the typed record and the rendered `message()`.
- **Validation (L4).** The rejection drains through `validateTenantSelector` into a `ValidationError`, mirroring `ContextArgumentTypeAgreementValidationTest`.
- **Pipeline (L4).** Facade snapshots: selector configured with a matching contextArgument (one shared parameter), configured standalone (one added parameter), and unconfigured (no overload).
- **Compile (L5).** `graphitron-sakila-example` calls the multi-tenant overload.
- **Execute (L6).** None here. Per-tenant isolation and unknown-tenant rejection are R429 slice 4's to land; R45's selector surface is what that test routes through, so sequencing is decided when the first of the two reaches implementation.

## Open questions

1. **Element naming.** `<tenantSelector>` reads as if it applied to both multi-tenant shapes, but it configures only the database-per-tenant overload. Keep the name with explicit docs, or pick something overtly routing-flavored (`<tenantDataSourceKey>`)? Current pick: keep, document.
2. **Selector parameter position.** Drafted map-first then selector. If R429's factory work settles a different convention for its own parameters, follow it.

## Lineage

Two earlier designs are superseded and recorded only in this file's git history: the 2026-05-20 draft widened the sealed `GraphitronContext` with `getTenantId(env)` and `getDslContext(T)`; the 2026-06-26 rework replaced that with a per-field sealed `TenantIdSource` axis, `<tenantColumn>` table classification, a `byTenant Function<T, DSLContext>` factory overload, DataLoader-name prefixes, and federation per-tenant grouping. Both predate R429, which moved the seam from consumer-built `DSLContext` to graphitron-owned `DataSource` acquisition and made the tenant request-uniform, dissolving the per-field model.

## Siblings

- **Depends on R429** ([`connection-transaction-lifecycle.md`](connection-transaction-lifecycle.md)): the substrate; owns runtime routing and the execute-tier isolation proof.
- **R46** ([`service-multi-tenant-fanout.md`](service-multi-tenant-fanout.md)): the only remaining home for many-tenants-in-one-request. Its spec is stale (it still builds on a `ContextValueRegistration` permit dissolved by R190) and needs reconciliation before the deferral target is real.
- **R190** (Done, recorded in [`changelog.md`](changelog.md)): the contextArguments classifier, `ResolvedContextArg`, the rejection pattern, and the validator-mirror wiring this item reuses.
- Prior coordination and independence notes ([`dslcontext-on-condition-tablemethod.md`](dslcontext-on-condition-tablemethod.md), [`helper-emission-non-fetcher-hosts.md`](helper-emission-non-fetcher-hosts.md), [`service-short-classname-resolution.md`](service-short-classname-resolution.md)) dissolved with the `byTenant` dispatch; all three are independent of this item now.

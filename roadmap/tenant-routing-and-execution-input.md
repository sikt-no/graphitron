---
id: R45
title: "Operation-divined tenant routing: tenant-column bindings select the per-tenant DataSource"
status: Spec
bucket: architecture
priority: 5
theme: service
depends-on: [connection-transaction-lifecycle]
last-updated: 2026-07-03
---

# Operation-divined tenant routing: tenant-column bindings select the per-tenant DataSource

## Summary

The tenant is not known at request scope; it is divined from the operation itself:

```graphql
query { emner(filter: { eierOrganisasjon: 1234 }) { ... } }
```

The main insight: graphitron already knows which database column every argument, input field, nodeId package, federation representation, and parent row binds to. If the consumer additionally declares **which column carries the tenant id**, graphitron can infer, per field, where the tenant key comes from and route the connection accordingly. No `@tenantId` directive, no request-level tenant parameter: the schema's existing column mappings are the declaration, and `eierOrganisasjon: 1234` above routes the whole subtree to tenant 1234's database.

R429 (`connection-transaction-lifecycle`) is the substrate: graphitron owns connection acquisition and transactions, and database-per-tenant deployments hand it a `Map<TenantId, DataSource>`. R429 owns acquisition, transaction demarcation, and session state; R45 owns everything schema-shaped: the tenant-column declaration, the table classification, the per-field `TenantBinding` inference, the factory shape, the per-tenant partitioning at the fan-out points, and the validation that makes unroutable tenant-scoped fields a build error. The shared-database RLS flavor needs nothing from R45 (row scoping is the database's job there).

One R429 amendment is required and is called out in [Siblings](#siblings): R429 currently assumes the tenant "arrives as a contextArgument" and demarcates one read-only transaction per query operation. Operation-divined tenancy means acquisition happens per divined tenant: a query touching N tenants runs N read-only transactions. Both items are in Spec; reconcile there before either is signed off.

## Design

### Tenant column declaration and table classification

One Mojo element names the column:

```xml
<tenantColumn>eier_organisasjon</tenantColumn>
```

At catalog load, every table classifies as **tenant-scoped** (carries the column) or **global** (does not). The tenant Java type `T` is not configured; it is read off the jOOQ catalog's column type. All tenant-scoped tables must agree on that type; disagreement is a rejection (`tenantColumnTypeDisagreement`). Absent the element, none of the machinery below exists and R429's single-`DataSource` surface is the whole story.

### `TenantBinding`: the per-field inference

A sealed per-field axis, an optional overlay sibling to R316's `source()` / `operation()` / `target()` (computed only when `<tenantColumn>` is configured; in single-tenant builds the axis is absent, not "everything `Untenanted`"):

```java
sealed interface TenantBinding {
    /** An argument or input-object field whose column mapping resolves to a tenant column. */
    record ArgumentBound(ArgPath path, ColumnRef column) implements TenantBinding {}
    /** The field resolves by node id whose decoded key columns include the tenant column. */
    record NodeIdBound(/* decoded-column position */) implements TenantBinding {}
    /** A federation _entities representation carries the tenant column. */
    record EntityRepBound(/* rep field ref */) implements TenantBinding {}
    /** A child field inherits the tenant divined at its binding ancestor. */
    record Inherited(/* ancestor coordinate */) implements TenantBinding {}
    /** The field touches only global tables; runs on the default DataSource. */
    record Untenanted() implements TenantBinding {}
}
```

Classification rules:

- An argument or input-object field whose column mapping (the same resolution filters and conditions already use) lands on a tenant column yields `ArgumentBound`. This is the `emner(filter: { eierOrganisasjon })` case, and it covers mutations too: an insert/update input field mapping to the tenant column divines the routing for that mutation field.
- A field resolved by node id whose decoded key columns include the tenant column yields `NodeIdBound`; each id in a batch carries its own tenant.
- A federation `_entities` resolution whose representation carries the tenant column yields `EntityRepBound`; each rep carries its own tenant.
- A field below a bound ancestor yields `Inherited`: the divined tenant flows down the subtree. Within any execution context made tenant-homogeneous by the partitioning below, inheritance is a value hand-down, not a per-row re-read; the exact runtime carrier is an implementation choice against the `SourceKey.Reader` surfaces.
- A field touching only global tables yields `Untenanted`.
- A field reaching a tenant-scoped table with **no binding in scope** is a rejection (`noTenantBinding`), not a silent fallback: routing tenant data through a default connection because nothing named the tenant is exactly the cross-tenant leak this item exists to prevent.

A binding whose runtime value is absent (the nullable filter arrived empty) is a request-level error before any SQL, same family as R429's unknown-tenant lookup failure.

### Factory shape

Rides R429's `DataSource` factory. With `<tenantColumn>` configured, the facade emits a multi-tenant overload; for the catalog-inferred `T = Long` and `@service(contextArguments: ["userInfo", "fnr"])`:

```java
public static ExecutionInput.Builder newExecutionInput(
    DataSource defaultDataSource,
    Map<Long, DataSource> dataSourcesByTenant,
    String fnr,
    UserInfo userInfo);
```

`defaultDataSource` serves `Untenanted` fields (global reference data); the map serves every divined key. There is no tenant parameter: the tenant is not a request-scope fact. Unknown divined key at acquisition is a request-level error before any SQL (R429's seam raises it). Body follows the R190 null-check + `graphQLContext.put` shape.

### Execution: partition every fan-out point per tenant

Routing happens where the key is divined; batching machinery partitions so each SQL execution is tenant-homogeneous:

- **Root and mutation fields** (`ArgumentBound`): the emitted fetcher reads the bound value and acquires through R429 against the map. The DSLContext-selection sites are the direct-SQL fetchers, the DML fetcher in `TypeFetcherGenerator`, and `ServiceMethodCallEmitter`; all currently emit `getDslContext(env)` and gain the routed acquisition.
- **Node dispatch** (`NodeIdBound`): `QueryNodeFetcherClassGenerator.dispatchNodes` groups by `(type, alternative, tenant)` and issues one SELECT per group against that tenant's source. This restores the pre-R190 grouping its stale comment still describes.
- **Federation `_entities`** (`EntityRepBound`): `HandleMethodBody` re-widens the grouping to `Map<Integer, Map<TenantKey, List<Object[]>>>`, reads the tenant off each rep, and dispatches each inner group against its tenant's source.
- **Batched children** (`Inherited`): DataLoader identity partitions per tenant (the tenant key joins the path-derived loader name), so each loader is tenant-homogeneous and its captured environment routes the right source. This is load-bearing, not cosmetic: a batch loader resolves one `DSLContext` from the environment captured at loader creation, so a tenant-mixed loader would execute every key against the first key's tenant. The per-request `DataLoaderRegistry` means the partition only matters within a request, which is exactly when node ids and entity reps span tenants.

### Legacy retirement (implementation tasks)

- The two R190-commented execution tests come back to life as the canonical proofs of the per-key arms: `GraphQLQueryTest.nodes_perTenantPartition_separateBatchPerTenant` (`NodeIdBound`) and `FederationEntitiesDispatchTest.entities_multiTenancyPartition_oneSelectPerTenant` (`EntityRepBound`), reshaped from consumer-supplied `getTenantId` to inferred bindings.
- Sweep the stale pre-R190 tenant prose where it is inaccurate today and true it up as the machinery returns: `MultiTablePolymorphicEmitter` ("build the tenant-scoped DataLoader", ~`:1341`), `QueryNodeFetcherClassGenerator` ("(type, alternative, tenantId) grouping", ~`:180`), `LoaderRegistration` (~`:43`). Anchor on the quoted phrases; lines drift.

## Tests

- **Catalog (L1/L2).** Table classification against the configured column; `T` inferred from the catalog column type; `tenantColumnTypeDisagreement` on mixed types; no element, no axis.
- **Classification (L2).** SDL fixtures per arm: the `emner(filter: { eierOrganisasjon })` shape yields `ArgumentBound` with the resolved column; a node-id field whose key embeds the tenant column yields `NodeIdBound`; an entity resolution yields `EntityRepBound`; a child under a bound root yields `Inherited`; a global-table field yields `Untenanted`; a tenant-scoped field with no binding yields `noTenantBinding`, asserted as the typed record and the rendered `message()`.
- **Validation (L4).** Both rejections drain through `validateTenantBindings`, mirroring `ContextArgumentTypeAgreementValidationTest`.
- **Pipeline (L4).** Facade snapshot (overload iff configured, map key type from the catalog); loader-name partition snapshot (`Inherited` prefixed, `Untenanted` bare); node-dispatch and `_entities` grouping snapshots.
- **Compile (L5).** `graphitron-sakila-example` gains a multi-tenant fixture exercising the overload, an inferred argument binding, and a global reference-data fetch.
- **Execute (L6).** The two revived tests above; single-request isolation through an inferred `ArgumentBound` (same query, two tenant values, disjoint rows); unknown divined tenant errors before any SQL; a mutation routed by its input's tenant field.

## Open questions

1. **R429 reconciliation (blocking).** R429's connection model says the tenant "arrives as a contextArgument" and a query runs one read-only transaction. Amend to: the tenant key is supplied by the caller *or divined from the operation* (this item), and acquisition is per divined tenant, so a query touching N tenants runs N read-only transactions. Both items are in Spec; land the amendment there.
2. **Multiple bindings on one field.** Two arguments (or an argument plus a nodeId package) can both bind tenant columns. Recommendation: classify deterministically (document precedence), require runtime equality of the divined values, error on disagreement; a validate-time rejection would forbid legitimate schemas.
3. **Explicit override.** Is a directive escape hatch (`@tenantId` on an argument) needed for schemas where inference picks the wrong binding? Deferred until a real schema demonstrates the misfire; inference-only keeps the surface clean.
4. **Request-scope fallback.** Consumers who do know the tenant up front are covered by R429's contextArgument story; whether that fallback should also feed `TenantBinding` (an extra arm) is deferred to the R429 reconciliation.
5. **`Inherited` runtime carrier.** Subtree value hand-down vs parent-row column read; resolve against the live `SourceKey.Reader` / `ColumnRef` surfaces at implementation time.

## Lineage

Earlier designs, superseded and recorded in this file's git history: a 2026-05-20 draft widening the sealed `GraphitronContext` with `getTenantId(env)` / `getDslContext(T)`; a 2026-06-26 rework introducing a per-field `TenantIdSource` axis driven by an explicit `@tenantId` directive plus a `byTenant Function<T, DSLContext>` factory overload; and a brief 2026-07-03 request-scope `<tenantSelector>` variant, dropped the same day because the tenant is operation-divined, not request-scoped. The current design keeps the per-field axis but derives every binding from the column mappings the schema already carries.

## Siblings

- **Depends on R429** ([`connection-transaction-lifecycle.md`](connection-transaction-lifecycle.md)): the substrate (DataSource acquisition, transactions, session state, the `Map<TenantId, DataSource>` shape). Needs the amendment in Open question 1; its slice 4 execute coverage and this item's overlap, so the isolation proofs should land once, split as: R429 proves routing given a caller-known tenant, R45 proves the divined bindings.
- **R46** ([`service-multi-tenant-fanout.md`](service-multi-tenant-fanout.md)): fan-out (run one field across many tenants and union) stays separate; this item routes each field to the one tenant its data names. R46's spec is stale (it builds on a `ContextValueRegistration` permit dissolved by R190) and needs its own rework.
- **R190** (Done, recorded in [`changelog.md`](changelog.md)): the factory, classifier-cache, rejection, and validator-mirror patterns this item reuses throughout.

---
id: R45
title: "Operation-divined tenant routing: tenant-column bindings select the per-tenant DataSource"
status: Spec
bucket: architecture
priority: 5
theme: runtime-connection
depends-on: []
last-updated: 2026-07-14
---

# Operation-divined tenant routing: tenant-column bindings select the per-tenant DataSource

## Summary

The tenant is not known at request scope; it is divined from the operation itself:

```graphql
query { emner(filter: { eierOrganisasjon: 1234 }) { ... } }
```

The main insight: graphitron already knows which database column every argument, input field, nodeId package, federation representation, and parent row binds to. If the consumer additionally declares **which column carries the tenant id**, graphitron can infer, per field, where the tenant key comes from and route the connection accordingly. No `@tenantId` directive, no request-level tenant parameter: the schema's existing column mappings are the declaration, and `eierOrganisasjon: 1234` above routes the whole subtree to tenant 1234's database.

Multi-tenant queries are first-class: an API user can hold data in several tenants at once. The canonical case is a student with results at multiple universities. The query starts in a **tenant-index table** on the default source, mapping the student to the organisations holding their data; each returned row names the tenant its children must be fetched from, on a different connection per row. Lacking such an index, the fallback is fanning out across every tenant and looking, the pattern production runs by hand today; that fallback is R46's arm of this model (see [Siblings](#siblings)).

R429 (`connection-transaction-lifecycle`, Done; file self-deleted) is the shipped substrate: graphitron owns connection acquisition and transactions, and database-per-tenant deployments hand it a per-tenant DataSource map carried by the generated `TenantConnections` class (keyed by an opaque tenant key). R429 owns acquisition, transaction demarcation, and session state; R45 owns everything schema-shaped: the tenant-column declaration, the table classification, the per-field `TenantBinding` inference, the factory shape, the per-tenant partitioning at the fan-out points, and the validation that makes unroutable tenant-scoped fields a build error. The shared-database RLS flavor needs nothing from R45 (row scoping is the database's job there).

R429's connection model was amended in step with the 2026-07-03 revision to cover operation-divined tenancy, and R429 has since shipped (Done). It owns the transaction demarcation rule this item routes through: within one operation, all SQL for the same divined tenant shares one transaction, so a query touching N tenants runs N read-only transactions. This item references that rule rather than restating it.

## Design

### Tenant column declaration and table classification

One Mojo element names the column:

```xml
<tenantColumn>eier_organisasjon</tenantColumn>
```

At catalog load, every table classifies into one of three scopes:

- **Tenant-scoped**: carries the tenant column and is partitioned per tenant; lives in the per-tenant databases.
- **Tenant index**: carries the tenant column but is *not* partitioned; it lives on the default source and its rows point out into tenants (the student-to-organisation index above). Declared explicitly (see Open questions for the form), because carrying the column cannot distinguish partitioned data from an index over tenants.
- **Global**: does not carry the column; lives on the default source.

The tenant Java type `T` is not configured; it is read off the jOOQ catalog's column type. All tables carrying the tenant column must agree on that type; disagreement is a rejection (`tenantColumnTypeDisagreement`). Absent the element, none of the machinery below exists and R429's single-`DataSource` surface is the whole story.

### `TenantBinding`: the per-field inference

A sealed per-field axis, an optional overlay sibling to R316's `source()` / `operation()` / `target()` (computed only when `<tenantColumn>` is configured; in single-tenant builds the axis is absent, not "everything `Untenanted`"):

```java
sealed interface TenantBinding {
    /** Argument / input-object fields whose column mappings resolve to tenant columns.
        Carries every co-binding; the first is the documented-precedence primary, and the
        emitter guards that all divined values agree at runtime. */
    record ArgumentBound(List<BoundSlot> bindings) implements TenantBinding {}
    /** The field resolves by node id; the tenant is a decoded-column position of the batch key. */
    record NodeIdBound(/* decoded-column position */) implements TenantBinding {}
    /** The field resolves _entities representations; the tenant is a decoded-column position of the batch key. */
    record EntityRepBound(/* decoded-column position */) implements TenantBinding {}
    /** The tenant is a column of the arrived parent row (a tenant-index parent). */
    record ParentRowBound(/* parent-row column read */) implements TenantBinding {}
    /** A child field inherits the tenant divined at its binding ancestor. */
    record Inherited(/* ancestor coordinate */) implements TenantBinding {}
    /** The field touches only global or tenant-index tables; runs on the default DataSource. */
    record Untenanted() implements TenantBinding {}
}
```

`NodeIdBound`, `EntityRepBound`, and `ParentRowBound` form the **per-row family**: a single batch spans tenants, so their consumers partition rather than hand a value down. The two decoded arms deliberately share the "positional slot in a decoded key" shape: node ids and federation representations both decode at the DataFetcher boundary, and the model carries the decoded position, never a reference into the raw id string or rep map (wire format stays a boundary concern). `ParentRowBound` carries a column read off the parent row instead, the `SourceKey.Reader` family.

Classification rules:

- An argument or input-object field whose column mapping (the same resolution filters and conditions already use) lands on a tenant column yields `ArgumentBound`. This is the `emner(filter: { eierOrganisasjon })` case, and it covers mutations too: an insert/update input field mapping to the tenant column divines the routing for that mutation field. When several such bindings occur on one field, the classifier resolves the full set into the arm (deterministic precedence picks the primary) so the emitter reads the runtime-equality guard off the model rather than re-walking the arguments.
- A field resolved by node id whose decoded key columns include the tenant column yields `NodeIdBound`; each id in a batch carries its own tenant.
- A federation `_entities` resolution whose representation carries the tenant column yields `EntityRepBound`; each rep carries its own tenant.
- A child field reaching tenant-scoped data off a **tenant-index parent** yields `ParentRowBound`: each parent row names its own tenant, so the batch spans tenants by construction. A child under a tenant-scoped parent does not need this arm; its execution context is already tenant-homogeneous, so it classifies `Inherited`.
- A field below a bound ancestor yields `Inherited`: the divined tenant flows down the subtree. Within any execution context made tenant-homogeneous by the partitioning below, inheritance is a value hand-down, not a per-row re-read; the exact runtime carrier is an implementation choice against the `SourceKey.Reader` surfaces.
- A field touching only global or tenant-index tables yields `Untenanted`.
- A field reaching a tenant-scoped table with **no binding in scope** is a rejection (`noTenantBinding`), not a silent fallback: routing tenant data through a default connection because nothing named the tenant is exactly the cross-tenant leak this item exists to prevent. The *deliberate* no-binding case, fan out across every tenant and union the results, is R46's: an explicit schema marker classifies into a fan-out arm that lands there together with its emitters (an arm without emitters would violate the validate-time guarantee). Unmarked unroutable fields keep rejecting.

A binding whose runtime value is absent (the nullable filter arrived empty) is a request-level error before any SQL, same family as R429's unknown-tenant lookup failure.

### Factory shape

Rides R429's shipped acquisition seam, which differs from the pre-shipping sketch this section used to illustrate. What shipped (see `GraphitronFacadeGenerator` and `ConnectionRuntimeClassGenerator`): the facade emits `newExecutionInput(DSLContext defaultDsl, ...)` as the R190 escape hatch plus a distinctly-named `newOwnedExecutionInput(...)` for graphitron-owned acquisition (deliberately not an overload), and the per-tenant map is not a factory parameter at all; it lives on the generated `TenantConnections` carrier, whose `dataSourcesByTenant` field is keyed by an opaque tenant key resolved at acquisition time.

R45's factory work therefore extends `TenantConnections` and the `newOwnedExecutionInput` path rather than adding a map-taking `newExecutionInput` overload: the default source serves `Untenanted` fields (global reference data); the divined binding supplies the tenant key at each acquisition. There is still no tenant parameter on the factory, because the tenant is not a request-scope fact. Unknown divined key at acquisition is a request-level error before any SQL (R429's seam raises it). The concrete signature is re-derived against the shipped generators at pickup.

### Execution: partition every fan-out point per tenant

Routing happens where the key is divined; batching machinery partitions so each SQL execution is tenant-homogeneous:

- **Root and mutation fields** (`ArgumentBound`): the emitted fetcher reads the bound value and acquires through R429 against the map. The DSLContext-selection sites are the direct-SQL fetchers, the DML fetcher in `TypeFetcherGenerator`, and `ServiceMethodCallEmitter`; all currently emit `getDslContext(env)` and gain the routed acquisition.
- **Node dispatch** (`NodeIdBound`): `QueryNodeFetcherClassGenerator.dispatchNodes` groups by `(type, alternative, tenant)` and issues one SELECT per group against that tenant's source. This restores the pre-R190 grouping its stale comment still describes.
- **Federation `_entities`** (`EntityRepBound`): `HandleMethodBody` re-widens the grouping to `Map<Integer, Map<TenantKey, List<Object[]>>>`, reads the tenant at the classified decoded position of each representation, and dispatches each inner group against its tenant's source.
- **Children of tenant-index parents** (`ParentRowBound`): the tenant read off each parent row joins the loader name, so the mechanism is the same partition the per-key arms use: each per-tenant loader is tenant-homogeneous and its captured environment routes that tenant's source.
- **Batched children** (`Inherited`): DataLoader identity partitions per tenant (the tenant key joins the path-derived loader name), so each loader is tenant-homogeneous and its captured environment routes the right source. This is load-bearing, not cosmetic: a batch loader resolves one `DSLContext` from the environment captured at loader creation, so a tenant-mixed loader would execute every key against the first key's tenant. The per-request `DataLoaderRegistry` means the partition only matters within a request, which is exactly when node ids and entity reps span tenants. Two invariants keep the scheme honest: the name is composed by a single shared helper read by both the registration and lookup sites, and the tenant segment is an opaque partition key, never parsed back to recover the value (the captured environment carries the typed tenant).

### Worked example: student results across universities

`STUDENT_ORGANISASJON` (student id against organisations holding the student's data) is a declared tenant-index table on the default source; `RESULTAT` is tenant-scoped.

```graphql
query {
  student(id: "...") {
    organisasjoner {          # index rows, default source; Untenanted
      eierOrganisasjon
      resultater { karakter } # ParentRowBound: routed per row's eierOrganisasjon
    }
  }
}
```

`organisasjoner` runs one query on the default source. `resultater` partitions its loader by each row's `eierOrganisasjon` and acquires per partition through the map: one query and one read-only transaction per university that holds data for this student (R429's demarcation rule). Fields below `resultater` classify `Inherited`. Per-tenant RLS composes: each acquisition sets session state from the request's contextArguments, so a tenant database where this user has no access returns nothing rather than leaking.

### Legacy retirement (implementation tasks)

- The two R190-commented execution tests come back to life as the canonical proofs of the per-key arms: `GraphQLQueryTest.nodes_perTenantPartition_separateBatchPerTenant` (`NodeIdBound`) and `FederationEntitiesDispatchTest.entities_multiTenancyPartition_oneSelectPerTenant` (`EntityRepBound`), reshaped from consumer-supplied `getTenantId` to inferred bindings.
- Sweep the stale pre-R190 tenant prose where it is inaccurate today and true it up as the machinery returns: `MultiTablePolymorphicEmitter` ("build the tenant-scoped DataLoader", ~`:1341`), `QueryNodeFetcherClassGenerator` ("(type, alternative, tenantId) grouping", ~`:180`), `LoaderRegistration` (~`:43`). Anchor on the quoted phrases; lines drift.

## Tests

- **Catalog (L1/L2).** Three-way table classification against the configured column and the index declaration; `T` inferred from the catalog column type; `tenantColumnTypeDisagreement` on mixed types; no element, no axis.
- **Classification (L2).** SDL fixtures per arm: the `emner(filter: { eierOrganisasjon })` shape yields `ArgumentBound` with the resolved binding set (plus a two-binding fixture asserting the precedence primary and the carried co-binding); a node-id field whose key embeds the tenant column yields `NodeIdBound`; an entity resolution yields `EntityRepBound`; a tenant-scoped child under a tenant-index parent yields `ParentRowBound` while the same child under a tenant-scoped parent yields `Inherited`; a global-table field yields `Untenanted`; a tenant-scoped field with no binding yields `noTenantBinding`, asserted as the typed record and the rendered `message()`.
- **Validation (L4).** Both rejections drain through `validateTenantBindings`, mirroring `ContextArgumentTypeAgreementValidationTest`.
- **Pipeline (L4).** Facade snapshot (overload iff configured, map key type from the catalog); loader-name partition snapshot (`Inherited` prefixed, `Untenanted` bare); node-dispatch and `_entities` grouping snapshots.
- **Compile (L5).** `graphitron-sakila-example` gains a multi-tenant fixture exercising the overload, an inferred argument binding, and a global reference-data fetch.
- **Execute (L6).** The two revived tests above; single-request isolation through an inferred `ArgumentBound` (same query, two tenant values, disjoint rows); the worked student shape (one default-source index query plus one query per tenant holding data, asserted by query count per source); unknown divined tenant errors before any SQL; a mutation routed by its input's tenant field.

## Open questions

1. **R429 sync: resolved.** R429 has shipped (Done), so the lockstep-Spec discipline this question described is over. The remaining task is one-directional: R45 tracks the shipped seam (`TenantConnections`, `newOwnedExecutionInput`), and the Factory shape section above records where the shipped surface diverged from the pre-shipping sketch.
2. **Multiple bindings on one field: resolved into the model.** `ArgumentBound` carries the full co-binding set with a documented-precedence primary; the emitter emits a runtime equality guard across the set and errors on disagreement. A validate-time rejection would forbid legitimate schemas, and build time cannot know the values agree.
3. **Explicit override.** Is a directive escape hatch (`@tenantId` on an argument) needed for schemas where inference picks the wrong binding? Deferred until a real schema demonstrates the misfire; inference-only keeps the surface clean. Two shape notes for whoever picks this up: if the concern is SDL legibility (an author cannot see that a filter field routes databases), the mitigation is surfacing, not a directive (validation output, generated docs, or LSP hover naming each field's binding); and if a genuine single-connection cross-tenant read ever needs an escape hatch, it enters as an explicit positively-classified arm (`CrossTenant`) the validator and dispatcher both read, never a flag that suppresses the `noTenantBinding` rejection.
4. **Request-scope fallback.** Consumers who do know the tenant up front are covered by R429's contextArgument path; whether that should also surface as a `TenantBinding` arm (a request-bound sibling to `ArgumentBound`) is deferred until a schema needs both styles at once.
5. **Per-row carriers.** The exact shapes for `ParentRowBound`'s parent-row column read and `Inherited`'s value hand-down; resolve against the live `SourceKey.Reader` / `ColumnRef` surfaces at implementation time.
6. **Tenant-index declaration form.** Drafted as Mojo config (a `<tenantIndexTables>` list beside `<tenantColumn>`), consistent with tenancy-as-deployment-topology. A schema-side alternative (a directive on the `@table` type) would make the index role visible in the SDL, which is worth weighing here because the index role, unlike routing, arguably *is* a data-model fact.

## Lineage

Earlier designs, superseded and recorded in this file's git history: a 2026-05-20 draft widening the sealed `GraphitronContext` with `getTenantId(env)` / `getDslContext(T)`; a 2026-06-26 rework introducing a per-field `TenantIdSource` axis driven by an explicit `@tenantId` directive plus a `byTenant Function<T, DSLContext>` factory overload; and a brief 2026-07-03 request-scope `<tenantSelector>` variant, dropped the same day because the tenant is operation-divined, not request-scoped. The current design keeps the per-field axis but derives every binding from the column mappings the schema already carries.

## Siblings

- **Depends on R429** (`connection-transaction-lifecycle`, Done; file self-deleted): the substrate (DataSource acquisition, transactions, session state, the `TenantConnections` per-tenant map) and the owner of the transaction demarcation rule; amended 2026-07-03 to cover operation-divined tenancy, shipped since. Its execute coverage landed (`TenantRoutingExecutionTest`, `TenantConnectionsGeneratorTest`, `NewExecutionInputFactoryTest`), so the isolation proofs split as planned: R429 proved routing given a caller-known tenant, R45 proves the divined bindings.
- **R46** ([`service-multi-tenant-fanout.md`](service-multi-tenant-fanout.md)): the deliberate no-binding fallback. When no index narrows the tenant, the field fans out across every tenant and unions the results, the pattern production runs by hand today. Its arm of the `TenantBinding` axis lands there together with its emitters; its body was refreshed 2026-07-03 from the dissolved `ContextValueRegistration` design onto this substrate.
- **R190** (Done, recorded in [`changelog.md`](changelog.md)): the factory, classifier-cache, rejection, and validator-mirror patterns this item reuses throughout.

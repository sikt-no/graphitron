---
id: R45
title: "Operation-divined tenant routing: tenant-column bindings select the per-tenant DataSource"
status: In Review
bucket: architecture
priority: 5
theme: runtime-connection
depends-on: []
last-updated: 2026-07-21
---

# Operation-divined tenant routing: tenant-column bindings select the per-tenant DataSource

## Summary

The tenant is not known at request scope; it is divined from the operation itself:

```graphql
query { emner(filter: { eierOrganisasjon: 1234 }) { ... } }
```

The main insight: graphitron already knows which database column every argument, input field, nodeId package, federation representation, and parent row binds to. If the consumer additionally declares **which column carries the tenant id**, graphitron can infer, per field, where the tenant key comes from and route the connection accordingly. No `@tenantId` directive, no request-level tenant parameter: the schema's existing column mappings are the declaration, and `eierOrganisasjon: 1234` above routes the whole subtree to tenant 1234's database. A production consumer runs this divination by hand today as a per-fetch instrumentation heuristic; the [Production prior art](#production-prior-art-design-validation) section maps it onto the model.

Multi-tenant queries are first-class: an API user can hold data in several tenants at once. The canonical case is a student with results at multiple universities. In this item's iteration, that shape is served by R46's arm of the model: fan out across every tenant and union the results, the pattern production runs by hand today (see [Siblings](#siblings)). The targeted refinement, a **tenant-index table** on the default source whose rows name the tenant each child row must be fetched from, is extracted to R505 and layers on later; this item's classification is deliberately two-way (tenant-scoped or global) until then.

R429 (`connection-transaction-lifecycle`, Done; file self-deleted) is the shipped substrate: graphitron owns connection acquisition and transactions, and database-per-tenant deployments hand it a per-tenant DataSource map carried by the generated `TenantConnections` class (keyed by an opaque tenant key). R429 owns acquisition, transaction demarcation, and session state; R45 owns everything schema-shaped: the tenant-column declaration, the table classification, the per-field `TenantBinding` inference, the factory shape, the per-tenant partitioning at the fan-out points, and the validation that makes unroutable tenant-scoped fields a build error. The shared-database RLS flavor needs nothing from R45 (row scoping is the database's job there).

R429's connection model was amended in step with the 2026-07-03 revision to cover operation-divined tenancy, and R429 has since shipped (Done). It owns the transaction demarcation rule this item routes through: within one operation, all SQL for the same divined tenant shares one transaction, so a query touching N tenants runs N read-only transactions. This item references that rule rather than restating it.

## Design

### Tenant column declaration and table classification

One Mojo element names the column:

```xml
<tenantColumn>eier_organisasjon</tenantColumn>
```

At catalog load, every table classifies into one of two scopes:

- **Tenant-scoped**: carries the tenant column and is partitioned per tenant; lives in the per-tenant databases.
- **Global**: does not carry the column; lives on the default source.

A third scope, the **tenant-index table** (carries the column but is *not* partitioned; lives on the default source and points out into tenants), is R505's; it requires an explicit declaration because carrying the column cannot distinguish partitioned data from an index over tenants, and that declaration surface lands there.

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
    /** A child field inherits the tenant divined at its binding ancestor. */
    record Inherited(/* ancestor coordinate */) implements TenantBinding {}
    /** The field touches only global tables; runs on the default DataSource. */
    record Untenanted() implements TenantBinding {}
}
```

`NodeIdBound` and `EntityRepBound` form the **per-row family**: a single batch spans tenants, so their consumers partition rather than hand a value down. The two arms deliberately share the "positional slot in a decoded key" shape: node ids and federation representations both decode at the DataFetcher boundary, and the model carries the decoded position, never a reference into the raw id string or rep map (wire format stays a boundary concern). R505 adds a third member, `ParentRowBound` (a column read off a tenant-index parent row), when the index scope lands.

Classification rules:

- An argument or input-object field whose column mapping (the same resolution filters and conditions already use) lands on a tenant column yields `ArgumentBound`. This is the `emner(filter: { eierOrganisasjon })` case, and it covers mutations too: an insert/update input field mapping to the tenant column divines the routing for that mutation field. When several such bindings occur on one field, the classifier resolves the full set into the arm (deterministic precedence picks the primary) so the emitter reads the runtime-equality guard off the model rather than re-walking the arguments.
- A field resolved by node id whose decoded key columns include the tenant column yields `NodeIdBound`; each id in a batch carries its own tenant.
- A federation `_entities` resolution whose representation carries the tenant column yields `EntityRepBound`; each rep carries its own tenant.
- A field below a bound ancestor yields `Inherited`: the divined tenant flows down the subtree. Within any execution context made tenant-homogeneous by the partitioning below, inheritance is a value hand-down, not a per-row re-read; the exact runtime carrier is an implementation choice against the live batch-key surfaces (`KeyLift`, `SourceEnvelope`, the `SourceKey` residue; the seven-arm `SourceKey.Reader` this section once named is retired).
- A field touching only global tables yields `Untenanted`.
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
- **Batched children** (`Inherited`): DataLoader identity partitions per tenant (the tenant key joins the path-derived loader name), so each loader is tenant-homogeneous and its captured environment routes the right source. This is load-bearing, not cosmetic: a batch loader resolves one `DSLContext` from the environment captured at loader creation, so a tenant-mixed loader would execute every key against the first key's tenant. The per-request `DataLoaderRegistry` means the partition only matters within a request, which is exactly when node ids and entity reps span tenants. Two invariants keep the scheme honest: the name is composed by a single shared helper read by both the registration and lookup sites, and the tenant segment is an opaque partition key, never parsed back to recover the value (the captured environment carries the typed tenant).

Per-tenant RLS composes with every routed acquisition: each acquisition sets session state from the request's contextArguments, so a tenant database where this user has no access returns nothing rather than leaking. The worked student-across-universities example moved to R505 with the tenant-index scope; this iteration's canonical shapes are the `emner(filter: { eierOrganisasjon })` root routing and the per-row node-id/entity batches above.

### Production prior art (design validation)

The by-hand production stack (reviewed 2026-07-20 against the live consumer code) is a graphql-java `SimplePerformantInstrumentation` that wraps every DataFetcher and a `QueryInspector` heuristic that divines the tenant from the dynamic environment on each fetch, stashing it in `localContext` for descendants. Mapping it onto the model validates the axis: every heuristic lands on an arm.

- A recursive name-based search for the tenant argument through nested argument maps is `ArgumentBound`, including its input-object nesting. Production matches by argument *name* (two names, `eierInstitusjonsnummer` and `eierOrganisasjonskode`, carry the same id); R45 classifies by column mapping at build time, so a coincidentally-named argument cannot route and a renamed one cannot silently stop routing.
- Decoding the tenant out of the `id` / `ids` arguments is `NodeIdBound`. Production confirms the arm's precondition: tenant-scoped types' node ids embed the tenant column in their key (a per-tenant primary key is not globally unique without it).
- Scanning `ID!`-typed input fields and requiring all decoded values to agree is mutation-side `ArgumentBound` with the runtime-equality guard. Production already needed that guard, so open question 2's resolution is load-bearing, not defensive.
- Stashing the divined tenant in `localContext` and short-circuiting when a parent already set one is `Inherited`: first binding wins, the value flows down the subtree.

The deltas are the design case:

- Production *throws* on a mixed-tenant batch (more than one distinct tenant across a `nodes(ids:)` list or an input list). R45 partitions instead, one tenant-homogeneous execution per group, so batch-spanning requests become servable rather than erroring.
- Production's no-binding fallback silently runs on the default connection. R45 rejects at build time (`noTenantBinding`).
- Production re-divines on every field fetch with runtime reflection: recursive argument walks, schema-type inspection, `ID!` string matching. R45 computes the binding once at build time, from absolute knowledge of where the tenant id lives, and emits exactly the right read; the entire runtime heuristic collapses into classification.

Retirement criterion: with R45 (and R46 for the fan-out shapes) live, the consumer deletes its instrumentation and inspector wholesale.

### Legacy retirement (implementation tasks)

- The two R190-commented execution tests come back to life as the canonical proofs of the per-key arms: `GraphQLQueryTest.nodes_perTenantPartition_separateBatchPerTenant` (`NodeIdBound`) and `FederationEntitiesDispatchTest.entities_multiTenancyPartition_oneSelectPerTenant` (`EntityRepBound`), reshaped from consumer-supplied `getTenantId` to inferred bindings.
- Sweep the stale pre-R190 tenant prose where it is inaccurate today and true it up as the machinery returns: `MultiTablePolymorphicEmitter` ("build the tenant-scoped DataLoader", ~`:1341`), `QueryNodeFetcherClassGenerator` ("(type, alternative, tenantId) grouping", ~`:180`), `LoaderRegistration` (~`:43`). Anchor on the quoted phrases; lines drift.

## Review findings (2026-07-21, In Review → Ready; resolutions 2026-07-21)

Independent review of the six shipped slices (`e6b955a`..`5364cbf`). The shipped substance is strong: declaration/classification, the `TenantBinding` axis and both rejections, the typed tenant key on every runtime surface, the routed acquisition at the fetcher/dispatch/loader seams, and real database-per-tenant execution proofs all landed, and the full reactor is green under `-Plocal-db`. Rework is requested on the following; items 1 and 2 are the blockers, the rest ride along.

1. **Blocker: non-`Record`-backed shapes over tenant-scoped tables silently classify `Untenanted`.** `TenantBindingIndex.armOf` decides "reaches a tenant-scoped table" via `domainReturnType() instanceof DomainReturnType.Record` only. Multi-table polymorphic fields (`QueryField.QueryInterfaceField`, `ChildField.InterfaceField` / `UnionField`) and pivot fields (`ChildField.PivotField` / `BatchedPivotField`) return `DomainReturnType.Plain`, so a polymorphic or pivot field whose participant/attribute tables are tenant-scoped classifies `Untenanted` and its emitted fetcher acquires `dslDefault(env)` (all their DSL sites route through `TenantDslEmitter.dslExpression`). That is precisely the silent default-source routing the `noTenantBinding` rule exists to forbid ("a field reaching a tenant-scoped table with no binding in scope is a rejection, not a silent fallback"). Fix direction: the classifier's reach predicate must cover every table the field's SQL touches (participant sets, pivot attribute tables), not just the `Record` target; each such shape either resolves a binding (inheritance or argument) or drains through `noTenantBinding`. If v1 deliberately does not *route* these shapes, rejecting them in multi-tenant builds is acceptable; silently misclassifying them is not. Add classification tests per shape. Related: `TenantDslEmitter.dslExpression` throws `IllegalStateException` for `ArgumentBound` (a multi-table root carrying a tenant-column filter would crash generation ungracefully); once these shapes classify honestly, that arm needs either a real emission or a typed rejection.

2. **Blocker: `ConnectionHelper.routedDsl` divines by `localContext` presence, not the classified arm.** The lazy `totalCount`/`facets` resolvers route to the tenant source whenever any ancestor handed a tenant down, and to the default source otherwise. A paginated connection over a *global* table nested under a bound ancestor therefore counts against the tenant database while its rows (correctly, per the `Untenanted` arm) come from the default source; `TenantDslEmitter`'s own contract says `Untenanted` "deliberately never consults localContext". This is the one place the shipped code reintroduced the runtime presence-heuristic the whole item exists to retire. Fix direction: the connection carrier (`ConnectionResult` or its registration) should carry the field's classified arm (or the resolved routing) so the lazy resolvers read the build-time decision; a runtime null-check is not the classified fact.

3. **`EntityRepBound` has no execution-tier proof and the spec-named revival was silently substituted.** The promised revival of `entities_multiTenancyPartition_oneSelectPerTenant` became a pointer comment to `TenantDivinedRoutingExecutionTest.nodes_batchSpanningTenants_partitionsPerDecodedTenant`. The shared-surface argument is verified true (nodes dispatch synthesises reps into `EntityFetcherDispatch.resolveByReps`, so the L6 nodes test executes the identical per-tenant grouping and `dslFor` acquisition), and the widened grouping is pinned at pipeline tier; but the multitenant fixture contains no federation `@key` type, so a representation-map decode (as opposed to a synthesised id rep) never runs at execution tier. Either add a federated entity to the multitenant fixture with an `_entities` execution test, or record the substitution explicitly in the Tests section below as the accepted coverage shape.

4. **Housekeeping, same pass:** true up the Tests section's stale "facade snapshot (overload iff configured)" bullet to the shipped `TenantConnections`/`newOwnedExecutionInput` surface its own Factory-shape section records, and "Inherited prefixed" to the actual opaque-suffix composition; `TenantBinding.NodeIdBound.positionByTypeName` is populated but never read by any emitter (dispatch reads the entity-side facts), so either consume it or drop the arm's payload to what dispatch actually reads; `DataLoaderFetcherEmitter`'s 10-arg overload + `buildDataLoaderName` are no longer called from main sources; the single-tenant `TenantConnections` carrier gained `dslDefault()`/`defaultPinned` (dead surface in single-tenant builds, against "absent the element, none of the machinery below exists" — gate it or note the deliberate exception).
Resolutions (same day, rework pass):

1. `TenantBindingIndex.armOf` now computes the field's full reach (`reachedTables`): the Record target, a multi-table polymorphic field's table-backed participants (root, child, and service shapes), and a pivot field's attribute table. All-tenant-scoped reach resolves a binding or drains through `noTenantBinding`; a reach mixing tenant-scoped and global tables rejects as cross-scope (one statement cannot span sources, so no binding could help). Polymorphic roots additionally collect `ArgumentBound` slots from their per-participant filters, and the polymorphic root fetchers emit the full routed declaration (slot reads, agreement fold, `localContext` hand-down) via the same seam as every other fetcher, so the previous generation-time crash arm is gone. Classification tests cover the four shapes (bound, unbound-reject, cross-scope-reject, global).
2. `ConnectionResult` (multi-tenant builds) carries the `DSLContext` its owning fetcher resolved per the classified arm; `ConnectionHelper.totalCount`/`facets` read it off the carrier. The `localContext`-presence heuristic is deleted.
3. Accepted-substitution note recorded in the Tests section; the `@key` field-value decode is additionally pinned at pipeline tier.
4. Tests-section prose trued up; `NodeIdBound` slimmed to the verdict (positions live on the entity-side dispatch facts); the dead `DataLoaderFetcherEmitter` overload folded away; the default-source arm (`dslDefault`/`defaultPinned`) is now emitted only in multi-tenant builds, restoring "absent the element, none of the machinery exists".


## Tests

- **Catalog (L1/L2).** Two-way table classification against the configured column; `T` inferred from the catalog column type; `tenantColumnTypeDisagreement` on mixed types; no element, no axis.
- **Classification (L2).** SDL fixtures per arm: the `emner(filter: { eierOrganisasjon })` shape yields `ArgumentBound` with the resolved binding set (plus a two-binding fixture asserting the precedence primary and the carried co-binding); a node-id field whose key embeds the tenant column yields `NodeIdBound`; an entity resolution yields `EntityRepBound`; a tenant-scoped child under a tenant-scoped parent yields `Inherited`; a global-table field yields `Untenanted`; a tenant-scoped field with no binding yields `noTenantBinding`, asserted as the typed record and the rendered `message()`.
- **Validation (L4).** Both rejections drain through `validateTenantBindings`, mirroring `ContextArgumentTypeAgreementValidationTest`.
- **Pipeline (L4).** Typed tenant key on every runtime surface (`TenantRuntimeKeyTypeTest`: the shipped `TenantConnections` carrier and keyed acquisition, per the Factory shape section; there is no map-taking `newExecutionInput` overload); loader-name partition (`TenantRoutedFetcherPipelineTest`: `Inherited` composes the opaque tenant suffix through the carrier's single naming seam, `Untenanted` keeps the bare path form); per-tenant dispatch grouping for both decode shapes (a synthesised node-id rep and a federation `@key` field-value rep, each reading the tenant at its classified decoded position).
- **Compile (L5).** `graphitron-sakila-example` gains a multi-tenant fixture (`multitenant.graphqls`, `<tenantColumn>film_id</tenantColumn>`) exercising the typed per-tenant map construction, an inferred argument binding, and a global reference-data fetch.
- **Execute (L6).** `TenantDivinedRoutingExecutionTest` against real database-per-tenant PostgreSQL: single-request isolation through an inferred `ArgumentBound` (same query, two tenant values, disjoint rows); the batched child routed by the handed-down tenant; global data touching no tenant database; unknown divined tenant errors before any tenant acquisition; a mutation routed by its input's tenant field; a `nodes(ids:)` batch spanning tenants partitioning into one tenant-homogeneous group each. Coverage note, accepted shape: the two originally-named revived tests are subsumed rather than restored verbatim — node dispatch synthesises representations into `EntityFetcherDispatch.resolveByReps`, so the `nodes` execution test drives the identical per-tenant grouping and keyed acquisition the `_entities` field would, and the `@key` field-value decode (the shape `_entities` adds beyond node ids) is pinned at pipeline tier; a federated multi-tenant execution fixture would add an `_entities` wire-level pass and remains open as follow-up work.

## Open questions

1. **R429 sync: resolved.** R429 has shipped (Done), so the lockstep-Spec discipline this question described is over. The remaining task is one-directional: R45 tracks the shipped seam (`TenantConnections`, `newOwnedExecutionInput`), and the Factory shape section above records where the shipped surface diverged from the pre-shipping sketch.
2. **Multiple bindings on one field: resolved into the model.** `ArgumentBound` carries the full co-binding set with a documented-precedence primary; the emitter emits a runtime equality guard across the set and errors on disagreement. A validate-time rejection would forbid legitimate schemas, and build time cannot know the values agree.
3. **Explicit override.** Is a directive escape hatch (`@tenantId` on an argument) needed for schemas where inference picks the wrong binding? Deferred until a real schema demonstrates the misfire; inference-only keeps the surface clean. Two shape notes for whoever picks this up: if the concern is SDL legibility (an author cannot see that a filter field routes databases), the mitigation is surfacing, not a directive (validation output, generated docs, or LSP hover naming each field's binding); and if a genuine single-connection cross-tenant read ever needs an escape hatch, it enters as an explicit positively-classified arm (`CrossTenant`) the validator and dispatcher both read, never a flag that suppresses the `noTenantBinding` rejection.
4. **Request-scope fallback.** Consumers who do know the tenant up front are covered by R429's contextArgument path; whether that should also surface as a `TenantBinding` arm (a request-bound sibling to `ArgumentBound`) is deferred until a schema needs both styles at once.
5. **Inherited carrier: resolved into graphql-java `localContext`.** Every emitted fetcher already returns `DataFetcherResult`, so an `ArgumentBound` fetcher hands its divined key down as `.localContext(key)` and every `Inherited` descendant reads `env.getLocalContext()` (graphql-java propagates it when a fetcher sets none), the same carrier the production instrumentation used. One known refinement remains open: the per-row dispatch surfaces (`Query.node`/`nodes`, `_entities`) partition their own batches per decoded tenant but do not yet stamp a per-element `localContext` on each returned row, so an `Inherited` field *below* a per-row-resolved object currently fails loudly (absent binding value, before any SQL) rather than inheriting that row's tenant. Serving that shape means wrapping each dispatched element in its own `DataFetcherResult` with the row's decoded tenant; it degrades closed, so it can land as a follow-up increment.
6. **Tenant-index declaration form: moved to R505.** The tenant-index scope, its declaration surface (a 2026-07-20 principles consult leaned toward a directive on the `@table` type over a Mojo list; the analysis is summarised there), and the `ParentRowBound` arm were extracted to R505 to keep this item's first iteration two-way.

## Lineage

Earlier designs, superseded and recorded in this file's git history: a 2026-05-20 draft widening the sealed `GraphitronContext` with `getTenantId(env)` / `getDslContext(T)`; a 2026-06-26 rework introducing a per-field `TenantIdSource` axis driven by an explicit `@tenantId` directive plus a `byTenant Function<T, DSLContext>` factory overload; and a brief 2026-07-03 request-scope `<tenantSelector>` variant, dropped the same day because the tenant is operation-divined, not request-scoped. The current design keeps the per-field axis but derives every binding from the column mappings the schema already carries. On 2026-07-20 the tenant-index scope (`ParentRowBound`, the declaration surface, the student worked example) was extracted to R505 to keep this item's first iteration to a two-way classification.

## Siblings

- **Depends on R429** (`connection-transaction-lifecycle`, Done; file self-deleted): the substrate (DataSource acquisition, transactions, session state, the `TenantConnections` per-tenant map) and the owner of the transaction demarcation rule; amended 2026-07-03 to cover operation-divined tenancy, shipped since. Its execute coverage landed (`TenantRoutingExecutionTest`, `TenantConnectionsGeneratorTest`, `NewExecutionInputFactoryTest`), so the isolation proofs split as planned: R429 proved routing given a caller-known tenant, R45 proves the divined bindings.
- **R46** ([`service-multi-tenant-fanout.md`](service-multi-tenant-fanout.md)): the deliberate no-binding arm, and this iteration's answer for queries spanning tenants: the field fans out across every tenant and unions the results, the pattern production runs by hand today. Its arm of the `TenantBinding` axis lands there together with its emitters; its body was refreshed 2026-07-03 from the dissolved `ContextValueRegistration` design onto this substrate.
- **R505** ([`tenant-index-parent-row-routing.md`](tenant-index-parent-row-routing.md)): the extracted tenant-index scope. Adds the third table classification, its declaration surface, and the `ParentRowBound` arm that routes children per index-parent row, narrowing R46's fan-out to the tenants that actually hold data.
- **R190** (Done, recorded in [`changelog.md`](changelog.md)): the factory, classifier-cache, rejection, and validator-mirror patterns this item reuses throughout.

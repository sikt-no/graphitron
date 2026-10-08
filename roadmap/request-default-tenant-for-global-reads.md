---
id: R1005
title: "Request default tenant: route global reads to a caller-named tenant"
status: Spec
bucket: architecture
priority: 6
theme: runtime-connection
depends-on: []
created: 2026-10-08
last-updated: 2026-10-08
---

# Request default tenant: route global reads to a caller-named tenant

## Goal

Under database-per-tenant routing (`<tenantColumn>`), a request can name a *default tenant*, one of the tenants in its request tenant set, and graphitron's own reads of global tables (tables without the tenant column) in that request run on that tenant's connection, under that tenant's session mount, instead of on the runtime's fixed default source. A deployment whose global reference data is present in every tenant database, and whose session mount needs a tenant to run at all, can then serve root fields, `@splitQuery` children and `node` / `nodes` / `_entities` lookups over global types under the caller's own identity. Writes to global tables and `@service` calls keep the default source, because graphitron cannot see whether their SQL is safe to move. A request that names no default tenant behaves exactly as today.

Terms used below. A *tenant-scoped* table carries the configured tenant column and lives once per tenant database; a *global* table does not. The *default source* is the one `DataSource` the runtime is constructed with beside its per-tenant map, which today serves every global read. The *request tenant set* is the set of tenants a request may touch, passed to the generated request factory (`Graphitron.newOwnedExecutionInput`). A *session mount* is the consumer's `<sessionState>` method, which graphitron runs on every connection it takes and which can receive the tenant it is mounting for as an `Optional`.

The shape, over the multi-tenant sakila fixture (`graphitron-sakila-example/src/main/resources/graphql/multitenant.graphqls`, `film_id` as the tenant column), where `language` and `store` are global:

```graphql
type Query {
  films(filmId: Int! @field(name: "film_id")): [Film!]!   # tenant-routed by its argument
  languages: [Language!]!                                 # global root
}
type Inventory @table(name: "inventory") {
  inventoryId: Int @field(name: "inventory_id")
  store: Store @splitQuery                                # global child of a tenant row
}
```

```java
// today: languages and every store batch read the default source, mounted with Optional.empty()
Graphitron.newOwnedExecutionInput(Set.of(1, 2), claims);
// after: they read tenant 2's database, on the same connection and mount films(filmId: 2) would use
Graphitron.newOwnedExecutionInput(Set.of(1, 2), 2, claims);
```

The consumer this is for is sis, during its Graphitron 10 port. sis runs one database per institution (`<tenantColumn>INSTITUSJONSNR_EIER</tenantColumn>`, a `String` key) on the owned-connection path, with a `<sessionState>` mount that sets up an Oracle RAS session, which needs an institution number. About 65 sis tables carry no tenant column (national code tables such as `LAND`, `SPRAK`, `INSTITUSJON`, `STUDIENIVA`, `FYLKE`), and every institution database exposes the same views over them, so any of the caller's institution databases gives the same answer. Graphitron 9 read them on the user's own institution connection under the user's own session. Today they can only be read from the fixed default source, whose mount receives `Optional.empty()` and so cannot set up a RAS session; sis's default source therefore hands out no connection, which fails closed and fails every such read. Inline joins of global tables inside a tenant statement already run on the tenant connection and are unaffected.

## The rule

A request names a default tenant through the factory overload below. In such a request:

- **Graphitron's own global reads follow it.** A field classified as a global read (the `Untenanted` arm below) acquires the default tenant's connection: root fields over global tables, batched (`@splitQuery`) children over global tables whatever their parent, multi-table polymorphic fields over global participants, the count and facet queries of a connection over a global table (they ride the same connection carrier), and the per-type groups of `node`, `nodes` and `_entities` for a global type. The connection is the request's one entry for that tenant key, so a request that also routes a field to that tenant takes one connection and runs the mount once, with `Optional.of(tenant)`.
- **Writes and service calls keep the default source.** A global field whose statement writes (a DML mutation, a routine write) or calls a `@service` (including `@globalData`, a service returning a global `@table`, and a child service on a global parent) runs on the default source in every request (the new `DefaultSource` arm below). A service's SQL is opaque, so graphitron cannot tell whether it touches tables that exist only on the default source, and a write to replicated reference data in one tenant database would make that database disagree with the rest. The field's own re-read of rows it wrote stays on the same connection as the write, as today. `GraphitronContext.getDslContext(env)`, the hand-written-code accessor, also keeps the default source.
- **A route is never overridden.** Every field that names a tenant keeps its route: an argument (`ArgumentBound`), an ancestor's hand-down (`Inherited`), a parent row (`ParentRowBound`), a decoded id at a dispatch surface (`NodeIdBound`, `EntityRepBound`) and `@tenantFanOut` (`FanOut`). None of them reads the default source today, so the default tenant only replaces the fixed default source where nothing names a tenant.
- **Under a tenant-bound parent, a global batched child reads the default tenant, not the parent's.** `films(filmId: 1) { inventories { store { storeId } } }` in a request whose default tenant is 2 reads the inventories from tenant 1 and the stores from tenant 2. The global child's loader batches parents of every tenant into one statement today (its loader name carries no tenant), and keeps doing so; any tenant's database gives the same answer, which is the premise of naming a default tenant at all. A consumer that wants one connection per request in the common case names the tenant the request mostly touches.
- **The default tenant must be in the request tenant set and hosted.** The factory refuses a default tenant outside the set with an `IllegalArgumentException` at the call that made the mistake. The instrumentation refuses an operation whose default tenant has no `DataSource` in this runtime before any field runs, as it refuses an operation built without the set: the default tenant is chosen by the consumer, not the client, so there is no hosting probe to hide, and every global read in the request would otherwise fail one by one.
- **Absent, nothing changes.** The existing factory signature stays, and a request built through it reads global tables from the default source, mounted with `Optional.empty()`.
- **The choice is the consumer's.** sis passes a deterministic one (the caller's lowest institution number). Graphitron does not pick one from the set, because the tenant key type need not be ordered and the policy belongs to the deployment.

Consequence to document: in a request with a default tenant, a global read sees a global write from the same request only if the tenant database's view of global data reflects the default source's committed state, since the write and the read run on different databases. A mutation's re-read of its own rows inside the writing field is unaffected (same connection); a separate field reading those rows later in the response is not. A deployment that writes global data through graphitron and replicates it to tenant databases asynchronously should not name a default tenant on requests that write global data.

### Settled questions from the Backlog stub

- **Which global tables follow: all of them.** Naming a default tenant is the deployment's statement that its global tables can be read from any tenant database, so every global table a graphitron read reaches follows it. A per-table declaration (global tables that exist only on the default source) is deferred until a deployment needs both kinds in one request; such a deployment names no default tenant meanwhile. When it comes, it is a classification fact that turns the affected fields from `Untenanted` to `DefaultSource`, the same place R505 adds its scope, and nothing here needs to change shape for it.
- **Tenant-index tables (R505).** R505 is Backlog, and no index table exists yet. An index table lives only on the default source by definition, so R505's index reads must acquire the fixed default source (`dslDefault`), never `dslGlobal`. R505's body carries that constraint.
- **`@globalData` services keep the default source.** The directive's reference page says the service runs on the default source, and the reasons above (opaque SQL, possible writes) apply in full. A consumer that needs a read-only global service on its tenant connection needs a new author statement for it, which is a later item.
- **Dispatch surfaces.** `node`, `nodes` and `_entities` groups for a global type run on the default tenant's connection, one statement per alternative as today; nothing partitions, since a global id carries no tenant.
- **Factory surface: a positional overload of the owned factory.** See Implementation. It keeps the R975 convention that request-level tenancy is a typed factory parameter (a compile error to get the type wrong, never a stringly context lookup), puts the default tenant beside the set it must belong to, and leaves every existing caller compiling.

## Implementation

**Model: `TenantBinding`.** Add `record DefaultSource() implements TenantBinding` (singleton, like `Untenanted`): the field touches only global tables and its statement writes or calls a service, so it runs on the default source whatever the request names. Restate `Untenanted`'s javadoc as the global-read arm: graphitron's own SQL over global tables, acquired from the request default tenant when one is named, else the default source. The interface javadoc gains one sentence on the pair.

**Classification: `TenantBindingIndex.Fold`.** The arm is decided from the field's operation members, which the fold already reads (`operationMembers.membersOf(coord)`, `hasKind`):

- In `armOf`, where `!needsTenant` returns `Untenanted` today, return `DefaultSource` when the members include `OperationMember.Kind.WRITE` or `OperationMember.Kind.SERVICE_CALL`, else `Untenanted`.
- `globalDataArmOf` returns `DefaultSource` where it returns `Untenanted` today.
- The node-dispatch arm (`NODE_RESOLVE` with no tenant-scoped node types) stays `Untenanted`: it is a read.
- `edgeEstablishesContext` / `edgeDivinesTenant` and the every-path fold treat `DefaultSource` exactly as `Untenanted`: neither establishes a tenant context. The compiler names every other exhaustive switch.

**Generated runtime: `ConnectionRuntimeClassGenerator`.**

- `TenantConnections` gains a final `Optional<K> defaultTenant` field and constructor parameter after `tenants` (multi-tenant builds only), and an instance method `dslGlobal()` returning `entryFor(defaultTenant).dsl`, plus the static `dslGlobal(env)` beside `staticDslDefault` with the same checked-exception wrap. `entryFor` is unchanged: a present key goes through the membership check and the timed-out check like any routed key, and keys the same entry `dslFor(tenant)` uses, which is what gives one connection and one mount per tenant. `dslDefault` is unchanged and keeps meaning the fixed default source.
- A `DEFAULT_TENANT_KEY` constant beside `TENANTS_KEY`, the graphitron-owned `GraphQLContext` key the factory writes and the instrumentation reads.
- In single-tenant builds nothing is emitted for this: no field, no method, no key.

**Instrumentation: `GraphitronConnectionInstrumentationGenerator`.** In `beginExecuteOperation`, after decoding the set, read `DEFAULT_TENANT_KEY`. When present, fail the operation before any fetcher runs if `runtime.tenantKeys()` does not contain it (an `IllegalStateException` naming the tenant and saying it has no `DataSource` in this runtime), and pass `Optional.ofNullable(...)` to the carrier constructor.

**Factory: `GraphitronFacadeGenerator`.** In a `<tenantColumn>` build, emit a second owned factory, `newOwnedExecutionInput(Collection<K> tenants, K defaultTenant, <contextArgs...>)`, through `buildExecutionInputFactory` so the two owned forms cannot drift. It `requireNonNull`s `defaultTenant`, throws `IllegalArgumentException("Default tenant '…' is not in the request tenant set.")` when `tenants` does not contain it, and adds `b.put(TenantConnections.DEFAULT_TENANT_KEY, defaultTenant)`. The escape-hatch `newExecutionInput(DSLContext, ...)` gets no overload: it has no carrier, and nothing on that path reads the key. The javadoc on both owned forms names the other. Overload resolution is by arity, so a payload whose first parameter is also of the tenant key type still resolves; the positional hazard there (swapping the default tenant with that payload value) is a review concern for the consumer, and the membership check catches most swaps.

**Emission.**

- `TenantDslEmitter`: `Untenanted` renders `TenantConnections.dslGlobal(env)` in `resolve` and `dslExpression`; `DefaultSource` renders `TenantConnections.dslDefault(env)` there. `handDownOnly` answers the empty resolution and `loaderNameDeclaration` the bare path for both. Update the class javadoc's arm list.
- `HandleMethodBody.emitGroupDispatch`: the global-entity branch (`routing != null`, no bound slot) renders `dslGlobal(groupEnv)`.
- `RoutineWriteCommands.acquisitionOf`: `DefaultSource` maps to `TenantAcquisition.Untenanted`, whose render (`TenantAcquisitionFragments`) stays `dslDefault(env)`; `TenantBinding.Untenanted` becomes unreachable at a routine write (a write always classifies `DefaultSource`) and throws the generator-bug `IllegalStateException` in the style of the `FanOut` arm. `TenantAcquisition.Untenanted`'s javadoc says it is the write-side default-source acquisition.
- `GraphitronContextInterfaceGenerator`: unchanged (`getDslContext(env)` keeps `dslDefault`).
- The `TenantDslEmitter` javadoc's claim that the single-tenant fallback "fails loudly under owned multi-tenant acquisition" is false today (the owned `getDslContext(env)` returns `dslDefault(env)`). Correct the sentence in the same commit, since this item makes the difference between the two acquisitions matter; changing the fallback's behaviour is out of scope.

**Docs.**

- `docs/manual/how-to/tenant-scoping.adoc` §2 gains the paragraph drafted below, after the mount paragraph; the sentence "global tables read the default source" in the routing paragraph gains "unless the request names a default tenant (below)"; the mount paragraph's "the default source, which serves your global tables, receives `Optional.empty()`" is restated so it stays true in both cases.
- `docs/manual/reference/directives/globalData.adoc`: "The service runs on the default source" gains "also in a request that names a default tenant".

**Migration debt.** `DefaultSource` adds one arm to the `TenantBinding` axis, a walk-side fold that R682 moves into the store; like R992's arm, it adds no new walk-side registry. The predicate is a pure function of the field's own reach and operation members, both already facts the fold reads.

## User documentation (first-client check)

> **Reading global tables from a tenant database.** If your global tables are present in every tenant database (for example as views over shared reference data), a request can name one of its tenants as its *default tenant*, and graphitron then reads global tables for that request from that tenant's database instead of from the default source:
>
> ```java
> ExecutionInput input = Graphitron.newOwnedExecutionInput(permitted, lowestInstitution(permitted), claims)
>     .query(query).build();
> ```
>
> The default tenant must be in the request's tenant set, or the factory throws, and it must be one this runtime has a `DataSource` for, or the request fails before any field runs. Global reads then use the same connection, and the same session mount, as any field routed to that tenant, so your mount receives the tenant rather than `Optional.empty()`, and a request that routes to its default tenant anyway takes no extra connection. This covers root fields over global tables, `@splitQuery` fields over global tables (also below a tenant row: they read the default tenant, not the row's), and `node`, `nodes` and `_entities` for global types. Fields that name a tenant are routed exactly as before.
>
> Writes to global tables and `@service` fields, `@globalData` among them, still run on the default source, because graphitron cannot see what their SQL touches. A global read therefore sees a global write made earlier in the same request only if your tenant databases show the default source's committed data. Which tenant to name is your policy; graphitron never picks one. A request built without a default tenant reads global tables from the default source, as before.

## Tests

- **Classification** (`TenantBindingClassificationTest`): `Query.languages` and `Inventory.store` classify `Untenanted`; `Query.globalServedBy` (`@globalData`) classifies `DefaultSource`; a DML mutation over a global table, a root service returning a global `@table`, and a child `@service` on a global parent each classify `DefaultSource`; root node dispatch with no tenant-scoped node type stays `Untenanted`. An `Inherited` child below a `DefaultSource` field is still rejected for lack of a context, as below `Untenanted` today.
- **Generated runtime shape** (`TenantRuntimeKeyTypeTest` or `TenantConnectionsGeneratorTest`, read off `TypeSpec` / `MethodSpec`, no body-string assertions): in a multi-tenant build the carrier's constructor takes `Optional<K>` after the set, it declares `dslGlobal()` and the static `dslGlobal(DataFetchingEnvironment)`, and `DEFAULT_TENANT_KEY` exists; in a single-tenant build none of them does. The facade declares the owned overload with parameter types `(Collection<K>, K, <payload>)`, and the escape-hatch factory has no such overload.
- **Carrier substrate** (`TenantAuthorizationSubstrateTest`, over the real emitted runtime and fake JDBC): with a default tenant, `dslGlobal()` pins the tenant's source and the mount receives `Optional.of(tenant)`; `dslGlobal()` followed by `dslFor(tenant)` takes one connection and runs one mount; `dslDefault()` in the same carrier still pins the default source with `Optional.empty()`. Without a default tenant, `dslGlobal()` pins the default source with `Optional.empty()`.
- **Execution** (`TenantDivinedRoutingExecutionTest`, real database-per-tenant PostgreSQL). The tenant databases gain `language` and `store` tables with rows that tell the databases apart (for example a language named after its database), and the default source's data source gains an open counter beside `TENANT_1_OPENED` / `TENANT_2_OPENED`.
  - `{ languages { name } }` with default tenant 2 returns tenant 2's rows, opens only tenant 2, and the mounts for that request are exactly `[Optional.of(2)]`. Without a default tenant, the existing `untenanted_readsTheDefaultSource_touchingNoTenantDatabase` and `mount_receivesEachRoutedTenant_andEmptyForTheDefaultSource` hold unchanged.
  - `films(filmId: 1) { inventories { store { storeId } } }` with default tenant 1 opens tenant 1 once and mounts once (`[Optional.of(1)]`), never the default source; with default tenant 2 the inventories come from tenant 1 and the stores from tenant 2.
  - `globalServedBy` with default tenant 2 still answers the default database's name and opens no tenant database.
  - A DML mutation over `film_endorsement` (global; added to `multitenant.graphqls`, filtered by this class's note like the existing endorsement rows) with default tenant 2 writes the default database and opens no tenant database. The tenant databases have no `film_endorsement` table, so a write that went there would fail.
  - `node(id:)` for a global node type (the fixture's `Language` gains `implements Node @node(keyColumns: ["language_id"])`) with default tenant 2 resolves tenant 2's row.
  - The factory throws `IllegalArgumentException` for a default tenant outside the set, before any database is opened; a default tenant in the set but not hosted fails the operation before any database is opened.
  - Together these are the compile proof that the overload, the carrier and both acquisitions build at `<release>17</release>`.
- **Existing body-string pins.** `TenantRoutedFetcherPipelineTest.untenantedRootAcquiresTheDefaultSourceAndHandsNothingDown` and `globalEntityDispatchAcquiresTheDefaultSourceInMultiTenantBuilds` assert emitted method text naming `dslDefault`. `development-principles.adoc` bans that pattern at every tier, so they are not re-pinned to `dslGlobal`: their acquisition claim moves to the execution cases above, and what each still says that is not text (no hand-down, the bare loader name) is asserted from classification or `TypeSpec` shape or dropped where execution already covers it. `RoutineWriteTenancyPipelineTest` and `TenantAcquisitionFragmentsTest` are untouched: the write side keeps `dslDefault`.

## Other solutions we've considered

- **Inherit the parent's tenant for a global child under a tenant row** (Graphitron 9's behaviour). It saves a connection when the default tenant differs from the row's, but the global child's loader batches parents of every tenant into one statement, so inheriting would mean partitioning the global loader per tenant (more statements, not fewer) and reading `localContext` at a site whose javadoc promises not to. One rule, "global reads use the default tenant", is cheaper and the consumer controls the common case by choosing it.
- **Move every default-source acquisition, writes and services included.** Smaller diff (only `dslDefault`'s body changes), and it matches Graphitron 9. Rejected because it silently moves opaque service SQL and global writes onto a tenant database the moment a consumer names a default tenant, which contradicts the `@globalData` reference page and has no build-time check.
- **Per-table declaration of replicated global tables now.** No deployment needs a mix in one request; see Settled questions.
- **A default tenant on the runtime constructor.** It cannot vary per caller, and sis's identity is per caller.
- **Graphitron picks the default tenant from the set** (lowest, first). Needs an ordered key type and puts deployment policy in the generator.
- **A builder-style slot** (a generated `Consumer<GraphQLContext.Builder>` helper passed to `.graphQLContext(...)`) avoids the positional overload but moves the membership check from the factory call to execution, and is a second request-tenancy idiom beside R975's typed parameter.

## Siblings

- **R975** (Done, recorded in [`changelog.md`](changelog.md)): the request tenant set, the membership check in `entryFor` that the default tenant passes through, and the `Optional<K>` mount slot.
- **R992** (Ready, rework): parent-row routing from a global parent. A global parent read under a default tenant comes from that tenant's database; each child still routes on the tenant its row names.
- **R505** (Backlog): tenant-index tables, which live only on the default source; their reads acquire `dslDefault`, never `dslGlobal`.
- **R682** (Spec): moves the `TenantBinding` axis into the store; the `DefaultSource` arm moves with it.

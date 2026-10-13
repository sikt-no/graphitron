---
id: R1024
title: "Node and entity dispatch hand no tenant down, so Inherited children of a node fail"
status: In Progress
bucket: bug
priority: 2
theme: runtime-connection
depends-on: []
created: 2026-10-13
last-updated: 2026-10-13
---

# Node and entity dispatch hand no tenant down, so Inherited children of a node fail

## Goal

A tenant-scoped child field reached through `Query.node(id:)`, `Query.nodes(ids:)` or `_entities` reads from the tenant the decoded id names, the same as when it is reached through an ordinary root field. Today the build accepts the shape, and the request fails at runtime with `NoSuchElementException: The tenant binding value is absent; cannot route the operation to a tenant database.` In a multi-tenant build (`<tenantColumn>` configured), every field below a tenant-scoped node or entity type that runs a statement of its own on its parent's tenant (a `@splitQuery` child, a child service handed a `DSLContext`, anything nested below either) is unreachable through node and entity dispatch. After this item those fields resolve, each against its own node's tenant, and a `nodes` batch spanning tenants keeps each subtree on its own database.

From sis (Oracle, `<tenantColumn>INSTITUSJONSNR_EIER</tenantColumn>`), a self-reference on a tenant-scoped node type:

```graphql
type Organisasjonsenhet implements Node @table(name: "ORGANISASJONSENHET") {
    institutt: Organisasjonsenhet @splitQuery
}
```

```graphql
query { node(id: "<Organisasjonsenhet id>") { ... on Organisasjonsenhet { institutt { id } } } }
```

The same shape exists in the repo's own multi-tenant fixture (`multitenant.graphqls`, tenant column `film_id`), where `Film implements Node` is keyed on the tenant column and carries the `@splitQuery` child `inventories`:

```graphql
# works today: the root's filmId argument names the tenant and hands it down
{ films(filmId: 1) { inventories { inventoryId } } }
# fails today, resolves after this item: the decoded id names the same tenant
{ node(id: "<Film 1 id>") { ... on Film { inventories { inventoryId } } } }
```

## The defect

*Tenant context* is the build-time fact that a type is only ever reached below an edge that hands a tenant down. A tenant-scoped field on such a type classifies `TenantBinding.Inherited` ("use my parent's tenant") and, at runtime, reads its tenant from graphql-java's `localContext`, the one per-subtree value slot a data fetcher can set for everything below it. In generated code that slot carries the tenant and nothing else; `TenantHandDown` and `LocalContextOwnershipGuardTest` hold that invariant.

The classifier and the emitter disagree about whether node and entity dispatch hand a tenant down:

- **The classifier says yes.** `TenantBindingIndex.Fold.siteEstablishesContext` returns `nodeDispatchRoutable` for a `NODE_RESOLVE` site, and `Fold.foldAncestorContexts` leaves a type that a *routable* dispatch surface enters (`routableDispatchSurface`: a tenant-scoped node type whose key embeds the tenant column, or an entity type with a `TenantBinding.EntityRepBound`) out of the no-context seeds. A dispatch surface that cannot route (`unboundDispatchEntry`: global node and entity types, and tenant-scoped ones whose key lacks the tenant column) seeds no-context, so their tenant-scoped children reject at build time. That split is correct; this item keeps it and only ties the classifier's "routable" fact to the map the emitter reads (see Implementation).
- **The emitter says no.** `HandleMethodBody` (the per-type `handle<Type>` body inside `EntityFetcherDispatch`, reached by `node`, `nodes` and `_entities` through the shared `resolveByReps` seam) partitions each batch per decoded tenant and routes each tenant-homogeneous group through `TenantConnections.dslFor(groupEnv, tenantKey)`, then leaves bare jOOQ `Record`s in the result slots. Nothing stamps the tenant, so the fetchers above return rows with no `localContext`, and an `Inherited` child's `TenantConnections.divinedTenant(env.getLocalContext())` throws.

R45 recorded this as deferred at Done ("per-element localContext stamping below per-row dispatch surfaces, degrades closed"). It does degrade closed (the absent tenant throws rather than mis-routing), but because the classifier counts dispatch as establishing context, the build never rejects the shape and the failure only shows up at request time.

The fix is on the emitter side: the dispatcher hands each resolved row the tenant its group was routed on, so the runtime delivers what the classifier already assumes. The `@tenantFanOut` emission already does exactly this for its unioned rows (`ConnectionRuntimeClassGenerator.fanOutRows` wraps each row as a `DataFetcherResult` carrying the row's tenant as `localContext`, and graphql-java unwraps list elements individually), so the mechanism is in the tree and proven at execution tier; this item applies it to the second per-row surface.

## Implementation

**One stamping seam in the runtime.** `ConnectionRuntimeClassGenerator` emits a public static helper on `TenantConnections`, `<T> DataFetcherResult<T> handDown(T row, K tenantKey)`, where `K` is the build's typed tenant key (`schema.requestTenantKeyType()`, the type `divinedTenant` already returns), building `DataFetcherResult.<T>newResult().data(row).localContext(tenantKey).build()`. The typed key keeps the helper from being a general-purpose way into the slot. `fanOutRows` and `fanOutBatchRows` switch their inline `DataFetcherResult...localContext(success.key())` expressions to it, so the per-element stamp has one emitted home that both per-row surfaces share. The `CodeBlock` that calls the helper is minted by a tenant owner (a factory beside the existing acquisitions in `TenantAcquisitionFragments`), and `HandleMethodBody` splices it, so the owners named in `LocalContextOwnershipGuardTest` stay the record of who stamps and the list does not grow.

**`resolveByReps` slot contract, multi-tenant builds.** Every non-null slot is a `DataFetcherResult<Record>`: the field value graphql-java completes for that representation, stamped with its tenant when its group was routed per tenant, unstamped otherwise. One shape per build mode, rather than a slot that is sometimes a `Record` and sometimes an envelope, so each caller reads it with a total cast and no `instanceof` fork. Single-tenant builds keep bare `Record`s and stay byte-identical. In `HandleMethodBody.emitGroupDispatch`, after `emitAltSwitch` has run the group's SELECT, loop the group's bindings (`binding[0]` is the slot index) and wrap each filled slot:

- **Per-tenant branch** (`routing.bound() != null`): `result[slot] = TenantConnections.handDown((Record) result[slot], tenantKey)`. `tenantKey` is the typed key `divinedTenant(tenantEntry.getKey())` already produced for `dslFor`, so the value handed down is exactly the one the group was routed on, in the same typed form a routed root hands down. A group the `permits` guard skips never reaches the loop, so refused ids stay `null`.
- **Global branch** (multi-tenant build, global entity, `dslGlobal`): a plain `DataFetcherResult.<Record>newResult().data(row).build()`, no `localContext`. The classifier seeds these types no-context (`unboundDispatchEntry`), so nothing below them reads the slot.

The javadoc on `buildResolveByRepsMethod` and the class javadoc of `EntityFetcherDispatchClassGenerator` (its "entries are jOOQ `Record`s" wording) state the per-mode contract. `fetchEntities` already returns `List<Object>` and passes slots through unchanged; graphql-java unwraps each list element's `DataFetcherResult` before completing it, so `resolveType` and the `Node` type resolver keep reading a bare `Record`.

**`QueryNodeFetcherClassGenerator` and the root fetchers, multi-tenant emission only.** graphql-java unwraps one `DataFetcherResult` level for a field value, and one per list element; it does not unwrap an envelope nested inside another. So no caller may wrap a slot again:

- `nodes`: `rowsNodes` returns `List<Object>` and drops its `(Record)` cast, the DataLoader becomes `DataLoader<String, Object>`, and the `CompletableFuture` types follow. In `TypeFetcherGenerator.buildQueryNodesFetcher` the list type widens to `List<Object>`; its async tail wraps the *list* in the field's envelope, and the elements inside stay the stamped per-element results, the same nesting `@tenantFanOut` already produces through `collapseFanOut`.
- `node`: `fetchById` and `getNode` return `DataFetcherResult<Record>`, the slot as is, or an empty envelope for a null id, an unknown type id or a refused tenant. `TypeFetcherGenerator.buildQueryNodeFetcher`, which today assigns `Record payload = QueryNodeFetcher.getNode(env)` and wraps it through `returnSyncSuccess`, returns `getNode`'s envelope directly inside its existing `try`, keeping the `noChannelCatchArm`. That is the one success path that bypasses `FetcherResult.success`, deliberately: the envelope was built by the tenant owner's helper, and wrapping it again is exactly the nesting that breaks type resolution.

One local (`rowType`, `Record` or `Object` by `schema.tenantScopes()`) feeds the existing type-name locals in each generator, beside the existing multi-tenant fork for the loader name.

**Classifier: read one map.** `TenantBindingIndex.Fold.routableDispatchSurface` reads `byEntityType` alone, the same `TenantBinding.EntityRepBound` map `EntityFetcherDispatchClassGenerator` passes to `HandleMethodBody.TenantRouting`. Today it reads `nodePositions || byEntityType`, and the two agree only because every node type in `nodePositions` but not in `byEntityType` is rejected elsewhere (another key alternative lacks the tenant column, or an empty `nodeKeyColumns` gives the `NodeId` alternative no tenant slot) or is never dispatched (no resolvable alternative). With the stamp, "the classifier says this surface hands a tenant down" and "the emitter stamps this surface's rows" become the same question, so they read the same fact. `nodePositions` keeps its job of firing the node-key rejection and feeding `NodeIdBound`. The never-dispatched corner (a tenant-scoped node type with no resolvable alternative and no reaching edge) newly seeds no-context, which is correct: nothing hands it a tenant. The implementer confirms with `TenantBindingClassificationTest` that no passing build changes classification.

**User docs.** `docs/manual/how-to/tenant-scoping.adoc`, the paragraph opening "A node id (and a federation representation) carries its tenant inside the key", gains one sentence: fields below a node or entity that dispatch resolved read from that node's tenant, so a `nodes` batch spanning tenants keeps each node's subtree on its own database. `docs/manual/how-to/apollo-federation.adoc` changes in two places: where it describes a custom `fetchEntities` delegating back to the default, it says that in a multi-tenant build the default's results arrive as `DataFetcherResult`s carrying each entity's tenant, which a delegating fetcher returns as received; and the limitation bullet "Custom `fetchEntities` implementations must return entities the default `resolveEntityType` recognises (jOOQ `Record`s ...)" admits a `DataFetcherResult` wrapping such a `Record`.

## Tests

- **Execution tier** (`TenantDivinedRoutingExecutionTest`, multi-tenant fixture, no SDL change: `Film` is already a tenant-scoped node with `Inherited` children). Three cases, named for what they prove:
  - `node_inheritedChildOfTheResolvedNode_readsTheDecodedTenant`: `node(id: <Film 1>) { ... on Film { inventories { inventoryId } } }` answers tenant 1's two inventories with no errors, and `TENANT_2_OPENED` stays zero. This is the reproduction: it fails on trunk today with the absent-tenant `NoSuchElementException`. It covers the `getNode` / `fetchById` path.
  - `nodes_batchSpanningTenants_eachNodesSubtreeReadsItsOwnTenant`: `nodes(ids: [<Film 1>, <Film 2>]) { ... on Film { title stock { scenes { id } } } }` answers one scene for film 1 and three for film 2 (the fixture's per-tenant scene counts, which `nestedInheritedChild_neverSharesOneBatchBetweenTwoTenants` already relies on), so a child batch shared between tenants, or run on the wrong one, would fail the assertion. It covers the `rowsNodes` / DataLoader path and the nesting-type hop.
  - `nodes_refusedTenantsSlot_staysNullAndItsSubtreeNeverRuns`: the `nodes_batchSpanningTheSetsEdge_...` shape with an `inventories` selection, request tenant set `[1]`; film 2's slot is `null`, film 1's inventories resolve, and `TENANT_2_OPENED` stays zero.
- **`_entities`** rides the shared `resolveByReps` surface, as R45's accepted coverage did: the stamp lives in `handle<Type>` below all three fields, and `fetchEntities` passes slots through. The multi-tenant fixture declares no federation `@link`, and adding a federated multi-tenant execution fixture stays the follow-up R45 already recorded, not this item's.
- **No new pipeline-tier assertion.** Asserting the stamp as code strings in the emitted `handle<Type>` body would pin shape, not behaviour; the execution cases above are the pin, and the classifier change makes the structural agreement a single fact rather than something a test has to police. The existing single-tenant byte-for-byte baselines in `TenantRoutedFetcherPipelineTest` keep passing unchanged, which shows the single-tenant path did not move. `TenantBindingClassificationTest` and `TenantScopeValidationTest` keep passing unchanged after the `routableDispatchSurface` change.
- **Global node in a multi-tenant build** (regression for the uniform slot shape): the existing `defaultTenant_nodeOfAGlobalType_resolvesInTheDefaultTenantsDatabase` and `nodes_batchSpanningTheSetsEdge_resolvesThePermittedTenant_andNullsTheOther` keep passing.
- **Fan-out regression**: the existing `filmsEverywhere` / batched fan-out execution cases continue to pass on the shared `handDown` helper.
- **Consumer reproduction**: sis's `OrganisasjonsenhetInstituttQueryIT.enhetPaaInstituttnivaFarSegSelvSomInstitutt` and `enhetUtenInstituttnummerFarIkkeInstitutt`, once sis generates on trunk (after R1023). Not a gate for Done; the execution-tier reproduction above is.

## Other solutions we've considered

- **Stop counting node dispatch as establishing context** (make `siteEstablishesContext` return `false` for `NODE_RESOLVE`). Turns the runtime failure into a build-time rejection, but rejects a shape that is correct and that sis needs; the only remedy it would offer the author is to stop reaching tenant-scoped children through `node`. Rejected now that the emitter fix is small.
- **Route a tenant-scoped parent's child on the tenant column in its own loader key** (sis's key here is `Row5(INSTITUSJONSNR_EIER, ...)`): the parent-row routing R992 landed for a *global* parent and R505 / R996 discuss widening. It would fix this field but not a child whose key does not carry the tenant column, nor a child service, so it does not replace the stamp. If it is worth having for its own reasons, it belongs in those items.
- **Return tenants in a parallel array from `resolveByReps`** and let `getNode`, `rowsNodes` and `fetchEntities` wrap at their own boundaries, keeping the slot contract `Record`-only. Every `handle<Type>` signature and all three callers grow a second array, the stamp moves away from the one place the tenant is in scope, and a consumer's `fetchEntities` delegating to `resolveByReps` would still drop the tenant unless it learned the array too. The in-slot stamp keeps the change inside the branch that already holds the key and hands a delegating consumer stamped values by default.
- **Stamp only tenant-bound slots and leave global ones bare `Record`s.** Less emitted code, but each caller then has to tell the two shapes apart at runtime, and a delegating consumer's `instanceof Record` filter would work for global entities and silently drop tenant-scoped ones. One shape per build mode makes the contract something a consumer either handles or visibly does not.

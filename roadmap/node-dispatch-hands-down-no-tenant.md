---
id: R1024
title: "Node and entity dispatch hand no tenant down, so Inherited children of a node fail"
status: Backlog
bucket: bug
priority: 2
theme: runtime-connection
depends-on: []
created: 2026-10-13
last-updated: 2026-10-13
---

# Node and entity dispatch hand no tenant down, so Inherited children of a node fail

## Goal

A tenant-scoped child field reached through `Query.node(id:)` or `_entities` reads from the tenant the decoded id names, the same as when it is reached through an ordinary root field. Today the build accepts the shape, and the request fails at runtime with `NoSuchElementException: The tenant binding value is absent; cannot route the operation to a tenant database.` In a multi-tenant build (`<tenantColumn>` configured) every `@splitQuery` or inline child of a tenant-scoped node type that classifies as inheriting its parent's tenant is unreachable through node and entity dispatch.

From sis (Oracle, `<tenantColumn>INSTITUSJONSNR_EIER</tenantColumn>`), a self-reference on a tenant-scoped node type:

```graphql
type Organisasjonsenhet implements Node @table(name: "ORGANISASJONSENHET") {
    institutt: Organisasjonsenhet @splitQuery
}
```

```graphql
query { node(id: "<Organisasjonsenhet id>") { ... on Organisasjonsenhet { institutt { id } } } }
```

The generated `rowsInstitutt` routes with `TenantConnections.dslFor(env, TenantConnections.divinedTenant(env.<Object>getLocalContext()))`, and nothing put a tenant in that `localContext`. The same field reached through a root query routes fine, because the root fetcher hands its tenant down.

## The defect

*Tenant context* is the build-time fact that a type is only ever reached below an edge that hands a tenant down; a tenant-scoped field on such a type classifies `TenantBinding.Inherited` and reads its tenant from graphql-java's `localContext` at runtime. The classifier and the emitter disagree about whether node dispatch is such an edge:

- `TenantBindingIndex.Fold.siteEstablishesContext` returns `nodeDispatchRoutable` for a `NODE_RESOLVE` site, so a node type entered only through node dispatch has tenant context and its tenant-scoped children classify `Inherited`. `siteDivinesTenant` says the same for the any-path facts, and R1023's plan leans on it ("a node or entity type that dispatch enters with a tenant").
- The node and entity dispatchers (`QueryNodeFetcherClassGenerator`, `EntityFetcherDispatchClassGenerator`, through the shared `resolveByReps` seam) partition each batch per decoded tenant and route each group, but return rows without a `localContext`. Neither uses `TenantHandDown`, the rider every routed root fetcher attaches.

R45 recorded this as deferred at Done ("per-element localContext stamping below per-row dispatch surfaces, degrades closed"), and no item picked it up. It does degrade closed: the absent tenant throws rather than mis-routing. But since the classifier counts dispatch as establishing context, the build never rejects the shape, so the failure only shows up at request time.

## Direction

The natural fix is the one the deferral named: the dispatchers stamp each returned element with the tenant its id decoded to (`DataFetcherResult` per element, or the list-level equivalent graphql-java offers for a heterogeneous batch), so the emitter delivers what the classifier already assumes. A group is tenant-homogeneous by construction, so the value is at hand where each group is routed.

An alternative the reporter raised, routing a tenant-scoped parent's child on the tenant column carried in its own loader key (sis's key here is `Row5(INSTITUSJONSNR_EIER, ...)`), is the parent-row routing R992 landed for a *global* parent and R505 / R996 discuss widening. It would fix this field but not a child whose key does not carry the tenant column, so it does not replace the stamping. If Spec finds it worth having anyway, it belongs in those items.

Until this lands, the honest alternative to fixing the emitter is to make the classifier stop counting node dispatch as establishing context, which turns the runtime failure into a build-time rejection. That should only be a stopgap.

## Tests

An execution-tier case in the multi-tenant fixture (`TenantDivinedRoutingExecutionTest` already proves cross-tenant node batches partition): a `node(id:)` batch spanning two tenants selecting an `Inherited` child on each, asserting each child comes from its own tenant. Its `_entities` twin can ride the shared dispatch surface, as R45's coverage did. sis's `OrganisasjonsenhetInstituttQueryIT.enhetPaaInstituttnivaFarSegSelvSomInstitutt` and `enhetUtenInstituttnummerFarIkkeInstitutt` are the consumer-side reproductions.

## Provenance

Reported by the sis Oracle IT run (fs-plattform, branch `sis/upgrade-graphitron`, write-up in `sis/GRAPHITRON10_STATUS.md` § "Third IT run"). The run used a pre-R1019 snapshot of unknown exact commit and has not been re-run on trunk, because sis does not generate there until R1023 lands. The classifier/emitter mismatch above was confirmed by reading trunk at `1cb2e1b04`.

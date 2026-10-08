---
id: R1005
title: "Request default tenant: route global reads to a caller-named tenant"
status: Backlog
bucket: architecture
priority: 6
theme: runtime-connection
depends-on: []
created: 2026-10-08
last-updated: 2026-10-08
---

# Request default tenant: route global reads to a caller-named tenant

## Goal

Under database-per-tenant routing (`<tenantColumn>`), a request can name a *default tenant*, one of the tenants in its request tenant set, and reads of global tables (tables without the tenant column) in that request run on that tenant's connection and session mount instead of the runtime's fixed default source. A consumer whose global reference data is present in every tenant database, and whose session mount needs a tenant to run at all, can then serve root and split global reads under the caller's own identity. A request that names no default tenant behaves exactly as today.

## Context

Requested by the sis consumer during its Graphitron 10 port. sis runs one database per institution (`<tenantColumn>INSTITUSJONSNR_EIER</tenantColumn>`, a `String` key) on the owned-connection path, with `GraphitronRuntime(defaultSource, Map<String, Source>)` and a `<sessionState>` mount that sets up an Oracle RAS session (which needs an institution number) or a role per connection.

About 65 sis tables carry no tenant column: national code tables such as `LAND`, `SPRAK`, `INSTITUSJON`, `STUDIENIVA` and `FYLKE`. Every institution database exposes the same views over them, so any of the caller's institution databases gives the same answer. Every authenticated sis user has at least one institution. Graphitron 9 read these tables on the user's own institution connection under the user's own session: the parent's tenant when nested, otherwise the user's first institution.

Today global reads go to the one fixed default source per runtime, whose mount receives `Optional.empty()` (see the manual's tenant-scoping how-to, "global tables read the default source"). So there is no way to read global tables from one of the caller's own tenant databases, nor to mount the caller's identity for them: the RAS mount cannot run without an institution, and the `DataSource` cannot vary per request. Inline joins of global tables under a tenant parent already run on the tenant connection and are unaffected. What breaks is root fields over global tables (`Query.land`, `Query.sprak`), `@splitQuery` children of global type, and `node` / `nodes` / `_entities` on global types. sis's current workaround is a default source that hands out no connection, which fails closed and so fails every such read.

## Proposed shape (for Spec to decide)

- A per-request default tenant on the generated execution-input factory, for example `newOwnedExecutionInput(tenants, defaultTenant, ...)` or an optional slot beside the request tenant set.
- Global reads in that request route to that tenant's source: the same pinned connection and the same mount as tenant-routed fields for that key, so no extra connection or session.
- The default tenant must be in the request tenant set; naming one outside it is a client error, like any other routed tenant.
- Absent, behaviour is unchanged: the fixed default source.
- sis would pass a deterministic choice (the caller's lowest institution number); the choice policy stays with the consumer.

## Open questions for Spec

- **Which tables are "replicated global".** The proposal assumes every untenanted table exists in every tenant database. R505's tenant-index tables live *only* on the default source, and a deployment may also keep genuinely central global tables there. Spec decides whether the request default tenant applies to every untenanted table, or only to a declared subset (and if declared, where: per table in the SDL, or per deployment), and what a tenant-index read does when a default tenant is set.
- **`@globalData` services.** A root `@service` marked `@globalData` runs on the default source today. Decide whether it follows the request default tenant too, since its SQL is opaque and may touch central-only tables.
- **Parent-row routing.** A `@splitQuery` reference from a global parent into tenant data routes on the parent row, not on the default tenant (R505 and R992 extend that routing). Confirm the default tenant never overrides a parent-row or argument route, and only replaces the fixed default source where nothing else names a tenant.
- **Dispatch surfaces.** `node` / `nodes` / `_entities` on a global type: confirm they run on the default tenant's connection and do not partition.
- **Factory surface.** Positional parameter versus an optional builder slot; the request tenant set is required on every factory today, and this slot is optional.

Related: R505 (tenant-index parent-row routing), R992 (tenant from a foreign-key terminal column), the request tenant set and `Optional<K>` mount slot recorded under R975 in [`changelog.md`](changelog.md).

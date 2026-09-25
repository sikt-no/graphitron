---
id: R975
title: "Routed tenant acquisition: authorize the key and hand it to the session mount"
status: Backlog
bucket: bug
priority: 1
theme: runtime-connection
depends-on: []
created: 2026-09-25
last-updated: 2026-09-25
---

# Routed tenant acquisition: authorize the key and hand it to the session mount

## Goal

Under database-per-tenant routing (a `<tenantColumn>` build, where a field's tenant value is divined from its arguments or parent row and selects that tenant's `DataSource`), a routed field acquires a connection only for a tenant the request is authorized to use, and the `<sessionState>` mount learns which tenant it is mounting for. Today neither holds: a caller can route to any hosted tenant by naming it in an argument, and the mount receives the same payload for every tenant, so a consumer whose session identity is per tenant (Sikt's sis: VPD institution, roles and RAS realm all set per institution) can neither refuse the request nor mount the right identity. For sis this is a security blocker for going live, not a convenience.

## Observed (verified against the generator source, 2026-09-25)

- **Routed acquisition checks hosting, not authorization.** The generated `GraphitronRuntime.acquireForTenant(tenantKey, payload...)` (emitted by `ConnectionRuntimeClassGenerator`) looks up `sourcesByTenant.get(tenantKey)`, throws `NoSuchElementException` only when the key is unhosted, then calls `PinnedConnection.acquire`. The request's tenant collection, stored in the GraphQL context under `TenantConnections.FAN_OUT_TENANTS_KEY`, is read in exactly one place: `TenantConnections.fanOutDomain`, the fan-out path the manual's tenant-scoping how-to calls "the authorization pre-filter". The carrier's routed path (`entryFor(Optional<K>)` into `runtime.acquireForTenant(key.get(), payload)`) never consults it. So an argument such as `studenter(filter: {eierOrganisasjonskode: "194"})` from a caller with no rights at 194 routes to 194's source.
- **The collection only exists with `@tenantFanOut`.** `GraphitronFacadeGenerator` adds the `Collection<K> fanOutTenants` factory parameter only when a fanned field exists. A routed-only schema has no tenant collection to check against at all.
- **The mount never sees the key.** `mountCallArgs` builds `connection, dialect, settings, <payload...>`; `acquire(payload)` for the default source and `acquireForTenant(k, payload)` call the mount identically. The carrier holds one payload for the life of the request ("every mount receives the same payload"), so encoding the tenant in the payload is not a workaround: under fan-out one payload reaches every tenant's mount.
- **The output side is already per tenant.** A pinned entry is per tenant key, and the `$session` handle a service reads is the handle of the connection the call runs on, per tenant under fan-out. Only the input side lacks the key.

## Why the mount needs the key (sis)

One Oracle database hosts several institutions (VPD), and for RAS users there is one `ras_<db>` `DataSource` per database, so several tenant keys share one pool. The sis mount (`PK_RAS.Create_And_Attach`) first calls `FS.PK_VPD.set_instnr(<institution>)`, which in the shared-VPD case is the only thing separating two institutions; it then enables the user's roles at that institution and sets the per-institution person number that RAS realm predicates filter on. Without the key the mount can mount nothing institution-specific or guess (sis v9's documented "suspekt og trolig feil" fallback to the user's first institution), and a guess mounts institution A on a connection serving institution B. The v9 precedent (`ConnectionManager.createDSLContext(institusjonsnr)`) checked `harTilgangTil(institusjonsnr)` before opening a connection and passed the institution to its session setup.

## Direction (for the Spec author to decide)

- **Minimal:** the mount receives the tenant key. A throwing mount already fails the request closed and evicts the connection, so the consumer can do its own authorization check there, which gives v9's semantics. Default-source acquisition passes no key (absent, `Optional.empty()`, or a separate default mount).
- **Design fork:** the mount signature is consumer-declared and read at build time (`<mount>fqcn#method</mount>`; everything but the `Configuration`/`Connection` parameter is payload and becomes a factory contextArgument). How the generator recognises one parameter as the tenant key rather than payload (by the tenant column's Java type, by position, by name, by a separate config element) is open, as is what happens to an existing mount that declares no key parameter.
- **Possibly also:** routed acquisition intersects the key with the request's tenant collection when one is supplied, the same check `fanOutDomain` makes, so authorization does not rest on the consumer remembering to check in the mount. That raises whether the tenant collection becomes a factory parameter for every `<tenantColumn>` build rather than only with `@tenantFanOut`.
- **Pool keying:** several keys can share one `DataSource` while a pinned entry is per key, so session identity is per (connection, tenant). State it in the manual's session-identity section.
- **Second call site:** the `graphitron-mcp` `execute` tool mounts through `<devDatabase><claims>`; a mount signature change reaches it too.

## Tests the Spec should require

- Execution tier: a caller whose tenant set excludes tenant X sends an argument bound to X; refused before any SQL, and X's `DataSource` is never touched (connection counts, as the fan-out pre-filter tests do).
- A mount that asserts it received the routed key, including the shared-`DataSource` case (two keys, one pool, two mounts with different keys).

## Related

- R468 (Oracle/RAS execution coverage for session mounts) is adjacent: the sis mount is its load-bearing worked example.
- `tenant-fanout-argument-narrowing` concerns the fan-out domain only.
- Out of scope, filed separately by the sis session: `@service` fields neither divining nor stamping a tenant, and a self-FK `@reference` on UPDATE writing the tenant column in `SET`.

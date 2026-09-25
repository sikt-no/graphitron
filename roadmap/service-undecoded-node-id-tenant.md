---
id: R978
title: "Refuse a connection-binding root service that names no tenant under database-per-tenant"
status: Backlog
bucket: bug
priority: 2
theme: runtime-connection
depends-on: [service-tenant-binding]
created: 2026-09-25
last-updated: 2026-09-25
---

# Refuse a connection-binding root service that names no tenant under database-per-tenant

## Goal

Under database-per-tenant routing (a `<tenantColumn>` build, where each tenant's data lives in its own database), a root `@service` field that receives a connection (a `DSLContext` or the `$session` handle) and whose arguments name no tenant is refused at build time with a message naming the fix, instead of silently running on the default database. Once R976 (`service-tenant-binding`) lands, such a service is `Untenanted` (routed to the default source) as an accepted gap: its SQL is opaque, so the build cannot tell a service over global data from one writing tenant data. This item closes that gap without breaking services that really are global.

## Consumer-side resolution (sis, decided 2026-09-25)

The 40 sis service methods that take an encoded node id as a plain `String` (34 bean members such as `AktiverFagpersonerRecord.fagpersonVedLarestedID`, 6 bare `List<String> ids` parameters such as `UndervisningsaktivitetService.godkjennForPublisering`) migrate to the node table's jOOQ record with `@nodeId(typeName:)` on the SDL field: a `FagpersonRecord` member, a `List<UndervisningsaktivitetRecord>` parameter. That is the `CallSiteExtraction.NodeIdDecodeRecord` path R976 reads, for a bean member through `InputBeanResolver` and for a parameter through `ServiceCatalog.nodeIdSlotExtraction` (whose `takesTheNodeTablesRecord` admits a `List<XRecord>` slot). No generator change is needed for them. The migration also removes the wire-format leak the principles name: the services stop decoding Relay ids themselves.

Runtime inference from the embedded type id, as v9 did, was weighed during R976's spec and not chosen: the classification verdict would rest on per-request evidence.

## What the generator still owes

- **The refusal.** In `TenantBindingIndex.Fold.armOf`, a root `ServiceField` for which the "binds a connection" predicate R976 introduces holds, which divines nothing and has no tenant context, rejects instead of falling to `Untenanted`. The message names the tenant column and the fix: bind the tenant through a `@nodeId` on a jOOQ-record member or parameter, or a `@field` mapping to the tenant column. That the refusal would have fired on each of sis's 40 unmigrated methods is the proof that it is aimed right.
- **The escape for global services.** A service over genuinely global data (reference data on the default source) must still build. This is the open design question. Candidates to weigh: a declaration on the field; reading it off the service's return (a global `@table` return is already evidence); or scoping the refusal to mutations first. Whichever wins, it must be build-time decidable and must not become a way to silence the refusal for a service that writes tenant data.
- **Docs.** The user manual's tenant-scoping how-to shows the jOOQ-record `@nodeId` shape for services and states the refusal.

## Related

- R976 (`service-tenant-binding`): the decode path, the connection predicate, and the gap this item closes. This item depends on it.

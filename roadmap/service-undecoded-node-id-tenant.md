---
id: R978
title: "Services that take encoded node ids as strings name their tenant"
status: Backlog
bucket: bug
priority: 2
theme: runtime-connection
depends-on: []
created: 2026-09-25
last-updated: 2026-09-25
---

# Services that take encoded node ids as strings name their tenant

## Goal

Under database-per-tenant routing (a `<tenantColumn>` build), a `@service` whose tenant arrives only inside an encoded node id handed to the service as a plain `String` (a bean member or a `List<String> ids` parameter, with no `@nodeId`) either runs on the tenant's connection and hands the tenant down, or is refused at build time with a message naming the fix. Today it silently runs on the default database, and every tenant-scoped field under it is rejected. This is the question left open when R976 (`service-tenant-binding`) routes the services whose ids arrive decoded; settling it is what lets Sikt's sis route its remaining 40 of 63 service methods (34 bean-taking, e.g. `FagpersonService.aktiverFagpersoner(List<AktiverFagpersonerRecord>)` with `id: ID! @field(name: "fagpersonVedLarestedID")` on a `String` member; 6 bare-id, e.g. `UndervisningsaktivitetService.godkjennForPublisering(List<String> ids)`).

## Why the build cannot read these today

- The `ID` value reaches the service through a `CallSiteExtraction.Direct` leaf, so no node type, key column or tenant column is attached to it.
- `InputBeanResolver.singleValuedMemberDeferral` refuses `@nodeId` on a bean member that is not a jOOQ record, on the invariant that a consumer never receives the wire format. A tenant-scoped sis key embeds the tenant column beside others, so it is composite, and a one-value slot refuses a composite key in both `singleValuedMemberDeferral` and `ServiceCatalog.nodeIdSlotExtraction`.
- R976 leaves a root service that binds a connection and names no tenant `Untenanted`, as an accepted gap. This item owns it.

## Options to weigh

- **Consumer migration, no new generator surface.** sis declares these members as the node table's jOOQ record with `@nodeId(typeName:)`, or takes `List<XRecord>` parameters. That is the `NodeIdDecodeRecord` path R976 reads. It costs a Java change in 40 sis services and gives up nothing on the principle side. This item would then document the migration and add the build-time refusal below.
- **Runtime type-id dispatch, v9 parity.** Decode each `Direct` `ID` by its embedded type id (`NodeIdEncoder.peekTypeId`) against the per-type tenant positions node dispatch keeps. No schema change, but the classification verdict then rests on per-request evidence, and it merges the per-row node-dispatch family into `ArgumentBound`. The principles-architect review during R976's spec found this weaker than migration.
- **Build-time refusal of the gap.** Reject a connection-binding root service under `<tenantColumn>` that names no tenant, with a message naming the migration. The open part is how a service over genuinely global data declares that it is global.

A settled answer states which option ships, what the consumer writes, and what the rejection says.

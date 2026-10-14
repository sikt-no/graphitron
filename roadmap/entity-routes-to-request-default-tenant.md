---
id: R1035
title: "An opted-in federation entity routes to the request default tenant when its key names none"
status: Backlog
bucket: feature
priority: 2
theme: runtime-connection
depends-on: []
created: 2026-10-14
last-updated: 2026-10-14
---

# An opted-in federation entity routes to the request default tenant when its key names none

## Goal

In a database-per-tenant build, a federation entity type whose `@key` cannot carry the tenant column can opt in to being routed by the request instead of by its key. Every representation of that type is resolved on the request's default tenant (R1005's `RequestTenants.withDefault(set, tenant)`). The entity's rows and its subtree get a real tenant context, the way `TenantBinding.EntityRepBound` gives one when the key carries the tenant. Today such an entity is rejected: "its federation key alternative #0 does not carry tenant column". The routing is opt-in per type and never a silent fallback, so the default tenant serves no tenant-scoped read the schema did not ask for. When the request has no default tenant, each representation of an opted-in type resolves to `null` at its position rather than failing the operation. This is Graphitron 9 parity for sis's `FeideBruker`.

## Motivating case

```graphql
type FeideBruker @table(name: "PERSON") @key(fields: "brukernavn") {   # owned by tilgangsstyring
  brukernavn: String
  personProfil: PersonProfil @splitQuery                              # contributed by sis
}
```

A Feide username is unique only within an institution, and the router's representation is only `{__typename, brukernavn}`, so the key cannot name the tenant (`INSTITUSJONSNR_EIER`). sis decided on 2026-10-14 to keep v9's rule. In v9 this was hand-written sis code, not graphitron:

- `_entities` was routed to the caller's home institution: a JWT claim gave `UserInfo.getHjemmeinstitusjonsnr()`, with leading zeros stripped and `null` unless the caller has FS access there. v9 applied it only when every representation in the batch was a `FeideBruker`. Per-type routing removes that limit, which sis accepts.
- Usernames with no `PERSON` row where `INSTITUSJONSNR_EIER` equals the home institution resolved to `null` at their position, and the other positions kept their index.
- A caller with no home institution (machine users) got `null` at every `FeideBruker` position. This was deliberate: no guessing and no cross-institution lookups.

sis will set the default tenant from the home institution in `motor.tenantsFor(userInfo)` and port v9's `FeideBrukerEntityIT`.

## What sis asks for

1. **A request routing source for entities.** An explicit opt-in on the entity type; the Spec names the directive or argument. The tenant fold treats it as a tenant context the entity's subtree inherits (`personProfil` and below), which also removes the R1027 cascade below `FeideBruker` for good rather than only collapsing it.
2. **No request tenant: `null` per position.** R1005 throws for a default that is unhosted or outside the request's tenant set, which is right for global reads. On this path a missing default yields a `null` entity. sis prefers `null` for an unusable default as well, and can avoid setting one. The Spec decides whether the two cases behave the same.
3. **The ownership check as an `@condition`.** sis will state "`PERSON.INSTITUSJONSNR_EIER` = the routed tenant" (and anything else it needs) as an `@condition`, so an unowned username resolves to `null` at its position.

## Questions for the Spec

- **Is `@condition` honoured on `_entities` dispatch for a type, and where is it declared?** Neither `apollo-federation.adoc` nor `tenant-scoping.adoc` says. If it is not honoured, this item adds it or names the item that does.
- **Can the condition read the routed tenant,** for example as a context argument?
- **Does routing alone already constrain the rows?** In a database-per-tenant deployment, routing to the tenant's database may already mean every `PERSON` row is owned by that institution, so the condition would only be a guard. Say which, so sis knows whether the condition is load-bearing.
- **Fold arm.** A new `TenantBinding` arm beside `EntityRepBound`, or `EntityRepBound` reading its tenant from a different source. Either way R1027's dispatch-entry rule and this item's opt-in must agree, so an opted-in entity does not reject.
- **Shared runtime path.** `_entities` goes through the shared `resolveByReps` seam (R1024). Check whether a per-type request-routed group sits in the same per-tenant grouping as key-routed types in one batch.
- **Scope.** Whether `Query.node` / `nodes` dispatch can opt in the same way, or whether that waits for a consumer to ask.

sis expects 0 author errors on validate once R1027, R1032 and this item land.

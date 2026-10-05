---
id: R989
title: "A root @service whose only tenant carrier is a scalar input member cannot route under database-per-tenant"
status: Backlog
bucket: feature
theme: runtime-connection
depends-on: []
created: 2026-10-05
last-updated: 2026-10-05
---

# A root @service whose only tenant carrier is a scalar input member cannot route under database-per-tenant

## Goal

Under database-per-tenant routing, a root `@service` handed a connection can route on a plain scalar value the author declares to be the tenant, such as an `eierOrganisasjonskode: String!` member of a hand-written input bean, instead of being refused with "nothing in its arguments names a tenant". Today a service routes only when its arguments reach a jOOQ record field bound to the tenant column or a decoded `@nodeId` (R976), and everything else is refused (R978). That leaves no clean fix for the common "create X at institution Y" mutation, where no row or id exists yet and the only thing that names the tenant is a scalar in the input. The query side already routes on such a scalar through `@field(name:)` on the tenant column (R965); this item gives the service path the same carrier.

## Minimal pair

Over the `TenantBindingClassificationTest` fixture (`film_id` as tenant column, `SERVICE_TYPES`, `TenantServiceStub`). The nearest existing neighbour is `anUndecodedIdHandedAConnectionRejectsAtTheRootAndTheChildStillRejects`, and the pair extends it.

Before: a service taking a `DSLContext` and a hand-written bean whose `filmId` member is an `Integer`.

```graphql
input RateByFilmIdInput { filmId: Int!  rating: Int }
type Mutation {
    rateByFilmId(in: RateByFilmIdInput!): RateFilmsPayload
        @service(service: {className: "...TenantServiceStub", method: "rateByFilmId"})
}
```

```java
public static TenantFilmsPayload rateByFilmId(DSLContext dsl, RateByFilmIdBean in) { ... }
```

`Mutation.rateByFilmId` rejects with `UnroutedServiceCall`, and `RateFilmsPayload.films` rejects with "no ancestor established a tenant context".

After: the same field with `filmId` marked as the tenant carrier (spelling open, see below) is `ArgumentBound` with one slot `in.filmId`, read as `NestedInput("in", ["filmId"])` with the `Raw` projection, and `RateFilmsPayload.films` is `Inherited`, with no rejections. That is the slot shape `aJooqRecordParameterBindingTheTenantColumnDivinesRaw` already asserts for a record field, so the agreement machinery downstream of the slot needs nothing new. The bean (`RateByFilmIdBean`) and the stub method are new test surface.

## What is true today

- `TenantBindingIndex.collectFromServiceCall` walks every argument-sourced parameter's `ValueShape` through `collectFromValueShape`. `JavaBeanInput` and `RecordInput` recurse into their members, `JooqRecordInput` mints a `Raw` slot for a column binding on the tenant column or a `DecodedKeySlot` for a `@nodeId` decode, and a `ValueShape.Scalar` goes to `collectFromServiceLeaf`.
- `collectFromServiceLeaf` mints only for `NodeIdDecodeRecord` and `NodeIdDecodeKeys` (and declines a polymorphic record decode). Every other leaf "carries no column and mints nothing", so a scalar member of a bean, and by the same arm a top-level scalar argument of a root `@service`, never routes.
- With no slot and a connection handed in, R978's refusal fires. Its text names two fixes, a node table's jOOQ record with `@nodeId(typeName:)` or a record field bound to the tenant column, and `@globalData`.
- Once a slot is minted, `SlotCollector` already gives the rules this item needs: every element of a list input must agree on the tenant, and a scalar slot must agree with any decoded `@nodeId` slot in the same call (R976). R975's request-tenant-set check authorizes the routed tenant whatever its source.

## Open design points for the Spec

1. **Spelling.** On a query argument, `@field(name: "film_id")` names a column, which is why R965 can read it as the tenant binding. On a bean or Java record input member, `@field(name:)` is believed to name the Java member, so it cannot double as the marker there; the Spec settles that by reading how `@field` resolves on `JavaBeanInput`/`RecordInput` members. Candidates: a dedicated marker (for example `@tenant` on an input field or argument), or reusing `@field(name:)` where it does name a column. The top-level scalar argument of a `@service` (`rateById(filmId: Int!)`) hits the same `Scalar` leaf and is in scope; whether `@field(name:)` is accepted there today is to be checked.
2. **Trust.** The service is opaque, so the marker is an author statement, like `@globalData`. The build cannot see that the value is used as the tenant; the request-time tenant-set check is the authorization.
3. **Type.** The scalar's Java type must convert to the tenant key type (sis: institution number as a `String`, with leading zeros normalized). Check the conversion at build time and reject a marker whose type cannot convert.
4. **Batches and siblings.** Covered by the existing slot agreement, as above; the Spec should pin a list-of-beans case and a scalar-beside-`@nodeId` case, including a disagreeing pair that is refused before the service runs.
5. **`@globalData`.** A marked scalar is an argument naming a tenant, so it must land on the `@globalData` contradiction rung (`globalDataOverArgumentsNamingATenantRejects`); minting the slot the same way should give that for free, and the Spec pins it.
6. **Docs and refusal text.** The service paragraph of `docs/manual/how-to/tenant-scoping.adoc` and the R978 `UnroutedServiceCall` message both name the fixes and must name the new carrier.

## Motivation

Reported by the sis Graphitron 10 migration (`<tenantColumn>INSTITUSJONSNR_EIER</tenantColumn>`, validated against trunk at `17196b8eb`). R978 refuses 20 root `@service` fields there. Nine were fixed sis-side with a bean member typed as the node table's jOOQ record plus `@nodeId`. The other eleven are create-mutations (`opprettFagpersonerGittFodselsnumre`, `opprettStudenter`, `registrerInnreisendeUtvekslinger`, `overfoerTilHovedbok` and others) whose only tenant carrier is a scalar `eierOrganisasjonskode: String!` on a hand-written bean; the service's own mapper writes that value straight into `INSTITUSJONSNR_EIER`. Graphitron 9 routed these by argument name. Each sis-side workaround distorts the API or the Java: restructuring the input breaks the wire format of stable fields, a dummy jOOQ-record parameter changes eleven signatures just to satisfy the check, and `@globalData` would write tenant data on the default source. With the every-path rule each refused root also keeps about 594 child rejections alive in sis. That count is motivation, not the acceptance criterion; the sakila pair above is.

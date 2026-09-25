---
id: R976
title: "@service fields divine a tenant from their arguments and hand it down"
status: Backlog
bucket: bug
priority: 1
theme: runtime-connection
depends-on: []
created: 2026-09-25
last-updated: 2026-09-25
---

# @service fields divine a tenant from their arguments and hand it down

## Goal

Under database-per-tenant routing (a `<tenantColumn>` build, where a field's tenant value is divined from its arguments or parent row and selects that tenant's `DataSource`), a `@service` field whose arguments name a tenant runs its service method on that tenant's connection and hands the tenant down to the rows it returns, so tenant-scoped children below it classify as inheriting the tenant instead of being rejected. Today a service field divines nothing: the service method receives the default source's `DSLContext`, and every tenant-scoped field under a service-returned wrapper is rejected with `NoTenantBinding`. For Sikt's sis this is about 660 of the 686 remaining `NoTenantBinding` errors (67 `@service` root fields, mostly mutations), and a regression for the v9 to v10 port: v9 divined the tenant from the arguments of any fetcher, service or not.

## Observed (verified against the generator source, 2026-09-25)

- **No service arm in the direct-binding fold.** `TenantBindingIndex.Fold.directBinding` switches over the coordinate's `OperationMember`s with arms for `Condition`, `Lookup`, `Write.Insert`, `Write.Upsert` and `Write.Dml`, and `default -> { }`. A service field's member is `OperationMember.ServiceCall`, which falls to the default, so a service input carrying a tenant-column-bound field or a `@nodeId` whose key embeds the tenant column mints no slot.
- **Two root shapes, two outcomes.** `Fold.reachedTables` reads the field's own SQL reach off `domainReturnType()`. A `@service` root returning a tenant-scoped `@table` type directly has a `DomainReturnType.Record` reach, so `Fold.armOf` rejects it at the root ("no argument or input field maps to tenant column"). A service root returning a non-table wrapper (the sis shape: `aktiverFagpersoner(...): AktiverFagpersonerPayload`) has an empty reach and classifies `TenantBinding.Untenanted`, with no error at the root.
- **Nothing below inherits.** `Fold.edgeEstablishesOrTransmitsContext` returns true for an edge that is fan-marked, divines through `directBinding`, or is routable node dispatch; otherwise it defers to the parent's own context. A service root is none of these, so `tenantContextOf` fails every-path for the wrapper and everything under it, and each tenant-scoped child rejects, e.g. "'AktiverFagpersonerPayload.fagpersoner' reaches tenant-scoped table 'FAGPERSON' with no tenant binding in scope".
- **The service call runs on the default source.** The service fetcher in `TypeFetcherGenerator` splices `TenantDslEmitter.dslExpression(...)` into `ServiceMethodCallEmitter.emit`; for `Untenanted` that expression is `TenantConnections.dslDefault(env)`. So this is not only a build error: once the children are made to classify, a service mutation writing tenant data would run on the default database.
- **A service child under a bound parent misroutes too.** In `Fold.armOf` the `!anyTenant -> Untenanted` return sits ahead of the `tenantContextOf -> Inherited` check. A wrapper-returning `@service` child under a tenant-bound ancestor therefore also classifies `Untenanted` and gets `dslDefault`, though its ancestor divined a tenant. Only a `$session`-bound call escapes this, through the `bindsSessionHandle` rung that runs first.
- **The emitter assumes the arm is unreachable.** `TenantDslEmitter.dslExpression` throws `IllegalStateException` on `ArgumentBound`, documented as "unreachable by construction (service operations contribute no argument slots to the classifier)". The service success return (`returnSyncSuccess` / `returnSyncSuccessWrapped`) carries no `.localContext(_divinedTenant)` tail. Adding the classifier arm alone would turn a validation error into a generation-time crash.
- **Where the slot surface is.** A service argument is a `MappingEntry.FromArg` carrying a `ValueShape`. `ValueShape.JooqRecordInput` carries `CallSiteExtraction.JooqRecord(table, columnBindings, keyDecodes)`, whose `ColumnBinding` holds a resolved `ColumnRef` and whose `RecordKeyDecode` holds the decoded `targetColumns`, so the jOOQ-record input (the sis `argMapping: "records: input"` shape, pending confirmation) has both axes to read. A `ValueShape.Scalar` carries its `leafTransform`, so a `NodeIdDecodeKeys` leaf is reachable. `ValueShape.RecordInput` / `JavaBeanInput` field bindings carry an access path and a Java field name but no `ColumnRef`, so a bean-shaped service input has no column axis on this surface.
- **Fan-out is separate.** `Fold.fanOutArmOf` rejects `@tenantFanOut` combined with `@service` (the rung naming `DIR_SERVICE`). This item is the single-tenant routed case.

## v9 precedent

sis-app's homegrown `SetTenantIdInstrumentation` with `QueryInspector.identifiserInstitusjonsnrEier` worked the tenant out from the field's arguments (`eierOrganisasjonskode` / `eierInstitusjonsnummer`, `id`, `ids`, and every top-level `ID!` of `input`, requiring agreement across a batch) whatever the fetcher kind. `getDslContext(env)` then gave the service that tenant's connection, and the wrapper returned `DataFetcherResult` with `localContext(tenant)`, so every child inherited.

## Direction (for the Spec author to decide)

- **Divine.** Give `OperationMember.ServiceCall` a `directBinding` arm reading the service's argument surface in the slot vocabulary the other arms use: a column binding on the tenant column mints a `Raw` projection, a node-id decode whose target columns include the tenant column mints a `DecodedKeySlot` at that position, with the existing `divinedTenant` agreement fold across a batched (list) input. Open: whether a bean-shaped input reaches its column through the input type's `InputField` model rather than `ValueShape`, or is declined with its own detail.
- **Route and stamp.** Reroute the service-call emission sites from `dslExpression` to `TenantDslEmitter.resolve`, so an `ArgumentBound` service declares `_divinedTenant`, acquires through `dslFor(env, _divinedTenant)`, and returns with `localContextTail()`. The sites: the service fetcher and its reentry lift in `TypeFetcherGenerator`, and the service paths in `MultiTablePolymorphicEmitter` that splice `dslExpression`. `edgeEstablishesOrTransmitsContext` then holds for the service edge through `directBinding(...).divines()` with no new rung.
- **Inherit.** Decide whether a wrapper-returning service child under a bound ancestor should classify `Inherited` rather than `Untenanted`, as the `$session` rung already does. A service method's SQL is opaque to graphitron, so "reaches no table" says nothing about whether it touches tenant data; the safe reading may be that a service under a tenant context always runs on the inherited tenant.
- **Open:** a service root naming no tenant at all (reject, or stay `Untenanted` as today, which is right for services over global data and wrong for the rest); a service returning rows from several tenants (probably out of scope, like fan-out with service).

## Tests the Spec should require

- The sakila multitenant fixture has no `@service` over a tenant-scoped type, which is why this was not caught. Add a service root taking a tenant-bound input and returning a non-table wrapper whose field is a tenant-scoped `@table` type with a tenant-scoped child; the wrapper case is the sis shape and comes first. Add the direct `@table`-returning service root as well.
- Execution tier: the service method observes the divined tenant's connection (not the default source), and the child reads from the same tenant.
- A batched input whose elements name different tenants is refused before the service is called.

## Related

- R975 (`tenant-routed-mount-authorization`): once a service divines, its `dslFor` acquisition inherits that item's authorization gap; closing it is R975's, not this item's.
- The self-FK `@reference` writing the tenant column in an UPDATE's `SET` is filed separately.

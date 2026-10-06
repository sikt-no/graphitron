---
id: R989
title: "A root @service whose only tenant carrier is a scalar input member cannot route under database-per-tenant"
status: In Review
bucket: feature
theme: runtime-connection
depends-on: []
created: 2026-10-05
last-updated: 2026-10-06
---

# A root @service whose only tenant carrier is a scalar input member cannot route under database-per-tenant

## Goal

Under database-per-tenant routing, where each tenant's rows live in that tenant's own database and graphitron picks the database from what the operation names, a root `@service` can route on a plain scalar that the schema author marks as the tenant with a new `@tenant` directive, on an argument or on a member of a hand-written input bean or record. Today such a service is refused with "nothing in its arguments names a tenant" unless its arguments reach a jOOQ record field bound to the tenant column or a decoded `@nodeId`, and the common "create X at institution Y" mutation has neither: no row or id exists yet, and the only thing naming the tenant is a scalar like `eierOrganisasjonskode: String!`. With the marker the service runs on that tenant's connection, and the tenant is handed down to the tenant-scoped fields under its payload, exactly as an id-carrying service's is today.

```graphql
input OpprettStudentInput {
    eierOrganisasjonskode: String! @tenant
    fodselsnummer: String!
}
type Mutation {
    opprettStudenter(input: [OpprettStudentInput!]!): OpprettStudenterPayload
        @service(service: {className: "...StudentService", method: "opprettStudenter"})
}
```

The marker is the author's statement about an opaque service, of the same kind as `@globalData`: graphitron cannot see that the service writes the value into the tenant column. What graphitron does check is everything around that statement. The marked value must have the tenant key's Java type, every value in one call must name the same tenant (a batch naming two is refused before the service runs), and the routed tenant must be in the request's permitted tenant set, so a client can never reach a tenant it is not entitled to by sending a different value.

The pair that pins it, over the `TenantBindingClassificationTest` fixture (`film_id` as tenant column, so the tenant key type is `Integer`):

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

Before (and still, without the marker): `Mutation.rateByFilmId` is refused with `UnroutedServiceCall`, and `RateFilmsPayload.films` with "no ancestor established a tenant context". After, with `filmId: Int! @tenant`: `Mutation.rateByFilmId` is `ArgumentBound` with one slot named `in.filmId`, read as `NestedInput("in", ["filmId"])` with the `Raw` projection, `RateFilmsPayload.films` is `Inherited`, and there are no rejections.

The motivation is the sis Graphitron 10 migration (`<tenantColumn>INSTITUSJONSNR_EIER</tenantColumn>`, validated against trunk at `17196b8eb`). There the R978 refusal fires on 20 root `@service` fields. Nine were fixed on the sis side by typing a bean member as the node table's jOOQ record with `@nodeId`. The remaining eleven are create-mutations (`opprettFagpersonerGittFodselsnumre`, `opprettStudenter`, `registrerInnreisendeUtvekslinger`, `overfoerTilHovedbok` and others) whose only tenant carrier is the scalar above. Every workaround available today distorts the API or the Java: restructuring the input breaks the wire format of stable fields, a dummy jOOQ-record parameter changes eleven signatures, and `@globalData` would write tenant data to the default database. Graphitron 9 routed these by argument name.

## User documentation (first-client check)

The service paragraph of `docs/manual/how-to/tenant-scoping.adoc` § 2 that begins "A service's own SQL is opaque to graphitron" becomes:

> A service's own SQL is opaque to graphitron, so a root service that is handed a `DSLContext` or `$session` must name its tenant in its arguments, or the build refuses it rather than run it on the default source. Take the node table's jOOQ record with `@nodeId(typeName:)` instead of the encoded id as a `String`, and the call routes on the id. When no row exists yet and the tenant arrives as a plain value, as in a mutation that creates something at an institution, mark the argument or input field holding it with xref:../reference/directives/tenant.adoc[`@tenant`]:
>
> ```graphql
> input CreateStudentInput { institution: String! @tenant  name: String! }
> ```
>
> The call then routes on that value, which must have your tenant key's Java type. Like any routed value it is checked against the request's tenant set, and every value in one call, across a batch and beside any tenant-bearing ids, must name the same tenant. The marker is your statement that the service uses the value as its tenant, which graphitron cannot see. A service that works only on global tables, or only reads the mounted session handle, says so with xref:../reference/directives/globalData.adoc[`@globalData`], and runs on the default source. The build rejects `@globalData` wherever it can see tenant data: a tenant-scoped `@table` return, an argument that names a tenant (an id carrying it, or a `@tenant` value), or an argument typed by a tenant-scoped table's record. The reverse holds too: a service that takes a tenant's record runs on that tenant's database, so it should not take one if it only works on global tables.

A new reference page, `docs/manual/reference/directives/tenant.adoc`, listed in `docs/manual/reference/directives/index.adoc` beside `@globalData`:

> *`@tenant`* marks the argument or input field of a root xref:service.adoc[`@service`] field that carries the tenant, in a build with database-per-tenant routing (`<tenantColumn>`). The service runs on that tenant's connection, and the tenant-scoped fields under its result inherit it.
>
> ```graphql
> directive @tenant on ARGUMENT_DEFINITION | INPUT_FIELD_DEFINITION
> ```
>
> Put it on a scalar argument of the service field, or on a scalar field of an input object the service receives as a Java bean or record, at any depth and inside lists. The value is read as the client sent it, so its GraphQL type must arrive as your tenant key's Java type: `String` or `ID` for a `String` key, `Int` for an `Integer` key, a custom scalar whose Java type is the key type. Graphitron does not convert or normalise the value; one the request's tenant set does not contain fails the field with a client error.
>
> The build rejects the marker where it decides nothing: in a build without `<tenantColumn>`; anywhere the field it reaches is not a root `@service` (a query field binds the tenant through `@field(name:)` on the tenant column, and a child service inherits its parent's tenant); on a field bound into a jOOQ record (bind it to the tenant column with `@field(name:)` instead); beside `@nodeId` (the id already carries its tenant); on a field that is not a scalar; on a service whose result is a global `@table` type, which runs on the default source; and on a value whose type is not the tenant key's.

## Implementation

The slot machinery downstream of a minted slot needs nothing new: `TenantDslEmitter` already renders `TopLevelArg` and `NestedInput` reads with the `Raw` projection, the generated `divinedTenant` fold already flattens list levels and refuses disagreement, and `TenantConnections.entryFor` already checks the routed tenant against the request tenant set (R975). The work is the directive, getting the marked leaf into the fold as a model fact, the build-time ladder, and freeing `BoundSlot` from the table column a scalar does not have.

- **The directive.** `graphitron-model/.../schema/directives.graphqls` declares `directive @tenant on ARGUMENT_DEFINITION | INPUT_FIELD_DEFINITION` with a description condensed from the reference page above; `BuildContext` gains `DIR_TENANT` beside `DIR_GLOBAL_DATA`. No store relation: a directive with no arguments gets no `graphitron_` decode relation (see the DDL comment near the directive relations in `graphitron-model.sql`), and its applications are already in the `graphql_ast_*` capture.
- **The leaf's definition coordinate on `ValueShape.Scalar`.** `ValueShape.Scalar(TypeName javaType, ArgPath sdlPath, CallSiteExtraction leafTransform)` gains a `String definition`: the schema coordinate of the SDL slot the leaf reads, spelled through `SchemaCoordinateSyntax` (`RateByFilmIdInput.filmId` for an input field, `Mutation.rateById(filmId:)` for a top-level argument). `ArgPath` is a use-site path, not a key to the definition, which is why the coordinate is stamped where the definition is in hand rather than recovered from the path. For a bean or record member that is `InputBeanResolver`, which holds the `GraphQLInputObjectField` when it builds `CallSiteExtraction.FieldBinding`; `FieldBinding` carries the coordinate and `ServiceMethodCallWalker.fieldBindingShape` copies it onto the `Scalar`. For a top-level argument, and for an argument bound through an `argMapping` path into a nested input field (the `NestedInputField` unwrap in `ServiceMethodCallWalker.deriveValueShape`), the walker derives it from the service field's coordinate and the binding's path. Every `Scalar` construction site carries it, including the defensive `Direct` fallbacks. The coordinate is an identity fact, not a tenancy flag, so the fold joins it against the marker's applications and owns the whole marker ladder in one place.
- **Minting, in `TenantBindingIndex.Fold`.** The fold computes once, from the SDL, the set of `@tenant` application coordinates. `collectFromValueShape`'s `Scalar` arm passes `scalar.definition()` to `collectFromServiceLeaf`, whose `Direct` case, when the definition is marked, mints `collector.add(slotNameOf(path), new SlotAccess.Resolved(readOf(path), SlotProjection.Raw.INSTANCE))`, provided the type rung below passes, and records the `(use coordinate, definition)` pair as consumed. `ListOf` already recurses, so a list of beans and a `[String!] @tenant` list both read through the existing list-mapping `tenantSlot` walk. A marked definition reached through any other leaf (`NodeIdDecodeRecord`, `NodeIdDecodeKeys`, `EnumValueOf`) mints only what that leaf mints today and leaves the pair unconsumed for the sweep. `collectFromServiceCall`'s global-reach gate stays first, so a service returning a global `@table` type mints nothing from a marked value either. Because a marked slot is an ordinary slot, the `@globalData` ladder's "the arguments name a tenant (in.filmId)" rung and the `SlotCollector` agreement fold apply to it with no change.
- **The type rung.** The marked slot's wire Java type comes from `ScalarTypeResolver.coercionOutputType` over the definition's GraphQL scalar (looked up in the SDL by the coordinate, element type for a list), the one forward mapping `WireCoercionResolver` already uses, not from `Scalar.javaType` (the bean member's declared type, which `WireCoercionResolver.checkScalar` lets through unjudged for an unknown custom scalar). It must equal the boxed `TenantScopes.Configured.tenantType()`. Equality is required rather than convertibility because the generated `agreeOnTenant` compares candidates with `equals` before `divinedTenant` coerces anything, and a decoded `@nodeId` sibling's tenant-slot helper returns the typed key: an `ID`-typed marked value `"1"` beside a decoded `1` would be refused as a disagreement at request time. A mismatch, or a scalar whose output type is unknown, is a decline (`collector.decline(...)`) naming the scalar, its Java type and the key type, so a connection-binding service gets one `UnroutedServiceCall` carrying that text and nothing routes on a value the fold might misjudge. Graphitron normalises nothing: a value with leading zeros that the request tenant set holds without them is refused at request time, which fails closed.
- **The marker sweep.** Beside `forEachMarkerApplication`, which walks field definitions and so cannot see argument or input-field applications, a second sweep judges every use site of every application. For each output field coordinate it walks the field's argument input trees (cycle-safe; input types can recurse) and collects the `(use coordinate, definition)` pairs reaching a `@tenant` application; every pair the fold did not consume gets a `Rejection.directiveConflict(List.of("tenant"), reason)` at the definition coordinate, naming the use coordinate. Per use site rather than per definition, because one input type is consumed by many fields: a definition consumed at one root service and silently ignored at a child service or a query input would leave the second site running on a tenant the marked value never had to agree with. The reasons, first match wins: no `<tenantColumn>` configured (this arm alone runs in `rejectMarkersWithoutTenancy`'s single-tenant path); the use site is not a root `@service` (query and `@mutation` fields bind the tenant through `@field(name:)` on the tenant column; a child service inherits its parent's tenant); the definition is a non-scalar input field; the definition carries `@nodeId`; the definition is bound into a jOOQ record parameter (name `@field(name: "<tenantColumn>")`); the service's reach holds a global table; and the type rung declined. Where the fold also refuses the field, as it does for a connection-binding service whose only carrier is a mistyped marker, the author sees both messages, the sweep's at the definition and the fold's at the field; that is accepted rather than threading a suppression between the two.
- **Drop the table column from `BoundSlot`.** A scalar slot has no table, so it has no `ColumnRef`. `TenantBinding.BoundSlot(String slotName, ColumnRef column, SlotRead read, SlotProjection projection)` loses `column`. Its one main-code reader is `RoutineWriteCommands.acquisitionOf`, which passes the primary's column as `TenantAcquisition.ArgumentBound.keyColumn` so `TenantAcquisitionFragments.divinedKey` can type the `_divinedTenant` local. That is a second route to a run-wide fact already held once, on `TenantScopes.Configured.tenantType()` and enforced by the catalog-wide `TenantColumnTypeDisagreement` rejection, and `TenantDslEmitter` already reads it there. `ArgumentBound` loses `keyColumn`; `TenantRouting.Routed` gains `TypeName tenantKeyType`, filled in `RoutineWriteCommands` from `GraphitronSchema.requestTenantKeyType()`; `divinedKey` reads it. The javadoc on `TenantAcquisition.ArgumentBound` that justifies `keyColumn` and the one on `divinedKey` ("the bound column's own Java type") are rewritten to name `TenantRouting.Routed.tenantKeyType`. `SlotCollector.add` and every `collector.add` call site drop the column argument. A synthesised representative column was the alternative and is worse: it names a table the slot has nothing to do with.
- **Refusal text.** The fixes clause of `Rejection.AuthorError.UnroutedServiceCall.message()` gains the new carrier: "... or bind a jOOQ record field to '<col>', or mark the scalar argument or input field that holds the tenant with @tenant." `TenantBindingIndex`'s `@globalData` evidence rung text ("Take the node table's jOOQ record with @nodeId(typeName:), or bind a jOOQ record field to ...") gets the same clause.
- **Docs.** The manual paragraph and the reference page above; the `@globalData` description in `directives.graphqls` and `globalData.adoc` need no edit, since "fields whose arguments name a tenant" already covers a marked value.
- **Execution fixture.** `graphitron-sakila-example/src/main/resources/graphql/multitenant.graphqls` gains `RateByFilmIdInput { filmId: Int! @tenant  rating: Int }` and a `rateFilmsByFilmId(in: [RateByFilmIdInput!]!)` root `@service` field on `FilmRatingService` (in `graphitron-sakila-service`), taking a `DSLContext` and a hand-written bean, returning the existing payload shape with `ranOn` read from `current_database()`. A second field adds an `ID! @nodeId(typeName: "Film")` member beside the marked one for the agreement case.

## Tests

Classification tier, in `TenantBindingClassificationTest` beside the R976 service cases, over `SERVICE_TYPES` with new `TenantServiceStub` methods and a `RateByFilmIdBean` (plus a Java record twin):

- The pair: `anUnmarkedScalarMemberHandedAConnectionRejectsAtTheRoot` (the before half, `UnroutedServiceCall` naming `@tenant` in its fixes) and `aTenantMarkedScalarMemberDivinesRawAndItsChildrenInherit` (the after half exactly as the Goal states it).
- A marked top-level argument, `rateById(filmId: Int! @tenant)`: slot `filmId`, `TopLevelArg`, `Raw`.
- A marked Java record member, and a member of a nested grouping input: slot path includes every segment.
- A list of beans, `in: [RateByFilmIdInput!]!`: `NestedInput("in", ["filmId"])`, one slot.
- A marked scalar beside a `@nodeId` member: two slots, `in.filmId` (`Raw`) then `in.film` (`DecodedKeySlot`), in declaration order.
- `@globalData` over a marked argument: the "the arguments name a tenant (in.filmId), which contradicts @globalData" rung.
- The type rung: `filmId: String! @tenant` and `filmId: ID! @tenant` against the `Integer` key each yield one `UnroutedServiceCall` carrying the decline text, plus the sweep's rejection at the definition.
- The sweep, one case per reason: a single-tenant build; the marker on a query `@table` field's argument; on a child service's argument; on an input-object-typed field; beside `@nodeId`; on a jOOQ record member; on a service returning a global `@table` type. Each asserts the `directiveConflict` at the definition coordinate naming the use site.
- One input type reached by a root service and by a query field: the root service is `ArgumentBound`, and the query use site is rejected, which is the per-use-site reason for the sweep.

Pipeline tier: the tests already pinning `TenantAcquisition.ArgumentBound` and the routine-write `divinedKey` declaration keep passing with the key type read from `TenantRouting.Routed`; assertions on `BoundSlot.column().sqlName()` are deleted with the component, since every minted column matched the configured name by construction.

Execution tier, in `TenantDivinedRoutingExecutionTest` over the fixture above:

- `service_routesOnATenantMarkedScalar_andHandsItDownToTheReturnedRows`: `filmId: 1` runs on `tenant_1`, its films and their inventories resolve, and `tenant_2` is never opened.
- `service_batchOfMarkedScalarsNamingTwoTenants_isRefusedBeforeTheServiceRuns`: zero acquisitions, "Tenant bindings disagree".
- `service_markedScalarDisagreeingWithASiblingNodeId_isRefused`: `filmId: 1` beside the encoded id of film 2, refused with zero acquisitions; the agreeing pair routes.
- `service_markedScalarOutsideTheRequestTenantSet_isRefused`: a request permitted only tenant 2 sending `filmId: 1` fails with the client error, no connection opened.

Completeness rests on the classification pair, the four execution cases, and the multitenant example building with the new fixture.

## Retired vocabulary

`BoundSlot.column`, `TenantAcquisition.ArgumentBound.keyColumn`, and the javadoc phrase "the bound column's own Java type". The retirement sweep greps for `keyColumn` within `command/`, `plan/` and `render/`, and for `.column()` on a `BoundSlot` across the tenant tests.

## Implementation notes

Landed at `cdd2716`. The plan above held; where the code differs, it is here.

- `ValueShape.FieldBinding` carries `definition` as well as `ValueShape.Scalar`. `TypeFetcherGenerator` rebuilds each `CallSiteExtraction.FieldBinding` from the value shape when it collects the bean helpers, and the round trip has to keep the coordinate.
- `ServiceMethodCallWalker.walk` now takes the service field's parent type name beside its definition, which the top-level argument coordinate needs. An argument mapping can project past the SDL (`in.inventoryId.inventry_id`, a key column of a decoded id); the walk stops at the deepest declared slot and stamps that, rather than throwing before the resolver reports the mapping's own rejection (`ArgmappingProjectionRejectionPipelineTest` caught the first version).
- `TenantRouting.Routed` carries `tenantKeyTypeName`, the boxed key type's fully qualified name, not a `TypeName`: the command package may not import the emit library (`PackageImportDirectionTest`), and its records carry Java types as names, as `CatalogColumn.javaTypeName` does. The renderer turns it into a `ClassName`.
- The sweep reads the direct binding of every root `@service` use site itself before judging, since `armOf` can leave a coordinate (a marker ladder, a cross-scope reach) before reading it. It has two reasons beyond the plan's list: a root `@service` field that failed classification ("did not classify (see its own error)"), and a fallback for a use the service's parameters never read (a bean with no member for the field, or a top-level argument the method does not take).
- Registries that enumerate directives: `SchemaDirectiveRegistryTest` pins `tenant`, `supported-directives.adoc` is regenerated, and the input-value site's javadoc in `GraphitronInputValueEntries` and its DDL comment now count `@tenant` among the argumentless directives. The `UnroutedServiceCall` paragraph in `docs/architecture/explanation/typed-rejection.adoc` names the new fix.
- Test names. The pair is `anUnmarkedScalarMemberHandedAConnectionRejectsAtTheRoot` and `aTenantMarkedScalarMemberDivinesRawAndItsChildrenInherit`. The top-level argument case uses `rateByFilmIdArgument`, since `rateById` already names the decoded-record stub. The type rung's `String!` and `ID!` cases share `aMarkedValueNotOfTheTenantKeyTypeDeclinesAtTheFieldAndTheDefinition`. The sweep cases are `aMarkerInASingleTenantBuildRejects`, `aMarkerOnAQueryFieldsArgumentRejectsNamingTheUseSite`, `aMarkerOnAChildServicesArgumentRejects`, `aMarkerOnAnInputObjectTypedFieldRejects`, `aMarkerBesideNodeIdRejects`, `aMarkerOnAJooqRecordMemberRejects` and `aMarkerOnAServiceReturningAGlobalTableRejects`; the per-use-site case is `oneInputTypeRoutesItsRootServiceAndRejectsAtAQueryUseSite`. The execution fixture's fields are `rateFilmsByFilmId` and `rateFilmsByFilmIdAndFilm`, and the four execution cases carry the names the Tests section gives.

## Other solutions we've considered

- **Reuse `@field(name: "<tenantColumn>")` on the bean member.** On a bean or record member `@field(name:)` names the Java member it binds (`InputBeanResolver.bindingKey`), and on a top-level `@service` argument it is ignored; giving it a second meaning that depends on the value naming the configured column would make one directive mean two things by coincidence of spelling. Where it does name a column, on a jOOQ record member, it already routes, which is why the marker is rejected there.
- **Route by argument name, as Graphitron 9 did.** A naming convention is invisible in the schema and fires on any field that happens to share the column's name; the marker is a statement the author makes and the build can check.
- **A boolean `tenantCarrier` on `ValueShape.Scalar`, or a wrapping `CallSiteExtraction` arm.** The boolean spreads the marker's rejection ladder across `InputBeanResolver`, `ServiceCatalog` and the fold, and loses the coordinate the per-use-site sweep keys on. A wrapping extraction arm fuses the tenant role onto the extraction-strategy axis, so the permit set becomes their product. The definition coordinate is the plain fact both need.
- **Convert the marked value to the key type** (`String` to `Integer`, normalising leading zeros). `divinedTenant` already parses a `String` for a numeric key, but the agreement fold compares first, so a converting marker would need the fold reworked to coerce before comparing, which changes every existing slot's runtime path. Exact type equality covers the reported case (a `String` key with `String!` values) and the sakila fixture (`Int` against `Integer`); conversion can be its own item if a schema needs it.

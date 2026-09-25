---
id: R976
title: "@service fields divine a tenant from their arguments and hand it down"
status: Spec
bucket: bug
priority: 1
theme: runtime-connection
depends-on: []
created: 2026-09-25
last-updated: 2026-09-25
---

# @service fields divine a tenant from their arguments and hand it down

## Goal

Under database-per-tenant routing (a `<tenantColumn>` build, where each tenant's data lives in its own database and a field's tenant value, read from its arguments or inherited from a parent, picks which `DataSource` serves it), a `@service` field whose arguments name the tenant runs the service method on that tenant's connection and hands the tenant down to the rows it returns. Tenant-scoped fields below it then inherit the tenant instead of being rejected. A `@service` field nested under a field that already named a tenant runs on that tenant's connection instead of the default database. Today neither holds: every service field is handed the default source's `DSLContext`, and every tenant-scoped field under a service-returned wrapper is rejected with `NoTenantBinding`.

The shape this item makes work, in the sakila multitenant fixture's terms (`<tenantColumn>film_id</tenantColumn>`, `Film @node(keyColumns: ["film_id"])`):

```graphql
input FilmRatingInput {
    film: ID! @nodeId(typeName: "Film")   # bound to a FilmRecord member of the service's bean
    rating: String
}
type RateFilmsPayload {
    films: [Film!]!                        # rows the service returns
}
type Mutation {
    rateFilms(in: [FilmRatingInput!]!): RateFilmsPayload @service(service: {
        className: "...FilmRatingService", method: "rateFilms", argMapping: "ratings: in"})
}
```

`rateFilms` decodes the tenant from the `film` ids, refuses a batch naming two tenants before calling the service, calls `rateFilms` with that tenant's `DSLContext`, and `RateFilmsPayload.films` and `Film.inventories` below it inherit the tenant. Today `rateFilms` runs on the default database and `Film.inventories` is rejected.

For Sikt's sis this gives the 23 `@service` methods whose parameters are jOOQ records a surface the build can read the tenant from (they route where their `@nodeId` key decodes or `@field` bindings land on the tenant column), and routes every connection-binding service nested under a tenant-bound parent. The other 40 sis service methods take the encoded node id as a plain `String`, which names no node type the build can read. sis is migrating them to the node table's jOOQ record with `@nodeId(typeName:)` (a bean member, or a `List<XRecord>` parameter), which puts them on this item's decode path; R978 (`service-undecoded-node-id-tenant`) owns the build-time refusal for a service that still names no tenant.

## Observed (verified against the generator source, 2026-09-25)

- **No service arm in the direct-binding fold.** `TenantBindingIndex.Fold.directBinding` (the per-field read of which argument slots name the tenant) switches over the coordinate's `OperationMember`s (the operation's parts: a condition, a lookup, a write, a service call) with arms for `Condition`, `Lookup`, `Write.Insert`, `Write.Upsert` and `Write.Dml`, and `default -> { }`. A service field's member is `OperationMember.ServiceCall`, which falls to the default, so no service argument ever mints a slot.
- **Two root shapes, two outcomes.** `Fold.reachedTables` reads the field's own SQL reach off `domainReturnType()`. A `@service` root returning a tenant-scoped `@table` type directly reaches that table, so `Fold.armOf` rejects it at the root ("no argument or input field maps to tenant column"). A service root returning a non-table wrapper (the sis shape: `aktiverFagpersoner(...): AktiverFagpersonerPayload`) reaches nothing and classifies `TenantBinding.Untenanted`, with no error at the root.
- **Nothing below inherits.** `Fold.edgeEstablishesOrTransmitsContext` returns true for an edge that is fan-marked, divines through `directBinding`, or is routable node dispatch; otherwise it defers to the parent's own context. A service root is none of these, so every tenant-scoped child under the wrapper rejects, e.g. "'AktiverFagpersonerPayload.fagpersoner' reaches tenant-scoped table 'FAGPERSON' with no tenant binding in scope".
- **The service call runs on the default source.** `TypeFetcherGenerator.buildServiceFetcherCommon` (the one fetcher builder every root `@service` of the four table/record query and mutation variants goes through) splices `TenantDslEmitter.dslExpression(...)` into `ServiceMethodCallEmitter.emit`; for `Untenanted` that expression is `TenantConnections.dslDefault(env)`. Once the children classify, a service mutation writing tenant data would run on the default database.
- **A service child under a bound parent misroutes too.** In `Fold.armOf` the `!anyTenant -> Untenanted` return sits ahead of the `tenantContextOf -> Inherited` check. A `@service` child that reaches no table classifies `Untenanted` under a tenant-bound ancestor and gets `dslDefault`. Only a `$session`-bound call escapes, through the `bindsSessionHandle` rung that runs first.
- **The emitter assumes the arm is unreachable.** `TenantDslEmitter.dslExpression` throws `IllegalStateException` on `ArgumentBound` ("service operations contribute no argument slots to the classifier"), and the service success returns (`returnSyncSuccess`, `returnSyncSuccessWrapped`) carry no `.localContext(_divinedTenant)` tail. Child services already resolve through `TenantDslEmitter.resolve` (the `ChildField.ServiceTableField` / `ServiceRecordField` arms of `TypeFetcherGenerator`), so they are not affected by this.
- **Where the slot surface is.** A root service argument is a `MappingEntry.FromArg` carrying a `ValueShape` (the tree describing how the wire value is built into the Java parameter), whose leaves carry their `ArgPath` into the arguments:
  - **jOOQ record parameter.** `ValueShape.JooqRecordInput` carries `CallSiteExtraction.JooqRecord(table, columnBindings, keyDecodes)`. A `ColumnBinding` holds a resolved `ColumnRef` and its SDL paths; a `RecordKeyDecode` holds the `typeId` and the decoded `targetColumns`.
  - **Bean parameter with a record member.** A bean member typed as a node table's jOOQ record with `@nodeId` gets a `CallSiteExtraction.NodeIdDecodeRecord(encoderClass, typeName, typeId, keyColumns, table, nonNull)` leaf (`ServiceMethodCallWalker.fieldBindingShape`).
  - **Top-level `@nodeId` argument.** `ServiceCatalog.nodeIdSlotExtraction` gives a record-typed slot `NodeIdDecodeRecord` and any other slot `ThrowOnMismatch`, which is a `NodeIdDecodeKeys` carrying its `HelperRef.Decode`.
  - **Undecoded `ID`.** A `String` bean member or parameter bound to an `ID` without `@nodeId` gets a `Direct` leaf, and `InputBeanResolver.singleValuedMemberDeferral` refuses `@nodeId` on a non-record bean member (the consumer never receives the wire format). The build has no node type to read here.
- **The runtime reads already cover lists.** `TenantConnections.tenantSlot` maps the remaining key path over a list-shaped level, the decode helper `CompositeDecodeHelperRegistry.registerTenantSlot` mints flattens a list of ids, and `divinedTenant` flattens, requires agreement, and fails an absent value before any SQL.
- **Fan-out is separate.** `Fold.fanOutArmOf` rejects `@tenantFanOut` with `@service`; this item is the single-tenant routed case.

## Census of sis service parameter shapes (from the sis session, 2026-09-25)

Of the 63 distinct `@service` methods in the sis schema, 23 take jOOQ `TableRecord`s (e.g. `EndreKullService.endreKull(List<KullRecord>)`), 34 take hand-written beans whose `ID` fields map to `String` members holding the encoded id (e.g. `FagpersonService.aktiverFagpersoner(List<AktiverFagpersonerRecord>)`), and 6 take bare `List<String> ids` (e.g. `UndervisningsaktivitetService.godkjennForPublisering`). v9 routed all 63 by inspecting every `ID` argument at runtime (`SetTenantIdInstrumentation`, `QueryInspector.identifiserInstitusjonsnrEier`) and stamped the tenant as `localContext` on the returned result.

## Design

### Divine: a `ServiceCall` arm in `directBinding`

`directBinding` gains `case OperationMember.ServiceCall s`, reading `ServiceCallCarrier.StructuredCall` (root services) and nothing for `ServiceCallCarrier.ReflectedMethod`. Child services carry a reflected `MethodRef` rather than a `ServiceMethodCall`, and they get their tenant from the parent (the next section), so their own arguments divining nothing is deliberate. The arm walks each `MappingEntry.FromArg`'s `ValueShape`, descending `ListOf`, `RecordInput` and `JavaBeanInput`, and mints slots in the vocabulary the other arms use:

- **`JooqRecordInput`:** each `ColumnBinding` whose column is the tenant column mints `SlotProjection.Raw` (the wire value is the tenant) at each of its paths. Each `RecordKeyDecode` whose `targetColumns` include the tenant column mints `SlotProjection.DecodedKeySlot` at that column's index, with the `HelperRef.Decode` of the node type whose `typeId` it names (read off the fold's `types` map, whose `GraphitronType.NodeType.decodeMethod()` is the helper every other decode of that type uses).
- **`NodeIdDecodeRecord` leaf:** a `DecodedKeySlot` at the tenant column's index in `keyColumns`, with `NodeType.decodeMethod()` of `typeName`.
- **`NodeIdDecodeKeys` leaf (including `ThrowOnMismatch`):** through the existing `accessOf`, which already maps this leaf to a `DecodedKeySlot`, at the tenant column's index in the decode's `outputColumnShape`.
- **Every other leaf** (`Direct`, `EnumValueOf`, `JooqConvert`) mints nothing. A `Direct` leaf has no column, so there is nothing to test against the tenant column.

The slot's read comes from the leaf's `ArgPath` (plus a `ColumnBinding` / `RecordKeyDecode` path relative to its record): a one-segment path is `SlotRead.TopLevelArg`, a longer one `SlotRead.NestedInput(head, rest)`. The slot name is the argument name for a one-segment path, which is what `TopLevelArg` reads by, and the dot-joined SDL path for a nested one. `SlotCollector` dedupes by name, so a name that stopped at the last segment would silently merge two different `id` fields at different paths into one slot and drop the second from the agreement fold. A list input needs nothing further, since the runtime reads above flatten it.

**Every tenant-bearing slot in a row folds, and absent optional ids are skipped.** An input may carry several tenant-bearing ids per row: sis's `endreKull` input has `id: ID! @nodeId(typeName: "Kull")` beside the nullable `bibliotekId` and `lmsRomMalId`, and `leggTilBegrensninger` has `studieoppbygningsdelId` beside a nullable `fagId`. Each mints its own slot with its own node type's decode helper, and `divinedTenant` folds them all through `agreeOnTenant`, which skips a null candidate and refuses two non-null values that differ. So an omitted optional id neither routes nor disagrees, and an optional id from another tenant refuses the call. Only when every slot is null does the fold report the binding absent, which a non-null `@nodeId` in the row rules out.

With a slot minted, `armOf` returns `ArgumentBound` for the service field ahead of every reach-derived rung, as for any other divining field, and `edgeEstablishesOrTransmitsContext` holds for the service edge through `directBinding(...).divines()` with no new rung.

### Route and stamp: service sites go through `TenantDslEmitter.resolve`

- `ServiceMethodCallEmitter.emit` takes a DSL *expression* and declares `DSLContext dsl = <expression>` itself when the call binds one (`declaresDslLocal`), so the service sites must not paste a `resolve` declaration that declares `dsl` too. They use `TenantDslEmitter.handDownOnly`, which exists for fetchers whose routed `dsl` is declared elsewhere: for `ArgumentBound` its declaration is the `_divinedTenant` local alone, and for every other arm it is empty.
- `TypeFetcherGenerator.buildServiceFetcherCommon` takes the `OutputField` carrier, pastes `handDownOnly(...).declaration()` ahead of the call, and hands `emit` the expression `TenantConnections.dslFor(env, _divinedTenant)` when the resolution `handsDownTenant()`, else `dslExpression(...)` as today (`dslDefault` for `Untenanted`, the handed-down read for `Inherited`). Both success returns, `returnSyncSuccess` and `returnSyncSuccessWrapped`, append `localContextTail()`. The divination runs before the service is called, so a disagreeing batch never reaches the service.
- `emitServiceReentryLift` (the table-returning root's re-projection) already declares its companion through `resolve`; where it declares its own `dsl` local it uses the same expression choice as above.
- `MultiTablePolymorphicEmitter.buildServiceMainFetcher` and `buildServiceTableInterfaceFetcher` (the polymorphic service roots) move the same way, including their stamped returns.
- `dslExpression` keeps its remaining callers, which are the child polymorphic sites (`buildScalarPerParentFetcher`, `buildBatchedConnectionRowsMethod`, `buildBatchedListRowsMethod`). Its `ArgumentBound` throw stays as the emitter's shape check for those callers, and its javadoc stops claiming the service case as the reason. Whether those child sites can carry an `ArgumentBound` coordinate (a child polymorphic field with a tenant-bound filter argument) is a separate question this item does not change.

### Inherit: one "binds a connection" rung, keyed on the call, not on `@service`

A method call whose parameters receive a connection-bound value, a `DSLContext` or the `$session` handle, runs on whichever connection it is handed, so which connection serves it is semantics, not plumbing. The existing `bindsSessionHandle` rung encodes half of that question. It is widened to `bindsConnection(OutputField)`, true when a `MethodBackedField` has a `ParamSource.DslContext` or `ParamSource.SessionHandle` parameter, or a `ServiceField` carrier has a `MappingEntry.FromDsl` or `MappingEntry.FromSessionHandle` entry. The rung keeps its place ahead of `!anyTenant -> Untenanted`: under a tenant context such a call classifies `Inherited` and routes through the handed-down tenant. One predicate rather than a second rung, so the two cannot drift. A call binding neither never touches a connection, so `Untenanted` stays right for it.

### What an empty reach means, and the gap this item leaves

A service's SQL is opaque to graphitron, so an empty reach is not evidence that it touches only global data. The rule this item applies is: route on a tenant whenever one is known (its own arguments, or its parent's context), and only otherwise fall to the default source. A root service that binds a connection and names no tenant therefore stays `Untenanted` and keeps running on the default source, as today. That is an accepted gap, not a verdict. It is where sis's 40 undecoded-id services sit until their migration to jOOQ-record parameters lands, and a service writing tenant data through it writes to the default database. R978 owns turning it into a build-time refusal; this item does not change its behaviour, so nothing that runs today starts failing here.

## Implementation

- `TenantBindingIndex.Fold`: the `ServiceCall` arm in `directBinding` and its `ValueShape` walk; `bindsSessionHandle` widened to `bindsConnection`.
- `TypeFetcherGenerator`: `buildServiceFetcherCommon` and `emitServiceReentryLift` resolve and stamp as above.
- `MultiTablePolymorphicEmitter`: `buildServiceMainFetcher`, `buildServiceTableInterfaceFetcher`.
- `TenantDslEmitter`: `dslExpression`'s javadoc and its `ArgumentBound` message restated for its remaining child callers; `handDownOnly` gains the service sites as callers.
- `graphitron-sakila-service`: a `FilmRatingService` (name illustrative) with a bean method taking a `FilmRecord` member, a method taking `List<FilmActorRecord>` whose input carries the composite `FilmActor` id (the tenant at key position 1), and a child service binding a `DSLContext`. Each returns what `current_database()` reports on the connection it was handed, so a test can see which database served it.
- `graphitron-sakila-example`'s `multitenant.graphqls`: the fields below, one per shape, in the file's "one field per arm" style.

## Tests

- **Classification** (`TenantBindingClassificationTest`): a wrapper-returning service root with a `@nodeId` record member classifies `ArgumentBound` with a `DecodedKeySlot` at the tenant's key position, and the wrapper's tenant-scoped child classifies `Inherited` with no rejection. The same for a jOOQ-record parameter binding the tenant column through `@field` (a `Raw` slot), and for a table-returning service root. An input with two tenant-bearing `@nodeId` fields of different node types mints two slots, and two fields both named `id` at different paths mint two slots rather than one. A service root with only a `Direct` `ID` stays `Untenanted` and its child still rejects, pinning the gap R978 owns. A connection-binding child service under a bound parent classifies `Inherited`; one binding no connection stays `Untenanted`.
- **Pipeline** (generated source): the service fetcher declares `_divinedTenant` before the service call, declares `dsl` exactly once as `dslFor(env, _divinedTenant)` for a service that binds a `DSLContext`, and its success return carries `.localContext(_divinedTenant)`, on both the plain and the error-channel return. A divining service that binds no `DSLContext` still stamps.
- **Compile tier:** the multitenant fixture compiles at Java 17 with the new fields.
- **Execution** (`TenantDivinedRoutingExecutionTest`): `rateFilms` with ids from tenant 1 reports `tenant_1` as the database it ran on, and `films { inventories }` returns tenant 1's rows; a batch mixing tenant 1 and tenant 2 ids is refused before the service is called (its counting `DataSource`s record no acquisition); a row whose non-null id is tenant 1 and whose nullable second id is omitted routes to tenant 1, and the same row with the second id from tenant 2 is refused; the child service under a bound `Film` reports the parent's tenant database, not the default.

## Other solutions we've considered

- **Decode every undecoded `ID` by its embedded type id at runtime**, as v9 did: `NodeIdEncoder.peekTypeId`, then the per-type key positions node dispatch already keeps (`nodePositions`). It would have routed the 40 sis services with no schema change. It was not chosen because the verdict would rest on evidence that arrives per request: the build would assert `ArgumentBound`, and every child's `Inherited` under it, on a slot it cannot prove names a tenant, and a service whose `ID` arguments name global or foreign ids would classify bound and fail at runtime. It would also splice the per-row node-dispatch family into `ArgumentBound`'s fixed-decode shape. sis chose to migrate those services to jOOQ-record parameters instead.
- **A routing-only marker, or `@nodeId` on a `String` member that keeps handing over the encoded id.** Both are the "this is a node id" marker the principles name as a smell, and the second breaks the invariant `singleValuedMemberDeferral` enforces. Lifting that deferral would not help sis either: a tenant-scoped key that embeds the tenant is composite there, and a one-value slot refuses a composite key.
- **Reject every connection-binding root service that names no tenant.** Honest about the gap, but it needs a way for a service over global data to declare itself global, which this item does not design. R978 owns it.

## Related

- R978 (`service-undecoded-node-id-tenant`): the build-time refusal for a connection-binding root service that names no tenant, pointing at the jOOQ-record migration sis is making.
- R975 (`tenant-routed-mount-authorization`): once a service divines, its `dslFor` acquisition inherits that item's authorization gap; closing it is R975's.
- R977 (`update-set-self-fk-tenant-agreement`): independent, from the same sis port.

## Reviewer findings

### Round 1 (2026-09-25, Spec -> Ready, reviewer session 01Dh9nfVNoZTmLep3d6fUb8E)

Verdict: withhold. Two findings on question two, both small. Question one passes. In my own words: under a `<tenantColumn>` build, a root `@service` whose arguments carry a tenant-scoped node id (through a jOOQ record parameter, a `@nodeId` record bean member, or a decoded `@nodeId` argument) gets that tenant's connection and hands the tenant down, so the tenant-scoped fields under its payload resolve instead of failing the build. A child service that binds a connection under a tenant-bound parent now runs on the parent's tenant instead of the default database. Every premise in Observed holds against the tree, and every symbol the spec names exists as named. The plan extends the existing slot vocabulary and emitter seams rather than adding new ones.

1. **The widened inherit rung misses the instance-holder child service (question 2).** The spec defines `bindsConnection` for a `MethodBackedField` as "has a `ParamSource.DslContext` or `ParamSource.SessionHandle` parameter", which reads `method().params()` only. A child service whose method is non-static gets `MethodRef.CallShape.InstanceWithDslHolder` from `ServiceCatalog` (line 508). Its `DSLContext` comes in through the holder constructor's `ctorParams`, not through the method's own parameters. So `new FooService(dsl).bar(...)`, record-returning and reaching no table under a tenant-bound parent, would still classify `Untenanted` and run on `dslDefault`. That is the silent wrong-database routing this item exists to close. The root branch does not have this problem, because it already folds `ServiceMethodCall.Instance.ctorArgs()` in. The tree has one home for this fork, `MethodRef.CallShape.needsDsl()`, whose javadoc says every emit site reads it "rather than re-stating the disjunction". To satisfy the finding, define the `MethodBackedField` half through `needsDsl()` (plus the `SessionHandle` parameter check), or say why the holder arm is excluded. Then add a holder-shaped child service to the classification and execution tests beside the parameter-shaped one.

2. **The leaf enumeration omits `NodeIdDecodePolymorphicRecord`, which reaches this walk (question 2).** The `ValueShape` walk lists the leaves that mint slots and says "every other leaf (`Direct`, `EnumValueOf`, `JooqConvert`) mints nothing" because "a `Direct` leaf has no column". But `ServiceMethodCallWalker.isLeaf` and `fieldBindingShape` carry `CallSiteExtraction.NodeIdDecodePolymorphicRecord` as a `Scalar` leaf. `ServiceCatalog.nodeIdSlotExtraction` also mints it for a top-level `@nodeId` slot that names a multitable interface or union. So a polymorphic record decode can reach the service arm today. It does have columns, one key tuple per `PolymorphicCandidate`, so the stated reason does not cover it. It is also the service-side analogue of `PruneOnMismatch`, which `accessOf` explicitly declines ("no single decode to route the statement on"). The implementer's switch has to pick an answer: mint nothing (the leaf joins the accepted gap), decline with its own text as `PruneOnMismatch` does, or route per candidate. The spec should make that choice, and a classification test should pin it.

Non-blocking:

- "Nothing that runs today starts failing" is scoped to the undecoded-id gap, and it holds there. The newly divining services are a deliberate change of behaviour, though. A wrapper-returning service that takes a tenant-scoped record id and today runs, `Untenanted`, on the default source (for example one that writes only global tables) will route to the tenant database once this lands. That follows from the "route whenever a tenant is known" rule, and I agree with the rule. The Goal or the gap section could say so, so that a consumer reading the changelog is not surprised.
- `handDownOnly` takes an `OutputField`, but the two `MultiTablePolymorphicEmitter` service roots carry only `fieldName`. They will need the carrier threaded through, or a by-name variant like `resolveByName`. This is mechanical and does not affect the verdict.

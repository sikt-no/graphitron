---
id: R1035
title: "An opted-in federation entity routes to the request default tenant when its key names none"
status: Spec
bucket: feature
priority: 2
theme: runtime-connection
depends-on: []
created: 2026-10-14
last-updated: 2026-10-14
---

# An opted-in federation entity routes to the request default tenant when its key names none

## Goal

In a database-per-tenant build (each tenant's rows live in a database of their own, and graphitron picks the database per statement), a federation entity type whose `@key` cannot carry the tenant column can be marked `@defaultTenant`. Every representation of that type that `_entities` receives is then resolved on the request's *default tenant* (the one tenant a request may name for itself through `RequestTenants.withDefault(set, tenant)`). The lookup matches only rows whose tenant column equals that tenant, so the representation resolves exactly as if its key had carried the default tenant. Fields below the resolved entity read from the same tenant. When the request names no default tenant, every representation of a marked type answers `null` at its position, with no error, and the rest of the batch resolves normally. Today such a type is a build error ("its federation key alternative #0 does not carry tenant column"). The marker is opt-in per type, so the default tenant never serves a tenant-scoped read the schema did not ask for. This gives sis's `FeideBruker` the behaviour it had in Graphitron 9.

```graphql
# Today: build error, the key cannot name the tenant (PERSON.INSTITUSJONSNR_EIER).
type FeideBruker @table(name: "PERSON") @key(fields: "brukernavn") {
  brukernavn: String
  personProfil: PersonProfil @splitQuery
}

# With this item: each representation resolves on the request's default tenant,
# matching only a PERSON row that tenant owns; personProfil reads the same tenant.
type FeideBruker @table(name: "PERSON") @key(fields: "brukernavn") @defaultTenant {
  brukernavn: String
  personProfil: PersonProfil @splitQuery
}
```

## Motivating case

A Feide username is unique only within an institution, and the router's representation is only `{__typename, brukernavn}`, so the key cannot name the tenant (`INSTITUSJONSNR_EIER`). sis decided on 2026-10-14 to keep v9's rule, which was hand-written sis code there:

- `_entities` was routed to the caller's home institution: a JWT claim gave `UserInfo.getHjemmeinstitusjonsnr()`, with leading zeros stripped and `null` unless the caller has FS access there. v9 applied it only when every representation in the batch was a `FeideBruker`. Routing per type removes that limit, which sis accepts.
- A username with no `PERSON` row whose `INSTITUSJONSNR_EIER` equals the home institution resolved to `null` at its position, and the other positions kept their index.
- A caller with no home institution (machine users) got `null` at every `FeideBruker` position. This was deliberate: no guessing and no cross-institution lookups.

sis will set the default tenant from the home institution in `motor.tenantsFor(userInfo)` and port v9's `FeideBrukerEntityIT`. sis expects 0 author errors on validate once R1027, R1032 and this item land.

## User documentation (first-client check)

The reference page `docs/manual/reference/directives/defaultTenant.adoc`, drafted:

> = @defaultTenant
>
> Resolves a federation entity on the request's default tenant when its key cannot name a tenant.
>
> ```graphql
> directive @defaultTenant on OBJECT
> ```
>
> In a database-per-tenant build, `_entities` normally reads the tenant out of each representation: a key that includes the tenant column names the database to look in. Some keys cannot include it. A username that is unique only within one institution, sent by a router that knows nothing of institutions, is the typical case, and such a type is a build error unless you mark it:
>
> ```graphql
> type FeideBruker @table(name: "PERSON") @key(fields: "brukernavn") @defaultTenant {
>   brukernavn: String
>   personProfil: PersonProfil @splitQuery
> }
> ```
>
> Each representation of the type is then looked up on the request's default tenant (see xref:../../how-to/tenant-scoping.adoc[Tenant scoping], "Reading global tables from a tenant database", for how a request names one). The lookup matches only rows whose tenant column holds the default tenant, so `{__typename: "FeideBruker", brukernavn: "ola"}` resolves to Ola's row in the default tenant, or to `null` when that tenant has no such row. Fields below the entity read from the same tenant, as they do below an entity whose key names its tenant.
>
> A request that names no default tenant answers every representation of a marked type with `null`, without an error, and resolves the rest of the batch normally; no tenant connection is opened for the type. A request whose default tenant this runtime has no `DataSource` for fails before any field runs, as every request naming such a default does.
>
> The marker applies to each resolvable `@key` of the type that does not include the tenant column; a key that does include it keeps routing by its own value. It does not apply to node ids: on a `@node` type, `node`, `nodes` and the id key of `_entities` still route by the tenant inside the id, so the node key must still include the tenant column. It is rejected at build time where it decides nothing: in a build without `<tenantColumn>`, on a type whose table has no tenant column (`_entities` already reads such types from the default tenant), on a type with no resolvable key, and on a type whose every resolvable `@key` includes the tenant column.

`tenant-scoping.adoc` §2 gains one sentence after the `node`, `nodes` and `_entities` paragraph naming `@defaultTenant` as the way an entity whose key cannot carry the tenant resolves (with a link), and the "covers root fields over global tables ..." sentence under the default-tenant paragraph gains "and `_entities` for types marked `@defaultTenant`". `federation-keys.adoc` gains a short "Keys without the tenant column" note pointing at the reference page.

## Design decisions

- **Is `@condition` honoured on `_entities` dispatch?** No. `@condition` is declared `on FIELD_DEFINITION | ARGUMENT_DEFINITION | INPUT_FIELD_DEFINITION`, and the generated `select<Type>Alt<N>` (`SelectMethodBody`) emits `DSL.noCondition()` as its WHERE with nothing ANDed in; `EntityResolution` carries no condition. This item does not add one, because the ownership check sis wanted it for becomes graphitron's own (next point). A type-level condition on entity dispatch is a separate feature (a new `@condition` location, glue reflected at an entity rather than a field coordinate) and waits for a consumer that needs a predicate beyond ownership.
- **Can the condition read the routed tenant?** Moot for ownership. For the record: a `contextArguments` value is read from `GraphQLContext` by name, and graphitron publishes no tenant there under a context-argument name.
- **Does routing alone constrain the rows?** Not by itself. Routing picks the connection and runs the session mount for that tenant; until this item graphitron wrote no tenant predicate of its own into a routed read. The rows are constrained by the key: a key that carries the tenant column puts `tenant_column = value` into the lookup's join. A marked type's key has no such column, so this item supplies it: the lookup ANDs `tenant_column = default tenant`, the first tenant predicate graphitron writes itself, confined to keys the marker routes. In a deployment where each tenant database holds only its own rows the predicate matches every row the database has; where tenants share a database (the runtime allows several keys on one `DataSource`) it is what keeps another institution's username out. Either way sis needs no `@condition` for ownership.
- **Fold arm.** No new `TenantBinding` arm and no new `DispatchFact` arm. `EntityRepBound.AlternativeSlot` names where each alternative's tenant comes from, and a marked type's dispatch fact is the ordinary `Routed`. That makes R1027's dispatch-entry rule agree by construction: `Routed` hands a tenant down, so the subtree inherits (`personProfil` and below classify `Inherited`), `belowBoundEdge` is seeded, and the type is not rejected.
- **Shared runtime path.** `resolveByReps` buckets representations by `__typename` and calls one `handle<Type>` per type, so a marked type forms its own groups and never shares one with a key-routed type. Within `handle<Type>` the group key stays (alternative, tenant); for an alternative routed by the request the tenant is the default for every representation. A batch mixing `FeideBruker` with key-routed types resolves each type on its own tenants, and a key-routed group on the default tenant reuses the default tenant's connection (`entryFor` keeps one per key).
- **Scope.** `_entities` `@key` alternatives only. The marker never routes a `NodeId` alternative, so `node`, `nodes` and the id key of `_entities` keep `classifyNodeDispatch`'s rule (the node key must carry the tenant column, else its own rejection). A `@node` type whose node key carries the tenant and whose `@key` does not may be marked: node ids route by the id, the `@key` by the default. Routing node ids by the default tenant waits for a consumer; sis's case is not a node type.
- **No default tenant vs. an unusable one.** They differ, deliberately. An absent default answers `null` per position (above). A default outside the request's tenant set cannot exist (`RequestTenants`' constructor throws), and an unhosted default fails the operation before any field runs (`GraphitronConnectionInstrumentation.beginExecuteOperation`). That check guards every global read of the request, not only this path, and loosening it for one surface would make a misconfigured default look like an absent user. sis can leave the default unset instead.

## Implementation

**Directive.** `directives.graphqls` (graphitron-model) declares `directive @defaultTenant on OBJECT` with a description in the style of `@globalData`'s, stating the effect and the rejection ladder. `DeclaredDirectives` picks it up, so `SchemaDirectiveRegistry.GENERATOR_ONLY_DIRECTIVES` strips it from the runtime schema and `_service.sdl`; `SchemaDirectiveRegistryTest`'s hard-coded list gains it. `BuildContext` gains `DIR_DEFAULT_TENANT` beside `DIR_GLOBAL_DATA`.

**Model.** `TenantBinding.EntityRepBound.AlternativeSlot(int alternativeIndex, int decodedPosition)` becomes `AlternativeSlot(int alternativeIndex, TenantSource source)` with a sealed `TenantSource`: `KeyColumn(int decodedPosition)` (today's meaning) and `RequestDefault(ColumnRef tenantColumn)`, carrying the tenant column the fold resolved on the type's table so the emitter writes the predicate off the model and never re-resolves the column from `scopes.columnName()`. `EntityRepBound`'s non-empty invariant is unchanged. The emitter switches on `TenantSource` with no `default` branch. The javadoc that describes every entity slot as a decoded-key position is corrected in the same change: the `TenantBinding` class doc ("positional slot in a decoded key", the per-row family), the `EntityRepBound` doc ("each representation carries its own tenant"), and the `NodeIdBound` doc's reference to decoded positions on `EntityRepBound`.

This extends the transitional tenant fold, which reads the walk model and the SDL rather than the fact store, as every tenancy marker does today. The authored application is already captured (`graphql_ast_type_directive_entry` transcribes type-site directives), and an argument-less marker gets no `graphitron_` decode relation by the store's own rule, so this item adds no DDL; whoever re-sources the tenant axis into the store inherits `TenantSource` with it.

**Fold** (`TenantBindingIndex.Fold`).

- *Marker registry.* `@defaultTenant` joins the tenancy markers as the first type-site marker. `TENANCY_MARKERS` and `forEachMarkerApplication` widen to visit object types' applied directives as well as field definitions, so `rejectMarkersWithoutTenancy` (single-tenant build) and `sweepUnreachedMarkers` cover it with no second walk. The ladder records a verdict per marked type through `recordMarkerVerdict`; the sweep rejects a marked type that reached none, with the detail "has no resolvable key, so `_entities` never resolves it". That covers a type absent from `entitiesByType` and one whose keys are all `resolvable: false`, which `classifyEntityDispatch` skips before any ladder could run.
- *Ladder* (`defaultTenantVerdict(typeName, res)`, run in `classifyEntityDispatch` for a resolvable marked type, each rejecting rung a `markerRejection` and leaving the type classified as if unmarked):
  1. its table is not tenant-scoped: "`_entities` already reads global types from the request's default tenant";
  2. no resolvable `Direct` alternative lacks the tenant column: "every key names its tenant" (a type whose only tenant-less key is its node id lands here, its node-key rejection from `classifyNodeDispatch` standing alone);
  3. otherwise accepted.
- *Alternatives.* For a resolvable alternative with `tenantIndex(alt.columns()) < 0`: a `Direct` alternative of an accepted marked type adds `AlternativeSlot(i, RequestDefault(column))`; every other case rejects as today. A type whose every resolvable alternative got a slot records `Routed`, as today.
- *Fix text.* The `Direct` arm of the "does not carry tenant column" detail gains a third fix: "..., mark the alternative resolvable: false, or mark the type @defaultTenant to resolve it on the request's default tenant." That arm fires only for a resolvable `Direct` alternative lacking the tenant column on a tenant-scoped table, which is exactly the ladder's accept condition, so the error never offers a marker the ladder would refuse and needs no separate `accepts…` gate (unlike `acceptsFanOut` and `acceptsGlobalData`, whose ladders refuse sites their rejections reach). The `NodeId` arm is unchanged and never offers the marker, which does not route node ids.

**Emitter** (`HandleMethodBody`, `SelectMethodBody`, `ConnectionRuntimeClassGenerator`).

- Runtime: `TenantConnections` gains `static Optional<K> defaultTenant(DataFetchingEnvironment env)`, reading the request's `RequestTenants.defaultTenant()` from the carrier. Emitted only in multi-tenant builds, like its siblings.
- `emitDecodeAndGroup`: the grouping key for an alternative's representation is `cols[pos]` for `KeyColumn(pos)` (today) and the default tenant for `RequestDefault`. When the default is absent, the representation is not grouped, so its slot stays `null` and no connection is taken. Read the default once per `handle<Type>` call, not per representation, and only when the type has a `RequestDefault` alternative.
- `emitGroupDispatch`: unchanged. `permits`, `dslFor(groupEnv, tenantKey)` and the `handDownRow` stamp apply to the default tenant as to any other; `permits` always holds for it, since the default is in the set by construction.
- `SelectMethodBody`: for a `RequestDefault` alternative the lookup also matches `RequestDefault.tenantColumn` against the group's tenant key, so it resolves exactly as a `KeyColumn` alternative whose join carries the tenant does. `buildMethod` receives the alternative's `TenantSource` rather than looking the column up. Keep the `select<Type>Alt<N>` call site in `emitAltSwitch` uniform across sources; whether the tenant key reaches the method as a parameter every routed select takes, or as an extra column of the `VALUES` tuple joined like a key column, is the implementer's choice. `KeyColumn` alternatives emit the same SQL as today.
- `tenantPosition` (which throws "classification and emission disagree" for an alternative without a slot) switches on `TenantSource`.

**Docs.** The reference page drafted above, an entry in `docs/manual/reference/directives/index.adoc`, the generated `supported-directives.adoc` (both enforced by `DirectiveDocCoverageTest`), and the `tenant-scoping.adoc` and `federation-keys.adoc` edits above.

## Tests

Classification tier, `TenantBindingClassificationTest`:

- a marked tenant-scoped entity whose one `@key` lacks the tenant column records no rejection and `byEntityType` holds `EntityRepBound([AlternativeSlot(0, RequestDefault(film_id))])`; a tenant-scoped `@splitQuery` child classifies `Inherited`;
- a marked type with one key carrying the tenant column and one not yields `[AlternativeSlot(0, KeyColumn(n)), AlternativeSlot(1, RequestDefault(...))]` in declaration order;
- a marked `@node` type whose node key carries the tenant and whose `@key` does not: `NodeId` slot `KeyColumn`, `Direct` slot `RequestDefault`, no rejection;
- a marked `@node` type whose only tenant-less key is its node id: the marker rejects ("every key names its tenant") and the node-key rejection stands, without offering `@defaultTenant`;
- each remaining rejection with its own detail: single-tenant build, global table, no resolvable key (absent from `entitiesByType`, and all `resolvable: false`), every `@key` carrying the tenant column;
- `anEntityKeyMissingTheTenantColumnRejectsAloneNamingItsFix` pins the extended fix text, and `aNodeKeyMissingTheTenantColumnRejectsAloneAndOtherNodeTypesKeepTheirContext` stays as is (the node-key text does not offer the marker).

`TenantFanOutClassificationTest`: `@tenantFanOut` on a child of a marked entity rejects with "sits under a tenant-bound ancestor", as `markerOnARoutedEntityRejects` does for a key-routed one.

Execution tier, `TenantDivinedRoutingExecutionTest`. The multi-tenant fixture schema (`multitenant.graphqls`) gains a federation `@link` importing `@key`, and `Inventory` becomes `@key(fields: "inventoryId") @defaultTenant` with a tenant-scoped `@splitQuery` child over `inventory_film_id_fkey` (its film). Tenant 1's database gains one `inventory` row whose `film_id` is 2, standing in for a shared database holding another tenant's row. Cases:

- default tenant 1: representations for tenant 1's own inventory, the film-2 row in tenant 1's database, and an unknown id answer row, `null`, `null` at their positions, with no errors; the resolved inventory's film is tenant 1's film, read on tenant 1's database;
- no default tenant (`RequestTenants.of`): every `Inventory` position is `null`, no errors, no tenant connection opened for them, and a key-routed representation in the same batch (a `FilmActor` node id) still resolves on its own tenant;
- default tenant 2 with tenant 1's inventory id: `null` (the row is on another database).

The existing multi-tenant execution tests stay green with the `@link` added; the wrap only adds `_service` and `_entities`.

## Other solutions we've considered

- **A new `TenantBinding` or `DispatchFact` arm for request-routed entities.** Every exhaustive switch over `TenantBinding` (`TenantDslEmitter`, `RoutineWriteCommands`) would gain a case that means what `EntityRepBound` already means to them (an inherited tenant), and `routedEntities()` would project two shapes into the map the emitter reads. A tenant source per alternative is the smaller change, and it is the only shape that serves a type with one key of each kind.
- **`@condition` on the entity type for ownership,** as sis first asked. It would need a new directive location and entity-coordinate glue to say something graphitron already knows (the tenant column and the routed tenant), and a schema that forgot it would silently serve another tenant's row in a shared database.
- **Treat the default tenant as a silent fallback for any unroutable entity.** Rejected by the goal: the default tenant must serve only reads the schema opted into.
- **`null` per position for an unhosted default as well.** See the design decisions above: that check guards the whole request.

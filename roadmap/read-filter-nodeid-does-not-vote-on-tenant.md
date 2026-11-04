---
id: R1130
title: "A node id in a query filter does not vote on the tenant when another slot already binds it"
status: In Progress
bucket: feature
priority: 4
theme: runtime-connection
depends-on: []
created: 2026-10-30
last-updated: 2026-11-04
---

# A node id in a query filter does not vote on the tenant when another slot already binds it

## Goal

Some query filters name the tenant in a value the client must send, such as a non-null tenant field. That value already decides which tenant the query reads. A node id elsewhere in the same filter then only filters: it no longer helps choose the tenant. If the id belongs to another tenant it matches no row, and the field returns the bound tenant's rows instead of failing.

Case, from sis (`LarestedRutingIT.idListeMedIderFraToLarestederAvvisesForFeltet`, difftest X3):

```graphql
input StudieprogramV2FilterInput {
    eierOrganisasjonskode: String! @field(name: "INSTITUSJONSNR_EIER")   # the tenant column
    studieprogrammer: [ID!] @nodeId(typeName: "Studieprogram")          # key embeds the tenant
}
```

```graphql
studieprogramV2(filter: { eierOrganisasjonskode: "1234",
                          studieprogrammer: [<id at 1234>, <id at 186>] }) { totalCount }
```

Today the field is `null` with "Tenant bindings disagree within one operation: '1234' vs '186'". After this item it answers `totalCount: 1` (the 1234 row) with no error. Graphitron 9 gave the same answer.

Two things stay as they are:

- **Writes** still refuse disagreeing tenant values, because one statement across two tenants has no correct execution.
- **A query whose only tenant source is its ids** still refuses when they disagree, because there is no single tenant to pick. An example is `filmActorsByNodeId(ids: [ID!]!)` in the sakila multi-tenant fixture.

## Why the rule is sound

Some vocabulary first. A *bound slot* is one argument or input field whose value names the tenant. The *agreement fold* is the generated runtime check that reads every bound slot of a field and requires them to name one tenant before it acquires that tenant's connection. A slot that *steps down* leaves the fold but keeps its SQL predicate, so it still filters the rows.

- **The SQL itself excludes the foreign row.** The slot that keeps routing (the *anchor*) compiles to a predicate on the filtered table's own tenant column, `INSTITUSJONSNR_EIER = '1234'`. That predicate is joined with AND to the id predicate. A row that matches an id at 186 carries 186 in that column, so the anchor rejects it.
  - This holds even when two tenants share one database. The runtime supports that layout: `TenantDivinedRoutingExecutionTest.mount_tenantsSharingOneDataSource_getOneMountEach_withTheirOwnKey`. So the argument is "the anchor's predicate excludes it", never "the other tenant's row is in another database".
  - It is also why the rule requires an anchor whose predicate graphitron emits on the filtered table's own column, and does not accept just any slot that is guaranteed to be present.
- **Reads are already lenient elsewhere.** `node(id:)` and `_entities` answer `null` without an error for an id at another tenant. An id that exists nowhere, in this same filter list, already matches nothing. A foreign id in a read filter is the only case that fails the whole field.
  - Under a parent that already fixed the tenant, a filter id is already filter-only today. `TenantBindingIndex.Fold.armOf` decides `ParentRowBound` and `Inherited` ahead of any argument. The `FilmStock.scenesWhere` fixture is that case.
- **Access checking is unchanged.** The anchor's tenant is checked against the request's permitted set as today. The stepped-down id's tenant is never checked, because nothing is routed to it and nothing from it is read.
- **Malformed ids still fail the same way.** A stepped-down slot's id is still decoded by the filter's own predicate. A malformed id still fails with the one decode message, a little later than before (at the predicate, not at routing).

## What exists today

- **Classification.** `TenantBindingIndex.Fold.armOf` classifies a field whose own arguments name the tenant as `TenantBinding.ArgumentBound`. `ArgumentBound.bindings()` lists every `BoundSlot`. `Fold.directBinding` collects them through a `SlotCollector`, which reads the operation members:
  - condition members (filters) are read from `ColumnBindingLedger.slotsAt`;
  - lookups, insert/upsert inputs, the WHERE keys of an update or delete, and service-call parameters are read from the member itself.
  - The collector dedupes by slot name and keeps the first mint.
- **Slot axes.** Each `BoundSlot` carries:
  - a `SlotProjection`: `Raw` means the value is the tenant; `DecodedKeySlot` means the value is a node id whose decoded key holds the tenant at a known position;
  - a `SlotPresence`: `Guaranteed` means every request carries it; `PerElement` means it is non-null but read through lists; `Optional` means the client may omit it.
- **Runtime.** `TenantDslEmitter.argumentBound` renders one read per slot and folds them through the generated `TenantConnections.divinedTenant` / `namedTenant`. Each value passes through `agreeOnTenant` (`ConnectionRuntimeClassGenerator.divinedTenantAgree`), which throws the generated client exception on the first mismatch.
- **Where the routing slots come from.**
  - `ColumnBindingLedger` records a filter slot's columns ahead of the emission decision. A predicate suppressed by an authored `@condition` (`override: true`, or an input field's own `@condition`) still routes. `Query.filmsUnderOverride` is the fixture.
  - The ledger does not record whether the slot's implicit predicate was emitted, or whether it compares the filtered table's own columns. This item needs both.
- **Predicate shape.** A decoded node id's implicit predicate compares the whole key tuple, tenant column included: `BodyParam.RowEq` / `RowIn` for a composite key, `Eq` / `In` for arity 1. The ledger mints a filter slot only where the tenant column is in that tuple.

## The rule

A bound slot steps down when all four of these hold. A field may have several anchors, and two anchors that disagree still refuse.

1. **It is a non-guaranteed decoded id.** Its projection is `DecodedKeySlot` and its presence is not `Guaranteed`. A guaranteed id (`id: ID!`) routes like any guaranteed slot.
2. **It is a pure filter that graphitron writes.** Every mint of the slot's name came from a condition member, and its implicit predicate is emitted.
   - A name also minted by a lookup, a write or a service-call parameter keeps voting.
   - So does one whose predicate an authored `@condition` suppressed. That condition is opaque, so nothing shows it compares the whole key.
3. **The field has an anchor.** An anchor is a bound slot that:
   - is `Guaranteed`;
   - was minted from a condition member on the same table as the stepping-down slot;
   - has an emitted implicit predicate on that table's own tenant column, meaning a column-backed argument or input field, or a reference whose binding lifts onto own-table columns (`FilterBinding.Local`).

   The anchor's projection may be `Raw` or `DecodedKeySlot`; both predicates compare the tenant column.
4. **The field only reads.** Its operation members include no `WRITE` and no `SERVICE_CALL`.
   - This is the "query versus mutation" line, drawn on what the statement does rather than on which root type it hangs from. Every mutation leaf carries a `WRITE` or `SERVICE_CALL` member, so mutations keep refusing whatever their slots look like.
   - It also leaves service-backed reads alone, since the service is handed the connection and its SQL is opaque.

**Optional `Raw` slots keep voting.** An example is `altFilm: Int @field(name: "film_id")` beside `filmId: Int!`. Two plain tenant values that disagree are the client contradicting itself in tenant terms, not a reference to another tenant's entity. sis has not asked for this, and widening the rule later is additive.

**Only filters are covered, not lookups.** A `@lookupKey` slot answers per key and is owned by the lookup mapping. Decoded node-id lookups already classify through the per-row node dispatch, not as an `ArgumentBound` slot.

**What the rule does not change.**
- `WhenNoTenant` is unaffected: an anchor is `Guaranteed`, so the arm stays `Unreachable`.
- `ArgumentBound`'s invariants hold: the anchor stays, and it is required.
- A field that had no anchor folds exactly as today.

## Implementation

- **`ColumnBindingLedger.ColumnBoundSlot`** gains a component stating the slot's implicit predicate: suppressed, emitted on the filtered table's own columns, or emitted through a non-local reference binding. A sealed three-arm type fits better than two booleans, because "suppressed and own-table" is not a state.
  - It is set where the emission decision is already made, at the three sites that mint ledger slots: `FieldBuilder.projectFilters` (field-level and argument-level `override`), `FieldBuilder.walkInputFieldConditions` (`enclosingOverride`, or the field's own `@condition`), and `FieldBuilder.collectCells`.
  - `ColumnBindingLedger.requireCovers` already pairs each emitted predicate with a ledger slot. Extend it to check that a slot marked emitted has its predicate among the body params and a slot marked suppressed does not. Then the new component cannot drift from what is emitted.
- **`TenantBindingIndex.Fold.SlotCollector`** records, per slot name, whether every mint came from a condition member, and that member's table and predicate state. `collectFromColumnBindings` is the only path that can mark a name filter-only. Any other `add` of the same name clears it.
- **`SlotCollector.result()`** applies the rule after collection, so every consumer of `DirectBinding.reading()` sees the same slots: `armOf`, `siteDivinesTenant`, the `@globalData` and fan-out ladders. Pruning never empties the list (the anchor stays), so the `Reading` arm a field classifies to does not change.
  - Condition 4 needs the site's members. The collector is built in `directBinding(site)`, so pass the members' kinds in, or apply the rule in `directBinding` just before `result()`.
- **No runtime or emitter change.** `TenantDslEmitter` renders whatever `ArgumentBound.bindings()` holds, and a stepped-down slot is simply absent from it. The filter predicate is emitted as before.
- **The javadoc that states the fold's contract** names the step-down: `ArgumentBound` ("the emitter guards at runtime that all divined values agree") and `BoundSlot`.
- **The user manual** changes in one place: the node-id paragraph in §2 of `docs/manual/how-to/tenant-scoping.adoc`, the one beginning "A node id (and a federation representation) carries its tenant inside the key". It currently says that in "a filter reading them ... every id in it must decode to the same tenant". It gains the exception: beside a required tenant value in the same filter, an id only filters inside that tenant, and one naming another tenant matches nothing. The filter in the Goal is the example. The write half of the paragraph stands as written.

## Tests

- **Classification tier** (`TenantBindingClassificationTest`):
  - A field with a guaranteed column-backed tenant field and an optional `@nodeId` list filter in one input: `bindings()` holds the anchor alone.
  - The same with `override: true` on the field: the id keeps its slot.
  - The same with the id on an input field that has its own `@condition`: the id keeps its slot.
  - The ids alone (`filmActorsByNodeId` shape): the id slot stays.
  - A guaranteed `id: ID!` beside an optional id list: the guaranteed id anchors, and the list steps down.
  - An optional `Raw` tenant slot beside a guaranteed one: both stay (the existing `oneRequiredSlotRoutesAndCarriesTheOptionalOneBesideIt` already pins it).
  - A mutation whose input carries a required id and an optional decoded id (`UpdateFilmSceneParentInput` shape): both stay.
  - A child `@service` table field with the same filter: both stay.
    - *Implementation note:* this shape does not reach `ArgumentBound` today. A child `@service` table field's filter mints no slot (the field is rejected with "no argument or input field maps to tenant column"), so there is nothing for the rule to keep or prune, and no test pins it. The `SERVICE_CALL` arm of condition 4 stays as a guard for the shape when it starts binding. The `WRITE` arm is pinned by `aWriteKeepsEverySlot`.
- **Ledger** (`ColumnBindingLedger` coverage): `requireCovers` fails on a slot marked emitted whose predicate is absent, and on one marked suppressed whose predicate is present.
- **Execution tier** (`TenantDivinedRoutingExecutionTest`, sakila `multitenant.graphqls`). Add the sis shape as a connection, so `totalCount` is exercised as in the sis case:

  ```graphql
  input FilmActorFilter {
      filmId: Int! @field(name: "film_id")
      actors: [ID!] @nodeId(typeName: "FilmActor")
  }
  # in Query
  filmActorsWhere(filter: FilmActorFilter!, first: Int, after: String): [FilmActor!]!
      @asConnection @defaultOrder(primaryKey: true)
  ```

  - `filter: { filmId: 1, actors: [<id at film 1>, <id at film 2>] }` answers the film 1 row, `totalCount: 1`, no error, and tenant 2's database is never opened.
  - `filter: { filmId: 1, actors: [<id at film 2>] }` answers an empty page with no error.
  - `filter: { filmId: <tenant outside the set>, actors: [<id at film 1>] }` is refused as `argumentRoutedTenantOutsideTheSet_isRefused_andItsDatabaseNeverOpened` is today.
  - `filmActorsByNodeId(ids: [<id at film 1>, <id at film 2>])` still answers "Tenant bindings disagree", with no tenant database opened. This is new: today only the agreeing case is pinned.
  - The existing write refusals stand unchanged and are the mutation pins: `updateSceneParent_crossTenantParent_refusedBeforeAnyConnection`, `bulkDeleteByNodeId_idsMixingTenants_refusedBeforeAnySql`, `service_optionalSecondIdJoinsTheAgreementOnlyWhenSent`.
  - Under an inherited tenant, `films(filmId: 1) { stock { scenesWhere(filter: { scene: <scene at film 2> }) { id } } }` answers an empty list with no error. This pins that the parent-decided arms were already filter-only, beside the existing same-tenant `nestedChildWithATenantFilter_filtersInsideTheInheritedTenant`.

## Other solutions we've considered

- **Every non-guaranteed slot steps down once an anchor exists**, `Raw` included. This would also be sound for the same SQL reason. It is set aside because `Raw` disagreement is a different client error from a foreign entity reference, and nobody has asked for it; it can be widened later.
- **Any guaranteed slot anchors**, including a context argument or a suppressed predicate. This is rejected: neither puts the tenant column in the statement. Where two tenants share a database, a stepped-down foreign id would then match the other tenant's row, under an access check that only saw the anchor's tenant.
- **Decide by operation type** (Query versus Mutation root). This is rejected in favour of member kinds. A read under a mutation payload and a service-backed read under Query both sit on the wrong side of a root-type test, and the member kinds are already what decides the `Untenanted` leaf.
- **Lenient at runtime**: keep every slot and have the fold drop the disagreeing filter ids. This is rejected: classification already knows which slots are filters, and a runtime filter would have to carry that knowledge into the generated `TenantConnections` helpers, which are shared by every arm.

## Provenance

Requested 2026-10-30 by sis (the Graphitron 10 port, request C40). When this lands, sis's `LarestedRutingIT` test flips to expecting the 1234 row; no sis code change.

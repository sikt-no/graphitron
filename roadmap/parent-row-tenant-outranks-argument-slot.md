---
id: R1018
title: "A child field routes on its parent row tenant, not on an optional argument slot"
status: Spec
bucket: bug
priority: 6
theme: runtime-connection
depends-on: []
created: 2026-10-12
last-updated: 2026-10-12
---

# A child field routes on its parent row tenant, not on an optional argument slot

## Goal

In a database-per-tenant build, a child field below a tenant-scoped parent routes on the tenant its parent row names, even when the field also takes an argument that can carry a tenant. Today the argument wins: a nested `@splitQuery` whose filter argument has a tenant slot (a value that names the tenant column, such as an encoded id) classifies as `ArgumentBound`, the tenant binding arm that reads the tenant from the request arguments. When the client omits that optional argument, the fetcher throws `NoSuchElementException: The tenant binding value is absent`, although every parent key in the batch already carries the tenant column. When this lands the parent-row binding (or the inherited tenant) decides, and an argument slot never routes a child field whose parent already supplies a tenant: the argument is only a filter there, evaluated in the parent's tenant database.

The minimal pair, on the multi-tenant sakila fixture (`graphitron-sakila-example/src/main/resources/graphql/multitenant.graphqls`, tenant column `film_id`, where `Film` is reached under a tenant through `films(filmId:)` and through the parent-row field `FilmEndorsement.film`). `Film.scenes` exists there today; `scenesWhere` and its input are the fixture addition this item makes, shaped like the sis fields that tripped (an optional input whose member is a node id embedding the tenant column):

```graphql
input FilmSceneFilter { scene: ID @nodeId(typeName: "FilmScene") }

type Film implements Node @table(name: "film") @node(keyColumns: ["film_id"]) {
    # Routed today on the parent Film's tenant.
    scenes: [FilmScene!]! @splitQuery @reference(path: [{key: "film_scene_film_id_fkey"}])
    # Today: routed on filter.scene, and throws when the client omits filter.
    # After: routed exactly like scenes; filter only narrows the rows.
    scenesWhere(filter: FilmSceneFilter): [FilmScene!]! @splitQuery
        @reference(path: [{key: "film_scene_film_id_fkey"}])
}
```

One build-time effect follows from the same rule. A child field whose argument names the tenant column through a shape that cannot route (a `@nodeId` over a multi-table interface or union, whose members decode the same id with their own key columns) is rejected today with "no single decode to route the statement on". Below a parent that supplies the tenant, nothing needs that argument to route, so the field builds and routes on its parent.

## Mechanism today

Terms used below. The *tenant binding* (`TenantBinding`) is the per-field decision of where the tenant comes from, computed once after classification by `TenantBindingIndex.Fold` (the *fold*). A *site* is one classified instance of a coordinate (a flat field is one site, a field of a nesting type one per enclosing parent). The arms that matter here:

- `ArgumentBound`: the field's own arguments carry the tenant (its *direct binding*, read by `Fold.directBinding` from the column-binding ledger, lookup keys, write inputs and root service parameters).
- `ParentRowBound`: a global parent row names the tenant of the child row through a foreign key landing on the tenant column (`Fold.parentRowBinding`).
- `Inherited`: every path from a root to the parent type passed through an edge that established a tenant, so the field reuses it (`Fold.tenantContextOf`).

`Fold.armOf` checks, in order: the `@tenantFanOut` / `@globalData` marker ladders, the cross-scope rejection (tenant and global tables in one statement), the direct binding's declines (a shape that names the tenant column but cannot route, rejected when the statement needs a tenant), `direct.divines()` → `ArgumentBound`, node dispatch, then the parent-row rung, then the two `Inherited` rungs (the connection-bound service one and the plain one), with the untenanted leaf between them. So a slot-bearing child field never reaches the parent rungs. `parentRowBinding` additionally returns `null` for any site whose direct binding has slots or declines, so the edge predicates agree with that order.

The comment above the parent-row rung already states the intended rule ("the parent row names the tenant that holds the child row, so it decides even when an ancestor handed a tenant down"), and the manual makes the same point for child services ("runs on its parent's tenant when the parent is tenant-bound"); the argument rung simply sits ahead of it.

## Implementation

**`TenantBindingIndex.Fold.armOf`: the parent decides first.** Move the parent-row rung and the two `Inherited` rungs, each with its existing guard, to sit directly after the cross-scope rejection and ahead of the declines rejection and the `ArgumentBound` rung:

1. `parentRowBinding(site)` non-null → that arm.
2. `!anyGlobal && bindsConnection(out) && tenantContextOf(type)` → `Inherited`.
3. `needsTenant && tenantContextOf(type)` → `Inherited`.

Then the declines rejection, `ArgumentBound`, node dispatch, the untenanted leaf, the connection-service rejection and the `noTenantBinding` rejection follow as today, and the old positions of the three rungs are deleted. Rung 3 carries `needsTenant` explicitly because it used to sit below the `!needsTenant` untenanted leaf; a field touching no tenant table keeps its untenanted leaf. Rung 2 changes no verdict on its own (child services contribute no slots, see `collectFromServiceCall`), and is moved only so the parent rungs read as one block.

Root sites are unchanged by construction: the roots seed `withoutContext`, so `tenantContextOf` is false there, and `parentRowBinding` serves only a `ChildField.BatchedTableField`. The ladder a root walks is therefore exactly today's, which is why R1019 (the root-level variant) needs its own design. A non-root site whose parent supplies no tenant (a global parent with no foreign key onto the tenant column) still reaches `ArgumentBound` as today.

**`Fold.parentRowBinding`: drop the direct-binding exclusion.** It keeps the source-shape and marker exclusions and stops returning `null` when the site's direct binding has slots or declines. Its javadoc loses "binding no tenant itself". Through it, `siteEstablishesContext` now answers true for a declining child with a parent-row binding, which is the subtree context the new arm hands down; a slot-bearing child already answered true.

**Edge predicates otherwise unchanged.** `siteEstablishesContext` needs nothing else: a non-root site whose parent has a context sits on a type outside `withoutContext`, so whether its edge establishes or merely passes on context does not change the every-path fact. `siteDivinesTenant` keeps counting the argument slot. It feeds the any-path `belowBoundEdge` fact, which only rejects (`@tenantFanOut` below a divined tenant), and an argument naming a tenant still contradicts fanning below it. Neither predicate may consult `tenantContextOf`, which `foldAncestorContexts` computes from them.

**What the demoted argument does: nothing for routing, and no agreement check.** Only condition and lookup slots can reach a non-root field (service-call and write slots are root-only), so a demoted slot is a read filter's value, never a value whose job is routing. The tenant the field runs on is the parent's, which was already checked against the request's tenant set where it was divined or read off the parent row, so no connection to another tenant can open through the argument. Whatever filter the author wrote then runs in that database: the generated predicate, or an `@condition` that overrides it. That is the posture the tree already takes for child services. The posture for values that route is untouched: the root agreement fold, the write and service refusals of disagreeing ids, and `@tenant` markers (which reject outside a root `@service`, `Fold.sweepTenantMarkers`) all stay as they are.

**Nesting types.** A field of a nesting type gets the same reorder at each site. `Fold.combine`'s "unequal arms cannot arise" argument still holds: `tenantContextOf` is a per-type fact, the parent-row slot is still the only per-parent input, and `GraphitronSchemaValidator.validateNestingParentCompat` still rejects a batched field of a nesting type shared by several parents, so a shared type cannot yield `ParentRowBound` at one site and `ArgumentBound` at another on a schema the validator admits.

**No emitter change.** `TenantDslEmitter` already emits `Inherited` and `ParentRowBound` for `@splitQuery` children (per-tenant loader names, `dslFor`, parent-row key reads), and those paths are execution-tested. The fields that change arm simply stop emitting the `divinedTenant(decode…TenantSlot…OrThrow(tenantSlot(env.getArgument(…))))` acquisition. Single-tenant output is byte-identical: without configured tenant scopes every `TenantDslEmitter` entry point returns the single-tenant form before it reads a binding.

**User manual** (`docs/manual/how-to/tenant-scoping.adoc`, §2). After "Routing is divined from the operation: an argument bound to the tenant column routes the statement and hands the tenant down its subtree", add:

> Below a field that already has a tenant, either handed down or named by its parent row, a child field runs on that tenant even when its own arguments name the tenant column. There the argument only filters, inside the parent's tenant database, so it can be optional, and a value naming another tenant cannot route the field elsewhere.

## Tests

- **Classification** (`TenantBindingClassificationTest`). Each case is first written against today's tree and seen to fail (today's verdict is `ArgumentBound` or a rejection):
  - Under an `ArgumentBound` root (`films(filmId:)`), a child `@splitQuery` with an optional scalar argument `filmId: Int @field(name: "film_id")` classifies `Inherited("Film")`, and so does one whose optional input-object argument carries `@nodeId(typeName:)` of a type whose key embeds `film_id`.
  - On the `ENDORSEMENTS` fixture, `FilmEndorsement.film @splitQuery` with an optional `filmId: Int @field(name: "film_id")` argument classifies `ParentRowBound` with the same slot as the argument-free field.
  - The `occSchema` field (a multi-table `@nodeId` argument over `Inventory | FilmActor`) moved under `Film`, so it sits under a tenant context, classifies `Inherited` with no `NoTenantBinding` rejection; the same argument on the root (`multiTablePolymorphicNodeIdDeclinesInBothMemberOrders`) still rejects.
  - Unchanged verdict: a child of a global parent with no foreign key onto the tenant column (`Language.films(filmId: Int @field(name: "film_id")) @splitQuery`, whose key lands on `film.language_id`) stays `ArgumentBound`.
  - A nesting-type variant (`FilmStock` under `Film`, from the existing `FILM_STOCK` fixture) with a slot-bearing `@splitQuery` field classifies `Inherited`.
- **Pipeline** (`TenantRoutedFetcherPipelineTest`): `schema.tenantBindingOf("Film", "scenesWhere")` on the multi-tenant fixture is `Inherited`. Facts only, no code-string assertions on generated bodies (`docs/architecture/how-to/testing.adoc`).
- **Compilation and execution** (sakila-example): add `FilmSceneFilter` and `Film.scenesWhere` (Goal) to `multitenant.graphqls`. In `TenantDivinedRoutingExecutionTest`:
  - `films(filmId: 2) { scenesWhere { label } }` with `filter` omitted answers tenant 2's three scenes. Today this throws `NoSuchElementException`, so it is the acceptance evidence for the goal.
  - The same query with `filter: {scene: <film 2 scene id>}` answers that one scene.
  - `endorsements { film { scenesWhere { label } } }` with `filter` omitted answers one scene for film 1 and three for film 2. Both tenants hold scenes, so this shows each parent routes on its own tenant within one request; with the argument as the router it could not run at all.

## Other solutions we've considered

- **Check an agreeing argument at runtime and refuse a disagreeing one.** This would decode the slot on every invocation and throw when it names a tenant other than the parent's. It adds a failure mode to a well-formed read whose answer, in the parent's database, is already well defined, and it keeps emitting the per-invocation slot decode the fix removes. It buys no data safety: the connection is the parent's either way. The agreement checks that exist protect values whose job is routing (root bindings, writes, service ids), and a child filter's value is not one.
- **Fall back to the parent when the argument is absent.** That would keep `ArgumentBound` and add an absent-value branch to it. The argument would still route when present, so a request whose parents span tenants (the endorsements query above, or a `@tenantFanOut` parent) would send every parent's batch to the one tenant the argument names. Ordering fixes both the absent case and the mixed-tenant batch.

## Related

- R1009 (Done) introduced the site-based fold this plan edits (`Site`, `Verdict`, `combine`, nested sites); the plan is written against it.
- R996 (a tenant-scoped row referencing another tenant) is about which parent-row tenant wins, a neighbouring question in the same ladder; this item does not change which parent rung fires, only that the parent rungs precede the argument.
- R1019 covers the root-level variant, where there is no parent row to fall back on.

## Provenance

sis IT run against Oracle 18c on trunk `dd940c703` (`sis/GRAPHITRON10_STATUS.md`, section "Second IT run"). In the generated `StudieprogramFetchers.rowsKull`, every parent key is a `Row2<String, String>` whose `field1` is `INSTITUSJONSNR_EIER` (the tenant column), yet the tenant is computed as

```java
TenantConnections.divinedTenant(decodeTerminTenantSlot0OrThrow(
    TenantConnections.tenantSlot(env.getArgument("filter"), "terminer")))
```

so omitting `filter` or `filter.terminer` throws. Same in `OrganisasjonsenhetFetchers.rowsInstitutt` and `StudentVedLarestedFetchers.rowsEmneStudieretter`.

---
id: R1018
title: "A child field routes on its parent row tenant, not on an optional argument slot"
status: In Progress
bucket: bug
priority: 6
theme: runtime-connection
depends-on: []
created: 2026-10-12
last-updated: 2026-10-12
---

# A child field routes on its parent row tenant, not on an optional argument slot

## Goal

In a database-per-tenant build, a child field whose parent already supplies a tenant routes on that tenant, even when the field also takes an argument that can carry a tenant. The parent supplies one in two ways: its row names the tenant through a foreign key onto the tenant column, or every path from a root to the parent passed through a field that established a tenant, which then is handed down. Today the argument wins: a nested `@splitQuery` whose filter argument has a tenant slot (a value that names the tenant column, such as an encoded id) classifies as `ArgumentBound`, the tenant binding arm that reads the tenant from the request arguments. When the client omits that optional argument, the fetcher throws `NoSuchElementException: The tenant binding value is absent`, although every parent key in the batch already carries the tenant column. When this lands the parent-row binding (or the inherited tenant) decides, and an argument slot never routes a child field whose parent already supplies a tenant: the argument is only a filter there, evaluated in the parent's tenant database.

The minimal pair, on the multi-tenant sakila fixture (`graphitron-sakila-example/src/main/resources/graphql/multitenant.graphqls`, tenant column `film_id`). `FilmStock` there is a nesting type (no `@table`, it shares the film row) reached through `Film.stock`, and `Film` is reached under a tenant through `films(filmId:)` and through the parent-row field `FilmEndorsement.film`. `FilmStock.scenes` exists today; `scenesWhere` and its input are the fixture addition this item makes, shaped like the sis fields that tripped (an optional input whose member is a node id embedding the tenant column):

```graphql
input FilmSceneFilter { scene: ID @nodeId(typeName: "FilmScene") }

type FilmStock {
    # Routed today on the tenant the film was fetched under.
    scenes: [FilmScene!]! @splitQuery @reference(path: [{key: "film_scene_film_id_fkey"}])
    # Today: routed on filter.scene, and throws when the client omits filter.
    # After: routed exactly like scenes; filter only narrows the rows.
    scenesWhere(filter: FilmSceneFilter): [FilmScene!]! @splitQuery
        @reference(path: [{key: "film_scene_film_id_fkey"}])
}
```

The handed-down case covers global parents too. A global type that every path reaches below a tenant (say `Language`, reached only through `Film.language` below `films(filmId:)`) already hands that tenant to its argument-free children, which classify `Inherited`. After this item a child of it that takes a tenant-naming argument does the same, so the argument filters inside the handed-down tenant: an argument naming another tenant answers no rows instead of routing to that tenant. This is the rule, not a side effect: the same field already reads the handed-down tenant when its sibling omits the argument, and an argument that could reroute it would also send a per-tenant batch to a single tenant (see the alternatives below). Where some path reaches the parent with no tenant (the same `Language` also reached from an untenanted `languages` root), nothing is handed down and the argument still routes, as today.

No build-time verdict changes. The slot shapes that name the tenant column but cannot route (the declines) all arise at root fields today: the multi-table `@nodeId` dispatch leaf (`CallSiteExtraction.PruneOnMismatch`) is minted only for root polymorphic fields, and the `UPDATE` SET, `@tenant` type and service decode declines only on root writes and root services. A child field over a multi-table `@nodeId` argument already classifies `Inherited` below a tenant. So this item lifts no rejection and adds none; it changes which arm a child field that builds today gets.

## Mechanism today

Terms used below. The *tenant binding* (`TenantBinding`) is the per-field decision of where the tenant comes from, computed once after classification by `TenantBindingIndex.Fold` (the *fold*). A *site* is one classified instance of a coordinate (a flat field is one site, a field of a nesting type one per enclosing parent). The arms that matter here:

- `ArgumentBound`: the field's own arguments carry the tenant (its *direct binding*, read by `Fold.directBinding` from the column-binding ledger, lookup keys, write inputs and root service parameters).
- `ParentRowBound`: a global parent row names the tenant of the child row through a foreign key landing on the tenant column (`Fold.parentRowBinding`).
- `Inherited`: every path from a root to the parent type passed through an edge that established a tenant, so the field reuses it (`Fold.tenantContextOf`).

`Fold.armOf` checks, in order: the `@tenantFanOut` / `@globalData` marker ladders, the cross-scope rejection (tenant and global tables in one statement), the direct binding's declines (a shape that names the tenant column but cannot route, rejected when the statement needs a tenant), `direct.divines()` → `ArgumentBound`, node dispatch, then the parent-row rung, then the two `Inherited` rungs (the connection-bound service one and the plain one), with the untenanted leaf between them. So a slot-bearing child field never reaches the parent rungs. `parentRowBinding` additionally returns `null` for any site whose direct binding has slots or declines, so the edge predicates agree with that order. (Declines reach no child site today, see the Goal, so only the slot half of that exclusion has any effect.)

The comment above the parent-row rung already states the intended rule ("the parent row names the tenant that holds the child row, so it decides even when an ancestor handed a tenant down"), and the manual makes the same point for child services ("runs on its parent's tenant when the parent is tenant-bound"); the argument rung simply sits ahead of it.

## Implementation

**`TenantBindingIndex.Fold.armOf`: the parent decides first.** Move the parent-row rung and the two `Inherited` rungs, each with its existing guard, to sit directly after the cross-scope rejection and ahead of the declines rejection and the `ArgumentBound` rung:

1. `parentRowBinding(site)` non-null → that arm.
2. `!anyGlobal && bindsConnection(out) && tenantContextOf(type)` → `Inherited`.
3. `needsTenant && tenantContextOf(type)` → `Inherited`.

Then the declines rejection, `ArgumentBound`, node dispatch, the untenanted leaf, the connection-service rejection and the `noTenantBinding` rejection follow as today, and the old positions of the three rungs are deleted. Rung 3 carries `needsTenant` explicitly because it used to sit below the `!needsTenant` untenanted leaf; a field touching no tenant table keeps its untenanted leaf. Rung 2 changes no verdict on its own (child services contribute no slots, see `collectFromServiceCall`), and is moved only so the parent rungs read as one block.

Root sites are unchanged by construction: the roots seed `withoutContext`, so `tenantContextOf` is false there, and `parentRowBinding` serves only a `ChildField.BatchedTableField`. The ladder a root walks is therefore exactly today's, which is why R1019 (the root-level variant) needs its own design. A non-root site whose parent supplies no tenant (no parent-row slot, and some root-to-parent path that establishes no tenant, which is the parent type sitting in `withoutContext`) still reaches `ArgumentBound` as today. A global parent type outside `withoutContext` does supply one, through rung 3, as the Goal states.

**`Fold.parentRowBinding`: drop the direct-binding exclusion.** It keeps the source-shape and marker exclusions and no longer reads the direct binding at all, so it stops returning `null` when the site's direct binding has slots (or declines, which no child site has today, so that half changes nothing and goes only because the read goes). Its javadoc loses "binding no tenant itself". `siteEstablishesContext` answers as before for every site that builds: a slot-bearing child already answered true through `divines()`.

**Edge predicates otherwise unchanged.** `siteEstablishesContext` needs nothing else: a non-root site whose parent has a context sits on a type outside `withoutContext`, so whether its edge establishes or merely passes on context does not change the every-path fact. `siteDivinesTenant` keeps counting the argument slot. It feeds the any-path `belowBoundEdge` fact, which only rejects (`@tenantFanOut` below a divined tenant), and an argument naming a tenant still contradicts fanning below it. Neither predicate may consult `tenantContextOf`, which `foldAncestorContexts` computes from them.

**What the demoted argument does: nothing for routing, and no agreement check.** Only condition and lookup slots can reach a non-root field (service-call and write slots are root-only), so a demoted slot is a read filter's value, never a value whose job is routing. The tenant the field runs on is the parent's, which was already checked against the request's tenant set where it was divined or read off the parent row, so no connection to another tenant can open through the argument. Whatever filter the author wrote then runs in that database: the generated predicate, or an `@condition` that overrides it. That is the posture the tree already takes for child services. The posture for values that route is untouched: the root agreement fold, the write and service refusals of disagreeing ids, and `@tenant` markers (which reject outside a root `@service`, `Fold.sweepTenantMarkers`) all stay as they are.

**Nesting types.** A field of a nesting type gets the same reorder at each site. `Fold.combine`'s "unequal arms cannot arise" argument still holds: `tenantContextOf` is a per-type fact, the parent-row slot is still the only per-parent input, and `GraphitronSchemaValidator.validateNestingParentCompat` still rejects a batched field of a nesting type shared by several parents, so a shared type cannot yield `ParentRowBound` at one site and `ArgumentBound` at another on a schema the validator admits.

**No emitter change.** `TenantDslEmitter` already emits `Inherited` and `ParentRowBound` for `@splitQuery` children (per-tenant loader names, `dslFor`, parent-row key reads), and those paths are execution-tested. The fields that change arm simply stop emitting the `divinedTenant(decode…TenantSlot…OrThrow(tenantSlot(env.getArgument(…))))` acquisition. Single-tenant output is byte-identical: without configured tenant scopes every `TenantDslEmitter` entry point returns the single-tenant form before it reads a binding.

**User manual** (`docs/manual/how-to/tenant-scoping.adoc`, §2). After "Routing is divined from the operation: an argument bound to the tenant column routes the statement and hands the tenant down its subtree", add the paragraph below. As implemented it sits a little later in §2, as its own paragraph after the nesting-type one, so it follows the parent-row routing it names:

> Below a field that already has a tenant, either handed down or named by its parent row, a child field runs on that tenant even when its own arguments name the tenant column. There the argument only filters, inside the parent's tenant database, so it can be optional, and a value naming another tenant cannot route the field elsewhere.

## Tests

- **Classification** (`TenantBindingClassificationTest`). Each case marked *flips* is first written against today's tree and seen to fail (today's verdict is `ArgumentBound`):
  - *Flips.* Under an `ArgumentBound` root (`films(filmId:)`), a child `@splitQuery` with an optional scalar argument `filmId: Int @field(name: "film_id")` classifies `Inherited("Film")`, and so does one whose optional input-object argument carries `@nodeId(typeName:)` of a type whose key embeds `film_id`.
  - *Flips.* On the `ENDORSEMENTS` fixture, `FilmEndorsement.film @splitQuery` with an optional `filmId: Int @field(name: "film_id")` argument classifies `ParentRowBound` with the same slot as the argument-free field.
  - *Flips, and its unchanged twin.* `Language.films(filmId: Int @field(name: "film_id")): [Film!]! @splitQuery @reference(path: [{key: "film_language_id_fkey"}])`, a child of a global parent whose key lands on `film.language_id`, not the tenant column. With `Language` reached only through `Film.language` below `films(filmId:)` it classifies `Inherited("Language")`. Add an untenanted root `languages: [Language!]!` to the same SDL and it stays `ArgumentBound`. The pair pins the Goal's handed-down case for a global parent and the "nothing handed down, the argument still routes" boundary.
  - *Flips.* A nesting-type variant (`FilmStock` under `Film`, from the existing `FILM_STOCK` fixture) with a slot-bearing `@splitQuery` field classifies `Inherited("FilmStock")`.
- **Pipeline** (`TenantRoutedFetcherPipelineTest`): `schema.tenantBindingOf("FilmStock", "scenesWhere")` on the multi-tenant fixture is `Inherited`, beside the existing `FilmStock` assertions. Facts only, no code-string assertions on generated bodies (`docs/architecture/how-to/testing.adoc`).
- **Compilation and execution** (sakila-example): add `FilmSceneFilter` and `FilmStock.scenesWhere` (Goal) to `multitenant.graphqls`. In `TenantDivinedRoutingExecutionTest`:
  - `films(filmId: 2) { stock { scenesWhere { label } } }` with `filter` omitted answers tenant 2's three scenes. Today this throws `NoSuchElementException`, so it is the acceptance evidence for the goal.
  - The same query with `filter: {scene: <film 2 scene id>}` answers that one scene.
  - `endorsements(note: ENDORSEMENT_NOTE) { film { stock { scenesWhere { label } } } }` with `filter` omitted answers one scene for film 1 and three for film 2, the same shape as the existing `FilmStock.scenes` two-tenant case. Both tenants hold scenes, so this shows each parent routes on its own tenant within one request; with the argument as the router it could not run at all.

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

## Reviewer findings

### Spec → Ready (revisions requested), session_01KPjUvfFpQLkCxqTPrV4Em9, 2026-10-12

The core of the plan holds. The `armOf` ladder, `parentRowBinding`'s direct-binding exclusion, the edge predicates, `foldAncestorContexts` seeding the roots into `withoutContext`, `collectFromServiceCall`'s child-service gate, `combine` and `validateNestingParentCompat` all match the spec's description. Two probe classifications on today's tree, under `films(filmId:)`, confirm the headline defect: a child `@splitQuery` with a scalar `filmId: Int @field(name: "film_id")` argument, and one with an input filter holding `@nodeId(typeName: "FilmActor")`, both classify `ArgumentBound`. Moving the three parent rungs ahead of the declines rejection and the `ArgumentBound` rung extends the existing ladder and adds no new mechanism, so question 2 holds. Question 1 fails on two counts. The spec claims a consumer-visible effect that the tree does not bear out, and it understates which fields change.

1. **The "one build-time effect" paragraph and its test case do not hold against the tree (question 1, viability).** The Goal says a child field whose argument is a `@nodeId` over a multi-table interface or union "is rejected today with 'no single decode to route the statement on'". The Tests section plans the `occSchema` field moved under `Film` as a case that fails first. Probed on today's tree: `Film.occ(id: ID! @nodeId): [Occ!]!` with `union Occ = Inventory | FilmActor`, below `films(filmId:)`, classifies as a `ChildField.BatchedUnionField`. With or without `@splitQuery` its verdict is `Inherited("Film")` and no rejection names it. The decline exists only as `SlotAccess.Declined` for a `CallSiteExtraction.PruneOnMismatch` leaf (`TenantBindingIndex.accessOf`), and this child shape mints no such leaf. So the named test does not fail first, and the effect it is meant to evidence is not a change. Either name a non-root shape that actually declines today and pin it with a failing-first case, or drop the paragraph and the test bullet. If no such shape exists, `parentRowBinding`'s declines exclusion and the "a declining child with a parent-row binding" sentence under Implementation may be guarding an empty set. Say so, so the implementer does not go looking for one.

   *Author:* dropped the paragraph and the `occSchema` test bullet. No non-root shape declines today: `FieldBuilder.lowerParticipantFilters`, the only path that mints `PruneOnMismatch`, serves the root `QueryInterfaceField` / `QueryUnionField` alone, and the other declines (`UPDATE` SET, `@tenant` type, service decode) sit on root writes and root services. The Goal now says no build-time verdict changes. Under Implementation, `parentRowBinding` drops the direct-binding read whole, and the text says the declines half guards an empty set; the "declining child" sentence is gone.

2. **The change also reaches children of global types reached under an inherited tenant, and the spec does not say so (question 1, what changes for a consumer).** `tenantContextOf` is a per-type every-path fact. It also answers true for a global type whose every reaching path crossed an establishing edge, for example `Store` below `films(filmId:) { inventories { store } }`. After the reorder, rung 3 makes a slot-bearing tenant-scoped child of such a type `Inherited`. Today it is `ArgumentBound`. So a request whose argument names tenant 1 below a tenant-2 root changes from answering tenant 1's rows to answering nothing. That may well be the intended rule: the manual sentence the spec adds ("either handed down or named by its parent row") covers it, and argument-free children of such types already inherit. But the Goal speaks only of "a tenant-scoped parent". The Implementation sentence "A non-root site whose parent supplies no tenant (a global parent with no foreign key onto the tenant column) still reaches `ArgumentBound` as today" is true only when that global parent also has no inherited context. State the intended behaviour for this case in the Goal. Correct that sentence. Add a classification case that pins it, either way.

   *Author:* intended, and now stated in the Goal as the handed-down case for a global parent, with the rationale (argument-free siblings already inherit; an argument that reroutes would send a per-tenant batch to one tenant). The Implementation sentence now names the boundary as the parent type sitting in `withoutContext`. Pinned by a `Language.films` pair in Tests: `Inherited("Language")` when `Language` is reached only below `films(filmId:)`, `ArgumentBound` once an untenanted `languages` root also reaches it. Both shapes probed on today's tree classify `ArgumentBound`, so the first fails first.

3. **Factual corrections to carry in the same revision (not blocking on their own).** `Film.scenes` does not exist in `multitenant.graphqls`. The field there is `FilmStock.scenes`, on the nesting type reached through `Film.stock`. So the Goal's "exists there today" claim and the SDL block's `scenes` line on `Film` need adjusting: either the fixture adds `Film.scenes` as well, or the minimal pair is stated against `FilmStock.scenes`. The root `endorsements` takes a required `note: String!`, so the third execution query needs `endorsements(note: ENDORSEMENT_NOTE)`, the way the existing scene test calls it.

   *Author:* the minimal pair is now stated against `FilmStock.scenes`, with `scenesWhere` added beside it on `FilmStock`; the pipeline and execution tests follow (`tenantBindingOf("FilmStock", "scenesWhere")`, queries through `stock`). A probe on today's tree gives `FilmStock.scenes` `Inherited` and `FilmStock.scenesWhere` `ArgumentBound`. The endorsements query now passes `note: ENDORSEMENT_NOTE`.

What satisfies this: finding 1 resolved with either a real failing-first shape or the paragraph and test removed, finding 2 stated in the Goal and pinned by a test, and the corrections in 3.

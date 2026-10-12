---
id: R1019
title: "A field whose tenant slots may all be absent is a build error, or fans out when they are"
status: Spec
bucket: bug
priority: 4
theme: runtime-connection
depends-on: [parent-row-tenant-outranks-argument-slot]
created: 2026-10-12
last-updated: 2026-10-12
---

# A field whose tenant slots may all be absent is a build error, or fans out when they are

## Goal

In a database-per-tenant build, a field that learns its tenant only from values the client may leave out no longer compiles into a fetcher that crashes when the client does leave them out. A *tenant slot* is an argument or input field whose value names the tenant column, such as a scalar mapped to that column, a scalar marked `@tenant`, or an encoded node id whose key holds it. Today a field whose every slot sits behind a nullable argument or input field builds cleanly, and a request omitting them all fails at runtime with an internal `NoSuchElementException: The tenant binding value is absent`. When this lands the build rejects that field and names two fixes: make one slot non-null, or mark the field `@tenantFanOut`, which now accepts optional slots and means "query only the tenants the given values name, or every tenant the request may see when none is given".

A minimal pair over the sakila multi-tenant fixture (`multitenant.graphqls`, compiled with `<tenantColumn>film_id</tenantColumn>`), where `films(filmId: Int! @field(name: "film_id"))` is the existing routed root:

```graphql
type Query {
    # Rejected: every request that omits filmId names no tenant.
    filmsMaybe(filmId: Int @field(name: "film_id")): [Film!]!

    # Accepted: filmId given queries only that tenant; filmId omitted queries every
    # tenant in the request's tenant set and unions the rows.
    filmsNarrowable(filmId: Int @field(name: "film_id")): [Film] @tenantFanOut
}
```

The same holds for a slot nested in a filter input, which is the shape the provenance below hit: `studieprogrammer(filter: StudieprogramFilter)` whose `terminer` and `campuser` fields carry the tenant routes only when one is given, so it rejects unless marked.

## Design

Three changes, one in each place the tenant-binding axis already lives. The *tenant-binding axis* is `TenantBinding`, the per-field verdict on where a field's tenant comes from; `TenantBindingIndex.Fold.armOf` assigns it, reading a per-field `DirectBinding` (the slots, declines and evidence the field's own arguments carry).

**A slot's presence is a classification fact.** A bound slot is *required* when the client cannot omit it: its read path is non-null all the way down. For `SlotRead.TopLevelArg` that is the argument's type; for `SlotRead.NestedInput` it is the outer argument and every input field on the path; `SlotRead.ContextArg` counts as required, because the consumer supplies context arguments server-side and a missing one is a wiring bug, not a client choice. Nullability decides alone: a non-null list slot is required even though it can arrive empty (see Out of scope). One function computes it from the coordinate's `GraphQLFieldDefinition` and the slot's `SlotRead`, called from `SlotCollector.add`, which every mint site (column bindings, lookup scalars and map inputs, WHERE keys, agreement-checked SET columns, service value shapes, `@tenant`-marked scalars) already funnels through, so no mint site computes it on its own. It rides `BoundSlot` as a `boolean required`, and the arm constructors enforce the rule rather than leaving it to the rung that applies it: `ArgumentBound` asserts at least one of its slots is required, and `FanOut` asserts none of its narrowing slots is.

**`DirectBinding` gets a closed reading.** Five readers consult `divines()` / `slots()` once R1018 has landed (`armOf`, `fanOutArmOf`, `globalDataArmOf`, `siteDivinesTenant`, `siteEstablishesContext`; R1018 removes `parentRowBinding`'s read), and a boolean `divines()` would make each of them decide afresh what an optional-only binding means. Replace it with a sealed reading the readers switch on: `Unbound` (no slots, no declines), `Declined` (declines present), `Guaranteed` (at least one required slot) and `Conditional` (slots, none required). The readers decide:

- `armOf`: `Guaranteed` classifies `ArgumentBound` carrying every slot, optional ones included, as today; `Conditional` rejects (below).
- `fanOutArmOf`: `Guaranteed` keeps today's "already binds the tenant column" rejection, since the field would never fan; `Conditional` falls through and becomes the arm's narrowing.
- `globalDataArmOf`: both `Guaranteed` and `Conditional` contradict `@globalData`, as any tenant-naming argument does today.
- `siteDivinesTenant` and `siteEstablishesContext`: only `Guaranteed` counts. A `Conditional` field never establishes a tenant of its own: unmarked, it either rejects or (below a parent that supplies a tenant, by R1018's ordering) inherits one that an upstream edge already counted; marked, it is covered by the `fanMarked` short-circuit in `edgeEstablishesContext` and by `anyFannedAncestor`. This narrows R1018's "`siteDivinesTenant` keeps counting the argument slot" to required slots, with no verdict change, since every path through such a field already crossed the edge that bound it.

**`armOf` rejects a `Conditional` binding.** The rejection is `Rejection.noTenantBinding`, the family that already covers "nothing names the tenant", with a detail that names the optional slots and the fixes. The fan-out fix comes first, because with two optional slots "make one non-null" breaks every client that sends only the other:

> 'Query.filmsMaybe' takes its tenant only from optional values (filmId); a request that omits all of them names no tenant. Mark the field @tenantFanOut to query every tenant the request may see when none is given, or make one of them non-null.

The `@tenantFanOut` sentence appears only where the fan-out ladder would accept the marker on this field. Derive that from the ladder itself (factor `fanOutArmOf`'s rungs so they can be asked without the marker present; the ladder has no side effects, `recordMarkerVerdict` lives in `armOf`), not from a second list of its conditions: a mutation, `@asConnection`, `@service`, `@routine`, `@lookupKey` or non-list field gets the non-null fix alone.

This rung only ever sees fields that still classify `ArgumentBound` after R1018 moves the parent-row and inherited tenants ahead of argument slots. That is why this item depends on R1018: before it, a child field whose parent row names the tenant but whose filter has an optional slot (`StudieprogramFetchers.rowsKull`) would reject here, though R1018 makes it route correctly.

**`@tenantFanOut` with a `Conditional` binding narrows the fan-out.** `TenantBinding.FanOut` stops being a singleton and becomes `FanOut(List<BoundSlot> narrowing)`, where an empty list is today's plain fan-out; its javadoc's "nothing for the model to carry" goes. This is the "fan-out arm gains a carried argument reference" shape R517's open question 4 named, and a sibling arm would only repeat every exhaustive `FanOut` switch for a difference that lives in one runtime argument.

Narrowing is a set, not the single-tenant agreement fold `divinedTenant` / `agreeOnTenant` performs for `ArgumentBound`. A fanned field already answers from several tenants, and a filter slot such as `filter.terminer` is list-valued, so a client filtering terms in two tenants must get both tenants' rows, not a disagreement error. The generated `TenantConnections` gains a narrowing form of `fanOutDomain` taking the slot values:

- Each slot that names at least one tenant (its value, or every element of a list value, flattened as `agreeOnTenant` flattens) contributes that set. A slot that names none (null, absent, or an empty list) contributes no constraint.
- The domain is today's domain (the request's tenant set intersected with the hosted tenant map, in configured order, with today's error for a claimed tenant the deployment does not host) intersected with every contributed set. Intersecting across slots is exact, since a row must satisfy every filter; a union would also be correct but queries tenants that cannot answer.
- A named tenant outside the request set is silently not queried, the same authorization pre-filter fan-out applies to hosted tenants outside the set, and the posture `node` / `nodes` / `_entities` take for an id whose tenant is outside it. An empty domain answers an empty list without taking a connection.

The zero-slot call is today's full domain, so the emitter does not branch on an empty `narrowing`. Everything downstream of the domain is the fan-out path unchanged: per-element `localContext` stamping, failure markers, `collapseFanOut`. `TenantStrategy.Fanned` carries the narrowing slot reads, rendered with the same slot-read fragments `ArgumentBound` acquisition uses.

This settles R517's open questions 1 (a requested tenant outside the request set is skipped) and 4 (the arm carries the slots) for both items; R517 keeps the remaining design, the list-valued tenant-column argument whose job is narrowing rather than filtering, and is updated to say so. Two consequences to document rather than avoid: a narrowed fan-out that hits a failing tenant answers like a fan-out (a `null` element and a path-bearing error, or a nulled field under `[Film!]`), not like a routed field; and a value naming a tenant the request may not see answers no rows from it rather than an error.

## Implementation

- `TenantBindingIndex`: the presence function and its call in `SlotCollector.add`; `DirectBinding`'s sealed reading and its five readers; the `Conditional` rejection in `armOf`; the ladder factoring and the `Conditional` narrowing in `fanOutArmOf`.
- `TenantBinding`: `BoundSlot.required`; the `ArgumentBound` and `FanOut` constructor assertions; `FanOut(List<BoundSlot> narrowing)`, with every `FanOut.INSTANCE` site and exhaustive switch over the arm moved to the record.
- `ConnectionRuntimeClassGenerator`: the narrowing `fanOutDomain` form; `fanOutRows` / `fanOutBatchRows` take the narrowing values and pass them through.
- `TenantStrategy.Fanned` and the launcher render (`plan/LauncherCommands`, `render/`): carry and render the narrowing reads. The batched form (`fanOutBatchRows`) reads the narrowing values from the same environment its statement reads the filter's own values from, so the domain and the WHERE clause see one set of arguments.
- Docs: `docs/manual/how-to/tenant-scoping.adoc` (the "Routing is divined" paragraph and §3) and `docs/manual/reference/directives/tenantFanOut.adoc` (the rejection list's "an argument bound to the tenant column" becomes "a non-null argument bound to the tenant column", plus the narrowing paragraph drafted below).
- `roadmap/tenant-fanout-argument-narrowing.md` (R517): open questions 1 and 4 marked settled here.

## User documentation (first-client check)

For `tenant-scoping.adoc`, after "A tenant-scoped field that nothing routes is a build error":

> The same holds for a field whose tenant comes only from optional arguments: a request that leaves them all out names no tenant, so the build rejects the field. Mark the field `@tenantFanOut` (§3) to query every tenant the request may see when none is given, or make one of the arguments non-null.

For `tenantFanOut.adoc`, a new paragraph after the rejection list:

> A fanned field may take optional arguments that name the tenant, such as a nullable `filmId: Int @field(name: "film_id")` or a node id in a filter. When the request gives one, the field queries only the tenants its value names (several, for a list of ids); when it gives none, the field fans out as usual. Two such arguments narrow together: only tenants both name are queried. A tenant the request may not see is never queried, so a value naming one answers no rows from it, exactly as a filter on rows you cannot see would. A non-null argument naming the tenant is still rejected: the field would never fan out, so drop the directive.

## Tests

- `TenantBindingClassificationTest`: a nullable top-level slot rejects with the optional-slot detail and the fan-out hint; a nullable outer input and a non-null outer input with a nullable inner field both reject; a non-null path classifies `ArgumentBound`; one required plus one optional slot classifies `ArgumentBound` carrying both; the `ArgumentBound` and `FanOut` constructor assertions each reject a slot list that breaks them; a context-argument slot classifies `ArgumentBound`; a mutation and an `@asConnection` field with optional-only slots reject without the fan-out hint. Existing fixtures that assert `ArgumentBound` over `filmId: Int` move to `Int!` (they mean "routed", not "optional"), including R1018's unchanged-verdict case `Language.films(filmId: Int @field(name: "film_id")) @splitQuery`, a child of a global parent with no foreign key onto the tenant column; with `Int` it becomes this item's child-site rejection case, with the fan-out hint since `@table` children may fan.
- `TenantFanOutClassificationTest`: `markerOnFieldAlreadyBindingTheTenantColumnRejects` moves to `filmId: Int!`; a new case pins `FanOut` carrying the nullable slot, and one with a nested nullable input slot; below a fanned field, a marked child with an optional slot rejects as a nested fan-out and an unmarked one classifies `Inherited` (R1018's ordering); an unmarked `Conditional` parent rejects and hands its children no context.
- `TenantFanOutFetcherPipelineTest`: a narrowed root and a narrowed batched child render the narrowing reads into the domain call; a plain fan-out renders as before.
- `TenantFanOutExecutionTest`, over a `filmsNarrowable(filmId: Int @field(name: "film_id")): [Film] @tenantFanOut` root added to `multitenant.graphqls`: omitted returns both tenants' rows; given returns that tenant's rows and opens only its `DataSource` (the counting sources already in the fixture); a tenant outside the request set returns no rows and opens no connection. A list-valued narrowing case, over a nullable `@nodeId` list filter added beside it, returns rows from exactly the tenants its ids name.
- The acceptance evidence for the goal is the classification rejection test plus the execution test: the first shows the crash shape cannot be built, the second that the marked shape answers both requests.

## Out of scope

- An empty list in a non-null list slot. It folds to no tenant and keeps today's runtime error on an `ArgumentBound` field (under a narrowed fan-out it constrains nothing, so the field fans and the filter decides the rows). Treating `[ID!]!` as optional would reject every list-filtered routed root; the empty list deserves its own decision, likely an empty answer without a connection.
- The error type of the remaining runtime absence (`NoSuchElementException`, reported to clients as an internal error). After this item it is reachable only through the empty list above and a missing context argument.
- A list-valued argument whose purpose is choosing tenants rather than filtering rows, and its interplay with the request set, which stays R517.

## Other solutions we've considered

- **Fall back to the request's default tenant.** `RequestTenants.defaultTenant()` already exists, but its documented role is to serve global reads, and the manual promises "which tenant to name is your policy; graphitron never picks one". An omitted filter answered from one tenant's database is a subset presented as complete, the failure `fanOutDomain` already refuses for unhosted tenants.
- **Fan out implicitly, without the marker.** Fan-out is the right meaning of an omitted filter, but the manual's position is that fan-out cannot be inferred, it must be asked for: it multiplies connections and changes the field's failure shape. The rejection asks for it.
- **Reject only, with no new fan-out shape.** Leaves an author whose filter is optional by design (sis) one fix, changing the API to make a filter required.
- **A build warning instead of a rejection.** The field has no correct execution for a legal request; the axis's posture for that is a typed rejection, as with `noTenantBinding` itself.

## Provenance

Latent in the sis IT run against Oracle 18c on trunk `dd940c703` (`sis/GRAPHITRON10_STATUS.md`, section "Second IT run"); it did not fire in the ITs. `StudieprogramFetchers` around line 502 in the generated sources: a root field with two optional slots, `filter.terminer` and `filter.campuser`, and nothing behind them. Whether that field is `@asConnection` was not recorded; if it is, the rejection offers only the non-null fix until fan-out pagination exists.

## Related

R1018 (prerequisite) is the child-field variant, where the parent row supplies the tenant and the fix is ordering. R517 (a tenant-column list argument narrows the fan-out domain) shares this item's narrowing semantics; this item settles its open questions 1 and 4. The `@tenant` marker (shipped under R989, see `changelog.md`) mints slots like any other, so a nullable `@tenant` argument or input-bean member falls under the same rule.

## Reviewer findings

### Spec → Ready, round 1: request revisions (session_01Gi18Yfhx6pV9XENi2M6z9f, 2026-10-12)

Question 1 holds. The goal reads on its own: a field whose only tenant-naming arguments are nullable stops building into a fetcher that crashes when the client omits them, and `@tenantFanOut` gains a narrowing meaning for exactly that shape. The code it describes exists as named, and the outcome is reachable. Question 2 holds for the classification axis, the `FanOut(List<BoundSlot>)` arm and the runtime narrowing: they extend shapes already in the tree. Two points in the `DirectBinding` reading and the new rejection are left for the implementer to design, and they are what sends the item back.

1. **The `Conditional` rejection cannot be `Rejection.noTenantBinding` for a connection-bound root `@service`.** The spec includes service value shapes and `@tenant`-marked scalars as mint sites, and its Related section applies the rule to a nullable `@tenant` member. Take `rateFilmsByFilmId(in: [RateByFilmIdInput!]!): RateFilmsPayload @service(...)` in `multitenant.graphqls` with `filmId: Int @tenant` (nullable). The plain payload's reach is empty, so in `Fold.armOf` `connectionService` holds while `anyTenant` does not. The binding reads `Conditional` and hits the new rung, but `noTenantBinding(coordinate, tableName, detail)` needs a tenant-scoped table from reach, and there is none. As written, `reach...findFirst().orElseThrow()` throws inside the generator, or the message claims a table the field does not reach. The declines branch a few lines above already handles this case: it switches to `Rejection.unroutedServiceCall` because "NoTenantBinding's 'reaches table' opening would be false". To satisfy this, name the rejection for the `!anyTenant` connection-service case (for example `unroutedServiceCall` naming the optional slots, or whatever shape the author prefers). Add a `TenantBindingClassificationTest` case for it, a root service with a nullable `@tenant` member and a non-`@table` return. Derived from the ladder, the fan-out hint will not appear there, which is correct.

2. **The sealed reading's per-reader table covers only `Guaranteed` and `Conditional`.** Each reader switches on the reading, so each one needs an answer for `Declined` and `Unbound` too, and one of those answers is not obvious. Today `fanOutArmOf` ignores declines entirely. A marked field whose only tenant-naming argument declines (say a multi-table `@nodeId` filter) classifies `FanOut`, while one with declines and slots rejects as "already binds". Because `Declined` takes precedence over slots, the implementer would have to choose between keeping that, rejecting, or treating the declined argument as a non-narrowing filter. To satisfy this, state `Declined` and `Unbound` for each of the five readers, even if the answer is "as today", and name the `fanOutArmOf` choice explicitly.

Corrected in passing: "its six readers" under Implementation became "five", matching the five readers listed under Design once R1018 removes `parentRowBinding`'s read.

Non-blocking, for the revision if the author agrees:

- Fixtures beyond the `filmId: Int` ones will newly reject. They use nullable node-id slots: `Query.filmActorsByNodeId(ids: [ID!] @nodeId(...))` in `multitenant.graphqls` (exercised by `TenantDivinedRoutingExecutionTest`), and the `filmActorsByNodeId`, `filmsByNodeId(ids: [ID!])` and `FilmActorFilter { id: ID }` fixtures in `TenantBindingClassificationTest`. The Tests section's migration bullet could name them, since the sakila build breaks without the first.
- The presence walk says "the outer argument and every input field on the path". Say whether a list wrapper's element nullability counts too (`filter: [F]!` with a null element names no tenant for that element). The function also needs the slot name for `TopLevelArg` and `ContextArg`, and `SlotCollector` is static today, so the field definition has to be threaded into it.
- The narrowing `fanOutDomain` should coerce decoded `String` segments to a numeric tenant key, as `divinedTenant` does, or a narrowed node-id slot never matches a hosted key.
- Flattening a list-shaped level drops elements whose slot is null. That is exact only if such an element matches no rows. Where list-of-input-object filters with a nullable tenant member exist, it deserves one sentence.
- R517's open questions 1 and 4 already carry the "R1019 settles this" notes, so that Implementation bullet is done.

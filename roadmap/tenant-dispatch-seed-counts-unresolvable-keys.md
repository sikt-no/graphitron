---
id: R988
title: "The tenant-context fold seeds a @key type no _entities call can dispatch, so its children reject under a bound root"
status: In Progress
bucket: bug
theme: runtime-connection
depends-on: []
created: 2026-10-05
last-updated: 2026-10-06
---

# The tenant-context fold seeds a @key type no _entities call can dispatch, so its children reject under a bound root

## Goal

Under database-per-tenant routing, a `@key` type whose every key alternative is `resolvable: false` stops denying its tenant-scoped children a tenant context. A child field inherits the tenant when every path from a root to its parent passes a field that binds the tenant. Today the federation `_entities` lookup counts as one of those paths, entered with no tenant, for an entity it cannot route. But `_entities` never dispatches a type that has no resolvable alternative, so that path does not exist. As a result, a schema that reaches such a type only through a binding root still has the type's tenant-scoped children rejected with "no ancestor established a tenant context": a build error standing in for a request-time failure that cannot happen.

The minimal pair, under a tenant column of `film_id`. Here `Query.films` binds the tenant (its `filmId` argument maps to the tenant column) and is the only way into `Language`:

```graphql
type Film @table(name: "film") {
    title: String
    language: Language @reference(path: [{key: "film_language_id_fkey"}])
}
type Language @table(name: "language") @key(fields: "languageId", resolvable: false) {
    languageId: Int @field(name: "language_id")
    films: [LanguageFilm!]! @reference(path: [{key: "film_language_id_fkey"}])
}
type LanguageFilm @table(name: "film") { title: String }
type Query { films(filmId: Int @field(name: "film_id")): [Film!]! }
```

With `resolvable: false`, `Language.films` inherits the tenant `Query.films` bound. With `resolvable: true` (the default), the reject stays: a router can hand this subgraph a `Language` representation, `_entities` then serves `Language` from the default source with no tenant, and `Language.films` would read `film` there. The same holds for a tenant-scoped entity. A `Film @key(fields: "title", resolvable: false)` under `Query.films` gets its `Film.inventories` back, where today it rejects even though no representation can reach `Film`.

## What is true today

Terms used below. The *tenant-context fold* is `TenantBindingIndex.Fold`, which decides for each type whether every path from a root to it established a tenant. It does this by seeding a "no context" set and closing it forward along every field edge that does not itself bind a tenant. A *dispatch entry* is a type that a batched lookup surface (`_entities`, `Query.node`) enters from outside the field-edge graph. An *`EntityRepBound`* is the fold's record that an entity's representation carries the tenant column, so `_entities` can route it.

- `TenantBindingIndex.Fold.foldAncestorContexts` seeds every type for which `unboundDispatchEntry` holds. The entity half of that predicate is `entitiesByType.containsKey(t) && !byEntityType.containsKey(t)`: any recorded entity with no `EntityRepBound`.
- `EntityResolutionBuilder` records an `EntityResolution` for every table-bound `@key` type, including one whose keys are all `resolvable: false`. `EntityResolutionBuilderTest` pins that the flag carries through ("dispatcher will skip this alt at match time"). Only a non-table-bound all-non-resolvable type is skipped outright. The `EntityResolution` record javadoc says `alternatives` holds "one entry per resolvable `@key`", which is wrong and is the likely origin of the seed's reading.
- `TenantBindingIndex.Fold.classifyEntityDispatch` skips non-resolvable alternatives. A type with none resolvable therefore gets no `EntityRepBound` and no rejection, and the entity half of the seed fires for it.
- At runtime, `HandleMethodBody.emitPerRepLoop` emits an if-else cascade over resolvable alternatives only. For a type with none, the loop body is empty and the representation resolves to `null`, so no child field runs. `Query.node` reuses the same core (`QueryNodeFetcher.rowsNodes` synthesises representations and calls `EntityFetcherDispatch.resolveByReps`), so neither surface dispatches such a type.

Probed on trunk at `6419211` with the `TenantBindingClassificationTest` fixture (`film_id` as tenant column):

[cols="2,1,3"]
|===
| Fixture | `resolvable` | Result

| `Language` above, under `Query.films`
| `false`
| `Language.films` rejects, no ancestor context (the bug)

| same
| `true`
| `Language.films` rejects, no ancestor context (correct)

| `Film @key(fields: "title")` with `inventories`, under `Query.films`
| `false`
| `Film.inventories` rejects, no ancestor context (the bug)

| same
| `true`
| `Film` rejects (key alternative #0 lacks the tenant column) and `Film.inventories` rejects (correct)
|===

## Implementation

- `EntityResolution` (`graphitron/.../rewrite/model/EntityResolution.java`): add `boolean resolvable()`, computed from `alternatives` and true when any alternative is resolvable. This names the subgraph-level fact, "this subgraph resolves the entity", beside the per-alternative `KeyAlternative.resolvable()` it aggregates. It is a computed accessor, not a stored component, so it cannot drift from its source.
- Correct two wrong claims in the `EntityResolution` record javadoc. First, `alternatives` does not hold "one entry per resolvable `@key`": every `@key` alternative is recorded, and a non-resolvable one is skipped at dispatch. Second, `@node` does not always imply `resolvable: true`: `KeyNodeSynthesiser` keeps a consumer's explicit `@key(fields: "id", resolvable: false)`. Then state the consequence: an entity with no resolvable alternative is dispatched by neither `_entities` nor `Query.node`.
- `TenantBindingIndex.Fold.unboundDispatchEntry`: open with a guard that returns `false` when the type has an `EntityResolution` that is not `resolvable()`. The guard covers both halves of the predicate, the entity half and the tenant-scoped node half, so the method answers "does a dispatch surface enter this type with no tenant?" one way for both surfaces, which share `resolveByReps`. The entity half then covers both the untenanted and the tenant-scoped entity, because `classifyEntityDispatch` leaves either kind without an `EntityRepBound` when no alternative is resolvable. The guard is keyed on a present resolution, so a type with no entry keeps today's answer. Rewrite the javadoc to say a dispatch surface enters a type only when this subgraph resolves it, linking `{@link EntityResolution#resolvable}`.
- No change to `classifyEntityDispatch`, `classifyNodeDispatch`, `routableDispatchSurface`, or the emitters. They already skip per alternative, and the dispatch handler for an all-non-resolvable type stays empty, so the tenant it would route on is never read. For a tenant-scoped `@node` type, the node-half guard never turns a failing build green: if its node key lacks the tenant column, `classifyNodeDispatch` still rejects the type itself (out of scope below), and the guard only drops the redundant child rejections beneath it.

## Tests

In `TenantBindingClassificationTest`, in the "Dispatch entries" section beside `anUntenantedEntityUnderABindingRootDeniesItsChildrenAContext`, which stays as the `resolvable: true` half of the pair:

- `aNonResolvableUntenantedEntityUnderABindingRootLetsItsChildrenInherit`: the Goal fixture. `Language.films` is `Inherited`, and `tenantBindings().rejections()` is empty.
- `aNonResolvableTenantScopedEntityUnderABindingRootLetsItsChildrenInherit`: `Film @key(fields: "title", resolvable: false) { title: String inventories: [Inventory!]! }` under `Query.films(filmId:)`. `Film.inventories` is `Inherited`, with no rejections.
- `anEntityWithOneResolvableAlternativeStillDeniesItsChildrenAContext`: the Goal fixture with a second key on `Language`, one `resolvable: false` and one resolvable. `Language.films` still rejects with "no ancestor established a tenant context". This pins "any alternative" over "every alternative".

In `EntityResolutionBuilderTest`, assert the new `EntityResolution.resolvable()` in three existing cases. It is `false` in `keyNotResolvable_carriesResolvableFalseThrough`. It is also `false` in `nodeTypeWithExplicitIdKey_dedupes`: a `@node` type with an explicit `@key(fields: "id", resolvable: false)` dedups to that one non-resolvable alternative. It is `true` in the plain resolvable-key case at the top of the class.

That `@node` case also shows what the fold change covers: a `@node` type that opts out with `@key(fields: "id", resolvable: false)` stops being seeded. That is correct, because `Query.node` reaches it only through `resolveByReps`, and `resolveByReps` skips the alternative. Pin it with a fourth `TenantBindingClassificationTest` case beside `anUntenantedNodeTypeReachedThroughQueryNodeDeniesItsChildrenAContext`: `aNonResolvableNodeTypeUnderABindingRootLetsItsChildrenInherit`. Its fixture is that test's `Language` with the explicit `@key(fields: "id", resolvable: false)`, reached through a `Film.language` edge under `Query.films(filmId:)` rather than through `Query.node`. `Language.films` is `Inherited`.

## Out of scope

Two neighbouring cases also reject children of a type that never executes. Each is left alone because its fix moves a fact other readers depend on:

- **The node-dispatch classification.** `classifyNodeDispatch` ignores `resolvable` on a tenant-scoped `@node` type that declares `@key(fields: "id", resolvable: false)`. If its node key lacks the tenant column, it still rejects the type and clears `nodeDispatchRoutable` for every node type. It also counts the type in `nodePositions`, which picks between `NodeIdBound` and `Untenanted` for `Query.node`. Changing either one changes emitted routing, so it needs its own item and an execution-tier test. The fold's seed is not part of this; it is in scope above.
- **A stub nothing reaches.** A reference-only `@key(..., resolvable: false)` type with no reaching field edge is still seeded, by the fold's "nothing hands a tenant" clause. The cause is in the fold, not in the domain. `SchemaReachability` seeds every `@key` type because such a type must be *emitted*, and its other readers rightly want "emitted". The fold's javadoc, though, reads the domain as "the types that execute". The follow-up gives the fold its own "executes" notion: reached by a field edge, or entered by a dispatch surface, where dispatch reads the same `EntityResolution.resolvable()`. It does not edit `SchemaReachability`. Reference-only stubs normally carry only their key fields, so tenant-scoped children on one are rare.

Neither is filed yet. File one if a consumer schema hits it.

## Other solutions we've considered

- **A fold-local predicate** instead of `EntityResolution.resolvable()`. Rejected because "does this subgraph dispatch the entity?" is a model fact the emitters already act on, since they emit a handler for every entity and leave it empty when nothing is resolvable. A private copy in the fold would be a second reading of it.
- **An indexed view, `resolvableAlternatives()`**, yielding (declaration index, alternative) pairs, with `resolvable()` defined as "that view is non-empty". `classifyEntityDispatch`, `EntityFetcherDispatchClassGenerator` and the two loops in `HandleMethodBody` each repeat the per-alternative `if (!alt.resolvable()) continue` skip, and each needs the declaration index, so a boolean cannot replace them. That view would let all five sites read one source. It is a refactor with no behaviour change, so it stays out of this fix.

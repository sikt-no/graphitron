---
id: R1169
title: "Connection pagination on a routine-backed child field"
status: Spec
bucket: feature
priority: 5
theme: routine
depends-on: []
created: 2026-11-11
last-updated: 2026-11-12
---

# Connection pagination on a routine-backed child field

## Goal

A child field whose rows come from a table-valued function can be paginated as a connection: `@asConnection` on a `@routine` child field generates the same paged, filtered, ordered connection with `totalCount` that it generates on a table-backed child, with the parent row feeding the function through `$source`. Today that combination fails the build as deferred, so an author who needs it writes a `@service` that calls an array-taking function once per batch and rebuilds the connection, the filters, the ordering and the cursors by hand, and every new filter or sort key then has to be added in two places.

The shape, on the sakila example's correlated routine child (`Actor.films` over `films_for_actor`, which generates today as a plain list), with the one directive added:

```graphql
type ActorFilm {
    filmId: Int    @field(name: "FILM_ID")
    title:  String
}

type Actor @table(name: "actor") {
    films(minLength: Int!): [ActorFilm!]
        @asConnection(defaultFirstValue: 10)
        @routine(
            name: "films_for_actor"
            argMapping: "pActorId: $source.actor_id, pMinLength: minLength"
        )
        @defaultOrder(fields: [{name: "film_id"}])
}
```

When this lands, each actor's `films` is a connection over that actor's function result: `first` / `after` page it, an `@orderBy` argument or the `@defaultOrder` supplies the cursor columns (a function result has no primary key to fall back on), `@condition` filters compose as they do on the unpaginated field, and `totalCount` counts the function's rows for that parent. The request came from a consumer schema (GitHub issue 577) whose field is this shape with a filter input and an `@orderBy` argument on top.

## What exists today

Facts found when the item was filed; re-check them at pickup.

* The refusal is one gate: `FieldBuilder.classifyChildRoutineChain` returns a `Deferred` rejection ("Connection pagination at a child-positioned routine-backed field is not yet supported; the root position paginates") when the field's wrapper is a connection, pinned by `GraphitronSchemaBuilderTest.asConnectionOnAChildRoutineChainDefersOnTheChildSeat`. The mutation sibling of that gate is a different matter (a write's re-read is keyed, not paged) and is not part of this item.
* A child connection is delivered by the batched keyed re-query, the form where the parent rows' key columns are collected by a DataLoader and sent as a `VALUES` table to one batch statement, rather than nested in the parent's statement. A correlated routine child already rides that form without pagination under `@splitQuery` (`Actor.filmsSplit` in the sakila example): the batch key is the routine's column-bound inputs and the `CROSS JOIN LATERAL` call reads them off the `VALUES` table. The same arm already passes the field's pagination component into `ChildField.BatchedTableField`.
* The batched form already pages table-backed children per parent (`SplitRowsMethodEmitter.buildScatterConnectionByIdxHelper`, with `totalCount` as one `count(*)` grouped by the batch index). So the open work looks like joining those two existing halves, the lateral call and the per-parent page and count, not building either. That is a reading of the classifier side only; the emitter side has not been read for this item.
* A routine child with no parameter bound to a parent column has no batch key. `@splitQuery` on that shape is already a directive conflict; a connection on it needs a ruling of its own (refuse, or deliver every parent the same page).
* Ordering needs nothing new at a routine terminus: the rule that a list over a function result must carry `@defaultOrder(fields: ...)` or take an `@orderBy` argument already applies, and it is what gives keyset pagination its cursor columns.

## What the item owes beyond the generator

* `docs/manual/reference/directives/routine.adoc` states the deferral twice (the read-surface paragraph on child pagination, and the limits list). Both change when this lands. The first one's stated reason, that a correlated routine call does not join the batched keyed re-query, is already untrue of the unpaginated `@splitQuery` form.
* An execution fixture in the sakila example beside `Actor.filmsSplit`, showing per-parent pages, a cursor round-trip and `totalCount` against the seeded `film_actor` rows.
* R447 (`routine-chain-fetch-form-breadth`) lists the other deferred fetch forms on routine chains and does not cover this one; its parked note is about a root chain that ends on a catalog table. This item stands on its own and does not depend on it. A chain with `@reference` hops after the routine, at a child position, is in scope here only if it falls out of the same change.

---
id: R1179
title: "A filter or order argument on an inline routine child crashes the generator"
status: Backlog
bucket: bug
priority: 4
theme: routine
depends-on: []
created: 2026-11-12
last-updated: 2026-11-12
---

# A filter or order argument on an inline routine child crashes the generator

## Goal

A filter or an `@orderBy` argument on a `@routine` child field that is not split is answered by the build: with a located error naming the field, or with generated code where the shape can be rendered. Today it crashes the generator. The classifier hands the field's filters and ordering to `ChildField.TableField`, whose constructor pins a routine-bearing path to no filters, no order argument and no pagination and throws, so the build ends on an internal message with no coordinate:

```
TableField with a routine-node path must be the inline correlated chain shape (exactly one lateral
routine node, no filters/argument-ordering/pagination, non-Connection); other routine chain shapes
classify as typed Deferred
```

The schema, a copy of the sakila example's `Actor.films` with one argument added; confirmed against the tree on 2026-11-12:

```graphql
type Actor @table(name: "actor") {
    films(minLength: Int!, title: String): [ActorFilm!]
        @routine(name: "films_for_actor", argMapping: "pActorId: $source.actor_id, pMinLength: minLength")
        @defaultOrder(fields: [{name: "film_id"}])
}
```

`title` names a column of the function's result, so it is a filter the routine does not spend on a parameter; a `@condition` argument and an `@orderBy` argument reach the same pin.

## Notes for pickup

* The message's premise, that the classifier defers these shapes before the leaf is minted, stopped holding when filters and ordering opened on routine reads at the root. Nothing in `FieldBuilder.classifyChildRoutineChain` looks at the child's filters or ordering before the mint.
* `docs/manual/reference/directives/routine.adoc` says filters and `@orderBy` compose normally on a routine-backed read. At a child position that is true only of the `@splitQuery` form, and only once R1169 lands; R1169 rewrites that passage.
* The two halves differ. An order argument on an inline child list is lowered for no child shape (R935 carries that), so here it wants the same refusal other inline children get. A filter on the inline lateral may simply render: the batched form does once its own pin lifts, as R1169's spike showed, and nobody has tried the inline one.
* Until then the remedy for an author is `@splitQuery`, and the located error should say so.

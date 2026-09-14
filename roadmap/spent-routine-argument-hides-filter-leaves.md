---
id: R950
title: "A filter directive on a leaf of a routine-spent argument is ignored without a verdict"
status: Backlog
bucket: bug
priority: 3
theme: routine
depends-on: []
created: 2026-09-14
last-updated: 2026-09-14
---

# A filter directive on a leaf of a routine-spent argument is ignored without a verdict

## Goal

An author who puts a filter directive on an input field that the generator will never read learns so
from the build. Today a `@routine`-backed field spends the whole of any argument its `argMapping`
opens (an argument is *spent* when it is bound to a routine IN parameter and so is never read as a
filter, per the routine manual's read-surface rule), at argument grain: once one dotted path into
`filter` feeds the call, every other field of `filter` is invisible to the WHERE clause, including a
field carrying `@field`, `@nodeId` or `@condition`. No verdict says so. The build is green, the emitted
SDL advertises the filter, and a client that supplies it gets the unfiltered result: a silent
over-return, the failure class the project otherwise refuses to ship. When this lands, a filter-bearing
leaf inside a spent argument is a build error naming the coordinate, the leaf, and the two-argument
spelling that already works. Surfaced in the follow-up comment on
[issue 547](https://github.com/sikt-no/graphitron/issues/547), where it was the third of three
declarative routes to an optional filter on a routine-backed field, the other two being the NPE the
sibling item on omitted node ids in key projections fixes.

```graphql
input ActorFilmFilter {
  actorId:    ID!    @nodeId(typeName: "Actor")   # feeds the routine
  ratingCode: String @field(name: "rating")       # meant as WHERE rating = ?; emits nothing today
}

type Query {
  actorFilms(filter: ActorFilmFilter!): [ActorFilm!]
    @routine(name: "films_for_actor", argMapping: "pActorId: filter.actorId.actor_id")
    @defaultOrder(fields: [{name: "film_id"}])

  # The spelling that works today: the routine input and the filter as separate arguments.
  actorFilmsSplit(actorId: ID! @nodeId(typeName: "Actor"), filter: RatingFilter): [ActorFilm!]
    @routine(name: "films_for_actor", argMapping: "pActorId: actorId.actor_id")
    @defaultOrder(fields: [{name: "film_id"}])
}
```

## Other solutions we've considered

*Spend at leaf grain rather than argument grain.* Every input leaf would have exactly one consumer,
`argMapping` being one kind of consumer beside `@field`, `@nodeId` and `@condition`, and the leaves the
call does not take would fall through to the read surface as they do on a plain table-backed field.
That is the principled rule and the likely successor to this item, and it is not step one for one
reason: on a plain filter an input field without `@field` binds to a result column by name match, so a
fall-through leaf that happens to share a name with a function result column would silently become a
filter the author never wrote, the same failure in the other direction. Making leaf-grain spending safe
means deciding that fall-through leaves must bind explicitly and that a leaf nobody consumes is a
build error, which is a read-surface design of its own. The diagnostic here is the smaller change that
closes the silent case, and the two-argument spelling means the feature is not missing, only one
packaging of it.

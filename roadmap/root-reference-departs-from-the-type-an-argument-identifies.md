---
id: R1086
title: "A root field @reference departs from the type its argument identifies"
status: Backlog
bucket: feature
theme: classification-model
depends-on: []
created: 2026-10-22
last-updated: 2026-10-22
---

# A root field @reference departs from the type its argument identifies

## Goal

A root field can state a `@reference` whose path departs from the type one of its arguments
identifies, so an author reaches a related type from a key without writing a `@routine`:

```graphql
type Query {
  storeForFilm(filmId: ID! @nodeId(typeName: "Film")): [Store!]!
    @reference(path: [{key: "inventory_film_id_fkey"}, {key: "inventory_store_id_fkey"}])
}
```

The argument uniquely identifies rows of `Film`, so the path is naturally the one from `Film` to the
field's target type, and the root field selects `Store` rows by walking it from the identified film.

## Today

A `@reference` on a root field is supported only after a leading `@routine`, whose result type is
where the path departs. Without one the chain has no departure and the field is refused with
`NO_ROUTE_FROM_DEPARTURE`, whose message does not say that a root `@reference` needs a departure.
Ruled out of scope for the current derived-read work on 2026-10-22, so this records the pattern for
later rather than a defect: the refusal is correct for what is supported now.

The refusal now names the shape: a root field with a `@reference` and no `@routine` draws
`ROOT_REFERENCE_WITHOUT_ROUTINE` at the `@reference`, basis unsupported, saying a root field has no
source row for the path to depart from, and no longer `NO_ROUTE_FROM_DEPARTURE`.

## Open questions

* Which arguments may act as the departure: an `@nodeId` argument only, a `@lookupKey` set, or any
  argument set that covers a unique key of the departure table.
* Whether the argument's identified type is named by the argument (`@nodeId(typeName:)`) or must be
  stated on the `@reference`.
* What a list argument means: one departure row per key, or the union of their paths.

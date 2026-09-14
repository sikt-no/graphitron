---
id: R948
title: "An omitted nullable @nodeId in a key projection reads as null, not as an NPE"
status: Backlog
bucket: bug
priority: 2
theme: nodeid
depends-on: []
created: 2026-09-14
last-updated: 2026-09-14
---

# An omitted nullable @nodeId in a key projection reads as null, not as an NPE

## Goal

A nullable `@nodeId` input field that an `argMapping` opens (a *key projection*: the generated code
decodes the opaque node id the client sent into a jOOQ record and reads one key column off it) no
longer crashes the request when the client omits the field. Today the emitted SDL advertises the
field as optional, but the generated fetcher reads the key column off a record the decode helper
returned as `null`, so omitting the field surfaces as a redacted internal error, on both consumers
of the projection: a `@routine` argument and a `@condition` method parameter. When this lands, an
omitted or explicitly-null nullable `@nodeId` in a key projection either reaches the routine or
condition as a plain `null`, letting the database function or the condition author define what an
absent filter means, or is refused at build time with an error naming the coordinate; the Spec
decides which, and no combination of the two ships a runtime NPE. Reported as
[issue 547](https://github.com/sikt-no/graphitron/issues/547); the WHERE-clause path for the same
field shape already carries the guard, so this closes the gap between the two rails.

```graphql
input MineTilgangerFilterInput {
  miljo: ID! @nodeId(typeName: "Miljo")
  navnerom: ID @nodeId(typeName: "Tilgangsnavnerom")   # nullable: omitting it NPEs today
}

type Query {
  mineTilganger(filter: MineTilgangerFilterInput!): [Brukertilgang]
    @routine(name: "mine_tilganger",
             argMapping: "pMiljokode: filter.miljo.MILJOKODE, pNavneromskode: filter.navnerom.NAVNEROMSKODE")
}
```

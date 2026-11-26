---
id: R1211
title: "A node id the tenant slot cannot decode answers a client error on a tenant-routed service, not a NullPointerException"
status: Backlog
bucket: bug
priority: 2
theme: runtime-connection
depends-on: []
created: 2026-11-26
last-updated: 2026-11-26
---

# A node id the tenant slot cannot decode answers a client error on a tenant-routed service, not a NullPointerException

## Goal

A tenant-routed root `@service` field whose tenant is read out of a node-id argument answers a client error when the id is one the generated `NodeIdEncoder` refuses, as the tenant-scoping how-to promises, instead of throwing a `NullPointerException`. A tenant-routed field runs on the connection of the tenant its arguments name; here the tenant is one slot (one key column's value) of a node id, the opaque encoded `id` a client holds. Today an id that is malformed, of another type, of the wrong arity, or carrying a tenant value that is not a value of the tenant column makes the generated fetcher throw, and the client sees the field null beside an error its error channel does not match.

The reproduction, from the sis port to 10.0.0-RC58 (the emitter is unchanged on trunk). `Mutation.endreTimeplaner` is a root `@service` routed by a `@nodeId` field in its input:

```graphql
mutation {
  endreTimeplaner(input: [{id: "abc!!"}]) { errors { __typename } }
}
```

On RC47 this answered sis's `UgyldigInput` error with `Invalid node id "abc!!" for this argument: not a valid Undervisningstimeplan id`. On RC58 the field is null beside an unmatched error, and the log shows the exception below, thrown in `MutationFetchers.endreTimeplaner` on its way through `OwnedConnections.mapTenantLeaves`:

```
NullPointerException: Cannot load from object array because the return value of
"...NodeIdEncoder.decodeColumns(String, String, org.jooq.Field[])" is null
```

The promise, in `docs/manual/how-to/tenant-scoping.adoc` (the paragraph on refusing a tenant): an id whose tenant slot does not decode is malformed, and "on an argument it gets the malformed-id client error, since the slot is part of the id".

## What we know

- `ServiceEmission.slotRead` emits the tenant read for a slot inside a node id as `OwnedConnections.mapTenantLeaves(read, id -> id instanceof String s ? NodeIdEncoder.decodeColumns("<typeId>", s, <columns>)[<keyPosition>] : null)`. `decodeColumns` returns `null` by contract for every id it refuses (its javadoc: malformed, names another type, has the wrong arity, or carries a value that is not exactly what `encode` writes for its column), and the lambda indexes the result unchecked.
- On RC47 the argument's own decode answered the refusal; on RC58 the tenant read throws before that decode is reached. The read arrived with R876's move of a root service call onto the connection the store states for it (`5d6b898af`, `a0a97cfc1`). R876 is In Progress, so this item may fold into it at its owner's call.
- Open for the spec: what the tenant read does with a refused id. It can answer the same client error the argument's decode gives, through the field's error channel where it has one; or it can yield no tenant for that id and leave the refusal to the argument's decode, which then needs a guard, because a list whose every id is refused would otherwise meet the "names no tenant" refusal (`noTenantMessage`) first and answer the wrong message. Execution tests pin a malformed id, an id of another type, and a list mixing a good id with a bad one.

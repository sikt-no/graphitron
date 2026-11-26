---
id: R1211
title: "A node id the tenant slot cannot decode answers a client error on a tenant-routed service, not a NullPointerException"
status: Spec
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

## Cause

The fetcher of a root `@service` field reads its tenant before it calls the service. Each value the tenant is read from is a *slot*: an argument, or a path below one, that the store records in `graphitron_occurrence_tenant_slot`, and where the slot is a node id the tenant is one column of the id's key. The store-sourced emitter writes that read in `ServiceEmission.slotRead`:

```java
OwnedConnections.mapTenantLeaves(read,
    id -> id instanceof String s ? NodeIdEncoder.decodeColumns("<typeId>", s, <columns>)[<keyPosition>] : null)
```

`decodeColumns` answers `null` for every id it refuses: malformed, of another type, of the wrong arity, or carrying a key value that is not exactly what `encode` writes for its column. The lambda indexes that `null`. The throw lands inside the frame's `try` (`FrameEmitter.root`), so `failure` handles it, but a `NullPointerException` is neither claimed by the field's error mappings nor surfaced as a client error, and so it is redacted.

The classifier-side routing does not have this fault. `TenantDslEmitter.projected` reads a node-id slot through the helper `CompositeDecodeHelperRegistry.registerTenantSlot` mints, which raises `NodeIdDecodeFailure`'s client error for a refused id. That is the class's stated rule: every host that decodes a node id fails the same way, with the same message. The store-sourced read did not keep it when root services moved onto the connection the store states for them, which is R876's work.

A routed slot is always of exactly one node type. The store gives a root service no route when it reads a tenant slot off a node id of several node types (the `several_types` arm of the connection rule, raised as a route-entry defect, documented in the tenant-scoping how-to), so a polymorphic id never reaches this read.

## Implementation

The read raises the malformed-id client error, the one the argument's own decode would raise, before any connection is asked for.

- **`ConnectionQuery`**: carry the node's type name on `ConnectionQuery.Node`, because the message names it (`not a valid Film id`). Read it from the slot's own row rather than re-deriving it: join `graphitron_occurrence_tenant_slot_node_id_key` to `graphitron_occurrence_node_id` on `(coordinate, bound, position)` and take that row's `node_type_name`, replacing the `min(n.NODE_TYPE_NAME)` lookup by coordinate and position. The node's type id, tables class, table field and key columns then key off that type name as they do now. Today's lookup gives the same answer only because a routed slot is never polymorphic; reading the exact row stops the query from relying on that.
- **`ServiceEmission.slotRead`**: for a node-id slot, emit a named private static helper on the fetchers class and pass it to `mapTenantLeaves` as a method reference, so the call site stays an expression and the decode is breakpointable statements (development-principles, "Statement form over expression tricks"). The helper takes one leaf, an `Object`:
  - not a `String` (including `null`): return `null`, as today. An absent optional id names no tenant, and the divined-tenant fold skips it.
  - `decodeColumns` answers `null`: raise `NodeIdDecodeFailure.throwStatement(routing.clientException(), routing.nodeIdEncoder(), typeId, typeName, <leaf local>)`, the single-type throw that peeks the prefix. It tells a malformed id apart from an id of another type with the same text every other single-type decode uses.
  - otherwise return the value at the tenant's key position.

  Name the helper per field and slot (for example after the frame method plus the slot's index), so two routed fields in one fetchers class never mint the same name. That removes any need for a per-class deduplicating collector on the store side. Do not route through `CompositeDecodeHelperRegistry`: it is keyed on the classified model's `HelperRef.Decode` and goes when the classifier-side tenant routing goes. The two sides share the message class, which is what keeps them failing the same way.

Nothing else changes. The throw happens while `FrameEmitter.routedKey` evaluates the reads, before `namedTenant`/`divinedTenant` and before any `NoTenant` arm. So a list in which every id is refused never reaches the "names no tenant" refusal or an empty answer. The throw is inside the frame's `try`, so an error channel whose handler claims `GraphitronClientException` claims it, which is what sis's `UgyldigInput` did on RC47, and a field without one surfaces it as a client error. When a request carries more than one refused id, the message names the first one the slot reads reach: slot by slot, and within a slot in list order. Which of several bad ids the message names is not promised, here or at the argument's decode.

No user documentation changes: the tenant-scoping how-to already promises this behaviour, and this item makes the store-sourced path keep that promise.

## Tests

**Unit tier** (`graphitron/src/test/java/no/sikt/graphitron/emit/`, on the pattern of `NodeIdFillEmitterTest`: read the rows from a fact document, compile the emitted helper strictly with the generated `NodeIdEncoder` and `GraphitronClientException`, and call it). The `scenarios/tenant-slots` fact document already carries a `Film` id slot (`filmByNode`) and a composite-key slot (`FilmActorCode`). Pin:
- a good id answers the tenant column's value, at position 0 and at a non-zero position of a composite key;
- `null` and a non-string answer `null`;
- a malformed id raises `GraphitronClientException` with `Invalid node id "<id>" for this argument: not a valid <Type> id`;
- an id of another type raises the `decodes to type "<Other>", expected a <Type> id` message;
- a well-formed id of the right type whose tenant key value is not a value of the tenant column raises the malformed message.

A `ConnectionQuery` test pins that `Node` carries the slot's node type name.

**Execution tier** (`TenantDivinedRoutingExecutionTest`, `graphitron-sakila-example`, multitenant schema). Each case asserts the exact message, the field's path, and that no tenant database was opened:
- `rateFilms` with `"abc!!"`: `not a valid Film id`. This is also the all-refused list, which must not answer `Mutation.rateFilms names no tenant`.
- `rateFilms` with a `FilmActor` id: the other-type message.
- `rateFilmActors` with the existing `TENANT_SLOT_ABC` id (a `FilmActor` id whose tenant slot is `"abc"`): `not a valid FilmActor id`. This is the how-to's own case, on an argument.
- `rateFilms` with a good id followed by a bad one: the bad id's message, and the service does not run.
- `rateFilmsAsRecords` (empty batch answers an empty list) with a bad id: the error, not an empty list.
- A service whose payload's error union claims `GraphitronClientException` through a `GENERIC` handler answers the refusal as the typed error inside the payload, with no top-level error. No `@service` fixture has such a channel today. Add one to `multitenant.graphqls`: a `rateFilmsClaimed` returning a payload whose errors union includes `FilmWriteClientError`, served by a `FilmRatingService` method shaped like `rateFilmsChecked`. This is the sis shape.

## Other solutions we've considered

Answering `null` for a refused id, and leaving the refusal to the argument's decode, was rejected. The tenant fold runs first, and an all-refused list then reaches whatever the field's `NoTenant` arm does with no tenant: `REFUSE` answers the "names no tenant" message, `EMPTY` answers an empty result as if the call had succeeded, and `UNREACHABLE` throws the fold's absent-value error. Each arm would need its own guard, and the read would be the one node-id decode that does not fail as `NodeIdDecodeFailure` says every decode does.

Folding this into R876 was also considered. It stays standalone because the fix is a narrow emitter defect with its own tests, and R876 is a modelling item. One overlap needs coordinating: R876's Mikado node "A root's tenant is the input binding its table's tenant column…", claimed by E, moves `ConnectionQuery`'s slot read, and the type-name join above touches the same query. Whichever lands second rebases onto the other.

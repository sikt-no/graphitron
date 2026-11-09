---
id: R1076
title: "_entities answers a representation with an unknown __typename with an error, not a silent null"
status: In Progress
bucket: bug
priority: 3
theme: codegen-correctness
depends-on: []
created: 2026-10-20
last-updated: 2026-11-09
---

# _entities answers a representation with an unknown __typename with an error, not a silent null

## Goal

A federation `_entities` request whose representation names a `__typename` this subgraph does not
resolve gets an error the client and the router can see, as it did under Graphitron 9 ("Cannot
resolve entity with __typename 'Foo'."), instead of a silent `null` in that slot. The rest of the
batch still resolves. An unknown type in a representation means the supergraph and the subgraph
disagree, which is a deployment fault worth surfacing.

Terms, once: a *subgraph* is one GraphQL service in a federated graph; the *router* splits a client
query across subgraphs using the *supergraph*, the composed schema that records which subgraph resolves
which entity type. When the router needs a subgraph's fields of an entity it already holds, it calls
that subgraph's `_entities` field with a list of *representations*, each a `__typename` plus key
fields, and gets back one answer per representation, in order.

Against the sakila federated example (`graphitron-sakila-example`, `federated-schema.graphqls`), where
`Customer` is an entity and `DoesNotExist` is not a type at all:

```graphql
query ($reps: [_Any!]!) { _entities(representations: $reps) { __typename } }
```

```json
{ "reps": [ { "__typename": "Customer", "id": "<valid Customer id>" },
            { "__typename": "DoesNotExist", "id": "x" } ] }
```

Today:

```json
{ "data": { "_entities": [ { "__typename": "Customer" }, null ] } }
```

After:

```json
{ "data": { "_entities": [ { "__typename": "Customer" }, null ] },
  "errors": [ { "message": "Cannot resolve entity with __typename 'DoesNotExist'.",
                "path": [ "_entities", 1 ] } ] }
```

## Evidence and decision

Surfaced by the sis v9 against v10 differential test (2026-10-19). The user decided (relayed through
the sis session, 2026-10-20) that the client must get an error. sis does not depend on either
behaviour. Nothing in the manual documents today's `null` for an unknown `_entities` type;
`FederationEntitiesDispatchTest.entities_unknownTypename_yieldsNullSlot` pins it, with a javadoc
claiming federation lifts the `null` into its own error at the gateway, which it does not: a `null`
element of `[_Entity]!` is a legal answer.

## Which answers change

The line falls between "this subgraph does not resolve that type" and "this subgraph resolves that
type, but this representation does not name a row". The first is the supergraph and the subgraph
disagreeing, since a correctly composed router only sends a type to a subgraph that declared a
resolvable key for it. The second is per-representation data and stays a silent `null`, which is also
what the node-lookup item (R1146, in Spec) holds the `_entities` path to.

[cols="3,1,1"]
|===
| Representation | Today | After

| `__typename` names no entity type of this subgraph | `null` | `null` + error
| `__typename` names an entity type whose every key is `resolvable: false` (sakila `Language`) | `null` | `null` + error
| representation is `null`, or has no string `__typename` | `null` | `null` + error
| known resolvable type, key matches only a `resolvable: false` alternative, or no alternative | `null` | `null`
| known resolvable type, key value does not decode (garbage node id, wrong arity, not a value of its column) | `null` | `null`
| known resolvable type, tenant outside the request's set, or no entity default tenant | `null` | `null`
| known resolvable type, no such row | `null` | `null`
|===

Messages, the type named as received:

* `Cannot resolve entity with __typename 'Foo'.` for the first two rows. Graphitron 9's text verbatim,
  so the sis differential test compares like with like. A fully non-resolvable type reads the same:
  from the router's side it is a type this subgraph does not resolve.
* `Cannot resolve entity: the representation has no __typename.` for the third.

Each is a field error located at the slot (`path: ["_entities", <index>]`), with that slot `null` and
every other slot resolved as today. The check runs before any dispatch, so a rejected representation
takes no connection and fires no SQL, in single- and multi-tenant builds alike.

## Implementation

* `EntityFetcherDispatchClassGenerator`
  * Emit a `RESOLVABLE_TYPENAMES` constant, `Set.of(...)` over the entities whose
    `EntityResolution.resolvable()` is true, sorted for deterministic output.
  * `fetchEntities` (the `_entities`-only entry point) walks the representations before calling
    `resolveByReps`. For each rep that is `null`, has no `String` `__typename`, or names a type outside
    `RESOLVABLE_TYPENAMES`, it builds a `GraphitronClientException` with the message above and asks the
    router for the slot error (below). It then calls `resolveByReps` unchanged, which already leaves
    every such slot `null`, and returns a `DataFetcherResult` carrying the list and the collected
    errors. Its return type widens accordingly (`CompletableFuture<DataFetcherResult<List<Object>>>`);
    graphql-java and the federation `fetchEntities` hook accept it, and no consumer surface names the
    old type.
  * `resolveByReps` does not change. It is shared with `Query.nodes` (`QueryNodeFetcher.rowsNodes`
    synthesises representations only for type ids it maps, so it never hands in an unknown type), and
    R1146 reshapes that node path; keeping the check in `fetchEntities` keeps the two items apart.
  * The message texts live here as constants: their one home, beside the dispatcher that raises them.
  * Rewrite the class javadoc's last sentence ("federation surfaces its own resolution-failure error"),
    which is the same false claim the test javadoc makes, and add the slot-error contract to the
    `fetchEntities` description.
* `ErrorRouterClassGenerator`: add `clientErrorAt(GraphitronClientException, DataFetchingEnvironment,
  int index)`, returning one `GraphQLError` whose path is the field's path plus `index` and whose
  locations come from `env`. Its message goes through the same configured
  `FetcherErrorResponse.clientErrorMessage` hook, with the same containment, as the client-error arm of
  `surfaceClientErrorOrRedact`; extract that resolution into a private helper both call rather than
  repeating it. It does not call `TenantConnections.markFieldFailed`: the field does not fail, one
  element of it does. This keeps the client-exception marker the single route by which a client error
  reaches the response unredacted.
* `PlanCompileGraph.addGlobalEdges`: the `EntityDispatch` row gains precise edges to
  `GlobalUnitKind.ERROR_ROUTER` and `GlobalUnitKind.CLIENT_EXCEPTION`, both always emitted by
  `FixedRuntimeEmitter`.
* Federation customizer: a consumer who replaces `fetchEntities` gets none of this, as today.

### Implementation notes

* `fetchEntities` hands `resolveByReps` a copy of the representations with every rejected one
  replaced by `null`, which `resolveByReps` already skips. That is what makes "no connection, no
  SQL" hold for `Language`-shaped types too: they are classified entities with a `handle<Type>`
  method, so without the substitution `resolveByReps` would still dispatch them.
* The gate reads the representations as `List<?>`, so a representation that is not an object at all
  takes the "no `__typename`" error instead of the `ClassCastException` the old typed read raised.

## User documentation (first-client check)

`docs/manual/how-to/federation-keys.adoc` gains a short section, and its two `resolvable: false`
remarks (the non-resolvable-key paragraph and the "honoured but advisory" pitfall bullet) are reworded
to say that a type with no resolvable key at all now answers with the error, while a type with a
resolvable key still answers `null` for a representation that matches only the non-resolvable one.

> === What `_entities` answers
>
> Each representation gets one slot in the answer, in the order sent.
>
> * A representation this subgraph resolves, naming a row that exists, gets the row.
> * A representation whose `__typename` this subgraph does not resolve gets `null` and an error at its
>   slot: `Cannot resolve entity with __typename 'Foo'.` That covers a type this schema does not
>   declare, and an entity whose every `@key` is `resolvable: false`. A representation with no
>   `__typename` gets `null` and an error the same way. The router only sends a subgraph types it
>   declared resolvable, so either error means the supergraph and this subgraph disagree, usually a
>   subgraph deployed without recomposing.
> * A representation of a type this subgraph resolves, but whose key names no row, does not decode, or
>   names a tenant outside the request's set, gets `null` with no error, exactly as a missing row does.
>
> The error is a client error, so it reaches the response as written; reword it through
> `FetcherErrorResponse.clientErrorMessage` like any other.

## Tests

Execution tier, `FederationEntitiesDispatchTest` in `graphitron-sakila-example`: the behaviour is a
runtime answer over a real database. Add an `executeRaw` helper beside `execute` (which asserts no
errors) for the error cases.

* Flip `entities_unknownTypename_yieldsNullSlot` to
  `entities_unknownTypename_nullSlotWithAnErrorAtItsPath`: slot `null`, exactly one error, the message
  above, path `["_entities", 0]`, no `Reference:` (not redacted). Replace its javadoc.
* Flip `entities_nonResolvableKey_yieldsNullSlot` (sakila `Language`) to
  `entities_typeWithNoResolvableKey_nullSlotWithAnError`.
* New `entities_unknownTypenameInABatch_errorsItsSlotWhileTheRestResolve`: valid `Customer`, unknown
  type, valid `Film`; slots 0 and 2 resolve, slot 1 is `null`, one error at index 1. This is the goal's
  example as a test.
* New `entities_representationWithoutTypename_nullSlotWithAnError`.
* New: with a `FetcherErrorResponse` whose `clientErrorMessage` rewords, the slot error carries the
  reworded message.
* Unchanged, and the guard that the line falls where the table puts it: `entities_garbageNodeId_yieldsNullSlot`,
  `entities_compositeNodeIdUnderArity_yieldsNullSlotNoError`, `entities_compoundKey_partialMatchYieldsNullSlot`
  and the other per-representation `null` cases keep asserting no error.
* `PlanCompileGraphTest`: the dispatch row's refs now include `ErrorRouter` and
  `GraphitronClientException`.
* Verification build per the repo rule; the example module compiling the emitted dispatcher at Java 17
  covers the new constant and return type.

## Other solutions we've considered

* Fail the whole `_entities` field, as Graphitron 9 did. Rejected: one stale type would null every
  entity in the batch, including the ones this subgraph resolves, and it would treat `_entities`
  unlike every other per-representation outcome, all of which are slot-local.
* Raise the error in `resolveByReps`. Rejected: that method is shared with `Query.nodes`, which never
  passes an unknown type and is being reshaped separately; the check belongs to the one entry point
  that receives router input.
* Throw a plain runtime exception from `fetchEntities`. Rejected: graphql-java's default handler would
  fail the whole field with an "Exception while fetching data" wrapper, and it would bypass the client
  error route and its rewording hook.
* Keep `null` for a type whose every key is `resolvable: false`. Rejected as the default: the router
  never sends such a type to this subgraph, so receiving one is the same disagreement as an unknown
  type. Say if you want the narrower reading; it moves one row of the table and one test.

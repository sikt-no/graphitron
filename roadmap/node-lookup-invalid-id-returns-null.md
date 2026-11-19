---
id: R1146
title: "An invalid id to a Node-returning lookup returns null with no error"
status: Spec
bucket: bug
priority: 2
theme: nodeid
depends-on: []
created: 2026-11-03
last-updated: 2026-11-19
---

# An invalid id to a Node-returning lookup returns null with no error

## Goal

A Node-returning root field (the generated `Query.node(id:)` and `Query.nodes(ids:)`, and any consumer
field of that shape such as `kregNode(id: ID!): Node`) answers a node id that is not well formed with
the same client error every `@nodeId` site gives, and keeps answering `null` for an id that is well
formed but names nothing this subgraph has. A client can then tell "you sent garbage" from "that
object does not exist". A node id is the opaque string graphitron hands out for a `@node` type: the
base64 of `<typeId>:<key values>`, where the type id is the wire prefix naming the type.

Minimal pair against the sakila example schema (`Query.node(id: ID!): Node`, `Customer` is a `@node`
type with a single-column key):

```graphql
{ bad: node(id: "ikke-en-id")                    { __typename } }  # not an id at all
{ key: node(id: "<base64 of Customer:abc>")      { __typename } }  # a Customer id with a key no customer_id can hold
{ gone: node(id: "<base64 of Customer:999999>")  { __typename } }  # well formed, no such row
{ other: node(id: "<base64 of Elsewhere:1>")     { __typename } }  # well formed, a type this subgraph does not own
```

Today all four answer `null` with no error. After this item the first two fail with a client error
(`Invalid node id "ikke-en-id" for this argument: not a valid node id`, and `Invalid node id "<id>"
for this argument: not a valid Customer id`), and the last two stay `null`.

## The defect

In kreg, `kregNode(id: "ikke-en-id") { __typename id }` returns `{"data": {"kregNode": null}}` and no
error, on RC46, RC47 and trunk alike. The kreg session reports that Graphitron 9 answered with a
`DataFetchingException` saying the id is not valid (not re-verified here).

The cause is not that `kregNode` lacks a `@nodeId` directive. Every root field whose element type is
the `Node` interface classifies as `QueryField.QueryNodeField` (single) or `QueryField.QueryNodesField`
(list) by signature, never by name or directive (`FieldBuilder`, the "Relay-style node fetchers are
recognised by signature" branch; the `relay-node` corpus example pins `internalFilmNode(id: ID)`), and
all of them delegate to the same generated `QueryNodeFetcher.getNode` / `getNodes`. `Query.node` itself
answers `null` for a malformed id today, so the sakila schema reproduces the defect without kreg. The
null is deliberate, not an oversight: the dispatcher javadoc ("Returns `null` for null/garbage/unknown
IDs (Relay spec)") and `docs/manual/how-to/global-id.adoc` both promise it, on an opacity argument. The
ruling below reverses that promise for malformed ids, so the docs and the pinned tests change with it.
The `@nodeId` decode-failure error shipped earlier covers argument sites; the node dispatch never
decodes an argument, it peels the type id and hands the id to `EntityFetcherDispatch`, so nothing
reached it.

## Decision

Ruled by Alf on 2026-11-04: an id that is not well formed is an error, and an id that is well formed
but matches nothing this subgraph has yields `null`. "Not well formed" means the id cannot be read as
a node id at all; "matches nothing" covers a well-formed id whose row is absent. This item delivers
the first half, since the second already holds today.

## Where the line falls

The node lookup can only judge an id against the nodes this subgraph serves by id: the node types it
fetches by their id, known at generation time, each with its wire type id and key columns. A node
declared `@node(resolvable: false)`, a stub for a type another subgraph owns, is not one of them, and
neither is a node whose `id` carries `@field`, which reads a column and encodes no node id. Well
formed therefore has two checkable tiers, and a third outcome that is not a judgement at all.

| Input | Today | After |
|---|---|---|
| Not decodable: not base64url, or decodes to text with no `:`, or an empty type id | `null` | client error, `not a valid node id` |
| Type id is that of a node this subgraph serves by id (`@node(typeId:)` overrides honoured), but the key is wrong: wrong arity, or a value no column of that key can hold (`NodeIdEncoder.decodeColumns` answers `null`: wrapped number, non-numeric, padded, converter-rejected, tenant slot that is not a value of its column, legacy-shape id with an unparseable dropped value) | `null` | client error, `not a valid <Type> id` |
| Type id is not that of a node this subgraph serves by id: a type declared elsewhere, a `@node(resolvable: false)` stub, a node whose `id` is a column mapping | `null` | `null` (not ours) |
| A node served by id, valid key, no row | `null` | `null` |
| Valid key naming a tenant outside the request's tenant set (multi-tenant), or a connection key the request's source refuses (`KeyNotPermitted`) | `null` | `null` (matches nothing) |
| `null` element in `ids` (only where the schema makes elements nullable) | `null` | `null` |

The federation caution is met by the third row: an id carrying a type id this subgraph does not
serve is never an error, however it was minted, so a gateway fanning `node(id:)` out to every
subgraph gets `null` from the ones that do not own the type. The unverified fact is whether the
gateway calls non-owning subgraphs at all; that affects how often the third row is hit, not where the
line falls (see the open questions). The residual ambiguity is unavoidable and accepted: a string that
is not an id but happens to base64-decode to `x:y` reads as a foreign type `x` and answers `null`.

In `nodes(ids:)` the judgement is made per id. A malformed id leaves its own position `null` and puts
the client error at that position's path (`["nodes", 1]`), and the other ids resolve. Where a schema
declares the elements non-null (`[Node!]`), graphql-java carries that `null` up to the field, so the
schema's own nullability decides how far one bad id reaches, not the generator. `node(id:)` has one
id, so its error is the field's. Both hold alike in a build with a tenant column and in one without.
The grain inside `nodes` is not part of the 2026-11-04 ruling and awaits one (see the open questions).

A later rule reads as if it covered the third row. The polymorphic lookup rule in
`docs/architecture/reference/code-generation-triggers.adoc` (the source-lift section: "An id naming no
participant is malformed for the field, a client error", Alf, 2026-11-14) is about a node id argument
on a field returning a union or an interface, which a root field returning `Node` also is. This item
keeps the third row `null` on the 2026-11-04 ruling, which was given for this field and carries the
federation caution, and that page gains a sentence saying where the node lookup departs from the rule
(see the open questions).

The `_entities` path must not change: federation expects a null slot for a bad representation, and
`FederationEntitiesDispatchTest` pins it (`entities_garbageNodeId_yieldsNullSlot`,
`entities_compositeNodeIdUnderArity_yieldsNullSlotNoError`,
`entities_compositeNodeIdOverArity_yieldsNullNotWrongRow`,
`entities_nodeIdKeyValueNotOfItsColumn_yieldsNullSlotBesideAValidRep`), as does
`TenantDivinedRoutingExecutionTest.entities_tenantSlotThatDoesNotDecode_nullsThatSlot_andTheRestResolves`.
The failure therefore belongs to the node fetcher, not to the representation decode the entity
dispatch shares between its read paths (`HandleMethodBody.emitAlternativeCascade`).

## Implementation

The judgement is made once per id, in the node fetcher, before anything is loaded: a wire format's
failure mode is classified at the fetcher boundary and never below it
(`docs/architecture/principles/development-principles.adoc`, "Boundaries decode and encode; the
interior is typed"). The client error is the generated `GraphitronClientException`, and its text comes
from `NodeIdDecodeFailure`, the one home for the invalid-node-id message.

What is there today. `QueryNodeFetcherClassGenerator` emits `QueryNodeFetcher` in one of two shapes,
chosen by whether the build's connection key (the value a request's database connection is acquired
by) has a tenant part:

* Without a tenant column (`untenanted`): `representationOf(id)` peeks the type id
  (`NodeIdEncoder.peekTypeId`), maps it to a typename (`EntityFetcherDispatch.typenameForTypeId`) and
  builds the representation `{__typename, id}`, answering `null` wherever either step fails. `getNode`
  reads it through `EntityFetcherDispatch.load`; `getNodes` builds one slot per id through
  `EntityFetcherDispatch.loadSlot` and combines them with `inOrder`, so each slot already answers its
  own failure at its own path.
* With one: `fetchById` and `rowsNodes` do the same peek and lookup and hand the representations to
  `EntityFetcherDispatch.resolveByReps`. `rowsNodes` is the batch function of a DataLoader `getNodes`
  loads every id through, so a throw inside it fails every id that happened to share the batch.

In both shapes the id's key is only decoded later, inside the entity dispatch
(`HandleMethodBody.emitDecode`, the `NodeId` arm: `NodeIdEncoder.decodeColumns` over the node's key
columns, a `null` skipping the representation). That decode stays as it is, because `_entities` shares
it.

The change:

* **A node query** (new, in `no.sikt.graphitron.emit` beside `NodeIdEncoderQuery`): one row per node
  this subgraph serves by id, with its type name, its wire type id, the `Tables` class and field its
  table is reached through, and its key columns in key order. That is `NodeIdEncoderQuery.Node`'s
  shape over a narrower population: `graphitron_node` rows that are `resolvable` and whose `id` binds
  `NODE_ID` at the type's table, the predicate `EntityKeyQuery.read` states as `servedById`. State the
  predicate once and have both queries use it, so the node fetcher's map and the entity dispatch's node
  id alternative cannot disagree while both exist. No stored relation is added: this is a filter and a
  join over facts the store already holds, and stating "served by id" as a fact of its own is the node
  query's business under R876 (see the sequencing section).
* **`QueryNodeFetcherClassGenerator`** takes the node query's rows (`GraphQLRewriteGenerator`'s
  `QUERY_NODE_FETCHER` arm reads them, as its `NODE_ID_ENCODER` arm reads `NodeIdEncoderQuery`) and
  emits, in both shapes, one private method that judges an id, replacing the `peekTypeId` plus
  `typenameForTypeId` pair at all three call sites (`representationOf`, `fetchById`, `rowsNodes`). Given
  a non-null id it answers the GraphQL typename for a node served by id, `null` for a type id that is
  not one, and throws the client error in the two malformed cases:
  * `peekTypeId` answers `null` or an empty type id: the undecodable message.
  * the type id is a served node's and `NodeIdEncoder.decodeColumns(<typeId>, id, <the node's key
    columns as Tables constants>)` answers `null`: the single-type message, `not a valid <Type> id`.
    This is the call the entity dispatch's decode makes for the same node, over the same
    `graphitron_node_keycolumn` rows, so legacy key shapes, converters and a tenant slot that is not
    a value of its column come for free and the two cannot drift.

  The shape is the one `NodeIdDecodeEmitter.dispatched` already emits for a `@nodeId` argument over
  several node types: peek, branch per type id, decode. It differs only in its last arm, where a type
  id naming none of them is `null` here and an error there. The emitted code compiles at Java 17.
* **`node`, both shapes.** The judging method throws and the existing routes surface it: without a
  tenant column `getNode` already turns a throw into a failed future, which the fetcher's
  `asyncWrapTail` routes; with one, the throw leaves `getNode` and the fetcher's `noChannelCatchArm`
  routes it. Both end in `ErrorRouter.surfaceClientErrorOrRedact`, which surfaces a
  `GraphitronClientException` with its own message, path and locations and never redacts it (a
  configured `FetcherErrorResponse.clientErrorMessage` may reword it, as at every other site).
  `TypeFetcherGenerator.buildQueryNodeFetcher` does not change.
* **`nodes`, without a tenant column.** In `getNodes`' loop a malformed id's slot is answered the way
  `EntityLoaders.fetchEntities` answers a representation it cannot resolve: a completed slot holding
  `null` and `ErrorRouter.clientErrorAt(<the client error>, env, i)`, which locates the error at the
  slot's path and leaves the field unfailed. `inOrder` already gathers every slot's errors.
* **`nodes`, with a tenant column.** `getNodes` judges each id by its index before `loader.load`. A
  malformed id's element completes at once as an envelope carrying `ErrorRouter.clientErrorAt(...)`
  and no data (every resolved element is already a `DataFetcherResult` envelope that graphql-java
  unwraps per element, which is how each hands its tenant down); the id never reaches the loader, so
  `rowsNodes` receives only ids that passed and nothing in the batch function refuses one. An id whose
  tenant is well formed but outside the request's set, or one naming a tenant the source refuses, stays
  `null` through the existing skips in `HandleMethodBody` (`tenantKeyOrNull`, `dslForIfPermitted`). No
  connection is opened for a malformed id in either shape.
* **`EntityFetcherDispatchClassGenerator`** loses `buildTypenameForTypeIdMethod` and
  `TYPENAME_FOR_TYPE_ID_METHOD`, the node fetcher being their only caller. Nothing else in the entity
  dispatch changes: `load`, `loadSlot`, `resolveByReps`, `HandleMethodBody` and `SelectMethodBody`
  keep reading a representation exactly as they do, and `_entities` keeps its null slots.
* **`NodeIdDecodeFailure`** gains the undecodable-id message, `Invalid node id "<id>" for this
  argument: not a valid node id`, beside the existing ones. The served-node case uses the existing
  single-type text so it matches `@nodeId` sites word for word. The multi-candidate message is not
  used: it would list every node type in the schema, and the wrong-type branch it carries is the
  not-ours case, which here is `null`.
* **Javadoc.** Rewrite `QueryNodeFetcherClassGenerator`'s class javadoc and the emitted javadoc of
  `getNode` and `getNodes` in both shapes and of `representationOf`, which say "null/garbage/unknown IDs
  (Relay spec)" and "opacity says we never leak decoding errors", to the new contract. The
  `NullOnMismatch` remark in `CallSiteExtraction`'s javadoc names `Query.node` and `Query.nodes` beside
  `_entities` as dispatcher-driven nulls; after this only `_entities` is.
* **User docs**, `docs/manual/how-to/global-id.adoc`. Find each passage by its text:
  * the "Decoding errors stay opaque to clients on the dispatcher path" bullet: a malformed id on
    `node` or `nodes` is now the client error, and an id of a type this subgraph does not serve, or
    of no row, stays `null`;
  * the "This is intentionally different from the dispatcher path" paragraph and its
    `NullOnMismatch` contract: the asymmetry left is that an argument asserts one type, so an id of
    another type is an error there, while `node` answers `null` for a type that is not this
    subgraph's and for a missing row;
  * the key-value paragraph's "`null` on `node`, `nodes` and `_entities`": the client error on
    `node` and `nodes`, at the id's own position in `nodes`, and `null` on `_entities`;
  * "Null IDs, base64 errors, and unknown typeIds all return `null`" in the `Query.node` walkthrough;
  * the two closing bullets, "the same mismatch returns `null`" and "`null` on the lookups".

  `docs/manual/reference/directives/node.adoc` (the federation stubs section) gains a clause saying a
  stub's id on `node` answers `null`, well formed or not. `docs/architecture/reference/code-generation-triggers.adoc`
  gains the sentence beside the polymorphic lookup rule described above. No roadmap vocabulary in any
  of it.

The id is decoded twice on its way to a row: once in the node fetcher to judge it, and once in the
entity dispatch's own decode of `{__typename, id}`. That is the price of leaving the loads where they
are, and it is transitional (next section).

## Sequencing against R876

R876's node "`Query.node` and `_entities` each read their own query" (claimed by C, open) gives the
node fetcher its own unit, read from a node query: its own type-id map, loads and tenant routing. Its
child "`Query.node` renders its own unit from a node query" is blocked on "The dispatch's read
emitters render from a plain keyed read", and lists `typenameForTypeId` among what it removes. The
separation is ruled (`docs/architecture/explanation/fact-model.adoc`, "Federation knows keys, not
`Node.id`", 2026-11-12): node resolution and entity resolution are separate lookups with a query each.

This item takes the half of that child the blocker does not hold back. The blocker is about loads; the
type-id map and the judgement of an id need only the node query. So here the node fetcher gets the
node query and its map, `typenameForTypeId` goes, and the loads and tenant routing stay on the entity
dispatch for R876 to move. Building the judgement on `EntityFetcherDispatch` instead would add new code
to a method R876 deletes and tie it to the entity side's decode, against the ruling.

What each order means:

* **This item first** (recommended: it is a live priority-2 defect and R876's child is blocked). The
  implementing change also edits R876's body: the blocked child's list drops `typenameForTypeId` and
  names the node query as existing, and the parent node records that the node query now feeds the map.
  When R876 then gives the node fetcher loads that take key columns, the judging method's
  `decodeColumns` result becomes the key it loads by, and the second decode goes.
* **R876's child first.** The node fetcher already has its own map and decode; this item is then the
  refusal on that decode and the slot handling in `nodes`, with no node query to add and nothing to
  remove from the entity dispatch. Re-read this section at pickup.

Either way the claim on that node is C's, so the bite is agreed with C's session before work starts
(see the open questions).

## Retired vocabulary

* `typenameForTypeId` (the method emitted on `EntityFetcherDispatch`)
* `EntityFetcherDispatchClassGenerator.TYPENAME_FOR_TYPE_ID_METHOD` and `buildTypenameForTypeIdMethod`
* "`NullOnMismatch` contract" as a description of `Query.node` and `Query.nodes` (it stays true of
  `_entities`)

## Tests

Execution tier, `graphitron-sakila-example`, with `executeRaw` and `assertFieldNullified` for the error
cases. The behaviour is a runtime answer over a real database, which is what that tier is for; no
generated-body string assertions. The example covers both shapes: `GraphQLQueryTest` runs the schema
without a tenant column (`nodes(ids: [ID!]!): [Node]`), `TenantDivinedRoutingExecutionTest` the
multi-tenant one (`nodes(ids: [ID!]!): [Node]!`).

* New, `node`: `"not-a-valid-base64-id"` asserts the undecodable text, no `Reference:`, field
  nullified; a `Customer` id carrying key `abc` and a composite `FilmActor` id of one part assert
  `not a valid <Type> id`.
* New, `nodes`: one good and one malformed id assert the good one resolves, the bad position is
  `null`, and exactly one error sits at that position's path naming the bad id; a mix of unknown type
  id, missing row and valid id resolves per position with no error. The same pair in the multi-tenant
  schema, where the field is `[Node]!` and must not be nullified.
* New, not ours: a `StudyLevel` id (the example's `@node(resolvable: false)` stub), well formed and
  with a key no column can hold, answers `null` with no error on `node`.
* Must change from `null` to the client error on `node`: in `GraphQLQueryTest`,
  `node_garbageInput_returnsNull`, `node_compositeKeyWrongArityId_returnsNull`,
  `node_keyValueWrapsItsColumn_returnsNullNotTheWrappedRow`, `node_nonNumericOrPaddedKeyValue_returnsNull`;
  in `TenantDivinedRoutingExecutionTest`, `node_tenantSlotThatDoesNotDecode_answersNull_withoutAConnection`
  (still opens no connection); in `LegacyNodeKeyExecutionTest`,
  `aLegacyIdWithAnUnparseableDroppedValue_isRejectedAsMalformed`; in `ConverterFilterExecutionTest`,
  `node_orgCodeSlotTheConverterCannotTake_answersNull`. Rename each so the name states the new outcome.
* Must gain an error at the bad position on `nodes`, the position staying `null` and the rest
  resolving: in `GraphQLQueryTest`, `nodes_garbageId_returnsNullEntry`,
  `nodes_compositeKeyWrongArityIds_nullSlotsPreservePositions`,
  `nodes_undecodableKeyValues_nullSlotsWhileTheRestResolve`; in `TenantDivinedRoutingExecutionTest`,
  `nodes_tenantSlotThatDoesNotDecode_nullsThatSlot_andTheRestResolves` (still opens no connection for
  the bad id); in `ConverterFilterExecutionTest`, `nodes_paddedOrgCodeSlot_isNotAnAliasOfTheRealId`
  (the padded slot is `null` with an error, the real id beside it resolves).
* Kept, the explicit "stays null" set: `node_unknownTypeId_returnsNull`,
  `node_validPrefixNoSuchRow_returnsNull`, `nodes_unknownTypeId_returnsNullEntry`,
  `nodes_validPrefixNoSuchRow_returnsNullEntry`, `nodeAndNodes_aSourceRefusingTheKey_answerNullWithNoError`,
  `node_idOfATenantOutsideTheSet_answersNull_likeAnUnknownId`,
  `nodes_batchSpanningTheSetsEdge_resolvesThePermittedTenant_andNullsTheOther`,
  `nodes_refusedTenantsSlot_staysNullAndItsSubtreeNeverRuns`, and
  `DmlBulkMutationsExecutionTest.node_byAStaleInventoryUnitId_resolvesToNull`.
  `nodes_paddedBase64Id_canonicalizesAndResolves` keeps resolving: base64 padding is not malformed.
* Unchanged, and the guard that the entity decode did not move: the `_entities` cases named under
  "Where the line falls" stay `null`.
* Consumer-shaped field: the sakila example has no `Node`-returning root under another name, so add
  one (`internalNode(id: ID): Node`, the shape of the `relay-node` corpus example) to the example
  schema and assert it errors on a malformed id too. That is the kreg report as a test. Its argument
  is named `id`, which is the name the node fetcher reads.
* The node query, pipeline tier, beside `NodeIdEncoderQueryTest` and over the same fact document
  (`scenarios/synthesized-node-type-id`, which carries a `@node(resolvable: false)` stub): every node
  served by id draws a row with its type id and key columns in key order, and the stub draws none.
  `docs/architecture/how-to/testing.adoc`, "What a test is built out of", says how such a test reads
  a fact document.
* The `relay-node` corpus example stays as is; classification does not change.
* Verification build per the repo rule; the example module compiling the emitted fetcher at Java 17
  covers the new emitted method.

## Other solutions we've considered

* Throw from the entity dispatch's decode, where `decodeColumns` answers `null`
  (`HandleMethodBody.emitDecode`). Rejected: that decode is shared with `_entities`, where federation
  wants a null slot, and it would turn gateway entity resolution into errors.
* Signal strictness through the synthesised representation map. Rejected: a flag in the data to change
  control flow in a shared method, where a judgement at the boundary states the difference.
* Put the judging method on `EntityFetcherDispatch`, in place of `typenameForTypeId`, over the map
  built from `EntityKeyQuery`'s node id alternatives. The smallest diff, rejected: it extends a method
  and a prepended alternative R876 removes, and it makes the entity side the owner of a decode the
  2026-11-12 ruling gives to node resolution.
* Put it in `NodeIdEncoder`, rendered from `NodeIdEncoderQuery`. Rejected: the encoder serves every
  node and every `@nodeId` site, stubs included, and "served by id" is a fact about the node lookup's
  population alone.
* Fail the whole `nodes` field on one malformed id, as one bad element in a `@nodeId` list filter fails
  its field. Not chosen: a filter's value is one set, a `nodes` answer is positional; in the
  multi-tenant shape the throw would come from a DataLoader batch function and take down whichever ids
  happened to share the batch; and on a `[Node]!` field it nulls the field's parent, which on `Query`
  is the whole response.
* Give `Query.node` an error only for the undecodable case and keep `null` for a wrong-shaped key of a
  served node. Rejected: it contradicts the ruling (the id cannot be read as a node id of its own type)
  and would leave the sakila `Customer:abc` case silent.

## Open questions for the user

* Inside `nodes`, does a malformed id fail its own position or the whole field? Not covered by the
  2026-11-04 ruling. Recommendation: its own position, as written above. Say if you want the whole
  field; the plan then throws from `getNodes` before any slot loads and the `nodes` tests assert a
  nullified field.
* Does the 2026-11-14 polymorphic lookup rule ("an id naming no participant is malformed for the
  field") cover a root field returning `Node`? Read literally it makes the third row of the table an
  error, against the 2026-11-04 ruling and the federation caution. Recommendation: the node lookup is
  the stated exception, since it asks whether an id resolves here at all, and
  `code-generation-triggers.adoc` says so beside the rule. This needs Alf or Filip to confirm, and the
  confirmation enters that page the day it is given.
* Does this item take the node query and the type-id map out of R876's claimed node, as the sequencing
  section recommends, or wait for C to land it? To be agreed with C's session before Ready.
* Does the federation gateway call `node(id:)` on subgraphs that do not own the id's type, and with what
  id? Not verified. The spec is safe either way (a foreign type id is `null`), but if the gateway sends
  an id scheme graphitron cannot peel (not `<typeId>:` base64) to every subgraph, those would now be
  errors; the answer decides whether the undecodable row needs a carve-out. Recommendation: ship as
  specified and confirm against kreg's gateway before Ready.
* A node served by id whose only id key is `@key(fields: "id", resolvable: false)`: its type id is in
  the map, so a wrong-shaped key errors, while a valid id still answers `null`, because the entity
  dispatch skips a key alternative that is not resolvable. That `null` is today's behaviour and is
  R876's to change when the node fetcher stops reading through entity keys. Recommendation: accept it
  here. Say if you want such a type treated as not ours until then.
* Message for the undecodable case: `not a valid node id` with no candidate list, recommended for
  brevity and to avoid enumerating the schema. Say if you want the type list.
* Release note: consumers that rely on `null` for garbage ids (graphitron 10 so far) change behaviour;
  recommend noting it in the release notes as a bug fix, not a breaking change.

Provenance: found by the kreg Graphitron 10 port session (kreg branch `worktree-kreg-g10` at `0d9997fd39`), replaying recorded Graphitron 9 requests against 10.0.0-RC46, RC47 and a trunk snapshot (`claude/graphitron-rewrite` at `06d71b62b`), 2026-11-03; identical on all three.

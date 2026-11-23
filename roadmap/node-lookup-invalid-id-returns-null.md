---
id: R1146
title: "An invalid id to a Node-returning lookup returns null with no error"
status: Ready
bucket: bug
priority: 2
theme: nodeid
depends-on: []
created: 2026-11-03
last-updated: 2026-11-23
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
`DataFetchingException` saying the id is not valid. Graphitron 9's source bears that out, and shows it
failed the field in both cases this item tells apart: at tag `v9.3.0`, `NodeIdStrategy.getTypeId`
throws for an id that is not base64 ("contains illegal characters") or decodes to text with no `:`
("is not a valid id"), and the generated node fetcher throws for a type id naming none of the
schema's node types (`FetchNodeMethodGenerator`, the `default:` arm of its switch, "Could not resolve
input ... within type ...").

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

Ruled by Filip on 2026-11-20: inside `nodes(ids:)` a malformed id fails its own position, not the
field. That position is `null` with the client error at its path, and the other ids are answered. It
is how GraphQL itself treats an error in a list: the failing element is `null`, the error's path
carries the element's index, and the `null` travels further up only where the schema declares the
element or the field non-null.

Ruled by Filip on 2026-11-20: a well-formed id whose type id is not one this subgraph serves answers
`null`, confirming the 2026-11-04 ruling against the later polymorphic lookup rule (see "Where the
line falls").

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
subgraph gets `null` from the ones that do not own the type. Whether a gateway calls non-owning
subgraphs at all is not verified, and nothing here waits on it. For an id graphitron minted it only
decides how often the third row is hit. For an id of another scheme, one this subgraph cannot peel,
the first row applies and the answer is the client error: a subgraph cannot tell such an id from
garbage, so no carve-out could spare it without sparing every undecodable id, which is the 2026-11-04
ruling undone. Graphitron 9 already answered that row with an error, and the third row too (see "The
defect"), so on those two rows a gateway that worked in front of a Graphitron 9 subgraph meets no
error it did not meet before, and stops meeting the one for an unknown type id. The residual ambiguity
is unavoidable and accepted: a string that is not an id but happens to base64-decode to `x:y` reads as
a foreign type `x` and answers `null`.

In `nodes(ids:)` the judgement is made per id. A malformed id leaves its own position `null` and puts
the client error at that position's path (`["nodes", 1]`), and the other ids resolve. Where a schema
declares the elements non-null (`[Node!]`), graphql-java carries that `null` up to the field, so the
schema's own nullability decides how far one bad id reaches, not the generator. `node(id:)` has one
id, so its error is the field's. Both hold alike in a build with a tenant column and in one without.
That grain is the 2026-11-20 ruling above, and
`docs/architecture/reference/code-generation-triggers.adoc` states it beside the node id lookup rule.

A later rule reads as if it covered the third row. The polymorphic lookup rule in
`docs/architecture/reference/code-generation-triggers.adoc` (the source-lift section: "An id naming no
participant is malformed for the field, a client error", Alf, 2026-11-14) is about a node id argument
on a field returning a union or an interface, which a root field returning `Node` also is. Filip
ruled on 2026-11-20 that the node lookup is the exception: the third row stays `null`, as the
2026-11-04 ruling has it. A subgraph only knows its own type ids, so it cannot tell another subgraph's
type from a mistyped or invented one, and every miss answers alike. That page states the exception
beside the rule.

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

* **"Served by id" becomes a stored fact** (new, in `graphitron-model.sql`):
  `graphitron_node_served_by_id`, one row per node type this subgraph fetches by its id, which is a
  `graphitron_node` row that is `resolvable` and whose own `id` binds `NODE_ID` at the type's table.
  That is the predicate `EntityKeyQuery.read` evaluates today as its local condition `servedById`. It
  moves into the model because this item gives it a second reader: a fact two uses need is a relation
  both read, each with its own query, and neither a condition they share nor one each repeats
  (`docs/architecture/explanation/fact-model.adoc`, "One base, many views"). What the relation owes,
  per `docs/architecture/explanation/modeling-discipline.adoc`:
  * It is derived from facts the store holds, so it is written in the anchoring phase from a rule
    view, `graphitron_node_served_by_id_rule`, the way `graphitron_occurrence_producer_node` is
    written from `graphitron_occurrence_producer_node_rule`. Its stage is its own anchor stage in
    `GraphitronAssemblyCapture`, after the stages that write the occurrence bindings it reads, where
    `EntityKeyAlternatives` already runs for the same reason. It is not a stage of the entity keys:
    node resolution is not a federation key. Confirm which stage writes a node's `id` binding before
    placing it; reads go upstream only.
  * It is a table at `graphitron_node`'s grain, keyed by graph and type, and not a column on
    `graphitron_node`, whose stage runs before the bindings exist. It references `graphitron_node`
    and the `id`'s row in `graphitron_occurrence_binding`, cascading on both, so a node that stops
    being a node, or whose `id` stops carrying a node id, stops being served.
  * Its owner and grain are declared in `meta_relation` (`MetaDeclarationGateTest` refuses it
    otherwise), and its stage marks what it wrote with the reading's time and sweeps the rest.
  * The rule is today's predicate exactly, so no population changes when the readers move onto it.
  * `graphitron_node.resolvable`'s column comment says the column is whether this subgraph fetches
    the node by its id. That is the new relation's fact; the comment is corrected to what the column
    holds, the resolvability the author wrote, and names the relation for the other.
* **`EntityKeyQuery.read`** reads the relation in the two places it evaluates `servedById` today, the
  node it attaches to a type and the entity disjunct, in its own statement. Its rows do not change,
  which `EntityKeyQueryTest` and the `_entities` execution tests hold.
* **A node query** (new, in `no.sikt.graphitron.emit` beside `NodeIdEncoderQuery`): one row per row of
  `graphitron_node_served_by_id`, with its type name, its wire type id, the `Tables` class and field
  its table is reached through, and its key columns in key order. That is `NodeIdEncoderQuery.Node`'s
  shape over a narrower population, in a record and a statement of its own that share nothing with
  `EntityKeyQuery` or `NodeIdEncoderQuery` but the `ColumnRef` a key column is read as. It carries
  nothing a load needs beyond that; R876 widens it when the node fetcher gets loads of its own.
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
  The method names the generated `GraphitronClientException` and `NodeIdEncoder`; the
  `QUERY_NODE_FETCHER` arm of `PlanCompileGraph` already states the edge to the encoder, and any edge
  the fetcher newly needs is added there and nowhere else.
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
  stub's id on `node` answers `null`, well formed or not.
  `docs/architecture/reference/code-generation-triggers.adoc` already states both 2026-11-20 rulings
  beside the polymorphic lookup rule. Its "a node this subgraph serves by id" gains the name of the
  relation that states it, `graphitron_node_served_by_id`, so the term the rule turns on is defined
  where every reader of the store can see it. No roadmap vocabulary in any of it.

The id is decoded twice on its way to a row: once in the node fetcher to judge it, and once in the
entity dispatch's own decode of `{__typename, id}`. That is the price of leaving the loads where they
are, and it is transitional (next section).

## Sequencing against R876

R876's node "`Query.node` and `_entities` each read their own query" (claimed by C, open) gives the
node fetcher its own unit, read from a node query: its own type-id map, loads and tenant routing. Its
child "`Query.node` renders its own unit from a node query" is blocked on "The dispatch's read
emitters render from a plain keyed read". The
separation is ruled (`docs/architecture/explanation/fact-model.adoc`, "Federation knows keys, not
`Node.id`", 2026-11-12): node resolution and entity resolution are separate lookups with a query each.

This item takes the half of that child the blocker does not hold back. The blocker is about loads; the
type-id map and the judgement of an id need only the node query. So here the node fetcher gets the
node query and its map, `typenameForTypeId` goes, and the loads and tenant routing stay on the entity
dispatch for R876 to move. Until R876 moves them the node query and `EntityKeyQuery` both need to
know which nodes are served by id, which is why that fact is stored here and not left for R876 to
state: R876's child inherits a relation, and when it removes `EntityKeyQuery`'s prepended alternative
and resolvable-node disjunct, the node query is the relation's one remaining reader. Building the judgement on `EntityFetcherDispatch` instead would add new code
to a method R876 deletes and tie it to the entity side's decode, against the ruling.

This item lands first and takes the node query from R876 (Filip, 2026-11-20): it is a live
priority-2 defect and R876's child is blocked. R876's blocked child records the split: the node query,
the type-id map and the removal of `typenameForTypeId` are this item's, and its own loads and tenant
routing start from this item's node query without writing a second one. When R876 then gives the node
fetcher loads that take key columns, the judging method's `decodeColumns` result becomes the key it
loads by, and the second decode goes.

At pickup, re-read R876's node: if its child has landed regardless, the node fetcher already has its
own map and decode, and this item is then the refusal on that decode and the slot handling in `nodes`,
with no node query to add and nothing to remove from the entity dispatch. The relation is then
needed only if `EntityKeyQuery` still evaluates the predicate beside the node query; where the node
query is its one reader, the predicate stays in that query.

## Retired vocabulary

* `typenameForTypeId` (the method emitted on `EntityFetcherDispatch`)
* `servedById` (the local condition in `EntityKeyQuery.read`, replaced by a read of
  `graphitron_node_served_by_id`)
* `EntityFetcherDispatchClassGenerator.TYPENAME_FOR_TYPE_ID_METHOD` and `buildTypenameForTypeIdMethod`
* "`NullOnMismatch` contract" as a description of `Query.node` and `Query.nodes` (it stays true of
  `_entities`)

## Tests

Execution tier, `graphitron-sakila-example`, with `executeRaw` and `assertFieldNullified` for the error
cases; `assertFieldNullified` is private to `GraphQLQueryTest`, so the other test classes named below
assert the nullified field and the error's path in their own terms. The behaviour is a runtime answer over a real database, which is what that tier is for; no
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
  is named `id`: a node fetcher decodes the argument named `id`, or `ids` for a list, which the store
  states in `graphitron_occurrence_producer_node`, and a Node-returning root field with neither is
  refused (`NODE_FETCHER_WITHOUT_ID`).
* The relation, as a fact document's own claim: an `@expectEquals` over
  `graphitron_node_served_by_id` in a document that holds a node served by id, a
  `@node(resolvable: false)` stub and a resolvable node whose `id` carries `@field`. The first draws
  a row and the other two are the near misses that draw none; the `@field` node is the case
  `graphitron_node.resolvable` alone would get wrong, so it is what pins the rule.
  `scenarios/synthesized-node-type-id` already holds the first two. Add the third there only if its
  existing claim over `graphitron_synthesized_node_type_id` still states what that document is for
  with the extra node in it; otherwise the claim gets a document of its own.
  `docs/architecture/how-to/testing.adoc`, "What a test is built out of", says where a fact document
  goes and how a test reads one.
* The node query, pipeline tier, beside `NodeIdEncoderQueryTest` and over that document: each row
  carries its node's type id and its key columns in key order. The population is the relation's and
  is claimed there, so this test asserts what a row holds and not which nodes draw one.
* `EntityKeyQueryTest` stays green unchanged: the entity query's rows are the same read off the
  relation as off its own condition.
* The `relay-node` corpus example stays as is; classification does not change.
* Verification build per the repo rule; the example module compiling the emitted fetcher at Java 17
  covers the new emitted method.

## Other solutions we've considered

* Have the node query and `EntityKeyQuery` share the `servedById` condition, stated once. Rejected:
  `fact-model.adoc` ("One base, many views") rules out a jOOQ condition two uses of the store share
  so that they cannot disagree, since it hides in Java a fact the model is missing.
* Have the node query write the predicate in its own statement and leave `EntityKeyQuery`'s as it
  is, adding no relation. The smaller change, not chosen: for as long as both exist, two readers
  each evaluate one predicate, which is the case the same page moves into the model, and nothing
  written grants an exception for a reader R876 is due to delete. It is the right plan only with a
  ruling from Alf or Filip that two statements of one predicate may stand until R876 lands.
* Derive the relation from `graphitron_occurrence_node_id`, requiring the `id`'s node type to be
  the type itself, in place of the binding's kind. Not chosen here: it is narrower than today's
  predicate for an `id` written `@nodeId(typeName:)` naming another node over the same table, and
  this item moves the entity side onto the relation on the promise that its rows do not change.
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
  its field. Rejected by the 2026-11-20 ruling: a filter's value is one set, a `nodes` answer is positional; in the
  multi-tenant shape the throw would come from a DataLoader batch function and take down whichever ids
  happened to share the batch; and on a `[Node]!` field it nulls the field's parent, which on `Query`
  is the whole response.
* Answer a type id that is not ours with a client error, as the polymorphic lookup rule does for an
  id naming no participant. Rejected by the 2026-11-20 ruling: a gateway asking every subgraph for one
  id would get an error from each subgraph that does not own it.
* Answer `null` only for a type declared here as a `@node(resolvable: false)` stub, and a client error
  for any other unknown type id. Not chosen: it helps a gateway only if every subgraph declares a stub
  for every node type in the whole graph.
* Give `Query.node` an error only for the undecodable case and keep `null` for a wrong-shaped key of a
  served node. Rejected: it contradicts the ruling (the id cannot be read as a node id of its own type)
  and would leave the sakila `Customer:abc` case silent.

## Open questions for the user

* A node served by id whose only id key is `@key(fields: "id", resolvable: false)`: its type id is in
  the map, so a wrong-shaped key errors, while a valid id still answers `null`, because the entity
  dispatch skips a key alternative that is not resolvable. That `null` is today's behaviour and is
  R876's to change when the node fetcher stops reading through entity keys. Recommendation: accept it
  here. Say if you want such a type treated as not ours until then.
* Message for the undecodable case: `not a valid node id` with no candidate list, recommended for
  brevity and to avoid enumerating the schema. Say if you want the type list.
* Release note: consumers that rely on `null` for garbage ids (graphitron 10 so far) change behaviour;
  recommend noting it in the release notes as a bug fix, not a breaking change. The note also says
  what a consumer coming from Graphitron 9 sees: the error it had for an undecodable id, and `null`
  where 9 failed the field for an unknown type id.

Provenance: found by the kreg Graphitron 10 port session (kreg branch `worktree-kreg-g10` at `0d9997fd39`), replaying recorded Graphitron 9 requests against 10.0.0-RC46, RC47 and a trunk snapshot (`claude/graphitron-rewrite` at `06d71b62b`), 2026-11-03; identical on all three.

## Reviewer findings

### Round 1: Spec → Ready, revisions requested (session_01FY5UvvNdo22LAot3QmZgG7, 2026-11-20)

Question 1 passes on communication: the goal reads on its own (a malformed node id to `node`,
`nodes` or any root field returning `Node` becomes the client error every `@nodeId` site gives, in
`nodes` at the id's own position, while a well-formed id of no row or of a type not served here stays
`null`), and every symbol, test, relation, fixture and doc passage the spec names exists as named.
One viability point under it is left open by the spec's own wording (finding 2). Question 2 fails on
one bullet (finding 1); the rest of the plan extends shapes already in the tree and could be handed
over as written.

1. **The node query shares `servedById` with `EntityKeyQuery`, which `fact-model.adoc` rules out
   (question 2).** The node query bullet says to "state the predicate once and have both queries use
   it, so the node fetcher's map and the entity dispatch's node id alternative cannot disagree while
   both exist", and that no stored relation is added. `docs/architecture/explanation/fact-model.adoc`,
   "One base, many views", says each use of the store owns its own query; that no two share a jOOQ
   condition or a select helper stated once so that two readers cannot disagree; that a fact two uses
   need moves into the model as a relation both read; and that no gate sees a shared helper, so the
   rule is held in review. The spec asks for the shared condition, for the very reason the page
   names, and cites neither the page nor a ruling lifting it for the window until R876 states the
   fact. Any one of these satisfies it, and choosing among them is the author's or a ruling's, not
   this round's: the node query writes its own statement of its population, and its pipeline test
   pins that population; or "served by id" is stored now, with what a stored relation owes, and both
   queries read it; or Alf or Filip rule that the condition may be shared until R876 lands, the
   ruling entered on that page and cited here.

   *Author's note (session_01FY5UvvNdo22LAot3QmZgG7, 2026-11-20).* The second: "served by id" is
   stored now, as `graphitron_node_served_by_id`, and `EntityKeyQuery` and the node query each read
   it in a statement of their own. The node query bullet is replaced by three (the relation and
   what it owes, `EntityKeyQuery`'s move onto it, the node query), the sequencing section says why
   the fact is stored here and not in R876, the tests claim the population in a fact document, and
   the shared condition and the two-statements plan are under "Other solutions we've considered".
   The first option was not taken because, without a ruling, it leaves two readers evaluating one
   predicate, which the same page moves into the model; the third was not sought. `principles-architect`
   was consulted on the fork and read the pages the same way. From its advice: the relation is a
   table at `graphitron_node`'s grain and not a column, the `resolvable` column comment is
   corrected, the population is claimed in a fact document, the reference page names the relation,
   and the plan-tier edge is stated (all taken); deriving from `graphitron_occurrence_node_id` with
   the node type pinned to the type itself was not taken, for the reason given under "Other
   solutions we've considered".

2. **The first open question sets a precondition for this gate that is not met, and says more than
   the body does (question 1, viability).** It recommends to "confirm against kreg's gateway before
   Ready" and says the answer "decides whether the undecodable row needs a carve-out". No
   confirmation is recorded. "Where the line falls" says the unverified fact affects "how often the
   third row is hit, not where the line falls", which holds only for ids graphitron minted: the case
   the question adds, a gateway handing every subgraph an id of a scheme graphitron cannot peel,
   lands in the first row and becomes an error, which is the line. Satisfied by the confirmation, or
   by a ruling that the item goes ahead without it, the body and the question then saying one thing,
   including where the check happens if not before Ready.

   *Author's note (session_01FY5UvvNdo22LAot3QmZgG7, 2026-11-20).* Neither the confirmation nor a
   ruling was obtained; the precondition is withdrawn on a fact instead, for the next reviewer to
   judge. Graphitron 9's source at tag `v9.3.0` fails the field for an undecodable id and for an
   unknown type id alike, so the row the question was about is one a gateway in front of a
   Graphitron 9 subgraph already met as an error, and no carve-out could tell an id of another
   scheme from garbage. "The defect" now states what 9 did, verified rather than reported; "Where
   the line falls" states the other-scheme case and why nothing waits on the gateway; the open
   question is gone, and the release-note question says what a consumer coming from 9 sees.

Not blocking. The other three open questions each carry a recommendation the body already follows,
so they do not hold the gate. `assertFieldNullified` is private to `GraphQLQueryTest`, so the error
cases in the three other test classes need a helper of their own or a shared one.

---
id: R1146
title: "An invalid id to a Node-returning lookup returns null with no error"
status: Spec
bucket: bug
priority: 2
theme: nodeid
depends-on: []
created: 2026-11-03
last-updated: 2026-11-04
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

The dispatch can only judge an id against what this subgraph's schema declares: the set of `@node`
types it owns is known at generation time, each with its wire type id and key columns. Well formed
therefore has two checkable tiers, and a third outcome that is not a judgement at all.

| Input | Today | After |
|---|---|---|
| Not decodable: not base64url, or decodes to text with no `:`, or an empty type id | `null` | client error, `not a valid node id` |
| Type id is one this subgraph declares (any `@node` type, `@node(typeId:)` overrides honoured), but the key is wrong: wrong arity, or a value no column of that key can hold (`NodeIdEncoder.decodeColumns` answers `null`: wrapped number, non-numeric, padded, converter-rejected, tenant slot that is not a value of its column, legacy-shape id with an unparseable dropped value) | `null` | client error, `not a valid <Type> id` |
| Type id is not one this subgraph declares | `null` | `null` (not ours) |
| Declared type, valid key, no row | `null` | `null` |
| Valid key naming a tenant outside the request's tenant set (multi-tenant) | `null` | `null` (matches nothing) |
| `null` element in `ids` (only where the schema makes elements nullable) | `null` | `null` |

The federation caution is met by the third row: an id carrying a type id this subgraph does not
declare is never an error, however it was minted, so a gateway fanning `node(id:)` out to every
subgraph gets `null` from the ones that do not own the type. The unverified fact is whether the
gateway calls non-owning subgraphs at all; that affects how often the third row is hit, not where the
line falls (see the open questions). The residual ambiguity is unavoidable and accepted: a string that
is not an id but happens to base64-decode to `x:y` reads as a foreign type `x` and answers `null`.

The `_entities` path must not change: federation expects a null slot for a bad representation, and
`FederationEntitiesDispatchTest` pins it (`entities_garbageNodeId_yieldsNullSlot`,
`entities_compositeNodeIdUnderArity_yieldsNullSlotNoError`). The failure therefore belongs to the node
dispatch, not to the shared `HandleMethodBody` decode.

## Implementation

Reuse the existing route; do not stand a parallel one. The client error is the generated
`GraphitronClientException` (`GraphitronClientExceptionClassGenerator`), thrown through
`NodeIdDecodeFailure`, the one home for the message text. A throw inside the node fetchers is already
routed: `TypeFetcherGenerator.buildQueryNodeFetcher` wraps `getNode` in `noChannelCatchArm`, and
`buildQueryNodesFetcher` ends in `asyncWrapTail`, both calling `ErrorRouter.surfaceClientErrorOrRedact`,
which surfaces a `GraphitronClientException` with its own message, path and locations and never
redacts it (a configured `FetcherErrorResponse.clientErrorMessage` may reword it, as at every other
site). `asyncWrapTail` already unwraps the `CompletionException` a DataLoader batch function throw
arrives in, as `MultiTablePolymorphicEmitter` relies on. No change is needed in the fetcher wrappers.

* `EntityFetcherDispatchClassGenerator`: replace `typenameForTypeId` with one emitted
  `typenameOfNodeId(String id)` that returns the GraphQL typename for a node id, `null` for a type id
  this subgraph does not declare, and throws the client error for the two malformed cases above. It
  peeks the type id with `NodeIdEncoder.peekTypeId`, looks it up in the same entry map
  `buildTypenameForTypeIdMethod` builds today (the `KeyAlternative.NodeId` alternatives, deliberately
  regardless of `resolvable`), and for a hit runs `NodeIdEncoder.decodeColumns` with that entity's
  `SlotParse.parseClasses` and column refs. Extract the `decodeColumns` call that
  `HandleMethodBody.emitDecodeAndGroup` emits for `KeyAlternative.NodeId` into a shared fragment so the
  validator and the entity decode cannot drift (legacy key shapes and converters then come for free).
  The dispatcher is emitted whenever `QueryNodeFetcher` is, since every `NodeType` gets an entity entry
  (`EntityResolutionBuilder`). Retire `typenameForTypeId` if `QueryNodeFetcher` is its only caller
  (grep before deleting).
* `NodeIdDecodeFailure`: add the undecodable-id message, `Invalid node id "<id>" for this argument: not a
  valid node id`, beside the existing ones, and use the existing single-type `throwStatement` for the
  known-type case so the text matches `@nodeId` sites word for word (`not a valid <Type> id`). Do not use
  the multi-candidate message: it would list every node type in the schema, and the wrong-type branch it
  carries is the not-ours case, which here is `null`.
* `QueryNodeFetcherClassGenerator`: in `fetchById` and `rowsNodes`, replace the `peekTypeId` plus
  `typenameForTypeId` pair and its two early `null` returns with `typenameOfNodeId(id)`; a `null` typename
  keeps the existing `noNode` / `reps.add(null)` path. `getNode` / `getNodes` keep returning `null` for a
  `null` id or element. In `getNodes` a malformed id fails the whole field, matching how one bad element
  in a `@nodeId` list filter nulls the field and names the id; it does not null only its own slot.
  Rewrite the class javadoc and both method javadocs, which currently say "null/garbage/unknown IDs
  (Relay spec)" and "opacity says we never leak decoding errors", to the new contract.
* Multi-tenant builds: the tenant slot is a key column, so a tenant slot that is not a value of its
  column becomes the malformed error through `decodeColumns`; an id whose tenant is well formed but
  outside the request's set stays `null` via the existing skip in the handle method. No separate work.
* User docs, `docs/manual/how-to/global-id.adoc`: change the "Decoding errors stay opaque" bullet (the
  dispatcher path no longer returns `null` for a malformed id, it fails with the client error; unknown
  type id and no-such-row stay `null`), rewrite the "intentionally different from the dispatcher path"
  paragraph (the asymmetry is now only that a type this subgraph does not own, and a missing row, are
  `null` while an argument asserts one type), and fix the key-value paragraph's "`null` on `node`,
  `nodes` and `_entities`" to say the client error on `node` and `nodes` and `null` on `_entities`.
  `docs/architecture/reference/code-generation-triggers.adoc` (`#relay-node`) says "a null, malformed,
  or unknown-prefix id comes back as `null`"; correct it. The `NullOnMismatch` remark in the
  `CallSiteExtraction` javadoc should be reread for the same claim. No roadmap vocabulary in any of it.

## Tests

Execution tier, `graphitron-sakila-example` (`GraphQLQueryTest`, `@ExecutionTier`, with `executeRaw` and
`assertFieldNullified` for the error cases). The behaviour is a runtime answer over a real database,
which is what that tier is for; no generated-body string assertions.

* New: `node` and `nodes` with `"not-a-valid-base64-id"` assert the client error text, no `Reference:`,
  field nullified; `node` with a `Customer` id carrying key `abc`, and a composite `FilmActor` id of one
  part, assert `not a valid <Type> id`; `nodes` with one good and one malformed id asserts the whole
  field errors and names the bad id.
* Kept and now the explicit "stays null" pair: `node_unknownTypeId_returnsNull` (not ours) and
  `node_validPrefixNoSuchRow_returnsNull`; add the `nodes` variant with a mix of unknown, missing and
  valid ids resolving per position with no error. Keep `node_idOfATenantOutsideTheSet_answersNull_likeAnUnknownId`.
* Must change from `null` to the client error: in `GraphQLQueryTest`, `node_garbageInput_returnsNull`,
  `node_compositeKeyWrongArityId_returnsNull`, `node_keyValueWrapsItsColumn_returnsNullNotTheWrappedRow`,
  `node_nonNumericOrPaddedKeyValue_returnsNull`, `nodes_undecodableKeyValues_nullSlotsWhileTheRestResolve`;
  in `TenantDivinedRoutingExecutionTest`, `node_tenantSlotThatDoesNotDecode_answersNull_withoutAConnection`
  and `nodes_tenantSlotThatDoesNotDecode_nullsThatSlot_andTheRestResolves` (still opens no connection);
  in `LegacyNodeKeyExecutionTest`, `aLegacyIdWithAnUnparseableDroppedValue_isRejectedAsMalformed`; in
  `ConverterFilterExecutionTest`, `node_orgCodeSlotTheConverterCannotTake_answersNull` and
  `nodes_paddedOrgCodeSlot_isNotAnAliasOfTheRealId` (the padded slot now errors, so split it: the
  malformed id on its own, the real id on its own). Rename each so the name states the new outcome.
* Unchanged, and the guard that the shared decode did not move: the `_entities` cases in
  `FederationEntitiesDispatchTest` stay `null`.
* Consumer-shaped field: the sakila example has no `Node`-returning root under another name, so add one
  (`internalNode(id: ID): Node`, the shape of the `relay-node` corpus example) to the example schema and
  assert it errors on a malformed id too. That is the kreg report as a test.
* The `relay-node` corpus example stays as is; classification does not change.
* Verification build per the repo rule; the example module compiling the emitted dispatcher at Java 17
  covers the new emitted method.

## Other solutions we've considered

* Throw from `HandleMethodBody` where `decodeColumns` returns `null`. Rejected: that decode is shared
  with `_entities`, where federation wants a null slot, and it would turn gateway entity resolution into
  errors.
* Signal strictness through the synthesised representation map. Rejected: a flag in the data to change
  control flow in a shared method, where a separate validating entry point states the difference.
* Give `Query.node` an error only for the undecodable case and keep `null` for a wrong-shaped key of a
  known type. Rejected: it contradicts the ruling (the id cannot be read as a node id of its own type) and
  would leave the sakila `Customer:abc` case silent.

## Open questions for the user

* Does the federation gateway call `node(id:)` on subgraphs that do not own the id's type, and with what
  id? Not verified. The spec is safe either way (a foreign type id is `null`), but if the gateway sends
  an id scheme graphitron cannot peel (not `<typeId>:` base64) to every subgraph, those would now be
  errors; the answer decides whether the undecodable row needs a carve-out. Recommendation: ship as
  specified and confirm against kreg's gateway before Ready.
* Types declared `@node @key(fields: "id", resolvable: false)` (reference-only, owned elsewhere): their
  type id is in the dispatcher's map. Recommendation: treat them as declared, so a wrong-shaped key
  errors, since the key columns are declared in this schema. Alternative: treat them as not ours (`null`)
  because another subgraph owns them. Say if you want the alternative.
* Message for the undecodable case: `not a valid node id` with no candidate list, recommended for
  brevity and to avoid enumerating the schema. Say if you want the type list.
* Release note: consumers that rely on `null` for garbage ids (graphitron 10 so far) change behaviour;
  recommend noting it in the release notes as a bug fix, not a breaking change.

Provenance: found by the kreg Graphitron 10 port session (kreg branch `worktree-kreg-g10` at `0d9997fd39`), replaying recorded Graphitron 9 requests against 10.0.0-RC46, RC47 and a trunk snapshot (`claude/graphitron-rewrite` at `06d71b62b`), 2026-11-03; identical on all three.

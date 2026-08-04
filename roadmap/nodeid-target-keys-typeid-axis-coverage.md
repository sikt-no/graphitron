---
id: R583
title: "Pin the typeId axis of name-first resolveTargetKeys on both jOOQ-record decode arms"
status: Spec
bucket: cleanup
priority: 3
theme: nodeid
depends-on: []
created: 2026-08-04
last-updated: 2026-08-04
---

# Pin the typeId axis of name-first resolveTargetKeys on both jOOQ-record decode arms

`BuildContext.resolveTargetKeys` reads the `NodeIndex` by-name entry ahead of the backing table's
`KjerneJooqGenerator` metadata, so a `@nodeId(typeName:)` target takes both `typeId` and `keyColumns` from
the named type's own reconciled `@node`. The `keyColumns` axis is pinned
(`NodeIdPipelineTest.InputCase.EXPLICIT_TYPENAME_TAKES_KEY_ORDER_FROM_THE_NAMED_NODE`). The `typeId` axis is
not: reverting the read order and running the whole `graphitron` module breaks that one case out of 3120
tests, so a regression on the other axis would ship silently.

## Why no existing test catches it

Four assertions on the resolved `typeId` already exist. None of them discriminates, because no fixture
pairs a metadata-carrying table with a *differing* SDL `typeId`, which is the only shape where the two read
orders disagree:

[cols="2,2,3"]
|===
| Assertion | Asserts | Why it cannot discriminate

| `NodeIdRecordInputBeanPipelineTest:68`, `:205`
| `"Film"`
| `film` carries no `__NODE_TYPE_ID`, so metadata-first falls through to the by-name arm anyway.

| `NodeIdRecordInputBeanPipelineTest:185`, `:219`
| `"FilmActor"`
| `film_actor`'s metadata `typeId` is `"FilmActor"`, identical to the type name the by-name arm yields.

| `JooqRecordServiceParamPipelineTest:97`
| `"Film"`
| As above; `film` has no metadata.

| `JooqRecordServiceParamPipelineTest:514`
| `"Email"`
| `email` carries no metadata either.
|===

The fixture metadata map is `NodeIdFixtureGenerator.METADATA`; the tables in it are `bar`, `baz`,
`shared_node`, `studieprogram`, `film_actor`, `parent_node`, `too_wide`, `level_a`, `lift_fail_a`,
`reordered_pk_parent`. Only `film_actor` is reachable from the two record tests, and its metadata `typeId`
happens to equal its type name, which is precisely what makes the coverage look present and be absent.

## What is at stake

Not a cosmetic prefix. `resolveNodeIdRecordDecode` feeds `keys.typeId()` into the emitted
`decodeValues($S, nodeId)` call, and `decodeValues` returns `null` on a `typeId` mismatch, whereupon the
generated helper throws `GraphqlErrorException` ("Decoded NodeId did not match the expected type for this
argument"). So a wrong prefix does not corrupt a value, it **rejects every well-formed client ID at
runtime**, on an input that builds green. That is the failure mode the pin is protecting against.

Two arms consume the typeId, not one, which is the correction to this item as originally filed:

* `CallSiteExtraction.NodeIdDecodeRecord` — a jOOQ-record-typed member of a `@service` input bean
  (`InputBeanResolver:785`), emitted by `InputBeanInstantiationEmitter.buildRecordDecodeHelper`.
* `CallSiteExtraction.RecordKeyDecode` — an `ID! @nodeId` field populating a jOOQ-record `@service` param
  (`InputBeanResolver:410`), emitted by `JooqRecordInstantiationEmitter.emitKeyDecode`.

The `@nodeId`-leaf path does **not** need pinning on this axis. Post-R581 `NodeIdLeafResolver.resolve` passes
`keys.typeId()` to `resolveDecodeHelperForType` only as the *fallback* argument, which is read solely when
no NodeType carries the name; when one does, the helper comes off the NodeType and the fallback is ignored.
That is what bounds this item to the two record arms.

## Both shapes reproduce

Confirmed empirically, by writing the two probes below against the current tree (both pass), then reverting
`resolveTargetKeys` to the metadata-first order and re-running:

* SDL override, single `@node`: `type FilmActor implements Node @table(name: "film_actor") @node(typeId:
  "FA46")` with a member `ID! @nodeId(typeName: "FilmActor")` → `expected: "FA46" but was: "FilmActor"`.
* Sibling `@node`, the federation shape: a second `@node(typeId: "46")` over `film_actor` with the member
  naming it → `expected: "46" but was: "FilmActor"`.

Both regress to the table's `__NODE_TYPE_ID`, which is the defect exactly as described.

## Design

Three cases, all model-level assertions on the resolved `CallSiteExtraction` record, never code-string
assertions on the generated body:

* `NodeIdRecordInputBeanPipelineTest`: the SDL-override shape. Pins the `TypeBuilder` reconciliation
  (SDL `typeId` wins over metadata outright) as observed through the record decode arm.
* `NodeIdRecordInputBeanPipelineTest`: the sibling-`@node` shape, naming the second type. Pins the
  multi-node half and is the direct regression test for the reported federation scenario.
* `JooqRecordServiceParamPipelineTest`: one case on the `RecordKeyDecode` arm, so the second consumer is
  not left resting on the first arm's coverage.

`film_actor` is the right fixture table for all three: it already carries metadata (`typeId "FilmActor"`,
key columns `(ACTOR_ID, FILM_ID)`), both record tests already reach it, and its composite key means the
cases exercise the arity the single-key fixtures do not. No new fixture table or `NodeIdFixtureGenerator`
entry is needed.

## Acceptance

* The three cases above land and pass.
* Each is verified to *discriminate*, by reverting `resolveTargetKeys` to metadata-first and observing the
  failure, not merely by observing a green run. A non-discriminating assertion is the exact defect this
  item exists to correct, so shipping one would be self-defeating.
* No code-string assertions on generated method bodies.

## Not in scope

* Changing any production behaviour. `resolveTargetKeys` is already correct; this item is coverage only.
  If the work turns up a *behavioural* gap, that is a separate item, not a widening of this one.
* The `keyColumns` axis, already pinned.
* The `@nodeId`-leaf path's fallback `typeId`, inert per the bounding argument above.
* Adding a fixture table whose metadata `typeId` differs from every plausible type name so the trap cannot
  recur. Defensible, but it is a fixture-design change affecting every consumer of the shared catalog and
  wants its own item if anyone wants it.

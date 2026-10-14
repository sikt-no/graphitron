---
id: R1029
title: "The tenant fold crashes on a @nodeId(typeName:) decode whose target is a @table-only type"
status: Ready
bucket: bug
priority: 2
theme: diagnostics
depends-on: []
created: 2026-10-14
last-updated: 2026-10-14
---

# The tenant fold crashes on a @nodeId(typeName:) decode whose target is a @table-only type

## Goal

A `@nodeId(typeName: T)` input field whose target `T` is a `@table` type that does not implement `Node` builds under a configured `<tenantColumn>`, the same as it builds without one. Graphitron already supports that shape: `BuildContext.resolveTargetKeys` falls back to the table's catalog node metadata when no `NodeType` carries the name, and `JooqRecordInstantiationEmitter.emitKeyDecode` decodes from the encoder class, type id and target columns without needing a `NodeType`. Only the tenant fold (`TenantBindingIndex.Fold`, which works out where each field reads its tenant from) assumes every decoded type id names a `NodeType`, and it throws `IllegalStateException: no node type carries type id '<id>'` when one does not. The fold should read the target's key tuple from the same source the decode uses. Where that tuple has no tenant column, which is the common case and the sis case, the fold skips the decode as it already does for a node key without one. Where the tuple does carry the tenant column, the build rejects the field with an author error naming both ways out (make the target a node type, or bind the tenant through a field mapping to the tenant column), never a crash.

## Evidence

Reported from sis on 2026-10-14 (fs-plattform branch `sis/upgrade-graphitron` rebased onto main, graphitron trunk `9e6f1fa49`). Six code tables moved to another subgraph. In sis they are now federation stubs with no `implements Node`, for example:

```graphql
type Studieniva @table @key(fields: "kode") {
  kode: String @field(name: "STUDIENIVAKODE") @shareable
}
```

sis still accepts the v9-encoded ids of these types in 15 input fields, including `studienivaId: ID @nodeId(typeName: "Studieniva")` on a `@service` input (`EndreStudieprogramInput`). Clients still send those ids. The generator stops with:

```
IllegalStateException: no node type carries type id '104'
  at TenantBindingIndex$Fold.nodeTypeByTypeId
  at TenantBindingIndex$Fold.collectFromJooqRecord
  at TenantBindingIndex$Fold.collectFromValueShape
  at TenantBindingIndex$Fold.collectFromServiceCall
  at TenantBindingIndex$Fold.directBinding
  at TenantBindingIndex$Fold.siteEstablishesContext ... foldAncestorContexts ... run
```

There is no author-side workaround short of dropping the decode. The crash only fires with a tenant column configured, which is probably why no fixture caught it.


## Which decodes reach the crash

A decode carrier is the classified record of how one input value becomes key columns. Three carriers hand the fold a decoded node id, and only two of them can name a `@table`-only target:

- `CallSiteExtraction.RecordKeyDecode`, one `@nodeId` field of a jOOQ-record `@service` parameter, folded in `TenantBindingIndex.Fold.collectFromJooqRecord`. Built by `InputBeanResolver` from `BuildContext.resolveNodeIdRecordDecode`, which resolves `@table`-only targets through `resolveTargetKeys`. This is the sis crash. The carrier keeps the wire `typeId` and the `targetColumns` it loads (for a reference decode, the foreign-key columns on the record), but drops the resolved `typeName` and the target's own key tuple, so the fold has nothing to read the tenant position from except a `NodeType` lookup.
- `CallSiteExtraction.NodeIdDecodeRecord`, an id decoded into a whole record slot, folded in `collectFromServiceLeaf`. Built from the same `resolveNodeIdRecordDecode`, so it reaches `@table`-only targets too, and it already carries `typeName`, `keyColumns` and `table`. It crashes for the same reason: the fold looks the node type up by type id instead of reading the carrier.
- `CallSiteExtraction.NodeIdDecodeKeys` (`ThrowOnMismatch`, `PruneOnMismatch`), carrying a `HelperRef.Decode`. Every `HelperRef.Decode` is minted from a `NodeType` (`BuildContext.resolveDecodeHelperForType`, `NodeType.decodeMethod()`), and `NodeIdLeafResolver.resolve` rejects a `@table`-only target for scalar `@nodeId` arguments and filter inputs with "'T' is not a node type", so this carrier never names one. Its lookup in the fold's slot-miss branch (which needs the node's table to report tenant evidence) is sound by construction.

So the fix is two carriers' worth of fold, plus one carrier field pair. Nothing in emission changes for the skip path: `JooqRecordInstantiationEmitter.emitKeyDecode` and the `decode<TypeName>Record` helper already decode a `@table`-only target from the encoder class, type id and key columns.

## Implementation

**`CallSiteExtraction.RecordKeyDecode`** gains two components from the `NodeIdRecordDecode.Resolved` it is built from: `typeName` (the GraphQL type the author named) and `keyColumns` (the target's key tuple in decode order, the list `resolveTargetKeys` returned). The compact constructor checks both non-empty, like its neighbours. The javadoc says why both exist beside `targetColumns`: a reference decode lands the key on foreign-key columns that need not carry the tenant column's name, so the tenant position is read off the target's own tuple. `InputBeanResolver` (the one production construction site, the `KeyDecodeResult.Ok` return) passes `resolved.typeName()` and `resolved.keyColumns()`. No test constructs a `RecordKeyDecode` directly today.

**`TenantBindingIndex.Fold`** reads both record-shaped carriers through one private helper, say `decodedSlotAccess(String typeName, List<ColumnRef> keyColumns, TenantBinding.SlotRead read, String slotName)`, returning the tenant position's access or nothing:

- `tenantIndex(keyColumns) < 0`: no tenant in the key. The caller keeps its existing behaviour: `collectFromJooqRecord` skips the decode and the record-level evidence check still runs; the `NodeIdDecodeRecord` arm reports tenant evidence when `record.table()` is tenant-scoped, as it does today. (Tenant evidence is the fold's note that a tenant-scoped table was reached by something naming no tenant, which feeds the rejection text.)
- Tenant in the key and `types.get(typeName)` is a `NodeType`: `SlotAccess.Resolved` with `DecodedKeySlot(nt.decodeMethod(), slot)`, the binding the fold produces today. The node's own key and `keyColumns` are the same list by `resolveTargetKeys`' name-first arm, so the slot index is unchanged for every schema that builds now.
- Tenant in the key and `typeName` is not a `NodeType`: `SlotAccess.Declined`. A decline is the fold's existing author-error path for a shape that names the tenant column but cannot route on it; it becomes a `NoTenantBinding` rejection on the field whenever the field's statement needs a tenant, and it stops a divining sibling from routing alone, which is what keeps the id from being handed to the service unchecked against the tenant. The detail, on the model of the polymorphic-record decline: the tenant column is reached through `@nodeId(typeName: 'T')` at `<slot>`, whose key carries tenant column `<c>`, but `T` is not a node type, so there is no node-id decode helper to route the statement on; make `T` a node type, or bind the tenant through a field mapping to tenant column `<c>`.

Lookup is by `typeName`, not by type id. `NodeIdRecordDecode.Resolved` already names the type name as the decode's identity downstream, since two types can back one table and a `@table`-only type's catalog type id can coincide with a node type's.

`collectFromJooqRecord` calls the helper per `keyDecode` with `keyDecode.typeName()` and `keyDecode.keyColumns()`; the `NodeIdDecodeRecord` arm of `collectFromServiceLeaf` calls it with `record.typeName()` and `record.keyColumns()`. The `NodeIdDecodeKeys` arm's slot-miss branch looks the node type up by `keys.decodeMethod().nodeTypeName()` and keeps an `IllegalStateException` for a miss, with javadoc stating the real invariant (every `HelperRef.Decode` is minted from a `NodeType`). `nodeTypeByTypeId` goes, and with it the "a decode naming none is a classifier bug" premise.

**Javadoc premises.** The same "every decode names a node type" premise sits in prose beside the code this item touches, and is corrected in the same change: the `NodeIdDecodeRecord` javadoc ("every other component is a function of the node type it names", "a per-node-type `decode<TypeName>Record` helper"), the `RecordKeyDecode` javadoc ("one entry for a single-key NodeType"), the `NodeIdRecordDecode.Resolved` javadoc ("the node type's own `typeName`"), and the `collectFromJooqRecord` javadoc ("a `@nodeId` decode whose node key includes the tenant column"). Each should say "the `@nodeId(typeName:)` target", a node type or a `@table` type over a table with catalog node metadata.

**Not changed:** `TenantBinding.SlotProjection.DecodedKeySlot`, `CompositeDecodeHelperRegistry.registerTenantSlot`, `TenantDslEmitter`, and every emitter. The rejected tenant-in-key case is the only shape that would need them, and see the alternatives section for why it is rejected rather than routed.

## Tests

All in `TenantBindingClassificationTest` (classification tier, tenant column `film_id`), with whatever `TenantServiceStub` methods the SDL names. The `@table`-only target mirrors the sis stub: `type Baz @table(name: "baz") { code: String @field(name: "id") }`, with no `implements Node` and no `@node`, over `nodeidfixture.baz`, which carries catalog node metadata (type id `Baz`, key `ID`) and no `film_id`. That is a code table with node metadata, a key without the tenant column, and no node type in the schema. The three skip-path cases return a type over a global table, so nothing in the field needs a tenant, and assert the same two things: the build completes, and no bound slot and no rejection names the field. Today each of them throws.

- **Identity decode on a record parameter.** A `BazRecord` parameter whose input has `id: ID! @nodeId(typeName: "Baz")`.
- **Reference decode on a record parameter**, the exact sis shape. A `BarRecord` parameter whose input has `bazId: ID @nodeId(typeName: "Baz")`, loading through the `bar.id_1 → baz.id` foreign key, beside plain `@field` bindings for the rest of the record.
- **Record slot.** A `BazRecord` service parameter fed by `baz: ID! @nodeId(typeName: "Baz")` (`NodeIdDecodeRecord`). Same assertion; `baz` is global, so no tenant evidence either.
- **Tenant in the key.** A `@table`-only type over `film_actor` (metadata key `ACTOR_ID, FILM_ID`, so `film_id` sits at slot 1), decoded on a `FilmActorRecord` parameter of a mutation whose payload needs a tenant. Rejects with `NoTenantBinding` whose detail carries the decline text above. The existing `aJooqRecordParameterDecodingACompositeKeyReadsTheTenantPosition` is its node-type twin and keeps passing unchanged: same table, same slot, `DecodedKeySlot` at 1.
- **Scalar argument and filter input.** `@nodeId(typeName: "Baz")` on a scalar query argument and on a filter input field, under the tenant column. Both reject with the classifier's existing "'Baz' is not a node type" message and the fold does not throw. This pins the third carrier's invariant from the outside.

The existing decode tests in the class (`nodeIdFilterInputFieldDivinesTheDecodedSlot`, `wrapperServiceWithANodeIdRecordMemberDivinesAndItsChildrenInherit`, the polymorphic-decline tests) are the regression guard that node-type targets still bind exactly as before.

## Retired vocabulary

- `nodeTypeByTypeId` (the `TenantBindingIndex.Fold` method).
- "a decode naming none is a classifier bug" (its javadoc premise).

## Other solutions we've considered

**Route the tenant-in-key case instead of rejecting it.** `DecodedKeySlot` would carry either a node's `HelperRef.Decode` or a table-keyed source (encoder class, type id, key columns), and `CompositeDecodeHelperRegistry` would grow a second tenant-slot body that calls `decodeValues(typeId, id)` and converts the slot's string through the tenant column's data type, the way `emitKeyDecode` lets `rec.fromArray` convert. It works, but it costs a second helper body and a second malformed-id failure path beside the one `DecodedKeySlot`'s javadoc promises ("one generated helper and one failure message"), for a shape no consumer has: every sis target is a code table without the tenant column. The decline names a fix the author can apply today, and lifting it later is additive.

**Re-resolve the key tuple inside the fold** (catalog metadata by table, or by type id) instead of carrying it on `RecordKeyDecode`. That would be a second resolution of a fact `resolveTargetKeys` already settled, with its own fallback order to keep in step; a `@node(keyColumns:)` that reorders the metadata's key is exactly where two resolutions would disagree. The carrier already holds every other fact the decode needs, so it holds this one too.

**Carry the node-or-not decision on the carriers** as a sealed target (a node arm holding the `HelperRef.Decode`, a table-only arm), decided in `resolveTargetKeys` and switched on in the fold, instead of the fold reading `types.get(typeName)`. That fork in `resolveTargetKeys` answers a different question: where the key metadata comes from, asked against the pre-walk `NodeIndex`. What the fold needs to know is whether a `decode<TypeName>` helper will exist, and `NodeIdEncoderClassGenerator` emits one for exactly the types the classified schema holds as `NodeType`. Reading `types` asks the helper's own question of the helper's own source. Carrying the answer would also force the emission-side `NodeIdDecodeRecord` built in `TypeFetcherGenerator.collectProjectionDecoders` from key-projection rows to mint a decode helper reference it has no use for.

**One shared decode-target record held by both carriers**, replacing the `(encoderClass, typeName, typeId, keyColumns)` components `NodeIdDecodeRecord` already has and `RecordKeyDecode` gains here. That is the tidier model, but it rewrites every emitter that reads either carrier for a bug fix that needs neither; the fold helper takes `(typeName, keyColumns)` and both carriers feed it without duplication. Worth its own cleanup item if a third consumer appears.

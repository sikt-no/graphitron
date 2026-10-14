---
id: R1029
title: "The tenant fold crashes on a @nodeId(typeName:) decode whose target is a @table-only type"
status: Backlog
bucket: bug
priority: 2
theme: diagnostics
depends-on: []
created: 2026-10-14
last-updated: 2026-10-14
---

# The tenant fold crashes on a @nodeId(typeName:) decode whose target is a @table-only type

## Goal

A `@nodeId(typeName: T)` input field whose target `T` is a `@table` type that does not implement `Node` builds under a configured `<tenantColumn>`, the same as it builds without one. Graphitron already supports that shape: `BuildContext.resolveTargetKeys` falls back to the table's catalog node metadata when no `NodeType` carries the name, and `JooqRecordInstantiationEmitter.emitKeyDecode` decodes from the encoder class, type id and target columns without needing a `NodeType`. Only the tenant fold (`TenantBindingIndex.Fold`, which works out where each field reads its tenant from) assumes every decoded type id names a `NodeType`, and it throws `IllegalStateException: no node type carries type id '<id>'` when one does not. The fold should read the target's key tuple from the same source the decode uses. Where that tuple has no tenant column, which is the common case, the fold skips the decode as it already does for a node key without one. Where the tuple does carry the tenant column, the fold either projects the tenant through the same decode path or rejects with an author error, never a crash.

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

## Scope notes for the Spec

- `nodeTypeByTypeId` has three callers in `TenantBindingIndex`, all in the jOOQ-record arm of the fold: the record's `keyDecodes`, a record-level decode, and a key decode reached through `keys.decodeMethod()`. Its javadoc calls a miss "a classifier bug". That premise is stale now that `resolveTargetKeys` supports `@table`-only targets on purpose, and the fix should correct it.
- `TenantBinding.SlotProjection.DecodedKeySlot` carries a `HelperRef.Decode`, a per-node decode helper. A `@table`-only target has no such helper, so the tenant-in-key case is where the design cost lies. The Spec chooses between teaching the projection to decode the way `emitKeyDecode` does and rejecting that case as an author error. For sis all six key tuples are code-table keys without the tenant column, so the skip path is what sis needs.
- Owes a fixture: a `@service` input with `@nodeId(typeName:)` targeting a `@table`-only type over a table that carries node metadata, built with a tenant column configured. Cover the scalar `@nodeId` argument and the filter-input paths too. The fold's `instanceof NodeType` guard looks safe for those, but a test should show it.

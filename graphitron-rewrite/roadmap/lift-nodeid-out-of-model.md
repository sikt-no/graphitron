---
id: R42
title: "Lift NodeId out of the model"
status: Backlog
bucket: architecture
priority: 3
theme: nodeid
depends-on: []
---

# Lift NodeId out of the model

`@nodeId` is a wire-format encoding (base64 over `typeId` + composite key), but the classifier model and emitted query builders carry the wire shape into layers that only need column refs and key tuples. Encode and decode belong at the DataFetcher boundary; everything below it should see decoded values. The smoking gun is `BodyParam.NodeIdIn`'s body, which emits `NodeIdEncoder.hasIds("typeId", arg, table.col1, ..., table.colN)` from inside a generated condition method, reaching the encoder across the DataFetcher / query-builder boundary; jOOQ should see decoded key tuples and a standard row-IN predicate.

## Wire-shape leaks to retire

- `InputField.NodeIdField`, `NodeIdReferenceField`, `NodeIdInFilterField`, `IdReferenceField`
- `ChildField.NodeIdField`, `ChildField.NodeIdReferenceField`
- `BodyParam.NodeIdIn`
- `LookupMapping.NodeIdMapping`
- `ArgumentRef.ScalarArg.NodeIdArg`

Each is the same wire-shape leak in a different position.

**Plan sketch.** Introduce `CallSiteExtraction.NodeIdDecodeKeys(typeId)` (input boundary) and a parallel `NodeIdEncodeKeys(typeId)` projection-side hook (output boundary). Replace each model variant with the column-shaped variant that already exists for its role: NodeId-in-filter input fields collapse to `ColumnField` / a composite-column analog with `NodeIdDecodeKeys` extraction; NodeId output fields collapse to `ColumnField` projections with `NodeIdEncodeKeys` at the DataFetcher; `BodyParam.NodeIdIn` collapses to `BodyParam.ColumnEq` (single-PK) or a composite row-IN body param; `LookupMapping.NodeIdMapping` collapses to a regular `LookupMapping` over the PK columns; `ArgumentRef.ScalarArg.NodeIdArg` collapses to `ColumnArg` with the wire-decode extraction. `NodeIdEncoder.hasIds` deletes; emitted bodies use jOOQ `DSL.row(table.col1, ..., table.colN).in(decodedKeys)`. `NodeIdEncoder.encode` / `decodeKeys` / `peekTypeId` / `canonicalize` stay (boundary helpers consumed by DataFetcher emitters).

**`GraphitronType.NodeType` stays.** It carries `(typeId, keyColumns)`, which is type-level identity (the schema author declared this type as a Node and these are its key columns). Identity is a model concern; the wire format derived from that identity is not.

**Coupling.** R40 (argument-level `@nodeId` support) is shaped so it does not add to this debt: it lands the `NodeIdDecodeKeys` extraction and uses it for the new arg-level path only, leaving existing variants untouched. This item retires those variants. R20 (`IdReferenceField` code generation) is currently in Spec on the legacy variant; it should be re-evaluated as part of this item, since the right shape under the new framing emits standard FK-equality through a column-shaped variant rather than a `has<Qualifier>` method call. Spec author for this item should decide whether R20 lands first on the legacy shape or is folded into this work.

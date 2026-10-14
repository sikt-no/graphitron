---
id: R1033
title: "The @nodeId decode census covers a @table-only target with catalog node metadata"
status: Backlog
bucket: architecture
priority: 4
theme: nodeid
depends-on: []
created: 2026-10-14
last-updated: 2026-10-14
---

# The @nodeId decode census covers a @table-only target with catalog node metadata

## Goal

A `@nodeId(typeName: T)` whose `T` is a `@table` type without `implements Node`, over a table with catalog node metadata, gets the same build-time guarantees from the fact store that a node-type target gets. Today the store has no rows for such a coordinate. `graphitron_node_id_instruction_rule` resolves a target only through `graphitron_node_type`, or as a polymorphic container, so the decode-coverage rule (`NodeIdDecodeCoverage`, which fails the build when an authored `@nodeId` is neither decoded nor refused) never censuses it, and the slot-shape and landing views never check it. The record path already decodes these targets, and R1032 opens the leaf path, so an authored decode on a slot no rail reaches would pass silently as the wire string.

## Scope notes for the Spec

- A third `resolved_type_kind` for catalog-metadata targets, keyed on `graphitron_tabletype` joined to `sql_node_metadata` and `sql_node_key_column`. The endpoint, hop, decode-column and landing views are driven from the instruction table, so check each one for an arm that assumes `graphitron_node_keycolumn`.
- Once the relation exists, R1032's walk-side registry of catalog decodes (`GraphitronSchema.catalogNodeDecodes`) can be re-sourced from it, and `ServiceCatalog`'s named-parameter key slot can accept a `@table`-only target, because the store would then check that slot's parameter type against the key.
- Widening the named-parameter slot owes the classification test R1032 could not write: a root `@service` scalar slot naming a `@table`-only target whose key lacks the tenant column reaches `TenantBindingIndex.Fold.collectFromServiceLeaf`'s slot-miss branch, which must report evidence off `decode.table()` rather than throw. Until the slot widens, `ServiceCatalog` refuses that shape before the fold.
- Found while specifying R1032, by a read of `graphitron-model.sql` (instruction rule around the `EXPLICIT_TYPE_NAME` arm).

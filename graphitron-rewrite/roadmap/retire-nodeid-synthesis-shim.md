---
title: "Retire `@nodeId` synthesis shim"
status: Backlog
bucket: cleanup
priority: 12
theme: legacy-migration
depends-on: [sis-rewrite-migration]
---

# Retire `@nodeId` synthesis shim

Two field-level shim sites remain:

- `FieldBuilder` Path-2 synthesizes a `NodeIdField` output for a bare scalar `ID` field on a `NodeType` parent.
- `BuildContext.classifyInputField` synthesizes a `NodeIdField` input for a bare scalar `ID` field on a `@table` input whose backing table carries `__NODE_TYPE_ID` / `__NODE_KEY_COLUMNS`.

Both fire a per-occurrence WARN today. Once consumer schemas declare `@nodeId` explicitly (production schema in alf is canonical; one external-consumer release window is the courtesy gate), delete the two branches and turn the WARN into a terminal classifier error. Test fixtures retain the synthesized cases until then; flip them to canonical `@nodeId` SDL alongside the deletion.

## History

The original item also covered a third shim site in `TypeBuilder.buildTableType`: any `@table` SDL type whose backing jOOQ class carried `__NODE_TYPE_ID` / `__NODE_KEY_COLUMNS` was silently promoted to `NodeType`, even without `implements Node @node`. That branch was retired separately on consumer feedback after it produced a mass typeId collision (≈200 sis event types all sharing `__NODE_TYPE_ID = "195"` were promoted in lockstep, then symmetrically demoted to `UnclassifiedType` by the registry-uniqueness check). The type-level shim is now gone: `@table` without `implements Node @node` is a `TableType` regardless of metadata. Field-level synthesis still applies inside types that *do* opt in via `implements Node @node`.

---
id: R27
title: "Retire `@nodeId` and `IdReferenceField` synthesis shims"
status: Backlog
bucket: cleanup
priority: 5
theme: legacy-migration
depends-on: [sis-rewrite-migration]
---

# Retire synthesis shims (`@nodeId` field, `IdReferenceField`)

Two parallel shims survive in the classifier so legacy SDL keeps building. Both should retire on the same gate (sis migration to canonical SDL); their wire shape is independent but the user-visible migration is one piece of work, so the two retirements ship together.

## Shim 1: `@nodeId` field-level synthesis (two sites)

- `FieldBuilder` Path-2 synthesises a `NodeIdField` output for a bare scalar `ID` field on a `NodeType` parent.
- `BuildContext.classifyInputField` synthesises a `NodeIdField` input for a bare scalar `ID` field on a `@table` input whose backing table carries `__NODE_TYPE_ID` / `__NODE_KEY_COLUMNS`.

Both fire a per-occurrence WARN today. Once consumer schemas declare `@nodeId` explicitly (production schema in alf is canonical; one external-consumer release window is the courtesy gate), delete the two branches and turn the WARN into a terminal classifier error. Test fixtures retain the synthesised cases until then; flip them to canonical `@nodeId` SDL alongside the deletion.

### History

The original item also covered a third shim site in `TypeBuilder.buildTableType`: any `@table` SDL type whose backing jOOQ class carried `__NODE_TYPE_ID` / `__NODE_KEY_COLUMNS` was silently promoted to `NodeType`, even without `implements Node @node`. That branch was retired separately on consumer feedback after it produced a mass typeId collision (≈200 sis event types all sharing `__NODE_TYPE_ID = "195"` were promoted in lockstep, then symmetrically demoted to `UnclassifiedType` by the registry-uniqueness check). The type-level shim is now gone: `@table` without `implements Node @node` is a `TableType` regardless of metadata. Field-level synthesis still applies inside types that *do* opt in via `implements Node @node`.

## Shim 2: `IdReferenceField` synthesis on `@table` input types

The shim fires when an `ID!` or `[ID!]` field on a `@table` input type resolves to a FK qualifier in the catalog's qualifier map, synthesising `IdReferenceField` with a per-site WARN. Schema authors should replace the legacy `@field(name: "X_ID")` (or bare field-name) form with an explicit `@nodeId(typeName: "T")` declaration. Once all consumer schemas (primarily sis) have migrated, the shim body can be replaced with an `Unresolved` return and the WARN upgraded to an error.

Migration recipe: replace `fieldName: [ID!] @field(name: "X_ID")` with `fieldName: [ID!] @nodeId(typeName: "TargetType")`, adding `@reference(path: [{key: "fk_constraint_name"}])` when the FK is ambiguous.

## Retirement gate

Both shims promote in lockstep on the same trigger: sis-graphql-spec has migrated to declared `@nodeId` / `@node` SDL (tracked at [sis-rewrite-migration](sis-rewrite-migration.md)) and one external-consumer release window has elapsed. At that point: delete the synthesis branches, flip WARNs to errors, and migrate any remaining test fixtures to canonical SDL.

The canonical form is in place: R50 retired `IdReferenceField` and routed `[ID!] @nodeId(typeName: T)` (and the legacy synthesis-shim cases) to column-shaped successors (`InputField.ColumnReferenceField` / `InputField.CompositeColumnReferenceField` carrying `extraction = NodeIdDecodeKeys.SkipMismatchedElement`). What remains for this item is the consumer-schema migration — flipping the WARN to an error once sis-graphql-spec has migrated to declared `@nodeId(typeName: T)` SDL.

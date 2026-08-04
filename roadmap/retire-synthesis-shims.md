---
id: R27
title: "Retire `@nodeId` and `IdReferenceField` synthesis shims"
status: Backlog
bucket: cleanup
priority: 5
theme: legacy-migration
depends-on: [nodeid-migration-quickfix, explicit-nodeid-grammar]
---

# Retire synthesis shims (`@nodeId` field, `IdReferenceField`)

Two parallel shims survive in the classifier so legacy SDL keeps building. Both should retire on the same gate (sis migration to canonical SDL); their wire shape is independent but the user-visible migration is one piece of work, so the two retirements ship together.

## Shim 1: `@nodeId` field-level synthesis (two sites)

- `FieldBuilder` Path-2 synthesises a `NodeIdField` output for a bare scalar `ID` field on a `NodeType` parent. **Narrowed:** the field satisfying the `Node` interface (`id`) no longer goes through this branch. It is now a permanent carrier resolved ahead of the column lookup, per R473 rule 1, and carries no WARN. Only the *other* bare `ID` fields on a node type (`externalId: ID`) are still shim-synthesised here. Delete accordingly: this branch's retirement must leave the `Node.id` arm above it standing.
- `BuildContext.classifyInputField` synthesises a `NodeIdField` input for a bare scalar `ID` field on a `@table` input whose backing table carries `__NODE_TYPE_ID` / `__NODE_KEY_COLUMNS`.

Both fire a per-occurrence WARN today. Once consumer schemas declare `@nodeId` explicitly (production schema in alf is canonical; one external-consumer release window is the courtesy gate), delete the two branches and turn the WARN into a terminal classifier error. Test fixtures retain the synthesised cases until then; flip them to canonical `@nodeId` SDL alongside the deletion.

### History

The original item also covered a third shim site in `TypeBuilder.buildTableType`: any `@table` SDL type whose backing jOOQ class carried `__NODE_TYPE_ID` / `__NODE_KEY_COLUMNS` was silently promoted to `NodeType`, even without `implements Node @node`. That branch was retired separately on consumer feedback after it produced a mass typeId collision (≈200 sis event types all sharing `__NODE_TYPE_ID = "195"` were promoted in lockstep, then symmetrically demoted to `UnclassifiedType` by the registry-uniqueness check).

**Partly reversed, deliberately.** Metadata-driven promotion is back, but gated on the SDL declaration the retired shim lacked: a `@table` type that declares `implements Node` and whose table publishes the metadata now classifies as a `NodeType` without `@node`. `@table` plus metadata and no interface is still a `TableType`, so a type that never published the Relay contract cannot be promoted, and the lockstep promotion of the incident population does not recur through this path. The collision that made the incident visible is unchanged in kind: it is still diagnosed per colliding type by the typeId-uniqueness reduction, still with `@node(typeId:)` as the escape hatch, and a large shared-typeId group would still produce a diagnostic per member. What changed is who can enter that group. The `@node`-free spelling and the `implements Node @table` spelling are now the same declaration, which is why field-level synthesis inside a node type is described in terms of node types rather than in terms of `@node` presence throughout this item.

## Shim 2: `IdReferenceField` synthesis on `@table` input types

The shim fires when an `ID!` or `[ID!]` field on a `@table` input type resolves to a FK qualifier in the catalog's qualifier map, synthesising `IdReferenceField` with a per-site WARN. Schema authors should replace the legacy `@field(name: "X_ID")` (or bare field-name) form with an explicit `@nodeId(typeName: "T")` declaration. Once all consumer schemas (primarily sis) have migrated, the shim body can be replaced with an `Unresolved` return and the WARN upgraded to an error.

Migration recipe: replace `fieldName: [ID!] @field(name: "X_ID")` with `fieldName: [ID!] @nodeId(typeName: "TargetType")`, adding `@reference(path: [{key: "fk_constraint_name"}])` when the FK is ambiguous.

## Retirement gate

Both shims promote in lockstep on the same trigger: sis-graphql-spec has migrated to declared `@nodeId` / `@node` SDL (tooling tracked at [nodeid-migration-quickfix](nodeid-migration-quickfix.md); R34 pivoted 2026-07-14 from a manual migration tracker to LSP quick fixes that automate it) and one external-consumer release window has elapsed. At that point: delete the synthesis branches, flip WARNs to errors, and migrate any remaining test fixtures to canonical SDL.

Gate update (2026-07-13): per user confirmation there is no actual consumer of the shim behavior today, so the gate above is more conservative than reality and retirement can likely proceed ahead of the sis window. R473 (`explicit-nodeid-grammar`) defines the post-shim grammar (directive-less `ID` becomes an ordinary column-mapped scalar, `Node.id` the only implicit nodeId) and the typeName-first decode resolution that deletes `resolveDecodeHelperForTable` together with these branches; coordinate the two retirements.

The canonical form is in place: R50 retired `IdReferenceField` and routed `[ID!] @nodeId(typeName: T)` (and the legacy synthesis-shim cases) to a column-shaped successor (since R508 the merged `InputField.ColumnBackedReferenceField`, arity 1..N, carrying `extraction = NodeIdDecodeKeys.SkipMismatchedElement`). What remains for this item is the consumer-schema migration — flipping the WARN to an error once sis-graphql-spec has migrated to declared `@nodeId(typeName: T)` SDL.

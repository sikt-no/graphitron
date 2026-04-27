---
title: "Retire `IdReferenceField` synthesis shim"
status: Backlog
priority: 5
---

# Retire `IdReferenceField` Synthesis Shim

Promote the `IdReferenceField` synthesis shim WARN to a terminal classifier error once sis schemas have migrated to canonical `@nodeId(typeName:)` form.

The shim currently fires when a `ID!` or `[ID!]` field on a `@table` input type resolves to a FK qualifier in the catalog's qualifier map, synthesizing `IdReferenceField` with a per-site WARN. Schema authors should replace the legacy `@field(name: "X_ID")` (or bare field-name) form with an explicit `@nodeId(typeName: "T")` declaration. Once all consumer schemas (primarily sis) have migrated, the shim body can be replaced with an `Unresolved` return and the WARN upgraded to an error.

Migration recipe: replace `fieldName: [ID!] @field(name: "X_ID")` with `fieldName: [ID!] @nodeId(typeName: "TargetType")`, adding `@reference(path: [{key: "fk_constraint_name"}])` when the FK is ambiguous.

See also: `roadmap/retire-nodeid-synthesis-shim.md` for the analogous scalar `ID` shim retirement.

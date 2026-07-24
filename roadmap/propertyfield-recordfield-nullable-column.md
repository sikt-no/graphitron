---
id: R51
title: "Split PropertyField/RecordField on parent-kind instead of nullable column"
status: Backlog
theme: classification-model
bucket: cleanup
priority: 5
depends-on: []
---

# Split PropertyField/RecordField on parent-kind instead of nullable column

`ChildField.PropertyField` and `ChildField.RecordField` each carry both `columnName: String` and `column: ColumnRef`, with `column` nullable depending on the parent type: non-null when the parent is a `JooqTableRecordType` with a resolvable column, null for `JooqRecordType` / `JavaRecordType` / `PojoResultType` parents. The single record straddles two parent kinds via an Optional component, leaving `columnName` as the only carrier of the SDL string when `column` is absent. Since filing, both records gained a second parent-conditional nullable slot, `accessor: AccessorResolution.Resolved` (non-null only for class-backed `JavaRecordType` / `PojoResultType` parents), so the straddle now spans three parent kinds and two nullable components. Per *Narrow component types over broad interfaces* and *Sub-taxonomies for resolution outcomes*, the right shape is per-parent-kind sealed-arm variants (a table-backed arm carrying a non-null `ColumnRef`, a class-backed arm carrying a non-null accessor, a bare arm carrying just the SDL string), not one record with nullable components. Split surfaced during R50's `columnName` cleanup on the column-backed carriers (since merged by R508 into `ChildField.ColumnBackedField` / `ColumnBackedReferenceField`), where the table-backed-only invariant let those carriers retire `columnName` outright; this item carries the same rigour to `PropertyField` and `RecordField`.

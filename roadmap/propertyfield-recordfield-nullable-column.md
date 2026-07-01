---
id: R51
title: "Split PropertyField/RecordField on parent-kind instead of nullable column"
status: Backlog
bucket: cleanup
priority: 5
depends-on: []
---

# Split PropertyField/RecordField on parent-kind instead of nullable column

`ChildField.PropertyField` and `ChildField.RecordField` each carry both `columnName: String` and `column: ColumnRef`, with `column` nullable depending on the parent type: non-null when the parent is a `JooqTableRecordType` with a resolvable column, null for `JooqRecordType` / `JavaRecordType` / `PojoResultType` parents. The single record straddles two parent kinds via an Optional component, leaving `columnName` as the only carrier of the SDL string when `column` is absent. Per *Narrow component types over broad interfaces* and *Sub-taxonomies for resolution outcomes*, the right shape is two sealed-arm variants (one for table-backed parents carrying a non-null `ColumnRef`, one for non-table-backed parents carrying just the SDL string), not one record with a nullable component. Split surfaced during R50's `columnName` cleanup on `ChildField.ColumnField` / `ColumnReferenceField`, where the table-backed-only invariant let those carriers retire `columnName` outright; this item carries the same rigour to `PropertyField` and `RecordField`.

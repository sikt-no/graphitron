---
id: R1111
title: "A routine argument mapped from a computed $source column looks up a parentInput field typed with its generator"
status: Backlog
bucket: bug
priority: 4
theme: codegen-correctness
depends-on: []
created: 2026-10-27
last-updated: 2026-10-27
---

# A routine argument mapped from a computed $source column looks up a parentInput field typed with its generator

## Goal

A `@routine` field whose argument maps a `$source.<column>` that is a client-side computed column (a jOOQ column whose `DataType` carries a `<generator>`, a commercial-distribution feature) reads that argument through a `parentInput` field typed by the column's own `DataType`, generator included. `RoutineCallEmitter.argExpression`, on its `ParamSource.SourceColumn` over a `PreviousNodeRef.ParentInputField` arm, emits that `parentInput.field(<name>, <Table>.<COLUMN>.getDataType())` lookup. It is close to the shape behind the multitable order-slot crash, where commercial jOOQ ran a generator against a field with no owning table, though here the field belongs to the `parentInput` table; nobody has reported it. When this lands the lookup goes through the same per-class `withoutGenerator` helper the multitable slot and key declarations use (`MultiTablePolymorphicEmitter.buildWithoutGeneratorHelper`), so a computed column reaches a routine argument with its converter and binding but without a generator to run. The test needs a routine fixture whose argument maps a column of `display_name_person` or `display_name_account` (the fixture tables whose `display_name` carries a client-side generator), and can reuse the render-time listener in `MultiTableOrderingExecutionTest`, adjusted to record a computed-on-client field belonging to the `parentInput` derived table.

---
id: R267
title: "Replace deprecated-for-removal DataType.convert(Object) in NodeIdEncoder.decode<Type>"
status: Backlog
bucket: tech-debt
priority: 4
theme: model-cleanup
depends-on: []
created: 2026-06-01
last-updated: 2026-06-01
---

# Replace deprecated-for-removal DataType.convert(Object) in NodeIdEncoder.decode<Type>

`NodeIdEncoderClassGenerator.buildPerTypeDecode` emits, for each NodeType, a
`decode<Type>(String)` that builds a throwaway typed `RecordN` and populates it with
`rec.set(col, col.getDataType().convert(values[i]))`. `DataType.convert(Object)` is
deprecated for removal in jOOQ 3.20 (it bypasses `Configuration.converterProvider`
and misbehaves for user-defined types). The generator masks the resulting warning by
stamping a class-wide `@SuppressWarnings({"deprecation", "removal"})` on the whole
`NodeIdEncoder` class — which only hides a future hard compile break when jOOQ
actually removes the method.

R195 removed this call from its input-bean `decode<Type>Record` helpers (switched to
the non-deprecated `Record.fromArray(Object[], Field<?>...)`, which coerces through
the proper converter path). `NodeIdEncoder.decode<Type>` was left as-is because it
feeds the `NodeIdDecodeKeys` filter/lookup consumers (which need the typed `RecordN`
projection, `.value1()` / `::valuesRow`) and is out of R195's scope.

This item: do the same for `NodeIdEncoder.decode<Type>` — populate the `RecordN` via
a non-deprecated coercion path (e.g. `rec.fromArray(values)` on the freshly
`newRecord(...)`-built record, or whatever keeps the typed projection intact) — and
**remove the class-wide `@SuppressWarnings({"deprecation", "removal"})`**. A
deprecation-for-removal must be fixed at the source, never suppressed. Verify the
`NodeIdDecodeKeys` consumers (`ArgCallEmitter`, R260's `CompositeDecodeHelperRegistry`)
still compile and that the encoder round-trips against PostgreSQL.

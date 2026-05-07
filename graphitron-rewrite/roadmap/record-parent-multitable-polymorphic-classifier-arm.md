---
id: R105
title: "@record-parent multi-table polymorphic ChildField classifier arm"
status: Backlog
bucket: architecture
priority: 5
theme: structural-refactor
depends-on: [batch-multitable-polymorphic-child-fetcher]
---

# @record-parent multi-table polymorphic ChildField classifier arm

Multi-table polymorphic interface/union child fields on `@record`-backed parents are deferred today: `FieldBuilder.classifyChildFieldOnResultType` rejects polymorphic returns at `FieldBuilder.java:2703` with `Rejection.deferred("@record type returning a polymorphic type is not yet supported")`. Schema authors using `@record` parents (POJO or Java record sources) cannot model unions or interfaces of multiple participant tables as child fields; the only workaround is to flatten the union into a single concrete type or move the parent to a table-backed source.

R102 lands the structural prerequisites — `BatchKey.RecordParentBatchKey` slot on `ChildField.{InterfaceField,UnionField}` plus delegation to `GeneratorUtils.buildRecordParentKeyExtraction` — but does not deliver the new classifier arm. R102's records and emitter accept any `RecordParentBatchKey` permit; today only `RowKeyed` (the table-backed parent path) is reachable from classification. R105 wires up the other three permits (`LifterRowKeyed`, `AccessorKeyedSingle`, `AccessorKeyedMany`) by adding the missing classifier arm.

The work splits into three pieces:

1. **Classifier entry.** Replace the `Rejection.deferred` at `FieldBuilder.java:2703` with a real arm that constructs `ChildField.InterfaceField` / `ChildField.UnionField` for `@record`-parent polymorphic returns. Reuses `BatchKeyLifterDirectiveResolver` and `FieldBuilder.deriveBatchKeyFromTypedAccessor` for parent-side key derivation; both producers consult the parent class's structure (FK-equivalent `@batchKeyLifter` static method or typed zero-arg accessor) and are independent of the child's polymorphic shape.

2. **Per-participant join-path resolution without a parent SQL table.** `classifyObjectReturnChildField` (`FieldBuilder.java:424`) is gated on `TableBackedType parentTableType` and uses `parentTableType.table().tableName()` as the anchor for `parsePath` per participant (`:491`, `:516`, `:527`). The `@record`-parent case has no parent SQL table; participant join paths anchor instead on the lifter's target table or the accessor's element table (whichever the `RecordParentBatchKey` permit declares). Either factor a `parsePath` overload that takes the anchor-table source explicitly, or share the classifier arm between table-backed and `@record`-backed parents by parameterising on a "parent anchor table" sealed type.

3. **Tests.** Pipeline-tier coverage for each of the three new `RecordParentBatchKey` permits as the parent-key strategy on multi-table polymorphic children, on both Interface and Union arms; equivalence between table-backed and `@record`-backed parents pinned via the existing `childInterfaceField` / `childUnionField` fixture shape. Optional execution-tier sibling on `graphitron-sakila-example` if the runtime semantics (`loader.loadMany` dispatch on `AccessorKeyedMany`) materially differ from the table-backed path; pipeline tier likely sufficient.

Out of scope for R105 (filed as follow-ups when picked up): cross-cutting validator coverage for participant-side invariants on the new arm, documentation pass on the user-facing `@record`-with-polymorphic-children surface.

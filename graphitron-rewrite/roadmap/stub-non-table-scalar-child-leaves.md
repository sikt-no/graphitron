---
title: "Stub #8: Non-table / scalar / reference child leaves"
status: Backlog
bucket: stubs
priority: 2
---

# Stub #8: Non-table / scalar / reference child leaves

Lift these leaves out of `TypeFetcherGenerator.NOT_IMPLEMENTED_REASONS`: `ChildField.ColumnReferenceField`, `ComputedField`, `TableMethodField`, `ServiceRecordField`, `MultitableReferenceField`.

Priority number `#8` is referenced by emitted reason strings and must stay stable.

`NodeIdReferenceField` shipped under *`@nodeId` + `@node` directive support* (Done) for the FK-mirror case; the non-FK-mirror form is tracked under [Cleanup → `NodeIdReferenceField` JOIN-projection form](nodeidreferencefield-join-projection-form.md).

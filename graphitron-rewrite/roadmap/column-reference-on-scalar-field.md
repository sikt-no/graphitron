---
id: R42
title: "Stub: `@reference` on a scalar (FK column) field (`ColumnReferenceField`)"
status: Backlog
bucket: stubs
priority: 4
theme: model-cleanup
depends-on: []
---

# Stub: `@reference` on a scalar (FK column) field (`ColumnReferenceField`)

Lift `ChildField.ColumnReferenceField` out of `TypeFetcherGenerator.STUBBED_VARIANTS`. Today schemas using `@reference` on a scalar field (mapping the field to an FK column on the parent table) fail validation with `[deferred]`. Carved out of the original umbrella (R37) for independent prioritisation; not currently a blocker for any in-flight migration.

Plan body pending. Likely shape: smaller than R48 (`computed-field-with-reference`); no reflection, no fixture method; primarily a projection-layer change reusing the `@reference` directive's FK resolution path that is already in place for `ColumnField`-shaped sites.

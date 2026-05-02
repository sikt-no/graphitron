---
id: R44
title: "Stub: `@multitableReference` on a scalar field (`MultitableReferenceField`)"
status: Backlog
bucket: stubs
priority: 5
theme: interface-union
depends-on: []
---

# Stub: `@multitableReference` on a scalar field (`MultitableReferenceField`)

Lift `ChildField.MultitableReferenceField` out of `TypeFetcherGenerator.STUBBED_VARIANTS`. Today schemas using `@reference` on a scalar field whose target is a multi-table interface or union fail validation with `[deferred]`. Carved out of the original umbrella (R37) for independent prioritisation; not currently a blocker for any in-flight migration.

Plan body pending. Builds on Track B of R36 (shipped) for the multi-table dispatch design; see `MultiTablePolymorphicEmitter` and the R36 entry in [`changelog.md`](changelog.md).

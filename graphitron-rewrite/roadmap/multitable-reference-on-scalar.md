---
id: R44
title: "Stub: `@multitableReference` on a scalar field (`MultitableReferenceField`)"
status: Backlog
bucket: stubs
priority: 5
theme: interface-union
depends-on: [stub-interface-union-fetchers]
---

# Stub: `@multitableReference` on a scalar field (`MultitableReferenceField`)

Lift `ChildField.MultitableReferenceField` out of `TypeFetcherGenerator.NOT_IMPLEMENTED_REASONS`. Today schemas using `@reference` on a scalar field whose target is a multi-table interface or union fail validation with `[deferred]`. Carved out of the original umbrella (R37) for independent prioritisation; not currently a blocker for any in-flight migration.

Plan body pending. Depends on Track B of [`stub-interface-union-fetchers.md`](stub-interface-union-fetchers.md) for the multi-table dispatch design.

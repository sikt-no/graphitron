---
id: R43
title: "Stub: `@tableMethod` with scalar/enum return (`TableMethodField`)"
status: Backlog
bucket: stubs
priority: 4
theme: model-cleanup
depends-on: []
---

# Stub: `@tableMethod` with scalar/enum return (`TableMethodField`)

Lift `ChildField.TableMethodField` out of `TypeFetcherGenerator.NOT_IMPLEMENTED_REASONS`. Today schemas using `@tableMethod` to return a non-table type (scalar / enum) fail validation with `[deferred]`. Carved out of the original umbrella (R37) for independent prioritisation; not currently a blocker for any in-flight migration.

Plan body pending. Likely the smallest of the carved-out variants because most plumbing exists: the `@tableMethod` directive's reflection path already ships for table-returning fields (see `QueryField.QueryTableMethodTableField` in `IMPLEMENTED_LEAVES`). This track repurposes the call site against a `Field<?>`-typed result, similar in shape to R48 (`computed-field-with-reference`) but reusing the existing `reflectTableMethod` helper instead of adding a new one.

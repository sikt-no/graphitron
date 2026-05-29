---
id: R257
title: "UpdateRowsWalker raw-SDL substrate absorption"
status: Backlog
bucket: structural
priority: 4
theme: structural-refactor
depends-on: []
created: 2026-05-29
last-updated: 2026-05-29
---

# UpdateRowsWalker raw-SDL substrate absorption

R246 shipped `UpdateRowsWalker` as a *translator* over the already-classified `InputField` permits (the four admitted column carriers `ColumnField` / `CompositeColumnField` / `ColumnReferenceField` / `CompositeColumnReferenceField`, reached via `TableInputType.inputFields()`) plus the jOOQ catalog, rather than re-deriving the input-field classification from raw SDL + classloader as the R246 spec's ideal `walk(GraphQLFieldDefinition, JooqCatalog)` signature implied. This is the same blast-radius concession R238's `ServiceMethodCallWalker` took (translating over a resolved `MethodRef.Service` rather than reflecting from scratch); re-deriving the `@reference` FK-join and `@nodeId` decode resolution inside the walker would have duplicated the substantial classifier in `InputFieldResolver` / `EnumMappingResolver.buildLookupBindings`.

A consequence of the concession is that the per-field rules `buildLookupBindings` enforced for the UPDATE path (list-typed input field, field-level `@condition`) had to be re-expressed as typed `UpdateRowsError` arms inside the walker, because the UPDATE-direct path in `FieldBuilder.classifyUpdateTableField` bypasses `resolveInput`/`buildLookupBindings` entirely. Those rules now live in two places (the legacy resolver for INSERT/DELETE/UPSERT/payload-UPDATE, and the walker for table-return UPDATE).

This item absorbs the intermediate: have `UpdateRowsWalker` classify `GraphQLInputObjectField` shapes from SDL directly (the `@reference` FK-join path resolution and `@nodeId` decode-method resolution), so the UPDATE path no longer depends on the upstream `TableInputType.inputFields()` classification and the duplicated per-field rules collapse back to one owner. Mirrors R256 (`service-walker-substrate-absorption`), which does the analogous absorption for the service walker.

The `UpdateRowsWalker` javadoc references this item by slug (`updaterows-walker-sdl-substrate`).

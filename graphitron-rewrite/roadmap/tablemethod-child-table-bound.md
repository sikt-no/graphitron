---
id: R43
title: "Stub: child `@tableMethod` with table-bound return (`TableMethodField`)"
status: Backlog
bucket: stubs
priority: 4
theme: model-cleanup
depends-on: []
---

# Stub: child `@tableMethod` with table-bound return (`TableMethodField`)

Lift `ChildField.TableMethodField` out of `TypeFetcherGenerator.STUBBED_VARIANTS`. Today schemas using `@tableMethod` on a child field of a `@table`-bound parent fail the build with `[deferred] child @tableMethod (table-bound return) not yet implemented`, even though classification succeeds and the directive's reflection path already ships at the root site (`QueryField.QueryTableMethodTableField` in `IMPLEMENTED_LEAVES`).

This item was originally scoped as the carved-out scalar/enum-return form of `TableMethodField`. That carve-out is closed: `@tableMethod` returning a non-table type is rejected by `TableMethodDirectiveResolver` as a schema error (`"@tableMethod requires a @table-annotated return type"`) regardless of root vs child site, since the directive's whole purpose is to bind a developer-authored jOOQ table method (which by construction returns a generated jOOQ table class). What remains is the table-bound child case, the only shape the classifier produces for `TableMethodField` after the resolver narrows it.

Plan body pending. Most plumbing exists: reuse the root-site `reflectTableMethod` invariants and the existing per-field fetcher emission idioms (similar in shape to R48 `computed-field-with-reference`, but reusing `reflectTableMethod` rather than adding a new helper). Not currently a blocker for any in-flight migration.

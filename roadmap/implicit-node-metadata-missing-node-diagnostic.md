---
id: R580
title: "Name the missing @node when a Node-implementing @table type has __NODE_* metadata"
status: Backlog
bucket: validation
priority: 8
theme: nodeid
depends-on: []
created: 2026-08-03
last-updated: 2026-08-03
---

# Name the missing @node when a Node-implementing @table type has __NODE_* metadata

A type declared `implements Node @table(name: "X")` whose backing jOOQ class publishes
`__NODE_TYPE_ID` / `__NODE_KEY_COLUMNS`, but which omits `@node`, stays a `TableType`
(`TypeBuilder.buildTableType`: promotion to `NodeType` is opt-in on the directive, deliberately, so
metadata alone can never silently collide typeIds across two GraphQL types over the same table). Its
`id: ID!` field then falls through to plain column mapping and is rejected as
`UnclassifiedField("column 'id' could not be resolved in the jOOQ table; did you mean: FILM_ID,
ACTOR_ID, LAST_UPDATE")`. The rejection is accurate about the mechanism and useless about the cause:
the author is not misspelling a column, they are missing a type-level declaration, and the
did-you-mean list actively pushes them toward `@field(name: "actor_id")`, which would satisfy the
compiler while publishing a broken `Node` contract. Reproduced against the `film_actor` fixture
(metadata typeId `FilmActor`, keys `ACTOR_ID`/`FILM_ID`); adding `@node` flips the type to `NodeType`
and the field to `ChildField.ColumnBackedField`.

The classifier already holds every fact needed to say so at the rejection site: the parent is a
`TableType`, the SDL type implements `Node` (`TypeBuilder.implementsNode`), the field is the
interface-satisfying `id: ID!`, and `JooqCatalog.nodeIdMetadata(table)` is `Present`. The fix is a
targeted rejection ahead of the column lookup that names the missing `@node` and the metadata's own
`typeId` / `keyColumns` as the values it would take, so the message doubles as the migration recipe.

Scope note: this is the diagnostic, not the inference. Auto-promoting on `implements Node` +
metadata is the deliverable R273 records as *contradicted, not pending* (R473 makes the explicit
`implements Node @node` pair the source of node identity; R34 deliberately replaces silent promotion
with a hint offering to add the declaration; R27 records that metadata-based auto-promotion was
removed on purpose). Reopening that would be a change to R473's grammar decision, not this item.
The relationship to R34 is complementary and worth settling at Spec time: R34 step 3 plans a
hint-severity LSP finding at the *type* level offering `implements Node @node`, which is the
editor-side surface for the same signal. This item is the build-log surface for the *error* an
author already hit, which R34's warning does not change. Deciding whether one producing site feeds
both (a `BuildWarning.LintFinding` alongside the rejection) or they stay separate is Spec work.

## Reproduction

```graphql
type Baz implements Node @table(name: "film_actor") { id: ID! }
type Query { baz: Baz }
```

`schema.type("Baz")` is a `TableType`; `schema.field("Baz", "id")` is an `UnclassifiedField` whose
reason names the column lookup. With `@node` added, `NodeType` + `ColumnBackedField`.

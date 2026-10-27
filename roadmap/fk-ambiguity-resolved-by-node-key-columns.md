---
id: R1107
title: "Two foreign keys to a node type resolve to the one whose referenced columns are the node key"
status: Backlog
bucket: feature
priority: 5
theme: nodeid
depends-on: []
created: 2026-10-27
last-updated: 2026-10-27
---

# Two foreign keys to a node type resolve to the one whose referenced columns are the node key

## Goal

A table with two foreign keys to a node type's table, one onto the columns that identify the node and one onto some other unique constraint, no longer needs an explicit `@reference(path:)` on every field that crosses it. When the target is a node type and exactly one of the candidate foreign keys references precisely the node's key columns (`@node(keyColumns:)`, else the primary key), graphitron picks that one, for an object field and for an input `@nodeId(typeName:)` alike; zero or several matches keep today's "multiple foreign keys found" error. Today `JooqCatalog.findOutgoingFkToTable` returns `Ambiguous` whenever there is more than one foreign key, without looking at the referenced columns, and the callers in `BuildContext` (`fkCountMessage`) and `NodeIdLeafResolver` require exactly one. Requested in [GitHub issue #556](https://github.com/sikt-no/graphitron/issues/556), where the workaround is a `@reference(path: [{key: ...}])` on the field plus one `@referenceFor` per participant type.

Open for Spec: whether the rule applies to plain `@table` targets that are not node types (preferring the foreign key onto the primary key), and whether the implicit pick should be visible to the author, for example as an LSP hint, since adding a second matching key later turns a building schema into an error.

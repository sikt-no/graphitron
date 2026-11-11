---
id: R1173
title: "An element anchor's coordinate is a generated column, so no writer spells it and no check holds it to the key"
status: Backlog
bucket: architecture
priority: 6
theme: model-cleanup
depends-on: []
created: 2026-11-11
last-updated: 2026-11-11
---

# An element anchor's coordinate is a generated column, so no writer spells it and no check holds it to the key

## Goal

The coordinate of every element anchor (`graphql_type_element`, `graphql_field_element`, `graphql_argument_element`, `graphql_enum_value_element`, `graphql_directive_element`, `graphql_directive_argument_element`) and of `graphitron_field` and `graphitron_argument` is computed by the database from the key columns, `coordinate VARCHAR GENERATED ALWAYS AS (type_name || '.' || field_name)` and its siblings, instead of being spelled by the writer and held to the key by a `CHECK`. A coordinate is a rendered value of the key (`fact-model.adoc`, "The key discipline"), so the store can carry the rule rather than code recomputing it. When this lands, the eight `CHECK (coordinate = ...)` clauses, the writers' spelling of the coordinate in `GraphQLAstCapture` and `MacroExpansion`, and `CoordinateSpellingGateTest` are deleted: the rule can no longer be broken, so nothing has to test that it is refused.

## Evidence

* H2 2.5.250, which the store runs on, accepts a generated column under `UNIQUE (graph_name, coordinate)` and as the referencing side of `FOREIGN KEY (graph_name, coordinate) REFERENCES graphql_element ... ON DELETE CASCADE`. It refuses an explicit write to the column (error 90154), and `MERGE ... KEY (graph_name, type_name, field_name)` works. This was checked with a scratch program on 2026-11-11.
* The store already has a generated column, `graphitron_field.bound_name_upper`, and the writers already leave it out.
* `CoordinateSpellingGateTest` captures a schema only to supply foreign-key parents, then shows that H2 refuses a row the check forbids. Every fact document's capture already proves that the writers spell the coordinate legally.

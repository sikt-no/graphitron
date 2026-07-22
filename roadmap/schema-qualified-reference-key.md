---
id: R512
title: "Schema-qualify @reference(key:) to escape cross-schema FK-name collisions"
status: Backlog
bucket: feature
priority: 3
theme: codegen-correctness
depends-on: []
created: 2026-07-22
last-updated: 2026-07-22
---

# Schema-qualify @reference(key:) to escape cross-schema FK-name collisions

A `@reference(path: [{key: "<fk>"}])` foreign-key name is resolved by `JooqCatalog.findForeignKey(name, sourceSqlName)` across every schema in one flat namespace (SQL constraint name first, then the jOOQ `TABLE__CONSTRAINT` Java-constant form). When the same constraint name exists in two or more schemas and the source-table scoping cannot break the tie (the source is null, non-table-backed, or itself resolves ambiguously), the lookup returns `ForeignKeyLookup.Ambiguous` and the author sees an "ambiguous" rejection. Unlike `@table(name:)`, which accepts a schema-qualified `schema.table` form (`parseQualifiedTableName`, the audited schema-qualified-`@table` bug class), the `key:` grammar has no schema-qualified form, so a cross-schema constraint-name collision is not just ambiguous but unfixable by the author. This surfaces on real multi-schema databases (e.g. Samordna opptak) where constraint names collide across generated schemas. The fix is to let `key:` carry a leading `schema.` qualifier that scopes the FK candidate set to the FK-holder table's schema, symmetric to the `@table` precedent, leaving unqualified behaviour unchanged.

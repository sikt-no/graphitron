---
id: R152
title: "Scope @nodeId(typeName:) hover column lookup to the @node type's @table"
status: Backlog
bucket: bug
theme: lsp
depends-on: []
created: 2026-05-13
last-updated: 2026-05-13
---

# Scope @nodeId(typeName:) hover column lookup to the @node type's @table

`Hovers.formatNodeType` (the hover popup for `@nodeId(typeName: "X")`) renders X's `NodeMetadata.keyColumns` with each column's `graphqlType`, but resolves the column type via `columnGraphqlType(CompletionData, String)` which linear-scans every table in the catalog case-insensitively and returns the first match. The lookup is not scoped to X's `@table`-backing table, so when two tables in the catalog hold a column with the same name but different `graphqlType` projections (e.g. one mapped through a custom scalar via `@scalarType`, one not; or one nullable and one non-null), the hover renders whichever table the catalog enumerated first. Latent under Sakila (Sakila's recurring column names map to identical jOOQ-generated graphql types) but a real bug for schemas where same-named columns diverge. The sibling `columnHover` for `@field(name:)` / `@reference(key:)` / `@node(keyColumns:)` at the directive's own site already scopes via `TypeContext.enclosingTypeDefinition` + `tableNameOf` + `catalog.getTable`; the R100 hover diverges because the columns it renders belong to a different type than the one the cursor sits in. Fix: extend `CompletionData.NodeMetadata` to carry the `@table` name (`CatalogBuilder.buildNodeMetadata` already walks each `@node`-bearing `GraphQLObjectType` and can read the sibling `@table` directive in the same pass), then `formatNodeType` looks up `catalog.getTable(meta.tableName())` and searches columns inside it, mirroring `columnHover`'s shape. Add a hover test with two tables that share a column name with diverging `graphqlType` to pin the scoping. Surfaced during R100's In Review → Done review.

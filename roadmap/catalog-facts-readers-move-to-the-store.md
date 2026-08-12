---
id: R642
title: "CatalogFacts' non-LSP readers move to the store"
status: Backlog
bucket: architecture
priority: 2
theme: tooling
depends-on: []
created: 2026-08-12
last-updated: 2026-08-12
---

# CatalogFacts' non-LSP readers move to the store

Sibling of the LSP fact-store item (`lsp-reads-the-fact-store.md`), which retires the
`CatalogFacts` projection but cannot delete it while non-LSP consumers read it:
`GraphitronMcpServer`'s `catalog.tables` and `catalog.describe` tools, `EdgeProducer`, `EdgesTool`,
`ReverseEdgeIndex`, `NodeRef`, `CatalogDescriptors` and `CatalogSearchIndex` in `graphitron-mcp`,
plus `GraphQLRewriteGenerator`, whose output record carries the projection. These migrate to
store-side reads here, apart from the LSP work, because the MCP catalog tools have their own
acceptance surface (tool output, paging) that has nothing to do with cursors and buffers, and
because folding them into the LSP item would credit their `rewrite/catalog` lines to that item's
simplification measurement. Two constraints bind the siblings: `CatalogFacts` deletes in the same
commit as its last reader's migration, and both consumers read one shared store-side catalog view,
never a narrowing made for one of them (the `FactCapture.capture` javadoc already states this).
`TenantScopes` and `McpWire` cite the type only in javadoc and just repoint.

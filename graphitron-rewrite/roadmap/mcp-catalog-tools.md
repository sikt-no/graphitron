---
id: R362
title: "MCP catalog tools: catalog.tables / catalog.describe over raw jOOQ, with the live-catalog projection (D1)"
status: Backlog
bucket: feature
theme: lsp
depends-on: []
created: 2026-06-24
last-updated: 2026-06-24
---

# MCP catalog tools: catalog.tables / catalog.describe over raw jOOQ, with the live-catalog projection (D1)

Slice 2 of the R118 MCP-server programme, and the first structured *domain* tool slice on
the seam R361 landed. It ships two tools that let an MCP-aware agent discover the database
the schema wires to: `catalog.tables` (list tables, optional schema/name filter) and
`catalog.describe` (one table: columns, types, nullability, PK, unique keys, FKs in/out).
These anchor the programme's foundational greenfield-onboarding use case, pointing graphitron
at a large existing database with an empty schema and needing to discover what exists before
authoring anything. SQL names drive discovery; table/column comments surface only when jOOQ
codegen captured them, degrading to name-only otherwise.

## The central problem this slice owns (R361 D1)

R361 deliberately threaded only the live `Workspace` into `GraphitronMcpServer`, *not* a live
`JooqCatalog`, and handed this slice the job of solving the catalog-data projection. The raw
`JooqCatalog` cannot be held as a live handle: it reflects lazily against the `codegenLoader`
`URLClassLoader`, which `DevMojo.withCodegenScope` closes at the end of each pass, and it is
not part of `GraphQLRewriteGenerator.BuildArtifacts`. The durable, auto-refreshed projection
on the `Workspace` is `CompletionData` (table name, description, `classFqn`, columns, FK
references), but `catalog.describe` wants more than `CompletionData.Table` carries: PK / unique
keys, indexes, SQL-vs-Java column names, FK constraint names.

The design fork to settle in Spec (R361 D1 names both and states a preference):

- **Build-time enrichment (preferred).** Capture the richer catalog facts into `CompletionData`
  (or a sibling projection) at build time *while the codegen loader is still open*, so they ride
  the existing auto-refreshed artifact. Respects "classification belongs at the parse boundary":
  `JooqCatalog` is a sanctioned raw-jOOQ holder, and the MCP module stays on the consuming side
  of that boundary.
- **Retained-loader lifecycle.** Keep the `URLClassLoader` alive so a live `JooqCatalog` can keep
  reflecting on demand. The heavier alternative, flagged in D1 as the option that argues against
  itself.

## Open questions deferred from R118 to this slice

- **Result paging (R118 OQ5).** Large catalog queries can exceed MCP response limits; pick a
  default page size and a cursor for `catalog.tables`.
- **Comment-capture expectation (R118 OQ4).** Surface to consumer projects that comment-bearing
  output depends on jOOQ codegen capturing table/column remarks; degrade to name-only otherwise.
- **Name normalization (R118 OQ3).** Splitting snake_case/camelCase into readable descriptors is a
  slice-10 (`catalog.search`) concern, but the readable per-table descriptor composed here is what
  slice 10 embeds, so keep the projection reusable.

## Builds on

- **R361** (Done): the shared-model seam, the live `Workspace` handle and `tools` capability on
  `GraphitronMcpServer`, plus the liveness-tool registration shape these tools mirror. Landed on
  trunk, so a *Builds on*, not a `depends-on`.

## Related

- **R118** (`graphitron-mcp-server.md`, Backlog programme): slice 2 of the tool surface.
- Feeds **slice 7** (cross-reference edges, which walk table IDs this slice emits) and **slice 10**
  (`catalog.search`, which embeds the readable per-table descriptor this slice composes).

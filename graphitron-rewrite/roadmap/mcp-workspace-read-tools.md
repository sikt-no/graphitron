---
id: R368
title: "MCP structured read-tools over the live Workspace: services/conditions/records, schema, diagnostics, directives (R118 slices 3-6)"
status: Backlog
bucket: feature
theme: lsp
depends-on: []
created: 2026-06-24
last-updated: 2026-06-24
---

# MCP structured read-tools over the live Workspace: services/conditions/records, schema, diagnostics, directives (R118 slices 3-6)

Collapses R118 slices 3, 4, 5, and 6 into one item. These are the *thin* structured-tool
slices on the seam R361 landed: each reads a single projection already held live on the
`Workspace` and exposes it over MCP, mirroring R361's liveness-tool registration shape. Unlike
slice 2 (R362, raw-jOOQ catalog, which carries the D1 catalog-projection fork), none of these
needs new build-time data, a retained classloader, or the RAG stack; they are register-one-
tool-per-projection reads. Grouping them is a deliberate trade: they share a mechanical shape
and the cross-field-consistency caveat (R361 D3), so one reviewable unit beats four near-identical
ones. If parallel implementers are wanted, this splits cleanly back into four along the bullets
below.

The four reads:

- **Slice 3, code tools** (`services` / `conditions` / `records`): the `@service`- / `@condition`-
  referenceable classes and consumer record/POJO backings, categorised by the `ExternalReference`
  scan's return type (`Condition` -> condition; record components -> record; else service), with
  source locations joined from the `SourceWalker.Index`. The richest of the four; the one that may
  later grow an optional semantic method-search sibling (R118 slice 11).
- **Slice 4, schema tool** (`schema`): current types/fields, classifications, backing shapes,
  `@node` metadata, and definition locations off the `LspSchemaSnapshot`. The data is "the work
  itself", least stable, never embedded, always served live and structured.
- **Slice 5, diagnostics tool** (`diagnostics`): current validation errors/warnings off
  `Workspace.validationReport()`, closing the authoring loop (an agent edits, then reads its own
  diagnostics back).
- **Slice 6, directives resource** (`directives`): a directive-vocabulary cheat-sheet (arguments,
  applicable locations, descriptions) from `LspVocabulary` plus user-declared directives from the
  snapshot. Exposed as an MCP *resource* (standing pinned context), not a tool.

## Cross-cutting concerns for the Spec phase

- **Cross-field consistency (R361 D3).** `setBuildOutput` swaps the `Workspace`'s `snapshot` /
  `validationReport` / `sourceIndex` fields non-atomically; `volatile` gives per-field visibility
  but no consistent multi-field read. The schema (4) and diagnostics (5) tools are the first that
  could correlate fields (schema state against the diagnostics raised on it); R361 D3 explicitly
  pinned this as these slices' question. Decide whether per-tool single-projection reads are
  enough (likely yes) or a snapshot-consistency mechanism is warranted.
- **Stable cross-tool node IDs (R118 binding principle).** Every tool emits and accepts stable
  IDs: a schema coordinate `Type.field`, a method ref `Class.method/arity`. Slice 7 (cross-
  reference edges) walks these, so the IDs these tools emit are its input; settle the ID grammar
  here rather than retrofitting it.
- **Tool vs resource split.** Slices 3/4/5 are tools (invoked); slice 6 is a resource (pinned).
  Confirm the MCP resource wiring against the SDK alongside the `SyncToolSpecification` shape R361
  established.
- **Paging.** `schema` and `services`/`records` can be large; reuse the `limit` / opaque-`cursor` /
  `nextCursor` convention R362 settled for `catalog.tables` rather than inventing a second one.

## Builds on

- **R361** (Done): the shared-model seam, the live `Workspace` handle and `tools` capability on
  `GraphitronMcpServer`, and the liveness-tool registration shape every read here mirrors. Landed
  on trunk, so a *Builds on*, not a `depends-on`.

## Related

- **R118** (`graphitron-mcp-server.md`, Backlog programme): slices 3-6 of the tool surface.
- **R362** (`mcp-catalog-tools.md`): sibling slice 2; shares the page-cursor convention and the
  stable-ID grammar.
- Feeds **slice 7** (cross-reference edges), which walks the schema-coordinate and method-ref IDs
  these tools emit.

---
id: R374
title: "MCP cross-reference edges: forward edges + stable node IDs, then a reverse-edge index for impact analysis (R118 slice 7)"
status: Backlog
bucket: feature
theme: lsp
depends-on: []
created: 2026-06-25
last-updated: 2026-06-25
---

# MCP cross-reference edges: forward edges + stable node IDs, then a reverse-edge index for impact analysis (R118 slice 7)

Slice 7 of the R118 MCP-server programme: the slice that turns the independent structured tools
into a *traversable graph* without a graph database or a query language. It is the realisation of
R118's binding principle "stable cross-tool node IDs = edges": every tool already emits and accepts
stable identifiers (a schema coordinate `Type.field`, a table name, a method ref
`Class.method/arity`), a result names its neighbours by ID, and the agent traverses by following
those IDs through further tool calls. This slice makes that traversal first-class: it ships the
edges as data, not just as IDs an agent happens to recognise.

Now safely fileable: the ID-emitting slices it consumes have both landed. **R362** (slice 2,
catalog tools, Done) fixed the table / column ID shape and the page-cursor convention; **R368**
(slices 3-6, structured read-tools, Done) fixed the schema-coordinate (`Type.field`) and method-ref
(`Class.method/arity`) grammar, and pinned the per-method categorisation that the code-side edges
walk. Slice 7 builds on a settled ID vocabulary rather than inventing one, which is exactly why
R118 sequenced it after them.

## Scope, staged (R118 OQ6: ship forward edges first)

R118 is explicit that edges ship in order of proven need, not all at once:

1. **Forward edges + stable IDs (nearly free).** Each structured result already knows its outbound
   neighbours: a schema field names its backing table / column and its `@service` / `@condition`
   method; a table names its outbound FKs; a `@node` type names its table. This stage emits those
   as explicit typed edges on the existing tool results (or a thin `edges` projection), reusing the
   IDs R362/R368 already emit. No new index, no new traversal machinery.
2. **Reverse-edge index (impact analysis).** The high-value direction agents cannot cheaply walk
   forward: "what schema fields break if I touch this column / method?" This is a built-once
   reverse index keyed by the same stable IDs (table/column -> schema fields that bind it; method
   ref -> schema fields that wire it). It is the slice's real deliverable; the forward stage is
   mostly bookkeeping on data already in hand.
3. **(optional) `neighborhood` subgraph tool.** A single call returning the local subgraph around a
   node, materialised **only if round-trip count proves painful** in practice. R118 defers this
   explicitly; do not build it speculatively.

## Design questions for the Spec phase

- **Edge representation.** Whether edges ride inline on each structured tool's result (every result
  grows a `neighbours` / `edges` field) or live behind a dedicated edge/traversal tool that the
  others stay free of. The inline form keeps a single round trip but couples every tool's wire shape
  to the edge model; a dedicated tool keeps the structured slices' contracts clean. Lean toward not
  retrofitting the R362/R368 wire shapes if it can be avoided.
- **Reverse-index build site and freshness.** The reverse index is derived from the same
  `Workspace` projections R368 reads (snapshot + catalog), so it must refresh on the same dev
  triggers and tolerate the non-atomic multi-field swap R361 D3 / R368 pinned. Decide whether it is
  rebuilt eagerly on the build-output swap (mirroring R368's posture) or lazily on first traversal.
- **Edge typing.** Whether edges carry a typed kind (`backs`, `filters`, `references`, `resolves`)
  or are untyped adjacency. Typed edges make impact-analysis answers self-describing ("field X
  *filters on* column Y") at the cost of a small enum the Spec must enumerate exhaustively.
- **Catalog ID reconciliation.** R362 resolved its catalog facts onto a build-time `CatalogFacts`
  projection keyed by schema-qualified SQL name; slice 7's column/table edge IDs must use that same
  key so a forward edge from a schema field lands on the exact ID `catalog.describe` accepts.

## Builds on

- **R362** (Done): catalog tools; the table / column / FK ID shape and the `CatalogFacts` key the
  catalog-side edges traverse.
- **R368** (Done): structured read-tools; the schema-coordinate and method-ref ID grammar the
  schema- and code-side edges traverse, plus the per-method categorisation.
- **R361** (Done): the shared-model seam and the `Workspace` projections the reverse index derives
  from.

All landed, so *Builds on*, not `depends-on`.

## Related

- **R118** (`graphitron-mcp-server.md`, Backlog programme): slice 7, the traversal layer over the
  structured tools.
- Independent of the RAG track (**R372** slice 8 and its consumers); edges are structured, not
  semantic, and need no embedder or store.

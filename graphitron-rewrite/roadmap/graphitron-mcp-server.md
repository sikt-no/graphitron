---
id: R118
title: "Graphitron MCP server: knowledge-base tools for AI agents"
status: Backlog
bucket: feature
theme: structural-refactor
depends-on: [knowledge-base-programme]
---

# Graphitron MCP server: knowledge-base tools for AI agents

The first non-build consumer of the knowledge base R117 frames. AI agents working in a graphitron-generated codebase ask cross-cutting questions ("what classification produced the fetcher at `Film.actors`? which roadmap items mention it? which capability does it exemplify? which test exercises it?") that span the SDL, the codegen output, the runtime trace, and the roadmap. Today they grep across half a dozen file trees and reconstruct relationships from prose; the KB already holds these joins as natural-keyed tables, so the MCP server's job is to expose the joins as tools and let the agent ask SQL or call typed lookups. This item delivers the server, not the schema (which is R117's programme); the server queries whatever's in the KB and grows naturally as dimensions land.

## Why MCP, not a CLI or HTTP API

MCP is the protocol AI agents already speak. A graphitron MCP server slots into Claude Code, Cursor, and any other MCP-aware tool with no per-tool integration work; the agent picks up the tool list at session start and uses the lookups exactly as it would any other. CLI/HTTP would force every consumer to write a wrapper. The KB is also small (single-digit MB DuckDB file), read-only, and rebuilt on every `mvn install`, so the server's deployment shape is "open the file, expose tools" — no service, no replication, no auth boundary beyond filesystem permissions on the artefact.

## Tools sketched

| tool | input | output |
|---|---|---|
| `graphitron.coordinate` | `parent_type`, `field_name` | every fact graphitron knows about a coordinate: classification, generated fetcher, capability tags (exemplar + implicit), exemplifying operations, jOOQ backing, roadmap mentions, validator findings |
| `graphitron.capability` | `slug` | preamble prose, surface coordinates, worked examples (operation + facet + summary), exercising operations, related roadmap items |
| `graphitron.classification` | `name` | every coordinate producing this classification, every roadmap item mentioning it, every test exercising it, the sealed-variant family it belongs to |
| `graphitron.operation` | `document_path`, `operation_name` | description, body, variables, the capabilities it exemplifies, the coordinates it touches statically and dynamically, the test classes that run it |
| `graphitron.roadmap` | `R<n>` or slug | front-matter, body, dependencies, classifications/capabilities/coordinates referenced |
| `graphitron.search` | free-text query | full-text over docs, capability prose, operation descriptions, roadmap bodies |
| `graphitron.sql` | read-only SQL | direct query over the KB; for an agent that wants to ask its own question rather than ride a typed lookup |

The typed lookups exist for the common questions; `graphitron.sql` is the escape hatch so the server isn't bottlenecked on the author's enumeration. As R117's per-dimension items land, the typed lookups grow correspondingly.

## Deployment posture

Read-only against the DuckDB file emitted by `mvn install -Pleaf-coverage` (or whatever the build toggle becomes). The server doesn't write; the KB rebuilds on every build, so any "stale data" complaint is fixed by a rebuild, not by a write path. Authentication is filesystem-level: the server runs alongside the user's repo, queries the file in `target/`, and trusts that whoever has read access to the build output has the right to query its facts. No network exposure, no service to operate.

## Open questions for the Spec phase

1. *Schema discovery for the agent.* Should the server expose `graphitron.schema` returning the DuckDB table and view list with column types, so an agent using `graphitron.sql` can introspect first? Probably yes — the cost is one query and it dramatically reduces "agent guesses a column name."
2. *Multi-repo support.* Some users will run graphitron across several modules. The server probably accepts a glob over `**/target/leaf-coverage.duckdb` (or the renamed file) and unions results, with a `module` column auto-injected. Or it accepts one path and the agent handles federation. Decide during Spec.
3. *Result paging.* `graphitron.sql` against a large `fetcher_call` table can return thousands of rows. MCP tools cap response size; the server pages or summarises automatically. Pick a default page size and a "next-page-cursor" tool.
4. *What happens before R117 has lit up many dimensions?* The server still ships and exposes whatever's there. Tools that depend on absent dimensions return "this dimension is not yet populated; see R117 for the programme" rather than empty results. That's better feedback than a silent zero.

## Out of scope for this item

Designing the KB schema (R117's programme); writing per-dimension absorbers (per-dimension items under R117); a hosted/SaaS deployment model (the local-file model is the V0); auth beyond filesystem permissions; write tools (the KB is a projection, never written through the MCP).

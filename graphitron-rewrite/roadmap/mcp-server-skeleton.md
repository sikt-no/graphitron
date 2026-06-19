---
id: R341
title: "MCP server skeleton: Streamable HTTP in graphitron:dev with a static about-prompt"
status: Backlog
bucket: feature
theme: lsp
depends-on: []
created: 2026-06-19
last-updated: 2026-06-19
---

# MCP server skeleton: Streamable HTTP in graphitron:dev with a static about-prompt

Onboarding to graphitron means absorbing a lot of implicit context at once: that the schema-authoring loop runs under `mvn graphitron:dev`, that an LSP is bound on a loopback port, what the directives mean, and where the docs live. An agent (Claude Code, Cursor) sitting alongside a developer has none of this unless the developer pastes it in. This item delivers the smallest useful MCP server that closes that gap and, more importantly, establishes the transport and lifecycle foundation that the live-data discovery tools in R118 (`graphitron-mcp-server`) build on. It ships static content only; the value is proving the integration end to end so the substantive tools land on a known-good seam.

## Scope

- An MCP server embedded in the long-running `graphitron:dev` JVM, hosted over the MCP Streamable HTTP transport from the official MCP Java SDK on a lightweight embedded servlet container. stdio is unusable in this process because Maven (and, under `quarkus:dev`, Quarkus) write freely to stdout, which the stdio transport cannot share; this is the same constraint that pushed the LSP to a socket transport.
- Bound on a loopback address (`127.0.0.1`), matching the LSP's posture, on a hard-coded port `8488` (distinct from the LSP's `8487`). A configurable or overridable port is deliberately out of scope for the skeleton; see Deferred.
- The server exposes static content only: the `instructions` string returned in the MCP initialize handshake (always-on ambient context: what graphitron is, that the dev loop and LSP are running), plus a single `about` prompt (an on-demand explainer of the project and the dev loop, surfaced as a slash command in MCP-aware clients).
- Lifecycle: the server is a sibling of `DevServer`. It is constructed in `DevMojo.bindServer` alongside the LSP server, shares the one JVM, and is closed in `DevMojo.cleanup`. It does not read the live `Workspace` in this item.

## Developer ergonomics

- A committed, static `.mcp.json` in `graphitron-sakila-example`, hard-coding `http://127.0.0.1:8488/mcp`, is the worked example, so a developer configures the server once (or zero times, cloning the example) rather than re-adding it per session.
- On startup the server logs its URL and a copy-pasteable `claude mcp add --transport http graphitron http://127.0.0.1:8488/mcp` line.
- No config file is generated or mutated on startup. The committed static `.mcp.json` is the whole story for the skeleton.

## Out of scope (stays in R118)

Live catalog and schema tools over the warm `Workspace` (`catalog.tables`, `catalog.describe`, `schema`); documentation RAG; any vector store, embeddings, or ONNX model; the stdio-to-HTTP proxy for stdio-only clients.

## Deferred (fix later)

The skeleton hard-codes `8488`. The following robustness work is intentionally postponed; the analysis is recorded here so it is not re-derived later.

- Configurable port: a single `GRAPHITRON_MCP_PORT` knob read by both sides, the dev server's bind and the committed `.mcp.json` (via Claude Code's `${GRAPHITRON_MCP_PORT:-8488}` expansion, which is supported in `url` values and resolved from Claude's own environment at startup). One knob keeps the bound port and the configured port in lockstep.
- Bind-collision handling: when 8488 is already taken (a second concurrent `graphitron:dev` for a different project), the server must fail fast with a clear message rather than silently roam to another port. A silent fallback would leave the second project's committed `.mcp.json` still pointing at 8488, i.e. the first project's server, miswiring it with no error.
- Project-identity stamp: put the project root or module name into `serverInfo` and the `instructions` string, so that if a client ever connects to the wrong project's server the mismatch is visible rather than silently poisoning answers.
- Concurrent multi-project support generally, of which the two items above are the load-bearing parts.

Why hard-coding is safe to start: because the port never changes, the committed `.mcp.json` entry stays loaded for the life of a Claude session, so recovering from a dev restart needs at most a `/mcp reconnect` and never a Claude restart. (Verified against Claude Code docs: it retries an initial connection three times, auto-reconnects a mid-session drop up to five times with exponential backoff, and only a changed URL forces a config re-read and restart.) Binding the MCP port early in `graphitron:dev` startup makes the automatic-reconnect path the common one.

## Open questions for Spec

1. Which embedded servlet container hosts the SDK's servlet-based Streamable HTTP transport (embedded Jetty is the conventional choice, Undertow is lighter), plus the exact SDK and container artifact coordinates and versions, pinned against `graphitron-rewrite/pom.xml`.
2. Module placement: a dedicated module versus folding the transport glue into `graphitron-maven-plugin` next to `DevServer`. The static skeleton has no heavy dependencies, so this is mostly a question of where R118's later store and embedding dependencies will want to live.
3. The exact text and shape of the `instructions` string and the `about` prompt, and whether `about` takes any parameters.

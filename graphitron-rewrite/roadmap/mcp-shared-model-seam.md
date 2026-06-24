---
id: R361
title: "MCP shared-model seam: hand the live Workspace into GraphitronMcpServer and declare the tools capability"
status: In Review
bucket: feature
theme: lsp
depends-on: []
created: 2026-06-24
last-updated: 2026-06-24
---

# MCP shared-model seam: hand the live Workspace into GraphitronMcpServer and declare the tools capability

The MCP server R341 shipped (`graphitron-mcp/.../GraphitronMcpServer.java`) serves static
content only: it is constructed with just an `InetSocketAddress`, declares the `prompts`
capability alone (`ServerCapabilities.builder().prompts(false)`), and answers a single
argument-less `about` prompt plus the handshake `instructions`. It holds no reference to the
live generator model, so it cannot answer one question about the database catalog, the schema,
the classifications, or the diagnostics. Every structured tool in the R118 surface (slices 2-7:
`catalog.*`, `services`/`conditions`/`records`, `schema`, `diagnostics`, the `directives`
resource, cross-reference edges) is blocked on the server first holding that model.

The model already exists and is already refreshed. `DevMojo.execute` builds a live `Workspace`
(`graphitron-lsp/.../state/Workspace.java`) and hands it to the LSP `DevServer`
(`bindServer`, `DevMojo.java:143`/`183`); the schema / classpath / source watchers mutate that
same `Workspace` in place on every save and recompile (`setBuildOutput` / `demoteSnapshot` /
`refreshSourceIndex`). The MCP server is constructed two lines later in the same `bindServer`
with only an address (`DevMojo.java:197`), wired to nothing. This is the seam R118 names as
foundational: "the only new wiring is handing `GraphitronMcpServer` the live `Workspace` handle,
refreshed on the existing dev triggers."

## Goal

Hand the live `Workspace` into `GraphitronMcpServer`, declare the `tools` capability, and ship
exactly one minimal liveness tool that reads the live model end-to-end. This unblocks slices 2-7
without itself defining any domain-data tool's wire contract.

## Design

1. **Widen the constructor; wire it in `DevMojo`.** `GraphitronMcpServer(InetSocketAddress)`
   becomes `GraphitronMcpServer(InetSocketAddress, Workspace)`; `DevMojo.bindServer` passes the
   `workspace` it already built (the same instance handed to `DevServer`). The partial-bind
   unwind (close the LSP socket if the MCP bind throws) is unchanged. `DevMojoTest`'s injected
   `mcpPort` bind-failure path is unaffected: the `Workspace` param is orthogonal to the address.

2. **Refresh is automatic; no per-trigger re-push.** `Workspace`'s `catalog` / `snapshot` /
   `validationReport` / `sourceIndex` are `volatile` and swapped in place by the existing
   watchers. Holding the same mutable reference means a tool call reads the latest state on the
   next request; the seam adds no new trigger, listener, or refresh path. This mirrors how
   `DevServer.workspace()` already exposes the one shared handle to each LSP connection.

3. **Module edge: `graphitron-mcp` -> `graphitron-lsp`.** `Workspace` lives in `graphitron-lsp`
   and exposes types from `graphitron` (`CompletionData`, `LspSchemaSnapshot`, `ValidationReport`),
   so slice 1 adds a compile dependency on `graphitron-lsp` (transitively `graphitron`), replacing
   the skeleton's "depends on neither" posture. Acyclic: the plugin already depends on all three,
   and `lsp` / `graphitron` never depend on `mcp` (plugin -> {graphitron, lsp, mcp};
   mcp -> lsp -> graphitron). Orthogonal to the module's dependency-quarantine purpose, which is to
   keep the heavy native RAG stack (ONNX / Lucene, slices 8-10) off `graphitron-maven-plugin`'s
   compile surface; the `lsp` edge adds no such dependency. Note: `graphitron-lsp` carries the
   tree-sitter FFM native, so confirm the `lsp` edge does not surprise the published
   `graphitron-mcp` artifact's transitive natives, and add `--enable-native-access=ALL-UNNAMED`
   to this module's surefire `argLine` if the test JVM loads an LSP class that triggers the
   tree-sitter load.

4. **Declare `tools` and register one liveness tool.** Add `.tools(false)` to the
   `ServerCapabilities` builder (the boolean is `listChanged`) and register one
   `McpServerFeatures.SyncToolSpecification`, mirroring the `aboutPrompt(...)` wiring shape. The
   tool reports server liveness plus the snapshot state read off the live `Workspace` (shape in
   D2). This is the smallest call that proves the live read works end-to-end: `tools/list` becomes
   non-empty and `tools/call` exercises a real `workspace.snapshot()` read.

## Decisions

**D1 - Workspace-only seam; the live `JooqCatalog` is not threaded (deferred to slice 2).**
R118's slice-1 line reads "hand the live `Workspace` + jOOQ catalog," but the raw `JooqCatalog`
cannot be handed as a live handle: it reflects lazily against the `codegenLoader` `URLClassLoader`,
which `DevMojo.withCodegenScope` *closes* at the end of each pass, and it is not part of
`GraphQLRewriteGenerator.BuildArtifacts` (which carries only `CompletionData` +
`LspSchemaSnapshot.Built.Current`). The durable, auto-refreshed projection is `CompletionData` on
the `Workspace`, which already carries table name / description / `classFqn` / columns / FK
references. The "raw jOOQ for unmediated DB truth" that slice 2's `catalog.*` tools want (PK /
unique keys, indexes, SQL-vs-Java column names, FK constraint names; richer than
`CompletionData.Table`) is slice 2's to solve, by *either* enriching `CompletionData` (or a sibling
projection) at build time while the codegen loader is open, *or* standing up a retained-loader
lifecycle. The build-time-enrichment option respects "classification belongs at the parse boundary"
(`JooqCatalog` is a sanctioned raw-jOOQ holder; the MCP module must stay on the consuming side of
that boundary); the retained-loader option is the heavier alternative that argues against it.
Slice 1 flags this rather than silently dropping the R118 line.

**D2 - the liveness tool reports snapshot state on two axes, not domain counts.**
`LspSchemaSnapshot` is sealed over two *orthogonal* axes: availability (`Unavailable` vs `Built`)
and freshness (`Built.Current` vs `Built.Previous`). The tool reports these as two fields mapped
exhaustively off the sealed permits (availability; freshness, absent when unavailable), not a
flattened `Current | Previous | Unavailable` tri-state, which would splice two axes onto one and
re-derive in the MCP view a fork the model owns. It deliberately does not return domain counts
(table / external-reference / diagnostic counts): those are agent-facing wire schemas whose
semantics slices 2 / 3 / 5 own, and shipping them here would commit slice 1 to contracts those
slices must then stay consistent with. The tool is scoped as transport-and-liveness: "structured
tools ready" plus the two-axis snapshot read. If a single readable status token is later wanted,
derive it through a method *on* `LspSchemaSnapshot` (mirroring its existing `siteContext(...)`) so
the LSP and MCP read one answer; that method is not in slice 1's scope.

**D3 - per-field visibility only; multi-field consistency is each later slice's concern.** The
seam exposes the live `Workspace`; `volatile` gives per-field visibility on the next read, but
`setBuildOutput` swaps three fields non-atomically, so a tool that correlates (say) snapshot
against diagnostics has no consistent multi-field snapshot. Harmless for the liveness tool (one
field). Pinned here so slices 4 / 5 own the cross-field-consistency question rather than assuming
it.

**D4 - test tier: infrastructure.** Slice 1 emits no Java and classifies no schema; it is
transport-and-state glue, so the signal stays at the infrastructure tier, extending
`GraphitronMcpServerTest` (which already boots a real server and drives it with the real MCP
client). R361 adds a test that constructs `new GraphitronMcpServer(loopback(0), workspace)` with a
hand-built `Workspace(CompletionData)`, asserts `tools/list` advertises the liveness tool, and
asserts `tools/call` returns the snapshot state reflecting the workspace. To exercise the
`Built.Current` freshness arm (not just the default `Unavailable`), the test drives
`workspace.setBuildOutput(...)` before the call.

## Acceptance

- `GraphitronMcpServer` is constructed with a `Workspace`; `DevMojo` passes the live instance;
  existing bind / unwind behaviour is unchanged.
- `initialize` advertises the `tools` capability; `tools/list` returns the one liveness tool;
  `tools/call` on it returns the two-axis snapshot state read live off the `Workspace`.
- `graphitron-mcp` compiles against `graphitron-lsp`; the full `mvn install -Plocal-db` pipeline
  stays green; nothing heavy lands on `graphitron-maven-plugin`'s compile surface.

## Out of scope

- The structured domain tools and the `directives` resource (slices 2-7) and the RAG stack
  (slices 8-10).
- Any raw-`JooqCatalog` access / build-time catalog enrichment (D1; slice 2).
- A single-token snapshot-status method on `LspSchemaSnapshot` (D2) and any cross-field-consistency
  guarantee (D3).
- Configurable MCP port and the stdio-to-HTTP proxy (deferred by R118 / R341).

## Builds on

- **R341** (Done): the transport-and-lifecycle skeleton (`GraphitronMcpServer`, the Jetty bind, the
  `bindServer` unwind, `GraphitronMcpServerTest`). Not a `depends-on`: it has landed.

## Related

- **R118** (`graphitron-mcp-server.md`, Backlog programme): R361 is slice 1, the foundational seam
  every structured-tool slice (2-7) depends on. Filing the downstream slices as their own items
  follows once this lands.

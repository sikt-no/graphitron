---
id: R584
title: "MCP server instructions route agents to every tool family"
status: Backlog
bucket: feature
priority: 5
theme: tooling
depends-on: []
created: 2026-08-04
last-updated: 2026-08-04
---

# MCP server instructions route agents to every tool family

The shipped server instructions (`graphitron-mcp/src/main/resources/mcp/instructions.txt`) orient an
agent to exactly one tool family out of the eleven tools `GraphitronMcpServer` registers. A full
paragraph routes to `catalog.tables` / `catalog.describe`, naming the discovery keys and the comment
caveat. The other tools are never named: not `schema`, `diagnostics`, `edges`, `services`,
`conditions`, `records`, `docs.search`, `catalog.search`, or `status`. An agent's only other
orientation is each tool's own description, read one at a time out of the input schema.

The observed consequence is that agents re-derive from text what the wire already carries. Two
episodes from one live session against a consumer schema mid-migration:

- Asked to read diagnostics, the agent called `diagnostics`, then shelled out to `python3` over the
  harness-spilled tool-result JSON to count the entries and list their coordinates. The pre-paging
  total was already in the tool's own summary text (`DiagnosticsTool.diagnosticsResult` composes
  `"diagnostics: N entr(ies) … showing M"`).
- Needing the DELETE mutations and the table each targets, the agent ran
  `grep -rn 'typeName: DELETE'` across the whole monorepo. `schema(type: "Mutation")` already returns
  per-field `dmlKind` and the resolved `tableName`, projected from
  `FieldClassification.DmlMutation` in `SchemaView`.

Why it matters: every tool the server ships is a token-cost saving that pays only when an agent finds
it, and an undiscovered tool is worse than an absent one because the fallback returns weaker data. The
second episode's grep reads author intent out of SDL text rather than the classifier's verdict, gets no
table binding, and sweeps a whole monorepo instead of the schema under generation. The first burns a
subprocess round trip on a number the tool had already handed over.

Scope sketch: extend the instructions to cover the remaining tool families, keyed to the question an
agent is asking rather than to the tool list, since routing by question is what neither the tool
descriptions nor the current instructions do. Worth an enforcer: nothing asserts the instructions'
content today (`GraphitronMcpServerTest` pins the registered tool *list* with
`containsExactlyInAnyOrder`, not the orientation text), so the instructions silently fall behind every
newly registered tool. A coverage pin over the registered tool names, each either named in the
instructions or declared as deliberately needing no orientation, is the shape the module already uses
for partition invariants.

Two boundaries to record, both surfaced by the same session:

- **Overlap with the aggregated-diagnostics MCP item.** That item plans to append its routing
  paragraph to "the diagnostics guidance" in `instructions.txt`. No such guidance exists, so either
  that item writes the diagnostics orientation from scratch or this item does. Sequencing to settle
  when whichever item reaches Spec first.
- **Not every miss here is a routing failure.** For fields that failed classification the snapshot
  carries `Unclassified` plus a prose reason and no `dmlKind` / `tableName`, so an agent repairing
  broken delete mutations genuinely cannot get the DELETE-intent population out of `schema`. That is a
  model gap (the classified model discards author intent exactly where classification failed), noted
  in the aggregated-diagnostics item's Spec review and out of scope here.

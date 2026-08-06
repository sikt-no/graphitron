---
id: R598
title: "MCP warm-degradation messages carry their tool prefix"
status: Backlog
bucket: cleanup
priority: 5
theme: tooling
depends-on: []
created: 2026-08-06
last-updated: 2026-08-06
---

# MCP warm-degradation messages carry their tool prefix

The ambient MCP instructions promise an agent that "a paged result's first line summarises the whole
set, including the total before paging", and `ServerInstructionsTest.everyPagedToolLeadsWithTheUnpagedTotal`
pins that claim across the six tools that page through `McpWire.page`. The warm-degradation arms of
`docs.search` and `catalog.search` break the surrounding pattern: `WarmState.degradationMessage`
returns a bare notice carrying neither a tool prefix nor a count, so an agent that has learned to read
the first line gets a sentence about index warming with no coordinate telling it which call produced
it. `catalog.search`'s cold arm (`GraphitronMcpServer` composes the same message directly) and
`DocsSearchTool.degraded` are the two call sites, plus `CatalogSearchIndex`'s `SearchOutcome.Degraded`.

Prefixing those messages with the tool name (and, where one exists, a count) makes the first-line
convention uniform across the whole result surface rather than true only of the paged tools, and would
let the summary-line pin widen from the six paged tools to every list-shaped tool. Small main-code
change; the reason it is a separate item is that it is wire-visible and has its own argument about
what a degraded result should say, which is why the instruction-routing work deliberately scoped its
convention to the paged tools instead of widening the message format in passing.

Filed at the In Review → Done gate of the MCP instruction-routing item, whose Out of scope section
committed to filing it separately.

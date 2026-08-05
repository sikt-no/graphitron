---
id: R594
title: "Snapshot axis keys are consistent across the snapshot-reporting MCP tools"
status: Backlog
bucket: cleanup
priority: 3
theme: tooling
depends-on: []
created: 2026-08-05
last-updated: 2026-08-05
---

# Snapshot axis keys are consistent across the snapshot-reporting MCP tools

Four MCP tools report the live snapshot's availability and freshness, and they spell the two keys two
different ways. `McpWire.writeSnapshotAxes` writes `snapshotAvailability` / `snapshotFreshness` and its
javadoc states the reason for the prefix ("so the axes never collide with a tool's own payload
fields"); `diagnostics` and `edges` call it. `status` (`GraphitronMcpServer.statusResult`) and `schema`
(`SchemaView`) hand-roll the same exhaustive switch over the `LspSchemaSnapshot` permits and write bare
`availability` / `freshness` instead. So the shared helper documents a convention that half its
potential callers do not follow, and the divergence is duplication rather than a decision: neither
hand-rolled site has a stated reason for the shorter keys.

Why it matters: an agent reading structured content across tools cannot use one key to answer "is this
answer current". It has to know which of two spellings a given tool uses, which is exactly the kind of
per-tool fact the MCP surface otherwise works hard to make uniform, and the un-prefixed form is the one
the helper's javadoc argues against because it can collide with a payload field. The values are already
uniform (`Built` / `Unavailable`, `Current` / `Previous`) and the freshness key is correctly omitted
rather than null-valued on the unavailable arm in all four, so only the key names are at issue.

Both hand-rolled sites have a local reason to own their switch that a fix has to respect: `status`
composes a distinct summary sentence per arm and also emits `toolsReady`, and `SchemaView` threads the
freshness value into its `built()` helper. So the likely shape is to route the field writes through
`writeSnapshotAxes` while leaving each site's summary composition alone, rather than to collapse the
switches. Changing the emitted key names is a wire-visible change to `status` and `schema`; the existing
assertions on those tools in `GraphitronMcpServerTest` pin the current spelling and would need updating
in the same commit, which is the right forcing function.

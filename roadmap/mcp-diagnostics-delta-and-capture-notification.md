---
id: R913
title: "An agent re-reads every diagnostic to learn what one edit changed"
status: Backlog
bucket: mcp
priority: 2
theme: tooling
depends-on: []
created: 2026-09-02
last-updated: 2026-09-02
---

# An agent re-reads every diagnostic to learn what one edit changed

## Goal

An agent editing a schema against `graphitron:dev` learns what its edit did by calling `diagnostics` again and diffing two full lists in its own context, after guessing how long the capture takes and polling `status` in the meantime. When this item lands, the agent asks the MCP server for the diagnostics that changed since a cursor it holds and gets back three short lists: resolved, new, and still present (as a count), each entry the same `(file, location, message)` triple the LSP delivers. Alongside, the server tells the agent when a capture has finished rather than leaving it to poll, so the loop becomes edit, wait for the signal, read the delta. What changes for a consumer is that the agent's inner loop costs one small read per edit instead of a full re-read plus a guess about timing, and its verdict on an edit is the server's, not a diff it reconstructed itself.

## The problem in the tree today

The `diagnostics` tool is a projection of the fact store's `diagnostic` union view, paged at a hundred rows, and it reports the two `SchemaLifecycle` axes (availability, freshness) beside its payload. Neither axis says *which* capture the payload came from: the store carries `store_graph.build_file_stamp`, which is build identity (the recipe's content hash), not pass identity. The `diagnostic` view has no pass column either, so nothing an agent can hold today names "the state I last read", and nothing on the wire says "a newer pass has landed since".

On the notification side, the MCP server registers with `listChanged` and `subscribe` capabilities off, because the tool and resource lists are fixed for the server's lifetime. The dev loop does have the signal: the LSP's `GraphitronTextDocumentService` hangs a recalculate listener on the workspace and re-publishes diagnostics per URI when a pass commits. The MCP server sits in the same JVM on the same store and does not listen.

## Sketch for Spec

Two halves, each independently useful and shippable in either order.

**Delta read.** A `since` argument on `diagnostics` (or a sibling `diagnostics.delta`, the Spec decides) taking an opaque cursor the server minted, returning `resolved`, `added`, and an `unchanged` count, plus a fresh cursor. The open design question is what the cursor names. Two arms to weigh:

- *A pass identity in the store.* The run-record families item wants committed pass rows in the store anyway; a `diagnostic` row keyed by the pass that produced it lets the delta be a set difference in SQL between two pass partitions, with the cursor a pass id. This is the principled arm and the one that survives a dev-server restart, but it lands a store schema change and depends on that item's shape.
- *A server-held snapshot.* The MCP server keeps the diagnostic set it last served per cursor in memory and diffs on the next call. Cheap and needs no store change, but the cursor dies with the process and the server grows state the store does not hold, which cuts against every other tool answering off the store alone.

The delta's entry identity must be the same triple the `diagnostics` tool already spells, so a resolved entry in the delta is one the agent saw in a plain listing. Filters (`severity`, `coordinate`, and the aggregate-group dimensions) apply to the delta the same way they apply to the listing, through the shared `DiagnosticFacets.conditions` translation, so the two cannot disagree about membership.

**Capture-finished notification.** Turn on the MCP `subscribe` capability for one resource (a `capture` or `status` resource, spelled in Spec) and emit `notifications/resources/updated` from the same recalculate hook the LSP listens on. An agent that subscribes stops polling `status`; one that does not sees no change. The notification carries no payload beyond the resource URI, per protocol; the agent follows it with a delta read. Whether MCP clients in the field (Claude Code, Cursor) surface resource-updated notifications to the model is an input to Spec, since a signal no client relays is not worth the capability flag.

## Out of scope

Diagnostics for the compile oracle already flow through the same view and need no special casing. Pushing the diagnostics *payload* in a notification is not proposed: the read tools stay the one place answers come from, and the notification only says "read again".

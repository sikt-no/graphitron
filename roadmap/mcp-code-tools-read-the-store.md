---
id: R681
title: "The MCP code tools read the store"
status: Backlog
bucket: architecture
priority: 2
theme: tooling
depends-on: []
created: 2026-08-14
last-updated: 2026-08-14
---

# The MCP code tools read the store

The `services`, `conditions`, `records` and `edges` tools read two generator-side projections that
the LSP's fact-store migration retires: `CompletionData.ExternalReference` (the classpath census the
three code tools list, and the arity reconciliation `EdgeProducer` does) and `SourceWalker.Index`
(the Javadoc and source-position join behind their `location` / `locationStatus` wire fields, read
through `Workspace.sourceIndex()`). The store already carries both at their own grain: the `jvm_`
family holds the bytecode census, and the java-source family holds Javadoc and positions on the
source's own cadence, which is why the LSP's own hover and goto-definition already read them there.

Until these repoint, `SourceWalker.Index`, `Workspace.sourceIndex` and its setter survive purely to
feed another module, which is the cross-consumer private model the architecture docs argue against.
The LSP item (`lsp-reads-the-fact-store.md`) states as much and defers the repoint to a sibling; it
is filed here rather than in the catalog-readers sibling
(`catalog-facts-readers-move-to-the-store.md`) because these are different relation families, a
different acceptance surface, and share no query with the catalog reads. The two siblings agree on
where shared store readers live and on the graph-scoped handle; neither may narrow a reader for its
own consumer.


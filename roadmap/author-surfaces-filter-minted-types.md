---
id: R895
title: "Author-facing surfaces cannot tell a minted Connection type from an authored one"
status: Backlog
bucket: bug
priority: 3
theme: lsp
depends-on: []
created: 2026-08-31
last-updated: 2026-08-31
---

# Author-facing surfaces cannot tell a minted Connection type from an authored one

`@asConnection` mints types. For a carrier field the author wrote, the macro creates
`<Carrier>Connection`, `<Carrier>Edge` and a shared `PageInfo`, and rewrites the carrier field's type
to the Connection. Those minted types now live in `graphitron_minted_type` and `graphitron_minted_field`,
separate from the transcription of what the author actually declared, and a union view resolves the
two for readers that want the expanded population.

**Four author-facing surfaces in the language server read the expanded population without filtering
the provenance**, so a minted type is indistinguishable from one the author wrote:
`no.sikt.graphitron.lsp.completions.ArgNameCompletions`,
`no.sikt.graphitron.lsp.facts.SdlTypeUsages`, `no.sikt.graphitron.lsp.facts.SdlDescriptions` and
`no.sikt.graphitron.lsp.diagnostics.DiagnosticFacts`.

**What is not yet established is whether that is a defect at all, and the answer is probably not the
same for all four.** An author who wrote `@asConnection` did cause those types to exist, and offering
them in a completion may be exactly right. A diagnostic pointing at a source location inside a type
nobody wrote is a different matter, because there is no location to point at. A hover description for
a minted type has no author-written description to show. Each surface has to be asked separately what
it means to show a type the author did not type.

The mechanical part is now easy and was not before: the provenance is a row, so any of these surfaces
can filter on it with a join rather than a name heuristic. What this item owes is the four decisions,
not the filter.


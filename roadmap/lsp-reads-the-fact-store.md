---
id: R638
title: "The LSP reads the fact store instead of the catalog projection seam"
status: Backlog
bucket: architecture
priority: 2
theme: lsp
depends-on: []
created: 2026-08-12
last-updated: 2026-08-12
---

# The LSP reads the fact store instead of the catalog projection seam

The language server answers hover, completion, go-to-definition, inlay hints, diagnostics and code
actions out of `CompletionData`, a pre-baked in-memory projection of four fixed collections built
per pipeline round. That shape bounds what the LSP can offer: it can only ask what someone
projected in advance, its lookups are linear scans over lists (`getTable` streams and filters),
and the projection accretes a field whenever a consumer needs something new, which its own
backwards-compatible three-argument constructor records. The fact store holds the same knowledge
as queryable relations carrying source line and column, so hover and hints could ask questions
nobody pre-projected: which columns of a table sibling fields already claim, what the foreign-key
graph reaches from here, what the claim strata resolved and why. The store is also the one place
where a new fact family becomes available to every reader at once, and the language server is
currently the reader that cannot see it: it imports no store relation at all, while the generator
module reads sixteen and the MCP server two.

Scope is the six feature packages (roughly 4,800 lines), not the module. Tree-sitter parsing stays
where it is, because the store only ever reflects the last successful capture and the server must
still answer on an unparseable mid-edit buffer; protocol lifecycle and tracing stay too. The
migration surface is the call sites reading `CompletionData`, `LspSchemaSnapshot`,
`SourceWalker`, `FieldClassification`, `TypeBackingShape` and `DirectiveShape`.

Three things make this cheaper than it looks. The module already depends on `graphitron`, so the
store's generated `Tables` is on its classpath with no new dependency. The dev session already owns
one live store handle and already hands it to the MCP server, so the same handle reaches the
workspace the same way. And the availability seal the LSP needs is something the store provides by
persistence rather than by type: a failed round leaves the previous rows in place, which is what
`Built.Previous` exists to express, and `last_captured` is a timestamp where the seal has a
two-valued enum.

Two open questions the plan has to answer rather than assume. Per-keystroke query latency against a
warm embedded handle, measured, not argued from the fact that a map lookup feels faster than SQL;
the incumbent is a linear scan, so the comparison may go either way. And thread safety of sharing
one `DSLContext` across the server's request threads and the build thread, which the MCP precedent
does not cover because that server is turn-based and this one is not.

Doing hover first, as a measured pilot rather than a slice of a big-bang port, also settles an open
architectural question recorded in the history page: the store has exactly one consumer besides the
generator, so the claim that a relational core lowers the cost of an additional consumer currently
has one data point to divide by. A ported hover makes it two, measured against the seam
implementation it replaces.

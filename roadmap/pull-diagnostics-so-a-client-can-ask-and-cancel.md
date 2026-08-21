---
id: R797
title: "Pull diagnostics, so a client can ask, cancel, and be told to ask again"
status: Spec
bucket: feature
priority: 5
theme: lsp
depends-on: []
created: 2026-08-21
last-updated: 2026-08-21
---

# Pull diagnostics, so a client can ask, cancel, and be told to ask again

The server publishes diagnostics and never answers for them. `ServerCapabilities` advertises hover,
completion, definition, code actions and inlay hints, and no `diagnosticProvider`, so the only channel
is the `textDocument/publishDiagnostics` notification the drain pushes. Three consequences follow from
that, and all three are the protocol's, not ours.

A client cannot ask. It takes what arrives, when it arrives, for whichever files the server decided
to walk. A client cannot cancel: there is no request to cancel, so an editor that has moved on from a
file still receives the answer for it. And the server cannot say "I did not finish, ask me again". Our
one honest thing to say when a read overruns its budget is exactly that, and push has no shape for it,
which is why the drain's `StoreAnswer.OutOfBudget` arm publishes nothing at all and the developer is
left with squiggles that silently stopped tracking the schema.

LSP 3.17 answers all three with pull diagnostics, and lsp4j 0.24.0 carries the types.

## Stage 0, which gates everything after it

Pull diagnostics is negotiated: it exists for a session only if the client advertises
`textDocument.diagnostic`. So the first deliverable is a fact, not code. The server already receives
the client's capabilities at `initialize`; log what it advertises, run the dev loop from the editor
that prompted this item, and read the line.

If the client does not advertise it, this item buys that developer nothing and should be parked
rather than built, because push remains their only channel. It is worth saying plainly that this item
is not what fixes an editor freezing on a slow drain. The asynchronous-drain item beside it has since
shipped: the drain now leaves the triggering thread, that fix was client-independent, and it landed
without waiting on what stage 0 finds here.

## What changes, if stage 0 says yes

**Advertise, and keep push for everyone else.** `diagnosticProvider` with `interFileDependencies`
true, since a file's diagnostics are the graph's capture judging it and a sibling edit changes them,
and `workspaceDiagnostics` true. Clients that advertise nothing keep exactly today's behaviour, so
the push path stays rather than being replaced.

**One place holds the answer to "is this session pulling?".** Naming it is part of this item, because
the three objects involved do not talk today. `GraphitronLanguageServer.initialize` is where the
client's capabilities arrive and it currently hands `textService` nothing. The push decision is taken
in `GraphitronTextDocumentService`, which owns the client proxy and the drain. And
`markAllForRecalculation` is on `Workspace`, which reaches a client only through the `Runnable`
listener the document service installs, and which knows nothing about the protocol at all. The last
of those is worth keeping: `Workspace` should stay protocol-free, its listener meaning no more than
"something changed", and the document service should remain the only object that decides what goes on
the wire. So the negotiated flag is read once from `InitializeParams` and handed to the document
service, which then either pushes or sends a refresh. No `ServerCapabilities` state leaks into
`Workspace`.

**Two handlers over the batch that already exists.** `textDocument/diagnostic` answers one document
and `workspace/diagnostic` answers the set, both built from `Diagnostics.Batch`, which is already
shaped for this: it walks a set, resolves membership once, reads one statement per graph and judges
per document. A single-document pull is the batch with one member, which is what the interactive
`diagnostic` path in `Diagnostics` already does. Both are overloads of `diagnostic` on lsp4j's
`TextDocumentService` in 0.24.0, despite one of the two protocol methods being workspace-scoped, so
neither belongs on `GraphitronWorkspaceService`.

**An overrun becomes an answer.** The `OutOfBudget` arm maps to a `ResponseError` with code
`ServerCancelled` carrying `DiagnosticServerCancellationData` with `retriggerRequest` true. That is
the protocol saying what our sealed arm has always meant: no answer, nothing fabricated, ask again.
It is strictly better than the push path's silence, and it is the reason this item is worth building
even after the drain is fast.

**A build invites a re-pull.** Where a session is pulling, `markAllForRecalculation` sends
`workspace/diagnostic/refresh` instead of pushing, so the client re-asks for what it currently cares
about rather than the server guessing at the set.

## Deferred deliberately, with reasons

* **Partial results.** `workspace/diagnostic` supports a `partialResultToken`, and streaming per-file
  chunks is the feature that would make a slow sweep useful rather than merely cancellable. It needs
  the read split per file or per graph-relation, which trades away the one-snapshot consistency the
  batch exists to provide and multiplies the statement count that
  `roadmap/completion-and-code-action-statement-counts.md` is about pinning. Its own item, after this
  one, with that trade stated as the question.
* **`unchanged` reports.** Answering `DocumentDiagnosticReportKind.Unchanged` against a `resultId`
  needs an identity for "the capture this answer came from", and the store publishes no such identity
  today: a capture is a transaction, not a numbered round. Adding one is a store-side change with its
  own consumers, so the first cut answers `full` every time, which is correct and merely not
  optimal.

## Verification

No harness states a client capability today, so this needs saying rather than assuming. The two
`RecordingClient` implementations are private nested classes in
`BuildTriggerPublishesDiagnosticsTest` and `StoreOutOfBudgetTest`, and both construct a
`GraphitronTextDocumentService` directly and never call `initialize`, which is why they see no
capabilities: the handshake is not in their path. That is a consequence of the flag living on the
document service rather than a problem with it. Handing the flag in at construction, as the section
above has it, makes both branches of the negotiation two harnesses that differ in one argument, in
the tier the diagnostics tests already sit in: a client that advertises the capability is answered on
request and never pushed to, and a client that does not is pushed to exactly as today. Covering the
handshake itself, that `initialize` reads `textDocument.diagnostic` and hands the right value on,
belongs in `TextDocumentServiceTest`, which is the one harness that already drives a real launcher
proxy through `initialize`. The overrun case belongs beside the existing one in
`StoreOutOfBudgetTest`, which already provokes a real overrun through `RunawayRelation`: a pulling
client receives a `ServerCancelled` error carrying `retriggerRequest`, where a pushing client receives
nothing on the wire.


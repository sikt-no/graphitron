---
id: R1075
title: "An LSP round-trip test times out on a 5-second wait while the reactor saturates the runner"
status: Backlog
bucket: cleanup
priority: 3
theme: testing
depends-on: []
created: 2026-10-20
last-updated: 2026-10-20
---

# An LSP round-trip test times out on a 5-second wait while the reactor saturates the runner

## Goal

`TextDocumentServiceTest`'s round trips stop failing trunk CI on a slow runner, so a red
`graphitron-lsp` means a language-server defect and not that the machine was busy. Today every
request the class sends over the wire (`initialize`, `completion`, `hover`, `definition`,
`references`) waits through `CompletableFuture.get(5, TimeUnit.SECONDS)`, and CI runs the reactor
with `-T 1C`, so `graphitron-lsp`'s tests share the runner with `graphitron`'s 30-minute suite,
`graphitron-mcp` and `roadmap-tool`. Under that load the module's test classes run two to five
times slower than on a quiet machine (`FieldCompletionsTest` above 520 s, `DiagnosticsTest` above
450 s), and a 5-second wall-clock cap on one request is a guess about scheduling rather than a
statement about the server.

`completionAfterMultiByteDescriptionResolvesCorrectDirective` has failed with a
`java.util.concurrent.TimeoutException` in four trunk runs between 2026-10-17 and 2026-10-19:
#6798 (`9d984eb`), #6799 (`2d0609e`), #6817 (`4ed5156`) and #6825 (`0e6faef`). The call that timed
out differs: `initialize` at line 115 in #6825, `completion` at line 123 in the other three. So no
single request is hanging. The method itself took 63 to 107 s, nearly all of it building
`StoreFixture.ofCatalog` before `labelsAt` sends anything; its sibling
`completionRequestRoundTripsThroughTableCompletions` runs the same `labelsAt` flow on the same
fixture and passed in every one of those runs, which fits a timing window rather than a
multi-byte defect. The file has not changed across the failing commits.

The likely shape is to stop treating a fixed per-request wall-clock bound as the assertion:
a generous bound kept only as a hang guard, one place that owns it rather than sixteen literal
`get(5, TimeUnit.SECONDS)` calls, or a wait whose failure says the server never answered. Whether
the module's test scheduling against the reactor is also part of the answer is for the Spec.

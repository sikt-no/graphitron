---
id: R782
title: "Completions and code actions get statement-count enforcers"
status: Backlog
bucket: architecture
priority: 4
theme: lsp
depends-on: []
created: 2026-08-21
last-updated: 2026-08-21
---

# Completions and code actions get statement-count enforcers

Six language-server surfaces read the fact store, and four of them are pinned at O(1) statements per
request by a test that also asserts the count does not track the document's size:
`InlayHintStatementCountTest`, `DeclarationHoverStatementCountTest`,
`DeclarationDefinitionStatementCountTest` and `DiagnosticsStatementCountTest`. `Completions` and
`CodeActions` read the store through the same `StoreAccess.answering` door and are pinned by none of
them.

Why that matters now rather than as a tidiness point. A store read is bounded by a `ReadBudget`, and
that budget bounds one *statement*, not one request: where a statement count is also pinned, the
product of the two is what bounds a request, and neither enforcer is sufficient alone. For these two
surfaces the budget is the only bound, so a request's store time is the budget times an uncounted
number of statements. Completion is also the most latency-sensitive surface there is, being the one
an author blocks a keystroke on.

The work is two more tests in the existing tier, modelled on the four that exist, and no production
change is expected. The tier's own rule holds: it counts statements and asserts no duration, so
neither test may grow a wall-clock assertion. If either surface turns out to issue a count that
tracks the document, that is a finding for its own item rather than something to absorb here.

---
id: R149
title: "End-to-end LSP publish-diagnostics test and buildOutput report-population test for R147"
status: Backlog
bucket: test
priority: 5
theme: lsp
depends-on: []
created: 2026-05-12
last-updated: 2026-05-12
---

# End-to-end LSP publish-diagnostics test and buildOutput report-population test for R147

R147's spec called for two tests that the implementation deferred:

- **End-to-end LSP test** under `graphitron-rewrite/graphitron-lsp/src/test/...` that drives a schema through `Workspace.setBuildOutput` and asserts the published `PublishDiagnosticsParams` on the client side carries the expected diagnostic. The current `ValidatorDiagnosticsTest` exercises `Diagnostics.compute` directly per-call; the wire-shape contract — that `setBuildOutput → markAllForRecalculation → drainRecalculate → publishDiagnosticsForRecalculate` actually fires a `client.publishDiagnostics(PublishDiagnosticsParams)` — is unverified. The "empty report clears previous diagnostics" assertion in particular was reduced from "two successive `setBuildOutput` calls produce a non-empty then an empty `PublishDiagnosticsParams.diagnostics`" to "two successive `Diagnostics.compute` calls produce non-empty then empty lists"; the wire-level clearing signal isn't pinned.

- **`GraphQLRewriteGeneratorTest`** asserting `buildOutput()` populates `BuildOutput.report().errors()` and `.warnings()` end-to-end. The current `ValidationReportTest` covers the `from(...)` factory in isolation and `ValidatorDiagnosticsTest` covers the consumer side, but the producer-side wiring (validator pass + `bundle.model().warnings()` flow through `buildOutput`) is unverified. Skipped in R147 because driving `buildOutput()` requires a real jOOQ catalog and the existing pipeline tests stop at `loadAttributedRegistry`.

Failure mode if these tests are skipped permanently: a future refactor breaking the publish path or the warnings-into-report wiring would slip through; the LSP client would silently stop receiving diagnostics, or warnings would disappear from editor squiggles. The build-time log surface stays correct because that path still exists, but the LSP-only regression has no other test gate.

Implementation hooks:
- For the end-to-end LSP test: instantiate a `GraphitronTextDocumentService` with a captured `LanguageClient` stub (record `publishDiagnostics` calls in a `List<PublishDiagnosticsParams>`), call `setClient`, drive `didOpen` then `setBuildOutput` with a hand-built `ValidationReport`, and assert the captured params.
- For the `buildOutput` test: either set up a minimal `RewriteContext` against the test jOOQ catalog (`no.sikt.graphitron.rewrite.test.jooq`) or stub `JooqCatalog` behind a test-only seam. The cheaper option is the test catalog since pipeline tests already wire it.

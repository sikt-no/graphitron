---
id: R149
title: End-to-end LSP publish-diagnostics test and buildOutput report-population test for R147
status: In Review
bucket: test
priority: 5
theme: lsp
depends-on: []
created: 2026-05-12
last-updated: 2026-06-08
---

# End-to-end LSP publish-diagnostics test and buildOutput report-population test for R147

R147's spec called for two tests that the implementation deferred. **Bullet 1 (the end-to-end LSP publish-diagnostics test) shipped under R196** as `BuildTriggerPublishesDiagnosticsTest.setBuildOutputPublishesDiagnosticsForOpenFiles`: it drives `Workspace.setBuildOutput` against a wired `Workspace` + captured `LanguageClient` and asserts the wire `PublishDiagnosticsParams` is non-empty after an error report, then empty after `setBuildOutput(ValidationReport.empty())`, pinning the wire-level clearing signal. This item is now narrowed to the one remaining test:

- **`buildOutput()` report-population test.** Assert `GraphQLRewriteGenerator.buildOutput()` populates `BuildOutput.report().errors()` and `.warnings()` end-to-end. The current `ValidationReportTest` covers the `ValidationReport.from(...)` factory in isolation and `ValidatorDiagnosticsTest` covers the consumer side, but the producer-side wiring (the validator pass over `bundle.model()` plus the `bundle.model().warnings()` flow through `buildOutput`) is unverified. Skipped in R147 because driving `buildOutput()` requires a real jOOQ catalog and the existing classification pipeline tests stop at `loadAttributedRegistry` / `bundle.model()`.

Failure mode if this test is skipped permanently: a future refactor breaking the warnings-into-report wiring or the validator pass would slip through; warnings would silently disappear from editor squiggles. The build-time log surface stays correct because `validate()` still exists, but the LSP-report regression has no other gate.

Implementation hook: set up a minimal `RewriteContext` against the test jOOQ catalog (`DEFAULT_JOOQ_PACKAGE`) with a hand-written schema input, the shape pipeline tests already use (see `TaggedInputsPipelineTest`). Drive a schema that produces both a validator error (an unresolvable `@reference(key:)` -> `UnclassifiedField`) and a build warning (a redundant `@record`), then assert both halves of `buildOutput().report()` are populated.

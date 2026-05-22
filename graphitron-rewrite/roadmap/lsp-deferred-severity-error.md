---
id: R225
title: LSP severity for Rejection.Deferred should be Error, not Warning
status: In Review
bucket: lsp
depends-on: []
created: 2026-05-22
last-updated: 2026-05-22
---

# LSP severity for Rejection.Deferred should be Error, not Warning

The LSP maps `Rejection.Deferred` to `DiagnosticSeverity.Warning` in `Diagnostics.severityOf` (R147), but the build path throws `ValidationFailedException` on **any** non-empty validator error list, including `Deferred` ones (`GraphQLRewriteGenerator.java:134`, `:173`); `DevMojo.runGeneratorPass` then logs the whole tree at ERROR level. From the schema author's perspective the editor squiggle is yellow but `mvn graphitron:dev` is red on the same item, e.g. `@splitQuery` with a `ConditionJoin` step on `SplitRowsMethodEmitter.java:330`. R129 (`column-reference-on-scalar-field-condition-join.md`) records the intent explicitly: "the SDL author sees a build-time error rather than a runtime stub." The "Deferred is not author-actionable" rationale that motivated the Warning mapping doesn't earn a softer color when the consequence is identical (build fails, no resolver generated); the roadmap-item slug carried by the rejection is the actionable hint, not the severity. Flip the mapping to `Error` and update the paired tests (`ValidatorDiagnosticsTest.deferredMapsToWarningSeverity`, `RejectionSeverityCoverageTest` sample stays unchanged — the coverage test only asserts non-null).

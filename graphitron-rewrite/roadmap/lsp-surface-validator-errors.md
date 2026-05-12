---
id: R147
title: "Surface GraphitronSchemaValidator errors and warnings as LSP diagnostics"
status: Backlog
bucket: feature
priority: 5
theme: lsp
depends-on: []
---

# Surface GraphitronSchemaValidator errors and warnings as LSP diagnostics

`GraphitronSchemaValidator.validate()` and `GraphitronSchema.warnings()` are the build pipeline's voice for everything that survives parse and classification but still rejects: `paginationRequiresOrdering`, multi-table participant PK arity, nested-parent compat, `@reference` shape errors, deferred-variant rejections, `UnclassifiedType` / `UnclassifiedField` fallbacks, and the connection-`totalCount` type check, plus the warning channel (`-parameters` missing, etc.). Today these reach the developer only as `path:line:col: error/warning: ...` lines in the SLF4J log (`GraphQLRewriteGenerator.validateAndLogErrors` / `logWarnings`). A developer driving SDL edits through the LSP gets no editor-side signal for any of them — the watch-mode dev console is the sole surface.

The LSP's own `Diagnostics.compute` is independent and SDL-only: unknown directive args, missing required args, catalog table/column/FK existence, classpath class/method existence, and the unknown-directive arm wired to the R139 snapshot. It does not call the validator and has no access to its output. Closing the gap means lifting the validator's findings onto the existing dev-pipeline → LSP side-channel and merging them into the per-file diagnostic list `GraphitronTextDocumentService` already publishes.

The seam is small and already cut. R139 added `Workspace.setCatalogAndSnapshot(CompletionData, LspSchemaSnapshot.Built)` and the per-buffer recalculation loop in `GraphitronTextDocumentService.publishDiagnosticsForRecalculate`. The validator output is a `List<ValidationError>` (with `SourceLocation` carrying `sourceName`, `line`, `column`) plus a parallel warning list off the built schema. Plumbing a third volatile field on `Workspace`, swapped in lockstep with the catalog/snapshot pair on every successful build, lets `Diagnostics.compute` filter by file URI and emit one LSP `Diagnostic` per `ValidationError` that lands in the buffer the user is editing.

Open questions for the Spec body (do not pre-empt here):

- Severity mapping. `ValidationError.kind()` projects the `Rejection` sealed hierarchy; `Deferred` rejections (stubbed variants, unsupported nested-depth leaves) plausibly want `Information` or `Warning` rather than `Error`, since the schema is structurally valid but the generator hasn't shipped the path. `InvalidSchema` and `AuthorError` are clearly `Error`. Warnings (`GraphitronSchema.warnings`) map to `DiagnosticSeverity.Warning`. The Spec decides the per-kind map.
- Stale-snapshot policy. The R139 precedent (`Diagnostics.java:81-86` `@DependsOnClassifierCheck`) silences warnings under `Unavailable` and `Built.Previous`. Validator errors are structurally similar — they reflect a build that has since become stale. Default to the same policy unless the Spec finds reason to diverge.
- Range mapping. `SourceLocation` is `(line, column)`; LSP `Range` needs a span. The simplest mapping is a zero-width range at the location; a richer mapping would extend to the end of the line or to the relevant token. Decide in Spec; the zero-width default is probably fine for v1.
- File matching. `SourceLocation.getSourceName()` is the schema file path (populated by `RewriteSchemaLoader`'s `MultiSourceReader.trackData(true)`); the LSP keys files by URI. Path-to-URI canonicalisation matches the precedent in `Workspace`/`GraphitronTextDocumentService` already.
- Schema-wide errors (`coordinate: null`, `location: null` in `ValidationError`). These have no file to attach to; either drop them in the LSP, attach to the schema's root file, or publish as a separate `window/showMessage`. Decide in Spec.
- Deduplication against existing LSP diagnostics. `Diagnostics.compute` already flags catalog-misses and unknown classes; the validator's `UnclassifiedField` path covers an overlapping set. Pick the source of truth per check (likely: LSP keeps its instant-feedback arms, validator output augments with everything else) in the Spec.

Non-goals for this item: running the validator inside the LSP (the LSP has neither a freshly-built `GraphitronSchema` nor the resources to build one on every keystroke). The build pipeline writes; the LSP reads.

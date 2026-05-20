---
id: R196
title: "LSP fails to publish diagnostics after save-triggered build; stale errors persist until next edit"
status: Backlog
bucket: bug
theme: lsp
depends-on: []
created: 2026-05-20
last-updated: 2026-05-20
---

# LSP fails to publish diagnostics after save-triggered build; stale errors persist until next edit

User-visible symptom: a validation error or warning in an open schema file does not clear after the user fixes it and saves the file; the squiggle disappears only on the *next* edit. Root cause: `Workspace.toRecalculate` is populated by `setBuildOutput` / `demoteSnapshot` / `markAllForRecalculation` (called from `DevMojo.regenerate` and `rebuildCatalog` after the schema-file and classpath watchers fire), but only `publishDiagnosticsForRecalculate()` in `GraphitronTextDocumentService` drains the queue, and that method is invoked only from `didOpen` / `didChange` / `didClose`. So the save-triggered build path enqueues every open file for recalculation and then sits on the queue until the user types again, at which point `didChange` drains it and publishes diagnostics computed against the fresh `ValidationReport` — making the editor look like "the save didn't take, but the next keystroke did". This silently negates the whole R147 validator-into-LSP pipeline whenever a save fixes a diagnostic without itself being followed by another keystroke; the fast-feedback loop that the dev mojo + validator was designed to deliver is broken for the exact happy-path the feature was added for. Likely shape of the fix: a recalculation-publish listener on `Workspace` that the document service registers a `publishDiagnosticsForRecalculate` method-reference onto, fired by every queue-mutating method on `Workspace`; tests pin the wire shape by capturing `LanguageClient.publishDiagnostics` calls across a `setBuildOutput` (subsuming the LSP-side half of R149). Out of scope: the `GraphQLRewriteGeneratorTest` half of R149 (still its own item).

---
id: R34
title: "LSP quick fixes for the @node/@nodeId migration, driven by shim facts"
status: Backlog
bucket: feature
priority: 13
theme: lsp
depends-on: []
last-updated: 2026-07-14
---

# LSP quick fixes for the @node/@nodeId migration, driven by shim facts

Pivoted 2026-07-14. This item was previously the sis-side migration tracker (phased manual schema edits driven by build-log WARN/ERROR diffing; see git history of `sis-rewrite-migration.md`). The pivot replaces the manual grind with tooling: surface every site where the `@nodeId` synthesis shims fire as an LSP diagnostic carrying a ready-made fix, so sis-graph developers walk the migration diagnostic-by-diagnostic with the correct directive text offered in-editor. The shims themselves already derive everything the fix needs; today they throw that information away into a console WARN.

## The gap

The three synthesis-shim sites warn via SLF4J loggers, not via `BuildContext.addWarning(BuildWarning)`:

- Site A, output shim: `FieldBuilder` Path-2 (bare scalar `ID` output field on a `NodeType` parent). Has in hand: parent type name, field name, the field's `SourceLocation` (already a parameter), and the resolved `NodeType` (table, key columns, typeId).
- Site B, input scalar shim: `BuildContext.classifyInputFieldInternal`, NodeId-scalar arm (scalar `ID` input on a `@table` input whose backing table carries `__NODE_TYPE_ID` / `__NODE_KEY_COLUMNS`). Has in hand: coordinates, table name, `JooqCatalog.NodeIdMetadata` (typeId, key columns); `SourceLocation` one `BuildContext.locationOf(field)` call away.
- Site C, id-reference shim: `BuildContext.classifyInputFieldInternal`, FK-qualifier arm. Already precomputes the exact canonical replacement string (`@nodeId(typeName: "T")`, plus `@reference(path: [{key: "fk"}])` when the qualifier is ambiguous).

Because only `BuildWarning`s reach `ValidationReport.warnings()`, and `Diagnostics.validatorDiagnosticsForCurrent` (graphitron-lsp) replays exactly that report at Warning severity, these WARNs produce no squiggle and nothing for a code action to anchor to. The LSP side is otherwise ready: `GraphitronTextDocumentService.codeAction` is wired, and `LintQuickFixes.compute` already projects a build-side `BuildWarning.LintFinding` carrying a `LintFix` into a rendered quick-fix `TextEdit`.

## Shape of the fix

Follow the `lsp-reference-path-authoring` rung-4 principle: the fix is computed generator-side from classifier authority and merely rendered by the LSP; the LSP never re-derives node facts.

1. Convert the three shim WARNs into `BuildContext.addWarning(new BuildWarning.LintFinding(...))` with the field's `SourceLocation` and a `LintFix` whose edit inserts the canonical directive text:
   - Site A: insert ` @nodeId` (bare form; the parent is the field's own type, which is exactly where R473's grammar keeps the bare form legal).
   - Site B: insert ` @nodeId(typeName: "<T>")` with the type name resolved from the node index rather than the raw typeId, per R473's typeName-first direction.
   - Site C: insert the already-computed canonical string.
2. The existing `LintQuickFixes` path then renders these as per-diagnostic quick fixes with no LSP-side changes beyond tests.
3. A companion diagnostic for the type level: a `@table` type whose backing class carries `__NODE_TYPE_ID` / `__NODE_KEY_COLUMNS` but does not declare `implements Node @node` gets a hint-severity finding offering to add `implements Node @node`. This replaces the judgment step the old Phase 1 asked authors to make by hand ("decide whether the parent should be a Node"); the metadata is the same signal the retired type-level promotion shim keyed on, now surfaced as a suggestion instead of silent promotion.
4. Bulk application: with ~250 expected sites in sis, per-diagnostic clicking is not enough. Decide at Spec time between extending the finding-keyed path with file/workspace-scoped aggregation or hosting a detector-driven `SdlAction` for the bulk tier (the `CodeActions` dispatcher already has per-site / file-bulk / workspace-bulk activation for `SdlAction`s; mind the per-request re-parse noted in `lsp-structural-consolidation` if going that route).

## Sequencing

- The inserted grammar must be R473-conformant (`explicit-nodeid-grammar`): bare `@nodeId` only on own-type output fields, `typeName:` everywhere else. Land this action before or together with R473 phase 2's error flip so authors get fixes while the old forms still merely warn.
- R27 (`retire-synthesis-shims`) is the deletion vehicle for the shims. This item is what makes its migration recipe executable at scale: once the quick fixes have driven the shim findings to zero in consumer schemas, R27's WARN-to-error flip is safe. R27's gate note (2026-07-13) already records that no consumer relies on shim *behavior*; this item is about migrating the ~250 sis declarations comfortably, not about correctness of the transition.
- The WARN-to-`BuildWarning` conversion in step 1 is a prerequisite worth its own commit: it makes the shim findings visible in every consumer build report, LSP or not.

## Out of scope

- The sis-side execution itself (running the quick fixes over sis-graphql-spec); that happens in the sis repo once this ships.
- The old plan's Phase 2 (filter inputs missing `@table`) and Phase 3 (author-error `@node`/`@nodeId` cleanup): those already surface as ordinary validator errors with locations, so they are visible in-editor today; whether any deserve their own quick fixes is a separate question to file per finding kind if wanted.
- Deleting the shims (R27) and enforcing the grammar (R473).

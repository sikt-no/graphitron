---
id: R34
title: "LSP quick fixes for the @node/@nodeId migration, driven by shim facts"
status: Backlog
bucket: feature
priority: 13
theme: lsp
depends-on: []
last-updated: 2026-08-06
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

Follow the shipped `LintQuickFixes` pattern (R398; the same generator-computes/LSP-renders principle `lsp-reference-path-authoring` rung 3 takes from R233): the fix is computed generator-side from classifier authority and merely rendered by the LSP; the LSP never re-derives node facts.

1. Convert the three shim WARNs into `BuildContext.addWarning(new BuildWarning.LintFinding(...))` with the field's `SourceLocation` and a `LintFix` whose edit inserts the canonical directive text:
   - Site A: insert ` @nodeId` (bare form; the parent is the field's own type, which is exactly where R473's grammar keeps the bare form legal). Note the narrowing: this site no longer covers the field satisfying the `Node` interface, which is now a permanent carrier with no WARN and needs no fix offered. What remains here is the other bare `ID` fields on a node type.
   - Site B: insert ` @nodeId(typeName: "<T>")` with the type name resolved from the node index rather than the raw typeId, per R473's typeName-first direction.
   - Site C: insert the already-computed canonical string.
2. The existing `LintQuickFixes` path then renders these as per-diagnostic quick fixes with no LSP-side changes beyond tests.
3. A companion diagnostic for the type level, **narrower than it was, and with a gate it did not have**. Metadata-carrying tables now promote on `implements Node` alone, so a hint offering `implements Node @node` is moot for that case: `@node` would be redundant. What survives is a hint offering bare `implements Node` on a `@table` type whose backing class carries the metadata but which has not published the interface. This still replaces the judgment step the old Phase 1 asked authors to make by hand ("decide whether the parent should be a Node").

   The gate: **do not offer the hint where accepting it would collide.** Because the hint is now a one-word edit that promotes the type outright, and because step 4 plans workspace-scoped bulk application across ~250 sites, bulk-accepting it over a schema whose metadata-carrying tables share a `__NODE_TYPE_ID` mass-promotes exactly the population the retired promotion shim mass-failed on, through the tooling, in one click. The hint computation must therefore read the typeId-uniqueness reduction and not just the metadata probe: no hint where the resulting typeId would collide with an existing node or with a sibling the same sweep would promote. That stays inside this item's own "generator computes, LSP renders" discipline, since the reduction is generator-side.

   A second exclusion from the same reasoning: no hint where the type's `id` field is `@field`-pinned to a non-key column. Promoting such a type yields a node whose `Node.id` is a raw column value, which cannot round-trip through `Query.node(id:)`.
4. Bulk application: with ~250 expected sites in sis, per-diagnostic clicking is not enough. Read step 3's gate before designing this tier; the bulk path is what makes an ungated type-level hint dangerous rather than merely noisy. Decide at Spec time between extending the finding-keyed path with file/workspace-scoped aggregation or hosting a detector-driven `SdlAction` for the bulk tier (the `CodeActions` dispatcher already has per-site / file-bulk / workspace-bulk activation for `SdlAction`s; mind the per-request re-parse noted in `lsp-structural-consolidation` if going that route).

## Sequencing

- The inserted grammar must be R473-conformant (`explicit-nodeid-grammar`): bare `@nodeId` only on own-type output fields, `typeName:` everywhere else. Land this action before or together with R473 phase 2's error flip so authors get fixes while the old forms still merely warn.
- The shims are already deleted: R473 (`explicit-nodeid-grammar`) removed all three sites together with the grammar that replaces them, and R27 (`retire-synthesis-shims`) was discarded into it with an empty deletion set. So this item no longer unblocks anything; it is purely about migrating the ~250 sis declarations comfortably. The correctness question is settled (the user re-confirmed on 2026-08-09 that sis is the only touched subgraph), which means the quick fixes are an ergonomics deliverable rather than a gate, and the shapes they rewrite are the two the manual's migration recipe names.
- The WARN-to-`BuildWarning` conversion in step 1 is a prerequisite worth its own commit: it makes the shim findings visible in every consumer build report, LSP or not.

## Out of scope

- The sis-side execution itself (running the quick fixes over sis-graphql-spec); that happens in the sis repo once this ships.
- The old plan's Phase 2 (filter inputs missing `@table`) and Phase 3 (author-error `@node`/`@nodeId` cleanup): those already surface as ordinary validator errors with locations, so they are visible in-editor today; whether any deserve their own quick fixes is a separate question to file per finding kind if wanted.
- Deleting the shims and enforcing the grammar (both shipped in R473).

## Fact-base note (2026-08-06)

The three synthesis shims derive everything the quick-fix needs and throw it away, which is the fact-base thesis at the WARN grain. Once inferred claims carry join witnesses (R589), the quick-fix text is selectable from the claim row; re-anchor the deliverable on reading that relation rather than adding `BuildWarning` calls at the shim sites.
Context and the whole-board picture: `roadmap/audits/2026-08-06-fact-base-impact-sweep.md`.

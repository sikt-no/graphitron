---
id: R148
title: "Re-anchor LSP validator diagnostics past the description so they point at the field, not the doc block"
status: In Review
bucket: bug
priority: 5
theme: lsp
depends-on: []
created: 2026-05-12
last-updated: 2026-06-25
---

# Re-anchor LSP validator diagnostics past the description so they point at the field, not the doc block

graphql-java's `FieldDefinition.getSourceLocation()` (and the same call on type, input-field, and enum-value definitions) returns the start of the *description block* when one is present, not the line of the field name, because the description is the AST node's first token. The R147 LSP diagnostic surface inherits this: an error on a documented field underlines the opening `"""` of the doc block rather than the field, which is visually wrong in the editor squiggle.

## Why the originally-planned `BuildContext.locationOf` heuristic was abandoned

The first plan advanced the `SourceLocation` past the description with line arithmetic over `description.getContent()` (single-line `"..."` → +1 line; block `"""..."""` → `2 + newlineCount` lines). Verifying that against graphql-java 25 showed it cannot work:

- The block formula was off by one (own-line blocks advance by `3 + newlineCount`, not `2 + newlineCount`).
- More fundamentally, graphql-java's processed `content` cannot distinguish an inline block `"""text"""` (the name is on the *next* line, advance +1) from an own-line block (`"""` / `text` / `"""`, advance +3): both report `multiLine=true` with zero interior newlines. The raw source extent graphql-java needs to tell them apart is discarded at parse time.
- Inline `"""text"""` is the dominant documentation style in this codebase's directive schema (`directives.graphqls`), so a content-newline heuristic would mis-place the *common* case, not an edge case.

`BuildContext` also has no raw SDL source to scan, so a correct fix could not live there.

## Implemented fix (LSP, tree-sitter)

The LSP already holds the raw source and a tree-sitter parse, so it re-anchors precisely without any line arithmetic. In `Diagnostics.signatureRange` / `descriptionNameRange` (`graphitron-rewrite/graphitron-lsp/src/main/java/no/sikt/graphitron/lsp/diagnostics/Diagnostics.java`): resolve the validator `SourceLocation` to a tree-sitter point; if it lands inside a `description` node, re-anchor the diagnostic range to the enclosing definition's `name` (or `enum_value` for enum-value definitions); otherwise fall back to the prior column-to-end-of-line range straight from the location. This is exact for every documentation form (single-line, inline block, multi-line block) and handles interspersed comments/blank lines for free, because it reads the real parse tree rather than reconstructing offsets. The fix is location-source-agnostic: every validator error/warning routed through `validatorDiagnostic` is re-anchored, so no `BuildContext` or `GraphitronSchemaValidator` call site changes.

A `DESCRIPTION("description")` constant was added to `GraphqlNodeKind` for the node-kind test.

Coverage (`ValidatorDiagnosticsTest`): one test per documentation form (own-line block on a type, inline block on a field, single-line on a type) asserting the range covers the *name* token, plus a "no description" pass-through case asserting the column-to-end-of-line fallback is preserved.

## Out of scope

The build-time console / watch-mode formatter (`graphitron-core`) still anchors at the doc block: it has neither tree-sitter nor the raw source, so a correct fix there would require threading the SDL text into `BuildContext`. That is a separate, lower-priority surface (the complaint is the editor squiggle) and is left as a follow-up. Non-goals: changing the `SourceLocation` shape itself, or back-porting to legacy build-log paths outside the rewrite module.

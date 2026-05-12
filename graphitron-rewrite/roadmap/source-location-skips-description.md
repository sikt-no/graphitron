---
id: R148
title: "Advance SourceLocation past description so diagnostics point at the field, not the doc block"
status: Backlog
bucket: bug
priority: 5
theme: lsp
depends-on: []
created: 2026-05-12
last-updated: 2026-05-12
---

# Advance SourceLocation past description so diagnostics point at the field, not the doc block

graphql-java's `FieldDefinition.getSourceLocation()` (and the same call on type, input-field, and enum-value definitions) returns the start of the *description block* when one is present, not the line of the field name. Build-time validator logs and the R147 LSP diagnostic surface both inherit this: an error on a documented field highlights the opening `"""` of the doc block rather than the field, which is misleading in the console and visually wrong in the editor squiggle.

The fix lives in the `BuildContext.locationOf(...)` family (`graphitron-rewrite/graphitron/src/main/java/no/sikt/graphitron/rewrite/BuildContext.java:320-353`) plus the inline `def.getSourceLocation()` call in `validateConnectionType` (`graphitron-rewrite/graphitron/src/main/java/no/sikt/graphitron/rewrite/GraphitronSchemaValidator.java:224-225`). A helper that walks past the description gives every validator error site the right location without touching the call sites.

Mechanically: when `def.getDescription() != null`, take `description.getSourceLocation()` as the start and advance past the description. Two delimiter forms to handle: single-line `"..."` (advance by 1 line) and block string `"""..."""` (advance by `2 + newlineCountIn(description.getContent())` lines). Column resets to 1 on the new line. Test coverage: one unit test per delimiter form (single-line, single-line block, multi-line block) plus a "no description" pass-through case, asserting the returned `SourceLocation` line/column match what an editor would highlight.

Out of scope: comments (`#`) between the description and the field declaration. graphql-java's parser doesn't surface comment tokens on AST nodes, so any heuristic past the description is approximate. v1 of the fix assumes no comments in that gap; if it becomes a problem in practice, the precise fix is a tree-sitter pass over the source range, which is meaningful infrastructure for a one-line offset bug.

Non-goals: changing the `SourceLocation` shape itself, threading column-precise field-name offsets (still 1 because graphql-java doesn't expose the name token's column), or back-porting to legacy build-log paths outside the rewrite module.

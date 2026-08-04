---
id: R592
title: "Document the lint rules as a manual reference page"
status: Backlog
bucket: dx
priority: 3
theme: docs
depends-on: []
created: 2026-08-04
last-updated: 2026-08-04
---

# Document the lint rules as a manual reference page

`LintRule` is a closed set of fifteen rules with stable kebab-case ids that already cross a wire: the
MCP `diagnostics` tool projects each id so an agent can see which rule fired, and the consumer's
`<lint>` config names rules by the same ids. No page in the manual documents any of them.

`docs/manual/reference/diagnostics-glossary.adoc` is error-only by construction: it opens on the
three error prefixes (`[author-error]`, `[invalid-schema]`, `[deferred]`) and enumerates the
`unknown-name` attempt kinds. It has no warnings section at all, so a warning has no glossary-shaped
home to go into, and an author who sees a rule id in a build report or an LSP squiggle has nowhere to
look it up. The suppression config is the sharper gap: a consumer configuring `<lint>` has to name an
id that is documented nowhere, and the only way to discover the namespace is to read the enum.

The deliverable is a reference page covering all fifteen rules: id, what fires it, why it is
discouraged, and how to satisfy or silence it. The `Source` axis is the natural structure, since it
is already a typed partition and it tells the reader something real about where a finding comes from:
nine `ENGINE` rules (syntactic, re-derivable from the AST), four `CLASSIFIER` advisories (verdicts the
classifier computes and tags at its emit site), and two `CODEGEN` config advisories. Worth a coverage
meta-test in the same shape as the existing doc-coverage gates (`DirectiveDocCoverageTest`,
`DiagnosticsDocCoverageTest`), so a rule added to the enum without a documented id fails the build
rather than shipping undocumented.

Filed out of the `@node` inference work, which added `node-id-shadows-column` and found there was no
home for it. That item documented its warning where an author actually hits it (the `@node` reference
page's shadowed-column subsection) rather than growing an inventory page as a rider.

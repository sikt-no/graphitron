---
id: R160
title: "LSP inlay hints and hover for inferred directives and field/type classification"
status: Backlog
bucket: feature
theme: lsp
depends-on: []
created: 2026-05-13
last-updated: 2026-05-13
---

# LSP inlay hints and hover for inferred directives and field/type classification

Graphitron interprets SDL through two layers the user never sees: inference fills in directive arguments the author omitted (`@table(name:)` from the type name, `@field(name:)` from the field name, `@reference(path:)` from a unique single-hop FK), and classification assigns each type and field to a sealed variant in `GraphitronType` / `GraphitronField` that fully determines how the generator will emit code. Both layers run silently at build time. A schema author has to mentally re-run inference to know what column a field will hit; anyone debugging "why did graphitron emit X for this field?" has to read the classifier or the generator to find the variant assignment that drove the decision. The LSP already receives `BuildArtifacts(catalog, snapshot)` on every successful generator pass via `Workspace.setBuildOutput`; the same plumbing can surface both layers to the editor.

Concretely, extend `BuildArtifacts` to carry the post-classification `GraphitronSchema` (or, if a slim projection turns out to be worth the maintenance cost, a coordinate-keyed projection of it; the default is the whole model since the LSP ships in the same module group and has nothing to hide from itself). Add an inlay-hint provider that, gated by editor-side config, emits two categories at the relevant SDL coordinates:

1. **Inferred-directive hints** — at `@table` / `@field` / `@reference` sites where the author omitted the relevant argument (the LSP's tree-sitter cursor decides "was it written?"; the model decides "what did it resolve to?"). Shows `name: "customer"` (or `path: ["..."]`) as a ghost annotation. First scope is `@table`, `@field`, and `@reference`; other inferred-argument directives are explicit non-goals for the first pass.
2. **Classification hints** — at every field site and every type declaration, shows the simple sealed-variant name (`ChildField.JoinedColumn`, `RootField.QueryField`, `GraphitronType.NodeType`, etc.). Default off; opt-in via config because most authors don't need it, but it's a high-leverage debugging view for anyone investigating generator output.

Hover gets the rich form of classification: where the inlay hint shows just the variant name, the hover popup unpacks the variant's distinguishing fields (`table`, `column`, FK path, compaction mode, etc.) so a focused inspection answers "what specifically does graphitron know about this field?" without forcing the user to read the model source. Hover for inferred-directive sites is a secondary win but worth including in the same pass.

The dual audience is load-bearing for the design, not just a coincidence of shared plumbing. Inferred-directive hints help the schema author understand what graphitron sees in their schema; classification hints help anyone (author or graphitron developer) debug how that interpretation drives generator output. Designing them together pushes us toward a single "schema X-ray" concept with two facets rather than two ad-hoc features. Spec phase should decide: (a) one toggle or per-category toggles, (b) whether the variant-name label is the simple class name or a friendlier label (the variant taxonomy is still evolving; user-visible names freeze faster than internal ones), (c) what stale-snapshot behavior looks like for each category (current direction: keep showing them, since the validator behaves the same way, but the Spec should be explicit), and (d) whether the rich-hover-on-classification half should be split out as a follow-up if it pushes the increment too wide.

Forward references: builds on the data flow established by R139 (`lsp-schema-snapshot-side-channel`) and the hover infrastructure that `lsp-nodetype-hover-column-scoping` already exercises. Adjacent but distinct from `lsp-javaparser-javadoc-and-definitions` (Java-side definitions for `@service` classes) and `lsp-diagnostic-redundant-splitquery-on-record` (LSP-tier validator advisory).

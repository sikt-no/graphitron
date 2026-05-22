---
id: R223
title: "roadmap-tool: flag markdown-formatted tables in authored .adoc files"
status: Spec
bucket: cleanup
theme: docs
depends-on: []
created: 2026-05-22
last-updated: 2026-05-22
---

# roadmap-tool: flag markdown-formatted tables in authored .adoc files

Markdown table syntax (`| col | col |` rows separated by a `|---|---|` line) silently leaks into authored AsciiDoc. Asciidoctor renders it as paragraph text with literal pipes rather than a table, and the typo is invisible until the page ships; the truth table at `graphitron-rewrite/docs/argument-resolution.adoc` § "Truth table (per input-field, per call site)" was carrying this shape before the same commit that added the check converted it to AsciiDoc syntax. `roadmap-tool` gains a verify-phase check that walks every `.adoc` under `graphitron-rewrite/` and `docs/`, tracks structural context (inside a `|===` table block, a `----` listing block, a `....` literal block, a `////` comment block, or a `++++` passthrough block), and fails the build when it finds a markdown-separator row outside all of those. The render-side hole, `mdBodyToAdoc` not translating markdown tables embedded in `.md` roadmap plans, is a separate concern and is tracked at `translate-md-tables-in-mdbodytoadoc.md` (R227).

## Tests

`AdocMarkdownTableCheckTest` exercises each of the five structural block types listed above as a fixture: a markdown separator outside any block is flagged; the same characters inside a `|===` table block, a `----` listing block, a `....` literal block, a `////` comment block, and a `++++` passthrough block are not flagged. The walker's directory filter is pinned by separate fixtures: a markdown table under a `target/` subdirectory is skipped (rendered output is out of scope), and a `.md` file at any path is skipped (markdown table syntax is native there).

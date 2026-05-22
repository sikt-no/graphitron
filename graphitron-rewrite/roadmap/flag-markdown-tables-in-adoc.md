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

Markdown table syntax (`| col | col |` rows separated by a `|---|---|` line) silently leaks into authored AsciiDoc. Asciidoctor renders it as paragraph text with literal pipes rather than a table, and the typo is invisible until the page ships; today's repo already carries one such case at `graphitron-rewrite/docs/argument-resolution.adoc:266` (the truth table). `roadmap-tool` gains a verify-phase check that walks every `.adoc` under `graphitron-rewrite/` and `docs/`, tracks structural context (inside a `|===` table block, a `----` listing block, a `....` literal block, a `////` comment block, or a `++++` passthrough block), and fails the build when it finds a markdown-separator row outside all of those. The render-side hole, `mdBodyToAdoc` not translating markdown tables embedded in `.md` roadmap plans, is a separate concern and gets its own item.

---
id: R227
title: "mdBodyToAdoc: translate markdown tables to AsciiDoc when rendering roadmap"
status: Backlog
bucket: cleanup
theme: docs
depends-on: []
created: 2026-05-22
last-updated: 2026-05-22
---

# mdBodyToAdoc: translate markdown tables to AsciiDoc when rendering roadmap

`Main.mdBodyToAdoc` (`graphitron-rewrite/roadmap-tool/src/main/java/no/sikt/graphitron/roadmap/Main.java`) is a best-effort markdown→AsciiDoc converter used by `render-adoc` to stage roadmap plan bodies into the documentation site. It currently passes markdown tables (`| col | col |` header rows separated by a `|---|---|` line) through verbatim, so the rendered `.adoc` carries raw pipe rows that Asciidoctor displays as paragraph text with literal pipes rather than a table. R223 added a verify-phase check that flags this pattern in *authored* `.adoc`, but explicitly defers the render-side hole; markdown tables embedded in `.md` roadmap plans still leak into the rendered roadmap site. This item is the follow-up: extend `mdBodyToAdoc` to detect a markdown-table block (header row + separator + body rows, terminated by a blank line) and emit an AsciiDoc `|===` block with a `[cols=...]` attribute synthesized from the separator's column count and any GFM alignment markers.

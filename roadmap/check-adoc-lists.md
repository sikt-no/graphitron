---
id: R910
title: "The build fails on an adoc list item that renders as paragraph text"
status: Backlog
bucket: dx
priority: 5
theme: tooling
depends-on: []
created: 2026-09-01
last-updated: 2026-09-01
---

# The build fails on an adoc list item that renders as paragraph text

## Goal

A `**` list line whose predecessor is neither a list item, a blank line, nor a block delimiter silently renders as continuation prose with the markers paired off as bold, turning a rule list into one long paragraph; a build step in roadmap-tool (a sibling of `check-adoc-tables`, which already guards the analogous markdown-table failure) fails loudly on that pattern so it cannot land unnoticed. The gap is real: `roadmap/workflow.adoc` is rendered by no build step, so the R906 review caught exactly this defect by hand-rendering the file.

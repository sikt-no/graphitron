---
id: R1175
title: "A file rewritten back to an earlier shape stores each directive application once"
status: Backlog
bucket: bug
priority: 5
theme: diagnostics
depends-on: []
created: 2026-11-12
last-updated: 2026-11-12
---

# A file rewritten back to an earlier shape stores each directive application once

## Goal

A directive written once is stored as one `graphql_directive_application` row, however a file got to its current text. Today `defects/defect-sweep`'s final reading stores the single `@table` written on `Language` (line 10, column 15) as two rows, ordinals 0 and 1, both marked with the final reading's instant, while the transcribed registry holds one application. It appears when a rewritten file returns a declaration to a position an earlier reading used, so an application from the earlier reading is re-marked instead of swept. A reader counting applications at a site (the editor's hover, the emitted registry, any rule reading ordinals) sees a directive applied twice. When this lands the duplicate is gone, and the population-wide check that compares each graph's application counts with its registry (today the one case left in `GraphQLAnchorTest`, for this reason) joins the other graph-wide invariants in `CapturedGraphInvariantsTest`.

## Evidence

* Found on 2026-11-12, when the count check was first run over every fact document rather than one inline schema.

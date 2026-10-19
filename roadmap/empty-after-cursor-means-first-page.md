---
id: R1063
title: "An empty after cursor reads as the first page, as v9 read it"
status: Backlog
bucket: bug
priority: 3
theme: codegen-correctness
depends-on: []
created: 2026-10-19
last-updated: 2026-10-19
---

# An empty after cursor reads as the first page, as v9 read it

## Goal

A connection field given `after: ""` returns the first page, as it did under Graphitron 9,
instead of failing with "cursor is not valid". Consumers whose operations bind `after` to a
non-null `String!` variable send the empty string for the first page, and keep working across the
upgrade.

## Evidence

Surfaced by the sis v9 against v10 differential test (2026-10-19).
`studenter(filter: {eierOrganisasjonskode: "1234"}, first: 1, after: "")` returns the error
`cursor is not valid (was: "")`; on a root field typed `Connection!` the null propagates and the
whole `data` is null. In sis' production traffic 48 operations pass a `String!` variable as
`after`, so their first page most likely sends `""`.

The error itself comes from R479, which turned a malformed cursor into a clean client error instead
of a redacted server fault. That stays right for a genuinely malformed cursor; only the empty string
changes meaning.

## Constraint for the plan

`ConnectionResult.hasPreviousPage()` returns `afterCursor != null` for a forward page. The
normalisation of `""` to absent has to happen before the cursor reaches `ConnectionResult`, or a
first page fetched with `after: ""` reports `hasPreviousPage: true`. The same question applies to
`before: ""`; decide it in the same place. The plan should also say whether the treatment is
unconditional or a v9-compatibility setting.

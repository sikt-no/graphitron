---
id: R520
title: "@table-on-input removal housekeeping: changelog, LSP directive list, docs (Phase 4)"
status: Backlog
bucket: architecture
priority: 6
theme: classification-model
depends-on: [remove-table-on-input-directive]
created: 2026-07-24
last-updated: 2026-07-24
---

# `@table`-on-input removal housekeeping (Phase 4)

Carved out of R97 (`consumer-derived-input-tables`) as the housekeeping tail of
the `@table`-on-input removal. Follows R519 (the directive removal); nothing here
is load-bearing, so it can ship any time after R519 lands.

## Scope

- Add a migration note in `roadmap/changelog.md` naming the SHA where
  `@table`-on-input ships zero scope (the R519 landing commit).
- LSP completion + diagnostics: drop `@table` from the `INPUT_OBJECT`-applicable
  directive list (once R519 narrows the scope, `@table` on an input is a parse
  error and should not be offered as a completion there).
- `docs/README.adoc` and any other documentation references: remove `@table` as a
  directive consumers reach for on inputs.

## Acceptance

`@table` no longer surfaces as an input-applicable directive in LSP completion or
diagnostics; docs carry no residual "reach for `@table` on inputs" guidance; the
changelog names the zero-scope SHA. Build green.

---
id: R520
title: "@table-on-input removal housekeeping: changelog, LSP directive list, docs (Phase 4)"
status: Backlog
bucket: architecture
priority: 6
theme: classification-model
depends-on: []
created: 2026-07-24
last-updated: 2026-07-24
---

# `@table`-on-input removal housekeeping (Phase 4)

Carved out of R97 (`consumer-derived-input-tables`) as the housekeeping tail of
the `@table`-on-input removal. Follows R519 (the directive removal); nothing here
is load-bearing, so it can ship any time after R519 lands.

## Scope

Re-scoped after the deprecation window reopened. The LSP sub-goal below is void:
`@table` stays applicable on `INPUT_OBJECT` for as long as the deprecation window
is open, because a schema still carrying it has to parse and build, and the
application has to surface as a warning squiggle rather than an unknown-location
parse error. Dropping it from the completion list belongs to the eventual
re-removal item, not here.

What remains is documentation-only:

- Add a migration note in `roadmap/changelog.md` naming the SHA where
  `@table`-on-input stopped contributing scope.
- `docs/README.adoc` and any other documentation references: remove `@table` as a
  directive consumers reach for on inputs. The reference pages already state the
  deprecation; this is about residual "reach for it" framing elsewhere.

## Acceptance

Docs carry no residual "reach for `@table` on inputs" guidance; the changelog
names the zero-scope SHA. `@table` still completes on `INPUT_OBJECT` and its
application still surfaces as a warning, not an error. Build green.

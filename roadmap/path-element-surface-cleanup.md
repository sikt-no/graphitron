---
id: R235
title: "Tidy @reference path-element surface: separate join-shape from WHERE-filter"
status: Backlog
bucket: cleanup
priority: 6
theme: classification-model
depends-on: []
created: 2026-05-23
last-updated: 2026-05-23
---

# Tidy @reference path-element surface: separate join-shape from WHERE-filter

The legacy `ReferenceElement { table, key, condition }` directive surface combines three
roles in one input object: `key:` and `table:` and `condition:` (without companions) name
the join shape; `condition:` combined with `key:` or `table:` names a WHERE-filter that
folds onto the FkJoin's `whereFilter`. The full combinations table at R232's spec lines
44-52 documents seven valid shapes. The conflation invites cargo-culting (`condition:`
sometimes means "ConditionJoin", sometimes "WHERE filter on FkJoin") and the seven-shape
free combination invites authoring drift.

R232 deliberately preserved the legacy semantics so the lift could ship without forcing
a migration; the regression-guard fixtures
`TABLE_WITH_CONDITION_PRESERVES_WHERE_FILTER` /
`KEY_WITH_CONDITION_PRESERVES_WHERE_FILTER` in `GraphitronSchemaBuilderTest` pin the
existing meanings so a future cleanup pass can't silently re-classify.

Likely design: `@oneOf` for the join-shape arm (mutually-exclusive `key:` | `table:` |
`condition:`) + a separate `whereFilter:` field for the conditional, or an equivalent
restructuring that makes the SDL author's intent unambiguous at the directive site.
Migration would either be cleanly automated (the resolver knows which `condition:` slot
each schema uses) or surfaced via an actionable `AUTHOR_ERROR` per element.

Surfaced during R232's design fork; filed as a separate item because R232's scope was
target-table resolution, not surface cleanup.

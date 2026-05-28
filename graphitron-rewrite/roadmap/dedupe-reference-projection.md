---
id: R255
title: "Dedupe duplicate column projection in @reference DBQueries (RC-6 regression)"
status: Backlog
bucket: bug
priority: 5
theme: structural-refactor
depends-on: []
created: 2026-05-28
last-updated: 2026-05-28
---

# Dedupe duplicate column projection in @reference DBQueries (RC-6 regression)

Regression introduced in 10-rc6: generated `*DBQueries` for types using `@reference` project the same `(alias, column)` pair twice — once for the bridge's own field and once as the FK column feeding the `@reference` subquery. Generated fetchers then resolve those projections by name (`DSL.field("navn")`, unaliased `Tables.X.COL`), so `FieldsImpl.indexOf` matches both candidates and jOOQ logs `Ambiguous match for "<alias>.<column>". Both <alias>.<column> and <alias>.<column> match.` at INFO for every fetched row. Data is correct because both columns hold the same value, but the log spam is severe on any list query, and the duplicates are a latent correctness risk if the projections ever diverge (different casts, NULL-handling, computed expressions). Two fixes are on the table: (a) dedupe at projection time so each `(alias, column)` is emitted once, or (b) resolve fetcher field references by identity/position rather than name. Investigation should locate the emit sites in the `*DBQueries` builder and the fetcher field-lookup path, decide between (a) and (b) (or both), and pin the regression to a specific change between rc5 and rc6.

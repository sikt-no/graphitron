---
id: R450
title: "Split-path hop-0 condition filter binds the same alias as source and target"
status: Backlog
bucket: bug
theme: structural-refactor
depends-on: []
created: 2026-07-08
last-updated: 2026-07-08
---

# Split-path hop-0 condition filter binds the same alias as source and target

`SplitRowsMethodEmitter.buildWhereCondition` emits a hop's `condition:` filter as
`method(srcAlias, tgtAlias)` with `srcAlias = i == 0 ? firstAlias : aliases.get(i - 1)`, but
`firstAlias` *is* `aliases.get(0)`, so a filter on hop 0 of a split path binds the same alias
to both parameters: `method(firstAlias, firstAlias)`. The inline emitters pass the parent's
alias as the source at step 0. Latent since the file's creation (predates R435; found during
the R435 second-pass review) and unreachable today: routine paths pin `filters` empty via the
`SplitTableField` compact constructor, and no existing fixture puts a `condition:` filter on
the first hop of a `@splitQuery` path. A schema that does would emit a wrong correlation or a
javac error in the generated source. Fix is one line (source should be the `parentInput` /
parent-side alias at step 0, matching the inline emitters) plus a pipeline fixture and an
execution proof for a hop-0 condition filter under `@splitQuery`.

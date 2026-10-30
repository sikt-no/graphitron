---
id: R1134
title: "An argument @condition(override: true) is rejected as an unbound column before the override is considered"
status: Backlog
bucket: bug
priority: 4
theme: classification-model
depends-on: []
created: 2026-10-30
last-updated: 2026-10-30
---

# An argument @condition(override: true) is rejected as an unbound column before the override is considered

## Goal

An argument's `@condition(override: true)` replaces the argument's implicit column predicate, as
`condition.adoc` says, so a scalar argument with no matching column is accepted when it carries
one. Today it is rejected first.

## The defect

A scalar argument whose name matches no column on the field's table, carrying
`@condition(override: true)`, is rejected as `UnboundArg` ("column 'x' could not be resolved in
table ...", from `FieldBuilder`, near line 2580 at the time of writing) before the override is
considered. The override exists precisely so the implicit predicate, and with it the column, is not
needed. kreg worked around it with `@reference(path: [{condition: ...}]) @field(name: "<column on
target>")` on the argument.

## Direction

Consider the override before resolving the implicit column, so an overridden argument never asks
for one. A pipeline-tier case: an unbound scalar argument with `@condition(override: true)`
classifies, and the same argument without the override is still rejected.

Related but distinct: R1092 (`input-field-condition-drops-implicit-predicate`) is about an input field's
`@condition` without override.

Provenance: reported by the kreg Graphitron 10 port session (fs-plattform's kreg module on 10.0.0-RC46, branch `worktree-kreg-g10` at `7858137af4`, not pushed), 2026-10-30; the reporter found it present on graphitron `origin/main` the same day.

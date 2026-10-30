---
id: R1141
title: "A path-step condition rejection carries only prose, so the typed reflection error never reaches the LSP"
status: Backlog
bucket: cleanup
priority: 8
theme: diagnostics
depends-on: [referencefor-condition-failure-names-the-reason]
created: 2026-10-30
last-updated: 2026-10-30
---

# A path-step condition rejection carries only prose, so the typed reflection error never reaches the LSP

## Goal

A path-step condition that fails to resolve carries its typed reflection error through the path
parse, so the LSP can show the specific diagnostic code (for example the ambiguous-method one) rather
than a prose-only structural rejection.

`ParsedPath.errorMessage` is a plain string (`String.join("; ", errors)`), so the typed
`ReflectionError.AmbiguousMethod` arm and its LSP code do not survive to the `@referenceFor` or
`@reference` rejection. The condition-failure message item restores the prose; this item carries the
typed arm.

## Direction

Replace the string with a typed rejection list on `ParsedPath` and update every consumer. Widens the
change to all `ParsedPath` consumers, hence its own low-priority item. Depends on the condition
failure message item landing first.

Provenance: reported by the kreg Graphitron 10 port session (fs-plattform's kreg module on 10.0.0-RC46, branch `worktree-kreg-g10` at `7858137af4`, not pushed), 2026-10-30, as review comments on the specs for R1134 and R1136.

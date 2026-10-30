---
id: R1137
title: "A rejected @error type cascades into misleading diagnostics on the types around it"
status: Backlog
bucket: bug
priority: 5
theme: diagnostics
depends-on: []
created: 2026-10-30
last-updated: 2026-10-30
---

# A rejected @error type cascades into misleading diagnostics on the types around it

## Goal

When a type is rejected, the build reports that rejection and leaves out the diagnostics whose only
cause is it, or words them as consequences ("because KregUgyldigInput was rejected above"). Today
the cascade reads as independent faults, one of which points the author at the wrong type with the
wrong fix.

## The defect

The root cause in kreg was a good message: `@error handler {handler: GENERIC, className:
"jakarta.ws.rs.ForbiddenException"}: class ... is in jakarta.ws.rs:jakarta.ws.rs-api, which this
module does not declare`. The same run also reported:

* `Type 'Error': implementing type 'KregUgyldigInput' is not table-bound (missing @table
  directive)`, which points at the stable `Error` interface and suggests putting `@table` on an
  error type;
* `Field 'KregUgyldigInput.path': parent type is unclassified`, and the same for `.message`.

All three disappear once the dependency is declared.

## Direction

Suppress a diagnostic whose only cause is an already-rejected type, or attribute it to that
rejection. The Spec decides which, and whether the rule is general (any rejected type) or starts
with the `@error` case. A pipeline-tier case: an `@error` type rejected for an undeclared handler
dependency yields that one rejection, not the interface and field follow-ons.

Provenance: reported by the kreg Graphitron 10 port session (fs-plattform's kreg module on 10.0.0-RC46, branch `worktree-kreg-g10` at `7858137af4`, not pushed), 2026-10-30; the reporter found it present on graphitron `origin/main` the same day.

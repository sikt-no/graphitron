---
id: R379
title: "Validate @reference terminal hop resolves to the field return type table"
status: Backlog
bucket: bug
priority: 5
theme: structural-refactor
depends-on: []
created: 2026-06-25
last-updated: 2026-06-25
---

# Validate @reference terminal hop resolves to the field return type table

`InlineTableFieldEmitter.buildArm` (`InlineTableFieldEmitter.java:63`) carries a silent
invariant: the `@reference` path's terminal hop must land on the field return type's
`@table`. It generates `terminalAlias` from the *last hop's* `targetTable`
(`:84`, alias typed off the jOOQ table class) but feeds it to
`<ReturnType>.$fields(selectionSet, terminalAlias, env)` (`:133`), whose signature was
generated for the return type's table. When the last hop differs from the return type's
`@table`, javac rejects the *generated* source with an incompatible-types error, the
worst place for the failure to surface.

Observed in the wild: `NusGrupperingFagfelt @table("NUSFAGFELT")` reached via
`@reference(path:[{table:"NUSUTDANNINGSGRUPPE"},{table:"NUSFAGGRUPPE"}])` aliases the
terminal table as `Nusfaggruppe` and passes it to `NusGrupperingFagfelt.$fields(Nusfagfelt, ...)`
=> "Nusfaggruppe cannot be converted to Nusfagfelt". The sibling `faggruppe` fields work
only because their path's last table coincides with their target type's table.

Fix: assert at classify/build time that the terminal hop genuinely resolves to the return
type's `@table`, and fail with a pointed Graphitron diagnostic instead of letting javac
choke on generated code. The check is **not** a single "last table == return type table"
comparison; it differs per terminal-hop kind:

* *terminal `{table:X}`* — `X` must equal the return type's `@table`.
* *terminal `{key:K}`* — `K` must be an FK from the penultimate resolved table whose
  *referenced* table is the return type's `@table` (i.e. the key connects penultimate ->
  return type, not merely "ends on a key").
* *terminal `{condition:C}`* — the target is the return type *by construction* (terminal
  condition-joins resolve their target from the field's `@table` in
  `BuildContext.parsePathElement`), so target-equality is tautological and proves nothing.
  The meaningful check moves to the *source* side: the path's penultimate resolved table
  must be the input record the condition method expects to receive. Validate that the path
  delivered the condition its input, not that the path "ends on" the target.

Open design question for Spec: validate-and-reject only (recommended; strictly better than
status quo, no behavior change for valid schemas) versus auto-appending an implicit
terminal hop when a unique FK bridges the last path table to the return type table. The
latter is the catalog-guessing the `@reference` docs deliberately refuse elsewhere
("the generator does not guess"); treat it as a separate, debatable convenience layered on
top of the validation, not a substitute for it.

Surfaced from a downstream subgraph build (utdanningsregisteret) and confirmed against the
emitter. Sibling to R236 (candidate-hint terminal table) and R282 (FK-key hint scope),
which also harden `@reference` resolution diagnostics.

---
id: R457
title: "@mutation(table:) parameter for DELETE write-target; retire @table-on-input for DELETE"
status: Backlog
bucket: architecture
priority: 6
theme: model-cleanup
depends-on: []
created: 2026-07-09
last-updated: 2026-07-09
---

# `@mutation(table:)` parameter for DELETE write-target; retire `@table`-on-input for DELETE

A `@mutation(typeName: DELETE)` field still gets its write target exclusively
from the `@table` directive on its input type, yet R332
(`table-on-input-deprecation-signal`) now fires a deprecation warning on that
same directive with no replacement path for DELETE. The result is a warning the
author cannot act on: `FilmDeleteInput @table(name: "film")` is told to drop the
directive, but dropping it leaves DELETE with no table. This item supplies the
field-relative alternative so the warning becomes truthful, without yet removing
`@table` from input types generally (that is R97's job).

## Why DELETE is stuck today

R246/R266 moved DELETE's *columns* field-relative: `DeleteRowsWalker` derives the
WHERE partition (and the PK-or-UK single-row guard) from the already-classified
input fields. But the *table anchor* it walks against is still the input's
`@table`. `FieldBuilder.resolveDmlWalkerInputArg` (`FieldBuilder.java:4195`)
resolves the single `@table` input argument to a `GraphitronType.TableInputType`
and hands `foundTit.table()` to the walker (`classifyDeleteTableField` at
`:4336`, `classifyDeletePayloadField` at `:4403`). For an `ID` return the encoder
is then found *table -> node* via `ctx.nodes.forTable(tableSqlName)` (`:4359`).
So the input's `@table` is the sole write-target signal for DELETE.

R332's carve-out (`encodedWriteTargetInputTypes`) only spares encoded-ID/scalar-return
INSERT/UPSERT; DELETE is not in it, so `FilmDeleteInput` warns (the R332 changelog
entry names it explicitly).

## What replaces it (the R97 ladder, scoped to the DELETE write-target)

The table is a property of the *consuming field*, not the input (R97's thesis).
Resolve it in this order:

1. **Convention (default): derive from the return.** When the DELETE field returns
   its `@table` type (`deleteFilm(...): Film`) or a payload whose data-field
   element is `@table`-typed, the write target is already derivable from the
   return with no new syntax. This is the common shape and needs neither the new
   parameter nor `@table` on the input.
2. **Explicit override: `@mutation(typeName: DELETE, table: "film")`.** For the
   genuine gap, a bare `ID`/scalar return (`deleteFilm(...): ID`,
   `deleteFilm(...): Boolean`, a delete count) carries no return-type table. The
   new `table:` argument on `@mutation` names the write target on the *consuming
   field*, the field-level analogue of `argMapping` on `@service`. It covers every
   return shape uniformly (including non-ID scalars), is unambiguous, and needs no
   reflection.

Rejected as the primary mechanism (may return as a convenience rung later):
deriving the table from a `@nodeId(typeName:)` on the returned ID. It cannot stand
alone (does nothing for non-ID scalar returns; a bare `ID` still carries nothing),
its own type-deduction rule is circular here (`@nodeId` deduces the type *from* the
table), and it couples write-target resolution to the encoder/node subsystem. See
the discussion folded into R97's Phase 2b framing.

This item deliberately does **not** remove `@table` from input types. It relocates
the DELETE signal to the field so the directive becomes removable *for DELETE*;
the general removal stays R97.

## Warnings and errors must name the preferred way

The whole point is to make the R332 warning actionable, so the message wording is
in scope, not an afterthought:

- **R332 DELETE carve-out (first commit, immediate relief).** Until the `table:`
  parameter lands, suppress the R332 `@table`-on-input deprecation warning for
  input types consumed only by DELETE mutations, mirroring the encoded INSERT/UPSERT
  carve-out (`encodedWriteTargetInputTypes`). Warning-without-alternative is the
  defect this item opens against.
- **After the parameter lands**, the R332 warning on a DELETE input's `@table`
  must name the replacement explicitly: "set the table on the consuming
  `@mutation(table:)` field, not with `@table` on the input type." The prose tier
  (`deprecations.adoc`, `table.adoc` WARNING, `code-generation-triggers.adoc`)
  gains the DELETE-specific replacement instruction.
- **Redundancy is a warning, not silence.** When both the return type derives a
  table *and* `@mutation(table:)` is set, and they agree, warn that the parameter
  is redundant (convention already resolves it). When they *disagree*, reject at
  build time naming both tables (the parameter is an override precisely so the
  author can see the conflict, not paper over it).
- **The old "no `@table` input argument found" structural rejection** in
  `resolveDmlWalkerInputArg` (`:4227`) must be reworded: absence of `@table` is no
  longer an error once the table resolves from the return or `@mutation(table:)`.
  The rejection now fires only when *none* of the three sources resolves, and its
  message enumerates all three ("return a `@table` type, add `@mutation(table:)`,
  or annotate the input").

## Scope

- Add the `table: String` argument to the `@mutation` directive in
  `directives.graphqls` (document it as DELETE-relevant; other verbs derive their
  target from the return / input as today).
- Thread the resolved table through `resolveDmlWalkerInputArg` /
  `classifyDeleteTableField` / `classifyDeletePayloadField` so `DeleteRowsWalker`
  receives a field-derived `TableRef` instead of `foundTit.table()`.
- Wording updates to the R332 warning, the structural rejections, and the
  redundancy/conflict diagnostics above.
- R332 DELETE carve-out as the first commit.

## Out of scope

- Removing `@table` from input types (R97).
- The `@mutation(table:)` parameter for INSERT/UPSERT/UPDATE (INSERT/UPSERT is
  R97 Phase 2b's encoded-write-target migration; UPDATE derives from the return via
  R246/R258). This item is DELETE-only; generalising the parameter to other verbs,
  if wanted, is a follow-up.
- Deriving the table from a `@nodeId`-typed return (rejected above; possible later
  convenience rung).

## Relationship to R97

A DELETE-scoped slice of the same "the write target is the consuming field's
property" axis R97 Phase 2b describes for encoded INSERT/UPSERT. R97 remains the
home for the general `@table`-on-input removal; this item unblocks the DELETE case
so R332's warning stops being a dead end ahead of that.

## Tests

- Pipeline-tier: DELETE returning `ID` with `@mutation(table:)` and no `@table` on
  the input classifies to the same `MutationDeleteTableField` / `DeleteRows` carrier
  as the `@table`-on-input form (byte-identical emit).
- Pipeline-tier: DELETE returning its `@table` type with no `@table` on input and no
  `@mutation(table:)` resolves the table from the return.
- Pipeline-tier: `@mutation(table:)` disagreeing with a return-derived table rejects
  at build time naming both; agreeing emits the redundancy warning.
- Pipeline-tier: R332 warning suppressed for DELETE-only inputs before the parameter,
  fires with the replacement wording after.
- Execution-tier: a sakila DELETE fixture with `@table` removed from the input and
  the table set via `@mutation(table:)` round-trips correctly against PostgreSQL.

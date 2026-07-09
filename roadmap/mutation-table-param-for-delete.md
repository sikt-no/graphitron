---
id: R457
title: "@mutation(table:) parameter for DELETE write-target; retire @table-on-input for DELETE"
status: Spec
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
`@table`. `FieldBuilder.resolveDmlWalkerInputArg` (`FieldBuilder.java:4596`)
resolves the single `@table` input argument to a `GraphitronType.TableInputType`
and hands `foundTit.table()` + `foundTit.inputFields()` to the walker
(`classifyDeleteTableField` at `:4737`, `classifyDeletePayloadField` at `:4804`).
For an `ID` return the encoder is then found *table -> node* via
`ctx.nodes.forTable(tableSqlName)` (`:4756-4770`). So the input's `@table` is the
sole write-target signal for DELETE.

The existing consumer-derived branch of `TypeBuilder.buildInputType`
(`TypeBuilder.java:1554`) does not help: its aggregate
`findReturnTablesForInput` (`:1766`) deliberately skips `@mutation` fields, so a
mutation input without `@table` classifies as `PojoInputType` and
`resolveDmlWalkerInputArg`'s look-ahead rejects it. Widening that aggregate is
not the fix; R97 Phase 2 retires it as "right idea, wrong altitude" in favour of
per-call-site resolution, which is exactly what this item does for DELETE.

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
3. **Migration bridge (bottom rung): the input's `@table`.** Consulted only when
   neither live source resolves. It is deliberately *not* a participant in the
   agreement/disagreement diagnostics below: R97's end state stops consulting the
   directive's value ("becomes a no-op"), and promoting a directive the R332
   warning tells authors to delete into a build-breaking conflict participant
   would invert that. An input `@table` that disagrees with a live source is
   simply outranked, never cross-checked (today such a disagreement is not even
   observable, since the input's `@table` is the only source; introducing a new
   rejection for it would break schemas the deprecation path wants migrating
   quietly).

Rejected as the primary mechanism (may return as a convenience rung later):
deriving the table from a `@nodeId(typeName:)` on the returned ID. It cannot stand
alone (does nothing for non-ID scalar returns; a bare `ID` still carries nothing),
its own type-deduction rule is circular here (`@nodeId` deduces the type *from* the
table), and it couples write-target resolution to the encoder/node subsystem. See
the discussion folded into R97's Phase 2b framing.

This item deliberately does **not** remove `@table` from input types. It relocates
the DELETE signal to the field so the directive becomes removable *for DELETE*;
the general removal stays R97.

## Design (mechanism)

Reviewed against the principles (principles-architect consult, 2026-07-09); the
field-relative altitude is endorsed, with the following shape constraints.

### Resolution lives in the DELETE classifiers, not the shared resolver

`resolveDmlWalkerInputArg` stays verb-agnostic. Its own javadoc establishes that
verb-divergent behaviour (multiRow) is the caller's concern; a DELETE-only
fallback inside it would fork a UPDATE/DELETE-shared method on verb. Instead:

- `resolveDmlWalkerInputArg` changes only in that "the single input arg is not a
  `TableInputType`" becomes a *normal outcome* (a third
  `DmlWalkerInputArgResolution` arm carrying the raw arg surface) rather than an
  immediate structural reject. UPDATE callers translate that arm to today's
  rejection verbatim; behaviour for UPDATE is byte-identical.
- `classifyDeleteTableField` / `classifyDeletePayloadField` own the fallback:
  resolve the write target by the precedence above (`@mutation(table:)`, then
  return-derived, then the input's `@table` via the `Resolved` arm), emit the
  redundancy/conflict diagnostics, and produce the reworded none-of-three
  rejection. The three sources are known only there, so the diagnostics live
  there.

### The carrier is narrow: `(TableRef, List<InputField>)`

The walker consumes exactly `foundTit.table()` and `foundTit.inputFields()`.
When the input carries no `@table` (registry verdict `PojoInputType`), the
DELETE classifier resolves the input's fields against the field-derived table by
calling a helper factored out of `TypeBuilder.buildTableInputType`'s
field-resolution loop (`TypeBuilder.java:1585`), shared by both call sites. It
returns the narrow fact, the `TableRef` plus resolved `List<InputField>` (or
the accumulated failures), **not** a synthesized `TableInputType`: that type
additionally carries `name`/`location`/`inputType`/`InputRecordShape`, which are
type-registry concerns, and landing it nowhere would make one model type mean
two things. The registry verdict for the input stays `PojoInputType`; the LSP
surfacing residual is acknowledged and owned by R97/R337.

### Validator-mirror obligation (the R330 hazard)

`GraphitronSchemaValidator.validateTableInputType`
(`GraphitronSchemaValidator.java:528`) walks *registry* `TableInputType`s to
surface the R215-deferred input-field rejections (`UnboundField` under
`@condition(override:false)`, reference-field checks). A field-derived DELETE
input never lands in that registry walk, so the same broken input would reject
on the `@table`-on-input path and slip through on the field-derived path: the
exact validator-bypass shape `GraphQLQueryTest.java:260` memorializes from R330.
The DELETE classifier must therefore enforce the identical rule at the call
site: a non-override `UnboundField` (and the other `validateTableInputType`
input-field rejections) in the field-derived resolution rejects the field, with
a pipeline-tier test pinning parity between the two paths (same schema defect,
same rejection, both routes).

### `table:` on non-DELETE verbs rejects loudly

`table:` is wired only for DELETE in this item. On INSERT/UPSERT/UPDATE it is an
unimplemented classification, and silently ignoring an author-written directive
argument is the green-build-wrong-intent failure mode the axioms forbid. The
classifier rejects with a typed, sealed `Rejection` (stable LSP code, not a bare
string), and the classifier and `ValidateMojo` read the same "verbs accepting
`table:`" set (a one-element set today) so validate time mirrors classify time
and a future generalisation is a single edit point.

## Warnings and errors must name the preferred way

The whole point is to make the R332 warning actionable, so the message wording is
in scope, not an afterthought:

- **R332 DELETE carve-out (first commit, immediate relief).** Until the `table:`
  parameter lands, suppress the R332 `@table`-on-input deprecation warning for
  input types consumed by DELETE mutations, mirroring the encoded INSERT/UPSERT
  carve-out (`encodedWriteTargetInputTypes`, `GraphitronSchemaBuilder.java:796`).
  Computed off the classified model: add `inputArg().typeName()` for every
  `MutationDeleteTableField` / `MutationDeletePayloadField` /
  `MutationBulkDeletePayloadField` (the record-carrier DML leaves are
  INSERT/UPSERT-only by compact constructor, so these three are exhaustive).
  Conservative any-DELETE-consumer rule, deliberately stronger than the Backlog
  draft's "consumed only by DELETE": in commit 1 the return-derivation does not
  yet exist, so the input's `@table` is genuinely the sole write-target signal
  for *every* DELETE shape, and R332's D3 rationale (a false fire tells an
  author to delete their only signal) applies verbatim.
  Warning-without-alternative is the defect this item opens against. The final
  commit **deletes this carve-out set again** (additive-then-cutover; no dead
  set left behind).
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
- **The old "only accept `@table` input arguments" structural rejection** in
  `resolveDmlWalkerInputArg` (`:4610-4613`) must be reworded for DELETE: absence
  of `@table` is no longer an error once the table resolves from the return or
  `@mutation(table:)`. The DELETE-side rejection now fires only when *none* of
  the three sources resolves, and its message enumerates all three ("return a
  `@table` type, add `@mutation(table:)`, or annotate the input"). UPDATE keeps
  today's message verbatim.

## Scope

- R332 DELETE carve-out as the first commit (deleted again in the last).
- Add the `table: String` argument to the `@mutation` directive in
  `directives.graphqls:229` (document it as DELETE-relevant; loud typed rejection
  on other verbs per the design section).
- The `DmlWalkerInputArgResolution` third arm in `resolveDmlWalkerInputArg`; the
  precedence + fallback + diagnostics in `classifyDeleteTableField` /
  `classifyDeletePayloadField`; the ID-return encoder lookup (`:4756-4770`) and
  `DeleteRowsWalker` calls re-sourced to the field-resolved table + fields.
- The factored input-field-resolution helper shared with
  `TypeBuilder.buildTableInputType`, returning the narrow
  `(TableRef, List<InputField>)` fact.
- Call-site enforcement of the `validateTableInputType` input-field rules on the
  field-derived path (validator-mirror obligation above).
- Wording updates to the R332 warning, the structural rejections, and the
  redundancy/conflict diagnostics above.
- Docs: `docs/manual/reference/directives/mutation.adoc` (the SDL signature,
  parameter table, and the "input type must carry `@table`" constraint bullet all
  change), `table.adoc` WARNING, `deprecations.adoc` row,
  `docs/architecture/reference/code-generation-triggers.adoc` rows.

## Sequencing

1. **R332 DELETE carve-out** (immediate relief; independently shippable).
2. **Convention rung**: the `DmlWalkerInputArgResolution` third arm, the factored
   field-resolution helper, return-derived resolution in the two DELETE
   classifiers, call-site validator-mirror enforcement, reworded rejections.
3. **Override rung**: the `table:` directive argument, precedence + redundancy /
   conflict diagnostics, the unsupported-verb rejection with the shared verb set.
4. **Cutover**: R332 wording gains the DELETE replacement instruction, the DELETE
   carve-out set from commit 1 is deleted, docs land, the execution-tier sakila
   fixture drops `@table` from its input.

Steps 2 and 3 may merge if the diff stays reviewable; step 4 must not land
before both.

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
  at build time naming both; agreeing emits the redundancy warning. An input
  `@table` disagreeing with a live source is outranked, not rejected (migration
  bridge).
- Pipeline-tier: `@mutation(table:)` on an INSERT/UPSERT/UPDATE field rejects
  loudly with the typed unsupported-verb rejection.
- Pipeline-tier: validator-mirror parity: an input with a non-column,
  non-override field rejects identically on the `@table`-on-input path and the
  field-derived path (the R330 validator-bypass pin).
- Pipeline-tier: R332 warning suppressed for DELETE-consumed inputs before the
  parameter, fires with the replacement wording after.
- Execution-tier: a sakila DELETE fixture with `@table` removed from the input and
  the table set via `@mutation(table:)` round-trips correctly against PostgreSQL.

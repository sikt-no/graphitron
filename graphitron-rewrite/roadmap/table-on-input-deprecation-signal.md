---
id: R332
title: "Mark @table on input types as deprecated (signal ahead of R97 removal)"
status: Backlog
bucket: cleanup
priority: 6
theme: model-cleanup
depends-on: []
created: 2026-06-18
last-updated: 2026-06-18
---

# Mark @table on input types as deprecated (signal ahead of R97 removal)

The `@table` directive is declared on three scopes
(`directives.graphqls:13`: `directive @table(name: String) on OBJECT |
INPUT_OBJECT | INTERFACE`). The `INPUT_OBJECT` scope is slated for removal: its
information is redundant with the consuming field's return-type table, and R97
owns the replacement (consumer-derived tables + `argMapping` grouping) and the
eventual scope-narrowing to `OBJECT | INTERFACE`. But R97 is a large
architecture item gated behind R94, so the removal is some distance out, and
there is no user-facing signal today telling consumers that `@table`-on-input is
on its way out. This item is the **deprecation announcement only**: surface, now
and cheaply, that `@table` on an input type is deprecated, so consumers stop
adding new usages ahead of R97's removal. It deliberately does **not** change
classification behavior (that is R97's job); it makes the existing behavior
loudly deprecated.

## Origin and the R315 note

R315 (Done, `0bb7161` + rework `0d4acca`; bind FK-reference `@nodeId` onto
jOOQ-record `@service` params) already took the first bite out of
`@table`-on-input, but only on one path. Its "convergence by rejection" decision
(D2) added a narrower `isTableRecord` reject arm in `InputBeanResolver`: a
`@table`-present record param now fails honestly with "drop `@table`; the service
owns the DML" instead of silently falling to the bean path's misleading "has no
fields matching" error. Crucially, R315 scoped that to the jOOQ-record `@service`
case and was explicit that it does **not** deprecate `@table` generally, that the
general deprecation is R97's, and that R315's targeted rejection is
forward-compatible with it. So today the picture is uneven: one path hard-rejects
`@table`-on-input, every other path silently accepts it, and nothing announces
the intended direction. This item closes that gap with a deprecation signal over
input-type usages, with one carve-out (the encoded-ID / scalar-return
INSERT/UPSERT case; see "Scope constraint" below) that the signal must not flag
until its replacement mechanism (R327) lands.

## Why a separate item from R97

R97 couples the deprecation to its replacement mechanism: its Phase 2 build
warning ("`@table` on input is redundant; consumer-derived table resolution is
in effect") can only fire once consumer-derived resolution exists, which depends
on R97 Phase 1 + R94. A pure "deprecated, will be removed; see R97" signal needs
none of that machinery and can ship immediately. Shipping the announcement early
is the point: it tells consumers to stop adding new `@table`-on-input usages
while the replacement is still being built, and it shrinks the eventual R97
migration. This item is the signaling precursor and should be folded into the
removal owner (or retired) if that owner lands its own deprecation warning first.

Note that "the removal owner" is not cleanly R97 alone; see the cluster section
below. R97 names the consumer-derived resolution + `argMapping` grouping, but the
field-relative mechanism that actually retires `@table`-on-input is R327's, and
the directive-scope narrowing is claimed by both R97 Phase 3 and R222 Stage 7.
The Spec author should reconcile against all three before settling which item the
"see Rnn" pointer in the deprecation message targets.

## Related items: the `@table`-on-input cluster

Three other live items converge on `@table`-on-input. They were filed
independently and do not all cross-reference each other; this item's Spec should
reconcile the ownership before announcing a direction, because the deprecation
message points at "the removal owner" and that owner is currently contested.

- **R97** (`consumer-derived-input-tables`, Backlog, architecture). The original
  full-lifecycle item: consumer-derived table resolution + `argMapping` grouping
  (GG-376) + a Phase 2 build warning + Phase 3 directive-scope removal. Its
  redundancy proof walks only the `createFilm(in: ...): Film @table` happy path.
- **R327** (`field-relative-input-classification`, Backlog, architecture). The
  concrete, reachable-now mechanism: derive an input's table-boundness from the
  consuming field's resolved target (via `lookAheadVerdict`) instead of the
  global `@table` + `findReturnTablesForInput` aggregate. Split out of R317
  slice 4. This is the item that actually retires `@table`-on-input; R97's
  "consumer-derived tables" is the same idea under a different name.
- **R222** (`dimensional-model-pivot`, **Spec**, structural). The umbrella that
  already declares it absorbs R97: Stage 5 removes `findReturnTablesForInput`,
  Stage 7 narrows `@table` / `@record(class:)` / `@value` out of `INPUT_OBJECT`
  ("Closes R97"), and its vocabulary states "table-binding collapses to the
  consumer's `@table` return at production time." `argMapping` grouping (R97
  Phase 1) is explicitly left separable.

Overlap map (what a Spec author will collide with): `findReturnTablesForInput`
removal is claimed by both R327 and R222 Stage 5; directive-scope narrowing by
R97 Phase 3, R222 Stage 7, and R327; the deprecation signal itself by this item
**and** R97 Phase 2. The genuinely separable piece is `argMapping` grouping
(R222 says so twice). The field-relative mechanism (R327) is the load-bearing
migration step the directive narrowing depends on.

## Scope constraint: the encoded-ID INSERT/UPSERT carve-out

A blanket "`@table` on input is deprecated, remove it" signal is **wrong** for
one class of mutation, and the Spec must carve it out (or gate the whole signal
behind R327).

The write-target table is sourced per verb today (`MutationField.DmlTableField`,
`MutationField.java:82-109`):

- INSERT / UPSERT "carry the `@table` `TableInputArg` that drives the statement
  **directly**" (`MutationField.java:92-94`). The input's `@table` *is* the
  write-target mechanism for these.
- UPDATE / DELETE already moved off it (R246 / R266) onto a field-relative
  walker carrier; the code comment already cites "per R222, input fields have no
  semantics independent of the consuming field."
- The return shape is an independent axis (`DmlReturnExpression`, R204):
  `Encoded` (ID / scalar return), `Projected` (`@table` return), or class-backed
  payload.

The replacement framing "table-binding collapses to the consumer's `@table`
return" (R222) covers INSERT/UPSERT that **return** a `@table` type, and the
single-`@table`-field payload case the model already reads
(`MutationField.java:271`). It does **not** cover INSERT/UPSERT returning an
**encoded ID or scalar** (`createFilm(...): ID`): the return type carries no
`@table`, so there is nothing to collapse to, and `@table`-on-input is currently
the *only* signal naming the write target. R327 has not yet migrated those arms.

Consequence for this item: the deprecation signal must **not** fire on inputs
feeding encoded-ID / scalar-return INSERT/UPSERT until R327 closes that
derivation, or it instructs authors to remove the only mechanism that works.
(Secondary correction for whoever writes R327/R97/R222 prose: the table comes
from the consuming field's *resolved target*, not literally its *return type*;
the "consumer's `@table` return" wording papers over the encoded-ID case.)
`argMapping` grouping does not plug this; it binds input fields to params /
columns, not the write target table.

## Candidate signal surfaces (to be settled at Spec)

The detailed plan belongs at Spec; the rough shape is a deprecation signal in
some combination of:

- the `@table` directive description in `directives.graphqls` (note the
  `INPUT_OBJECT` scope as deprecated, point at R97);
- a build-time deprecation warning emitted when `@table` is seen on an
  `INPUT_OBJECT` (distinct from R315's hard reject on the narrow service-record
  path, and distinct from R97's "redundant; consumer-derived resolution in
  effect" warning that presumes the replacement is live);
- an LSP deprecation diagnostic / hint on `@table` applied to an input type;
- user-facing docs (`code-generation-triggers.adoc`, `docs/README.adoc`)
  marking `@table`-on-input as deprecated.

Spec should decide which of these constitute "marked as deprecated" for this
item versus what is left to R97's removal phases, and whether the build warning
is unconditional or suppressible.

## Out of scope

- The `OBJECT` and `INTERFACE` scopes of `@table`. Those carry load-bearing
  output-emit semantics (`TableType` / `TableInterfaceType`) with no
  consumer-derived equivalent, and are not being deprecated.
- Changing input classification or removing the directive scope. That is R97
  (Phases 2 and 3). This item only adds the deprecation signal over today's
  behavior.
- The `argMapping` grouping / consumer-derived table mechanism. Owned by R97.

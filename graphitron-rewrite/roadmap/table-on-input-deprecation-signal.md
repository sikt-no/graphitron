---
id: R332
title: "Mark @table on input types as deprecated (signal ahead of R97 removal)"
status: Spec
bucket: cleanup
priority: 6
theme: model-cleanup
depends-on: []
created: 2026-06-18
last-updated: 2026-06-22
---

# Mark @table on input types as deprecated (signal ahead of R97 removal)

The `@table` directive is declared on three scopes
(`directives.graphqls:13`: `directive @table(name: String) on OBJECT |
INPUT_OBJECT | INTERFACE`). The `INPUT_OBJECT` scope is slated for removal: its
information is redundant with the consuming field's return-type table, and R97
owns the replacement (consumer-derived tables + `argMapping` grouping) and the
eventual scope-narrowing to `OBJECT | INTERFACE`. But R97 is still Backlog and
the scope removal that closes it (R222 Stage 7) is only at Spec, so the removal
is some distance out, and
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
until its replacement mechanism (R97 Phase 2b) lands.

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

The removal owner is now R97: as of 2026-06-22 it absorbed R327
(`field-relative-input-classification`, the field-relative derivation that
retires `@table`-on-input) into its consumer-derived resolution, so the
consumer-derived-tables item and the mechanism that actually retires the
directive are the same item. The only residual ownership overlap is with R222
Stage 7 (directive-scope narrowing); see the cluster section below. Decisions
(D2) settle the surfacing: the user-facing message names no `R<n>` at all (it
gives a replacement instruction); the internal code-comment pointer names the
mechanism (R97 / R222 Stage 7).

## Related items: the `@table`-on-input cluster

Two other live items converge on `@table`-on-input (a third, R327, folded into
R97 on 2026-06-22). They were filed independently and do not all cross-reference
each other. The Decisions and Roadmap-coordination sections below reconcile the
ownership: the user-facing signal names no owner (D2); the internal pointer names
the mechanism (R97 / R222 Stage 7).

- **R97** (`consumer-derived-input-tables`, Backlog, architecture). The
  full-lifecycle removal owner, and now also the mechanism: consumer-derived
  table resolution + `argMapping` grouping (GG-376) + a Phase 2 build warning +
  Phase 2b INSERT/UPSERT write-target migration + Phase 3 directive-scope
  removal. It absorbed R327's field-relative derivation (derive an input's
  table-boundness from the consuming field's resolved target via
  `lookAheadVerdict`, retiring the global `@table` + `findReturnTablesForInput`
  aggregate; split out of R317 slice 4) into Phase 2, so the consumer-derived
  resolution and the directive's actual retirement are one item.
- **R222** (`dimensional-model-pivot`, **Spec**, structural). The umbrella that
  already declares it absorbs R97: Stage 5 removes `findReturnTablesForInput`,
  Stage 7 narrows `@table` / `@record(class:)` / `@value` out of `INPUT_OBJECT`
  ("Closes R97"), and its vocabulary states "table-binding collapses to the
  consumer's `@table` return at production time." `argMapping` grouping (R97
  Phase 1) is explicitly left separable.

Overlap map (what a Spec author will collide with): `findReturnTablesForInput`
removal is claimed by both R97 (Phase 2) and R222 Stage 5; directive-scope
narrowing by R97 Phase 3 and R222 Stage 7; the deprecation signal itself by this
item **and** R97 Phase 2. The genuinely separable piece is `argMapping` grouping
(R222 says so twice). R97's field-relative mechanism (Phase 2 / 2b) is the
load-bearing migration step the directive narrowing depends on.

## Scope constraint: the encoded-ID INSERT/UPSERT carve-out

A blanket "`@table` on input is deprecated, remove it" signal is **wrong** for
one class of mutation, and the signal carves it out. The carve-out is computable
from today's model (Decisions, D3), so it ships in this item and does **not** gate
R332 behind R97 Phase 2b.

The write-target table is sourced per verb today (the `MutationField.DmlTableField`
sealed supertype and its four leaves):

- INSERT / UPSERT carry the `@table` `TableInputArg` directly:
  `MutationInsertTableField` / `MutationUpsertTableField` each hold a `tableInputArg`
  component. The input's `@table` *is* the write-target mechanism for these.
- UPDATE / DELETE already moved off it (R246 / R266) onto a walker carrier:
  `MutationUpdateTableField` / `MutationDeleteTableField` hold an `inputArg` plus a
  `updateRows` / `deleteRows` carrier, not a `TableInputArg`. Per R222, input fields
  have no semantics independent of the consuming field.
- The return shape is an independent axis (`DmlReturnExpression`, R204):
  `Encoded` (ID / scalar return), `Projected` (`@table` return), or class-backed
  payload.

The replacement framing "table-binding collapses to the consumer's `@table`
return" (R222) covers INSERT/UPSERT that **return** a `@table` type, and the
single-`@table`-field payload case the model already reads
(`MutationServiceRecordField`'s R204 carrier-shape note). It does **not** cover
INSERT/UPSERT returning an
**encoded ID or scalar** (`createFilm(...): ID`): the return type carries no
`@table`, so there is nothing to collapse to, and `@table`-on-input is currently
the *only* signal naming the write target. R97 Phase 2b has not yet migrated
those arms.

Consequence for this item: the deprecation signal must **not** fire on inputs
feeding encoded-ID / scalar-return INSERT/UPSERT until R97 Phase 2b closes that
derivation, or it instructs authors to remove the only mechanism that works.
(Secondary correction for whoever writes R97 / R222 prose: the table comes
from the consuming field's *resolved target*, not literally its *return type*;
the "consumer's `@table` return" wording papers over the encoded-ID case.)
`argMapping` grouping does not plug this; it binds input fields to params /
columns, not the write target table.

## Decisions

**D1. Two signal tiers, split by whether the signal fires per-usage.**

- *Prose tier (ships unconditionally; carries the carve-out in words).* The `@table`
  directive description in `directives.graphqls`; the `@table`-on-input row in
  `code-generation-triggers.adoc`; a row in the deprecations index
  (`docs/manual/reference/deprecations.adoc`) plus the deprecation note on the
  canonical directive page (`docs/manual/reference/directives/table.adoc`). None of
  these compute anything per usage, so they state the carve-out in prose and are safe
  to ship now.
- *Actionable tier (fires per `@table`-on-input usage; must respect the carve-out).*
  A non-fatal build warning (`BuildWarning` via `ctx.addWarning`, the same channel as
  the `@record` "directive ignored" warning at `TypeBuilder.emitDirectiveIgnoredWarning`).
  Non-fatal by the `BuildContext` warnings contract: it never fails the build.

The LSP per-usage diagnostic is **deferred** (see Out of scope): the directive-description
edit already surfaces on LSP hover for free (the LSP reads `directives.graphqls` through
`RewriteSchemaLoader.directivesSdl()`), and a dedicated squiggle would re-implement the
carve-out LSP-side without the `MutationField` model in hand, out of proportion to a nudge.

**D2. User-facing surfaces cite no roadmap ID; they give a replacement instruction.**
R97 / R222 are internal, churn, and (per the cluster section above) overlap on
ownership; a schema author cannot navigate them. The description and docs say *what
replaces `@table`-on-input* in consumer terms ("the consuming mutation field's resolved
target determines the write table; remove the directive"), not "see R97." The internal
"which item removes the scope" pointer lives only in code comments and this roadmap item,
and names the *mechanism* (R97's field-relative derivation / R222 Stage 7's directive
narrowing). This mirrors the existing `@record` description, which names
its build warning and a replacement but no `R<n>`.

**D3. The carve-out is computed from the existing model; R332 stays `depends-on: []`.**
The carve-out set is the SDL input type names that are the `tableInputArg().typeName()`
of a `MutationInsertTableField` / `MutationUpsertTableField` whose `returnExpression()`
is a `DmlReturnExpression.Encoded*` arm. Every part is already pre-resolved on the
classified model (the encoded-vs-projected axis is a settled `DmlReturnExpression` arm;
the leaf already names its input type), so no `lookAheadVerdict`, reflection, or R97's
field-relative mechanism is needed. Conservative rule for the type-level coarseness (one input feeding both an encoded
INSERT and a projected consumer): suppress the warning if *any* consumer is an encoded
INSERT/UPSERT. The failure modes are asymmetric: a false suppress costs an author one extra
release carrying a directive; a false fire tells an author to delete the only signal naming
their write target and breaks their build. Per-`(input, consumer)` precision is R97's axis.

**D4. The build warning is unconditional, not suppressible.** A nudge that can be switched
off defeats the "stop adding new usages" purpose, and there is no build-breakage to suppress
(it is non-fatal). The earlier "unconditional or suppressible" question resolves to unconditional.

## Implementation

Flat file list; the only ordering constraint is "the warning pass reads the classified model."

- **`directives.graphqls`** — extend the `@table` description (line 13) with the input-type
  deprecation note and replacement instruction (draft below). No SDL `@deprecated` marker:
  the GraphQL spec forbids it on a directive definition (let alone a single location), which
  is exactly why this routes through prose + the doc-coverage allow-list.
- **`GraphitronSchemaBuilder.build()`** — add a post-classification pass
  `emitTableOnInputDeprecationWarnings(ctx)`, placed beside `rejectCaseInsensitiveTypeCollisions(ctx)`
  (the existing precedent for a cross-cutting pass on the live `ctx` after classification,
  before the `new GraphitronSchema(...)` snapshot). The pass: (1) computes the carve-out set;
  (2) walks input types that *explicitly declare* `@table` (the `tableOpt`-resolved branch of
  `TypeBuilder.buildInputType`, not the consumer-derived `findReturnTablesForInput` branch,
  which carries no author-written directive); (3) for each not in the carve-out set,
  `ctx.addWarning(new BuildWarning(message, inputType.getSourceLocation()))`. Inline emission in
  `TypeBuilder.buildInputType` is wrong: it lacks the consuming-field view the carve-out needs.
- **`encodedWriteTargetInputTypes(...)`** — a single named helper returning `Set<String>`. This
  is the find-usages anchor R97 Phase 2b retires (see Roadmap coordination); keep it named
  rather than inlining the stream.
- **`docs/manual/reference/deprecations.adoc`** — add a row (draft below); widen the "Deprecated
  whole directives" section intro to cover a deprecation the spec cannot mark inline on a
  directive *location*, not only a whole directive.
- **`docs/manual/reference/directives/table.adoc`** — add the deprecation note on the canonical
  per-directive page (the deprecations index defers to it as the migration guide).
- **`graphitron-rewrite/docs/code-generation-triggers.adoc`** — annotate the
  `Input type with @table` → `TableInputType` row (line 176) as deprecated-on-input.
- **`DeprecationsDocCoverageTest`** — add `"table"` to `WHOLE_DIRECTIVE_DEPRECATIONS`. The
  constant name becomes mildly inaccurate (`@table` is location-scoped, not wholly deprecated);
  an optional rename to e.g. `DIRECTIVE_LEVEL_DEPRECATIONS` is polish, not required for green.

## Tests

- **Pipeline tier** (the carve-out is the load-bearing behavior, and this test is what fails
  when R97 Phase 2b moves the INSERT/UPSERT arms):
  - encoded INSERT/UPSERT (`createFilm(in: FilmInput @table): ID`) → assert **no** `@table`-on-input
    deprecation warning names `FilmInput`.
  - a non-carved usage (projected `... : Film`, or a query / UPDATE / DELETE `@table`-on-input) →
    assert the warning **is** present, with the input's source location.
  - (optional) one input reused by an encoded INSERT and a projected consumer → assert suppressed
    (pins the conservative rule, D3).
- **Doc-coverage:** `DeprecationsDocCoverageTest.everyWholeDirectiveDeprecationHasARowInTheIndex`
  already gates the `"table"` allow-list entry against a `` `@table` `` row in `deprecations.adoc`;
  the new row + allow-list entry turn it green and keep it as the drift guard between the
  description prose and the index.

## Roadmap coordination

- **R97 Phase 2b** (the INSERT/UPSERT write-target migration) is what empties the carve-out:
  once the write target is field-relative, encoded INSERT/UPSERT inputs no longer need `@table`,
  so they should warn too. R97 Phase 2b retires `encodedWriteTargetInputTypes(...)` and lets the
  warning fire on those inputs. This is a forward edge R97 → R332's code, so R332's
  `depends-on: []` is correct; R97's plan carries the "retires `encodedWriteTargetInputTypes`"
  note.
- The internal removal pointer in code comments names R97 / R222 Stage 7 (R97's ownership now
  also covers the field-relative mechanism, per the cluster section).
- Per the "signaling precursor" framing above, fold or retire this item when R97 Phase 3 / R222
  Stage 7 lands the scope removal and the warning generalizes.

## User-facing surfaces (first-client draft)

The directive surface and docs are the design's first client; the drafts below move into their
real homes when the item ships. If they do not read simply, the design is wrong.

**`@table` description (`directives.graphqls`):**

```
Connect this type to a jOOQ table. All the containing fields will by default be assumed to be located there.

Deprecated on input types: applying `@table` to an input (`INPUT_OBJECT`) is deprecated and will be removed in a future release. A mutation's write/read target is derived from the consuming field's resolved target table, so the directive on the input is redundant; remove it. (One case still needs it for now: a mutation returning an encoded ID or scalar, e.g. `createFilm(...): ID`, where the input's `@table` is currently the only signal naming the write target. The build does not warn on that case yet.) `@table` on `OBJECT` / `INTERFACE` is unaffected.
```

**Build warning message:**

```
`@table` on input type 'X' is deprecated and will be removed in a future release; the write target is derived from the consuming mutation field's resolved table. Remove `@table` from this input.
```

**`deprecations.adoc` row (in the directive-level section):**

```
| xref:directives/table.adoc[`@table`] (on `INPUT_OBJECT`)
| Deprecated; `OBJECT` / `INTERFACE` unaffected
| Rewrite v1
| Remove `@table` from input types. The mutation's write/read target is derived from the consuming field's resolved target table, so the directive on the input is redundant. A build warning fires per `@table`-on-input usage, except encoded-ID / scalar-return INSERT/UPSERT, where the input's `@table` is still the only write-target signal until that derivation lands.
```

## Out of scope

- The `OBJECT` and `INTERFACE` scopes of `@table`. Those carry load-bearing
  output-emit semantics (`TableType` / `TableInterfaceType`) with no
  consumer-derived equivalent, and are not being deprecated.
- Changing input classification or removing the directive scope. That is R97
  (Phases 2 and 3). This item only adds the deprecation signal over today's
  behavior.
- The `argMapping` grouping / consumer-derived table mechanism. Owned by R97.
- A dedicated LSP per-usage deprecation diagnostic (a squiggle on the `@table`-on-input
  application). The description prose already surfaces on LSP hover; a per-usage diagnostic
  re-implements the carve-out LSP-side without the `MutationField` model and is deferred to
  the field-relative model (R97 Phase 2) or a follow-on. See Decisions, D1.

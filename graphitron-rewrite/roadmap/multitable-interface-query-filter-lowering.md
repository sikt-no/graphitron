---
id: R363
title: "Lower @field filter inputs and @condition onto multitable-interface queries"
status: Spec
bucket: bug
priority: 2
theme: interface-union
depends-on: []
created: 2026-06-24
last-updated: 2026-06-24
---

# Lower @field filter inputs and @condition onto multitable-interface queries

## Problem

A `@field`-mapped filter input (and `@condition`) on a Query field that returns a multitable
interface or union is silently dropped during code generation. Codegen succeeds,
`graphitron:validate` passes, the query runs, and it returns every row unfiltered, with no error or
warning. In the reporting context (an access-control subgraph whose `Applikasjon` interface spans
`feide_applikasjon` / `maskinporten_applikasjon` / `maskinbruker_applikasjon`) that silently leaks
the full table where the consumer asked for a filtered slice, so the severity is data-correctness,
not ergonomics. Reported as a 9.x to 10.x regression: 9.3 threaded the filter into every UNION
branch. The rewrite never built the path, so the regression framing is right under the
feature-equivalence goal; confirm the exact 9.3 generated shape before citing it externally.

## Mechanism (confirmed by source trace)

The filter has nowhere to live, at any layer; this is a missing feature across model, builder,
validator, and emitter, not one dropped call.

- **Model has no slot.** `QueryField.QueryInterfaceField` and `QueryField.QueryUnionField` carry only
  `parentTypeName, name, location, returnType, participants`; no `filters`, `orderBy`, `pagination`,
  or `condition`. Contrast `QueryTableField` and the single-table `QueryTableInterfaceField`, which
  carry `List<WhereFilter>`. `operation()` hardcodes `List.of()` filters and `OrderBySpec.None()` for
  the polymorphic arms.
- **Builder never parses the arguments.** `FieldBuilder` (interface/union arms, around lines
  3385-3394) constructs the record from `participants()` alone and never calls
  `resolveTableFieldComponents(...)`, the only method that turns field arguments, `@field`-mapped
  inputs, and `@condition` into `WhereFilter`s (and that can return `Rejected`). Every call site of
  that method is on a table-bound path.
- **Emitter reads only pagination.** `MultiTablePolymorphicEmitter` calls `env.getArgument` only for
  `first`/`last`/`after`/`before`; `buildStage1Block` / `buildStage1ConnectionBlock` emit a bare
  `UNION ALL` with no per-branch WHERE for root queries. The one WHERE-producing helper
  (`branchParentFkWhere`) exists only for child fetchers' parent-FK predicate.
- **`@condition` is unvalidated on this path.** Because the directive is never lowered into the model,
  a `@condition` naming a non-existent method passes `graphitron:validate`:
  `GraphitronSchemaValidator.validateQueryInterfaceField` / `validateQueryUnionField` only check
  cardinality and participants, never the `@condition` predicate.

Do not conflate these multitable variants with `QueryTableInterfaceField`, the single-table
discriminator interface, which does carry and apply filters (it emits a discriminator IN-filter) and
is unaffected.

## Plan

The fix mirrors the single-table discriminator interface (`QueryTableInterfaceField`), which already
carries and applies a filter surface (`QueryField.java:189-204`); the two multitable polymorphic
variants are the only catalog-bound query fields without it.

**Scope (Spec review 2026-06-25): `@field` filters only. `orderBy` is cut to a follow-up (R382).**
The original draft put `orderBy` in day-one scope alongside filters, but the multitable emitter
hardcodes the synthetic `__sort__` key as the participant PK, and on the connection path `__sort__`
*is* the Relay cursor seek key (round-tripped through `ConnectionHelper.encodeCursor` /
`decodeCursor` with `__typename ASC` as the tiebreaker). Threading a user `orderBy` means projecting
a chosen column into every UNION branch, replacing `__sort__` as the sort and cursor key, and
round-tripping it through the cursor codec, materially more work than the filter `.where(...)`
threading this item does, and the reported defect is purely the dropped `@field` filter. Carrying
an `orderBy` model slot the emitter ignores would re-create the producer-carries / consumer-drops
gap this item exists to close. So day one keeps `operation()` passing `new OrderBySpec.None()` for
these arms unchanged; R382 owns the `__sort__`/cursor reconciliation.

**Resolved design decision (Spec review): the filter surface is per-participant, not a single shared
list.** A `GeneratedConditionFilter` names a *table-specific* generated condition method and column
constants (`WhereFilter.java`): the same logical `@field` filter on `ORGANISASJONSKODE` resolves to
`FeideApplikasjonConditions.…(FEIDE_APPLIKASJON.ORGANISASJONSKODE, …)` for one participant and
`MaskinportenApplikasjonConditions.…(MASKINPORTEN_APPLIKASJON.…)` for another — *different*
`WhereFilter`s. So the builder lowers the filter arguments once **per participant table**, against
each participant's own `TableRef`, and the model carries the resolved filters keyed by participant.
This single decision settles the three questions the original draft left open:

- **Which layer rejects a column absent from a participant — builder or validator?** The classifier
  (builder). Lowering the filter against a participant whose table lacks the `@field`-named column
  makes that participant's `resolveTableFieldComponents` return `TableFieldComponents.Rejected` (the
  existing `unknownColumn` typed rejection), which fails the build naming the participant and column.
  No separate validator pass is invented; per "Validator mirrors classifier invariants" the validator
  already surfaces classifier rejections. The contract — "every participant carries the filtered
  column" (the 9.x behaviour) — is thus enforced at classify time, not as a runtime scope-narrowing.
- **Column-type compatibility across participants.** Falls out of the same per-participant lowering:
  each participant's arg→column binding is validated against that participant's column `DataType`
  (`DSL.val(rawValue, col.getDataType())` binds per branch). A same-named column with an incompatible
  type on one participant surfaces as that participant's rejection, not a latent per-branch bind hole.
- **`@condition` scope (day one).** Out of scope for this slice, and **rejected — not silently
  dropped**. A developer `@condition` method takes a single concrete jOOQ `Table` first parameter; a
  multitable field has N participant tables, so per-participant `@condition` lowering needs an emitted
  per-participant adapter, a real design that a future item can take up. Day one lowers `@field`-mapped
  column filters (the reported data-correctness bug) and rejects a `@condition` on the multitable path
  with a typed rejection, closing the silent-pass hole the Mechanism section flags. **The rejection
  uses a non-deferred kind (`Rejection.structural(...)`), not `Rejection.deferred(slug)`.** Nothing in
  the build pins a deferred `planSlug` to a real roadmap file (shipped slugs already dangle), so a
  slug here would be an unenforced dangling pointer, the exact hazard the predecessor cluster had to
  fix. A `@condition` on this path is "recognised but unsupported on this site", which `structural`
  expresses without a slug, so no follow-up item need exist for the rejection to be honest.

1. **Model: add the per-participant filter surface.** Give `QueryField.QueryInterfaceField` and
   `QueryField.QueryUnionField` (`QueryField.java:212-245`) a *per-participant* filter carrier and
   have them `implements SqlGeneratingField` as `QueryTableInterfaceField` does. The carrier must be
   per-participant: the same logical `@field` filter resolves to a *different* table-specific
   `WhereFilter` per participant, so a single shared `List<WhereFilter>` cannot serve it. Prefer
   co-locating the resolved `List<WhereFilter>` on each `ParticipantRef.TableBound` (or a small
   per-participant record) over a parallel `Map<typeName, List<WhereFilter>>` keyed by a stringly
   typename, which invites key-set / participant-set drift the co-located shape makes
   unrepresentable. No `orderBy` slot this slice (see Scope above; R382). Pagination already flows
   through the connection wrapper, so day-one scope is `filters` only.
2. **`operation()`: stop hardcoding the empty filter list.** The interface/union arms
   (`QueryField.java:46-47`) pass `List.of(), new OrderBySpec.None()`; pass the lowered
   per-participant filters in place of `List.of()` (keep `new OrderBySpec.None()` — `orderBy` is
   R382). Note the per-participant filter shape may not fit `OutputField.readOperation`'s existing
   single-`List<WhereFilter>` parameter as-is; reconciling that signature (or routing the
   per-participant filters to the emitter outside `operation()`, see step 4) is part of this step.
3. **Builder: lower the arguments per participant; reject `@condition`.** In `FieldBuilder`
   (interface/union arms at `FieldBuilder.java:3441-3450`), for each table-bound participant call
   `resolveTableFieldComponents(fieldDef, participant.table(), elementTypeName, …)`, collect the
   per-participant filters, and surface any participant's `Rejected` as the field's rejection (a
   column absent from one participant, or a type mismatch, fails the build naming the participant).
   Watch the side effects of running `resolveTableFieldComponents` N times: it also emits the
   `@asConnection` same-table `@nodeId` advisory warning, so guard against emitting that warning once
   per participant (lower it once, or dedupe). Separately, if the field carries a `@condition`, return
   `Rejection.structural("@condition on a multitable interface/union is not yet supported")` — a
   non-deferred kind, no slug (see the `@condition` design decision above). Keep this rejection on the
   classify side (FieldBuilder), not bolted into the validator, consistent with where the multitable
   participant invariants already live.
4. **Emitter: thread each participant's filters into its own UNION branch.** The non-connection branch
   loop (`MultiTablePolymorphicEmitter.buildStage1Block`, ~`:872-910`) already ANDs in
   `branchParentFkWhere` (~`:927-951`); the connection branch loop (`buildStage1ConnectionBlock`,
   ~`:807-854`) currently emits no per-branch `.where(...)` at all and needs one added. The emitter
   entry points (`emitMethods` / `emitConnectionMethods`) take `participants` but no filters today, so
   add a per-participant-filters parameter and pass it from the call sites in
   `TypeFetcherGenerator.java` (~`:452-475`, which currently pass `f.participants()`). Extend each
   branch loop (or add a sibling `branchFilterWhere`) to AND each participant's lowered filters into
   its `stage1_<Type>` branch `.where(...)`, combined with the existing parent-FK predicate via
   `.and(...)`. Each participant's filters were generated against its own table, so they bind cleanly
   to that branch's alias — the same way `branchProjection` / `branchParentFkWhere` resolve columns
   today.

## Tests

- Pipeline tier: a `@field`-mapped filter input on a root `QueryInterfaceField` / `QueryUnionField`
  classifies into a model carrying a per-participant predicate for each participant.
- Pipeline tier (rejection): a filter naming a column absent from (or type-incompatible on) one
  participant fails classification with a typed rejection naming that participant and column.
- Execution tier: the query applies `WHERE <col> IN (...)` per branch and returns only matching rows.
- Validation: a `@condition` on a multitable interface/union field is rejected at build (this also
  covers the previously-silent non-existent-method case, since any `@condition` on the path rejects).
  The test asserts the rejection *kind* (`structural`), not merely that some rejection fires, so the
  no-dangling-slug decision is pinned.

## Cross-links

Shares `MultiTablePolymorphicEmitter` with R366 (list-cardinality polymorphic split-query emit).
R382 (orderBy lowering on the same fields) is the split-off follow-up for the ordering surface this
item scopes out.

## Spec-review revisions (2026-06-24)

Reviewer (Spec gate, session ≠ author) resolved the original draft's open design fork rather than
carrying it into Ready: filters are lowered **per participant**, which makes the absent-column case a
classifier rejection (not a runtime scope-narrowing or a separate validator pass), folds column-type
compatibility into the same per-participant binding, and scopes `@condition` out of day one with a
typed rejection in place of the silent drop. A fresh session must still sign this off to Ready.

## Spec-review revisions (2026-06-25)

Second reviewer (Spec gate, session ≠ author/last committer) made three changes, corroborated by a
`principles-architect` read:

- **Cut `orderBy` to a follow-up (R382).** It was in day-one scope (Steps 1–2) but contradicted by
  the emitter the plan points at: `__sort__` is the participant PK and, on the connection path, the
  Relay cursor seek key. Step 4 described only filter threading and the Tests section had no orderBy
  test, so the slot would have been carried-but-dropped, the very gap this item fixes. Day one is now
  `@field` filters only, matching the reported defect; R382 owns the `__sort__`/cursor reconciliation.
- **`@condition` rejection is now non-deferred (`structural`), no slug.** The original note ("file the
  follow-up first so the slug resolves") managed a real hazard by prose: nothing in the build pins a
  deferred `planSlug` to a roadmap file (shipped slugs already dangle), and the referenced `R367` is
  not a live item. A `structural` rejection removes the dangling-pointer risk entirely, and the
  `@condition` test now asserts the rejection kind.
- **Implementation notes added:** prefer co-locating filters on `ParticipantRef.TableBound` over a
  stringly `Map`; guard against running `resolveTableFieldComponents` per participant re-emitting the
  `@asConnection` advisory N times; the emitter entry points need a new filters parameter threaded
  from `TypeFetcherGenerator`'s call sites; drifted line references refreshed against current source.

A fresh session must still sign this off to Ready (this reviewer's substantive edits disqualify it
from the approval).

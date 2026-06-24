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
carries and applies a filter surface (`QueryField.java:184-199`); the two multitable polymorphic
variants are the only catalog-bound query fields without it.

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
  per-participant adapter — real design deferred to a follow-up item. Day one lowers `@field`-mapped
  column filters (the reported data-correctness bug) and rejects a `@condition` on the multitable path
  with a typed rejection, closing the silent-pass hole the Mechanism section flags.

1. **Model: add the per-participant filter surface.** Give `QueryField.QueryInterfaceField` and
   `QueryField.QueryUnionField` (`QueryField.java:207-240`) a *per-participant* filter carrier (e.g.
   the resolved `List<WhereFilter>` alongside each `ParticipantRef.TableBound`, or a
   `Map<typeName, List<WhereFilter>>`; the exact carrier is the implementer's call, but it must be
   per-participant — a single shared `List<WhereFilter>` cannot serve table-specific generated
   condition methods) plus `OrderBySpec orderBy`, and have them `implements SqlGeneratingField` as
   `QueryTableInterfaceField` does. `orderBy` is subject to the same "column present on every
   participant" rule, lowered per participant. Pagination already flows through the connection
   wrapper, so day-one scope is `filters` + `orderBy`.
2. **`operation()`: stop hardcoding empties.** Lines 45-46 pass `List.of(), new OrderBySpec.None()`;
   pass the lowered per-participant filters / `f.orderBy()` exactly as the `QueryTableInterfaceField`
   arm (line 40) does.
3. **Builder: lower the arguments per participant; reject `@condition`.** In `FieldBuilder`
   (interface/union arms around 3385-3394), for each participant call
   `resolveTableFieldComponents(fieldDef, participant.table(), elementTypeName, …)`, collect the
   per-participant filters, and surface any participant's `Rejected` as the field's rejection (a
   column absent from one participant, or a type mismatch, fails the build naming the participant).
   Separately, if the field carries a `@condition`, return a typed rejection ("`@condition` on a
   multitable interface/union is not yet supported"). If that rejection is produced via
   `Rejection.deferred(slug)`, **file the follow-up roadmap item first so the slug resolves** — do not
   ship the dangling-pointer bug R367 exists to fix.
4. **Emitter: thread each participant's filters into its own UNION branch.** The branch loop
   (`MultiTablePolymorphicEmitter.java:668-687`) already ANDs in `branchParentFkWhere`
   (`:708-732`). Extend the loop (or add a sibling `branchFilterWhere`) to AND each participant's
   lowered filters into its `stage1_<Type>` branch `.where(...)`, combined with the existing parent-FK
   predicate via `.and(...)`. Each participant's filters were generated against its own table, so they
   bind cleanly to that branch's alias — the same way `branchProjection` / `branchParentFkWhere`
   resolve columns today.

## Tests

- Pipeline tier: a `@field`-mapped filter input on a root `QueryInterfaceField` / `QueryUnionField`
  classifies into a model carrying a per-participant predicate for each participant.
- Pipeline tier (rejection): a filter naming a column absent from (or type-incompatible on) one
  participant fails classification with a typed rejection naming that participant and column.
- Execution tier: the query applies `WHERE <col> IN (...)` per branch and returns only matching rows.
- Validation: a `@condition` on a multitable interface/union field is rejected at build (this also
  covers the previously-silent non-existent-method case, since any `@condition` on the path rejects).

## Cross-links

Shares `MultiTablePolymorphicEmitter` with R366 (list-cardinality polymorphic split-query emit).

## Spec-review revisions (2026-06-24)

Reviewer (Spec gate, session ≠ author) resolved the original draft's open design fork rather than
carrying it into Ready: filters are lowered **per participant**, which makes the absent-column case a
classifier rejection (not a runtime scope-narrowing or a separate validator pass), folds column-type
compatibility into the same per-participant binding, and scopes `@condition` out of day one with a
typed rejection in place of the silent drop. A fresh session must still sign this off to Ready.

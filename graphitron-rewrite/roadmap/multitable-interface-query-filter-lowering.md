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

1. **Model: add the filter surface to the polymorphic variants.** Give
   `QueryField.QueryInterfaceField` and `QueryField.QueryUnionField` (`QueryField.java:207-240`) the
   `List<WhereFilter> filters` and `OrderBySpec orderBy` components the table-bound variants carry, and
   have them `implements ... SqlGeneratingField` as `QueryTableInterfaceField` does. Pagination already
   flows through the connection wrapper, so day-one scope can stop at `filters` + `orderBy`.
2. **`operation()`: stop hardcoding empties.** Lines 45-46 pass `List.of(), new OrderBySpec.None()`;
   pass `f.filters()` / `f.orderBy()` exactly as the `QueryTableInterfaceField` arm (line 40) does.
3. **Builder: lower the arguments per participant.** In `FieldBuilder` (interface/union arms around
   3385-3394) call `resolveTableFieldComponents(fieldDef, ...)` to parse field arguments,
   `@field`-mapped inputs, and `@condition` into `WhereFilter`s, and surface its `Rejected` outcome so
   a bad `@condition` method name fails the build. Each `WhereFilter` binds against each participant's
   table in step 4.
4. **Emitter: thread the predicate into every UNION branch.** `buildStage1Block` /
   `buildStage1ConnectionBlock` already have the per-branch hook: `branchParentFkWhere`
   (`MultiTablePolymorphicEmitter.java:708-732`) returns a `.where(...)` predicate the branch loop
   ANDs in (lines 668-687). Extend it (or add a sibling `branchFilterWhere`) to emit the lowered filter
   predicate per participant against the `stage1_<Type>` alias, combined with the existing parent-FK
   predicate via `.and(...)`, exactly as `branchProjection` / `branchParentFkWhere` resolve columns
   today.

**Design fork (flag to principles-architect):** a filter input maps to a column by `@field` name, and
that column must exist on every participant (the `Applikasjon` filter `ORGANISASJONSKODE` is present
on all three). Decide whether a filter naming a column absent from one participant is a validation
error or silently scopes to the participants that have it. Recommend a validation error, so the
multitable filter contract is "every participant carries the filtered column", matching the 9.x
behaviour this regression refers to.

## Tests

- Pipeline tier: a `@field`-mapped filter input on a root `QueryInterfaceField` / `QueryUnionField`
  classifies into a model that carries the predicate.
- Execution tier: the query applies `WHERE <col> IN (...)` per branch and returns only matching rows.
- Validation: a `@condition` naming a non-existent method is rejected at `validate`.

## Cross-links

Shares `MultiTablePolymorphicEmitter` with R366 (list-cardinality polymorphic split-query emit).

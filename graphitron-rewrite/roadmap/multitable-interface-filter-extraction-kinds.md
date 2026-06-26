---
id: R383
title: "Support nested-input @field filters on multitable interface/union queries"
status: Ready
bucket: bug
priority: 3
theme: interface-union
depends-on: []
created: 2026-06-25
last-updated: 2026-06-26
---

# Support nested-input @field filters on multitable interface/union queries

R363 lowered `@field`-mapped filter inputs onto multitable interface/union root query fields, but
scoped day one to the branch-safe argument extractions: `Direct`, `EnumValueOf`, and `ContextArg`.
That covers a filter declared as a **top-level argument** (`occupants(firstName: [String!] @field(...))`),
but not the same filter delivered through an **input object**
(`occupants(filter: OccupantFilter)` where `OccupantFilter { firstNames: [String!] @field(...) }`),
which is the idiomatic shape for a growable, reusable filter surface. A nested `@field` column lowers
to an implicit column-equality predicate whose call-site extraction is a
`NestedInputField(filter -> firstNames, leaf)`; because `NestedInputField` was not in the branch-safe
set, `FieldBuilder.firstUnsupportedFilterArg` rejected it at classify time with an
`author-error`, even though the leaf is a plain scalar. Consumers hit this as a hard build failure
on a perfectly ordinary filter-input schema.

## Key finding

The nested-input `@field` family needs **only a classifier relaxation**, not the registry / lift-context
plumbing the sibling kinds (R384) require:

- The condition-method generator (`TypeConditionsGenerator.buildConditionMethod`) is
  **extraction-agnostic**: it builds the predicate purely from each `BodyParam`'s column and
  Eq/In/Row shape, never reading the `CallSiteExtraction`. So the generated `<Participant>Conditions`
  method is byte-identical whether the value arrives top-level or Map-traversed.
- The call-site emitter's `NestedInputField` arm (`ArgCallEmitter.buildNestedInputFieldExtraction`)
  emits a **self-contained** null-safe Map traversal
  (`env.getArgument("filter") instanceof Map<?, ?> map1 ? (List<String>) map1.get("firstNames") : null`)
  that needs the `CompositeDecodeHelperRegistry` and the `liftedOuters` locals only when the leaf is a
  `NodeIdDecodeKeys` decode. The branch path already passes `registry = null`, so a `Direct` /
  `EnumValueOf` / `ContextArg` leaf needs nothing extra.
- A nested `@field` column is always built with a `Direct` leaf (`BuildContext`), so admitting the
  nested family by leaf-recursion both covers every plain nested filter and keeps the genuinely
  registry-bound kinds (nested `@nodeId`) rejected by construction.

## Delivered

- `FieldBuilder.isBranchSafeExtraction` is now a recursive switch: a `NestedInputField` is branch-safe
  exactly when its `leaf` is. `Direct` / `EnumValueOf` / `ContextArg` leaves are admitted; a
  `NodeIdDecodeKeys` leaf stays rejected through the recursion (it needs the decode registry), and a
  developer `@condition` (a `ConditionFilter` / `FkTargetConditionFilter`, not a
  `GeneratedConditionFilter`) is still rejected by the first guard. The switch is **exhaustive over the
  sealed `CallSiteExtraction` (no `default`)**: the remaining five permits are listed as explicit
  `false` arms, so when R384 lifts one (or a new permit is added) the switch fails to compile and forces
  a deliberate decision at this gate. The `lowerParticipantFilters` rejection message and the
  `firstUnsupportedFilterArg` / `isBranchSafeExtraction` docs are reworded to reflect the new boundary.
- The list-typed nested leaf extracts as `(List<X>) map.get(key)`, an inherently unchecked cast since
  `Map.get` is statically `Object` for a value graphql-java has already coerced and validated to
  `List<X>` before the fetcher runs. The unchecked cast (over an element-wise checked copy) is the
  deliberate choice: it is provably correct under graphql-java's input-coercion contract and keeps the
  multitable and single-table paths on one idiom. The "this extraction emits an unchecked cast" fact is
  lifted onto the model as `CallParam.emitsUncheckedCast()` (single source of truth, Generation-thinking);
  both hosts (`MultiTablePolymorphicEmitter`'s two root fetcher methods `buildMainFetcher` /
  `buildRootConnectionFetcher`, and the single-table `QueryConditionsGenerator` method) fold over their
  call params and ask the model rather than each re-deriving `list() && instanceof NestedInputField`, so
  R384 adds its unchecked-emitting arm in one place and neither host can drift.

## Coverage

- Pipeline-tier `MultiTableFilterLoweringTest`: a nested-input `@field` filter lowers per participant
  with a `NestedInputField(filter -> firstNames, Direct)` call param (`nested...WithNestedExtraction`);
  a developer `@condition` on a nested input field is still rejected structural
  (`nestedInputFieldCondition_rejectedStructuralNotDeferred`).
- Execution-tier `MultiTableFilterExecutionTest` (`AddressOccupant = Customer | Staff`): an
  `occupantsByFilter(filter: { firstNames: [...] })` query filters per branch and returns only matching
  rows, and an empty filter narrows by nothing. New sakila fixture: `OccupantFilter` input +
  `Query.occupantsByFilter`. No code-string assertions on generated bodies.

The converted (`JooqConvert` / ID-typed), `@nodeId`-decoded, and developer-`@condition` kinds remain
deferred to **R384**, which carries the registry / FK-target-alias plumbing they genuinely need.

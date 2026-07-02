---
id: R384
title: "Support converted/@nodeId/developer-@condition filters on multitable interface/union queries"
status: In Progress
bucket: bug
priority: 3
theme: interface-union
depends-on: []
created: 2026-06-25
last-updated: 2026-07-02
---

# Support converted/@nodeId/developer-@condition filters on multitable interface/union queries

R363 lowered `@field`-mapped filter inputs onto multitable interface/union root query fields, scoped
day one to the branch-safe argument extractions `Direct`, `EnumValueOf`, and `ContextArg`. R383 then
lifted the **nested-input `@field`** family by admitting a `NestedInputField` whose leaf is itself
branch-safe (the call-site emitter's nested-Map traversal is self-contained: no registry, no lift
locals). The remaining kinds the polymorphic branch emitter
(`MultiTablePolymorphicEmitter.branchFilterWhere`) still cannot emit are the ones whose extraction
genuinely needs plumbing the branch path does not carry, so the classifier
(`FieldBuilder.firstUnsupportedFilterArg` / `isBranchSafeExtraction`) structurally rejects them:

- **`JooqConvert`** (ID-typed / custom-converter columns), whether top-level or as a nested-input
  leaf. Its emission (`ArgCallEmitter` lines 284-288) hands a column-typed Java *value* to the
  developer condition method: scalar `table.COL.getDataType().convert((String) env.getArgument(...))`
  and list `<name>Keys.stream().map(table.COL.getDataType()::convert).toList()`. That
  `DataType.convert(Object)` is `@Deprecated(forRemoval = true)` in jOOQ 3.20, so under
  `graphitron-sakila-example`'s `-Xlint:all -Werror` (deprecation and removal are both errors) the
  first generated source that emits it fails the build. Lifting this needs the shared `<name>Keys`
  local declared once across branches (the env argument is the same for every branch; per-branch
  declaration duplicates the local) *and* the deprecated call replaced at the source by a
  non-deprecated coercion: `DSL.val(rawValue, table.COL.getDataType()).getValue()` (see Design). Note:
  a nested `@field` column is currently
  built with a hardcoded `Direct` leaf in `BuildContext` regardless of GraphQL type, so a nested
  `[ID!] @field` over a plain (non-FK, non-`@nodeId`) column does **not** today route through
  `JooqConvert`; aligning the nested leaf with the top-level conversion semantics is part of this
  item, not a silent pre-existing gap to inherit.
- **`NodeIdDecodeKeys`** (a `@nodeId`-decoded filter arg, top-level or nested-input leaf). Needs the
  `CompositeDecodeHelperRegistry` threaded into the emitter and a home for the drained
  composite-decode helper methods. `CompositeDecodeHelperRegistry.collectInto` brackets one
  construct → thread → drain lifecycle against a single `TypeSpec.Builder`, and the single-table path
  drains onto its one `<Root>Conditions` class. The multitable branch path extracts inline in the
  fetcher body, so the helper home is the `<Type>Fetchers` class hosting the call site: see Design
  below (revised in phase b; the original per-participant-composer wording was not implementable).
- **Developer `@condition` filters** (`ConditionFilter` / `FkTargetConditionFilter`), including a
  nested-input `@condition` that the field/arg-level `hasCondition` guard does not reach. These are
  not `GeneratedConditionFilter`s at all, so `firstUnsupportedFilterArg` rejects them at the first
  guard. Needs the FK-target alias declaration pass (`FkTargetConditionEmitter.declareAliases`) wired
  into the stage-1 union, plus the registry for any composite decode the nested args carry.

## Design

**Why one multi-phase item, not three.** R383 was cleanly carved off because the nested-input
`@field` family needed *zero* new plumbing: a pure classifier relaxation over an extraction-agnostic
condition generator and a self-contained call-site arm. The three kinds here are the opposite: they
are bound together by shared scaffolding. `branchFilterWhere` today calls
`FkTargetConditionEmitter.emitTerm(ctx, filter, alias, null, null, Map.of())` with a `null`
`CompositeDecodeHelperRegistry`, `null` `liftedOuters`, and an empty FK-target-alias map. Widening
those three nulls into real threaded values, and pre-declaring the alias / `<name>Keys` locals before
the inline stage-1 union expression, is one body of work that every lifted kind sits on top of.
Slicing into three roadmap items would force either a ceremonial precursor item or inter-item coupling
("R384c consumes the threading R384b added"); a multi-phase plan keeps the shared seam in **phase 0**
and lets each arm flip independently on top, which is the natural shape the workflow's multi-phase
guidance describes. The classifier gate stays the forcing function it already is: `isBranchSafeExtraction`
is exhaustive over the nine `CallSiteExtraction` permits with no `default`, so each phase that flips one
`false` arm to a branch-safe arm is a compile-forced, self-contained decision, reviewed against the
emitter sites it newly enables (the "audit every consumer in the same commit" rule, per phase).

**Scope is exactly two extraction arms plus the developer-`@condition` guard.** The nine permits are
`Direct`, `EnumValueOf`, `ContextArg`, `JooqConvert`, `NestedInputField`, `NodeIdDecodeKeys`,
`NodeIdDecodeRecord`, `InputBean`, `JooqRecord`. R363/R383 admit the first three plus a branch-safe
`NestedInputField` leaf. This item flips **`JooqConvert`** and **`NodeIdDecodeKeys`** (and, by leaf
recursion, a `NestedInputField` wrapping either), and relaxes the `firstUnsupportedFilterArg` first
guard for developer `@condition`. `NodeIdDecodeRecord`, `InputBean`, and `JooqRecord` stay `false`:
they are mutation-input / record-decode shapes that do not occur as a multitable root-query filter arg,
so they remain explicit `false` arms (the switch keeps forcing a decision if that ever changes).

**Decode-helper home (`NodeIdDecodeKeys`): the `<Type>Fetchers` class hosting the branch call site.**
(Revised in phase b from the original "one registry per participant `<Participant>Conditions`
composer" wording, which misidentified the boundary: `<Participant>Conditions` is the pure-function
composer, contractually free of graphql-java/env concerns, while the multitable branch path does its
env extraction inline in the fetcher body, where the registry's helper calls are emitted unqualified
as private statics of the enclosing class. Draining onto a different class in a different package
cannot compile, and would put wire-decode machinery on the composer side of the adapter/composer
boundary.) The implemented home is the existing per-`<Type>Fetchers` `collectInto` bracket in
`TypeFetcherGenerator`, threaded through `emitMethods` / `emitConnectionMethods` into
`branchFilterWhere`. This matches the true single-table precedent, one registry per class doing env
extraction (`QueryConditions` for the shim layer, `<Type>Fetchers` for the split-rows / lookup-rows
sites), and keeps the registry's "cannot silently forget to emit a registered helper" invariant
intact: one registry, one host builder, hosting the call sites it serves.

**`JooqConvert` deprecation: fix at the source with `DSL.val(...).getValue()`, do not suppress.** The
`JooqConvert` arm produces a column-typed Java *value* for a developer condition-method parameter (the
scalar `getDataType().convert(...)` and the list `.map(getDataType()::convert).toList()` forms above).
Per R267 ("a deprecation-for-removal must be fixed at the source, never suppressed"), the deprecated
`DataType.convert(Object)` is replaced, not wrapped in `@SuppressWarnings`. The non-deprecated
replacement is `DSL.val(rawValue, table.COL.getDataType()).getValue()`:

- `DSL.val(Object, DataType<T>)` returns a `Param<T>` and is not deprecated; `Param.getValue()` returns
  the bare `T`. Both calls are warning-free under `-Xlint:all -Werror`.
- `val` coerces *eagerly* through the column's `DataType` and its registered `Converter`, so the value
  read back is the column's Java type with any custom converter applied. Verified against jOOQ 3.20.11
  that `DSL.val("42", convertedDataType).getValue()` yields the identical result to the deprecated
  `convertedDataType.convert("42")` (a converted domain wrapper, `.equals` true), so this is a
  behaviour-preserving substitution, not an approximation.
- It is already the blessed idiom: the design-principles "Column value binding" section pins
  `DSL.val(rawValue, col.getDataType())` as the two-argument coercion-through-converter form; this arm
  adds `.getValue()` to extract the bare value the condition-method parameter wants (the boundary that
  section calls out as `JooqConvert`'s job), rather than handing jOOQ a `Field`.

This is *not* `Record.fromArray`: that path (R195's input-bean `decode<Type>Record`, R267's
`NodeIdEncoder.decode<Type>`) is record-materialization (positional values onto a record's columns) and
does not fit a bare scalar / `List` of one repeated column. `getConverter().from(...)` was also ruled
out (it takes the database type, not an arbitrary wire `String`), as was `org.jooq.tools.Convert`
(itself `forRemoval`). Because the fix lives in `ArgCallEmitter`'s shared `JooqConvert` arm, it corrects
the single-table path in the same change; no `@SuppressWarnings`, no model predicate, no per-member
stamp is introduced. (The sibling bare-value `convert` sites that still carry class-level suppressions,
`NodeIdEncoder.requireColumnAgreement` and `ConnectionHelper`'s cursor decode, can be retired the same
way; that cleanup is R267 / a sibling item, out of scope here, but it confirms `DSL.val(...).getValue()`
is the general answer.)

Empirically, no generated fetcher / `QueryConditions` class emits a `JooqConvert` `convert` today: a
single-table `ID`-typed `@field` filter is not exercised in `graphitron-sakila-example` (the only
generated `getDataType().convert` sites are `NodeIdEncoder` and `ConnectionHelper`), so swapping the arm
changes no currently-generated output; this item's fixture is the first source to exercise it, and it
exercises the non-deprecated form from the start.

## Implementation

### Phase 0: thread the shared plumbing (behaviorally inert)

Shipped at `367abe2`. (One deviation from the original text: the threaded registry is the enclosing
`<Type>Fetchers` class's, not per-participant; see the revised Design.)

### Phase a: `JooqConvert`

Shipped at `5183f7c` (`DSL.val(...).getValue()` in the shared arm, nested-leaf alignment,
`emitsUncheckedCast` doc/predicate fix, classifier arm flip, pipeline + execution + `-Werror`
compile-pin coverage).

### Phase b: `NodeIdDecodeKeys`

- Registry home as in the revised Design (the `<Type>Fetchers` class hosting the branch call site);
  the threaded registry from phase 0 now actually collects helpers on the `NodeIdDecodeKeys` path.
- Flip the `case CallSiteExtraction.NodeIdDecodeKeys` arm to branch-safe (top-level and as a
  `NestedInputField` leaf, which the recursion already covers once the arm flips).

### Phase c: developer `@condition`

- Relax the `firstUnsupportedFilterArg` first guard so a `ConditionFilter` / `FkTargetConditionFilter`
  is no longer rejected outright; the FK-target alias pass from phase 0 supplies the `EXISTS`
  correlation aliases, and the registry covers any composite decode a nested-input `@condition` carries
  (the field/arg-level `hasCondition` guard does not reach the nested case).

Each phase updates this file (collapse the shipped phase to a one-line "shipped at `<sha>`" note,
keep pending phases); status stays `Ready` while phases remain and flips to `In Review` only when the
last phase is pending review.

## Tests

Each phase earns coverage mirroring R363's `first_name` / R383's nested-input pattern, with **no
code-string assertions on generated bodies** at any tier:

- **Pipeline (`MultiTableFilterLoweringTest`):** the kind's rejection test flips from rejected to
  lowered, asserting the per-participant filter lowers with the expected call-param extraction
  (`JooqConvert` / `NodeIdDecodeKeys` / the `@condition` filter shape). Phase 0 adds no new lowering
  behavior, so it is covered by the existing rejection tests staying red until the relevant phase.
- **Execution (`MultiTableFilterExecutionTest`):** a query filtering each branch over the lifted kind
  returns only matching rows. Candidate fixtures on the existing `AddressOccupant = Customer | Staff`
  union: an ID-typed shared column such as `store_id` on `customer` / `staff` for `JooqConvert`; a
  `@nodeId` filter for `NodeIdDecodeKeys`; a developer `@condition` for phase c. New sakila fixtures
  added per phase as needed, in the `OccupantFilter` / `occupantsByFilter` family R383 introduced.
- **Compilation backstop for phase a's coercion.** The `JooqConvert` fixture is the first generated
  fetcher to emit the coercion, so the `graphitron-sakila-example` `-Xlint:all -Werror` compile *is* the
  pin that the arm uses the non-deprecated `DSL.val(...).getValue()` form: a regression back to
  `DataType.convert(Object)` re-introduces the deprecation/removal warning and fails that compile. The
  execution-tier fixture additionally proves the coercion is behaviour-correct (the converted column's
  filter returns the right rows). No code-string assertion is needed at either tier, per the
  no-body-string rule.

The `NodeIdDecodeRecord` / `InputBean` / `JooqRecord` arms remain rejected after this item; if a future
schema produces one of them as a multitable filter arg, the exhaustive switch forces a fresh decision
at the gate rather than silently rejecting.

## Revision log

**2026-06-27 (Spec → Spec revise, phase-a deprecation handling).** The first Spec → Ready review flagged
that phase a's "reuse `Record.fromArray`" framing conflated record-materialization (R195/R267) with the
`JooqConvert` bare-value coercion, which `fromArray` cannot express. An intermediate revision then
proposed *suppressing* the deprecation (`@SuppressWarnings({"deprecation", "removal"})` at the emitting
member). That was wrong and is retracted: it contradicts R267's standing rule that a deprecation-for-removal
must be fixed at the source, never suppressed; silencing a `forRemoval` warning removes the only signal
before jOOQ deletes the method. The replacement, verified against jOOQ 3.20.11, is
`DSL.val(rawValue, col.getDataType()).getValue()`: non-deprecated, eagerly coerces through the column's
registered converter (`.equals`-identical to the deprecated `convert` on a converted domain type), and
yields the bare value the condition method wants. `getConverter().from` (takes the database type, not a
wire `String`) and `org.jooq.tools.Convert` (itself `forRemoval`) were ruled out. The fix lives in
`ArgCallEmitter`'s shared arm, so it corrects the single-table path too and needs no suppression, model
predicate, or per-member stamp. Design and phase a were rewritten accordingly. The next Spec → Ready
sign-off must come from a session distinct from both the original author and the reviewer who landed this
revision.

**2026-07-02 (Spec → Ready re-review, sign-off recorded).** Re-review by a session distinct from both the
original author and the revising reviewer, closing the sign-off this item's status flip was missing: the
front-matter went `Spec` → `Ready` inside the unrelated R370 commit (`21ed3cf`, a third session) with no
review recorded, so this entry supplies the missing gate record. Verified on live code: every symbol, path,
and line citation still holds on current trunk (`isBranchSafeExtraction`'s exhaustive nine-permit switch,
`branchFilterWhere`'s `emitTerm(ctx, filter, alias, null, null, Map.of())` seam, `ArgCallEmitter` lines
284-288, the `QueryConditionsGenerator` 144-153 pre-lift, `CallParam.emitsUncheckedCast`'s stale R384
forward-reference, `BuildContext`'s hardcoded `Direct` nested leaf, `FkTargetConditionEmitter.declareAliases`,
one-registry-per-class `collectInto` precedents, the `AddressOccupant` fixture family, and the R267 rule
text). The revised phase-a design was re-verified empirically against jOOQ 3.20.11: on a converted domain
type, `DSL.val("42", convertedType).getValue()` is `.equals`-identical to the deprecated
`convertedType.convert("42")`, the list and null cases behave (null in, null out), and under
`-Xlint:all -Werror` the deprecated form fails with `[removal]` while the `DSL.val` form compiles
warning-free, confirming both the substitution and the compilation-backstop claim. One reviewer note, not
blocking: `MultiTableFilterLoweringTest` has rejection tests for `JooqConvert` (`idTypedFilter_...`) and
developer `@condition` (three cases) but none for `NodeIdDecodeKeys`, so phase b adds a new
rejection-to-lowered test rather than flipping an existing one; the Tests section's "the kind's rejection
test flips" reads loosely for that phase. Status stays `Ready`.

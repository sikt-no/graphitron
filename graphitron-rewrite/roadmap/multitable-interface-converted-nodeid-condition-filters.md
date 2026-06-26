---
id: R384
title: "Support converted/@nodeId/developer-@condition filters on multitable interface/union queries"
status: Spec
bucket: bug
priority: 3
theme: interface-union
depends-on: []
created: 2026-06-25
last-updated: 2026-06-26
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
  `DataType.convert(Object)` is deprecated-for-removal in jOOQ 3.20, so under
  `graphitron-sakila-example`'s `-Xlint:all -Werror` (deprecation and removal are both errors) a
  fixture exercising it fails the build. Lifting this needs the shared `<name>Keys` local declared
  once across branches (the env argument is the same for every branch; per-branch declaration
  duplicates the local) *and* a decision on the deprecation. The deprecation is handled by
  suppression, not by `Record.fromArray`: see Design (`JooqConvert` is a bare-value coercion, a
  different shape from the record-building `fromArray` sites). Note: a nested
  `@field` column is currently
  built with a hardcoded `Direct` leaf in `BuildContext` regardless of GraphQL type, so a nested
  `[ID!] @field` over a plain (non-FK, non-`@nodeId`) column does **not** today route through
  `JooqConvert`; aligning the nested leaf with the top-level conversion semantics is part of this
  item, not a silent pre-existing gap to inherit.
- **`NodeIdDecodeKeys`** (a `@nodeId`-decoded filter arg, top-level or nested-input leaf). Needs the
  `CompositeDecodeHelperRegistry` threaded into the emitter and a home for the drained
  composite-decode helper methods. `CompositeDecodeHelperRegistry.collectInto` brackets one
  construct → thread → drain lifecycle against a single `TypeSpec.Builder`, and the single-table path
  drains onto its one `<Root>Conditions` class. The polymorphic path emits a `<Participant>Conditions`
  composer per participant, so the helper home is a real decision, not a typo: see Design below
  (one registry per participant composer, mirroring the single-table one-registry-per-class precedent).
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

**Decode-helper home (`NodeIdDecodeKeys`).** One `CompositeDecodeHelperRegistry` per participant
`<Participant>Conditions` composer, each its own `collectInto` construct → thread → drain bracket. This
mirrors the single-table precedent (one registry per emitted `<Root>Conditions` class) and keeps the
registry's "cannot silently forget to emit a registered helper" invariant intact: a registry maps to
exactly one host builder, never to one-of-N. A participant's decode helpers land on that participant's
composer, alongside the participant-named condition method its filters already lower to.

**`JooqConvert` deprecation: suppress at the emitting member, do not convert it away.** The
`JooqConvert` arm produces a column-typed Java *value* for a developer condition-method parameter (the
scalar `getDataType().convert(...)` and the list `.map(getDataType()::convert).toList()` forms above).
This is a *bare-value* coercion, structurally unlike the *record-building* sites R195 moved to
`Record.fromArray(Object[], Field<?>...)`: `fromArray` loads positional values onto a record's columns
and coerces as a side effect of populating the record (R195's input-bean `decode<Type>Record`, R267's
`NodeIdEncoder.decode<Type>` which builds a `RecordN` via `rec.set`). A single coerced value is not a
record, and a list of ids for one column is not N columns of one record, so `fromArray` does not express
either `JooqConvert` form. There is also no clean non-deprecated public `Object → T` coercion to
substitute: the codebase already recorded this finding at `NodeIdEncoderClassGenerator` (the comment
above its class-level suppression notes that the recommended `Field.getConverter().from` does not accept
`Object` input and `org.jooq.tools.Convert` is itself marked for removal). `JooqConvert` therefore
belongs with the other *bare-value* `convert` sites, `NodeIdEncoder.requireColumnAgreement` and
`ConnectionHelper`'s cursor decode, both of which keep `getDataType().convert(...)` under a
`@SuppressWarnings({"deprecation", "removal"})` (class-level on those helper classes). This item does the
same, scoped to the *narrowest enclosing member* (the generated fetcher method), driven by a model
single-source-of-truth predicate so the single-table and multitable hosts cannot drift, exactly as
`CallParam.emitsUncheckedCast()` already does for the `unchecked` category (Phase a). A throwaway-record
`fromArray` round-trip (build a one-element record, read the coerced value back) was considered and
rejected: it allocates per value, needs a per-element form for lists, and reads worse than the one-line
`convert` call the generated code already emits, against "Generated code is read and debugged"; it would
also diverge from the two sibling bare-value sites for no gain while jOOQ ships no public successor.

Empirically, no generated fetcher / `QueryConditions` class emits a `JooqConvert` `convert` today: a
single-table `ID`-typed `@field` filter is not exercised in `graphitron-sakila-example`, so the only
generated `getDataType().convert` sites are `NodeIdEncoder` and `ConnectionHelper` (both already
suppressed). The `-Werror` trip is therefore latent, and this item's fixture is the first generated
source to hit it; the same model predicate also closes the latent single-table gap when such a filter is
eventually added.

## Implementation

### Phase 0: thread the shared plumbing (behaviorally inert)

Widen the branch path to carry what the lifted kinds need, **without flipping any classifier arm**, so
the existing pipeline/execution tests stay green and prove the threading is inert:

- `MultiTablePolymorphicEmitter`: thread a per-participant `CompositeDecodeHelperRegistry` (and its
  drain) through `emitMethods` / `emitConnectionMethods` → `buildStage1Block` /
  `buildStage1ConnectionBlock` → `branchFilterWhere`, and call `FkTargetConditionEmitter.declareAliases`
  for each participant's filters, emitting the alias declarations as statements **before** the inline
  union expression begins (the union branches read the resulting alias map). Pre-declare the shared
  `<name>Keys` local once per arg name (deduped), matching the single-table
  `QueryConditionsGenerator` pre-lift at lines 144-153. Pass the threaded `registry` /
  `liftedOuters` / `fkTargetAliases` into `emitTerm` in place of `null, null, Map.of()`.

### Phase a: `JooqConvert`

- Keep `ArgCallEmitter`'s `JooqConvert` arm as is (it already emits the scalar / list `convert` forms);
  the deprecation is handled by suppression, not by rewriting the expression (see Design). Add a model
  single-source-of-truth predicate `CallParam.emitsDeprecatedConversion()`, sibling to
  `emitsUncheckedCast()`, returning true for a top-level `JooqConvert` and for a `NestedInputField`
  whose `leaf` is `JooqConvert` (recurse into the leaf, as `isBranchSafeExtraction` does). Both hosts
  fold over their `CallParam`s and ask this predicate rather than re-deriving the `instanceof JooqConvert`
  test.
- Stamp `@SuppressWarnings({"deprecation", "removal"})` at the narrowest enclosing fetcher member when
  any participant filter carries such a param. `MultiTablePolymorphicEmitter` already has
  `stampUncheckedSuppressionIfNeeded`; generalise the suppression so a method needing both `unchecked`
  (R383 list `NestedInputField`) and `deprecation`/`removal` (this arm) emits a **single** merged
  `@SuppressWarnings({...})` (Java rejects two `@SuppressWarnings` on one element), collecting the union
  of categories from the two model predicates. Apply the same predicate-driven stamp in
  `QueryConditionsGenerator`, which closes the latent single-table gap at no behavioural cost (single-table
  `JooqConvert` is unexercised today, so nothing is emitted differently until such a filter exists).
- Align the nested `@field` leaf with top-level conversion semantics so a nested `[ID!] @field` over a
  converted column routes through `JooqConvert` rather than the hardcoded `Direct` leaf `BuildContext`
  builds today.
- Fix the stale forward-reference in `CallParam.emitsUncheckedCast()`'s javadoc: it speculates that
  "R384's `JooqConvert` lift" will start emitting an *unchecked cast* and gain an arm there. It does not;
  the scalar `(String)` cast is checked and the list arm carries no cast (the `<name>Keys` local is a
  plain generic assignment), so `JooqConvert`'s warning is `deprecation`, not `unchecked`. Redirect the
  comment to the new `emitsDeprecatedConversion()` predicate so the doc names the live mechanism
  ("Documentation names only live tests/code").
- Flip the `case CallSiteExtraction.JooqConvert` arm in `FieldBuilder.isBranchSafeExtraction` to
  branch-safe.

### Phase b: `NodeIdDecodeKeys`

- Per-participant registry home as in Design; the threaded registry from phase 0 now actually collects
  helpers on the `NodeIdDecodeKeys` path.
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
- **Compilation backstop for phase a's suppression.** The `JooqConvert` fixture is the first generated
  fetcher to emit `getDataType().convert(...)`, so the `graphitron-sakila-example` `-Xlint:all -Werror`
  compile *is* the pin that the merged `@SuppressWarnings({"deprecation", "removal"})` is emitted at the
  right member: if phase a omits or misplaces the stamp, the deprecation/removal warning fails that
  compile. No code-string assertion on the annotation is needed (and none is added, per the no-body-string
  rule); the warning-as-error build is the live check.

The `NodeIdDecodeRecord` / `InputBean` / `JooqRecord` arms remain rejected after this item; if a future
schema produces one of them as a multitable filter arg, the exhaustive switch forces a fresh decision
at the gate rather than silently rejecting.

## Revision log

**2026-06-26 (Spec → Spec revise).** First Spec → Ready review flagged that phase a's "reuse
`Record.fromArray`" framing conflated two jOOQ operations: `fromArray` is record-*materialization*
(positional values → record columns, what R195 and R267 do), whereas the `JooqConvert` arm is a
bare-value coercion for a condition-method parameter, which `fromArray` cannot express. The codebase had
already established (at `NodeIdEncoderClassGenerator`) that no clean non-deprecated public `Object → T`
coercion exists, and that the standing answer for bare-value `convert` sites is a localized
`@SuppressWarnings({"deprecation", "removal"})`. Phase a was rewritten accordingly: keep the existing
`convert` expression, add the `CallParam.emitsDeprecatedConversion()` single-source-of-truth predicate,
stamp a single merged suppression at the narrowest enclosing member in both hosts, and lean on the
`graphitron-sakila-example` `-Werror` compile as the backstop. The Design section now records the
suppress-not-convert decision and the empirical finding that single-table `JooqConvert` is unexercised
(so the `-Werror` trip is latent and this item's fixture is the first to hit it). The next Spec → Ready
sign-off must come from a session distinct from both the original author and the reviewer who landed this
revision.

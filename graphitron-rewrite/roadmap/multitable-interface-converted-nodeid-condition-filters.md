---
id: R384
title: "Support converted/@nodeId/developer-@condition filters on multitable interface/union queries"
status: Backlog
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
  leaf. Its emission routes through a `DataType.convert(Object)` call that is deprecated-for-removal
  in jOOQ 3.20 and trips the consumer `-Werror` (`graphitron-sakila-example` compiles generated
  output with warnings-as-errors). Lifting this needs a non-deprecated conversion path *and* the
  shared `<name>Keys` local declared once across branches (the env argument is the same for every
  branch; per-branch declaration duplicates the local). Note: a nested `@field` column is currently
  built with a hardcoded `Direct` leaf in `BuildContext` regardless of GraphQL type, so a nested
  `[ID!] @field` over a plain (non-FK, non-`@nodeId`) column does **not** today route through
  `JooqConvert`; aligning the nested leaf with the top-level conversion semantics is part of this
  item, not a silent pre-existing gap to inherit.
- **`NodeIdDecodeKeys`** (a `@nodeId`-decoded filter arg, top-level or nested-input leaf). Needs the
  `CompositeDecodeHelperRegistry` threaded into the emitter and a home for the drained
  composite-decode helper methods on the per-participant `<Participant>Conditions` composer (the
  single-table path drains onto `<Root>Conditions`).
- **Developer `@condition` filters** (`ConditionFilter` / `FkTargetConditionFilter`), including a
  nested-input `@condition` that the field/arg-level `hasCondition` guard does not reach. These are
  not `GeneratedConditionFilter`s at all, so `firstUnsupportedFilterArg` rejects them at the first
  guard. Needs the FK-target alias declaration pass (`FkTargetConditionEmitter.declareAliases`) wired
  into the stage-1 union, plus the registry for any composite decode the nested args carry.

Scope: thread the registry through `emitMethods` / `emitConnectionMethods` →
`buildStage1Block` / `buildStage1ConnectionBlock` → `branchFilterWhere`; re-introduce the FK-target
alias declaration and `<name>Keys` local pre-declaration (declared once, deduped by arg name) before
the union; resolve the per-participant composer's decode-helper destination; and replace the
deprecated `DataType.convert` emission with a non-deprecated conversion path (shared with the nested
leaf). Then relax `FieldBuilder.isBranchSafeExtraction` / `firstUnsupportedFilterArg` to admit the
newly-supported kinds. Each lifted kind earns a pipeline + execution test mirroring R363's
`first_name` coverage (e.g. an ID-typed shared column such as `store_id` on `customer` / `staff`), and
the corresponding rejection test in `MultiTableFilterLoweringTest` flips from rejected to lowered.

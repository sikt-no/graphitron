---
id: R383
title: "Support converted/@nodeId/nested-input filters on multitable interface/union queries"
status: Backlog
bucket: bug
priority: 3
theme: interface-union
depends-on: []
created: 2026-06-25
last-updated: 2026-06-25
---

# Support converted/@nodeId/nested-input filters on multitable interface/union queries

R363 lowered `@field`-mapped filter inputs onto multitable interface/union root query fields, but
scoped day one to the branch-safe argument extractions: `Direct`, `EnumValueOf`, and `ContextArg`.
The polymorphic branch emitter (`MultiTablePolymorphicEmitter.branchFilterWhere`) reuses the
single-table condition-term emission (`FkTargetConditionEmitter.emitTerm`) without a
`CompositeDecodeHelperRegistry`, a nested-input lift context, or pre-declared extraction locals, so
the classifier (`FieldBuilder.firstUnsupportedFilterArg`) structurally rejects the remaining kinds:

- **`JooqConvert`** (ID-typed / custom-converter columns). Its emission routes through a
  `DataType.convert(Object)` call that is deprecated-for-removal in jOOQ 3.20 and trips the consumer
  `-Werror` (`graphitron-sakila-example` compiles generated output with warnings-as-errors). Lifting
  this needs a non-deprecated conversion path *and* the shared `<name>Keys` local declared once across
  branches (the env argument is the same for every branch; per-branch declaration duplicates the local).
- **`NodeIdDecodeKeys`** (a `@nodeId`-decoded filter arg). Needs the `CompositeDecodeHelperRegistry`
  threaded into the emitter and a home for the drained composite-decode helper methods on the
  per-participant `<Participant>Conditions` composer (the single-table path drains onto `<Root>Conditions`).
- **Nested-input and developer `@condition` filters** (`ConditionFilter` / `FkTargetConditionFilter`),
  including a nested-input `@condition` that the field/arg-level `hasCondition` guard does not reach.
  Needs the FK-target alias declaration pass (`FkTargetConditionEmitter.declareAliases`) wired into the
  stage-1 union, plus the registry for any composite decode the nested args carry.

Scope: thread the registry through `emitMethods` / `emitConnectionMethods` →
`buildStage1Block` / `buildStage1ConnectionBlock` → `branchFilterWhere`; re-introduce the FK-target
alias declaration and `<name>Keys` local pre-declaration (declared once, deduped by arg name) before
the union; resolve the per-participant composer's decode-helper destination; and replace the
deprecated `DataType.convert` emission. Then relax `FieldBuilder.firstUnsupportedFilterArg` to admit
the newly-supported kinds. Each lifted kind earns a pipeline + execution test mirroring R363's
`first_name` coverage (e.g. an ID-typed shared column such as `store_id` on `customer` / `staff`).


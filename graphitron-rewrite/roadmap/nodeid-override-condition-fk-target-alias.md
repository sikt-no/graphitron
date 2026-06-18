---
id: R330
title: "@condition(override: true) on a @nodeId filter field passes the root table instead of the joined FK-target alias"
status: Backlog
bucket: bug
priority: 3
theme: nodeid
depends-on: []
created: 2026-06-18
last-updated: 2026-06-18
---

# @condition(override: true) on a @nodeId filter field passes the root table instead of the joined FK-target alias

When a filter input field carries both `@nodeId(typeName: "X")` and `@condition(override: true)`, the generated override-condition method receives the parent's root-table local (`table`) as its implicit first (`ParamSource.Table`) argument, instead of an alias for the FK-target table `X` that the method signature expects. The custom method `iRegelverksamling(Regelverksamling rs, ...)` on a `@table(name: "soknadsmangeltype")` type is handed the `Soknadsmangeltype` root table, so generated source fails to compile (`incompatible types: Soknadsmangeltype cannot be converted to Regelverksamling`). This is a regression from pre-RC16 behaviour, where Graphitron joined the FK `soknadsmangeltype -> regelverksamling` and passed the joined `regelverksamling` alias. Plain `@condition(override: true)` without `@nodeId` is unaffected (its first arg genuinely is the field's own table); the breakage is specific to the `@nodeId` + override combination.

## Root cause (diagnosis, not yet a plan)

The FK-target `@nodeId` rewrite (the `DirectFk` + `liftedSourceColumns` model, landed across R131/R189/R312/R315) binds decoded keys directly against lifted columns on the field's *own* table with no JOIN. That is correct for the implicit nodeId predicate but never propagates the join (`joinPath` / target `TableRef`) into the `@condition` method's `ParamSource.Table` slot.

- `model/ConditionFilter.java` + `model/ParamSource.java`: `ParamSource.Table` has no notion of which table beyond "the field's target table"; the `ConditionFilter` produced by `BuildContext.buildInputFieldCondition` (`BuildContext.java:1735-1765`, via `reflectTableMethod(..., TableSlotPolicy.REQUIRED, ...)`) carries className/method/params only, no join reference.
- `BuildContext.inputFieldFromNodeIdResolved` (`BuildContext.java:2097-2110`) stitches the `DirectFk` join path and the condition onto an `InputField.ColumnReferenceField` as independent slots; the condition's `Table` slot is never reconciled with `direct.joinPath()` / target table.
- `FieldBuilder.walkInputFieldConditions` (`FieldBuilder.java:1557-1570`, `ColumnReferenceField` arm): under `enclosingOverride` the implicit predicate that *would* use `rf.liftedSourceColumns()` / join path is suppressed (guard at line 1559), so the join information is dropped for the field; only the condition (rewrapped for nested Arg params, Table slot untouched) survives.
- `QueryConditionsGenerator.buildConditionMethod` (`QueryConditionsGenerator.java:179` and `:185`): the source alias is the hard-coded literal `"table"`, passed to `ArgCallEmitter.buildCallArgs` (`ArgCallEmitter.java:66-75`) as the `ParamSource.Table` first argument for *every* condition method. The emitter never has the FK-target alias in scope.

## Fix shape (to be specified)

A fix needs to (a) carry the nodeId-target table/joinPath onto the condition (or a `Table`-source variant distinguishing root from FK-target alias), (b) emit the FK JOIN to the target table in the conditions/fetcher scope, and (c) pass the joined alias rather than `"table"` for that filter's call. Related deferred JOIN-emission tracking lives in R24 (`NodeIdReferenceField` JOIN-projection form); scope overlap to be settled at Spec.

## Coverage gap

`NodeIdReferenceFilterPipelineTest` puts `@nodeId` and `@condition` on *separate* fields and only asserts the decode helper is lifted + generation does not throw; no test exercises both directives on one field, nor asserts the alias bound to an override method's first argument. A falsifiable test asserting the FK-target alias (not the root table) reaches the override call is the acceptance check.

## Reported instances

Two real consumer instances (both `incompatible types` compile failures at Graphitron 10.0.0-RC16): `SoknadsmangeltypeFilterInput.regelverksamlingId` -> `iRegelverksamling(Regelverksamling, ...)`, and `EndringsloggV2FilterInput.brukerId` -> `harBruker(Bruker, ...)`.

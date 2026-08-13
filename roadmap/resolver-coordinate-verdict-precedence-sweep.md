---
id: R654
title: "Coordinate-level verdicts masked by reflection and binding rejections outside the @service seat"
status: Backlog
bucket: bug
priority: 4
theme: diagnostics
depends-on: []
created: 2026-08-13
last-updated: 2026-08-13
---

# Coordinate-level verdicts masked by reflection and binding rejections outside the @service seat

The defect class R649 fixes at the `@service` seat (a reflection or parameter-binding rejection short-circuits ahead of a coordinate-level verdict living downstream in the same classifier, so a problem that belongs to the coordinate is reported as a problem with the author's Java signature) recurs at other seats. R649's survey of the resolver family found these instances, which that item deliberately left in place:

* **`@externalField`.** The `@reference`-path deferral ("condition-join lift form is not yet supported") lives in `GraphitronSchemaValidator.validateComputedField` and only runs on a successfully classified `ComputedField`, so every `reflectExternalField` signature rejection (arity, non-`Table` parameter, non-`Field` return, parent-record mismatch) masks it. An author whose field carries `@reference` gets told to fix a signature that cannot help.
* **`@sourceRow`.** `SourceRowDirectiveResolver.resolve` runs its reflection step (lifter arity, parameter assignability, `RowN` return) before its derivation step, and the derivation carries coordinate-level rejections: leaf target table with no primary key, `@reference` first hop resolving to a condition join. A lifter with a wrong return type on a PK-less target is answered about the return type.
* **`@condition` + `@lookupKey`.** `FieldBuilder.projectForFilter` returns on the `ConditionResolver` reflection rejection ahead of the "@lookupKey is declared but no argument resolved to a lookup column" verdict, so a misnamed condition parameter masks the lookup verdict.
* **Root `@service` residue.** R649 hoists the coordinate verdicts inside `ServiceDirectiveResolver.resolve` but leaves the root polymorphic narrowing (the union-return rejection, the single-table-interface deferral) and the mutation payload checks (orphan service carrier, `$source` sigil rules) at the `FieldBuilder` query/mutation arms, downstream of binding, where a binding rejection still wins.

`RoutineDirectiveResolver` already orders every coordinate verdict ahead of `bindArgs` and is the reference shape for ordering. Note it still spells its coordinate as a bare `boolean isRoot`; R649 deliberately nested its sealed `ParentContext` inside `ServiceDirectiveResolver` rather than sharing it (the routine seat has two coordinates and no masking defect), so treat any unification as a per-seat judgment call, not a defect R649 forgot. The fix per seat is the same move R649 makes: decide (or at least consult) what the classifier knows about the coordinate before a signature or binding mismatch becomes the reported failure. Whether each seat wants R649's full decode/classify/bind split or a simple reorder is per-seat judgment; `@sourceRow`'s steps are already pure and adjacent, while `@externalField`'s verdict may just need to move from the validator into the classifier (with the validator keeping the mirror, per "Validator mirrors classifier invariants").

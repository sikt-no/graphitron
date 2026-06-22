---
id: R337
title: "Input-side nesting-projection classification (NestingType mirror)"
status: Backlog
bucket: architecture
priority: 4
theme: model-cleanup
depends-on: [field-relative-input-classification]
created: 2026-06-19
last-updated: 2026-06-22
---

# Input-side nesting-projection classification (NestingType mirror)

**Deferred in favor of R327 (`field-relative-input-classification`).** This item proposed to fix the null-backed `PojoInputType` mislabel for nested grouping inputs by *adding* a new per-type `GraphitronType` variant (an input mirror of the output `NestingType`). R327's 2026-06-20 reframing claims the same artifact from the opposite direction and supersedes that mechanism, so R337 is parked as a redirect rather than spec'd. Revive it only for the narrow residual below, and only if R327 lands without covering it.

## The artifact (unchanged, still real)

A directiveless SDL `input` type nested under a table-bound parent (a `@table` input, or a jOOQ-record `@service` param via the R336 flatten, now Done) is semantically a *projection of columns on the parent's table*: its fields resolve against the parent's `TableRef`, it has no table of its own and no Java backing. Today it classifies as `GraphitronType.PojoInputType` with `fqClassName = null` (the `bindings.resolveInput(name)`-empty branch in `TypeBuilder.buildNonTableInputType`). Calling a column-grouping projection a "POJO" is a misnomer that leaks a reflection fallback into the model and the LSP (hover + inlay).

## Why deferred, not spec'd

R337's plan was a *type-level* relabel: introduce a new variant and assign it instead of `PojoInput(null)`. The relabel is codegen-neutral, the nested grouping's record class still emits via `HasInputRecordShape`, and both flatten paths (the `@table`-input `classifyInputField` recursion and the R336 `InputBeanResolver` recursion) read the raw graphql-java `Map`, never the typed record, so the work really is just "add a variant + the two exhaustive `GraphitronType` switch arms (`projectTypeClassification`, `projectType`) + an LSP label/hover + tests."

But R327 (Spec, reopened and reframed 2026-06-20, the day after this item was filed) settles that input classification is **contextual**, a function of the consuming field/coordinate, not a global property of the type, and names the null-backed `PojoInputType` explicitly as **"the bug"**: it "exists only because `buildInputType` is asked to classify globally with no consumer in view. Per-coordinate, it cannot occur." R327's fix is to *dissolve* the per-type verdict (routed through R333's coordinate-lowering), not to add another per-type label. R337's mechanism is the exact type-level altitude R327 diagnoses as wrong, and R327 records a withdrawn attempt that failed because "the type-level demotion throws away context the coordinate already has." Adding R337's variant now is model surface (a `GraphitronType` permit, a `TypeClassification` permit, `HasInputRecordShape` / `InputType` membership, LSP labels, pinned tests) that R327 / R333 would then have to remove: negative work against the agreed direction.

## Residual this redirect guards

R327's reframing is scoped to query-filter binding, `@table`-on-input retirement, and the mutation write-target axis; it does not *explicitly* name the honest **surfacing** of a nested-grouping projection (the LSP hover / inlay and the `TypeClassification` label that today reads `PojoInput`). If R327 / R333 land their per-coordinate model but leave that surfacing on the null-`PojoInput` label, revive R337 narrowly as "surface the projection honestly on the lowered coordinate," with no new per-type variant. Until then this file is a redirect.

## Disposition

Backlog tombstone (per `workflow.adoc`): kept as a redirect during R327's lifecycle, `depends-on` R327. Delete this file when R327 reaches Done if the surfacing residual is subsumed there; otherwise re-scope to the residual only at that point. Out of scope regardless: the functional flatten (R336, Done), how nested fields resolve to columns, and the output-side `NestingType`.

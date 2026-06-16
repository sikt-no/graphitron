---
id: R317
title: "Inline type classification into the field-first walk (retire TypeBuilder.buildTypes)"
status: Spec
bucket: architecture
priority: 4
theme: structural-refactor
depends-on: [field-first-classification-driver]
created: 2026-06-16
last-updated: 2026-06-16
---

# Inline type classification into the field-first walk (retire TypeBuilder.buildTypes)

## Problem

R279 made classification field-first and reachability-pruned and collapsed `TypeRegistry` to one
`register` verb, but it stopped short of the single walk its own Direction describes. The reachable
surface is still traversed three times:

1. `SchemaReachability.reachableTypeNames` runs a `SchemaTraverser` and hands back a materialised
   `Set<String>` of reachable output-composite names.
2. `TypeBuilder.buildTypes` loops that set, classifying each type, then runs its deferred-classification
   passes: carrier binding (`promoteSingleRecordPayloads`), participant enrichment,
   `surfaceMultiProducerRejections`, `emitDirectiveIgnoredWarning`.
3. `GraphitronSchemaBuilder.buildSchema` loops the same set again, classifying each object's fields.

The set is threaded across two classes as a hand-off (`TypeBuilder.reachableOutputTypes`). The walk
exists only to compute a set; the verdicts are then produced by two flat loops over it. `buildTypes`
is, in the R279 model corrections' own words, "transitional scaffolding, not part of the model."

## Goal

One walk classifies everything. The `SchemaTraverser` that today only computes a reachable set instead
carries a classifying visitor: it classifies each type as the edge reaches it and each field as it
visits it, and `buildTypes` is deleted. There is no second classification pass. This is R279's stated
model ("each field visit classifies the field and, as a byproduct, registers a classification for the
field's target type") carried to its end state. Output is byte-identical; the prerequisites are already
paid (order-independent `participantClassification` from slice 3a, the commutative single-verb
`register` from slice 6).

One walk for classification does not erase R279's classify then validate then emit phases. The global
soundness checks listed below are *validation*, a later phase, not a second classification pass.
Keeping them after the walk is the phase boundary working as designed, not the multiphase smell R279
set out to kill.

Verdicts stay order-independent. Every verdict still reads only node SDL, reflection, and downward
context, so it is identical regardless of visit order. "Register a type at the edge that reaches it
before classifying its fields" is a local data dependency the walk's own shape satisfies, not a verdict
that changes with order; it is precisely R279's model, not a regression toward strict-ordered phasing.

## What folds into the walk

These are deferred *classification*. Each reads a fixed point that exists before the walk (the
`RecordBindingResolver.resolveAll` bindings) or is a pure function of SDL plus reflection, so none needs
a separate pass; each becomes work done at the node the walk is already visiting:

- *Participant enrichment (interface / union).* Slice 3a made `participantClassification` a pure
  SDL+reflection function with no registry read, and the walk already fans out to implementors and
  members. An interface or union is classified with its participants when reached. This computes
  participants earlier; it does not change *what* a participant is, so it stays clear of R278 (which
  owns the `ParticipantRef` primitive).
- *Multi-producer rejection.* `surfaceMultiProducerRejections` reads `bindings.rejection(name)`, a
  fixed point. Register the `UnclassifiedType` when the rejected type is reached.
- *Directive-ignored warning.* Reads bindings plus SDL, a fixed point. The only reason it is a
  separate SDL-order pass today is to keep warning emission order stable, a presentation concern
  (collect and emit in a deterministic order), not a reason to keep a classification pass.
- *Single-record carrier binding (`promoteSingleRecordPayloads`).* The producer to carrier to table
  relationship is already a fixed point in `RecordBindingResolver`, so a carrier's
  `JooqTableRecordType` verdict is computable at the node, not in a post-pass. This is the one with a
  real dependency to resolve (next section).

## What stays after the walk (validation, not classification)

The global soundness reductions need the *complete* classified set to detect a cross-type conflict; a
per-edge visit structurally cannot: `validateNodeTypeIdUniqueness`, `rejectCaseInsensitiveTypeCollisions`,
`rejectDanglingTypeReferences`, `collectDomainReturnTypeConflicts`, `EntityResolutionBuilder`,
`MappingsConstantNameDedup`. These are reductions over the finished registry and belong to the validate
phase; R279 slice 4 already moved the `domainReturnType` agreement check to the validator on exactly
this reasoning. R317 leaves them where they sit. Migrating any of them further into the validator is a
separate concern and not in scope. They are not a classification pass, so they do not contradict the
goal.

## The one real dependency to resolve

`promoteSingleRecordPayloads` runs today "after the second pass so the scan sees the fully-classified
element-type registry": `scanStructuralDmlPayload` reads the classifications of a carrier's *element*
types, which are the carrier's own children in the walk. Folding it in therefore needs the carrier
classified from verdicts that exist only after descending into it. This is the substance of the item,
and it is local and solvable, not a global pre-order invariant. Two routes to settle during
implementation:

- *Finalise carriers on walk-leave (post-order).* Normal types classify on enter (before their
  fields); a carrier finalises on leave, once its children are classified. The dependency is on the
  carrier's own subtree, which the walk has by then visited.
- *Lean on the binding fixed point.* `RecordBindingResolver` already knows the carrier's table, so the
  `JooqTableRecordType` verdict needs no descent; the scan's registry read is then re-expressed as the
  validation guard it really is and moves to the validate phase with the other reductions.

Pick whichever keeps the diff byte-identical with less coupling; the post-order route is the smaller
change, the fixed-point route is the cleaner end state.

## Slicing

Risk is purely reordering (behaviour byte-identical), so the slices isolate reorderings; no classifier
invariant is retired, so the validator-mirror rule is satisfied trivially.

1. **Fold the fixed-point classification into the walk.** Move participant enrichment, multi-producer
   rejection, the directive-ignored warning, and the no-descent part of carrier binding onto the node
   visit, so the traversal classifies every type and field in one pass. The reachable set may stay
   materialised through this slice. Global validation reductions untouched. Gate: truth table + sakila,
   byte-identical.
2. **Resolve the carrier scan dependency and delete `buildTypes`.** Land the post-order (or
   fixed-point) carrier classification, retire `TypeBuilder.buildTypes` and the `reachableOutputTypes`
   cross-class hand-off, and make the `SchemaTraverser` visitor the sole classifier with only the
   validation reductions after it. Gate: truth table + sakila, byte-identical.

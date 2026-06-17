---
id: R317
title: "Single edge-driven classification pass and immutable validation (retire TypeBuilder.buildTypes)"
status: Spec
bucket: architecture
priority: 4
theme: structural-refactor
depends-on: []
created: 2026-06-16
last-updated: 2026-06-17
---

# Single edge-driven classification pass and immutable validation (retire TypeBuilder.buildTypes)

> Rescoped 2026-06-17, mid-implementation. The original plan framed this as a byte-identical
> reordering that moved the existing passes into the walk. Investigation (see "Why the original
> framing could not reach the goal") showed that framing cannot land the single walk: the walk
> requires `classifyType` to become edge-complete and the reverse-index registry scans to become
> fixed points, and a genuinely simple end state also requires the validate phase to stop mutating
> classification. R318 (immutable validate phase) is inlined here as the closing slice; R319
> (warn on pruned unreachable types) stays separate.

## Problem

R279 made classification field-first and reachability-pruned and collapsed `TypeRegistry` to one
`register` verb, but the reachable surface is still traversed three times: `SchemaReachability`
computes a `Set<String>`; `TypeBuilder.buildTypes` loops it classifying each type and then runs its
deferred passes; `GraphitronSchemaBuilder.buildSchema` loops it again classifying fields. The set is
threaded across two classes as a hand-off (`TypeBuilder.reachableOutputTypes`). The goal is one walk
that classifies as it goes, with `buildTypes` deleted.

Two structural facts block that, and a third keeps the validate phase from being clean:

1. **`classifyType` is a standalone, own-directive classifier that punts on directiveless objects.**
   For a directiveless object it returns `null` (TypeBuilder.classifyType), an open admission that
   it cannot decide the type standalone, because what such an object *is* depends on the edge that
   reaches it. The real verdict is then scattered across three later passes keyed on edge context
   discovered after `classifyType` ran: `promoteSingleRecordPayloads` (a directiveless carrier
   becomes `JooqTableRecordType`), `registerNestingTypes` (a directiveless object embedded by a
   `NestingField` becomes `NestingType`), and the orphan arm of `rejectDanglingTypeReferences` (a
   reached-but-neither object demotes its referencing field). That scatter is the inversion the
   field-first model set out to kill: fields should drive types, at the edge.

2. **`classifyField`'s only whole-registry reads are reverse-index queries over a fixed point.**
   Almost every `ctx.types.get(...)` in `FieldBuilder` is edge-local (the field's own
   return / element / member type, satisfiable by a post-order walk). The exceptions are two reverse
   indexes: `table -> NodeType` (five byte-identical `ctx.types.values()` scans resolving an
   ID-returning field's encode helper) and `participant-field -> crossTableField`
   (`lookupParticipantCrossTableField`). Both query information that is a registry-free fixed point
   (the `@node` / `@key` and `@reference` SDL plus the catalog and reflection bindings, the same
   inputs the classifier itself consumes). Five consumers evaluating one predicate is the
   under-specified-model smell; the fix is to compute the index once as a fixed point.

3. **The validate phase mutates classification.** The global soundness reductions
   (`validateNodeTypeIdUniqueness`, `rejectCaseInsensitiveTypeCollisions`,
   `collectDomainReturnTypeConflicts`, the dangling-reference backstop, and the carrier-shape scan)
   surface their findings by demoting to `UnclassifiedType` / `UnclassifiedField`. So "what a type
   is" and "what is wrong with the schema" are entangled in one mutable registry value, and a
   verdict read after validation is not the verdict classification produced.

## Why the original framing could not reach the goal

A byte-identical reorder leaves `classifyType` punting and the reverse scans reading the live
registry, so field classification still needs the complete type map first and cannot fold into the
walk. The single walk is not a reordering; it is the model correction in (1) and (2). And a clean
classify-then-validate boundary is not reachable while (3) holds. This item does that work.

## Goal

One edge-driven classification pass, then an immutable validate pass, then emit.

- **One classification pass.** The `SchemaTraverser` walk classifies each type as the edge reaches
  it (directiveless objects classified at the reaching edge from the parent's nature, carried down
  the walk, plus the binding fixed points) and each field in post-order (its edge-local target is
  already classified). The two reverse-lookups are served by precomputed fixed-point indices, so
  `classifyField` has no whole-registry dependency. `buildTypes` is deleted; the
  `reachableOutputTypes` cross-class hand-off is retired.
- **Immutable validate pass.** The global reductions read the finished registry and *register
  diagnostics*; they do not reclassify. After validation, a verdict equals the verdict
  classification produced.
- Verdicts stay order-independent: every verdict reads only node SDL, reflection, the fixed-point
  indices, and downward walk context, never the mutable in-progress registry sideways or back.

## Design decisions (settled with the principles-architect pass, 2026-06-17)

- **Fixed-point indices derive from the fixed point, not from the registry.** `table -> NodeType`
  is built from the `@node` / `@key` SDL scan plus the catalog (the inputs `NodeType` itself comes
  from); `participant-field -> crossTableField` from the `@reference` SDL. Building either by
  scanning `ctx.types.values()` and memoising would create a second producer of the same fact that
  can drift; deriving from the fixed point keeps one producer. The encode-helper the `table` index
  yields is itself derivable (encoder class constant + `"encode" + typeName` + keyColumns), so the
  index need not hold a `NodeType` registry entry.
- **`table -> NodeType` makes one-NodeType-per-table load-bearing.** Today the five scans take
  `findFirst()` in registry-iteration order; nothing enforces uniqueness
  (`validateNodeTypeIdUniqueness` guards only typeId). Lifting to a map must pin the tie-break and
  add a validator mirror rejecting two `@node` types on one table, or the lift is not byte-identical
  on a pathological schema.
- **Carrier binding takes the fixed-point route, not post-order.** The `JooqTableRecordType` verdict
  needs no descent: the table is known from the producer binding (`dmlEmittedBinding` /
  `serviceEmittedBinding`). Only the structural carrier scan reads element verdicts, and that scan
  is a validation guard in classification costume (it rejects a carrier whose element shape
  disagrees with its producer). Land the verdict at the edge from the binding; re-express the scan
  as a validate-phase diagnostic. This retires the classify-reads-classify dependency rather than
  relocating it to walk-leave (the post-order route would keep it).
- **Nesting becomes a product of the embedding edge.** `NestingField` construction already reads
  only the parent's table context; its single target read is a negative guard ("the target carries
  no competing verdict"), structurally true at the edge under edge-driven classification. Folding
  `registerNestingTypes` into the walk is sound; the two-pass `|| instanceof NestingType` guard is
  dropped (it encodes the old two-pass shape).
- **Orphan handling stays split.** The edge produces `UnclassifiedField` directly for an
  edge-decidable orphan (no reclassify, which is already the immutable-validate end shape for free).
  But the whole-registry dangling-reference *backstop* stays a validate-phase reduction: a target
  that looks orphaned mid-walk may be rescued by a later nesting or connection edge, so a per-edge
  final demotion is not sound. Under the immutable validate phase the backstop registers a
  diagnostic rather than demoting.

## What stays after the walk (validate phase, now immutable)

`validateNodeTypeIdUniqueness`, the new one-NodeType-per-table guard,
`rejectCaseInsensitiveTypeCollisions`, the dangling-reference backstop,
`collectDomainReturnTypeConflicts`, the carrier-shape scan, `EntityResolutionBuilder`, and
`MappingsConstantNameDedup`. These are reductions over the finished registry; a per-edge visit
cannot detect a cross-type conflict. They keep their place but, after the closing slice, register
diagnostics instead of reclassifying.

## Out of scope

R319 (emitting the warn-on-prune warning for unreachable types) stays a separate item; this item
only shifts unreachable types from silently classified to pruned. Migrating further reductions into
the LSP layer, and the broader diagnostic-surface redesign beyond what the inlined R318 needs, are
out of scope.

## Slicing

Each slice is independently gateable on the `GraphitronSchemaBuilderTest` truth table plus the
sakila pipeline / compile / execution tiers. Byte-identity is claimed per slice as noted; the
rescope deliberately relocates two invariants (one-NodeType-per-table becomes load-bearing; the
orphan verdict moves to the edge), so the validator-mirror rule is satisfied by explicit guards, not
trivially.

1. **Participant enrichment onto the node visit.** *Shipped (`96b2f18`).* `classifyType` classifies
   each interface / union with its participants at the node visit; the separate enrichment pass and
   its helpers are deleted. Byte-identical.
2. **Lift the reverse-lookups to fixed-point indices.** Build `table -> NodeType` (from the `@node`
   / `@key` SDL + catalog) and `participant-field -> crossTableField` (from `@reference` SDL); route
   the five encode-helper sites and `lookupParticipantCrossTableField` through them; add the
   one-NodeType-per-table validator mirror and pin the tie-break. Removes `classifyField`'s
   whole-registry reads. Byte-identical except that the new guard rejects a previously
   silently-first-wins two-node-per-table schema.
3. **Edge-complete `classifyType` for directiveless objects.** Classify nesting (fold
   `registerNestingTypes`) and carrier (fold `promoteSingleRecordPayloads` via the binding
   fixed-point verdict) at the reaching edge; edge-decidable orphans produce `UnclassifiedField`
   directly. The dangling backstop stays a post-walk reduction. Unreachable output types shift from
   classified to pruned (the warning is R319). Byte-identical for the reachable verdicts.
4. **Collapse to one walk; delete `buildTypes`.** The `SchemaTraverser` walk classifies types and
   fields (post-order); delete `buildTypes` and the `reachableOutputTypes` hand-off; update the
   `buildContextForTests` seam. Only validation reductions after. Byte-identical.
5. **Immutable validate phase (inlines R318).** The global reductions register diagnostics into a
   diagnostic channel instead of demoting to `UnclassifiedType` / `UnclassifiedField`; the
   validator, LSP, and emitter read diagnostics from there. Needs the diagnostic-channel design
   (where diagnostics live and how each reader consumes them). Changes the error-carrier mechanism,
   not which schemas pass or fail.

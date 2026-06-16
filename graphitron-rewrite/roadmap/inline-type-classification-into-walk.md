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

R279 made classification field-first and reachability-pruned and collapsed `TypeRegistry` to a single
`register` verb, but it stopped short of the "true single-pass DFS fold" its slice-3b honest scope
named "approach A" and explicitly deferred. The remaining structure runs the reachable surface three
times:

1. `SchemaReachability.reachableTypeNames(schema)` runs a `SchemaTraverser` (seeds
   Query / Mutation / Subscription plus the `@node` / `@key` scan; edges field-output, union-member,
   interface-implementor, object-interface) and returns a `Set<String>` of reachable output-composite
   names. This is the only place the traverser runs.
2. `TypeBuilder.buildTypes` iterates that set in a flat loop calling `classifyType` per type and
   `register`, then the narrowed sweep over the non-output-composite leftovers (inputs / scalars /
   enums), then its post-passes.
3. `GraphitronSchemaBuilder.buildSchema` iterates the **same** set in a second flat loop calling
   `classifyFieldsOfObject` per reachable object type, then its post-passes.

The reachable set is materialised once and threaded across two classes (`TypeBuilder.reachableOutputTypes`,
read by `GraphitronSchemaBuilder.buildSchema`), a small coupling smell that the R279 model corrections
already flagged: `buildTypes` "is transitional scaffolding, not part of the model." The driver is
field-first in *which* types it classifies (reachability-pruned) but the type verdict is still computed
in a loop keyed off the materialised name set, not registered at the field edge that reaches the type.

## Direction

Fold the type-classify loop into the field walk so a type's verdict is registered as a byproduct of the
edge that reaches it ("fields drive types"), retire `TypeBuilder.buildTypes` as a standalone type pass,
and stop materialising the reachable-name set as a cross-class hand-off. Behaviour-preserving over the
already-inverted R279 driver (byte-identical output); the prerequisite work (order-independent
`participantClassification` from slice 3a, the commutative single-verb `register` from slice 6) is
already paid, so this is the cheap finish, not a new hard problem. A `principles-architect` read
(2026-06-16) shaped the scope below.

- *Lighter register-at-edge, not a DFS-pre-order visitor.* The target is **not** a
  `GraphQLSchemaVisitor`-driven walk whose correctness depends on visiting a parent before its fields
  in pre-order. That would re-encode the ordering invariant R279 spent its slices eliminating (the
  "Determinism resolved structurally" guarantee: no verdict depends on walk order). The
  `setVar` / `getVarFromParents` machinery is for R308's ancestor-cardinality rider, out of scope here;
  pulling it in would be over-engineering. The only ordering R317 may rely on is the **local** edge
  property "register a type at the edge that reaches it, before classifying that type's own fields,"
  never a global pre-order invariant.
- *Every post-pass stays a post-pass.* The line R279 slice 3b drew is correct and R317 does not move
  it. Sorted by what they key on:
  - Global reductions over the finished registry (`validateNodeTypeIdUniqueness`,
    `rejectCaseInsensitiveTypeCollisions`, `collectDomainReturnTypeConflicts`,
    `rejectDanglingTypeReferences`, `MappingsConstantNameDedup`, `EntityResolutionBuilder`) are
    fold-points, not visit-points: a per-edge visit cannot decide "is this typeId unique across the
    whole schema." Leave them.
  - Passes already keyed on an order-independent fixed point (`surfaceMultiProducerRejections` reads
    the `RecordBindingResolver` rejection set; `emitDirectiveIgnoredWarning` reads reflection bindings
    plus SDL) run in dedicated SDL-order passes precisely so their output order is walk-independent.
    Folding them into walk order would re-introduce an order dependence for zero gain. Leave them.
  - The participant-enrichment second pass is foldable in principle (slice 3a made `enrich*` a pure
    SDL+reflection function), but folding it reshapes polymorphic classification, which R279 held
    constant and R278 owns. Leave it; let R278 decide whether the enrich step survives at all.
- *Inputs / scalars / enums keep their sweep.* They are leaves with no output edges; the model
  deliberately does not descend into arguments / input objects. Dragging argument-descent into the
  traversal to register input types re-introduces an excluded traversal event and splits input
  binding (already done per-field-usage) across two sites. A flat sweep over the named types minus the
  reachable composites is the simplest correct, order-independent thing. Keep it.

## Slicing

Behaviour is byte-identical and no classifier invariant is retired, so the "validator mirror in the
same commit" rule is satisfied trivially (nothing to mirror); the risk is purely *reordering*, so slice
for reorder-isolation.

1. **Fuse the type loop and field loop into one traversal; keep the materialised set.** Drive
   `classifyType`-register and `classifyFieldsOfObject` from one iteration / walk, the set still
   computed up front. Post-passes and the input sweep untouched. This is the pure reordering slice and
   the one most likely to trip the gate, so a bisect lands cleanly here. **The pressure-test for the
   whole item lives in this slice** (see Open question 1): `classifyFieldsOfObject` reads the parent's
   verdict via `ctx.types.get(parent)`, and `promoteSingleRecordPayloads` (a global post-pass over the
   complete registry) rewrites some object verdicts from `NestingType` to `JooqTableRecordType` *before*
   the field pass reads them today. If the field pass genuinely depends on a type post-pass having run
   over the full registry, the type pass (post-passes included) must complete before any field is
   classified, and the two loops cannot truly fuse into one register-then-classify-fields step. Gate:
   truth table + sakila TypeSpec / compile / execute, byte-identical.
2. **Drop the materialised hand-off and retire `buildTypes`** (only if slice 1 proved the fold clean).
   Remove `reachableOutputTypes` as a cross-class field; retire `TypeBuilder.buildTypes` as a named
   standalone pass. This is the payoff slice. Gate: truth table + sakila.

## Open questions / discard criterion

1. **Does `reachableTypeNames` genuinely stop being materialised, or does it merely move?** The
   input / scalar / enum sweep needs "all named types minus the reachable composites," and the
   post-passes iterate the registry; both may keep the set alive. If, after slice 1, the set has only
   moved rather than dissolved, the win is "two loops fused while the set stays," which is close to a
   wash, and the stub's discard criterion is met: stop, record the finding (the field pass depends on
   `promoteSingleRecordPayloads` over the complete registry, so the type pass cannot dissolve into the
   field walk), and close R317 as Discarded rather than forcing slice 2.
2. **Scope tripwire.** If the fold seems to want a post-pass folded in (e.g. `surfaceMultiProducerRejections`)
   to look clean, that is the signal R317 has left its scope and entered new behaviour; push it back out
   and, if it has independent merit, file it separately. R317 is byte-identical or it is not R317.

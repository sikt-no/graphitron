---
id: R546
title: "Invert the command flow: the core produces method commands, the shell consumes them"
status: Backlog
bucket: structural
theme: classification-model
depends-on: [model-free-of-emit-vocabulary]
created: 2026-07-26
last-updated: 2026-07-26
---

# Invert the command flow: the core produces method commands, the shell consumes them

The command flow runs backwards. `MethodCommandRegistry.declareReentryRowsMethod(field, unitFqcn)` is called *by* the shell (`TypeFetcherGenerator`, `TypeFetcherEmissionContext`) and *returns* the method name for the emitter to use, so a command is minted during rendering and read afterwards as an audit trail, which `MethodClosureOracleTest` joins against the emitted units through `EmittedMethodClosure`. That is shell-asks-core. The cut R333 draws is core-tells-shell: the core decides the entire emit and the shell renders what it is handed. The distinction is not academic, because a command produced during rendering can never be the input that constrains rendering, so the shell keeps its freedom to decide and the closure oracle can only check consistency after the fact rather than completeness before it.

Flip the direction for the one family that already has commands, and nothing more. The core produces the reentry rows-method command ahead of rendering, `TypeFetcherGenerator` consumes it rather than asking for a name, and `MethodClosureOracleTest` stays green across the flip. Coverage is deliberately unchanged: this item buys the *direction*, on the only ground the oracle already covers, so the flip is falsifiable the moment it regresses. Every later family (R541's root query unit first, since it is already in Spec and already spending the registry) then follows the same shape with the oracle as its ratchet, and each one that flips shrinks the shell's decision surface measurably. Context and measurements are in `roadmap/audits/2026-07-26-fcis-command-layer-distance.md`.

Depends on R545 landing first, or at least on the command type it produces being free of emit vocabulary; a command carrying a `TypeName` re-creates the problem this item exists to remove. Spec owes the command's shape beyond today's four strings (`MethodCommand` carries coordinate, unit, type path, method name, which is enough to name a method but not to say what kind of method it is or which callees it closes over), where the production site sits relative to `GraphitronSchemaBuilder.buildBundle`, and whether the registry survives as the core's output collection or is replaced by a plain relation on the bundle. The oracle's assertion probably strengthens too: today it asks whether every callee resolves, and with core-produced commands it can also ask whether every command was rendered, which is the completeness half R333's thread I names.

## Why this pays: the recompile graph is a duplicate of the same relation

The strongest case for core-produced commands is not test leverage, it is deleting a duplicated
derivation that already generates bugs. The dev loop needs to know which generated units a schema edit
invalidates, and it answers that with `CompileDependencyGraphBuilder.fromModel`, 731 lines that coarsen
the classified model into an FQCN-keyed edge graph through an exhaustive switch over leaf arms, each arm
declaring the unit-level edges its field contributes. `TypeSpecReferenceWalk` then walks the emitted
specs as a completeness oracle, because the model-derived graph and the real references can disagree.
So the emit call graph is derived twice: once by the emitters that emit the calls, once by a
hand-maintained switch that predicts them.

The duplication generates a recurring bug class rather than occasional bugs. R455 fixed
`TypeSpecReferenceWalk` blind spots that silently falsified the oracle. R459 added a missing node for
fetcher-owning plain-object nesting types. R462 is open (bucket `bug`) for the nested fetcher's missing
outgoing per-field edges, and it names the root cause exactly: `addFieldEdges` never sees those fields
because they are absent from `schema.fields()`, and `schema.fieldsOf(nestedType)` is empty for a
coordinate-less nesting type, so the coarsening helpers read that empty collection and answer wrongly.
All three are the same shape: the graph is derived from coordinates, the emit contains methods no
coordinate exposes, and such a method is invisible to the graph by construction.

A command exists because a method will be emitted, not because a coordinate was found in
`schema.fields()`. So the dependency graph stops being a re-derivation and becomes a projection: nodes
are commands grouped by `unitFqcn`, edges are the command edge relation projected to unit granularity.
R462's failure mode cannot occur, since there is no path from "a method was emitted" to "no node
represents it". The 731-line switch collapses toward a group-by, and the two walks over emitted specs
unify: `TypeSpecReferenceWalk` stops cross-checking two derivations and becomes the closure invariant
`EmittedMethodClosure` already asserts. If commands carry signatures, the ABI axis follows too, since
`AbiSignature.hash` today fingerprints the *rendered* unit while a command diff would tell the engine
what changed before rendering.

Caveats that keep this honest. javac's unit is the file, so output granularity does not change, only the
derivation. The blanket edges onto frozen runtime scaffolding remain, as explicit command dependencies.
Signature-carrying commands are a larger command than this item's probe needs, so the recompile-graph
payoff lands a step later, and it must not be smuggled into the probe's scope. And the dev loop is
user-visible, where a wrong recompile set means stale classes, so this is riskier than re-basing tests;
the mitigation already exists in `IncrementalCompileHarnessTest` (superset oracle, clause (a)
incremental-equals-clean-full-compile, clause (b) body-edit-prunes-while-ABI-edit-propagates), which is
the ratchet any such re-basing must hold green.

## Abandon condition

This item is a probe and its product is information. The program it belongs to (re-platforming a
29,837-LOC shell onto commands) is not justified by current evidence, so it should be judged on what the
cheapest family teaches, with an explicit willingness to stop. The baseline, measured 2026-07-26 and
re-derivable from the audit's script: 1,641 branches in `generators/`, roughly 100 `instanceof` sites
naming model leaves, 29,837 generator LOC, 400 `ClassificationCase` constants, and R25's emitter
coverage figures (`JooqRecordInstantiationEmitter` 40.7%, `FetcherEmitter` 50.2%).

Re-run those after this item, R545, and one real family (R541). If they have not moved in the right
direction, stop: keep the facts half of R333, which is paying its way slice by slice, and abandon the
command half rather than accepting a half-migrated shell. A partial migration is the one outcome worse
than either endpoint, and R333 says so itself, since leaves kept alive to feed one consumer is how the
leaf zoo returns as a second model.

## Out of scope

Extending coverage to further emit families (each rides with its family), the reflection-at-the-edge
purity work, signature-carrying commands and the recompile-graph re-basing they enable (a later item,
justified above but deliberately not attempted here), and any change to emitted output. The emitted code
should be byte-identical across this flip, which is the cheapest possible acceptance test for it.

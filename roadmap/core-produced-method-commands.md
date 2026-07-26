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

Out of scope: extending coverage to further emit families (each rides with its family), the reflection-at-the-edge purity work, and any change to emitted output. The emitted code should be byte-identical across this flip, which is the cheapest possible acceptance test for it.

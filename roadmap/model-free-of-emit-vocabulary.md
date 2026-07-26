---
id: R545
title: "The model owns no emit-library vocabulary"
status: Backlog
bucket: structural
theme: classification-model
depends-on: []
created: 2026-07-26
last-updated: 2026-07-26
---

# The model owns no emit-library vocabulary

Under the functional-core / imperative-shell cut R333 draws, facts and commands are pure data and the emit library is the shell's business. The model does not hold to that today: `ClassName` appears in 21 model files and `TypeName` in 20, plus `ParameterizedTypeName` and `ArrayTypeName`, and the model does not merely carry javapoet types but *computes* them (`CallParam.deriveJavaType`, `RowsMethodShape.strictPerKeyType`, `RowsMethodShape.standardScalarJavaType`, `RowsMethodShape.outerRowsReturnType`). One file goes further and holds rendered output: `RowsMethodBody`'s permits each carry an opaque `CodeBlock`, and since `SplitRowsMethodEmitter` and `TypeFetcherGenerator` construct it while `RowsMethodSkeleton` consumes it, it is a shell-to-shell handoff misfiled as a model type, with the boundary inverted (the shell owns the declaration scaffolding while a model type carries the body text). `BodyParam` is the counter-example proving the target shape is reachable: a sealed hierarchy of pure records with no emit vocabulary at all. Measurements and their method are in `roadmap/audits/2026-07-26-fcis-command-layer-distance.md`.

This is a precondition, and its value is entirely in what it unblocks; it is not an independent win. Two things are blocked while facts speak javapoet. Facts are comparable only through the renderer's equality and printable only as generated-code fragments, so a declarative fixture cannot assert them as data, which is why R543's command half names this item as its gate. And no file can move between layers while both layers share a vocabulary, so the later steps of the audit's migration sequence are gated here.

One argument that looks available is not: the projection layer is *not* contaminated. `rewrite/catalog/` (the `FieldClassification` / `TypeClassification` / `CompletionData` projections the language server and the model-context server read) imports no javapoet at all, and `graphitron-lsp` and `graphitron-mcp` import it in zero files, so the projection seam already launders the vocabulary out. Anyone justifying this item on "the editor and the agent shouldn't depend on a code generator" is arguing from a claim the code refutes. The honest case is narrower: assertability, and the layer-hygiene precondition above.

Two deliverables, both compiler-guided rather than behavioural. First, a pure type-reference record (working name `JavaTypeRef`: package, simple name, type arguments, array-ness) replaces `TypeName` / `ClassName` across the model, with the javapoet-producing helpers either moving to the shell or returning the record; the shell converts at its own boundary, which is the one place that should know javapoet exists. Second, `RowsMethodBody` moves to `generators/` as the shell-internal handoff it already is, with no semantic change. Worth landing the move *before* any real command type exists, so nobody reads a `CodeBlock`-carrying record as the template for one.

Spec owes the conversion boundary (one adapter in the shell, or per-emitter conversion), whether `JavaTypeRef` needs to model wildcards and primitives or only what the model actually uses today, and what guards the invariant afterwards. An `ArchUnit`-style or meta-test check that no `model/` source imports the emit library is the obvious candidate, and it is the only thing that keeps this from re-rotting the way the vocabulary arrived in the first place. Out of scope: the command layer itself (R546), the reflection-at-the-edge purity work, and any change to what the generators emit.

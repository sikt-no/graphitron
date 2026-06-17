---
id: R318
title: "Validation registers diagnostics without reclassifying (immutable validate phase)"
status: Backlog
bucket: architecture
priority: 4
theme: structural-refactor
depends-on: []
created: 2026-06-17
last-updated: 2026-06-17
---

# Validation registers diagnostics without reclassifying (immutable validate phase)

Today the global soundness reductions surface their findings by *mutating* classification: a
NodeType with a colliding typeId, a case-fold type collision, a dangling type reference, and a
multi-producer domain disagreement all `register` an `UnclassifiedType` (or `reclassify` an
`UnclassifiedField`) over the original verdict so the validator's `UnclassifiedType` /
`UnclassifiedField` pass picks the error up. So "what a type is" and "what is wrong with the
schema" are entangled in one mutable registry value, and the classify and validate phases are not
cleanly separable: a verdict read after validation is not the verdict classification produced.

End goal (the simplicity north star): classification is a single pass producing an **immutable**
result; validation is a pure read over that result that **registers diagnostics** (errors and
warnings) as their own entries, without altering any type or field classification. A reviewer can
then reason about classification and validation independently, and "the registry verdict" means
exactly one thing. This depends on R317 having collapsed classification to a single pass first; the
behavior change here (errors no longer ride on `UnclassifiedType`/`UnclassifiedField`) is out of
R317's byte-identical scope and is the substance of this item. Needs a diagnostic-registry design
(where errors/warnings live, how the validator and the LSP/emitter read them) before it can move to
Spec.

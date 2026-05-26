---
id: R218
title: "Carry inference provenance on ParamSource.Arg so resolved bindings audit cleanly"
status: Backlog
bucket: architecture
depends-on: []
created: 2026-05-21
last-updated: 2026-05-21
---

# Carry inference provenance on ParamSource.Arg so resolved bindings audit cleanly

R214's `ServiceCatalog.inferBindingsByType` mutates `argByJavaName` silently between the override-typo check and the per-parameter loop. The resulting `ParamSource.Arg(extraction, path)` is structurally identical regardless of whether the binding came from an explicit `argMapping`, a same-name identity match, the arity-unique inference branch, or the type-unique inference branch. The resolved-coordinate report and any future LSP "where did this binding come from?" surface can't tell them apart. The principles-architect review (round 1, finding 4) flagged this as a "load-bearing invariant doesn't have an emit-time witness" gap, citing the typed-rejection / auditable-resolution narrative the project rests on.

This is the same shape R215 addresses for input fields. R215 introduces `InputField.UnboundField` precisely so the classifier's structural answer is visible by type rather than re-derived; consumers switch on the variant exhaustively. The R214 analogue: split `ParamSource.Arg` into a small sealed taxonomy carrying the binding's provenance (`Explicit` for `argMapping` overrides, `IdentityNameMatch` for the default same-name binding, `Inferred.Arity` and `Inferred.Type` for the two R214 branches). Consumers either treat them uniformly (most emitters) or switch exhaustively when provenance matters (resolved-coordinate report, LSP "go to binding source", validators that want to refuse inferred bindings in strict modes).

Scope notes for Spec:

- **Shape.** Sealed sub-taxonomy on `ParamSource.Arg`, vs. an enum axis carried alongside `extraction` / `path`, vs. a wrapper `record InferredArg(Arg inner, InferenceKind kind) implements ParamSource`. Each has different exhaustiveness consequences for the downstream switch surface (`FetcherEmitter`, `ServiceProducerPipeline`, `RecordBindingResolver`).
- **Visibility.** Where does provenance render? Resolved-coordinate report (definitely); LSP hover on the Java parameter (likely); a build-time warning when an inferred binding survives into emission (open — turns the silent rebind into a noisy one, which authors can grep but might find chatty).
- **Strict mode.** Should a build-time flag let authors disable inference entirely (only `argMapping` and identity name match allowed)? Useful for codebases that want zero implicit behavior; cheap to add once provenance is typed.

Files in play: `model/ParamSource.java`, `ServiceCatalog.java` (`inferBindingsByType`, both reflect methods), every downstream switch on `ParamSource` (compiler exhaustiveness check is the safety net), and the resolved-coordinate report renderer.

Related: R214 (sibling, shipped with silent provenance; this item adds the audit axis), R215 (same principle applied to input field classification — the variant identity records the structural fact once).

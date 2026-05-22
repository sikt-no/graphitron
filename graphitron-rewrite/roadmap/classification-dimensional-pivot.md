---
id: R226
title: "Classification dimensional pivot: diagnostics off the model"
status: Backlog
bucket: structural
priority: 3
theme: structural-refactor
depends-on: []
created: 2026-05-22
last-updated: 2026-05-22
---

# Classification dimensional pivot: diagnostics off the model

`GraphitronType` permits `UnclassifiedType` alongside the legitimate types (`TableType`, `RootType`, `InputType`, `TableInputType`, ...); `GraphitronField` permits `UnclassifiedField` alongside its legitimate field variants; `GraphitronSchema` carries `warnings: List<BuildWarning>` as a side slot. All three are validator-output data riding the model. Two dimensions collapsed onto one identity slot: type-kind on one axis, classification-outcome (classified / rejected / "we ignored your decoration") on another. The validator pays for it by doing a translation half ("walk `Unclassified*` carriers; project the typed `Rejection` payloads to `ValidationError`s") on top of its actual job of checking cross-type invariants. R222 is the same shape one organ over (consumer-independent vs consumer-dependent collapsed onto `InputType` / `TableInputType` permits).

The cure mirrors R222's. Split the axes. `GraphitronSchemaBuilder` emits to a diagnostic sink (`Consumer<Diagnostic>`, where `Diagnostic` carries severity + typed payload + `SourceLocation`); successful classifications populate the model maps; failures emit diagnostics. The five existing `BuildWarning` producers (the `@record`-shadowed-by-`@table` lint, the redundant-`@record` lint, the divergent-`@record` lint, the federation entity-key drift warning, the `FieldBuilder.java:3853` site) keep their `ctx.addWarning(...)` call shape; the call delegates to the injected sink instead of appending to a `BuildContext.warnings` list. The `Unclassified*` permits retire; `Rejection` stays as the diagnostic payload taxonomy, detached from carrier residence on the model. The validator shrinks to "check invariants over the classified model"; the translation half evaporates because there is nothing to translate.

Forward-compatibility with R222: the `ctx.addWarning(...)` call site R222's Phase 3 introduces (the `@table`-on-input deprecation warning) is shape-compatible with both today's accumulator and the post-pivot sink. R222 does not need to anticipate this item's design; this item picks up the cleanup whenever it ships.

Open design questions (deferred to Spec phase):

- **Two sinks or one with a severity field.** The existing pattern uses `Consumer<BuildWarning>` (`EntityResolutionBuilder.build(..., warningSink)`). Generalising to `Consumer<Diagnostic>` with a severity enum reads cleaner but converges with the typed-rejection narrative (`docs/typed-rejection.adoc`) which already shapes `Rejection` as the error payload. The Spec call: does `Diagnostic` wrap `Rejection` for errors, or do errors and warnings share a single sealed `Diagnostic.{Error(Rejection), Warning(message)}` taxonomy?
- **Validator surface shrinks how far.** Today's `GraphitronSchemaValidator.validateUnclassifiedType` / `validateUnclassifiedField` retire entirely under this item. The cross-type invariant checks (`paginated-fields-have-ordering`, `list-fields-have-pagination`, the cross-site type-agreement walks) stay. Whether the validator's `ValidationReport.from(errors, warnings)` projection retains today's accumulating shape, or becomes a pass-through over the sink output, is a Spec call.
- **LSP sink shape.** The LSP needs to surface diagnostics at editor positions; the sink contract has to be IDE-friendly without leaking IDE concerns into `BuildContext`. The dependency-injection pattern (caller supplies the sink) keeps the boundary clean.
- **Migration ordering.** Phased introduction of `ClassifiedGraphitronType` alongside today's `GraphitronType` (with the `Unclassified*` permits)? Or a coordinated cutover with the diagnostic sink injected and every producer / consumer flipped in one PR?

Adjacent / not absorbed:

- **R222** (input model dimensional pivot): forward-compatible. R222's `ctx.addWarning(...)` call site does not change shape under this item.
- **R164** (field model two-axis pivot): orthogonal organ; R164's field hierarchy pivot is independent of the classification-outcome pivot this item codifies, though both belong to the same dimensional-decomposition principle family.
- **`Rejection` taxonomy** (`docs/typed-rejection.adoc`): preserved verbatim. This item changes *where* `Rejection` payloads live (on diagnostic events, not on `Unclassified*` model carriers), not *what* the taxonomy carries.

Scope: large. Touches `GraphitronSchemaBuilder`, `BuildContext`, every `Unclassified*` producer and consumer, `GraphitronSchema`, `GraphitronSchemaValidator`, `ValidationReport`, the Maven plugin's `validate` / `generate` Mojos, and the LSP's classification surface. Comparable in scope to R164.

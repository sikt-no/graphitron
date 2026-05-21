---
id: R217
title: LSP inlay classification labels surface model leaf names; inferred-@table renders on declarations when directive is absent
status: Ready
bucket: lsp
priority: 3
theme: lsp
depends-on: []
created: 2026-05-21
last-updated: 2026-05-21
---

# LSP inlay classification labels surface model leaf names; inferred-@table renders on declarations when directive is absent

The R160 LSP inlay-hint surface trades pedagogical value for prose polish in two places. The classification arm (both inlay and hover) renders pretty strings like `"table type"`, `"column"`, `"query table-method field"` via `LspClassificationLabels.projectionLabel` / `projectionTypeLabel`, decoupled from the projection record names a developer would learn the model by (`FieldClassification.TableTarget`, `TypeClassification.TableInput`). Developers reading hints get no foothold into the taxonomy they will eventually navigate, and refining the pretty phrasing was never a goal worth a label-vocabulary file. The inferred-directive arm only fires at *existing* `@table` / `@field` / `@reference` directive nodes whose canonical argument is omitted, because it walks `Directives.findAll(root)`; types that classify as `Table` / `Node` / `TableInterface` / `TableInput` but carry no `@table` directive at all render nothing, hiding the binding the developer most needs to see (a `TableInput` whose binding came from naming convention is the salient case, but the same gap applies to any `@table`-classified type written without the directive).

## Affected sites

1. **`LspClassificationLabels.projectionLabel` / `projectionTypeLabel`** (`graphitron-lsp/src/main/java/no/sikt/graphitron/lsp/inlay/LspClassificationLabels.java:112-145, 201-228`). The two switches collapse the projection record into a hand-curated prose label. Replace each body with an exhaustive `switch (c) { case X x -> X.class.getSimpleName(); ... }` form that keeps compile-time coverage over `FieldClassification` / `TypeClassification` even though every arm body is uniform; a new permit then still fails to compile until the author confirms the default `getSimpleName()` shape is acceptable. Class-level Javadoc inverts: the projection record name *is* the label.
2. **`LspClassificationLabels.fieldLabel(GraphitronField)` / `typeLabel(GraphitronType)`** (same file, lines 39-103, 167-194). The generator-permit variants have no callers in the LSP (only the projection variants are referenced, from `InlayHints` + `DeclarationHovers`). Delete both, plus the doc paragraphs that distinguish "generator-side permit" from "projection".
3. **`DeclarationHovers.renderFieldMarkdown` / `renderTypeMarkdown`** (`graphitron-lsp/src/main/java/no/sikt/graphitron/lsp/hover/DeclarationHovers.java:118-119, 240-241`). The hover header prints `**<label>**`; switch to the qualified name (`**FieldClassification.TableTarget**` / `**TypeClassification.Table**`) so the hover answers "what is this called in the codebase". Rich payload below the header stays unchanged.
4. **`InferredDirectiveArgs.Entry`** (`graphitron/src/main/java/no/sikt/graphitron/rewrite/catalog/InferredDirectiveArgs.java:30`). Extend the entry with absent-eligibility metadata so the new arm dispatches through the same canonical-arg table as the present-but-bare arm. Concretely: add a `boolean renderWhenAbsent` (or a sealed sub-variant) carrying the classification predicate; the `@table` entry flips it on, `@field` / `@reference` leave it off (matching the policy in the judgement-calls section below). This keeps the doc-comment invariant — *"A future inference rule adds one entry here and downstream consumers either pick it up automatically or fail to compile"* — in force across both arms; without it, R217 silently makes the canonical-arg table non-authoritative for the new arm.
5. **`InlayHints.collectInferredDirectiveHints`** (`graphitron-lsp/src/main/java/no/sikt/graphitron/lsp/inlay/InlayHints.java:139-159`). Add a second pass that walks type-definition nodes (parallel to `collectClassificationHints`'s walk) and, for entries where `renderWhenAbsent` is set, emits a synthetic `@<directive>(<arg>: "...")` hint when the classification is in the entry's eligibility set *and* no directive node of that name is present on the type. For the `@table` entry, the eligibility set is `Table` / `Node` / `TableInterface` / `TableInput`. Anchor at the type-name node's end (same anchoring as `collectClassificationHints`); padding-left on; label string `@table(name: "<resolved>")` (full directive, not just the arg, since there's no directive name to dock against). Wear the existing `@DependsOnClassifierCheck(key = "type-classification-payload-faithful")` annotation on the new helper — its `reliesOn` text widens to note the absent-directive arm reads `tableName()` off the same projection records.
6. **`InlayHintsTest`** (`graphitron-lsp/src/test/java/no/sikt/graphitron/lsp/inlay/InlayHintsTest.java:79, 173`) and **`DeclarationHoversTest`** (`graphitron-lsp/src/test/java/no/sikt/graphitron/lsp/hover/DeclarationHoversTest.java:40, 123`) assert pretty-string labels (`"table type"`, `"column"`, `"**insert mutation**"`); flip to projection record names. Add new cases pinning the synthetic-`@table` ghost on input + object types that lack the directive.

## Shape of the fix

The label change is mechanical: the two switches keep their structure but each arm body collapses to `X.class.getSimpleName()`. Exhaustiveness over `FieldClassification` / `TypeClassification` survives as a compile-time tripwire for "a new projection variant exists; come confirm the default label shape reads sensibly"; this is the second job the switches were doing (beyond carrying the vocabulary) and it stays useful. The Javadoc rewrite is the larger surface area than the code change.

For the absent-`@table` arm, the walk reuses `walkTypeDefinitions` (already driving `collectClassificationHints`) and the renderer dispatches off `InferredDirectiveArgs.Entry.renderWhenAbsent`. Per type, per absent-eligible entry:

- Look up the classification by type name.
- If it's in the entry's eligibility set (for `@table`: `Table` / `Node` / `TableInterface` / `TableInput`), resolve the canonical-arg value via the existing `tableNameOf(TypeClassification)` helper inside `InlayHints`.
- Check whether the type node carries a directive child with the entry's name. If a directive node exists, the existing present-but-bare arm handles it; the absent arm renders only when no such directive node is on the type.
- Emit the hint at the type-name node, label `@<directive>(<arg>: "<resolved>")`.

The two passes (existing present-but-bare arm over directive nodes; new absent arm over type-name nodes) stay structurally separate as code paths but share the canonical-arg table as their source of truth. Both gated by the same `config.inferredDirectives()` toggle: a user who wants either wants both.

### Invariant change (load-bearing, called out)

R217 couples user-visible LSP labels to `FieldClassification` / `TypeClassification` projection-record simple names. Before R217, the projection-record name was model-internal vocabulary; renaming `TableTarget` to `JoinedColumnTarget` was a refactor with zero user-facing footprint. After R217, every projection-record rename is also a user-visible-string change (docs, screenshots, tutorials). The Spec accepts that coupling deliberately — it's the pedagogical mechanism that makes the LSP a teaching surface for the model — but the projection records should grow a class-level comment recording the dual role, so a future renamer is aware they're touching user-facing text.

### Judgement calls

- **Should the present-but-bare ghost change to render the full `@table(name: "x")` string for consistency with the absent case?** No. The existing arm docks against the directive name node and the user already sees `@table`; rendering only `name: "x"` keeps the ghost short and avoids visual duplication. The absent case has no directive name, so it must spell out `@table(...)` in full.
- **Should `@field` / `@reference` get the same absent-directive treatment?** Out of scope for R217. `@field` is implicit on every column the projection covers; synthesising a ghost on every field declaration would drown the view. `@reference` belongs only on FK fields; the cost/benefit is different and worth its own item if asked for. Because absent-eligibility lives on `InferredDirectiveArgs.Entry`, flipping either on later is a single entry edit, not a new renderer arm.
- **Should the label-switch deletion bring `LspClassificationLabels` down to nothing, or keep the projection switches as an exhaustiveness firewall?** Keep the switches (uniform arm bodies). Two `getSimpleName()` calls don't earn a module; one compile-time tripwire over the sealed projection sets does.

## Test surface

- `InlayHintsTest.classificationHintsLabelFieldDeclarations` flips assertions from `"table type", "column"` to `"Table", "Column"` (or the appropriate projection-record simple names for the fixture).
- `InlayHintsTest` gains two cases: one with `input ActorInput { ... }` classified as `TableInput` and no `@table` directive, pinning that `@table(name: "actor")` renders at the type-name node; one with `type Customer { ... }` classified as `Table` and no `@table` directive, same pin.
- `InlayHintsTest.inferredTableHintSuppressedWhenAuthored` extends: a `@table(name: "actor")` (canonical arg present) still produces no hint, and the new absent-directive pass also stays quiet because the directive node is present.
- `DeclarationHoversTest.*` flips header assertions from `"**table type**"` / `"**insert mutation**"` to `"**TypeClassification.Table**"` / `"**FieldClassification.DmlMutation**"`.

## Out of scope (called out, not regressed)

- Inferred `@field` / `@reference` on field declarations that omit the directive entirely.
- Hover-side classification-arm wiring (already done in R160); only the header string changes.
- Any change to the generator-side permit hierarchy or projection record set; the new labels surface whatever names exist today.

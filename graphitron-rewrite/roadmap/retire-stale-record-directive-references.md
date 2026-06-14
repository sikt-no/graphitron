---
id: R307
title: "Retire stale @record references: dead-directive advice, jargon, the deprecation-warning emitter, test-fixture @record, and the LSP's @record className tooling"
status: Ready
bucket: cleanup
priority: 5
theme: legacy-migration
depends-on: []
created: 2026-06-14
last-updated: 2026-06-14
---

# Retire stale @record references: dead-directive advice, jargon, the deprecation-warning emitter, test-fixture @record, and the LSP's @record className tooling

The `@record` directive is **DEPRECATED and IGNORED** (`directives.graphqls:288-297`):
it binds no type to a Java class and drives no behaviour, declared only so existing
schemas keep parsing. A reachable type carrying it gets a build warning telling the
author to remove it. `@record` **stays a legal-but-ignored directive for now**: the
`directives.graphqls` declaration is intentionally retained so existing consumer
schemas keep parsing. In the generator module the only live reads of the directive name
(`DIR_RECORD`, `BuildContext.java:78`) are the schema-declaration assert
(`GraphitronSchemaBuilder.java:561`), the deprecation-warning machinery
(`TypeBuilder.emitDirectiveIgnoredWarnings` at `:333`, called from `:192`, plus
`readRecordClassName` at `:403`, which reads the `className` arg only to phrase the warning),
and nothing else. Nothing reads `@record` to drive binding. (The LSP also treats `@record`
as live; see below.)

Today that warning is produced by a **standalone emitter**: `emitDirectiveIgnoredWarnings`
re-walks the entire schema *after* classification looking for `@record`-carrying types.
The classifier already visits every type; this item folds the deprecation warning into
that single classification pass and deletes the separate emitter, so the signal bottoms
out at classification (and is testable there without a pipeline fixture).

A **second live treatment lives in graphitron-lsp**. Because `@record`'s declared argument
is `record: ExternalCodeReference`, the LSP resolves it through the same generic
ECR-className machinery as `@enum`: it offers className FQN completion on
`@record(record: {className: ...})` (`ClassNameCompletions.generate`, dispatching on
`Behavior.ClassNameBinding`, off the generic `ExternalCodeReference.className` behavior
registered at `parsing/LspVocabulary.java:755` on the coordinate
`InputField("ExternalCodeReference", "className")`), raises an
"Unknown class '…'" diagnostic when the className does not resolve
(`Diagnostics.validateClassName`, `Diagnostics.java:631`), and hovers the directive as a live
binding (`Hovers.richerHover` → `classNameHover`, `Hovers.java:171`). None of that should survive: `@record` is dead, so the editor
should say only that it is ignored/deprecated and offer nothing else.

The "say it is deprecated" signal already reaches the editor, and not through the
declaration-side `@deprecated` pathway. `LspVocabulary.deprecationOf` / `deprecatedCoordinates`
(the `@deprecated` docstring-token convention on a declaration) is consumed only by the
code-action drift test (`SdlActionDriftTest`) and the auto-migration allow-list
(`SdlActions.MANUAL_MIGRATION_DEPRECATIONS`); it produces **no** usage-site diagnostic or hover,
so wiring `@record` into it would light up nothing for the author. The signal that *does* reach
the editor is the generator's own `@record`-ignored `BuildWarning`: the LSP runs a full
`GraphQLRewriteGenerator` pass, `schema.warnings()` (the `ctx.addWarning` output, including the
ignored-directive warning) is bundled into the `ValidationReport`, and
`Diagnostics.validatorDiagnostics` (`Diagnostics.java:190-194`) maps every `BuildWarning` to a
usage-site LSP `Warning` (covered by `ValidatorDiagnosticsTest`). Because strand 1 keeps the
warning a `BuildWarning` (it only moves *where* it is produced, from a standalone emitter to the
classifier), this editor signal is preserved with **no new LSP machinery**. The LSP work is
therefore purely subtractive: retire the className tooling; the deprecation signal stays wired
through the existing validator-warning surface.

The work has two parts; Part B carries both the generator-side relocation and the LSP-side
retirement.

**Part A (wording, already landed on trunk)** scrubbed the stale `@record` *references*
in two flavours, neither of which is the deprecation machinery:

1. **Error messages that told authors to *add* `@record`.** Five rejection strings
   steered authors toward the dead directive (two via the never-valid `@record(class: ...)`
   arg form); they were rewritten to point at the reflected-backing path. (`MutationInputResolver`,
   `FieldBuilder` ×3, `SourceRowDirectiveResolver`.)

2. **`@record` used as jargon for "record-backed type."** Comments, javadoc, rejection
   text, section labels, assertion strings, and the `EntityResolutionBuilder` kind label
   used "@record" as shorthand for a Pojo / JavaRecord / jOOQ `Record` / `TableRecord`-backed
   type. All were renamed to "record-backed" (or the variant name) across main and test
   source, in lockstep so the build stayed green.

**Part B (this re-spec) retires the standalone emitter, the LSP's `@record` className
tooling, and the applied `@record` in test fixtures.** The production-shaped
`graphitron-sakila-example/schema.graphqls` already carries no applied `@record` (only
comments; the 11 it used to carry were removed in R294). Applied `@record` survives only in
the inline-SDL test fixtures of two modules. There is no applied `@record` in any
`.graphqls` resource; every occurrence is in a Java text-block fixture. It comes in three
shapes:

1. **Deprecation-warning-path fixtures** (generator module) that exist solely to assert the
   ignored-warning: `R96RecordBindingPipelineTest:84-85`,
   `GraphitronSchemaBuilderTest:4164-4165`, `BuildOutputReportPipelineTest:84`.
2. **Binding-hint decoration** (generator module, the bulk: roughly fifty
   `@record(record: ...)` uses plus a handful of bare no-arg `@record` applications across
   the `graphitron` test tree) on types that actually bind via reflection (a producing
   `@service` return type / `@table`), so the directive is inert there and dropping it is
   classification-neutral.
3. **LSP editor-surface fixtures** (`graphitron-lsp`, roughly fourteen `@record(record: ...)`
   uses across ~8 files: `ClassNameCompletionsTest`, `DiagnosticsTest`, `HoversTest`,
   `DirectiveShapeSmokeTest`, etc.) that pin the className-completion / unknown-class-diagnostic
   / hover treatment. These are **not** inert: the LSP serves them off the directive
   declaration, not reflection, so the classification-neutrality argument does not apply. They
   pin behaviour that must itself be retired.

All three shapes go. The binding-hint fixtures drop `@record` (classification is unchanged,
since reflection already drives the binding). The warning-path coverage moves off pipeline
fixtures onto a simple classifier-level test, because the deprecation warning bottoms out at
classification and needs no compilation/execution fixture to verify. The LSP fixtures are
rewritten to assert the carve-out: `@record` gets **no** className completion, **no**
unknown-class diagnostic, and **no** live-binding hover. The "it is ignored" signal in the
editor is the generator's `@record`-ignored `BuildWarning` already surfaced through
`validatorDiagnostics`; a reachable `@record` fixture asserts that warning lands as a usage-site
`Warning` (the `BuildWarning` is reachability-gated, so an unreachable-type fixture asserts only
the absence of className tooling, matching the generator, which never warns about types it does
not generate).

## Scope

### Part A — wording (landed on trunk)

- **Rewrote the five recommend-`@record` rejection messages** to point at the reflected
  backing path (a producing `@service` return type, `@table`, `@tableMethod`, a
  parent-accessor chain, or `@sourceRow`) instead of the dead directive, dropping the
  never-valid `@record(class:)` form; updated the assertions that pinned the old text.
- **Renamed the `@record`-as-jargon vocabulary** to "record-backed" across main and test
  source, in lockstep so the build stayed green. Where a message names the *type variant*,
  the variant name (Pojo / JavaRecord / JooqRecord / JooqTableRecord) is preferred.

### Part B — emitter relocation + LSP retirement + fixture purge (remaining)

- **Delete the standalone `emitDirectiveIgnoredWarnings` pass** (`TypeBuilder:333`, call
  site `:192`) and register the `@record`-ignored deprecation warning from the classifier
  as it classifies each type, so the warning is a classification output rather than a
  post-classification re-scan. Preserve the three existing message variants
  (shadowed-by-`@table`, redundant/matches, disagrees) and the multi-producer-rejection
  suppression (`bindings.rejection(name)`). `readRecordClassName` and the directive
  declaration stay.
- **Retire the LSP's `@record` className tooling.** Stop the LSP treating `@record` as a
  live `ExternalCodeReference`-className binding: it must no longer offer className FQN
  completion (`ClassNameCompletions`) or raise the "Unknown class '…'"
  diagnostic (`Diagnostics.validateClassName`, `Diagnostics.java:631`) or hover it as a live
  binding (`Hovers.richerHover` → `classNameHover`, `Hovers.java:171`) for `@record`. The
  declaration is **retained** (still parses
  `@record(record: {className: ...})`), so this is a per-directive carve-out of the generic
  ECR-className handling, not a retyping of the argument. Gate the carve-out on the **enclosing
  directive name** (`"record"`), mirroring the established `Diagnostics.METHOD_VALIDATING_DIRECTIVES`
  pattern. Coordinate-based gating is **not** an option: all three surfaces dispatch off the
  shared `InputField("ExternalCodeReference", "className")` coordinate
  (`parsing/LspVocabulary.java:755`), which is identical for `@record` and `@enum`, so each site
  must read the enclosing directive name. Two of the three sites already have it: `validateClassName`
  (`Diagnostics.java:631`) does not currently receive the enclosing
  directive, but the `dispatch` site (`Diagnostics.java:408`) has it in scope and `validateMethod`
  already threads it, so the same thread-through applies; `Hovers.richerHover` receives `directive`
  directly (line 99) and can gate its `ClassNameBinding` arm the same way. The **completion** site
  needs new plumbing: `ClassNameCompletions.generate` receives only
  `CompletionContext(SchemaCoordinate, Range)`, which carries no directive identity and (per the
  shared coordinate above) cannot derive it; the implementer threads the enclosing directive name
  from `LspVocabulary.CursorLocation` into `CompletionContext` (or the provider call) so
  `ClassNameCompletions` can skip `@record`. This threading is in scope for this item. No `@deprecated` docstring token is added and the
  `deprecationOf` / `deprecatedCoordinates` pathway is **not** wired (it surfaces nothing at usage
  sites and would only force a `SdlActions.MANUAL_MIGRATION_DEPRECATIONS` entry for no DX gain);
  the editor's "ignored" signal is the generator `BuildWarning` already surfaced through
  `validatorDiagnostics`.
- **Remove every applied `@record` from test-fixture SDL** across both modules (generator
  and `graphitron-lsp`). For the generator-module binding-hint fixtures the type already binds
  via its `@service` producer / `@table`, so dropping `@record` is classification-neutral;
  migrate any fixture that leaned on `@record(record: {className: ...})` purely for binding
  onto the reflected form so its verdict is unchanged. The `graphitron-lsp` fixtures are
  rewritten, not merely stripped: each is repointed to assert the **absence** of className
  completion / unknown-class diagnostics / live-binding hover for `@record`, with one reachable
  fixture asserting the generator's ignored-directive `BuildWarning` surfaces as a usage-site
  `Warning` through `validatorDiagnostics`.
- **Replace pipeline-fixture warning coverage with a simple classifier-level test.** The
  deprecation warning bottoms out at classification, so a focused test that drives the
  classifier (one of the two legitimate places `@record` still appears in tests, the other
  being the rewritten `graphitron-lsp` carve-out / `validatorDiagnostics` fixtures) replaces the
  ignored-warning assertions currently piggybacking on `R96RecordBindingPipelineTest`,
  `GraphitronSchemaBuilderTest` (input `@table + @record`), and `BuildOutputReportPipelineTest`.
  Compilation/execution fixture tests are reserved for verifying generated-code shape and
  runtime behaviour, not classifier diagnostics.

## Out of scope

- Removing the `@record` directive *declaration* from `directives.graphqls`. `@record`
  stays a legal-but-ignored directive for now so existing consumer schemas keep parsing
  (this item only stops the editor offering className tooling for it); the hard removal of the
  declaration is a separate, later item once schemas are scrubbed.
- Wiring the `@deprecated` docstring-token / `deprecatedCoordinates` pathway for `@record`, and
  any presence-based "directive is deprecated" usage-site diagnostic (a new consumer of
  `deprecationOf(Directive(...))` in `Diagnostics.compute`'s `Bundled` arm). The editor's
  ignored signal is the reachability-gated generator `BuildWarning`; a presence-based signal for
  unreachable `@record` is a separate, optional enhancement. If filed, it carries the
  `@deprecated` token plus the matching `SdlActions.MANUAL_MIGRATION_DEPRECATIONS` entry.
- The legacy modules at the repo root.

## Done when

- (Part A, done) No rejection/error message recommends authoring `@record`; the
  replacement advice names the reflected-backing mechanism; no message uses the invalid
  `@record(class:)` form. "@record" no longer labels a record-backed *type* in main or
  test source.
- No standalone deprecation-warning emitter remains: `emitDirectiveIgnoredWarnings` is
  gone and the `@record`-ignored warning is produced by the classifier. The three message
  variants and the multi-producer-rejection suppression are preserved.
- The LSP no longer treats `@record` as a live `ExternalCodeReference`-className binding:
  no className FQN completion, no "Unknown class" diagnostic, no live-binding hover for
  `@record` (carve-out gated on the `"record"` directive name). The editor's ignored signal
  for `@record` is the generator `BuildWarning` surfaced through `validatorDiagnostics`; the
  `deprecationOf` / `deprecatedCoordinates` pathway is untouched.
- No applied `@record` remains in any test-fixture SDL across either module. The only
  `@record` left in test source is the minimal schema in the dedicated classifier-level
  warning test and the rewritten `graphitron-lsp` carve-out fixtures.
- The classifier warning is covered by a simple classifier-level test, not a
  compilation/execution fixture; the LSP carve-out and the `validatorDiagnostics` surfacing
  are covered by the rewritten `graphitron-lsp` fixtures.
- `@record` remains a declared directive (`directives.graphqls` declaration and
  `readRecordClassName` intact); nothing reads it to drive binding.
- Full pipeline build green (`mvn -f graphitron-rewrite/pom.xml install -Plocal-db`).

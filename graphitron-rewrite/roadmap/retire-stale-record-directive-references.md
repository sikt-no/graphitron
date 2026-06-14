---
id: R307
title: "Retire stale @record references: dead-directive advice, jargon, the deprecation-warning emitter, and test-fixture @record"
status: Spec
bucket: cleanup
priority: 5
theme: legacy-migration
depends-on: []
created: 2026-06-14
last-updated: 2026-06-14
---

# Retire stale @record references: dead-directive advice, jargon, the deprecation-warning emitter, and test-fixture @record

The `@record` directive is **DEPRECATED and IGNORED** (`directives.graphqls:288-297`):
it binds no type to a Java class and drives no behaviour, declared only so existing
schemas keep parsing. A reachable type carrying it gets a build warning telling the
author to remove it. `@record` **stays a legal-but-ignored directive for now**: the
`directives.graphqls` declaration is intentionally retained so existing consumer
schemas keep parsing. The only live reads of the directive name (`DIR_RECORD`,
`BuildContext.java:78`) are the schema-declaration assert (`GraphitronSchemaBuilder.java:561`),
the deprecation-warning machinery (`TypeBuilder.emitDirectiveIgnoredWarnings` at `:333`,
called from `:192`, plus `readRecordClassName` at `:403`, which reads the `className`
arg only to phrase the warning), and nothing else. Nothing reads `@record` to drive
binding.

Today that warning is produced by a **standalone emitter**: `emitDirectiveIgnoredWarnings`
re-walks the entire schema *after* classification looking for `@record`-carrying types.
The classifier already visits every type; this item folds the deprecation warning into
that single classification pass and deletes the separate emitter, so the signal bottoms
out at classification (and is testable there without a pipeline fixture).

The work has two parts.

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

**Part B (this re-spec) retires the standalone emitter and the applied `@record` in test
fixtures.** The production-shaped `graphitron-sakila-example/schema.graphqls` already
carries no applied `@record` (only comments). Applied `@record` survives only in
test-fixture SDL (72 occurrences across ~22 files), in two shapes: a handful exist solely
to exercise the deprecation-warning path (e.g. `R96RecordBindingPipelineTest:84-85`,
`GraphitronSchemaBuilderTest:4164-4165`, `BuildOutputReportPipelineTest:84`, all asserting
on the ignored-warning); the rest is legacy binding-hint decoration on types that actually
bind via reflection (a producing `@service` return type / `@table`), so the directive is
inert there. Both shapes go: the binding-hint fixtures drop `@record` (classification is
unchanged, since reflection already drives the binding), and the warning-path coverage
moves off pipeline fixtures onto a simple classifier-level test, because the deprecation
warning bottoms out at classification and needs no compilation/execution fixture to verify.

## Scope

### Part A — wording (landed on trunk)

- **Rewrote the five recommend-`@record` rejection messages** to point at the reflected
  backing path (a producing `@service` return type, `@table`, `@tableMethod`, a
  parent-accessor chain, or `@sourceRow`) instead of the dead directive, dropping the
  never-valid `@record(class:)` form; updated the assertions that pinned the old text.
- **Renamed the `@record`-as-jargon vocabulary** to "record-backed" across main and test
  source, in lockstep so the build stayed green. Where a message names the *type variant*,
  the variant name (Pojo / JavaRecord / JooqRecord / JooqTableRecord) is preferred.

### Part B — emitter relocation + fixture purge (remaining)

- **Delete the standalone `emitDirectiveIgnoredWarnings` pass** (`TypeBuilder:333`, call
  site `:192`) and register the `@record`-ignored deprecation warning from the classifier
  as it classifies each type, so the warning is a classification output rather than a
  post-classification re-scan. Preserve the three existing message variants
  (shadowed-by-`@table`, redundant/matches, disagrees) and the multi-producer-rejection
  suppression (`bindings.rejection(name)`). `readRecordClassName` and the directive
  declaration stay.
- **Remove every applied `@record` from test-fixture SDL** (all 72 occurrences). For the
  binding-hint fixtures the type already binds via its `@service` producer / `@table`, so
  dropping `@record` is classification-neutral; migrate any fixture that leaned on
  `@record(record: {className: ...})` purely for binding onto the reflected form so its
  verdict is unchanged.
- **Replace pipeline-fixture warning coverage with a simple classifier-level test.** The
  deprecation warning bottoms out at classification, so a focused test that drives the
  classifier (the one legitimate place `@record` appears in tests) replaces the
  ignored-warning assertions currently piggybacking on `R96RecordBindingPipelineTest`,
  `GraphitronSchemaBuilderTest` (input `@table + @record`), and `BuildOutputReportPipelineTest`.
  Compilation/execution fixture tests are reserved for verifying generated-code shape and
  runtime behaviour, not classifier diagnostics.

## Out of scope

- Removing the `@record` directive *declaration* from `directives.graphqls`. `@record`
  stays a legal-but-ignored directive for now so existing consumer schemas keep parsing;
  the hard removal of the declaration is a separate, later item once schemas are scrubbed.
- The legacy modules at the repo root.

## Done when

- (Part A, done) No rejection/error message recommends authoring `@record`; the
  replacement advice names the reflected-backing mechanism; no message uses the invalid
  `@record(class:)` form. "@record" no longer labels a record-backed *type* in main or
  test source.
- No standalone deprecation-warning emitter remains: `emitDirectiveIgnoredWarnings` is
  gone and the `@record`-ignored warning is produced by the classifier. The three message
  variants and the multi-producer-rejection suppression are preserved.
- No applied `@record` remains in any test-fixture SDL. The only `@record` left in test
  source is the minimal schema in the dedicated classifier-level deprecation-warning test.
- The deprecation warning is covered by a simple classifier-level test, not a
  compilation/execution fixture.
- `@record` remains a declared, legal-but-ignored directive (`directives.graphqls`
  declaration and `readRecordClassName` intact); nothing reads it to drive binding.
- Full pipeline build green (`mvn -f graphitron-rewrite/pom.xml install -Plocal-db`).

---
id: R535
title: "Remove the @tableMethod directive"
status: Spec
bucket: architecture
priority: 3
theme: model-cleanup
depends-on: []
created: 2026-07-25
last-updated: 2026-07-25
---

# Remove the `@tableMethod` directive

## Problem

R400 withheld `@tableMethod` from the v1 advertised surface but deliberately left the
declaration and machinery intact ("declared and behaviourally unchanged, just outside the
v1 surface"). That leaves a live gap: `directives.graphqls` still declares the directive
and no classify-time rejection exists, so a consumer schema can adopt an unadvertised,
undocumented directive today and generate working code we would then have to support.
User decision 2026-07-25: the directive is not supposed to be exposed at all, no consumer
uses it, so remove the declaration and the machinery now. `@routine` covers the need it
was chasing (per the R277 discard rationale in `changelog.md`).

This item replaces the migration framing this file was originally filed with (migrating
the inline `TableMethodField` leaf onto `TableExpr.MethodCall`, the R314 residue named in
R333's join-path section). With the directive removed, both the inline leaf and the
`MethodCall` node are deleted instead; the residue is discharged by removal.

## Design decisions

- **Full removal, not `REJECTED_ON_USE`.** R400's rejected set keeps a declaration so
  legacy schemas parse and get a friendly classify-time migration message. `@tableMethod`
  does not need that half-state: the migration report's "Legacy-only directives" section
  (`DirectiveSupportReport.renderMigration`) already renders any directive declared by
  legacy but not by the rewrite as removed-in-rewrite with a drop-before-migrating
  instruction, so deleting the declaration outright still leaves migrating consumers a
  clear message. A rewrite schema applying it fails schema validation as an unknown
  directive, which is the correct signal for a directive that was never advertised.
- **`TableExpr.MethodCall` dies with the directive.** Its only construction site is the
  DTO-parent `@tableMethod` arm (`FieldBuilder.java:6252`). The seal reverts to
  `Catalog | RoutineCall`, and the `JoinPathEmitter` wiring-bug guard for a `MethodCall`
  node at a general materialization site is deleted rather than lifted.
- **R403 stays** as the rethink-and-reintroduce parking spot; recovery is anchor-free via
  git history, per the R400 pattern. Update its notes to record that the declaration and
  machinery are now gone, so reintroduction is a fresh design, not a re-advertising edit.

## Scope sweep

1. **Declaration and docs.** Delete `@tableMethod` from `directives.graphqls`; sweep the
   factual mentions in sibling directive docstrings (`@field`, `@externalField`,
   `@tenantFanOut`, the `@record` migration note) and any remaining docs-site inline
   mentions (R400 Stage 2 already deleted the reference page). `legacy-directives.graphqls`
   is untouched: it documents the legacy surface, and its declaration is what routes the
   directive into the report's Legacy-only section.
2. **Resolution and model.** Delete `TableMethodDirectiveResolver`, the root leaf
   (`QueryField.QueryTableMethodTableField`), the child leaf
   (`ChildField.TableMethodField`), the DTO-parent batched arm in `FieldBuilder`
   (`FieldBuilder.java:6165` region), and `TableExpr.MethodCall`; sweep the remaining
   main-source files whose diagnostic ladders, javadoc, or switch arms name the directive
   or its types (about 23 files reference the vocabulary today; shared carriers such as
   `MethodRef` and `MethodBackedField` stay, only their `@tableMethod`-specific arms and
   mentions go).
3. **Emit.** Delete `TypeFetcherGenerator.buildChildTableMethodFetcher` and its dispatch
   rows; sweep `TypeClassGenerator` and `JoinPathEmitter`.
4. **Report policy.** Drop `tableMethod` from `WITHHELD_FROM_V1` in
   `DirectiveSupportReport` (shrinks to `sourceRow`, `experimental_constructType`);
   regenerate `supported-directives.adoc`; the derived exempt set in
   `DirectiveDocCoverageTest` follows automatically since the directive is no longer
   declared.
5. **Fixtures and tests.** Delete the three sakila fixtures (the `@tableMethod` root
   fetcher, `Inventory.filmViaTableMethod`, `Film.languageViaTableMethod`), their
   `GraphQLQueryTest` execution tests and the R277 `@Disabled` test,
   `SampleQueryService.tableMethodFilm` / `tableMethodLanguage`,
   `TableMethodFieldPipelineTest`, `TableMethodFieldValidationTest`,
   `QueryTableMethodTableFieldValidationTest`, `TestTableMethodStub`, and the
   `@tableMethod` rows in `ClassifiedCorpus` / `GraphitronSchemaBuilderTest`; sweep the
   remaining test files that mention the vocabulary.
6. **Roadmap state.** R288 stays as filed (already narrowed to the polymorphic-interface
   N+1 case, independent of `@tableMethod`); R277 is already Discarded; R333's join-path
   residue note about the inline leaf is discharged by this removal (update the living
   document's residue sentence when the item ships).

## Acceptance criteria

- `directives.graphqls` no longer declares `@tableMethod`; a schema applying it fails
  schema validation as an unknown directive.
- No main-source or test-source references to the retired vocabulary remain; the
  retirement sweep at the Done gate is clean.
- The migration report renders `tableMethod` under "Legacy-only directives" and nowhere
  else; the `directive-support --verify` drift guard is green.
- Full reactor green under `-Plocal-db`.

## Retired vocabulary

`@tableMethod`, `TableMethodDirectiveResolver`, `ChildField.TableMethodField`,
`QueryField.QueryTableMethodTableField`, `TableExpr.MethodCall`,
`buildChildTableMethodFetcher`, `TestTableMethodStub`, `TableMethodFieldPipelineTest`,
`TableMethodFieldValidationTest`, `QueryTableMethodTableFieldValidationTest`.

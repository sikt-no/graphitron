---
id: R400
title: "Remove the @tableMethod directive"
status: Spec
bucket: structural
priority: 5
theme: structural-refactor
depends-on: []
created: 2026-06-30
last-updated: 2026-06-30
---

# Remove the @tableMethod directive

`@tableMethod` lets a developer static Java method choose *which* jOOQ table backs a field's `SELECT`, selected at request time from GraphQL argument values (the documented use cases: sharded-by-tenant tables, archived-vs-live history tables). It is fully wired in the rewrite across three model leaves: `QueryField.QueryTableMethodTableField` (root), `ChildField.TableMethodField` (table-bound parent, a per-row **synchronous** fetcher, i.e. an N+1, flagged by R288), and `ChildField.RecordTableMethodField` (DTO/`@service`-produced parent, DataLoader-batched). The decision is to retire it. This item removes the directive, its model leaves, its resolver and reflection, its generator arms, and its fixtures, and points the one capability it uniquely served at `@service`.

## Why this is *not* the `@record` removal again

The `@record` retirement (R276 / doc-alignment R301) could make the directive **parse-but-ignore**: the backing Java class `@record` named was already reflection-derivable from the producing field, so ignoring it and deriving instead lost nothing, and the directive stayed declared purely so existing schemas keep loading. `@tableMethod` is the opposite: nothing else derives which table backs the field, so there is no reflection fallback. "Ignore" is not an option, an ignored `@tableMethod` field drops to `UnclassifiedField` and fails the build with a message about the wrong thing. Removing `@tableMethod` therefore retires a **real capability** (argument-driven table selection), not a vestigial annotation. The spec must say so honestly rather than frame this as a cleanup.

## The capability gap and the migration targets

State the gap precisely rather than paper it with a blanket "use `@service`". What is genuinely retired is **request-time choice of which generated jOOQ `Table<?>` singleton backs a graphitron-projected `SELECT`, chosen by arbitrary Java over argument values, with selection-narrowing (`$fields`) preserved.** Nothing else does exactly that. There are two migration targets covering different subsets:

- **`@routine` (R300)** is the faithful target for the subset where the table choice is expressible as a parameterised database routine (`Routines.x(args)`). It is the database twin of `@tableMethod`'s `MethodRef` and projects the same bare `TargetShape.Table` (`QueryField.QueryRoutineTableField`, `model/QueryField.java:141-165`), so graphitron's selection-narrowing **still applies**. This is the projection-preserving escape hatch and should be named first.
- **`@service`** covers the general case (the reference page's "the alternative when the developer supplies the *entire* fetcher rather than just the source table"). The cost is real: a `@service` field is a private scope with **no graphitron column projection**, so the developer owns the whole fetcher, and at child-on-`@table`-parent sites also owns the parent correlation `@reference` used to provide.

So: `@routine` for the DB-expressible subset (keeps projection), `@service` for the rest (loses projection). The child-`@table`-parent shape is the heaviest migration *and* is the per-row N+1 that R288 already wanted gone; retiring it rather than fixing it is defensible but is a capability decision for the user, surfaced here for the Spec → Ready reviewer to confirm.

## Removal model (design fork; recommend for Spec → Ready)

Two faithful shapes. The right precedent here is **not** `@record` (parse-but-*ignore*, which only works because that directive was redundant), but `@notGenerated` and `@multitableReference`, the two directives this project has already retired by keeping them declared and raising a typed rejection:

- **(a) Parse-but-reject (recommended).** Keep the directive **declared** in `directives.graphqls` (reframed "Removed. … no longer supported", the `@notGenerated` doc-comment shape at `directives.graphqls:4-10`), so SDL carrying it still parses, but `FieldBuilder` raises a typed `InvalidSchema.Structural` rejection before conflict detection (the existing `@notGenerated` / `@multitableReference` arm, `FieldBuilder.java:2072-2087`) carrying the migration message. Route it through `INVALID_SCHEMA`, **not** `AUTHOR_ERROR`: per `typed-rejection.adoc`, `INVALID_SCHEMA` "prompts the author to drop or replace a directive entirely" (exactly this case) whereas `AUTHOR_ERROR` "prompts the author to edit". The consumer gets a directed message and a typed `Rejection` the LSP fix-it consumes, not a downstream `UnclassifiedField`. Forward-compatible with R398's SDL lint engine (a deprecated-directive visitor projects the same message edit-time).
- **(b) Hard removal.** Delete the declaration too; a schema using `@tableMethod` fails at graphql-java *parse* with a generic "unknown directive" before the classifier runs. Smaller diff, but the error is undirected and there is no typed `Rejection` for the LSP or R398 to project.

Recommendation: **(a)**, and explicitly model it on the `@notGenerated` retirement rather than the `@record` one. The error quality, the typed `Rejection`, and the R398 lint seam are worth the one retained declaration block plus the one rejection arm. Either way the implementation (model leaves, resolver, reflection, emit, fixtures, tests) is deleted; the fork only governs the front door.

## Implementation seams

Delete, except where noted. The sealed-hierarchy exhaustiveness is the safety net: deleting a leaf without deleting its switch arms is a compile error, so the compiler drives most of this once the leaves go.

1. **Model leaves** ; `QueryField.QueryTableMethodTableField` (`model/QueryField.java:128-139`), `ChildField.TableMethodField` (`model/ChildField.java:538-550`), `ChildField.RecordTableMethodField` (`model/ChildField.java:564-587`). Remove from the sealed `permits` clauses; drop the three `IMPLEMENTED_LEAVES` entries (`TypeFetcherGenerator.java:194,223,224`) so `GeneratorCoverageTest` stays exhaustive. The three classify sites that build them: `FieldBuilder.classifyQueryField` (root, ~3669), `classifyChildFieldOnTableType` (~5874), the class-backed-parent arm (~4753).
2. **Validator mirror (delete in this slice, not a follow-up)** ; the three dedicated methods `validateQueryTableMethodTableField` / `validateTableMethodField` / `validateRecordTableMethodField`, wired through the leaf-exhaustive switch at `GraphitronSchemaValidator.java:173,206-207,601,764-768`. Deleting the permits without these is a compile error.
3. **Resolver** ; `TableMethodDirectiveResolver.java` (whole file: `Resolved.TableBound` / `Resolved.Rejected`, the directive-arg parse at 107-119) and its call sites (`DIR_TABLE_METHOD` constant `BuildContext.java:90`, `FieldBuilder.java:122`).
4. **Reflection** ; `ServiceCatalog.reflectTableMethod` and the strict-`ClassName.equals` table-token check at `ServiceCatalog.java:498`, plus the `(MethodRef.StaticOnly)` cast site in the resolver. **Correction to a tempting over-delete: `MethodRef.StaticOnly` SURVIVES.** It is also constructed by `ServiceCatalog.reflectExternalField` (~679) for `@externalField` / `ComputedField` and the enum-mapping wrappers (`MethodRef.java:138,162-179`); only `reflectTableMethod` dies. Same trap as #5.
5. **Shared types ; keep, don't over-delete.** (a) `ReturnTypeRef.TableBoundReturnType` is also the `NestingField` / `@routine` return carrier; survives. (b) `MethodRef.StaticOnly`, per #4. (c) `WithErrorChannel` / `ErrorChannel` and the channel resolution at `FieldBuilder.java:~2909` are shared with `@service`; delete only the `@tableMethod` *callers*, not the capability.
6. **Generator + model-rewrite + body-variant arms** ; `TypeFetcherGenerator.buildQueryTableMethodFetcher` (1176-1234), `buildChildTableMethodFetcher` (1317-1390), the `RecordTableMethodField` dispatch (532-535) + its `SplitRowsMethodEmitter.buildForRecordTableMethod` arm, the case dispatches at 443/531, the `TableMethodField` arm in `TypeClassGenerator.collectRequiredProjectionColumns`, **`MappingsConstantNameDedup.java:201-212`** (the model-rewrite pass reconstructs all three leaves), and **`RowsMethodBody.SqlRecordTableMethod`** (`model/RowsMethodBody.java:82`, the dedicated body variant for `RecordTableMethodField`). `buildRecordBasedDataFetcher` is **shared** with `RecordTableField` ; remove only the `RecordTableMethodField` routing into it, not the method.
7. **LSP projection (its own sealed hierarchy)** ; `catalog/FieldClassification.java` carries a parallel sealed taxonomy with `TableMethod` / `QueryTableMethod` variants (`:50,62,261,375`) plus the `CatalogBuilder` projection. This is a sub-decision: does `TableMethod` collapse out entirely, or does the LSP keep a variant to *project the now-rejected directive as a diagnostic*? Recommend collapsing it out and letting the typed `Rejection` (seam 8) carry the edit-time signal, but flag for the reviewer.
8. **Front door (fork (a))** ; reframe the `directives.graphqls:390-409` declaration as "Removed / no longer supported" (the `@notGenerated` shape) and add the `InvalidSchema.Structural` rejection arm at `FieldBuilder.java:2072-2087` where the resolver was called. Under fork (b), delete the declaration instead.
9. **Fixtures** ; `Query.popularFilms` (`schema.graphqls:275-281`), `Inventory.filmViaTableMethod` (905-908), `Film.languageViaTableMethod` (1176-1179), the deferred-`NestingField` comment block (917-920), and the `SampleQueryService` methods (`popularFilms` / `tableMethodFilm` / `tableMethodLanguage`). Removing these is the execution/compilation-tier proof the capability is gone.
10. **Docs (mirror R301)** ; rewrite `docs/manual/reference/directives/tableMethod.adoc` as a removed/unsupported page with the `@routine` + `@service` migration, drop it from the directive indexes, add it to `deprecations.adoc`, scrub `@tableMethod` from the reflection-derivation list in `record.adoc` and the `@record` ignored-directive blurb (`directives.graphqls:300`), and from the `external-code.adoc` how-to's shared classpath note. Regenerate `supported-schema-shapes.adoc` (leaf-coverage) after the leaves are deleted.

## Dependent roadmap items

- **R277** (`tablemethod-under-nested-type`) ; purely `@tableMethod`. **Discard as superseded** (the R121/R296 → R398 precedent: retire the file, note it here, do not reuse the ID).
- **R288** (`inline-interface-and-tablemethod-children`) ; the `@tableMethod` half (the N+1 child fetcher this item deletes) goes; the `TableInterfaceField` inlining half is independent and **stays**. Re-scope R288 to the interface case only, or split.
- **R11** (`DSLContext on @condition / @tableMethod`) ; the `@tableMethod` half evaporates; the `@condition` half survives. Trim its body.
- **R240** (`type-token threading on MethodRef.StaticOnly + ReturnTypeRef.TableBoundReturnType`) ; `MethodRef.StaticOnly` is deleted by this item, so the `@tableMethod` motivation is gone; the service-catalog return-type-token half (`MethodRef.Service`) survives. Trim or discard.

Coordinate the trims when this lands; none block it.

## Suggested slicing (subtractive, compile-driven)

This is a deletion, and only one slice changes user-visible behaviour. Mirroring how R276 sliced the `@record` deletions:

1. **Behaviour slice** ; land the directive-level `InvalidSchema.Structural` rejection in `FieldBuilder` (the `@notGenerated` shape) and flip `TableMethodFieldPipelineTest` / the two validation tests to assert the rejection. This is the user-visible change and earns the one new pipeline/validation test.
2. **Leaf + arms** ; delete the three leaves, their three validator methods, `RowsMethodBody.SqlRecordTableMethod`, the `MappingsConstantNameDedup` arms, and the generator arms. Compile-driven: the exhaustive switches tell you what to touch.
3. **Resolver + reflection + projection + fixtures** ; delete `reflectTableMethod`, `TableMethodDirectiveResolver`, the LSP `FieldClassification.TableMethod` / `QueryTableMethod` projection, the fixtures + `TestTableMethodStub` + `SampleQueryService` methods, and the docs.

Slices 2-3 are mechanical subtraction; if the item is taken as one commit that is fine, the slicing is guidance for review legibility, not a hard partition.

## Tests

- **Delete** `TableMethodFieldPipelineTest`, `TableMethodFieldValidationTest`, `QueryTableMethodTableFieldValidationTest`, `TestTableMethodStub`, and the `SampleQueryService` `@tableMethod` methods.
- **Add (fork (a))** a validator/pipeline-tier assertion that a schema carrying `@tableMethod` is **rejected with the directed migration message** (typed `InvalidSchema.Structural`), not silently dropped to `UnclassifiedField`. This is the one piece of *new* coverage; everything else is subtraction. Per `rewrite-design-principles.adoc`, assert on the rejection/diagnostic, not on generated-body strings.
- **Compilation/execution** ; `mvn -f graphitron-rewrite/pom.xml install -Plocal-db` green over `graphitron-sakila-example` after the three fixtures and their service methods are removed, proving nothing in the reachable schema still depends on the capability.

## Out of scope

- **Building a replacement for argument-driven table selection.** The migration is `@routine` (DB-expressible subset, keeps projection) and `@service` (the rest, loses projection); no new directive.
- **Fixing the child-`@table`-parent N+1 (R288's interface half).** Independent; survives this removal.
- **The `@condition` / `@service` halves of R11 / R240.** Only their `@tableMethod` motivation is removed here.

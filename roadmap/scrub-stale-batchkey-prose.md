---
id: R126
title: "Scrub residual BatchKey.X references from sakila-service / sakila-example prose"
status: Spec
bucket: cleanup
priority: 9
theme: docs
depends-on: []
last-updated: 2026-07-19
---

# Scrub residual BatchKey.X references from sakila-service / sakila-example prose

Re-grounded 2026-07-19 against the current tree; the original inventory (see git history of this file) was written in the R38 era and has largely been overtaken. Most sites it named (`FilmService`, `FilmCardData`, `InventoryExtensions`, `CreateFilmsPayload{,Service}` carrying `BatchKey.MappedRecordKeyed` / `MappedTableRecordKeyed` / `AccessorKeyedSingle` / `AccessorKeyedMany`) are already clean, and its proposed replacement mapping is itself stale: it targets an `enum Cardinality` and a `deriveBatchKeyFromTypedAccessor` method that no longer exist. What survives is a smaller, precisely-located residue plus a fixture-rename decision.

## What is actually rotten

The sealed class `BatchKey` (its `RecordParentBatchKey` permits `AccessorKeyedSingle` / `AccessorKeyedMany`, and `LifterPathKeyed`) no longer exists; it was dissolved into `SourceKey` (`model/SourceKey.java`) carrying a `Reader` and a `Cardinality`. Prose that names `BatchKey` as a type is therefore describing a deleted symbol. This is distinct from the live orthogonal interface `BatchKeyField` (`model/BatchKeyField.java`), which is used correctly throughout main and test sources and is **out of scope**: only `BatchKey.X` class vocabulary is stale.

Current in-scope sites (grep `BatchKey` under `graphitron-sakila-service` / `graphitron-sakila-example`, source only, minus `BatchKeyField`):

* `graphitron-sakila-example/.../test/internal/AccessorDerivedBatchKeyTest.java`: prose "accessor-derived BatchKey path" (`:23`); a dead `{@link no.sikt.graphitron.rewrite.model.BatchKey.AccessorKeyedMany}` (`:28`); the class name itself (`:44`, and the file name).
* `graphitron-sakila-example/src/main/resources/graphql/schema.graphqls`: SDL `#` comments naming `BatchKey.AccessorKeyedMany` / `AccessorKeyedMany` (`:572`, `:574`, `:701`, `:705`), `BatchKey.LifterPathKeyed` (`:725`), and `AccessorKeyedSingle BatchKey` / "auto-derives the BatchKey" (`:1233`, `:1277`).
* `graphitron-sakila-example/.../test/querydb/GraphQLQueryTest.java`: comment prose "Phase A plumbing (BatchKey, IMPLEMENTED_LEAVES, ...)" (`:585`) and "AccessorKeyedSingle BatchKey" (`:691`).

The dead `{@link}` at `AccessorDerivedBatchKeyTest:28` does not fail the build because `graphitron-sakila-example/pom.xml` sets `maven.javadoc.skip=true`, opting the module out of the parent pom's `{@link}`-reference gate. It is dangling-but-tolerated, exactly the "noise for readers, trips code search" the item targets.

## The replacement vocabulary (corrected)

Describe the current behaviour with current terms rather than the item's stale mapping. The canonical in-tree template already sits in the same schema file: the R366 `OccupantsBatchPayload` comment (`schema.graphqls`, the `# ...` block above `type OccupantsBatchPayload`) reads `SourceKey(Reader.AccessorCall, Cardinality.MANY)` with `loader.loadMany` dispatch. Mirror it:

* `BatchKey.AccessorKeyedMany` / `AccessorKeyedMany` prose → `SourceKey(Reader.AccessorCall, Cardinality.MANY)`, `loader.loadMany` dispatch.
* `AccessorKeyedSingle BatchKey` prose → `SourceKey(Reader.AccessorCall, Cardinality.ONE)`, `loader.load` dispatch.
* `BatchKey.LifterPathKeyed` prose → the path-keyed lift shape stated in current terms (a `@sourceRow` + `@reference` lifter walking the FK chain to the leaf table); name the live carrier if one is cited, otherwise state the fact without a dead type name.
* The dead `{@link ...BatchKey.AccessorKeyedMany}` at `:28` → a live `{@link}` (e.g. to `SourceKey`) or `{@code}` if the precise variant is not worth a resolvable link; do not leave it dangling.
* `GraphQLQueryTest:585`'s "Phase A plumbing (BatchKey, ...)" drops the deleted-era phase/type name-drop and states the end-to-end fact directly.

No roadmap-id citation replaces any of these (main + test source comments, scanned by `RoadmapReferenceGuardTest`).

## Fixture rename

`AccessorDerivedBatchKeyTest` is a public test-fixture name that embeds the dead type, so it perpetuates the vocabulary in a discoverable symbol. Recommend renaming to `AccessorDerivedSourceTest`, which parallels the existing pipeline-tier fixture `AccessorDerivedSourceCase` (`GraphitronSchemaBuilderTest`) and matches the current `SourceKey` model. The rename ripples to three prose references that name the class:

* `graphitron-sakila-service/.../test/services/CreateFilmsPayloadService.java` (`:9`)
* `graphitron-sakila-service/.../test/services/CreateFilmsPayload.java` (`:13`)
* `graphitron-sakila-example/README.md` (`:62`)

The rename widens the blast radius past a pure comment scrub, so it is the reviewer's call to confirm or drop; if dropped, leave the class name and refresh only the Javadoc body (`:23`, `:28`). `roadmap/changelog.md`'s historical entries naming `AccessorDerivedBatchKeyTest` are append-only provenance and are **not** touched either way.

## Scope, non-goals, coverage

* The parallel `BatchKey.X` residue in the author-facing user manual (`docs/manual/result-types.adoc:58`, `handle-services.adoc:125`/`:174`, `split-vs-inline.adoc:12`, `migrating-from-legacy.adoc:40`) is a different, higher-visibility surface and stays out of this item's filed scope. Capture it as a sibling Backlog item rather than widening this one.
* `BatchKeyField` references (the live interface) stay untouched everywhere.
* Pure doc-rot: no production-model, generated-output, or test-coverage change. The `AccessorDerivedBatchKeyTest` / `GraphQLQueryTest` bodies keep asserting exactly what they assert today; only comment/Javadoc/SDL-comment prose (and, if the rename is taken, the class identifier and its three name references) change. The tests remain their own enforcer.

---
id: R126
title: "Scrub deleted classification-vocabulary from prose (BatchKey / Reader / SourceKey.Cardinality / Mapped*Keyed)"
status: Spec
bucket: cleanup
priority: 9
theme: docs
depends-on: []
last-updated: 2026-07-19
---

# Scrub deleted classification-vocabulary from prose

Re-grounded 2026-07-19 against the current tree. The rot is not just `BatchKey.X`: it is a whole family of deleted classification vocabulary (`BatchKey` permits, `Reader.AccessorCall`, `SourceKey.Cardinality`, `Mapped*Keyed`) that still appears in fixture prose and, more visibly, in the author-facing user manual. This is one clean item covering every surface; nothing is deferred to a sibling.

An earlier pass of this spec had its own instance of the bug it targets: it prescribed rewriting dead prose as `SourceKey(Reader.AccessorCall, Cardinality.MANY)` and pointed at a schema-file comment as the "canonical template" to mirror. Both name deleted symbols. This revision replaces that mapping with verified live symbols and folds the mis-cited "template" comment into scope as co-located rot.

## Dead vocabulary and its live successors

None of these dead symbols exist as types in main sources (grep confirms zero non-`@code`/`@link` hits). Rewrite prose that names them onto the verified live successor:

* `BatchKey.AccessorKeyedSingle` / `AccessorKeyedMany` and `Reader.AccessorCall` -> `KeyLift.Accessor(AccessorRef accessor, Arity arity)` (`model/KeyLift.java:73`), with `Arity.ONE` (`loader.load` dispatch) or `Arity.MANY` (`loader.loadMany`). `Reader` is not a live type.
* `BatchKey.LifterPathKeyed` -> `KeyLift.Lifter(LifterRef)` (`model/KeyLift.java:60`); the `@sourceRow` + `@reference` path-keyed lift.
* `SourceKey.Cardinality`, bare `Cardinality.ONE` / `Cardinality.MANY` -> `Arity.ONE` / `Arity.MANY` (`model/Arity.java`, whose javadoc records the retirement: "the surviving half of the retired `SourceKey.Cardinality`").
* `Mapped{Row,Record,TableRecord}Keyed` (the `@service` `Set<Row/Record/TableRecord>` source shapes) -> `SourcesShape(SourceKey.Wrap.{Row,Record,TableRecord}, LoaderRegistration.Container)` from `ServiceCatalog.classifySourcesType` (`ServiceCatalog.java:943`, `:959`).

`SourceKey` itself is live, but its shape is `record SourceKey(List<ColumnRef> columns, SourceKey.Wrap wrap)` (`model/SourceKey.java:40`): it carries no `Reader` and no `Cardinality`. Prose spelling a classification as `SourceKey(Reader.AccessorCall, Cardinality.MANY)` therefore mis-names its components as well as citing dead types. Describe the classification as the `KeyLift.Accessor` verdict (single vs list = `Arity`), not as `SourceKey` constructor arguments.

Live and **out of scope** (do not touch): `BatchKeyField` (the orthogonal DataLoader-marker interface, used correctly throughout), `SourceKey.Wrap`, `Arity`, `LoaderRegistration.Container`.

## Surfaces and sites

Line numbers drift; the implementer runs a final sweep grep (`grep -rnE 'BatchKey\.[A-Z]|Reader\.AccessorCall|Cardinality\.(ONE|MANY)|AccessorKeyed|LifterPathKeyed|Mapped(Row|Record|TableRecord)Keyed|SourceKey\.Cardinality' --include=*.java --include=*.graphqls --include=*.adoc --include=*.md`, minus `/target/` and minus live `BatchKeyField`) and confirms zero in-scope hits at the end. The confirmed sites at re-grounding time:

* **sakila-service** prose: `OccupantsBatchPayload.java:13`, `OccupantsBatchPayloadService.java:10` (`{@code Reader.AccessorCall}` + `{@code Cardinality.MANY}`). Plus, if the fixture rename below is taken, the two files that name the test class: `CreateFilmsPayloadService.java:9`, `CreateFilmsPayload.java:13`.
* **sakila-example** main SDL comments in `src/main/resources/graphql/schema.graphqls`: the `#` blocks at (re-grounding) `:574`, `:576`, `:600`-`:601`, `:701`, `:704`, `:713`, `:725`, `:1233`, `:1242`, `:1275`, `:1280`, `:1439`, `:1449`, `:2728`, `:2941`.
* **sakila-example** test comments: `AccessorDerivedBatchKeyTest.java` (`:23` prose, `:28` dead `{@link no.sikt.graphitron.rewrite.model.BatchKey.AccessorKeyedMany}`); `GraphQLQueryTest.java` (`:463`-`:464`, `:486`, `:585`, `:691`, `:700`, `:736`); `DmlBulkMutationsExecutionTest.java:578`.
* **docs/manual** (author-facing, higher visibility): `how-to/result-types.adoc:56`/`:58`/`:94`, `how-to/computed-fields.adoc:107`/`:137`, `how-to/handle-services.adoc:125`/`:174`/`:206`/`:207`/`:208`, `reference/directives/externalField.adoc:65`. `handle-services.adoc:174` additionally carries a stale line range (`ServiceCatalog.classifySourcesType:600-639`); re-point it to the live method location or drop the range.

The dead `{@link}` at `AccessorDerivedBatchKeyTest:28` does not fail the build: `graphitron-sakila-example/pom.xml` sets `maven.javadoc.skip=true`, opting the module out of the parent pom's `{@link}`-reference gate. Repoint it to a live `{@link}` (e.g. `KeyLift.Accessor`) or downgrade to `{@code}` per the gate rule; do not leave it dangling.

Where prose uses "BatchKey" as a bare concept word ("accessor-derived BatchKey path", "auto-derives the BatchKey", "Phase A plumbing (BatchKey, ...)"), rephrase to the live vocabulary (the accessor-keyed `DataLoader` path / the `KeyLift.Accessor` verdict) rather than keeping a CamelCase name that reads as the deleted type.

## Identifier renames (reviewer's call on how far)

Two discoverable symbols embed dead type names and so keep the vocabulary alive in code search:

* Test class `AccessorDerivedBatchKeyTest` -> `AccessorDerivedSourceTest`, paralleling the pipeline-tier `AccessorDerivedSourceCase` (`GraphitronSchemaBuilderTest`). Ripples to `CreateFilmsPayloadService.java:9`, `CreateFilmsPayload.java:13`, `graphitron-sakila-example/README.md:62`.
* Test method `inventoryById_filmCardData_firesAccessorKeyedSingleLiftThroughCustomJavaRecord` (`GraphQLQueryTest.java:687`) embeds `AccessorKeyedSingle`. Rename to drop the dead type (e.g. `..._firesAccessorLiftThroughCustomJavaRecord`); it is cited by name in `docs/manual/how-to/computed-fields.adoc:137`, which updates in lockstep.

The identifier renames widen the blast radius past a pure prose scrub, so the reviewer confirms or drops them; the prose scrub proceeds either way. If dropped, refresh the affected Javadoc/comment bodies but keep the identifiers. `roadmap/changelog.md`'s historical entries naming these identifiers are append-only provenance and are **not** touched under either choice.

## Scope, non-goals, coverage

* One item, all surfaces (sakila-service, sakila-example, docs/manual); no sibling deferral.
* `BatchKeyField` and the other live vocabulary above stay untouched everywhere.
* Pure doc / identifier rot: no production-model, generated-output, or test-behaviour change. Test bodies keep asserting exactly what they assert today; only prose (and, if the renames are taken, the two identifiers and their references) change. The tests remain their own enforcer, so no new coverage is added.
* No roadmap-id citation replaces any comment (main + test source comments are scanned by `RoadmapReferenceGuardTest`). The `.adoc` edits use no em dashes, per the writing-style rule.

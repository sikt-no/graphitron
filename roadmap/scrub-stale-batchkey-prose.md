---
id: R126
title: "Scrub deleted classification-vocabulary from prose (BatchKey / Reader / SourceKey.Cardinality / Mapped*Keyed)"
status: In Review
bucket: cleanup
priority: 9
theme: docs
depends-on: []
last-updated: 2026-07-19
---

# Scrub deleted classification-vocabulary from prose

Re-grounded 2026-07-19 against the current tree. The rot is not just `BatchKey.X`: it is a whole family of deleted classification vocabulary (`BatchKey` permits, the retired four-arm `Reader` family `ColumnRead` / `SourceRowsCall` / `AccessorCall` / `ProducedRecordRead`, `SourceKey.Cardinality`, `Mapped*Keyed`) that still appears in fixture prose and, more visibly, in the author-facing user manual. This is one clean item covering every surface; nothing is deferred to a sibling.

An earlier pass of this spec had its own instance of the bug it targets: it prescribed rewriting dead prose as `SourceKey(Reader.AccessorCall, Cardinality.MANY)` and pointed at a schema-file comment as the "canonical template" to mirror. Both name deleted symbols. This revision replaces that mapping with verified live symbols and folds the mis-cited "template" comment into scope as co-located rot.

The Spec review pass (2026-07-19) widened the inventory again: the previous revision's sweep pattern only caught the dotted `Reader.AccessorCall` spelling, so the undotted `Reader`-arm vocabulary (`ColumnRead`, `SourceRowsCall`, bare `AccessorCall`, `ProducedRecordRead`) went unswept, and with it two whole surfaces (the `graphitron` module's own main and test sources, and `docs/architecture/`) plus additional sites in the surfaces already listed. The retired `Reader` had four arms; each maps one-to-one onto a live `KeyLift` arm, which is what makes the scrub mechanical.

## Dead vocabulary and its live successors

None of these dead symbols exist as types in main sources (grep confirms zero non-`@code`/`@link` hits). Rewrite prose that names them onto the verified live successor:

* `BatchKey.AccessorKeyedSingle` / `AccessorKeyedMany` and `Reader.AccessorCall` (also spelled bare, `AccessorCall`) -> `KeyLift.Accessor(AccessorRef accessor, Arity arity)` (`model/KeyLift.java:73`), with `Arity.ONE` (`loader.load` dispatch) or `Arity.MANY` (`loader.loadMany`). `Reader` is not a live type.
* `BatchKey.LifterPathKeyed` and `SourceRowsCall` -> `KeyLift.Lifter(LifterRef)` (`model/KeyLift.java:60`); the `@sourceRow` + `@reference` path-keyed lift.
* `ColumnRead` (the catalog-FK `Reader` arm) -> `KeyLift.FkColumns()` (`model/KeyLift.java:52`).
* `Reader.ProducedRecordRead` / bare `ProducedRecordRead` (the source=target re-fetch read) -> `KeyLift.ProducedRecords(Arity)` (`model/KeyLift.java:90`).
* `SourceKey.Cardinality`, bare `Cardinality.ONE` / `Cardinality.MANY` -> `Arity.ONE` / `Arity.MANY` (`model/Arity.java`, whose javadoc records the retirement: "the surviving half of the retired `SourceKey.Cardinality`").
* `Mapped{Row,Record,TableRecord}Keyed` (the `@service` `Set<Row/Record/TableRecord>` source shapes) -> `SourcesShape(SourceKey.Wrap.{Row,Record,TableRecord}, LoaderRegistration.Container)` from `ServiceCatalog.classifySourcesType` (`ServiceCatalog.java:943`, `:959`).

`SourceKey` itself is live, but its shape is `record SourceKey(List<ColumnRef> columns, SourceKey.Wrap wrap)` (`model/SourceKey.java:40`): it carries no `Reader` and no `Cardinality`. Prose spelling a classification as `SourceKey(Reader.AccessorCall, Cardinality.MANY)` therefore mis-names its components as well as citing dead types. Describe the classification as the `KeyLift.Accessor` verdict (single vs list = `Arity`), not as `SourceKey` constructor arguments.

Live and **out of scope** (do not touch): `BatchKeyField` (the orthogonal DataLoader-marker interface, used correctly throughout), `SourceKey.Wrap`, `Arity`, `LoaderRegistration.Container`.

## Surfaces and sites

Line numbers drift; the implementer runs a final sweep grep (`grep -rnE 'BatchKey\.[A-Z]|Cardinality\.(ONE|MANY)|AccessorKeyed|LifterPathKeyed|Mapped(Row|Record|TableRecord)Keyed|SourceKey\.Cardinality|AccessorCall|SourceRowsCall|ProducedRecordRead|\bColumnRead\b' --include=*.java --include=*.graphqls --include=*.adoc --include=*.md`, minus `/target/` and minus `roadmap/`) and confirms the only remaining hits are the enumerated retirement-provenance mentions below. Live `BatchKeyField` does not match the pattern and needs no exclusion.

**Retirement-provenance carve-out.** A mention that explicitly frames the vocabulary as retired ("the retired ...", "the old compact-constructor pairings", "the former ...") is documentation of the retirement itself, not rot, and stays. The allowed residue at re-grounding time: `model/Arity.java:17`, `model/Target.java:10`, `model/KeyLift.java:37`-`:38`, and the test-side dispositions `model/KeyLiftTest.java:16`-`:18`, `:57`, `:69` and `model/SourceKeyTest.java:17`-`:22`. Everything else the sweep hits must be rewritten; a present-tense claim that names a dead arm as the thing the classifier produces today is rot even when it sits next to live symbols.

The confirmed rot sites at re-grounding time:

* **graphitron main sources** (comment/javadoc rot in the generator itself): `FieldBuilder.java:3927`-`:3928` (`Cardinality.ONE`/`MANY`), `:5860` (`LifterPathKeyed`), `:6514` (bare `AccessorCall`), `:6648` + `:6653`-`:6654` (the mis-shaped `SourceKey(Wrap.Row, parent.PK, ColumnRead, Cardinality.ONE)` constructor prose this spec diagnoses, plus `AccessorCall` + Single/Many); `model/MutationField.java:564` and `generators/TypeFetcherGenerator.java:6078` (`{@code Cardinality.MANY} arm`, live vocabulary is the `Arity.MANY` arm); `generators/SplitRowsMethodEmitter.java:244`-`:245` (present-tense `ColumnRead` / `SourceRowsCall` / `AccessorCall`); `model/ChildField.java:787`-`:789` and `generators/MultiTablePolymorphicEmitter.java:511`-`:513` (`{@code ColumnRead}`-reader parent keys, "lifter and accessor reader permits").
* **graphitron main-source string literal**: `model/ParentCorrelation.java:198`-`:200`, an invariant-throw message naming `SourceRowsCall, AccessorCall, ProducedRecordRead` as the delegating arms; rewrite onto the live `KeyLift` arm names. Message-text-only edit, no behaviour change; this is the consumer-rendering habitat, so it is the highest-priority main-source site.
* **graphitron test sources**: `GraphitronSchemaBuilderTest.java` case-description strings `:2727`, `:2759` (`LifterPathKeyed`), `:3307`, `:3334`, `:3357` (`AccessorCall` + `Cardinality.*`), comment `:6988` (`ProducedRecordRead`); `ServiceCatalogTest.java:386`-`:387` comments (`Mapped*Keyed`); `ServiceFieldValidationTest.java:281`, `:287` case strings; `InterfaceFieldValidationTest.java:202`-`:204` comment (`ColumnRead` case); `RecordParentMultiTablePolymorphicPipelineTest.java:40`, `:75` (`ColumnRead`), `:138`, `:166` (`AccessorKeyedSingle`/`Many`); `SingleRecordTableFieldServiceProducerPipelineTest.java:24` (dead `{@link SourceKey.Reader.ProducedRecordRead}`), `:54`, `:69`; `SingleRecordPayloadPipelineTest.java:103` (`Reader.ProducedRecordRead`); `generators/TypeFetcherGeneratorTest.java:2129` (`ColumnRead`).
* **sakila-service** prose: `OccupantsBatchPayload.java:13`, `OccupantsBatchPayloadService.java:10` (`{@code Reader.AccessorCall}` + `{@code Cardinality.MANY}`); `NestedFilmsPayloadService.java:11` and `NestedFilmsPayloadHolder.java:13`-`:14` (`{@code AccessorCall}`-keyed, list-cardinality `SourceKey` claims; the live verdict is `KeyLift.Accessor` + `Arity.MANY`). Plus, if the fixture rename below is taken, the two files that name the test class: `CreateFilmsPayloadService.java:9`, `CreateFilmsPayload.java:13`.
* **sakila-example** main SDL comments in `src/main/resources/graphql/schema.graphqls`: the `#` blocks at (re-grounding) `:418`, `:574`, `:576`, `:600`-`:601`, `:701`, `:704`, `:713`, `:725`, `:1018`, `:1233`, `:1242`, `:1275`, `:1280`, `:1439`, `:1449`, `:2728`, `:2941`.
* **sakila-example** test comments: `AccessorDerivedBatchKeyTest.java` (`:23` prose, `:28` dead `{@link no.sikt.graphitron.rewrite.model.BatchKey.AccessorKeyedMany}`); `GraphQLQueryTest.java` (`:463`-`:464`, `:486`, `:585`, `:691`, `:700`, `:736`); `DmlBulkMutationsExecutionTest.java:578`.
* **docs/manual** (author-facing, higher visibility): `how-to/result-types.adoc:56`/`:58`/`:94`, `how-to/computed-fields.adoc:107`/`:137`, `how-to/handle-services.adoc:125`/`:174`/`:206`/`:207`/`:208`, `reference/directives/externalField.adoc:65`. `handle-services.adoc:174` additionally carries a stale line range (`ServiceCatalog.classifySourcesType:600-639`); re-point it to the live method location or drop the range.
* **docs/architecture** (contributor-facing): `explanation/typed-rejection.adoc:75` says the `ServiceCarrierShapeError` arms carry "typed `SourceKey.Cardinality` values"; the live payload type is `Arity` (its javadoc names these arms). `reference/emitter-conventions.adoc:84`-`:86` describes the `@sourceRow` classification as "a `SourceKey` whose `Reader` is `SourceRowsCall(lifter)` and whose `Wrap` is `Row` ... a `LifterRef` ... on the reader"; the live shape is a `KeyLift.Lifter(LifterRef)` verdict whose residue `SourceKey` derives `Wrap.Row` from the lift arm.

Two dead `{@link}`s survive because neither habitat is covered by the parent pom's `{@link}`-reference gate: `AccessorDerivedBatchKeyTest:28` (`graphitron-sakila-example/pom.xml` sets `maven.javadoc.skip=true`, opting the module out) and `SingleRecordTableFieldServiceProducerPipelineTest:24` (test sources are outside the javadoc run). Repoint each to a live `{@link}` (`KeyLift.Accessor`, `KeyLift.ProducedRecords`) or downgrade to `{@code}` per the gate rule; do not leave them dangling.

Where prose uses "BatchKey" as a bare concept word ("accessor-derived BatchKey path", "auto-derives the BatchKey", "Phase A plumbing (BatchKey, ...)"), rephrase to the live vocabulary (the accessor-keyed `DataLoader` path / the `KeyLift.Accessor` verdict) rather than keeping a CamelCase name that reads as the deleted type.

## Identifier renames (reviewer's call on how far)

Discoverable symbols embedding dead type names keep the vocabulary alive in code search. The two sakila-facing ones:

* Test class `AccessorDerivedBatchKeyTest` -> `AccessorDerivedSourceTest`, paralleling the pipeline-tier `AccessorDerivedSourceCase` (`GraphitronSchemaBuilderTest`). Ripples to `CreateFilmsPayloadService.java:9`, `CreateFilmsPayload.java:13`, `graphitron-sakila-example/README.md:62`.
* Test method `inventoryById_filmCardData_firesAccessorKeyedSingleLiftThroughCustomJavaRecord` (`GraphQLQueryTest.java:687`) embeds `AccessorKeyedSingle`. Rename to drop the dead type (e.g. `..._firesAccessorLiftThroughCustomJavaRecord`); it is cited by name in `docs/manual/how-to/computed-fields.adoc:137`, which updates in lockstep.

The review pass surfaced more identifiers in the `graphitron` module's test tier, same disease:

* `ServiceCatalogTest` methods `reflectServiceMethod_compositeKeyTableRecordSources_classifiedAsMappedTableRecordKeyed` (`:383`), `..._setOfTableRecordSources_classifiedAsMappedTableRecordKeyed` (`:409`), `..._setOfRow1Sources_classifiedAsMappedRowKeyed` (`:427`), `..._setOfRecord1Sources_classifiedAsMappedRecordKeyed` (`:444`); rename onto the live `SourcesShape` wrap (e.g. `..._classifiedAsWrapTableRecordSources`).
* `InterfaceFieldValidationTest.rejects_listArm_onAccessorKeyedManyHubArityOver21` (`:200`); drop the dead arm name (e.g. `..._onAccessorManyHubArityOver21`).
* Fixture methods referenced by name from SDL string literals, so each rename updates its SDL call sites in lockstep: `TestFilmService.getRankMappedRecordKeyed` (`generators/TestFilmService.java:86`; cited at `ServiceProjectionPipelineTest.java:158`) and `TestServiceStub.childServiceMappedRecordKeyedWrongScalarValue` (`TestServiceStub.java:548`; cited at `GraphitronSchemaBuilderTest.java:10349`).

The identifier renames widen the blast radius past a pure prose scrub, so the reviewer confirms or drops them; the prose scrub proceeds either way. **Spec -> Ready sign-off decision (2026-07-19): take all of them.** They are test-tier and fixture-only identifiers whose in-tree callers update in lockstep, carry no production or public-API surface, and leaving a dead type name embedded in a test class or method name keeps the retired vocabulary alive in code search, which is exactly what this item exists to eliminate. If dropped, refresh the affected Javadoc/comment bodies but keep the identifiers. `roadmap/changelog.md`'s historical entries naming these identifiers are append-only provenance and are **not** touched under either choice.

## Scope, non-goals, coverage

* One item, all surfaces (graphitron main + test sources, sakila-service, sakila-example, docs/manual, docs/architecture); no sibling deferral.
* `BatchKeyField` and the other live vocabulary above stay untouched everywhere. The enumerated retirement-provenance mentions stay as-is.
* Pure doc / identifier rot: no production-model, generated-output, or test-behaviour change. The one main-source string edit (`ParentCorrelation` invariant message) changes message text only. Test bodies keep asserting exactly what they assert today; only prose (and, if the renames are taken, the identifiers and their references) change. The tests remain their own enforcer, so no new coverage is added.
* No roadmap-id citation replaces any comment (main + test source comments are scanned by `RoadmapReferenceGuardTest`). The `.adoc` edits use no em dashes, per the writing-style rule.

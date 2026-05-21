---
id: R204
title: Validate uniform env.getSource() domain return type across OutputField producers on an SDL type
status: In Review
bucket: cleanup
priority: 10
theme: mutations-errors
depends-on: []
created: 2026-05-21
last-updated: 2026-05-21
---

# Validate uniform env.getSource() domain return type across OutputField producers on an SDL type

## Problem

Every classified field whose return type is an SDL Object `P` is a producer of `P`: its emitted resolver puts a Java value at `env.getSource()` for `P`'s child datafetchers. The generator commits to one Java type per `(P, child-field-coord)` pair when it emits the child datafetcher; we deliberately do not generate dispatch fetchers that branch on runtime source type. A schema in which two producers of `P` hand different Java types at `env.getSource()` is malformed: whichever producer the runtime invokes second feeds a datafetcher generated for the other's record shape, returning silently wrong data or NPEing on a column the fetcher expects.

R178 retired the carrier-walk's compare-then-write `carrier-data-field.single-producer-kind` invariant, which was the prior structural surface for this constraint. The unified path observes producer bindings on independent `putIfAbsent` memos in `RecordBindingResolver` (`dmlEmittedMemo`, `serviceEmittedMemo`) and never compares them; the only producer combination that currently exercises the disagreement is DML `@mutation` (sparse-Record source) + `@service`-on-`Mutation` (typed `TableRecord` source), but the constraint is general across any current or future producer permit. Two pipeline-tier pins in `SingleRecordTableFieldServiceProducerPipelineTest` are `@Disabled` against the gap.

## Design

### `OutputField` sealed sub-interface

Today `GraphitronField` is sealed over `RootField | ChildField | InputField | UnclassifiedField`. `InputField` permits are argument-side (no resolver, no `env.getSource()` story) and `UnclassifiedField` has no classification; everything else emits a Java value. Lift a sealed `OutputField extends GraphitronField` between the root and the two output sub-hierarchies:

```
GraphitronField    (sealed, permits OutputField | InputField | UnclassifiedField)
└─ OutputField     (sealed, permits RootField | ChildField; declares DomainReturnType domainReturnType())
   ├─ RootField    (sealed)
   └─ ChildField   (sealed)
```

Every leaf permit in `RootField` and `ChildField` answers `domainReturnType()`. The validator's group-by step is `instanceof OutputField`, not a prose-enforced "must implement on every permit" rule. This is consistent with how existing capability interfaces (`SqlGeneratingField`, `MethodBackedField`, `BatchKeyField`) carve typed sub-axes of the hierarchy.

### `DomainReturnType` sealed handle

`Class<?>` is the wrong shape: it collapses `SourceKey.Wrap.Record` (sparse-Record projection over `FilmRecord`'s columns) and `SourceKey.Wrap.TableRecord(FilmRecord)` (typed record handed in directly) into "same class," which is exactly the disagreement the disabled tests are pinning. The accessor must preserve the axis the test asserts on.

`DomainReturnType` is a sealed handle mirroring (a subset of) `SourceKey.Wrap`'s discriminant, lifted to the output permit:

- `Record(TableRef)` — sparse-Record projection over the named table's columns (DML mutations' shape).
- `TableRecord(Class<?> recordClass)` — typed jOOQ `TableRecord` subclass (`@service` carrier shape).
- `Plain(Class<?>)` — explicit Java type, no jOOQ surface (column scalars, computed fields, properties, generated POJOs, etc.).

Each output permit answers from data it already carries. Examples: `ColumnField` answers `Plain(<column's domain class>)`; `MutationDmlRecordField` / `MutationBulkDmlRecordField` answer `Record(tia.inputTable())`; `MutationServiceRecordField` answers `TableRecord(<reflected return-element class>)`; nesting / interface / union / errors permits answer from their already-carried backing-class or wrap arm; the small permit set without an obvious answer (if any surfaces during implementation) gets a fourth arm rather than a fall-through.

The validator's equality check is structural on the sealed arm — two `Record(t1)` agree iff `t1.equals(t2)`; `Record(t)` and `TableRecord(t.recordClass())` *disagree*; etc. The disabled tests' assertions about `Wrap.Record` vs `Wrap.TableRecord` reshape onto the `DomainReturnType` arm names.

### Validator pass

The check is post-classification and structural. It runs once over the classified field registry after `TypeBuilder.buildTypes()` and the per-type field classification loop complete:

1. Iterate `registry.entries()`, filter by `instanceof OutputField`, group by SDL return-type name (`unwrappedTypeName(field.returnType())`).
2. For each group, compute the distinct set of `domainReturnType()` values. If size == 1, pass.
3. If size > 1, the group is in conflict. Every field in the group is rewritten via `FieldRegistry.reclassify(coords, new UnclassifiedField(...), <existing-permit-class>)` to an `UnclassifiedField` with a new `Rejection.AuthorError.MultiProducerDomainTypeDisagreement` (or close-sibling-named) variant naming the SDL type, all conflict participants' coords, and each participant's `DomainReturnType`. `FieldRegistry.reclassify` is the existing typed swap API (used today by R178's DELETE arm to swap a `ColumnField` for `SingleRecordIdFieldFromReturning`) and is the right primitive — no new registry surface needed.

### Load-bearing classifier check

The new check is a load-bearing classifier guarantee: relaxing it would let a child datafetcher receive a runtime `env.getSource()` it wasn't generated for. Annotate the validator pass with `@LoadBearingClassifierCheck(key = "output-fields.uniform-domain-return-type", description = "All OutputField producers reaching the same SDL return type expose the same DomainReturnType; child datafetchers emit against one source-Java-type per (SDL parent, field coord) pair and do not branch on runtime source type.")`. Tag the child-datafetcher emit sites that type `env.getSource()` from the parent's `DomainReturnType` with `@DependsOnClassifierCheck(key = "output-fields.uniform-domain-return-type", reliesOn = "we emit one source-Java-type per child-field coord; the classifier guarantees only one DomainReturnType reaches each SDL return type.")`. The `LoadBearingGuaranteeAuditTest` then surfaces orphaned consumers if the check is ever relaxed.

### Why this seam, not `RecordBindingResolver`'s existing fold

`RecordBindingResolver` already produces a typed cross-producer rejection on a per-SDL-type basis (`Rejection.AuthorError.RecordBindingMultiProducer`, surfaced as `UnclassifiedType` via `TypeBuilder.surfaceMultiProducerRejections`). The constraint there is class-identity of the backing record. The new check is a different axis — `DomainReturnType` agreement, which factors in both record class and wrap shape — and lives on the `GraphitronField` permits the generator actually consumes, not on the upstream `ProducerBinding` observations. The two folds answer different questions over different data; the field-registry walk is the canonical surface because the generator's "what type does `env.getSource()` carry here" question is asked of `OutputField`s, not of `ProducerBinding`s. Keeping the resolver's job strictly "observe per-producer bindings" and the validator's job strictly "close over the classified registry" maintains the existing separation.

### Fate of the data field on the conflict payload

When both producer mutations on `FilmListPayload` demote, the question is what `FilmListPayload.films` classifies to. Today the data-field arm of `FieldBuilder` reads `dmlEmittedBinding(parentTypeName)` / `serviceEmittedBinding(parentTypeName)` to choose the `SourceKey.Wrap` arm; with both bindings still present in the memos but both producer mutations demoted, the data field still classifies to a `SingleRecordTableField` whose wrap arm matches the first-observed producer — *which is now `UnclassifiedField`*. Two acceptable answers:

- **(a) Leave the data field classified as-is.** The emitter compiles its field-fetcher against a wrap arm whose producer is now `UnclassifiedField`. Validation surfaces the producers as the actionable coords; the orphan field-fetcher never gets called because no producer reaches it (both are unclassified, the build halts on author errors before runtime).
- **(b) Demote the data field to `UnclassifiedField` as well.** Suppresses downstream noise (no dependent permit on an orphan producer); costs a second author-error coord that points at a field the author can't act on directly.

Pick (a). The build halts on author errors before any generated code runs, so the orphan wrap arm is unreachable; the author's actionable surface is the producers and only the producers. If a future case surfaces where leaving the data field classified produces downstream classifier noise the author can't decode, demoting it transitively is an additive change.

## Implementation

Shipped:

- `OutputField` lifted as a sealed sub-interface of `GraphitronField` (`permits OutputField, InputField, GraphitronField.UnclassifiedField`); `RootField` and `ChildField` both extend it. The abstract method `DomainReturnType domainReturnType()` is declared on `OutputField` and implemented on every leaf permit in `RootField` and `ChildField` (compile-time enforced; reflection meta-test pins runtime coverage as belt-and-suspenders).
- Sealed `DomainReturnType` lands at `no.sikt.graphitron.rewrite.model.DomainReturnType` with three arms: `Record(TableRef)`, `TableRecord(ClassName)`, `Plain(ClassName)`. **Design fork from the spec draft:** the arm payloads are `ClassName` (javapoet) rather than `Class<?>`. Rationale: avoids classloading at validator time, matches `SourceKey.Wrap.TableRecord`'s existing slot type, and the structural equality the validator needs is FQN string equality on the `ClassName` arms / record value-equality on `TableRef`. Each arm overrides `toString()` to render as `Record(film)` / `TableRecord(FilmRecord)` / `Plain(java.lang.String)` so the rejection message tail names the arm without permit-internal tokens.
- New `Rejection.AuthorError.MultiProducerDomainTypeDisagreement(String sdlTypeName, List<Participant> participants)` variant, with `Participant(String parentTypeName, String fieldName, DomainReturnType domainReturnType)` as the sub-record. The arm slots under `AuthorError` (the projection in `RejectionKind.of` is transparent); LSP severity coverage lands via the existing `SealedHierarchyDocCoverageTest` + `RejectionSeverityCoverageTest` pair (typed-rejection.adoc and LSP test both updated).
- Validator pass `GraphitronSchemaBuilder.validateUniformDomainReturnType` runs in the schema-builder pipeline immediately after the per-type field classification loop and before `ConnectionPromoter.promote`. The pass walks `registry.entries()`, filters `instanceof OutputField`, groups by SDL return-type name (with an SDL-Object-only filter: scalars/enums/interfaces/unions skip — they're leaves at the consumer level or resolve to per-implementation arms with their own producer-agreement guarantees), and `FieldRegistry.reclassify`s every participant in a multi-`DomainReturnType` group to `UnclassifiedField` with the new rejection variant. The same rejection payload is shared across every demoted producer so each surfaces independently in the validator's per-`UnclassifiedField` pass.
- The validator pass is annotated `@LoadBearingClassifierCheck(key = "output-fields.uniform-domain-return-type")`. The single consumer site today is `FetcherEmitter.buildSingleRecordTableFetcherValue` (the data-field-carrier emitter, which switches on `SourceKey.Wrap` to type `env.getSource()` for the child datafetcher); it carries a matching `@DependsOnClassifierCheck`. `LoadBearingGuaranteeAuditTest` passes.
- The R178 step 2 in-source comment block and `@org.junit.jupiter.api.Disabled` annotations in `SingleRecordTableFieldServiceProducerPipelineTest` were removed; the two formerly-disabled mixed-producer cases now assert against the unified-path diagnostic.
- The `RecordBindingResolver` stays "observe per-producer bindings"; cross-producer comparison lives on the validator (no `mixedProducerConflicts` framing landed on the resolver).

**Per-permit `domainReturnType()` rationale (design fork from the spec draft's broad-detection wording).** The spec called for detecting "any current or future producer permit" disagreement; in practice the existing sakila example mixes service-table producers (typed `XRecord`) with SQL-emit table producers (sparse `Record`) on the same `@table`-bound SDL type and works at runtime because typed `XRecord` IS-A jOOQ `Record` via subtyping and the children read columns by name through the generic `Record` interface. Distinguishing those at the validator level would surface false-positive conflicts the runtime handles. The shipped per-permit choices keep R204's detection scoped to the case the spec explicitly named (DML `@mutation` carrier vs `@service`-on-Mutation carrier on the same payload SDL type):

- `ServiceTableField` / `MutationServiceTableField` / `QueryServiceTableField` answer `Record(returnType.table())` (consumer-equivalent to typed `XRecord` via subtyping; not `TableRecord(recordClass)` as a naive reading of the spec would suggest).
- `MutationDmlRecordField` / `MutationBulkDmlRecordField` answer `Record(tableInputArg.inputTable())` (sparse `RecordN` projection).
- `MutationServiceRecordField` answers `TableRecord(peelToClassName(method.returnType()))` (typed `XRecord` verbatim; this is the carrier-shape producer that's structurally distinct from the DML carrier).
- DML `@table`-direct permits (`MutationInsertTableField`, etc.) dispatch on `DmlReturnExpression`: `EncodedSingle` / `EncodedList` answer `Plain(STRING)` (encoded NodeId scalar); `ProjectedSingle` / `ProjectedList` answer `Record(tableInputArg.inputTable())`.
- `NestingField` answers `Plain(org.jooq.Record)` (pass-through; the same nested SDL type is reachable from multiple `@table` parents and the children read by column name on the generic `Record` interface).
- `ConstructorField` dispatches on `returnType`: `Record(table)` for `TableBoundReturnType` returns, `Plain(Object)` otherwise (pass-through to the constructor-populated child).
- `ColumnField` / `ColumnReferenceField` dispatch on `compaction`: `NodeIdEncodeKeys` answers `Plain(STRING)` (encoded NodeId), otherwise `Plain(ClassName.bestGuess(column.columnClass()))`.
- Scalar / encoded permits (`CompositeColumnField`, `CompositeColumnReferenceField`, `SingleRecordIdFieldFromReturning`) answer `Plain(STRING)`; the various SQL-emit table permits answer `Record(returnType.table())`; polymorphic permits (`InterfaceField`, `UnionField`, `QueryInterfaceField`, `QueryUnionField`, `QueryNodeField`, `QueryNodesField`) answer `Plain(Object)`; the various `@record` / accessor permits answer `Plain(Object)` or `Plain(column.columnClass())` where data is available.

The conflict surface for the today-exercised case (`FilmListPayload` reached by both DML `createFilms` and `@service runFilms`) is symmetric: `MutationDmlRecordField` answers `Record(film)`; `MutationServiceRecordField` answers `TableRecord(FilmRecord)`; the validator demotes both.

## Tests

Shipped:

- `SingleRecordTableFieldServiceProducerPipelineTest`'s two formerly-`@Disabled` cases re-enabled with assertions against the unified-path diagnostic. Each test's Javadoc carries the one-line rationale; both ordering directions pin the symmetric conflict surface and assert each producer's rejection message names the payload SDL type, both producer field coords, and both `DomainReturnType` arms (`Record(film)` / `TableRecord(FilmRecord)`).
- Unit-tier coverage in `DomainReturnTypeCoverageTest` (`graphitron-rewrite/graphitron/src/test/java/no/sikt/graphitron/rewrite/model/`): per-arm structural-equality pins, `toString()` rendering pin (so the rejection message tail stays stable), `MutationDmlRecordField` answers-`Record` pin, and the meta-test that walks every leaf in `OutputField`'s sealed-permit graph via reflection and asserts each declares a non-abstract `domainReturnType` method.
- `LoadBearingGuaranteeAuditTest`, `SealedHierarchyDocCoverageTest`, `RejectionSeverityCoverageTest` all pass against the new producer/consumer key and the new `Rejection` arm.
- Full pipeline (`mvn install -Plocal-db -P!docs`) builds clean end-to-end including the sakila example's execution-tier tests.

## Roadmap entries

- This file deletes on Done; `changelog.md` gets a one-line R204 entry citing the landing commit.

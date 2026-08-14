---
id: R656
title: "@sourceRow-declared batch key for @service children on scalar-only parents"
status: Spec
bucket: feature
priority: 4
theme: service
depends-on: []
created: 2026-08-13
last-updated: 2026-08-14
---

# @sourceRow-declared batch key for @service children on scalar-only parents

## Problem

A batched child `@service` on a class-backed parent keys on the table its `Sources` element type names, and the parent must produce a record of that table through one of two routes (`ServiceKeySource`): it *is* that record (`FromHeldRecord`), or its backing class exposes exactly one zero-arg accessor returning one (`FromAccessor`, found name-free by `ClassAccessorResolver.enumerateZeroArg`). A parent carrying only scalar key columns satisfies neither, and `FieldBuilder.cannotProduceKey` rejects with a message ending "has no route today".

When the author owns the DTO the fix is to add a producing accessor. When the class cannot be edited (third-party, generated elsewhere, another module's record) there is no fix, which is exactly the gap `@sourceRow` closes on the table-child path. The Backlog body feared this would force an optional columns axis on `MethodRef.Param.Sourced`; the design below avoids that entirely by keeping the `Sources` element type as the catalog grounding and adding the lifter only as a third *producer* of the same key.

## Design: the lifter as the accessor route's static twin

`@sourceRow(className:, method:)` on the child `@service` field names a public static method that takes the parent and returns the `Sources` element record class itself:

```graphql
type Aktivitet {          # class-backed: produced by a @service returning Aktivitet
    navn: String
    beskrivelse: String @service(
        service: {className: "no.example.TekstService", method: "hentBeskrivelser"}
    ) @sourceRow(className: "no.example.AktivitetKeyLifter", method: "key")
}
```

```java
public final class AktivitetKeyLifter {
    public static AktivitetRecord key(Aktivitet parent) {
        var r = new AktivitetRecord();
        r.setAktivitetId(parent.aktivitetId());
        return r;
    }
}
```

The return contract is site-derived: on a `@table` child the lifter returns the derived `RowN` tuple (unchanged), on a `@service` child it returns the `Sources` element record. Why the record and not the table path's `RowN`:

* Validation and emit become near-verbatim reuse of the accessor route; the arm is `FromAccessor`'s twin with a static call in place of the instance accessor.
* Per-position type checking relocates into the author's own javac (the record's setters are typed), which is stronger than the table path's `ColumnRef.columnClass` string equality and has none of its array/converter edge cases.
* Converting a `RowN` to the declared record at emit would need the `parentKeyCellValue`-style "cells must be bind Params" extraction, an unchecked runtime contract this path would newly inherit.
* The lifter returns exactly the key element type the service method receives.

Enforcers, per invariant:

* *Return class identity*: classify time, compared against `SourceKey.Wrap.TableRecord.className()` directly, not table identity via `denotesSameTableAs`. Table identity is a proxy; the emit casts and copies by field identity, so no downstream compile would catch a class mismatch. This check is the only enforcer, so it carries the exact fact the emit assumes.
* *Author body typing*: the author's javac, via the record's typed setters.
* *Key-column population*: unenforced at build time. A lifter returning a record with the PK unset yields an all-null key; the PK-copy normalization copies the nulls faithfully. This is the same hole the accessor route already has (an accessor can return an unpopulated record) and surfaces at the execution tier only.

### Producer ladder (ordered)

`FieldBuilder.classifyChildFieldOnResultType` currently checks the standalone `@sourceRow` branch before `@service` (the `hasAppliedDirective(DIR_SOURCE_ROW)` guard above the `DIR_SERVICE` guard), so a field carrying both directives today routes into `SourceRowDirectiveResolver` and silently drops `@service`. Reorder: `@service` presence wins, and the service classification consumes `@sourceRow` as its key-producer declaration. Inside `FieldBuilder.resolveServiceKeySource`, the ladder is:

1. Wrap gate, unchanged: the `Sources` element type must be a jOOQ record class (`Wrap.TableRecord`). Anonymous `Row`/`Record` wraps stay rejected, directive or not; the element type is the catalog grounding for the key and nothing else supplies it.
2. Key owner resolution and PK gate, unchanged.
3. Directive present: the parent must be class-backed (`PojoResultType.Backed` or `JavaRecordType`), mirroring `SourceRowDirectiveResolver.parentBackingClass`'s gate. On a `JooqRecordCarrier` parent the directive is rejected as redundant, matching the table path's rule that the catalog record already supplies the key; the wrong-table typed-record parent's diagnostic wording stays R665's concern. Otherwise resolve and validate the lifter (below) and mint `FromLifter`. Accessor enumeration never runs; the directive wins totally.
4. Directive absent: existing routes unchanged (`FromHeldRecord`, then accessor enumeration into `FromAccessor` / ambiguity / list-cardinality / `cannotProduceKey`).

On a `@table` parent, `classifyChildFieldOnTableType` already rejects `@sourceRow` before `@service` is consulted ("@sourceRow is for record-backed (non-table) parents"), so the table-parent and root combinations are covered by the existing rejection and stay as they are.

Directive-over-accessor coexistence is neither rejected nor warned. The directive is authored intent; accessor enumeration is the inference default, and it is deliberately name-free precisely because a coincidentally-shaped accessor is feared. An author who writes the directive is overriding inference, not drifting. This also gives the two-accessor ambiguity rejection an actionable exit that needs no edit to the class.

### Model

One new arm: `ServiceKeySource.FromLifter(TableRef keyOwner, StaticProducerRef ref)`, where the new `StaticProducerRef(ClassName declaringClass, String methodName, ClassName parentBackingClass, ClassName elementClass)` record (name open to reviewer) is the static twin of `AccessorRef`: it carries the cast target and the resolved return class so the emitter has typed access without redoing reflection. This matters because `TypeFetcherGenerator.buildServiceDataFetcher` receives only `(SourceKey, ServiceKeySource)` and has no `GraphitronType.ResultType` in hand; the table path's two-component `LifterRef` gets away without a cast target only because `GeneratorUtils.backingClassOf` recovers it from the result type, a recovery this arm has no input for. `LifterRef` stays untouched and keeps its single documented meaning (returns `RowN`).

Nothing else in the model moves. `keyColumns()` stays a derivation off `keyOwner().primaryKeyColumns()`; `sourceShape()` gains `FromLifter -> SourceShape.Record` and stays a total derivation; `MethodRef.Param.Sourced` keeps its non-empty columns (the key owner's PK); `SourceKey.keyElementType()` is untouched.

At four arms `ServiceKeySource` shadows `KeyLift` arm-for-arm (`FromTableRow`/`FkColumns`, `FromHeldRecord`/`ProducedRecords`-adjacent, `FromAccessor`/`Accessor`, `FromLifter`/`Lifter`) with nothing binding the two seals. The split's justification is wrap provenance, not the arm set: `@service` wraps are authored by the signature, record-parent wraps are derived and residue-checked via `KeyLift.checkResidueAgreement`. Restate that in `ServiceKeySource`'s javadoc when the fourth arm lands, and note there the collapse question (a residue check gated on wrap provenance rather than leaf identity could let one lift axis serve both paths) as a future-audit pointer, not this item's work.

### Classification and validation

Extract the reflection preamble the two lifter sites share into a builder-side helper returning a sealed result: class load via `ctx.codegenLoader()`, unique static method by name minting `Rejection.unknownLifterMethod` with the did-you-mean candidates, single parameter assignable from the parent backing class. The `Ok` arm carries the resolved `java.lang.reflect.Method`; raw reflection stays inside the builder boundary. `SourceRowDirectiveResolver` consumes the helper for its steps and keeps its return-shape validation (`RowN` arity plus per-position `columnClass`) unchanged; the service path adds its own return-shape validation: return class equals the `Sources` element class, single cardinality (a `List`-returning lifter is rejected by name, mirroring the list-cardinality accessor rejection; fan-in stays R657's design space). Sharing the preamble keeps the rejection vocabulary single-sourced so the LSP renders the same candidates for the identical authored mistake on both paths.

`GraphitronSchemaValidator.validateServiceBatchKey` is arm-agnostic over `ServiceKeySource` (key owner PK plus derived columns) and should need no change; verify at implementation that the validator's service-leaf walk has no per-arm switch to extend.

### Emit

`GeneratorUtils.buildServiceKeyExtraction` gains the `FromLifter` arm as `FromAccessor`'s twin: `ElementRecord keyRecord = Declaring.method((Parent) env.getSource())` in place of the instance accessor call, the same null gate returning a completed null future, then the same `buildKeyExtraction` PK-copy into a fresh record by jOOQ field identity. "The keys carry the key columns, and nothing else" holds by construction even when the lifter populated extra columns.

### Messages and contract prose

All producer-route message surfaces gain the third route; two retire the "has no route today" tail:

* `FieldBuilder.cannotProduceKey`: name all three routes, drop the tail. (R665 separately rewrites this message's description half for the wrong-table typed-record parent; whichever item lands second rebases the string. The route list this item adds and the shape naming R665 adds are different halves.)
* The anonymous-wrap rejection: keep "declare the batch key as a jOOQ record class instead", drop the tail; once a record class is declared all three routes apply. The test `anonymousKeyWrapOnClassBackedParent_isRejectedWithoutPromisingARoute` exists precisely to be flipped by this item.
* The two-accessor ambiguity rejection: name `@sourceRow` as the tie-break that needs no class edit.
* `ServiceCatalog.dtoSourcesRejectionReason`: the "must either be that record or expose exactly one zero-arg accessor returning it" prose gains the lifter.
* The `@sourceRow` description in `directives.graphqls` and the `graphitron_source_row` table comment in `graphitron-model.sql` both currently assert the `RowN`-only contract. Rewrite each once, site-derived: the lifter returns the batch key in the shape the annotated field's key wants, the first-hop or leaf tuple as `RowN` on a `@table` child, the `Sources` element record on a `@service` child. No DDL shape change: the relation stays a site-agnostic verbatim transcription keyed on the field coordinate, so no new fact enters the store, and `GraphitronFactCapture`'s `sourceRow` arm is untouched.

### LSP

`LspVocabulary`'s bindings for `sourceRow(className:)` / `sourceRow(method:)` are coordinate-keyed and return-type-agnostic, so no vocabulary change. Check whether `MethodCompletions` filters `@sourceRow` method candidates by `RowN` return; if it does, derive the expected return per site.

## User documentation (first-client check)

`docs/manual/how-to/handle-services.adoc`, `#batching-class-backed`: the two-route bullet list becomes three, the Java lifter example above lands beside it, and the quoted rejection text is refreshed. Draft:

> For the parent to supply it, one of three things must hold:
>
> * the parent's backing class *is* an `AktivitetRecord`, or
> * the parent's backing class exposes exactly one zero-arg accessor returning `AktivitetRecord`, or
> * the field declares `@sourceRow(className: ..., method: ...)` naming a public static method that takes the parent and returns an `AktivitetRecord`.
>
> Reach for the third when the parent class is not yours to edit, or when more than one accessor returns the declared record and the build asks you to break the tie. As everywhere on the `@service` path, the framework copies the key columns off the record the lifter returns and hands the service a record carrying the key columns and nothing else.

`docs/manual/how-to/result-types.adoc`: the decision tree's `@service` row currently states "Neither accessor inference (which matches by the child field's name) nor `@sourceRow` (which needs a catalog anchor for its columns) applies" on this path. Replace: accessor inference still does not apply (the `@service` key is name-free), and `@sourceRow` does apply with the record-return contract keyed on the `Sources` element type. The constraints bullet "`@sourceRow` is rejected on `JooqTableRecordType` and `JooqRecordType` parents" stays true and unchanged.

`@sourceRow`'s dedicated reference and how-to pages stay withheld from v1 (`DirectiveSupportReport.WITHHELD_FROM_V1`; reintroduction is R404's). Both files above already discuss the directive inline, and this item follows that precedent without changing the withholding.

## Implementation

* `ServiceKeySource`: new `FromLifter` arm, `sourceShape()` case, javadoc restatement (wrap-provenance justification plus the collapse-question pointer).
* New `StaticProducerRef` record beside `AccessorRef` in `no.sikt.graphitron.rewrite.model`.
* `FieldBuilder`: reorder the `DIR_SOURCE_ROW` / `DIR_SERVICE` guards in `classifyChildFieldOnResultType`; the lifter path and message edits in `resolveServiceKeySource` / `cannotProduceKey`; the shared reflection-preamble helper.
* `SourceRowDirectiveResolver`: consume the shared preamble helper; behaviour otherwise unchanged.
* `ServiceCatalog.dtoSourcesRejectionReason`: prose.
* `GeneratorUtils.buildServiceKeyExtraction`: `FromLifter` arm.
* `GraphitronSchemaValidator`: verify the mirror needs no per-arm change.
* `directives.graphqls` description; `graphitron-model.sql` `graphitron_source_row` comment prose.
* Exhaustive switches over `ServiceKeySource` elsewhere: the compiler finds them.
* Docs: `handle-services.adoc`, `result-types.adoc` per the draft above.
* Fixtures: a lifter in `graphitron-sakila-service`, a scalar-only DTO field wired through it in `graphitron-sakila-example`'s schema.

## Tests

Pipeline tier, extending `ServiceRecordParentBatchKeyTest` with its existing fixture style (`ServiceKeyPayloads`, `TestFilmService`, a new `@sourceRow` lifter stub beside them):

* Happy path: scalar-only parent (`NoRecordAccessor`-shaped) plus directive classifies as `ChildField.ServiceRecordField` with `FromLifter`, key owner `language`, columns exactly the key owner's PK, wrap `TableRecord`, `sourceShape() == Record`; sibling case for the `ServiceTableField` leaf.
* Directive wins: parent with a qualifying accessor plus the directive mints `FromLifter`, not `FromAccessor`.
* Directive breaks the two-accessor ambiguity (the currently-rejected `TwoLanguageAccessors` shape plus a directive classifies).
* Rejections: unknown lifter class; unknown method with did-you-mean candidates (`Rejection.unknownLifterMethod`); non-unique method name; parameter not assignable from the parent backing class; return class differs from the `Sources` element class; `List`-returning lifter; directive on a `JooqRecordCarrier` parent; directive with an anonymous wrap (wrap rejection fires, unchanged).
* Message pins updated: the "only scalar key columns has no route" tail assertions in `classBackedParentThatCannotProduceTheKey_isRejectedNamingBothRoutes` and `anonymousKeyWrapOnClassBackedParent_isRejectedWithoutPromisingARoute` re-pin the new route-naming text.
* Both-directives ordering: a `@service` child carrying `@sourceRow` no longer routes into `SourceRowDirectiveResolver` (today it does, silently dropping `@service`).

Compile and execution tier: the emit arm is new generated-code shape, and two of its three enforcers live in the consumer's compiler and at runtime, so this is not optional polish. A `graphitron-sakila-service` lifter plus a `graphitron-sakila-example` schema use pins the compile tier; one execution test (style of the existing service-batching tests in `GraphQLQueryTest`) asserts one service invocation per request with per-parent values scattered correctly.

## Non-goals

* Anonymous `Row`/`Record` wraps on class-backed parents stay rejected; no optional columns axis on `MethodRef.Param.Sourced` (the Backlog body's other fork, declined).
* The table-child `@sourceRow` contract is unchanged (`RowN`, catalog-derived tuple, `@reference` composition).
* `JooqRecordCarrier` parents stay rejected for the directive. A lifter from a wrong-table typed record (for example `(FilmRecord) -> LanguageRecord`) is conceivable but unrequested; widen later if a real schema needs it. R665 owns that rejection's wording.
* Many-key fan-in stays R657.
* The v1 withholding of `@sourceRow`'s dedicated docs pages stays (R404).
* No `KeyLift` / `ServiceKeySource` collapse here; the javadoc records the question.

## Retired vocabulary

* "has no route today" (the shared message tail in `cannotProduceKey` and the anonymous-wrap rejection, quoted in `handle-services.adoc`, pinned by `ServiceRecordParentBatchKeyTest`).
* "needs a catalog anchor for its columns" (the `result-types.adoc` claim that `@sourceRow` does not apply on the `@service` path).
* "return Row1..Row22" as the whole `@sourceRow` contract (the `directives.graphqls` description and the `graphitron_source_row` table comment; both become site-derived statements).

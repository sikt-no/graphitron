---
id: R648
title: "Author-declared batch key for child @service on record-backed parents"
status: Spec
bucket: feature
priority: 3
theme: service
depends-on: [service-coordinate-rejection-precedence]
created: 2026-08-13
last-updated: 2026-08-13
---

# Author-declared batch key for child @service on record-backed parents

A child `@service` field can only be batched when its parent type maps a table: the batch key is hardwired to the parent's primary key. `FieldBuilder.classifyChildFieldOnResultType` passes `List.of()` for `parentPkColumns` on every record-backed parent, `ServiceCatalog` reads the empty list as "root coordinate" and discards any recognised SOURCES shape, and the parameter falls through to name-based argument binding. Schemas that aggregate a type in Java and want one child resolved by a service have no route, and the two mechanisms that already lift a batch key out of a record-backed parent (typed-accessor inference and `@sourceRow`) feed only the table-child path.

## Why this is a gap and not a stance

Graphitron already accepts every premise the feature needs. Record-backed parents are supported, including author-declared key lifting via `@sourceRow`. The service contract already holds for a service-returned parent, not just a framework SELECT, so "the parent did not come from a generated query" is settled. And the key an author wants here is still a catalog key: a real column on a real table, reached through hand-written jOOQ, which is the documented extension point. What is missing is a way to say *which* key, not permission to have one.

The database-first stance says data semantics live in the database. It does not say every batch key must be the parent's primary key. Meanwhile "separate business logic from API code" argues the other way: a type aggregated in Java is aggregated there because that is where the business logic belongs, and today's rule pushes authors to add a database view purely to satisfy a code-generation constraint.

## Why the table-bound workaround does not substitute

Making the parent table-bound (a view) does not unblock the shape. `ServiceDirectiveResolver.validateTableRecordSourceParentTable` requires the `Set<X>` element to be the parent type's own backing record class, so a service keyed on some other table's record is rejected on a view exactly as it is on a POJO. The author has to rewrite the service to take the parent's record and re-derive the real key inside it, which turns a reusable service method into a per-caller variant. The workaround costs the schema change and the reuse.

## Desired outcome

One key-provenance mechanism, consumed by both child paths. Today they diverge:

[cols="1,2"]
|===
| Child path | Batch key source

| Table child
| Catalog FK, typed-accessor inference, or `@sourceRow`

| `@service` child
| Parent table primary key, and nothing else
|===

The key-lift machinery that already serves table children should serve the `@service` SOURCES path too. The parent declares how to produce the key record; the `Set<KeyRecord>` parameter binds; the DataLoader dedups across the request as it does for a table parent.

The substantive change underneath: the SOURCES contract relaxes from "keys carry the parent's primary key" to "keys carry the columns the parent's key lift produced". This makes the feature more database-first, not less. The key stops being an accident of the parent's PK and becomes a named set of catalog columns the author pointed at.

## The declaration is the service signature

The design question this spec settles is *what names the key*. The item's framing offered two candidate mechanisms; only one of them transfers, and a third route exists that needs no declaration at all.

**Typed-accessor inference does not transfer as-is.** On the table-child path, `FieldBuilder.collectAccessorMatches` matches accessors *by the child field's name* and filters them by the field's `@table` return. Neither anchor exists on a `@service` child: the field is named after what the service returns, and the return type is frequently a scalar or a plain DTO. Matching by field name here would let a coincidentally-named accessor silently become the batch key.

**`@sourceRow` cannot ground its columns here.** `SourceRowDirectiveResolver` derives the expected column tuple from `@reference`'s first hop or the leaf target's PK, then validates the lifter's `RowN` arity and per-position column classes against it. On a `@service` child, neither anchor exists: the service's own SQL decides what the key means, so the catalog has nothing to arbitrate and the only surviving check would be one author-written signature against another. `MethodRef.Param.Sourced` also requires a non-empty `List<ColumnRef>` and derives its Java parameter type from `ColumnRef::columnClass`, so admitting a lifter would additionally mean making the columns axis optional. That is a model widening, not a threading exercise. See "Out of scope" below.

**What does ground the key is the element type the service already declares.** A batched child `@service` writes `Set<AktivitetRecord>` (or `List<...>`, or the `RowN` / `RecordN` shapes) as its SOURCES parameter. On a table parent the element type is a *consequence* of the coordinate. On a record-backed parent it can be the *input* to it: `AktivitetRecord` resolves through `ServiceCatalog.resolveTableByRecordClass` to a real table, whose primary key is a real column tuple, and the classifier then asks the parent one question with a determinate answer, "can you produce an `AktivitetRecord`?". Two producers qualify:

[cols="1,2"]
|===
| Parent shape | Key lift

| `JooqTableRecordType` whose table is the declared element's table
| `KeyLift.FkColumns` reading the key columns off the parent's own held record. No author declaration at all: the parent already *is* a typed record.

| `PojoResultType` / `JavaRecordType` exposing exactly one zero-arg accessor returning that record class
| `KeyLift.Accessor`, the arm the table-child path already builds, reduced by element class instead of by field name.
|===

Anything else is the new rejection: the parent cannot produce the declared key, and the message says so and names both routes.

This is why the item's framing of `validateTableRecordSourceParentTable` as "check the element type against the lift's table rather than the parent's" resolves further than stated. On the record-parent path the check dissolves: the element type is what *found* the lift, so there is nothing left to compare. The validation stays, unchanged, on the table-parent path where the element type is still a claim about the parent.

## The coordinate answer is a callback, not a column list

`ServiceCatalog.reflectServiceMethod`'s `parentPkColumns` parameter encodes three distinct coordinate facts as one nullable pair, and two of them are indistinguishable: a root operation type and a record-backed child both arrive as `(List.of(), null)`. `ServiceCatalog.PkLessParent` exists precisely to patch the third out of the same overload. That collapse is the mechanical cause of the masking R649 tracks.

Replace the pair with a resolver the caller supplies, consulted at the moment a SOURCES shape is recognised:

```java
sealed interface ParentKeyResolution {
    /** Root operation type: no parent context. Root diagnostics stay reachable. */
    record Root() implements ParentKeyResolution {}
    /** The parent can produce this key, and here is how the emitter reads it. */
    record Available(List<ColumnRef> columns, KeyLift lift, TableRef owner,
                     SourceShape shape) implements ParentKeyResolution {}
    /** The parent cannot produce this key; the rejection names why. */
    record Rejected(Rejection rejection) implements ParentKeyResolution {}
}
```

`reflectServiceMethod` takes a `Function<SourcesShape, ParentKeyResolution>` where it takes `parentPkColumns` and `pkLessParent` today. The three callers build it:

* Root sites (`FieldBuilder`'s `classifyRootField` service arms): `shape -> new Root()`.
* `classifyChildFieldOnTableType`: `shape -> parentTable.hasPrimaryKey() ? new Available(parentTable.primaryKeyColumns(), new KeyLift.FkColumns(), parentTable, SourceShape.Table) : new Rejected(new ServiceMethodCallError.SourcesOnPkLessParent(...))`. Behaviour identical to today; `PkLessParent` dissolves into this arm.
* `classifyChildFieldOnResultType`: the new resolver described above.

The three coordinates become three named arms of one exhaustive fact, so the two that share an empty list today can no longer be confused for each other.

**Ordering.** The callback fires where `classifySourcesType` recognises the parameter, which is upstream of both the argument-name-mismatch arm and `looksLikeSourcesShape`, so the coordinate's answer wins at this call site by construction. That is the specific instrument for the general precedence rule R649 owns. If R649 lands first with a different instrument, this item adopts it rather than forking a second ordering; if this item lands first, R649's sweep of the resolver family finds this site already correct. Either way the two must not each install their own precedence at this seat.

## Keys stay sparse

The accessor arm hands back a record the parent is already holding, which may be fully populated. The keys the framework passes must not be. Two reasons, and they point the same way:

* The documented contract is "the keys carry the key columns and nothing else" (`docs/manual/how-to/handle-services.adoc`). A pass-through record would quietly carry whatever snapshot the parent held, and a service body reading a non-key column off it would work in one schema and read `null` in the next.
* DataLoader dedup compares keys by `equals`. jOOQ records compare across all fields, so two parents pointing at the same key while holding different snapshots would enqueue as two distinct keys, and the batch the feature exists to produce would silently stop deduplicating.

So the accessor arm projects a fresh sparse record over the resolved key columns, exactly as `GeneratorUtils.buildAccessorKeySingle` does today for the table-child path. No new emit shape.

## Implementation

* `model/ParentKeyResolution.java` (new). The sealed fact above.
* `ServiceCatalog.reflectServiceMethod`: swap `parentPkColumns` + `pkLessParent` for the resolver function; consult it at the `classifySourcesType` recognition point; carry `Available.columns()` into `MethodRef.Param.Sourced`. Delete `ServiceCatalog.PkLessParent`. The `isRoot` derivation in `ServiceDirectiveResolver.resolve` reads the `Root` arm instead of inferring root from an empty list.
* `ServiceCatalog`: a `Set<XRecord>` / `List<XRecord>` element whose class does not resolve through `resolveTableByRecordClass` keeps its existing diagnostic; `dtoSourcesRejectionReason`'s trailer, which currently points at `@sourceRow` as the analogous solution for record-backed parents, is repointed at the typed-record route this item creates.
* `FieldBuilder`: the record-parent key resolver. Reduce `collectAccessorMatches` by element class rather than field name for this caller (the reduction step already differs per caller; only the per-method match logic is shared), and add the `JooqTableRecordType` arm reading `jtr.table().primaryKeyColumns()`.
* `FieldBuilder.classifyChildFieldOnResultType`'s `DIR_SERVICE` branch: the `Result` and `Scalar` arms stop returning the deferred rejection and build `ServiceRecordField` the way the `TableBound` arm already builds `ServiceTableField`. The `Polymorphic` arm keeps its deferral (doubly out of scope, unchanged).
* `ServiceDirectiveResolver.validateTableRecordSourceParentTable`: gate on the table-parent arm. On the record-parent path the element type is the resolver's input and the check is vacuous.
* `ChildField.ServiceTableField` / `ChildField.ServiceRecordField`: add `SourceShape sourceShape` and a nullable `KeyLift lift`, with a compact-constructor rule that `lift` is non-null exactly when `sourceShape` is `Record`, and `KeyLift.checkResidueAgreement` applied on that arm. The Table arm keeps its wrap-driven freedom and stores no lift, mirroring the same asymmetry `BatchedTableField` documents (the Table-sourced emit path is wrap-driven; the Record-sourced one is lift-driven).
* `TypeFetcherGenerator.buildServiceDataFetcher`: fork the key extraction on `sourceShape`, `GeneratorUtils.buildKeyExtraction(sourceKey, parentTable)` on Table and `buildRecordParentKeyExtraction(sourceKey, lift, owner, resultType)` on Record, including the null-source and `Outcome.Success` preludes. This is the same three-way fork `buildBatchedDataFetcher` already makes, and `resultType` / `sourceIsOutcome` are already in scope at the `ServiceTableField` / `ServiceRecordField` cases of the emit switch. Consider extracting the fork rather than writing it twice.
* `GraphitronSchemaValidator`: the service-field checks currently return early for a non-table parent ("no DataLoader key needed"). That early return is now wrong for the shapes this item admits; the parent-PK invariant below it becomes a parent-key invariant that the record arm satisfies through its lift.

## Out of scope

* **`@sourceRow` as a `@service` key declaration.** Grounded above: no catalog anchor for the columns, and admitting it means making `MethodRef.Param.Sourced`'s columns axis optional. A parent carrying only scalar FK columns and no typed record therefore still has no route, and the new diagnostic must not promise one. Filed as a follow-up (see below) so the gap is tracked rather than implied.
* **List-cardinality accessors.** An accessor returning `List<XRecord>` fans one parent out to many keys, which on the table-child path is the `LOAD_MANY` dispatch. The service path's `Map<Key, Value>` return contract assumes one key per parent, so the many case is rejected by name here and left to a follow-up that designs the fan-in.
* **Polymorphic child `@service`.** Unchanged deferral.
* **The rejection-precedence rule itself.** R649's, per the ordering note above.

## User documentation (first-client check)

New subsection in `docs/manual/how-to/handle-services.adoc`, after the existing `Map<Key, Value>` section. Draft:

> === Batching a child `@service` on a class-backed parent
>
> A child `@service` batches against a key its parent can produce. When the parent type carries `@table`, that key is the parent table's primary key and there is nothing to declare. When the parent is class-backed (a DTO a service returned, or a Java type an accessor chain reached), the key is whatever the SOURCES parameter's element type names.
>
> ```graphql
> type Aktivitet {          # class-backed: produced by a @service returning Aktivitet
>   navn: String
>   beskrivelse: String @service(
>     service: {className: "no.example.TekstService", method: "hentBeskrivelser"}
>   )
> }
> ```
>
> ```java
> public static Map<AktivitetRecord, String> hentBeskrivelser(
>         Set<AktivitetRecord> keys, DSLContext ctx) { ... }
> ```
>
> `AktivitetRecord` names the key: the framework resolves it to the `aktivitet` table and keys the batch on that table's primary key. For the parent to supply it, one of two things must hold:
>
> * the parent's backing class *is* an `AktivitetRecord`, or
> * the parent's backing class exposes exactly one zero-arg accessor returning `AktivitetRecord`.
>
> As everywhere else on the `@service` path, the records the framework hands you carry the key columns and nothing else, even when the accessor it read them from returned a fully populated record. Fetch the rest through the injected `DSLContext`, in one query for the batch.
>
> If neither holds, the build fails:
>
> ....
> @service on 'Aktivitet.beskrivelse' declares a batch key of 'AktivitetRecord'
> (table 'aktivitet'), but the parent type's backing class 'no.example.Aktivitet'
> cannot produce one. Either expose a zero-arg accessor returning 'AktivitetRecord'
> on that class, or change the Sources element type to a record class the parent
> can produce. A parent that carries only scalar key columns has no route today.
> ....

Companion edits: the decision tree in `docs/manual/how-to/result-types.adoc` gains a `@service`-child row beside its existing `@table`-child rows; `handle-services.adoc`'s "the keys carry the parent's primary key, and nothing else" line becomes "the keys carry the key columns, and nothing else" with the parent-PK case named as the table-parent instance of it; the "a `@table` parent with no primary key cannot host a batched child `@service`" gotcha stays true and stays put.

## Tests

* **Unit** (`ServiceCatalogTest`): the three `ParentKeyResolution` arms through `reflectServiceMethod`, including that a `Root` resolution still reaches the batch-at-root diagnostic and a `Rejected` resolution surfaces its own rejection rather than the argument-name mismatch. The `Rejected` case is the pin that this item did not re-mask what R649 unmasks.
* **Pipeline**, modelled on `PkLessParentServiceSourcesRejectionTest`: a DTO parent with a typed accessor classifies to `ServiceRecordField` carrying `KeyLift.Accessor` and a `SourceKey` over the element table's PK; a `JooqTableRecordType` parent classifies to `KeyLift.FkColumns`; a table parent is unchanged (the regression pin for the callback swap); and the rejection fixtures for no producer, more than one matching accessor, and a list-cardinality accessor.
* **Compilation**: a fixture schema whose emitted fetcher exercises the record-arm key extraction, so the fork in `buildServiceDataFetcher` is compiled rather than only asserted on.
* **Execution**: one test asserting that N parents sharing a key invoke the service once with a deduplicated key set, and that the keys arrive sparse. Both are contract claims the docs make and neither is observable at the pipeline tier.

## Retired vocabulary

* `parentPkColumns` (the parameter name and the concept: the batch key is no longer the parent's PK by definition)
* `ServiceCatalog.PkLessParent`
* "the batch key must be lifted through the parent chain to the rooted `@table`" (the deferred rejection text)
* "the keys carry the parent's primary key" (the docs contract statement, in all its paraphrases)
* "@service on a record-backed parent is not yet supported"

## Roadmap entries

Two follow-ups to file when this ships, both named in "Out of scope" above:

* `@sourceRow`-declared batch keys for `@service` children on parents carrying only scalar key columns, which needs a column-naming surface or an optional columns axis on `MethodRef.Param.Sourced`.
* List-cardinality accessor keys on the `@service` path (the `LOAD_MANY` fan-in against a `Map<Key, Value>` service contract).

## Provenance

Reported twice by the same consumer team, most recently on a type aggregated in Java whose child is produced by a shared translated-text service.

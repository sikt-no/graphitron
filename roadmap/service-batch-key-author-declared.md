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
| Parent shape | Key source

| `JooqTableRecordType` whose table is the declared element's table
| The key columns read off the record the parent already holds at `env.getSource()`. No author declaration at all: the parent already *is* a typed record.

| `PojoResultType` / `JavaRecordType` exposing exactly one zero-arg accessor returning that record class
| The key columns read off the record that accessor returns.
|===

Neither producer is a `KeyLift`; both are wrap-driven reads off a bound source expression, for the reasons in "So the `@service` path stays wrap-driven and grows no `KeyLift`" below. The two rows are the `ServiceKeySource` arms that section's sibling declares, and the accessor row is *not* a reduction of the table-child path's accessor inference (see the Implementation bullet on `ClassAccessorResolver`).

Anything else is the new rejection: the parent cannot produce the declared key, and the message says so and names both routes.

**The counter-argument, stated rather than buried.** "Stability through simplicity" in `docs/graphitron-principles.adoc` asks that explicit mappings live in schema annotations so a future maintainer can read the API-to-database relationship without reverse-engineering. Every neighbouring mechanism honours that by pointing SDL and catalog at reflection and letting reflection only confirm: `SourceRowDirectiveResolver` derives the expected tuple from `@reference` or the leaf PK and validates the lifter against it, `collectAccessorMatches` filters by the child's `@table` return, `validateTableRecordSourceParentTable` checks the element against the parent's record class. This item points the other way for one fact. Two things keep it from being an inversion: `@service(className:, method:)` is already the SDL author's explicit statement that this coordinate's contract is defined in Java, so the key is a property of a contract the schema has already delegated; and the element type is not taken on trust, it must resolve through the catalog to a real table and the parent must independently be able to produce it, so the catalog is still the arbiter. It is a real tension nonetheless, and a reviewer who thinks the key belongs in SDL should say so at this gate rather than after implementation.

**Grain.** "Can this parent produce an `AktivitetRecord`" reads like a per-parent-type fact, but the fact this item resolves is the pair: *this* field's declared key, against this parent. Two `@service` children of the same DTO can legitimately key on different tables (one on `aktivitet`, another on a translation table), and a parent able to produce both should serve both. Per-field resolution is therefore intended, not an accident of where the code sits. There is no cross-field agreement to enforce and no conflict view to add.

This is why the item's framing of `validateTableRecordSourceParentTable` as "check the element type against the lift's table rather than the parent's" resolves further than stated. On the record-parent path the check dissolves: the element type is what *found* the key source, so there is nothing left to compare. The validation stays, unchanged, on the table-parent path where the element type is still a claim about the parent.

## The coordinate answer is a value, and R649 is what makes it one

`ServiceCatalog.reflectServiceMethod`'s `parentPkColumns` parameter encodes three distinct coordinate facts as one nullable pair, and two of them are indistinguishable: a root operation type and a record-backed child both arrive as `(List.of(), null)`. `ServiceCatalog.PkLessParent` exists precisely to patch the third out of the same overload. That collapse is the mechanical cause of the masking R649 tracks.

Replace the pair with a sealed fact:

```java
sealed interface ParentKeyResolution {
    /** Root operation type: no parent context. Root diagnostics stay reachable. */
    record Root() implements ParentKeyResolution {}
    /** The parent can produce this key, and here is where the emitter binds it. */
    record Available(ServiceKeySource source, SourceShape shape) implements ParentKeyResolution {}
    /** The parent cannot produce this key; the rejection names why. */
    record Rejected(Rejection rejection) implements ParentKeyResolution {}
}
```

The three coordinates become three named arms of one exhaustive fact, so the two that share an empty list today can no longer be confused for each other. The callers: root sites yield `Root`; `classifyChildFieldOnTableType` yields `Available(new FromSource(parentTable), SourceShape.Table)` when the parent has a primary key and `Rejected(SourcesOnPkLessParent)` when it does not, which is today's behaviour with `PkLessParent` dissolved into it; `classifyChildFieldOnResultType` runs the resolver described above.

**This has to be a value, and that is a constraint on sequencing rather than a free choice.** The record-parent arm cannot answer without the recognised SOURCES element type, which today exists only inside `reflectServiceMethod`'s parameter loop, so the naive threading is a `Function<SourcesShape, ParentKeyResolution>` callback. That would be a step backwards: the coordinate answer is at least precomputed by the caller today, and making it a callee of the reflection loop is strictly harder to hoist than the status quo. Hoisting is R649's entire scope.

So the ordering is a hard dependency, not a coordination note:

* **R649 lands first and owns it.** Its fix is the phase split inside the service boundary: decode the method into a typed signature fact (per-parameter name, declared type, and the recognised `SourcesShape` where present), then classify the coordinate over that fact, then bind parameters. That is the same "boundaries decode and encode; the interior is typed" seam the rest of the codebase runs on, and it makes R649's precedence rule a reordering of pure steps rather than surgery inside a reflection loop. It also removes the "reflects before it classifies" defect R649 names, structurally, instead of working around it at one seat.
* **R648 consumes it.** With the decoded signature fact in hand, the caller computes `ParentKeyResolution` as a value before the binding step, and this item carries no ordering of its own.

The earlier draft of this section hedged that either item could land first. That hedge is exactly the branch that produces two instruments at one seat, so it is withdrawn. The precedence item was also ranked beneath this one, which inverted the dependency; both now sit at priority 3.

**Consequence for this item's own readiness.** Because this item carries no ordering of its own, it cannot be started until the precedence item's phase split exists, and that item is still in Backlog: it needs its own Spec → Ready pass before anyone implements it, and its plan body defers the phase-split shape to the section above rather than restating it. So the sign-off on this spec is a sign-off on a design, not a green light to open an editor. Whoever flips this to Ready should carry `deferred: true` on the front-matter until the precedence item lands, and the first implementer of this item should re-read that item's status before starting rather than absorbing its scope.

## Keys stay sparse, and the emitter already does that

The accessor arm reads off a record the parent is already holding, which may be fully populated. The keys the framework passes must not be. Two reasons, and they point the same way:

* The documented contract is "the keys carry the key columns and nothing else" (`docs/manual/how-to/handle-services.adoc`). A pass-through record would quietly carry whatever snapshot the parent held, and a service body reading a non-key column off it would work in one schema and read `null` in the next.
* DataLoader dedup compares keys by `equals`. jOOQ records compare across all fields, so two parents pointing at the same key while holding different snapshots would enqueue as two distinct keys, and the batch the feature exists to produce would silently stop deduplicating.

`GeneratorUtils.buildKeyExtraction`'s `SourceKey.Wrap.TableRecord` arm already emits exactly that: a fresh `new XRecord()` with one `key.set(col, source.get(col))` per key column, by jOOQ field identity rather than a by-name `into(...)`. Its `source` is `env.getSource()` cast to the generic `Record` interface, and `Record.get(Tables.X.COL)` reads the same whether that record is the parent's projected row, a producer-handed typed record, or a record an accessor returned.

**So the `@service` path stays wrap-driven and grows no `KeyLift`.** This is the one correction the architect review forced, and it deletes most of the work this item looked like it needed:

* `SourceKey`'s wrap is *stored where authored* and *derived from the lift where inferred*; the `Sources` signature is the authored case by that document's own words. Routing the service leaves through `KeyLift.checkResidueAgreement` would assert the inferred rule against an authored wrap, and the two disagree numerically: `FieldBuilder.buildServiceSourceKey` stores `Wrap.TableRecord(AktivitetRecord)` because `SourceKey.keyElementType()` is what types the loader against the service's `Map<AktivitetRecord, String>`, while `KeyLift.Accessor.wrap()` is `Wrap.Record` and `KeyLift.FkColumns.wrap()` is `Wrap.Row`. The pin would throw on both proposed arms.
* `GeneratorUtils.buildRecordParentKeyExtraction` is lift-driven and emits `RowN` / `RecordN` keys. Neither type-checks against `Set<AktivitetRecord>`. It is the wrong builder for this path.
* Three live statements say `@service` fields carry no lift (`KeyLift`'s class javadoc, `SourceKey`'s wrap-provenance paragraph, `docs/architecture/explanation/dispatch-axes.adoc`). Under the wrap-driven shape all three stay true and none needs retiring, which is itself the signal that this is the grain the codebase already has.

The only fact the emitter is missing is **where to bind the record it reads from**. `buildKeyExtraction` reads `env.getSource()` unconditionally; `buildKeyExtractionWithNullCheck` already takes a `sourceExpr` for exactly this reason. Give `buildKeyExtraction` the same parameter and the accessor arm is one extra statement ahead of the existing body.

## The key source is three arms, not a lift

```java
/** Where the jOOQ record carrying the batch key columns is bound at a @service leaf. */
sealed interface ServiceKeySource {
    /** A @table parent's SQL-projected row, read off {@code env.getSource()}. */
    record FromTableRow(TableRef keyOwner) implements ServiceKeySource {}
    /** A JooqTableRecordType parent that holds the key record itself, read off
     *  {@code env.getSource()}. */
    record FromHeldRecord(TableRef keyOwner) implements ServiceKeySource {}
    /** Read off a zero-arg accessor's returned record on the parent's backing class. */
    record FromAccessor(TableRef keyOwner, AccessorRef accessor) implements ServiceKeySource {}
}
```

All three arms emit through `buildKeyExtraction(sourceKey, keyOwner, sourceExpr)`; only the source expression differs, and the first two share `SOURCE_FROM_ENV`. `keyOwner` is the table whose `Tables.X.COL` constants the columns are read through, and the key columns derive from it (`keyOwner.primaryKeyColumns()`) rather than being stored beside it, so a future arm cannot set the two inconsistently. When the `@sourceRow` follow-up lands and makes columns the independent axis, that derivation widens deliberately at one place.

**Why the two `env.getSource()` arms stay distinct despite emitting the same expression.** They differ on the one axis `ChildField.sourceShape()` reports: a `@table` parent is a `TableBackedType` and puts `SourceShape.Table` at the source; a `JooqTableRecordType` parent is a `ResultType` and puts `SourceShape.Record`. `SourceShapeProjectionTest` asserts, for every classified `ChildField` in `ClassifiedCorpus`, that `sourceShape()` equals the parent GraphQL type's independently-classified backing under exactly that rule, so the two cannot be collapsed into one arm and still answer it. A single `FromSource` arm spanning both would make the derivation below partial.

This replaces `TypeFetcherGenerator.buildServiceDataFetcher`'s bare `TableRef prt` parameter, which is the parent table today and would be `null` on every record parent.

## Source shape becomes a derivation, and that is the test this item has to satisfy

`ChildField.sourceShape()` answers `SourceShape.Table` for `ServiceTableField` and `ServiceRecordField` today. Both leaves already span both parent kinds (`classifyChildFieldOnResultType`'s `TableBound` arm mints a `ServiceTableField` on a record parent right now), so the current answer is latent-wrong and only survives because no corpus example reaches it. Minting `ServiceRecordField` on a DTO parent makes it live: `SourceShapeProjectionTest` fails the moment a `Table` answer meets a `PojoResultType` parent.

A leaf-identity split cannot fix it, because neither leaf is parent-kind-pure. The stored `ServiceKeySource` is what carries the fact, which is why its arms are cut on the source-shape seam:

* `FromTableRow` → `SourceShape.Table`
* `FromHeldRecord`, `FromAccessor` → `SourceShape.Record`

Two obligations ride along, and both are build-enforced rather than optional:

* The corpus must reach the new arms, or they must carry a typed exemption on `ExemptionRegistry.SOURCE_SHAPE_CORPUS`; `everyChildFieldLeafIsCoveredOrDocumented` fails otherwise. Reaching them is the right answer here, and the pipeline fixtures below are the vehicle.
* The new component inherits `sourceKey`'s nullability: all four classify sites thread `sourced == null ? null : buildServiceSourceKey(sourced)`, so a `@service` field whose method declares no `Sources` parameter carries a null key and would carry a null key source, leaving `sourceShape()` nothing to derive from. Decide that case explicitly rather than letting the derivation NPE. The leaves' compact constructors are the seat for a `(sourceKey == null) == (keySource == null)` pin, and the validator additions below are what make the null pair unreachable past validation, which is the argument for an invariant throw rather than a guessed default.

## Implementation

* `model/ServiceKeySource.java`, `model/ParentKeyResolution.java` (new). The two sealed facts above.
* `ServiceCatalog.reflectServiceMethod`: swap `parentPkColumns` + `pkLessParent` for the resolved `ParentKeyResolution` value; carry `Available.source().keyOwner().primaryKeyColumns()` into `MethodRef.Param.Sourced`. Delete `ServiceCatalog.PkLessParent`. The `isRoot` derivation in `ServiceDirectiveResolver.resolve` reads the `Root` arm instead of inferring root from an empty list. Shape depends on R649's phase split, per the section above.
* `ServiceCatalog`: a `Set<XRecord>` / `List<XRecord>` element whose class does not resolve through `resolveTableByRecordClass` keeps its existing diagnostic; `dtoSourcesRejectionReason`'s trailer, which currently points at `@sourceRow` as the analogous solution for record-backed parents, is repointed at the typed-record route this item creates. Note that the DTO-sources diagnostic is gated on `!parentPkColumns.isEmpty()` today, so it does not fire on a record-backed parent at all; re-expressing that gate against `ParentKeyResolution` widens its reach to the coordinates this item admits, which is the intended reading and should be stated in the replacement rather than inherited by accident.
* `FieldBuilder`: the record-parent key resolver, with the `JooqTableRecordType` arm reading `jtr.table()` and the accessor arm described below.
* **Accessor discovery is new, not a reuse of `collectAccessorMatches`.** The earlier draft claimed the reduction step could key on element class instead of field name because "only the per-method match logic is shared". That is wrong: `collectAccessorMatches` enumerates through `ClassAccessorResolver.enumerate(parentClass, accessorBaseName, ...)`, and the name rules, the `is`-prefix gate, and the member filter are single-sourced *there*, ahead of the per-method match. Name matching therefore happens before anything this item could reduce differently. What is actually needed is a name-free candidate mode on `ClassAccessorResolver` (all public zero-arg non-bridge non-synthetic instance methods) plus this item's own reduction, "the parent class's sole zero-arg accessor returning `X`", with its own more-than-one rejection arm. Adding a mode to a deliberately single-sourced helper has blast radius across `resolve`, `probe`, and `derivePolymorphicHubSource`; budget for it rather than calling it reuse.
* `FieldBuilder.classifyChildFieldOnResultType`'s `DIR_SERVICE` branch: the `Result` and `Scalar` arms stop returning the deferred rejection and build `ServiceRecordField` the way the `TableBound` arm already builds `ServiceTableField`. The `Polymorphic` arm keeps its deferral (doubly out of scope, unchanged).
* `ServiceDirectiveResolver.validateTableRecordSourceParentTable`: gate on the table-parent arm. On the record-parent path the element type is the resolver's input and the check is vacuous.
* `ChildField.ServiceTableField` / `ChildField.ServiceRecordField`: add one `ServiceKeySource` component. No `KeyLift`, and no component named `sourceShape`: `ChildField.sourceShape()`'s default projection already answers `SourceShape.Table` for both leaves, and a same-named record component would silently override it and leave those arms dead. Update the two arms to derive from the new component, per the source-shape section above.
* `GeneratorUtils.buildKeyExtraction`: add the `CodeBlock sourceExpr` overload its `buildKeyExtractionWithNullCheck` sibling already has, defaulting to `SOURCE_FROM_ENV`. All three wrap arms spell `env.getSource()` inline in their format strings today, so the parameter threads through all three even though only `TableRecord` is reachable from this path; a `Wrap.Row`-only assertion of the kind the null-check sibling carries is the alternative, and either is fine as long as the choice is deliberate rather than a half-threaded parameter.
* `TypeFetcherGenerator.buildServiceDataFetcher`: take `ServiceKeySource` where it takes `TableRef prt`, and emit the accessor arm's source binding ahead of the shared extraction body. The record-parent preludes (null-source guard, `Outcome.Success` narrowing) are the ones `buildBatchedDataFetcher` already builds; `resultType` and `sourceIsOutcome` are in scope at both service cases of the emit switch.
* `GraphitronSchemaValidator`: `validateServiceTableField` returns early for a non-table parent ("no DataLoader key needed"), and that early return is now wrong. More importantly, `validateServiceRecordField` carries *none* of the sibling's checks (no Sources-required, no parent-PK; it validates only the `@reference` path), and `ServiceRecordField` is the leaf the `Result` and `Scalar` arms mint, so it is the one this item most needs mirrored. State its post-change invariant outright ("a Sources parameter exists, and its columns are the key-owner table's primary key") and add it there in this change, rather than generalising a check that lives on the other leaf. The three new classify-time rejections (parent cannot produce the declared key, more than one matching accessor, list-cardinality accessor) each need a named validate-time counterpart.

## Out of scope

* **`@sourceRow` as a `@service` key declaration.** Grounded above: no catalog anchor for the columns, and admitting it means making `MethodRef.Param.Sourced`'s columns axis optional. A parent carrying only scalar FK columns and no typed record therefore still has no route, and the new diagnostic must not promise one. Filed as a follow-up (see below) so the gap is tracked rather than implied.
* **List-cardinality accessors.** An accessor returning `List<XRecord>` fans one parent out to many keys, which on the table-child path is the `LOAD_MANY` dispatch. The service path's `Map<Key, Value>` return contract assumes one key per parent, so the many case is rejected by name here and left to a follow-up that designs the fan-in.
* **Polymorphic child `@service`.** Unchanged deferral.
* **The rejection-precedence rule itself.** R649's, per the ordering note above.

## User documentation (first-client check)

New subsection in `docs/manual/how-to/handle-services.adoc`, after the existing `Map<Key, Value>` section. Draft:

> === Batching a child `@service` on a class-backed parent
>
> A child `@service` batches against a key its parent can produce, and the SOURCES parameter's element type names that key. When the parent type carries `@table`, the element is the parent's own record and the key is its primary key, so there is nothing to think about. When the parent is class-backed (a DTO a service returned, or a Java type an accessor chain reached), the element names whichever table the batch keys on, and the parent has to be able to produce it.
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

Write the replacement contract line at the altitude the "Desired outcome" section states it, not at the narrower one this item happens to implement. "The keys carry the key columns, and nothing else" survives the `@sourceRow` follow-up; "the key is whatever the SOURCES element type names" would have to be retired one release later.

## Tests

* **Unit** (`ServiceCatalogTest`): the three `ParentKeyResolution` arms through `reflectServiceMethod`, including that a `Root` resolution still reaches the batch-at-root diagnostic and a `Rejected` resolution surfaces its own rejection rather than the argument-name mismatch. The `Rejected` case is the pin that this item did not re-mask what R649 unmasks.
* **Pipeline**, modelled on `PkLessParentServiceSourcesRejectionTest`: a DTO parent with a typed accessor classifies to `ServiceRecordField` carrying `ServiceKeySource.FromAccessor` and a `SourceKey` whose columns are the element table's PK and whose wrap is `Wrap.TableRecord`; a `JooqTableRecordType` parent classifies to `FromHeldRecord`; a table parent is unchanged and still carries `FromTableRow` (the regression pin for the `ParentKeyResolution` swap); and the rejection fixtures for no producer, more than one matching accessor, and a list-cardinality accessor. No arm asserts a `KeyLift`: these leaves carry none, and asserting `KeyLift.Accessor` alongside the service `SourceKey` is not merely redundant but unsatisfiable, since `KeyLift.checkResidueAgreement` would compare `Accessor`'s derived `Wrap.Record` against the stored `Wrap.TableRecord` and throw.
* **Pipeline**, `SourceShapeProjectionTest`: the DTO-parent and `JooqTableRecordType`-parent fixtures above land in `ClassifiedCorpus` so the mirror walk reaches the new `Record`-answering arms, discharging the `ExemptionRegistry.SOURCE_SHAPE_CORPUS` obligation by coverage rather than by exemption.
* **Compilation**: a fixture schema whose emitted fetcher exercises the record-arm key extraction, so the fork in `buildServiceDataFetcher` is compiled rather than only asserted on.
* **Execution**: one test asserting that N parents sharing a key invoke the service once with a deduplicated key set, and that the keys arrive sparse. Both are contract claims the docs make and neither is observable at the pipeline tier.

## Retired vocabulary

* `parentPkColumns` (the parameter name and the concept: the batch key is no longer the parent's PK by definition)
* `ServiceCatalog.PkLessParent`
* "the batch key must be lifted through the parent chain to the rooted `@table`" (the deferred rejection text)
* "the keys carry the parent's primary key" (the docs contract statement, in all its paraphrases)
* "@service on a record-backed parent is not yet supported"

Deliberately *not* retired, and load-bearing that they are not: "`@service`-backed fields never carry a lift" and its two siblings in `SourceKey`'s wrap-provenance paragraph and `docs/architecture/explanation/dispatch-axes.adoc`. An implementation that has to retire those three has taken the lift-driven shape this spec rejects, and the sweep at the Done gate should read their survival as a check on the design rather than as nothing to do.

## Roadmap entries

Two follow-ups to file when this ships, both named in "Out of scope" above:

* `@sourceRow`-declared batch keys for `@service` children on parents carrying only scalar key columns, which needs a column-naming surface or an optional columns axis on `MethodRef.Param.Sourced`.
* List-cardinality accessor keys on the `@service` path (the `LOAD_MANY` fan-in against a `Map<Key, Value>` service contract).

## Provenance

Reported twice by the same consumer team, most recently on a type aggregated in Java whose child is produced by a shared translated-text service.

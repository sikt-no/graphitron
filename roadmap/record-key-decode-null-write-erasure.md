---
id: R534
title: "An explicit null for a NOT NULL column survives a later plain value in a service's jOOQ record"
status: Spec
bucket: architecture
priority: 5
theme: codegen-correctness
depends-on: []
created: 2026-07-25
last-updated: 2026-11-19
---

# An explicit null for a NOT NULL column survives a later plain value in a service's jOOQ record

## Goal

A client that sends an explicit `null` for a field bound to a `NOT NULL` column, in an input a `@service` method receives as a jOOQ record, gets that null written and validated whatever else the same input sends. When this item lands, the shape a consumer hit is pinned by execution tests, that consumer's own reproducer is green on a release cut from trunk, and the one path that can still lose the null is named in the roadmap node whose closing removes it.

```graphql
input FilmRecordDraftInput {
    id: ID @nodeId(typeName: "Film")
    title: String @field(name: "title")              # film.title is NOT NULL
    description: String @field(name: "description")
}

countFilmRecordDrafts(in: [FilmRecordDraftInput!]!): FilmDraftsCountedPayload
    @service(service: { className: "...FilmRecordDraftService", method: "count", argMapping: "drafts: in" })
```

```graphql
countFilmRecordDrafts(in: [{ title: null }])                      # refused at title
countFilmRecordDrafts(in: [{ title: null, description: "X" }])    # must be refused at title too
```

The service is handed a `FilmRecord`. Each column of a jOOQ record carries a *touched* flag, jOOQ's note of which columns the caller set: `store()` and `batchUpdate` write only touched columns, and the generated validation checks only touched columns. A null that loses its flag is therefore neither checked nor written, and the client sees success with the old value kept.

The reference page already states the rule, in `docs/manual/reference/directives/service.adoc`: "A field sent as `null` writes `NULL`, on a `NOT NULL` column too, whatever else the input sends." Today that sentence is demonstrated only where the other thing the input sends is a node id. This item demonstrates it for a plain value, which is what the consumer sent.

## The defect and where it still lives

Every `Record.fromArray` (and every other `from()` load) ends with jOOQ's `Tools.resetTouchedOnNotNull`, which clears the touched flag of every column that is null-valued and whose `DataType` is `NOT NULL`. A helper that runs `set(COLUMN, null)` for an explicit null and afterwards loads anything else into the same record through `fromArray` loses the null. A nullable column is spared, so `film.description` keeps a `NULL` write that `film.title` would lose.

Two helpers build a jOOQ record from an input object:

* `BuildEmitter.recordHelper`, emitted from the fact store (the database of schema, catalog and classpath facts the generator reads). It is correct: it loads every sent value through `fromArray`, then sets every explicit null, then decodes node ids through `NodeIdEncoder.decodeInto`, which writes each key column with `set`. No `fromArray` reaches the record after a null is set, and its javadoc says why.
* `JooqRecordInstantiationEmitter`, the classified model's helper, which is being retired. It still has the defect, for a later key decode and for a later plain value alike, and the javadoc on `emitKeyDecode` calls it one.

Which helper a `@service` field gets is decided by `ServiceEmission.verdict`: `EMITTED` means the store-fed fetcher and the first helper, anything else falls back to the classified fetcher and the second. `EmissionCensusTest.theCensusOfTheExampleSchemasServices` pins every service of the example schema as `EMITTED`, the `assignOccupantRecord` family included, so nothing in this repository reaches the second helper any more.

A consumer's schema can still reach it, through a verdict other than `EMITTED` that no build error accompanies. Closing that is not this item's: it belongs to the node "Every service verdict is emitted or a stored deferral" of R876 (`derived-read-cost-is-a-shape-problem.md`), which closes when the verdict can only be `EMITTED` on any graph the store accepts, and deletes the classified fallback in the same commit. That node records that on sis every service not emitted already carries a stored error defect (a build error the store states as a row), so on current trunk a sis service is either built by the correct helper or refused by the build.

### Consumer report

sis, Graphitron 10.0.0-RC47, jOOQ 3.20.11, Oracle, 2026-11-11 (sis differential-test finding M6, 2026-10-19). `EndreKullInput` binds `navn: String` to `STUDIEKULLNAVN`, a `NOT NULL` column, followed by `navnEn: String` on a nullable column; `endreKull` is a `@service` mutation taking `List<KullRecord>` and running `ctx.batchUpdate(records)`. With `{ navn: null, navnEn: "X", ... }` the generated record builder ran `rec.set(KULL.STUDIEKULLNAVN, null)` and then `rec.fromArray(new Object[]{ value }, KULL.STUDIEKULLNAVN_ENGELSK)`, which cleared the flag the first call raised. The `@NotNull` check was skipped and `batchUpdate` left the column out: `errors: null`, the old name kept, the element's other fields written. `rec` is the classified helper's local, so RC47 built this record with the second helper.

Graphitron 9 answered the same request with a validation error, because its generated validator chose which properties to check by the arguments the client sent, not by the record's flags (`RecordValidatorMethodGenerator` at tag `v9.3.0`). That is the baseline sis's reproducer holds: `EndreKullMutationIT.endreKull_eksplisittNullINavn_girUgyldigInputUtenAaLagre` expects `UgyldigInput` and no write, and is red until the fix reaches it.

## Implementation

No generator behaviour changes. `BuildEmitter.recordHelper` already orders its loads correctly, and `JooqRecordInstantiationEmitter` is not patched: a fix there is work on code the R876 node above deletes.

Two comments state the erasure wrongly and are corrected, so what a reader finds matches the tree:

* The class javadoc of `CustomerRecordService` in `graphitron-sakila-service` still describes a present-null nested leaf as collapsing to omitted when the identity group is present. `GraphQLQueryTest.customerUpsert_explicitNullNestedLeaf_besideADecodedNodeId_writesNull` pins the opposite. The sentence is rewritten to the behaviour that test pins.
* The javadoc on `JooqRecordInstantiationEmitter.emitKeyDecode` and `emitColumnBinding` attributes the reset to a later key decode and to every null-valued column. It is narrowed to the mechanism as verified: any later `fromArray` load, and only a null on a `NOT NULL` column.

## Tests

Two execution-tier cases in `GraphQLQueryTest`, beside the cases they complete. Both send the null first in declaration order and a plain value after it, the order the consumer's input had.

* At the flag: `customerUpsert(in: {details: {firstName: null, lastName: "X"}})`, with no identity group and so no decode. `customer.first_name` is `NOT NULL`. `CustomerRecordService.describeCustomerUpsert` reports the flags, and the case expects `first[changed=true,val=null]` beside `last[changed=true,val=X]`. It sits with `customerUpsert_explicitNullNestedLeaf_noIdentityDecode_writesNull`.
* At the check: `countFilmRecordDrafts(in: [{ title: null, description: <a value its constraints admit> }])` expects no count and one error whose path ends in `title`. It sits with `countFilmRecordDrafts_nullForANotNullColumnBesideADecodedNodeId_isWrittenAndChecked`, and shows the validation following the write for a plain later load as that case does for a decoded id.

A pin that cannot fail pins nothing, so before the item moves to In Review the implementer swaps the value loop and the null loop of `BuildEmitter.recordHelper` in a working copy, runs both new cases, and states in the In Review commit message that both went red. The swap is not committed.

No test asserts the order of statements in the generated helper. Behaviour is pinned at the pipeline tier and above (`docs/architecture/principles/development-principles.adoc`), and these two cases are the enforcer of the ordering the helper's javadoc explains.

## Consumer confirmation

Once the two cases are on trunk, sis runs `EndreKullMutationIT.endreKull_eksplisittNullINavn_girUgyldigInputUtenAaLagre` against a release cut from trunk at or after that commit, and the outcome is recorded here before In Review. Three outcomes, each with one owner:

* Green. The consumer half of the goal is delivered.
* The build refuses `endreKull` or its input with a stored defect. The field was not emitted from the store and the build said so; the defect names what to change, and the reproducer is rerun once the field builds.
* Red, with the build green. Read which helper built `KullRecord` in sis's generated fetcher. The store's helper means the first helper loses the null on a shape the two cases here do not cover, and this item returns to Spec with that shape. The classified helper means a service fell back without a defect, which is a gap in the R876 node above and is reported there; this item does not patch the classified helper in response.

## Roadmap entries

* The R876 node "Every service verdict is emitted or a stored deferral" gains a sentence naming this erasure among what its closing removes, with `JooqRecordInstantiationEmitter` beside the fallback it deletes, so the residual has an owner after this file is gone.
* On Done, `changelog.md` records the two cases, the sis outcome, and that hand-off.

## Other solutions we've considered

* **Port the store helper's order to the classified helper** (every `fromArray` load first, explicit nulls last). It would fix a consumer service that falls back without a defect, at the cost of changing every helper that emitter generates, its overlap-agreement path included, in code about to be deleted. Chosen against by Filip, 2026-11-19, in favour of letting the path close with its owner.
* **Load values without `fromArray`**, by per-column `set` with an explicit conversion, so the order stops mattering. `fromArray` is there for the column's own `DataType` and `Converter` coercion; replacing it is a separate design question with a wider surface than this defect.
* **Validate by what the client sent**, as Graphitron 9 did. It restores the validation error and leaves the write dropped wherever no constraint covers the column, which reproduces the erasure Alf ruled a bug (2026-11-10).
* **Tell the consumer which services still use the classified helper**, by a build warning or a third generate-time refusal beside `GraphQLRewriteGenerator.unemittedProjections` and `unemittedOutboxes`. A warning is new machinery in code about to be deleted. A refusal raised in Java at generate time is what `docs/architecture/explanation/defects-as-facts.adoc` rules out: what the generator cannot emit yet is a stored deferral, and the R876 node already turns each remaining verdict into one.

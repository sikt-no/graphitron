---
id: R534
title: "An explicit null for a NOT NULL column survives a later plain value in a service's jOOQ record"
status: In Progress
bucket: architecture
priority: 5
theme: codegen-correctness
depends-on: []
created: 2026-07-25
last-updated: 2026-11-20
---

# An explicit null for a NOT NULL column survives a later plain value in a service's jOOQ record

## Goal

A client that sends an explicit `null` for a field bound to a `NOT NULL` column, in an input a `@service` method receives as a jOOQ record, gets that null written and validated whatever else the same input sends. When this item lands, the shape a consumer hit is pinned by execution tests, that consumer's own reproducer is green on a release cut from trunk, and the one helper that still carries the defect, which no consumer's schema reaches any more, is named in the roadmap node that deletes it.

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

Which helper a `@service` field gets was decided by `ServiceEmission.verdict`: `EMITTED` meant the store-fed fetcher and the first helper, anything else fell back to the classified fetcher and the second. Since R876's service flip every service the store owns takes the first; `ServiceVerdictCoverageGateTest` holds that every fact document's service is emitted or carries a stored error defect.

No consumer's schema reaches it since R876's service flip (`derived-read-cost-is-a-shape-problem.md`): a service the store owns is emitted from the store or the build stops on a generator bug, and a verdict other than `EMITTED` on a graph the store accepts is that bug, so there is no fallback to the classified fetcher. The second helper is reached only by the classified model's own tests, and it goes with the walk's service code, under R876's node "The walk's service path is gone".

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

No test asserts the order of statements in the generated helper. Behaviour is pinned at the pipeline tier and above (`docs/architecture/principles/development-principles.adoc`). The enforcer of the ordering the helper's javadoc explains is `RecordBuildEmitterTest.anExplicitNullOnANotNullColumnSurvivesAValueLoadedAfterIt`, which runs the emitted helper on a flat input and already sends a plain value after the null; these two cases are the execution-tier demonstration above it, through a grouping and through the validation that follows the write. The swap is run against that test too.

## Consumer confirmation

Once the two cases are on trunk, sis runs `EndreKullMutationIT.endreKull_eksplisittNullINavn_girUgyldigInputUtenAaLagre` against a release cut from trunk at or after that commit, and the outcome is recorded here before In Review. Three outcomes, each with one owner:

* Green. The consumer half of the goal is delivered.
* The build refuses `endreKull` or its input with a stored defect. The field is not emitted from the store and the build said so; the defect names what to change, and the reproducer is rerun once the field builds. A build that stops on a generator bug naming the service is R876's, and is reported there.
* Red, with the build green. No service falls back any more, so the store's helper built `KullRecord` and loses the null on a shape the two cases here do not cover. This item returns to Spec with that shape.

### The run of 2026-11-20

Green, on a snapshot of trunk rather than a release: no release has been cut since `ad8d21c`, so whether this run stands in for the one the paragraph above names is open.

* **What ran.** sis's `sis/upgrade-graphitron` at `a30f72c6bc`, the commit after the one adding the reproducer, with `sis-graphql-spec` and `sis-app` built against Graphitron `10-SNAPSHOT` installed from trunk `4df4e926e`, which contains `ad8d21c`. `sis-jooq` and `sis-service` were sis's own jars of 2026-11-17, which no later sis commit changes. sis's `systemtest-ci` profile deployed the war to WildFly 40.0.1 over the local FS Oracle image, and only `EndreKullMutationIT` ran.
* **Result.** The reproducer passes, and so does `endreKull_gyldigInput_oppdatererKull`, the control that a valid element is written through the same build. The class's other three cases fail with a top-level "A nested transaction in this field failed": a service that opens its own transaction fails the field on a claimed exception, which sis tracks apart from this defect and which fails the same three on RC47.
* **Baseline.** The same class in the same environment, from an unedited copy on `10.0.0-RC47`: the reproducer fails, `data.endreKull.kull` carrying the element where null is expected, and the control passes.
* **Which helper.** Trunk built the record with `BuildEmitter.recordHelper`: the values for `STUDIEKULLNAVN_ENGELSK` and `STATUS_EKSPORT_LMS` loaded by `fromArray`, then the explicit null on `STUDIEKULLNAVN` set, then the node ids decoded by `decodeInto`. The fetcher validates every touched column before it constructs `EndreKullService`, so the `@NotNull` on `STUDIEKULLNAVN` refuses the element before the service's own transaction is opened. RC47 built it with the classified helper, `rec.set(KULL.STUDIEKULLNAVN, null)` followed by `rec.fromArray(..., KULL.STUDIEKULLNAVN_ENGELSK)`, the sequence the consumer report names.
* **What the copy changed.** sis does not build on trunk unchanged, and none of what trunk refused is `endreKull`, `EndreKullInput` or a `KULL` column. In a scratch copy only, not on sis's branch: eleven FSKODE-owned types a `@nodeId` names declared `@node(resolvable: false)`, implementing `Node` with an `id` (`NODE_ID_TYPE_NAME_UNDECLARED_STUB`, then `NODE_WITHOUT_NODE_INTERFACE`; two `COLUMN_UNRESOLVED` refusals on `studienivaer` went with it); the `kullterminer` and `terminer` filters' `@reference` paths written on to `TERMIN` (`NODE_ID_LANDS_ON_TABLE`); the two `aktivInnenforPeriode` filters removed (`MEMBER_UNKNOWN`); `PersonProfil`'s `eierOrganisasjonskode fodselsnummer` key removed (`KEY_FIELD_NOT_A_COLUMN`); `FeideBruker`'s `@defaultTenant` dropped, its key made `resolvable: false` and sis's `personProfil` on it removed; and `sis-app`'s runtime wiring ported from `RequestTenants` and `SessionState` to the generated `ConnectionSources.mounting`, the key with no tenant read on the home organisation as the old default did.

## Roadmap entries

* The R876 node "The walk's service path is gone" gains a sentence naming this erasure among what its subtract removes, with `JooqRecordInstantiationEmitter` beside the service leaves it deletes, so the residual has an owner after this file is gone.
* On Done, `changelog.md` records the two cases, the sis outcome, and that hand-off.

## Other solutions we've considered

* **Port the store helper's order to the classified helper** (every `fromArray` load first, explicit nulls last). Chosen against by Filip, 2026-11-19, while a consumer's service could still fall back to that helper, in favour of letting the path close with its owner. R876's service flip has since closed it: the port would now change only what the classified model's own tests exercise, in code about to be deleted.
* **Load values without `fromArray`**, by per-column `set` with an explicit conversion, so the order stops mattering. `fromArray` is there for the column's own `DataType` and `Converter` coercion; replacing it is a separate design question with a wider surface than this defect.
* **Validate by what the client sent**, as Graphitron 9 did. It restores the validation error and leaves the write dropped wherever no constraint covers the column, which reproduces the erasure Alf ruled a bug (2026-11-10).
* **Tell the consumer which services still use the classified helper**, by a build warning or a generate-time refusal. Considered while a service could still fall back. A refusal raised in Java at generate time is what `docs/architecture/explanation/defects-as-facts.adoc` rules out, what the generator cannot emit yet being a stored deferral, and the service flip has since left no such service to name.

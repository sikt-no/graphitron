---
id: R275
title: Error channel and data projection for source-record-carrier @service mutations
status: Spec
bucket: structural
priority: 2
theme: mutations-errors
depends-on: []
created: 2026-06-02
last-updated: 2026-06-02
---

# Error channel and data projection for source-record-carrier @service mutations

A `@service` mutation whose service method returns a single jOOQ table `Record` (not a `@record`-annotated payload object) into a payload type that pairs a `@splitQuery`/`@table` data field with an `errors` union field gets **no error channel and no data-field projection**. This shape is pervasive in `opptak-subgraph`: every saksbehandling mutation migrated from admissio uses it (`tilordneSaksbehandlerV3`, `fjernTilordnetSaksbehandler`, `settMerknadV2`, `endreSaksbehandlingsstatusV3`, `kvalifiserKravelementV3`), with payloads shaped `{ sak: Sak @splitQuery, errors: [SomeError!] }` and a service returning `SakRecord`. It accounts for 10 of the 17 failsafe failures in `opptak-subgraph/target/failsafe-reports` (see the failure map below).

R244 built the typed `Outcome<T>` channel, but only for `@service` mutations whose method returns a `@record` payload (`ReturnTypeRef.ResultReturnType`); `FieldBuilder.resolveServiceOutcomeChannel` (`FieldBuilder.java:~2068-2119`) returns `NoChannel` for anything else. R268 (shipped, `fc365b4`) added the `@table`-bound data-field arm-switch, but only *inside* those Outcome payloads. DML uses the localContext transport. The source-record-carrier `@service` shape classifies as `MutationServiceTableField` over a `TableBoundReturnType` (`FieldBuilder.java:~3196-3198`) and falls through all three: its generated fetcher is `try { ... return DataFetcherResult.data(record); } catch (Exception e) { return ErrorRouter.redact(e, env); }` with no `Mapping[]`, and its payload's data field gets no registered datafetcher, so graphql-java's default `PropertyDataFetcher` reads a non-existent `getSak()` off the returned `SakRecord` and yields `null`. This item brings that shape onto the existing `Outcome` transport so the R244 error mapping and R268 arm-switch resolve both the data field and the errors field, and fixes the success-arm errors value to honour SDL nullability.

## Failure map (opptak-subgraph failsafe)

The 17 failures decompose into four buckets; this item owns three of them, all graphitron-side. The fourth is consumer-side and out of scope.

* **B, error path redacted (8 failures), owned here.** `TilordneSaksbehandlerV3IT.{sakFinnesIkke, nyBrukerManglerRolle, ledigSakAlleredePlukket}`, `FjernTilordnetSaksbehandlerIT.{sakTilordnetAnnenBruker, sakFinnesIkke}`, `SettMerknadV2IT.merknadFinnesIkke`, `EndreSaksbehandlingsstatusV3IT.sakFinnesIkke`, `KvalifiserKravelementV3IT.tomResultatkravlisteReturnererFeil`. The service throws a domain exception mapped via `@error(handlers:[{handler: GENERIC, className: ...}])`, but with no channel the exception is redacted to a generic `DataFetchingException` instead of landing in the typed `errors` union.
* **D, success-path data field null (2 failures), owned here.** `SettMerknadV2IT.oppretterMerknad` (`sak.id` null), `EndreSaksbehandlingsstatusV3IT.endrerTilUnderBehandling` (`sak.statuskode` null). The payload's `sak` field has no datafetcher; the default `PropertyDataFetcher` cannot read it off the returned `SakRecord`.
* **C, success-arm `errors: []` should be `null` (5 failures), owned here.** `KvotetypeIT.{opprettKvotetype, oppdaterKvotetype}`, `SettSaksdokumentErLestIT.{oppretterRadVedUpsertHvisIkkeFinnes, markererSomLest}`, `SettTilbudsgarantiIT.setterTilbudsgarantiTilTrue`. These already get the Outcome channel (they return `@record` payloads), but `FetcherEmitter`'s `WrapperArm` transport (`FetcherEmitter.java:~240-249`) resolves the nullable errors field to `List.of()` on the success arm, so the wire shows `errors: []` where the consumer contract (admissio parity) requires `null`. This is also a precondition for the bucket-B/D success tests, which likewise assert `data.<field>.errors` is `null`.
* **A, `SubselectionNotAllowed` (2 failures), NOT owned here, consumer-side.** `KvotetypeIT.{slettKvotetype, slettIBruk}` query `kvotetype { id }` against a payload field declared `kvotetype: ID` (a scalar), rejected at validation before any resolver runs. Graphitron emitted exactly what the SDL declares; the fix is in the consumer test (select `kvotetype` as a scalar) or the consumer schema. Tracked back to the `fs-plattform` team, not against graphitron.

## Design: route the source-record carrier through the existing `Outcome` transport

`Outcome<T>` is the right carrier and is already non-null by construction, which is what closes the localContext silent-errors-drop that R244 documented. The move is to make the classifier recognise one more producer shape and reuse everything downstream.

The relationship that makes this clean: in R244's payload-returning case `Outcome.Success.value()` holds the `@record` payload and the payload's fields read off it; here `Outcome.Success.value()` holds the returned jOOQ record (`SakRecord`), and the payload's single `@splitQuery`/`@table` data field (`sak`) is a split-query field whose correlation key is exactly the record's primary-key columns. That is precisely R268's `@table`-bound arm-switch (`RecordTableField` / `RecordLookupTableField`): narrow `env.getSource()` to `Outcome.Success`, read the key off `success.value()`, dispatch the DataLoader, and return `completedFuture(null)` on the `ErrorList` arm. No new transport, no new arm-switch; the only novelty is that `success.value()` is the table record itself rather than a payload wrapping it.

The producer fetcher becomes the R244 shape it should have had: run the `@service`, return `Outcome.Success(record)` on the happy path, walk the per-field `ErrorMappings.<CONST>` and return `Outcome.ErrorList(List.of(cause))` on the first match, fall through to `ErrorRouter.redact` on no match. That single change converts bucket B from redacted to typed.

### Two carrier invariants, mirrored

The carrier is fully nullable on both arms, pinned by two classify-time rejection rules so a violating schema cannot be built:

* **Data/success field must be nullable** (existing `NonNullableSuccessProjectionField`): a non-null data field would raise `NonNullableFieldWasNullError` on the error arm and drop the sibling errors field. Already enforced; applies unchanged to the source-record carrier.
* **Errors field must be nullable** (new, this item): reject a payload whose `errors` field carries a non-null list type (`[X!]!`). This is the mirror of the success-projection rule: it is what makes `null` a legal success-arm value for the errors field, so the success arm can always emit `null`. A non-null errors field is an author mistake, surfaced as a classification error, not a generator limitation.

With both rules in force, the success arm emits `null` for the errors field unconditionally; there is no nullable-vs-non-null branch to maintain at the emit site.

## Implementation

* **`FieldBuilder` channel detection.** Generalise the service-outcome classification so a `@service` field over a `TableBoundReturnType` whose SDL payload carries an `errors` field (with at least one `@error` handler) and exactly one non-errors data field is classified as an `OutcomeType` (`ErrorChannel.Mapped`) rather than a channel-less `MutationServiceTableField`. The `OutcomeType`'s `successProjection` is that single data field; the `Outcome.Success` value type is the returned jOOQ record. Keep the channel-less `MutationServiceTableField` path for service-table fields that have no `errors` field (unchanged behaviour). Coordinate the `successProjection` population with R274 (see Coordination).
* **Producer fetcher emit.** For the new classification, emit the R244 catch-arm shape (the `ErrorMappings.<CONST>` walk producing `Outcome.ErrorList`, `redact` fallback) and the `Outcome.Success(record)` happy path, replacing today's bare `redact`-only catch. Reuse `ChannelCatchArmEmitter` / `ErrorMappings` generation already built by R244.
* **Data-field projection.** Register the data field's datafetcher via R268's `@table`-bound arm-switch (the `RecordTableField` / split-query path), source-bound to `success.value()`, with `completedFuture(null)` on the `ErrorList` arm. This is the missing registration that fixes bucket D; it is the same emit R268 produces for a `@table` data field sitting next to an errors field inside an Outcome payload.
* **Errors success-arm value.** Change `FetcherEmitter`'s `WrapperArm` transport so the success arm resolves the errors field to `null` instead of `List.of()`. Safe by the new errors-nullable rule. This fixes bucket C and the bucket-B/D success assertions.
* **New rejection rule.** Add an `ErrorChannelWalkerError` arm (e.g. `NonNullableErrorsField`) in the output-walking `ErrorChannelWalker` / `GraphitronSchemaValidator`, alongside `MultipleErrorsFields` and `NonNullableSuccessProjectionField`, with a matching LSP `graphitron.error-channel.*` diagnostic code and a `typed-rejection.adoc` entry.

## Tests

* **Execution (`@ExecutionTier`), primary net.** Add a sakila fixture: a `@service` mutation whose method returns a single table record into a payload `{ <entity>: <Table> @splitQuery, errors: [SomeError!] }`. Assert all three arms round-trip against the real generated fetchers: success returns `{data: {<entity>: {...}}, errors: null}` (not `[]`), the mapped-error path returns `{data: {<entity>: null}, errors: [{__typename: ...}]}`, and an unmapped exception redacts to a correlation id. Reproduces the `opptak-subgraph` `sakFinnesIkke` (B), `endrerTilUnderBehandling` / `oppretterMerknad` (D) failures in-tree.
* **Pipeline (`@PipelineTier`).** The source-record-carrier `@service` field classifies as an `OutcomeType` (not a channel-less `MutationServiceTableField`); its `successProjection` holds the single `@table` data field; the errors field is `WrapperArm` transport; a structural assertion that the data field resolves through a graphitron-emitted fetcher, never graphql-java's default `PropertyDataFetcher`. Model-and-registry assertions, not fetcher-body strings (per `rewrite-design-principles.adoc`).
* **Validation (`@ValidationTier`).** `NonNullableErrorsField` rejects `errors: [X!]!`; nullable `errors: [X!]` is accepted; the existing `NonNullableSuccessProjectionField` still rejects a non-null data field on this new shape.
* **Compilation (`@CompilationTier`).** `mvn -f graphitron-rewrite/pom.xml install -Plocal-db` green end-to-end, exercising the `CompletableFuture`-returning arm-switch over the erased `success.value()` source seam.

## Coordination with sibling items

* **R244 (Done):** owns the `Outcome` runtime type, the producer pattern, the `WrapperArm` errors transport, the `OutcomeType` classification, and the two author rules. This item is a fourth producer shape onto the same machinery; the new `NonNullableErrorsField` rule extends R244's rule family.
* **R268 (Done, `fc365b4` + `f4ae047`):** shipped the `@table`-bound data-field arm-switch this item reuses for the `sak` projection (the `RecordTableField` source-bound to `success.value()`, `completedFuture(null)` on the `ErrorList` arm), and the contextual structural invariant (`resolvesViaPropertyDataFetcher`) that every immediate child of a `WrapperArm` outcome type resolves through a graphitron-emitted fetcher. Both are in trunk and directly usable. Its as-built success arm emits `errors: []`, pinned by the execution-tier `GraphQLQueryTest.submitFilmReviewWithFilm_*` fixture; this item's bucket-C change to `null` is therefore an edit to shipped behaviour and must update that existing assertion (success arm now `errors: null`), not only the new fixture.
* **R269 (Spec):** null-guards split-query key extraction for nullable to-one records. The `sak` field here is a to-one record resolved by split query, so R269's null-guard applies to its key extraction. Logically independent; land in either order and rebase the helper shape.
* **R274 (Backlog):** populates `OutcomeType.successProjection` so the nullability invariant lives on the carrier rather than an inline loop. This item needs `successProjection` populated with the single source-record-backed data field; either R274 lands first and this item consumes the populated projection, or this item populates it for the new shape and R274 generalises. Decide ordering at Ready.

## Out of scope

* **Bucket A** (`slettKvotetypeV2` `kvotetype { id }` subselection): consumer-side test/schema mismatch in `fs-plattform`, not a graphitron defect.
* **Multi-data-field source-record carriers:** payloads with more than one non-errors data field backed by the returned record. The opptak-subgraph cases all have exactly one (`sak`). A multi-field carrier needs a defined mapping from the single returned record to several projection fields; defer until a real schema demands it.
* **DML transport changes:** DML mutations stay on the sentinel/localContext transport (R244's deferred DML-migration slice).

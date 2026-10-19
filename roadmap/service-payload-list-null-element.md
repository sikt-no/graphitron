---
id: R1064
title: "A service payload list with a null element throws while extracting keys"
status: In Review
bucket: bug
priority: 2
theme: service
depends-on: []
created: 2026-10-19
last-updated: 2026-10-19
---

# A service payload list with a null element throws while extracting keys

## Goal

A `@service` whose Java method returns a list of `@table` records with a `null` element, or with a
record whose key has no live row, returns the rows that do exist and leaves the others out, instead
of failing the field. Today a `null` element throws a `NullPointerException` while the generated
fetcher builds the re-fetch keys; the error router redacts it, so the client sees the list as `null`
plus an opaque error, although the mutation already committed the rows it knows about. This holds
both where the field returns the `@table` type directly and where it is a list field on a payload
type the service fills (a *carrier payload*: a payload type such as `FilmsServicePayload` whose data
field re-selects the returned records by primary key).

Using the sakila fixture's carrier payload, where the service's Java method returns
`[filmA, null, filmB]` and film `B`'s row has since been deleted:

```graphql
type FilmsServicePayload {
    films: [Film!]
}
```

```
today:  { "films": null }                + a redacted internal error (the NullPointerException)
after:  { "films": [{ "title": "A" }] }  and no error
```

The rule is the one the manual already states for a service returning `@table` records ("a key with
no live row drops out ... that element is absent from a list result"), extended to a `null` element
and applied to the carrier payload's list field, which today keeps a `null` slot for a key with no
row instead. Under `[Film!]` that slot is a non-null violation that nulls the whole list, which is
the same "committed rows hidden" failure by another route. A class-backed payload whose list
accessor returns `@table` records follows the same rule for a key with no row, and a carrier's
`[ID!]` field skips a `null` element the same way. A single-record result keeps resolving `null` for
no row, as now.

## Evidence

Surfaced by the sis v9 against v10 differential test (2026-10-19), on `endreTimeplaner`, a stable
mutation used in production. The service returns `null` for an id it does not know. The generated
`EndreTimeplanerPayloadFetchers.timeplaner` builds the batch keys with

```java
for (Object element : (Iterable<?>) success.value()) {
    Row9<...> key = DSL.row(((Record) element).get(Tables.UNDERVISNINGSTIMEPLAN.INSTITUSJONSNR_EIER), ...);
    keys.add(key);
}
```

and dereferences the `null` element. The client gets `timeplaner: null` plus a redacted error,
although the known rows were written. Graphitron 9 returned the known rows: its mapped-loader
result assembly (`AbstractFetcher.resultAsMap` in `graphitron-common` at `v9.3.0`) filters `null`
out of list values, so dropping is also the migration-compatible answer.

## Context

Three generated sites read records off a service's returned list, and they disagree today:

* **Root re-select** (the field's return type is the `@table` type, `Mutation.films: [Film]
  @service`). `TypeFetcherGenerator.emitServiceReentryLift` emits a loop
  `for (Record carried : result) keys.add(dsl.newRecord(PK...).values(carried.get(PK)...))`, and
  the companion rows method (`ReentryRowsFragments.projectedBody`, list arm) inner-joins the table
  to a `VALUES (idx, pk)` table ordered by `idx`. A key with no row produces no output row: the
  documented drop. A `null` element throws at `carried.get(...)`. The `result == null ||
  result.isEmpty()` gate runs before the loop, and the rows method has no empty-keys guard of its
  own (an empty `VALUES` is not valid SQL).
* **Carrier payload list field** (`ChildField.BatchedTableField` carrying
  `KeyLift.ProducedRecords` at `Arity.MANY`, the *source=target re-fetch*: the service hands back
  the target table's records and the field reads each one's primary key to re-project the
  `@table`). `GeneratorUtils.buildProducedRecordsKeyMany` emits the loop quoted above with no element
  check; `TypeFetcherGenerator.buildBatchedDataFetcher` dispatches `loader.loadMany(keys, ...)`; the
  rows method answers through `scatterSingleByIdx`, which by the DataLoader contract returns one value
  per key, `null` where no row matched. That `null` reaches the wire. `FieldBuilder
  .buildPayloadCarrierBatchedTableField` is the one construction site of `ProducedRecords`, so this
  is the only leaf on the path; the DML payload carrier (`@mutation` with a payload) mints the same
  leaf, which matters below.
* **Errors-carrying carrier** (the payload declares an `errors` field, so the producer's value
  travels inside the generated `Outcome` wrapper, *Success* or *ErrorList*).
  `ParentSourceBinding.OutcomeRecord.prelude` narrows to `Outcome.Success` and its `sourceExpr`
  reads `success.value()`, with no null check, while its sibling `DirectRecord.prelude` escapes on a
  `null` source. A service returning `null` (for the list, or for a single record) is wrapped as
  `new Outcome.Success<>(null)` by the mutation fetcher, so the data field throws dereferencing it:
  the list arm at the `(Iterable<?>)` loop, the single arm at
  `GeneratorUtils.buildKeyExtractionThroughLocals`' first column read. Under the direct envelope
  the same `null` makes the payload itself `null` and graphql-java never reaches the field.
* **ID carrier list field** (`ChildField.SingleRecordIdField`, a carrier's `ids: [ID!]` data
  field that encodes each returned record's node key without a re-select, because the records may
  be deleted rows). `FetcherEmitter.buildSingleRecordIdFetcherValue`'s list arm guards a `null`
  list but loops `for (row : source) ids.add(encode(row.get(...)))` with no element check, so the
  same `[filmA, null, filmB]` throws there too.

How a `loadMany` result becomes the field's list is a property of the loader's dispatch, not of how
the key was lifted. `LoaderRegistration.Dispatch.LOAD_MANY` is minted at three sites in
`FieldBuilder`: the carrier re-fetch (`buildPayloadCarrierBatchedTableField`) and the two
accessor-many arms (a class-backed payload whose typed list accessor returns `@table` records). Its
consumers already disagree: `MultiTablePolymorphicEmitter.buildBatchedListFetcher` flattens per-key
buckets, so the polymorphic accessor-many field drops a key with no row today, while
`DataLoaderFetcherEmitter.dispatchCall`'s `LOAD_MANY` arm, which every `BatchedTableField` goes
through, passes the `null` slots on.

## Implementation

* `GeneratorUtils.buildProducedRecordsKeyMany`: skip a `null` element (`if (element == null)
  continue;` as the loop's first statement). The key list then holds only real records; positions
  no longer correspond to the service's list, which the drop below makes unobservable.
* `DataLoaderFetcherEmitter.dispatchCall`, `LOAD_MANY` arm: follow the `loadMany` call with
  `.thenApply(rows -> rows.stream().filter(Objects::nonNull).toList())`, so every `LOAD_MANY`
  consumer assembles its list the same way: a key with no row is absent, which is what the
  polymorphic sibling already does by flattening. The rule then reads off the dispatch axis the
  emitter already switches on, with no fork on the lift and no new model fact. It reaches the
  accessor-many `BatchedTableField` too: a class-backed payload's list field now drops a key with no
  row instead of rendering a `null` slot, which brings it in line with its polymorphic twin. The
  rows methods keep returning one value per key (`scatterSingleByIdx`), as the DataLoader contract
  requires; the drop happens after the loader answers. `Stream.toList` compiles at release 17.
* `FetcherEmitter.buildSingleRecordIdFetcherValue`, list arm: skip a `null` `row` in the encode
  loop. There is no re-select on this field, so the null element is the only case.
* Javadoc made stale by the change, updated in the same commit:
  `LoaderRegistration.Dispatch.LOAD_MANY` (it says only the accessor-many arm reaches the dispatch,
  already false, and should state the drop), `KeyLift.ProducedRecords` (it says one `RowN` key per
  element, false once `null` elements are skipped), and `ParentSourceBinding.OutcomeRecord` (it
  says the backing object lives in `Success.value()`, which may now be `null` and escapes).
* `TypeFetcherGenerator.emitServiceReentryLift`, list arm: skip a `null` `carried` in the loop, and
  move the empty gate after the loop as `if (keys.isEmpty())`, keeping the `result == null` half
  ahead of it. A list of only `null` elements then answers `[]` without issuing an empty `VALUES`.
  The single arm is unchanged: its `result == null` gate already answers `null`.
* `ParentSourceBinding.OutcomeRecord.prelude`: escape when the narrowed value is `null` as well,
  `if (!(subject instanceof Success<?> success) || success.value() == null) return <escape>;`. This
  is `DirectRecord`'s null-source guard on the wrapped arm. The escape a caller passes already means
  "no backing object" on every consumer of the binding, which is the `BINDING_SEAM_LEAVES` bucket of
  the parent-source posture partition that `GeneratorCoverageTest` pins exhaustive; the
  envelope-carrying leaves' twin (`FetcherEmitter.emitRecordSourceLocal` followed by their own
  `source == null` checks) already escapes the same way. The net invariant is that a
  `Success(null)` source throws in no child: the field renders `null` and `errors` stays `null`, a
  success with no data. The pattern variable is definitely assigned on the right of `||`, so the
  shape compiles at release 17.
* `docs/manual/how-to/handle-services.adoc`: extend the "A key with no live row drops out" bullet
  and the matching gotcha bullet near the end of the page, per the draft below.

DML carriers ride the same leaf, so a bulk `@mutation` payload whose post-commit re-read finds no row
for a written key (a read policy hiding it) now drops that element rather than nulling it. That is
what `docs/manual/reference/directives/routine.adoc` already promises for a list data field ("a
re-read that finds no row renders an empty list"), and `mutation.adoc`'s "one entry per written row"
stays true for every row the caller may read.

A `null` *whole list* from the service keeps the answer each path gives it today, now without a
throw on the errors carrier: the root field resolves `[]` (the `result == null` gate), the direct
carrier's payload is itself `null`, and the errors carrier renders `films: null, errors: null`. The
three differ because the payload is or is not there to render; aligning them is not this item's.

Out of scope: a `null` *element* in a class-backed payload's list member read through an accessor
(`KeyLift.Accessor` at `Arity.MANY`, `GeneratorUtils.buildAccessorKeyMany`). It throws too, but key
extraction is per lift arm, that arm's comment records a deliberate choice to leave element nulls
unguarded, its key is a jOOQ `Record` built with `element.into(...)`, and the author owns the
backing class rather than returning bare records. It wants its own decision. A key with *no row* on
that arm is a dispatch question, and the `dispatchCall` change above does cover it.

## User documentation (first-client check)

The bullet in `handle-services.adoc` § the records are key carriers becomes:

> * *A record with no live row drops out.* If your method hands back a key the table has no row
>   for, or a `null` in place of a record, that element is absent from a list result, whether the
>   list is the field's own return type or a list field on a payload your method fills, and a single
>   result resolves `null`. This is worth a second look on a mutation `@service` that deletes rows
>   and hands the deleted records back: those rows are gone, so there is nothing to re-select.
>   Return the ids, or use the escape hatch below.

and the gotcha bullet's "a key the table has no row for is absent from a list result" gains "or a
`null` element". Both read simply; nothing else in the manual describes the carrier list's slots.

## Tests

Execution tier, in `ServiceListGapExecutionTest` (`graphitron-sakila-example`, beside
`ListCarrierServiceProducerExecutionTest`, same database setup), each case seeded with films it
inserts and deletes. The fixtures as landed: `FilmListGapService.filmsByIdsWithNulls` and
`filmsByIdsKeyOnly` (a class of their own, so the LSP completion tests that pin
`FilmCarrierService`'s method set stay put) feed the `[Film!]` carrier, the new `[Film]` `FilmsNullableServicePayload`
and (the `null`-element producer) the new direct-envelope `FilmIdsServicePayload`;
`FilmCarrierWithErrorsService.filmsByIdsWithNulls` feeds the existing
`FilmsCarrierWithErrorsPayload`; `SampleQueryService.filmsByServiceWithNulls` backs the root
`filmsByServiceWithNulls(ids: [Int]!)`; `CreateFilmsPayloadService
.recentlyCreatedFilmsBatchedWithMissingRow` is the accessor-many case on `CreateFilmsPayload`.
All ten cases fail against the generator before this change:

* Carrier `[Film!]`, `null` element: a new `FilmCarrierService` method returning `null` for an
  unknown id, behind a new mutation field typed `FilmsServicePayload`. Asserts the known films in the
  service's order and no errors. This is the reported failure; it reproduces today as `films: null`
  plus one error.
* Carrier `[Film!]`, key with no row: a sibling method returning a key-only `FilmRecord` for an
  unknown id. Asserts the known films and no errors (today: `films: null` and a non-null violation).
* Carrier `[Film]` (a new payload type with nullable elements), both cases above: asserts the list
  holds no `null`, pinning that the drop does not depend on element nullability.
* Errors-carrying carrier, `null` element: a new method on `FilmCarrierWithErrorsService` behind a
  payload shaped like `FilmsCarrierWithErrorsPayload`. Asserts the known films, `errors: null`, no
  field errors.
* Errors-carrying carrier, `null` list: the same method returning `null` for an empty id list.
  Asserts `films: null`, `errors: null`, no field errors.
* Root `@service` returning `[Film!]`, with a `null` element: asserts the known films; and with only
  `null` elements: asserts `[]` with no field error, pinning the moved empty gate.
* ID carrier `[ID!]`, `null` element: asserts the encoded ids of the known records only.
* Accessor-many class-backed payload (the existing `RateFilmsCheckedPayload`-style fixture, or a
  sibling), a key with no row: asserts the list drops it, pinning that the drop lives on the
  `LOAD_MANY` dispatch rather than on the carrier lift.

The compilation tier needs nothing new: `graphitron-sakila-example` compiles the changed emissions at
release 17 under `-Werror`. No generator-tier assertion is added; the emitted shape is covered by the
cases above, and body-text matching is banned.

## Other solutions we've considered

* **A `null` in the element's position on the carrier.** The Backlog direction's reading, by
  analogy with root lookups (one slot per input key). It keeps today's carrier behaviour for a key
  with no row and fixes only the throw. Rejected: a service's returned list has no client-side
  positional contract to preserve (the client sent ids, the service chose what to return), the
  manual already documents the drop for the same records on the root path, Graphitron 9 dropped,
  and under `[T!]`, the declaration every sakila carrier uses, the slot cannot render at all, so the
  client would still lose the committed rows.
* **Reject the element with a message naming the field.** Turns a service's ordinary "not found"
  answer into a field error and loses the rows the mutation committed, which is the outcome the item
  exists to remove.
* **Drop only on the carrier, forked on `KeyLift.ProducedRecords` in
  `TypeFetcherGenerator.buildBatchedDataFetcher`.** Smaller blast radius, but `LOAD_MANY` would then
  mean "drop" on the carrier and on the polymorphic accessor-many field and "keep a `null` slot" on
  the `BatchedTableField` accessor-many field, a meaning recovered from the lift and arity rather
  than read off the dispatch. If accessor-many genuinely needed `null` slots, that would be a
  typed fact minted at the `LOAD_MANY` sites, not a fork in the emitter; nothing in the manual or the
  tests asks for it.
* **Dispatch a `null` element as an all-`null` key** that matches nothing in the `VALUES` join.
  Works without the skip, but pays a key row per `null` for an answer the fetcher already knows, and
  the drop step would discard it anyway.

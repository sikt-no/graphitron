---
id: R1084
title: "A DML mutation without an error channel sends an over-long value to the database"
status: In Progress
bucket: architecture
priority: 4
theme: mutation-write
depends-on: []
created: 2026-10-21
last-updated: 2026-10-22
---

# A DML mutation without an error channel sends an over-long value to the database

## Goal

A `@mutation(typeName: INSERT | UPDATE | UPSERT)` field whose payload has no errors field, or that returns its table type directly, should reject a string too long for the column it writes before the statement runs. It should answer with a top-level GraphQL error that has classification `ValidationError`, a path to the offending input field and the limit in the message, the way Graphitron 9 did. Today the value reaches the database, the statement fails (ORA-12899 on Oracle, `22001` on PostgreSQL), nothing is written, and the client gets the redacted "An error occurred. Reference: ...". There is no build warning either. The user manual's column-length section also reads as if the build always warns.

An *error channel* is a payload's errors field: a union of `@error` types whose `handlers` say which failures land in it. R1025 added the length check for DML mutations that have one, gated on a `{handler: VALIDATION}` member. It kept mutations without one out of scope. This item extends the check to them.

The minimal pair, from the sakila example schema (`film.title` is `varchar(255)`):

```graphql
type FilmPayload { film: Film }                                   # no errors field
type FilmCreateCheckedPayload { film: Film  errors: [FilmCreateError] }

type Mutation {
  createFilmPayload(in: FilmCreateInput!): FilmPayload @mutation(typeName: INSERT)
  createFilmWithErrors(in: FilmCreateInput!): FilmCreateCheckedPayload @mutation(typeName: INSERT, table: "film")
  createFilm(in: FilmCreateInput!): Film @mutation(typeName: INSERT)
}
```

With a 256-character title, `createFilmWithErrors` already answers `{ film: null, errors: [{ path: ["createFilmWithErrors", "in", "title"], message: "size must be between 0 and 255" }] }`. After this item `createFilmPayload` and `createFilm` answer `data: { createFilmPayload: null }` with one top-level error:

```json
{ "message": "size must be between 0 and 255",
  "path": ["createFilmPayload", "in", "title"],
  "extensions": { "classification": "ValidationError", "constraint": "Size" } }
```

and nothing is written, where today both answer with the redacted error and log `22001`.

## Why this does not reopen R1025's gating decision

R1025 considered "always check, and fall back to a top-level error without `VALIDATION`" and rejected it. The reason was specific to fields with a union. A consumer may already catch the overflow there with `{handler: DATABASE, code: "12899"}` or `{handler: DATABASE, sqlState: "22001"}`, and an always-on check would move that error out of the union into a top-level error. A field without an errors field has no union to move anything out of. Today's outcome is already a top-level error, just a redacted one. After this item it is a readable top-level error in the same place, with nothing written in either case. No consumer's existing routing changes.

Fields that have an errors field but no `VALIDATION` member keep R1025's behaviour and its `dml-write-length-unrouted` warning.

## Why the consumer cannot add an errors field instead

From sis. A union member only reaches a client that selects `errors`. sis's production caller of `endreLanetakerIdForStudenter` (`uit_adgangskort`) selects only `studenter`. With an errors field added, an over-long value would come back as `studenter: null` and nothing else. Today that caller at least sees an error, and in Graphitron 9 it saw the validation message.

Repro (sis difftest finding M4), with `STUDENT.BIBSYSLANETAKERID VARCHAR2(20)`:

```graphql
mutation {
  endreLanetakerIdForStudenter(input: [{id: "<an existing student id>", lanetakerId: "123456789012345678901"}]) {
    studenter { id }
  }
}
```

Graphitron 9 answers "size must be between 0 and 20". Graphitron 10 answers with the redacted error and logs ORA-12899. Other affected sis mutations, all experimental: `registrerSoknaderOmEkstraVurderingsforsok`, `registrerVedtakOmEkstraVurderingsforsok`, `behandleEksamenstilpasningssoknader`, `endreUndervisningstilpasninger`, `endreStudieretningForProgramStudieretter`, `endreBetalingsfrister`, `endreStudietilpasningForStudentVedLarested`. sis has accepted the difference for now and listed it in its breaking-changes table, so this does not block their upgrade. None of sis's 90 `@mutation` fields returns a `@table` type directly; all eight return a plain payload (`EndreLanetakerIdForStudenterPayload { studenter }`).

## Scope

In: every DML carrier with no errors field that writes a length-bounded cell (a *written cell* is one value the statement stores, `OperationMember.Write.Dml.writtenCells()`; it is *length-bounded* when its column declares a character length, `ColumnWriteBound.Length`). That is three record types:

- `MutationField.MutationDmlRecordField` and `MutationField.MutationBulkDmlRecordField` with an empty `errorChannel`: the payload-wrap shape (`createFilmPayload: FilmPayload`, `createFilmsPayload: FilmsPayload`), sis's shape.
- `MutationField.DmlTableField`: the direct-return shape (`createFilm: Film`, `createFilms: [Film!]!`, `createKeyedNode: ID`). It has no errors field by definition, and this item makes that a type fact (see Implementation). It has no sis consumer and is in scope because R1025 left it out only for want of a union, which this item no longer needs. DELETE writes no cells and stays unchecked by construction.

Unchanged: the `VALIDATION`-routed fields (R1025), the `Unrouted` fields and their warning, and, for R1025's reasons, numeric precision, byte-length semantics and `@service` writes.

A non-null payload (`endreStudietilpasningForStudentVedLarested` returns `...Payload!`) is not special-cased: a top-level error on a non-null root field nulls `data`, which is also what the redacted error does today. The execution tests pin it.

No build warning is added. R1025 judged that a warning on every field without an errors field would fire on nearly every payload over a `varchar` column; once these fields always check, there is nothing left to warn about.

## Implementation

**Model: `LengthCheck`.** `Checked` gains the destination of its errors as a component: `Checked(List<WrittenCell> bounded, Sink sink)`, with `Sink` a two-constant enum nested in `LengthCheck`:

- `CHANNEL`: the payload has an errors field with a `VALIDATION` member, so the errors become entries in the union. This is today's `Checked`.
- `TOP_LEVEL`: the payload has no errors field, or there is no payload, so the errors are the field's top-level errors.

Where the errors go is a separate axis from which cells are checked. Both sinks run the same cell loop over the same `bounded` list and differ only in the collecting list's type and the return, so it is a component on the one arm that emits a check, not a fourth arm. It is an enum rather than a sealed type because neither destination carries data. `instanceof Checked` keeps its meaning, "a check is emitted". `Unrouted` and `None` are unchanged, apart from `None`'s javadoc dropping "or no errors field to route a violation into".

`LengthCheck.of` filters the bounded cells first and then reads the channel: empty → `Checked(bounded, TOP_LEVEL)`, `VALIDATION` member → `Checked(bounded, CHANNEL)`, otherwise `Unrouted`. Its javadoc's "a carrier with no errors field is `None` rather than `Unrouted`" paragraph is rewritten to say why such a carrier is checked rather than advised: there is no union whose routing a check could change.

**Model: `DmlTableField` has no channel.** The record carries `Optional<ErrorChannel.Mapped> errorChannel` today. Every classifier site passes `Optional.empty()`, `buildDmlFetcher` never wraps in `Outcome` and always uses `noChannelCatchArm`. But two tests build it with a present channel as "the lightest variant with a channel slot": `ErrorMappingsClassGeneratorTest.synthesizeSchema` and `MappingsConstantNameDedupTest.routedField`. Once the record derives a `LengthCheck`, a present channel there would reach `Checked(CHANNEL)` and emit `Outcome.ErrorList` into a `DataFetcherResult<Record>` that does not compile. So the component goes, and the record overrides `errorChannel()` to return `Optional.empty()`, as `MutationRoutineWriteField` already does. Consequences:

- The three `new MutationField.DmlTableField(...)` sites in `FieldBuilder` drop the `Optional.empty()` argument.
- `MappingsConstantNameDedup.withResolvedChannel` drops its `DmlTableField` arm. It is called only for fields whose channel is present.
- The two test helpers build a `MutationDmlRecordField` instead, the lightest variant whose channel is real.

**Model: `DmlWriteField`.** The capability interface gains `Optional<ErrorChannel.Mapped> errorChannel()`: the two payload records' component accessors and `DmlTableField`'s override satisfy it, alongside `WithErrorChannel`'s wildcard return. It also gains `default LengthCheck lengthCheck()`, returning `LengthCheck.of(write(), errorChannel())`. The two identical `lengthCheck()` methods on `MutationDmlRecordField` and `MutationBulkDmlRecordField` are deleted, so `DmlTableField` gets the derivation without a third copy. The `{@link}`s that name the deleted methods are repointed to `DmlWriteField#lengthCheck()`: the bulk record's javadoc, `FieldBuilder`'s `advisedOnLength` javadoc, and `DmlWriteLengthCheckClassificationTest`'s class javadoc. `FieldBuilder.warnLengthUnrouted` still reads `Unrouted` only.

**Wire shape: `ConstraintViolationsClassGenerator`.** The private `validationError` builder adds `.errorType(graphql.ErrorType.ValidationError)`, unconditionally. Without it graphql-java's default classification is `DataFetchingException`, which is wrong for a top-level validation error. Inside a union it is visible only to an `@error` type that declares a field `PropertyDataFetcher` resolves to `getErrorType()`. Graphitron's own type resolution does not read the classification, and no schema in the tree declares such a field. A consumer schema that did would see `ValidationError` where it saw `DataFetchingException`, which is the correct answer for a validation error. So one builder still owns the wire shape (R1025's rule), with no parameter for a choice that has only one correct value. This also reaches `toGraphQLError`, the Jakarta path, which shares the builder by design. The class javadoc's "placed directly into the payload's errors slot" sentence widens to say that a carrier without an errors field returns the same errors as top-level field errors.

**Emit: `TypeFetcherGenerator.lengthCheckPreStep`.** It keeps taking `LengthCheck.Checked` and loops over the cells once. It switches on `sink()` for two things:

- `CHANNEL`: collects into `List<Object>` and returns through `ChannelEarlyReturnEmitter.emit`, as today. `Outcome.ErrorList` takes `List<Object>`.
- `TOP_LEVEL`: collects into `List<GraphQLError>` and returns `DataFetcherResult.<V>newResult().data(null).errors(violations).build()`. That return is a new `FetcherResult.errors(valueType, errorsLocal)` beside `FetcherResult.nullData`.

The fetcher returns the errors on its `DataFetcherResult` rather than throwing. A throw would reach the catch arm, whose no-channel disposition (`ErrorRouter.surfaceClientErrorOrRedact`) surfaces only a `GraphitronClientException`, and that exception carries one message. A bulk input can have several violations, each with its own path, and `sizeExceeded` already builds each error with that path. Returning them also keeps a client mistake out of the redaction log. `ErrorRouterClassGenerator.noChannelRouterCall` calls itself "the one definition of the no-channel error disposition". Its javadoc is scoped to thrown exceptions, since the length check is now a second, non-throwing way a no-channel field answers with errors.

The three call sites:

- `buildSingleRecordTwoStepFetcher` and `buildBulkRecordTwoStepFetcher` (the payload carriers) keep their `instanceof Checked` guard and position: after the tenant DSL and the table local, before the statement.
- `buildDmlFetcher` (the direct-return shape, all four verbs) gains `field.lengthCheck()` and the same guard, after `in` is bound and before `postInGuard`. That puts it after the empty-list short-circuit and the dialect guard, so an unsupported dialect still wins. It also puts it ahead of the SET-map construction, the NodeId decode locals and the bulk-UPDATE uniform-shape guard. That is the same "before the statement, its decode or its transaction" position R1025 chose. The six verb builders do not change.

`valueType` for the `TOP_LEVEL` sink is the fetcher's unwrapped value type, which every site already computes. On the payload carriers that is `RecordN<PK>` or `Result<RecordN<PK>>`. In `buildDmlFetcher` it is `String`, `Record` or a list of either.

## User documentation (first-client check)

`docs/manual/how-to/error-channel.adoc#column-length-check` is rewritten so the check is stated first and the routing second:

> Before a `@mutation` INSERT, UPDATE or UPSERT runs its statement, every string it writes to a column with a declared character length (`varchar(n)`, `char(n)`) is checked against that length. A value that is too long is rejected with the message `size must be between 0 and n`, a `path` pointing at the input field (`["createFilm", "in", "title"]`, or `["createFilms", "in", "1", "title"]` for the second element of a list input), and `extensions.constraint` set to `Size`. Nothing is written: in a list input, one over-long value rejects the whole call. Values are counted in characters. On an Oracle column declared with byte length semantics a value can fit in characters and still exceed the column's bytes, and the database rejects it as before.
>
> Where the error goes depends on the payload:
>
> * *No errors field*, or the mutation returns its `@table` type or an ID directly: the error is a top-level error with classification `ValidationError`, and the field is `null`.
> * *An errors field with a `{handler: VALIDATION}` member*: the error is an entry in the errors list, typed as that member.
> * *An errors field without a `VALIDATION` member*: no check runs and the value reaches the database, because a `DATABASE` handler there may already be catching the overflow. The build warns (`dml-write-length-unrouted`); add `{handler: VALIDATION}` to one `@error` type to have the value checked.

The Graphitron 9 note after it stays. In `docs/manual/reference/directives/mutation.adoc`, the "Give one `@error` type in the errors field `{handler: VALIDATION}`" paragraph under *Payload with an errors field* says that the member routes the length check into the errors list. A sentence goes in the opening DML part of the page that every INSERT, UPDATE and UPSERT checks string lengths before writing, linking the same anchor. The how-to's `VALIDATION runs pre-execution` gotcha bullet stays as it is, since it describes the routed case.

## Tests

Pipeline tier, `DmlWriteLengthCheckClassificationTest`:

- `payloadWithoutErrorsField_isNoneAndSilent` becomes `payloadWithoutErrorsField_isCheckedTopLevelAndSilent`: the same schema, asserting `Checked` with sink `TOP_LEVEL`, the `title` cell, and no `dml-write-length-unrouted` finding.
- `insertWithValidationMember_checksTheBoundedCell` and `bulkCarrier_derivesTheSameCheckAsItsSingleRowTwin` also assert sink `CHANNEL`.
- New: a direct-return INSERT (`createFilm(in: FilmInput!): Film`) and a direct-return UPDATE derive `Checked(TOP_LEVEL)` with their bounded cells; a direct-return DELETE derives `None`.

Unit tier, `ConstraintViolationsWireShapeTest`: `sizeExceeded` and `toGraphQLError` both carry `ErrorType.ValidationError`, asserted side by side as the existing cases assert path and extensions.

The two tests that built a `DmlTableField` with a present channel (`ErrorMappingsClassGeneratorTest`, `MappingsConstantNameDedupTest`) keep their assertions on a `MutationDmlRecordField`. Their channel-bearing behaviour is unchanged, so passing as before is the evidence.

Execution tier, `GraphQLQueryTest` against PostgreSQL, beside R1025's cases. Each sends a 256-character title, asserts that the field is `null`, that exactly one top-level error has the message `size must be between 0 and 255`, the input path, classification `ValidationError` and `extensions.constraint` `Size`, and that nothing was written:

- `createFilmPayload_overLongTitle_isATopLevelValidationError`: payload with no errors field, single INSERT (sis's shape).
- `createFilmsPayload_overLongTitleOnOneRow_rejectsTheWholeBatch`: bulk payload; path `["createFilmsPayload", "in", 1, "title"]`, and the valid first row is not inserted either.
- `updateFilmPayload_overLongTitle_leavesTheRowUnchanged`: payload UPDATE.
- `createFilm_overLongTitle_isATopLevelValidationError` and `updateFilm_overLongTitle_leavesTheRowUnchanged`: direct-return INSERT and UPDATE.
- `createFilmPayloadRequired_overLongTitle_nullsData`: a new fixture field `createFilmPayloadRequired(in: FilmCreateInput!): FilmPayload! @mutation(typeName: INSERT)` in the sakila schema, sis's `...Payload!` shape. Asserts `data` is `null`, the violation is among the top-level errors and nothing was written. It asserts "among" rather than "exactly one" because graphql-java may add its own non-null error beside a null on a `!` field. That is graphql-java's behaviour, and it is the same with today's redacted error.
- `createFilms_overLongTitleOnOneRow_nullsData`: direct-return bulk INSERT. `createFilms` returns `[Film!]!`, so it asserts as the required-payload case does, plus that the valid first row is not inserted.

R1025's code-point case (`createFilmWithErrors_titleOf255SupplementaryCharacters_isAccepted`) gets a direct-return twin, `createFilm_titleOf255SupplementaryCharacters_isAccepted`, since the direct-return fetcher is a new emit site for the same loop.

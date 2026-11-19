---
id: R881
title: "No write carrier reads column nullability, so a clear or a write into a NOT NULL column is only the database's to refuse"
status: Spec
bucket: validation
priority: 4
theme: mutation-write
depends-on: []
created: 2026-08-31
last-updated: 2026-11-19
---

# No write carrier reads column nullability, so a clear or a write into a NOT NULL column is only the database's to refuse

## Goal

An `INSERT` or `UPDATE` `@mutation` that is sent an explicit `null` for a column the database declares `NOT NULL` with no default refuses the request before its statement runs: nothing is written, and the answer is `must not be null` with a `path` to the input field the client sent. That is how Graphitron 9 answered with `<recordValidation>` on, and how the rewrite already answers a string longer than its column. Today the null reaches the database, which refuses it with a not-null violation naming the SQL column rather than the input field, and unless the payload maps that violation the client gets a redacted error.

The pair below is `title` as `UpdateFilmByNodeIdInput` declares it (`multitenant.graphqls`) beside `description` as `FilmUpdateInput` declares it (`schema.graphqls`), on one input:

```graphql
input UpdateFilmByNodeIdInput {
    id:          ID!    @nodeId(typeName: "Film")
    title:       String @field(name: "title")        # film.title varchar(255) NOT NULL
    description: String @field(name: "description")  # film.description text, nullable
}
```

Both fields are optional, which is the only way a GraphQL input lets a patch say "may be left out". They differ only in the column behind them, and only the catalog knows that:

| The client sends | Today | When this lands |
|---|---|---|
| `title` left out | title unchanged | unchanged |
| `description: null` | description cleared | unchanged: cleared |
| `title: null` | the database refuses: `null value in column "title" of relation "film" violates not-null constraint` | refused before the statement: `must not be null` at `[..., "in", "title"]`, row unchanged |

No request that succeeds today is refused, with the two exceptions the column-length check already has (see *The rule*). A build-time rejection was the other way to close this, and it was declined: `String!` in an input means "sent on every call" as well as "never null", so it would cost a patch input its optionality and refuse schemas Graphitron 9 built (see *Other solutions we've considered*).

Terms used below. A *write carrier* is one input field that writes one or more columns. The *matched key* of an `UPDATE` is the set of columns its `WHERE` clause finds the row by; the other columns it writes are its `SET` columns. A payload's *errors channel* is its errors field and the `@error` types in it; a *`VALIDATION` member* is one of those types declaring `{handler: VALIDATION}`.

## What a client sees (first-client docs draft)

This replaces the `#column-length-check` section of `docs/manual/how-to/error-channel.adoc`, which becomes a section on the checks a `@mutation` runs before it writes, keeping that anchor:

> Before an `INSERT` or `UPDATE` `@mutation` runs its statement it checks the values it is about to write, and rejects a request that breaks one without writing anything. Two checks run:
>
> * A string longer than its column's declared length (`varchar(n)`, `char(n)`) is rejected with `size must be between 0 and n` and `extensions.constraint` set to `Size`.
> * An explicit `null` for a column declared `NOT NULL` without a default is rejected with `must not be null` and `extensions.constraint` set to `NotNull`. A field left out is not checked: on an `UPDATE` it leaves the column alone, and on an `INSERT` the column takes its default or, where it has none, the database refuses the row as before. A column with a default is not checked either: an explicit `null` there still reaches the database, which refuses it.
>
> Each error's `path` points at the input field (`["updateFilm", "in", "title"]`, or `["updateFilms", "in", "1", "title"]` for the second element of a list input). Every row of a list input is checked before any statement runs, so one bad value rejects the whole call, and a request breaking several checks gets every error in one response.
>
> Where the errors go depends on the payload: *(the three existing bullets, unchanged)*.
>
> Coming from Graphitron 9: the error type `<recordValidation><schemaErrorType>` named is the one to give `{handler: VALIDATION}`. The two checks answer what jOOQ's `<validationAnnotations>` `@Size` and `@NotNull` answered there, on the same columns and with the same messages.

`docs/manual/reference/directives/mutation.adoc`'s opening paragraph states the rule in one sentence beside the length sentence it already has, which is what the reference check at the Done gate reads.

## The rule

- **Which writes.** `INSERT` and `UPDATE`, single-row and bulk, direct-return and payload-returning. `UPSERT` is refused at build time: the defect rule raises `MUTATION_UPSERT`, severity `error`, at every `@mutation(typeName: UPSERT)`, and no example schema declares one. So no rule here reaches it, and the docs sentence that names `UPSERT` among the length-checked writes is corrected in the same change. `DELETE` writes no values.
- **Which columns.** A column the statement writes a value into: every column an `INSERT` binds, and an `UPDATE`'s `SET` columns only, never its matched key. It is checked when `sql_column.nullable` and `sql_column.defaulted` are both false, which is the rule jOOQ applies to its `@NotNull` annotation (`JavaGenerator.effectivelyNotNull`: not nullable, not defaulted, not identity, not computed; the store's `defaulted` covers default and identity). That is the column set Graphitron 9 refused, so a channel mapping the database's not-null violation for a defaulted column (`FilmInsertValueMissing`, `sqlState: "23502"`, on `rental_duration NOT NULL DEFAULT 3`) keeps receiving it.
- **Which writers.** Any SDL-nullable write carrier: a plain `@field` of any type, an enum, a `@nodeId` over the row's own key, a `@nodeId @reference`, a leaf under a nested grouping input. An SDL non-null field cannot carry a `null`; GraphQL refuses it before graphitron sees it. A carrier whose `UPDATE` null rule is `CarrierNullRule.OnExplicitNull.RefusedAsIdentity` keeps that refusal and is not checked here.
- **When a request is refused.** A checked column is refused when one of its writers is *present in the request and null*, and no other writer of the same column is present with a value. Presence is the predicate the `SET` and `VALUES` emission already uses, so a leaf left out, and on an `UPDATE` a nested group sent absent or `null` (an empty patch), is never refused. The co-writer clause matches what the statement writes: a column shared by a cleared reference and a plain field takes the field's value, and is written `NULL` only when no present writer carries one.
- **What the client gets.** One error per refused writer, built by the private builder `ConstraintViolations` already shares between the Jakarta translation and `sizeExceeded`: message `must not be null`, `extensions.constraint` `NotNull`, classification `ValidationError`, `path` the execution path, the argument, the list index for a list input, and the input path. The message is the literal Hibernate Validator's default bundle gives `@NotNull`, written as a literal the way `sizeExceeded` writes `size must be between 0 and n`, so a client migrating from Graphitron 9 sees the same text it got under the default locale.
- **Where it goes.** Exactly where the length check sends its errors, decided by one predicate: no errors field (or a direct `@table` or `ID` return) gives top-level errors with no value; an errors field with a `VALIDATION` member gives entries in the errors list; an errors field without one runs no check, and the null reaches the database as today.
- **What it refuses that the database would not have.** Two cases, both shared with the length check: an `UPDATE` whose key matches no row, which wrote nothing either way and today answers an empty payload; and a column a `BEFORE` trigger fills, which the catalog cannot see.

## Implementation

The nullability fact is read from the store, not minted on the classified model. `docs/architecture/explanation/pipeline-overview.adoc` § "Classification gathers (transitional)" rules the walk a surface being drained, where new facts land only in the store, and says what a classified tier still needs is added from the stored fact so the addition is deleted when its tier flips. The length check predates that framing: it copies `declared_length` onto the walk as `ColumnWriteBound`, minted in `BuildContext.writeBound` from a live-catalog read in `BuildContext.columnFacts`. This item does not extend that copy, and `ColumnWriteBound`'s domain (direct, single-column, non-enum `String` cells) is narrower than the writers above in any case.

- **The rows.** A query in `no.sikt.graphitron.emit`, read once per build and handed to `TypeFetcherGenerator.generate` the way `OutboxQuery.Rows` is. It reads, per write producer, the table it writes (`graphitron_occurrence_producer_write`) and that table's columns' `nullable` and `defaulted` (`sql_column`). Nullability is a fact about a column, not about the field that reaches it, so the query needs no occurrence-level relation. The emitter looks a column up by the producer and the column's SQL name, case-insensitively, as `BuildContext.columnFacts` matches today; `sql_column.column_name_upper` exists for that. Every column the classified plan writes therefore finds its row, including the columns a `@nodeId @reference`'s key lands in.
- **Capture first.** Before the query, a fact document under `graphitron-model/src/test/resources/facts/scenarios/`: an `INSERT` and an `UPDATE` writing `film`, claiming the producer-to-table rows and the `nullable` and `defaulted` values the query reads for a column of each kind (`title`, `description`, `rental_duration`). Which writers reach which column is not a store question in this item (see *One writer plan*). It becomes one when DML emission reads the store, and one capture gap is known for that day: no relation states which written-table column a `@nodeId @reference`'s key lands in under a write. `graphitron_occurrence_node_id_slot_record_column` has that shape for built records only, so the gap is a capture question at that point (`docs/architecture/explanation/modeling-discipline.adoc` § "A missing fact is a capture question first").
- **The hook.** The pre-step renders at the classified DML site, beside the length pre-step (`TypeFetcherGenerator.lengthCheckPreStep`, called from `buildDmlFetcher` and the two payload-returning fetchers), the way `outboxHook` renders store-side outbox behaviour there. Length and not-null violations go into one list and one early return. The hook goes when DML emission reads the store; the query and its rows outlive it.
- **One writer plan.** The pre-step tests each checked column over the writer plan the statement's `SET` and `VALUES` emission already reads (`setColumnPlan` and `UpdateRows.setColumns()` for `UPDATE`, the `INSERT` writer index for `INSERT`), never a second derivation of who writes a column or which columns are `SET`. A second derivation that drifted would refuse a request the statement accepts, which is the one thing *The rule* promises not to do. The plan is classified-side, so this is the interim the transitional ruling allows, deleted with the hook. The store does not yet state the matched key ("The matched key is stated", an `open` node of R876); when it does, the partition can come from there.
- **One routing decision.** Whether a check runs, and where its errors go, is decided once per write and read by both checks. Today `LengthCheck.of` computes it only when the write has a length-bounded cell, and returns `None` otherwise; an `UPDATE` writing only `language_id` has no sink to read. So the derivation is lifted out of `LengthCheck`: from the errors channel alone it gives top-level, into the channel, or no check. `LengthCheck` and the not-null cells both read it. See open question 5 for where the `VALIDATION`-member predicate under it comes from.
- **Wire shape.** `ConstraintViolations` gains a public `notNull(env, argPath)` beside `sizeExceeded`, through the same private `validationError` builder (`ConstraintViolationsClassGenerator`).
- **Docs.** The draft above into `docs/manual/how-to/error-channel.adoc`; one sentence in `docs/manual/reference/directives/mutation.adoc`'s opening paragraph; the `VALIDATION` row of `docs/manual/reference/directives/error.adoc`, which names the column-length check today, names both.

## Tests

- **Fact document.** The scenario above, with claims on the rows the query reads: the producers' table, and `nullable` and `defaulted` for a nullable column, a `NOT NULL` column with a default, and one without.
- **Execution, in `graphitron-sakila-example`.** Each case asserts the response and the row afterwards:
  - `UPDATE` with `title: null` over `film.title`: a `FilmWriteInvalid`-style entry under a `VALIDATION` member; a top-level `ValidationError` with no errors field; the same on a direct-return field. The row is unchanged in all three.
  - `title` left out leaves the row unchanged with no error; `description: null` still clears (the existing missing-versus-null cases stay green).
  - `INSERT` with an explicit `null` on a nullable field over a `NOT NULL` column without a default. This needs a fixture input with a nullable `title`, since `FilmCreateInput.title` is `String!`.
  - The non-breaking pin: `insertFilmReportingFailure` with `rentalDuration: null` still answers `FilmInsertValueMissing`, because `rental_duration` has a default.
  - A list input with one `null` row: the whole call is refused, the error path carries the index, and no row is written.
  - The co-writer case: a cleared reference sharing a checked column with a valued plain field is accepted and writes the field's value. This needs a fixture, since the shelf fixture's `shelf_code` is nullable.
  - An over-long `title` and a `null` on another checked column in one request: both errors in one response.
  - An errors field without a `VALIDATION` member: no check; the null reaches the database and its handler, as today.
  - The existing `*_overLongTitle*` cases pass unchanged.
- **Wire shape.** `ConstraintViolationsWireShapeTest` gains the `notNull` case, asserting the literal `must not be null` and the `NotNull` constraint name; nothing pins that text today.
- **Routing without a length cell.** An `UPDATE` whose only checked cell is a non-string column (a nullable `Int` over `film.language_id`) still routes its `NotNull` error. This pins that the routing decision no longer depends on a length-bounded cell existing.
- **Not added.** No cases in `DmlWriteLengthCheckClassificationTest` or any other `TestSchemaHelper.buildSchema` test; `docs/architecture/how-to/testing.adoc` retires those with the walk and gives them no new cases. No assertions on generated method bodies.

## Open questions

These are the forks the author could not settle alone. Each carries a recommendation, and the ones marked for a ruling go to Alf and Filip.

1. **Does the length check move onto the same rows in this item?** Recommended: yes. One pre-step reading one source, and R1025's walk-side copies go: `ColumnWriteBound`, `SetColumn.bound()`, `BuildContext.writeBound`, `WrittenCell`, `OperationMember.Write.Dml.writtenCells()` and `LengthCheck`. The behaviour is pinned by the existing `*_overLongTitle*` execution cases, which must pass unchanged. The catch is question 3: the unrouted advisory's producer (`FieldBuilder.advisedOnLength`) reads `LengthCheck`, so the move needs the advisory a home first. If that ruling is not ready, this item ships the not-null rows only, and a successor moves length the day it is.
2. **Read the `UPDATE` partition from the classified plan, or depend on the matched-key node first?** Recommended: the classified plan, as *Implementation* states. It is the interim the transitional ruling allows, and waiting couples this item to a node no session has taken.
3. **The unrouted advisory: a sibling id, a widened `dml-write-length-unrouted`, or none?** Ruling wanted. Retiring the existing id breaks every consumer configuration that disables it, because an unknown rule id fails the build (`docs/manual/reference/mojo-configuration.adoc`, `<lint>`). A sibling id adds a second classifier-side producer at a site that leaves with the walk. Widening keeps configurations working but changes what the id means. Two things are unwritten: whether lint ids are a compatibility surface, and where a suppressible warning lives once DML reads the store. Recommended in the meantime: widen the existing id's meaning and message to cover both checks, since the condition (an errors field without a `VALIDATION` member) and the fix (add one) are the same.
4. **Skip columns with a default, or check every `NOT NULL` column?** Recommended: skip them, as *The rule* states. It is Graphitron 9's column set, and it leaves an author's explicit `23502` mapping for a defaulted column untouched (`FilmInsertValueMissing`). Checking every `NOT NULL` column refuses only requests the database refuses anyway, but reroutes those mappings.
5. **Which predicate sits under the routing decision?** The `VALIDATION`-member test is evaluated twice already: from the store by `ServiceEmission.validating`, and on the classified model by `ErrorChannel.Mapped.hasValidationHandler()`, which `LengthCheck.of` reads. Recommended: the lifted routing decision keeps reading `hasValidationHandler()`, so this item adds no third evaluation. Stating the predicate once, on the errors field's store row, belongs with question 1's move.
6. **A channel with a `VALIDATION` member and a handler for `23502` on a column without a default.** The check pre-empts that handler, as Graphitron 9's `<recordValidation>` did, and as the length check pre-empts a handler for its own violation. Recommended: accept, and say so in the docs draft's routing bullets.

## Other solutions we've considered

- **Leave it to the database, improving only the error text.** This is unchanged for a consumer who never enabled `<recordValidation>` in Graphitron 9. It is a regression for one who did, whose typed `must not be null` would come back as a database error.
- **Reject at build time.** Graphitron 9 never did: `MutationValidator.checkRequiredFields` (tag `v9.3.0`) only warned, and only on `INSERT`/`UPSERT`. A rejection refuses schemas Graphitron 9 built, and its fix, `String!`, forces every caller to send the field, which a patch input cannot afford.
- **Mint the fact on the classified carriers**, beside `ColumnWriteBound`, and group by column on `OperationMember.Write.Dml`. This is the shape the length check took, and the transitional ruling closes it: it extends the surface being drained, copying a stored fact onto a record nothing compares with its source.
- **Check after the `SET` map or `VALUES` row is assembled**, when the written value is known for certain. Each statement shape assembles differently, and the check would run after the node id decode, inside the per-row path. The pre-step on the request is exact anyway: a present non-null value either decodes to non-null column values or throws.
- **Graphitron 9's build-time warnings**, `MISSING_NON_NULLABLE` (an `INSERT` field over a `NOT NULL` column without a default, spelled nullable, where `!` costs nothing because leaving it out fails too) and `MISSING_FIELD` (such a column bound by no field at all). These are a different rule at a different grain: per write, per such column, does the input bind it and how. Their natural home is a stage rule over relations the store already holds; `graphitron_outbox_target_rule` asks the same `NOT nullable AND NOT defaulted` question for its own table. They belong to a separate item, store-side from the start, and not as a classifier advisory.

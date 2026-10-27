---
id: R1113
title: "@outbox: emit a transactional outbox message in the mutation field transaction"
status: Spec
bucket: feature
priority: 5
theme: runtime-connection
depends-on: [mutation-field-transaction-scope]
created: 2026-10-27
last-updated: 2026-10-27
---

# @outbox: emit a transactional outbox message in the mutation field transaction

## Goal

A schema author marks a mutation field with `@outbox(event: "FilmCreated", arguments: "{ in { title languageId } }")`, and every successful call of that field emits one outbox message in the same database transaction as its write: the message exists if and only if the write committed. A *transactional outbox* is the pattern where a service records "this happened" in its own database, in the transaction that made it happen, and a separate relay (a poller, or change data capture such as Debezium) publishes it to a message broker afterwards; it is how a service tells other systems about a change without a two-phase commit and without losing or inventing events. The message holds exactly two things about the call: the arguments the schema selected, and the success outcome, the ids of the rows the write produced. Graphitron runs no query to build it, so emitting adds no SQL, no lock time and no failure mode to the mutation beyond the emit itself. Graphitron decides *what* is emitted and *when*, from the schema; the consumer decides *how*, by registering one `OutboxEmitter` on the runtime, so the same generated code emits on PostgreSQL (a `pg_logical_emit_message`, or an insert into an outbox table) and on Oracle (an insert into an outbox table, or a Transactional Event Queue enqueue). Builds on R1101, which gives every top-level mutation field exactly one transaction on the owned-connection engine and the `MutationScope` that names the field.

Against the sakila example schema:

```graphql
type Mutation {
  createFilm(in: FilmCreateInput!): Film
    @mutation(typeName: INSERT)
    @outbox(event: "FilmCreated", arguments: "{ in { title releaseYear languageId } }")

  updateFilm(in: FilmUpdateInput!): Film
    @mutation(typeName: UPDATE)
    @outbox(event: "FilmChanged", arguments: "{ in { title releaseYear } }")

  deleteFilms(in: [FilmDeleteInput!]!): [ID!]!
    @mutation(typeName: DELETE, table: "film")
    @outbox(event: "FilmsDeleted")
}
```

and the consumer's wiring, here for PostgreSQL logical decoding:

```java
var runtime = Graphitron.runtime(dataSource, SQLDialect.POSTGRES)
    .withOutboxEmitter((tx, message) ->
        tx.dsl().select(DSL.function("pg_logical_emit_message", Object.class,
            DSL.inline(true), DSL.inline("outbox"), DSL.val(toJson(message)))).fetch());
```

`mutation { a: createFilm(in: {title: "A", languageId: 1}) { id } b: createFilm(in: {title: "B", languageId: 999}) { id } }` emits one `FilmCreated` message for field `a`, with `arguments` `{"in": {"title": "A", "languageId": 1}}` (no `releaseYear`: the client did not send one) and the new film's id as its outcome, committed with the film row. Field `b` violates a foreign key, so its write and its message roll back together and nothing about `b` reaches the relay. `updateFilm(in: {id: "...", title: "B2"})` emits `{"in": {"title": "B2"}}`: an omitted input field is absent, an explicit `null` is `null`, so an update's message says what the client changed.

## User documentation (first-client check)

Draft for a new directive reference page, `docs/manual/reference/directives/outbox.adoc`, with a how-to section in `docs/manual/how-to/` for the database recipes:

> **`@outbox`** tells graphitron to emit a message to your transactional outbox every time a mutation field succeeds, inside the transaction that made the change.
>
> ```graphql
> directive @outbox(event: String!, arguments: String) on FIELD_DEFINITION
> ```
>
> `event` is the message's event type, the name your downstream consumers subscribe to. It is required rather than defaulted to the field's name, so renaming a GraphQL field never silently renames a published event.
>
> `arguments` is a selection over the field's arguments, written like a GraphQL selection set and rooted at the argument names: `"{ in { title releaseYear languageId } }"` takes three fields of the `in` argument. It descends into nested input objects, applies to every element of a list, and is checked against the input types when you build. Leave it out and the message carries no arguments. Only what you select is emitted, so personal data in the input reaches your outbox only when you name it.
>
> **What is emitted.** One `OutboxMessage` per successful call of the field, built from what the call already has; graphitron runs no query for it. A message carries:
>
> * `event()`: the directive's `event`.
> * `arguments()`: the selected arguments as a `Map<String, Object>` in the API's own representation (an `ID` as your clients see it, an enum by its GraphQL name), nested maps and lists for input objects and lists. A field the client left out is absent; a field the client set to `null` is present with a `null` value. Serialize it with the JSON library you already use.
> * `table()`: the written table's SQL name, schema-qualified when the catalog is.
> * `keys()`: the outcome, the primary key of every row the write produced, as jOOQ `Record`s of the key columns, in the order the field's result reports them: for `INSERT`, `UPDATE` and `UPSERT` the rows the statement wrote, for `DELETE` the rows it deleted (an input key under `missingAsDeleted` that matched no row is not among them).
> * `nodeIds()`: the same rows' encoded global ids when the type is a `@node`, empty otherwise; these are the ids your API's clients already know.
> * `scope()`: the field's `MutationScope` (coordinate, alias, path, operation name, tenant), the same object the mutation hook receives.
>
> The message describes the call, not the stored row. Values the database filled in (column defaults, trigger-computed columns) are not in it, apart from the generated key in `keys()`. When a consumer needs the row's current state, it reads it by id after the event arrives.
>
> **When.** A field that failed emits nothing: its transaction rolls back, and a failure an `@error` channel turned into a typed payload is a failure. A field that succeeded without writing a row (an `UPDATE` that matched nothing) emits nothing either.
>
> **Emitting.** Register one `OutboxEmitter` on the runtime. Graphitron calls `emit(tx, message)` right after the field's write succeeds and before its payload is resolved; `tx` is the field transaction's jOOQ `Configuration`, and what you write through it commits or rolls back with the field. Without an emitter registered, a runtime whose schema carries `@outbox` refuses to build an engine and names the first `@outbox` field.
>
> PostgreSQL, logical decoding (no table; a CDC relay reads the message from the WAL): see the example above. PostgreSQL or Oracle, an outbox table (Debezium's outbox event router, or a poller):
>
> ```java
> .withOutboxEmitter((tx, message) ->
>     tx.dsl().insertInto(OUTBOX)
>         .set(OUTBOX.EVENT_TYPE, message.event())
>         .set(OUTBOX.AGGREGATE_TYPE, message.table())
>         .set(OUTBOX.PAYLOAD, toJson(message))
>         .execute())
> ```
>
> Oracle Transactional Event Queues: call `DBMS_AQ.ENQUEUE` through `tx` with the default enqueue visibility (`ON_COMMIT`), which is what keeps it transactional.
>
> Keep the emitter to one write. It runs inside the mutation's transaction, so anything slower (a query, a call to another service) holds the mutation's locks for as long as it takes, and anything that fails rolls the mutation back.
>
> If the emitter throws, the field fails and its transaction rolls back, write included; the error is logged and redacted, and no `@error` channel can claim it, since an outbox fault is not business data. In a multi-tenant build each tenant connection the field wrote on gets its own message, holding that connection's keys, so each tenant's outbox holds its own events.
>
> **Escape hatch.** On `Graphitron.newGraphQL()`, which has no runtime, put the emitter in each request's context: `newExecutionInput(dsl, ...).graphQLContext(b -> b.put(OutboxEmitter.class, emitter))`. An `@mutation` field emits inside its own transaction there too.

## Design decisions

Settled with the user:

* **The message holds the selected arguments and the success outcome, nothing read.** A payload built by a query after the write (a GraphQL selection over the written type, resolved in the transaction) was considered and rejected: it makes a read fault fail the write, holds the write's locks for as long as the read runs, grows with the rows written and with any list in the selection (past broker message limits), picks up requester-dependent `@condition(contextArguments:)` filters so the same change emits different events for different callers, can run `@service` or `@externalField` code inside the transaction, and reads nothing at all after a `DELETE`. The arguments are already in memory and bounded by the request; the outcome is the keys the write captured. Leaving the read to the emitter would move the same trap into consumer code, so the docs tell the consumer to keep the emitter to one write.
* **One message per field call, not per row.** The arguments belong to the call, and a list input or a multi-row `DELETE` maps to its rows only through the outcome, so the message carries the call's arguments once and every key in `keys()`. A consumer that publishes one event per aggregate splits the message in its emitter. This removes any pairing of input elements with rows from the generated code.

Open, each with the recommendation the draft is written to:

1. **Who emits: one runtime-registered emitter, or a method reference per directive.** Recommended: one `OutboxEmitter`, an emitted interface registered on `GraphitronRuntime`, as R1101 does for `MutationHook` and as `FetcherErrorLog` already is. *How* to emit is a property of the deployment (its dialect, its outbox table, its relay), not of a field. A per-field `emit: {className:, method:}` reflected at build time cannot take the emitted `OutboxMessage` or `MutationScope` (R1101 phase 1 found this: generation loads the class before the emitted types exist), and every field would repeat the same reference.
2. **Where the directive may sit.** Recommended: Mutation fields only, not repeatable. A type-level `@outbox` hides the emission from the field that causes it; a second event per call is a later, additive change if a consumer needs it.
3. **`@service` mutations.** Recommended: supported on the owned-connection engine, with the outcome taken from the `@table` rows the service returns, and refused on the escape hatch at request time, where the service's writes run in transactions graphitron cannot join. The arguments half works unchanged, which is what makes this worth having: the call's input is known even though graphitron cannot see what the service wrote.
4. **A successful call that wrote no rows.** Recommended: no message, since nothing happened. The alternative emits a message with empty `keys()`, which records the attempt.

## Implementation

Phased, since each phase is observable on its own.

### Phase 1: generated DML mutations

* **Directive.** `@outbox(event: String!, arguments: String) on FIELD_DEFINITION` in `directives.graphqls`. Classification attaches an outbox slot to the `MutationField` variants that write a known `@table` with a primary key (`DmlTableField`, `MutationDmlRecordField`, `MutationBulkDmlRecordField`); every other placement is a validate-time rejection naming the field and the reason (a Query field; a `@service` or routine write until phase 2; a table without a primary key; an empty `event`).
* **The arguments selection.** Parsed with graphql-java's parser as a selection set and resolved at classify time against the field's arguments and their input types: each name must be an argument or an input field of the enclosing input type, a leaf must be a scalar or enum (or a list of them), and an input object must have a sub-selection. The result is a tree in the model; the generated fetcher walks it over the coerced argument values, keeping absent fields absent and explicit nulls null, and turns each leaf back into the API's representation through its type's `Coercing.serialize`. No JSON library enters the generated code: the consumer receives maps and lists.
* **The outcome.** The DML fetchers already capture the written rows' keys through `RETURNING` for the payload's re-fetch (`Returning.primaryKey`); `keys()` is those records. Where a variant's `RETURNING` captures less than the primary key, phase 1 widens it to the key. Confirm per variant at pickup.
* **Emitted types**, in every owned build that has an `@outbox` field: `OutboxEmitter` (functional interface, `void emit(Configuration transaction, OutboxMessage message)`), `OutboxMessage` (final class, record-style accessors, since the JavaPoet fork emits no records), and `OutboxEmitException`, the wrapper for an emitter's throw. They sit beside R1101's `MutationHook` in `<outputPackage>.schema`.
* **Registration.** `GraphitronRuntime.withOutboxEmitter(OutboxEmitter)`; the runtime carries it to each operation as it does the fetcher error log. `newGraphQL` refuses when the schema has an `@outbox` field and no emitter is registered. On the escape hatch the emitter is read from the request's `GraphQLContext` under its class, and a missing one fails the field at request time.
* **The call.** In the generated fetcher, right after the write statement returns and inside the same `transactionResult` (which joins the field's transaction on the owned path, R1101 phase 2, and is the fetcher's own transaction on the escape hatch), the fetcher builds the message and calls the emitter once per connection written, when at least one row was written. The `transactionResult` carries R1101's graphitron mark, so it raises no nested-scope warning. A field whose exception an `@error` channel claims never reaches the call.
* **Failure routing.** A throw is wrapped in `OutboxEmitException`; `ErrorRouter.claim` declines a chain carrying it, beside R1101's `MutationHookException` decline, so the field takes the redacted path and rolls back.

### Phase 2: `@service` and routine writes

`MutationServiceTableField` (owned path only; request-time refusal on the escape hatch) and the routine-write variants whose terminus is a keyed `@table`. Settled by open decision 3.

## Tests

Execution tier, with a test emitter that inserts each message, serialized, plus `txid_current()` into a fixture outbox table (`graphitron-sakila-db`'s `init.sql` gains it):

* `createFilm` twice, the second violating a foreign key: the first film row and its message share a `txid`; the second leaves neither.
* `arguments` holds exactly the selected fields: an unselected input field is absent, an omitted selected field is absent, an explicit `null` is present as `null`, an enum is its GraphQL name, an `ID` is the encoded form the client sent.
* `createFilms` with three inputs: one message, the three inputs under `arguments`, three keys in the order of the field's result.
* `deleteFilms` under `missingAsDeleted` with one absent key: `keys()` holds only the rows deleted.
* An `UPDATE` matching no row calls no emitter.
* `nodeIds()` on a `@node` type equals the ids the payload returns.
* A field whose exception an `@error` channel claims emits nothing.
* The emitter throwing: the field fails redacted, an `@error` channel on the field does not claim it, the film row is gone, the next field commits.
* No emitter registered: `newGraphQL` refuses, naming the field. Escape hatch without the context entry: field error. Escape hatch with it: the message commits with the write.
* `ROLLBACK_ONLY`: emitted and discarded with the field.
* Multi-tenant: one message per tenant connection written, each holding that tenant's keys, `scope().tenant()` set.

Pipeline tier: the `arguments` selection is rejected, naming the offending name, for an unknown argument, an unknown input field, a sub-selection on a scalar and a missing one on an input object; classification accepts the listed variants and rejects the others with their messages; the emitted types appear only in builds with an `@outbox` field.

Oracle: the repository has no Oracle execution tier. The generated side is dialect-neutral (the arguments are in memory, and the keys come from the `RETURNING` capture the DML fetchers already use on every dialect they accept), and the emitter is consumer code; the Oracle recipes in the docs are checked by hand against an Oracle instance at implementation time, and the item says so in its Done evidence.

## Relation to other items

* **R1101** (Ready), one transaction per mutation field: hard dependency. Phase 2 (nested scopes join) is what makes the fetcher's `transactionResult` and an `@service` share one transaction with the emit; phase 3 emits `MutationScope`, which `OutboxMessage.scope()` returns, and the `claim` decline this item extends. R1101's `MutationHook` runs at the transaction's start and knows no rows, so it records *who* and *why*; `@outbox` runs after the write and records *what*. R1101 keeps field arguments away from the hook because they can carry personal data; `@outbox` offers them only as a selection the schema names.

## Other solutions we've considered

* **A payload read after the write.** See Design decisions: failure coupling, lock time, unbounded size, requester-dependent filters, and nothing to read after a `DELETE`. A payload of the written row's own columns compiled into the write's `RETURNING` avoids the extra statement, and could be added later as a separate argument if a consumer needs database-filled values; it is not part of this item.
* **The R1101 mutation hook alone.** It runs before the field's SQL, so it cannot know which rows the field wrote.
* **Change data capture without an outbox** (publishing every row change from the WAL or LogMiner). It publishes the table's shape, not an event a consumer chose to publish, and does not exist the same way on both databases.
* **Graphitron-shipped emitters per dialect.** The outbox table's layout and the relay are the consumer's; shipping one would freeze a layout graphitron does not own. The docs carry recipes instead.

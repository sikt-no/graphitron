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

A schema author marks a mutation field with `@outbox(event: "FilmCreated")`, and every row the field writes produces one outbox message, emitted in the same database transaction as the write: the message exists if and only if the write committed. A *transactional outbox* is the pattern where a service records "this happened" in its own database, in the transaction that made it happen, and a separate relay (a poller, or change data capture such as Debezium) publishes it to a message broker afterwards; it is how a service tells other systems about a change without a two-phase commit and without losing or inventing events. Graphitron decides *what* is emitted and *when*, from the schema; the consumer decides *how*, by registering one `OutboxEmitter` on the runtime, so the same generated code emits on PostgreSQL (a `pg_logical_emit_message`, or an insert into an outbox table) and on Oracle (an insert into an outbox table, or a Transactional Event Queue enqueue). Builds on R1101, which gives every top-level mutation field exactly one transaction on the owned-connection engine and the `MutationScope` that names the field.

Against the sakila example schema:

```graphql
type Mutation {
  createFilm(in: FilmCreateInput!): Film
    @mutation(typeName: INSERT)
    @outbox(event: "FilmCreated")

  deleteFilms(in: [FilmDeleteInput!]!): [ID!]!
    @mutation(typeName: DELETE, table: "film")
    @outbox(event: "FilmDeleted")
}
```

and the consumer's wiring, here for PostgreSQL logical decoding:

```java
var runtime = Graphitron.runtime(dataSource, SQLDialect.POSTGRES)
    .withOutboxEmitter((tx, messages) -> {
        for (var m : messages) {
            tx.dsl().select(DSL.function("pg_logical_emit_message", Object.class,
                DSL.inline(true), DSL.inline("outbox"), DSL.val(toJson(m)))).fetch();
        }
    });
```

`mutation { a: createFilm(in: {title: "A", languageId: 1}) { id } b: createFilm(in: {title: "B", languageId: 999}) { id } }` emits one `FilmCreated` message for field `a`, keyed by the new film's primary key, which commits with the film row; field `b` violates a foreign key, so its write and its message roll back together and nothing about `b` reaches the relay. `deleteFilms` with three ids emits three `FilmDeleted` messages in one `emit` call.

## User documentation (first-client check)

Draft for a new directive reference page, `docs/manual/reference/directives/outbox.adoc`, with a how-to section in `docs/manual/how-to/` for the two database recipes:

> **`@outbox`** tells graphitron to emit a message to your transactional outbox for every row a mutation field writes, inside the transaction that writes it.
>
> ```graphql
> directive @outbox(event: String!) on FIELD_DEFINITION
> ```
>
> `event` is the message's event type, the name your downstream consumers subscribe to. It is required rather than defaulted to the field's name, so renaming a GraphQL field never silently renames a published event.
>
> Allowed on a `Mutation` field that writes rows of one `@table` type graphitron can key: an `@mutation` field of any `typeName`, and (on the owned-connection engine) a `@service` mutation returning a `@table` type. Rejected on a Query field, and on a mutation whose rows graphitron cannot identify by primary key.
>
> **What is emitted.** One `OutboxMessage` per row the field wrote, in the order the rows were written: for `INSERT`, `UPDATE` and `UPSERT` the rows the statement returned, for `DELETE` the rows it deleted (an input key under `missingAsDeleted` that matched no row emits nothing), for a `@service` the rows it returned. A field that wrote no rows emits nothing and calls no emitter. A message carries:
>
> * `event()`: the directive's `event`.
> * `table()`: the written table's SQL name, schema-qualified when the catalog is.
> * `key()`: the row's primary key, a jOOQ `Record` of the key columns, so `key().formatJSON()` and typed access both work.
> * `nodeId()`: the row's encoded global id when its type is a `@node`, empty otherwise; this is the id your API's clients already know.
> * `scope()`: the field's `MutationScope` (coordinate, alias, path, operation name, tenant), the same object the mutation hook receives.
>
> The message carries no column values beyond the key. When your consumers want the row's state, read it in your emitter: `tx` sees the field's own writes, so the read is consistent with what commits.
>
> **Emitting.** Register one `OutboxEmitter` on the runtime. Graphitron calls `emit(tx, messages)` once per field and connection, with every message for that field's rows on that connection, right after the write and before the field's payload is resolved. `tx` is the field transaction's jOOQ `Configuration`; what you write through it commits or rolls back with the field. Without an emitter registered, a runtime whose schema carries `@outbox` refuses to build an engine and names the first `@outbox` field.
>
> PostgreSQL, logical decoding (no table; a CDC relay reads the message from the WAL): see the example above. PostgreSQL or Oracle, an outbox table (Debezium's outbox event router, or a poller):
>
> ```java
> .withOutboxEmitter((tx, messages) -> {
>     var insert = tx.dsl().insertInto(OUTBOX,
>         OUTBOX.EVENT_TYPE, OUTBOX.AGGREGATE_TYPE, OUTBOX.AGGREGATE_ID, OUTBOX.PAYLOAD);
>     for (var m : messages) {
>         insert = insert.values(m.event(), m.table(), m.key().formatJSON(), toJson(m));
>     }
>     insert.execute();
> })
> ```
>
> Oracle Transactional Event Queues: call `DBMS_AQ.ENQUEUE` through `tx` with the default enqueue visibility (`ON_COMMIT`), which is what keeps it transactional.
>
> If the emitter throws, the field fails and its transaction rolls back, write included; the error is logged and redacted, and no `@error` channel can claim it, since an outbox fault is not business data. In a multi-tenant build each tenant connection the field wrote on gets its own `emit` call with that connection's rows, so each tenant's outbox holds its own events.
>
> **Escape hatch.** On `Graphitron.newGraphQL()`, which has no runtime, put the emitter in each request's context: `newExecutionInput(dsl, ...).graphQLContext(b -> b.put(OutboxEmitter.class, emitter))`. An `@mutation` field emits inside its own transaction there too. A `@service` field with `@outbox` is refused with a field error on the escape hatch: its writes run in whatever transactions your service opened, which graphitron cannot join, so an atomic emit cannot be promised.

## Design forks

Each fork names the recommendation the draft above is written to; the user's call settles it before Ready.

1. **Who emits: one runtime-registered emitter, or a method reference per directive.** Recommended: one `OutboxEmitter`, an emitted interface registered on `GraphitronRuntime`, as R1101 does for `MutationHook` and as `FetcherErrorLog` already is. *How* to emit is a property of the deployment (its dialect, its outbox table, its relay), not of a field, and it is the same for every event. A per-field `@outbox(event:, emit: {className:, method:})` reflected at build time cannot take the emitted `OutboxMessage` or `MutationScope` (R1101 phase 1 found this: generation loads the class before the emitted types exist), so its signature would have to be spelled in JDK and jOOQ types only, and every field would repeat the same reference. The runtime registration also lets one build run against both databases.
2. **What a message carries: the key, or a declared payload.** Recommended: key, node id, table, event and scope only. The emitter has the transaction and can read whatever it wants to publish, consistently with the write, so a "fat" event is consumer code, and personal data in an event is something the consumer chose to read. The alternative, a `payload:` selection on the directive (`@outbox(event: "FilmCreated", payload: "{ title language { name } }")`, resolved in the transaction by the generated read path and serialized to JSON), puts the event's contract in the schema, where it is versioned and checked; it is a substantial addition (a JSON serializer for a selection, outside graphql-java's execution) and can come as a follow-up item without breaking the first version, since it only adds `payload()`.
3. **One message per row or one per field.** Recommended: per row, delivered together per field and connection. An outbox event is conventionally about one aggregate, keyed by its id (Debezium routes on it); the batch lets the emitter write one multi-row insert.
4. **Where the directive may sit.** Recommended: Mutation fields only, not repeatable. A type-level `@outbox` on a `@table` type (emit for every mutation that writes it) hides the emission from the field that causes it, and a repeatable directive (two events per row) is a later, additive change if a consumer needs it.
5. **`@service` mutations.** Recommended: supported on the owned-connection engine only, keyed by the `@table` rows the service returns, and refused on the escape hatch at request time. Graphitron cannot see which rows a service wrote; the rows it returns are the contract a reader of the schema sees. An alternative is to reject `@outbox` on `@service` fields altogether and let such services emit themselves inside the field transaction (they already have it, after R1101).

## Implementation

Phased, since each phase is observable on its own.

### Phase 1: generated DML mutations

* **Directive.** `@outbox(event: String!) on FIELD_DEFINITION` in `directives.graphqls`. Classification attaches an outbox slot to the `MutationField` variants that write a known `@table` with a primary key (`DmlTableField`, `MutationDmlRecordField`, `MutationBulkDmlRecordField`); every other placement is a validate-time rejection naming the field and the reason (a Query field; a `@service` or routine write until phase 2; a table without a primary key; an empty `event`).
* **Key capture.** The DML fetchers already capture the written rows' keys through `RETURNING` for the payload's re-fetch (`Returning.primaryKey`); the outbox reuses those keys rather than adding a statement. Where a variant's `RETURNING` captures less than the primary key, phase 1 widens it to the key. Confirm per variant at pickup.
* **Emitted types**, in every owned build that has an `@outbox` field: `OutboxEmitter` (functional interface, `void emit(Configuration transaction, List<OutboxMessage> messages)`), `OutboxMessage` (final class, record-style accessors, since the JavaPoet fork emits no records), and `OutboxEmitException`, the wrapper for an emitter's throw. They sit beside R1101's `MutationHook` in `<outputPackage>.schema`.
* **Registration.** `GraphitronRuntime.withOutboxEmitter(OutboxEmitter)`; the runtime carries it to each operation as it does the fetcher error log. `newGraphQL` refuses when the schema has an `@outbox` field and no emitter is registered. On the escape hatch the emitter is read from the request's `GraphQLContext` under its class, and a missing one fails the field at request time.
* **The call.** In the generated fetcher, right after the write statement returns and inside the same `transactionResult` (which joins the field's transaction on the owned path, R1101 phase 2, and is the fetcher's own transaction on the escape hatch), the fetcher builds the messages from the captured keys and calls the emitter once, when there is at least one row. The `transactionResult` carries R1101's graphitron mark, so it raises no nested-scope warning.
* **Failure routing.** A throw is wrapped in `OutboxEmitException`; `ErrorRouter.claim` declines a chain carrying it, beside R1101's `MutationHookException` decline, so the field takes the redacted path and rolls back.

### Phase 2: `@service` and routine writes

`MutationServiceTableField` (owned path only; request-time refusal on the escape hatch, through the existing `DialectRequirement`-style guard rendering) and the routine-write variants whose terminus is a keyed `@table`. Settled by fork 5.

## Tests

Execution tier, with a test emitter that inserts each message plus `txid_current()` into a fixture outbox table (`graphitron-sakila-db`'s `init.sql` gains it):

* `createFilm` twice, the second violating a foreign key: the first film row and its message share a `txid`; the second leaves neither.
* `createFilms` with three inputs: one `emit` call, three messages, in input order, keys matching the returned rows.
* `deleteFilms` under `missingAsDeleted` with one absent key: messages only for the rows deleted.
* An `UPDATE` matching no row calls no emitter.
* `nodeId()` on a `@node` type equals the id the payload returns.
* The emitter throwing: the field fails redacted, an `@error` channel on the field does not claim it, the film row is gone, the next field commits.
* No emitter registered: `newGraphQL` refuses, naming the field. Escape hatch without the context entry: field error.
* Escape hatch with the emitter in context: atomic with the write.
* `ROLLBACK_ONLY`: emitted and discarded with the field.
* Multi-tenant: one `emit` per tenant connection written, each holding that tenant's rows, `scope().tenant()` set.

Pipeline tier: classification accepts the listed variants and rejects the others with their messages; the emitted types appear only in builds with an `@outbox` field.

Oracle: the repository has no Oracle execution tier. The generated side is dialect-neutral (the keys come from the `RETURNING` capture the DML fetchers already use on every dialect they accept), and the emitter is consumer code; the Oracle recipe in the docs is checked by hand against an Oracle instance at implementation time, and the item says so in its Done evidence.

## Relation to other items

* **R1101** (Ready), one transaction per mutation field: hard dependency. Phase 2 (nested scopes join) is what makes the fetcher's `transactionResult` and an `@service` share one transaction with the emit; phase 3 emits `MutationScope`, which `OutboxMessage.scope()` returns, and the `claim` decline this item extends. R1101's `MutationHook` runs at the transaction's start and knows no rows, so it records *who* and *why*; `@outbox` runs after the write and records *what*.

## Other solutions we've considered

* **The R1101 mutation hook alone.** It runs before the field's SQL, so it cannot know which rows the field wrote; a consumer would have to re-derive them from arguments.
* **Change data capture without an outbox** (publishing every row change from the WAL or LogMiner). It publishes the table's shape, not an event a consumer chose to publish, and does not exist the same way on both databases.
* **Graphitron-shipped emitters per dialect.** The outbox table's layout and the relay are the consumer's; shipping one would freeze a layout graphitron does not own. The docs carry recipes instead.

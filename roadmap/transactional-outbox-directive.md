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

A schema author marks a mutation field with `@outbox(event: "FilmCreated", arguments: "{ in { title languageId } }")`, configures once where the deployment's outbox lives, and every successful call of that field writes one outbox message in the same database transaction as its write: the message exists if and only if the write committed. A *transactional outbox* is the pattern where a service records "this happened" in its own database, in the transaction that made it happen, and a separate relay (a poller, or change data capture such as Debezium) publishes it to a message broker afterwards; it is how a service tells other systems about a change without a two-phase commit and without losing or inventing events. Graphitron generates the emit itself, as one jOOQ statement right after the write that builds the JSON message in SQL, against one of two targets the deployment configures, both on PostgreSQL and Oracle: an *outbox table* graphitron inserts into, or an *outbox function*, a database function or procedure graphitron calls with the message, which does whatever the deployment's relay needs (a `pg_logical_emit_message` on PostgreSQL, a `DBMS_AQ.ENQUEUE` on Oracle). Both are checked against the jOOQ catalog when you build, and no Java is written for either. The message holds the arguments the schema selected and the success outcome, the ids of the rows the write produced; graphitron reads nothing to build it, so emitting adds one statement and no read to the mutation. Builds on R1101, which gives every top-level mutation field exactly one transaction on the owned-connection engine and the `MutationScope` that names the field and its operation.

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

with, in the plugin configuration,

```xml
<outbox>
  <table>outbox</table>
</outbox>
```

the request

```graphql
mutation AddFilms {
  a: createFilm(in: {title: "A", languageId: 1}) { id }
  b: createFilm(in: {title: "B", languageId: 999}) { id }   # foreign key violation
}
```

inserts one `outbox` row for field `a`, committed with the film row, whose payload is

```json
{"v": 1, "event": "FilmCreated", "coordinate": "Mutation.createFilm", "operationName": "AddFilms",
 "table": "film", "keys": [{"film_id": 1001}], "nodeIds": ["RmlsbToxMDAx"],
 "arguments": {"in": {"title": "A", "languageId": 1}}}
```

(no `releaseYear`: the client did not send one). Field `b` violates a foreign key, so its write and its message roll back together and nothing about `b` reaches the relay. `updateFilm(in: {id: "...", title: "B2"})` emits `"arguments": {"in": {"title": "B2"}}`: an omitted input field is absent and an explicit `null` is `null`, so an update's message says what the client changed.

## User documentation (first-client check)

Draft for a new directive reference page, `docs/manual/reference/directives/outbox.adoc`, a `<outbox>` row and section in `docs/manual/reference/mojo-configuration.adoc`, and a how-to in `docs/manual/how-to/` for relaying the messages:

> **`@outbox`** tells graphitron to write a message to your transactional outbox every time a mutation field succeeds, inside the transaction that made the change.
>
> ```graphql
> directive @outbox(event: String!, arguments: String) on FIELD_DEFINITION
> ```
>
> `event` is the message's event type, the name your downstream consumers subscribe to. It is required rather than defaulted to the field's name, so renaming a GraphQL field never silently renames a published event.
>
> `arguments` is a selection over the field's arguments, written like a GraphQL selection set and rooted at the argument names: `"{ in { title releaseYear languageId } }"` takes three fields of the `in` argument. It descends into nested input objects, applies to every element of a list, and is checked against the input types when you build. Leave it out and the message carries no arguments. Only what you select is emitted, so personal data in the input reaches your outbox only when you name it.
>
> **Where messages go.** Configure the target once, in the plugin configuration. A schema with an `@outbox` field and no `<outbox>` block fails the build, naming the field.
>
> * **An outbox table**, on PostgreSQL and Oracle: `<outbox><table>outbox</table></outbox>`. Graphitron resolves the table in your jOOQ catalog and inserts one row per message. By default it expects Debezium's outbox layout and fills `type` (the event), `aggregatetype` (the written table), `aggregateid` and `payload` (the message below); name other columns with `<columns><type>event_type</type><payload>body</payload>...</columns>`. Leave `id` and any timestamp to column defaults. The build fails when the table or a configured column is missing, when `payload` is not a JSON, JSONB, CLOB or text column, or when another `NOT NULL` column has no default.
> * **An outbox function**, on PostgreSQL and Oracle: `<outbox><function>outbox_emit</function></outbox>`. Graphitron resolves the function or procedure in your jOOQ catalog and calls it once per message, in the field's transaction, binding the same roles as the table's columns to its parameters: `payload` is required, `type`, `aggregatetype` and `aggregateid` are passed when the routine has parameters of those names, and `<parameters>` maps other names. A return value is ignored. The build fails when the routine is missing or overloaded, when it has a parameter that is neither mapped nor defaulted, or when `payload`'s type is not JSON, JSONB, CLOB or text. What the routine does is yours, and it runs inside the mutation's transaction, so keep it to the emit. On PostgreSQL, to publish through logical decoding without a table:
>
> ```sql
> create function outbox_emit(type text, aggregateid text, payload jsonb) returns void
> language plpgsql as $$
> begin
>   perform pg_logical_emit_message(true, 'outbox', payload::text);
> end $$;
> ```
>
> A change data capture relay then reads the message from the write-ahead log once the transaction commits; the database must be set up for logical decoding. On Oracle, a procedure that calls `DBMS_AQ.ENQUEUE` with the default `ON_COMMIT` visibility publishes to a Transactional Event Queue, transactionally.
>
> **The message.** One message per successful call of the field, a JSON object graphitron builds in the same statement that writes it:
>
> [cols="1,3"]
> |===
> | `v` | The format version, `1`. A change to the format raises it.
> | `event` | The directive's `event`.
> | `coordinate`, `operationName` | The field (`Mutation.createFilm`) and the operation's name, absent for an anonymous operation.
> | `tenant` | In a `<tenantColumn>` build, the tenant whose connection the write ran on.
> | `table` | The written table's SQL name.
> | `keys` | The outcome: the primary key of every row the write produced, as objects of key column to value, in the order the field's result reports them. For `INSERT`, `UPDATE` and `UPSERT` the rows the statement wrote; for `DELETE` the rows it deleted (an input key under `missingAsDeleted` that matched no row is not among them).
> | `nodeIds` | The same rows' global ids when the type is a `@node`, as your API's clients see them; absent otherwise.
> | `arguments` | The selected arguments, in the API's own representation: an `ID` as your clients send it, an enum by its GraphQL name. A field the client left out is absent; a field set to `null` is `null`.
> |===
>
> The message describes the call, not the stored row. Values the database filled in (column defaults, trigger-computed columns) are not in it, apart from generated keys in `keys`. A consumer that needs the row's current state reads it by id after the event arrives.
>
> **When.** Right after the field's write succeeds and before its payload is resolved, as one more statement in the field's transaction. A field that failed emits nothing: its transaction rolls back, and a failure an `@error` channel turned into a typed payload is a failure. A field that succeeded without writing a row (an `UPDATE` that matched nothing) emits nothing. In a multi-tenant build each tenant connection the field wrote on gets its own message, holding that connection's keys. If the emit fails, the field fails and rolls back, write included; the error is logged and redacted, and no `@error` channel can claim it, since an outbox fault is not business data.

## Design decisions

Settled with the user:

* **The message holds the selected arguments and the success outcome, nothing read.** A payload built by a query after the write (a GraphQL selection over the written type, resolved in the transaction) was considered and rejected: it makes a read fault fail the write, holds the write's locks for as long as the read runs, grows with the rows written and with any list in the selection (past broker message limits), picks up requester-dependent `@condition(contextArguments:)` filters so the same change emits different events for different callers, can run `@service` or `@externalField` code inside the transaction, and reads nothing at all after a `DELETE`. The arguments are in memory and bounded by the request; the outcome is the keys the write captured.
* **Graphitron generates the emit; there is no consumer emitter.** The target is a property of the deployment and is known at build time, so it is plugin configuration, and the emit is a jOOQ statement the generator renders like any other: the table target is checked against the catalog when you build, and the consumer writes and tests no code. The cost is that the message format becomes graphitron's public contract, versioned by `v`. A Java emitter the consumer registers on the runtime would put the dialect knowledge in consumer code that nothing checks, and need a JSON library on the consumer side.
* **Two targets: an outbox table and an outbox function.** The table covers the common relay (Debezium's outbox event router, or a poller) with nothing to write. The function is the extension point: the deployment's own emit lives in the database, where its dialect belongs, and graphitron calls it with the same message on either database. It is how a PostgreSQL logical message (five lines of SQL, in the docs) and an Oracle Transactional Event Queue are reached, so graphitron ships no mechanism-specific target.
* **The JSON is built in SQL from values graphitron has already converted.** jOOQ's JSON constructors (`DSL.jsonObject`, `DSL.jsonbObject`) render `jsonb_build_object` on PostgreSQL and `JSON_OBJECT` on Oracle, so the generated code needs no JSON library and no escaping of its own. The bound leaf values are the API's representation, each argument leaf passed through its type's `Coercing.serialize` and each `nodeIds` entry encoded as the API encodes it, so the event speaks the API's vocabulary, not the columns'. Only the fields the client sent are rendered, so the statement's shape varies with the request.
* **One message per field call, not per row.** The arguments belong to the call, and a list input or a multi-row `DELETE` maps to its rows only through the outcome, so the message carries the call's arguments once and every key in `keys`. This keeps any pairing of input elements with rows out of the generated code.

Open, each with the recommendation the draft is written to:

1. **`aggregateid` on a call that wrote several rows.** Debezium uses the column as the Kafka message key, which decides partitioning and so per-aggregate ordering. Recommended: the row's node id (or its key as JSON when the type is not a `@node`) when the call wrote one row, and `NULL` when it wrote several, documented as losing per-aggregate ordering for bulk calls. The alternative writes one outbox row per written row, each repeating the call's whole `arguments`, which restores per-aggregate keys at the cost of a payload that grows with the square of a bulk input.
2. **Where the directive may sit.** Recommended: Mutation fields only, not repeatable. A type-level `@outbox` hides the emission from the field that causes it; a second event per call is a later, additive change if a consumer needs it.
3. **`@service` mutations.** Recommended: supported on the owned-connection engine, with the outcome taken from the `@table` rows the service returns, and refused on the escape hatch at request time, where the service's writes run in transactions graphitron cannot join. The arguments half works unchanged, which is what makes this worth having: the call's input is known even though graphitron cannot see what the service wrote.
4. **A successful call that wrote no rows.** Recommended: no message, since nothing happened. The alternative emits a message with empty `keys`, which records the attempt.

## Implementation

Phased, since each phase is observable on its own.

### Phase 1: the table target, for generated DML mutations

* **Directive.** `@outbox(event: String!, arguments: String) on FIELD_DEFINITION` in `directives.graphqls`. Classification attaches an outbox slot to the `MutationField` variants that write a known `@table` with a primary key (`DmlTableField`, `MutationDmlRecordField`, `MutationBulkDmlRecordField`); every other placement is a validate-time rejection naming the field and the reason (a Query field; a `@service` or routine write until phase 3; a table without a primary key; an empty `event`).
* **The arguments selection.** Parsed with graphql-java's parser as a selection set and resolved at classify time against the field's arguments and their input types: each name must be an argument or an input field of the enclosing input type, a leaf must be a scalar or enum (or a list of them), and an input object must have a sub-selection. The result is a tree in the model, rendered into the emit statement as nested JSON constructors guarded by presence, so an absent field contributes no key and an explicit null contributes a JSON `null`.
* **Configuration.** An `<outbox>` mojo parameter with exactly one of `<table>` or `<function>` (the function target lands in phase 2); `<columns>` maps the roles `type`, `aggregatetype`, `aggregateid` and `payload` to columns, defaulting to the Debezium names. The table and its columns are resolved against the jOOQ catalog when the model is built, and every rejection in the draft is a build failure naming the table and column. `payload` takes a JSON or JSONB column directly; a CLOB or text column takes the JSON rendered as text, which on Oracle needs `JSON_OBJECT(... RETURNING CLOB)` to lift the 4000-byte `VARCHAR2` default (confirm jOOQ 3.20 renders the `RETURNING` form at pickup).
* **The outcome.** The DML fetchers already capture the written rows' keys through `RETURNING` for the payload's re-fetch (`Returning.primaryKey`); `keys` and `nodeIds` are built from those records. Where a variant's `RETURNING` captures less than the primary key, phase 1 widens it to the key. Confirm per variant at pickup.
* **The call.** In the generated fetcher, right after the write statement returns and inside the same `transactionResult` (which joins the field's transaction on the owned path, R1101 phase 2, and is the fetcher's own transaction on the escape hatch), the fetcher runs the emit statement once per connection written, when at least one row was written. The scope values (`coordinate`, `operationName`, `tenant`) come from R1101's `MutationScope` on the owned path; on the escape hatch, which has no field scope, from the field's `DataFetchingEnvironment`. The `transactionResult` carries R1101's graphitron mark, so it raises no nested-scope warning. A field whose exception an `@error` channel claims never reaches the emit.
* **Failure routing.** The emit's exception is wrapped in an emitted `OutboxEmitException`; `ErrorRouter.claim` declines a chain carrying it, beside R1101's `MutationHookException` decline, so the field takes the redacted path and rolls back.

### Phase 2: the function target

`<function>` resolves through `JooqCatalog`'s routine lookup (the one `@routine` uses for routines that are not table-valued) to a jOOQ-generated `Routine`, functions and procedures alike; an overloaded name is a build failure, since the parameters cannot pick one. The roles bind by parameter name, defaulting to `type`, `aggregatetype`, `aggregateid` and `payload`, with `<parameters>` mapping other names, and the payload parameter's type decides the JSON rendering as for the table's column. The emit is the routine's call through the field's `Configuration` (`Routine.execute`), which jOOQ renders as a `select` of a function or a `call` of a procedure per dialect; any return value is discarded.

### Phase 3: `@service` and routine writes

`MutationServiceTableField` (owned path only; request-time refusal on the escape hatch) and the routine-write variants whose terminus is a keyed `@table`. Settled by open decision 3.

## Tests

Execution tier, against an `outbox` table in Debezium's layout that `graphitron-sakila-db`'s `init.sql` gains, with `id` and a timestamp defaulted:

* `createFilm` twice, the second violating a foreign key: the first film row and its outbox row commit together (same `xmin`); the second leaves neither.
* The payload: `v`, `event`, `coordinate`, `operationName` for a named operation and absent for an anonymous one, `table`, `keys`, `nodeIds` equal to the ids the field returns.
* `arguments` holds exactly the selected fields: an unselected input field is absent, an omitted selected field is absent, an explicit `null` is present as `null`, an enum is its GraphQL name, an `ID` is the encoded form the client sent, a string with quotes and non-ASCII characters round-trips.
* `createFilms` with three inputs: one outbox row, the three inputs under `arguments`, three keys in the order of the field's result, `aggregateid` per open decision 1.
* `deleteFilms` under `missingAsDeleted` with one absent key: `keys` holds only the rows deleted.
* An `UPDATE` matching no row writes no outbox row.
* A field whose exception an `@error` channel claims writes no outbox row.
* The emit failing (the fixture revokes `INSERT` on the outbox table for one test, or a check constraint rejects the row): the field fails redacted, an `@error` channel on the field does not claim it, the film row is gone, the next field commits.
* Escape hatch: the outbox row commits with the write.
* `ROLLBACK_ONLY`: emitted and discarded with the field.
* Multi-tenant: one outbox row per tenant connection written, in that tenant's database, each holding that tenant's keys and `tenant`.
* Phase 2, against a fixture `outbox_emit` function that writes its arguments to a second table: the committed field's call is visible with the same payload the table target writes, a rolled-back field leaves none, a role parameter the routine lacks is not passed, a `<parameters>` mapping binds a differently named one, and a routine that raises fails the field redacted with the write gone. A procedure (not a function) is called the same way. The logical-message recipe from the docs is run with a replication slot (`pg_logical_slot_get_changes` with `test_decoding`) where the test database has `wal_level = logical`; check the local-db and CI databases at pickup, and keep it out of the suite if they do not.

Pipeline tier: the `arguments` selection is rejected, naming the offending name, for an unknown argument, an unknown input field, a sub-selection on a scalar and a missing one on an input object; classification accepts the listed variants and rejects the others with their messages; the configuration is rejected for an `@outbox` field without `<outbox>`, both targets set, a missing table or column, an unsupported `payload` type and an unmapped `NOT NULL` column without a default; for the function target, a missing or overloaded routine, an unmapped parameter without a default and an unsupported `payload` type.

Oracle: the repository has no Oracle execution tier. Both targets' statements, the insert with `JSON_OBJECT` and the routine call, are rendered for `ORACLE` in a pipeline-tier rendering check (the CLOB `RETURNING` form included); the item records in its Done evidence that it was also run by hand against an Oracle instance.

## Relation to other items

* **R1101** (Ready), one transaction per mutation field: hard dependency. Phase 2 (nested scopes join) is what makes the fetcher's `transactionResult` and an `@service` share one transaction with the emit; phase 3 emits `MutationScope`, which supplies the message's scope values, and the `claim` decline this item extends. R1101's `MutationHook` runs at the transaction's start and knows no rows, so it records *who* and *why*; `@outbox` runs after the write and records *what*. R1101 keeps field arguments away from the hook because they can carry personal data; `@outbox` offers them only as a selection the schema names.

## Other solutions we've considered

* **A payload read after the write.** See Design decisions. A payload of the written row's own columns, compiled into the write's `RETURNING`, avoids the extra read and could be added later as a separate argument if a consumer needs database-filled values; it is not part of this item.
* **A consumer-registered emitter** (`withOutboxEmitter`). See Design decisions: unchecked dialect code in every consumer, and a JSON library on their side.
* **The emit fused into the write**, as a PostgreSQL data-modifying CTE (`WITH w AS (INSERT ... RETURNING ...) SELECT ..., pg_logical_emit_message(...) FROM w`). Saves one round trip, but is PostgreSQL-only, must reference the emit from the outer `SELECT` or PostgreSQL skips it, and complicates the DML emitters. A possible later optimization; the separate statement is cheap and works on both databases.
* **Built-in targets per mechanism** (a PostgreSQL logical message, an Oracle Transactional Event Queue). Each needs mechanism-specific configuration (a prefix; a queue name and payload object type), and the first exists on one database only. The function target reaches both with a few lines of the deployment's own SQL.
* **The R1101 mutation hook alone.** It runs before the field's SQL, so it cannot know which rows the field wrote.
* **Change data capture without an outbox** (publishing every row change from the WAL or LogMiner). It publishes the table's shape, not an event the schema chose to publish, and does not exist the same way on both databases.

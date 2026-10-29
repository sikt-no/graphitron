---
id: R1113
title: "@outbox: emit a transactional outbox message in the mutation field transaction"
status: Spec
bucket: feature
priority: 5
theme: runtime-connection
depends-on: []
created: 2026-10-27
last-updated: 2026-10-28
---

# @outbox: emit a transactional outbox message in the mutation field transaction

## Goal

A schema author marks a mutation field with `@outbox`, and every successful call of that field writes one outbox message per `@outbox` in the same database transaction as its write: the messages exist if and only if the write committed. A *transactional outbox* is the pattern where a service records "this happened" in its own database, in the transaction that made it happen, and a separate relay (a poller, or change data capture such as Debezium) publishes it to a message broker afterwards; it is how a service tells other systems about a change without a two-phase commit and without losing or inventing events. The author builds the message's JSON payload with `argMapping`, the binding syntax graphitron already uses on `@service` and `@routine`, from the field's arguments, its success outcome (the result the write produced), the session and the request context; a common payload, configured once, is added to every message. `@outbox` is repeatable and every occurrence is a message of its own, so a new event (or a new version of one) is added beside the published ones without changing them. Graphitron generates the emit as jOOQ right after the write, building the JSON in SQL, against one of two targets the deployment configures, both on PostgreSQL and Oracle: an *outbox table* graphitron inserts into, or an *outbox function*, a database function or procedure graphitron calls with the message (a `pg_logical_emit_message` on PostgreSQL, a `DBMS_AQ.ENQUEUE` on Oracle). A message's `$result` values are read by a subselect inside its own `INSERT ... SELECT`, from the rows the write just wrote, so emitting adds one statement per message to the mutation (one call per message for the function target) and no separate query; the write's own statement is unchanged. Builds on R1101 phase 1 (shipped), which gives every top-level mutation field exactly one transaction on the owned-connection engine; phases 1 and 2 below need nothing more of R1101, and phase 3 needs R1101's join rule (see Relation to other items).

Against the sakila example schema:

```graphql
type Mutation {
  createFilm(in: FilmCreateInput!): Film
    @mutation(typeName: INSERT)
    @outbox(event: "FilmCreated",
            argMapping: "film.id: $result.id, film.title: in.title, film.languageId: in.languageId")
    @outbox(event: "FilmCatalogued",                              # added in a later release
            argMapping: "filmId: $result.id, releaseYear: in.releaseYear")

  deleteFilms(in: [FilmDeleteInput!]!): [ID!]!
    @mutation(typeName: DELETE, table: "film")
    @outbox(argMapping: "films: $result")                        # event defaults to Mutation.deleteFilms
}
```

with, in the plugin configuration (the session handle here has `userId` and `requestId` members, which the consumer's mount callback fills),

```xml
<outbox>
  <table>outbox</table>
  <payload>actor: $session.userId, requestId: $session.requestId, operation: $scope.operationName</payload>
</outbox>
```

the request

```graphql
mutation AddFilms {
  a: createFilm(in: {title: "A", languageId: 1}) { id }
  b: createFilm(in: {title: "B", languageId: 999}) { id }   # foreign key violation
}
```

inserts two `outbox` rows for field `a`, committed with the film row: a `FilmCreated` whose payload is

```json
{"actor": "u-42", "requestId": "r-9", "operation": "AddFilms",
 "film": {"id": "RmlsbToxMDAx", "title": "A", "languageId": 1}}
```

exactly as before the second directive was added, and a `FilmCatalogued` with `{"actor": "u-42", "requestId": "r-9", "operation": "AddFilms", "filmId": "RmlsbToxMDAx"}` (no `releaseYear`: the client did not send one). Field `b` violates a foreign key, so its write and its messages roll back together and nothing about `b` reaches the relay. `deleteFilms` with three ids writes one message, of type `Mutation.deleteFilms`, whose `films` is the array of the three deleted ids: one message per `@outbox` per call, however many rows the call touched.

## User documentation (first-client check)

Draft for a new directive reference page, `docs/manual/reference/directives/outbox.adoc`, an `<outbox>` row and section in `docs/manual/reference/mojo-configuration.adoc`, and a how-to in `docs/manual/how-to/` for relaying the messages:

> **`@outbox`** tells graphitron to write a message to your transactional outbox every time a mutation field succeeds, inside the transaction that made the change.
>
> ```graphql
> directive @outbox(event: String, argMapping: String) repeatable on FIELD_DEFINITION
> ```
>
> Each `@outbox` on a field is one message, written once per successful call, in the order the directives appear. To publish something new, add another `@outbox` beside the ones you have: the published messages do not change, so their consumers are not affected. That includes a new version of an event: add `@outbox(event: "FilmCreated.v2", ...)` beside the old one, and remove the old one when its last consumer has moved.
>
> `event` is the message's event type, the name your downstream consumers subscribe to. It defaults to the field's coordinate (`Mutation.createFilm`); name it when the event should outlive the field's name, since renaming a field without an `event` renames the event its consumers subscribe to. Two `@outbox` in the schema with the same event, given or defaulted, fail the build, so on one field at most one `@outbox` may leave `event` out.
>
> `argMapping` builds the message's JSON payload, `jsonKey: source`, comma-separated, the same form `@service` and `@routine` use. A key may be a dot path (`film.title`) to nest objects. The same key twice in one `argMapping`, or a key that is also in the common payload, fails the build.
>
> **Where a value comes from.**
>
> [cols="1,3"]
> |===
> | `in.title` | A field argument, walking into nested input fields with dots, as in every `argMapping`. A path through a list yields an array; an input object yields an object of the fields the client sent. A field the client left out is absent from the payload; a field set to `null` is `null`.
> | `$result`, `$result.id` | The field's success outcome: what the field returns, or a field of it, read from what the write produced. For an `@mutation` field that is the written type's node id and its own column fields, read from the rows the write wrote; a field that would need another query (a `@reference`, a `@splitQuery` field, a `@service` child) is rejected at build. For a `@service` mutation it is the value your service returned. A list result yields an array. Through a payload type, address its data field: `$result.film.id`.
> | `$session.userId` | A member of the session handle your `<sessionState>` configuration names, decoded from the handle's type, the same `$session.<member>` binding `@service` and `@condition` use. The value is read from the connection the message's statement runs on, so it is the identity that connection was mounted with. Per-request values the message needs (a request id, say) belong in the handle, which your mount callback fills from the code that builds the request.
> | `$scope.coordinate`, `$scope.operationName`, `$scope.alias`, `$scope.tenant` | The field and its operation (`operationName` and `alias` are absent when there is none; `tenant` only in a `<tenantColumn>` build).
> |===
>
> Values appear in your API's own representation: an `ID` as your clients see it, an enum by its GraphQL name. Session values must be strings, numbers, booleans, enums, dates and times (as ISO-8601 strings), UUIDs, or maps and lists of those; a type that cannot be written as JSON fails the build when graphitron knows the type, and fails the field when it only learns it at request time.
>
> **The common payload.** `<outbox><payload>` takes an `argMapping` that every message carries, for what belongs on every event: who, which request, which operation. It reads `$session` and `$scope`; arguments and `$result` belong to a field and are not available there.
>
> Arguments, session and context are in memory; the outcome's columns are read by a subselect inside the statement that writes the message, from the rows this call wrote and nothing else. A `DELETE` has no rows left to read, so after one `$result` can only name the node id, which the delete's own result carries; any other `$result` field fails the build. The message describes the call, not the stored row; a consumer that needs a row's current state reads it by id after the event arrives.
>
> **Where messages go.** Configure the target once in the plugin configuration. A schema with an `@outbox` field and no `<outbox>` block fails the build, naming the field.
>
> * **An outbox table**, on PostgreSQL and Oracle: `<outbox><table>outbox</table></outbox>`. Graphitron resolves the table in your jOOQ catalog and inserts one row per message. By default it expects Debezium's outbox layout and fills `type` (the event), `aggregatetype` (the written table), `aggregateid` (the message key; open decision 1 settles how a message names it) and `payload`; name other columns with `<columns><type>event_type</type><payload>body</payload>...</columns>`. Leave `id` and any timestamp to column defaults. The build fails when the table or a configured column is missing, when `payload` is not a JSON, JSONB, CLOB or text column, or when another `NOT NULL` column has no default.
> * **An outbox function**, on PostgreSQL and Oracle: `<outbox><function>outbox_emit</function></outbox>`. Graphitron resolves the function or procedure in your jOOQ catalog and calls it once per message, binding the same roles as the table's columns to its parameters: `payload` is required, `type`, `aggregatetype` and `aggregateid` are passed when the routine has parameters of those names, and `<parameters>` maps other names. A return value is ignored. The build fails when the routine is missing or overloaded, when it has a parameter that is neither mapped nor defaulted, or when `payload`'s type is not JSON, JSONB, CLOB or text. What the routine does is yours, and it runs inside the mutation's transaction, so keep it to the emit. On PostgreSQL, to publish through logical decoding without a table:
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
> **When.** Right after the field's write succeeds and before its payload is resolved, as one more statement in the field's transaction. A field that failed writes no message: its transaction rolls back, and a failure an `@error` channel turned into a typed payload is a failure. If the emit fails, the field fails and rolls back, write included; the error is logged and redacted, and no `@error` channel can claim it, since an outbox fault is not business data.
>
> **Escape hatch.** On `Graphitron.newGraphQL()` an `@mutation` field emits inside its own transaction too. A `$session` source there fails the field, as a `$session` service parameter does, since graphitron mounted nothing on your connection; a common payload that reads `$session` therefore fails every `@outbox` field on the escape hatch.

## Design decisions

Settled with the user:

* **The payload is built from the arguments, the success outcome, the session and the context, and the only read is a subselect inside the message's own insert.** A payload built by a query after the write (a GraphQL selection over the written type, resolved in the transaction) was considered and rejected: it makes a read fault fail the write, holds the write's locks for as long as the read runs, grows with the rows written and with any list in the selection (past broker message limits), picks up requester-dependent `@condition(contextArguments:)` filters so the same change emits different events for different callers, can run `@service` or `@externalField` code inside the transaction, and reads nothing at all after a `DELETE`. `$result` stays inside that line: on an `@mutation` field it reaches only the node id and the written table's own columns, read by a subselect in the message's `INSERT ... SELECT`, keyed by the primary key the write already captures. The write statement's `RETURNING` is not touched, so the write and its re-select companion are exactly what they are today. The subselect is not the rejected read: it is part of the insert, so it is no statement of its own and cannot fail apart from it, it reads the written type's own columns by key (no selection, no filters, no code), and it is as wide as the columns the mapping names.
* **`argMapping` constructs the payload.** It is the binding syntax every graphitron directive that binds values already uses (dot paths into inputs, `$session`, `$source` on `@routine`), so `@outbox` adds the `$result` and `$scope` sources and a dot-path left-hand side for nesting, not a second syntax.
* **`@outbox` is repeatable, one message per directive.** Adding a directive adds a message and leaves every published one as it was, so a schema change around events is additive: new events, and new versions of old ones, are published beside what consumers already read. Merging occurrences into one message would make a new directive modify a published payload. `event` defaults to the field's coordinate, which is unique in the schema; two directives with the same event, given or defaulted, fail the build, since a consumer could not tell their messages apart.
* **A common payload, configured once.** `<outbox><payload>` carries what every event needs (actor, request, operation) from `$session` and `$scope`, so no field repeats it and no field forgets it. It has no arguments or outcome to read.
* **One message per directive per mutation call, not per result.** The message describes the call; a list result or a multi-row `DELETE` appears as arrays inside it.
* **Graphitron generates the emit; there is no consumer emitter.** The target is a property of the deployment and is known at build time, so it is plugin configuration, and the emit is a jOOQ statement the generator renders like any other, checked against the catalog when you build. A Java emitter the consumer registers on the runtime would put the dialect knowledge in consumer code that nothing checks, and need a JSON library on the consumer side.
* **Two targets: an outbox table and an outbox function.** The table covers the common relay (Debezium's outbox event router, or a poller) with nothing to write. The function is the extension point: the deployment's own emit lives in the database, where its dialect belongs, and graphitron calls it with the same message on either database. It is how a PostgreSQL logical message (five lines of SQL, in the docs) and an Oracle Transactional Event Queue are reached, so graphitron ships no mechanism-specific target.
* **The JSON is built in SQL from values graphitron has already converted.** jOOQ's JSON constructors (`DSL.jsonObject`, `DSL.jsonbObject`) render `jsonb_build_object` on PostgreSQL and `JSON_OBJECT` on Oracle, so the generated code needs no JSON library and no escaping of its own. The bound leaf values are the API's representation (argument leaves through their input type's `Coercing.serialize`, outcome leaves through their output type's, node ids encoded as the API encodes them), so the event speaks the API's vocabulary, not the columns'. Only the fields the client sent are rendered, so the statement's shape varies with the request.

Open, each with the recommendation the draft is written to:

1. **`aggregateid`.** Debezium uses the column as the Kafka message key, which decides partitioning and so per-aggregate ordering, and with one message per call there is no automatic choice. Recommended: an optional `aggregateId` argument on each `@outbox`, one scalar source in `argMapping`'s right-hand form (`aggregateId: "$result.id"`); `NULL` when it is absent or yields an array, documented as losing per-aggregate ordering.
2. **A successful call that wrote no rows** (an `UPDATE` that matched nothing, or one whose input sets no column, which the fetcher answers with a key read and no write). Recommended, now that `$result` columns come from a subselect over the written rows: the call writes no messages (the method returns at once when it is handed `null`), since a message with a `$result` column would select nothing and a binds-only one would describe a change that did not happen, and the two must not differ; the alternative writes every message with an empty `$result`.
3. **Multi-tenant fields.** A message is written on the connection the field wrote on. Recommended: confirm at pickup that a mutation field writes on one tenant connection (a `@service` batch naming two tenants is already refused) and reject `@outbox` on any variant that can write on two, rather than split one call into two messages.
4. **`@service` mutations.** Recommended: supported on the owned-connection engine, with `$result` reading the service's returned value in memory (accessors on a record or bean, columns on a jOOQ record), and refused on the escape hatch at request time, where the service's writes run in transactions graphitron cannot join.
5. **Where the directive may sit.** Recommended: Mutation fields only. A type-level `@outbox` hides the emission from the field that causes it.
6. **A list result that names both the node id and a column per element.** The subselect reads columns per row in SQL, but the node id is encoded by a generated Java helper, so one element object carrying both cannot be built in one statement. Recommended: a list `$result` may name the node id alone (an array of ids encoded in Java from the captured keys) or columns of the written table (aggregated in SQL), and a mapping that mixes both per element fails the build naming the field; lift the restriction if the store later states how a node id is built in SQL.
7. **`$context`.** Not offered. With R1120's supplied mount there are no mount payload parameters to type a `$context.<name>` from, and a `GraphQLContext` key checked at request time is the string-keyed lookup R1120 argues against. Per-request values a message needs (a request id) belong in the session handle, which the consumer's mount callback fills by closure, and `$session.<member>` reads them typed and from the statement's own connection. Recommended: leave `$context` out; a consumer with a schema-declared `contextArgument` it wants in an event can add it as a handle member or, if that proves too narrow, file the source as its own item against R1120's context story.

## Implementation

Phased, since each phase is observable on its own.

### The generated code

Two additions to a mutation's fetchers class: a companion method that writes the field's messages, and one call to it inside the write's transaction. A field without `@outbox` emits exactly what it emits today; an `@outbox` field changes only where its write was not already in a transaction (the `ID`-returning arm, below). Both are shown for `createFilm` from the example above (the table target, two `@outbox`, the common payload from the configuration), against the fetcher the generator emits today.

The call. Today the write is an expression lambda; with `@outbox` it becomes a block lambda that writes, emits, and returns the same keys, so the emit is in the write's transaction on both paths and the code after the lambda is unchanged:

```java
public static DataFetcherResult<Record> createFilm(DataFetchingEnvironment env) {
    try {
        DSLContext dsl = graphitronContext(env).getDslContext(env);
        Map<?, ?> in = (Map<?, ?>) env.getArgument("in");
        // ... length check and insertCols, unchanged ...
        Film filmTable = Tables.FILM;
        Record1<Integer> keys = dsl.transactionResult(tx -> {                  // was: tx -> DSL.using(tx)...fetchOne()
            Record1<Integer> written = DSL.using(tx)
                .insertInto(filmTable, insertCols.keySet())
                .values(insertCols.values())
                .returningResult(Tables.FILM.FILM_ID)                          // unchanged
                .fetchOne();
            outboxCreateFilm(DSL.using(tx), env, written);                     // the new call
            return written;
        });
        if (keys == null) return DataFetcherResult.<Record>newResult().data(null).build();
        Record payload = rowsCreateFilm(keys, env);                             // unchanged
        return DataFetcherResult.<Record>newResult().data(payload).build();
    } catch (Exception e) {
        return ErrorRouter.surfaceClientErrorOrRedact(e, env);
    }
}
```

The `ID`-returning arm. A `DmlTableField` that returns `ID` or `[ID]` (`DmlReturnExpression.EncodedSingle` / `EncodedList`, rendered by `emitEncoded`) has no transaction and no key records today: it runs the write on the bare `dsl` and encodes each `RETURNING` row in the fetch lambda, so on the escape hatch the write autocommits. `@outbox` on such a field gives the write a `transactionResult` that captures the key records, emits, and returns them, with the encoding moved after the lambda, the shape `emitKeysTransaction` already renders for the re-select arms. This is the spec's own `deleteFilms` example:

```java
public static DataFetcherResult<List<String>> deleteFilms(DataFetchingEnvironment env) {
    try {
        List<Map<?, ?>> in = env.getArgument("in");
        if (in.isEmpty()) { /* empty batch: no connection, no write, no message, unchanged */ }
        DSLContext dsl = graphitronContext(env).getDslContext(env);
        Film filmTable = Tables.FILM;
        Result<Record1<Integer>> keys = dsl.transactionResult(tx -> {          // new: the arm had no transaction
            Result<Record1<Integer>> deleted = DSL.using(tx)
                .deleteFrom(filmTable)
                .where(/* unchanged */)
                .returningResult(Tables.FILM.FILM_ID)
                .fetch();                                                        // was: .fetch(r -> NodeIdEncoder.encodeFilm(...))
            outboxDeleteFilms(DSL.using(tx), env, deleted);                     // films: $result, the ids encoded in Java from the captured keys
            return deleted;
        });
        List<String> payload = keys.map(r -> NodeIdEncoder.encodeFilm(r.get(Tables.FILM.FILM_ID)));   // encoding moves after the lambda
        return DataFetcherResult.<List<String>>newResult().data(payload).build();
    } catch (Exception e) {
        return ErrorRouter.surfaceClientErrorOrRedact(e, env);
    }
}
```

On the owned path this is a nested scope inside the field's transaction (a savepoint today, a joined scope once R1101's join rule lands); on the escape hatch it is what makes the write and its messages commit together. After a `DELETE` the rows are gone, so `outboxDeleteFilms` has nothing to select from and builds its payload from the captured keys alone, which is why `$result` on a `DELETE` is limited to the node id.

The method. One per `@outbox`-carrying coordinate, a sibling of the `rows<Name>` re-select companion on the same class, named from the field by the generated-names vocabulary like it. It takes the transaction's `DSLContext`, the environment and the captured rows, and reads each argument its mappings name from the environment by that argument's name (the `in` local is the field's argument called `in`, as in the fetcher, not the whole arguments map), and writes every message of the field in directive order. The JSON is built with jOOQ's `jsonbObject` from entries added under the same `containsKey` presence guards the write uses, so an omitted input field is an absent key and an explicit `null` is `null`; the table target is one `INSERT ... SELECT` per message. A message with a `$result` column selects it `FROM` the written table `WHERE` the captured key matches, so the read is part of the insert and the write statement keeps its key-only `RETURNING`; a message without one selects bind values only:

```java
private static void outboxCreateFilm(DSLContext tx, DataFetchingEnvironment env,
                                     Record1<Integer> written) {
    Film film = Tables.FILM;
    Map<?, ?> in = (Map<?, ?>) env.getArgument("in");                           // the argument the mapping names as `in`
    try {
        // common payload: <outbox><payload>, from $session and $scope only; the handle is read from tx, the connection this statement runs on
        List<JSONEntry<?>> common = List.of(
            key("actor").value(session(tx).userId()),
            key("requestId").value(session(tx).requestId()),
            key("operation").value(env.getOperationDefinition().getName()));

        // message 1, event "FilmCreated": film.id: $result.id, film.title: in.title, film.languageId: in.languageId
        List<JSONEntry<?>> filmEntries = new ArrayList<>();
        filmEntries.add(key("id").value(encodeFilm(written.value1())));        // $result.id: the API's node id, from the captured key
        if (in.containsKey("title")) filmEntries.add(key("title").value(in.get("title")));
        if (in.containsKey("languageId")) filmEntries.add(key("languageId").value(in.get("languageId")));
        List<JSONEntry<?>> created = new ArrayList<>(common);
        created.add(key("film").value(DSL.jsonbObject(filmEntries)));

        // No $result column: a select of bind values, no FROM.
        tx.insertInto(Tables.OUTBOX, Tables.OUTBOX.TYPE, Tables.OUTBOX.AGGREGATETYPE, Tables.OUTBOX.PAYLOAD)
            .select(DSL.select(DSL.val("FilmCreated"), DSL.val("film"), DSL.jsonbObject(created)))
            .execute();

        // message 2, event "FilmCatalogued": filmId: $result.id, title: $result.title, releaseYear: in.releaseYear
        // (the example's second directive also maps title: $result.title, to show a column read)
        List<JSONEntry<?>> catalogued = new ArrayList<>(common);
        catalogued.add(key("filmId").value(encodeFilm(written.value1())));
        catalogued.add(key("title").value(film.TITLE));                         // $result.title: read by the subselect below
        if (in.containsKey("releaseYear")) catalogued.add(key("releaseYear").value(in.get("releaseYear")));

        // A $result column: the subselect reads the row this call wrote, by the key the write captured.
        tx.insertInto(Tables.OUTBOX, Tables.OUTBOX.TYPE, Tables.OUTBOX.AGGREGATETYPE, Tables.OUTBOX.PAYLOAD)
            .select(DSL.select(DSL.val("FilmCatalogued"), DSL.val("film"), DSL.jsonbObject(catalogued))
                .from(film)
                .where(film.FILM_ID.eq(written.value1())))
            .execute();
    } catch (RuntimeException e) {
        throw new OutboxEmitException(e);                                       // ErrorRouter.claim declines it
    }
}
```

The function target differs only in the last statement: one `new OutboxEmit()` per message, with the role setters the routine has, `execute(tx.configuration())`. Value rendering shown here (`session(tx)`, leaf conversion through the types' `Coercing`) uses the helpers the generated fetchers already carry for `@service` bindings; the exact helper names are the implementer's to settle, the shape is the commitment.

What varies by coordinate, and so is the generator's work:

* *The call site.* The four DML emit sites (`renderTransactionalKeyFetch` for the re-select arms and the record-carrier fetchers, the hand-written `transactionResult` loop of the bulk fetcher, and `emitEncoded` for the `ID`-returning arm, which gains a `transactionResult` of its own as shown above) each gain the same optional step. A list result passes the `Result<RecordN>`, and the subselect's `WHERE` becomes a key `IN` over it, with the columns aggregated into an array (`jsonbArrayAgg`), so one message still carries the whole call; a single-row UPDATE or DELETE that matched nothing passes `null` (open decision 2 says whether the method then writes anyway).
* *The `RETURNING` list.* Unchanged: the key columns the write captures today, which are also the method's parameter type and the subselect's key.
* *The method body.* One block per message, one `if (in.containsKey(...))` per optional argument leaf, one entry per `$result` leaf (a bind from the captured key for the node id, a column of the written table through the message's subselect for the rest), the common entries shared.
* *No `@outbox`, no change:* a field without the directive emits exactly what it emits today.

In the generator a new emitter beside `RoutineWriteEmitter` renders the method from the coordinate's outbox rows, and `TypeFetcherGenerator` makes the one seam call at each of the four sites (`ctx.addCompanionMethod`, as `emitReentry` does for the re-select).

### Phase 1: the table target, for generated DML mutations

* **Directive.** `@outbox(event: String, argMapping: String) repeatable on FIELD_DEFINITION` in `directives.graphqls`. Classification attaches a list of outbox messages, one per occurrence in directive order, to the `MutationField` variants that write a known `@table` with a primary key (`DmlTableField`, `MutationDmlRecordField`, `MutationBulkDmlRecordField`); every other placement is a validate-time rejection naming the field and the reason (a Query field; a `@service` or routine write until phase 3; an empty `event`; two occurrences in the schema with the same event, given or defaulted; a key bound twice in one `argMapping`).
* **The mapping.** Parsed by the existing `argMapping` parser, extended with a dot-path left-hand side, and resolved at classify time into a payload tree in the model: argument paths against the field's arguments and input types (as `@service` resolves them); `$result` paths against the return type, accepting the node id field and column fields of the written table, through a payload type's data field, and rejecting anything beyond the written table's own columns (a `@reference`, a `@splitQuery` field, a `@service` child); `$session.<member>` paths are not admitted in this phase (a mapping naming `$session` is rejected, naming phase 1b); see Phase 1b; `$scope.<member>` against a fixed member list (`coordinate`, `operationName`, `alias`, `tenant`). The common `<payload>` resolves the same way with only `$session` and `$scope` admitted, and its keys are merged into each message's tree, a collision failing the build.
* **The outcome.** The DML fetchers already capture the written rows' keys through `RETURNING` for the payload's re-fetch (`Returning.primaryKey`, `Returning.nodeKeyTail`), and the write statement is left as it is. A message's `$result` columns are read by a subselect in the message's own `INSERT ... SELECT` (`FROM` the written table, `WHERE` its key in the captured keys), so the outcome costs no statement of its own; a message that names no `$result` column selects bind values only. The node id is encoded from the captured keys. A `DELETE` has no rows to select from, so there only the node id is addressable.
* **Configuration.** An `<outbox>` mojo parameter with exactly one of `<table>` or `<function>` (the function target lands in phase 2), an optional `<payload>`, and `<columns>` mapping the roles `type`, `aggregatetype`, `aggregateid` and `payload` to columns, defaulting to the Debezium names. The table and its columns are resolved against the jOOQ catalog when the model is built, and every rejection in the draft is a build failure naming the table and column. `payload` takes a JSON or JSONB column directly; a CLOB or text column takes the JSON rendered as text, which on Oracle needs `JSON_OBJECT(... RETURNING CLOB)` to lift the 4000-byte `VARCHAR2` default (confirm jOOQ 3.20 renders the `RETURNING` form at pickup).
* **The call.** In the generated fetcher, right after the write statement returns and inside the same `transactionResult` (which joins the field's transaction on the owned path, R1101 phase 2, and is the fetcher's own transaction on the escape hatch), the fetcher renders each message's payload tree into nested JSON constructors, each leaf guarded by presence so an absent argument contributes no key, and emits the field's messages in directive order, one statement per message: the table target as an `INSERT ... SELECT`, the function target as one call per message (so directive order is the statement order, which a single `UNION ALL` insert would not promise). `$scope` values come from the field's `DataFetchingEnvironment` (the coordinate from `env.getFieldDefinition()` and its parent type, the operation name from `env.getOperationDefinition()`, the alias from `env.getField()`) and, for `tenant`, from the tenant the fetcher already resolved for its write, on both paths, so the owned path and the escape hatch read one source and nothing waits on R1101's `MutationScope`. Should R1101's join rule land first, the emit sits in the fetcher's own `transactionResult` and joins the field's transaction with no change here; if that rule emits a mark on that call to silence its nested-scope warning, whichever of the two items lands second adds the mark to the emit's call site, which is the same call site. A field whose exception an `@error` channel claims never reaches the emit.
* **Failure routing.** The emit's exception, and a request-time source failure (an unserializable session value, `$session` on the escape hatch), is wrapped in an emitted `OutboxEmitException`; `ErrorRouter.claim` declines a chain carrying it, through a sibling of `TenantConnections.isMountFailure` of its own (R1101's `MutationHookException` decline is a different exception, and whichever item lands second merges the two declines into one place), so the field takes the redacted path and rolls back.

### Phase 1b: the `$session` source

Starts once R1102 (`$session.<member>`, the member decode of the session handle) is on trunk. `$session.<member>` resolves through R1102's decode of `SessionHooks.Handled`'s handle type, which holds for both mount forms (the reflected method form and R1120's supplied per-request callback), so this item does not reflect the mount and does not care which form a build uses. The generated emit method reads the handle from its own `tx`, the connection the message's statement runs on, as R1102 reads it for any statement, and never from `env`, which matters under vouched connections. The common `<payload>` admits `$session` from here, and the Goal's `actor: $session.userId` is this phase. On the escape hatch a `$session` source fails the field, as a `$session` service parameter does. A `$context` source is not offered (open decision 7).

### Phase 2: the function target

`<function>` resolves through `JooqCatalog`'s routine lookup (the one `@routine` uses for routines that are not table-valued) to a jOOQ-generated `Routine`, functions and procedures alike; an overloaded name is a build failure, since the parameters cannot pick one. The roles bind by parameter name, defaulting to `type`, `aggregatetype`, `aggregateid` and `payload`, with `<parameters>` mapping other names, and the payload parameter's type decides the JSON rendering as for the table's column. The emit is the routine's call through the field's `Configuration` (`Routine.execute`), which jOOQ renders as a `select` of a function or a `call` of a procedure per dialect; any return value is discarded.

### Phase 3: `@service` and routine writes

Starts once R1101 phase 2 (nested scopes join the field transaction) is on trunk; phases 1 and 2 do not wait for it.

`MutationServiceTableField` and `MutationServiceRecordField` (owned path only; request-time refusal on the escape hatch), with `$result` resolved against the service method's return type, and the routine-write variants whose terminus is a keyed `@table`. Settled by open decision 4.

## Tests

Execution tier, against an `outbox` table in Debezium's layout that `graphitron-sakila-db`'s `init.sql` gains, with `id` and a timestamp defaulted, and a common payload reading `$scope` (and `$session` once phase 1b lands):

* `createFilm` twice, the second violating a foreign key: the first film row and its outbox row commit together (same `xmin`); the second leaves neither.
* The common payload's keys are on every message: the session handle's members, the operation name for a named operation and its absence for an anonymous one. A session handle member that cannot be written as JSON fails the field and writes nothing.
* Two `@outbox` on one field produce two messages, in directive order, each with its own `event`, its own payload and the common keys; the first message's payload is byte-for-byte what it was with the first directive alone.
* An `@outbox` without `event` writes the field's coordinate (`Mutation.deleteFilms`) as the message's type.
* Arguments: an unmapped input field is absent, an omitted mapped field is absent, an explicit `null` is `null`, a dot-path key nests, a path through a list input yields an array, an enum is its GraphQL name, an `ID` is the encoded form the client sent, a string with quotes and non-ASCII characters round-trips.
* Outcome: `$result.id` equals the id the field returns; a `$result` column is in the payload, read by the message's own insert with the write's `RETURNING` unchanged (a recording `ExecuteListener` counts one statement per message beside the write and the re-select), and a column the client's request did not select is still there; `$result` on a `DELETE` accepts the node id and rejects a column at build; `deleteFilms` with `$result` yields the deleted ids as one array in one message, and under `missingAsDeleted` only the rows deleted.
* A field whose exception an `@error` channel claims writes no message.
* The emit failing (a check constraint on the outbox table rejects the row): the field fails redacted, an `@error` channel on the field does not claim it, the film row is gone, the next field commits.
* Escape hatch: the message commits with the write, on the `ID`-returning arm (`deleteFilms`) as well as the re-select arms; a failing emit (a check constraint on the outbox table) leaves the deleted row in place on none of them; a `$session` source fails the field.
* `ROLLBACK_ONLY`: emitted and discarded with the field.
* Multi-tenant: the message is written in the tenant database the field wrote on, `$scope.tenant` set.
* Phase 2, against a fixture `outbox_emit` function that writes its arguments to a second table: the committed field's call carries the same payload the table target writes, a rolled-back field leaves none, a role parameter the routine lacks is not passed, a `<parameters>` mapping binds a differently named one, and a routine that raises fails the field redacted with the write gone. A procedure is called the same way. The logical-message recipe from the docs is run with a replication slot (`pg_logical_slot_get_changes` with `test_decoding`) where the test database has `wal_level = logical`; check the local-db and CI databases at pickup, and keep it out of the suite if they do not.

Pipeline tier, each rejection naming the field, key or source: an empty `event`, two directives in the schema with the same event (two given, a given one equal to another field's coordinate, two on one field that leave it out), a key bound twice in one `argMapping`, a field key colliding with a common one, an unknown argument or input field, a `$result` path that needs a read, an unknown `$session` accessor, an unknown `$scope` member, an argument or `$result` source in the common payload, a `$context` source (not offered), a session handle member type known not to serialize; classification accepts the listed variants and rejects the others; the configuration is rejected for an `@outbox` field without `<outbox>`, both targets set, a missing table or column, an unsupported `payload` type and an unmapped `NOT NULL` column without a default, and for the function target a missing or overloaded routine and an unmapped parameter without a default.

Oracle: the repository has no Oracle execution tier. Both targets' statements, the insert with `JSON_OBJECT` and the routine call, are rendered for `ORACLE` in a pipeline-tier rendering check (the CLOB `RETURNING` form included); the item records in its Done evidence that it was also run by hand against an Oracle instance.

## Later extension: related data as of the transaction

Because a message's `$result` values are read by a subselect in its own insert, the select syntax can grow to capture related data in the state the transaction sees, so an event carries (say) the film's language name or its actors without the consumer reading back. It is a syntax extension on the same statement, not a new mechanism: a `@reference` path in the mapping becomes a join or a correlated `MULTISET` in the message's `SELECT`. It is out of scope here, and the design keeps it open by holding `$result` paths as a tree of selections over the written table rather than a flat column list. It reopens the concerns Design decisions rejected for a free-form read (message size, filters that differ by caller, code running in the transaction, locks held for the read), so when it is specified it admits only catalog-defined paths, applies no `@condition` or `contextArguments` filter, runs no `@service` or `@externalField`, and bounds list size; those guardrails are its Spec's to settle.

## Relation to other items

* **R1102** (Ready), `$session.<member>`: the dependency of phase 1b. The member decode of the session handle's type is R1102's, and this item consumes it rather than decoding the handle itself. Phases 1, 2 and 3 do not wait for it; a build whose schema uses no `$session` source needs nothing of it.
* **R1120** (Spec), the per-request supplied session state: independent of this item. `$session.<member>` goes through R1102's decode of the handle type, which `SessionHooks.Handled` carries for both the reflected mount and R1120's supplied callback, so a build that later switches forms keeps its `$session` mappings unchanged; there is no mount reflection here to retire. R1120 also removes the mount payload parameters, which is why `$context` is not offered.
* **R1101** (Ready; its phase 1 is shipped), one transaction per mutation field. Phases 1 and 2 here depend only on R1101 phase 1: the write and its messages share the field's transaction, so they commit or roll back together, and an `@error`-claimed field rolls back, whether the fetcher's own `transactionResult` is a savepoint (today) or joins the field's transaction (R1101 phase 2). Phase 3 here (`@service` and routine writes) depends on R1101 phase 2: a service that opens nested transactions must join the field's transaction, or a failure it swallows would leave the emit running over a partly written field. This item carries its own `OutboxEmitException` claim decline beside R1101's `MutationHookException` one, and reads `$scope` from the `DataFetchingEnvironment`, so it does not wait on R1101 phase 3's `MutationScope`; the two items touch the same `ErrorRouter.claim` decline and `renderTransactionalKeyFetch`, so whichever lands second merges there. R1101's `MutationHook` runs at the transaction's start and knows no rows; `@outbox` runs after the write and records what the call did. R1101 keeps field arguments away from the hook because they can carry personal data; `@outbox` emits only the arguments the schema maps.

## Other solutions we've considered

* **A payload read after the write.** See Design decisions.
* **A selection-set syntax for the payload** (`arguments: "{ in { title } }"`). It covers arguments only; `argMapping` reaches the outcome, the session and the context in one form graphitron already parses.
* **A consumer-registered emitter** (`withOutboxEmitter`). See Design decisions.
* **One message per written row.** Gives each row its own aggregate key, but repeats the call's arguments on every row of a bulk call, and the message describes the call, not a row.
* **The emit fused into the write**, as a PostgreSQL data-modifying CTE (`WITH w AS (INSERT ... RETURNING ...) SELECT ..., pg_logical_emit_message(...) FROM w`). Saves one round trip, but is PostgreSQL-only, must reference the emit from the outer `SELECT` or PostgreSQL skips it, and complicates the DML emitters. A possible later optimization; the separate statement is cheap and works on both databases.
* **Built-in targets per mechanism** (a PostgreSQL logical message, an Oracle Transactional Event Queue). Each needs mechanism-specific configuration (a prefix; a queue name and payload object type), and the first exists on one database only. The function target reaches both with a few lines of the deployment's own SQL.
* **The R1101 mutation hook alone.** It runs before the field's SQL, so it cannot know what the field wrote.
* **Change data capture without an outbox** (publishing every row change from the WAL or LogMiner). It publishes the table's shape, not an event the schema chose to publish, and does not exist the same way on both databases.

## Reviewer findings

### Round 1 (2026-10-28, Spec -> Ready, reviewer session 01WkW2JFVN5PqPWn2Ewgh2yG)

Author response: finding 1 is right (verified against `emitEncoded` and the generated `deleteFilms`, which writes on the bare `dsl` and encodes in the fetch lambda). Taken the first way: the `ID`-returning arm is the fourth call site and gains a `transactionResult` that captures the keys, emits and returns them, with the encoding after the lambda (see "The generated code"); the "no other change" sentence, the call-site list, the seam count, and the escape-hatch test are updated. Rejecting `@outbox` on that arm would have removed `deleteFilms` from the Goal and left delete events, the natural outbox case, unreachable. The non-blocking note is taken: the decline is its own sibling of `TenantConnections.isMountFailure`.

Verdict: withhold. One blocking finding, bearing on both questions; one non-blocking note. The goal reads on its own: a schema author adds `@outbox` to a mutation field, and every successful call writes one Debezium-style outbox row (or one call to their own emit routine) per directive, carrying a JSON payload they mapped from arguments, the written rows, the session and the context, committed or rolled back with the write. The plan extends shapes already in the tree (the `argMapping` parser behind `ArgBindingMap`, `Returning`, `JooqCatalog`, the `rows<Name>` companion registered through `addCompanionMethod`, the `ErrorRouter.claim` declines) rather than standing a parallel mechanism beside them, and every symbol it names exists as named, except `MutationHookException`, which is R1101 phase 3 and not yet on trunk (see the note).

**Finding 1 (question one: viability, and question two: fit). The call-site list misses the `ID`-returning DML arm, which is the spec's own `deleteFilms` example, and on that arm the write runs in no transaction and captures no key record.**

The spec names three emit sites: `renderTransactionalKeyFetch` for the direct-return and record-carrier fetchers, and the bulk fetcher's `transactionResult` loop. A `DmlTableField` that returns `ID` or `[ID]` (`DmlReturnExpression.EncodedSingle` / `EncodedList`) goes through neither. `TypeFetcherGenerator.emitEncoded` renders through `renderKeyFetch` on the bare `dsl`, with no `transactionResult`, and maps each `RETURNING` row straight to an encoded string in the fetch lambda (`.fetch(r -> encodeFilm(r.get(...)))`). So there is no `written` / `Result<RecordN>` of keys to hand the emit method, and no lambda to put the call in. The sakila schema's real `deleteFilms(in: [FilmDeleteInput!]!): [ID!]! @mutation(typeName: DELETE, table: "film")` is exactly this arm, and it is the Goal example whose `films: $result` array the spec promises.

Two promises in the spec do not hold for this arm as written:

- "No other change to what is emitted today ... one call to it inside the write's transaction": the arm has to be restructured to capture key records, emit, and then encode. That is a different emit shape from the one shown, and the implementer would have to design it.
- The escape-hatch promise ("On `Graphitron.newGraphQL()` an `@mutation` field emits inside its own transaction too", and the Tests bullet "Escape hatch: the message commits with the write"): on this arm the write is an autocommitted statement on the escape hatch. Without a transaction added there, the outbox insert commits separately, and a failing emit leaves the write in place. That is the event-without-write / write-without-event split the item exists to rule out.

What would satisfy it: name the encoded arm as a fourth call site, and state its shape. Either it gains a `transactionResult` that captures the key records, emits, and returns them, with the encoding moved after the lambda (the same shape as `emitKeysTransaction`), or `@outbox` on an `ID`-returning DML field is a phase 1 rejection. In the second case, the Goal example and the `deleteFilms` tests move to a field that phase 1 admits. Update the "no other change" sentence and the escape-hatch test to match whichever is chosen.

**Non-blocking.**

- Failure routing says `OutboxEmitException`'s claim decline sits "beside R1101's `MutationHookException` decline". That exception is R1101 phase 3's and is not on trunk, so if this item lands first there is nothing beside it yet. Relation to other items already says whichever lands second merges, so this is a wording point only: the decline needs its own sibling of `TenantConnections.isMountFailure` regardless of order.

### Round 2 (2026-10-28, Ready -> Spec reopen, reviewer session 01WkW2JFVN5PqPWn2Ewgh2yG)

Author response: finding 2 is right (R1120 replaces the reflected mount, and `$session.<member>` is R1102's). Taken the second way the finding offers: `$session` is out of phase 1 and arrives in a new phase 1b that waits on R1102, resolving through R1102's decode of the handle type for either mount form and reading the handle from the emit's own `tx`; the mapping bullet, the source table, the common-payload text, the generated example (`session(tx)`), the tests and the pipeline rejections are restated against that. `$context` is dropped rather than retyped (open decision 7): R1120 leaves no mount payload parameters to type it from, and per-request values go in the handle, so the Goal's `requestId` is now `$session.requestId`. Relation to other items names R1102 and R1120 and says this item is independent of R1120's form.

Verdict: reopened. I signed this item off in an earlier pass the same day. That pass missed that the plan rests on the `<sessionState>` mechanism R1120 replaces, and on R1102's unshipped member binding. One blocking finding on question two (fit). The rest of the plan is unaffected: the `<outbox>` table and function targets name catalog objects (data, not code), which is the shape R1120 itself keeps in the POM, and `$result`, `$scope`, argument sources, the four call sites and failure routing stand as signed off.

**Finding 2 (question two: fit). `$session` and `$context` are resolved against the reflected static mount, which R1120 replaces, and `$session.<member>` is R1102's feature, which this item neither depends on nor names.**

The Phase 1 mapping bullet resolves "`$session` paths against the `<sessionState>` mount's return type (accessors reflected at build time, as the mount is)" and types "`$context.<name>` ... when the name is a mount payload parameter". R1120 (now Spec) moves session identity to a per-request callback (`SessionState.of(mount, unmount)`). In that form there is no mount method to reflect: the handle type is named as data (`<sessionState><handle>fqcn</handle>`), `SessionHooks.Handled` carries it under a `MountSource` of `Reflected` or `Supplied`, and the mount contributes no payload parameters, because its inputs are captured by closure. R1120 also depends on R1102 for `$session.<member>`, the member decode this item uses (`$session.userId` in the Goal, the common payload and the generated code) but does not list. Today `$session` binds only the whole handle, and only on `@service`.

So the plan names a typing source that disappears in the form R1120 makes the default, and a binding syntax it does not own:

- `$session.<member>`: resolve it through R1102's member decode of `SessionHooks.Handled`'s handle type, which holds for both mount sources, rather than by reflecting the mount's return type. Add R1102 to `depends-on:` for whichever phase first admits a `$session` source, or keep `$session` out of phase 1 until R1102 lands.
- Where the handle is read: R1102 reads `$session.<member>` through the statement's own `dsl`, so from the connection the statement runs on, and rules out reading the handle from `env` (it matters under R1119's vouched connections). The generated companion's `session(env).userId()` should read it from the emit's `tx` instead.
- `$context.<name>`: with a supplied mount there are no mount payload parameters, so state what `$context` reads and how it is typed. Today's schema-declared `contextArguments` is one answer; a `GraphQLContext` key checked at request time is another, and it is the string-keyed lookup R1120 argues against. The Goal example and the common payload's `$context.requestId` should be consistent with whichever is chosen.
- Relation to other items: add R1102 and R1120, and say whether this item lands before or after R1120's `Supplied` source. If before, say what `$session` and `$context` do on a build that later switches forms.

What would satisfy it: the mapping bullet, the source table in the user-documentation draft (the `$session.userId` and `$context.requestId` rows, and the sentence on build-time type checks) and the pipeline-tier rejections ("an unknown `$session` accessor", "a session or context type known not to serialize") restated against the handle type R1102 and R1120 define, with the dependency recorded in front-matter or the phase that needs it.

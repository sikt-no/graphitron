---
id: R1113
title: "@outbox: emit a transactional outbox message in the mutation field transaction"
status: In Progress
bucket: feature
priority: 5
theme: runtime-connection
depends-on: []
created: 2026-10-27
last-updated: 2026-10-29
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
            argMapping: "filmId: $result.id, title: in.title, languageId: in.languageId")
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
 "filmId": "RmlsbToxMDAx", "title": "A", "languageId": 1}
```

exactly as before the second directive was added, and a `FilmCatalogued` with `{"actor": "u-42", "requestId": "r-9", "operation": "AddFilms", "filmId": "RmlsbToxMDAx"}` (no `releaseYear`: the client did not send one). Field `b` violates a foreign key, so its write and its messages roll back together and nothing about `b` reaches the relay. In phase 1 the common payload reads `$scope` only, so `actor` and `requestId` appear with phase 1b; the rest is as shown. `deleteFilms` with three ids writes one message, of type `Mutation.deleteFilms`, whose `films` is the array of the three deleted ids: one message per `@outbox` per call, however many rows the call touched.

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
> `argMapping` builds the message's JSON payload, `jsonKey: source`, comma-separated, the same form `@service` and `@routine` use. Keys are flat, one JSON key per entry; an object or an array in the payload comes from its source, not from the key: an input-object argument yields an object of the fields the client sent, a list yields an array. The same key twice in one `argMapping`, or a key that is also in the common payload, fails the build.
>
> **Where a value comes from.**
>
> [cols="1,3"]
> |===
> | `in.title` | A field argument, walking into nested input fields with dots, as in every `argMapping`. A path through a list yields an array; an input object yields an object of the fields the client sent. A field the client left out is absent from the payload; a field set to `null` is `null`.
> | `$result`, `$result.id` | The field's success outcome: what the field returns, or a field of it, read from what the write produced. For an `@mutation` field that is the written type's node id and its own column fields, read from the rows the write wrote; a field that would need another query (a `@reference`, a `@splitQuery` field, a `@service` child) is rejected at build. For a `@service` mutation it is the value your service returned. A list result yields an array. Through a payload type, address its data field: `$result.film.id`.
> | `$session.userId` | (Phase 1b.) A member of the session handle your `<sessionState>` configuration names, decoded from the handle's type, the same `$session.<member>` binding `@service` and `@condition` use. The value is read from the connection the message's statement runs on, so it is the identity that connection was mounted with. Per-request values the message needs (a request id, say) belong in the handle, which your mount callback fills from the code that builds the request.
> | `$scope.coordinate`, `$scope.operationName`, `$scope.alias`, `$scope.tenant` | The field and its operation (`operationName` and `alias` are absent when there is none; `tenant` only in a `<tenantColumn>` build).
> |===
>
> Values appear in your API's own representation: an `ID` as your clients see it, an enum by its GraphQL name. Session values must be strings, numbers, booleans, enums, dates and times (as ISO-8601 strings), UUIDs, or maps and lists of those; a type that cannot be written as JSON fails the build when graphitron knows the type, and fails the field when it only learns it at request time.
>
> **The common payload.** `<outbox><payload>` takes an `argMapping` that every message carries, for what belongs on every event: who, which request, which operation. It reads `$session` and `$scope`; arguments and `$result` belong to a field and are not available there.
>
> Arguments and session values are in memory; the outcome's columns are read by a subselect inside the statement that writes the message, from the rows this call wrote and nothing else. A `DELETE` has no rows left to read, so after one `$result` can only name the node id, which the delete's own result carries; any other `$result` field fails the build. The message describes the call, not the stored row; a consumer that needs a row's current state reads it by id after the event arrives.
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
* **`argMapping` constructs the payload.** It is the binding syntax every graphitron directive that binds values already uses (dot paths into inputs, `$session`, `$source` on `@routine`), so `@outbox` adds the `$result` and `$scope` sources to the flat `key: source` entries the parser already handles, not a second syntax. Nested payload objects built in the mapping are a later extension.
* **`@outbox` is repeatable, one message per directive.** Adding a directive adds a message and leaves every published one as it was, so a schema change around events is additive: new events, and new versions of old ones, are published beside what consumers already read. Merging occurrences into one message would make a new directive modify a published payload. `event` defaults to the field's coordinate, which is unique in the schema; two directives with the same event, given or defaulted, fail the build, since a consumer could not tell their messages apart.
* **A common payload, configured once.** `<outbox><payload>` carries what every event needs (actor, request, operation) from `$session` and `$scope`, so no field repeats it and no field forgets it. It has no arguments or outcome to read.
* **One message per directive per mutation call, not per result.** The message describes the call; a list result or a multi-row `DELETE` appears as arrays inside it.
* **The emit is built on the store, not on the walk.** The messages, their leaves and the target are fact relations and the emitter reads rows, so the capability is a relation plus an emitter and the transitional classification walk, which is draining, takes on nothing new. That is why `$session` waits for R1120: the handle's members are a classified-model fact today, and resolving against them here would be the one exception to the rule, which R1120 is about to retire. The cost is the seam in the walk-driven DML emitters, which is one call per site, shown in "Built on the store".
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
8. **`UPSERT`.** `graphitron_producer_write.kind` admits `INSERT`, `UPDATE` and `DELETE`, so an `UPSERT` coordinate has no row to be admitted by. Recommended: reject `@outbox` on `UPSERT` in phase 1 as a derived placement rejection, since the fetcher also refuses `UPSERT` on Oracle and returns no row-level outcome to tell an insert from an update; widening the relation to `UPSERT` is its own change.

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

The method. One per `@outbox`-carrying coordinate, package-private and static so a unit test calls it directly (see Tests), a sibling of the `rows<Name>` re-select companion on the same class, named from the field by the generated-names vocabulary like it. It takes the transaction's `DSLContext`, the environment and the captured rows, and reads each argument its mappings name from the environment by that argument's name (the `in` local is the field's argument called `in`, as in the fetcher, not the whole arguments map), and writes every message of the field in directive order. The JSON is built with jOOQ's `jsonbObject` from entries added under the same `containsKey` presence guards the write uses, so an omitted input field is an absent key and an explicit `null` is `null`; the table target is one `INSERT ... SELECT` per message. A message with a `$result` column selects it `FROM` the written table `WHERE` the captured key matches, so the read is part of the insert and the write statement keeps its key-only `RETURNING`; a message without one selects bind values only:

```java
static void outboxCreateFilm(DSLContext tx, DataFetchingEnvironment env,
                                     Record1<Integer> written) {
    Film film = Tables.FILM;
    Map<?, ?> in = (Map<?, ?>) env.getArgument("in");                           // the argument the mapping names as `in`
    try {
        // common payload: <outbox><payload>, from $session and $scope only; the handle is read from tx, the connection this statement runs on
        List<JSONEntry<?>> common = List.of(
            key("actor").value(TenantConnections.sessionMember_userId(tx, "Mutation.createFilm")),
            key("requestId").value(TenantConnections.sessionMember_requestId(tx, "Mutation.createFilm")),
            key("operation").value(env.getOperationDefinition().getName()));

        // message 1, event "FilmCreated": filmId: $result.id, title: in.title, languageId: in.languageId
        List<JSONEntry<?>> created = new ArrayList<>(common);
        created.add(key("filmId").value(encodeFilm(written.value1())));         // $result.id: the API's node id, from the captured key
        if (in.containsKey("title")) created.add(key("title").value(in.get("title")));
        if (in.containsKey("languageId")) created.add(key("languageId").value(in.get("languageId")));

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

The function target differs only in the last statement: one `new OutboxEmit()` per message, with the role setters the routine has, `execute(tx.configuration())`. Value rendering shown here (`TenantConnections.sessionMember_<name>`, leaf conversion through the types' `Coercing`) uses the helpers the generated fetchers already carry for `@service` bindings; the exact helper names are the implementer's to settle, the shape is the commitment.

What varies by coordinate, and so is the generator's work:

* *The call site.* The four DML emit sites (`renderTransactionalKeyFetch` for the re-select arms and the record-carrier fetchers, the hand-written `transactionResult` loop of the bulk fetcher, and `emitEncoded` for the `ID`-returning arm, which gains a `transactionResult` of its own as shown above) each gain the same optional step. A list result passes the `Result<RecordN>`, and the subselect's `WHERE` becomes a key `IN` over it, with the columns aggregated into an array (`jsonbArrayAgg`), so one message still carries the whole call; a single-row UPDATE or DELETE that matched nothing passes `null` (open decision 2 says whether the method then writes anyway).
* *The `RETURNING` list.* Unchanged: the key columns the write captures today, which are also the method's parameter type and the subselect's key.
* *The method body.* One block per message, one `if (in.containsKey(...))` per optional argument leaf, one entry per `$result` leaf (a bind from the captured key for the node id, a column of the written table through the message's subselect for the rest), the common entries shared.
* *No `@outbox`, no change:* a field without the directive emits exactly what it emits today.

### Built on the store

The method and its call are rendered from store rows, not from the classified model. The walk does not learn `@outbox`: no `MutationField` variant gains a message list and the classifier attaches nothing. What the emitter needs about a coordinate is a set of questions the store answers: which messages the field has and in what order, what each message's payload is made of, which table the write wrote and its key, and where messages go. A capability is added to the store as a fact relation, never as a new leaf on the classified model, so `@outbox` (an independent axis that would otherwise multiply across the three DML variants and the service and routine variants) is relations plus an emitter. The relations below follow the `adding-a-relation` discipline: what is captured from a corpus is shaped by the questions the emitter asks, what is derived is derived in the anchoring phase into tables, reads go upstream only, and each relation owes an owner, a grain sentence, and a mark and sweep. Relation names are the implementer's to settle; the grains and the provenance are the commitment. None is modelled on the dissolving `intent_` family.

Captured from the SDL (transcription, one row per thing the document wrote):

* **`@outbox` applications.** One row per `@outbox` application, keyed by the application's source position as `graphitron_ast_mutation_entry` is, carrying the `event` as written (absent when omitted). Written beside the `@mutation` entries in `GraphitronFieldEntries`, with the same `touched_at` mark and sweep, so removing a directive removes its rows and what hung off them.
* **The mapping pairs need no new writer.** `graphitron_ast_argmapping_pair_entry` is written by the generic argMapping writer (`GraphitronArgMappingEntries`), which finds any argument named `argMapping` by name through `VocabularyWalk`; the pairs of an `@outbox` appear once the directive is in the vocabulary (`directives.graphqls`), and `graphitron_argmapping_site` already places a repeated directive by `ordinal`. What does change is the sigil grammar, `ArgMappingSigil`: it learns `$result` and `$scope.<member>` beside `$session`, `$session.<member>` and `$source.<column>`, its per-site admission table admits `$result` and `$scope` at `@outbox` only (and, in phase 1b, `$session` there as well as at `@service` and `@condition`). Entries stay flat, `key: source` with a dotted source path, which is exactly the shape `GraphQLSelectionParser.parseEntries` and the sigil scan handle today (a nested `key: { ... }` form is not parsed by either, and is left to a later version).

Captured from the configuration (a corpus of its own, as the configured tags are):

* **The `<outbox>` block.** One row per graph naming the target (table or function and its name), one row per role binding (`type`, `aggregatetype`, `aggregateid`, `payload` and `<parameters>` entries), and the common payload's argMapping entries, all as written. Nothing is resolved at capture.

Derived in the anchoring phase, into tables (each reads only upstream families, and each is a table so integrity lives in its keys):

* **The messages of a coordinate.** One row per `@outbox` application on a field, keyed `(type, field, ordinal)` with the ordinal the document order of the repeats, carrying the effective event (the written one, else the coordinate). Every application is kept; there is no collision rule to choose because repeats are the point. Built on the pattern `mutations(...)` uses in `GraphitronAnchor`, over the field's own applications.
* **The leaves of a message.** One row per key path of a message's payload (its own `argMapping` entries plus the common payload's, merged), classified by source: argument path, `$result` node id, `$result` column, scope member, and from phase 1b session member. Resolved against what the store already holds: argument and input paths against the `graphql_` input relations and the node-id decode relations; `$result` against `graphitron_producer_write` (the write's resolved table and kind) and the table's key and columns in `sql_`; `$scope` against the fixed member list. A session member is not resolved in phase 1: the store holds no handle members until R1120's member relation exists (Phase 1b). A path that does not resolve is a row in the rejection relation below, never a missing row.
* **The resolved target.** The configured target resolved against `sql_table` and `sql_column` for the table (payload column type, `NOT NULL` columns without a default) and the `sql_routine` relations for the function (the same ones `RoutineWriteQuery` reads for parameters).
* **Rejections.** Every rejection in this item is a row of `graphitron_entry_defect`, reported at the `@outbox` application it affects and not thrown from a walk. That relation is keyed `(graph, source position, code)` and its position is an SDL position, so the application is the one position every rejection here can sit at, and the key is `(application, code)`, one row per application and code. Two consequences are stated rather than left to the implementer:
  * *Faults that live in the configuration are reported at every `@outbox` application they affect*, after the `DefaultPageSizeDefects` precedent, which reports a configuration-dependent fault at the SDL application that breaks it (the `@asConnection`) and decodes the stored defect into a located error. This covers an `@outbox` field with no `<outbox>` block, both targets set, a missing table or column, an unsupported `payload` type, a `NOT NULL` column with no mapping and no default, a missing or overloaded routine, an unmapped routine parameter, and the `<outbox><payload>` pairs' own faults (a key bound twice, an unresolvable source, a collision with a message's own key, and in phase 1b an unknown `$session` member). The message names the configuration element and the remedy. A fault in an `<outbox>` block that no `@outbox` uses reports nothing, since nothing reads it. A fault is repeated once per application, which is the cost of having no configuration-anchored defect relation; this item adds none.
  * *Several offending pairs of one mapping collapse into one row per application and code, with each pair's detail aggregated in pair order* (the pair `position` the entry relation already carries), so two unresolvable sources or two colliding keys in one `argMapping`, or one in the message's mapping and one in the common payload, are one row whose detail lists both. The same rule answers it for every code below.
  The codes, one per rule (names are the implementer's to settle): a placement the store does not admit (a coordinate with no `graphitron_producer_write` row, which includes every Query field; a `@service` or routine write until phase 3), a duplicated `event` across the schema, a key bound twice or colliding with the common payload, an unresolvable source, a target fault (the list above), and in phase 1b an unknown or unserializable `$session` member. A decoder beside `DefaultPageSizeDefects` mints the located build error from the stored rows.

The emitter. A query in `no.sikt.graphitron.emit` reads the three grains (coordinate and message, leaf, target) once per run into immutable rows, in the shape of `RoutineWriteQuery`: one statement per grain, leaves nested as a correlated `MULTISET` under their message, driven from the derived message relation. A sibling of `RoutineWriteEmitter` renders the companion method from a coordinate's row. It takes no `MutationField`: the write's table, its key columns and the captured record's type come from the row (the table from `graphitron_producer_write`, the key from the catalog's primary key in `sql_`). `TypeFetcherGenerator` asks for the rows at each of the four sites, registers the method with `ctx.addCompanionMethod` as `emitReentry` does for the re-select, and renders the call from the Java names in scope there (`tx` and the captured variable). That seam is the one place the walk-driven DML emitters ask the store; when the DML emitters later move onto store rows the seam is already the right shape and nothing else here changes.

### Phase 1: the table target, for generated DML mutations (shipped at `50e2b93b7`)

Shipped: the directive and its store vocabulary, the table target, the `<outbox>` mojo block, and the emit at the four DML sites with the `OutboxEmitException` routing. The code is the record of the design; what differs from the draft:

* **The seam supplies the captured columns.** The emitter does not read the written table's primary key from the store. Each DML site already holds the columns its write returns (the reentry correlation's columns, the record carrier's primary key, the node key's columns on an ID return), and passes them to the companion's renderer with the call. The store's query supplies the message grains, the leaves and the target only.
* **A node id needs a node key that is the primary key.** The captured columns are what the encoder is fed, so a node type whose key is not the written table's primary key makes `$result` or `$result.id` a derived rejection (`OUTBOX_SOURCE_UNRESOLVED`) rather than a second read.
* **On an ID-returning field the node id is bare `$result`.** `$result.<field>` resolves against the field's returned type, which an `ID` return does not have, so `id: $result` is the spelling there; a table-returning field takes either.
* **`aggregateid` is left to the column default.** The target relation records it when the table has the column, but no source fills it yet. The draft's check that every other `NOT NULL` column has a default is not built: the store holds no default information for a column, so it cannot be answered from the store.
* **Narrower than drafted.** `$scope.tenant` is not admitted (the members are `coordinate`, `operationName` and `alias`). A list-valued argument as a whole becomes a JSON array, and a list `$result` an aggregated array, but a path that walks through a list (`in.items.title`) is not built.
* **The payload column.** A jOOQ `JSON` or `JSONB` column takes the JSON value and a `String` column takes it rendered as text; the Oracle `CLOB` rendering is not built.
* **Test tiers.** A pipeline test pins the companion's shape and the call at each DML site; an execution test drives four mutations through GraphQL on PostgreSQL and reads the rows they leave; a unit-tier test calls the generated companion directly on a real PostgreSQL inside a rolled-back transaction.

### Phase 1b: the `$session` source (shipped at `eca552968`)

Shipped: `$session.<member>` as a source in an `@outbox` message and in the common payload. It reuses the session work that landed in between: the handle's member relation, and the session defects, which read every argMapping pair whatever site wrote it, so an unknown member, an ambiguous one and a build with no session identity are reported at an `@outbox` with no new rule. What this phase added:

* **The site admits a member and not the handle.** `$session.<member>` is admitted at the outbox site; the whole `$session` is rejected there, since a payload is JSON and a handle is not, and so is a nested path.
* **A leaf of kind `SESSION`.** The leaf relation resolves a member the handle offers once, by the member's name as the source path. It is written after the session members are, which the derivation order now says, and the derived outbox relations are owned by `derivation`, the gatherer that writes them and may read the member relation.
* **A member must be something JSON carries.** The check reads the slot's type from the store: text, the numbers, a boolean, a character, a `BigDecimal`, a `BigInteger`, a UUID or a `java.time` value, boxed or not. A member of another type, an `Optional` included, has no leaf and is `OUTBOX_SESSION_MEMBER_UNSERIALIZABLE` at the application, naming the type. The check is on the declared type alone; the draft's erased class is not read.
* **The read.** The companion reads the member through the generated guarded accessor on its own `tx`, the connection its statements run on, which the call site names to the emitter so `emit` imports no generator.
* **Owned acquisition only.** A field that reads a member fails on a caller-supplied `DSLContext`, which carries no mounted handle, and its write rolls back: the failure is the table target's, an `OutboxEmitException` the router never claims. A `$session` entry in the common payload therefore makes every message owned-path only, which the mojo reference says.
* **Tests.** Two fact documents (a message and a common payload key resolving; an unwritable member, the whole handle and an unknown member refused), sigil and pipeline tests, and an execution test on PostgreSQL that reads the principal and the session number the mount drew, and fails and rolls back on a caller-supplied `DSLContext`.

### Phase 2: the function target (shipped at `f31c78147`)

Shipped: the capture of routines that are no table, the routine target and its defects, and the emit of one routine call per message. The store did not hold these routines (`sql_routine` was fed from the table census, so it held table-valued functions only), which is why phase 2 began in the capture. What it came to:

* **The capture.** The catalog reads each schema's generated `Routines` class for the `Configuration`-first methods that return no `Result`, pairs each with the `Routine` class jOOQ's naming puts beside it (the SQL name, the IN parameters' SQL names and types), and writes `sql_routine` rows, `PROCEDURE` for a routine that returns no value (a void function included) and `FUNCTION` for one that does, with `sql_name` added to `sql_routine_parameter`. A SQL name that two routines share is written for neither. The Java parameter names are reflected, so jOOQ's output must be compiled with `-parameters`, as the `@routine` matching already requires.
* **The target.** `graphitron_outbox_routine_target` beside the table target, one row per graph holding the parameter position each role landed in. A parameter answers to a role by the name `<parameters>` binds the role to, else by the role's own name, underscores and case aside; every parameter must answer to a role, `type`, `aggregatetype` and `aggregateid` must take text and `payload` JSON, JSONB or text. A routine with a `sql_table` row of the same name is table-valued and no target. `sql_routine` gained a generated upper-cased name column and an index, which the lookup reads. Both targets have since become views under their own names (R876): each is the rule over the configuration and the catalog, with no derivation step and nothing stored, and every reader reads the same name.
* **The emit.** Each message's JSON is evaluated once, by a select of its constructor (from the written rows where a leaf reads a column of them), and `Routines.<method>(tx.configuration(), ...)` is called with it, the event and the written table's name, the aggregate id passed as `null`; a payload that evaluates to nothing calls nothing, as the table form inserts nothing. The failure routing is the table target's.
* **Configuration.** A build configures one target, so a per-execution `<outbox>` must replace the plugin-level one with `combine.self="override"`; Maven would otherwise merge the two and name both, which the build rejects.
* **Tests.** Fact documents for the capture, the resolving scenario and two defect cases; a pipeline test of the call; an execution test and a direct-call unit test on PostgreSQL against `outbox_emit`, which stamps the aggregate id so a row it wrote is told from one written directly.

### Phase 3: `@service` and routine writes

Owned by the work that moves `@service` generation onto the store, which builds it on the store-row seam phases 1 and 2 shipped (`OutboxQuery` reads the rows, `OutboxEmitter` renders the companion, and the DML sites call it through `outboxHook`) rather than on a second walk-driven call site. What is left for it to settle is the `$result` source: the companion reads the written rows back by a captured key, and a service return has none.

Starts once R1101 phase 2 (nested scopes join the field transaction) is on trunk; phases 1 and 2 do not wait for it.

A service or routine-writing coordinate becomes admissible by the same route: the store row that says the coordinate is a write. The routine write is already rendered from store rows (`RoutineWriteQuery`), so its seam is the same one; a `@service` mutation is still rendered from the classified model, so its seam is a second call site on the walk until that variant moves, and `$result` resolves against the service method's return type in memory rather than by subselect (open decision 4). Owned path only; request-time refusal on the escape hatch.

## Tests

Store tier, in `graphitron-model`, beside the capture and derivation tests of the neighbouring directives (`FactCaptureAgreementTest`'s anchors, `RelationRegistrationGateTest`, `MetaDeclarationGateTest`): every new relation declares an owner and a grain and is registered, and each is pinned directly against fixture schemas.

* Capture: two `@outbox` on one field write two application rows in document order, `event` absent when omitted; their argMapping pairs are captured at the right site and ordinal by the unchanged generic writer, with the new sigils parsed; removing one directive and re-capturing sweeps its rows and what hung off them. The `<outbox>` block captures as written, resolving nothing.
* Derivation, one anchor per relation: the messages relation keeps every application with the effective event (written, else the coordinate); the leaves relation classifies each key path by source and merges the common payload's leaves per message; the target relation resolves a table and a function against `sql_` (a missing column, an unsupported payload type, an unmapped `NOT NULL` column without a default, an overloaded routine).
* Each rejection is a `graphitron_entry_defect` row at the `@outbox` application, one per application and code: a coordinate with no write row (a Query field), a duplicated event across the schema, a key bound twice, a collision with the common payload, an unresolvable source, an `@outbox` field with no `<outbox>` block. Two pins on the collapse: two offending pairs in one `argMapping` (two unresolvable sources, and separately two keys colliding with the common payload) are one row with both details in pair order, never two rows with one key; and one configuration fault (a missing outbox table) with three `@outbox` applications is three rows, one at each application, while the same fault with no `@outbox` in the schema is none. The decoder mints a located build error from each stored row, pinned as `DefaultPageSizeDefectsTest` pins its own. The emitter reads no relation that runs later than the one it is anchored on.

Unit tier, the generated method called directly, against a real PostgreSQL. The companion `outbox<Field>(tx, env, written)` is package-private and static, so a test in the generated fetchers' package calls it with a `DSLContext` on a real connection to the execution tier's PostgreSQL (the `-Plocal-db` or Testcontainers database, with the sakila schema and the `outbox` table), a stub `DataFetchingEnvironment` carrying the arguments the test chooses, and a hand-built key record. Only the environment and the key record are stubbed; the statements run on the database, and the test asserts the rows they wrote by reading the `outbox` table back. There is no mocked `DSLContext`, no `MockConnection` and no in-memory database: the method's value is the SQL it builds (`INSERT ... SELECT`, the subselect, `jsonb_build_object`, `jsonb_agg`), which only a real database can accept, and a mock would assert the strings the spec forbids pinning. This tier is therefore in the `execution` group, excluded from the inner loop's `-DexcludedGroups=execution` run and part of the verification build, like the other execution tests. There is no GraphQL execution and no mutation round trip. Each test runs in a transaction it rolls back, or deletes the rows it wrote, so tests do not leak into the shared database's other tests (the cross-talk the execution tier already has known victims of). The tests assert:

* The payload shapes, one input per case: an omitted mapped field is an absent key, an explicit `null` is `null`, an input-object argument yields an object of the fields sent, a path through a list input yields an array, an enum is its GraphQL name, a string with quotes and non-ASCII characters round-trips.
* The subselect: with a film row inserted by the test, a `$result` column is read from it by the key record; a message with no `$result` column selects bind values only; a key record whose row does not exist writes nothing for a column message; a list of key records aggregates into one message with an array.
* Order and count: two directives write two rows in directive order, one statement per message (a recording `ExecuteListener`); a `null` key record writes no message.
* Scope: `$scope` values come from the stub environment. (Phase 1b adds: a `$session` member is read through the accessor on the `tx` passed in, and from no other connection.)
* Failure: a check constraint on the outbox table makes the method throw `OutboxEmitException` wrapping the database error, and the surrounding transaction the test opened is left to roll back.
* The function target's method, against the fixture `outbox_emit`, the same way.

Generated code is exercised in these tests, never read as strings: the pipeline-tier rule against code-string assertions on generated bodies holds here too.

Execution tier, against an `outbox` table in Debezium's layout that `graphitron-sakila-db`'s `init.sql` gains, with `id` and a timestamp defaulted, and a common payload reading `$scope` (phase 1b adds `$session`):

* `createFilm` twice, the second violating a foreign key: the first film row and its outbox row commit together (same `xmin`); the second leaves neither.
* The common payload's keys are on every message: the operation name for a named operation and its absence for an anonymous one. (Phase 1b adds the session handle's members, and a member that cannot be written as JSON fails the field and writes nothing.)
* Two `@outbox` on one field produce two messages, in directive order, each with its own `event`, its own payload and the common keys; the first message's payload is byte-for-byte what it was with the first directive alone.
* An `@outbox` without `event` writes the field's coordinate (`Mutation.deleteFilms`) as the message's type.
* Arguments: an unmapped input field is absent, an omitted mapped field is absent, an explicit `null` is `null`, an input-object argument yields an object of the fields sent, a path through a list input yields an array, an enum is its GraphQL name, an `ID` is the encoded form the client sent, a string with quotes and non-ASCII characters round-trips.
* Outcome: `$result.id` equals the id the field returns; a `$result` column is in the payload, read by the message's own insert with the write's `RETURNING` unchanged (a recording `ExecuteListener` counts one statement per message beside the write and the re-select), and a column the client's request did not select is still there; `$result` on a `DELETE` accepts the node id and rejects a column at build; `deleteFilms` with `$result` yields the deleted ids as one array in one message, and, when a key matches no row, only the rows actually deleted.
* A field whose exception an `@error` channel claims writes no message.
* The emit failing (a check constraint on the outbox table rejects the row): the field fails redacted, an `@error` channel on the field does not claim it, the film row is gone, the next field commits.
* Escape hatch: the message commits with the write, on the `ID`-returning arm (`deleteFilms`) as well as the re-select arms; a failing emit (a check constraint on the outbox table) leaves the deleted row in place on none of them; a `$session` source fails the field.
* `ROLLBACK_ONLY`: emitted and discarded with the field.
* Multi-tenant: the message is written in the tenant database the field wrote on, `$scope.tenant` set.
* Phase 2, against a fixture `outbox_emit` function that writes its arguments to a second table: the committed field's call carries the same payload the table target writes, a rolled-back field leaves none, a role parameter the routine lacks is not passed, a `<parameters>` mapping binds a differently named one, and a routine that raises fails the field redacted with the write gone. A procedure is called the same way. The logical-message recipe from the docs is run with a replication slot (`pg_logical_slot_get_changes` with `test_decoding`) where the test database has `wal_level = logical`; check the local-db and CI databases at pickup, and keep it out of the suite if they do not.

Pipeline tier, each rejection (a derived row at the store tier, above) surfaced naming the field, key or source: an empty `event`, two directives in the schema with the same event (two given, a given one equal to another field's coordinate, two on one field that leave it out), a key bound twice in one `argMapping`, a field key colliding with a common one, an unknown argument or input field, a `$result` path that needs a read, an unknown `$session` accessor, an unknown `$scope` member, an argument or `$result` source in the common payload, a `$context` source (not offered), and in phase 1b an unknown `$session` member and a session member type known not to serialize; classification accepts the listed variants and rejects the others; the configuration is rejected for an `@outbox` field without `<outbox>`, both targets set, a missing table or column, an unsupported `payload` type and an unmapped `NOT NULL` column without a default, and for the function target a missing or overloaded routine and an unmapped parameter without a default.

Oracle: the repository has no Oracle execution tier. Both targets' statements, the insert with `JSON_OBJECT` and the routine call, are rendered for `ORACLE` in a pipeline-tier rendering check (the CLOB `RETURNING` form included); the item records in its Done evidence that it was also run by hand against an Oracle instance.

## Later extension: nested payload objects

The first version's mapping is flat, `key: source`, because that is the shape the argMapping parser and the sigil scan already handle. A later version can build nested objects in the mapping with a selection set, `film: { id: $result.id, title: in.title }`. That needs real grammar work: `GraphQLSelectionParser.parse` reads nested selection sets and aliases but lexes `$`-prefixed text as a variable token and has no `alias: { ... }` form, and the sigil scan lifts sigils from flat entries only, so both would have to learn it together. Until then, nesting comes from the source (an input-object argument is an object, a list is an array).

## Later extension: related data as of the transaction

Because a message's `$result` values are read by a subselect in its own insert, the select syntax can grow to capture related data in the state the transaction sees, so an event carries (say) the film's language name or its actors without the consumer reading back. It is a syntax extension on the same statement, not a new mechanism: a `@reference` path in the mapping becomes a join or a correlated `MULTISET` in the message's `SELECT`. It is out of scope here, and the design keeps it open by holding `$result` paths as a tree of selections over the written table rather than a flat column list. It reopens the concerns Design decisions rejected for a free-form read (message size, filters that differ by caller, code running in the transaction, locks held for the read), so when it is specified it admits only catalog-defined paths, applies no `@condition` or `contextArguments` filter, runs no `@service` or `@externalField`, and bounds list size; those guardrails are its Spec's to settle.

## Relation to other items

* **R1102** (Done), `$session.<member>`: decoded the handle's members by reflection onto `SessionHooks.Handled`, a classified-model fact the store does not hold, and added the `TenantConnections.sessionMember_<name>(dsl, fieldCoordinate)` accessor. This item does not resolve against that decode, because R1120 retires it.
* **R1120** (Spec), session identity on the store: the dependency of phase 1b. It adds the handle's configured class and a derived member relation (one row per readable member, referencing the `code_read_slot` row of its accessor, which carries the member's type), and turns its `$session` checks into `graphitron_entry_defect` rows over the captured argMapping pairs. Phase 1b resolves `$session.<member>` against that relation and reads the member's type from its slot for the serialization check. Two things to confirm in R1120's own review, not assumed here: that the `sessionMember_<name>` accessor survives its retirement of `SessionHooks`, and that its `$session` defects are not scoped to `@service` and `@condition` sites. The `<outbox><payload>` pairs are captured from the configuration and not by the SDL argMapping writer, so phase 1b makes that capture join the same check. R1120 also removes the mount payload parameters, which is why `$context` is not offered.
* **R1101** (Ready; its phase 1 is shipped), one transaction per mutation field. Phases 1 and 2 here depend only on R1101 phase 1: the write and its messages share the field's transaction, so they commit or roll back together, and an `@error`-claimed field rolls back, whether the fetcher's own `transactionResult` is a savepoint (today) or joins the field's transaction (R1101 phase 2). Phase 3 here (`@service` and routine writes) depends on R1101 phase 2: a service that opens nested transactions must join the field's transaction, or a failure it swallows would leave the emit running over a partly written field. This item carries its own `OutboxEmitException` claim decline beside R1101's `MutationHookException` one, and reads `$scope` from the `DataFetchingEnvironment`, so it does not wait on R1101 phase 3's `MutationScope`; the two items touch the same `ErrorRouter.claim` decline and `renderTransactionalKeyFetch`, so whichever lands second merges there. R1101's `MutationHook` runs at the transaction's start and knows no rows; `@outbox` runs after the write and records what the call did. R1101 keeps field arguments away from the hook because they can carry personal data; `@outbox` emits only the arguments the schema maps.

## Other solutions we've considered

* **A payload read after the write.** See Design decisions.
* **Classify `@outbox` into the `MutationField` variants** (the walk). Attaches a message list to three DML variants, then to the service and routine variants in phase 3, and puts the argMapping resolution in the classifier that the store's relations already do better for every other directive.
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

Author response: finding 2 is right (R1120 replaces the reflected mount, and `$session.<member>` is R1102's). Taken the second way the finding offers: `$session` is resolved through R1102's decode of the handle type for either mount form and read from the emit's own `tx` (first drafted as a separate phase 1b waiting on R1102; R1102 has since shipped, so it is part of phase 1); the mapping bullet, the source table, the common-payload text, the generated example (`session(tx)`), the tests and the pipeline rejections are restated against that. `$context` is dropped rather than retyped (open decision 7): R1120 leaves no mount payload parameters to type it from, and per-request values go in the handle, so the Goal's `requestId` is now `$session.requestId`. Relation to other items names R1102 and R1120 and says this item is independent of R1120's form.

Verdict: reopened. I signed this item off in an earlier pass the same day. That pass missed that the plan rests on the `<sessionState>` mechanism R1120 replaces, and on R1102's unshipped member binding. One blocking finding on question two (fit). The rest of the plan is unaffected: the `<outbox>` table and function targets name catalog objects (data, not code), which is the shape R1120 itself keeps in the POM, and `$result`, `$scope`, argument sources, the four call sites and failure routing stand as signed off.

**Finding 2 (question two: fit). `$session` and `$context` are resolved against the reflected static mount, which R1120 replaces, and `$session.<member>` is R1102's feature, which this item neither depends on nor names.**

The Phase 1 mapping bullet resolves "`$session` paths against the `<sessionState>` mount's return type (accessors reflected at build time, as the mount is)" and types "`$context.<name>` ... when the name is a mount payload parameter". R1120 (now Spec) moves session identity to a per-request callback (`SessionState.of(mount, unmount)`). In that form there is no mount method to reflect: the handle type is named as data (`<sessionState><handle>fqcn</handle>`), `SessionHooks.Handled` carries it under a `MountSource` of `Reflected` or `Supplied`, and the mount contributes no payload parameters, because its inputs are captured by closure. R1120 also depends on R1102 for `$session.<member>`, the member decode this item uses (`$session.userId` in the Goal, the common payload and the generated code) but does not list. Today `$session` binds only the whole handle, and only on `@service`.

So the plan names a typing source that disappears in the form R1120 makes the default, and a binding syntax it does not own:

- `$session.<member>`: resolve it through R1102's member decode of `SessionHooks.Handled`'s handle type, which holds for both mount sources, rather than by reflecting the mount's return type. Add R1102 to `depends-on:` for whichever phase first admits a `$session` source, or keep `$session` out of phase 1 until R1102 lands.
- Where the handle is read: R1102 reads `$session.<member>` through the statement's own `dsl`, so from the connection the statement runs on, and rules out reading the handle from `env` (it matters under R1119's vouched connections). The generated companion's `session(env).userId()` should read it from the emit's `tx` instead.
- `$context.<name>`: with a supplied mount there are no mount payload parameters, so state what `$context` reads and how it is typed. Today's schema-declared `contextArguments` is one answer; a `GraphQLContext` key checked at request time is another, and it is the string-keyed lookup R1120 argues against. The Goal example and the common payload's `$context.requestId` should be consistent with whichever is chosen.
- Relation to other items: add R1102 and R1120, and say whether this item lands before or after R1120's `Supplied` source. If before, say what `$session` and `$context` do on a build that later switches forms.

What would satisfy it: the mapping bullet, the source table in the user-documentation draft (the `$session.userId` and `$context.requestId` rows, and the sentence on build-time type checks) and the pipeline-tier rejections ("an unknown `$session` accessor", "a session or context type known not to serialize") restated against the handle type R1102 and R1120 define, with the dependency recorded in front-matter or the phase that needs it.

### Round 3 (2026-10-29, Spec -> Ready, reviewer session 01WkW2JFVN5PqPWn2Ewgh2yG)

Author response: finding 3 is right, and the addendum makes it moot to add a relation here. Taken the third way: phase 1 does not admit `$session`; a new phase 1b admits it once R1120's handle-member relation exists, and resolves against that relation, so "built on the store" has no exception. The leaves bullet, the mapping bullet, the rejections (now `graphitron_entry_defect` rows), the store-tier and pipeline-tier tests, Design decisions and Relation to other items are restated. The two statements the addendum calls stale are replaced: R1120 is a dependency of phase 1b, and nothing resolves through R1102's decode. R1120's two open dependencies (the accessor surviving, and its defects covering `@outbox` sites including the configuration's common payload) are recorded as things to confirm in R1120's review. The cost is that the motivating `actor` key waits for phase 1b; the alternative, resolving `$session` outside the store for one version, would be reworked when R1120 retires `SessionHooks`.

Verdict: withhold. One blocking finding on question two (fit); question one holds. Round 2's finding is resolved: `$session.<member>` goes through R1102's decode and is read from the emit's own `tx`. That read works because jOOQ 3.20's `DefaultConfiguration.derive()` carries the `data` map, so `DSL.using(tx)` sees the handle the owned acquisition stored. Dropping `$context` is a direct answer. The move onto the store fits the architecture: `rewrite` is documented as the surface being drained, and store-derived rejections already project through the typed `Rejection` contract. Every store symbol the revision names exists as named and says what the plan says it does. That includes `graphitron_producer_write`, whose foreign key to `graphitron_ast_mutation_entry` and `kind` check admit exactly the `@mutation` writes phase 1 wants, with no routine, service or `UPSERT` rows (open decision 8). It also includes the generic argMapping writer, `graphitron_argmapping_site`'s `ordinal`, `ArgMappingSigil.Site` as the admission table to extend, and `RoutineWriteQuery` over `SQL_ROUTINE_PARAMETER`.

**Finding 3 (question two: fit). The leaves relation resolves `$session.<member>` against "what the store already holds", but the store holds no handle members: R1102's decode is a classified-model fact.**

"Built on the store" derives the leaves of a message in the anchoring phase and lists `$session.<member>` as resolved "against R1102's decode of the handle type". That decode is `ServiceCatalog.decodeHandleMembers`. It runs in `resolveSessionHooks`, reflects the mount's return type, and puts `SessionHooks.HandleMember` values on `SessionHooks.Handled` in the `graphitron` module's classified model. The store has only the authored strings (`store_graph_session_mount`, `store_graph_session_unmount`), and its comment rules the rest out: "the reflected signature is a build-time model fact, never stored back into this provenance family." R1004, the one open item on reading consumer classes, does not change that either.

So a derived relation in `graphitron-model` has nothing to resolve a session member against. The plan promises three things on top of that missing fact: the unknown-member rejection, the "session handle member type known not to serialize" rejection, and the store-tier test that classifies each leaf by source. An implementer would have to pick an answer mid-build, and each answer changes the plan's shape:

- Capture the decoded handle members into the store as a relation of their own. This is new provenance, written from reflection, which the session family's comment currently forbids. R1120's `<handle>` would feed it too.
- Leave `$session` leaves unresolved in the store, as a source kind carrying only the member name, and check them where `SessionHooks.Handled` lives. That makes it a stated exception to "built on the store", with the two rejections detected outside the derived rejection relation.
- Keep `$session` out of the first version.

What would satisfy it: pick one, and restate the leaves bullet, the rejections list, the store-tier and pipeline-tier tests, and Design decisions' "the emit is built on the store" to match. If the answer is a new relation, name its owner and grain the way the other relations in the section do.

### Round 3 addendum (2026-10-29, Spec -> Ready, reviewer session 01WkW2JFVN5PqPWn2Ewgh2yG)

R1120 changed after round 3 was written (`ee9c78cce`, now Spec): it moves session identity onto the store. That bears on finding 3, and it makes two statements in this plan stale.

What R1120 now plans, from its "The store" section:

- `store_graph_session_supplied` and `store_graph_session_handle` capture the switch and the `<handle>` class from the configuration.
- A derived member relation has one row per member the handle offers, keyed by graph and member name, referencing the `code_read_slot` row of its accessor. That row carries the member's type (`slot_type`, `erased_class`).
- Derived rejections are anti-joins between the captured argMapping pairs and those relations: a `$session` binding with no identity configured, a bare `$session` with no handle, and a `$session.<member>` the handle does not offer.
- `SessionHooks` (`HandleLess`, `Handled`, `HandleMember`), `ServiceCatalog.resolveSessionHooks` and `decodeHandleMembers` retire, and so does the method-reference form (`<mount>`/`<unmount>`).

Consequences for this item:

1. The first option in finding 3, a store relation for the handle's members, becomes R1120's relation rather than one this item adds. Resolved against it, every `$session` check this item needs can be answered on the store. The one check R1120 leaves in the validator compares a bound Java parameter's type with the member's yield, and an `@outbox` payload key binds no Java parameter. The "member type known not to serialize" rejection can read the member's `slot_type`.
2. `$session` then depends on R1120, not on R1102 alone. Phase 1 without `$session`, and `$session` once R1120 lands, is one way to sequence it; the choice is the author's.
3. Two statements are stale under R1120's current plan. Relation to other items says R1120 is "independent of this item". The mapping bullet resolves members through R1102's decode on `SessionHooks.Handled` "for both the reflected method mount and R1120's supplied per-request callback". `SessionHooks` retires, and only the supplied form remains.

Two dependencies to confirm against R1120, rather than assume. Both are R1120's to state, so they belong in R1120's review:

- The emit calls the generated `TenantConnections.sessionMember_<name>(dsl, fieldCoordinate)` accessor. R1120 does not name it: it says only that `ConnectionRuntimeClassGenerator` takes the new value in place of `SessionHooks`.
- R1120's `$session` rejections must not be scoped to `@service` and `@condition` sites, or `$session` keys on `@outbox` and the common payload escape them. The `<outbox><payload>` pairs are a further case: they come from the configuration, not from the generic SDL argMapping writer, so R1120's anti-joins over the SDL pairs do not see them unless this item's capture joins the same check.

### Round 4 (2026-10-29, Spec -> Ready, reviewer session 01WkW2JFVN5PqPWn2Ewgh2yG)

Author response: finding 4 is right. Both halves are settled in the Rejections bullet. Configuration-sourced rejections are reported at every `@outbox` application they affect, after the `DefaultPageSizeDefects` precedent, which also covers an `@outbox` field with no `<outbox>` block; this item adds no configuration-anchored defect relation, and a fault in a block nothing uses reports nothing. The defect key is `(application, code)`, and several offending pairs of one mapping collapse into one row whose detail aggregates the pairs in pair order, so there is no key collision; R1120 has not settled its own collapse yet, and this rule does not depend on it. The store-tier tests pin both the collapse (two offending pairs, one row) and the fan-out (one configuration fault, one row per application, none with no application), and the decode to a located error.

Verdict: withhold. One blocking finding on question two (fit), introduced by the revision. Finding 3 is resolved: `$session` moves to phase 1b and resolves against R1120's member relation, so "built on the store" holds with no exception. The stale statements are gone. R1120's two open dependencies are recorded where they belong, and R1120's round 1 raises both (its finding 3).

**Finding 4 (question two: fit). The rejections are restated as `graphitron_entry_defect` rows, "one row per written position and code", but that relation cannot hold the configuration's rejections, and its key collapses several bad pairs in one mapping.**

`graphitron_entry_defect` is keyed `(graph_name, source_name, source_line, source_column, code)`, and its position is a foreign key into `graphql_ast_entry`: a defect must sit at a position in the SDL. Two consequences follow, and the implementer would have to settle both:

- **Rejections that live in the configuration have no SDL position.** These are both targets set, a missing table or column, an unsupported `payload` type, a `NOT NULL` column with no mapping and no default, a missing or overloaded routine, an unmapped routine parameter, and the `<outbox><payload>` pairs' own faults (a key bound twice, an unresolvable source, and in phase 1b an unknown `$session` member). None of them can be a row there. The store has no configuration-anchored defect relation today; the only positionless rejections are `rejection_validation_error`'s walk residue, which is being drained. The precedent nearby, `DefaultPageSizeDefects`, reports a configuration-dependent fault at the SDL application that breaks it (the `@asConnection`), not at the configuration.
- **The pairs of one `argMapping` share their string's position.** `graphitron_ast_argmapping_pair_entry` carries a `position` within the string, but the defect key does not. So two pairs breaking the same rule in one mapping, such as two unresolvable sources or two keys colliding with the common payload, are two rows with one key. R1120's round 1 finding 2 is the same collision on its `$session` arms.

What would satisfy it: say where each configuration-sourced rejection is reported. One option is at every `@outbox` application it affects, after the `DefaultPageSizeDefects` precedent, which also covers "an `@outbox` field with no `<outbox>` block". Another is a configuration-anchored defect relation this item adds, with its owner, grain and the diagnostic arm the editor reads. Then state how several offending pairs in one mapping collapse (one row per string and code, with the pairs' details aggregated in pair order), and pin both in the store-tier tests. If R1120 settles the pair collapse first, adopting its answer is enough for the second half.

### Round 5 (2026-10-29, Spec -> Ready, reviewer session 01WkW2JFVN5PqPWn2Ewgh2yG)

Verdict: Ready. Finding 4 is resolved. Every rejection, configuration faults included, sits at the `@outbox` application, which is a schema position `graphitron_entry_defect`'s foreign key accepts, as `DefaultPageSizeDefects` does for its `@asConnection`. The key is `(application, code)`, and several offending pairs collapse into one row with their details in pair order, so there is no collision. The store-tier tests pin the collapse and the per-application fan-out. Phase 1b's two dependencies on R1120 are now stated there (`aa53b94ed`): `sessionMember_<name>(DSLContext dsl, String fieldCoordinate)` is emitted from the member relation with today's name and signature, and R1120's `$session` defect arms run over every SDL argMapping pair, whichever directive wrote it. That leaves the `<outbox><payload>` pairs to this item's own phase 1b check, as the plan says.

Non-blocking: R1125 (Backlog, a home for defects with no SDL position) lists this item's `<outbox>` rejections as a waiting consumer, but Relation to other items does not mention R1125. A line there would record that the per-application repetition is what R1125 would remove.

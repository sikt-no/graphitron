---
id: R1167
title: "Error-channel dispatch routes every throw where Graphitron 9 routed it"
status: Ready
bucket: dx
priority: 3
theme: error-channel
depends-on: []
created: 2026-11-11
last-updated: 2026-11-12
---

# Error-channel dispatch routes every throw where Graphitron 9 routed it

## Goal

A schema that ran on Graphitron 9 has each exception its services and mutations throw claimed, on Graphitron 10, by the handler that claimed it on Graphitron 9, so it lands in the same `@error` member with the same `message:`. A database error raised through jOOQ is offered to the `DATABASE` handlers and only then to the `GENERIC` ones; any other exception is offered to the `GENERIC` handlers only. Within each kind the schema's declared order decides, as it did on Graphitron 9. Today Graphitron 10 walks every handler in one declared order, so a broad `GENERIC` catch-all declared ahead of a `DATABASE` member silently takes the database errors that member used to get, and a `DATABASE` member can claim a service's own exception that Graphitron 9 offered only to `GENERIC` handlers.

This item covers the throws Graphitron 9 routed through a handler. The throws Graphitron 9 handed to consumer code instead (an unclaimed `DataAccessException`, every `IllegalArgumentException`) reach their Graphitron 9 member through R1171 (`error-channel-default-member`), which depends on this item and inserts a default member into the order fixed here. Until it lands, an application that relied on that consumer code, which every Graphitron 9 application did, still answers those throws differently.

Where each kind of throw lands on Graphitron 9.4, on Graphitron 10 today, after this item, and after R1171 on top of it, with a scenario matrix: [Error Dispatch, 9 to 10](https://claude.ai/artifact/4u5ReGFuvTTtvHCU5NxdBp).

The schema below follows the catch-all the error-channel how-to recommends for consumers moving from Graphitron 9, placed in a union beside a database member taken from the sakila example (`FilmRentalRateOverflow`):

```graphql
union UpdateFilmError = UgyldigInput | FilmRentalRateOverflow

type UgyldigInput @error(handlers: [
    {handler: GENERIC, className: "java.lang.IllegalArgumentException"},
    {handler: GENERIC, className: "java.lang.RuntimeException",
     description: "Operasjonen kunne ikke utføres."}
]) { path: [String!]! message: String! }

type FilmRentalRateOverflow @error(handlers: [{
    handler: DATABASE, sqlState: "22003", matches: "rental_rate",
    description: "The rental rate is too large"
}]) { path: [String!]! message: String! }
```

A rental rate that overflows its column reaches the service as jOOQ's `DataAccessException` around the driver's `SQLException`. Graphitron 9 answered `FilmRentalRateOverflow` with "The rental rate is too large". Graphitron 10 today answers `UgyldigInput` with "Operasjonen kunne ikke utføres.", because `RuntimeException` is declared first and `DataAccessException` is one. After this item it answers as Graphitron 9 did, and the union can be written in either member order.

The requirement is Alf's ruling of 2026-11-11: error-channel dispatch is backwards compatible with Graphitron 9. This item reads that as "every throw Graphitron 9 put into a payload through a handler lands in the same `@error` member, through the same handler, on Graphitron 10"; R1171 extends the reading to the throws Graphitron 9 put into a payload through consumer code. The places this item deliberately does not follow Graphitron 9 are listed under "Divergences", each with its reason. Alf ruled the three that needed a ruling on 2026-11-11: the compatibility ruling does not reach throws Graphitron 9 left at the top level (a), `message:` keeps Graphitron 10's reading (c), and `path:` is the response path (d).

The plan was revised on 2026-11-11 against the first Graphitron 9 consumer studied, recorded under "First-client evidence": its schemas showed that the second phase the earlier plan gave every throw reproduces nothing Graphitron 9 did for a non-database exception, and that the in-payload shape Graphitron 9 produced through consumer code needs its own declared member rather than a `GENERIC` catch-all.

## Glossary

- *Channel*: what a producer field (a `@service` field, a payload-returning `@mutation`, or a payload-returning `@routine` write) catches into: the payload's errors field and the `@error` types it lists.
- *Handler*: one entry of an `@error` type's `handlers:` argument. Its *kind* is `GENERIC`, `DATABASE` or `VALIDATION`.
- *Dispatch*: choosing, for one thrown exception, the handler that claims it, and so the member it lands in.

## What Graphitron 9 did

Read from the v9.3.0 tag (`graphitron-common`, `no.sikt.graphql.exception`) and checked against the 9.4.4 sources published to the internal package registry, which is the version the first consumer builds against. 9.4.4 dispatches exactly as 9.3.0; it adds `NoRowsAffectedException` (see "User documentation" for its Graphitron 10 replacement) and per-item batch failures (`PartialBatchFailureException`, which Graphitron 10 has no counterpart for and the first consumer does not use).

- `SchemaBasedErrorStrategy.handleException` unwraps one `CompletionException`, checks that the exception it then holds (the *outer* exception) is an instance of some class registered for the operation, and branches on the outer exception's own type: `ValidationViolationGraphQLException` and `IllegalArgumentException` go to methods the consumer wrote, a `DataAccessException` goes to the operation's `DATABASE` mappings only, and anything else goes to its `GENERIC` mappings only. The registered classes (`ExceptionStrategyConfigurationGenerator`) are `DataAccessException` when the operation has a `DATABASE` handler, each `GENERIC` handler's class, and `ValidationViolationGraphQLException` and `IllegalArgumentException` when `recordValidation.schemaErrorType` is among the operation's errors.
- An outer `DataAccessException` that no `DATABASE` mapping matched went to the consumer's `createDefaultDataAccessError`, which built an error in the payload; the method is abstract, so every application had one. A `DataAccessException` in an operation that registered it therefore always landed in the payload, never at the top level.
- `ExceptionToErrorMappingProviderGenerator` builds each of those two lists in union-member order, then `handlers:` order, keeping only handlers of the list's kind. `SchemaErrorMapper` takes the first mapping that matches. `GenericExceptionMatcher` walks the cause chain for an instance of its class; `DataAccessMatcher` reads `sqlState` and `code` off the first `SQLException` under the `DataAccessException` (`getCause(SQLException.class)`) and tests `matches:` against the messages of the `DataAccessException` links of the chain.
- So a `GENERIC` handler never saw an outer `DataAccessException`, and a `DATABASE` handler never saw any other outer exception. Within one kind, declared order decided, and a broad handler declared first hid a narrow one declared after it.
- `message:` was the claiming handler's `description:`, or else the outer exception's `getMessage()`; `path:` was `[fieldName]`.

## Today

`ErrorMappingsClassGenerator` emits one `Mapping[]` per channel, `concat(ByType.A, ByType.B, ...)`, with both kinds mixed in declared order. `ErrorRouter.claim` walks it mapping by mapping, each over the cause chain, and the first match wins. Two readers re-derive the claiming handler afterwards and must agree with dispatch: the union or interface type resolver (`GraphitronSchemaClassGenerator.buildErrorPolymorphicResolver`, through `ErrorRouter.matchesSource`) and `<ErrorType>Fetchers.message` (`ErrorTypeFetcherClassGenerator`), which walks the type's own handlers for the `description:`. All three take a type's handler order from `GraphitronType.ErrorType.handlers()`. Member order has two sources: the channel table reads it from the store (`ErrorChannelRelation.membersByOutcome`), the resolver from the model's `participants()`.

`claim` starts its walk at the exception the fetcher caught. On a batched producer that is a `CompletionException`: the `.exceptionally` arms after `thenApply` in `FrameEmitter`'s batched path and in `TypeFetcherGenerator.outcomeAsyncWrapTail` receive the wrapper `CompletableFuture` and DataLoader put around the throw (`GraphQLQueryTest.splitQueryConnection_negativeFirst_surfacesClientErrorThroughAsyncArm` pins the wrapping for the arm without a channel).

Three routes differ from Graphitron 9 as a result:

1. An outer `DataAccessException` goes to a `GENERIC` handler declared before the `DATABASE` handler that matches it, when the `GENERIC` class is `DataAccessException` or a supertype (`RuntimeException`, `Exception`, `Throwable`), or a class anywhere in its cause chain. The goal's example is this route.
2. Any other outer exception whose cause chain holds an `SQLException` can go to a `DATABASE` handler, which Graphitron 9 never offered it to. A service throwing its own `KontraktBruddException` around a `DataAccessException`, with a `GENERIC` member for `KontraktBruddException` declared after a bare `DATABASE` member, got the `GENERIC` member on Graphitron 9 and gets the `DATABASE` member today; with no `GENERIC` member that matches, it went to the top level on Graphitron 9 and gets the `DATABASE` member today.
3. A throw that reaches `claim` inside a `CompletionException` is offered wrapper first, and `CompletionException` is a `RuntimeException`. A `GENERIC` handler on `RuntimeException` or wider therefore claims the wrapper itself: without a `description:`, `message:` reads the wrapper's text ("pkg.Cause: text"), and the type's extra fields are read off the wrapper, not off the author's exception. Graphitron 9 unwrapped before it matched anything. Only producers below the root are batched, and Graphitron 9 had none, so no Graphitron 9 schema sees this route change; it is fixed here because the rule below needs one definition of the outer exception, and a walk that starts somewhere else is a second one.

The same mixing reaches `message:` within one type: a type with `GENERIC RuntimeException` (description A) declared before `DATABASE sqlState 22003` (description B) answers A for an overflow today and answered B on Graphitron 9.

## The rule

The *outer* exception of a throw is the exception the fetcher caught, past any `java.util.concurrent.CompletionException` or `ExecutionException` wrapping it.

Dispatch walks one order: the channel's `DATABASE` handlers, only when the outer exception is an `org.jooq.exception.DataAccessException` (or a subclass); then its `GENERIC` handlers. The first handler that claims wins.

- `DATABASE` is scoped to jOOQ's errors and `GENERIC` is the tail every throw reaches. An outer exception that is not a `DataAccessException` is never offered to a `DATABASE` handler, whatever its cause chain holds.
- Within each kind, declared order: the channel's `@error` types in member order, then each type's `handlers:` in array order.
- A `GENERIC` handler is tried against the cause chain from the outer exception down, and claims the first link that is an instance of its class and passes its `matches:`.
- A `DATABASE` handler is offered one link: the first `SQLException` in the chain below the outer exception, as Graphitron 9's `DataAccessMatcher` read it. Its `sqlState` and `code` are read off that link, and its `matches:` reads the messages from the outer exception down to it.
- The wrappers above the outer exception are never offered to a handler, and no message of theirs is read.
- `VALIDATION` runs before the service and never enters dispatch; nothing about it changes.

Every throw that Graphitron 9 put into a payload through a handler is claimed by the handler Graphitron 9 chose, outside the two narrow cases divergences (e) and (f) name. For a non-database exception the order is Graphitron 9's `GENERIC` list. For a database exception the `DATABASE` part is Graphitron 9's `DATABASE` list, and the `GENERIC` part only ever claims a throw no `DATABASE` handler claimed, which Graphitron 9 handed to `createDefaultDataAccessError`. Graphitron 10 keeps that `GENERIC` part because its own schemas claim database errors with `GENERIC` handlers on a `DataAccessException` subclass (the sakila example's `RentFilmConstraintViolation` and `FilmCreateConstraintViolation`, on `IntegrityConstraintViolationException`). R1171 inserts its default member between the two parts, which gives such a throw the member Graphitron 9 gave it.

`message:` follows the same rule at its own grain: it reads the `description:` of the first handler of the matched type, in that order for the throw's outer exception, that matches the claimed exception.

## Divergences

Each is a place where a Graphitron 9 schema can see a different answer. The implementation records each in a new history page (see "User documentation") with its reason as ruled, and each has a test asserting the direction taken.

- **(a) Wrapped exceptions are claimed.** Graphitron 9 offered an exception to handlers only when the outer exception was an instance of a class registered for the operation, so a `FooException` inside an unregistered wrapper became a top-level error even with a `GENERIC` handler for `FooException`. Graphitron 10 claims it through the cause chain, and a client that saw a redacted top-level error sees a typed payload error. Every throw this changes went to the top level on Graphitron 9, so the reading of the ruling above does not require reproducing it. **Ruled by Alf, 2026-11-11: kept.** The compatibility ruling does not cover throws Graphitron 9 left at the top level, and the handler was written to claim that exception. Graphitron 9's registered-class gate is therefore not reproduced on either side.
- **(b) Throws Graphitron 9 handed to consumer code.** Graphitron 9 called consumer-written methods for an unclaimed outer `DataAccessException` (a default error in the payload), for every `IllegalArgumentException`, and for validation exceptions. **Deferred to R1171**, which gives the first two a declared default member placed in the order this item fixes. Until it lands an unclaimed database error is redacted at the top level, and an `IllegalArgumentException` goes to the `GENERIC` handler that matches it, where Graphitron 9 handed every one to the consumer's method whatever the handlers said. Validation exceptions already have their Graphitron 10 counterpart in `VALIDATION` handlers.
- **(c) `message:` without `description:` reads the claimed exception.** Graphitron 9 read the outer exception's `getMessage()`. For a `DATABASE` handler that is jOOQ's message, which carries the SQL statement; Graphitron 10 reads the driver's `SQLException`. For a `GENERIC` handler that matched a cause, Graphitron 9 read the wrapper's message. **Ruled by Alf, 2026-11-11: kept.** Reproducing Graphitron 9 sends SQL text to clients, and `FetcherErrorResponse.matchedMessage` can still rewrite the text. The protection does not reach a `GENERIC` handler whose class is `DataAccessException` itself: it claims jOOQ's exception, and without `description:` its `message:` is jOOQ's text, SQL statement included, and at Oracle the column values the first consumer's own code says can carry a national identity number. That is the shape `migrating-from-legacy.adoc` recommends today for replacing `createDefaultDataAccessError`; the how-to says to pair it with `description:` or `matchedMessage`, and R1171 replaces the advice.
- **(d) `path:` is the response path.** Graphitron 9 answered `[fieldName]`; Graphitron 10 answers the response path, which uses the field's alias and carries list indexes. The two agree for an unaliased root field, the only place Graphitron 9 had a channel. **Ruled by Alf, 2026-11-11: kept** for the aliased case too; the response path is what GraphQL clients address. (Graphitron 9's own `handleIllegalArgumentException` already received the response path, and the first consumer answered with it.)
- **(e) The outer exception is found past any depth of wrappers.** Graphitron 9 removed one `CompletionException` and never an `ExecutionException`, so a `DataAccessException` inside two wrappers went to its `GENERIC` list only, where here it reaches the `DATABASE` handlers. Graphitron 9 had channels only on root fields, whose fetchers do not wrap a throw twice, so no Graphitron 9 schema is known to see this. Kept: one definition of the outer exception.
- **(f) `matches:` on a `DATABASE` handler reads more messages.** Graphitron 9 read the messages of the `DataAccessException` links of the chain; here it reads every link from the outer exception down to the claimed `SQLException`, which adds the `SQLException`'s own message (already part of jOOQ's) and the messages of non-jOOQ links between. A handler that matched on Graphitron 9 still matches, and one that did not can match only on text that appears in no jOOQ message. Kept: narrowing it would add a class test to a read that is otherwise one walk.

Graphitron 10 also gives a channel to fields below the root, which Graphitron 9 did not have at all; that is additive and not a divergence.

## Relation to the shadowed-handler diagnostic

R1165 (`error-handlers-ordered-by-narrowness`), which sorted handlers narrowest-first, was discarded on 2026-11-11 on Filip's ruling: it answered differently from Graphitron 9 for any schema declaring a broad handler before a narrower one of the same kind. What replaces it is R1168 (`shadowed-error-handler-is-reported`): declared order is kept within each kind, as here, and a handler that an earlier handler of the same kind shadows is reported, as a warning or an error. R1168 depends on this item, since the order it judges "earlier" by is the one this item defines; this item needs nothing from it. Its argument that both kinds are reachable for every throw loses its premise here (`DATABASE` is reachable only for a database exception); its conclusion, that no handler shadows one of the other kind, still holds. The per-kind declared rank the "Implementation" below emits is the grain R1168 plans to store.

The first consumer's schemas already carry handlers that an earlier one of the same kind shadows, and two exact duplicates that rule 8 rejects; they are listed under "First-client evidence" as input for R1168's choice between warning and error.

## Implementation

**The order, from the store.** The order is fixed when the generator runs; only whether its `DATABASE` part is walked depends on the throw. They are an `ORDER BY` over facts the store already holds, so no new relation is needed.

- *Keyed by `@error` type, not by channel.* Handlers are written on the type, and two of the three readers exist where no channel does: `<ErrorType>Fetchers.message` and the `ByType` constant are emitted for every `@error` type, and the type resolver for every union or interface whose members are all `@error` types, whether or not an errors field lists them. So `ErrorChannelRelation` gains a third component beside its two channel-keyed ones, keyed by `@error` type and reaching every `@error` type in the graph, channel or no channel: per type, its dispatch-capable handlers in array order, each with its kind and its *dispatch index*. The read joins `graphitron_ast_error_handler_entry` to the type through the type's `@error` application in `graphql_directive_application`, the join `ServiceEmission.validating` and the LSP's `TypeInformation` already make. The two existing components and the member-grain read are unchanged, and the record's javadoc says it now holds the error family's order at both grains.
- *The dispatch index is computed in the read.* It is the handler's ordinal among the type's handlers that are not `VALIDATION`, from 0, which is the index of its `Mapping` in the type's `ByType` array. The store's `position` counts every element of the list, `VALIDATION` included, so the read ranks, in SQL or in the record's constructor, and no emitter translates one into the other.
- *One function gives every reader its order.* Given a member list, it answers two per-kind sequences of (type, dispatch index), the `DATABASE` handlers and the `GENERIC` handlers of the members, each in member order then array order. A database exception's order is the first followed by the second; any other exception's order is the second alone, a suffix of the first's, so there is one order and not two. The channel tables call it with the channel's members (`ErrorChannelRelation.membersByOutcome`), the resolver with the container's participants, and `message` with the one type. No emitter tests a handler's kind itself, and none takes order from `ErrorType.handlers()`.
- *Mapping content stays on the model in this item.* `buildMappingEntry` still reads class name, `sqlState`, `code`, `matches` and `description` from `ErrorType.handlers()`, so for the length of this item one list has two sources. `ErrorMappingsClassGenerator.generate` holds them together: for every `@error` type of the model it compares the ordered kind sequence of the model's dispatch-capable handlers with the store's rows, and throws the "store and the classified schema have drifted" `IllegalStateException` it already throws for members when they differ. The comparison is total over the model's types, so a type the store lacks fails it too: its `ByType` mappings would otherwise be emitted with no channel sequence and no reader naming them, and nothing would say so. The assertion goes when the content moves to the store too; `graphitron_ast_error_generic_handler_entry` and `graphitron_ast_error_database_handler_entry` already hold all of it. That move is not this item.
- *The resolver's member order stays where it is.* It reads `participants()` and the channel table reads the store, as today; for a union both are declaration order. This item does not unify them.

R1168 is the second reader of handler order, and it was weighed in choosing no relation here. Its rule judges within one kind, so it needs the declared order within a container and never the outer kind, and it is planned as a store rule: it stores that order as a relation of its own and repoints `ErrorChannelRelation`'s read at it. Until then the read above is the order's only reader and stays a query. The outer kind stays out of the store either way; it is a fact about a throw.

**Emitted router** (`ErrorRouterClassGenerator`, `ErrorMappingsClassGenerator`):

- Each channel gets its two per-kind sequences, each written as references to the `ByType` elements (`ByType.X[i]`) in the order the function above gives, so a `Mapping` instance is shared, not copied. A holder of the two (a nested `ErrorRouter` record with a `database` and a `generic` array, say) under the channel's existing constant name keeps every catch arm's spelling; the choice of shape is the implementer's. The `ByType` arrays stay as they are, one per type in array order. `concat` goes.
- `ErrorRouter` gains `outer(Throwable thrown)`: the first link of the chain that is neither a `CompletionException` nor an `ExecutionException`, or `thrown` itself when the chain is nothing but those. It is the one definition of the outer exception.
- `ErrorRouter` gains one classifier, `outerKind(Throwable outer)`, answering an emitted enum: `DATA_ACCESS` when `outer instanceof org.jooq.exception.DataAccessException`, otherwise `OTHER`. It is the only place the decision is made, and an enum rather than a boolean because R1171 adds `ILLEGAL_ARGUMENT` beside them.
- `ErrorRouter` gains `firstSqlException(Throwable outer)`: the first `SQLException` in the chain below `outer`, or `null`. It is the one definition of the link a `DATABASE` handler reads, called by `claim` and by the read-side test below.
- `claim` takes the channel's holder, computes `outer(thrown)` once, and for `DATA_ACCESS` offers `firstSqlException(outer)`, and only it, to each `DATABASE` mapping in turn; then, for either kind, walks the `GENERIC` sequence over the cause chain from the outer exception down. It uses the outer exception wherever `thrown` stands for the root of the match today: the start of the cause-chain walk, the first argument of `Mapping.match` (which `messageOnPathContains` reads messages down from), the root `recordClaim` records, and `recordClaim`'s "the claimed link is the throw itself" test. `thrown` is still what `markFieldFailed`, the mount and outbox declines and `FetcherErrorLog.matched` receive. Its javadoc, the `Mapping` javadoc and the mappings class javadoc stop saying "source-declaration order" and state the rule.
- `Claims` and what `recordClaim` records are unchanged: a root is recorded when the claimed link is not the outer exception, and with no hook configured a claim of the outer exception itself still allocates no record. The outer kind is not recorded. It is a function of the outer exception, and the outer exception is the recorded root when there is one and the source itself when there is none, so a recorded kind would be a copy of a value the record already determines.
- `ErrorRouter` gains the read-side test, `offeredToDatabase(Throwable source, GraphQLContext context)`: whether dispatch offered this source to the `DATABASE` handlers. It holds when some root recorded for the source is a `DataAccessException` (by `outerKind`) whose `firstSqlException` is the source, and not otherwise. It is a question about the source and not the throw's kind read back, because the kind is not enough: a `GENERIC` handler can claim an `SQLException` deeper than the first (chain `DataAccessException`, `SQLException` A, `SQLException` B, with a `GENERIC` handler that only B passes), and a reader that re-checked the `DATABASE` mappings against B because the root is a `DataAccessException` would answer a `DATABASE` member dispatch never offered B to. "Some root" is the same union `matchesSource` takes over roots, and has the ambiguity `claim`'s javadoc already states for a source claimed by two fetchers through different wrappers. With no root recorded it answers false. In a request that is exact: the link a `DATABASE` handler claims is below the outer exception, so its claim always records a root. Where `claim` ran with a null `env` or context nothing is recorded and the readers do not re-check the `DATABASE` sequence; the javadoc says so.
- `matchesSource` keeps its contract: it re-checks one mapping, and the readers call it for a `DATABASE` mapping only under `offeredToDatabase`. Its arm without a root, `mapping.match(source, source)`, cannot disagree with dispatch there: a `matches:` text found in the source's own message is found on the path from the root down to it.

**Readers:**

- `GraphitronSchemaClassGenerator.buildErrorPolymorphicResolver` emits two `matchesSource` sequences, `DATABASE` and `GENERIC`, each in the order the function gives for the container's participants, and walks the first only when `offeredToDatabase` holds for the source.
- `ErrorTypeFetcherClassGenerator.messageMethod` does the same over the type's own two sequences. `lastOverrideIndex` becomes two cut-offs over the one order: the last handler declaring a `description:` in the whole order, and the last in its `GENERIC` suffix.
- The emitted fetchers' catch arms pass the channel's holder to `claim`: `ErrorDispatchFragments.outcomeArm` (reached from `ChannelCatchArmEmitter`, `RoutineWriteEmitter` and `TypeFetcherGenerator.outcomeAsyncWrapTail`) and `FrameEmitter.failure`. The hand-written test stubs `graphitron/src/test/java/no/sikt/graphitron/emit/stub/ErrorRouter.java` and `ErrorMappings.java` follow `claim`'s parameter type.

`claim` and the readers call the emitted methods; none repeats the `instanceof` test or the search for the first `SQLException`.

**A run without a store.** `EmitPlan.produce` hands the emitters `ErrorChannelRelation.empty()` when there is no store. Both mojos construct the generator with a store, so this reaches test harnesses only. "No store" and "a store with no rows" are different answers and the relation says which it is: `empty()` is the no-store relation, a relation `read(store)` returned is not, and the kind-sequence check above runs whenever the relation was read from a store, where a model type with no rows is drift. On the no-store relation the check is skipped, the channel tables are not emitted (as today), and the readers emit no handler lines.

One harness renders `@error` types with dispatch handlers and no store today: `GraphitronSchemaClassGeneratorTest`, whose resolver cases go through `SchemaShapeRenderTestSupport.schemaClass` and whose `message` cases through `errorFetcherSource`, both from `TestSchemaHelper.buildBundle`. Those cases assert emitted text (`body.contains(...)`), which `development-principles.adoc` ("Behaviour is pinned at the pipeline tier and above") bans, and they stop seeing handler lines once order comes from the store. They are not moved onto a store or handed a built relation to keep the text assertions alive: the cases that assert only handler-line text are deleted, and what they stood for is pinned by the reader test and the execution fixtures under "Tests". `TypeFetcherRenderTestSupport` passes `empty()` too, and reads only a producer's outcome from it, which this item does not change.

**Model.** No classifier change. `ChannelRuleChecks` rule 8 (identical criteria) stays: identical handlers have the same kind, where the second is still unreachable.

## Tests

The tests follow named fixtures, one per route: each reproduced Graphitron 9 route asserts agreement, each divergence asserts the direction ruled, and nothing claims general agreement with Graphitron 9.

Execution tier, on the sakila example (`GraphQLQueryTest` or a sibling; new SDL in `schema.graphqls`, any new service in `graphitron-sakila-service`):

- The goal's union, with the catch-all member first: an overflowing rental rate lands in `FilmRentalRateOverflow` with its `description:`. The same union with the members reversed answers the same.
- A service throwing its own exception around a `DataAccessException`, with a bare `DATABASE` member declared before a `GENERIC` member for the service's exception: the `GENERIC` member wins. The same with no `GENERIC` member: the throw is unclaimed and redacted at the top level, as on Graphitron 9, where today the `DATABASE` member claims it.
- One `@error` type with `GENERIC RuntimeException` (description A) before `DATABASE sqlState 22003` (description B): an overflow answers B, and `__typename` agrees.
- An outer `DataAccessException` that no `DATABASE` handler matches, with a `GENERIC` handler on a `DataAccessException` subclass (the sakila example's existing `IntegrityConstraintViolationException` members already pin this; the test names them as the fixture rather than adding one): the `GENERIC` handler claims it.
- A `DataAccessException` whose chain holds two `SQLException`s, with a `DATABASE` handler declared first that matches only the deeper one and one declared after that matches the first: the later handler claims it, as on Graphitron 9.
- The same chain, with a `DATABASE` member that matches only the deeper `SQLException` and a `GENERIC` member that only the deeper one passes: the `GENERIC` member claims it, and `__typename` and `message:` answer the `GENERIC` member, not the `DATABASE` one (`offeredToDatabase`).
- One `@error` type mixing `VALIDATION` with dispatch handlers, the `VALIDATION` entry written between them: a throw the handler after it claims answers that handler's `description:` (the dispatch index skips `VALIDATION`).
- One `@error` type whose `handlers:` are a described `GENERIC` handler, then a described `DATABASE` handler, then an undescribed `GENERIC` one: an overflow answers the `DATABASE` handler's `description:`, a throw the first `GENERIC` handler claims answers its `description:`, and a throw the last one claims answers the exception's own message (the cut-offs of `lastOverrideIndex` follow the dispatch order, not the array's).
- Divergence (a): a `GENERIC` handler for an exception thrown inside a wrapper no handler names claims it.
- Divergence (c): a `DATABASE` handler without `description:` answers the `SQLException`'s message, not jOOQ's; a `GENERIC` handler that matched a cause answers the cause's message, not the outer exception's.
- Divergence (d): an aliased producer field answers the alias in `path:` (`GraphQLQueryTest.createFilmWithErrors_aliased_pathNamesTheAlias` already pins it; the test names it as the fixture).
- Divergence (f): a `DATABASE` handler whose `matches:` text appears only in a non-jOOQ wrapper's message between the outer exception and the `SQLException` claims the throw.
- A batched producer below the root (the fixture behind `GraphQLQueryTest`'s child-payload `path:` case, or a sibling of it), whose throw reaches `claim` inside a `CompletionException`: a `DataAccessException` reaches the `DATABASE` handlers, and a `GENERIC` handler on `RuntimeException` without `description:` answers the author's exception's own message and extra fields, not the wrapper's.

Pipeline tier, the read. A fact document, `scenarios/error-handler-dispatch-order` under `graphitron-model/src/test/resources/facts/`, states a union of two `@error` types that between them carry all three kinds, with a `VALIDATION` entry between two dispatch handlers of one type, and claims the `graphitron_ast_error_handler_entry` rows it rests on. A reader test beside `FrameQueryTest` reads it through `FactGraphs` and asserts, as data, what `ErrorChannelRelation` answers: each type's dispatch-capable handlers with kind and dispatch index (the index skips `VALIDATION`; it is computed in the read, so it is asserted here and not claimed in the document), and the two per-kind sequences the order function gives for the union, in member order then array order. `undecided/error-handlers.graphqls` already holds a type of that mix, but `undecided` only shrinks, so the new document does not grow it; whether it can pull that document in with `@withFacts` depends on why it sits there, which the implementer checks.

No test asserts the emitted sequences as text. `ErrorMappingsClassGeneratorTest` gains two cases on a hand-built relation, its inputs literals at the point of assertion as its member cases already are: a relation and a model that disagree on a type's kind sequence, and a relation that is not the no-store one and lacks a type the model has, each asserting the drift `IllegalStateException`.

Unit tier, on the compiled `ErrorRouter` beside `ErrorRouterDatabaseMatchesTest`: `outer` over nested wrappers and over a chain of nothing but wrappers; `outerKind` on a `DataAccessException` subclass with no `SQLException` (the first consumer's domain exceptions: `DATA_ACCESS`, no `DATABASE` handler claims, the `GENERIC` sequence is walked); `firstSqlException` over a chain with two and with none; `offeredToDatabase` for the first `SQLException` below a recorded `DataAccessException` root, for a deeper one below the same root, for a source whose recorded roots are none of them a `DataAccessException`, with no root recorded, and with a null context.

## User documentation (first-client check)

`docs/manual/reference/directives/error.adoc`, Constraints, replacing the bullets "Declaration order decides", "Nothing sorts by specificity" and the cross-variant sentence of the duplicate-criteria bullet:

> * A database error raised through jOOQ (a `DataAccessException`, or a subclass of it) is offered to the channel's `DATABASE` handlers first, and only then to its `GENERIC` handlers. Any other exception is offered to the `GENERIC` handlers only, even when a database error is among its causes. A `GENERIC` catch-all on `RuntimeException` therefore does not take a database error from a `DATABASE` member, wherever either is declared.
> * A `DATABASE` handler reads the first `SQLException` below the database error: `sqlState` and `code` come from that one, and `matches:` reads the messages from the exception your code threw down to it.
> * Among handlers of one kind, declaration order decides, and the first match wins: the channel's `@error` types in declaration order, then each type's `handlers:` in array order. Declare the narrow handler before the broad one; a broad one declared first leaves the narrow one unreachable.
> * Two handlers of the same kind with identical criteria are rejected: the second would be unreachable.
> * Matching starts at the exception your code threw. The `CompletionException` or `ExecutionException` a future wraps it in is never offered to a handler, so a catch-all on `RuntimeException` claims the exception inside, and `message:` reads that one.

The `handlers` parameter row says "The first entry whose criteria match, in the order described under Constraints, wins". `docs/manual/how-to/error-channel.adoc`: the "Handler dispatch: source order, cause-chain unwrap" section is retitled and rewritten to the rule with the goal's example, the loop it prints starts at the outer exception and names the two per-kind sequences, its "orthogonal fields" sentence goes, the `message:` paragraph says "in the type's order for that exception", and the "Source order is significant" pitfall becomes "Order matters within a kind".

`docs/manual/how-to/migrating-from-legacy.adoc`, the `@error` bullet of "Things that didn't change", gains: "Dispatch keeps Graphitron 9's order: a `DataAccessException` goes to `DATABASE` handlers, then to `GENERIC` ones, which Graphitron 9 did not offer it to; anything else goes to `GENERIC` handlers only; each kind in declaration order." Its sentence on subclassing `SchemaBasedErrorStrategy` keeps the catch-all advice until R1171 replaces it, and gains: "Name the catch-all's class narrowly and give it a `description:`: a `GENERIC` handler on `DataAccessException` without one answers jOOQ's message, which carries the SQL statement." It also gains, for the zero-row refusal: "A handler on Graphitron 9.4's `no.sikt.graphql.exception.NoRowsAffectedException` becomes `{handler: GENERIC, className: "java.lang.IllegalStateException", matches: "matched zero rows"}`, which claims the `ZeroRowsMatchedException` a list-input `UPDATE` throws when a key matches no row." It links the history page below for the divergences.

A new history page, `docs/history/error-dispatch-divergence.adoc`, sibling of `argument-resolution-divergence.adoc` and listed in `docs/history/index.adoc`: the compatibility ruling with its date, Graphitron 9's dispatch as described above (version 9.4.4), and divergences (a) to (f) with their reasons as ruled.

## Roadmap entries

- Changelog: routing changes for a Graphitron 10 schema that mixes kinds with a broad handler of the other kind declared first, or that relies on a `DATABASE` handler claiming a non-jOOQ exception wrapping a database error. The commit is `feat(R1167)!` with a `BREAKING-CHANGE:` footer: "A `DataAccessException` now reaches `DATABASE` handlers before `GENERIC` ones, and any other exception reaches `GENERIC` handlers only; a schema that relied on a `GENERIC` handler declared first taking database errors declares a `DATABASE` handler for them instead, and one that relied on a `DATABASE` handler claiming its own exception around a database error declares a `GENERIC` handler for that exception. A `DATABASE` handler reads only the first `SQLException` of the chain. On a batched field a handler now claims the exception inside the `CompletionException`, never the wrapper, so `message:` and the type's extra fields read the exception the service threw."

## Retired vocabulary

- "source order", "source-declaration order" and "Declaration order decides" as the description of dispatch across kinds: emitted javadoc on `ErrorRouter.claim`, `Mapping` and the mappings class; the javadoc of `ErrorDispatchFragments.outcomeArm`; `error.adoc`; `error-channel.adoc`.
- "the runtime's source-order findFirst on MAPPINGS": the rule 8 rejection message and javadoc in `ChannelRuleChecks.checkDuplicateMatchCriteria`, and the copy `FieldBuilder` keeps for the DML path. The rule stays; its message says the second handler is unreachable behind the first of the same kind.
- "inspect orthogonal fields" and "reach different exceptions" (the claim that `GENERIC` and `DATABASE` handlers cannot overlap): `error.adoc`, `error-channel.adoc`, the rule 8 javadoc.
- The `claim` loop printed in `error-channel.adoc` with `thrown` as the start of the walk.
- The `concat` helper of the emitted mappings class (`ErrorMappingsClassGenerator.CONCAT_METHOD`).

## Other solutions we've considered

- **Narrowest first (the discarded R1165).** Ends the ordering chore, but answers differently from Graphitron 9 wherever a broad handler of one kind was declared first, which the ruling excludes.
- **`DATABASE` always before `GENERIC`, whatever the throw.** One emitted order, no runtime selector. It sends the second route under "Today" (a service's own exception around a database error) to the `DATABASE` member, as today, against Graphitron 9.
- **Graphitron 9's single kind, no `GENERIC` part for a database error.** Exact Graphitron 9 for routing between handlers, but it breaks Graphitron 10's own schemas, whose `GENERIC` handlers on `IntegrityConstraintViolationException` claim database errors, and Graphitron 9 did not leave those throws unclaimed: it handed them to consumer code. R1171's default member is the Graphitron 9 answer for them, and placed between the two parts it reproduces Graphitron 9 wherever it is declared.
- **Two phases for every throw (this item's plan before 2026-11-11).** A database exception `DATABASE`-first then `GENERIC`, any other `GENERIC`-first then `DATABASE`. The second phase for a non-database exception reproduces nothing Graphitron 9 did, keeps the second route under "Today" open whenever no `GENERIC` handler matches, and is reachable otherwise only through a `GENERIC` handler that covers the class and fails its `matches:`. It also needs two independent orders where the rule here needs one and a suffix of it.
- **Graphitron 9's registered-class gate in this item.** Reproduces Graphitron 9's top-level answers for wrapped exceptions (divergence a). It changes only throws Graphitron 9 left at the top level, which Alf ruled on 2026-11-11 the compatibility ruling does not cover (divergence a).
- **The default member inside this item.** It is a new authored fact with its own grain question (Graphitron 9's was application-wide), its own validation and its own reference pages, so it is R1171; this item fixes the order it slots into, and its `outerKind` enum leaves room for the `ILLEGAL_ARGUMENT` kind R1171 adds.
- **Filter by kind at runtime.** `Mapping` gains a kind accessor and every reader makes two passes over the mixed table. Smaller to emit, but three emitted readers evaluate the same kind predicate and can drift; the per-kind sequences are generation-time facts, so they are emitted once.
- **A stored match-order relation.** The order is an `ORDER BY` over handler kind and position already in the store; a relation for it holds nothing a query does not, while this item's read is its only reader. R1168 brings the second reader and the relation with it, as said under "Implementation".
- **Record the outer kind in `Claims`.** Dispatch writes its decision and the readers read it back. The record already holds what the decision is a function of (the root), so the kind would be a second copy that can disagree with the first, and recording it for every claim would end the shortcut that allocates no record for a claim of the outer exception with no hook configured.
- **Read the throw's kind back on the read side** (`outerKind(source, context)`, this plan before its second revision). The readers walked the `DATABASE` sequence whenever a recorded root was a `DataAccessException`. The kind does not say which link the `DATABASE` handlers were offered, so a deeper `SQLException` a `GENERIC` handler claimed was re-checked against them. `offeredToDatabase` asks the question the readers have; R1171 adds a sibling for its default member, and the enum stays on the dispatch side.
- **The first-link test inside `DatabaseMapping.match`.** One place for the rule, but `matchesSource`'s arm without a root passes the source as its own chain, where any `SQLException` is trivially the first. A test for `DatabaseMapping` inside `matchesSource` would put a kind fork in a reader, which "Filter by kind at runtime" above already turns down.
- **Leave handler order on `ErrorType.handlers()`.** It is already ordered and already tagged by kind, and nothing new would be read from the store. It keeps three emitters each testing a handler's kind, and adds a read of the classified model where the codebase is moving reads to the store.
- **Unwrap for the outer kind only, and keep walking from the caught exception.** Smaller, and nothing changes for a batched field. It leaves two definitions of where a throw starts, and the catch-all on a batched field keeps claiming the `CompletionException`.

## First-client evidence

The first Graphitron 9 consumer studied is sis, an internal application on Graphitron 9.4.4, read on 2026-11-11 from its repository and from the Graphitron 9.4.4 sources it builds against. What it declares and does, as it bears on dispatch:

- 68 `@error` types with 188 handlers: 186 `DATABASE`, all keyed on Oracle `code:` (none on `sqlState:`), and 2 `GENERIC`. 161 producer fields have an errors union (94 `@mutation`, 67 `@service`).
- One type, `UgyldigInput`, carries 84 `DATABASE` handlers and is the first member of 159 of the 161 unions.
- The two `GENERIC` handlers are always declared after `UgyldigInput`, with no `DATABASE` member after them. Neither divergent route under "Today" occurs; this item changes no answer its declared handlers give.
- It subclasses `SchemaBasedErrorStrategy`: an unclaimed `DataAccessException` (`createDefaultDataAccessError`, with a sanitised message and a trace reference), every `IllegalArgumentException` (61 of its services throw one to reject input; response path, the exception's own message) and validation violations all become `UgyldigInput` in the payload. That is divergence (b), and the reason R1171 exists.
- All ten of its domain exception classes extend `DataAccessException` and are thrown without an `SQLException` cause. On Graphitron 9 each went to the `DATABASE` list, matched nothing, and reached the default; its `GENERIC` handler on one of them (`UgyldigId`, on `UgyldigVerdiException`) could never claim. Here it claims (the `GENERIC` part of a database exception's order), until R1171's default precedes it.
- It already depends on declared order within a kind, and carries handlers an earlier one of the same kind shadows (in `LeggTilVeivalgperioderForKullError`, `UgyldigInput`'s `I01_EMNEKOMBINASJON` ahead of `VeivalgperiodeFinnesAllerede`'s `I01_EMNEKOMBINASJON_VALG`; in `OpprettStudieoppbygningsdelerError`, a `code`-only handler ahead of two narrower ones). `UgyldigInput` holds two exact duplicate pairs, and two unions repeat a `UgyldigInput` tuple in another member; rule 8 rejects every channel that contains them, which Graphitron 9 accepted. Input for R1168.
- Its `FantIngenOppforingAaEndre` maps Graphitron 9.4's `NoRowsAffectedException` in 12 unions. The port replaced it with `GENERIC IllegalStateException`, `matches: "matched zero rows"` (below); this item's migration-guide text names that replacement.

The above is sis's main branch. sis also has a Graphitron 10 port in progress (branch `sis/upgrade-graphitron`, tracked in its `sis/GRAPHITRON10_STATUS.md`), which rebuilt Graphitron 9's consumer code as handlers before this item existed. Read on 2026-11-12, as it bears on this item:

- `UgyldigInput` is declared last in every union (79 reordered), so the specific members win under today's single order.
- `UgyldigInput` carries a `GENERIC` handler on `IllegalArgumentException` without `description:`, and a `GENERIC` catch-all on `DataAccessException`, last in its list, whose message a `FetcherErrorResponse` recomputes as Graphitron 9's text plus the trace reference (on R1087's hooks).
- The duplicate criteria came off `UgyldigInput` because rule 8 forced it.
- `ErrorMapper` throws a new `SjekkskrankeException` for ORA-02290 instead of `UgyldigVerdiException`, so `UgyldigId`'s `GENERIC` handler does not take it ahead of the catch-all, which gives Graphitron 9's answer.
- `FantIngenOppforingAaEndre` matches `GENERIC IllegalStateException` with `matches: "matched zero rows"`, the shape `ZeroRowsMatchedException` keeps matching, plus a `GENERIC` handler on its own `FinnesIkkeException`.

Under the rule here the port answers as it does today: its `DATABASE` handlers already precede its `GENERIC` ones for every database error, since `UgyldigInput`, the only member carrying both, is last, and none of its exceptions wraps a database error in a non-jOOQ class. What the port shows is what this item and R1171 spare the next Graphitron 9 consumer: the union reordering, and the exception reclassification that keeps a `GENERIC` handler on a `DataAccessException` subclass from claiming ahead of the default.

## Architect consultation

`principles-architect` was consulted four times: at take-up, again when the plan's claims had been checked against the tree and the Graphitron 9.3.0 source, on the revision against the first consumer, and on the answer to review round 1 (both below). The first two accepted the runtime phase (a fact about the throw, not the schema) and the two-kind rule. What the plan took from them, where the revision has not replaced it:

- Emit both orders at generation time and choose at runtime through one selector, instead of a kind accessor and runtime filtering (now under "Other solutions").
- Read the order from the store, not from `ErrorType.handlers()`. The second consultation moved the key from the channel to the `@error` type, since the handlers are written there and two readers have no channel, and asked for the dispatch index to be computed in the read, for the model-against-store assertion to compare the ordered kind sequence, and for the plan to name what removes the assertion.
- The resolver and `message` agree with dispatch. The first consultation proposed recording the phase in `Claims`; the second preferred deriving it from the recorded root, a recorded phase being a copy of a derived value, on two conditions the plan meets: readers call the emitted selector, and the plan says how several roots settle the phase.
- One definition of the outer exception, used by the selector and the walk alike, in this item and not a separate one; the batched-field change goes in the `BREAKING-CHANGE:` footer.
- No stored relation for the order while it has one reader; say which reader R1168 is expected to be.
- Test with named fixtures per route and per divergence.

A third consultation, on the revision against the first consumer, judged the asymmetric rule sound if stated as one order rather than two phases (`DATABASE` scoped to jOOQ's errors, `GENERIC` the universal tail), with the default member a conditional middle step, and found it deletes the generic-first composite order, the `DATABASE` phase for other exceptions and the multi-`SQLException` walk. The plan takes from it: the one-order statement and the per-kind sequences (the grain R1168 plans to store), an enum classifier instead of a boolean, the two cut-offs of `lastOverrideIndex` over one order, the first-`SQLException` read, the default member as its own item with this item's goal narrowed to say so, and leaving Graphitron 9's registered-class gate out, since a `GENERIC`-only gate would be half of Graphitron 9's rule and the reading of the ruling here does not require it.

A fourth consultation, on 2026-11-12, covered the answer to review round 1's observations. It confirmed the counter-example against the read-side classifier and the `offeredToDatabase` shape, asked that the helper be named for the question it answers with the enum kept on the dispatch side, and checked that `matchesSource`'s arm without a root cannot disagree with dispatch under a true gate. It asked for the kind-sequence check to be total over the model's types whenever the relation was read from a store, with "no store" and "a store with no rows" kept apart. It turned down both ways of keeping the storeless emitter tests (a store-backed capture, a hand-built relation), since they assert emitted text, and asked for the "assert both emitted sequences" line under "Tests" to go; a hand-built relation stays right for the drift cases. And it asked for a `scenarios/` fact document with a reader test for the new read, not an addition to `undecided/error-handlers.graphqls`, with the dispatch index asserted in the test because it is computed in the read. The plan takes all of it.

The first two consultations also pointed out that the ruling's reach over `message:`, `path:` and unclaimed throws is not written down; divergences (a), (c) and (d) were ruled by Alf on 2026-11-11, and (b) is deferred to R1171. On where the ruling lives, it found no `docs/architecture/` page owning dispatch semantics and suggested the history page this item creates, with an optional line in `runtime-extension-points.adoc` if the ruling should bind beyond dispatch; that placement is for Alf and Filip.

## Reviewer findings

### Round 1: Spec → Ready (session_01FY5UvsLHL4QaoqPYs6hAmA, 2026-11-11)

Verdict: withhold. The goal reads cleanly on its own and is reachable: the account of Graphitron 9
holds against the v9.3.0 source, the account of today holds against the tree, and every class,
method, relation, test and doc passage the body names exists as named. Question two passes: the
plan extends `ErrorChannelRelation`, `ErrorMappings` and `ErrorRouter` and stands nothing beside
them. One finding on question one holds the gate.

**Finding 1 (question one: goal communication and viability). The goal promises "the same
`message:`", and the item leaves open whether that holds.** Divergences (c) and (d) are marked
"Needs a ruling", the goal section says both rulings are needed before the item is Ready, and
neither is recorded here or under `docs/`. Until they are, a reader cannot say what a consumer
sees in `message:` from a handler without `description:`, or in `path:` on an aliased field, and
those are two of the four things the goal is about.

The plan also covers only the recommended arm of each. "Tests" carries "(or the reverse, per the
ruling)" twice, and "Implementation" has nothing for the reverse: (c) reversed has
`ErrorTypeFetcherClassGenerator.messageMethod` read the recorded root's message where it reads
the source's today, and (d) reversed changes `pathMethod` and the standing
`GraphQLQueryTest.createFilmWithErrors_aliased_pathNamesTheAlias`. An implementer handed the item
as it stands meets a fork that is not theirs to settle.

Satisfied by: each ruling recorded under "Divergences kept" with who gave it and when, the two
markers and the conditional halves of the two tests removed, and, where a ruling goes against the
recommendation, the implementation and user-documentation text for that arm added. In the same
pass, say whether (a) and (b) were confirmed: "Architect consultation" calls them "proposed as
kept", and (b) is the widest of the four, since on Graphitron 9 every `IllegalArgumentException`
and every unclaimed outer `DataAccessException` went to consumer code.

_Response (2026-11-11, session_01WkW2JJGJqUgYuR5HA3Dm35, which revised the body in two commits;
noted here on 2026-11-12 by session_01FY5UvsLHL4QaoqPYs6hAmA)._ Alf ruled (a), (c) and (d) on
2026-11-11, each kept, and "Divergences" records each ruling with its reason; the goal section
names all three. The two "Needs a ruling" markers and the conditional halves of the two tests are
gone: the (c) and (d) tests under "Tests" name the direction ruled, and (d) names
`createFilmWithErrors_aliased_pathNamesTheAlias` as its fixture. No ruling went against a
recommendation, so no implementation arm was added. (b) is not kept: it is deferred to R1171
(`error-channel-default-member`), and the goal's second paragraph says what stays different until
that lands. The same revision changed the rule itself, against the first consumer's schemas:
dispatch is one order, the `DATABASE` handlers only for an outer `DataAccessException` and then
the `GENERIC` ones, so the round's reading of the body does not carry over and the next pass
reads "The rule" and "Implementation" afresh.

**Not holding the gate.** Three observations for the author to weigh.

* *Two corners of the compatibility claim are not in the divergence list (question one).* "The
  rule" says every throw Graphitron 9 put into a payload through a handler is claimed by the
  handler Graphitron 9 chose. Graphitron 9's `unwrapCompletionException` removes one
  `CompletionException` and never an `ExecutionException`, where `outer` removes any depth of
  both; a throw whose outer exception on Graphitron 9 was an `ExecutionException`, or a second
  `CompletionException`, around a `DataAccessException` went to the `GENERIC` list only and goes
  `DATABASE`-first here. And `DataAccessMatcher` read the first `SQLException` only, so a
  `DATABASE` handler declared earlier that matches a deeper `SQLException` claims here ahead of
  the handler Graphitron 9 chose; (a) says a handler that matched still matches, which is true
  and is not the same as it still winning. Both are narrow, and the history page is where they
  would be stated.

  _Response (2026-11-11 revision, sharpened 2026-11-12)._ The wrapper depth is divergence (e).
  The deeper `SQLException` is no longer a divergence: a `DATABASE` handler is offered the first
  `SQLException` only, as on Graphitron 9, with an execution test for the two-`SQLException`
  chain. The wider `matches:` read that remains is divergence (f). The sentence in "The rule"
  that every throw is claimed by the handler Graphitron 9 chose now excepts (e) and (f) by name.
* *What a run without a store reads (question two).* `EmitPlan.produce` hands the emitters
  `ErrorChannelRelation.empty()` when there is no store. The member check in
  `ErrorMappingsClassGenerator.generate` passes on it vacuously; the planned kind-sequence check
  walks the model's `@error` types, so on an empty relation every type with a dispatch handler
  differs. Both mojos construct the generator with a store, so this reaches test harnesses only
  (`TypeFetcherRenderTestSupport`, `SchemaShapeRenderTestSupport`), but the plan does not say
  what `empty()` means for the third component.

  _Response (2026-11-12, session_01FY5UvsLHL4QaoqPYs6hAmA)._ "A run without a store" under
  "Implementation" is rewritten. The 2026-11-11 revision answered this by narrowing the check to
  the types the relation holds, which let a store missing a type pass in a real run, and left
  the harness question for pickup. Now the check is total over the model's types whenever the
  relation was read from a store, and the no-store relation is told apart from a store with no
  rows. The harness question is answered from the tree: `GraphitronSchemaClassGeneratorTest`
  renders `@error` types with dispatch handlers and no store, asserting emitted text, and those
  cases are deleted, not carried onto a relation. "Tests" replaces "assert both emitted
  sequences" with a fact document and a reader test for the read, and execution fixtures for
  the dispatch index and the `lastOverrideIndex` cut-offs.
* *The read-side selector's fallback (question two).* As worded it answers
  `dataAccessFirst(source)` when roots are recorded and none is a `DataAccessException`. A
  `DataAccessException` claimed by a `GENERIC` handler through a wrapper of another class then
  reads `DATABASE`-first where dispatch went `GENERIC`-first. The answer is still the one
  dispatch gave, because a `DatabaseMapping` claims only an `SQLException` and a
  `DataAccessException` is not one; the selector's javadoc or its unit test is where to say so,
  or the fallback is taken only when no root is recorded.

  _Response (2026-11-12, session_01FY5UvsLHL4QaoqPYs6hAmA)._ The 2026-11-11 revision took the
  fallback only when no root is recorded. Under the new rule that was still not enough: a
  `GENERIC` handler can claim an `SQLException` deeper than the first, and the readers would
  re-check the `DATABASE` mappings against it because its root is a `DataAccessException`. The
  read-side classifier is replaced by `offeredToDatabase`, true only for the first
  `SQLException` below a recorded `DataAccessException` root, over one `firstSqlException`
  helper `claim` calls too. An execution fixture and the unit cases under "Tests" pin it, and
  the two shapes turned down are under "Other solutions".

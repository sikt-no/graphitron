---
id: R1167
title: "Error-channel dispatch routes every throw where Graphitron 9 routed it"
status: Spec
bucket: dx
priority: 3
theme: error-channel
depends-on: []
created: 2026-11-11
last-updated: 2026-11-11
---

# Error-channel dispatch routes every throw where Graphitron 9 routed it

## Goal

A schema that ran on Graphitron 9 puts each exception its services and mutations throw into the same `@error` member, with the same `message:`, on Graphitron 10: a database error raised through jOOQ is offered to the `DATABASE` handlers before any `GENERIC` one, and any other exception to the `GENERIC` handlers before any `DATABASE` one, each group in the order the schema declares it, as Graphitron 9 did. Today Graphitron 10 walks every handler in one declared order, so a broad `GENERIC` catch-all declared ahead of a `DATABASE` member silently takes the database errors that member used to get.

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

The requirement is Alf's ruling of 2026-11-11: error-channel dispatch is backwards compatible with Graphitron 9. This item reads that as "every throw Graphitron 9 put into a payload lands in the same `@error` member, through the same handler, on Graphitron 10". The places this item deliberately does not follow Graphitron 9 are listed under "Divergences kept", each with its reason, and two of them need a ruling before the item is Ready.

## Glossary

- *Channel*: what a producer field (a `@service` field, a payload-returning `@mutation`, or a payload-returning `@routine` write) catches into: the payload's errors field and the `@error` types it lists.
- *Handler*: one entry of an `@error` type's `handlers:` argument. Its *kind* is `GENERIC`, `DATABASE` or `VALIDATION`.
- *Dispatch*: choosing, for one thrown exception, the handler that claims it, and so the member it lands in.

## What Graphitron 9 did

Read from the v9.3.0 tag (`graphitron-common`, `no.sikt.graphql.exception`):

- `SchemaBasedErrorStrategy.handleException` unwraps one `CompletionException`, checks that the exception it then holds (the *outer* exception) is an instance of some class registered for the operation, and branches on the outer exception's own type: `ValidationViolationGraphQLException` and `IllegalArgumentException` go to methods the consumer wrote, a `DataAccessException` goes to the operation's `DATABASE` mappings only, and anything else goes to its `GENERIC` mappings only.
- `ExceptionToErrorMappingProviderGenerator` builds each of those two lists in union-member order, then `handlers:` order, keeping only handlers of the list's kind. `SchemaErrorMapper` takes the first mapping that matches; each matcher (`GenericExceptionMatcher`, `DataAccessMatcher`) walks the cause chain.
- So a `GENERIC` handler never saw an outer `DataAccessException`, and a `DATABASE` handler never saw any other outer exception. Within one kind, declared order decided, and a broad handler declared first hid a narrow one declared after it.
- `message:` was the claiming handler's `description:`, or else the outer exception's `getMessage()`; `path:` was `[fieldName]`.

## Today

`ErrorMappingsClassGenerator` emits one `Mapping[]` per channel, `concat(ByType.A, ByType.B, ...)`, with both kinds mixed in declared order. `ErrorRouter.claim` walks it mapping by mapping, each over the cause chain, and the first match wins. Two readers re-derive the claiming handler afterwards and must agree with dispatch: the union or interface type resolver (`GraphitronSchemaClassGenerator.buildErrorPolymorphicResolver`, through `ErrorRouter.matchesSource`) and `<ErrorType>Fetchers.message` (`ErrorTypeFetcherClassGenerator`), which walks the type's own handlers for the `description:`. All three take a type's handler order from `GraphitronType.ErrorType.handlers()`. Member order has two sources: the channel table reads it from the store (`ErrorChannelRelation.membersByOutcome`), the resolver from the model's `participants()`.

`claim` starts its walk at the exception the fetcher caught. On a batched producer that is a `CompletionException`: the `.exceptionally` arms after `thenApply` in `FrameEmitter`'s batched path and in `TypeFetcherGenerator.outcomeAsyncWrapTail` receive the wrapper `CompletableFuture` and DataLoader put around the throw (`GraphQLQueryTest.splitQueryConnection_negativeFirst_surfacesClientErrorThroughAsyncArm` pins the wrapping for the arm without a channel).

Three routes differ from Graphitron 9 as a result:

1. An outer `DataAccessException` goes to a `GENERIC` handler declared before the `DATABASE` handler that matches it, when the `GENERIC` class is `DataAccessException` or a supertype (`RuntimeException`, `Exception`, `Throwable`), or a class anywhere in its cause chain. The goal's example is this route.
2. Any other outer exception whose cause chain holds an `SQLException` goes to a `DATABASE` handler declared before the `GENERIC` handler that matches it. A service throwing its own `KontraktBruddException` around a `DataAccessException`, with a `GENERIC` member for `KontraktBruddException` declared after a bare `DATABASE` member, got the `GENERIC` member on Graphitron 9 and gets the `DATABASE` member today.
3. A throw that reaches `claim` inside a `CompletionException` is offered wrapper first, and `CompletionException` is a `RuntimeException`. A `GENERIC` handler on `RuntimeException` or wider therefore claims the wrapper itself: without a `description:`, `message:` reads the wrapper's text ("pkg.Cause: text"), and the type's extra fields are read off the wrapper, not off the author's exception. Graphitron 9 unwrapped before it matched anything. Only producers below the root are batched, and Graphitron 9 had none, so no Graphitron 9 schema sees this route change; it is fixed here because the rule below needs one definition of the outer exception, and a walk that starts somewhere else is a second one.

The same mixing reaches `message:` within one type: a type with `GENERIC RuntimeException` (description A) declared before `DATABASE sqlState 22003` (description B) answers A for an overflow today and answered B on Graphitron 9.

## The rule

The *outer* exception of a throw is the exception the fetcher caught, past any `java.util.concurrent.CompletionException` or `ExecutionException` wrapping it.

- If the outer exception is an `org.jooq.exception.DataAccessException`, the channel's `DATABASE` handlers are tried first, then its `GENERIC` handlers.
- Otherwise the `GENERIC` handlers are tried first, then the `DATABASE` handlers.
- Within each kind, declared order: the channel's `@error` types in member order, then each type's `handlers:` in array order. Each handler is tried against the cause chain from the outer exception down. The wrappers above it are never offered to a handler, and a `DATABASE` handler's `matches:` reads the messages from the outer exception down to the `SQLException` it claims, not the wrappers'.
- `VALIDATION` runs before the service and never enters dispatch; nothing about it changes.

Every throw that Graphitron 9 put into a payload through a handler is claimed by the handler Graphitron 9 chose: Graphitron 9 consulted only the first kind, in the same order, and the first kind is tried first here. The second kind only ever claims a throw Graphitron 9 did not put into a payload through a handler (see "Divergences kept").

`message:` follows the same rule at its own grain: it reads the `description:` of the first handler of the matched type, in that type's own order for the throw's outer exception, that matches the claimed exception.

## Divergences kept

Each is a place where a Graphitron 9 schema can see a different answer, kept for the reason given. The implementation records each in a new history page (see "User documentation"), and each has a test asserting the Graphitron 10 direction.

- **(a) Wrapped exceptions are claimed.** Graphitron 9 checked only the outer exception's class before matching, so a `FooException` inside an unregistered wrapper became a top-level error even with a `GENERIC` handler for `FooException`. Graphitron 10 claims it through the cause chain. A client that saw a redacted top-level error sees a typed payload error. The `DATABASE` side is wider in the same way: Graphitron 9's `DataAccessMatcher` read `sqlState` and `code` off the first `SQLException` under the `DataAccessException` and tested `matches:` against the `DataAccessException`'s own message, where Graphitron 10 offers every `SQLException` in the chain and reads every message from the outer exception down to it. A handler that matched on Graphitron 9 still matches. Kept: the handler was written to claim that exception.
- **(b) A throw no handler claims is redacted at the top level.** Graphitron 9 called consumer-written code for an unclaimed outer `DataAccessException` (a default error in the payload), for every `IllegalArgumentException`, and for validation exceptions. Graphitron 10 has no such hook; a catch-all member declared last reproduces the in-payload shape, as `migrating-from-legacy.adoc` already says. For the same reason an `IllegalArgumentException` that a `GENERIC` handler matches is claimed by that handler, where Graphitron 9 handed every one to the consumer's method whatever the handlers said. Kept: the hook was consumer code, and the second phase plus a catch-all covers it.
- **(c) `message:` without `description:` reads the claimed exception.** Graphitron 9 read the outer exception's `getMessage()`. For a `DATABASE` handler that is jOOQ's message, which carries the SQL statement; Graphitron 10 reads the driver's `SQLException`. For a `GENERIC` handler that matched a cause, Graphitron 9 read the wrapper's message. **Needs a ruling.** The recommendation is to keep Graphitron 10's behaviour, since reproducing Graphitron 9 sends SQL text to clients and `FetcherErrorResponse.matchedMessage` can still rewrite the text.
- **(d) `path:` is the response path.** Graphitron 9 answered `[fieldName]`; Graphitron 10 answers the response path, which uses the field's alias and carries list indexes. The two agree for an unaliased root field, the only place Graphitron 9 had a channel. **Needs a ruling** on the aliased case; the recommendation is to keep the response path, which is what GraphQL clients address.

Graphitron 10 also gives a channel to fields below the root, which Graphitron 9 did not have at all; that is additive and not a divergence.

## Relation to the shadowed-handler diagnostic

R1165 (`error-handlers-ordered-by-narrowness`), which sorted handlers narrowest-first, was discarded on 2026-11-11 on Filip's ruling: it answered differently from Graphitron 9 for any schema declaring a broad handler before a narrower one of the same kind. What replaces it is R1168 (`shadowed-error-handler-is-reported`): declared order is kept within each kind, as here, and a handler that an earlier handler of the same phase shadows is reported, as a warning or an error. R1168 depends on this item, since the order it judges "earlier" by is the one this item defines; this item needs nothing from it.

## Implementation

**The order, from the store.** Both orders are fixed when the generator runs; only the choice between them depends on the throw. They are an `ORDER BY` over facts the store already holds, so no new relation is needed.

- *Keyed by `@error` type, not by channel.* Handlers are written on the type, and two of the three readers exist where no channel does: `<ErrorType>Fetchers.message` and the `ByType` constant are emitted for every `@error` type, and the type resolver for every union or interface whose members are all `@error` types, whether or not an errors field lists them. So `ErrorChannelRelation` gains a third component beside its two channel-keyed ones, keyed by `@error` type and reaching every `@error` type in the graph, channel or no channel: per type, its dispatch-capable handlers in array order, each with its kind and its *dispatch index*. The read joins `graphitron_ast_error_handler_entry` to the type through the type's `@error` application in `graphql_directive_application`, the join `ServiceEmission.validating` and the LSP's `TypeInformation` already make. The two existing components and the member-grain read are unchanged, and the record's javadoc says it now holds the error family's order at both grains.
- *The dispatch index is computed in the read.* It is the handler's ordinal among the type's handlers that are not `VALIDATION`, from 0, which is the index of its `Mapping` in the type's `ByType` array. The store's `position` counts every element of the list, `VALIDATION` included, so the read ranks, in SQL or in the record's constructor, and no emitter translates one into the other.
- *One function gives every reader its order.* Given a member list and a phase, it answers the ordered sequence of (type, dispatch index): for database-first, every `DATABASE` handler of the members in member order then array order, followed by every `GENERIC` handler in the same order; for generic-first, the reverse. The channel tables call it with the channel's members (`ErrorChannelRelation.membersByOutcome`), the resolver with the container's participants, and `message` with the one type. No emitter tests a handler's kind itself, and none takes order from `ErrorType.handlers()`.
- *Mapping content stays on the model in this item.* `buildMappingEntry` still reads class name, `sqlState`, `code`, `matches` and `description` from `ErrorType.handlers()`, so for the length of this item one list has two sources. `ErrorMappingsClassGenerator.generate` holds them together: for each `@error` type it compares the ordered kind sequence of the model's dispatch-capable handlers with the store's rows, and throws the "store and the classified schema have drifted" `IllegalStateException` it already throws for members when they differ. The assertion goes when the content moves to the store too; `graphitron_ast_error_generic_handler_entry` and `graphitron_ast_error_database_handler_entry` already hold all of it. That move is not this item.
- *The resolver's member order stays where it is.* It reads `participants()` and the channel table reads the store, as today; for a union both are declaration order. This item does not unify them.

R1168 is the second reader of handler order, and it was weighed in choosing no relation here. Its rule judges within one kind, so it needs the declared order within a container and never the phase, and it is planned as a store rule: it stores that order as a relation of its own and repoints `ErrorChannelRelation`'s read at it. Until then the read above is the order's only reader and stays a query. The phase stays out of the store either way; it is a fact about a throw.

**Emitted router** (`ErrorRouterClassGenerator`, `ErrorMappingsClassGenerator`):

- Each channel gets its two tables in final order, database-first and generic-first, each written as references to the `ByType` elements (`ByType.X[i]`) in the order the function above gives, so a `Mapping` instance is shared, not copied. A holder of the pair (a nested `ErrorRouter` record, say) under the channel's existing constant name keeps every catch arm's spelling; two constants per channel is the alternative, and the choice is the implementer's. The `ByType` arrays stay as they are, one per type in array order. `concat` goes.
- `ErrorRouter` gains `outer(Throwable thrown)`: the first link of the chain that is neither a `CompletionException` nor an `ExecutionException`, or `thrown` itself when the chain is nothing but those. It is the one definition of the outer exception.
- `ErrorRouter` gains one selector, `dataAccessFirst(Throwable outer)`: `outer instanceof org.jooq.exception.DataAccessException`. It is the only place the phase is decided.
- `claim` takes the channel's pair, computes `outer(thrown)` once, walks the table the selector picks, and uses the outer exception wherever `thrown` stands for the root of the match today: the start of the cause-chain walk, the first argument of `Mapping.match` (which `messageOnPathContains` reads messages down from), the root `recordClaim` records, and `recordClaim`'s "the claimed link is the throw itself" test. `thrown` is still what `markFieldFailed`, the mount and outbox declines and `FetcherErrorLog.matched` receive. Its javadoc, the `Mapping` javadoc and the mappings class javadoc stop saying "source-declaration order" and state the rule.
- `Claims` and what `recordClaim` records are unchanged: a root is recorded when the claimed link is not the outer exception, and with no hook configured a claim of the outer exception itself still allocates no record. The phase is not recorded. It is a function of the outer exception, and the outer exception is the recorded root when there is one and the source itself when there is none, so a recorded phase would be a copy of a value the record already determines.
- `ErrorRouter` gains the read-side selector, `dataAccessFirst(Throwable source, GraphQLContext context)`: true when `dataAccessFirst` holds for any root recorded for the source, and otherwise `dataAccessFirst(source)`. "Any root" is the same union `matchesSource` takes over roots, and has the ambiguity `claim`'s javadoc already states for a source claimed by two fetchers through different wrappers. With a null context there are no roots, and the answer can differ from dispatch only when the source is a cause of the outer exception and exactly one of the two is a `DataAccessException`; the javadoc says so.
- `matchesSource` keeps its contract: it re-checks one mapping. Which sequence of mappings a reader walks is the read-side selector's answer.

**Readers:**

- `GraphitronSchemaClassGenerator.buildErrorPolymorphicResolver` emits two `matchesSource` sequences, one per phase, each in the order the function gives for the container's participants, and picks between them with the read-side selector.
- `ErrorTypeFetcherClassGenerator.messageMethod` does the same over the type's own two orders. `lastOverrideIndex` is per order: the cut-off is the last handler *in that order* declaring a `description:`, since the handlers after it in one order are not the handlers after it in the other.
- The emitted fetchers' catch arms pass the channel's pair to `claim`: `ErrorDispatchFragments.outcomeArm` (reached from `ChannelCatchArmEmitter`, `RoutineWriteEmitter` and `TypeFetcherGenerator.outcomeAsyncWrapTail`) and `FrameEmitter.failure`. The hand-written test stubs `graphitron/src/test/java/no/sikt/graphitron/emit/stub/ErrorRouter.java` and `ErrorMappings.java` follow `claim`'s parameter type.

Both the selector and the readers call the emitted methods; none repeats the `instanceof` test.

**Model.** No classifier change. `ChannelRuleChecks` rule 8 (identical criteria) stays: identical handlers have the same kind and so stay in one phase, where the second is still unreachable.

## Tests

The tests follow named fixtures, one per route: each reproduced Graphitron 9 route asserts agreement, each kept divergence asserts the Graphitron 10 direction, and nothing claims general agreement with Graphitron 9.

Execution tier, on the sakila example (`GraphQLQueryTest` or a sibling; new SDL in `schema.graphqls`, any new service in `graphitron-sakila-service`):

- The goal's union, with the catch-all member first: an overflowing rental rate lands in `FilmRentalRateOverflow` with its `description:`. The same union with the members reversed answers the same.
- A service throwing its own exception around a `DataAccessException`, with a bare `DATABASE` member declared before a `GENERIC` member for the service's exception: the `GENERIC` member wins.
- One `@error` type with `GENERIC RuntimeException` (description A) before `DATABASE sqlState 22003` (description B): an overflow answers B, and `__typename` agrees.
- An outer `DataAccessException` that no `DATABASE` handler matches, with a `GENERIC` catch-all on `DataAccessException` last: lands in the catch-all (divergence b, in-payload shape reproduced).
- Divergence (a): a `GENERIC` handler for an exception thrown inside an unregistered wrapper claims it.
- Divergence (c): a `DATABASE` handler without `description:` answers the `SQLException`'s message, not jOOQ's (or the reverse, per the ruling).
- Divergence (d): an aliased producer field answers the alias in `path:` (or the field name, per the ruling).
- A batched producer below the root (the fixture behind `GraphQLQueryTest`'s child-payload `path:` case, or a sibling of it), whose throw reaches `claim` inside a `CompletionException`: a `DataAccessException` takes the database-first order, and a `GENERIC` handler on `RuntimeException` without `description:` answers the author's exception's own message and extra fields, not the wrapper's.

Pipeline tier: `ErrorMappingsClassGeneratorTest` and the resolver and message emitter tests assert both emitted orders for a mixed-kind union, including a type that mixes `VALIDATION` with dispatch handlers (the dispatch index skips it) and a type with a `description:` on a different last handler in each order (`lastOverrideIndex` per order). One test feeds `ErrorMappingsClassGenerator.generate` a store and a model that disagree on a type's kind sequence and asserts the drift `IllegalStateException`.

Unit tier, on the compiled `ErrorRouter` beside `ErrorRouterDatabaseMatchesTest`: `outer` over nested wrappers and over a chain of nothing but wrappers; the read-side selector with a recorded root, with none, and with a null context.

## User documentation (first-client check)

`docs/manual/reference/directives/error.adoc`, Constraints, replacing the bullets "Declaration order decides", "Nothing sorts by specificity" and the cross-variant sentence of the duplicate-criteria bullet:

> * A database error raised through jOOQ (a `DataAccessException`) is offered to the channel's `DATABASE` handlers first, and only then to its `GENERIC` handlers. Any other exception is offered to the `GENERIC` handlers first, then the `DATABASE` handlers. A `GENERIC` catch-all on `RuntimeException` therefore does not take a database error from a `DATABASE` member, wherever either is declared.
> * Among handlers of one kind, declaration order decides, and the first match wins: the channel's `@error` types in declaration order, then each type's `handlers:` in array order. Declare the narrow handler before the broad one; a broad one declared first leaves the narrow one unreachable.
> * Two handlers of the same kind with identical criteria are rejected: the second would be unreachable.
> * Matching starts at the exception your code threw. The `CompletionException` or `ExecutionException` a future wraps it in is never offered to a handler, so a catch-all on `RuntimeException` claims the exception inside, and `message:` reads that one.

The `handlers` parameter row says "The first entry whose criteria match, in the order described under Constraints, wins". `docs/manual/how-to/error-channel.adoc`: the "Handler dispatch: source order, cause-chain unwrap" section is retitled and rewritten to the rule with the goal's example, the loop it prints starts at the outer exception and names the two tables, its "orthogonal fields" sentence goes, the `message:` paragraph says "in the type's order for that exception", and the "Source order is significant" pitfall becomes "Order matters within a kind".

`docs/manual/how-to/migrating-from-legacy.adoc`, the `@error` bullet of "Things that didn't change", gains: "Dispatch keeps Graphitron 9's order: a `DataAccessException` goes to `DATABASE` handlers before `GENERIC` ones, anything else to `GENERIC` handlers first, each in declaration order." It links the history page below for the kept divergences.

A new history page, `docs/history/error-dispatch-divergence.adoc`, sibling of `argument-resolution-divergence.adoc` and listed in `docs/history/index.adoc`: the compatibility ruling with its date, Graphitron 9's dispatch as described above, and divergences (a) to (d) with their reasons as ruled.

## Roadmap entries

- Changelog: routing changes for a Graphitron 10 schema that mixes kinds with a broad handler of the other kind declared first. The commit is `feat(R1167)!` with a `BREAKING-CHANGE:` footer: "A `DataAccessException` now reaches `DATABASE` handlers before `GENERIC` ones, and any other exception reaches `GENERIC` handlers first; a schema that relied on a `GENERIC` handler declared first taking database errors declares a `DATABASE` handler for them instead. On a batched field a handler now claims the exception inside the `CompletionException`, never the wrapper, so `message:` and the type's extra fields read the exception the service threw."

## Retired vocabulary

- "source order", "source-declaration order" and "Declaration order decides" as the description of dispatch across kinds: emitted javadoc on `ErrorRouter.claim`, `Mapping` and the mappings class; the javadoc of `ErrorDispatchFragments.outcomeArm`; `error.adoc`; `error-channel.adoc`.
- "the runtime's source-order findFirst on MAPPINGS": the rule 8 rejection message and javadoc in `ChannelRuleChecks.checkDuplicateMatchCriteria`, and the copy `FieldBuilder` keeps for the DML path. The rule stays; its message says the second handler is unreachable behind the first of the same kind.
- "inspect orthogonal fields" and "reach different exceptions" (the claim that `GENERIC` and `DATABASE` handlers cannot overlap): `error.adoc`, `error-channel.adoc`, the rule 8 javadoc.
- The `claim` loop printed in `error-channel.adoc` with `thrown` as the start of the walk.
- The `concat` helper of the emitted mappings class (`ErrorMappingsClassGenerator.CONCAT_METHOD`).

## Other solutions we've considered

- **Narrowest first (the discarded R1165).** Ends the ordering chore, but answers differently from Graphitron 9 wherever a broad handler of one kind was declared first, which the ruling excludes.
- **`DATABASE` always before `GENERIC`, whatever the throw.** One emitted order, no runtime selector. It sends the second route under "Today" (a service's own exception around a database error) to the `DATABASE` member, as today, against Graphitron 9.
- **Graphitron 9's single kind, no second phase.** Exact Graphitron 9, but a `DataAccessException` no `DATABASE` handler matches could then never reach a `GENERIC` catch-all, and the in-payload shape Graphitron 9 produced through consumer code (divergence b) would have no replacement.
- **Filter by kind at runtime.** `Mapping` gains a kind accessor and every reader makes two passes over the mixed table. Smaller to emit, but three emitted readers evaluate the same kind predicate and can drift; the two orders are generation-time facts, so they are emitted once.
- **A stored match-order relation.** The order is an `ORDER BY` over handler kind and position already in the store; a relation for it holds nothing a query does not, while this item's read is its only reader. R1168 brings the second reader and the relation with it, as said under "Implementation".
- **Record the phase in `Claims`.** Dispatch writes its decision and the readers read it back. The record already holds what the decision is a function of (the root), so the phase would be a second copy that can disagree with the first, and recording it for every claim would end the shortcut that allocates no record for a claim of the outer exception with no hook configured.
- **Leave handler order on `ErrorType.handlers()`.** It is already ordered and already tagged by kind, and nothing new would be read from the store. It keeps three emitters each testing a handler's kind, and adds a read of the classified model where the codebase is moving reads to the store.
- **Unwrap for the phase only, and keep walking from the caught exception.** Smaller, and nothing changes for a batched field. It leaves two definitions of where a throw starts, and the catch-all on a batched field keeps claiming the `CompletionException`.

## Architect consultation

`principles-architect` was consulted twice: at take-up, and again when the plan's claims had been checked against the tree and the Graphitron 9.3.0 source. It accepted the runtime phase (a fact about the throw, not the schema) and the two-kind rule. What the plan takes from it:

- Emit both orders at generation time and choose at runtime through one selector, instead of a kind accessor and runtime filtering (now under "Other solutions").
- Read the order from the store, not from `ErrorType.handlers()`. The second consultation moved the key from the channel to the `@error` type, since the handlers are written there and two readers have no channel, and asked for the dispatch index to be computed in the read, for the model-against-store assertion to compare the ordered kind sequence, and for the plan to name what removes the assertion.
- The resolver and `message` agree with dispatch. The first consultation proposed recording the phase in `Claims`; the second preferred deriving it from the recorded root, a recorded phase being a copy of a derived value, on two conditions the plan meets: readers call the emitted selector, and the plan says how several roots settle the phase.
- One definition of the outer exception, used by the selector and the walk alike, in this item and not a separate one; the batched-field change goes in the `BREAKING-CHANGE:` footer.
- No stored relation for the order while it has one reader; say which reader R1168 is expected to be.
- Test with named fixtures per route and per divergence.

It also pointed out that the ruling's reach over `message:`, `path:` and unclaimed throws is not written down; divergences (c) and (d) are marked for a ruling, and (a) and (b) are proposed as kept. On where the ruling lives, it found no `docs/architecture/` page owning dispatch semantics and suggested the history page this item creates, with an optional line in `runtime-extension-points.adoc` if the ruling should bind beyond dispatch; that placement is for Alf and Filip.

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

`ErrorMappingsClassGenerator` emits one `Mapping[]` per channel, `concat(ByType.A, ByType.B, ...)`, with both kinds mixed in declared order. `ErrorRouter.claim` walks it mapping by mapping, each over the cause chain, and the first match wins. Two readers re-derive the claiming handler afterwards and must agree with dispatch: the union or interface type resolver (`GraphitronSchemaClassGenerator.buildErrorPolymorphicResolver`, through `ErrorRouter.matchesSource`) and `<ErrorType>Fetchers.message` (`ErrorTypeFetcherClassGenerator`), which walks the type's own handlers for the `description:`. All three take their order from `GraphitronType.ErrorType.handlers()`.

Two routes differ from Graphitron 9 as a result:

1. An outer `DataAccessException` goes to a `GENERIC` handler declared before the `DATABASE` handler that matches it, when the `GENERIC` class is `DataAccessException` or a supertype (`RuntimeException`, `Exception`, `Throwable`), or a class anywhere in its cause chain. The goal's example is this route.
2. Any other outer exception whose cause chain holds an `SQLException` goes to a `DATABASE` handler declared before the `GENERIC` handler that matches it. A service throwing its own `KontraktBruddException` around a `DataAccessException`, with a `GENERIC` member for `KontraktBruddException` declared after a bare `DATABASE` member, got the `GENERIC` member on Graphitron 9 and gets the `DATABASE` member today.

The same mixing reaches `message:` within one type: a type with `GENERIC RuntimeException` (description A) declared before `DATABASE sqlState 22003` (description B) answers A for an overflow today and answered B on Graphitron 9.

## The rule

The *outer* exception of a throw is the exception the fetcher caught, past any `java.util.concurrent.CompletionException` or `ExecutionException` wrapping it.

- If the outer exception is an `org.jooq.exception.DataAccessException`, the channel's `DATABASE` handlers are tried first, then its `GENERIC` handlers.
- Otherwise the `GENERIC` handlers are tried first, then the `DATABASE` handlers.
- Within each kind, declared order: the channel's `@error` types in member order, then each type's `handlers:` in array order. Each handler is tried against the whole cause chain, as today.
- `VALIDATION` runs before the service and never enters dispatch; nothing about it changes.

Every throw that Graphitron 9 put into a payload through a handler is claimed by the handler Graphitron 9 chose: Graphitron 9 consulted only the first kind, in the same order, and the first kind is tried first here. The second kind only ever claims a throw Graphitron 9 did not put into a payload through a handler (see "Divergences kept").

`message:` follows the same rule at its own grain: it reads the `description:` of the first handler of the matched type, in that type's own order for the throw's outer exception, that matches the claimed exception.

## Divergences kept

Each is a place where a Graphitron 9 schema can see a different answer, kept for the reason given. The implementation records each in a new history page (see "User documentation"), and each has a test asserting the Graphitron 10 direction.

- **(a) Wrapped exceptions are claimed.** Graphitron 9 checked only the outer exception's class before matching, so a `FooException` inside an unregistered wrapper became a top-level error even with a `GENERIC` handler for `FooException`. Graphitron 10 claims it through the cause chain. A client that saw a redacted top-level error sees a typed payload error. Kept: the handler was written to claim that exception.
- **(b) A throw no handler claims is redacted at the top level.** Graphitron 9 called consumer-written code for an unclaimed outer `DataAccessException` (a default error in the payload), for every `IllegalArgumentException`, and for validation exceptions. Graphitron 10 has no such hook; a catch-all member declared last reproduces the in-payload shape, as `migrating-from-legacy.adoc` already says. For the same reason an `IllegalArgumentException` that a `GENERIC` handler matches is claimed by that handler, where Graphitron 9 handed every one to the consumer's method whatever the handlers said. Kept: the hook was consumer code, and the second phase plus a catch-all covers it.
- **(c) `message:` without `description:` reads the claimed exception.** Graphitron 9 read the outer exception's `getMessage()`. For a `DATABASE` handler that is jOOQ's message, which carries the SQL statement; Graphitron 10 reads the driver's `SQLException`. For a `GENERIC` handler that matched a cause, Graphitron 9 read the wrapper's message. **Needs a ruling.** The recommendation is to keep Graphitron 10's behaviour, since reproducing Graphitron 9 sends SQL text to clients and `FetcherErrorResponse.matchedMessage` can still rewrite the text.
- **(d) `path:` is the response path.** Graphitron 9 answered `[fieldName]`; Graphitron 10 answers the response path, which uses the field's alias and carries list indexes. The two agree for an unaliased root field, the only place Graphitron 9 had a channel. **Needs a ruling** on the aliased case; the recommendation is to keep the response path, which is what GraphQL clients address.

Graphitron 10 also gives a channel to fields below the root, which Graphitron 9 did not have at all; that is additive and not a divergence.

## Relation to the shadowed-handler diagnostic

R1165 (`error-handlers-ordered-by-narrowness`), which sorted handlers narrowest-first, was discarded on 2026-11-11 on Filip's ruling: it answered differently from Graphitron 9 for any schema declaring a broad handler before a narrower one of the same kind. What replaces it is R1168 (`shadowed-error-handler-is-reported`): declared order is kept within each kind, as here, and a handler that an earlier handler of the same phase shadows is reported, as a warning or an error. R1168 depends on this item, since the order it judges "earlier" by is the one this item defines; this item needs nothing from it.

## Implementation

**The order, from the store.** Both orders of a channel are fixed when the generator runs; only the choice between them depends on the throw. They are an `ORDER BY` over facts the store already holds, so no new relation is needed: extend `ErrorChannelRelation` from member grain to handler grain, reading each member's dispatch-capable handlers (`graphitron_ast_error_handler_entry.handler` and `.position`, joined to its `@error` type through the directive application) with the member position it already reads. `ErrorChannelRelation` exposes, per channel and per `@error` type, the handler sequence in declared order with each handler's kind; a reader derives "DATABASE first" and "GENERIC first" from it. No emitter takes order from `ErrorType.handlers()` any longer.

**Emitted router** (`ErrorRouterClassGenerator`, `ErrorMappingsClassGenerator`):

- Each channel gets two constants in final order, `<CHANNEL>_DATA_ACCESS_FIRST` and `<CHANNEL>_GENERIC_FIRST` (names are the implementer's), and each `@error` type gets the same pair for `message`. Each is written as references to the `ByType` elements, so a `Mapping` instance is shared, not copied. `concat` goes.
- `ErrorRouter` gains one selector, `dataAccessFirst(Throwable thrown)`: unwrap `CompletionException` and `ExecutionException`, then `instanceof org.jooq.exception.DataAccessException`. It is the only place the phase is decided.
- `claim` keeps its signature shape but receives both tables (or a holder of the pair) and walks the one the selector picks. Its javadoc, the `Mapping` javadoc and the mappings class javadoc stop saying "source-declaration order" and state the rule.
- `recordClaim` records the phase in `Claims` beside the roots for every claim, including when the claimed link is the thrown exception itself, so readers on the request see the order dispatch used. With a null environment or context nothing is recorded, as today, and readers fall back to `dataAccessFirst` applied to the source; the javadoc says that this fallback can differ from dispatch only for a source claimed below an outer `DataAccessException` with no context.
- `matchesSource` is unchanged.

**Readers:**

- `GraphitronSchemaClassGenerator.buildErrorPolymorphicResolver` emits two `matchesSource` sequences, one per phase, and picks between them through the recorded phase (or the fallback).
- `ErrorTypeFetcherClassGenerator` (`message`, `lastOverrideIndex`) does the same over the type's own pair.
- The emitted fetchers' catch arms (`ErrorDispatchFragments.outcomeArm`, `FrameEmitter.failure`) pass the channel's pair to `claim`.

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

Pipeline tier: `ErrorMappingsClassGeneratorTest` and the resolver and message emitter tests assert both emitted orders for a mixed-kind union, and that the three readers use the same pair for one channel. If fetchers can hand `claim` a `CompletionException` (check the async producer paths at pickup), a test covers a wrapped `DataAccessException` taking the database-first order; otherwise the selector's unwrap is documented as defensive.

## User documentation (first-client check)

`docs/manual/reference/directives/error.adoc`, Constraints, replacing the bullets "Declaration order decides", "Nothing sorts by specificity" and the cross-variant sentence of the duplicate-criteria bullet:

> * A database error raised through jOOQ (a `DataAccessException`) is offered to the channel's `DATABASE` handlers first, and only then to its `GENERIC` handlers. Any other exception is offered to the `GENERIC` handlers first, then the `DATABASE` handlers. A `GENERIC` catch-all on `RuntimeException` therefore does not take a database error from a `DATABASE` member, wherever either is declared.
> * Among handlers of one kind, declaration order decides, and the first match wins: the channel's `@error` types in declaration order, then each type's `handlers:` in array order. Declare the narrow handler before the broad one; a broad one declared first leaves the narrow one unreachable.
> * Two handlers of the same kind with identical criteria are rejected: the second would be unreachable.

The `handlers` parameter row says "The first entry whose criteria match, in the order described under Constraints, wins". `docs/manual/how-to/error-channel.adoc`: the "Handler dispatch: source order, cause-chain unwrap" section is retitled and rewritten to the rule with the goal's example, its "orthogonal fields" sentence goes, the `message:` paragraph says "in the type's order for that exception", and the "Source order is significant" pitfall becomes "Order matters within a kind".

`docs/manual/how-to/migrating-from-legacy.adoc`, the `@error` bullet of "Things that didn't change", gains: "Dispatch keeps Graphitron 9's order: a `DataAccessException` goes to `DATABASE` handlers before `GENERIC` ones, anything else to `GENERIC` handlers first, each in declaration order." It links the history page below for the kept divergences.

A new history page, `docs/history/error-dispatch-divergence.adoc`, sibling of `argument-resolution-divergence.adoc` and listed in `docs/history/index.adoc`: the compatibility ruling with its date, Graphitron 9's dispatch as described above, and divergences (a) to (d) with their reasons as ruled.

## Roadmap entries

- Changelog: routing changes for a Graphitron 10 schema that mixes kinds with a broad handler of the other kind declared first. The commit is `feat(R1167)!` with a `BREAKING-CHANGE:` footer: "A `DataAccessException` now reaches `DATABASE` handlers before `GENERIC` ones, and any other exception reaches `GENERIC` handlers first; a schema that relied on a `GENERIC` handler declared first taking database errors declares a `DATABASE` handler for them instead."

## Retired vocabulary

- "source order", "source-declaration order" and "Declaration order decides" as the description of dispatch across kinds: emitted javadoc on `ErrorRouter.claim`, `Mapping` and the mappings class; `error.adoc`; `error-channel.adoc`.
- "inspect orthogonal fields" (the claim that `GENERIC` and `DATABASE` handlers cannot overlap).
- The `concat` helper of the emitted mappings class (`ErrorMappingsClassGenerator.CONCAT_METHOD`).

## Other solutions we've considered

- **Narrowest first (the discarded R1165).** Ends the ordering chore, but answers differently from Graphitron 9 wherever a broad handler of one kind was declared first, which the ruling excludes.
- **`DATABASE` always before `GENERIC`, whatever the throw.** One emitted order, no runtime selector. It sends the second route under "Today" (a service's own exception around a database error) to the `DATABASE` member, as today, against Graphitron 9.
- **Graphitron 9's single kind, no second phase.** Exact Graphitron 9, but a `DataAccessException` no `DATABASE` handler matches could then never reach a `GENERIC` catch-all, and the in-payload shape Graphitron 9 produced through consumer code (divergence b) would have no replacement.
- **Filter by kind at runtime.** `Mapping` gains a kind accessor and every reader makes two passes over the mixed table. Smaller to emit, but three emitted readers evaluate the same kind predicate and can drift; the two orders are generation-time facts, so they are emitted once.
- **A stored match-order relation.** The order is an `ORDER BY` over handler kind and position already in the store; a relation for it holds nothing a query does not.

## Architect consultation

`principles-architect` was consulted at take-up. It accepted the runtime phase (a fact about the throw, not the schema) and the two-kind rule. The plan follows its four other points:

- Emit both orders at generation time and choose at runtime through one selector, instead of a kind accessor and runtime filtering (now under "Other solutions").
- Read the order from the store through `ErrorChannelRelation` at handler grain, not from `ErrorType.handlers()`.
- Record the phase in `Claims` so the resolver and `message` agree with dispatch.
- Test with named fixtures per route and per divergence.

It also pointed out that the ruling's reach over `message:`, `path:` and unclaimed throws is not written down; divergences (c) and (d) are marked for a ruling, and (a) and (b) are proposed as kept. On where the ruling lives, it found no `docs/architecture/` page owning dispatch semantics and suggested the history page this item creates, with an optional line in `runtime-extension-points.adoc` if the ruling should bind beyond dispatch; that placement is for Alf and Filip.

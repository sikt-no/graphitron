---
id: R1087
title: "Graphitron 9 error fallback cannot be reproduced: a catch-all handler is never logged and its message cannot be computed"
status: In Progress
bucket: enhancement
priority: 3
theme: error-channel
depends-on: []
created: 2026-10-22
last-updated: 2026-10-23
---

# Graphitron 9 error fallback cannot be reproduced: a catch-all handler is never logged and its message cannot be computed

## Goal

A consumer moving from Graphitron 9 should be able to keep the error responses its clients already parse when no specific `@error` handler matches. Today it cannot, for three reasons. A catch-all handler (a `GENERIC` handler on a broad class, last in the union) is never logged, so it would hide outages. Its `message:` is either a fixed `description:` or the exception's own text, so it cannot be computed from the exception. And the client's reference is always a random UUID. After this item a consumer can declare a catch-all member whose matches still reach `FetcherErrorLog`. It can compute the message of a matched or redacted error from the exception and the reference. It can also supply the reference itself, for example the OpenTelemetry trace id. A consumer that configures nothing sees today's behaviour.

## What Graphitron 9 consumers depend on

Reported by sis, whose consumer-facing teams require the Graphitron 9 shapes to survive the upgrade because client integrations parse them. On Graphitron 9 sis ran a `SchemaBasedErrorStrategy` subclass and a `TopLevelErrorHandler` subclass:

- **In a payload.** On a mutation whose errors union has `UgyldigInput`, an exception no handler matched came back as an `UgyldigInput` entry when it was an `IllegalArgumentException`, or any jOOQ `DataAccessException` on a mutation. The message was computed: a text chosen by SQL-state class ("Du har ikke tilgang til å utføre denne operasjonen" for ORA-28115, "Fikk ikke kontakt med databasen." for a connection failure), the FS rule text for ORA-20xxx with the national id number masked, then `" Referanse: " + <OTel trace id>`. Every such error was logged, at WARN for rejected client data and ERROR otherwise, with the trace id.
- **At the top level.** Everything else answered "An exception occurred. The error has been logged with id <uuid>: <hint>", where the hint was the same SQL-state text for a `DataAccessException`.

On Graphitron 10 the payload case becomes a `null` field plus "An error occurred. Reference: <uuid>.", and the hint is gone from the top-level case. sis tried a `GENERIC` handler on `DataAccessException` last in each union and dropped it: a matched error is never logged, so a database outage on a mutation would vanish from the log.

Graphitron 9 also logged each top-level exception once, however many fields a batch failure reached, and kept the SQL out of the ERROR line. That half is R920's goal and is not repeated here.

## The consumer's view (first-client check)

This section drafts the manual text the item ships. It goes into `docs/manual/how-to/error-channel.adoc`, replacing the closing paragraph of § `path:`, `message:` field fetchers that recommends overriding `getMessage()`, and adding a section after § Choosing how unmatched exceptions are logged.

The catch-all is plain `@error` SDL, valid today. This is the sis shape: the existing `UgyldigInput` member that catches Graphitron's own client errors, with two broad handlers appended last so they only see what every earlier handler passed over.

```graphql
type UgyldigInput @error(handlers: [
    {handler: GENERIC, className: "com.example.generated.schema.GraphitronClientException"},
    {handler: GENERIC, className: "java.lang.IllegalArgumentException"},
    {handler: GENERIC, className: "org.jooq.exception.DataAccessException",
     description: "Operasjonen kunne ikke utføres."}
]) {
    path: [String!]!
    message: String!
}
```

What the item adds is Java, on the runtime:

```java
var runtime = Graphitron.runtime(dataSource, SQLDialect.ORACLE)
    .withFetcherErrorLog(new FsErrorLog())
    .withFetcherErrorResponse(new FsErrorResponse());
```

> **Logging the errors a handler caught.** A handler that catches an exception puts it in the payload and logs nothing, which is right for a business error the client is meant to handle. For a broad handler last in a union (a catch-all) it means a database outage on a mutation reaches the client and never the log. Override `matched` on your `FetcherErrorLog`:
>
> ```java
> final class FsErrorLog implements FetcherErrorLog {
>     public void unmatched(Throwable thrown, String reference, DataFetchingEnvironment env) {
>         LOG.error("Unhandled error; reference = {}", reference, thrown);
>     }
>     @Override
>     public void matched(Throwable thrown, Throwable claimed, String reference, DataFetchingEnvironment env) {
>         if (claimed instanceof DataAccessException) {
>             LOG.error("Database error returned in payload; reference = {}", reference, thrown);
>         }
>     }
> }
> ```
>
> Graphitron calls `matched` once for each exception a handler catches in a request, however many fields it failed, with the exception the fetcher threw, the one the handler caught (the same object, or a link of its cause chain), the reference the client can be given for it, and the failing field's environment. Its default does nothing, so a log that does not override it behaves as before. `VALIDATION` errors and the column-length check are not exceptions and do not reach it.
>
> **Choosing what the client reads.** `description:` gives a handler a fixed message. When the message has to be computed from the exception, or has to carry the reference, give the runtime a `FetcherErrorResponse`. All three of its methods have defaults that reproduce what Graphitron does without one, so override only what you need:
>
> ```java
> final class FsErrorResponse implements FetcherErrorResponse {
>     @Override
>     public String reference(Throwable thrown, DataFetchingEnvironment env) {
>         String traceId = env.getGraphQlContext().get(TRACE_ID);
>         return traceId != null ? traceId : FetcherErrorResponse.super.reference(thrown, env);
>     }
>     @Override
>     public String matchedMessage(Throwable source, String defaultMessage, String reference,
>                                  DataFetchingEnvironment env) {
>         if (!"UgyldigInput".equals(((GraphQLNamedType) env.getParentType()).getName())) {
>             return defaultMessage;
>         }
>         return FsMessages.describe(source, defaultMessage) + " Referanse: " + reference;
>     }
>     @Override
>     public String unmatchedMessage(Throwable thrown, String reference, DataFetchingEnvironment env) {
>         return "An exception occurred. The error has been logged with id " + reference
>             + FsMessages.hint(thrown).map(h -> ": " + h).orElse(".");
>     }
> }
> ```
>
> * `reference` mints the text that joins a client's report to your log line. The default is a random UUID. Return a trace id to let an operator go straight from a support ticket to the trace. Read it from somewhere the fetcher's thread can see: a fetcher may run on a thread that does not carry your request's tracing context, so put the trace id into the request's `GraphQLContext` when you build the execution input, and read it here. Graphitron hands the same reference to your `FetcherErrorLog` and to the two message methods, so they always agree. A runtime with a log and no response uses the default reference.
> * `matchedMessage` is called when a payload error's `message:` is read, with the exception the handler caught, the message Graphitron would return (the handler's `description:`, else the exception's own message), the reference and the `message` field's environment. `env.getParentType()` is the `@error` type, which is how to change the message of one type only. Return `defaultMessage` for errors you leave alone.
> * `unmatchedMessage` is the top-level error's message when no handler caught the exception. The default is `"An error occurred. Reference: <reference>."`. It is the client-facing text of a fault you did not plan for, so do not put the exception's message in it unless you know what it contains.
>
> If a method throws or returns `null`, the client gets the default text, and Graphitron logs one ERROR line naming your class, the class of what it threw and the reference. As with `FetcherErrorLog`, the escape-hatch engine has no runtime: put the response into each request's context under its class, `graphQLContext(b -> b.put(FetcherErrorResponse.class, response))`.
>
> **Pitfall: a catch-all can leak.** A handler on `DataAccessException` without `description:` and without a `matchedMessage` that replaces it returns jOOQ's message to the client, and that message holds the SQL statement and, on Oracle, row values. Give a catch-all a `description:` as its safe default. A catch-all on `RuntimeException` also catches `GraphitronClientException`, since handlers are tried before Graphitron surfaces its own client errors; declare a handler for that class first if the two should read differently.

For `graphitron-jakarta-rest` the javadoc of `GraphitronApplication` carries the matching text, since that module has no manual page yet: a fault thrown while building the execution input (the database down when `newExecutionInput` opens a connection) never reaches a fetcher, and `GraphqlHttpHandler` answers it itself. Override `newReference(Throwable)` and `redactedMessage(Throwable, String)` to give it the same reference and text as your `FetcherErrorResponse`; the resource cannot see the generated interface, so the two overrides call the same helper of yours.

## Implementation

No directive, classifier or model change: the catch-all is already expressible, and both hooks are runtime callbacks. Everything below is emission in the generated `schema` package plus two default methods in `graphitron-jakarta-rest`. Generated code stays Java 17, and no runtime jar appears.

**`FetcherErrorLog.matched`** (`ConnectionRuntimeClassGenerator.fetcherErrorLog`). A default no-op method `matched(Throwable thrown, Throwable claimed, String reference, DataFetchingEnvironment env)`, the later hook point the interface's javadoc already reserves: `unmatched` stays the one abstract method, so every existing lambda implementor compiles unchanged. The interface javadoc stops describing itself as only for unclaimed exceptions. It also states the call count: `matched` fires once per claimed source per request. And it says the reference is minted by `FetcherErrorResponse.reference`, so a consumer that configures only a log gets the default.

**`FetcherErrorResponse`**, a new interface emitted beside `FetcherErrorLog` (a new `ConnectionRuntimeClassGenerator.FETCHER_ERROR_RESPONSE_CLASS_NAME`). Three default methods, none abstract, so it is not a functional interface and a consumer overrides only what it needs:

* `String reference(Throwable thrown, DataFetchingEnvironment env)`, default `UUID.randomUUID().toString()`.
* `String matchedMessage(Throwable source, String defaultMessage, String reference, DataFetchingEnvironment env)`, default `defaultMessage`.
* `String unmatchedMessage(Throwable thrown, String reference, DataFetchingEnvironment env)`, default `"An error occurred. Reference: " + reference + "."`.

It joins the `CONNECTION_RUNTIME` unit set the way `FetcherErrorLog` did: `EmitPlan.connectionRuntime`, the `PlanCompileGraph` edge beside `runtimeUnit("FetcherErrorLog")`, and `ErrorRouterClassGenerator.fetcherErrorResponseClass(outputPackage)` resolved through `GeneratedUnits` like `fetcherErrorLogClass`. `GraphitronRuntime` gains a `fetcherErrorResponse` field, carried by the private copy constructor alongside `fetcherErrorLog`, a `withFetcherErrorResponse(FetcherErrorResponse)` wither (null rejected, as `withFetcherErrorLog` does) and an `Optional` accessor; `GraphitronConnectionInstrumentationGenerator` publishes it under its class key next to the log.

**`ErrorRouter`** (`ErrorRouterClassGenerator`):

* `toConfiguredLog` generalises into one containment helper parameterised on the hook class, so the log and the response share one spelling of "look it up in the `GraphQLContext`, call it, contain anything but a `VirtualMachineError` to one ERROR line naming the hook's class, the class of what it threw and the reference". The response's flavour also treats a `null` return as a failure. The `reference` call has no reference to name yet; its failure line names the field's path instead.
* When no response is configured, `ErrorRouter` calls a private static default instance, `new FetcherErrorResponse() {}`, rather than restating the defaults. The interface's default methods are then the only spelling of the generated defaults; no second `"An error occurred. Reference: "` literal appears in `ErrorRouter`.
* `newReference(Throwable thrown, DataFetchingEnvironment env)`: the configured `reference`, else the default instance's. It replaces the inline `UUID.randomUUID()` in `redactBody` and in both arms of `TenantConnections.logFanOutFailure`, so every reference the client sees comes from one place. The default log lines keep printing the reference where they print `correlationId` today.
* `ChainRoots` becomes the per-request claim record: per claimed source, identity-keyed as today, the chain roots plus the source's reference. It is the one per-request holder keyed on throwable identity in the error channel, and R920's memo at `redact` is meant to become a second writer to it, not a second holder.
* `claim`: on a match, when a log or a response is configured, it mints the source's reference through `newReference(t, env)` and stores it on the record if the source has none yet. So `reference` always receives the failing field's environment. When a log is configured and this was the source's first claim in the request, it then calls `matched(thrown, t, reference, env)` through the containment helper. A batch that hands one exception to many fields therefore logs one `matched` line. A null `env` (the javadoc already allows one) skips both.
* `redactBody`: `reference = newReference(thrown, env)`; the message comes from `unmatchedMessage(thrown, reference, env)` on the configured or default response, through the helper.
* `clientMessage(Throwable source, String defaultMessage, DataFetchingEnvironment env)`: returns `defaultMessage` when no response is configured; otherwise `matchedMessage(source, defaultMessage, reference, env)` through the helper, with the reference read off the claim record. Every `Throwable` that reaches an errors list goes through `claim` (`ErrorDispatchFragments.outcomeArm` is the one producer of an `ErrorList`). The implementer confirms that against the tree. If it holds, a missing record is an invariant breach and fails loudly, rather than minting a second reference at read time.

**`<ErrorType>Fetchers.message`** (`ErrorTypeFetcherClassGenerator.messageMethod`). The hook's domain is every `Throwable` source, in one layout. Today the order of the two arms depends on whether some handler on the type declares `description:`. When none does, the `GraphQLError` arm runs first. A claimed exception that is also a `GraphQLError` (any `GraphqlErrorException` subclass, `GraphitronClientException` included) would then reach the hook on some types and not on others, and adding a `description:` to a sibling handler would change which. The `Throwable` block therefore always comes first and always ends in `return ErrorRouter.clientMessage(thr, thr.getMessage(), env)`; each per-handler statement returns `ErrorRouter.clientMessage(thr, <today's expression>, env)`. The `GraphQLError` arm follows and is reached only by a non-`Throwable` source, the `VALIDATION` path. Moving the arm changes no default: a class that is both shapes has one `getMessage()`. The build-time `ClientMessage` fork is untouched: it still decides, per handler and at build time, what the default message is. The hook decides at runtime whether the consumer replaces it, and only the runtime holds that fact. The class javadoc's resolution order is rewritten to match.

**Generated unit names.** Every site naming `FetcherErrorResponse` gets its `ClassName` from `GeneratedUnits`, through `fetcherErrorResponseClass`. That includes the instrumentation generator, which today re-spells `ClassName.get(schemaPackage, ...)` for the log; that site moves over for both while it is being edited.

**Emitted style.** The new emitted helpers are written in plain Java 17: no `var`, no switch patterns, no ternaries in the containment branch.

**`graphitron-jakarta-rest`** (`GraphitronApplication`, `GraphqlHttpHandler`). Two default methods on the SPI, `String newReference(Throwable thrown)` (default a random UUID) and `String redactedMessage(Throwable thrown, String reference)` (default today's text), which `GraphqlHttpHandler`'s broad catch arm calls in place of its inline UUID and string. A throw from either falls back to the default under the same one-line containment; the module compiles at 17, so no pattern-matching `instanceof` on the catch. The `WebApplicationException` passthrough above it is unchanged. The defaults duplicate the generated ones, unavoidably: the module compiles before, and independently of, any generated code. `GraphQLOverHttpConformanceTest.redactionShapeMatchesFetcherPath` is the enforcer that keeps the two sets of defaults agreeing.

**Manual.** `docs/manual/how-to/error-channel.adoc` takes the first-client text above: § `path:`, `message:` field fetchers's last paragraph points at `matchedMessage` instead of `getMessage()`, § Unmatched exceptions shows `newReference` and `unmatchedMessage` in the `redactBody` excerpt, and a new section after § Choosing how unmatched exceptions are logged carries `matched`, `FetcherErrorResponse` and the pitfall; the § Pitfalls list gains the catch-all bullet. `docs/manual/reference/directives/error.adoc`'s `description` row says it is the default a `matchedMessage` can replace. `docs/manual/how-to/migrating-from-legacy.adoc`'s `@error` bullet gains one sentence for the `SchemaBasedErrorStrategy` / `TopLevelErrorHandler` subclass: declare the catch-all, then move the subclass's message code into `FetcherErrorResponse` and its logging into `FetcherErrorLog`.

## Tests

Execution tier in `graphitron-sakila-example`, against the existing `Query.filmLookup` channel (`FilmLookupService`, union `FilmLookupError`) and the managed runtime as `SessionHookExecutionTest` drives it; if a catch-all member is clearer than reusing `FilmLookupClientFacing`, add one to the fixture schema with a sentence saying what it exercises. Each case names what it pins:

* **Logged match.** A claimed `filmLookup` error reaches `matched` exactly once, with `claimed` the payload's source and a non-blank reference; the response's payload entry is unchanged. The same exception claimed by two fields of one request (a service that throws one shared instance, or a batched child payload) reaches `matched` once, and both payload entries quote one reference. A runtime whose log only implements `unmatched` (a lambda) gets the same response and no call.
* **Computed message, one reference.** With a `FetcherErrorResponse` overriding `reference` (a fixed string read from the request's `GraphQLContext`) and `matchedMessage`, the payload entry's `message` is the computed text quoting that string, and the same string is the reference `matched` received. A second `@error` member on the same union, which the hook leaves alone by parent type, still reads its `description:`.
* **Unmatched message.** `unmatchedMessage` replaces the top-level text, and the reference in it equals the one `unmatched` received, as `runtimeFetcherErrorLog_receivesTheUnmatchedThrowWithTheClientsReference` pins today for the default.
* **Every `Throwable` source reaches the hook.** A claimed `GraphitronClientException` (a `GraphQLError` as well) on a type whose handlers carry no `description:` has its message computed by `matchedMessage`. This pins the single arm order.
* **Containment.** A `matchedMessage` that throws an `AssertionError` and an `unmatchedMessage` that returns `null` each leave the client the default text and no exception detail.
* **Nothing configured.** The existing `filmLookup` and redaction assertions in `GraphQLQueryTest` and `SessionHookExecutionTest` pass unchanged; that is the "configures nothing, sees today's behaviour" half of the goal.
* **Escape hatch.** One case puts the response into the request's context by hand, as `DmlBulkMutationsExecutionTest` does for the log, and sees the computed top-level message.
* **HTTP agreement.** `GraphQLOverHttpConformanceTest.redactionShapeMatchesFetcherPath` keeps passing on defaults. Its application fixture gains an override pair for a second case asserting the input-building fault and a fetcher fault carry the same computed text and the same reference source.

Generator tier: `ErrorRouterClassGeneratorTest` and the connection-runtime generator test assert structure only (the new methods and interface exist with the stated signatures, and `FetcherErrorLog` keeps exactly one abstract method), no matches on generated bodies.

## Implementation notes

Landed as planned, in one commit (`R1087: a catch-all handler is logged and its message can be computed`). Where it differs from or adds to the plan:

- **The claim record is two identity maps.** `ChainRoots` became `Claims`, holding chain roots and references side by side; `claim` delegates its write to a private `recordClaim` so the arguments are effectively final for the `matched` lambda. `claim` mints only when a log or a response is in the request's context, and skips minting when the source already has a reference, so a consumer's `reference` runs once per source in the common case; two racing claims each mint, one wins `assignReference`, and only the winner calls `matched`.
- **One containment helper with a subject supplier.** `contained(hookClass, hook, call, subject)` is the one spelling for both hooks; its failure line is `"<Interface> <class> threw <class>; <subject>"`, which keeps the existing `FetcherErrorLog ... threw ...; reference = ...` text. `answer(response, call, subject)` falls back to the private `DEFAULT_RESPONSE`, so the default text exists only on the interface. `newReference`'s subject is the field's path, as planned.
- **`clientMessage` fails loudly on a missing claim**, after confirming against the tree that `ErrorDispatchFragments.outcomeArm` is the one producer of a `Throwable` in an `ErrorList` (`ChannelEarlyReturnEmitter` puts `GraphQLError`s, not `Throwable`s, there).
- **jakarta-rest's defaults live in one package-private class**, `RedactionDefaults`, which both the SPI defaults and `GraphqlHttpHandler`'s fallback call, so the module spells its default text once.
- **Fixture:** `FilmLookupError` gained a catch-all `FilmLookupCatchAll` member last in the union (`GraphqlErrorException` with no `description:`, standing in for `GraphitronClientException`; `IllegalStateException` with one), and `FilmLookupService` gained `id == 778` (a both-shapes source) and `id == 779` (one shared `IllegalStateException`, `SHARED_FAILURE`). The unmatched cases fail `Film.durabilityError` on `filmById(film_id: ["1"])`.
- **Acceptance evidence:** `FetcherErrorResponseExecutionTest` (eight cases, one per bullet of the plan's Tests section: logged match, shared exception logged once with one reference, lambda log, computed message by parent type, `GraphQLError` source on a no-description type, unmatched message with the log's reference, containment, escape hatch); `GraphQLOverHttpConformanceTest.computedRedactionMatchesFetcherPath` beside the unchanged `redactionShapeMatchesFetcherPath`; structural pins in `ConnectionRuntimeClassGeneratorTest` (`matched` is a default, `FetcherErrorResponse` has three defaults and no abstract method, the withers keep each other's hook) and `ErrorRouterClassGeneratorTest` (`newReference`, `clientMessage`, `DEFAULT_RESPONSE`, `Claims`).
- **Verification-build notes.** Four things outside this diff stopped runs on this 4-core sandbox: a rebase that brought `presence_*` DDL (re-seeded per `.claude/web-environment.md`); `GraphQLQueryTest.filmsByTitleBean_absentInput_buildsNoBeanAndLeavesTheFieldUnfiltered` reading films another class wrote (the shared-database cross-talk item); an mvnd daemon running a pre-R1090 copy of the snapshot plugin, so `PageSizeMaximumQueryTest` saw no cap (green under plain `mvn`); and `GraphCountGrowthGateTest` exceeding its ratio while other modules' test JVMs ran (green alone, and green single-threaded). The covering build ran the reactor to `graphitron-model` in parallel and resumed from there with `-T 1` on the same tree.

## Reviewer findings

### In Review → Done, round 1: rework (question 2, completeness evidence)

Reviewed on trunk `f6d8331` against the delivery in `71bf1b1`. The verification build (`mvn install -Plocal-db`) is green, and the implementation is the change this spec approved: `FetcherErrorLog.matched`, `FetcherErrorResponse` with three defaults, the `Claims` record, `newReference` at every reference site including the tenant fan-out, `clientMessage` failing loudly on a missing claim (the producer audit holds: `ChannelEarlyReturnEmitter` only puts non-`Throwable` `GraphQLError`s in an `ErrorList`), the jakarta-rest pair, the manual text and the R423/R920 cross-references. No question-1 finding.

**The arm-order change has no test that would fail without it.** The plan's Tests section names its evidence: "A claimed `GraphitronClientException` (a `GraphQLError` as well) on a type whose handlers carry no `description:` has its message computed by `matchedMessage`. This pins the single arm order." The delivered case, `FetcherErrorResponseExecutionTest.matchedMessage_reachesASourceThatIsAlsoAGraphQLError_onATypeWithoutDescription`, runs on `FilmLookupCatchAll`, and that type's second handler (`IllegalStateException`) carries `description: "The film lookup failed."`. So `lastOverrideIndex` is 1 for the type, and the pre-change generator already emitted the `Throwable` block first for it; the generated `FilmLookupCatchAllFetchers.message` has per-handler statements, the same layout the old code produced. Reverting `ErrorTypeFetcherClassGenerator.messageMethod` to the conditional order (with the `clientMessage` calls kept) leaves that type's emitted method unchanged, so the case passes either way. No other test overrides `matchedMessage`, which is the only way the order is observable: without it both arms return the same `getMessage()`. The fixture comment in `schema.graphqls` and the test's own comment describe the handler ("carries no `description:`") where the old rule keyed on the type, which is how the gap slipped through.

What would satisfy it: run that case on an `@error` type none of whose handlers declares `description:`, with a source that is both a `Throwable` and a `GraphQLError`. For example, split the `GraphqlErrorException` handler into its own union member with no `description:` anywhere on it (keeping `FilmLookupCatchAll`'s `IllegalStateException` handler for the shared-failure, containment and parent-type cases), point `id == 778` at it, have `TracingResponse.matchedMessage` compute for that member too, and correct both comments. Then check that the case fails against the old ordering before restoring the fix.

Non-blocking: `ErrorRouterClassGeneratorTest.surfaceClientErrorOrRedact_surfacesClientErrorElseFallsThroughToRedact` still asserts on `surface.code().toString()`. That predates this item (`7e16d9b`), and this delivery removed the one body-string assertion it touched (`redact_logsCorrelationIdAndReturnsRedactedResult`).

## Roadmap entries

* R423 (reference from the MDC `trace_id`) depends on this item and narrows to the default of `FetcherErrorResponse.reference` and `GraphitronApplication.newReference`: this item supplies the seam both sites call, so R423 no longer has to decide where the shared helper lives.
* R920 (a failed batch logs once) gets its holder from this item: the claim record keyed on throwable identity is the per-request memo its plan sketches, and its change to `redact` becomes a second writer to it. The matched side already logs once per source here. Whether `redact` should share one client reference across a batch stays R920's question; this item keeps one reference per `redact` call. R920's implementation sketch carries a sentence saying so.

## Other solutions we've considered

* **A handler flag in SDL** (`{handler: GENERIC, className: "...", log: true}`). It names the logged handlers in the schema, but the level, the sanitising and the filtering still have to live in the consumer's log, so the flag only moves half the decision into SDL and adds a directive argument, a model field and a lift rule for it. `matched` lets the consumer filter on the exception it already has to inspect to choose a level.
* **An SDL opt-in for a computed message** (a third `ClientMessage` arm). The same split: the computation is Java either way, and the consumer can scope it by `env.getParentType()`. A schema-visible opt-in would earn its cost only if the build could check something about the computation, and it cannot.
* **Folding the three response methods into `FetcherErrorLog`.** One object to configure, but the log decides what reaches the operator and the response decides what reaches the client; one interface for both would make "the log" also the place a client-facing leak is introduced. Two interfaces keep the review question for each one simple.
* **Overriding `getMessage()` on the exception**, which the manual recommends today. It does not reach a consumer whose exception is jOOQ's `DataAccessException`, and it cannot quote a reference that does not exist yet.
* **Rewriting the result in a graphql-java `instrumentExecutionResult`**, the consumer's current workaround. It can change top-level text after the fact by stashing `(reference, thrown)` from the log, but it cannot create a payload entry for an error no handler caught, since by then the field is already `null`, and it has no link from a payload entry's `message` back to the exception behind it.

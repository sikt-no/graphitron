---
id: R1058
title: "Client errors thrown from generated fetchers carry no path or locations"
status: In Progress
bucket: bug
depends-on: []
created: 2026-10-16
last-updated: 2026-10-16
---

# Client errors thrown from generated fetchers carry no path or locations

## Goal

A client error that a generated fetcher raises carries the failing field's `path` and `locations`, as the GraphQL spec asks of a field error and as v9 did, so a client can tell which field failed. Examples are an invalid cursor and a tenant outside the request's set. Today these errors reach the response with only `message` and `extensions.classification`.

A client error here is graphitron's own report of a mistake in the request, raised as the generated `GraphitronClientException` and delivered with its real message rather than redacted to a correlation id. Against the sakila example's `filmsConnection` (`schema.graphqls`), the query

```graphql
{ filmsConnection(first: 2, after: "not-base64!!!") { nodes { title } } }
```

answers today with two errors, because `filmsConnection` is `FilmsConnection!` and its null bubbles to the root:

```json
{"errors": [{"message": "cursor is not valid (was: \"not-base64!!!\")",
             "extensions": {"classification": "DataFetchingException"}},
            {"message": "The field at path '/filmsConnection' was declared as a non null type, but the code involved in retrieving data has wrongly returned a null value. ...",
             "path": ["filmsConnection"],
             "extensions": {"classification": "NullValueInNonNullableField"}}],
 "data": null}
```

and after this item with one:

```json
{"errors": [{"message": "cursor is not valid (was: \"not-base64!!!\")",
             "locations": [{"line": 1, "column": 3}],
             "path": ["filmsConnection"],
             "extensions": {"classification": "DataFetchingException"}}],
 "data": null}
```

Two things change for a consumer. The client error carries `path` and `locations`. And on a non-null field, graphql-java's `NullValueInNonNullableField` error at the same path no longer appears beside it. graphql-java keeps at most one error per field path, and today the client error has no path to claim one. This matches what a redacted error already gets: it carries the path, so no non-null error appears beside it either. Nullability still bubbles as before (`data` is `null` in both responses). Only the duplicate error goes. The client error's message and classification do not change. On a field fetched once per parent (a `@splitQuery` field, batched through a DataLoader), each parent's error names that parent's path, so a client sees which element failed.

## Cause

Confirmed against graphql-java 25.0 bytecode and the generator.

Every generated fetcher (the graphql-java `DataFetcher` graphitron emits for a field) wraps its body in a catch arm that hands the throw to the generated `ErrorRouter`; async fetchers do the same from an `.exceptionally` arm. When no authored `@error` mapping claims the throw, the router's `surfaceClientErrorOrRedact` walks the cause chain for a `GraphitronClientException` and, on a hit, returns `DataFetcherResult.newResult().data(null).error(clientError)`: the exception instance itself is the error. `GraphitronClientExceptionClassGenerator` builds that class with a message only, so the instance has no path and no locations, and its classification is `GraphqlErrorException`'s default, `DataFetchingException`. graphql-java's `ExecutionStrategy.unboxPossibleDataFetcherResult` adds a `DataFetcherResult`'s errors to the response unchanged, so nothing fills them in.

The same missing path explains the extra non-null error. `ExecutionContext.addErrors`, which `unboxPossibleDataFetcherResult` reaches through `addErrorsToRightContext`, records the path of each error that has one. `NonNullableFieldValidator` adds its `NonNullableFieldWasNullError` through `ExecutionContext.addError(error, path)`, which returns without adding when that path is already recorded. A pathless client error records nothing, so the non-null error is added beside it. A client error that carries the field's path suppresses it.

The redact arm beside it does not have the defect: `ErrorRouterClassGenerator.redactBody` builds its error with `GraphqlErrorBuilder.newError(env)`, which reads `env.getField().getSourceLocation()` and `env.getExecutionStepInfo().getPath()`. Nor does an exception that escapes a fetcher entirely: graphql-java's `SimpleDataFetcherExceptionHandler` always wraps it in `ExceptionWhileDataFetching(path, exception, sourceLocation)`. The router is the one place path and locations are lost.

## Implementation

One emitter changes: `ErrorRouterClassGenerator.buildSurfaceClientErrorOrRedactMethod`. On a cause-chain hit the generated method builds the surfaced error from the field's environment, the way `redactBody` already does:

```java
return DataFetcherResult.<P>newResult().data(null)
    .error(GraphqlErrorBuilder.newError(env)
        .message(clientError.getMessage())
        .errorType(clientError.getErrorType())
        .build())
    .build();
```

carrying over the client error's extensions as well when it has any (the generated class sets none today, so this is future-proofing the implementer may confirm against `GraphqlErrorBuilder`'s null handling rather than a requirement). The cause-chain walk, the redact fall-through and the method's signature stay as they are.

Because every disposition funnels through this one method, the change covers every surfacing site without touching any of them: the sync catch arms (`ErrorDispatchFragments.catchArm`, `redactArm`, `outcomeArm` and `ErrorRouterClassGenerator.noChannelRouterCall`, reached from `TypeFetcherGenerator`, `MultiTablePolymorphicEmitter`, `RoutineWriteFetcherRenderer` and `ConnectionFetcherClassGenerator.facetsDelegate`), the async `.exceptionally` arms (`TypeFetcherGenerator.asyncWrapTail`, `fannedAsyncWrapTail`, `MultiTablePolymorphicEmitter.asyncWrapTail`), the DataLoader sync guard (`TypeFetcherGenerator.dataLoaderSyncCatchBody`), and the unmatched tail of the generated `ErrorRouter.dispatch`. `env` at each of those is the failing field's own environment, per parent on a batched field, which is what gives per-element paths.

Prose that describes the surfaced error as the exception instance is updated in the same change:

- The javadoc `buildSurfaceClientErrorOrRedactMethod` emits ("returns a `DataFetcherResult` with `data=null` and that error"), the generated `ErrorRouter` class javadoc, and `GraphitronClientExceptionClassGenerator`'s class javadoc and emitted javadoc ("surfaces an instance of this type as a real `GraphQLError`"): the router surfaces the exception's message as a field error carrying the field's path and locations. The class stays a `GraphqlErrorException` subclass; it is still what an authored `GENERIC` handler matches, and an authored mapping still places the instance itself into a payload.
- `docs/manual/how-to/error-channel.adoc`, § Unmatched exceptions: redact and correlate. The sentence "that error reaches the response's top-level `errors` with its own message and the field resolves to `null`" becomes: "that error reaches the response's top-level `errors` with its own message and with the failing field's `path` and `locations`, and the field resolves to `null`; on a non-null field it is the only error at that path, as with a redacted error, and the null bubbles to the nearest nullable parent". The § Catching Graphitron's own client errors section needs no change.
- Two `TenantDivinedRoutingExecutionTest` comments that say graphql-java adds its non-null bubbling error beside the refusal become false: the `.as(...)` description in `argumentRoutedTenantOutsideTheSet_isRefused_andItsDatabaseNeverOpened` ("films is [Film!]!, so graphql-java adds its non-null bubbling error beside it") and the line comment in `emptyList_listFilter_isRefusedReadably`. Their `anySatisfy` / `contains` assertions keep passing, so no build step flags the comments. Rewrite them to state the new fact, and tighten both assertions to `singleElement()` (see Tests).

### Every site that raises a client error

The fix sits at the router, so the throw sites need no edit. They are listed so the reviewer can check coverage.

Raised as `GraphitronClientException` and surfaced through the router; all gain path and locations:

- `ConnectionHelperClassGenerator`: the generated `pageRequest` ("first and last must not both be specified", negative `first`/`last`, page size at `Integer.MAX_VALUE`) and `decodeCursor` ("cursor is not valid (was: ...)"), from root connections (sync arm) and `@splitQuery` connections (DataLoader, `.exceptionally` arm).
- `ConnectionRuntimeClassGenerator`, the generated `TenantConnections`: `entryFor` ("Tenant 'K' is not permitted for this request."), `tenantKeyOf` ("Tenant value 'V' is not a value of the tenant column."), `agreeOnTenant` ("Tenant bindings disagree within one operation ...").
- `TenantDslEmitter.divinedKeyDeclaration` and `TenantAcquisitionFragments.divinedKey`: the empty-list "names no tenant" refusal.
- `NodeIdDecodeFailure.throwStatement` and `multiCandidateThrowStatement`: "Invalid node id ..." for filter, argument and service-argument decodes (`CompositeDecodeHelperRegistry` throw mode, the client form of `RecordDecodeFragments.decodeHelper`, `RecordDecodeFragments.polymorphicDecodeHelper`, `MultiTablePolymorphicEmitter.nodeIdDispatchGuard`).
- `SelectionOccurrencesClassGenerator`: `canonical` and `requireConsistentArguments` ("Result key 'k' selects two different fields ...", "... selected with conflicting arguments ...").

Already carrying path and locations, unchanged by this item:

- A client error raised inside a tenant fan-out worker (a per-tenant query run on its own thread, then merged by `TenantConnections.collapseFanOut`): the worker's failure is redacted by `collapseFanOut`, which sets the path.
- A client error thrown from the federation `_entities` fetcher (`EntityFetcherDispatchClassGenerator.buildFetchEntitiesMethod`), which has no router wrapper: graphql-java's default handler wraps it with path and locations.

Out of scope, a different defect: client mistakes thrown as a raw `graphql.GraphqlErrorException` or a plain JDK exception rather than as `GraphitronClientException`. The router does not recognise them as client errors, so it redacts them; the redacted error already carries path and locations, so this item's goal holds for them, but the client gets a correlation id instead of the message. That is R1036's defect, which files the DML `UPDATE` rail; the same shape also appears at `LookupRows.addRowBuildingCore`, the plain form of `RecordDecodeFragments.decodeHelper`, `JooqRecordInstantiationEmitter.emitKeyDecode` and `emitArityThrow`, `NodeIdEncoder.requireColumnAgreement` (from `NodeIdEncoderClassGenerator`), the identity-clear refusals in `TypeFetcherGenerator`, and the bulk-DML `IllegalArgumentException` refusals in `TypeFetcherGenerator`. One routine-write site escapes the router instead: `RoutineWriteFetcherRenderer.renderChainReread` and `renderCarrierKeys` emit the key declarations ahead of the `try`, so a decode mismatch there reaches graphql-java's default handler with path, locations and its raw message under an "Exception while fetching data" prefix. These belong with R1036's Spec rather than here.

## Tests

Execution tier (`graphitron-sakila-example`, real PostgreSQL), the acceptance evidence for the goal:

- `GraphQLQueryTest.filmsConnection_malformedBase64AfterCursor_surfacesClientError` asserts `singleElement()` on the errors (today it asserts `get(0)`, and a second, non-null error sits at index 1). That one error carries the cursor message, `getPath()` is `["filmsConnection"]`, and `getLocations()` is the single location of the `filmsConnection` selection (line 1, column 3 in the test's query). `getData()` is `null`. This covers the sync catch arm, and the single-element assertion pins the suppressed non-null error.
- A new `GraphQLQueryTest` case selecting `actorsConnection(first: 1, after: "not-base64!!!")` (the `@splitQuery` connection on `Film`, `ActorsConnection!`) under `filmById(film_id: ["1", "2"])` (`[Film]!`, nullable elements). Exactly two errors, each with the cursor message and a path naming its own film element: `["filmById", 0, "actorsConnection"]` and `["filmById", 1, "actorsConnection"]`. `data.filmById` is `[null, null]`, because each non-null `actorsConnection` null bubbles to its nullable `Film` element. The count is two because each element's non-null error is suppressed by the client error at the same path; today the same query yields four errors. This covers the DataLoader `.exceptionally` arm and the per-parent claim in the goal.
- `TenantDivinedRoutingExecutionTest.argumentRoutedTenantOutsideTheSet_isRefused_andItsDatabaseNeverOpened` tightens `anySatisfy` to `singleElement()` and also asserts the refusal's path and locations: the tenant refusal named in the report. `emptyList_listFilter_isRefusedReadably` tightens to a single error the same way.
- `TenantDivinedRoutingExecutionTest`'s `assertUnclaimedClientErrorSurfaced` helper also asserts a non-empty path, so the field-with-an-`@error`-channel arms (`ErrorRouter.dispatch`'s unmatched tail) are pinned end to end.

Unit tier:

- `ErrorRouterDispatchUnmatchedTest.unclaimedClientException_inTheCauseChain_surfacesItsOwnMessage` asserts `containsExactly(clientError)`, the instance identity this item removes. It asserts instead that the single error carries the client error's message, `ErrorType.DataFetchingException`, the environment's path and the field's source location (its `environment()` helper's `Field` gains a `SourceLocation`, which it lacks today).
- `ErrorRouterClassGeneratorTest.surfaceClientErrorOrRedact_surfacesClientErrorElseFallsThroughToRedact` pins `.data(null).error(clientError)` as a substring of the generated body. Code-string body matching is banned (`docs/architecture/how-to/testing.adoc`), so that `contains` is deleted, not rewritten; the two tiers above pin the behaviour.

## Other solutions we've considered

- **Attach path and locations at each throw site.** The throw sites are mostly static runtime helpers (`decodeCursor`, `TenantConnections.entryFor`) that hold no `DataFetchingEnvironment`, and a DataLoader batch serves many parents at once, so no throw site knows the one path. Threading `env` through would touch a dozen emitters to fix what one method decides.
- **Keep the error a `GraphitronClientException` by giving the generated class a second constructor taking the environment.** It would keep `instanceof GraphitronClientException` true on `ExecutionResult.getErrors()` elements, but no documented contract offers that: the documented anchor is the `@error` handler match, which happens before the router builds the response error and is untouched. A plain `GraphQLError` matches what `redact` already returns, and the generated class stays a constructor-only subclass, which the classify-time accessor check on `@error` types relies on.
- **Let client errors escape to graphql-java's default handler.** That handler fills path and locations, but prefixes the message with "Exception while fetching data (/path) : ", which changes every surfaced message and bypasses the router's single disposition.

## Reviewer findings

### Round 1 (2026-10-16, Spec -> Ready, reviewer session 0164XNbiNhgYZJuvSuDtaFxk)

Verdict: withhold. One finding, on question one. Question two passes: building the surfaced error with `GraphqlErrorBuilder.newError(env)` in the one router method copies the shape `redactBody` already has, every disposition reaches that method, and every symbol, test and throw site the spec names exists under that name. The cause section is right as far as it goes.

**Finding 1 (question one: what changes for a consumer). The worked example gets today's response wrong, and the plan leaves out a second change consumers will see: the non-null error next to the client error goes away.**

`Query.filmsConnection` is `FilmsConnection!`. Today the query in the Goal returns two errors with `data: null`: the client error with no path, then graphql-java's `NonNullableFieldWasNullError` at `/filmsConnection`. The spec shows one error and `data: {"filmsConnection": null}`. The "after" example has `data` wrong as well.

The second error disappears once the fix lands, for this reason. In graphql-java 25.0, `ExecutionContext.addErrors`, which `unboxPossibleDataFetcherResult` reaches through `addErrorsToRightContext`, records the path of each error that has one in `errorPaths`. `NonNullableFieldValidator` then adds its error with `addError(error, path)`, and that call returns early when the path is already in `errorPaths`. So a surfaced client error that carries the field's path suppresses the null-bubbling error at the same path, the same way the redacted error already does. Any consumer that counts errors or matches on that error will notice this, so the Goal has to say it. Three parts of the spec depend on it:

- The `actorsConnection` test expects "two errors". That count holds only because of this deduplication: `Film.actorsConnection` is `ActorsConnection!` and `filmById` is `[Film]!`, so today each element also gets its own non-null error and the total is four. The expectation needs to give that reason, or the implementer reads the result as a coincidence.
- Two test comments in `TenantDivinedRoutingExecutionTest` say graphql-java adds its non-null bubbling error next to the refusal (in `argumentRoutedTenantOutsideTheSet_isRefused_andItsDatabaseNeverOpened` and `emptyList_listFilter_isRefusedReadably`). They become false and belong in the change. Their `anySatisfy` / `contains` assertions keep passing, so the build will not catch the stale comments.
- The `error-channel.adoc` sentence that changes should mention the dropped error too, if the author decides the manual should describe it.

To satisfy this finding: correct both JSON examples, state the deduplication as part of what changes for a consumer (or argue that it should not count), and add the two comments to the change list.

> Author response (round 1): The Goal now shows today's response as two errors with `data: null` and the response after this item as one error with `data: null`. It says the suppressed non-null error is the second thing a consumer sees change, and accepts it rather than arguing it away: it matches what a redacted error already gets, and nullability still bubbles. Cause gains a paragraph on the `addErrors` / `addError(error, path)` mechanism. The `error-channel.adoc` sentence now says the client error is the only error at its path on a non-null field. The two `TenantDivinedRoutingExecutionTest` comments are in the change list, and their assertions tighten to `singleElement()` so the deduplication is pinned rather than tolerated; both refusals are raised inside the fetcher's `try` and go through `surfaceClientErrorOrRedact`, as the generated `multitenant` `QueryFetchers.films` and `filmActorsByNodeId` show. The `actorsConnection` test names its root (`filmById(film_id: ["1", "2"])`, `[Film]!`), gives the `[null, null]` data shape, and gives the deduplication as the reason the count is two, against four today. `filmsConnection_malformedBase64AfterCursor_surfacesClientError` tightens from `get(0)` to `singleElement()` for the same reason.

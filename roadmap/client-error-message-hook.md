---
id: R1118
title: "FetcherErrorResponse can rewrite the message of an unclaimed GraphitronClientException"
status: Spec
bucket: feature
priority: 4
theme: error-channel
depends-on: []
created: 2026-10-28
last-updated: 2026-10-28
---

# FetcherErrorResponse can rewrite the message of an unclaimed GraphitronClientException

## Goal

A consumer's `FetcherErrorResponse` (the generated hook interface that decides what a client reads for a fetcher exception) can rewrite the message of a `GraphitronClientException` that no `@error` handler claims. A schema owner can then give Graphitron's own client errors, such as the tenant refusal, the wording their clients already know, on queries as well as on mutations. Today `ErrorRouter.surfaceClientErrorOrRedact` puts `clientError.getMessage()` into the top-level error without consulting the hook, so the only way to change that text is to rewrite the finished `ExecutionResult` by matching on it.

`GraphitronClientException` is the exception Graphitron's generated code throws for a client mistake: a tenant outside the request's tenant set, a node id that decodes to the wrong type, an out-of-range page size or an invalid cursor. "Unclaimed" means no `@error` handler on the field caught it, so it surfaces as a top-level error at the field's path instead of as data in a payload.

The minimal pair, from the multi-tenant sakila fixture, under a request whose tenant set is `[1]`:

```graphql
{ films(filmId: 2) { title } }
```

Without a configured hook (today, and unchanged after this item):

```json
{ "errors": [{ "message": "Tenant '2' is not permitted for this request.", "path": ["films"], ... }] }
```

With a hook that overrides the new method:

```java
@Override
public String clientErrorMessage(GraphitronClientException error, DataFetchingEnvironment env) {
    return FsMessages.tenantRefusal(error.getMessage())
        .orElseGet(() -> FetcherErrorResponse.super.clientErrorMessage(error, env));
}
```

```json
{ "errors": [{ "message": "You do not have access to data from institusjonsnrEier = 2", "path": ["films"], ... }] }
```

Classification, extensions, path and locations are the same in both. Without a configured hook, nothing changes.

## Design

**A dedicated fourth method on `FetcherErrorResponse`.** The emitted interface gains

```java
default String clientErrorMessage(GraphitronClientException error, DataFetchingEnvironment env) {
    return error.getMessage();
}
```

beside `reference`, `matchedMessage` and `unmatchedMessage`. It is called once per unclaimed client error, with the exception found on the cause chain (not the outermost throw) and the failing field's environment. The parameter is typed on the generated `GraphitronClientException`, which lives in the same schema package as the interface, because that is the only thing this path ever hands it.

The three message methods then partition what a client reads for a fetcher exception:

* a handler caught it: `matchedMessage`, as today;
* no handler caught it and it is a `GraphitronClientException`: `clientErrorMessage`;
* no handler caught it and it is anything else: `unmatchedMessage`, as today.

`matchedMessage` is not reused for the middle case. Its contract does not fit: its `env` is the `@error` type's `message` field (`env.getParentType()` is the `@error` type), and its `reference` is the one the log received, while on this path `env` is the failing field's and no reference exists. Reusing it would also start feeding every existing implementation errors it was never written for, which the dedicated default method avoids: an implementation that does not override `clientErrorMessage` behaves exactly as before.

**No `defaultMessage` parameter.** `matchedMessage` takes one because its default (the handler's `description:`, else the exception's own message) depends on the handler table, which the consumer cannot see. Here the default is exactly `error.getMessage()`, which the method already holds; a consumer that leaves an error alone returns `FetcherErrorResponse.super.clientErrorMessage(error, env)`.

**No reference, no log line.** The surfacing path mints no reference and logs nothing, and stays that way: a client error is the client's mistake, already explained by its text, and Graphitron 9 gave none for the tenant refusal either. The method therefore takes no `reference` parameter.

**Failure containment through the existing helpers.** The router asks the hook through `ErrorRouter.answer(configured(env, FetcherErrorResponse.class), call, subject)`, the helper `newReference`, `clientMessage`, `redact` and `settleFailure` already use. A hook that throws or returns `null` is one ERROR line naming the hook's class and the class of what it threw, and the client gets `error.getMessage()` from `DEFAULT_RESPONSE`. The line's subject is the field's path (`"path = " + pathOf(env)`, as `newReference` does), since there is no reference to name. That ERROR line is the only thing this path can ever log, and only for a misbehaving hook.

**Discrimination is by message text.** All of Graphitron's client errors are one class, so a hook tells the tenant refusal from a node-id decode failure by the exception's message (and, where useful, `env`). For sis's case the hook parses the tenant key out of `Tenant '<k>' is not permitted for this request.`, the same coupling sis already accepts on the mutation path through `matchedMessage`. This makes Graphitron's English wording a parse contract that nothing enforces; a reworded message fails open to Graphitron's own text, never into a leak. The manual says so in the `clientErrorMessage` bullet. A structured discriminator is deliberately not part of this item; see Other solutions.

**Out of scope.** The mount-failure placeholder ("The session identity was rejected.") on the same method keeps its fixed text: its result is discarded, because the operation throws the rejection out of `GraphQL.execute`. A client error a handler claims keeps going through `matchedMessage`. Fan-out failures (`TenantConnections.logFanOutFailure`, `collapseFanOut`) are redacted and do not pass through this arm.

## Implementation

* `ConnectionRuntimeClassGenerator.fetcherErrorResponse(ClassName)`: add the `clientErrorMessage` default method, its javadoc (when it is called, that its `env` is the failing field's, that discrimination is by message text, that `null` falls back to the exception's own message), and update the interface javadoc ("its reference, the `message:` of an error a handler caught, the top-level message of a client error none caught, and of anything else none caught"). The class name comes from `GeneratedUnits.clientException()`, as `buildSurfaceClientErrorOrRedactMethod` resolves it. Update the generator method's own javadoc ("three default methods") and the `reference` method's javadoc, which says the reference reaches "the two message methods"; it still reaches exactly those two, so name them instead of counting.
* `ErrorRouterClassGenerator.buildSurfaceClientErrorOrRedactMethod`: in the `instanceof clientError` arm, compute `String message = answer(configured(env, FetcherErrorResponse.class), response -> response.clientErrorMessage(clientError, env), () -> "path = " + pathOf(env))` and pass `message` to `.message(...)`; `errorType`, `extensions`, path and locations unchanged. The method needs the `FetcherErrorResponse` class name (`fetcherErrorResponseClass(outputPackage)`), as `buildNewReferenceMethod` takes it. Update its javadoc, the `ErrorRouter` class javadoc, and the generator's class javadoc, which list the hook calls.
* `GraphitronClientExceptionClassGenerator`: the class javadoc and the emitted class javadoc say the router "surfaces its message"; make them say the configured `FetcherErrorResponse.clientErrorMessage` may reword it.
* `PlanCompileGraph`, `CONNECTION_RUNTIME` arm: add an edge `fetcherErrorResponse -> CLIENT_EXCEPTION` (`refOf(GlobalUnitKind.CLIENT_EXCEPTION)`). It is required, not optional: leg 2 of `IncrementalCompileHarnessTest.threeLegOracle_projectedGraphMatchesTheEmitArtifact` exempts frozen-scaffold units only as *sources* of references, and `FetcherErrorResponse` is not one, so the new parameter type is a gap without the edge. Use `declared(...)` to match the rest of the arm. In the same arm, add the missing `tenantConnections -> CLIENT_EXCEPTION` edge (`declared`) for the tenant refusal's throw: it is a latent gap the oracle has never seen because its run is single-tenant.

No classifier, model or SDL change: the item adds a hook call on a runtime path every build already emits.

## User documentation (first-client check)

In `docs/manual/how-to/error-channel.adoc`:

* *Catching Graphitron's own client errors* gains a closing paragraph: "To change only the wording, and keep the error at the top level, override `FetcherErrorResponse.clientErrorMessage` instead; see <<fetcher-error-response,Choosing what the client reads>>."
* *Unmatched exceptions*: the sentence "that error reaches the response's top-level `errors` with its own message" becomes "with its own message, or the one your `FetcherErrorResponse.clientErrorMessage` returns". "Nothing is logged" gains "(unless your `clientErrorMessage` throws or returns `null`, which logs one ERROR line naming the field's path)".
* *Choosing what the client reads*: "All three of its methods" becomes "All four". The example `FsErrorResponse` gains the override from the Goal above. A new bullet between `matchedMessage` and `unmatchedMessage`:

  > `clientErrorMessage` is the top-level error's message for a `GraphitronClientException` no handler caught, for example a tenant outside the request's tenant set. It receives the exception and the failing field's environment. The default is the exception's own message. There is no reference: Graphitron neither logs these errors nor gives them one. All of Graphitron's client errors are one class, so tell them apart by message; that text is Graphitron's and may be reworded in a release, so match it loosely and fall back to `FetcherErrorResponse.super.clientErrorMessage(error, env)` when nothing matches.

  The failure paragraph ("logs one ERROR line naming your class, the class of what it threw and the reference") gains "(the field's path, for `clientErrorMessage`)".
* *Pitfalls*, "Unmatched flows redact, except graphitron's own client errors": "surfaces as a top-level error with its own message" gains "(or your `clientErrorMessage`'s)".

The pitfall paragraph about a `RuntimeException` catch-all claiming `GraphitronClientException` does not change: a claimed client error goes through `matchedMessage`, so `clientErrorMessage` is no alternative there.

## Tests

* **Unit tier**, `graphitron-sakila-example/.../internal/ErrorRouterUnclaimedTest`, calling `ErrorRouter.surfaceClientErrorOrRedact` with a hand-built environment that carries a `GraphQLContext` holding a `FetcherErrorResponse`:
  * a hook that rewrites: the error carries the hook's message, and the same `errorType`, extensions, path and locations as without one; the hook received the client exception from the cause chain, not the wrapper;
  * a hook that throws, and one that returns `null`: the error carries the exception's own message;
  * a hook that overrides only `matchedMessage`: the error carries the exception's own message (an existing implementation is unaffected);
  * an unclaimed internal fault with a hook configured: `clientErrorMessage` is not called, and the message is `unmatchedMessage`'s.
  The existing `unclaimedClientException_inTheCauseChain_surfacesItsOwnMessage` pins the no-hook case.
* **Execution tier**, `graphitron-sakila-example/.../querydb/TenantDivinedRoutingExecutionTest`, with a second `GraphQL` built from the same runtime's `withFetcherErrorResponse(...)`, the hook rewriting the tenant refusal to sis's Graphitron 9 text:
  * `{ films(filmId: 2) { title } }` under tenant set `[1]`: one error, the rewritten text, at path `films` (the query case from sis);
  * `retitleFilmsChecked` (a channel whose only handler is `DATABASE`): the rewritten text, through the fall-through after the channel's mappings;
  * `retitleFilmsClaimed` (a `GENERIC` handler claims the client exception): the payload's `message` is Graphitron's text, untouched by `clientErrorMessage`, which pins the partition.
* **Generator tier**, `ErrorRouterClassGeneratorTest`: no new structural assertion; the existing signature tests stay green.
* `IncrementalCompileHarnessTest.threeLegOracle_projectedGraphMatchesTheEmitArtifact` stays green with the new edge, and fails without it.

## Other solutions we've considered

* **Reuse `matchedMessage`**, passing `null` (or a freshly minted reference no log line carries) and the field's environment. Rejected for the contract mismatch and the silent behaviour change for existing implementations, both under Design.
* **A structured discriminator**, for example a final subclass of `GraphitronClientException` per kind carrying `tenant()`, so the hook need not parse text. It would not break the invariant that `GraphitronClientException` declares nothing beyond its constructor, since that binds the base class the classifier's accessor check substitutes, and a Java subclass is not wire-visible. It is out of scope for its cost: new emitted units, a non-final base, and classifier recognition of the subclass names in `@error` handlers. Worth filing as its own item if a second consumer needs to tell Graphitron's client errors apart.
* **Rewrite the `ExecutionResult` in an instrumentation.** What sis would do without this item; it matches on finished text across the whole response, which is the coupling the goal removes from the response layer.

## Provenance

Requested 2026-10-28 by the sis Graphitron 10 port session, which tracks it as C24 in `GRAPHITRON10_STATUS.md`. It is not a port blocker: until it lands, sis queries carry Graphitron's text.

On mutations sis already gets its text: its `UgyldigInput` claims the exception through a `GENERIC` handler on `graphql.GraphqlErrorException` with no `description:`, so the message goes through `matchedMessage`, which sis's implementation (`FsFeilsvar`) rewrites. On queries such as `studenter(filter: {eierOrganisasjonskode: "<other>"})` no handler claims it, and the surfacing path does not call the hook. Graphitron 10's refusal is thrown from the generated `TenantConnections` (`ConnectionRuntimeClassGenerator`, method `entryFor`); sis's Graphitron 9 text was `You do not have access to data from institusjonsnrEier = <nr>`.

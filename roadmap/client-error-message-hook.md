---
id: R1118
title: "FetcherErrorResponse can rewrite the message of an unclaimed GraphitronClientException"
status: Backlog
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

## The case from sis

sis wants Graphitron 9's text back for the tenant refusal. Graphitron 10 says `Tenant '<nr>' is not permitted for this request.`, thrown from the generated `TenantConnections` (emitted by `ConnectionRuntimeClassGenerator`). sis's Graphitron 9 text was `You do not have access to data from institusjonsnrEier = <nr>`.

* **Mutations: solved in sis.** sis's `UgyldigInput` claims the exception through a `GENERIC` handler on `graphql.GraphqlErrorException` with no `description:`. The message therefore goes through `FetcherErrorResponse.matchedMessage`, which sis's implementation (`FsFeilsvar`) rewrites.
* **Queries: blocked.** For example, `studenter(filter: {eierOrganisasjonskode: "<other>"})` has no handler, so the refusal is unclaimed. R1046 made an unclaimed client error surface as a top-level error with its own message, and that path does not call the hook. The only sis-side alternative is an instrumentation that rewrites the finished `ExecutionResult` by text.

## Where it happens today

`ErrorRouterClassGenerator.buildSurfaceClientErrorOrRedactMethod` emits the cause-chain walk. On a `GraphitronClientException` it returns a `DataFetcherResult` with `data(null)` and one error built from `clientError.getMessage()`, `getErrorType()` and `getExtensions()`, plus the failing field's path and locations. The user manual describes the behaviour in `error-channel.adoc` under unmatched exceptions ("that error reaches the response's top-level `errors` with its own message … and nothing is logged"). The hook is documented in the same page's `FetcherErrorResponse` section (`#fetcher-error-response`).

## Proposed shape (from the sis request)

When a `FetcherErrorResponse` is configured, the surfaced client error's message comes from the hook. Classification, extensions, path and locations stay as they are today. Without a configured hook, nothing changes.

## Open questions for Spec

* **Reuse `matchedMessage`, or add a dedicated method.** The request leaves this to Spec. Three facts from trunk bear on it:
  * `matchedMessage(source, defaultMessage, reference, env)` is documented as the message of "an error a handler caught", and its `env` is the `@error` type's `message` field, where `env.getParentType()` is the `@error` type. On the surfacing path, `env` is the failing field's and there is no `@error` type.
  * The surfacing path logs nothing and mints no reference. `matchedMessage` takes the reference the log received, so reusing it means either minting a reference no log line carries, or passing `null`.
  * An existing implementation that overrides `matchedMessage` would start seeing unclaimed client errors it was never written for.
  
  A dedicated default method that returns the message unchanged, for example `clientErrorMessage(GraphitronClientException error, DataFetchingEnvironment env)`, leaves every existing implementation unaffected and gives the method an honest contract. It is the likelier choice, but it is Spec's.
* **A reference.** sis does not need one: Graphitron 9 gave none for this text. Minting one only makes sense if this path also starts logging, which it deliberately does not.
* **Failure containment.** `clientMessage` contains a failing hook (the default message stands, via `answer(...)`). The new call should do the same.
* **The mount-failure placeholder.** "The session identity was rejected." is a fixed text on the same method, and its result is discarded. It stays out of scope.

## Docs

`error-channel.adoc`: the unmatched-exceptions paragraph and the `FetcherErrorResponse` section (`#fetcher-error-response`), which lists the hook's methods. The pitfall paragraph that tells a reader to declare a `GraphitronClientException` handler to make the two read differently gains an alternative.

## Provenance

Requested 2026-10-28 by the sis Graphitron 10 port session, which tracks it as C24 in `GRAPHITRON10_STATUS.md`. It is not a port blocker: until it lands, sis queries carry Graphitron's text.

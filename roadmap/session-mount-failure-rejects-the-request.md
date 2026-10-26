---
id: R1097
title: "A rejected session identity fails the whole request instead of surfacing as a redacted field error"
status: Ready
bucket: feature
priority: 4
theme: error-channel
depends-on: []
created: 2026-10-23
last-updated: 2026-10-26
---

# A rejected session identity fails the whole request instead of surfacing as a redacted field error

## Goal

When the database rejects the caller's identity (the consumer's `<sessionState>` mount method throws), the whole request is rejected once, as a typed `SessionMountException` the consumer can map to a status such as 401 or 400, instead of a redacted field error plus a 422 and one ERROR log line per root field. The *mount* is the consumer-authored static method named in `<sessionState>` that sets per-request identity on the connection graphitron pins for the operation; today a throw from it is treated like any other fetcher failure. Tracked as GitHub issue sikt-no/graphitron#563.

Consumer view, with a JAX-RS adapter that already implements `GraphitronApplication`:

```java
@Override
public Optional<WebApplicationException> clientFault(Throwable thrown) {
    if (thrown instanceof SessionMountException mount && rejectedIdentity(mount.getCause())) {
        return Optional.of(new NotAuthorizedException("identity rejected"));
    }
    return Optional.empty();
}
```

A consumer on the raw engine (no graphitron-jakarta-rest) sees `GraphQL.execute(...)` throw `SessionMountException` with the original cause attached, so the SQLState is inspectable.

## Problem, as it stands

On the owned-connection engine (`runtime.newGraphQL(schema)`), connections are pinned and identity mounted lazily, the first time a fetcher needs a `DSLContext`: `TenantConnections.entryFor` calls `GraphitronRuntime.acquire`, which calls `PinnedConnection.acquire`, which calls the generated `GraphitronSessionHook.mount` (all emitted by `ConnectionRuntimeClassGenerator`). Graphitron already fails closed when the mount throws: `PinnedConnection.acquire` evicts the connection. What follows is wrong for a rejected identity:

1. The exception is thrown inside the first fetcher that needs the database, and the generated `ErrorRouter` redacts it: one ERROR log line with a correlation id per failing field, and the client receives only the reference message.
2. `GraphqlHttpHandler.statusFor` then answers 422 when no data came back and 200 when other fields resolved.
3. The failed acquisition is not remembered (`computeIfAbsent` throws, nothing is cached), so each root field that needs the database retries the mount: N fields, N round trips, N rejections.

No field can run without identity, so this is a request-level fault, and the consumer cannot turn it into an authentication status: the exception never leaves execution.

## Verified premise

On the pinned graphql-java 25.0, an exception thrown from `Instrumentation.instrumentExecutionResult`, thrown from `beginExecuteOperation`, or returned as a failed future from `instrumentExecutionResult`, each propagates out of `GraphQL.execute` unchanged (probe run against the 25.0 jar, three modes, all threw the original exception). The plan leans on the first form, so an execution-tier test pins it; the pending graphql-java upgrade is where that test would catch a change.

## Plan

### Implementation

* **`SessionMountException`**, a new emitted class in `<outputPackage>.schema` beside `GraphitronClientException`, a `RuntimeException` that carries the original cause. It is a different thing from `GraphitronClientException`: that one is a field-scoped client mistake surfaced as a field error; this one is a request-scoped fault that never reaches a field error. Emitted only when `<sessionState>` configures a mount (a build without one has nothing to reject), and everything below that names it is gated the same way.
* **`PinnedConnection.acquire`** (in `ConnectionRuntimeClassGenerator`): wrap only the `GraphitronSessionHook.mount(...)` call in its own `try`, converting a thrown `Exception` into `SessionMountException`; `Error` still propagates as is. The existing outer `catch (Throwable)` keeps evicting the connection and rethrows, so eviction is unchanged for both arms. The `setAutoCommit(true)` assertion and `dataSource.getConnection()` stay outside the wrap: a pool configured `autoCommit=false` is a deployment error and must not read to the consumer's 401 mapping as an identity fault.
* **First mount failure is remembered per operation.** `TenantConnections` (the per-operation carrier) holds an `AtomicReference<SessionMountException>` set with `compareAndSet`, so concurrent scatter workers on different tenant keys resolve to one winner. `entryFor` checks it after the tenant-set refusal and the timed-out-tenant refusal (so the tenant-authorization order, and the inability to probe which tenants a deployment hosts, are unchanged) and before `computeIfAbsent`, and rethrows the stored exception for every later acquisition on any key. `entryFor` stays the single enforcer. Memoizing per carrier, not per key, is a deliberate escalation: the mount payload is one identity for every tenant, and per-tenant authorization is settled earlier by the tenant set. It overrides the documented `@tenantFanOut` partial-data rule (a failing tenant contributes a `null` element and a path-bearing error) for mount failures only, and `tenant-scoping.adoc` says so.
* **`ErrorRouter` arm for `SessionMountException`**, placed at the shared catch entry ahead of both the `@error` channel claim and the no-channel `redact` path, covering every catch arm that calls into `ErrorRouter` (the per-fetcher wrappers, the batched `@splitQuery` arms and the tenant scatter workers; the implementer enumerates them from the emitter). It logs nothing, mints no correlation id, and returns a fixed placeholder error with no cause detail. Ahead of the channel claim matters: on a field with an `@error` channel, a `GENERIC` handler on `RuntimeException`, or a database mapping on the SQLState walking the cause chain, could otherwise claim the mount failure into a typed payload and turn a rejected identity into business data. The placeholder never reaches the client, because the next bullet discards the result.
* **`GraphitronConnectionInstrumentation.instrumentExecutionResult`** (new override in `GraphitronConnectionInstrumentationGenerator`, emitted only with a mount): when the operation's carrier holds a memoized failure, throw it, so it escapes `GraphQL.execute`. Connection release still runs on the existing completion path, and the failed connection was already evicted at acquire. `GraphQL.executeAsync` yields a failed future with the same exception.
* **`GraphitronApplication.clientFault`** (graphitron-jakarta-rest, hand-written, compiles at Java 17): `default Optional<WebApplicationException> clientFault(Throwable thrown)` returning `Optional.empty()`. `GraphqlHttpHandler`'s broad `catch (Exception)` arm calls it, guarded the way `newReference` and `redactedMessage` are (a throw or a `null` falls back to today's redaction with one ERROR line naming the application class). A present value is thrown through the existing `WebApplicationException` passthrough, so the consumer's chosen status reaches JAX-RS unredacted, with no ERROR log from the handler. This reuses the supported client-fault shape (`newExecutionInput` already documents throwing a `WebApplicationException` as the way to answer one) rather than adding a second status mechanism, and the seam can be typed `Throwable` because the consumer's adapter is the one place allowed to name the generated `SessionMountException`. With no override, a mount failure falls to today's redacted 500: one ERROR line, no stack leak to the client.
* **Other callers of `execute`.** `ExecuteTool` in graphitron-mcp and the generated dev executor run the same instrumentation and now see the exception where they used to see an error result; the implementer confirms each reports it as a failed call rather than an unchecked escape.

### Tests

* Execution tier, extending `SessionHookExecutionTest` (the `reject-me` principal already exists): a request with N root fields that need the database, mount rejected, shows `GraphQL.execute` throwing `SessionMountException` with the original cause reachable, exactly one mount attempt, no per-field errors reaching a result, and the connection evicted. Variants: a `@splitQuery` batched field, an `@error`-channel field with a `GENERIC` `RuntimeException` handler (the mount failure is not claimed), and a multi-tenant build where two scatter workers race.
* A request that touches no database still never pins and never mounts, and still succeeds with a rejecting principal (the lazy contract holds).
* An instrumentation chained *before* and *after* `GraphitronConnectionInstrumentation` in a `ChainedInstrumentation` still lets the exception escape; this is also the premise pin for the graphql-java version.
* A mount that fails the `setAutoCommit` assertion (the existing `autoCommit=false` fixture) is not a `SessionMountException`.
* graphitron-jakarta-rest: `clientFault` present returns the consumer's status with no handler ERROR log; absent, throwing and `null`-returning overrides each fall back to the redacted 500.
* Pipeline tier: the emitted classes carry `SessionMountException` only in a build with a mount.

### Documentation

First-client draft is the consumer view in the Goal. Real homes when this ships: `docs/architecture/reference/runtime-extension-points.adoc` (the `<sessionState>` section: a throwing mount rejects the request, `execute` throws `SessionMountException` on the owned path, and what a raw-engine consumer must catch), the `GraphitronApplication.clientFault` javadoc, the `newGraphQL` javadoc in `ConnectionRuntimeClassGenerator`, and `docs/manual/how-to/tenant-scoping.adoc` (the fan-out exception above).

The connection instrumentation is attached by `runtime.newGraphQL(schema)`, and a later `.instrumentation(other)` on that builder, for example OpenTelemetry, replaces it silently, after which no mount ever happens. The same doc pass fixes this footgun: the `newGraphQL` javadoc and the `GraphitronApplication.engineBuilder` javadoc (whose current text recommends chaining `.instrumentation(...)` for OpenTelemetry) name `ChainedInstrumentation` over `GraphitronConnectionInstrumentation` as the safe pattern. Accepting extra instrumentations on `newGraphQL` itself would remove the footgun rather than document it; that is out of this item's scope and left to a successor if wanted.

## Other solutions we've considered

* **Eager mount in `beginExecuteOperation`** (an opt-in flag). Fixes timing and the per-field retry, but gives up the documented guarantee that an operation touching no database never pins, and in multi-tenant builds would only cover one source. It would still need the typed exception and the status seam this plan adds, so it saves nothing.
* **A consumer-named classifier method mapping mount exceptions to a GraphQL error or status.** The most flexible option and the largest surface: it adds a second status mechanism beside the `WebApplicationException` passthrough and competes with JAX-RS `ExceptionMapper`.
* **A shared hand-written exception type in a runtime module** so jakarta-rest can name it. Breaks the invariant that runtime code is generated into the consumer, not shipped as a jar.
* **A consumer instrumentation that calls `dslDefault()` in `beginExecuteOperation`** (the issue's workaround). Works, but depends on generated classes the docs do not offer as consumer API, and mounts only the default source.

## Reviewer findings

### Round 1 (2026-10-26, Spec -> Ready, reviewer session 01JD78h7sQVcWHBqWJ6mi1SH)

Verdict: Ready. The goal stands on its own, and the plan extends the existing seams (`PinnedConnection.acquire`, the `TenantConnections` carrier, `ErrorRouter`, the `WebApplicationException` passthrough in `GraphqlHttpHandler`) rather than adding a parallel mechanism. Corrected in passing: the "Verified premise" said the plan leans on the second probed form; it leans on the first (a throw from `instrumentExecutionResult`). Three notes for the implementer, none of which changes the design:

1. `ErrorRouter` has no single shared catch entry. Channel arms call `claim` and fall through to `surfaceClientErrorOrRedact`; no-channel arms call `surfaceClientErrorOrRedact` directly. The arm therefore belongs in both: `claim` declines a chain carrying `SessionMountException` (so `recordClaim` neither records nor calls `FetcherErrorLog.matched`), and `surfaceClientErrorOrRedact` answers the placeholder ahead of `redact`.
2. The tenant scatter workers do not reach `claim` or `redact`; a failed tenant goes through the carrier's `logFanOutFailure`, which mints a reference and logs. It needs the same suppression for a mount failure.
3. On a multi-tenant mutation, a field routed to a tenant whose mount succeeded may commit before a later tenant's mount is rejected, and the request then throws. The `tenant-scoping.adoc` paragraph this plan already owes should say so, so a consumer mapping the rejection to 401 knows writes on other tenants may have landed.


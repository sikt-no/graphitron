---
id: R1158
title: "Release pinned connections when execution aborts synchronously"
status: Spec
bucket: bug
priority: 1
theme: runtime-connection
depends-on: []
created: 2026-11-05
last-updated: 2026-11-05
---

# Release pinned connections when execution aborts synchronously

## Goal

A connection the owned-connection path pinned is unmounted and returned to the pool however a query ends, including when graphql-java abandons it synchronously. Today a query that is cancelled, or that has any exception escape the execution strategy's synchronous dispatch, leaves the connection an earlier root field pinned out of the pool for good, still mounted with the caller's identity. Nothing reaches another borrower, but each such request permanently removes one connection, which a consumer sees as pool exhaustion; a deployment that wires a client disconnect to `ExecutionInput.cancel()` can drain its pool through ordinary use. When this lands, release runs on every way a request ends, and the runtime's documented promise ("releases every pinned connection on every completion path") holds as written.

## Why it happens

Terms: the *owned-connection path* is the engine `GraphitronRuntime.newGraphQL(schema)` builds, where `GraphitronConnectionInstrumentation` (emitted by `GraphitronConnectionInstrumentationGenerator`) publishes a per-operation carrier, `TenantConnections`, that pins and mounts one connection per key on first demand. *Release* is `TenantConnections.releaseAll()`: unmount each connection, then return it to the pool, or evict it if unmount failed.

Release runs in one place: the completion callback of the context `beginExecuteOperation` returns, which settles the running mutation field and then calls `releaseAll()`. graphql-java completes that context only when the execution strategy's future completes. `AsyncExecutionStrategy` (queries) resolves root fields in a synchronous loop, and checks for cancellation at two points that sit outside the `try` in `resolveFieldWithInfo`: the per-field check in `getAsyncFieldValueInfo` and the check at the top of `fetchField`. A cancel noticed there throws `AbortExecutionException` straight out of `strategy.execute(...)`. Around that call, `Execution.executeOperation` catches only `NonNullableFieldWasNullException`. `GraphQL` then turns the abort into `AbortExecutionException.toExecutionResult()`, and the operation context is never completed. Any other `RuntimeException` thrown at the same points (a consumer's chained instrumentation, for example) takes the same route and fails `executeAsync` exceptionally.

`AsyncSerialExecutionStrategy` (mutations) dispatches each root field after the previous one from a future callback, where a throw completes the strategy's future exceptionally, so the operation context completes and release runs. Mutations are not affected.

## Evidence

Two spikes against the generated single-tenant runtime of the sakila example (graphql-java 25.0, a fake `DataSource` counting borrows and closes, a mount that runs no SQL), executing two aliased root fields, `a` and `b`, that each acquire. A chained test instrumentation either cancels the input or throws an `IllegalStateException` at a chosen point, and records which of graphql-java's completion hooks fire.

[cols="3,1,1,1,3"]
|===
| Operation, and what happens at `b` | Borrowed | Returned | Unmounted | Hooks that fired

| query, nothing (control)
| 1 | 1 | 1
| operation context, `beginExecution` context, `instrumentExecutionResult`

| query, cancel before `execute`
| 0 | 0 | 0
| (the operation never starts)

| query, cancel at `beginFieldFetching` (noticed in `completeField`, inside the `try`)
| 1 | 1 | 1
| all three

| query, cancel at `beginFieldExecution`
| 1 | 0 | 0
| `beginExecution` context, `instrumentExecutionResult`

| query, throw at `beginFieldExecution`
| 1 | 0 | 0
| `beginExecution` context only (completed exceptionally)

| mutation, cancel at `beginFieldExecution`
| 1 | 1 | 1
| all three

| mutation, throw at `beginFieldExecution`
| 1 | 1 | 1
| operation context, `beginExecution` context
|===

The `beginExecution` context is the one completion hook that fired on every path that pinned a connection.

## Implementation

All in `GraphitronConnectionInstrumentationGenerator`; the instrumentation is one emitted class shared by the single-tenant and multi-tenant builds, so both carriers are covered by one change.

- **Override `beginExecution`** to return a `whenCompleted` context whose callback is the release backstop: when the per-request `State` holds a carrier (it does once `beginExecuteOperation` ran), call `settle(state, true)` and then `state.carrier.releaseAll()`, in a `try`/`finally` like the operation callback. graphql-java creates the state before `beginExecution`, and completes the `beginExecution` context after the operation context on the normal path, so:
  - On the normal path the backstop is a no-op: `settle` returns at once because the operation callback already cleared `fieldPath`, and `releaseAll()` is idempotent (its map is cleared, and the multi-tenant variant is already `closed`).
  - On a synchronous abort it is the release. `settle(state, true)` rolls back a mutation field left running, which the evidence says cannot happen today, but costs nothing to make safe, since an abandoned field must not commit.
- **Keep the operation callback as the primary release.** It settles the last mutation field with its real outcome; the backstop only ever sees a field that did not finish.
- **Do not release in `instrumentExecutionResult`.** It is skipped when `executeAsync` completes exceptionally (the throw row above). The rejected-mount rethrow there is unchanged; it already runs after release on every path that reaches it.
- **Javadoc.** The emitted `beginExecution` and the class javadoc state the two-hook arrangement and why the outer hook exists, naming graphql-java's synchronous-dispatch behaviour rather than any item.

Interaction with R1154: on a synchronous abort, a consumer fetcher that returned an incomplete future may still be running when the backstop releases. In the multi-tenant carrier the `closed` flag refuses its later acquisitions; the single-tenant carrier gains that guard in R1154. This item does not depend on R1154 (single-tenant fetchers that Graphitron emits run synchronously, so nothing of Graphitron's is in flight at the abort), but the two together close the release story.

## Tests

- **Unit tier, `ConnectionRuntimeClassGeneratorTest`.** It already compiles the emitted runtime, instrumentation included, with `EmittedCodeHarness` and records lifecycle events from fake `DataSource`/`Connection` proxies and `RecordingSessionState`. Add an engine-driven group over a two-root-field schema whose fetchers call the emitted carrier's `dslDefault(env)`, built with `newGraphQL(schema, testInstrumentation)` so Graphitron's instrumentation stays first:
  - query, control: one acquire, mount, unmount, close.
  - query, cancel at `beginFieldExecution` of `b`: the same four events, and the result carries `AbortExecutionException`. Fails today (no unmount, no close).
  - query, throw at `beginFieldExecution` of `b`: the same four events, and `executeAsync` completes exceptionally with the thrown exception as cause. Fails today.
  - mutation, cancel and throw at `beginFieldExecution` of `b`: the same four events. Passes today; pins that the serial path keeps releasing.
- **Unit tier, multi-tenant.** The same cancel and throw cases against the multi-tenant emission, with fetchers on two keys through `dslFor(key)`, asserting both connections unmount and close. `TenantConnectionsGeneratorTest`'s harness already compiles that variant.
- **Execution tier, through a real pool.** The real-pool cancel at `beginFieldExecution` of a later root field belongs in R1153's suite, which names it as a gap. Whichever of the two items lands second adds it: on a pool of one, the next borrow returning at all (rather than blocking) and reading `app.user_id` as empty is the end-to-end evidence.

## Documentation

`docs/architecture/reference/runtime-extension-points.adoc`, owned-connection section: the sentence that release runs "on every completion path" gains "including a query cancelled through `ExecutionInput.cancel()` or abandoned by an exception during dispatch", so a consumer wiring client disconnects to cancellation knows it is safe.

## Other solutions we've considered

- **Release in `instrumentExecutionResult`.** Reached on both abort routes, but not when `executeAsync` completes exceptionally, so a synchronous `RuntimeException` would still leak.
- **Catch the synchronous throw inside the strategy.** A Graphitron `ExecutionStrategy` subclass that turns a synchronous throw into a failed future would complete the operation context. But queries run on whatever strategy the consumer's `GraphQL.Builder` carries, and making Graphitron own the query strategy is a larger contract than one more instrumentation hook.
- **Ask graphql-java to complete the operation context on a synchronous abort.** Worth reporting upstream, but the runtime has to be correct on the graphql-java it ships against.

## Context

Found by the Spec → Ready review of R1153, whose in-flight cancel case relied on this release and would have hung on a pool of one.

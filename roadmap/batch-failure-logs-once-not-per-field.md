---
id: R920
title: "a failed batch logs once, not once per awaiting field"
status: Backlog
bucket: bug
priority: 3
theme: error-channel
depends-on: []
created: 2026-09-04
last-updated: 2026-09-04
---

# a failed batch logs once, not once per awaiting field

## Goal

When one batched child query fails, a consumer's log backend should receive **one** record for it,
and every GraphQL error the request returns for that failure should quote the same reference id.
Today it receives one record per field that was waiting on the batch, each with its own reference id
and a full stack trace. A query selecting a split child under 400 parents turns a single timed-out
statement into 400+ stack traces that nothing groups together, so an operator reading the logs cannot
tell one failure from four hundred, and the request's own volume dwarfs everything else in the window.

Two terms, glossed once. `@splitQuery` is the directive that takes a child field out of its parent's
SQL statement and gives it its own query, batched across every parent in the request. It is
implemented with a *DataLoader*: graphql-java collects the keys of all parents that need the child,
runs the child query once for the whole set, then hands each parent its slice of the result.
That is the shape whose failure path this item is about, and the amplification factor is the batch
size, which is exactly the number `@splitQuery` exists to make large.

Concretely, from a consumer report on the 9.x line with the same shape as trunk: a nested field under
a 400-element parent list timed out (Oracle `ORA-01013`, the vendor code for a cancelled statement,
which is what a JDBC query timeout raises), and the log filled with hundreds of records for the one
timeout. The fix belongs on trunk; the 9.x line is not in scope.

Alongside the collapse, a consumer needs a way to substitute the logging. Today they have exactly one
lever, the slf4j level on the generated `ErrorRouter` logger, and turning it off discards the mapping
from the client's reference id to the real cause, which is the whole point of redaction.

## Why it happens

The redaction path is per-field by construction, and nothing along it knows a batch failed once.

* The batch lambda emitted by `RowsMethodCall.batchLoaderLambda` calls its `rows<Field>` method
  synchronously inside `CompletableFuture.completedFuture(...)`, so a SQL throw escapes the batch
  function rather than completing a future. java-dataloader then completes *every* per-key future
  exceptionally with that same `Throwable` instance.
* The fetcher built by `DataLoaderFetcherEmitter.build` attaches the disposition per fetcher
  invocation: `loader.load(key, env).thenApply(...).exceptionally(t -> ErrorRouter.surfaceClientErrorOrRedact(t, env))`,
  from `TypeFetcherGenerator.asyncWrapTail` (and its twin on `MultiTablePolymorphicEmitter`). Each
  awaiting field runs its own `.exceptionally`.
* `surfaceClientErrorOrRedact` falls through to `redact`, emitted by
  `ErrorRouterClassGenerator.redactBody`, which mints a fresh `UUID.randomUUID()` and logs
  `"Unmatched exception in fetcher; correlation id = {}"` with the throwable as the trailing
  argument, so slf4j renders a full stack trace on every one of them.
* The batch lambda has no catch and no logging of its own, so there is no single point where the
  failure is observed once.

The consumer has no seam either. The generated fetcher catches the throwable and returns a
`DataFetcherResult` carrying the error, so graphql-java's `DataFetcherExceptionHandler` is never
invoked and neither is a custom `ExecutionStrategy` chained through
`GraphitronApplication.engineBuilder`. The log is already written before anything a consumer controls
can see it. This is a regression against the 9.x line, where the same logging sat in a public
`TopLevelErrorHandler` a consumer could subclass and inject.

`ConnectionRuntimeClassGenerator.logFanOutFailure` is the same shape at lower severity: it mints a
fresh id per tenant, so its volume is bounded by tenant count rather than by batch size. It should
move with whatever mechanism this item picks, so the two redaction sites keep one spelling.

## Implementation sketch (fill in at Spec)

The recommended shape is a per-request memo keyed on **throwable identity**, consulted at the single
chokepoint `ErrorRouter.redact`: the first field to redact a given `Throwable` mints the reference id
and logs; every later field holding the same instance reuses that id and logs nothing. Identity is
the right key because java-dataloader hands the *same* instance to every key in the batch, while two
genuinely independent failures are two instances and must still produce two records. Per-request
state has a home already, the `GraphQLContext` the consumer seeds in
`GraphitronApplication.newExecutionInput`.

Doing it at `redact` rather than in each batch lambda is what makes it one change instead of one per
emitter, and it also covers fan-out shapes that are not `@splitQuery`: `loadMany` dispatch, the
polymorphic batched path, and the connection runtime.

For the substitution seam, the same `GraphQLContext` can carry an optional sink the generated
`redact` prefers over its own logger, falling back to `LOGGER` when absent. That keeps the default
behaviour for consumers who configure nothing and gives the rest a supported override without a new
dependency. Note the emitted code must compile at Java 17.

Open at Spec: whether the client-facing errors should all quote the one shared reference (better for
a support ticket, and the reading this goal assumes) or keep per-field ids while collapsing only the
log. Also whether `redact` should log at a lower level, or without the stack trace, for the
subsequent occurrences rather than staying silent.

## Tests

* An execution-tier test that a failing split-query batch over N parents yields exactly one log
  record and N GraphQL errors citing one reference. **There is no log-capture helper in the tree
  today**, so asserting "exactly one record" needs one built; that is part of this item's cost, and
  it is the assertion that actually pins the goal.
* `GraphQLOverHttpConformanceTest.redactionShapeMatchesFetcherPath` pins that the resource-side and
  fetcher-side redactions emit one wire shape. Whatever this item changes must keep that identity.

## Other solutions we've considered

* **Log inside the batch lambda, where the failure is singular, and stop logging in `redact`.** The
  failure is genuinely observed once there, so no memo is needed. Rejected as the primary shape
  because it moves the logging into every emitter that builds a batch lambda and leaves `redact`
  unable to log the non-batched failures it still handles, so the tree ends up with two logging
  sites to keep in agreement instead of one.
* **Leave it to consumers via logger configuration.** This is today's state. It is all-or-nothing and
  discards the reference-to-cause mapping, so it does not reach the goal.
* **Rely on the reference id becoming the OTel trace id (R423).** That would make the records
  *groupable*, since every field in a request would quote one id, which is a real improvement. It
  does not reduce the count: 400 stack traces remain 400 stack traces. The two items compose, and
  neither blocks the other.

## Provenance

Raised by a colleague reading Grafana logs for a 9.x consumer: requests using `@splitQuery` produced
far more log volume than expected. Investigation traced the volume to the fan-out described above,
confirmed the same construction on trunk by reading the generated fetchers and `ErrorRouter` under
`graphitron-sakila-example/target/generated-sources/`, and confirmed that trunk additionally removed
the injection seam 9.x had. The underlying slow query is the consumer's to fix; the log amplification
is ours.

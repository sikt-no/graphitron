---
id: R920
title: "a failed batch logs once, without its SQL, and a consumer can cap batch size"
status: Backlog
bucket: bug
priority: 3
theme: error-channel
depends-on: []
created: 2026-09-04
last-updated: 2026-09-28
---

# a failed batch logs once, without its SQL, and a consumer can cap batch size

## Goal

When one batched child query fails, a consumer's log backend should receive **one** record for it,
and every GraphQL error the request returns for that failure should quote the same reference id.
Today it receives one record per field that was waiting on the batch, each with its own reference id
and a full stack trace. A query selecting a split child under 400 parents turns a single timed-out
statement into 400+ stack traces that nothing groups together, so an operator reading the logs cannot
tell one failure from four hundred, and the request's own volume dwarfs everything else in the window.

The one record should not carry the SQL. A jOOQ `DataAccessException`'s message is the full rendered
statement, and for a batched child query that statement grows with the batch: a thousand parent keys
make a statement with thousands of bind markers, repeated in the message and again in the driver's
causes. The driver's own message can also carry row data (Oracle spells out the conflicting column
values of a unique-constraint violation), so it can carry personal data. The ERROR record should name
the failure by what locates it: the exception class, the SQL state, the vendor code and the driver's
message on one line, truncated. The full exception, SQL included, stays available at DEBUG.

Finally, a consumer should be able to bound how many keys one batched query carries. Today a batch is
as large as the request makes it, and one query holds every key. A cap makes a large batch several
bounded queries instead: shorter SQL, fewer bind variables, a plan the database can cost, and a
failure that takes down at most one chunk of fields rather than all of them. The default stays
unbounded, so a consumer who configures nothing sees no change.

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

## Evidence from a production trace

A second consumer report, investigated on 2026-09-28 through the consumer's Loki logs and Tempo
traces, shows all three parts of the goal in a single request. The consumer runs the 9.x line; the
construction on trunk is the same (see below). One production request on 2026-09-23 (trace
`0cb95eee1d3449c6df51ae472e37fece`) selected two many-to-one `@splitQuery` children, `sprak` and
`tekstkategori`, under 2,006 parents:

* The whole request issued **four** SELECTs, so batching worked. The `sprak` batch was one statement
  whose key list held 2,006 six-column tuples, about 12,000 bind variables. It ran 59.5 s and was
  cancelled with `ORA-01013`.
* java-dataloader delivered that one exception to all 2,006 fields. Each logged at ERROR with the
  full SQL in the exception message, and each got its own reference id.
* The fields were error-handled one after another, at 20 to 100 ms each. The `tekstkategori` batch
  could not start until all 2,006 `sprak` failures had been handled. It then ran 74 s, failed the
  same way, and fanned out again.
* The request took 397 s in total, of which about 250 s went on handling the two fan-outs. The
  gateway in front of the service had given up at 59 s, so none of that work reached a client.
* Over seven days this shape produced about 223,000 ERROR records in production, almost all
  `ORA-01013`, from requests of 3,700 to 4,000 records each.

So the amplification costs more than log volume. Handling the same failure once per field is slow
enough to hold back the next batch, and the unbounded batch is what made the statement slow in the
first place.

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
* Because the throwable is the trailing argument, slf4j renders its message as well as its stack
  trace. The fixed text of the log call carries no SQL, but a `DataAccessException`'s message is the
  rendered statement, so the SQL reaches the record anyway. `redact` treats every throwable alike;
  the SQL state and vendor code mappings exist only on the `@error` channels.

The batch has no bound for a separate reason. None of the emitters that build a DataLoader
(`DataLoaderFetcherEmitter`, `MultiTablePolymorphicEmitter`, `QueryNodeFetcherClassGenerator`) pass
`DataLoaderOptions`, so java-dataloader's default of an unlimited batch applies. The rows method the
batch lambda calls renders every key into one `VALUES` derived table (`BatchedRowsFragments`), whose
row count is bounded by nothing. And the generated `GraphitronContext` offers no method a consumer
could use to supply a limit. On trunk a plain many-to-one split child is keyed on the parent's FK
columns and the loader's cache merges parents sharing a value, so the six-column-tuple batch in the
evidence above would be smaller here. A one-to-many child, or a first hop with a filter, still keys
one entry per parent, and so does any list of distinct FK values, so the bound is still needed.

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

For the SQL, `redact` logs a one-line summary at ERROR in place of the raw throwable when the cause
chain holds a `java.sql.SQLException`: exception class, SQL state, vendor code, and the driver's
message with whitespace collapsed and truncated to a fixed length. Without a driver exception it logs
the jOOQ message truncated. The full throwable follows at DEBUG under the same reference id. The
summary is what the consumer's sink receives by default, so a consumer who must keep row data out of
the log entirely replaces it there rather than subclassing anything.

For the cap, the generated `GraphitronContext` gains a default method returning the maximum keys per
batch for a field, reading the `DataFetchingEnvironment` so a consumer can vary it by field, with a
value below 1 meaning unbounded, the default. Every emitter that creates a DataLoader passes
`DataLoaderOptions` built from it, and java-dataloader then splits an oversized batch into several
calls of the batch lambda. The rows method and the `VALUES` rendering do not change. The loader name
already identifies the field, so one loader per field keeps one option set.

The 9.x line carries a working version of all three parts, on branch `graphitron-9-head` in commit
`e7b80004e` ("Log top-level errors once per exception and cap DataLoader batches"). It keys a
`WeakHashMap` on throwable identity inside its `TopLevelErrorHandler`, adds
`describeDataAccessException` for the summary, and adds `GraphitronContext.getDataLoaderMaxBatchSize`.
Its shape is a reference for the Spec, not a template: trunk has no handler to subclass, which is why
the memo goes in per-request state and the override goes through the sink.

Open at Spec: whether the client-facing errors should all quote the one shared reference (better for
a support ticket, and the reading this goal assumes) or keep per-field ids while collapsing only the
log. Also whether `redact` should log at a lower level, or without the stack trace, for the
subsequent occurrences rather than staying silent. Whether the batch cap belongs in this item or
splits into its own at Spec: it answers the same incident and composes with the memo (a cap turns one
failure into at most one record per chunk), but it touches the loader emitters rather than
`ErrorRouter`, so it can ship independently. Whether a cap should also be settable at build time, as
a default the context method overrides.

## Tests

* An execution-tier test that a failing split-query batch over N parents yields exactly one log
  record and N GraphQL errors citing one reference. **There is no log-capture helper in the tree
  today**, so asserting "exactly one record" needs one built; that is part of this item's cost, and
  it is the assertion that actually pins the goal.
* `GraphQLOverHttpConformanceTest.redactionShapeMatchesFetcherPath` pins that the resource-side and
  fetcher-side redactions emit one wire shape. Whatever this item changes must keep that identity.
* A unit test that the ERROR summary of a `DataAccessException` whose message and causes contain SQL
  names the SQL state and vendor code and contains no part of the statement, and that an overlong
  driver message is truncated.
* An execution-tier test that with a cap of `k`, a split child under `n > k` parents runs
  `ceil(n / k)` child queries and still returns every parent's slice; and with no cap, one query.
  Counting statements needs a jOOQ `ExecuteListener` in the test, not the log.

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

Amended 2026-09-28 after a second 9.x consumer's production trace (the evidence section above). It
showed that the amplification also costs wall-clock, that the SQL in the record is a size and privacy
problem of its own, and that the size of the batch was what made the statement slow. The SQL summary
and the batch cap were added to the goal then.

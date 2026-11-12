---
id: R920
title: "a failed batch logs once, without its SQL, and a consumer can cap batch size"
status: Ready
bucket: bug
priority: 3
theme: error-channel
depends-on: []
created: 2026-09-04
last-updated: 2026-11-12
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
causes. Nor should it carry the driver's detail lines, which is where a driver spells out row data:
PostgreSQL's `Detail:` line gives the conflicting key values of a unique-constraint violation, and
Oracle 23 adds them on a second line of its own. The ERROR record should say what failed and where,
on one line: the path of the field that failed, the exception class, the SQL state, the vendor code
and the first line of the driver's message, truncated. That is a reduction, not a guarantee that no
value reaches the log: a driver can put one in its first line (PostgreSQL's
`invalid input syntax for type integer: "abc"`), and a consumer who needs the guarantee writes a
`FetcherErrorLog`, as today. The full exception, SQL included, stays available at DEBUG.

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

A consumer who replaces the logging altogether already can: a `FetcherErrorLog` set on the runtime
receives every unmatched exception in place of Graphitron's line. After this item it receives each
failed batch once too, so a consumer's own log inherits the collapse without doing anything.

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
  exceptionally with that same `Throwable` instance. `FrameEmitter.child`'s batch lambda differs in
  form only: it catches what its body throws and returns a failed future, and java-dataloader hands
  that future's exception to every per-key future just the same.
* The fetcher built by `DataLoaderFetcherEmitter.build` attaches the disposition per fetcher
  invocation: `loader.load(key, env).thenApply(...).exceptionally(t -> ErrorRouter.surfaceClientErrorOrRedact(t, env))`,
  from `TypeFetcherGenerator.asyncWrapTail` (and its twins on `MultiTablePolymorphicEmitter` and
  `FrameEmitter.child`). Each awaiting field runs its own `.exceptionally`.
* The `t` each field's `.exceptionally` receives is **not** the shared instance. The per-key future
  completes with the batch's exception, but the dependent `thenApply` stage completes with a fresh
  `CompletionException` wrapping it, one per field. The shared instance is the wrapper's cause. Any
  memo keyed on `t` itself would miss every time; this is why the claim record R1087 added keys on
  the claimed chain link, not on `thrown`.
* `surfaceClientErrorOrRedact` falls through to `redact`, emitted by
  `ErrorRouterClassGenerator.redactBody`, which mints a fresh `UUID.randomUUID()` and logs
  `"Unmatched exception in fetcher; correlation id = {}"` with the throwable as the trailing
  argument, so slf4j renders a full stack trace on every one of them.
* No batch lambda logs. Four of the five have no catch at all, and the one in `FrameEmitter.child`
  only turns the throw into a failed future, so there is no single point where the failure is
  observed once.
* Because the throwable is the trailing argument, slf4j renders its message as well as its stack
  trace. The fixed text of the log call carries no SQL, but a `DataAccessException`'s message is the
  rendered statement, so the SQL reaches the record anyway. `redact` treats every throwable alike;
  the SQL state and vendor code mappings exist only on the `@error` channels.

The claim path already collapses. R1087 made `ErrorRouter.recordClaim` mint a claimed source's
reference once per request and call `FetcherErrorLog.matched` once, so a batch failure an `@error`
handler catches is logged once today. Only the unmatched path (`redact`) and the settle path
(`settleFailure`) still mint and log per call. `FetcherErrorResponse.reference`'s javadoc already
promises "once per exception and request"; the unmatched path is where that promise is not kept.

The batch has no bound for a separate reason. None of the five sites that build a DataLoader
(`DataLoaderFetcherEmitter.build`, the two in `MultiTablePolymorphicEmitter`,
`QueryNodeFetcherClassGenerator`, `FrameEmitter.child`) pass `DataLoaderOptions`, so
java-dataloader's default of an unlimited batch applies. The rows method the batch lambda calls
renders every key into one `VALUES` derived table (`BatchedRowsFragments`), whose row count is
bounded by nothing. There is also no consumer surface to put a limit on: the generated
`GraphitronContext` is a `sealed` interface with one permitted implementation, so a consumer cannot
override a method on it, and the configuration a consumer does own lives on `GraphitronRuntime`
(`withFetcherErrorLog`, `withFetcherErrorResponse`). On trunk a plain many-to-one split child is keyed
on the parent's FK columns and the loader's cache merges parents sharing a value, so the
six-column-tuple batch in the evidence above would be smaller here. A one-to-many child, or a first
hop with a filter, still keys one entry per parent, and so does any list of distinct FK values, so
the bound is still needed.

`TenantConnections.logFanOutFailure` (emitted by `ConnectionRuntimeClassGenerator`) is the same
shape at lower severity: it mints a fresh reference per failed tenant, so its volume is bounded by
tenant count rather than by batch size, and each tenant's failure is its own exception. It needs the
SQL summary (it logs the cause the same way) but not the memo.

## Decisions

The forks the Backlog body left open, settled here so the implementation has one shape.

* **Every client error for one failure quotes one reference.** The 2,006 field errors of the trace
  above would all carry the reference the one log record carries. That is what lets a support
  ticket quoting any of them find the record, and it is what `FetcherErrorResponse.reference`
  already documents ("once per exception and request") and what the claim path already does. Each
  field keeps its own error entry with its own path; only the reference is shared.
* **Later occurrences log nothing.** Not a shorter line, not a lower level: the client errors carry
  every affected path, and the log's job is the cause, once. A DEBUG line per later field would put
  the amplification back for anyone who turns DEBUG on to diagnose exactly this.
* **The SQL summary replaces the stack trace only for a database failure.** When the cause chain
  holds a `java.sql.SQLException`, the ERROR record is the one-line summary and the full throwable
  goes to DEBUG. Any other exception keeps today's ERROR line with its stack trace: a bug in a
  consumer's service or condition needs the trace to be found, and its message is not a rendered
  statement. jOOQ's own non-driver exceptions (`TooManyRowsException` and the like) carry no SQL,
  so they keep the trace too.
* **The summary takes the driver's first line, not its whole message.** The first line names the
  failure; the lines after it are where PostgreSQL (`Detail:`, `Where:`) and Oracle 23 put the
  values involved. Taking the first line keeps those out of the ERROR record without generated code
  naming any driver's classes. It is a reduction and the goal says so: the interface javadoc keeps
  its warning that a message can carry row data, and `FetcherErrorLog` stays the seam for a
  consumer who must sanitise.
* **The summary names the field.** Without the stack trace and the SQL, the class, state and code
  only classify a failure, and every timeout in a service would read the same. The line carries
  the path of the field that logged it, so an operator who has only the log knows which query to
  look at.
* **The batch cap ships in this item, as its own commit.** It answers the same incident and
  composes with the memo (a cap turns one failure into at most one record per failed chunk), but it
  touches the loader emitters and the runtime rather than `ErrorRouter`, so the two commits are
  independent and either can land first.
* **The cap is runtime configuration only, with no build-time default.** Consumer configuration
  lives on `GraphitronRuntime`; a Mojo parameter would be a second place to set the same number,
  and nothing about a batch size is known at build time that the runtime does not know better.

## Implementation

Two independent commits. Each passes the verification build on its own.

### Log each failure once, without its SQL

All in `ErrorRouterClassGenerator`, plus one call site in `ConnectionRuntimeClassGenerator`.

* **Key on the failure, not the wrapper.** A new private helper `failureOf(thrown)` returns
  `thrown` with every leading `CompletionException` and `ExecutionException` that has a non-null
  cause stripped. That is the instance java-dataloader shared across the batch.
* **Extend the claim record rather than adding a holder.** `Claims` (built by `buildClaimsHolder`)
  gains a second identity map, failure to unmatched reference, with synchronized
  `unmatchedReferenceOf(failure)` and `assignUnmatchedReference(failure, reference)` (a
  `putIfAbsent` answering whether this call set it), mirroring `referenceOf` / `assignReference`.
  It is a map of its own rather than a share of `references`, because `recordClaim` reads "has a
  reference" as "`matched` was already called": an unmatched mint in that map would silently
  suppress a later `matched`. The keys seldom coincide anyway (a claim keys on the claimed chain
  link, often the `SQLException`; the unmatched path keys on what `failureOf` returns). The class
  javadoc's "one per-request holder keyed on throwable identity in the error channel" stays true.
* **One reporting helper for both unmatched dispositions.** A new private
  `reportUnmatched(thrown, env, what)` does, in order: compute `failure = failureOf(thrown)`; get or
  create the request's `Claims` (none when `env` or its context is null, and then everything below
  behaves exactly as today); if `unmatchedReferenceOf(failure)` is set, return it and log nothing;
  else mint with `newReference(thrown, env)` and `assignUnmatchedReference(failure, minted)`. The
  call that sets the reference is the one that logs, handing `thrown` to
  `toConfiguredLog(... log.unmatched(thrown, reference, env))` or writing the default line; a call
  that loses a race takes the winner's reference and logs nothing. So the field that logs is the
  field whose reference stands, and "the first field's `env`" is literally the logged one. A lost
  race costs one discarded `FetcherErrorResponse.reference` call, never a second record. Minting
  stays outside the lock, so no consumer code runs under it. `redact` and `settleFailure` call the
  helper in place of their `newReference` + log pair, with their existing line prefixes as `what`.
  The client message (`unmatchedMessage`) is still computed per field, with the shared reference.
* **Which throwable the consumer sees.** `FetcherErrorResponse.reference` and
  `FetcherErrorLog.unmatched` keep receiving `thrown` as the winning field caught it, wrapper
  included, exactly as today; `failureOf` is the memo's key, not a new argument. Their javadoc says
  so, and says the call is once per failure per request.
* **The default line.** A new public static `ErrorRouter.describe(Throwable, DataFetchingEnvironment)`
  returns `Optional<String>`, the one-line summary when the chain holds a `java.sql.SQLException`
  and empty otherwise, matching the shape of `TenantConnections.mountCause`. It is public because
  it is useful to a `FetcherErrorLog` author too, and the error-channel how-to says so. The summary
  is `path = <path>; <failure class>: <driver class> [<SQL state>/<vendor code>] <first line>`: the
  field's path from the router's private `pathOf(env)` (`unknown` without an environment), the
  class of `failureOf(thrown)`, the class of the first `SQLException` on the chain, its SQL state
  and vendor code, and the first non-blank line of its message, cut at a fixed bound (240
  characters, ending in `...` when cut). The walk stops at a cycle or after a fixed depth. With a
  summary, the default branch logs `<what>; correlation id = {}; {}` at ERROR with no throwable
  argument, then `<what>; correlation id = {}` at DEBUG with the throwable. Without one it logs
  today's ERROR line, throwable included. A configured `FetcherErrorLog` still receives the raw
  throwable and never sees the summary. The path is part of `describe` rather than of each caller
  so the line has one spelling and `pathOf` stays private: `TenantConnections` reaches the router
  through its public statics, as it does today. A settle failure whose environment was not
  captured reads `path = unknown`; the client's error still carries the path.
* **The tenant fan-out.** `logFanOutFailure`'s default line for a failed tenant uses
  `ErrorRouter.describe(failed.cause(), env)` the same way (summary at ERROR, throwable at DEBUG,
  or today's line). It keeps a reference per tenant and takes no memo: each tenant's failure is
  its own exception.
* **Javadoc that changes meaning.** In `ConnectionRuntimeClassGenerator.fetcherErrorLog`:
  `FetcherErrorLog.unmatched` changes from "once per fetcher exception" to once per failure per
  request however many fields it failed, with the logging field's `env`, mirroring `matched`; and
  the interface's class javadoc, which says the default line includes "message and cause chain",
  says that a database failure's default line is the summary at ERROR with the full exception at
  DEBUG. `redact`'s and `settleFailure`'s javadoc say the reference is shared. `tenantFailed` is
  the one exception to "once per failure per request" and `unmatched`'s javadoc names it: its
  default hands each failed tenant to `unmatched`, once per tenant, as today.

### Let a consumer cap batch size

* **A new consumer hook.** `ConnectionRuntimeClassGenerator` emits `BatchSizeLimit` beside
  `FetcherErrorLog` in the `CONNECTION_RUNTIME` unit set: a pure `@FunctionalInterface` with
  `int maxBatchSize(DataFetchingEnvironment env)` and nothing else, where a value below 1 means
  unbounded. It is asked once per loader per request, with the environment of the first field that
  needed the loader, so a consumer can vary the limit by field (`env.getExecutionStepInfo()`,
  `env.getFieldDefinition()`). Its `ClassName` comes from a `GeneratedUnits`-backed resolver beside
  `ErrorRouterClassGenerator.fetcherErrorLogClass`, never from `outputPackage + ".schema"` at a site.
* **Runtime configuration, the way the other hooks are configured.** `GraphitronRuntime` gains a
  `batchSizeLimit` field, `withBatchSizeLimit(BatchSizeLimit)` and `batchSizeLimit()` returning an
  `Optional`, and its copy constructor gains the parameter (so `withFetcherErrorLog` and
  `withFetcherErrorResponse` carry it over); its class javadoc, which lists the callbacks it holds,
  names the third. `GraphitronConnectionInstrumentationGenerator` publishes it into the request's
  `GraphQLContext` under its class, next to the two it publishes today. The escape-hatch engine
  reads the same context entry, put there by the caller.
* **One lookup, on the router.** A new public static `ErrorRouter.loaderOptions(env)` returns
  `DataLoaderOptions`: no entry gives `DataLoaderOptions.newOptions().build()`, a positive answer
  adds `setMaxBatchSize(n)`, and a throw goes through the router's existing private `configured`,
  `contained` and `pathOf` (one ERROR line naming the hook's class, the class of what it threw and
  the field's path; unbounded options stand; only a `VirtualMachineError` propagates). This keeps the
  existing split, where hook interfaces carry only the consumer's contract and Graphitron's calls
  into hooks are statics on `ErrorRouter` (`toConfiguredLog`, `newReference`), and it keeps every
  helper private. The cost is the name: this is the first hook that is not about errors. A second
  non-error hook is the point at which `configured`, `contained` and `answer` move into a unit of
  their own; not now.
* **One way to build a loader.** A new render fragment, `no.sikt.graphitron.render.LoaderConstruction`,
  renders the whole construction,
  `DataLoaderFactory.<newDataLoader|newMappedDataLoader>(<lambda>, ErrorRouter.loaderOptions(<env>))`.
  It has two entry points, `positional` and `mapped`, each taking the lambda's `CodeBlock`, the name
  of the environment variable and the router's `ClassName`, the way `ErrorDispatchFragments` takes
  the router. `LoaderRegistration` is in neither signature: `FrameEmitter` lives in
  `no.sikt.graphitron.emit`, which `PackageImportDirectionTest` keeps from naming the leaf model.
  So `DataLoaderFetcherEmitter.build` chooses the entry point from `registration.container()`, the
  three sites that have always built a positional loader call `positional`, and `FrameEmitter.child`
  calls `mapped` with `runtime.errorRouter()`. `EMIT_ALLOWANCES` in that test gains the class, its
  reason being that a loader is built one way, with the request's batch-size options, whichever
  emitter builds it (the map holds ten entries, `Map.of`'s limit, so it becomes `Map.ofEntries`).
  The five sites named under "Why it happens" call the fragment inside their `computeIfAbsent`
  lambda (so the lookup runs once per loader), and their own `DATA_LOADER_FACTORY` /
  `LOADER_FACTORY` constants are deleted, with the unused one on `TypeFetcherGenerator`. A loader
  built without the cap then has to name `DataLoaderFactory` anew, and `GeneratedSourcesLintTest`
  gains a rule rejecting a one-argument `newDataLoader(` / `newMappedDataLoader(` call in generated
  sources. That rule reads the sakila example's output, so it catches a sixth site only once the
  example exercises it; the deleted constants are what a new site meets first.
* **Compile-graph wiring.** `EmitPlan.connectionRuntime` lists the `CONNECTION_RUNTIME` refs by
  hand and gains `BatchSizeLimit`'s. `PlanCompileGraph.addFixedKindEdges` gains the runtime to
  `BatchSizeLimit` and instrumentation to `BatchSizeLimit` edges in the `CONNECTION_RUNTIME` and
  `CONNECTION_INSTRUMENTATION` arms, beside the log and response edges. Four of the five loader
  sites emit into fetchers units, which reach the cap only through `ErrorRouter` and already have
  a frozen-scaffold edge into it. The fifth does not: `QueryNodeFetcher` is the
  `QUERY_NODE_FETCHER` global unit, outside the blanket, and names no router today, so its arm
  gains a precise edge to `ERROR_ROUTER` beside the three it has. The edge is precise because the
  reference is real in every build that has the unit: `dispatchNodes`, which builds the loader, is
  emitted unconditionally. The router itself is exempt as an oracle source, so its new reference
  to `BatchSizeLimit` needs no edge, but
  `IncrementalCompileHarnessTest.frozenScaffoldSources_referenceOnlyThePinnedUnits` pins what the
  router references and gains `schema.BatchSizeLimit`. The exemption stays sound, which is what
  that pin asks to be checked before its list grows: `BatchSizeLimit` is one abstract method on an
  interface emitted the same in every build, so the member the router calls varies with neither
  schema nor configuration. `FrameEmitter.Runtime` needs no new component, since it already carries
  the router. `IncrementalCompileHarnessTest`'s oracle leg pins the edges.
* **Chunking.** java-dataloader 6.0.0 (the version graphql-java 25.0 brings in) then calls the batch
  lambda once per chunk of at most `n` keys. The rows methods, `BatchedRowsFragments` and the
  key-context handling do not change: each chunk's lambda call sees its own keys and key contexts,
  and `FrameEmitter`'s `getKeyContextsList().get(0)` reads that chunk's first.

## User documentation (first-client check)

For `docs/manual/how-to/error-channel.adoc`, replacing the redact code block's surrounding prose and
the first paragraphs of "Choosing how unmatched exceptions are logged":

> Every other unmatched exception runs the redact path. It is logged once per request, however
> many fields it failed: a batched child query that fails under 400 parents produces 400 field
> errors, one log record, and one reference that all 400 errors quote. Two batches that fail
> separately are two records with two references. (A `@service` of yours that rethrows one cached
> exception wrapped in a `CompletionException` for two batches reads as one failure.)
>
> When the exception comes from the database driver, the ERROR record is one line: the path of the
> field that failed, the exception class, the driver exception's class, its SQL state and vendor
> code, and the first line of the driver's message, shortened. The SQL statement is not in it; for
> a batched query that statement grows with the batch. Nor are the driver's detail lines, where
> PostgreSQL and Oracle name the values involved. A driver can still put a value in its first
> line, so configure a `FetcherErrorLog` if nothing from the database may reach your log. The full
> exception, statement included, is logged at DEBUG under the same reference. Any other exception
> is logged at ERROR with its stack trace, as before.
>
> A `FetcherErrorLog` you configure receives the full exception instead, once per request in the
> same way, with the environment of the field that logged it. To write the same one-line summary
> yourself, call `ErrorRouter.describe(thrown, env)`, which is empty for an exception that did not
> come from the driver.

For `docs/manual/explanation/batching-model.adoc`, a new section "Bounding a batch":

> A batch is as large as the request makes it: a split child under 2,000 parents is one statement
> with 2,000 keys. To bound it, give the runtime a `BatchSizeLimit`:
>
> ```java
> var runtime = Graphitron.runtime(dataSource, SQLDialect.ORACLE)
>     .withBatchSizeLimit(env -> 500);
> ```
>
> A batch over the limit runs as several statements of at most that many keys, one after another,
> and each parent still gets its own rows. For a `@service` child it is your method that is called
> once per chunk of keys. A statement that fails takes down only the fields whose keys were in it,
> and the chunks after it still run, so a request whose every chunk times out waits for each in
> turn. The limit is asked once per batch with the first field's environment, so it can
> depend on the field; return 0 for no limit, which is also what you get without a
> `BatchSizeLimit`. On the escape-hatch engine, put it into each request's context under
> `BatchSizeLimit.class`.

Both read simply, so the design stands.

## Tests

All new tests live in `graphitron-sakila-example`; all but one are execution tier. Log capture is a small test
helper built on logback's `ListAppender` (logback-classic is already on that module's test
classpath), attached to the generated `ErrorRouter` logger for the test's duration and able to
lower its level to DEBUG; it is the assertion that actually pins the goal.

* **One record, one reference, many errors.** `{ films { durabilityError } }` over the seeded films:
  `Film.durabilityError` is an existing batched child `@service` (`DurabilityErrorService.synthesize`)
  that throws once per batch. Assert N field errors, every message quoting the same reference,
  exactly one ERROR event on the `ErrorRouter` logger, carrying that reference and the throwable
  (the non-database path keeps its stack trace).
* **Two failures, two records.** The same field under two aliases runs two loaders, so two
  batches: assert two ERROR events and two distinct references, each quoted by its own alias's
  errors.
* **A configured log is called once.** With a `FetcherErrorLog` lambda counting calls, the first
  query above calls `unmatched` once, with a reference equal to the one the errors quote, and the
  `ErrorRouter` logger records nothing.
* **The database summary.** A new fixture batched child `@service` on `Film` whose method runs one
  statement binding every key and a marker text, and fails at execution casting the marker (a
  truncated JSON literal) to `json`. PostgreSQL answers SQL state `22P02` with
  `invalid input syntax for type json` on the first line and the marker on a later `Where:` line,
  so the failure carries a value the way a unique violation's `Detail:` line does, without a
  write. Assert one ERROR event, no throwable on it, its message naming the failing field's path,
  `22P02` and `invalid input syntax for type json`, and containing neither the statement text nor
  the marker; and, with the logger at DEBUG, one DEBUG event under the same reference carrying the
  throwable, whose message does contain the marker.
* **Truncation and the walk.** A `@UnitTier` test of the generated `ErrorRouter.describe`, in
  memory with no database (the precedent for a unit-tier test against a generated class in this
  module is `ScatterSingleByIdxTest`): a jOOQ `DataAccessException` whose message is a long SQL
  statement, wrapping a `SQLException` whose message is an overlong first line followed by a
  `Detail:` line, gives a summary that names state and vendor code, contains no part of the
  statement and nothing of the second line, and stops at the bound; a null environment gives
  `path = unknown`; a chain without a `SQLException` gives an empty `Optional`; a cyclic cause
  chain terminates.
* **The cap.** A test using a jOOQ `ExecuteListener` counting statements, as
  `BatchCountsExecutionTest` does, over an existing list-valued `@splitQuery` child under `n`
  parents: with `withBatchSizeLimit(env -> k)` and `n > k`, `ceil(n / k)` child statements, and the
  response equal to the unbounded one; without a limit, one child statement. A limit that throws
  leaves the response equal to the unbounded one and logs one ERROR line naming `BatchSizeLimit`.
  The escape-hatch engine honours a limit put into the request context. One case with the database
  fixture under a cap pins that a failure is one record per failed chunk.
* **Unchanged.** `GraphQLOverHttpConformanceTest.redactionShapeMatchesFetcherPath` and
  `computedRedactionMatchesFetcherPath`, `FetcherErrorResponseExecutionTest`, and
  `TenantFanOutExecutionTest`'s tenant-failure cases pass unchanged. The generator unit tests pin
  structure only (`ErrorRouterClassGeneratorTest`, `ConnectionRuntimeClassGeneratorTest`: the new
  methods and the new unit exist), with no assertions on generated method bodies.
* **Wiring and the one construction path.** `IncrementalCompileHarnessTest`'s oracle leg passes
  with the new unit and edges, the node fetcher's edge to the router among them; its
  frozen-scaffold pin is edited to list `schema.BatchSizeLimit` under the router;
  `PackageImportDirectionTest` passes with the `LoaderConstruction` allowance present and in use;
  and the new `GeneratedSourcesLintTest` rule finds no one-argument loader construction in the
  sakila example's generated sources.

## Other solutions we've considered

* **Log inside the batch lambda, where the failure is singular, and stop logging in `redact`.** The
  failure is genuinely observed once there, so no memo is needed. Rejected as the primary shape
  because it moves the logging into every emitter that builds a batch lambda and leaves `redact`
  unable to log the non-batched failures it still handles, so the tree ends up with two logging
  sites to keep in agreement instead of one.
* **Leave the collapse to the consumer's `FetcherErrorLog`.** A consumer can already dedupe inside
  their own log. Rejected because the default line, which every consumer gets until they write one,
  keeps the flood; because each consumer would rediscover that the shared instance hides behind a
  per-field `CompletionException`; and because the client errors would still quote one reference
  each, which only Graphitron can change.
* **Port the 9.x shape.** The 9.x line carries all three parts (branch `graphitron-9-head`, commit
  `e7b80004e`): a `WeakHashMap` on throwable identity inside a `TopLevelErrorHandler`,
  `describeDataAccessException`, and `GraphitronContext.getDataLoaderMaxBatchSize`. Trunk has no
  handler to subclass, and its `GraphitronContext` is sealed, so a consumer cannot override a method
  on it. The memo goes into the per-request claim record and the cap goes onto `GraphitronRuntime`,
  where the consumer's other hooks already are. The summary's content carries over, with the
  driver's first line in place of its whole message and the field's path added.
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

## Reviewer findings

### Round 1: Spec → Ready (session_01FY5UvtV99jaTh2xtJxqGNK, 2026-11-11)

Verdict: withhold. The three consumer-visible changes read cleanly from the goal alone, the memo
extends the claim record rather than standing a holder beside it, the cap goes where the other
hooks are, and every class, method, test and doc section the body names exists as named. One
finding on question one, one on question two.

**Finding 1 (question one: goal communication and viability). The summary's content does not
answer either reason the goal gives for it.** Two parts, one decision.

* The goal says the driver's message "can carry personal data" and, in the next sentence, puts the
  driver's message on the ERROR line. Truncation does not remove it: PostgreSQL's
  `PSQLException.getMessage()` for a unique violation is the error text followed by
  `Detail: Key (email)=(a@b.no) already exists.`, well inside 240 characters once whitespace is
  collapsed. So a reader cannot tell whether the item's outcome is "no SQL at ERROR" or "no row data
  at ERROR", and the Done gate will not be able to tell whether row data in the summary is a defect.
  State which. If row data in the default line stays accepted (the interface javadoc already warns
  of it and names `FetcherErrorLog` as the sanitising seam), say so in the goal and drop personal
  data as a reason; if it does not, the summary cannot carry the message verbatim.
* The goal says the record should "name the failure by what locates it", and the summary is the
  exception class, the driver class, the SQL state, the vendor code and the message. Those classify
  a failure; none says which field or statement failed. Today the stack trace and the SQL do that.
  For the incident the item is built on, every record would read the same `ORA-01013` line with a
  different reference, and finding the query means turning DEBUG on in production and waiting for
  it to happen again. Either the line carries something that locates the failure (the router
  already has `pathOf(env)` for the logging field) or the body says why class, state and code are
  enough for an operator who has only the log.

The tests should then pin whichever answer is given: the database fixture fails with
`division by zero`, whose message carries no row data and whose record nothing checks for a
location, so neither part is observed today.

_Response (session_01FY5UvtV99jaTh2xtJxqGNK, 2026-11-11; the session that wrote this round revised
the body at the user's request, so the next pass belongs to a different session)._ Both parts are
decided, as two new entries under "Decisions". Row data: the summary takes the first line of the
driver's message instead of the whole of it, which leaves out PostgreSQL's `Detail:` and `Where:`
lines and Oracle 23's second line, and the goal now says plainly that this is a reduction and not
a guarantee, with `FetcherErrorLog` as the seam for a consumer who needs one. Location: the
summary opens with the logging field's path. `describe` takes the environment and composes the
path itself, so the line has one spelling and `pathOf` stays private. The database fixture now
fails casting a marker text to `json` (`22P02`, the marker on the `Where:` line, checked against
PostgreSQL 16 and the driver's `ServerErrorMessage`), and its test asserts the path is present and
the marker absent at ERROR and present at DEBUG; the unit-tier case covers the second-line drop
and the null environment. Changed: Goal, Decisions, "The default line", "The tenant fan-out", the
error-channel draft, Tests.

**Finding 2 (question two: fit). "Compile-graph wiring" and "One way to build a loader" make
claims the tree contradicts.** None changes the design; each changes what the implementer builds,
so they are the author's to restate.

* "Fetcher units reach the cap only through `ErrorRouter`, which they already have a
  frozen-scaffold edge into ... so no fetcher-side edge is added" holds for four of the five sites.
  `QueryNodeFetcher` is not a fetchers unit: it is `GlobalUnitKind.QUERY_NODE_FETCHER`, its arm in
  `PlanCompileGraph.addFixedKindEdges` declares three precise edges (encoder, context, entity
  dispatch), the blanket in `addBlanketEdges` covers `plan.typeUnits().fetchersUnits()` only, and
  `QueryNodeFetcherClassGenerator` names no router today. `ErrorRouter.loaderOptions(idEnv)` is a
  new reference, and leg 2 of the oracle reports it as a gap until that arm gains an edge.
* `IncrementalCompileHarnessTest.frozenScaffoldSources_referenceOnlyThePinnedUnits` pins
  `ErrorRouter`'s references to exactly four units. `BatchSizeLimit` is a fifth, so that test is
  edited, not passed, and its javadoc asks for a check that the new target's members are stable
  across schemas and configurations before the list grows. The body should name the pin and make
  that argument (a one-method interface emitted the same in every build).
* The shared loader fragment has no stated home, and `PackageImportDirectionTest` constrains it.
  `FrameEmitter` is in `no.sikt.graphitron.emit`, which may name neither `rewrite.generators` nor
  the leaf model, and `render` only through `EMIT_ALLOWANCES`, each entry with its reason and none
  unused. A fragment built "from the container kind" takes `LoaderRegistration.Container`, which
  `FrameEmitter` cannot name. Say where the fragment lives, what `emit` is allowed to call on it,
  and the allowance's reason.

_Response (same session and date)._ All three restated in "One way to build a loader" and
"Compile-graph wiring", and in the last entry under Tests. The `QUERY_NODE_FETCHER` arm gains a
precise edge to `ERROR_ROUTER`, precise because `dispatchNodes` is emitted in every build that has
the unit. The frozen-scaffold pin gains `schema.BatchSizeLimit`, with the stability argument its
javadoc asks for. The fragment is `no.sikt.graphitron.render.LoaderConstruction` with `positional`
and `mapped` entry points that take no `LoaderRegistration`, after the `ErrorDispatchFragments`
precedent, and `EMIT_ALLOWANCES` gains it with its reason.

Non-blocking, for the author to take or leave:

* With a cap, chunks run one after another and a failed chunk does not stop the next, so a request
  whose statement times out can take chunk count times the timeout. Worth a sentence in "Bounding a
  batch".
* For a batched `@service` child the cap multiplies calls to the consumer's method, not statements;
  the manual text says "several statements".
* `FetcherErrorLog.tenantFailed` defaults to `unmatched`, so "once per failure per request" in the
  new `unmatched` javadoc has the per-tenant call as an exception.

_Response (same session and date)._ All three taken: two sentences in the "Bounding a batch" draft
(a `@service` child's method is called once per chunk; later chunks still run after a failed one),
and `unmatched`'s javadoc names `tenantFailed` as the per-tenant exception.

_Architect consult before this handover._ The `principles-architect` found the first-line rule,
the node-fetcher edge, the pin argument and the fragment's home sound. It raised three things. It
objected to an earlier draft that made `pathOf` package-private for `TenantConnections`, as a
second kind of coupling beside the router's public statics; taken, the path moved into `describe`.
It noted the lint rule sees only the sakila example's output; taken, the body now says so. It
noted that `docs/manual/reference/runtime-api.adoc` states none of the `GraphitronRuntime` hooks,
the two existing ones included, and that whether hooks belong on that page is unwritten; not
acted on here, raised with the user as a question for Alf and Filip.

### Round 2: Spec → Ready (session_01FY5Uvrd4MaxcxdSzZ52C47, 2026-11-12)

Verdict: sign off. Both questions are answered.

Question one. The goal reads on its own as three consumer-visible changes: a failure that reaches
many fields is one log record and one reference that every field's error quotes; a database
failure's default record is a one-line summary with the full exception at DEBUG; and a runtime
hook bounds the keys in one batched query, unbounded by default. Each is reachable. The claims the
body makes about java-dataloader 6.0.0 were run against the jar rather than read: each field's
`thenApply` tail receives its own `CompletionException` whose cause is the one shared instance,
for a thrown batch function and a failed future alike; with `setMaxBatchSize` the batch function
is called once per chunk with that chunk's key contexts, for positional and mapped loaders; and a
failed chunk fails only its own keys while later chunks run.

Question two. The memo is a second map on the existing claim record, the hook sits where the two
existing hooks sit and is published the way they are, the loader fragment follows
`ErrorDispatchFragments`, and the compile-graph edges and both pins match the tree as it stands.
Round 1's two findings are answered in the body.

Non-blocking, for the implementer and the Done reviewer:

* "Either can land first" has one exception: the cap's last test case (one record per failed
  chunk) needs the first commit's database fixture and memo, so the log commit lands first or that
  case moves with it.
* No page under `docs/manual/reference/` states any `GraphitronRuntime` hook, and this item adds a
  third and changes a default log line. The Done gate's reference check will ask where the rule is
  written, so the question the handover left with Alf and Filip wants an answer before In Review.

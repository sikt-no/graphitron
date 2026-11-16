---
id: R920
title: "a failed batch logs once, without its SQL, and a consumer can cap batch size"
status: In Progress
bucket: bug
priority: 3
theme: error-channel
depends-on: []
created: 2026-09-04
last-updated: 2026-11-16
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

`OwnedConnections.logFanOutFailure` (emitted by `ConnectionRuntimeClassGenerator`) is the same
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
* **The hooks are stated on the runtime API reference page.** `docs/manual/reference/runtime-api.adoc`
  gains "Consumer hooks on `GraphitronRuntime`", stating all three hooks, the two that predate
  this item included, since no reference page stated any. The Reference check wants a generated
  surface written on a reference page, and that page's scope is what is emitted unconditionally;
  a table of one hook out of three would be a partial inventory. This is the implementer's
  reading of the question Round 2 left open, not a ruling: it is Alf's and Filip's to redirect.

## Implementation

Two commits, each collapsed here to where it shipped; "Implementation notes" below carries what a
reader would otherwise have to work out.

* **Log each failure once, without its SQL** (`failureOf`, the unmatched map on `Claims`,
  `reportUnmatched`, `ErrorRouter.describe`, the tenant fan-out's summary line, the javadoc that
  changed meaning): shipped at `7a456e46`.
* **Let a consumer cap batch size** (`BatchSizeLimit`, `GraphitronRuntime.withBatchSizeLimit`, its
  publication by the instrumentation, `ErrorRouter.loaderOptions`, `LoaderConstruction` at the five
  loader sites, the compile-graph edges and pins): shipped at `4d86d2ca`.
* The reference page's "Consumer hooks on `GraphitronRuntime`" shipped at `e9929dcb`, and the
  architect's test tightening at `2c455634`.

## User documentation (first-client check)

The drafts shipped with the commits above; "Where the drafts landed" under "Implementation notes"
names the pages.

## Tests

All new tests live in `graphitron-sakila-example`; all but one are execution tier. Log capture is a small test
helper, `ErrorRouterLog`, attached to the generated `ErrorRouter` logger for the test's duration
and able to lower its level to DEBUG; it is the assertion that actually pins the goal. It is a
`java.util.logging` handler on the JBoss logger, not a logback appender: that module's slf4j binds
to the JBoss LogManager Quarkus brings, as `NodeIdFoldWarningTest` records. Other test classes
fail fields concurrently, so a test reads back only the records naming the reference its own
response quotes.

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
* **The cap.** `BatchSizeLimitExecutionTest` counts the statements a request prepares, at the JDBC
  connection, over `Film.actorsByCondition` (a list-valued `@splitQuery` child) under the five
  seeded films: the count with no limit is the baseline, and `withBatchSizeLimit(env -> 2)` costs
  two statements more (`ceil(5 / 2)` child statements where there was one), with the response equal
  to the unbounded one. The count is taken at the connection because the owned-connection runtime
  builds its own `DSLContext` and takes no jOOQ listener, and statements are counted, never read. A
  limit below 1 changes nothing. The limit is asked once per loader. A limit that throws leaves the
  response and the count equal to the unbounded ones and logs one ERROR line naming
  `BatchSizeLimit` and the limit's class. The escape-hatch engine honours a limit put into the
  request context. One case with the database fixture under a cap pins that a failure is one record
  per failed chunk, each chunk's fields quoting its own reference.
* **Unchanged.** `GraphQLOverHttpConformanceTest.redactionShapeMatchesFetcherPath` and
  `computedRedactionMatchesFetcherPath`, `FetcherErrorResponseExecutionTest`, and
  `TenantFanOutExecutionTest`'s tenant-failure cases pass unchanged. The generator unit tests pin
  structure only (`ErrorRouterClassGeneratorTest`, `ConnectionRuntimeClassGeneratorTest`: the new
  methods and the new unit exist), with no assertions on generated method bodies.
* **Wiring and the one construction path.** `IncrementalCompileHarnessTest`'s oracle leg passes
  with the new unit and edges, the node fetcher's edge to the router among them; its
  frozen-scaffold pin is edited to list `schema.BatchSizeLimit` under the router;
  `PackageImportDirectionTest` passes with the `LoaderConstruction` allowance present and in use;
  and the new `GeneratedSourcesLintTest` rule finds every loader construction in the sakila
  example's generated sources ending in `ErrorRouter.loaderOptions(<env>)`.

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

## Implementation notes

Implemented as planned, in four commits, and a fifth from the Done-gate rework (below): the log commit (`fix(R920)`), the cap commit
(`feat(R920)`), the reference page (`docs(R920)`), and a test-tightening commit (`test(R920)`)
taken from the architect consult below. What a reviewer would otherwise have to work out:

- **The database fixture** is `Film.databaseError` (`DatabaseErrorService.castMarkerToJson`). jOOQ
  raises its `DataException` subclass for a class-22 SQL state, so the summary the test pins reads
  `org.jooq.exception.DataException: org.postgresql.util.PSQLException [22P02/0] ERROR: invalid input syntax for type json`.
  The new field is one more emitted service, so `EmissionCensusTest`'s count moved from 124 to 125.
- **The lint rule is stricter than "not one-argument".**
  `GeneratedSourcesLintTest.emittedLoadersAreBuiltWithTheRequestsOptions` requires every
  `newDataLoader(` / `newMappedDataLoader(` call to end in `ErrorRouter.loaderOptions(<env>)`, and
  fails on a tree with fewer than ten constructions.
- **The generator-side pin moved with the constants.**
  `UnifiedEmissionPinsTest.dataLoaderFactory_registrationHomes` counted `$T.newDataLoader(` emission
  sites, which no longer exist under `generators/`; it now counts `LoaderConstruction` calls (the
  same three homes, the same counts). A second case,
  `dataLoaderFactory_isNamedByTheConstructionFragmentAlone`, holds what the deleted constants were
  for: no main source but `LoaderConstruction` names `DataLoaderFactory`, `emit/` included.
- **Signatures.** `DataLoaderFetcherEmitter.build` and `buildKeyFirst` take the router as a trailing
  `ClassName`. The emitted method's name is `LoaderConstruction.LOADER_OPTIONS_METHOD`, which the
  router generator reads, so the call and its target cannot drift; `render` may not import
  `rewrite`, so the name could not live the other way round. `FrameEmitterTest`'s stub
  `ErrorRouter` gained `loaderOptions`.
- **Both walks are bounded by depth alone.** `failureOf` and `describe` follow at most 32 links, which
  is also what ends a cyclic chain; `ErrorRouterDescribeTest.aCyclicCauseChain_terminates` covers
  both, the second with a cycle made of `CompletionException`s only.
- **Where the drafts landed.** `docs/manual/how-to/error-channel.adoc` (the unmatched section and
  "Choosing how unmatched exceptions are logged") and `docs/manual/explanation/batching-model.adoc`
  ("Bounding a batch"). The loader construction shown in `docs/manual/how-to/split-vs-inline.adoc`
  and `docs/architecture/explanation/dispatch-axes.adoc` was brought up to date.
- **What the builds covered.** The four commits together passed the full verification build (13
  modules, 8,350 tests). The plan's "each passes the verification build on its own" was not
  established for the log commit alone: by itself it ran the reactor through `graphitron` (4,805
  tests, the census count its one failure, since fixed in that commit) and its own example tests
  by name, and the cap commit was only ever built on top of it.

- **The Done-gate rework** (`test(R920)`, Round 3's finding) deleted `LoaderConstructionTest`,
  which asserted the fragment's rendered Java. Until then the "Unchanged" bullet's "no assertions
  on generated method bodies" did not hold.

_Architect consult before this handover._ The `principles-architect` read the built tree and found
it the change the spec approved, with no structural finding. Taken, in the `test(R920)` commit:
the cap's test picked out its statements by their SQL text, against "counting statements is
allowed, reading them is not", and now counts against the unbounded request; the fetcher emitter
chose the fragment's entry point with `==` on `Container`, now an exhaustive `switch`; the
factory-name pin matched only the quoted simple name, now any spelling; the throwing limit is a
named class so its log line is found by that name; the reference table's log default names the
per-tenant case; and this body's Tests and Decisions say what was built. Left alone, with the
architect's agreement that neither belongs in this item: the "summary at ERROR, exception at DEBUG"
branch is spelled in `reportUnmatched` and again in `logFanOutFailure`, as the spec asked ("the
same way"), and wants one static on the router if a third default line appears; and the hook
names `FetcherErrorLog`, `FetcherErrorResponse` and `BatchSizeLimit` are string literals in
`EmitPlan` and `PlanCompileGraph`, the pattern the first two set, where a `GeneratedUnits` method
per hook would be the one spelling.

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
objected to an earlier draft that made `pathOf` package-private for `OwnedConnections`, as a
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

### Round 3: In Review → Done (session_01FY5Uvu9heH1ZGKxef6QCVs, 2026-11-16)

Verdict: rework, on one approval precondition. Back to Ready.

The full verification build passes on trunk at `df6eb169` (13 modules, BUILD SUCCESS), with
`UnmatchedFailureLogExecutionTest` (4), `BatchSizeLimitExecutionTest` (7),
`ErrorRouterDescribeTest` (8) and `GeneratedSourcesLintTest` (7) among it.

Question one is answered. The delivered tree is the change the spec approved: `failureOf`, the
second identity map on `Claims`, `reportUnmatched` with the setter as the one that logs, `describe`
with the path, the first line and the 240-character bound, the fan-out's summary line, the
`BatchSizeLimit` hook on `GraphitronRuntime` and published by the instrumentation,
`ErrorRouter.loaderOptions` through `configured`/`contained`, `LoaderConstruction` at all five
sites inside their `computeIfAbsent`, the `QUERY_NODE_FETCHER` edge to the router and the
frozen-scaffold pin. The departures the implementation notes name (the lint rule's stricter shape,
the `UnifiedEmissionPinsTest` move, the fixture being `filmById` over three films) are in the body.

Question two is answered by the execution tier: one ERROR record and one reference across three
fields, two records for two batches, one `FetcherErrorLog.unmatched` call, the database summary at
ERROR without statement or `Where:` marker and the exception at DEBUG, a cap of two over five
parents costing two more statements with the same response, and one record per failed chunk. The
Reference check is met by "Consumer hooks on `GraphitronRuntime`" in
`docs/manual/reference/runtime-api.adoc`; the User-facing-doc check finds no roadmap markers in the
`docs/` changes; nothing is declared retired.

**Finding (approval precondition: no assertions on generated code text).**
`graphitron/src/test/java/no/sikt/graphitron/render/LoaderConstructionTest.java`, which the spec's
Tests section does not name, asserts the exact Java text `LoaderConstruction.positional` and
`mapped` render (`"org.dataloader.DataLoaderFactory.newDataLoader((keys, batchEnv) -> rows(keys),
com.example.schema.ErrorRouter.loaderOptions(env))"`). That fragment is spliced into every
generated fetcher's loader registration, so this is code-string matching on a generated method
body, which `development-principles.adoc` ("Asserting on generated Java or SQL text is banned")
and the Tests section's own "no assertions on generated method bodies" rule out. What it pins is
already pinned by behaviour and structure: `BatchSizeLimitExecutionTest` shows the options reach
positional and mapped loaders, `GeneratedSourcesLintTest.emittedLoadersAreBuiltWithTheRequestsOptions`
holds every emitted construction to the router's options, and `UnifiedEmissionPinsTest` holds the
call sites. Satisfied by deleting the test, or by replacing it with an assertion that is not on the
rendered text; `PackageImportDirectionTest`'s "allowance in use" check does not depend on it.

**Also for the next pass (precondition: the body reflects what shipped).** "Implementation" still
reads as the forward plan. Collapse "Log each failure once, without its SQL" and "Let a consumer cap
batch size" to one-line "shipped at `<sha>`" notes (`7a456e46`, `4d86d2ca`, with `e9929dcb` and
`2c455634` beside them), keeping the implementation notes, which carry what a reader needs.

Non-blocking: `RootLauncherRendererTest` asserts `m.code().toString()).contains(".fetchOne();")`, an
older instance of the same ban this item did not introduce; worth its own Backlog item rather than
this one.

_Response (session_01FY5Uvu9heH1ZGKxef6QCVs, 2026-11-16; the session that wrote this round
implemented the rework at the user's request, so the next Done pass belongs to a different
session)._ `LoaderConstructionTest` is deleted, not rewritten: a `CodeBlock` has no structure to
assert beyond its text, and what it pinned is held by `BatchSizeLimitExecutionTest` (the options
reach positional and mapped loaders: the positional shape runs under a cap at
`Film.actorsByCondition`, the mapped one at `Film.databaseError`) and `UnifiedEmissionPinsTest`
(the call sites, and `LoaderConstruction`, a branch-free expression, as the one class naming
`DataLoaderFactory`). "Implementation" and "User documentation" are collapsed to where each part
shipped. The older `RootLauncherRendererTest` instance is left alone, as the round says.

_Architect consult before this handover._ The `principles-architect` agreed deletion is what
`testing.adoc` § "Authoritative tests" asks for and named no gap. It raised that
`GeneratedSourcesLintTest.emittedLoadersAreBuiltWithTheRequestsOptions`, which an earlier draft of
this response listed as cover, reads generated method-body text for a call-site seam, the same kind
of assertion as the deleted test, so it cannot be cited as the reason the deletion is safe. Taken:
it is no longer cited above; the structural pin and the execution tier are the cover. The rule
itself was approved at Spec → Ready, so it stays, and R522, the Backlog item that decides where seam
pins sit, now names it. Also taken: the Tests bullet describes the rule as shipped, the
implementation notes name the rework commit, and "User documentation" points at "Where the drafts
landed" instead of keeping a second list.

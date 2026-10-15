---
id: R1048
title: "Consumers choose how an unmatched fetcher exception is logged, and the zero-row guard keeps input values out of its message"
status: In Review
bucket: feature
priority: 3
theme: error-channel
depends-on: []
created: 2026-10-15
last-updated: 2026-10-15
---

# Consumers choose how an unmatched fetcher exception is logged, and the zero-row guard keeps input values out of its message

## Goal

A consumer can decide how a fetcher exception that no `@error` mapping claims is logged, so personal data in an exception message never has to reach its logs, and Graphitron's bulk UPDATE and DELETE zero-row errors stop copying the client's input row into their message. Today such an exception is logged by the generated `ErrorRouter` through a fixed SLF4J logger, always at ERROR, with its full message and cause chain; the consumer's only lever is that logger's level, and turning it off loses the line that ties the client's reference to the cause. When this lands, the consumer hands the application-scoped `GraphitronRuntime` a `FetcherErrorLog`, a small generated interface, and Graphitron calls it in place of its own log line with the exception, the reference the client received, and the field's `DataFetchingEnvironment`. A consumer who configures nothing sees today's line unchanged, and what reaches the client (the redacted "An error occurred. Reference: <id>." error) stays Graphitron's in both cases.

Two terms, glossed once. An *`@error` mapping* is one handler a payload's `@error` types declare (`GENERIC` by exception class, `DATABASE` by SQL state or vendor code); a matched exception travels to the client as data in the payload. An exception no mapping claims is *unmatched*: Graphitron redacts it, meaning the client gets only a generic message plus a fresh *correlation id* (the reference), and the server log gets the real cause under the same id. This item is about that log line.

What a consumer writes, in the shape the first-client docs draft below settles:

```java
var runtime = Graphitron.runtime(dataSource, SQLDialect.ORACLE)
    .withFetcherErrorLog((thrown, reference, env) ->
        LOG.error("Unmatched exception at {}; reference = {}; {}",
            env.getExecutionStepInfo().getPath(), reference, Sanitiser.describe(thrown)));
```

## Evidence

Reported by the sis port (Graphitron 9 parity, privacy). Two leaks in sis today:

1. **Database message text.** On a unique-constraint violation Oracle adds an ORA-03301 line spelling out the conflicting column values ("row with column values (...) already exists"), so the message can carry a national identity number, an e-mail address or a name. It travels in the `SQLException` and in jOOQ's `DataAccessException` message. jOOQ's own `SQL [...]` part is safe: it renders `?` placeholders.
2. **Graphitron's own message.** The bulk zero-row guards emitted by `TypeFetcherGenerator.buildCarrierBulkPerRowUpdateBody` and `TypeFetcherGenerator.buildBulkRecordPerRowDeleteBody` throw `IllegalStateException("@mutation(typeName: UPDATE) bulk row matched zero rows; key filter found no target for input row: " + row)` (and the DELETE twin, "@lookupKey filter"), putting the whole client input map into the message. Unmapped, it is logged; mapped without a `description:`, it reaches the client. sis maps it today (to `FantIngenOppforingAaEndre`), so the message prefix is matched by consumers and must stay stable.

Graphitron 9 sis carried `CustomTopLevelErrorHandler` and `ExceptionLoggSanering`, which the port removed because there is nowhere to wire them (sis carries a TEMP commit dropping them; this blocks a v9-parity release on the logging side). What they did, as the bar the hook must make buildable without Graphitron building any of it:

- **Allowlist sanitising:** the log line holds only the exception class chain, ORA codes, constraint and index names, FS PL/SQL rule keys, and the innermost frames from the consumer's own packages; never the message.
- **Level by kind:** rejected client data (integrity violations, `IllegalArgumentException`, ORA-20000..20999 application errors) at WARN without a stack trace; real faults at ERROR.
- **Damping:** during a database outage every request fails the same way; connection errors log a stack trace at most once a minute and authentication errors once every ten minutes, INFO in between with a count of the damped ones.

All three are functions of the throwable plus process-wide state the consumer owns, so one callback that receives the throwable is sufficient for each.

A survey of the generated `throw new X("..." + value)` sites (the `$S + ` throw statements across `graphitron/src/main/java/no/sikt/graphitron/rewrite/generators`) found the two zero-row guards to be the only ones that concatenate a whole input row. The rest carry a tenant key, a page size, a truncated cursor echo, a selection name or a row index; the nested-shape guard in `TypeFetcherGenerator` already names its row by index (`"... row " + rowIdx + " differs from row 0 ..."`), which is the precedent the zero-row fix follows.

## User documentation (first-client check)

Lands in `docs/manual/how-to/error-channel.adoc`, as a new subsection at the end of "Unmatched exceptions: redact and correlate", after the existing "Add a typed channel slot ..." paragraph. Draft:

> === Choosing how unmatched exceptions are logged
>
> By default the redact path logs the whole exception at ERROR through the `ErrorRouter` logger, message and cause chain included. An exception message can carry row data: Oracle's unique-constraint message spells out the conflicting column values, for instance. To decide yourself what reaches your logs, give the runtime a `FetcherErrorLog`:
>
> ```java
> var runtime = Graphitron.runtime(dataSource, SQLDialect.ORACLE)
>     .withFetcherErrorLog((thrown, reference, env) ->
>         LOG.error("Unmatched exception at {}; reference = {}; {}",
>             env.getExecutionStepInfo().getPath(), reference, Sanitiser.describe(thrown)));
> var engine = runtime.newGraphQL(Graphitron.buildSchema(b -> {})).build();
> ```
>
> `FetcherErrorLog` is generated into your schema package next to `GraphitronRuntime`. Graphitron calls it once per unmatched exception, in place of its own log line, with the exception, the reference the client receives, and the field's `DataFetchingEnvironment` (its path, field definition and arguments). The client's response does not change: it is still "An error occurred. Reference: <reference>." Graphitron then logs nothing about the exception, so the reference is the only thing that joins a client's report to your log line; log it.
>
> Levels, sanitising and rate limiting are yours to build inside the callback. A common shape logs the exception class chain plus `SQLException.getSQLState()` and `getErrorCode()` and never `getMessage()`, picks WARN for exceptions that mean the client sent bad data, and rate-limits stack traces for connection failures so an outage does not flood the log.
>
> If your log throws, the client still gets the redacted error, and Graphitron logs one ERROR line naming your log's class, the class of what it threw and the reference, without either exception's message or stack trace. Only JVM-fatal errors (`VirtualMachineError`, such as `OutOfMemoryError`) propagate.
>
> The escape-hatch engine (`Graphitron.newGraphQL()`) has no runtime, so `withFetcherErrorLog` cannot reach it. There, put the log into each request's context under its class: `Graphitron.newExecutionInput(dsl, ...).graphQLContext(b -> b.put(FetcherErrorLog.class, log))`. A request built without that entry gets the default line, exception message included; nothing warns you, so build every request through one helper of your own.
>
> In a `<tenantColumn>` build, a field fanned out across tenants that fails or times out for one tenant reaches `tenantFailed(tenantKey, cause, reference, env)`, where a timeout arrives as a `java.util.concurrent.TimeoutException`. Its default calls `unmatched(cause, reference, env)`; override it to log the tenant too.

And the § Pitfalls bullet "Unmatched flows redact" ends "check the redaction log (or your `FetcherErrorLog`) for a correlation ID, then add the handler."

The draft reads simply: one interface, one method to implement, one line of wiring, and one sentence on what does not change. It also documents what was undocumented before, that the default line carries the exception message.

## Implementation

### The hook type

A new public interface `<outputPackage>.schema.FetcherErrorLog`, emitted by `ConnectionRuntimeClassGenerator` beside `RequestTenants` and joining the `CONNECTION_RUNTIME` unit set. Emitted rather than shipped, like `ErrorRouter`, so the no-runtime-jar invariant holds; valid Java 17. Shape:

```java
@FunctionalInterface
public interface FetcherErrorLog {
    void unmatched(Throwable thrown, String reference, DataFetchingEnvironment env);

    // <tenantColumn> builds only; K is the build's tenant key type
    default void tenantFailed(K tenantKey, Throwable cause, String reference, DataFetchingEnvironment env) {
        unmatched(cause, reference, env);
    }
}
```

- `reference` is a `String`, not a `UUID`: it is the text the client sees after "Reference:", and R423 (Backlog) proposes deriving it from the OTel trace id, which is not a UUID. A `String` keeps that change from breaking every implementor.
- The interface carries only what the consumer implements. The lookup and the failure guard live in `ErrorRouter` (below), so the consumer-facing type has no static helpers on it.
- `tenantFailed` is emitted only when the tenant key type `ConnectionRuntimeClassGenerator.generate` already receives is non-null, typed with that key, so a single-tenant build exposes no method that can never fire.
- Wiring: no new `GlobalUnitKind`. `EmitPlan.connectionRuntime` adds `units.singleton(GeneratedUnits.SUB_SCHEMA, "FetcherErrorLog")` unconditionally, and `PlanCompileGraph.addFixedKindEdges` declares edges into it from `GraphitronRuntime` and `TenantConnections` (`CONNECTION_RUNTIME` arm) and from the instrumentation (`CONNECTION_INSTRUMENTATION` arm), and from `TenantConnections` to `ErrorRouter` for the shared guard. The unit's surface varies with the tenant key, so it is not a frozen scaffold and stays out of `FROZEN_SCAFFOLD_KINDS`; the `CONNECTION_RUNTIME` set is where tenant-conditional, schema-invariant units already live (`RequestTenants` is gated the same way). `ErrorRouter` names it, but `ErrorRouter` is a frozen source that the oracle exempts, and the members it calls do not vary.

### The runtime carries it

In `ConnectionRuntimeClassGenerator.runtime`:

- A `private final FetcherErrorLog fetcherErrorLog` field (null when unset) and `public Optional<FetcherErrorLog> fetcherErrorLog()`.
- `public GraphitronRuntime withFetcherErrorLog(FetcherErrorLog log)`: requires non-null and returns a copy carrying it. The copy is built through a new private constructor taking every field as-is, so a multi-tenant runtime's fan-out executor is shared rather than a second pool being created, and the source map is not re-copied. A wither rather than a constructor parameter, because `runtime()` already emits three public constructors in a single-tenant build and six in a multi-tenant one, and the hook is orthogonal to all of them; a parameter would double that count.
- The runtime class javadoc's "Holds no per-request state and no hook state" sentence is about session hooks; it gains a clause that the one held callback is the consumer's error log, application-scoped.

In `GraphitronConnectionInstrumentationGenerator`, `beginExecuteOperation` publishes it before the carrier: when `runtime.fetcherErrorLog()` is present it puts it under `FetcherErrorLog.class`, the same class-key convention the instrumentation uses for `TenantConnections.class`. The runtime's log is the one on the managed path; there is no per-request override contract. The escape hatch reads the same key, which is how it is served with no further code, at the cost the docs draft states plainly: a request built without the entry gets the default line.

### The two log sites consult it

The contract (look the log up, call it, contain its failure) is spelled once, as a public static helper on `ErrorRouter`, in emitted form:

```java
/** Hands an unmatched throw to the configured FetcherErrorLog; false when none is configured. */
public static boolean toConfiguredLog(DataFetchingEnvironment env, String reference,
                                      Consumer<FetcherErrorLog> call) {
    GraphQLContext context = env.getGraphQlContext();
    FetcherErrorLog log = context == null ? null : context.get(FetcherErrorLog.class);
    if (log == null) return false;
    try {
        call.accept(log);
    } catch (Throwable logFailure) {
        if (logFailure instanceof VirtualMachineError fatal) throw fatal;
        LOGGER.error("FetcherErrorLog {} threw {}; reference = {}",
            log.getClass().getName(), logFailure.getClass().getName(), reference);
    }
    return true;
}
```

The failure line passes no throwable, so neither the hook's exception nor the original reaches the log through it. Catching `Throwable` (fatal JVM errors aside) matters for the promise that the client still gets the redacted error: an `Error` escaping `redact` would reach graphql-java's default exception handler, which puts its message in the response. Each call site keeps its own default line and logger, so the logger names consumers configure levels by do not move.

`ErrorRouterClassGenerator.redactBody` becomes:

```java
UUID correlationId = UUID.randomUUID();
if (!toConfiguredLog(env, correlationId.toString(), log -> log.unmatched(thrown, correlationId.toString(), env))) {
    LOGGER.error("Unmatched exception in fetcher; correlation id = {}", correlationId, thrown);
}
return /* the redacted DataFetcherResult, unchanged */;
```

The default branch is today's statement verbatim. `redact` is the single funnel: `surfaceClientErrorOrRedact`, `dispatch` and the inlined `ErrorDispatchFragments.outcomeArm` tail all end in it, so the sync catch arms, the async `.exceptionally` arms and the DataLoader paths are all covered by this one edit. The `redact` javadoc changes from "logs the original throw at ERROR" to "hands the throw to the configured `FetcherErrorLog`, else logs it at ERROR".

`ConnectionRuntimeClassGenerator.logFanOutFailure` gains a `DataFetchingEnvironment env` parameter (both callers, `fanOutRows` and `fanOutBatchRows`, already hold one) and calls `ErrorRouter.toConfiguredLog` in both arms, keeping today's line as the fallback in each:

- `Outcome.Failed`: `log.tenantFailed(failed.key(), failed.cause(), reference, env)`; fallback `"Tenant fan-out failed for tenant {}; correlation id = {}"`.
- `Outcome.TimedOut`: `log.tenantFailed(outcome.key(), new TimeoutException("tenant fan-out passed its deadline"), reference, env)`; fallback `"Tenant fan-out timed out for tenant {}; correlation id = {}"`. A timeout is the dominant outage signal in a tenant build, so the consumer's damping must see it; a synthesized `TimeoutException` keeps one method and lets the consumer tell the two apart with `instanceof`.

### The zero-row message

Both guard bodies keep the message text up to and including "found no target for input row" and replace the appended map with the row's position and the field coordinate, both known without the input's content:

```java
throw new IllegalStateException("@mutation(typeName: UPDATE) bulk row matched zero rows; key filter "
    + "found no target for input row at index " + acc.size() + " of Mutation.updateFilmsPayload");
```

- `acc.size()` at the throw is the current row's zero-based index, since `acc` holds exactly the rows already written.
- The coordinate is a generation-time constant from `f.qualifiedName()`, threaded into `buildCarrierBulkPerRowUpdateBody` and `buildBulkRecordPerRowDeleteBody` as a new `String fieldCoordinate` parameter from the two `buildBulkRecordTwoStepFetcher` lambdas in the bulk switch, where `f` is in scope.
- Keeping the prefix is deliberate: `ExceptionMapping.match` tests `matches:` with `String.contains`, and a consumer handler matching any prefix of today's text, through "found no target for input row", keeps matching.

### Out of scope

- **Mapped and surfaced errors.** Calling the log when a mapping claims an exception, or when `surfaceClientErrorOrRedact` surfaces a `GraphitronClientException`, would let sis log rejected client data at WARN as Graphitron 9 did. It needs a call at the dispatch hit, the surfacing hit, and every inlined `outcomeArm` match, three sites for a requested nice-to-have. It slots in later as a further default method on `FetcherErrorLog` without breaking any implementor, which is why `FetcherErrorLog` keeps exactly one abstract method. Not filed; file it when a consumer asks.
- **The HTTP pipeline guard** in `graphitron-jakarta-rest`'s `GraphqlHttpHandler`, which logs whatever escapes `newExecutionInput()` and `engine.execute(...)` (including Graphitron's own instrumentation refusals, such as a missing tenant set). `graphitron-jakarta-rest` is hand-written Java 17 runtime code that cannot name the per-application emitted `FetcherErrorLog`, and the guard has no `DataFetchingEnvironment`; giving it a seam is a separate design over `GraphitronApplication`'s default methods. Not filed.
- **Graphitron-built sanitising, levels or damping.** The hook makes them buildable; shipping a default sanitiser would pick an allowlist for every consumer, which is the decision this item hands back to them.

## Tests

- **Execution tier, acceptance evidence for both halves.** In `DmlBulkMutationsExecutionTest` (escape-hatch engine), `updateFilmsPayload` with two rows: the first an existing film, the second a film id that matches nothing, with a distinctive title (say `"sentinel-title-7f3a"`). Build the input with `Graphitron.newExecutionInput(...)` plus `.graphQLContext(b -> b.put(FetcherErrorLog.class, recorder))`, where `recorder` keeps its calls. Assert: the response carries one error, `"An error occurred. Reference: <ref>."`; the recorder saw exactly one `unmatched` call whose `reference` equals `<ref>`; its throwable is an `IllegalStateException` whose message contains `"matched zero rows"`, `"at index 1"` and `"Mutation.updateFilmsPayload"`, and does not contain `"sentinel-title-7f3a"`. This also proves the escape-hatch wiring: graphql-java 25's `ExecutionInput.Builder.graphQLContext(Consumer)` merges into the existing context with `putAll`, so the entry joins the generated factory's entries rather than replacing them.
- **Execution tier, the DELETE twin.** In the same class, `deleteFilmsIdCarrier` with a second id naming no film: the recorded throwable's message contains `"matched zero rows"`, `"at index 1"` and `"Mutation.deleteFilmsIdCarrier"`.
- **Execution tier, the managed runtime.** Beside `sessionBoundServiceParameter_readsTheMountedHandleThroughTheEngine` in `SessionHookExecutionTest`, which already builds `Graphitron.runtime(...)` over the main schema: the same failing `updateFilmsPayload` through `runtime.withFetcherErrorLog(recorder).newGraphQL(...)` and `newOwnedExecutionInput`. Assert the recorder saw the call with the client's reference. A second case with a log that throws (an `AssertionError`, to exercise the `Throwable` catch): assert the response still carries one redacted error with a reference and the request completes.
- **Execution tier, the fan-out arm.** In `TenantFanOutExecutionTest`, a fanned field with one tenant's `DataSource` failing (a source whose `getConnection()` throws is enough), on a runtime with a recorder overriding `tenantFailed`. Assert the recorder saw that tenant's key and a reference equal to the one in that element's error. If the class has a timeout setup (a short `fanOutTimeout` over a slow source), a second case asserts the cause is a `TimeoutException`.
- **Generator tier, structure only.** `ConnectionRuntimeClassGeneratorTest` pins `FetcherErrorLog` as an interface with exactly one abstract method, and `tenantFailed` present with the typed key when a tenant key type is given and absent otherwise; it also pins `withFetcherErrorLog` and `fetcherErrorLog()` on the runtime. `ErrorRouterClassGeneratorTest` pins `toConfiguredLog`'s signature. `EmitPlanTest` gains the unit in the connection-runtime set. Method bodies are not pinned as code strings; the execution cases above carry the behaviour. The existing `redact_logsCorrelationIdAndReturnsRedactedResult` body pin is not extended; if it breaks on the new body, narrow it to what it is named for rather than re-pinning the new text.
- **Compilation tier** comes free: `graphitron-sakila-example` compiles the emitted interface, runtime and instrumentation at `<release>17</release>`.

## Roadmap interactions

- **R920** (Backlog, batch failures log once) sketches "an optional sink the generated `redact` prefers over its own logger" in the `GraphQLContext`. That is this item's seam; R920 gains a `depends-on:` edge to this item and keeps its own goal (one record per failed batch, an SQL-free default line, a batch cap). Its memo naturally sits in front of the hook, so a consumer's log is also called once per batch failure, and its new default summary line replaces the `log == null` branch above without touching the hook path.
- **R1046** (In Progress) swaps the channel fall-through to `surfaceClientErrorOrRedact`, which still ends in `redact`. Either order works; they touch neighbouring methods of `ErrorRouterClassGenerator`.
- **R1040** (In Review) edits `buildCarrierBulkPerRowUpdateBody` for empty patches and quotes "matched zero rows" in its user docs; the kept prefix leaves that prose true. A textual overlap only.
- **R423** (Backlog) would change how the reference is minted; the `String reference` parameter absorbs that.

## Implementation notes

Landed as planned, in one commit (`R1048: consumers choose how an unmatched fetcher exception is logged`). Where it differs from or adds to the plan:

- **The managed-runtime cases fail on a permission error, not the zero-row guard.** `SessionHookExecutionTest` builds its runtime over the RLS probe role, which holds no grant on `film`, so `updateFilmsPayload` there throws a permission-denied `DataAccessException`. It is as unmatched as the zero-row throw and exercises the same wiring (runtime holds the log, instrumentation publishes it, `redact` hands it over). The zero-row message itself is proven on the escape-hatch cases in `DmlBulkMutationsExecutionTest`.
- **The zero-row cases run inside the class's rolled-back transaction.** Their seed film is never committed, so they add nothing to the shared-database race that `GraphQLQueryTest`'s full-table reads already hit (R1028; one verification build saw it as a sixth film in `filmsByTitleBean_absentInput_buildsNoBeanAndLeavesTheFieldUnfiltered`).
- **Three generator-tier harnesses compile `ErrorRouter` beside the runtime.** In a `<tenantColumn>` build `TenantConnections.logFanOutFailure` calls `ErrorRouter.toConfiguredLog`, so `TenantScatterSubstrateTest`, `TenantAuthorizationSubstrateTest` and `SessionHookImplGeneratorTest`'s tenant arm now add it to the compiled unit set, as a real build always does.
- **`ConnectionRuntimeClassGeneratorTest` also drives the compiled wither:** `withFetcherErrorLog_returnsACopyCarryingTheLog_andLeavesTheOriginalWithout` (copy carries the log, original stays empty, `null` refused), beside the structural pins the plan named.
- **Rebased over R1038**, which reworked the runtime's canonical constructor to canonicalise tenant keys; the wither's private copy constructor takes the already-canonical map as-is, so no key is re-canonicalised and R1038's new fields (on the carrier, not the runtime) need no copying.
- **Acceptance evidence:** `DmlBulkMutationsExecutionTest.updateFilmsPayload_zeroRowRow_reachesTheConfiguredLogWithoutTheInputRow` and `deleteFilmsIdCarrier_zeroRowRow_reachesTheConfiguredLogWithoutTheInputRow`; `SessionHookExecutionTest.runtimeFetcherErrorLog_receivesTheUnmatchedThrowWithTheClientsReference` and `runtimeFetcherErrorLog_thatThrows_stillLeavesTheClientTheRedactedError`; `TenantFanOutExecutionTest.downedTenant_reachesTheRuntimesFetcherErrorLogWithTheTenantAndTheClientsReference` and `timedOutTenant_reachesTheRuntimesFetcherErrorLogAsATimeoutException`.
- **Verification-build note:** two runs lost `graphitron-lsp` to load (a 19 s request timeout, then a fork killed with exit 137 while the MCP, LSP and generator test JVMs ran in parallel on 15 GB). The covering build resumed from `graphitron-lsp` single-threaded on the same tree, after every module before it had passed.

## Other solutions we've considered

- **Bake the log in at build time, as session hooks are.** A `<fetcherErrorLog>` Mojo entry naming a static `(Throwable, String, DataFetchingEnvironment)` method would emit a direct call into `redact` and `logFanOutFailure`: no wither, no context publish, no fallback branch, and the escape hatch covered for free. It loses on what the callback holds. Sanitising, levels and damping are configured per deployment (an allowlist of the consumer's own packages, damping windows from application config) and are built by the consumer's DI container; a static method forces that into a process-global singleton the container cannot inject, and tests cannot vary it per runtime. It would also make the callback a configuration fact the build captures and validates, which buys nothing here: a wrong runtime value cannot be caught at build time anyway. Session hooks are emitted because their choice is part of the schema's connection contract; this one is a deployment collaborator, like the `DataSource`.
- **A typed parameter on the escape-hatch factory `newExecutionInput(dsl, ...)`.** It would make the escape hatch's log impossible to forget, but that factory's signature is frozen by design (additive-by-construction), and the escape hatch is the path where the caller owns everything by definition. The class-key entry plus a plain statement of the fallback is the honest contract there.

- **A constructor parameter on `GraphitronRuntime`.** Doubles a constructor set that already varies by build kind, for a value orthogonal to sources and fan-out. The wither composes with every constructor.
- **A hook on `GraphitronRuntime.newGraphQL(schema, log)`.** Puts the configuration on engine assembly rather than on the application-scoped object the consumer already holds and passes around, and a second `newGraphQL` overload reads as a different engine.
- **Make `ErrorRouter` non-static and inject it.** Every generated catch arm calls it statically; making it an instance means threading it through every fetcher for a value the `GraphQLContext` already carries per request.
- **Swap the logger name and a message formatter** (the minimal shape in the report). Level-by-kind and damping both need code that sees the throwable and owns state, which a formatter cannot express; the functional interface subsumes the formatter.
- **Drop the exception message from the default line.** Fixes sis and degrades every consumer whose messages are safe and useful; the default stays, and the consumer who needs otherwise says so in one line.

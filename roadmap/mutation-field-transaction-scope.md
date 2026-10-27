---
id: R1101
title: "One transaction per top-level mutation field, opened by the instrumentation, with a consumer hook at its start"
status: Spec
bucket: feature
priority: 5
theme: runtime-connection
depends-on: []
created: 2026-10-26
last-updated: 2026-10-27
---

# One transaction per top-level mutation field, opened by the instrumentation, with a consumer hook at its start

## Goal

On the owned-connection engine, every top-level mutation field runs as exactly one database transaction per connection it touches (one in a single-tenant build), which graphitron opens and closes around the whole field, its payload included. Inside that transaction there are no savepoints: a `transaction(...)` or `transactionResult(...)` opened inside the field, by a generated fetcher or by a `@service`, joins it, and a nested scope that fails makes the whole field fail, even when the code that opened it caught the exception. A field the client is told succeeded has committed; a commit that fails, a transaction the database has already aborted, or a nested failure that was caught, is reported as that field's error. A consumer hook, registered on the runtime, runs at the start of each such transaction, inside it, knowing which mutation field and which operation it belongs to. The *owned-connection engine* is the execution mode where graphitron pins one connection per operation and owns its lifecycle (`runtime.newGraphQL(schema)`). A consumer can then attach who and why to every write with one hook instead of one call per service, and a `@service` mutation becomes all-or-nothing like a generated one. Tracked as GitHub issue sikt-no/graphitron#564.

The motivating consumer is FS audit logging: change data capture reads row changes from the PostgreSQL WAL, and the who and why travel in the same transaction as a transactional logical message, which the database discards with the transaction on rollback. The hook is an emitted interface the consumer implements beside its wiring and registers on the runtime:

```java
var runtime = Graphitron.runtime(dataSource, SQLDialect.POSTGRES)
    .withMutationHook((tx, scope, request) ->
        tx.dsl().select(DSL.function("pg_logical_emit_message", Object.class,
            DSL.inline(true), DSL.inline("audit"),
            DSL.val(json(scope.operationName().orElse(null), scope.fieldCoordinate(),
                request.<String>get("claims"))))).fetch());
```

With that registered, a request against the sakila schema

```graphql
mutation AddFilms {
  a: createFilm(in: {title: "A", languageId: 1}) { id }
  b: createFilm(in: {title: "B", languageId: 999}) { id }   # foreign key violation
}
```

runs the hook twice, each time inside the field's own transaction with `scope.fieldCoordinate()` equal to `Mutation.createFilm` and `scope.operationName()` equal to `Optional.of("AddFilms")` (empty for an anonymous `mutation { ... }`). Field `a` commits its row and its message together; field `b` rolls back its row and its message together.

Graphitron caters to good patterns, not all patterns. Work that must commit independently of the rest of a field belongs in a separate top-level mutation field, which commits on its own in document order, or in a job; graphitron offers no savepoint API, no per-field opt-out and no after-commit phase.

## User documentation (first-client check)

Draft for the section "Transactions on the owned-connection path" in `docs/architecture/reference/runtime-extension-points.adoc` (its Phase 1 text is on trunk; this draft replaces it) and a new section after it:

> **Transactions on the owned-connection path.** Each top-level mutation field runs in one transaction. It begins the first time the field uses a connection and ends when the field's value is complete, payload included: it commits when the field succeeded and rolls back when the field failed. A field fails when its fetcher, or anything under it, threw, whether or not an `@error` channel turned the exception into a typed payload, or when a GraphQL error lies under the field's path. Fields commit independently of each other, in document order. A field that never touches the database opens no transaction. Query operations run in autocommit.
>
> Inside the field there is one transaction and no savepoints. A `transaction(...)` or `transactionResult(...)` your `@service` opens joins the field's transaction, and graphitron logs a warning naming the field and your calling method, once per transaction: leaving it normally commits nothing, and leaving it by an exception marks the whole field to roll back. Catching that exception does not save the field: your code may carry on, but nothing the field wrote commits, before or after the nested scope, and the field is reported as failed with an error that says a nested transaction failed and its exception was caught. The same holds on every database. Work that must commit on its own belongs in its own top-level mutation field, or in a job.
>
> A field reported as successful has committed. If the commit fails, or the database had already aborted the transaction (on PostgreSQL, any statement that failed inside it aborts it, and the driver's commit then rolls back without an error), the field is reported as failed: its value becomes `null` and the response carries an error at its path, logged under the error's reference. Later fields in the operation still run. The mutations run one field at a time; an engine configured with a mutation execution strategy other than graphql-java's serial one is refused when a mutation starts.
>
> In a multi-tenant build each connection the field touches has its own transaction; there is no transaction across connections. A field rolls back on every connection when it failed; once every connection is known to be clean, they commit in the order the field first used them, and a field whose second connection then fails to commit is reported as failed with the first connection's writes kept.
>
> Under `CommitPolicy.ROLLBACK_ONLY`, the dev-execution mode, each field is one savepoint in a single operation transaction that is discarded at the end, so later fields observe earlier ones. That is the only savepoint graphitron takes.
>
> **Running code at the start of each mutation transaction.** Register a `MutationHook` on the runtime; graphitron calls it once per transaction it opens for a mutation field, right after the transaction begins and before any SQL for the field:
>
> ```java
> var runtime = Graphitron.runtime(dataSource, SQLDialect.POSTGRES)
>     .withMutationHook((tx, scope, request) -> audit(tx, scope, request.get("claims")));
> ```
>
> `tx` is the transaction's jOOQ `Configuration`. `scope` names the field: `fieldCoordinate()` (`Mutation.createFilm`), `fieldName()`, `alias()` (empty when the field is not aliased), `path()` (the result path, `/a`) and `operationName()` (empty for an anonymous operation); in a `<tenantColumn>` build also `tenant()`, the tenant whose connection the transaction is on (empty for the default source). `request` is the request's `GraphQLContext`, where the generated factory put every contextArgument under its name. When your `<sessionState>` mount returns a handle, the hook receives it too, typed, as a fourth parameter. Field arguments are not offered, since they can carry personal data.
>
> If the hook throws, the field fails and its transaction rolls back; the error is logged and redacted, and no `@error` channel can claim it. In a multi-tenant build the hook runs once per tenant connection the field touches.
>
> Typical uses: `SET LOCAL` of session variables that audit triggers read, a transactional `pg_logical_emit_message`, or `SET LOCAL lock_timeout` for writes.

## Current behaviour

Verified against trunk at `8da06c6`, with phase 1 of this item on it (`fd9e592`):

* `GraphitronTransactionProvider` (emitted by `GraphitronTransactionProviderGenerator`) keeps a stack of open scopes, `TopLevel` or `Savepointed`. Its package-private `open()` turns autocommit off on an empty stack under `COMMIT`, and otherwise takes a savepoint (`connection.setSavepoint()`), pushing the scope only once the JDBC call succeeded; the package-private `end(Scope, boolean failed)` ends a scope the carrier holds, popping after a successful commit and before a rollback. The jOOQ path (`begin` / `commit` / `rollback`) records its scope on the `TransactionContext`. So every `transactionResult` nested inside a field today takes a savepoint, the generated DML and `@routine` fetchers' own `transactionResult` included (`TypeFetcherGenerator.renderTransactionalKeyFetch`, `RoutineWriteEmitter`): one subtransaction per nested scope. Under `ROLLBACK_ONLY` a field's own scope is a savepoint in one operation transaction.
* On PostgreSQL a savepoint that writes is a subtransaction with its own transaction id. A backend caches at most 64 subtransactions per transaction; past that it overflows, and until the transaction ends every other session's snapshot checks visibility through `pg_subtrans`, which contends across the cluster and is worse on replicas (PostgreSQL 17's `subtransaction_buffers` softens it). `RELEASE SAVEPOINT` does not free a slot. Savepoints combined with `SELECT ... FOR UPDATE` create multixacts. A savepoint also makes catch-and-continue look safe: code swallows a database failure and keeps going, the hidden-partial-state problem this item exists to remove. A known consumer pattern on the escape hatch today (opptak's `settTilbudsgarantiFraTidligopptak`) takes one savepoint per candidate in a sweep over a whole admission round, hundreds to thousands per transaction; that team will change it.
* `TenantConnections` carries the active `FieldScope` (`MutationFieldScopeEmitter`): every owned acquisition enlists, opening the field's scope on a connection's first use; `settleField(failed)` ends each scope in open order, probing a committing PostgreSQL `TopLevel` scope with `select 1`, rolling back the rest once one fails, never touching a timed-out tenant's connection, and refusing a connection left with an open nested scope. `scatter` captures the dispatching field scope for its workers. `TenantConnections.markFieldFailed(env)` is the lenient static `ErrorRouter` calls from `claim` and `surfaceClientErrorOrRedact`.
* `GraphitronConnectionInstrumentation` settles the previous top-level mutation field in `beginFieldExecution` and the last one in the operation callback, before `releaseAll`; refuses a non-serial mutation strategy; captures the field's `DataFetchingEnvironment` in `beginFieldFetching`; and in `instrumentExecutionResult` (every owned build) nulls a field that failed to settle and adds the error `ErrorRouter.settleFailure(cause, env, path)` built, which is redacted and logged through the configured `FetcherErrorLog.unmatched`. A remembered `SessionMountException` still takes precedence.
* The `<sessionState>` hooks are static methods named in plugin configuration and reflected at generation time (`ServiceCatalog.resolveSessionHooks`). Generation loads the hook's class from the plugin's classpath, so the class is compiled before generation runs, in a dependency or reactor sibling (the sakila hooks live in `graphitron-sakila-service`). Such a class cannot refer to a type generation emits. The consumer callbacks that do take emitted types, `FetcherErrorLog` and `FetcherErrorResponse`, are emitted interfaces the consumer implements in the module that uses the generated code and registers on the runtime (`GraphitronRuntime.withFetcherErrorLog` / `withFetcherErrorResponse`), and the runtime carries them to the operation.
* jOOQ 3.20.11: `transactionResult0` follows any exception out of the body with `provider.rollback(ctx)` after `ctx.cause(e)`, then rethrows; a `commit` that throws is followed by a rollback on the same context.
* graphql-java 25.0 facts phase 1 rests on, unchanged: `beginFieldExecution`'s context completes before the field's sub-selections; `AsyncSerialExecutionStrategy` starts field N+1 only once field N's value is complete; the strategy and operation contexts complete from sibling callbacks on one future, which `CompletableFuture` may run in either order; `GraphQL.executeAsync` composes `instrumentExecutionResult` after the operation context's callback.
* The graphitron JavaPoet fork emits no records, and emitted sources target Java 17.

## Implementation

### Phase 1, shipped at `fd9e592`

One transaction per top-level mutation field, without the join rule or the hook: the carrier's field scope and lazy enlistment, the provider's package-private `open()` / `end()`, the boundaries (`beginFieldExecution`, settle then release in the operation callback), the serial-strategy check, failure marking (`markFieldFailed`, the `getErrors()` backstop), settling (the PostgreSQL probe, timed-out entries never touched, the scatter binding to the dispatching field), and the settle-failure report through `instrumentExecutionResult` and `ErrorRouter.settleFailure`. Learnings it leaves for the phases below:

* A failed-settle report reuses `ErrorRouter` with the field's own environment, captured in `beginFieldFetching`, rather than `GraphQLContext` overloads; the consumer's `FetcherErrorLog` and `FetcherErrorResponse` see a real field.
* Once one connection of a field fails to settle, the rest roll back. Phase 2 tightens this into a scan before any commit.
* The rejected-mount multi-tenant execution test was not written: no fixture has a mutation field that writes on one tenant and then reaches another. Phase 2's multi-tenant fixture (below) is the place to add it.
* A `<mutationState>` hook reflected at build time cannot work: it would take the emitted `MutationScope`, which its class, compiled before generation, cannot see. Phase 3 registers the hook on the runtime instead.

### Phase 2: nested scopes join the field transaction

* **A third scope variant.** The provider's stack gains `Joined`, a scope with no JDBC state. The carrier opens a field's scope through a new package-private `openField()`, which pushes the field's `TopLevel` (or, under `ROLLBACK_ONLY`, `Savepointed`) scope marked as the field's own. A nested `begin` on a non-empty stack pushes `Joined` and makes no JDBC call, so it cannot fail. The stack and its innermost-only rule from R1055 stay: every close still names its scope.
* **The rule applies to every nested scope, not only inside a mutation field.** On the owned path a `@service` on a Query field can open nested transactions too, through the same provider; they join the outermost scope the same way. So the only savepoint the provider ever takes is a field's own scope under `ROLLBACK_ONLY`, bounded by the number of fields in the document. (Decided with the user, on the principle that opening a transaction is fine and opening a subtransaction is not: a query's outermost `transactionResult` is still a real transaction, and only what nests inside it joins. The narrower alternative would have kept savepoints outside mutation fields.)
* **Joined exits.** A `Joined` commit pops and does nothing. A `Joined` rollback pops, marks the outermost scope on the stack rollback-only, and records the first cause it saw (jOOQ's `ctx.cause()`) on that scope. A rollback-only mark is sticky for the scope's life.
* **The outermost scope's end.** A rollback-only outermost scope never commits. On the jOOQ path (a top-level `transactionResult` outside any field, queries included), `commit` on a rollback-only scope throws a `DataAccessException` naming the nested failure without touching the connection, and jOOQ's follow-up rollback rolls back, so the caller sees the failure it swallowed. On the carrier path, `settleField` reads the mark.
* **Settle scans before it commits.** `settleField` first reads every enlisted connection's state: the field's failed flag, an error under its path, a timed-out tenant, and now each connection's rollback-only mark. Only when all are clean does it commit, in open order, with the PostgreSQL probe as a backstop for an aborted transaction no nested scope reported (a failed statement outside any nested scope, caught by the service). Any finding rolls back every connection that is not timed out. The scan runs on the dispatch thread after the field's value is complete, and a scatter worker's marks are visible after the scatter join, the same happens-before the timed-out handling uses.
* **The named error.** A field whose own result was successful but whose connection is rollback-only is reported as failed with an error that names what happened: a fixed graphitron message, "A nested transaction in this field failed and its exception was caught; the field's writes were rolled back", followed by the error's reference. The swallowed exception is logged under that reference through the configured `FetcherErrorLog.unmatched`, never sent. The message is graphitron's, not the consumer's `FetcherErrorResponse.unmatchedMessage`, so it states the cause the same way in every deployment; it carries no database text. (Decided with the user: the alternative, keeping every settle message under `FetcherErrorResponse` and naming the cause only in the log and an `extensions.classification` code, would let a deployment hide the cause.) `ErrorRouter.settleFailure` takes the distinction from the cause it is handed: a new emitted `NestedScopeFailedException` for this case, the redacted answer for every other settle failure. A field that also failed some other way (its fetcher threw) keeps that error and gets no second one.
* **Service code that opens a nested scope is warned about.** A nested scope taking no savepoint is the rule, but code that opens one expects a subtransaction it no longer gets, so the provider logs a WARN when service code opens one: that the nested transaction joined the enclosing one and took no savepoint, that a failure inside it now fails the whole transaction even if caught, the mutation field's coordinate when there is one, and the first caller frame outside jOOQ and graphitron (found with `StackWalker`, so no stack trace is logged). It logs once per outermost scope on a connection, on the first such nested scope, so a sweep that nests per item logs one line, not thousands. Graphitron's own nested scopes never warn: the generated fetchers' `transactionResult` joins inside every field, so the emitted call marks itself (an entry in the derived `Configuration`'s data the provider reads in `begin`), and a `Joined` begin carrying the mark is silent.
* **The generated fetchers.** Their own `transactionResult` stays: inside a field it joins and commits nothing, and outside one it is the outermost scope. The one emit change is the mark above, in `TypeFetcherGenerator.renderTransactionalKeyFetch` and `RoutineWriteEmitter`; jOOQ's default provider on the escape hatch ignores it.
* **The escape hatch** keeps jOOQ's default provider and the consumer's own transactions; nothing here applies.

### Phase 3: the mutation hook, registered on the runtime

* **Emitted types, in every owned build.** `MutationHook`, a functional interface in `<outputPackage>.schema`: `void begin(Configuration transaction, MutationScope scope, GraphQLContext request)`, with a fourth typed parameter for the session handle when the `<sessionState>` mount returns one (`SessionHooks.Handled`). `MutationScope`, a final class with record-style accessors: `String fieldCoordinate()`, `String fieldName()`, `Optional<String> alias()`, `String path()`, `Optional<String> operationName()`, and in a `<tenantColumn>` build `Optional<K> tenant()`. `MutationHookException`, the wrapper for a hook's throw. The consumer implements the interface in the module that uses the generated code, so its compiler checks the signature: no plugin configuration, no reflection and no build-time signature rejections.
* **Registration.** `GraphitronRuntime.withMutationHook(MutationHook)` returns the runtime, as `withFetcherErrorLog` does, and the runtime carries the hook to each operation's carrier.
* **The call.** In `enlist`, right after `openField()` on a connection and under the field-scope lock, the carrier calls the hook with the connection's provider-bound `Configuration`, the field's `MutationScope` (the instrumentation builds it in `beginFieldExecution` from the field's `ExecutionStepInfo`, with the operation name captured in `beginExecuteOperation`, and the carrier adds the connection's tenant), the request's `GraphQLContext`, and the connection's session handle when the build has one. A transaction the hook opens joins the field's. A throw is wrapped in `MutationHookException`, marks the field failed, and propagates to the acquiring fetcher.
* **Hook failure routing.** `ErrorRouter.claim` declines a chain carrying `MutationHookException`, through a sibling of `TenantConnections.isMountFailure` emitted in every build, so no `@error` channel turns an infrastructure fault into business data; the ordinary `surfaceClientErrorOrRedact` path then logs and redacts it.
* **No hook registered.** Phase 1 and 2 behaviour, unchanged; the hook call is a null check.

## Tests

Phase 1's tests are on trunk: provider field-scope cases in `GraphitronTransactionProviderGeneratorTest`, carrier cases in `TenantScatterSubstrateTest` (timed-out tenant, straggler binding, rollback of every connection, a commit failing on the second), and `MutationScopeExecutionTest` (a throwing service, a claimed exception, a caught failed statement, a failed commit as middle and last field, null propagation, the batched-payload release race, a field with no database, a non-serial strategy).

Phase 2, execution tier, no generated-body string assertions. A counting connection wrapper on the test's `DataSource` records `setSavepoint`, `commit` and `rollback` per connection:

* A generated `@mutation` that runs its own `transactionResult` commits once, with no savepoint.
* A `@service` mutation whose nested `transactionResult` succeeds: no savepoint, one commit at settle.
* A `@service` mutation that writes, opens a nested scope that throws, catches the exception, writes again and returns normally: the field is `null` with the named error and its reference, nothing it wrote before or after the nested scope is visible afterwards, and the next field commits. The same with the service on a database where nothing aborts the transaction is shown at the carrier tier (below), since the rule does not depend on PostgreSQL.
* A nested scope that throws and is not caught: the field fails with the router's ordinary redacted error, not the named one.
* A Query `@service` that nests a transaction inside its own: no savepoint, and a caught nested failure makes its own outermost commit fail with the named cause.
* Two top-level mutation fields still commit independently, in document order.
* `ROLLBACK_ONLY`: one savepoint per field, nested scopes join it, and everything is discarded at release.
* Multi-tenant: a field touching two tenants where the second connection's nested scope fails and is caught rolls back both, and the first connection was never committed (the scan precedes every commit). The rejected-mount case phase 1 left open joins this fixture.

* The warning: a `@service` mutation that opens two nested scopes logs exactly one WARN naming the field and the service's own frame; a generated `@mutation` logs none.

Phase 2, unit and carrier tier: `Joined` begin makes no JDBC call; a `Joined` rollback marks the outermost scope and records its cause; a rollback-only outermost scope refuses to commit on the jOOQ path and is rolled back by jOOQ's follow-up; `settleField` with a rollback-only connection among several commits none of them.

Phase 3, execution tier, with a test `MutationHook` that records `txid_current()`, the coordinate, the operation name and a request value in a fixture table:

* Two `createFilm` fields, the second violating a foreign key: the first commits with its hook row in the same transaction as its film row (same `txid`), the second leaves neither.
* The payload of a DML mutation, including a batched sub-field, resolves inside the field's transaction (same `txid`).
* A field that touches no database calls no hook; a query operation calls no hook.
* The hook throwing: the field fails with a redacted error, an `@error` channel on the field does not claim it, nothing is written, and the next field runs and commits.
* Operation name: named and anonymous operations.
* `ROLLBACK_ONLY`: the hook runs per field and everything is discarded at release.
* Multi-tenant: the hook runs once per connection with that connection's `tenant()`.
* With a handle-returning mount, the hook receives the handle of the connection it runs on.

Pipeline tier: `MutationHook`'s signature follows `SessionHooks` (three parameters, or four with a handle); `NestedScopeFailedException` and `MutationHookException` are emitted in every owned build.

## Documentation

`runtime-extension-points.adoc` (the draft above replaces phase 1's section and adds the hook section), `service.adoc` (`transaction-demarcation`: nested scopes join, a caught nested failure fails the field, work that must commit alone goes in its own field), `error-channel.adoc` (Pitfalls), the `newGraphQL` and `withMutationHook` javadoc, and the class javadoc of `GraphitronTransactionProviderGenerator` (one savepoint, `ROLLBACK_ONLY` only) and `GraphitronConnectionInstrumentationGenerator`. No `mojo-configuration.adoc` row: the hook is not plugin configuration.

## Breaking change

Phase 1 broke, and recorded, three behaviours on the owned path: a `@service` mutation that committed in steps commits once at the end of the field; one that throws after writing rolls back; on PostgreSQL one that catches a failed statement and returns normally is reported as failed. Phase 2 tightens that: catching an exception from a nested scope no longer lets a field partly succeed, on any database. Two patterns stop working on the owned path: per-item failure tolerance through nested transactions (a sweep that takes one transaction per item and carries on past the ones that fail), and dry-run done by writing and then rolling back a nested scope. The phase 2 commit follows the breaking-change convention: the subject says it breaks, the message records the FS-plattform check (no subgraph is on the owned path today), and its `changelog.md` line names those two patterns.

## Relation to other items

* **R1097** (Done), rejected session identity: landed the `instrumentExecutionResult` override phase 1 widened to every owned build, and the `claim` decline phase 3 extends to `MutationHookException`.
* **R722** (Spec), `@service(transactional:)`: on the owned path every `@service` mutation is now transactional, and nested scopes join, so R722's value there is gone. Its reviewer should decide whether it is re-scoped to the escape hatch or discarded; this item does not change it.
* **R1055** (Done), a failed begin closes the enclosing scope: the stack of scopes and the innermost-only close it introduced stay; phase 2 adds the `Joined` variant, whose begin makes no JDBC call and so cannot fail.
* **R469** (Backlog), `@defer` on the owned path: deferred work runs after the last field settles, outside every field transaction.

## Other solutions we've considered

* **Savepoints for nested scopes** (phase 1 as shipped). Every nested `transactionResult` a subtransaction, the generated fetchers' own included, with the cluster-wide cost described under Current behaviour, and catch-and-continue made to look safe. Joining with a rollback-only mark keeps the field's all-or-nothing promise without either.
* **A savepoint API, a per-field opt-out, or an after-commit phase** for work that must commit independently. Not offered: such work belongs in a separate top-level field, which commits on its own in document order, or in a job.
* **Marking rollback-only only where PostgreSQL aborts the transaction.** The probe already catches that case; making the rule depend on the database would let the same service partly succeed on one engine and not another.
* **A `<mutationState>` static method reflected at build time** (the earlier draft of the hook). The method would take the emitted `MutationScope`, but generation reflects a class compiled before it runs, which cannot see an emitted type. Binding the scope as JDK-typed parameters recognised by reserved names would collide with payload contextArguments bound by name, and shipping `MutationScope` in a runtime jar would break the rule that generated code depends only on graphql-java, jOOQ and the JDK. A runtime-registered emitted interface follows `FetcherErrorLog`, and the consumer's compiler checks the signature.
* **A hook in `GraphitronTransactionProvider.begin`, keeping the fetcher-owned transaction.** The provider sees no field, `@service` mutations would write outside any transaction, and the payload would be read after commit.
* **Settling in the `beginFieldExecution` completion**, or **when the `beginExecutionStrategy` context completes.** The first commits before the payload is read; the second races release. Phase 1 settles in `beginFieldExecution` of the next field and in the operation callback, before release.
* **Reading the driver's transaction state instead of probing.** The generated runtime would name a driver class.

## Reviewer findings

### Round 1 (2026-10-26, Spec -> Ready, reviewer session 01AzNHaZ9KuhqRa3sNfSoAPn)

Verdict: withhold. One blocking finding on question one (viability), one on question two (architecture fit), and two non-blocking. The goal reads on its own: a consumer can say what changes for them (one transaction per top-level mutation field, payload included, a hook at its start) without the phase list, and the audit example makes the motivation concrete. The plan extends the shapes already in the tree (the carrier, the provider's depth and savepoint stack, `MethodRef.Param` / `ParamSource`, the `ErrorRouter` arms) rather than standing a parallel mechanism beside them. The claims under Current behaviour check out against the tree and against the graphql-java 25.0 sources, including the early completion of the `beginFieldExecution` context and the serial ordering in `AsyncSerialExecutionStrategy`.

**Finding 1 (question one: viability). The last field's settle races `releaseAll`, and on the asynchronous path `releaseAll` wins, rolling back a field the client is told succeeded.**

The plan settles the last scope when the `beginExecutionStrategy` context completes, and keeps `releaseAll` as a backstop. In graphql-java 25.0 both callbacks hang off the same future: `AsyncSerialExecutionStrategy.execute` registers `overallResult.whenComplete(executionStrategyCtx::onCompleted)`, and `Execution.executeOperation` then derives `result.thenApply(mergeExtensions...).whenComplete(executeOperationCtx::onCompleted)` from that same `overallResult`; the emitted instrumentation's `beginExecuteOperation` returns the context whose completion runs `releaseAll()`. `CompletableFuture` fires dependents of a future completed later in reverse registration order, so when `overallResult` completes asynchronously the derived chain, and with it `releaseAll`, runs first. A standalone reproduction of exactly that chain shape on JDK 25 prints `releaseAll` before the strategy settle when the future is completed from another thread; completed synchronously, the order is the other way round. `PinnedConnection.release` rolls back any open transaction before restoring autocommit. So whenever the root strategy finishes asynchronously, which is the case for a payload with a DataLoader-batched sub-field (a case the Tests section names), the last mutation field's work is rolled back while its success is already in the result. The outcome depends on scheduling, which is the worst kind of failure here.

What would satisfy it: settle the last scope at a point ordered before release by construction, not by registration order. The obvious one is the operation context itself: `beginExecuteOperation`'s completion settles any open scope and then calls `releaseAll`, in that order, in one callback. The `beginExecutionStrategy` override may then be unnecessary. The plan should state the chosen point and why it is ordered, and the Tests section should pin it with the batched-payload case as the last field of the operation.

> *Author response (session 013nppeKAeTQdYmEGB6LeLv8, 2026-10-26).* Taken as suggested. The last scope settles in the callback of the context `beginExecuteOperation` returns, which settles and then calls `releaseAll()` in one callback; the `beginExecutionStrategy` override is dropped (Scope boundaries, and the first draft moved to Other solutions with the reason). Tests pin the batched-payload case as the operation's last field and the settle-before-release order directly.

**Finding 2 (question two: architecture fit). The plan does not say where a failed commit goes, and both settle points sit where the result can no longer carry it.**

Today a field's `transactionResult` commits inside its fetcher, so a commit that fails (a deferred constraint, a serialization failure, a lost connection) surfaces as that field's error. Under this plan field N commits from field N+1's `beginFieldExecution`, and the last field from a completion callback (Finding 1). By then field N's value is in the result as a success, and an exception thrown from either point would land on field N+1 or be swallowed. The multi-tenant case has the same gap at a finer grain: the field's connections share one failed flag, but the first can commit and the second fail. The plan has to state the contract: either a commit failure is reported (for example, recorded on the instrumentation state and added at the field's path in `instrumentExecutionResult`, which `GraphQL.executeAsync` composes after the operation context completes, with the field's data nulled per the spec's error handling), or the documentation says plainly that a commit failure after the field resolved is not visible to the client. The first is what today's behaviour gives; dropping it is a regression the Breaking change section does not list.

> *Author response (session 013nppeKAeTQdYmEGB6LeLv8, 2026-10-26).* Reported, not documented away. Each entry's settle failure is recorded on the carrier with the field's path and cause, logged once under a reference through the consumer's configured log and response, and applied in `instrumentExecutionResult` (now emitted in every owned build): the field becomes `null` under the spec's null propagation and gets a redacted error at its path. That runs after the operation context's callback, which `Execution` and `GraphQL` order by future composition (verified in the 25.0 bytecode), so the last field's failure is in the record. A remembered `SessionMountException`, which R1097 throws from the same method, still wins. The multi-tenant gap is stated as the contract: entries settle independently and the field is reported as failed with a committed connection's writes kept; the multi-tenant test no longer asserts cross-connection atomicity, which contradicted the user-docs draft. A failed commit is followed by `close(scope, true)`, since R1055 leaves a failed commit's scope open. Breaking change now says commit failures stay visible.

**Non-blocking.**

- R1055 should be a hard dependency, not "fold it in or land it first". Under this plan every `@service` mutation runs at depth 1 or deeper, so the R1055 scenario (service code catches a failed statement, then opens a nested `transactionResult` whose `setSavepoint()` fails with `25P02`) now closes the *field* scope and leaves `depth` wrong for every later field. Put it in `depends-on:` and drop the alternative.

  *Response:* R1055 is now in `depends-on:` and the Relation to other items entry drops the alternative. R1055's spec also restructures the provider's scope tracking so the outer scope's open and settle have an all-or-nothing open and a scope-naming close to build on.
- The scope boundaries assume the serial mutation strategy, and the spec itself notes a consumer can replace it with `.mutationExecutionStrategy(...)`. With a parallel strategy, field N+1's `beginFieldExecution` would settle field N mid-flight. `ExecutionContext.getMutationStrategy()` is reachable from `beginExecuteOperation`; failing the operation when it is not an `AsyncSerialExecutionStrategy` matches how the instrumentation already fails fast on incremental support, and the "replaced instrumentation" test has a natural sibling.

  *Response (session 013nppeKAeTQdYmEGB6LeLv8, 2026-10-26):* Taken. `beginExecuteOperation` rejects a `MUTATION` whose `getMutationStrategy()` is not an `AsyncSerialExecutionStrategy` (subclasses accepted), and the test is the replaced-instrumentation test's sibling. Breaking change lists it.

### Round 1 addendum (2026-10-26, Spec -> Ready, reviewer session 01AzNHaZ9KuhqRa3sNfSoAPn)

Verdict unchanged. One further non-blocking finding, raised by the user after round 1 was pushed.

**Non-blocking.**

- The operation name is part of what each field's hook call must capture, but the spec states it only in the user-docs draft ("`MutationScope` ... the operation name") and nowhere it would be built or checked. Implementation says only that `MutationScope` is a new emitted record: list its components and where each comes from (the operation name from `ExecutionContext.getOperationDefinition().getName()`, captured in `beginExecuteOperation`). Settle what an anonymous operation (`mutation { ... }`) gives, `null`, `Optional`, or something else, since an audit consumer has to handle it. The Tests section's hook records only `txid_current()` and the coordinate; have it record the operation name too and pin both a named and an anonymous operation. Consider using it in the Goal's `begin` example, so the requirement sits in the goal rather than in a passing clause. Relatedly, the Goal reads as one hook call per field, while in a multi-tenant build it is one per field per tenant connection touched; the user-docs draft says so, and the Goal should not contradict it.

  *Response (session 013nppeKAeTQdYmEGB6LeLv8, 2026-10-26):* `MutationScope` is now specified component by component, as a final class with record-style accessors since the JavaPoet fork emits no records: `operationName()` is `Optional<String>`, empty for an anonymous operation, captured from `getOperationDefinition().getName()` in `beginExecuteOperation`. The Goal's example emits it, the Goal states one transaction and one hook call per connection touched, and the test hook records the name for a named and an anonymous operation.

### Revision note (session 013nppeKAeTQdYmEGB6LeLv8, 2026-10-26)

Three further points, raised by a reviewer session but not yet recorded here, are addressed in the same revision:

* **Stale descriptions.** Current behaviour is re-verified at `916639caf`: the provider is described as R1055 left it (a scope stack, no depth), the instrumentation as R1097 left it (`instrumentExecutionResult` with a mount), and Relation to other items shows R1097 Done and R1055 In Review. Hook failure routing now extends R1097's `claim` decline rather than racing it.
* **Where the field scope's `Scope` lives.** On the carrier, one per entry, handed back to `close`; `open`, `close`, `requireInnermost` and `Scope` become package-private, and a refused close evicts the entry (The provider scope the carrier holds, Settling one entry). Unit tests drive the provider without a `TransactionContext`.
* **The aborted transaction.** Reproduced against the local PostgreSQL with pgjdbc 42.7.13: `commit()` after a caught failed statement returns normally and the write is gone. A PostgreSQL-family `TopLevel` settle probes with `select 1` before committing and treats a throw as a failed field; an execution test pins the caught-statement `@service`. Breaking change lists the behaviour change.

### Round 2 (2026-10-26, Spec -> Ready, reviewer session 01JD78h7sQVcWHBqWJ6mi1SH)

Verdict: withhold, on two findings that change what the implementer builds; both are narrow. The round 1 responses hold: the operation-context callback that settles and then releases orders the two by construction, settle failures are reported through `instrumentExecutionResult`, and the strategy check closes the parallel-mutation hole. The Current behaviour claims check out against trunk: R1055's `open`, `close(Scope, boolean)` and `requireInnermost` are in `916639caf`, and R1097's `instrumentExecutionResult` and `claim` decline are as described. Corrected in passing: R1055 is Done, not In Review, under Relation to other items (the empty `depends-on:` is then right, since the dependency has landed).

**Finding 1 (question one: viability). A field scope opened on a timed-out scatter worker's connection is settled from the dispatch thread while the worker may still be using it.**

Every owned acquisition opens a field scope while one is active, reads included, so a `@tenantFanOut` read in a mutation payload opens one on each tenant entry, from the scatter worker. When a worker misses the join deadline its key goes into `timedOutTenants`, and the carrier's documented rule is that such a connection "may still be mid-statement" and is never touched again except through `PinnedConnection.abort()` in `releaseAll`. Settling one entry as planned runs `close(scope, ...)` (and on PostgreSQL the `select 1` probe) on that connection from the next field's `beginFieldExecution` or from the operation callback, which is concurrent JDBC use of a connection the straggler owns. The plan should say that settle skips a timed-out key's scope (the entry is left to `releaseAll`'s abort, which discards the transaction), and that the field then settles as failed on that entry, so the client is not told a write committed that the abort will roll back. A multi-tenant test with a hanging tenant under a mutation payload pins it.

> *Author response (session 01AzNHaZ9KuhqRa3sNfSoAPn, 2026-10-26).* Taken as stated, in a new Implementation bullet, "A timed-out tenant's entry is never settled". Checked against `ConnectionRuntimeClassGenerator`: `releaseAll` aborts a `timedOutTenants` entry and never closes or returns it ("the connection may still be mid-statement"), and `entryFor` refuses any later acquisition on that key. Settle checks the field's entries against `timedOutTenants` on the dispatch thread after the join, skips a timed-out one (no probe, no `close`), and leaves it to the abort. The field settles as failed with a recorded failure naming the tenant, so its other entries roll back. Tests gain the hanging-tenant case, with a spy showing no `close` or probe on the straggler's connection.

**Finding 2 (question two: architecture fit). `ErrorRouter` does not reach the carrier, and the escape hatch has none.**

"`ErrorRouter` marks the scope through the carrier it already reaches via `env`" is not the tree: `ErrorRouter` is a fixed runtime class that calls only the static `TenantConnections.isMountFailure`, and `TenantConnections.of(env)` throws when no carrier was published, which is every fetcher on the escape-hatch path and every query fetcher that errors before acquiring. Marking through `of(env)` would turn every escape-hatch fetcher error into an `IllegalStateException` escaping the router. The plan should name the seam: a static on the carrier, emitted in every build like `isMountFailure`, that looks the carrier up leniently from `env`'s `GraphQLContext` and is a no-op when there is none or no field scope is active. It also needs to say which router entry points call it: `claim` when it claims, and `surfaceClientErrorOrRedact` on both its client-error and redact arms.

> *Author response (session 01AzNHaZ9KuhqRa3sNfSoAPn, 2026-10-26).* Confirmed in the tree (`ErrorRouterClassGenerator` calls only the static `isMountFailure`; `TenantConnections.of` throws without a carrier) and fixed as suggested. The Failure bullet now names `TenantConnections.markFieldFailed(env)`, emitted in every build. It reads the carrier leniently, is a no-op without a carrier, without an active field scope, or for a path outside the active field, and only sets the `AtomicBoolean`, so it is safe on a scatter worker. It is called from `claim` when a channel claims, and from both arms of `surfaceClientErrorOrRedact`. The `getErrors()` path check stays as the backstop for errors that bypass the router. Tests gain an escape-hatch and a failed-before-acquire query case showing the router answers exactly as today, and the pipeline tier pins the method in every owned build.

**Non-blocking.**

- A rejected mount inside a multi-tenant mutation field (written on tenant A, rejected mounting tenant B) does fail the field: R1097's placeholder is a field error built from the field's `env`, so it lands in `ExecutionContext.getErrors()` under the field's path, and a fan-out failure's marker does too. Worth one test, since the client is told the request was rejected and tenant A's write must not stand.

  *Author response.* Added to Tests: write on tenant A, reject the mount on tenant B, `execute` throws `SessionMountException` and A's write is gone.
- The hook recognises a parameter of the mount's return type as the session handle. When the handle type is also a payload type (a mount returning `String`, say), a `String` parameter is ambiguous between the handle and a payload contextArgument. Either reject a hook whose handle type equals a payload parameter's type at build time, or bind the handle by a declared marker; state which.

  *Author response.* Build-time rejection, stated in The hook. A parameter of the handle type binds the handle, at most one per hook. When the handle type is a JDK type, a parameter of that type is rejected as ambiguous, naming the parameter and the handle type, so nothing binds silently. A marker would need an annotation on the consumer's classpath that graphitron does not ship. The `<unmount>` hook keeps its rule: it takes no payload, so it has no ambiguity. The user-docs draft states the rule, and the pipeline tier pins both rejections.

### Round 3 (2026-10-26, Spec -> Ready, reviewer session 01JD78h7sQVcWHBqWJ6mi1SH)

Verdict: Ready. Both round 2 findings are answered in the plan body as asked: the timed-out entry is skipped on the dispatch thread after the join and left to `releaseAll`'s abort, with the field failed and a spy test; `TenantConnections.markFieldFailed(env)` is the lenient seam, emitted in every build, called from `claim` and both arms of `surfaceClientErrorOrRedact`, with escape-hatch tests. The two non-blocking notes are taken. One note for the implementer, not blocking:

- A scatter worker records the scope it opens on whatever field scope is active at that moment. A straggler from field N that passes `entryFor`'s post-check just before its deadline and opens after field N+1 became active would register on N+1, whose settle then sees a timed-out key and fails N+1 for N's straggler. Bind the field scope a worker opens against to the field that dispatched the scatter (capture it when the scatter starts), and skip the open when the key is already timed out.


### Revision note (session 01AzNHaZ9KuhqRa3sNfSoAPn, 2026-10-27): reopened after phase 1, Ready -> Spec

Phase 1 shipped at `fd9e592`. Two findings since round 3's sign-off change the remaining plan substantively, so the item is back at Spec for an independent review of the revision:

* **Nested scopes join, no savepoints** (an architectural decision by the FS-plattform architect, the user). Phase 1 made every nested `transactionResult` inside a field a savepoint, the generated fetchers' own included, which on PostgreSQL is a subtransaction per scope, with the cluster-wide `pg_subtrans` cost and the catch-and-continue hazard now described under Current behaviour. Phase 2 replaces that: nested scopes join, a failed one marks the field rollback-only even when caught, and the only savepoint left is a field's own under `ROLLBACK_ONLY`. Settle now scans every connection before committing any. This tightens phase 1's breaking change; the Breaking change section names the two patterns that stop working.
* **The hook cannot be a build-time reflected static method.** Found while starting the hook phase: generation reflects a class compiled before it runs, which cannot see the emitted `MutationScope`. The user chose a runtime-registered emitted interface, following `FetcherErrorLog` (phase 3). The `<mutationState>` plugin configuration, its binding, the `ParamSource` arms and the handle-ambiguity rule from rounds 1 and 2 are gone with it.

A WARN when service code opens a nested scope was added at the user's request. Two choices were drafted with a recommendation: the join rule applies to every nested scope on the owned path, queries included, rather than only inside mutation fields (agreed by the user); and a caught nested failure is reported with graphitron's fixed message plus the reference rather than through `FetcherErrorResponse` (agreed by the user).

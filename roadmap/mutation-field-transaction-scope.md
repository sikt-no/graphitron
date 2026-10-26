---
id: R1101
title: "One transaction per top-level mutation field, opened by the instrumentation, with a consumer hook at its start"
status: Spec
bucket: feature
priority: 5
theme: runtime-connection
depends-on: [transaction-provider-failed-begin-closes-enclosing-scope]
created: 2026-10-26
last-updated: 2026-10-26
---

# One transaction per top-level mutation field, opened by the instrumentation, with a consumer hook at its start

## Goal

On the owned-connection engine, every top-level mutation field runs as exactly one database transaction that graphitron opens and closes around the whole field, its payload included, and a consumer-named static method runs at the start of that transaction, inside it, knowing which mutation field it belongs to. The *owned-connection engine* is the execution mode where graphitron pins one connection per operation and owns its lifecycle (`runtime.newGraphQL(schema)`). A consumer can then attach who and why to every write with one method instead of one call per service, and a `@service` mutation becomes all-or-nothing like a generated one. Tracked as GitHub issue sikt-no/graphitron#564.

The motivating consumer is FS audit logging: change data capture reads row changes from the PostgreSQL WAL, and the who and why travel in the same transaction as a transactional logical message, which the database discards with the transaction on rollback:

```xml
<mutationState>
  <begin>no.sikt.fs.audit.AuditContext#begin</begin>
</mutationState>
```

```java
public static void begin(Configuration tx, MutationScope scope, String claims) {
    tx.dsl().select(DSL.function("pg_logical_emit_message", Object.class,
        DSL.inline(true), DSL.inline("audit"),
        DSL.val(json(scope.fieldCoordinate(), claims)))).fetch();
}
```

With that configured, a request against the sakila schema

```graphql
mutation {
  a: createFilm(in: {title: "A", languageId: 1}) { id }
  b: createFilm(in: {title: "B", languageId: 999}) { id }   # foreign key violation
}
```

runs `begin` twice, each time inside the field's own transaction with `scope.fieldCoordinate()` equal to `Mutation.createFilm`. Field `a` commits its row and its message together; field `b` rolls back its row and its message together.

## User documentation (first-client check)

Draft for a new section in `docs/architecture/reference/runtime-extension-points.adoc`, after the `<sessionState>` section, and a row in `docs/manual/reference/mojo-configuration.adoc`:

> **Transactions on the owned-connection path.** Each top-level mutation field runs in one transaction. It begins the first time the field uses a connection and ends when the field's value is complete, payload included: it commits when the field succeeded and rolls back when the field failed. A field fails when its fetcher, or anything under it, threw, whether or not an `@error` channel turned the exception into a typed payload, or when a GraphQL error lies under the field's path. Fields commit independently of each other, in document order. A `transactionResult` your `@service` opens inside the field is a savepoint in that transaction, not a commit. A field that never touches the database opens no transaction. Query operations are unchanged and run in autocommit.
>
> **Running code at the start of each mutation transaction.** A `<mutationState>` block names a static method graphitron calls once per transaction it opens for a mutation field, right after the transaction begins and before any SQL for the field:
>
> ```xml
> <mutationState>
>   <begin>com.example.db.AuditContext#begin</begin>
> </mutationState>
> ```
>
> Graphitron reads the signature at build time. A `Configuration` parameter receives the transaction's configuration. A `MutationScope` parameter receives the field: its coordinate (`Mutation.createFilm`), name, alias, result path and the operation name. A parameter of the `<sessionState>` mount's return type receives the session handle. Every other parameter is payload and becomes a contextArgument on the generated factory; one with the same name and type as a mount payload parameter is the same value, supplied once. Field arguments are not offered, since they can carry personal data.
>
> If the method throws, the field fails and its transaction rolls back; the error is logged and redacted, and no `@error` channel can claim it. In a multi-tenant build the method runs once per tenant connection the field touches, and each connection commits or rolls back on its own: there is no transaction across connections.
>
> Typical uses: `SET LOCAL` of session variables that audit triggers read, a transactional `pg_logical_emit_message`, or `SET LOCAL lock_timeout` for writes.

## Current behaviour

Verified against trunk at `b21e2d5be`:

* `GraphitronConnectionInstrumentation` (emitted by `GraphitronConnectionInstrumentationGenerator`) implements only `createState` and `beginExecuteOperation`. Its class javadoc states the contract this item replaces: no outer transaction is opened, and each mutation field's shipped `dsl.transactionResult(...)` is the per-field writable boundary.
* The generated DML mutation fetchers (`TypeFetcherGenerator.renderTransactionalKeyFetch`) and `@routine` writes (`RoutineWriteEmitter`) wrap the statement in `dsl.transactionResult(...)` and read the payload back after it returns, in autocommit under `CommitPolicy.COMMIT`. `@service` mutations get no transaction (`TypeFetcherGenerator.buildServiceFetcherCommon`); `service.adoc` documents that contract.
* `GraphitronTransactionProvider` (emitted by `GraphitronTransactionProviderGenerator`) turns autocommit off at depth 0 and takes a savepoint deeper; `settle` commits or rolls back and reasserts autocommit. Under `ROLLBACK_ONLY` each field is already a savepoint inside one operation transaction, with the payload read back before the discard: the topology this item gives `COMMIT` per field.
* An owned fetcher without the carrier fails loudly: `TenantConnections.of(env)` throws when the instrumentation did not publish it. A consumer who replaces the instrumentation therefore gets errors, not unattributed writes.
* graphql-java 25.0 (read from the sources jar): `ExecutionStrategy.resolveFieldWithInfo` completes the `beginFieldExecution` context when the `FieldValueInfo` future completes, which can be before `FieldValueInfo.getFieldValueFuture()`, the field's sub-selections, completes. `AsyncSerialExecutionStrategy` starts field N+1 only after field N's value future completed, and completes its `beginExecutionStrategy` context after the last one. `beginExecutionStrategy` is called by the root strategies only; nested objects use `beginExecuteObject`. `ExecutionContext.getErrors()` exposes the errors recorded so far.

## Implementation

* **Field scope on the carrier.** `TenantConnections` (in `ConnectionRuntimeClassGenerator`) gains an active mutation scope: the `MutationScope` value, a failed flag, and the set of entries whose transaction the scope began. Every owned acquisition path (`getDslContext` / `dslFor` / `dslDefault` / `dslGlobal`) checks, after resolving the entry, whether a scope is active and has not yet begun on that entry; if so it begins the entry's transaction and calls the hook. The state lives on the carrier, so it exists exactly when the instrumentation ran.
* **Begin and settle on the provider.** `GraphitronTransactionProvider` gains an explicit open and settle for the outer scope, used by the carrier, alongside the jOOQ `begin`/`commit`/`rollback`. Inside an open scope, the fetcher's own `transactionResult` and any a `@service` opens run at depth 1 or deeper and become savepoints, with no change to their emit. Under `ROLLBACK_ONLY` the scope is a savepoint in the operation transaction, as each field is today.
* **Scope boundaries in the instrumentation.** `GraphitronConnectionInstrumentation` overrides `beginFieldExecution`: for a field at result-path level 1 in a `MUTATION` operation, it settles the previous field's scope, if any, and opens a new one. It overrides `beginExecutionStrategy` and settles the last scope when that context completes. Both points are after the previous field's value, payload included, is complete, which the `beginFieldExecution` completion is not (see Current behaviour). `releaseAll` keeps its existing open-transaction handling as the backstop for a scope a failed execution never settled.
* **Failure.** A scope settles as failed when the `ErrorRouter` handled an exception for a field under it, whether it redacted the exception or an `@error` channel claimed it into a payload; or when `ExecutionContext.getErrors()` holds an error whose path starts with the field's path; or when the hook threw. `ErrorRouter` marks the scope through the carrier it already reaches via `env`.
* **The hook.** `<mutationState>` with a `<begin>` method reference: a new `MutationStateBinding` beside `SessionStateBinding` in graphitron-maven-plugin, a `@Parameter` on `AbstractRewriteMojo`, and resolution at build time into the existing `MethodRef.Param` / `ParamSource` taxonomy (`Context` for payload, `SessionHandle` for the mount's handle) plus two new sources for `Configuration` and `MutationScope`, so the same signature rules and build failures as `<sessionState>` apply. The emitted `GraphitronMutationHook` mirrors `GraphitronSessionHook`. `MutationScope` is a new emitted record in `<outputPackage>.schema`. Payload contextArguments are read in `beginExecuteOperation`, as the mount's are.
* **Hook failure routing.** A throw from the hook is wrapped in a new emitted `MutationHookException`. `ErrorRouter` gets an arm for it ahead of the `@error` channel claim, logging with a correlation id and redacting, so an infrastructure fault never becomes business data. R1097 adds an arm for `SessionMountException` at the same place; whichever lands second extends the arm rather than adding a parallel one.
* **No hook configured.** The scope and its transaction are unconditional on the owned path; only the hook call and its types are gated on `<mutationState>`.
* **Escape-hatch path out of scope.** With `newExecutionInput(dsl, ...)` the consumer owns the `DSLContext` and its transactions; graphitron opens no scope there. The documentation states how to get the same effect with a consumer `Instrumentation` and a jOOQ `TransactionListener`.

## Tests

Execution tier in graphitron-sakila-example, a new `MutationScopeExecutionTest` beside `SessionHookExecutionTest`, with a test hook that records `txid_current()` and the coordinate in a fixture table (a real table in `init.sql`, with the schema version bumped above trunk's):

* Two `createFilm` fields, the second violating a foreign key: the first commits with its hook row in the same transaction as its film row (same `txid`), the second leaves neither.
* A `@service` mutation that writes twice in autocommit and then throws: both writes roll back. The same with an `@error` channel claiming the exception into a payload: the payload is returned and both writes still roll back.
* The payload of a DML mutation, including a batched sub-field, resolves inside the field's transaction (same `txid`).
* A mutation field that touches no database opens no transaction and calls no hook.
* A query operation calls no hook and runs in autocommit.
* The hook throwing: the field fails with a redacted error, an `@error` channel on the field does not claim it, nothing is written, and the next field in the operation still runs and commits.
* `ROLLBACK_ONLY`: the hook runs per field and everything is discarded at release.
* Multi-tenant: a field touching two tenant connections calls the hook once per connection, and both connections commit or both roll back with the field.
* A consumer instrumentation that replaces graphitron's fails the mutation loudly rather than writing without a scope.

Pipeline tier: the hook class, `MutationScope` and `MutationHookException` are emitted only with `<mutationState>`; a malformed `<begin>` reference fails the build naming the method. `MojoDocCoverageTest` covers the new parameter row.

## Documentation

Real homes for the first-client draft: `runtime-extension-points.adoc` and `mojo-configuration.adoc`. The contract change also lands in `service.adoc` (Constraints: on the owned path a `@service` mutation runs inside the field's transaction; the bare-`DSLContext` contract stays for queries and the escape hatch), `mutation.adoc` (the transaction claim covers the payload), `error-channel.adoc` (a claimed exception rolls the field back), and the class javadoc of `GraphitronConnectionInstrumentationGenerator` and `GraphitronTransactionProviderGenerator`.

## Breaking change

Two behaviours change for owned-path consumers with no configuration: a `@service` mutation that commits in steps now commits once at the end of the field, and one that throws after writing now rolls back. The implementation commit follows the breaking-change convention: the subject says it breaks, the message records the check against the known consumers, and `changelog.md` gets its line in the same commit.

## Relation to other items

* **R1097** (Spec), rejected session identity: same instrumentation class and same `ErrorRouter` placement; see Hook failure routing. Independent otherwise.
* **R722** (Spec), `@service(transactional:)`: on the owned path this item makes every `@service` mutation transactional, so R722's value there is gone. Its reviewer should decide whether it is re-scoped to the escape hatch and queries or discarded; this item does not change it.
* **R1055** (Spec), a failed begin closes the enclosing scope: a hard dependency. Under this plan every `@service` mutation runs inside the field scope, so a failed nested begin would otherwise close the field scope. R1055 replaces the provider's depth counter with a stack of scope entries, makes an open all-or-nothing, and makes every close name its scope; the outer scope's open and settle here build on those private open and close methods, and this item decides their visibility.
* **R469** (Backlog), `@defer` on the owned path: a deferred payload fragment would complete after the scope settles. Incremental delivery stays rejected; R469 has to account for this when it lifts that.

## Other solutions we've considered

* **A hook in `GraphitronTransactionProvider.begin`, keeping the fetcher-owned transaction.** Smallest change, but the provider sees no field, `@service` mutations would still write in autocommit outside any transaction, and the payload would still be read after commit.
* **Settling in the `beginFieldExecution` completion.** Simpler, and wrong: graphql-java completes that context before the field's sub-selections are done, so the scope would commit before the payload is read and before its errors are known.
* **A custom mutation `ExecutionStrategy`.** It would see the true end of each field, but `resolveSerialField` is private, and a strategy set on the builder is replaced by a later `.mutationExecutionStrategy(...)` as silently as an instrumentation is.
* **A per-field opt-out** for services that must commit in steps. Not offered: one pinned connection cannot hold an independent commit inside an open transaction, and no known consumer needs it. A successor can add it if one appears.

## Reviewer findings

### Round 1 (2026-10-26, Spec -> Ready, reviewer session 01AzNHaZ9KuhqRa3sNfSoAPn)

Verdict: withhold. One blocking finding on question one (viability), one on question two (architecture fit), and two non-blocking. The goal reads on its own: a consumer can say what changes for them (one transaction per top-level mutation field, payload included, a hook at its start) without the phase list, and the audit example makes the motivation concrete. The plan extends the shapes already in the tree (the carrier, the provider's depth and savepoint stack, `MethodRef.Param` / `ParamSource`, the `ErrorRouter` arms) rather than standing a parallel mechanism beside them. The claims under Current behaviour check out against the tree and against the graphql-java 25.0 sources, including the early completion of the `beginFieldExecution` context and the serial ordering in `AsyncSerialExecutionStrategy`.

**Finding 1 (question one: viability). The last field's settle races `releaseAll`, and on the asynchronous path `releaseAll` wins, rolling back a field the client is told succeeded.**

The plan settles the last scope when the `beginExecutionStrategy` context completes, and keeps `releaseAll` as a backstop. In graphql-java 25.0 both callbacks hang off the same future: `AsyncSerialExecutionStrategy.execute` registers `overallResult.whenComplete(executionStrategyCtx::onCompleted)`, and `Execution.executeOperation` then derives `result.thenApply(mergeExtensions...).whenComplete(executeOperationCtx::onCompleted)` from that same `overallResult`; the emitted instrumentation's `beginExecuteOperation` returns the context whose completion runs `releaseAll()`. `CompletableFuture` fires dependents of a future completed later in reverse registration order, so when `overallResult` completes asynchronously the derived chain, and with it `releaseAll`, runs first. A standalone reproduction of exactly that chain shape on JDK 25 prints `releaseAll` before the strategy settle when the future is completed from another thread; completed synchronously, the order is the other way round. `PinnedConnection.release` rolls back any open transaction before restoring autocommit. So whenever the root strategy finishes asynchronously, which is the case for a payload with a DataLoader-batched sub-field (a case the Tests section names), the last mutation field's work is rolled back while its success is already in the result. The outcome depends on scheduling, which is the worst kind of failure here.

What would satisfy it: settle the last scope at a point ordered before release by construction, not by registration order. The obvious one is the operation context itself: `beginExecuteOperation`'s completion settles any open scope and then calls `releaseAll`, in that order, in one callback. The `beginExecutionStrategy` override may then be unnecessary. The plan should state the chosen point and why it is ordered, and the Tests section should pin it with the batched-payload case as the last field of the operation.

**Finding 2 (question two: architecture fit). The plan does not say where a failed commit goes, and both settle points sit where the result can no longer carry it.**

Today a field's `transactionResult` commits inside its fetcher, so a commit that fails (a deferred constraint, a serialization failure, a lost connection) surfaces as that field's error. Under this plan field N commits from field N+1's `beginFieldExecution`, and the last field from a completion callback (Finding 1). By then field N's value is in the result as a success, and an exception thrown from either point would land on field N+1 or be swallowed. The multi-tenant case has the same gap at a finer grain: the field's connections share one failed flag, but the first can commit and the second fail. The plan has to state the contract: either a commit failure is reported (for example, recorded on the instrumentation state and added at the field's path in `instrumentExecutionResult`, which `GraphQL.executeAsync` composes after the operation context completes, with the field's data nulled per the spec's error handling), or the documentation says plainly that a commit failure after the field resolved is not visible to the client. The first is what today's behaviour gives; dropping it is a regression the Breaking change section does not list.

**Non-blocking.**

- R1055 should be a hard dependency, not "fold it in or land it first". Under this plan every `@service` mutation runs at depth 1 or deeper, so the R1055 scenario (service code catches a failed statement, then opens a nested `transactionResult` whose `setSavepoint()` fails with `25P02`) now closes the *field* scope and leaves `depth` wrong for every later field. Put it in `depends-on:` and drop the alternative.

  *Response:* R1055 is now in `depends-on:` and the Relation to other items entry drops the alternative. R1055's spec also restructures the provider's scope tracking so the outer scope's open and settle have an all-or-nothing open and a scope-naming close to build on.
- The scope boundaries assume the serial mutation strategy, and the spec itself notes a consumer can replace it with `.mutationExecutionStrategy(...)`. With a parallel strategy, field N+1's `beginFieldExecution` would settle field N mid-flight. `ExecutionContext.getMutationStrategy()` is reachable from `beginExecuteOperation`; failing the operation when it is not an `AsyncSerialExecutionStrategy` matches how the instrumentation already fails fast on incremental support, and the "replaced instrumentation" test has a natural sibling.

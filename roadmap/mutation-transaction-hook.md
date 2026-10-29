---
id: R1129
title: "A consumer hook that runs at the start of each mutation field transaction"
status: Backlog
bucket: feature
priority: 6
theme: runtime-connection
depends-on: [mutation-field-transaction-scope]
created: 2026-10-29
last-updated: 2026-10-29
---

# A consumer hook that runs at the start of each mutation field transaction

## Goal

A consumer registers one hook on the runtime that graphitron calls at the start of every top-level mutation field's transaction, inside it, with the field's coordinate, alias, path and operation name, so who and why can be attached to every write (audit triggers, a transactional logical message, `SET LOCAL` settings) without one call per service. It was Phase 3 of the one-transaction-per-mutation-field item, split out because that item is the transaction control and this is a consumer convenience on top of it, and because `@outbox` now covers the audit case for fields that carry the directive. The open question before this moves to Spec is whether a consumer needs it: the hook reaches every mutation field with no schema annotation, `@service` mutations included, and can run statements that are not messages, which `@outbox` cannot.

## Design carried over

Written as Phase 3 of R1101 and kept as it stood, to be reviewed as a draft when this moves to Spec. It rests on R1101's field scope (phase 1, shipped) and reads the declared session handle from the store (`store_graph_session_handle`, landed with R1120).

### Motivating consumer and shape

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

### User documentation (draft)

> **Running code at the start of each mutation transaction.** Register a `MutationHook` on the runtime; graphitron calls it once per transaction it opens for a mutation field, right after the transaction begins and before any SQL for the field:
>
> ```java
> var runtime = Graphitron.runtime(dataSource, SQLDialect.POSTGRES)
>     .withMutationHook((tx, scope, request) -> audit(tx, scope, request.get("claims")));
> ```
>
> `tx` is the transaction's jOOQ `Configuration`. `scope` names the field: `fieldCoordinate()` (`Mutation.createFilm`), `fieldName()`, `alias()` (empty when the field is not aliased), `path()` (the result path, `/a`) and `operationName()` (empty for an anonymous operation); in a `<tenantColumn>` build also `tenant()`, the tenant whose connection the transaction is on (empty for the default source). `request` is the request's `GraphQLContext`, where the generated factory put every contextArgument under its name. When the build declares a session `<handle>` type, the hook receives the handle of the connection it runs on too, typed, as a fourth parameter; the session is mounted before the transaction begins, so the hook sees it. Field arguments are not offered, since they can carry personal data.
>
> If the hook throws, the field fails and its transaction rolls back; the error is logged and redacted, and no `@error` channel can claim it. In a multi-tenant build the hook runs once per tenant connection the field touches.
>
> Typical uses: `SET LOCAL` of session variables that audit triggers read, a transactional `pg_logical_emit_message`, or `SET LOCAL lock_timeout` for writes.

### Plan

* **Emitted types, in every owned build.** `MutationHook`, a functional interface in `<outputPackage>.schema`: `void begin(Configuration transaction, MutationScope scope, GraphQLContext request)`, with a fourth parameter typed as the declared session handle when the build declares one, read from the store's handle row (`store_graph_session_handle`); a build with no declared handle gets the three-parameter shape. `MutationScope`, a record: `String fieldCoordinate()`, `String fieldName()`, `Optional<String> alias()`, `String path()`, `Optional<String> operationName()`, and in a `<tenantColumn>` build `Optional<K> tenant()`. `MutationHookException`, the wrapper for a hook's throw. The consumer implements the interface in the module that uses the generated code, so its compiler checks the signature: no plugin configuration, no reflection and no build-time signature rejections.
* **Registration.** `GraphitronRuntime.withMutationHook(MutationHook)` returns the runtime, as `withFetcherErrorLog` does, and the runtime carries the hook to each operation's carrier.
* **The call.** In `enlist`, right after `openField()` on a connection has been recorded, outside the field-scope lock (R1101's rule that no JDBC or consumer code runs under the field-scope lock), the carrier calls the hook with the connection's provider-bound `Configuration`, the field's `MutationScope` (the instrumentation builds it in `beginFieldExecution` from the field's `ExecutionStepInfo`, with the operation name captured in `beginExecuteOperation`, and the carrier adds the connection's tenant), the request's `GraphQLContext`, and the connection's session handle when the build has one. A transaction the hook opens joins the field's. A throw is wrapped in `MutationHookException`, marks the field failed, and propagates to the acquiring fetcher.
* **Hook failure routing.** `ErrorRouter.claim` declines a chain carrying `MutationHookException`, so no `@error` channel turns an infrastructure fault into business data; the ordinary `surfaceClientErrorOrRedact` path then logs and redacts it. The router already declines a rejected mount and a failed outbox emit in one condition, so this phase makes that condition one predicate over all three infrastructure faults (mount, outbox emit, hook) rather than a third arm.
* **No hook registered.** Phase 1 and 2 behaviour, unchanged; the hook call is a null check.
* **One source for the handle.** The hook's handle parameter reads `store_graph_session_handle` and nothing else, so the interface is fixed before any consumer implements it.

### Where it attaches

R1101's carrier opens a field's scope on a connection in `enlist`, keeps JDBC and consumer code outside the field-scope monitor, and completes a per-key completion once the open is done. The hook runs at that point: after the scope is recorded (so a hook that throws leaves a scope settle rolls back) and before the completion, so every other acquirer of the key waits for the hook as well as the open. A waiter whose first acquirer's hook threw fails the same way.

### Tests (carried over)

Execution tier, with a test `MutationHook` that records `txid_current()`, the coordinate, the operation name and a request value in a fixture table:

* Two `createFilm` fields, the second violating a foreign key: the first commits with its hook row in the same transaction as its film row (same `txid`), the second leaves neither.
* The payload of a DML mutation, including a batched sub-field, resolves inside the field's transaction (same `txid`).
* A field that touches no database calls no hook; a query operation calls no hook.
* The hook throwing: the field fails with a redacted error, an `@error` channel on the field does not claim it, nothing is written, and the next field runs and commits.
* Operation name: named and anonymous operations.
* `ROLLBACK_ONLY`: the hook runs per field and everything is discarded at release.
* Multi-tenant: the hook runs once per connection with that connection's `tenant()`.
* With a declared handle, the hook receives the handle of the connection it runs on, and the session was mounted before the transaction began (the hook reads a value the mount set).
* A hook failure and an outbox emit failure and a rejected mount are each declined by the one router predicate, pinned by calling `claim` with each cause and an `@error` channel that would otherwise match.

Pipeline tier: `MutationHook`'s signature is three parameters, or four when the store's handle row names a handle type, the fourth typed as that class; `MutationScope` is a record whose `tenant()` is typed from the tenant column's catalog type; `NestedScopeFailedException` and `MutationHookException` are emitted in every owned build.

## Other solutions we've considered

* **A `<mutationState>` static method reflected at build time** (the earlier draft of the hook). The method would take the emitted `MutationScope`, but generation reflects a class compiled before it runs, which cannot see an emitted type. Binding the scope as JDK-typed parameters recognised by reserved names would collide with payload contextArguments bound by name, and shipping `MutationScope` in a runtime jar would break the rule that generated code depends only on graphql-java, jOOQ and the JDK. A runtime-registered emitted interface follows `FetcherErrorLog`, and the consumer's compiler checks the signature.
* **A hook in `GraphitronTransactionProvider.begin`, keeping the fetcher-owned transaction.** The provider sees no field, `@service` mutations would write outside any transaction, and the payload would be read after commit.
* **Leaving the audit case to `@outbox`.** Covers the fields that carry the directive and writes messages, not session settings; the open question above is whether that is enough.

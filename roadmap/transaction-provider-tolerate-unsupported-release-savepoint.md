---
id: R1054
title: "GraphitronTransactionProvider fails a nested commit on drivers without releaseSavepoint (Oracle)"
status: In Review
bucket: bug
depends-on: []
created: 2026-10-16
last-updated: 2026-10-16
---

# GraphitronTransactionProvider fails a nested commit on drivers without releaseSavepoint (Oracle)

## Goal

A service that opens a jOOQ transaction inside another one commits on Oracle, as it does under jOOQ's own transaction handling. Today the inner commit fails on Oracle, the whole operation rolls back, and the client gets a generic error with nothing persisted:

```java
// a @service method: graphitron opens no transaction around it, so it opens its own
return ctx.transactionResult(cfg -> {
    // ... a CRUD service called from here opens a nested one:
    cfg.dsl().transaction(trx -> { /* writes */ });
    return payload;
});
```

After this item that service commits on Oracle with no change to the schema or the service code. Separately, an inner commit that fails for a real reason (rather than because the driver lacks a feature) still surfaces as an error, and no longer leaves graphitron's transaction bookkeeping corrupted for the rest of the GraphQL operation.

Reported from sis, a Sikt system being ported from graphitron v9 to the rewrite (trunk `02b6a7f3d`): the mutation `registrerMeldingerOmDodsfall`, a `@service` field, has exactly this shape and fails `registrerMeldingerOmDodsfallTest` and `registrerMeldingerOmFodselsnummerendring`. Nesting is ordinary in a service layer; 47 files in sis-service use `transaction` or `transactionResult`.

## Background

`GraphitronTransactionProvider` is the jOOQ `TransactionProvider` graphitron emits into the consumer's `<outputPackage>.schema` package (generator: `GraphitronTransactionProviderGenerator`). Every `transaction`/`transactionResult` on the `DSLContext` graphitron hands a service routes its begin, commit and rollback through it. It keeps a nesting `depth` and a stack of JDBC *savepoints*: markers a nested transaction opens so it can be rolled back without the enclosing one. `begin` at depth > 0 pushes `connection.setSavepoint()`; `rollback` rolls back to it; `commit` *releases* it. Releasing only frees the marker: the nested writes already belong to the enclosing transaction either way, and an unreleased savepoint lives until the enclosing transaction commits or rolls back, then goes with it. A later `rollback(savepoint)` stays safe beside an unreleased marker, since the stack only holds savepoints opened around or after it, and rolling back to a savepoint destroys any opened after it.

The emitted `commit` calls `connection.releaseSavepoint(savepoints.pop())` at two sites:

* the nested branch (depth > 0 after the decrement), the sis failure;
* the deferred-rollback branch: depth 0 under `CommitPolicy.ROLLBACK_ONLY`, the dev-execution mode that keeps one operation transaction open and gives each mutation field its own savepoint. A field *settles* there by releasing its savepoint, so on Oracle every mutation field in dev mode fails the same way. Not observed yet, but it is the same call. The unreleased markers that dev mode would pile up across fields are discarded by `PinnedConnection.release` (emitted by `ConnectionRuntimeClassGenerator`), which rolls back the whole open transaction at operation completion.

These are the only two `releaseSavepoint` calls in the reactor. Oracle's JDBC driver does not implement the method: it throws `SQLFeatureNotSupportedException` (ORA-17023, "Unsupported feature"), which `commit`'s `catch (SQLException e)` rethrows as `DataAccessException("Could not commit transaction")`. `rollback(Savepoint)`, which Oracle supports, is unaffected.

Two facts about the surroundings decide the design, both checked against the pinned jars:

* **jOOQ calls `rollback` after a failed `commit`.** `DefaultDSLContext.transactionResult0` sets its `committed` flag only after `provider.commit(ctx)` returns, and on any throwable with the flag unset it calls `provider.rollback(ctx)` for the same scope. The emitted `commit` decrements `depth` and pops the savepoint *before* the call that can fail, so that follow-up rollback closes the scope a second time. On a failed nested commit at depth 2, the rollback lands at depth 0 and `settle(true)` rolls back the *outer* transaction and turns autocommit on; the outer scope's own rollback then pops an empty stack (`NoSuchElementException`, which jOOQ attaches as suppressed) and leaves `depth` at -1. One provider serves the whole operation (one per pinned connection), so every later mutation field in that operation then starts from the wrong depth. A failing top-level `connection.commit()` (a deferred constraint checked at commit, say) takes the same path. Today Oracle reaches it through the release; any driver reaches it through a real commit failure.
* **A Postgres `COMMIT` on an aborted transaction silently rolls back.** If service code catches a failed statement inside the nested lambda and returns normally, Postgres has marked the transaction aborted, and the nested release fails with SQLState `25P02`. pgjdbc 42.7.13 has no option to raise on the silent rollback a later `COMMIT` performs. So if the release failure were swallowed, the outer commit would report success and persist nothing.

jOOQ 3.20.11's `DefaultTransactionProvider.commit` discards *every* release failure (`[#3537] Ignore those cases where the JDBC driver incompletely implements the API`; its exception table covers exactly the `releaseSavepoint` call). That fixes the Oracle case but walks into the Postgres one, so this plan tolerates only the unsupported-feature failure and fixes what happens to every other one.

## Implementation

All in `GraphitronTransactionProviderGenerator` (module `graphitron`); the emitted class stays JDK + jOOQ only and valid Java 17.

* **Commit closes its scope only on success.** Restructure the emitted `commit` so the branch is chosen on `depth` as it stands (`depth == 1` for the top-level and deferred-rollback arms, `depth > 1` for nested), the savepoint is read with `peek()` and released, and only then is it `pop()`ped and `depth` decremented. When anything in the arm throws, `depth` and the stack are as `begin` left them, and jOOQ's follow-up `rollback` closes the scope exactly once: a nested scope rolls back to its savepoint (which on Postgres also clears the aborted state, so the enclosing transaction carries on and the exception reaches the enclosing lambda), a deferred-rollback field rolls back to its savepoint, and a top-level transaction runs `settle(true)`. `begin` and `rollback` keep their shape; `rollback` already pops and decrements as it goes, and nothing calls anything after a failed rollback.
* **Release tolerates an unimplemented method, and only that.** Emit a private helper `releaseSavepoint(Savepoint savepoint) throws SQLException` that calls `connection.releaseSavepoint(savepoint)` and catches `SQLException e`: when `e` is a `SQLFeatureNotSupportedException`, or its `getSQLState()` is in class `0A` ("feature not supported", which is how JDBC defines that exception, for drivers that raise a plain `SQLException`), it returns, with a comment saying the marker dies with the enclosing transaction anyway; otherwise it rethrows. Both release sites in `commit` call the helper. A rethrown failure reaches `commit`'s existing `catch` and becomes the `DataAccessException` it is today, now with consistent state behind it.
* **Javadoc.** The class javadoc on `GraphitronTransactionProviderGenerator` gains a short paragraph covering both rules: a commit that throws leaves its scope open for the rollback jOOQ issues next, and savepoint release is skipped where the driver does not implement it, but no other release failure is hidden, because a swallowed release over an aborted transaction turns into a silent rollback at commit.

No logging of the tolerated case: the emitted class depends only on the JDK and jOOQ, and on Oracle the event fires on every nested commit, so it would be noise.

No user-manual change. The `@service` reference already tells authors to open `ctx.transactionResult(...)` themselves; nested transactions working on every driver is the behaviour they already assume, and the schema and service contract are unchanged.

## Tests

**Unit tier**, in `GraphitronTransactionProviderGeneratorTest`, which compiles the emitted provider through `EmittedCodeHarness` and drives it against a recording fake `Connection`, the way jOOQ would (a failed `commit` followed by `rollback` on the same scope). Two changes to the fake first: `setSavepoint` mints a distinct labelled savepoint per call (`sp1`, `sp2`, ...) and logs the label, and the release and rollback-to events record their target (`releaseSavepoint:sp2`, `rollbackToSavepoint:sp3`), so the log shows *which* scope each call closed; and the fake can be told to throw a given `SQLException` from `releaseSavepoint` or `commit` (after recording the event). Existing expected logs gain the labels and otherwise stay as they are. New cases:

* `nested_commit_toleratesUnsupportedReleaseSavepoint`: `COMMIT` policy; begin, begin, commit, commit, release throwing `SQLFeatureNotSupportedException`. Nothing escapes; the log is `setAutoCommit:false, setSavepoint:sp1, releaseSavepoint:sp1, commit, setAutoCommit:true`. The sis failure in miniature.
* `nested_commit_toleratesSqlState0A`: the same with a plain `SQLException` carrying SQLState `0A000`.
* `rollbackOnly_fieldSettle_toleratesUnsupportedReleaseSavepoint`: `ROLLBACK_ONLY`; two fields, each begin then commit, release throwing. Nothing escapes; the second field reuses the open transaction (no second `setAutoCommit:false`) under its own `sp2`.
* `nested_releaseFailure_propagates_andTheFollowingRollbackClosesTheSameScope`: begin, begin, commit with release throwing a plain `SQLException` (SQLState `25P02`), then rollback, as jOOQ does. The commit throws `DataAccessException`; the rollback logs `rollbackToSavepoint:sp1`, not a top-level `rollback`. Then begin, commit, commit: the log shows `sp2` released and the top-level `commit, setAutoCommit:true`, proving `depth` and the stack came back consistent.
* `topLevel_commitFailure_isFollowedByASingleTopLevelRollback`: begin, commit with `commit` throwing, then rollback. The log ends `commit, rollback, setAutoCommit:true`; a second top-level begin/commit afterwards logs a fresh `setAutoCommit:false` and commits normally.

**Execution tier**, a new `TransactionProviderExecutionTest` in `graphitron-sakila-example`'s `querydb` test package, against the real Postgres the tier already runs (container setup as in `TenantRoutingExecutionTest`), with a `DSLContext` over one connection carrying `new GraphitronTransactionProvider(connection, CommitPolicy.COMMIT)`. One case pins what no fake can, that pgjdbc reports the aborted-transaction release as something the helper rethrows: an outer `transactionResult` inserts a row, runs a nested `transaction` whose lambda executes a failing statement, catches the failure, and returns normally, and the outer lambda catches the nested commit's `DataAccessException` and returns. The row is present after the outer commit (the nested scope rolled back to its savepoint and the outer transaction recovered), and a second top-level transaction on the same provider commits. Clean the row up in the test, since it commits for real.

There is no Oracle in the build, so the Oracle path is pinned by the unit fakes. A re-run of the two sis tests after the item lands is welcome confirmation but is not a gate on Done.

## Other solutions we've considered

* **Swallow every release failure, as jOOQ does.** Matches v9 behaviour exactly and is one line, but over an aborted Postgres transaction it turns a reported error into a commit that reports success and persists nothing (see Background). Tolerating only the unsupported-feature failure delivers the Oracle fix without that.
* **Skip the release per dialect** (the reporter's alternative): consult `SQLDialect` and never call `releaseSavepoint` on Oracle. It needs a dialect list graphitron maintains and misses drivers nobody listed, and the provider holds a bare `Connection`, not a dialect, so the constructor would widen. The SQLState-class check is the standard's own definition of "not supported" and needs no list.
* **Leave the commit-failure bookkeeping to its own item.** Narrowing the release catch deliberately keeps a rethrow path live, and that path is the one that corrupts `depth`; specifying the narrow catch without fixing what follows the rethrow would ship a known-broken failure path. Both changes are in the same emitted method.
* **Remember that release is unsupported** after the first failure and stop calling it. Saves one thrown exception per nested commit on Oracle, far below the cost of the statements around it, at the price of state on the provider. Not worth it.

## Implementation notes

Landed as planned, all in `GraphitronTransactionProviderGenerator`. `commit` now branches on `depth == 1` (top-level and deferred-rollback arms) versus nested, releases `savepoints.peek()` through the new private `releaseSavepoint(Savepoint)` helper, and only then pops and decrements, so jOOQ's follow-up `rollback` after a failed commit closes the same scope once. The helper returns on `SQLFeatureNotSupportedException` or SQLState class `0A` and rethrows everything else; the class javadoc gained a "A failed commit leaves its scope open" section stating both rules.

Where the goal is demonstrated:

* `GraphitronTransactionProviderGeneratorTest`: the fake connection now mints labelled savepoints and records each release / rollback-to target, and can throw from `releaseSavepoint` or `commit`. The five new cases are the ones the plan names (`nested_commit_toleratesUnsupportedReleaseSavepoint` is the sis failure in miniature); existing expected logs gained the labels only.
* `TransactionProviderExecutionTest` (`graphitron-sakila-example`, `querydb`): against real Postgres, a nested commit over an aborted transaction surfaces as `DataAccessException` with a `25P02` cause, the enclosing write commits, and a second top-level transaction on the same provider commits with autocommit restored. It writes to a throwaway `tx_provider.marker` table dropped after the class, rather than a Sakila table.

---
id: R1055
title: "GraphitronTransactionProvider closes the enclosing scope when a begin fails"
status: In Review
bucket: bug
theme: runtime-connection
depends-on: []
created: 2026-10-16
last-updated: 2026-10-26
---

# GraphitronTransactionProvider closes the enclosing scope when a begin fails

## Goal

When opening a transaction fails on the owned-connection engine, only that transaction fails: the transaction around it keeps its writes, and every later mutation field in the same GraphQL operation runs normally. The *owned-connection engine* is the execution mode where graphitron pins one connection per operation (`runtime.newGraphQL(schema)`), and every transaction on that connection goes through the emitted `GraphitronTransactionProvider`. Today a failed open instead closes the transaction around it. On Postgres this happens whenever service code catches a failed statement and then opens a nested `transactionResult`. The enclosing transaction is rolled back, and the provider's record of which transactions are open stays wrong for the rest of the operation. R1054 fixed the same class of fault for a failed commit; this item fixes it for a failed open, and does it in a way the scope R1101 opens per mutation field can build on.

## Current behaviour

Verified against trunk at `e2d0d1778` and the jOOQ 3.20.11 sources:

* `DefaultDSLContext.transactionResult0` calls `provider.begin(ctx)` inside the same `try` as the transaction body and `provider.commit(ctx)`. Any throw before the commit succeeded, a throw from `begin` included, is followed by `provider.rollback(ctx)` on the same `TransactionContext` object, and an exception from that rollback is added as a suppressed exception to the original cause rather than thrown. jOOQ builds a fresh `DefaultTransactionContext` for every `transactionResult` call, with its own `data()` map.
* The emitted provider (`GraphitronTransactionProviderGenerator`) tracks open transactions with a `depth` counter and a `Deque<Savepoint>`. A *scope* here is one transaction the provider demarcates: the top-level transaction, or a savepoint nested inside it. `begin` makes its JDBC call (`setAutoCommit(false)` at depth 0 under `COMMIT`, `setSavepoint()` deeper or under `ROLLBACK_ONLY`) and only then pushes and increments. `commit` pops and decrements only after its JDBC call succeeded, since R1054. `rollback` decrements first, then settles or rolls back to `savepoints.pop()`, choosing by the new `depth` and the commit policy.
* So jOOQ's rollback after a failed `begin` is applied to the enclosing scope:
  * Nested `begin` fails under `COMMIT` at depth 1: `rollback` takes `depth` to 0 and runs `settle(true)`, rolling back the outer transaction and asserting autocommit. The outer scope's own rollback or commit then takes `depth` to -1 and pops an empty stack (`NoSuchElementException`, suppressed by jOOQ).
  * Nested `begin` fails under `ROLLBACK_ONLY`: `rollback` pops and rolls back to the enclosing field's savepoint.
  * Top-level `setAutoCommit(false)` fails: `rollback` takes `depth` to -1 and pops an empty stack.
* On Postgres, `setSavepoint()` inside an aborted transaction fails with SQLState `25P02`. A transaction is aborted after any failed statement, so the nested case is reached whenever service code catches a failed statement and then opens a nested transaction.
* `commit` and `rollback` choose what to do from `depth` and the commit policy (`depth == 1 && ROLLBACK_ONLY` releases, `depth == 1` settles, deeper releases). Nothing ties a `commit` or `rollback` call to the scope its `begin` opened; that tie is the call-ordering assumption both this bug and R1054's broke.

## Implementation

The change is in `GraphitronTransactionProviderGenerator`, plus the prose that describes the provider by its depth counter elsewhere (see Retired vocabulary); the emitted provider's public surface (`CommitPolicy`, the constructor, the three `TransactionProvider` methods) is unchanged.

* **Scopes are entries, not a count.** Replace `depth` and `Deque<Savepoint> savepoints` with one `Deque<Scope> scopes`. `Scope` is a private sealed interface in the emitted class with two record variants: `TopLevel`, a top-level transaction under `COMMIT`, and `Savepointed(Savepoint savepoint)`, every other scope. Sealed interfaces and records are valid Java 17; dispatch on them with `instanceof` patterns, since a pattern `switch` needs 21. Scopes are compared by identity (`==`), never by record `equals`. The nesting depth, where still needed, is `scopes.size()`.
* **An open is all-or-nothing.** `begin` makes its JDBC calls first and changes provider state only as its last step: it pushes the new `Scope` and records it on the context with `ctx.data(SCOPE_KEY, scope)`, where `SCOPE_KEY` is a private static key object of the emitted class. If any JDBC call throws, nothing is pushed or recorded. The decision tree is unchanged, now keyed on `scopes.isEmpty()` instead of `depth == 0`: empty under `ROLLBACK_ONLY` turns autocommit off if it is on and takes a savepoint; empty under `COMMIT` turns autocommit off; otherwise takes a savepoint. Factor the open into a private method returning the `Scope`, so R1101's carrier-driven open can reuse it.
  * One partial state remains and is sound: under `ROLLBACK_ONLY` with no scope open, `setAutoCommit(false)` can succeed and `setSavepoint()` then fail. Autocommit stays off with no scope recorded. The next open finds autocommit off and reuses the transaction, as it does for a second field today, and `PinnedConnection.release` discards the transaction at operation end. Undoing the `setAutoCommit(false)` instead would add a second JDBC call that can fail on the error path.
* **Closing names its scope.** `commit` and `rollback` read their scope from `ctx.data(SCOPE_KEY)` and close that scope, dispatching on the scope's variant rather than on depth and policy: a `Savepointed` scope releases its savepoint (commit) or rolls back to it (rollback); a `TopLevel` scope settles through `settle(false)` or `settle(true)`. This replaces the `depth == 1 && ROLLBACK_ONLY` arm: a `ROLLBACK_ONLY` field scope already carries its savepoint, so it needs no special case. Factor the close into a private method taking the `Scope` and the outcome, again for R1101.
  * `rollback` for a context with no scope recorded is the failed-open case: it returns without touching the connection or the stack.
  * A scope that is not the top of `scopes` is never closed. `commit` or `rollback` on such a scope throws `IllegalStateException` naming the mismatch and leaves the stack as it is. jOOQ cannot produce this ordering through `transactionResult`; the check turns a future ordering bug into an error at the site instead of a wrong depth fields later. From `rollback` jOOQ attaches it to the original cause as a suppressed exception, which is still visible in the log and in tests.
  * `commit` for a context with no scope recorded is the same invariant violation and throws.
  * `commit` removes the scope (pop, and clear the context entry) only after its JDBC call succeeded, so the rollback jOOQ issues after a failed commit closes the same scope once. This keeps R1054's behaviour.
  * `rollback` removes the scope whether or not its JDBC call succeeded, as today's `rollback` already does: nothing will call rollback for that scope again, and a failed top-level rollback is left to `PinnedConnection.release`, whose open-transaction detection already covers it.
* **`releaseSavepoint` and `settle`** keep their current bodies, including R1054's tolerance of drivers that do not implement release.
* **Class javadoc.** Replace the "A failed commit leaves its scope open" section with one stating the scope contract: an open records its scope only once it has fully succeeded, every close names its scope and refuses one that is not innermost, a failed commit leaves its scope open for jOOQ's follow-up rollback, and a rollback for a scope that never opened changes nothing. The "Single-connection safety" section's "nesting depth and savepoint stack" becomes the scope stack.

What this item does not fix: after `25P02` the enclosing Postgres transaction is aborted, so its writes can be saved only by rolling back to an earlier savepoint. If service code catches the failed open and lets the enclosing transaction try to commit, the outcome is the driver's commit on an aborted transaction, which is the commit-failure question R1101 has to answer for the per-field scope. This item guarantees only that the provider's record of open scopes stays correct either way.

## Tests

Unit tier, `GraphitronTransactionProviderGeneratorTest`. The failed-open cases go through jOOQ's own `transactionResult` over the existing fake connection (a `DefaultConfiguration` with the fake connection and the compiled provider), since the bug is in how the provider meets jOOQ's call sequence; driving `begin`/`rollback` by hand would assume the sequence the test exists to check. The existing direct-invocation tests stay, with the `null` context they pass today replaced by a small fake `TransactionContext` whose `data` methods are backed by a map. The fake connection gains injectable `setSavepoint` and `setAutoCommit(false)` failures beside the existing release and commit ones.

* A nested open failing under `COMMIT`, with the outer body letting the exception propagate: the exception is a `DataAccessException` with message "Could not begin transaction", the call log shows no rollback between the failed `setSavepoint` and the outer scope's own single `rollback` and `setAutoCommit:true`, and the thrown exception carries no suppressed exceptions.
* The same, with the outer body catching the exception: the outer scope commits once.
* A top-level `setAutoCommit(false)` failing under `COMMIT`: no rollback reaches the connection, and a second `transactionResult` on the same provider runs the full top-level sequence.
* A nested open failing under `ROLLBACK_ONLY`, with the field body catching the exception: the enclosing field's savepoint is untouched, and the field's own commit releases that savepoint.
* Under `ROLLBACK_ONLY` with no scope open, `setAutoCommit(false)` succeeding and `setSavepoint()` failing: the next field's open reuses the open transaction (no second `setAutoCommit:false`) and takes a savepoint.
* Closing out of order: with two scopes open, `commit` on the outer scope's context throws `IllegalStateException` and the inner scope still closes normally afterwards.
* The R1054 tests keep passing unchanged in what they assert.

Execution tier, `TransactionProviderExecutionTest` against real Postgres: inside an outer transaction, insert a marker, run a failing statement and catch it, then open a nested transaction. The nested open throws with a `25P02` cause and no suppressed exceptions; the exception propagates out of the outer transaction, which rolls back once (no marker); a second transaction on the same provider commits its marker; the connection is back in autocommit.

## Implementation notes

* The graphitron JavaPoet fork emits no records, so `TopLevel` and `Savepointed` are private static final classes implementing a private `SEALED` interface, following the scatter `Outcome` precedent in `ConnectionRuntimeClassGenerator`; identity equality is what plain classes have, which is what the plan asks for. The private methods are `open()`, `close(Scope, boolean failed)` and `requireInnermost(Scope, String)`, for R1101 to reach.
* `rollback` checks innermost, then pops and clears the context entry before its JDBC call, so a failed rollback leaves no scope behind; `commit` checks innermost and pops only after its JDBC call succeeded.
* Every new or changed unit test fails against the provider as it stood before this change (8 of 21), and the 13 existing ones pass on both.

## Retired vocabulary

* The `depth` field and the `savepoints` field of the emitted `GraphitronTransactionProvider`, and the provider-javadoc phrase "nesting depth and savepoint stack". Test names and comments in `GraphitronTransactionProviderGeneratorTest` that describe behaviour in terms of `depth` are renamed in terms of scopes. The same wording outside the generator goes too: the carrier javadoc and the `entryFor` comment in `ConnectionRuntimeClassGenerator` ("the provider's `depth` is the only nesting counter"), the provider constructor's javadoc, and the assertion message in `TransactionProviderExecutionTest` ("the provider's depth and savepoint stack came back consistent").

## Relation to other items

* **R1101** (Spec), one transaction per top-level mutation field: depends on this item. Under R1101 every `@service` mutation runs inside a field scope, so the failed nested open described above would close the field scope. This item factors the all-or-nothing open and the scope-naming close into private methods; R1101 decides how its provider-side open and settle reach them, including any widening of visibility.
* **R1097** (Done), rejected session identity: no interaction. The mount runs in `PinnedConnection.acquire` in autocommit, before any scope opens, so a rejected identity never reaches the provider.

## Other solutions we've considered

* **Record on the context only, keep `depth`.** `begin` marks the context on success and `rollback` returns when the mark is missing. It fixes the jOOQ path with the smallest diff, but R1101's carrier opens a scope without a `TransactionContext` and without a follow-up rollback, so it would have to solve the same failure again on a counter whose meaning depends on call order.
* **A provider-wide "last begin failed" flag** consumed by the next rollback. Smaller still, and it relies on the next rollback being the one for that begin, which is the same kind of ordering assumption this item removes.
* **Increment before the JDBC call and push a placeholder.** The follow-up rollback would then close the right scope, but it would try to roll back to a savepoint that does not exist, and the partial state would leak into the stack.

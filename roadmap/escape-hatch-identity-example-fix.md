---
id: R1155
title: "Fix the escape-hatch identity example, which mounts nothing in autocommit"
status: In Review
bucket: bug
priority: 2
theme: runtime-connection
depends-on: []
created: 2026-11-05
last-updated: 2026-11-06
---

# Fix the escape-hatch identity example, which mounts nothing in autocommit

## Goal

A consumer on the escape-hatch path who copies the documented identity example gets a request that runs under the caller's identity from its first query to its last, and returns a connection to the pool that carries no identity. Today the example in `docs/architecture/reference/runtime-extension-points.adoc` (section "On the escape-hatch path", under Row-Level Security) sets `app.user_id` with `set_config(..., true)` on a freshly borrowed connection in autocommit. That setting reverts at the end of its own statement, so every query Graphitron runs afterwards sees no identity and a fail-closed policy returns nothing. When this lands the example sets identity session-scoped, clears it before closing the connection, and evicts the connection when the clear cannot be shown to have happened: by hand, the shape the owned path's mount and unmount already have. In the same area, the hook state contract stops claiming that owned-path acquisition refuses a connection the pool hands over with autocommit off. Acquisition forces autocommit on, and after this item it first rolls back any transaction that connection carried open, instead of committing it.

The two paths, glossed once. On the *owned-connection path* Graphitron borrows the connection itself and runs the consumer's *mount* callback (which writes the caller's identity onto the connection) when it takes one and the *unmount* callback (which clears it) before returning it. On the *escape-hatch path* the consumer hands Graphitron a `DSLContext` through `Graphitron.newExecutionInput(dsl, ...)` and owns the connection, its transactions and its identity, with no callbacks. A *fail-closed* policy is one that matches no rows when identity is missing, such as the probe policy the docs use:

```sql
CREATE POLICY caller_isolation ON documents
    USING (owner_id = NULLIF(current_setting('app.user_id', true), ''));
```

The one line that sets identity, in the four forms a consumer might write it, on PostgreSQL with jOOQ's default transaction provider:

| Form | What the request sees | What the pool's next borrower sees |
|---|---|---|
| `set_config(..., true)` in autocommit (the example today) | identity for that one statement, none after | nothing |
| `set_config(..., true)` inside a request-wide transaction | identity until the first generated DML mutation field commits, none after | nothing |
| `set_config(..., false)`, never cleared | identity throughout | the previous caller's identity |
| `set_config(..., false)`, cleared after execution (this item) | identity throughout | nothing |

## The example (first-client draft)

This replaces the body of "=== On the escape-hatch path" in `docs/architecture/reference/runtime-extension-points.adoc`:

> You own the connection and the identity on it, so you do by hand what the owned path's mount and unmount do: set identity session-scoped on a connection in autocommit before the request runs, and clear it before the connection goes back to the pool. Hold one connection for the whole request, so the set, every query, and the clear share it.
>
> ```java
> // ILLUSTRATIVE: session identity on the escape-hatch DSLContext
> Connection conn = dataSource.getConnection();
> try {
>     settle(conn);
>     DSLContext ctx = DSL.using(conn, SQLDialect.POSTGRES);
>     ctx.execute("select set_config('app.user_id', ?, false)", userId); // false: session-scoped
>     var input = Graphitron.newExecutionInput(ctx, /* contextArguments... */).query(q).build();
>     result = engine.execute(input);
> } finally {
>     try {
>         settle(conn);
>         DSL.using(conn, SQLDialect.POSTGRES).execute("select set_config('app.user_id', '', false)");
>         conn.close();
>     } catch (Exception clearFailed) {
>         conn.abort(Runnable::run); // identity not proven cleared: evict, never hand it on
>         throw clearFailed;
>     }
> }
>
> // Autocommit on, with nothing left open. Roll back first: JDBC commits an open
> // transaction when autocommit is switched on.
> static void settle(Connection conn) throws SQLException {
>     if (!conn.getAutoCommit()) {
>         conn.rollback();
>         conn.setAutoCommit(true);
>     }
> }
> ```
>
> Why each piece is there:
>
> * *Session scope, set in autocommit.* The third argument `false` makes the setting last for the session; set in autocommit it is committed at once, so the commits and rollbacks your request goes on to make cannot revert it. `true` does not work in either place you might put it. In autocommit it reverts at the end of its own statement. Inside a transaction you open around the whole request it reverts at that transaction's first commit, and Graphitron's generated mutations each open and commit a transaction on the `DSLContext` you pass (the manual's `@routine` and `@mutation` reference pages describe that commit; link them in the real section), so identity ends with the first mutation field.
> * *The clear runs after execution, in autocommit.* With `engine.executeAsync(...)`, run the `finally` block when the returned future completes, not when `executeAsync` returns; the connection stays borrowed until then. The `settle` before the clear matters: a clear issued inside a transaction your request left open is reverted when the pool rolls that transaction back on return.
> * *Clear to the empty string.* A custom setting PostgreSQL has seen cannot return to unset; the policy's `NULLIF` treats `''` as no identity.
> * *Evict when the clear fails.* `close()` hands a connection back to the pool, still carrying identity if the clear did not run; `abort` makes the pool discard it.
>
> Graphitron's own reset at release covers only connections it borrowed itself, so the clear on this path is yours. `EscapeHatchSessionIdentityExecutionTest` runs this shape against PostgreSQL with row-level security.

The sentence about the reset at release depends on the release-time reset item (R1152); if this item lands first, drop that sentence and let R1152 add it.

## Acquisition: the hook state contract against the code

The contract's third bullet ("*You may assume no transaction is open.*", in `docs/architecture/reference/runtime-extension-points.adoc`) says acquisition "asserts the connection arrives in autocommit and fails closed if it does not (a pool configured `autoCommit=false` is a deployment error surfaced at the first acquisition, not silently worked around)". The emitted `PinnedConnection.acquire` does neither. It calls `connection.setAutoCommit(true)` unconditionally, so a pool configured with `autoCommit=false` works, and a connection handed over with a transaction open has that transaction committed, because JDBC commits it when autocommit is switched on. The code's own vocabulary, in the `ConnectionRuntimeClassGenerator` class javadoc ("Graphitron asserts the connection's transaction mode"), already uses "asserts" to mean "imposes, never inherits", so the contract bullet is the outlier.

The plan keeps the code's behaviour, closes the commit hazard, and fixes the doc to match:

- **Force, do not refuse.** A pool shared with an application that configures `autoCommit=false` (a common setting alongside JPA) is a legitimate deployment. Graphitron already hands connections back in autocommit, and pools restore their own configured mode on return, so refusing would buy nothing and break those consumers on every request.
- **Roll back before forcing.** If the connection arrives with autocommit off, roll back first, so work Graphitron did not issue is discarded rather than committed under the caller's request. This is the order `release()` already uses, for the same JDBC reason. On a connection with no transaction open, the rollback does nothing.

## Implementation

- **`ConnectionRuntimeClassGenerator.pinnedConnection`, the emitted `acquire(...)`.** Before the existing `connection.setAutoCommit(true)`, emit `if (!connection.getAutoCommit()) { connection.rollback(); }`. Keep the `setAutoCommit(true)` unconditional, so the normal-path call log is unchanged apart from the rollback. Both calls stay inside the outer `try` whose `catch (Throwable mountFailure)` evicts, and outside the inner `catch` that maps mount failures, so a refused rollback evicts the connection and propagates as itself, never as `SessionMountException`, as a refused `setAutoCommit` already does. Update the emitted comment above the call to say it rolls back what the pool left open before forcing the mode. The emitted `acquire(...)` javadoc, both variants, reads as a check today ("asserts autocommit on it", "a failed autocommit assertion evicts"); consumers read generated code without the generator's javadoc beside it, so it says "forces autocommit on it, first rolling back any transaction the pool left open" and "a failure doing so evicts the connection".
- **`ConnectionRuntimeClassGenerator` class javadoc.** The *Acquire* bullet of the lifecycle contract gains the rollback step ("`DataSource.getConnection()`, then a rollback if autocommit is off, then `setAutoCommit(true)`, then the mount"), and "Graphitron asserts the connection's transaction mode" gains one sentence: acquire rolls back before switching for the same reason release does.
- **`docs/architecture/reference/runtime-extension-points.adoc`, the hook state contract.** The third bullet's acquisition clause becomes: acquisition puts the connection in autocommit before the mount runs, first rolling back any transaction the pool handed over open (switching autocommit on would otherwise commit it), so a pool configured with `autoCommit=false` works and nothing it left open is committed under your request. The release clause stays as it is. The "Only the mount is an identity fault" bullet already covers a connection that refuses autocommit, and now covers a refused rollback; no change.
- **`docs/architecture/reference/runtime-extension-points.adoc`, "On the escape-hatch path".** Replace the section body with the draft above.
- **The same page, the closing paragraph of "Transactions on the owned-connection path".** Its first sentence, "On the escape-hatch path you own the `DSLContext` and its transactions, and Graphitron opens no scope", sits beside the new example and contradicts its rationale. It becomes: on the escape-hatch path Graphitron opens no scope per mutation field; each generated write runs in its own transaction on your `DSLContext`, which commits when the write's statement returns (the wording the manual's `@routine` page already uses). The recipe sentence after it is out of scope, below.

R1152 edits the same `acquire(...)` method, adding a settings capture "after `connection.setAutoCommit(true)`". The rollback goes before that line, so neither plan's text goes stale; whichever lands second rebases a few adjacent lines. No `depends-on` either way.

## Tests

*Unit tier, `ConnectionRuntimeClassGeneratorTest`.* Its `fakeConnection` deliberately starts with autocommit off, so every expected acquire log gains `"rollback"` between `"getConnection"` and `"setAutoCommit:true"`; update those logs, and keep the fake's default, since that is the fixture in which removing the forcing fails. The fake logs `rollback` but not `getAutoCommit`, so no other log changes. New cases:

- a connection that arrives with autocommit on: no `rollback` in the log (the fake gains an initial-mode parameter);
- the rollback throws: the log ends `"getConnection", "abort"`, the mount never runs, and the thrown exception is the rollback's own, not `SessionMountException`.

*Execution tier, `SessionHookExecutionTest`.* Its acquire rows already run against a `DataSource` handing out `autoCommit=false` connections. Add one whose connection arrives with an uncommitted insert, under a marker key the test chose, into an existing table the connection's role may write: after acquire and release, the superuser finds no row with that key, and the test deletes by that key in its cleanup. Today the insert is committed by `setAutoCommit(true)`, so the case fails before the change and passes after. Per the execution tier's rule for shared databases (`docs/architecture/how-to/testing.adoc`), it counts only its own key, never the table.

*Execution tier, a new `EscapeHatchSessionIdentityExecutionTest` beside `RoutineCarrierRlsExecutionTest`.* It reuses that test's fixture: the `secure_note` table and its policy from `init.sql`, the `createSecureNote` mutation, whose post-commit re-read runs under the caller's identity, and a non-superuser probe role, since superusers bypass RLS. The example's shape lives in one helper that mirrors the documented code; the class javadoc names the doc section it mirrors, and the doc section names the class, so a reader of either finds the other. Each case writes a note under a marker text it chose and deletes by that text, never the whole table. Cases, each a `createSecureNote(owner: "alice", ...)` mutation that should re-read its own row:

- the documented form: the re-read returns the row, so identity survived the mutation's commit; after the helper's clear and before `close()`, so the read is certain to reach the same physical connection, `current_setting('app.user_id', true)` reads `''`;
- today's form, `set_config(..., true)` in autocommit: the re-read returns null;
- a request-wide transaction with `set_config(..., true)`: identity is visible before the mutation (control), and the re-read returns null. This pins why the example does not take this form.

The last two pin the doc's stated reasons, so a future jOOQ or Graphitron change that alters them fails here rather than leaving the prose wrong.

## Implementation notes

Delivered as planned; where the code and the plan differ, it is listed here.

- **A refused rollback at acquire surfaces wrapped, not as itself.** The emitted `rethrow` returns an unchecked failure unchanged and wraps a checked one in `RuntimeException`, so the rollback's `SQLException` arrives as the cause, exactly as a refused `setAutoCommit` already did (`SessionHookExecutionTest.failedAutocommitAssertion_isNotARejectedIdentity` asserts the root cause). What the plan's "as itself" protects holds: it is never `SessionMountException`. `ConnectionRuntimeClassGeneratorTest.acquire_rollbackFailure_evictsBeforeTheMount_andIsNeverARejectedMount` pins the log `"getConnection", "abort"`, the not-a-rejection, and the cause; the emitted `acquire(...)` javadoc says the same.
- **`TenantScatterSubstrateTest` also logs the acquire rollback.** Its fake connection arrives with autocommit off and logs `rollback:<label>`, so `pinInFlightAcrossRelease_isAbortedAndRefused` gains `rollback:A` in its exact log. No other case there changed.
- **`RoutineCarrierRlsExecutionTest` deletes only its own notes.** It deleted every `secure_note` row, which under the module's class-level parallelism could remove the new test's row between its routine's commit and re-read. It now deletes by its two note texts. The new test also uses its own caller name (`escape-hatch-alice`), because `RoutineCarrierRlsExecutionTest` counts every row visible to `alice`.
- **The manual says it too.** `docs/manual/reference/mojo-configuration.adoc`, "When your callbacks run", gains one sentence: a pool lending connections with autocommit off works, and what it left open is rolled back, not committed. The behaviour is consumer-visible, and that section already states the autocommit fact consumers rely on.
- **The doc-symbol guard exempts the new test by module.** `docs/architecture/reference/runtime-extension-points.adoc` names `EscapeHatchSessionIdentityExecutionTest`, which lives in `graphitron-sakila-example`, off `graphitron`'s test classpath; `ArchitectureDocSymbolGuardTest` carries it as a `module:` exemption beside `SessionIdentityPoolIsolationExecutionTest`.
- **The `@routine` and `@mutation` links** in the example's rationale point at `docs/manual/reference/directives/mutation.adoc` and `routine.adoc#write`.
- **R1152 landed first**, so the sentence about the reset at release stays as drafted.

## Out of scope

- **The thread-safety warning for the mount.** Already in the manual: `docs/manual/reference/mojo-configuration.adoc`, section "Session identity", says the callbacks run on whichever thread first needs a connection and must read no ambient state. It landed before this item was filed; nothing to do.
- **The consumer `Instrumentation` recipe for per-field transactions on the escape-hatch path** (the closing paragraph of "Transactions on the owned-connection path"). It has the same interaction as the request-wide form above: generated mutations commit the connection at the end of their own top-level jOOQ transaction, so a JDBC transaction the instrumentation opens would be committed early by the first generated write. That is a transaction-demarcation claim, not identity, and wants its own item if a spike confirms it.
- **Resetting escape-hatch connections from Graphitron.** Graphitron never sees these connections close; the consumer's clear is the only place a reset can go.

## Other solutions we've considered

- **A request-wide transaction with `set_config(..., true)`.** Attractive because it cannot leak by construction: the setting dies with the transaction, even if cleanup is forgotten. It fails on the escape-hatch path because jOOQ's `DefaultTransactionProvider`, which a plain `DSL.using(conn, dialect)` gets, calls `Connection.commit()` when its outermost scope closes, whether or not autocommit was already off. Generated DML and routine mutations open that scope through `<Provider>.owned(dsl).transactionResult(...)`, so the consumer's transaction, and the identity in it, ends at the first mutation field. It holds only if the consumer installs a transaction provider that joins an outer transaction, which is more than an example should ask.
- **`RESET ALL` or `DISCARD ALL` as the clear.** Both clear every custom setting, not just the one the example set; `RESET ALL` also resets built-ins the pool configured after connecting, and `DISCARD ALL` also reverts the role and drops temp tables and prepared statements. Clearing the one key the example set is the narrow, honest default. The reset-at-release item measures the trade-offs for the owned path, where Graphitron cannot know the keys.
- **Make the test helper the example's source, pulled into the doc by a tagged include.** It would make drift between the two impossible rather than merely findable. There is no precedent for including test sources into `docs/`, and the build wiring it needs outweighs one example. Instead each names the other.
- **Refuse a connection that arrives with autocommit off.** This is what the contract text claims today. Rejected above: it breaks shared-pool deployments for no safety gain once the rollback closes the commit hazard.

## Context

Found in an adversarial review of the owned-connection session lifecycle, prompted by a consumer asking whether `app.claims` can leak between requests. Siblings from the same review: R1152 (reset at release), R1153 (pool execution tests), R1154 (single-tenant carrier acquisition after release).

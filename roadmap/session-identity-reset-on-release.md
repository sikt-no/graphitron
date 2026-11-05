---
id: R1152
title: "Reset session variables at release as a backstop to the consumer's unmount"
status: Spec
bucket: architecture
priority: 1
theme: runtime-connection
depends-on: []
created: 2026-11-05
last-updated: 2026-11-05
---

# Reset session variables at release as a backstop to the consumer's unmount

## Goal

On PostgreSQL, a connection Graphitron hands back to the pool carries none of the session variables the request set, even when the consumer's unmount callback is wrong. Today release runs the consumer's unmount and nothing else, so whether identity is cleared rests on that callback alone. An unmount written with `set_config(..., true)` or `SET LOCAL` is transaction-scoped: in autocommit it clears nothing, raises nothing, and the connection returns to the pool still carrying the previous caller's claims. When this lands, release on PostgreSQL ends with `RESET ALL` after the unmount, so a defective unmount degrades to "the next borrower has no identity" (fail-closed) rather than "the next borrower is the previous user". The cost to consumers: a setting their pool applies with SQL after connecting (init SQL such as HikariCP's `connectionInitSql`) holds for a connection's first request only. Settings applied at connect time (pgjdbc's `options` and `currentSchema` properties, `ALTER ROLE ... SET`) and the current role are untouched.

The minimal pair, as two unmounts a consumer might write (the *unmount* is the callback Graphitron runs before returning a connection to the pool, meant to clear the identity the *mount* set when the connection was taken):

```java
// correct: session-scoped, clears immediately
(s, handle) -> s.configuration().dsl().execute("select set_config('app.user_id', '', false)")

// defective: transaction-scoped, reverts at the end of its own statement, clears nothing
(s, handle) -> s.configuration().dsl().execute("select set_config('app.user_id', '', true)")
```

Today the second passes every check Graphitron has. After this item, both leave `current_setting('app.user_id', true)` reading empty for the pool's next borrower.

## What the reset touches

A *GUC* is PostgreSQL's name for a configuration variable; session identity on PostgreSQL is carried in custom GUCs such as `app.user_id`, which RLS policies read with `current_setting`. *Init SQL* is the statement a connection pool runs once on each new physical connection. Measured on PostgreSQL 16 (the build's local database), with `app.user_id` set by `set_config(..., false)`, `search_path` set by a post-connect `SET`, `statement_timeout` set in the startup packet (`PGOPTIONS`, which is what pgjdbc's `options` property sends), and `SET ROLE` to a non-login role:

| Statement | `app.user_id` | post-connect `SET search_path` | startup `statement_timeout` | `SET ROLE` |
|---|---|---|---|---|
| `RESET ALL` | cleared to `''` | reset to default | kept | kept |
| `DISCARD ALL` | cleared to `''` | reset to default | kept | reverted to login role |
| `RESET ROLE` | kept | kept | kept | reverted to login role |

Two further facts constrain the design. Custom GUCs do not appear in `pg_settings` (`select count(*) from pg_settings where name = 'app.user_id'` reads 0 while the variable is set), so Graphitron cannot enumerate or snapshot them. And `RESET ALL` does not deallocate server-side prepared statements, which `DISCARD ALL` does.

`RESET ALL` is the reset: it clears every custom GUC, which is what carries identity, and leaves the role alone, which matters because the common RLS deployment connects as the table owner and drops to a non-owner role in init SQL. A reset that reverted the role would hand the next borrower the owner, whom RLS does not apply to: a fail-open, the opposite of what a backstop is for.

## Implementation

- **`ConnectionRuntimeClassGenerator.pinnedConnection`, the emitted `release()`.** Inside the existing `if (unmounts)` emission, after the `GraphitronSessionHook.unmount(...)` call and still inside the `try`, emit:

  ```java
  if (dialect.family() == SQLDialect.POSTGRES) {
      if (!connection.getAutoCommit()) {
          throw new IllegalStateException("the unmount left autocommit off; identity cannot be proven reset");
      }
      try (Statement reset = connection.createStatement()) {
          reset.execute("RESET ALL");
      }
  }
  ```

  Any failure takes the existing `catch (Throwable releaseFailure)`: evict the physical connection, rethrow. The autocommit check exists because `RESET ALL` is transactional: an unmount that switched autocommit off would leave the reset in an open transaction the pool's return-rollback reverts. Graphitron evicts rather than settles there, since a callback that broke the contract that way gives no reason to trust anything else it did.
- **The dialect check is a runtime one.** The dialect is a per-source value (`Graphitron.runtime(dataSource, dialect)`, and each tenant source in a multi-tenant build carries its own), already held in `PinnedConnection.dialect`. A build mixing dialects resets only its PostgreSQL connections. `SQLDialect.family()` folds the versioned PostgreSQL dialects into `POSTGRES`.
- **Gate.** The reset is emitted exactly when the unmount call is, under `SessionIdentityRows.emitsHookImplementation()`. A build without `<sessionState>` mounts nothing and keeps today's release. Within a hooked build the reset runs whether or not the request's `SessionState` supplied an unmount, so the mount-only `SessionState.of(mount)` gets the backstop too.
- **Multi-tenant.** `TenantConnections.releaseAll` releases each entry through `PinnedConnection.release()` and inherits the reset with no change. The straggler `abort()` path evicts and needs none.
- **Dev executor.** `GraphitronDevExecutor` runs through the same `PinnedConnection`, over a connection `DevQueryExecutor` opens fresh per call with no post-connect SQL, so nothing changes there.
- **Javadoc.** The `ConnectionRuntimeClassGenerator` class javadoc's lifecycle "Release" bullet, the emitted `release()` javadoc, and the emitted `GraphitronSessionHook.unmount` javadoc name the reset as the step after the unmount and before the return to the pool.

## Tests

*Unit tier, `ConnectionRuntimeClassGeneratorTest`.* Its fake connection gains `createStatement`, returning a recording `Statement` that logs `execute:<sql>`. Without that the reset would hit a `null` statement and evict, so every existing release log for a hooked build under `SQLDialect.POSTGRES` changes: each gains `execute:RESET ALL` between the unmount and `close`, and that ordering is the assertion. New cases:

- the reset throws: the log ends in `abort`, never `close`, and `release()` rethrows;
- the unmount turns autocommit off: no `execute:RESET ALL`, the connection is aborted;
- a non-PostgreSQL dialect (`SQLDialect.H2`): no `createStatement` at all;
- a `SessionState.of(mount)` with no unmount: the reset still runs.

*Execution tier, `SessionHookExecutionTest`* (real PostgreSQL, the emitted `PinnedConnection`, the existing non-superuser probe role). Each case uses one raw connection wrapped in a `DataSource` whose `close()` leaves the physical connection open, a stand-in for a pool of one, so the test can read what the next borrower would see. The existing `singleConnectionDataSource` closes the connection for real; the new helper is a delegating proxy that swallows `close`.

- `defectiveUnmount_isBackstoppedByTheResetAtRelease`, a minimal pair on one connection. First call `GraphitronSessionHook.mount` and the defective unmount directly and assert `app.user_id` still reads `alice`, proving the unmount really is defective. Then acquire and release through `PinnedConnection` with the same `SessionState` and assert it reads empty and the connection was returned rather than aborted.
- `connectTimeSettingsSurviveTheReset_postConnectSettingsDoNot`: a connection opened with pgjdbc `options=-c statement_timeout=4321`, then a post-connect `SET search_path`. After one acquire and release, `statement_timeout` reads `4321ms` and `search_path` reads the default. This pins the claim the manual makes below.
- `serverPreparedStatementsSurviveTheReset`: ten acquire/release cycles on one physical connection, each running the same parameterized jOOQ query. pgjdbc switches to a server-prepared statement after `prepareThreshold` (5 by default) executions, so the later cycles run on a statement prepared before an earlier reset; all succeed.

*Through a real pool.* R1153's pool suite reserves "a consumer unmount that clears nothing" as a gap owed by this item. Whichever of the two lands second adds that case to the suite; if this item lands first, R1153 writes the case in rather than naming the gap.

## Documentation (first-client draft)

The reset is consumer-visible through the init-SQL cost, so the manual states it. In `docs/manual/reference/mojo-configuration.adoc`, section "When your callbacks run, and what your mount must mount", a new paragraph after the transaction-scoped warning:

> On PostgreSQL, graphitron ends every release with `RESET ALL`, after your unmount. It is a backstop, not a replacement: your unmount still runs first and should still clear what your mount set. What the reset buys is that a mistake there, such as an unmount written with `set_config(..., true)` that clears nothing, leaves the next borrower with no identity rather than with yours. It resets every setting changed with `SET` or `set_config` since the connection was opened, so a setting your pool applies with SQL on each new connection (HikariCP's `connectionInitSql`, Agroal's `initialSql`, Quarkus's `new-connection-sql`) holds for that connection's first request only. Put such settings where the reset leaves them: in the connection properties (pgjdbc's `options=-c search_path=app`, or `currentSchema=app`), or on the role (`ALTER ROLE app_user SET search_path = app`). The reset does not touch the role. A `SET ROLE` in your init SQL stays in effect; a `SET ROLE` in your mount stays too, so your unmount must undo it with `RESET ROLE` itself.

The section "The unmount is optional" currently argues the mount-only form from the next mount's overwrite, and warns that readers which never mount inherit the previous caller's identity. On PostgreSQL that warning no longer holds, so its first sentences become:

> On PostgreSQL the reset at release clears every session variable whether or not you supply an unmount, so `SessionState.of(mount)` leaves nothing behind for the pool's next borrower, graphitron or otherwise. On other databases, and for state the reset does not cover, such as a role switch, the unmount is what clears it.

followed by the existing reasoning, scoped to those cases. Whether `of(mount)` survives at all is R1151's decision; this item only keeps the page true on landing.

In `docs/architecture/reference/runtime-extension-points.adoc`: the owned-connection lifecycle sentence ("then runs your unmount callback and releases the connection") gains the reset, and "The hook state contract" gains one bullet: on PostgreSQL Graphitron issues `RESET ALL` after the unmount as a backstop, which neither relaxes the contract nor reverts the role.

## Out of scope

- **Oracle.** There is no generic cheap reset: application contexts clear per namespace (`DBMS_SESSION.CLEAR_ALL_CONTEXT` takes one) and a RAS detach takes the consumer's own session handle, neither of which Graphitron knows. Oracle stays on the consumer's unmount; R468 is its execution coverage.
- **The role.** See the table: resetting it reverts an init-SQL `SET ROLE`, a fail-open.
- **Advisory locks, temp tables, `LISTEN`.** Not identity. A request that leaks them is a different defect, and clearing them is what made `DISCARD ALL` too broad.
- **Connections Graphitron does not release.** An escape-hatch operation or `@service` code that borrows from the same `DataSource` itself returns its own connections. What this item gives those borrowers is that a connection Graphitron released reaches them clean.

## Other solutions we've considered

- **`DISCARD ALL`.** Clears everything `RESET ALL` does plus prepared statements, temp tables, advisory locks and listeners, and runs `SET SESSION AUTHORIZATION DEFAULT`, which reverts the role. The role revert is a fail-open under the init-SQL `SET ROLE` deployment. It also discards pgjdbc's server-prepared statements on every request.
- **Snapshot and restore.** Read the session's settings before the mount and restore only what changed after the unmount, leaving init-SQL settings alone. Not possible: custom GUCs are invisible to `pg_settings`, so the snapshot cannot see the variables that carry identity.
- **Reset named variables only.** The consumer lists the GUCs their mount sets, and Graphitron resets those. The backstop then depends on the consumer listing them correctly, the same class of mistake as the unmount it is backing up.
- **An opt-out flag.** A switch that disables a security backstop, for a cost (init-SQL settings) that has a fix keeping the reset on. Not offered; reconsider if a consumer shows a setting that can only be applied after connecting.

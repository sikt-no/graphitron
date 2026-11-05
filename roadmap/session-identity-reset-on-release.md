---
id: R1152
title: "Reset session variables at release as a backstop to the consumer's unmount"
status: Ready
bucket: architecture
priority: 1
theme: runtime-connection
depends-on: []
created: 2026-11-05
last-updated: 2026-11-05
---

# Reset session variables at release as a backstop to the consumer's unmount

## Goal

On PostgreSQL, a connection Graphitron hands back to the pool carries none of the session variables the request set, even when the consumer's unmount callback is wrong. Today release runs the consumer's unmount and nothing else, so whether identity is cleared rests on that callback alone. An unmount written with `set_config(..., true)` or `SET LOCAL` is transaction-scoped: in autocommit it clears nothing, raises nothing, and the connection returns to the pool still carrying the previous caller's claims. When this lands, release on PostgreSQL ends with a reset after the unmount: `RESET ALL`, which clears every custom session variable, followed by restoring the built-in settings the connection carried when Graphitron took it, before the mount ran. A defective unmount then degrades to "the next borrower has no identity" (fail-closed) rather than "the next borrower is the previous user", and the connection otherwise goes back to the pool as it arrived.

What changes for a consumer:

- Every request on PostgreSQL pays one extra query when Graphitron takes the connection: a read of `pg_settings`, measured at about 0.9 ms of server time on PostgreSQL 16, to capture what the reset restores.
- A custom variable (a dotted name such as `app.region`) that the pool sets after connecting, through init SQL such as HikariCP's `connectionInitSql`, holds for a connection's first request only, since nothing can enumerate custom variables to restore them. So does a built-in setting only a superuser may change. Both belong at connect time instead (pgjdbc's `options` property, `ALTER ROLE ... SET`), where the reset leaves them.
- Everything else the pool configures survives: built-in settings applied by init SQL, and those applied through pgjdbc's JDBC setters (HikariCP's `transactionIsolation`, `schema` and `readOnly`, the last under pgjdbc's `readOnlyMode=always`; Agroal's transaction isolation, which Quarkus exposes as `transaction-isolation-level`; and pgjdbc's own default `application_name`), which pgjdbc issues as session `SET`s after connecting. So do connect-time settings and the current role.

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

Further facts constrain the design, measured on the same database with pgjdbc 42.7.13 (the driver the build pins as `version.postgresql`):

- Custom GUCs do not appear in `pg_settings` (`select count(*) from pg_settings where name = 'app.user_id'` reads 0 while the variable is set), so Graphitron cannot enumerate or snapshot them. `RESET ALL` is the only generic way to clear them. A custom GUC sent in the startup packet (`options=-c app.region=eu`) survives `RESET ALL`, like any startup setting.
- Built-in settings changed in the session do appear, with `source = 'session'`; startup settings appear with `source = 'client'`. The role does not appear at all.
- pgjdbc applies several pool-facing settings as session `SET`s after connecting: `PgConnection.setTransactionIsolation` sets `default_transaction_isolation`, `setSchema` sets `search_path`, `setReadOnly` under `readOnlyMode=always` sets `default_transaction_read_only`, and the driver's default `application_name` goes out as a post-connect `SET` unless `assumeMinServerVersion` is at least 9.0. HikariCP calls its `transactionIsolation`, `schema` and `readOnly` setters once per physical connection (`PoolBase.setupConnection`) and calls them again on return only when the borrower changed them through the pool's proxy, which Graphitron never does; Agroal applies its isolation once at connection creation. `RESET ALL` alone therefore leaves such a connection at `read committed`, the default `search_path`, read-write and with an empty `application_name`, while the pool's and pgjdbc's client-side state still report the configured values.
- `set_config(name, current_setting(name), false)` round-trips a built-in value: `current_setting` returns the text form with its unit and quoting (`4321ms`, `app, "My Schema", public`).
- Reading `pg_settings` scans every setting whatever the filter: about 0.9 ms of server time on the local PostgreSQL 16 (364 settings).
- `RESET ALL` does not deallocate server-side prepared statements, which `DISCARD ALL` does.

The reset is `RESET ALL` followed by a restore. `RESET ALL` clears every custom GUC, which is what carries identity, and leaves the role alone, which matters because the common RLS deployment connects as the table owner and drops to a non-owner role in init SQL. A reset that reverted the role would hand the next borrower the owner, whom RLS does not apply to: a fail-open, the opposite of what a backstop is for. The restore puts back every built-in setting that had `source = 'session'` and `context = 'user'` when Graphitron took the connection, captured before the mount, so the pool's post-connect configuration survives and the reset changes only what the request itself changed.

What a mount that changes a built-in setting gets back follows from where the capture sits. The capture runs before the mount, so the mount's change is undone: to the captured value if the setting was session-set on arrival, otherwise by `RESET ALL` to the connect-time or server default. A mount that carries tenancy in `search_path` is backstopped the same way as one that carries it in `app.user_id`. Settings with `context = 'superuser'` are reset and not restored: restoring one under a non-superuser role (the init-SQL `SET ROLE` deployment) would fail on every release.

## Implementation

- **`ConnectionRuntimeClassGenerator.pinnedConnection`, the emitted `acquire(...)`: the capture.** After `connection.setAutoCommit(true)` and before the inner `try` that wraps the mount, emit:

  ```java
  String[][] restore = dialect.family() == SQLDialect.POSTGRES ? captureSessionSettings(connection) : null;
  ```

  with a private static helper on `PinnedConnection`:

  ```java
  private static String[][] captureSessionSettings(Connection connection) throws SQLException {
      List<String> names = new ArrayList<>();
      List<String> values = new ArrayList<>();
      try (Statement capture = connection.createStatement();
           ResultSet settings = capture.executeQuery("select name, current_setting(name) from pg_settings"
               + " where source = 'session' and context = 'user'")) {
          while (settings.next()) {
              names.add(settings.getString(1));
              values.add(settings.getString(2));
          }
      }
      return new String[][] {names.toArray(new String[0]), values.toArray(new String[0])};
  }
  ```

  `restore` is passed to the constructor and kept in a new final field beside `dialect`. The capture runs in autocommit (asserted just above it), so it opens no transaction. A failure takes the existing outer `catch (Throwable mountFailure)`: evict, rethrow. It sits outside the inner `catch (Exception rejected)`, like the autocommit assertion, so a failed capture is a deployment fault and never reaches the consumer's mapping of identity rejections.
- **The emitted `release()`: the reset.** Inside the existing `if (unmounts)` emission, after the `GraphitronSessionHook.unmount(...)` call and still inside the `try`, emit:

  ```java
  if (restore != null) {
      if (!connection.getAutoCommit()) {
          throw new IllegalStateException("the unmount left autocommit off; identity cannot be proven reset");
      }
      try (PreparedStatement reset = connection.prepareStatement("RESET ALL;"
              + " select set_config(n, v, false) from unnest(?::text[], ?::text[]) as t(n, v)")) {
          reset.setArray(1, connection.createArrayOf("text", restore[0]));
          reset.setArray(2, connection.createArrayOf("text", restore[1]));
          reset.execute();
      }
  }
  ```

  pgjdbc sends both statements in one round trip, so release costs what a bare `RESET ALL` would; the extra round trip is the capture at acquire. An empty capture makes the `unnest` produce no rows. Any failure, including a restore that the role current at release may not perform, takes the existing `catch (Throwable releaseFailure)`: evict the physical connection, rethrow. The autocommit check exists because `RESET ALL` is transactional: an unmount that switched autocommit off would leave the reset in an open transaction the pool's return-rollback reverts. Graphitron evicts rather than settles there, since a callback that broke the contract that way gives no reason to trust anything else it did.
- **The dialect check is a runtime one.** The dialect is a per-source value (`Graphitron.runtime(dataSource, dialect)`, and each tenant source in a multi-tenant build carries its own), already held in `PinnedConnection.dialect`. It is checked once, at acquire: a non-null `restore` is what tells release to reset, so a build mixing dialects captures and resets only its PostgreSQL connections. `SQLDialect.family()` folds the versioned PostgreSQL dialects into `POSTGRES`.
- **Gate.** The capture is emitted exactly when the mount call is, and the reset exactly when the unmount call is, both under `SessionIdentityRows.emitsHookImplementation()`; the `restore` field and `captureSessionSettings` helper exist only then. A build without `<sessionState>` mounts nothing and keeps today's acquire and release. Within a hooked build the reset runs whether or not the request's `SessionState` supplied an unmount, so the mount-only `SessionState.of(mount)` gets the backstop too.
- **Multi-tenant.** Each tenant entry is acquired through `PinnedConnection.acquire(...)` and `TenantConnections.releaseAll` releases each through `PinnedConnection.release()`, so both inherit the capture and the reset with no change. The straggler `abort()` path evicts and needs none.
- **Dev executor.** `GraphitronDevExecutor` runs through the same `PinnedConnection`, over a connection `DevQueryExecutor` opens fresh per call, so it captures and restores pgjdbc's `application_name` and nothing else; no change there beyond the extra query.
- **Javadoc.** The `ConnectionRuntimeClassGenerator` class javadoc's lifecycle bullets name the capture as the step between the autocommit assertion and the mount, and the reset as the step after the unmount and before the return to the pool; the emitted `acquire(...)` and `release()` javadoc, and the emitted `GraphitronSessionHook.mount` and `unmount` javadoc, say the same.

## Tests

*Unit tier, `ConnectionRuntimeClassGeneratorTest`.* Its fake connection gains `createStatement`, returning a `Statement` whose `executeQuery` logs `capture` and returns a fake `ResultSet` over rows the test sets (none by default), and `prepareStatement` plus `createArrayOf`, returning a `PreparedStatement` whose `execute` logs `reset` with the two bound arrays. Without them the capture would hit a `null` statement and evict, so every existing log for a hooked build under `SQLDialect.POSTGRES` changes: acquire gains `capture` between `setAutoCommit:true` and the mount, release gains `reset` between the unmount and `close`, and that ordering is the assertion. New cases:

- the capture returns rows: the arrays bound at release are exactly the names and values the capture returned at acquire;
- the capture throws: the mount never runs, the log ends in `abort`, and `acquire` rethrows the failure itself, not a `SessionMountException`;
- the reset throws: the log ends in `abort`, never `close`, and `release()` rethrows;
- the unmount turns autocommit off: no `reset`, the connection is aborted;
- a non-PostgreSQL dialect (`SQLDialect.H2`): no `createStatement` or `prepareStatement` at all;
- a `SessionState.of(mount)` with no unmount: the reset still runs.

*Execution tier, `SessionHookExecutionTest`* (real PostgreSQL, the emitted `PinnedConnection`, the existing non-superuser probe role). Each case uses one raw connection wrapped in a `DataSource` whose `close()` leaves the physical connection open, a stand-in for a pool of one, so the test can read what the next borrower would see. The existing `singleConnectionDataSource` closes the connection for real; the new helper is a delegating proxy that swallows `close`.

- `defectiveUnmount_isBackstoppedByTheResetAtRelease`, a minimal pair on one connection. First call `GraphitronSessionHook.mount` and the defective unmount directly and assert `app.user_id` still reads `alice`, proving the unmount really is defective. Then acquire and release through `PinnedConnection` with the same `SessionState` and assert it reads empty and the connection was returned rather than aborted.
- `poolConfiguredSettingsSurviveTheReset`: before the first acquire, do to the raw connection what a pool does to a new one: `setTransactionIsolation(TRANSACTION_SERIALIZABLE)` and `setSchema(...)`, as HikariCP's `setupConnection` does, and a `SET statement_timeout = '4321ms'`, as init SQL does. After one acquire and release, `default_transaction_isolation` reads `serializable`, `search_path` the schema set, `statement_timeout` `4321ms`, and `application_name` pgjdbc's default. It pins the restore: under `RESET ALL` alone all four revert, silently.
- `mountChangedBuiltinIsUndone`: a mount that sets `search_path` alongside `app.user_id`, with the defective unmount. After release `search_path` reads its value from before the mount.
- `customVariableFromInitSqlHoldsForTheFirstRequestOnly`: a post-connect `SET app.region = 'eu'` before the first acquire reads empty after release, while the same variable sent at connect time (pgjdbc `options=-c app.region=eu`) still reads `eu`. This pins the cost the manual states.
- `serverPreparedStatementsSurviveTheReset`: ten acquire/release cycles on one physical connection, each running the same parameterized jOOQ query. pgjdbc switches to a server-prepared statement after `prepareThreshold` (5 by default) executions, so the later cycles run on a statement prepared before an earlier reset; all succeed.

*Through a real pool.* R1153's pool suite reserves "a consumer unmount that clears nothing" as a gap owed by this item. Whichever of the two lands second adds that case to the suite; if this item lands first, R1153 writes the case in rather than naming the gap.

## Documentation (first-client draft)

The reset is consumer-visible through its two costs (custom variables set by init SQL, and the extra query at acquire), so the manual states it. In `docs/manual/reference/mojo-configuration.adoc`, section "When your callbacks run, and what your mount must mount", a new paragraph after the transaction-scoped warning:

> On PostgreSQL, graphitron ends every release with a reset, after your unmount. It is a backstop, not a replacement: your unmount still runs first and should still clear what your mount set. What the reset buys is that a mistake there, such as an unmount written with `set_config(..., true)` that clears nothing, leaves the next borrower with no identity rather than with yours. The reset hands the connection back to the pool as graphitron took it. Every custom variable (a dotted name such as `app.user_id`) is cleared. Every built-in setting (`search_path`, `statement_timeout`, the default transaction isolation and read-only mode, and the like) goes back to the value it had before your mount ran, so what your pool configures on each new connection survives, whether it does so with SQL or through its isolation, schema and read-only properties. Two kinds of setting do not survive past a connection's first request: a custom variable your pool sets with SQL after connecting (HikariCP's `connectionInitSql`, Agroal's `initialSql`, Quarkus's `new-connection-sql`), and a setting only a superuser may change. Set those at connect time instead, where the reset leaves them: in the connection properties (pgjdbc's `options=-c app.region=eu`) or on the role (`ALTER ROLE app_user SET app.region = 'eu'`). To know what to put back, graphitron reads the connection's settings each time it takes one from the pool: one extra query per request. The reset does not touch the role. A `SET ROLE` in your init SQL stays in effect; a `SET ROLE` in your mount stays too, so your unmount must undo it with `RESET ROLE` itself.

The section "The unmount is optional" currently argues the mount-only form from the next mount's overwrite, and warns that readers which never mount inherit the previous caller's identity. On PostgreSQL that warning no longer holds, so its first sentences become:

> On PostgreSQL the reset at release clears every custom variable and undoes every built-in setting your mount changed, whether or not you supply an unmount, so `SessionState.of(mount)` leaves nothing behind for the pool's next borrower, graphitron or otherwise. On other databases, and for state the reset does not cover, such as a role switch, the unmount is what clears it.

followed by the existing reasoning, scoped to those cases. Whether `of(mount)` survives at all is R1151's decision; this item only keeps the page true on landing.

In `docs/architecture/reference/runtime-extension-points.adoc`: the owned-connection lifecycle sentence ("then runs your unmount callback and releases the connection") gains the reset, and "The hook state contract" gains one bullet: on PostgreSQL Graphitron captures the connection's session-set built-in settings before the mount and, after the unmount, issues `RESET ALL` and restores them, as a backstop that neither relaxes the contract nor reverts the role.

## Out of scope

- **Oracle.** There is no generic cheap reset: application contexts clear per namespace (`DBMS_SESSION.CLEAR_ALL_CONTEXT` takes one) and a RAS detach takes the consumer's own session handle, neither of which Graphitron knows. Oracle stays on the consumer's unmount; R468 is its execution coverage.
- **The role.** See the table: resetting it reverts an init-SQL `SET ROLE`, a fail-open.
- **Advisory locks, temp tables, `LISTEN`.** Not identity. A request that leaks them is a different defect, and clearing them is what made `DISCARD ALL` too broad.
- **Connections Graphitron does not release.** An escape-hatch operation or `@service` code that borrows from the same `DataSource` itself returns its own connections. What this item gives those borrowers is that a connection Graphitron released reaches them clean.

## Other solutions we've considered

- **`DISCARD ALL`.** Clears everything `RESET ALL` does plus prepared statements, temp tables, advisory locks and listeners, and runs `SET SESSION AUTHORIZATION DEFAULT`, which reverts the role. The role revert is a fail-open under the init-SQL `SET ROLE` deployment. It also discards pgjdbc's server-prepared statements on every request.
- **`RESET ALL` alone.** One statement at release and no query at acquire, the cheapest reset there is. It loses because its cost lands on consumers who wrote no SQL: every setting pgjdbc applies with a post-connect `SET` reverts after a connection's first request, which turns a pool's configured `serializable` or read-only into `read committed` and read-write for Graphitron's own transactions, with no error, while the pool still reports the configured value. A documented remedy exists for each (`options=-c default_transaction_isolation=...`, `currentSchema`, `ALTER ROLE ... SET`), but the consumer it reaches is the one who reads the paragraph; a backstop against a bug most consumers do not have should not silently change isolation for the ones who configured it. One query per request is the price of not doing that.
- **Restore everything, custom variables included.** A full snapshot before the mount, restored after the unmount, would leave init-SQL custom variables alone too. Not possible: custom GUCs are invisible to `pg_settings`, so the snapshot cannot see them.
- **Capture at release instead of acquire.** Read the session-set built-ins after the unmount, then reset and restore them in one server-side block, saving the acquire round trip. It loses because whatever the mount changed in a built-in setting is captured and put back: a mount that carries tenancy in `search_path`, with an unmount that fails to clear it, would hand the next borrower the previous tenant's schema, which `RESET ALL` alone would not.
- **Capture once per physical connection.** Cache the capture keyed on the driver's connection, saving the acquire query on every reuse. It needs `unwrap` through the pool's proxy to reach the physical connection, which each pool implements its own way, and the cache goes stale whenever another borrower of the same `DataSource` changes a setting the pool then re-applies.
- **Reset named variables only.** The consumer lists the GUCs their mount sets, and Graphitron resets those. The backstop then depends on the consumer listing them correctly, the same class of mistake as the unmount it is backing up.
- **An opt-out flag.** A switch that disables a security backstop, for costs (custom variables set by init SQL, one query per request) the first of which has a fix keeping the reset on. Not offered; reconsider if a consumer shows a setting that can only be applied after connecting.

## Reviewer findings

### Round 1: Spec → Ready, request revisions (session_01FY5Uvv7pp1sZHXYZeTPkah, 2026-11-05)

Read against trunk `4f4c14f14`. Every symbol, test, and doc section the spec names exists as named, and the reset table reproduces on the local PostgreSQL 16. Question 2 holds for the mechanism: the reset sits inside the existing release `try` and its evict path, under the existing gate, with no parallel lifecycle. The failure is in question 1, and it reaches the plan's choice of design.

1. **The consumer cost is understated: pool settings applied through JDBC setters are reset too, silently, and the pool never puts them back (question 1).** The Goal and the manual draft name the cost as "init SQL" (`connectionInitSql`, `initialSql`, `new-connection-sql`) and tell consumers that connect-time settings are untouched. Two pool settings that consumers do not think of as SQL are applied as post-connect session `SET`s by pgjdbc: `PgConnection.setTransactionIsolation` issues `SET SESSION CHARACTERISTICS AS TRANSACTION ISOLATION LEVEL ...`, and `PgConnection.setSchema` issues `SET SESSION search_path TO ...` (pgjdbc 42.7.4 source). HikariCP's `transactionIsolation` and `schema` properties call those setters once in `PoolBase.setupConnection`, and `resetConnectionState` re-applies them only when the borrower changed them through the proxy (`DIRTY_BIT_ISOLATION`, `DIRTY_BIT_SCHEMA`), which Graphitron never does. Measured: after `SET SESSION CHARACTERISTICS AS TRANSACTION ISOLATION LEVEL SERIALIZABLE`, `RESET ALL` leaves `default_transaction_isolation` at `read committed`. A Quarkus or HikariCP consumer that configures the pool's isolation level therefore runs every request after a connection's first at read committed, with no error, and the manual draft as written gives that consumer no reason to think the paragraph concerns them. pgjdbc's `setReadOnly` under `readOnlyMode=always` is the same shape (`SET SESSION CHARACTERISTICS AS TRANSACTION READ ONLY`). "Holds for the first request only" is a silent semantic change for isolation, not only a configuration move, so it belongs in the goal's statement of what changes for a consumer.

   What would satisfy it: either the Goal and the manual draft name pool-configured isolation, schema, and read-only alongside init SQL, with the connect-time remedy for each (`options=-c default_transaction_isolation=...`, `currentSchema`, `ALTER ROLE ... SET`), and an execution case pins one of them, or the design changes so these settings survive (finding 2). Which one is the author's call.

   *Response:* The design changed so these settings survive (finding 2's arm). Confirmed against pgjdbc 42.7.13, the version the build pins: the setters issue the session `SET`s named here, and the driver's default `application_name` is a post-connect `SET` too, so `RESET ALL` alone would also blank it for every pgjdbc consumer. The Goal now lists what changes for a consumer (the acquire query, init-SQL custom variables, superuser-only settings) and what survives, naming HikariCP's `transactionIsolation`, `schema` and `readOnly` and Agroal/Quarkus `transaction-isolation-level`; "What the reset touches" records the pgjdbc and HikariCP facts; the manual draft says pool-configured settings survive and names the two that do not, with their connect-time remedy; the execution tier gains `poolConfiguredSettingsSurviveTheReset` (isolation, schema, init-SQL `statement_timeout`, `application_name`).

2. **The rejection of snapshot-and-restore covers custom GUCs only, and a narrower restore was not weighed (question 2, follows from 1).** "Other solutions we've considered" rejects snapshot-and-restore because custom GUCs are invisible to `pg_settings`. That holds for the identity variables, which the plan resets rather than restores anyway. Built-in settings changed in the session are visible: with `search_path` and the isolation level set by session `SET`, `select name, setting from pg_settings where source = 'session'` returns both rows. A design that keeps `RESET ALL` for the custom GUCs and restores the built-in session settings captured at acquire, before the mount, would remove both the init-SQL cost and finding 1's silent downgrade, at the price of one extra round trip at acquire and the question of what a mount that itself changes a built-in setting should get back. The plan may still be right to take the cost and document it, but the section should weigh this option on its own grounds, since it decides whether consumers pay the cost at all.

   *Response:* Adopted. The reset is now `RESET ALL` plus a restore of the built-in settings with `source = 'session'` and `context = 'user'`, captured at acquire after the autocommit assertion and before the mount (Implementation). Settled the open points: a mount's own change to a built-in is undone, to the captured value or the default, so `search_path`-carried tenancy is backstopped like a custom GUC; the capture costs one round trip and about 0.9 ms of server time per request (measured), while the restore rides in the same round trip as `RESET ALL`; a failed capture evicts through the acquire's existing outer catch and never reaches the rejection mapping, and a failed restore evicts through release's existing catch; superuser-context settings are not restored, since a restore under a non-superuser role fails on every release (measured). "Other solutions" now weighs `RESET ALL` alone, capture at release, and a per-connection cached capture on their own grounds, and keeps the full-snapshot rejection for custom GUCs only. Unit and execution tiers gained the capture cases.

Verdict: request revisions. Status stays Spec.

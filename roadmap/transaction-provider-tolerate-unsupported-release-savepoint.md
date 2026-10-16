---
id: R1054
title: "GraphitronTransactionProvider fails a nested commit on drivers without releaseSavepoint (Oracle)"
status: Backlog
bucket: bug
depends-on: []
created: 2026-10-16
last-updated: 2026-10-16
---

# GraphitronTransactionProvider fails a nested commit on drivers without releaseSavepoint (Oracle)

## Goal

A nested jOOQ transaction inside a `@service` commits on Oracle, as it does under jOOQ's own `DefaultTransactionProvider`. The generated `GraphitronTransactionProvider` treats a failed or unsupported `Connection.releaseSavepoint` as harmless, because the savepoint goes away with the enclosing commit or rollback anyway. Today Oracle's driver rejects the call, the provider turns that into a commit failure, and the whole operation rolls back.

## Context

Reported from the sis port (trunk `02b6a7f3d`). Shape: `registrerMeldingerOmDodsfall(input: [...]!): RegistrerMeldingerOmDodsfallPayload @service(...)`. The service runs `context.transactionResult(cfg -> ...)`, and inside it a CRUD service runs `context.transaction(trx -> ...)` again. Nesting like this is common in sis-service: 47 files use `transaction`/`transactionResult`.

The inner transaction is depth 1, so `begin` pushes `connection.setSavepoint()`, and its `commit` runs `connection.releaseSavepoint(savepoints.pop())`. Oracle's JDBC driver throws `SQLFeatureNotSupportedException` (ORA-17023, "Unsupported feature"). The provider's `catch (SQLException e)` rethrows it as `DataAccessException("Could not commit transaction")`, so the outer transaction rolls back and the client gets the redacted error. The data was fine. On sis this fails `registrerMeldingerOmDodsfallTest` and `registrerMeldingerOmFodselsnummerendring`.

Both release sites are in `GraphitronTransactionProviderGenerator`'s emitted `commit`: the nested-depth branch, and the deferred-rollback (`ROLLBACK_ONLY`) branch at depth 0, where a mutation field settles by releasing its savepoint. The second is untested on Oracle, but by the same mechanism the deferred-rollback dev mode should fail at every mutation field settle there.

v9 parity: jOOQ 3.20.11's `DefaultTransactionProvider.commit` releases the savepoint inside its own try and ignores the failure (`[#3537] Ignore those cases where the JDBC driver incompletely implements the API`). The rewritten provider dropped that tolerance. `rollback(savepoint)` is unaffected; Oracle supports it.

Expected: a failed or unsupported release is swallowed at both sites, matching jOOQ, so the commit or settle carries on. Skipping the call per dialect is the alternative the reporter offered, but it is narrower: it needs a dialect list and misses drivers nobody has listed. A test should drive the nested commit and the deferred-rollback settle against a connection whose `releaseSavepoint` throws.

---
id: R1060
title: "A DATABASE @error handler's matches never sees the SQL text jOOQ wraps around the SQLException"
status: Backlog
bucket: bug
depends-on: []
created: 2026-10-19
last-updated: 2026-10-19
---

# A DATABASE @error handler's matches never sees the SQL text jOOQ wraps around the SQLException

## Goal

A `DATABASE` `@error` handler with `code` or `sqlState` plus `matches` fires when the `matches` text appears in the message of any exception from the outermost throw down to the `SQLException` that carries the code. That includes jOOQ's `DataAccessException`, whose message starts with the SQL statement. The code or state is still read off the `SQLException`. Handlers that key on a column name, which only the SQL text carries, then map to their typed error as they did in v9, instead of falling through to redaction. Handlers whose `matches` targets the driver's own message are unaffected.

## Context

Reported from the sis port (trunk `f611072f5`). Two sis handlers depend on SQL text, out of 179 audited:

- `UgyldigBankkontonummer`: `{handler: DATABASE, code: "1438", matches: "BANKKONTONR", description: "Bankkontonummer kan ha høyst 11 sifre"}`
- stable `UgyldigInput`: `{handler: DATABASE, code: "1438", matches: "PROSENTTALL", description: "Prosenttall kan ikke overstige 100"}`

ORA-01438 never names the column. On Oracle 26ai the message reads `ORA-01438: value 1111111111111111 greater than specified precision (11, 0) for column`, and in a Norwegian session `ORA-01438: verdien er større enn presisjonsangivelsen som er tillatt for denne kolonnen`. The column name appears only in jOOQ's wrapper. The generated DML runs `update ... returning ... fetchOne()`, and jOOQ's `Tools.translate` throws `DataException("SQL [update \"KJERNEAPI\".\"PERSON\" set ... \"BANKKONTONR\" = ? ...]; ORA-01438: ...", sqlException)`.

Cause: the generated `ErrorRouter.VendorCodeMapping.match(Throwable)` and `SqlStateMapping.match(Throwable)` (`ErrorRouterClassGenerator.buildVendorCodeMapping` / `buildSqlStateMapping`) accept only `throwable instanceof SQLException` and test `matches` against that same throwable's `getMessage()`. The dispatch loop walks the cause chain outermost-first and offers one link at a time, so no single `match` call ever sees the code and the SQL text together. The throw goes unmatched and is redacted.

v9 (graphitron-common 9): `DataAccessExceptionContentToErrorMapping.matches` took the code from the `SQLException` and checked `matches` against the `DataAccessException`'s message. sis relies on this on purpose; main's `BankkontonummerErrorMappingTest` documents it, and it is also the language-independent choice.

Expected, per the reporter:
- For `VendorCodeMapping` and `SqlStateMapping` only, `matches` succeeds against the message of any exception from the outermost throw down to the code-bearing `SQLException`. The GENERIC `ExceptionMapping` already sees the outer message.
- The `<Type>Fetchers.message` and union `TypeResolver` paths re-check the matched source, so they need the same rule.
- Whether the source placed in the errors list stays the `SQLException` is for Spec to decide.

The mapping interface takes one `Throwable`, so Spec has to choose between handing the mapping the whole chain (or the outer throw plus the candidate) and letting it walk the chain itself from the outermost throw. Either way the "first (mapping, cause) pair wins" ordering in `dispatch` must stay well defined.

Acceptance on sis: three tests ported from main pass unchanged. They are `BankkontonummerErrorMappingTest.forMangeSifreMappesTilUgyldigBankkontonummer`, `...forMangeSifreMappesUavhengigAvDatabasensSprak` and `...forStortProsenttallMappesFortsattTilProsentmelding`, each building `new DataException("SQL [<update ... BANKKONTONR/PROSENTTALL ...>]; ORA-01438: ...", new SQLException(oraText, "22003", 1438))` and running it through the generated `ErrorMappings` the way `ErrorRouter.dispatch` does. graphitron's own coverage should be the same shape: a wrapper whose message carries the `matches` text over an `SQLException` that carries the code, plus a negative case where neither message carries it.

---
id: R1048
title: "Consumers choose how an unmatched fetcher exception is logged, and the zero-row guard keeps input values out of its message"
status: Backlog
bucket: feature
priority: 3
theme: error-channel
depends-on: []
created: 2026-10-15
last-updated: 2026-10-15
---

# Consumers choose how an unmatched fetcher exception is logged, and the zero-row guard keeps input values out of its message

## Goal

A consumer can decide how a fetcher exception that no `@error` mapping claims is logged, so personal data in an exception message never has to reach its logs, and Graphitron's own generated exceptions stop putting client input into their messages. Today the generated `ErrorRouter.redact` (emitted by `ErrorRouterClassGenerator.redactBody`) writes `LOGGER.error("Unmatched exception in fetcher; correlation id = {}", correlationId, thrown)` through a fixed SLF4J logger: the full exception, message and cause chain, always at ERROR. When this lands, `GraphitronRuntime` accepts a hook for that disposition; the default keeps today's line, and what reaches the client (the redacted message plus the correlation id) stays Graphitron's. Separately, the bulk UPDATE and DELETE zero-row guards in `TypeFetcherGenerator` name the field and say the key matched nothing, without appending the input row.

## Background

Reported by the sis port (v9 parity, privacy). Two leaks in sis today:

1. **Database message text.** On a unique-constraint violation Oracle adds an ORA-03301 line spelling out the conflicting column values ("row with column values (...) already exists"), so the message can carry a national identity number, an e-mail address or a name. It travels in the `SQLException` and in jOOQ's `DataAccessException` message. jOOQ's own `SQL [...]` part is safe: it renders `?` placeholders.
2. **Graphitron's own message.** The zero-row guard throws `IllegalStateException("@mutation(typeName: UPDATE) bulk row matched zero rows; key filter found no target for input row: " + row)` (and the DELETE twin), putting the whole client input map into the message. Unmapped, it is logged; mapped without a description, it reaches the client.

v9 sis carried `CustomTopLevelErrorHandler` and `ExceptionLoggSanering`, which the port removed because there is nowhere to wire them (sis carries a TEMP commit dropping them; this blocks a v9-parity release on the logging side). What they did, as the bar the hook must make buildable:

- **Allowlist sanitising:** the log line holds only the exception class chain, ORA codes, constraint and index names, FS PL/SQL rule keys, and the innermost frames from the consumer's own packages; never the message. A denylist of patterns is always one column behind.
- **Level by kind:** rejected client data (integrity violations, `IllegalArgumentException`, ORA-20000..20999 application errors) at WARN without a stack trace; real faults at ERROR.
- **Damping:** during a database outage every request fails the same way; connection errors log a stack trace at most once a minute and authentication errors once every ten minutes, INFO in between with a count of the damped ones.

## Shape, as requested

A runtime-level hook on `GraphitronRuntime` (constructor or builder) that receives the throwable, the correlation id and the field coordinate/path, and owns the logging. Candidate shapes, to be settled at Spec:

- a functional interface, roughly `(Throwable, UUID correlationId, DataFetchingEnvironment) -> void`, on which a consumer builds sanitising, levels and damping;
- at minimum, a way to swap the logger and a message formatter.

Open questions for Spec: how the generated `ErrorRouter` (static today) reaches a runtime-scoped hook; whether the hook also sees mapped errors (dispatch hits), which would let sis log rejected client data at WARN as v9 did (requested as nice-to-have, not required); and whether the zero-row fix belongs in this item or a sibling.

## Scope

R1046 moves an unmatched `GraphitronClientException` on a channel fetcher to `surfaceClientErrorOrRedact`, so client errors stop being logged at ERROR. This item covers what remains: true unmatched faults, plus the zero-row message.

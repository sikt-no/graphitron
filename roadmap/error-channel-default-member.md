---
id: R1171
title: "A default @error member receives the throws Graphitron 9 handed to consumer code"
status: Backlog
bucket: dx
priority: 3
theme: error-channel
depends-on: [error-dispatch-matches-graphitron-9]
created: 2026-11-11
last-updated: 2026-11-11
---

# A default @error member receives the throws Graphitron 9 handed to consumer code

## Goal

A schema moving from Graphitron 9 names one `@error` member as its default, and that member receives the throws Graphitron 9 handed to consumer code: a `DataAccessException` that no `DATABASE` handler claims, and every `IllegalArgumentException`. With it declared, each of those lands in the same member it landed in on Graphitron 9, and `FetcherErrorResponse.matchedMessage` computes its `message:` the way the consumer's `createDefaultDataAccessError` did. Today Graphitron 10 has nothing in that place: an unclaimed database error is redacted at the top level, and an `IllegalArgumentException` goes to whatever `GENERIC` handler matches it, which on Graphitron 9 never happened.

On Graphitron 9 the place was code, not schema. `SchemaBasedErrorStrategy` is abstract, so every Graphitron 9 application implemented `createDefaultDataAccessError` and `handleIllegalArgumentException`; `recordValidation.schemaErrorType` named the type validation and `IllegalArgumentException` errors went into. Graphitron 9 therefore put every `DataAccessException` of an operation with a `DATABASE` handler into the payload, through a handler or through the default, and never at the top level.

The first consumer studied (sis, on Graphitron 9.4.4) makes all three a `UgyldigInput`, the first member of 159 of its 161 error unions; 61 of its services throw `IllegalArgumentException` to reject input, and all ten of its domain exceptions extend `DataAccessException` without carrying an `SQLException`, so on Graphitron 9 each reached the default.

## Where the default sits in dispatch

R1167 (`error-dispatch-matches-graphitron-9`) fixes dispatch as one order: `DATABASE` handlers, only when the outer exception is a `DataAccessException`, then `GENERIC` handlers. The default slots in as a conditional middle step, which reproduces Graphitron 9 for every schema that declares one and leaves a schema without one as R1167 has it:

- outer `DataAccessException`: `DATABASE` handlers, then the default, then `GENERIC` handlers;
- outer `IllegalArgumentException`: the default, then `GENERIC` handlers;
- anything else: `GENERIC` handlers.

A `GENERIC` handler on a `DataAccessException` class (sis's `UgyldigId`) or on `IllegalArgumentException` becomes unreachable behind a declared default, as it was on Graphitron 9; R1168 (`shadowed-error-handler-is-reported`) or this item reports it, and for sis that is migration friction to name in the migration guide.

## Open at pickup

- Grain: Graphitron 9's default was application-wide. Candidates are per schema (plugin configuration, like `recordValidation`), per channel, or per `@error` type (a marker on the type, used wherever it is a member). A channel without the default member among its members has no default.
- Graphitron 9 registered `IllegalArgumentException` for an operation only when `schemaErrorType` was among its errors, and a `DataAccessException` only when the operation had a `DATABASE` handler or a `GENERIC` handler whose class covers it; whether the default inherits those conditions.
- `path:` of a default error: Graphitron 9 answered `[fieldName]` for the database default and the response path for `IllegalArgumentException`. Alf's ruling of 2026-11-11 on R1167's divergence (d) makes it the response path for both.
- The `migrating-from-legacy.adoc` advice "declare a catch-all `@error` member last" is replaced by this, and `error.adoc` and `error-channel.adoc` describe the step.

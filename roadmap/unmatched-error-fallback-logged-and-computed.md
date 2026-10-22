---
id: R1087
title: "Graphitron 9 error fallback cannot be reproduced: a catch-all handler is never logged and its message cannot be computed"
status: Backlog
bucket: enhancement
priority: 3
theme: error-channel
depends-on: []
created: 2026-10-22
last-updated: 2026-10-22
---

# Graphitron 9 error fallback cannot be reproduced: a catch-all handler is never logged and its message cannot be computed

## Goal

A consumer moving from Graphitron 9 should be able to keep the error responses its clients already parse when no specific `@error` handler matches. Today it cannot, for three reasons. A catch-all handler (a `GENERIC` handler on a broad class, last in the union) is never logged, so it would hide outages. Its `message:` is either a fixed `description:` or the exception's own text, so it cannot be computed from the exception. And the client's reference is always a random UUID. After this item a consumer can declare a catch-all member whose matches still reach `FetcherErrorLog`. It can compute the message of a matched or redacted error from the exception and the reference. It can also supply the reference itself, for example the OpenTelemetry trace id. A consumer that configures nothing sees today's behaviour.

## What Graphitron 9 consumers depend on

Reported by sis, whose consumer-facing teams require the Graphitron 9 shapes to survive the upgrade because client integrations parse them. On Graphitron 9 sis ran a `SchemaBasedErrorStrategy` subclass and a `TopLevelErrorHandler` subclass:

- **In a payload.** On a mutation whose errors union has `UgyldigInput`, an exception no handler matched came back as an `UgyldigInput` entry when it was an `IllegalArgumentException`, or any jOOQ `DataAccessException` on a mutation. The message was computed: a text chosen by SQL-state class ("Du har ikke tilgang til å utføre denne operasjonen" for ORA-28115, "Fikk ikke kontakt med databasen." for a connection failure), the FS rule text for ORA-20xxx with the national id number masked, then `" Referanse: " + <OTel trace id>`. Every such error was logged, at WARN for rejected client data and ERROR otherwise, with the trace id.
- **At the top level.** Everything else answered "An exception occurred. The error has been logged with id <uuid>: <hint>", where the hint was the same SQL-state text for a `DataAccessException`.

On Graphitron 10 the payload case becomes a `null` field plus "An error occurred. Reference: <uuid>.", and the hint is gone from the top-level case. sis tried a `GENERIC` handler on `DataAccessException` last in each union and dropped it: a matched error is never logged, so a database outage on a mutation would vanish from the log.

## Sketch (fill in at Spec)

Three capabilities, each opt-in:

1. **A logged match.** A handler entry can ask for its matches to be reported to `FetcherErrorLog` as well as delivered in the payload, with the same reference the client sees. One shape is a handler flag; another is a default `FetcherErrorLog.matched(thrown, reference, env)` called for every match, which the consumer filters. The second also answers sis's separate wish to log every payload error as Graphitron 9 did.
2. **A computed message.** A runtime hook, set on `GraphitronRuntime` beside `withFetcherErrorLog`, that returns the client message from `(thrown, reference, env)` for a matched entry that opts in, and for the redacted top-level error. `description:` stays the static form. Overriding `getMessage()` in the exception class, which the manual suggests today, is not open to a consumer whose exception is jOOQ's `DataAccessException`.
3. **A supplied reference.** The same hook, or a sibling, returns the reference. R423 proposes reading `trace_id` from the SLF4J MDC. That does not reach sis: fsapi-rest runs on WildFly and does not put the trace id in the MDC, and Graphitron 9's strategy read `Span.current()` directly. A consumer-supplied function covers both; R423's MDC lookup could be its default.

The redaction path in `GraphqlHttpHandler` must agree with the generated `ErrorRouter.redactBody`, as R423 already notes.

## Consumer workaround meanwhile

sis can rewrite the top-level text in a graphql-java `instrumentExecutionResult` by stashing `(reference, thrown)` from its `FetcherErrorLog`. It cannot restore the payload entry: by then the field is already `null`.

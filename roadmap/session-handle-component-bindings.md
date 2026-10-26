---
id: R1102
title: "A value the session mount derives binds where a contextArgument binds today"
status: Backlog
bucket: feature
priority: 4
theme: runtime-connection
depends-on: []
created: 2026-10-26
last-updated: 2026-10-26
---

# A value the session mount derives binds where a contextArgument binds today

## Goal

A value the `<sessionState>` mount derives (an identity the database looked up or provisioned while mounting, such as a user id) can be bound wherever a `contextArgument` binds today, in `@service` parameters and `@condition` methods alike, without the consumer resolving it before execution. Today the mount's handle reaches generated code only as a whole, through `argMapping: "x: $session"` on a service, and nothing documents `$session` in a `@condition`. Meanwhile `newOwnedExecutionInput` needs every `contextArgument` before execution starts, and the mount cannot write to the request's `GraphQLContext`. A consumer whose identity is an output of authentication therefore has to choose between taking two connections per request (resolve, then mount) and rewriting every binding and signature to take the whole handle.

## Motivating consumer

fs-plattform's opptak subgraph, moving from the escape hatch to the owned-connection engine on Postgres with RLS and a single tenant. Its mount looks up the caller (`auth.finn_identitet`), may provision a `SOKER` row, and yields `brukerId` and `sokerId`. Its schema reads those two values through `contextArguments` in 26 places, across services and conditions. Reading such a value would pin a connection first; that is acceptable for opptak, since every use is a database field or condition.

## Shapes raised so far

* A component binding against a mount that returns a record, for example `argMapping: "brukerId: $session.brukerId"`, accepted in `@condition` as well as `@service`.
* A `contextArgument` declared as derived from the handle, so the existing 26 bindings stay as they are and only the source of the value changes.

Related: a rejected mount already fails the whole request as `SessionMountException` (R1097, in review), which is what makes a mount that does real identity work viable.

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

## Direction

The mount's return type is already introspected at build time: it is the handle type `unmount` receives and `argMapping: "x: $session"` binds today. Make that introspected value the `$session` variable, addressable by path the way other `argMapping` dot-paths are: `argMapping: "brukerId: $session.brukerId"` binds one member of the handle, resolved and type-checked against the mount's declared return type at build time, so a path that names no member fails the build rather than a request. The sigil works in every directive that takes an `argMapping`, `@condition` as well as `@service`, which also gives `$session` in a condition a documented meaning.

Considered and set aside: a `contextArgument` declared as derived from the handle. It would leave existing bindings untouched, but it adds a second declaration form for a value the handle already carries, where the path form reuses the handle the build already knows.

Related: a rejected mount already fails the whole request as `SessionMountException` (R1097, in review), which is what makes a mount that does real identity work viable.

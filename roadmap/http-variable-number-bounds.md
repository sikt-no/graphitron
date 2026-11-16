---
id: R1190
title: "Bound JSON numbers in GraphQL-over-HTTP variables and extensions before the engine coerces them"
status: Backlog
bucket: bug
priority: 1
theme: runtime-connection
depends-on: []
created: 2026-11-16
last-updated: 2026-11-16
---

# Bound JSON numbers in GraphQL-over-HTTP variables and extensions before the engine coerces them

## Goal

`GraphqlHttpHandler` (in `graphitron-jakarta-rest`) validates the size of every number in a request's `variables` and `extensions` before building the `ExecutionInput`, at any nesting depth and on both POST and GET. A number outside a small fixed bound on digit count and exponent is answered as a `4xx` request error; ordinary integer, decimal and exponent values coerce exactly as before.

## Notes

Details are held outside the public tree; ask the maintainers. Starting points: `doPost`, `doGet` / `parseMapParam` and `execute` in `GraphqlHttpHandler`.

* Validation walks the decoded maps rather than configuring the JSON-B binder, since the provider is whatever the consumer ships (Yasson, `quarkus-jsonb`).
* The module compiles at `<release>17</release>`.
* Tests cover `Int`, `Float`, `ID` and a custom scalar over POST and GET for out-of-bound values, and pin the ordinary cases unchanged.
* `R1188` is the sibling for generated `ColumnValues`; neither depends on the other.

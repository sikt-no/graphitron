---
id: R251
title: AppliedDirectiveEmitter trips assert on NOT_SET argument values
status: In Review
bucket: bug
depends-on: []
created: 2026-05-27
last-updated: 2026-05-27
---

# AppliedDirectiveEmitter trips assert on NOT_SET argument values

`AppliedDirectiveEmitter.emitAstLiteralValue` (AppliedDirectiveEmitter.java:114) calls `ValuesResolver.valueToLiteral(arg.getArgumentValue(), …)` for every argument returned by `applied.getArguments()`. graphql-java's `InputValueWithState` has four states (`LITERAL`, `EXTERNAL_VALUE`, `INTERNAL_VALUE`, `NOT_SET`); `valueToLiteral` only handles the first three and asserts `should never happen` on `NOT_SET`. `getArguments()` returns one slot per *declared* argument on the directive, including arguments the SDL application did not supply. When a survivor directive (e.g. federation `@key` without an explicit `resolvable`) is applied without all arguments, the emitter trips this assertion and `graphitron:dev` fails with `Internal error: should never happen: unexpected value state InputValueWithState{state=NOT_SET, value=null}`. R248 fixed adjacent issues on the directive-*definition* side (default values, federation scalar typing) but did not add `NOT_SET` handling on the *application* side.

Fix: in `buildApplication`, skip arguments whose `getArgumentValue().isNotSet()` is true rather than emitting a `.argument(...)` block for them. The reconstructed `GraphQLAppliedDirective` then carries only the explicitly-supplied arguments, and graphql-java resolves the rest from the directive definition's defaults at consumer-side schema-build time. This matches how graphql-java round-trips applied directives internally. Add a unit-tier regression that builds an application of a directive with an omitted nullable argument and asserts the emitter produces valid output without hitting the assert.

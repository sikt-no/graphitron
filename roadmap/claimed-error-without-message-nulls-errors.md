---
id: R1147
title: "A claimed exception with no message nulls the whole errors list"
status: Backlog
bucket: bug
priority: 4
theme: error-channel
depends-on: []
created: 2026-11-03
last-updated: 2026-11-03
---

# A claimed exception with no message nulls the whole errors list

## Goal

When a handler claims an exception whose `getMessage()` is `null` and the `@error` type has no
`description:`, the error still renders: graphitron gives the `message: String!` slot it fills a
fallback, or refuses the shape at build time, rather than returning `null` into it and nulling the
whole `errors` list.

## The defect

In kreg, with `annullerVgdok`'s payload switched to a union carrier so the channel forms, a thrown
`MeldingerException` is claimed, but its `getMessage()` is `null` (its texts are held in a list).
The generated `KregUgyldigInputFetchers.message` returns `null` into `message: String!`, graphql-java
reports "The field at path '/annullerVgdok/errors[0]/message' was declared as a non null type", and
the whole `errors` list nulls. The consumer can fix its exception, but graphitron synthesises the
slot, so the slot's non-null contract is graphitron's to keep.

## Direction

The Spec chooses between a fallback (the exception's class name, or the handler's class) and a
build-time requirement that a handler over an exception that may carry no message names a
`description:`; a fallback is the only option that needs no consumer change. An execution-tier
case: a claimed exception with a null message renders one error with a non-null message.

Related: R1133's open question on one exception rendering several errors (Graphitron 9's fan-out
with a per-message path) is a different change and stays there.

Provenance: found by the kreg Graphitron 10 port session (kreg branch `worktree-kreg-g10` at `0d9997fd39`), replaying recorded Graphitron 9 requests against 10.0.0-RC46, RC47 and a trunk snapshot (`claude/graphitron-rewrite` at `06d71b62b`), 2026-11-03; identical on all three.

---
id: R1146
title: "An invalid id to a Node-returning lookup returns null with no error"
status: Backlog
bucket: bug
priority: 2
theme: nodeid
depends-on: []
created: 2026-11-03
last-updated: 2026-11-03
---

# An invalid id to a Node-returning lookup returns null with no error

## Goal

A Node-returning lookup given an id that does not decode, the generated `Query.node(id:)` and a
consumer's own `kregNode(id: ID!): Node`, fails with the same client error a bad node id gets at
every `@nodeId` site, instead of returning `null` with no error. R1036 promised that error "at every
`@nodeId` site", and the node dispatch is the one place it does not reach.

## The defect

In kreg, `kregNode(id: "ikke-en-id") { __typename id }` returns `{"data": {"kregNode": null}}` and no
error, on RC46, RC47 and trunk alike. Graphitron 9 answered with a `DataFetchingException` saying the
id is not valid. `kregNode` is kreg's `Query` field over the generated node dispatch; it carries no
`@nodeId` directive of its own, which is likely why R1036's decode-failure path does not cover it.
A client cannot tell a malformed id from a well-formed id that names nothing.

## Direction

Route the node dispatch's decode failure through the same client error R1036 gives `@nodeId`
sites, keeping `null` for an id that decodes but names no row. An execution-tier case: a malformed
id and a well-formed missing id to the node lookup, asserting the error on the first and a clean
`null` on the second.

A wrong answer on a valid schema, so a regression ahead of planned work.

Provenance: found by the kreg Graphitron 10 port session (kreg branch `worktree-kreg-g10` at `0d9997fd39`), replaying recorded Graphitron 9 requests against 10.0.0-RC46, RC47 and a trunk snapshot (`claude/graphitron-rewrite` at `06d71b62b`), 2026-11-03; identical on all three.

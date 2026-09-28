---
id: R980
title: "An authored redeclaration of a built-in directive fails the whole capture instead of drawing a diagnostic"
status: Backlog
bucket: bug
priority: 2
theme: diagnostics
depends-on: []
created: 2026-09-28
last-updated: 2026-09-28
---

# An authored redeclaration of a built-in directive fails the whole capture instead of drawing a diagnostic

## Goal

A schema that declares a directive graphql-java already ships, such as `directive @oneOf on
INPUT_OBJECT`, either captures normally or gets a diagnostic pinned to the line that declares it.
Today it does neither. `GraphQLAstCapture.elements` fills `graphql_element` with one statement
that unions the authored declarations with the specified directives, so an authored `@oneOf`
arrives twice in one upsert. On a cold store both rows insert and the primary key refuses the
second, and the whole capture fails as an infrastructure error. `graphitron:dev` then has no store
at all, so the author's editor loses every diagnostic instead of getting one about the line they
wrote. On a warm store that already holds the row, both update it and nothing fails, so the fault
shows on a first capture and hides on every later one.

The specified-directive union arrived with `1644b314b`. Its neighbouring comment notes that a
document redeclaring a specified scalar is refused before this statement runs; nothing does the
same for a specified directive.

## Seen on

A consumer schema's `schema/features/stable/common/directives.graphqls`, line 6, on 2026-09-28,
reproduced on a cold store with and without the chain-resolution change. The stack is
`GraphQLAstCapture.elements`, from `GraphQLAstCapture.anchor`, from `ModelCapture.capture`. The
violated key is `graphql_element (graph_name, coordinate)`.

## Decision owed at Spec

Tolerate or refuse. Tolerating means one coordinate however many populations name it, which is
what the rank in that statement already does for authored sites. Refusing is defensible too, but
then the refusal must be a positioned diagnostic naming the file and line, never a failed capture.
Whether graphql-java accepts the redeclaration when it builds the executable schema is unchecked
and bears on which is right.

---
id: R1065
title: "An interface's own implements clause is dropped from the runtime schema"
status: Backlog
bucket: bug
priority: 5
theme: codegen-correctness
depends-on: []
created: 2026-10-19
last-updated: 2026-10-19
---

# An interface's own implements clause is dropped from the runtime schema

## Goal

An interface that implements another interface in the source SDL keeps that `implements`
clause in the runtime schema, so introspection and `_service { sdl }` show
`interface Personrolle implements Node` as authored.

## Evidence

Surfaced by the sis v9 against v10 differential test (2026-10-19): the runtime SDL and introspection
lose `implements Node` on the interfaces `Personrolle` and `Studentvurdering`. `node(id:)` still
resolves their implementations, so this is schema fidelity, not dispatch.

## Cause

`ObjectTypeGenerator.buildInterfaceTypeSpec` emits name, description, fields and applied directives
for an interface, but has no counterpart to the `b.withInterface(GraphQLTypeReference.typeRef(...))`
loop the object-type builder emits, so `GraphQLInterfaceType.getInterfaces()` is never carried over.
The fix is that loop on the interface builder, with a test that pins an interface-implements-interface
round-trip through the emitted schema.

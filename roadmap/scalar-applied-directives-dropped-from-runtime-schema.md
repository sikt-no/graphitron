---
id: R1066
title: "Directives applied to a custom scalar are dropped from the runtime schema"
status: Backlog
bucket: bug
priority: 5
theme: codegen-correctness
depends-on: []
created: 2026-10-19
last-updated: 2026-10-19
---

# Directives applied to a custom scalar are dropped from the runtime schema

## Goal

A directive applied to a custom scalar in the source SDL, `scalar Date @tag(name: "stable")`
for instance, survives into the runtime schema and `_service { sdl }`, so Apollo contract filtering
sees it.

## Evidence

Surfaced by the sis v9 against v10 differential test (2026-10-19): the scalars `BigDecimal`,
`Date`, `DateTime`, `Time` and `Duration` lose `@tag(name: "stable")` in `_service { sdl }`. The
source declares it.

## Cause

`GraphitronSchemaClassGenerator` registers a resolved scalar by handing the library's
`GraphQLScalarType` constant straight to `schemaBuilder.additionalType(...)` (the
`ScalarResolution.Resolved` arm), and a synthesised one through a `HelperMethodSink` factory that
sets only name and coercing. Neither path passes through `AppliedDirectiveEmitter`, which is how
objects, interfaces and unions carry their applications. For the resolved arm there is no builder
to emit into; the fix needs a `transform` of the constant (or an equivalent builder copy) that adds
the SDL's applications, and the synthesised arm needs the same emission.

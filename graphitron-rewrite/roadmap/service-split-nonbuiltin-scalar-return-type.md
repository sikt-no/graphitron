---
id: R364
title: "Fix @service split-query rows-method return type for enum and non-built-in scalar fields"
status: In Progress
bucket: bug
priority: 4
theme: service
depends-on: []
created: 2026-06-24
last-updated: 2026-06-24
---

# Fix @service split-query rows-method return type for enum and non-built-in scalar fields

## Problem

A non-root `@service @splitQuery` child field whose GraphQL type is an enum generates a rows-method
with a doubly-nested return type, `Map<KeyRecord, Map<KeyRecord, String>>` instead of the flat
`Map<KeyRecord, String>`, so the generated code does not compile (`incompatible types: Map<...,String>
cannot be converted to Map<...,Map<...,String>>`). Scalar `Int` and `Boolean` fields on the same
parent generate the correct flat shape. New on 10.x. The defect is not enum-specific at root: the
trigger is any `ScalarReturnType` whose name is not one of the five GraphQL spec built-ins
(`String`, `Boolean`, `Int`, `Float`, `ID`), which in practice means enums and unregistered custom
scalars.

## Mechanism (confirmed by source trace)

- The field classifies as `ChildField.ServiceRecordField` (built at `FieldBuilder.java:5472-5478` from
  a `ServiceDirectiveResolver.Resolved.Scalar`; enums route through `Resolved.Scalar`, see
  `ServiceDirectiveResolver.java:219`).
- The rows method is emitted by `TypeFetcherGenerator.buildServiceRowsMethod`
  (`TypeFetcherGenerator.java:5711-5748`); its return type is
  `RowsMethodShape.outerRowsReturnType(perKeyType, ...)` (`RowsMethodShape.java:92-105`), which for a
  Set-keyed container is `Map<keyElement, perKey>`.
- `perKey` comes from `ServiceRecordField.elementType()` (`ChildField.java:797-801`). It calls
  `RowsMethodShape.strictPerKeyType(returnType())`, which for a `ScalarReturnType` calls
  `ScalarTypeResolver.builtInJavaType(name)` (`ScalarTypeResolver.java:546-549`); that returns null for
  any name outside the five built-ins. An enum's type name is not a built-in, so it returns null, and
  `elementType()` falls back to `method().returnType()`, the service method's whole
  `Map<KeyRecord, String>`. `outerRowsReturnType` then wraps that once more, producing
  `Map<KeyRecord, Map<KeyRecord, String>>`. The `String` leaf is correct (enum to DB text); only the
  per-parent Map wrapper is duplicated.
- No defense in depth: `ServiceDirectiveResolver.validateChildServiceReturnType` (lines 349-368) uses
  the same `strictPerKeyType` and bails out (skips) when it is null, explicitly listing "custom
  scalars / enums until the typed-context-value-registry lands" as skipped.

Correction to the original report: the broken method is the rows method, not the async `DataFetcher`.

## Plan

The defect is the `elementType()` fallback (`ChildField.java:797-801`): when
`RowsMethodShape.strictPerKeyType` returns null for a non-built-in scalar (`RowsMethodShape.java:57-66`
via `ScalarTypeResolver.builtInJavaType`, `ScalarTypeResolver.java:546-549`), it returns the service
method's whole `Map<KeyRecord, V>` instead of the per-key `V`; `outerRowsReturnType`
(`RowsMethodShape.java:92-105`) then wraps it again.

1. **Resolve the leaf type for non-built-in scalars instead of falling back to the whole map.** For a
   `ScalarReturnType` whose name is not a spec built-in (enum, or an `@scalarType` / extended custom
   scalar), the per-key `V` is the leaf the service map yields. Two sub-options to pin at Spec:
   - (a) Peel `V` off the reflected `method().returnType()` (it is a `Map<K, V>`; take the second type
     argument) so `elementType()` returns `V`, not the `Map`. Smallest change; works for any leaf the
     service already produces (`String` for an enum-as-text mapping).
   - (b) Resolve the enum's generated Java type from the schema, coupling with
     `emit-text-mapped-enum-fields-as-enum-type`. Cleaner typing, larger blast radius.
   Recommend (a) for this bug-fix slice (makes the generated code compile and match the service
   contract), with (b) tracked as the typing-fidelity follow-up.
2. **Close the validator gap.** `ServiceDirectiveResolver.validateChildServiceReturnType`
   (`ServiceDirectiveResolver.java:349-368`) skips when `strictPerKeyType` is null ("custom scalars /
   enums until the typed-context-value-registry lands"). Extend it to validate the non-built-in case
   against the same leaf resolution as step 1, so a mismatched service return is rejected at build
   rather than miscompiled.

## Tests

- Pipeline / compilation tier: an enum-typed `@service @splitQuery` child field generates a flat
  `Map<KeyRecord, <leaf>>` rows method and compiles; a scalar `Int` sibling stays unchanged.
- `TestServiceStub.getFilmActorsCompositeKey` already provides the `Map<Record, String>` service
  shape to extend with an enum field fixture.

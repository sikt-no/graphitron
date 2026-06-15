---
id: R313
title: "Scalar alias name mismatch: @scalarType registers under constant intrinsic name not the SDL name"
status: Backlog
bucket: bug
priority: 1
depends-on: []
created: 2026-06-15
last-updated: 2026-06-15
---

# Scalar alias name mismatch: @scalarType registers under constant intrinsic name not the SDL name

When a scalar is declared with an SDL name that differs from the intrinsic name of
the `GraphQLScalarType` constant it resolves to, the generated `GraphitronSchema.build()`
registers the scalar under the *constant's* name, not the SDL name. The canonical repro is
the extended-scalars `Date` constant aliased to a different SDL name:

----
scalar LocalDate @scalarType(scalar: "graphql.scalars.ExtendedScalars.Date")
----

`ExtendedScalars.Date.getName()` is `"Date"`, so `GraphitronSchemaClassGenerator` emits
`schemaBuilder.additionalType(ExtendedScalars.Date)`, which graphql-java registers under the
name `Date`. Every field referencing the scalar emits `GraphQLTypeReference.typeRef("LocalDate")`
(the SDL name), so `schemaBuilder.build()` fails at runtime with
`AssertException: type LocalDate not found in schema`. Downstream consumers see this as
`buildSchema` failures (e.g. `SakMerknaderShapeTest`, `SakSaksdokumenterShapeTest`).

`ScalarResolution.Resolved` carries only the `(owner, fieldName)` of the constant, not its
intrinsic GraphQL name, so the emitter cannot detect the mismatch. The fix is to carry the
constant's intrinsic name (the resolver already holds the `GraphQLScalarType` during reflection)
and, when it differs from the SDL name, register a renamed alias:
`schemaBuilder.additionalType(GraphQLScalarType.newScalar(ExtendedScalars.Date).name("LocalDate").build())`.
The existing happy-path test never caught this because `ScalarConstants.MONEY.getName()` happens
to equal its SDL name `Money`; the bug only surfaces on a genuine alias.

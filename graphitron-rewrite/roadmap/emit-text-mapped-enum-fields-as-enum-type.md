---
id: R230
title: "Emit text-mapped-enum fields as the GraphQL enum type, not String"
status: Backlog
bucket: architecture
priority: 5
theme: structural-refactor
depends-on: []
created: 2026-05-22
last-updated: 2026-05-22
---

# Emit text-mapped-enum fields as the GraphQL enum type, not String

When an SDL field is declared with an enum return type whose values use
`@field(name:)` to bind to a varchar column (e.g. `textRating: TextRating`
with `enum TextRating { PG_13 @field(name: "PG-13") ... }`), graphitron's
field-emit lowers the GraphQL field type to `String` in the generated
`FieldDefinition` (see `FilmType.java:36` for `textRating`). The fetcher
returns the raw column string, and graphql-java's Coercing layer is never
engaged for that field — clients see the runtime form (`"PG-13"`) instead
of the SDL identifier (`PG_13`).

R229 lifted `@field(name:)` into the `GraphQLEnumValueDefinition.value(...)`
slot at schema-emit time so graphql-java's wire ↔ runtime translation
works correctly when the field IS typed as the enum (the case the reported
consumer bug exercises). But on this lowered-to-String path, the
registered enum type is bypassed entirely; R229's lift is invisible to
clients. The bug R229 fixed remains observable for schemas that route
through the enum type, but not for graphitron's own Sakila example —
hence the absence of an end-to-end execution-tier round-trip on the
existing fixture.

The fix is structural: text-mapped-enum fields should be emitted as the
GraphQL enum type, same as jOOQ-native-enum fields (e.g. `rating: MpaaRating`
emits at `FilmType.java:35` as `typeRef("MpaaRating")`). graphql-java then
routes the runtime string through the registered enum type's Coercing,
matching against `.value(runtime)` and serializing back to `.name(sdl)`.
Investigate the field-type-emit fork (likely in `TypeClassGenerator` or
the ResultType-side emitters) and route text-mapped-enum fields through
the same path that already works for jOOQ-native enums.

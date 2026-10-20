---
id: R1078
title: "An enum-typed output field over a jOOQ-enum column is not checked against the Java enum constants"
status: Backlog
bucket: bug
priority: 4
theme: codegen-correctness
depends-on: []
created: 2026-10-20
last-updated: 2026-10-20
---

# An enum-typed output field over a jOOQ-enum column is not checked against the Java enum constants

## Goal

A GraphQL enum returned from a column whose database type is a Postgres enum (jOOQ generates a
Java enum class for it, such as sakila's `mpaa_rating` as `MpaaRating`) fails the build when one of
the database's literals has no GraphQL value to serialize as, the way the same mismatch already fails
the build on a filter argument. Today the output side is unchecked, so the gap surfaces per row at
runtime as graphql-java's unredacted "Can't serialize value ... Unknown value 'X'" error.

## Evidence

The output read hands graphql-java the Java enum constant unchanged (`FetcherEmitter.bindRaw`, the
`ColumnBackedField` arm), and graphql-java matches it by `name()` against the runtime value
`EnumTypeGenerator.buildValueDefinitionMethod` writes. `EnumMappingResolver.validateEnumFilter` and
`checkEnumConstants` are called from input sites only (filter arguments, lookup bindings, `@service`
arguments). The corpus fixture `graphitron-model/src/test/resources/corpus/enum-column.graphqls`
classifies `enum Rating { G PG PG13 R NC17 }` over `film.rating` as a plain column read with no
rejection, although `PG13` and `NC17` name no `MpaaRating` constant.

The manual's "Enum values: text-column and jOOQ-enum" section in
`docs/manual/how-to/map-types-to-tables.adoc` says the generator validates every GraphQL value
against the Java enum and that `@field` on a jOOQ-enum value fails the build; on the output side
neither holds.

## Questions for the plan

The check's direction: every database literal needs a GraphQL value (what output requires), which is
the reverse of the input check (every GraphQL value needs a constant); an enum shared by a filter and
an output field owes both. Whether a schema that builds today and fails after this lands needs a
warning period. Split off from R1068, which handles a free-text column, where the value set is not
known at build time.

---
id: R1068
title: "A database value outside an enum's mapping reads as null instead of a per-row serialization error"
status: Backlog
bucket: bug
priority: 3
theme: codegen-correctness
depends-on: []
created: 2026-10-19
last-updated: 2026-10-19
---

# A database value outside an enum's mapping reads as null instead of a per-row serialization error

## Goal

A GraphQL enum mapped over a free-text column (values bound with `@field(name:)`) resolves a row
whose column holds a value outside the mapping to `null`, as Graphitron 9 did, instead of adding one
unredacted serialization error per row that echoes the raw database value to the client.

## Evidence

Surfaced by the sis v9 against v10 differential test (2026-10-19), on a stable field used in
production. `enum Maalform { NB @field(name: "BOKMÅL"), NN @field(name: "NYNORSK"), EN @field(name: "ENGELSK") }`
is read from `SPRAKKODE_MALFORM`, which also holds `ARABISK`, `DANSK`, `NOB`, `NORSK` and `SAMISK`.

`personProfilerGittPersonlopenumre(eierOrganisasjonskode: "1234", personlopenumre: [...]) { personlopenummer maalform }`
returns `maalform: null` plus, per affected row, the error
"Can't serialize value (/.../maalform) : Invalid input for enum 'Maalform'. Unknown value 'ARABISK'".
Graphitron 9 built the mapping with `QueryHelper.makeEnumMap(...)`, which yields `null` for an
unknown value, and returned no error.

## Cause

`EnumTypeGenerator` sets each `GraphQLEnumValueDefinition`'s runtime value to the `@field(name:)`
string, and the fetcher hands the column's string to graphql-java unchanged. graphql-java's enum
coercing rejects a value no definition carries, at serialization time, where the error is not routed
through Graphitron's redaction. The leak of the raw value is part of the defect, not only the
behaviour change.

## Questions for the plan

Where the mapping happens (in the projection, so the field value is already the enum or `null`, or
in a coercing that tolerates unknown values), whether an unmapped value is logged server-side, and
whether the behaviour is unconditional or opt-in per enum. A column whose declared type is a
database enum with a known literal set could be checked at build time instead; a free-text column
cannot.

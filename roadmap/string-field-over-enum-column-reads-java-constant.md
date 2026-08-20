---
id: R754
title: "A String-typed field over a jOOQ-enum column reads the Java constant name, not the database literal"
status: Backlog
bucket: bug
priority: 6
theme: codegen-correctness
depends-on: []
created: 2026-08-20
last-updated: 2026-08-20
---

# A String-typed field over a jOOQ-enum column reads the Java constant name, not the database literal

A GraphQL field typed `String` over a column whose Postgres type is an enum returns the *Java
constant name*, not the value the database stores. The two coincide only while every literal of
that enum happens to be a valid Java identifier, so the defect is invisible in the current fixture
tree and appears the moment a consumer's enum carries a literal like `'PG-13'`.

## Where it comes from

The emitted per-column fetcher reads the value off the record through the typed jOOQ field:

```java
public static Object subjectKind(Object source) {
    return ((Record) source).get(Tables.JTI_APP_ACCOUNT.SUBJECT_KIND);
}
```

On an enum column `SUBJECT_KIND` is a `TableField<..., SubjectKind>`, so `get` returns the Java
enum constant rather than a `String`. graphql-java's `String` coercion then serialises it with
`String.valueOf(...)`, and a jOOQ-generated enum does not override `toString()`: it carries the
database literal on `getLiteral()` only. So `MpaaRating.PG_13` serialises as `"PG_13"` where the
column holds `'PG-13'`.

The typed read is not itself wrong; the missing step is asking for the literal. `record.get(field,
String.class)` converts through the column's binding and yields `'PG-13'`, which is what a
`String`-typed SDL field promises.

## Scope

Only the *projection* direction is affected, and only where the SDL type is `String`. Two adjacent
paths are already correct and should stay untouched:

* A field typed as a GraphQL enum goes through the enum-mapping path, which
  `EnumMappingResolver.validateEnumFilter` gates and which resolves the namespace deliberately.
  `FilmContent.rating: MpaaRating` is the tree's instance.
* The discriminated `__discriminator__` routing alias projects untyped (`Object.class`) and is read
  back with `String.class`, so it already carries the database literal. That asymmetry is
  deliberate and documented on `DiscriminatedTableFragments`.

Whether a `String` field over an enum column should be *rejected* rather than converted is the
design question this item has to answer: converting is the least surprising behavior and the SDL
type is a plain promise, but rejecting would push authors toward declaring the GraphQL enum and
getting the typed path. The write direction already converts (a `String` mutation input binds
through `DSL.val(value, col.getDataType())`), which argues for symmetry.

## Provenance

Surfaced while converting two discriminated fixture families to Postgres enum discriminators. The
spec being implemented predicted that a `String` field over the converted column would read back as
the literal and asked for a finding rather than a fixture retype if it turned out otherwise; it
does turn out otherwise. The conversion itself is unaffected because every converted literal
(`FILM`, `SHORT`, `PODCAST`, `APP`, `PERSON`) is a valid Java identifier, so the two spellings
coincide and `Subject.subjectKind` keeps answering correctly.

## Reproduction sketch

Give an interface over `film` a `String` field on `rating` (`mpaa_rating`), seed a `PG-13` row, and
query it: the field answers `"PG_13"`. The existing `Subject.subjectKind: String!` field over
`jti_subject.subject_kind` is the same shape with literals that hide the divergence.

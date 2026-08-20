---
id: R750
title: "@discriminate over an enum-typed discriminator column emits an uncast varchar comparison Postgres rejects"
status: Backlog
bucket: bug
priority: 6
theme: codegen-correctness
depends-on: []
created: 2026-08-20
last-updated: 2026-08-20
---

# @discriminate over an enum-typed discriminator column emits an uncast varchar comparison Postgres rejects

A `@discriminate(on:)` column whose database type is a Postgres enum generates a statement the
database refuses to plan. The discriminator is read and compared through an untyped field
reference, so the `@discriminator(value:)` strings bind as `varchar` and Postgres finds no operator
for `<enum> = character varying`. Every query over such an interface fails at execution, and the
failure reaches the client as a redacted generic error rather than anything naming the cause.

Every discriminated fixture in the tree uses a `varchar` discriminator column (`content_type`,
`fan_kind`, `subject_kind`, `party_kind`), so no test exercises an enum-typed one and the build is
green.

## Reproduction

Found while building a fixture for the participant alias collision item, on `e60c176`. Declaring an
interface over the existing `film` table with its `rating` column (Postgres type `mpaa_rating`) as
the discriminator:

```graphql
interface FilmKind @table(name: "film") @discriminate(on: "rating") {
    filmId: Int!    @field(name: "film_id")
    title:  String! @field(name: "title")
}

type GFilm implements FilmKind @table(name: "film") @discriminator(value: "G") {
    filmId: Int!    @field(name: "film_id")
    title:  String! @field(name: "title")
}
```

generates and compiles clean, and then fails on every query:

```
org.jooq.exception.DataAccessException: SQL [select "film"."rating" as "__discriminator__", ...
  from "public"."film" where "film"."rating" in (?, ?) order by "public"."film"."film_id" asc];
ERROR: operator does not exist: mpaa_rating = character varying
  Hint: No operator matches the given name and argument types. You might need to add explicit type casts.
```

The client sees only `An error occurred. Reference: <uuid>` (the error router redacts it, correctly,
since it is an unmatched exception).

## Where it comes from

Both the projection and the filter build the discriminator reference as an untyped field over the
qualified column name, which is what erases the column's type:

```java
DSL.field(filmTable.getQualifiedName().append(DSL.name("rating")), Object.class).as("__discriminator__")
DSL.field(filmTable.getQualifiedName().append(DSL.name("rating")), Object.class).in("G", "PG")
```

The `Object.class` reference is deliberate: the discriminator is resolved by SQL name rather than
through the generated column, which is what lets the joined-table participants qualify it to the
base table. The consequence is that the bind values arrive with no type information for Postgres to
unify against an enum column.

## Two candidate directions

* **Resolve the discriminator through the catalog column** where one exists, so the comparison
  carries the column's own data type and jOOQ binds an enum-typed value. The joined-table shapes
  need the qualification the current form provides, so this has to keep the base-table qualification
  the composite-key fixture depends on (the `subject_kind` re-declaration case).
* **Cast at the comparison** (render the discriminator as text, or the literals as the column type).
  Cheaper and dialect-visible, but it changes every discriminated statement in the tree, including
  the ones the SQL baseline pins cover.

Either way the interesting question is whether an enum-typed discriminator should be *supported* or
*rejected at build time* with an author error naming the column and its type. The classifier resolves
the discriminator column against the catalog, so the type is known before emission; a rejection
would be a small change and is strictly better than the current redacted runtime failure. Support is
the better end state: an enum discriminator column is a natural modelling choice, and consumers who
generate jOOQ from an existing schema do not control it.

## Related

Surfaced during the trace for R749 (`participant-projection-alias-collision`), which is an unrelated
defect on the same discriminated-interface family. Independent of it: this one fails loudly (a
database error on every query) rather than silently.

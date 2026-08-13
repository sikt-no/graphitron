---
id: R646
title: "Enforce @externalField helper parameter assignability against the parent table"
status: Backlog
bucket: architecture
priority: 4
theme: codegen-correctness
depends-on: []
created: 2026-08-13
last-updated: 2026-08-13
---

# Enforce @externalField helper parameter assignability against the parent table

`ServiceCatalog.reflectExternalField` takes a `parentTableClass` argument and documents its
contract as "the method must be `public static`, take exactly one parameter assignable from the
parent's jOOQ `Table<?>` class, and return parameterised `org.jooq.Field<X>`". The static and
return-type halves are enforced. The assignability half is not: the parameter check is
`org.jooq.Table.class.isAssignableFrom(p.getType())`, which admits *any* jOOQ table, and
`parentTableClass` is never read.

The consequence is a generated-code compile failure rather than a located rejection. A helper
declared `public static Field<Boolean> isEnglish(Film film)` referenced from an `@externalField`
on a type backed by a different table classifies clean, and `ProjectionUnitRenderer`'s
`SelectTerm.HelperCall` arm emits `FilmExtensions.isEnglish(table)` into that type's `$project`
unit with the wrong table type. The consumer sees a javac error inside generated sources with no
line back to the SDL that caused it, which is the failure mode the classify-time reflection checks
exist to prevent.

Reachable today at ordinary depth: two `@table` types on different tables can each declare an
`@externalField` naming the same helper. Found while specifying the nested-depth admission of
`ComputedField` (`roadmap/nested-depth-projected-reference-and-computed-leaves.md`), which makes it
materially easier to hit: a plain-object nesting type shared across two `@table` parents carries
*one* SDL declaration served by both parents, so the divergence needs no duplicated SDL to appear.

The fix is a one-line-ish tightening (compare `p.getType()` against the resolved parent table
class) plus a rejection message and a test, but it needs a scan of the existing corpus first: any
fixture relying on the loose check would start failing, and a helper legitimately typed on
`org.jooq.Table<?>` rather than the concrete generated class should keep working, so the
comparison has to be assignability in the right direction rather than class equality.

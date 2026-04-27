---
title: "Interface fetchers: cross-table participant fields via conditional LEFT JOIN"
status: Backlog
bucket: cleanup
priority: 3
---

# Interface fetchers: cross-table participant fields via conditional LEFT JOIN

Follow-up to the closed `interface-selection-set-projection` item. Same-table
selection-set projection is shipped (discriminator-first + per-participant
`$fields` union into a `LinkedHashSet`, replacing `asterisk()`). What remains
is the cross-table case: a `TableInterfaceType` participant declaring a field
that lives on a different table than the interface's own table (e.g.
`FilmContent.rating` resolving to `film.rating` while the interface is on
`content`).

## Design

`TypeFetcherGenerator.buildInterfaceFieldsList` already iterates each
`ParticipantRef.TableBound`. For participants with cross-table fields, emit a
runtime guard around the JOIN and the cross-table column additions:

```java
if (env.getSelectionSet().contains("FilmContent/rating")) {
    FilmTable filmTable = Tables.FILM.as(contentTable.getName() + "_film");
    fields.add(filmTable.RATING);
    // accumulate (filmTable, joinCondition) for emission between .from and .where
}
```

`DataFetchingFieldSelectionSet.contains("TypeName/fieldName")` is the
graphql-java type-scoped API for inline-fragment fields.

**Critical invariant.** The LEFT JOIN ON clause must include the participant's
discriminator value so non-matching rows carry NULL for the cross-table
columns rather than a spurious match:

```sql
LEFT JOIN film
  ON film.film_id = content.film_id
  AND content.content_type = 'FILM'
```

The TypeResolver routes each row to the correct concrete type by reading the
discriminator from the interface table; cross-table columns owned by other
participants stay NULL and are never accessed.

## Fixture additions

Two additions are needed to make the test matrix meaningful: without a
`ShortContent`-specific column there is no way to verify a `FilmContent`-only
query does not pull `ShortContent` columns; without a cross-table field on
`FilmContent` there is no way to verify the LEFT JOIN is omitted when not
requested.

1. `short_description` column on `content` (varchar, NULL on FILM rows,
   populated on SHORT rows); add `description: String @field(name:
   "SHORT_DESCRIPTION")` to `ShortContent`.
2. `rating: String @reference(path: [{key: "content_film_id_fkey"}]) @field(name:
   "RATING")` on `FilmContent`. The FK is already in the schema.

## Tests

Unit (`TypeFetcherGeneratorTest`):

- `_crossTableField_emitsLeftJoin`: body contains a `LEFT JOIN` with the
  discriminator value in the ON condition.
- `_crossTableField_joinIncludesDiscriminatorCondition`: ON clause has both the
  FK condition and the discriminator equality.

Execution (`GraphQLQueryTest`):

- `allContent_crossTableField_joinsFilmOnlyWhenRequested`: `... on FilmContent {
  rating }` returns the rating from the joined film row.
- `allContent_crossTableField_absentWhenNotRequested`: query without `rating`
  emits SQL with no reference to `film` (verify via jOOQ `ExecuteListener` or
  rendered statement string).
- `allContent_filmContentOnly_selectsLengthNotShortDescription`,
  `allContent_shortContentOnly_selectsDescriptionNotLength`: per-participant
  column isolation.
- `allContent_bothParticipantSpecificFields`: `length`, `description`, and
  `rating` requested together; assert each appears for the right participant
  type.

## Non-goals

Track B (multi-table polymorphic interfaces with no shared interface table)
remains a separate item gated on the design decision in
`stub-interface-union-fetchers.md`. The implementation here is structured so
Track B is an extension (same per-participant LEFT JOIN pattern, different
table argument) rather than a rework.

---
title: "Interface fetchers: selection-set-aware projection"
status: Spec
bucket: cleanup
priority: 2
---

# Interface fetchers: selection-set-aware projection

Replace the `asterisk()` over-fetch in `buildQueryTableInterfaceFieldFetcher` and
`buildTableInterfaceFieldFetcher` with per-participant selection-set filtering and
conditional LEFT JOINs. This is Track A gap C from the SQL comparison in
`stub-interface-union-fetchers.md`.

---

## Problem

Both interface fetcher methods emit:

```java
.select(contentTable.asterisk(), DSL.field(DSL.name("content_type")))
```

This fetches every column unconditionally and duplicates the discriminator. A query
for `{ allContent { title } }` fetches all six columns of `content` on every call.

The correct pattern used by all non-interface table fetchers is:

```java
.select(Film.$fields(env.getSelectionSet(), table, env))
```

Interface fetchers cannot simply call a single type's `$fields` because at
code-generation time any participant might be returned. The fix must union across
participants and handle the case where a participant's fields live on a different
table from the interface's own table.

---

## Design

The interface table is the spine of the query. The discriminator column is always
selected from it. Each participant contributes its field selection; fields on the
interface table are selected directly, fields on another table require a conditional
LEFT JOIN.

**Critical invariant:** the LEFT JOIN condition includes the participant's
discriminator value so that non-matching rows carry NULL for that participant's
cross-table columns rather than a spurious join match:

```sql
LEFT JOIN film
  ON film.film_id = content.film_id
  AND content.content_type = 'FILM'   -- non-FILM rows stay NULL
```

The TypeResolver reads the discriminator from the interface table row, routes to
the right concrete type, and that type's resolver reads from the columns it owns.
Cross-table columns for other participants are NULL and are never accessed.

**Generated SQL examples** (given the extended fixture schema below):

Query: `{ allContent { title } }`
```sql
SELECT content.content_type, content.title
FROM content
WHERE content.content_type IN ('FILM', 'SHORT')
ORDER BY content.content_id ASC
```

Query: `{ allContent { ... on FilmContent { rating } } }`
```sql
SELECT content.content_type, film.rating
FROM content
LEFT JOIN film
  ON film.film_id = content.film_id
  AND content.content_type = 'FILM'
WHERE content.content_type IN ('FILM', 'SHORT')
ORDER BY content.content_id ASC
```

Query: `{ allContent { title ... on FilmContent { length rating } ... on ShortContent { description } } }`
```sql
SELECT content.content_type, content.title, content.length, film.rating,
       content.short_description
FROM content
LEFT JOIN film
  ON film.film_id = content.film_id
  AND content.content_type = 'FILM'
WHERE content.content_type IN ('FILM', 'SHORT')
ORDER BY content.content_id ASC
```

The `film` JOIN is absent when no `film`-backed field is requested, even if
`FilmContent` is a known participant.

---

## Fixture additions

Two additions are needed to make the test matrix meaningful. Without a
`ShortContent`-specific column there is no way to verify that a `FilmContent`-only
query does not pull `ShortContent` columns, and without a cross-table field on
`FilmContent` there is no way to verify that the LEFT JOIN is omitted when not
requested.

### 1. `short_description` on `ShortContent`

**`init.sql`** — add column and update seed rows:

```sql
ALTER TABLE content ADD COLUMN short_description varchar(255);
-- Update the two SHORT seed rows:
UPDATE content SET short_description = 'A short sunrise film'   WHERE content_type = 'SHORT' AND title = 'Sunrise';
UPDATE content SET short_description = 'A brief musical piece'  WHERE content_type = 'SHORT' AND title = 'Interlude';
```

Or inline in the INSERT:

```sql
INSERT INTO content (content_type, title, length, film_id, short_description) VALUES
    ('FILM',  'ACADEMY DINOSAUR (extended)',  120, 1, NULL),
    ('FILM',  'ACE GOLDFINGER (extended)',     90, 2, NULL),
    ('SHORT', 'Sunrise',                       NULL, NULL, 'A short sunrise film'),
    ('SHORT', 'Interlude',                     NULL, NULL, 'A brief musical piece');
```

**`schema.graphqls`** — add field to `ShortContent`:

```graphql
type ShortContent implements Content @table(name: "content") @discriminator(value: "SHORT") {
    contentId:   Int!   @field(name: "CONTENT_ID")
    title:       String! @field(name: "TITLE")
    description: String  @field(name: "SHORT_DESCRIPTION")
}
```

### 2. `rating` on `FilmContent` via JOIN to `film`

`content.film_id` already references `film(film_id)`. The FK constant in the
generated `Keys` class is the existing `content_film_id_fkey`.

**`schema.graphqls`** — add field to `FilmContent`:

```graphql
type FilmContent implements Content @table(name: "content") @discriminator(value: "FILM") {
    contentId: Int!    @field(name: "CONTENT_ID")
    title:     String! @field(name: "TITLE")
    length:    Int     @field(name: "LENGTH")
    rating:    String  @reference(path: [{key: "content_film_id_fkey"}])
                       @field(name: "RATING")
}
```

Seed data: both FILM rows already reference film rows 1 and 2, which have non-null
`rating` values, so no data change is needed.

---

## Implementation

### `TypeFetcherGenerator.java`

Both `buildQueryTableInterfaceFieldFetcher` and `buildTableInterfaceFieldFetcher`
share the same fix shape. The current `.select(table.asterisk(), discriminatorField)`
expression is replaced by a runtime-built field list:

1. Always include the discriminator: `DSL.field(DSL.name(discriminatorColumn))`.
2. For each participant, partition its fields into two groups at code-generation time:
   - **Same-table fields** (field's table matches the interface table): contribute
     `ParticipantType.$fields(env.getSelectionSet(), table, env)` — no JOIN.
   - **Cross-table fields** (field's terminal table is a different table): contribute
     a `LEFT JOIN participantTable ON <fkCondition> AND <discriminatorColumn> = '<value>'`
     and `ParticipantType.$fields(env.getSelectionSet(), joinAlias, env)` from that
     alias.
3. Collect all field lists into a `LinkedHashSet<Field<?>>` (preserves order,
   eliminates duplicates for shared columns like `title` appearing in both
   participant `$fields` calls).
4. Emit `.select(new ArrayList<>(fields))` in place of `.select(asterisk(), ...)`.
5. Emit any accumulated LEFT JOINs before the `.where(...)` clause.

For Track A today all participants are on the same table, so step 2 produces only
same-table entries and no JOINs — the generated code degenerates cleanly to a pure
field-union.

### `init.sql` and `schema.graphqls`

As described in Fixture additions above.

---

## Tests

### Unit (`TypeFetcherGeneratorTest`)

For both `buildQueryTableInterfaceFieldFetcher` and `buildTableInterfaceFieldFetcher`:

- `_noAsterisk_inSelectClause` — generated method body does not contain `asterisk()`.
- `_discriminatorAlwaysSelected` — body contains the discriminator column reference
  regardless of selection set.
- `_crossTableField_emitsLeftJoin` — when a participant has a cross-table field,
  the body contains a LEFT JOIN with the discriminator value in the condition.
- `_crossTableField_joinIncludesDiscriminatorCondition` — the LEFT JOIN ON clause
  contains both the FK condition and the discriminator equality.

### Execution (`GraphQLQueryTest`)

- `allContent_onlySharedFields_selectsNoParticipantColumns` — query `{ allContent { title } }`;
  assert returned data is correct and (via SQL log capture or result-set inspection)
  that `length`, `short_description`, and no `film` join appears.
- `allContent_filmContentOnly_selectsLengthNotShortDescription` — `... on FilmContent { length }`;
  assert FILM rows have `length`, SHORT rows resolve correctly, no `short_description` fetched.
- `allContent_shortContentOnly_selectsDescriptionNotLength` — `... on ShortContent { description }`;
  assert SHORT rows have `description`, FILM rows resolve correctly, no `length` fetched.
- `allContent_crossTableField_joinsFilmOnlyWhenRequested` — `... on FilmContent { rating }`;
  assert FILM rows carry the `rating` value from the joined `film` table.
- `allContent_crossTableField_absentWhenNotRequested` — query with no `rating`; assert
  no JOIN to `film` in the executed SQL (verify via jOOQ `ExecuteListener` or by
  confirming `film` table is not referenced in the query string).
- `allContent_bothParticipantSpecificFields` — request `length`, `description`, and
  `rating` together; assert all three appear in results for the appropriate types.

---

## Non-goals

- Track B (participants on different primary tables): a separate item once the
  Track B design decision in `stub-interface-union-fetchers.md` is resolved. The
  implementation above is intentionally structured so Track B is an extension
  (same per-participant LEFT JOIN pattern, different table argument) rather than a
  rework.
- Union types: same mechanism applies but union types are not in the fixture schema
  yet.

---

## Changelog

- **2026-04-27** — Spec drafted. Follows design session identifying that even Track A
  participants can have cross-table fields; the LEFT JOIN must be gated on both the
  selection set and the discriminator value.

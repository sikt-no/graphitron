---
title: "Interface fetchers: selection-set-aware projection"
status: In Review
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
selected from it. Each participant contributes its field selection via its already-
generated `$fields` method (participant types are classified as `TableType` in the
first TypeBuilder pass, so `FilmContent.$fields` and `ShortContent.$fields` already
exist). Fields on the interface table are selected directly; fields on another table
require a conditional LEFT JOIN.

**No SQL UNION.** For a single-table interface all participants live on the same
table; the result is a single SELECT with an optional LEFT JOIN per cross-table
participant field group. SQL UNION is only relevant for multi-table interfaces
(Track B, a separate item).

**Participant `$fields` calls.** The fetcher calls each participant's `$fields`
with the selection set and the interface table, then unions the results into a
`LinkedHashSet` (deduplication preserves order; shared fields like `title`
appearing in both participants collapse to one column reference):

```java
var fields = new LinkedHashSet<Field<?>>();
fields.add(DSL.field(DSL.name("content_type")));  // discriminator always first
fields.addAll(FilmContent.$fields(env.getSelectionSet(), contentTable, env));
fields.addAll(ShortContent.$fields(env.getSelectionSet(), contentTable, env));
```

`$fields` iterates `sel.getFieldsGroupedByResultKey()`, which flattens inline
fragments, so `{ ... on FilmContent { length } }` naturally contributes `length`
to the result just as a top-level `{ length }` would.

**Cross-table fields and conditional LEFT JOINs.** When a participant has fields
on a different table (e.g. `FilmContent.rating` on the `film` table), the fetcher
must:

1. Determine at runtime whether any such cross-table field is requested.
2. If yes, include the LEFT JOIN and the field in the SELECT list.

Determining this requires type-scoped selection-set checking. graphql-java's
`DataFetchingFieldSelectionSet.contains(String)` accepts a `"TypeName/fieldName"`
qualified name, so the JOIN guard is:

```java
if (env.getSelectionSet().contains("FilmContent/rating")) {
    FilmTable filmTable = Tables.FILM.as(contentTable.getName() + "_film");
    fields.add(filmTable.RATING);
    // LEFT JOIN is added to the query after the .from() call
}
```

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
expression is replaced by a runtime-built field list derived from per-participant
`$fields` calls.

**Participant `$fields` are already generated.** Participant types (`FilmContent`,
`ShortContent`) are classified as `TableType` in TypeBuilder's first pass (they
carry `@table`). `TypeClassGenerator` generates `$fields` for every `TableType`,
so `FilmContent.$fields(sel, contentTable, env)` and
`ShortContent.$fields(sel, contentTable, env)` already exist.

**Same-table field union (current scope):**

1. Always include the discriminator: `DSL.field(DSL.name(discriminatorColumn))`.
2. For each participant, call `ParticipantType.$fields(env.getSelectionSet(), table, env)`.
   `$fields` iterates `sel.getFieldsGroupedByResultKey()`, which flattens inline
   fragments — `{ ... on FilmContent { length } }` contributes `length` naturally.
3. Collect all results into a `LinkedHashSet<Field<?>>` to deduplicate shared columns
   (e.g. `title` from both participants collapses to one reference).
4. Emit `.select(new ArrayList<>(fields))` in place of `.select(asterisk(), ...)`.

**Cross-table fields (needed for the `rating` fixture extension):**

For each participant that has fields on a different table (determined at code-
generation time by inspecting the participant's classified fields):

5. Emit a type-scoped selection check at runtime:
   ```java
   if (env.getSelectionSet().contains("FilmContent/rating")) {
   ```
   `DataFetchingFieldSelectionSet.contains("TypeName/fieldName")` is the graphql-java
   API for type-scoped inline-fragment fields.
6. Inside the guard, declare the joined table alias, add the field(s) to the
   `LinkedHashSet`, and record the LEFT JOIN to emit.
7. After `.from(table)`, emit any accumulated LEFT JOINs:
   ```java
   LEFT JOIN film ON film.film_id = content.film_id
                  AND content.content_type = 'FILM'
   ```
   The discriminator condition on the JOIN prevents spurious matches for non-FILM rows.

**Emitting the query.** jOOQ's `SelectJoinStep` is returned by `.from(table)`; each
`leftJoin(...).on(...)` call chains off it before `.where(condition)`. The code
generator accumulates a list of `(joinTable, onCondition)` pairs and emits them in
order between `.from` and `.where`.

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
- **2026-04-27** — Spec revised: corrected that participant types (`FilmContent`,
  `ShortContent`) already have `$fields` generated (they are `TableType` in
  TypeBuilder's first pass); no SQL UNION for single-table interface (that is only
  relevant for Track B multi-table); cross-table field JOIN guard uses
  `sel.contains("TypeName/fieldName")` type-scoped API.

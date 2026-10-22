---
id: R1088
title: "A cursor issued by Graphitron 9 is rejected, so a client that stores cursors loses its place at the upgrade"
status: Spec
bucket: enhancement
priority: 3
theme: pagination
depends-on: []
created: 2026-10-22
last-updated: 2026-10-22
---

# A cursor issued by Graphitron 9 is rejected, so a client that stores cursors loses its place at the upgrade


## Goal

A cursor that Graphitron 9 issued keeps working as `after:` or `before:` after the consumer upgrades to Graphitron 10, on a connection whose order columns are unchanged. Today the generated decoder rejects it with "cursor is not valid", so a client that stores its last cursor as a checkpoint either replays the feed from the start or loses its place. After this item Graphitron 10 has one cursor format, the one Graphitron 9 used: URL-safe Base64 without padding of a JSON array of the order columns' values. A 9 cursor needs no translation, and the manual's "Cursor encoding is part of the public contract" section holds across the major version.

On the sakila example, where `filmsConnection` orders on `film_id`, the cursor Graphitron 9 issued for film 2 is `WzJd`, the encoding of `[2]`:

```graphql
{ filmsConnection(first: 2, after: "WzJd") { nodes { title } } }
```

Today this fails with `cursor is not valid (was: "WzJd")`. After this item it returns films 3 and 4, and the `endCursor` of that page is `WyI0Il0`, the encoding of `["4"]`. Graphitron 10 writes each value as a JSON string and reads any JSON scalar, so both cursors decode under either version's rules.

## Why a restart is not acceptable

Reported by sis. Its event feeds (`studenthendelser`, `personProfilhendelser` in the stable contract; `emnehendelser`, `studieprogramhendelser`, `evukurshendelser` in beta) are paged by consumers that poll with `after: <last cursor>` and persist that cursor between runs, often for days. sis had accepted the format change on the assumption that clients restart pagination. That holds for a UI paging through a list, but not for a feed checkpoint. `studenthendelser` orders on `INSTITUSJONSNR_EIER, ROW_SCN, ID`, all numeric, and the column list is the same in both versions.

## The two formats today

A *cursor* is the opaque string a connection hands out per edge and in `pageInfo`, naming the position of a row in the connection's order; the client passes it back as `after:` or `before:` and the generated fetcher turns it into a keyset seek, a `WHERE (order columns) > (cursor values)` predicate.

- **Graphitron 9** (`graphitron-common` `QueryHelper.getOrderByToken` / `getOrderByValues` at tag `v9.3.0`): `jsonArray(<order columns>)` built in SQL, so each value arrives in the database's JSON rendering, then URL-safe Base64 without padding. Decoding parsed the array with `org.json`, loaded it into a record of the order fields (`ctx.newRecord(fields).fromArray(...)`) and bound each value with the field's `DataType`. The multi-table interface variant (`getOrderByTokenForMultitableInterface`) wrapped the token in a JSON object with `typeName` and `fields`.
- **Graphitron 10** (`ConnectionHelperClassGenerator`, the emitted `ConnectionHelper.encodeCursor` / `decodeCursor`): each value's `toString()`, joined by U+0000 with U+0001 for SQL `NULL`, then standard Base64 with padding. Each token is converted with `DataType.convert`.

The difference is a by-product of the rewrite (the cursor is now built in Java from the fetched record, with no JSON dependency), not a deliberate wire change. The 10 format also carries three defects of its own that the 9 format does not have: a text value containing U+0000 splits into two tokens (PostgreSQL forbids NUL in text, Oracle, which sis runs, does not); a text value that is exactly U+0001 decodes as `NULL`; and a single text order column holding `''` encodes to the empty cursor, which `pageRequest` reads as no cursor (the pitfall `connections.adoc` documents). Graphitron 10 has not been released, so this is the cheapest point there will be to change what it emits.

## Design

`ConnectionHelper` gets one codec, shaped like 9's on the wire and like 10's in how it binds values.

**Encode.** `encodeCursor` writes a JSON array with one element per order column, in order: SQL `NULL` as `null`, every other value as a JSON string of `value.toString()`, the value `record.get(column)` returns today. The string is escaped per RFC 8259: `"`, `\` and every character below U+0020 (`\n`, `\t` and the others as `\u00XX`), so U+0000 and U+0001 become ordinary escaped characters. The array's UTF-8 bytes are encoded with `Base64.getUrlEncoder().withoutPadding()`.

Every non-null value is a string on purpose. The decoder converts each element with the column's `DataType`, so a JSON number or boolean tag would carry nothing the column does not already know, and choosing it by the value's Java class would be a second type system beside the `DataType` (and would emit invalid JSON for a `Double` NaN or infinity). The cursor stays column-driven, as it is today.

**Decode.** `decodeCursor` keeps its signature, its `null` handling (`DSL.noField` per column) and its single client error. Its body becomes: `Base64.getUrlDecoder()` (which accepts padded and unpadded input), UTF-8, then an emitted reader that parses one JSON array of scalars into a `List<Object>`: a string becomes its unescaped text, a number its literal text, `true`/`false` a `Boolean`, `null` a null. Arity is strict, as today. A `null` element binds `DSL.val((Object) null, col.getDataType())`; any other element is converted with `col.getDataType().convert(element)`, and a null result is rejected as "cursor token not coercible", as today.

The reader rejects anything else as malformed: a nested array or object (so the 9 multi-table object form is rejected, see below), trailing content after the array, an unterminated string, a bad escape. **Every reader failure is thrown as `IllegalArgumentException`**, including an overrun past the end of the input, so it lands in the existing `IllegalArgumentException | DataTypeException` catch and surfaces as `cursor is not valid (was: "…")`. An `IndexOutOfBoundsException` or `NumberFormatException` escaping the reader would be a redacted server error for what is a client's malformed input. The reader parses a number by its JSON grammar and keeps the literal text, so `DataType.convert` does the numeric conversion and a 19-digit `ROW_SCN` loses no precision.

The reader and writer are private static helpers of the emitted `ConnectionHelper` (`writeJsonString`, `readJsonArray` or similar), written in statement form with explicit types and Java 17 syntax, as the rest of the class is. They add no dependency: the generated code still needs only jOOQ, graphql-java and the JDK.

**What a 9 value looks like to `DataType.convert`.** Checked against jOOQ 3.20.11: the database JSON renderings convert to the column's type, `"2024-01-01T00:00:00"` (Oracle `DATE`, PostgreSQL `timestamp`) to `LocalDateTime`, `LocalDate` and `Timestamp`; fractional seconds and offsets (`"2024-01-01T10:11:12.000000+02:00"`) to `LocalDateTime` / `OffsetDateTime`; a number's text to `Long`, `Integer` or `BigDecimal`; a UUID string to `UUID`. No 9-specific conversion is needed, so there is no 9-specific code path at all: a 9 cursor and a 10 cursor go through the same reader and the same conversion, differing only in whether a number element was written bare.

**A converter-backed order column.** `record.get(column)` on a column with a jOOQ `Converter` returns the user type, so a 10 cursor carries the user value's text, and decode converts it back through the column's converted `DataType`, as it does today. A 9 cursor for the same column carries the raw database value (9's `jsonArray` rendered the column before the converter). `DataType.convert` on a converted type is expected to accept the database type's value as well; `converterPlacesConnection` (an `org_code` bigint column exposed as `String`) pins it. If it does not, the fix is to try the converter's database type when the user-type conversion yields null, inside `decodeCursor`; the implementer decides from the test.

**Out of scope, and why.**

- *The 9 multi-table interface cursor* (`{"typeName": …, "fields": …}`). 10's multi-table connection orders the union on the participants' key columns and then the type name, a different seek from 9's per-type token, so a 9 cursor cannot be translated position for position. sis's event types are single-table interfaces with a discriminator, which page as plain single-table connections. The object form fails decode with the ordinary client error. A consumer who needs it files its own item.
- *A 9 cursor with a `NULL` order value issued on Oracle.* jOOQ renders `jsonArray` without an `ON NULL` clause (checked on its open-source dialects), and Oracle's `JSON_ARRAY` defaults to `ABSENT ON NULL`, so such a cursor has fewer elements than the order has columns. 9 bound it positionally, shifting the values; 10's strict arity rejects it. Feed keys are `NOT NULL`, so this does not reach the reported case, and rejecting a cursor 9 itself mis-decoded is the correct outcome.
- *Cursors issued by a Graphitron 10 snapshot.* They use the format this item retires and stop decoding. Graphitron 10 is unreleased; a consumer running a snapshot in production with persisted cursors is not known. If the reviewer knows of one, the fallback is cheap and unambiguous enough to add: a decoded text that does not start with `[` is read as the NUL tuple.
- *`graphitron-mcp`'s own page cursors* (`McpWire.encodeCursor`, a NUL-joined keyset cursor for the MCP tool surface). They are hand-written server code, not emitted into consumers, and no client stores them across a Graphitron 9 upgrade. They stay as they are, and the retirement sweep below excludes them.

## Implementation

- `graphitron/src/main/java/no/sikt/graphitron/rewrite/generators/util/ConnectionHelperClassGenerator.java`: rewrite the `encodeCursor` and `decodeCursor` emissions as above and add the two JSON helper methods to the emitted class. Update the class javadoc's "Cursor encoding" paragraph and the inline comments on the decode body (the arity comment's "PostgreSQL strings cannot contain NUL" premise goes). `decodeCursor`'s blame contract is unchanged and its comment says so. Callers (`pageRequest`, `edges`, `pageInfo`, and `MultiTablePolymorphicEmitter`, which reaches the codec only through `PageRequest` and `encodeCursor`) do not change.
- `docs/manual/how-to/connections.adoc`, "Cursor encoding is part of the public contract": state the format (URL-safe Base64 without padding of a JSON array of the order columns' values, one per column, `null` for SQL `NULL`), that it is the format Graphitron 9 issued so a 9 cursor carries across the upgrade on an unchanged order, and that the 9 multi-table interface cursor does not. Drop the empty-cursor sentence's claim that graphitron itself emits an empty cursor; the rest of that bullet (an empty `after:` reads as no cursor) stays. The pitfalls bullet "Cursor wire format embeds the active order's columns" stays.
- `docs/manual/how-to/sort-results.adoc`: the "base64-encoded NUL-separated tuple" sentence becomes the same format statement, by reference to the connections page.
- `docs/manual/how-to/migrating-from-legacy.adoc`, the `@orderBy` / `@order` / `@defaultOrder` bullet: replace "The cursor wire format is the same base64-encoded NUL-separated tuple" with the true statement: the format is the one 9 issued, a cursor a client stored under 9 keeps working while the connection's order columns are unchanged, and multi-table interface connections are the exception, whose stored cursors must be dropped.
- `GraphQLQueryTest`: the malformed-cursor tests that hand-build NUL-format cursors (`filmsConnection_nonCoercibleTokenAfterCursor_surfacesClientError`, `filmsByRateDescTitleAsc_underSplitAfterCursor_surfacesClientError`, `filmsConnection_overSplitAfterCursor_surfacesClientError`) are rebuilt as JSON-array cursors with the same failure meaning (non-coercible element, too few elements, too many elements), and their comments lose the NUL vocabulary.

## Tests

All execution tier, in `graphitron-sakila-example` `GraphQLQueryTest` (or `MultiTableOrderingExecutionTest` where the fixture lives there), asserting returned rows and the client error, never generated text or SQL.

- **A Graphitron 9 cursor pages correctly.** Build the cursor the way 9 did, in SQL: select `jsonArray(<order columns>)` for a seeded row through the test's `DSLContext`, encode it URL-safe without padding, and pass it as `after:`; assert the returned rows are the ones after that row. Cover `filmsConnection` (one numeric column, the `WzJd` example above) and `filmsOrderedConnection` with `RATE_DESC_TITLE_ASC` (`rental_rate DESC, title ASC`, a numeric and a text column, across the 2.99 tie). Repeat one of them as `before:` with `last:`.
- **The reported shape: a numeric composite key.** A 9 cursor over a multi-column numeric order, `filmActorsFaceted` (ordered on `film_actor`'s primary key `(actor_id, film_id)`), pages to the expected rows.
- **A 9 timestamp in Oracle's rendering.** Call the emitted `ConnectionHelper.decodeCursor` directly with a hand-built `["2024-01-01T00:00:00"]` cursor (bare JSON string, as Oracle renders `DATE`) against a timestamp column such as `FILM.LAST_UPDATE`, and assert the bound value; repeat with fractional seconds.
- **A converter-backed column.** A 9 cursor carrying a bare JSON number for `converterPlacesConnection`'s `org_code` pages to the expected rows.
- **10 round-trips.** Every existing forward/backward paging test keeps passing on the new format unchanged, which is the round-trip coverage. Add one assertion that a page's `endCursor` decodes (URL-safe Base64, no padding) to the JSON array of strings the design names, so the emitted format is pinned and not merely self-consistent.
- **Escapes.** `decodeCursor` called directly with an element carrying `\"`, `\\`, `\u0000` and a non-BMP character escaped as a surrogate pair binds the unescaped text. No sakila row holds such text, which is why this one goes through the helper directly.
- **Malformed input stays a client error.** The three rebuilt tests above, plus: a JSON object (the 9 multi-table form), a nested array element, trailing content after the array, an unterminated string, and input truncated mid-escape. Each asserts `cursor is not valid (was: "…")` and that the message is not the redacted `An error occurred. Reference:` form, which is what proves every reader failure reaches the catch.

## Retired vocabulary

The NUL-joined cursor format of `ConnectionHelper`: "NUL-separated tuple", "NUL-joined" and U+0000 as a cursor separator, U+0001 (SOH) as the cursor's `NULL` sentinel, "PostgreSQL strings cannot contain NUL" as a cursor premise, and the claim that graphitron emits an empty cursor for a `''` text value. Sweep the generator, the emitted-code comments, `GraphQLQueryTest`, and `docs/manual/`. `graphitron-mcp`'s `McpWire` keyset cursor is a different cursor and keeps its NUL joiner.

## Roadmap entries

R1085 (`manual-v9-cursor-and-error-path-claims`) fixes the same `migrating-from-legacy.adoc` cursor sentence, with the opposite advice ("clients must drop stored cursors"), which this item makes untrue. This item owns that sentence: R1085's error 1 is dropped from its body when this item moves to Ready, leaving it the error-path fix alone. If R1085 lands first, this item's docs edit replaces its wording.

## Other solutions we've considered

- **Keep 10's format and add a legacy decoder beside it**, recognising a 9 cursor by its encoding (URL-safe alphabet, missing padding) and content (no U+0000, a JSON array of the order's arity). Two codecs, plus a runtime guess at which one an opaque string is in, with a residual ambiguity that cannot be removed: a one-column text order whose 10 value is itself a one-element JSON array reads either way. It also raises a lifetime question (permanent, or opt-in behind a plugin setting with a removal date nothing enforces), and a setting is one more configuration axis to test in both positions. Switching 10 to URL-safe output on top of it, which the Backlog sketch considered for URL friendliness, removes the encoding signal and leaves content as the only discriminator. Adopting 9's shape removes the guess, the ambiguity, the setting and the lifetime question together, and fixes 10's NUL, SOH and empty-cursor defects in passing.
- **Write numbers and booleans bare**, matching 9's bytes more closely. Rejected above: the tag carries nothing the column's `DataType` does not, and choosing it by Java class is a parallel type system. Byte-for-byte equality with 9 is not reachable anyway, since 9 used each database's JSON rendering.
- **`Record.formatJSON` in array format** for the writer. It renders values by their Java type (bare numbers) and through jOOQ's JSON formatting rules rather than the cursor's, so it brings back the type tag and couples the wire format to a formatting API; the writer needed is a dozen lines of string escaping.
- **A JSON library on the consumer classpath** (as 9 depended on `org.json`). The generated code is a consumer artifact with no runtime dependency on graphitron beyond jOOQ and graphql-java; a scalar-array reader does not justify one.

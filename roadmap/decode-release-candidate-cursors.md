---
id: R1144
title: "A cursor a Graphitron 10 release candidate issued keeps its place after the upgrade"
status: Ready
bucket: enhancement
priority: 2
theme: pagination
depends-on: []
created: 2026-11-02
last-updated: 2026-11-03
---

# A cursor a Graphitron 10 release candidate issued keeps its place after the upgrade

## Goal

A client that stored a connection cursor from a Graphitron 10 release candidate keeps paging from it after its subgraph upgrades to the next release. Every published release candidate, `10.0.0-RC1` through `10.0.0-RC46`, writes cursors in a format of its own, and the next release switches to the JSON format Graphitron 9 wrote, on both the writing and the reading side. Without this item that release answers every cursor a client stored under a release candidate with `cursor is not valid`, and the client restarts from the first page. After it, the decoder reads both kinds of cursor, the release candidates' and the JSON form, and the writer keeps writing only the JSON form.

A cursor is the opaque string a connection hands out per row (`edges.cursor`, `pageInfo.endCursor`) and takes back as `after` or `before`. Clients store them: the event-feed consumers of sis (Sikt's student information system, a Graphitron 9 subgraph) keep the last one as a checkpoint for days, which is why R1088 (the change that adopted Graphitron 9's JSON format, unreleased) made the decoder read Graphitron 9's cursors. Several Sikt subgraphs already run release candidates (four in the fs-plattform repository as of 2026-10-21, on RC25 to RC41); whether their clients store cursors across a release is not known yet, and is what decides how much this item matters to them. "Trunk" below is the integration branch the next release is cut from. The same row in each format, sakila's first film ordered by `title, film_id`:

```
RC1 to RC46:             QUNBREVNWSBESU5PU0FVUgAx         ACADEMY DINOSAUR, U+0000, 1; standard Base64
trunk and Graphitron 9:  WyJBQ0FERU1ZIERJTk9TQVVSIiwxXQ   ["ACADEMY DINOSAUR",1]; URL-safe Base64, no padding
```

The promise is the one the manual already makes for Graphitron 9's cursors: a stored cursor keeps its place while the connection pages on the same columns. A release-candidate cursor reads exactly as RC46 read it, so one that RC46 could already page from pages after the upgrade, and one that RC46 itself rejected is still rejected. The manual draft under Implementation is the promise in the words a client's developer reads.

The item should ship in the same release as R1088 and R1142 (which made a newly written cursor byte-identical to Graphitron 9's on Oracle), the first one after RC46. A release that carries R1088 without it is the break.

## What the release candidates wrote

Read 2026-11-02 from `ConnectionHelperClassGenerator` in the published RC14, RC20, RC28, RC39 and RC45 jars, identical in all five, and in the source at `e99388f85` ("roadmap: R1083 Spec -> Ready"), the commit RC46 was cut from. R1088's delivery (`04f8bd139`, 2026-10-29) and R1142's (`9022a2c85`) are not ancestors of it, so no published release writes or reads the JSON form. RC1 to RC13 were not read from their jars; the NUL-joined encoding with its U+0001 sentinel is in the generator from the module's first commit (`56c8c8241`, 2026-04-24) until R1088 replaced it, so they wrote it too.

* **Encode.** For each order column, `record.get(column).toString()`, or U+0001 for SQL `NULL`; the tokens joined by U+0000; the result encoded with `Base64.getEncoder()`, the standard alphabet with `=` padding.
* **Decode.** `Base64.getDecoder()`, split on U+0000 keeping trailing empty tokens (`split("\u0000", -1)`), strict arity, U+0001 as a typed `NULL` (`DSL.val((Object) null, col.getDataType())`), every other token through `col.getDataType().convert(token)`, and a `null` from that rejected as not coercible. Bad Base64, arity and coercion failures all surfaced as the same `cursor is not valid (was: "…")` client error trunk raises. `DataType.convert(Object)` is deprecated for removal since jOOQ 3.20; RC46 suppressed `deprecation` and `removal` on the whole `ConnectionHelper` class. The non-deprecated `DSL.val((Object) token, type).getValue()`, which trunk's `ConnectionHelper` already uses to coerce facet values, gives the same answer: checked 2026-11-02 on jOOQ 3.20.11 for an enum (`G`, `NC-17`, `NC_17`, `PG_13`), `integer` (`2`, `abc`, `2.5`), `date`, `timestamp` (`LocalDateTime.toString()` forms with and without a fraction, and garbage) and `numeric` (`1.50`, `x`), identical value or `null` in every case.
* **The token is the user-side value.** `record.get` answers the value after the column's converter, where trunk writes the database value through `ColumnValues`, and `DataType.convert` on the column's own (converted) type is what read it back. Whether that round-trips depends on the converter; R1088's changelog entry records converter-backed columns failing on the second page in the release candidate itself.
* **Enum columns.** A jOOQ `EnumType`'s `toString()` is the Java constant's name, and the column's `DataType.convert` parses the literal. Checked 2026-11-02 against sakila's `MpaaRating` on jOOQ 3.20.11: `convert("G")` gives `G`, `convert("NC-17")` gives `NC_17`, `convert("NC_17")` and `convert("PG_13")` give `null`. So RC46 paged from a cursor on an enum whose constant name equals its literal and rejected one on `NC-17` or `PG-13` as not coercible. Reproducing RC46's read reproduces both.
* **Multi-table connections** (an interface or union connection over several tables, `MultiTablePolymorphicEmitter`) call the same `ConnectionHelper.encodeCursor` and `decodeCursor`, over the participant key fields followed by the synthetic `__typename` tiebreaker. The emitter's diff from `e99388f85` to trunk is the JavaPoet import swap and a single javadoc line, so their order columns are unchanged, and a release-candidate cursor from one reads like any other.
* **Order columns, RC46 to trunk.** Unchanged for every connection that compiled under RC46. R1106 (a single-table connection's order gets the row key as tiebreaker, `8e9abb051`) is an ancestor of `e99388f85`, so RC46's orders already carry it. Of the order-shaping commits since, R1122 (`c8197d1cf`) extended the same completion to plain lists without touching connections, and R1083 (`2d9093056`) added `path:` and `method:` sort keys, an order RC46 could not express, so no RC46 cursor exists for one. A release candidate before R1106 wrote a shorter cursor on a connection whose order did not already identify a row; that cursor stopped working at the release-candidate upgrade that added the tiebreaker, falls outside "the same columns", and is not this item's to restore.

## Telling the formats apart

The decoder decides the format from the cursor string before reading it, in this order:

1. **The alphabet, when it speaks.** The standard encoder can emit `+`, `/` and `=`; the URL-safe unpadded encoder trunk and Graphitron 9 use can emit `-` and `_` and never `=`. A cursor holding any of `+ / =` is a release-candidate cursor, decoded with `Base64.getDecoder()`; one holding `-` or `_` is a JSON cursor, decoded with `Base64.getUrlDecoder()`; one holding both is malformed. The other 62 characters decode identically under both decoders, and both accept a missing pad.
2. **The decoded text, when the alphabet is silent.** A JSON cursor opens with `[` and never holds a raw U+0000: the writer escapes control characters and the reader rejects them unescaped. A release-candidate cursor over two or more columns always holds U+0000. So text that holds U+0000 or does not open with `[` is a release-candidate cursor.
3. **Text that looks like JSON, under a silent alphabet,** is read as JSON first; if that reading fails (a malformed array, the wrong arity, an element that is no value of its column), it is read as a release-candidate cursor, and only if that also fails is it the client error.

What no rule settles is a release-candidate cursor whose bytes are a valid JSON cursor for the same connection: a single-column order whose string value is itself the text of a one-element JSON array (`["xy"]`), with a byte length that is a multiple of three and Base64 that happens to hold no `+` or `/`. Those bytes are byte-identical to the JSON cursor for the row whose value is `xy`, and the JSON reading wins, because every cursor trunk issues has to read right. The residue needs a connection whose whole order, row key included, is one string column holding JSON-array-shaped values; it is accepted, and named in the `decodeCursor` emission comment rather than the manual.

## Implementation

* **`ConnectionHelperClassGenerator`, `decodeCursor` emission.** The try body becomes the format choice above, delegating to two new private static emitted helpers that each return `Field<?>[]` and throw only `IllegalArgumentException` or `DataTypeException` on malformed input, so the existing multi-catch and its one `cursor is not valid (was: "…")` client error stay as they are. The `cursor == null` early return is unchanged.
  * `readJsonCursor(String text, List<Field<?>> orderByColumns)`: today's body after the Base64 step, moved verbatim (`readJsonArray`, strict arity, `null` as a typed `NULL`, `ColumnValues.fromDatabase`, a `null` result rejected as not coercible).
  * `readReleaseCandidateCursor(String text, List<Field<?>> orderByColumns)`: RC46's decode after the Base64 step, reproduced as listed under "What the release candidates wrote", with each token coerced on the column's converted type rather than through `ColumnValues`, since the release candidates wrote the user value's text. The coercion is `DSL.val((Object) token, col.getDataType()).getValue()`, the facet path's non-deprecated equivalent of RC46's `convert`, so the compatibility path does not put a call jOOQ will remove into every consumer's generated `ConnectionHelper`. If the converter-backed fixture column shows the two disagreeing, the implementer records the difference here rather than reaching for `convert`.
  * The fallback in step 3 catches the JSON reading's `IllegalArgumentException | DataTypeException` only on the alphabet-silent path; a cursor the alphabet already routed to one reading gets no second one.
* **`ConnectionHelperClassGenerator` comments.** The class javadoc gains a paragraph naming the second read format, why it is read (cursors the Graphitron 10 release candidates issued, so a stored one survives the upgrade) and how the decoder tells them apart; it keeps "a cursor that wrote every value as a string still reads", which stays true of the JSON reading. The comment above the `decodeCursor` emission drops "and earlier releases wrote with every value quoted", which no release did (only trunk snapshots between R1088 and R1142 wrote it), describes the three-step choice, and names the accepted residue. No comment cites the item (`RoadmapReferenceGuardTest`). `encodeCursor` is unchanged.
* **Manual, `docs/manual/how-to/connections.adoc` § Cursor encoding** (the first-client draft). The sentence "Cursors earlier Graphitron 10 releases issued, which wrote every value as a string, are still read." is replaced by:

  > A cursor a Graphitron 10 release candidate issued is still read, so a client that stored one keeps its place after the upgrade while the connection pages on the same columns. The release candidates wrote their own format; graphitron reads it and writes only the format above, so the next cursor the client receives is in the new form. A release-candidate cursor its own release could not page from either is still rejected: one on an enum order column whose value differs from the enum constant's name, such as `NC-17`, and one on an order column with a converter whose value does not read back from its text.
* **Architecture, `docs/architecture/reference/emitter-conventions.adoc` § Cursor encode/decode.** The "Decode a seek value" bullet gains the second reading: a release-candidate cursor's token is coerced on the converted type with `DSL.val(token, field.getDataType()).getValue()`, the one place the decoder deliberately reads through the converted type the bullet otherwise warns against, because the token is the user value's text.

## Tests

Both tiers build release-candidate cursors with one helper, `releaseCandidateCursor(Object... values)`, that does exactly what RC46's `encodeCursor` did: each value's `toString()`, U+0001 for `null`, joined by U+0000, `Base64.getEncoder()`. Where a case depends on the cursor's alphabet, the test asserts the alphabet as a precondition. The split follows `docs/architecture/how-to/testing.adoc`: the format choice and the coercion are a pure function from a string to `Field<?>[]` and sit at the unit tier; that a stored cursor keeps its place is row content, and sits at the execution tier.

**Unit tier, `CursorEncodingTest`** (`graphitron-sakila-example`, `@UnitTier`, already calls `ConnectionHelper.decodeCursor` directly), a new "A release-candidate cursor" section:

* **Alphabet-silent, single column.** `decodeCursor("MTAw", List.of(FILM.FILM_ID))`, the release-candidate cursor for `100`, binds `100`; `AQAy` (`NULL`, U+0000, `2`) over `validity_period`'s `(valid_to, period_id)` binds a typed `NULL` and `2`.
* **The JSON reading wins the residue; the fallback covers the rest.** `decodeCursor("WyJ4eSJd", List.of(FILM.TITLE))`, the release-candidate cursor for the text `["xy"]` and the JSON cursor for `xy` at once, binds `xy`. `decodeCursor("W2FiY2Rd", List.of(FILM.TITLE))`, the release-candidate cursor for `[abcd]`, fails the JSON reading and binds `[abcd]`.
* **`+` or `/` in the Base64.** A release-candidate cursor over `(TITLE, FILM_ID)` whose title the implementer picks so its Base64 holds `+` or `/` binds the title and id.
* **Tokens per column type bind as RC46 bound them:** an `integer`, a `numeric`, a `date` and a `timestamp` written as their Java value's `toString()`; an enum whose constant name is its literal (`G`) binds, `NC_17` does not.
* **Malformed input raises the client exception, never another throw:** both alphabets in one cursor (`Mg-+`); bad standard Base64 (`Mg=x`); the wrong arity (`MQAy`, two tokens for one column); and one token that is no value of its column for each non-string type above (`abc` for `integer` and `numeric`, `bogus` for `date` and `timestamp`, `NC_17` for the enum). Each throws the generated `GraphitronClientException` with `cursor is not valid (was: "<cursor>")`, which pins the blame contract for the new reader's coercion, not only for its Base64 and arity.

**Execution tier, `CursorFormatExecutionTest`**, a new "A release-candidate cursor keeps its place" section:

* **One numeric column, forward.** `releaseCandidateCursor(2)` is `Mg==`; `filmsConnection(first: 2, after:)` from it gives the same titles as the JSON cursor `WzJd`, and the page's `endCursor` is the JSON `WzRd`, so the writer is unchanged.
* **Two columns.** `releaseCandidateCursor("ACE GOLDFINGER", 2)` on `filmsOrderedConnection(order: [{field: TITLE}, {field: FILM_ID}])` gives the same seeded rows after it as the Graphitron 9 cursor test does.
* **Backward.** `releaseCandidateCursor(4)` (`NA==`) as `before` with `last: 2` gives `ACE GOLDFINGER`, `ADAPTATION HOLES`.
* **`NULL` and date tokens.** On `validityPeriodsByValidTo`, `releaseCandidateCursor(null, 2)` pages to periods 5 and 7 like the JSON `[null,2]`, and the release-candidate cursor on the `2026-03-01` row with id 4 pages like its JSON counterpart.
* **Enum column.** On `filmsOrderedConnection(order: [{field: RATING}])`, a release-candidate cursor on a seeded film rated `G`, `PG` or `R` pages backward to the same seeded films as the JSON cursor for that row.
* **Multi-table connection.** On a multi-table connection the sakila example already pages (`displayNamedConnection` in `MultiTableOrderingExecutionTest` is one), a release-candidate cursor built from the elements of the first page's JSON `endCursor` gives the same second page as that `endCursor`.
* **The client error reaches the client.** One malformed release-candidate cursor (`YWJj`, `abc` on `film_id`) on `filmsConnection` gives exactly `cursor is not valid (was: "YWJj")` and no correlation-id 500.
* **Existing test.** `allStringCursor_earlierReleasesIssued_pagesLikeTheBareOne` is renamed `allStringCursor_pagesLikeTheBareOne` and its comment loses the claim that earlier releases wrote that form; what it pins, that the JSON reading takes a string element for a numeric column, is unchanged.

## Other solutions we've considered

* **Leave the break and document it.** The manual would add release-candidate cursors to the list a client drops. Rejected because the release candidates are in production use at Sikt and the cost of reading their format is one helper method; the decision would rest on not yet knowing whether any client stores cursors across a release, which is exactly what we cannot cheaply find out.
* **Read the release-candidate token through `ColumnValues` instead of the converted type's coercion.** That would page from more release-candidate cursors than RC46 did (an `NC-17` enum, some converter-backed columns), but it reads a user-side token with a database-side parser, so on a converter whose user and database text differ it can seek on a different value than the client was shown, silently. Reproducing RC46 keeps the rule simple: a release-candidate cursor behaves after the upgrade as it did before, and the client error a broken one raises is the one its client already handles.
* **Prefer the release-candidate reading in the ambiguous case.** Would misread the JSON cursor for any single-column string order whose value is JSON-array-shaped text, a cursor trunk issues today, to rescue one a release candidate issued.

## Provenance

Raised 2026-11-02 by a developer on a team already running a release candidate, replying to R1142: the cursor change is backwards-incompatible for teams already on Graphitron 10. The break they named is R1088's, which replaced the release candidates' format on both sides; R1142 changed only the writer and adds no break of its own to a published release. The release-candidate format and the RC46 base were checked the same day against the published jars and git history; the enum, multi-table and order-column facts above were checked against `e99388f85`, trunk and jOOQ 3.20.11 while drafting the Spec.

## Reviewer findings

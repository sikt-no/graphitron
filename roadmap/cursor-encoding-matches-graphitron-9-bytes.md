---
id: R1142
title: "A newly issued cursor is byte-identical to the one Graphitron 9 issued for the same row"
status: In Review
bucket: enhancement
priority: 3
theme: pagination
depends-on: []
created: 2026-11-02
last-updated: 2026-11-02
---

# A newly issued cursor is byte-identical to the one Graphitron 9 issued for the same row

## Goal

On a single-table connection that pages on the same columns in both versions, the cursor Graphitron 10 writes for a row is the exact string Graphitron 9 wrote for it on Oracle. Cursors already stored, from Graphitron 9 or from earlier Graphitron 10 releases, keep paging as they do today.

A cursor is the opaque string a connection hands out per row (`edges.cursor`, `pageInfo.endCursor`) and takes back as `after` or `before`; Graphitron writes it as a Base64-encoded JSON array of the row's values in the order's columns. Graphitron 9 had the database render that array with Oracle's `JSON_ARRAY`, so a numeric column came out as a bare JSON number, a boolean as `true` or `false`, a date as an ISO date-time, everything else as a JSON string, with no whitespace. Graphitron 10 writes the same text. A consumer that pins cursors in approval tests keeps its Graphitron 9 values: sis, the Graphitron 10 port of Sikt's student information system, has 50 such cursors in 8 approval files. A release rolled back to Graphitron 9 reads Graphitron 10's cursors without relying on type coercion. The same row of sis's `emneBeskrivelsesavsnittPaginert`, where `INSTITUSJONSNR_EIER`, the second key column and `ARSTALL` are `NUMBER`:

```
Graphitron 9 (Oracle):        [1234,185,"MUS101","1","MÅL","ENGELSK","VÅR",2017]
Graphitron 10 today:          ["1234","185","MUS101","1","MÅL","ENGELSK","VÅR","2017"]
Graphitron 10 after R1142:    [1234,185,"MUS101","1","MÅL","ENGELSK","VÅR",2017]
```

A date order column, from sis's `programStudieretter(orderBy: {orderByField: FRA_DATO})`, writes `[1234,"2003-08-15T00:00:00"]` in both versions.

There is one format on every dialect, Oracle's, because Graphitron 10 writes a cursor in Java from the fetched row rather than asking the database to render it. Three kinds of Graphitron 9 cursor are therefore not reproduced byte for byte, and the manual names them:

* **A row with a `NULL` order value.** Graphitron 9 on Oracle dropped the element, so its cursor was one element short and Graphitron 9 itself could not page from it. Graphitron 10 writes `null`.
* **A nested connection on Oracle whose cursor holds a decimal number, a `/`, or certain non-ASCII characters.** Graphitron 9 selected those cursors inside a `multiset`, and jOOQ parsed and re-serialized the JSON on the way out, writing `0.0000001` as `1.0E-7` and `/` as `\/`. Graphitron 10 writes the root-connection form for every connection.
* **Graphitron 9 on PostgreSQL.** Its `json_build_array` wrote `", "` between elements, kept a number's trailing zeros (`100.00`), and wrote a `date` without a time part.

## Graphitron 9's Oracle rendering, measured

`graphitron-common` 9.4.4 `QueryHelper.getOrderByToken` builds the token in SQL with `jsonArray(table.fields(orderFields)).convertFrom(QueryHelper::encodeToken)`, so a Graphitron 9 cursor is the database's `JSON_ARRAY` text, Base64-encoded. On Oracle that text follows fixed rules, measured 2026-11-02 on Oracle AI Database 26ai Free (23.26.1), with plain `json_array(...)` and with Graphitron 9's own expression executed through jOOQ Pro 3.19.18 under `SQLDialect.ORACLE18C`, the dialect sis's main runs. sis's own measurement on its FSLOKAL database (also 2026-11-02) agrees for `DATE`, `TIMESTAMP` and `NUMBER`. Oracle 19c was not measured.

* **What jOOQ sent.** Every Oracle dialect from `ORACLE` to `ORACLE23C` renders `json_array("T"."A", "T"."B", ...)` with no `ON NULL` clause, no `RETURNING` clause, and no per-element wrapping, except that a `BOOLEAN` column becomes a `case` expression with `format json`. Root connections selected the token as a top-level column; nested connections selected it inside `multiset(...)`.
* **Separators.** `,` with no whitespace. Output does not depend on the session's NLS settings.
* **`NULL`.** The element is dropped (Oracle's default is `ABSENT ON NULL`): `json_array(cast(null as number), 1, 'a')` gives `[1,"a"]`. An empty string is `NULL` in Oracle and is dropped too.
* **`NUMBER`.** Canonical form, trailing fractional zeros dropped: `1.50` gives `1.5`, `100` in a `NUMBER(10,2)` gives `100`, `-0` gives `0`, `0.0000001` gives `0.0000001`, `123456789012345678` stays exact. Oracle switches to scientific notation at extreme magnitudes (`1.5E+40`, `1E-129`). `BINARY_DOUBLE` 0.1 gives `0.1`; NaN and infinity give the strings `"Nan"` and `"Inf"`.
* **`BOOLEAN`.** `true`, `false`.
* **`DATE`.** `"2003-08-15T00:00:00"`, or with its time part, `"2003-08-15T13:14:15"`. sis's jOOQ model maps an Oracle `DATE` to `LocalDate`, which holds no time part, so its cursors always read `T00:00:00`; a consumer that maps `DATE` to `java.sql.Date` keeps the time in the value's milliseconds.
* **`TIMESTAMP`.** A zero fraction is omitted (`"2003-08-15T13:14:15"`, for `TIMESTAMP(0)` and `TIMESTAMP(6)` alike). A non-zero fraction is written to six digits, or nine when the value needs them: `.12` in a `TIMESTAMP(3)` gives `"2003-08-15T13:14:15.120000"`, `.123456789` gives `.123456789`.
* **`TIMESTAMP WITH TIME ZONE`.** The fraction is always written, with the same six-or-nine rule (`.000000`), then the value's offset: `Z` at UTC, otherwise `+01:00`; a named region is written as its offset.
* **Strings.** `\"` and `\\`; the short escapes `\b`, `\f`, `\n`, `\r`, `\t`; every other control character and U+007F as `\u00XX` with uppercase hex (`\u001F`, `\u007F`). `/`, non-ASCII and supplementary characters are raw UTF-8.
* **`INTERVAL`.** ISO-8601 durations, `"P1D"`, `"P1Y2M"`.

What the cursors sis actually holds look like, from its Graphitron 9 vs 10 differential run on the same Oracle (1857 distinct Graphitron 9 cursors) and its approval files: every one compact, `NUMBER` columns bare, `VARCHAR2` columns quoted even when they look numeric (`"06"`, `"1"`), 25 with a date element, all `T00:00:00`. None holds a decimal, a boolean or a `NULL` element.

## What exists today

`ConnectionHelperClassGenerator` emits `ConnectionHelper.encodeCursor(Record, List<Field<?>>)`, which writes `null`, or `writeJsonString(json, value.toString())` of each column's database value (`databaseValue`: the record's value through the column's `Converter.to`, an `EnumType` as its literal). It is called from `ConnectionHelper.edges` and `ConnectionHelper.pageInfo`, which every connection producer reaches (root, split and multi-table alike), so it is the single cursor writer. `writeJsonString` short-escapes `"`, `\`, `\n` and `\t` and writes every other character below U+0020 as lowercase `\u%04x`. `decodeCursor` reads any JSON scalar through `readJsonArray`, keeping a number's literal text, and binds through `ColumnValues.fromDatabase(col.getDataType(), element)`, so the read side already accepts bare numbers. `CursorFormatExecutionTest` pins the current wire shape, for example `filmsConnection`'s `endCursor` `WyI0Il0` (`["4"]`).

The read side also accepts every form this item writes. Checked 2026-11-02 against jOOQ 3.20.11's `DataType.convert`, which `ColumnValues.fromDatabase` calls: `"2003-08-15T00:00:00"` converts to a `LOCALDATE` and a `DATE`, and `"2003-08-15T13:14:15.000000Z"` and `"...+01:00"` convert to a `TIMESTAMPWITHTIMEZONE`. One gap predates this item and is not closed by it: an element with a time part read into a `java.sql.Date` column comes back at midnight, so a consumer whose `DATE` key maps to `java.sql.Date` and holds times cannot page by it today either (`ColumnValuesTest.DATE_WITH_TIME` pins the parse). Today's writer drops the time on encode as well; after this item the cursor carries it, as Graphitron 9's did, and the read side stays as it is. The implementer files a Backlog item for that read if none covers it; none did, and it is filed as R1143.

## Implementation

Shipped at `9022a2c85`, with `a24e76b05` correcting the `decodeCursor` emission comment. The plan below is what landed, with two departures: `ColumnValues.Text` is emitted through JavaPoet's `recordBuilder` (the 0.22.0 fork has it, though an older comment in `ConnectionHelperClassGenerator` says otherwise), and in the manual's paragraph the `NULL` sentence reads "A cursor for a row with a `NULL` order column does not read back" and the following one "Two kinds of connection are an exception as well", so the paragraph does not name two different "one exception"s. The temporal coercions answer `null` on a jOOQ `DataTypeException` or `IllegalArgumentException` as well as on a `null` result, falling back to `toString()`, so the writer never throws.

The element text moves into `ColumnValues`, beside the `fromDatabase` it inverts; `ConnectionHelper` keeps only the JSON framing. The decode path is untouched.

* **`ColumnValues.toText(DataType<?> type, Object databaseValue)`** (new, `ColumnValuesClassGenerator`) returns the database value's cursor text and whether it is a JSON literal, as a small emitted record `ColumnValues.Text(String text, boolean literal)`. It branches on `type.getSQLDataType()` with jOOQ's `DataType` predicates, and inside each branch it coerces the value to one canonical class through jOOQ, `DSL.val(value, SQLDataType.X).getValue()`, the coercion the generated code already uses, so neither the branch nor the conversion depends on the value's Java class. In this order:
  * `isBoolean()`: coerced to `BOOLEAN`, `true` or `false`, literal.
  * `isNumeric()`: coerced to `NUMERIC`, then `stripTrailingZeros().toPlainString()` (zero writes `0`), literal. jOOQ's coercion is exact for integral types, `BigInteger` and `ULong`, and gives a finite `Float` or `Double` its shortest decimal text (`0.1`). It answers `null` for NaN and infinity, which then write as today, a JSON string of `toString()`, since JSON has no NaN.
  * `isTimestampWithTimeZone()` (checked before `isTimestamp()`): coerced to `OFFSETDATETIME`, except that a column whose SQL type is `INSTANT` coerces to `INSTANT` and is placed at UTC (jOOQ's `Instant`-to-`OffsetDateTime` coercion uses the JVM's zone). Written `yyyy-MM-dd'T'HH:mm:ss`, the fraction always, to six digits or nine, then `Z` for a zero offset, otherwise `+HH:MM`.
  * `isTimestamp()`: coerced to `LOCALDATETIME`, written `yyyy-MM-dd'T'HH:mm:ss`, with the fraction only when it is non-zero, to six digits or nine.
  * `isDate()`: coerced to `LOCALDATETIME`, written `yyyy-MM-dd'T'HH:mm:ss` with no fraction. A `LocalDate` coerces to midnight and a `java.sql.Date` keeps the time in its milliseconds (both checked 2026-11-02), which is what Oracle wrote for each.
  * Anything else (strings, enum literals, `TIME`, intervals, UUIDs): `toString()`, not literal, as today.

  A coercion that answers `null` in a temporal branch also falls back to `toString()`, not literal. The six-or-nine fraction rule is one emitted helper used by both timestamp branches. The emitted code stays Java 17: records and `instanceof` patterns, no pattern `switch`.
* **`ConnectionHelper.encodeCursor`** writes `null`, or a literal `Text` bare, or `writeJsonString` of a non-literal one, for each column's `databaseValue`. Sort-key columns (`path:` and `method:` orders) and the multi-table `__pkN__` and `__typename` fields go through it by their own `DataType`s.
* **`writeJsonString` uses Oracle's escape set.** Short escapes for `\b`, `\f`, `\n`, `\r`, `\t`, plus `\"` and `\\`; every other character below U+0020, and U+007F, as `\u%04X` with uppercase hex; everything else raw. Today's lowercase hex and unescaped U+007F were valid JSON that decodes the same; the change is for byte identity only.
* **`encodeCursor` becomes `public`**, as `decodeCursor` already is, so the unit test below calls the generated writer directly. It widens the generated API by one method on the same footing as its inverse.
* **Comments.** The class javadoc and the comment above the `encodeCursor` emission, which state the all-string rule and why, restate the typed rule. They do not cite the item (`RoadmapReferenceGuardTest`); naming Oracle's `JSON_ARRAY` rendering as the format is enough.

## User documentation (first-client check)

The cursor-format paragraphs of `docs/manual/how-to/connections.adoc` § Cursor encoding is part of the public contract become:

> A cursor names one row's position in the active order: the values of the order's columns for that row, written as a JSON array with one element per column in order and no whitespace, then encoded as URL-safe Base64 without padding. Each element is the column's database value, so a column with a jOOQ converter contributes its database type's value, not the converted one. The column's SQL type decides how it is written:
>
> * SQL `NULL`: `null`.
> * A numeric column: a bare JSON number without trailing fractional zeros, `2.5` rather than `2.50`. A floating-point NaN or infinity, which JSON cannot hold, is written as a string.
> * A boolean column: `true` or `false`.
> * A date column: a date-time, `"2017-08-15T00:00:00"`, with the time of day when the column's Java type carries one.
> * A timestamp column: `"2017-08-15T13:45:10"`. A non-zero fraction of a second is written to six digits, or nine when the value needs them, `"2017-08-15T13:45:10.120000"`. With a time zone, the fraction is always written and the offset follows it, `Z` for UTC: `"2017-08-15T13:45:10.000000+01:00"`.
> * Any other column: a JSON string of its value.
>
> Clients should still treat a cursor as opaque. The format is stated here because it is a compatibility promise, not an interface to build on.
>
> Graphitron 9 issued the same bytes on Oracle, so a cursor pinned in a Graphitron 9 test keeps its value, and a cursor a client stored under Graphitron 9 keeps working while the connection pages on the same columns. Some Graphitron 9 cursors still read back but are not the bytes Graphitron 10 writes for the same row: those issued on PostgreSQL, which wrote a space after each comma, kept a number's trailing zeros and wrote a date without a time; and those of a nested connection holding a decimal number. A row with a `NULL` order column is the one exception on the read side: Graphitron 9 on Oracle left the element out, a cursor Graphitron 9 could not page from either, and Graphitron 10 rejects it and writes `null` in its place. Cursors earlier Graphitron 10 releases issued, which wrote every value as a string, are still read. Two kinds of connection are the exception, and a client drops a cursor it stored from one and restarts from the first page:

The two existing exception bullets (key-appended order, multi-table interface or union) follow unchanged. The other pages:

* `docs/manual/how-to/migrating-from-legacy.adoc`, the sentence "The cursor wire format is the one Graphitron 9 issued": "the one Graphitron 9 issued on Oracle", with the `NULL` and PostgreSQL caveats by xref to `connections.adoc#cursor-encoding` rather than restated.
* `docs/manual/how-to/sort-results.adoc` only points at `connections.adoc#cursor-encoding`; it changes only if the implementer finds a sentence that restates the element form.
* `docs/architecture/reference/emitter-conventions.adoc` § Cursor encode/decode, the *Encode a seek value* bullet: "written as a JSON string" becomes "written by the column's SQL `DataType`: numeric bare, boolean bare, temporal in Oracle's `JSON_ARRAY` form, everything else a JSON string".
* `docs/architecture/principles/development-principles.adoc` § Model metadata over parallel type systems keeps the cursor as its exemplar; the element form now follows the `DataType` in both directions, which strengthens the example, so no edit is planned.
* `roadmap/changelog.md` gets the Done entry: newly issued cursors match Graphitron 9 on Oracle byte for byte.

## Tests

* **`CursorEncodingTest`** (new, `graphitron-sakila-example` `test/internal`, unit tier, no database), beside `ColumnValuesTest`. It calls `ConnectionHelper.encodeCursor` on detached records over typed `DSL.field(name, SQLDataType.X)` columns and compares the decoded JSON with literals from the Oracle measurements above. The format depends only on the column and the value, never on the dialect, so these literals pin it on every database, Oracle included, without an Oracle database in the build:
  * sis's row, two `NUMERIC` columns, five `VARCHAR` and a `NUMERIC`, reproducing `[1234,185,"MUS101","1","MÅL","ENGELSK","VÅR",2017]`, and the Base64 of it equal to main's approved cursor string. (Implemented as the URL-safe unpadded Base64 of that measured text, the encoding Graphitron 9's `encodeToken` uses, confirmed from its bytecode; sis's approval file itself was not at hand to compare against.)
  * numbers: `1.50` gives `1.5`, `100.00` gives `100`, `-0` gives `0`, `0.0000001`, `Long.MAX_VALUE`, a `ULong` above it, a `DOUBLE` 0.1, and a `DOUBLE` NaN as the string `"NaN"` (written as today; whether it reads back is unchanged by this item and not asserted).
  * `BOOLEAN` `true`/`false`; `LOCALDATE` gives `"2003-08-15T00:00:00"`; a `DATE` (`java.sql.Date`) holding 13:14:15 gives `"2003-08-15T13:14:15"`; `LOCALDATETIME` with zero, `.12` and `.123456789` fractions; `OFFSETDATETIME` at UTC and at `+01:00`; `INSTANT` at UTC under a non-UTC default time zone.
  * escapes: `"`, `\`, `\b\f\n\r\t`, U+001F and U+007F (uppercase hex), `/`, `æ`, `€` and an emoji raw.
  * `null` elements and a converter column (database value, not user value).
  * every case except the `java.sql.Date` time (the read gap under What exists today) and NaN decodes back through `decodeCursor` to the value it was written from, numbers compared with `isEqualByComparingTo` since `100.00` comes back as `100`.
* **`CursorFormatExecutionTest`** (PostgreSQL, execution tier):
  * flip the pinned emitted cursors: `filmsConnection`'s `endCursor` `WzRd` (`[4]`); the enum cursor `["NC-17",3]`; the converter column's `[1,"2007-08-22T00:00:00.000000Z"]`; `endCursor_isAUrlSafeUnpaddedJsonArrayOfStrings` is renamed for what it now pins and asserts `["ACADEMY DINOSAUR",1]`.
  * new: a `validity_period` connection (`validityPeriodsByValidTo`) whose emitted cursors carry a date element in the `T00:00:00` form and a `null` element, and that pages forward from each.
  * new: `filmsByOriginalLanguageId`, a `path:` sort key over the numeric `language.language_id`, whose emitted cursor carries the key bare. A sort key is aliased under `ReservedAliases.sortKey` around the expression `SortKeyFragments.call` builds, so it is written by that expression's `DataType`; this pin shows the alias keeps the column's numeric type rather than reaching the writer as an untyped field.
  * new: an all-string cursor in the form Graphitron 10 issued before this item (`WyI0Il0`) still pages to the same rows as `WzRd`.
* Any other test pinning emitted cursor bytes flips with them. `NullSortKeyPagingExecutionTest`, `ConnectionTiebreakerExecutionTest`, `SortKeyOrderExecutionTest` and `MultiTableOrderingExecutionTest` round-trip and stay green unchanged.

The 50 cursors in sis's approvals reaching main's values again is the consumer-side acceptance, with no sis code change; it is sis's to report, not this item's gate.

## Retired vocabulary

* The manual and emitter-conventions rule "every other value is a JSON string" / "written as a JSON string" for the encode side.
* The test name `endCursor_isAUrlSafeUnpaddedJsonArrayOfStrings`.

## Other solutions we've considered

* **Render the cursor in SQL with `DSL.jsonArray(...)`, as Graphitron 9 did.** Byte identity per dialect would come by construction for root connections. It was rejected because Graphitron 9's own nested cursors were not the database's text either (jOOQ's multiset re-serialization, which also failed outright on a `NUMBER` above `Long.MAX_VALUE`); because it adds a projected column and a change to every connection emitter (root, split, multi-table), where today one helper writes every cursor; because Oracle's `ABSENT ON NULL` would need an explicit `nullOnNull()` anyway; and because the format would become whatever each dialect does, which the manual cannot state as one rule. The Java writer's format depends only on column and value, so one set of tests pins it everywhere.
* **Dispatch on the row's dialect (`record.configuration().dialect()`) and reproduce PostgreSQL's rendering as well.** Two formats to state and test, with the sakila fixture exercising only the PostgreSQL one, so the form sis asked for would rest on unit tests alone either way. No consumer has asked for PostgreSQL byte identity; it can be added the same way if one does.
* **Drop a `NULL` element on write, as Graphitron 9 on Oracle did.** That reproduces a cursor Graphitron 9 itself could not page from, and makes Graphitron 10's decode guess which position is missing.
* **Reproduce Oracle's scientific notation at extreme magnitudes and its `"Nan"`/`"Inf"` strings.** No key column holds such values; plain notation is valid JSON both versions read.

## Provenance

Requested 2026-11-02 by sis (the Graphitron 10 port, request C41, status-doc row C26, which asked for R1088 to be revived). It is filed as a successor rather than a revive because R1088's goal, reading a stored Graphitron 9 cursor, is delivered. Byte identity on write is a new goal, and R1088's design ruled it out on purpose. sis established the Oracle `NULL` behaviour and the `DATE`, `TIMESTAMP` and `NUMBER` renderings first; the rest of the measurements above come from the same day's run against a local copy of sis's Oracle and its Graphitron 9 differential-test archive.

---
id: R1143
title: "A cursor over a java.sql.Date order column pages from the time of day it carries"
status: In Progress
bucket: bug
priority: 4
theme: pagination
depends-on: []
created: 2026-11-02
last-updated: 2026-11-03
---

# A cursor over a java.sql.Date order column pages from the time of day it carries

## Goal

A connection that pages on a `DATE` column whose jOOQ type is `java.sql.Date`, and whose values hold a time of day (an Oracle `DATE` does), pages from the row the cursor names rather than from midnight of its day. A cursor writes such a value with its time, `"2003-08-15T13:14:15"`, as Graphitron 9 did on Oracle and as R1142 makes Graphitron 10 do. The read side drops it: `ColumnValues.fromDatabase` parses the element through `SQLDataType.DATE.convert`, which keeps the date and binds midnight, so the seek lands before every row of that day and the next page repeats rows. A consumer whose `DATE` maps to `LocalDate` (sis does) is unaffected, since its values hold no time.

Concretely, for an order on `registered` (an Oracle `DATE`, jOOQ type `java.sql.Date`) where three rows fall on 2003-08-15 at 09:00, 13:14:15 and 17:30, a client holding the cursor of the 13:14:15 row asks for `after:`. Today the seek binds `registered > DATE '2003-08-15 00:00:00'`, so the page opens with the 09:00 row and repeats the 13:14:15 one. After this item it binds 13:14:15 and the page opens with the 17:30 row. Nothing about the schema or the cursor bytes changes; `docs/manual/how-to/connections.adoc` § Cursor encoding already promises that a cursor names its row, and this item makes the read side keep that promise for this column type.

Terms used below. The *order columns* are the columns a connection sorts on, primary key appended, and a cursor carries one JSON element per order column. The *seek* is the `WHERE (cols) > (values)` condition the next page is cut by; `ConnectionHelper.decodeCursor` turns each element into the bound value for it. `ColumnValues` is the generator-emitted utility (`ColumnValuesClassGenerator`) that answers "is this value a value of that column, and which one"; `fromDatabase` is its entry point for a value written as the column's database value, which is what a cursor element is.

## What exists today

Measured 2026-11-03 against jOOQ 3.20.11, with the JVM zone at UTC, `Europe/Oslo` and `America/Sao_Paulo`:

* `SQLDataType.DATE.convert("2003-08-15T13:14:15")` gives a `java.sql.Date` at 00:00 of 2003-08-15 in the JVM zone. The time is dropped for every date-time form tried: `T`-separated, space-separated, with a fraction, with `Z` or an offset.
* `SQLDataType.LOCALDATETIME.convert` of the same text gives `2003-08-15T13:14:15`, and `DSL.val(thatLocalDateTime, SQLDataType.DATE).getValue()` gives a `java.sql.Date` whose milliseconds are 13:14:15 in the JVM zone. Coercing that `java.sql.Date` back to `LOCALDATETIME`, which is what `ColumnValues.toText`'s `isDate()` branch does on encode, gives `2003-08-15T13:14:15` again in all three zones. So jOOQ's own coercions are each other's inverse, and the round trip holds whatever the JVM zone, because both legs use the same zone.
* `LOCALDATETIME.convert` refuses a form `DATE.convert` accepts: one carrying `Z` or an offset (`"2003-08-15T13:14:15Z"`, `"...+01:00"`). Graphitron 10 never writes a `DATE` element that way, and neither did Graphitron 9 on Oracle; it matters only as acceptance this item must not narrow.
* jOOQ binds a `java.sql.Date` with `PreparedStatement.setDate` on every dialect except SQLite (`DefaultBinding.DefaultDateBinding.set0`), handing the driver the value's milliseconds, time included.
* `ConnectionHelper.decodeCursor` is the only caller of `fromDatabase`. The release-candidate cursor path binds its tokens on its own and is out of scope: those releases wrote a date without its time, so there is nothing to keep.
* `CursorEncodingTest.sqlDate_keepsItsTimeOfDay` pins the encode (`["2003-08-15T13:14:15"]`) and, alone among the date cases, does not read back; the class javadoc names it as the exception. `ColumnValuesTest.DATE_WITH_TIME` (a `java.sql.Date`-backed `LocalDateTime` user type) pins the same loss on the `exact` path, `exact(DATE_WITH_TIME, null, ...)` answering `null` because the database-type parse lands at 00:00; that is the node-id decode and stays as it is.

## Implementation

All in `ColumnValuesClassGenerator`, emitted Java 17 source.

* `fromDatabase`'s first step, `Object database = type.getSQLDataType().convert(value)`, becomes a call to a new private static `databaseValue(DataType<?> sql, Object value)`. For a column whose SQL type `isDate()` and whose Java type is `java.sql.Date` (the one date class that can hold a time of day), it parses the value as `SQLDataType.LOCALDATETIME` and coerces the result to the column's SQL type through the existing `coerced` helper. Where that parse gives `null`, and for every other column, it answers `sql.convert(value)` as today. The fallback is what keeps every form read today readable: an offset-bearing date still reads, at midnight, as it does now.
* The `fromDatabase` bullet of the class javadoc and the method's own javadoc say that a `java.sql.Date` column reads the element as a date-time so its time of day survives, the inverse of `toText`'s `isDate()` branch. The `decodeCursor` comment in `ConnectionHelperClassGenerator` already describes `fromDatabase` as the inverse of the encode; it gains nothing.

Restricting the branch to `java.sql.Date` rather than every `isDate()` column leaves the `LocalDate` path, the one sis runs, byte-for-byte as it is. Running a `LocalDate` column through the same branch gives the same values (checked: the coercion to `LOCALDATE` drops the time either way), so the restriction buys only that the change cannot touch a consumer it does not serve.

No user-manual change. `connections.adoc` already states the cursor carries the time "when the column's Java type carries one" and that a cursor names its row; it never documented the gap.

## Tests

Unit tier, in `graphitron-sakila-example`, where the generated `ColumnValues` and `ConnectionHelper` are compiled:

* `CursorEncodingTest.sqlDate_keepsItsTimeOfDay` gains the read-back: `readBack(SQLDataType.DATE, date)` equals `date`, compared on `getTime()`. The class javadoc drops `java.sql.Date` from its list of cases that do not read back, leaving NaN. A second case runs the same round trip with the JVM default zone set to `Europe/Oslo` and restored in `finally`, the way `instant_isWrittenAtUtc_whateverTheJvmZone` does, since the parse and the encode both lean on the default zone.
* `ColumnValuesTest` gains a `fromDatabase` case on `SQLDataType.DATE`: `"2003-08-15T13:14:15"` gives a `java.sql.Date` at 13:14:15; `"2003-08-15T00:00:00"` and `"2003-08-15"` give midnight; `"2003-08-15T13:14:15+01:00"` still reads, at midnight, pinning the fallback; `"garbage"` gives `null`.
* `ColumnValuesTest.exact_userTypeSlot_keepsTheTimeADateOnlyParseWouldDrop` is unchanged and must stay green: it pins that the `exact` path is not this item's.

No execution-tier test. PostgreSQL's `DATE` holds no time, so a seek over it cannot show the difference, and there is no Oracle in the build. The unit cases pin the value bound; what the database does with it is the premise below.

## Premise the build cannot check

The fix delivers the goal only if the consumer's Oracle driver keeps the time of a `java.sql.Date` both ways: `getDate` hands jOOQ the time in the milliseconds (the premise `ColumnValuesTest.DATE_WITH_TIME` and R1142's encode already rest on), and `setDate` binds it rather than truncating to the day. If `setDate` truncates, the seek still lands at midnight and the remedy is a bind-side cast, a different item. The consumer that maps `DATE` to `java.sql.Date` confirms this against its own approval suite, as sis confirmed R1142's bytes; the In Review reviewer checks that the hand-off names it.

## Other solutions we've considered

* **Parse every date element as `LOCALDATETIME`, no fallback.** Simpler, but it refuses offset-bearing date text that reads today, turning a cursor that pages (from midnight) into a client error. Narrowing acceptance is not this item's business.
* **Map through the column's converter user type, as `exact` does with its parse class.** `exact` takes a parse class because a node id carries the *user* value's text; a cursor element is the *database* value, so the column's database class, not the converter's user type, is what decides the parse. A converter is beside the point here: the bug shows on a bare `java.sql.Date` column.
* **Encode `java.sql.Date` without its time.** Would make encode and decode agree, but breaks R1142's byte-identity with Graphitron 9 on Oracle and still pages from midnight, which is the bug.

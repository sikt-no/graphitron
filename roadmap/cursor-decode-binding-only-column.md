---
id: R1162
title: "A connection cursor over a column with no SQL data type cannot be read back: fromDatabase throws NPE on page 2"
status: Ready
bucket: bug
priority: 2
theme: runtime-connection
depends-on: []
created: 2026-11-09
last-updated: 2026-11-10
---

# A connection cursor over a column with no SQL data type cannot be read back: fromDatabase throws NPE on page 2

## Goal

Paging forward works on a connection whose order includes a column jOOQ has no built-in SQL type for, such as a Postgres `tstzrange` typed through a custom `Binding`. Today the first page answers and every `after:` page throws a `NullPointerException`, reported as GitHub issue #576 against 10.0.0-RC47. Because every connection's order ends with the table's primary key as a tiebreaker, any table whose primary key holds such a column is affected, with or without an `orderBy` argument. When this lands, the cursor a page hands out reads back on the next request to the value it was minted from, so `after:` continues from the right row.

No schema changes: the SDL that fails today is the SDL that works afterwards. A `@table` type over a table whose key is a `tstzrange`, exposed through `@asConnection`, is the reported shape.

## What happens today

A connection cursor is a JSON array with one element per order column (the columns the page is sorted on, ending with the primary key). The emitted `ConnectionHelper.encodeCursor` writes each element as `ColumnValues.toText` of the column's *database value* (the record's value passed back through the column's converter), and `ConnectionHelper.readJsonCursor` reads it back with `ColumnValues.fromDatabase(column.getDataType(), element)`. Both are emitted by `ColumnValuesClassGenerator` and both key on the column's SQL data type, `DataType.getSQLDataType()`.

For a column jOOQ codegen types as unknown, the `DataType` is `DefaultDataType.getDefaultDataType("\"pg_catalog\".\"tstzrange\"")`, a `DataType<Object>`, and a forced binding or converter is applied on top with `asConvertedDataType`. Checked against jOOQ 3.20.11 in a scratch probe: `getSQLDataType()` is `null` on both the raw and the converted type, the converter's `fromType()` is `Object.class`, and the converted type's `isBoolean()`, `isNumeric()`, `isDate()` and `hasPrecision()` are all `false` whatever the user type is (a `Converter<Object, BigDecimal>` and a `Converter<Object, Boolean>` give the same answers). The `Object` database type is forced by the type system: `asConvertedDataType` on a `DataType<Object>` takes a `Binding<? super Object, U>`, so the converter's database side is `Object`.

- `toText` allows for the null: it falls back to the column's own `DataType`, whose flags are all false, so it writes the database value's `toString()` as a JSON string. jOOQ's own `jooq-postgres-extensions` converters (`AbstractRangeConverter`, `HstoreConverter`, `AbstractInetConverter`, `CitextConverter`, `LtreeConverter`) all declare `Object` as the database type, write a `String` from `to`, and read `t.toString()` in `from`. For the `tstzrange` column the element is therefore the text `[2024-01-01T00:00Z,2025-01-01T00:00Z)`.
- `fromDatabase` does not allow for it: it hands `getSQLDataType()` to the private `databaseValue`, whose first line is `sql.isDate()`, and throws. `decodeCursor` catches only `IllegalArgumentException` and `DataTypeException`, so the NPE escapes as a server fault.

The other entry points are unaffected. `exact`, `coerce` and `folded` run the column trip through `type.convert`, which on such a type hands the input to the converter and works; `toText` and `fromDatabase` are the only readers of `getSQLDataType()` in the class. `fitsPrecision` reads the column's own type, not the SQL type, and answers `true` for these columns because `isNumeric()` is false; the `java.sql.Date` branch lives inside `databaseValue`, which the new path below does not reach. The release-candidate cursor reader (`readReleaseCandidateCursor`) binds tokens through `DSL.val(token, type)`, the converted type, and already reads such a column.

## Implementation

**`ColumnValuesClassGenerator`, the emitted `fromDatabase`.** Branch on the column's SQL type before parsing:

- With an SQL type, unchanged: parse the element as the database type through `databaseValue`, then `converter.from` through `fromDatabaseValue`.
- Without one, there is no database-type parse to run. The element *is* the database value's text, and the converter is the only thing that knows how to read it, so hand the element to `fromDatabaseValue` as the database value directly. The `Trip` built for the precision check carries the element as its database value; `fitsPrecision` passes it, as shown above.

In the new branch the element reaches the converter unparsed, so check `converter.fromType().isInstance(element)` first and answer `null` when it is not: casting would throw `ClassCastException`, a fault of ours, not the converter's. In the codegen shape the database type is `Object` and the check always passes; it matters only for a hand-built `DataType` whose converter declares something narrower. Exceptions from `converter.from` itself keep the existing contract, which the new branch already sits inside: `IllegalArgumentException` and `DataTypeException` mean the element is no value of the column (`null`, so `readJsonCursor` throws `cursor token not coercible` and the client gets `cursor is not valid`), and any other exception is a fault in the consumer's converter and propagates. The comment above the emitted `decodeCursor` states that classification on purpose ("Any other unchecked throw (e.g. an NPE from a buggy custom Converter) is a genuine server fault and keeps propagating"), and this item does not revisit it.

**Out of scope: a forged cursor over a converter that throws something else.** jOOQ's range converter throws `ArrayIndexOutOfBoundsException` on text with no comma, so a forged or mangled element over such a column still fails as a server fault after this lands, rather than as `cursor is not valid`. That is not specific to columns with no SQL type: the release-candidate reader (`readReleaseCandidateCursor`), which `decodeCursor` also falls back to when a JSON reading is refused, hands the raw token to the converter through `DSL.val(token, type)` for every converter-backed column, and has the same exposure today. Settling where blame lies for a converter's other exceptions on client text is a question about both readers and the stated classification, so it belongs to its own Backlog item rather than to this fix.

**The emitted `toText`.** Make the no-SQL-type case explicit at the top: with no SQL type, return the value's `toString()` as a JSON string. This changes no output (the converted type's flags are all false, so the fallback through the type's own flags already lands there) and it removes branches that cannot fire. The point is that the two halves read as a pair: no SQL type writes the database value's text, and reads it back by handing that text to the converter.

**Javadoc and comments.** Restate the `fromDatabase` contract in three places so they describe the column with no SQL type: the `fromDatabase` bullet of `ColumnValuesClassGenerator`'s class javadoc (including the exception sentence in its opening paragraph), the emitted `fromDatabase` method javadoc, and the `ConnectionHelperClassGenerator` class javadoc plus the `readJsonCursor` comment, which say the element "parses as the column's database type and runs the converter".

No user-manual change: the fix restores the behaviour `@asConnection` already documents, and adds no surface.

## Tests

Both tests are unit tier in `graphitron-sakila-example`, against the emitted classes, with no database and no new dependency. They share a test-local column type typed the way codegen types an unknown column: `DefaultDataType.getDefaultDataType("\"pg_catalog\".\"tstzrange\"").asConvertedDataType(converter)`, where the converter is a `Converter<Object, Span>` over a test-local `record Span(String lower, String upper)` that reads and writes `[lower,upper)` text the way `AbstractRangeConverter` does (`from` parses `t.toString()`), throwing `IllegalArgumentException` on text with no comma so the refusal path is pinned under the existing contract. A plain converter rather than a full `Binding` is enough, since the probe shows both give the same `DataType` answers and the cursor code reads only the `DataType` and its converter. `Span`'s `toString()` is the record default, deliberately unlike its range text, so a design that ran the converter twice (see Other solutions) fails these tests.

- `CursorEncodingTest`, a new section for a column with no SQL type: `json(SPAN, v)` is `["[2024-01-01T00:00Z,2025-01-01T00:00Z)"]`, pinning the element as the database value's text; `readBack(SPAN, v)` is `v`, which is the issue's reproduction and throws the NPE today; a two-column order of an `int` and the span reads back to both values; and a cursor whose span element the converter refuses (`["garbage"]`, and a bare `[true]`) makes `decodeCursor` throw `GraphitronClientException`. The refusal case holds end to end only because the release-candidate fallback reading also gets the converter's `IllegalArgumentException`; the test pins that, so a later change to either reader that lets the forged case escape fails here.
- `ColumnValuesTest`, beside the existing `fromDatabase_*` cases: `fromDatabase(SPAN, "[a,b)")` is `new Span("a", "b")`, and `fromDatabase(SPAN, "garbage")` and `fromDatabase(SPAN, Boolean.TRUE)` are `null`.

No execution-tier test. Both halves of the cursor are column-driven and dialect-free, which is the premise `CursorEncodingTest` already rests on for every other column type, and the seek binds through the column's own `DataType`, so a binding's SQL rendering is jOOQ's concern and not this code's. An execution test would need a new Postgres fixture table with a range key, a converter or binding in `graphitron-fixtures-codegen` and a forced type in the codegen configuration, all to reach a path the unit tests already drive end to end on the cursor side.

The Done gate's evidence is `CursorEncodingTest`'s `readBack` case: it is the issue's failure, and it passes only when a cursor minted over the column reads back to its value.

## Other solutions we've considered

- **Parse with the column's own `DataType` when the SQL type is null**, as issue #576 suggests (`databaseValue(type, value)` in place of `databaseValue(type.getSQLDataType(), value)`). The converted type's `convert` runs `converter.from` during the parse, and `fromDatabase` then runs `converter.from` a second time on the finished user value. It round-trips for jOOQ's range converters only because their `from` reads `toString()` and a range's `toString()` happens to be its database text; it relies on a consumer's converter accepting its own user value as a database value, which nothing promises. The `Span` test type above makes that reliance fail visibly.
- **Catch every `RuntimeException` from the converter in the new branch**, so a forged element is always a client error. It contradicts the classification `decodeCursor` documents, would leave the release-candidate reader with the opposite answer for the same converter, and would turn a consumer converter that cannot read its own text into a misleading `cursor is not valid` with no stack trace. See the out-of-scope note under Implementation.
- **Refuse such an order at build time.** The generator could see from the jOOQ catalog that an order column has no SQL type and reject the connection. That turns a working first page into a build failure for a shape the cursor can carry, and the reported table has the column in its primary key, so the consumer would have no connection at all.
- **Write the user value's text instead**, as the release-candidate format did, and read it through the converted type. The release-candidate reader already does this, but it makes this column type the one exception to the invariant that a cursor element is the column's database value, and it reads back only when the converter's `from` can parse the user type's `toString()`. jOOQ's range types happen to qualify; `Span`, whose `toString()` is the record default, does not, and nothing promises a consumer's user type does.

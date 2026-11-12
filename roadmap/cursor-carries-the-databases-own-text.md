---
id: R1180
title: "A connection pages wrong, or not at all, on an order column whose value Java does not carry exactly"
status: Spec
bucket: bug
priority: 2
theme: runtime-connection
depends-on: []
created: 2026-11-12
last-updated: 2026-11-12
---

# A connection pages wrong, or not at all, on an order column whose value Java does not carry exactly

## Goal

A connection pages through every row exactly once, forward and backward, whatever the type of the columns it is ordered on. Today that holds only for a column whose value Java carries exactly. GitHub issue #578 reports the case that broke a consumer: a table keyed on a Postgres `tstzrange` whose current rows end in `infinity`, paged in descending order, returns the same row on every `after:` request and never ends. Measured against Postgres 16, the same fault reaches further than that one type: a `bytea` key skips rows without an error, an array or composite column makes every second page a client error, and `inet`, `cidr`, `macaddr`, multiranges and any other type jOOQ reads without a binding fail every `after:` page in the database. When this lands, the cursor a page hands out names the row it was taken from to the database itself, so the next page starts after that row for all of those types.

No schema changes. The reported shape, from fs-plattform's tilgangsstyring subgraph, is a connection whose order ends in a primary key that holds the range:

```graphql
type FeideApplikasjon @table(name: "feide_applikasjon") {
  brukerdelegeringer(orderBy: BrukerdelegeringerOrderByInput): [Brukerdelegering!]!
    @asConnection
}
```

The primary key of `brukerdelegering` includes `gyldig_periode tstzrange`, and graphitron appends the primary key to every connection's order, so the fault needs no `orderBy` argument naming the range.

## What happens today

A connection cursor is a JSON array with one element per order column: the columns the page is sorted on, ending with the primary key graphitron appends so the order identifies a row. Both halves of the cursor run in Java. The emitted `ConnectionHelper.encodeCursor` reads the column out of the fetched record and writes it with `ColumnValues.toText`; `ConnectionHelper.readJsonCursor` parses the element with `ColumnValues.fromDatabase` and binds the result into jOOQ's `.seek(...)`, which renders the keyset predicate. The next page is right only when the value that comes back out of that trip is, to the database, equal to the value the row holds.

For the issue's column it is not. The row holds `["2021-01-01 00:00:00+01",infinity)`. jOOQ's `OffsetDateTimeRange` has no way to say `infinity`, so `AbstractRangeConverter` reads the bound as absent and the cursor carries `[2021-01-01T00:00+01:00,)`. Postgres orders a range ending in `infinity` before one with no upper bound, so on a descending page the predicate `(…) < (cursor…)` is true for the cursor's own row. Ascending pages on the same data happen to come out right; with `-infinity` as a lower bound the ascending direction repeats the row instead.

The measurement behind the wider claim: a scratch database with one table per column type (55 types, each with edge values), jOOQ 3.20.11 codegen over it, and the emitted `ConnectionHelper` and `ColumnValues`. For every row, in both directions: write the cursor, read it back, run the seek, and compare with the rows that should follow. With codegen's default typing 25 of the 55 types page correctly; with the `jooq-postgres-extensions` bindings applied, 31. The failures fall in four groups:

- **The next page is silently wrong.** Java's reading loses part of the value. `tstzrange` and `tsrange` with an `infinity` bound, through the extension bindings (the issue). `bytea`: the element is the array's identity text, `[B@1c32386d`, read back as the bytes of those characters, and rows are skipped. `time` with fractional seconds: jOOQ reads the `LocalTime` without the fraction. `citext` read without its binding: the seek compares case-sensitively.
- **The cursor is refused on the way back**, a client error on every second page. Every array column (`[Ljava.lang.Integer;@4aa83f4f`) and every composite column (the element is jOOQ's rendering of the record, `cast(row(1, 'a') as "probe"."pair")`).
- **The seek fails in the database**, a server error on every `after:` page. Every type jOOQ reads as `Object` when no binding is configured: `inet`, `cidr`, `macaddr`, the range and multirange types, `ltree`, `tsvector`, `pg_lsn`. The element is bound as a string and Postgres answers `operator does not exist: inet > character varying`.
- **Page one itself fails inside jOOQ or pgjdbc**, before any cursor exists: `numeric` holding `NaN`, `timestamptz` holding `infinity`, `daterange` and `numrange` with an infinite bound through the extension bindings. The cursor is not at fault here, but the order column is read only for the cursor's sake when the client did not select it.

The same oracle with the database writing the element and reading it back (`cast(column as varchar)` selected beside the row, and the seek bound as `cast(? as <the column's type>)`) pages 52 of the 55 types correctly, all four groups included. The three left over are not cursor faults: Postgres has no `<` for `hstore`, and jOOQ codegen types `bit` and `varbit` as `CHAR` and `VARCHAR`, so the cast names the wrong type and the comparison fails as it does today. Every one of the 55 types satisfies `(v::text)::<type> IS NOT DISTINCT FROM v` in Postgres, which is why the database's own text is a value the cursor can carry.

## The rule

A cursor element is the database's value, and the party that writes it is the party that reads it back.

- **Java writes and reads a column when its reading of the value is exact.** Two sets, both closed. The forms the manual promises byte for byte, because Graphitron 9 issued them: numeric, boolean, date, timestamp and timestamp with time zone. And the types whose Java value is the database's value and whose text is the database's text: character, uuid and enum columns. Nothing changes for any of these, on the wire or in the SQL.
- **The database writes and reads every other column.** The page selects `cast(column as varchar)` beside the row and the element is that text; the seek binds `cast(? as <the column's type>)`. Java never holds the value, so nothing Java cannot say (an `infinity` bound, a fraction of a second, an array, a type with no Java class) is lost on the way.

The default is the database, so a type nobody listed is carried correctly rather than approximately. This is "The database is your ally" (`docs/graphitron-principles.adoc`) applied to the one place the cursor code did the database's job for it. The first set is fixed by "Existing schemas keep working" in the same file: a cursor a client stored keeps its meaning.

## Implementation

**One predicate, in the emitted `ColumnValues`** (`ColumnValuesClassGenerator`). A public static method answering whether a column's cursor element is written by the database: `false` when `type.getSQLDataType()` is non-null and that type is numeric, boolean, character, date, timestamp, timestamp with time zone, uuid or enum; `true` otherwise, which includes every column with no SQL type. Test `isDate()`, `isTimestamp()` and `isTimestampWithTimeZone()` rather than `isDateTime()`, which also answers for `time`. It is the only place the question is asked ("Decide once, at capture", `docs/architecture/principles/development-principles.adoc`): `pageRequest`, `encodeCursor` and `readJsonCursor` all read it, so the three cannot disagree about a column. It stays a runtime read of the column's `DataType` rather than a fact carried from generation time, which is what "Model metadata over parallel type systems" names the cursor as its example of, and it is what lets a `method:` sort key, an arbitrary jOOQ expression the generator never classified, be handled like a column.

With the predicate in place a column with no SQL type no longer reaches `toText` or `fromDatabase`. Remove the branches the previous fix added for it (the `sql == null` arms of both, and `fromDatabase`'s `fromType().isInstance` check) together with their javadoc, unless a caller other than the cursor turns up; `readJsonCursor` is the only caller of `fromDatabase` in the generator today.

**`ConnectionHelperClassGenerator`, the emitted `pageRequest`.** For the order column at position `i` of `extraFields` that the predicate gives to the database, add `DSL.cast(column, SQLDataType.VARCHAR).as("__cursor_<i>")` to `selectFields`, and do not add the column itself for the cursor's sake (it stays when the selection already holds it). The alias is positional because two order columns can share a name: a `path:` entry sorts on a related table's column. The alias text comes from one private method both `pageRequest` and `encodeCursor` call. `PageRequest` exposes the projections it added (an accessor returning the `Field<String>` list, empty when every column is Java's), so an emitter that builds its own select list reads them from the page rather than recomputing the predicate.

Not selecting the column is what lets a connection page on a column jOOQ cannot read at all, the fourth group above, as long as the client does not select it either.

**The emitted `encodeCursor`.** For a database-written column, the element is `record.get(<alias>, String.class)` as a JSON string through `writeJsonString`, or `null` when the text is `NULL`. A record that lacks the alias is an emitter fault and should fail loudly, not fall back to the Java reading. Java-written columns go through `databaseValue` and `ColumnValues.toText` as today.

**The emitted `readJsonCursor`.** For a database-written column, a `null` element stays a typed `NULL` (`DSL.val((Object) null, col.getDataType())`), a string element (which is also how `readJsonArray` hands over a JSON number) becomes `DSL.cast(DSL.val(text, SQLDataType.VARCHAR), col.getDataType())`, and a JSON `true` or `false` is refused as `cursor token not coercible`. Checked on jOOQ 3.20.11 against Postgres: the cast renders the type's own name for everything in the probe, `cast(? as "pg_catalog"."tstzrange")`, `cast(? as int[])`, `cast(? as bytea)`, `cast(? as "probe"."pair")`, `cast(? as time(6))`, including a type carrying a binding, and `.seek(...)` accepts the cast field beside ordinary bind values.

**`readReleaseCandidateCursor` is unchanged.** A release-candidate token is the user value's `toString()`, read on the column's converted type as those releases read it. A token they could not page from (`[B@…` for a `bytea`) stays a client error, as the manual says it is; sending it to the database would turn that into a server fault.

**The select lists.** Every `pageRequest(` call site has to select the projections. `RootLauncherRenderer` (two sites) and `BatchedRowsFragments.pageRequestLocals` build their select from `page.selectFields()`, so the projections ride along; in the batched path they pass through the `ranked` derived table and the outer `select()` by name, which the nested-connection test below pins. `MultiTablePolymorphicEmitter` (three sites: `buildStage1ConnectionBlock` and the two beside it) builds its outer select from the typed `key<I>` locals instead, and passes an empty selection; add the page's projections to that outer select from the new `PageRequest` accessor. The order columns there are fields of the `pages` derived table, so the cast is over the union's column and needs nothing in the branches.

**Wire bytes.** Unchanged for every Java-written column. For a database-written column the element was the Java value's `toString()` and becomes the database's text. Those are the same bytes wherever Java's text was the database's (the discrete ranges, `inet` and `ltree` through their bindings, `jsonb`), and differ for `interval`, `time with time zone` and the timestamp ranges. An outstanding JSON cursor in the old text is still read: Postgres parses `[2021-01-01T00:00+01:00,)`, `13:14:15.123456-08:00` and `+1-02 +3 04:05:06.789000000` through the cast (checked). An old element that never named a value (`[B@1c32386d`, `[Ljava.lang.Integer;@4aa83f4f`) pages as badly as it did, once, and the cursor on that page is a good one.

**Blame for a forged element narrows, on purpose.** An element Java reads is checked when the cursor is decoded, and one that is no value of its column is the client error `cursor is not valid`. An element the database reads is checked when the page query runs, so a forged or mangled one is refused there (SQLSTATE class 22) and surfaces as an unmatched exception: redacted, with a correlation id. That moves a failure below the boundary "Boundaries decode and encode; the interior is typed" wants it classified at, and it narrows what `docs/manual/how-to/error-channel.adoc` says about an invalid cursor, so it is stated here as a decision rather than left as residue. The reason to accept it: only the database can tell whether the text is a value of the type, and the two ways to ask it first are both worse (see "Other solutions"). A cursor is opaque, so the client this reaches is one that edited the bytes. Base64, JSON shape, arity and every Java-read element keep the client error.

**Javadoc and conventions.** Restate the contract where it is written down: the class javadoc of `ColumnValuesClassGenerator` and `ConnectionHelperClassGenerator`, the comments above the emitted `encodeCursor`, `databaseValue` and `readJsonCursor`, and `docs/architecture/reference/emitter-conventions.adoc`. In the last, "Cursor encode/decode" gains the database-written half beside its encode and decode bullets, and "Column value binding" gets one sentence saying the cursor's database-read element is the deliberate exception to "No SQL `CAST` is rendered", since that section is about binding GraphQL input.

## User documentation (first-client check)

In `docs/manual/how-to/connections.adoc`, "Cursor encoding is part of the public contract", the last bullet of the list is replaced by two, and a paragraph follows the list:

> * A character, uuid or enum column: a JSON string of its value, an enum as its database literal.
> * Any other column: a JSON string holding the database's own text for the value, what `cast(column as varchar)` returns. The database reads it back when the next page is fetched, so the cursor names exactly the value the row holds: a range ending in `infinity` stays distinct from one with no upper bound, and an array, a `bytea` or a network address pages like any other column.
>
> Graphitron checks a cursor when it decodes it, and answers a malformed one with `cursor is not valid`. The exception is the text of a column in the last group, which only the database can read: a cursor edited so that this text is no value of the column's type fails when the page is fetched, as an unmatched exception with a correlation id.

In `docs/manual/how-to/error-channel.adoc`, both places that list "an invalid cursor" among the client errors get the same qualification in half a sentence, pointing at the paragraph above.

The section's existing sentence about a column with a jOOQ converter ("contributes its database type's value, not the converted one") stays true and needs no edit: for a database-written column the converter is simply not involved.

## Tests

The previous cursor fix argued that no execution test was needed because the cursor is column-driven and free of the dialect. Issue #578 is the counterexample: the cursor read back to the Java value it was written from, and the page was still wrong, because only the database knows whether the value it is handed equals the one it stores. This item's evidence is therefore at the execution tier, on Postgres.

**Fixtures.** In `graphitron-sakila-db`'s `init.sql`, tables whose order columns cover the four groups, each with a handful of rows chosen so that neighbouring rows differ only in what Java loses:

- a table keyed on `(subject_id, valid_period tstzrange)` with periods ending in `infinity`, starting at `-infinity`, unbounded on each side, bounded, and `empty`;
- a table keyed on `bytea`;
- a table with an `integer[]` column and a `time(6)` column carrying fractions, both exposed as sort columns;
- a table with an `inet` column left to codegen's default typing.

The range column is read through a converter added to `graphitron-fixtures-codegen` and wired as a forced type in `graphitron-sakila-db/pom.xml`, beside `UtcLocalDateTimeConverter`. It reads the range text the way `jooq-postgres-extensions`' `AbstractRangeConverter` does, `infinity` becoming an absent bound, so the fixture reproduces the issue without adding that library to the build.

**The walk** (execution tier, `graphitron-sakila-example`, beside `NullSortKeyPagingExecutionTest`). For each fixture connection and each direction of its order: page with `first: 1` following `endCursor` until `hasNextPage` is false, then with `last: 1` following `startCursor`, and assert the ids seen are exactly the ids of the unpaged, ordered query, each once and in order. Connections to cover: a root connection on each fixture table; the range-keyed table as a nested connection under a list of parents, which is the batched path; and a multi-table connection with the range-keyed table as a participant. One case selects no field of the order column, and one selects it, so both select-list shapes run.

**The cursor's bytes** (`CursorEncodingTest`, unit tier). Every existing pin for a Java-written column passes untouched, which is the evidence that those bytes did not move; add `uuid` and an enum beside them. The "column with no SQL type" section is rewritten for the new half: the element is the record's projected text, and a JSON boolean for such a column is `cursor is not valid`. The release-candidate section passes untouched.

**The narrowed blame**, pinned so it cannot drift further: an execution test sends a cursor whose range element is `garbage` and asserts the response is the redacted, correlated error and not a page; and a cursor with bad Base64 or the wrong arity on the same connection is still `cursor is not valid`.

**`ColumnValuesTest`**: the predicate's answer for one type of each family on both sides, and the removal of the `fromDatabase` cases for a column with no SQL type.

The Done gate's evidence is the walk over the range-keyed table in descending order: it is the issue's failure, it loops today, and it passes only when the row holding `infinity` is left behind.

## Not covered

- **`infinity` in a plain `date`, `timestamp` or `timestamptz` column, and timestamps before the common era.** They are in the promised forms, whose bytes this item does not change. Filed as R1181 (`infinity-in-a-date-or-timestamp-order-column.md`).
- **An order column the database cannot compare the way the seek needs.** Postgres has no `<` for `hstore`; jOOQ codegen types `bit` and `varbit` as `CHAR` and `VARCHAR`, so both the bind today and the cast after this item name the wrong type. Both fail on the first `after:` page as they do now, and a forced type is the consumer's remedy for the second. Refusing such an order column at build time would be its own item.
- **Oracle.** The build has no Oracle and the open-source jOOQ has no Oracle dialect, so the database-written half is verified on Postgres only. On Oracle the Java-written set covers what a key is ordinarily made of (numbers, character data, dates, timestamps); the types that move are `RAW`, the interval types and `ROWID`, and `RAW` is today's `[B@…` element there as well. jOOQ renders both casts for the dialect; a consumer on Oracle with such an order column is the first real test.
- **A session whose `DateStyle` or `IntervalStyle` differs between the request that wrote a cursor and the one that reads it.** The database's text for a range of timestamps or an interval is read back under the reading session's settings. With the defaults, and with any setting that is the same across the pool, text written is text read.

## Other solutions we've considered

- **Fix the range type's reading of `infinity`.** A converter that keeps `infinity` apart from an absent bound would cure the reported column, and the issue says as much. It is the binding's to fix, not graphitron's, and it leaves every other group as it is: the fault is that the cursor trusts a Java reading at all, not that this one reading is lossy.
- **The database's text for every column.** Simplest rule, and on Postgres the probe passes it. It changes the bytes of the promised forms (`2017-08-15`, not `2017-08-15T00:00:00`; `1.50`, not `1.5`), which breaks cursors clients hold and the Graphitron 9 compatibility the manual states, and on Oracle the text of a `DATE` depends on the session's NLS settings.
- **The database's text only for the types found broken.** A list of broken types is open-ended: the next binding, extension type or dialect is wrong until someone adds it. Listing the exact types and defaulting to the database fails the other way, toward a redundant cast.
- **Decide per column at generation time and emit the cast statically.** The predicate reads only the column's `DataType`, which the emitted helper already holds; a classified fact would restate it, and would not exist for a `method:` sort key.
- **Send the element as an untyped parameter and let Postgres infer the type.** It would not need the type's name, so `bit` would work. It is a pgjdbc behaviour (`Types.OTHER`) with no jOOQ rendering and no Oracle counterpart, and it needs a custom binding in emitted code.
- **Keep the client error for a forged database-read element.** Either validate the element with a query of its own when the cursor is decoded, which costs a round trip per request and needs a connection `pageRequest` deliberately does not take, or classify a class 22 SQL error from a page query as the client's, which would also reclassify a data fault in a `@condition` or a filter argument. Neither is worth it for a cursor someone edited by hand.

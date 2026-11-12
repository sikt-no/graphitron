---
id: R1180
title: "A connection pages wrong, or not at all, on an order column whose value Java does not carry exactly"
status: Backlog
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

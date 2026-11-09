---
id: R1162
title: "A connection cursor over a column with no SQL data type cannot be read back: fromDatabase throws NPE on page 2"
status: Backlog
bucket: bug
priority: 2
theme: runtime-connection
depends-on: []
created: 2026-11-09
last-updated: 2026-11-09
---

# A connection cursor over a column with no SQL data type cannot be read back: fromDatabase throws NPE on page 2

## Goal

Paging forward works on a connection whose order includes a column jOOQ has no built-in SQL type for, such as a Postgres `tstzrange` typed through a custom `Binding`. Today the first page answers and every `after:` page throws a `NullPointerException`, reported as GitHub issue #576 against 10.0.0-RC47. Because every connection's order ends with the table's primary key as a tiebreaker, any table whose primary key holds such a column is affected, with or without an `orderBy` argument.

## What happens today

A connection cursor is a JSON array with one element per order column. The emitted `ColumnValues` class writes each element (`toText`) and reads it back (`fromDatabase`), both keyed on the column's SQL data type, `DataType.getSQLDataType()`. For a column whose type comes from a binding over a type jOOQ does not know, that is `null`.

- `toText` allows for it: it falls back to the column's own `DataType`, matches none of the boolean, numeric or temporal branches, and writes the database value's `toString()` as a JSON string. For the `tstzrange` column the database value is the string `[2024-01-01T00:00Z,2025-01-01T00:00Z)`, and that is the element.
- `fromDatabase` does not: it hands `getSQLDataType()` to `databaseValue`, whose first line is `sql.isDate()`, and throws.

Reproduced outside the build against jOOQ 3.20.11 and `jooq-postgres-extensions` 3.20.11, with the column typed as jOOQ codegen types it (`DefaultDataType.getDefaultDataType("\"pg_catalog\".\"tstzrange\"").asConvertedDataType(new OffsetDateTimeRangeBinding())`): `getSQLDataType()` is `null`, the converter is `Converter<Object, OffsetDateTimeRange>`, and `fromDatabase` throws `Cannot invoke "org.jooq.DataType.isDate()"`.

## Notes for the Spec

- The likely fix: when the column has no SQL data type, there is no database-type parse to run, so `fromDatabase` hands the element to the column's converter as the database value (`converter.from(element)`, when `converter.fromType()` admits it) and checks the result as it checks any other. In the reproduction that gives back a range equal to the original.
- The issue suggests the fallback `toText` uses, parsing with the column's own `DataType`. That runs the converter during the parse, and `fromDatabase` then runs `converter.from` a second time on the finished user value. It happens to round-trip for jOOQ's range converters, which parse `toString()`, but it relies on a consumer's converter accepting its own output, which nothing promises. The Spec should settle which, and say why.
- Regression test: `ColumnValuesTest` in `graphitron-sakila-example` can build such a `DataType` with a small test-local `Binding` over an unknown type name, with no database and no new dependency, and pin `fromDatabase(type, toText(type, converter.to(v)).text())` coming back equal to `v`. An execution-tier test over a real binding-backed key column would need a new fixture column; whether it earns its place is a Spec question.
- `databaseValue`'s `java.sql.Date` branch and `fitsPrecision` both read the SQL type and should be checked for the same null on the way.

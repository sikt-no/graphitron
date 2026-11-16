---
id: R1191
title: "A Float argument filtering a NUMERIC column is converted, not cast to BigDecimal"
status: Backlog
bucket: bug
priority: 2
theme: codegen-correctness
depends-on: []
created: 2026-11-16
last-updated: 2026-11-16
---

# A Float argument filtering a NUMERIC column is converted, not cast to BigDecimal

## Goal

A query argument typed `Float` that filters a `NUMERIC` column through `@field` executes instead of failing with a redacted error. Today the generated condition reads the argument as `(BigDecimal) args.get(...)`, but the engine hands a `Float` argument over as a `Double`, so every non-null value throws `ClassCastException` in the fetcher. The sakila example's `Query.films(maxRentalRate: Float @field(name: "RENTAL_RATE"))` shows it: `{ films(maxRentalRate: 2.99) { title } }` answers `An error occurred. Reference: ...` at `films`, from `QueryConditions.filmsCondition`. No test passes a value for `maxRentalRate`, which is how it went unnoticed.

## Notes

* The generated reader is the cast; jOOQ's `DataType.convert` on the column's type (as the `filmsByWireIdCondition` shape already does with `DSL.val(..., getDataType()).getValue()`) is the obvious alternative. Spec decides whether a `Float` on an exact `NUMERIC` column should be accepted at all, given that a `double` cannot carry every `NUMERIC` value, or refused at build time in favour of a `BigDecimal` scalar.
* Found while writing `RequestNumberBoundsTest` in `graphitron-sakila-example`, which steps around it with `@skip(if: true)`; once this lands that test can drop the `@skip`.

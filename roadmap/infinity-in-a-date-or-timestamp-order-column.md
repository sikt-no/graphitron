---
id: R1181
title: "A connection ordered on a date or timestamp column holding infinity cannot page past it"
status: Backlog
bucket: bug
priority: 5
theme: runtime-connection
depends-on: [cursor-carries-the-databases-own-text]
created: 2026-11-12
last-updated: 2026-11-12
---

# A connection ordered on a date or timestamp column holding infinity cannot page past it

## Goal

A connection whose order includes a plain Postgres `date`, `timestamp` or `timestamptz` column pages past a row holding `infinity` or `-infinity`, the usual way to say "still valid" in a `gyldig_til` column. Today such a row ends the walk: with `date` and `timestamp` every `after:` request from that row fails in the database, and with `timestamptz` the first page already fails when the column is selected or ordered on. What is not settled is how, which is why this is filed as a question first: the three types are among the cursor forms graphitron promises byte for byte (see "Cursor encoding is part of the public contract" in `docs/manual/how-to/connections.adoc`), so the answer has to keep those bytes for ordinary values and still say `infinity` for the others.

## What happens today

Measured against Postgres 16 with jOOQ 3.20.11, with the probe described in R1180 (`cursor-carries-the-databases-own-text.md`): one table per type, and for every row the cursor is written, read back and sought from.

- `date` holding `infinity`: jOOQ reads a sentinel `LocalDate` in the year 292278994, the cursor carries `+292278994-08-16T00:00:00`, and Postgres refuses it on the way back with `date out of range`. `timestamp` behaves the same way.
- `timestamptz` holding `infinity`: jOOQ's own read throws (`Expected '-' at position 0 in infinity`) before graphitron's cursor code runs.
- A `timestamp` before the common era: the era is lost in the read, the cursor carries `0044-03-15T12:00:00`, and a descending page repeats the cursor's row.

In the same probe, a cursor element written by the database (`cast(column as varchar)`) and read back by it (`cast(? as <type>)`) pages all three correctly. R1180 takes that route for every column outside the promised forms. It does not take it here because the database's text for a date is not the promised form (`2017-08-15`, not `2017-08-15T00:00:00`), and because on Oracle the text of a `DATE` depends on the session's NLS settings.

The GraphQL field over such a column is wrong today for the same reason (the client sees the sentinel year, or the read fails), so a consumer storing `infinity` already needs a converter for the field itself. Whether the cursor should work without one is part of the question.

## Depends on

R1180 settles how a database-written element travels (the extra projection, the cast on the seek). A solution here most likely reuses it for the values Java cannot carry, and should start from what that item shipped.

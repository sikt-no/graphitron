---
id: R1072
title: "A node id over a DATE key column with a time part decodes to a different row"
status: Backlog
bucket: bug
priority: 5
theme: nodeid
depends-on: []
created: 2026-10-19
last-updated: 2026-10-19
---

# A node id over a DATE key column with a time part decodes to a different row

## Goal

A node id whose key includes an Oracle `DATE` column decodes to the row it was encoded from,
including the time part, so `node(id:)` returns the row the id was minted for.

## Evidence

Surfaced by the sis v9 against v10 differential test on Oracle (2026-10-19). `Fodselsnummerendring`
for one person has two rows whose key column `DATO_FORETATT` holds 2007-08-22 00:00 and 02:24. v10
encodes them as distinct ids (v9 gave both the same id, which was worse), but `node(id: <the 02:24 id>)`
returns the 00:00 row. Either the decode or the key comparison truncates to the date.

## Questions for the plan

Which side loses the time: the decoded value's Java type (an Oracle `DATE` mapped to `LocalDate`
in the consumer's jOOQ catalog, while the encoder reads the raw value), or the bind. The plan pins
encode and decode against the column's declared jOOQ type so both sides agree, and covers this in a
test with a key column whose Java type drops information the database keeps.

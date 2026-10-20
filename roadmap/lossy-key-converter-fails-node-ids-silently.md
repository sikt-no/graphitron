---
id: R1079
title: "A node-key column whose converter does not round-trip makes its ids resolve to null with no diagnostic"
status: Backlog
bucket: dx
priority: 3
theme: nodeid
depends-on: []
created: 2026-10-20
last-updated: 2026-10-20
---

# A node-key column whose converter does not round-trip makes its ids resolve to null with no diagnostic

## Goal

When a node id that Graphitron itself minted fails to decode because the key column's jOOQ
converter does not round-trip the value, the consumer can find out why: the manual states that a
key column's converter must round-trip, and the server logs a warning naming the column, so an id
that resolves to `null` is diagnosable instead of indistinguishable from a deleted row.

## Evidence

From the sis Graphitron 9 against 10 differential test, retested on trunk after R1072 (2026-10-20).
sis declares six node-key `DATE` columns with a custom `Binding<LocalDate, OffsetDateTime>` whose
real read and write path keeps the time part, but whose `converter()` goes through `LocalDate` and
drops it (jOOQ consults `converter()` mainly for the classes, so the lie is invisible to jOOQ
itself). The generated `ColumnValues.exact` accepts a decoded slot only if the value survives
`type.getConverter()` to the database type and back with its text unchanged. For a value with a
time part it does not, so the id answers `null`: no error, no log line.

Three of the six columns hold a time part in sis's data. One backs a stable `@key` entity that is also
a `Node`; on v10 only 9 of the first 20 ids a list field returns resolve through `_entities`, where v9
resolved all 20. A production client calls both that list field and `node(id:)`. The consumer has
agreed to fix its declarations (a datetime type with a lossless converter), so this item is not
about making the lossy declaration work; it is about the next consumer with such a declaration being
able to see it.

## Plan sketch

- `how-to/global-id.adoc` (near R1072's converter text): a key column's converter must map every
  value the column holds to the database type and back unchanged; a `Binding` whose `converter()` is
  narrower than its read path makes those rows' ids resolve to `null`.
- A server-side warning when `exact` rejects a slot whose text parsed and whose trip succeeded but
  changed the value. That combination cannot come from a client typo (a malformed id fails the parse
  first); it comes from a converter that does not round-trip, or an id minted before the column's
  declaration changed. Log the column and the before/after text once per column rather than per
  request, so a hot entity does not flood the log. The client-facing answer stays `null`, as R1067
  decided for the lookup surfaces.
- Whether the same check can run once at startup or in a test helper over a sample of rows is an
  option for the Spec to weigh, not a requirement.

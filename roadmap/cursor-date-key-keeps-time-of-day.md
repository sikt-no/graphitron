---
id: R1143
title: "A cursor over a java.sql.Date order column pages from the time of day it carries"
status: Backlog
bucket: bug
priority: 4
theme: pagination
depends-on: []
created: 2026-11-02
last-updated: 2026-11-02
---

# A cursor over a java.sql.Date order column pages from the time of day it carries

## Goal

A connection that pages on a `DATE` column whose jOOQ type is `java.sql.Date`, and whose values hold a time of day (an Oracle `DATE` does), pages from the row the cursor names rather than from midnight of its day. A cursor writes such a value with its time, `"2003-08-15T13:14:15"`, as Graphitron 9 did on Oracle and as R1142 makes Graphitron 10 do. The read side drops it: `ColumnValues.fromDatabase` parses the element through `SQLDataType.DATE.convert`, which keeps the date and binds midnight, so the seek lands before every row of that day and the next page repeats rows. `ColumnValuesTest.DATE_WITH_TIME` pins the parse that loses the time. A consumer whose `DATE` maps to `LocalDate` (sis does) is unaffected, since its values hold no time.

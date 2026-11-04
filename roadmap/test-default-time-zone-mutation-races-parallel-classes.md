---
id: R1149
title: "Tests that set the JVM default time zone race the test classes running beside them"
status: Backlog
bucket: tech-debt
priority: 6
theme: testing
depends-on: []
created: 2026-11-04
last-updated: 2026-11-04
---

# Tests that set the JVM default time zone race the test classes running beside them

## Goal

A test that sets the JVM default time zone cannot change the zone under another test class running at the same moment, so no zone-sensitive test in `graphitron-sakila-example` can fail intermittently. Today, `CursorEncodingTest.instant_isWrittenAtUtc_whateverTheJvmZone` and `sqlDate_keepsItsTimeOfDay_whateverTheJvmZone` call `TimeZone.setDefault(Europe/Oslo)` and restore it in `finally`. The module's `junit-platform.properties`, however, runs test classes concurrently (fixed parallelism 4), and the default zone is JVM-global. A class that reads the zone twice in one assertion therefore sees a two-hour shift if the flip lands between the reads. `ColumnValuesTest.fromDatabase_readsASqlDateAsADateTime_keepingItsTimeOfDay` reads it twice: once in `fromDatabase`'s coercion and once in `Timestamp.valueOf`. The likely shape is JUnit's `@ResourceLock(Resources.TIME_ZONE)`, `READ_WRITE` on the writers and `READ` on the zone-sensitive readers, or `@Isolated` on the writers alone. Picking between them, and finding the readers, is the Spec's work. Seen during the independent In Review -> Done review of the `java.sql.Date` cursor read; it has not been observed failing.

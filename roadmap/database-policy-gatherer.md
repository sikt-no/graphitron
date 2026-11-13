---
id: R1184
title: "An optional gatherer reads the database's row-level security policies over a live connection"
status: Backlog
bucket: architecture
priority: 4
theme: diagnostics
depends-on: []
created: 2026-11-13
last-updated: 2026-11-13
---

# An optional gatherer reads the database's row-level security policies over a live connection

## Goal

A schema author is told what the database's row-level security means for the schema they are
writing. Hover on a `@table` type shows whether the table secures its rows, whether that is forced,
and each policy's command, roles and `USING` / `WITH CHECK` text. Warnings name the places where a
policy makes the schema promise something the database will not keep. The knowledge comes from a new
optional gatherer that reads the database's own catalog over a live connection. It runs only when
connection settings are given, and generated code is the same whether it ran or not.

## Why a live connection

The jOOQ-generated classes, which are how graphitron knows the database today, carry no policies, so
the facts have to come from the database itself. A gatherer over a live connection (rather than a
sidecar written at jOOQ codegen time, or a parse of migration scripts) is the direction Alf gave when
this was filed. The ruling that a live database may be a corpus for the store is to be written into
the owning `docs/architecture/` page as this item proceeds.

## What is settled

* **Connection configuration.** A new connection setting carries the coordinates the gatherer
  connects with. It replaces `<devDatabase>` (`DevDatabaseBinding`, read by the MCP `execute` tool):
  for now both are accepted, so consumers have time to migrate, and `<devDatabase>` is then retired.
  It keeps the environment-variable overrides `<devDatabase>` has, so credentials stay out of the
  checked-in POM.
* **Optional.** Without connection settings the gatherer does not run. That it ran is itself
  recorded, so "no policies" is never read from "nobody looked", and every diagnostic that depends on
  its facts stays silent when it did not run.
* **It informs, never shapes.** Its facts feed hover, MCP and warnings only. Generated output must
  not depend on whether a database was reachable.

## What it captures, to be settled at Spec

A new corpus row in `meta_corpus` beside `catalog` (the database's system catalog read over a
connection, as opposed to through jOOQ) and a new gatherer reading it. Candidate relations, each with
its grain:

* one row per table the database secures by row, with whether that is forced;
* one row per policy a table declares: command, permissive or restrictive, `USING` and `WITH CHECK`
  text kept opaque;
* one row per role a policy applies to, `PUBLIC` included;
* derived in the anchoring phase: one row per session setting a policy expression reads, with
  whether it treats both `NULL` and `''` as no identity.

These match `sql_table` by schema and table name in a derived step, not by foreign key, since the two
corpora are refreshed separately. A table one side has and the other lacks is itself a finding: the
jOOQ classes are stale against the database. Postgres first (`pg_class.relrowsecurity`,
`relforcerowsecurity`, `pg_policy`); Oracle VPD (`ALL_POLICIES`) and grants fit the same gatherer later.

## Diagnostics it enables

Each is a warning defect owned by the `graphitron` gatherer, which joins these facts with the SDL and
the configuration; none goes into `lint_violation`. They may be split into their own items at Spec.

* A non-null field reached through `@reference` into a row-secured table can resolve to null when a
  policy hides the target row, even where the foreign key is `NOT NULL`. Keyed on the use site.
* A DML mutation whose `WITH CHECK` admits a row its read policy then hides: the write succeeds and
  the payload's re-read returns nothing (the `secure_note` fixture in `graphitron-sakila-db`'s
  `init.sql` is this case). Also a row-secured table with no policy for the mutation's command, which
  the database denies by default.
* A policy reading a session setting without treating `NULL` and `''` alike as no identity, the
  fail-closed rule in `docs/security.adoc`; and setting names policies read that the configured
  `SessionState` mount does not write.
* A `@table` on a table without row security while the tables around it have it.

## Open at Spec

* Which role the runtime connects as. Policies apply `TO` roles; without knowing the application
  role(s), the diagnostics must assume any policy may apply.
* Whether `meta_gatherer_dependency` can express a downstream gatherer reading an optional upstream
  that may not have run.
* Refresh cadence: re-read every round (a few cheap catalog queries) or skip on an unchanged hash.
* The relation to R1050, which fetches DDL bodies from the same connection on request; its "later"
  catalog gatherer is largely this one.
* Whether the gatherer may run under `generate` in CI when settings are given, or only in dev
  sessions.

---
id: R1050
title: "The database's own DDL, asked for one object at a time"
status: Backlog
bucket: dx
priority: 5
theme: lsp
depends-on: []
created: 2026-10-15
last-updated: 2026-10-15
---

# The database's own DDL, asked for one object at a time

## Goal

An author in the editor or an agent over MCP can see the database's own definition of what a schema
names: a table, its keys, constraints and indexes; a view; a routine's body; a trigger and the
function it runs. The answer comes from the live database, one object at a time, when it is asked
for, so it is never stale and costs nothing on a schema nobody asks about. It is a dev-session
feature, present where the session already has the database connection `execute` uses and absent
otherwise; `generate` and CI never need it.

## Why at request time, not a gatherer

Structure (names, kinds, columns, keys, signatures) is small, the model reasons over it, and it
already arrives through the jOOQ catalog, which the generator has without a database. Bodies are
large text the model never reads: capturing every routine and trigger body on every round costs in
proportion to the schema, which on sis is the wrong trade for an answer wanted one object at a time.
The database stays the authority for what it holds.

## What the database offers

* Postgres: `pg_get_functiondef` for routines, `pg_get_triggerdef` for triggers, `pg_get_viewdef`
  for views, `pg_get_constraintdef` and `pg_get_indexdef`, `pg_policies` for row-level security.
  Postgres has no single statement for a table, so its DDL is composed from those, or rendered by
  jOOQ's `meta().ddl()` over live JDBC metadata.
* Oracle: `DBMS_METADATA.GET_DDL` covers every object kind, packages and triggers included.

The lookup is per dialect from the start, Postgres first because sakila tests it.

## Shape, to be settled at Spec

* An MCP tool answering one object's DDL by name, read-only, refusing with its cause where the
  session has no database or the object does not exist there.
* An LSP hover or go-to on a `@table`, a `@reference` key and a `@routine` showing the same answer.
* Sakila's `init.sql` gains a view and a trigger so there is something to show and test.

## Later, not here

A thin catalog gatherer of what the database holds (name, kind, signature, and a last-DDL time where
the database keeps one) would let the editor tell a routine the database has from an unknown name,
and warn when the database has moved past the jOOQ classes, as a module read stale is warned of
today. Bodies would still be fetched on request.

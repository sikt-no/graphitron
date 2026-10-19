---
id: R1062
title: "A multi-table interface or union connection pages and orders correctly only on PostgreSQL"
status: Backlog
bucket: bug
priority: 2
theme: codegen-correctness
depends-on: []
created: 2026-10-19
last-updated: 2026-10-19
---

# A multi-table interface or union connection pages and orders correctly only on PostgreSQL

## Goal

A root connection that returns a multi-table interface or union (an interface or union whose
implementing types live in different tables) pages and orders the same way on every dialect: a page
fetched with `after` starts after the last row of the previous page, and rows come out in key order
rather than in the text order of a JSON rendering of the key.

## Evidence

Surfaced by a v9 against v10 differential test of the sis subgraph on Oracle (2026-10-19).
`Query.personroller` (interface `Personrolle`, nine participant tables with a three-column key) and
`Query.studentvurderinger` both return page 1 for every `after` cursor, and `hasNextPage` stays
true, so a client that pages loops forever. Repro shape:
`personroller(filter: {eierOrganisasjonskode: "1234"}, first: 2, after: <endCursor of page 1>) { nodes { id } }`
returns the same two ids as page 1. The cursor decodes to `[1234,1000,11]` NUL `Undervisningsaktivitetsrolle`.
The same field also interleaves the participant types, ordering `...,10` before `...,1`, where v9
grouped by type and then by key.

## Probable cause

`MultiTablePolymorphicEmitter` builds a `UNION ALL` of per-participant selects; for a composite key
it projects `DSL.jsonbArray(pk0..pkN)` as the `__sort__` column and orders and seeks on
`__sort__` plus `__typename`. Its own comment states the assumption: "PostgreSQL's JSONB
lexicographic ordering reproduces the multi-column ordering". That holds on PostgreSQL, which orders
`jsonb` arrays element by element with numeric comparison. On Oracle the column degrades to JSON
text (or a type the seek predicate cannot compare), which explains both the text ordering and the
seek that excludes nothing. This is a hypothesis to confirm against the rendered Oracle SQL of the
page-2 request (whether the seek predicate is emitted at all, and what `__sort__` renders as).

The validator already enforces a uniform key arity across participants, and the stage-1 select
projects `__pk0__..__pkN__` beside `__sort__`, so ordering and seeking on the individual key columns
looks reachable without the JSON column. Participant key columns may differ in type across tables,
which is presumably why the JSON column was chosen; the plan has to settle that.

## Test gap

`MultiTableOrderingExecutionTest` covers ordering and paging on PostgreSQL only, where the JSONB
ordering happens to be right, so nothing in the reactor would catch this. The plan should state how
the dialect-independence is pinned (a rendered-SQL assertion for a non-PostgreSQL dialect, or a
sort key whose ordering does not depend on the dialect by construction).

Related: `generated-output-hygiene-sweep.md` lists `__sort__` among the reserved aliases; that item
is about naming, not this defect.

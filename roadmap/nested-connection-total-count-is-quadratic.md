---
id: R1070
title: "A nested connection totalCount runs one count per parent, each over every parent's rows"
status: Backlog
bucket: bug
priority: 2
theme: codegen-correctness
depends-on: []
created: 2026-10-19
last-updated: 2026-10-19
---

# A nested connection totalCount runs one count per parent, each over every parent's rows

## Goal

Selecting `totalCount` on a connection nested under a list of N parents costs one grouped count
statement for the whole batch, as it did in Graphitron 9, instead of N statements that each scan the
rows of all N parents. A page of 400 parents with a nested `totalCount` returns in seconds rather
than timing out.

## Evidence

Surfaced by the sis v9 against v10 differential test on Oracle (2026-10-19).
`emnerV2(filter: {eierOrganisasjonskode: "1234"}, first: N) { nodes { kode vurderingsenheter(first: 1) { totalCount } } }`
returns identical data on both versions. Times, v9 against v10: N=50 1.4 s / 2.6 s; N=100 4.4 s /
13.8 s; N=200 3.9 s / 36 s; N=400 4.3 s / over 120 s (timeout). The same curve shows on two other
nested connections; nested `nodes` without `totalCount` is faster on v10 than on v9. Production
operations select nested `totalCount` this way (including one with three nested counts), and with no
`first` cap a request with `first: 1000` holds a database connection for minutes.

## Cause

`SplitRowsMethodEmitter.buildScatterConnectionByIdxHelper` emits `scatterConnectionByIdx`, which
gives each parent's `ConnectionResult` the shared count source (the derived table over all N parent
keys) plus the condition `__idx__ = i`. The generated `ConnectionHelper.totalCount` resolves lazily
per `ConnectionResult` with `dsl.selectCount().from(cr.table()).where(cr.condition())`, so N parents
issue N statements, each evaluating the derived table for every key before filtering to one.

## Direction

One `select __idx__, count(*) ... group by __idx__` per batch, computed the first time any parent's
`totalCount` resolves (a DataLoader or a memo shared by the batch's carriers), and read by index for
the rest. The plan should keep the laziness (no count when `totalCount` is not selected), say how
the multi-tenant variant and the polymorphic batched path, which uses the same per-index count
shape, are covered, and pin the statement count in a test rather than a timing.

## Further evidence (sis studieprogram area, 2026-10-19)

`studieprogramV2(first: N) { nodes { campuser(first: 50) { totalCount } } }`, v9 against v10: N=100
about 1 s against 9 s; N=200 1.4 s against 38 to 40 s. A scan of the consumer's production log finds
139 documents with a nested `totalCount`, some of them among the most frequent operations.

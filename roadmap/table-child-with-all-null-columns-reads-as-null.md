---
id: R1069
title: "A single-row @table child whose columns are all null resolves to null, as v9 resolved it"
status: Backlog
bucket: bug
priority: 4
theme: codegen-correctness
depends-on: []
created: 2026-10-19
last-updated: 2026-10-19
---

# A single-row @table child whose columns are all null resolves to null, as v9 resolved it

## Goal

A single-row `@table` child field resolves to `null` when every column it projects is `null`, as
Graphitron 9 resolved it, so a client that reads `postadresse != null` as "has an address" keeps
working across the upgrade. Today the field resolves to an object whose fields are all `null`
whenever the underlying row exists.

## Evidence

Surfaced by the sis v9 against v10 differential test (2026-10-19). `PersonProfil.postadresse` is a
`@table(name: "STUDENT") @splitQuery` child with four `*_SEMADR` columns. When the `STUDENT` row
exists but all four columns are `null`, v10 returns `{co: null, gate: null, ...}` and v9 returned
`null`. Repro shape:
`personProfilerGittPersonlopenumre(eierOrganisasjonskode: "1234", personlopenumre: ["900", "1690"]) { postadresse { __typename } }`.
Graphitron 9 projected the child with `DSL.row(...).mapping(Functions.nullOnAllNull(Postadresse::new))`.

## Relation to R1026

R1026 restored `null` for nesting wrappers (a type with no table of its own, folded into its
parent's row) through a null contract on the nesting field, whose `CollapsesWhenAbsent` arm collapses
the wrapper when every leaf is absent. This item is the same question for a child that does have a
table: the row is present, and only its projected columns are empty.

## Question for the plan

Whether collapsing on all-null columns is right in general for a row that exists (it conflates "no
row" with "a row with no values", which v9 did silently), or should be a declared choice on the
field. The plan settles that before choosing between a default and a directive, and says how it
interacts with `@splitQuery` batching, where the child row is read in a separate statement.

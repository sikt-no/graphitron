---
id: R1017
title: "An INSERT leaves absent input fields out of its column list instead of writing DEFAULT"
status: Backlog
bucket: bug
priority: 5
theme: mutation-write
depends-on: []
created: 2026-10-12
last-updated: 2026-10-12
---

# An INSERT leaves absent input fields out of its column list instead of writing DEFAULT

## Goal

A generated INSERT works against a view on Oracle. Today, for each input field absent from a row, the INSERT writes the column with an explicit `DEFAULT` (`row.containsKey(f) ? DSL.val(...) : DSL.defaultValue(type)`, rendering `values (:1, :2, :3, default, cast(:4 as date), ...)`). Oracle rejects an explicit column default against a view (ORA-32575, "Explicit column default is not supported for modifying views"), and most sis API tables are views. When this lands, absent fields are left out of the column list, which is what Graphitron 9 did and which lets the database apply its own default without naming it.

## Provenance

sis IT run against Oracle 18c on trunk `dd940c703` (`sis/GRAPHITRON10_STATUS.md`, section "Second IT run"). Seen on `insert into "STUDENTKORT"`.

## Where it lives

`TypeFetcherGenerator.buildPerCellValueList` and its sibling `buildPerCellValueListDeduped` (the `DSL.defaultValue` arms around `TypeFetcherGenerator.java:2518`), plus the nested-map reader documented near `:2645`.

## Open question for the Spec

The insert is multi-row (`input: [X!]!` renders one `VALUES` list). Leaving absent columns out of the column list only works directly when every row in the batch has the same set of present keys. With mixed shapes the options include one INSERT per distinct present-key set, or falling back to the column's default value fetched from the jOOQ `DataType` rather than the `DEFAULT` keyword. Name the choice; do not let the batch quietly change meaning.

## Related

R103 (lift jOOQ column defaults onto input fields) assumes the current `DSL.defaultValue` emission is the correct runtime story and treats its own gap as contract-only. This item shows that premise does not hold on Oracle views, so R103's Spec should be read against whatever this item lands.

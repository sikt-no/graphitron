---
id: R236
title: "BuildContext nested-input candidate-hint draws from path-origin table instead of @reference terminal table"
status: Backlog
bucket: bug
priority: 5
theme: diagnostics
depends-on: []
created: 2026-05-23
last-updated: 2026-07-14
---

# BuildContext nested-input candidate-hint draws from path-origin table instead of @reference terminal table

## Symptom

`BuildContext.classifyInputFieldInternal` (`BuildContext.java:2437`) emits a "Did you mean…" hint when a nested-input column name is unresolvable. The candidate list is built from `catalog.columnSqlNamesOf(resolvedTable.tableName())` (`:2535`) where `resolvedTable` is the path-*origin* enclosing input's `@table`, not the path's terminal table.

For an unreachable column under a `@reference` path field, the candidate suggestions are drawn from the wrong table and lead the user away from the fix. Sibling bug to R233 / R224 on the LSP-arm side, on a different surface.

## Sibling context

R224 fixed the LSP diagnostic. R233 (this item's parent, since shipped) lifted the column dispatch onto `FieldClassification.lspColumnDispatch()` and routed the LSP completion + hover arms through it. The runtime-side candidate-hint at `BuildContext.java:2535` is a different surface (compile-time validator error message vs. LSP arm) and a different audience (the user sees it once at build time, not interactively per keystroke), so it was filed as a separate Backlog item rather than folded into R233.

## Trace

Inside `classifyInputFieldInternal`, the failure-aggregation block (`BuildContext.java:2527`-`2539`) walks `failures` for nested input fields, picks the first one with a non-null `lookupColumn`, and asks for a candidate hint from `resolvedTable.tableName()` (`:2535`). For a `@reference` path field the terminal table on the projected `FieldClassification.{ColumnReference,CompositeColumnReference}.tableName()` is the right list; the path-origin table is not.

## Design sketch

The runtime classifier already projects the terminal table (R224 introduced the projection; R233 lifted it onto `lspColumnDispatch()`). One option is to route the candidate-hint dispatch through the same `FieldClassification` projection so the runtime and LSP arms share a single terminal-table source. Another is a narrower fix that looks up the path's terminal table directly at the call site. Spec-time decision.

## Out of scope

LSP arms (covered by R224 + R233). The runtime data-fetcher behaviour (not affected; the column resolution is already correct, only the error message's candidate list is wrong).

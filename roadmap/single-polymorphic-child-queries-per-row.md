---
id: R1012
title: "A single-valued child over a multi-table interface or union queries once per parent row, where its list form batches"
status: Backlog
bucket: bug
theme: interface-union
depends-on: []
created: 2026-10-03
last-updated: 2026-10-03
---

# A single-valued child over a multi-table interface or union queries once per parent row, where its list form batches

## Goal

A single-valued field returning a multi-table interface or union runs its own query once for every parent row, reading the parent's key off the row by name: `Store.contact` (`@referenceFor`) is a `UNION ALL` per store, and `Film.firstMemberViaJunction` and `Film.firstByCondition` are the same shape. The list forms of the same fields batch through a DataLoader over `VALUES(idx, key)` and scatter by `__idx__`, as every `@splitQuery` does. When this lands the single forms batch the same way, so a page of parents costs one query per participant rather than one per parent. The single-table interface child, `Film.filmContent`, has the same per-row shape and is R288's, which inlines it instead. Found while cataloguing the generated code's patterns for the model of what the generator reads.

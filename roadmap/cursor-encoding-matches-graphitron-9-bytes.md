---
id: R1142
title: "A newly issued cursor is byte-identical to the one Graphitron 9 issued for the same row"
status: Backlog
bucket: enhancement
priority: 3
theme: pagination
depends-on: []
created: 2026-11-02
last-updated: 2026-11-02
---

# A newly issued cursor is byte-identical to the one Graphitron 9 issued for the same row

## Goal

On a connection that pages on the same columns in both versions, the cursor Graphitron 10 writes for a row is the exact string Graphitron 9 wrote for it. Each JSON array element is rendered as Graphitron 9's SQL `jsonArray` rendered it: a numeric column as a bare JSON number, a character column as a JSON string, and nulls and temporal values exactly as the database's `JSON_ARRAY` wrote them. A consumer that pins cursors in approval tests keeps its Graphitron 9 values. A release rolled back to Graphitron 9 reads cursors Graphitron 10 issued without relying on type coercion. Decoding keeps accepting both forms, strings and numbers, because Graphitron 10 cursors issued between R1088 and this item may already be stored.

## Why R1088 did not cover it

R1088 (Done) made Graphitron 9 cursors *readable*, which is its goal, and sis confirms it: `QueryFilesApprovalIT` passes main's real Graphitron 9 cursor as `after` and pages correctly. For *writing*, R1088 deliberately chose "every non-null value a JSON string" so the cursor stays column-driven. But Graphitron 9 did not write strings everywhere. `graphitron-common` 9.4.4 `QueryHelper.getOrderByToken` builds the token in SQL with `DSL.jsonArray(table.fields(orderFields))` then `convertFrom(...)`, so the database renders a typed array. Same row of sis's `emneBeskrivelsesavsnittPaginert`, where `INSTITUSJONSNR_EIER`, the second key column and `ARSTALL` are `NUMBER`:

```
Graphitron 9:  [1234,185,"MUS101","1","MÅL","ENGELSK","VÅR",2017]
Graphitron 10: ["1234","185","MUS101","1","MÅL","ENGELSK","VÅR","2017"]
```

The manual (`connections.adoc` § the cursor format) says both "This is the format Graphitron 9 issued" and "Graphitron 9 wrote some values as bare JSON numbers", which this item reconciles.

## What exists today

`ConnectionHelperClassGenerator` emits `ConnectionHelper.encodeCursor`, which writes `null` or `writeJsonString(json, value.toString())` of each column's database value (the column's converter applied). `decodeCursor` reads any JSON scalar through `readJsonArray` and binds through `ColumnValues.fromDatabase`, so the read side already accepts bare numbers. `CursorFormatExecutionTest` pins the current wire shape, for example `filmsConnection`'s `endCursor` `WyI0Il0` (`["4"]`). After this item the expected value is `WzRd` (`[4]`).

## Things to settle in Spec

* **Choosing the element form from the column, not the value.** R1088 refused to tag by the value's Java class, since that would be a second type system beside the `DataType`. The form here follows the column's SQL data type: numeric types bare, everything else a string, unless the temporal rows below say otherwise. Decide where that classification lives, and what a numeric value that is not finite writes, since JSON has no NaN.
* **Nulls.** Graphitron 9 called `convertFrom` straight on the `JSONArrayNullStep`, with neither `nullOnNull()` nor `absentOnNull()`, so the rendered SQL decided. Oracle's `JSON_ARRAY` defaults to `ABSENT ON NULL`. Establish what jOOQ 3.x rendered for Oracle there, since jOOQ may emit an explicit clause, and what sis's main issues for a row with a `NULL` order column. If Graphitron 9 dropped the element, its cursor is one element short, and Graphitron 10's strict-arity decode **rejects** it. That is a read-side gap in R1088's own goal for a nullable order column, and it should be handled as a revive of R1088 rather than inside this item. Matching the writer to an absent element would also make cursors ambiguous, so the decision belongs together with that read question.
* **Dates and timestamps.** Compare the text Oracle's `JSON_ARRAY` renders for `DATE` and `TIMESTAMP` (and PostgreSQL's for `timestamp`/`timestamptz`) with the database-value `toString()` R1088 writes. Match it where it differs. The decode already parses the database renderings.
* **Dialect.** The renderings are the database's, so byte identity is per dialect. Name the dialects the item promises (Oracle for sis, PostgreSQL for the sakila fixture) and how a test checks Oracle's rendering without an Oracle database.

## Docs

`connections.adoc`'s cursor-format paragraph states the typed rendering. `migrating-from-legacy.adoc` and `sort-results.adoc` change where they restate the format.

## Tests

`CursorFormatExecutionTest`'s pinned cursors flip to the typed form. Add a mixed numeric/text order (the sis shape), a cursor in the old all-string form still decoding, and the null and temporal rows Spec settles.

## Provenance

Requested 2026-11-02 by sis (the Graphitron 10 port, request C41, which asked for R1088 to be revived). It is filed as a successor rather than a revive because R1088's goal, reading a stored Graphitron 9 cursor, is delivered. Byte identity on write is a new goal, and R1088's design ruled it out on purpose. sis's 8 cursor approvals go back to main's values when it lands, with no sis code change.

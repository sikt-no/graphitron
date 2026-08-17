---
id: R701
title: "Capture declares the columns it writes, so an insert is designed rather than every-field"
status: Backlog
bucket: architecture
theme: model-cleanup
depends-on: []
created: 2026-08-17
last-updated: 2026-08-17
---

# Capture declares the columns it writes, so an insert is designed rather than every-field

## Problem

An insert should name the columns the writer has data for, and that column list should be written
rather than reconstructed. For 123 relations, capture writes no insert statement at all. A capture
site builds a generated record, calls one typed setter per column, and hands it to the sink:

```java
var row = sink.dsl().newRecord(SQL_COLUMN);
row.setSourceName(source);
row.setColumnName(column.sqlName());
...
sink.add(row);
```

`FactSink.flush()` then assembles the statement at runtime from the relation's own shape:

```java
Field<?>[] fields = table.fields();
var insert = dsl.insertInto(table).columns(fields).values(new Object[fields.length]);
...
batch = batch.bind(row.intoArray());
```

So the column list is inferred from the DDL, and every column of the relation is asserted writable
whether or not capture has anything for it. The rest of the write side does not work this way:
`FactCapture`, `ClasspathSources` and `JavaSourceFacts` name their columns in the `insertInto` call.
`flush()` is the one path that reconstructs what its callers already stated, and it is the path the
123 relations go through.

The precondition, that capture writes every column of every relation it touches, is load-bearing,
unstated and unchecked. A column added to a base relation joins every insert silently. A column
capture cannot supply breaks the relation's whole write at runtime rather than at review. The bind is
also positional over `Object[]`; that is sound today only because `table.fields()` and
`record.intoArray()` derive from the same generated field row, and the store is overwhelmingly
`VARCHAR`, so two sequences that ever did disagree would write wrong values rather than fail.

Nothing is broken today. The trigger was hypothetical: the case-folded-name-column work needs a
generated column, and H2 refuses an insert that names one, so `.columns(table.fields())` would break
that relation's whole write. The item is that the write path's contract should be stated by the writer
instead of reconstructed from the schema, which is worth landing whether or not that column ever
arrives.

## Settled

Inserts are designed. The statement names the columns the writer has data for, explicitly, and that
list is not derived from the relation's shape at runtime.

Two mechanisms were proposed and rejected for the same reason, recorded so they are not re-proposed:
filtering `table.fields()` by jOOQ's readonly flag, and grouping each relation's buffered rows by
jOOQ's changed-field set. Both reconstruct the column list from a different source instead of stating
it, and both leave the knowledge in the writer rather than at the site that has the data.

## The fork to settle at Spec

How far the indirection goes with it.

* **The sites write the statements.** Each of the 123 capture sites writes its own
  `insertInto(...).columns(...)`, replacing the `newRecord` plus setters. `FactSink` keeps the jobs
  that are its own (the `claim()` dedup, the graph-partition stamp, the parents-first ordering, and
  batching) and loses column-list construction. The statement reads as a statement, at the cost of a
  large mechanical change across every capture class.
* **The records stay, `flush()` stops reconstructing.** The sites keep the generated-record surface
  they currently dogfood, and the declaration of which columns a relation writes moves somewhere
  explicit that `flush()` consults. Smaller change, but the insert is still assembled rather than
  written, so it honours the principle's letter more than its shape.

The first is the reading the principle most plainly implies. The Spec should settle it before any
capture class is touched, because the two differ by roughly two orders of magnitude in blast radius.

Either way one measurement is mandatory before and after: `flush()`'s bind-batch beats `batchInsert`
by a measured 1.8x (3.7 s to 2.1 s over a 207k-row census, per its own comment), so the shape must
keep one prepared statement per relation rather than rendering per row.

## Gate

The precondition becomes checkable, which is the durable half. After a capture of the fixture corpus,
assert per relation that the columns capture writes cover every `NOT NULL` column, and that the set is
stable across the sites that write it. That is the check which would have caught the generated-column
collision at build time instead of at H2's error message.

Whether an intended-absence roster is worth it (a column deliberately never written, argued in a row,
in the exemption polarity `meta_prefixless_relation` uses) is a Spec question, and only worth adding
if such a column exists or is expected.

## Care

`flush()` is the single write path for 123 relations, so the capture-agreement tier is the harness and
it is substantial: `FactCaptureAgreementTest` compares captured values against the catalog's and the
scanner's own readings for the wide relations, so a mis-bound column fails loudly there.

One fact that bounds the risk: the DDL declares no `DEFAULT` on any column, in 4,492 lines. So a
column omitted from an insert and a column bound null produce the same row today, and no change here
can alter a captured fact through that difference.

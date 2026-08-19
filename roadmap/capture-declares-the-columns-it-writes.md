---
id: R701
title: "Capture declares the columns it writes, so an insert is designed rather than every-field"
status: Backlog
bucket: architecture
theme: model-cleanup
depends-on: []
created: 2026-08-17
last-updated: 2026-08-18
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

**Eleven of the 123 arrive ahead of this item.** That trigger is no longer hypothetical:
`roadmap/decodes-normalize-internal-grammars.md` adds the generated columns, and rather than wait on
this item it converts the eleven relations it folds columns onto (`graphitron_table`,
`graphitron_mutation`, `graphitron_routine`, the three reference-step relations,
`graphitron_field_binding`, `graphql_field`, `sql_table`, `sql_constraint`, `sql_column`), thirteen
`newRecord` sites in all. `sql_constraint_column` was the twelfth in an earlier draft of that item
and is *not* converted: its only case-insensitive comparison is against `sql_column` inside the
catalog family, so the narrowed fold rule mints it no folded column, it carries nothing the database
computes, and it stays on the generic arm for this item to convert with the rest.

That item takes the written-statement shape and the
settled rejections below unchanged, and it introduces the one mechanism this item's end state does
not have: while both arms exist, `flush()` dispatches to a relation's write function where one is
registered and renders generically where none is, because the converted relations interleave with
unconverted parents on both sides and the write order has to span both. It also lands the
column-coverage gate scoped to the relations that have write functions, so the gate grows with the
conversion instead of arriving at the end.

So this item keeps the remaining 112 relations, the plain-record gatherer layer, the three
relocations below, the corpus-wide gate, and the deletion of the generic arm and its dispatch once no
relation needs it. Read the design below as the whole of the work, with those eleven already done and
their write functions the worked example the rest follows.

## Settled

Inserts are designed. The statement names the columns the writer has data for, explicitly, and that
list is not derived from the relation's shape at runtime.

Generated jOOQ records are not the vehicle. Hand-written DML is both simpler and more powerful:
records cannot express an upsert or a `RETURNING` clause without leaving the record API anyway.

Three mechanisms were proposed and rejected, recorded so they are not re-proposed. Filtering
`table.fields()` by jOOQ's readonly flag, and grouping each relation's buffered rows by jOOQ's
changed-field set: both reconstruct the column list from a different source instead of stating it.
And keeping the record surface while moving only the column declaration somewhere `flush()` consults:
that honours the principle's letter while leaving the insert assembled rather than written.

## The design

Two layers, with the jOOQ boundary between them.

**Gatherers produce plain Java records, in bulk.** A crawler's output is a `List<SomeFact>` of
ordinary records, and it imports neither `DSLContext` nor the generated `Tables`. That is the
testability half: a gatherer becomes a function from a graphql-java AST, a jOOQ catalog reading or a
classfile to a list of facts, assertable without a store.

**The store owns the DML: 123 functions, each of a store and a batch.** One per relation, each a
hand-written bulk statement naming its columns. Writing the DML rather than assembling it is also what
makes upserts and `RETURNING` available, which the record path cannot express cleanly; today the
choice between a plain insert and `onDuplicateKeyIgnore` is made by `sharedFamily()`, a string prefix
test on the table name, so conflict behaviour is currently inferred from a naming convention rather
than stated per relation.

### Where the assumption does not hold yet

Only the catalog side already has the plain-record intermediate: `JooqCatalog.ColumnFacts`,
`IndexFacts` and `ForeignKeyFacts` are ordinary records, and `CatalogFactCapture` translates them into
jOOQ records field by field, so for that side the work is deleting a translation step. The two largest
capture classes do not work that way. `SdlFactCapture` (1,021 lines) and `GraphitronFactCapture`
(1,050) go straight from a graphql-java `Node` to `newRecord(...)` plus setters, so those gatherers do
know about jOOQ and are harder to test than the design assumes. The capture layer is 4,702 lines
across fourteen classes, and that is the real size of the move.

### Three responsibilities need new homes

`FactSink` is not only building column lists, so the rewrite has to place these deliberately rather
than let them fall out:

* **The `claim()` dedup, at 79 call sites.** It is not constraint avoidance. It decides which
  occurrence of a duplicated declaration wins, and the loser is the input to duplicate-declaration
  detection. An `onDuplicateKeyIgnore` would let physical insert order pick the winner instead, which
  is a different rule. So dedup belongs in the gather stage, over the plain records, where it is
  testable, with the losers becoming their own batch.
* **Parents-first foreign-key ordering.** With 123 functions the caller orders the calls, which makes
  the ordering explicit instead of a topological sort computed at flush. That is an improvement and
  also a transfer of a correctness property from machinery into a call sequence, so it wants a test of
  its own.
* **The graph-partition stamp**, currently applied inside `add()`. Becomes a parameter of the write
  function or a component the gatherer fills.

One measurement is mandatory before and after: `flush()`'s bind-batch beats `batchInsert` by a
measured 1.8x (3.7 s to 2.1 s over a 207k-row census, per its own comment), so each write function
must issue one bulk statement rather than rendering per row.

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

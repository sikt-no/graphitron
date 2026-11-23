---
id: R454
title: "A routine write takes the shapes opptak's slice needs"
status: Spec
bucket: feature
priority: 3
theme: routine
depends-on: []
created: 2026-07-09
last-updated: 2026-11-23
---

# A routine write takes the shapes opptak's slice needs

## Goal

opptak's first messaging slice (kommunikasjon med søker, slice 1) backs every mutation with one
plpgsql routine. The routine changes one row and hands it back as `RETURNS kommunikasjon.utsending`, a
table's row type, neither `SETOF` nor `RETURNS TABLE`. It refuses with a SQLSTATE of its own per refusal.
The payload is `{ utsending: Utsending, errors: [UtsendingFeil] }`, with a `@table` object type and the
error union. Its reads want the same routines' read twins as row sources. When this item lands, an author
gets the following:

1. **A mutation over a routine returning one row of a table.** The routine is called alone in the
   mutation's transaction. The returned record is the payload's data, and the selection's columns are read
   off it.
2. **A query over such a routine.** The routine is the field's row source, in `FROM` or `LATERAL` under a
   parent. The selection joins onward from its row in the same statement.
3. **A list of node ids handed to a routine as one array.** For a composite key, such as opptak's
   `Sak`, that is an array of a composite type with exactly the key's columns, as in
   `saksbehandling.sak_nokkel[]`. Otherwise it is an array of a table's record, or an array of one key
   column named in `argMapping`. Nothing about a list of ids is deferred.
4. **A refusal raised in the routine.** A mapped SQLSTATE lands as its `@error` member in `errors` and
   the write is rolled back. An unmapped one stays a top-level error. An execution test shows this.
5. **A refusal instead of a crash.** A payload data field the write cannot re-read yet, a `@table`
   `@discriminate` interface, is refused with a stored defect naming that shape. Today it crashes the
   capture.

Scalar and void routines and procedures as a field's value stay out of this item.

## What jOOQ gives, and what the store holds

This section is evidence from a scratch PostgreSQL 17 cluster and jOOQ 3.20.11 codegen. Every rendered
statement below was executed.

**What jOOQ generates.** A function `RETURNS k.utsending` is not table-valued, since jOOQ's Postgres
reader takes only `proretset` functions as tables. It generates `routines.UtsendingPubliser extends
AbstractRoutine<UtsendingRecord>`, typed with the table's own `TableRecord`. The holder class `Routines`
offers three forms:

- **execute:** `UtsendingRecord utsendingPubliser(Configuration, Integer)`;
- **value:** `Field<UtsendingRecord> utsendingPubliser(Integer)`;
- **value over fields:** `Field<UtsendingRecord> utsendingPubliser(Field<Integer>)`.

A `SETOF k.utsending` function is a table-valued `TableImpl<UtsendingRecord>`, with the table's record
again. A `RETURNS TABLE(...)` function gets a record of its own.

Array parameters map as follows:

| SQL parameter | Java type |
| --- | --- |
| `int[]` | `Integer[]` |
| `text[]` | `String[]` |
| `k.sak[]`, a table's row type | `SakRecord[]`, the table's record |
| `k.sak_noekkel[]`, a composite type | `SakNoekkelRecord[]`, a UDT record |

jOOQ binds each array as `cast(? as <type>[])`.

**How the execute form runs.** It renders `select row(t.*) from "k"."utsending_publiser"("p_id" := ?) as
t` and runs the routine once. A routine returning NULL hands back a record with every field null, not
`null`.

**Expanding the composite runs the routine several times.** `select ("k"."utsending_publiser"(...)).*`
runs the routine once per column of the row: three times for a three-column table.

**Joining onward from a write sees the old rows.** A write's statement reads the snapshot from before
the call:

- The routine updated `tittel` to `publisert` and a child row of `k.innhold` to `ny`.
- The returned row says `publisert`.
- A join or nested select in the same statement still reads `a` and `gammel`.

**A read routine can be a row source.** For a `STABLE` read routine returning one row,
`table("{0}", Routines.utsendingHent(1)).as("u")` puts it in `FROM`, with its columns typed from the
table's own data types. A nested multiset correlated on `"u"."utsending_id"` runs in the same statement:

    ... from "k"."utsending_hent"("p_id" := 1) as "u"

Under a parent, it is a lateral row source:

    cross join lateral "k"."utsending_hent"("p_id" := "p"."utsending_id") as "u2"

`DSL.table(Field)` over the value form throws, because it goes through `unnest`.

**What the store holds today.**

- `sql_routine` records whether a value comes back, but not which.
- `sql_routine_parameter.binding_type` is the JVM name, for example `[Ljava.lang.String;`.
- `sql_table.record_class_fqn` maps a record class to its table, and a `SETOF <table>` function's row
  carries the base table's record class.
- `code_class` holds the generated `Routines` classes. `code_method` holds no method of a generated jOOQ
  class, because the classpath census drops the generated package on purpose.
- Nothing captures a UDT.

## Model

### 0. jOOQ's holder classes are read like any class (blocker)

This slice closes two open Mikado nodes:

- "The generated jOOQ classes are read with their members". The census reads the generated package
  like any other class, with a sis capture timed before and after.
- "A method's form is read off its signature", as settled in
  `roadmap/audits/2026-10-22-jooq-routine-model.md`, "What the store takes from this".

Holder classes are found by type and never by the name `Routines`, as the audit says ("Configuration that
changes what exists").

Each method's form is an attribute of `code_method`, decided when the classfile is read, the way a
parameter's `role` is. The forms are:

- execute: `Configuration` first;
- value: a `Field` returned, over values or over fields;
- table: a `Table` returned.

The forms are a statement about the signature, so a hand-written method of the same shape offers them
too.

This gives a routine's return and parameters as the ordinary typed facts every method already has:

- the result's erased and element class;
- the first type argument of `Field<UtsendingRecord>`;
- each parameter's class, element class and delivery.

No relation is added for a routine's return.

An array's element is a rule change, stated at the type's grain. Today `code_type_element` says an
array names no class. With this slice, `SakNoekkelRecord[]` delivers `SakNoekkelRecord`, many, the way
`List<SakNoekkelRecord>` does. The DDL comment changes with it.

### 1. A routine returning one row of a table

"Returns one row of `T`" is a join. A root routine's call pins its holder method in
`graphitron_occurrence_call_method`, as a service's call does: every producer is a call
(`code-generation-triggers.adoc`, "Bind"). The arm `graphitron_occurrence_producer_result_rule` already
has for a service then answers for a routine too. That arm joins `code_method.result_element_class` to
`sql_table.record_class_fqn` of a `TABLE`, by `result_delivery`. The ROUTINE case and its join of
`graphitron_routine_result` go.

A `SETOF <table>` function's execute form delivers `T`'s record, many, so its rows are rows of `T` by
the same join, with no name matching. Name matching stays only for `RETURNS TABLE(...)`.

**In a mutation:**

- The routine's statement is the call alone, and nothing else is read in it, since that statement would
  see the old rows.
- The result is a single record of `T`, and the payload's data field is that record, handed over.
- The record carries `T`'s whole row, unlike a record a DML write or a `@service` hands back, which
  carries only its key. The producer result states this as a fact, and the data field reads its
  columns off the record without re-reading `T`.
- This reverses the documented rule that a write's data field is re-read by key after the call
  (`code-generation-triggers.adoc`, "Producer", and `routine.adoc` on the post-commit re-read). Both
  pages, and every emitter site that relies on the rule, change in the same commit.
- A field under the data field that needs more than the record's columns, such as `Utsending.innhold`,
  is fetched after the call from the record's key, through the lift's `key_constraint`.
- A record whose key columns are all null is no row, and the data field is then `null`.

**In a query:**

- At the root, a field over a one-row routine of `T` has the routine as its producer's row source, in
  `FROM` as `table("{0}", <value form>)`.
- Under a parent, a child `@routine` is never a producer but a step on its query's path
  (`code-generation-triggers.adoc`, "Bind"). It is a `LATERAL` step that lands on `T`, its arguments
  correlated on the parent's columns.
- Its columns are typed from `T`'s `sql_column` rows, which the join supplies.
- The selection joins onward from it in the same statement, with no waterfall.
- This is the Mikado node "A routine's result is an object of its function's record class", for the case
  where the routine returns a bound type and so gives a row of that table.
- It is emitted by the store-fed emitters that read the producer and the path, not by the walk's Query
  routine launchers, which are retiring (`pipeline-overview.adoc`, "Planning joins").

The emitter never renders the composite expanded with `.*`, which would run the routine once per
column.

### 2. A list of node ids at a routine parameter

What the routine receives is decided by the parameter's element class, as the census states it:

1. **A UDT record**, as in `saksbehandling.sak_nokkel[]`. This is opptak's case and comes first. Each id
   is decoded, and a record of the UDT is built with its attributes matched to the node type's key
   columns. This needs the UDT's attributes, which is a new catalog capture:
   - `sql_udt`: "this schema declares this composite type, whose record class is this";
   - `sql_udt_attribute`: "this attribute sits at this position of this composite type, with this
     type". It has `sql_column`'s shape: SQL name, jOOQ name and binding type.

   Both are read from `Schema.getUDTs()` and owned by the catalog gatherer. Attributes match key columns
   by exact SQL name. Both sides are catalog names, so there is no case fold. The match is stored beside
   the record slot, as the `OWN_RECORD` landing is, because the defect rule and the emitter both read it.
2. **A table's record** (`sql_table.record_class_fqn`). The record is built with its key columns set.
   This and the UDT arm are whole-key landings, which a decoded key may take
   (`defects-as-facts.adoc`, "Detail and subtype relations").
3. **One key column's values.** Off a list of ids, a column's value is a projection the author asks for
   by naming the column in `argMapping` (Filip, 2026-11-20). It is never handed over implicitly
   (Alf, 2026-11-12). The projection off a list yields an array of that column.

A list whose element class takes none of these is an author's mismatch, reported with the codes that
already state it (`NODE_ID_KEY_COLUMN_TYPE_DISAGREEMENT`, `NODE_ID_LANDS_ON_TABLE`) and not a code of its
own. `ROUTINE_NODE_ID_LIST_UNSUPPORTED` goes away. So does the routine-site list arm of
`ArgmappingProjectionDefects.unemittableProjections`, a step of the Mikado node "The argMapping readers
read the landing, and the candidate and match go". The `@routine` reference page,
`docs/manual/reference/directives/routine.adoc` ("A list of node ids"), changes in the same commit.

### 3. A refusal raised in the routine

This slice is an execution test in `graphitron-sakila-example`, over a fixture routine raising two
SQLSTATEs:

- The one a `DATABASE` `@error` handler maps lands as that member in `errors`, and the row the routine
  changed before the raise is not committed.
- The other stays a top-level GraphQL error.

If the test fails, the fix lands in this slice.

### 4. A `@discriminate` interface as a write's data field

- **The crash.** Two arms of `graphitron_occurrence_producer_lift_rule` both yield a row for a ROUTINE
  parent, and the capture dies on `graphitron_occurrence_producer_lift`'s primary key.
- **The misleading code.** A table-only interface draws `KEY_TABLE_WITHOUT_PRIMARY_KEY`, which is untrue.
- **The fix.** Each arm's eligibility follows from a stored fact it reads, the parent's produced shape,
  rather than from more mutual `NOT EXISTS`.
- **The refusal.** The shape is refused with one stored UNSUPPORTED code, which comes with its guidance:
  - It names the mechanism, a write payload's data field the write cannot re-read, not the routine. It
    also covers a DML write's payload if that has the same gap, which is checked first.
  - It replaces the misleading code rather than standing beside it.

This slice is a regression (`defects-as-facts.adoc`, "Adding a defect"), so it lands as its own commit.

## Order

Slice 0 first, as its own commit, since 1 and 2 read it. Then 1, 2 (the UDT arm, then the record and
column arms), 3 and 4. Slice 4 does not block 1 to 3, since opptak's slice writes no such payload.

## Tests

- **Slice 0:**
  - a fact document claiming the `code_method` forms and result classes of fixture holder methods,
    including an array's element;
  - a sis capture timed before and after.
- **Slice 1:**
  - sakila fixture routines returning one row of a table: a `VOLATILE` write that returns NULL on one
    input, and a `STABLE` read;
  - fact documents claiming the occurrence's producer result for both;
  - unit-tier tests (`testing.adoc`, unit tier) compiling and calling the emitted mutation call and the
    query's `FROM` and `LATERAL` forms;
  - execution tests: the mutation's payload read off the record, a nested field fetched after the call
    seeing the routine's change, the all-null record, and the query in one statement at the root and
    under a parent.
- **Slice 2:**
  - a fixture composite key type and routines taking its array, a table record array and a key column
    array;
  - fact documents for the `sql_udt` rows and the bindings;
  - `defects/routine-node-id-slot` keeping only the author mismatch;
  - unit-tier and execution tests for each arm.
- **Slice 3:** the execution test above.
- **Slice 4:** a defect document with the interface and the table-only interface as a write's data
  field, claiming the one code and no `KEY_TABLE_WITHOUT_PRIMARY_KEY`, plus its DML twin if that shares the
  gap.

## Open questions

- **opptak's utfylling data model** may change how kontekster reach the routine, and so the shape of
  slice 2's parameter. That is Alf's ruling.
- **The returned row and read policy.** Today a write's data field is re-read under the caller's
  identity, so row-level security can hide the row the routine just wrote (`routine.adoc`, the nullable
  data field). Reading the payload off the returned record skips that read policy. opptak's routines
  are `SECURITY DEFINER`. Alf decides whether that is accepted.
- **`RETURNS TABLE(...)` writes.** With `SETOF <table>` served as rows of `T`, name matching is left only
  for `RETURNS TABLE(...)`. Are routine writes over those still wanted? If not, the name-matched write
  and its reader of `sql_name_matched_key_column` could go, as a deliberate breaking change. A recommends
  keeping them. That is Alf's and Filip's decision.

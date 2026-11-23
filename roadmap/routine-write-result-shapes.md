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
plpgsql routine, which changes one row and hands it back: `RETURNS kommunikasjon.utsending`, a table's
row type, neither `SETOF` nor `RETURNS TABLE`. It refuses with a SQLSTATE of its own per refusal. The
payload is `{ utsending: Utsending, errors: [UtsendingFeil] }`, a `@table` object type and the error
union. When this item lands, an author writes that mutation as a Mutation `@routine` and gets:

1. the routine called in the mutation's transaction, and the row it returns re-read by its key into
   the payload's data field, with the usual nested selection;
2. a list of node ids handed to the routine as one array parameter (`sakIder: [ID!]! @nodeId` into a
   `text[]`);
3. a refusal raised with a mapped SQLSTATE answered as its `@error` member in `errors`, the write
   rolled back, and an unmapped one staying a top-level error, shown by an execution test;
4. a payload data field the write cannot re-read yet, a `@table` `@discriminate` interface, refused
   with a stored defect naming that shape, where the capture crashes today.

Scalar and void routines and procedures stay out. They keep their deferral, and they are the
remainder of this item's original scope once opptak's shapes have landed.

## Today

A Mutation `@routine` works only over a table-valued function. The function sits in the statement's
`FROM`, the write takes the key of its anchor table out of the function's columns by name
(`sql_name_matched_key_column`), and the frame and `LiftQuery` re-read the payload after the
statement (`RoutineWriteQuery`, `RoutineWriteEmitter`).

jOOQ's Postgres reader counts a function as table-valued only when it is `proretset`, so a routine
returning one row of a table has no `sql_table` row. `JooqCatalog.executableRoutineFacts` reads it
like the outbox routine, as a `Configuration`-first `Routines` method, into `sql_routine` and
`sql_routine_parameter`. It records whether a value comes back, but not what the value is.

## Model

### 1. A routine returning one row of a table

**Capture.** A new `sql_` relation, owned by the catalog gatherer, keyed by `sql_routine`'s key, with
a foreign key to `sql_table`. Its grain is "this routine returns one row of this table". The table is
read at capture from the generated routine's return parameter: its type is the table's generated
record class, which names its table. This is a subtype, not nullable columns on `sql_routine`. The
other return forms, a scalar `DataType` and OUT parameters, would each add more nullable columns
there (`fact-model.adoc`, the entry-facts section). The name is a contents label off jOOQ's own word,
`sql_routine_return_table`, since "result" already names two other relations. This relation is the
first slice of the Mikado node "A routine is enriched by the database construct behind it", and that
node is updated to say so.

**Derive.** The ROUTINE arm of `graphitron_occurrence_producer_result_rule` reads the new relation
through `graphitron_occurrence_producer_routine`. A routine returning one row of `T` produces a
single record of `T`, the same as a `@service` returning `T`'s record. The payload's data field then
lifts through the existing re-fetch and selection arms of `graphitron_occurrence_producer_lift_rule`,
which already admit a routine parent, and the key is the lift's `key_constraint`, decided once. No
name matching is involved, and `graphitron_routine_result` stays a read-side relation. This is the
Mikado node "A routine's result is an object of its function's record class" for the write's case,
"one returning a bound type gives a row of that table".

**Emit.** `RoutineWriteQuery` gains the arm for this producer. `RoutineWriteEmitter` calls
`Routines.<method>(configuration, ...)` in the mutation's transaction and takes the returned record's
key, from the lift's key constraint. A `NULL` composite, all key columns `NULL`, is no row, and the
data field is then `null`. The re-read is unchanged. The parameters are filled as today, through
`graphitron_occurrence_routine_parameter_rule` over `sql_routine_parameter`, which already describes
the `Configuration`-first method.

**Defects.** A routine returning another table's row than the data field's `@table` reads along the
field's join path, as a service's record does. Where no path exists, the existing mismatch defects
apply. A routine that returns a scalar, nothing, or a composite that is no table keeps its stored
UNSUPPORTED deferral.

### 2. A list of node ids at a routine parameter

The list is decoded id by id into the single key column of the node type, and the parameter
receives an array of that column's Java type. `sql_routine_parameter.binding_type` must be that array
type, or the existing parameter-type mismatch fires. This retires the list arm of
`ArgmappingProjectionDefects.unemittableProjections` for the routine site instead of adding to it. That
arm belongs to the Mikado node "The argMapping readers read the landing, and the candidate and match
go", which is E's. The emitter reads the landing (`graphitron_argmapping_landing_key_column`).

`ROUTINE_NODE_ID_LIST_UNSUPPORTED` narrows to a composite key: a list of ids whose node type has
several key columns has no one array to hand over. Its statement, its guidance, and the `@routine`
reference page (`docs/manual/reference/directives/routine.adoc`, "A list of node ids") change in the
same commit.

### 3. A refusal raised in the routine

This slice is an execution test in `graphitron-sakila-example` over a fixture routine that raises
two SQLSTATEs. One is mapped by a `DATABASE` `@error` handler on the payload's union, and it lands as
that member in `errors`, with the row the routine changed before the raise not committed. The other
is unmapped and stays a top-level GraphQL error. If the test fails, the fix lands in this slice.

### 4. A `@discriminate` interface as a routine write's data field

Today two arms of `graphitron_occurrence_producer_lift_rule` both yield a row for a ROUTINE parent,
and the capture dies on `graphitron_occurrence_producer_lift`'s primary key. A table-only interface
draws `KEY_TABLE_WITHOUT_PRIMARY_KEY`, which is untrue. The fix makes each arm's eligibility follow
from a stored fact it reads, the parent's produced shape, rather than adding more mutual `NOT EXISTS`.
The shape is then refused with one stored UNSUPPORTED code. The code names the mechanism, a write
payload's data field the write cannot re-read, and is not specific to a routine. It covers a DML
write's payload too if that has the same gap, which is checked first. It replaces the misleading
code rather than standing beside it, and it comes with its guidance.

## Order

Slices 1, 2, 3, 4, opptak's priority. Slice 4 is a regression (`defects-as-facts.adoc`, "Adding a
defect"), so it lands as its own commit. opptak's slice writes no such payload, so it does not block
1 to 3.

## Tests

- **Slice 1:**
  - a sakila fixture routine returning one row of a table;
  - a fact document claiming its `sql_routine_return_table` row and the occurrence's
    producer-result and lift rows;
  - a unit-tier test (`testing.adoc`, unit tier) that compiles the emitted `Routines` call and runs it;
  - an execution test re-reading the returned row with a nested selection, plus the `NULL` composite.
- **Slice 2:**
  - the `defects/routine-node-id-slot` document moves its list case to the composite key;
  - a scenario binding `[ID!]! @nodeId` to an array parameter;
  - a unit-tier test of the decode into the array;
  - an execution test.
- **Slice 3:** the execution test above.
- **Slice 4:**
  - a defect document with the interface and the table-only interface as a routine write's data
    field, claiming the one code and no `KEY_TABLE_WITHOUT_PRIMARY_KEY`;
  - its DML twin if that shares the gap.

Settled (A, 2026-11-23): the composite-key list keeps its own code,
`ROUTINE_NODE_ID_LIST_UNSUPPORTED`, following the site-prefixed precedent. `BUILD_NODE_ID_LIST_UNSUPPORTED`
is about built records, a different mechanism.

## Open questions

- Once one-row returns are served, does anyone still need routine writes over table-valued
  functions? If not, the name-matched write and its reader of `sql_name_matched_key_column` could go,
  as a deliberate breaking change. That is for Alf and Filip.

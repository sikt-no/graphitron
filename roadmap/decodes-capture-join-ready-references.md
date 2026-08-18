---
id: R715
title: "The decodes capture references the catalog can be joined on"
status: Spec
bucket: architecture
priority: 3
theme: classification-model
depends-on: []
created: 2026-08-18
last-updated: 2026-08-18
---

# The decodes capture references the catalog can be joined on

The `graphitron_` family is where the SDL's stated intent is decoded. It is not where that intent is
composed with what the database and the classpath actually offer; that is the derived stratum's job.
Which means the decodes owe the composition layer a shape it can *join* on, and today they hand it a
string it has to take apart.

## The predicate that shows it

`graphitron_table.table_ref` is one column, and its comment says what is in it: "the name argument as
written (may carry a schema qualifier)". `sql_table` is keyed `(source_name, table_schema,
table_name)`. `intent_spelled_table` reconciles them like this:

```
JOIN store_graph_source m ON m.graph_name = s.graph_name
JOIN sql_table st ON st.source_name = m.source_name
 AND CASE WHEN POSITION('.' IN s.spelling) > 0
      THEN UPPER(st.table_schema) = UPPER(SUBSTRING(s.spelling FROM 1 FOR POSITION('.' IN s.spelling) - 1))
       AND UPPER(st.table_name)   = UPPER(SUBSTRING(s.spelling FROM POSITION('.' IN s.spelling) + 1))
      ELSE UPPER(st.table_name)   = UPPER(s.spelling)
      END
```

Two substrings, a position computed twice, and a case fold on both sides, all inside the join
predicate, so no index can serve it and the join scans `sql_table` once per distinct spelling. The
driving side is a six-way union over `graphitron_table`, the three `*_reference_step` relations,
`graphitron_mutation` and `graphitron_routine`, because each decode owns its own single-string column.

## Scale

29 of the 63 `graphitron_` relations carry a cross-corpus reference column, every one of them a single
string: `table_ref`, `key_ref`, `column_ref`, `name_ref`, `index_ref`, `routine_ref`, and the
`class_name` plus `method` pairs. Across the whole schema the views spend 30 `UPPER`, 12 `REPLACE`, 10
`POSITION` and 9 `SUBSTRING` calls reconciling shapes, and the catalog-facing ones are the reason.

## Implementation

### The split: seven columns the DDL already documents as qualifiable

These carry `schema.name` or a bare name, by their own column comments, and are the columns
`intent_spelled_table` takes apart on every read. Each becomes two columns, both as written, split
once by the decoder:

[cols="2,3"]
|===
| Column | What it names

| `graphitron_table.table_ref`
| the `@table(name:)` target

| `graphitron_routine.routine_ref`
| the `@routine(name:)` target

| `graphitron_argument_reference_step.table_ref`
| a path element's table

| `graphitron_reference_for_step.table_ref`
| a path element's table

| `graphitron_field_reference_step.key_ref`
| a path element's constraint

| `graphitron_argument_reference_step.key_ref`
| a path element's constraint

| `graphitron_reference_for_step.key_ref`
| a path element's constraint
|===

### Settle first: two more columns the view splits but the comments do not admit

`graphitron_field_reference_step.table_ref` is commented "ReferenceElement.table as written" and
`graphitron_mutation.table_ref` "the DELETE write target as written", neither noting a qualifier,
yet both feed the `intent_spelled_table` union whose predicate splits on the first dot. So either the
comments under-document what an author may write, or the view handles a case that cannot occur.
Decide this before splitting, because it decides whether these two join the seven above or stay single
columns. Reading the `@reference` and `@mutation` reference pages, and the rejection detections around
them, should settle it without guessing.

### The fold: an ordinary column with a postfixed name and a comment

The catalog matches case-insensitively, so the composition predicate folds both sides. The fold moves
to capture as a plain sibling column named `<column>_upper`, whose DDL comment states exactly what it
holds and that it is the upper-cased form of the column beside it. No generated column: it buys
nothing here that a named column and a comment do not, and it would add an H2 feature to the schema's
surface for a cost that is not yet anybody's problem.

The columns that get one are the catalog-facing name halves: the seven split names above, plus
`field_binding.name_ref`, `argument_binding.name_ref`, `enum_value_binding.name_ref`,
`order_field.name_ref`, `default_order_field.name_ref`, `node_key_column.column_ref`,
`order.index_ref`, `default_order.index_ref` and `index.index_ref`.

Drift becomes impossible rather than merely discouraged, by a check constraint on each pair:

```
CHECK (table_ref_name_upper = UPPER(table_ref_name))
```

Verified against h2 2.4.240: the constraint is accepted, a consistent row inserts, a drifted row is
rejected, and a value bearing a quote round-trips. That is what makes the extra column safe to carry:
it cannot disagree with its sibling, so a reader may treat it as the same fact in a second spelling
rather than as a value to re-verify.

### Deliberately excluded, and why

- `graphitron_routine_column_mapping_pair.column_ref`, whose comment says "a dotted right side is
  captured and rejected by detection". A dot there is an author error the store is *meant* to hold, so
  splitting it would destroy the case the detection exists to report. This is the clearest instance of
  capture stating and detection judging, and it must survive untouched.
- `scalar_type.scalar_ref`, a Java constant reference, and the GraphQL-type references
  (`reference_for.participant_type_ref`, the two `node_type_ref` columns, `pivot.vocabulary_ref`).
  Same-corpus or Java-cased; neither splits nor folds.
- The `class_name` and `method` pairs. Java identifiers are case-sensitive and already fully
  qualified.

### The views stop taking strings apart

`intent_spelled_table` loses its `POSITION` and both `SUBSTRING` calls, and its `UPPER` pair becomes a
comparison of two stored columns, so the predicate can be served by an index instead of scanning
`sql_table` once per distinct spelling. The six-way union over the decode relations stays, per the
recorded decision below. Every other view reconciling a catalog reference gets the same treatment;
the schema-wide count to work down from is 30 `UPPER`, 10 `POSITION` and 9 `SUBSTRING`.

## Tests

- Pipeline tier: capture a qualified `@table(name: "public.film")` and an unqualified
  `@table(name: "film")` and pin both columns on each, including that the qualifier is NULL rather
  than empty on the unqualified one. Same for a `@reference` path element's key and for `@routine`.
- The check constraints are the invariant for the folded columns, so no test restates them; what does
  need a test is that the decoder writes the pair at all, which the pipeline pin above covers by
  asserting the `_upper` value.
- Agreement: `intent_spelled_table` returns the same rows before and after the reshape over the
  multi-schema fixture catalog, which is the whole point of the change being shape-only.
- A negative pin on the excluded column: a dotted `columnMapping` right side still lands in
  `graphitron_routine_column_mapping_pair.column_ref` whole and still reaches its detection.

## Notes settled during spec

**Nested classes are an unwritten invariant, not a live defect.** The concern was that
`graphitron_service.class_name` is documented "as written" while `jvm_class.class_name` is the binary
name, which differ for a nested class (`Foo.Bar` against `Foo$Bar`). Measured: every `className:`
value in the corpus names a top-level class, and none has two consecutive capitalised trailing
segments. So the join works today and nothing enforces that it keeps working. The fix is not a
nesting-specific rule but the ordinary unresolved-reference path: a `class_name` matching no
`jvm_class` should be visible as an absence rather than silently producing no row, which is the
three-arm discipline the fact model already prescribes. Filed separately rather than carried here.

## Relationship to the sibling items

This is where the payoff of the decode reclassification actually sits. The sibling item moving the
decodes off the AST and onto captured rows was justified partly on decoupling the `graphitron_`
population from the `graphql_` one, and that justification does not hold: both decode the same
document at the same cadence, so nothing decouples. The tier label stands as a description of what
the family *is*, and this item is the work that description was pointing at.

## Out of scope

- Resolving a reference to a catalog object. That is composition and stays in the derived stratum;
  this item only changes the shape composition reads.
- The `graphitron_` decodes' input (AST versus captured rows), which is the sibling item.
- The sub-grammar parsers, which decode strings whose contents are not catalog references.

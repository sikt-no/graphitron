---
id: R715
title: "The decodes capture references the catalog can be joined on"
status: Backlog
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

## What to change

**Split the qualifier at decode time.** A qualified `table_ref` becomes a qualifier column and a name
column, both as written, separated once by the decoder that already holds a parser rather than on
every read by a view. That removes every `POSITION` and `SUBSTRING` from the predicate. The same
treatment applies to the other catalog-facing references that can carry a qualifier
(`routine_ref`, `column_ref`).

**Do not capture the case-folded form.** Upper-casing is a function of the written value, so a second
base column holding it would fail the recompute test that decides tier one from tier two. The
instrument for this is a generated column the database maintains and an index over it: explicitly
derived, not captured, and it moves the fold out of the predicate and into the index. Getting this
distinction right matters more than the performance, because "store the normalised form beside the
written one" is exactly the shortcut that produced
`graphitron_field_synthesis.authored_type_sdl`.

**Leave the `class_name` + `method` pairs alone**, except for the defect below. Java identifiers are
case-sensitive and the values are already fully qualified, so those joins need no folding and no
splitting.

## A defect found while measuring, worth its own item

`graphitron_service.class_name` is documented as "the fully-qualified Java class name as written".
`jvm_class.class_name` is documented as "fully qualified binary name". For a nested class those are
different strings, `com.example.Foo.Bar` against `com.example.Foo$Bar`, and nothing in the schema
reconciles them. Either nested classes never appear as `@service` / `@condition` / `@record` targets,
in which case that is an invariant nobody wrote down, or the join silently misses them. Worth
establishing which before this item reshapes anything nearby, because the answer decides whether
`class_name` is join-ready or merely looks it.

## An open decision, cheaper to make before the reshape

The six-way union is itself composition, and it exists because each decode owns its own spelling
column. The alternative is that every decode also writes into one shared spelling relation keyed by
site, so composition reads a single relation. The recommendation is against it: the union costs little
next to the predicate, and keeping the spelling on the relation that decoded it preserves a locality
that a shared table trades away for a join. Recorded because it is a structural choice that is much
harder to revisit once the columns have been split.

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

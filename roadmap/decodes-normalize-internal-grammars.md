---
id: R715
title: "Capture decodes internal grammars into normalized relations"
status: Spec
bucket: architecture
priority: 3
theme: classification-model
depends-on: []
created: 2026-08-18
last-updated: 2026-08-18
---

# Capture decodes internal grammars into normalized relations

Several directive arguments carry a grammar inside a string: a schema-qualified name, an
`argMapping` pair list, a federation field set. Capture decodes each of them, because a grammar is a
parse boundary SQL cannot express. What it then does with the result is inconsistent: some grammars
land as normalized relations with an ordinal, and some are re-flattened into a string that every
reader has to take apart again.

The rule this item applies: **a decoded grammar lands in a normalized shape, with an ordinal wherever
order is part of the meaning.** The `graphitron_` family decodes stated intent; it is not where intent
is composed with the catalog or the classpath, so it owes the composition layer rows to join rather
than strings to parse.

## The audit, and it closes

Three parsers decode a grammar at capture. All three are dependency-free and already in the tree.

**Already normalized. These are the model, not the work:**

- `graphitron_service_arg_mapping_pair` and its siblings: `position` in the primary key, one row per
  pair, `param_name` and `argument_path` as columns. `GraphQLSelectionParser` output, normalized.
- `graphitron_service_arg_mapping_sigil`: `position`, `param_name`, `sigil`, with a `CHECK` pinning
  the sigil vocabulary. `ArgMappingSigil` output, normalized.
- `graphitron_argument_path_segment`: `position`, `segment_name`, one row per segment of an argument
  path. Already the shape a decoded path should have.

**Re-flattened into a string. This is the work:**

- **The dot grammar**, on nine `*_ref` columns. A qualified name is parsed by the pipeline and stored
  by capture as one string, so `intent_spelled_table` re-splits it on every read.
- **The field-set grammar's nesting.** `FieldSetGrammar`'s javadoc states it plainly: it decodes
  `@key(fields:)` into "the ordered dotted paths `graphitron_federation_key_field` stores", so
  `"a { b c }"` becomes `a.b` and `a.c`. The parser holds the nesting in a prefix deque and discards
  the structure at `qualify()`. `graphitron_federation_key_field` has a `position` for order among
  paths, which is right, and a `field_path` string for the path itself, which is not.

Nothing else in the family flattens a grammar result, so this list is complete rather than a first
instalment.

## The dot grammar

### One split rule, two different things being named

`parseQualifiedTableName` and `parseQualifiedForeignKeyName` are line-for-line identical, and the
javadoc says the second record exists only to keep the two "independently evolvable". Routine names go
through `parseQualifiedTableName` as well. So the *mechanics* are one rule and capture applies it
uniformly, rather than reproducing a per-resolver variant.

What the two halves *mean* is not one rule, and the columns and comments have to say which is which. A
table or routine qualifier names the schema the object lives in. A `key_ref` qualifier does not name
the constraint's schema, because a constraint has none of its own; see below. Same split, two
semantics, and conflating them in a comment is how a later reader writes the wrong join.

### The rule: always partition, never fall back

The verbatim input stays in its own column beside the two parts, and the split is unconditional.

[cols="2,1,1,2"]
|===
| Input | `schema_part` | name part | Reads as

| `film`
| NULL
| `film`
| no qualifier written

| `public.film`
| `public`
| `film`
| qualified

| `film.`
| `film`
| `''`
| a qualifier position was written, the name half is empty

| `.film`
| `''`
| `film`
| the qualifier position was written empty

| `a.b.c`
| `a`
| `b.c`
| qualified, name half not a legal identifier
|===

NULL and the empty string carry different meanings and both are load-bearing: NULL means no period
appeared, the empty string means a period appeared and that side of it was empty. A row with an empty
half joins nothing, and that is the point. The failure is structural, visible in the stored fact, and
needs no fallback rule to produce it.

This diverges deliberately from the pipeline, which treats a blank half as unqualified and passes the
raw string on as the bare name, degrading to a `NotInCatalog` rejection. Both arrangements fail on the
same input; the difference is that the store's failure is a non-match a reader can see the shape of,
where the pipeline's is a fallback that has to be known about. Capture states what was written and
lets the join say nothing matched, which is the same reason the store keeps a dotted
`columnMapping` right side whole for its detection instead of repairing it.

### `key_ref`'s dot is a scope hint, not a qualified name

Worth separating, because treating it as a schema-qualified name would be wrong about SQL. A
constraint is not an independently schema-namespaced object the way a table is: it is scoped to its
table, and therefore to that table's schema. The store already says so, since `sql_constraint` is
keyed `(source_name, table_schema, table_name, constraint_name)` and the schema arrives through the
table rather than beside the name.

So the dot in `@reference(key:)` does not qualify the constraint. It names *which schema's table holds
it*, a disambiguator for a constraint name that occurs in more than one schema, and the pipeline uses
it exactly that way in `findForeignKey(name, currentSourceSqlName, schema)`. The DDL already records
this ("the qualifier binds the FK-holder (child / referencing) schema, which is where jOOQ's generated
`Keys` class declares the constraint") and the new columns must not contradict it.

Consequences: the split still happens, because the grammar admits a dot and both halves are used. The
schema column is commented as the FK-holder table's schema, never as the constraint's schema. And its
join target is two columns of a four-column key, `sql_constraint(table_schema, constraint_name)`,
narrowed by the source table the walk is standing on, which is a different join from the table case
and should not be written as though it were the same one.

Routine names are the opposite case and take the table treatment unchanged: a routine *is* a
schema-qualified object, and the pipeline already parses `routine_ref` with
`parseQualifiedTableName`.

### The fold, on both sides

The fold exists for one purpose: matching case-insensitively against the `sql_` family. So both sides
get it, which is what makes the comparison an equality of two stored columns that an index can serve.

Each folded column is an ordinary column named `<column>_upper`, with a comment saying it is the
upper-cased form of the column beside it, and a `CHECK (<column>_upper = UPPER(<column>))` so it
cannot drift. Verified against h2 2.4.240: the constraint is accepted, a consistent row inserts, a
drifted row is rejected, a quote-bearing value round-trips.

On the `sql_` side, the columns to fold are the ones the composition views already compare
case-insensitively, counted from the current view text: `sql_table.table_schema`,
`sql_table.table_name`, `sql_column.table_name`, `sql_column.column_name`, `sql_column.jooq_name`,
`sql_constraint.table_schema` and `sql_constraint.constraint_name`. The rule for anything added later
is the same: a `sql_` column a composition view folds, folds in the schema instead.

No fold where the target namespace is case-sensitive. Java identifiers (`class_name`, `method`) and
GraphQL field names, including the field-set segments below, are compared exactly, and a folded
companion there would be dead weight a later reader would mistake for a matching rule.

Both parts keep their unfolded form as well as their folded one. The unfolded halves are what a
consumer echoing an author's spelling back wants, and re-deriving them from the verbatim column would
put the split in two places. So a qualifiable reference carries five columns: the verbatim value, two
parts as written, and two folded parts.

## The field-set grammar

`graphitron_federation_key_field.field_path` becomes segments in a child relation keyed
`(graph_name, type_name, ordinal, position, segment_position)`, or the existing row keeps its
`position` and a sibling relation carries `(…, position, segment_position, segment_name)`. Either way
the nesting the parser already computed is recorded rather than rendered, and a consumer asking "which
leaf fields does this key select, and under what parent" answers by joining instead of by splitting a
dotted string.

`FieldSetGrammar.paths` changes shape to return the segmented form; its prefix deque already holds
exactly what the new rows need, so this is surfacing state the parser has rather than parsing more.
Its tolerance contract is unchanged: a malformed field set still yields whatever prefix parsed and
still never throws.

No case fold: these are GraphQL field names.

## Deliberately excluded

`graphitron_routine_column_mapping_pair.column_ref`, whose comment says "a dotted right side is
captured and rejected by detection". A dot there is an author error the store is *meant* to hold so a
detection can report it, so splitting it would delete the case it exists for. This is the clearest
instance in the family of capture stating and detection judging, and it must survive untouched.

Also unchanged: `scalar_type.scalar_ref` (a Java constant reference), and the GraphQL-type references
(`reference_for.participant_type_ref`, both `node_type_ref` columns, `pivot.vocabulary_ref`), which
name things in the same corpus and carry no grammar.

## `graphitron_argument_path_segment` gains its coordinates

Today it is keyed `(graph_name, argument_path, position)`, so its rows are interned by path *text* for
the whole graph and a segment set has no owner. That is an oversight rather than a design: it limits
what the facts can answer. "Which argument paths does this field's service mapping segment into" cannot
be asked of the relation itself, only of a pair relation joined to it on a string.

The key gains the coordinate: `(graph_name, type_name, field_name, argument_path, position)`.

What that buys and what it does not, stated so the next reader does not expect more. It makes the
segments addressable by the coordinate every consumer already holds. It leaves segment sets shared
across the several pairs of one field that happen to use the same path text, which is harmless because
the segmentation of a path is a pure function of that text. It does not identify *which* pair a segment
set came from, and the owners are not one shape: `graphitron_service_arg_mapping_pair` is keyed
`(graph, type, field, position)`, the argument-level pairs add `argument_name`, and the step-level
pairs add `ordinal` and `step_position`. Carrying the widest of those would make a mostly-null key. A
consumer needing the exact owner joins the pair relation on `(type_name, field_name, argument_path)`,
which is a coordinate join rather than the bare string join it is today.

## Comment corrections shipping with this

- `graphitron_field_reference_step.table_ref` does not mention the qualifier, while
  `graphitron_argument_reference_step.table_ref`, the same SDL construct captured at another location,
  does. Traced: it resolves through `findForeignKeysBetweenTables`, which routes each argument through
  `findTable(String)`, so it is qualifiable and the comment under-documents.
- `graphitron_mutation.table_ref` reads "the DELETE write target as written" and is wrong twice. It
  resolves through `MutationInputResolver`'s rung 2 into `BuildContext.resolveTable`, documented as
  accepting qualified values, and `TABLE_ARG_SUPPORTED_VERBS` is `{DELETE, INSERT, UPDATE}`.

## Tests

- Pipeline tier, one case per arm of the partition table, on a `@table`, a `@reference` path element's
  table and key, a `@routine` and a `@mutation(table:)`: unqualified pinning a NULL schema part, and
  qualified, trailing-dot, leading-dot and multi-dot values pinning both parts. The trailing- and
  leading-dot cases are the ones worth writing first, since they pin the empty string against NULL,
  which is the distinction the whole arrangement rests on.
- One case proving an empty half joins nothing: a trailing-dot `@table` value yields no
  `intent_spelled_table` row rather than resolving as though unqualified.
- Pipeline tier for the field set: a nested `@key(fields: "a { b c }")` pinning two paths with their
  segments and positions, and a malformed field set pinning the parsed prefix.
- Pipeline tier for the segment coordinate: two fields on one type whose mappings share a path text,
  pinning that each field's segments are addressable from its own coordinate.
- The check constraints are the invariant for the folded columns on both sides, so no test restates
  them; the pipeline pins assert the folded value, which is what proves the writer fills the pair.
- A gate over the schema rather than a case: every `sql_` column a composition view folds has an
  `_upper` sibling, so the next folded comparison added to a view cannot quietly reintroduce a
  per-row `UPPER`.
- Agreement: `intent_spelled_table` returns identical rows over the multi-schema fixture catalog
  before and after, since this change is shape-only.
- A negative pin: a dotted `columnMapping` right side still lands whole in
  `graphitron_routine_column_mapping_pair.column_ref` and still reaches its detection.

## The views stop taking strings apart

`intent_spelled_table` loses its `POSITION` and both `SUBSTRING` calls and compares stored columns, so
an index can serve what currently scans `sql_table` once per distinct spelling. The six-way union over
the decode relations stays: it costs little next to the predicate, and keeping each spelling on the
relation that decoded it preserves locality a shared spelling table would trade for a join. Every
other view reconciling a reference gets the same treatment; the schema-wide budget to work down is 30
`UPPER`, 10 `POSITION` and 9 `SUBSTRING`.

## Notes settled during spec

**Nested classes are an unwritten invariant, not a live defect.** `graphitron_service.class_name` is
documented "as written" while `jvm_class.class_name` is the binary name, which differ for a nested
class (`Foo.Bar` against `Foo$Bar`). Measured: every `className:` value in the corpus names a
top-level class. So the join works today and nothing enforces that it keeps working. The remedy is not
a nesting rule but the ordinary unresolved-reference absence, filed separately.

## Relationship to the sibling items

This is where the payoff of the decode reclassification sits. The sibling item moving the decodes off
the AST onto captured rows was justified partly on decoupling the `graphitron_` population from the
`graphql_` one, and that does not hold: both decode the same document at the same cadence. The tier
label describes what the family is; this item is the work the description was pointing at.

## Out of scope

- Resolving a reference to a catalog object, which is composition and stays derived. This item only
  changes the shape composition reads.
- The decodes' input, AST versus captured rows, which is the sibling item.
- Identifying which *pair* a segment set came from. The coordinate reaches the field; the exact owner
  stays a join against the pair relation, for the reason recorded above.

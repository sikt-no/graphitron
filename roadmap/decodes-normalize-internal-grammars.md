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

### One grammar, not three

`parseQualifiedTableName` and `parseQualifiedForeignKeyName` are line-for-line identical, and the
javadoc says the second record exists only to keep the two "independently evolvable". Routine names
go through `parseQualifiedTableName` as well. So there is one split rule, and capture applies it
uniformly rather than reproducing a per-resolver variant.

### The rule has three arms, and the third is the one to get right

[cols="2,1,2"]
|===
| Input | `schema_part` | name part

| `film`
| NULL
| `film`

| `public.film`
| `public`
| `film`

| `film.` or `.film`
| NULL
| the whole value

| `a.b.c`
| `a`
| `b.c`
|===

The third arm is not a guess: both parsers return empty when either half is blank, and the author
sites then "treat a malformed value as unqualified (they pass the raw string on as the bare name), so
a stray-dot value degrades to the ordinary `NotInCatalog` rejection". A typo is exactly what produces
that input, so a capture that split it differently would put the store and the generator into
disagreement on the input an author is most likely to get wrong. The multi-dot arm is likewise the
pipeline's: `"a.b.c"` parses as schema `a`, name `b.c`, and the two-part lookup then finds nothing.

### Columns

Each of the nine keeps its as-written column and gains two: `<col>_schema_part`, NULL when the value
carries no usable qualifier, and the name half. The name half is `<col>_table_part` where it names a
table (`graphitron_table.table_ref`, `graphitron_mutation.table_ref`, the three
`*_reference_step.table_ref`) and `<col>_name_part` where it names something else (the three
`*_reference_step.key_ref` constraints, `graphitron_routine.routine_ref`), mirroring the pipeline's own
record components `QualifiedTableName.table` and `QualifiedForeignKeyName.name`.

### The fold is per target namespace, not general

The catalog matches case-insensitively, so the parts that join `sql_` gain a `<part>_upper` sibling:
an ordinary column, named for what it holds, with a comment saying it is the upper-cased form of the
column beside it, and a `CHECK (<part>_upper = UPPER(<part>))` so it cannot drift. Verified against
h2 2.4.240: the constraint is accepted, a consistent row inserts, a drifted row is rejected, a
quote-bearing value round-trips.

No fold where the target namespace is case-sensitive. Java identifiers (`class_name`, `method`) and
GraphQL field names (the field-set segments below) are compared exactly, and a folded companion there
would be dead weight that a future reader would mistake for a matching rule.

Open trim, worth deciding before writing the DDL: as specified this is five columns per reference (as
written, two parts, two folds) across nine references. Storing only the folded parts would make it
three, since the parts are re-derivable from the as-written column by the same one-line split. The
recommendation is to take the five-column form only if the unfolded parts have a reader; nothing
identified so far needs them.

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

## An open question the widened rule raises

`graphitron_argument_path_segment` is keyed `(graph_name, argument_path, position)`, so its rows are
interned by path *text* and a pair reaches its segments by joining on `argument_path` rather than by
owning them. That is normalization by value, and it is the only relation in the family keyed by a
value instead of a coordinate. It works, and interning avoids a row set per pair. But under the rule
this item states, the next reader will ask whether it is a design or an oversight, so it should be
either re-keyed on its owner or documented as deliberate in its DDL comment. Recommendation: keep the
interning, document it, and note that the graph-scoped clear is what currently keeps orphaned segments
from accumulating.

## Comment corrections shipping with this

- `graphitron_field_reference_step.table_ref` does not mention the qualifier, while
  `graphitron_argument_reference_step.table_ref`, the same SDL construct captured at another location,
  does. Traced: it resolves through `findForeignKeysBetweenTables`, which routes each argument through
  `findTable(String)`, so it is qualifiable and the comment under-documents.
- `graphitron_mutation.table_ref` reads "the DELETE write target as written" and is wrong twice. It
  resolves through `MutationInputResolver`'s rung 2 into `BuildContext.resolveTable`, documented as
  accepting qualified values, and `TABLE_ARG_SUPPORTED_VERBS` is `{DELETE, INSERT, UPDATE}`.

## Tests

- Pipeline tier, per grammar arm: qualified, unqualified, stray-dot and multi-dot values on a
  `@table`, a `@reference` path element's table and key, a `@routine` and a `@mutation(table:)`,
  pinning both parts including the NULL qualifier and the whole-value fallback.
- Pipeline tier for the field set: a nested `@key(fields: "a { b c }")` pinning two paths with their
  segments and positions, and a malformed field set pinning the parsed prefix.
- The check constraints are the invariant for the folded columns, so no test restates them; the
  pipeline pins assert the folded value, which is what proves the decoder writes the pair.
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
- Retiring or re-keying `graphitron_argument_path_segment`, beyond documenting the interning.

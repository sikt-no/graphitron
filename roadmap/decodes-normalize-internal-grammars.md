---
id: R715
title: "Capture decodes internal grammars into normalized relations"
status: Ready
bucket: architecture
priority: 3
theme: classification-model
depends-on: []
created: 2026-08-18
last-updated: 2026-08-19
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
  by capture as one string, so `intent_spelled_table` re-splits it on every read. The nine are
  `graphitron_table.table_ref`, `graphitron_mutation.table_ref`, `graphitron_routine.routine_ref`,
  and the `table_ref` and `key_ref` pair on each of `graphitron_field_reference_step`,
  `graphitron_argument_reference_step` and `graphitron_reference_for_step`. That is the six-way
  union `intent_spelled_table` already reads, plus the three `key_ref` columns
  `intent_field_reference_step_hop` splits by hand.
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
| Input | `namespace_part` | `name_part` | Reads as

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

### Columns: name the parse, not the meaning

Each of the nine qualifiable references keeps its verbatim column and gains four:

```
<col>                      the value as written                                    written
<col>_namespace_part       left of the first period, NULL when no period appeared  written
<col>_name_part            right of the first period, or the whole value when none written
<col>_namespace_part_upper folded, for the case-insensitive catalog match          generated
<col>_name_part_upper      folded, same                                            generated
```

The parts are written by capture and the folds are generated by the schema, and the line between
them is where the decision is. Splitting on a period is a decode: it is the rule this item is about,
it has to agree with the field-set grammar's Java on what a decode is, and it is what the partition
table below pins. Upper-casing decides nothing. Two mechanical points fall out and are worth
recording, because a reader will otherwise try the tidier-looking arrangement and find out the hard
way. First, h2 does not admit a generated column over another generated column
(`Column "NP" not found` on `np_upper AS UPPER(np)` where `np` is itself generated), so generating
the parts would force each fold to repeat the whole split expression, putting the grammar in the
schema nine times over. Second, generating the parts would move the split back into SQL, which is the
boundary this item exists to draw.

`namespace_part` rather than `schema_part`, and `name_part` rather than `table_part`, because the
qualifier's meaning is dialect-dependent where its syntax is not. A constraint name sits in the schema
namespace in Oracle and in the table namespace in PostgreSQL, so neither `schema` nor `table` is true
of `key_ref` in general. The same reasoning covers the columns where one dialect's answer happens to be
unambiguous today: what capture records is the result of splitting on a period, and a column named for
what that part is later interpreted *as* would be asserting something the parse does not know.

This adopts vocabulary the tree already uses for the distinction rather than minting a term.
`JooqCatalog` calls `parseQualifiedForeignKeyName` "the FK-namespace sibling of
{@link #parseQualifiedTableName}", contrasts it with "the jOOQ Java-constant namespace", and describes
FK matching as "dual-namespace"; the DDL carries the word through the hop and target views, where
`key_matched_by` is commented as making "the resolver's namespace precedence visible data", and
through the diagnostics stratum, where two comments turn on which of two namespaces minted a value.

The asymmetry with the `sql_` family is deliberate and worth stating, because it will read as an
inconsistency otherwise. `sql_table.table_schema` keeps its name: it is a catalog fact read from a
database that calls it a schema, not a piece of a parsed string. So a join reads
`namespace_part_upper = table_schema_upper`, and the mismatch across the equals sign is exactly the
boundary where a syntactic part becomes a semantic one. Naming both sides the same would hide that
boundary rather than clarify it.

### `key_ref`'s dot is a scope hint, not a qualified name

Worth separating, because treating it as a schema-qualified name would be wrong about SQL. A
constraint is not an independently schema-namespaced object the way a table is: it is scoped to its
table, and therefore to that table's schema. The store already says so, since `sql_constraint` is
keyed `(source_name, table_schema, table_name, constraint_name)` and the schema arrives through the
table rather than beside the name.

So the dot in `@reference(key:)` does not qualify the constraint. It names *which schema's table holds
it*, a disambiguator for a constraint name that occurs in more than one schema, and the pipeline uses
it exactly that way in `findForeignKey(name, currentSourceSqlName, schema)`.

This is already stated in three places, and the store is not one of them. The author-facing
declaration in `directives.graphqls` says "the qualifier binds the FK-holder (child / referencing)
schema"; the user manual's `@reference` page expands it to "the schema whose generated `Keys` class
declares the constraint, not the FK target's schema"; `JooqCatalog.parseQualifiedForeignKeyName` and
the scoped `findForeignKey` overload say it twice more in javadoc. The three `key_ref` comments in
the DDL say only "(may carry a schema qualifier)", which is true and stops one word short of the
distinction that matters. So `key_ref_namespace_part`'s comment is not a restatement of something the
schema already holds, it is the first time the store says it, and getting it right is load-bearing
rather than tidy: a reader who has only the DDL currently cannot tell this qualifier from a table's.

Which dialect decides *what* the namespace is, and that is the second reason the column cannot be
called `schema_part`: a constraint name lives in the schema namespace in Oracle and in the table
namespace in PostgreSQL. So `key_ref_namespace_part` holds a namespace whose kind the parse does not
determine, and its comment says which namespace the resolver currently reads it as rather than
asserting what the string is.

The split still happens, because the grammar admits a dot and both halves are used. Its join target is
two columns of a four-column key, `sql_constraint(table_schema, constraint_name)`, narrowed by the
source table the walk is standing on, which is a different join from the table case and must not be
written as though it were the same one.

Routine names are the clearer case and need no special handling: the pipeline already parses
`routine_ref` with `parseQualifiedTableName`, and the namespace a routine's qualifier names is the
schema in every dialect jOOQ models as one. Same columns, same split; only the comment differs.

### The fold, on both sides

The fold exists for one purpose: matching case-insensitively against the `sql_` family. So both sides
of every such comparison get it, which is what makes the comparison an equality of two stored columns
that an index can serve.

**A folded column is generated, not written.** Each one is
`<column>_upper VARCHAR GENERATED ALWAYS AS (UPPER(<column>))`, with a comment saying it is the
upper-cased form of the column beside it. Nothing writes it, nothing can write it, and no constraint
is needed to keep it honest, because the value is not a fact anybody states. Verified against h2
2.4.240: the column materializes, `CREATE INDEX` over it is accepted and the planner uses it
(`/* PUBLIC.IX: A_UPPER = 'ABC' */` on a 2000-row probe), NULL propagates to NULL so the unqualified
and empty-half rows need no special case, and an `INSERT` naming the column fails outright with
`Generated column "…_UPPER" cannot be assigned`.

That last property is the reason to prefer generation over a written column with a check constraint.
A written column has to be filled by every writer, which is a duty that can be forgotten in a
relation added later; a check constraint over it is the wrong shape of guard anyway, because
`CHECK (<column>_upper = UPPER(<column>))` evaluates to UNKNOWN when the folded half is NULL and a
`CHECK` admits UNKNOWN. Measured on h2 2.4.240: that constraint accepts the row `('Public', NULL)`,
which is exactly the drift it was meant to catch. `IS NOT DISTINCT FROM` fixes the null hole and
rejects the same row, so a written column is at least expressible; a generated column makes the
question moot and deletes the writer duty, the constraint and the test together.

**The property that makes generation safe is the property that breaks the current write path.**
`FactSink.flush()` builds one insert per relation as
`dsl.insertInto(table).columns(table.fields()).values(...)`, naming every column the DDL declares,
and binds it from `record.intoArray()`. Measured on h2 2.4.240, an insert that names a generated
column is rejected outright, so the first `_upper` column added to a captured relation breaks that
relation's whole write. Every relation this item folds goes through that path, the `sql_` ones
reaching the same sink through `CatalogFactCapture`. So this item carries the write-path change for
the relations it folds, which is its own section below. Nothing here argues for reopening the
written-column alternative as a way around that: a written fold column writes through the same sink
and inherits the drift the check-constraint measurement above already settles.

The rule, stated over comparisons rather than over columns, because that is what decides:

> **A column either side of a case-insensitive comparison in a composition view carries a folded
> companion.** Which family it belongs to does not enter into it.

Read off the current view text, that is:

[cols="2,3"]
|===
| Column | Folded at

| `sql_table.table_schema`, `sql_table.table_name`
| `intent_spelled_table`

| `sql_constraint.table_schema`, `sql_constraint.constraint_name`, `sql_constraint.jooq_name`
| `intent_field_reference_step_hop`

| `sql_constraint_column.column_name`, `sql_column.column_name`
| `intent_field_reference_step_hop`'s name-match arm, and `intent_name_matched_key_pair`

| `sql_column.jooq_name`, `sql_column.column_name`
| `intent_column_match_claim`

| `graphitron_table.type_name`
| `intent_spelled_table`, through the `COALESCE(table_ref, type_name)` fallback

| `graphitron_field_binding.name_ref`, `graphql_field.field_name`
| `intent_column_match_claim`, through the `COALESCE(fb.name_ref, f.field_name)` effective name

| the nine `*_ref` split parts
| every view reconciling a reference
|===

`sql_column.table_name` is not on that list and an earlier draft had it there: the views join it
exactly, not case-insensitively. The two `sql_constraint` entries and `sql_constraint_column` were
missing from that draft and are the reason the rule is now stated over comparisons: a list of columns
is a thing to keep in sync, and a rule about comparisons is checkable by reading a view.

**The left sides are the correction this section needed.** The parts columns were designed after the
folded columns, and the join between them was never re-read: a case-insensitive comparison has two
sides, and folding only the `sql_` one leaves a per-row `UPPER` on the other, which is the cost the
fold existed to remove. Three left sides matter, and two of them are values from case-sensitive
namespaces being *used as* SQL spellings, which is why the old "no fold where the namespace is
case-sensitive" phrasing gave the wrong answer:

- `graphitron_table.type_name` is a GraphQL type name, and `intent_spelled_table` matches it against
  `sql_table.table_name` case-insensitively wherever `@table(name:)` was omitted. It is a table
  spelling at that point, whatever namespace minted it.
- `graphitron_field_binding.name_ref` and `graphql_field.field_name` are a column reference and a
  GraphQL field name, and `intent_column_match_claim` matches whichever of them is effective against
  `sql_column.jooq_name` and `column_name`, case-insensitively, six times over.

The corrected rule stands on what the comparison does, not on what the namespace is: **a value
compared case-insensitively is folded, and a value compared exactly is not.** So the field-set
segments below still get no fold, and neither do `graphitron_service.class_name` or `method`, because
nothing compares those case-insensitively; the reason is now the comparison and not an appeal to
Java's or GraphQL's case rules, which were never the thing being asked about.

On the `COALESCE` pairs, be plain about what the fold buys where. Folding
`sql_column.jooq_name` makes the join's indexable side an ordinary column, which is the win. Folding
the `COALESCE` operands removes a per-row `UPPER` on the probe value and makes both sides of the
equals sign read the same way; it does not by itself produce an index, because a `COALESCE` over two
columns is not indexable either way. Both are worth doing and only the first is a plan change.

Both parts keep their unfolded form as well as their folded one. The unfolded halves are what a
consumer echoing an author's spelling back wants, and re-deriving them from the verbatim column would
put the split in two places. So a qualifiable reference carries five columns: three written (the
verbatim value and the two parts) and two generated.

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

No case fold, and by the corrected rule rather than the old one: nothing compares a field-set segment
case-insensitively, because a `@key` selection is matched against the GraphQL document and not
against the catalog. If a later view ever matches one against a column name, it folds then.

## Deliberately excluded

`graphitron_routine_column_mapping_pair.column_ref`, whose comment says "a dotted right side is
captured and rejected by detection". A dot there is an author error the store is *meant* to hold so a
detection can report it, so splitting it would delete the case it exists for. This is the clearest
instance in the family of capture stating and detection judging, and it must survive untouched.

Also unchanged: `scalar_type.scalar_ref` (a Java constant reference), and the GraphQL-type references
(`reference_for.participant_type_ref`, both `node_type_ref` columns, `pivot.vocabulary_ref`), which
name things in the same corpus and carry no grammar.

That accounts for fifteen of the family's twenty-four `*_ref` columns. The remaining nine name a
single object in a namespace the qualifier grammar does not reach, and are listed so the audit reads
as closed rather than as a sample: the three binding references (`graphitron_field_binding.name_ref`,
`graphitron_argument_binding.name_ref`, `graphitron_enum_value_binding.name_ref`), the four order
references (`graphitron_order.index_ref`, `graphitron_default_order.index_ref`,
`graphitron_order_field.name_ref`, `graphitron_default_order_field.name_ref`),
`graphitron_index.index_ref` and `graphitron_node_key_column.column_ref`. Each names a column or an
index within a table the site has already arrived at, so there is no qualifier position to write. One
of them, `graphitron_field_binding.name_ref`, does reach the fold, for the unrelated reason that a
view compares it case-insensitively; see the left sides below. The other eight are read by no view
today and so fold when one first folds them, under the same rule.

## `graphitron_argument_path_segment` gains its coordinates

Today it is keyed `(graph_name, argument_path, position)`, so its rows are interned by path *text* for
the whole graph and a segment set has no owner. "Which argument paths does this field's service
mapping segment into" cannot be asked of the relation itself, only of a pair relation joined to it on
a string.

The key gains the coordinate: `(graph_name, type_name, field_name, argument_path, position)`, and the
relation gains the foreign key that coordinate makes available.

One view reads the relation today, and it is worth naming because the re-key changes what its join
matches without changing what it returns. `intent_type_backing_seed` joins the segments on
`(graph_name, argument_path, position = 0)`, which today matches at most one row and afterwards
matches one per coordinate that spells the path. The extra rows are absorbed: the view's outer term
is a `UNION`, and none of its three output columns varies with which coordinate matched, because the
head segment of a path is a function of the path text. So the view is correct either way, which is
the same total-function argument this section makes for the duplication itself. The join still gains
`type_name` and `field_name`, because a join that is right by absorption is a join whose next editor
has to rediscover why.

### Why the recorded rationale for the current key is wrong

The table's own comment defends the interning, so this is a reversal and not a gap, and it has to be
argued rather than asserted. The comment says the site-keyed form "would be the repeating group
`sql_schema` exists to avoid, one value copied down every row that shares it", and that the path is
"keyed by the path itself, which is the only identity a value has". Three things are wrong with that.

**A repeating group is not what this is.** A repeating group is a first-normal-form violation:
several values of one attribute inside a single row, which is why the phrase is used correctly
elsewhere in the tree for an `N`-column key spread across a row. Copying a value down many rows under
distinct keys is a different thing entirely, a redundancy arising from a non-key functional
dependency, which is a second- and third-normal-form concern. The comment reaches for the 1NF word to
carry a 3NF worry, and the borrowed authority is doing the work the argument should be doing.

**The anomaly those forms exist to prevent cannot occur here.** Normalizing away a redundant value
protects against the update anomaly: two copies of an independently-updatable fact drifting apart
because one was changed. `argument_path, position → segment_name` is a total function of a column
sitting in the same row. There is nothing to update, so nothing can drift, so there is no anomaly to
be protected from. This item already accepts exactly that trade one section earlier: a folded column
is a derived duplicate of the column beside it, kept because its invariant is structural rather than
maintained. The segments are the same kind of duplicate, and if the trade is wrong here it is wrong
there too.

**The interning is an entity split with no entity.** `sql_schema`, which the comment cites as the
precedent, splits out a real thing: a schema exists in the catalog, has its own key, carries its own
attributes, and other relations reference it. The path-text analogue would be a
`graphitron_argument_path` relation declaring which paths a graph contains, with the segments and all
seven pair relations pointing at it. No such relation exists. What exists is a segment relation keyed
on a bare string that nothing in the store declares, with a foreign key to `store_graph` and to
nothing else. That is the shape that makes the objection collapse: the current form has no owner to
be constrained against, so it cannot express "these segments belong to something", cannot be cleaned
up when the last site spelling a path stops spelling it, and cannot answer any question at a
coordinate. The coordinate-keyed form has a foreign key to its owner, and a copy under a foreign key
is a copy the schema is holding on purpose.

The store's stated model says the same thing from the other direction. Authored facts are
definition-keyed, at the coordinate where the author's cursor sits, and each fact is an independent
functional dependency *of its coordinate*. A segment decode is an authored fact: it is what this
site wrote, decoded. Interning it by value is the one place in the family where an authored fact is
keyed by its own content instead of by where it was authored, and the exception is what makes it
unreachable.

The honest form of the redundancy worry is a question about scale, and the scale answers it: these
are the segments of an `argMapping` right-hand side, a handful of names per site, on a store holding
a dozen graphs. Trading a bounded, derived, foreign-keyed duplication for addressability along every
axis a consumer holds is the trade the fact model is built to make.

What that buys and what it does not, stated so the next reader does not expect more. It makes the
segments addressable by the coordinate every consumer already holds. It leaves segment sets shared
across the several pairs of one field that happen to use the same path text, which is harmless because
the segmentation of a path is a pure function of that text. It does not identify *which* pair a segment
set came from, and the seven owners are five key shapes:
`graphitron_service_arg_mapping_pair` and `graphitron_field_condition_arg_mapping_pair` are keyed
`(graph, type, field, position)`, `graphitron_argument_condition_arg_mapping_pair` adds
`argument_name`, `graphitron_routine_arg_mapping_pair` adds `ordinal`, and the three step-level pairs
add `ordinal` and `step_position` with the argument-site one adding `argument_name` on top. Carrying
the widest of those would make a mostly-null key. A consumer needing the exact owner joins the pair
relation on `(type_name, field_name, argument_path)`, which is a coordinate join rather than the bare
string join it is today.

All seven lead with `(graph_name, type_name, field_name)`, which is what makes the new key reachable
from every owner and gives the relation a foreign key target it does not have today. It anchors on
`graphql_field`, whose primary key is exactly that triple and which twenty-two relations already
reference, rather than on any one of the seven pair relations: the coordinate is what the owners
share, and picking one of them as the parent would be choosing a site for a fact that has several.
Today the relation's only foreign key is to `store_graph`, so a segment set whose path no site spells
any more is not merely unreferenced, it is unconstrainable.

## Comment corrections shipping with this

- `graphitron_field_reference_step.table_ref` does not mention the qualifier, while
  `graphitron_argument_reference_step.table_ref`, the same SDL construct captured at another location,
  does. Traced: it resolves through `findForeignKeysBetweenTables`, which routes each argument through
  `findTable(String)`, so it is qualifiable and the comment under-documents.
- `graphitron_mutation.table_ref` reads "the DELETE write target as written" and is wrong twice. It
  resolves through `MutationInputResolver`'s rung 2 into `BuildContext.resolveTable`, documented as
  accepting qualified values, and `TABLE_ARG_SUPPORTED_VERBS` is `{DELETE, INSERT, UPDATE}`.
- `graphitron_argument_path_segment`'s table comment argues for the keying this item reverses, so it
  is rewritten rather than amended: the repeating-group paragraph goes, replaced by what the new key
  says (segments of a path as one site decoded it, anchored on the coordinate, duplicated across
  sites by a total function of the path text). Its `argument_path` column comment, which reads "what
  lets a pair row reach its own decode without normalising either side", loses its point once the
  join is a coordinate join and gets the coordinate-join sentence instead. These are not cosmetic:
  leaving them would make the schema's own documentation contradict its keys, which is the failure
  mode the comment discipline exists to prevent.

## The write path states the columns it writes, for the twelve relations this touches

A generated column is a column no writer may name, and capture's writer names every column there is.
That is not a fold problem, it is a write-path problem the fold is the first to hit, and
`roadmap/capture-declares-the-columns-it-writes.md` already holds the full statement of it: an insert
should name the columns the writer has data for, and that list should be written rather than
reconstructed from the relation's shape at runtime. Its settled positions carry over unchanged, in
particular the one that matters here: **filtering `table.fields()` by jOOQ's readonly flag is not the
fix.** It swaps one inference from the DDL for another and leaves the statement assembled at runtime,
which is the thing being removed. So does moving a column list somewhere `flush()` consults.

That item is 123 relations and a 4,702-line capture layer. This one needs twelve of them, and takes
exactly those.

### Which twelve, and how small that is

Every relation this item puts a generated column on: `graphitron_table`, `graphitron_mutation`,
`graphitron_routine`, `graphitron_field_reference_step`, `graphitron_argument_reference_step`,
`graphitron_reference_for_step`, `graphitron_field_binding`, `graphql_field`, `sql_table`,
`sql_constraint`, `sql_constraint_column` and `sql_column`. Fourteen `newRecord` sites across the
twelve, eleven of them one site each and `graphql_field` three. The catalog four are the easiest of
the set, because `JooqCatalog.ColumnFacts`, `IndexFacts` and `ForeignKeyFacts` are already plain
records and `CatalogFactCapture` translates them field by field, so there the work is deleting a
translation rather than inventing one.

### The shape: written statements, dispatched in the order the sink already computes

Each of the twelve gains a write function issuing one bulk statement that names its columns in the
generated constants, so the column list is compile-checked and a column the writer has nothing for
cannot enter it, now or later.

The sink keeps its other three jobs and loses none of them. `claim()` still decides which occurrence
of a duplicated declaration wins, the graph stamp still lands in `add()`, and `parentsFirst` still
orders the write. What changes is only who renders the statement: where a relation has a write
function, `flush()` calls it with that relation's buffered rows instead of building an insert from
`table.fields()`. Everything else keeps the generic arm.

Dispatching rather than calling directly is load-bearing, not a hedge. The twelve interleave with
relations that stay generic on both sides: `graphql_field`'s parents `graphql_type` and
`graphql_type_declaration` are outside the set, `sql_table`'s parents `sql_schema` and `store_source`
are outside it, while `graphitron_field_binding`'s parent `graphql_field` and
`sql_constraint_column`'s parents `sql_column` and `sql_constraint` are inside. A slice that wrote
eagerly during the walk would insert a child before the sink flushed its parent. Ordering therefore
has to span both arms while both exist, and the sort that already computes it from the declared
foreign keys is the thing that spans them.

This is a partial migration and says so. The end state is the sibling item's: every relation written,
the caller ordering the calls, the generic arm deleted. Nothing here forecloses it, and the twelve
are the first twelve of the 123 rather than a different mechanism.

### Two things the conversion settles rather than carries over

**Conflict behaviour becomes stated.** `sharedFamily()` picks `onDuplicateKeyIgnore` by testing
whether a relation's name starts with `jvm_` or `sql_`, so the four catalog relations here currently
get their conflict rule from a naming convention. Their written statements name it, and the generic
arm keeps the prefix test for the relations still on it.

**The bulk property is a duty, not an inheritance.** `flush()`'s bind batch beats `batchInsert` by a
measured 1.8x (3.7 s to 2.1 s over a 207k-row census, per its own comment), and that margin comes
from one prepared statement per relation rather than a render per row. Each write function issues one
bulk statement, and the census load is measured before and after; `sql_column` and `graphql_field`
are the two of the twelve big enough for a regression to show.

### The gate, scoped to what exists

The sibling item's gate is what would have caught this collision at review rather than at h2's error
message, and the scoped form is worth having now: for each relation with a write function, assert
after a capture of the fixture corpus that the columns it names cover every `NOT NULL` column of the
relation. It ranges over the twelve today and over each relation the sibling converts after, so it
grows rather than being rewritten. One fact bounds the risk of getting a column list wrong meanwhile:
the DDL declares no `DEFAULT` on any column, so a column omitted from an insert and a column bound
null produce the same row, and no conversion here can change a captured fact through that difference.

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
  pinning that each field's segments are addressable from its own coordinate, and that the two
  segment sets are separate rows rather than one shared set.
- The write path: `FactCaptureAgreementTest` is the harness that already compares captured values
  against the catalog's and the scanner's own readings, so a mis-bound column in a converted
  statement fails there loudly, and it covers the wide relations that matter most here
  (`sql_column`, `graphql_field`). Add to it the column-coverage gate above, and one case pinning
  that a converted relation still writes under a generated column, which is the whole point and is
  the case that fails today.
- Nothing tests that a folded column holds the upper-cased form of the column beside it. Generation
  is the invariant, h2 rejects an `INSERT` that names the column at all, and a test over it would be
  testing the database. This is a deliberate subtraction from an earlier draft, which needed such a
  test precisely because it had capture write the folded columns.
- One case per left-side fold, because those are the ones a reader will doubt: a `@table`-less type
  whose type name differs in case from its catalog table still binds through
  `intent_spelled_table`, and a field whose `@field(name:)` differs in case from its column still
  claims `TABLE_COLUMN` through `intent_column_match_claim`. Both pass today through the per-row
  `UPPER` calls, so these pin behaviour the change must preserve rather than behaviour it adds.
- Agreement: `intent_spelled_table` returns identical rows over the multi-schema fixture catalog
  before and after, since this change is shape-only. Same pin on `intent_type_backing_seed`, the one
  view reading `graphitron_argument_path_segment`, whose join the re-key widens; the fixture needs
  two coordinates spelling one path for the pin to be worth anything, which the segment-coordinate
  case above already sets up.
- A negative pin: a dotted `columnMapping` right side still lands whole in
  `graphitron_routine_column_mapping_pair.column_ref` and still reaches its detection.

## The views stop taking strings apart

`intent_spelled_table` loses its `POSITION` and both `SUBSTRING` calls and compares stored columns, so
an index can serve what currently scans `sql_table` once per distinct spelling. Its
`COALESCE(table_ref, type_name)` fallback arm goes the same way rather than keeping one `UPPER`: with
`type_name` folded per the left-side rule, the arm reads
`COALESCE(t.table_ref_name_part_upper, t.type_name_upper)`, and the whole predicate is column against
column. The six-way union over the decode relations stays: it costs little next to the predicate, and
keeping each spelling on the relation that decoded it preserves locality a shared spelling table
would trade for a join. Every other view reconciling a reference gets the same treatment.

The schema holds 30 `UPPER`, 10 `POSITION` and 9 `SUBSTRING`, and the budget this item works down is
30, 8 and 5. The rest is not a remainder to get to later, it is string work that has nothing to do
with a spelling, and stating it now keeps the totals from reading as a promise they are not.
`intent_class_member_slot` spends four `SUBSTRING` on the bean-accessor rule, taking `get` or `is`
off a method name and lowering the next letter, which its own view comment defends as a rule over the
census rather than a match against anything. `intent_authored_field_claim` and
`intent_class_assignable` spend one `POSITION` each on a recursion cycle guard, testing whether a name
already appears in the path a recursive term has walked. Neither is a case fold nor a dot split, and
no column this item adds touches either.

Two of the 30 `UPPER` do come off, but not by folding a base column, which is worth separating for
the same reason. `intent_field_reference_discovery` compares
`UPPER(sc.table_name) <> UPPER(bt.table_name)` where both sides are columns of *derived views*, not
of `sql_` relations. Those come off only if the views underneath project the folded column alongside
the unfolded one, which they should, on the same rule: a view that carries a spelling carries its
fold. That is a propagation and not a new column, and it is the one part of the budget that is view
work rather than schema work.

**No build-time gate, because generation removes the thing a gate would police.** An earlier draft
proposed a schema gate asserting that every folded `sql_` column has an `_upper` sibling, which was a
guard against a writer forgetting to fill a written column. Generated columns cannot be forgotten and
cannot be filled wrong, so that gate has nothing left to check. What is worth checking is the
property the item is actually after, and it is simpler than the column-list gate and cannot drift
with it: **no `UPPER(` in a view definition.** One rule over the view text, no reference list to
maintain, and it fails on the next per-row case fold whoever adds it and whatever columns are
involved.

`UPPER` alone, and that is the correction the budget above forces rather than a weakening. A rule
naming `POSITION(` and `SUBSTRING(` too would have to carry an exemption for the bean-accessor rule
and the two cycle guards, which puts a list of blessed views back into a gate whose whole appeal was
having no list. `UPPER` is the operation this item is about, its reducible count and its total are
both 30, and a fold is the one string operation whose absence a stored column can guarantee. The
residual `POSITION` and `SUBSTRING` uses in those three views are then not exempted from a rule,
they are outside it. Whether the gate lands now or once the count reaches zero is an implementation call; a gate that
fails on day one is not useful, so it arrives with the last of the 30.

## Notes settled during spec

**Nested classes are an unwritten invariant, not a live defect.** `graphitron_service.class_name` is
documented "as written" while `jvm_class.class_name` is the binary name, which differ for a nested
class (`Foo.Bar` against `Foo$Bar`). Measured: every `className:` value in the corpus names a
top-level class. So the join works today and nothing enforces that it keeps working. The remedy is not
a nesting rule but the ordinary unresolved-reference absence, filed separately.

## Relationship to the sibling items

This is where the payoff of the decode reclassification sits. The sibling is
`roadmap/graphitron-decodes-read-rows-not-ast.md`, which moves the decodes off the AST onto captured
rows. It was justified partly on decoupling the `graphitron_` population from the `graphql_` one, and
that does not hold: both decode the same document at the same cadence. That item's own body now
records the same correction, so the two agree. The tier label describes what the family is; this item
is the work the description was pointing at.

Neither of those two depends on the other and neither declares the other. This one changes the shape
a decode lands in, that one changes what a decode reads from, and either order works.

`roadmap/capture-declares-the-columns-it-writes.md` is the third sibling and the one this item nearly
depended on. It states the write-path principle in full and carries it across 123 relations; the
generated `_upper` columns cannot land through a path that names every column of a relation, so on
the face of it this item waits for that one. It does not, because the twelve relations it needs are a
prefix of that work rather than a precondition for it. Folding them in costs fourteen write sites and
buys an unblocked item, so this item's `depends-on` is empty and the write-path section above says
what it takes.

The split is by relation and nothing is done twice: this one converts the twelve it folds columns
onto, that one converts the remaining 111 and deletes the generic arm once none is left. It keeps
everything this item does not touch, which is most of it: the plain-record gatherer layer, moving
`claim()` into the gather stage, handing the ordering to the caller, and the corpus-wide form of the
column-coverage gate. Its own body records that this item was the trigger that turned its collision
from hypothetical into real, and it now also records which twelve arrive early.

## Out of scope

- Resolving a reference to a catalog object, which is composition and stays derived. This item only
  changes the shape composition reads.
- The other 111 relations' writes, the plain-record gatherer layer, relocating `claim()` and the
  parents-first ordering, and deleting the generic arm of `flush()`. Twelve relations convert here
  because twelve is what the fold needs; the rest is the sibling item and is not made harder by
  taking them early.
- The decodes' input, AST versus captured rows, which is the sibling item.
- Identifying which *pair* a segment set came from. The coordinate reaches the field; the exact owner
  stays a join against the pair relation, for the reason recorded above.
- Declaring path text as an entity of its own. A `graphitron_argument_path` relation that the
  segments and all seven pair relations referenced would make value-interning legitimate, and it is
  the shape the current comment's argument actually calls for. It is not proposed here: it is more
  machinery for a value with no attributes beyond its own decode, and it would still leave "which
  paths does this field segment into" answerable only through the pair relations.

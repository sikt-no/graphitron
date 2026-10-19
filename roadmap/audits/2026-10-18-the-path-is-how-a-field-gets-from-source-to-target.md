# The path is how a field gets from its source to its target

Read 2026-10-18 on `66bd0f7b8`, from the DDL and from every reader of each relation, SQL views and
Java alike. Nothing was built or measured; the counts are the store's own definitions.

## The model

A field's source is its parent type's table and its target is its field type's table. Its path is
how it gets from one to the other, and its arguments condition the target. Three query shapes:

```
SELECT … FROM T1                                   -- root: no source, the query starts on the target
SELECT …, multiset(SELECT … FROM T2) FROM T1       -- child in an existing query: the source is T1
SELECT …, multiset(…) FROM (VALUES …) T1           -- child in a new query: the source's keys as values
```

Each row of the path says where it comes from, where it goes and how it joins. That is the whole
resolution, so the path carries it rather than a second relation beside it:

```sql
-- written, in the directive's words; spelling lives only here
graphitron_ast_reference_entry                (application → graphql_ast_directive_application_entry)
graphitron_ast_reference_element_entry        (element → graphql_ast_value_entry,
                                               application → graphitron_ast_reference_entry, position)
graphitron_ast_reference_key_step_entry       (element → graphitron_ast_reference_element_entry, key_ref …)
graphitron_ast_reference_table_step_entry     (element → graphitron_ast_reference_element_entry, table_ref …)
graphitron_ast_reference_condition_step_entry (element → graphitron_ast_reference_element_entry, class_name, method, argmapping)
graphitron_ast_reference_for_entry            (application → graphitron_ast_reference_entry, participant_type_ref)
graphitron_ast_routine_entry                  (application → graphql_ast_field_directive_entry, …)

-- the field's ends
graphitron_field_source_table (field, from_table → sql_table, basis)
graphitron_field_target       (field PK, target_type, wrapper)
graphitron_field_target_table (field, target_type → graphitron_field_target, basis)

-- the path, one row per written element and source
graphitron_field_path (field, source_table, position PK,
                       element → graphql_ast_entry,
                       from_table → sql_table,              -- where we come from
                       to_table   → sql_table,              -- where we go
                       constraint → sql_referential_constraint, fk_on_from   -- a foreign key, ridden one way
                       | method → code_condition_method                      -- a join method, given both tables
                       | function → sql_table)                               -- a function whose result is read
graphitron_field_participant_path (same, per participant)
```

**Each family keeps its own vocabulary.** `graphql_ast_` and `graphql_` speak GraphQL and
graphql-java; `graphitron_ast_` speaks the directives as users write them; `graphitron_` speaks our
own words, chosen for what the store is for rather than for the directives we happen to expose. A
`graphitron_` relation is not named after a directive and neither are its columns or their values,
though its comment and the prose explaining it name the directives freely, since that is what makes
it readable. So the path's joins are a foreign key, a join method and a function, not a key step, a
condition step and a routine, and a `graphitron_` derivation reads what a directive said from its
`graphitron_ast_` entry, where that meaning was captured, rather than from the directive's name. An element naming a table joins by the one key between where the walk stands and the named
table, so it fills the foreign-key columns, and "table" stays the entry's word for how it was
written. A written element the walk
cannot resolve is still a row, with nothing past its element, so a broken path is a defect at its
position. The last row's `to_table` is the target type's table, or the field has a defect; no
relation is needed for that check.

**The grain includes the source.** A field can have several: `FilmDetails` and `MoreDetails` on
sakila are reached from `film` and from `customer`, so every field under them has two sources, and
a path written there is walked once from each. Keyed by field and position alone, two walks would
collide. This needs its own look before the path's key changes.

**Spelling becomes the actual key in the path's own derivation.** The walk joins each element's
entry to the catalog from the table it stands at: a key spelling to `sql_constraint`, then
`sql_constraint_hop` from here; a table spelling to `sql_constraint_hop` from here to that table; a
method to its signature's tables. Every input is a stored table, so the recursion is cheap; the
942 ms the two-sided rule took was a view expanded in every round.

## What stays

* **The entries**, as merged in `66bd0f7b8`: the application, every element at its index, the
  three step relations as the element's subtypes, the `@referenceFor` participant, and the routine
  entry and its column-mapping pairs.
* **The catalog**: `sql_constraint`, `sql_referential_constraint`, `sql_constraint_hop`,
  `sql_table`, `sql_name_matched_key_column` (a routine result's key matched by name), and
  `code_condition_method` with `code_method_parameter`.
* **The ends**: `graphitron_field_target`, `graphitron_field_target_table`.

## What changes

* **`graphitron_field_path`** gains the three facts and, with them, the source in its key. Today
  `GraphitronAnchor` writes it from the entries alone, before anything resolves; the walk writes it
  after the sources, in written order, so the anchor's path writing moves into the walk. Readers:
  `graphitron_entry_defect_rule`, `graphitron_field_source_table_rule`, the reading.
* **`graphitron_field_participant_path`**, the same, per participant. No reader today.
* **`graphitron_field_source_table`** stops reading the path, which would otherwise be a cycle once
  the path is derived after it. Its `INHERITED` arm asks whether the field reaching a type wrote a
  path, which the step and routine entries answer; its `SOURCE_ROW` arm reads the first key's table
  off the reading, which the key entry and `sql_constraint` answer, and it finds its fields by
  filtering `graphql_directive_application` on `sourceRow`, where the `@sourceRow` entry already
  states it.
* **`graphitron_entry_defect_rule`** reads the path's columns for its chain codes, where it reads
  the reading, the resolution and the target tables today. It also asks two directive names, whether
  a field carries `@routine` (through `graphitron_field_path_application`) and `@pivot` (through
  `graphql_directive_application`); both questions are the `graphitron_ast_` entries' to answer.
* **`graphitron_field_column_scope_rule`** and **`graphitron_node_id_instruction_rule`** read the
  path where they read `graphitron_field_reference_step_target` and the decode twin
  `graphitron_field_reference_step_entry`.
* **The editor's `ClaimFacts` and the MCP server's `SchemaQueries`** read the path where they read
  `graphitron_field_reference_step_target`, and so see the 38 sakila fields that walk misses.

## What goes, at the field grain

| Relation | What it was | Its readers, which move |
|---|---|---|
| `graphitron_field_table_link`, its rule, `FieldTableLinks` | the path resolved per target type, beside the path | none |
| `graphitron_field_path_reading` | every interpretation of every link, a view | the resolution rule, the source rule, the entry defects |
| `graphitron_field_path_resolution`, its four arm tables, its rule, `FieldPathResolutions` | the two-sided walk | the entry defects, the table link rule |
| `graphitron_field_reference_step_hop`, its two arm tables, `FieldReferenceStepHops` | every move an element could make, keyed by the decode coordinate | the field and input-field walks, `intent_field_path_node` |
| `graphitron_field_reference_step_target`, its two arm tables, `FieldReferenceStepTargets` | the forward walk from the parent's own table only | column scope, node-id instruction, `intent_field_reference_step_fanout`, `ClaimFacts`, `SchemaQueries` |
| `intent_field_reference_step_fanout` | the step target's fan-out | `ReferencePathFanout` |
| `graphitron_condition_method_route` | a method's route keyed by the method as spelled | the hop, `intent_argument_reference_step_hop` |
| `graphitron_field_path_application`, `FieldPathApplications` | a field's applications in written order, its key and its values the directives' names | `intent_field_path_node`, the entry defects' `@routine` question |

That is two register lines closed at once: "Two resolutions of an authored element" (the hop and
the reading) and, with the readers below, much of "Read-time path views".

## What goes with it, as readers follow

* **`intent_field_path_start`, `_node`, `_terminus`**, the read-time path views. Readers:
  `intent_mutation_routine_seat`, `intent_routine_return_binding`, `RoutineWriteFacts`. The routine
  write reads the path's rows instead; this is the register's "Read-time path views" line.
* **The coordinate-keyed decode twins** lose most of their readers: the hop, the condition route,
  column scope and node-id instruction read `graphitron_field_reference_step_entry` today. What is
  left is `FactWrites`, `RoutineWriteFacts`, `intent_mutation_routine_seat`, `intent_field_column_table`
  and the node-id landing defect. They stay under the "Entry twins" line.
* **`graphitron_spelled_table`** stops being read by the path; it is still read by
  `graphitron_field_scope_table_rule`, `intent_bound_table` and four other `intent_` views, so it
  stays for now.

## At the argument and input-field grains, the same shape later

An argument's path and an input field's path are filters: each starts at a field's target, and an
element's order is its value entry's position in the one path. The relations that walk them today
take the same fall once those grains have a path of their own:

* `graphitron_argument_reference_step_target` and its arms, `ArgumentReferenceStepTargets`, and
  `intent_argument_reference_step_hop`;
* `graphitron_input_field_reference_step_target` and its arms, `InputFieldReferenceStepTargets`;
* `graphitron_argument_scope_table` and `graphitron_field_scope_table`, which state where an
  argument's path starts, from the dissolving `intent_field_navigated_type`;
* `graphitron_input_field_resolving_table`, `intent_input_occurrence_path` and its steps, which state
  an input field's use sites. An input field is walked once per use, so its start is the use's
  field target, and that is the use site the graph already names as unmodelled.

Their readers are the node-id decode family (`graphitron_node_id_instruction_rule`,
`graphitron_node_id_decode_hop_rule`, `intent_node_id_decode_endpoint` and the decode defects), which
the node-id branch is already flipping onto the target type; the two pieces of work meet there.

## Counted

At the field grain 21 relations go, 10 of them stored tables, with their 5 Java writers
(`FieldTableLinks`, `FieldPathResolutions`, `FieldReferenceStepHops`, `FieldReferenceStepTargets`,
`FieldPathApplications`): the table link and its rule, the reading, the resolution with its four
arms, its view and its rule, the hop with its two arms and its view, the step target with its two
arms and its view, the fan-out, the condition route, the application order, and the three
read-time path views. The application order's position is the path's own: the path is every
element in written order across every application, so the order of applications is that order
grouped by holder. At the other two grains 15 more
follow the same way once their paths are stated, rules counted.

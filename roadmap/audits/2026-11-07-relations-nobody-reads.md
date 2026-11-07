# Relations nobody reads: a census of graphitron-model.sql

A working document, not a roadmap item; it lives in `audits/` so the roadmap-tool ignores it. It
records how the store's relations were counted for readers on trunk `79bd636f41` (2026-11-07), what
the count found, and where the count is known to be wrong. The decisions live in the R876 graph
node "Relations nobody reads go".

## Why

The DDL declared 333 tables and 152 views on that trunk, about five times what the model's questions
need. The census asks which relations any main code reads, so the ones nothing reads can go.

## Method

Two censuses, each blind where the other sees.

1. **Static.** For every relation, the main Java sources across all modules were scanned with
   comments stripped. A relation (or a variable assigned it) in a `from`, `join` or similar position
   is a read; in an `insertInto`, `deleteFrom`, `update` or `mergeInto` position a write; a relation
   named in a raw SQL string counts as read. Views read what their bodies name, and a relation is
   live when main code reads it or a live view does.
2. **Runtime.** A spike listener, never on trunk, appended every distinct statement the store ran
   during a full `mvn clean install` to a file per process, with the first calling class outside
   jOOQ and the store's own boot and sink classes. Statements from test classes (by name against
   every `src/test/java` tree, nested classes included) and from JUnit and Surefire frames were
   dropped, as were per-table maintenance sweeps (`ALTER`, emptiness checks, whole-table counts).
   A read is a relation in a `FROM`, `JOIN` or `USING` position, a delete's own target excepted;
   views were closed over as in the static census.

The runtime census sees only what the build exercises; the static census misses a read made through
a helper handed the table as a parameter. Neither counts the model's own readers well: a subtype
whose foreign key references a relation, and a view or rule view naming it, the static pass's view
bodies having been cut short at the first semicolon inside one. So every lead is also checked
against the DDL, statement by statement with comments stripped, for a `REFERENCES` naming it and a
view body naming it, before it is deleted. The lists below are after that check (2026-11-07).

## What it found

Of 449 relations, 56 were unread by both. Checked by hand, they fall into four groups.

### 1. Unread, deleted (2026-11-07)

`intent_node_container_member`, `intent_record_slot_assignable`, `sql_constraint_hop`. Their only
consumers were fact-document assertions on the views themselves.

### 2. Capture written twice and read by neither copy

No main code, view or subtype reads these:

- the `@error` capture: `graphitron_ast_error_entry`, `graphitron_ast_error_handler_entry`,
  `graphitron_ast_error_database_handler_entry`, `graphitron_ast_error_generic_handler_entry`, a
  family whose only readers are each other, and `graphitron_error_entry`, whose only reader is its
  subtype `graphitron_error_handler_entry`;
- `graphitron_ast_discriminate_entry`, `graphitron_ast_discriminator_entry`,
  `graphitron_ast_enum_entry`, `graphitron_ast_order_entry`, `graphitron_ast_order_field_entry`,
  `graphitron_ast_index_entry`;
- `graphitron_ast_field_condition_entry`, `graphitron_ast_field_condition_context_arg_entry`,
  `graphitron_ast_input_value_condition_context_arg_entry`,
  `graphitron_ast_service_context_arg_entry`;
- `graphitron_external_field_entry`, `graphitron_pivot_entry`,
  `graphitron_default_order_field_entry`, `graphitron_split_query_entry`,
  `graphitron_tenant_fan_out_entry`, `graphitron_multitable_reference_entry`,
  `graphitron_argument_binding_entry`, `graphitron_argument_reference_entry`.

Each family needs a named reader or goes, checked that nothing else carries its fact.

Went (2026-11-07): the eight decode copies in the last item, and the three context-argument
entries, `graphitron_call_context_key_written` reading a context key off the generic
`graphql_ast_` entries. The argument-site `@reference` decode's claim, which decided whether a path
step's method reference is written, is keyed on `graphitron_method_reference_entry` now, as the
`@service` one is. Stayed, as the store's only copy of what they capture: `graphitron_ast_pivot_entry`,
`graphitron_ast_external_field_entry` and `graphitron_ast_default_order_field_entry`, the twins of
the copies that went, read by nothing yet.

Stayed, with the reader each waits for named in the graph: `graphitron_ast_discriminate_entry` and
`graphitron_ast_discriminator_entry`, `graphitron_ast_enum_entry`, `graphitron_ast_order_entry`,
`graphitron_ast_order_field_entry`, `graphitron_ast_index_entry` and
`graphitron_ast_field_condition_entry`, each the store's only typed copy of what the generator still reads
off the schema.

The `@error` decode went too, `graphitron_error_entry` and `graphitron_error_handler_entry`: the
language server's type hover read the handler rows, and reads `graphitron_ast_error_handler_entry`
and `graphitron_ast_error_generic_handler_entry` instead, which gives the `@error` capture its reader.

### 3. New-model relations written ahead of their readers

`graphitron_occurrence_parameter`, `graphitron_occurrence_source_object`,
`graphitron_occurrence_producer_key_row`, `graphitron_occurrence_producer_write`,
`graphitron_entry_defect_method`. Each wants its reader named in the graph.

### 4. Settings and catalog facts written and never read

`store_graph_supergraph`, `store_graph_tenant_column`, `store_graph_lint_disabled_rule`,
`sql_enum_binding`, `code_throwable_supertype`, `federation_version_directive_location`, and
`sql_table_record_supertype`, whose one reader is `graphitron_entry_defect_member_rule`, itself
unread. The generator may read the same settings off its configuration rather than the store; each
is checked before it goes.

### Read after all

The DDL check moved these out of the lists the censuses gave:

- read by `graphitron_entry_defect_rule`: `graphitron_service_entry`, `graphitron_scalar_type_entry`,
  `graphitron_argument_lookup_key_entry` (gone 2026-11-07, its rule reading the applications),
  `graphitron_ast_input_value_condition_entry`,
  `sql_node_metadata_unreadable`, `code_method_exception`;
- supertypes whose subtypes are read: `graphitron_ast_reference_entry`, `graphitron_ast_link_entry`,
  `graphql_ast_schema_definition_entry`, `graphql_directive_argument_element`,
  `graphql_enum_value_element`, `graphitron_occurrence_binding`, `store_graph_dependency`;
- `graphitron_fetcher`, keyed into by its subtypes `graphitron_producer` and `graphitron_reader` and
  read by a rule of `graphitron_entry_defect_rule`.

## Where the census was wrong

Four of the seven relations first put in group 1 are in use, and the DDL check above moved fourteen
more out of the other groups, which is why every lead is checked:

- `intent_poly_member` is read by `BuildContext` on a path the build does not exercise;
- `graphitron_occurrence_call_parameter_defect` is read through `graphitron_entry_defect_rule`,
  whose body the static scan cut short at a semicolon inside it;
- `graphitron_runtime_class` and `graphitron_runtime_member` are vocabularies other relations
  reference by foreign key, which neither census counts as a read;
- `graphitron_accessor_path` is a declared subtype of `graphitron_path`, waiting for its writer.

Two earlier runtime passes counted almost everything as read: test harnesses sweep every relation
(row counts, `@expectEquals` selects, agreement checks), and a test-jar class (`ThreadConfinedStore`)
was taken for main code until callers were classified by the test source trees.

---
id: R1173
title: "A column that renders other columns of its row is generated, so no writer spells it and no check holds it"
status: Spec
bucket: architecture
priority: 6
theme: model-cleanup
depends-on: []
created: 2026-11-11
last-updated: 2026-11-12
---

# A column that renders other columns of its row is generated, so no writer spells it and no check holds it

## Goal

Every column that is a function of other columns in its own row is computed by the database, `coordinate VARCHAR GENERATED ALWAYS AS (type_name || '.' || field_name)` and its kin, instead of written by the code that fills the relation and held to the other columns by a `CHECK`. This carries out the rule `fact-model.adoc` states under "The key discipline" (ruled 2026-11-12): the store applies a rendering once, rather than every writer spelling it and a check refusing the one that got it wrong. When this lands, the twenty `CHECK`s in scope below are gone, no writer names the columns they held, `CoordinateSpellingGateTest` is deleted, and a new gate fails the build on a `CHECK` that equates a column with an expression over its row's other columns, so the shape cannot come back. Nothing an author or consumer sees changes; every row reads back exactly as before.

## Scope

The scope was found by scanning every `CREATE TABLE` in `graphitron-model.sql` for a `CHECK` that equates a column with an expression over other columns of the same row, and for coordinate columns with no check. Of the 151 matching checks, about 120 are the `x_ref IS NULL OR x_ref = x` twin the ruling exempts, and the biconditionals (`(kind = 'INSERT') = (matches IS NULL)` and its kin) tie a column's presence to a kind rather than computing it. What is left is below.

**Coordinates.** Eleven relations, each with the expression its column becomes. Nine carry a `CHECK` today; `graphitron_type` carries the same spelling with no check at all, which is weaker than its siblings; `graphitron_key_field_binding` renders two of its own non-key columns.

[cols="2,3,1"]
|===
| Relation | `coordinate` becomes | Check today

| `graphql_type_element` | `type_name` | yes
| `graphql_field_element` | `type_name \|\| '.' \|\| field_name` | yes
| `graphql_argument_element` | `type_name \|\| '.' \|\| field_name \|\| '(' \|\| argument_name \|\| ':)'` | yes
| `graphql_enum_value_element` | `type_name \|\| '.' \|\| value_name` | yes
| `graphql_directive_element` | `'@' \|\| directive_name` | yes
| `graphql_directive_argument_element` | `'@' \|\| directive_name \|\| '(' \|\| argument_name \|\| ':)'` | yes
| `graphitron_type` | `type_name` | no
| `graphitron_field` | as `graphql_field_element` | yes
| `graphitron_enum_value` | as `graphql_enum_value_element` | yes
| `graphitron_argument` | as `graphql_argument_element` | yes
| `graphitron_key_field_binding` | `type_name \|\| '.' \|\| field_name` | yes
|===

**Other renderings.** Ten more columns, none in a primary key, one of them deleted rather than generated; three are the referencing side of a foreign key, and `call` sits under a `UNIQUE`, which the evidence below covers.

[cols="3,2,3"]
|===
| Column | Becomes | Keys it sits in

| `is_list` on `graphql_field`, `graphql_argument`, `graphql_directive_argument`, `graphql_ast_field_definition_entry`, `graphql_ast_field_argument_entry`, `graphql_ast_input_field_entry`, `graphql_ast_directive_argument_entry` | `list_depth > 0` | none
| `graphitron_occurrence_condition.call` | `'condition:' \|\| bound` | `UNIQUE (graph_name, coordinate, call)`, and the referencing side of `FOREIGN KEY (graph_name, coordinate, call, call_kind)`
| `graphitron_occurrence_call_parameter_pair.call_coordinate` | deleted, see below | the referencing side of the foreign key into `graphitron_argmapping_landing`, at that table's `call_coordinate`
| `graphitron_occurrence_binding_member.class_name` | `SUBSTRING(bound, 1, POSITION('#', bound) - 1)` | the referencing side of two foreign keys
|===

`call_coordinate` is a copy of the row's own `coordinate` with nothing else in it: `CHECK (call_coordinate = coordinate)` holds it equal in every row today, so naming `coordinate` in the foreign key references exactly the rows it references now. A foreign key's referencing columns need not share names with the ones they reference, so the foreign key names `coordinate` in that position and the column goes, which is one column fewer than generating it; its writer in `OccurrenceCallParameters` stops writing it, its rule view `graphitron_occurrence_call_parameter_pair_rule` stops projecting it, and any reader the compiler finds reads `coordinate`. If an implementer finds a reader that genuinely needs the name, a generated column is the fallback, and the spec should be told.

`graphitron_occurrence_binding_member` turns its check around: today `bound` is checked to begin with `class_name`, and since `bound` is the key and carries the member name too, the prefix is what is generated. `graphitron_occurrence.parent_coordinate` and `graphitron_ast_argmapping_pair_entry`'s `root_name` and `tail_name` already parse a column this way. The old check also guaranteed that `bound` carries a `#`; that guarantee stays as `CHECK (POSITION('#', bound) > 0)`, a shape check the gate passes, so a `bound` without one is refused at the write rather than given a garbage `class_name`.

Each expression is the `CHECK` it replaces, character for character, or for `class_name` its inverse, so every row the store holds today is a row it holds after.

Out of scope: the `bound` columns held by `CHECK (bound = table_schema || '.' || table_name)` on `graphitron_occurrence_node_id`, `graphitron_occurrence_column`, `graphitron_occurrence_binding_table` and `graphitron_key_field_binding`. Each `bound` sits in its relation's primary key or in a foreign key into `graphitron_occurrence_binding`'s, so making it generated is a question about a key built from a rendering, the open question the supertypes raise below and which the ruling leaves unsettled. The new gate exempts them by name, with a ceiling of four, so they cannot grow while the question is open.

## Evidence

* H2 2.5.250, which the store runs on, accepts a generated column under `UNIQUE (graph_name, coordinate)` and as the referencing side of `FOREIGN KEY (graph_name, coordinate) REFERENCES graphql_element ... ON DELETE CASCADE`. It refuses an explicit write to the column (error 90154), and `MERGE ... KEY (graph_name, type_name, field_name)` works. Checked with a scratch program on 2026-11-11.
* The store already has generated columns that writers leave out (`graphitron_field.bound_name_upper` and the `_upper` family), and `graphitron_occurrence.parent_coordinate`, generated, is already the referencing side of a foreign key.
* `CoordinateSpellingGateTest` captures a schema only to supply foreign-key parents, then shows that H2 refuses a row the check forbids. Every fact document's capture already proves that the writers spell the coordinate legally.

## Plan

One commit; the DDL change and its writers cannot land apart, since a writer naming a generated column fails on H2's 90154.

1. **DDL**, `graphitron-model.sql`. Each column in the two scope tables becomes generated with the expression shown, and its `CHECK` and the check's explanatory comment go; `call_coordinate` is dropped and its foreign key names `coordinate`. Each column's `COMMENT ON COLUMN` keeps its meaning and gains "Generated, so nothing writes it", as `bound_name_upper` says; the coordinate comments that say it is "written by the same call ... from the same string" are rewritten, the writer no longer being the one keeping them equal.
2. **Writers stop naming the columns.**
   * `GraphQLAstCapture`, the six anchor writers (`typeElements`, `fieldElements`, `enumValueElements`, `directiveElements`, `directiveArgumentElements`, `argumentElements`): `COORDINATE` leaves `.columns(...)`, `ef.COORDINATE` leaves the select list, and the `.set(t.COORDINATE, excluded(t.COORDINATE))` arm goes. The join to `graphql_ast_element_entry` stays: the touched filter `touched(graph, ef.COORDINATE)` needs it, and running it before the anchor row exists is the reason it reads the entry. The comment on `fieldElements`'s `onConflict` that credits the `CHECK` with keeping jOOQ's key-or-coordinate `MERGE` from matching on the coordinate alone is rewritten: the coordinate is now a function of the key by construction.
   * `MacroExpansion`: the `graphitron_type`, `graphitron_field`, `graphitron_enum_value` and `graphitron_argument` inserts and their `excluded` arms.
   * `FederationVocabulary`: the `graphitron_type` insert.
   * `EntityKeyAlternatives`: the `graphitron_key_field_binding` insert stops copying `r.COORDINATE`. `graphitron_key_field_binding_rule` keeps projecting it; a rule view may say more than the table stores.
   * `is_list`: the four entry writers in `GraphQLAstEntries` stop binding `val(isList(...), t.IS_LIST)` and its `excluded` arm, and `GraphQLAstEntries.isList` goes if nothing else calls it; the `graphql_field`, `graphql_argument` and `graphql_directive_argument` writers in `GraphQLAstCapture` stop copying it from the entries.
   * `OccurrenceBuilds.writeMembers`: the `graphitron_occurrence_binding_member` insert stops copying `r.CLASS_NAME`.
   * `OccurrenceCallParameters`: the pair insert stops writing `call_coordinate`, and `graphitron_occurrence_call_parameter_pair_rule` stops projecting it.
   * `PersistentStoreTest`: the raw `INSERT INTO graphql_type_element` drops the column.
   * Any other writer the compiler or the first capture turns up gets the same treatment; a missed one fails loudly on 90154.
3. **The store says which columns are generated, once, and both generic writers ask it.** `OccurrenceProducers.refresh` writes `graphitron_occurrence_condition`, among others, by copying every field of the target table from the same-named field of the rule view, so it would write `call`. The generated jOOQ classes cannot say which columns to skip: `FactSink.writtenColumns`' javadoc records that the code generator models H2's computed columns as ordinary fields. A small helper beside `FactSink` reads `INFORMATION_SCHEMA.COLUMNS` (`IS_GENERATED = 'ALWAYS'`) once per store and answers, per table, which columns are generated. `refresh` leaves those out of both its insert and its update; `FactSink.writtenColumns` leaves them out too, so its `touched` inference and the catalog cannot give two answers to one question (the reason `StoreCatalog`'s javadoc gives for answering a question one way). Reading it once per store rather than per `refresh` call keeps a catalog query off every depth of every stage. The rule view's projection is not the answer: that would let a view's shape decide what a table stores.
4. **`CoordinateSpellingGateTest` is deleted.** Its unique-coordinate refusal for an enum value (the one case not reducible to the check) needs no successor: the coordinate is now a function of the key, and an injective one, since a GraphQL name cannot contain `.`, `(`, `:` or `@`, so a second row at one coordinate is a second row at one key, which the primary key refuses.
5. **A gate refuses the shape.** `RenderedColumnGateTest`, beside `CollectionValuedColumnGateTest` in `graphitron-model` and reading the authored DDL text through the same splitter, for the same reason: the authored `CHECK` is the construct to refuse, and the splitter already blanks comments and string-literal contents. Per `CREATE TABLE`, it collects the table's column names, then fails on any check that is a single equation with a bare column of the table on either side and an expression naming another column of the table on the other, `CHECK (coordinate = type_name || '.' || field_name)` and `CHECK (type_name || '.' || field_name = coordinate)` alike, naming the table and the check and saying to generate the column instead. Only two shapes inside a disjunction are let through, each pinned by its form rather than by being a disjunction, since an `OR` wrapped around a rendering would otherwise hide it: the twin `x_ref IS NULL OR x_ref = x`, which a foreign-key action must be able to clear, and the conditional equality `slot_method_name IS NOT NULL OR value_class = source_class` on `graphitron_occurrence_binding_value`. Any other check containing such an equation fails. A biconditional `(a = ...) = (b ...)` has no bare column on either side and so never matches; it ties presence to a kind and computes nothing. The four `bound` checks the ruling leaves open are exempted by (table, column), not by table, since `graphitron_key_field_binding` holds both a converted `coordinate` and an exempt `bound`; the list carries a ceiling of four, which an exempt check leaving lowers and nothing raises without a ruling. A floor assertion, as in its sibling, that the scan saw `CREATE TABLE` text, keeps it from passing vacuously.
6. **The ruling names its enforcer.** The section of `fact-model.adoc` holding the ruling ends with two enforcer lines today, a "Not yet mechanically enforced" for the ruling and the key discipline's "Enforced by". They become one "Enforced by", as every other section of the page has: it gains `RenderedColumnGateTest` with its (table, column) exemption, and the subtype-to-supertype foreign key as what holds a supertype's written coordinate to the subtype's generated one, so a writer that spells a supertype coordinate differently from the expression fails that foreign key on every fact document rather than drifting.
7. **R1160's note** that `CoordinateSpellingGateTest` "dissolves instead under R1173" is left as it stands; it becomes true when this lands.

## What stays spelled, and what holds it

Two spellings remain and should. Capture spells `graphql_ast_element_entry.coordinate`, and the supertype rows `graphql_element` and `graphitron_element` key on it; the DDL expression spells each subtype's generated column. The subtype's `FOREIGN KEY (graph_name, coordinate) REFERENCES graphql_element` (or `graphitron_element`) ties them together. Every relation in the table has that foreign key today; `graphitron_key_field_binding` instead references `graphitron_occurrence_binding (graph_name, coordinate, bound, kind)`, which holds its coordinate to an occurrence just as firmly.

## Interactions

* R876's open node "The occurrence source is written once per depth" records the ruling that `graphitron_type` becomes the strict supertype, written first after the anchor. It will reshape `graphitron_type`'s writer; a generated coordinate makes that writer smaller and constrains nothing about it, so neither waits on the other.
* `ordinals-follow-the-row-number` (Backlog) touches the same `GraphQLAstCapture` writers. Whichever lands second rebases over a smaller diff.
* `SeededStore` writes anchors only through `MacroExpansion.derive`, the production call, so it follows without change.

## Tests

* The whole fact-document population is the acceptance test for the conversion: every capture writes all of the relations in scope, and every subtype row's foreign key into its supertype holds only if the generated expression matches capture's spelling. A mismatched argument grammar (`(arg:)`) fails every document with an argument. The same population exercises `is_list`, `call` and `class_name` through their foreign keys and the rules that read them.
* `GraphQLAnchorTest` and `ConfiguredNoteTest` already read the coordinate back from these relations and pin its values; they stay unchanged and must stay green.
* `FactSchemaGateTest`'s comment-coverage rule covers the rewritten column comments, on the terms `bound_name_upper` already meets.
* `RenderedColumnGateTest` is the evidence that the shape cannot come back. It gets a self-test in its sibling's manner: the scan run over a small inline DDL string fails a `CHECK (coordinate = type_name || '.' || field_name)`, fails the same check written the other way round and wrapped in an unrelated `OR`, passes the twin, the pinned conditional equality and a biconditional, and passes an exempt `bound` check only while the exemption names its table and column, failing a coordinate check on the same table. Green on the tree at the end of this item, with exactly four exemptions, is what shows the twenty checks are gone.
* No test of a refused row is added for the converted columns: there is no longer a wrong row to refuse.

## Retired vocabulary

* `CoordinateSpellingGateTest`.
* "The CHECK that the coordinate spells the key" and "the coordinate is the key spelled out" as a justification for a check, in SQL and Java comments.
* `call_coordinate` on `graphitron_occurrence_call_parameter_pair` and its rule view.
* The "Not yet mechanically enforced" line for the generated-column rule in `fact-model.adoc`.

## Owed, not done here

* The four `bound` columns the gate exempts, if the open question below is answered in favour of rendered keys; the exemption list's ceiling then falls to zero.
* Writers that build a rendering in Java into a column that has no check were not swept: the scan found unchecked columns only by name (`graphitron_type.coordinate`). The gate cannot see that shape either; review does.
* For Alf and Filip: `graphql_element` and `graphitron_element` key on the rendered coordinate itself, which sits awkwardly with "never the key" in "The key discipline". No page says whether a cross-grain supertype keyed by its rendering is the sanctioned exception. This item does not depend on the answer.

## Architect consultation

Consulted at pickup. It agreed with including `graphitron_type` and `graphitron_enum_value`; taken. It called excluding `graphitron_key_field_binding.coordinate` and the `bound` columns thin; taken for `graphitron_key_field_binding.coordinate`, whose coordinate is not in a key, and declined for `bound`, which is, with the reason stated under Scope. It said the entry join in `GraphQLAstCapture` does not dissolve; agreed, step 2. It asked that the foreign key be named as the enforcer and the key discipline say so; step 6 and "What stays spelled". It flagged the R876 node and the Backlog ordinals item; recorded under Interactions. It raised the supertype-keying question; recorded under Owed.

Consulted again at the widening, on the ruling of 2026-11-12. It agreed with dropping `call_coordinate` provided the copy is exact in every row; the existing check shows it is, recorded under Scope. It said reading the catalog inside `refresh` would give the question of generated columns a second answer beside `FactSink`'s `touched` inference; taken, step 3 now has one helper read once per store that both writers ask. It found three holes in the gate's predicate (a reversed equation, any disjunction passing, a per-table exemption covering a converted column on the same table); all three closed in step 5. It asked that `class_name`'s generated parse not hide a `bound` with no `#`; a shape check keeps that refusal. On `fact-model.adoc` it asked that the open question read "in a key" rather than "in a primary key", that the paragraph not assert a supertype keyed by its rendering as settled, and that the two enforcer lines become one; the first two are fixed in the paragraph, the third is step 6.

## Reviewer findings

### Round 1: Spec → Ready (session_01WkW2JJ265QAF8dWXrmJ62C, 2026-11-12)

Verdict: withhold, on step 3 alone. The goal reads cleanly on its own: nothing an author or consumer sees changes, and the store stops holding twenty renderings to their rows with checks that every writer must satisfy. It is viable as stated. All twenty `CHECK`s are in `graphitron-model.sql` on the relations and in the forms the two scope tables give, the four exempt `bound` checks and the pinned conditional equality are where the spec says, and a scan of every single-line and multi-line `CHECK` finds no other column-equals-expression-over-its-row shape the new gate would trip on. Every class, method, test and doc section the body names exists as named. One finding, on question two.

**Finding 1 (question two: fit with the architecture). Step 3's helper is under-specified in a way the implementer would have to redesign.** Three parts, one decision.

* *Where it lives.* The store already has a home for "a property of a relation asked of the database because the generated classes cannot say": `GraphPartition` in `no.sikt.graphitron.model.catalog`, whose javadoc gives this spec's own reason (the codegen model cannot answer, so ask `INFORMATION_SCHEMA.COLUMNS`) and its own cost rule ("one query per caller per pass, not one per relation"), beside `StoreCatalog`, which the spec cites for answering a question one way. A helper "beside `FactSink`" in the sink package is a second catalog reader standing beside that shape rather than extending it. Say where the predicate lives and why, and if it is not the catalog package, why not.
* *"Once per store" has no seam to hang on.* `OccurrenceProducers.refresh` is static over a `DSLContext`; nothing in the model holds store-scoped state it could reach (`GraphitronModelStore` creates the schema once per store but hands nothing down). Read once per store therefore means inventing a cache keyed on something, and the plan does not say on what or where it is held. The choices differ (per store, per JVM given the DDL is fixed, per pass as `GraphPartition` does, or computed at schema creation and carried), and choosing among them is design, not implementation. Name one.
* *`FactSink.writtenColumns` already gives the right answer, by a documented design.* Its javadoc says it derives the written set from the rows' touched fields so that a column the database computes is left out by construction and "nothing has to be kept in step with the DDL". Filtering generated columns there changes its behaviour only when a row touched a generated column, which today H2 refuses with 90154, the very loud failure step 2 relies on ("a missed one fails loudly"); after step 3 that writer's value would be dropped silently. The generic arm writes none of the relations in scope. The two do not answer one question: `writtenColumns` answers what the rows set, `refresh` answers what it may copy from a rule view, and only the second needs the catalog. Either leave `FactSink` alone and say why that is not a second answer, or say why giving up the loud failure is worth it.

What would satisfy it: step 3 names the predicate's home, the scope and holder of its cache, and states `FactSink`'s part consistently with its existing javadoc and with step 2's reliance on 90154.

**Not blocking.**

* `graphitron_key_field_binding.coordinate` is described as one of "its own non-key columns" (Scope) and as "not in a key" (Architect consultation), but it is the referencing side of `FOREIGN KEY (graph_name, coordinate, bound, kind) REFERENCES graphitron_occurrence_binding`, the same foreign key that is half the stated reason for exempting that table's `bound`. Generating it is fine under the ruling (a generated column may be the referencing side of a foreign key), as it is for `call` and `class_name`, so the plan is not wrong; but the exemption criterion for `bound` ("in its relation's primary key or in a foreign key into `graphitron_occurrence_binding`'s") then exempts `graphitron_key_field_binding.bound` for a reason the item does not apply to the coordinate beside it. Worth stating the criterion as the one the plan actually uses.

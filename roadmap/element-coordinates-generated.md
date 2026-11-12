---
id: R1173
title: "An element anchor's coordinate is a generated column, so no writer spells it and no check holds it to the key"
status: Spec
bucket: architecture
priority: 6
theme: model-cleanup
depends-on: []
created: 2026-11-11
last-updated: 2026-11-12
---

# An element anchor's coordinate is a generated column, so no writer spells it and no check holds it to the key

## Goal

Every relation whose `coordinate` column is a rendering of columns stored beside it has that column computed by the database, `coordinate VARCHAR GENERATED ALWAYS AS (type_name || '.' || field_name)` and its siblings, instead of written by the code that fills the relation and held to the other columns by a `CHECK`. A coordinate is a rendered value of the key (`fact-model.adoc`, "The key discipline"), so the store can carry the rule rather than code restating it. When this lands, nine `CHECK (coordinate = ...)` clauses are gone, no writer of those relations names the column, and `CoordinateSpellingGateTest` is deleted: the rule can no longer be broken, so nothing has to test that a broken row is refused. Nothing an author or consumer sees changes; the rows read back exactly as before.

## Scope

Eleven relations, each with the expression its column becomes. The first nine carry a `CHECK` today; `graphitron_type` carries the same spelling with no check at all, which is weaker than its siblings; `graphitron_key_field_binding` renders two of its own non-key columns.

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

Each expression is the `CHECK` it replaces, character for character, so every row the store holds today is a row it holds after.

Out of scope: the `bound` columns held by `CHECK (bound = table_schema || '.' || table_name)` on `graphitron_occurrence_node_id`, `graphitron_occurrence_column`, `graphitron_occurrence_binding_table` and `graphitron_key_field_binding`, and `graphitron_occurrence_condition.call`. Each `bound` sits in its relation's primary key or in a foreign key into `graphitron_occurrence_binding`'s, so making it generated is a question about a key built from a rendering, the same open question the supertypes raise below, not this item's mechanical move. `graphitron_occurrence_binding_member`'s check is not a rendering at all.

## Evidence

* H2 2.5.250, which the store runs on, accepts a generated column under `UNIQUE (graph_name, coordinate)` and as the referencing side of `FOREIGN KEY (graph_name, coordinate) REFERENCES graphql_element ... ON DELETE CASCADE`. It refuses an explicit write to the column (error 90154), and `MERGE ... KEY (graph_name, type_name, field_name)` works. Checked with a scratch program on 2026-11-11.
* The store already has generated columns that writers leave out (`graphitron_field.bound_name_upper` and the `_upper` family), and `graphitron_occurrence.parent_coordinate`, generated, is already the referencing side of a foreign key.
* `CoordinateSpellingGateTest` captures a schema only to supply foreign-key parents, then shows that H2 refuses a row the check forbids. Every fact document's capture already proves that the writers spell the coordinate legally.

## Plan

One commit; the DDL change and its writers cannot land apart, since a writer naming a generated column fails on H2's 90154.

1. **DDL**, `graphitron-model.sql`. For each relation in the table above, the column becomes generated with the expression shown, and the `CHECK` and its explanatory comment go. The column's `COMMENT ON COLUMN` keeps its meaning and gains "Generated, so nothing writes it", as `bound_name_upper` says; the sentences that say the coordinate is "written by the same call ... from the same string" are rewritten, the writer no longer being the one keeping them equal.
2. **Writers stop naming the column.**
   * `GraphQLAstCapture`, the six anchor writers (`typeElements`, `fieldElements`, `enumValueElements`, `directiveElements`, `directiveArgumentElements`, `argumentElements`): `COORDINATE` leaves `.columns(...)`, `ef.COORDINATE` leaves the select list, and the `.set(t.COORDINATE, excluded(t.COORDINATE))` arm goes. The join to `graphql_ast_element_entry` stays: the touched filter `touched(graph, ef.COORDINATE)` needs it, and running it before the anchor row exists is the reason it reads the entry. The comment on `fieldElements`'s `onConflict` that credits the `CHECK` with keeping jOOQ's key-or-coordinate `MERGE` from matching on the coordinate alone is rewritten: the coordinate is now a function of the key by construction.
   * `MacroExpansion`: the `graphitron_type`, `graphitron_field`, `graphitron_enum_value` and `graphitron_argument` inserts and their `excluded` arms.
   * `FederationVocabulary`: the `graphitron_type` insert.
   * `EntityKeyAlternatives`: the `graphitron_key_field_binding` insert stops copying `r.COORDINATE`. `graphitron_key_field_binding_rule` keeps projecting it; a rule view may say more than the table stores.
   * `PersistentStoreTest`: the raw `INSERT INTO graphql_type_element` drops the column.
   * Any other writer the compiler or the first capture turns up gets the same treatment; a missed one fails loudly on 90154.
3. **`CoordinateSpellingGateTest` is deleted.** Its unique-coordinate refusal for an enum value (the one case not reducible to the check) needs no successor: the coordinate is now a function of the key, and an injective one, since a GraphQL name cannot contain `.`, `(`, `:` or `@`, so a second row at one coordinate is a second row at one key, which the primary key refuses.
4. **The key discipline names its new enforcer.** In `docs/architecture/explanation/fact-model.adoc`, "The key discipline" gains a sentence and an "Enforced by" entry: a subtype's coordinate is generated from its key columns, and its foreign key into `graphql_element` or `graphitron_element` is what holds the supertype's spelling, written by capture, to the subtype's. A writer that spells a supertype coordinate differently from the expression fails that foreign key on every fact document, rather than drifting.
5. **R1160's note** that `CoordinateSpellingGateTest` "dissolves instead under R1173" is left as it stands; it becomes true when this lands.

## What stays spelled, and what holds it

Two spellings remain and should. Capture spells `graphql_ast_element_entry.coordinate`, and the supertype rows `graphql_element` and `graphitron_element` key on it; the DDL expression spells each subtype's generated column. The subtype's `FOREIGN KEY (graph_name, coordinate) REFERENCES graphql_element` (or `graphitron_element`) ties them together. Every relation in the table has that foreign key today; `graphitron_key_field_binding` instead references `graphitron_occurrence_binding (graph_name, coordinate, bound, kind)`, which holds its coordinate to an occurrence just as firmly.

## Interactions

* R876's open node "The occurrence source is written once per depth" records the ruling that `graphitron_type` becomes the strict supertype, written first after the anchor. It will reshape `graphitron_type`'s writer; a generated coordinate makes that writer smaller and constrains nothing about it, so neither waits on the other.
* `ordinals-follow-the-row-number` (Backlog) touches the same `GraphQLAstCapture` writers. Whichever lands second rebases over a smaller diff.
* `SeededStore` writes anchors only through `MacroExpansion.derive`, the production call, so it follows without change.

## Tests

* The whole fact-document population is the acceptance test: every capture writes all eleven relations, and every subtype row's foreign key into its supertype holds only if the generated expression matches capture's spelling. A mismatched argument grammar (`(arg:)`) fails every document with an argument.
* `GraphQLAnchorTest` and `ConfiguredNoteTest` already read the coordinate back from these relations and pin its values; they stay unchanged and must stay green.
* `FactSchemaGateTest`'s comment-coverage rule covers the rewritten column comments, on the terms `bound_name_upper` already meets.
* No new test is added: there is no longer a wrong row to refuse.

## Retired vocabulary

* `CoordinateSpellingGateTest`.
* "The CHECK that the coordinate spells the key" and "the coordinate is the key spelled out" as a justification for a check, in SQL and Java comments.

## Owed, not done here

* The `bound` columns above, if the open question below is answered in favour of rendered keys.
* For Alf and Filip: `graphql_element` and `graphitron_element` key on the rendered coordinate itself, which sits awkwardly with "never the key" in "The key discipline". No page says whether a cross-grain supertype keyed by its rendering is the sanctioned exception. This item does not depend on the answer.

## Architect consultation

Consulted at pickup. It agreed with including `graphitron_type` and `graphitron_enum_value`; taken. It called excluding `graphitron_key_field_binding.coordinate` and the `bound` columns thin; taken for `graphitron_key_field_binding.coordinate`, whose coordinate is not in a key, and declined for `bound`, which is, with the reason stated under Scope. It said the entry join in `GraphQLAstCapture` does not dissolve; agreed, step 2. It asked that the foreign key be named as the enforcer and the key discipline say so; steps 4 and "What stays spelled". It flagged the R876 node and the Backlog ordinals item; recorded under Interactions. It raised the supertype-keying question; recorded under Owed.

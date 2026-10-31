---
id: R614
title: "@lookupKey is declared for arguments only"
status: Spec
bucket: docs
priority: 4
theme: docs
depends-on: []
created: 2026-08-09
last-updated: 2026-10-31
---

# @lookupKey is declared for arguments only

## Goal

`@lookupKey` is declared on `ARGUMENT_DEFINITION` only, so the schema itself says where the
directive goes. Today the bundled declaration also allows `INPUT_FIELD_DEFINITION`, the classifier
rejects every use there with a migration message phrased for mutations, and the manual describes a
per-input-field key the generator has never offered. An author who writes `@lookupKey` on an input
field gets graphql-java's directive-location error from schema assembly, and the code, the store
relation and the manual text that existed only to explain that site go.

No consumer changes. Every `@lookupKey` in our subgraphs is on an argument: a parser pass over
fs-plattform on main (`bef4ae018a`, 225 schema files) finds 74 applications, all on arguments of
root `Query` fields, and none on an input field; the kreg-g10 worktree has none. The 27 composite
keys among them put the directive on an input-typed argument, and every field of the input becomes
a key column; that rule stays. The Graphitron 10 subgraphs read the bundled declaration, not
fs-plattform's own `graphitron-directives/graphitron.graphqls`, which still lists both locations
and is fs-plattform's to change.

## What changes

* *The declaration.* `graphitron-model/src/main/resources/no/sikt/graphitron/model/schema/directives.graphqls`
  declares `directive @lookupKey on ARGUMENT_DEFINITION`, and its description drops the paragraph
  on the retired input-field location. The description states the argument rule as it holds: on a
  list argument of scalars each element is a key; on an input-typed argument every field of the
  input is a key column, and a field that binds no column on the return table is rejected.
* *The classifier's input-field rejections.* Three sites reject `@lookupKey` on an input field with
  "@lookupKey on a mutation input field is no longer supported": `BuildContext` (the mutation input
  field check), `FieldBuilder`'s query-side check on a plain input argument's fields, and
  `MutationInputResolver`. With the location gone, schema assembly refuses such a schema before the
  classifier runs, so all three are unreachable and go.
* *The store.* `graphitron_field_lookup_key_entry` records `@lookupKey` written on an input field.
  It can no longer be populated, so it goes with its writer (`GraphitronFactCapture`'s
  `lookupKey` case on input fields and its place in that class's table list) and its two readers:
  the `lookup_bearing` recursive term in `intent_authored_field_claim`, and the `EXISTS` term in
  `graphitron_carrier_data_field_rule`. Each reader is checked for what it answers without the
  term before it is cut; both read the term as "an input type carries a lookup key", which is now
  never true. `RelationRegistrationGateTest` and `undeclared-relations.txt` lose the entry.
* *The manual.* `docs/manual/reference/directives/lookupKey.adoc` loses the per-input-field claim
  this item was filed for, and `docs/manual/how-to/batch-lookups.adoc`'s composite-key recipe stops
  putting `@lookupKey` on the input's fields (sakila's `FilmActorKey` already has none). Other pages
  are corrected only where they state that `@lookupKey` may sit on an input field; a broader
  rewrite of the lookup pages is not this item's.

## Tests

* A schema with `@lookupKey` on a query input field, and one with it on a mutation input field, are
  refused at schema assembly, and the test pins graphql-java's location error naming the directive
  and the location. These replace the three tests asserting the migration message:
  `GraphitronSchemaBuilderTest`'s `LOOKUP_NESTED_IN_INPUT` and `LOOKUP_KEY_ON_INPUT_FIELD_RETIRED_R144`,
  and `MutationTableArgClassificationTest.insert_lookupKeyOnInputField_rejects`.
* The existing lookup tests stay green unchanged, the input-typed composite key
  (`Query.filmActorsByKey`) among them, which shows the argument rule still makes every input field
  a key column.
* `RelationRegistrationGateTest`, `MetaDeclarationGateTest` and the fact documents stay green with
  the relation gone.

## Retired vocabulary

* `graphitron_field_lookup_key_entry`, `GRAPHITRON_FIELD_LOOKUP_KEY_ENTRY`.
* The message "@lookupKey on a mutation input field is no longer supported".
* `INPUT_FIELD_DEFINITION` beside `@lookupKey`, in the declaration, its description and the manual.

## Related items

R866 asks for a correction of `graphitron_field_lookup_key_entry`'s comment. This item removes the
relation, so R866 is discarded when this item reaches Done.

## Provenance

Filed at Backlog as a doc claim only: the reference page said `@lookupKey` on an individual input
field applies to that field alone, while the query side rejects the shape with a message phrased for
mutations. Widened on 2026-10-31 after an inventory of every `@lookupKey` in the fs-plattform
subgraphs found the input-field location unused, so the declaration, not only the page, is what
changes.

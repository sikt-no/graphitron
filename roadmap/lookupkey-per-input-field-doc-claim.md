---
id: R614
title: "@lookupKey is declared for arguments only"
status: Ready
bucket: docs
priority: 4
theme: docs
depends-on: []
created: 2026-08-09
last-updated: 2026-11-01
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
a key column; that rule stays. fs-plattform's own `graphitron-directives/graphitron.graphqls` still
lists both locations, but no Graphitron 10 module reads it, so this item cannot meet a second, wider
declaration of the directive. Every Graphitron 10 module's `<schemaInputs>` are feature folders under
its own `src/main/resources/schema/` (tilgangsstyring, utdanningsregisteret, kompetanse and opptak on
main, kreg in the kreg-g10 worktree, sis on its upgrade branch at `1dcc6ebbf7`), no pom in
fs-plattform names `graphitron-directives`, and the parser pass finds `directive @lookupKey`
declared in that one file only. The modules still on Graphitron 9 (sis and fskode on main) read it
and are not affected by this item. The file is fs-plattform's to change.

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

* A fact document under `graphitron-model/src/test/resources/facts/defects/`, with `@lookupKey` on a
  query input field and on a mutation input field, asserts what the store reports to an author:
  each application yields a `graphql_schema_problem` row (`ASSEMBLY`,
  `DirectiveIllegalLocationError`) at the application's line, and no `graphitron_` entry is written
  for either; `graphitron_argument_lookup_key_entry` holds no row at those input fields. This is
  the refusal the editor shows and validate reads, written by capture through `DirectiveLegality`,
  and it holds whatever the classifier does; `facts/defects/authored-claim-undecoded.graphqls`
  already asserts the same row for `@lookupKey` on a field.
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

## Implementation notes

Where the landing differs from the plan above, for the In Review → Done reviewer:

* *One SQL reader, not two.* `graphitron_carrier_data_field_rule` had already lost its
  `graphitron_field_lookup_key_entry` term on trunk before implementation, so only
  `intent_authored_field_claim` changed. Its lookup branch is now an inner join on the marked
  argument, the closure CTEs (`input_object_field_edge`, `lookup_bearing`) are gone, and
  `transcription-readers.txt` loses the view's `graphql_type` line, which only the closure read.
* *The Java mirror went too.* `LookupFactVisitor` and `LookupFacts` carried the same input-field rows
  and type closure for `LookupFacts.triggersFor`. With the location gone both were always empty, so
  the trigger is now the marked argument alone, under the Goal's "code that existed only to explain
  that site". `TriggerFactPopulationPinTest` and `AuthoredClaimConflictsTest`'s agreement anchor pin
  the argument-only trigger.
* *Further tests built on the retired site* were rewritten or dropped beyond the three the plan
  named: `facts/defects/authored-claim-decoded.graphqls` loses its closure cases,
  `TableOnInputIgnoredNestingTest` loses its `@lookupKey` case, `EntryFamilyFixture` loses an
  input-field `@lookupKey` nothing asserted on, and `DecodeClearScopeGuardTest`'s floor drops by the
  one relation removed.
* *Two more doc sites.* `docs/manual/how-to/migrating-from-legacy.adoc` now says to remove
  `@lookupKey` from mutation input fields, and `docs/architecture/explanation/fact-model.adoc` keeps
  its recurse-over-pairs rule without citing the removed closure as its example. A stale see-also in
  `lookupKey.adoc` claiming tutorial page 5 uses the directive is gone.

## Related items

R866 asks for a correction of `graphitron_field_lookup_key_entry`'s comment. This item removes the
relation, so R866 is discarded when this item reaches Done.

## Provenance

Filed at Backlog as a doc claim only: the reference page said `@lookupKey` on an individual input
field applies to that field alone, while the query side rejects the shape with a message phrased for
mutations. Widened on 2026-10-31 after an inventory of every `@lookupKey` in the fs-plattform
subgraphs found the input-field location unused, so the declaration, not only the page, is what
changes.

## Reviewer findings

### Round 1: Spec -> Ready, withheld (session_01WkW2JHe9idtwUqpKsp5KXe, 2026-10-31)

The goal reads on its own and is reachable: the declaration, the three rejection sites, the
relation with its writer and both readers, and the three tests named all exist as described, and
R866 is the item to discard at Done. Withheld on one finding.

1. *The refusal an author meets is untested where it is reported.* The Tests section pins the
   refusal as graphql-java's location error from the classifier's schema assembly. But the store
   already reports a misplaced graphitron directive itself: `DirectiveLegality` keeps an application
   the definition does not admit out of the entries, and capture records a positioned
   `graphql_schema_problem` row (`ASSEMBLY`, `DirectiveIllegalLocationError`), which is what the
   editor shows and what validate reads. `facts/defects/authored-claim-undecoded.graphqls` asserts
   that row today for `@lookupKey` on a field. Add a fact document, or an assertion in an existing
   one, that `@lookupKey` on a query input field and on a mutation input field each yield that row
   at the application's line, and that no `graphitron_*` entry is written for them. That is the
   behaviour this item changes for an author, and it survives the classifier going, which the three
   replaced classifier tests do not.

   *Author:* added as the first bullet of Tests: a new fact document under `facts/defects/` with
   both sites, asserting the `ASSEMBLY` / `DirectiveIllegalLocationError` row at each application's
   line and no `graphitron_` entry, `graphitron_argument_lookup_key_entry` named.
2. *How the bundled declaration wins is stated, not shown.* The Goal says the Graphitron 10
   subgraphs read the bundled declaration and not fs-plattform's
   `graphitron-directives/graphitron.graphqls`, which still allows `INPUT_FIELD_DEFINITION`. Say
   how that was established (no Graphitron 10 module lists that file among its schema inputs), so a
   reader can tell this item cannot meet a second, wider declaration of the same directive.
   A wording fix; it does not block on its own.

   *Author:* the Goal now says how: every Graphitron 10 module's `<schemaInputs>` (named per
   module) are its own feature folders, no fs-plattform pom names `graphitron-directives`, and the
   parser pass finds `directive @lookupKey` declared in that file only.

### Round 2: Spec -> Ready, approved (session_01WkW2JHe9idtwUqpKsp5KXe, 2026-10-31)

Both round 1 findings are addressed. The Tests section now opens with the store's refusal as a
fact document, which is the behaviour this item changes for an author and outlives the classifier,
and the Goal shows how the bundled declaration is the only one a Graphitron 10 module reads.
Approved.

### Round 3: In Review -> Done, withheld (session_01WkW2JHe9idtwUqpKsp5KXe, 2026-11-01)

The change is the one approved and it is correct: the declaration is argument-only, the three
rejections, the relation, its writer and the closure in `intent_authored_field_claim` and in
`LookupFacts` are gone, and `facts/defects/lookup-key-on-input-field.graphqls` shows the goal
delivered where an author meets it, both refusals at their lines and only the marked argument
decoded. The implementation notes account for every departure from the plan. Withheld on the
retirement sweep, which finds the retired site still described in main sources:

1. *Emitted into consumers' code.* `TypeFetcherGenerator` throws "@mutation(typeName: UPSERT) call
   has no settable fields present; only @lookupKey fields were provided" from generated UPSERT
   fetchers (two emit sites). A mutation input can no longer carry `@lookupKey`, so the message
   names a shape the author cannot have written. State the fields as what they are now, the input
   fields bound to the conflict key, and the javadoc on the same emitter ("`@lookupKey` fields
   included so the user-supplied PK lands on the insert branch") with it.
2. *Javadoc describing the retired shape.* `InputColumnBindingGroup.MapGroup` ("today's standard
   `@lookupKey`-on-scalar-input-field shape", "an input type carries multiple separate `@lookupKey`
   fields") and `LookupMappingResolver`'s class comment ("a `TableInputArg` with no
   `@lookupKey`-bearing input fields"). Restate them as an input-typed `@lookupKey` argument whose
   fields are each a key column, as the two `InputColumnBinding` comments already were.

Left alone on purpose: `roadmap-tool/src/main/resources/legacy-directives.graphqls`, which declares
the legacy directive set as it was, and test prose that cites the retirement as history.

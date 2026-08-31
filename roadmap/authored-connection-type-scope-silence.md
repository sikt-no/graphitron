---
id: R846
title: "A field returning an author-declared connection type has no scope table"
status: Backlog
bucket: bug
priority: 2
theme: classification-model
depends-on: []
created: 2026-08-26
last-updated: 2026-08-27
---

# A field returning an author-declared connection type has no scope table

**Backlog tombstone.** The fix landed under R682, whose own scope rule claims it: a store silence a
conversion's diff turns up is that item's deliverable rather than a separate rewrite. This file
stays as the redirect and deletes when R682 reaches Done. What follows is the diagnosis as it was
filed, kept because it is what the fix was measured against, plus a closing section on what actually
shipped and how it differs from either option this item proposed.

`intent_field_scope_table` answers where a field's own generated SQL is rooted. For a field
returning a connection it is supposed to navigate as the element type, and it did so by reading
`graphitron_field_synthesis.authored_type_sdl`, which the generator writes when it *synthesises*
the connection type. A connection type the author declares in the SDL has no synthesis row, so the
rule fell back to the field's own named type, that type binds no table, and the field had no row
at all.

**The mechanism this describes no longer exists, and the premise needs re-checking before the item
is picked up (noted 2026-08-31 by the retirement sweep, not by this item's author).** The column
`authored_type_sdl` is gone: the expansion no longer overwrites the field's type expression, so
`graphql_field` carries what the author wrote and `graphitron_field_synthesis` carries the macro's
replacement, which is the opposite direction. `intent_field_scope_table` reads neither of them now,
navigating through `intent_field_navigated_type` and `intent_expanded_field` instead. Whether the
four coordinates below still have no row is a question to re-measure rather than inherit.

Four coordinates in the sakila example schema are in this population: `Query.filmsConnection`,
`Query.filmsConnectionDesc`, `Query.filmsByRateDescTitleAsc` and `Query.filmsOrderedConnection`,
all returning the hand-written `FilmsConnection`. Their generator-synthesised siblings
(`Query.filmsFaceted`, `Query.filmsConnectionByRequiredIds` and the rest) all resolved to `film`
correctly, which is what hid this: the population reads as "connections work" until you look for
the ones the author named.

Everything under that relation inherited the silence. `intent_argument_scope_table` is its fan-out
over arguments, so those fields' arguments had no scope; `intent_argument_column_scope` and
`intent_argument_column_match` then had nothing to resolve against, so a filter argument on such a
field resolved no column; and `intent_input_field_resolving_table` and the whole input-field family
below it were blank for any input type reached only from one of these coordinates.

Found by diffing `intent_condition_membership` against what `ConditionCommands.produce` actually
yields for the example schema: `Query.filmsOrderedConnection` carries a `rating` filter argument
whose binding and column both exist and which the producer emits a condition for, and the fold
could not see it. That is the one of the four with a filter argument, so it is the one that showed.

## What shipped, and why it is neither option

The item asked whether the fix belonged on the synthesis capture (write a row for an
author-declared connection too) or on the scope rule (resolve the element type from the type's own
`edges`/`node` shape). Reading the tree settled it as a third thing, because the question turned
out not to be about this relation.

The `COALESCE` over the synthesis record was written out at **five** sites, not one:
`intent_routine_return_binding`, `intent_field_column_scope`, `intent_field_participant_scope_table`,
`intent_field_scope_table` and `intent_mutation_routine_seat`. All five read the same way, and all
five carried the same silence. Fixing the scope rule alone would have left four spellings of a rule
that had just been shown to be wrong, and widening the capture would have hidden the duplication
rather than removed it.

So the deliverable was the missing relation: `intent_connection_element_type` states the structural
shape at the type's own grain, and `intent_field_navigated_type` states which type a field navigates
as in three ranked rungs over it. The classifier's own `BuildContext.isConnectionType` is what the
shape rule transcribes, so the store and the walk answer the same question the same way.

**Four of the five sites read it; the fifth is filed as R850.** `intent_field_scope_table`, which is
this item's own subject, plus `intent_field_participant_scope_table`,
`intent_routine_return_binding` and `intent_mutation_routine_seat` all read the navigation now.
`intent_field_column_scope`'s named-type arm cannot: repointing it makes that arm fifty times
slower, because it carries a correlated anti-join against a recursive view that is cheap only under
the plan H2 picks when the navigated type is a literal base-table expression. Five forms were
measured and R850 carries the figures. The coordinates this item names all resolve their scope table
correctly now; what remains silent at that one arm is where a column name written on such a field
resolves, which is a different question and a different reader.

---
id: R846
title: "A field returning an author-declared connection type has no scope table"
status: Backlog
bucket: bug
priority: 2
theme: classification-model
depends-on: []
created: 2026-08-26
last-updated: 2026-08-26
---

# A field returning an author-declared connection type has no scope table

<One-paragraph problem statement: what is missing or broken, and why it matters. Replace this and add a plan body when the item moves to Spec.>

`intent_field_scope_table` answers where a field's own generated SQL is rooted. For a field
returning a connection it is supposed to navigate as the element type, and it does so by reading
`graphitron_field_synthesis.authored_type_sdl`, which the generator writes when it *synthesises*
the connection type. A connection type the author declares in the SDL has no synthesis row, so the
rule falls back to the field's own named type, that type binds no table, and the field has no row
at all.

Four coordinates in the sakila example schema are in this population: `Query.filmsConnection`,
`Query.filmsConnectionDesc`, `Query.filmsByRateDescTitleAsc` and `Query.filmsOrderedConnection`,
all returning the hand-written `FilmsConnection`. Their generator-synthesised siblings
(`Query.filmsFaceted`, `Query.filmsConnectionByRequiredIds` and the rest) all resolve to `film`
correctly, which is what hid this: the population reads as "connections work" until you look for
the ones the author named.

Everything under that relation inherits the silence. `intent_argument_scope_table` is its fan-out
over arguments, so those fields' arguments have no scope; `intent_argument_column_scope` and
`intent_argument_column_match` then have nothing to resolve against, so a filter argument on such a
field resolves no column; and `intent_input_field_resolving_table` and the whole input-field family
below it are blank for any input type reached only from one of these coordinates.

Found by diffing `intent_condition_membership` against what `ConditionCommands.produce` actually
yields for the example schema: `Query.filmsOrderedConnection` carries a `rating` filter argument
whose binding and column both exist and which the producer emits a condition for, and the fold
could not see it. That is the one of the four with a filter argument, so it is the one that showed.

## What to check when picking this up

Whether the fix belongs on the synthesis capture (write a row for an author-declared connection
too) or on the scope rule (resolve a connection's element type from the type's own `edges`/`node`
shape rather than from the synthesis record). The first is narrower; the second is what the rule
claims to do.

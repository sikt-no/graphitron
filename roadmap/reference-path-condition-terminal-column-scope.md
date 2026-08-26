---
id: R847
title: "A reference path ending in a condition hop resolves no column scope"
status: Backlog
bucket: bug
priority: 2
theme: classification-model
depends-on: []
created: 2026-08-26
last-updated: 2026-08-26
---

# A reference path ending in a condition hop resolves no column scope

<One-paragraph problem statement: what is missing or broken, and why it matters. Replace this and add a plan body when the item moves to Spec.>

An argument carrying `@reference(path: [...])` resolves its column against the path's terminal
table: that is `intent_argument_column_scope`'s `PATH_TERMINAL` basis, reading
`intent_argument_reference_step_target`. A path step can be a key hop (`{key: "..."}`) or a
condition hop (`{condition: {className, method}}`), and a condition hop names no foreign key, so the
step target relation has no row for it and the scope relation has no row for the whole path.

Two coordinates in the sakila example schema are in this population, and both are deliberate
fixtures for the shape:

- `Query.customersByConditionDistrict`, whose `district` argument has a single-step path that is a
  bare condition hop, and
- `Query.filmsByBridgedActorFirstName`, whose `firstName` argument has an FK hop to the
  `film_actor` junction and then a terminal condition hop to `actor`.

The classifier resolves both. `intent_argument_column_scope` and `intent_argument_column_match`
have no rows for either, so `intent_argument_filter_role` has no row either, absence in that
relation meaning "a rejection's population" where these two are nothing of the kind.

Found by diffing `intent_condition_membership` against what `ConditionCommands.produce` actually
yields for the example schema: the producer emits a condition at both coordinates and the fold
could not see either.

## What to check when picking this up

Where a condition hop's target table comes from. The classifier gets it from the condition method's
own declared table parameter, which is a Java-side fact the store may or may not carry; if it does
not, this is a capture question before it is a derivation one. The FK-then-condition case shows the
two hop kinds have to compose, so a fix that only handles a single terminal condition hop is half
of it.

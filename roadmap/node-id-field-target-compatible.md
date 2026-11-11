---
id: R1172
title: "A @field beside a @nodeId names a target compatible with the node"
status: Backlog
bucket: feature
priority: 5
theme: diagnostics
depends-on: []
created: 2026-11-11
last-updated: 2026-11-11
---

# A @field beside a @nodeId names a target compatible with the node

## Goal

An author who writes `@field(name:)` beside a `@nodeId` is told at generate time when what the
`@field` names cannot hold the node id, instead of meeting a wrong value or a compile error in the
generated code. A node id encodes one or more key columns of the node's table, and the `@field` maps
it onto a target: a record whose members are those columns, a table record matching the node, or,
for a node keyed on one column, a single scalar of that column's type. Anything else is a defect at
the field's declaration (the ruling of 2026-11-11 in `docs/architecture/explanation/fact-model.adoc`,
"`@nodeId` with `@field` maps the node id onto what the `@field` names").

## Where it stands

Some of this is already checked, partly by the walk: an `@error` extra field's accessor must return
the key column's Java type and the node must be keyed on one column (`reference/directives/error.adoc`),
and `defects/claims-conflict` treats a `@field` beside a `@nodeId` as the node id claim's target rather
than a column claim. The work is to state the compatibility once in the store, over the binding
claim's target and the node's key columns, and refuse the incompatible cases nothing checks yet: a
`@field` naming one column of a node keyed on several, a record or table record missing a key column
or carrying one of the wrong type, and a scalar whose type differs from the key column's. Existing
uses (`scenarios/binding-claim`, the sakila `FilmKeyRow.filmRef` and `FilmLookupInvalid.attemptedFilm`)
are compatible and stay.

A node's `id` with `@field` and no `@nodeId` is a column mapping, not this case, and stays out of scope.

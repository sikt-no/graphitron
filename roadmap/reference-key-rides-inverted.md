---
id: R1037
title: "A @reference key element can ride its key inverted"
status: Backlog
bucket: feature
priority: 3
theme: codegen-correctness
depends-on: []
created: 2026-10-14
last-updated: 2026-10-14
---

# A @reference key element can ride its key inverted

## Goal

A `@reference` key element can say which way it rides its key. `ReferenceElement` gains
`inverse: Boolean`, nullable with no default, and its three values mean:

- **`null`, written or left out: infer.** The direction comes from deterministic rules, which always
  give one. Between two different tables it is the one direction departing where the path stands,
  the only one that does. For a self-referencing key, whose two directions depart and arrive at one
  table so the path cannot say, it is forward, departing the table that declares the key, the
  direction the key's own name and jOOQ's `Keys` constant name.
- **`true`: ride the key against itself.**
- **`false`: ride it forward.**

An explicit value is for the case where the rules give the wrong answer, and only a self-reference
can be that case. Between two tables a value that disagrees with the path names a direction the path
cannot take there, so it is an error; one that agrees is a statement the build checks. On a
self-reference `true` overrides the rule and `false` restates it, and the editor hints that a
self-reference left to the rule could say which way it means, since the path cannot show a reader.

This replaces the generator's guess from the field's cardinality, which today decides every
self-reference and is sometimes wrong without saying so.

## Why

A foreign key is directional and can be ridden both ways. Between two tables the path settles which
way: a hop departs where the previous one arrived, and only one of the key's two directions departs
there. A self-referencing key departs and arrives at one table, so where the path stands says
nothing, and the generator takes the direction from the field: a single-valued field reads along the
key, toward the parent, and a list reads against it, toward the children (`selfRefFkOnSource =
!isList`, applied at every hop of a path). That guess fails twice:

- **At a hop that is not the last.** The field's cardinality is the whole path's, not one hop's.
  `managersRentals: [Rental!]! @reference(path: [{key: "staff_manager_id_fkey"}, {table:
  "rental"}])` means my manager, then that manager's rentals; the list makes the first hop read
  "the staff I manage".
- **For a self-reference made one-to-one by a unique key.** Both directions are single-valued, so
  `successor: Staff` and `predecessor: Staff` read the same way and one of them is wrong.

A name cannot carry the direction instead. jOOQ keeps only the forward key in `Keys`; an inverse key
is the runtime object `ForeignKey.getInverseKey()`, named only by a table's to-many path methods,
and jOOQ drops those as ambiguous for every self-reference and for keys running both ways between
two tables (checked with jOOQ 3.20.11's own generator; the fixture catalog turns to-many paths off
for the same warning). `inverse` is jOOQ's own word for a key ridden against itself, and the fact
store already models the two directions as `sql_constraint_hop.fk_on_from`.

## What consumers write today

Measured over the seven Graphitron subgraphs in fs-plattform at `origin/main` `4d2f79280b`: sis,
opptak, tilgangsstyring, utdanningsregisteret, kompetanse, kreg and fskode.

- Every key spelling the resolver accepts is in use: the SQL constraint name and the `Keys` constant,
  in either case, with and without a schema qualifier. Nothing names an inverse key.
- About a fifth of key hops ride a key from its referenced table to its referencing one, and between
  two tables the path orients every one of them.
- The catalogs hold 47 self-referencing keys and none is one-to-one in practice. Twelve are named in a
  path, each the only hop of its path. Eleven are single-valued and mean the parent, which forward
  reads as they read today. The twelfth, `URegOrganisasjonsenhet.overordnetOrganisasjonsenhet` in
  utdanningsregisteret, is described as the unit above in the hierarchy and declared as a list, so the
  cardinality guess reads it as the units below; read forward it returns its parent.

So the default changes the answer for one field in one subgraph, and that field reads today as the
opposite of its description. Its owners should hear of it before this lands, in case the list is what
was meant. The evidence is in `roadmap/audits/2026-10-14-reference-keys-in-consumer-schemas.md`.

## Scope

The directive's SDL and the element's decode; the generator's path resolution taking the direction
from the element and dropping the cardinality guess; a validation that `inverse:` agrees with the
direction a path between two tables implies; the fact store's readings, which keep both directions of
a key and let the walk choose, choosing by the element for a self-reference; and the `@reference`
reference page, which states the key spellings, the direction rule and `inverse`.

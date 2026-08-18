---
id: R718
title: "Three reads a chain field or a routine-bound type falls through"
status: Backlog
bucket: architecture
priority: 3
theme: routine
depends-on: [routine-composition-surface-from-facts]
created: 2026-08-18
last-updated: 2026-08-18
---

# Three reads a chain field or a routine-bound type falls through

`roadmap/routine-composition-surface-from-facts.md` derived the routine read surface from facts: a
routine's result now binds its return type, a chain's terminus is a relation, and a hop out of a
function result is keyed by a relation rather than by a loop. Three existing reads did not catch up.
Each answers correctly for the population it was written for and answers wrongly, or not at all, for
a field whose source is a chain or a type bound by a routine's return. They are filed together
because two of them are rules of one view and have to move as a pair, and the third is the same
mistake one relation over.

Named in that item and not folded into it: the first two are a change to a view with live consumers
and its own anchor, and the third is a relation whose own readers all shift with it. The item's
slice list originally homed the first with "slice 12's retirement pass", a pass that moved to
`roadmap/planners-read-facts-emitters-read-commands.md` when slice 13 did, so the residue would
otherwise have no home at all.

## The reads

**A chain field has no column-scope rule.** `intent_field_column_scope` answers "which table do the
column names written at this field resolve against", which is the question the ordering and
filtering axes ask. Its three rules are disjoint by construction and a chain field falls through
all of them. The `PATH_TERMINAL` rule walks from the enclosing type's binding, which a chain does
not depart from; the `NAMED_TYPE_TABLE` rule now answers a child-position single-node routine field
for free, its named type being bound by the return derivation, but it cannot answer the root
position, whose root-parent guard masks it, nor a routine-then-hops chain, whose landing is not its
named type's binding. A fourth rule reading `intent_field_chain_terminus` answers both.

**`PATH_TERMINAL` is missing a guard.** It walks from the *enclosing* type's binding, so on a child
field carrying `@routine` plus `@reference` it resolves the written elements out of the parent's
table and names a destination the chain never visits. The defect predates the chain arm's absence
and is not caused by it: it is the first rule missing a guard for a field whose path does not
depart the parent. A fourth rule cannot land without it, since a chain field would then satisfy
two rules and break the one-row-per-site property the view's union stands on. That is why these two
are one item and not two.

**`intent_carrier_data_field`'s TABLE arm reads the `@table` population.** It decides a payload data
field's `element_kind` by checking `intent_bound_table`, where the readers that moved during slice 9
read `intent_resolved_type_binding`. So a data field whose named type is bound only by being what a
routine returns is not TABLE, and everything gated on that gate excludes it, including
`intent_carrier_routine_hop`, whose own arrival resolution reads the resolution and would have
handled the type. The narrowness is recorded in that view's comment as inherited rather than
chosen, with the note that the gate follows this relation when it moves.

## Worked example for the third read

```graphql
type Film { filmId: Int! @field(name: "film_id") }        # no @table

type Query {
    filmsForActor(actorId: Int!): [Film]
        @routine(name: "films_for_actor", argMapping: "pActorId: actorId")
        @reference(path: [{table: "FILM"}])
}

type MakeFilmPayload { film: Film, errors: [WriteError] }
type Mutation {
    noteFilm(body: String!): MakeFilmPayload
        @routine(name: "create_secure_note", argMapping: "pBody: body")
}
```

`Film` is bound to `film` by the routine return, so `intent_resolved_type_binding` holds one
unambiguous row for it and no `@table` is written anywhere. `intent_carrier_data_field` finds none,
so `MakeFilmPayload.film` gets an `element_kind` other than TABLE and never reaches the hop
relation.

## What to settle at pickup

Whether the third read is genuinely the same change as the first two or wants its own commit. The
argument for one item is that all three are the same omission; the argument for splitting is that
the column-scope pair is guarded work on a view with an anchor, and the carrier arm is a one-line
repoint whose risk is entirely in its readers.

## Related items

* `roadmap/routine-composition-surface-from-facts.md` recorded all three and is where the
  derivations they should read came from.
* `roadmap/planners-read-facts-emitters-read-commands.md` inherited the retirement pass that used
  to be the first read's home.

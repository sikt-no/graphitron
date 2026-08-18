---
id: R717
title: "The routine carrier's explicit data-field path needs a correlation arm that anchors on the captured record"
status: Backlog
bucket: architecture
priority: 3
theme: routine
depends-on: []
created: 2026-08-18
last-updated: 2026-08-18
---

# The routine carrier's explicit data-field path needs a correlation arm that anchors on the captured record

A `@routine` write on `Mutation` may return a payload carrier: a type wrapping one data field beside
an errors channel. The routine call is the write, and the data field owns the post-commit re-read of
the committed row. Today that re-read can only reach a table the routine's own result columns
name-match directly, because the data field is not allowed to declare `@reference` at all. A payload
whose target sits one or more hops past that table has no spelling, and the author's only route is to
abandon `@routine` for a hand-written `@service`.

## Worked example

`rent_film` inserts a rental and returns a one-row table exposing `rental_id`. That name-matches
`rental`'s primary key, so this works:

```graphql
type RentFilmPayload {
    rental: Rental          # Rental @table(name: "rental")
    errors: [RentFilmError]
}
```

A payload wanting the rental's `customer` instead has no way to say so. `@reference(path: [{table:
"customer"}])` on the data field is refused outright, even though the identical hop is legal one
seat over on the chained form (`rentFilm: [Rental!]! @routine(...) @reference(path: [{table:
"rental"}])`).

## Why it is not just admitting the directive

This is inherited from R622 as its real design work, and the fold into R704
(`roadmap/routine-composition-surface-from-facts.md`) did not change it.
`ParentCorrelation.checkCarrierInvariant` pairs a non-empty `joinPath` only with a hop-anchored
correlation, while the carrier data field's correlation is the hop-less `OnLiftedSlots` over the
captured slots. So a residual path needs a correlation arm that anchors on the captured record and
walks onward from it, plus the post-commit query emit that rides it. That is model work on the write
path, and no view hands it over, which is why this left R704: none of it is a question the read
surface can answer.

An open question worth settling at pickup rather than mid-implementation: whether that arm
generalises to R447's `RecordTableField`, which correlates from a handed record for a different
reason. If it does, the arm is one shape with two callers; if it does not, the difference should be
stated where a reader of either will find it.

## Inherited as decided

Not reopenable here without arguing down the carrier item's two-statements rule:

* the write transaction contains the routine call and a projection of its own result and nothing
  else, at every hop count;
* residual hops run post-commit under the caller's identity, so read policies apply to them;
* a multi-hop data field legitimately resolving null with empty errors is the carrier's documented
  success outcome, not a defect.

In-transaction capture would not escape row-level security either, so it buys only insulation from
visibility that changes at commit.

## What is already in hand

The endpoint resolution is not this item's work, and it has shipped. `intent_carrier_routine_hop`
states where a carrier's data field departs from and arrives at, and `intent_name_matched_key_pair`
is the pairing underneath it. This item reads those for hop 0 and walks onward; the multi-hop walk
itself is `intent_field_reference_step_target`, already shipped, since an element's departure is the
previous element's arrival and position 0 is the only special one.

The two relations landing is what cleared this item's `depends-on`.

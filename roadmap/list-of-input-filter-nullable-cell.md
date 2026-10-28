---
id: R1116
title: "A nullable element field in a list-of-input filter does not constrain when absent"
status: Backlog
bucket: feature
priority: 4
depends-on: []
created: 2026-10-28
last-updated: 2026-10-28
---

# A nullable element field in a list-of-input filter does not constrain when absent

## Goal

A list-of-input filter (a filter field typed as a list of input objects, where a row matches when it equals any one element) can have a nullable element field. When an element leaves that field absent or null, the field does not constrain that element: `{arstall: 2026}` matches every term in 2026, `{arstall: 2026, betegnelse: VAR}` matches only spring 2026, and a list mixing the two matches the union. Today the build refuses a nullable element field, so a schema cannot say "any value here" per element.

## The case from sis

`FagpersonVedLarested.undervisningsenhetsroller(filter: { terminkoder: [FagpersonUndervisningsenhetsrollerFilterTerminkodeInput!] })` filters `UNDERVISNINGSENHETSROLLE` on `(ARSTALL, TERMINKODE)`. The element type is:

```graphql
input FagpersonUndervisningsenhetsrollerFilterTerminkodeInput {
  arstall: Int!
  betegnelse: FagpersonUndervisningsenhetsrollerFilterTerminbetegnelseInput @field(name: "TERMINKODE")
}
```

Clients expect `[{arstall: 2025}, {arstall: 2026, betegnelse: VAR}]` to mean all of 2025 plus spring 2026. Graphitron 9 intended exactly this. Its generated condition used `hasArgumentAtIndex(...) ? DSL.val(betegnelse) : TERMINKODE`, so an absent cell compared the column with itself. The intent was never exercised, because the presence check looked up `filter[i]` instead of `filter.terminkoder[i]` and so never applied `betegnelse`, even when it was sent. Since R1057 refuses a nullable element field, sis has made `betegnelse` non-null for now. It will relax the field back to nullable when this lands, which is non-breaking for clients.

## Where it is refused today

A *cell* is one element field bound to one column, one position of the row an element is compared as. `FieldBuilder.addCell` refuses a nullable cell ("it is nullable; a left-out cell would compare as null and the element would match no row, so declare it non-null"). The `NestingField` arm of the same walk refuses a nullable nested input inside an element on the same grounds. Both refusals are documented in the user manual: `field.adoc` § list-of-input filter (`[[list-of-input-filter]]`), and the diagnostics glossary entry "An element field a list-of-input filter cannot compare", which says outright that the build refuses a nullable element field. Both need updating when this lands.

The rule for a `null` *element* is a separate matter and stands: `[null]` matches no row (R1057). This item is about a null *cell* inside a non-null element.

## Proposed semantics (from the sis request)

* Non-null cells keep today's behaviour, and an element shape whose cells are all non-null keeps the `row(...).in(rows)` rendering (`BodyParam.RowIn`, or `In` for one cell).
* A nullable cell that is absent or null in an element drops out of that element's predicate. Per element that reads `(ARSTALL = a AND TERMINKODE = b)`, or `ARSTALL = a` when `b` is absent, OR'ed across elements. Grouping elements by which cells are present and rendering one row-IN per group, OR'ed, is an equivalent and cheaper rendering.
* Explicit null and absent mean the same, since this is a filter, not a write. The row helper `ElementRowsHelperRegistry` mints reads the element as a map, so telling them apart would be a `containsKey` question there. The proposal is not to tell them apart.
* The R1059 shape, where every cell is reached through one `@reference` path and renders as a correlated `EXISTS` (`BodyParam.RemoteColumnPredicate` wrapping `RowIn`), gets the same rule if it is cheap.

## Open questions for Spec

* **Does an absent cell match rows whose column is NULL?** Graphitron 9's `TERMINKODE = TERMINKODE` evaluates to NULL, not true, when the column is NULL, so its "unconstrained" cell excluded those rows. Dropping the cell from the predicate includes them. The new reading suggests including them, but it has to be chosen, and it is the one place a sis client could see a difference from what Graphitron 9 intended.
* **An element whose cells are all absent.** "Matches everything" is consistent but surprising. The alternatives are to refuse it at runtime as a client error, or to require at least one non-null cell in the element shape at build time. sis is fine with either.
* **Nullable nested inputs.** A nullable nested input inside an element, left out, leaves all of its cells absent. The same "does not constrain" reading applies one level up. Decide whether this item lifts that refusal too.
* **Rendering.** Per-element OR against one row-IN per presence pattern. The number of patterns is bounded by 2^(nullable cells) but in practice is the handful a request actually sends.

## Provenance

Requested 2026-10-28 by the sis Graphitron 10 port session. It is not a port blocker: sis has made the field non-null in the meantime.

---
id: R974
title: "Ordinals stop being shifted: the store keeps the number SQL gives it, and the fourteen subtractions go"
status: Backlog
bucket: cleanup
priority: 7
theme: model-cleanup
depends-on: []
created: 2026-09-24
last-updated: 2026-09-24
---

# Ordinals stop being shifted: the store keeps the number SQL gives it, and the fourteen subtractions go

## Goal

Every ordinal in the store is the number `ROW_NUMBER()` produced, and no writer shifts it. When this
lands the fourteen `.minus(inline(1))` calls across the capture classes are gone, the column comments
stop asserting a base, and a reader adding an ordinal has nothing to get wrong.

One term, glossed once. An *ordinal* here is a dense position within a partition: a field's place on
its type, an argument's on its field, a facet's on its carrier. What every consumer does with one is
order by it. Nothing compares an ordinal to a literal, and nothing exposes one to an author.

## Why it is worth a commit

The store is inconsistent with itself today and the inconsistency is an omission rather than a
decision. `GraphQLAstCapture` writes `rowNumber().over(...).minus(inline(1))` eleven times, the two
other capture classes three more between them, and those columns are documented as dense from 0.
`graphitron_connection_facet.position` is a bare `ROW_NUMBER()` and is documented as 1-based. Both
comments describe what the code happens to do; neither argues for a base.

The subtraction is the part that costs. It is fourteen opportunities to forget, it makes every new
ordinal a question ("which convention is this one?"), and the answer is invisible at the call site
because a window function and a subtraction look like one expression. A reader who forgets writes a
relation that is off by one against its siblings and passes every gate, because nothing compares two
relations' ordinals.

Converging on the shifted convention would mean adding a fifteenth subtraction. Converging on what
SQL gives means deleting fourteen and leaving nothing to remember.

## What this changes, and what it does not

It changes the numbers in the store and the expectations pinned on them. Corpus and fact documents
assert ordinals directly, so every such cell shifts by one; that is a wide diff and a mechanical one.

It changes no behaviour. An ordinal is read to order by, so a uniform shift is invisible to every
consumer, which is also why nothing catches the current inconsistency.

## What is already on the right side

The macro expansion's facet arm takes `graphitron_connection_facet.position` as the row number gives
it rather than subtracting, so that one arm is already 1-based and does not move. Its sibling arms in
the same relation state their ordinals as literals from 0, which is the inconsistency this item
closes; they were written before the convention was settled and are a one-line change each.

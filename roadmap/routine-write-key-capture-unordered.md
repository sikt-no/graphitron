---
id: R660
title: "Routine write key capture fetches unordered"
status: Backlog
bucket: bug
theme: routine
depends-on: []
created: 2026-08-13
last-updated: 2026-08-13
---

# Routine write key capture fetches unordered

A list-returning `@routine` write (`MutationField.MutationRoutineWriteField`) produces rows in
no defined order, with no directive the author can write to fix it and no diagnostic saying so.
Sibling of `roadmap/routine-composition-surface-from-facts.md` (R704), which fixes the same
defect class on the read side; split off because the seam is different (a write's key capture,
not a read's order surface) and the fix is not the same edit.

The direct-return shape is not exempted from the deterministic-order rule; it is outside the
capability the rule keys on. `MutationRoutineWriteField` implements `MutationField` alone, not
`SqlGeneratingField`, and `GraphitronSchemaValidator.validateListRequiresOrdering` guards
`SqlGeneratingField` members only, so the leaf never reaches the check. It also carries no
ordering slot, so there is nowhere for a resolved `OrderBySpec` to live even if `@defaultOrder`
were honoured on the mutation field. Both of its fetches are unordered: step 1's key capture
emits `.select(source.<key cols>).from(source).fetch()` over the routine result, and step 2 is a
keyed `SELECT ... WHERE key IN (...) .fetch()` that is the field's visible result.

`Mutation.rentFilm` in the sakila example schema (`[Rental!]!` off `rent_film`) is a live
instance.

The carrier shape (`Mutation.rentFilmPayload`) fails differently and should be checked
separately. Its payload data field *is* an `SqlGeneratingField` and *is* exempted, by
`validateListRequiresOrdering`'s `requiresReFetch()` clause, whose stated justification is that
the `ORDER BY idx` scatter re-keys the re-projected rows to the upstream source order. That is
sound wherever the upstream order is itself defined; for a routine write the upstream is step 1's
unordered fetch, so the exemption rests on a premise this path does not supply.

The two failure modes matter because the fix differs: non-membership is closed by capability
membership (or by a membership meta-test that makes such gaps fail loudly), while the exemption
is closed by narrowing `requiresReFetch()`.

Open questions for the Spec:

* Which seat carries the order, the mutation field or the payload's data field? The re-read is
  owned by the data field, but the key capture that fixes the visible order happens in step 1,
  on the mutation field.
* Whether `requiresReFetch()`'s exemption should be narrowed to re-fetches whose source order
  is actually defined, rather than removed or left whole. That clause guards more than the
  routine write, so narrowing it needs a census of its other users first.
* Whether the right closure is a capability-membership meta-test rather than a per-leaf fix.
  `development-principles.adoc` names membership completeness as review-only and flags the
  silent-skip case as candidate roadmap material; a list-shaped root leaf outside
  `SqlGeneratingField` is exactly that case, and a meta-test would catch the next one instead of
  waiting for a field report.
* Whether a single-row write (the common `rentFilm` case) is worth the surface at all, or
  whether the rule should key on list cardinality only.

## The rejection built for this coordinate, and the arm this item flips

The coordinate now has a cell that can reject it, and the rejection is deliberately withheld.
`intent_field_unlowerable_ordering` mints a `KEY_CAPTURE_SCATTER` row for every mutation-root
`@routine` write that `intent_mutation_routine_seat` admits and that returns a list, on the
`PRIMARY_KEY_FALLBACK` availability route: the target table's primary key is available at
`Mutation.rentFilm` and nothing delivers it. `UnlowerableOrderings.rejectionOf` is a total switch
over that view's two verdicts and its `KEY_CAPTURE_SCATTER` arm mints no `ValidationError`, because
the only live instance of the shape is in `graphitron-sakila-example`'s own schema, so wording the
rejection would redden this reactor's verification build until this item lands.

What that leaves this item is a choice made deliberately rather than met in a failing build.

* **If the fix gives the write an order to deliver**, the arm's population empties on its own, the
  rule keying on the read shape rather than on a list of coordinates, and the arm comes out with its
  test. The pinning assertion is
  `UnlowerableOrderingsTest.aListReturningRoutineWriteMintsARowAndHoldsItsRejection`, whose two
  halves are the row's existence and the absence of a violation; a lowering that lands upstream turns
  it into a failing test rather than a quietly empty population.
* **If the shape is refused instead**, the arm gains its message and nothing else changes. R677's
  body carries the draft: the write returns a list and delivers it in no defined order, the routine's
  returned keys being captured and the rows re-read by key with neither step sorting, so the target
  table's primary key orders nothing here even though it is available; return a single object rather
  than a list. Turning it on is a **breaking change** for any consumer schema carrying a
  list-returning `@routine` write, and `Mutation.rentFilm` in our own example schema has to be fixed
  in the same commit.

The `requiresReFetch()` census this item owns has one more datum now. `LauncherCommands`'s ordering
fold exempts the `ProjectedReentry` and `DiscriminatedReentry` launch sources from its
absent-ordering ratchet, on the same premise `requiresReFetch()` rests on and with the same
qualification: the `ORDER BY idx` scatter re-keys the rows to the upstream source order, which for a
DML write's returned keys is defined and for a routine write's key capture is not. The exemption
entry in `LauncherCommands.orderIsEntailedBySource` says so in its comment, and narrowing it belongs
with the narrowing of `requiresReFetch()` rather than separately.

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
Sibling of `roadmap/routine-chain-order-directive-silent-noop.md` (R659), which fixes the same
defect class on the read side; split off because the seam is different (a write's key capture,
not a read's order surface) and the fix is not the same edit.

Two facts compose into the hole:

* `MutationRoutineWriteField` carries no ordering slot at all, so there is nowhere for a
  resolved `OrderBySpec` to live even if `@defaultOrder` were honoured on the mutation field.
  Step 1's key capture in `TypeFetcherGenerator` emits `.select(source.<key cols>).from(source)
  .fetch()` over the routine result with no `ORDER BY`.
* The payload data field's post-commit re-read is then exempted from the deterministic-order
  rule by `GraphitronSchemaValidator.validateListRequiresOrdering`'s `requiresReFetch()`
  clause. That exemption's stated justification is that the `ORDER BY idx` scatter re-keys the
  re-projected rows to the upstream source order, which is sound wherever the upstream order is
  itself defined. For a routine write the upstream is step 1's unordered fetch, so the
  exemption rests on a premise this path does not supply, and the visible order of the payload
  list is the function's incidental row order.

`Mutation.rentFilm` in the sakila example schema (`[Rental!]!` off `rent_film`) is a live
instance of the direct-return shape; `Mutation.rentFilmPayload` is the carrier shape, whose
data field takes the re-fetch exemption.

Open questions for the Spec:

* Which seat carries the order, the mutation field or the payload's data field? The re-read is
  owned by the data field, but the key capture that fixes the visible order happens in step 1,
  on the mutation field.
* Whether `requiresReFetch()`'s exemption should be narrowed to re-fetches whose source order
  is actually defined, rather than removed or left whole. That clause guards more than the
  routine write, so narrowing it needs a census of its other users first.
* Whether a single-row write (the common `rentFilm` case) is worth the surface at all, or
  whether the rule should key on list cardinality only.

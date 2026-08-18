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

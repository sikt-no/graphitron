---
id: R393
title: "Disambiguate the base-to-detail (interface-to-implementer) join path via @reference"
status: Backlog
bucket: feature
priority: 7
theme: interface-union
depends-on: []
created: 2026-06-26
last-updated: 2026-06-26
---

# Disambiguate the base-to-detail (interface-to-implementer) join path via @reference

R389 ships first-class discriminated joined-table inheritance: a participant declares its own detail
`@table` and its base-only inherited fields carry `@reference` back to the discriminated base. Two paths
ride that declared reference: resolving the inherited (base-resident) fields (detail to base), and the
base-to-detail join the interface fetcher emits to reach each implementer's own detail table (base to
detail, the *interface-to-implementer* path). R389 handles the **unambiguous** shape only: exactly one
foreign key connects the detail table and the base, so the reference pins the join with nothing to
disambiguate. Both R389 fixtures are this shape (`party_individual -> party`; `jti_app_account ->
jti_subject`).

This item owns the **disambiguation**: how `@reference` names the base-to-detail (interface-to-implementer)
join path when the unambiguous assumption does not hold, and what the canonical declaration is. The two
cases R389 cannot express:

- **Multiple FKs between detail and base.** A detail table with more than one FK to the base (or to a base
  unique key) leaves the inheritance join ambiguous; the author must be able to say which FK is the
  inheritance join.
- **No base-only inherited field to carry the reference.** When every inherited field is also physically on
  the detail (e.g. the shared-key columns), no base-only field's `@reference` names the FK, so the
  base-to-detail join has nothing to ride; a participant-level declaration of the inheritance join is
  needed.

R389's behaviour at this boundary is to **reject** the ambiguous / undeclared case at validate time with a
candidate-FK hint (a hard `INVALID_SCHEMA` author error); this item lifts those from "rejected" to
"supported".

Design constraints carried from R389:

- The declared path resolves at the parse boundary into the existing `JoinStep` / `JoinSlot` vocabulary
  through `ctx.parsePath` (the mechanism `@reference` already uses); the emitter keeps receiving an
  already-classified hop, never a raw `ForeignKey<?,?>` or a re-parsed string.
- One declaration should serve both roles where they coincide: naming the inheritance join used for the
  base-to-detail projection, and (when the same FK resolves a base-only inherited field) that field's
  resolution.

Open question for Spec: the directive surface. Does the participant's inherited-field `@reference(key:)`
serve double duty (resolve the field and name the join), with a participant-level declaration as the
fallback when no base-only field exists? Or a dedicated participant-level reference (a path argument on
`@table` / `@discriminator`, or a participant `@reference`)? Draft the user docs for whichever surface it
picks (first-client check), since it is user-visible authoring.

---
id: R278
title: "Polymorphic type classification: sealed union-type variants over ParticipantRef"
status: Backlog
bucket: structural-refactor
priority: 6
theme: interface-union
depends-on: []
created: 2026-06-04
last-updated: 2026-06-05
---

# Polymorphic type classification: sealed union-type variants over ParticipantRef

Resurrected. This item originally proposed classifying polymorphic types (interface/union)
"in field context" by enriching the `ParticipantRef` participant-list model. It was briefly
absorbed into R279 (`field-first-classification-driver`) as a set of `ParticipantRef`
tweaks, then pulled back out: the participant-list-with-role-flags shape is itself suspect,
and R279 is a behaviour-preserving driver restructure that should not also redesign
polymorphic classification. This item is re-scoped to that redesign and held at Backlog to
iterate the design.

## The suspected wrong primitive

`UnionType` today carries a `List<ParticipantRef>`, where `ParticipantRef permits
TableBound, Unbound` tags each member by whether it has `@table`. That shape has accreted
overloaded meaning and forces role logic into a flag-bearing list rather than the type
system:

- `Unbound` means two unrelated things at once: an `@error` member, and a directiveless
  plain-interface implementor. There is no `Error` participant kind, so error members ride
  the `Unbound` arm.
- `buildParticipantList` carries an `allowNonTableMembers` flag to gate whether non-`@table`
  members are admissible, which is really a property of *how the returning field populates
  the type*, not of the type or its participants.
- An all-`@error` union is conceptually an `ErrorType` (a type with `@error` is an
  `ErrorType`; a union of `ErrorType` is too), but it is classified as a `UnionType` with
  `@error` `Unbound` participants and special-cased downstream by
  `GraphitronSchemaClassGenerator`'s `isErrorUnion` fork plus a source-class `TypeResolver`.

The interface side already shows the better shape: `TableInterfaceType` and `InterfaceType`
are *distinct sealed variants*, not one type with a participant-role flag. The union side
never got the same treatment.

## Direction (to iterate)

Drop `ParticipantRef` in favour of sealed union-type variants, mirroring the existing
interface split:

- `TableUnionType` for SQL-polymorphism unions (every member table-bound, members read off
  a `__typename` column from a multi-table query).
- `ErrorUnionType` (or fold into `ErrorType`) for all-`@error` unions, carrying the
  per-member handlers keyed for the `TypeResolver` dispatch, so the emitter reads the shape
  off the variant instead of re-deriving it from an `isErrorUnion` special-case.

The vocabulary and the exact variant set are not pinned; the load-bearing idea is *sealed
variants over a participant-list-with-flags primitive*, consistent with "Sealed hierarchies
over enums for typed information" and the interface-side precedent.

Open threads to settle during Spec:

- **Population strategy as field-context validation.** Whether a non-`@table` member is
  legal depends on the returning field (SQL polymorphism requires all members table-bound;
  service/reflection dispatch admits non-`@table` members). With sealed union variants the
  type carries the intrinsic fact and the field/validator carries admissibility; this is
  where `allowNonTableMembers` retires.
- **Service/reflection-populated non-error polymorphic types are an unhandled gap.**
  `TypeBuilder.classifyType` never consults `bindings.resolveResult` for interfaces/unions,
  and the emitter has only three `TypeResolver` strategies (TableInterfaceType discriminator
  column; plain InterfaceType/UnionType `__typename` from a SQL result; `@error`-only
  source-class), none for a service/reflection-populated non-error polymorphic type (whose
  runtime objects carry no `__typename`). Spec must implement the strategy or reject the
  construct at validate time (the latter is the safe default per "validator mirrors
  classifier").
- **Mixed unions** (some members table-bound, some not, some `@error`): does the variant set
  cover them, or are they rejected? An axis the participant list tolerated implicitly.

## Relationship to R279

Orthogonal, and not a prerequisite either way. R279 preserves today's polymorphic
classification unchanged. This redesign most naturally *layers after* R279, since the
field-first walk gives field-context classification structurally (a field reaching a union
knows its population strategy), which is exactly the context the admissibility check wants;
but it can also proceed independently against the current driver. Sequencing is a Spec-time
call, not fixed here.

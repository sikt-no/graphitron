---
id: R1092
title: "An input field's @condition without override drops the field's implicit column predicate"
status: Backlog
bucket: bug
priority: 3
theme: codegen-correctness
depends-on: []
created: 2026-10-23
last-updated: 2026-10-23
---

# An input field's @condition without override drops the field's implicit column predicate

## Goal

An input field's `@condition` without `override: true` ANDs the authored method with the field's own
column predicate, as an argument's already does and as the manual says, so a filter like
`filmId: Int @field(name: "film_id") @condition(...)` narrows by both the method and `film_id`
instead of silently by the method alone. Today `FieldBuilder.walkInputFieldConditions` emits a
column-backed input field's implicit predicate only when the field has no `@condition` at all
(`FieldBuilder.java:3382-3387`), while classification still demands the column exist for the
predicate it then drops (`plainInput_overrideFalseWithoutMatchingColumn_stillFailsTheBuild`).

## What is known

* The drop is pinned by `GraphitronSchemaBuilderTest`'s `PLAIN_INPUT_ARG_FIELD_CONDITION_EMITTED`,
  which asserts one filter, the method, where the documented reading gives two.
* The manual (`condition.adoc`) and `docs/architecture/reference/argument-resolution.adoc` say the
  implicit predicate is kept and ANDed; an argument's `@condition(override: false)` keeps both.
* No behavioural test can tell the readings apart: sakila's four affected inputs
  (`FilmConditionInput`, `PlainFilmIdInput`, `InnerFilmInput`, `FilmMixedNodeIdConditionFilter`) use
  `filmIdCondition`, which returns the same `film_id` predicate the drop removes.
* No consumer graph reaches it: in opptak, sis, tilgangsstyring, utdanningsregisteret and kompetanse
  every input-field `@condition` sets `override: true` (stores captured 2026-10-22), which may be a
  habit this bug taught.
* Found writing `roadmap/audits/2026-10-23-argument-resolution-in-the-walk.md`, section 5.

## What a fix owes

* A case where the method and the implicit predicate select different rows, at the execution tier,
  so the behaviour is held rather than the shape.
* The classification case corrected to expect both filters, and the projection fixed to emit the
  implicit predicate unless an override (the field's own or an enclosing one) suppresses it.

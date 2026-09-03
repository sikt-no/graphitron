---
id: R915
title: "A producing field's result is a field-grain fact; it is unioned into a type-grain binding instead"
status: Backlog
bucket: architecture
priority: 3
theme: model-cleanup
depends-on: []
created: 2026-09-03
last-updated: 2026-09-03
---

# A producing field's result is a field-grain fact; it is unioned into a type-grain binding instead

A graph type is bound to a catalog table by carrying `@table`, and by nothing else. A field carrying
a producing directive has a result, and that result is a fact about the field. Where a producing
field's result reaches a type that is table bound, the two must agree; where it reaches a type that
is not, the result is simply what the type is backed by. Those are three separate statements and the
schema currently makes one relation out of the first two.

`intent_resolved_type_binding` is the union of `intent_bound_table`, which is the `@table`
population, and `intent_routine_return_binding`, which is where a field's `@routine` chain lands. So
a field-grain fact becomes a source of type-grain boundness, and fifteen views read the result
without any way to say which of the two they meant.

## What is wrong, precisely

**The grain.** A type can be reached by several producing fields, so the result is keyed by the
producing field and not by the type. Unioning it into a type-keyed relation loses that, and the
arity the union then computes is counting two different kinds of thing.

**The disagreement is reported as ambiguity.** A type carrying `@table(name: "film")` whose chain
lands on `films_for_actor` becomes two rows with `candidates = 2`. Eleven of the fifteen readers
filter `candidates = 1`, so the type silently becomes unbound. That is a compatibility violation
between a field's result and its type's binding, and it should be a named defect rather than a
disappearance. `ReferenceStepTargetTest` and `RoutineReturnBindingTest` pin today's reading.

**A routine's result is not a table.** It is a user-defined type that jOOQ models as a table so it
can appear in a query; unjoined it is a `Record`. `@service` and `@externalField` results are
likewise shapes that may or may not be a `TableRecord`. `intent_type_backing` argues the opposite in
its own comment, that a routine chain's return binding "stands for one exactly as a written `@table`
does", and that is the step to revisit.

**The producing directives have two partial spellings and no whole one.**
`intent_field_payload_producer` covers `SERVICE`, `DML` and `ROUTINE` and omits `@externalField`.
`intent_field_producer_reference` covers `@service` and `@externalField` and omits `@routine`. So
the set of directives that produce a field's value exists twice and completely nowhere, which is why
a reader wanting "what does this field produce" has no relation to ask.

**`@reference` moves where the result lands.** A reference step can pick a result up and join it
onward, so what reaches the type is the end of the chain rather than the raw call result. Any
remodelling has to keep that distinction rather than collapse it.

## What a fix looks like

One relation over all four producing directives at the coordinate grain, carrying what the field
produces. `intent_type_backing`'s table arm reading the `@table` population alone. The agreement
between a field's result and its type's binding stated as a defect, and the agreement between two
producing fields reaching one type stated the same way. Then `intent_resolved_type_binding` has
nothing left to be and its fifteen readers each say which question they were asking.

Two readers are known to be relying on the conflation and need the field grain instead rather than a
repointing: `intent_field_column_scope`'s `PARENT_BINDING` arm, which resolves a child column of a
routine-returned type through a type-grain binding that was never really a table binding, and
`intent_field_separate_fetch`, which asks whether a type is bound at all.

## Why it is not R876's

R876 is remodelling the type-to-table binding itself, and `graphitron_tabletype` is the `@table`
population captured with a key. That work needs none of this: the nodehood family reads the `@table`
arm or should be reading it, since `@node` only takes effect on a type that also carries `@table`.
This item is what the remaining union readers wait for, and it is a different subject.

## Measured

On the captured consumer schema (`sis-2026-08-31`): 635 types have a binding, **none** of them from
anything but `@table`, and there are **zero** `@routine` applications. The whole second arm is
fixture territory today, so this is a correctness and modelling item rather than a repair.

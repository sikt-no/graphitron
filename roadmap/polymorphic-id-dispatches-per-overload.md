---
id: R984
title: "A polymorphic id dispatches to the overload each member lands in, and the generated code widens each arm"
status: Backlog
bucket: architecture
priority: 2
theme: model-cleanup
depends-on: []
created: 2026-09-30
last-updated: 2026-09-30
---

# A polymorphic id dispatches to the overload each member lands in, and the generated code widens each arm

## Goal

**An author can take a polymorphic id into one overload per member, and the generated code calls
the right one.** A union's or an interface's members are unrelated results, so the best slot for a
decoded id is `assign(CustomerRecord)` beside `assign(StaffRecord)`, which hands each call a concrete
record. Today `BuildContext.admitPolymorphicSlotType` checks one parameter type against every member
and treats an overloaded producer as ambiguous, so that schema is refused. When this lands, the
generator dispatches on the decoded member to the overload the model names for it, and assigns each
arm's result to the field's shared type, because an overload usually returns the record that went in
and an author cannot be asked to widen it.

## What this rests on, and what rests on it

The model half is R876's branch "A polymorphic id resolves per member, to the overload that accepts
it": which overload accepts each member, the destination naming that overload per member, and the
refusal where the overloads differ in anything but the polymorphic parameter. This item reads those
rows and does not re-decide them.

What rests on this item is that branch's subtraction. The per-member supertype fit,
`intent_record_slot_assignable` with its `SLOT_NOT_SUPERTYPE_OF_MEMBER` verdict and
`sql_table_record_supertype`, can only go once the generator dispatches from the new rows.

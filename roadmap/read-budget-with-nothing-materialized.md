---
id: R899
title: "No relation a consumer reads refuses a five-second budget with nothing materialized"
status: Backlog
bucket: architecture
priority: 1
theme: model-cleanup
depends-on: []
created: 2026-08-31
last-updated: 2026-08-31
---

# No relation a consumer reads refuses a five-second budget with nothing materialized

The fact store keeps a register of twenty materialized relations. The item that examined it
established that the register is a diagnostic rather than a mechanism worth defending: every
registered target was a relation that had never said what one of its rows was about, and the fixes
that came out of asking why were grains, supertypes and stored join keys, not retirements. It shed
the register itself on the way past, and this item picks it up with the burden that item established.

**The burden runs the opposite way from how the register's own reasons read.** A registration does
not have to be shown wasteful before it goes. It has to be shown necessary, and necessary means the
rule underneath it is correctly modelled and still cannot be planned. Where it cannot, the answer is
capture writing the fact, an index on a stored column, or a rewrite, in that order.

**The target is a test rather than a count:** no relation in the consumer read set refuses a
five-second budget with nothing materialized. That is what makes `mvn graphitron:validate` and
`mvn graphitron:dev` usable against a real consumer schema and lets the dev loop reach its
language-server and MCP binds.

**What is already measured, and it says under-implemented rather than refuted.** Two arms on a kept
consumer store, both with statistics present, over the thirty-nine relations the generator, the
language server and the MCP server name. The register as it ships costs 43.0 s of refresh and 251.5 s
of reads, with one relation over budget at a hundred and twenty seconds. With nothing registered and
the two supertypes and two keys that landed, reads are a floor of 1054 s with seven over. So the
register wins the total today and this item should not pretend otherwise.

**What makes it still the right target is the shape of the difference.** One relation moves the right
way and it is the one the register was never able to fix: `intent_field_accessor_hop` refuses the
budget with all registrations in place, and with none of them plus a generated bean-property column
and one index it returns its 21 287 rows in 1.90 s. No registration can index an expression. Ten move
the wrong way, and three of them sit above `intent_argmapping_bound_parameter_type`, the six-arm
reconstruction of the one confirmed missing supertype nobody has captured. That is a prediction to be
held to. The other seven have no cause identified, and saying so is more useful than implying the
signature explains everything.

**Two questions inherited with it.** Whether `intent_spelled_table`'s registration is still priced
correctly now that the relation it reads has a grain, and the fifty-fold planner degradation observed
on `intent_resolved_type_binding`.

**And one that is deferred rather than open:** whether a rule earns a registration before anything
reads it. That question presumes registrations, and on this target it dissolves rather than being
answered. It is worth writing as the rule for whatever survives, once the survivors are known.

Measuring any of this needs a captured consumer store, which is not in this repository and must not
be. Refreshing and reading an already-captured one needs no consumer machine, which is what every
figure above rests on.


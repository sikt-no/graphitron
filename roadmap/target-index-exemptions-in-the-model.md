---
id: R827
title: "The registered targets index exemptions are a Java set in a test, so the measurement that argues each one is not in the model"
status: Backlog
bucket: model
priority: 4
theme: model-cleanup
depends-on: []
created: 2026-08-24
last-updated: 2026-08-24
---

# The registered targets index exemptions are a Java set in a test, so the measurement that argues each one is not in the model

`MaterializeRegistryGateTest` gates that every registered materialization target carries a
declared index, and exempts four of them through `NO_INDEX`, a `Set.of` of four relation names in
the test class. Beside it sits about forty lines of javadoc carrying the measurement that argues
each exemption: `intent_resolved_type_binding` takes `intent_argument_scope_table`'s source view
from 559 scans to 952 on the coordinate its thirteen readers join; `intent_errors_field` takes
`intent_carrier_routine_hop` from 3876 to 8136 and `intent_mutation_routine_seat` from 28857 to
33117, with no reader improving; `intent_carrier_data_field` moves no reader at all, to the scan.
Each figure was taken over every view whose derivation reaches the target, with statistics current
on both sides.

None of that is in the model. It is a fact about the store's physical shape, discoverable only by
a Java reader who already knows the file exists, unqueryable from a booted store, and unreachable
by the reference renderer. The schema states facts of exactly this kind about itself, and it
already has the convention: `meta_prefixless_relation` is a placement exemption roster with a
`reason` column, described in its own comment as being "in the exemption polarity the schema gates
use throughout, so a new prefix-less relation fails the roster gate until an authored row argues
it in", with the reason "rendered beside the relation in the reference". `meta_materialize` itself
carries a `reason` column for why each registration exists. The index exemption is the same kind
of claim as both, and it is the only one of the three that lives outside the DDL.

## Shape

A separate `meta_` relation keyed on the exempted relation, carrying the argument, not a column on
`meta_materialize`. The grain question was considered and settled: a nullable column is not the
form this schema takes for this, and a relation keyed on the exempted relation can later cover a
table that is not a registration target, which a column on the register structurally cannot.

The gate then reads the roster instead of holding its own set, and keeps the equality in both
directions it has today, so a target that later earns an index fails until its row goes rather
than surviving as an exemption nobody revisits.

## Boundaries

Two things sit near this and are not it.

`HAND_WRITTEN`, the other Java-side set in the same test, is a weaker case and is deliberately
left alone here. Its own javadoc says each rostered table "argues impossibility in its own table
comment", so the reason already is in the model and the Java set is a duplicated enumeration
rather than a fact with no home. Worth revisiting, on different grounds, and not on this item's
contract.

Moving the roster into the model does not by itself publish it. `SchemaReferencePages` renders
from `meta_family`, `meta_family_headline`, `meta_family_bridge`, `meta_relation_reference` and
`meta_prefixless_relation`, and does not read `meta_materialize`, so the register's existing
`reason` column renders nowhere today either. R758 is the item for that half. This item makes the
exemptions statable and gated in the model; whether they render is R758's contract, not this one's.


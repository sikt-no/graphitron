---
id: R841
title: "The write-payload refresh is the largest pair in the register"
status: Backlog
bucket: architecture
priority: 3
theme: classification-model
depends-on: []
created: 2026-08-26
last-updated: 2026-08-26
---

# The write-payload refresh is the largest pair in the register

`intent_mutation_payload_column` and `intent_mutation_payload_refusal` are both materialized targets,
and refilling the first costs about four seconds per graph on the read-cost gate's twelve-unit
fixture: 217,475 rows visited for one evaluation of `intent_mutation_payload_column_live`. Every other
registration in `meta_materialize` quotes a refresh in the single or double digits of milliseconds.
The capture that matters finishes: the sakila example build completes in under three minutes with both
registrations in place. But this pair is three orders of magnitude above everything else in the
register on the gate's fixture, and nobody has timed either refresh against a real schema in
isolation.

## What is already settled, so nobody re-litigates it

Written as plain views these two cost four seconds a read on the gate's fixture and did not terminate
at all on the sakila example schema. Both are fixed, and the fix in each case was a registration
rather than a rewrite. The figures live in `DerivedReadCostTest`'s javadoc, beside the gate that holds
them.

Three rewrites were measured before the first registration and all three refused: the occurrence cut
is not the cause (1961 milliseconds with it against 2002 without); an index on
`intent_resolved_type_binding` cut rows visited fivefold and made the clock worse (3900 against 5071);
and driving the two column arms from their own views made the whole rule an order of magnitude worse,
an inlined common table expression being re-evaluated per driving row of whatever sits outside it.

The one shape that did explain everything is a per-row probe into a derived relation. It cost four
seconds when it sat on the read side and non-termination when the first registration moved it onto the
refresh side, and it went away both times only by making the probed relation a table. That is worth
holding on to: whether such a probe shows up as a slow read or a slow refresh depends only on which
side of a registration it lands, and the gate's synthetic fixture understates it badly.

R839 is the same shape found independently at another site, a correlated `EXISTS` re-deriving a rule
once per driving row inside `intent_carrier_data_field_live`, and it is the stronger evidence of the
two because it is measured against a real consumer store rather than a scaled fixture. Read it before
this one. If a third instance of the probe turns up, the shape has earned a written rule of its own
rather than a third item.

A third registration has since landed in this family and the reading first written here about it was
wrong, so it is corrected rather than removed. `intent_mutation_payload_key_membership` was registered,
and this file said it was the ordinary breadth case, a cheap rule named too often. It was not. The rule
had the same defect one join further out: a derived relation on the inner side of a join, which H2
re-evaluates once per driving row. The registration priced before that was fixed cost 326 seconds of
refresh per capture, and priced after it costs 36 milliseconds. Both figures are of the same
registration, which is the whole point of recording it: what changed was the rule, not the lever.

## What this item is about

Whether four seconds of refresh is a real cost or an artefact of a fixture that is twelve repetitions
of a node cluster.

1. **Time all three refreshes against the sakila example schema.** Every other registration quotes its
   refresh there rather than against the scaled fixture. The key-membership registration has since
   joined the two this item was filed for and is refilled on the same cadence, so it belongs in the
   same measurement. If all three are milliseconds, this item closes with three figures added to the
   registrations' own comments.
2. **If the column refresh is real, the remaining expansion is the write payload.**
   `intent_mutation_payload_column_live` names `intent_mutation_write_payload` in its admitted set and
   the admitted set once per column-resolving arm, so the scope family is still expanded twice per
   evaluation. Whether that relation earns a registration of its own is a reader-count question the
   write destination relation will settle.
3. **An index on `intent_mutation_payload_column` is a separate question and not yet earned.**
   `MaterializeRegistryGateTest` carries a roster row saying why: the one current reader reads the
   target whole. The write destination will read it keyed by one mutation coordinate, and
   `(graph_name, type_name, field_name)` should be declared and timed at that point.

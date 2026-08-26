---
id: R839
title: "The carrier refresh costs 41 seconds per capture, and it is the producer CTE inlined per driving row"
status: Backlog
bucket: model
priority: 2
theme: model-cleanup
depends-on: []
created: 2026-08-26
last-updated: 2026-08-26
---

# The carrier refresh costs 41 seconds per capture, and it is the producer CTE inlined per driving row

`intent_carrier_data_field_live` takes 41 seconds to produce 151 rows against a real consumer store,
and the whole of it is one correlated `EXISTS` re-deriving a 172-row rule once per driving row.
Registering the relation that rule reads, `intent_field_payload_producer`, measures 0.3 seconds.

Unlike R781 and R830, which price relations nothing exercises at build time yet, this cost is being
paid now, on every capture, by every consumer whose schema reaches the carrier family. That is what
the priority reflects.

## What a capture pays today

Measured against a real captured store for a consumer schema of 8408 fields, 2345 types, 5619
catalog columns and 39 classpath sources, one graph, using the same `DELETE` plus
`INSERT INTO target SELECT * FROM source_view WHERE graph_name = ?` statements `Materializations`
itself runs:

| Registration target | One refresh |
|---|---|
| `intent_carrier_data_field` | 41.1 s |
| `intent_field_column_scope` | 6.4 s |
| `intent_errors_field` | 4.2 s |
| `intent_node_id_instruction` | 3.4 s |
| `intent_node_id_decode_hop_column` | 2.2 s |
| `intent_argument_scope_table` | 1.1 s |
| the remaining six | about 1.4 s combined |
| **total, one capture** | **59.9 s** |

`Materializations.analyse` is 23 ms of that and is not worth attention. The store measured carried
twelve registrations; the register has since grown, so the total is a floor rather than a current
figure.

## The cost is the expansion, and the term is identified

The children are all cheap, so there is nothing expensive underneath to reach for:
`intent_field_payload_producer` is 4 to 31 ms, `intent_bound_table` 2 to 21 ms,
`intent_type_backing` 3 to 15 ms, and `intent_errors_field` is a table and answers in under a
millisecond.

Bisecting the body puts the cost in one place. The whole view is 45.7 s, the bare
`producer` join `data_channel` with all three disqualification arms removed is 44.6 s, and the
`data_channel` CTE evaluated on its own is 44.4 s. So the three `NOT EXISTS` arms together cost
about 1.1 s and the CTE is the subject.

Inside that CTE the term is the population filter:

```sql
WHERE EXISTS (SELECT 1 FROM producer p
               WHERE p.graph_name = f.graph_name
                 AND p.payload_type_name = f.type_name)
```

`producer` is a non-recursive `WITH` naming `intent_field_payload_producer`, and H2 inlines such a
CTE exactly like a view with no common-subexpression elimination, so a correlated reference
re-derives the whole rule once per driving row of `graphql_field` joined to `graphql_type`. The
arithmetic closes: about 5 ms per evaluation against roughly eight thousand candidate rows is about
forty seconds, against a measured 41.

## Controls, including the one that refuted the obvious reading

Same store, same run, two sweeps each, `OPTIMIZE_REUSE_RESULTS` off:

| `data_channel` variant | Sweep 0 | Sweep 1 |
|---|---|---|
| as written | 44.9 s | 39.7 s |
| the `producer` CTE snapshotted into a table | 224 ms | 194 ms |
| all three probed relations snapshotted | 233 ms | 197 ms |
| only `intent_bound_table` and `intent_type_backing` snapshotted | 40.6 s | 40.0 s |

The last row is the one worth keeping. The CTE also probes those two views inside a `CASE WHEN
EXISTS`, which is where a reader following the plan's shape would look first, and substituting tables
for both changes nothing. Reading the body without pricing it would have named the wrong term.

## The lever, and which registration to land

Both candidate depths were measured on the same store:

* registering `intent_field_payload_producer`, which is already a named relation, leaving the CTE
  spelled as it is: 327 ms and 261 ms;
* promoting the `producer` CTE itself to a first-class relation and registering that: 253 ms and
  206 ms.

The deeper option buys about 60 ms more and costs a new relation with a name and comments, so the
shallower one is the registration to land and the difference belongs in the `reason` as measured
follow-up rather than in this change.

The trade the middle rung has to win is a refresh against the re-evaluations it avoids, and here it
is not close. One evaluation of `intent_field_payload_producer` is 4 to 31 ms. It has two readers in
SQL: this correlated probe, and `intent_field_error_channel`, which drives from it in a plain `FROM`
and therefore already pays exactly one evaluation and is indifferent to the registration.

## What landing it touches

The cheap registration shape, so the mechanics are the established ones and the gates will name them
one build at a time. Expect all of it in one pass: the view keeps its text under
`intent_field_payload_producer_live` and needs its own view and column comments in the established
form; a table takes the canonical name every reader already spells and inherits the original
relation's comment plus the standard materialization note; the new `_live` view joins the
agreement-source list in `FactCaptureAgreementTest`; and the `meta_materialize` row carries the
arithmetic above in its `reason`. The agreement test fails a full build and not a scoped one, so
this needs a verification build rather than a `-pl` run.

`DerivedReadCostTest` pins its matrix by equality, so adding a registration puts new cells in the
domain and fails that test until the figures have been looked at. That is the gate working; budget
for it rather than being surprised by it.

## The carrier's own reason row is wrong, and this change should say so

`intent_carrier_data_field_live`'s registration prices its refresh at about 170 ms for 15 rows on the
sakila example and about 12 ms on a carrier-free schema. The relation this item is about is the
reason that figure does not transfer: on a consumer schema the same refresh is 41 s. The fact model
page is explicit that a recorded measurement is evidence about the schema it was measured on and that
a stored reason contradicted by a later measurement needs correcting where it lives, so the row
should be corrected in the same change that moves the number, with both figures and the schema each
was taken on.

Two sibling rows are wrong the same way and are deliberately out of scope here, because re-pricing
the register's recorded claims is R831's subject rather than this item's: `intent_errors_field_live`
records about ten milliseconds against a measured 4.2 s, and `intent_field_column_scope_live` records
about 170 ms "on a real schema" against a measured 6.4 s.

## Not in scope

Why a registration can land with an unpriced refresh at all. `DerivedReadCostTest` holds the read
side only, in scan counts, over a twelve-unit synthetic fixture, and states outright that it asserts
no duration anywhere; `MaterializeRegistryGateTest` closes the register against the schema and asks
nothing about cost. So nothing prices the side every consumer generate pays. That is an architectural
question about what a registration must prove before it lands, it wants its own Spec cycle, and it is
filed separately.

## How to re-take these figures

Every number above comes from the procedure in the `store-performance` skill, against a store a real
build had already written rather than a fixture, driven from single-file JDBC programs over the H2
version the root pom pins. Nothing here was read off a reactor wall clock, a thread dump or a
profiler frame. The relation timings are `INFORMATION_SCHEMA.QUERY_STATISTICS` rows or direct
statement timings under `OPTIMIZE_REUSE_RESULTS FALSE`; the totals are the real refresh statements.
The one figure taken at a single execution is the per-registration table in the first section, which
ranks and is provisional in its tail; the 41 s subject was measured six times across four programs
and ranged 39.7 s to 49.1 s, the high end taken while a second probe shared the machine.

---
id: R819
title: "The carrier data field read tripled and the seat put it on every generation"
status: Backlog
bucket: store
priority: 2
theme: mutation-write
depends-on: []
created: 2026-08-24
last-updated: 2026-08-24
---

# The carrier data field read tripled and the seat put it on every generation

Reading `intent_carrier_data_field` against a real capture costs about half a minute, and the
R682 slice-one commits multiplied that cost onto the build's hot paths: the new
`intent_mutation_routine_seat` names it four times and costs about 43 seconds per read,
`intent_carrier_routine_hop` drives from it (about 10 seconds), and `RoutineWriteCommands`
reads the seat relation once per generation, so the sakila example's generation and every
routine-carrying pipeline test now pay these reads. The relation answers 15 rows in every
measurement below; this is a cost regression, not an answer change.

## What was measured

The probe methodology is the one the node-id decode regression item established: the sakila
example's own schema captured against the sakila catalog (`CapturedStore.ofCatalog`), timing
one read per relation inside one capture. Same-fixture controls ran the view's old and new
body texts as raw queries against the same captured rows, with suspect children snapshotted
into plain tables where named.

Per-relation, current trunk (`4b9ddcea9`):

| relation | rows | ms |
|---|---|---|
| `intent_carrier_data_field` | 15 | 48569 |
| `intent_mutation_routine_seat` | 5 | 43311 |
| `intent_carrier_routine_hop` | 2 | 10291 |
| `intent_field_error_channel` | 19 | 5299 |
| `intent_field_chain_start` / `_node` / `_terminus` | 18 / 24 / 18 | 1 / 14 / 11 |
| `intent_poly_member`, `intent_field_payload_producer`, `intent_errors_field`, `intent_errors_field_member` (each in isolation) | 50 / 129 / 15 / 27 | 10 / 16 / 16 / 45 |

Same-fixture controls on `intent_carrier_data_field`'s body (one store, one run, warm):

| body variant | ms |
|---|---|
| new body as shipped | 31798 |
| old body, from before `a5f5ad1a3` | 14922 |
| new body with `intent_field_payload_producer` snapshotted to a table | 33767 |
| new body with `intent_errors_field` snapshotted to a table | 9587 |
| new body with both snapshotted | 8508 |

## What the controls say

- Every child of the expensive relations is cheap in isolation (10 to 45 ms), so the cost is
  re-evaluation, not an expensive term: the shape the fact-model page names as view inlining
  with no common-subexpression elimination.
- `a5f5ad1a3` ("the error channel becomes facts") roughly doubled to tripled the read
  (15 s to 32-48 s). The convicted term is the promoted `intent_errors_field`, which now
  probes `intent_poly_member` per driving field row; that view carries a `ROW_NUMBER() OVER`
  on its interface arm, so no outer predicate prunes it and each correlated probe evaluates it
  whole. The producer promotion is exonerated by its control (33.8 s, within noise of as-is).
- The pre-existing 15 s belongs to the body's own tail: the windowed `data_channel` CTE is
  named four times (one join, three correlated `NOT EXISTS`), and each naming re-evaluates it
  over every object-type field. That shape predates R682 slice one; what slice one added is
  the readers that made it hot (`1f260e67f` seat, `4b9ddcea9` generation-path read).
- Snapshotting `intent_errors_field` alone brings the new body under the old one
  (9.6 s vs 14.9 s), so the promotion done as a materialized relation is an improvement
  rather than a rollback candidate.

## What was not established

`intent_node_id_decode` measured 24.4 s here where the decode regression item recorded about
13 s on `37c5814`. Different session, so per the store-performance discipline the pair is
suggestive, not evidence; whether something after `37c5814` regressed the decode read is open.

## Candidate levers, in the registry's order

1. Register `intent_errors_field` in `meta_materialize`: refresh is one 16 ms evaluation per
   graph, it has four naming view bodies plus the LSP, and the control above prices the win
   (32 s to 9.6 s on the carrier read alone, before its effect on the seat, hop and error
   channel reads).
2. Consider registering `intent_poly_member` beside it (10 ms refresh, three readers, and it
   is the window-carrying term the errors view multiplies through).
3. The residual 8.5 s is the `data_channel` four-fold expansion inside
   `intent_carrier_data_field`; whether that earns the relation its own registration (seven
   naming bodies plus two runtime readers) or a restructure is this item's design question,
   and the answer decides what `intent_mutation_routine_seat` costs.

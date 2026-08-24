---
id: R826
title: "intent_node_id_instruction costs 26 seconds per evaluation, and the fix is stranded on a quickfix branch"
status: Backlog
bucket: model
priority: 2
theme: model-cleanup
depends-on: []
created: 2026-08-24
last-updated: 2026-08-24
---

# intent_node_id_instruction costs 26 seconds per evaluation, and the fix is stranded on a quickfix branch

`intent_node_id_instruction` is named by three view bodies, and one of them, the decode slot,
names it twice through a local alias that is itself named twice, so a single read of the slot
relation expands to several whole evaluations of the rule. Measured against a consumer schema
with 95 classpath sources and a 388-row catalog census, one evaluation is 26 seconds while every
relation it reads answers in under a second: the cost is internal to one union arm, which joins a
local alias to a second local alias derived from the first. H2 inlines a non-recursive `WITH`
exactly like a view and eliminates no common subexpression, so the inner alias is recomputed once
per driving row. What it costs downstream is the point: the decode slot relation does not answer
in 400 seconds, and the decode defect view a build reads sits directly on it, so a consumer with a
schema this size has a build that does not finish.

The fix exists and is not on trunk. It sits on `quickfix/10.0.0-RC34`, which carries three commits
trunk does not have, on a history whose merge base with trunk is now 100 commits back.

## What is stranded

1. Materialize the relation, on the registry's own mechanism: rename the rule to
   `intent_node_id_instruction_live`, declare the canonical name as the target table with its
   column and table comments, and add the `meta_materialize` registration carrying the
   measurement. With the rows stored, the decode slot relation answers in 278 ms.
2. Register `intent_node_id_instruction_live` in `FactCaptureAgreementTest`, which the first
   commit missed.
3. Key `DevExecuteExecutionTest.query_throughTheExecutor_matchesDirectInAppExecution` on films the
   class can name rather than on an unfiltered root field. The subject is byte-equality between
   two execution paths, which an unfiltered field states no better; on the local-db path every
   class in the module shares one PostgreSQL instance and classes run concurrently, so a sibling's
   rolled-back insert is visible to one of the two reads and not the other. The module's
   `junit-platform.properties` already states that hazard, and this is the reader half of its
   remedy. Independent of the first two, and worth taking whatever happens to them.

## What the port costs beyond the three commits

The branch predates three trunk gates, so this is not a clean cherry-pick.

- Two conflicts, both keep-both: trunk and the branch each appended a registration at the same
  point in the `meta_materialize` INSERT list, and each added a line at the same point in
  `FactCaptureAgreementTest`. Trunk's additions since the merge base are
  `intent_field_reference_step_hop_live`, `intent_errors_field_live` and
  `intent_carrier_data_field_live`.
- `MaterializeRegistryGateTest.everyTargetIsIndexedOrStatesWhyNot` asserts by equality in both
  directions that every registered target carries a declared index or sits on the exemption
  roster. The stranded DDL declares neither. All three readers drive from this relation and join
  outward, so they scan it whole and never probe it by key, which means no index shape is a
  candidate and the exemption is the honest answer. Note that this entry's argument differs in
  kind from the four already rostered: those are indexes measured and rejected, this one is a
  reader shape that admits no index at all.
- `DerivedReadCostTest.theDomainIsTheSizeThisTestStates` pins `CELLS` at one per (registration,
  reaching relation) pair, deliberately, so that a new registration fails until somebody has
  priced its cells. Pricing them is the real work in this item; `KNOWN_NON_MONOTONIC` and
  `KNOWN_EXHAUSTED` may also gain rows.
- The registration comment states measured figures as fact, and they were measured before trunk
  declared five new indexes, one of whose comments names this relation as a reader. The internal
  cost is a per-driving-row recomputation that no index on an input touches, so the fix should
  still be warranted, but the figures want re-measuring against the tree they land in rather than
  being carried across.

Nothing else needs a hand edit. `meta_materialize_dependency` is derived at boot by
`MaterializeDependencies`, the family rosters are prefix-derived, and the reference pages render
from `meta_` rows rather than from an authored enumeration. The target table's column list already
matches the view name for name in order.

## Alternatives the branch already ruled out

Recorded so the Spec does not re-run them: widening the inner alias to carry the outer one's
columns measured 94 seconds, driving the arm from the inner alias measured 66 seconds, and
spelling the join's null-safe comparisons as `IS NOT DISTINCT FROM` changed nothing. Snapshotting
the inner alias into a table put the arm at 0.7 seconds, which is why this is a registration
rather than a rewrite.

## The narrower registration this defers

Refresh here is one evaluation of the rule per capture, the most expensive refresh in the
registry. The registration that would cut it is the inner alias rather than the whole rule, and
that alias is a local one today; promoting it to a named relation is what would make it
registrable. Out of scope here, and worth its own item once this lands.


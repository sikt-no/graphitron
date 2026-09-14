---
id: R943
title: "A dev round on a consumer schema answers in seconds: three registrations are over an hour of the refresh pass and eleven others are six seconds"
status: Backlog
bucket: dx
priority: 1
theme: dev-loop
depends-on: []
created: 2026-09-11
last-updated: 2026-09-14
---

# A dev round on a consumer schema answers in seconds: three registrations are over an hour of the refresh pass and eleven others are six seconds

## Goal

A `graphitron:dev` round on a real consumer schema answers in seconds. On the `sis` consumer it does
not: the refresh pass is dominated by three of its twenty-one registrations, which together run for
over an hour, while eleven of the others cost six seconds between them. The cost is concentrated
rather than spread, so this item is the pricing and the fix of those three, not a broad campaign
against the register.

Two terms, glossed once. The *fact store* is the H2 database each generator pass captures the schema,
the jOOQ catalog and the classpath into. A *registration* is a row of `meta_materialize` that keeps a
derived rule in a view under a `_live` name and moves the canonical name onto a table the capture
refills once per pass; the *refresh pass* is that refill, and a dev round pays it at boot and again
after every save.

## What the pass costs

Measured 2026-09-14 on the `sis` consumer's `sis-graphql-spec` module, by `mvn graphitron:dev -X`,
which prints one line per registration before its statements and one after. These are `done in` lines
from a real capture rather than a standalone ranking, which is the distinction R939 paid for twice
and which this item keeps: a timing taken outside the transaction that has just written the relations
a rule reads is not what a round pays.

On the in-transaction cadence, `Materializations.refresh`, against a warm store:

| registration | time | rows |
|---|---|---|
| 1 to 11 (eleven registrations) | 2.9 s at worst, about 6 s in total | 85 to 11183 |
| 12 `intent_node_id_instruction_live` | 386.1 s | 683 |
| 13 `intent_input_field_filter_role_live` | 2076.0 s | 1765 |
| 14 `intent_node_id_decode_hop_live` | over 30 min, stopped before it returned | not reached |
| 15 to 21 | not reached | |

Two readings the table carries. The output is tiny where the time is large: 0.57 s per row produced
at registration 12 and 1.18 s per row at registration 13, which is re-evaluation per driving row
rather than volume. And the dearest is not the one this item previously named as its first target:
`intent_input_field_filter_role` is on the list, but at roughly ninety times the figure the item was
carrying, and two node-id relations sit beside it that the item did not name at all.

## The cost is the shape, not the planner's statistics

`FactCapture` picks its refresh cadence on whether the store already held a graph: a cold store gets
`Materializations.refreshAnalysing`, which commits and analyses per registration, and every later
capture gets `Materializations.refresh`, which runs the pass inside the capture's transaction and
analyses nothing. The analysing path exists because a pass planned with no selectivity on what it
reads is, in that method's own words, hours rather than a factor, so the cadence is the first thing
that looks like an explanation for these figures.

It is not the explanation, and the control says so on the same population. A cold boot, taking the
analysing path, produced identical row counts at every registration and ran registration 12 in
**470.1 s against the in-transaction path's 386.1 s**, which is 22% slower rather than faster;
registration 13 was still running past 21 minutes when the observation stopped. So neither the
cadence nor the statistics it supplies is the multiplier, and a fix aimed at either buys nothing.
The remaining candidate is the shape of the rules themselves, which is where the plan below goes.

## Where the cost multiplies through

Registrations 12 and 13, the two priced in full, reach one chain that no other registration's view
tree reaches at any depth:

```
intent_node_type -> intent_inferred_node_type -> intent_node_metadata_defect
```

`intent_node_metadata_defect` is the bottom of it, and carries the shape a stack sample of the stuck
statement showed: ten union arms and two correlated `EXISTS`, expanded once per path through the
views above it. Registration 14 does not read that chain; it shares
`intent_argument_reference_step_hop` with registration 12 instead.

That makes `intent_node_metadata_defect` the first candidate under the rule the fact-model page
states, which is to materialize the relation the cost multiplies *through* rather than the one that
looked slow from where the measurement started. It is a candidate and not yet a verdict: the chain
has to be timed relation by relation, and the body of whatever proves expensive bisected, before any
lever is chosen. R876's own finding across every relation it measured is that the lever was a stored
key and an index rather than a registration, since no registration can index an expression, and this
item should expect the same answer before it reaches for a twenty-second row of `meta_materialize`.

## What is now settled

The reading this item owed R876 is taken. R939's step 1 named a slice that never ran,
`intent_argument_scope_table` alone and its row count against `intent_field_scope_table`'s, the
question being whether crossing the field scope with the field's arguments widens the seed on a
consumer population. It does not: on `sis` the argument relation holds 968 rows against the field
relation's 2606, and refreshing it costs 5 ms. Whatever else that wrong-grain case is, it is not a
cost on this consumer, and this item does not carry it further.

## What this item is not

It is not a wall-clock gate; nothing in this repository captures a schema of the size that exposes
this, and a fixture that did would be the build-wall-clock item's. It is not R857, which makes a
round refresh only what the edit touched and so attacks how often the cost is paid rather than what
it is. It is not R876, which is the ownership and shape argument the register sits inside, and which
states of itself that the refresh is not the cost it claims. Those three and this one can all land
independently, and this one is the only one whose unit of account is the pass's own wall clock on a
consumer population.

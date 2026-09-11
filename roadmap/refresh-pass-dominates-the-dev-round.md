---
id: R943
title: "A dev round on a consumer schema answers in seconds: the refresh pass is 53 s warm and one registration is 22 to 24 s of it"
status: Backlog
bucket: dx
priority: 1
theme: dev-loop
depends-on: []
created: 2026-09-11
last-updated: 2026-09-11
---

# A dev round on a consumer schema answers in seconds: the refresh pass is 53 s warm and one registration is 22 to 24 s of it

## Goal

A `graphitron:dev` round on a real consumer schema answers in seconds. R939 took the round from
never finishing to finishing and left this: on the `sis` consumer the refresh pass alone is 53 s
warm and the capture as a whole is 3:33 min cold and 4:27 min warm, so the loop is usable and still
not fast. That item's goal sentence claimed the round, its measurement closed only its own read, and
the gap is this item.

Two terms, glossed once. The *fact store* is the H2 database each generator pass captures the schema,
the jOOQ catalog and the classpath into. A *registration* is a row of `meta_materialize` that keeps a
derived rule in a view under a `_live` name and moves the canonical name onto a table the capture
refills once per pass; the *refresh pass* is that refill, and a dev round pays it at boot and again
after every save.

The measurement that exists already names the first target. The largest single term in the pass is
`intent_input_field_filter_role` at 22 to 24 s, which is roughly twice the next dearest and was the
dearest registration in the register before R939 added one. It landed under R682 and nothing has
priced it since. That figure is a `done in` line from a real capture, not a standalone ranking, which
is the distinction R939 paid for twice and which this item inherits: a timing taken outside the
transaction that has just written the relations a rule reads is not what a round pays.

What this item is not. It is not a wall-clock gate; nothing in this repository captures a schema of
the size that exposes this, and a fixture that did would be the build-wall-clock item's. It is not
R857, which makes a round refresh only what the edit touched and so attacks how often the cost is
paid rather than what it is. It is not R876, which is the ownership and shape argument the register
sits inside. Those three and this one can all land independently, and this one is the only one whose
unit of account is the pass's own wall clock on a consumer population.

One reading is owed to R876 and was not taken when it could have been. R939's step 1 named a slice
that never ran: `intent_argument_scope_table` alone, and its row count against
`intent_field_scope_table`'s. That relation is one of R876's three measured wrong-grain cases,
`intent_field_scope_table` crossed with the field's arguments and nothing else, and whether the cross
widens the seed on a consumer population has never been measured. It is one count on a store a
session with the consumer clone already has open.

---
id: R882
title: "A nullable input field can be the sole supplier of a matched-key column on UPDATE, and an omitted value then has no diagnostic"
status: Backlog
bucket: validation
priority: 4
theme: mutation-write
depends-on: []
created: 2026-08-31
last-updated: 2026-08-31
---

# A nullable input field can be the sole supplier of a matched-key column on UPDATE, and an omitted value then has no diagnostic

`UpdateRowsWalker` matches a primary or unique key against the columns the input covers, and then routes every carrier whose columns are wholly inside that key to the WHERE partition. It never asks whether those carriers are optional. So an UPDATE input can identify its row through a nullable field, and the resulting fetcher has no answer when the caller omits it: the WHERE clause is built at generate time and its value read is a decode local over `in.get(name)`, which yields a confusing "Decoded NodeId did not match the expected type" for an omitted `@nodeId`, and a `WHERE col = NULL` predicate matching no row for an omitted plain field. Neither says what the caller actually did wrong, and the second is silent about it.

R880 states the rule for the one shape it had to settle: an optional field cannot be load-bearing identity, so a nullable *straddling* reference is rejected when it is the sole contributor of a matched-key column. That reasoning does not depend on straddling at all, and the wholly-in-key carrier is the same hazard one step over. What this item owes is the generalisation and its blast radius: a rejection over every carrier whose columns are wholly inside the matched key and whose SDL field is nullable would fail schemas that build today, so the item has to measure how many, decide whether the diagnostic replaces the confusing runtime error or precedes it, and say what an author whose identity really is optional should write instead.

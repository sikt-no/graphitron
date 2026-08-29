---
id: R878
title: "The node id decode hop column drops the foreign key it walked, so nine distinct hops become nine identical rows and arity states a false number"
status: Backlog
bucket: bug
priority: 1
theme: codegen-correctness
depends-on: []
created: 2026-08-30
last-updated: 2026-08-30
---

# The node id decode hop column drops the foreign key it walked, so nine distinct hops become nine identical rows and arity states a false number

`intent_node_id_decode_hop_column` resolves a hop between two tables into the pair of columns that
join them. It projects the column names and drops the constraint and the tables the hop went between.
Where several foreign keys join different tables using the same column names, the rows those hops
produce become indistinguishable, and the relation ends up holding the same row many times over.
Everything above it then counts those repeats as if they were separate facts.

## The worked case

A filter input over person roles, `Query.personroller(filter)/fsRoller` on a real consumer schema.
`intent_node_id_decode_hop` holds nine rows for that coordinate, correctly: nine foreign keys, from
`EMNEROLLE`, `KLASSEROLLE`, `KULLROLLE`, `KURSROLLE`, `ORGANISASJONSENHETSROLLE`,
`STUDIEPROGRAMROLLE`, `TIMEPLANROLLE`, `UNDERVISNINGSAKTIVITETSROLLE` and
`UNDERVISNINGSENHETSROLLE`, each pointing at `ROLLE`. All nine join on the same two column names,
`INSTITUSJONSNR_EIER` and `ROLLEKODE`.

The hop column relation keeps the column names and drops which foreign key produced them, so those
nine rows arrive as nine identical output rows. On the same capture:

[cols="4,2,2"]
|===
| relation | rows | distinct rows

| `intent_node_id_decode_hop_column` | 1078 | 1009
| `intent_node_id_decode_column` | 1559 | 1114
|===

Per coordinate the multiplication is much larger than those totals suggest. At the coordinate above,
`intent_node_id_decode_column` holds 162 rows of which 2 are distinct. Six other coordinates are the
same shape: 64 against 4, 40 against 5, 28 against 7, 8 against 2, 6 against 3.

## What it currently breaks

**`intent_node_id_decode.arity` states a false number.** It is `COUNT(*)` over
`intent_node_id_decode_column` partitioned by graph and use site, so the node identity at that
coordinate is described as having 162 key columns where it has 2.

**Nothing emitted carries that number today.** `intent_node_id_decode` is named in javadoc and in
tests; no main source reads its `arity`. The generator consumes `destination`.

**`destination` survives, but by accident.** It is `lifted = positions`, and at all seven affected
coordinates every row carries a non-null lifted column, so the duplication inflates both sides
equally and the comparison lands where it would have. Nothing in the rule makes that proportional.
One duplicated row with a null lifted column at any of those coordinates flips `destination` from
own-table columns to target-table columns, and that value is consumed. This is the reason the item is
filed as a bug rather than as cleanup.

## Why `DISTINCT` is the wrong fix

It looks like a missing `DISTINCT` and it is not. The nine rows entering the projection are nine
different facts, one per foreign key. Collapsing them at the top would delete eight real hops and
hand any reader that needs to know which table it hopped from one arbitrary answer. The duplication
is information already lost by the time it is visible, so a fix that removes the visible copies
removes the evidence rather than the defect.

## The decision this needs

Two grains are available and the item cannot pick between them from cost evidence:

**The grain is the column mapping.** Nine foreign keys that map the same columns are one fact about
how to decode, so the collapse is correct and belongs where it happens, not as a `DISTINCT` bolted on
above. Right if a consumer only ever needs the columns to decode a node id.

**The grain includes the foreign key that was walked.** Then the constraint and the two tables belong
in the projection, rows become distinct, and the four relations above inherit a wider one. Right if
any consumer needs to know which branch of a polymorphic filter it is on.

What decides it is what a consumer of a multi-table node id decode actually generates: one decode
shared by every branch, or one per branch. That is a question for whoever owns node id decoding, and
answering it is the whole of this item.

## Reproducing

Any store with a captured consumer schema that has a polymorphic filter over several tables sharing
a foreign key column naming. Compare `count(*)` against `count(DISTINCT (all columns))` on the two
relations named above, then group by `use_site` to see the per-coordinate multiplication.

## Not in this item

The other eighteen materialization targets. A separate item measured all twenty and found these two
are the only ones holding duplicate rows; the rest have a key available and need no remodelling.


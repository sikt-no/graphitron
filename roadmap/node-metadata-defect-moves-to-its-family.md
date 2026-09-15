---
id: R952
title: "The node-metadata defect rule is the catalog family's, and it mixes two grains that a declared table cannot carry"
status: Backlog
bucket: architecture
priority: 2
theme: model-cleanup
depends-on: []
created: 2026-09-15
last-updated: 2026-09-15
---

# The node-metadata defect rule is the catalog family's, and it mixes two grains that a declared table cannot carry

## Goal

`intent_node_metadata_defect` stops being re-evaluated per driving row by the five sites that
anti-join it, and stops being filed in a family whose own rule says it does not belong there. A
*grain* here is what one row of a relation is about, and the fault is that this relation's rows are
about two different things.

## Why it is a successor rather than part of the item that found it

R943 planned this as its lever 2, on the ownership rule: a view reading one family is a view of that
family, owned by that family's gatherer, and this one reads `sql_node_metadata`,
`sql_node_key_column` and `sql_column` and nothing else. That reasoning is unchanged and still
right. What changed is the cost case. R943's lever 1 re-sourced the nodehood membership view onto
captured tables, and after it no registered rule reaches this relation at all: its only readers are
`intent_inferred_node_type`, `intent_resolved_node_type_id` and `intent_resolved_node_key_column`,
and none of the three is in any registration's tree. So the move no longer touches the refresh pass,
which was R943's unit of account. It touches the detection pass, where
`DetectionReadReachGateTest` records the reach and where the cost is still paid on every dev round.

The breadth the move is worth is stated: `intent_resolved_node_type_id` at 23 instantiations per
read and `intent_resolved_node_key_column` at 24, against 11 and 12 once this lands. Both figures
are checkable today with `mvn -pl roadmap-tool exec:java -Dexec.args='report-inline-multiplicity .'`.

## The two facts that make this more than a rename

**It mixes two grains, and that is why it cannot be one declared table.** Eight of its ten defect
values are about a whole constant and sit at `sql_node_metadata`'s grain; the other two are about
one entry of the key-columns array and sit at `sql_node_key_column`'s. The `position` column is
therefore NULL on eight rows out of ten, which its own comment defends as a stated absent bucket.
A base table cannot carry that: `MetaDeclarationGateTest.aDeclaredTableKeyMatchesItsGrain` requires
a primary key equal to the grain's key shape, a primary key admits no NULL, and the frozen
undeclared roster only shrinks so a relation arriving under a new name cannot stand on it
undeclared instead.

A sentinel is the wrong answer and the schema says so in the one place it carries sentinels in a
key: `javac_diagnostic` transcribes javac's own `-1` and `"(no source)"`, and the same paragraph
records that the SDL-toolchain arm beside it normalises graphql-java's `(-1, -1)` to NULL rather
than adopting a second convention. A store-minted sentinel has no precedent here, and a row keyed
on position `-1` asserts something about a position that is not one.

The shape that follows is a split, one relation per grain: `sql_node_metadata_defect` keyed on the
metadata row and the defect, and `sql_node_key_column_defect` keyed on the entry and the defect,
each carrying a foreign key it can actually declare. The five reading sites each gain a second
anti-join, which is two primary-key seeks against base tables in place of one scan of a ten-arm
union. Whether the conjunction those five sites then spell should itself become a relation is the
question `intent_inferred_node_type`'s comment already raises and R943 declined; it is worth
reopening here, because half of why it was declined was that filing it under `intent_` reproduced
the very fault this item fixes.

**The stored fold that was proposed alongside it should not be taken.** R943's lever 2 proposed
respelling the `KEY_COLUMN_UNRESOLVED` arm's `UPPER(...) = UPPER(...)` disjunction against the
stored `column_name_upper` and `jooq_name_upper` columns. The fact-model page has already ruled on
this exact comparison, naming this relation, in its case-fold section: both sides are values the
crawler produced, so the fold is a hedge rather than a semantic, and "it is a fold nothing owes
anything to, and it goes away by becoming exact rather than by being stored". Storing it is the
opposite rung and would pin the fold as a semantic with a test. Making it exact is a behaviour
change, a class stating `FILM_ID` against a column named `film_id` starting to draw a defect row,
so it is this item's to take deliberately or to decline, not something to slide in beside a move.

## What this item is not

It is not R943, which is the refresh pass and which has landed the three levers that touch it. It
does not make the relation cheaper by registering it: the catalog moves when a consumer regenerates
jOOQ where the SDL moves on every keystroke, so a catalog-owned table is refreshed almost never
where a register row would be refreshed on every save, and that cadence difference is the whole
reason this is an ownership move rather than a twentyfourth registration.

## Tests

Not settled until this reaches Spec. What is already known to be owed: the two new relations keep
the view's rows by equality against it, per grain; `NodeMetadataDefectTest` moves to the split
shape; `MetaDeclarationGateTest` gains two `meta_relation` rows and two `meta_grain` rows, both
grains in the `catalog` corpus so the owner gate passes; `FactCaptureAgreementTest`'s
oracle-lifecycle gate holds the new stage to clearing exactly its own partition, which is per
catalog source and not the per-graph delete the sibling nodes stage performs; and
`DetectionReadReachGateTest`'s pin moves for the two components that reach the relation.

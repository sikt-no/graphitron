---
id: R968
title: "The partition declaration's cost claim has no fixture evidence left"
status: Backlog
bucket: tech-debt
priority: 3
theme: testing
depends-on: []
created: 2026-09-23
last-updated: 2026-09-23
---

# The partition declaration's cost claim has no fixture evidence left

## Goal

Decide, on evidence, whether the partition dimension's declared selectivity still earns its place,
and leave whichever answer is true holding a measurement rather than a memory. The declaration is a
statement the model makes about one column's distribution so a pass planning inside a transaction,
where no `ANALYZE` can run, reaches an index it would otherwise price badly. Its cost justification
used to be a control in `PartitionSelectivityWorthTest`: the same read visited 10939 rows without
the declaration against 1113 with it. Three conversions have since put primary keys on the relations
that reader joins, each key giving the planner a seekable ordering it can reach without being told
anything, and the gap closed to twelve rows in 524. The control was retired rather than propped up
with a lower floor, so the file now records a collapse and the declaration's remaining evidence is
plan text in `RefreshPlanStatisticsTest` plus one consumer-scale round taken on a shape that no
longer ships.

That is a real gap and it points both ways, which is why this is a question and not a repair. The
fixture understates by construction: a per-driving-row partition scan is linear in driving rows and
a twelve-unit fixture is precisely where such a scan is cheap, so the declaration may well still be
worth a great deal at consumer scale with nothing here able to see it. Equally it may have been
made redundant by the keys, in which case a declaration nobody can measure is complexity the model
is carrying for a defect it already fixed another way. Landing this means either an instrument that
has signal again, on a fixture sized to where the cliff lives or on a reader whose keys do not reach
it, or a decision to retire the declaration with the reasoning written down. What must not survive
is the current state, where the claim stands on a measurement whose subject has moved.

